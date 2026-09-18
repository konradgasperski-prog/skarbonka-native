package com.skarbonka.app

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
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
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
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
