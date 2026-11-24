package io.github.samaliyan.trilingo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Grammar lessons: every lesson file is complete and consistent, search by topic and by word. */
public final class GrammarTest {
    static int checks, failures;

    static void eq(Object exp, Object got, String what) {
        checks++;
        if (exp == null ? got != null : !exp.equals(got)) {
            failures++;
            System.out.println("FAIL: " + what + " — expected <" + exp + "> got <" + got + ">");
        }
    }

    static void ok(boolean c, String what) {
        eq(true, c, what);
    }

    static final String ASSETS = "app/src/main/assets/";

    static String ids(List<Grammar.Lesson> ls) {
        StringBuilder b = new StringBuilder();
        for (Grammar.Lesson l : ls) b.append(b.length() == 0 ? "" : ", ").append(l.id);
        return "[" + b + "]";
    }

    static String first(List<Grammar.Lesson> ls) {
        return ls.isEmpty() ? null : ls.get(0).id;
    }

    static boolean top(List<Grammar.Lesson> ls, String id, int n) {
        for (int i = 0; i < Math.min(n, ls.size()); i++) if (ls.get(i).id.equals(id)) return true;
        return false;
    }

    public static void main(String[] args) throws IOException {
        Words en = Words.read(Lang.EN, new FileInputStream(ASSETS + "words_en.txt"));
        Words de = Words.read(Lang.DE, new FileInputStream(ASSETS + "words_de.txt"));
        Nouns nouns = Nouns.read(new FileInputStream(ASSETS + "de_nouns.txt"));

        File[] files = new File(ASSETS + "grammar").listFiles((d, n) -> n.endsWith(".txt"));
        ok(files != null && files.length >= 6, "lesson files present");
        Arrays.sort(files);
        List<Grammar.Lesson> all = new ArrayList<>();
        for (int i = 0; i < files.length; i++) all.addAll(Grammar.parse(new FileInputStream(files[i]), i * 1000));
        Grammar g = new Grammar(all);

        // ---------------------------------------------------------------- content
        List<String> problems = g.problems();
        for (String p : problems) System.out.println("PROBLEM: " + p);
        eq(0, problems.size(), "lessons have no problems");
        int deN = g.of(Lang.DE).size(), enN = g.of(Lang.EN).size();
        System.out.println(deN + " German and " + enN + " English lessons");
        ok(deN >= 55 && enN >= 45, "enough lessons");
        for (Lang l : new Lang[]{Lang.DE, Lang.EN})
            for (String level : Grammar.LEVELS) {
                int n = 0;
                for (Grammar.Lesson x : g.of(l)) if (x.level.equals(level)) n++;
                ok(n >= 3, l + " has lessons at " + level + ": " + n);
            }
        // order: German A1 first, levels ascending
        List<Grammar.Lesson> deL = g.of(Lang.DE);
        eq("de-pronouns-sein-haben", deL.get(0).id, "first German lesson");
        for (int i = 1; i < deL.size(); i++) ok(deL.get(i - 1).levelIndex() <= deL.get(i).levelIndex(), "levels ascending at " + deL.get(i).id);
        // text has no stray markup and Persian has no Arabic letter forms
        for (Grammar.Lesson l : g.all()) {
            for (String p : l.body(Lang.FA)) ok(!p.contains("ي") && !p.contains("ك"), l.id + ": Persian letters");
            for (Lang e : Grammar.EXPLAIN) for (String p : l.body(e)) ok(!p.contains("---") && !p.startsWith("==="), l.id + ": clean text");
            ok(l.body(Lang.FA).size() >= 1 && l.body(Lang.EN).size() >= 1 && l.body(Lang.DE).size() >= 1, l.id + ": all three explanations");
        }
        // parsing details
        Grammar.Lesson sep = g.get("de-separable-verbs");
        ok(sep != null, "separable verbs lesson");
        eq("A1", sep.level, "level");
        eq(Lang.DE, sep.lang, "teaches German");
        ok(sep.title(Lang.FA).contains("fernsehen"), "Persian title");
        ok(sep.words.contains("fernsehen"), "words lower case");
        ok(sep.body(Lang.FA).size() >= 4, "paragraphs and bullets split: " + sep.body(Lang.FA).size());
        ok(sep.body(Lang.FA).get(3).startsWith("•"), "bullet is its own paragraph");
        eq(3, sep.table.get(0).length, "table columns");
        eq("Ich sehe jeden Abend fern.", sep.examples.get(0)[0], "example sentence");
        eq("I watch TV every evening.", sep.examples.get(0)[2], "example translation");
        eq("Separable verbs (like fernsehen, anrufen)", sep.title(Lang.EN), "English title");

        // ---------------------------------------------------------------- word kinds
        Set<String> t = Grammar.tagsFor("fernsehen", Lang.DE, nouns, de);
        ok(t.contains("de-sep-verb") && t.contains("de-verb"), "fernsehen is a separable verb: " + t);
        ok(!t.contains("de-noun"), "fernsehen is not (here) a noun: " + t);
        t = Grammar.tagsFor("Fernsehen", Lang.DE, nouns, de);
        ok(t.contains("de-noun"), "das Fernsehen is a noun: " + t);
        t = Grammar.tagsFor("verstehen", Lang.DE, nouns, de);
        ok(t.contains("de-insep-verb") && !t.contains("de-sep-verb"), "verstehen inseparable: " + t);
        t = Grammar.tagsFor("sich freuen", Lang.DE, nouns, de);
        ok(t.contains("de-reflexive"), "sich freuen reflexive: " + t);
        t = Grammar.tagsFor("können", Lang.DE, nouns, de);
        ok(t.contains("de-modal"), "können modal: " + t);
        t = Grammar.tagsFor("fahren", Lang.DE, nouns, de);
        ok(t.contains("de-strong-verb"), "fahren strong: " + t);
        t = Grammar.tagsFor("Tisch", Lang.DE, nouns, de);
        ok(t.contains("de-noun") && !t.contains("de-verb"), "Tisch noun: " + t);
        t = Grammar.tagsFor("tisch", Lang.DE, nouns, de);
        ok(t.contains("de-noun"), "tisch typed lower case is still a noun: " + t);
        t = Grammar.tagsFor("essen", Lang.DE, nouns, de);
        ok(t.contains("de-verb"), "essen is a verb: " + t);
        t = Grammar.tagsFor("ruft", Lang.DE, nouns, de);
        ok(t.contains("de-verb"), "a conjugated form: " + t);
        t = Grammar.tagsFor("go", Lang.EN, nouns, en);
        ok(t.contains("en-irregular-verb"), "go irregular: " + t);
        t = Grammar.tagsFor("went", Lang.EN, nouns, en);
        ok(t.contains("en-irregular-verb"), "went irregular: " + t);
        t = Grammar.tagsFor("give up", Lang.EN, nouns, en);
        ok(t.contains("en-phrasal"), "give up phrasal: " + t);
        eq(0, Grammar.tagsFor("", Lang.DE, nouns, de).size(), "empty word");

        // ---------------------------------------------------------------- lessons about a word (the "Grammar" button)
        List<Grammar.Lesson> a = g.about("fernsehen", Lang.DE, Grammar.tagsFor("fernsehen", Lang.DE, nouns, de), 6);
        eq("de-separable-verbs", first(a), "fernsehen → separable verbs first: " + ids(a));
        a = g.about("anrufen", Lang.DE, Grammar.tagsFor("anrufen", Lang.DE, nouns, de), 6);
        eq("de-separable-verbs", first(a), "anrufen → separable verbs: " + ids(a));
        a = g.about("aufräumen", Lang.DE, Grammar.tagsFor("aufräumen", Lang.DE, nouns, de), 6);
        ok(top(a, "de-separable-verbs", 2), "aufräumen (by kind) → separable verbs: " + ids(a));
        a = g.about("Tisch", Lang.DE, Grammar.tagsFor("Tisch", Lang.DE, nouns, de), 6);
        ok(top(a, "de-gender-articles", 3) && top(a, "de-plural", 3), "noun → articles and plural: " + ids(a));
        a = g.about("helfen", Lang.DE, Grammar.tagsFor("helfen", Lang.DE, nouns, de), 6);
        ok(top(a, "de-dative-verbs", 2), "helfen → dative verbs: " + ids(a));
        a = g.about("weil", Lang.DE, Grammar.tagsFor("weil", Lang.DE, nouns, de), 6);
        eq("de-weil-dass", first(a), "weil → subordinate clauses: " + ids(a));
        a = g.about("went", Lang.EN, Grammar.tagsFor("went", Lang.EN, nouns, en), 6);
        ok(top(a, "en-irregular-verbs", 3) && top(a, "en-past-simple", 3), "went → irregular verbs / past simple: " + ids(a));
        a = g.about("give up", Lang.EN, Grammar.tagsFor("give up", Lang.EN, nouns, en), 6);
        eq("en-phrasal-verbs", first(a), "give up → phrasal verbs: " + ids(a));
        a = g.about("although", Lang.EN, Grammar.tagsFor("although", Lang.EN, nouns, en), 6);
        eq("en-linking-contrast", first(a), "although → linking words: " + ids(a));
        eq(0, g.about("خانه", Lang.FA, Grammar.tagsFor("خانه", Lang.FA, nouns, null), 6).size(), "Persian words have no lessons");
        a = g.about("fernsehen", Lang.EN, Grammar.tagsFor("fernsehen", Lang.EN, nouns, en), 6);
        for (Grammar.Lesson l : a) ok(l.lang == Lang.EN, "only lessons of the word's language");
        ok(g.about("x", Lang.DE, Grammar.tagsFor("Tisch", Lang.DE, nouns, de), 1).size() <= 1, "max respected");

        ok(!Grammar.tagsFor("watch TV", Lang.EN, nouns, en).contains("en-phrasal"), "watch TV is no phrasal verb");

        // ---------------------------------------------------------------- lessons for a lookup
        java.util.List<Lookup.Sense> verbSense = java.util.Collections.singletonList(new Lookup.Sense("verb", Arrays.asList("watch TV")));
        Lookup fern = new Lookup("fernsehen", Lang.DE, Arrays.asList(
                new Lookup.Result(Lang.EN, "watch TV", verbSense),
                new Lookup.Result(Lang.FA, "تلویزیون دیدن", java.util.Collections.emptyList())), "Google", 0);
        List<Grammar.Lesson> fl = g.forLookup(fern, Verbs.of(fern), nouns, de, en, 8);
        eq("de-separable-verbs", first(fl), "lookup fernsehen → separable verbs first: " + ids(fl));
        Lookup tisch = new Lookup("Tisch", Lang.DE, Arrays.asList(new Lookup.Result(Lang.EN, "table", java.util.Collections.emptyList())), "Google", 0);
        fl = g.forLookup(tisch, Verbs.of(tisch), nouns, de, en, 8);
        ok(top(fl, "de-gender-articles", 2), "lookup Tisch → articles: " + ids(fl));
        Lookup went = new Lookup("went", Lang.EN, Arrays.asList(new Lookup.Result(Lang.DE, "ging",
                java.util.Collections.singletonList(new Lookup.Sense("verb", Arrays.asList("gehen"))))), "Google", 0);
        fl = g.forLookup(went, Verbs.of(went), nouns, de, en, 8);
        ok(top(fl, "en-irregular-verbs", 2), "lookup went → irregular verbs: " + ids(fl));
        Lookup house = new Lookup("خانه", Lang.FA, Arrays.asList(new Lookup.Result(Lang.DE, "Haus", java.util.Collections.emptyList()),
                new Lookup.Result(Lang.EN, "house", java.util.Collections.emptyList())), "Google", 0);
        fl = g.forLookup(house, Verbs.of(house), nouns, de, en, 8);
        ok(top(fl, "de-gender-articles", 2), "Persian word → German translation's lessons: " + ids(fl));
        Lookup sentence = new Lookup("Ich gehe heute mit meinen Freunden ins Kino", Lang.DE, java.util.Collections.emptyList(), "Google", 0);
        eq(0, g.forLookup(sentence, Verbs.of(sentence), nouns, de, en, 8).size(), "no lessons for a long sentence");

        // ---------------------------------------------------------------- search box
        List<Grammar.Lesson> s = g.search("fernsehen", nouns, de, en);
        eq("de-separable-verbs", first(s), "search a word: fernsehen: " + ids(s));
        s = g.search("trennbar", nouns, de, en);
        eq("de-separable-verbs", first(s), "German key: " + ids(s));
        s = g.search("separable", nouns, de, en);
        eq("de-separable-verbs", first(s), "English title: " + ids(s));
        s = g.search("جداشدنی", nouns, de, en);
        eq("de-separable-verbs", first(s), "Persian title: " + ids(s));
        s = g.search("fern ته جمله", nouns, de, en);
        ok(top(s, "de-separable-verbs", 2), "the way he described it: " + ids(s));
        s = g.search("akkusativ", nouns, de, en);
        ok(top(s, "de-nominative-accusative", 2), "akkusativ: " + ids(s));
        s = g.search("Dativ", nouns, de, en);
        eq("de-dative", first(s), "Dativ: " + ids(s));
        s = g.search("perfekt", nouns, de, en);
        eq("de-perfekt", first(s), "perfekt: " + ids(s));
        s = g.search("present perfect", nouns, de, en);
        eq("en-present-perfect", first(s), "present perfect: " + ids(s));
        s = g.search("passive", nouns, de, en);
        ok(top(s, "en-passive", 3) && top(s, "de-passive", 3), "passive in both languages: " + ids(s));
        s = g.search("مجهول", nouns, de, en);
        ok(top(s, "en-passive", 3) || top(s, "de-passive", 3), "Persian topic: " + ids(s));
        s = g.search("ماضی نقلی", nouns, de, en);
        ok(top(s, "en-present-perfect", 3), "Persian term: " + ids(s));
        s = g.search("konjunktiv", nouns, de, en);
        ok(top(s, "de-konjunktiv2", 3), "konjunktiv: " + ids(s));
        s = g.search("relativsatz", nouns, de, en);
        ok(top(s, "de-relative-clauses", 2), "relativsatz: " + ids(s));
        s = g.search("relativsatze", nouns, de, en);
        ok(s.size() >= 0, "no crash on near words");
        s = g.search("wechselprapositionen", nouns, de, en);
        eq("de-two-way-prepositions", first(s), "umlaut typed without dots: " + ids(s));
        s = g.search("go", nouns, de, en);
        ok(top(s, "en-irregular-verbs", 4), "go → irregular verbs: " + ids(s));
        s = g.search("phrasal", nouns, de, en);
        eq("en-phrasal-verbs", first(s), "phrasal: " + ids(s));
        s = g.search("if", nouns, de, en);
        ok(top(s, "en-conditionals-0-1", 3), "if → conditionals: " + ids(s));
        s = g.search("sein haben", nouns, de, en);
        ok(top(s, "de-pronouns-sein-haben", 2), "two words: " + ids(s));
        eq(0, g.search("", nouns, de, en).size(), "empty search");
        eq(0, g.search("   ", nouns, de, en).size(), "blank search");
        eq(0, g.search("qqqzzz", nouns, de, en).size(), "nothing found");

        // Persian text with Latin words
        String iso = Grammar.isolateLatin("مثلاً fernsehen (تلویزیون دیدن): Ich sehe jeden Abend fern. فعل");
        eq("مثلاً \u2066fernsehen\u2069 (تلویزیون دیدن): \u2066Ich sehe jeden Abend fern.\u2069 فعل", iso, "Latin runs isolated");
        eq("\u2066habe gemacht\u2069، \u2066bin gegangen\u2069", Grammar.isolateLatin("habe gemacht، bin gegangen"), "Persian comma splits runs");
        eq("(\u2066sehe\u2069)", Grammar.isolateLatin("(sehe)"), "brackets stay outside");
        eq("\u2066fahren → du fährst\u2069", Grammar.isolateLatin("fahren → du fährst"), "arrow inside run");
        eq("فقط فارسی.", Grammar.isolateLatin("فقط فارسی."), "Persian only unchanged");
        // a translation's generic verb lessons are not listed
        for (Grammar.Lesson x : g.forLookup(fern, Verbs.of(fern), nouns, de, en, 8))
            ok(x.lang == Lang.DE, "fernsehen: no English tense lessons from 'watch TV': " + x.id);

        // names
        eq("Verbs", Grammar.categoryName("verbs", Lang.EN), "category name");
        eq("مبتدی", Grammar.levelName("A1", Lang.FA), "level name");

        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
