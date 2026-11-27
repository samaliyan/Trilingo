package io.github.samaliyan.trilingo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.util.List;
import java.util.Map;

/**
 * Home-screen widget: a search bar that opens a small translate window right on the home screen (with the
 * word of the day under its hint), and three buttons beside it — speak a
 * word, translate what is copied (in a small window over the current app), scan text with the camera. When
 * the widget is taller, the last looked-up words are listed under it (tap one to see it again). Redrawn
 * only when the history or the widget size changes, so it uses no battery.
 */
public final class WidgetProvider extends AppWidgetProvider {
    private static final int[] ROWS = {R.id.row1, R.id.row2, R.id.row3};
    private static final int[] WORDS = {R.id.word1, R.id.word2, R.id.word3};
    private static final int[] MEANINGS = {R.id.meaning1, R.id.meaning2, R.id.meaning3};

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) update(c, m, id);
    }

    @Override public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle newOptions) {
        update(c, m, id);
    }

    /** Redraws every widget of the app: the search widgets and the Word cards (saved words changed too). */
    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) return;
        int[] ids = m.getAppWidgetIds(new ComponentName(c, WidgetProvider.class));
        for (int id : ids) update(c, m, id);
        WordWidget.updateAll(c);
    }

    /** How many recent words fit: none in a one-row widget, up to three when it is taller. */
    static int rowsFor(int heightDp) {
        if (heightDp <= 0) return 0;
        return Math.max(0, Math.min(ROWS.length, (heightDp - 64) / 30));
    }

    private static void update(Context c, AppWidgetManager m, int id) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // Android 12+: one layout per size; the launcher picks the one that fits, also while resizing.
                Map<SizeF, RemoteViews> map = new ArrayMap<>();
                for (int rows = 0; rows <= ROWS.length; rows++) map.put(new SizeF(150, rows == 0 ? 40 : 64 + rows * 30), views(c, rows));
                m.updateAppWidget(id, new RemoteViews(map));
                return;
            }
            Bundle o = m.getAppWidgetOptions(id);
            int h = 0;
            if (o != null) {
                h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
                if (h <= 0) h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            }
            m.updateAppWidget(id, views(c, rowsFor(h)));
        } catch (RuntimeException e) {
            // A launcher that rejects an update must not crash the app; the next change will try again.
        }
    }

    static RemoteViews views(Context c, int rows) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        // the search bar: type a word in a small window right here, without leaving the home screen
        Intent type = new Intent(c, BubbleActivity.class).putExtra(BubbleActivity.EXTRA_TYPE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.bar, PendingIntent.getActivity(c, 13, type, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        // the word of the day under it: one of your saved words with its translation; tap to open it
        Notebook.Card day = WordCards.ofTheDay(Store.notebook(c),
                new Store(c).saveHistory() ? Store.history(c).items() : null, System.currentTimeMillis());
        if (day != null) {
            String back = day.back.split("\n")[0];
            v.setTextViewText(R.id.daily, c.getString(R.string.widget_daily, day.word, back));
            v.setViewVisibility(R.id.daily, View.VISIBLE);
            v.setOnClickPendingIntent(R.id.daily, open(c, day.word, 14));
        } else {
            v.setViewVisibility(R.id.daily, View.GONE);
        }
        Intent voice = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_VOICE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.btn_voice, PendingIntent.getActivity(c, 10, voice, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        // the small translate window reads what is copied as soon as it opens
        Intent paste = new Intent(c, BubbleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.btn_paste, PendingIntent.getActivity(c, 11, paste, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent scan = new Intent(c, ScanActivity.class).putExtra(ScanActivity.EXTRA_CAMERA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.btn_scan, PendingIntent.getActivity(c, 12, scan, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        List<Lookup> items = new Store(c).saveHistory() ? Store.history(c).items() : java.util.Collections.emptyList();
        int shown = Math.min(rows, items.size());
        for (int k = 0; k < ROWS.length; k++) {
            if (k < shown) {
                Lookup l = items.get(k);
                v.setViewVisibility(ROWS[k], View.VISIBLE);
                v.setTextViewText(WORDS[k], l.query);
                v.setTextViewText(MEANINGS[k], l.results.get(0).text);
                v.setOnClickPendingIntent(ROWS[k], open(c, l.query, k + 1));
            } else {
                v.setViewVisibility(ROWS[k], View.GONE);
            }
        }
        v.setViewVisibility(R.id.divider, shown > 0 ? View.VISIBLE : View.GONE);
        colors(c, v);
        return v;
    }

    /** Light or dark as chosen in Settings; "as the phone" keeps the resources (values / values-night). */
    private static void colors(Context c, RemoteViews v) {
        int t = new Store(c).widgetTheme();
        if (t == Store.THEME_SYSTEM) return;
        boolean dark = t == Store.THEME_DARK;
        int text = dark ? 0xFFF1F3F6 : 0xFF12161C, sub = dark ? 0xFFA3ABB7 : 0xFF5F6874, accent = dark ? 0xFF7AB0FF : 0xFF2563EB;
        v.setInt(android.R.id.background, "setBackgroundResource", dark ? R.drawable.widget_bg_dark : R.drawable.widget_bg_light);
        v.setInt(R.id.bar, "setBackgroundResource", dark ? R.drawable.widget_field_dark : R.drawable.widget_field_light);
        v.setImageViewResource(R.id.search_icon, dark ? R.drawable.ic_search_dark : R.drawable.ic_search_light);
        v.setTextColor(R.id.hint, sub);
        v.setTextColor(R.id.daily, accent);
        for (int b : new int[]{R.id.btn_voice, R.id.btn_paste, R.id.btn_scan}) v.setInt(b, "setColorFilter", accent);
        for (int k = 0; k < ROWS.length; k++) {
            v.setTextColor(WORDS[k], text);
            v.setTextColor(MEANINGS[k], sub);
        }
    }

    /** Opens the app with a clean field (text == null) or with a word shown. */
    static PendingIntent open(Context c, String text, int requestCode) {
        Intent it = new Intent(c, MainActivity.class).setAction(MainActivity.ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (text != null) it.putExtra(MainActivity.EXTRA_TEXT, text);
        return PendingIntent.getActivity(c, requestCode, it, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
