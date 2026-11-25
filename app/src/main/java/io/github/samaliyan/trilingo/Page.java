package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The frame of a secondary screen: a top bar (back, title, optional button on the right) and scrolling content. */
final class Page {
    final LinearLayout root, bar, content;
    final ScrollView scroll;
    final TextView title;

    Page(Activity a, Ui ui, CharSequence titleText) {
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(4), ui.dp(8), ui.dp(8), ui.dp(4));
        ImageButton back = ui.iconButton(Icons.Kind.BACK, ui.text, a.getString(R.string.back));
        back.setOnClickListener(v -> a.finish());
        bar.addView(back, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        title = ui.line(21, ui.text, Ui.MEDIUM);
        title.setText(titleText);
        title.setPadding(ui.dp(4), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);
        scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);
        int side = ui.sidePadding(720, 16);                // tablets: a readable column in the middle
        content.setPadding(side, ui.dp(4), side, ui.dp(28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        a.setContentView(root);
    }

    /** Adds a view at the right end of the top bar. */
    void action(View v) {
        bar.addView(v);
    }

    static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams full(int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = topMargin;
        p.bottomMargin = bottomMargin;
        return p;
    }
}
