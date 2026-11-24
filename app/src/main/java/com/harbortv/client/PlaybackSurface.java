package com.harbortv.client;

import android.content.Context;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.Collections;
import java.net.UnknownHostException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;

@OptIn(markerClass = UnstableApi.class)
final class PlaybackSurface {
    private final FrameLayout frame;
    private PlayerView mediaView;
    private ExoPlayer exoPlayer;
    private LibVLC libVlc;
    private MediaPlayer vlcPlayer;

    PlaybackSurface(FrameLayout frame) {
        this.frame = frame;
    }

    void play(String url, boolean preferVlc, Runnable onError) {
        play(url, preferVlc, false, reason -> onError.run());
    }

    void play(String url, boolean preferVlc, boolean replay, Failure onError) {
        attempt(url, preferVlc, replay, replay, null, onError);
    }

    private void attempt(String url, boolean vlc, boolean replay, boolean retry,
                         String earlierError, Failure onError) {
        release();
        Failure failure = reason -> {
            if (retry) attempt(url, !vlc, replay, false, reason, onError);
            else onError.report(reason.equals("VLC could not open the stream.") && earlierError != null
                    ? earlierError : reason);
        };
        if (vlc) {
            try {
                playVlc(url, replay, failure);
                return;
            } catch (RuntimeException | LinkageError unavailable) {
                if (replay) {
                    failure.report("VLC could not open the stream.");
                    return;
                }
                release(); // Keep Media3 available when LibVLC cannot initialise.
            }
        }
        playMedia3(url, replay, failure);
    }

    private void playMedia3(String url, boolean replay, Failure onError) {
        Context context = frame.getContext();
        mediaView = new PlayerView(context);
        mediaView.setUseController(false);
        mediaView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        frame.addView(mediaView, fill());
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Portal.USER_AGENT).setAllowCrossProtocolRedirects(replay);
        exoPlayer = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http)).build();
        ExoPlayer current = exoPlayer;
        mediaView.setPlayer(current);
        current.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                frame.post(() -> {
                    if (exoPlayer == current) onError.report(mediaError(error));
                });
            }
        });
        current.setTrackSelectionParameters(current.getTrackSelectionParameters().buildUpon()
                .setMaxVideoSize(1920, 1080).build());
        current.setMediaItem(MediaItem.fromUri(url));
        current.prepare();
        current.play();
    }

    private void playVlc(String url, boolean replay, Failure onError) {
        Context context = frame.getContext();
        libVlc = new LibVLC(context, Collections.emptyList());
        vlcPlayer = new MediaPlayer(libVlc);
        MediaPlayer current = vlcPlayer;
        VLCVideoLayout view = new VLCVideoLayout(context);
        frame.addView(view, fill());
        current.attachViews(view, null, false, false);
        current.setEventListener(new MediaPlayer.EventListener() {
            private boolean started;

            @Override
            public void onEvent(MediaPlayer.Event event) {
                if (event.type == MediaPlayer.Event.TimeChanged && event.getTimeChanged() > 0) started = true;
                if (event.type != MediaPlayer.Event.EncounteredError
                        && !(replay && !started && event.type == MediaPlayer.Event.EndReached)) return;
                frame.post(() -> {
                    if (vlcPlayer == current) onError.report("VLC could not open the stream.");
                });
            }
        });
        Media media = new Media(libVlc, Uri.parse(url));
        try {
            media.addOption(":http-user-agent=" + Portal.USER_AGENT);
            current.setMedia(media);
        } finally {
            media.release();
        }
        current.play();
    }

    private static String mediaError(PlaybackException error) {
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException) {
                int status = ((HttpDataSource.InvalidResponseCodeException) cause).responseCode;
                return "The stream server returned HTTP " + status + ".";
            }
            if (cause instanceof UnknownHostException) return "The stream server name could not be resolved.";
            if (cause instanceof SocketTimeoutException) return "The stream server timed out.";
            if (cause instanceof ConnectException) return "The connection to the stream server could not be opened.";
            if (cause instanceof SSLException) return "The secure connection to the stream server failed.";
        }
        return "Media3 playback error (" + error.getErrorCodeName() + ").";
    }

    interface Failure {
        void report(String reason);
    }

    void seekBy(long offsetMs) {
        seekTo(position() + offsetMs);
    }

    void seekTo(long positionMs) {
        if (!isSeekable()) return;
        long duration = duration();
        long target = Math.max(0, duration > 0 ? Math.min(positionMs, duration) : positionMs);
        if (vlcPlayer != null) vlcPlayer.setTime(target);
        else exoPlayer.seekTo(target);
    }

    boolean isSeekable() {
        if (vlcPlayer != null) return vlcPlayer.isSeekable();
        return exoPlayer != null && exoPlayer.isCurrentMediaItemSeekable();
    }

    long position() {
        if (vlcPlayer != null) return Math.max(0, vlcPlayer.getTime());
        return exoPlayer == null ? 0 : Math.max(0, exoPlayer.getCurrentPosition());
    }

    long duration() {
        if (vlcPlayer != null) return Math.max(0, vlcPlayer.getLength());
        return exoPlayer == null ? 0 : Math.max(0, exoPlayer.getDuration());
    }

    boolean wantsPlayback() {
        if (vlcPlayer != null) return vlcPlayer.isPlaying();
        return exoPlayer != null && exoPlayer.getPlayWhenReady();
    }

    boolean togglePause() {
        if (vlcPlayer != null) {
            if (vlcPlayer.isPlaying()) vlcPlayer.pause();
            else vlcPlayer.play();
            return true;
        }
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(!exoPlayer.getPlayWhenReady());
            return true;
        }
        return false;
    }

    void release() {
        if (vlcPlayer != null) {
            MediaPlayer current = vlcPlayer;
            vlcPlayer = null;
            current.setEventListener(null);
            try {
                current.detachViews();
            } finally {
                current.release();
            }
        }
        if (libVlc != null) {
            libVlc.release();
            libVlc = null;
        }
        if (exoPlayer != null) {
            mediaView.setPlayer(null);
            exoPlayer.release();
            exoPlayer = null;
            mediaView = null;
        }
        frame.removeAllViews();
    }

    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
