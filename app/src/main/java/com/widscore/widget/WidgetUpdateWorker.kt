package com.widscore.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.RemoteViews
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
import java.net.URL
import java.util.concurrent.TimeUnit

// Fetch ESPN + push RemoteViews aux 2 widgets. Périodique 15 min (mini Android)
// + one-shot à chaque onUpdate / bouton ⟳ / changement prefs.
class WidgetUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val s = Prefs.load(applicationContext)
        return try {
            // Ligues = abonnées + ligues des équipes suivies (copie RefreshAsync Palisades).
            val teamLeagues = s.teams.filter { it.kind == "team" && it.leagueSlug.isNotBlank() }
                .map { it.leagueSlug.lowercase() }
            val fetch = (s.leagues + teamLeagues).map { it.trim() }
                .filter { it.isNotBlank() }.distinct()
            val all = mutableListOf<com.widscore.data.EspnMatch>()
            for (lg in fetch) {
                all += EspnApi.getMatches(lg)
                kotlinx.coroutines.delay(150)
            }
            // Backfill terminés via schedules équipes suivies.
            for (t in s.teams.filter { it.kind == "team" }) {
                val lgs = listOf(t.leagueSlug).filter { it.isNotBlank() }
                for (lg in lgs) all += EspnApi.getTeamSchedule(lg, t.id)
            }
            val seen = HashSet<String>()
            val dedup = all.filter { seen.add(it.leagueSlug.lowercase() + "/" + it.id) }
            val shown = EspnApi.applySettings(dedup, s)

            val mgr = AppWidgetManager.getInstance(applicationContext)
            for (comp in listOf(
                ComponentName(applicationContext, ScoreWidgetClassicProvider::class.java),
                ComponentName(applicationContext, ScoreWidgetDarkProvider::class.java)
            )) {
                val dark = comp.className.contains("Dark")
                for (id in mgr.getAppWidgetIds(comp)) {
                    val views = WidgetRenderer.render(applicationContext, shown, s, dark)
                    try { mgr.updateAppWidget(id, views) } catch (_: Exception) {}
                }
            }
            schedulePeriodic(applicationContext, s.refreshMinutes)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    class RefreshReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: android.content.Intent?) {
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
            // TuneTimer Palisades : ralenti si rien de chaud — WorkManager mini 15 min.
            val every = 15L // base mini ; refresh <15 géré par one-shot au déverrouillage/clic
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetUpdateWorker>(every, TimeUnit.MINUTES).build()
            )
        }
    }
}

// Cache crests mémoire (équivalent _crestCache Palisades).
object CrestCache {
    private val mem = LruCache<String, Bitmap>(60)
    fun get(url: String): Bitmap? {
        if (url.isBlank()) return null
        mem[url]?.let { return it }
        return try {
            val bmp = BitmapFactory.decodeStream(URL(url).openStream()) ?: return null
            val scaled = Bitmap.createScaledBitmap(bmp, 96, 96, true)
            mem.put(url, scaled)
            scaled
        } catch (_: Exception) { null }
    }
}
