package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The one screen: type (or paste, or select in another app) a word and see it in the two other languages.
 * Results come from the history when the word was looked up before (instant, works offline),
 * otherwise from the translation service, 400 ms after the last key press.
 */
public final class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    static final String ACTION_OPEN = "io.github.samaliyan.trilingo.OPEN";
    static final String EXTRA_TEXT = "io.github.samaliyan.trilingo.TEXT";

    private static final long TYPE_DELAY = 400, SAVE_DELAY = 2000;
    private static final int RECENT_SHOWN = 100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui ui;
    private Store store;
    private History history;
    private Engine engine;
    private Updater updater;
    private int themeAtStart;

    private EditText input;
    private ImageButton clearBtn, pasteBtn;
    private ProgressBar busy;
    private LinearLayout content;
    private ScrollView scroll;
    private final TextView[] chips = new TextView[4];

    /** Source language chosen with the chips; null = automatic. */
    private Lang forced;
    /** Increases with every new text; answers for an older text are thrown away. */
    private int generation;
    private String lastQuery = "";
    private Lookup shown;
    private boolean settingText;

    private Lookup pendingSave;
    private final Runnable saveTask = this::saveNow;
    private final Runnable typeTask = () -> lookup(false);

    /** Open the keyboard as soon as the window has focus (it cannot be opened before). */
    private boolean wantKeyboard;

    private TextToSpeech tts;
    private final Set<Lang> speakable = EnumSet.noneOf(Lang.class);

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(this);
        ui = Ui.of(this);
        ui.applyWindow(this);
        themeAtStart = store.theme();
        history = Store.history(this);
        engine = new Engine(Engine.NET);
        updater = new Updater(this, ui);
        if (saved != null) forced = Lang.of(saved.getString("forced"));

        boolean hasText = incomingText(getIntent()) != null;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | (hasText || saved != null ? WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE));
        build();
        updateChips();
        // After a rotation the restored text triggers onTextChanged by itself (shown again from the history).
        if (saved == null) {
            wantKeyboard = !hasText;
            handleIntent(getIntent());
        }

        try {
            tts = new TextToSpeech(getApplicationContext(), this);
        } catch (RuntimeException e) {
            tts = null;
        }
        if (saved == null && store.autoUpdate() && System.currentTimeMillis() - store.lastUpdateCheck() > 12 * 60 * 60_000L) {
            updater.check(true);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (store.theme() != themeAtStart) {
            recreate();
            return;
        }
        if (Text.clean(input.getText().toString()).isEmpty()) showRecent();   // history may have been cleared in Settings
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && wantKeyboard) {
            wantKeyboard = false;
            openKeyboardNow();
        }
    }

    @Override protected void onPause() {
        saveNow();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (forced != null) out.putString("forced", forced.code);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        generation++;
        engine.shutdown();
        updater.destroy();
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
                // engine already gone
            }
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ intents

    /** Text handed to the app: selected text ("Trilingo" in the text menu), shared text, or a widget row. */
    private static String incomingText(Intent it) {
        if (it == null) return null;
        CharSequence cs = null;
        if (Intent.ACTION_PROCESS_TEXT.equals(it.getAction())) cs = it.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        else if (Intent.ACTION_SEND.equals(it.getAction())) cs = it.getCharSequenceExtra(Intent.EXTRA_TEXT);
        else if (it.hasExtra(EXTRA_TEXT)) cs = it.getStringExtra(EXTRA_TEXT);
        if (cs == null) return null;
        String t = Text.clean(cs.toString());
        return t.isEmpty() ? null : t;
    }

    private void handleIntent(Intent it) {
        String text = incomingText(it);
        if (text != null) {
            setInput(text);
            lookup(true);
            hideKeyboard();
        } else if ((it != null && ACTION_OPEN.equals(it.getAction())) || wantKeyboard) {
            // From the widget or the quick-settings tile: a clean field, ready to type.
            forced = null;
            updateChips();
            setInput("");
            showKeyboard();
        }
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);

        // Search field + settings
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ui.dp(12), ui.dp(10), ui.dp(4), ui.dp(4));

        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(ui.outline(ui.surface, ui.line, 26));
        field.setPadding(ui.dp(14), 0, ui.dp(2), 0);
        field.setMinimumHeight(ui.dp(52));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(Icons.of(Icons.Kind.SEARCH, ui.sub));
        field.addView(icon, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));

        input = new EditText(this);
        input.setId(R.id.input);
        input.setBackground(null);
        input.setHint(R.string.input_hint);
        input.setHintTextColor(ui.sub);
        input.setTextColor(ui.text);
        input.setTextSize(18);
        input.setTypeface(Ui.REGULAR);
        input.setPadding(ui.dp(10), ui.dp(12), ui.dp(6), ui.dp(12));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setHorizontallyScrolling(false);      // long text wraps (up to 4 lines) but Enter still means "search"
        input.setMaxLines(4);
        input.setContentDescription(getString(R.string.input_hint));
        Ui.bidi(input);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (!settingText) MainActivity.this.onTextChanged();
            }
        });
        input.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                handler.removeCallbacks(typeTask);
                if (!lastQueryShown()) lookup(true);
                saveNow();
                hideKeyboard();
                return true;
            }
            return false;
        });
        field.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        clearBtn = ui.iconButton(Icons.Kind.CLOSE, ui.sub, getString(R.string.clear));
        clearBtn.setOnClickListener(v -> {
            setInput("");
            onTextChanged();
            showKeyboard();
        });
        field.addView(clearBtn, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));
        pasteBtn = ui.iconButton(Icons.Kind.PASTE, ui.sub, getString(R.string.paste));
        pasteBtn.setOnClickListener(v -> paste());
        field.addView(pasteBtn, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));

        top.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton settings = ui.iconButton(Icons.Kind.SETTINGS, ui.text, getString(R.string.settings));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        root.addView(top);

        // Source language chips
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
        String[] labels = {getString(R.string.auto), Lang.EN.label, Lang.DE.label, Lang.FA.label};
        for (int k = 0; k < 4; k++) {
            final Lang l = k == 0 ? null : Lang.values()[k - 1];
            TextView c = ui.text(14, ui.text, Ui.MEDIUM);
            c.setText(labels[k]);
            c.setGravity(Gravity.CENTER);
            c.setMinHeight(ui.dp(36));
            c.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            c.setClickable(true);
            c.setFocusable(true);
            c.setContentDescription(getString(R.string.from_chip, labels[k]));
            c.setOnClickListener(v -> {
                if (forced == l) return;
                forced = l;
                updateChips();
                if (!Text.clean(input.getText().toString()).isEmpty()) lookup(true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ui.dp(8);
            row.addView(c, lp);
            chips[k] = c;
        }
        hs.addView(row);
        root.addView(hs);

        busy = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busy.setIndeterminate(true);
        busy.setIndeterminateTintList(ColorStateList.valueOf(ui.accent));
        busy.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4));
        bp.leftMargin = ui.dp(16);
        bp.rightMargin = ui.dp(16);
        root.addView(busy, bp);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        updateFieldButtons();
    }

    private void updateChips() {
        for (int k = 0; k < 4; k++) {
            boolean on = (k == 0 && forced == null) || (k > 0 && forced == Lang.values()[k - 1]);
            chips[k].setTextColor(on ? ui.onAccent : ui.text);
            chips[k].setBackground(ui.ripple(on ? ui.round(ui.accent, 18) : ui.outline(ui.surface, ui.line, 18), 18));
            chips[k].setSelected(on);
        }
    }

    private void updateFieldButtons() {
        boolean empty = input.getText().length() == 0;
        clearBtn.setVisibility(empty ? View.GONE : View.VISIBLE);
        pasteBtn.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ typing and looking up

    private void setInput(String t) {
        settingText = true;
        input.setText(t);
        input.setSelection(input.getText().length());
        settingText = false;
        updateFieldButtons();
    }

    private void onTextChanged() {
        updateFieldButtons();
        handler.removeCallbacks(typeTask);
        String q = Text.clean(input.getText().toString());
        if (q.equals(lastQuery) && shown != null) return;
        if (q.isEmpty()) {
            generation++;
            lastQuery = "";
            busy.setVisibility(View.INVISIBLE);
            saveNow();
            showRecent();
            return;
        }
        handler.postDelayed(typeTask, TYPE_DELAY);
    }

    private boolean lastQueryShown() {
        return shown != null && Text.key(shown.query).equals(Text.key(input.getText().toString()));
    }

    /** Looks up the current text: from the history if possible, else from the service. */
    private void lookup(boolean now) {
        handler.removeCallbacks(typeTask);
        final String q = Text.clean(input.getText().toString());
        final int g = ++generation;
        lastQuery = q;
        if (q.isEmpty()) {
            showRecent();
            return;
        }
        if (!Text.hasLetter(q)) {
            busy.setVisibility(View.INVISIBLE);
            showMessage(getString(R.string.no_letters), null, false);
            return;
        }
        Lookup cached = history.find(q, forced);
        if (cached != null) {
            busy.setVisibility(View.INVISIBLE);
            showLookup(cached, forced == null);
            scheduleSave(cached, now);
            return;
        }
        busy.setVisibility(View.VISIBLE);
        final Lang f = forced;
        final int service = store.service();
        new Thread(() -> {
            Lookup res = null;
            Engine.Failure fail = null;
            try {
                res = engine.translate(q, f, service, System.currentTimeMillis());
            } catch (Engine.Failure e) {
                fail = e;
            }
            final Lookup r = res;
            final Engine.Failure x = fail;
            runOnUiThread(() -> {
                if (g != generation || isFinishing() || isDestroyed()) return;
                busy.setVisibility(View.INVISIBLE);
                if (r != null) {
                    showLookup(r, f == null);
                    scheduleSave(r, now);
                } else {
                    boolean offline = (x != null && x.offline) || !online();
                    showMessage(getString(offline ? R.string.error_offline : R.string.error_service),
                            getString(offline ? R.string.error_offline_hint : R.string.error_service_hint), true);
                }
            });
        }, "lookup").start();
    }

    /** The word is saved to the history once the user has stopped typing (or copied it, pressed Enter…). */
    private void scheduleSave(Lookup l, boolean now) {
        handler.removeCallbacks(saveTask);
        pendingSave = l;
        if (now) saveNow();
        else handler.postDelayed(saveTask, SAVE_DELAY);
    }

    private void saveNow() {
        handler.removeCallbacks(saveTask);
        Lookup l = pendingSave;
        pendingSave = null;
        if (l == null || !store.saveHistory()) return;
        history.add(l.withTime(System.currentTimeMillis()));
        WidgetProvider.updateAll(this);
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm != null ? cm.getPrimaryClip() : null;
        CharSequence cs = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        String t = cs != null ? Text.clean(cs.toString()) : "";
        if (t.isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        setInput(t);
        lookup(true);
        hideKeyboard();
    }

    // ------------------------------------------------------------------ showing results

    private void showLookup(Lookup l, boolean detected) {
        shown = l;
        content.removeAllViews();

        LinearLayout src = new LinearLayout(this);
        src.setGravity(Gravity.CENTER_VERTICAL);
        src.setPadding(ui.dp(6), 0, 0, 0);
        TextView from = ui.line(13, ui.sub, Ui.MEDIUM);
        from.setText(getString(detected ? R.string.from_detected : R.string.from_lang, l.source.label));
        src.addView(from, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (speakable.contains(l.source)) {
            ImageButton say = ui.iconButton(Icons.Kind.SPEAK, ui.sub, getString(R.string.speak_lang, l.source.label));
            say.setOnClickListener(v -> speak(l.query, l.source));
            src.addView(say, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        } else {
            src.setMinimumHeight(ui.dp(44));
        }
        content.addView(src);

        for (Lookup.Result r : l.results) content.addView(card(l, r));

        if (Services.NAMES[Services.MYMEMORY].equals(l.service)) {
            TextView via = ui.text(12, ui.sub, Ui.REGULAR);
            via.setText(getString(R.string.via_service, l.service));
            via.setPadding(ui.dp(6), ui.dp(4), 0, 0);
            content.addView(via);
        }
        scroll.scrollTo(0, 0);
    }

    private View card(Lookup l, Lookup.Result r) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(ui.round(ui.surface, 18));
        c.setPadding(ui.dp(18), ui.dp(6), ui.dp(6), ui.dp(18));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView tag = ui.line(12, ui.accent, Ui.MEDIUM);
        tag.setText(r.lang.label.toUpperCase(Locale.ROOT));
        tag.setLetterSpacing(0.08f);
        head.addView(tag, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (speakable.contains(r.lang)) {
            ImageButton say = ui.iconButton(Icons.Kind.SPEAK, ui.sub, getString(R.string.speak_lang, r.lang.label));
            say.setOnClickListener(v -> {
                speak(r.text, r.lang);
                saveNow();
            });
            head.addView(say, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        }
        ImageButton copy = ui.iconButton(Icons.Kind.COPY, ui.sub, getString(R.string.copy_lang, r.lang.label));
        copy.setOnClickListener(v -> copy(r.text));
        head.addView(copy, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        c.addView(head);

        TextView main = ui.text(r.text.length() > 60 ? 18 : 24, ui.text, Ui.REGULAR);
        main.setText(r.text);
        main.setTextIsSelectable(true);
        main.setLineSpacing(0, 1.2f);
        Ui.bidi(main);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.rightMargin = ui.dp(12);
        c.addView(main, mp);

        String sep = r.lang == Lang.FA ? "\u060C " : ", ";
        for (Lookup.Sense s : r.senses) {
            if (s.terms.size() == 1 && s.terms.get(0).equalsIgnoreCase(r.text) && r.senses.size() == 1) break;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(14);
            lp.rightMargin = ui.dp(12);
            if (!s.pos.isEmpty()) {
                TextView pos = ui.line(12, ui.sub, Ui.MEDIUM);
                pos.setText(s.pos.substring(0, 1).toUpperCase(Locale.ROOT) + s.pos.substring(1));
                c.addView(pos, lp);
                lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = ui.dp(4);
                lp.rightMargin = ui.dp(12);
            }
            TextView terms = ui.text(16, ui.text, Ui.REGULAR);
            terms.setText(String.join(sep, s.terms));
            terms.setTextIsSelectable(true);
            terms.setLineSpacing(0, 1.25f);
            Ui.bidi(terms);
            c.addView(terms, lp);
        }

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = ui.dp(12);
        c.setLayoutParams(cp);
        return c;
    }

    private void showMessage(String title, String hint, boolean retry) {
        shown = null;
        content.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(ui.round(ui.surface, 18));
        box.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
        TextView t = ui.text(16, ui.text, Ui.MEDIUM);
        t.setText(title);
        t.setLineSpacing(0, 1.2f);
        box.addView(t);
        if (hint != null) {
            TextView s = ui.text(14, ui.sub, Ui.REGULAR);
            s.setText(hint);
            s.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(6);
            box.addView(s, lp);
        }
        if (retry) {
            TextView b = ui.pill(getString(R.string.retry), true);
            b.setOnClickListener(v -> lookup(true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(14);
            box.addView(b, lp);
        }
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = ui.dp(8);
        content.addView(box, bp);
    }

    /** Empty field: the recent words, or a short introduction when there are none. */
    private void showRecent() {
        shown = null;
        content.removeAllViews();
        List<Lookup> items = history.items();
        if (items.isEmpty() || !store.saveHistory()) {
            intro();
            return;
        }
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(6), ui.dp(8), 0, ui.dp(4));
        TextView t = ui.line(13, ui.accent, Ui.MEDIUM);
        t.setText(R.string.recent);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView clear = ui.text(14, ui.sub, Ui.MEDIUM);
        clear.setText(R.string.clear_history_short);
        clear.setGravity(Gravity.CENTER);
        clear.setMinHeight(ui.dp(40));
        clear.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        clear.setBackground(ui.ripple(null, 0));
        clear.setOnClickListener(v -> confirmClear());
        head.addView(clear);
        content.addView(head);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(ui.round(ui.surface, 18));
        int n = Math.min(items.size(), RECENT_SHOWN);
        for (int k = 0; k < n; k++) {
            final Lookup l = items.get(k);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
            row.setMinimumHeight(ui.dp(60));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(ui.ripple(null, 0));
            TextView q = ui.line(16, ui.text, Ui.REGULAR);
            q.setText(l.query);
            Ui.bidi(q);
            row.addView(q, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView s = ui.line(14, ui.sub, Ui.REGULAR);
            s.setText(l.results.get(0).summary(2));
            Ui.bidi(s);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(4);
            row.addView(s, lp);
            row.setOnClickListener(v -> {
                setInput(l.query);
                lastQuery = Text.clean(l.query);
                generation++;
                showLookup(l, true);
                scheduleSave(l, true);
                hideKeyboard();
            });
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this, ui.dialogTheme())
                        .setMessage(getString(R.string.remove_word, l.query))
                        .setPositiveButton(R.string.remove, (d, w) -> {
                            history.remove(l);
                            WidgetProvider.updateAll(this);
                            showRecent();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });
            list.addView(row);
        }
        content.addView(list);
        TextView tip = ui.text(12, ui.sub, Ui.REGULAR);
        tip.setText(R.string.recent_tip);
        tip.setPadding(ui.dp(6), ui.dp(10), ui.dp(6), 0);
        content.addView(tip);
    }

    private void intro() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(10), ui.dp(28), ui.dp(10), 0);
        TextView t = ui.text(22, ui.text, Ui.MEDIUM);
        t.setText(R.string.intro_title);
        box.addView(t);
        TextView b = ui.text(15, ui.sub, Ui.REGULAR);
        b.setText(R.string.intro_body);
        b.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(10);
        box.addView(b, lp);
        if (canPinWidget(this)) {
            TextView pin = ui.pill(getString(R.string.widget_add_home), false);
            pin.setOnClickListener(v -> pinWidget(this));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pp.topMargin = ui.dp(20);
            box.addView(pin, pp);
        }
        content.addView(box);
    }

    private void confirmClear() {
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setMessage(R.string.clear_history_confirm)
                .setPositiveButton(R.string.clear_history_short, (d, w) -> {
                    history.clear();
                    WidgetProvider.updateAll(this);
                    showRecent();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** True when the phone has a network that reaches the internet. */
    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm != null ? cm.getActiveNetwork() : null;
            NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (RuntimeException e) {
            return true;      // unknown: do not claim "no internet"
        }
    }

    // ------------------------------------------------------------------ widget pinning (also used by Settings)

    static boolean canPinWidget(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        return m != null && m.isRequestPinAppWidgetSupported();
    }

    static void pinWidget(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        boolean ok = false;
        try {
            ok = m != null && m.requestPinAppWidget(new ComponentName(c, WidgetProvider.class), null, null);
        } catch (RuntimeException ignored) {
            ok = false;
        }
        if (!ok) Toast.makeText(c, R.string.widget_add_manual, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ copy, speak, keyboard

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text));
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        saveNow();
    }

    static Locale locale(Lang l) {
        switch (l) {
            case DE: return Locale.GERMANY;
            case FA: return new Locale("fa", "IR");
            default: return Locale.US;
        }
    }

    @Override public void onInit(int status) {
        runOnUiThread(() -> {
            if (isDestroyed() || tts == null || status != TextToSpeech.SUCCESS) return;
            speakable.clear();
            for (Lang l : Lang.values()) {
                try {
                    if (tts.isLanguageAvailable(locale(l)) >= TextToSpeech.LANG_AVAILABLE) speakable.add(l);
                } catch (RuntimeException ignored) {
                    // engine without this language
                }
            }
            if (shown != null && !speakable.isEmpty()) showLookup(shown, forced == null);
        });
    }

    private void speak(String text, Lang l) {
        if (tts == null) return;
        try {
            tts.setLanguage(locale(l));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "trilingo");
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.speak_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** Opens the keyboard now if the window has focus, otherwise as soon as it gets it. */
    private void showKeyboard() {
        input.requestFocus();
        if (hasWindowFocus()) openKeyboardNow();
        else wantKeyboard = true;
    }

    private void openKeyboardNow() {
        input.requestFocus();
        input.post(() -> {
            if (isFinishing()) return;
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) c.show(WindowInsets.Type.ime());
            }
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }
}
