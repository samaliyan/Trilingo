package io.github.samaliyan.trilingo;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Small motion and touch feedback: a short vibration and a nod for a right answer, a shake for a wrong one,
 * a soft fade for new content. Vibration follows the app setting (and the phone's touch-feedback setting);
 * motion is skipped when the phone has animations turned off.
 */
final class Feel {
    private Feel() { }

    private static boolean animations() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    private static void haptic(View v, int constant) {
        if (v == null || !new Store(v.getContext()).haptics()) return;
        v.performHapticFeedback(constant);
    }

    /** Right answer: a light confirm vibration and a small "pop". */
    static void right(View v) {
        haptic(v, Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
        if (v == null || !animations()) return;
        v.animate().cancel();
        v.setScaleX(0.96f);
        v.setScaleY(0.96f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
    }

    /** Wrong answer: a stronger vibration and a short sideways shake. */
    static void wrong(View v) {
        haptic(v, Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS);
        if (v == null || !animations()) return;
        float d = v.getResources().getDisplayMetrics().density * 8;
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0, -d, d, -d * 0.6f, d * 0.6f, -d * 0.25f, 0);
        a.setDuration(380);
        a.start();
    }

    /** A light tick (saving a word, a swipe action). */
    static void tick(View v) {
        haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** New content fades and slides in a little. */
    static void appear(View v) {
        if (v == null || !animations()) return;
        v.setAlpha(0f);
        v.setTranslationY(v.getResources().getDisplayMetrics().density * 6);
        v.animate().alpha(1f).translationY(0).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** A button that changed state (star filled) grows and settles. */
    static void pop(View v) {
        tick(v);
        if (v == null || !animations()) return;
        v.setScaleX(0.6f);
        v.setScaleY(0.6f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(new OvershootInterpolator(4f)).start();
    }
}
