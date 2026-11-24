package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Saved words: folders, the words in them, review a folder, export for Anki. */
public final class NotebookActivity extends Activity {
    private static final int EXPORT = 61;

    private Ui ui;
    private Notebook nb;
    private Page page;
    /** null = all words, "" = words without a folder, else a folder name. */
    private String folder;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        nb = Store.notebook(this);
        if (saved != null && saved.containsKey("folder")) folder = saved.getString("folder");
        page = new Page(this, ui, getString(R.string.saved_words));
        ImageButton export = ui.iconButton(Icons.Kind.SHARE, ui.text, getString(R.string.export_anki));
        export.setOnClickListener(v -> export());
        page.action(export);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (folder != null) out.putString("folder", folder);
    }

    @Override protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        LinearLayout c = page.content;
        c.removeAllViews();
        if (folder != null && !folder.isEmpty() && !nb.folders().contains(folder)) folder = null;

        // folders
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, ui.dp(4), 0, ui.dp(8));
        row.addView(chip(getString(R.string.all_words, nb.count(null)), folder == null, v -> select(null), null));
        if (nb.count("") > 0 && !nb.folders().isEmpty())
            row.addView(chip(getString(R.string.no_folder_n, nb.count("")), "".equals(folder), v -> select(""), null));
        for (String f : nb.folders()) row.addView(chip(f + "  " + nb.count(f), f.equals(folder), v -> select(f), v -> {
            folderMenu(f);
            return true;
        }));
        TextView add = chip(getString(R.string.new_folder), false, v -> newFolder(), null);
        add.setContentDescription(getString(R.string.new_folder));
        row.addView(add);
        hs.addView(row);
        c.addView(hs);

        List<Notebook.Card> words = nb.saved(folder);
        if (words.isEmpty()) {
            TextView t = ui.text(16, ui.text, Ui.MEDIUM);
            t.setText(R.string.saved_empty);
            t.setGravity(Gravity.CENTER);
            t.setPadding(ui.dp(16), ui.dp(40), ui.dp(16), 0);
            c.addView(t, Page.full());
            TextView h = ui.text(14, ui.sub, Ui.REGULAR);
            h.setText(R.string.saved_empty_hint);
            h.setGravity(Gravity.CENTER);
            h.setLineSpacing(0, 1.25f);
            h.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0);
            c.addView(h, Page.full());
            return;
        }
        TextView review = ui.pill(getResources().getQuantityString(R.plurals.review_these, words.size(), words.size()), true);
        review.setOnClickListener(v -> startActivity(new Intent(this, ReviewActivity.class)
                .putExtra(ReviewActivity.EXTRA_FOLDER, folder == null ? ReviewActivity.ALL_SAVED : folder)));
        c.addView(review, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(48)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(ui.round(ui.surface, 18));
        for (Notebook.Card w : words) list.addView(wordRow(w));
        c.addView(list, Page.full(ui.dp(12), 0));
    }

    private void select(String f) {
        folder = f;
        render();
    }

    private TextView chip(String label, boolean on, View.OnClickListener click, View.OnLongClickListener longClick) {
        TextView t = ui.text(14, on ? ui.onAccent : ui.text, Ui.MEDIUM);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(ui.dp(38));
        t.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        t.setBackground(ui.ripple(on ? ui.round(ui.accent, 19) : ui.outline(ui.surface, ui.line, 19), 19));
        t.setSelected(on);
        t.setOnClickListener(click);
        if (longClick != null) t.setOnLongClickListener(longClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = ui.dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private View wordRow(Notebook.Card w) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        row.setMinimumHeight(ui.dp(60));
        row.setBackground(ui.ripple(null, 0));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView word = ui.line(17, ui.text, Ui.MEDIUM);
        word.setText(w.word);
        Ui.bidi(word);
        top.addView(word, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView meta = ui.line(12, ui.sub, Ui.MEDIUM);
        String level = Lexicon.level(w.word, w.lang);
        meta.setText(w.lang.label + (level == null ? "" : " · " + level) + (folder == null && !w.folder.isEmpty() ? " · " + w.folder : ""));
        top.addView(meta);
        row.addView(top, Page.full());
        TextView back = ui.text(14, ui.sub, Ui.REGULAR);
        back.setText(w.back.replace('\n', '·'));
        back.setMaxLines(2);
        back.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ui.bidi(back);
        row.addView(back, Page.full(ui.dp(4), 0));
        row.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_TEXT, w.word).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
        row.setOnLongClickListener(v -> {
            wordMenu(w);
            return true;
        });
        row.setContentDescription(w.word + ". " + w.back);
        return row;
    }

    private void wordMenu(Notebook.Card w) {
        String[] items = {getString(R.string.move_to_folder), getString(R.string.remove)};
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle(w.word)
                .setItems(items, (d, i) -> {
                    if (i == 1) {
                        nb.unsave(w.lang, w.word);
                        render();
                        return;
                    }
                    List<String> fs = nb.folders();
                    String[] names = new String[fs.size() + 1];
                    names[0] = getString(R.string.no_folder);
                    for (int k = 0; k < fs.size(); k++) names[k + 1] = fs.get(k);
                    new AlertDialog.Builder(this, ui.dialogTheme())
                            .setTitle(R.string.move_to_folder)
                            .setItems(names, (d2, k) -> {
                                nb.save(w.lang, w.word, w.back, k == 0 ? "" : fs.get(k - 1), w.added);
                                render();
                            })
                            .show();
                })
                .show();
    }

    private void newFolder() {
        ask(getString(R.string.new_folder), "", name -> {
            if (!nb.addFolder(name)) Toast.makeText(this, R.string.folder_exists, Toast.LENGTH_SHORT).show();
            else folder = Text.clean(name);
            render();
        });
    }

    private void folderMenu(String f) {
        String[] items = {getString(R.string.rename), getString(R.string.delete_folder)};
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle(f)
                .setItems(items, (d, i) -> {
                    if (i == 0) ask(getString(R.string.rename), f, name -> {
                        nb.renameFolder(f, name);
                        folder = Text.clean(name);
                        render();
                    });
                    else new AlertDialog.Builder(this, ui.dialogTheme())
                            .setMessage(getString(R.string.delete_folder_confirm, f))
                            .setPositiveButton(R.string.delete_folder, (d2, w) -> {
                                nb.deleteFolder(f);
                                folder = null;
                                render();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                })
                .show();
    }

    interface Answer {
        void text(String s);
    }

    private void ask(String title, String value, Answer answer) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setSelection(value.length());
        LinearLayout box = new LinearLayout(this);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle(title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (!Text.clean(e.getText().toString()).isEmpty()) answer.text(e.getText().toString());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ export for Anki

    @SuppressWarnings("deprecation")
    private void export() {
        if (nb.count(folder) == 0) {
            Toast.makeText(this, R.string.saved_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "trilingo" + (folder == null || folder.isEmpty() ? "" : "-" + folder.replace(' ', '-')) + ".txt";
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(it, EXPORT);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        try (OutputStream o = getContentResolver().openOutputStream(data.getData())) {
            if (o == null) throw new java.io.IOException("no stream");
            o.write(nb.ankiExport(folder).getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, getString(R.string.exported, nb.count(folder)), Toast.LENGTH_LONG).show();
        } catch (java.io.IOException | RuntimeException e) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }
}
