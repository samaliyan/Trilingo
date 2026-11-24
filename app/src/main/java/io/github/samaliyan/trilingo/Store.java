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
    private static final String K_SCALE = "textScale", K_WIDGET_THEME = "widgetTheme", K_MEANING_FIRST = "meaningFirst";
    private static final String K_VOICE = "voiceLang", K_REVIEW_RECENT = "reviewRecent";
    static final float[] SCALES = {0.9f, 1f, 1.15f, 1.3f};
    private static Notebook notebook;
    private static final String K_EXPLAIN = "grammarExplain", K_GRAMMAR_LANG = "grammarLang";

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

    /** Saved words and review progress, one object per process. */
    static synchronized Notebook notebook(Context c) {
        if (notebook == null) notebook = new Notebook(new File(c.getApplicationContext().getFilesDir(), "notebook.json"));
        return notebook;
    }

    /** Text size inside the app: index into {@link #SCALES} (1 = normal). */
    int textScale() {
        int i = p.getInt(K_SCALE, 1);
        return i < 0 || i >= SCALES.length ? 1 : i;
    }

    void setTextScale(int i) {
        p.edit().putInt(K_SCALE, i).apply();
    }

    /** Widget colours: {@link #THEME_SYSTEM} (as the phone), light or dark. */
    int widgetTheme() {
        return p.getInt(K_WIDGET_THEME, THEME_SYSTEM);
    }

    void setWidgetTheme(int t) {
        p.edit().putInt(K_WIDGET_THEME, t).apply();
    }

    /** Show the meaning in the word's own language first (translations behind a button). */
    boolean meaningFirst() {
        return p.getBoolean(K_MEANING_FIRST, false);
    }

    void setMeaningFirst(boolean b) {
        p.edit().putBoolean(K_MEANING_FIRST, b).apply();
    }

    /** Last language chosen for voice search. */
    Lang voiceLang() {
        Lang l = Lang.of(p.getString(K_VOICE, "de"));
        return l == null ? Lang.DE : l;
    }

    void setVoiceLang(Lang l) {
        p.edit().putString(K_VOICE, l.code).apply();
    }

    /** Review also recently looked-up words (not only saved ones). */
    boolean reviewRecent() {
        return p.getBoolean(K_REVIEW_RECENT, true);
    }

    void setReviewRecent(boolean b) {
        p.edit().putBoolean(K_REVIEW_RECENT, b).apply();
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

    /** Language the grammar lessons are explained in (Persian, English or German). */
    Lang explainLang() {
        Lang l = Lang.of(p.getString(K_EXPLAIN, "fa"));
        return l == null ? Lang.FA : l;
    }

    void setExplainLang(Lang l) {
        p.edit().putString(K_EXPLAIN, l.code).apply();
    }

    /** Which grammar the Grammar screen shows: German or English. */
    Lang grammarLang() {
        return Lang.EN.code.equals(p.getString(K_GRAMMAR_LANG, "de")) ? Lang.EN : Lang.DE;
    }

    void setGrammarLang(Lang l) {
        p.edit().putString(K_GRAMMAR_LANG, l.code).apply();
    }
}
