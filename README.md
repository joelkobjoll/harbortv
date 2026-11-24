# Harbor TV

[![Android](https://github.com/joelkobjoll/harbortv/actions/workflows/android.yml/badge.svg)](https://github.com/joelkobjoll/harbortv/actions/workflows/android.yml)

Harbor TV is an IPTV client for Android phones, Android TV and Fire TV Stick (Fire OS 5+, Android 5.1 / API 22 and up). It talks to Stalker/MAG/Ministra-compatible portals and plays live TV, catch-up, movies and series with VideoLAN's LibVLC or Media3 ExoPlayer.

**Download:** <https://joelkobjoll.github.io/harbortv/HarborTV.apk> · **Update feed:** <https://joelkobjoll.github.io/harbortv/update.json> · **User guide:** [Wiki](https://github.com/joelkobjoll/harbortv/wiki)

## Features

- Live TV with channel categories, inline preview and full-screen playback
- Channel guide (EPG) with catch-up / replay where the portal supports it
- Movies and series catalogue with artwork, search, seasons and episodes
- Optional YouTube trailers (needs your own YouTube Data API key)
- Multiple saved portals, D-pad / remote first UI
- Self-update from a signed HTTPS feed with SHA-256 and signature verification

## Quick start (users)

1. In your IPTV panel, enable the Stalker/MAG portal and add a device with a MAC address and a package with at least one live channel.
2. Install `HarborTV.apk` (on Fire TV use the *Downloader* app with the link above, or `adb install -r HarborTV.apk`).
3. Open Harbor TV, enter the portal URL (ending in `/stalker_portal/c/`, `/server/load.php` or `/portal.php`) and the same MAC, then select **Connect**.

Full instructions, remote-control shortcuts and troubleshooting live in the [wiki](https://github.com/joelkobjoll/harbortv/wiki).

## Development

Requirements: JDK 17, Android SDK Platform 35. The Gradle wrapper (8.9) is committed, so no local Gradle install is needed.

```bash
./gradlew :app:assembleDebug        # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease      # release APK (debug-signed unless signing env vars are set)
./gradlew :app:lintRelease          # Android lint
```

Install on a device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

| Path | What it is |
| --- | --- |
| `app/src/main/java/com/harbortv/client/` | All app code (see [docs/architecture.md](docs/architecture.md)) |
| `gradle/libs.versions.toml` | Dependency and plugin versions |
| `gradle.properties` | `VERSION_CODE`, `VERSION_NAME`, default `UPDATE_FEED_URL` |
| `scripts/prepare-update-feed.sh` | Turns a signed APK into the update feed folder |
| `.github/workflows/android.yml` | CI: build, lint, publish feed, create release |
| `docs/` | Architecture, releasing, signing, code review notes |

## Releasing

Bump `VERSION_CODE` (and `VERSION_NAME`) in `gradle.properties` and merge to `main`. CI builds a signed release APK, publishes it with `update.json` to GitHub Pages and creates a `v<VERSION_NAME>` GitHub Release. Boxes pick it up on their next launch. Details: [docs/releasing.md](docs/releasing.md).

## Licenses

Harbor TV links to VideoLAN's unmodified LibVLC 3.6.5 (LGPL-2.1). Notices, the license text and source links are in `app/src/main/assets/licenses/`. The app can be rebuilt against a replacement library by changing `gradle/libs.versions.toml`.
