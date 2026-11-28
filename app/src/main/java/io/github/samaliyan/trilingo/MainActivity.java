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

import java.util.ArrayList;
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
    /** From the widget's microphone: listen at once. */
    static final String ACTION_VOICE = "io.github.samaliyan.trilingo.VOICE";
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
        // First start of a new install: a short introduction. (Updated installs already know the app.)
        boolean introduce = false;
        if (!store.onboarded()) {
            introduce = saved == null && store.isNew() && Store.history(this).items().isEmpty() && incomingText(getIntent()) == null;
            store.setOnboarded(true);
        }
        ui = Ui.of(this);
        ui.applyWindow(this);
        themeAtStart = store.theme();
        scaleAtStart = store.textScale();
        history = Store.history(this);
        engine = new Engine(Engine.NET);
        updater = new Updater(this, ui);
        sheets = new DetailSheets(this, ui, this);
        Net.POOL.execute(() -> Net.init(this));          // disk work, off the screen thread
        if (store.bubble()) BubbleService.start(this);   // e.g. after the app was stopped
        Backfill.start(this);                            // saved words still without a translation
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
        if (introduce) {
            afterIntro = true;
            startActivity(new Intent(this, OnboardingActivity.class));
        }
        // (an update question waits behind the introduction until it is closed)
        if (saved == null && store.autoUpdate() && System.currentTimeMillis() - store.lastUpdateCheck() > 12 * 60 * 60_000L) {
            updater.check(true);
        }
    }

    /** The introduction is open over this screen: when it closes, the keyboard comes up (as on a normal start). */
    private boolean afterIntro;

    @Override protected void onRestart() {
        super.onRestart();
        if (afterIntro) {
            afterIntro = false;
            if (Text.clean(input.getText().toString()).isEmpty()) showKeyboard();
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
        } else if (it != null && ACTION_VOICE.equals(it.getAction())) {
            wantKeyboard = false;
            setInput("");
            voiceSearch();
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
        int side = ui.sidePadding(1100, 12);
        content.setPadding(side, ui.dp(4), side, ui.dp(72));      // room for the undo bar under the last row
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(root);
        frame.addView(buildUndoBar());
        setContentView(frame);
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
        lastSuggestions = words;
        handler.removeCallbacks(prefetchTask);
        if (!words.isEmpty()) handler.postDelayed(prefetchTask, 700);
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
        // What the text is: a number (8 → هشت), German without umlauts (mude → müde), a compound with a
        // typo (marketingassitentin → Marketingassistentin), its language — decided without the network.
        final Resolver.Prepared prep = Lexicon.resolver().prepare(q, forced, null);
        if (!Text.hasLetter(prep.text)) {
            busy.setVisibility(View.INVISIBLE);
            showMessage(getString(R.string.no_letters), null, false);
            return;
        }
        final boolean umlautFixed = prep.corrected;
        final String umlautHint = prep.hint;
        final String query = prep.text;

        long now0 = System.currentTimeMillis();
        // Automatic: the language our word lists see (put on is English); a lookup kept from before under
        // another language (a wrong guess of the service) is not taken for it.
        Lang known = forced != null ? forced : prep.source != null ? prep.source : Lexicon.guessLatin(query);
        Lookup cached = history.find(query, known);
        Lookup pre = cached == null ? prefetched.get(prefetchKey(query, forced)) : null;
        if (cached == null && pre != null) cached = pre;
        // Shown at once: the saved lookup, or what is known without the network (dictionary, number, a
        // compound's parts). A dictionary answer is replaced by the translation service's (richer) answer
        // when it arrives, and stays when there is no internet; a number needs no service.
        Lookup offline = cached != null ? cached : Lexicon.resolver().offline(prep, false, now0);
        if (offline != null) {
            busy.setVisibility(View.INVISIBLE);
            showLookup(offline, forced == null && prep.number < 0 && prep.decimal == null, umlautFixed);
            shownFor = Text.key(q);
            scheduleSave(offline, now);
            boolean refresh = Dict.SERVICE.equals(offline.service) || Compounds.SERVICE.equals(offline.service);
            if (!refresh || !online()) return;
        }
        final Lookup instant = offline;
        busy.setVisibility(View.VISIBLE);
        final Lang f = forced;
        // Automatic: a word only German (or only English) has is decided here; the rest by the service.
        final int service = store.service();
        new Thread(() -> {
            // right after the app starts the word lists may still be loading: wait a moment for them, so the
            // language is decided by them and not guessed by the service
            if (f == null && prep.source == null && Lexicon.words(Lang.EN) == null) Lexicon.await(4000);
            final Lang source = f != null ? f : prep.source != null ? prep.source : Lexicon.guessLatin(query);
            Lookup res = null;
            Engine.Failure fail = null;
            boolean corrected = false;                       // the service's word was replaced by a corrected one
            try {
                res = engine.translate(query, source, service, System.currentTimeMillis());
                // An English phrasal verb opens with its first (most common) meaning from the built-in list:
                // "put on" as wear (پوشیدن · tragen), not the service's word-for-word قرار دادن. The others are
                // a tap away under "Which meaning?".
                res = firstMeaning(res, service);
                // No correction from the service for a word it does not know: the closest word of the built-in list.
                if (!res.hasSuggestion() && echoes(res)) {
                    // automatic and one word: English and German both (Termien is German, though the
                    // service took it for English)
                    String near = f == null && !query.contains(" ") ? Lexicon.closestAny(query) : Lexicon.closest(query, res.source);
                    if (near == null && res.source == Lang.DE && prep.split != null) near = prep.split.word;
                    if (near != null) res = res.withSuggestion(near);
                }
                // The service "translated" a word no list knows (hous → Haus): a very likely correction is
                // still offered, without replacing the answer.
                if (!res.hasSuggestion() && !echoes(res) && prep.split == null && !query.contains(" ")) {
                    // automatic: English and German both (the service may take a German typo for English)
                    Spelling.Candidate c = null;
                    if (f == null) c = Lexicon.bestAny(query);
                    else if (!(f == Lang.EN && Character.isUpperCase(query.charAt(0)))) {
                        List<Spelling.Candidate> all = Lexicon.spellings(query, f, 1);
                        c = all.isEmpty() ? null : all.get(0);
                    }
                    if (c != null && c.cost < 0.85) res = res.withSuggestion(c.word);
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
            // Without an answer (no internet) a misspelt word is not lost: the built-in dictionary's entry
            // for the closest word, shown as "Showing results for".
            Lookup nearOffline = null;
            if (res == null && instant == null && prep.number < 0 && Lexicon.resolver().offline(prep, true, 0) == null) {
                List<Spelling.Candidate> c = Lexicon.spellings(query, f, 1);
                if (!c.isEmpty()) nearOffline = Lexicon.dictionary(c.get(0).word, f, true, System.currentTimeMillis());
            }
            final Lookup near = nearOffline;
            final Lookup r = res;
            final Engine.Failure x = fail;
            final boolean fixedWord = corrected;
            runOnUiThread(() -> {
                if (g != generation || isFinishing() || isDestroyed()) return;
                busy.setVisibility(View.INVISIBLE);
                if (instant != null) {
                    // the dictionary answer is on screen: replaced only by a real translation of the same word
                    if (r == null || echoes(r) || r.hasSuggestion() || fixedWord) return;
                    // the backup service gives one word without meanings: the dictionary's answer is better
                    if (service == Services.GOOGLE && Services.NAMES[Services.MYMEMORY].equals(r.service)) return;
                    int y = scroll.getScrollY();
                    showLookup(r, f == null, umlautFixed);
                    scroll.post(() -> scroll.scrollTo(0, y));
                    shownFor = Text.key(q);
                    scheduleSave(r, now);
                    return;
                }
                Lookup shownNow = r;
                if (shownNow == null) shownNow = Lexicon.resolver().offline(prep, true, System.currentTimeMillis());
                if (shownNow == null && near != null) {
                    showLookup(near, f == null, true);
                    shownFor = Text.key(q);
                    return;
                }
                if (shownNow != null) {
                    showLookup(shownNow, f == null && prep.number < 0, umlautFixed || (r != null && fixedWord));
                    shownFor = Text.key(q);
                    scheduleSave(shownNow, now);
                } else {
                    boolean offlineNow = (x != null && x.offline) || !online();
                    showMessage(getString(offlineNow ? R.string.error_offline : R.string.error_service),
                            getString(offlineNow ? R.string.error_offline_hint : R.string.error_service_hint), true);
                }
            });
        }, "lookup").start();
    }

    // ------------------------------------------------------------------ prefetching suggestions

    /** Translations of the first suggestions, fetched while the user still types (tapping one is instant). */
    private final java.util.Map<String, Lookup> prefetched = new java.util.LinkedHashMap<String, Lookup>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Lookup> e) {
            return size() > 40;
        }
    };
    private final Set<String> prefetching = new java.util.HashSet<>();
    private List<String> lastSuggestions = new java.util.ArrayList<>();
    private final Runnable prefetchTask = this::prefetch;

    private static String prefetchKey(String word, Lang forced) {
        return Text.key(word) + "|" + (forced == null ? "auto" : forced.code);
    }

    private void prefetch() {
        if (isFinishing() || lastSuggestions.isEmpty() || !online()) return;
        final Lang f = forced;
        final int service = store.service();
        // Only the first suggestion, and only one the built-in dictionary does not answer already: the free
        // service limits how often it may be asked, so nothing is fetched that is not likely to be needed.
        for (String w : lastSuggestions.subList(0, 1)) {
            final String key = prefetchKey(w, f);
            if (prefetched.containsKey(key) || prefetching.contains(key) || history.find(w, f) != null
                    || Lexicon.dictionary(w, f, false, 0) != null) continue;
            final Lang source = f != null ? f : Lexicon.guessLatin(w);
            if (source == null) continue;                     // language unknown: it would take three requests
            prefetching.add(key);
            Net.POOL.execute(() -> {
                Lookup r = null;
                try {
                    r = engine.translate(w, source, service, System.currentTimeMillis());
                } catch (Engine.Failure | RuntimeException ignored) {
                    // the normal lookup tries again when the word is chosen
                }
                final Lookup res = r != null && !echoes(r) && !r.hasSuggestion() ? r : null;
                handler.post(() -> {
                    prefetching.remove(key);
                    if (res != null) prefetched.put(key, res);
                });
            });
        }
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
        View numRow = numberRow(l);
        if (numRow != null) content.addView(numRow);
        View nounRow = typedNounRow(l);
        if (nounRow != null) content.addView(nounRow);
        View senseRow = senseRow(l);
        if (senseRow != null) content.addView(senseRow);
        if (corrected) content.addView(correctedRow(l.query));
        else if (l.hasSuggestion()) content.addView(suggestionRow(l.suggestion));
        if (corrected || l.hasSuggestion()) {
            View also = otherSpellings(corrected ? l.query : l.suggestion);
            if (also != null) content.addView(also);
        }

        boolean meaning = MeaningView.shows(l);
        LinearLayout picture = column(), meaningBox = column();
        if (meaning) content.addView(modeSwitch(l, detected, corrected));
        // Tablet or sideways: the two parts side by side; on a phone one below the other.
        LinearLayout first = content, second = content;
        if (ui.wide()) {
            LinearLayout cols = new LinearLayout(this);
            first = column();
            second = column();
            cols.addView(first, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            sp.leftMargin = ui.dp(14);
            cols.addView(second, sp);
            content.addView(cols);
        }
        View actions = actionRow(l);
        if (meaning && store.meaningFirst()) {
            // learn from the meaning first; the translation is one tap away
            first.addView(picture);
            first.addView(meaningBox);
            LinearLayout hidden = column();
            hidden.setVisibility(View.GONE);
            for (Lookup.Result r : l.results) hidden.addView(card(l, r));
            TextView reveal = ui.pill(getString(R.string.show_translation), true);
            reveal.setOnClickListener(v -> {
                reveal.setVisibility(View.GONE);
                hidden.setVisibility(View.VISIBLE);
                Feel.appear(hidden);
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48));
            rp.bottomMargin = ui.dp(12);
            second.addView(reveal, rp);
            second.addView(hidden);
            if (actions != null) second.addView(actions);
        } else {
            // translation, its tools (examples, conjugation …), then the picture and the meaning
            for (Lookup.Result r : l.results) first.addView(card(l, r));
            if (actions != null) first.addView(actions);
            if (meaning) second.addView(picture);
            if (meaning) second.addView(meaningBox);
        }

        boolean fromDict = Dict.SERVICE.equals(l.service);
        if (Services.NAMES[Services.MYMEMORY].equals(l.service) || fromDict) {
            TextView via = ui.text(12, ui.sub, Ui.REGULAR);
            via.setText(fromDict ? getString(R.string.via_dictionary) : getString(R.string.via_service, l.service));
            via.setPadding(ui.dp(6), ui.dp(4), 0, 0);
            content.addView(via);
        }
        if (meaning) meaningView.fill(l, picture, meaningBox);
        scroll.scrollTo(0, 0);
        if (!l.key().equals(animatedKey)) Feel.appear(content);      // a new word fades in; a refresh does not
        animatedKey = l.key();
    }

    private String animatedKey = "";

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

    /** Tiles above the recent words: Review (with the number due), Saved words, the practices and tools. */
    private View homeTiles() {
        Notebook nb = Store.notebook(this);
        List<Lookup> recent = store.reviewRecent() ? Notebook.reviewable(history.items(), Lexicon.resolver()::isWord) : null;
        int due = nb.due(null, recent, System.currentTimeMillis(), 99).size();
        String[] labels = {due > 0 ? getString(R.string.review_n, due) : getString(R.string.review), getString(R.string.saved_words),
                getString(R.string.learn), getString(R.string.progress),
                getString(R.string.listen_write), getString(R.string.text_words),
                getString(R.string.scan_text), getString(R.string.check_sentence)};
        Icons.Kind[] icons = {Icons.Kind.CARDS, Icons.Kind.STAR, Icons.Kind.PEN, Icons.Kind.CHART, Icons.Kind.EAR, Icons.Kind.LIST,
                Icons.Kind.CAMERA, Icons.Kind.CHECK};
        Class<?>[] screens = {ReviewActivity.class, NotebookActivity.class, LearnActivity.class, ProgressActivity.class,
                DictationActivity.class, TextWordsActivity.class, ScanActivity.class, CheckActivity.class};
        int perRow = ui.wide() ? 3 : 2;
        LinearLayout box = column();
        box.setPadding(0, ui.dp(4), 0, ui.dp(8));
        LinearLayout row = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % perRow == 0) {
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
            if (i % perRow > 0) lp.leftMargin = ui.dp(10);
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
        // German nouns are saved with their capital (typed "tisch" → "Tisch")
        String word = l.source == Lang.DE ? Lexicon.germanCase(Text.clean(l.query)) : Text.clean(l.query);
        ImageButton star = ui.iconButton(Icons.Kind.STAR, ui.sub, "");
        Runnable update = () -> {
            boolean saved = nb.isSaved(l.source, word);
            star.setImageDrawable(Icons.of(saved ? Icons.Kind.STAR_ON : Icons.Kind.STAR, saved ? ui.accent : ui.sub));
            String d = getString(saved ? R.string.unsave_word : R.string.save_word);
            star.setContentDescription(d);
            star.setTooltipText(d);
        };
        update.run();
        star.setOnClickListener(v -> {
            if (nb.isSaved(l.source, word)) {
                nb.unsave(l.source, word);
                Toast.makeText(this, R.string.word_unsaved, Toast.LENGTH_SHORT).show();
                update.run();
                Feel.tick(star);
                return;
            }
            List<String> folders = nb.folders();
            if (folders.isEmpty()) {
                nb.save(l.source, word, Notebook.summary(l), "", System.currentTimeMillis());
                Toast.makeText(this, R.string.word_saved, Toast.LENGTH_SHORT).show();
                update.run();
                Feel.pop(star);
                return;
            }
            String[] items = new String[folders.size() + 1];
            items[0] = getString(R.string.no_folder);
            for (int i = 0; i < folders.size(); i++) items[i + 1] = folders.get(i);
            new AlertDialog.Builder(this, ui.dialogTheme())
                    .setTitle(R.string.save_to)
                    .setItems(items, (d, i) -> {
                        nb.save(l.source, word, Notebook.summary(l), i == 0 ? "" : folders.get(i - 1), System.currentTimeMillis());
                        Toast.makeText(this, R.string.word_saved, Toast.LENGTH_SHORT).show();
                        update.run();
                        Feel.pop(star);
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

    /**
     * "Other spellings": the next best corrections of what was typed, under "Did you mean" or "Showing
     * results for" — tap one to look it up.
     */
    private View otherSpellings(String shown) {
        String typed = Text.clean(input.getText().toString());
        if (typed.contains(" ") || typed.isEmpty()) return null;
        // the search takes a moment on a slow phone: done in the background, the row appears when ready
        LinearLayout holder = column();
        int gen = generation;
        new Thread(() -> {
            List<String> words = new ArrayList<>();
            for (Spelling.Candidate c : Lexicon.spellings(typed, forced, 6)) {
                // not the word shown, nor just a form of it (Kühlschrank → Kühlschränke)
                String k = c.word.toLowerCase(Locale.ROOT), sh = shown.toLowerCase(Locale.ROOT);
                if (k.equals(sh) || sh.length() >= 4 && (k.startsWith(sh) || Words.fold(Lang.DE, k).startsWith(Words.fold(Lang.DE, sh)))) continue;
                if (words.size() < 3) words.add(c.word);
            }
            runOnUiThread(() -> {
                if (gen != generation || isDestroyed() || words.isEmpty()) return;
                holder.addView(spellingRow(words));
            });
        }, "spellings").start();
        return holder;
    }

    private View spellingRow(List<String> words) {
        LinearLayout box = column();
        TextView h = ui.line(12, ui.sub, Ui.MEDIUM);
        h.setText(R.string.other_spellings);
        box.addView(h);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (String w : words) {
            TextView c = ui.line(15, ui.text, Ui.MEDIUM);
            c.setText(w);
            c.setGravity(Gravity.CENTER);
            c.setMinHeight(ui.dp(44));
            c.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            c.setBackground(ui.ripple(ui.outline(ui.bg, ui.line, 22), 22));
            c.setContentDescription(getString(R.string.suggestion_desc, w));
            c.setOnClickListener(v -> {
                setInput(w);
                lookup(true);
                hideKeyboard();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ui.dp(8);
            row.addView(c, lp);
        }
        hs.addView(row);
        box.addView(hs, Page.full(ui.dp(6), 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(14);
        box.setLayoutParams(lp);
        return box;
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
        View parts = partsView(shown);
        if (parts != null) box.addView(parts);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(12);
        box.setLayoutParams(lp);
        return box;
    }

    /**
     * The parts of a German compound the dictionary does not have as a whole, each with its translation;
     * tap a part to look it up. "Female form of …" for a -in word.
     */
    private View partsView(String word) {
        Resolver res = Lexicon.resolver();
        Compounds.Split s = res.compounds == null ? null : res.compounds.split(word);
        if (s == null) return null;
        LinearLayout box = column();
        box.setPadding(0, ui.dp(10), 0, 0);
        TextView h = ui.line(12, ui.sub, Ui.MEDIUM);
        h.setText(R.string.word_parts);
        box.addView(h);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < s.parts.size(); i++) {
            Compounds.Part p = s.parts.get(i);
            if (i > 0) {
                TextView plus = ui.text(16, ui.sub, Ui.REGULAR);
                plus.setText("+");
                plus.setPadding(ui.dp(6), 0, ui.dp(6), 0);
                plus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(plus);
            }
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6));
            chip.setBackground(ui.ripple(ui.outline(ui.bg, ui.line, 12), 12));
            chip.setMinimumHeight(ui.dp(44));
            TextView t = ui.line(15, ui.text, Ui.MEDIUM);
            t.setText(p.base);
            chip.addView(t);
            String[] tr = res.compounds.translate(p.base);
            String meaning = tr == null ? "" : tr[0];
            if (!meaning.isEmpty()) {
                TextView m = ui.line(12, ui.sub, Ui.REGULAR);
                m.setText(meaning);
                chip.addView(m);
            }
            chip.setContentDescription(getString(R.string.word_part_desc, p.base, meaning));
            chip.setOnClickListener(v -> {
                setInput(p.base);
                lookup(true);
            });
            row.addView(chip);
        }
        hs.addView(row);
        box.addView(hs, Page.full(ui.dp(6), 0));
        if (!s.male.isEmpty()) {
            TextView f = ui.line(13, ui.sub, Ui.REGULAR);
            f.setText(getString(R.string.female_form, s.male));
            box.addView(f, Page.full(ui.dp(8), 0));
        }
        return box;
    }

    /**
     * "Which meaning?" for a word with several (put on: wear · switch on · gain weight). The chosen one is
     * translated again, so the German and Persian, the explanation and the picture follow it; it is kept, so
     * the word opens with it next time and is saved with it.
     */
    private View senseRow(Lookup l) {
        if (Resolver.NUMBER.equals(l.service)) return null;
        Phrasal ph = Lexicon.phrasal();
        String phrasal = ph == null || l.source != Lang.EN ? null : ph.meaning(Text.clean(l.query).toLowerCase(Locale.ROOT));
        // the word kinds the built-in dictionary knows (noun, verb …); without an entry, no choice by kind
        java.util.Set<String> kinds = new java.util.HashSet<>();
        Dict d = Lexicon.dict(l.source);
        Dict.Entry de = d == null ? null : d.find(Text.clean(l.query));
        if (de != null && de.word.equalsIgnoreCase(Text.clean(l.query)))
        {
            // the kinds of the language the choices are in (Persian; English for a Persian word)
            List<Lookup.Sense> ss = de.targets.get(l.source == Lang.FA ? Lang.EN : Lang.FA);
            if (ss != null) for (Lookup.Sense x : ss) if (x.pos != null) kinds.add(x.pos.toLowerCase(Locale.ROOT));
        }
        List<Senses.Option> options = Senses.of(l, phrasal, kinds);
        if (options.isEmpty()) return null;
        LinearLayout box = column();
        box.setPadding(0, 0, 0, ui.dp(12));
        TextView h = ui.line(12, ui.sub, Ui.MEDIUM);
        h.setText(R.string.which_meaning);
        box.addView(h);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < options.size(); i++) {
            Senses.Option o = options.get(i);
            // the chosen one: the meaning kept for this word, else the first
            // shown as chosen only when the translation on screen is that meaning (a phrasal verb's English
            // meanings are not chosen until tapped: "put on" first shows قرار دادن, which is none of them)
            boolean on = !l.sense.isEmpty() && o.label.equalsIgnoreCase(l.sense) || isChosen(l, o);
            TextView c = ui.line(15, on ? ui.onAccent : ui.text, Ui.MEDIUM);
            c.setText(o.label);
            c.setGravity(Gravity.CENTER);
            c.setMinHeight(ui.dp(44));
            c.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            c.setBackground(on ? ui.ripple(ui.round(ui.accent, 22), 22) : ui.ripple(ui.outline(ui.bg, ui.line, 22), 22));
            c.setContentDescription(getString(on ? R.string.meaning_chosen_desc : R.string.meaning_choose_desc, o.label));
            c.setSelected(on);
            if (!on) c.setOnClickListener(v -> chooseSense(l, o, c));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ui.dp(8);
            row.addView(c, lp);
        }
        hs.addView(row);
        box.addView(hs, Page.full(ui.dp(6), 0));
        return box;
    }

    /** For an English phrasal verb with listed meanings: the lookup with the first one chosen (network). */
    private Lookup firstMeaning(Lookup res, int service) {
        if (res == null || res.source != Lang.EN || !res.sense.isEmpty()) return res;
        Phrasal ph = Lexicon.phrasal();
        String m = ph == null ? null : ph.meaning(Text.clean(res.query).toLowerCase(Locale.ROOT));
        List<Senses.Option> options = m == null ? java.util.Collections.emptyList() : Senses.of(res, m);
        if (options.isEmpty() || !options.get(0).verb) return res;
        Senses.Option o = options.get(0);
        try {
            Lookup t = engine.translate(Senses.query(o), Lang.EN, service, System.currentTimeMillis());
            java.util.Map<Lang, String> by = new java.util.EnumMap<>(Lang.class);
            for (Lookup.Result r : t.results) by.put(r.lang, r.text);
            return Senses.choose(res, o, by, Senses.gloss(o));
        } catch (Engine.Failure e) {
            return res;
        }
    }

    /** A Persian (or English) chip is the chosen meaning when its word is the translation shown now. */
    private static boolean isChosen(Lookup l, Senses.Option o) {
        Lookup.Result r = l.result(o.lang);
        return r != null && Text.clean(r.text).equalsIgnoreCase(o.label);
    }

    private void chooseSense(Lookup l, Senses.Option o, View chip) {
        if (!online()) {
            Toast.makeText(this, R.string.error_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        Feel.tick(chip);
        busy.setVisibility(View.VISIBLE);
        int gen = ++generation;
        String q = Senses.query(o);
        Net.run(() -> engine.translate(q, o.lang, store.service(), System.currentTimeMillis()), (res, err) -> {
            if (gen != generation || isDestroyed()) return;
            busy.setVisibility(View.INVISIBLE);
            if (res == null) {
                Toast.makeText(this, R.string.error_service, Toast.LENGTH_SHORT).show();
                return;
            }
            java.util.Map<Lang, String> by = new java.util.EnumMap<>(Lang.class);
            for (Lookup.Result r : res.results) by.put(r.lang, r.text);
            // the meaning in English: the chip itself (wear), or the English of a Persian chip (پوشیدن → wear)
            String sense = o.lang == Lang.EN ? Senses.gloss(o) : by.containsKey(Lang.EN) ? by.get(Lang.EN) : q;
            Lookup chosen = Senses.choose(l, o, by, sense);
            history.add(chosen);
            WidgetProvider.updateAll(this);
            showLookup(chosen, false, shownCorrected);
        });
    }

    /** "Number 8" above the translation of a number typed or spoken in digits. */
    private View numberRow(Lookup l) {
        String typed = Text.clean(input.getText().toString());
        // digits typed: always a number, whichever service answered; number words: the number lookup's value
        long n = Numbers.parse(typed);
        if (n < 0 && Resolver.NUMBER.equals(l.service)) n = Numbers.valueOfLookup(l);
        String shown;
        if (n >= 0 && Numbers.decimalWords(typed, Lang.EN) == null) shown = Numbers.digits(n, Lang.EN);
        else if (Numbers.decimalWords(typed, Lang.EN) != null) shown = typed;
        else return null;
        TextView t = ui.line(13, ui.sub, Ui.MEDIUM);
        t.setText(getString(R.string.number_row, shown));
        t.setPadding(ui.dp(6), 0, 0, ui.dp(10));
        return t;
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
        for (Lookup.Sense s0 : r.senses) {
            // the main word is already shown big above: not again as its own line or at the start of a list
            List<String> rest = new ArrayList<>();
            for (String t : s0.terms) if (!Text.clean(t).equalsIgnoreCase(Text.clean(r.text))) rest.add(t);
            if (rest.isEmpty()) continue;
            Lookup.Sense s = new Lookup.Sense(s0.pos == null ? "" : s0.pos, rest);
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
        scroll.scrollTo(0, 0);                         // a long result may have been scrolled down
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
        list.setClipToOutline(true);                   // the swipe bands keep the rounded corners
        Notebook nb = Store.notebook(this);
        int n = Math.min(items.size(), RECENT_SHOWN);
        for (int k = 0; k < n; k++) list.addView(recentRow(items.get(k), nb));
        content.addView(list);
        TextView tip = ui.text(12, ui.sub, Ui.REGULAR);
        tip.setText(R.string.recent_tip);
        tip.setPadding(ui.dp(6), ui.dp(10), ui.dp(6), 0);
        content.addView(tip);
    }

    /** One recent word: tap to show it; swipe right to save it, left to remove it (long press: the same as a menu). */
    private View recentRow(Lookup l, Notebook nb) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        row.setMinimumHeight(ui.dp(60));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ui.ripple(ui.round(ui.surface, 0), 0));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView q = ui.line(16, ui.text, Ui.REGULAR);
        q.setText(l.query);
        Ui.bidi(q);
        top.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final boolean canSave = MeaningView.applies(l) && !l.results.isEmpty();
        final String word = l.source == Lang.DE ? Lexicon.germanCase(Text.clean(l.query)) : Text.clean(l.query);
        ImageView mark = new ImageView(this);
        mark.setImageDrawable(Icons.of(Icons.Kind.STAR_ON, ui.accent));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mark.setVisibility(canSave && nb.isSaved(l.source, word) ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16));
        mp.leftMargin = ui.dp(8);
        top.addView(mark, mp);
        row.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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

        SwipeRow swipe = new SwipeRow(this, ui, row);
        Runnable toggleSave = () -> {
            boolean now = !nb.isSaved(l.source, word);
            if (now) nb.save(l.source, word, Notebook.summary(l), "", System.currentTimeMillis());
            else nb.unsave(l.source, word);
            mark.setVisibility(now ? View.VISIBLE : View.GONE);
            if (now) Feel.pop(mark);
            Toast.makeText(this, now ? R.string.word_saved : R.string.word_unsaved, Toast.LENGTH_SHORT).show();
        };
        Runnable remove = () -> {
            int index = history.items().indexOf(l);
            history.remove(l);
            nb.forgetRecent(l.source, l.query);
            WidgetProvider.updateAll(this);
            collapse(swipe);
            showUndo(getString(R.string.removed_word, l.query), () -> {
                history.restore(l, index);
                WidgetProvider.updateAll(this);
                if (Text.clean(input.getText().toString()).isEmpty()) showRecent();
            });
        };
        if (canSave) swipe.onRight(Icons.Kind.STAR, getString(R.string.save_word), ui.accent, () -> {
            toggleSave.run();
            return false;
        });
        swipe.onLeft(Icons.Kind.CLOSE, getString(R.string.remove), ui.danger, () -> {
            remove.run();
            return true;
        });
        row.setOnLongClickListener(v -> {
            boolean saved = canSave && nb.isSaved(l.source, word);
            List<String> names = new java.util.ArrayList<>();
            if (canSave) names.add(getString(saved ? R.string.unsave_word : R.string.save_word));
            names.add(getString(R.string.remove));
            new AlertDialog.Builder(this, ui.dialogTheme())
                    .setTitle(l.query)
                    .setItems(names.toArray(new String[0]), (d, i) -> {
                        if (canSave && i == 0) toggleSave.run();
                        else remove.run();
                    })
                    .show();
            return true;
        });
        // the same actions for screen readers
        row.setContentDescription(l.query + ". " + s.getText());
        accessibilityActions(row, canSave, toggleSave, remove);
        return swipe;
    }

    /** Custom accessibility actions ("Save word", "Remove"), so the swipes are not the only way. */
    private void accessibilityActions(View row, boolean canSave, Runnable save, Runnable remove) {
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                if (canSave) info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.action_save, getString(R.string.save_word)));
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(R.id.action_remove, getString(R.string.remove)));
            }

            @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == R.id.action_save && canSave) {
                    save.run();
                    return true;
                }
                if (action == R.id.action_remove) {
                    remove.run();
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    /** The row's height shrinks to nothing, then it is taken out of the list. */
    private void collapse(View v) {
        int h = v.getHeight();
        android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(h, 0);
        a.setDuration(180);
        a.addUpdateListener(x -> {
            v.getLayoutParams().height = (int) x.getAnimatedValue();
            v.requestLayout();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator x) {
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                if (history.items().isEmpty() && Text.clean(input.getText().toString()).isEmpty()) showRecent();
            }
        });
        a.start();
    }

    // ------------------------------------------------------------------ undo bar

    private LinearLayout undoBar;
    private TextView undoText;
    private Runnable undoAction;
    private final Runnable hideUndo = () -> {
        if (undoBar != null) undoBar.setVisibility(View.GONE);
        undoAction = null;
    };

    private void showUndo(String text, Runnable undo) {
        undoText.setText(text);
        undoAction = undo;
        undoBar.setVisibility(View.VISIBLE);
        Feel.appear(undoBar);
        handler.removeCallbacks(hideUndo);
        handler.postDelayed(hideUndo, 8000);
    }

    private View buildUndoBar() {
        undoBar = new LinearLayout(this);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.setPadding(ui.dp(18), ui.dp(4), ui.dp(6), ui.dp(4));
        undoBar.setMinimumHeight(ui.dp(52));
        undoBar.setBackground(ui.round(ui.dark ? 0xFFE6E9EE : 0xFF232A33, 14));
        undoBar.setElevation(ui.dp(6));
        undoText = ui.line(14, ui.dark ? 0xFF12161C : 0xFFF2F4F7, Ui.REGULAR);
        undoText.setId(R.id.undo_text);
        undoBar.addView(undoText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView undo = ui.text(14, ui.dark ? 0xFF2563EB : 0xFF7AB0FF, Ui.MEDIUM);
        undo.setText(R.string.undo);
        undo.setId(R.id.undo_button);
        undo.setGravity(Gravity.CENTER);
        undo.setMinHeight(ui.dp(44));
        undo.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        undo.setBackground(ui.ripple(null, 0));
        undo.setOnClickListener(v -> {
            Runnable r = undoAction;
            hideUndo.run();
            if (r != null) r.run();
        });
        undoBar.addView(undo);
        undoBar.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(12));
        undoBar.setLayoutParams(lp);
        return undoBar;
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
                    Store.notebook(this).forgetAllRecent();
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
        pinWidget(c, WidgetProvider.class);
    }

    /** Asks the launcher to place a widget of the app (the search bar, or the Word card). */
    static void pinWidget(Context c, Class<?> widget) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        boolean ok = false;
        try {
            ok = m != null && m.requestPinAppWidget(new ComponentName(c, widget), null, null);
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

    /** Which request to open the keyboard is the current one; an older one's retries stop. */
    private int keyboardRequest;
    /** The keyboard of the current request came up: no more retries (the user may close it again). */
    private boolean keyboardCameUp;

    private void openKeyboardNow() {
        input.requestFocus();
        int req = ++keyboardRequest;
        keyboardCameUp = false;
        // Right after the app starts the keyboard service may not be connected yet: ask again a little
        // later, for a few seconds on a slow first start — but only until it has come up once. Once it was
        // up, closing it (the Back or hide-keyboard button) is the user's choice and it stays closed.
        input.post(() -> askKeyboard(req));
        for (int ms : new int[]{450, 1200, 2000, 3000}) input.postDelayed(() -> askKeyboard(req), ms);
    }

    private void askKeyboard(int req) {
        if (isFinishing() || !input.hasFocus() || req != keyboardRequest) return;
        if (keyboardVisible()) keyboardCameUp = true;
        if (keyboardCameUp) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.show(WindowInsets.Type.ime());
        }
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        // seen up a moment later: the later retries are not needed
        input.postDelayed(() -> {
            if (req == keyboardRequest && keyboardVisible()) keyboardCameUp = true;
        }, 300);
    }

    private boolean keyboardVisible() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets wi = input.getRootWindowInsets();
            return wi != null && wi.isVisible(WindowInsets.Type.ime());
        }
        // Android 8–10: the keyboard is up when it covers a good part of the window
        View root = getWindow().getDecorView();
        android.graphics.Rect r = new android.graphics.Rect();
        root.getWindowVisibleDisplayFrame(r);
        int h = root.getRootView().getHeight();
        return h > 0 && h - r.bottom > h / 5;
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        if (suggestScroll != null) suggestScroll.setVisibility(View.GONE);
    }
}
