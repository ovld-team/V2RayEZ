package com.v2rayez.app.data.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** 1×1 Quick Connect / Disconnect home-screen widget. */
class QuickConnectWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = VpnWidgetUpdater.buildQuickConnect(context)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    override fun onEnabled(context: Context) {
        VpnWidgetUpdater.refreshAll(context)
    }

    override fun onReceive(context: Context, intent: android.content.Intent) {
        super.onReceive(context, intent)
        if (intent.action == VpnWidgetUpdater.ACTION_REFRESH ||
            intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE
        ) {
            VpnWidgetUpdater.refreshAll(context)
        }
    }
}
