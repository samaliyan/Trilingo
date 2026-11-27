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
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
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
 * Scan text: take a photo (or choose a picture, or share a screenshot) of a sign, a page or a menu. The text
 * is read on the phone, without internet. Then:
 * <ul>
 * <li>tap a word: its translation, read it aloud, save it;</li>
 * <li>drag over the photo: the lines in that area, translated, copied or read aloud;</li>
 * <li>the whole text below the photo, to correct a misread word, translate, copy, read aloud, or find the
 * new words in it;</li>
 * <li>words above your level are marked in orange, saved words in green; turn a sideways photo.</li>
 * </ul>
 */
public final class ScanActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int CAMERA = 71, PICK = 72;
    /** Opens the camera at once (from the widget). */
    static final String EXTRA_CAMERA = "camera";

    private Ui ui;
    private Page page;
    private Engine engine;
    private Store store;
    private Notebook nb;
    private Bitmap photo;
    private final List<ScanText.Item> words = new ArrayList<>();
    private String allText = "";
    private Lang textLang;
    private LinearLayout panel, translations;
    private EditText textBox;
    private PhotoView photoView;
    private int generation;
    private TextToSpeech tts;
    private boolean ttsReady;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui = Ui.of(this);
        ui.applyWindow(this);
        store = new Store(this);
        nb = Store.notebook(this);
        engine = new Engine(Engine.NET);
        page = new Page(this, ui, getString(R.string.scan_text));
        Lexicon.load(this, null);
        try {
            tts = new TextToSpeech(getApplicationContext(), this);
        } catch (RuntimeException e) {
            tts = null;
        }
        Uri shared = sharedImage(getIntent());
        if (saved == null && shared != null) read(shared);
        else {
            start();
            if (saved == null && getIntent().getBooleanExtra(EXTRA_CAMERA, false)) camera();
        }
    }

    @Override public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
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
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
                // gone
            }
        }
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
        TextView note = ui.text(13, ui.sub, Ui.REGULAR);
        note.setText(R.string.scan_languages_note);
        note.setLineSpacing(0, 1.25f);
        c.addView(note, Page.full(ui.dp(16), 0));
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
        try {
            com.google.mlkit.common.MlKit.initialize(getApplicationContext());
        } catch (IllegalStateException alreadyStarted) {
            // started before in this process
        }
        TextRecognizer r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        r.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(text -> {
                    words.clear();
                    StringBuilder all = new StringBuilder();
                    int line = 0;
                    for (com.google.mlkit.vision.text.Text.TextBlock block : text.getTextBlocks()) {
                        for (com.google.mlkit.vision.text.Text.Line ln : block.getLines()) {
                            if (all.length() > 0) all.append('\n');
                            all.append(ln.getText());
                            for (com.google.mlkit.vision.text.Text.Element e : ln.getElements()) {
                                String w = e.getText().replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
                                android.graphics.Rect b = e.getBoundingBox();
                                if (!w.isEmpty() && b != null) words.add(new ScanText.Item(w, b.left, b.top, b.right, b.bottom, line));
                            }
                            line++;
                        }
                        all.append('\n');                  // a blank line between blocks (paragraphs)
                    }
                    allText = all.toString().trim();
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

    /** The language of the text read: English or German (the recogniser reads Latin letters). */
    private Lang language(String text) {
        Lang l = TextWords.detect(text, Lexicon.words(Lang.EN), Lexicon.words(Lang.DE));
        if (l == null) l = Lexicon.guessLatin(text);
        return l == null ? Lang.EN : l;
    }

    /** Turns the photo a quarter to the right and reads it again (for a sideways photo). */
    private void rotate() {
        if (photo == null) return;
        Matrix m = new Matrix();
        m.postRotate(90);
        photo = Bitmap.createBitmap(photo, 0, 0, photo.getWidth(), photo.getHeight(), m, true);
        loading();
        recognise(photo);
    }

    private void showPhoto() {
        textLang = language(allText);
        LinearLayout c = page.content;
        c.removeAllViews();
        TextView hint = ui.text(14, ui.sub, Ui.REGULAR);
        hint.setText(words.isEmpty() ? getString(R.string.scan_no_text) : getString(R.string.scan_tap_or_drag));
        hint.setLineSpacing(0, 1.2f);
        c.addView(hint, Page.full(ui.dp(4), ui.dp(8)));
        photoView = new PhotoView(this);
        c.addView(photoView, Page.full());
        if (!words.isEmpty()) {
            TextView legend = ui.text(12, ui.sub, Ui.REGULAR);
            legend.setText(getString(R.string.scan_legend, store.myLevel(textLang)));
            c.addView(legend, Page.full(ui.dp(6), 0));
        }

        // photo actions
        LinearLayout row = new LinearLayout(this);
        TextView again = ui.pill(getString(R.string.scan_again), false);
        again.setOnClickListener(v -> start());
        row.addView(again, new LinearLayout.LayoutParams(0, ui.dp(46), 1));
        TextView turn = ui.pill(getString(R.string.scan_rotate), false);
        turn.setOnClickListener(v -> rotate());
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ui.dp(46), 1);
        tl.leftMargin = ui.dp(10);
        row.addView(turn, tl);
        c.addView(row, Page.full(ui.dp(12), 0));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        c.addView(panel, Page.full(ui.dp(12), 0));
        if (allText.isEmpty()) return;

        // the whole text: correct it, translate it, copy it, hear it
        TextView h = ui.line(13, ui.accent, Ui.MEDIUM);
        h.setText(getString(R.string.scan_the_text, textLang.label));
        c.addView(h, Page.full(ui.dp(20), ui.dp(6)));
        textBox = new EditText(this);
        textBox.setText(allText);
        textBox.setTextColor(ui.text);
        textBox.setTextSize(16);
        textBox.setLineSpacing(0, 1.25f);
        textBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        textBox.setBackground(ui.outline(ui.surface, ui.line, 14));
        textBox.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        textBox.setGravity(Gravity.TOP | Gravity.START);
        textBox.setContentDescription(getString(R.string.scan_text_desc));
        Ui.bidi(textBox);
        c.addView(textBox, Page.full());
        TextView fix = ui.text(12, ui.sub, Ui.REGULAR);
        fix.setText(R.string.scan_fix_hint);
        c.addView(fix, Page.full(ui.dp(4), 0));

        LinearLayout tools = new LinearLayout(this);
        TextView tr = ui.pill(getString(R.string.scan_translate_text), true);
        tr.setOnClickListener(v -> translateText(text(), translations, true));
        tools.addView(tr, new LinearLayout.LayoutParams(0, ui.dp(46), 1));
        ImageButton say = ui.iconButton(Icons.Kind.SPEAK, ui.accent, getString(R.string.read_aloud));
        say.setOnClickListener(v -> speak(text(), language(text())));
        tools.addView(say, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(46)));
        ImageButton copy = ui.iconButton(Icons.Kind.COPY, ui.accent, getString(R.string.copy_all_text));
        copy.setOnClickListener(v -> copy(text()));
        tools.addView(copy, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(46)));
        c.addView(tools, Page.full(ui.dp(10), 0));
        translations = new LinearLayout(this);
        translations.setOrientation(LinearLayout.VERTICAL);
        c.addView(translations, Page.full(ui.dp(6), 0));

        if (TextWords.detect(allText, Lexicon.words(Lang.EN), Lexicon.words(Lang.DE)) != null) {
            TextView nw = ui.pill(getString(R.string.find_new_words), false);
            nw.setOnClickListener(v -> startActivity(new Intent(this, TextWordsActivity.class).putExtra(TextWordsActivity.EXTRA_TEXT, text())));
            c.addView(nw, Page.full(ui.dp(10), ui.dp(16)));
            nw.getLayoutParams().height = ui.dp(46);
        }
    }

    private String text() {
        return textBox == null ? allText : textBox.getText().toString().trim();
    }

    private void copy(String t) {
        if (t.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", t));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void speak(String t, Lang l) {
        if (t.isEmpty()) return;
        if (tts == null || !ttsReady) {
            Toast.makeText(this, R.string.speak_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            tts.setLanguage(MainActivity.locale(l));
            tts.speak(t.length() > 3900 ? t.substring(0, 3900) : t, TextToSpeech.QUEUE_FLUSH, null, "scan");
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.speak_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** Translates a text into the two other languages, shown as cards in {@code into}. */
    private void translateText(String t, LinearLayout into, boolean whole) {
        if (t.isEmpty() || into == null) return;
        int gen = ++generation;
        into.removeAllViews();
        TextView wait = ui.text(14, ui.sub, Ui.REGULAR);
        wait.setText(R.string.loading);
        into.addView(wait, Page.full(ui.dp(8), 0));
        String q = t.length() > 4500 ? t.substring(0, 4500) : t;
        Lang src = language(q);
        Net.run(() -> engine.translate(q, src, store.service(), System.currentTimeMillis()), (l, err) -> {
            // a tapped word does not cancel the whole text's translation, only an earlier area's
            if (!whole && gen != generation || isDestroyed()) return;
            into.removeAllViews();
            if (l == null) {
                TextView e = ui.text(14, ui.sub, Ui.REGULAR);
                e.setText(R.string.error_service);
                into.addView(e, Page.full(ui.dp(8), 0));
                return;
            }
            for (Lookup.Result r : l.results) into.addView(resultCard(r), Page.full(ui.dp(8), 0));
        });
    }

    private View resultCard(Lookup.Result r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(ui.surface, 16));
        card.setPadding(ui.dp(14), ui.dp(8), ui.dp(6), ui.dp(12));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lang = ui.line(12, ui.accent, Ui.MEDIUM);
        lang.setText(r.lang.label.toUpperCase(java.util.Locale.ROOT));
        head.addView(lang, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton say = ui.iconButton(Icons.Kind.SPEAK, ui.sub, getString(R.string.speak_lang, r.lang.label));
        say.setOnClickListener(v -> speak(r.text, r.lang));
        head.addView(say, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        ImageButton copy = ui.iconButton(Icons.Kind.COPY, ui.sub, getString(R.string.scan_copy));
        copy.setOnClickListener(v -> copy(r.text));
        head.addView(copy, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        card.addView(head);
        TextView t = ui.text(16, ui.text, Ui.REGULAR);
        t.setText(r.text);
        t.setTextIsSelectable(true);
        t.setLineSpacing(0, 1.25f);
        GrammarViews.direction(t, r.lang);
        card.addView(t, Page.full(0, 0));
        return card;
    }

    /** The lines chosen by dragging over the photo: their text, translated, with copy and read aloud. */
    private void showArea(String t) {
        panel.removeAllViews();
        if (t.isEmpty()) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(ui.accentSoft, 18));
        card.setPadding(ui.dp(16), ui.dp(8), ui.dp(6), ui.dp(12));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = ui.line(12, ui.accent, Ui.MEDIUM);
        label.setText(R.string.scan_chosen_text);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Lang l = language(t);
        ImageButton say = ui.iconButton(Icons.Kind.SPEAK, ui.sub, getString(R.string.read_aloud));
        say.setOnClickListener(v -> speak(t, l));
        head.addView(say, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        ImageButton copy = ui.iconButton(Icons.Kind.COPY, ui.sub, getString(R.string.scan_copy));
        copy.setOnClickListener(v -> copy(t));
        head.addView(copy, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        ImageButton close = ui.iconButton(Icons.Kind.CLOSE, ui.sub, getString(R.string.close));
        close.setOnClickListener(v -> {
            panel.removeAllViews();
            photoView.clearSelection();
        });
        head.addView(close, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        card.addView(head);
        TextView text = ui.text(16, ui.text, Ui.REGULAR);
        text.setText(t);
        text.setTextIsSelectable(true);
        text.setLineSpacing(0, 1.25f);
        card.addView(text, Page.full(0, ui.dp(4)));
        panel.addView(card, Page.full());
        LinearLayout out = new LinearLayout(this);
        out.setOrientation(LinearLayout.VERTICAL);
        panel.addView(out, Page.full());
        translateText(t, out, false);
    }

    /** Translates a tapped word in the panel under the photo: translations, read aloud, save, open. */
    private void translate(String w) {
        int gen = ++generation;
        panel.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(ui.surface, 18));
        card.setPadding(ui.dp(16), ui.dp(8), ui.dp(6), ui.dp(14));
        Lang guess = Lexicon.guessLatin(w);
        Lang src = guess != null ? guess : textLang != null ? textLang : Lang.EN;
        String word = src == Lang.DE ? Lexicon.germanCase(w) : w;
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = ui.text(20, ui.text, Ui.MEDIUM);
        title.setText(word);
        Ui.bidi(title);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton say = ui.iconButton(Icons.Kind.SPEAK, ui.sub, getString(R.string.speak_lang, src.label));
        say.setOnClickListener(v -> speak(word, src));
        head.addView(say, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        ImageButton star = ui.iconButton(nb.isSaved(src, word) ? Icons.Kind.STAR_ON : Icons.Kind.STAR, ui.accent, getString(R.string.save_word));
        star.setEnabled(false);
        head.addView(star, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        card.addView(head);
        TextView res = ui.text(15, ui.sub, Ui.REGULAR);
        res.setText(R.string.loading);
        res.setLineSpacing(0, 1.25f);
        card.addView(res, Page.full(ui.dp(2), 0));
        panel.addView(card, Page.full());
        Net.run(() -> engine.translate(word, src, store.service(), System.currentTimeMillis()), (l, err) -> {
            if (gen != generation || isDestroyed()) return;
            if (l == null) {
                Lookup d = Lexicon.dictionary(word, src, true, System.currentTimeMillis());
                if (d == null) {
                    res.setText(R.string.error_service);
                    return;
                }
                l = d;
            }
            final Lookup found = l;
            StringBuilder b = new StringBuilder();
            for (Lookup.Result r : found.results) {
                if (b.length() > 0) b.append('\n');
                b.append(r.lang.label).append(": ").append(r.summary(3));
            }
            res.setText(b);
            res.setTextColor(ui.text);
            Ui.bidi(res);
            star.setEnabled(true);
            star.setOnClickListener(v -> {
                boolean on = !nb.isSaved(found.source, found.query);
                if (on) nb.save(found.source, found.query, Notebook.summary(found), "", System.currentTimeMillis());
                else nb.unsave(found.source, found.query);
                star.setImageDrawable(Icons.of(on ? Icons.Kind.STAR_ON : Icons.Kind.STAR, ui.accent));
                Toast.makeText(this, on ? getString(R.string.scan_saved, found.query) : getString(R.string.removed_word, found.query),
                        Toast.LENGTH_SHORT).show();
                photoView.invalidate();
                WidgetProvider.updateAll(this);
            });
            TextView open = ui.pill(getString(R.string.open_word), false);
            open.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_TEXT, found.query)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = ui.dp(10);
            card.addView(open, lp);
        });
    }

    /** Orange: above the learner's level; green: saved; blue: the rest. */
    private int colorOf(ScanText.Item w) {
        Lang l = textLang == null ? Lang.EN : textLang;
        String word = l == Lang.DE ? Lexicon.germanCase(w.text) : w.text;
        if (nb.isSaved(l, word) || nb.isSaved(l, w.text)) return 0xFF16A34A;
        Levels lv = Lexicon.levels(l);
        String level = lv == null ? null : lv.of(w.text, l, Lexicon.nouns());
        if (ScanText.aboveLevel(level, store.myLevel(l))) return 0xFFEA580C;
        return 0xFF2563EB;
    }

    /**
     * The photo with the recognised words outlined. Tap a word to translate it; drag to choose an area
     * (its lines are shown, translated, under the photo).
     */
    private final class PhotoView extends View {
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
                area = new Paint(Paint.ANTI_ALIAS_FLAG), areaLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final java.util.Map<ScanText.Item, Integer> colors = new java.util.HashMap<>();
        private ScanText.Item selected;
        private float k = 1, downX, downY, nowX, nowY;
        private boolean dragging, hasArea;

        PhotoView(android.content.Context c) {
            super(c);
            box.setStyle(Paint.Style.STROKE);
            box.setStrokeWidth(ui.dp(1.5f));
            fill.setStyle(Paint.Style.FILL);
            area.setStyle(Paint.Style.FILL);
            area.setColor(0x332563EB);
            areaLine.setStyle(Paint.Style.STROKE);
            areaLine.setStrokeWidth(ui.dp(2));
            areaLine.setColor(0xFF2563EB);
            setContentDescription(getString(R.string.scan_photo_desc, words.size()));
            for (ScanText.Item w : words) colors.put(w, colorOf(w));
        }

        void clearSelection() {
            hasArea = false;
            selected = null;
            invalidate();
        }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            k = photo == null ? 1 : (float) w / photo.getWidth();
            setMeasuredDimension(w, photo == null ? 0 : Math.round(photo.getHeight() * k));
        }

        @Override public void invalidate() {
            if (colors != null) for (ScanText.Item w : words) colors.put(w, colorOf(w));
            super.invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (photo == null) return;
            c.save();
            c.scale(k, k);
            c.drawBitmap(photo, 0, 0, null);
            float sx = Math.min(downX, nowX) / k, ex = Math.max(downX, nowX) / k;
            float sy = Math.min(downY, nowY) / k, ey = Math.max(downY, nowY) / k;
            for (ScanText.Item w : words) {
                r.set(w.left, w.top, w.right, w.bottom);
                r.inset(-4, -4);
                Integer col = colors.get(w);
                int color = col == null ? 0xFF2563EB : col;
                box.setColor(0xCC000000 | (color & 0xFFFFFF));
                boolean chosen = w == selected || (hasArea || dragging) && ScanText.inside(w, sx, sy, ex, ey);
                if (chosen) {
                    fill.setColor(0x55000000 | (color & 0xFFFFFF));
                    c.drawRoundRect(r, 8, 8, fill);
                }
                c.drawRoundRect(r, 8, 8, box);
            }
            if (dragging || hasArea) {
                r.set(sx, sy, ex, ey);
                c.drawRect(r, area);
                areaLine.setStrokeWidth(ui.dp(2) / k);
                c.drawRect(r, areaLine);
            }
            c.restore();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = nowX = e.getX();
                    downY = nowY = e.getY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    nowX = e.getX();
                    nowY = e.getY();
                    if (!dragging && Math.hypot(nowX - downX, nowY - downY) > ui.dp(16)) {
                        dragging = true;
                        // the page must not scroll while an area is drawn
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        dragging = false;
                        hasArea = true;
                        selected = null;
                        invalidate();
                        String t = ScanText.textIn(words, Math.min(downX, nowX) / k, Math.min(downY, nowY) / k,
                                Math.max(downX, nowX) / k, Math.max(downY, nowY) / k);
                        showArea(t);
                        return true;
                    }
                    ScanText.Item best = ScanText.at(words, e.getX() / k, e.getY() / k, ui.dp(30) / k);
                    if (best != null) {
                        selected = best;
                        hasArea = false;
                        invalidate();
                        performClick();
                        translate(best.text);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        @Override public boolean performClick() {
            return super.performClick();
        }
    }
}
