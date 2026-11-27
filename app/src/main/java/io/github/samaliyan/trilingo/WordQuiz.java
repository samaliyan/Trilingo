package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Active practice: the learner produces the answer instead of only remembering it.
 * <ul>
 * <li>{@link #WRITE}: the translation is shown, the word is written (der Tisch / table);</li>
 * <li>{@link #ARTICLE}: der, die or das?</li>
 * <li>{@link #VERB}: the right form of a verb ("ich ___ (gehen, Perfect)" → bin gegangen);</li>
 * <li>{@link #SAY}: the word is said aloud and recognised ({@link #heard}).</li>
 * </ul>
 * Built from the saved words (English and German), with common words of the learner's level when there are
 * few. Pure Java (tested).
 */
final class WordQuiz {
    static final int WRITE = 0, ARTICLE = 1, VERB = 2, SAY = 3;
    static final int RIGHT = 0, ALMOST = 1, WRONG = 2;

    static final class Item {
        final Lang lang;
        final String word, back;

        Item(Lang lang, String word, String back) {
            this.lang = lang;
            this.word = word;
            this.back = back == null ? "" : back;
        }
    }

    static final class Question {
        final int kind;
        final Lang lang;
        /** What is shown ("table · میز", "Tisch", "ich ___"). */
        final String prompt;
        /** A second line: "gehen · Perfect", "German noun". */
        final String hint;
        /** The right answer as shown after answering ("der Tisch", "bin gegangen"). */
        final String answer;
        /** The answers that count as right. */
        final List<String> accepted;
        /** Buttons to choose from (der / die / das), or empty for a typed answer. */
        final List<String> choices;
        /** The word the question is about (to save, hear, look up). */
        final String word;

        Question(int kind, Lang lang, String prompt, String hint, String answer, List<String> accepted,
                 List<String> choices, String word) {
            this.kind = kind;
            this.lang = lang;
            this.prompt = prompt;
            this.hint = hint;
            this.answer = answer;
            this.accepted = accepted;
            this.choices = choices;
            this.word = word;
        }
    }

    private final Nouns nouns;
    private final Random random;
    /** Whether a word is mainly a verb (English "house" is mainly a noun); null: every verb form counts. */
    private final java.util.function.BiPredicate<Lang, String> verb;

    WordQuiz(Nouns nouns, Random random, java.util.function.BiPredicate<Lang, String> verb) {
        this.nouns = nouns;
        this.random = random;
        this.verb = verb;
    }

    /** Up to {@code n} questions of one kind from these words (shuffled); fewer when the words do not allow it. */
    List<Question> make(int kind, List<Item> items, int n) {
        List<Item> pool = new ArrayList<>(items);
        Collections.shuffle(pool, random);
        List<Question> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Item it : pool) {
            if (out.size() >= n) break;
            if (it.lang == Lang.FA || seen.contains(it.lang.code + Text.key(it.word))) continue;
            Question q = question(kind, it);
            if (q == null) continue;
            seen.add(it.lang.code + Text.key(it.word));
            out.add(q);
        }
        return out;
    }

    private Question question(int kind, Item it) {
        String w = Text.clean(it.word);
        if (w.isEmpty() || w.split(" ").length > 3) return null;
        // a German noun is written with a capital (gehen is a verb, das Gehen would be the noun)
        Nouns.Info noun = it.lang == Lang.DE && nouns != null && Character.isUpperCase(w.charAt(0))
                ? nouns.exact(w) : null;
        if (noun != null && !noun.noun.equals(w) && !noun.noun.equalsIgnoreCase(w)) noun = null;
        switch (kind) {
            case WRITE: {
                String shown = shown(it.back);
                if (shown.isEmpty()) return null;
                List<String> ok = new ArrayList<>();
                ok.add(w);
                String answer = w;
                if (noun != null && noun.genders.size() == 1) {
                    answer = noun.articles() + " " + noun.noun;
                    ok.add(answer);
                }
                return new Question(WRITE, it.lang, shown, it.lang == Lang.DE ? "German" : "English", answer, ok,
                        Collections.emptyList(), w);
            }
            case ARTICLE: {
                if (noun == null || noun.genders.size() != 1) return null;
                String a = noun.articles();
                List<String> ok = Collections.singletonList(a);
                return new Question(ARTICLE, Lang.DE, noun.noun, shown(it.back), a + " " + noun.noun, ok,
                        java.util.Arrays.asList("der", "die", "das"), noun.noun);
            }
            case VERB: {
                if (noun != null || Character.isUpperCase(w.charAt(0)) && it.lang == Lang.DE) return null;
                String base = w.toLowerCase(Locale.ROOT).replaceFirst("^to ", "");
                if (verb != null && !verb.test(it.lang, base)) return null;
                Conjugation c = Conjugation.of(it.lang, base);
                if (c == null) return null;
                List<Conjugation.Tense> usable = new ArrayList<>();
                for (Conjugation.Tense t : c.tenses)
                    if (t.name.equals("Present") || t.name.equals("Simple past") || t.name.equals("Perfect")) usable.add(t);
                if (usable.isEmpty()) return null;
                Conjugation.Tense t = usable.get(random.nextInt(usable.size()));
                int p = random.nextInt(t.persons.length);
                String form = t.form(p);
                if (form == null || form.isEmpty() || form.equals("—")) return null;
                List<String> ok = new ArrayList<>();
                for (String f : form.split(" / ")) ok.add(f.trim());
                return new Question(VERB, it.lang, t.persons[p] + " ___", c.verb + " · " + t.name, form, ok,
                        Collections.emptyList(), c.verb);
            }
            case SAY: {
                List<String> ok = new ArrayList<>();
                ok.add(w);
                if (noun != null) ok.add(noun.articles() + " " + noun.noun);
                return new Question(SAY, it.lang, noun != null && noun.genders.size() == 1 ? noun.articles() + " " + noun.noun : w,
                        shown(it.back), w, ok, Collections.emptyList(), w);
            }
            default:
                return null;
        }
    }

    /** The translation to show: the Persian line when there is one, else the first. */
    static String shown(String back) {
        String[] lines = back == null ? new String[0] : back.split("\n");
        String fa = null, first = null;
        for (String l : lines) {
            String x = Text.clean(l);
            if (x.isEmpty()) continue;
            if (first == null) first = x;
            if (fa == null && Text.hasPersian(x)) fa = x;
        }
        return fa != null ? fa : first == null ? "" : first;
    }

    /** RIGHT, ALMOST (one small typing mistake: shown, but counted right) or WRONG. */
    static int check(Question q, String typed) {
        String t = norm(q.lang, typed);
        if (t.isEmpty()) return WRONG;
        for (String a : q.accepted) if (norm(q.lang, a).equals(t)) return RIGHT;
        if (q.kind == ARTICLE) return WRONG;
        for (String a : q.accepted) {
            String n = norm(q.lang, a);
            if (n.length() >= 4 && Spelling.distance(q.lang, t, n, 1.0) <= 1.0) return ALMOST;
        }
        return WRONG;
    }

    /**
     * What the speech recogniser heard, against the word: RIGHT when one of its guesses is the word, ALMOST
     * when one is close (a sound or two), else WRONG.
     */
    static int heard(Question q, List<String> guesses) {
        int best = WRONG;
        for (String g : guesses) {
            String h = norm(q.lang, g);
            for (String a : q.accepted) {
                String n = norm(q.lang, a);
                // the recogniser often adds words around it ("das ist Tisch"): the word in it counts
                if (h.equals(n) || (" " + h + " ").contains(" " + n + " ")) return RIGHT;
                double limit = n.length() <= 4 ? 1.0 : 1.6;
                if (Spelling.distance(q.lang, h, n, limit) <= limit) best = ALMOST;
            }
        }
        return best;
    }

    /** Case, punctuation and extra spaces do not count; ae/oe/ue/ss are as good as ä/ö/ü/ß. */
    static String norm(Lang l, String s) {
        String x = Text.clean(s == null ? "" : s).toLowerCase(Locale.ROOT).replaceAll("[.,!?;:\"„“”«»()]", "").trim();
        if (l == Lang.DE) x = x.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        return x.replaceAll("\\s+", " ");
    }
}
