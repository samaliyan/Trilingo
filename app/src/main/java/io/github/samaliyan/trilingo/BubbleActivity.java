package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * The small translation panel the bubble opens over the current app: it takes the copied text, shows it in
 * the two other languages (saved words and the built-in dictionary at once, then the translation service),
 * and offers listen, copy and "Open in Trilingo". Tap outside or Back to close.
 */
public final class BubbleActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui ui;
    private Store store;
    private History history;
    private Engine engine;
    private TextToSpeech tts;
    private EditText input;
    private ProgressBar busy;
    private LinearLayout results;
    private boolean readClipboard;
    private int generation;
    private final Runnable typed = () -> translate(false);

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        store = new Store(this);
        history = Store.history(this);
        engine = new Engine(Engine.NET);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        Lexicon.load(this, null);

        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(v -> finish());
        scrim.setContentDescription(getString(R.string.close));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(ui.surface, 24));
        card.setPadding(ui.dp(18), ui.dp(8), ui.dp(8), ui.dp(18));
        card.setClickable(true);                         // taps inside do not close the panel
        card.setElevation(ui.dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = ui.line(13, ui.accent, Ui.MEDIUM);
        name.setText(R.string.app_name);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton paste = ui.iconButton(Icons.Kind.PASTE, ui.sub, getString(R.string.paste));
        paste.setOnClickListener(v -> {
            String t = clipboard();
            if (t.isEmpty()) Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            else setText(t);
        });
        head.addView(paste, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        ImageButton close = ui.iconButton(Icons.Kind.CLOSE, ui.sub, getString(R.string.close));
        close.setOnClickListener(v -> finish());
        head.addView(close, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        card.addView(head);

        input = new EditText(this);
        input.setId(R.id.bubble_input);
        input.setHint(R.string.bubble_hint_input);
        input.setHintTextColor(ui.sub);
        input.setTextColor(ui.text);
        input.setTextSize(19);
        input.setMaxLines(3);
        input.setHorizontallyScrolling(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackground(ui.outline(ui.bg, ui.line, 14));
        input.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        Ui.bidi(input);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(typed);
                handler.postDelayed(typed, 450);
            }
        });
        input.setOnEditorActionListener((v, id, ev) -> {
            handler.removeCallbacks(typed);
            translate(true);
            return true;
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.rightMargin = ui.dp(10);
        card.addView(input, ip);

        busy = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busy.setIndeterminate(true);
        busy.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        busy.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4));
        bp.rightMargin = ui.dp(10);
        card.addView(busy, bp);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        card.addView(results);

        TextView open = ui.pill(getString(R.string.open_in_app), false);
        open.setOnClickListener(v -> {
            String t = Text.clean(input.getText().toString());
            Intent it = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (!t.isEmpty()) it.putExtra(MainActivity.EXTRA_TEXT, t);
            startActivity(it);
            finish();
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(46));
        op.topMargin = ui.dp(12);
        op.rightMargin = ui.dp(10);
        card.addView(open, op);

        int side = ui.sidePadding(560, 12);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cp.setMargins(side, 0, side, ui.dp(16));
        scrim.addView(card, cp);
        setContentView(scrim);
        Feel.appear(card);

        try {
            tts = new TextToSpeech(getApplicationContext(), status -> { });
        } catch (RuntimeException e) {
            tts = null;
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readClipboard = false;               // opened again from the bubble: read what is copied now
    }

    /** Android 10+ lets an app read the clipboard only while its window has focus. */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || readClipboard) return;
        readClipboard = true;
        String t = clipboard();
        if (!t.isEmpty() && !t.equals(Text.clean(input.getText().toString()))) setText(t);
        else if (t.isEmpty() && input.getText().length() == 0) {
            input.requestFocus();
            android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        generation++;
        engine.shutdown();
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
                // gone
            }
        }
        super.onDestroy();
    }

    private String clipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        try {
            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
            CharSequence cs = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
            return cs == null ? "" : Text.clean(cs.toString());
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void setText(String t) {
        input.setText(t);
        input.setSelection(input.getText().length());
        handler.removeCallbacks(typed);
        translate(true);
    }

    private void translate(boolean save) {
        String typed = Text.clean(input.getText().toString());
        int g = ++generation;
        // the same reading as the main screen: numbers, umlauts, compound typos, language
        Resolver.Prepared prep = Lexicon.resolver().prepare(typed, null, null);
        String q = prep.text;
        if (q.isEmpty() || !Text.hasLetter(q)) {
            results.removeAllViews();
            busy.setVisibility(View.INVISIBLE);
            return;
        }
        Lookup instant = history.find(q, prep.source);
        if (instant == null) instant = Lexicon.resolver().offline(prep, false, System.currentTimeMillis());
        if (instant != null) show(instant);
        if (instant != null && !Dict.SERVICE.equals(instant.service) && !Compounds.SERVICE.equals(instant.service)) {
            busy.setVisibility(View.INVISIBLE);
            if (save) keep(instant);
            return;
        }
        final Lookup first = instant;
        busy.setVisibility(View.VISIBLE);
        int service = store.service();
        Net.POOL.execute(() -> {
            Lookup r = null;
            try {
                r = engine.translate(q, prep.source != null ? prep.source : Lexicon.guessLatin(q), service, System.currentTimeMillis());
            } catch (Engine.Failure | RuntimeException ignored) {
                // shown below
            }
            Lookup res = r;
            handler.post(() -> {
                if (g != generation || isFinishing()) return;
                busy.setVisibility(View.INVISIBLE);
                if (res != null && !MainActivity.echoes(res)) {
                    show(res);
                    keep(res);
                } else if (first == null) {
                    Lookup any = Lexicon.resolver().offline(prep, true, System.currentTimeMillis());
                    if (any != null) show(any);
                    else message(res == null ? getString(R.string.error_service) : getString(R.string.bubble_unknown));
                }
            });
        });
    }

    private void keep(Lookup l) {
        if (!store.saveHistory() || l.hasSuggestion()) return;
        history.add(l.withTime(System.currentTimeMillis()));
        WidgetProvider.updateAll(this);
    }

    private void message(String m) {
        results.removeAllViews();
        TextView t = ui.text(15, ui.sub, Ui.REGULAR);
        t.setText(m);
        t.setPadding(0, ui.dp(12), ui.dp(10), 0);
        results.addView(t);
    }

    private void show(Lookup l) {
        results.removeAllViews();
        TextView from = ui.line(12, ui.sub, Ui.MEDIUM);
        from.setText(getString(R.string.from_lang, l.source.label));
        from.setPadding(0, ui.dp(10), 0, ui.dp(2));
        results.addView(from);
        for (Lookup.Result r : l.results) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView tag = ui.line(11, ui.accent, Ui.MEDIUM);
            tag.setText(r.lang.label.toUpperCase(Locale.ROOT));
            tag.setLetterSpacing(0.08f);
            texts.addView(tag);
            TextView main = ui.text(r.text.length() > 60 ? 16 : 20, ui.text, Ui.REGULAR);
            main.setText(r.text);
            main.setTextIsSelectable(true);
            Ui.bidi(main);
            texts.addView(main);
            java.util.List<String> more = new java.util.ArrayList<>();
            for (Lookup.Sense s : r.senses)
                for (String t : s.terms) if (more.size() < 3 && !t.equalsIgnoreCase(r.text) && !more.contains(t)) more.add(t);
            if (!more.isEmpty()) {
                TextView m = ui.text(14, ui.sub, Ui.REGULAR);
                m.setText(String.join(r.lang == Lang.FA ? "\u060C " : ", ", more));
                Ui.bidi(m);
                texts.addView(m);
            }
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (tts != null && r.lang != Lang.FA) {
                ImageButton say = ui.iconButton(Icons.Kind.SPEAK, ui.sub, getString(R.string.speak_lang, r.lang.label));
                say.setOnClickListener(v -> speak(r.text, r.lang));
                row.addView(say, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
            }
            ImageButton copy = ui.iconButton(Icons.Kind.COPY, ui.sub, getString(R.string.copy_lang, r.lang.label));
            copy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), r.text));
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
            });
            row.addView(copy, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = ui.dp(10);
            results.addView(row, rp);
        }
    }

    private void speak(String text, Lang l) {
        try {
            tts.setLanguage(MainActivity.locale(l));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bubble");
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.speak_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
