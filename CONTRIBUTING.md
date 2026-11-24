# Contributing

1. Create a branch from `main`: `feature/<short-name>` or `fix/<short-name>`.
2. Make your change. Read [AGENTS.md](AGENTS.md) for the code map and house rules (TV-first UI, `minSdk 22`, no I/O on the main thread).
3. Run `./gradlew :app:assembleDebug :app:lintRelease`.
4. Test on a device: ideally a Fire TV Stick or Android TV box plus a phone. Note the portal type (Stalker / Ministra) and stream type (HLS / MPEG-TS) you used.
5. Open a pull request using the template. CI builds the APK for every PR; download it from the run's *Artifacts* to test.
6. Do **not** bump `VERSION_CODE` unless the PR is meant to ship to users. See [docs/releasing.md](docs/releasing.md).

Commit messages: short imperative subject (`Fix EPG paging on Ministra portals`), body explaining *why* when it is not obvious.
