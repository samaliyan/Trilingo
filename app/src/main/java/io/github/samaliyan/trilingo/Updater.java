package io.github.samaliyan.trilingo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.res.ColorStateList;
import android.os.Build;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-update from GitHub Releases.
 * 1) reads the latest release (its notes end with "code=N", the build number of the APK);
 * 2) if N is newer, asks the user; 3) downloads the APK with a progress dialog and checks it;
 * 4) hands it to Android's installer, which always shows its own confirmation.
 * Only HTTPS addresses on github.com / githubusercontent.com are followed.
 */
final class Updater {
    static final String REPO = "samaliyan/Trilingo";
    static final String REPO_URL = "https://github.com/" + REPO;
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String APK_URL = REPO_URL + "/releases/latest/download/Trilingo.apk";
    /** Same information as the API answer, as a release file (no API rate limit). */
    private static final String INFO_URL = REPO_URL + "/releases/latest/download/update.txt";
    private static final Pattern VERSION = Pattern.compile("(?m)^version=(\\S+)\\s*$");
    private static final String ACTION = "io.github.samaliyan.trilingo.INSTALL_RESULT";
    private static final int MAX_APK = 30 * 1024 * 1024;
    private static final Pattern CODE = Pattern.compile("code=(\\d{1,9})");

    private final Activity act;
    private final Ui ui;
    private volatile boolean checking, downloading, cancelled;
    private BroadcastReceiver receiver;
    private volatile int sessionId = -1;
    private volatile String token;

    Updater(Activity a, Ui ui) {
        act = a;
        this.ui = ui;
    }

    int versionCode() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : versionCodeOld(pi);
        } catch (Exception e) {
            return 0;
        }
    }

    @SuppressWarnings("deprecation")
    private static int versionCodeOld(PackageInfo pi) {
        return pi.versionCode;
    }

    String versionName() {
        try {
            String n = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
            return n != null ? n : "?";
        } catch (Exception e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ check

    /** @param silent true when called automatically: no message unless an update exists. */
    void check(final boolean silent) {
        if (checking || downloading) return;
        checking = true;
        if (!silent) Toast.makeText(act, R.string.checking, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String body, version;
                try {
                    JSONObject j = new JSONObject(readText(API, silent ? 8000 : 15000));
                    body = j.optString("body", "");
                    version = j.optString("tag_name", "").replaceFirst("^[vV]", "");
                } catch (Exception apiFailed) {
                    // GitHub's API allows 60 requests an hour per network; the release file has no limit.
                    body = readText(INFO_URL, silent ? 8000 : 15000);
                    Matcher vm = VERSION.matcher(body);
                    version = vm.find() ? vm.group(1) : "";
                    body = VERSION.matcher(body).replaceAll("");
                }
                Matcher m = CODE.matcher(body);
                if (!m.find()) throw new java.io.IOException("no build number");
                int code = Integer.parseInt(m.group(1));
                final String notes = CODE.matcher(body).replaceAll("").trim();
                final String ver = version;
                new Store(act).setLastUpdateCheck(System.currentTimeMillis());
                ui(() -> {
                    if (code > versionCode()) offer(ver, code, notes);
                    else if (!silent) Toast.makeText(act, R.string.up_to_date, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                if (!silent) ui(() -> Toast.makeText(act, R.string.check_failed, Toast.LENGTH_LONG).show());
            } finally {
                checking = false;
            }
        }, "update-check").start();
    }

    private void offer(String version, int code, String notes) {
        StringBuilder msg = new StringBuilder(act.getString(R.string.update_message, version, code, versionName(), versionCode()));
        String n = shortNotes(notes, 420);
        if (!n.isEmpty()) msg.append("\n\n").append(n);
        new AlertDialog.Builder(act, ui.dialogTheme())
                .setTitle(R.string.update_title)
                .setMessage(msg)
                .setPositiveButton(R.string.update_now, (d, w) -> download())
                .setNegativeButton(R.string.later, null)
                .show();
    }

    /**
     * Release notes for the dialog, as plain text: markdown marks (#, **, `) removed, bullets shown as •
     * (sub-points as ◦), wrapped lines joined, cut at a whole line at most {@code max} characters.
     */
    static String shortNotes(String notes, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String raw : notes.replace("\r", "").split("\n")) {
            String line = raw.replace("**", "").replace("__", "").replace("`", "");
            String t = line.trim();
            if (t.isEmpty()) {
                if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) out.add("");
                continue;
            }
            int indent = line.length() - line.replaceAll("^\\s+", "").length();
            if (t.startsWith("#")) {
                out.add(t.replaceAll("^#+\\s*", ""));
            } else if (t.startsWith("- ") || t.startsWith("* ")) {
                out.add((indent >= 2 ? "    \u25E6 " : "\u2022 ") + t.substring(2).trim());
            } else if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + " " + t);      // continuation of the line above
            } else {
                out.add(t);
            }
        }
        String n = String.join("\n", out).trim();
        if (n.length() <= max) return n;
        int cut = n.lastIndexOf('\n', max);
        return (cut > 0 ? n.substring(0, cut) : n.substring(0, max)).trim() + "\n\u2026";
    }

    // ------------------------------------------------------------------ download

    private void download() {
        if (downloading) return;
        downloading = true;
        cancelled = false;

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        bar.setProgressTintList(ColorStateList.valueOf(ui.accent));
        bar.setIndeterminateTintList(ColorStateList.valueOf(ui.accent));
        final TextView pct = ui.text(14, ui.sub, Ui.REGULAR);
        pct.setText(R.string.downloading);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(8);
        box.addView(pct, lp);
        final AlertDialog dlg = new AlertDialog.Builder(act, ui.dialogTheme())
                .setTitle(R.string.update_title)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled = true)
                .show();

        new Thread(() -> {
            File dir = new File(act.getCacheDir(), "update");
            File tmp = new File(dir, "download.tmp"), apk = new File(dir, "Trilingo.apk");
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cache");
                HttpURLConnection c = open(APK_URL, 30000);
                long len = c.getContentLengthLong();
                if (len > MAX_APK) throw new IOException("too big");
                int total = 0, last = -1;
                boolean zip = false;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled) throw new IOException("cancelled");
                        if (total == 0 && n >= 2) zip = buf[0] == 'P' && buf[1] == 'K';
                        total += n;
                        if (total > MAX_APK) throw new IOException("too big");
                        out.write(buf, 0, n);
                        if (len > 0) {
                            final int p = (int) (total * 100L / len);
                            if (p != last) {
                                last = p;
                                ui(() -> {
                                    bar.setIndeterminate(false);
                                    bar.setProgress(p);
                                    pct.setText(act.getString(R.string.downloading_pct, p));
                                });
                            }
                        }
                    }
                } finally {
                    c.disconnect();
                }
                if (!zip || total < 10_000) throw new IOException("not an apk");
                PackageInfo pi = act.getPackageManager().getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
                if (pi == null || !act.getPackageName().equals(pi.packageName)) throw new IOException("wrong package");
                int code = Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : versionCodeOld(pi);
                if (code <= versionCode()) throw new IOException("not newer");
                if (apk.exists() && !apk.delete()) throw new IOException("cache");
                if (!tmp.renameTo(apk)) throw new IOException("rename");
                ui(() -> {
                    dismiss(dlg);
                    install(apk);
                });
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                final boolean byUser = cancelled;
                ui(() -> {
                    dismiss(dlg);
                    if (!byUser) Toast.makeText(act, R.string.download_failed, Toast.LENGTH_LONG).show();
                });
            } finally {
                downloading = false;
            }
        }, "update-download").start();
    }

    // ------------------------------------------------------------------ install

    @SuppressLint("InlinedApi")
    private void install(final File apk) {
        new Thread(() -> {
            try {
                registerReceiver();
                PackageInstaller pi = act.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(act.getPackageName());
                int id = pi.createSession(params);
                sessionId = id;
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (OutputStream o = s.openWrite("Trilingo.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                        s.fsync(o);
                    } catch (IOException e) {
                        s.abandon();
                        throw e;
                    }
                    token = UUID.randomUUID().toString();
                    Intent it = new Intent(ACTION).setPackage(act.getPackageName()).putExtra("token", token);
                    // Mutable: the installer adds the result to this intent. (The flag is ignored before Android 12.)
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
                    s.commit(PendingIntent.getBroadcast(act.getApplicationContext(), id, it, flags).getIntentSender());
                }
            } catch (Exception e) {
                ui(() -> Toast.makeText(act, R.string.install_failed, Toast.LENGTH_LONG).show());
            }
        }, "update-install").start();
    }

    /**
     * The installer answers through a broadcast. It is registered on the application context so the answer
     * still arrives if the screen that started the update is closed meanwhile; it unregisters itself at the end.
     */
    @SuppressLint("InlinedApi")
    private synchronized void registerReceiver() {
        if (receiver != null) return;
        final Context app = act.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent it) {
                // Accept only the answer for our own session (older Android lets any app send broadcasts).
                if (it.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != sessionId || token == null
                        || !token.equals(it.getStringExtra("token"))) return;
                int st = it.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = confirmIntent(it);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            app.startActivity(confirm);
                        } catch (Exception e) {
                            Toast.makeText(app, R.string.install_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                    return;
                }
                if (st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    Toast.makeText(app, R.string.install_conflict, Toast.LENGTH_LONG).show();
                } else if (st != PackageInstaller.STATUS_SUCCESS && st != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Toast.makeText(app, R.string.install_failed, Toast.LENGTH_LONG).show();
                }
                unregister();
            }
        };
        // Not exported on Android 13+; on older versions the random token above rejects foreign broadcasts.
        app.registerReceiver(receiver, new IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED);
    }

    @SuppressWarnings("deprecation")
    private static Intent confirmIntent(Intent it) {
        return Build.VERSION.SDK_INT >= 33 ? it.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                : (Intent) it.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    private synchronized void unregister() {
        try {
            if (receiver != null) act.getApplicationContext().unregisterReceiver(receiver);
        } catch (Exception ignored) {
            // already unregistered
        }
        receiver = null;
    }

    /** Called when the screen closes: stops a running download. A started installation still finishes. */
    void destroy() {
        cancelled = true;
    }

    // ------------------------------------------------------------------ network helpers

    private static boolean hostOk(String h) {
        if (h == null) return false;
        h = h.toLowerCase(Locale.ROOT);
        return h.equals("github.com") || h.equals("api.github.com") || h.endsWith(".githubusercontent.com");
    }

    private static HttpURLConnection open(String url, int timeoutMs) throws IOException {
        for (int hop = 0; hop < 6; hop++) {
            URL u = new URL(url);
            if (!"https".equals(u.getProtocol()) || !hostOk(u.getHost())) throw new IOException("host");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Trilingo-updater");
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, */*");
            int st = c.getResponseCode();
            if (st >= 300 && st < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("redirect");
                url = new URL(u, loc).toString();
                continue;
            }
            if (st != 200) {
                c.disconnect();
                throw new IOException("http " + st);
            }
            return c;
        }
        throw new IOException("too many redirects");
    }

    private static String readText(String url, int timeoutMs) throws IOException {
        HttpURLConnection c = open(url, timeoutMs);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0 && total < 512 * 1024) {
                bo.write(buf, 0, n);
                total += n;
            }
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void ui(Runnable r) {
        act.runOnUiThread(() -> {
            if (!act.isFinishing() && !act.isDestroyed()) r.run();
        });
    }

    private static void dismiss(AlertDialog d) {
        try {
            if (d.isShowing()) d.dismiss();
        } catch (Exception ignored) {
            // window already gone
        }
    }
}
