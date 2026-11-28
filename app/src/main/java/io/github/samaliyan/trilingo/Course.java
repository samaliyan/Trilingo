package io.github.samaliyan.trilingo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The learning plan: a course through the words of a level (German or English A1 … C1), most common first,
 * in units of {@value #UNIT} words. Every day a lesson is built for the learner:
 * <ol>
 * <li>words due for review (the course's and the learner's own saved words);</li>
 * <li>weak words: the ones with mistakes, practised the way they went wrong (the article, the spelling, the
 * verb form, saying it);</li>
 * <li>a few new words of the current unit, introduced and practised.</li>
 * </ol>
 * Each word climbs stages (choose its meaning → choose the word → write it → write it with its article or
 * form → say it); a mistake takes it a stage back and the question comes again at the end of the lesson.
 * How many new words come each day follows how the last lessons went. Everything is kept in a file.
 * Pure Java (tested).
 */
final class Course {
    static final int UNIT = 8, MASTERED = 5;
    /** What a mistake was about. */
    static final int MEANING = 0, SPELLING = 1, ARTICLE = 2, FORM = 3, SPEAKING = 4;
    private static final int SKILLS = 5;

    /** Exercise kinds. */
    static final int INTRO = 0, CHOOSE_MEANING = 1, CHOOSE_WORD = 2, WRITE = 3, ARTICLE_Q = 4, FORM_Q = 5, SAY = 6;

    /** Where the learner is with one word. */
    static final class WordState {
        Lang lang;
        /** As shown and saved: "Tisch", "gehen", "house". */
        String word;
        /** "der Tisch" for a German noun, else the word. */
        String display;
        /** Translations, one language a line (English, Persian for German words). */
        String back;
        int stage, right, wrong;
        final int[] mistakes = new int[SKILLS];
        long introduced, seen;
        boolean noun, verb;

        String key() {
            return lang.code + "|" + word.toLowerCase(Locale.ROOT);
        }

        int mistakeCount() {
            int n = 0;
            for (int m : mistakes) n += m;
            return n;
        }

        /** The skill with most mistakes, or -1. */
        int weakest() {
            int best = -1, n = 0;
            for (int i = 0; i < SKILLS; i++) if (mistakes[i] > n) {
                n = mistakes[i];
                best = i;
            }
            return best;
        }
    }

    /** A learner's settings and recent results in one language. */
    static final class Track {
        String level = "A1";
        int dailyNew = 5;
        long day = -1;
        int newToday, lessonsToday;
        /** Right answers in percent of the last lessons, newest last (at most 5). */
        final List<Integer> recent = new ArrayList<>();
    }

    final Map<String, WordState> words = new LinkedHashMap<>();
    final Map<Lang, Track> tracks = new EnumMap<>(Lang.class);
    Lang learning = Lang.DE;
    private final File file;

    Course(File file) {
        this.file = file;
        if (file != null && file.exists()) {
            try {
                read(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                // a broken file starts again
            }
        }
    }

    Track track(Lang l) {
        return tracks.computeIfAbsent(l, k -> new Track());
    }

    WordState state(Lang l, String word) {
        return words.get(l.code + "|" + word.toLowerCase(Locale.ROOT));
    }

    /** Today's counters start again on a new day. */
    void newDay(Lang l, long now) {
        Track t = track(l);
        long d = Progress.day(now);
        if (t.day != d) {
            t.day = d;
            t.newToday = 0;
            t.lessonsToday = 0;
        }
    }

    // ------------------------------------------------------------------ the units of a level

    /** A word of the level list with what the course needs to teach it. */
    static final class Entry {
        final String word, display, back;
        final boolean noun, verb;

        Entry(String word, String display, String back, boolean noun, boolean verb) {
            this.word = word;
            this.display = display;
            this.back = back;
            this.noun = noun;
            this.verb = verb;
        }
    }

    /**
     * The words of a level that can be taught (they have a Persian translation in the built-in dictionary),
     * most common first; nouns with their article. {@code verb} tells a verb (English "house" is not one).
     */
    static List<Entry> entries(Lang l, String level, Levels lv, Words w, Dict d, Nouns nouns,
                               java.util.function.BiPredicate<Lang, String> verb) {
        List<Entry> out = new ArrayList<>();
        if (lv == null || d == null) return out;
        List<String> list = lv.words(level);
        List<Object[]> ranked = new ArrayList<>();
        for (String x : list) {
            Dict.Entry e = d.find(x);
            // the entry of this very word; the German list is in lower case (haus → Haus), but a verb form is
            // not a noun (habe is not die Habe, hast not die Hast)
            if (e == null || !e.word.equalsIgnoreCase(x)) continue;
            if (l == Lang.DE && !e.word.equals(x) && GermanVerbs.isKnownForm(x)) continue;
            if (!content(e) || STOP.contains(x.toLowerCase(Locale.ROOT))) continue;
            String back = d.summary(e.word);
            if (WordQuiz.shown(back).isEmpty() || !Text.hasPersian(back)) continue;
            int r = w == null ? -1 : w.rank(e.word);
            ranked.add(new Object[]{e.word, back, r < 0 ? Integer.MAX_VALUE : r});
        }
        ranked.sort((a, b) -> Integer.compare((Integer) a[2], (Integer) b[2]));
        List<String> seen = new ArrayList<>();
        for (Object[] r : ranked) {
            String word = (String) r[0];
            if (seen.contains(word.toLowerCase(Locale.ROOT))) continue;
            seen.add(word.toLowerCase(Locale.ROOT));
            Nouns.Info n = l == Lang.DE && nouns != null && Character.isUpperCase(word.charAt(0)) ? nouns.exact(word) : null;
            boolean noun = n != null && n.genders.size() == 1;
            String display = noun ? n.articles() + " " + n.noun : word;
            String pos = firstPos(d.find(word));
            boolean v = !noun && "verb".equals(pos) && (l != Lang.DE || Character.isLowerCase(word.charAt(0)))
                    && (verb == null || verb.test(l, word)) && Conjugation.of(l, word.toLowerCase(Locale.ROOT)) != null;
            out.add(new Entry(word, display, (String) r[1], noun, v));
        }
        return out;
    }

    /** Forms of articles and pronouns the level lists keep as words; they are learned in sentences. */
    private static final List<String> STOP = java.util.Arrays.asList("einen", "einem", "einer", "eines", "dem", "den",
            "des", "ihm", "ihn", "ihnen", "dir", "mir", "mich", "dich", "uns", "euch", "sich", "dessen", "deren",
            // conjunctions the dictionary also has as names (die Oder, a river)
            "oder", "und", "aber", "denn", "sondern", "weil", "dass", "ob", "als");

    /** The first word kind of a dictionary entry ("noun", "verb" …), or "". */
    static String firstPos(Dict.Entry e) {
        if (e == null) return "";
        for (List<Lookup.Sense> senses : e.targets.values())
            if (!senses.isEmpty() && senses.get(0).pos != null) return senses.get(0).pos.toLowerCase(Locale.ROOT);
        return "";
    }

    /**
     * A word that means a thing, an action or a quality (noun, verb, adjective, adverb, a greeting) — not
     * an article, pronoun, preposition or conjunction, which have no meaning of their own to learn alone.
     */
    static boolean content(Dict.Entry e) {
        String p = firstPos(e);
        return p.equals("noun") || p.equals("verb") || p.equals("adjective") || p.equals("adverb")
                || p.equals("interjection") || p.equals("phrase") || p.equals("numeral") || p.equals("number");
    }

    /** The level's words in course order (most common first), split in units. */
    static List<List<Entry>> units(List<Entry> levelWords) {
        List<List<Entry>> out = new ArrayList<>();
        for (int i = 0; i < levelWords.size(); i += UNIT)
            out.add(new ArrayList<>(levelWords.subList(i, Math.min(levelWords.size(), i + UNIT))));
        return out;
    }

    /** How far a unit is: words that reached the "write it" stage, of all. */
    int unitDone(Lang l, List<Entry> unit) {
        int n = 0;
        for (Entry e : unit) {
            WordState s = state(l, e.word);
            if (s != null && s.stage >= 3) n++;
        }
        return n;
    }

    /** The first unit not finished (all its words written right at least once), or the last. */
    int currentUnit(Lang l, List<List<Entry>> units) {
        for (int i = 0; i < units.size(); i++) if (unitDone(l, units.get(i)) < units.get(i).size()) return i;
        return Math.max(0, units.size() - 1);
    }

    /** Words of the level the learner has met. */
    int introduced(Lang l, List<Entry> levelWords) {
        int n = 0;
        for (Entry e : levelWords) if (state(l, e.word) != null) n++;
        return n;
    }

    // ------------------------------------------------------------------ a lesson

    static final class Exercise {
        final int kind;
        final WordState word;
        /** Buttons for a choice (meanings, words, articles); empty for writing and saying. */
        final List<String> choices;
        /** For a verb form: the person ("ich") and the tense, and the form asked for. */
        final String person, tense, form;
        /** Asked again after a mistake. */
        final boolean again;

        Exercise(int kind, WordState word, List<String> choices, String person, String tense, String form, boolean again) {
            this.kind = kind;
            this.word = word;
            this.choices = choices;
            this.person = person;
            this.tense = tense;
            this.form = form;
            this.again = again;
        }

        Exercise repeat() {
            return new Exercise(kind, word, choices, person, tense, form, true);
        }
    }

    /**
     * Today's lesson: due words (up to 6), weak words (up to 3), and the new words still allowed today from
     * {@code unit} (introduced, then practised later in the lesson). {@code own} are the learner's own saved
     * words due in this language, as states (they are practised too). {@code pool} gives the wrong choices.
     */
    List<Exercise> lesson(Lang l, List<Entry> unit, List<WordState> due, List<Entry> pool, long now, Random rnd) {
        newDay(l, now);
        Track t = track(l);
        List<Exercise> first = new ArrayList<>(), later = new ArrayList<>();
        List<String> used = new ArrayList<>();
        // 1. due words
        for (WordState s : due) {
            if (used.size() >= 6) break;
            if (used.contains(s.key())) continue;
            used.add(s.key());
            first.add(practise(s, pool, rnd));
        }
        // 2. weak words
        List<WordState> weak = new ArrayList<>();
        for (WordState s : words.values())
            if (s.lang == l && s.mistakeCount() > 0 && s.stage < MASTERED && !used.contains(s.key())) weak.add(s);
        weak.sort((a, b) -> b.mistakeCount() - a.mistakeCount());
        for (WordState s : weak.subList(0, Math.min(3, weak.size()))) {
            used.add(s.key());
            first.add(practise(s, pool, rnd));
        }
        // 3. new words
        int allowed = Math.max(0, t.dailyNew - t.newToday);
        List<Exercise> intros = new ArrayList<>();
        for (Entry e : unit) {
            if (intros.size() >= allowed) break;
            if (state(l, e.word) != null) continue;
            WordState s = fresh(l, e, now);
            intros.add(new Exercise(INTRO, s, Collections.emptyList(), "", "", "", false));
            later.add(new Exercise(CHOOSE_MEANING, s, meanings(s, pool, rnd), "", "", "", false));
            later.add(new Exercise(CHOOSE_WORD, s, wordChoices(s, pool, rnd), "", "", "", false));
        }
        // interleaved: a new word, something to recall, the next new word …; the new words' questions at the end
        List<Exercise> out = new ArrayList<>();
        int a = 0, b = 0;
        while (a < intros.size() || b < first.size()) {
            if (a < intros.size()) out.add(intros.get(a++));
            if (b < first.size()) out.add(first.get(b++));
        }
        // the meaning questions before the word questions, so a word is recognised before it is recalled
        for (Exercise e : later) if (e.kind == CHOOSE_MEANING) out.add(e);
        for (Exercise e : later) if (e.kind == CHOOSE_WORD) out.add(e);
        return out;
    }

    /** A lesson of one kind of practice: words with that kind of mistake first, then words already met. */
    List<Exercise> drill(Lang l, int skill, List<WordState> extra, List<Entry> pool, Random rnd, int max) {
        List<WordState> list = new ArrayList<>();
        for (WordState s : words.values()) if (s.lang == l && (skill < 0 ? s.mistakeCount() > 0 : s.mistakes[skill] > 0)) list.add(s);
        list.sort((a, b) -> b.mistakeCount() - a.mistakeCount());
        if (skill >= 0) {
            List<WordState> rest = new ArrayList<>();
            for (WordState s : words.values()) if (s.lang == l && !list.contains(s)) rest.add(s);
            if (extra != null) for (WordState s : extra) if (!list.contains(s) && !rest.contains(s)) rest.add(s);
            Collections.shuffle(rest, rnd);
            list.addAll(rest);
        }
        List<Exercise> out = new ArrayList<>();
        for (WordState s : list) {
            if (out.size() >= max) break;
            Exercise e;
            switch (skill) {
                case ARTICLE: e = s.noun ? new Exercise(ARTICLE_Q, s, ARTICLES, "", "", "", false) : null; break;
                case FORM: e = s.verb ? form(s, rnd) : null; break;
                case SPEAKING: e = new Exercise(SAY, s, Collections.emptyList(), "", "", "", false); break;
                case SPELLING: e = new Exercise(WRITE, s, Collections.emptyList(), "", "", "", false); break;
                default: e = practise(s, pool, rnd);
            }
            if (e != null) out.add(e);
        }
        return out;
    }

    static final List<String> ARTICLES = java.util.Arrays.asList("der", "die", "das");

    /** The exercise that moves this word on: by its stage, or by the kind of mistake made with it. */
    Exercise practise(WordState s, List<Entry> pool, Random rnd) {
        int weak = s.weakest();
        if (weak == ARTICLE && s.noun) return new Exercise(ARTICLE_Q, s, ARTICLES, "", "", "", false);
        if (weak == FORM && s.verb) {
            Exercise f = form(s, rnd);
            if (f != null) return f;
        }
        if (weak == SPELLING) return new Exercise(WRITE, s, Collections.emptyList(), "", "", "", false);
        if (weak == SPEAKING) return new Exercise(SAY, s, Collections.emptyList(), "", "", "", false);
        switch (Math.min(s.stage, 4)) {
            case 0: return new Exercise(CHOOSE_MEANING, s, meanings(s, pool, rnd), "", "", "", false);
            case 1: return new Exercise(CHOOSE_WORD, s, wordChoices(s, pool, rnd), "", "", "", false);
            case 2: return new Exercise(WRITE, s, Collections.emptyList(), "", "", "", false);
            case 3:
                if (s.noun) return new Exercise(ARTICLE_Q, s, ARTICLES, "", "", "", false);
                if (s.verb) {
                    Exercise f = form(s, rnd);
                    if (f != null) return f;
                }
                return new Exercise(WRITE, s, Collections.emptyList(), "", "", "", false);
            default: return new Exercise(SAY, s, Collections.emptyList(), "", "", "", false);
        }
    }

    private static Exercise form(WordState s, Random rnd) {
        Conjugation c = Conjugation.of(s.lang, s.word.toLowerCase(Locale.ROOT));
        if (c == null) return null;
        List<Conjugation.Tense> ok = new ArrayList<>();
        for (Conjugation.Tense t : c.tenses)
            if (t.name.equals("Present") || t.name.equals("Simple past") || t.name.equals("Perfect")) ok.add(t);
        if (ok.isEmpty()) return null;
        Conjugation.Tense t = ok.get(rnd.nextInt(ok.size()));
        int p = rnd.nextInt(t.persons.length);
        String f = t.form(p);
        if (f == null || f.isEmpty() || f.equals("—")) return null;
        return new Exercise(FORM_Q, s, Collections.emptyList(), t.persons[p], t.name, f, false);
    }

    /** The translation shown for a word: its Persian line when there is one. */
    static String meaning(WordState s) {
        return WordQuiz.shown(s.back);
    }

    /** Four meanings, one right, in random order. */
    static List<String> meanings(WordState s, List<Entry> pool, Random rnd) {
        List<String> out = new ArrayList<>();
        String right = meaning(s);
        out.add(right);
        List<Entry> p = new ArrayList<>(pool);
        Collections.shuffle(p, rnd);
        for (Entry e : p) {
            if (out.size() >= 4) break;
            String m = WordQuiz.shown(e.back);
            if (m.isEmpty() || out.contains(m) || e.word.equalsIgnoreCase(s.word)) continue;
            out.add(m);
        }
        Collections.shuffle(out, rnd);
        return out;
    }

    /** Four words, one right (nouns among nouns, with their articles), in random order. */
    static List<String> wordChoices(WordState s, List<Entry> pool, Random rnd) {
        List<String> out = new ArrayList<>();
        out.add(s.display);
        List<Entry> p = new ArrayList<>(pool);
        Collections.shuffle(p, rnd);
        for (int pass = 0; pass < 2 && out.size() < 4; pass++) {
            for (Entry e : p) {
                if (out.size() >= 4) break;
                if (pass == 0 && e.noun != s.noun) continue;                  // like with like first
                if (out.contains(e.display) || e.word.equalsIgnoreCase(s.word)) continue;
                out.add(e.display);
            }
        }
        Collections.shuffle(out, rnd);
        return out;
    }

    private WordState fresh(Lang l, Entry e, long now) {
        WordState s = new WordState();
        s.lang = l;
        s.word = e.word;
        s.display = e.display;
        s.back = e.back;
        s.noun = e.noun;
        s.verb = e.verb;
        s.introduced = now;
        return s;
    }

    /** A word met for the first time (after its introduction): counted as one of today's new words. */
    void met(WordState s, long now) {
        if (words.containsKey(s.key())) return;
        words.put(s.key(), s);
        newDay(s.lang, now);
        track(s.lang).newToday++;
        s.seen = now;
    }

    /** An own saved word practised in a lesson becomes part of the course (its mistakes are kept). */
    WordState adopt(Lang l, String word, String display, String back, boolean noun, boolean verb) {
        WordState s = state(l, word);
        if (s != null) return s;
        s = new WordState();
        s.lang = l;
        s.word = word;
        s.display = display;
        s.back = back == null ? "" : back;
        s.noun = noun;
        s.verb = verb;
        s.stage = 1;
        words.put(s.key(), s);
        return s;
    }

    /** The skill an exercise tests. */
    static int skill(int kind) {
        switch (kind) {
            case WRITE: return SPELLING;
            case ARTICLE_Q: return ARTICLE;
            case FORM_Q: return FORM;
            case SAY: return SPEAKING;
            default: return MEANING;
        }
    }

    /**
     * An answer. Right: one stage up (once per lesson for each word, so a word is not "learned" in one
     * sitting), and an old mistake of that kind is forgiven; ALMOST counts right but keeps a spelling note;
     * wrong: a stage back and a mistake of that kind.
     */
    void answer(Exercise e, int verdict, boolean firstThisLesson, long now) {
        WordState s = e.word;
        s.seen = now;
        int skill = skill(e.kind);
        if (verdict == WordQuiz.WRONG) {
            s.wrong++;
            s.mistakes[skill]++;
            s.stage = Math.max(0, s.stage - 1);
            return;
        }
        s.right++;
        if (verdict == WordQuiz.ALMOST && skill == SPELLING) s.mistakes[SPELLING] = Math.max(1, s.mistakes[SPELLING]);
        else if (s.mistakes[skill] > 0) s.mistakes[skill]--;
        if (firstThisLesson && !e.again) s.stage = Math.min(MASTERED, s.stage + 1);
    }

    /**
     * The end of a lesson: how it went decides tomorrow's new words — fewer after two hard lessons, one more
     * after two easy ones (3 to 10). Returns true when the level looks too easy (time to move up).
     */
    boolean finished(Lang l, int rightPercent, long now) {
        Track t = track(l);
        newDay(l, now);
        t.lessonsToday++;
        t.recent.add(rightPercent);
        while (t.recent.size() > 5) t.recent.remove(0);
        int n = t.recent.size();
        if (n >= 2 && t.recent.get(n - 1) < 60 && t.recent.get(n - 2) < 60) t.dailyNew = Math.max(3, t.dailyNew - 2);
        else if (n >= 2 && t.recent.get(n - 1) >= 90 && t.recent.get(n - 2) >= 90) t.dailyNew = Math.min(10, t.dailyNew + 1);
        return n >= 3 && t.recent.get(n - 1) >= 95 && t.recent.get(n - 2) >= 95 && t.recent.get(n - 3) >= 95;
    }

    /** The words with mistakes, most first. */
    List<WordState> weak(Lang l) {
        List<WordState> out = new ArrayList<>();
        for (WordState s : words.values()) if (s.lang == l && s.mistakeCount() > 0) out.add(s);
        out.sort((a, b) -> b.mistakeCount() - a.mistakeCount());
        return out;
    }

    // ------------------------------------------------------------------ the file

    void save() {
        if (file == null) return;
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(toJson());
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            tmp.renameTo(file);
        }
    }

    String toJson() {
        StringBuilder b = new StringBuilder("{\"learning\":").append(Json.quote(learning.code)).append(",\"tracks\":{");
        boolean first = true;
        for (Map.Entry<Lang, Track> e : tracks.entrySet()) {
            Track t = e.getValue();
            if (!first) b.append(',');
            first = false;
            b.append(Json.quote(e.getKey().code)).append(":{\"level\":").append(Json.quote(t.level))
                    .append(",\"new\":").append(t.dailyNew).append(",\"day\":").append(t.day)
                    .append(",\"newToday\":").append(t.newToday).append(",\"lessons\":").append(t.lessonsToday)
                    .append(",\"recent\":[");
            for (int i = 0; i < t.recent.size(); i++) b.append(i > 0 ? "," : "").append(t.recent.get(i));
            b.append("]}");
        }
        b.append("},\"words\":[");
        first = true;
        for (WordState s : words.values()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"l\":").append(Json.quote(s.lang.code)).append(",\"w\":").append(Json.quote(s.word))
                    .append(",\"d\":").append(Json.quote(s.display)).append(",\"b\":").append(Json.quote(s.back))
                    .append(",\"s\":").append(s.stage).append(",\"r\":").append(s.right).append(",\"x\":").append(s.wrong)
                    .append(",\"i\":").append(s.introduced).append(",\"t\":").append(s.seen)
                    .append(",\"n\":").append(s.noun ? 1 : 0).append(",\"v\":").append(s.verb ? 1 : 0).append(",\"m\":[");
            for (int i = 0; i < SKILLS; i++) b.append(i > 0 ? "," : "").append(s.mistakes[i]);
            b.append("]}");
        }
        return b.append("]}").toString();
    }

    private void read(String json) {
        Map<String, Object> m = Json.obj(Json.parse(json));
        if (m == null) return;
        Lang l = Lang.of(Json.str(m, "learning"));
        if (l != null && l != Lang.FA) learning = l;
        Map<String, Object> ts = Json.obj(m.get("tracks"));
        if (ts != null) {
            for (Map.Entry<String, Object> e : ts.entrySet()) {
                Lang tl = Lang.of(e.getKey());
                Map<String, Object> x = Json.obj(e.getValue());
                if (tl == null || x == null) continue;
                Track t = track(tl);
                String lv = Json.str(x, "level");
                if (lv != null) t.level = lv;
                t.dailyNew = (int) Json.num(x, "new", 5);
                t.day = (long) Json.num(x, "day", -1);
                t.newToday = (int) Json.num(x, "newToday", 0);
                t.lessonsToday = (int) Json.num(x, "lessons", 0);
                List<Object> r = Json.arr(x, "recent");
                if (r != null) for (Object o : r) if (o instanceof Number) t.recent.add(((Number) o).intValue());
            }
        }
        List<Object> ws = Json.arr(m, "words");
        if (ws == null) return;
        for (Object o : ws) {
            Map<String, Object> x = Json.obj(o);
            Lang wl = x == null ? null : Lang.of(Json.str(x, "l"));
            String w = x == null ? null : Json.str(x, "w");
            if (wl == null || w == null) continue;
            WordState s = new WordState();
            s.lang = wl;
            s.word = w;
            s.display = Json.str(x, "d") == null ? w : Json.str(x, "d");
            s.back = Json.str(x, "b") == null ? "" : Json.str(x, "b");
            s.stage = (int) Json.num(x, "s", 0);
            s.right = (int) Json.num(x, "r", 0);
            s.wrong = (int) Json.num(x, "x", 0);
            s.introduced = (long) Json.num(x, "i", 0);
            s.seen = (long) Json.num(x, "t", 0);
            s.noun = Json.num(x, "n", 0) == 1;
            s.verb = Json.num(x, "v", 0) == 1;
            List<Object> mm = Json.arr(x, "m");
            if (mm != null) for (int i = 0; i < Math.min(SKILLS, mm.size()); i++)
                if (mm.get(i) instanceof Number) s.mistakes[i] = ((Number) mm.get(i)).intValue();
            words.put(s.key(), s);
        }
    }
}
