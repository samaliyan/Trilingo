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
        return Words.guessLatin(en, de, text);
    }

    /** German umlaut correction of typed text (null if none), for automatic or German input. */
    static Umlauts.Fix umlauts(String text, Lang forced) {
        if (forced != null && forced != Lang.DE) return null;
        return Umlauts.fix(text, de, forced == Lang.DE ? null : en);
    }

    /** The closest known word for a typo in this language, or null. */
    static String closest(String typed, Lang lang) {
        String t = Text.clean(typed);
        // An English word written with a capital is usually a name: no guessing.
        if (t.isEmpty() || (lang == Lang.EN && Character.isUpperCase(t.charAt(0)))) return null;
        Words w = words(lang);
        return w == null ? null : w.closest(typed);
    }
}
