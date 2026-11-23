package io.github.samaliyan.trilingo;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/** Quick-settings tile (pull down the notification shade → "Translate"): opens the app ready to type. */
@SuppressLint("StartActivityAndCollapseDeprecated")   // the old call is only used before Android 14
public final class TranslateTile extends TileService {

    @Override public void onClick() {
        super.onClick();
        Intent it = new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 100, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        } else {
            startOld(it);
        }
    }

    @SuppressWarnings("deprecation")
    private void startOld(Intent it) {
        startActivityAndCollapse(it);
    }
}
