package io.github.samaliyan.trilingo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Translates one text into the two other languages. Pure Java (the network is behind {@link Http},
 * so the logic is unit-tested with recorded answers).
 *
 * Source language: forced by the user, or Persian script → Persian, or detected by Google.
 * For automatic Latin text the three requests (→fa, →en, →de) go out at the same time, so the answer
 * takes one round trip; the two that are needed are kept. If the chosen service fails, the other is tried.
 */
final class Engine {
    interface Http {
        String get(String url, int timeoutMs) throws IOException;
    }

    /** Thrown when no service could translate; {@code offline} when the phone could not connect at all. */
    static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        final boolean offline;

        Failure(boolean offline, Throwable cause) {
            super(offline ? "offline" : "service", cause);
            this.offline = offline;
        }
    }

    static final int TIMEOUT = 9000;

    private final Http http;
    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "translate");
        t.setDaemon(true);          // never keeps the app process (or a test) alive
        return t;
    });

    Engine(Http http) {
        this.http = http;
    }

    void shutdown() {
        pool.shutdownNow();
    }

    /**
     * @param q       cleaned text
     * @param forced  source language chosen by the user, or null for automatic
     * @param service preferred service ({@link Services#GOOGLE} or {@link Services#MYMEMORY})
     */
    Lookup translate(String q, Lang forced, int service, long now) throws Failure {
        IOException first;
        try {
            return service == Services.MYMEMORY ? myMemory(q, forced, now) : google(q, forced, now);
        } catch (IOException e) {
            first = e;
        }
        try {
            return service == Services.MYMEMORY ? google(q, forced, now) : myMemory(q, forced, now);
        } catch (IOException e) {
            throw new Failure(isOffline(first) && isOffline(e), first);
        }
    }

    private static boolean isOffline(Throwable e) {
        while (e != null) {
            if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException
                    || e instanceof java.net.NoRouteToHostException) return true;
            e = e.getCause();
        }
        return false;
    }

    // ------------------------------------------------------------------ Google

    private Lookup google(String q, Lang forced, long now) throws IOException {
        Lang src = forced != null ? forced : Text.hasPersian(q) ? Lang.FA : null;
        if (src != null) {
            Lang[] t = src.targets();
            List<Services.Answer> a = all(gReq(q, src.code, t[0]), gReq(q, src.code, t[1]));
            return make(q, src, a.get(0).result, a.get(1).result, Services.GOOGLE, now, spell(a), base(a));
        }
        // Automatic Latin text: ask for all three at once and keep what fits the detected language.
        List<Services.Answer> a = all(gReq(q, "auto", Lang.FA), gReq(q, "auto", Lang.EN), gReq(q, "auto", Lang.DE));
        Lang det = Lang.of(a.get(0).detected);
        if (det == null || det == Lang.FA) {
            // Detected as another language (e.g. Dutch for a short German word): prefer English or German
            // from Google's candidate list, else guess, then translate again from that language.
            for (String c : a.get(0).detectedCandidates) {
                Lang l = Lang.of(c);
                if (l == Lang.EN || l == Lang.DE) {
                    det = l;
                    break;
                }
            }
            if (det == null || det == Lang.FA) det = Text.guessLatin(q);
            Lang[] t = det.targets();
            List<Services.Answer> b = all(gReq(q, det.code, t[0]), gReq(q, det.code, t[1]));
            return make(q, det, b.get(0).result, b.get(1).result, Services.GOOGLE, now, spell(b), base(b));
        }
        Services.Answer fa = a.get(0), other = det == Lang.EN ? a.get(2) : a.get(1);
        Lang[] t = det.targets();
        Services.Answer r0 = t[0] == Lang.FA ? fa : other, r1 = t[1] == Lang.FA ? fa : other;
        return make(q, det, r0.result, r1.result, Services.GOOGLE, now, spell(a), base(a));
    }

    private Callable<Services.Answer> gReq(String q, String sl, Lang tl) {
        return () -> Services.parseGoogle(http.get(Services.googleUrl(q, sl, tl), TIMEOUT), tl);
    }

    // ------------------------------------------------------------------ MyMemory

    private Lookup myMemory(String q, Lang forced, long now) throws IOException {
        Lang src = forced != null ? forced : Text.hasPersian(q) ? Lang.FA : Text.guessLatin(q);
        Lang[] t = src.targets();
        List<Services.Answer> a = all(mReq(q, src, t[0]), mReq(q, src, t[1]));
        return make(q, src, a.get(0).result, a.get(1).result, Services.MYMEMORY, now, "", "");
    }

    private Callable<Services.Answer> mReq(String q, Lang sl, Lang tl) {
        return () -> Services.parseMyMemory(http.get(Services.myMemoryUrl(q, sl, tl), TIMEOUT), tl);
    }

    // ------------------------------------------------------------------ helpers

    private static Lookup make(String q, Lang src, Lookup.Result a, Lookup.Result b, int service, long now, String spell, String base) {
        List<Lookup.Result> rs = new ArrayList<>();
        rs.add(a);
        rs.add(b);
        return new Lookup(q, src, rs, Services.NAMES[service], now, spell, base);
    }

    /** The first dictionary form any of the answers gives. */
    private static String base(List<Services.Answer> answers) {
        for (Services.Answer x : answers) if (!x.baseForm.isEmpty()) return x.baseForm;
        return "";
    }

    /** The first correction any of the answers gives. */
    private static String spell(List<Services.Answer> answers) {
        for (Services.Answer x : answers) if (!x.suggestion.isEmpty()) return x.suggestion;
        return "";
    }

    @SafeVarargs
    private final List<Services.Answer> all(Callable<Services.Answer>... calls) throws IOException {
        List<Future<Services.Answer>> fs = new ArrayList<>();
        for (Callable<Services.Answer> c : calls) fs.add(pool.submit(c));
        List<Services.Answer> out = new ArrayList<>();
        try {
            for (Future<Services.Answer> f : fs) out.add(f.get());
        } catch (ExecutionException e) {
            for (Future<Services.Answer> f : fs) f.cancel(true);
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(c);
        } catch (InterruptedException e) {
            for (Future<Services.Answer> f : fs) f.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new IOException("closed", e);
        }
        return out;
    }

    // ------------------------------------------------------------------ real network

    /** HTTPS GET with a browser-like user agent; at most 1 MB is read. */
    static final Http NET = (url, timeoutMs) -> {
        HttpURLConnection c = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Trilingo");
            c.setRequestProperty("Accept", "application/json, */*");
            int st = c.getResponseCode();
            if (st != 200) throw new IOException("http " + st);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 1024 * 1024) throw new IOException("too big");
                    bo.write(buf, 0, n);
                }
                return bo.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    };
}
