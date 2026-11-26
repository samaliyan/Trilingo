package io.github.samaliyan.trilingo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.http.HttpResponseCache;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small network helper for the dictionary, picture and grammar-check services: background threads,
 * a disk cache for answers and pictures (so a word seen once loads instantly and works offline), and
 * a memory cache for decoded pictures.
 */
final class Net {
    private Net() { }

    static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "net");
        t.setDaemon(true);
        return t;
    });
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int TIMEOUT = 12_000;

    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    /** Turns on the HTTP disk cache once per process. */
    static synchronized void init(Context c) {
        if (HttpResponseCache.getInstalled() != null) return;
        try {
            HttpResponseCache.install(new File(c.getApplicationContext().getCacheDir(), "http"), 30L * 1024 * 1024);
        } catch (IOException ignored) {
            // works without the cache
        }
    }

    interface Done<T> {
        void done(T result, Exception error);
    }

    /** Runs {@code job} in the background and gives its result (or error) on the main thread. */
    static <T> void run(java.util.concurrent.Callable<T> job, Done<T> done) {
        POOL.execute(() -> {
            T r = null;
            Exception err = null;
            try {
                r = job.call();
            } catch (Exception e) {
                err = e;
            }
            final T fr = r;
            final Exception fe = err;
            MAIN.post(() -> done.done(fr, fe));
        });
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setRequestProperty("User-Agent", Meaning.UA);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(true);
        return c;
    }

    /** GET as text; "" for a missing page (404). */
    static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code == 404) return "";
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            return new String(read(c.getInputStream()), StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            return "";
        } finally {
            c.disconnect();
        }
    }

    /** POST form data as text. */
    static String post(String url, String form) throws IOException {
        HttpURLConnection c = open(url);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            try (OutputStream o = c.getOutputStream()) {
                o.write(form.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            return new String(read(c.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** A picture scaled down to about {@code maxPx} on its longer side; cached in memory. */
    static Bitmap bitmap(String url, int maxPx) throws IOException {
        Bitmap cached = IMAGES.get(url);
        if (cached != null) return cached;
        byte[] data;
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            data = read(c.getInputStream());
        } finally {
            c.disconnect();
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) throw new IOException("not a picture");
        IMAGES.put(url, b);
        return b;
    }

    static Bitmap cachedBitmap(String url) {
        return IMAGES.get(url);
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
