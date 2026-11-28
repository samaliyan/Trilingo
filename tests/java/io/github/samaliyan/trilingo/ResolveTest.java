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
        quiz(nouns);
        progress();
        senses();
        course(nouns);
        polish(nouns);
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

    static void quiz(Nouns nouns) {
        List<WordQuiz.Item> items = new ArrayList<>();
        items.add(new WordQuiz.Item(Lang.DE, "Tisch", "table\nمیز"));
        items.add(new WordQuiz.Item(Lang.DE, "gehen", "go\nرفتن"));
        items.add(new WordQuiz.Item(Lang.EN, "house", "Haus\nخانه"));
        items.add(new WordQuiz.Item(Lang.FA, "کتاب", "book\nBuch"));
        WordQuiz q = new WordQuiz(nouns, new java.util.Random(1), (l, w) -> !w.equals("house"));

        List<WordQuiz.Question> write = q.make(WordQuiz.WRITE, items, 10);
        eq(3, write.size(), "write: the English and German words, not the Persian one");
        WordQuiz.Question tisch = null;
        for (WordQuiz.Question x : write) if (x.word.equals("Tisch")) tisch = x;
        eq("میز", tisch.prompt, "the Persian translation is shown");
        eq("der Tisch", tisch.answer, "a noun's answer with its article");
        eq(WordQuiz.RIGHT, WordQuiz.check(tisch, "tisch"), "case does not count");
        eq(WordQuiz.RIGHT, WordQuiz.check(tisch, "der Tisch"), "with the article");
        eq(WordQuiz.ALMOST, WordQuiz.check(tisch, "Tich"), "one letter off: almost");
        eq(WordQuiz.WRONG, WordQuiz.check(tisch, "Stuhl"), "another word");
        eq(WordQuiz.WRONG, WordQuiz.check(tisch, ""), "nothing typed");

        List<WordQuiz.Question> art = q.make(WordQuiz.ARTICLE, items, 10);
        eq(1, art.size(), "der/die/das only for German nouns");
        eq(WordQuiz.RIGHT, WordQuiz.check(art.get(0), "der"), "der Tisch");
        eq(WordQuiz.WRONG, WordQuiz.check(art.get(0), "die"), "not die");

        List<WordQuiz.Question> verb = q.make(WordQuiz.VERB, items, 10);
        eq(1, verb.size(), "verb forms only for verbs");
        WordQuiz.Question v = verb.get(0);
        eq(WordQuiz.RIGHT, WordQuiz.check(v, v.answer), "its own form is right: " + v.prompt + " " + v.hint + " = " + v.answer);
        ok(v.hint.startsWith("gehen · "), "the verb and the tense are shown");

        WordQuiz.Question perfect = new WordQuiz.Question(WordQuiz.VERB, Lang.DE, "ich ___", "gehen · Perfect", "bin gegangen",
                java.util.Collections.singletonList("bin gegangen"), java.util.Collections.emptyList(), "gehen");
        eq(WordQuiz.RIGHT, WordQuiz.check(perfect, "Bin  gegangen."), "spaces and a full stop do not count");
        eq(WordQuiz.ALMOST, WordQuiz.check(perfect, "bin gegagen"), "one letter missing");
        WordQuiz.Question uml = new WordQuiz.Question(WordQuiz.WRITE, Lang.DE, "", "", "schön",
                java.util.Collections.singletonList("schön"), java.util.Collections.emptyList(), "schön");
        eq(WordQuiz.RIGHT, WordQuiz.check(uml, "schoen"), "oe for ö");

        List<WordQuiz.Question> say = q.make(WordQuiz.SAY, items, 10);
        WordQuiz.Question sayTisch = null;
        for (WordQuiz.Question x : say) if (x.word.equals("Tisch")) sayTisch = x;
        eq(WordQuiz.RIGHT, WordQuiz.heard(sayTisch, java.util.Arrays.asList("Fisch", "Tisch")), "one of the guesses is the word");
        eq(WordQuiz.RIGHT, WordQuiz.heard(sayTisch, java.util.Arrays.asList("das ist ein Tisch")), "the word inside a sentence");
        eq(WordQuiz.ALMOST, WordQuiz.heard(sayTisch, java.util.Arrays.asList("Tasch")), "a sound off");
        eq(WordQuiz.WRONG, WordQuiz.heard(sayTisch, java.util.Arrays.asList("Kühlschrank")), "another word");
        eq(WordQuiz.WRONG, WordQuiz.heard(sayTisch, new ArrayList<>()), "nothing heard");
    }

    static void course(Nouns nouns) throws IOException {
        Levels lv = Levels.read(new FileInputStream(A + "levels_de.txt"));
        Words de = Words.read(Lang.DE, new FileInputStream(A + "words_de.txt"));
        Dict dd = Dict.read(Lang.DE, new FileInputStream(A + "dict_de.txt"));
        List<Course.Entry> a1 = Course.entries(Lang.DE, "A1", lv, de, dd, nouns, null);
        ok(a1.size() > 250, "German A1 words to teach: " + a1.size());
        boolean article = false, verb = false;
        for (Course.Entry e : a1.subList(0, 40)) {
            article |= e.noun && e.display.matches("(der|die|das) .*");
            verb |= e.verb;
            ok(Text.hasPersian(e.back), "a Persian translation for " + e.word);
        }
        ok(article && verb, "nouns with their article and verbs among the first words");
        for (Course.Entry e : a1) {
            ok(!e.display.equals("die Habe") && !e.display.equals("das Oder"), "no verb form or conjunction as a noun: " + e.display);
        }
        List<List<Course.Entry>> units = Course.units(a1);
        eq(Course.UNIT, units.get(0).size(), "units of " + Course.UNIT);

        java.io.File f = java.io.File.createTempFile("course", ".json");
        f.delete();
        Course c = new Course(f);
        long now = 1_800_000_000_000L;
        java.util.Random rnd = new java.util.Random(3);
        eq(0, c.currentUnit(Lang.DE, units), "the first unit to start");
        List<Course.Exercise> lesson = c.lesson(Lang.DE, units.get(0), new ArrayList<>(), a1, now, rnd);
        int intros = 0, meanings = 0;
        for (Course.Exercise e : lesson) {
            if (e.kind == Course.INTRO) intros++;
            if (e.kind == Course.CHOOSE_MEANING) {
                meanings++;
                eq(4, e.choices.size(), "four meanings to choose from");
                ok(e.choices.contains(Course.meaning(e.word)), "the right one among them");
            }
        }
        eq(5, intros, "five new words on the first day");
        eq(5, meanings, "each practised");
        eq(Course.INTRO, lesson.get(0).kind, "a lesson starts with a new word");

        // go through it: all right
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Course.Exercise e : lesson) {
            if (e.kind == Course.INTRO) {
                c.met(e.word, now);
                continue;
            }
            c.answer(e, WordQuiz.RIGHT, seen.add(e.word.key()), now);
        }
        Course.WordState w0 = c.state(Lang.DE, units.get(0).get(0).word);
        eq(1, w0.stage, "one stage up per lesson, not two");
        eq(5, c.track(Lang.DE).newToday, "five new today");
        List<Course.Exercise> again = c.lesson(Lang.DE, units.get(0), new ArrayList<>(), a1, now, rnd);
        int more = 0;
        for (Course.Exercise e : again) if (e.kind == Course.INTRO) more++;
        eq(0, more, "no more new words the same day");
        again = c.lesson(Lang.DE, units.get(0), new ArrayList<>(), a1, now + Progress.DAY, rnd);
        more = 0;
        for (Course.Exercise e : again) if (e.kind == Course.INTRO) more++;
        eq(3, more, "the next day the rest of the unit (8 words)");

        // a noun answered with the wrong article: a stage back, and it comes back as an article question
        Course.WordState noun = c.adopt(Lang.DE, "Tisch", "der Tisch", "table\nمیز", true, false);
        noun.stage = 3;
        Course.Exercise q = new Course.Exercise(Course.ARTICLE_Q, noun, Course.ARTICLES, "", "", "", false);
        c.answer(q, WordQuiz.WRONG, true, now);
        eq(2, noun.stage, "a stage back");
        eq(1, noun.mistakes[Course.ARTICLE], "an article mistake");
        eq(Course.ARTICLE_Q, c.practise(noun, a1, rnd).kind, "practised as an article question");
        ok(c.weak(Lang.DE).contains(noun), "among the weak words");
        List<Course.Exercise> drill = c.drill(Lang.DE, Course.ARTICLE, null, a1, rnd, 10);
        eq(noun, drill.get(0).word, "the article drill starts with it");
        c.answer(drill.get(0), WordQuiz.RIGHT, true, now);
        eq(0, noun.mistakes[Course.ARTICLE], "a right answer forgives it");

        // tomorrow's new words follow how it went
        int before = c.track(Lang.DE).dailyNew;
        c.finished(Lang.DE, 50, now);
        c.finished(Lang.DE, 40, now);
        eq(before - 2, c.track(Lang.DE).dailyNew, "two hard lessons: fewer new words");
        c.finished(Lang.DE, 100, now);
        ok(!c.finished(Lang.DE, 100, now), "not yet too easy");
        eq(before - 1, c.track(Lang.DE).dailyNew, "two easy ones: one more");
        ok(c.finished(Lang.DE, 100, now), "three perfect lessons: time to move up");

        c.save();
        Course back = new Course(f);
        eq(c.toJson(), back.toJson(), "saved and read back the same");
        eq(3, back.state(Lang.DE, noun.word).stage, "a word's stage is kept");
        f.delete();
    }

    static void senses() {
        Lookup putOn = lookup("put on", Lang.EN, "گذاشتن", "auflegen");
        List<Senses.Option> o = Senses.of(putOn, "wear; switch on; gain (weight)");
        eq(3, o.size(), "a phrasal verb's meanings");
        eq("wear", o.get(0).label, "first meaning");
        eq("to gain weight", Senses.query(o.get(2)), "brackets dropped, and as a verb (infinitive, not an order)");
        eq("gain weight", Senses.gloss(o.get(2)), "kept without to");
        java.util.Map<Lang, String> by = new java.util.EnumMap<>(Lang.class);
        by.put(Lang.FA, "پوشیدن");
        by.put(Lang.DE, "anziehen");
        Lookup chosen = Senses.choose(putOn, o.get(0), by, "wear");
        eq("پوشیدن", chosen.result(Lang.FA).text, "Persian follows the meaning");
        eq("anziehen", chosen.result(Lang.DE).text, "German follows the meaning");
        eq("wear", chosen.sense, "the meaning is kept");
        eq("put on", chosen.query, "the word stays");
        ok(chosen.result(Lang.FA).summary(5).contains("گذاشتن"), "the first translation is not lost");
        Lookup back = Lookup.fromJson(Json.parse(chosen.toJson()));
        eq("wear", back.sense, "kept in the history");
        ok(Senses.matches("To don (clothing); to wear.", "wear"), "a definition of the meaning");
        ok(!Senses.matches("To switch on a device.", "wear"), "not another meaning");
        ok(Senses.matches("To increase in weight.", "gain weight"), "weight");
        eq(0, Senses.of(lookup("house", Lang.EN, "خانه", "Haus"), null).size(), "one meaning: no choice");

        List<Lookup.Result> rs = new ArrayList<>();
        List<Lookup.Sense> fa = new ArrayList<>();
        fa.add(new Lookup.Sense("noun", java.util.Arrays.asList("نور", "روشنایی")));
        fa.add(new Lookup.Sense("adjective", java.util.Arrays.asList("سبک", "روشن")));
        fa.add(new Lookup.Sense("verb", java.util.Arrays.asList("روشن کردن")));
        rs.add(new Lookup.Result(Lang.FA, "نور", fa));
        rs.add(new Lookup.Result(Lang.DE, "Licht", new ArrayList<>()));
        List<Senses.Option> light = Senses.of(new Lookup("light", Lang.EN, rs, "t", 1), null);
        eq(3, light.size(), "one meaning per word kind");
        eq("نور", light.get(0).label, "the translation shown first");
        eq("سبک", light.get(1).label, "the adjective");
        java.util.Set<String> kinds = new java.util.HashSet<>(java.util.Arrays.asList("verb"));
        eq(0, Senses.of(new Lookup("light", Lang.EN, rs, "t", 1), null, kinds).size(),
                "a kind the dictionary does not know is not offered (give as a noun)");
    }

    static void polish(Nouns nouns) throws IOException {
        Dict ed = Dict.read(Lang.EN, new FileInputStream(A + "dict_en.txt"));
        Dict dd = Dict.read(Lang.DE, new FileInputStream(A + "dict_de.txt"));
        Polish p = new Polish(ed, dd, r.en, r.de, nouns);
        // gehen → برو (an order) → رفتن
        List<Lookup.Result> rs = new ArrayList<>();
        rs.add(new Lookup.Result(Lang.EN, "go", new ArrayList<>()));
        rs.add(new Lookup.Result(Lang.FA, "برو", java.util.Collections.singletonList(
                new Lookup.Sense("verb", java.util.Arrays.asList("رفتن", "شدن")))));
        Lookup g = p.apply(new Lookup("gehen", Lang.DE, rs, "Google", 1));
        eq("رفتن", g.result(Lang.FA).text, "a verb in the infinitive, not an order");
        eq("روشن کردن", Polish.infinitive("روشن کنید"), "روشن کنید → روشن کردن");
        // Haus → House → house
        rs = new ArrayList<>();
        rs.add(new Lookup.Result(Lang.EN, "House", new ArrayList<>()));
        rs.add(new Lookup.Result(Lang.FA, "خانه", new ArrayList<>()));
        eq("house", p.apply(new Lookup("Haus", Lang.DE, rs, "Google", 1)).result(Lang.EN).text, "small letters in English");
        // also → Auch → auch, but table → Tisch stays
        rs = new ArrayList<>();
        rs.add(new Lookup.Result(Lang.FA, "همچنین", new ArrayList<>()));
        rs.add(new Lookup.Result(Lang.DE, "Auch", new ArrayList<>()));
        eq("auch", p.apply(new Lookup("also", Lang.EN, rs, "Google", 1)).result(Lang.DE).text, "not a noun: small");
        rs = new ArrayList<>();
        rs.add(new Lookup.Result(Lang.FA, "جدول", new ArrayList<>()));
        rs.add(new Lookup.Result(Lang.DE, "Tisch", new ArrayList<>()));
        eq("Tisch", p.apply(new Lookup("table", Lang.EN, rs, "Google", 1)).result(Lang.DE).text, "a noun keeps its capital");
        // Tisch → جدول (a spreadsheet table) → میز, when the dictionary says میز and the service lists it too
        Dict.Entry tisch = dd.find("Tisch");
        String first = tisch.targets.get(Lang.FA).get(0).terms.get(0);
        rs = new ArrayList<>();
        rs.add(new Lookup.Result(Lang.EN, "table", new ArrayList<>()));
        rs.add(new Lookup.Result(Lang.FA, "جدول", java.util.Collections.singletonList(
                new Lookup.Sense("noun", java.util.Arrays.asList("جدول", first)))));
        eq(first, p.apply(new Lookup("Tisch", Lang.DE, rs, "Google", 1)).result(Lang.FA).text, "the dictionary's meaning first: " + first);
        // light as نور stays (its first kind is a noun, not a verb)
        rs = new ArrayList<>();
        rs.add(new Lookup.Result(Lang.FA, "نور", java.util.Arrays.asList(new Lookup.Sense("noun", java.util.Arrays.asList("نور")),
                new Lookup.Sense("verb", java.util.Arrays.asList("روشن کردن")))));
        rs.add(new Lookup.Result(Lang.DE, "Licht", new ArrayList<>()));
        eq("نور", p.apply(new Lookup("light", Lang.EN, rs, "Google", 1)).result(Lang.FA).text, "a noun is not made a verb");
        // German → Persian through English gives no list: Tisch → جدول is still the dictionary's میز
        rs = new ArrayList<>();
        rs.add(new Lookup.Result(Lang.EN, "table", new ArrayList<>()));
        rs.add(new Lookup.Result(Lang.FA, "جدول", new ArrayList<>()));
        eq(first, p.apply(new Lookup("Tisch", Lang.DE, rs, "Google", 1)).result(Lang.FA).text, "no list: a noun takes the dictionary's meaning");
        // زیبا → Schön → schön
        rs = new ArrayList<>();
        rs.add(new Lookup.Result(Lang.EN, "beautiful", new ArrayList<>()));
        rs.add(new Lookup.Result(Lang.DE, "Schön", new ArrayList<>()));
        eq("schön", p.apply(new Lookup("زیبا", Lang.FA, rs, "Google", 1)).result(Lang.DE).text, "from Persian: small letters in German");
        // the chosen meaning "to switch on" in Persian comes back as برای روشن کردن
        rs = new ArrayList<>();
        rs.add(new Lookup.Result(Lang.FA, "پوشیدن", new ArrayList<>()));
        rs.add(new Lookup.Result(Lang.DE, "anziehen", new ArrayList<>()));
        java.util.Map<Lang, String> by = new java.util.HashMap<>();
        by.put(Lang.FA, "برای روشن کردن");
        by.put(Lang.DE, "zu einschalten");
        Lookup ch = Senses.choose(new Lookup("put on", Lang.EN, rs, "Google", 1), new Senses.Option("switch on", Lang.EN, true), by, "switch on");
        eq("روشن کردن", ch.result(Lang.FA).text, "برای is dropped");
        eq("einschalten", ch.result(Lang.DE).text, "zu is dropped");
        // a phrasal verb is an action: no picture
        eq(true, MeaningSource.phrasalVerb(new Lookup("put on", Lang.EN, rs, "Google", 1)), "put on is a phrasal verb");
        eq(false, MeaningSource.phrasalVerb(new Lookup("apple pie", Lang.EN, rs, "Google", 1)), "apple pie is not");
    }

    static void progress() {
        Progress p = new Progress();
        long day = Progress.DAY, now = 1_800_000_000_000L;
        eq(0, p.streak(now), "no streak yet");
        eq(-1, p.accuracy(now), "no accuracy yet");
        p.add(now - 3 * day, Progress.REVIEWS, 4);
        p.add(now - 2 * day, Progress.SAVED, 1);
        p.add(now - day, Progress.PRACTICE, 5);
        p.add(now - day, Progress.PRACTICE_RIGHT, 4);
        eq(3, p.streak(now), "three days in a row up to yesterday (today not started)");
        p.add(now, Progress.REVIEWS, 2);
        p.add(now, Progress.REVIEWS_RIGHT, 2);
        eq(4, p.streak(now), "four with today");
        eq(0, p.streak(now + 2 * day), "a missed day ends it");
        eq(4, p.bestStreak(), "the best streak stays");
        p.add(now - 20 * day, Progress.LEARNED, 3);
        p.add(now, Progress.LEARNED, 2);
        eq(java.util.Arrays.asList(0, 3, 0, 2), p.weeks(now, Progress.LEARNED, 4), "learned per week, oldest first");
        eq(55, p.accuracy(now), "6 of 11 right (4 reviews unanswered right)");
        Progress back = new Progress();
        back.read(Json.obj(Json.parse(p.toJson(now))));
        eq(p.toJson(now), back.toJson(now), "saved and read back the same");
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
