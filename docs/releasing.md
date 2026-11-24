# Releasing

## TL;DR

1. Bump `VERSION_CODE` (+1) and `VERSION_NAME` in `gradle.properties`.
2. Add a `CHANGELOG.md` entry.
3. Merge to `main`.

CI then:
- builds `app-release.apk` signed with the release key,
- runs `scripts/prepare-update-feed.sh` to create `HarborTV.apk`, `HarborTV-<code>-<sha12>.apk`, `update.json` and `index.html`,
- deploys that folder to GitHub Pages: <https://joelkobjoll.github.io/harbortv/>,
- creates GitHub Release `v<VERSION_NAME>` with the APK attached (once per version).

Boxes check the feed on launch and offer the update. Pushes to `main` that do not bump `VERSION_CODE` redeploy the same version, so boxes ignore them.

## URLs

| Use | URL |
| --- | --- |
| First install (Downloader app, browser) | `https://joelkobjoll.github.io/harbortv/HarborTV.apk` |
| Update feed (set in app, default for new installs) | `https://joelkobjoll.github.io/harbortv/update.json` |

## Signing

Release builds read the key from environment variables, which CI fills from repository secrets:

| Secret | Content |
| --- | --- |
| `HARBOR_KEYSTORE_BASE64` | `base64` of the `.jks` file |
| `HARBOR_KEYSTORE_PASSWORD` | keystore password |
| `HARBOR_KEY_ALIAS` | key alias (`harbortv`) |
| `HARBOR_KEY_PASSWORD` | key password |

If the secrets are missing (for example on forks), CI builds with the debug key and does **not** publish.

**Back up the keystore offline.** An update installs only if it is signed with the same certificate as the installed app. If the key is lost, every box has to uninstall and reinstall.

To rotate or recreate the secrets:

```bash
gh secret set HARBOR_KEYSTORE_BASE64 < <(base64 -i harbortv-release.jks)
gh secret set HARBOR_KEYSTORE_PASSWORD
gh secret set HARBOR_KEY_ALIAS --body harbortv
gh secret set HARBOR_KEY_PASSWORD
```

## Signing history

| Builds | Certificate | Notes |
| --- | --- | --- |
| up to 1.45 (46) | `CN=Android Debug` (original build machine), SHA-256 `27:EE:1D:1F:…:94:81` | Not available. These boxes cannot auto-update. |
| from the first CI build | `CN=Harbor TV` release key, SHA-256 `70:E6:AA:81:…:FE:AC` (in GitHub secrets) | All future updates. |

### Moving a box from 1.45 to the CI build

Android refuses to install over an app signed with a different key, so this is a one-time manual step per box:

1. Uninstall Harbor TV (Settings > Applications > Harbor TV > Uninstall). Saved portals are lost; note the portal URL and MAC first.
2. Install `https://joelkobjoll.github.io/harbortv/HarborTV.apk` (Fire TV: Downloader app).
3. Enter the portal again. The update feed is already set, so future versions arrive automatically.

## Manual release (fallback)

```bash
HARBOR_KEYSTORE_FILE=/path/harbortv-release.jks HARBOR_KEYSTORE_PASSWORD=... \
HARBOR_KEY_ALIAS=harbortv HARBOR_KEY_PASSWORD=... ./gradlew :app:assembleRelease
scripts/prepare-update-feed.sh app/build/outputs/apk/release/app-release.apk update-feed
```

Then host the `update-feed/` folder on any HTTPS server that serves files with a direct `200` (no redirects). Upload `update.json` last. `scripts/Publish-HarborUpdate.ps1` is the original Windows equivalent.
