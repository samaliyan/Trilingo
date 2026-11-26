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

    private static final String K_HAPTICS = "haptics", K_ONBOARDED = "onboarded", K_BUBBLE = "bubble";
    private static final String K_SORT = "notebookSort", K_MY_LEVEL = "myLevel", K_DICTATION = "dictationLang";
    /** Keys that describe this phone, not the user's choices: not part of a backup. */
    private static final java.util.Set<String> LOCAL = new java.util.HashSet<>(java.util.Arrays.asList(K_LAST_UPD, K_BUBBLE));

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

    /** A short vibration on right and wrong answers. */
    boolean haptics() {
        return p.getBoolean(K_HAPTICS, true);
    }

    void setHaptics(boolean b) {
        p.edit().putBoolean(K_HAPTICS, b).apply();
    }

    /** The introduction was shown (or the app was already in use before it existed). */
    boolean onboarded() {
        return p.getBoolean(K_ONBOARDED, false);
    }

    void setOnboarded(boolean b) {
        p.edit().putBoolean(K_ONBOARDED, b).apply();
    }

    /** True before the app has saved any setting (a new install). */
    boolean isNew() {
        return p.getAll().isEmpty();
    }

    /** The quick-translate bubble over other apps is switched on. */
    boolean bubble() {
        return p.getBoolean(K_BUBBLE, false);
    }

    void setBubble(boolean b) {
        p.edit().putBoolean(K_BUBBLE, b).apply();
    }

    /** Order of the saved words ({@link Notebook#SORT_NEWEST} …). */
    int notebookSort() {
        return p.getInt(K_SORT, Notebook.SORT_NEWEST);
    }

    void setNotebookSort(int s) {
        p.edit().putInt(K_SORT, s).apply();
    }

    /** The learner's level in a language ("A2"), used to pick new words from a text. */
    String myLevel(Lang l) {
        return p.getString(K_MY_LEVEL + l.code, "A2");
    }

    void setMyLevel(Lang l, String level) {
        p.edit().putString(K_MY_LEVEL + l.code, level).apply();
    }

    /** Language of the listening practice. */
    Lang dictationLang() {
        return Lang.EN.code.equals(p.getString(K_DICTATION, "de")) ? Lang.EN : Lang.DE;
    }

    void setDictationLang(Lang l) {
        p.edit().putString(K_DICTATION, l.code).apply();
    }

    // ------------------------------------------------------------------ backup

    /** All settings as "key" → "b:true" / "i:2" / "l:5" / "f:1.0" / "s:text" (without this phone's own state). */
    java.util.Map<String, String> exportSettings() {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
            Object v = e.getValue();
            if (LOCAL.contains(e.getKey()) || v == null) continue;
            if (v instanceof Boolean) out.put(e.getKey(), "b:" + v);
            else if (v instanceof Integer) out.put(e.getKey(), "i:" + v);
            else if (v instanceof Long) out.put(e.getKey(), "l:" + v);
            else if (v instanceof Float) out.put(e.getKey(), "f:" + v);
            else if (v instanceof String) out.put(e.getKey(), "s:" + v);
        }
        return out;
    }

    /** Restores settings written by {@link #exportSettings}; unknown or damaged values are skipped. */
    void importSettings(java.util.Map<String, String> in) {
        SharedPreferences.Editor ed = p.edit();
        for (java.util.Map.Entry<String, String> e : in.entrySet()) {
            String v = e.getValue();
            if (LOCAL.contains(e.getKey()) || v == null || v.length() < 2 || v.charAt(1) != ':') continue;
            String x = v.substring(2);
            try {
                switch (v.charAt(0)) {
                    case 'b': ed.putBoolean(e.getKey(), Boolean.parseBoolean(x)); break;
                    case 'i': ed.putInt(e.getKey(), Integer.parseInt(x)); break;
                    case 'l': ed.putLong(e.getKey(), Long.parseLong(x)); break;
                    case 'f': ed.putFloat(e.getKey(), Float.parseFloat(x)); break;
                    case 's': ed.putString(e.getKey(), x); break;
                    default: break;
                }
            } catch (NumberFormatException ignored) {
                // skipped
            }
        }
        ed.apply();
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
