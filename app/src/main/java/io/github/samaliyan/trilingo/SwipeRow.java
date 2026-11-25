package io.github.samaliyan.trilingo;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A list row that can be swiped: to the right for one action (save), to the left for another (remove).
 * Behind the row a coloured band with the action's icon and name shows while it is dragged.
 * A short or slow swipe springs back. Taps and long presses reach the row as usual.
 */
final class SwipeRow extends FrameLayout {
    interface Action {
        /** Called after the swipe; return true when the row should slide away (removed), false to come back. */
        boolean run();
    }

    private final View row;
    private final LinearLayout under;
    private final ImageView icon;
    private final TextView label;
    private final Ui ui;
    private final int slop;
    private Action right, left;
    private String rightText, leftText;
    private Icons.Kind rightIcon, leftIcon;
    private int rightColor, leftColor;

    private float downX, downY;
    private boolean dragging;
    private VelocityTracker velocity;

    SwipeRow(Context c, Ui ui, View row) {
        super(c);
        this.ui = ui;
        this.row = row;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        under = new LinearLayout(c);
        under.setGravity(Gravity.CENTER_VERTICAL);
        under.setPadding(ui.dp(22), 0, ui.dp(22), 0);
        icon = new ImageView(c);
        label = ui.text(14, 0xFFFFFFFF, Ui.MEDIUM);
        under.addView(icon, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = ui.dp(10);
        lp.rightMargin = ui.dp(10);
        under.addView(label, lp);
        under.setVisibility(INVISIBLE);
        under.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(under, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Swipe to the right. */
    void onRight(Icons.Kind kind, String text, int color, Action a) {
        rightIcon = kind;
        rightText = text;
        rightColor = color;
        right = a;
    }

    /** Swipe to the left. */
    void onLeft(Icons.Kind kind, String text, int color, Action a) {
        leftIcon = kind;
        leftText = text;
        leftColor = color;
        left = a;
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(e);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (velocity != null) velocity.addMovement(e);
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (Math.abs(dx) > slop && Math.abs(dx) > 2 * Math.abs(dy) && (dx > 0 ? right != null : left != null)) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    row.setPressed(false);
                    row.cancelLongPress();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        if (velocity != null) velocity.addMovement(e);
        float dx = e.getX() - downX;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (dx > 0 && right == null) dx = 0;
                if (dx < 0 && left == null) dx = 0;
                row.setTranslationX(dx);
                showUnder(dx);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float v = 0;
                if (velocity != null) {
                    velocity.computeCurrentVelocity(1000);
                    v = velocity.getXVelocity();
                    velocity.recycle();
                    velocity = null;
                }
                dragging = false;
                boolean far = Math.abs(dx) > getWidth() * 0.35f;
                boolean fling = Math.abs(v) > ui.dp(900) && Math.signum(v) == Math.signum(dx) && Math.abs(dx) > ui.dp(40);
                if (e.getActionMasked() == MotionEvent.ACTION_UP && (far || fling)) finish(dx > 0);
                else settle();
                return true;
            default:
                return true;
        }
    }

    private void showUnder(float dx) {
        if (dx == 0) {
            under.setVisibility(INVISIBLE);
            return;
        }
        boolean r = dx > 0;
        under.setVisibility(VISIBLE);
        under.setBackground(ui.round(r ? rightColor : leftColor, 0));
        under.setGravity(Gravity.CENTER_VERTICAL | (r ? Gravity.LEFT : Gravity.RIGHT));
        icon.setImageDrawable(Icons.of(r ? rightIcon : leftIcon, 0xFFFFFFFF));
        label.setText(r ? rightText : leftText);
        float reach = Math.min(1f, Math.abs(dx) / (getWidth() * 0.35f));
        icon.setAlpha(0.4f + 0.6f * reach);
        label.setAlpha(0.4f + 0.6f * reach);
    }

    private void settle() {
        row.animate().translationX(0).setDuration(180).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                under.setVisibility(INVISIBLE);
                row.animate().setListener(null);
            }
        }).start();
    }

    private void finish(boolean toRight) {
        Feel.tick(this);
        float end = toRight ? getWidth() : -getWidth();
        row.animate().translationX(end).setDuration(160).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                row.animate().setListener(null);
                boolean gone = (toRight ? right : left).run();
                if (!gone) settle();
            }
        }).start();
    }
}
