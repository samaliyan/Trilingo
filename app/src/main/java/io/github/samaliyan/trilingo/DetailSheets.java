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
import java.util.concurrent.ConcurrentHashMap;
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

    // ------------------------------------------------------------------ tabs (shared)

    /** Row of equal tabs: a small language label over a word; {@code onSelect} gets the index. */
    private LinearLayout tabBar(List<Lang> langs, List<String> words, int selected, java.util.function.IntConsumer onSelect) {
        LinearLayout tabs = new LinearLayout(act);
        tabs.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
        for (int i = 0; i < langs.size(); i++) {
            boolean on = i == selected;
            LinearLayout tab = new LinearLayout(act);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8));
            tab.setMinimumHeight(ui.dp(60));
            tab.setBackground(ui.ripple(on ? ui.outline(ui.accentSoft, ui.accent, 16) : ui.outline(ui.surface, ui.line, 16), 16));
            tab.setSelected(on);
            TextView lang = ui.line(11, on ? ui.accent : ui.sub, Ui.MEDIUM);
            lang.setText(langs.get(i).label.toUpperCase(Locale.ROOT));
            lang.setLetterSpacing(0.08f);
            lang.setGravity(Gravity.CENTER);
            TextView word = ui.line(16, ui.text, Ui.MEDIUM);
            word.setText(words.get(i));
            word.setGravity(Gravity.CENTER);
            tab.addView(lang);
            tab.addView(word);
            tab.setContentDescription(act.getString(R.string.conjugation_tab, langs.get(i).label, words.get(i)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = ui.dp(8);
            final int index = i;
            tab.setOnClickListener(v -> onSelect.accept(index));
            tabs.addView(tab, lp);
        }
        return tabs;
    }

    // ------------------------------------------------------------------ Examples

    /** Words to show examples for: the typed word, and its English / German / Persian translation when short. */
    private static Map<Lang, String> exampleWords(Lookup l) {
        Map<Lang, String> m = new java.util.LinkedHashMap<>();
        if (Examples.suitable(l.query)) m.put(l.source, l.source == Lang.DE ? germanCase(l.query) : Text.clean(l.query));
        for (Lookup.Result r : l.results) {
            String w = Text.clean(r.text);
            if (r.lang == Lang.EN && w.toLowerCase(Locale.ROOT).startsWith("to ")) w = w.substring(3);
            Words enList = Lexicon.words(Lang.EN);
            if (r.lang == Lang.EN && enList != null && !w.contains(" ") && enList.known(w)) w = w.toLowerCase(Locale.ROOT);
            if (r.lang == Lang.DE) w = germanCase(w);
            if (Examples.suitable(w) && !m.containsKey(r.lang)) m.put(r.lang, w);
        }
        return m;
    }

    /**
     * German words as German writes them: Tatoeba only finds the exact form, so "tisch" finds nothing
     * but "Tisch" finds many sentences. Single words only; the word list and the noun list decide.
     */
    static String germanCase(String word) {
        return Lexicon.germanCase(word);
    }

    boolean hasExamples(Lookup l) {
        return !exampleWords(l).isEmpty();
    }

    /** What the examples in {@code tab} should mean: the lookup's words in the other languages. */
    private static Map<Lang, java.util.Set<String>> expectedFor(Lookup l, Lang tab) {
        Map<Lang, java.util.Set<String>> m = Examples.expected(l);
        java.util.Set<String> src = new java.util.HashSet<>();
        src.add(Text.clean(l.query).toLowerCase(Locale.ROOT));
        if (!l.baseForm.isEmpty()) src.add(l.baseForm.toLowerCase(Locale.ROOT));
        m.put(l.source, src);
        m.remove(tab);
        return m;
    }

    /**
     * Examples of the typed word and (in tabs) of its translations, so there are English sentences
     * for a German word too.
     */
    void showExamples(Lookup l) {
        Map<Lang, String> words = exampleWords(l);
        if (words.isEmpty()) return;
        List<Lang> langs = new ArrayList<>(words.keySet());
        List<String> ws = new ArrayList<>(words.values());
        Sheet sheet = new Sheet(act, ui, act.getString(R.string.examples), langs.size() == 1 ? ws.get(0) : null);
        selectExampleTab(sheet, l, langs, ws, 0);
        sheet.show();
    }

    private void selectExampleTab(Sheet sheet, Lookup l, List<Lang> langs, List<String> ws, int index) {
        sheet.top.removeAllViews();
        if (langs.size() > 1) sheet.top.addView(tabBar(langs, ws, index, i -> selectExampleTab(sheet, l, langs, ws, i)));
        sheet.scroll.scrollTo(0, 0);
        loadExamples(sheet, ws.get(index), langs.get(index), expectedFor(l, langs.get(index)));
    }

    private void loadExamples(Sheet sheet, String word, Lang src, Map<Lang, java.util.Set<String>> expected) {
        String key = src.code + "|" + Text.key(word);
        sheet.token = key;
        List<Examples.Example> cached = cache.get(key);
        if (cached != null) {
            renderExamples(sheet, word, src, cached);
            return;
        }
        sheet.loading(act.getString(R.string.examples_loading));
        new Thread(() -> {
            List<Examples.Example> list = null;
            boolean offline = false;
            // Best quality first (recorded sentences, then native speakers, then all); the next level is only
            // asked when there are not enough good sentences yet. One retry when the site is busy.
            List<Examples.Candidate> all = new ArrayList<>();
            int ok = 0;
            IOException last = null;
            for (String f : Examples.FILTERS) {
                String body = null;
                for (int attempt = 0; attempt < 2 && body == null; attempt++) {
                    try {
                        body = Engine.NET.get(Examples.url(word, src, f), Engine.TIMEOUT);
                    } catch (IOException e) {
                        last = e;
                        if (e instanceof java.net.UnknownHostException) break;
                        try {
                            Thread.sleep(1200);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (body == null) continue;
                try {
                    all.addAll(Examples.parse(body, src));
                    ok++;
                } catch (IOException e) {
                    last = e;
                }
                if (Examples.select(all, src, expected, Examples.MAX).size() >= Examples.MAX) break;
            }
            if (ok > 0) list = fillMissing(Examples.select(all, src, expected, Examples.MAX), src);
            else offline = last instanceof java.net.UnknownHostException || last instanceof java.net.ConnectException;
            final List<Examples.Example> res = list;
            final boolean off = offline;
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed() || !sheet.isShowing() || !key.equals(sheet.token)) return;
                if (res == null) {
                    sheet.message(act.getString(off ? R.string.error_offline : R.string.examples_failed), null,
                            act.getString(R.string.retry), v -> loadExamples(sheet, word, src, expected));
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
        renderExamples(sheet, java.util.Collections.singletonList(word), src, list, R.string.examples_none);
    }

    /** Sentences with their translations; the given words in bold. */
    private void renderExamples(Sheet sheet, List<String> words, Lang src, List<Examples.Example> list, int none) {
        if (list.isEmpty()) {
            sheet.message(act.getString(none), null, null, null);
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
            s.setText(highlight(e.text, words));
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

    private CharSequence highlight(String text, List<String> words) {
        SpannableString sp = new SpannableString(text);
        for (String word : words) for (int[] r : Examples.find(text, word)) {
            if (r[1] > text.length()) continue;
            sp.setSpan(new StyleSpan(Typeface.BOLD), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new ForegroundColorSpan(ui.accent), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    // ------------------------------------------------------------------ Phrasal / prefix verbs

    /** Translations of phrasal and prefix verbs already loaded in this session. */
    private final Map<String, Lookup> familyCache = new ConcurrentHashMap<>();

    /**
     * English phrasal verbs (give up, give in …) and German prefix verbs (ankommen, mitkommen …) of the
     * looked-up verb, in tabs. Each row shows the meaning and the Persian translation; tapping one
     * translates it on the main screen.
     */
    void showFamilies(Map<Lang, List<Phrasal.Entry>> families, Map<Lang, String> verbs, Lang source,
                      java.util.function.Consumer<String> open) {
        List<Lang> langs = new ArrayList<>(families.keySet());
        if (langs.isEmpty()) return;
        if (langs.contains(source)) {          // the typed language first
            langs.remove(source);
            langs.add(0, source);
        }
        List<String> base = new ArrayList<>();
        for (Lang l : langs) base.add(verbs.get(l));
        Sheet sheet = new Sheet(act, ui, act.getString(langs.contains(Lang.EN) ? R.string.phrasal_title : R.string.prefix_verbs),
                langs.size() == 1 ? base.get(0) : null);
        selectFamilyTab(sheet, families, langs, base, 0, open);
        sheet.show();
    }

    private void selectFamilyTab(Sheet sheet, Map<Lang, List<Phrasal.Entry>> families, List<Lang> langs, List<String> base,
                                 int index, java.util.function.Consumer<String> open) {
        sheet.top.removeAllViews();
        if (langs.size() > 1) sheet.top.addView(tabBar(langs, base, index, i -> selectFamilyTab(sheet, families, langs, base, i, open)));
        Lang lang = langs.get(index);
        List<Phrasal.Entry> list = families.get(lang);
        sheet.token = lang;
        sheet.body.removeAllViews();
        sheet.scroll.scrollTo(0, 0);

        TextView hint = ui.text(13, ui.sub, Ui.REGULAR);
        hint.setText(lang == Lang.EN ? R.string.family_hint_en : R.string.family_hint_de);
        hint.setLineSpacing(0, 1.2f);
        hint.setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(10));
        sheet.body.addView(hint);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(ui.round(ui.surface, 18));
        List<TextView[]> rows = new ArrayList<>();
        for (Phrasal.Entry e : list) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
            row.setMinimumHeight(ui.dp(60));
            row.setBackground(ui.ripple(null, 0));
            TextView verb = ui.line(17, ui.text, Ui.MEDIUM);
            verb.setText(e.verb);
            row.addView(verb);
            TextView meaning = ui.text(14, ui.sub, Ui.REGULAR);
            meaning.setText(e.meaning);
            meaning.setVisibility(e.meaning.isEmpty() ? View.GONE : View.VISIBLE);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mp.topMargin = ui.dp(2);
            row.addView(meaning, mp);
            TextView fa = ui.text(15, ui.text, Ui.REGULAR);
            Ui.bidi(fa);
            fa.setVisibility(View.GONE);
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fp.topMargin = ui.dp(2);
            row.addView(fa, fp);
            row.setContentDescription(e.verb);
            row.setOnClickListener(v -> {
                sheet.dialog.dismiss();
                open.accept(e.verb);
            });
            card.addView(row);
            rows.add(new TextView[]{meaning, fa});
        }
        sheet.body.addView(card);

        // Meanings: English for German prefix verbs, Persian for all; four at a time, filled in as they arrive.
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        for (int i = 0; i < list.size(); i++) {
            final Phrasal.Entry e = list.get(i);
            final TextView[] views = rows.get(i);
            final String key = lang.code + "|" + e.verb;
            Lookup cached = familyCache.get(key);
            if (cached != null) {
                fillFamilyRow(views, cached, lang);
                continue;
            }
            pool.submit(() -> {
                Lookup t;
                try {
                    t = host.engine().translate(e.verb, lang, host.service(), System.currentTimeMillis());
                } catch (Engine.Failure x) {
                    return;
                }
                familyCache.put(key, t);
                act.runOnUiThread(() -> {
                    if (act.isFinishing() || act.isDestroyed() || !sheet.isShowing() || sheet.token != lang) return;
                    fillFamilyRow(views, t, lang);
                });
            });
        }
        pool.shutdown();
    }

    private void fillFamilyRow(TextView[] views, Lookup t, Lang lang) {
        Lookup.Result fa = t.result(Lang.FA);
        if (fa != null && !fa.text.isEmpty()) {
            // Prefer the infinitives of the verb meanings (دریافت کردن), not the main translation, which for a
            // lone verb is often an imperative (دریافت کنید).
            List<String> inf = new ArrayList<>();
            for (Lookup.Sense sense : fa.senses) {
                if (!Verbs.isVerbPos(sense.pos)) continue;
                for (String term : sense.terms) {
                    String v = Verbs.infinitive(Lang.FA, term);
                    if (v != null && !inf.contains(v) && inf.size() < 2) inf.add(v);
                }
            }
            views[1].setText(inf.isEmpty() ? fa.summary(1) : String.join("\u060C ", inf));
            views[1].setVisibility(View.VISIBLE);
        }
        if (lang == Lang.DE && views[0].getText().length() == 0) {
            Lookup.Result en = t.result(Lang.EN);
            if (en != null && !en.text.isEmpty()) {
                views[0].setText(en.summary(2));
                views[0].setVisibility(View.VISIBLE);
            }
        }
    }

    // ------------------------------------------------------------------ Conjugation

    /** One open conjugation panel: its tables (one per language) and the selected one. */
    private final class ConjPanel {
        final Sheet sheet;
        final List<Conjugation> tables = new ArrayList<>();
        final LinearLayout tabs = new LinearLayout(act);
        int selected;

        ConjPanel(Sheet sheet) {
            this.sheet = sheet;
            tabs.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(8));
            sheet.top.addView(tabs);
        }

        /** Languages in the fixed order of the lookup (typed language first). */
        void add(Conjugation c, List<Lang> order) {
            for (Conjugation t : tables) if (t.lang == c.lang) return;
            Lang current = tables.isEmpty() ? null : tables.get(selected).lang;
            tables.add(c);
            tables.sort((x, y) -> order.indexOf(x.lang) - order.indexOf(y.lang));
            if (current != null) for (int i = 0; i < tables.size(); i++) if (tables.get(i).lang == current) selected = i;
        }

        void rebuildTabs() {
            tabs.removeAllViews();
            tabs.setVisibility(tables.size() > 1 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < tables.size(); i++) {
                Conjugation c = tables.get(i);
                boolean on = i == selected;
                LinearLayout tab = new LinearLayout(act);
                tab.setOrientation(LinearLayout.VERTICAL);
                tab.setGravity(Gravity.CENTER);
                tab.setPadding(ui.dp(6), ui.dp(8), ui.dp(6), ui.dp(8));
                tab.setMinimumHeight(ui.dp(60));
                tab.setBackground(ui.ripple(on ? ui.outline(ui.accentSoft, ui.accent, 16) : ui.outline(ui.surface, ui.line, 16), 16));
                tab.setSelected(on);
                TextView lang = ui.line(11, on ? ui.accent : ui.sub, Ui.MEDIUM);
                lang.setText(c.lang.label.toUpperCase(Locale.ROOT));
                lang.setLetterSpacing(0.08f);
                TextView verb = ui.line(16, ui.text, Ui.MEDIUM);
                verb.setText(c.verb);
                lang.setGravity(Gravity.CENTER);
                verb.setGravity(Gravity.CENTER);
                tab.addView(lang);
                tab.addView(verb);
                tab.setContentDescription(act.getString(R.string.conjugation_tab, c.lang.label, c.verb));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                if (i > 0) lp.leftMargin = ui.dp(8);
                tabs.addView(tab, lp);
                final int index = i;
                tab.setOnClickListener(v -> {
                    if (index == selected) return;
                    selected = index;
                    rebuildTabs();
                    renderTable(sheet, tables.get(selected), tables.size() == 1);
                    sheet.scroll.scrollTo(0, 0);
                });
            }
        }
    }

    /**
     * @param verbs  language → infinitive found in the lookup (typed language first)
     * @param source the typed word's language; its table is shown first
     * A language whose verb is not in the lookup (e.g. the Persian translation was a conjugated form)
     * is looked up from the typed verb's infinitive and added as a tab when it arrives.
     */
    void showConjugation(Map<Lang, String> verbs, Lang source) {
        List<Lang> order = new ArrayList<>();
        order.add(source);
        for (Lang l : source.targets()) order.add(l);
        Sheet sheet = new Sheet(act, ui, act.getString(R.string.conjugation), null);
        ConjPanel panel = new ConjPanel(sheet);
        for (Map.Entry<Lang, String> e : verbs.entrySet()) {
            Conjugation c = Conjugation.of(e.getKey(), e.getValue());
            if (c != null) panel.add(c, order);
        }
        if (panel.tables.isEmpty()) return;
        panel.selected = 0;
        panel.rebuildTabs();
        renderTable(sheet, panel.tables.get(0), panel.tables.size() == 1);
        sheet.show();

        if (panel.tables.size() >= 3) return;
        // A missing language (e.g. Persian, when the translation was the conjugated "دارند") is found by
        // translating the infinitive: "to have" from English is the most reliable, else the typed language's.
        final Lang fromLang;
        final String fromText;
        if (verbs.containsKey(Lang.EN)) {
            fromLang = Lang.EN;
            fromText = "to " + verbs.get(Lang.EN);
        } else if (verbs.containsKey(source)) {
            fromLang = source;
            fromText = verbs.get(source);
        } else {
            Map.Entry<Lang, String> e = verbs.entrySet().iterator().next();
            fromLang = e.getKey();
            fromText = e.getValue();
        }
        new Thread(() -> {
            Map<Lang, String> found = new java.util.LinkedHashMap<>();
            try {
                Lookup t = host.engine().translate(fromText, fromLang, host.service(), System.currentTimeMillis());
                for (Lookup.Result r : t.results) {
                    if (verbs.containsKey(r.lang)) continue;
                    String v = null;
                    for (Lookup.Sense sense : r.senses) {
                        if (!Verbs.isVerbPos(sense.pos)) continue;
                        for (String term : sense.terms) if (v == null) v = Verbs.infinitive(r.lang, term);
                    }
                    if (v == null) v = Verbs.infinitive(r.lang, r.text);
                    if (v != null) found.put(r.lang, v);
                }
            } catch (Engine.Failure e) {
                return;
            }
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed() || !sheet.isShowing()) return;
                int before = panel.tables.size();
                for (Map.Entry<Lang, String> e : found.entrySet()) {
                    Conjugation c = Conjugation.of(e.getKey(), e.getValue());
                    if (c != null) panel.add(c, order);
                }
                if (panel.tables.size() == before) return;
                panel.rebuildTabs();
                if (before == 1) renderTable(sheet, panel.tables.get(panel.selected), false);
            });
        }, "conjugation").start();
    }

    /** Persian text pinned to the right edge of its view, whatever the text-direction rules decide. */
    @android.annotation.SuppressLint("RtlHardcoded")      // deliberately right, also on left-to-right phones
    private static void rightAligned(TextView t) {
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
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
                    // Persian: person in a column on the right, its form right next to it (right-aligned)
                    rightAligned(form);
                    LinearLayout.LayoutParams fp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                    fp2.rightMargin = ui.dp(16);
                    row.addView(form, fp2);
                    rightAligned(person);
                    row.addView(person, new LinearLayout.LayoutParams(ui.dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
                } else {
                    row.addView(person, new LinearLayout.LayoutParams(ui.dp(c.lang == Lang.DE ? 84 : 76), ViewGroup.LayoutParams.WRAP_CONTENT));
                    row.addView(form, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                }
                card.addView(row);
            }
            // sentences that use this tense, from real texts
            if (!Examples.tenseQuery(t.forms).isEmpty()) {
                TextView ex = ui.pill(act.getString(R.string.tense_examples), false);
                ex.setContentDescription(act.getString(R.string.tense_examples_desc, t.name, c.verb));
                ex.setOnClickListener(v -> showTenseExamples(c, t));
                LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40));
                ep.topMargin = ui.dp(8);
                ep.gravity = rtl ? Gravity.START : Gravity.END;
                card.addView(ex, ep);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = ui.dp(12);
            sheet.body.addView(card, cp);
        }
    }

    /** Example sentences of one tense ("Perfect · gehen"): sentences with any of its forms, the forms in bold. */
    private void showTenseExamples(Conjugation c, Conjugation.Tense t) {
        Sheet sheet = new Sheet(act, ui, t.name, c.verb);
        sheet.show();
        String query = Examples.tenseQuery(t.forms);
        List<String> bold = Examples.tenseWords(t.forms);
        String key = "tense|" + c.lang.code + "|" + query;
        sheet.token = key;
        List<Examples.Example> cached = cache.get(key);
        if (cached != null) {
            renderExamples(sheet, bold, c.lang, cached, R.string.tense_examples_none);
            return;
        }
        sheet.loading(act.getString(R.string.examples_loading));
        new Thread(() -> {
            List<Examples.Candidate> all = new ArrayList<>();
            int ok = 0;
            IOException last = null;
            for (String f : Examples.FILTERS) {
                try {
                    all.addAll(Examples.parse(Engine.NET.get(Examples.queryUrl(query, c.lang, f), Engine.TIMEOUT), c.lang));
                    ok++;
                } catch (IOException e) {
                    last = e;
                    if (e instanceof java.net.UnknownHostException) break;
                }
                // only sentences that really show this tense
                List<String[]> others = new ArrayList<>();
                String[] orders = null;
                for (Conjugation.Tense o : c.tenses) {
                    if (o != t) others.add(o.forms);
                    if (o.name.toLowerCase(java.util.Locale.ROOT).startsWith("imperative")) orders = o.forms;
                }
                boolean imperative = t.name.toLowerCase(java.util.Locale.ROOT).startsWith("imperative");
                // English lists no imperative: it is the plain verb ("Go there alone.")
                if (orders == null && c.lang == Lang.EN) orders = new String[]{c.verb.replaceFirst("^to ", "")};
                final String[] ord = orders;
                all.removeIf(x -> !Examples.showsTense(x.example.text, t.forms, others, imperative, ord));
                if (Examples.select(all, c.lang, null, 4).size() >= 4) break;
            }
            final List<Examples.Example> res = ok > 0 ? fillMissing(Examples.select(all, c.lang, null, 4), c.lang) : null;
            final boolean off = last instanceof java.net.UnknownHostException || last instanceof java.net.ConnectException;
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed() || !sheet.isShowing() || !key.equals(sheet.token)) return;
                if (res == null) {
                    sheet.message(act.getString(off ? R.string.error_offline : R.string.examples_failed), null,
                            act.getString(R.string.retry), v -> {
                                sheet.dismiss();
                                showTenseExamples(c, t);
                            });
                    return;
                }
                cache.put(key, res);
                renderExamples(sheet, bold, c.lang, res, R.string.tense_examples_none);
            });
        }, "tense-examples").start();
    }
}
