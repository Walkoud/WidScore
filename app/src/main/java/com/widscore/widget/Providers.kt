package com.widscore.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.widscore.MainActivity
import com.widscore.R
import com.widscore.data.Lang
import com.widscore.data.Prefs

// Widgets scrollables (ListView) : header fixe + adapter, refresh via worker.
abstract class BaseScoreProvider : AppWidgetProvider() {
    abstract val dark: Boolean

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            bindAdapter(ctx, mgr, id)
        }
        WidgetUpdateWorker.enqueueOneShot(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            WidgetUpdateWorker.enqueueOneShot(ctx)
        }
    }

    private fun bindAdapter(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val lctx = Lang.localizedContext(ctx)
        val views = WidgetRenderer.buildHeader(ctx, dark, 0, 0L, Prefs.load(ctx).let {
            it.leagues.isEmpty() && it.teams.isEmpty()
        })
        views.setTextViewText(R.id.empty_view, lctx.getString(R.string.w_loading))
        // Adapter distinct par widget (data Uri unique, sinon factory partagée).
        val adapter = Intent(ctx, MatchListService::class.java).apply {
            putExtra(MatchListService.EXTRA_DARK, dark)
            data = Uri.parse("widscore://widget/$id-${if (dark) "dark" else "classic"}")
        }
        views.setRemoteAdapter(R.id.match_list, adapter)
        // Template clic item -> MainActivity (fill-in depuis la factory).
        val template = Intent(ctx, MainActivity::class.java).setAction(ACTION_MATCH)
        val pi = PendingIntent.getActivity(
            ctx, if (dark) 11 else 12, template,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        views.setPendingIntentTemplate(R.id.match_list, pi)
        try {
            mgr.updateAppWidget(id, views)
            mgr.notifyAppWidgetViewDataChanged(id, R.id.match_list)
        } catch (_: Exception) {}
    }

    companion object {
        const val ACTION_REFRESH = "com.widscore.ACTION_REFRESH"
        const val ACTION_MATCH = "com.widscore.MATCH"
        fun refreshIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, WidgetUpdateWorker.RefreshReceiver::class.java).setAction(ACTION_REFRESH)
            return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        fun openAppIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java)
            return PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

class ScoreWidgetClassicProvider : BaseScoreProvider() {
    override val dark = false
}

class ScoreWidgetDarkProvider : BaseScoreProvider() {
    override val dark = true
}
