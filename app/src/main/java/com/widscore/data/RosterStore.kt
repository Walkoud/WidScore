package com.widscore.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

// Directory monde des équipes, copie EnsureWorldRosters de Palisades :
// leagues/{slug} -> teams.$ref -> collection (limit=100, pages) -> team details.
// Cache disque 30j, même forme JSON que Palisades (football_rosters.json).
object RosterStore {

    data class RosterTeam(
        val id: String = "",
        val name: String = "",
        val abbr: String = "",
        val logo: String = "",
        val leagueSlug: String = ""
    ) {
        fun toFav() = FavTeam(id, name, "team", leagueSlug)
    }

    private const val FILE = "football_rosters.json"
    private const val CACHE_DAYS = 30L
    @Volatile var done = 0
    @Volatile var total = 0
    @Volatile var running = false

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun readDisk(ctx: Context): JSONObject {
        return try {
            val f = file(ctx)
            if (f.exists()) JSONObject(f.readText()) else JSONObject()
        } catch (_: Exception) { JSONObject() }
    }

    private fun writeDisk(ctx: Context, root: JSONObject) {
        try { file(ctx).writeText(root.toString()) } catch (_: Exception) {}
    }

    fun isFresh(o: JSONObject, slug: String): Boolean {
        return try {
            val node = o.optJSONObject(slug) ?: return false
            val at = node.optLong("fetchedAt", 0)
            if (at == 0L) return false
            (System.currentTimeMillis() - at) < CACHE_DAYS * 24 * 3600_000L &&
                (node.optJSONArray("teams")?.length() ?: 0) > 0
        } catch (_: Exception) { false }
    }

    fun loadCached(ctx: Context): List<RosterTeam> {
        val out = mutableListOf<RosterTeam>()
        try {
            val root = readDisk(ctx)
            for (slug in root.keys()) {
                if (slug == "v") continue
                val node = root.optJSONObject(slug) ?: continue
                val arr = node.optJSONArray("teams") ?: continue
                for (i in 0 until arr.length()) {
                    val t = arr.optJSONObject(i) ?: continue
                    val id = t.optString("id")
                    if (id.isBlank()) continue
                    out.add(
                        RosterTeam(
                            id, t.optString("name"), t.optString("abbr"),
                            t.optString("logo"), slug
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }

    // Warm : ligues abonnées d'abord, puis le reste des curated. Copie EnsureWorldRostersAsync.
    suspend fun warm(
        ctx: Context,
        leagues: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        if (running) return@withContext
        running = true
        try {
            val root = readDisk(ctx)
            val missing = leagues.map { it.trim().lowercase() }
                .filter { it.isNotBlank() }.distinct()
                .filter { !isFresh(root, it) }
            if (missing.isEmpty()) {
                total = 0; done = 0
                onProgress(0, 0)
                return@withContext
            }
            total = missing.size; done = 0
            onProgress(0, total)
            // Petits lots séquentiels (réseau mobile) : 4 ligues en parallèle.
            for (chunk in missing.chunked(4)) {
                coroutineScope {
                    chunk.map { slug ->
                        async {
                            val teams = fetchOneRoster(slug)
                            if (teams.isNotEmpty()) {
                                synchronized(root) {
                                    root.put(
                                        slug, JSONObject()
                                            .put("fetchedAt", System.currentTimeMillis())
                                            .put("teams", JSONArray(teams.map {
                                                JSONObject().put("id", it.id).put("name", it.name)
                                                    .put("abbr", it.abbr).put("logo", it.logo)
                                            }))
                                    )
                                }
                            }
                            synchronized(root) { done++ }
                            onProgress(done, total)
                        }
                    }.awaitAll()
                }
                writeDisk(ctx, root)
            }
            writeDisk(ctx, root)
        } catch (_: Exception) {
        } finally {
            running = false
        }
    }

    private suspend fun fetchOneRoster(slug: String): List<RosterTeam> {
        return try {
            val leagueUrl = "https://sports.core.api.espn.com/v2/sports/soccer/leagues/" +
                android.net.Uri.encode(slug) + "?lang=en&region=us"
            val leagueBody = EspnApi.getRaw(leagueUrl) ?: return emptyList()
            val teamsRef = JSONObject(leagueBody).optJSONObject("teams")?.optString("\$ref")
                ?: return emptyList()
            val refs = getRefList(fixRef(teamsRef))
            coroutineScope {
                refs.map { ref ->
                    async {
                        try {
                            val body = EspnApi.getRaw(fixRef(ref)) ?: return@async null
                            val json = JSONObject(body)
                            val id = json.optString("id")
                            val name = json.optString("displayName", json.optString("name"))
                            if (id.isBlank() || name.isBlank()) return@async null
                            var logo = ""
                            val logos = json.optJSONArray("logos")
                            if (logos != null) for (i in 0 until logos.length()) {
                                val href = logos.optJSONObject(i)?.optString("href") ?: ""
                                if (href.startsWith("https://")) { logo = href; break }
                            }
                            RosterTeam(
                                id, name,
                                json.optString("abbreviation").uppercase(),
                                logo, slug
                            )
                        } catch (_: Exception) { null }
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun getRefList(collectionUrl: String): List<String> {
        val refs = mutableListOf<String>()
        return try {
            val sep = if (collectionUrl.contains("?")) "&" else "?"
            var next: String? = collectionUrl + sep + "limit=100"
            var pages = 0
            while (next != null && pages < 10 && refs.size < 500) {
                pages++
                val body = EspnApi.getRaw(next) ?: break
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val r = items.optJSONObject(i)?.optString("\$ref") ?: ""
                    if (r.isNotBlank()) refs.add(r)
                }
                next = null
                if ((json.optInt("pageCount", 1)) > (json.optInt("pageIndex", 1))) {
                    next = collectionUrl + sep + "limit=100&page=" + (json.optInt("pageIndex", 1) + 1)
                }
            }
            refs
        } catch (_: Exception) { refs }
    }

    private fun fixRef(url: String): String {
        return if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url
    }

    // Recherche : minuscules + accents stripés + alias FR (copie FootballTeamSearchWindow).
    fun search(teams: List<RosterTeam>, query: String): List<RosterTeam> {
        val q = norm(query)
        if (q.isBlank()) return teams.sortedBy { it.name }.take(60)
        return teams.filter {
            norm(it.name).contains(q) || it.abbr.lowercase().contains(q) || matchesAlias(it.name, q)
        }.sortedBy { it.name }.take(60)
    }

    fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val lower = s.trim().lowercase().let {
            Normalizer.normalize(it, Normalizer.Form.NFD)
        }
        return Normalizer.normalize(lower, Normalizer.Form.NFC)
            .filter { it.category != CharCategory.NON_SPACING_MARK }
    }

    private val frAliases = mapOf(
        "angleterre" to "england", "espagne" to "spain", "allemagne" to "germany",
        "italie" to "italy", "pays-bas" to "netherlands", "pays bas" to "netherlands",
        "belgique" to "belgium", "suisse" to "switzerland", "pologne" to "poland",
        "turquie" to "turkiye", "grece" to "greece", "suede" to "sweden",
        "norvege" to "norway", "danemark" to "denmark", "ecosse" to "scotland"
    )

    private fun matchesAlias(espnName: String, q: String): Boolean {
        if (q.isEmpty() || espnName.isEmpty()) return false
        val name = norm(espnName)
        for ((fr, en) in frAliases) {
            if ((q.contains(fr) || q.contains(en)) && name.contains(en)) return true
        }
        return false
    }
}
