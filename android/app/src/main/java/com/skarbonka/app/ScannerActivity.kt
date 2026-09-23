package com.skarbonka.app

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Color
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
 * 1) tekst ze zdjecia (ML Kit) -> produkty, ceny, rabaty (dokladnie czyta je potem AI w apce),
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

    private val strings = mapOf(
        "pl" to mapOf("aim" to "Ustaw cały paragon w ramce (razem z kodem QR) i zrób zdjęcie", "qrSeen" to "Kod QR widoczny ✓ — zrób zdjęcie", "reading" to "Odczytuję paragon…", "vmi" to "Sprawdzam w VMI…", "done" to "Gotowe ✓ — zdjęcie usunięte", "fail" to "Nie udało się odczytać — spróbuj bliżej i przy lepszym świetle", "noCam" to "Brak dostępu do aparatu"),
        "en" to mapOf("aim" to "Fit the whole receipt in the frame (with the QR code) and take a photo", "qrSeen" to "QR code visible ✓ — take the photo", "reading" to "Reading the receipt…", "vmi" to "Checking with VMI…", "done" to "Done ✓ — photo deleted", "fail" to "Couldn't read it — try closer and with better light", "noCam" to "No camera access"),
        "ru" to mapOf("aim" to "Поместите весь чек в рамку (вместе с QR-кодом) и сделайте фото", "qrSeen" to "QR-код виден ✓ — сделайте фото", "reading" to "Читаю чек…", "vmi" to "Проверяю в VMI…", "done" to "Готово ✓ — фото удалено", "fail" to "Не удалось прочитать — попробуйте ближе и при лучшем свете", "noCam" to "Нет доступа к камере"),
        "lt" to mapOf("aim" to "Sutalpinkite visą kvitą rėmelyje (su QR kodu) ir nufotografuokite", "qrSeen" to "QR kodas matomas ✓ — fotografuokite", "reading" to "Skaitau kvitą…", "vmi" to "Tikrinu VMI…", "done" to "Atlikta ✓ — nuotrauka ištrinta", "fail" to "Nepavyko nuskaityti — bandykite arčiau ir šviesiau", "noCam" to "Nėra prieigos prie kameros")
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
                readTextAndQr(InputImage.fromBitmap(bmp, 0)) { }
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
            readTextAndQr(InputImage.fromFilePath(this, uri)) { }
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
            if (qr == null && (r == null || (r.total == null && r.items.isEmpty()))) { fail(); return@addOnCompleteListener }
            val result = r ?: ReceiptResult("", null, null, emptyList(), "")
            if (qr == null) { finishWith(result, null); return@addOnCompleteListener }
            status.text = s("vmi")
            val vmi = JSONObject()
            val u = Uri.parse(qr)
            fun param(k: String) = (u.getQueryParameter(k) ?: u.getQueryParameter(k.lowercase(Locale.ROOT)) ?: "").trim()
            vmi.put("url", qr)
            vmi.put("nr", param("NR"))
            vmi.put("sm", param("SM"))
            vmi.put("dt", param("DT"))
            fetchVmiText(qr) { pageText ->
                vmi.put("text", pageText ?: JSONObject.NULL)
                finishWith(result, vmi)
            }
        }
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
