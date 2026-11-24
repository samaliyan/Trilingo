package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Pieces shared by the Grammar screen, the lesson screen and the "Grammar" panel of a looked-up word. */
final class GrammarViews {
    private GrammarViews() { }

    static final String[] EXPLAIN_NAMES = {"Persian", "English", "German"};

    static String explainName(Lang l) {
        return EXPLAIN_NAMES[l == Lang.FA ? 0 : l == Lang.EN ? 1 : 2];
    }

    /** "German · A1 · Verbs" */
    static String meta(Grammar.Lesson l, boolean withLanguage) {
        String s = l.level + " · " + Grammar.categoryName(l.cat, Lang.EN);
        return withLanguage ? (l.lang == Lang.DE ? "German" : "English") + " · " + s : s;
    }

    /** Persian text is set right-to-left (and right-aligned) even when it starts with a German or English word. */
    static void direction(TextView t, Lang lang) {
        if (lang == Lang.FA) {
            t.setTextDirection(View.TEXT_DIRECTION_RTL);
            t.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
            t.setGravity(Gravity.RIGHT);
        } else {
            t.setTextDirection(View.TEXT_DIRECTION_LTR);
            t.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
            t.setGravity(Gravity.LEFT);
        }
    }

    /** A lesson in a list: its title in the explanation language, level and topic below, an arrow on the right. */
    static View row(Activity a, Ui ui, Grammar.Lesson l, Lang explain, boolean withLanguage, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(16), ui.dp(12), ui.dp(8), ui.dp(12));
        row.setMinimumHeight(ui.dp(64));
        row.setBackground(ui.ripple(ui.round(ui.surface, 16), 16));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = ui.text(16, ui.text, Ui.MEDIUM);
        title.setText(l.title(explain));
        title.setLineSpacing(0, 1.15f);
        direction(title, explain);
        texts.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView sub = ui.line(13, ui.sub, Ui.REGULAR);
        sub.setText(meta(l, withLanguage));
        direction(sub, explain);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = ui.dp(4);
        texts.addView(sub, sp);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView arrow = new ImageView(a);
        arrow.setImageDrawable(Icons.of(Icons.Kind.CHEVRON, ui.sub));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20));
        ap.leftMargin = ui.dp(8);
        row.addView(arrow, ap);
        row.setContentDescription(l.title(explain) + ", " + meta(l, true));
        row.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    /** Small label above a group of lessons ("A1 · Beginner"). */
    static TextView header(Activity a, Ui ui, String text) {
        TextView h = ui.line(13, ui.accent, Ui.MEDIUM);
        h.setText(text);
        h.setAllCaps(true);
        h.setLetterSpacing(0.06f);
        h.setPadding(ui.dp(6), ui.dp(16), ui.dp(6), ui.dp(8));
        return h;
    }

    /** Pill in the top bar that shows and changes the explanation language. */
    static TextView explainButton(Activity a, Ui ui, Store store, Runnable changed) {
        TextView b = ui.pill(explainName(store.explainLang()), false);
        b.setMinHeight(ui.dp(36));
        b.setContentDescription(a.getString(R.string.explain_in_desc, explainName(store.explainLang())));
        b.setOnClickListener(v -> {
            int cur = store.explainLang() == Lang.FA ? 0 : store.explainLang() == Lang.EN ? 1 : 2;
            new AlertDialog.Builder(a, ui.dialogTheme())
                    .setTitle(R.string.explain_in)
                    .setSingleChoiceItems(EXPLAIN_NAMES, cur, (d, i) -> {
                        d.dismiss();
                        store.setExplainLang(Grammar.EXPLAIN[i]);
                        b.setText(EXPLAIN_NAMES[i]);
                        b.setContentDescription(a.getString(R.string.explain_in_desc, EXPLAIN_NAMES[i]));
                        changed.run();
                    })
                    .show();
        });
        return b;
    }

    static void open(Activity a, Grammar.Lesson l) {
        a.startActivity(new Intent(a, LessonActivity.class).putExtra(LessonActivity.EXTRA_ID, l.id));
    }
}
