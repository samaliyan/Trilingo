package io.github.samaliyan.trilingo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;

/**
 * Home-screen "Word card": review saved words without opening the app. The word is shown; Show turns the
 * card over (its translation); Again or Got it answer it, exactly as in Review, and the next due word
 * comes. With nothing due, Next word goes through the saved words; with none saved, the recent ones.
 * Redrawn when it is tapped, when words are saved, and every few hours (a word becomes due).
 */
public final class WordWidget extends AppWidgetProvider {
    static final String ACTION_SHOW = "io.github.samaliyan.trilingo.CARD_SHOW";
    static final String ACTION_AGAIN = "io.github.samaliyan.trilingo.CARD_AGAIN";
    static final String ACTION_GOOD = "io.github.samaliyan.trilingo.CARD_GOOD";
    static final String ACTION_NEXT = "io.github.samaliyan.trilingo.CARD_NEXT";
    private static final String PREFS = "word_card", K_KEY = "key", K_SHOWN = "shown";

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) update(c, m, id, false);
    }

    @Override public void onDeleted(Context c, int[] ids) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int id : ids) e.remove(K_KEY + id).remove(K_SHOWN + id);
        e.apply();
    }

    @Override public void onReceive(Context c, Intent it) {
        String a = it.getAction();
        int id = it.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || a == null || !a.startsWith("io.github.samaliyan.trilingo.CARD_")) {
            super.onReceive(c, it);
            return;
        }
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        SharedPreferences p = prefs(c);
        String key = p.getString(K_KEY + id, null);
        if (ACTION_SHOW.equals(a)) {
            p.edit().putBoolean(K_SHOWN + id, true).apply();
            update(c, m, id, false);
            return;
        }
        if (ACTION_AGAIN.equals(a) || ACTION_GOOD.equals(a)) {
            Notebook nb = Store.notebook(c);
            long now = System.currentTimeMillis();
            for (Notebook.Card card : nb.due(null, null, now, 500)) {
                if (card.key().equals(key)) {
                    nb.answer(card, ACTION_AGAIN.equals(a) ? Notebook.AGAIN : Notebook.GOOD, now);
                    break;
                }
            }
        }
        p.edit().putBoolean(K_SHOWN + id, false).apply();
        update(c, m, id, true);
        // other Word cards show the same queue
        for (int other : ids(c)) if (other != id) update(c, m, other, false);
    }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) return;
        for (int id : ids(c)) update(c, m, id, false);
    }

    private static int[] ids(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return m == null ? new int[0] : m.getAppWidgetIds(new ComponentName(c, WordWidget.class));
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void update(Context c, AppWidgetManager m, int id, boolean next) {
        try {
            SharedPreferences p = prefs(c);
            String current = p.getString(K_KEY + id, null);
            java.util.List<Lookup> recent = new Store(c).saveHistory() ? Store.history(c).items() : null;
            WordCards.Pick pick = WordCards.pick(Store.notebook(c), recent, System.currentTimeMillis(), current, next);
            String key = pick.card == null ? null : pick.card.key();
            boolean shown = key != null && key.equals(current) && p.getBoolean(K_SHOWN + id, false);
            p.edit().putString(K_KEY + id, key).putBoolean(K_SHOWN + id, shown).apply();
            m.updateAppWidget(id, views(c, id, pick, shown));
        } catch (RuntimeException e) {
            // A launcher that rejects an update must not crash the app; the next change will try again.
        }
    }

    static RemoteViews views(Context c, int id, WordCards.Pick pick, boolean shown) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_card);
        if (pick.card == null) {
            v.setTextViewText(R.id.card_title, c.getString(R.string.app_name));
            v.setTextViewText(R.id.card_lang, "");
            v.setTextViewText(R.id.card_word, c.getString(R.string.widget_card_open));
            v.setTextViewText(R.id.card_back, c.getString(R.string.widget_card_empty));
            v.setViewVisibility(R.id.card_back, View.VISIBLE);
            v.setOnClickPendingIntent(R.id.card_word, WidgetProvider.open(c, null, 200 + id));
            buttons(v, false, false, false);
            colors(c, v);
            return v;
        }
        Notebook.Card card = pick.card;
        v.setTextViewText(R.id.card_title, pick.mode == WordCards.DUE ? c.getString(R.string.widget_card_due, pick.due)
                : pick.mode == WordCards.PRACTICE ? c.getString(R.string.widget_card_practice) : c.getString(R.string.widget_card_recent));
        v.setTextViewText(R.id.card_lang, card.lang.label);
        v.setTextViewText(R.id.card_word, card.word);
        v.setTextViewText(R.id.card_back, shown ? card.back : "");
        v.setViewVisibility(R.id.card_back, View.VISIBLE);
        v.setContentDescription(R.id.card_word, card.word + ", " + card.lang.label);
        v.setOnClickPendingIntent(R.id.card_word, WidgetProvider.quick(c, card.word, 300 + id));
        v.setOnClickPendingIntent(R.id.card_show, action(c, ACTION_SHOW, id));
        v.setOnClickPendingIntent(R.id.card_again, action(c, ACTION_AGAIN, id));
        v.setOnClickPendingIntent(R.id.card_good, action(c, pick.mode == WordCards.DUE ? ACTION_GOOD : ACTION_NEXT, id));
        v.setTextViewText(R.id.card_good, c.getString(pick.mode == WordCards.DUE ? R.string.widget_card_good : R.string.widget_card_next));
        buttons(v, !shown, shown && pick.mode == WordCards.DUE, shown);
        colors(c, v);
        return v;
    }

    private static void buttons(RemoteViews v, boolean show, boolean again, boolean good) {
        v.setViewVisibility(R.id.card_show, show ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.card_again, again ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.card_gap, again && good ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.card_good, good ? View.VISIBLE : View.GONE);
    }

    private static PendingIntent action(Context c, String action, int id) {
        Intent it = new Intent(c, WordWidget.class).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        int code = id * 8 + (ACTION_SHOW.equals(action) ? 1 : ACTION_AGAIN.equals(action) ? 2 : ACTION_GOOD.equals(action) ? 3 : 4);
        return PendingIntent.getBroadcast(c, code, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Light or dark as chosen in Settings (the same choice as the search widget). */
    private static void colors(Context c, RemoteViews v) {
        int t = new Store(c).widgetTheme();
        if (t == Store.THEME_SYSTEM) return;
        boolean dark = t == Store.THEME_DARK;
        int text = dark ? 0xFFF1F3F6 : 0xFF12161C, sub = dark ? 0xFFA3ABB7 : 0xFF5F6874, accent = dark ? 0xFF7AB0FF : 0xFF2563EB;
        v.setInt(android.R.id.background, "setBackgroundResource", dark ? R.drawable.widget_bg_dark : R.drawable.widget_bg_light);
        v.setTextColor(R.id.card_title, accent);
        v.setTextColor(R.id.card_lang, sub);
        v.setTextColor(R.id.card_word, text);
        v.setTextColor(R.id.card_back, sub);
        v.setTextColor(R.id.card_again, text);
        v.setInt(R.id.card_again, "setBackgroundResource", dark ? R.drawable.widget_field_dark : R.drawable.widget_field_light);
    }
}
