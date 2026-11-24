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
public final class MainActivity extends Activity implements TextToSpeech.OnInitListener, DetailSheets.Host {
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
    private DetailSheets sheets;
    private int themeAtStart, scaleAtStart;

    private EditText input;
    private ImageButton clearBtn, pasteBtn;
    private ProgressBar busy;
    private LinearLayout content;
    private HorizontalScrollView suggestScroll;
    private LinearLayout suggestRow;
    private ScrollView scroll;
    private final TextView[] chips = new TextView[4];

    /** Source language chosen with the chips; null = automatic. */
    private Lang forced;
    /** Increases with every new text; answers for an older text are thrown away. */
    private int generation;
    private String lastQuery = "";
    private Lookup shown;
    /** Key of the typed text the shown result answers (differs from shown.query after a spelling correction). */
    private String shownFor = "";
    private boolean shownCorrected;
    private boolean settingText;

    private Lookup pendingSave;
    private final Runnable saveTask = this::saveNow;
    private final Runnable typeTask = () -> lookup(false);

    /** Open the keyboard as soon as the window has focus (it cannot be opened before). */
    private boolean wantKeyboard;

    private TextToSpeech tts;
    private Voice voice;
    private MeaningView meaningView;
    private ImageButton micBtn;
    private static final int VOICE_REQUEST = 52;
    private Lang voiceLang;
    /** Other spellings the speech recognizer heard, shown above the result ("Did you say …"). */
    private List<String> voiceAlternatives = new java.util.ArrayList<>();
    private final Set<Lang> speakable = EnumSet.noneOf(Lang.class);

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(this);
        ui = Ui.of(this);
        ui.applyWindow(this);
        themeAtStart = store.theme();
        scaleAtStart = store.textScale();
        history = Store.history(this);
        engine = new Engine(Engine.NET);
        updater = new Updater(this, ui);
        sheets = new DetailSheets(this, ui, this);
        Net.init(this);
        voice = new Voice(this);
        meaningView = new MeaningView(this, ui, new MeaningView.Host() {
            @Override public boolean canSpeak(Lang l) {
                return speakable.contains(l);
            }

            @Override public void speak(String text, Lang l) {
                MainActivity.this.speak(text, l);
            }

            @Override public void openWord(String word) {
                MainActivity.this.openWord(word);
            }
        }, voice);
        // Builds the irregular-verb index once, off the screen thread, so the first result shows without delay.
        new Thread(() -> GermanVerbs.isKnownForm("habe"), "verbs").start();
        if (saved != null) forced = Lang.of(saved.getString("forced"));

        boolean hasText = incomingText(getIntent()) != null;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | (hasText || saved != null ? WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                : WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE));
        build();
        updateChips();
        // Word lists and German nouns (loaded once per process, in the background). The callback may run
        // at once when they are already loaded, so it comes after build(); it posts to the screen thread.
        Lexicon.load(this, () -> handler.post(() -> {
            if (isDestroyed() || input == null) return;
            if (shown != null) showLookup(shown, forced == null, shownCorrected);
            if (input.hasFocus()) updateSuggestions();
        }));
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
        if (store.theme() != themeAtStart || store.textScale() != scaleAtStart) {
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
        voice.release();
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
        micBtn = ui.iconButton(Icons.Kind.MIC, ui.sub, getString(R.string.voice_search));
        micBtn.setOnClickListener(v -> voiceSearch());
        field.addView(micBtn, new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)));

        top.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton grammar = ui.iconButton(Icons.Kind.BOOK, ui.text, getString(R.string.grammar_open));
        grammar.setOnClickListener(v -> startActivity(new Intent(this, GrammarActivity.class)));
        top.addView(grammar, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        ImageButton settings = ui.iconButton(Icons.Kind.SETTINGS, ui.text, getString(R.string.settings));
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(48)));
        root.addView(top);

        // Word suggestions while typing (dicti → dictionary), right under the field
        suggestScroll = new HorizontalScrollView(this);
        suggestScroll.setHorizontalScrollBarEnabled(false);
        suggestRow = new LinearLayout(this);
        suggestRow.setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2));
        suggestScroll.addView(suggestRow);
        suggestScroll.setVisibility(View.GONE);
        root.addView(suggestScroll);

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
                updateSuggestions();
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
        // Suggestions belong to typing: when the keyboard closes (Back, scrolling …) they go away.
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            android.graphics.Rect r = new android.graphics.Rect();
            root.getWindowVisibleDisplayFrame(r);
            boolean keyboard = root.getRootView().getHeight() - r.bottom > ui.dp(150);
            if (!keyboard && suggestScroll.getVisibility() == View.VISIBLE) suggestScroll.setVisibility(View.GONE);
        });
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
        voiceAlternatives.clear();
        updateFieldButtons();
        updateSuggestions();
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

    /** Shows the words that start with what is being typed; tapping one translates it. */
    private void updateSuggestions() {
        List<String> words = input.hasFocus() ? Lexicon.suggestions(input.getText().toString(), forced, 6) : new java.util.ArrayList<>();
        suggestRow.removeAllViews();
        for (String w : words) {
            TextView c = ui.text(15, ui.text, Ui.REGULAR);
            c.setText(w);
            c.setGravity(Gravity.CENTER);
            c.setMinHeight(ui.dp(36));
            c.setPadding(ui.dp(14), 0, ui.dp(14), 0);
            c.setBackground(ui.ripple(ui.round(ui.accentSoft, 18), 18));
            c.setContentDescription(getString(R.string.suggestion_desc, w));
            c.setOnClickListener(v -> {
                setInput(w);
                suggestScroll.setVisibility(View.GONE);
                lookup(true);
                hideKeyboard();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ui.dp(8);
            lp.topMargin = ui.dp(4);
            lp.bottomMargin = ui.dp(4);
            suggestRow.addView(c, lp);
        }
        suggestScroll.setVisibility(words.isEmpty() ? View.GONE : View.VISIBLE);
        suggestScroll.scrollTo(0, 0);
    }

    private boolean lastQueryShown() {
        return shown != null && shownFor.equals(Text.key(input.getText().toString()));
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
        // German typed without ä/ö/ü/ß (mude → müde): fixed before anything else, without the network.
        Umlauts.Fix uf = Lexicon.umlauts(q, forced);
        final boolean umlautFixed = uf != null && uf.sure;
        final String umlautHint = uf != null && !uf.sure ? uf.text : null;
        final String query = umlautFixed ? uf.text : q;

        Lookup cached = history.find(query, forced);
        if (cached != null) {
            busy.setVisibility(View.INVISIBLE);
            showLookup(cached, forced == null, umlautFixed);
            shownFor = Text.key(q);
            scheduleSave(cached, now);
            return;
        }
        busy.setVisibility(View.VISIBLE);
        final Lang f = forced;
        // Automatic: a word only German (or only English) has is decided here; the rest by the service.
        final Lang source = f != null ? f : Lexicon.guessLatin(query);
        final int service = store.service();
        new Thread(() -> {
            Lookup res = null;
            Engine.Failure fail = null;
            boolean corrected = umlautFixed;
            try {
                res = engine.translate(query, source, service, System.currentTimeMillis());
                // No correction from the service for a word it does not know: the closest word of the built-in list.
                if (!res.hasSuggestion() && echoes(res)) {
                    String near = Lexicon.closest(query, res.source);
                    if (near != null) res = res.withSuggestion(near);
                }
                if (!res.hasSuggestion() && umlautHint != null) res = res.withSuggestion(umlautHint);
                // Misspelled and the "translation" only repeats it: show the corrected word's translation instead.
                if (res.hasSuggestion() && echoes(res)) {
                    try {
                        Lookup fixed = engine.translate(res.suggestion, f != null ? f : Lexicon.guessLatin(res.suggestion), service, System.currentTimeMillis());
                        res = fixed;
                        corrected = true;
                    } catch (Engine.Failure ignored) {
                        // keep the first answer with its "Did you mean"
                    }
                }
            } catch (Engine.Failure e) {
                fail = e;
            }
            final Lookup r = res;
            final Engine.Failure x = fail;
            final boolean fixedWord = corrected;
            runOnUiThread(() -> {
                if (g != generation || isFinishing() || isDestroyed()) return;
                busy.setVisibility(View.INVISIBLE);
                if (r != null) {
                    showLookup(r, f == null, fixedWord);
                    shownFor = Text.key(q);
                    scheduleSave(r, now);
                } else {
                    boolean offline = (x != null && x.offline) || !online();
                    showMessage(getString(offline ? R.string.error_offline : R.string.error_service),
                            getString(offline ? R.string.error_offline_hint : R.string.error_service_hint), true);
                }
            });
        }, "lookup").start();
    }

    /** True when every result is just the typed text again (the service did not know the word). */
    static boolean echoes(Lookup l) {
        for (Lookup.Result r : l.results) if (!Text.key(r.text).equals(Text.key(l.query))) return false;
        return true;
    }

    /** The word is saved to the history once the user has stopped typing (or copied it, pressed Enter…). */
    private void scheduleSave(Lookup l, boolean now) {
        handler.removeCallbacks(saveTask);
        // A word that is probably misspelled ("Did you mean …") is not kept in the history.
        pendingSave = l.hasSuggestion() ? null : l;
        if (pendingSave == null) return;
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

    /**
     * @param corrected the typed text was misspelled and unknown, so {@code l} is the corrected word's lookup
     */
    private void showLookup(Lookup l, boolean detected, boolean corrected) {
        shown = l;
        shownCorrected = corrected;
        content.removeAllViews();

        LinearLayout src = new LinearLayout(this);
        src.setGravity(Gravity.CENTER_VERTICAL);
        src.setPadding(ui.dp(6), 0, 0, 0);
        src.setMinimumHeight(ui.dp(44));
        TextView from = ui.line(13, ui.sub, Ui.MEDIUM);
        from.setText(getString(detected ? R.string.from_detected : R.string.from_lang, l.source.label));
        src.addView(from);
        String level = MeaningView.applies(l) ? Lexicon.level(l.query, l.source) : null;
        if (level != null) {
            TextView lv = ui.text(12, ui.accent, Ui.MEDIUM);
            lv.setText(level);
            lv.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
            lv.setBackground(ui.round(ui.accentSoft, 10));
            lv.setContentDescription(getString(R.string.level_desc, level, Levels.name(level)));
            lv.setTooltipText(level + " · " + Levels.name(level));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = ui.dp(8);
            src.addView(lv, lp);
        }
        src.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (MeaningView.applies(l) && !l.results.isEmpty()) src.addView(starButton(l), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        if (speakable.contains(l.source)) {
            ImageButton say = ui.iconButton(Icons.Kind.SPEAK, ui.sub, getString(R.string.speak_lang, l.source.label));
            say.setOnClickListener(v -> speak(l.query, l.source));
            src.addView(say, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        }
        content.addView(src);

        if (!voiceAlternatives.isEmpty()) content.addView(alternativesRow(l));
        View nounRow = typedNounRow(l);
        if (nounRow != null) content.addView(nounRow);
        if (corrected) content.addView(correctedRow(l.query));
        else if (l.hasSuggestion()) content.addView(suggestionRow(l.suggestion));

        boolean meaning = MeaningView.applies(l);
        LinearLayout picture = column(), meaningBox = column();
        if (meaning) content.addView(modeSwitch(l, detected, corrected));
        View actions = actionRow(l);
        if (meaning && store.meaningFirst()) {
            // learn from the meaning first; the translation is one tap away
            content.addView(picture);
            content.addView(meaningBox);
            LinearLayout hidden = column();
            hidden.setVisibility(View.GONE);
            for (Lookup.Result r : l.results) hidden.addView(card(l, r));
            TextView reveal = ui.pill(getString(R.string.show_translation), true);
            reveal.setOnClickListener(v -> {
                reveal.setVisibility(View.GONE);
                hidden.setVisibility(View.VISIBLE);
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48));
            rp.bottomMargin = ui.dp(12);
            content.addView(reveal, rp);
            content.addView(hidden);
            if (actions != null) content.addView(actions);
        } else {
            // translation, its tools (examples, conjugation …), then the picture and the meaning
            for (Lookup.Result r : l.results) content.addView(card(l, r));
            if (actions != null) content.addView(actions);
            if (meaning) content.addView(picture);
            if (meaning) content.addView(meaningBox);
        }

        if (Services.NAMES[Services.MYMEMORY].equals(l.service)) {
            TextView via = ui.text(12, ui.sub, Ui.REGULAR);
            via.setText(getString(R.string.via_service, l.service));
            via.setPadding(ui.dp(6), ui.dp(4), 0, 0);
            content.addView(via);
        }
        if (meaning) meaningView.fill(l, picture, meaningBox);
        scroll.scrollTo(0, 0);
    }

    // ------------------------------------------------------------------ voice search

    /** Speak a word you heard: the phone's speech recognition writes it (in the chosen language). */
    private void voiceSearch() {
        Intent probe = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        if (probe.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        if (forced != null) {
            startVoice(forced);
            return;
        }
        Lang[] langs = {Lang.EN, Lang.DE, Lang.FA};
        String[] names = {Lang.EN.label, Lang.DE.label, Lang.FA.label};
        int cur = java.util.Arrays.asList(langs).indexOf(store.voiceLang());
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle(R.string.voice_which)
                .setSingleChoiceItems(names, cur, (d, i) -> {
                    d.dismiss();
                    store.setVoiceLang(langs[i]);
                    startVoice(langs[i]);
                })
                .show();
    }

    @SuppressWarnings("deprecation")
    private void startVoice(Lang l) {
        Intent it = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        String tag = l == Lang.DE ? "de-DE" : l == Lang.EN ? "en-US" : "fa-IR";
        it.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, tag);
        it.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag);
        it.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        it.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt, l.label));
        voiceLang = l;
        try {
            startActivityForResult(it, VOICE_REQUEST);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE_REQUEST || result != RESULT_OK || data == null) return;
        java.util.ArrayList<String> heard = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        List<String> alt = new java.util.ArrayList<>();
        for (String h : heard) {
            String c = Text.clean(h);
            boolean dup = false;
            for (String a : alt) dup |= a.equalsIgnoreCase(c);
            if (!c.isEmpty() && !dup) alt.add(c);
        }
        if (alt.isEmpty()) return;
        if (voiceLang != null) {
            forced = voiceLang;
            updateChips();
        }
        voiceAlternatives = alt.size() > 1 ? alt : new java.util.ArrayList<>();
        setInput(alt.get(0));
        lookup(true);
        hideKeyboard();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == Voice.PERMISSION_REQUEST)
            voice.permissionResult(results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    /** "You said: … · or: a, b" — tap another spelling the recognizer heard. */
    private View alternativesRow(Lookup l) {
        LinearLayout box = column();
        box.setPadding(ui.dp(6), 0, 0, ui.dp(10));
        TextView h = ui.line(13, ui.sub, Ui.MEDIUM);
        h.setText(R.string.voice_or);
        box.addView(h);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (String a : voiceAlternatives) {
            if (Text.key(a).equals(Text.key(l.query))) continue;
            TextView chip = ui.pill(a, false);
            chip.setContentDescription(getString(R.string.suggestion_desc, a));
            chip.setOnClickListener(v -> {
                setInput(a);
                lookup(true);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ui.dp(8);
            lp.topMargin = ui.dp(6);
            row.addView(chip, lp);
        }
        hs.addView(row);
        box.addView(hs);
        return box;
    }

    // ------------------------------------------------------------------ home: review, notebook, scan, check

    /** Four tiles above the recent words: Review (with the number due), Saved words, Scan text, Check a sentence. */
    private View homeTiles() {
        Notebook nb = Store.notebook(this);
        int due = nb.due(null, store.reviewRecent() ? history.items() : null, System.currentTimeMillis(), 99).size();
        String[] labels = {due > 0 ? getString(R.string.review_n, due) : getString(R.string.review), getString(R.string.saved_words),
                getString(R.string.scan_text), getString(R.string.check_sentence)};
        Icons.Kind[] icons = {Icons.Kind.CARDS, Icons.Kind.STAR, Icons.Kind.CAMERA, Icons.Kind.CHECK};
        Class<?>[] screens = {ReviewActivity.class, NotebookActivity.class, ScanActivity.class, CheckActivity.class};
        LinearLayout box = column();
        box.setPadding(0, ui.dp(4), 0, ui.dp(8));
        LinearLayout row = null;
        for (int i = 0; i < 4; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rp.topMargin = ui.dp(10);
                box.addView(row, rp);
            }
            LinearLayout t = ui.actionTile(icons[i], labels[i]);
            final Class<?> screen = screens[i];
            t.setOnClickListener(v -> {
                hideKeyboard();
                startActivity(new Intent(this, screen));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i % 2 == 1) lp.leftMargin = ui.dp(10);
            row.addView(t, lp);
        }
        return box;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    /** "Translation | Meaning": which comes first. The choice is remembered. */
    private View modeSwitch(Lookup l, boolean detected, boolean corrected) {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, 0, 0, ui.dp(10));
        String[] names = {getString(R.string.mode_translation), getString(R.string.mode_meaning)};
        for (int i = 0; i < 2; i++) {
            boolean meaningMode = i == 1;
            boolean on = store.meaningFirst() == meaningMode;
            TextView t = ui.text(13, on ? ui.onAccent : ui.text, Ui.MEDIUM);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(ui.dp(34));
            t.setBackground(ui.ripple(on ? ui.round(ui.accent, 17) : ui.outline(ui.surface, ui.line, 17), 17));
            t.setSelected(on);
            t.setContentDescription(getString(meaningMode ? R.string.mode_meaning_desc : R.string.mode_translation_desc));
            t.setOnClickListener(v -> {
                if (store.meaningFirst() == meaningMode) return;
                store.setMeaningFirst(meaningMode);
                showLookup(l, detected, corrected);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i == 1) lp.leftMargin = ui.dp(8);
            row.addView(t, lp);
        }
        return row;
    }

    /** Star: saves the word in the notebook (choosing a folder when there are folders); tap again to remove. */
    private ImageButton starButton(Lookup l) {
        Notebook nb = Store.notebook(this);
        ImageButton star = ui.iconButton(Icons.Kind.STAR, ui.sub, "");
        Runnable update = () -> {
            boolean saved = nb.isSaved(l.source, l.query);
            star.setImageDrawable(Icons.of(saved ? Icons.Kind.STAR_ON : Icons.Kind.STAR, saved ? ui.accent : ui.sub));
            String d = getString(saved ? R.string.unsave_word : R.string.save_word);
            star.setContentDescription(d);
            star.setTooltipText(d);
        };
        update.run();
        star.setOnClickListener(v -> {
            if (nb.isSaved(l.source, l.query)) {
                nb.unsave(l.source, l.query);
                Toast.makeText(this, R.string.word_unsaved, Toast.LENGTH_SHORT).show();
                update.run();
                return;
            }
            List<String> folders = nb.folders();
            if (folders.isEmpty()) {
                nb.save(l.source, l.query, Notebook.summary(l), "", System.currentTimeMillis());
                Toast.makeText(this, R.string.word_saved, Toast.LENGTH_SHORT).show();
                update.run();
                return;
            }
            String[] items = new String[folders.size() + 1];
            items[0] = getString(R.string.no_folder);
            for (int i = 0; i < folders.size(); i++) items[i + 1] = folders.get(i);
            new AlertDialog.Builder(this, ui.dialogTheme())
                    .setTitle(R.string.save_to)
                    .setItems(items, (d, i) -> {
                        nb.save(l.source, l.query, Notebook.summary(l), i == 0 ? "" : folders.get(i - 1), System.currentTimeMillis());
                        Toast.makeText(this, R.string.word_saved, Toast.LENGTH_SHORT).show();
                        update.run();
                    })
                    .show();
        });
        return star;
    }

    /** "Did you mean house?" — tap to look up the corrected word instead. */
    private View suggestionRow(String suggestion) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(ui.ripple(ui.round(ui.accentSoft, 18), 18));
        row.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        row.setMinimumHeight(ui.dp(56));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = ui.line(13, ui.sub, Ui.MEDIUM);
        label.setText(R.string.did_you_mean);
        row.addView(label);
        TextView word = ui.text(18, ui.accent, Ui.MEDIUM);
        word.setText(suggestion);
        Ui.bidi(word);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = ui.dp(2);
        row.addView(word, wp);
        row.setContentDescription(getString(R.string.did_you_mean_desc, suggestion));
        row.setOnClickListener(v -> {
            setInput(suggestion);
            lookup(true);
            hideKeyboard();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * "Examples" and "Conjugation" under the translations (the translation itself stays at the top),
     * each shown only when it has something to show.
     */
    private View actionRow(Lookup l) {
        boolean examples = sheets.hasExamples(l);
        java.util.Map<Lang, String> verbs = Verbs.of(l);
        java.util.Map<Lang, List<Phrasal.Entry>> families = Lexicon.families(verbs);
        List<View> tiles = new java.util.ArrayList<>();
        if (examples) {
            LinearLayout b = ui.actionTile(Icons.Kind.EXAMPLES, getString(R.string.examples));
            b.setOnClickListener(v -> {
                saveNow();
                sheets.showExamples(l);
            });
            tiles.add(b);
        }
        if (!verbs.isEmpty()) {
            LinearLayout b = ui.actionTile(Icons.Kind.TABLE, getString(R.string.conjugation));
            b.setOnClickListener(v -> {
                saveNow();
                sheets.showConjugation(verbs, l.source);
            });
            tiles.add(b);
        }
        if (!families.isEmpty()) {
            boolean english = families.containsKey(Lang.EN);
            LinearLayout b = ui.actionTile(Icons.Kind.FAMILY, getString(english ? R.string.phrasal_verbs : R.string.prefix_verbs));
            b.setOnClickListener(v -> {
                saveNow();
                sheets.showFamilies(families, verbs, l.source, this::openWord);
            });
            tiles.add(b);
        }
        List<Grammar.Lesson> lessons = Lexicon.lessonsFor(l, verbs, 8);
        if (!lessons.isEmpty()) {
            LinearLayout b = ui.actionTile(Icons.Kind.BOOK, getString(R.string.grammar));
            b.setOnClickListener(v -> {
                saveNow();
                showGrammar(l, lessons);
            });
            tiles.add(b);
        }
        if (tiles.isEmpty()) return null;
        // up to three in a row; four as two rows of two, so every label fits
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, ui.dp(12));
        int perRow = tiles.size() == 4 ? 2 : tiles.size();
        LinearLayout row = null;
        for (int i = 0; i < tiles.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rp.topMargin = ui.dp(10);
                box.addView(row, rp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i % perRow > 0) lp.leftMargin = ui.dp(10);
            row.addView(tiles.get(i), lp);
        }
        return box;
    }

    /** The grammar lessons about the looked-up word (e.g. separable verbs for "fernsehen"). */
    private void showGrammar(Lookup l, List<Grammar.Lesson> lessons) {
        Sheet sheet = new Sheet(this, ui, getString(R.string.grammar_for_word), Text.clean(l.query));
        Lang explain = store.explainLang();
        for (Grammar.Lesson x : lessons)
            sheet.body.addView(GrammarViews.row(this, ui, x, explain, true, v -> GrammarViews.open(this, x)));
        TextView all = ui.pill(getString(R.string.grammar_all), false);
        all.setOnClickListener(v -> {
            sheet.dialog.dismiss();
            startActivity(new Intent(this, GrammarActivity.class));
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.topMargin = ui.dp(4);
        ap.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.body.addView(all, ap);
        sheet.show();
    }

    /** Translates a word chosen in a panel (a phrasal verb, a prefix verb). */
    private void openWord(String word) {
        forced = null;
        updateChips();
        setInput(word);
        lookup(true);
        hideKeyboard();
    }

    // DetailSheets.Host
    @Override public boolean canSpeak(Lang l) {
        return speakable.contains(l);
    }

    @Override public Engine engine() {
        return engine;
    }

    @Override public int service() {
        return store.service();
    }

    /** "Showing results for Schmetterling" — the typed word was misspelled and unknown. */
    private View correctedRow(String corrected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(ui.round(ui.accentSoft, 18));
        row.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        TextView label = ui.line(13, ui.sub, Ui.MEDIUM);
        label.setText(R.string.showing_results_for);
        row.addView(label);
        TextView word = ui.text(18, ui.accent, Ui.MEDIUM);
        word.setText(corrected);
        Ui.bidi(word);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = ui.dp(2);
        row.addView(word, wp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    // ------------------------------------------------------------------ German articles

    /**
     * Article and plural of a German noun from the built-in list. Words from the translation service count
     * only when capitalised (German nouns always are); a typed word may be in lower case ({@code typed}).
     */
    private Nouns.Info germanNoun(String word, boolean typed) {
        Nouns n = Lexicon.nouns();
        String w = Text.clean(word);
        if (n == null || w.isEmpty() || w.contains(" ")) return null;
        if (!typed && !Character.isUpperCase(w.charAt(0))) return null;
        return n.lookup(w);
    }

    private int articleColor(String article) {
        switch (article) {
            case "der": return ui.dark ? 0xFF7AB0FF : 0xFF2563EB;
            case "die": return ui.dark ? 0xFFF87171 : 0xFFDC2626;
            default: return ui.dark ? 0xFF4ADE80 : 0xFF16A34A;
        }
    }

    /** The plural "die" is not feminine: shown in a neutral colour. */
    private CharSequence pluralArticle() {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder("die");
        b.setSpan(new android.text.style.ForegroundColorSpan(ui.sub), 0, 3, 0);
        b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, 3, 0);
        return b;
    }

    /** "der", "die" or "das" in its colour (blue / red / green, as in most German textbooks). */
    private CharSequence coloredArticles(String articles) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        String[] parts = articles.split(" / ");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) b.append(" / ");
            int st = b.length();
            b.append(parts[i]);
            b.setSpan(new android.text.style.ForegroundColorSpan(articleColor(parts[i])), st, b.length(), 0);
            b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, b.length(), 0);
        }
        return b;
    }

    /** "das Haus"; for a plural form ("Häuser") the plural article "die". */
    private CharSequence withArticle(Nouns.Info n, String shownWord) {
        boolean plural = !n.noun.equalsIgnoreCase(shownWord) && n.plurals.contains(shownWord);
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        b.append(plural ? pluralArticle() : coloredArticles(n.articles()));
        b.append(' ').append(shownWord);
        return b;
    }

    /** Small line under a German noun: its plural (or its singular, when the plural is shown). */
    private View otherNumber(Nouns.Info n, String shownWord) {
        boolean plural = !n.noun.equalsIgnoreCase(shownWord) && n.plurals.contains(shownWord);
        String other = plural ? n.noun : n.plural();
        if (other.isEmpty() || other.equals(shownWord)) return null;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = ui.line(13, ui.sub, Ui.REGULAR);
        label.setText(plural ? R.string.singular : R.string.plural);
        row.addView(label);
        TextView v = ui.line(15, ui.text, Ui.MEDIUM);
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        b.append(plural ? coloredArticles(n.articles()) : pluralArticle()).append(' ').append(other);
        v.setText(b);
        v.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ui.dp(8);
        row.addView(v, lp);
        row.setPadding(0, ui.dp(4), 0, 0);
        return row;
    }

    /** Noun meanings with their articles: "das Haus, das Heim, die Wohnung". */
    private CharSequence nounsWithArticles(List<String> terms, String sep) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            if (i > 0) b.append(sep);
            String t = terms.get(i);
            Nouns.Info n = germanNoun(t, false);
            if (n != null) b.append(withArticle(n, t));
            else b.append(t);
        }
        return b;
    }

    /** For a German noun that was typed: "das Haus · Plural die Häuser" under the language line. */
    private View typedNounRow(Lookup l) {
        if (l.source != Lang.DE) return null;
        String q = Text.clean(l.query);
        if (q.isEmpty() || q.contains(" ")) return null;
        boolean nounMeaning = Character.isUpperCase(q.charAt(0));
        for (Lookup.Result r : l.results) {
            if (!r.senses.isEmpty() && r.senses.get(0).pos.toLowerCase(Locale.ROOT).startsWith("noun")) nounMeaning = true;
            if (!r.senses.isEmpty() && !r.senses.get(0).pos.toLowerCase(Locale.ROOT).startsWith("noun")
                    && !Character.isUpperCase(q.charAt(0))) return null;
        }
        if (!nounMeaning) return null;
        Nouns.Info n = germanNoun(q, true);
        if (n == null) return null;
        String shown = Character.toUpperCase(q.charAt(0)) + q.substring(1);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(ui.round(ui.surface, 18));
        box.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        TextView word = ui.text(22, ui.text, Ui.REGULAR);
        word.setText(withArticle(n, shown));
        word.setTextIsSelectable(true);
        box.addView(word);
        View other = otherNumber(n, shown);
        if (other != null) box.addView(other);
        box.setContentDescription(getString(R.string.german_noun_desc, n.articles(), n.noun));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(12);
        box.setLayoutParams(lp);
        return box;
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
        Nouns.Info noun = r.lang == Lang.DE ? germanNoun(r.text, false) : null;
        main.setText(noun != null ? withArticle(noun, r.text) : r.text);
        main.setTextIsSelectable(true);
        main.setLineSpacing(0, 1.2f);
        Ui.bidi(main);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.rightMargin = ui.dp(12);
        c.addView(main, mp);
        if (noun != null) {
            View other = otherNumber(noun, r.text);
            if (other != null) c.addView(other);
        }

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
            boolean germanNouns = r.lang == Lang.DE && s.pos.toLowerCase(Locale.ROOT).startsWith("noun");
            terms.setText(germanNouns ? nounsWithArticles(s.terms, sep) : String.join(sep, s.terms));
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
        content.addView(homeTiles());
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
                showLookup(l, true, false);
                shownFor = Text.key(l.query);
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
            if (shown != null && !speakable.isEmpty()) showLookup(shown, forced == null, shownCorrected);
        });
    }

    @Override public void speak(String text, Lang l) {
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
        // Right after the app starts the keyboard service may not be connected yet: ask again a little later.
        input.post(this::askKeyboard);
        input.postDelayed(() -> {
            if (!keyboardVisible()) askKeyboard();
        }, 450);
        input.postDelayed(() -> {
            if (!keyboardVisible()) askKeyboard();
        }, 1200);
    }

    private void askKeyboard() {
        if (isFinishing() || !input.hasFocus()) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.show(WindowInsets.Type.ime());
        }
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private boolean keyboardVisible() {
        if (Build.VERSION.SDK_INT < 30) return false;      // unknown: asking again does no harm
        WindowInsets wi = input.getRootWindowInsets();
        return wi != null && wi.isVisible(WindowInsets.Type.ime());
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        if (suggestScroll != null) suggestScroll.setVisibility(View.GONE);
    }
}
