package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tidies the translation service's answer for one word with the built-in dictionaries and word lists:
 * <ul>
 * <li>a Persian verb as its infinitive (gehen → رفتن, not the order برو; drive → رانندگی کردن, not
 * رانندگی کنید), when the answer's own word list has the infinitive;</li>
 * <li>the meaning the dictionary gives first when the service's first word is another one it also lists
 * (Tisch → میز, not جدول, the table of a spreadsheet; Apfel → سیب, not اپل, the company);</li>
 * <li>small letters where the language writes them (Haus → house, not House; also → auch, not Auch), a
 * capital only for a German noun.</li>
 * </ul>
 * Pure Java (tested).
 */
final class Polish {
    private final Dict enDict, deDict;
    private final Words en, de;
    private final Nouns nouns;

    Polish(Dict enDict, Dict deDict, Words en, Words de, Nouns nouns) {
        this.enDict = enDict;
        this.deDict = deDict;
        this.en = en;
        this.de = de;
        this.nouns = nouns;
    }

    Lookup apply(Lookup l) {
        if (l == null || l.results.isEmpty()) return l;
        String q = Text.clean(l.query);
        boolean word = !q.isEmpty() && q.split(" ").length <= 3;
        if (!word) return l;
        List<Lookup.Result> rs = new ArrayList<>();
        boolean changed = false;
        for (Lookup.Result r : l.results) {
            String main = r.text;
            if (r.lang == Lang.FA) main = persian(l, r, main);
            else main = cased(l, r, main);
            if (!main.equals(r.text)) {
                changed = true;
                rs.add(new Lookup.Result(r.lang, main, r.senses));
            } else rs.add(r);
        }
        return changed ? new Lookup(l.query, l.source, rs, l.service, l.time, l.suggestion, l.baseForm, l.sense) : l;
    }

    private String persian(Lookup l, Lookup.Result r, String main) {
        // the dictionary's first meaning, when the service lists it too but put another one first
        Dict d = l.source == Lang.DE ? deDict : l.source == Lang.EN ? enDict : null;
        Dict.Entry e = d == null ? null : d.find(Text.clean(l.query));
        if (e != null && e.word.equalsIgnoreCase(Text.clean(l.query))) {
            List<Lookup.Sense> fa = e.targets.get(Lang.FA);
            String first = fa == null || fa.isEmpty() || fa.get(0).terms.isEmpty() ? null : fa.get(0).terms.get(0);
            if (first != null && !same(first, main) && !listed(fa, main)
                    // the service lists the dictionary's word too; or it gave no list at all (German → Persian goes
                    // through English): for a noun (Tisch → table → جدول, Apfel → apple → اپل), or for a verb
                    // whose answer is an order (gehen → go → برو)
                    && (listed(r.senses, first) || r.senses.isEmpty() && (Character.isUpperCase(Text.clean(l.query).charAt(0))
                    && l.source == Lang.DE || first.endsWith("ن") && !main.endsWith("ن")))) main = first;
        }
        // a verb (its first kind is verb): the infinitive (…ن), not an order (برو، رانندگی کنید)
        boolean verb = !r.senses.isEmpty() && r.senses.get(0).pos != null && r.senses.get(0).pos.toLowerCase(Locale.ROOT).startsWith("verb");
        if (verb && !main.endsWith("ن")) {
            for (Lookup.Sense s : r.senses) {
                if (s.pos == null || !s.pos.toLowerCase(Locale.ROOT).startsWith("verb")) continue;
                for (String t : s.terms) {
                    if (t.endsWith("ن") && (t.endsWith("تن") || t.endsWith("دن") || t.endsWith("یدن") || t.endsWith("ستن"))) {
                        return t;
                    }
                }
            }
        }
        // without word kinds (a phrase): "روشن کنید" → "روشن کردن"
        if (!main.endsWith("ن")) {
            String inf = infinitive(main);
            if (inf != null && (verb || r.senses.isEmpty())) return inf;
        }
        return main;
    }

    /** "رانندگی کنید" → "رانندگی کردن", "برو" stays (no general rule for the stem). */
    static String infinitive(String fa) {
        String t = Text.clean(fa);
        String[][] endings = {{" کنید", " کردن"}, {" کن", " کردن"}, {" شوید", " شدن"}, {" شو", " شدن"}, {" دهید", " دادن"},
                {" بدهید", " دادن"}, {" بزنید", " زدن"}, {" بگیرید", " گرفتن"}};
        for (String[] e : endings) if (t.endsWith(e[0])) return t.substring(0, t.length() - e[0].length()) + e[1];
        return null;
    }

    private String cased(Lookup l, Lookup.Result r, String main) {
        String t = Text.clean(main);
        if (t.isEmpty() || t.contains(" ") || !Character.isUpperCase(t.charAt(0))) return main;
        // the word typed with a capital in English is a name or the start of a sentence: kept
        if (l.source == Lang.EN && Character.isUpperCase(Text.clean(l.query).charAt(0))) return main;
        String low = t.toLowerCase(Locale.ROOT);
        String q = Text.clean(l.query);
        if (r.lang == Lang.EN) {
            // from a German common noun (Haus, Tisch) or a German word in small letters: not a name
            boolean common = l.source == Lang.DE && (Character.isLowerCase(q.charAt(0)) || nouns != null && nouns.exact(q) != null);
            return common && en != null && en.known(low) ? low : main;
        }
        if (r.lang == Lang.DE) {
            // a German word keeps its capital only as a noun (or a name: then the list does not have it small)
            boolean noun = nouns != null && nouns.exact(t) != null;
            boolean small = l.source == Lang.FA || l.source == Lang.EN && Character.isLowerCase(q.charAt(0));
            return !noun && small && de != null && de.known(low) ? low : main;
        }
        return main;
    }

    private static boolean listed(List<Lookup.Sense> senses, String term) {
        if (senses == null) return false;
        for (Lookup.Sense s : senses) for (String t : s.terms) if (same(t, term)) return true;
        return false;
    }

    private static boolean same(String a, String b) {
        return Text.unifyPersian(Text.clean(a)).equals(Text.unifyPersian(Text.clean(b)));
    }
}
