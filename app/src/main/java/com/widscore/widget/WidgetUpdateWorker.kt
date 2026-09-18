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
            // Dedup cross-sources : même jour + mêmes côtés (ids égaux, ou noms
            // flous si id vide), même ligue OU (même score / tous sans score).
            // Tue aussi les copies mal labellisées (ex. même match sous 2 ligues).
            // Ordre d'insertion = priorité (scoreboard/schedule d'abord).
            val kept = mutableListOf<com.widscore.data.EspnMatch>()
            for (m in all) {
                val day = dayKey(m.utcMillis)
                val dup = kept.any { k ->
                    dayKey(k.utcMillis) == day &&
                        sameSide(k.home, m.home) && sameSide(k.away, m.away) &&
                        (k.leagueSlug.equals(m.leagueSlug, ignoreCase = true) || sameScore(k, m))
                }
                if (!dup) kept.add(m)
            }
            val dedup = kept
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
                        data = Uri.parse("widscore://widget/$id-${if (dark) "dark" else "classic"}")
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
        // Id vide d'un côté : compare noms normalisés (accents ignorés),
        // containment dans un sens (ex. "marseille" vs "olympique marseille").
        val na = normTeam(a.name)
        val nb = normTeam(b.name)
        if (na.length < 4 || nb.length < 4) return na == nb
        return na == nb || na.contains(nb) || nb.contains(na)
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
                            data = Uri.parse("widscore://widget/$id-${if (dark) "dark" else "classic"}")
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
