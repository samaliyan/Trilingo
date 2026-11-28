package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Panel that slides up from the bottom of the screen (like the system share sheet): a handle, a title row
 * with a close button, and scrolling content. Closes with the close button, Back, a tap outside it,
 * or by dragging the handle down.
 */
final class Sheet {
    final Dialog dialog;
    /** Fixed area under the title (e.g. tabs); empty by default. */
    final LinearLayout top;
    /** Scrolling content. */
    final LinearLayout body;
    final ScrollView scroll;
    /** Identifies the content being loaded, so a late answer for an older tab is not shown. */
    Object token;
    private final Ui ui;

    Sheet(Activity a, Ui ui, String title, String subtitle) {
        this.ui = ui;
        dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        MaxHeight root = new MaxHeight(a);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ui.bg);
        float r = ui.dp(24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.maxHeight = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.86f);

        // drag handle
        FrameLayout handleBox = new FrameLayout(a);
        View handle = new View(a);
        handle.setBackground(ui.round(ui.line, 3));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ui.dp(40), ui.dp(5), Gravity.CENTER);
        handleBox.addView(handle, hp);
        root.addView(handleBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(22)));

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(22), 0, ui.dp(8), ui.dp(4));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = ui.line(20, ui.text, Ui.MEDIUM);
        t.setText(title);
        texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = ui.line(15, ui.sub, Ui.REGULAR);
            s.setText(subtitle);
            Ui.bidi(s);
            texts.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton close = ui.iconButton(Icons.Kind.CLOSE, ui.sub, a.getString(R.string.close));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        root.addView(head);

        top = new LinearLayout(a);
        top.setOrientation(LinearLayout.VERTICAL);
        root.addView(top);

        scroll = new ScrollView(a);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        dragToClose(handleBox, root);
        dragToClose(head, root);

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.4f);
        }
    }

    void show() {
        dialog.show();
    }

    void dismiss() {
        dialog.dismiss();
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    /** Replaces the content with a centred message (and an optional button). */
    void message(String title, String hint, String button, View.OnClickListener click) {
        body.removeAllViews();
        LinearLayout box = new LinearLayout(dialog.getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(ui.dp(16), ui.dp(28), ui.dp(16), ui.dp(28));
        TextView t = ui.text(16, ui.text, Ui.MEDIUM);
        t.setText(title);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        if (hint != null) {
            TextView h = ui.text(14, ui.sub, Ui.REGULAR);
            h.setText(hint);
            h.setGravity(Gravity.CENTER);
            h.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(6);
            box.addView(h, lp);
        }
        if (button != null) {
            TextView b = ui.pill(button, true);
            b.setOnClickListener(click);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(16);
            box.addView(b, lp);
        }
        body.addView(box);
    }

    /** Spinner with a line of text while content loads. */
    void loading(String text) {
        body.removeAllViews();
        LinearLayout box = new LinearLayout(dialog.getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, ui.dp(32), 0, ui.dp(32));
        android.widget.ProgressBar p = new android.widget.ProgressBar(dialog.getContext());
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        box.addView(p, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        TextView t = ui.text(14, ui.sub, Ui.REGULAR);
        t.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(12);
        box.addView(t, lp);
        body.addView(box);
    }

    /** Dragging the top of the sheet down moves it with the finger; far enough (or fast) closes it. */
    private void dragToClose(View grip, View sheet) {
        final float[] start = new float[1];
        grip.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = e.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    sheet.setTranslationY(Math.max(0, e.getRawY() - start[0]));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float dy = e.getRawY() - start[0];
                    if (dy > ui.dp(90)) dialog.dismiss();
                    else sheet.animate().translationY(0).setDuration(150).start();
                    if (Math.abs(dy) < ui.dp(4)) v.performClick();
                    return true;
                default:
                    return false;
            }
        });
    }

    /** LinearLayout that never grows taller than {@link #maxHeight}. */
    private static final class MaxHeight extends LinearLayout {
        int maxHeight = Integer.MAX_VALUE;

        MaxHeight(Context c) {
            super(c);
        }

        @Override protected void onMeasure(int w, int h) {
            int mode = MeasureSpec.getMode(h), size = MeasureSpec.getSize(h);
            int cap = mode == MeasureSpec.UNSPECIFIED ? maxHeight : Math.min(size, maxHeight);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }
}
