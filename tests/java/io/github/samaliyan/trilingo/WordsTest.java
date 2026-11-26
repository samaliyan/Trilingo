package io.github.samaliyan.trilingo;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Word lists, suggestions while typing, spelling help, umlauts and German articles — with the real data files. */
public final class WordsTest {
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

    public static void main(String[] args) throws IOException {
        long t = System.currentTimeMillis();
        Words en = Words.read(Lang.EN, new FileInputStream(ASSETS + "words_en.txt"));
        Words de = Words.read(Lang.DE, new FileInputStream(ASSETS + "words_de.txt"));
        Words fa = Words.read(Lang.FA, new FileInputStream(ASSETS + "words_fa.txt"));
        Nouns nouns = Nouns.read(new FileInputStream(ASSETS + "de_nouns.txt"));
        long load = System.currentTimeMillis() - t;
        System.out.println("loaded " + en.size() + " EN, " + de.size() + " DE, " + fa.size() + " FA words, " + nouns.size() + " nouns in " + load + " ms");
        ok(en.size() > 20000 && de.size() > 20000 && fa.size() > 10000 && nouns.size() > 40000, "lists are complete");

        // ---------------------------------------------------------------- suggestions while typing
        List<String> s = en.complete("dicti", 5);
        eq("dictionary", s.isEmpty() ? null : s.get(0), "dicti → dictionary first: " + s);
        s = en.complete("Hou", 5);
        ok(s.contains("house"), "case ignored: " + s);
        s = de.complete("bu", 8);
        ok(s.contains("Buch") || s.contains("Bücher"), "bu finds Buch/Bücher (umlauts ignored): " + s);
        s = de.complete("wort", 5);
        ok(s.contains("Wörterbuch") || s.contains("Wort") || s.contains("Worte"), "wort → Wort…: " + s);
        s = de.complete("stras", 5);
        ok(s.contains("Straße"), "ß typed as ss: " + s);
        s = fa.complete("کتا", 5);
        ok(s.contains("کتاب"), "persian prefix: " + s);
        s = fa.complete("كتا", 5);                          // Arabic kaf
        ok(s.contains("کتاب"), "arabic letter forms unified: " + s);
        eq(0, en.complete("", 5).size(), "empty prefix");
        eq(0, en.complete("zzzzqq", 5).size(), "nothing found");
        ok(en.complete("a", 5).size() == 5, "max respected");
        // most frequent first
        s = en.complete("th", 3);
        ok(s.get(0).equals("the"), "most frequent first: " + s);

        // ---------------------------------------------------------------- known words and typos
        ok(en.known("House") && de.known("haus") && de.known("Müde") && fa.known("خانه"), "known words");
        ok(!en.known("hosue"), "typo is not a word");
        eq("house", en.closest("hosue"), "swapped letters");
        eq("dictionary", en.closest("dictionry"), "missing letter");
        eq(null, en.closest("house"), "known word needs no fix");
        eq(null, en.closest("ab"), "too short");
        eq("Schmetterling", de.closest("Schmeterling"), "german missing letter keeps capital");
        ok("کتاب".equals(fa.closest("کتابب")), "persian extra letter: " + fa.closest("کتابب"));

        // ---------------------------------------------------------------- umlauts typed without dots
        Umlauts.Fix f = Umlauts.fix("mude", de, en);
        eq("müde", f == null ? null : f.text, "mude → müde");
        ok(f != null && f.sure, "mude is not a word: sure");
        f = Umlauts.fix("bucher", de, en);
        eq("Bücher", f == null ? null : f.text, "bucher → Bücher");
        f = Umlauts.fix("strasse", de, en);
        eq("Straße", f == null ? null : f.text, "strasse → Straße");
        f = Umlauts.fix("muede", de, en);
        eq("müde", f == null ? null : f.text, "muede (ue spelling) → müde");
        f = Umlauts.fix("ich bin mude", de, en);
        eq("ich bin müde", f == null ? null : f.text, "word inside a sentence");
        f = Umlauts.fix("fur", de, en);
        ok(f != null && f.text.equals("für") && !f.sure, "fur (English word) → only a suggestion für: " + (f == null ? null : f.text));
        eq(null, Umlauts.fix("Haus", de, en), "normal word unchanged");
        eq(null, Umlauts.fix("schon", de, en), "schon is a common word itself (not schön)");
        eq(null, Umlauts.fix("house", de, en), "english word unchanged");
        eq(null, Umlauts.fix("müde", de, en), "already with umlaut");
        eq(null, Umlauts.fix("خانه", de, en), "persian unchanged");
        f = Umlauts.fix("Uber", de, en);
        eq("Über", f == null ? null : f.text, "capital kept");

        // ---------------------------------------------------------------- German articles
        Nouns.Info n = nouns.lookup("Haus");
        eq("das", n == null ? null : n.articles(), "das Haus");
        eq("Häuser", n == null ? null : n.plural(), "Häuser");
        n = nouns.lookup("haus");
        eq("das", n == null ? null : n.articles(), "lower case typed");
        n = nouns.lookup("Häuser");
        eq("Haus", n == null ? null : n.noun, "plural → singular");
        eq("die", nouns.lookup("Tür") == null ? null : nouns.lookup("Tür").articles(), "die Tür");
        eq("der", nouns.lookup("Tisch") == null ? null : nouns.lookup("Tisch").articles(), "der Tisch");
        eq("das", nouns.lookup("Mädchen") == null ? null : nouns.lookup("Mädchen").articles(), "das Mädchen");
        eq("das", nouns.lookup("Wörterbuch") == null ? null : nouns.lookup("Wörterbuch").articles(), "das Wörterbuch");
        n = nouns.lookup("See");
        ok(n != null && n.articles().contains("der") && n.articles().contains("die"), "der / die See: " + (n == null ? null : n.articles()));
        n = nouns.lookup("Kinderzimmertür");                // compound not in the list
        eq("die", n == null ? null : n.articles(), "compound takes the last part's article");
        eq("Kinderzimmertüren", n == null ? null : n.plural(), "compound plural");
        eq(null, nouns.lookup("schnell"), "adjective is not a noun");
        eq(null, nouns.lookup("das Haus"), "phrase is not looked up");
        eq(null, nouns.lookup(""), "empty");

        eq("Tisch", de.canonical("tisch"), "canonical German casing");
        eq("gehen", de.canonical("Gehen"), "verbs stay lower case");
        eq("xyzzy", de.canonical("xyzzy"), "unknown unchanged");

        // ---------------------------------------------------------------- English or German?
        eq(Lang.DE, Words.guessLatin(en, de, "Haus"), "Haus is German");
        eq(Lang.DE, Words.guessLatin(en, de, "Schmetterling"), "Schmetterling is German");
        eq(Lang.EN, Words.guessLatin(en, de, "house"), "house is English");
        eq(Lang.EN, Words.guessLatin(en, de, "dictionary"), "dictionary is English");
        eq(Lang.DE, Words.guessLatin(en, de, "ich habe keine Zeit"), "German sentence");
        eq(Lang.EN, Words.guessLatin(en, de, "I have no time"), "English sentence");
        eq(null, Words.guessLatin(en, de, "Siavash"), "unknown name: the service decides");
        eq(null, Words.guessLatin(en, de, "خانه"), "persian: not this");
        eq(null, Words.guessLatin(null, de, "Haus"), "lists not loaded");

        // ---------------------------------------------------------------- phrasal / prefix verbs
        Phrasal ph = Phrasal.read(new FileInputStream(ASSETS + "phrasal_en.txt"));
        List<Phrasal.Entry> pe = ph.english("give");
        ok(pe.size() >= 4, "give has phrasal verbs");
        ok(pe.stream().anyMatch(e -> e.verb.equals("give up") && e.meaning.contains("quit")), "give up = quit");
        ok(ph.english("to give").size() == pe.size(), "to give → give");
        ok(ph.english("give up").size() == pe.size(), "a phrasal verb shows its family");
        eq("stop trying; quit", ph.meaning("give up"), "meaning of give up");
        ok(ph.english("house").isEmpty(), "noun: none");
        ok(ph.english("look").stream().anyMatch(e -> e.verb.equals("look forward to")), "three-word phrasal verb");
        List<Phrasal.Entry> ge = Phrasal.german("kommen", de, 14);
        List<String> gv = new java.util.ArrayList<>();
        for (Phrasal.Entry e : ge) gv.add(e.verb);
        ok(gv.contains("bekommen") && gv.contains("ankommen"), "kommen → bekommen, ankommen: " + gv);
        ok(!gv.contains("kommen"), "the verb itself is not listed");
        ok(!gv.contains("gekommen"), "the participle is not a prefix verb");
        List<Phrasal.Entry> ge2 = Phrasal.german("ankommen", de, 14);
        ok(ge2.size() == ge.size(), "ankommen shows the kommen family");
        ok(Phrasal.german("Haus", de, 14).isEmpty(), "noun: none");
        // every line of the list is well formed
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(ASSETS + "phrasal_en.txt"))) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            ok(line.matches("[a-z]+( [a-z]+){1,3}\\|[^|]+"), "phrasal line: " + line);
        }

        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static List<String> list(String... s) {
        return Arrays.asList(s);
    }
}
