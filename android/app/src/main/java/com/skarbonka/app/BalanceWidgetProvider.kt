package com.skarbonka.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

class BalanceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context, appWidgetManager, appWidgetIds)
    }

    // Wywoływane przez system za każdym razem, gdy użytkownik zmienia rozmiar widgetu (np.
    // przeciąga uchwyty z 3x2 na 2x2 albo na 2x6) - bez tego widget zostałby na starym layoucie
    // aż do następnej aktualizacji salda.
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        updateAll(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    companion object {
        fun updateAll(context: Context, mgr: AppWidgetManager, ids: IntArray) {
            // Must match the SharedPreferences file name MainActivity.postBalance() writes to
            // ("stash_widget") - this used to read from a different, never-written file
            // ("skarbonka_prefs"), so the widget silently showed "brak danych" forever on every
            // phone, not just Xiaomi.
            val prefs = context.getSharedPreferences("stash_widget", Context.MODE_PRIVATE)
            val balanceText = prefs.getString("balance_text", "brak danych") ?: "brak danych"
            val otherText = prefs.getString("balance_other_text", "") ?: ""

            val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = if (openAppIntent != null) {
                PendingIntent.getActivity(context, 0, openAppIntent, pendingFlags)
            } else null

            for (id in ids) {
                val layoutRes = pickLayout(mgr, id)
                val views = RemoteViews(context.packageName, layoutRes)
                views.setTextViewText(R.id.widget_balance, balanceText)
                if (layoutRes == R.layout.widget_balance_large) {
                    val hasOther = otherText.isNotBlank()
                    views.setTextViewText(R.id.widget_other, otherText)
                    views.setViewVisibility(R.id.widget_other, if (hasOther) android.view.View.VISIBLE else android.view.View.GONE)
                }
                if (pendingIntent != null) {
                    views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                }
                mgr.updateAppWidget(id, views)
            }
        }

        // Wybiera jeden z trzech layoutów na podstawie rzeczywistego rozmiaru, jaki użytkownik nadał
        // widgetowi na swoim ekranie głównym (a nie tylko domyślnego z widget_info.xml) - to właśnie
        // dzięki temu ten sam widget wygląda dobrze zarówno jako mały kwadrat (2x2), jak i duży,
        // wysoki prostokąt (2x6) czy szeroki (4x2).
        private fun pickLayout(mgr: AppWidgetManager, id: Int): Int {
            val opts = try { mgr.getAppWidgetOptions(id) } catch (e: Exception) { null }
            val minW = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
            val minH = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            // Brak danych o rozmiarze (np. bardzo stary launcher) - zostajemy przy domyślnym.
            if (minW <= 0 || minH <= 0) return R.layout.widget_balance
            return when {
                minW >= 250 && minH >= 180 -> R.layout.widget_balance_large
                minW < 150 || minH < 100 -> R.layout.widget_balance_compact
                else -> R.layout.widget_balance
            }
        }
    }
}
