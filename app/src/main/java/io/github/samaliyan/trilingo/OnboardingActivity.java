package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A short introduction on the first start: four pages (swipe or Next), each with one picture and two
 * lines. Skip leaves at once. Can be opened again from Settings.
 */
public final class OnboardingActivity extends Activity {
    private static final Icons.Kind[] ICONS = {Icons.Kind.SEARCH, Icons.Kind.BOOK, Icons.Kind.CARDS, Icons.Kind.CAMERA};
    private static final int[] TITLES = {R.string.intro1_title, R.string.intro2_title, R.string.intro3_title, R.string.intro4_title};
    private static final int[] BODIES = {R.string.intro1_body, R.string.intro2_body, R.string.intro3_body, R.string.intro4_body};

    private Ui ui;
    private int index;
    private LinearLayout pageBox, dots;
    private TextView next, skip;
    private View widget;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        if (saved != null) index = saved.getInt("page");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        int side = ui.sidePadding(560, 24);
        root.setPadding(side, ui.dp(12), side, ui.dp(20));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.END);
        skip = ui.text(15, ui.sub, Ui.MEDIUM);
        skip.setText(R.string.skip);
        skip.setGravity(Gravity.CENTER);
        skip.setMinHeight(ui.dp(44));
        skip.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        skip.setBackground(ui.ripple(null, 0));
        skip.setOnClickListener(v -> finish());
        top.addView(skip);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout middle = new FrameLayout(this);
        pageBox = new LinearLayout(this);
        pageBox.setOrientation(LinearLayout.VERTICAL);
        pageBox.setGravity(Gravity.CENTER_HORIZONTAL);
        middle.addView(pageBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(middle);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        dots.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(28)));

        if (MainActivity.canPinWidget(this)) {
            TextView w = ui.pill(getString(R.string.widget_add_home), false);
            w.setOnClickListener(v -> MainActivity.pinWidget(this));
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48));
            wp.topMargin = ui.dp(10);
            root.addView(w, wp);
            widget = w;
        }
        next = ui.pill("", true);
        next.setOnClickListener(v -> {
            if (index < ICONS.length - 1) show(index + 1, true);
            else finish();
        });
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52));
        np.topMargin = ui.dp(10);
        root.addView(next, np);
        setContentView(root);

        GestureDetector g = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || Math.abs(vx) < Math.abs(vy) * 1.5f || Math.abs(b.getX() - a.getX()) < ui.dp(40)) return false;
                if (vx < 0 && index < ICONS.length - 1) show(index + 1, true);
                else if (vx > 0 && index > 0) show(index - 1, false);
                return true;
            }
        });
        middle.setOnTouchListener((v, e) -> g.onTouchEvent(e));
        show(index, true);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", index);
    }

    private void show(int i, boolean forward) {
        index = i;
        pageBox.removeAllViews();
        FrameLayout art = new FrameLayout(this);
        art.setBackground(ui.round(ui.accentSoft, 80));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(Icons.of(ICONS[i], ui.accent));
        art.addView(icon, new FrameLayout.LayoutParams(ui.dp(68), ui.dp(68), Gravity.CENTER));
        art.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ui.dp(160), ui.dp(160));
        ap.bottomMargin = ui.dp(28);
        pageBox.addView(art, ap);
        TextView t = ui.text(25, ui.text, Ui.MEDIUM);
        t.setText(TITLES[i]);
        t.setGravity(Gravity.CENTER);
        pageBox.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView b = ui.text(16, ui.sub, Ui.REGULAR);
        b.setText(BODIES[i]);
        b.setGravity(Gravity.CENTER);
        b.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = ui.dp(12);
        pageBox.addView(b, bp);
        pageBox.setContentDescription(getString(R.string.intro_page, i + 1, ICONS.length) + ". " + getString(TITLES[i]) + ". " + getString(BODIES[i]));

        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            pageBox.setAlpha(0f);
            pageBox.setTranslationX(ui.dp(forward ? 24 : -24));
            pageBox.animate().alpha(1f).translationX(0).setDuration(220).start();
        }

        dots.removeAllViews();
        for (int k = 0; k < ICONS.length; k++) {
            View d = new View(this);
            boolean on = k == i;
            d.setBackground(ui.round(on ? ui.accent : ui.line, 4));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ui.dp(on ? 22 : 8), ui.dp(8));
            dp.leftMargin = ui.dp(4);
            dp.rightMargin = ui.dp(4);
            dots.addView(d, dp);
        }
        boolean last = i == ICONS.length - 1;
        next.setText(last ? R.string.get_started : R.string.next);
        skip.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
        if (widget != null) widget.setVisibility(last ? View.VISIBLE : View.GONE);
    }
}
