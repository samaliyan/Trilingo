package io.github.samaliyan.trilingo;

import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The quick-translate bubble: a small round button over other apps. Copy a word or a sentence anywhere,
 * tap the bubble, and the translation opens in a small panel ({@link BubbleActivity}). Drag the bubble to
 * move it; drop it on the × at the bottom to turn it off. Runs as a foreground service (with a quiet
 * notification), because Android stops background work otherwise.
 */
public final class BubbleService extends Service {
    static final String ACTION_STOP = "io.github.samaliyan.trilingo.BUBBLE_STOP";
    private static final String CHANNEL = "bubble";
    private static final int NOTIFICATION = 7;

    private WindowManager wm;
    private FrameLayout bubble, target;
    private WindowManager.LayoutParams bp, tp;
    private SharedPreferences pos;
    private Ui ui;

    static boolean canDraw(Context c) {
        return Settings.canDrawOverlays(c);
    }

    static void start(Context c) {
        if (!canDraw(c)) return;
        try {
            c.startForegroundService(new Intent(c, BubbleService.class));
        } catch (RuntimeException e) {
            // not allowed right now (e.g. started from the background on a new Android): the next app start tries again
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, BubbleService.class));
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onCreate() {
        super.onCreate();
        ui = Ui.of(this);
        pos = getSharedPreferences("bubble", MODE_PRIVATE);
        foreground();
        if (!canDraw(this)) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        addBubble();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            new Store(this).setBubble(false);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        removeViews();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ notification

    private void foreground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.bubble_channel), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BubbleService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(getString(R.string.bubble_on))
                .setContentText(getString(R.string.bubble_on_text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, getString(R.string.turn_off), stop).build())
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIFICATION, n);
    }

    // ------------------------------------------------------------------ the bubble

    private WindowManager.LayoutParams params(int w, int h) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }

    private void addBubble() {
        int size = ui.dp(54);
        bubble = new FrameLayout(this);
        bubble.setBackground(ui.round(ui.accent, 27));
        bubble.setElevation(ui.dp(6));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(Icons.of(Icons.Kind.BUBBLE, ui.onAccent));
        bubble.addView(icon, new FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER));
        bubble.setContentDescription(getString(R.string.bubble_desc));
        bubble.setAlpha(0.9f);
        bp = params(size, size);
        int screenW = getResources().getDisplayMetrics().widthPixels, screenH = getResources().getDisplayMetrics().heightPixels;
        bp.x = Math.min(pos.getInt("x", screenW - size - ui.dp(8)), screenW - size);
        bp.y = Math.min(pos.getInt("y", screenH / 3), screenH - size);
        try {
            wm.addView(bubble, bp);
        } catch (RuntimeException e) {
            stopSelf();
            return;
        }

        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        float[] down = new float[4];
        boolean[] dragging = {false};
        bubble.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    down[2] = bp.x;
                    down[3] = bp.y;
                    dragging[0] = false;
                    bubble.setAlpha(1f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (!dragging[0] && Math.hypot(dx, dy) > slop) {
                        dragging[0] = true;
                        showTarget(true);
                    }
                    if (dragging[0]) {
                        bp.x = (int) (down[2] + dx);
                        bp.y = (int) (down[3] + dy);
                        update();
                        highlightTarget(overTarget());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    bubble.setAlpha(0.9f);
                    if (!dragging[0]) {
                        v.performClick();                // → open()
                    } else if (overTarget()) {
                        Feel.tick(bubble);
                        new Store(this).setBubble(false);
                        stopSelf();
                    } else {
                        showTarget(false);
                        snap();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    bubble.setAlpha(0.9f);
                    showTarget(false);
                    snap();
                    return true;
                default:
                    return false;
            }
        });
        bubble.setOnClickListener(v -> open());
    }

    private void update() {
        try {
            if (bubble != null && bubble.isAttachedToWindow()) wm.updateViewLayout(bubble, bp);
        } catch (RuntimeException ignored) {
            // the window is going away
        }
    }

    /** Moves to the nearest side of the screen and remembers the place. */
    private void snap() {
        int screenW = getResources().getDisplayMetrics().widthPixels, screenH = getResources().getDisplayMetrics().heightPixels;
        int size = bp.width;
        int endX = bp.x + size / 2 < screenW / 2 ? ui.dp(6) : screenW - size - ui.dp(6);
        bp.y = Math.max(ui.dp(24), Math.min(bp.y, screenH - size - ui.dp(48)));
        ValueAnimator a = ValueAnimator.ofInt(bp.x, endX);
        a.setDuration(220);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(x -> {
            bp.x = (int) x.getAnimatedValue();
            update();
        });
        a.start();
        pos.edit().putInt("x", endX).putInt("y", bp.y).apply();
    }

    private void open() {
        Feel.tick(bubble);
        Intent it = new Intent(this, BubbleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(it);
        } catch (RuntimeException ignored) {
            // the system did not allow it at this moment
        }
    }

    // ------------------------------------------------------------------ the × target to turn it off

    private void showTarget(boolean show) {
        if (show && target == null) {
            int size = ui.dp(64);
            target = new FrameLayout(this);
            target.setBackground(ui.round(0xCC1F242C, 32));
            ImageView x = new ImageView(this);
            x.setImageDrawable(Icons.of(Icons.Kind.CLOSE, 0xFFFFFFFF));
            target.addView(x, new FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER));
            tp = params(size, size);
            tp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            tp.x = (getResources().getDisplayMetrics().widthPixels - size) / 2;
            tp.y = getResources().getDisplayMetrics().heightPixels - size - ui.dp(72);
            try {
                wm.addView(target, tp);
            } catch (RuntimeException e) {
                target = null;
            }
        } else if (!show && target != null) {
            try {
                wm.removeView(target);
            } catch (RuntimeException ignored) {
                // gone already
            }
            target = null;
        }
    }

    private boolean overTarget() {
        if (target == null) return false;
        float bx = bp.x + bp.width / 2f, by = bp.y + bp.height / 2f;
        float tx = tp.x + tp.width / 2f, ty = tp.y + tp.height / 2f;
        return Math.hypot(bx - tx, by - ty) < ui.dp(64);
    }

    private void highlightTarget(boolean over) {
        if (target == null) return;
        target.setScaleX(over ? 1.2f : 1f);
        target.setScaleY(over ? 1.2f : 1f);
        target.setBackground(ui.round(over ? 0xFFDC3B33 : 0xCC1F242C, 32));
    }

    private void removeViews() {
        showTarget(false);
        if (bubble != null && wm != null) {
            try {
                wm.removeView(bubble);
            } catch (RuntimeException ignored) {
                // gone already
            }
        }
        bubble = null;
    }
}
