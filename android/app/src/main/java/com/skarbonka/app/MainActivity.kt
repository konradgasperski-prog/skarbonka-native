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
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val REQ_SCAN = 4711
    }

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
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.scheme == "http" || url.scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (e: Exception) { }
                    return true
                }
                return false
            }
        }
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")

        applyIconMode()
        pushSystemAccentColor()
    }

    override fun onResume() {
        super.onResume()
        applyIconMode()
    }

    // --- Wynik ze skanera paragonow -> strona (window.onNativeReceipt) ---
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCAN || resultCode != RESULT_OK) return
        val json = data?.getStringExtra(ScannerActivity.EXTRA_RESULT) ?: return
        // JSON is a valid JS literal; only these two characters need escaping inside JS source
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

        // --- Lokalne AI (AiEngine) ---
        @JavascriptInterface
        fun aiStatus(): String = AiEngine.statusJson()

        @JavascriptInterface
        fun aiInit() {
            AiEngine.init(this@MainActivity) { pushAiStatus() }
        }

        @JavascriptInterface
        fun aiGenerate(id: String, prompt: String) {
            AiEngine.generate(prompt) { text, err ->
                val json = org.json.JSONObject()
                    .put("id", id)
                    .put("text", text ?: org.json.JSONObject.NULL)
                    .put("error", err ?: org.json.JSONObject.NULL)
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
