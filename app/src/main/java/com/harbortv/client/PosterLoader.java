package com.harbortv.client;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class PosterLoader {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32));
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String url, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
    private final Map<String, List<ImageView>> waiting = new HashMap<>();

    void bind(ImageView image, String url) {
        image.setTag(url);
        image.setImageDrawable(null);
        if (url.isEmpty()) return;
        Bitmap bitmap = cache.get(url);
        if (bitmap != null) {
            image.setImageBitmap(bitmap);
            return;
        }
        List<ImageView> listeners = waiting.get(url);
        if (listeners != null) {
            if (!listeners.contains(image)) listeners.add(image);
            return;
        }
        listeners = new ArrayList<>();
        listeners.add(image);
        waiting.put(url, listeners);
        try {
            requests.execute(() -> {
                Bitmap result = fetch(url);
                main.post(() -> {
                    List<ImageView> views = waiting.remove(url);
                    if (views == null || result == null) return;
                    cache.put(url, result);
                    for (ImageView view : views) {
                        if (url.equals(view.getTag())) view.setImageBitmap(result);
                    }
                });
            });
        } catch (RejectedExecutionException error) {
            waiting.remove(url);
        }
    }

    void close() {
        requests.shutdownNow();
        waiting.clear();
        cache.evictAll();
    }

    private static Bitmap fetch(String url) {
        try {
            for (int redirect = 0; redirect < 4; redirect++) {
                URI uri = Portal.httpUri(url);
                HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
                try {
                    connection.setConnectTimeout(3500);
                    connection.setReadTimeout(4500);
                    connection.setInstanceFollowRedirects(false);
                    int code = connection.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) return null;
                        url = uri.resolve(location).toASCIIString();
                        continue;
                    }
                    if (code != 200) return null;
                    try (InputStream body = connection.getInputStream();
                         ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = body.read(buffer)) != -1) {
                            if (bytes.size() + count > 3 * 1024 * 1024) return null;
                            bytes.write(buffer, 0, count);
                        }
                        byte[] data = bytes.toByteArray();
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, options);
                        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
                        int longest = Math.max(options.outWidth, options.outHeight);
                        options.inSampleSize = 1;
                        while (longest / options.inSampleSize > 640) options.inSampleSize *= 2;
                        options.inJustDecodeBounds = false;
                        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
                    }
                } finally {
                    connection.disconnect();
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // Unavailable artwork leaves the card placeholder visible.
        }
        return null;
    }
}
