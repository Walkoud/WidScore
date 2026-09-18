package com.widscore.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.net.HttpURLConnection
import java.net.URL

// sports.bzzoiro.com API v2 (clé user, header "Authorization: Token ...").
// 3e source : passé + à venir par équipe (team_id + fenêtre dates), cross-ligues.
// Trouvaille : couvre Süper Lig et tous les matchs (ex. Besiktas 4-1 Marseille).
object BzApi {
    private const val BASE = "https://sports.bzzoiro.com/api/v2"
    private const val LEAGUES_FILE = "football_bz_leagues.json"
    private const val TEAMS_FILE = "football_bz_teams.json"
    private const val CACHE_DAYS = 30L
    private var lastCall = 0L
    private const val GAP_MS = 400L

    // bzzoiro league id -> ESPN slug (dedup cross-sources via affiche+jour).
    private val leagueMap = mapOf(
        1 to "eng.1", 12 to "eng.2", 39 to "eng.fa",
        3 to "esp.1", 38 to "esp.2", 41 to "esp.copa_del_rey",
        4 to "ita.1", 42 to "ita.coppa_italia",
        5 to "ger.1", 94 to "ger.2", 43 to "ger.dfb_pokal",
        6 to "fra.1", 44 to "fra.coupe_de_france",
        10 to "ned.1", 2 to "por.1",
        7 to "uefa.champions", 8 to "uefa.europa", 83 to "uefa.europa.conf",
        90 to "uefa.super_cup", 64 to "uefa.nations", 66 to "uefa.euro",
        11 to "tur.1", 9 to "bra.1", 27 to "fifa.world",
        79 to "fifa.friendly", 31 to "fifa.friendly",
        13 to "sco.1", 49 to "jpn.1", 18 to "usa.1"
    )

    fun espnSlug(leagueId: Int): String = leagueMap[leagueId] ?: "bz-$leagueId"

    data class BzStatus(val label: String, val ok: Boolean, val count: Int, val note: String = "")
    @Volatile var lastStatuses: List<BzStatus> = emptyList()

    private suspend fun get(path: String, key: String): Pair<String?, Int> = withContext(Dispatchers.IO) {
        synchronized(this@BzApi) {
            val wait = GAP_MS - (System.currentTimeMillis() - lastCall)
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 20000
                setRequestProperty("Authorization", "Token $key")
                setRequestProperty("User-Agent", "WidScore/1.0")
            }
            val code = conn.responseCode
            if (code !in 200..299) return@withContext null to code
            conn.inputStream.bufferedReader().readText() to code
        } catch (_: Exception) { null to -1 } finally { conn?.disconnect() }
    }

    suspend fun ping(key: String): String {
        if (key.isBlank()) return "empty key"
        val (body, code) = get("/coverage/?sport=football", key)
        if (body == null) return "HTTP $code"
        return try {
            val sp = JSONObject(body).optJSONArray("sports")?.optJSONObject(0)
            "ok (${sp?.optString("status")}, ${sp?.optInt("events_next_7d")} next 7d)"
        } catch (_: Exception) { "bad payload" }
    }

    // Nom de ligue (cache 30j, fallback League {id}).
    suspend fun leagueName(ctx: android.content.Context, key: String, id: Int): String {
        try {
            val f = File(ctx.filesDir, LEAGUES_FILE)
            if (f.exists()) {
                val root = JSONObject(f.readText())
                val at = root.optLong("fetchedAt", 0)
                if (at > 0 && (System.currentTimeMillis() - at) < CACHE_DAYS * 24 * 3600_000L) {
                    val n = root.optJSONObject("leagues")?.optString(id.toString())
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {}
        // Refresh cache complet (1 appel, 88 ligues).
        try {
            val (body, _) = get("/leagues/?limit=200", key)
            if (body != null) {
                val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
                val obj = JSONObject()
                for (i in 0 until arr.length()) {
                    val l = arr.optJSONObject(i) ?: continue
                    obj.put(l.optInt("id").toString(), l.optString("name"))
                }
                File(ctx.filesDir, LEAGUES_FILE).writeText(
                    JSONObject().put("fetchedAt", System.currentTimeMillis())
                        .put("leagues", obj).toString()
                )
                val n = obj.optString(id.toString())
                if (n.isNotBlank()) return n
            }
        } catch (_: Exception) {}
        return "League $id"
    }

    data class BzTeam(val id: Int, val name: String)

    // Directory équipes par ligue bzzoiro (cache 30j) pour résoudre les favoris.
    suspend fun leagueTeams(ctx: android.content.Context, key: String, leagueId: Int): List<BzTeam> {
        try {
            val f = File(ctx.filesDir, TEAMS_FILE)
            if (f.exists()) {
                val root = JSONObject(f.readText())
                val node = root.optJSONObject(leagueId.toString())
                if (node != null) {
                    val at = node.optLong("fetchedAt", 0)
                    if (at > 0 && (System.currentTimeMillis() - at) < CACHE_DAYS * 24 * 3600_000L) {
                        val arr = node.optJSONArray("teams") ?: JSONArray()
                        val list = mutableListOf<BzTeam>()
                        for (i in 0 until arr.length()) {
                            val t = arr.optJSONObject(i) ?: continue
                            list.add(BzTeam(t.optInt("id"), t.optString("name")))
                        }
                        if (list.isNotEmpty()) return list
                    }
                }
            }
        } catch (_: Exception) {}
        val list = mutableListOf<BzTeam>()
        try {
            var offset = 0
            while (offset < 2000) {
                val (body, _) = get("/teams/?league_id=$leagueId&limit=200&offset=$offset", key) ?: break
                val arr = JSONObject(body).optJSONArray("results") ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    list.add(BzTeam(t.optInt("id"), t.optString("name")))
                }
                if (arr.length() < 200) break
                offset += 200
            }
        } catch (_: Exception) {}
        if (list.isNotEmpty()) {
            try {
                val f = File(ctx.filesDir, TEAMS_FILE)
                val root = try {
                    if (f.exists()) JSONObject(f.readText()) else JSONObject()
                } catch (_: Exception) { JSONObject() }
                root.put(
                    leagueId.toString(), JSONObject()
                        .put("fetchedAt", System.currentTimeMillis())
                        .put("teams", JSONArray(list.map {
                            JSONObject().put("id", it.id).put("name", it.name)
                        }))
                )
                f.writeText(root.toString())
            } catch (_: Exception) {}
        }
        return list
    }

    // Résout un favori ESPN vers bzzoiro (toutes ligues mappées, nom normalisé).
    suspend fun resolveTeam(ctx: android.content.Context, key: String, fav: FavTeam): BzTeam? {
        val fn = normName(fav.name)
        if (fn.isBlank()) return null
        for (leagueId in leagueMap.keys) {
            val teams = try { leagueTeams(ctx, key, leagueId) } catch (_: Exception) { emptyList() }
            for (t in teams) {
                val n = normName(t.name)
                if (n.isNotBlank() && (n == fn || n.contains(fn) || fn.contains(n))) return t
            }
        }
        return null
    }

    // Events d'une équipe sur fenêtre [jours passés, jours à venir].
    suspend fun getTeamEvents(
        key: String, teamId: Int, daysBack: Int = 4, daysAhead: Int = 35
    ): List<JSONObject>? {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_YEAR, -daysBack)
        val from = sdf.format(cal.time)
        cal.timeInMillis = System.currentTimeMillis()
        cal.add(Calendar.DAY_OF_YEAR, daysAhead)
        val to = sdf.format(cal.time)
        val out = mutableListOf<JSONObject>()
        try {
            var offset = 0
            while (offset < 2000) {
                val (body, code) = get(
                    "/events/?team_id=$teamId&date_from=$from&date_to=$to&limit=200&offset=$offset", key
                )
                if (body == null) {
                    LogStore.log("BZ", "team $teamId FAIL http=$code")
                    return null
                }
                val arr = JSONObject(body).optJSONArray("results") ?: break
                for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
                if (arr.length() < 200) break
                offset += 200
            }
        } catch (e: Exception) {
            LogStore.log("BZ", "team $teamId FAIL ${e.message}")
            return null
        }
        return out
    }

    fun convert(ev: JSONObject, fav: FavTeam, leagueName: String): EspnMatch? {
        return try {
            val id = ev.optLong("id", 0)
            if (id == 0L) return null
            val status = ev.optString("status").lowercase()
            if (status.contains("cancel")) return null
            val state = when {
                status.contains("live") || status.contains("progress") ||
                    status.contains("half") || status.contains("1st") || status.contains("2nd") -> "in"
                status == "finished" -> "post"
                else -> "pre" // notstarted, postponed, suspended…
            }
            val utc = try {
                java.time.OffsetDateTime.parse(ev.optString("event_date")).toInstant().toEpochMilli()
            } catch (_: Exception) { 0L }
            if (utc <= 0) return null
            val homeName = ev.optString("home_team")
            val awayName = ev.optString("away_team")
            if (homeName.isBlank() || awayName.isBlank()) return null
            val leagueId = ev.optInt("league_id", 0)
            val home = EspnTeam(name = homeName, abbr = triCode(homeName))
            val away = EspnTeam(name = awayName, abbr = triCode(awayName))
            // Lie le favori par nom normalisé (ids bzzoiro != ids ESPN).
            linkByName(home, away, fav)
            val hs = if (ev.isNull("home_score")) null else ev.optInt("home_score")
            val aws = if (ev.isNull("away_score")) null else ev.optInt("away_score")
            val clock = ev.opt("current_minute")?.toString().orEmpty()
            EspnMatch(
                id = "bz-$id", leagueSlug = espnSlug(leagueId), leagueName = leagueName,
                utcMillis = utc, state = state, clock = clock,
                home = home, away = away, homeScore = hs, awayScore = aws
            )
        } catch (_: Exception) { null }
    }

    private fun linkByName(home: EspnTeam, away: EspnTeam, fav: FavTeam) {
        val fn = normName(fav.name)
        if (fn.isBlank()) return
        val hn = normName(home.name)
        val an = normName(away.name)
        if (hn == fn || (fn.length > 3 && hn.contains(fn))) {
            home.id = fav.id; home.name = fav.name
        } else if (an == fn || (fn.length > 3 && an.contains(fn))) {
            away.id = fav.id; away.name = fav.name
        }
    }

    private fun triCode(name: String): String {
        return name.filter { it.isLetterOrDigit() }.take(3).uppercase().ifBlank { "?" }
    }

    fun normName(s: String): String {
        val stop = setOf("fc", "cf", "sc", "jk", "fk", "sk", "as", "ac", "ssc", "spor", "club", "de", "la", "le", "les", "real")
        return s.lowercase()
            .map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("")
            .split(" ").filter { it.isNotEmpty() && !stop.contains(it) }.joinToString(" ")
    }

    fun record(label: String, ok: Boolean, count: Int, note: String = "") {
        lastStatuses = (lastStatuses.filter { it.label != label } + BzStatus(label, ok, count, note)).takeLast(20)
    }
}
