package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * "Practice words": active practice with your words, ten questions a round, one at a time.
 * Write the word (its translation is shown), der · die · das, Verb forms, and Say it (the phone listens and
 * tells you whether it understood the word). Built from the saved English and German words; common words of
 * your level fill in when there are few. Every answer counts on the Progress page.
 */
public final class WordPracticeActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int ROUND = 10, SPEECH = 81;

    private Ui ui;
    private Page page;
    private Store store;
    private Notebook nb;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final Random random = new Random();
    private List<WordQuiz.Question> questions = new ArrayList<>();
    private int kind, at, right;
    private boolean answered;
    private TextView feedback, next;
    private EditText field;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        store = new Store(this);
        nb = Store.notebook(this);
        page = new Page(this, ui, getString(R.string.practice_words));
        try {
            tts = new TextToSpeech(getApplicationContext(), this);
        } catch (RuntimeException e) {
            tts = null;
        }
        TextView wait = ui.text(15, ui.sub, Ui.REGULAR);
        wait.setText(R.string.loading);
        page.content.addView(wait);
        Lexicon.load(this, () -> runOnUiThread(() -> {
            if (!isDestroyed()) menu();
        }));
    }

    @Override public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
    }

    @Override protected void onPause() {
        super.onPause();
        WidgetProvider.updateAll(this);
    }

    @Override protected void onDestroy() {
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
                // gone
            }
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (!questions.isEmpty()) {
            questions = new ArrayList<>();
            menu();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ the four kinds

    private void menu() {
        questions = new ArrayList<>();
        LinearLayout c = page.content;
        c.removeAllViews();
        int savedCount = 0;
        for (Notebook.Card card : nb.saved(null)) if (card.lang != Lang.FA) savedCount++;
        TextView intro = ui.text(15, ui.sub, Ui.REGULAR);
        intro.setText(savedCount >= ROUND ? getString(R.string.practice_from_saved, savedCount) : getString(R.string.practice_from_level));
        intro.setLineSpacing(0, 1.25f);
        c.addView(intro, Page.full(ui.dp(4), ui.dp(16)));
        int[] kinds = {WordQuiz.WRITE, WordQuiz.ARTICLE, WordQuiz.VERB, WordQuiz.SAY};
        int[] names = {R.string.practice_write, R.string.practice_article, R.string.practice_verbs, R.string.practice_say};
        int[] notes = {R.string.practice_write_note, R.string.practice_article_note, R.string.practice_verbs_note, R.string.practice_say_note};
        Icons.Kind[] icons = {Icons.Kind.PEN, Icons.Kind.BOOK, Icons.Kind.TABLE, Icons.Kind.MIC};
        for (int i = 0; i < kinds.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(ui.ripple(ui.round(ui.surface, 18), 18));
            row.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
            row.setMinimumHeight(ui.dp(64));
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageDrawable(Icons.of(icons[i], ui.accent));
            row.addView(icon, new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)));
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = ui.line(17, ui.text, Ui.MEDIUM);
            t.setText(names[i]);
            texts.addView(t);
            TextView n = ui.text(13, ui.sub, Ui.REGULAR);
            n.setText(notes[i]);
            texts.addView(n);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tp.leftMargin = ui.dp(14);
            row.addView(texts, tp);
            final int k = kinds[i];
            row.setOnClickListener(v -> start(k));
            c.addView(row, Page.full(0, ui.dp(10)));
        }
        TextView progress = ui.pill(getString(R.string.progress), false);
        progress.setOnClickListener(v -> startActivity(new Intent(this, ProgressActivity.class)));
        c.addView(progress, Page.full(ui.dp(8), ui.dp(16)));
        progress.getLayoutParams().height = ui.dp(46);
    }

    /** The saved English and German words, and common words of the learner's level when there are few. */
    private List<WordQuiz.Item> items() {
        List<WordQuiz.Item> out = new ArrayList<>();
        for (Notebook.Card card : nb.saved(null))
            if (card.lang != Lang.FA) out.add(new WordQuiz.Item(card.lang, card.word, card.back));
        if (out.size() >= 3 * ROUND) return out;
        for (Lang l : new Lang[]{Lang.DE, Lang.EN}) {
            Levels lv = Lexicon.levels(l);
            Dict d = Lexicon.dict(l);
            if (lv == null || d == null) continue;
            List<String> words = lv.words(store.myLevel(l));
            Collections.shuffle(words, random);
            int added = 0;
            for (String w : words) {
                if (added >= 25) break;
                Dict.Entry e = d.find(w);
                if (e == null) continue;
                String back = d.summary(e.word);
                if (back == null || back.isEmpty()) continue;
                out.add(new WordQuiz.Item(l, e.word, back));
                added++;
            }
        }
        return out;
    }

    private void start(int k) {
        kind = k;
        WordQuiz quiz = new WordQuiz(Lexicon.nouns(), random, Lexicon::mainlyVerb);
        questions = quiz.make(k, items(), ROUND);
        if (questions.isEmpty()) {
            Toast.makeText(this, k == WordQuiz.ARTICLE ? R.string.practice_no_nouns : k == WordQuiz.VERB
                    ? R.string.practice_no_verbs : R.string.practice_no_words, Toast.LENGTH_LONG).show();
            return;
        }
        at = 0;
        right = 0;
        show();
    }

    // ------------------------------------------------------------------ one question

    private void show() {
        if (at >= questions.size()) {
            done();
            return;
        }
        answered = false;
        WordQuiz.Question q = questions.get(at);
        LinearLayout c = page.content;
        c.removeAllViews();
        TextView count = ui.line(12, ui.accent, Ui.MEDIUM);
        count.setText(getString(R.string.practice_count, at + 1, questions.size()));
        c.addView(count, Page.full(ui.dp(4), ui.dp(10)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(ui.surface, 20));
        card.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(18));
        TextView ask = ui.text(14, ui.sub, Ui.REGULAR);
        ask.setText(task(q));
        card.addView(ask);
        TextView prompt = ui.text(q.prompt.length() > 24 ? 22 : 30, ui.text, Ui.MEDIUM);
        prompt.setText(q.prompt);
        Ui.bidi(prompt);
        card.addView(prompt, Page.full(ui.dp(8), 0));
        if (!q.hint.isEmpty() && q.kind != WordQuiz.WRITE) {
            TextView hint = ui.text(15, ui.sub, Ui.REGULAR);
            hint.setText(q.hint);
            Ui.bidi(hint);
            card.addView(hint, Page.full(ui.dp(4), 0));
        }
        c.addView(card, Page.full());

        field = null;
        if (q.kind == WordQuiz.WRITE || q.kind == WordQuiz.VERB) {
            field = new EditText(this);
            field.setHint(q.kind == WordQuiz.WRITE ? getString(R.string.practice_type_word, q.lang.label) : getString(R.string.practice_type_form));
            field.setHintTextColor(ui.sub);
            field.setTextColor(ui.text);
            field.setTextSize(19);
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
            field.setBackground(ui.outline(ui.bg, ui.line, 14));
            field.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
            field.setContentDescription(getString(R.string.practice_answer));
            field.setOnEditorActionListener((v, id, e) -> {
                answerTyped();
                return true;
            });
            c.addView(field, Page.full(ui.dp(14), 0));
            TextView check = ui.pill(getString(R.string.check), true);
            check.setOnClickListener(v -> answerTyped());
            c.addView(check, Page.full(ui.dp(10), 0));
            check.getLayoutParams().height = ui.dp(48);
            field.requestFocus();
            field.postDelayed(() -> {
                InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (im != null && field != null) im.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }, 250);
        } else if (q.kind == WordQuiz.ARTICLE) {
            LinearLayout row = new LinearLayout(this);
            for (int i = 0; i < q.choices.size(); i++) {
                String a = q.choices.get(i);
                TextView b = ui.pill(a, false);
                b.setTextSize(18);
                b.setOnClickListener(v -> answer(WordQuiz.check(q, a), a));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ui.dp(56), 1);
                if (i > 0) lp.leftMargin = ui.dp(10);
                row.addView(b, lp);
            }
            c.addView(row, Page.full(ui.dp(14), 0));
        } else {
            LinearLayout row = new LinearLayout(this);
            LinearLayout listen = ui.actionPill(Icons.Kind.SPEAK, getString(R.string.listen));
            listen.setOnClickListener(v -> say(q.prompt, q.lang));
            row.addView(listen);
            LinearLayout mic = ui.actionPill(Icons.Kind.MIC, getString(R.string.practice_say_now));
            mic.setOnClickListener(v -> listen(q.lang));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mp.leftMargin = ui.dp(10);
            row.addView(mic, mp);
            c.addView(row, Page.full(ui.dp(14), 0));
            TextView skip = ui.pill(getString(R.string.practice_skip), false);
            skip.setOnClickListener(v -> {
                if (!answered) answer(WordQuiz.WRONG, null);
            });
            c.addView(skip, Page.full(ui.dp(10), 0));
            skip.getLayoutParams().height = ui.dp(44);
        }

        feedback = ui.text(16, ui.text, Ui.MEDIUM);
        feedback.setLineSpacing(0, 1.25f);
        feedback.setVisibility(View.GONE);
        Ui.bidi(feedback);
        c.addView(feedback, Page.full(ui.dp(16), 0));
        next = ui.pill(getString(at + 1 < questions.size() ? R.string.next : R.string.practice_finish), true);
        next.setVisibility(View.GONE);
        next.setOnClickListener(v -> {
            at++;
            show();
        });
        c.addView(next, Page.full(ui.dp(12), ui.dp(16)));
        next.getLayoutParams().height = ui.dp(48);
    }

    private String task(WordQuiz.Question q) {
        switch (q.kind) {
            case WordQuiz.WRITE: return getString(R.string.practice_write_task, q.lang.label);
            case WordQuiz.ARTICLE: return getString(R.string.practice_article_task);
            case WordQuiz.VERB: return getString(R.string.practice_verb_task);
            default: return getString(R.string.practice_say_task);
        }
    }

    private void answerTyped() {
        if (answered || field == null) return;
        String t = field.getText().toString();
        if (Text.clean(t).isEmpty()) {
            field.requestFocus();
            return;
        }
        answer(WordQuiz.check(questions.get(at), t), t);
    }

    /** Shows how it went, counts it (Progress), and offers Next. */
    private void answer(int verdict, String given) {
        if (answered) return;
        answered = true;
        WordQuiz.Question q = questions.get(at);
        boolean ok = verdict != WordQuiz.WRONG;
        if (ok) right++;
        long now = System.currentTimeMillis();
        nb.practiced(ok, now);
        if (q.kind == WordQuiz.SAY && ok) nb.spoke(now);
        int green = ui.dark ? 0xFF4ADE80 : 0xFF16A34A, orange = ui.dark ? 0xFFFBBF24 : 0xFFD97706;
        if (verdict == WordQuiz.RIGHT) {
            feedback.setText(getString(R.string.practice_right, q.answer));
            feedback.setTextColor(green);
            Feel.right(feedback);
        } else if (verdict == WordQuiz.ALMOST) {
            feedback.setText(q.kind == WordQuiz.SAY ? getString(R.string.practice_say_almost, given == null ? "" : given, q.answer)
                    : getString(R.string.practice_almost, q.answer));
            feedback.setTextColor(orange);
            Feel.right(feedback);
        } else {
            feedback.setText(q.kind == WordQuiz.SAY && given != null ? getString(R.string.practice_say_wrong, given, q.answer)
                    : getString(R.string.practice_wrong, q.answer));
            feedback.setTextColor(ui.danger);
            Feel.wrong(feedback);
        }
        feedback.setVisibility(View.VISIBLE);
        next.setVisibility(View.VISIBLE);
        if (field != null) field.setEnabled(false);
        if (q.kind != WordQuiz.SAY) say(q.answer, q.lang);
        page.content.post(() -> page.scroll.smoothScrollTo(0, Math.max(0, next.getBottom() - page.scroll.getHeight() + ui.dp(24))));
    }

    private void done() {
        LinearLayout c = page.content;
        c.removeAllViews();
        TextView big = ui.text(34, ui.text, Ui.MEDIUM);
        big.setText(getString(R.string.practice_score, right, questions.size()));
        c.addView(big, Page.full(ui.dp(24), ui.dp(6)));
        TextView streak = ui.text(16, ui.sub, Ui.REGULAR);
        int s = nb.progress.streak(System.currentTimeMillis());
        streak.setText(getResources().getQuantityString(R.plurals.streak_days, s, s));
        c.addView(streak, Page.full(0, ui.dp(20)));
        TextView again = ui.pill(getString(R.string.practice_again), true);
        again.setOnClickListener(v -> start(kind));
        c.addView(again, Page.full(0, ui.dp(10)));
        again.getLayoutParams().height = ui.dp(48);
        TextView other = ui.pill(getString(R.string.practice_other), false);
        other.setOnClickListener(v -> menu());
        c.addView(other, Page.full(0, ui.dp(10)));
        other.getLayoutParams().height = ui.dp(48);
        questions = new ArrayList<>();
    }

    // ------------------------------------------------------------------ speech

    private void say(String text, Lang l) {
        if (tts == null || !ttsReady) return;
        try {
            tts.setLanguage(MainActivity.locale(l));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "practice");
        } catch (RuntimeException ignored) {
            // no voice for this language
        }
    }

    @SuppressWarnings("deprecation")
    private void listen(Lang l) {
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        if (it.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        String tag = l == Lang.DE ? "de-DE" : "en-US";
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        it.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.practice_say_prompt, questions.get(at).prompt));
        try {
            startActivityForResult(it, SPEECH);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != SPEECH || questions.isEmpty() || at >= questions.size() || answered) return;
        if (result != RESULT_OK || data == null) return;
        List<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        WordQuiz.Question q = questions.get(at);
        int v = WordQuiz.heard(q, heard);
        if (v == WordQuiz.WRONG) {
            // not yet: say what was heard, and let the learner try again (Skip moves on)
            feedback.setText(getString(R.string.practice_say_try, heard.get(0)));
            feedback.setTextColor(ui.danger);
            feedback.setVisibility(View.VISIBLE);
            Feel.wrong(feedback);
            return;
        }
        answer(v, heard.get(0));
    }
}
