package com.widscore.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

// football-data.org v4 (clé user, 10 req/min) : 2e source, surtout upcoming.
// Authorization via header X-Auth-Token. Compétitions gratuites mappées vers slugs ESPN.
object FDOrgApi {
    private const val BASE = "https://api.football-data.org/v4"
    private var lastCall = 0L
    private const val GAP_MS = 6100L

    // ESPN slug -> FD code (free tier).
    private val slugToCode = mapOf(
        "eng.1" to "PL", "eng.2" to "ELC",
        "esp.1" to "PD", "ita.1" to "SA",
        "ger.1" to "BL1", "fra.1" to "FL1",
        "ned.1" to "DED", "por.1" to "PPL",
        "bra.1" to "BSA",
        "uefa.champions" to "CL", "uefa.euro" to "EC",
        "fifa.world" to "WC"
    )

    fun codeFor(espnSlug: String): String? = slugToCode[espnSlug.trim().lowercase()]
    fun slugFor(code: String): String? = slugToCode.entries.firstOrNull { it.value == code }?.key

    data class FdStatus(val code: String, val ok: Boolean, val count: Int, val note: String = "")
    @Volatile var lastStatuses: List<FdStatus> = emptyList()

    private suspend fun get(path: String, key: String): Pair<String?, Int> = withContext(Dispatchers.IO) {
        synchronized(this@FDOrgApi) {
            val wait = GAP_MS - (System.currentTimeMillis() - lastCall)
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 20000
                setRequestProperty("X-Auth-Token", key)
                setRequestProperty("User-Agent", "WidScore/1.0")
            }
            val code = conn.responseCode
            if (code !in 200..299) return@withContext null to code
            conn.inputStream.bufferedReader().readText() to code
        } catch (_: Exception) { null to -1 } finally { conn?.disconnect() }
    }

    // Test clé : 1 appel léger. Retourne "ok" ou message d'erreur.
    suspend fun ping(key: String): String {
        if (key.isBlank()) return "empty key"
        val (body, code) = get("/competitions", key)
        if (body == null) return "HTTP $code"
        return try {
            val n = JSONObject(body).optJSONArray("competitions")?.length() ?: 0
            if (n > 0) "ok ($n competitions)" else "empty"
        } catch (_: Exception) { "bad payload" }
    }

    // Matchs d'une compétition, convertis + liés aux favoris par nom normalisé.
    suspend fun getCompetitionMatches(
        key: String, fdCode: String, espnSlug: String, favTeams: List<FavTeam>
    ): List<EspnMatch>? {
        val (body, code) = get("/competitions/$fdCode/matches", key)
        if (body == null) {
            record(fdCode, false, 0, "HTTP $code" + if (code == 403) " (bad key?)" else "")
            LogStore.log("FD", "$fdCode FAIL http=$code")
            return null
        }
        return try {
            val arr = JSONObject(body).optJSONArray("matches") ?: JSONArray()
            val out = mutableListOf<EspnMatch>()
            for (i in 0 until arr.length()) {
                parseMatch(arr.getJSONObject(i), fdCode, espnSlug, favTeams)?.let { out.add(it) }
            }
            record(fdCode, true, out.size, "")
            LogStore.log("FD", "$fdCode ok count=${out.size}")
            out
        } catch (e: Exception) {
            record(fdCode, false, 0, "parse")
            LogStore.log("FD", "$fdCode PARSE FAIL ${e.message}")
            null
        }
    }

    private fun record(code: String, ok: Boolean, count: Int, note: String) {
        lastStatuses = (lastStatuses.filter { it.code != code } + FdStatus(code, ok, count, note)).takeLast(20)
    }

    private fun parseMatch(o: JSONObject, fdCode: String, espnSlug: String, favs: List<FavTeam>): EspnMatch? {
        return try {
            val id = o.optLong("id", 0)
            if (id == 0L) return null
            val status = o.optString("status")
            val state = when (status) {
                "IN_PLAY", "PAUSED" -> "in"
                "FINISHED", "AWARDED" -> "post"
                else -> "pre" // SCHEDULED, TIMED, POSTPONED, SUSPENDED, CANCELED
            }
            val utc = try {
                OffsetDateTime.parse(o.optString("utcDate")).toInstant().toEpochMilli()
            } catch (_: Exception) { 0L }
            if (utc <= 0) return null
            val home = parseSide(o.optJSONObject("homeTeam"))
            val away = parseSide(o.optJSONObject("awayTeam"))
            if (home.name.isBlank() || away.name.isBlank()) return null
            // Lie les favoris par nom normalisé (ids FD != ids ESPN).
            linkFav(home, favs); linkFav(away, favs)
            val score = o.optJSONObject("score")?.optJSONObject("fullTime")
            val hs = if (score == null || score.isNull("home")) null else score.optInt("home")
            val aws = if (score == null || score.isNull("away")) null else score.optInt("away")
            EspnMatch(
                id = "fd-$id", leagueSlug = espnSlug,
                leagueName = CuratedLeagues.nameOf(espnSlug),
                utcMillis = utc, state = state,
                home = home, away = away, homeScore = hs, awayScore = aws
            )
        } catch (_: Exception) { null }
    }

    private fun parseSide(t: JSONObject?): EspnTeam {
        if (t == null) return EspnTeam()
        return EspnTeam(
            id = "fd-" + t.optLong("id", 0),
            name = t.optString("name"),
            abbr = t.optString("tla").uppercase().ifBlank { t.optString("shortName").uppercase() },
            logo = t.optString("crest")
        )
    }

    private fun linkFav(side: EspnTeam, favs: List<FavTeam>) {
        val n = norm(side.name)
        if (n.isBlank()) return
        for (f in favs) {
            if (f.kind == "league") continue
            val fn = norm(f.name)
            if (fn.isNotBlank() && (n == fn || n.contains(fn) || fn.contains(n))) {
                side.id = f.id
                side.name = f.name
                return
            }
        }
    }

    private fun norm(s: String): String {
        return s.lowercase()
            .replace("fc", "").replace("cf", "").replace("sc", "")
            .filter { it.isLetterOrDigit() || it == ' ' }
            .split(" ").filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun clearStatuses() {
        lastStatuses = emptyList()
    }
}
