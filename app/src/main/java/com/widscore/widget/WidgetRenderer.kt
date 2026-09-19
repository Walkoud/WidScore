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
    // pressed = bouton ⟳ foncé pendant le refresh, restore normal à la fin (worker).
    fun buildHeader(
        ctx: Context, dark: Boolean, liveCount: Int, updatedAt: Long, emptySetup: Boolean,
        pressed: Boolean = false
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
        if (pressed) {
            // État pressé : icône foncée + heure en suspens, le worker restore le normal.
            try {
                views.setTextColor(R.id.btn_refresh, Color.parseColor(if (dark) "#000000" else "#616161"))
            } catch (_: Exception) {}
            views.setTextViewText(R.id.header_time, "…")
        }
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

    // Item classic : date intégrée + statut + ligue + codes + score.
    // TOUT est posé explicitement (texte, couleur, image, visibilité) :
    // jamais de résidu d'une vue recyclée (logos fantômes).
    fun buildClassicItem(ctx: Context, m: EspnMatch, s: FootballSettings, lctx: Context, dateLabel: String = ""): RemoteViews {
        val item = RemoteViews(ctx.packageName, R.layout.widget_item_classic)
        item.setTextViewText(R.id.date_line, dateLabel)
        item.setViewVisibility(R.id.date_line, if (dateLabel.isEmpty()) View.GONE else View.VISIBLE)
        val (status, color) = statusText(m, s, lctx)
        item.setTextViewText(R.id.status, status)
        try { item.setTextColor(R.id.status, Color.parseColor(color)) } catch (_: Exception) {}
        item.setTextViewText(R.id.league, "☆ ${m.leagueName.ifBlank { m.leagueSlug }}")
        item.setViewVisibility(R.id.league, if (!s.showLeague || s.compact) View.GONE else View.VISIBLE)
        item.setTextViewText(R.id.home_code, triCode(m.home.name, m.home.abbr))
        item.setTextViewText(R.id.away_code, triCode(m.away.name, m.away.abbr))
        val score = if ((m.isLive || m.isFinished) && m.homeScore != null && m.awayScore != null)
            "${m.homeScore} - ${m.awayScore}" else "vs"
        item.setTextViewText(R.id.score, score)
        try { item.setTextColor(R.id.score, Color.parseColor(if (m.isLive) LIVE else "#F0F0F0")) } catch (_: Exception) {}
        setCrest(item, R.id.home_crest, if (s.showCrests) m.home.logo else "")
        setCrest(item, R.id.away_crest, if (s.showCrests) m.away.logo else "")
        return item
    }

    // Pose un crest OU un placeholder transparent (chasse les logos fantômes
    // des vues recyclées : une absence de set laisse l'ancienne image).
    private fun setCrest(item: RemoteViews, viewId: Int, url: String) {
        val bmp = if (url.isBlank()) null else CrestCache.get(url)
        if (bmp != null) {
            item.setImageViewBitmap(viewId, bmp)
            item.setViewVisibility(viewId, View.VISIBLE)
        } else {
            try { item.setImageViewResource(viewId, android.R.color.transparent) } catch (_: Exception) {}
            item.setViewVisibility(viewId, View.VISIBLE)
        }
    }

    // Item dark spec : layout selon block size (S 52dp / M 64dp / L 78dp) + scale textes.
    fun buildDarkItem(ctx: Context, m: EspnMatch, s: FootballSettings, dateLabel: String = ""): RemoteViews {
        val layout = when (s.blockSize) {
            "xsmall" -> R.layout.widget_item_dark_s
            "small" -> R.layout.widget_item_dark_s
            "large" -> R.layout.widget_item_dark_l
            else -> R.layout.widget_item_dark
        }
        // Tailles de base par variante (× widgetScale).
        val (baseCode, baseMain, baseSub) = when (s.blockSize) {
            "xsmall" -> Triple(9f, 11f, 7f)
            "small" -> Triple(10f, 12f, 8f)
            "large" -> Triple(12f, 16f, 9f)
            else -> Triple(11f, 14f, 8f)
        }
        val item = RemoteViews(ctx.packageName, layout)
        item.setTextViewText(R.id.date_line, dateLabel)
        item.setViewVisibility(R.id.date_line, if (dateLabel.isEmpty()) View.GONE else View.VISIBLE)
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
        val k = s.widgetScale.coerceIn(0.7f, 1.3f)
        item.setTextViewTextSize(R.id.home_code, android.util.TypedValue.COMPLEX_UNIT_SP, baseCode * k)
        item.setTextViewTextSize(R.id.away_code, android.util.TypedValue.COMPLEX_UNIT_SP, baseCode * k)
        item.setTextViewTextSize(R.id.center_main, android.util.TypedValue.COMPLEX_UNIT_SP, baseMain * k)
        item.setTextViewTextSize(R.id.center_sub, android.util.TypedValue.COMPLEX_UNIT_SP, baseSub * k)
        if (s.showCrests) {
            setCrest(item, R.id.home_crest, m.home.logo)
            setCrest(item, R.id.away_crest, m.away.logo)
        } else {
            item.setViewVisibility(R.id.home_crest, View.GONE)
            item.setViewVisibility(R.id.away_crest, View.GONE)
        }
        return item
    }

    // Perso widgets : scale textes (70-130%), compact (masque ligue + sous-titres),
    // showLeague (ligne ligue classic).
    fun applyItemScale(item: RemoteViews, m: EspnMatch, s: FootballSettings, dark: Boolean) {
        // Facteur bloc (XS 0.8 / S 0.9 / L 1.1) combiné au scale textes.
        val block = when (s.blockSize) { "xsmall" -> 0.8f; "small" -> 0.9f; "large" -> 1.1f; else -> 1.0f }
        val k = (s.widgetScale.coerceIn(0.7f, 1.3f) * block).coerceIn(0.6f, 1.4f)
        if (dark) {
            if (s.compact && !m.isLive) item.setViewVisibility(R.id.center_sub, View.GONE)
        } else {
            item.setTextViewTextSize(R.id.status, android.util.TypedValue.COMPLEX_UNIT_SP, 10f * k)
            item.setTextViewTextSize(R.id.league, android.util.TypedValue.COMPLEX_UNIT_SP, 9f * k)
            item.setTextViewTextSize(R.id.home_code, android.util.TypedValue.COMPLEX_UNIT_SP, 12f * k)
            item.setTextViewTextSize(R.id.away_code, android.util.TypedValue.COMPLEX_UNIT_SP, 12f * k)
            item.setTextViewTextSize(R.id.score, android.util.TypedValue.COMPLEX_UNIT_SP, 14f * k)
            if (!s.showLeague || s.compact) item.setViewVisibility(R.id.league, View.GONE)
        }
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
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        if (same(tomorrow)) return lctx.getString(R.string.w_tomorrow)
        if (same(yesterday)) return lctx.getString(R.string.w_yesterday)
        return when (s.dateFormat) {
            "numeric" -> SimpleDateFormat("dd/MM/yy", locale).format(d)
            "daynumeric" -> SimpleDateFormat("EEE dd/MM/yy", locale).format(d)
            else -> SimpleDateFormat("d MMM", locale).format(d)
        }
    }
}
