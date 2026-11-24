# Code review (v1.45, November 2026)

Review of the source package as received. Overall the app is small, dependency-light and careful in the places that matter most (update verification, response size limits, input validation). The main risks are maintainability and the release process, not correctness.

## Strengths

- **Update path is well hardened.** HTTPS-only feed, SHA-256 check, package/version/certificate comparison before install, 64 KB feed and 250 MB APK caps, no redirect following, `FileProvider` with a narrow `cache/updates/` path.
- **Defensive networking.** Timeouts on every connection, size limits on portal, YouTube and poster responses, URL validation before use.
- **TV-friendly.** Leanback launcher + banner, D-pad navigation throughout, touchscreen optional, works down to API 22.
- **License compliance** for LibVLC is documented and the library is unmodified.

## Issues and recommendations

| # | Severity | Finding | Recommendation | Status |
| --- | --- | --- | --- | --- |
| 1 | High | Releases were debug builds signed with a machine-local debug key that is not available. Debug builds are also `debuggable`, so anyone with ADB can read app data (portal MAC, serial, YouTube key). | Release build type signed from CI secrets. | Done in this repo. Boxes reinstall once. |
| 2 | High | No version control, no CI, no reproducible build (no Gradle wrapper). | Repo, wrapper, CI, Pages feed. | Done. |
| 3 | Medium | `FileProvider` (`androidx.core`) is only on the classpath transitively through Media3. A Media3 change could break the updater at compile time. | Declare `androidx.core` explicitly. | Done. |
| 4 | Medium | `MainActivity` is ~1,800 lines and builds all UI in code, mixing navigation, networking callbacks and rendering. | Split by screen (setup, live, guide, catalogue) into separate controllers or activities as features are added. | Open. |
| 5 | Medium | No tests. `Portal` response parsing and URL handling are pure logic and easy to unit test. | Extract and add JUnit tests; run them in CI. | Open. |
| 6 | Low | APK is ~95 MB because `libvlc-all` ships x86/x86_64 native libraries that TV boxes and phones do not need. | `ndk { abiFilters 'armeabi-v7a', 'arm64-v8a' }` would roughly halve the size. Test on Fire TV first. | Open. |
| 7 | Low | `GET_SIGNATURES` is deprecated and does not handle APK key rotation. | Use `GET_SIGNING_CERTIFICATES` on API 28+ if key rotation is ever needed. | Open. |
| 8 | Low | `targetSdk 34`. Not on Google Play, so no store deadline, but newer Android TV releases tighten behaviour for old targets. | Move to 35 with a device test pass. | Open. |
| 9 | Info | `usesCleartextTraffic="true"` globally. | Needed for legacy portals; could be narrowed with a network security config if portals are known. | Accepted. |
| 10 | Info | No default update feed, so every box had to be configured by hand. | Feed URL baked in via `BuildConfig`, still editable. | Done. |
