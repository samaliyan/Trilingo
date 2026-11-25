package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Flashcards with spaced repetition: the word first, the translation after "Show answer".
 * "Again" brings it back in 10 minutes; "Good" and "Easy" push it further each time.
 */
public final class ReviewActivity extends Activity implements TextToSpeech.OnInitListener {
    /** Folder to review; {@link #ALL_SAVED} = all saved words; missing = saved and recently looked-up words. */
    static final String EXTRA_FOLDER = "folder";
    static final String ALL_SAVED = "\u0001all";

    private Ui ui;
    private Notebook nb;
    private Page page;
    private final List<Notebook.Card> queue = new ArrayList<>();
    private int done, total;
    private TextToSpeech tts;
    private boolean ttsReady;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        nb = Store.notebook(this);
        page = new Page(this, ui, getString(R.string.review));
        try {
            tts = new TextToSpeech(getApplicationContext(), this);
        } catch (RuntimeException e) {
            tts = null;
        }
        // the word lists decide which recent words are real words: loaded first (at once when already loaded)
        Lexicon.load(this, () -> runOnUiThread(() -> {
            if (!isDestroyed()) start(false);
        }));
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

    @Override public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
    }

    private String folder() {
        String f = getIntent().getStringExtra(EXTRA_FOLDER);
        return ALL_SAVED.equals(f) ? null : f;
    }

    private List<Lookup> recent() {
        boolean fromHome = getIntent().getStringExtra(EXTRA_FOLDER) == null;
        return fromHome && new Store(this).reviewRecent()
                ? Notebook.reviewable(Store.history(this).items(), Lexicon.words(Lang.EN), Lexicon.words(Lang.DE)) : null;
    }

    /** Fills the queue with the cards due now; {@code anyway}: the oldest cards even if not due. */
    private void start(boolean anyway) {
        queue.clear();
        done = 0;
        long now = System.currentTimeMillis();
        queue.addAll(nb.due(folder(), recent(), anyway ? Long.MAX_VALUE : now, 20));
        if (anyway) Collections.shuffle(queue);
        total = queue.size();
        if (queue.isEmpty()) empty();
        else front();
    }

    private void empty() {
        LinearLayout c = page.content;
        c.removeAllViews();
        page.title.setText(R.string.review);
        TextView t = ui.text(20, ui.text, Ui.MEDIUM);
        t.setText(done > 0 ? getResources().getQuantityString(R.plurals.reviewed_n, done, done) : getString(R.string.review_nothing));
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(16), ui.dp(48), ui.dp(16), 0);
        c.addView(t, Page.full());
        TextView h = ui.text(15, ui.sub, Ui.REGULAR);
        h.setText(getResources().getQuantityString(R.plurals.learned_n, nb.learned(), nb.learned()));
        h.setGravity(Gravity.CENTER);
        h.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), 0);
        c.addView(h, Page.full());
        if (nb.due(folder(), recent(), Long.MAX_VALUE, 1).isEmpty()) {
            TextView hint = ui.text(14, ui.sub, Ui.REGULAR);
            hint.setText(R.string.review_empty_hint);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(0, 1.25f);
            hint.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), 0);
            c.addView(hint, Page.full());
            return;
        }
        TextView anyway = ui.pill(getString(R.string.review_anyway), false);
        anyway.setOnClickListener(v -> start(true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = ui.dp(24);
        c.addView(anyway, lp);
    }

    private LinearLayout card(Notebook.Card c) {
        page.title.setText(getString(R.string.review_progress, done + 1, total));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackground(ui.round(ui.surface, 22));
        box.setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(24));
        box.setMinimumHeight(ui.dp(220));
        TextView meta = ui.line(13, ui.sub, Ui.MEDIUM);
        String level = Lexicon.level(c.word, c.lang);
        meta.setText(c.lang.label + (level == null ? "" : " · " + level) + (c.isNew() ? " · " + getString(R.string.new_word) : ""));
        box.addView(meta);
        TextView word = ui.text(30, ui.text, Ui.MEDIUM);
        word.setText(c.word);
        word.setGravity(Gravity.CENTER);
        Ui.bidi(word);
        word.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        box.addView(word, Page.full(ui.dp(14), 0));
        Nouns n = Lexicon.nouns();
        if (c.lang == Lang.DE && n != null && Character.isUpperCase(c.word.charAt(0))) {
            Nouns.Info info = n.lookup(c.word);
            if (info != null && info.genders.size() == 1) {
                TextView art = ui.text(16, ui.sub, Ui.MEDIUM);
                art.setText(Nouns.article(info.genders.get(0)) + " " + info.noun);
                art.setGravity(Gravity.CENTER);
                box.addView(art, Page.full(ui.dp(4), 0));
            }
        }
        if (ttsReady && tts != null) {
            ImageButton say = ui.iconButton(Icons.Kind.SPEAK, ui.accent, getString(R.string.speak_lang, c.lang.label));
            say.setOnClickListener(v -> speak(c));
            box.addView(say, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        }
        return box;
    }

    private void speak(Notebook.Card c) {
        try {
            tts.setLanguage(c.lang == Lang.DE ? Locale.GERMANY : c.lang == Lang.EN ? Locale.US : new Locale("fa", "IR"));
            tts.speak(c.word, TextToSpeech.QUEUE_FLUSH, null, "review");
        } catch (RuntimeException ignored) {
            // no voice for this language
        }
    }

    private void front() {
        Notebook.Card c = queue.get(0);
        LinearLayout content = page.content;
        content.removeAllViews();
        View front = card(c);
        content.addView(front, Page.full(ui.dp(8), 0));
        Feel.appear(front);
        TextView hint = ui.text(14, ui.sub, Ui.REGULAR);
        hint.setText(R.string.review_think);
        hint.setGravity(Gravity.CENTER);
        content.addView(hint, Page.full(ui.dp(16), 0));
        TextView show = ui.pill(getString(R.string.show_answer), true);
        show.setOnClickListener(v -> back());
        content.addView(show, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52)));
        ((LinearLayout.LayoutParams) show.getLayoutParams()).topMargin = ui.dp(20);
    }

    private void back() {
        Notebook.Card c = queue.get(0);
        LinearLayout content = page.content;
        content.removeAllViews();
        LinearLayout box = card(c);
        View sep = new View(this);
        sep.setBackgroundColor(ui.line);
        box.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)));
        ((LinearLayout.LayoutParams) sep.getLayoutParams()).topMargin = ui.dp(16);
        for (String line : c.back.split("\n")) {
            TextView t = ui.text(18, ui.text, Ui.REGULAR);
            t.setText(line);
            t.setGravity(Gravity.CENTER);
            Ui.bidi(t);
            t.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            box.addView(t, Page.full(ui.dp(12), 0));
        }
        content.addView(box, Page.full(ui.dp(8), 0));
        flip(box);
        if (ttsReady && tts != null) speak(c);

        TextView q = ui.text(14, ui.sub, Ui.REGULAR);
        q.setText(R.string.review_how);
        q.setGravity(Gravity.CENTER);
        content.addView(q, Page.full(ui.dp(18), ui.dp(8)));
        String[] when = Notebook.preview(c);
        int[] names = {R.string.again, R.string.good, R.string.easy};
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < 3; i++) {
            final int answer = i;
            LinearLayout b = new LinearLayout(this);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setGravity(Gravity.CENTER);
            b.setMinimumHeight(ui.dp(64));
            boolean main = i == 1;
            b.setBackground(ui.ripple(main ? ui.round(ui.accent, 18) : ui.outline(ui.surface, ui.line, 18), 18));
            TextView n = ui.text(16, main ? ui.onAccent : i == 0 ? ui.danger : ui.text, Ui.MEDIUM);
            n.setText(names[i]);
            n.setGravity(Gravity.CENTER);
            b.addView(n, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView w = ui.text(12, main ? ui.onAccent : ui.sub, Ui.REGULAR);
            w.setText(when[i]);
            w.setGravity(Gravity.CENTER);
            b.addView(w, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            b.setContentDescription(getString(names[i]) + ", " + when[i]);
            b.setClickable(true);
            b.setFocusable(true);
            b.setOnClickListener(v -> {
                if (answer == Notebook.AGAIN) Feel.wrong(v);
                else Feel.tick(v);
                answer(answer);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = ui.dp(10);
            row.addView(b, lp);
        }
        content.addView(row, Page.full());
    }

    /** The card turns over to its back. */
    private void flip(View card) {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        card.setCameraDistance(card.getResources().getDisplayMetrics().density * 8000);
        card.setRotationY(-90);
        card.setAlpha(0.4f);
        card.animate().rotationY(0).alpha(1f).setDuration(260)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    private void answer(int a) {
        Notebook.Card c = queue.remove(0);
        nb.answer(c, a, System.currentTimeMillis());
        if (a == Notebook.AGAIN) queue.add(c);         // once more in this session
        else done++;
        if (queue.isEmpty()) empty();
        else front();
    }
}
