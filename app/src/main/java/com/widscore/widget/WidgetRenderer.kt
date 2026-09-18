package com.widscore.widget

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.widscore.R
import com.widscore.data.EspnMatch
import com.widscore.data.FootballSettings
import com.widscore.data.Lang
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Rendu RemoteViews : headers + items classic (codes 3 lettres) + items dark (spec card).
object WidgetRenderer {
    const val LIVE = "#FF5F56"
    const val DIM = "#8A8E96"

    // Header fixe : logo + live/heure MAJ + refresh. Contenu localisé.
    fun buildHeader(
        ctx: Context, dark: Boolean, liveCount: Int, updatedAt: Long, emptySetup: Boolean
    ): RemoteViews {
        val lctx = Lang.localizedContext(ctx)
        val views = RemoteViews(ctx.packageName, if (dark) R.layout.widget_dark else R.layout.widget_classic)
        if (!dark) views.setTextViewText(R.id.header_title, "⚽ WidScore")
        val time = if (updatedAt > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(updatedAt)) else ""
        if (liveCount > 0) {
            views.setTextViewText(R.id.live_label, "● $liveCount ${lctx.getString(R.string.w_live)}")
            views.setTextViewText(R.id.header_time, time)
        } else {
            views.setTextViewText(R.id.live_label, "")
            views.setTextViewText(R.id.header_time, time)
        }
        views.setOnClickPendingIntent(R.id.btn_refresh, BaseScoreProvider.refreshIntent(ctx))
        if (!dark) views.setOnClickPendingIntent(R.id.header_title, BaseScoreProvider.openAppIntent(ctx))
        else views.setOnClickPendingIntent(R.id.logo_box, BaseScoreProvider.openAppIntent(ctx))
        // Clic sur item de liste géré par template (factory) ; empty view -> ouvre l'app.
        views.setOnClickPendingIntent(R.id.empty_view, BaseScoreProvider.openAppIntent(ctx))
        views.setEmptyView(R.id.match_list, R.id.empty_view)
        views.setTextViewText(
            R.id.empty_view,
            lctx.getString(if (emptySetup) R.string.w_empty_setup else R.string.w_empty_none)
        )
        return views
    }

    fun buildDateHeader(ctx: Context, text: String): RemoteViews {
        val views = RemoteViews(ctx.packageName, R.layout.widget_date_header)
        views.setTextViewText(android.R.id.text1, text)
        return views
    }

    // Lignes de la liste : DateRow(text) ou MatchRow(match). Regroupés par jour,
    // terminés haut/bas selon réglage (copie RenderMatches/RenderDarkGroup Palisades).
    sealed interface Row {
        data class Date(val text: String) : Row
        data class Match(val match: EspnMatch) : Row
    }

    fun buildRows(matches: List<EspnMatch>, s: FootballSettings, locale: Locale, todayStr: String, tomorrowStr: String, yesterdayStr: String, finishedStr: String, upcomingStr: String): List<Row> {
        val rows = mutableListOf<Row>()
        val upcoming = matches.filter { !it.isFinished }
        val finished = matches.filter { it.isFinished }.sortedByDescending { it.utcMillis }
        val showSections = s.showFinishedHeader && finished.isNotEmpty() && upcoming.isNotEmpty()
        fun addGroup(list: List<EspnMatch>) {
            var lastDay = "--"
            for (m in list) {
                val day = dayKey(m.utcMillis)
                if (day != lastDay) {
                    lastDay = day
                    rows.add(Row.Date(dateLabel(m.utcMillis, locale, todayStr, tomorrowStr, yesterdayStr)))
                }
                rows.add(Row.Match(m))
            }
        }
        if (showSections) {
            if (s.finishedPosition == "top") {
                rows.add(Row.Date("$finishedStr (${finished.size})"))
                addGroup(finished)
                rows.add(Row.Date("$upcomingStr (${upcoming.size})"))
                addGroup(upcoming)
            } else {
                rows.add(Row.Date("$upcomingStr (${upcoming.size})"))
                addGroup(upcoming)
                rows.add(Row.Date("$finishedStr (${finished.size})"))
                addGroup(finished)
            }
        } else {
            // Ordre global déjà trié (applySettings) : simple regroupement par jour.
            addGroup(matches)
        }
        return rows
    }

    private fun dayKey(millis: Long): String {
        if (millis <= 0) return "?"
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "%d-%d".format(c.get(Calendar.YEAR), c.get(Calendar.DAY_OF_YEAR))
    }

    // "Today" / "Tomorrow, Fri 11.09." / "Sat 12.09." localisé (copie spec + FormatBandDate).
    fun dateLabel(millis: Long, locale: Locale, todayStr: String, tomorrowStr: String, yesterdayStr: String): String {
        if (millis <= 0) return "—"
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance()
        fun same(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val d = Date(millis)
        val fmt = SimpleDateFormat("EEE dd.MM.", locale)
        return when {
            same(cal, today) -> todayStr
            same(cal, tomorrow) -> "$tomorrowStr, ${fmt.format(d)}"
            same(cal, yesterday) -> "$yesterdayStr, ${fmt.format(d)}"
            else -> fmt.format(d)
        }
    }

    fun timeOf(millis: Long): String {
        if (millis <= 0) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    }

    fun triCode(name: String, abbr: String): String {
        if (abbr.length == 3) return abbr.uppercase()
        val n = name.trim()
        if (n.length <= 3) return n.uppercase()
        return n.filter { it.isLetterOrDigit() }.take(3).uppercase().ifBlank { "?" }
    }

    // Item classic : statut + ligue + codes 3 lettres + score.
    fun buildClassicItem(ctx: Context, m: EspnMatch, s: FootballSettings, lctx: Context): RemoteViews {
        val item = RemoteViews(ctx.packageName, R.layout.widget_item_classic)
        val (status, color) = statusText(m, s, lctx)
        item.setTextViewText(R.id.status, status)
        try { item.setTextColor(R.id.status, Color.parseColor(color)) } catch (_: Exception) {}
        item.setTextViewText(R.id.league, "☆ ${m.leagueName.ifBlank { m.leagueSlug }}")
        item.setTextViewText(R.id.home_code, triCode(m.home.name, m.home.abbr))
        item.setTextViewText(R.id.away_code, triCode(m.away.name, m.away.abbr))
        val score = if ((m.isLive || m.isFinished) && m.homeScore != null && m.awayScore != null)
            "${m.homeScore} - ${m.awayScore}" else "vs"
        item.setTextViewText(R.id.score, score)
        try { item.setTextColor(R.id.score, Color.parseColor(if (m.isLive) LIVE else "#F0F0F0")) } catch (_: Exception) {}
        if (s.showCrests) {
            CrestCache.get(m.home.logo)?.let { item.setImageViewBitmap(R.id.home_crest, it) }
            CrestCache.get(m.away.logo)?.let { item.setImageViewBitmap(R.id.away_crest, it) }
        } else {
            item.setViewVisibility(R.id.home_crest, View.GONE)
            item.setViewVisibility(R.id.away_crest, View.GONE)
        }
        return item
    }

    // Item dark spec : carte #1E1E1E, blasons + heure/score centrés, codes dessous.
    fun buildDarkItem(ctx: Context, m: EspnMatch, s: FootballSettings): RemoteViews {
        val item = RemoteViews(ctx.packageName, R.layout.widget_item_dark)
        item.setTextViewText(R.id.home_code, triCode(m.home.name, m.home.abbr))
        item.setTextViewText(R.id.away_code, triCode(m.away.name, m.away.abbr))
        val (main, sub, subColor) = when {
            m.isLive -> Triple(
                if (m.homeScore != null && m.awayScore != null) "${m.homeScore} - ${m.awayScore}" else "LIVE",
                "● ${m.clock.ifBlank { "LIVE" }}", LIVE
            )
            m.isFinished -> Triple(
                if (m.homeScore != null && m.awayScore != null) "${m.homeScore} - ${m.awayScore}" else "FT",
                "FT", s.finishedTextColor.ifBlank { DIM }
            )
            else -> Triple(timeOf(m.utcMillis).ifBlank { "—" }, "", DIM)
        }
        item.setTextViewText(R.id.center_main, main)
        if (m.isLive) try { item.setTextColor(R.id.center_main, Color.parseColor(LIVE)) } catch (_: Exception) {}
        item.setTextViewText(R.id.center_sub, sub)
        item.setViewVisibility(R.id.center_sub, if (sub.isEmpty()) View.GONE else View.VISIBLE)
        try { item.setTextColor(R.id.center_sub, Color.parseColor(subColor)) } catch (_: Exception) {}
        if (s.showCrests) {
            CrestCache.get(m.home.logo)?.let { item.setImageViewBitmap(R.id.home_crest, it) }
            CrestCache.get(m.away.logo)?.let { item.setImageViewBitmap(R.id.away_crest, it) }
        } else {
            item.setViewVisibility(R.id.home_crest, View.GONE)
            item.setViewVisibility(R.id.away_crest, View.GONE)
        }
        return item
    }

    // Ligne statut classic (copie BuildRow Palisades).
    private fun statusText(m: EspnMatch, s: FootballSettings, lctx: Context): Pair<String, String> {
        val locale = lctx.resources.configuration.locales.get(0) ?: Locale.ENGLISH
        return when {
            m.isLive -> "● ${m.clock.ifBlank { lctx.getString(R.string.w_live) }}" to LIVE
            m.isFinished -> {
                val label = if (s.showFinishedDates && m.utcMillis > 0)
                    shortDate(m.utcMillis, s, locale, lctx) + " · ${lctx.getString(R.string.w_ft)}"
                else lctx.getString(R.string.w_ft)
                label to s.finishedTextColor.ifBlank { DIM }
            }
            else -> {
                val d = if (m.utcMillis > 0) shortDate(m.utcMillis, s, locale, lctx) + " " + timeOf(m.utcMillis) else "—"
                d to DIM
            }
        }
    }

    private fun shortDate(millis: Long, s: FootballSettings, locale: Locale, lctx: Context): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance()
        fun same(b: Calendar) =
            cal.get(Calendar.YEAR) == b.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val d = Date(millis)
        if (same(today)) return lctx.getString(R.string.w_today)
        if (same(yesterday)) return lctx.getString(R.string.w_yesterday)
        return when (s.dateFormat) {
            "numeric" -> SimpleDateFormat("dd/MM/yy", locale).format(d)
            "daynumeric" -> SimpleDateFormat("EEE dd/MM/yy", locale).format(d)
            else -> SimpleDateFormat("d MMM", locale).format(d)
        }
    }
}
