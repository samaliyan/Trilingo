package io.github.samaliyan.trilingo;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/**
 * Pronunciation practice: plays a real recording of the word (from the dictionary), records the user saying it
 * and plays that back, so both can be compared. One recording is kept (in the cache folder).
 */
final class Voice {
    static final int PERMISSION_REQUEST = 41;
    private static final int MAX_MS = 6000;

    interface Listener {
        /** Recording started or stopped, or a recording became available. */
        void changed();
    }

    private final Activity act;
    private final File mine;
    private MediaPlayer player;
    private MediaRecorder recorder;
    private Listener listener;
    private Runnable afterPermission;
    private final Runnable autoStop = this::stopRecording;

    Voice(Activity act) {
        this.act = act;
        this.mine = new File(act.getCacheDir(), "my_voice.m4a");
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isRecording() {
        return recorder != null;
    }

    boolean hasRecording() {
        return mine.exists() && mine.length() > 0 && recorder == null;
    }

    /** Forgets the recording (a new word was looked up). */
    void clear() {
        stopRecording();
        mine.delete();
    }

    void play(String url) {
        stopPlaying();
        MediaPlayer p = new MediaPlayer();
        player = p;
        try {
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            p.setDataSource(url);
            p.setOnPreparedListener(MediaPlayer::start);
            p.setOnCompletionListener(mp -> stopPlaying());
            p.setOnErrorListener((mp, what, extra) -> {
                stopPlaying();
                Toast.makeText(act, R.string.speak_failed, Toast.LENGTH_SHORT).show();
                return true;
            });
            p.prepareAsync();
        } catch (IOException | RuntimeException e) {
            stopPlaying();
            Toast.makeText(act, R.string.speak_failed, Toast.LENGTH_SHORT).show();
        }
    }

    void playMine() {
        if (hasRecording()) play(mine.getAbsolutePath());
    }

    /** Starts recording (asks for the microphone first if needed); a second call stops it. */
    void toggleRecording() {
        if (recorder != null) {
            stopRecording();
            return;
        }
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = this::startRecording;
            act.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
            return;
        }
        startRecording();
    }

    /** From the activity's onRequestPermissionsResult. */
    void permissionResult(boolean granted) {
        Runnable r = afterPermission;
        afterPermission = null;
        if (granted && r != null) r.run();
        else if (!granted) Toast.makeText(act, R.string.mic_denied, Toast.LENGTH_LONG).show();
    }

    @SuppressWarnings("deprecation")
    private void startRecording() {
        stopPlaying();
        mine.delete();
        MediaRecorder r = android.os.Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(act) : new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioSamplingRate(44100);
            r.setAudioEncodingBitRate(64000);
            r.setOutputFile(mine.getAbsolutePath());
            r.prepare();
            r.start();
            recorder = r;
            Net.MAIN.postDelayed(autoStop, MAX_MS);
        } catch (IOException | RuntimeException e) {
            r.release();
            recorder = null;
            Toast.makeText(act, R.string.record_failed, Toast.LENGTH_SHORT).show();
        }
        if (listener != null) listener.changed();
    }

    private void stopRecording() {
        Net.MAIN.removeCallbacks(autoStop);
        MediaRecorder r = recorder;
        recorder = null;
        if (r != null) {
            try {
                r.stop();
            } catch (RuntimeException e) {
                mine.delete();                 // stopped too early: nothing usable was recorded
            }
            r.release();
            if (listener != null) listener.changed();
        }
    }

    private void stopPlaying() {
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try {
                p.release();
            } catch (RuntimeException ignored) {
                // already released
            }
        }
    }

    void release() {
        stopRecording();
        stopPlaying();
    }
}
