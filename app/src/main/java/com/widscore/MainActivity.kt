package com.widscore

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.widscore.data.CuratedLeagues
import com.widscore.data.FavTeam
import com.widscore.data.FootballSettings
import com.widscore.data.Lang
import com.widscore.data.Prefs
import com.widscore.data.RosterStore
import com.widscore.widget.BaseScoreProvider
import com.widscore.widget.WidgetUpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Pages bas : Teams (search + favs) | Leagues (liste + favs) | Settings (sync + perso + langue).
class MainActivity : AppCompatActivity() {

    private lateinit var leaguesBox: LinearLayout
    private lateinit var leaguesFilter: EditText
    private lateinit var leaguesCount: TextView
    private lateinit var searchBox: EditText
    private lateinit var searchResults: LinearLayout
    private lateinit var searchStatus: TextView
    private lateinit var dirProgress: ProgressBar
    private lateinit var favTeamsChips: ChipGroup
    private lateinit var favLeaguesChips: ChipGroup
    private lateinit var noFavTeams: TextView
    private lateinit var noFavLeagues: TextView
    private lateinit var syncStatus: TextView

    private var directory: List<RosterStore.RosterTeam> = emptyList()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchPending: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Lang.applySaved(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        leaguesBox = findViewById(R.id.leagues_box)
        leaguesFilter = findViewById(R.id.leagues_filter)
        leaguesCount = findViewById(R.id.leagues_count)
        searchBox = findViewById(R.id.search_box)
        searchResults = findViewById(R.id.search_results)
        searchStatus = findViewById(R.id.search_status)
        dirProgress = findViewById(R.id.directory_progress)
        favTeamsChips = findViewById(R.id.fav_teams_chips)
        favLeaguesChips = findViewById(R.id.fav_leagues_chips)
        noFavTeams = findViewById(R.id.no_fav_teams)
        noFavLeagues = findViewById(R.id.no_fav_leagues)
        syncStatus = findViewById(R.id.sync_status)

        handleMatchIntent()
        buildAll()

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            showPage(
                when (item.itemId) {
                    R.id.nav_leagues -> R.id.page_leagues
                    R.id.nav_settings -> R.id.page_settings
                    else -> R.id.page_teams
                }
            )
            true
        }

        searchBox.addTextChangedListener(watcher { renderSearch() })
        leaguesFilter.addTextChangedListener(watcher { renderLeagues() })

        findViewById<Button>(R.id.btn_refresh_now).setOnClickListener { refreshNow() }
        findViewById<Button>(R.id.btn_refresh_big).setOnClickListener { refreshNow() }
        findViewById<Button>(R.id.btn_lang_en).setOnClickListener { setLang(Lang.EN) }
        findViewById<Button>(R.id.btn_lang_fr).setOnClickListener { setLang(Lang.FR) }
        findViewById<Button>(R.id.btn_reload_teams).setOnClickListener { reloadTeams() }
        findViewById<Button>(R.id.btn_reload_all).setOnClickListener { reloadTeams() }

        startWarm()
        renderSync()
    }

    private fun watcher(go: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            searchPending?.let { searchHandler.removeCallbacks(it) }
            val r = Runnable { go() }
            searchPending = r
            searchHandler.postDelayed(r, 400)
        }
    }

    private fun showPage(id: Int) {
        findViewById<View>(R.id.page_teams).visibility = if (id == R.id.page_teams) View.VISIBLE else View.GONE
        findViewById<View>(R.id.page_leagues).visibility = if (id == R.id.page_leagues) View.VISIBLE else View.GONE
        findViewById<View>(R.id.page_settings).visibility = if (id == R.id.page_settings) View.VISIBLE else View.GONE
    }

    // --- Directory : dit qu'elle charge, reste en données (cache disque 30j) ---
    private fun startWarm() {
        lifecycleScope.launch {
            directory = withContext(Dispatchers.IO) { RosterStore.loadCached(this@MainActivity) }
            updateDirStatus(0, 0)
            renderSearch()
            val subs = withContext(Dispatchers.IO) { Prefs.load(this@MainActivity).leagues }
            val ordered = (subs + CuratedLeagues.all.map { it.slug }).distinct()
            withContext(Dispatchers.IO) {
                RosterStore.warm(this@MainActivity, ordered) { done, total ->
                    runOnUiThread {
                        updateDirStatus(done, total)
                        directory = RosterStore.loadCached(this@MainActivity)
                        renderSearch()
                    }
                }
            }
            directory = withContext(Dispatchers.IO) { RosterStore.loadCached(this@MainActivity) }
            updateDirStatus(-1, -1)
            renderSearch()
            // Découverte des compétitions des équipes suivies (TOUS leurs matchs
            // sans cocher aucune ligue). 1 appel/ligue, cache 30j.
            val favs = withContext(Dispatchers.IO) {
                Prefs.load(this@MainActivity).teams.filter { it.kind == "team" }
            }
            for (t in favs) {
                val known = withContext(Dispatchers.IO) {
                    RosterStore.loadCached(this@MainActivity)
                        .filter { it.id == t.id }.map { it.leagueSlug } + listOf(t.leagueSlug)
                }
                withContext(Dispatchers.IO) {
                    RosterStore.discoverTeamLeagues(this@MainActivity, t.id, known) { done, total ->
                        runOnUiThread {
                            dirProgress.visibility = View.VISIBLE
                            dirProgress.max = total
                            dirProgress.progress = done
                            searchStatus.text = getString(R.string.team_leagues_search, t.name, done, total)
                        }
                    }
                }
            }
            dirProgress.visibility = View.GONE
            searchStatus.text = getString(R.string.directory_ready, directory.size)
            WidgetUpdateWorker.enqueueOneShot(this@MainActivity)
        }
    }

    private fun updateDirStatus(done: Int, total: Int) {
        if (total > 0) {
            dirProgress.visibility = View.VISIBLE
            dirProgress.max = total
            dirProgress.progress = done
            searchStatus.text = getString(R.string.directory_loading, done, total)
        } else if (done == -1) {
            dirProgress.visibility = View.GONE
            searchStatus.text = getString(R.string.directory_ready, directory.size)
        } else {
            dirProgress.visibility = View.GONE
            searchStatus.text = if (directory.isEmpty()) getString(R.string.w_loading)
            else getString(R.string.directory_ready, directory.size)
        }
    }

    private fun reloadTeams() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { RosterStore.clearCache(this@MainActivity) }
            directory = emptyList()
            updateDirStatus(0, 0)
            renderSearch()
            startWarm()
            refreshNow()
        }
    }

    private fun setLang(lang: String) {
        if (Prefs.load(this).lang == lang) return
        Lang.set(this, lang)
        recreate()
    }

    private fun refreshNow() {
        WidgetUpdateWorker.enqueueOneShot(this)
        Toast.makeText(this, getString(R.string.toast_refreshing), Toast.LENGTH_SHORT).show()
    }

    private fun handleMatchIntent() {
        if (intent?.action == BaseScoreProvider.ACTION_MATCH) {
            val title = intent.getStringExtra("title") ?: "Match"
            // Clic match -> Google avec les 2 noms d'équipes (défaut).
            if (Prefs.load(this).matchClickAction == "google") {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(title)))
                    )
                } catch (_: Exception) {}
            } else {
                Toast.makeText(this, title, Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- Sync status : rate limit ESPN visible ---
    private fun renderSync() {
        val rep = Prefs.loadReport(this)
        if (rep == null) {
            syncStatus.text = getString(R.string.sync_never)
            return
        }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(rep.at))
        val ok = rep.leagues.count { it.ok }
        val sb = StringBuilder()
        sb.append(getString(R.string.sync_ok, time, rep.totalMatches)).append("\n")
        sb.append(getString(R.string.sync_leagues, ok, rep.leagues.size))
        for (lg in rep.leagues) {
            sb.append("\n• ${lg.league}: ${lg.count}")
        }
        if (rep.hiddenOld > 0) {
            val hours = settings().finishedHours
            sb.append("\n").append(getString(R.string.sync_hidden, rep.hiddenOld, hours))
        }
        for (sch in rep.schedules) {
            sb.append("\n• ${sch.team}: ")
            sb.append(if (sch.ok) "${sch.count}" + if (sch.source.isNotBlank()) " (${sch.source})" else ""
            else getString(R.string.sync_failed_short))
        }
        val failed = rep.leagues.filter { !it.ok }
        if (failed.isNotEmpty()) {
            val names = failed.joinToString(", ") { it.league }
            sb.append("\n").append(getString(R.string.sync_failed, names))
            if (failed.any { it.rateLimited }) sb.append("\n").append(getString(R.string.sync_ratelimit))
        }
        syncStatus.text = sb.toString()
    }

    private fun settings() = Prefs.load(this)

    private fun persist(s: FootballSettings) {
        Prefs.save(this, s)
        WidgetUpdateWorker.enqueueOneShot(this)
    }

    private fun buildAll() {
        renderLeagues()
        renderFavs()
        buildSpinners()
    }

    // --- Ligues (rien coché par défaut) ---
    private fun renderLeagues() {
        val s = settings()
        val q = RosterStore.norm(leaguesFilter.text.toString())
        leaguesBox.removeAllViews()
        var shown = 0
        for (lg in CuratedLeagues.all.filter {
            q.isEmpty() || RosterStore.norm(it.name).contains(q) || it.slug.contains(q)
        }) {
            val cb = CheckBox(this).apply {
                text = lg.name
                isChecked = s.leagues.any { it.equals(lg.slug, ignoreCase = true) }
            }
            cb.setOnCheckedChangeListener { _, checked ->
                val cur = settings()
                if (checked) {
                    if (cur.leagues.none { it.equals(lg.slug, ignoreCase = true) }) cur.leagues.add(lg.slug)
                } else cur.leagues.removeAll { it.equals(lg.slug, ignoreCase = true) }
                persist(cur)
                renderLeagues()
            }
            leaguesBox.addView(cb)
            shown++
        }
        leaguesCount.text = "${s.leagues.size} / ${CuratedLeagues.all.size} · $shown"
    }

    // --- Recherche auto teams + leagues (fuzzy) ---
    private fun renderSearch() {
        val q = searchBox.text.toString()
        searchResults.removeAllViews()
        val s = settings()
        val favIds = s.teams.map { it.kind + ":" + it.id }.toSet()
        val nq = RosterStore.norm(q)
        var added = 0

        for (lg in CuratedLeagues.all.filter {
            nq.isEmpty() || RosterStore.norm(it.name).contains(nq) || it.slug.contains(nq)
        }.take(4)) {
            searchResults.addView(favCheck("🏆 ${lg.name}", favIds.contains("league:${lg.slug}")) { v ->
                val cur = settings()
                if (v) cur.teams.add(FavTeam(lg.slug, lg.name, "league"))
                else cur.teams.removeAll { it.id == lg.slug && it.kind == "league" }
                persist(cur); renderFavs(); renderSearch()
            })
            added++
        }
        val teams = if (nq.isEmpty()) RosterStore.dedup(directory).sortedBy { it.name }.take(4)
        else RosterStore.search(directory, q).take(4)
        for (t in teams) {
            val label = "⭐ " + RosterStore.displayName(t) + if (t.abbr.isNotBlank()) " (${t.abbr})" else ""
            searchResults.addView(favCheck(label, favIds.contains("team:${t.id}")) { v ->
                val cur = settings()
                if (v) {
                    // Suivre équipe UNIQUEMENT : n'abonne plus sa ligue
                    // (fetch implicite via teamLeagues dans le worker).
                    cur.teams.add(t.toFav())
                } else cur.teams.removeAll { it.id == t.id && it.kind == "team" }
                persist(cur); renderFavs(); renderLeagues(); renderSearch()
            })
            added++
        }
        if (added == 0) {
            searchResults.addView(TextView(this).apply { text = getString(R.string.no_result) })
        }
    }

    private fun favCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox {
        val cb = CheckBox(this).apply { text = label; isChecked = checked }
        cb.setOnCheckedChangeListener { _, v -> onChange(v) }
        return cb
    }

    // --- Favoris séparés : chips teams / leagues ---
    private fun renderFavs() {
        val s = settings()
        favTeamsChips.removeAllViews()
        favLeaguesChips.removeAllViews()
        val teams = s.teams.filter { it.kind != "league" }
        val leagues = s.teams.filter { it.kind == "league" }
        noFavTeams.visibility = if (teams.isEmpty()) View.VISIBLE else View.GONE
        noFavLeagues.visibility = if (leagues.isEmpty()) View.VISIBLE else View.GONE
        for (t in teams) favTeamsChips.addView(favChip("⭐ " + t.name) {
            val cur = settings()
            cur.teams.removeAll { it.id == t.id && it.kind == t.kind }
            persist(cur); renderFavs(); renderSearch()
        })
        for (t in leagues) favLeaguesChips.addView(favChip("🏆 " + t.name) {
            val cur = settings()
            cur.teams.removeAll { it.id == t.id && it.kind == t.kind }
            persist(cur); renderFavs(); renderLeagues(); renderSearch()
        })
    }

    private fun favChip(label: String, onDelete: () -> Unit): Chip {
        val chip = Chip(this).apply { text = label; isCloseIconVisible = true }
        chip.setOnCloseIconClickListener { onDelete() }
        return chip
    }

    // --- Réglages perso ---
    private fun buildSpinners() {
        val s = settings()
        bindIntSpinner(R.id.sp_refresh, listOf(1, 5, 10, 15, 30, 60), s.refreshMinutes) { c, v -> c.refreshMinutes = v }
        bindIntSpinner(R.id.sp_max, listOf(4, 6, 8, 10, 12, 20, 30), s.maxMatches) { c, v -> c.maxMatches = v }
        bindIntSpinner(R.id.sp_finished_hours, listOf(0, 6, 12, 24, 48, 168, 336, 720), s.finishedHours) { c, v -> c.finishedHours = v }

        val scaleLabels = listOf("70%", "85%", "100%", "115%", "130%")
        val scaleVals = listOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f)
        val scaleIdx = scaleVals.indexOfFirst { it == s.widgetScale }.takeIf { it >= 0 } ?: 2
        bindStrSpinner(R.id.sp_scale, scaleLabels, scaleIdx) { c, p -> c.widgetScale = scaleVals[p] }

        val blockLabels = listOf(getString(R.string.block_small), getString(R.string.block_normal), getString(R.string.block_large))
        val blockVals = listOf("small", "normal", "large")
        bindStrSpinner(R.id.sp_block, blockLabels, blockVals.indexOf(s.blockSize).takeIf { it >= 0 } ?: 1) { c, p ->
            c.blockSize = blockVals[p]
        }

        val fmtLabels = listOf(getString(R.string.fmt_text), getString(R.string.fmt_numeric), getString(R.string.fmt_daynumeric))
        val fmtVals = listOf("text", "numeric", "daynumeric")
        bindStrSpinner(R.id.sp_datefmt, fmtLabels, fmtVals.indexOf(s.dateFormat).takeIf { it >= 0 } ?: 2) { c, p ->
            c.dateFormat = fmtVals[p]
        }

        val colLabels = listOf(getString(R.string.col_gray), getString(R.string.col_white), getString(R.string.col_accent))
        val colVals = listOf("#808080", "#FFFFFF", "#7DD3FC")
        val curCol = if (s.finishedTextColor.isBlank()) "#808080" else s.finishedTextColor.uppercase()
        bindStrSpinner(R.id.sp_fincolor, colLabels, colVals.indexOf(curCol).takeIf { it >= 0 } ?: 0) { c, p ->
            c.finishedTextColor = colVals[p]
        }

        val posLabels = listOf(getString(R.string.pos_top), getString(R.string.pos_bottom))
        bindStrSpinner(R.id.sp_finished_pos, posLabels, if (s.finishedPosition == "top") 0 else 1) { c, p ->
            c.finishedPosition = if (p == 0) "top" else "bottom"
        }
        val clickLabels = listOf(getString(R.string.click_details), getString(R.string.click_google))
        bindStrSpinner(R.id.sp_click, clickLabels, if (s.matchClickAction == "google") 1 else 0) { c, p ->
            c.matchClickAction = if (p == 1) "google" else "details"
        }
        bindCheck(R.id.cb_crests, s.showCrests) { c, v -> c.showCrests = v }
        bindCheck(R.id.cb_finished_header, s.showFinishedHeader) { c, v -> c.showFinishedHeader = v }
        bindCheck(R.id.cb_compact, s.compact) { c, v -> c.compact = v }
        bindCheck(R.id.cb_show_league, s.showLeague) { c, v -> c.showLeague = v }
    }

    private fun bindCheck(id: Int, current: Boolean, apply: (FootballSettings, Boolean) -> Unit) {
        findViewById<CheckBox>(id).apply {
            setOnCheckedChangeListener(null); isChecked = current
            setOnCheckedChangeListener { _, v -> val c = settings(); apply(c, v); persist(c) }
        }
    }

    private fun spinnerAdapter(options: List<String>): ArrayAdapter<String> {
        val a = ArrayAdapter(this, R.layout.spinner_item, options)
        a.setDropDownViewResource(R.layout.spinner_dropdown)
        return a
    }

    private fun bindIntSpinner(id: Int, options: List<Int>, current: Int, apply: (FootballSettings, Int) -> Unit) {
        val sp = findViewById<Spinner>(id)
        sp.adapter = spinnerAdapter(options.map { it.toString() })
        sp.onItemSelectedListener = null
        sp.setSelection(maxOf(0, options.indexOf(current)))
        sp.onItemSelectedListener = guardListener {
            val c = settings(); apply(c, options[sp.selectedItemPosition]); persist(c)
        }
    }

    private fun bindStrSpinner(id: Int, options: List<String>, current: Int, apply: (FootballSettings, Int) -> Unit) {
        val sp = findViewById<Spinner>(id)
        sp.adapter = spinnerAdapter(options)
        sp.onItemSelectedListener = null
        sp.setSelection(current)
        sp.onItemSelectedListener = guardListener {
            val c = settings(); apply(c, sp.selectedItemPosition); persist(c)
        }
    }

    private fun guardListener(go: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        var first = true
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            if (first) { first = false; return }
            go()
        }
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }

    override fun onResume() {
        super.onResume()
        renderSync()
    }
}
