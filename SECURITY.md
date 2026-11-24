# Security

Please report vulnerabilities privately through GitHub: **Security > Report a vulnerability** on this repository, or contact the maintainer directly. Do not open a public issue.

## How updates are protected

- The feed and APK must be served over HTTPS (private IPv4 addresses are allowed only for local testing).
- The APK's SHA-256 must match `update.json`.
- The downloaded APK must have the same package name, the advertised `versionCode`, a higher `versionCode` than the installed app, and the **same signing certificate** as the installed app. Android enforces the certificate check again at install time.

The release keystore only exists as encrypted GitHub Actions secrets plus an offline backup held by the maintainer. It is never committed.

## Known trade-offs

- `usesCleartextTraffic="true"`: many IPTV portals and stream servers only speak HTTP.
- Portal MAC/serial and the optional YouTube API key are stored in app-private SharedPreferences (`allowBackup="false"`).
