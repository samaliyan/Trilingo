package io.github.samaliyan.trilingo;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The built-in word data (word lists of the three languages, German nouns), loaded once per process
 * in the background. Until it is loaded every method answers "nothing", so the screen never waits for it.
 */
final class Lexicon {
    private Lexicon() { }

    private static volatile Words en, de, fa;
    private static volatile Nouns nouns;
    private static volatile Phrasal phrasal;
    private static volatile Grammar grammar;
    private static volatile Levels enLevels, deLevels;
    private static volatile Dict enDict, deDict;
    private static volatile Resolver resolver;
    private static boolean started;
    private static final List<Runnable> waiting = new ArrayList<>();

    /** Starts loading (once); {@code done} runs on the loading thread when everything is there. */
    static synchronized void load(Context c, Runnable done) {
        if (grammar != null) {                         // set last: everything is loaded
            if (done != null) done.run();
            return;
        }
        if (done != null) waiting.add(done);
        if (started) return;
        started = true;
        AssetManager am = c.getApplicationContext().getAssets();
        new Thread(() -> {
            try {
                en = Words.read(Lang.EN, am.open("words_en.txt"));
                de = Words.read(Lang.DE, am.open("words_de.txt"));
                fa = Words.read(Lang.FA, am.open("words_fa.txt"));
                phrasal = Phrasal.read(am.open("phrasal_en.txt"));
                nouns = Nouns.read(am.open("de_nouns.txt"));
            } catch (IOException | RuntimeException e) {
                // without the lists the app works as before (no suggestions, no articles)
            }
            try {
                enLevels = Levels.read(am.open("levels_en.txt"));
                deLevels = Levels.read(am.open("levels_de.txt"));
            } catch (IOException | RuntimeException e) {
                // no levels shown
            }
            try {
                enDict = Dict.read(Lang.EN, am.open("dict_en.txt"));
                deDict = Dict.read(Lang.DE, am.open("dict_de.txt"));
            } catch (IOException | RuntimeException e) {
                // no offline dictionary: answers come from the translation service only
            }
            resolver = new Resolver(en, de, nouns, enDict, deDict);
            grammar = readGrammar(am);
            List<Runnable> run;
            synchronized (Lexicon.class) {
                run = new ArrayList<>(waiting);
                waiting.clear();
            }
            for (Runnable r : run) r.run();
        }, "lexicon").start();
    }

    static Words words(Lang l) {
        switch (l) {
            case EN: return en;
            case DE: return de;
            default: return fa;
        }
    }

    static Nouns nouns() {
        return nouns;
    }

    static Phrasal phrasal() {
        return phrasal;
    }

    static Dict dict(Lang l) {
        return l == Lang.EN ? enDict : l == Lang.DE ? deDict : null;
    }

    /**
     * The word from the built-in dictionary, or null. {@code source} null = automatic: Persian script is
     * Persian; a Latin word only one dictionary has is taken from it. A word both have ("Gift", "Rat") is
     * left to the translation service unless {@code anyway} (then the more common one wins).
     */
    static Lookup dictionary(String query, Lang source, boolean anyway, long now) {
        return Dict.choose(query, source, anyway, now, enDict, deDict, en, de);
    }

    /**
     * What typed text is (number, umlauts, compound typo, language) — see {@link Resolver}. Before the lists
     * are loaded: the text as it is.
     */
    static Resolver resolver() {
        Resolver r = resolver;
        return r != null ? r : new Resolver(en, de, nouns, enDict, deDict);
    }

    /** CEFR level of a word ("B1"), or null. */
    static String level(String word, Lang lang) {
        Levels l = lang == Lang.EN ? enLevels : lang == Lang.DE ? deLevels : null;
        return l == null ? null : l.of(word, lang, nouns);
    }

    static Levels germanLevels() {
        return deLevels;
    }

    static Levels levels(Lang l) {
        return l == Lang.EN ? enLevels : l == Lang.DE ? deLevels : null;
    }

    /** The right case of a German word for dictionary pages ("tisch" → "Tisch"). */
    static String germanCase(String w) {
        return resolver().germanCase(w);
    }

    /** The grammar lessons (assets/grammar/*.txt), or null while loading. */
    static Grammar grammar() {
        return grammar;
    }

    private static Grammar readGrammar(AssetManager am) {
        List<Grammar.Lesson> all = new ArrayList<>();
        try {
            String[] files = am.list("grammar");
            if (files != null) {
                java.util.Arrays.sort(files);
                for (int i = 0; i < files.length; i++)
                    if (files[i].endsWith(".txt")) all.addAll(Grammar.parse(am.open("grammar/" + files[i]), i * 1000));
            }
        } catch (IOException | RuntimeException e) {
            // no lessons: the Grammar screen says so
        }
        return new Grammar(all);
    }

    /** Lessons for a looked-up word (empty while loading). */
    static List<Grammar.Lesson> lessonsFor(Lookup l, Map<Lang, String> verbs, int max) {
        Grammar g = grammar;
        if (g == null) return new ArrayList<>();
        return g.forLookup(l, verbs, nouns, de, en, max);
    }

    /** Lessons for what is typed in the grammar search. */
    static List<Grammar.Lesson> searchGrammar(String query) {
        Grammar g = grammar;
        if (g == null) return new ArrayList<>();
        return g.search(query, nouns, de, en);
    }

    /**
     * The verb families to show for a lookup's verbs: English phrasal verbs and German prefix verbs.
     * Empty when there are none (or the lists are not loaded yet).
     */
    static Map<Lang, List<Phrasal.Entry>> families(Map<Lang, String> verbs) {
        Map<Lang, List<Phrasal.Entry>> out = new LinkedHashMap<>();
        Phrasal p = phrasal;
        if (p != null && verbs.containsKey(Lang.EN)) {
            List<Phrasal.Entry> e = p.english(verbs.get(Lang.EN));
            if (!e.isEmpty()) out.put(Lang.EN, e);
        }
        if (de != null && verbs.containsKey(Lang.DE)) {
            List<Phrasal.Entry> g = Phrasal.german(verbs.get(Lang.DE), de, 14);
            if (g.size() >= 2) out.put(Lang.DE, g);
        }
        return out;
    }

    /**
     * Words that start with what is being typed: in the chosen language, or for automatic Latin text
     * English and German together, most frequent first (the same word once).
     */
    static List<String> suggestions(String typed, Lang forced, int max) {
        List<String> out = new ArrayList<>();
        String t = Text.clean(typed);
        if (t.length() < 2 || t.contains(" ")) return out;
        if (forced != null || Text.hasPersian(t)) {
            Words w = words(forced != null ? forced : Lang.FA);
            if (w != null) out.addAll(w.complete(t, max));
        } else {
            Words a = en, b = de;
            List<String> x = a == null ? new ArrayList<>() : a.complete(t, max);
            List<String> y = b == null ? new ArrayList<>() : b.complete(t, max);
            // merge by rank in their own list (both lists come from the same kind of source)
            int i = 0, j = 0;
            Map<String, String> seen = new LinkedHashMap<>();
            while (seen.size() < max && (i < x.size() || j < y.size())) {
                String pick;
                if (j >= y.size() || (i < x.size() && a.rank(x.get(i)) <= b.rank(y.get(j)))) pick = x.get(i++);
                else pick = y.get(j++);
                seen.putIfAbsent(pick.toLowerCase(Locale.ROOT), pick);
            }
            out.addAll(seen.values());
        }
        // what is typed already is no suggestion
        out.removeIf(s -> s.equalsIgnoreCase(t));
        return out;
    }

    /**
     * English or German for Latin text, from the word lists: a word only one of the languages has decides
     * ("Haus" → German, which the translation service sometimes takes for English). Null when unsure
     * (both have it, e.g. "Gift"), so the service decides.
     */
    static Lang guessLatin(String text) {
        return resolver().guess(text);
    }

    /** German umlaut correction of typed text (null if none), for automatic or German input. */
    static Umlauts.Fix umlauts(String text, Lang forced) {
        if (forced != null && forced != Lang.DE) return null;
        return Umlauts.fix(text, de, forced == Lang.DE ? null : en);
    }

    /**
     * The closest known word (or sentence with its misspelt words fixed) for a typo in this language, or
     * null.
     */
    static String closest(String typed, Lang lang) {
        String t = Text.clean(typed);
        // An English word written with a capital is usually a name: no guessing.
        if (t.isEmpty() || (lang == Lang.EN && Character.isUpperCase(t.charAt(0)) && !t.contains(" "))) return null;
        return Spelling.phrase(words(lang), t);
    }

    /** The best correction of one word in English or German (or Persian for Persian letters), or null. */
    static String closestAny(String typed) {
        String t = Text.clean(typed);
        if (t.isEmpty() || Character.isUpperCase(t.charAt(0)) && Text.hasLetter(t) && en != null && de != null
                && !de.known(t) && Spelling.candidates(de, t, 1).isEmpty()) return null;   // a name
        List<Spelling.Candidate> c = spellings(t, null, 1);
        return c.isEmpty() ? null : c.get(0).word;
    }

    /**
     * Up to {@code max} spelling suggestions for one typed word, best first: in the given language, or (null)
     * in English and German together, or Persian for Persian letters.
     */
    static List<Spelling.Candidate> spellings(String typed, Lang lang, int max) {
        String t = Text.clean(typed);
        List<Spelling.Candidate> out = new ArrayList<>();
        if (t.isEmpty() || t.contains(" ")) return out;
        List<Lang> langs = new ArrayList<>();
        if (lang != null) langs.add(lang);
        else if (Text.hasPersian(t)) langs.add(Lang.FA);
        else {
            langs.add(Lang.EN);
            langs.add(Lang.DE);
        }
        for (Lang l : langs) {
            Words w = words(l);
            // known in one of the languages: no typo
            if (w != null && w.known(t)) return new ArrayList<>();
        }
        for (Lang l : langs) out.addAll(Spelling.candidates(words(l), t, max));
        out.sort((a, b) -> Double.compare(a.cost, b.cost));
        List<Spelling.Candidate> best = new ArrayList<>();
        for (Spelling.Candidate c : out) {
            boolean dup = false;
            for (Spelling.Candidate b : best) dup |= b.word.equalsIgnoreCase(c.word);
            if (!dup && best.size() < max) best.add(c);
        }
        return best;
    }
}
