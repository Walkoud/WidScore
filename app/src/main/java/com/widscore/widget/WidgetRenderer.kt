package com.widscore.widget

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.widscore.R
import com.widscore.data.EspnMatch
import com.widscore.data.FootballSettings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Rendu RemoteViews copiant Palisades : BuildRow (classic) + BuildDarkCard (dark).
object WidgetRenderer {
    private const val ACCENT = "#7DD3FC"
    private const val LIVE = "#FF5F56"
    private const val DIM = "#8A8E96"

    fun render(
        ctx: Context, matches: List<EspnMatch>, s: FootballSettings, dark: Boolean
    ): RemoteViews {
        val views = RemoteViews(ctx.packageName, if (dark) R.layout.widget_dark else R.layout.widget_classic)
        views.setTextViewText(R.id.header_title, "⚽ WidScore")
        views.setOnClickPendingIntent(R.id.btn_refresh, BaseScoreProvider.refreshIntent(ctx))
        views.setOnClickPendingIntent(R.id.header_title, BaseScoreProvider.openAppIntent(ctx))
        views.removeAllViews(R.id.rows)

        val live = matches.count { it.isLive }
        views.setTextViewText(R.id.live_label, if (live > 0) "● $live LIVE" else "")
        if (matches.isEmpty()) {
            views.setTextViewText(R.id.status_label, "Aucun match. Ajoutez ligues/équipes dans l'app.")
            return views
        }
        views.setTextViewText(R.id.status_label, "")

        val upcoming = matches.filter { !it.isFinished }
        val finished = matches.filter { it.isFinished }
        if (s.showFinishedHeader && finished.isNotEmpty() && upcoming.isNotEmpty()) {
            if (dark) {
                // dark : bandes date par groupe (copie RenderDarkGroup) — simplifié : header section.
                if (s.finishedPosition == "top") {
                    addSection(ctx, views, "Finished (${finished.size})")
                    finished.forEach { addMatch(ctx, views, it, s, dark = true) }
                    addSection(ctx, views, "Upcoming (${upcoming.size})")
                    upcoming.forEach { addMatch(ctx, views, it, s, dark = true) }
                } else {
                    addSection(ctx, views, "Upcoming (${upcoming.size})")
                    upcoming.forEach { addMatch(ctx, views, it, s, dark = true) }
                    addSection(ctx, views, "Finished (${finished.size})")
                    finished.forEach { addMatch(ctx, views, it, s, dark = true) }
                }
            } else {
                if (s.finishedPosition == "top") {
                    addSection(ctx, views, "Finished (${finished.size})")
                    finished.forEach { addMatch(ctx, views, it, s, dark = false) }
                    addSection(ctx, views, "Upcoming (${upcoming.size})")
                    upcoming.forEach { addMatch(ctx, views, it, s, dark = false) }
                } else {
                    addSection(ctx, views, "Upcoming (${upcoming.size})")
                    upcoming.forEach { addMatch(ctx, views, it, s, dark = false) }
                    addSection(ctx, views, "Finished (${finished.size})")
                    finished.forEach { addMatch(ctx, views, it, s, dark = false) }
                }
            }
        } else {
            matches.forEach { addMatch(ctx, views, it, s, dark) }
        }
        views.setViewVisibility(R.id.rows, View.VISIBLE)
        return views
    }

    private fun addSection(ctx: Context, root: RemoteViews, text: String) {
        val item = RemoteViews(ctx.packageName, R.layout.widget_section_header)
        item.setTextViewText(R.id.section_text, text)
        root.addView(R.id.rows, item)
    }

    private fun addMatch(ctx: Context, root: RemoteViews, m: EspnMatch, s: FootballSettings, dark: Boolean) {
        val item = RemoteViews(ctx.packageName, if (dark) R.layout.widget_item_dark else R.layout.widget_item_classic)

        // Statut : copie BuildRow/BuildDarkCenter.
        val (status, statusColor) = when {
            m.isLive -> "● ${m.clock.ifBlank { "LIVE" }}" to LIVE
            m.isFinished -> {
                val label = if (s.showFinishedDates && m.utcMillis > 0)
                    shortDate(m.utcMillis, s) + " · FT" else "FT"
                label to s.finishedTextColor.ifBlank { DIM }
            }
            else -> (if (m.utcMillis > 0) shortDate(m.utcMillis, s) + " " + timeOf(m.utcMillis) else "—") to DIM
        }
        item.setTextViewText(R.id.status, status)
        try { item.setTextColor(R.id.status, Color.parseColor(statusColor)) } catch (_: Exception) {}

        val league = m.leagueName.ifBlank { m.leagueSlug }
        item.setTextViewText(R.id.league, "☆ $league")

        val homeName = shortName(m.home.name)
        val awayName = shortName(m.away.name)
        if (dark) {
            item.setTextViewText(R.id.home_code, triCode(m.home.name, m.home.abbr))
            item.setTextViewText(R.id.away_code, triCode(m.away.name, m.away.abbr))
            val score = if ((m.isLive || m.isFinished) && m.homeScore != null && m.awayScore != null)
                "${m.homeScore} - ${m.awayScore}" else timeOf(m.utcMillis).ifBlank { "—" }
            item.setTextViewText(R.id.center_score, score)
            val sub = when {
                m.isLive -> "● ${m.clock.ifBlank { "LIVE" }}"
                m.isFinished -> "FT"
                else -> ""
            }
            item.setTextViewText(R.id.center_sub, sub)
            item.setViewVisibility(R.id.center_sub, if (sub.isEmpty()) View.GONE else View.VISIBLE)
            if (m.isFinished && m.utcMillis > 0) item.setTextViewText(R.id.footer_date, shortDate(m.utcMillis, s))
            else item.setTextViewText(R.id.footer_date, "")
        } else {
            item.setTextViewText(R.id.home_name, homeName)
            item.setTextViewText(R.id.away_name, awayName)
            val score = if ((m.isLive || m.isFinished) && m.homeScore != null && m.awayScore != null)
                "${m.homeScore} - ${m.awayScore}" else "vs"
            item.setTextViewText(R.id.score, score)
            try {
                item.setTextColor(R.id.score, Color.parseColor(if (m.isLive) LIVE else "#F0F0F0"))
            } catch (_: Exception) {}
        }

        // Crests (option showCrests Palisades).
        if (s.showCrests) {
            CrestCache.get(m.home.logo)?.let { item.setImageViewBitmap(R.id.home_crest, it) }
            CrestCache.get(m.away.logo)?.let { item.setImageViewBitmap(R.id.away_crest, it) }
        } else {
            try {
                item.setViewVisibility(R.id.home_crest, View.GONE)
                item.setViewVisibility(R.id.away_crest, View.GONE)
            } catch (_: Exception) {}
        }

        // Clic → détails app ou Google (matchClickAction Palisades).
        val title = "${m.home.name} vs ${m.away.name}"
        val pi = if (s.matchClickAction == "google") {
            val i = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(title))
            )
            android.app.PendingIntent.getActivity(ctx, m.id.hashCode(), i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        } else {
            BaseScoreProvider.matchIntent(ctx, m.leagueSlug, m.id, title, m.id.hashCode())
        }
        item.setOnClickPendingIntent(R.id.item_root, pi)
        root.addView(R.id.rows, item)
    }

    private fun shortName(n: String) = if (n.length > 16) n.take(15) + "…" else n.ifBlank { "?" }

    private fun triCode(name: String, abbr: String): String {
        if (abbr.length == 3) return abbr.uppercase()
        val n = name.trim()
        if (n.length <= 3) return n.uppercase()
        return n.filter { it.isLetterOrDigit() }.take(3).uppercase().ifBlank { "?" }
    }

    private fun timeOf(millis: Long): String {
        if (millis <= 0) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
    }

    // FormatShortDate Palisades : Today/Hier + heure, ou text/numeric/daynumeric.
    private fun shortDate(millis: Long, s: FootballSettings): String {
        val cal = Calendar.getInstance()
        val today = Calendar.getInstance()
        cal.timeInMillis = millis
        val sameDay = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        today.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val d = Date(millis)
        if (sameDay) return "Today"
        if (yesterday) return "Hier"
        return when (s.dateFormat) {
            "numeric" -> SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(d)
            "daynumeric" -> SimpleDateFormat("EEE dd/MM/yy", Locale.getDefault()).format(d)
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(d)
        }
    }
}
