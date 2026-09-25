package com.skarbonka.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class BalanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun updateAll(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            // Must match the SharedPreferences file name MainActivity.postBalance() writes to
            // ("stash_widget") - this used to read from a different, never-written file
            // ("skarbonka_prefs"), so the widget silently showed "brak danych" forever on every
            // phone, not just Xiaomi.
            val prefs = context.getSharedPreferences("stash_widget", Context.MODE_PRIVATE)
            val balanceText = prefs.getString("balance_text", "brak danych") ?: "brak danych"

            val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = if (openAppIntent != null) {
                PendingIntent.getActivity(context, 0, openAppIntent, pendingFlags)
            } else null

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_balance)
                views.setTextViewText(R.id.widget_balance, balanceText)
                if (pendingIntent != null) {
                    views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                }
                mgr.updateAppWidget(id, views)
            }
        }
    }
}
