package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;

/**
 * Check a sentence you wrote (German, English or Persian): mistakes are marked, with a short explanation,
 * suggested corrections (tap to apply) and the grammar lesson that explains the rule. Uses LanguageTool.
 */
public final class CheckActivity extends Activity {
    private Ui ui;
    private Page page;
    private EditText text;
    private LinearLayout results;
    private Lang lang = Lang.DE;
    private final TextView[] langChips = new TextView[3];
    private static final Lang[] LANGS = {Lang.DE, Lang.EN, Lang.FA};
    private int generation;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        if (saved != null && Lang.of(saved.getString("lang")) != null) lang = Lang.of(saved.getString("lang"));
        page = new Page(this, ui, getString(R.string.check_sentence));
        LinearLayout c = page.content;

        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < 3; i++) {
            final Lang l = LANGS[i];
            TextView t = ui.text(14, ui.text, Ui.MEDIUM);
            t.setText(l.label);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(ui.dp(40));
            t.setClickable(true);
            t.setFocusable(true);
            t.setOnClickListener(v -> {
                lang = l;
                chips();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = ui.dp(8);
            row.addView(t, lp);
            langChips[i] = t;
        }
        c.addView(row, Page.full(ui.dp(4), ui.dp(12)));

        text = new EditText(this);
        text.setId(R.id.check_text);
        text.setHint(R.string.check_hint);
        text.setHintTextColor(ui.sub);
        text.setTextColor(ui.text);
        text.setTextSize(17 * ui.scale);
        text.setMinLines(3);
        text.setGravity(Gravity.TOP | Gravity.START);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        text.setBackground(ui.outline(ui.surface, ui.line, 16));
        text.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        Ui.bidi(text);
        c.addView(text, Page.full());

        TextView check = ui.pill(getString(R.string.check), true);
        check.setOnClickListener(v -> check());
        c.addView(check, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(50)));
        ((LinearLayout.LayoutParams) check.getLayoutParams()).topMargin = ui.dp(12);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        c.addView(results, Page.full(ui.dp(12), 0));

        TextView practice = ui.pill(getString(R.string.find_mistake_practice), false);
        practice.setOnClickListener(v -> startActivity(new Intent(this, PracticeActivity.class)
                .putExtra(PracticeActivity.EXTRA_MODE, PracticeActivity.MODE_MISTAKES)
                .putExtra(PracticeActivity.EXTRA_LANG, (lang == Lang.FA ? Lang.DE : lang).code)));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.topMargin = ui.dp(20);
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        c.addView(practice, pp);
        TextView src = ui.text(11, ui.sub, Ui.REGULAR);
        src.setText(R.string.check_source);
        src.setGravity(Gravity.CENTER);
        c.addView(src, Page.full(ui.dp(12), 0));
        chips();
        Net.init(this);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("lang", lang.code);
    }

    private void chips() {
        for (int i = 0; i < 3; i++) {
            boolean on = LANGS[i] == lang;
            langChips[i].setTextColor(on ? ui.onAccent : ui.text);
            langChips[i].setBackground(ui.ripple(on ? ui.round(ui.accent, 20) : ui.outline(ui.surface, ui.line, 20), 20));
            langChips[i].setSelected(on);
            langChips[i].setContentDescription(getString(R.string.check_in, LANGS[i].label));
        }
    }

    private void check() {
        String s = text.getText().toString().trim();
        if (s.isEmpty()) return;
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(text.getWindowToken(), 0);
        int gen = ++generation;
        Lang l = lang;
        results.removeAllViews();
        ProgressBar p = new ProgressBar(this);
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        results.addView(p, new LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)));
        Net.run(() -> Exercises.parseCheck(Net.post(Exercises.checkUrl(), Exercises.checkBody(s, l))), (issues, err) -> {
            if (gen != generation || isDestroyed()) return;
            results.removeAllViews();
            if (issues == null) {
                note(getString(R.string.check_failed), ui.danger);
                return;
            }
            show(s, l, issues);
        });
    }

    private void note(String msg, int color) {
        TextView t = ui.text(16, color, Ui.MEDIUM);
        t.setText(msg);
        t.setLineSpacing(0, 1.2f);
        results.addView(t, Page.full(ui.dp(4), 0));
    }

    private void show(String s, Lang l, List<Exercises.Issue> issues) {
        int green = ui.dark ? 0xFF4ADE80 : 0xFF16A34A;
        if (issues.isEmpty()) {
            note(getString(R.string.check_ok), green);
            return;
        }
        // the sentence with the mistakes marked
        SpannableStringBuilder b = new SpannableStringBuilder(s);
        for (Exercises.Issue i : issues) {
            int a = Math.max(0, Math.min(s.length(), i.offset)), e = Math.max(a, Math.min(s.length(), i.offset + i.length));
            b.setSpan(new BackgroundColorSpan(ui.dark ? 0x55F0605A : 0x33DC3B33), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(ui.danger), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView marked = ui.text(18, ui.text, Ui.REGULAR);
        marked.setText(b);
        marked.setLineSpacing(0, 1.3f);
        GrammarViews.direction(marked, l);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(ui.surface, 18));
        card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        card.addView(marked);
        results.addView(card, Page.full(0, ui.dp(12)));
        note(getResources().getQuantityString(R.plurals.mistakes_found, issues.size(), issues.size()), ui.text);

        for (Exercises.Issue i : issues) {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setBackground(ui.round(ui.surface, 18));
            c.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
            String wrong = s.substring(Math.max(0, Math.min(s.length(), i.offset)), Math.max(0, Math.min(s.length(), i.offset + i.length)));
            TextView w = ui.text(16, ui.danger, Ui.MEDIUM);
            w.setText(wrong);
            GrammarViews.direction(w, l);
            c.addView(w);
            TextView m = ui.text(15, ui.text, Ui.REGULAR);
            m.setText(i.message);
            m.setLineSpacing(0, 1.2f);
            GrammarViews.direction(m, l == Lang.FA ? Lang.FA : Lang.EN);
            c.addView(m, Page.full(ui.dp(4), 0));
            if (!i.replacements.isEmpty()) {
                PracticeActivity.Flow fixes = new PracticeActivity.Flow(this, ui.dp(8));
                for (String r : i.replacements) {
                    TextView chip = ui.pill(r, false);
                    chip.setAllCaps(false);
                    chip.setContentDescription(getString(R.string.use_fix, r));
                    chip.setOnClickListener(v -> {
                        String cur = text.getText().toString();
                        if (cur.trim().equals(s)) {
                            int start = cur.indexOf(s);
                            String fixed = s.substring(0, i.offset) + r + s.substring(Math.min(s.length(), i.offset + i.length));
                            text.setText(cur.substring(0, start) + fixed + cur.substring(start + s.length()));
                            text.setSelection(text.getText().length());
                            check();
                        }
                    });
                    fixes.addView(chip);
                }
                c.addView(fixes, Page.full(ui.dp(8), 0));
            }
            String lessonId = Exercises.lessonFor(i, l);
            Grammar g = Lexicon.grammar();
            if (lessonId != null && g != null && g.get(lessonId) != null) {
                Grammar.Lesson lesson = g.get(lessonId);
                TextView open = ui.text(14, ui.accent, Ui.MEDIUM);
                open.setText(getString(R.string.open_lesson, lesson.title(Lang.EN)));
                open.setPadding(0, ui.dp(10), 0, 0);
                open.setOnClickListener(v -> GrammarViews.open(this, lesson));
                c.addView(open, Page.full());
            }
            results.addView(c, Page.full(ui.dp(4), ui.dp(8)));
        }
    }
}
