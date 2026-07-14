package com.v2rayez.app.data.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** 2×2 status + action panel home-screen widget. */
class ControlPanelWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = VpnWidgetUpdater.buildControlPanel(context)
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
