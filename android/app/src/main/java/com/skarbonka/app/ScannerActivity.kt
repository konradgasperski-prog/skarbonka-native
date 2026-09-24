package com.skarbonka.app

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Skaner paragonow: zdjecie calego paragonu przyciskiem.
 * 1) tekst ze zdjecia (ML Kit) -> produkty, ceny, rabaty,
 * 2) kod QR VMI z tego samego zdjecia (albo zlapany wczesniej w podgladzie) -> strona VMI -> sklep, adres, suma, PVM.
 * Zdjecie jest tylko w pamieci i zaraz po odczycie jest usuwane - nic nie trafia do galerii.
 */
class ScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LANG = "lang"
        const val EXTRA_RESULT = "receipt"
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var overlay: SquareOverlay
    private lateinit var status: TextView
    private lateinit var analysisExecutor: ExecutorService
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var frozenView: ImageView? = null      // zatrzymany obraz zrobionego zdjecia
    private var frozenBmp: Bitmap? = null
    private lateinit var shutter: View
    @Volatile private var liveQr: String? = null     // kod VMI zauwazony juz w podgladzie (zapas, gdyby na zdjeciu byl nieczytelny)
    @Volatile private var busy = false
    private val qrScanner by lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    private var camera: Camera? = null
    private var torchOn = false
    private var vmiWeb: WebView? = null
    @Volatile private var found = false        // kod juz zlapany - dalej nie szukamy
    @Volatile private var qrBusy = false
    private var lastCheck = 0L
    private var lastOtherQrHint = 0L
    private var lang = "pl"
    // "Long receipt" mode: a very long thermal receipt has to be photographed from far enough back
    // to fit it all in one shot, which makes the small print too tiny/blurry for OCR - letting the
    // user take it as two overlapping photos (top half, then bottom half) and reading each normally
    // solves that without needing any special stitching: the two recognised texts are just
    // concatenated before being handed to the app's own item/total parsing.
    private lateinit var multiToggle: TextView
    private var multiMode = false
    private var awaitingSecondPart = false
    private var firstPartResult: ReceiptResult? = null
    private var firstPartQr: String? = null

    private val strings = mapOf(
        "pl" to mapOf("aim" to "Ustaw cały paragon w ramce (razem z kodem QR) i zrób zdjęcie", "qrSeen" to "Kod QR widoczny ✓ — zrób zdjęcie", "reading" to "Odczytuję paragon…", "vmi" to "Sprawdzam w VMI…", "done" to "Gotowe ✓ — zdjęcie usunięte", "fail" to "Nie udało się odczytać — spróbuj bliżej i przy lepszym świetle", "noCam" to "Brak dostępu do aparatu",
            "multiOff" to "🧾 Długi paragon (2 zdjęcia)", "multiOn" to "🧾 Długi paragon: WŁ.", "multiHint1" to "Zrób zdjęcie GÓRNEJ części paragonu", "multiHint2" to "Teraz zrób zdjęcie DOLNEJ części — może się trochę nakładać z górną"),
        "en" to mapOf("aim" to "Fit the whole receipt in the frame (with the QR code) and take a photo", "qrSeen" to "QR code visible ✓ — take the photo", "reading" to "Reading the receipt…", "vmi" to "Checking with VMI…", "done" to "Done ✓ — photo deleted", "fail" to "Couldn't read it — try closer and with better light", "noCam" to "No camera access",
            "multiOff" to "🧾 Long receipt (2 photos)", "multiOn" to "🧾 Long receipt: ON", "multiHint1" to "Photograph the TOP part of the receipt", "multiHint2" to "Now photograph the BOTTOM part — a little overlap with the top is fine"),
        "ru" to mapOf("aim" to "Поместите весь чек в рамку (вместе с QR-кодом) и сделайте фото", "qrSeen" to "QR-код виден ✓ — сделайте фото", "reading" to "Читаю чек…", "vmi" to "Проверяю в VMI…", "done" to "Готово ✓ — фото удалено", "fail" to "Не удалось прочитать — попробуйте ближе и при лучшем свете", "noCam" to "Нет доступа к камере",
            "multiOff" to "🧾 Длинный чек (2 фото)", "multiOn" to "🧾 Длинный чек: ВКЛ", "multiHint1" to "Сфотографируйте ВЕРХНЮЮ часть чека", "multiHint2" to "Теперь сфотографируйте НИЖНЮЮ часть — небольшое перекрытие не страшно"),
        "lt" to mapOf("aim" to "Sutalpinkite visą kvitą rėmelyje (su QR kodu) ir nufotografuokite", "qrSeen" to "QR kodas matomas ✓ — fotografuokite", "reading" to "Skaitau kvitą…", "vmi" to "Tikrinu VMI…", "done" to "Atlikta ✓ — nuotrauka ištrinta", "fail" to "Nepavyko nuskaityti — bandykite arčiau ir šviesiau", "noCam" to "Nėra prieigos prie kameros",
            "multiOff" to "🧾 Ilgas kvitas (2 nuotraukos)", "multiOn" to "🧾 Ilgas kvitas: ĮJ.", "multiHint1" to "Nufotografuokite VIRŠUTINĘ kvito dalį", "multiHint2" to "Dabar nufotografuokite APATINĘ dalį — nedidelis persidengimas su viršumi netrukdo")
    )
    private fun s(key: String) = strings[lang]?.get(key) ?: strings["pl"]!![key] ?: key
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) readFromGallery(uri)
    }
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = s("noCam")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = (intent.getStringExtra(EXTRA_LANG) ?: "pl").take(2).lowercase(Locale.ROOT)
        analysisExecutor = Executors.newSingleThreadExecutor()

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // COMPATIBLE (TextureView) instead of the default SurfaceView: a SurfaceView is drawn on its
        // own hardware layer and can keep showing live video "through" any view placed on top of it,
        // even after the camera is unbound - that is exactly why the shutter used to look like it
        // never froze. TextureView is a normal view, so covering it with the still photo actually works.
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))

        // Wysoka ramka na caly paragon, przyciemnienie dookola
        overlay = SquareOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        status = pill(s("aim"), 14f).apply { maxWidth = dp(320) }
        root.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(132)
        })

        shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(5), Color.argb(120, 255, 255, 255))
            }
            setOnClickListener { takePhoto() }
        }
        root.addView(shutter, FrameLayout.LayoutParams(dp(74), dp(74), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(40)
        })

        val gallery = pill("🖼", 20f).apply { setOnClickListener { if (!busy) pickImage.launch("image/*") } }
        root.addView(gallery, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(52); leftMargin = dp(36)
        })

        val torch = pill("🔦", 20f).apply { setOnClickListener { toggleTorch() } }
        root.addView(torch, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(52); rightMargin = dp(36)
        })

        val close = pill("✕", 18f).apply { setOnClickListener { finish() } }
        root.addView(close, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(36); leftMargin = dp(20)
        })

        multiToggle = pill(s("multiOff"), 12f).apply {
            maxWidth = dp(220)
            alpha = 0.75f
            setOnClickListener {
                if (busy) return@setOnClickListener
                if (awaitingSecondPart) {
                    // mid-sequence: tapping it again cancels back to a normal single photo
                    awaitingSecondPart = false
                    multiMode = false
                    firstPartResult = null
                    firstPartQr = null
                    text = s("multiOff"); alpha = 1f
                    status.text = s("aim")
                } else {
                    multiMode = !multiMode
                    text = if (multiMode) s("multiOn") else s("multiOff")
                    alpha = if (multiMode) 1f else 0.75f
                    status.text = if (multiMode) s("multiHint1") else s("aim")
                }
            }
        }
        root.addView(multiToggle, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(36); rightMargin = dp(20)
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
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                camera = try {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                } catch (e: Exception) {
                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }
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

    // Kody VMI bywaja zapisane roznie: "https://kvitas.vmi.lt?NR=..." (bez ukosnika),
    // z ukosnikiem, wielkimi literami albo z koncem linii na koncu - wszystkie przyjmujemy
    private val vmiRe = Regex("^https?://(www\\.)?kvitas\\.vmi\\.lt(?:[/?#]|$)", RegexOption.IGNORE_CASE)
    private fun isVmi(u: String) = vmiRe.containsMatchIn(u.trim())

    /** Porzadny adres strony VMI z tymi samymi parametrami (NR, SM, RS, RC, DT). */
    private fun canonicalVmiUrl(raw: String): String {
        val q = raw.trim().substringAfter('?', "").substringBefore('#').trim()
        return if (q.isNotEmpty()) "https://kvitas.vmi.lt/?$q" else "https://kvitas.vmi.lt/"
    }

    // Podglad na zywo tylko zapamietuje kod QR VMI (gdyby na zdjeciu byl nieostry) - nic nie robi sam
    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val media = proxy.image
        if (busy || liveQr != null || qrBusy || media == null || now - lastCheck < 250) { proxy.close(); return }
        qrBusy = true
        lastCheck = now
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        qrScanner.process(input)
            .addOnSuccessListener { codes ->
                val vmi = codes.mapNotNull { it.rawValue }.firstOrNull { isVmi(it) }
                if (vmi != null && liveQr == null && !busy) {
                    liveQr = canonicalVmiUrl(vmi)
                    overlay.setSuccess()
                    status.text = s("qrSeen")
                }
            }
            .addOnCompleteListener { qrBusy = false; proxy.close() }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        shutter.alpha = if (b) 0.4f else 1f
    }

    // Zdjecie trafia tylko do pamieci (bez pliku) i jest zamykane zaraz po odczycie
    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (busy) return
        setBusy(true)
        status.text = s("reading")
        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bmp = try { rotated(image.toBitmap(), rotation) } catch (e: Exception) { null }
                image.close()   // dane z aparatu zwolnione od razu
                if (bmp == null) { fail(); return }
                // obraz sie zatrzymuje - podglad z aparatu jest wylaczony, widac zrobione zdjecie
                freeze(bmp)
                readTextAndQr(InputImage.fromBitmap(enhanceForOcr(bmp), 0)) { }
            }

            override fun onError(exception: ImageCaptureException) { fail() }
        })
    }

    // Z galerii: czytamy tekst i kod, ale niczego nie kopiujemy ani nie zapisujemy
    private fun readFromGallery(uri: Uri) {
        if (busy) return
        setBusy(true)
        status.text = s("reading")
        try {
            val bmp = try { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } catch (e: Exception) { null }
            val input = if (bmp != null) InputImage.fromBitmap(enhanceForOcr(bmp), 0) else InputImage.fromFilePath(this, uri)
            readTextAndQr(input) { }
        } catch (e: Exception) {
            fail()
        }
    }

    private fun readTextAndQr(input: InputImage, release: () -> Unit) {
        val textTask = recognizer.process(input)
        val qrTask = qrScanner.process(input)
        Tasks.whenAllComplete(textTask, qrTask).addOnCompleteListener {
            release()   // zdjecie usuniete z pamieci
            val text: Text? = if (textTask.isSuccessful) textTask.result else null
            val codes: List<Barcode> = if (qrTask.isSuccessful) (qrTask.result ?: emptyList()) else emptyList()
            val qr = codes.mapNotNull { it.rawValue }.firstOrNull { isVmi(it) }?.let { canonicalVmiUrl(it) } ?: liveQr
            val r = if (text != null && text.text.isNotBlank()) ReceiptParser.parse(text) else null

            // Long-receipt mode, first (top) half: nothing to show yet - just remember what was read
            // and ask for the bottom half. The VMI QR code (usually printed near the very bottom, after
            // everything else) is unlikely to be on this half anyway, so it is not looked up here even
            // if one happened to be caught - firstPartQr is kept only as a fallback for the final result.
            if (multiMode && !awaitingSecondPart) {
                if (r == null && qr == null) { fail(); return@addOnCompleteListener }
                firstPartResult = r ?: ReceiptResult("", null, null, emptyList(), "")
                firstPartQr = qr
                awaitingSecondPart = true
                deletePhoto()
                setBusy(false)
                liveQr = null   // let the live preview catch a QR again on the second half
                status.text = s("multiHint2")
                startCamera()
                return@addOnCompleteListener
            }

            val finalResult: ReceiptResult
            val finalQr: String?
            val first = firstPartResult
            if (multiMode && awaitingSecondPart && first != null) {
                // Merge top + bottom: the app's own parsing (parseReceiptText, on the web side) works
                // off the concatenated raw text anyway, so gluing the two recognised texts together -
                // top half first, then bottom half - is enough; no image stitching needed.
                finalResult = ReceiptResult(
                    store = first.store.ifEmpty { r?.store ?: "" },
                    total = r?.total ?: first.total,
                    date = first.date ?: r?.date,
                    items = first.items + (r?.items ?: emptyList()),
                    rawText = (first.rawText + "\n" + (r?.rawText ?: "")).trim()
                )
                finalQr = firstPartQr ?: qr
                multiMode = false; awaitingSecondPart = false; firstPartResult = null; firstPartQr = null
                multiToggle.text = s("multiOff"); multiToggle.alpha = 0.75f
            } else {
                finalResult = r ?: ReceiptResult("", null, null, emptyList(), "")
                finalQr = qr
            }

            if (finalQr == null && finalResult.total == null && finalResult.items.isEmpty()) { fail(); return@addOnCompleteListener }
            if (finalQr == null) { finishWith(finalResult, null); return@addOnCompleteListener }
            status.text = s("vmi")
            val vmi = JSONObject()
            val u = Uri.parse(finalQr)
            fun param(k: String) = (u.getQueryParameter(k) ?: u.getQueryParameter(k.lowercase(Locale.ROOT)) ?: "").trim()
            vmi.put("url", finalQr)
            vmi.put("nr", param("NR"))
            vmi.put("sm", param("SM"))
            vmi.put("dt", param("DT"))
            fetchVmiText(finalQr) { pageText ->
                vmi.put("text", pageText ?: JSONObject.NULL)
                finishWith(finalResult, vmi)
            }
        }
    }

    // Wiekszosc paragonow z drukarek termicznych ma slaby, wyblakly kontrast - podbicie go
    // (szarosc + mocniejszy kontrast) wyraznie poprawia odczyt liter, zwlaszcza litewskich
    // znakow diakrytycznych (ą, č, ę, ė, į, š, ų, ū, ž). Zdjecie pokazywane uzytkownikowi
    // (freeze()) zostaje oryginalne - to tylko kopia uzywana do samego odczytu OCR.
    private fun enhanceForOcr(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val contrast = 1.9f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix().apply { setSaturation(0f) }   // grayscale
        cm.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun rotated(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out != src) src.recycle()
        return out
    }

    private fun freeze(bmp: Bitmap) {
        try { cameraProvider?.unbindAll() } catch (e: Exception) { }
        previewView.visibility = View.INVISIBLE   // belt-and-braces: hide the live feed itself too
        frozenBmp = bmp
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bmp)
        }
        frozenView = iv
        root.addView(iv, 1, FrameLayout.LayoutParams(MATCH, MATCH))   // nad podgladem, pod ramka i napisami
    }

    // Zdjecie usuwane z pamieci (nigdy nie bylo zapisane w telefonie)
    private fun deletePhoto() {
        frozenView?.let { root.removeView(it) }
        frozenView = null
        frozenBmp?.recycle()
        frozenBmp = null
        previewView.visibility = View.VISIBLE
    }

    private fun fail() {
        val wasFrozen = frozenView != null
        deletePhoto()
        if (wasFrozen) startCamera()      // wracamy do aparatu, zeby zrobic zdjecie jeszcze raz
        setBusy(false)
        status.text = s("fail")
    }

    private fun finishWith(r: ReceiptResult, vmi: JSONObject?) {
        deletePhoto()
        status.text = s("done")
        vibrate()
        val items = JSONArray()
        for (item in r.items) {
            items.put(JSONObject().put("name", item.name).put("price", item.price).put("discount", item.discount))
        }
        val json = JSONObject()
        json.put("store", r.store)
        json.put("total", r.total ?: JSONObject.NULL)
        json.put("date", r.date ?: JSONObject.NULL)
        json.put("items", items)
        json.put("text", r.rawText.take(6000))
        if (vmi != null) json.put("vmi", vmi)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, json.toString()))
        handler.postDelayed({ finish() }, 350)
    }

    /**
     * Strona VMI laduje dane skryptem, wiec otwieramy ja w ukrytym WebView i czytamy wyswietlony tekst.
     * Limit 15 s - bez internetu wracamy z samym kodem QR.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun fetchVmiText(url: String, done: (String?) -> Unit) {
        val wv = WebView(this)
        vmiWeb = wv
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.webViewClient = WebViewClient()
        wv.alpha = 0f
        root.addView(wv, 0, FrameLayout.LayoutParams(MATCH, MATCH))   // pod podgladem aparatu, niewidoczne
        val started = System.currentTimeMillis()
        var finished = false
        fun end(v: String?) {
            if (finished) return
            finished = true
            try { wv.stopLoading(); root.removeView(wv); wv.destroy() } catch (e: Exception) { }
            vmiWeb = null
            done(v)
        }
        val amount = Regex("\\d+[.,]\\d{2}")
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                if (System.currentTimeMillis() - started > 15000) { end(null); return }
                wv.evaluateJavascript("(function(){var b=document.body;return b?(b.innerText||b.textContent||''):'';})()") { res ->
                    val txt = try { org.json.JSONArray("[$res]").optString(0, "") } catch (e: Exception) { "" }
                    val n = ReceiptParser.norm(txt)
                    val loaded = txt.length > 60 && (amount.containsMatchIn(txt) || n.contains("nerast"))
                    if (loaded) end(txt.take(4000)) else handler.postDelayed(this, 500)
                }
            }
        }
        wv.loadUrl(url)
        handler.postDelayed(poll, 900)
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
        handler.removeCallbacksAndMessages(null)
        deletePhoto()
        overlay.stop()
        analysisExecutor.shutdown()
        try { vmiWeb?.destroy() } catch (e: Exception) { }
        super.onDestroy()
        try { qrScanner.close() } catch (e: Exception) { }
        try { recognizer.close() } catch (e: Exception) { }
    }

    /** Kwadratowa ramka skanera: przyciemnienie dookola, biale narozniki, przesuwajaca sie linia. */
    private class SquareOverlay(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f * density; strokeCap = Paint.Cap.ROUND
        }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 90, 220, 140); strokeWidth = 2.5f * density }
        private val box = RectF()
        private val path = Path()
        private var progress = 0f
        private var success = false
        private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }

        fun setSuccess() {
            success = true
            corner.color = Color.rgb(90, 220, 140)
            anim.cancel()
            invalidate()
        }

        fun stop() { anim.cancel() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            val bw = w * 0.84f
            val bh = h * 0.62f
            val left = (w - bw) / 2f
            val top = (h - bh) / 2f - 50f * density
            box.set(left, top, left + bw, top + bh)
            val size = minOf(bw, bh)
            val r = 18f * density
            // przyciemnienie wszystkiego poza kwadratem
            path.reset()
            path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, w, h, Path.Direction.CW)
            path.addRoundRect(box, r, r, Path.Direction.CW)
            canvas.drawPath(path, dim)
            // narozniki
            val c = size * 0.14f
            canvas.drawLine(box.left, box.top + c, box.left, box.top + r / 2, corner)
            canvas.drawLine(box.left + r / 2, box.top, box.left + c, box.top, corner)
            canvas.drawLine(box.right - c, box.top, box.right - r / 2, box.top, corner)
            canvas.drawLine(box.right, box.top + r / 2, box.right, box.top + c, corner)
            canvas.drawLine(box.left, box.bottom - c, box.left, box.bottom - r / 2, corner)
            canvas.drawLine(box.left + r / 2, box.bottom, box.left + c, box.bottom, corner)
            canvas.drawLine(box.right - c, box.bottom, box.right - r / 2, box.bottom, corner)
            canvas.drawLine(box.right, box.bottom - c, box.right, box.bottom - r / 2, corner)
            canvas.drawArc(RectF(box.left, box.top, box.left + r, box.top + r), 180f, 90f, false, corner)
            canvas.drawArc(RectF(box.right - r, box.top, box.right, box.top + r), 270f, 90f, false, corner)
            canvas.drawArc(RectF(box.left, box.bottom - r, box.left + r, box.bottom), 90f, 90f, false, corner)
            canvas.drawArc(RectF(box.right - r, box.bottom - r, box.right, box.bottom), 0f, 90f, false, corner)
            // linia skanowania
            if (!success) {
                val y = box.top + r + (box.height() - 2 * r) * progress
                canvas.drawLine(box.left + r, y, box.right - r, y, line)
            }
        }
    }
}
