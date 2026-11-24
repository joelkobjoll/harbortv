package com.harbortv.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TrailerActivity extends Activity {
    private static final String API_KEY = "youtubeTrailerApiKey";
    private final ExecutorService lookup = Executors.newSingleThreadExecutor();
    private WebView player;
    private TextView status;
    private Button find;
    private String movie;
    private String embed = "";

    static void open(Activity context, JSONObject movie) {
        String id = videoId(movie);
        String title = Portal.field(movie, "name", Portal.field(movie, "title", "Movie"));
        context.startActivity(new Intent(context, TrailerActivity.class)
                .putExtra("id", id).putExtra("title", title));
    }

    static String videoId(JSONObject movie) {
        for (String field : new String[] {"trailer_id", "youtube_id", "trailer", "trailer_url", "youtube_trailer"}) {
            String value = Portal.field(movie, field, "").trim();
            if (validId(value)) return value;
            if (value.isEmpty()) continue;
            Uri link = Uri.parse(value);
            if (!("https".equalsIgnoreCase(link.getScheme()) || "http".equalsIgnoreCase(link.getScheme()))) continue;
            String host = link.getHost();
            if (host == null) continue;
            host = host.toLowerCase(Locale.ROOT);
            String id = "";
            if ("youtu.be".equals(host)) {
                id = link.getLastPathSegment();
            } else if ("www.youtube.com".equals(host) || "youtube.com".equals(host)
                    || "m.youtube.com".equals(host) || "www.youtube-nocookie.com".equals(host)) {
                if ("/watch".equals(link.getPath())) id = link.getQueryParameter("v");
                else if (link.getPathSegments().size() == 2 && "embed".equals(link.getPathSegments().get(0))) {
                    id = link.getLastPathSegment();
                }
            }
            if (validId(id)) return id;
        }
        return "";
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{11}");
    }

    private void play(String id) {
        status.setVisibility(android.view.View.GONE);
        embed = "https://www.youtube.com/embed/" + id + "?autoplay=1";
        player.loadUrl(embed, Collections.singletonMap("Referer", "https://" + getPackageName() + "/"));
        player.requestFocus();
    }

    private SharedPreferences preferences() {
        return getSharedPreferences("trailers", MODE_PRIVATE);
    }

    private void editKey() {
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setHint("YouTube Data API key");
        key.setText(preferences().getString(API_KEY, ""));
        new AlertDialog.Builder(this)
                .setTitle("Automatic trailer lookup")
                .setMessage("Enter a Google API key with YouTube Data API v3 enabled.")
                .setView(key)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save & find", (dialog, button) -> {
                    String value = key.getText().toString().trim();
                    preferences().edit().putString(API_KEY, value).apply();
                    if (!value.isEmpty()) findTrailer();
                }).show();
    }

    private void findTrailer() {
        if (player == null || !find.isEnabled()) return;
        String key = preferences().getString(API_KEY, "");
        if (key.isEmpty()) {
            editKey();
            return;
        }
        find.setEnabled(false);
        showStatus("Finding an official trailer…");
        lookup.execute(() -> {
            try {
                String id = lookupTrailer(movie, key);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    find.setEnabled(true);
                    if (id.isEmpty()) showStatus("No matching trailer available for embedded playback.");
                    else play(id);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    find.setEnabled(true);
                    String reason = error.getMessage();
                    showStatus(error instanceof IOException && reason != null && reason.startsWith("YouTube ")
                            ? reason : "Trailer lookup failed. Check your connection and API key.");
                });
            }
        });
    }

    private void showStatus(String message) {
        status.setText(message);
        status.setVisibility(android.view.View.VISIBLE);
    }

    private static String lookupTrailer(String movie, String key) throws Exception {
        Uri query = Uri.parse("https://www.googleapis.com/youtube/v3/search").buildUpon()
                .appendQueryParameter("part", "snippet")
                .appendQueryParameter("type", "video")
                .appendQueryParameter("videoEmbeddable", "true")
                .appendQueryParameter("maxResults", "10")
                .appendQueryParameter("q", movie + " official trailer")
                .appendQueryParameter("key", key)
                .build();
        HttpURLConnection connection = (HttpURLConnection) new URL(query.toString()).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        connection.setInstanceFollowRedirects(false);
        try {
            int response = connection.getResponseCode();
            if (response == 400 || response == 401 || response == 403) {
                throw new IOException("YouTube rejected the lookup. Check your API key and search quota.");
            }
            if (response != 200) throw new IOException("YouTube lookup failed. Try again later.");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 256 * 1024) throw new IOException("YouTube search response is too large.");
                    output.write(buffer, 0, count);
                }
                return bestTrailer(movie, new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8)));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String bestTrailer(String movie, JSONObject response) {
        JSONArray items = response.optJSONArray("items");
        String match = normalize(movie.replaceFirst("\\s*\\([0-9]{4}\\)\\s*$", ""));
        if (items == null || match.isEmpty()) return "";
        String bestId = "";
        int bestScore = -1;
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            JSONObject ref = item.optJSONObject("id");
            JSONObject snippet = item.optJSONObject("snippet");
            if (ref == null || snippet == null) continue;
            String id = ref.optString("videoId");
            String title = normalize(android.text.Html.fromHtml(snippet.optString("title")).toString());
            String tokens = " " + title + " ";
            if (!validId(id) || !tokens.contains(" trailer ") || !tokens.contains(" " + match + " ")
                    || tokens.contains(" fan made ") || tokens.contains(" concept ") || tokens.contains(" reaction ")
                    || tokens.contains(" fake trailer ")) continue;
            int score = tokens.contains(" official trailer ") ? 3 : 0;
            if (title.startsWith(match + " ")) score += 2;
            if (score > bestScore) {
                bestScore = score;
                bestId = id;
            }
        }
        return bestId;
    }

    private static String normalize(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String id = getIntent().getStringExtra("id");
        boolean hasVideo = validId(id);
        movie = getIntent().getStringExtra("title");
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout actions = new LinearLayout(this);
        layout.addView(actions);
        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(view -> finish());
        actions.addView(back);
        find = new Button(this);
        find.setText("Find trailer");
        find.setOnClickListener(view -> findTrailer());
        actions.addView(find);
        Button settings = new Button(this);
        settings.setText("Trailer settings");
        settings.setOnClickListener(view -> editKey());
        actions.addView(settings);
        status = new TextView(this);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        layout.addView(status);
        setContentView(layout);
        back.requestFocus();
        try {
            player = new WebView(this);
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, "The embedded player is unavailable on this box.", Toast.LENGTH_LONG).show();
            return;
        }
        player.getSettings().setJavaScriptEnabled(true);
        player.getSettings().setDomStorageEnabled(true);
        player.getSettings().setAllowFileAccess(false);
        player.getSettings().setAllowContentAccess(false);
        player.getSettings().setMediaPlaybackRequiresUserGesture(false);
        player.setFocusable(true);
        player.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() && shouldOverrideUrlLoading(view, request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !embed.equals(url);
            }
        });
        layout.addView(player, new LinearLayout.LayoutParams(-1, 0, 1));
        if (hasVideo) play(id);
        else findTrailer();
    }

    @Override
    protected void onPause() {
        if (player != null) player.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.onResume();
    }

    @Override
    protected void onDestroy() {
        lookup.shutdownNow();
        if (player != null) {
            ((LinearLayout) player.getParent()).removeView(player);
            player.destroy();
        }
        super.onDestroy();
    }
}
