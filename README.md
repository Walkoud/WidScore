# WidScore

*En français ? Voir [README.fr.md](README.fr.md).*

<img src="doc/medias/header_image_logo.jpg" alt="WidScore" width="600">

Live football scores on your home screen: 2 widgets (Classic + Dark), live, upcoming, recent finished. No account, no ads.

## Why?

The new SofaScore widget is ugly, bloated and inflexible — forced size, forced content, my teams buried in noise. So I built my own: only my teams, my layout, my sizes. Nothing else.

![Classic and Dark](doc/medias/two_widgets_preview.png)

## Install

1. Go to the [**Releases**](https://github.com/Walkoud/WidScore/releases) page, download the latest `WidScore-vX.Y.Z.apk`.
2. Open the file on your phone, allow installs, install it.
3. Add the widget: long-press the home screen → **Widgets** → **WidScore** (Classic or Dark).

## Get started in 3 steps

1. **Open the app** → Teams tab, search your teams (e.g. `Galatasaray`, `Real Madrid`) and star them as favorites.
2. **No need to subscribe to leagues**: a followed team automatically brings all its competitions (league + European cups...).
3. **Tap ⟳ once and wait ~1 minute**: the first load fetches the calendars, later ones are near-instant (cached).

## Sources: which one to enable?

| Source | Key? | Coverage | Notes |
|---|---|---|---|
| **ESPN** | No, works out of the box | Live + 3 months upcoming + finished | Enough in 90% of cases |
| **football-data.org** | Yes, free ([sign up](https://www.football-data.org/client/register)) | Full season of top leagues | 10 calls/min, **no Süper Lig** |
| **bzzoiro** | Yes ([sign up](https://sports.bzzoiro.com/)) | 88 leagues, past + future per team, **incl. Süper Lig** | Best ESPN companion |

Paste your key in the app (Settings), save: matches arrive on the next refresh.

## Do / don't

**Do:**
- Follow **teams** rather than leagues (more precise, fewer API calls).
- After changing favorites or keys: **one single** tap on ⟳, then let it finish.
- Appearance settings (size, colors, position, compact mode) apply **instantly**, no reload.
- Turn off a source you don't use: its matches leave the widget right away.

**Don't:**
- **Don't spam the ⟳ button**: requests are merged, but a full run takes 1–2 minutes. Tapping 5 times = waiting longer, not shorter.
- Don't wipe cache / reinstall to "force" things: it destroys cached calendars (12 h for months, 30 min for finished) and everything gets re-downloaded.
- Don't look for a match from 3 days ago: finished retention is configurable ("Keep finished"); at 0, finished matches are hidden.
- Don't panic over `FAIL http=-1` in the logs: that's the phone's network hiccuping, the app retries with the old cache meanwhile.

## Quick troubleshooting

- **Empty widget**: make sure you have at least one team or league favorited, then ⟳ once and wait.
- **A match is missing**: check it's in a covered competition (see table), and that its source is enabled with its key.
- **Scores not fresh**: ⟳ once. Auto-refresh runs every 15 min minimum (Android limit).
- **See what's going on**: in the app, the sync card + logs show every call (leagues OK, matches shown/hidden).

## Technical notes (for the curious)

- 100% cloud: every push to `main` builds the APK via GitHub Actions, every `v*` tag publishes a Release with the APK.
- API and pipeline details: see [APIS.md](APIS.md).
