package io.github.samaliyan.trilingo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Grammar lessons for English and German, levels A1–C1, explained in Persian, English and German.
 * Lessons are plain text files in assets/grammar (format below); this class reads, checks and
 * searches them. Pure Java.
 *
 * <pre>
 * === de-separable-verbs          lesson id (unique; "en-" or "de-" = the language it teaches)
 * level: A1                       A1 A2 B1 B2 C1
 * cat: verbs                      see {@link #CATEGORIES}
 * title.fa: …   title.en: …   title.de: …
 * keys: fernsehen, trennbar, …    extra search words (any language)
 * words: fernsehen, anrufen …     words that link a looked-up word to this lesson (exact)
 * tags: de-sep-verb               word kinds that link to this lesson (see {@link #tagsFor})
 * see: de-word-order              related lessons
 * --- fa | --- en | --- de        explanation: paragraphs (blank line between), "• " bullets
 * --- table                       rows "a | b | c", the first row is the header
 * --- examples                    rows "sentence | Persian | English (German lessons) or German (English lessons)"
 * </pre>
 */
final class Grammar {
    static final String[] LEVELS = {"A1", "A2", "B1", "B2", "C1"};
    static final String[] CATEGORIES = {"verbs", "tenses", "nouns", "articles", "cases", "pronouns", "adjectives",
            "adverbs", "prepositions", "sentences", "questions", "conditionals", "passive", "other"};

    /** Explanation languages, in the order they are offered. */
    static final Lang[] EXPLAIN = {Lang.FA, Lang.EN, Lang.DE};

    private static final Set<String> PARTICLES = new LinkedHashSet<>(Arrays.asList("up", "down", "out", "off", "on", "in",
            "away", "back", "over", "around", "round", "through", "along", "after", "into", "about", "across", "forward", "by", "apart"));

    static final class Lesson {
        String id, level = "", cat = "";
        Lang lang;                                     // the language the lesson teaches
        final Map<Lang, String> title = new EnumMap<>(Lang.class);
        final Map<Lang, List<String>> body = new EnumMap<>(Lang.class);
        final List<String> keys = new ArrayList<>(), words = new ArrayList<>(), tags = new ArrayList<>(), see = new ArrayList<>();
        final List<String[]> table = new ArrayList<>(), examples = new ArrayList<>();
        int order;                                     // position in its file (lessons keep the author's order)

        String title(Lang l) {
            String t = title.get(l);
            return t != null ? t : title.get(Lang.EN);
        }

        List<String> body(Lang l) {
            List<String> b = body.get(l);
            return b != null ? b : body.get(Lang.EN);
        }

        int levelIndex() {
            return Arrays.asList(LEVELS).indexOf(level);
        }
    }

    private final List<Lesson> lessons = new ArrayList<>();
    private final Map<String, Lesson> byId = new HashMap<>();

    Grammar(List<Lesson> all) {
        for (Lesson l : all) {
            lessons.add(l);
            byId.put(l.id, l);
        }
        lessons.sort((a, b) -> {
            int c = a.lang.compareTo(b.lang);
            if (c != 0) return c;
            c = Integer.compare(a.levelIndex(), b.levelIndex());
            return c != 0 ? c : Integer.compare(a.order, b.order);
        });
    }

    List<Lesson> all() {
        return Collections.unmodifiableList(lessons);
    }

    List<Lesson> of(Lang lang) {
        List<Lesson> out = new ArrayList<>();
        for (Lesson l : lessons) if (l.lang == lang) out.add(l);
        return out;
    }

    Lesson get(String id) {
        return byId.get(id);
    }

    // ------------------------------------------------------------------ reading

    static List<Lesson> parse(InputStream in, int orderStart) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        return parse(lines, orderStart);
    }

    static List<Lesson> parse(List<String> lines, int orderStart) {
        List<Lesson> out = new ArrayList<>();
        Lesson cur = null;
        String section = null;
        StringBuilder para = new StringBuilder();
        for (String raw : lines) {
            String line = raw.replace("\r", "");
            if (line.startsWith("=== ")) {
                if (cur != null) flush(cur, section, para);
                cur = new Lesson();
                cur.id = line.substring(4).trim();
                cur.lang = cur.id.startsWith("de-") ? Lang.DE : Lang.EN;
                cur.order = orderStart + out.size();
                out.add(cur);
                section = null;
                continue;
            }
            if (cur == null || line.startsWith("#")) continue;
            if (line.startsWith("--- ")) {
                flush(cur, section, para);
                section = line.substring(4).trim();
                continue;
            }
            if (section == null) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String k = line.substring(0, c).trim(), v = line.substring(c + 1).trim();
                switch (k) {
                    case "level": cur.level = v; break;
                    case "cat": cur.cat = v; break;
                    case "title.fa": cur.title.put(Lang.FA, v); break;
                    case "title.en": cur.title.put(Lang.EN, v); break;
                    case "title.de": cur.title.put(Lang.DE, v); break;
                    case "keys": cur.keys.addAll(list(v)); break;
                    case "words": for (String w : list(v)) cur.words.add(w.toLowerCase(Locale.ROOT)); break;
                    case "tags": cur.tags.addAll(list(v)); break;
                    case "see": cur.see.addAll(list(v)); break;
                    default: break;
                }
                continue;
            }
            switch (section) {
                case "table":
                    if (!line.trim().isEmpty()) cur.table.add(cells(line));
                    break;
                case "examples":
                    if (!line.trim().isEmpty()) cur.examples.add(cells(line));
                    break;
                default:
                    if (line.trim().isEmpty()) flush(cur, section, para);
                    else if (line.trim().startsWith("•")) {
                        flush(cur, section, para);
                        para.append(line.trim());
                        flush(cur, section, para);
                    } else {
                        if (para.length() > 0) para.append(' ');
                        para.append(line.trim());
                    }
            }
        }
        if (cur != null) flush(cur, section, para);
        return out;
    }

    private static void flush(Lesson l, String section, StringBuilder para) {
        if (para.length() == 0) return;
        Lang lang = section == null ? null : Lang.of(section);
        if (lang != null) l.body.computeIfAbsent(lang, k -> new ArrayList<>()).add(para.toString());
        para.setLength(0);
    }

    private static List<String> list(String v) {
        List<String> out = new ArrayList<>();
        for (String s : v.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private static String[] cells(String line) {
        String[] c = line.split("\\|", -1);
        for (int i = 0; i < c.length; i++) c[i] = c[i].trim();
        return c;
    }

    /** Problems in the lessons (missing texts, broken links, duplicate ids); empty when all is well. */
    List<String> problems() {
        List<String> p = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Lesson l : lessons) {
            if (!ids.add(l.id)) p.add(l.id + ": duplicate id");
            if (l.levelIndex() < 0) p.add(l.id + ": level " + l.level);
            if (!Arrays.asList(CATEGORIES).contains(l.cat)) p.add(l.id + ": category " + l.cat);
            for (Lang e : EXPLAIN) {
                if (l.title.get(e) == null || l.title.get(e).isEmpty()) p.add(l.id + ": no title." + e.code);
                if (l.body.get(e) == null || l.body.get(e).isEmpty()) p.add(l.id + ": no text " + e.code);
            }
            if (l.examples.size() < 3) p.add(l.id + ": fewer than 3 examples");
            for (String[] ex : l.examples) if (ex.length != 3 || ex[0].isEmpty() || ex[1].isEmpty() || ex[2].isEmpty())
                p.add(l.id + ": example needs 3 parts: " + String.join(" | ", ex));
            int cols = l.table.isEmpty() ? 0 : l.table.get(0).length;
            for (String[] row : l.table) if (row.length != cols) p.add(l.id + ": table row has " + row.length + " cells, header " + cols);
            for (String s : l.see) if (!byId.containsKey(s)) p.add(l.id + ": see unknown lesson " + s);
        }
        return p;
    }

    // ------------------------------------------------------------------ word kinds

    /**
     * Kinds of a word that lessons can be about: "de-noun", "de-verb", "de-sep-verb", "de-insep-verb",
     * "de-reflexive", "de-modal", "de-strong-verb", "en-verb", "en-irregular-verb", "en-phrasal".
     */
    static Set<String> tagsFor(String word, Lang lang, Nouns nouns, Words list) {
        Set<String> tags = new LinkedHashSet<>();
        String w = Text.clean(word);
        if (w.isEmpty()) return tags;
        if (lang == Lang.DE) {
            String lw = w.toLowerCase(Locale.GERMAN);
            String inf = GermanVerbs.analyze(lw) != null ? lw : GermanVerbs.isKnownForm(lw) ? GermanVerbs.lemma(lw) : null;
            boolean nounFirst = Character.isUpperCase(w.charAt(0));
            if (inf != null && !nounFirst) {
                GermanVerbs.Parts p = GermanVerbs.analyze(inf);
                if (p != null && (list == null || list.known(p.reflexive ? p.full : inf) || GermanVerbs.isIrregular(p.base) || p.reflexive)) {
                    tags.add("de-verb");
                    if (!p.sep.isEmpty()) tags.add("de-sep-verb");
                    if (!p.insep.isEmpty()) tags.add("de-insep-verb");
                    if (p.reflexive) tags.add("de-reflexive");
                    if (Arrays.asList("können", "dürfen", "müssen", "sollen", "wollen", "mögen").contains(p.base)) tags.add("de-modal");
                    else if (GermanVerbs.isIrregular(p.base)) tags.add("de-strong-verb");
                }
            }
            if (nouns != null && !w.contains(" ") && (nounFirst || !tags.contains("de-verb")) && nouns.lookup(w) != null
                    && (nounFirst || list == null || !list.known(lw) || Character.isUpperCase(list.canonical(lw).charAt(0))))
                tags.add("de-noun");
        } else if (lang == Lang.EN) {
            String v = EnglishVerbs.clean(w);
            if (v != null) {
                String base = v.contains(" ") ? v : EnglishVerbs.lemma(v);
                if (base != null && EnglishVerbs.isIrregular(base.split(" ")[0])) {
                    tags.add("en-verb");
                    tags.add("en-irregular-verb");
                }
                String[] p = v.split(" ");
                if (p.length >= 2 && p.length <= 3 && PARTICLES.contains(p[1])) tags.add("en-phrasal");
            }
        }
        return tags;
    }

    /**
     * Lessons about a word: lessons that list it in "words", then lessons about its kind ("tags").
     * {@code lang} is the word's language; only lessons teaching that language are returned.
     */
    List<Lesson> about(String word, Lang lang, Set<String> tags, int max) {
        List<Lesson> out = new ArrayList<>();
        String w = Text.clean(word).toLowerCase(Locale.ROOT);
        if (w.isEmpty() || lang == Lang.FA) return out;
        Set<String> parts = new LinkedHashSet<>(Arrays.asList(w.split("[^\\p{L}]+")));
        for (Lesson l : lessons) {
            if (l.lang != lang) continue;
            for (String x : l.words) if (x.equals(w) || (x.indexOf(' ') < 0 && parts.contains(x))) {
                out.add(l);
                break;
            }
        }
        // then lessons about the word's kind; the rarer the kind, the more specific the lesson (separable verbs before verbs)
        Map<String, Integer> used = new HashMap<>();
        for (Lesson l : lessons) for (String t : l.tags) used.merge(t, 1, Integer::sum);
        List<Lesson> byKind = new ArrayList<>();
        Map<Lesson, Integer> rank = new HashMap<>();
        for (Lesson l : lessons) {
            if (l.lang != lang || out.contains(l)) continue;
            int best = Integer.MAX_VALUE;
            for (String t : l.tags) if (tags.contains(t)) best = Math.min(best, used.get(t));
            if (best == Integer.MAX_VALUE) continue;
            byKind.add(l);
            rank.put(l, best);
        }
        byKind.sort((x, y) -> Integer.compare(rank.get(x), rank.get(y)));
        out.addAll(byKind);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /**
     * Lessons for a looked-up word: about the typed word first (if it is English or German), then about
     * its German or English translation. {@code verbs} is {@link Verbs#of(Lookup)}: it tells which words are verbs.
     */
    List<Lesson> forLookup(Lookup l, Map<Lang, String> verbs, Nouns nouns, Words de, Words en, int max) {
        List<Lesson> out = new ArrayList<>();
        List<Lang> order = new ArrayList<>();
        order.add(l.source);
        for (Lang x : new Lang[]{Lang.DE, Lang.EN}) if (!order.contains(x)) order.add(x);
        for (Lang lang : order) {
            if (lang == Lang.FA) continue;
            String word = null;
            if (lang == l.source) word = l.query;
            else for (Lookup.Result r : l.results) if (r.lang == lang) { word = r.text; break; }
            if (word == null || Text.clean(word).isEmpty() || Text.clean(word).split(" ").length > 3) continue;
            Words list = lang == Lang.DE ? de : en;
            Set<String> tags = tagsFor(word, lang, nouns, list);
            String verb = verbs == null ? null : verbs.get(lang);
            if (verb != null) {
                tags.add(lang == Lang.DE ? "de-verb" : "en-verb");
                tags.addAll(tagsFor(verb, lang, nouns, list));
                if (lang == Lang.DE) tags.remove("de-noun");
            }
            if (lang != l.source) {                     // a translation: only its specific lessons, not every verb lesson
                tags.remove("de-verb");
                tags.remove("en-verb");
            }
            for (Lesson x : about(word, lang, tags, max)) if (!out.contains(x)) out.add(x);
            if (verb != null && !verb.equalsIgnoreCase(Text.clean(word)))
                for (Lesson x : about(verb, lang, tags, max)) if (!out.contains(x)) out.add(x);
        }
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    // ------------------------------------------------------------------ search

    private static String norm(String s) {
        return Words.fold(Lang.DE, Text.unifyPersian(Text.clean(s))).replace(String.valueOf((char) 0x200C), " ");
    }

    /**
     * Lessons for what was typed in the grammar search: a topic in any of the three languages
     * ("separable", "trennbar", "جداشدنی"), or a word ("fernsehen" → separable verbs).
     * Best first; empty for an empty query.
     */
    List<Lesson> search(String query, Nouns nouns, Words de, Words en) {
        String q = norm(query);
        List<Lesson> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        String[] terms = q.split(" ");
        Map<Lesson, Integer> score = new HashMap<>();
        for (Lesson l : lessons) {
            int s = 0;
            for (Lang e : EXPLAIN) {
                String t = norm(l.title(e));
                if (t.equals(q)) s += 40;
                else if (t.startsWith(q)) s += 25;
                else if (t.contains(q)) s += 18;
            }
            for (String k : l.keys) {
                String nk = norm(k);
                if (nk.equals(q)) s += 30;
                else if (nk.startsWith(q) && q.length() >= 3) s += 14;
                else if (nk.contains(q) && q.length() >= 4) s += 8;
            }
            for (String x : l.words) if (norm(x).equals(q)) s += 30;
            int hit = 0;                                   // every term somewhere in titles / keys
            for (String term : terms) {
                if (term.length() < 2) continue;
                boolean found = false;
                for (Lang e : EXPLAIN) found |= norm(l.title(e)).contains(term);
                for (String k : l.keys) found |= norm(k).contains(term);
                if (found) hit++;
            }
            if (terms.length > 1 && hit == terms.length) s += 12;
            if (s == 0 && q.length() >= 3) {               // the word appears in an example sentence
                for (String[] ex : l.examples) {
                    for (String part : norm(ex[0]).split("[^\\p{L}]+")) if (part.equals(q)) {
                        s += 3;
                        break;
                    }
                    if (s > 0) break;
                }
            }
            if (s > 0) score.put(l, s);
        }
        // a word: the lessons about its kind (fernsehen → separable verbs)
        if (terms.length <= 2) {
            for (Lang lang : new Lang[]{Lang.DE, Lang.EN}) {
                Set<String> tags = tagsFor(query, lang, nouns, lang == Lang.DE ? de : en);
                for (Lesson l : about(query, lang, tags, 6)) score.merge(l, 20, Integer::sum);
            }
        }
        out.addAll(score.keySet());
        out.sort((a, b) -> {
            int c = Integer.compare(score.get(b), score.get(a));
            if (c != 0) return c;
            c = Integer.compare(a.levelIndex(), b.levelIndex());
            return c != 0 ? c : Integer.compare(a.order, b.order);
        });
        return out;
    }

    // ------------------------------------------------------------------ Persian text with Latin words

    private static final char LRI = (char) 0x2066, PDI = (char) 0x2069;

    /**
     * Persian text with German or English inside: each Latin run ("Ich sehe jeden Abend fern.") is wrapped in a
     * left-to-right isolate, so its punctuation stays where it belongs instead of jumping to the other side.
     * Brackets are left outside the runs so they still pair up with the Persian text around them.
     */
    static String isolateLatin(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (!isLatinLetter(c)) {
                b.append(c);
                i++;
                continue;
            }
            int j = i, lastStrong = i;
            while (j < n && (isLatinLetter(s.charAt(j)) || inRun(s.charAt(j)))) {
                if (isLatinLetter(s.charAt(j)) || Character.isDigit(s.charAt(j))) lastStrong = j;
                j++;
            }
            int end = lastStrong + 1;                  // closing punctuation of the run: . ! ? … right after it
            while (end < j && ".!?…".indexOf(s.charAt(end)) >= 0) end++;
            b.append(LRI).append(s, i, end).append(PDI);
            i = end;
        }
        return b.toString();
    }

    private static boolean isLatinLetter(char c) {
        return Character.isLetter(c) && Character.UnicodeBlock.of(c) != Character.UnicodeBlock.ARABIC
                && Character.UnicodeBlock.of(c) != Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                && Character.UnicodeBlock.of(c) != Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B;
    }

    private static boolean inRun(char c) {
        return c == ' ' || Character.isDigit(c) || "'’.,!?…-/+=→·&*\"„“".indexOf(c) >= 0;
    }

    // ------------------------------------------------------------------ names

    static String levelName(String level, Lang in) {
        int i = Arrays.asList(LEVELS).indexOf(level);
        String[][] n = {
                {"مبتدی", "Beginner", "Anfänger"},
                {"مقدماتی", "Elementary", "Grundlagen"},
                {"متوسط", "Intermediate", "Mittelstufe"},
                {"بالاتر از متوسط", "Upper intermediate", "Obere Mittelstufe"},
                {"پیشرفته", "Advanced", "Fortgeschritten"}};
        if (i < 0) return level;
        return n[i][in == Lang.FA ? 0 : in == Lang.EN ? 1 : 2];
    }

    static String categoryName(String cat, Lang in) {
        int i = Arrays.asList(CATEGORIES).indexOf(cat);
        String[][] n = {
                {"فعل‌ها", "Verbs", "Verben"}, {"زمان‌ها", "Tenses", "Zeitformen"}, {"اسم‌ها", "Nouns", "Nomen"},
                {"حرف تعریف", "Articles", "Artikel"}, {"حالت‌ها", "Cases", "Kasus"}, {"ضمیرها", "Pronouns", "Pronomen"},
                {"صفت‌ها", "Adjectives", "Adjektive"}, {"قیدها", "Adverbs", "Adverbien"}, {"حرف اضافه", "Prepositions", "Präpositionen"},
                {"ساختار جمله", "Sentences", "Satzbau"}, {"سؤال‌ها", "Questions", "Fragen"}, {"جمله‌های شرطی", "Conditionals", "Bedingungssätze"},
                {"مجهول", "Passive", "Passiv"}, {"سایر", "Other", "Sonstiges"}};
        if (i < 0) return cat;
        return n[i][in == Lang.FA ? 0 : in == Lang.EN ? 1 : 2];
    }
}
