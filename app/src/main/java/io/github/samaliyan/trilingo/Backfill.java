package io.github.samaliyan.trilingo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * Saved words that have no translation yet (saved from a text, offline, or a word the built-in dictionary
 * does not have) get one from the translation service, one by one in the background. Runs again at the
 * next start for words it could not finish (no internet).
 */
final class Backfill {
    private static boolean running;

    private Backfill() { }

    static synchronized void start(Context context) {
        if (running) return;
        Context app = context.getApplicationContext();
        Notebook nb = Store.notebook(app);
        List<Notebook.Card> todo = new ArrayList<>(nb.withoutBack());
        if (todo.isEmpty()) return;
        running = true;
        int service = new Store(app).service();
        Handler main = new Handler(Looper.getMainLooper());
        Net.POOL.execute(() -> {
            Engine engine = new Engine(Engine.NET);
            try {
                for (Notebook.Card c : todo) {
                    Lookup l;
                    try {
                        l = engine.translate(c.word, c.lang, service, System.currentTimeMillis());
                    } catch (Engine.Failure e) {
                        if (e.offline) break;                // try again at the next start
                        continue;
                    }
                    if (MainActivity.echoes(l)) continue;
                    String back = Notebook.summary(l);
                    main.post(() -> nb.setBack(c.lang, c.word, back));
                    try {
                        Thread.sleep(250);                   // gentle on the free service
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            } finally {
                engine.shutdown();
                synchronized (Backfill.class) {
                    running = false;
                }
            }
        });
    }
}
