package io.github.samaliyan.trilingo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 1.7: the offline dictionary, words from a text, listening practice, searching and sorting saved words,
 * backup and restore.
 */
public final class StudyTest {
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

    static final String A = "app/src/main/assets/";
    static Words en, de, fa;
    static Nouns nouns;
    static Levels enLv, deLv;
    static Dict enDict, deDict;

    public static void main(String[] args) throws IOException {
        en = Words.read(Lang.EN, new FileInputStream(A + "words_en.txt"));
        de = Words.read(Lang.DE, new FileInputStream(A + "words_de.txt"));
        fa = Words.read(Lang.FA, new FileInputStream(A + "words_fa.txt"));
        nouns = Nouns.read(new FileInputStream(A + "de_nouns.txt"));
        enLv = Levels.read(new FileInputStream(A + "levels_en.txt"));
        deLv = Levels.read(new FileInputStream(A + "levels_de.txt"));
        enDict = Dict.read(Lang.EN, new FileInputStream(A + "dict_en.txt"));
        deDict = Dict.read(Lang.DE, new FileInputStream(A + "dict_de.txt"));
        dictFormat();
        dictData();
        textWords();
        dictation();
        notebook();
        review();
        backup();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static void dictFormat() {
        List<String> lines = Arrays.asList(
                "house\tde=noun:Haus,Gebäude;verb:unterbringen\tfa=noun:خانه,منزل;verb:جا دادن",
                "home\tde=noun:Zuhause,Heim\tfa=noun:خانه,منزل",
                "gift\tde=noun:Geschenk\tfa=noun:هدیه",
                "broken line",
                "only\tde=adverb:nur",
                "\tde=noun:X\tfa=noun:Y");
        Dict d = new Dict(Lang.EN, lines);
        eq(3, d.size(), "damaged lines and lines with one language are skipped");
        Lookup l = d.lookup("House", 5);
        ok(l != null, "found in any case");
        eq(Lang.EN, l.source, "source");
        eq(Dict.SERVICE, l.service, "marked as from the dictionary");
        eq("Haus", l.result(Lang.DE).text, "main German word");
        eq("خانه", l.result(Lang.FA).text, "main Persian word");
        eq(2, l.result(Lang.DE).senses.size(), "two word types");
        eq("verb", l.result(Lang.DE).senses.get(1).pos, "second word type");
        eq(Arrays.asList("Haus", "Gebäude"), l.result(Lang.DE).senses.get(0).terms, "terms");
        ok(d.lookup("to house", 0) != null, "English verbs typed with to");
        ok(d.lookup("houses", 0) == null, "only the headword");
        eq("خانه، منزل، جا دادن\nHaus, Gebäude, unterbringen", d.summary("house"), "summary for a card (Persian first, as on the cards)");

        Dict g = new Dict(Lang.DE, Arrays.asList(
                "essen\ten=verb:eat\tfa=verb:خوردن",
                "Essen\ten=noun:food,meal\tfa=noun:غذا",
                "Haus\ten=noun:house\tfa=noun:خانه"));
        eq("eat", g.lookup("essen", 0).result(Lang.EN).text, "lower case: the verb");
        eq("food", g.lookup("Essen", 0).result(Lang.EN).text, "capital: the noun");
        eq("house", g.lookup("haus", 0).result(Lang.EN).text, "a noun typed in lower case");
        eq("Haus", g.find("haus").word, "with its capital");

        // Persian → English and German, the main translation first
        Lookup p = Dict.persian("خانه", d, g, 0);
        ok(p != null, "Persian word found");
        eq(Lang.FA, p.source, "Persian source");
        eq("house", p.result(Lang.EN).text, "main English word");
        ok(p.result(Lang.EN).senses.get(0).terms.contains("home"), "other English words: " + p.result(Lang.EN).senses);
        eq("Haus", p.result(Lang.DE).text, "German word");
        eq("house", Dict.persian("خانه‌", d, g, 0).result(Lang.EN).text, "half-space ignored");
        eq("house", Dict.persian("خانِه", d, g, 0).result(Lang.EN).text, "vowel marks ignored");
        Lookup gift = Dict.persian("هدیه", d, g, 0);
        ok(gift != null && "gift".equals(gift.result(Lang.EN).text), "English only: German through the English word");
        eq("Geschenk", gift.result(Lang.DE).text, "German through the English word");
        ok(Dict.persian("ناشناخته", d, g, 0) == null, "unknown Persian word");

        // automatic language
        eq(Lang.DE, Dict.choose("Haus", null, false, 0, d, g, en, de).source, "German-only word → German");
        eq(Lang.EN, Dict.choose("house", null, false, 0, d, g, en, de).source, "English-only word → English");
        eq(Lang.FA, Dict.choose("خانه", null, false, 0, d, g, en, de).source, "Persian script");
        ok(Dict.choose("house", Lang.DE, false, 0, d, g, en, de) == null, "forced language without the word");
        ok(Dict.choose("a b c d", null, false, 0, d, g, en, de) == null, "long text: no dictionary");
    }

    static void dictData() {
        ok(enDict.size() >= 7000, "English dictionary size " + enDict.size());
        ok(deDict.size() >= 5500, "German dictionary size " + deDict.size());
        // the main translation (shown big) is the usual one
        eq("خانه", enDict.lookup("house", 0).result(Lang.FA).text, "house → خانه first");
        eq("رفتن", enDict.lookup("go", 0).result(Lang.FA).text, "go → رفتن first (the verb, not the board game)");
        eq("gehen", enDict.lookup("go", 0).result(Lang.DE).text, "go → gehen first");
        eq("کتاب", enDict.lookup("book", 0).result(Lang.FA).text, "book → کتاب first");
        eq("house", deDict.lookup("Haus", 0).result(Lang.EN).text, "Haus → house first");
        eq("خانه", deDict.lookup("Haus", 0).result(Lang.FA).text, "Haus → خانه first");
        String[][] en2 = {{"house", "Haus", "خانه"}, {"book", "Buch", "کتاب"}, {"water", "Wasser", "آب"}, {"go", "gehen", "رفتن"},
                {"eat", "essen", "خوردن"}, {"table", "Tisch", "میز"}, {"dog", "Hund", "سگ"}, {"beautiful", "schön", "زیبا"}};
        for (String[] t : en2) {
            Lookup l = enDict.lookup(t[0], 0);
            ok(l != null, "dictionary has " + t[0]);
            if (l == null) continue;
            ok(contains(l.result(Lang.DE), t[1]), t[0] + " → " + t[1] + ": " + l.result(Lang.DE).summary(9));
            ok(contains(l.result(Lang.FA), t[2]), t[0] + " → " + t[2] + ": " + l.result(Lang.FA).summary(9));
        }
        String[][] de2 = {{"Haus", "house", "خانه"}, {"gehen", "go", "رفتن"}, {"Tisch", "table", "میز"}, {"essen", "eat", "خوردن"},
                {"Buch", "book", "کتاب"}, {"schön", "beautiful", "زیبا"}};
        for (String[] t : de2) {
            Lookup l = deDict.lookup(t[0], 0);
            ok(l != null, "dictionary has " + t[0]);
            if (l == null) continue;
            ok(contains(l.result(Lang.EN), t[1]), t[0] + " → " + t[1] + ": " + l.result(Lang.EN).summary(9));
            ok(contains(l.result(Lang.FA), t[2]), t[0] + " → " + t[2] + ": " + l.result(Lang.FA).summary(9));
        }
        ok(Dict.choose("habe", null, false, 0, enDict, deDict, en, de) == null, "habe is a verb form, not the noun Habe");
        ok(Dict.choose("habe", Lang.DE, false, 0, enDict, deDict, en, de) == null, "also with German chosen");
        eq("table", Dict.choose("tisch", Lang.DE, false, 0, enDict, deDict, en, de).result(Lang.EN).text, "tisch is still Tisch");
        Lookup p = Dict.choose("کتاب", null, false, 0, enDict, deDict, en, de);
        ok(p != null && contains(p.result(Lang.EN), "book") && contains(p.result(Lang.DE), "Buch"), "Persian کتاب");
        int bad = 0;
        for (String w : new String[]{"the", "and", "time", "people", "water", "work", "school", "friend"}) if (enDict.lookup(w, 0) == null) bad++;
        ok(bad <= 2, "common English words are in the dictionary (" + bad + " missing)");
        // every entry is complete and clean
        ok(dictClean(A + "dict_en.txt") && dictClean(A + "dict_de.txt"), "every line has two languages and no empty terms");
    }

    static boolean dictClean(String file) {
        try {
            for (String line : Files.readAllLines(new File(file).toPath())) {
                Dict.Entry e = Dict.parse(line, 0);
                if (e == null) {
                    System.out.println("bad line: " + line);
                    return false;
                }
                for (List<Lookup.Sense> ss : e.targets.values())
                    for (Lookup.Sense s : ss) for (String t : s.terms) if (t.trim().isEmpty() || t.length() > 40) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean contains(Lookup.Result r, String term) {
        if (r == null) return false;
        if (r.text.equalsIgnoreCase(term)) return true;
        for (Lookup.Sense s : r.senses) for (String t : s.terms) if (t.equalsIgnoreCase(term)) return true;
        return false;
    }

    static List<String> words(List<TextWords.Item> items) {
        List<String> out = new ArrayList<>();
        for (TextWords.Item i : items) out.add(i.word);
        return out;
    }

    static void textWords() {
        eq(Lang.DE, TextWords.detect("Gestern bin ich mit meiner Schwester in die Stadt gegangen.", en, de), "German text");
        eq(Lang.EN, TextWords.detect("Yesterday I went to the city with my sister.", en, de), "English text");
        eq(null, TextWords.detect("دیروز با خواهرم به شهر رفتم", en, de), "Persian text");
        eq(null, TextWords.detect("1234 !!", en, de), "no words");

        TextWords g = new TextWords(Lang.DE, deLv, de, nouns, deDict);
        eq("gehen", g.base("ging", false), "irregular verb → infinitive");
        eq("spielen", g.base("spielte", false), "regular verb → infinitive");
        eq("Haus", g.base("Häuser", false), "plural → singular");
        eq("klein", g.base("kleinen", false), "adjective ending");
        eq(null, g.base("Kowalski", false), "a name in the middle of a sentence");
        eq("leider", g.base("leider", false), "a dictionary word stays as it is");
        eq("gestern", g.base("Gestern", true), "a small word at the start of a sentence");
        eq(null, g.base("EU", false), "abbreviation");

        String text = "Gestern ging ich mit meiner Schwester durch die Stadt. Die Verwaltung hat die Genehmigung "
                + "für das Gebäude verweigert, obwohl die Anwohner sich beschwert hatten. Die Verwaltung war streng.";
        List<TextWords.Item> a1 = g.find(text, "A1", w -> false);
        List<String> ws = words(a1);
        ok(!ws.contains("ich") && !ws.contains("gehen") && !ws.contains("Stadt"), "A1 words are left out: " + ws);
        ok(ws.contains("Genehmigung") || ws.contains("verweigern") || ws.contains("Verwaltung"), "harder words found: " + ws);
        for (TextWords.Item i : a1) if (i.word.equals("Verwaltung")) eq(2, i.count, "counted twice");
        List<String> b2 = words(g.find(text, "B2", w -> false));
        ok(b2.size() < ws.size(), "a higher level leaves out more: " + b2);
        List<String> savedOut = words(g.find(text, "A1", w -> w.equals("Verwaltung")));
        ok(!savedOut.contains("Verwaltung"), "saved words are left out");
        // harder first
        String prev = "";
        for (TextWords.Item i : a1) {
            String lv = i.level == null ? "Z" : i.level;
            ok(lv.compareTo(prev) >= 0, "sorted by level: " + i.word + " " + lv);
            prev = lv;
        }

        TextWords e = new TextWords(Lang.EN, enLv, en, nouns, enDict);
        eq("go", e.base("went", false), "went → go");
        eq("study", e.base("studies", false), "studies → study");
        eq(null, e.base("London", false), "a name");
        List<String> ew = words(e.find("The committee postponed the decision because the evidence was ambiguous. Peter agreed.", "A2", w -> false));
        ok((ew.contains("postpone") || ew.contains("postponed")) && ew.contains("ambiguous") && ew.contains("decision"), "harder English words: " + ew);
        ok(!ew.contains("the") && !ew.contains("because") && !ew.contains("Peter") && !ew.contains("peter"), "easy words and names out: " + ew);
    }

    static void dictation() {
        eq(Dictation.Grade.RIGHT, Dictation.check("der Tisch", "der Tisch", Lang.DE).grade, "exact");
        eq(Dictation.Grade.ALMOST, Dictation.check("Ich gehe nach Hause.", "ich gehe nach Hause", Lang.DE).grade, "German: capitals count");
        eq(Dictation.Grade.RIGHT, Dictation.check("Ich gehe nach Hause.", "Ich gehe nach Hause", Lang.DE).grade, "punctuation does not count");
        eq(Dictation.Grade.ALMOST, Dictation.check("der Tisch", "der tisch", Lang.DE).grade, "German noun in lower case: almost");
        eq(Dictation.NOTE_CAPITAL, Dictation.check("der Tisch", "der tisch", Lang.DE).note, "capital note");
        eq(Dictation.Grade.ALMOST, Dictation.check("müde", "muede", Lang.DE).grade, "ue for ü: almost");
        eq(Dictation.Grade.ALMOST, Dictation.check("die Straße", "die Strasse", Lang.DE).grade, "ss for ß: almost");
        eq(Dictation.Grade.RIGHT, Dictation.check("House", "house", Lang.EN).grade, "English first letter case");
        eq(Dictation.Grade.ALMOST, Dictation.check("beautiful", "beautifull", Lang.EN).grade, "one typo in a long word");
        eq(Dictation.Grade.WRONG, Dictation.check("cat", "cut", Lang.EN).grade, "short word, wrong letter");
        eq(Dictation.Grade.WRONG, Dictation.check("cat", "  ", Lang.EN).grade, "empty answer");
        eq(Dictation.Grade.RIGHT, Dictation.check("It’s late.", "It's late", Lang.EN).grade, "curly apostrophe");
        boolean[] m = Dictation.missed("Tisch", "Tich");
        eq("[false, false, true, false, false]", Arrays.toString(m), "missed letter marked");
        boolean[] m2 = Dictation.missed("der Tisch", "");
        eq(false, m2[3], "space never marked");
        eq(1, Dictation.distance("Haus", "Hause"), "distance");
        List<String> pool = Arrays.asList("a", "b", "c", "d", "e", "f", "g");
        List<String> first = Dictation.pick(pool, 5, new Random(1), new HashSet<>());
        eq(5, first.size(), "five picked");
        eq(5, new HashSet<>(first).size(), "no repeats");
        List<String> second = Dictation.pick(pool, 5, new Random(2), new HashSet<>(first));
        List<String> fresh = new ArrayList<>(pool);
        fresh.removeAll(first);
        ok(second.containsAll(fresh), "new items come first: " + second);
        eq(2, Dictation.pick(Arrays.asList("x", "y"), 5, new Random(), null).size(), "small pool");
    }

    static void notebook() throws IOException {
        File f = File.createTempFile("nbook", ".json");
        f.delete();
        Notebook nb = new Notebook(f);
        nb.save(Lang.DE, "müde", "tired\nخسته", "", 1000);
        nb.save(Lang.DE, "Tisch", "table\nمیز", "Home", 3000);
        nb.save(Lang.EN, "apple", "Apfel\nسیب", "", 2000);
        List<Notebook.Card> all = nb.saved(null);
        eq(1, Notebook.filter(all, "mude").size(), "search without umlaut");
        eq(1, Notebook.filter(all, "MÜDE").size(), "search in any case");
        eq(1, Notebook.filter(all, "میز").size(), "search in the translation");
        eq(1, Notebook.filter(all, "\u0645\u064A\u0632").size(), "Arabic letter forms");
        eq(3, Notebook.filter(all, " ").size(), "empty search: all");
        List<Notebook.Card> s = new ArrayList<>(all);
        Notebook.sort(s, Notebook.SORT_AZ, c -> null);
        eq("apple", s.get(0).word, "A to Z");
        eq("Tisch", s.get(2).word, "A to Z, umlaut as its letter");
        Notebook.sort(s, Notebook.SORT_OLDEST, c -> null);
        eq("müde", s.get(0).word, "oldest first");
        Notebook.sort(s, Notebook.SORT_NEWEST, c -> null);
        eq("Tisch", s.get(0).word, "newest first");
        Map<String, String> lv = new TreeMap<>();
        lv.put("apple", "A1");
        lv.put("müde", "A2");
        Notebook.sort(s, Notebook.SORT_LEVEL, c -> lv.get(c.word));
        eq(Arrays.asList("apple", "müde", "Tisch"), Arrays.asList(s.get(0).word, s.get(1).word, s.get(2).word), "by level, unknown last");
        nb.answer(nb.get(Lang.EN, "apple"), Notebook.AGAIN, 0);
        nb.answer(nb.get(Lang.EN, "apple"), Notebook.AGAIN, 0);
        Notebook.sort(s, Notebook.SORT_HARDEST, c -> null);
        eq("apple", s.get(0).word, "hardest first");

        int added = nb.saveAll(Lang.DE, Arrays.asList("Genehmigung", "müde", "verweigern"), w -> w.equals("verweigern") ? "" : "x", "Text", 5000);
        eq(2, added, "saveAll: already saved word not counted");
        eq("", nb.get(Lang.DE, "müde").folder, "saveAll keeps the folder of a saved word");
        ok(nb.folders().contains("Text"), "folder created");
        eq(1, nb.withoutBack().size(), "word without translation");
        nb.setBack(Lang.DE, "verweigern", "refuse");
        eq(0, nb.withoutBack().size(), "translation filled in");
        eq("refuse", new Notebook(f).get(Lang.DE, "verweigern").back, "saved to the file");

        // merge (restore)
        File f2 = File.createTempFile("nbook2", ".json");
        f2.delete();
        Notebook other = new Notebook(f2);
        other.save(Lang.EN, "apple", "Apfel", "Fruit", 100);
        other.save(Lang.EN, "pear", "Birne", "Fruit", 200);
        for (int i = 0; i < 4; i++) other.answer(other.get(Lang.EN, "apple"), Notebook.GOOD, 0);
        other.addFolder("Empty");
        int n = nb.merge(other.toJson());
        eq(1, n, "one new word from the backup");
        ok(nb.isSaved(Lang.EN, "pear"), "new word saved");
        eq(4, nb.get(Lang.EN, "apple").reps, "more review progress wins");
        eq("Fruit", nb.get(Lang.EN, "apple").folder, "a word without a folder gets the backup's folder");
        eq("Home", nb.get(Lang.DE, "Tisch").folder, "own folder kept");
        ok(nb.folders().contains("Fruit") && nb.folders().contains("Empty"), "folders added");
        eq(0, nb.merge("{broken"), "a broken backup adds nothing");
        ok(nb.isSaved(Lang.DE, "Tisch"), "and deletes nothing");
        f.delete();
        f2.delete();
        new File(f.getPath() + ".tmp").delete();
    }

    static Lookup lk(String q, Lang src, String tr, String suggestion) {
        List<Lookup.Result> rs = new ArrayList<>();
        rs.add(new Lookup.Result(src == Lang.EN ? Lang.DE : Lang.EN, tr, new ArrayList<>()));
        rs.add(new Lookup.Result(Lang.FA, "x", new ArrayList<>()));
        return new Lookup(q, src, rs, "Google", 1000, suggestion);
    }

    static void review() throws IOException {
        List<Lookup> recent = Arrays.asList(
                lk("Haus", Lang.DE, "house", ""),
                lk("hous", Lang.EN, "Haus", "house"),            // a typo with "Did you mean"
                lk("xqzv", Lang.EN, "xqzv", ""),                 // not a word: the translation only repeats it
                lk("eihnschlafen", Lang.DE, "fall asleep", ""),  // a typo the service still translated
                lk("give up", Lang.EN, "aufgeben", ""),
                lk("how are you today my friend", Lang.EN, "wie geht es", ""));
        List<Lookup> ok = Notebook.reviewable(recent, en, de);
        List<String> qs = new ArrayList<>();
        for (Lookup l : ok) qs.add(l.query);
        eq(Arrays.asList("Haus", "give up"), qs, "only real words are reviewed");
        File f = File.createTempFile("nbrev", ".json");
        f.delete();
        Notebook nb = new Notebook(f);
        List<Notebook.Card> due = nb.due(null, ok, 5000, 20);
        eq(2, due.size(), "recent words are due");
        eq(0, nb.size(), "nothing is stored before a review");
        nb.answer(due.get(0), Notebook.GOOD, 5000);
        eq(1, nb.size(), "a reviewed word is kept");
        nb.forgetRecent(Lang.DE, "Haus");
        nb.forgetRecent(Lang.EN, "give up");
        eq(0, nb.size(), "removed from Recent: no longer reviewed");
        nb.save(Lang.DE, "Tisch", "table", "", 1);
        nb.forgetAllRecent();
        ok(nb.isSaved(Lang.DE, "Tisch"), "clearing Recent keeps saved words");
        f.delete();
    }

    static void backup() throws IOException {
        File hf = File.createTempFile("hist", ".json");
        hf.delete();
        History h = new History(hf);
        List<Lookup.Result> rs = new ArrayList<>();
        rs.add(new Lookup.Result(Lang.EN, "house", new ArrayList<>()));
        rs.add(new Lookup.Result(Lang.FA, "خانه", new ArrayList<>()));
        Lookup haus = new Lookup("Haus", Lang.DE, rs, "Google", 1000);
        h.add(haus);
        List<Lookup.Result> rs2 = new ArrayList<>();
        rs2.add(new Lookup.Result(Lang.DE, "Hund", new ArrayList<>()));
        rs2.add(new Lookup.Result(Lang.FA, "سگ", new ArrayList<>()));
        Lookup dog = new Lookup("dog", Lang.EN, rs2, "Google", 50_000);
        h.add(dog);
        eq(dog, h.items().get(0), "newest first");
        h.remove(haus);
        h.restore(haus, 1);
        eq("Haus", h.items().get(1).query, "undo puts it back in its place");
        h.restore(haus, 0);
        eq(2, h.items().size(), "restored once only");

        Map<String, String> settings = new TreeMap<>();
        settings.put("theme", "i:2");
        settings.put("meaningFirst", "b:true");
        settings.put("voiceLang", "s:fa \"x\"");
        Notebook nb = new Notebook(null);
        nb.save(Lang.EN, "apple", "Apfel", "", 1);
        nb.save(Lang.DE, "Haus", "house", "A", 2);
        String text = Backup.create(settings, nb.toJson(), h.toJson(), 1_764_000_000_000L, "1.7");
        Backup.Contents c = Backup.read(text);
        eq(1_764_000_000_000L, c.created, "date");
        eq(2, c.savedWords, "saved words counted");
        eq(2, c.history.size(), "recent words");
        eq(settings, c.settings, "settings, with quotes kept");

        File hf2 = File.createTempFile("hist2", ".json");
        hf2.delete();
        History h2 = new History(hf2);
        List<Lookup.Result> rs3 = new ArrayList<>();
        rs3.add(new Lookup.Result(Lang.EN, "house (new)", new ArrayList<>()));
        rs3.add(new Lookup.Result(Lang.FA, "خانه", new ArrayList<>()));
        h2.add(new Lookup("Haus", Lang.DE, rs3, "Google", 99_000));
        eq(1, h2.merge(c.history), "one new recent word");
        eq(2, h2.items().size(), "merged");
        eq("house (new)", h2.find("Haus", Lang.DE).results.get(0).text, "the newer lookup wins");
        eq("dog", h2.items().get(1).query, "sorted newest first");

        Notebook nb2 = new Notebook(null);
        eq(2, nb2.merge(c.notebookJson), "words restored");
        ok(nb2.folders().contains("A"), "folders restored");

        for (String bad : new String[]{"", "{}", "[1,2]", "{\"kind\":\"other\"}", "not json", "{\"kind\":\"trilingo-backup\",\"format\":99}"}) {
            boolean threw = false;
            try {
                Backup.read(bad);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            ok(threw, "not a backup: " + bad);
        }
        eq("{\"a\":[1,2.5,\"x\",true,null],\"b\":{}}", Json.write(Json.parse("{\"a\":[1,2.5,\"x\",true,null],\"b\":{}}")), "JSON written back");
        hf.delete();
        hf2.delete();
    }
}
