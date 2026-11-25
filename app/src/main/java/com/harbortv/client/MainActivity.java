package com.harbortv.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.util.LruCache;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.text.ParsePosition;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final String DISPLAY_SIZE = "displaySize";
    private static final String REOPEN_PORTAL = "reopenPortalAfterDisplayChange";
    private static final int[] DISPLAY_SIZES = {100, 90, 80, 70};
    private static final int MINT = 0xff64e4c4;
    private static final int MUTED = 0xffbbcedc;
    private final ExecutorService requests = Executors.newSingleThreadExecutor();
    private final ExecutorService guideRequests = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PosterLoader posters = new PosterLoader();
    private final LruCache<Portal.Channel, GuidePreview> guideCache = new LruCache<>(64);
    private Runnable pendingGuide;
    private Future<?> guideRequest;
    private int previewVersion;
    private PlaybackSurface inlinePlayer;
    private TextView inlineCaption;
    private Portal.Channel inlineChannel;
    private String inlineStream;
    private boolean resumed;
    private SharedPreferences preferences;
    private AppUpdater updater;
    private LinearLayout content;
    private EditText portalName;
    private EditText address;
    private EditText mac;
    private EditText serial;
    private Spinner stbModel;
    private CheckBox defaultPortal;
    private JSONObject editingPortal;
    private Button connect;
    private TextView status;
    private ListView channelList;
    private int screenVersion;
    private Runnable backAction;
    private boolean openingStream;
    private boolean reopenPortalAfterDisplayChange;

    @Override
    protected void attachBaseContext(Context base) {
        // Fire OS can query the Activity's resources during a preference lookup.
        // At this point only the application context is fully attached.
        int size = base.getApplicationContext()
                .getSharedPreferences("portal", MODE_PRIVATE).getInt(DISPLAY_SIZE, 100);
        if (size >= 70 && size < 100) {
            Configuration override = new Configuration();
            override.densityDpi = Math.round(base.getResources().getDisplayMetrics().densityDpi * size / 100f);
            base = base.createConfigurationContext(override);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("portal", MODE_PRIVATE);
        updater = new AppUpdater(this, preferences);
        List<JSONObject> profiles = savedPortals();
        JSONObject startup = null;
        String defaultKey = preferences.getString("defaultPortal", "");
        boolean reopenPortal = state != null && state.getBoolean(REOPEN_PORTAL);
        String lastAddress = preferences.getString("address", "");
        String lastMac = preferences.getString("mac", "");
        for (JSONObject profile : profiles) {
            if (reopenPortal ? profile.optString("address").equals(lastAddress)
                    && profile.optString("mac").equalsIgnoreCase(lastMac)
                    : profileKey(profile).equals(defaultKey)) startup = profile;
        }
        if (startup == null && profiles.size() == 1) startup = profiles.get(0);
        if ((state == null || reopenPortal) && startup != null) {
            connectToSavedPortal(startup);
        } else if (profiles.size() > 1 && startup == null) {
            showSavedPortals();
        } else {
            showSetup();
        }
        if (state == null) updater.check(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(REOPEN_PORTAL, reopenPortalAfterDisplayChange);
    }

    private void showDisplaySize(boolean reopenPortal) {
        int current = preferences.getInt(DISPLAY_SIZE, 100);
        int selected = 0;
        for (int index = 0; index < DISPLAY_SIZES.length; index++) {
            if (DISPLAY_SIZES[index] == current) selected = index;
        }
        new AlertDialog.Builder(this)
                .setTitle("Display size")
                .setSingleChoiceItems(new String[] {"Standard (100%)", "Small (90%)",
                        "Compact (80%)", "Extra compact (70%)"}, selected, (dialog, index) -> {
                    dialog.dismiss();
                    int size = DISPLAY_SIZES[index];
                    if (size == current) return;
                    preferences.edit().putInt(DISPLAY_SIZE, size).apply();
                    reopenPortalAfterDisplayChange = reopenPortal;
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private List<JSONObject> savedPortals() {
        List<JSONObject> profiles = new ArrayList<>();
        try {
            JSONArray stored = new JSONArray(preferences.getString("profiles", "[]"));
            for (int index = 0; index < stored.length(); index++) {
                JSONObject profile = stored.optJSONObject(index);
                if (profile != null) profiles.add(profile);
            }
        } catch (JSONException ignored) {
            // Recover the previously connected profile from the original settings.
        }
        String previous = preferences.getString("address", "");
        if (profiles.isEmpty() && !previous.isEmpty()) {
            profiles.add(portalProfile(preferences.getString("portalName", ""), previous, preferences.getString("mac", ""),
                    preferences.getString("serial", ""), preferences.getString("model", "MAG254"),
                    preferences.getString("approvedRedirect", "")));
        }
        return profiles;
    }

    private JSONObject portalProfile(String name, String url, String device, String serial, String model, String redirect) {
        Map<String, String> values = new HashMap<>();
        values.put("name", name);
        values.put("address", url);
        values.put("mac", device);
        values.put("serial", serial);
        values.put("model", model);
        values.put("approvedRedirect", redirect);
        return new JSONObject(values);
    }

    private static String profileKey(JSONObject profile) {
        return profile.optString("address") + "\n" + profile.optString("mac").toUpperCase(Locale.ROOT);
    }

    private static String profileLabel(JSONObject profile) {
        String name = profile.optString("name").trim();
        if (!name.isEmpty()) return name;
        String url = profile.optString("address");
        try {
            return Portal.httpUri(url).getRawAuthority();
        } catch (IllegalArgumentException error) {
            return url;
        }
    }

    private void showSavedPortals() {
        startBrowse("Choose a portal", this::showSetup);
        button("Add portal", view -> showSetup(null)).requestFocus();
        ScrollView scroll = new ScrollView(this);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout entries = new LinearLayout(this);
        entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries);
        String defaultKey = preferences.getString("defaultPortal", "");
        for (JSONObject profile : savedPortals()) {
            LinearLayout row = new LinearLayout(this);
            entries.addView(row);
            String title = profileLabel(profile) + (defaultKey.equals(profileKey(profile)) ? "  •  Default" : "");
            Button open = addButton(row, title, view -> connectToSavedPortal(profile));
            open.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
            Button edit = addButton(row, "Edit", view -> showSetup(profile));
            edit.setContentDescription("Edit " + profileLabel(profile));
        }
    }

    private void connectToSavedPortal(JSONObject profile) {
        connectProfile(profile, profileKey(profile).equals(preferences.getString("defaultPortal", "")), profile);
    }

    private void showSetup() {
        String previous = preferences.getString("address", "");
        String device = preferences.getString("mac", "");
        for (JSONObject profile : savedPortals()) {
            if (previous.equals(profile.optString("address")) && device.equalsIgnoreCase(profile.optString("mac"))) {
                showSetup(profile);
                return;
            }
        }
        showSetup(null);
    }

    private void showSetup(JSONObject profile) {
        stopLivePreview();
        screenVersion++;
        backAction = null;
        editingPortal = profile;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = layout();
        scroll.addView(content);
        setContentView(scroll);
        text("Harbor TV", 32, MINT);
        text("Portal settings", 21, Color.WHITE);
        JSONObject fields = profile == null ? new JSONObject() : profile;
        portalName = field("Portal name", "e.g. Home TV", fields.optString("name"), InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        address = field("Portal URL", "https://tv.example.com/stalker_portal/c/",
                fields.optString("address"), InputType.TYPE_TEXT_VARIATION_URI);
        mac = field("Registered device MAC", "00:1A:79:00:00:01", fields.optString("mac"),
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        serial = field("Device serial (optional)", "Generated for MAG424 if blank", fields.optString("serial"),
                InputType.TYPE_TEXT_VARIATION_NORMAL);
        TextView modelLabel = text("STB model", 15, MUTED);
        stbModel = new Spinner(this, Spinner.MODE_DIALOG);
        stbModel.setId(View.generateViewId());
        modelLabel.setLabelFor(stbModel.getId());
        stbModel.setPrompt("STB model");
        String[] models = {"MAG254", "MAG424"};
        ArrayAdapter<String> modelOptions = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, models);
        modelOptions.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        stbModel.setAdapter(modelOptions);
        stbModel.setSelection("MAG424".equals(fields.optString("model")) ? 1 : 0);
        content.addView(stbModel, new LinearLayout.LayoutParams(-1, -2));
        defaultPortal = new CheckBox(this);
        defaultPortal.setText("Open this portal automatically");
        defaultPortal.setTextColor(Color.WHITE);
        defaultPortal.setChecked(profile != null && profileKey(profile).equals(preferences.getString("defaultPortal", "")));
        content.addView(defaultPortal, new LinearLayout.LayoutParams(-2, -2));
        CheckBox useVlc = new CheckBox(this);
        useVlc.setText("Use built-in VLC playback");
        useVlc.setTextColor(Color.WHITE);
        useVlc.setChecked(preferences.getBoolean("useVlc", true));
        useVlc.setOnCheckedChangeListener((view, checked) ->
                preferences.edit().putBoolean("useVlc", checked).apply());
        content.addView(useVlc, new LinearLayout.LayoutParams(-2, -2));
        CheckBox newestFirst = new CheckBox(this);
        newestFirst.setText("Show newest additions first in Movies and Series");
        newestFirst.setTextColor(Color.WHITE);
        newestFirst.setChecked(preferences.getBoolean("newestCatalogFirst", true));
        newestFirst.setOnCheckedChangeListener((view, checked) ->
                preferences.edit().putBoolean("newestCatalogFirst", checked).apply());
        content.addView(newestFirst, new LinearLayout.LayoutParams(-2, -2));
        button("Software updates", view -> updater.showSettings());
        button("Display size", view -> showDisplaySize(false));
        CheckBox classic = new CheckBox(this);
        classic.setText("Use classic Live TV layout");
        classic.setTextColor(Color.WHITE);
        classic.setChecked(preferences.getBoolean("classicLiveTv", false));
        classic.setOnCheckedChangeListener((view, checked) ->
                preferences.edit().putBoolean("classicLiveTv", checked).apply());
        content.addView(classic, new LinearLayout.LayoutParams(-2, -2));
        connect = button("Connect", view -> submitPortal(true));
        button("Save portal", view -> submitPortal(false));
        if (!savedPortals().isEmpty()) {
            button("Saved portals", view -> showSavedPortals());
            if (profile != null) button("Add portal", view -> showSetup(null));
        }
        status = text("", 16, MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connect.requestFocus();
    }

    private void submitPortal(boolean open) {
        String url = address.getText().toString().trim();
        String device = mac.getText().toString().trim();
        String deviceSerial = serial.getText().toString().trim();
        String model = (String) stbModel.getSelectedItem();
        String approved = "";
        for (JSONObject profile : savedPortals()) {
            if (url.equals(profile.optString("address")) && device.equalsIgnoreCase(profile.optString("mac"))) {
                approved = profile.optString("approvedRedirect");
                break;
            }
        }
        JSONObject profile = portalProfile(portalName.getText().toString().trim(), url, device, deviceSerial, model, approved);
        try {
            new Portal(url, device, deviceSerial, model, approved);
        } catch (IllegalArgumentException error) {
            status.setText(error.getMessage());
            return;
        }
        boolean setDefault = defaultPortal.isChecked();
        if (open) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(connect.getWindowToken(), 0);
            connectProfile(profile, setDefault, editingPortal);
        } else {
            savePortal(profile, setDefault, editingPortal);
            showSetup(profile);
            status.setText("Portal saved.");
        }
    }

    private void savePortal(JSONObject profile, boolean setDefault, JSONObject original) {
        List<JSONObject> profiles = savedPortals();
        String key = profileKey(profile);
        String previousKey = original == null ? key : profileKey(original);
        int position = profiles.size();
        for (int index = profiles.size() - 1; index >= 0; index--) {
            String existing = profileKey(profiles.get(index));
            if (existing.equals(key) || existing.equals(previousKey)) {
                position = index;
                profiles.remove(index);
            }
        }
        profiles.add(position, profile);
        SharedPreferences.Editor edit = preferences.edit()
                .putString("address", profile.optString("address"))
                .putString("mac", profile.optString("mac"))
                .putString("profiles", new JSONArray(profiles).toString());
        String defaultKey = preferences.getString("defaultPortal", "");
        if (setDefault) edit.putString("defaultPortal", key);
        else if (defaultKey.equals(previousKey) || defaultKey.equals(key)) edit.remove("defaultPortal");
        edit.apply();
    }

    private void connectProfile(JSONObject profile, boolean setDefault, JSONObject original) {
        String url = profile.optString("address");
        String device = profile.optString("mac");
        String deviceSerial = profile.optString("serial");
        String model = profile.optString("model", "MAG254");
        String approvedRedirect = profile.optString("approvedRedirect");
        stopLivePreview();
        int version = ++screenVersion;
        backAction = this::showSetup;
        content = layout();
        content.setGravity(android.view.Gravity.CENTER);
        setContentView(content);
        TextView loading = text("Connecting to " + profileLabel(profile) + "…", 22, MINT);
        loading.setGravity(android.view.Gravity.CENTER);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        content.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        TextView percent = text("0%", 16, MUTED);
        percent.setGravity(android.view.Gravity.CENTER);
        final Portal portal;
        try {
            String effectiveSerial = deviceSerial.isEmpty() && "MAG424".equals(model)
                    ? generatedSerial(url, device) : deviceSerial;
            portal = new Portal(url, device, effectiveSerial, model, approvedRedirect);
        } catch (IllegalArgumentException error) {
            showPortalFailure(profile, setDefault, original, error.getMessage());
            return;
        }
        requests.execute(() -> {
            try {
                List<Portal.Channel> channels = portal.connect(value -> deliver(version, () -> {
                    progress.setProgress(value);
                    percent.setText(value + "%");
                }));
                deliver(version, () -> {
                    savePortal(profile, setDefault, original);
                    progress.setProgress(100);
                    percent.setText("100%");
                    main.postDelayed(() -> deliver(version, () -> showChannels(portal, channels)), 200);
                });
            } catch (Portal.PortalRedirect redirect) {
                deliver(version, () -> {
                    showPortalFailure(profile, setDefault, original, "The portal redirected to another host.");
                    new AlertDialog.Builder(this)
                            .setTitle("Use redirected portal?")
                            .setMessage("Your portal points to " + redirect.authority
                                    + ". Select Connect to send your registered device details there.")
                            .setPositiveButton("Connect", (dialog, which) -> connectProfile(
                                    portalProfile(profile.optString("name"), url, device, deviceSerial, model,
                                            redirect.destination), setDefault, original))
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (IOException | IllegalArgumentException error) {
                deliver(version, () -> showPortalFailure(profile, setDefault, original, message(error)));
            }
        });
    }

    private void showPortalFailure(JSONObject profile, boolean setDefault, JSONObject original, String reason) {
        showSetup(profile);
        editingPortal = original;
        defaultPortal.setChecked(setDefault);
        status.setText(reason);
    }

    private String generatedSerial(String url, String macAddress) {
        java.net.URI portal = Portal.httpUri(url);
        String key = "generatedSerial/" + portal.getScheme().toLowerCase(Locale.ROOT)
                + "://" + portal.getRawAuthority().toLowerCase(Locale.ROOT)
                + portal.getRawPath().replaceAll("/+$", "") + "/" + macAddress.toUpperCase(Locale.ROOT);
        String stored = preferences.getString(key, "");
        if (!stored.isEmpty()) return stored;
        String generated = "H" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
        preferences.edit().putString(key, generated).apply();
        return generated;
    }

    private boolean classicLiveTv() {
        return preferences.getBoolean("classicLiveTv", false);
    }

    private void showHome(Portal portal, List<Portal.Channel> channels) {
        startBrowse("Harbor TV", () -> showChannels(portal, channels), true);
        Runnable home = () -> showHome(portal, channels);
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.VERTICAL);
        content.addView(tiles, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout primary = new LinearLayout(this);
        LinearLayout secondary = new LinearLayout(this);
        tiles.addView(primary, new LinearLayout.LayoutParams(-1, 0, 1));
        tiles.addView(secondary, new LinearLayout.LayoutParams(-1, 0, 1));
        homeTile(primary, "TV", android.R.drawable.ic_media_play, 0xff205b68,
                () -> showChannelCategories(portal, channels, home)).requestFocus();
        homeTile(primary, "Movies", android.R.drawable.ic_menu_slideshow, 0xff633e60,
                () -> showCategories(portal, "vod", "Movies", home));
        homeTile(primary, "Series", android.R.drawable.ic_menu_view, 0xff41497b,
                () -> showCategories(portal, "series", "Series", home));
        homeTile(secondary, "TV Guide", android.R.drawable.ic_menu_agenda, 0xff2c506e,
                () -> showGuideChannels(portal, channels, false, home));
        homeTile(secondary, "Catch-up", android.R.drawable.ic_menu_recent_history, 0xff665133,
                () -> showGuideChannels(portal, channels, true, home));
        homeTile(secondary, "Portal settings", android.R.drawable.ic_menu_preferences, 0xff384a5b,
                () -> {
                    showSetup();
                    backAction = home;
                });
    }

    private Button homeTile(LinearLayout row, String title, int icon, int color, Runnable open) {
        Button tile = addButton(row, title, view -> open.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -1, 1);
        size.setMargins(dp(5), dp(6), dp(5), dp(6));
        tile.setLayoutParams(size);
        tile.setTextSize(22);
        styleRaisedTitle(tile);
        tile.setShadowLayer(dp(3), 0, dp(2), 0xe6000000);
        tile.setElevation(dp(6));
        tile.setCompoundDrawablePadding(dp(8));
        tile.setPadding(dp(8), dp(18), dp(8), dp(18));
        android.graphics.drawable.Drawable symbol = getDrawable(icon).mutate();
        symbol.setTint(Color.WHITE);
        symbol.setBounds(0, 0, dp(32), dp(32));
        tile.setCompoundDrawables(null, symbol, null, null);
        tile.setGravity(android.view.Gravity.CENTER);
        tile.setTextColor(Color.WHITE);
        tile.setBackgroundTintList(null);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, raisedTileFace(color, true));
        background.addState(new int[0], raisedTileFace(color, false));
        tile.setBackground(background);
        return tile;
    }

    private LayerDrawable raisedTileFace(int color, boolean focused) {
        GradientDrawable edge = tileFace(0xff263749, 0xff09131f);
        GradientDrawable face = tileFace(color, focused ? 0xff254154 : 0xff172638);
        face.setStroke(dp(focused ? 2 : 1), focused ? MINT : 0x807fa5b8);
        LayerDrawable tile = new LayerDrawable(new android.graphics.drawable.Drawable[] {edge, face});
        tile.setLayerInset(1, 0, 0, 0, dp(5));
        return tile;
    }

    private GradientDrawable tileFace(int top, int bottom) {
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {top, bottom});
        face.setCornerRadius(dp(14));
        return face;
    }

    private void showChannels(Portal portal, List<Portal.Channel> channels) {
        showChannels(portal, channels, channels, null, 0);
    }

    private void showChannels(Portal portal, List<Portal.Channel> allChannels,
                              List<Portal.Channel> channels, String category, int initialPosition) {
        stopLivePreview();
        screenVersion++;
        boolean classic = classicLiveTv();
        Runnable home = () -> showHome(portal, allChannels);
        Runnable categories = () -> showChannelCategories(portal, allChannels, home);
        backAction = classic ? (category == null ? this::showSetup : () -> showChannels(portal, allChannels))
                : (category == null ? home : categories);
        openingStream = false;
        content = layout();
        setContentView(content);
        LinearLayout channelTitle = new LinearLayout(this);
        channelTitle.setGravity(android.view.Gravity.CENTER_VERTICAL);
        content.addView(channelTitle, new LinearLayout.LayoutParams(-1, -2));
        if (!classic) {
            Button homeButton = addButton(channelTitle, "⌂", view -> home.run());
            homeButton.setContentDescription("Home");
            homeButton.setPadding(0, 0, 0, 0);
            homeButton.setTextSize(23);
            homeButton.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(44)));
        }
        TextView heading = label(category == null ? "Live TV" : category, 32, MINT);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        channelTitle.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button display = addButton(channelTitle, "⚙", view -> showDisplaySize(true));
        display.setContentDescription("Display size");
        display.setTextSize(21);
        display.setPadding(0, 0, 0, 0);
        display.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(44)));
        Runnable live = () -> showChannels(portal, allChannels, channels, category, initialPosition);
        Selection<Integer> showGuide = position -> openChannelGuide(portal, channels.get(position),
                () -> showChannels(portal, allChannels, channels, category, position));
        boolean narrow = getResources().getConfiguration().screenWidthDp < 600;
        LinearLayout menu = classic && narrow ? buttonRow() : new LinearLayout(this);
        LinearLayout guideMenu = narrow ? menu : new LinearLayout(this);
        Button emptyFocus = display;
        if (classic) {
            addButton(menu, "Categories", view -> showChannelCategories(portal, allChannels, live));
            addButton(menu, "Movies", view -> showCategories(portal, "vod", "Movies", live));
            addButton(menu, "Series", view -> showCategories(portal, "series", "Series", live));
            addButton(guideMenu, "TV Guide", view -> showGuideChannels(portal, channels, false, live));
            addButton(guideMenu, "Catch-up", view -> showGuideChannels(portal, channels, true, live));
            emptyFocus = addButton(guideMenu, "Portal settings", view -> showSetup());
        }
        String caption = channels.isEmpty() ? "No channels assigned to this device."
                : channels.size() + (channels.size() == 1 ? " channel" : " channels");
        LinearLayout channelHeader = new LinearLayout(this);
        channelHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        status = label(caption, 16, MUTED);
        channelHeader.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        if (narrow) content.addView(channelHeader);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        channelList = new ListView(this);
        channelList.setId(View.generateViewId());
        styleList(channelList, !classic);
        Button goToTop = addButton(channelHeader, "← Top", view -> {
            channelList.requestFocus();
            channelList.setSelectionFromTop(0, 0);
        });
        goToTop.setContentDescription("Go to top of channel list");
        goToTop.setTextSize(13);
        goToTop.setNextFocusDownId(channelList.getId());
        goToTop.setVisibility(channels.isEmpty() ? View.GONE : View.VISIBLE);
        if (!classic) {
            goToTop.setId(View.generateViewId());
            View categoryShortcut = channelShortcut(channelHeader, "Channel categories", R.drawable.ic_shortcut_categories,
                    () -> showChannelCategories(portal, allChannels, live));
            View movies = channelShortcut(channelHeader, "Movies", R.drawable.ic_shortcut_movies,
                    () -> showCategories(portal, "vod", "Movies", live));
            View series = channelShortcut(channelHeader, "Series", R.drawable.ic_shortcut_series,
                    () -> showCategories(portal, "series", "Series", live));
            View menuButton = channelShortcut(channelHeader, "Main menu", R.drawable.ic_shortcut_menu, home);
            View previous = goToTop;
            for (View shortcut : new View[] {categoryShortcut, movies, series, menuButton}) {
                previous.setNextFocusRightId(shortcut.getId());
                shortcut.setNextFocusLeftId(previous.getId());
                shortcut.setNextFocusDownId(channelList.getId());
                previous = shortcut;
            }
        }
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(narrow ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        content.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));
        if (narrow) {
            split.addView(channelList, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            split.addView(left, new LinearLayout.LayoutParams(0, -1, 1));
            if (classic) for (LinearLayout row : new LinearLayout[] {menu, guideMenu}) {
                left.addView(row, new LinearLayout.LayoutParams(-1, -2));
                for (int index = 0; index < row.getChildCount(); index++) {
                    Button item = (Button) row.getChildAt(index);
                    item.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
                    item.setMinHeight(dp(44));
                    item.setTextSize(13);
                    item.setPadding(dp(4), item.getPaddingTop(), dp(4), item.getPaddingBottom());
                }
            }
            left.addView(channelHeader);
            left.addView(channelList, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(12), dp(8), dp(12), dp(8));
        right.setBackground(panelBackground(0xff172638));
        LinearLayout.LayoutParams rightSize = narrow ? new LinearLayout.LayoutParams(-1, 0, 1)
                : new LinearLayout.LayoutParams(0, -1, 1.35f);
        if (narrow) rightSize.topMargin = dp(8);
        else rightSize.leftMargin = dp(12);
        split.addView(right, rightSize);
        FrameLayout video = new FrameLayout(this);
        video.setBackgroundColor(Color.BLACK);
        right.addView(video, new LinearLayout.LayoutParams(-1, 0, narrow ? 1f : 1.15f));
        inlinePlayer = new PlaybackSurface(video);
        TextView videoCaption = label("Select a channel to preview", 14, MUTED);
        inlineCaption = videoCaption;
        right.addView(videoCaption);
        Button channelGuide = addButton(right, "Channel guide & replay",
                view -> openHighlightedGuide(channels, showGuide));
        channelGuide.setEnabled(!channels.isEmpty());
        ScrollView guide = new ScrollView(this);
        guide.setFocusable(false);
        right.addView(guide, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout guideRows = new LinearLayout(this);
        guideRows.setOrientation(LinearLayout.VERTICAL);
        guide.addView(guideRows);
        channelList.setAdapter(new ArrayAdapter<Portal.Channel>(this, android.R.layout.simple_list_item_1, channels) {
            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, recycled, parent);
                Portal.Channel channel = getItem(position);
                row.setText(channelLabel(channel));
                row.setContentDescription(channel.archive ? channel.name + ", catch-up available" : channel.name);
                row.setTextColor(Color.WHITE);
                row.setTextSize(18);
                styleRaisedTitle(row);
                row.setMinHeight(dp(classic ? 52 : 43));
                if (!classic) {
                    styleListRow(row, position);
                    row.setPadding(dp(12), 0, dp(12), 0);
                    row.setSingleLine(true);
                    row.setEllipsize(TextUtils.TruncateAt.END);
                }
                return row;
            }
        });
        channelList.setOnItemClickListener((parent, view, position, id) -> {
            Portal.Channel channel = channels.get(position);
            previewGuide(portal, channel, guideRows);
            if (channel == inlineChannel && inlineStream != null) {
                String stream = inlineStream;
                releaseInline();
                videoCaption.setText("Select a channel to preview");
                launchPlayer(stream, channel.name, true, null);
                return;
            }
            if (openingStream) return;
            releaseInline();
            openingStream = true;
            videoCaption.setText("Select a channel to preview");
            load("Opening " + channel.name + "…", () -> portal.resolve(channel), stream -> {
                openingStream = false;
                status.setText(caption);
                if (!resumed) return;
                inlineChannel = channel;
                inlineStream = stream;
                videoCaption.setText(channel.name + "  •  Select again for full screen");
                inlinePlayer.play(stream, useVlc(), () -> {
                    releaseInline();
                    videoCaption.setText("Preview failed. Select the channel to retry.");
                });
            });
        });
        channelList.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View row, int position, long id) {
                previewGuide(portal, channels.get(position), guideRows);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        channelList.setOnKeyListener((view, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && !channels.isEmpty()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    goToTop.requestFocus();
                }
                return true;
            }
            if (key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                openHighlightedGuide(channels, showGuide);
            }
            return true;
        });
        channelList.setOnItemLongClickListener((parent, view, position, id) -> {
            showGuide.select(position);
            return true;
        });
        if (channels.isEmpty()) emptyFocus.requestFocus();
        else {
            channelList.requestFocus();
            channelList.setSelection(initialPosition);
            previewGuide(portal, channels.get(initialPosition), guideRows);
        }
    }

    private View channelShortcut(LinearLayout row, String description, int icon, Runnable open) {
        ImageButton button = new ImageButton(this);
        button.setId(View.generateViewId());
        button.setContentDescription(description);
        button.setOnClickListener(view -> open.run());
        button.setFocusable(true);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, panelBackground(MINT));
        background.addState(new int[0], panelBackground(0xff284254));
        button.setBackground(background);
        button.setPadding(0, 0, 0, 0);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(icon);
        button.setImageTintList(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {0xff101a26, Color.WHITE}));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(42), dp(38));
        size.leftMargin = dp(4);
        row.addView(button, size);
        return button;
    }

    private static CharSequence channelLabel(Portal.Channel channel) {
        String number = channel.number.isEmpty() ? "" : channel.number + "   ";
        if (!channel.archive) return number + channel.name;
        SpannableString label = new SpannableString(number + "↶  " + channel.name);
        label.setSpan(new ForegroundColorSpan(MINT), number.length(), number.length() + 1, 0);
        return label;
    }

    private void showChannelCategories(Portal portal, List<Portal.Channel> allChannels, Runnable back) {
        startBrowse("Channel categories", back);
        button("All channels", view -> showChannels(portal, allChannels)).requestFocus();
        load("Loading channel categories…", portal::channelCategories, supplied -> {
            List<JSONObject> categories = new ArrayList<>();
            for (JSONObject category : supplied) {
                String id = channelCategoryId(category);
                if (!id.isEmpty() && !"*".equals(id)) categories.add(category);
            }
            rows(categories, MainActivity::titleOf, category -> {
                String name = titleOf(category);
                int version = ++screenVersion;
                status.setText("Loading " + name + "…");
                requests.execute(() -> {
                    try {
                        List<Portal.Channel> channels = portal.channelsInCategory(channelCategoryId(category));
                        deliver(version, () -> showChannels(portal, allChannels, channels, name, 0));
                    } catch (IOException | IllegalArgumentException error) {
                        deliver(version, () -> status.setText(message(error)));
                    }
                });
            }, "The portal supplied no additional channel categories.");
        });
    }

    private static String channelCategoryId(JSONObject category) {
        return Portal.field(category, "id", Portal.field(category, "genre_id", ""));
    }

    private void openHighlightedGuide(List<Portal.Channel> channels, Selection<Integer> showGuide) {
        if (channels.isEmpty()) return;
        int position = channelList.getSelectedItemPosition();
        if (position < 0) {
            position = inlineChannel == null ? channelList.getFirstVisiblePosition() : channels.indexOf(inlineChannel);
        }
        showGuide.select(Math.max(0, position));
    }

    private void openChannelGuide(Portal portal, Portal.Channel channel, Runnable back) {
        if (channel.id.isEmpty()) {
            status.setText("The portal gave this channel no guide ID.");
            return;
        }
        showProgrammes(portal, channel, false, 0, back);
    }

    private void previewGuide(Portal portal, Portal.Channel channel, LinearLayout rows) {
        cancelGuidePreview();
        int version = screenVersion;
        int requestVersion = previewVersion;
        GuidePreview cached = guideCache.get(channel);
        if (cached != null && SystemClock.elapsedRealtime() - cached.loadedAt < 120_000) {
            renderPreview(channel, rows, cached.programmes, null);
            return;
        }
        renderPreview(channel, rows, null, channel.id.isEmpty() ? "No guide ID for this channel." : "Loading guide…");
        if (channel.id.isEmpty()) return;
        pendingGuide = () -> guideRequest = guideRequests.submit(() -> {
            try {
                String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                List<JSONObject> programmes = portal.guidePreview(channel, date);
                deliver(version, () -> {
                    guideCache.put(channel, new GuidePreview(programmes));
                    if (requestVersion == previewVersion) renderPreview(channel, rows, programmes, null);
                });
            } catch (IOException | IllegalArgumentException error) {
                deliver(version, () -> {
                    if (requestVersion == previewVersion) {
                        renderPreview(channel, rows, null, "Guide listings unavailable.");
                    }
                });
            }
        });
        main.postDelayed(pendingGuide, 250);
    }

    private void renderPreview(Portal.Channel channel, LinearLayout rows, List<JSONObject> programmes, String feedback) {
        rows.removeAllViews();
        rows.addView(label(channel.name + "  •  Now & next", 16, MINT));
        if (programmes == null) {
            rows.addView(label(feedback, 14, MUTED));
            return;
        }
        int count = 0;
        long now = System.currentTimeMillis();
        for (JSONObject programme : programmes) {
            long stop = timestamp(programme.optLong("stop_timestamp", 0));
            if (stop > 0 && stop <= now) continue;
            TextView entry = label(programmeTime(programme) + titleOf(programme), 15, Color.WHITE);
            entry.setPadding(0, dp(3), 0, dp(3));
            rows.addView(entry);
            if (++count == 7) break;
        }
        if (count == 0) rows.addView(label("No upcoming listings from the portal.", 14, MUTED));
    }

    private void cancelGuidePreview() {
        previewVersion++;
        if (pendingGuide != null) main.removeCallbacks(pendingGuide);
        if (guideRequest != null) guideRequest.cancel(true);
        pendingGuide = null;
        guideRequest = null;
    }

    private void releaseInline() {
        if (inlinePlayer != null) inlinePlayer.release();
        inlineChannel = null;
        inlineStream = null;
    }

    private void stopLivePreview() {
        cancelGuidePreview();
        releaseInline();
        inlinePlayer = null;
        inlineCaption = null;
    }

    private static final class GuidePreview {
        final long loadedAt = SystemClock.elapsedRealtime();
        final List<JSONObject> programmes;

        GuidePreview(List<JSONObject> programmes) {
            this.programmes = programmes;
        }
    }

    private void showCategories(Portal portal, String type, String title, Runnable back) {
        startBrowse(title, back);
        Runnable categoriesScreen = () -> showCategories(portal, type, title, back);
        button("Search " + title.toLowerCase(Locale.ROOT),
                view -> showSearch(portal, type, title, categoriesScreen)).requestFocus();
        load("Loading categories…", () -> portal.categories(type), categories ->
                rows(categories, MainActivity::titleOf, category -> {
                    String id = Portal.field(category, "id", Portal.field(category, "category_id", ""));
                    if (id.isEmpty()) {
                        status.setText("The portal did not provide a category ID.");
                        return;
                    }
                    String categoryTitle = titleOf(category);
                    boolean newestFirst = preferences.getBoolean("newestCatalogFirst", true);
                    String[] filters = newestFirst
                            ? new String[] {"category", id, "movie_id", "0", "season_id", "0", "episode_id", "0",
                                    "sortby", "added"}
                            : new String[] {"category", id, "movie_id", "0", "season_id", "0", "episode_id", "0"};
                    showCatalog(portal, type, categoryTitle, categoriesScreen, filters,
                            titleSelection(portal, type));
                }, "No categories available."));
    }

    private void showSearch(Portal portal, String type, String title, Runnable back) {
        startBrowse("Search " + title.toLowerCase(Locale.ROOT), back);
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Title");
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(MUTED);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        content.addView(query, new LinearLayout.LayoutParams(-1, -2));
        Runnable search = () -> {
            String term = query.getText().toString().trim();
            if (term.isEmpty()) {
                status.setText("Enter a title to search.");
                openKeyboard(query);
                return;
            }
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(query.getWindowToken(), 0);
            showCatalog(portal, type, title + " • " + term, () -> showSearch(portal, type, title, back),
                    new String[] {"category", "*", "search", term, "movie_id", "0", "season_id", "0", "episode_id", "0"},
                    titleSelection(portal, type));
        };
        button("Search", view -> search.run());
        query.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search.run();
            return true;
        });
        query.setOnClickListener(view -> openKeyboard(query));
        openKeyboard(query);
    }

    private void openKeyboard(EditText query) {
        query.requestFocus();
        int version = screenVersion;
        query.post(() -> {
            if (version == screenVersion && query.isAttachedToWindow() && query.hasFocus()) {
                WindowCompat.getInsetsController(getWindow(), query).show(WindowInsetsCompat.Type.ime());
            }
        });
    }

    private CatalogSelection titleSelection(Portal portal, String type) {
        if ("series".equals(type)) return (item, back) -> showSeries(portal, item, back);
        return (item, back) -> openStream(titleOf(item), () -> portal.resolveMovie(item));
    }

    private void showCatalog(Portal portal, String type, String title, Runnable back,
                             String[] filters, CatalogSelection selection) {
        new CatalogListing(portal, title, back, page -> portal.catalog(type, page, filters), selection,
                (item, current) -> showTitle(portal, item, "series".equals(type), current),
                "vod".equals(type)).show();
    }

    private final class CatalogListing {
        private final Portal portal;
        private final String title;
        private final Runnable back;
        private final CatalogSource source;
        private final CatalogSelection selection;
        private final CatalogSelection details;
        private final boolean movieTrailers;
        private final List<JSONObject> items = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private int nextPage = 1;
        private int selectedPosition;
        private boolean more = true;
        private boolean fetching;
        private Button retry;
        private ListView list;
        private ArrayAdapter<JSONObject> adapter;
        private ScrollView preview;
        private ImageView previewPoster;
        private TextView previewTitle;
        private TextView previewMetadata;
        private TextView previewDate;
        private TextView previewSynopsis;
        private LinearLayout previewExtras;
        private TextView previewCredits;
        private Button previewTrailer;
        private JSONObject previewItem;

        CatalogListing(Portal portal, String title, Runnable back, CatalogSource source,
                       CatalogSelection selection, CatalogSelection details, boolean movieTrailers) {
            this.portal = portal;
            this.title = title;
            this.back = back;
            this.source = source;
            this.selection = selection;
            this.details = details;
            this.movieTrailers = movieTrailers;
        }

        void show() {
            boolean split = details != null && getResources().getConfiguration().screenWidthDp >= 600;
            startBrowse(title, back, split);
            fetching = false;
            retry = button("Retry loading titles", view -> {
                retry.setVisibility(View.GONE);
                fetchPage();
            });
            retry.setVisibility(View.GONE);
            list = new ListView(MainActivity.this);
            list.setId(View.generateViewId());
            list.setDivider(null);
            styleList(list, !classicLiveTv());
            preview = null;
            previewItem = null;
            if (split) showSplitLayout();
            else content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            adapter = split ? titleListAdapter(items) : catalogAdapter(portal, items);
            list.setAdapter(adapter);
            if (split) list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    selectedPosition = position;
                    updatePreview();
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {
                }
            });
            list.setOnItemClickListener((parent, view, position, id) -> {
                selectedPosition = position;
                selection.select(items.get(position), this::show);
            });
            if (details != null) list.setOnItemLongClickListener((parent, view, position, id) -> {
                selectedPosition = position;
                details.select(items.get(position), this::show);
                return true;
            });
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int state) {
                }

                @Override
                public void onScroll(AbsListView view, int first, int visible, int total) {
                    if (view == list && view.isAttachedToWindow() && visible > 0 && first + visible >= total - 3) {
                        fetchPage();
                    }
                }
            });
            if (items.isEmpty()) fetchPage();
            else {
                list.requestFocus();
                list.setSelection(selectedPosition);
                updatePreview();
            }
        }

        private void showSplitLayout() {
            LinearLayout split = new LinearLayout(MainActivity.this);
            content.addView(split, new LinearLayout.LayoutParams(-1, 0, 1));
            split.addView(list, new LinearLayout.LayoutParams(0, -1, 1.1f));
            preview = new ScrollView(MainActivity.this);
            preview.setId(View.generateViewId());
            preview.setFocusable(true);
            preview.setFillViewport(true);
            preview.setBackground(panelBackground(0xff172638));
            preview.setNextFocusLeftId(list.getId());
            list.setNextFocusRightId(preview.getId());
            LinearLayout.LayoutParams previewSize = new LinearLayout.LayoutParams(0, -1, 1);
            previewSize.leftMargin = dp(14);
            split.addView(preview, previewSize);

            LinearLayout detail = new LinearLayout(MainActivity.this);
            detail.setOrientation(LinearLayout.HORIZONTAL);
            detail.setPadding(dp(16), dp(12), dp(16), dp(12));
            LinearLayout body = new LinearLayout(MainActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            preview.addView(body);
            body.addView(detail, new LinearLayout.LayoutParams(-1, -2));
            previewPoster = newPoster();
            previewPoster.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int width = Math.max(92, Math.min(250, getResources().getConfiguration().screenWidthDp / 6));
            detail.addView(previewPoster, new LinearLayout.LayoutParams(dp(width), dp(width * 3 / 2)));
            LinearLayout copy = new LinearLayout(MainActivity.this);
            copy.setOrientation(LinearLayout.VERTICAL);
            copy.setPadding(dp(12), 0, 0, 0);
            detail.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            previewTitle = label("", 22, Color.WHITE);
            previewMetadata = label("", 15, MINT);
            previewDate = label("", 14, MUTED);
            previewSynopsis = label("", 16, Color.WHITE);
            previewTitle.setPadding(0, 0, 0, dp(4));
            copy.addView(previewTitle);
            copy.addView(previewMetadata);
            copy.addView(previewDate);
            copy.addView(previewSynopsis);
            previewExtras = new LinearLayout(MainActivity.this);
            previewExtras.setOrientation(LinearLayout.VERTICAL);
            previewExtras.setPadding(dp(16), 0, dp(16), dp(12));
            body.addView(previewExtras, new LinearLayout.LayoutParams(-1, -2));
            View divider = new View(MainActivity.this);
            divider.setBackgroundColor(0x407db5bf);
            previewExtras.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            previewCredits = label("", 15, Color.WHITE);
            previewExtras.addView(previewCredits);
            if (movieTrailers) {
                previewTrailer = addButton(previewExtras, "Watch trailer", view -> TrailerActivity.open(MainActivity.this, previewItem));
                previewTrailer.setId(View.generateViewId());
                previewTrailer.setNextFocusLeftId(list.getId());
                list.setNextFocusRightId(previewTrailer.getId());
            }
            preview.setVisibility(View.INVISIBLE);
        }

        private void updatePreview() {
            if (preview == null || selectedPosition >= items.size()) return;
            JSONObject item = items.get(selectedPosition);
            if (item == previewItem) return;
            previewItem = item;
            preview.setVisibility(View.VISIBLE);
            preview.scrollTo(0, 0);
            String artwork = portal.posterUrl(item);
            previewPoster.setVisibility(artwork.isEmpty() ? View.GONE : View.VISIBLE);
            posters.bind(previewPoster, artwork);
            previewTitle.setText(titleOf(item));
            String info = titleMetadata(item);
            previewMetadata.setText(info);
            previewMetadata.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);
            String added = dateAdded(item);
            previewDate.setText(added.isEmpty() ? "" : "Added " + added);
            previewDate.setVisibility(added.isEmpty() ? View.GONE : View.VISIBLE);
            String synopsis = description(item);
            previewSynopsis.setText(synopsis.isEmpty() ? "No description available." : synopsis);
            String credits = titleCredits(item);
            previewCredits.setText(credits);
            previewCredits.setVisibility(credits.isEmpty() ? View.GONE : View.VISIBLE);
            previewExtras.setVisibility(credits.isEmpty() && !movieTrailers ? View.GONE : View.VISIBLE);
            if (movieTrailers) {
                previewTrailer.setText(TrailerActivity.videoId(item).isEmpty() ? "Find trailer on YouTube" : "Watch trailer");
            }
        }

        private void fetchPage() {
            if (!more || fetching || retry.isShown()) return;
            fetching = true;
            int version = screenVersion;
            int page = nextPage;
            status.setText(items.isEmpty() ? "Loading titles…" : "Loading more titles…");
            requests.execute(() -> {
                try {
                    Portal.CatalogPage result = source.page(page);
                    deliver(version, () -> append(result));
                } catch (IOException | IllegalArgumentException error) {
                    deliver(version, () -> {
                        fetching = false;
                        status.setText(message(error));
                        retry.setVisibility(View.VISIBLE);
                        if (items.isEmpty()) retry.requestFocus();
                    });
                }
            });
        }

        private void append(Portal.CatalogPage result) {
            boolean first = items.isEmpty();
            int added = 0;
            for (JSONObject item : result.items) {
                String key = Portal.field(item, "id", "") + "\n" + Portal.field(item, "cmd", "") + "\n" + titleOf(item);
                if (seen.add(key)) {
                    items.add(item);
                    added++;
                }
            }
            nextPage++;
            more = result.next && added > 0;
            fetching = false;
            status.setText(items.isEmpty() ? "No titles found." : "");
            adapter.notifyDataSetChanged();
            if (first && added > 0) {
                list.requestFocus();
                list.setSelection(0);
                updatePreview();
            }
        }
    }

    private ArrayAdapter<JSONObject> titleListAdapter(List<JSONObject> items) {
        return new ArrayAdapter<JSONObject>(this, 0, items) {
            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout row = (LinearLayout) recycled;
                if (row == null) {
                    row = new LinearLayout(MainActivity.this);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    row.setMinimumHeight(dp(46));
                    row.setPadding(dp(12), 0, dp(12), 0);
                    TextView title = label("", 17, Color.WHITE);
                    styleRaisedTitle(title);
                    title.setSingleLine(true);
                    title.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView date = label("", 14, MUTED);
                    date.setGravity(android.view.Gravity.END);
                    date.setPadding(dp(8), 0, 0, 0);
                    row.addView(date);
                }
                JSONObject item = items.get(position);
                if (!classicLiveTv()) styleListRow(row, position);
                ((TextView) row.getChildAt(0)).setText(titleOf(item));
                ((TextView) row.getChildAt(1)).setText(dateAdded(item));
                return row;
            }
        };
    }

    private ArrayAdapter<JSONObject> catalogAdapter(Portal portal, List<JSONObject> items) {
        return new ArrayAdapter<JSONObject>(this, 0, items) {
            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
                CatalogCard card = recycled instanceof CatalogCard
                        ? (CatalogCard) recycled : new CatalogCard();
                if (!classicLiveTv()) styleListRow(card, position);
                JSONObject item = items.get(position);
                card.title.setText(titleOf(item));
                String synopsis = description(item);
                card.description.setText(synopsis);
                card.description.setVisibility(synopsis.isEmpty() ? View.GONE : View.VISIBLE);
                String info = titleMetadata(item);
                card.metadata.setText(info);
                card.metadata.setVisibility(info.isEmpty() ? View.GONE : View.VISIBLE);
                posters.bind(card.poster, portal.posterUrl(item));
                return card;
            }
        };
    }

    private void showTitle(Portal portal, JSONObject item, boolean series, Runnable back) {
        startBrowse(titleOf(item), back);
        button(series ? "Browse episodes" : "Play movie", view -> {
            if (series) showSeries(portal, item, () -> showTitle(portal, item, true, back));
            else openStream(titleOf(item), () -> portal.resolveMovie(item));
        }).requestFocus();
        if (!series) button(TrailerActivity.videoId(item).isEmpty() ? "Find trailer on YouTube" : "Watch trailer",
                view -> TrailerActivity.open(this, item));
        ScrollView scroll = new ScrollView(this);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout detail = new LinearLayout(this);
        detail.setPadding(dp(14), dp(14), dp(14), dp(14));
        detail.setBackground(panelBackground(0xff172638));
        scroll.addView(detail);
        ImageView cover = newPoster();
        detail.addView(cover, new LinearLayout.LayoutParams(dp(115), dp(172)));
        posters.bind(cover, portal.posterUrl(item));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(16), 0, 0, 0);
        detail.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        String info = titleMetadata(item);
        if (!info.isEmpty()) copy.addView(label(info, 15, MINT));
        String added = dateAdded(item);
        if (!added.isEmpty()) copy.addView(label("Added " + added, 14, MUTED));
        String synopsis = description(item);
        copy.addView(label(synopsis.isEmpty() ? "The portal has no description for this title." : synopsis,
                17, Color.WHITE));
        String credits = titleCredits(item);
        if (!credits.isEmpty()) copy.addView(label(credits, 15, MUTED));
    }

    private final class CatalogCard extends LinearLayout {
        final ImageView poster;
        final TextView title;
        final TextView metadata;
        final TextView description;

        CatalogCard() {
            super(MainActivity.this);
            setPadding(dp(8), dp(7), dp(8), dp(7));
            poster = newPoster();
            addView(poster, new LayoutParams(dp(70), dp(105)));
            LinearLayout copy = new LinearLayout(MainActivity.this);
            copy.setOrientation(VERTICAL);
            copy.setPadding(dp(14), 0, 0, 0);
            addView(copy, new LayoutParams(0, -2, 1));
            title = label("", 19, Color.WHITE);
            styleRaisedTitle(title);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            metadata = label("", 14, MINT);
            description = label("", 15, MUTED);
            description.setMaxLines(2);
            description.setEllipsize(TextUtils.TruncateAt.END);
            copy.addView(title);
            copy.addView(metadata);
            copy.addView(description);
        }
    }

    private ImageView newPoster() {
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(panelBackground(0xff294057));
        poster.setClipToOutline(true);
        poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return poster;
    }

    private static String description(JSONObject item) {
        return Portal.field(item, "description", Portal.field(item, "descr", Portal.field(item, "plot", ""))).trim();
    }

    private static String titleCredits(JSONObject item) {
        String cast = creditNames(item, "actors", "cast", "starring");
        String director = creditNames(item, "director", "directors");
        StringBuilder credits = new StringBuilder();
        if (!cast.isEmpty()) credits.append("Cast\n").append(cast);
        if (!director.isEmpty()) {
            if (credits.length() > 0) credits.append("\n\n");
            credits.append("Directed by\n").append(director);
        }
        return credits.toString();
    }

    private static String creditNames(JSONObject item, String... fields) {
        for (String field : fields) {
            JSONArray array = item.optJSONArray(field);
            if (array == null) {
                String value = Portal.field(item, field, "").trim();
                if (!value.isEmpty() && !value.startsWith("{")) return value;
                continue;
            }
            List<String> names = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject person = array.optJSONObject(index);
                String name = person != null ? Portal.field(person, "name", "") : array.optString(index, "");
                if (!name.trim().isEmpty() && !"null".equals(name)) names.add(name.trim());
            }
            if (!names.isEmpty()) return TextUtils.join(", ", names);
        }
        return "";
    }

    private static String dateAdded(JSONObject item) {
        for (String field : new String[] {"added", "added_at", "date_added", "addtime", "added_timestamp"}) {
            String value = Portal.field(item, field, "").trim();
            Date date = null;
            if (value.matches("[0-9]{10}|[0-9]{13}")) {
                long epoch = Long.parseLong(value);
                if (epoch > 0) date = new Date(timestamp(epoch));
            } else if (value.matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}(?:[ T].*)?")) {
                SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
                parser.setLenient(false);
                date = parser.parse(value, new ParsePosition(0));
            }
            if (date != null) return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(date);
        }
        return "";
    }

    private static String titleMetadata(JSONObject item) {
        String year = Portal.field(item, "year", "");
        String genre = Portal.field(item, "genre", Portal.field(item, "genres_str", ""));
        return year.isEmpty() || genre.isEmpty() ? year + genre : year + "  •  " + genre;
    }

    private void showSeries(Portal portal, JSONObject show, Runnable back) {
        JSONArray numbers = show.optJSONArray("series");
        String command = Portal.field(show, "cmd", "");
        if (numbers != null && numbers.length() > 0 && !command.isEmpty()) {
            startBrowse(titleOf(show), back);
            List<String> episodes = new ArrayList<>();
            for (int index = 0; index < numbers.length(); index++) {
                String number = numbers.optString(index, "");
                if (number.matches("[0-9]+")) episodes.add(number);
            }
            if (!episodes.isEmpty()) {
                rows(episodes, number -> "Episode " + number, number ->
                        openStream(titleOf(show) + " • Episode " + number,
                                () -> portal.resolveVideo(command, number)), "No episodes available.");
                return;
            }
        }
        String id = Portal.field(show, "id", Portal.field(show, "movie_id", ""));
        if (id.isEmpty()) {
            status.setText("The portal did not provide a series ID.");
            return;
        }
        showSeriesItems(portal, titleOf(show), id, "0", command, back);
    }

    private void showSeriesItems(Portal portal, String title, String movieId, String seasonId,
                                 String parentCommand, Runnable back) {
        new CatalogListing(portal, title, back,
                page -> portal.seriesCatalog(page, movieId, seasonId),
                (item, current) -> {
                    String itemId = Portal.field(item, "id", "");
                    String command = Portal.field(item, "cmd", "");
                    JSONArray numbers = item.optJSONArray("series");
                    if (numbers != null && numbers.length() > 0 && !command.isEmpty()) {
                        showSeries(portal, item, current);
                    } else if ((Portal.flag(item, "is_season") || itemId.matches("[0-9]+:[0-9]+"))
                            && "0".equals(seasonId)
                            && !itemId.isEmpty()) {
                        showSeriesItems(portal, titleOf(item), movieId, itemId, command, current);
                    } else {
                        String episode = command.isEmpty() ? Portal.field(item, "series_number", "") : "";
                        String playable = command.isEmpty() && !episode.isEmpty() ? parentCommand : command;
                        openStream(titleOf(item), () -> portal.resolveVideo(playable, episode));
                    }
                }, null, false).show();
    }

    private void showGuideChannels(Portal portal, List<Portal.Channel> channels, boolean archive, Runnable back) {
        startBrowse(archive ? "Catch-up" : "TV Guide", back);
        List<Portal.Channel> available = new ArrayList<>();
        for (Portal.Channel channel : channels) {
            if (!channel.id.isEmpty() && (!archive || channel.archive)) available.add(channel);
        }
        rows(available, channel -> channel.number.isEmpty() ? channel.name : channel.number + "   " + channel.name,
                channel -> showProgrammes(portal, channel, archive, 0,
                        () -> showGuideChannels(portal, channels, archive, back)),
                archive ? "The portal reported no channels with catch-up." : "No channels with a guide are available.");
    }

    private void showProgrammes(Portal portal, Portal.Channel channel, boolean archive, int day, Runnable back) {
        Calendar date = Calendar.getInstance();
        date.add(Calendar.DAY_OF_YEAR, day);
        String queryDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date.getTime());
        int firstDay = day - (date.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int lastDay = archive ? Math.min(0, firstDay + 6) : firstDay + 6;
        startBrowse(channel.name + (archive ? " • Catch-up" : " • TV Guide"), back);
        LinearLayout controls = buttonRow();
        addButton(controls, "Earlier week", view ->
                showProgrammes(portal, channel, archive, day - 7, back));
        if (!archive || firstDay + 7 <= 0) addButton(controls, "Later week", view ->
                showProgrammes(portal, channel, archive, archive ? Math.min(0, day + 7) : day + 7, back));
        addButton(controls, archive ? "All listings" : "Replay only", view ->
                showProgrammes(portal, channel, !archive, archive ? day : Math.min(0, day), back));

        LinearLayout columns = new LinearLayout(this);
        content.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
        List<Integer> days = new ArrayList<>();
        for (int offset = firstDay; offset <= lastDay; offset++) days.add(offset);
        SimpleDateFormat weekday = new SimpleDateFormat("EEEE, d MMM", Locale.getDefault());
        ListView dates = makeRows(days, offset -> {
            Calendar choice = (Calendar) date.clone();
            choice.add(Calendar.DAY_OF_YEAR, offset - day);
            return (offset == day ? "▸  " : "") + weekday.format(choice.getTime());
        }, offset -> {
            if (offset != day) showProgrammes(portal, channel, archive, offset, back);
        });
        dates.setId(View.generateViewId());
        columns.addView(dates, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout listings = new LinearLayout(this);
        listings.setOrientation(LinearLayout.VERTICAL);
        listings.setPadding(dp(14), 0, dp(6), 0);
        listings.setBackground(panelBackground(0xff172638));
        columns.addView(listings, new LinearLayout.LayoutParams(0, -1, 2.1f));
        listings.addView(label(weekday.format(date.getTime()), 19, MINT));
        dates.requestFocus();
        dates.setSelection(day - firstDay);

        load("Loading guide…", () -> portal.programmes(channel, queryDate, day == 0), programmes -> {
            List<JSONObject> visible = new ArrayList<>();
            for (JSONObject programme : programmes) {
                if (!archive || Portal.archived(programme)) visible.add(programme);
            }
            if (visible.isEmpty()) {
                listings.addView(label(archive ? "No replays available for this day."
                        : "No guide listings for this day.", 15, MUTED));
                return;
            }
            ListView programmesList = makeRows(visible, programme -> programmeTime(programme) + titleOf(programme)
                    + (!archive && Portal.archived(programme) ? "   •   Replay" : ""), programme -> {
                if (Portal.archived(programme)) {
                    openReplay(portal, programme);
                } else {
                    new AlertDialog.Builder(this).setTitle(titleOf(programme))
                            .setMessage(Portal.field(programme, "descr", "No programme description available."))
                            .setPositiveButton("OK", null).show();
                }
            });
            programmesList.setId(View.generateViewId());
            dates.setNextFocusRightId(programmesList.getId());
            programmesList.setNextFocusLeftId(dates.getId());
            listings.addView(programmesList, new LinearLayout.LayoutParams(-1, 0, 1));
        });
    }

    private static String titleOf(JSONObject item) {
        return Portal.field(item, "name", Portal.field(item, "title", "Untitled"));
    }

    private static String programmeTime(JSONObject programme) {
        long start = timestamp(programme.optLong("start_timestamp"));
        if (start > 0) {
            return new SimpleDateFormat("HH:mm", Locale.getDefault())
                    .format(new Date(start + 3_600_000L)) + "   ";
        }
        String time = Portal.field(programme, "time", "");
        if (time.length() >= 16 && time.charAt(10) == ' ') time = time.substring(11, 16);
        if (time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9])?")) {
            int hour = (Integer.parseInt(time.substring(0, 2)) + 1) % 24;
            time = String.format(Locale.ROOT, "%02d%s", hour, time.substring(2, 5));
        }
        return time.isEmpty() ? "" : time + "   ";
    }

    private static long timestamp(long epoch) {
        return epoch < 100_000_000_000L ? epoch * 1000 : epoch;
    }

    private void startBrowse(String title, Runnable back) {
        startBrowse(title, back, false);
    }

    private void startBrowse(String title, Runnable back, boolean compactHeader) {
        stopLivePreview();
        screenVersion++;
        backAction = back;
        openingStream = false;
        content = layout();
        setContentView(content);
        if (compactHeader) {
            LinearLayout header = new LinearLayout(this);
            header.setGravity(android.view.Gravity.CENTER_VERTICAL);
            content.addView(header, new LinearLayout.LayoutParams(-1, -2));
            Button arrow = addButton(header, "←", view -> back.run());
            arrow.setContentDescription("Back");
            arrow.setTextSize(22);
            arrow.setPadding(0, 0, 0, 0);
            arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(44)));
            arrow.requestFocus();
            TextView heading = label(title, 24, MINT);
            heading.setSingleLine(true);
            heading.setEllipsize(TextUtils.TruncateAt.END);
            heading.setPadding(dp(8), 0, dp(8), 0);
            header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
            status = label("", 14, MUTED);
            status.setMaxWidth(dp(getResources().getConfiguration().screenWidthDp / 2));
            header.addView(status);
        } else {
            text(title, 27, MINT);
            button("Back", view -> back.run()).requestFocus();
            status = text("", 16, MUTED);
        }
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    private <T> void load(String label, PortalTask<T> task, Selection<T> result) {
        status.setText(label);
        int version = screenVersion;
        requests.execute(() -> {
            try {
                T value = task.run();
                deliver(version, () -> {
                    status.setText("");
                    result.select(value);
                });
            } catch (IOException | IllegalArgumentException error) {
                deliver(version, () -> {
                    openingStream = false;
                    status.setText(message(error));
                });
            }
        });
    }

    private void openStream(String title, PortalTask<String> task) {
        openPlayback(title, task, stream -> launchPlayer(stream, title, false, null));
    }

    private void openReplay(Portal portal, JSONObject programme) {
        String title = titleOf(programme);
        openPlayback(title, () -> portal.resolveArchive(programme),
                replay -> launchPlayer(replay.primary, title, false, replay));
    }

    private <T> void openPlayback(String title, PortalTask<T> task, Selection<T> start) {
        if (openingStream) return;
        openingStream = true;
        load("Opening " + title + "…", task, result -> {
            openingStream = false;
            start.select(result);
        });
    }

    private <T> void rows(List<T> items, RowLabel<T> label, Selection<T> selection, String emptyLabel) {
        if (items.isEmpty()) {
            status.setText(emptyLabel);
            return;
        }
        ListView list = makeRows(items, label, selection);
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        list.requestFocus();
    }

    private <T> ListView makeRows(List<T> items, RowLabel<T> label, Selection<T> selection) {
        ListView list = new ListView(this);
        boolean modern = !classicLiveTv();
        styleList(list, modern);
        list.setAdapter(new ArrayAdapter<T>(this, android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, recycled, parent);
                row.setText(label.label(items.get(position)));
                row.setTextColor(Color.WHITE);
                row.setTextSize(18);
                row.setMinHeight(dp(modern ? 43 : 52));
                if (modern) {
                    styleListRow(row, position);
                    styleRaisedTitle(row);
                    row.setPadding(dp(12), dp(5), dp(12), dp(5));
                }
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> selection.select(items.get(position)));
        return list;
    }

    private void styleList(ListView list, boolean modern) {
        if (!modern) {
            list.setSelector(new ColorDrawable(0xff27534e));
            return;
        }
        GradientDrawable highlight = panelBackground(0x554facaa);
        highlight.setStroke(dp(2), MINT);
        list.setSelector(highlight);
        list.setDrawSelectorOnTop(true);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(3));
    }

    private void styleListRow(View row, int position) {
        row.setBackground(panelBackground(position % 2 == 0 ? 0xff203547 : 0xff263b50));
    }

    private interface PortalTask<T> {
        T run() throws IOException;
    }

    private interface RowLabel<T> {
        String label(T item);
    }

    private interface Selection<T> {
        void select(T item);
    }

    private interface CatalogSelection {
        void select(JSONObject item, Runnable currentPage);
    }

    private interface CatalogSource {
        Portal.CatalogPage page(int number) throws IOException;
    }

    @Override
    public void onBackPressed() {
        if (backAction != null) backAction.run();
        else super.onBackPressed();
    }

    private void launchPlayer(String stream, String channel, boolean live, Portal.ReplayLinks replay) {
        if (!resumed) return;
        startActivity(new Intent(this, PlayerActivity.class)
                .putExtra("stream", stream).putExtra("channel", channel).putExtra("useVlc", useVlc())
                .putExtra("live", live).putExtra("replay", replay != null)
                .putExtra("alternateStream", replay == null ? null : replay.alternate));
    }

    private boolean useVlc() {
        return preferences.getBoolean("useVlc", true);
    }

    private void deliver(int version, Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed() && version == screenVersion) action.run();
        });
    }

    private static String message(Exception error) {
        if (error instanceof UnknownHostException) return "Could not find the portal host. Check its URL and network connection.";
        if (error instanceof SocketTimeoutException) return "The portal timed out. Check that this device can reach it.";
        if (error instanceof java.net.ConnectException) return "Could not connect to the portal.";
        if (error instanceof javax.net.ssl.SSLException) return "Could not establish a secure connection to the portal.";
        return error.getMessage() == null ? "The portal request failed." : error.getMessage();
    }

    private LinearLayout layout() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        boolean television = getPackageManager().hasSystemFeature("android.software.leanback")
                || (getResources().getConfiguration().uiMode & 0x0f) == 4;
        int horizontal = dp(television ? 48 : 20);
        int vertical = dp(television ? 28 : 16);
        result.setPadding(horizontal, vertical, horizontal, vertical);
        return result;
    }

    private EditText field(String label, String hint, String value, int variation) {
        TextView title = text(label, 15, MUTED);
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        title.setLabelFor(field.getId());
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        field.setHint(hint);
        field.setText(value);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(MUTED);
        content.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private TextView text(String value, int size, int color) {
        TextView view = label(value, size, color);
        content.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private void styleRaisedTitle(TextView title) {
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setShadowLayer(dp(2), 0, dp(1), 0xb8000000);
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private GradientDrawable panelBackground(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(10));
        return shape;
    }

    private Button button(String label, View.OnClickListener listener) {
        return addButton(content, label, listener);
    }

    private LinearLayout buttonRow() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private Button addButton(LinearLayout row, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {0xff101a26, Color.WHITE}));
        button.setBackgroundTintList(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}},
                new int[] {MINT, 0xff284254}));
        button.setOnClickListener(listener);
        row.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
    }

    @Override
    protected void onPause() {
        resumed = false;
        releaseInline();
        if (inlineCaption != null) inlineCaption.setText("Select a channel to preview");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        updater.close();
        stopLivePreview();
        guideRequests.shutdownNow();
        posters.close();
        requests.shutdownNow();
        super.onDestroy();
    }
}
