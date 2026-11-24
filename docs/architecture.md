# Architecture

Single Android module, plain Java, no XML layouts. Everything lives in `app/src/main/java/com/harbortv/client/`.

```
MainActivity ──uses──> Portal ──HTTP JSON──> Stalker / Ministra portal
     │                    │
     │                    └─ create_link ──> stream URL
     ├──> PlaybackSurface (inline preview)  ── LibVLC | Media3 ExoPlayer
     ├──> PlayerActivity (full screen) ───────┘
     ├──> TrailerActivity ── YouTube Data API + WebView embed
     ├──> PosterLoader (LRU cache, 2 threads)
     └──> AppUpdater ── update.json + APK (GitHub Pages) ── system installer
```

## Portal protocol (`Portal`)

1. Normalise the URL (`httpUri`), discover the API endpoint (`/server/load.php` or `/portal.php`) and follow portal redirects (`PortalRedirect`).
2. `handshake` to get a token, then `get_profile` (classic Stalker) or the Ministra two-step profile with `prehash`/metrics.
3. Fetch channels (`get_all_channels`, `get_genres`, `get_ordered_list` per category).
4. VOD and series via `vod` / `series` catalogue calls (`get_categories`, `get_ordered_list`) with paging (`CatalogPage`).
5. EPG via `get_short_epg` / `get_simple_data_table`; catch-up via archive commands.
6. Playable URLs from `create_link` (`resolve`, `resolveMovie`, `resolveArchive`).

Requests carry a MAG-style user agent, MAC cookie, serial and device model. All calls are blocking; callers run them on executors and post results with `runOnUiThread`.

## Playback (`PlaybackSurface`)

LibVLC is the default engine (`libvlc-all`, all ABIs, which is why the APK is ~95 MB). Media3 ExoPlayer (with HLS) is the alternative, selectable in settings. `PlaybackSurface` hides the difference: `play`, `seekBy`, `seekTo`, `togglePause`, `release`, plus an error callback used to fall back to an alternate (replay) URL.

## State

`SharedPreferences`:
- `portal`: saved portal profiles (JSON), default portal, display size, playback engine, `updateFeed`.
- `trailers`: optional YouTube API key.

No database, no accounts, no analytics.

## Self-update (`AppUpdater`)

On launch (and from *Portal settings > Software updates*) it reads the feed URL (default from `BuildConfig.DEFAULT_UPDATE_FEED`). If `versionCode` is newer and `minSdk` fits, it offers a download, verifies SHA-256, package, version and signing certificate, stores the APK in `cache/updates/` and opens the installer via `FileProvider` (`${applicationId}.updates`). Redirects are not followed, by design.
