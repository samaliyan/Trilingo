package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** One grammar lesson: explanation, table, examples (with translations and a speaker button) and related lessons. */
public final class LessonActivity extends Activity implements TextToSpeech.OnInitListener {
    static final String EXTRA_ID = "id";

    private Ui ui;
    private Store store;
    private LinearLayout content;
    private ScrollView scroll;
    private TextToSpeech tts;
    private boolean canSpeak;
    private Grammar.Lesson lesson;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(this);
        ui = Ui.of(this);
        ui.applyWindow(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(4), ui.dp(8), ui.dp(12), ui.dp(4));
        ImageButton back = ui.iconButton(Icons.Kind.BACK, ui.text, getString(R.string.back));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        TextView title = ui.line(18, ui.text, Ui.MEDIUM);
        title.setText(R.string.grammar);
        title.setPadding(ui.dp(4), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(GrammarViews.explainButton(this, ui, store, this::build));
        root.addView(bar);

        scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(32));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        try {
            tts = new TextToSpeech(getApplicationContext(), this);
        } catch (RuntimeException e) {
            tts = null;
        }
        Lexicon.load(this, () -> runOnUiThread(() -> {
            if (!isDestroyed()) build();
        }));
    }

    @Override protected void onDestroy() {
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
                // engine already gone
            }
        }
        super.onDestroy();
    }

    @Override public void onInit(int status) {
        runOnUiThread(() -> {
            if (isDestroyed() || tts == null || status != TextToSpeech.SUCCESS || lesson == null) return;
            try {
                canSpeak = tts.isLanguageAvailable(locale(lesson.lang)) >= TextToSpeech.LANG_AVAILABLE;
            } catch (RuntimeException e) {
                canSpeak = false;
            }
            if (canSpeak) build();
        });
    }

    private static Locale locale(Lang l) {
        return l == Lang.DE ? Locale.GERMANY : Locale.US;
    }

    private void speak(String text) {
        if (tts == null) return;
        try {
            tts.setLanguage(locale(lesson.lang));
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lesson");
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.speak_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void build() {
        Grammar g = Lexicon.grammar();
        if (g == null) return;
        lesson = g.get(getIntent().getStringExtra(EXTRA_ID));
        if (lesson == null) {
            finish();
            return;
        }
        int y = scroll.getScrollY();
        content.removeAllViews();
        Lang ex = store.explainLang();

        TextView meta = ui.line(13, ui.accent, Ui.MEDIUM);
        meta.setText(GrammarViews.meta(lesson, true));
        GrammarViews.direction(meta, ex);
        content.addView(meta, full());
        TextView title = ui.text(23, ui.text, Ui.MEDIUM);
        title.setText(lesson.title(ex));
        title.setLineSpacing(0, 1.2f);
        GrammarViews.direction(title, ex);
        LinearLayout.LayoutParams tp = full();
        tp.topMargin = ui.dp(6);
        tp.bottomMargin = ui.dp(14);
        content.addView(title, tp);

        // explanation
        LinearLayout card = card();
        boolean firstPara = true;
        for (String p : lesson.body(ex)) {
            boolean bullet = p.startsWith("•");
            TextView t = ui.text(16, ui.text, Ui.REGULAR);
            t.setText(p);
            t.setLineSpacing(0, ex == Lang.FA ? 1.45f : 1.3f);
            GrammarViews.direction(t, ex);
            if (bullet) {
                if (ex == Lang.FA) t.setPadding(0, 0, ui.dp(6), 0);
                else t.setPadding(ui.dp(6), 0, 0, 0);
            }
            LinearLayout.LayoutParams lp = full();
            if (!firstPara) lp.topMargin = ui.dp(bullet ? 6 : 12);
            card.addView(t, lp);
            firstPara = false;
        }
        content.addView(card);

        // table
        if (!lesson.table.isEmpty()) {
            section(getString(R.string.grammar_table));
            content.addView(table());
        }

        // examples
        section(getString(R.string.examples));
        for (String[] e : lesson.examples) content.addView(example(e, ex));

        // related lessons
        boolean any = false;
        for (String id : lesson.see) {
            Grammar.Lesson r = g.get(id);
            if (r == null) continue;
            if (!any) section(getString(R.string.grammar_related));
            any = true;
            content.addView(GrammarViews.row(this, ui, r, ex, r.lang != lesson.lang, v -> GrammarViews.open(this, r)));
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(ui.round(ui.surface, 18));
        c.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(18));
        c.setLayoutParams(full());
        return c;
    }

    private void section(String name) {
        content.addView(GrammarViews.header(this, ui, name));
    }

    /** The lesson's table: header row in bold, rows separated by thin lines; scrolls sideways when wide. */
    private View table() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackground(ui.round(ui.surface, 18));
        hs.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        int cols = lesson.table.get(0).length;
        // column widths: measured by the longest text, so the table lines up
        int[] width = new int[cols];
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTextSize(ui.dp(15) * getResources().getConfiguration().fontScale);
        for (String[] row : lesson.table)
            for (int c = 0; c < cols && c < row.length; c++)
                width[c] = Math.max(width[c], (int) paint.measureText(row[c]) + ui.dp(24));
        int screen = getResources().getDisplayMetrics().widthPixels - ui.dp(44);
        int total = 0;
        for (int w : width) total += w;
        if (total < screen) for (int c = 0; c < cols; c++) width[c] += (screen - total) / cols;   // fill the card
        for (int r = 0; r < lesson.table.size(); r++) {
            String[] row = lesson.table.get(r);
            LinearLayout line = new LinearLayout(this);
            if (r == 0) line.setBackground(ui.round(ui.accentSoft, 12));
            for (int c = 0; c < cols; c++) {
                TextView cell = ui.text(15, ui.text, r == 0 || c == 0 ? Ui.MEDIUM : Ui.REGULAR);
                cell.setText(c < row.length ? row[c] : "");
                cell.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
                cell.setTextIsSelectable(r > 0);
                Ui.bidi(cell);
                line.addView(cell, new LinearLayout.LayoutParams(width[c], ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            t.addView(line);
            if (r > 0 && r < lesson.table.size() - 1) {
                View sep = new View(this);
                sep.setBackgroundColor(ui.line);
                t.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1) / 2)));
            }
        }
        hs.addView(t);
        LinearLayout.LayoutParams lp = full();
        hs.setLayoutParams(lp);
        return hs;
    }

    /** Example sentence with a speaker button, its Persian translation and its English or German translation. */
    private View example(String[] e, Lang explain) {
        LinearLayout c = card();
        c.setPadding(ui.dp(18), ui.dp(12), ui.dp(8), ui.dp(14));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView s = ui.text(17, ui.text, Ui.MEDIUM);
        s.setText(e[0]);
        s.setLineSpacing(0, 1.2f);
        s.setTextIsSelectable(true);
        GrammarViews.direction(s, lesson.lang);
        top.addView(s, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (canSpeak) {
            ImageButton sp = ui.iconButton(Icons.Kind.SPEAK, ui.accent, getString(R.string.speak_lang, lesson.lang.label));
            sp.setOnClickListener(v -> speak(e[0]));
            top.addView(sp, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        }
        c.addView(top);
        // translations: the explanation language's first
        String fa = e.length > 1 ? e[1] : "", other = e.length > 2 ? e[2] : "";
        Lang otherLang = lesson.lang == Lang.DE ? Lang.EN : Lang.DE;
        boolean otherFirst = explain == otherLang;
        String[] order = otherFirst ? new String[]{other, fa} : new String[]{fa, other};
        Lang[] langs = otherFirst ? new Lang[]{otherLang, Lang.FA} : new Lang[]{Lang.FA, otherLang};
        for (int i = 0; i < 2; i++) {
            if (order[i].isEmpty()) continue;
            TextView t = ui.text(i == 0 ? 15 : 14, i == 0 ? ui.text : ui.sub, Ui.REGULAR);
            t.setText(order[i]);
            t.setLineSpacing(0, 1.2f);
            GrammarViews.direction(t, langs[i]);
            t.setPadding(0, 0, ui.dp(10), 0);
            LinearLayout.LayoutParams lp = full();
            lp.topMargin = ui.dp(i == 0 ? 6 : 4);
            c.addView(t, lp);
        }
        LinearLayout.LayoutParams lp = full();
        lp.bottomMargin = ui.dp(8);
        c.setLayoutParams(lp);
        return c;
    }
}
