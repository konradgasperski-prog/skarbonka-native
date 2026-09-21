package com.skarbonka.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Skaner paragonow na zywo: podglad z aparatu, tekst odczytywany na biezaco (ML Kit, offline).
 * Gdy ta sama suma zostanie odczytana 3 razy z rzedu (obraz stabilny) - paragon jest
 * lapany automatycznie, bez przycisku. Mozna tez wybrac zdjecie z galerii.
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
    private lateinit var cameraExecutor: ExecutorService
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    @Volatile private var busy = false
    @Volatile private var done = false
    private var lastAnalysis = 0L
    private var lastTotal: Double? = null
    private var stableCount = 0
    private val startedAt = System.currentTimeMillis()
    private var lang = "pl"

    private val strings = mapOf(
        "pl" to mapOf("aim" to "Skieruj aparat na paragon", "seeing" to "Widzę paragon — szukam sumy…",
            "bottom" to "Nie widzę sumy — pokaż dół paragonu", "found" to "Suma:", "got" to "Mam! ✓",
            "gallery" to "Galeria", "reading" to "Odczytuję zdjęcie…", "fail" to "Nie udało się odczytać tekstu",
            "noCam" to "Brak dostępu do aparatu — możesz wybrać zdjęcie z galerii"),
        "en" to mapOf("aim" to "Point the camera at the receipt", "seeing" to "I see a receipt — looking for the total…",
            "bottom" to "Can't see the total — show the bottom of the receipt", "found" to "Total:", "got" to "Got it! ✓",
            "gallery" to "Gallery", "reading" to "Reading the photo…", "fail" to "Couldn't read any text",
            "noCam" to "No camera access — you can pick a photo from the gallery"),
        "ru" to mapOf("aim" to "Наведите камеру на чек", "seeing" to "Вижу чек — ищу итог…",
            "bottom" to "Не вижу итог — покажите низ чека", "found" to "Итог:", "got" to "Готово! ✓",
            "gallery" to "Галерея", "reading" to "Читаю фото…", "fail" to "Не удалось прочитать текст",
            "noCam" to "Нет доступа к камере — выберите фото из галереи"),
        "lt" to mapOf("aim" to "Nukreipkite kamerą į kvitą", "seeing" to "Matau kvitą — ieškau sumos…",
            "bottom" to "Nematau sumos — parodykite kvito apačią", "found" to "Suma:", "got" to "Yra! ✓",
            "gallery" to "Galerija", "reading" to "Skaitau nuotrauką…", "fail" to "Nepavyko nuskaityti teksto",
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
        cameraExecutor = Executors.newSingleThreadExecutor()

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
            setMargins(dp(28), dp(96), dp(28), dp(180))
        })

        status = pill(s("aim"), 15f)
        root.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(112)
        })

        val gallery = pill("🖼  " + s("gallery"), 15f).apply { setOnClickListener { pickImage.launch("image/*") } }
        root.addView(gallery, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(40)
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
        setPadding(dp(18), dp(11), dp(18), dp(11))
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
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                status.text = s("noCam")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val media = proxy.image
        if (done || busy || media == null || now - lastAnalysis < 350) {
            proxy.close(); return
        }
        busy = true
        lastAnalysis = now
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(input)
            .addOnSuccessListener { text -> onLiveText(text) }
            .addOnCompleteListener { busy = false; proxy.close() }
    }

    private fun onLiveText(text: Text) {
        if (done) return
        val r = ReceiptParser.parse(text)
        val total = r.total
        if (total == null) {
            stableCount = 0
            lastTotal = null
            status.text = when {
                text.text.length > 40 && System.currentTimeMillis() - startedAt > 8000 -> s("bottom")
                text.text.length > 40 -> s("seeing")
                else -> s("aim")
            }
            return
        }
        val prev = lastTotal
        if (prev != null && Math.abs(prev - total) < 0.001) stableCount++ else { stableCount = 1; lastTotal = total }
        status.text = s("found") + " " + String.format(Locale.US, "%.2f", total)
        if (stableCount >= 3) finishWith(r, previewView.bitmap)
    }

    private fun recognizeFromGallery(uri: Uri) {
        done = true
        status.text = s("reading")
        try {
            val input = InputImage.fromFilePath(this, uri)
            recognizer.process(input)
                .addOnSuccessListener { text ->
                    if (text.text.isBlank()) { status.text = s("fail"); done = false; return@addOnSuccessListener }
                    finishWith(ReceiptParser.parse(text), loadThumb(uri), fromGallery = true)
                }
                .addOnFailureListener { status.text = s("fail"); done = false }
        } catch (e: Exception) {
            status.text = s("fail"); done = false
        }
    }

    private fun finishWith(r: ReceiptResult, bmp: Bitmap?, fromGallery: Boolean = false) {
        done = true
        status.text = s("got")
        vibrate()
        val json = JSONObject()
        json.put("store", r.store)
        json.put("total", r.total ?: JSONObject.NULL)
        json.put("date", r.date ?: JSONObject.NULL)
        json.put("items", r.items)
        json.put("text", r.rawText.take(4000))
        json.put("thumb", bmp?.let { toDataUrl(it) } ?: JSONObject.NULL)
        json.put("source", if (fromGallery) "gallery" else "live")
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, json.toString()))
        status.postDelayed({ finish() }, 300)
    }

    private fun toDataUrl(src: Bitmap): String {
        val w = 360
        val h = (src.height.toFloat() / src.width * w).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun loadThumb(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 720) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) { null }

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
        cameraExecutor.shutdown()
        try { recognizer.close() } catch (e: Exception) { }
    }
}
