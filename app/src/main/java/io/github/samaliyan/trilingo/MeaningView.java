package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The "meaning" part of a result: a picture of the word, what it means in its own language (like a
 * monolingual dictionary), synonyms / opposites / word family, and pronunciation practice
 * (listen to a real recording, record yourself, compare).
 */
final class MeaningView {
    interface Host {
        boolean canSpeak(Lang l);

        void speak(String text, Lang l);

        /** Looks up a word tapped in the meaning (a synonym, a family word). */
        void openWord(String word);
    }

    private static final Map<String, Meaning> CACHE = new HashMap<>();

    private final Activity act;
    private final Ui ui;
    private final Host host;
    private final Voice voice;
    private int generation;

    MeaningView(Activity act, Ui ui, Host host, Voice voice) {
        this.act = act;
        this.ui = ui;
        this.host = host;
        this.voice = voice;
    }

    /** True when a meaning can be shown for this lookup (a word, not a sentence). */
    static boolean applies(Lookup l) {
        return MeaningSource.word(l) != null;
    }

    private static String key(Lookup l) {
        return l.source.code + "|" + Text.key(l.query);
    }

    /**
     * The picture and the meaning, filled in when they arrive. {@code pictureBox} receives the picture
     * (it may be placed elsewhere on the screen), {@code meaningBox} the meaning.
     */
    void fill(Lookup l, LinearLayout pictureBox, LinearLayout meaningBox) {
        int gen = ++generation;
        Meaning cached = CACHE.get(key(l));
        if (cached != null) {
            show(l, cached, pictureBox, meaningBox);
            return;
        }
        meaningBox.removeAllViews();
        meaningBox.addView(loadingRow());
        Net.run(() -> MeaningSource.load(l, Net::get, Lexicon::germanCase, Lexicon.words(Lang.EN)), (m, err) -> {
            if (gen != generation || act.isDestroyed()) return;
            if (m == null) {
                meaningBox.removeAllViews();
                meaningBox.addView(note(act.getString(R.string.meaning_failed), true, () -> fill(l, pictureBox, meaningBox)));
                return;
            }
            CACHE.put(key(l), m);
            show(l, m, pictureBox, meaningBox);
        });
    }

    private void show(Lookup l, Meaning m, LinearLayout pictureBox, LinearLayout meaningBox) {
        pictureBox.removeAllViews();
        meaningBox.removeAllViews();
        if (!m.image.isEmpty()) picture(m, pictureBox);
        meaningBox.addView(meaningCard(l, m));
    }

    // ------------------------------------------------------------------ picture

    private void picture(Meaning m, LinearLayout box) {
        String url = Meaning.fileUrl(m.image, 400);
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(ui.surface, 18));
        card.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(8));
        ImageView img = new ImageView(act);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setAdjustViewBounds(false);
        img.setBackground(ui.round(0xFFFFFFFF, 12));
        img.setClipToOutline(true);
        img.setContentDescription(act.getString(R.string.picture_of, m.word));
        card.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(170)));
        if (!m.imageCaption.isEmpty()) {
            TextView cap = ui.text(13, ui.sub, Ui.REGULAR);
            cap.setText(m.imageCaption);
            cap.setGravity(Gravity.CENTER);
            cap.setPadding(0, ui.dp(6), 0, 0);
            card.addView(cap);
        }
        TextView credit = ui.text(11, ui.sub, Ui.REGULAR);
        credit.setText(act.getString(R.string.picture_credit, m.imageSource.isEmpty() ? "Wikimedia" : m.imageSource));
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0, ui.dp(2), 0, 0);
        card.addView(credit);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(12);
        card.setVisibility(View.GONE);
        box.addView(card, lp);
        Bitmap cached = Net.cachedBitmap(url);
        if (cached != null) {
            img.setImageBitmap(cached);
            card.setVisibility(View.VISIBLE);
        } else {
            Net.run(() -> Net.bitmap(url, 700), (b, err) -> {
                if (b == null || act.isDestroyed()) return;            // no picture: nothing is shown
                img.setImageBitmap(b);
                card.setVisibility(View.VISIBLE);
            });
        }
        img.setOnClickListener(v -> zoom(url));
    }

    private void zoom(String url) {
        Bitmap b = Net.cachedBitmap(url);
        if (b == null) return;
        Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView big = new ImageView(act);
        big.setImageBitmap(b);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        big.setBackgroundColor(0xFF000000);
        big.setContentDescription(act.getString(R.string.close));
        big.setOnClickListener(v -> d.dismiss());
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(big);
        d.show();
    }

    // ------------------------------------------------------------------ meaning

    private View meaningCard(Lookup l, Meaning m) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(ui.surface, 18));
        card.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = ui.dp(12);
        card.setLayoutParams(clp);

        TextView head = ui.line(13, ui.accent, Ui.MEDIUM);
        head.setText(act.getString(R.string.meaning_in, l.source.label).toUpperCase(java.util.Locale.ROOT));
        head.setLetterSpacing(0.06f);
        card.addView(head);

        TextView word = ui.text(22, ui.text, Ui.MEDIUM);
        word.setText(m.word);
        GrammarViews.direction(word, l.source);
        LinearLayout.LayoutParams wp = full();
        wp.topMargin = ui.dp(6);
        card.addView(word, wp);
        if (!m.ipa.isEmpty()) {
            TextView ipa = ui.text(15, ui.sub, Ui.REGULAR);
            ipa.setText(m.ipa);
            GrammarViews.direction(ipa, Lang.EN);
            card.addView(ipa, full());
        }
        card.addView(pronunciation(l, m));

        if (m.senses.isEmpty()) {
            TextView none = ui.text(15, ui.sub, Ui.REGULAR);
            none.setText(l.source == Lang.FA ? R.string.meaning_none_fa : R.string.meaning_none);
            none.setLineSpacing(0, 1.25f);
            LinearLayout.LayoutParams np = full();
            np.topMargin = ui.dp(10);
            card.addView(none, np);
            return card;
        }

        LinearLayout senses = new LinearLayout(act);
        senses.setOrientation(LinearLayout.VERTICAL);
        card.addView(senses, full());
        renderSenses(senses, l.source, m, false);

        chips(card, R.string.synonyms, m.synonyms, l.source);
        chips(card, R.string.opposites, m.antonyms, l.source);
        chips(card, R.string.word_family, m.family, l.source);

        TextView src = ui.text(11, ui.sub, Ui.REGULAR);
        src.setText(l.source == Lang.EN ? R.string.meaning_source_en : R.string.meaning_source_wiktionary);
        LinearLayout.LayoutParams sp = full();
        sp.topMargin = ui.dp(12);
        card.addView(src, sp);
        return card;
    }

    private void renderSenses(LinearLayout box, Lang lang, Meaning m, boolean all) {
        box.removeAllViews();
        int hidden = 0;
        for (Map.Entry<String, List<Meaning.Sense>> g : m.byPos().entrySet()) {
            TextView pos = ui.line(13, ui.accent, Ui.MEDIUM);
            pos.setText(g.getKey());
            GrammarViews.direction(pos, Lang.EN);
            LinearLayout.LayoutParams pp = full();
            pp.topMargin = ui.dp(14);
            box.addView(pos, pp);
            int n = 0;
            for (Meaning.Sense s : g.getValue()) {
                if (!all && n >= 2) {
                    hidden++;
                    continue;
                }
                n++;
                TextView t = ui.text(16, ui.text, Ui.REGULAR);
                GrammarViews.show(t, n + ". " + s.text, lang);
                t.setLineSpacing(0, 1.25f);
                LinearLayout.LayoutParams tp = full();
                tp.topMargin = ui.dp(6);
                box.addView(t, tp);
                if (!s.example.isEmpty()) {
                    TextView ex = ui.text(14, ui.sub, Ui.REGULAR);
                    ex.setTypeface(Typeface.create(Ui.REGULAR, Typeface.ITALIC));
                    GrammarViews.show(ex, s.example, lang);
                    ex.setLineSpacing(0, 1.2f);
                    ex.setPadding(lang == Lang.FA ? 0 : ui.dp(14), ui.dp(2), lang == Lang.FA ? ui.dp(14) : 0, 0);
                    box.addView(ex, full());
                }
            }
        }
        if (hidden > 0) {
            TextView more = ui.pill(act.getResources().getQuantityString(R.plurals.more_meanings, hidden, hidden), false);
            more.setOnClickListener(v -> renderSenses(box, lang, m, true));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mp.topMargin = ui.dp(12);
            box.addView(more, mp);
        }
    }

    /** Listen (real recording or the phone's voice) · Record yourself · Play your recording. */
    private View pronunciation(Lookup l, Meaning m) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = full();
        rp.topMargin = ui.dp(10);
        row.setLayoutParams(rp);
        voice.clear();
        boolean real = !m.audio.isEmpty();
        boolean any = real || host.canSpeak(l.source);
        LinearLayout listen = ui.actionTile(Icons.Kind.SPEAK, act.getString(R.string.listen));
        listen.setContentDescription(act.getString(real ? R.string.listen_real : R.string.listen_tts, l.source.label));
        listen.setOnClickListener(v -> {
            if (real) voice.play(m.audio.get(0));
            else host.speak(m.word, l.source);
        });
        listen.setEnabled(any);
        listen.setAlpha(any ? 1f : 0.4f);
        row.addView(listen, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout rec = ui.actionTile(Icons.Kind.MIC, act.getString(R.string.record));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = ui.dp(8);
        row.addView(rec, lp);
        LinearLayout mine = ui.actionTile(Icons.Kind.PLAY, act.getString(R.string.my_voice));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        mp.leftMargin = ui.dp(8);
        row.addView(mine, mp);
        Runnable update = () -> {
            boolean on = voice.isRecording();
            ((TextView) rec.getChildAt(1)).setText(on ? R.string.stop : R.string.record);
            rec.setContentDescription(act.getString(on ? R.string.stop_recording : R.string.record_desc));
            mine.setEnabled(voice.hasRecording());
            mine.setAlpha(voice.hasRecording() ? 1f : 0.4f);
        };
        voice.setListener(update::run);
        update.run();
        rec.setOnClickListener(v -> voice.toggleRecording());
        mine.setOnClickListener(v -> voice.playMine());
        mine.setContentDescription(act.getString(R.string.my_voice_desc));
        return row;
    }

    /** "Synonyms: a, b, c" — each word can be tapped to look it up. */
    private void chips(LinearLayout card, int label, List<String> words, Lang lang) {
        if (words.isEmpty()) return;
        TextView h = ui.line(13, ui.accent, Ui.MEDIUM);
        h.setText(label);
        LinearLayout.LayoutParams hp = full();
        hp.topMargin = ui.dp(14);
        card.addView(h, hp);
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (String w : words) {
            if (b.length() > 0) b.append(lang == Lang.FA ? "، " : ",  ");
            int start = b.length();
            b.append(w);
            b.setSpan(new ClickableSpan() {
                @Override public void onClick(View v) {
                    host.openWord(w);
                }

                @Override public void updateDrawState(TextPaint ds) {
                    ds.setColor(ui.accent);
                    ds.setUnderlineText(false);
                }
            }, start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView t = ui.text(16, ui.text, Ui.REGULAR);
        t.setText(b);
        t.setLineSpacing(0, 1.35f);
        t.setMovementMethod(LinkMovementMethod.getInstance());
        GrammarViews.direction(t, lang);
        LinearLayout.LayoutParams tp = full();
        tp.topMargin = ui.dp(4);
        card.addView(t, tp);
    }

    private View loadingRow() {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ui.round(ui.surface, 18));
        row.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
        ProgressBar p = new ProgressBar(act);
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        row.addView(p, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        TextView t = ui.text(15, ui.sub, Ui.REGULAR);
        t.setText(R.string.meaning_loading);
        t.setPadding(ui.dp(12), 0, 0, 0);
        row.addView(t);
        LinearLayout.LayoutParams lp = full();
        lp.bottomMargin = ui.dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    private View note(String text, boolean retry, Runnable again) {
        LinearLayout row = new LinearLayout(act);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ui.round(ui.surface, 18));
        row.setPadding(ui.dp(18), ui.dp(10), ui.dp(10), ui.dp(10));
        TextView t = ui.text(15, ui.sub, Ui.REGULAR);
        t.setText(text);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (retry) {
            TextView b = ui.pill(act.getString(R.string.retry), false);
            b.setOnClickListener(v -> again.run());
            row.addView(b);
        }
        LinearLayout.LayoutParams lp = full();
        lp.bottomMargin = ui.dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    private LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
