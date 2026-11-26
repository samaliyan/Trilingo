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
            for (int i = 0; i < pairs.length; i += 2) if (url.contains(pairs[i])) return pairs[i + 1];
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
        // nothing anywhere: the translations are shown, never an empty meaning
        Lookup l = lookup("xyzzy", Lang.EN, "xyzzy", "ایکس");
        Meaning m = MeaningSource.load(l, fake(), r);
        ok(!m.senses.isEmpty(), "never empty");
        eq(Meaning.NOTE_TRANSLATION, m.note, "last resort: the translations");

        // an inflected English word: the meaning of its base
        l = lookup("went", Lang.EN, "ging", "رفت");
        m = MeaningSource.load(l, fake("entries/en/go", DICT_GO), r);
        eq(Meaning.NOTE_BASE, m.note, "went → meaning of go");
        eq("go", m.noteWord, "the base is named");
        ok(!m.senses.isEmpty(), "go has senses");

        // a German word with no entry: the meaning of its English translation
        l = lookup("Marketingassistentin", Lang.DE, "go", "رفتن");
        m = MeaningSource.load(l, fake("entries/en/go", DICT_GO), r);
        ok(!m.senses.isEmpty(), "compound has a meaning");

        // a sentence: its words
        l = lookup("Ich habe einen Hund", Lang.DE, "I have a dog", "من یک سگ دارم");
        m = MeaningSource.load(l, fake(), r);
        eq(Meaning.NOTE_WORDS, m.note, "a sentence shows its words");
        ok(!m.senses.isEmpty(), "with entries");

        // a number: never Wikipedia (۲۱ is not the card game), its value and ordinal
        l = Resolver.number(21, Lang.FA, 1);
        m = MeaningSource.load(l, fake("wikipedia", "{\"type\":\"standard\",\"extract\":\"A card game.\"}"), r);
        eq(Meaning.NOTE_NUMBER, m.note, "a number explains itself");
        ok(!m.senses.isEmpty() && m.senses.get(0).text.contains("21"), "with its digits");

        // a phrase is not looked up in Wikipedia
        l = lookup("how are you", Lang.EN, "چطوری", "wie geht es dir");
        m = MeaningSource.load(l, fake("wikipedia", "{\"type\":\"standard\",\"extract\":\"A health program.\"}"), r);
        ok(!Meaning.NOTE_WIKIPEDIA.equals(m.note), "no Wikipedia article for a phrase");
        ok(!m.senses.isEmpty(), "still a meaning");

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

    static String result(Lookup l, Lang t) {
        for (Lookup.Result x : l.results) if (x.lang == t) return x.text;
        return "";
    }
}
