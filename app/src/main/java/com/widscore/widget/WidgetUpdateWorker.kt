package com.widscore.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.widscore.R
import com.widscore.data.EspnApi
import com.widscore.data.Prefs
import java.util.concurrent.TimeUnit

// Fetch ESPN -> cache prefs -> headers + notifyDataChanged (listes scrollables).
// Périodique 15 min (mini Android) + one-shot à chaque onUpdate / bouton ⟳ / prefs.
class WidgetUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        com.widscore.data.LogStore.init(ctx)
        val s = Prefs.load(ctx)
        val L = com.widscore.data.LogStore
        return try {
            L.log("SYNC", "start leagues=${s.leagues} favs=${s.teams.map { it.kind + ":" + it.name }}")
            // Copie RefreshAsync Palisades : une équipe suivie amène TOUTES ses ligues
            // (directory monde : ex. Besiktas -> tur.1 + uefa.europa), pas juste sa ligue
            // principale. Sans ça, les matchs européens sont invisibles.
            val dirByTeam = com.widscore.data.RosterStore.loadCached(ctx)
                .groupBy({ it.id }, { it.leagueSlug.lowercase() })
            val discovered = com.widscore.data.RosterStore.loadTeamLeagues(ctx)
            val favTeams = s.teams.filter { it.kind == "team" }
            val teamLeagueMap = favTeams.associate { t ->
                t.id to ((dirByTeam[t.id] ?: emptyList()) + listOf(t.leagueSlug.lowercase()) +
                    (discovered[t.id] ?: emptyList()))
                    .map { it.trim() }.filter { it.isNotBlank() }.distinct()
            }
            val fetch = (s.leagues + teamLeagueMap.values.flatten()).map { it.trim() }
                .filter { it.isNotBlank() }.distinct()
            val all = mutableListOf<com.widscore.data.EspnMatch>()
            if (!s.useEspn) {
                L.log("ESPN", "skipped (disabled)")
            } else {
                for (lg in fetch) {
                    val before = all.size
                    all += EspnApi.getMatches(lg)
                    L.log("ESPN", "$lg +${all.size - before}")
                    kotlinx.coroutines.delay(150)
                }
                L.log("ESPN", "scoreboards total=${all.size}")
                // Backfill schedules par équipe ET par ligue (copie Palisades).
                for (t in favTeams) {
                    for (lg in teamLeagueMap[t.id].orEmpty()) {
                        val before = all.size
                        all += EspnApi.getTeamSchedule(lg, t.id, t.name)
                        L.log("SCHED", "${t.name}/$lg +${all.size - before}")
                    }
                }
                // Fixtures saison (core.api team events, cache 24h) : matchs à venir
                // garantis même hors fenêtre CDN et hors schedules.
                try {
                    val triples = favTeams.flatMap { t ->
                        teamLeagueMap[t.id].orEmpty().map { lg -> Triple(lg, t.id, t.name) }
                    }
                    val fx = com.widscore.data.TeamEventsStore.refresh(ctx, triples)
                    all += fx
                    L.log("FIX", "season fixtures +${fx.size}")
                } catch (_: Exception) {}
            }
            // Sources lentes (FD/BZ) : skip si cache frais (<90s) pour éviter
            // les runs interminables ; ESPN tourne toujours. Headers restaurés
            // dans tous les cas en fin de run (état pressé du bouton ⟳).
            val cacheAt = Prefs.loadCache(ctx).first
            val tailsFresh = cacheAt > 0 && (System.currentTimeMillis() - cacheAt) < 90_000L
            if (tailsFresh) L.log("SYNC", "slow tails skipped (cache fresh)")
            // sports.bzzoiro.com (clé user) : 3e source, passé+à venir par équipe.
            if (!tailsFresh && s.useBzApi && s.bzApiKey.isNotBlank()) {
                try {
                    val bz = com.widscore.data.BzApi
                    var okTeams = 0
                    for (t in favTeams) {
                        val bzTeam = bz.resolveTeam(ctx, s.bzApiKey, t)
                        if (bzTeam == null) {
                            bz.record(t.name, false, 0, "not found")
                            L.log("BZ", "${t.name} team not found")
                            continue
                        }
                        val evs = bz.getTeamEvents(s.bzApiKey, bzTeam.id, t.name)
                        if (evs == null) {
                            bz.record(t.name, false, 0, "fetch fail")
                            continue
                        }
                        var added = 0
                        for (ev in evs) {
                            val lid = ev.optInt("league_id", 0)
                            val lname = try { bz.leagueName(ctx, s.bzApiKey, lid) } catch (_: Exception) { "League $lid" }
                            bz.convert(ev, t, lname)?.let { all.add(it); added++ }
                        }
                        bz.record(t.name, true, added, "")
                        L.log("BZ", "${t.name} ok count=$added")
                        okTeams++
                    }
                    L.log("BZ", "teams ok=$okTeams/${favTeams.size}")
                } catch (e: Exception) {
                    L.log("BZ", "FAIL ${e.message}")
                }
            }
            if (!tailsFresh && s.useFdApi && s.fdApiKey.isNotBlank()) {
                // football-data.org (clé user, 10/min) : 2e source.
                try {
                    val codes = fetch.mapNotNull { com.widscore.data.FDOrgApi.codeFor(it) }.distinct().take(8)
                    L.log("FD", "competitions=$codes")
                    for (code in codes) {
                        val slug = com.widscore.data.FDOrgApi.slugFor(code) ?: continue
                        val list = com.widscore.data.FDOrgApi.getCompetitionMatches(
                            s.fdApiKey, code, slug, s.teams
                        )
                        if (list != null) all += list
                    }
                } catch (e: Exception) {
                    L.log("FD", "FAIL ${e.message}")
                }
            }
            // Backfill écussons : les sources sans logos (BZ, fixtures) récupèrent
            // ceux d'ESPN UNIQUEMENT si >=90% pareil (isSameClub), sinon rien.
            try {
                data class LogoEntry(val name: String, val abbr: String, val logo: String)
                val index = mutableListOf<LogoEntry>()
                fun putTeam(name: String, abbr: String, logo: String) {
                    if (logo.isBlank() || name.isBlank()) return
                    if (index.none { it.name == name && it.logo == logo }) {
                        index.add(LogoEntry(name, abbr, logo))
                    }
                }
                for (t in com.widscore.data.RosterStore.loadCached(ctx)) {
                    putTeam(t.name, t.abbr, t.logo)
                }
                for (m in all) {
                    // Seules les entrées ESPN (ids natifs) alimentent l'index.
                    if (m.id.startsWith("fd-") || m.id.startsWith("bz-")) continue
                    putTeam(m.home.name, m.home.abbr, m.home.logo)
                    putTeam(m.away.name, m.away.abbr, m.away.logo)
                }
                var filled = 0
                for (m in all) {
                    for (side in listOf(m.home, m.away)) {
                        if (side.logo.isNotBlank()) continue
                        val hit = index.firstOrNull {
                            com.widscore.data.RosterStore.isSameClub(side.name, side.abbr, it.name, it.abbr)
                        }
                        if (hit != null) {
                            side.logo = hit.logo
                            filled++
                        }
                    }
                }
                L.log("LOGOS", "backfilled +$filled (index ${index.size})")
            } catch (e: Exception) {
                L.log("LOGOS", "FAIL ${e.message}")
            }
            // + noms abrégés ("Amed SFK" vs "Amed Sportif Faaliyetler", tokens) +
            // dates en conflit inter-sources (10/10 vs 11/10).
            // Passe 1 : même jour + mêmes côtés + (même ligue OU même score).
            // Passe 2 : même ligue + mêmes côtés même si jours diffèrent
            // (conflit de dates). Aller/retour (côtés inversés) jamais mergés.
            // À égalité : live > score > ESPN-numerique > premier.
            fun idRank(m: com.widscore.data.EspnMatch): Int =
                if (m.id.startsWith("fd-") || m.id.startsWith("bz-")) 1 else 0
            fun stateRank(m: com.widscore.data.EspnMatch): Int = when {
                m.isLive -> 3
                m.isFinished && m.homeScore != null -> 2
                m.isFinished -> 1
                else -> 0
            }
            fun better(c: com.widscore.data.EspnMatch, k: com.widscore.data.EspnMatch): Boolean {
                if (stateRank(c) != stateRank(k)) return stateRank(c) > stateRank(k)
                return idRank(c) < idRank(k)
            }
            val kept = mutableListOf<com.widscore.data.EspnMatch>()
            for (m in all) {
                val day = dayKey(m.utcMillis)
                val idx = kept.indexOfFirst { k ->
                    dayKey(k.utcMillis) == day &&
                        sameSide(k.home, m.home) && sameSide(k.away, m.away) &&
                        (k.leagueSlug.equals(m.leagueSlug, ignoreCase = true) || sameScore(k, m))
                }
                if (idx < 0) kept.add(m)
                else if (better(m, kept[idx])) kept[idx] = m
            }
            val final = mutableListOf<com.widscore.data.EspnMatch>()
            for (m in kept) {
                val idx = final.indexOfFirst { k ->
                    k.leagueSlug.equals(m.leagueSlug, ignoreCase = true) &&
                        sameSide(k.home, m.home) && sameSide(k.away, m.away)
                }
                if (idx < 0) final.add(m)
                else {
                    val keepNew = better(m, final[idx])
                    L.log("DEDUP", "date-conflict ${m.home.name} vs ${m.away.name} -> ${if (keepNew) "new" else "kept"}")
                    if (keepNew) final[idx] = m
                }
            }
            val dedup = final
            L.log("SYNC", "dedup ${all.size} -> ${dedup.size}")
            val shown = EspnApi.applySettings(dedup, s)
            val now = System.currentTimeMillis()
            Prefs.saveCache(ctx, shown, now)
            // Terminés masqués par la rétention (diagnostic "match passé invisible").
            val hiddenOld = if (s.finishedHours <= 0) dedup.count { it.isFinished }
            else dedup.count {
                it.isFinished && (now - (it.utcMillis + 115 * 60_000L)) > s.finishedHours * 3600_000L
            }
            Prefs.saveReport(
                ctx, now, EspnApi.lastReport, shown.size, hiddenOld,
                EspnApi.lastSchedules, com.widscore.data.FDOrgApi.lastStatuses,
                com.widscore.data.BzApi.lastStatuses
            )
            L.log("SYNC", "fetched=${all.size} shown=${shown.size} hiddenOld=$hiddenOld live=${shown.count { it.isLive }}")
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
            for (m in shown.take(15)) {
                val sc = if (m.homeScore != null && m.awayScore != null) " ${m.homeScore}-${m.awayScore}" else ""
                L.log("SHOW", "${fmt.format(java.util.Date(m.utcMillis))} ${m.home.name} vs ${m.away.name}$sc [${m.leagueSlug}] ${m.state} id=${m.id}")
            }

            val mgr = AppWidgetManager.getInstance(ctx)
            val emptySetup = s.leagues.isEmpty() && s.teams.isEmpty()
            val live = shown.count { it.isLive }
            for (comp in listOf(
                ComponentName(ctx, ScoreWidgetClassicProvider::class.java),
                ComponentName(ctx, ScoreWidgetDarkProvider::class.java)
            )) {
                val dark = comp.className.contains("Dark")
                for (id in mgr.getAppWidgetIds(comp)) {
                    val views = WidgetRenderer.buildHeader(ctx, dark, live, now, emptySetup)
                    val adapter = Intent(ctx, MatchListService::class.java).apply {
                        putExtra(MatchListService.EXTRA_DARK, dark)
                        data = Uri.parse("widscore://widget/$id-${if (dark) "dark" else "classic"}-$now")
                    }
                    views.setRemoteAdapter(R.id.match_list, adapter)
                    val template = Intent(ctx, com.widscore.MainActivity::class.java)
                        .setAction(BaseScoreProvider.ACTION_MATCH)
                    val pi = android.app.PendingIntent.getActivity(
                        ctx, if (dark) 11 else 12, template,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                    )
                    views.setPendingIntentTemplate(R.id.match_list, pi)
                    try {
                        mgr.updateAppWidget(id, views)
                        mgr.notifyAppWidgetViewDataChanged(id, R.id.match_list)
                    } catch (_: Exception) {}
                }
            }
            schedulePeriodic(ctx, s.refreshMinutes)
            Result.success()
        } catch (e: Exception) {
            com.widscore.data.LogStore.log("SYNC", "FAIL ${e.message}")
            Result.retry()
        }
    }

    private fun sameSide(a: com.widscore.data.EspnTeam, b: com.widscore.data.EspnTeam): Boolean {
        if (a.id.isNotBlank() && b.id.isNotBlank()) return a.id == b.id
        // Id vide d'un côté : noms normalisés (accents ignorés).
        val na = normTeam(a.name)
        val nb = normTeam(b.name)
        // Nom court (ex. "psg") : match abbr ou initiales de l'autre.
        if (na.length < 4 || nb.length < 4) {
            if (na == nb) return true
            if (na.length < 4 && (na == b.abbr.lowercase() || na == initials(nb))) return true
            if (nb.length < 4 && (nb == a.abbr.lowercase() || nb == initials(na))) return true
            return false
        }
        if (na == nb || na.contains(nb) || nb.contains(na)) return true
        // Tokens : 1er token commun (prefixe >=6) + 2e signal
        // (token partagé, initiales, abbr). Ex. "amed sfk" = "amed sportif faaliyetler".
        // Les 2 côtés doivent matcher (appelant), donc pas de faux derby.
        val ta = na.split(" ")
        val tb = nb.split(" ")
        val fa = ta.firstOrNull().orEmpty()
        val fb = tb.firstOrNull().orEmpty()
        if (fa.isEmpty() || fb.isEmpty()) return false
        if (!(fa == fb || commonPrefixLen(fa, fb) >= 6)) return false
        if (a.abbr.isNotBlank() && b.abbr.isNotBlank() && a.abbr.equals(b.abbr, ignoreCase = true)) return true
        val ra = ta.drop(1)
        val rb = tb.drop(1)
        if (ra.any { x -> x.length >= 4 && rb.contains(x) }) return true
        val ia = initials(na)
        val ib = initials(nb)
        if (ia.isNotEmpty() && ib.isNotEmpty() && (ia.startsWith(ib) || ib.startsWith(ia))) return true
        val abA = a.abbr.uppercase()
        val abB = b.abbr.uppercase()
        if (abA.length >= 2 && (abA == ib || ib.startsWith(abA) || abA.startsWith(ib))) return true
        if (abB.length >= 2 && (abB == ia || ia.startsWith(abB) || abB.startsWith(ia))) return true
        return false
    }

    private fun initials(n: String): String =
        n.split(" ").mapNotNull { it.firstOrNull() }.joinToString("")

    private fun commonPrefixLen(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    private fun sameScore(a: com.widscore.data.EspnMatch, b: com.widscore.data.EspnMatch): Boolean {
        if (a.isLive || b.isLive) return true // le live absorbe les copies statiques
        if (a.isFinished && b.isFinished) return a.homeScore == b.homeScore && a.awayScore == b.awayScore
        return !a.isFinished && !b.isFinished // deux à venir sans score
    }

    private fun normTeam(s: String): String {
        val dec = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        return java.text.Normalizer.normalize(dec, java.text.Normalizer.Form.NFC)
            .filter { it.category != CharCategory.NON_SPACING_MARK }
            .map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("")
            .split(" ").filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun dayKey(millis: Long): String {        if (millis <= 0) return "?"
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        c.timeInMillis = millis
        return "%d-%d".format(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.DAY_OF_YEAR))
    }

    class RefreshReceiver : BroadcastReceiver() {        override fun onReceive(ctx: Context, intent: Intent?) {
            // Feedback immédiat : bouton ⟳ foncé, restore normal quand le worker finit.
            try {
                val s = Prefs.load(ctx)
                val emptySetup = s.leagues.isEmpty() && s.teams.isEmpty()
                val mgr = AppWidgetManager.getInstance(ctx)
                for (comp in listOf(
                    ComponentName(ctx, ScoreWidgetClassicProvider::class.java),
                    ComponentName(ctx, ScoreWidgetDarkProvider::class.java)
                )) {
                    val dark = comp.className.contains("Dark")
                    for (id in mgr.getAppWidgetIds(comp)) {
                        val views = WidgetRenderer.buildHeader(ctx, dark, 0, 0L, emptySetup, pressed = true)
                        val adapter = Intent(ctx, MatchListService::class.java).apply {
                            putExtra(MatchListService.EXTRA_DARK, dark)
                            data = Uri.parse("widscore://widget/$id-${if (dark) "dark" else "classic"}-pressed")
                        }
                        views.setRemoteAdapter(R.id.match_list, adapter)
                        try { mgr.updateAppWidget(id, views) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            enqueueOneShot(ctx)
        }
    }

    companion object {
        private const val ONE = "widscore-refresh-once"
        private const val PERIODIC = "widscore-refresh-periodic"
        fun enqueueOneShot(ctx: Context) {
            // APPEND : les runs se sérialisent au lieu de s'annuler (les tails
            // FD/BZ longs mouraient avec "Job was cancelled" sous REPLACE).
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                ONE, ExistingWorkPolicy.APPEND,
                OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
            )
        }
        fun schedulePeriodic(ctx: Context, minutes: Int) {
            val every = 15L // mini WorkManager ; refresh <15 via one-shot (clic/appli)
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetUpdateWorker>(every, TimeUnit.MINUTES).build()
            )
        }
    }
}
