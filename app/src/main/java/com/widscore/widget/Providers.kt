package com.widscore.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.widscore.MainActivity
import com.widscore.R

// Base commune : taille défaut 2x2 redimensionnable (voir xml/*_info), refresh via worker.
abstract class BaseScoreProvider : AppWidgetProvider() {
    abstract val dark: Boolean

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            renderLoading(ctx, mgr, id)
        }
        WidgetUpdateWorker.enqueueOneShot(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            WidgetUpdateWorker.enqueueOneShot(ctx)
        }
    }

    private fun renderLoading(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(ctx.packageName, if (dark) R.layout.widget_dark else R.layout.widget_classic)
        views.setTextViewText(R.id.header_title, "⚽ WidScore")
        views.setTextViewText(R.id.status_label, "Chargement…")
        views.setOnClickPendingIntent(R.id.btn_refresh, refreshIntent(ctx))
        views.setOnClickPendingIntent(R.id.header_title, openAppIntent(ctx))
        try { mgr.updateAppWidget(id, views) } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_REFRESH = "com.widscore.ACTION_REFRESH"
        fun refreshIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, WidgetUpdateWorker.RefreshReceiver::class.java).setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        fun openAppIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
            return PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        fun matchIntent(ctx: Context, league: String, id: String, title: String, req: Int): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
                .setAction("com.widscore.MATCH")
                .putExtra("league", league).putExtra("matchId", id).putExtra("title", title)
            return PendingIntent.getActivity(ctx, 1000 + req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

class ScoreWidgetClassicProvider : BaseScoreProvider() {
    override val dark = false
}

class ScoreWidgetDarkProvider : BaseScoreProvider() {
    override val dark = true
}
