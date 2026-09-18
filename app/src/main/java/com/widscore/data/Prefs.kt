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
        s.matchClickAction = if (o.optString("matchClickAction") == "google") "google" else "details"
        val df = o.optString("dateFormat", "daynumeric")
        s.dateFormat = if (df == "numeric" || df == "daynumeric") df else "text"
        s.lang = if (o.optString("lang") == "fr") "fr" else "en"
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
