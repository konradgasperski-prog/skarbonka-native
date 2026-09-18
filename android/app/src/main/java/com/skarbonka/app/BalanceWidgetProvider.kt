package com.skarbonka.app

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

class BalanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        fun updateAll(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            val prefs = context.getSharedPreferences("skarbonka_prefs", Context.MODE_PRIVATE)
            val balanceText = prefs.getString("balance_text", "brak danych") ?: "brak danych"

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_balance)
                views.setTextViewText(R.id.widget_balance, balanceText)
                mgr.updateAppWidget(id, views)
            }
        }
    }
}
