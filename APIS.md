# WidScore — APIs : requêtes, limites, manques

État réel vérifié par sondes directes (sept. 2026). Les clés ne sont JAMAIS dans le repo.

## 1. ESPN (sans clé, source principale)

UA utilisé : `Mozilla/5.0 (Linux; Android 14) WidScore/1.0`. Throttle interne 150ms.
CORS navigateur : `Access-Control-Allow-Origin: *` partout (debug.html marche en direct).

### 1a. Scoreboard CDN (live + fenêtre courante)
```
GET https://cdn.espn.com/core/soccer/scoreboard?league={slug}&xhr=1
GET https://cdn.espn.com/core/soccer/scoreboard?league={slug}&dates={YYYYMMDD}&xhr=1
```
- `content.sbData.events[]` → `competitions[0]` : `date`, `status.type.state`
  (`pre`/`in`/`post`), `status.displayClock`, `status.type.shortDetail`,
  `competitors[]` (`homeAway`, `team{id,displayName,abbreviation,logo}`, `score`
  string OU `{displayValue}`).
- Nom de ligue résolu en local (liste curée, le CDN ne donne que le calendrier).
- `dates=` : scoreboard d'un jour (sweep 6j passés en fallback).
- Fenêtre étroite constatée : ~1-2 matchs (ex. tur.1 = 1 seul, esp.1 = 2 post).
- Pas de rate limit documenté ; 429 backoffé.

### 1b. Team schedule / summary (backfill terminés + détails)
```
GET https://site.web.api.espn.com/apis/site/v2/sports/soccer/{league}/teams/{team}/schedule
GET https://site.api.espn.com/apis/site/v2/sports/soccer/{league}/teams/{team}/schedule   (fallback)
GET https://site.web.api.espn.com/apis/site/v2/sports/soccer/{league}/summary?event={id}  (détails : buts/cartons/stats)
```
- Schedule = passés uniquement en ce moment (0 upcoming constaté) ; crests absents
  → backfillés depuis le directory monde (comme Palisades).
- `site.api` (sans `web`) est bloqué Akamai 403 pour les clients non-navigateurs
  (vérifié : `Access Denied` edgesuite). `site.web.api` passe.

### 1c. sports.core.api (directory monde + fixtures saison)
```
GET .../v2/sports/soccer/leagues?limit=200&lang=en&region=us(&page=N)   # ~200 slugs, pageCount/pageIndex
GET .../leagues/{slug}?lang=en&region=us                               # -> teams.$ref, logos[] (préférer rel "dark")
GET {teams.$ref}?limit=100(&page=N)                                    # items[].$ref
GET {team.$ref}                        # id, displayName, abbreviation, logos[0].href
GET .../leagues/{lg}/seasons/{AAAA}/teams/{id}/events?limit=100        # fixtures TOUTE saison (passé+futur)
GET {event.$ref}                       # id, date, name ("X at Y" / "X vs Y")
GET .../leagues/{lg}/teams/{id}/seasons                                # saisons d'une équipe (limité à la ligue)
```
- Saison `AAAA` : année de début (août-mai) : si mois >= juillet → année courante sinon année-1.
- `name` : "Domicile at Extérieur" (US "at" = away @ home) ou "A vs B".
- Fixtures : pas de competitors inline (que date+nom) → crests absents, ids adverses
  absents → le côté suivi est taggé par nom, l'autre reste sans id.
- Coût : ~20 appels/ligue (rosters), ~8-40 appels/équipe/ligue (fixtures).
  Caches : rosters 30j (`football_rosters.json`, même forme que Palisades),
  liste ligues 30j (`football_leagues.json`), fixtures 24h (`football_team_events.json`).
- Équipes femmes : slugs `.w.` / `ww` / `nwsl` / `femenina` / `fifa.w.`… → suffixe " - Women".

### Ligues (slugs ESPN utiles, vérifiés)
`eng.1 eng.2 esp.1 esp.2 ita.1 ita.2 ger.1 ger.2 fra.1 ned.1 por.1 tur.1 usa.1 mex.1
bra.1 arg.1 chn.1 jpn.1 aus.1 sco.1 bel.1 aut.1 den.1 nor.1 swe.1`
Coupes : `uefa.champions uefa.europa uefa.europa.conf uefa.nations uefa.euro uefa.super_cup
fifa.world fifa.cwc fifa.friendly conmebol.america eng.fa eng.charity esp.copa_del_rey
esp.super_cup ita.coppa_italia ita.super_cup ger.super_cup ger.dfb_pokal fra.super_cup
fra.coupe_de_france ned.supercup club.friendly esp.joan_gamper`

### MANQUES ESPN
- Fenêtres CDN étroites (pas d'à-venir lointains) ; schedules actuellement post-only.
- Pas de mapping équipe→ligues natif → découvert par nous (rosters + sondes team-events).
- `site.api` 403 Akamai (desktop ET mobile non-navigateur).

## 2. football-data.org v4 (clé user, 2e source)

- Clé gratuite : https://www.football-data.org/client/register → header `X-Auth-Token`.
- Doc : fichier local `football-data.org - API Quickstart.html` (ignoré git).
- Throttle app : 1 appel / 6,1s (**10 req/min**, échec au-delà).
- Endpoints utilisés :
```
GET https://api.football-data.org/v4/competitions                       # test clé
GET https://api.football-data.org/v4/competitions/{CODE}/matches        # saison complète (380 matchs PD vérifiés)
```
- Match : `id`, `utcDate`, `status` (`SCHEDULED TIMED POSTPONED`→pre,
  `IN_PLAY PAUSED`→in, `FINISHED AWARDED`→post), `homeTeam/awayTeam`
  (`{id,name,shortName,tla,crest}`), `score.fullTime{home,away}` (null si à venir).
- Mapping ESPN slug → code (tier gratuit UNIQUEMENT) :
  `eng.1→PL eng.2→ELC esp.1→PD ita.1→SA ger.1→BL1 fra.1→FL1 ned.1→DED por.1→PPL
  bra.1→BSA uefa.champions→CL uefa.euro→EC fifa.world→WC`
- Favoris liés par nom normalisé (ids FD ≠ ids ESPN).
- Ids app : `fd-{id}` (dedup cross-sources par noms flous).

### MANQUES FD
- **Pas de Süper Lig** (tur.1) ni coupes nationales hors top ligues → Besiktas non couvert.
- Pas de minute en live (clock = "LIVE").
- 10 req/min → 8 compétitions/run max, 1er sync long (~1 min).

## 3. sports.bzzoiro.com v2 (token user, 3e source)

- Token : inscription sur https://sports.bzzoiro.com/ → header `Authorization: Token ...`.
- Throttle app : 400ms (pas de quota publié ; hot endpoints cachés ~5s côté edge).
- Doc locale : `sports.bzzoiro.com.txt` (ignoré git). Référence OpenAPI : `/openapi.json`.
- Endpoints utilisés :
```
GET https://sports.bzzoiro.com/api/v2/coverage/?sport=football   # sans token : in_season, next_7d/30d, live_now
GET /api/v2/leagues/?limit=200                                   # 88 ligues {id,name,country}
GET /api/v2/teams/?league_id={id}&limit=200(&offset=N)           # {id,name,short_name,country} (8474 équipes)
GET /api/v2/events/?team_id={id}&date_from={AAAA-MM-JJ}&date_to={...}&limit=200  # passé+futur, cross-ligues
GET /api/v2/events/live/?limit=200                               # live du moment
```
- Event : `id`, `league_id`, `home_team_id`, `home_team`, `away_team_id`, `away_team`,
  `event_date` (ISO UTC), `status` (`notstarted`→pre, `finished`→post,
  `postponed`→pre, `canceled/cancelled`→ignoré, live-like→in),
  `current_minute` (clock), `home_score/away_score` (null si à venir).
- Filtres vérifiés : `league_id`, `team_id`, `date_from/date_to`, `limit/offset`
  (max 200). `?search=` sur /teams/ → 400 (ne pas utiliser).
- Mapping ligues bzzoiro → slug ESPN (dedup cross-sources) :
  `1→eng.1 12→eng.2 39→eng.fa 3→esp.1 38→esp.2 41→esp.copa_del_rey 4→ita.1
  42→ita.coppa_italia 5→ger.1 94→ger.2 43→ger.dfb_pokal 6→fra.1 44→fra.coupe_de_france
  10→ned.1 2→por.1 7→uefa.champions 8→uefa.europa 83→uefa.europa.conf 90→uefa.super_cup
  64→uefa.nations 66→uefa.euro 11→tur.1 9→bra.1 27→fifa.world 79/31→fifa.friendly
  13→sco.1 49→jpn.1 18→usa.1` ; sinon `bz-{id}`.
- Favoris résolus par nom normalisé (accents strippés, stop-words fc/cf/sc/jk/fk/spor… ;
  "Beşiktaş JK" = "besiktas"). Directory par ligue caché 30j (`football_bz_teams.json`),
  noms ligues cachés 30j (`football_bz_leagues.json`).
- Fenêtre par équipe : -4j / +35j. Ids app : `bz-{id}`.
- Vérifié : Besiktas id 196 (67 équipes tur.1) ; event 4-1 Marseille (L8) + 3-0 Erzurum (L11)
  + prochain Amed 20/09.

### MANQUES BZ
- **Pas d'écussons** (teams = id/name/short_name/country) → backfillés depuis l'index
  ESPN (nom exact ou abbr exacte) dans le worker, log `LOGOS +N`.
- Pas de minute hors live ; statuts live exacts à confirmer (live_now=0 lors des sondes).
- Noms accentués parfois approximatifs côté API.

## 4. Pipeline app (ordre = priorité dedup)

1. Scoreboards ESPN (ligues abonnées + toutes ligues des suivis : directory + legacy + discovery team-events).
   + mois M/M+1/M+2 via site.web.api `?dates=YYYYMM` (cache disque 12h, repli périmé offline).
2. Schedules ESPN par équipe×ligue (+ fallback site.api, puis sweep CDN 6j ; cache disque 30min).
   Runs single-flight + rattrapage unique (plus de file d'attente) ; réglages
   cosmétiques = re-rendu sans réseau ; onUpdate launchers respecte la cadence.
3. Fixtures saison core.api par équipe×ligue (cache 24h).
4. football-data.org (si clé, ≤8 compétitions, 6,1s/appel).
5. bzzoiro (si token, par équipe suivie).
6. Backfill logos ESPN → dedup v3 (même jour+mêmes côtés flous+même ligue OU même score ;
   puis même ligue+côtés même si jours ≠ ; live > score > ESPN > premier ; aller/retour gardés).
7. Filtre favoris + rétention terminés (kickoff+115min) + tri live>à venir>terminés + max.
8. Cache prefs → headers widgets → `notifyAppWidgetViewDataChanged`.

## 5. Interrupteurs (carte Sources, Settings)

`useEspn / useFdApi / useBzApi` (défaut ON). ESPN off = que FD/BZ.
État reflété dans la carte sync (`... off`).

## 6. Combo conseillé (vérifié)

- **Tout ON** (défaut) : ESPN (live+crests) + BZ (couverture 88 ligues, passé/futur) + FD (saison top ligues).
- 1 seule clé : **bzzoiro** (Süper Lig + tout le reste).
- Sans clé : ESPN seul (correct, upcoming courts).
