#!/usr/bin/env bash
# Builds the public update feed folder from a signed APK.
#
#   scripts/prepare-update-feed.sh <signed.apk> <output-dir>
#
# Writes into <output-dir>:
#   HarborTV-<versionCode>-<sha12>.apk  versioned file referenced by update.json
#   HarborTV.apk                        stable link for first-time installs
#   update.json                         feed read by AppUpdater (written last)
#   index.html                          small download page
set -euo pipefail

apk="${1:?usage: $0 <signed.apk> <output-dir>}"
out="${2:?usage: $0 <signed.apk> <output-dir>}"
root="$(cd "$(dirname "$0")/.." && pwd)"

prop() { grep -E "^$1=" "$root/gradle.properties" | head -1 | cut -d= -f2-; }
version_code="$(prop VERSION_CODE)"
version_name="$(prop VERSION_NAME)"
min_sdk="$(grep -E 'minSdk [0-9]+' "$root/app/build.gradle" | grep -oE '[0-9]+' | head -1)"

if command -v sha256sum >/dev/null; then hash="$(sha256sum "$apk" | cut -d' ' -f1)"
else hash="$(shasum -a 256 "$apk" | cut -d' ' -f1)"; fi
name="HarborTV-${version_code}-${hash:0:12}.apk"

mkdir -p "$out"
cp "$apk" "$out/$name"
cp "$apk" "$out/HarborTV.apk"

cat > "$out/index.html" <<HTML
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Harbor TV</title>
<style>body{font-family:system-ui,sans-serif;background:#101A26;color:#e8eef5;max-width:40rem;margin:4rem auto;padding:0 1rem}a{color:#64E4C4}code{background:#1c2a3a;padding:.1rem .3rem;border-radius:4px}</style>
</head><body>
<h1>Harbor TV ${version_name}</h1>
<p><a href="HarborTV.apk">Download HarborTV.apk</a> (build ${version_code})</p>
<p>Update feed for the app: <code>update.json</code> in this folder.</p>
<p>SHA-256: <code>${hash}</code></p>
</body></html>
HTML

# update.json goes last so boxes never see a feed pointing at a missing APK.
cat > "$out/update.json.tmp" <<JSON
{
  "versionCode": ${version_code},
  "versionName": "${version_name}",
  "minSdk": ${min_sdk},
  "apk": "${name}",
  "sha256": "${hash}"
}
JSON
mv "$out/update.json.tmp" "$out/update.json"
cat "$out/update.json"
