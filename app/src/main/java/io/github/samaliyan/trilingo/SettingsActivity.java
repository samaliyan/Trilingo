package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings: theme, translation service, history, widget, updates, about. Only what is needed. */
public final class SettingsActivity extends Activity {
    private Ui ui;
    private Store store;
    private LinearLayout content;
    private Updater updater;
    private int themeAtStart;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new Store(this);
        ui = Ui.of(this);
        ui.applyWindow(this);
        themeAtStart = store.theme();
        updater = new Updater(this, ui);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(4), ui.dp(8), ui.dp(16), ui.dp(8));
        ImageButton back = ui.iconButton(Icons.Kind.BACK, ui.text, getString(R.string.back));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        TextView title = ui.line(22, ui.text, Ui.MEDIUM);
        title.setText(R.string.settings);
        title.setPadding(ui.dp(4), 0, 0, 0);
        bar.addView(title);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, ui.dp(24));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private static final int BACKUP = 71, RESTORE = 72, NOTIFY = 73;
    /** The bubble was switched on and the user was sent to allow "display over other apps". */
    private boolean wantBubble;

    @Override protected void onResume() {
        super.onResume();
        if (wantBubble) {
            wantBubble = false;
            if (BubbleService.canDraw(this)) enableBubble();
        }
        build();
    }

    // ------------------------------------------------------------------ quick-translate bubble

    private void bubbleSwitch(boolean on) {
        if (!on) {
            store.setBubble(false);
            BubbleService.stop(this);
            return;
        }
        if (BubbleService.canDraw(this)) {
            enableBubble();
            return;
        }
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle(R.string.bubble)
                .setMessage(R.string.bubble_permission)
                .setPositiveButton(R.string.allow, (d, w) -> {
                    wantBubble = true;
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException e) {
                        wantBubble = false;
                        Toast.makeText(this, R.string.bubble_no_permission, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> build())
                .setOnCancelListener(d -> build())
                .show();
    }

    private void enableBubble() {
        store.setBubble(true);
        // Android 13+: the bubble's (quiet) notification needs this permission to be visible; it works without it
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, NOTIFY);
        BubbleService.start(this);
        Toast.makeText(this, R.string.bubble_started, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ backup

    @SuppressWarnings("deprecation")
    private void backup() {
        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date());
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "trilingo-backup-" + day + ".json");
        try {
            startActivityForResult(it, BACKUP);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void restore() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try {
            startActivityForResult(it, RESTORE);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == BACKUP) {
            Notebook nb = Store.notebook(this);
            String text = Backup.create(store.exportSettings(), nb.toJson(), Store.history(this).toJson(),
                    System.currentTimeMillis(), updater.versionName());
            try (java.io.OutputStream o = getContentResolver().openOutputStream(uri, "wt")) {
                if (o == null) throw new java.io.IOException("no stream");
                o.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                int n = nb.count(null);
                Toast.makeText(this, getResources().getQuantityString(R.plurals.backup_done, n, n), Toast.LENGTH_LONG).show();
            } catch (java.io.IOException | RuntimeException e) {
                Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show();
            }
        } else if (request == RESTORE) {
            Backup.Contents b;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("no stream");
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 30 * 1024 * 1024) throw new java.io.IOException("too big");
                    bo.write(buf, 0, n);
                }
                b = Backup.read(bo.toString("UTF-8"));
            } catch (java.io.IOException | RuntimeException e) {
                Toast.makeText(this, R.string.restore_not_backup, Toast.LENGTH_LONG).show();
                return;
            }
            confirmRestore(b);
        }
    }

    private void confirmRestore(Backup.Contents b) {
        String when = b.created > 0 ? java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(b.created)) : "?";
        int recent = b.history == null ? 0 : b.history.size();
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle(R.string.restore)
                .setMessage(getString(R.string.restore_confirm, when, b.savedWords, recent))
                .setPositiveButton(R.string.restore_short, (d, w) -> {
                    int added = Store.notebook(this).merge(b.notebookJson);
                    Store.history(this).merge(b.history);
                    int theme = store.theme(), scale = store.textScale();
                    store.importSettings(b.settings);
                    WidgetProvider.updateAll(this);
                    Toast.makeText(this, getResources().getQuantityString(R.plurals.restored_n, added, added), Toast.LENGTH_LONG).show();
                    if (store.theme() != theme || store.textScale() != scale) recreate();
                    else build();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override protected void onDestroy() {
        updater.destroy();
        super.onDestroy();
    }

    private void build() {
        content.removeAllViews();

        section(R.string.section_translation);
        String[] services = {getString(R.string.service_google), getString(R.string.service_mymemory)};
        item(getString(R.string.service), services[store.service()] + "\n" + getString(R.string.service_hint),
                v -> choose(R.string.service, services, store.service(), i -> {
                    store.setService(i);
                    build();
                }));

        section(R.string.section_quick);
        toggle(getString(R.string.bubble), getString(R.string.bubble_hint), store.bubble() && BubbleService.canDraw(this), this::bubbleSwitch);

        section(R.string.section_display);
        String[] themes = {getString(R.string.phone_setting), getString(R.string.theme_light), getString(R.string.theme_dark)};
        item(getString(R.string.theme), themes[store.theme()], v -> choose(R.string.theme, themes, store.theme(), i -> {
            store.setTheme(i);
            if (i != themeAtStart) recreate();
        }));
        String[] sizes = {getString(R.string.size_small), getString(R.string.size_normal), getString(R.string.size_large), getString(R.string.size_xlarge)};
        item(getString(R.string.text_size), sizes[store.textScale()], v -> choose(R.string.text_size, sizes, store.textScale(), i -> {
            if (i == store.textScale()) return;
            store.setTextScale(i);
            recreate();
        }));

        toggle(getString(R.string.haptics), getString(R.string.haptics_hint), store.haptics(), store::setHaptics);

        section(R.string.section_history);
        toggle(getString(R.string.save_history), getString(R.string.save_history_hint), store.saveHistory(), b -> {
            store.setSaveHistory(b);
            WidgetProvider.updateAll(this);
        });
        int n = Store.history(this).items().size();
        item(getString(R.string.clear_history), getResources().getQuantityString(R.plurals.words_saved, n, n), v -> {
            if (Store.history(this).items().isEmpty()) {
                Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this, ui.dialogTheme())
                    .setMessage(R.string.clear_history_confirm)
                    .setPositiveButton(R.string.clear_history_short, (d, w) -> {
                        Store.history(this).clear();
                        Store.notebook(this).forgetAllRecent();
                        WidgetProvider.updateAll(this);
                        build();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });

        toggle(getString(R.string.review_recent), getString(R.string.review_recent_hint), store.reviewRecent(), store::setReviewRecent);

        section(R.string.section_backup);
        item(getString(R.string.backup_now), getString(R.string.backup_hint), v -> backup());
        item(getString(R.string.restore), getString(R.string.restore_hint), v -> restore());

        section(R.string.section_widget);
        String[] wthemes = {getString(R.string.phone_setting), getString(R.string.theme_light), getString(R.string.theme_dark)};
        item(getString(R.string.widget_theme), wthemes[store.widgetTheme()], v -> choose(R.string.widget_theme, wthemes, store.widgetTheme(), i -> {
            store.setWidgetTheme(i);
            WidgetProvider.updateAll(this);
            build();
        }));
        item(getString(R.string.widget_add_home), getString(R.string.widget_add_hint), v -> {
            if (MainActivity.canPinWidget(this)) MainActivity.pinWidget(this);
            else Toast.makeText(this, R.string.widget_add_manual, Toast.LENGTH_LONG).show();
        });

        section(R.string.section_updates);
        toggle(getString(R.string.auto_update), getString(R.string.auto_update_hint), store.autoUpdate(), store::setAutoUpdate);
        item(getString(R.string.check_now), getString(R.string.version_value, updater.versionName(), updater.versionCode()),
                v -> updater.check(false));

        section(R.string.section_about);
        item(getString(R.string.show_intro), getString(R.string.show_intro_hint), v -> startActivity(new Intent(this, OnboardingActivity.class)));
        item(getString(R.string.data_sources), getString(R.string.data_sources_value), v -> { });
        item(getString(R.string.source_code), Updater.REPO_URL.replace("https://", ""), v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Updater.REPO_URL)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ------------------------------------------------------------------ building blocks

    interface IntChoice { void on(int i); }
    interface BoolChoice { void on(boolean b); }

    private void choose(int titleRes, String[] items, int selected, IntChoice cb) {
        new AlertDialog.Builder(this, ui.dialogTheme())
                .setTitle(titleRes)
                .setSingleChoiceItems(items, selected, (d, which) -> {
                    d.dismiss();
                    cb.on(which);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void section(int titleRes) {
        TextView t = ui.line(13, ui.accent, Ui.MEDIUM);
        t.setText(titleRes);
        t.setPadding(ui.dp(24), ui.dp(20), ui.dp(24), ui.dp(8));
        content.addView(t);
    }

    private void item(String title, String value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12));
        row.setMinimumHeight(ui.dp(64));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ui.ripple(null, 0));
        row.setOnClickListener(click);
        TextView t = ui.text(16, ui.text, Ui.REGULAR);
        t.setText(title);
        TextView s = ui.text(13, ui.sub, Ui.REGULAR);
        s.setText(value);
        s.setLineSpacing(0, 1.15f);
        row.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(4);
        row.addView(s, lp);
        content.addView(row);
    }

    private void toggle(String title, String hint, boolean on, BoolChoice cb) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(24), ui.dp(12), ui.dp(20), ui.dp(12));
        row.setMinimumHeight(ui.dp(64));
        row.setBackground(ui.ripple(null, 0));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = ui.text(16, ui.text, Ui.REGULAR);
        t.setText(title);
        TextView s = ui.text(13, ui.sub, Ui.REGULAR);
        s.setText(hint);
        texts.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(4);
        texts.addView(s, lp);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final Switch sw = new Switch(this);
        sw.setChecked(on);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{ui.accent, ui.dark ? 0xFFB8BEC8 : 0xFFFFFFFF}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{ui.accent & 0x80FFFFFF, ui.dark ? 0xFF3A414C : 0xFFC5CBD3}));
        sw.setOnCheckedChangeListener((b, c) -> cb.on(c));
        sw.setContentDescription(title);
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        content.addView(row);
    }
}
