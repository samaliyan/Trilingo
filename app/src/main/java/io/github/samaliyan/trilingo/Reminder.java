package io.github.samaliyan.trilingo;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/**
 * The daily reminder of Learn: at the chosen time, a notification "Your lesson for today" — only when
 * today's lesson is not done yet. Set again after a restart and after an update ({@link BootReceiver}).
 */
public final class Reminder extends BroadcastReceiver {
    private static final String CHANNEL = "lesson";

    /** Sets (or cancels) the daily alarm from the setting. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Reminder.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        int minutes = new Store(c).reminder();
        if (minutes < 0) return;
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, minutes / 60);
        at.set(Calendar.MINUTE, minutes % 60);
        at.set(Calendar.SECOND, 0);
        if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(Calendar.DAY_OF_YEAR, 1);
        // not exact to the minute (no special permission, kind to the battery)
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, at.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
    }

    @Override public void onReceive(Context c, Intent intent) {
        Store store = new Store(c);
        if (store.reminder() < 0) return;
        Course course = Store.course(c);
        long now = System.currentTimeMillis();
        Course.Track t = course.track(course.learning);
        if (t.day == Progress.day(now) && t.lessonsToday > 0) return;          // done today
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.learn_reminder),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        int due = 0;
        for (Notebook.Card card : Store.notebook(c).due(null, null, now, 500)) if (card.lang == course.learning) due++;
        int streak = Store.notebook(c).progress.streak(now);
        Intent open = new Intent(c, LearnActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = streak > 0 ? c.getResources().getQuantityString(R.plurals.learn_reminder_streak, streak, streak)
                : c.getString(R.string.learn_reminder_text, due);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(c.getString(R.string.learn_reminder_title, course.learning.label))
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(41, n);
        } catch (SecurityException e) {
            // notifications not allowed
        }
    }
}
