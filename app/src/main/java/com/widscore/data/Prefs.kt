package com.widscore.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Persistance réglages (équivalent ApplyCustomSettings/SaveSettings FootballPlugin)
// + cache matchs pour les factories de widgets scrollables (ListView).
object Prefs {
    private const val FILE = "widscore"
    private const val KEY = "settings"
    private const val KEY_CACHE = "widget_cache"
    private const val KEY_REPORT = "sync_report"

    fun load(ctx: Context): FootballSettings {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return FootballSettings()
        return try { fromJson(JSONObject(raw)) } catch (_: Exception) { FootballSettings() }
    }

    fun save(ctx: Context, s: FootballSettings) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, toJson(s).toString()).apply()
    }

    fun toJson(s: FootballSettings): JSONObject = JSONObject()
        .put("leagues", JSONArray(s.leagues))
        .put("teams", JSONArray(s.teams.map {
            JSONObject().put("id", it.id).put("name", it.name)
                .put("kind", it.kind).put("leagueSlug", it.leagueSlug)
        }))
        .put("refreshMinutes", s.refreshMinutes)
        .put("maxMatches", s.maxMatches)
        .put("showCrests", s.showCrests)
        .put("finishedHours", s.finishedHours)
        .put("finishedPosition", s.finishedPosition)
        .put("finishedTextColor", s.finishedTextColor)
        .put("showFinishedHeader", s.showFinishedHeader)
        .put("showFinishedDates", s.showFinishedDates)
        .put("cardTheme", s.cardTheme)
        .put("matchClickAction", s.matchClickAction)
        .put("dateFormat", s.dateFormat)
        .put("lang", s.lang)
        .put("widgetScale", s.widgetScale.toDouble())
        .put("compact", s.compact)
        .put("showLeague", s.showLeague)

    fun fromJson(o: JSONObject): FootballSettings {
        val s = FootballSettings()
        val arr = o.optJSONArray("leagues")
        // Pas de fallback vers ligues par défaut : rien coché par défaut.
        s.leagues = if (arr == null) mutableListOf()
        else MutableList(arr.length()) { i ->
            arr.getString(i).trim().lowercase()
        }.filter { it.isNotBlank() }.distinct().toMutableList()
        val teams = o.optJSONArray("teams") ?: JSONArray()
        s.teams = mutableListOf()
        for (i in 0 until teams.length()) {
            val t = teams.getJSONObject(i)
            val id = t.optString("id")
            if (id.isNotBlank()) s.teams.add(
                FavTeam(id, t.optString("name"), t.optString("kind", "team"), t.optString("leagueSlug", ""))
            )
        }
        s.refreshMinutes = o.optInt("refreshMinutes", 10).coerceIn(1, 60)
        s.maxMatches = o.optInt("maxMatches", 10).coerceIn(1, 50)
        s.showCrests = o.optBoolean("showCrests", true)
        s.finishedHours = o.optInt("finishedHours", 24).coerceIn(0, 720)
        s.finishedPosition = if (o.optString("finishedPosition") == "top") "top" else "bottom"
        s.finishedTextColor = o.optString("finishedTextColor", "#808080")
        s.showFinishedHeader = o.optBoolean("showFinishedHeader", true)
        s.showFinishedDates = o.optBoolean("showFinishedDates", true)
        s.cardTheme = if (o.optString("cardTheme") == "dark") "dark" else "classic"
        s.matchClickAction = if (o.optString("matchClickAction", "google") == "details") "details" else "google"
        val df = o.optString("dateFormat", "daynumeric")
        s.dateFormat = if (df == "numeric" || df == "daynumeric") df else "text"
        s.lang = if (o.optString("lang") == "fr") "fr" else "en"
        s.widgetScale = o.optDouble("widgetScale", 1.0).toFloat().coerceIn(0.7f, 1.3f)
        s.compact = o.optBoolean("compact", false)
        s.showLeague = o.optBoolean("showLeague", true)
        return s
    }

    // Dernier jeu de matchs affiché (écrit par le worker, lu par les factories ListView).
    fun saveCache(ctx: Context, matches: List<EspnMatch>, updatedAt: Long) {
        try {
            val o = JSONObject()
                .put("updatedAt", updatedAt)
                .put("matches", JSONArray(matches.map { it.toJson() }))
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY_CACHE, o.toString()).apply()
        } catch (_: Exception) {}
    }

    // Rapport synchro (rate limit 429 visible dans l'app).
    fun saveReport(ctx: Context, at: Long, report: List<EspnApi.LeagueStatus>, totalMatches: Int) {
        try {
            val o = JSONObject()
                .put("at", at)
                .put("totalMatches", totalMatches)
                .put("leagues", JSONArray(report.map {
                    JSONObject().put("league", it.league).put("ok", it.ok)
                        .put("count", it.count).put("rate", it.rateLimited)
                }))
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(KEY_REPORT, o.toString()).apply()
        } catch (_: Exception) {}
    }

    data class SyncReport(val at: Long, val totalMatches: Int, val leagues: List<EspnApi.LeagueStatus>)

    fun loadReport(ctx: Context): SyncReport? {
        return try {
            val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_REPORT, null)
                ?: return null
            val o = JSONObject(raw)
            val arr = o.optJSONArray("leagues") ?: JSONArray()
            val list = mutableListOf<EspnApi.LeagueStatus>()
            for (i in 0 until arr.length()) {
                val l = arr.getJSONObject(i)
                list.add(
                    EspnApi.LeagueStatus(
                        l.optString("league"), l.optBoolean("ok"),
                        l.optInt("count"), l.optBoolean("rate")
                    )
                )
            }
            SyncReport(o.optLong("at"), o.optInt("totalMatches"), list)
        } catch (_: Exception) { null }
    }

    fun loadCache(ctx: Context): Pair<Long, List<EspnMatch>> {
        return try {
            val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_CACHE, null)
                ?: return 0L to emptyList()
            val o = JSONObject(raw)
            val arr = o.optJSONArray("matches") ?: JSONArray()
            val list = mutableListOf<EspnMatch>()
            for (i in 0 until arr.length()) {
                EspnMatch.fromJson(arr.getJSONObject(i))?.let { list.add(it) }
            }
            o.optLong("updatedAt") to list
        } catch (_: Exception) { 0L to emptyList() }
    }
}
