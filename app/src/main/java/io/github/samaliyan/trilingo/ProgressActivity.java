package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Progress: the streak (days in a row with some learning) and the last seven days as dots, this week's
 * numbers, and two charts of the last eight weeks — words learned, and answers given.
 */
public final class ProgressActivity extends Activity {
    private Ui ui;
    private Page page;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        page = new Page(this, ui, getString(R.string.progress));
    }

    @Override protected void onResume() {
        super.onResume();
        fill();
    }

    private void fill() {
        Notebook nb = Store.notebook(this);
        Progress p = nb.progress;
        long now = System.currentTimeMillis();
        LinearLayout c = page.content;
        c.removeAllViews();

        // streak
        LinearLayout top = box();
        int streak = p.streak(now);
        TextView big = ui.text(40, ui.accent, Ui.MEDIUM);
        big.setText(String.valueOf(streak));
        top.addView(big);
        TextView label = ui.text(16, ui.text, Ui.MEDIUM);
        label.setText(getResources().getQuantityString(R.plurals.streak_label, streak));
        top.addView(label);
        TextView best = ui.text(13, ui.sub, Ui.REGULAR);
        best.setText(getString(R.string.progress_best, p.bestStreak()));
        top.addView(best, Page.full(ui.dp(2), ui.dp(12)));
        top.addView(weekDots(p, now));
        top.setContentDescription(getResources().getQuantityString(R.plurals.streak_days, streak, streak) + ". "
                + getString(R.string.progress_best, p.bestStreak()));
        c.addView(top, Page.full(ui.dp(4), ui.dp(12)));

        // this week
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int acc = p.accuracy(now);
        String[][] cells = {
                {String.valueOf(p.week(now, Progress.REVIEWS, 0) + p.week(now, Progress.PRACTICE, 0)), getString(R.string.progress_answers)},
                {acc < 0 ? "–" : acc + "%", getString(R.string.progress_right)},
                {String.valueOf(p.week(now, Progress.SAVED, 0)), getString(R.string.progress_saved)},
                {String.valueOf(p.week(now, Progress.LEARNED, 0)), getString(R.string.progress_learned_week)},
        };
        TextView wh = ui.line(13, ui.accent, Ui.MEDIUM);
        wh.setText(R.string.progress_this_week);
        c.addView(wh, Page.full(ui.dp(8), ui.dp(8)));
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int k = 0; k < 2; k++) {
                String[] cell = cells[r * 2 + k];
                LinearLayout b = box();
                TextView n = ui.text(24, ui.text, Ui.MEDIUM);
                n.setText(cell[0]);
                b.addView(n);
                TextView t = ui.text(13, ui.sub, Ui.REGULAR);
                t.setText(cell[1]);
                b.addView(t);
                b.setContentDescription(cell[0] + " " + cell[1]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (k > 0) lp.leftMargin = ui.dp(10);
                row.addView(b, lp);
            }
            grid.addView(row, Page.full(0, ui.dp(10)));
        }
        c.addView(grid, Page.full());

        // all time
        TextView total = ui.text(14, ui.sub, Ui.REGULAR);
        total.setText(getString(R.string.progress_total, nb.learned(), nb.saved(null).size()));
        total.setLineSpacing(0, 1.25f);
        c.addView(total, Page.full(ui.dp(2), ui.dp(16)));

        // charts
        c.addView(chart(getString(R.string.progress_chart_learned), p.weeks(now, Progress.LEARNED, 8), now), Page.full(0, ui.dp(12)));
        List<Integer> answers = p.weeks(now, Progress.REVIEWS, 8);
        List<Integer> practice = p.weeks(now, Progress.PRACTICE, 8);
        for (int i = 0; i < answers.size(); i++) answers.set(i, answers.get(i) + practice.get(i));
        c.addView(chart(getString(R.string.progress_chart_answers), answers, now), Page.full(0, ui.dp(12)));

        if (p.isEmpty()) {
            TextView none = ui.text(14, ui.sub, Ui.REGULAR);
            none.setText(R.string.progress_empty);
            none.setLineSpacing(0, 1.25f);
            c.addView(none, Page.full(ui.dp(4), ui.dp(12)));
        }
        TextView practise = ui.pill(getString(R.string.learn), true);
        practise.setOnClickListener(v -> startActivity(new Intent(this, LearnActivity.class)));
        c.addView(practise, Page.full(ui.dp(4), ui.dp(20)));
        practise.getLayoutParams().height = ui.dp(48);
    }

    private LinearLayout box() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(ui.round(ui.surface, 18));
        b.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        return b;
    }

    /** The last seven days: a filled dot for a day with learning, the day's letter under it. */
    private View weekDots(Progress p, long now) {
        LinearLayout row = new LinearLayout(this);
        long today = Progress.day(now);
        SimpleDateFormat f = new SimpleDateFormat("EEEEE", Locale.ENGLISH);
        for (int i = 6; i >= 0; i--) {
            long d = today - i;
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            View dot = new View(this);
            boolean on = p.active(d);
            dot.setBackground(on ? ui.round(ui.accent, 12) : ui.outline(ui.bg, ui.line, 12));
            col.addView(dot, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
            TextView t = ui.line(11, i == 0 ? ui.text : ui.sub, i == 0 ? Ui.MEDIUM : Ui.REGULAR);
            // the day's letter, from noon of that day in the phone's time zone
            long noon = d * Progress.DAY + Progress.DAY / 2 - java.util.TimeZone.getDefault().getOffset(now);
            t.setText(f.format(new Date(noon)));
            t.setGravity(Gravity.CENTER);
            col.addView(t, new LinearLayout.LayoutParams(ui.dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return row;
    }

    private View chart(String title, List<Integer> values, long now) {
        LinearLayout b = box();
        TextView t = ui.line(14, ui.text, Ui.MEDIUM);
        t.setText(title);
        b.addView(t);
        Bars bars = new Bars(this, values);
        b.addView(bars, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(150)));
        StringBuilder desc = new StringBuilder(title);
        for (int i = 0; i < values.size(); i++)
            desc.append(i == 0 ? ": " : ", ").append(values.get(i));
        b.setContentDescription(desc.append(" (").append(getString(R.string.progress_weeks_oldest_first)).append(')'));
        LinearLayout labels = new LinearLayout(this);
        TextView a = ui.line(11, ui.sub, Ui.REGULAR);
        a.setText(R.string.progress_8_weeks_ago);
        labels.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView z = ui.line(11, ui.sub, Ui.REGULAR);
        z.setText(R.string.progress_this_week);
        labels.addView(z);
        b.addView(labels, Page.full(ui.dp(4), 0));
        return b;
    }

    /** Bars with their numbers above; the last one (this week) in the accent colour. */
    private final class Bars extends View {
        private final List<Integer> values;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), num = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Bars(Context c, List<Integer> values) {
            super(c);
            this.values = values;
            num.setTextAlign(Paint.Align.CENTER);
            num.setTextSize(ui.dp(11));
            num.setColor(ui.sub);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            int n = values.size();
            if (n == 0) return;
            int max = 1;
            for (int v : values) max = Math.max(max, v);
            float w = getWidth(), h = getHeight(), top = ui.dp(18), bottom = h - ui.dp(2);
            float slot = w / n, bw = Math.min(slot * 0.6f, ui.dp(28));
            for (int i = 0; i < n; i++) {
                int v = values.get(i);
                float cx = slot * i + slot / 2;
                float bh = v == 0 ? ui.dp(2) : Math.max(ui.dp(4), (bottom - top) * v / max);
                bar.setColor(i == n - 1 ? ui.accent : ui.accentSoft);
                r.set(cx - bw / 2, bottom - bh, cx + bw / 2, bottom);
                c.drawRoundRect(r, ui.dp(6), ui.dp(6), bar);
                if (v > 0) c.drawText(String.valueOf(v), cx, bottom - bh - ui.dp(4), num);
            }
        }
    }
}
