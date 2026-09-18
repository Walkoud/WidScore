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

// Config : ligues + auto-search directory monde + favoris chips + réglages + langue EN/FR.
class MainActivity : AppCompatActivity() {

    private lateinit var leaguesBox: LinearLayout
    private lateinit var leaguesFilter: EditText
    private lateinit var leaguesCount: TextView
    private lateinit var searchBox: EditText
    private lateinit var searchResults: LinearLayout
    private lateinit var searchStatus: TextView
    private lateinit var dirProgress: ProgressBar
    private lateinit var favsChips: ChipGroup
    private lateinit var favsBox: LinearLayout

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
        favsChips = findViewById(R.id.favs_chips)
        favsBox = findViewById(R.id.favs_box)

        handleMatchIntent()
        buildAll()

        // Auto-search : 400 ms debounce, sans bouton.
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchPending?.let { searchHandler.removeCallbacks(it) }
                val r = Runnable { renderSearch() }
                searchPending = r
                searchHandler.postDelayed(r, 400)
            }
        })
        leaguesFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { renderLeagues() }
        })

        findViewById<Button>(R.id.btn_refresh_now).setOnClickListener { refreshNow() }
        findViewById<Button>(R.id.btn_refresh_big).setOnClickListener { refreshNow() }
        findViewById<Button>(R.id.btn_lang_en).setOnClickListener { setLang(Lang.EN) }
        findViewById<Button>(R.id.btn_lang_fr).setOnClickListener { setLang(Lang.FR) }

        // Directory monde en fond : abonnées d'abord, puis curated (copie EnsureWorldRosters).
        lifecycleScope.launch {
            directory = withContext(Dispatchers.IO) { RosterStore.loadCached(this@MainActivity) }
            renderSearch()
            val subs = withContext(Dispatchers.IO) { Prefs.load(this@MainActivity).leagues }
            val ordered = (subs + CuratedLeagues.all.map { it.slug }).distinct()
            withContext(Dispatchers.IO) {
                RosterStore.warm(this@MainActivity, ordered) { done, total ->
                    runOnUiThread {
                        if (total > 0) {
                            dirProgress.visibility = View.VISIBLE
                            dirProgress.max = total
                            dirProgress.progress = done
                            searchStatus.text = getString(R.string.directory_loading, done, total)
                        } else {
                            dirProgress.visibility = View.GONE
                        }
                        directory = RosterStore.loadCached(this@MainActivity)
                        renderSearch()
                    }
                }
            }
            dirProgress.visibility = View.GONE
            directory = withContext(Dispatchers.IO) { RosterStore.loadCached(this@MainActivity) }
            searchStatus.text = getString(R.string.directory_ready, directory.size)
            renderSearch()
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
            val title = intent.getStringExtra(MatchListServiceTitle.EXTRA_TITLE) ?: "Match"
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
        val q = leaguesFilter.text.toString().trim().lowercase()
        leaguesBox.removeAllViews()
        var shown = 0
        for (lg in CuratedLeagues.all.filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.slug.contains(q)
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
        val n = s.leagues.size
        leaguesCount.text = "$n / ${CuratedLeagues.all.size} · $shown"
    }

    // --- Recherche auto : ligues + directory monde ---
    private fun renderSearch() {
        val q = searchBox.text.toString()
        searchResults.removeAllViews()
        val s = settings()
        val favIds = s.teams.map { it.kind + ":" + it.id }.toSet()
        val nq = RosterStore.norm(q)
        var added = 0

        for (lg in CuratedLeagues.all.filter {
            nq.isEmpty() || RosterStore.norm(it.name).contains(nq) || it.slug.contains(nq)
        }.take(if (nq.isEmpty()) 4 else 8)) {
            searchResults.addView(favCheck("🏆 ${lg.name}", favIds.contains("league:${lg.slug}")) { v ->
                val cur = settings()
                if (v) cur.teams.add(FavTeam(lg.slug, lg.name, "league"))
                else cur.teams.removeAll { it.id == lg.slug && it.kind == "league" }
                persist(cur); renderFavs(); renderSearch()
            })
            added++
        }
        val teams = if (nq.isEmpty()) directory.sortedBy { it.name }.take(20)
        else RosterStore.search(directory, q)
        for (t in teams) {
            val label = "⭐ ${t.name}" + if (t.abbr.isNotBlank()) " (${t.abbr})" else ""
            searchResults.addView(favCheck(label, favIds.contains("team:${t.id}")) { v ->
                val cur = settings()
                if (v) {
                    cur.teams.add(t.toFav())
                    if (t.leagueSlug.isNotBlank() && cur.leagues.none { it.equals(t.leagueSlug, ignoreCase = true) })
                        cur.leagues.add(t.leagueSlug) // auto-abonne ligue (copie SetFavorite)
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

    // --- Favoris : chips supprimables ---
    private fun renderFavs() {
        val s = settings()
        favsChips.removeAllViews()
        favsBox.removeAllViews()
        if (s.teams.isEmpty()) {
            favsBox.addView(TextView(this).apply { text = getString(R.string.no_favorites) })
            return
        }
        for (t in s.teams) {
            val chip = Chip(this).apply {
                text = (if (t.kind == "league") "🏆 " else "⭐ ") + t.name
                isCloseIconVisible = true
            }
            chip.setOnCloseIconClickListener {
                val cur = settings()
                cur.teams.removeAll { it.id == t.id && it.kind == t.kind }
                persist(cur); renderFavs(); renderLeagues(); renderSearch()
            }
            favsChips.addView(chip)
        }
    }

    // --- Réglages ---
    private fun buildSpinners() {
        val s = settings()
        bindIntSpinner(R.id.sp_refresh, listOf(1, 5, 10, 15, 30, 60), s.refreshMinutes) { c, v -> c.refreshMinutes = v }
        bindIntSpinner(R.id.sp_max, listOf(4, 6, 8, 10, 12, 20, 30), s.maxMatches) { c, v -> c.maxMatches = v }
        bindIntSpinner(R.id.sp_finished_hours, listOf(0, 6, 12, 24, 48, 168), s.finishedHours) { c, v -> c.finishedHours = v }
        val posLabels = listOf(getString(R.string.pos_top), getString(R.string.pos_bottom))
        bindStrSpinner(R.id.sp_finished_pos, posLabels, if (s.finishedPosition == "top") 0 else 1) { c, p ->
            c.finishedPosition = if (p == 0) "top" else "bottom"
        }
        val clickLabels = listOf(getString(R.string.click_details), getString(R.string.click_google))
        bindStrSpinner(R.id.sp_click, clickLabels, if (s.matchClickAction == "google") 1 else 0) { c, p ->
            c.matchClickAction = if (p == 1) "google" else "details"
        }
        findViewById<CheckBox>(R.id.cb_crests).apply {
            setOnCheckedChangeListener(null); isChecked = s.showCrests
            setOnCheckedChangeListener { _, v -> val c = settings(); c.showCrests = v; persist(c) }
        }
        findViewById<CheckBox>(R.id.cb_finished_header).apply {
            setOnCheckedChangeListener(null); isChecked = s.showFinishedHeader
            setOnCheckedChangeListener { _, v -> val c = settings(); c.showFinishedHeader = v; persist(c) }
        }
    }

    private fun bindIntSpinner(id: Int, options: List<Int>, current: Int, apply: (FootballSettings, Int) -> Unit) {
        val sp = findViewById<Spinner>(id)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        sp.onItemSelectedListener = null
        sp.setSelection(maxOf(0, options.indexOf(current)))
        sp.onItemSelectedListener = guardListener {
            val c = settings(); apply(c, sp.selectedItem as Int); persist(c)
        }
    }

    private fun bindStrSpinner(id: Int, options: List<String>, current: Int, apply: (FootballSettings, Int) -> Unit) {
        val sp = findViewById<Spinner>(id)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
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
}

// Alias pour extra titre (évite import circulaire widget).
private object MatchListServiceTitle {
    const val EXTRA_TITLE = "title"
}
