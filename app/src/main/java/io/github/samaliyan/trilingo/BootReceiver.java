package io.github.samaliyan.trilingo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the quick-translate bubble back after the phone restarts or the app is updated (when it was on). */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (new Store(c).bubble()) BubbleService.start(c);
        // After an update the home screen still shows the widgets as the old version drew them, with the old
        // version's taps (the whole widget opening the app): draw them anew.
        WidgetProvider.updateAll(c);
    }
}
