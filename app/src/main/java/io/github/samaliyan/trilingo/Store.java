package io.github.samaliyan.trilingo;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Settings, in one SharedPreferences file (writes use apply()). The history lives in its own file
 * ({@link #history}), shared by the app screen and the widget.
 */
final class Store {
    static final int THEME_SYSTEM = 0, THEME_LIGHT = 1, THEME_DARK = 2;

    private static final String FILE = "trilingo";
    private static final String K_THEME = "theme", K_SERVICE = "service", K_SAVE_HISTORY = "saveHistory";
    private static final String K_AUTO_UPD = "autoUpdate", K_LAST_UPD = "lastUpdateCheck";

    private static History history;

    private final SharedPreferences p;

    Store(Context c) {
        p = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** One History object for the whole process (the file is read once). */
    static synchronized History history(Context c) {
        if (history == null) history = new History(new File(c.getApplicationContext().getFilesDir(), "history.json"));
        return history;
    }

    int theme() {
        return p.getInt(K_THEME, THEME_SYSTEM);
    }

    void setTheme(int t) {
        p.edit().putInt(K_THEME, t).apply();
    }

    int service() {
        int s = p.getInt(K_SERVICE, Services.GOOGLE);
        return s == Services.MYMEMORY ? s : Services.GOOGLE;
    }

    void setService(int s) {
        p.edit().putInt(K_SERVICE, s).apply();
    }

    boolean saveHistory() {
        return p.getBoolean(K_SAVE_HISTORY, true);
    }

    void setSaveHistory(boolean b) {
        p.edit().putBoolean(K_SAVE_HISTORY, b).apply();
    }

    boolean autoUpdate() {
        return p.getBoolean(K_AUTO_UPD, true);
    }

    void setAutoUpdate(boolean b) {
        p.edit().putBoolean(K_AUTO_UPD, b).apply();
    }

    long lastUpdateCheck() {
        return p.getLong(K_LAST_UPD, 0);
    }

    void setLastUpdateCheck(long t) {
        p.edit().putLong(K_LAST_UPD, t).apply();
    }
}
