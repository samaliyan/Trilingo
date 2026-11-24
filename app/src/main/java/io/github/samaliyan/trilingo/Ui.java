package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.TextView;

/**
 * Colours, sizes and small view factories shared by all screens. The whole UI is built in code
 * from these helpers so every screen looks the same and follows the light/dark choice.
 */
final class Ui {
    final Context ctx;
    final boolean dark;
    final int bg, surface, text, sub, line, accent, accentSoft, onAccent, danger;

    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    private Ui(Context ctx, boolean dark) {
        this.ctx = ctx;
        this.dark = dark;
        if (dark) {
            bg = 0xFF0E1116; surface = 0xFF171B22; text = 0xFFECEFF4; sub = 0xFF9AA3AF; line = 0xFF262C36;
            accent = 0xFF7AB0FF; accentSoft = 0xFF1B2A42; onAccent = 0xFF0B1220;
            danger = 0xFFF0605A;
        } else {
            bg = 0xFFF4F6F9; surface = 0xFFFFFFFF; text = 0xFF12161C; sub = 0xFF66707D; line = 0xFFE3E7ED;
            accent = 0xFF2563EB; accentSoft = 0xFFE7EEFD; onAccent = 0xFFFFFFFF;
            danger = 0xFFDC3B33;
        }
    }

    static Ui of(Context c) {
        return new Ui(c, isDark(c, new Store(c).theme()));
    }

    static boolean isDark(Context c, int themeSetting) {
        if (themeSetting == Store.THEME_DARK) return true;
        if (themeSetting == Store.THEME_LIGHT) return false;
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Window, status bar and navigation bar colours matching the theme. */
    @SuppressWarnings("deprecation")
    void applyWindow(Activity a) {
        Window w = a.getWindow();
        w.getDecorView().setBackgroundColor(bg);
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        int flags = w.getDecorView().getSystemUiVisibility();
        if (dark) flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /** Theme for AlertDialogs. */
    int dialogTheme() {
        return dark ? R.style.Dialog_Dark : R.style.Dialog_Light;
    }

    int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    TextView text(float sp, int color, Typeface face) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Single-line text that ends with "…" instead of overflowing. */
    TextView line(float sp, int color, Typeface face) {
        TextView t = text(sp, color, face);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    GradientDrawable outline(int fillColor, int strokeColor, float radiusDp) {
        GradientDrawable g = round(fillColor, radiusDp);
        g.setStroke(dp(1.5f), strokeColor);
        return g;
    }

    /** Touch ripple over {@code content} (content may be null for a borderless ripple). */
    Drawable ripple(Drawable content, float radiusDp) {
        int rc = dark ? 0x33FFFFFF : 0x1F000000;
        Drawable mask = content != null ? round(0xFFFFFFFF, radiusDp) : null;
        return new RippleDrawable(ColorStateList.valueOf(rc), content, mask);
    }

    ImageButton iconButton(Icons.Kind kind, int color, CharSequence description) {
        ImageButton b = new ImageButton(ctx);
        b.setImageDrawable(Icons.of(kind, color));
        b.setBackground(ripple(null, 0));
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int p = dp(12);
        b.setPadding(p, p, p, p);
        b.setContentDescription(description);
        b.setTooltipText(description);
        return b;
    }

    /** Outlined button with an icon before its label, content centred, for secondary actions. */
    android.widget.LinearLayout actionPill(Icons.Kind kind, CharSequence label) {
        android.widget.LinearLayout b = new android.widget.LinearLayout(ctx);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(14), 0);
        b.setMinimumHeight(dp(44));
        android.widget.ImageView icon = new android.widget.ImageView(ctx);
        icon.setImageDrawable(Icons.of(kind, accent));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        b.addView(icon, new android.widget.LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = text(15, text, MEDIUM);
        t.setText(label);
        t.setMaxLines(1);
        android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(8);
        b.addView(t, tp);
        b.setBackground(ripple(outline(surface, line, 22), 22));
        b.setContentDescription(label);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** Square-ish button with the icon above its label, for a row of equal actions. */
    android.widget.LinearLayout actionTile(Icons.Kind kind, CharSequence label) {
        android.widget.LinearLayout b = new android.widget.LinearLayout(ctx);
        b.setOrientation(android.widget.LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(10), dp(6), dp(10));
        b.setMinimumHeight(dp(68));
        android.widget.ImageView icon = new android.widget.ImageView(ctx);
        icon.setImageDrawable(Icons.of(kind, accent));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        b.addView(icon, new android.widget.LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = text(13, text, MEDIUM);
        t.setText(label);
        t.setMaxLines(1);
        t.setGravity(Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(6);
        b.addView(t, tp);
        b.setBackground(ripple(outline(surface, line, 18), 18));
        b.setContentDescription(label);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** Filled or quiet pill button. */
    TextView pill(CharSequence label, boolean filled) {
        TextView b = text(14, filled ? onAccent : accent, MEDIUM);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(40));
        b.setMinWidth(dp(64));
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setBackground(ripple(round(filled ? accent : accentSoft, 20), 20));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /**
     * Text that may be Persian: its paragraph direction comes from its first strong letter, and it is
     * aligned to where that direction starts (right for Persian), although the app's layout stays left-to-right.
     */
    static void bidi(TextView t) {
        t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
    }
}
