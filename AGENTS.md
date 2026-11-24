# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Copilot, etc.) and humans working in this repo.

## Project in one paragraph

Harbor TV is a single-module Android app (`:app`, package `com.harbortv.client`) written in plain Java 17 with no AndroidX UI toolkit: views are built in code, there are no XML layouts, no fragments, no DI and no Kotlin. It targets old hardware (Fire TV Stick, API 22) and TV remotes, so D-pad focus and low memory matter more than modern APIs. Playback is LibVLC (default) or Media3 ExoPlayer. The portal protocol is Stalker/MAG/Ministra over HTTP(S) JSON.

## Commands

```bash
./gradlew :app:assembleDebug      # build
./gradlew :app:assembleRelease    # release build (debug-signed locally)
./gradlew :app:lintRelease        # lint; report in app/build/reports/
```

There are no unit tests yet. If you add logic that does not need Android (URL parsing, JSON decoding in `Portal`, feed validation in `AppUpdater`), extract it into a plain class and add JUnit tests under `app/src/test/java/`.

## Code map

- `MainActivity` (~1.8k lines): setup screen, saved portals, home tiles, live channel list, EPG / catch-up, movies and series browsing, search. UI is built programmatically.
- `Portal`: Stalker/Ministra client. Handshake, profile, channels, categories, catalogue paging, EPG, link creation (`create_link`), redirects. Network calls are blocking; call them off the main thread.
- `PlaybackSurface`: wraps LibVLC and ExoPlayer behind one API (play, seek, pause, release).
- `PlayerActivity`: full-screen player with seek controls.
- `TrailerActivity`: YouTube lookup (user-supplied API key) and WebView embed.
- `PosterLoader`: small LRU bitmap cache with a 2-thread pool.
- `AppUpdater`: reads `update.json`, downloads the APK, checks SHA-256, package name, versionCode and signing certificate, then hands it to the system installer through `FileProvider`.

## Rules

- **Never commit signing material** (`*.jks`, `*.keystore`, passwords). Release signing comes only from CI secrets (`HARBOR_KEYSTORE_BASE64`, `HARBOR_KEYSTORE_PASSWORD`, `HARBOR_KEY_ALIAS`, `HARBOR_KEY_PASSWORD`). Losing or changing the key means every box must uninstall and reinstall.
- **Version bumps ship to users.** Changing `VERSION_CODE` in `gradle.properties` and merging to `main` publishes an update to every installed box. Only bump it on purpose, always upward, and add a `CHANGELOG.md` entry.
- **Keep the update feed contract stable.** `update.json` fields (`versionCode`, `versionName`, `minSdk`, `apk`, `sha256`) are read by every installed version. Add fields, never rename or remove them. The updater does not follow redirects, so the feed and APK must be served with a direct `200` (GitHub Pages does this; GitHub Release download links do not).
- **Keep `minSdk 22`.** Fire TV Stick gen 1/2 run Fire OS 5 (API 22). Guard newer APIs with `Build.VERSION.SDK_INT` checks.
- **TV first.** Every new screen must be fully usable with D-pad + OK + Back. Check focus order and visible focus states.
- **Main thread.** No network or disk I/O on the UI thread; follow the existing executor + `runOnUiThread` pattern.
- **Cleartext HTTP** is allowed on purpose (legacy portals and stream servers). Do not disable it, and do not add new cleartext endpoints of our own.
- Keep dependencies in `gradle/libs.versions.toml`. LibVLC must stay the unmodified VideoLAN artifact (LGPL); update `app/src/main/assets/licenses/VideoLAN.txt` if its version changes.
- Match existing style: 4-space indent, `final` classes, package-private visibility by default, no new frameworks without discussion.

## Workflow

1. Branch from `main` (`feature/...`, `fix/...`).
2. Build and lint locally; test on a real TV device or an Android TV emulator when you touch UI or playback.
3. Open a PR. CI must be green. Describe how you tested (device, portal type, stream type).
4. To release, bump the version in the same PR or a follow-up one. See `docs/releasing.md`.
