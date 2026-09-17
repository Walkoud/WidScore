package com.widscore

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.widscore.data.CuratedLeagues
import com.widscore.data.EspnApi
import com.widscore.data.FavTeam
import com.widscore.data.Prefs
import com.widscore.widget.WidgetUpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Écran config : ligues suivies + recherche équipes + favoris + réglages.
// Équivaut au panneau Dashboard Football de Palisades + FootballTeamSearchWindow.
class MainActivity : AppCompatActivity() {

    private lateinit var leaguesBox: LinearLayout
    private lateinit var favsBox: LinearLayout
    private lateinit var searchBox: EditText
    private lateinit var searchResults: LinearLayout
    private var knownTeams: List<FavTeam> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        leaguesBox = findViewById(R.id.leagues_box)
        favsBox = findViewById(R.id.favs_box)
        searchBox = findViewById(R.id.search_box)
        searchResults = findViewById(R.id.search_results)

        handleMatchIntent()
        buildSettings()
        refreshKnownTeams()

        findViewById<Button>(R.id.btn_search).setOnClickListener { renderSearch() }
        findViewById<Button>(R.id.btn_refresh_now).setOnClickListener {
            WidgetUpdateWorker.enqueueOneShot(this)
            Toast.makeText(this, "Actualisation widgets…", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleMatchIntent() {
        if (intent?.action == "com.widscore.MATCH") {
            val title = intent.getStringExtra("title") ?: "Match"
            Toast.makeText(this, title, Toast.LENGTH_LONG).show()
        }
    }

    private fun settings() = Prefs.load(this)
    private fun saveSettings(s: com.widscore.data.FootballSettings) {
        Prefs.save(this, s)
        WidgetUpdateWorker.enqueueOneShot(this)
        buildSettings()
    }

    private fun buildSettings() {
        val s = settings()
        // Ligues : checkboxes (copie Db_FootballLeagues).
        leaguesBox.removeAllViews()
        for (lg in CuratedLeagues.all) {
            val cb = CheckBox(this).apply {
                text = lg.name
                isChecked = s.leagues.any { it.equals(lg.slug, ignoreCase = true) }
            }
            cb.setOnCheckedChangeListener { _, checked ->
                val cur = settings()
                if (checked) {
                    if (cur.leagues.none { it.equals(lg.slug, ignoreCase = true) }) cur.leagues.add(lg.slug)
                } else cur.leagues.removeAll { it.equals(lg.slug, ignoreCase = true) }
                Prefs.save(this, cur)
                WidgetUpdateWorker.enqueueOneShot(this)
            }
            leaguesBox.addView(cb)
        }
        // Favoris.
        favsBox.removeAllViews()
        if (s.teams.isEmpty()) favsBox.addView(TextView(this).apply { text = "Aucun favori — cherchez une équipe ci-dessous." })
        for (t in s.teams) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = (if (t.kind == "league") "🏆 " else "⭐ ") + t.name
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val del = Button(this).apply { text = "✕" }
            del.setOnClickListener {
                val cur = settings()
                cur.teams.removeAll { it.id == t.id && it.kind == t.kind }
                saveSettings(cur)
            }
            row.addView(label); row.addView(del)
            favsBox.addView(row)
        }
        // Réglages rapides.
        bindSpinner(R.id.sp_refresh, listOf(1, 5, 10, 15, 30, 60), s.refreshMinutes) { cur, v -> cur.refreshMinutes = v }
        bindSpinner(R.id.sp_max, listOf(2, 4, 6, 8, 12, 20), s.maxMatches) { cur, v -> cur.maxMatches = v }
        bindSpinner(R.id.sp_finished_hours, listOf(0, 6, 12, 24, 48, 168), s.finishedHours) { cur, v -> cur.finishedHours = v }
        val spPos = findViewById<Spinner>(R.id.sp_finished_pos)
        spPos.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("top", "bottom"))
        spPos.setSelection(if (s.finishedPosition == "top") 0 else 1)
        spPos.onItemSelectedListener = simpleListener {
            val cur = settings(); cur.finishedPosition = spPos.selectedItem as String; Prefs.save(this, cur)
            WidgetUpdateWorker.enqueueOneShot(this)
        }
        findViewById<CheckBox>(R.id.cb_crests).apply {
            setOnCheckedChangeListener(null); isChecked = s.showCrests
            setOnCheckedChangeListener { _, v -> val cur = settings(); cur.showCrests = v; Prefs.save(this@MainActivity, cur); WidgetUpdateWorker.enqueueOneShot(this@MainActivity) }
        }
        findViewById<CheckBox>(R.id.cb_finished_header).apply {
            setOnCheckedChangeListener(null); isChecked = s.showFinishedHeader
            setOnCheckedChangeListener { _, v -> val cur = settings(); cur.showFinishedHeader = v; Prefs.save(this@MainActivity, cur); WidgetUpdateWorker.enqueueOneShot(this@MainActivity) }
        }
    }

    private fun bindSpinner(id: Int, options: List<Int>, current: Int, apply: (com.widscore.data.FootballSettings, Int) -> Unit) {
        val sp = findViewById<Spinner>(id)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        sp.setSelection(maxOf(0, options.indexOf(current)))
        sp.onItemSelectedListener = simpleListener {
            val cur = settings(); apply(cur, sp.selectedItem as Int); Prefs.save(this, cur)
            WidgetUpdateWorker.enqueueOneShot(this)
        }
    }

    private fun simpleListener(go: () -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        var first = true
        override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
            if (first) { first = false; return }
            go()
        }
        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
    }

    // Recherche équipes : sur matchs fetchés des ligues suivies (léger, sans world-roster).
    private fun refreshKnownTeams() {
        lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) { Prefs.load(this@MainActivity) }
            val found = linkedMapOf<String, FavTeam>()
            withContext(Dispatchers.IO) {
                for (lg in s.leagues.take(12)) {
                    for (m in EspnApi.getMatches(lg)) {
                        if (m.home.id.isNotBlank()) found.putIfAbsent(m.home.id, FavTeam(m.home.id, m.home.name, "team", lg))
                        if (m.away.id.isNotBlank()) found.putIfAbsent(m.away.id, FavTeam(m.away.id, m.away.name, "team", lg))
                    }
                }
            }
            knownTeams = found.values.sortedBy { it.name }
            renderSearch()
        }
    }

    private fun renderSearch() {
        val q = searchBox.text.toString().trim().lowercase()
        searchResults.removeAllViews()
        val s = settings()
        val favIds = s.teams.map { it.kind + ":" + it.id }.toSet()
        // Ligues filtrées.
        for (lg in CuratedLeagues.all.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.slug.contains(q) }.take(10)) {
            val cb = CheckBox(this).apply {
                text = "🏆 ${lg.name}"
                isChecked = favIds.contains("league:${lg.slug}")
            }
            cb.setOnCheckedChangeListener { _, v ->
                val cur = settings()
                if (v) cur.teams.add(FavTeam(lg.slug, lg.name, "league"))
                else cur.teams.removeAll { it.id == lg.slug && it.kind == "league" }
                saveSettings(cur)
            }
            searchResults.addView(cb)
        }
        for (t in knownTeams.filter { q.isEmpty() || it.name.lowercase().contains(q) }.take(40)) {
            val cb = CheckBox(this).apply {
                text = "⭐ ${t.name}"
                isChecked = favIds.contains("team:${t.id}")
            }
            cb.setOnCheckedChangeListener { _, v ->
                val cur = settings()
                if (v) {
                    cur.teams.add(t)
                    if (t.leagueSlug.isNotBlank() && cur.leagues.none { it.equals(t.leagueSlug, ignoreCase = true) })
                        cur.leagues.add(t.leagueSlug) // auto-abonne ligue (copie SetFavorite)
                } else cur.teams.removeAll { it.id == t.id && it.kind == "team" }
                saveSettings(cur)
            }
            searchResults.addView(cb)
        }
    }
}
