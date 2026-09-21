package com.skarbonka.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Skaner paragonow: robisz zdjecie przyciskiem, tekst jest odczytywany w telefonie (ML Kit, offline),
 * a samo zdjecie nigdy nie jest zapisywane - zyje tylko w pamieci do czasu odczytu i od razu jest usuwane.
 * Do apki wraca wylacznie tekst: sklep, suma, data, produkty z cenami i pelny tekst paragonu.
 */
class ScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LANG = "lang"
        const val EXTRA_RESULT = "receipt"
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var shutter: View
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var torchOn = false
    @Volatile private var busy = false
    private var lang = "pl"

    private val strings = mapOf(
        "pl" to mapOf("aim" to "Ustaw cały paragon w ramce i zrób zdjęcie", "reading" to "Odczytuję paragon…",
            "got" to "Gotowe ✓ — zdjęcie usunięte", "gallery" to "Galeria", "fail" to "Nie udało się odczytać tekstu — spróbuj jeszcze raz, bliżej i przy lepszym świetle",
            "noCam" to "Brak dostępu do aparatu — możesz wybrać zdjęcie z galerii"),
        "en" to mapOf("aim" to "Fit the whole receipt in the frame and take a photo", "reading" to "Reading the receipt…",
            "got" to "Done ✓ — photo deleted", "gallery" to "Gallery", "fail" to "Couldn't read the text — try again, closer and with better light",
            "noCam" to "No camera access — you can pick a photo from the gallery"),
        "ru" to mapOf("aim" to "Поместите весь чек в рамку и сделайте фото", "reading" to "Читаю чек…",
            "got" to "Готово ✓ — фото удалено", "gallery" to "Галерея", "fail" to "Не удалось прочитать текст — попробуйте ещё раз, ближе и при лучшем свете",
            "noCam" to "Нет доступа к камере — выберите фото из галереи"),
        "lt" to mapOf("aim" to "Sutalpinkite visą kvitą rėmelyje ir nufotografuokite", "reading" to "Skaitau kvitą…",
            "got" to "Atlikta ✓ — nuotrauka ištrinta", "gallery" to "Galerija", "fail" to "Nepavyko nuskaityti teksto — bandykite dar kartą, arčiau ir šviesiau",
            "noCam" to "Nėra prieigos prie kameros — pasirinkite nuotrauką iš galerijos")
    )
    private fun s(key: String) = strings[lang]?.get(key) ?: strings["pl"]!![key] ?: key
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) recognizeFromGallery(uri)
    }
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = s("noCam")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = (intent.getStringExtra(EXTRA_LANG) ?: "pl").take(2).lowercase(Locale.ROOT)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))

        // Ramka pomocnicza
        val guide = View(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), Color.argb(190, 255, 255, 255))
                cornerRadius = dp(20).toFloat()
            }
        }
        root.addView(guide, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            setMargins(dp(24), dp(96), dp(24), dp(200))
        })

        status = pill(s("aim"), 14f).apply { maxWidth = dp(320) }
        root.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(140)
        })

        // Przycisk zdjecia (duze kolo)
        shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(5), Color.argb(120, 255, 255, 255))
            }
            contentDescription = "Zdjęcie"
            setOnClickListener { takePhoto() }
        }
        root.addView(shutter, FrameLayout.LayoutParams(dp(74), dp(74), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(44)
        })

        val gallery = pill("🖼", 20f).apply {
            contentDescription = s("gallery")
            setOnClickListener { if (!busy) pickImage.launch("image/*") }
        }
        root.addView(gallery, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(56); leftMargin = dp(36)
        })

        val torch = pill("🔦", 20f).apply { setOnClickListener { toggleTorch() } }
        root.addView(torch, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(56); rightMargin = dp(36)
        })

        val close = pill("✕", 18f).apply { setOnClickListener { finish() } }
        root.addView(close, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(36); leftMargin = dp(20)
        })

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun pill(text: String, size: Float) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = GradientDrawable().apply {
            setColor(Color.argb(150, 0, 0, 0))
            cornerRadius = dp(24).toFloat()
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
            } catch (e: Exception) {
                status.text = s("noCam")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        shutter.alpha = if (b) 0.4f else 1f
    }

    // Zdjecie trafia tylko do pamieci (bez pliku), po odczycie jest od razu zamykane i usuwane
    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (busy) return
        setBusy(true)
        status.text = s("reading")
        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            @OptIn(ExperimentalGetImage::class)
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val media = image.image
                val input = try {
                    if (media != null) InputImage.fromMediaImage(media, rotation)
                    else InputImage.fromBitmap(image.toBitmap(), rotation)
                } catch (e: Exception) {
                    // zapasowo: przez bitmape (tez tylko w pamieci)
                    try { InputImage.fromBitmap(image.toBitmap(), rotation) } catch (e2: Exception) { null }
                }
                if (input == null) { image.close(); fail(); return }
                recognizer.process(input)
                    .addOnSuccessListener { text -> handleText(text) }
                    .addOnFailureListener { fail() }
                    .addOnCompleteListener { image.close() }   // zdjecie usuniete z pamieci
            }

            override fun onError(exception: ImageCaptureException) { fail() }
        })
    }

    // Z galerii: odczytujemy tekst, ale nie kopiujemy ani nie zapisujemy zdjecia
    private fun recognizeFromGallery(uri: Uri) {
        if (busy) return
        setBusy(true)
        status.text = s("reading")
        try {
            val input = InputImage.fromFilePath(this, uri)
            recognizer.process(input)
                .addOnSuccessListener { text -> handleText(text) }
                .addOnFailureListener { fail() }
        } catch (e: Exception) {
            fail()
        }
    }

    private fun handleText(text: Text) {
        if (text.text.isBlank()) { fail(); return }
        val r = ReceiptParser.parse(text)
        if (r.total == null && r.items.isEmpty()) { fail(); return }
        finishWith(r)
    }

    private fun fail() {
        setBusy(false)
        status.text = s("fail")
    }

    private fun finishWith(r: ReceiptResult) {
        status.text = s("got")
        vibrate()
        val items = JSONArray()
        for (item in r.items) {
            items.put(JSONObject().put("name", item.name).put("price", item.price))
        }
        val json = JSONObject()
        json.put("store", r.store)
        json.put("total", r.total ?: JSONObject.NULL)
        json.put("date", r.date ?: JSONObject.NULL)
        json.put("items", items)
        json.put("text", r.rawText.take(6000))
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, json.toString()))
        status.postDelayed({ finish() }, 450)
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(40)
            }
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recognizer.close() } catch (e: Exception) { }
    }
}
