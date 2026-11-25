package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Words from a text": paste an English or German text (an article, an email, a class handout); the words
 * that are probably new at your level are listed, harder ones first, each with its meaning. Tick the ones to
 * learn and save them to a folder in one step.
 */
public final class TextWordsActivity extends Activity {
    static final String EXTRA_TEXT = "text";
    private static final String[] LEVELS = {"A1", "A2", "B1", "B2", "C1"};

    private Ui ui;
    private Store store;
    private Page page;
    private EditText input;
    private LinearLayout results;
    private Lang lang;
    private final Set<String> chosen = new LinkedHashSet<>();
    private List<TextWords.Item> found = new ArrayList<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        store = new Store(this);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        page = new Page(this, ui, getString(R.string.text_words));
        LinearLayout c = page.content;

        TextView intro = ui.text(14, ui.sub, Ui.REGULAR);
        intro.setText(R.string.text_words_intro);
        intro.setLineSpacing(0, 1.25f);
        c.addView(intro, Page.full(0, ui.dp(10)));

        input = new EditText(this);
        input.setId(R.id.text_words_input);
        input.setHint(R.string.text_words_hint);
        input.setHintTextColor(ui.sub);
        input.setTextColor(ui.text);
        input.setTextSize(16);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(5);
        input.setMaxLines(12);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackground(ui.outline(ui.surface, ui.line, 16));
        input.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        Ui.bidi(input);
        c.addView(input, Page.full());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout paste = ui.actionPill(Icons.Kind.PASTE, getString(R.string.paste));
        paste.setOnClickListener(v -> paste());
        buttons.addView(paste);
        LinearLayout clear = ui.actionPill(Icons.Kind.CLOSE, getString(R.string.clear));
        clear.setOnClickListener(v -> {
            input.setText("");
            found.clear();
            chosen.clear();
            results.removeAllViews();
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = ui.dp(10);
        buttons.addView(clear, cp);
        buttons.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView find = ui.pill(getString(R.string.find_words), true);
        find.setOnClickListener(v -> analyse(true));
        buttons.addView(find, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(44)));
        c.addView(buttons, Page.full(ui.dp(10), ui.dp(6)));

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        c.addView(results, Page.full());

        String given = getIntent().getStringExtra(EXTRA_TEXT);
        if (given == null && Intent.ACTION_SEND.equals(getIntent().getAction())) given = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (given != null && saved == null) input.setText(given);
        Lexicon.load(this, () -> runOnUiThread(() -> {
            if (!isDestroyed() && !Text.clean(input.getText().toString()).isEmpty()) analyse(false);
        }));
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = cm != null ? cm.getPrimaryClip() : null;
        CharSequence cs = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
        if (cs == null || cs.toString().trim().isEmpty()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        input.setText(cs);
        analyse(true);
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private void analyse(boolean user) {
        String text = input.getText().toString();
        results.removeAllViews();
        chosen.clear();
        found = new ArrayList<>();
        if (user) hideKeyboard();
        if (Text.clean(text).isEmpty()) {
            if (user) Toast.makeText(this, R.string.text_words_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Lexicon.grammar() == null) {
            message(getString(R.string.loading), null);
            return;
        }
        lang = TextWords.detect(text, Lexicon.words(Lang.EN), Lexicon.words(Lang.DE));
        if (lang == null) {
            message(getString(R.string.text_words_language), getString(R.string.text_words_language_hint));
            return;
        }
        String my = store.myLevel(lang);
        Notebook nb = Store.notebook(this);
        TextWords tw = new TextWords(lang, Lexicon.levels(lang), Lexicon.words(lang), Lexicon.nouns(), Lexicon.dict(lang));
        found = tw.find(text, my, w -> nb.isSaved(lang, w));
        for (TextWords.Item i : found) chosen.add(i.word);
        render(my);
    }

    private void message(String title, String hint) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(ui.round(ui.surface, 18));
        b.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16));
        TextView t = ui.text(16, ui.text, Ui.MEDIUM);
        t.setText(title);
        b.addView(t);
        if (hint != null) {
            TextView h = ui.text(14, ui.sub, Ui.REGULAR);
            h.setText(hint);
            h.setLineSpacing(0, 1.2f);
            b.addView(h, Page.full(ui.dp(6), 0));
        }
        results.addView(b, Page.full(ui.dp(10), 0));
    }

    private void render(String my) {
        results.removeAllViews();
        // "German text · My level:" A1 A2 B1 B2 C1
        TextView head = ui.line(13, ui.accent, Ui.MEDIUM);
        head.setText(getString(R.string.text_lang_level, lang.label));
        results.addView(head, Page.full(ui.dp(10), ui.dp(4)));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (String lv : LEVELS) {
            boolean on = lv.equals(my);
            TextView t = ui.text(14, on ? ui.onAccent : ui.text, Ui.MEDIUM);
            t.setText(lv);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(ui.dp(38));
            t.setMinWidth(ui.dp(52));
            t.setBackground(ui.ripple(on ? ui.round(ui.accent, 19) : ui.outline(ui.surface, ui.line, 19), 19));
            t.setSelected(on);
            t.setContentDescription(getString(R.string.my_level_desc, lv, Levels.name(lv)));
            t.setOnClickListener(v -> {
                store.setMyLevel(lang, lv);
                analyse(false);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = ui.dp(8);
            row.addView(t, lp);
        }
        hs.addView(row);
        results.addView(hs, Page.full(0, ui.dp(10)));

        if (found.isEmpty()) {
            message(getString(R.string.text_words_none), getString(R.string.text_words_none_hint));
            return;
        }
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = ui.line(15, ui.text, Ui.MEDIUM);
        count.setText(getResources().getQuantityString(R.plurals.new_words_found, found.size(), found.size()));
        bar.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView all = ui.text(14, ui.accent, Ui.MEDIUM);
        all.setGravity(Gravity.CENTER);
        all.setMinHeight(ui.dp(40));
        all.setPadding(ui.dp(12), 0, ui.dp(4), 0);
        all.setBackground(ui.ripple(null, 0));
        bar.addView(all);
        results.addView(bar, Page.full(0, ui.dp(4)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(ui.round(ui.surface, 18));
        List<CheckBox> boxes = new ArrayList<>();
        TextView save = ui.pill("", true);
        Runnable update = () -> {
            save.setText(chosen.isEmpty() ? getString(R.string.choose_words) : getResources().getQuantityString(R.plurals.save_n_words, chosen.size(), chosen.size()));
            save.setEnabled(!chosen.isEmpty());
            save.setAlpha(chosen.isEmpty() ? 0.5f : 1f);
            all.setText(chosen.size() == found.size() ? R.string.select_none : R.string.select_all);
        };
        Dict dict = Lexicon.dict(lang);
        for (TextWords.Item item : found) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(ui.dp(8), ui.dp(8), ui.dp(16), ui.dp(8));
            r.setMinimumHeight(ui.dp(56));
            r.setBackground(ui.ripple(null, 0));
            CheckBox cb = new CheckBox(this);
            cb.setChecked(chosen.contains(item.word));
            cb.setButtonTintList(ColorStateList.valueOf(ui.accent));
            cb.setClickable(false);
            cb.setFocusable(false);
            cb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            boxes.add(cb);
            r.addView(cb);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView w = ui.line(16, ui.text, Ui.MEDIUM);
            w.setText(item.word);
            top.addView(w);
            if (item.level != null) {
                TextView lv = ui.text(11, ui.accent, Ui.MEDIUM);
                lv.setText(item.level);
                lv.setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1));
                lv.setBackground(ui.round(ui.accentSoft, 8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = ui.dp(8);
                top.addView(lv, lp);
            }
            if (item.count > 1) {
                TextView n = ui.text(12, ui.sub, Ui.REGULAR);
                n.setText(getString(R.string.times_n, item.count));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = ui.dp(8);
                top.addView(n, lp);
            }
            texts.addView(top);
            String meaning = dict == null ? "" : dict.summary(item.word).replace('\n', '·');
            if (!meaning.isEmpty()) {
                TextView m = ui.line(14, ui.sub, Ui.REGULAR);
                m.setText(meaning);
                Ui.bidi(m);
                texts.addView(m, Page.full(ui.dp(2), 0));
            }
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tp.leftMargin = ui.dp(4);
            r.addView(texts, tp);
            r.setContentDescription(item.word + (item.level == null ? "" : ", " + item.level) + (meaning.isEmpty() ? "" : ". " + meaning));
            r.setOnClickListener(v -> {
                if (!chosen.remove(item.word)) chosen.add(item.word);
                cb.setChecked(chosen.contains(item.word));
                Feel.tick(v);
                update.run();
            });
            r.setOnLongClickListener(v -> {
                startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_TEXT, item.word)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                return true;
            });
            list.addView(r);
        }
        all.setOnClickListener(v -> {
            boolean selectAll = chosen.size() != found.size();
            chosen.clear();
            if (selectAll) for (TextWords.Item i : found) chosen.add(i.word);
            for (int i = 0; i < boxes.size(); i++) boxes.get(i).setChecked(selectAll);
            update.run();
        });
        results.addView(list, Page.full());
        TextView tip = ui.text(12, ui.sub, Ui.REGULAR);
        tip.setText(R.string.text_words_tip);
        tip.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), 0);
        results.addView(tip);
        save.setOnClickListener(v -> chooseFolder());
        results.addView(save, Page.full(ui.dp(14), 0));
        save.getLayoutParams().height = ui.dp(52);
        update.run();
    }

    private void chooseFolder() {
        if (chosen.isEmpty()) return;
        Notebook nb = Store.notebook(this);
        List<String> folders = nb.folders();
        String suggested = getString(R.string.text_folder_name, DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date()));
        String[] items = new String[folders.size() + 2];
        items[0] = getString(R.string.new_folder_named, suggested);
        items[1] = getString(R.string.no_folder);
        for (int i = 0; i < folders.size(); i++) items[i + 2] = folders.get(i);
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle(R.string.save_to)
                .setItems(items, (d, i) -> save(i == 0 ? suggested : i == 1 ? "" : folders.get(i - 2)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void save(String folder) {
        Notebook nb = Store.notebook(this);
        Dict dict = Lexicon.dict(lang);
        List<String> words = new ArrayList<>(chosen);
        int added = nb.saveAll(lang, words, w -> dict == null ? "" : dict.summary(w), folder, System.currentTimeMillis());
        Backfill.start(this);                         // words the built-in dictionary does not have
        Toast.makeText(this, getResources().getQuantityString(R.plurals.saved_n_words, added, added), Toast.LENGTH_LONG).show();
        analyse(false);
    }
}
