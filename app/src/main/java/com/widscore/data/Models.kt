package com.widscore.data

// Copie modèle EspnService.cs Palisades (cdn.espn.com scoreboard).
data class EspnTeam(
    var id: String = "",
    var name: String = "",
    var abbr: String = "",
    var logo: String = ""
)

data class EspnMatch(
    var id: String = "",
    var leagueSlug: String = "",
    var leagueName: String = "",
    var leagueLogo: String = "",
    var utcMillis: Long = 0L,
    var state: String = "", // pre | in | post
    var clock: String = "",
    var detail: String = "",
    var home: EspnTeam = EspnTeam(),
    var away: EspnTeam = EspnTeam(),
    var homeScore: Int? = null,
    var awayScore: Int? = null
) {
    val isLive get() = state == "in"
    val isFinished get() = state == "post"
    val isUpcoming get() = state == "pre"
}

data class FavTeam(
    val id: String = "",
    val name: String = "",
    val kind: String = "team", // team | league
    val leagueSlug: String = ""
)

data class FootballSettings(
    var leagues: MutableList<String> = mutableListOf("eng.1", "esp.1", "ita.1", "ger.1", "fra.1", "tur.1"),
    var teams: MutableList<FavTeam> = mutableListOf(),
    var refreshMinutes: Int = 10,
    var maxMatches: Int = 8,
    var showCrests: Boolean = true,
    var finishedHours: Int = 24,
    var finishedPosition: String = "top", // top | bottom
    var finishedTextColor: String = "#808080",
    var showFinishedHeader: Boolean = true,
    var showFinishedDates: Boolean = true,
    var cardTheme: String = "classic", // classic | dark (réglage global, widgets dédiés sinon)
    var matchClickAction: String = "details", // details | google
    var dateFormat: String = "daynumeric" // text | numeric | daynumeric
)
