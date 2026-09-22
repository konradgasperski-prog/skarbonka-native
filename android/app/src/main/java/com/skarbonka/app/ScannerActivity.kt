package com.skarbonka.app

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Skaner kodu QR z paragonu.
 * Bez zdjec i bez przyciskow: aparat na zywo szuka kodu QR z VMI w kwadratowej ramce.
 * Gdy go znajdzie - otwiera w tle strone kvitas.vmi.lt, czyta z niej dane
 * (sklep, adres, suma, PVM, data) i wraca z nimi do apki.
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
        "pl" to mapOf("aim" to "Skieruj aparat na kod QR na paragonie", "found" to "Kod QR znaleziony ✓",
            "vmi" to "Pobieram dane z VMI…", "done" to "Gotowe ✓", "offline" to "Brak internetu — zapisuję sam kod QR",
            "other" to "To nie jest kod QR z paragonu VMI", "noCam" to "Brak dostępu do aparatu"),
        "en" to mapOf("aim" to "Point the camera at the QR code on the receipt", "found" to "QR code found ✓",
            "vmi" to "Fetching data from VMI…", "done" to "Done ✓", "offline" to "No internet — saving the QR code only",
            "other" to "This is not a VMI receipt QR code", "noCam" to "No camera access"),
        "ru" to mapOf("aim" to "Наведите камеру на QR-код на чеке", "found" to "QR-код найден ✓",
            "vmi" to "Загружаю данные из VMI…", "done" to "Готово ✓", "offline" to "Нет интернета — сохраняю только QR-код",
            "other" to "Это не QR-код чека VMI", "noCam" to "Нет доступа к камере"),
        "lt" to mapOf("aim" to "Nukreipkite kamerą į QR kodą kvite", "found" to "QR kodas rastas ✓",
            "vmi" to "Gaunu duomenis iš VMI…", "done" to "Atlikta ✓", "offline" to "Nėra interneto — išsaugau tik QR kodą",
            "other" to "Tai ne VMI kvito QR kodas", "noCam" to "Nėra prieigos prie kameros")
    )
    private fun s(key: String) = strings[lang]?.get(key) ?: strings["pl"]!![key] ?: key
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = s("noCam")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = (intent.getStringExtra(EXTRA_LANG) ?: "pl").take(2).lowercase(Locale.ROOT)
        analysisExecutor = Executors.newSingleThreadExecutor()

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))

        // Kwadratowa ramka z przyciemnieniem dookola i przesuwajaca sie linia
        overlay = SquareOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        status = pill(s("aim"), 15f).apply { maxWidth = dp(320) }
        root.addView(status, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(120)
        })

        val torch = pill("🔦", 20f).apply { setOnClickListener { toggleTorch() } }
        root.addView(torch, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(48)
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
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
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

    // Kazda klatka podgladu (co ~120 ms) jest sprawdzana, czy jest w niej kod QR
    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val media = proxy.image
        if (found || qrBusy || media == null || now - lastCheck < 120) { proxy.close(); return }
        qrBusy = true
        lastCheck = now
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        qrScanner.process(input)
            .addOnSuccessListener { codes ->
                if (found) return@addOnSuccessListener
                val values = codes.mapNotNull { it.rawValue }
                val vmi = values.firstOrNull { isVmi(it) }
                if (vmi != null) onVmiFound(canonicalVmiUrl(vmi))
                else if (values.isNotEmpty() && System.currentTimeMillis() - lastOtherQrHint > 2500) {
                    lastOtherQrHint = System.currentTimeMillis()
                    // pokazujemy poczatek odczytanego kodu - latwiej sprawdzic, co to za kod
                    status.text = s("other") + "\n" + values.first().trim().take(40)
                    handler.postDelayed({ if (!found) status.text = s("aim") }, 2000)
                }
            }
            .addOnCompleteListener { qrBusy = false; proxy.close() }
    }

    private fun onVmiFound(url: String) {
        found = true
        vibrate()
        overlay.setSuccess()
        status.text = s("found") + "  " + s("vmi")
        val vmi = JSONObject()
        val u = Uri.parse(url)
        vmi.put("url", url)
        fun param(k: String) = (u.getQueryParameter(k) ?: u.getQueryParameter(k.lowercase(Locale.ROOT)) ?: "").trim()
        vmi.put("nr", param("NR"))
        vmi.put("sm", param("SM"))
        vmi.put("dt", param("DT"))       // niektore kasy (np. Maxima) nie podaja daty w kodzie - wtedy bierzemy ja ze strony
        fetchVmiText(url) { pageText ->
            vmi.put("text", pageText ?: JSONObject.NULL)   // null = brak internetu / strona nie odpowiedziala
            status.text = if (pageText != null) s("done") else s("offline")
            val json = JSONObject()
            json.put("store", "")
            json.put("total", JSONObject.NULL)
            json.put("date", JSONObject.NULL)
            json.put("items", org.json.JSONArray())
            json.put("text", "")
            json.put("vmi", vmi)
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, json.toString()))
            handler.postDelayed({ finish() }, if (pageText != null) 350 else 1200)
        }
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
        overlay.stop()
        analysisExecutor.shutdown()
        try { vmiWeb?.destroy() } catch (e: Exception) { }
        super.onDestroy()
        try { qrScanner.close() } catch (e: Exception) { }
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
            val size = minOf(w, h) * 0.72f
            val left = (w - size) / 2f
            val top = (h - size) / 2f - 40f * density
            box.set(left, top, left + size, top + size)
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
