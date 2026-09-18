package com.widscore.data

// Copie CuratedLeagues EspnService.cs Palisades.
data class EspnLeague(val slug: String, val name: String)

object CuratedLeagues {
    val all = listOf(
        EspnLeague("eng.1", "Premier League"),
        EspnLeague("eng.2", "Championship"),
        EspnLeague("esp.1", "La Liga"),
        EspnLeague("esp.2", "Segunda División"),
        EspnLeague("ita.1", "Serie A"),
        EspnLeague("ita.2", "Serie B"),
        EspnLeague("ger.1", "Bundesliga"),
        EspnLeague("ger.2", "2. Bundesliga"),
        EspnLeague("fra.1", "Ligue 1"),
        EspnLeague("ned.1", "Eredivisie"),
        EspnLeague("por.1", "Liga Portugal"),
        EspnLeague("sco.1", "Premiership (SCO)"),
        EspnLeague("bel.1", "Pro League (BEL)"),
        EspnLeague("aut.1", "Bundesliga (AUT)"),
        EspnLeague("den.1", "Superliga (DEN)"),
        EspnLeague("nor.1", "Eliteserien"),
        EspnLeague("swe.1", "Allsvenskan"),
        EspnLeague("tur.1", "Süper Lig (Türkiye)"),
        EspnLeague("usa.1", "MLS"),
        EspnLeague("mex.1", "Liga MX"),
        EspnLeague("bra.1", "Serie A (BRA)"),
        EspnLeague("arg.1", "Primera División (ARG)"),
        EspnLeague("chn.1", "Super League (CHN)"),
        EspnLeague("jpn.1", "J1 League"),
        EspnLeague("aus.1", "A-League"),
        EspnLeague("uefa.champions", "Champions League"),
        EspnLeague("uefa.europa", "Europa League"),
        EspnLeague("uefa.europa.conf", "Conference League"),
        EspnLeague("uefa.nations", "Nations League"),
        EspnLeague("fifa.world", "World Cup"),
        EspnLeague("conmebol.america", "Copa América"),
        EspnLeague("fifa.friendly", "Friendlies"),
        EspnLeague("uefa.euro", "Euro"),
        EspnLeague("fifa.cwc", "Club World Cup"),
        EspnLeague("eng.fa", "FA Cup"),
        EspnLeague("eng.charity", "Community Shield"),
        EspnLeague("esp.copa_del_rey", "Copa del Rey"),
        EspnLeague("esp.super_cup", "Spanish Super Cup"),
        EspnLeague("ita.coppa_italia", "Coppa Italia"),
        EspnLeague("ita.super_cup", "Italian Super Cup"),
        EspnLeague("ger.super_cup", "German Super Cup"),
        EspnLeague("fra.super_cup", "French Super Cup"),
        EspnLeague("uefa.super_cup", "UEFA Super Cup"),
        EspnLeague("ned.supercup", "Dutch Super Cup")
    )
    fun nameOf(slug: String) = all.firstOrNull {
        it.slug.equals(slug, ignoreCase = true)
    }?.name ?: slug
}
