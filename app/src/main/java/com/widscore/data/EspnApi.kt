package com.widscore.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Client ESPN copié EspnService.cs Palisades : CDN scoreboard sans clé + schedule backfill.
object EspnApi {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) WidScore/1.0"
    private var lastCall = 0L

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        synchronized(this@EspnApi) {
            val wait = 150L - (System.currentTimeMillis() - lastCall)
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 20000
                setRequestProperty("User-Agent", UA)
            }
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode !in 200..299) return@withContext null
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) { null } finally { conn?.disconnect() }
    }

    suspend fun getMatches(leagueSlug: String): List<EspnMatch> {
        val slug = leagueSlug.trim()
        if (slug.isEmpty()) return emptyList()
        val body = get("https://cdn.espn.com/core/soccer/scoreboard?league=${Uri.encode(slug)}&xhr=1")
            ?: return emptyList()
        return try { parseScoreboard(body, slug) } catch (_: Exception) { emptyList() }
    }

    suspend fun getTeamSchedule(leagueSlug: String, teamId: String): List<EspnMatch> {
        val body = get(
            "https://site.web.api.espn.com/apis/site/v2/sports/soccer/" +
                "${Uri.encode(leagueSlug)}/teams/${Uri.encode(teamId)}/schedule"
        ) ?: return emptyList()
        return try { parseSchedule(body, leagueSlug) } catch (_: Exception) { emptyList() }
    }

    // Tri Palisades CompareMatches : live > à venir (date) > terminés.
    fun sortMatches(list: List<EspnMatch>): List<EspnMatch> =
        list.sortedWith(compareBy({ !it.isLive }, { it.isFinished }, { it.utcMillis }))

    // Filtre favoris + rétention terminés (kickoff + 115 min), copie RefreshAsync.
    fun applySettings(all: List<EspnMatch>, s: FootballSettings): List<EspnMatch> {
        var list = all.toList()
        val favTeams = s.teams.filter { it.kind != "league" }.map { it.id }.toSet()
        val favLeagues = s.teams.filter { it.kind == "league" }.map { it.id.lowercase() }.toSet()
        if (favTeams.isNotEmpty() || favLeagues.isNotEmpty()) {
            list = list.filter {
                favLeagues.contains(it.leagueSlug.lowercase()) ||
                    favTeams.contains(it.home.id) || favTeams.contains(it.away.id)
            }
        }
        list = if (s.finishedHours <= 0) {
            list.filter { !it.isFinished }
        } else {
            val now = System.currentTimeMillis()
            list.filter {
                !it.isFinished || (now - (it.utcMillis + 115 * 60_000L)) <= s.finishedHours * 3600_000L
            }
        }
        val upcoming = sortMatches(list.filter { !it.isFinished })
        val finished = list.filter { it.isFinished }.sortedByDescending { it.utcMillis }
        val ordered = if (s.finishedPosition == "top") finished + upcoming else upcoming + finished
        return ordered.take(s.maxMatches.coerceIn(1, 50))
    }

    // --- parsing (mêmes champs que ParseScoreboard/ParseSchedule) ---
    private fun parseScoreboard(body: String, leagueSlug: String): List<EspnMatch> {
        val json = JSONObject(body)
        val root = json.optJSONObject("content")?.optJSONObject("sbData") ?: json
        val events = root.optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i ->
            parseEvent(events.optJSONObject(i), leagueSlug)
        }
    }

    private fun parseSchedule(body: String, leagueSlug: String): List<EspnMatch> {
        val json = JSONObject(body)
        val events = json.optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i ->
            val e = events.optJSONObject(i) ?: return@mapNotNull null
            parseEvent(e, leagueSlug, dateKey = "date")
        }
    }

    private fun parseEvent(e: JSONObject?, leagueSlug: String, dateKey: String = ""): EspnMatch? {
        if (e == null) return null
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val m = EspnMatch(
            id = e.optString("id", System.nanoTime().toString()),
            leagueSlug = leagueSlug,
            leagueName = CuratedLeagues.nameOf(leagueSlug)
        )
        val dateStr = if (dateKey.isNotEmpty()) e.optString(dateKey, comp.optString("date")) else comp.optString("date")
        m.utcMillis = parseDateUtc(dateStr)
        val status = comp.optJSONObject("status")
        m.state = status?.optJSONObject("type")?.optString("state", "")?.lowercase() ?: ""
        m.clock = status?.optString("displayClock", "") ?: ""
        m.detail = status?.optJSONObject("type")?.optString("shortDetail", "") ?: ""
        val comps = comp.optJSONArray("competitors")
        var homeObj: JSONObject? = null
        var awayObj: JSONObject? = null
        if (comps != null) for (i in 0 until comps.length()) {
            val c = comps.getJSONObject(i)
            when (c.optString("homeAway")) {
                "home" -> homeObj = c
                "away" -> awayObj = c
            }
        }
        if (homeObj == null && comps != null && comps.length() > 0) homeObj = comps.getJSONObject(0)
        if (awayObj == null && comps != null && comps.length() > 1) awayObj = comps.getJSONObject(1)
        m.home = parseTeam(homeObj)
        m.away = parseTeam(awayObj)
        m.homeScore = parseScore(homeObj?.opt("score"))
        m.awayScore = parseScore(awayObj?.opt("score"))
        return m
    }

    private fun parseTeam(c: JSONObject?): EspnTeam {
        if (c == null) return EspnTeam()
        val info = c.optJSONObject("team") ?: c
        return EspnTeam(
            id = info.optString("id", ""),
            name = info.optString("displayName", info.optString("name", "")),
            abbr = info.optString("abbreviation", "").uppercase(),
            logo = info.optString("logo", "")
        )
    }

    private fun parseScore(v: Any?): Int? = when (v) {
        is JSONObject -> v.optString("displayValue", "").trim().toIntOrNull()
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun parseDateUtc(s: String): Long {
        if (s.isBlank()) return 0L
        val fmts = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        for (f in fmts) try {
            val sdf = SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return sdf.parse(s)?.time ?: 0L
        } catch (_: Exception) {}
        return try {
            java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: Exception) { 0L }
    }
}
