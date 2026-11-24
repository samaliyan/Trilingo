package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Grammar: every English and German lesson by level, and a search that finds lessons by topic
 * (in Persian, English or German) or by a word ("fernsehen" → separable verbs).
 */
public final class GrammarActivity extends Activity {
    /** Optional text to search for when the screen opens. */
    static final String EXTRA_QUERY = "query";

    private Ui ui;
    private Store store;
    private EditText search;
    private ImageButton clear;
    private LinearLayout list, tabs;
    private ScrollView scroll;
    private final TextView[] tab = new TextView[2];

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
        TextView title = ui.line(22, ui.text, Ui.MEDIUM);
        title.setText(R.string.grammar);
        title.setPadding(ui.dp(4), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(GrammarViews.explainButton(this, ui, store, this::render));
        root.addView(bar);

        // search field
        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(ui.outline(ui.surface, ui.line, 24));
        field.setPadding(ui.dp(14), 0, ui.dp(2), 0);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(Icons.of(Icons.Kind.SEARCH, ui.sub));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        field.addView(icon, new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        search = new EditText(this);
        search.setBackground(null);
        search.setHint(R.string.grammar_search_hint);
        search.setHintTextColor(ui.sub);
        search.setTextColor(ui.text);
        search.setTextSize(16);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(ui.dp(10), ui.dp(12), ui.dp(4), ui.dp(12));
        search.setContentDescription(getString(R.string.grammar_search_hint));
        Ui.bidi(search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                render();
            }
        });
        search.setOnEditorActionListener((v, id, ev) -> {
            hideKeyboard();
            return true;
        });
        field.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        clear = ui.iconButton(Icons.Kind.CLOSE, ui.sub, getString(R.string.clear));
        clear.setOnClickListener(v -> search.setText(""));
        field.addView(clear, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.leftMargin = ui.dp(16);
        fp.rightMargin = ui.dp(16);
        fp.topMargin = ui.dp(4);
        root.addView(field, fp);

        // German | English
        tabs = new LinearLayout(this);
        tabs.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(4));
        String[] names = {getString(R.string.grammar_german), getString(R.string.grammar_english)};
        for (int i = 0; i < 2; i++) {
            final Lang l = i == 0 ? Lang.DE : Lang.EN;
            TextView t = ui.text(14, ui.text, Ui.MEDIUM);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(ui.dp(40));
            t.setClickable(true);
            t.setFocusable(true);
            t.setOnClickListener(v -> {
                if (store.grammarLang() == l) return;
                store.setGrammarLang(l);
                render();
                scroll.scrollTo(0, 0);
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i == 1) tp.leftMargin = ui.dp(8);
            tabs.addView(t, tp);
            tab[i] = t;
        }
        root.addView(tabs);

        scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        String q = saved == null ? getIntent().getStringExtra(EXTRA_QUERY) : null;
        if (q != null) search.setText(q);
        render();
        Lexicon.load(this, () -> runOnUiThread(() -> {
            if (!isDestroyed()) render();
        }));
    }

    @Override protected void onRestart() {
        super.onRestart();
        render();                                      // the explanation language may have changed in a lesson
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        String q = Text.clean(search.getText().toString());
        clear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        Lang explain = store.explainLang();
        Grammar g = Lexicon.grammar();
        tabs.setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < 2; i++) {
            boolean on = (i == 0) == (store.grammarLang() == Lang.DE);
            tab[i].setTextColor(on ? ui.onAccent : ui.text);
            tab[i].setBackground(ui.ripple(on ? ui.round(ui.accent, 20) : ui.outline(ui.surface, ui.line, 20), 20));
            tab[i].setSelected(on);
        }
        if (g == null) {
            note(getString(R.string.loading), null);
            return;
        }
        if (q.isEmpty()) {
            String level = null;
            for (Grammar.Lesson l : g.of(store.grammarLang())) {
                if (!l.level.equals(level)) {
                    level = l.level;
                    list.addView(GrammarViews.header(this, ui, level + " · " + Grammar.levelName(level, Lang.EN)));
                }
                list.addView(GrammarViews.row(this, ui, l, explain, false, v -> GrammarViews.open(this, l)));
            }
            return;
        }
        List<Grammar.Lesson> found = Lexicon.searchGrammar(q);
        if (found.isEmpty()) {
            note(getString(R.string.grammar_nothing), getString(R.string.grammar_nothing_hint));
            return;
        }
        list.addView(GrammarViews.header(this, ui, getResources().getQuantityString(R.plurals.lessons_found, found.size(), found.size())));
        for (Grammar.Lesson l : found)
            list.addView(GrammarViews.row(this, ui, l, explain, true, v -> {
                hideKeyboard();
                GrammarViews.open(this, l);
            }));
    }

    private void note(String title, String hint) {
        TextView t = ui.text(16, ui.text, Ui.MEDIUM);
        t.setText(title);
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(16), ui.dp(40), ui.dp(16), 0);
        list.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (hint != null) {
            TextView h = ui.text(14, ui.sub, Ui.REGULAR);
            h.setText(hint);
            h.setGravity(Gravity.CENTER);
            h.setLineSpacing(0, 1.25f);
            h.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0);
            list.addView(h, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }
}
