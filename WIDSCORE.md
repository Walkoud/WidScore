# WidScore — widget Android scores de match

But : app Android WidScore affichant scores foot en widgets home-screen,
copie système gadget Football de Palisades (`C:\Users\volka\Desktop\Palisades`,
`Palisades.Application/Plugins/FootballPlugin.cs` + `Palisades.Application/Services/EspnService.cs`).

## Copié de Palisades
- API ESPN sans clé : `https://cdn.espn.com/core/soccer/scoreboard?league={slug}&xhr=1`
  - JSON : `content.sbData.events[]` → `competitions[0]` → `status.type.state`
    (`pre`/`in`/`post`), `displayClock`, `competitors[]` (`homeAway`, `team.id/displayName/abbreviation/logo`, `score`),
    `date`, `id`. Nom ligue résolu via liste curée locale (CDN ne donne que calendrier).
- Backfill terminés : `https://site.web.api.espn.com/apis/site/v2/sports/soccer/{league}/teams/{teamId}/schedule`
  (matchs terminés déjà sortis fenêtre CDN), cache 30 min.
- Détails match : `.../soccer/{league}/summary?event={eventId}` (buts/cartons/remplacements + stats).
- Directory monde : toutes ligues ESPN, cache 30j (ici : cache mémoire + SharedPreferences, pas de world-roster complet — recherche équipes via matchs fetchés + ligues curées).
- Ligues suivies par défaut : `eng.1 esp.1 ita.1 ger.1 fra.1 tur.1` (idem Palisades après migration).
- Favoris : équipes (`id`, `name`, `leagueSlug`) + ligues (`id=slug`, `kind=league`) — suivre ligue = tous ses matchs.
  Équipe suivie auto-abonne sa ligue (idem `SetFavorite` Palisades).
- Tri : live d'abord, puis à venir par date, terminés placés haut/bas selon réglage.
- Rétention terminés : `finishedHours` (0 = masquer aussitôt, estimation fin = kickoff + 115 min).
- Refresh : réglable 1–60 min (défaut 10) ; ralenti x5 jusqu'à 15 min si aucun live ni coup d'envoi < 30 min (idem `TuneTimer`).
  Sur Android : WorkManager périodique 15 min mini + refresh manuel bouton ⟳.
- Clic match : ouvre détails (ou recherche Google selon réglage `details`/`google`).
- Clic ☆/★ : follow/unfollow équipe/ligue directement.

## 2 styles (copie visuelle Palisades)
1. **Classic rows** (`ScoreWidgetClassic`) : lignes transparentes —
   statut (`● 67'` rouge `#FF5F56` live / `Today 21:00` gris / `date · FT` pâle),
   ligue + ☆/★, `Home abbr/crest vs Away`, score gras centré.
2. **Dark cards** (`ScoreWidgetDark`) : cartes `#18191C` radius 8dp —
   bandes date (`Today` / `ddd dd/MM/yyyy` gris `#8A8E96`), 3 colonnes
   (tri-code blanc bold 14sp + crest 28dp + ☆/★, centre score 16sp bold + `● clock`
   rouge live / `FT` gris, watermark ⚽ 8% opacité), footer ligue centrée.
   Accent `#7DD3FC`, texte `#F0F0F0`.

## Widgets Android
- Taille défaut **2x2** : `targetCellWidth/Height=2`, `minWidth/Height=110dp`,
  `minResizeWidth/Height=110dp`, `resizeMode="horizontal|vertical"`,
  `widgetCategory="home_screen"`, `description` FR.
- Providers : `ScoreWidgetClassicProvider`, `ScoreWidgetDarkProvider`
  (extends `AppWidgetProvider`, `onUpdate` → `WidgetUpdateWorker.enqueueOneShot`,
  bouton ⟳ → même worker).
- Rendu : `RemoteViews` + conteneur `LinearLayout` rempli par items construits
  en code (`WidgetRenderer`), max 8 matchs, crests chargés en bitmap synchrone
  dans worker (cache mémoire LRU).
- Redimension : layouts `flexibles` (scroll via `ListView`? non — `ScrollView`
  interdit en widget ; liste tronquée à N selon hauteur : `maxMatches` + estimation
  `minHeight` → 4/6/8 items).

## App (config)
- `MainActivity` : choix ligues suivies (multi-select depuis `CuratedLeagues`),
  recherche équipes (filtre sur matchs fetchés + favoris), liste favoris avec
  suppression, réglages : refresh, max matchs, crests on/off, finished hours,
  position terminés, thème, format date, action clic. Bouton refresh manuel.
- Stockage : `SharedPreferences "widscore"` JSON (mêmes champs que
  `FootballSettings` Palisades : leagues, teams[{id,name,kind,leagueSlug}],
  refreshMinutes, maxMatches, showCrests, finishedHours, finishedPosition,
  finishedTextColor, showFinishedHeader, showFinishedDates, cardTheme,
  matchClickAction, cardScale ignoré sur widget natif, dateFormat).
- Permissions : `INTERNET`, `ACCESS_NETWORK_STATE`. `minSdk 26`, `targetSdk 34`,
  Kotlin + WorkManager + org.json (pas de Retrofit/Gson pour rester léger).

## Build
Ouvrir `WidScore/` dans Android Studio (pas de SDK sur cette machine),
`Sync Gradle` puis `Run`. Widgets ajoutables via long-press launcher →
Widgets → WidScore Classic / WidScore Dark.

## Fichiers
```
settings.gradle.kts / build.gradle.kts / gradle.properties
app/build.gradle.kts / app/src/main/AndroidManifest.xml
MainActivity.kt (config favoris/ligues/réglages)
data/Models.kt, CuratedLeagues.kt, Prefs.kt, EspnApi.kt
widget/ScoreWidgetClassicProvider.kt, ScoreWidgetDarkProvider.kt,
       WidgetUpdateWorker.kt, WidgetRenderer.kt
res/xml/*_info.xml, res/layout/widget_*.xml + item_*.xml,
res/values/strings.xml colors.xml themes.xml
```
