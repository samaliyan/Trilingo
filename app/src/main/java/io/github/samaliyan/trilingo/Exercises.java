package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Grammar exercises, made from the lessons and the built-in word data so every answer is right by construction:
 * <ul>
 *   <li><b>Practice</b> — multiple-choice questions: fill in a form from a lesson's table ("du ___ (sein)"),
 *       a gap in a lesson example, articles of real nouns, verb forms from the conjugation engines.</li>
 *   <li><b>Find the mistake</b> — one correct sentence (a lesson example) and versions of it with a typical
 *       mistake (wrong article, verb not in second position, wrong verb form …); pick the correct one.</li>
 * </ul>
 * Each call gives a new set; questions already asked in this session are avoided. Pure Java.
 */
final class Exercises {
    static final class Question {
        /** CHOICE: pick one of {@link #options}. ORDER: tap the words ({@link #options}, shuffled) in the right order. */
        static final int CHOICE = 0, ORDER = 1;
        final int type;
        final String prompt, hint, explain, lessonId;
        final List<String> options;
        final int answer;

        Question(String prompt, String hint, List<String> options, int answer, String explain, String lessonId) {
            this(CHOICE, prompt, hint, options, answer, explain, lessonId);
        }

        Question(int type, String prompt, String hint, List<String> options, int answer, String explain, String lessonId) {
            this.type = type;
            this.prompt = prompt;
            this.hint = hint == null ? "" : hint;
            this.options = Collections.unmodifiableList(new ArrayList<>(options));
            this.answer = answer;
            this.explain = explain == null ? "" : explain;
            this.lessonId = lessonId;
        }
    }

    private final Grammar grammar;
    private final Nouns nouns;
    private final Levels deLevels;
    private final Words deWords;
    private final Random rnd;
    private final Set<String> seen = new HashSet<>();

    Exercises(Grammar grammar, Nouns nouns, Levels deLevels, Words deWords, Random rnd) {
        this.grammar = grammar;
        this.nouns = nouns;
        this.deLevels = deLevels;
        this.deWords = deWords;
        this.rnd = rnd;
    }

    // ================================================================== practice

    /**
     * Up to {@code n} new questions for one lesson: its own questions first; lessons with few of them are
     * filled up with "which sentence is correct?" and "put the words in order" from the lesson's examples.
     */
    List<Question> practice(Grammar.Lesson l, int n) {
        List<Question> pool = new ArrayList<>();
        for (int round = 0; round < 3 && pool.size() < n * 3; round++) pool.addAll(candidates(l));
        List<Question> out = pick(pool, n);
        if (out.size() < n) out.addAll(mistakes(l.lang, l, n - out.size()));
        if (out.size() < n) out.addAll(order(l, n - out.size()));
        Collections.shuffle(out, rnd);
        return out;
    }

    /** "Put the words in order" from the lesson's examples (short sentences only). */
    List<Question> order(Grammar.Lesson l, int n) {
        List<String[]> ex = new ArrayList<>(l.examples);
        Collections.shuffle(ex, rnd);
        List<Question> out = new ArrayList<>();
        for (int pass = 0; pass < 2 && out.size() < n; pass++) {
            for (String[] e : ex) {
                if (out.size() >= n) break;
                String k = "O|" + e[0];
                if (pass == 0 && seen.contains(k) || containsQuestion(out, e[0])) continue;
                List<String> words = orderWords(e[0]);
                if (words.size() < 3 || words.size() > 9) continue;
                List<String> shuffled = new ArrayList<>(words);
                for (int t = 0; t < 10 && shuffled.equals(words); t++) Collections.shuffle(shuffled, rnd);
                if (shuffled.equals(words)) continue;
                seen.add(k);
                out.add(new Question(Question.ORDER, "Put the words in order", e[1], shuffled, -1, String.join(" ", words), l.id));
            }
        }
        return out;
    }

    private static boolean containsQuestion(List<Question> qs, String sentence) {
        for (Question q : qs) if (q.explain.equals(sentence)) return true;
        return false;
    }

    /** The words of a sentence for "put in order": final punctuation stays on the last word. */
    static List<String> orderWords(String sentence) {
        List<String> out = new ArrayList<>();
        for (String w : sentence.trim().split("\\s+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** Up to {@code n} new questions from all lessons of a language up to a level (e.g. "B1"). */
    List<Question> practice(Lang lang, String upTo, int n) {
        List<Grammar.Lesson> ls = new ArrayList<>();
        int max = Arrays.asList(Grammar.LEVELS).indexOf(upTo);
        for (Grammar.Lesson l : grammar.of(lang)) if (max < 0 || l.levelIndex() <= max) ls.add(l);
        Collections.shuffle(ls, rnd);
        List<Question> pool = new ArrayList<>();
        for (Grammar.Lesson l : ls) {
            List<Question> c = candidates(l);
            Collections.shuffle(c, rnd);
            pool.addAll(c.subList(0, Math.min(2, c.size())));
            if (pool.size() >= n * 4) break;
        }
        return pick(pool, n);
    }

    /** True when the lesson has its own practice questions. */
    boolean hasPractice(Grammar.Lesson l) {
        return !candidates(l).isEmpty();
    }

    private List<Question> pick(List<Question> pool, int n) {
        Collections.shuffle(pool, rnd);
        List<Question> out = new ArrayList<>();
        Set<String> now = new HashSet<>();
        for (int pass = 0; pass < 2 && out.size() < n; pass++) {
            for (Question q : pool) {
                if (out.size() >= n) break;
                String k = q.prompt + "|" + q.hint;
                if (now.contains(k) || (pass == 0 && seen.contains(k))) continue;
                now.add(k);
                out.add(q);
            }
        }
        for (Question q : out) seen.add(q.prompt + "|" + q.hint);
        return out;
    }

    private List<Question> candidates(Grammar.Lesson l) {
        List<Question> out = new ArrayList<>();
        out.addAll(tableQuestions(l));
        out.addAll(clozeQuestions(l));
        out.addAll(engineQuestions(l));
        return out;
    }

    /** Options with the answer at a random place. */
    private Question make(String prompt, String hint, String answer, List<String> wrong, String explain, String lesson) {
        List<String> opts = new ArrayList<>();
        Set<String> low = new HashSet<>();
        low.add(answer.toLowerCase(Locale.ROOT));
        for (String w : wrong) if (w != null && !w.isEmpty() && low.add(w.toLowerCase(Locale.ROOT)) && opts.size() < 3) opts.add(w);
        if (opts.isEmpty()) return null;
        int at = rnd.nextInt(opts.size() + 1);
        opts.add(at, answer);
        return new Question(prompt, hint, opts, at, explain, lesson);
    }

    private static boolean shortCell(String c) {
        return !c.isEmpty() && !c.equals("–") && c.length() <= 24 && !c.contains(".") && !c.contains("·") && !c.contains("?");
    }

    // ------------------------------------------------------------------ from the lesson's table

    private List<Question> tableQuestions(Grammar.Lesson l) {
        List<Question> out = new ArrayList<>();
        if (l.table.size() < 4) return out;
        String[] head = l.table.get(0);
        if (!TABLE_KINDS.contains(head[0])) return out;
        for (int c = 1; c < head.length; c++) {
            List<String> column = new ArrayList<>();
            for (int r = 1; r < l.table.size(); r++) {
                String[] row = l.table.get(r);
                if (c < row.length && shortCell(row[c]) && !row[c].contains("(") && !column.contains(row[c])) column.add(row[c]);
            }
            if (column.size() < 3 || head[c].isEmpty()) continue;
            for (int r = 1; r < l.table.size(); r++) {
                String[] row = l.table.get(r);
                if (c >= row.length || !column.contains(row[c]) || row[0].isEmpty() || row[0].length() > 28 || row[0].contains(".")) continue;
                // the same form in another row of this column would make two answers right
                int same = 0;
                for (int k = 1; k < l.table.size(); k++) if (c < l.table.get(k).length && l.table.get(k)[c].equals(row[c])) same++;
                if (same > 1) continue;
                List<String> wrong = new ArrayList<>(column);
                wrong.remove(row[c]);
                Collections.shuffle(wrong, rnd);
                String prompt = person(row[0]) ? row[0] + " ___  (" + head[c] + ")" : head[c] + ": " + row[0] + " → ___";
                Question q = make(prompt, head[0].isEmpty() ? "" : head[0] + " · " + head[c], row[c], wrong, row[0] + " · " + head[c] + " = " + row[c], l.id);
                if (q != null) out.add(q);
            }
        }
        return out;
    }

    /** Tables whose rows ask something clear (persons, cases, base forms); not word-order or overview tables. */
    private static final Set<String> TABLE_KINDS = new HashSet<>(Arrays.asList("Person", "", "Kasus", "Nominativ", "Infinitiv",
            "base", "adjective", "Positiv", "singular", "subject", "Verb", "Konnektor", "Verbalstil", "phrasal verb"));

    private static final Set<String> PERSONS = new HashSet<>(Arrays.asList("ich", "du", "er / sie / es", "er/sie/es", "wir", "ihr",
            "sie / Sie", "sie/Sie", "I", "you", "he / she / it", "we", "they", "I / you / we / they", "er / es", "sie", "es"));

    private static boolean person(String s) {
        return PERSONS.contains(s.trim());
    }

    // ------------------------------------------------------------------ gaps in the lesson's examples

    /** A word of an example that is a form in a person/case table; the other forms of its column are the choices. */
    private List<Question> clozeQuestions(Grammar.Lesson l) {
        List<Question> out = new ArrayList<>();
        if (l.table.size() < 4) return out;
        boolean paradigm = true;
        for (int r = 1; r < l.table.size(); r++) {
            String first = l.table.get(r)[0];
            if (!person(first) && !CASES.contains(first)) paradigm = false;
        }
        if (!paradigm) return out;
        for (String[] ex : l.examples) {
            String[] words = ex[0].split(" ");
            for (int i = 0; i < words.length; i++) {
                String w = words[i].replaceAll("[.,!?;:]", "");
                if (w.isEmpty()) continue;
                List<String> column = columnOf(l, w);
                if (column == null) continue;
                List<String> wrong = new ArrayList<>(column);
                wrong.removeIf(x -> x.equalsIgnoreCase(w));
                Collections.shuffle(wrong, rnd);
                String[] gap = words.clone();
                gap[i] = words[i].replace(w, "___");
                boolean cap = Character.isUpperCase(w.charAt(0)) && i == 0;
                List<String> wr = new ArrayList<>();
                for (String x : wrong) wr.add(cap ? Character.toUpperCase(x.charAt(0)) + x.substring(1) : x);
                Question q = make(String.join(" ", gap), ex[1], w, wr, ex[0], l.id);
                if (q != null) out.add(q);
                break;                                     // one gap per sentence
            }
        }
        return out;
    }

    private static final Set<String> CASES = new HashSet<>(Arrays.asList("Nominativ", "Akkusativ", "Dativ", "Genitiv"));

    /** The single-word forms of the table column that contains this word (case ignored), or null. */
    private static List<String> columnOf(Grammar.Lesson l, String w) {
        for (int c = 1; c < l.table.get(0).length; c++) {
            List<String> forms = new ArrayList<>();
            boolean has = false;
            for (int r = 1; r < l.table.size(); r++) {
                if (c >= l.table.get(r).length) continue;
                for (String part : l.table.get(r)[c].split("/")) {
                    String f = part.trim();
                    if (f.isEmpty() || f.contains(" ") || f.equals("–")) continue;
                    if (f.equalsIgnoreCase(w)) has = true;
                    if (!forms.contains(f)) forms.add(f);
                }
            }
            if (has && forms.size() >= 3) return forms;
        }
        return null;
    }

    // ------------------------------------------------------------------ from the word data and the conjugation engines

    private List<Question> engineQuestions(Grammar.Lesson l) {
        List<Question> out = new ArrayList<>();
        switch (l.id) {
            case "de-gender-articles":
            case "de-nominative-accusative":
                for (int i = 0; i < 8; i++) addIf(out, articleQuestion(l.id));
                break;
            case "de-plural":
                for (int i = 0; i < 8; i++) addIf(out, pluralQuestion(l.id));
                break;
            case "de-present-regular":
            case "de-present-stem-change":
            case "de-modal-verbs":
            case "de-praeteritum":
            case "de-praeteritum-basic":
                for (int i = 0; i < 8; i++) addIf(out, germanFormQuestion(l.id));
                break;
            case "de-perfekt":
                for (int i = 0; i < 4; i++) addIf(out, auxQuestion(l.id));
                for (int i = 0; i < 4; i++) addIf(out, participleQuestion(l.id));
                break;
            case "de-separable-verbs":
                for (int i = 0; i < 6; i++) addIf(out, separableQuestion(l.id));
                break;
            case "en-past-simple":
            case "en-irregular-verbs":
            case "en-present-perfect":
                for (int i = 0; i < 8; i++)
                    addIf(out, englishPastQuestion(l.id, l.id.equals("en-present-perfect") || l.id.equals("en-irregular-verbs") && i % 2 == 1));
                break;
            case "en-present-simple":
                for (int i = 0; i < 6; i++) addIf(out, englishThirdQuestion(l.id));
                break;
            default:
                break;
        }
        return out;
    }

    private static void addIf(List<Question> out, Question q) {
        if (q != null) out.add(q);
    }

    private <T> T any(List<T> l) {
        return l.isEmpty() ? null : l.get(rnd.nextInt(l.size()));
    }

    private List<String> nounPool;

    private List<String> nouns() {
        if (nounPool != null) return nounPool;
        nounPool = new ArrayList<>();
        if (deLevels == null || nouns == null) return nounPool;
        for (String level : new String[]{"A1", "A2"}) {
            for (String w : deLevels.words(level)) {
                String cap = Character.toUpperCase(w.charAt(0)) + w.substring(1);
                Nouns.Info n = nouns.lookup(cap);
                boolean noun = deWords == null || deWords.known(w) && Character.isUpperCase(deWords.canonical(w).charAt(0));
                if (noun && n != null && n.noun.equals(cap) && n.genders.size() == 1 && cap.length() >= 3) nounPool.add(cap);
            }
        }
        return nounPool;
    }

    private Question articleQuestion(String lesson) {
        String w = any(nouns());
        if (w == null) return null;
        Nouns.Info n = nouns.lookup(w);
        String a = Nouns.article(n.genders.get(0));
        List<String> wrong = new ArrayList<>(Arrays.asList("der", "die", "das"));
        wrong.remove(a);
        Question q = make("___ " + w, "der, die or das?", a, wrong, a + " " + w, "de-gender-articles");
        return q == null ? null : new Question(q.prompt, q.hint, q.options, q.answer, q.explain, lesson);
    }

    private Question pluralQuestion(String lesson) {
        String w = any(nouns());
        if (w == null) return null;
        Nouns.Info n = nouns.lookup(w);
        String pl = n.plurals.isEmpty() ? "" : n.plurals.get(0);
        if (pl.isEmpty() || pl.equals(w)) return null;
        Set<String> wrong = new LinkedHashSet<>();
        String en = w.endsWith("e") || w.endsWith("el") || w.endsWith("er") ? w + "n" : w + "en";
        for (String x : new String[]{w + "e", en, w + "er", w + "s", umlaut(w) + "e", umlaut(w) + "er"})
            if (!x.equals(pl)) wrong.add(x);
        List<String> wl = new ArrayList<>(wrong);
        Collections.shuffle(wl, rnd);
        return make(Nouns.article(n.genders.get(0)) + " " + w + " → die ___", "Plural", pl, wl, "die " + pl, lesson);
    }

    private static String umlaut(String w) {
        for (int i = w.length() - 1; i >= 0; i--) {
            char c = w.charAt(i);
            if (c == 'a' || c == 'o' || c == 'u') {
                if (c == 'u' && i > 0 && w.charAt(i - 1) == 'a') return w.substring(0, i - 1) + "äu" + w.substring(i + 1);
                return w.substring(0, i) + (c == 'a' ? 'ä' : c == 'o' ? 'ö' : 'ü') + w.substring(i + 1);
            }
        }
        return w;
    }

    private List<String> verbsFor(String lesson) {
        List<String> all = GermanVerbs.practiceVerbs(), out = new ArrayList<>();
        for (String v : all) {
            if (v.contains(" ") || GermanVerbs.analyze(v) == null) continue;
            GermanVerbs.Parts p = GermanVerbs.analyze(v);
            if (!p.sep.isEmpty() || !p.insep.isEmpty()) continue;
            boolean modal = GermanVerbs.isModal(v);
            switch (lesson) {
                case "de-modal-verbs": if (modal) out.add(v); break;
                case "de-present-regular": if (!GermanVerbs.isIrregular(v) && !modal) out.add(v); break;
                case "de-present-stem-change": {
                    Conjugation c = GermanVerbs.conjugate(v);
                    String du = c.tense("Present").forms[1];
                    String stem = v.substring(0, v.length() - 2);
                    if (!modal && GermanVerbs.isIrregular(v) && !du.startsWith(stem) && !v.equals("sein")) out.add(v);
                    break;
                }
                case "de-praeteritum-basic": if (modal || v.equals("sein") || v.equals("haben")) out.add(v); break;
                default: out.add(v);
            }
        }
        return out;
    }

    private Question germanFormQuestion(String lesson) {
        String v = any(verbsFor(lesson));
        if (v == null) return null;
        Conjugation c = GermanVerbs.conjugate(v);
        if (c == null) return null;
        boolean past = lesson.startsWith("de-praeteritum");
        Conjugation.Tense t = c.tense(past ? "Simple past" : "Present");
        int p = rnd.nextInt(6);
        String ans = t.forms[p];
        List<String> wrong = new ArrayList<>();
        for (int i = 0; i < 6; i++) if (!t.forms[i].equals(ans)) wrong.add(t.forms[i]);
        if (past) wrong.add(c.tense("Present").forms[p]);
        Collections.shuffle(wrong, rnd);
        String who = GermanVerbs.PERSONS[p].replace("er/sie/es", "er").replace("sie/Sie", "sie (they)");
        return make(who + " ___  (" + v + ")", past ? "Präteritum" : "Präsens", ans, wrong, who + " " + ans, lesson);
    }

    private Question auxQuestion(String lesson) {
        String v = any(verbsFor("all"));
        if (v == null) return null;
        Conjugation c = GermanVerbs.conjugate(v);
        String perf = c.tense("Perfect").forms[0];          // "habe gemacht"
        String aux = perf.split(" ")[0];
        String pp = perf.substring(perf.lastIndexOf(' ') + 1);
        String other = aux.equals("habe") ? "bin" : "habe";
        return make("ich ___ " + pp + "  (" + v + ")", "Perfekt: haben or sein?", aux, Collections.singletonList(other), "ich " + aux + " " + pp, lesson);
    }

    private Question participleQuestion(String lesson) {
        String v = any(verbsFor("all"));
        if (v == null) return null;
        Conjugation c = GermanVerbs.conjugate(v);
        String perf = c.tense("Perfect").forms[0];
        String pp = perf.substring(perf.lastIndexOf(' ') + 1);
        String stem = v.endsWith("en") ? v.substring(0, v.length() - 2) : v.substring(0, v.length() - 1);
        Set<String> wrong = new LinkedHashSet<>(Arrays.asList("ge" + stem + "t", "ge" + stem + "en", c.tense("Simple past").forms[0], stem + "t"));
        wrong.remove(pp);
        List<String> wl = new ArrayList<>(wrong);
        Collections.shuffle(wl, rnd);
        return make(v + " → Partizip II: ___", "Perfekt", pp, wl, perf.replace("habe", "ich habe").replace("bin ", "ich bin "), lesson);
    }

    private Question separableQuestion(String lesson) {
        List<String> seps = new ArrayList<>();
        for (Grammar.Lesson l : grammar.of(Lang.DE)) if (l.id.equals("de-separable-verbs")) seps.addAll(l.words);
        String v = any(seps);
        if (v == null) return null;
        GermanVerbs.Parts p = GermanVerbs.analyze(v);
        Conjugation c = GermanVerbs.conjugate(v);
        if (p == null || p.sep.isEmpty() || c == null) return null;
        String form = c.tense("Present").forms[0];           // "sehe fern"
        String finite = form.split(" ")[0];
        String right = "Ich " + finite + " heute " + p.sep + ".";
        List<String> wrong = Arrays.asList("Ich " + p.sep + finite + " heute.", "Ich heute " + finite + " " + p.sep + ".", "Ich " + v + " heute.");
        return make("ich + " + v + " + heute", "Which is correct?", right, new ArrayList<>(wrong), right, lesson);
    }

    private Question englishPastQuestion(String lesson, boolean participle) {
        List<String> verbs = new ArrayList<>(EnglishVerbs.irregularVerbs());
        verbs.removeAll(HAS_REGULAR);
        String v = any(verbs);
        if (v == null) return null;
        Conjugation c = EnglishVerbs.conjugate(v);
        if (c == null) return null;
        String past = c.tense("Simple past").forms[0];
        String pp = c.tense("Perfect").forms[0].substring(5);  // "have gone"
        if (v.equals("be")) return null;
        String ans = participle ? pp : past;
        Set<String> wrong = new LinkedHashSet<>(Arrays.asList(regular(v), participle ? past : pp, v, c.tense("Present").forms[2]));
        wrong.remove(ans);
        List<String> wl = new ArrayList<>(wrong);
        Collections.shuffle(wl, rnd);
        String prompt = participle ? "I have ___  (" + v + ")" : "Yesterday I ___  (" + v + ")";
        return make(prompt, participle ? "past participle" : "past simple", ans, wl, (participle ? "have " : "") + ans, lesson);
    }

    /** Irregular verbs that also have an accepted regular past (dreamed, learned, waked …). */
    private static final Set<String> HAS_REGULAR = new HashSet<>(Arrays.asList("wake", "dream", "learn", "burn", "spell", "smell",
            "spill", "spoil", "dwell", "leap", "lean", "kneel", "hang", "prove", "shine", "speed", "light", "knit", "wet", "quit",
            "fit", "bet", "broadcast", "dive", "sneak", "strive", "weave", "thrive", "mow", "sew", "swell", "show", "saw", "shear",
            "plead", "bid", "forecast", "spit", "string", "stink"));

    private static String regular(String v) {
        if (v.endsWith("e")) return v + "d";
        if (v.endsWith("y") && v.length() > 2 && "aeiou".indexOf(v.charAt(v.length() - 2)) < 0) return v.substring(0, v.length() - 1) + "ied";
        return v + "ed";
    }

    private static final List<String> EN_REGULAR = Arrays.asList("watch", "play", "work", "study", "wash", "live", "cook", "try",
            "fix", "miss", "carry", "finish", "listen", "travel", "help", "teach", "go", "do", "have", "kiss", "push", "relax");

    private Question englishThirdQuestion(String lesson) {
        String v = any(EN_REGULAR);
        Conjugation c = EnglishVerbs.conjugate(v);
        String s = c.tense("Present").forms[2];
        Set<String> wrong = new LinkedHashSet<>(Arrays.asList(v, v + "s", v + "es", c.tense("Present continuous").forms[2].substring(3)));
        wrong.remove(s);
        List<String> wl = new ArrayList<>(wrong);
        Collections.shuffle(wl, rnd);
        String who = any(Arrays.asList("She", "He", "My brother", "It"));
        return make(who + " ___ every day.  (" + v + ")", "present simple", s, wl, who + " " + s + " every day.", lesson);
    }

    // ================================================================== find the mistake

    /** Up to {@code n} "which sentence is correct?" questions from the examples of a language's lessons (or one lesson). */
    List<Question> mistakes(Lang lang, Grammar.Lesson only, int n) {
        List<String[]> pool = new ArrayList<>();          // sentence, lesson id
        for (Grammar.Lesson l : grammar.of(lang)) {
            if (only != null && !l.id.equals(only.id)) continue;
            for (String[] ex : l.examples) pool.add(new String[]{ex[0], l.id, ex[1]});
        }
        Collections.shuffle(pool, rnd);
        List<Question> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int pass = 0; pass < 2 && out.size() < n; pass++) {
            for (String[] s : pool) {
                if (out.size() >= n) break;
                if (pass == 0 && seen.contains("M|" + s[0]) || used.contains(s[0])) continue;
                List<String[]> wrong = lang == Lang.DE ? germanMistakes(s[0]) : englishMistakes(s[0]);
                if (wrong.isEmpty()) continue;
                Collections.shuffle(wrong, rnd);
                List<String> opts = new ArrayList<>();
                String why = wrong.get(0)[1];
                for (String[] w : wrong) if (!opts.contains(w[0]) && opts.size() < 2) opts.add(w[0]);
                Question q = make("Which sentence is correct?", s[2], s[0], opts, why, s[1]);
                if (q == null) continue;
                seen.add("M|" + s[0]);
                used.add(s[0]);
                out.add(q);
            }
        }
        return out;
    }

    private static final Set<String> DE_PRON = new HashSet<>(Arrays.asList("ich", "du", "er", "sie", "es", "wir", "ihr", "man"));
    private static final Set<String> DE_DET = new HashSet<>(Arrays.asList("der", "die", "das", "ein", "eine", "mein", "meine", "dein", "deine", "unser", "unsere"));
    private static final Set<String> DE_SUB = new HashSet<>(Arrays.asList("weil", "dass", "wenn", "obwohl", "ob", "damit", "bevor", "nachdem", "als"));

    /** Wrong versions of a correct German sentence, each with what is wrong. */
    List<String[]> germanMistakes(String sentence) {
        List<String[]> out = new ArrayList<>();
        String end = sentence.endsWith(".") || sentence.endsWith("!") || sentence.endsWith("?") ? sentence.substring(sentence.length() - 1) : "";
        String body = end.isEmpty() ? sentence : sentence.substring(0, sentence.length() - 1);
        if (body.contains("–") || body.contains("\"")) return out;
        String[] w = body.split(" ");

        // 1. wrong article before a noun
        if (nouns != null) {
            for (int i = 0; i + 1 < w.length; i++) {
                String art = w[i].toLowerCase(Locale.ROOT);
                if (!Arrays.asList("der", "die", "das").contains(art)) continue;
                String noun = w[i + 1].replaceAll("[,;:]", "");
                if (noun.isEmpty() || !Character.isUpperCase(noun.charAt(0))) continue;
                Nouns.Info n = nouns.lookup(noun);
                if (n == null || n.genders.size() != 1) continue;
                boolean plural = !noun.equals(n.noun);
                Set<String> possible = new HashSet<>();
                if (plural) possible.addAll(Arrays.asList("die", "der", "den"));
                else switch (n.genders.get(0)) {
                    case "m": possible.addAll(Arrays.asList("der", "den", "dem", "des")); break;
                    case "f": possible.addAll(Arrays.asList("die", "der")); break;
                    default: possible.addAll(Arrays.asList("das", "dem", "des"));
                }
                for (String x : new String[]{"der", "die", "das"}) {
                    if (possible.contains(x)) continue;
                    String[] c = w.clone();
                    c[i] = i == 0 ? Character.toUpperCase(x.charAt(0)) + x.substring(1) : x;
                    out.add(new String[]{String.join(" ", c) + end, "Article: " + (plural ? "die " + noun + " is plural" : Nouns.article(n.genders.get(0)) + " " + n.noun)});
                    break;
                }
                break;
            }
        }
        // 2. main clause: the verb is not in second position (subject: a pronoun, or article + noun)
        int v = -1;
        if (DE_PRON.contains(w[0].toLowerCase(Locale.ROOT))) v = 1;
        else if (w.length > 2 && DE_DET.contains(w[0].toLowerCase(Locale.ROOT)) && Character.isUpperCase(w[1].charAt(0))) v = 2;
        if (v > 0 && w.length >= v + 3 && !body.contains(",") && finite(w[v])) {
            List<String> c = new ArrayList<>(Arrays.asList(w));
            String verb = c.remove(v);
            c.add(verb);
            out.add(new String[]{String.join(" ", c) + end, "Word order: in a main clause the verb is in second position"});
        }
        // 3. wrong person: "ich bist"
        if (w.length >= 3 && DE_PRON.contains(w[0].toLowerCase(Locale.ROOT)) && !w[0].equalsIgnoreCase("sie") && !w[0].equalsIgnoreCase("man")) {
            String form = w[1].toLowerCase(Locale.ROOT);
            String inf = finite(form) ? GermanVerbs.lemma(form) : null;
            Conjugation c = inf == null ? null : GermanVerbs.conjugate(inf);
            int idx = Arrays.asList("ich", "du", "er", "wir", "ihr").indexOf(w[0].toLowerCase(Locale.ROOT));
            if (c != null && idx >= 0) {
                for (String tense : new String[]{"Present", "Simple past"}) {
                    Conjugation.Tense t = c.tense(tense);
                    String own = t.forms[idx].split(" ")[0];
                    if (!own.equals(form)) continue;
                    for (int k = 0; k < 6; k++) {
                        String other = t.forms[k].split(" ")[0];
                        if (!other.equals(form)) {
                            String[] x = w.clone();
                            x[1] = other;
                            out.add(new String[]{String.join(" ", x) + end, "Verb form: " + w[0].toLowerCase(Locale.ROOT) + " " + form});
                            break;
                        }
                    }
                    break;
                }
            }
        }
        // 4. subordinate clause: the verb is not at the end
        for (int i = 0; i < w.length; i++) {
            String a = w[i].replace(",", "").toLowerCase(Locale.ROOT);
            if (!DE_SUB.contains(a) || i == 0 || !w[i - 1].endsWith(",")) continue;
            int stop = w.length;
            for (int k = i + 1; k < w.length; k++) if (w[k].endsWith(",")) { stop = k + 1; break; }
            if (stop - i < 4 || stop != w.length) break;
            List<String> c = new ArrayList<>(Arrays.asList(w));
            String verb = c.remove(stop - 1);
            if (!finite(verb)) break;
            c.add(i + 2, verb);
            out.add(new String[]{String.join(" ", c) + end, "Word order: after \"" + a + "\" the verb goes to the end"});
            break;
        }
        return out;
    }

    private Set<String> practiceVerbs;

    /** A present or simple-past form of a known German verb ("spielt", "ging", "hat"). */
    private boolean finite(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (GermanVerbs.isKnownForm(w)) return true;
        if (practiceVerbs == null) practiceVerbs = new HashSet<>(GermanVerbs.practiceVerbs());
        String inf = GermanVerbs.lemma(w);
        if (inf == null || !practiceVerbs.contains(inf) || inf.equals(w)) return false;
        Conjugation c = GermanVerbs.conjugate(inf);
        if (c == null) return false;
        for (String t : new String[]{"Present", "Simple past"}) for (String f : c.tense(t).forms) if (f.equals(w)) return true;
        return false;
    }

    private static final Set<String> EN_HE = new HashSet<>(Arrays.asList("he", "she", "it"));

    /** Wrong versions of a correct English sentence, each with what is wrong. */
    List<String[]> englishMistakes(String sentence) {
        List<String[]> out = new ArrayList<>();
        String[] w = sentence.split(" ");
        for (int i = 0; i + 1 < w.length; i++) {
            String a = w[i].toLowerCase(Locale.ROOT), b = w[i + 1].replaceAll("[.,!?]", "");
            // a / an
            if ((a.equals("a") || a.equals("an")) && b.length() > 1 && !b.toLowerCase(Locale.ROOT).startsWith("u") && !b.toLowerCase(Locale.ROOT).startsWith("h")
                    && !b.toLowerCase(Locale.ROOT).startsWith("eu") && !b.toLowerCase(Locale.ROOT).startsWith("o")) {
                String[] c = w.clone();
                c[i] = (a.equals("a") ? "an" : "a");
                if (Character.isUpperCase(w[i].charAt(0))) c[i] = Character.toUpperCase(c[i].charAt(0)) + c[i].substring(1);
                out.add(new String[]{String.join(" ", c), "a before a consonant sound, an before a vowel sound"});
            }
            // he/she/it + -s
            if (EN_HE.contains(a) && b.length() > 3 && b.endsWith("s") && !b.endsWith("ss") && !b.equals("is") && !b.equals("was") && !b.equals("has")) {
                String base = EnglishVerbs.lemma(b);
                if (base != null && !base.equals(b) && EnglishVerbs.conjugate(base) != null && EnglishVerbs.conjugate(base).tense("Present").forms[2].equals(b)) {
                    String[] c = w.clone();
                    c[i + 1] = w[i + 1].replace(b, base);
                    out.add(new String[]{String.join(" ", c), "he / she / it: the verb needs -s"});
                }
            }
            // be: is / are
            if ((a.equals("i") || a.equals("we") || a.equals("they") || a.equals("you")) && (b.equals("am") || b.equals("are"))) {
                String[] c = w.clone();
                c[i + 1] = w[i + 1].replace(b, "is");
                out.add(new String[]{String.join(" ", c), "to be: I am, you/we/they are, he/she/it is"});
            }
            if (EN_HE.contains(a) && b.equals("is")) {
                String[] c = w.clone();
                c[i + 1] = w[i + 1].replace(b, "are");
                out.add(new String[]{String.join(" ", c), "to be: he / she / it is"});
            }
        }
        // irregular past made regular: went → goed
        for (int i = 0; i < w.length; i++) {
            String b = w[i].replaceAll("[.,!?]", "").toLowerCase(Locale.ROOT);
            if (b.length() < 3) continue;
            for (String v : EnglishVerbs.irregularVerbs()) {
                Conjugation c = EnglishVerbs.conjugate(v);
                if (c == null || v.equals("be") || v.equals("have") || v.equals("do") || HAS_REGULAR.contains(v)) continue;
                if (c.tense("Simple past").forms[0].equals(b) && !b.equals(v) && !b.equals(regular(v))) {
                    String[] x = w.clone();
                    x[i] = w[i].replace(w[i].replaceAll("[.,!?]", ""), regular(v));
                    out.add(new String[]{String.join(" ", x), "Irregular verb: " + v + " – " + b});
                    i = w.length;
                    break;
                }
            }
        }
        return out;
    }

    // ================================================================== checking your own sentence (LanguageTool)

    static final class Issue {
        final int offset, length;
        final String message;
        final List<String> replacements;
        final String rule;

        Issue(int offset, int length, String message, List<String> replacements, String rule) {
            this.offset = offset;
            this.length = length;
            this.message = message;
            this.replacements = replacements;
            this.rule = rule;
        }
    }

    static String checkUrl() {
        return "https://api.languagetool.org/v2/check";
    }

    static String checkBody(String text, Lang lang) {
        String code = lang == Lang.DE ? "de-DE" : lang == Lang.EN ? "en-US" : "fa";
        try {
            return "text=" + java.net.URLEncoder.encode(text, "UTF-8") + "&language=" + code + "&level=default";
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The problems LanguageTool found, in text order. Null when the answer cannot be read. */
    static List<Issue> parseCheck(String body) {
        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(body));
        } catch (RuntimeException e) {
            return null;
        }
        List<Object> ms = Json.arr(m, "matches");
        if (ms == null) return null;
        List<Issue> out = new ArrayList<>();
        for (Object o : ms) {
            Map<String, Object> x = Json.obj(o);
            if (x == null) continue;
            List<String> rep = new ArrayList<>();
            List<Object> rs = Json.arr(x, "replacements");
            if (rs != null) for (Object r : rs) {
                String v = Json.str(Json.obj(r), "value");
                if (v != null && rep.size() < 3) rep.add(v);
            }
            Map<String, Object> rule = Json.obj(x, "rule");
            String msg = Json.str(x, "message");
            out.add(new Issue((int) Json.num(x, "offset", 0), (int) Json.num(x, "length", 0), msg == null ? "" : msg, rep,
                    rule == null || Json.str(rule, "id") == null ? "" : Json.str(rule, "id")));
        }
        return out;
    }

    /** A grammar lesson that fits a LanguageTool rule (by its id and message), or null. */
    static String lessonFor(Issue i, Lang lang) {
        String r = (i.rule + " " + i.message).toUpperCase(Locale.ROOT);
        if (lang == Lang.DE) {
            if (r.contains("HILFSVERB") || r.contains("PERFEKT")) return "de-perfekt";
            if (r.contains("DE_AGREEMENT") || r.contains("ARTIKEL") || r.contains("KONGRUENZ") || r.contains("GENUS")) return "de-gender-articles";
            if (r.contains("VERBSTELLUNG") || r.contains("WORTSTELLUNG") || r.contains("VERB_POSITION")) return "de-word-order-v2";
            if (r.contains("DATIV")) return "de-dative";
            if (r.contains("AKKUSATIV")) return "de-nominative-accusative";
            if (r.contains("KOMMA") || r.contains("NEBENSATZ")) return "de-weil-dass";
            if (r.contains("PERSON") || r.contains("VERB")) return "de-present-regular";
        } else if (lang == Lang.EN) {
            if (r.contains("EN_A_VS_AN") || r.contains(" A ") && r.contains(" AN ")) return "en-articles";
            if (r.contains("HE_VERB_AGR") || r.contains("THIRD") || r.contains("AGREEMENT")) return "en-present-simple";
            if (r.contains("PAST") || r.contains("IRREGULAR")) return "en-irregular-verbs";
            if (r.contains("COMPARATIVE") || r.contains("MORE_")) return "en-comparatives";
            if (r.contains("PERFECT")) return "en-present-perfect";
        }
        return null;
    }
}
