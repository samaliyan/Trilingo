package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Grammar exercises, five at a time, with "5 new questions" for another set:
 * "Practice" (for one lesson, or mixed up to a level) and "Find the mistake" (which sentence is correct?).
 */
public final class PracticeActivity extends Activity {
    static final String EXTRA_MODE = "mode", EXTRA_LESSON = "lesson", EXTRA_LANG = "lang", EXTRA_LEVEL = "level";
    static final String MODE_PRACTICE = "practice", MODE_MISTAKES = "mistakes";
    private static final int N = 5;
    /** Kept while the app runs, so "5 new questions" does not repeat questions from before. */
    private static Exercises exercises;

    private Ui ui;
    private Page page;
    private int answered, right;
    private final List<Exercises.Question> current = new ArrayList<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        boolean mistakes = MODE_MISTAKES.equals(getIntent().getStringExtra(EXTRA_MODE));
        page = new Page(this, ui, getString(mistakes ? R.string.find_mistake : R.string.practice));
        TextView wait = ui.text(15, ui.sub, Ui.REGULAR);
        wait.setText(R.string.loading);
        page.content.addView(wait);
        Lexicon.load(this, () -> runOnUiThread(() -> {
            if (!isDestroyed()) next();
        }));
    }

    private Exercises exercises() {
        if (exercises == null && Lexicon.grammar() != null)
            exercises = new Exercises(Lexicon.grammar(), Lexicon.nouns(), Lexicon.germanLevels(), Lexicon.words(Lang.DE), new Random());
        return exercises;
    }

    private Grammar.Lesson lesson() {
        String id = getIntent().getStringExtra(EXTRA_LESSON);
        return id == null || Lexicon.grammar() == null ? null : Lexicon.grammar().get(id);
    }

    private Lang lang() {
        Grammar.Lesson l = lesson();
        if (l != null) return l.lang;
        Lang x = Lang.of(getIntent().getStringExtra(EXTRA_LANG));
        return x == null ? Lang.DE : x;
    }

    private void next() {
        Exercises ex = exercises();
        if (ex == null) return;
        boolean mistakes = MODE_MISTAKES.equals(getIntent().getStringExtra(EXTRA_MODE));
        Grammar.Lesson l = lesson();
        current.clear();
        if (mistakes) current.addAll(ex.mistakes(lang(), l, N));
        else if (l != null) current.addAll(ex.practice(l, N));
        else current.addAll(ex.practice(lang(), getIntent().getStringExtra(EXTRA_LEVEL), N));
        answered = 0;
        right = 0;
        render(l);
    }

    private void render(Grammar.Lesson l) {
        LinearLayout c = page.content;
        c.removeAllViews();
        TextView sub = ui.text(14, ui.sub, Ui.MEDIUM);
        String about = l != null ? l.title(Lang.EN) : (lang() == Lang.DE ? getString(R.string.grammar_german) : getString(R.string.grammar_english))
                + (getIntent().getStringExtra(EXTRA_LEVEL) == null ? "" : " · " + getString(R.string.up_to_level, getIntent().getStringExtra(EXTRA_LEVEL)));
        sub.setText(about);
        c.addView(sub, Page.full(0, ui.dp(8)));
        if (current.isEmpty()) {
            TextView t = ui.text(16, ui.text, Ui.MEDIUM);
            t.setText(R.string.practice_none);
            c.addView(t, Page.full(ui.dp(24), 0));
            return;
        }
        for (int i = 0; i < current.size(); i++) c.addView(question(i + 1, current.get(i)));
        TextView score = ui.text(16, ui.text, Ui.MEDIUM);
        score.setGravity(Gravity.CENTER);
        score.setTag("score");
        score.setVisibility(View.GONE);
        c.addView(score, Page.full(ui.dp(8), ui.dp(8)));
        TextView more = ui.pill(getString(R.string.five_more), true);
        more.setOnClickListener(v -> {
            next();
            page.scroll.scrollTo(0, 0);
        });
        c.addView(more, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52)));
    }

    private int green() {
        return ui.dark ? 0xFF4ADE80 : 0xFF16A34A;
    }

    private void finished() {
        answered++;
        if (answered < current.size()) return;
        View s = page.content.findViewWithTag("score");
        if (s instanceof TextView) {
            ((TextView) s).setText(getString(R.string.score, right, current.size()));
            s.setVisibility(View.VISIBLE);
        }
    }

    private LinearLayout box() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(ui.round(ui.surface, 18));
        b.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16));
        b.setLayoutParams(Page.full(0, ui.dp(12)));
        return b;
    }

    private View question(int n, Exercises.Question q) {
        LinearLayout b = box();
        TextView num = ui.line(12, ui.accent, Ui.MEDIUM);
        num.setText(getString(R.string.question_n, n));
        b.addView(num);
        TextView p = ui.text(18, ui.text, Ui.MEDIUM);
        p.setText(q.prompt);
        p.setLineSpacing(0, 1.2f);
        GrammarViews.direction(p, Lang.EN);
        b.addView(p, Page.full(ui.dp(6), 0));
        if (!q.hint.isEmpty()) {
            TextView h = ui.text(14, ui.sub, Ui.REGULAR);
            boolean persian = Text.hasPersian(q.hint);
            GrammarViews.show(h, q.hint, persian ? Lang.FA : Lang.EN);
            b.addView(h, Page.full(ui.dp(4), 0));
        }
        TextView result = ui.text(14, ui.sub, Ui.REGULAR);
        result.setVisibility(View.GONE);
        result.setLineSpacing(0, 1.2f);
        if (q.type == Exercises.Question.ORDER) order(b, q, result);
        else choice(b, q, result);
        b.addView(result, Page.full(ui.dp(10), 0));
        if (q.lessonId != null && Lexicon.grammar() != null && Lexicon.grammar().get(q.lessonId) != null
                && !q.lessonId.equals(getIntent().getStringExtra(EXTRA_LESSON))) {
            Grammar.Lesson l = Lexicon.grammar().get(q.lessonId);
            TextView open = ui.text(14, ui.accent, Ui.MEDIUM);
            open.setText(getString(R.string.open_lesson, l.title(Lang.EN)));
            open.setPadding(0, ui.dp(10), 0, ui.dp(4));
            open.setVisibility(View.GONE);
            open.setTag("lesson");
            open.setOnClickListener(v -> GrammarViews.open(this, l));
            b.addView(open, Page.full());
        }
        return b;
    }

    private void reveal(LinearLayout b, TextView result, boolean ok, String text) {
        result.setText((ok ? getString(R.string.correct) : getString(R.string.wrong)) + (text.isEmpty() ? "" : "  " + text));
        result.setTextColor(ok ? green() : ui.danger);
        result.setVisibility(View.VISIBLE);
        View lesson = b.findViewWithTag("lesson");
        if (lesson != null && !ok) lesson.setVisibility(View.VISIBLE);
        if (ok) right++;
        finished();
    }

    private void choice(LinearLayout b, Exercises.Question q, TextView result) {
        List<TextView> buttons = new ArrayList<>();
        for (int i = 0; i < q.options.size(); i++) {
            final int k = i;
            TextView o = ui.text(16, ui.text, Ui.REGULAR);
            o.setText(q.options.get(i));
            o.setMinHeight(ui.dp(48));
            o.setGravity(Gravity.CENTER_VERTICAL);
            o.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
            o.setBackground(ui.ripple(ui.outline(ui.surface, ui.line, 14), 14));
            o.setClickable(true);
            o.setFocusable(true);
            o.setTextDirection(View.TEXT_DIRECTION_LTR);
            o.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
            buttons.add(o);
            b.addView(o, Page.full(ui.dp(8), 0));
            o.setOnClickListener(v -> {
                for (TextView x : buttons) x.setClickable(false);
                boolean ok = k == q.answer;
                buttons.get(q.answer).setBackground(ui.outline(ui.surface, green(), 14));
                buttons.get(q.answer).setTextColor(green());
                if (!ok) {
                    o.setBackground(ui.outline(ui.surface, ui.danger, 14));
                    o.setTextColor(ui.danger);
                }
                reveal(b, result, ok, ok ? "" : q.explain);
            });
        }
    }

    /** Tap the words in order; tap a placed word to take it back. Checked when all words are placed. */
    private void order(LinearLayout b, Exercises.Question q, TextView result) {
        TextView built = ui.text(17, ui.text, Ui.MEDIUM);
        built.setMinHeight(ui.dp(48));
        built.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        built.setBackground(ui.round(ui.bg, 12));
        built.setHint(R.string.order_hint);
        built.setHintTextColor(ui.sub);
        b.addView(built, Page.full(ui.dp(10), 0));
        Flow words = new Flow(this, ui.dp(8));
        b.addView(words, Page.full(ui.dp(10), 0));
        List<String> placed = new ArrayList<>();
        List<TextView> chips = new ArrayList<>();
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            built.setText(String.join(" ", placed));
            if (placed.size() == q.options.size()) {
                for (TextView t : chips) t.setClickable(false);
                built.setClickable(false);
                boolean ok = String.join(" ", placed).equals(q.explain);
                built.setTextColor(ok ? green() : ui.danger);
                reveal(b, result, ok, ok ? "" : q.explain);
            }
        };
        for (String w : q.options) {
            TextView chip = ui.pill(w, false);
            chip.setAllCaps(false);
            chip.setOnClickListener(v -> {
                placed.add(w);
                chip.setVisibility(View.INVISIBLE);
                chip.setClickable(false);
                refresh[0].run();
            });
            chips.add(chip);
            words.addView(chip);
        }
        built.setOnClickListener(v -> {                    // take the last word back
            if (placed.isEmpty() || placed.size() == q.options.size()) return;
            String last = placed.remove(placed.size() - 1);
            for (TextView t : chips) if (t.getVisibility() == View.INVISIBLE && t.getText().toString().equals(last)) {
                t.setVisibility(View.VISIBLE);
                t.setClickable(true);
                break;
            }
            refresh[0].run();
        });
        built.setContentDescription(getString(R.string.order_desc));
    }

    /** Lays out its children in rows, wrapping like words in a line of text. */
    static final class Flow extends android.view.ViewGroup {
        private final int gap;

        Flow(android.content.Context c, int gap) {
            super(c);
            this.gap = gap;
        }

        @Override protected void onMeasure(int ws, int hs) {
            int width = MeasureSpec.getSize(ws);
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                ch.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + ch.getMeasuredWidth() > width) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                x += ch.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, ch.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + rowH);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l, x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (x > 0 && x + ch.getMeasuredWidth() > width) {
                    x = 0;
                    y += rowH + gap;
                    rowH = 0;
                }
                ch.layout(x, y, x + ch.getMeasuredWidth(), y + ch.getMeasuredHeight());
                x += ch.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, ch.getMeasuredHeight());
            }
        }
    }
}
