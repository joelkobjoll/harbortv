package com.harbortv.client;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public final class PlayerActivity extends Activity {
    private static final long SEEK_STEP_MS = 10_000L;
    private static final long CONTROLS_TIMEOUT_MS = 5_000L;
    private PlaybackSurface playback;
    private Button errorButton;
    private LinearLayout controls;
    private SeekBar timeline;
    private Button playPause;
    private TextView timeLabel;
    private boolean dragging;
    private boolean revealKeyPressed;
    private long hideAt;
    private final Runnable refreshControls = this::updateControls;
    private String stream;
    private boolean useVlc;
    private boolean replay;
    private boolean live;
    private boolean liveSelectPressed;
    private String alternateStream;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        stream = getIntent().getStringExtra("stream");
        if (stream == null) {
            finish();
            return;
        }
        useVlc = getIntent().getBooleanExtra("useVlc", true);
        replay = getIntent().getBooleanExtra("replay", false);
        live = getIntent().getBooleanExtra("live", false);
        alternateStream = getIntent().getStringExtra("alternateStream");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout layout = new FrameLayout(this);
        FrameLayout video = new FrameLayout(this);
        video.setContentDescription(getIntent().getStringExtra("channel"));
        layout.addView(video, new FrameLayout.LayoutParams(-1, -1));
        playback = new PlaybackSurface(video);
        if (!live) {
            createControls(layout);
            video.setOnClickListener(view -> showControls());
        }
        errorButton = new Button(this);
        errorButton.setAllCaps(false);
        errorButton.setVisibility(View.GONE);
        errorButton.setOnClickListener(view -> finish());
        layout.addView(errorButton, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        setContentView(layout);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playback == null) return;
        errorButton.setVisibility(View.GONE);
        play(stream, alternateStream);
        if (!live && !errorButton.isShown()) showControls();
    }

    private void play(String url, String alternate) {
        playback.play(url, useVlc, replay, reason -> {
            if (alternate != null) {
                play(alternate, null);
                return;
            }
            errorButton.setText((replay ? "Replay failed. " : "Playback failed. ")
                    + reason + "\nReturn to browsing");
            hideControls();
            errorButton.setVisibility(View.VISIBLE);
            errorButton.requestFocus();
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean select = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (revealKeyPressed && select && event.getAction() == KeyEvent.ACTION_UP) {
            revealKeyPressed = false;
            return true;
        }
        if (!live && playback != null && !errorButton.isShown()
                && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (!controls.isShown() && (select || key == KeyEvent.KEYCODE_DPAD_UP
                    || key == KeyEvent.KEYCODE_DPAD_DOWN)) {
                showControls();
                revealKeyPressed = select;
                return true;
            }
            hideAt = SystemClock.uptimeMillis() + CONTROLS_TIMEOUT_MS;
            if ((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)
                    && !timeline.hasFocus()) {
                if (event.getRepeatCount() == 0) {
                    playback.seekBy(key == KeyEvent.KEYCODE_DPAD_LEFT ? -SEEK_STEP_MS : SEEK_STEP_MS);
                    showControls();
                }
                return true;
            }
        }
        if (revealKeyPressed && select) return true;
        if (live && select) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) liveSelectPressed = true;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (liveSelectPressed && !event.isCanceled()) finish();
                liveSelectPressed = false;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && playback != null && !errorButton.isShown()) {
            if (event.getRepeatCount() == 0 && playback.togglePause() && !live) showControls();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void createControls(FrameLayout layout) {
        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(0xe61a2237);
        controls.setPadding(dp(20), dp(8), dp(20), dp(8));
        layout.addView(controls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        timeline = new SeekBar(this);
        timeline.setId(View.generateViewId());
        timeline.setFocusable(true);
        timeline.setContentDescription("Playback position");
        controls.addView(timeline, new LinearLayout.LayoutParams(-1, dp(42)));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long position = progress * 1000L;
                if (!dragging) playback.seekTo(position);
                timeLabel.setText(formatTime(position) + " / " + formatTime(playback.duration()));
                hideAt = SystemClock.uptimeMillis() + CONTROLS_TIMEOUT_MS;
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                playback.seekTo(bar.getProgress() * 1000L);
                hideAt = SystemClock.uptimeMillis() + CONTROLS_TIMEOUT_MS;
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(actions);
        playPause = new Button(this);
        playPause.setId(View.generateViewId());
        playPause.setAllCaps(false);
        playPause.setOnClickListener(view -> {
            playback.togglePause();
            showControls();
        });
        playPause.setNextFocusUpId(timeline.getId());
        timeline.setNextFocusDownId(playPause.getId());
        actions.addView(playPause);
        timeLabel = new TextView(this);
        timeLabel.setTextSize(16);
        timeLabel.setTextColor(Color.WHITE);
        timeLabel.setPadding(dp(12), 0, 0, 0);
        actions.addView(timeLabel);
    }

    private void showControls() {
        boolean wasHidden = !controls.isShown();
        controls.setVisibility(View.VISIBLE);
        hideAt = SystemClock.uptimeMillis() + CONTROLS_TIMEOUT_MS;
        if (wasHidden || !controls.hasFocus()) playPause.requestFocus();
        controls.removeCallbacks(refreshControls);
        updateControls();
    }

    private void updateControls() {
        boolean playing = playback.wantsPlayback();
        if (playing && !dragging && SystemClock.uptimeMillis() >= hideAt) {
            hideControls();
            return;
        }
        playPause.setText(playing ? "Pause" : "Play");
        long duration = playback.duration();
        if (!dragging) {
            long position = playback.position();
            boolean seekable = duration > 0 && playback.isSeekable();
            timeline.setEnabled(seekable);
            timeline.setMax((int) Math.min(Integer.MAX_VALUE, Math.max(1, duration / 1000L)));
            timeline.setKeyProgressIncrement((int) (SEEK_STEP_MS / 1000L));
            timeline.setProgress((int) Math.min(timeline.getMax(), position / 1000L));
            timeLabel.setText(formatTime(position) + " / " + (duration > 0 ? formatTime(duration) : "–:–"));
        }
        controls.postDelayed(refreshControls, 500);
    }

    private void hideControls() {
        if (controls == null) return;
        controls.removeCallbacks(refreshControls);
        controls.setVisibility(View.GONE);
        dragging = false;
    }

    private static String formatTime(long ms) {
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        if (hours > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onPause() {
        liveSelectPressed = false;
        revealKeyPressed = false;
        hideControls();
        if (playback != null) playback.release();
        super.onPause();
    }
}
