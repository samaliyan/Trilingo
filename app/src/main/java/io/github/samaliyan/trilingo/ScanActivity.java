package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Scan text: take a photo (or choose a picture) of a sign, a page or a menu; the text is recognised on the
 * phone (no internet needed) and every word can be tapped to translate it.
 */
public final class ScanActivity extends Activity {
    private static final int CAMERA = 71, PICK = 72;

    private Ui ui;
    private Page page;
    private Engine engine;
    private Store store;
    private Bitmap photo;
    private final List<Word> words = new ArrayList<>();
    private String allText = "";
    private LinearLayout panel;
    private int generation;

    static final class Word {
        final String text;
        final Rect box;

        Word(String text, Rect box) {
            this.text = text;
            this.box = box;
        }
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        store = new Store(this);
        engine = new Engine(Engine.NET);
        page = new Page(this, ui, getString(R.string.scan_text));
        Uri shared = sharedImage(getIntent());
        if (saved == null && shared != null) read(shared);
        else start();
    }

    /** A picture shared to "Scan text" (a screenshot, a photo from the gallery), or null. */
    @SuppressWarnings("deprecation")
    private static Uri sharedImage(Intent it) {
        if (it == null) return null;
        if (Intent.ACTION_SEND.equals(it.getAction())) {
            Object o = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (o instanceof Uri) return (Uri) o;
        }
        if (Intent.ACTION_SEND.equals(it.getAction()) || Intent.ACTION_VIEW.equals(it.getAction())) return it.getData();
        return null;
    }

    @Override protected void onDestroy() {
        engine.shutdown();
        super.onDestroy();
    }

    private void start() {
        LinearLayout c = page.content;
        c.removeAllViews();
        TextView t = ui.text(16, ui.text, Ui.REGULAR);
        t.setText(R.string.scan_intro);
        t.setLineSpacing(0, 1.3f);
        c.addView(t, Page.full(ui.dp(8), ui.dp(20)));
        LinearLayout row = new LinearLayout(this);
        LinearLayout cam = ui.actionTile(Icons.Kind.CAMERA, getString(R.string.take_photo));
        cam.setOnClickListener(v -> camera());
        row.addView(cam, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout pick = ui.actionTile(Icons.Kind.FOLDER, getString(R.string.choose_picture));
        pick.setOnClickListener(v -> pick());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = ui.dp(10);
        row.addView(pick, lp);
        c.addView(row, Page.full());
    }

    @SuppressWarnings("deprecation")
    private void camera() {
        Intent it = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        it.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, PhotoProvider.URI);
        it.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        it.setClipData(ClipData.newRawUri("photo", PhotoProvider.URI));
        try {
            startActivityForResult(it, CAMERA);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.no_camera, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void pick() {
        Intent it = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(it, PICK);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK) return;
        Uri uri = request == CAMERA ? Uri.fromFile(PhotoProvider.file(this)) : data != null ? data.getData() : null;
        if (uri != null) read(uri);
    }

    private void read(Uri uri) {
        loading();
        Net.run(() -> load(uri), (bmp, err) -> {
            if (isDestroyed()) return;
            if (bmp == null) {
                Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_LONG).show();
                start();
                return;
            }
            photo = bmp;
            recognise(bmp);
        });
    }

    private void loading() {
        page.content.removeAllViews();
        ProgressBar p = new ProgressBar(this);
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = ui.dp(48);
        page.content.addView(p, lp);
        TextView t = ui.text(15, ui.sub, Ui.REGULAR);
        t.setText(R.string.scan_reading);
        t.setGravity(Gravity.CENTER);
        page.content.addView(t, Page.full(ui.dp(12), 0));
    }

    /** The picture, at most 2000 px, turned upright. */
    private Bitmap load(Uri uri) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = open(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 2000) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap b;
        try (InputStream in = open(uri)) {
            b = BitmapFactory.decodeStream(in, null, d);
        }
        if (b == null) return null;
        int deg = 0;
        try (InputStream in = open(uri)) {
            int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            deg = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (IOException | RuntimeException ignored) {
            // no rotation information
        }
        if (deg != 0) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        }
        return b;
    }

    private InputStream open(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("cannot open " + uri);
        return in;
    }

    private void recognise(Bitmap bmp) {
        TextRecognizer r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        r.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(text -> {
                    words.clear();
                    StringBuilder all = new StringBuilder();
                    for (com.google.mlkit.vision.text.Text.TextBlock block : text.getTextBlocks()) {
                        for (com.google.mlkit.vision.text.Text.Line line : block.getLines()) {
                            if (all.length() > 0) all.append('\n');
                            all.append(line.getText());
                            for (com.google.mlkit.vision.text.Text.Element e : line.getElements()) {
                                String w = e.getText().replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
                                if (!w.isEmpty() && e.getBoundingBox() != null) words.add(new Word(w, e.getBoundingBox()));
                            }
                        }
                    }
                    allText = all.toString();
                    r.close();
                    if (!isDestroyed()) showPhoto();
                })
                .addOnFailureListener(e -> {
                    r.close();
                    if (isDestroyed()) return;
                    Toast.makeText(this, R.string.scan_failed, Toast.LENGTH_LONG).show();
                    start();
                });
    }

    private void showPhoto() {
        LinearLayout c = page.content;
        c.removeAllViews();
        TextView hint = ui.text(14, ui.sub, Ui.REGULAR);
        hint.setText(words.isEmpty() ? getString(R.string.scan_no_text) : getString(R.string.scan_tap_word));
        c.addView(hint, Page.full(ui.dp(4), ui.dp(8)));
        PhotoView pv = new PhotoView(this);
        c.addView(pv, Page.full());
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        c.addView(panel, Page.full(ui.dp(12), 0));
        LinearLayout row = new LinearLayout(this);
        TextView again = ui.pill(getString(R.string.scan_again), false);
        again.setOnClickListener(v -> start());
        row.addView(again, new LinearLayout.LayoutParams(0, ui.dp(46), 1));
        if (!allText.isEmpty()) {
            TextView copy = ui.pill(getString(R.string.copy_all_text), false);
            copy.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", allText));
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ui.dp(46), 1);
            lp.leftMargin = ui.dp(10);
            row.addView(copy, lp);
        }
        c.addView(row, Page.full(ui.dp(12), 0));
    }

    /** Translates a tapped word in the panel under the photo. */
    private void translate(String w) {
        int gen = ++generation;
        panel.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(ui.surface, 18));
        card.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(14));
        TextView word = ui.text(20, ui.text, Ui.MEDIUM);
        word.setText(w);
        Ui.bidi(word);
        card.addView(word);
        TextView res = ui.text(15, ui.sub, Ui.REGULAR);
        res.setText(R.string.loading);
        res.setLineSpacing(0, 1.25f);
        card.addView(res, Page.full(ui.dp(6), 0));
        panel.addView(card, Page.full());
        Lang guess = Lexicon.guessLatin(w);
        Net.run(() -> engine.translate(w, guess, store.service(), System.currentTimeMillis()), (l, err) -> {
            if (gen != generation || isDestroyed()) return;
            if (l == null) {
                res.setText(R.string.error_service);
                return;
            }
            StringBuilder b = new StringBuilder();
            for (Lookup.Result r : l.results) {
                if (b.length() > 0) b.append('\n');
                b.append(r.lang.label).append(": ").append(r.summary(3));
            }
            res.setText(b);
            Ui.bidi(res);
            TextView open = ui.pill(getString(R.string.open_word), true);
            open.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_TEXT, w)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(10);
            card.addView(open, lp);
        });
    }

    /** The photo with the recognised words outlined; tap a word to translate it. */
    private final class PhotoView extends View {
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG), sel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Word selected;
        private float k = 1;

        PhotoView(android.content.Context c) {
            super(c);
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(ui.dp(1.5f));
            box.setColor(0xAA2563EB);
            sel.setStyle(Paint.Style.FILL);
            sel.setColor(0x552563EB);
            setContentDescription(getString(R.string.scan_photo_desc, words.size()));
        }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            k = photo == null ? 1 : (float) w / photo.getWidth();
            setMeasuredDimension(w, photo == null ? 0 : Math.round(photo.getHeight() * k));
        }

        @Override protected void onDraw(Canvas c) {
            if (photo == null) return;
            c.save();
            c.scale(k, k);
            c.drawBitmap(photo, 0, 0, null);
            for (Word w : words) {
                r.set(w.box);
                r.inset(-4, -4);
                if (w == selected) c.drawRoundRect(r, 8, 8, sel);
                c.drawRoundRect(r, 8, 8, box);
            }
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (e.getAction() != MotionEvent.ACTION_UP) return super.onTouchEvent(e);
            float x = e.getX() / k, y = e.getY() / k;
            Word best = null;
            float bestD = Float.MAX_VALUE;
            for (Word w : words) {
                float cx = w.box.exactCenterX(), cy = w.box.exactCenterY();
                boolean inside = w.box.contains((int) x, (int) y);
                float d = inside ? 0 : (cx - x) * (cx - x) + (cy - y) * (cy - y);
                if (d < bestD) {
                    bestD = d;
                    best = w;
                }
            }
            if (best != null && bestD < (60 / k) * (60 / k) * 4) {
                selected = best;
                invalidate();
                performClick();
                translate(best.text);
            }
            return true;
        }

        @Override public boolean performClick() {
            return super.performClick();
        }
    }
}
