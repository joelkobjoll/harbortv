package com.harbortv.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppUpdater {
    private static final String FEED = "updateFeed";
    private static final long MAX_APK_BYTES = 250L * 1024 * 1024;
    private final Activity activity;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean busy;
    private boolean closed;

    AppUpdater(Activity activity, SharedPreferences preferences) {
        this.activity = activity;
        this.preferences = preferences;
    }

    void showSettings() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://example.com/update.json");
        input.setText(preferences.getString(FEED, BuildConfig.DEFAULT_UPDATE_FEED));
        new AlertDialog.Builder(activity)
                .setTitle("Software updates")
                .setMessage("Enter the update feed address. Leave it blank to turn off update checks.")
                .setView(input)
                .setPositiveButton("Save & check", (dialog, button) -> {
                    String value = input.getText().toString().trim();
                    try {
                        if (!value.isEmpty()) updateUri(value);
                        preferences.edit().putString(FEED, value).apply();
                        if (value.isEmpty()) message("Update checks turned off.");
                        else check(true);
                    } catch (IllegalArgumentException error) {
                        message(error.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void check(boolean manual) {
        String feed = preferences.getString(FEED, BuildConfig.DEFAULT_UPDATE_FEED);
        if (feed.isEmpty() || closed || busy) return;
        busy = true;
        worker.execute(() -> {
            try {
                URI source = updateUri(feed);
                JSONObject release = readFeed(source);
                int version = release.getInt("versionCode");
                if (version <= installed().versionCode) {
                    complete(() -> { if (manual) message("Harbor TV is up to date."); });
                    return;
                }
                int minSdk = release.optInt("minSdk", 22);
                if (Build.VERSION.SDK_INT < minSdk) {
                    complete(() -> { if (manual) message("This update needs a newer Android version."); });
                    return;
                }
                URI apk = updateUri(source.resolve(release.getString("apk")).toString());
                if ("https".equalsIgnoreCase(source.getScheme()) && !"https".equalsIgnoreCase(apk.getScheme())) {
                    throw new IOException("The update download must use HTTPS.");
                }
                String hash = release.getString("sha256").toLowerCase(Locale.ROOT);
                if (!hash.matches("[0-9a-f]{64}")) throw new IOException("The update checksum is missing or invalid.");
                String name = release.optString("versionName", Integer.toString(version));
                complete(() -> {
                    if (!feed.equals(preferences.getString(FEED, BuildConfig.DEFAULT_UPDATE_FEED))) return;
                    new AlertDialog.Builder(activity).setTitle("Harbor TV update available")
                            .setMessage("Version " + name + " is ready to download.")
                            .setPositiveButton("Download", (dialog, button) -> download(apk, hash, version))
                            .setNegativeButton("Later", null).show();
                });
            } catch (Exception error) {
                complete(() -> { if (manual) message("Could not check for updates. Check the feed address and connection."); });
            }
        });
    }

    private void download(URI url, String expectedHash, int version) {
        if (closed || busy) return;
        busy = true;
        message("Downloading Harbor TV update…");
        worker.execute(() -> {
            File directory = new File(activity.getCacheDir(), "updates");
            File partial = null;
            try {
                if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                    throw new IOException("Could not create update storage.");
                }
                partial = File.createTempFile("download-", ".apk", directory);
                downloadFile(url, partial, expectedHash);
                PackageInfo current = installed();
                PackageInfo update = activity.getPackageManager()
                        .getPackageArchiveInfo(partial.getAbsolutePath(), PackageManager.GET_SIGNATURES);
                if (update == null || !current.packageName.equals(update.packageName)
                        || update.versionCode != version || version <= current.versionCode
                        || current.signatures == null || current.signatures.length == 0
                        || !Arrays.equals(current.signatures, update.signatures)) {
                    throw new IOException("The APK is not a matching Harbor TV update.");
                }
                File apk = new File(directory, "harbor-" + version + ".apk");
                if (!partial.renameTo(apk)) throw new IOException("Could not stage the verified update.");
                complete(() -> openInstaller(apk));
            } catch (Exception error) {
                complete(() -> message("Could not download or verify the Harbor TV update."));
            } finally {
                if (partial != null && partial.isFile()) partial.delete();
            }
        });
    }

    private void openInstaller(File apk) {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            message("The box could not open its package installer.");
        }
    }

    private PackageInfo installed() throws PackageManager.NameNotFoundException {
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
    }

    private static JSONObject readFeed(URI uri) throws Exception {
        HttpURLConnection connection = open(uri);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 64 * 1024) throw new IOException("Update feed is too large.");
                output.write(buffer, 0, count);
            }
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadFile(URI uri, File apk, String hash) throws Exception {
        HttpURLConnection connection = open(uri);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(apk)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_APK_BYTES || Thread.currentThread().isInterrupted()) {
                    throw new IOException("Update download interrupted or too large.");
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        if (!hash.contentEquals(actual)) throw new IOException("Update checksum does not match.");
    }

    private static HttpURLConnection open(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(20_000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() == 200) return connection;
            throw new IOException("Update server did not return a file.");
        } catch (IOException error) {
            connection.disconnect();
            throw error;
        }
    }

    private static URI updateUri(String value) {
        URI uri = Portal.httpUri(value);
        String host = uri.getHost();
        if ("https".equalsIgnoreCase(uri.getScheme()) || host.matches(
                "(?:10\\.[0-9]{1,3}|192\\.168|172\\.(?:1[6-9]|2[0-9]|3[01]))(?:\\.[0-9]{1,3}){2}")) return uri;
        throw new IllegalArgumentException("Use HTTPS or a private IPv4 address for local testing.");
    }

    private void complete(Runnable action) {
        activity.runOnUiThread(() -> {
            busy = false;
            if (!closed && !activity.isFinishing()) action.run();
        });
    }

    private void message(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    void close() {
        closed = true;
        worker.shutdownNow();
    }
}
