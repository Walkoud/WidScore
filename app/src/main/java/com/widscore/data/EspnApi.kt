package com.widscore.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

    // Statut dernière synchro par ligue (affiché dans l'app : erreurs, rate limit 429).
    data class LeagueStatus(val league: String, val ok: Boolean, val count: Int, val rateLimited: Boolean)
    // Backfill schedules par équipe suivie (matchs terminés hors fenêtre CDN).
    data class ScheduleStatus(val teamId: String, val team: String, val league: String, val ok: Boolean, val count: Int, val source: String = "")
    @Volatile var lastReport: List<LeagueStatus> = emptyList()
    @Volatile var lastSchedules: List<ScheduleStatus> = emptyList()
    @Volatile var lastSyncAt: Long = 0L

    private suspend fun getWithCode(url: String): Pair<String?, Int> = withContext(Dispatchers.IO) {
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
            val code = conn.responseCode
            if (code !in 200..299) return@withContext null to code
            conn.inputStream.bufferedReader().readText() to code
        } catch (_: Exception) { null to -1 } finally { conn?.disconnect() }
    }

    private suspend fun get(url: String): String? = getWithCode(url).first

    // Accès brut pour RosterStore (ref-walk core.api, copie GetRawAsync).
    suspend fun getRaw(url: String): String? = get(url)

    suspend fun getMatches(leagueSlug: String): List<EspnMatch> {
        val slug = leagueSlug.trim()
        if (slug.isEmpty()) return emptyList()
        val (body, code) = getWithCode("https://cdn.espn.com/core/soccer/scoreboard?league=${Uri.encode(slug)}&xhr=1")
        if (body == null) {
            recordStatus(slug, false, 0, code == 429)
            LogStore.log("ESPN", "$slug FAIL http=$code" + if (code == 429) " RATE-LIMITED" else "")
            return emptyList()
        }
        return try {
            val list = parseScoreboard(body, slug)
            recordStatus(slug, true, list.size, false)
            list
        } catch (_: Exception) {
            recordStatus(slug, false, 0, false)
            emptyList()
        }
    }

    private fun recordStatus(league: String, ok: Boolean, count: Int, rate: Boolean) {
        lastReport = (lastReport.filter { it.league != league } + LeagueStatus(league, ok, count, rate)).takeLast(40)
        lastSyncAt = System.currentTimeMillis()
    }

    suspend fun getTeamSchedule(leagueSlug: String, teamId: String, teamName: String = ""): List<EspnMatch> {
        val name = teamName.ifBlank { teamId }
        // 1. site.web.api (source Palisades).
        var list = fetchSchedule(
            "https://site.web.api.espn.com/apis/site/v2/sports/soccer/" +
                "${Uri.encode(leagueSlug)}/teams/${Uri.encode(teamId)}/schedule", leagueSlug
        )
        var source = "web.api"
        // 2. site.api (même payload, autre host si Akamai bloque).
        if (list == null) {
            list = fetchSchedule(
                "https://site.api.espn.com/apis/site/v2/sports/soccer/" +
                    "${Uri.encode(leagueSlug)}/teams/${Uri.encode(teamId)}/schedule", leagueSlug
            )
            source = "site.api"
        }
        // 3. CDN dates sweep : scoreboard jour par jour (6 jours passés), filtré équipe.
        if (list == null) {
            list = sweepPast(leagueSlug, teamId)
            source = "cdn-dates"
        }
        val final = list ?: emptyList()
        lastSchedules = (lastSchedules.filter { it.teamId != teamId } +
            ScheduleStatus(teamId, name, leagueSlug, list != null, final.size, source)).takeLast(20)
        return final
    }

    private suspend fun fetchSchedule(url: String, leagueSlug: String): List<EspnMatch>? {
        val (body, _) = getWithCode(url)
        if (body == null) return null
        return try { parseSchedule(body, leagueSlug) } catch (_: Exception) { null }
    }

    // Clés mois local M/M+1/M+2 au format YYYYMM (port Palisades fec1685 :
    // le widget affiche des jours locaux, et les fixtures restent visibles
    // ~3 mois). Le CDN reste premier : le frais gagne toujours au dedup.
    fun monthKeys(): List<String> {
        return try {
            val sdf = SimpleDateFormat("yyyyMM", Locale.US)
            val cal = java.util.Calendar.getInstance()
            (0..2).map { off ->
                cal.timeInMillis = System.currentTimeMillis()
                cal.add(java.util.Calendar.MONTH, off)
                sdf.format(cal.time)
            }
        } catch (_: Exception) { emptyList() }
    }

    // Scoreboard mensuel via site.web.api (?dates=YYYYMM, non bloqué Akamai).
    // Retourne null en cas d'échec (le cache disque périmé prend le relais).
    // Port Palisades fec1685 (GetWebApiMonthAsync/ParseWebApiScoreboard).
    suspend fun getWebApiMonth(leagueSlug: String, yyyymm: String): List<EspnMatch>? {
        return try {
            val (body, _) = getWithCode(
                "https://site.web.api.espn.com/apis/site/v2/sports/soccer/" +
                    Uri.encode(leagueSlug.trim()) + "/scoreboard?dates=" + yyyymm
            )
            if (body == null) return null
            try {
                parseSchedule(body, leagueSlug).onEach {
                    if (it.state == "pre") {
                        // web.api envoie des scores factices "0" pour les non-joués.
                        it.homeScore = null
                        it.awayScore = null
                    }
                }
            } catch (_: Exception) { null }
        } catch (_: Exception) { null }
    }

    // Balaye les 6 derniers jours du scoreboard CDN pour une équipe
    // (matchs terminés déjà sortis de la fenêtre courante).
    private suspend fun sweepPast(leagueSlug: String, teamId: String): List<EspnMatch>? {
        return try {
            val out = mutableListOf<EspnMatch>()
            val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            for (d in 1..6) {
                cal.timeInMillis = System.currentTimeMillis()
                cal.add(java.util.Calendar.DAY_OF_YEAR, -d)
                val url = "https://cdn.espn.com/core/soccer/scoreboard?league=" +
                    Uri.encode(leagueSlug.trim()) + "&dates=" + sdf.format(cal.time) + "&xhr=1"
                val (body, _) = getWithCode(url)
                if (body == null) continue
                try {
                    out += parseScoreboard(body, leagueSlug)
                        .filter { it.home.id == teamId || it.away.id == teamId }
                } catch (_: Exception) {}
            }
            out
        } catch (_: Exception) { null }
    }

    // Fixtures saison d'une équipe (core.api team events) : TOUTE la saison,
    // y compris matchs à venir hors fenêtre CDN et hors schedules.
    // Léger : headers event (date + nom), sans competitors (1 appel par fixture).
    data class TeamFixture(val eventId: String, val utcMillis: Long, val homeName: String, val awayName: String)

    private fun seasonYear(): Int {
        val cal = java.util.Calendar.getInstance()
        val year = cal.get(java.util.Calendar.YEAR)
        // Saison août-mai : avant juillet, saison commencée l'année précédente.
        return if (cal.get(java.util.Calendar.MONTH) >= java.util.Calendar.JULY) year else year - 1
    }

    suspend fun getTeamFixtures(leagueSlug: String, teamId: String): List<TeamFixture>? {
        return try {
            val base = "https://sports.core.api.espn.com/v2/sports/soccer/leagues/" +
                android.net.Uri.encode(leagueSlug.trim()) + "/seasons/" + seasonYear() +
                "/teams/" + android.net.Uri.encode(teamId.trim()) + "/events?lang=en&region=us"
            val refs = getRefList(base)
            if (refs.isEmpty()) return emptyList()
            val out = mutableListOf<TeamFixture>()
            for (ref in refs) {
                val (body, _) = getWithCode(fixRef(ref))
                if (body == null) continue
                try {
                    val json = JSONObject(body)
                    val id = json.optString("id")
                    val date = parseDateUtc(json.optString("date"))
                    val name = json.optString("name")
                    if (id.isBlank() || date <= 0 || name.isBlank()) continue
                    val (home, away) = splitFixtureName(name) ?: continue
                    out.add(TeamFixture(id, date, home, away))
                } catch (_: Exception) {}
            }
            out
        } catch (_: Exception) { null }
    }

    // "Racing Santander at Barcelona" -> home=Barcelona, away=Racing ("X at Y").
    // "Barcelona vs Sevilla" -> home=Barcelona, away=Sevilla.
    private fun splitFixtureName(name: String): Pair<String, String>? {
        val atIdx = name.indexOf(" at ", ignoreCase = true)
        if (atIdx > 0) {
            val away = name.substring(0, atIdx).trim()
            val home = name.substring(atIdx + 4).trim()
            if (away.isNotEmpty() && home.isNotEmpty()) return home to away
        }
        val vsIdx = name.indexOf(" vs ", ignoreCase = true)
        if (vsIdx > 0) {
            val home = name.substring(0, vsIdx).trim()
            val away = name.substring(vsIdx + 4).trim()
            if (home.isNotEmpty() && away.isNotEmpty()) return home to away
        }
        return null
    }

    private suspend fun getRefList(collectionUrl: String): List<String> {
        val refs = mutableListOf<String>()
        return try {
            val sep = if (collectionUrl.contains("?")) "&" else "?"
            var next: String? = collectionUrl + sep + "limit=100"
            var pages = 0
            while (next != null && pages < 10 && refs.size < 500) {
                pages++
                val (body, _) = getWithCode(next) ?: break
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: break
                for (i in 0 until items.length()) {
                    val r = items.optJSONObject(i)?.optString("\$ref") ?: ""
                    if (r.isNotBlank()) refs.add(r)
                }
                next = null
                if (json.optInt("pageCount", 1) > json.optInt("pageIndex", 1)) {
                    next = collectionUrl + sep + "limit=100&page=" + (json.optInt("pageIndex", 1) + 1)
                }
            }
            refs
        } catch (_: Exception) { refs }
    }

    private fun fixRef(url: String): String {
        return if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url
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

// Cache disque des payloads réseau lents (mois 12h, schedules 30min).
// Adaptation Android des caches mémoire Palisades (monthCache 12h, schedCache
// 30min) : le process du worker meurt entre les runs, la mémoire ne suffit pas.
// Fichier unique : {key: {fetchedAt, matches[]}}. Anti-empoisonnement : on ne
// stocke que les succès (vide OK) ; en cas d'échec réseau le périmé prend
// le relais au lieu de spammer l'API en boucle.
object NetCache {
    private const val FILE = "football_netcache.json"
    private const val MAX_NODES = 200

    // Nœud frais ou null (absent/périmé/erreur).
    @Synchronized
    fun get(ctx: Context, key: String, ttlMs: Long): List<EspnMatch>? {
        return try {
            val f = java.io.File(ctx.filesDir, FILE)
            if (!f.exists()) return null
            val node = JSONObject(f.readText()).optJSONObject(key) ?: return null
            val at = node.optLong("fetchedAt", 0)
            if (at <= 0 || System.currentTimeMillis() - at >= ttlMs) return null
            readMatches(node)
        } catch (_: Exception) { null }
    }

    // Repli offline : le périmé vaut mieux que rien quand le réseau échoue.
    @Synchronized
    fun getStale(ctx: Context, key: String): List<EspnMatch> {
        return try {
            val f = java.io.File(ctx.filesDir, FILE)
            if (!f.exists()) return emptyList()
            val node = JSONObject(f.readText()).optJSONObject(key) ?: return emptyList()
            readMatches(node)
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun put(ctx: Context, key: String, matches: List<EspnMatch>) {
        try {
            val f = java.io.File(ctx.filesDir, FILE)
            val root = try {
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            } catch (_: Exception) { JSONObject() }
            root.put(
                key, JSONObject()
                    .put("fetchedAt", System.currentTimeMillis())
                    .put("matches", JSONArray(matches.map { it.toJson() }))
            )
            // Borne anti-gonflement : vire les nœuds les plus vieux.
            val keys = root.keys().asSequence().toList()
            if (keys.size > MAX_NODES) {
                val sorted = keys.sortedBy { root.optJSONObject(it)?.optLong("fetchedAt", 0) ?: 0 }
                for (k in sorted.take(keys.size - MAX_NODES)) root.remove(k)
            }
            f.writeText(root.toString())
        } catch (_: Exception) {}
    }

    private fun readMatches(node: JSONObject): List<EspnMatch> {
        val arr = node.optJSONArray("matches") ?: return emptyList()
        val out = mutableListOf<EspnMatch>()
        for (i in 0 until arr.length()) {
            try {
                EspnMatch.fromJson(arr.optJSONObject(i) ?: continue)?.let { out.add(it) }
            } catch (_: Exception) {}
        }
        return out
    }
}
