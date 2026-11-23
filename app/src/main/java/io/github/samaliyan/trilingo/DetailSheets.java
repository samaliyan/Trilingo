package io.github.samaliyan.trilingo;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The "Examples" and "Conjugation" panels of the main screen. */
final class DetailSheets {
    /** What the panels need from the screen that opens them. */
    interface Host {
        boolean canSpeak(Lang l);

        void speak(String text, Lang l);

        Engine engine();

        int service();
    }

    private final Activity act;
    private final Ui ui;
    private final Host host;
    /** Examples already loaded in this session, by language and word. */
    private final Map<String, List<Examples.Example>> cache = new HashMap<>();

    DetailSheets(Activity act, Ui ui, Host host) {
        this.act = act;
        this.ui = ui;
        this.host = host;
    }

    // ------------------------------------------------------------------ Examples

    void showExamples(String word, Lang src) {
        Sheet sheet = new Sheet(act, ui, act.getString(R.string.examples), word);
        sheet.show();
        loadExamples(sheet, word, src);
    }

    private void loadExamples(Sheet sheet, String word, Lang src) {
        String key = src.code + "|" + Text.key(word);
        List<Examples.Example> cached = cache.get(key);
        if (cached != null) {
            renderExamples(sheet, word, src, cached);
            return;
        }
        sheet.loading(act.getString(R.string.examples_loading));
        new Thread(() -> {
            List<Examples.Example> list = null;
            boolean offline = false;
            try {
                String body = Engine.NET.get(Examples.url(word, src), Engine.TIMEOUT);
                list = Examples.parse(body, src, Examples.MAX);
                list = fillMissing(list, src);
            } catch (IOException e) {
                offline = e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException;
            }
            final List<Examples.Example> res = list;
            final boolean off = offline;
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed() || !sheet.isShowing()) return;
                if (res == null) {
                    sheet.message(act.getString(off ? R.string.error_offline : R.string.examples_failed), null,
                            act.getString(R.string.retry), v -> loadExamples(sheet, word, src));
                    return;
                }
                cache.put(key, res);
                renderExamples(sheet, word, src, res);
            });
        }, "examples").start();
    }

    /** Some sentences have no human translation into one of the languages: the translation service fills it in. */
    private List<Examples.Example> fillMissing(List<Examples.Example> list, Lang src) {
        List<Examples.Example> out = new ArrayList<>();
        for (Examples.Example e : list) {
            Examples.Example x = e;
            boolean missing = false;
            for (Lang l : src.targets()) missing |= !e.translations.containsKey(l);
            if (missing) {
                try {
                    Lookup t = host.engine().translate(e.text, src, host.service(), System.currentTimeMillis());
                    for (Lang l : src.targets()) {
                        Lookup.Result r = t.result(l);
                        if (!x.translations.containsKey(l) && r != null) x = x.with(l, r.text);
                    }
                } catch (Engine.Failure ignored) {
                    // show the sentence with the translations it has
                }
            }
            out.add(x);
        }
        return out;
    }

    private void renderExamples(Sheet sheet, String word, Lang src, List<Examples.Example> list) {
        if (list.isEmpty()) {
            sheet.message(act.getString(R.string.examples_none), act.getString(R.string.examples_none_hint), null, null);
            return;
        }
        sheet.body.removeAllViews();
        for (Examples.Example e : list) {
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(ui.round(ui.surface, 18));
            card.setPadding(ui.dp(18), ui.dp(14), ui.dp(8), ui.dp(16));

            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.TOP);
            TextView s = ui.text(17, ui.text, Ui.REGULAR);
            s.setText(highlight(e.text, word));
            s.setLineSpacing(0, 1.2f);
            s.setTextIsSelectable(true);
            Ui.bidi(s);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            sp.topMargin = ui.dp(6);
            row.addView(s, sp);
            if (host.canSpeak(src)) {
                ImageButton say = ui.iconButton(Icons.Kind.SPEAK, ui.sub, act.getString(R.string.speak_lang, src.label));
                say.setOnClickListener(v -> host.speak(e.text, src));
                row.addView(say, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
            } else {
                row.setPadding(0, 0, ui.dp(10), 0);
            }
            card.addView(row);

            for (Lang l : src.targets()) {
                String t = e.translations.get(l);
                if (t == null) continue;
                TextView tag = ui.line(11, ui.accent, Ui.MEDIUM);
                tag.setText(l.label.toUpperCase(Locale.ROOT));
                tag.setLetterSpacing(0.08f);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tp.topMargin = ui.dp(12);
                tp.rightMargin = ui.dp(10);
                card.addView(tag, tp);
                TextView tv = ui.text(15, ui.sub, Ui.REGULAR);
                tv.setText(t);
                tv.setLineSpacing(0, 1.2f);
                tv.setTextIsSelectable(true);
                Ui.bidi(tv);
                LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vp.topMargin = ui.dp(2);
                vp.rightMargin = ui.dp(10);
                card.addView(tv, vp);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = ui.dp(12);
            sheet.body.addView(card, cp);
        }
        TextView src2 = ui.text(12, ui.sub, Ui.REGULAR);
        src2.setText(R.string.examples_source);
        src2.setGravity(Gravity.CENTER);
        sheet.body.addView(src2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private CharSequence highlight(String text, String word) {
        SpannableString sp = new SpannableString(text);
        for (int[] r : Examples.find(text, word)) {
            if (r[1] > text.length()) continue;
            sp.setSpan(new StyleSpan(Typeface.BOLD), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new ForegroundColorSpan(ui.accent), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    // ------------------------------------------------------------------ Conjugation

    void showConjugation(Map<Lang, String> verbs, Lang first) {
        List<Conjugation> tables = new ArrayList<>();
        for (Map.Entry<Lang, String> e : verbs.entrySet()) {
            Conjugation c = Conjugation.of(e.getKey(), e.getValue());
            if (c != null) tables.add(c);
        }
        if (tables.isEmpty()) return;
        Sheet sheet = new Sheet(act, ui, act.getString(R.string.conjugation), null);

        LinearLayout tabs = new LinearLayout(act);
        tabs.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        List<View> tabViews = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < tables.size(); i++) if (tables.get(i).lang == first) start = i;
        for (int i = 0; i < tables.size(); i++) {
            Conjugation c = tables.get(i);
            LinearLayout tab = new LinearLayout(act);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
            tab.setMinimumHeight(ui.dp(60));
            TextView lang = ui.line(11, ui.sub, Ui.MEDIUM);
            lang.setText(c.lang.label.toUpperCase(Locale.ROOT));
            lang.setLetterSpacing(0.08f);
            lang.setGravity(Gravity.CENTER);
            TextView verb = ui.line(16, ui.text, Ui.MEDIUM);
            verb.setText(c.verb);
            verb.setGravity(Gravity.CENTER);
            tab.addView(lang);
            tab.addView(verb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tab.setContentDescription(act.getString(R.string.conjugation_tab, c.lang.label, c.verb));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = ui.dp(8);
            tabs.addView(tab, lp);
            tabViews.add(tab);
            final int index = i;
            tab.setOnClickListener(v -> selectTab(sheet, tables, tabViews, index));
        }
        if (tables.size() > 1) sheet.top.addView(tabs);
        selectTab(sheet, tables, tabViews, start);
        sheet.show();
    }

    private void selectTab(Sheet sheet, List<Conjugation> tables, List<View> tabs, int index) {
        for (int i = 0; i < tabs.size(); i++) {
            boolean on = i == index;
            View t = tabs.get(i);
            t.setBackground(ui.ripple(on ? ui.outline(ui.accentSoft, ui.accent, 16) : ui.outline(ui.surface, ui.line, 16), 16));
            t.setSelected(on);
            TextView lang = (TextView) ((LinearLayout) t).getChildAt(0);
            lang.setTextColor(on ? ui.accent : ui.sub);
        }
        renderTable(sheet, tables.get(index), tables.size() == 1);
        sheet.scroll.scrollTo(0, 0);
    }

    /** @param single only one language: there are no tabs, so the verb is shown above the table */
    private void renderTable(Sheet sheet, Conjugation c, boolean single) {
        sheet.body.removeAllViews();
        boolean rtl = c.lang.rtl;

        if (single) {
            TextView v = ui.line(18, ui.text, Ui.MEDIUM);
            v.setText(c.verb);
            Ui.bidi(v);
            LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vp.leftMargin = ui.dp(6);
            vp.rightMargin = ui.dp(6);
            vp.bottomMargin = ui.dp(10);
            sheet.body.addView(v, vp);
        }

        // facts: type, auxiliary, participle / stems
        LinearLayout facts = new LinearLayout(act);
        facts.setOrientation(LinearLayout.VERTICAL);
        facts.setBackground(ui.round(ui.surface, 16));
        facts.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10));
        for (Conjugation.Info i : c.info) {
            LinearLayout row = new LinearLayout(act);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(ui.dp(30));
            TextView l = ui.line(13, ui.sub, Ui.REGULAR);
            l.setText(i.label);
            row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView v = ui.line(15, ui.text, Ui.MEDIUM);
            v.setText(i.value);
            v.setGravity(Gravity.END);
            row.addView(v);
            facts.addView(row);
        }
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.bottomMargin = ui.dp(12);
        sheet.body.addView(facts, fp);

        for (Conjugation.Tense t : c.tenses) {
            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(ui.round(ui.surface, 16));
            card.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(14));

            LinearLayout head = new LinearLayout(act);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = ui.line(15, ui.accent, Ui.MEDIUM);
            name.setText(t.name);
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (!t.nativeName.equals(t.name)) {
                TextView nat = ui.line(13, ui.sub, Ui.REGULAR);
                nat.setText(t.nativeName);
                head.addView(nat);
            }
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.bottomMargin = ui.dp(6);
            card.addView(head, hp);

            for (int i = 0; i < t.forms.length; i++) {
                LinearLayout row = new LinearLayout(act);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(ui.dp(32));
                TextView person = ui.line(14, ui.sub, Ui.REGULAR);
                person.setText(t.persons[i]);
                TextView form = ui.text(16, ui.text, Ui.MEDIUM);
                form.setText(t.forms[i]);
                form.setTextIsSelectable(true);
                if (rtl) {
                    // Persian: person on the right, the form to its left, both right-aligned
                    form.setGravity(Gravity.END);
                    Ui.bidi(form);
                    row.addView(form, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    person.setGravity(Gravity.END);
                    row.addView(person, new LinearLayout.LayoutParams(ui.dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
                } else {
                    row.addView(person, new LinearLayout.LayoutParams(ui.dp(c.lang == Lang.DE ? 84 : 76), ViewGroup.LayoutParams.WRAP_CONTENT));
                    row.addView(form, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                }
                card.addView(row);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = ui.dp(12);
            sheet.body.addView(card, cp);
        }
    }
}
