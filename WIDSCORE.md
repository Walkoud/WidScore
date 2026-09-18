# WidScore — widget Android scores de match (v2)

But : app Android WidScore affichant scores foot en widgets home-screen,
copie système gadget Football de Palisades (`C:\Users\volka\Desktop\Palisades`,
`Palisades.Application/Plugins/FootballPlugin.cs` + `Palisades.Application/Services/EspnService.cs`).

## Copié de Palisades
- API ESPN sans clé : `https://cdn.espn.com/core/soccer/scoreboard?league={slug}&xhr=1`
  (`content.sbData.events[]` → `competitions[0]` → `state` pre/in/post,
  `displayClock`, `competitors[]`, nom ligue via liste curée).
- Backfill terminés : `site.web.api/.../soccer/{league}/teams/{teamId}/schedule` (30 min).
- Directory monde : `sports.core.api.espn.com/.../leagues/{slug}` → `teams.$ref`
  → collection `limit=100` + pages → team details (id/displayName/abbreviation/logos),
  cache disque 30j `football_rosters.json` (même forme que Palisades),
  abonnées d'abord puis curated, recherche accents-stripés + alias FR.
- Favoris équipes (`id/name/leagueSlug`) + ligues (`kind=league`) ; équipe suivie
  auto-abonne sa ligue (copie `SetFavorite`). Tri live > à venir > terminés.
- Rétention terminés kickoff + 115 min, `finishedHours` (0 = masquer).
- Refresh 1–60 min (défaut 10) ; WorkManager 15 min mini + one-shot ⟳/prefs.

## Widgets (scrollables, 2x2 redimensionnables, coins ronds 20dp)
- **Structure** : carte rounded + header fixe + `ListView` scrollable
  (`MatchListService` + factory, `notifyAppWidgetViewDataChanged`),
  dates groupées centrées ("Today" / "Tomorrow, Fri 11.09."), sections
  Finished/Upcoming, clic item → app ou Google (fill-in + template).
- **Dark** (spec card/scroll) : fond `#121212`, header `#9F9FFF` top-rounded
  (logo ⚽ blanc sur carré `#212121`, heure MAJ blanche 12sp centrée, refresh
  40dp sombre — sans notif/corbeille), cartes match `#1E1E1E` 84dp
  (blasons + heure/score gras centrés, watermark ⚽, codes 3 lettres dessous).
- **Classic** : header `#1E1F22` top-rounded (titre + live + heure + refresh 40dp),
  lignes statut + ligue + **codes 3 lettres** + crests + score gras.

## App (EN par défaut + FR)
- `values/strings.xml` anglais, `values-fr/` français ; switch EN/FR dans l'app
  (`AppCompatDelegate` per-app locale) ; widgets localisés via contexte localisé.
- Material3 cards : Ligues (filtre, **rien coché par défaut**), recherche auto
  (debounce 400 ms, sans bouton) sur directory monde + ligues, Favoris en chips
  supprimables, Réglages (refresh, max, terminés, position, clic, crests, headers).
- Warm directory au lancement avec progression (`Loading world teams… n/total`).

## Build cloud
`.github/workflows/build.yml` : push main → `assembleDebug` (Java 17, Gradle 8.7,
SDK préinstallé runner) → artifact `WidScore-debug-apk` dans onglet Actions.

## Fichiers
```
settings.gradle.kts / build.gradle.kts / gradle.properties
app/build.gradle.kts / AndroidManifest.xml (+ MatchListService BIND_REMOTEVIEWS)
MainActivity.kt / data/Models.kt Prefs.kt (cache matchs) Lang.kt RosterStore.kt
  CuratedLeagues.kt EspnApi.kt (+getRaw)
widget/Providers.kt MatchListService.kt (+CrestCache) WidgetUpdateWorker.kt WidgetRenderer.kt
res/xml/*_info.xml (2x2, resize) / layout/widget_{classic,dark}.xml (ListView)
  widget_item_{classic,dark}.xml widget_date_header.xml activity_main.xml (Material3)
  drawable/widget_bg_*.xml header_*.xml match_card_dark.xml logo_box.xml
  values/strings.xml values-fr/strings.xml colors.xml themes.xml
```
