package com.widscore.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Fixtures saison par équipe suivie (core.api team events), cache 24h.
// Garantit les matchs À VENIR même hors fenêtre CDN et hors schedules.
object TeamEventsStore {
    private const val FILE = "football_team_events.json"
    private const val CACHE_HOURS = 24L

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun key(league: String, teamId: String) = league.lowercase() + "/" + teamId

    // Matchs à venir en cache (toutes équipes).
    fun loadUpcoming(ctx: Context): List<EspnMatch> {
        val out = mutableListOf<EspnMatch>()
        try {
            val f = file(ctx)
            if (!f.exists()) return out
            val root = JSONObject(f.readText())
            val now = System.currentTimeMillis()
            for (k in root.keys()) {
                val node = root.optJSONObject(k) ?: continue
                val arr = node.optJSONArray("matches") ?: continue
                for (i in 0 until arr.length()) {
                    val m = EspnMatch.fromJson(arr.optJSONObject(i) ?: continue) ?: continue
                    if (m.utcMillis > now - 6 * 3600_000L) out.add(m)
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun isFresh(node: JSONObject): Boolean {
        val at = node.optLong("fetchedAt", 0)
        return at > 0 && (System.currentTimeMillis() - at) < CACHE_HOURS * 3600_000L
    }

    // Refresh des ligues/équipes périmées. Retourne matchs à venir mergés.
    suspend fun refresh(
        ctx: Context, teams: List<Triple<String, String, String>> // league, teamId, teamName
    ): List<EspnMatch> = withContext(Dispatchers.IO) {
        val out = mutableListOf<EspnMatch>()
        try {
            val root = try {
                val f = file(ctx)
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            } catch (_: Exception) { JSONObject() }
            var dirty = false
            val now = System.currentTimeMillis()
            for ((league, teamId, teamName) in teams) {
                val k = key(league, teamId)
                val node = root.optJSONObject(k)
                if (node != null && isFresh(node)) {
                    val arr = node.optJSONArray("matches") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val m = EspnMatch.fromJson(arr.optJSONObject(i) ?: continue) ?: continue
                        if (m.utcMillis > now - 6 * 3600_000L) out.add(m)
                    }
                    continue
                }
                // Fetch fixtures saison (1 + N headers, 1x/24h par équipe/ligue).
                val fixtures = EspnApi.getTeamFixtures(league, teamId)
                if (fixtures == null) {
                    if (node != null) {
                        val arr = node.optJSONArray("matches") ?: JSONArray()
                        for (i in 0 until arr.length()) {
                            val m = EspnMatch.fromJson(arr.optJSONObject(i) ?: continue) ?: continue
                            if (m.utcMillis > now - 6 * 3600_000L) out.add(m)
                        }
                    }
                    continue
                }
                val matches = fixtures
                    .filter { it.utcMillis > now - 6 * 3600_000L }
                    .map { fx -> toMatch(fx, league, teamId, teamName) }
                root.put(
                    k, JSONObject()
                        .put("fetchedAt", now)
                        .put("matches", JSONArray(matches.map { it.toJson() }))
                )
                dirty = true
                out += matches
            }
            if (dirty) try { file(ctx).writeText(root.toString()) } catch (_: Exception) {}
        } catch (_: Exception) {}
        out
    }

    private fun toMatch(fx: EspnApi.TeamFixture, league: String, teamId: String, teamName: String): EspnMatch {
        val tnorm = teamName.trim().lowercase()
        val homeIsFollowed = fx.homeName.lowercase().contains(tnorm) || tnorm.contains(fx.homeName.lowercase())
        val home = EspnTeam(
            id = if (homeIsFollowed) teamId else "",
            name = fx.homeName, abbr = triAbbr(fx.homeName)
        )
        val away = EspnTeam(
            id = if (!homeIsFollowed) teamId else "",
            name = fx.awayName, abbr = triAbbr(fx.awayName)
        )
        return EspnMatch(
            id = fx.eventId, leagueSlug = league,
            leagueName = CuratedLeagues.nameOf(league),
            utcMillis = fx.utcMillis, state = "pre",
            home = home, away = away
        )
    }

    private fun triAbbr(name: String): String {
        val letters = name.filter { it.isLetterOrDigit() }.take(3).uppercase()
        return letters.ifBlank { "?" }
    }
}
