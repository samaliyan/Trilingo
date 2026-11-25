package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * "Listen and write": the phone says a word or a sentence, you write it. Five at a time, with
 * "5 new" for more. Words come from your saved words or a level's word list; sentences from the
 * grammar lessons. Each answer shows the right spelling with the missed letters marked, and the meaning.
 */
public final class DictationActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int N = 5;
    static final String SAVED = "saved", SENTENCES = "sentences";
    private static final String[] SOURCES = {SAVED, "A1", "A2", "B1", "B2", "C1", SENTENCES};
    /** Shown recently (while the app runs), so a new set brings other items. */
    private static final Set<String> recent = new HashSet<>();
    private static String source = "A1";

    private Ui ui;
    private Store store;
    private Page page;
    private TextToSpeech tts;
    private boolean ttsReady, ttsMissing;
    private Lang lang;
    private final Random random = new Random();
    private int checked, right;

    /** An item: what is said and written, and its meaning (shown after the answer). */
    private static final class Item {
        final String text, meaning;

        Item(String text, String meaning) {
            this.text = text;
            this.meaning = meaning;
        }
    }

    private final List<Item> items = new ArrayList<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        store = new Store(this);
        lang = store.dictationLang();
        page = new Page(this, ui, getString(R.string.listen_write));
        TextView wait = ui.text(15, ui.sub, Ui.REGULAR);
        wait.setText(R.string.loading);
        page.content.addView(wait);
        try {
            tts = new TextToSpeech(getApplicationContext(), this);
        } catch (RuntimeException e) {
            tts = null;
            ttsMissing = true;
        }
        Lexicon.load(this, () -> runOnUiThread(() -> {
            if (!isDestroyed()) next();
        }));
    }

    @Override protected void onDestroy() {
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        super.onDestroy();
    }

    @Override public void onInit(int status) {
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            ttsReady = status == TextToSpeech.SUCCESS;
            ttsMissing = !ttsReady;
            if (Lexicon.grammar() != null) render();
        });
    }

    private boolean canSpeak() {
        if (!ttsReady || tts == null) return false;
        try {
            return tts.isLanguageAvailable(MainActivity.locale(lang)) >= TextToSpeech.LANG_AVAILABLE;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void say(String text, boolean slow) {
        if (!canSpeak()) return;
        try {
            tts.setLanguage(MainActivity.locale(lang));
            tts.setSpeechRate(slow ? 0.6f : 0.95f);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dictation");
        } catch (RuntimeException ignored) {
            // no voice
        }
    }

    // ------------------------------------------------------------------ choosing items

    private void next() {
        items.clear();
        for (Item i : pick()) items.add(i);
        for (Item i : items) recent.add(i.text);
        if (recent.size() > 300) recent.clear();
        checked = 0;
        right = 0;
        render();
    }

    private List<Item> pick() {
        Map<String, String> pool = new LinkedHashMap<>();         // text → meaning
        Dict dict = Lexicon.dict(lang);
        if (SAVED.equals(source)) {
            for (Notebook.Card c : Store.notebook(this).saved(null))
                if (c.lang == lang && c.word.split(" ").length <= 3) pool.put(spoken(c.word), c.back);
        } else if (SENTENCES.equals(source)) {
            Grammar g = Lexicon.grammar();
            if (g != null) {
                for (Grammar.Lesson l : g.of(lang)) {
                    if (l.levelIndex() > 3) continue;
                    for (String[] ex : l.examples) {
                        if (ex.length < 3 || ex[0].length() > 70 || ex[0].length() < 8) continue;
                        pool.put(ex[0], ex[2] + "\n" + ex[1]);
                    }
                }
            }
        } else {
            Levels lv = Lexicon.levels(lang);
            if (lv != null && dict != null) {
                for (String w : lv.words(source)) {
                    Dict.Entry e = dict.find(w);
                    if (e == null || w.length() < 3 || w.contains(" ")) continue;
                    pool.put(spoken(e.word), dict.summary(e.word));
                }
            }
        }
        List<String> chosen = Dictation.pick(new ArrayList<>(pool.keySet()), N, random, recent);
        List<Item> out = new ArrayList<>();
        for (String t : chosen) out.add(new Item(t, pool.get(t)));
        return out;
    }

    /** A German noun is said and written with its article ("der Tisch"). */
    private String spoken(String word) {
        if (lang != Lang.DE || word.isEmpty() || !Character.isUpperCase(word.charAt(0)) || word.contains(" ")) return word;
        Nouns n = Lexicon.nouns();
        Nouns.Info i = n == null ? null : n.lookup(word);
        if (i == null || i.genders.size() != 1 || !i.noun.equals(word)) return word;
        return Nouns.article(i.genders.get(0)) + " " + word;
    }

    // ------------------------------------------------------------------ screen

    private void render() {
        LinearLayout c = page.content;
        c.removeAllViews();
        c.addView(chips(new String[]{Lang.DE.label, Lang.EN.label}, lang == Lang.DE ? 0 : 1, i -> {
            lang = i == 0 ? Lang.DE : Lang.EN;
            store.setDictationLang(lang);
            next();
        }));
        String[] names = new String[SOURCES.length];
        int sel = 0;
        for (int i = 0; i < SOURCES.length; i++) {
            names[i] = SAVED.equals(SOURCES[i]) ? getString(R.string.saved_words)
                    : SENTENCES.equals(SOURCES[i]) ? getString(R.string.sentences) : getString(R.string.level_words, SOURCES[i]);
            if (SOURCES[i].equals(source)) sel = i;
        }
        c.addView(chips(names, sel, i -> {
            source = SOURCES[i];
            next();
        }));

        if (!canSpeak()) {
            LinearLayout b = box();
            TextView t = ui.text(16, ui.text, Ui.MEDIUM);
            t.setText(ttsReady || ttsMissing ? getString(R.string.no_voice, lang.label) : getString(R.string.loading));
            b.addView(t);
            if (ttsReady || ttsMissing) {
                TextView h = ui.text(14, ui.sub, Ui.REGULAR);
                h.setText(R.string.no_voice_hint);
                h.setLineSpacing(0, 1.2f);
                b.addView(h, Page.full(ui.dp(6), 0));
                TextView install = ui.pill(getString(R.string.install_voice), true);
                install.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));
                    } catch (RuntimeException e) {
                        android.widget.Toast.makeText(this, R.string.no_voice_settings, android.widget.Toast.LENGTH_LONG).show();
                    }
                });
                b.addView(install, Page.full(ui.dp(14), 0));
            }
            c.addView(b);
            return;
        }
        if (items.isEmpty()) {
            TextView t = ui.text(16, ui.text, Ui.MEDIUM);
            t.setText(SAVED.equals(source) ? getString(R.string.dictation_no_saved, lang.label) : getString(R.string.practice_none));
            t.setLineSpacing(0, 1.2f);
            c.addView(t, Page.full(ui.dp(20), 0));
            return;
        }
        TextView how = ui.text(14, ui.sub, Ui.REGULAR);
        how.setText(R.string.dictation_how);
        c.addView(how, Page.full(ui.dp(4), ui.dp(10)));
        for (int i = 0; i < items.size(); i++) c.addView(question(i, items.get(i)));
        TextView score = ui.text(16, ui.text, Ui.MEDIUM);
        score.setGravity(Gravity.CENTER);
        score.setTag("score");
        score.setVisibility(View.GONE);
        c.addView(score, Page.full(ui.dp(8), ui.dp(8)));
        TextView more = ui.pill(getString(R.string.five_new), true);
        more.setOnClickListener(v -> {
            next();
            page.scroll.scrollTo(0, 0);
        });
        c.addView(more, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52)));
    }

    interface Pick {
        void on(int i);
    }

    private View chips(String[] names, int selected, Pick pick) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, ui.dp(2), 0, ui.dp(8));
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            boolean on = i == selected;
            TextView t = ui.text(14, on ? ui.onAccent : ui.text, Ui.MEDIUM);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(ui.dp(38));
            t.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            t.setBackground(ui.ripple(on ? ui.round(ui.accent, 19) : ui.outline(ui.surface, ui.line, 19), 19));
            t.setSelected(on);
            t.setOnClickListener(v -> {
                if (k != selected) pick.on(k);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ui.dp(8);
            row.addView(t, lp);
        }
        hs.addView(row);
        return hs;
    }

    private LinearLayout box() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(ui.round(ui.surface, 18));
        b.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16));
        b.setLayoutParams(Page.full(0, ui.dp(12)));
        return b;
    }

    private int green() {
        return ui.dark ? 0xFF4ADE80 : 0xFF16A34A;
    }

    private View question(int index, Item item) {
        LinearLayout b = box();
        TextView num = ui.line(12, ui.accent, Ui.MEDIUM);
        num.setText(getString(R.string.question_n, index + 1));
        b.addView(num);

        LinearLayout buttons = new LinearLayout(this);
        LinearLayout play = ui.actionPill(Icons.Kind.SPEAK, getString(R.string.listen));
        play.setOnClickListener(v -> say(item.text, false));
        buttons.addView(play);
        LinearLayout slow = ui.actionPill(Icons.Kind.PLAY, getString(R.string.slowly));
        slow.setOnClickListener(v -> say(item.text, true));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = ui.dp(10);
        buttons.addView(slow, sp);
        b.addView(buttons, Page.full(ui.dp(10), 0));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText in = new EditText(this);
        in.setHint(R.string.write_what_you_hear);
        in.setHintTextColor(ui.sub);
        in.setTextColor(ui.text);
        in.setTextSize(17);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        in.setImeOptions(EditorInfo.IME_ACTION_DONE);
        in.setBackground(ui.outline(ui.bg, ui.line, 12));
        in.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        in.setContentDescription(getString(R.string.answer_n, index + 1));
        inputRow.addView(in, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView check = ui.pill(getString(R.string.check), true);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44));
        cp.leftMargin = ui.dp(10);
        inputRow.addView(check, cp);
        b.addView(inputRow, Page.full(ui.dp(12), 0));

        TextView result = ui.text(15, ui.text, Ui.MEDIUM);
        result.setVisibility(View.GONE);
        b.addView(result, Page.full(ui.dp(12), 0));
        TextView answer = ui.text(19, ui.text, Ui.REGULAR);
        answer.setVisibility(View.GONE);
        answer.setTextIsSelectable(true);
        b.addView(answer, Page.full(ui.dp(4), 0));
        TextView meaning = ui.text(14, ui.sub, Ui.REGULAR);
        meaning.setVisibility(View.GONE);
        meaning.setLineSpacing(0, 1.25f);
        Ui.bidi(meaning);
        b.addView(meaning, Page.full(ui.dp(6), 0));

        boolean[] done = {false};
        Runnable run = () -> {
            if (done[0]) return;
            String typed = in.getText().toString();
            if (Text.clean(typed).isEmpty()) {
                in.requestFocus();
                Feel.wrong(in);
                return;
            }
            done[0] = true;
            Dictation.Result r = grade(item.text, typed);
            in.setEnabled(false);
            check.setVisibility(View.GONE);
            boolean ok = r.grade == Dictation.Grade.RIGHT;
            result.setText(ok ? getString(R.string.correct)
                    : r.grade == Dictation.Grade.ALMOST ? getString(R.string.almost, note(r.note)) : getString(R.string.wrong));
            result.setTextColor(ok ? green() : r.grade == Dictation.Grade.ALMOST ? ui.accent : ui.danger);
            result.setVisibility(View.VISIBLE);
            answer.setText(marked(item.text, ok ? new boolean[item.text.length()] : r.missed));
            answer.setVisibility(View.VISIBLE);
            if (item.meaning != null && !item.meaning.isEmpty()) {
                meaning.setText(item.meaning);
                meaning.setVisibility(View.VISIBLE);
            }
            if (ok) {
                right++;
                Feel.right(b);
            } else {
                Feel.wrong(b);
            }
            checked++;
            if (checked == items.size()) {
                View s = page.content.findViewWithTag("score");
                if (s instanceof TextView) {
                    ((TextView) s).setText(getString(R.string.score, right, items.size()));
                    s.setVisibility(View.VISIBLE);
                }
                InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) im.hideSoftInputFromWindow(in.getWindowToken(), 0);
            } else {
                focusNext(index);
            }
        };
        check.setOnClickListener(v -> run.run());
        in.setOnEditorActionListener((v, id, ev) -> {
            run.run();
            return true;
        });
        return b;
    }

    /** German nouns: the article counts, but forgetting it is only "almost". */
    private Dictation.Result grade(String expected, String typed) {
        Dictation.Result r = Dictation.check(expected, typed, lang);
        if (r.grade == Dictation.Grade.WRONG && lang == Lang.DE && expected.matches("(der|die|das) .+")) {
            String noun = expected.substring(4);
            Dictation.Result without = Dictation.check(noun, typed, lang);
            if (without.grade == Dictation.Grade.RIGHT) return new Dictation.Result(Dictation.Grade.ALMOST, "article", Dictation.missed(expected, typed));
        }
        return r;
    }

    private String note(String n) {
        switch (n) {
            case Dictation.NOTE_CAPITAL: return getString(R.string.note_capital);
            case Dictation.NOTE_UMLAUT: return getString(R.string.note_umlaut);
            case Dictation.NOTE_TYPO: return getString(R.string.note_typo);
            case "article": return getString(R.string.note_article);
            default: return n;
        }
    }

    /** The right answer with the letters that were missed in red on a soft background. */
    private CharSequence marked(String text, boolean[] missed) {
        SpannableStringBuilder b = new SpannableStringBuilder(text);
        for (int i = 0; i < text.length() && i < missed.length; i++) {
            if (!missed[i]) continue;
            b.setSpan(new ForegroundColorSpan(ui.danger), i, i + 1, 0);
            b.setSpan(new BackgroundColorSpan(ui.danger & 0x26FFFFFF), i, i + 1, 0);
        }
        return b;
    }

    private void focusNext(int index) {
        LinearLayout c = page.content;
        int seen = -1;
        for (int i = 0; i < c.getChildCount(); i++) {
            View v = c.getChildAt(i);
            EditText e = v instanceof ViewGroup ? findEdit((ViewGroup) v) : null;
            if (e == null) continue;
            seen++;
            if (seen > index && e.isEnabled()) {
                e.requestFocus();
                return;
            }
        }
    }

    private static EditText findEdit(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof EditText) return (EditText) v;
            if (v instanceof ViewGroup) {
                EditText e = findEdit((ViewGroup) v);
                if (e != null) return e;
            }
        }
        return null;
    }
}
