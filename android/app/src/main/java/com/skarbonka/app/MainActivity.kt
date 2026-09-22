package com.skarbonka.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQ_SCAN = 4711

        // Automatyczne aktualizacje: apka sama pobiera nowszy index.html z GitHuba.
        // Wystarczy wgrac nowy index.html do repo - telefon go sciagnie, bez instalowania APK.
        private const val UPDATE_URL =
            "https://raw.githubusercontent.com/konradgasperski-prog/skarbonka-native/main/android/app/src/main/assets/index.html"
        private val VERSION_RE = Regex("""<meta\s+name="stash-version"\s+content="(\d+)"""")
    }

    private var loadedVersion = 0L
    private var usingDownloaded = false
    @Volatile private var webReadyReceived = false
    private var updateChecked = false

    private val webDir by lazy { File(filesDir, "web").apply { mkdirs() } }
    private val downloadedHtml by lazy { File(webDir, "index.html") }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("stash_prefs", Context.MODE_PRIVATE)

        webView = WebView(this)
        setContentView(webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true

        webView.webViewClient = object : WebViewClient() {
            // Linki do stron w internecie (np. VMI) otwieraja sie w przegladarce, nie w apce
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.scheme == "http" || url.scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (e: Exception) { }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!updateChecked) { updateChecked = true; checkForUpdate() }
            }
        }
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        loadApp()

        applyIconMode()
        pushSystemAccentColor()
        AiEngine.prepare(this)   // pierwsze uruchomienie: model AI przygotowuje sie w tle
    }

    override fun onResume() {
        super.onResume()
        applyIconMode()
    }

    // ---------------------------------------------------------------------------------------
    // Ladowanie aplikacji: nowsza wersja pobrana z GitHuba albo ta zapisana w APK.
    // Obie laduja sie spod tego samego adresu (file:///android_asset/), wiec dane
    // (transakcje, ustawienia) sa te same.
    // ---------------------------------------------------------------------------------------
    private fun versionOf(html: String): Long =
        VERSION_RE.find(html.take(4000))?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private fun bundledHtml(): String = assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun loadApp() {
        webReadyReceived = false
        val bundled = bundledHtml()
        val bundledVer = versionOf(bundled)
        var html = bundled
        var ver = bundledVer
        usingDownloaded = false
        if (downloadedHtml.exists()) {
            val dl = try { downloadedHtml.readText(Charsets.UTF_8) } catch (e: Exception) { "" }
            val dlVer = versionOf(dl)
            if (dlVer > bundledVer) { html = dl; ver = dlVer; usingDownloaded = true }
            else downloadedHtml.delete()   // nowe APK ma juz nowsza wersje
        }
        loadedVersion = ver
        webView.loadDataWithBaseURL("file:///android_asset/index.html", html, "text/html", "utf-8", null)

        // Zabezpieczenie: jesli pobrana wersja sie nie uruchomi, wracamy do wersji z APK
        if (usingDownloaded) {
            handler.postDelayed({
                if (!webReadyReceived && usingDownloaded) {
                    downloadedHtml.delete()
                    loadApp()
                }
            }, 12000)
        }
    }

    private fun checkForUpdate() {
        Thread {
            try {
                val conn = (URL(UPDATE_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 20000
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                }
                if (conn.responseCode != 200) return@Thread
                val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val ver = versionOf(html)
                // tylko prawdziwa strona apki i tylko nowsza wersja
                if (ver <= loadedVersion || html.length < 50_000 || !html.contains("</html>")) return@Thread
                val tmp = File(webDir, "index.html.part")
                tmp.writeText(html, Charsets.UTF_8)
                downloadedHtml.delete()
                if (!tmp.renameTo(downloadedHtml)) return@Thread
                handler.post {
                    webView.evaluateJavascript("window.onWebUpdateReady && window.onWebUpdateReady('$ver');", null)
                }
            } catch (e: Exception) {
                // brak internetu - sprobujemy przy nastepnym uruchomieniu
            }
        }.start()
    }

    // --- Wynik ze skanera paragonow -> strona (window.onNativeReceipt) ---
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCAN || resultCode != RESULT_OK) return
        val json = data?.getStringExtra(ScannerActivity.EXTRA_RESULT) ?: return
        val safe = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        webView.post {
            webView.evaluateJavascript("window.onNativeReceipt && window.onNativeReceipt($safe);", null)
        }
    }

    private fun pushAiStatus() {
        val json = AiEngine.statusJson()
        webView.post { webView.evaluateJavascript("window.onAiStatus && window.onAiStatus($json);", null) }
    }

    // --- Icon switching (light / dark / glass / auto) ---
    private fun isSystemDark(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun resolveEffectiveMode(saved: String): String {
        return if (saved == "auto") {
            if (isSystemDark()) "dark" else "light"
        } else saved
    }

    private fun applyIconMode() {
        val saved = prefs.getString("icon_mode", "auto") ?: "auto"
        val effective = resolveEffectiveMode(saved)
        val aliases = mapOf(
            "light" to ".IconLight",
            "dark" to ".IconDark",
            "glass" to ".IconGlass"
        )
        val pm = packageManager
        for ((key, aliasName) in aliases) {
            val state = if (key == effective) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                pm.setComponentEnabledSetting(
                    ComponentName(this, "com.skarbonka.app$aliasName"),
                    state,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                // ignore — alias may not exist on older builds
            }
        }
    }

    // --- Material You accent color (best-effort) ---
    private fun pushSystemAccentColor() {
        try {
            val resId = resources.getIdentifier("system_accent1_500", "color", "android")
            if (resId != 0) {
                val color = resources.getColor(resId, theme)
                val hex = String.format("#%06X", 0xFFFFFF and color)
                webView.post {
                    webView.evaluateJavascript(
                        "if (window.setSystemAccentColor) { window.setSystemAccentColor('$hex'); }",
                        null
                    )
                }
            }
        } catch (e: Exception) {
            // Material You not available on this device/OS version — ignore
        }
    }

    // --- JS <-> Kotlin bridge ---
    inner class AndroidBridge {
        @JavascriptInterface
        fun postBalance(text: String) {
            val widgetPrefs = getSharedPreferences("stash_widget", Context.MODE_PRIVATE)
            widgetPrefs.edit().putString("balance_text", text).apply()
            try {
                val intent = Intent(applicationContext, BalanceWidgetProvider::class.java)
                intent.action = android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val mgr = android.appwidget.AppWidgetManager.getInstance(applicationContext)
                val ids = mgr.getAppWidgetIds(ComponentName(applicationContext, BalanceWidgetProvider::class.java))
                intent.putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                sendBroadcast(intent)
            } catch (e: Exception) {
                // widget provider not present / no widgets placed — ignore
            }
        }

        @JavascriptInterface
        fun setIconMode(mode: String) {
            prefs.edit().putString("icon_mode", mode).apply()
            runOnUiThread { applyIconMode() }
        }

        @JavascriptInterface
        fun getIconMode(): String {
            return prefs.getString("icon_mode", "auto") ?: "auto"
        }

        // Kopia ustawien wygladu (tlo, liquid glass, motyw) w pamieci telefonu - nigdy sie nie gubi
        @JavascriptInterface
        fun saveAppearance(json: String) {
            prefs.edit().putString("appearance", json).apply()
        }

        @JavascriptInterface
        fun getAppearance(): String {
            return prefs.getString("appearance", "") ?: ""
        }

        // --- Aktualizacje ---
        @JavascriptInterface
        fun webReady() {
            webReadyReceived = true
        }

        @JavascriptInterface
        fun reloadApp() {
            runOnUiThread { loadApp() }
        }

        @JavascriptInterface
        fun getWebVersion(): String = loadedVersion.toString()

        // --- Lokalne AI (AiEngine) ---
        @JavascriptInterface
        fun aiStatus(): String = AiEngine.statusJson()

        @JavascriptInterface
        fun aiModelDownloaded(): Boolean = AiEngine.isDownloaded(this@MainActivity)

        @JavascriptInterface
        fun aiInit() {
            AiEngine.init(this@MainActivity) { pushAiStatus() }
        }

        @JavascriptInterface
        fun aiGenerate(id: String, prompt: String) {
            aiGenerateT(id, prompt, 0.7)
        }

        @JavascriptInterface
        fun aiGenerateT(id: String, prompt: String, temperature: Double) {
            AiEngine.generate(prompt, temperature.toFloat()) { text, err ->
                val json = JSONObject()
                    .put("id", id)
                    .put("text", text ?: JSONObject.NULL)
                    .put("error", err ?: JSONObject.NULL)
                    .toString()
                    .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
                webView.post { webView.evaluateJavascript("window.onAiResult && window.onAiResult($json);", null) }
            }
        }

        // Otwiera strone (np. paragon w VMI) w przegladarce telefonu
        @JavascriptInterface
        fun openUrl(url: String) {
            if (!url.startsWith("https://")) return
            runOnUiThread {
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } catch (e: Exception) { }
            }
        }

        // Otwiera skaner paragonow; wynik wraca przez onActivityResult
        @JavascriptInterface
        fun scanReceipt(lang: String) {
            runOnUiThread {
                val i = Intent(this@MainActivity, ScannerActivity::class.java)
                i.putExtra(ScannerActivity.EXTRA_LANG, lang)
                startActivityForResult(i, REQ_SCAN)
            }
        }
    }
}
