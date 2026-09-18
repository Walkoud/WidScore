package com.widscore.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.widscore.R
import com.widscore.data.FootballSettings
import com.widscore.data.Lang
import com.widscore.data.Prefs
import java.net.URL

// Liste scrollable des widgets (ListView) : lit le cache du worker,
// regroupe par date, items classic (codes) ou dark (spec card).
class MatchListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        com.widscore.data.LogStore.init(this)
        return MatchFactory(applicationContext, intent.getBooleanExtra(EXTRA_DARK, false))
    }

    companion object {
        const val EXTRA_DARK = "dark"
        const val EXTRA_LEAGUE = "league"
        const val EXTRA_MATCH_ID = "matchId"
        const val EXTRA_TITLE = "title"
    }

    class MatchFactory(private val ctx: Context, private val dark: Boolean) : RemoteViewsFactory {
        // UN SEUL type de vue (item match avec ligne date intégrée) : aucun
        // recyclage inter-layouts possible -> pas de logos/textes fantômes.
        data class Item(val match: com.widscore.data.EspnMatch, val dateLabel: String)
        private var rows: List<Item> = emptyList()
        private var settings: FootballSettings = FootballSettings()

        override fun onCreate() {}
        override fun onDestroy() {}
        override fun getCount() = rows.size
        override fun getViewTypeCount() = 1
        // Ids stables : sans ça certains launchers dupliquent ou mélangent
        // les lignes quand le dataset change entre 2 updates.
        override fun getItemId(p: Int): Long {
            val r = rows[p]
            return (r.match.leagueSlug + "/" + r.match.id).hashCode().toLong()
        }
        override fun hasStableIds() = true
        override fun getLoadingView(): RemoteViews? = null

        override fun onDataSetChanged() {
            settings = Prefs.load(ctx)
            val lctx = Lang.localizedContext(ctx)
            val (_, matches) = Prefs.loadCache(ctx)
            val shown = matches.take(settings.maxMatches.coerceIn(1, 50))
            val locale = Lang.localeOf(ctx)
            val grouped = WidgetRenderer.buildRows(
                shown, settings, locale,
                lctx.getString(R.string.w_today),
                lctx.getString(R.string.w_tomorrow),
                lctx.getString(R.string.w_yesterday),
                lctx.getString(R.string.w_finished),
                lctx.getString(R.string.w_upcoming)
            )
            // Aplatit : la date surplombante devient le label du 1er match suivant.
            val items = mutableListOf<Item>()
            var pending = ""
            for (r in grouped) {
                when (r) {
                    is WidgetRenderer.Row.Date -> pending = r.text
                    is WidgetRenderer.Row.Match -> {
                        items.add(Item(r.match, pending))
                        pending = ""
                    }
                }
            }
            rows = items
            // Log des rows réellement rendues (preview) : compare avec l'affichage.
            try {
                val L = com.widscore.data.LogStore
                L.log("ROWS", "dark=$dark n=${rows.size}")
                val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
                for (r in rows.take(40)) {
                    val m = r.match
                    val sc = if (m.homeScore != null && m.awayScore != null) " ${m.homeScore}-${m.awayScore}" else ""
                    L.log("ROW", "M :: ${fmt.format(java.util.Date(m.utcMillis))} ${m.home.name} vs ${m.away.name}$sc [${m.leagueSlug}] ${m.state}" + if (r.dateLabel.isNotEmpty()) " §${r.dateLabel}" else "")
                }
            } catch (_: Exception) {}
        }

        override fun getViewAt(position: Int): RemoteViews? {
            return try {
                val row = rows[position]
                val m = row.match
                val lctx = Lang.localizedContext(ctx)
                val item = if (dark) WidgetRenderer.buildDarkItem(ctx, m, settings, row.dateLabel)
                else WidgetRenderer.buildClassicItem(ctx, m, settings, lctx, row.dateLabel)
                WidgetRenderer.applyItemScale(item, m, settings, dark)
                // Clic item -> template (MainActivity MATCH) complété par fill-in.
                val fill = Intent().apply {
                    putExtra(EXTRA_LEAGUE, m.leagueSlug)
                    putExtra(EXTRA_MATCH_ID, m.id)
                    putExtra(EXTRA_TITLE, "${m.home.name} vs ${m.away.name}")
                }
                item.setOnClickFillInIntent(R.id.item_root, fill)
                item
            } catch (_: Exception) { null }
        }
                }
            } catch (_: Exception) { null }
        }
    }
}

// Crests : mémoire partagée (remplie par worker) + fallback synchrone
// (factory tourne sur thread binder, réseau autorisé).
object CrestCache {
    private val mem = LruCache<String, Bitmap>(80)
    fun get(url: String): Bitmap? {
        if (url.isBlank()) return null
        mem[url]?.let { return it }
        return try {
            val bmp = BitmapFactory.decodeStream(URL(url).openStream()) ?: return null
            val scaled = Bitmap.createScaledBitmap(bmp, 96, 96, true)
            mem.put(url, scaled)
            scaled
        } catch (_: Exception) { null }
    }
    fun put(url: String, bmp: Bitmap) {
        if (url.isNotBlank()) try { mem.put(url, bmp) } catch (_: Exception) {}
    }
}
