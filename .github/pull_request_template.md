## What and why

## How I tested
- Device(s):
- Portal type (Stalker / Ministra):
- Streams (HLS / MPEG-TS, codecs):

## Checklist
- [ ] Builds and lint passes (`./gradlew :app:assembleDebug :app:lintRelease`)
- [ ] Works with D-pad / remote only
- [ ] No network or disk I/O on the main thread
- [ ] `VERSION_CODE` bumped **only** if this should ship to users, with a `CHANGELOG.md` entry
