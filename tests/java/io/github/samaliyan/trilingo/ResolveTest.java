package io.github.samaliyan.trilingo;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.8: numbers (typed or spoken as digits), German compounds (with a typo), how a search is read, the
 * dictionary form of inflected words, and a meaning for every search (with fake network answers).
 */
public final class ResolveTest {
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
    static Resolver r;

    public static void main(String[] args) throws IOException {
        Words en = Words.read(Lang.EN, new FileInputStream(A + "words_en.txt"));
        Words de = Words.read(Lang.DE, new FileInputStream(A + "words_de.txt"));
        Nouns nouns = Nouns.read(new FileInputStream(A + "de_nouns.txt"));
        Dict enDict = Dict.read(Lang.EN, new FileInputStream(A + "dict_en.txt"));
        Dict deDict = Dict.read(Lang.DE, new FileInputStream(A + "dict_de.txt"));
        r = new Resolver(en, de, nouns, enDict, deDict);
        numbers();
        compounds();
        prepare();
        bases();
        persianVerbs();
        meaningChain();
        namePicture();
        spelling(en, de, Words.read(Lang.FA, new FileInputStream(A + "words_fa.txt")));
        scanText();
        wordCards();
        tenseExamples();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static void numbers() {
        eq(8L, Numbers.parse("8"), "ascii digit");
        eq(8L, Numbers.parse("۸"), "Persian digit");
        eq(8L, Numbers.parse("٨"), "Arabic digit");
        eq(1500L, Numbers.parse("1,500"), "group separator");
        eq(-1L, Numbers.parse("acht"), "a word is not digits");
        eq(-1L, Numbers.parse(""), "empty");
        eq("eight", Numbers.words(8, Lang.EN), "8 in English");
        eq("acht", Numbers.words(8, Lang.DE), "8 in German");
        eq("هشت", Numbers.words(8, Lang.FA), "8 in Persian");
        eq("twenty-one", Numbers.words(21, Lang.EN), "21 in English");
        eq("einundzwanzig", Numbers.words(21, Lang.DE), "21 in German");
        eq("بیست و یک", Numbers.words(21, Lang.FA), "21 in Persian");
        eq("zero", Numbers.words(0, Lang.EN), "0");
        eq("eighth", Numbers.ordinal(8, Lang.EN), "ordinal");
        eq(8L, Numbers.valueOf("acht"), "number word back to its value");
        eq(8L, Numbers.parse("8."), "8. (German for 8th) is a number");
        eq(8L, Numbers.valueOf("هشت"), "Persian number word back");
        eq("drei Komma fünf", Numbers.decimalWords("3,5", Lang.DE), "decimal in German");
        eq("three point five", Numbers.decimalWords("3.5", Lang.EN), "decimal in English");
        eq("سه ممیز پنج", Numbers.decimalWords("۳٫۵", Lang.FA), "Persian decimal");
        eq(null, Numbers.decimalWords("1.000", Lang.EN), "1.000 is a thousand, not a decimal");
        eq(null, Numbers.decimalWords("abc", Lang.EN), "not a number");

        // "8" spoken in Persian: the words, the language known, a lookup with all three
        Resolver.Prepared p = r.prepare("8", null, Lang.FA);
        eq("هشت", p.text, "spoken 8 is read as هشت");
        eq(Lang.FA, p.source, "spoken 8 is Persian");
        eq(8L, p.number, "its value");
        Lookup l = r.offline(p, false, 1);
        ok(l != null, "a number never needs the network");
        eq(Resolver.NUMBER, l.service, "service of a number");
        eq("eight", result(l, Lang.EN), "8 → eight");
        eq("acht", result(l, Lang.DE), "8 → acht");
        // typed in the German field
        p = r.prepare("8", Lang.DE, null);
        eq("acht", p.text, "8 with German chosen");
        // no language at all: Persian (the user's own) is the default
        p = r.prepare("۸", null, null);
        eq("هشت", p.text, "Persian digits typed");
        // a number in words
        p = r.prepare("چهل و دو", null, null);
        eq(42L, p.number, "چهل و دو is the number 42");
        eq(Lang.FA, p.source, "in Persian");
        p = r.prepare("einundzwanzig", null, null);
        eq(21L, p.number, "einundzwanzig is 21");
        eq(Lang.DE, p.source, "in German");
        p = r.prepare("acht", null, null);
        eq(-1L, p.number, "a small number word stays a word (die Acht)");
        eq(1990L, Numbers.ofEnglish(Numbers.words(1990, Lang.EN)), "English words of any size back to digits");
        eq(1990L, Numbers.valueOfLookup(Resolver.number(1990, Lang.FA, 1)), "the value of a number lookup");
        eq(-1L, Numbers.ofEnglish("house"), "not a number");
        // a decimal
        p = r.prepare("3,5", Lang.DE, null);
        eq("drei Komma fünf", p.text, "3,5 with German chosen");
        l = r.offline(p, false, 1);
        eq("three point five", result(l, Lang.EN), "3,5 in English");
    }

    static void compounds() {
        Compounds c = r.compounds;
        Compounds.Split s = c.split("marketingassitentin");
        ok(s != null, "a compound with a typo is split");
        if (s != null) {
            eq("Marketingassistentin", s.word, "typo in a part is fixed, noun capital");
            ok(s.corrected, "marked as corrected");
            eq(2, s.parts.size(), "two parts");
            eq("Marketing", s.parts.get(0).base, "first part");
            eq("Assistentin", s.parts.get(1).base, "second part");
            eq("Marketingassistent", s.male, "the male form");
            Lookup l = c.lookup(s, 1);
            eq(Compounds.SERVICE, l.service, "offline translation from the parts");
            ok(result(l, Lang.EN).contains("marketing") && result(l, Lang.EN).contains("assistant"),
                    "English from the parts: " + result(l, Lang.EN));
            ok(!result(l, Lang.FA).isEmpty(), "Persian from the parts");
        }
        s = c.split("Haustür", true);
        ok(s != null && s.parts.size() == 2 && s.parts.get(0).base.equals("Haus"), "Haustür = Haus + Tür");
        s = c.split("Bundesregierung", true);
        ok(s != null && s.parts.get(0).base.equals("Bund"), "Bundes- is Bund with a link");
        eq(null, c.split("Freundschaft", true), "-schaft is a suffix, not a word");
        eq(null, c.split("Gesundheit"), "a known word is not changed");
        eq(null, c.split("Haus"), "a short known word");
        eq(null, c.split("xqzvbn"), "nonsense is not a compound");
        eq(null, c.split("Schmeterling"), "one typo in a known word is not a compound (Schmetterling)");
    }

    static void prepare() {
        Resolver.Prepared p = r.prepare("marketingassitentin", null, null);
        eq("Marketingassistentin", p.text, "the corrected compound is looked up");
        eq(Lang.DE, p.source, "a compound is German");
        ok(p.corrected, "shown as corrected");
        eq(Lang.DE, r.guess("Marketingassistentin"), "guessed as German, not English");
        p = r.prepare("house", null, null);
        eq("house", p.text, "an ordinary word is untouched");
        eq(-1L, p.number, "not a number");
        ok(r.isWord(Lang.DE, "Marketingassistentin"), "a compound is a real word for Review");
        p = r.prepare("z.B.", null, null);
        eq("zum Beispiel", p.text, "an abbreviation is written out");
        eq(Lang.DE, p.source, "z.B. is German");
        p = r.prepare("e.g.", null, null);
        eq("for example", p.text, "e.g.");
        p = r.prepare("Schmeterling", null, null);
        eq("Schmeterling", p.text, "a typo in a whole word is left for the spelling suggestion");
        ok(r.isWord(Lang.DE, "Haus"), "a word is a word");
        ok(!r.isWord(Lang.DE, "xqzvbn"), "nonsense is not");
    }

    static void bases() {
        ok(r.bases("Häuser", Lang.DE).contains("Haus"), "plural → singular: " + r.bases("Häuser", Lang.DE));
        ok(r.bases("ging", Lang.DE).contains("gehen"), "past → infinitive: " + r.bases("ging", Lang.DE));
        ok(r.bases("gegangen", Lang.DE).contains("gehen"), "participle → infinitive");
        eq("fahren", r.bases("gefahren", Lang.DE).get(0), "gefahren → fahren first, not die Gefahr");
        eq("aufstehen", r.bases("aufgestanden", Lang.DE).get(0), "separable participle");
        eq("anfangen", r.bases("angefangen", Lang.DE).get(0), "angefangen → anfangen");
        eq("aufstehen", r.bases("aufzustehen", Lang.DE).get(0), "zu-form");
        ok(r.bases("Marketingassistentin", Lang.DE).contains("Assistentin")
                || r.bases("Marketingassistentin", Lang.DE).contains("Assistent"), "compound → its last part");
        ok(r.bases("went", Lang.EN).contains("go"), "went → go: " + r.bases("went", Lang.EN));
        ok(r.bases("houses", Lang.EN).contains("house"), "houses → house");
        ok(r.bases("کتاب‌ها", Lang.FA).contains("کتاب"), "Persian plural: " + r.bases("کتاب‌ها", Lang.FA));
        ok(r.bases("رفتم", Lang.FA).contains("رفتن"), "Persian past: " + r.bases("رفتم", Lang.FA));
        List<String[]> g = r.glossary("Ich habe einen Hund", Lang.DE, 10);
        boolean dog = false;
        for (String[] x : g) dog |= x[0].equalsIgnoreCase("Hund");
        ok(dog, "the words of a sentence are explained");
    }

    static void persianVerbs() {
        eq("رفتن", PersianVerbs.infinitiveOf("رفتم"), "past form");
        eq("رفتن", PersianVerbs.infinitiveOf("می‌روم"), "present form");
        eq("خوردن", PersianVerbs.infinitiveOf("نمی‌خورد"), "negative present");
        eq(null, PersianVerbs.infinitiveOf("کتاب"), "not a verb");
    }

    /** Fake network: only the listed URL parts answer, everything else is "no such page". */
    static MeaningSource.Fetch fake(String... pairs) {
        return url -> {
            for (int i = 0; i < pairs.length; i += 2)
                if (pairs[i].startsWith("$") ? url.endsWith(pairs[i].substring(1)) : url.contains(pairs[i])) return pairs[i + 1];
            return "";
        };
    }

    static Lookup lookup(String q, Lang src, String... translations) {
        List<Lookup.Result> rs = new ArrayList<>();
        int i = 0;
        for (Lang t : src.targets()) {
            List<Lookup.Sense> senses = new ArrayList<>();
            List<String> one = new ArrayList<>();
            one.add(translations[i]);
            senses.add(new Lookup.Sense("", one));
            rs.add(new Lookup.Result(t, translations[i++], senses));
        }
        return new Lookup(q, src, rs, "test", 1);
    }

    static final String DICT_GO = "[{\"word\":\"go\",\"meanings\":[{\"partOfSpeech\":\"verb\",\"definitions\":"
            + "[{\"definition\":\"To move from one place to another.\"}]}]}]";

    static void meaningChain() throws IOException {
        // nothing in its own language anywhere: no meaning from another language (not the translations)
        Lookup l = lookup("xyzzy", Lang.EN, "xyzzy", "ایکس");
        Meaning m = MeaningSource.load(l, fake(), r);
        ok(m.senses.isEmpty(), "no meaning taken from another language");

        // an inflected English word: the meaning of its base
        l = lookup("went", Lang.EN, "ging", "رفت");
        m = MeaningSource.load(l, fake("entries/en/go", DICT_GO), r);
        eq(Meaning.NOTE_BASE, m.note, "went → meaning of go");
        eq("go", m.noteWord, "the base is named");
        ok(!m.senses.isEmpty(), "go has senses");

        // a German word whose English translation has a meaning: never shown (German meanings only)
        l = lookup("Xyzwort", Lang.DE, "go", "رفتن");
        m = MeaningSource.load(l, fake("entries/en/go", DICT_GO), r);
        ok(m.senses.isEmpty(), "no English meaning for a German word");

        // a compound: the German meaning of its last part
        String assist = "== Assistentin ({{Sprache|Deutsch}}) ==\n=== {{Wortart|Substantiv|Deutsch}}, {{f}} ===\n"
                + "{{Bedeutungen}}\n:[1] weibliche Hilfskraft\n";
        l = lookup("Marketingassistentin", Lang.DE, "marketing assistant", "دستیار بازاریابی");
        m = MeaningSource.load(l, fake("page=Assistentin", "{\"parse\":{\"wikitext\":" + jsonString(assist) + "}}"), r);
        ok(!m.senses.isEmpty() && m.senses.get(0).text.contains("Hilfskraft"), "compound: meaning of Assistentin " + m.senses.size());

        // a sentence: its words, explained in German
        String hund = "== Hund ({{Sprache|Deutsch}}) ==\n=== {{Wortart|Substantiv|Deutsch}}, {{m}} ===\n"
                + "{{Bedeutungen}}\n:[1] ein Haustier\n";
        l = lookup("Ich habe einen Hund", Lang.DE, "I have a dog", "من یک سگ دارم");
        m = MeaningSource.load(l, fake("page=Hund", "{\"parse\":{\"wikitext\":" + jsonString(hund) + "}}"), r);
        eq(Meaning.NOTE_WORDS, m.note, "a sentence shows its words");
        ok(!m.senses.isEmpty() && m.senses.get(0).text.contains("Haustier"), "in German: " + (m.senses.isEmpty() ? "" : m.senses.get(0).text));

        // a number: never Wikipedia (۲۱ is not the card game), its value and ordinal
        l = Resolver.number(21, Lang.FA, 1);
        m = MeaningSource.load(l, fake("wikipedia", "{\"type\":\"standard\",\"extract\":\"A card game.\"}"), r);
        eq(Meaning.NOTE_NUMBER, m.note, "a number explains itself");
        ok(!m.senses.isEmpty() && m.senses.get(0).text.contains("21"), "with its digits");

        l = Resolver.number(1990, Lang.FA, 1);
        m = MeaningSource.load(l, fake(), r);
        eq(Meaning.NOTE_NUMBER, m.note, "a long number (several words) explains itself too");

        // a phrase is not looked up in Wikipedia
        l = lookup("how are you", Lang.EN, "چطوری", "wie geht es dir");
        m = MeaningSource.load(l, fake("wikipedia", "{\"type\":\"standard\",\"extract\":\"A health program.\"}"), r);
        ok(!Meaning.NOTE_WIKIPEDIA.equals(m.note), "no Wikipedia article for a phrase");
        l = lookup("how are you", Lang.EN, "چطوری", "wie geht es dir");
        m = MeaningSource.load(l, fake("$entries/en/how", "[{\"word\":\"how\",\"meanings\":[{\"partOfSpeech\":\"adverb\",\"definitions\":[{\"definition\":\"In what way.\"}]}]}]"), r);
        ok(!m.senses.isEmpty() && m.senses.get(0).text.startsWith("how: In what way"), "common words explained in English");

        // a name: Wikipedia's first sentences
        String wiki = "{\"type\":\"standard\",\"title\":\"Berlin\",\"extract\":\"Berlin is the capital of Germany. "
                + "It is the largest city.\"}";
        l = lookup("Berlin", Lang.EN, "Berlin", "برلین");
        m = MeaningSource.load(l, fake("wikipedia.org/api/rest_v1/page/summary/Berlin", wiki), r);
        eq(Meaning.NOTE_WIKIPEDIA, m.note, "a name is explained by Wikipedia");
        ok(!m.senses.isEmpty() && m.senses.get(0).text.contains("capital"), "the extract is the meaning");
    }

    static void namePicture() {
        String page = "== Essen ({{Sprache|Deutsch}}) ==\n=== {{Wortart|Substantiv|Deutsch}}, {{n}} ===\n"
                + "{{Bedeutungen}}\n:[1] das, was man isst\n\n"
                + "=== {{Wortart|Substantiv|Deutsch}}, {{n}}, {{Wortart|Toponym|Deutsch}} ===\n{{Deutsch Toponym Übersicht\n|Bild=Lage der Stadt Essen.png|mini|Lage\n}}\n"
                + "{{Bedeutungen}}\n:[1] Stadt in Nordrhein-Westfalen\n";
        Meaning m = Meaning.parseGerman(page, "Essen");
        eq("", m.image, "the picture of the city Essen is not the picture of das Essen");
        ok(!m.senses.isEmpty(), "the meanings are kept");
        Compounds.Split s = r.compounds.split("Marketingassistent", true);
        ok(s != null && s.parts.size() == 2 && s.parts.get(1).base.equals("Assistent"), "Assistent, not Assi + Stent");
    }

    static void spelling(Words en, Words de, Words fa) {
        String[][] cases = {
                // two mistakes, swapped letters, doubled letters, silent letters, sound-alikes
                {"en", "hous", "house"}, {"en", "beautifull", "beautiful"}, {"en", "definately", "definitely"},
                {"en", "recieve", "receive"}, {"en", "tommorow", "tomorrow"}, {"en", "freind", "friend"},
                {"en", "wich", "which"}, {"en", "wether", "whether"}, {"en", "teh", "the"}, {"en", "exersice", "exercise"},
                {"en", "goverment", "government"}, {"en", "enviroment", "environment"}, {"en", "untill", "until"},
                {"de", "Schmeterling", "Schmetterling"}, {"de", "Fruend", "Freund"}, {"de", "sher", "sehr"},
                {"de", "schoen", "schön"}, {"de", "Geburstag", "Geburtstag"}, {"de", "vielleict", "vielleicht"},
                {"de", "Kuehlschrank", "Kühlschrank"}, {"de", "Strasenbahn", "Straßenbahn"}, {"de", "Fräulien", "Fräulein"},
                {"de", "biette", "bitte"}, {"de", "Mitwoch", "Mittwoch"}, {"de", "eigendlich", "eigentlich"},
                {"de", "Arbiet", "Arbeit"}, {"de", "nähmlich", "nämlich"},
                {"fa", "صلام", "سلام"}, {"fa", "سلم", "سلام"}, {"fa", "تحران", "تهران"}, {"fa", "حیلی", "خیلی"},
                {"fa", "قذا", "غذا"}, {"fa", "خداحافز", "خداحافظ"}, {"fa", "حواپیما", "هواپیما"}, {"fa", "مهربون", "مهربان"},
                {"fa", "آسمون", "آسمان"}, {"fa", "کامپیتر", "کامپیوتر"}, {"fa", "بزرک", "بزرگ"},
        };
        for (String[] c : cases) {
            Words w = c[0].equals("en") ? en : c[0].equals("de") ? de : fa;
            eq(c[2], Spelling.best(w, c[1]), "spelling " + c[1]);
        }
        ok(Spelling.suggest(de, "Haus", 3).isEmpty(), "a known word has no suggestion");
        ok(Spelling.suggest(en, "xqzvbnwk", 3).isEmpty(), "nonsense has none");
        ok(Spelling.suggest(de, "Termien", 3).size() >= 2, "more than one suggestion");
        eq("how are you today", Spelling.phrase(en, "how ar you todya"), "a sentence with typos");
        eq("ich habe einen Hund gesehen", Spelling.phrase(de, "ich habe einen Hnud gesehn"), "German sentence");
        eq(null, Spelling.phrase(en, "how are you"), "a right sentence is left alone");
        ok(Spelling.neighbours(Lang.DE, 'z', 'u'), "QWERTZ: z next to u");
        ok(!Spelling.neighbours(Lang.EN, 'q', 'p'), "q is far from p");
    }

    static void wordCards() throws IOException {
        java.io.File f = java.io.File.createTempFile("notebook", ".json");
        f.delete();
        Notebook nb = new Notebook(f);
        long now = 1_000_000_000_000L;
        List<Lookup> recent = new ArrayList<>();
        recent.add(lookup("Haus", Lang.DE, "house", "خانه"));
        recent.add(lookup("I have a very long sentence here", Lang.EN, "Ich habe", "من"));
        recent.add(lookup("Baum", Lang.DE, "tree", "درخت"));

        eq(null, WordCards.ofTheDay(nb, new ArrayList<>(), now), "no word of the day without words");
        ok(WordCards.ofTheDay(nb, recent, now) != null, "a recent word as the word of the day");
        WordCards.Pick p = WordCards.pick(nb, new ArrayList<>(), now, null, false);
        eq(WordCards.EMPTY, p.mode, "nothing saved, nothing recent: the empty card");
        p = WordCards.pick(nb, recent, now, null, false);
        eq(WordCards.RECENT, p.mode, "no saved words: recent words");
        eq("Haus", p.card.word, "the newest first");
        p = WordCards.pick(nb, recent, now, p.card.key(), true);
        eq("Baum", p.card.word, "Next: a long sentence is skipped");
        p = WordCards.pick(nb, recent, now, p.card.key(), true);
        eq("Haus", p.card.word, "and round again");

        nb.save(Lang.DE, "Tisch", "table", "", now - 10);
        nb.save(Lang.DE, "Stuhl", "chair", "", now - 5);
        p = WordCards.pick(nb, recent, now, null, false);
        eq(WordCards.DUE, p.mode, "a new saved word is due");
        eq(2, p.due, "two due");
        String first = p.card.word;
        nb.answer(p.card, Notebook.GOOD, now);
        p = WordCards.pick(nb, recent, now, null, true);
        eq(1, p.due, "one left after Got it");
        ok(!p.card.word.equals(first), "the next word comes");
        nb.answer(p.card, Notebook.GOOD, now);
        p = WordCards.pick(nb, recent, now, null, false);
        eq(WordCards.PRACTICE, p.mode, "all done: practice the saved words");
        String a = p.card.word;
        p = WordCards.pick(nb, recent, now, p.card.key(), true);
        ok(!p.card.word.equals(a), "Next word goes on");
        p = WordCards.pick(nb, recent, now + 3 * Notebook.DAY, null, false);
        eq(WordCards.DUE, p.mode, "due again days later");
        eq(WordCards.ofTheDay(nb, recent, now).word, WordCards.ofTheDay(nb, recent, now + 3600_000L).word, "the same word all day");
        ok(!WordCards.ofTheDay(nb, recent, now).word.equals(WordCards.ofTheDay(nb, recent, now + Notebook.DAY).word),
                "another word the next day");
        f.delete();
    }

    static void tenseExamples() {
        Conjugation g = Conjugation.of(Lang.DE, "gehen");
        String[] present = null, perfect = null, future = null, imperative = null;
        List<String[]> all = new ArrayList<>();
        for (Conjugation.Tense t : g.tenses) {
            all.add(t.forms);
            if (t.name.equals("Present")) present = t.forms;
            if (t.name.equals("Perfect")) perfect = t.forms;
            if (t.name.equals("Future")) future = t.forms;
            if (t.name.startsWith("Imperative")) imperative = t.forms;
        }
        eq("=gehe | =gehst | =geht | =gehen", Examples.tenseQuery(present), "present: any form");
        eq("=gegangen (=bin | =bist | =ist | =sind | =seid)", Examples.tenseQuery(perfect), "perfect: participle and an auxiliary");
        eq("=geh | =geht | (=gehen =Sie)", Examples.tenseQuery(imperative), "imperative: forms of different shape");
        List<String[]> others = new ArrayList<>(all);
        others.remove(present);
        ok(!Examples.showsTense("Ich weiß, dass ich gehen werde.", present, others, false), "future is not present");
        ok(Examples.showsTense("Wir gehen jetzt nach Hause.", present, others, false), "a present sentence");
        others = new ArrayList<>(all);
        others.remove(imperative);
        ok(Examples.showsTense("Geh nach Hause!", imperative, others, true), "an order");
        ok(!Examples.showsTense("Wohin gehen Sie?", imperative, others, true), "a question is no order");
        Conjugation r = Conjugation.of(Lang.FA, "رفتن");
        String[] past = null;
        List<String[]> fa = new ArrayList<>();
        for (Conjugation.Tense t : r.tenses) {
            if (t.name.equals("Simple past")) past = t.forms;
            else fa.add(t.forms);
        }
        ok(!Examples.showsTense("کی خواهید رفت؟", past, fa, false), "خواهید رفت is future, not past");
        ok(Examples.showsTense("دیروز کجا رفت؟", past, fa, false), "a past sentence");
        ok(Examples.tenseQuery(future).startsWith("=gehen ("), "future");
        Conjugation go = Conjugation.of(Lang.EN, "go");
        String[] goPresent = null, goImp = null;
        List<String[]> goOthers = new ArrayList<>();
        for (Conjugation.Tense t : go.tenses) {
            if (t.name.equals("Present")) goPresent = t.forms;
            else goOthers.add(t.forms);
            if (t.name.startsWith("Imperative")) goImp = t.forms;
        }
        if (goImp == null) goImp = new String[]{"go"};
        ok(!Examples.showsTense("Go there alone.", goPresent, goOthers, false, goImp), "an order is not the present");
        ok(Examples.showsTense("We all go to school.", goPresent, goOthers, false, goImp), "the present");
    }

    static void scanText() {
        List<ScanText.Item> w = new ArrayList<>();
        w.add(new ScanText.Item("Notausgang", 10, 10, 200, 40, 0));
        w.add(new ScanText.Item("Bitte", 10, 60, 80, 90, 1));
        w.add(new ScanText.Item("freihalten", 90, 60, 220, 90, 1));
        w.add(new ScanText.Item("Exit", 10, 200, 60, 230, 2));
        eq("Notausgang\nBitte freihalten", ScanText.textIn(w, 0, 0, 300, 100), "an area: its lines in reading order");
        eq("Bitte freihalten", ScanText.textIn(w, 300, 100, 0, 50), "dragged from bottom right to top left");
        eq("", ScanText.textIn(w, 400, 400, 500, 500), "an empty area");
        eq("freihalten", ScanText.at(w, 150, 75, 30).text, "a tap on a word");
        eq("Exit", ScanText.at(w, 70, 215, 40).text, "a tap next to a word");
        eq(null, ScanText.at(w, 600, 600, 30), "a tap far from every word");
        ok(ScanText.aboveLevel("B2", "A2"), "B2 is above A2");
        ok(!ScanText.aboveLevel("A1", "A2"), "A1 is not");
        ok(!ScanText.aboveLevel(null, "A2"), "an unknown word is not marked");
    }

    static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    static String result(Lookup l, Lang t) {
        for (Lookup.Result x : l.results) if (x.lang == t) return x.text;
        return "";
    }
}
