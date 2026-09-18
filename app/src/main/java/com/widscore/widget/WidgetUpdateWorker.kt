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
        val s = Prefs.load(ctx)
        return try {
            // Copie RefreshAsync Palisades : une équipe suivie amène TOUTES ses ligues
            // (directory monde : ex. Besiktas -> tur.1 + uefa.europa), pas juste sa ligue
            // principale. Sans ça, les matchs européens sont invisibles.
            val dirByTeam = com.widscore.data.RosterStore.loadCached(ctx)
                .groupBy({ it.id }, { it.leagueSlug.lowercase() })
            val favTeams = s.teams.filter { it.kind == "team" }
            val teamLeagueMap = favTeams.associate { t ->
                t.id to ((dirByTeam[t.id] ?: emptyList()) + listOf(t.leagueSlug.lowercase()))
                    .map { it.trim() }.filter { it.isNotBlank() }.distinct()
            }
            val fetch = (s.leagues + teamLeagueMap.values.flatten()).map { it.trim() }
                .filter { it.isNotBlank() }.distinct()
            val all = mutableListOf<com.widscore.data.EspnMatch>()
            for (lg in fetch) {
                all += EspnApi.getMatches(lg)
                kotlinx.coroutines.delay(150)
            }
            // Backfill schedules par équipe ET par ligue (copie Palisades).
            for (t in favTeams) {
                for (lg in teamLeagueMap[t.id].orEmpty()) {
                    all += EspnApi.getTeamSchedule(lg, t.id, t.name)
                }
            }
            // Fixtures saison (core.api team events, cache 24h) : matchs à venir
            // garantis même hors fenêtre CDN et hors schedules.
            try {
                val triples = favTeams.flatMap { t ->
                    teamLeagueMap[t.id].orEmpty().map { lg -> Triple(lg, t.id, t.name) }
                }
                all += com.widscore.data.TeamEventsStore.refresh(ctx, triples)
            } catch (_: Exception) {}
            val seen = HashSet<String>()
            val dedup = all.filter { seen.add(it.leagueSlug.lowercase() + "/" + it.id) }
            val shown = EspnApi.applySettings(dedup, s)
            val now = System.currentTimeMillis()
            Prefs.saveCache(ctx, shown, now)
            // Terminés masqués par la rétention (diagnostic "match passé invisible").
            val hiddenOld = if (s.finishedHours <= 0) dedup.count { it.isFinished }
            else dedup.count {
                it.isFinished && (now - (it.utcMillis + 115 * 60_000L)) > s.finishedHours * 3600_000L
            }
            Prefs.saveReport(ctx, now, EspnApi.lastReport, shown.size, hiddenOld, EspnApi.lastSchedules)

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
        } catch (_: Exception) {
            Result.retry()
        }
    }

    class RefreshReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent?) {
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
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                ONE, ExistingWorkPolicy.REPLACE,
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
