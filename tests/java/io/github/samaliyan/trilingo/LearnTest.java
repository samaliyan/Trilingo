package io.github.samaliyan.trilingo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Meanings (dictionary answers saved from the real services), word levels, the notebook with reviews, and exercises. */
public final class LearnTest {
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

    static final String A = "app/src/main/assets/", F = "tests/fixtures/";

    static String read(String f) throws IOException {
        return new String(Files.readAllBytes(new File(F + f).toPath()), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws IOException {
        meanings();
        sources();
        eq("spielen", GermanVerbs.lemma("spielt"), "spielt → spielen");
        eq("sammeln", GermanVerbs.lemma("sammelt"), "sammelt → sammeln");
        levels();
        notebook();
        exercises();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static void meanings() throws IOException {
        // English (dictionaryapi.dev)
        Meaning run = Meaning.parseDictionary(read("dictapi_run.json"), "run");
        ok(run.senses.size() >= 5, "run has meanings");
        ok(run.byPos().containsKey("Verb") && run.byPos().containsKey("Noun"), "grouped by word type: " + run.byPos().keySet());
        for (List<Meaning.Sense> g : run.byPos().values()) ok(g.size() <= 5, "at most 5 per word type");
        ok(run.audio.get(0).endsWith("-us.mp3") || run.audio.get(0).endsWith("-uk.mp3"), "us/uk recording first: " + run.audio);
        ok(run.ipa.startsWith("/"), "IPA");
        Meaning dog = Meaning.parseDictionary(read("dictapi_dog.json"), "dog");
        ok(dog.senses.get(0).text.contains("mammal"), "first meaning of dog");
        ok(dog.senses.get(0).example.contains("dog"), "example");
        Meaning give = Meaning.parseDictionary(read("dictapi_give_up.json"), "give up");
        ok(give.senses.size() >= 3 && give.synonyms.contains("surrender"), "phrasal verb meanings and synonyms");
        ok(Meaning.parseDictionary(read("dictapi_xqzv.json"), "xqzv").isEmpty(), "unknown word");
        ok(Meaning.parseDictionary("not json", "x").isEmpty(), "broken answer");
        ok(Meaning.parseDictionary("{\"title\":\"No Definitions Found\"}", "x").isEmpty(), "error object");
        List<String> fam = Meaning.englishFamily(Meaning.wikitext(read("enwkt_distinguish.json")), "distinguish", 20);
        ok(fam.contains("distinguishable") && fam.contains("distinction"), "English word family: " + fam);
        ok(!fam.contains("distinguish"), "the word itself is not in its family");

        // German (Wiktionary)
        Meaning tisch = Meaning.parseGerman(Meaning.wikitext(read("dewkt_Tisch.json")), "Tisch");
        eq("Noun", tisch.senses.get(0).pos, "German word type");
        ok(tisch.senses.get(0).text.startsWith("(Möbel) Möbelstück"), "German meaning in plain text: " + tisch.senses.get(0).text);
        ok(!tisch.senses.get(0).text.contains("[[") && !tisch.senses.get(0).text.contains("{{"), "no wiki markup");
        eq("Table.png", tisch.image, "picture");
        eq("ein Tisch", tisch.imageCaption, "picture caption");
        ok(tisch.antonyms.contains("Stuhl") && tisch.synonyms.contains("Tafel"), "synonyms / opposites");
        ok(tisch.family.contains("Tischbein"), "word family");
        ok(tisch.audio.get(0).endsWith("De-Tisch.ogg"), "recording: " + tisch.audio);
        Meaning haus = Meaning.parseGerman(Meaning.wikitext(read("dewkt_Haus.json")), "Haus");
        ok(!haus.synonyms.contains("salopp") && !haus.synonyms.contains("Winde"), "no labelled slang as synonyms: " + haus.synonyms);
        ok(haus.synonyms.contains("Wohnhaus"), "Haus synonyms");
        Meaning rot = Meaning.parseGerman(Meaning.wikitext(read("dewkt_rot.json")), "rot");
        eq("verschiedene Rottöne", rot.imageCaption, "caption with a link inside");
        Meaning gehen = Meaning.parseGerman(Meaning.wikitext(read("dewkt_gehen.json")), "gehen");
        eq("Verb", gehen.senses.get(0).pos, "verb");
        ok(!gehen.image.isEmpty(), "verbs have pictures too");
        for (Meaning.Sense s : gehen.senses) ok(!s.example.contains("„"), "no long quotations as examples");
        ok(Meaning.parseGerman("", "x").isEmpty(), "missing page");

        // Persian (Wiktionary)
        Meaning khane = Meaning.parsePersian(Meaning.wikitext(read("fawkt_خانه.json")), "خانه");
        ok(khane.senses.size() >= 2 && khane.senses.get(0).text.startsWith("بنایی"), "Persian meaning");
        eq("Noun", khane.senses.get(0).pos, "Persian word type");
        ok(khane.audio.size() == 1 && khane.audio.get(0).endsWith(".wav"), "Persian recording");
        for (Meaning.Sense s : khane.senses) ok(!s.text.contains("house") && !s.text.contains("{{"), "no translations in meanings");

        // Wikipedia picture; URLs
        ok(Meaning.wikipediaImage(read("wp_en_Apple.json")).contains("330px"), "Wikipedia thumbnail");
        eq("", Meaning.wikipediaImage("{\"type\":\"disambiguation\",\"thumbnail\":{\"source\":\"x\"}}"), "no picture for a list of meanings");
        eq("https://commons.wikimedia.org/wiki/Special:FilePath/Color_icon_red.svg?width=330", Meaning.fileUrl("Color icon red.svg", 330), "file url");
        ok(Meaning.wiktionaryUrl("de", "schön").endsWith("page=sch%C3%B6n"), "page name encoded");
        eq("Ein Tisch (Möbel) – groß", Meaning.plain("Ein [[Tisch]] {{K|Möbel}} – ''groß''<ref>x</ref>"), "plain text");
    }

    static void sources() throws IOException {
        java.util.Map<String, String> pages = new java.util.HashMap<>();
        pages.put(Meaning.dictionaryUrl("house"), read("dictapi_house.json"));
        pages.put(Meaning.wiktionaryUrl("en", "house"), read("enwkt_house.json"));
        pages.put(Meaning.wiktionaryUrl("de", "Haus"), read("dewkt_Haus.json"));
        pages.put(Meaning.wiktionaryUrl("de", "Tisch"), read("dewkt_Tisch.json"));
        pages.put(Meaning.wiktionaryUrl("fa", "خانه"), read("fawkt_خانه.json"));
        pages.put(Meaning.wikipediaUrl("en", "Dog"), read("wp_en_Dog.json"));
        pages.put(Meaning.dictionaryUrl("dog"), read("dictapi_dog.json"));
        List<String> asked = new ArrayList<>();
        MeaningSource.Fetch net = url -> {
            asked.add(url);
            String p = pages.get(url);
            return p == null ? "" : p;
        };
        Words en = Words.read(Lang.EN, new FileInputStream(A + "words_en.txt"));
        Words de = Words.read(Lang.DE, new FileInputStream(A + "words_de.txt"));
        java.util.function.UnaryOperator<String> cas = w -> de.known(w) ? de.canonical(w) : w;
        // English word: English meanings, family, and the German picture of "Haus"
        Lookup house = new Lookup("house", Lang.EN, Arrays.asList(new Lookup.Result(Lang.DE, "das Haus", Collections.emptyList()),
                new Lookup.Result(Lang.FA, "خانه", Collections.emptyList())), "Google", 0);
        Meaning m = MeaningSource.load(house, net, cas, en);
        eq(Lang.EN, m.lang, "meaning in the word's language");
        ok(m.senses.get(0).text.toLowerCase(java.util.Locale.ROOT).contains("structure") || m.senses.get(0).text.toLowerCase(java.util.Locale.ROOT).contains("building"), "house meaning: " + m.senses.get(0).text);
        eq("Leamouth riverside building 1.jpg", m.image, "picture from the German word (article removed)");
        ok(!m.family.isEmpty() && m.family.size() <= 10, "English family: " + m.family);
        for (String f : m.family) ok(en.known(f), "family words are real words: " + f);
        // German word typed in lower case
        Lookup tisch = new Lookup("tisch", Lang.DE, Arrays.asList(new Lookup.Result(Lang.EN, "table", Collections.emptyList())), "Google", 0);
        m = MeaningSource.load(tisch, net, cas, en);
        eq("Table.png", m.image, "German picture");
        ok(m.senses.get(0).text.contains("Möbelstück"), "German meaning for lower-case typing");
        // Persian word: Persian meanings, German picture
        Lookup khane = new Lookup("خانه", Lang.FA, Arrays.asList(new Lookup.Result(Lang.DE, "Haus", Collections.emptyList()),
                new Lookup.Result(Lang.EN, "house", Collections.emptyList())), "Google", 0);
        m = MeaningSource.load(khane, net, cas, en);
        ok(m.senses.get(0).text.startsWith("بنایی"), "Persian meaning");
        eq("Leamouth riverside building 1.jpg", m.image, "picture for a Persian word");
        // no German picture: a noun gets the Wikipedia picture
        Lookup dog = new Lookup("dog", Lang.EN, Arrays.asList(new Lookup.Result(Lang.DE, "Köter", Collections.emptyList())), "Google", 0);
        m = MeaningSource.load(dog, net, cas, en);
        ok(m.image.startsWith("https://") && "Wikipedia".equals(m.imageSource), "Wikipedia picture: " + m.image);
        // a sentence: nothing asked
        asked.clear();
        Lookup sentence = new Lookup("how are you doing today my friend", Lang.EN, Collections.emptyList(), "Google", 0);
        ok(MeaningSource.load(sentence, net, cas, en).isEmpty() && asked.isEmpty(), "no meaning for a sentence");
        eq("Haus", MeaningSource.germanWord(house), "German word without article");
    }

    static void levels() throws IOException {
        Levels en = Levels.read(new FileInputStream(A + "levels_en.txt"));
        Levels de = Levels.read(new FileInputStream(A + "levels_de.txt"));
        Nouns nouns = Nouns.read(new FileInputStream(A + "de_nouns.txt"));
        ok(en.size() > 10000 && de.size() > 8000, "level lists");
        eq("A1", en.of("house", Lang.EN, null), "house A1");
        eq("B1", en.of("distinguish", Lang.EN, null), "distinguish B1");
        eq("A1", en.of("went", Lang.EN, null), "went → go");
        eq("A1", en.of("houses", Lang.EN, null), "plural");
        eq("A1", de.of("Tisch", Lang.DE, nouns), "Tisch A1");
        eq("B1", de.of("unterscheiden", Lang.DE, nouns), "unterscheiden B1");
        eq("A1", de.of("ging", Lang.DE, nouns), "ging → gehen");
        eq(null, de.of("Qwertz", Lang.DE, nouns), "unknown");
        eq(null, en.of("give up", Lang.EN, null), "no level for phrasal verbs");
        eq("Intermediate", Levels.name("B1"), "level name");
    }

    static void notebook() throws IOException {
        File f = File.createTempFile("notebook", ".json");
        f.delete();
        Notebook n = new Notebook(f);
        long now = 1_000_000_000_000L;
        ok(n.addFolder("Class"), "add folder");
        ok(!n.addFolder("class"), "no duplicate folder");
        n.save(Lang.DE, "Tisch", "table · میز", "Class", now);
        n.save(Lang.EN, "distinguish", "unterscheiden", "", now + 1);
        ok(n.isSaved(Lang.DE, "tisch"), "saved (case ignored)");
        eq(2, n.count(null), "two saved");
        eq(1, n.count("Class"), "one in Class");
        eq("distinguish", n.saved(null).get(0).word, "newest first");
        // reload from the file
        Notebook m = new Notebook(f);
        eq(2, m.count(null), "kept in the file");
        eq(Arrays.asList("Class"), m.folders(), "folders kept");
        // review
        List<Notebook.Card> due = m.due(null, null, now + 10, 20);
        eq(2, due.size(), "new words are due");
        Notebook.Card c = due.get(0);
        String[] p = Notebook.preview(c);
        eq("10 min", p[0], "again → 10 min");
        eq("1 day", p[1], "good → 1 day");
        eq("4 days", p[2], "easy → 4 days");
        m.answer(c, Notebook.GOOD, now);
        eq(1, m.due(null, null, now + 10, 20).size(), "answered word not due");
        eq(2, m.due(null, null, now + Notebook.DAY + 1, 20).size(), "due again after a day");
        m.answer(c, Notebook.GOOD, now);
        eq("2.500", String.format(java.util.Locale.ROOT, "%.3f", c.interval), "interval grows (×2.5)");
        m.answer(c, Notebook.AGAIN, now);
        eq(0.0, c.interval, "again resets");
        eq(1, c.lapses, "lapse counted");
        // recent lookups join the review without being saved
        Lookup l = new Lookup("Haus", Lang.DE, Arrays.asList(new Lookup.Result(Lang.EN, "house", Collections.emptyList())), "Google", now);
        List<Notebook.Card> withRecent = m.due(null, Collections.singletonList(l), now + 10, 20);
        boolean hasHaus = false;
        for (Notebook.Card x : withRecent) hasHaus |= x.word.equals("Haus");
        ok(hasHaus, "recent lookup is reviewed");
        ok(!m.isSaved(Lang.DE, "Haus"), "but not saved");
        // export
        String tsv = m.ankiExport(null);
        ok(tsv.startsWith("#separator:tab"), "Anki header");
        ok(tsv.contains("Tisch\ttable · میز\ttrilingo de Class"), "Anki row: " + tsv);
        ok(!m.ankiExport("Class").contains("distinguish"), "export one folder");
        m.renameFolder("Class", "German class");
        eq("German class", m.get(Lang.DE, "Tisch").folder, "rename moves words");
        m.deleteFolder("German class");
        eq("", m.get(Lang.DE, "Tisch").folder, "delete keeps the words");
        m.unsave(Lang.EN, "distinguish");
        ok(!m.isSaved(Lang.EN, "distinguish"), "unsaved");
        f.delete();
    }

    static void exercises() throws IOException {
        Nouns nouns = Nouns.read(new FileInputStream(A + "de_nouns.txt"));
        Levels deL = Levels.read(new FileInputStream(A + "levels_de.txt"));
        Words de = Words.read(Lang.DE, new FileInputStream(A + "words_de.txt"));
        File[] files = new File(A + "grammar").listFiles((d, n) -> n.endsWith(".txt"));
        Arrays.sort(files);
        List<Grammar.Lesson> all = new ArrayList<>();
        for (int i = 0; i < files.length; i++) all.addAll(Grammar.parse(new FileInputStream(files[i]), i * 1000));
        Grammar g = new Grammar(all);
        Exercises ex = new Exercises(g, nouns, deL, de, new Random(1));

        int few = 0;
        for (Grammar.Lesson l : g.all()) {
            List<Exercises.Question> qs = ex.practice(l, 5);
            if (qs.size() < 5) few++;
            Set<String> prompts = new HashSet<>();
            for (Exercises.Question q : qs) {
                ok(prompts.add(q.prompt + q.hint + q.explain), l.id + ": no repeated question in a set");
                check(q, l.id);
            }
        }
        ok(few <= 2, "almost every lesson has 5 questions (" + few + " with fewer)");
        // "5 more" gives other questions
        Grammar.Lesson art = g.get("de-gender-articles");
        Set<String> first = new HashSet<>();
        for (Exercises.Question q : ex.practice(art, 5)) first.add(q.prompt);
        int repeated = 0;
        for (Exercises.Question q : ex.practice(art, 5)) if (first.contains(q.prompt)) repeated++;
        ok(repeated <= 1, "next set is new: " + repeated + " repeated");
        // articles are right
        for (int i = 0; i < 20; i++) {
            for (Exercises.Question q : ex.practice(art, 5)) {
                if (!q.hint.equals("der, die or das?")) continue;
                String noun = q.prompt.substring(4);
                ok(Character.isUpperCase(noun.charAt(0)), "a noun: " + noun);
                eq(Nouns.article(nouns.lookup(noun).genders.get(0)), q.options.get(q.answer), "article of " + noun);
            }
        }
        // mixed practice
        ok(ex.practice(Lang.EN, "B1", 5).size() == 5, "mixed English practice");
        ok(ex.practice(Lang.DE, "A1", 5).size() == 5, "mixed German practice");
        // find the mistake
        for (Lang lang : new Lang[]{Lang.DE, Lang.EN}) {
            List<Exercises.Question> ms = ex.mistakes(lang, null, 5);
            eq(5, ms.size(), lang + " mistakes: 5");
            for (Exercises.Question q : ms) {
                check(q, "mistakes");
                boolean found = false;
                for (Grammar.Lesson l : g.of(lang)) for (String[] e : l.examples) found |= e[0].equals(q.options.get(q.answer));
                ok(found, "the correct sentence is a lesson example: " + q.options.get(q.answer));
            }
            Set<String> a = new HashSet<>();
            for (Exercises.Question q : ms) a.add(q.options.get(q.answer));
            int again = 0;
            for (Exercises.Question q : ex.mistakes(lang, null, 5)) if (a.contains(q.options.get(q.answer))) again++;
            ok(again == 0, lang + ": the next 5 are other sentences");
        }
        List<String[]> dm = ex.germanMistakes("Der Hund spielt im Garten.");
        ok(contains(dm, "Die Hund spielt im Garten.") || contains(dm, "Das Hund spielt im Garten."), "wrong article: " + str(dm));
        ok(contains(dm, "Der Hund im Garten spielt."), "verb not second: " + str(dm));
        dm = ex.germanMistakes("Ich bin müde.");
        ok(contains(dm, "Ich bist müde.") || contains(dm, "Ich ist müde."), "wrong person: " + str(dm));
        dm = ex.germanMistakes("Sie kommt nicht, obwohl sie Zeit hat.");
        ok(contains(dm, "Sie kommt nicht, obwohl sie hat Zeit."), "subordinate clause: " + str(dm));
        List<String[]> em = ex.englishMistakes("She speaks three languages.");
        ok(contains(em, "She speak three languages."), "third person -s: " + str(em));
        em = ex.englishMistakes("I took the bus this morning.");
        ok(contains(em, "I taked the bus this morning."), "irregular past: " + str(em));
        ok(ex.englishMistakes("I woke up early.").isEmpty() || !contains(ex.englishMistakes("I woke up early."), "I waked up early."), "waked is not a mistake");
        // put the words in order
        List<Exercises.Question> order = ex.order(g.get("de-word-order-v2"), 3);
        ok(!order.isEmpty(), "word order questions");
        for (Exercises.Question q : order) {
            eq(Exercises.Question.ORDER, q.type, "type");
            List<String> sorted = new ArrayList<>(q.options), want = Exercises.orderWords(q.explain);
            Collections.sort(sorted);
            Collections.sort(want);
            eq(want, sorted, "same words as the sentence");
            ok(!String.join(" ", q.options).equals(q.explain), "shuffled");
        }
        // LanguageTool answers
        List<Exercises.Issue> lt = Exercises.parseCheck(read("lt_de.json"));
        eq(1, lt.size(), "one German problem");
        eq("HILFSVERB_HABEN_SEIN", lt.get(0).rule, "rule");
        eq(Arrays.asList("bin"), lt.get(0).replacements, "suggestion");
        eq(4, lt.get(0).offset, "offset");
        eq("de-perfekt", Exercises.lessonFor(lt.get(0), Lang.DE), "lesson for the rule");
        List<Exercises.Issue> lte = Exercises.parseCheck(read("lt_en.json"));
        ok(lte.size() >= 2, "English problems");
        eq("en-present-simple", Exercises.lessonFor(lte.get(0), Lang.EN), "agreement → present simple");
        eq(0, Exercises.parseCheck(read("lt_fa.json")).size(), "Persian: nothing found");
        eq(null, Exercises.parseCheck("<html>"), "broken answer");
        ok(Exercises.checkBody("Ich bin", Lang.DE).contains("language=de-DE"), "request body");
    }

    static void check(Exercises.Question q, String where) {
        if (q.type == Exercises.Question.ORDER) {
            ok(q.options.size() >= 3, where + ": order words");
            return;
        }
        ok(q.options.size() >= 2 && q.options.size() <= 4, where + ": 2–4 options " + q.options);
        ok(q.answer >= 0 && q.answer < q.options.size(), where + ": answer index");
        Set<String> low = new HashSet<>();
        for (String o : q.options) ok(low.add(o.toLowerCase(java.util.Locale.ROOT)), where + ": options differ " + q.options);
        ok(!q.prompt.isEmpty(), where + ": prompt");
    }

    static boolean contains(List<String[]> l, String s) {
        for (String[] x : l) if (x[0].equals(s)) return true;
        return false;
    }

    static String str(List<String[]> l) {
        StringBuilder b = new StringBuilder();
        for (String[] x : l) b.append(x[0]).append(" / ");
        return b.toString();
    }
}
