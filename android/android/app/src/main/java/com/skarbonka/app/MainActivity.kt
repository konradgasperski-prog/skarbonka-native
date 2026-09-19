package com.skarbonka.app

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val webView = findViewById<WebView>(R.id.webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                pushSystemAccentColor(view)
            }
        }
        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
    }

    /**
     * On Android 12+ (API 31+), reads the device's Material You dynamic accent
     * color and hands it to the web page's "Automatic (system color)" theme option.
     * On older Android versions this silently does nothing; the app just falls
     * back to its default color palette.
     */
    private fun pushSystemAccentColor(webView: WebView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val resId = resources.getIdentifier("system_accent1_500", "color", "android")
            if (resId == 0) return
            val color = resources.getColor(resId, theme)
            val hex = String.format("#%06X", 0xFFFFFF and color)
            webView.evaluateJavascript("window.setSystemAccentColor && window.setSystemAccentColor('$hex')", null)
        } catch (e: Exception) {
            // Dynamic color not available on this device/skin \u2014 ignore, keep default palette.
        }
    }

    class AndroidBridge(private val context: Context) {
        @JavascriptInterface
        fun postBalance(amount: String, currency: String) {
            val prefs = context.getSharedPreferences("skarbonka_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("balance_text", "$amount $currency").apply()

            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, BalanceWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                BalanceWidgetProvider.updateAll(context, mgr, ids)
            }
        }
    }
}
