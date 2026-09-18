package com.widscore.data

// Copie modèle EspnService.cs Palisades (cdn.espn.com scoreboard).
data class EspnTeam(
    var id: String = "",
    var name: String = "",
    var abbr: String = "",
    var logo: String = ""
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("id", id).put("name", name).put("abbr", abbr).put("logo", logo)

    companion object {
        fun fromJson(o: org.json.JSONObject) = EspnTeam(
            o.optString("id"), o.optString("name"),
            o.optString("abbr"), o.optString("logo")
        )
    }
}

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

    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("id", id).put("leagueSlug", leagueSlug).put("leagueName", leagueName)
        .put("leagueLogo", leagueLogo).put("utcMillis", utcMillis)
        .put("state", state).put("clock", clock).put("detail", detail)
        .put("home", home.toJson()).put("away", away.toJson())
        .put("homeScore", homeScore ?: org.json.JSONObject.NULL)
        .put("awayScore", awayScore ?: org.json.JSONObject.NULL)

    companion object {
        fun fromJson(o: org.json.JSONObject): EspnMatch? = try {
            EspnMatch(
                id = o.optString("id"),
                leagueSlug = o.optString("leagueSlug"),
                leagueName = o.optString("leagueName"),
                leagueLogo = o.optString("leagueLogo"),
                utcMillis = o.optLong("utcMillis"),
                state = o.optString("state"),
                clock = o.optString("clock"),
                detail = o.optString("detail"),
                home = EspnTeam.fromJson(o.optJSONObject("home") ?: org.json.JSONObject()),
                away = EspnTeam.fromJson(o.optJSONObject("away") ?: org.json.JSONObject()),
                homeScore = if (o.isNull("homeScore")) null else o.optInt("homeScore"),
                awayScore = if (o.isNull("awayScore")) null else o.optInt("awayScore")
            )
        } catch (_: Exception) { null }
    }
}

data class FavTeam(
    val id: String = "",
    val name: String = "",
    val kind: String = "team", // team | league
    val leagueSlug: String = ""
)

data class FootballSettings(
    // Rien coché par défaut : l'utilisateur choisit ses ligues/équipes dans l'app.
    var leagues: MutableList<String> = mutableListOf(),
    var teams: MutableList<FavTeam> = mutableListOf(),
    var refreshMinutes: Int = 10,
    var maxMatches: Int = 10,
    var showCrests: Boolean = true,
    var finishedHours: Int = 24,
    var finishedPosition: String = "top", // top | bottom
    var finishedTextColor: String = "#808080",
    var showFinishedHeader: Boolean = true,
    var showFinishedDates: Boolean = true,
    var cardTheme: String = "classic",
    var matchClickAction: String = "details", // details | google
    var dateFormat: String = "daynumeric", // text | numeric | daynumeric
    var lang: String = "en" // en | fr
)
