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
    private const val LEAGUES_FILE = "football_leagues.json"
    private const val CACHE_DAYS = 30L
    // Warm du reste du monde : borné par session (comme Palisades mais adapté mobile).
    private const val REST_PER_RUN = 25
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

    fun clearCache(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
        try { File(ctx.filesDir, TEAM_LEAGUES_FILE).delete() } catch (_: Exception) {}
        done = 0; total = 0
    }

    // Liste complète des slugs ESPN (copie GetAllLeagueSlugsAsync Palisades),
    // cache 30j. Sert le mapping équipe->ligues au-delà des curated.
    suspend fun getAllSlugs(ctx: Context): List<String> = withContext(Dispatchers.IO) {
        try {
            val f = File(ctx.filesDir, LEAGUES_FILE)
            if (f.exists()) {
                val cached = JSONObject(f.readText())
                val at = cached.optLong("fetchedAt", 0)
                if (at > 0 && (System.currentTimeMillis() - at) < CACHE_DAYS * 24 * 3600_000L) {
                    val arr = cached.optJSONArray("slugs") ?: JSONArray()
                    val list = MutableList(arr.length()) { i -> arr.optString(i) }
                        .filter { it.isNotBlank() }
                    if (list.isNotEmpty()) return@withContext list
                }
            }
        } catch (_: Exception) {}
        val slugs = mutableListOf<String>()
        try {
            var next: String? =
                "https://sports.core.api.espn.com/v2/sports/soccer/leagues?limit=200&lang=en&region=us"
            var pages = 0
            while (next != null && pages < 10) {
                pages++
                val body = EspnApi.getRaw(next) ?: break
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val ref = items.optJSONObject(i)?.optString("\$ref") ?: ""
                    val seg = ref.substringAfterLast("/").substringBefore("?")
                    if (seg.isNotBlank()) slugs.add(seg)
                }
                next = null
                if (json.optInt("pageCount", 1) > json.optInt("pageIndex", 1)) {
                    next = "https://sports.core.api.espn.com/v2/sports/soccer/leagues" +
                        "?limit=200&lang=en&region=us&page=" + (json.optInt("pageIndex", 1) + 1)
                }
            }
        } catch (_: Exception) {}
        val distinct = slugs.distinct()
        if (distinct.isNotEmpty()) {
            try {
                File(ctx.filesDir, LEAGUES_FILE).writeText(
                    JSONObject().put("fetchedAt", System.currentTimeMillis())
                        .put("slugs", JSONArray(distinct)).toString()
                )
            } catch (_: Exception) {}
        }
        distinct
    }

    // Découverte des compétitions d'une équipe suivie : sonde team-events
    // sur toutes les ligues ESPN connues (1 appel/ligue, existe = joue dedans).
    // Cache 30j. Garantit TOUS les matchs d'une équipe sans cocher aucune ligue.
    private const val TEAM_LEAGUES_FILE = "football_team_leagues.json"

    fun loadTeamLeagues(ctx: Context): Map<String, List<String>> {
        return try {
            val f = File(ctx.filesDir, TEAM_LEAGUES_FILE)
            if (!f.exists()) return emptyMap()
            val root = JSONObject(f.readText())
            val out = mutableMapOf<String, List<String>>()
            for (k in root.keys()) {
                val node = root.optJSONObject(k) ?: continue
                val at = node.optLong("fetchedAt", 0)
                if (at <= 0 || (System.currentTimeMillis() - at) >= CACHE_DAYS * 24 * 3600_000L) continue
                val arr = node.optJSONArray("leagues") ?: JSONArray()
                out[k] = MutableList(arr.length()) { i -> arr.optString(i) }.filter { it.isNotBlank() }
            }
            out
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveTeamLeagues(ctx: Context, teamId: String, leagues: List<String>) {
        try {
            val f = File(ctx.filesDir, TEAM_LEAGUES_FILE)
            val root = try {
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            } catch (_: Exception) { JSONObject() }
            root.put(
                teamId, JSONObject()
                    .put("fetchedAt", System.currentTimeMillis())
                    .put("leagues", JSONArray(leagues))
            )
            f.writeText(root.toString())
        } catch (_: Exception) {}
    }

    private fun seasonYear(): Int {
        val cal = java.util.Calendar.getInstance()
        val y = cal.get(java.util.Calendar.YEAR)
        return if (cal.get(java.util.Calendar.MONTH) >= java.util.Calendar.JULY) y else y - 1
    }

    // Sonde priorisée : ligues connues d'abord (rapide), puis reste.
    suspend fun discoverTeamLeagues(
        ctx: Context, teamId: String, knownFirst: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): List<String> = withContext(Dispatchers.IO) {
        try {
            val cached = loadTeamLeagues(ctx)[teamId]
            if (cached != null) return@withContext cached
            val all = try { getAllSlugs(ctx) } catch (_: Exception) { emptyList<String>() }
            val ordered = (knownFirst.map { it.lowercase() } +
                all.map { it.lowercase() }.filter { !knownFirst.contains(it) }).distinct()
            val found = mutableListOf<String>()
            var done = 0
            for (slug in ordered) {
                try {
                    val url = "https://sports.core.api.espn.com/v2/sports/soccer/leagues/" +
                        android.net.Uri.encode(slug) + "/seasons/" + seasonYear() +
                        "/teams/" + android.net.Uri.encode(teamId) + "/events?lang=en&region=us&limit=1"
                    val body = EspnApi.getRaw(url)
                    if (body != null) {
                        val items = JSONObject(body).optJSONArray("items")
                        if (items != null && items.length() > 0) found.add(slug)
                    }
                } catch (_: Exception) {}
                done++
                onProgress(done, ordered.size)
            }
            saveTeamLeagues(ctx, teamId, found)
            found
        } catch (_: Exception) { emptyList() }
    }
    // Copie EnsureWorldRostersAsync (priorités + enum complète).
    suspend fun warm(
        ctx: Context,
        leagues: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        if (running) return@withContext
        running = true
        try {
            val root = readDisk(ctx)
            val prio = leagues.map { it.trim().lowercase() }
                .filter { it.isNotBlank() }.distinct()
            val curated = CuratedLeagues.all.map { it.slug.lowercase() }
            val rest = try {
                getAllSlugs(ctx).map { it.lowercase() }
                    .filter { it.isNotBlank() && !prio.contains(it) && !curated.contains(it) }
            } catch (_: Exception) { emptyList() }
            val ordered = (prio + curated.filter { !prio.contains(it) } +
                rest.filter { !prio.contains(it) && !curated.contains(it) }.take(REST_PER_RUN))
                .filter { !isFresh(root, it) }
            if (ordered.isEmpty()) {
                total = 0; done = 0
                onProgress(0, 0)
                return@withContext
            }
            total = ordered.size; done = 0
            onProgress(0, total)
            // Petits lots séquentiels (réseau mobile) : 4 ligues en parallèle.
            for (chunk in ordered.chunked(4)) {
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

    // Recherche tolérante : accents/casse/espaces/ponctuation ignorés,
    // chaque mot de la requête doit apparaître (contenu) dans le nom.
    // Dedup par id comme GetKnownTeams Palisades (1 ligne par équipe).
    fun search(teams: List<RosterTeam>, query: String): List<RosterTeam> {
        val deduped = dedup(teams)
        val q = norm(query)
        if (q.isBlank()) return deduped.sortedBy { it.name }.take(60)
        val words = q.split(" ").filter { it.length > 1 }
        return deduped.filter { t ->
            val n = norm(t.name)
            val flat = n.replace(" ", "")
            val qflat = q.replace(" ", "")
            flat.contains(qflat) || t.abbr.lowercase() == qflat ||
                (words.isNotEmpty() && words.all { w -> n.contains(w) }) ||
                matchesAlias(t.name, q)
        }.sortedBy { it.name }.take(60)
    }

    // Une ligne par équipe (première ligue rencontrée = ligue principale).
    fun dedup(teams: List<RosterTeam>): List<RosterTeam> {
        val seen = HashSet<String>()
        return teams.filter { seen.add(it.id) }
    }

    // Suffixe Women comme Palisades (équipes femmes homonymes).
    fun displayName(t: RosterTeam): String {
        return if (isWomenLeague(t.leagueSlug)) t.name + " - Women" else t.name
    }

    fun isWomenLeague(slug: String): Boolean {
        if (slug.isBlank()) return false
        val s = slug.lowercase()
        if (s.contains(".w.") || s.endsWith(".w")) return true
        if (s.contains("nwsl") || s.contains("shebelieves") || s.contains("femenina") ||
            s.contains("womens") || s.contains("ww")
        ) return true
        if (s.startsWith("fifa.w.") || s.startsWith("uefa.w") || s.startsWith("concacaf.w")) return true
        if (s.contains("weuro") || s.contains("wchampions") || s.contains("w.nations")) return true
        return false
    }

    // "Türkiye" -> "turkiye", "St. Pauli" -> "st pauli".
    fun norm(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
        val stripped = decomposed.filter { it.category != CharCategory.NON_SPACING_MARK }
        return Normalizer.normalize(stripped, Normalizer.Form.NFC)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("").split(" ").filter { it.isNotEmpty() }.joinToString(" ")
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
