package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** English verb conjugation, offline: built-in irregular verbs plus the spelling rules. Pure Java. */
final class EnglishVerbs {
    private EnglishVerbs() { }

    static final String[] PERSONS = {"I", "you", "he/she/it", "we", "you", "they"};

    /** base | past | past participle */
    private static final String[] DATA = {
            "arise|arose|arisen", "awake|awoke|awoken", "be|was|been", "bear|bore|born", "beat|beat|beaten",
            "become|became|become", "begin|began|begun", "bend|bent|bent", "bet|bet|bet", "bind|bound|bound",
            "bite|bit|bitten", "bleed|bled|bled", "blow|blew|blown", "break|broke|broken", "breed|bred|bred",
            "bring|brought|brought", "build|built|built", "burst|burst|burst", "buy|bought|bought",
            "catch|caught|caught", "choose|chose|chosen", "come|came|come", "cost|cost|cost", "creep|crept|crept",
            "cut|cut|cut", "deal|dealt|dealt", "dig|dug|dug", "do|did|done", "draw|drew|drawn", "drink|drank|drunk", "drive|drove|driven", "eat|ate|eaten", "fall|fell|fallen", "feed|fed|fed",
            "feel|felt|felt", "fight|fought|fought", "find|found|found", "flee|fled|fled", "fly|flew|flown",
            "forbid|forbade|forbidden", "forget|forgot|forgotten", "forgive|forgave|forgiven", "freeze|froze|frozen",
            "get|got|got", "give|gave|given", "go|went|gone", "grind|ground|ground", "grow|grew|grown",
            "hang|hung|hung", "have|had|had", "hear|heard|heard", "hide|hid|hidden", "hit|hit|hit", "hold|held|held",
            "hurt|hurt|hurt", "keep|kept|kept", "kneel|knelt|knelt", "know|knew|known", "lay|laid|laid",
            "lead|led|led", "leave|left|left",
            "lend|lent|lent", "let|let|let", "lie|lay|lain", "light|lit|lit", "lose|lost|lost", "make|made|made",
            "mean|meant|meant", "meet|met|met", "mistake|mistook|mistaken", "pay|paid|paid", "prove|proved|proven",
            "put|put|put", "quit|quit|quit", "read|read|read", "ride|rode|ridden", "ring|rang|rung", "rise|rose|risen",
            "run|ran|run", "say|said|said", "see|saw|seen", "seek|sought|sought", "sell|sold|sold", "send|sent|sent",
            "set|set|set", "sew|sewed|sewn", "shake|shook|shaken", "shine|shone|shone", "shoot|shot|shot",
            "show|showed|shown", "shrink|shrank|shrunk", "shut|shut|shut", "sing|sang|sung", "sink|sank|sunk",
            "sit|sat|sat", "sleep|slept|slept", "slide|slid|slid", "speak|spoke|spoken", "spend|spent|spent",
            "spin|spun|spun", "spit|spat|spat", "split|split|split", "spread|spread|spread",
            "spring|sprang|sprung", "stand|stood|stood", "steal|stole|stolen", "stick|stuck|stuck", "sting|stung|stung",
            "stink|stank|stunk", "strike|struck|struck", "swear|swore|sworn", "sweep|swept|swept", "swim|swam|swum",
            "swing|swung|swung", "take|took|taken", "teach|taught|taught", "tear|tore|torn", "tell|told|told",
            "think|thought|thought", "throw|threw|thrown", "understand|understood|understood", "wake|woke|woken",
            "wear|wore|worn", "weep|wept|wept", "win|won|won", "wind|wound|wound", "write|wrote|written",
            "withdraw|withdrew|withdrawn", "undertake|undertook|undertaken", "overcome|overcame|overcome",
            "upset|upset|upset", "bless|blessed|blessed", "dive|dived|dived",
    };
    private static final Map<String, String[]> TABLE = new HashMap<>();

    static {
        for (String row : DATA) {
            String[] f = row.split("\\|");
            TABLE.put(f[0], f);
        }
    }

    /** Two-syllable verbs that double the last consonant (begin → beginning). */
    private static final Set<String> DOUBLE = new HashSet<>(Arrays.asList(
            "begin", "forget", "prefer", "admit", "occur", "refer", "control", "regret", "commit", "permit", "submit",
            "omit", "equip", "upset", "compel", "propel", "patrol", "transfer", "deter", "forbid", "acquit"));

    static boolean isIrregular(String base) {
        return TABLE.containsKey(base);
    }

    private static boolean vowel(char c) {
        return "aeiou".indexOf(c) >= 0;
    }

    private static int vowelGroups(String w) {
        int n = 0;
        boolean prev = false;
        for (int i = 0; i < w.length(); i++) {
            boolean v = vowel(w.charAt(i)) || (w.charAt(i) == 'y' && i > 0 && !vowel(w.charAt(i - 1)));
            if (v && !prev) n++;
            prev = v;
        }
        return n;
    }

    /** stop → stopp-, run → runn-: one syllable ending consonant-vowel-consonant. */
    static boolean doubles(String w) {
        if (DOUBLE.contains(w)) return true;
        int n = w.length();
        if (n < 3) return false;
        char a = w.charAt(n - 3), b = w.charAt(n - 2), c = w.charAt(n - 1);
        return vowelGroups(w) == 1 && !vowel(a) && vowel(b) && !vowel(c) && "wxy".indexOf(c) < 0;
    }

    static String third(String b) {
        switch (b) {
            case "be": return "is";
            case "have": return "has";
            case "do": return "does";
            case "go": return "goes";
        }
        if (b.endsWith("y") && b.length() > 1 && !vowel(b.charAt(b.length() - 2))) return b.substring(0, b.length() - 1) + "ies";
        if (b.endsWith("s") || b.endsWith("x") || b.endsWith("z") || b.endsWith("ch") || b.endsWith("sh") || b.endsWith("o")) return b + "es";
        return b + "s";
    }

    static String ing(String b) {
        if (b.equals("be")) return "being";
        if (b.endsWith("ie")) return b.substring(0, b.length() - 2) + "ying";
        if (b.endsWith("e") && !b.endsWith("ee") && !b.endsWith("ye") && !b.endsWith("oe") && b.length() > 2) return b.substring(0, b.length() - 1) + "ing";
        if (doubles(b)) return b + b.charAt(b.length() - 1) + "ing";
        return b + "ing";
    }

    static String pastRegular(String b) {
        if (b.endsWith("e")) return b + "d";
        if (b.endsWith("y") && b.length() > 1 && !vowel(b.charAt(b.length() - 2))) return b.substring(0, b.length() - 1) + "ied";
        if (doubles(b)) return b + b.charAt(b.length() - 1) + "ed";
        return b + "ed";
    }

    static String past(String b) {
        String[] e = TABLE.get(b);
        return e != null ? e[1] : pastRegular(b);
    }

    static String participle(String b) {
        String[] e = TABLE.get(b);
        return e != null ? e[2] : pastRegular(b);
    }

    private static final Set<String> PARTICLES = new HashSet<>(Arrays.asList(
            "up", "down", "out", "off", "on", "in", "away", "back", "over", "around", "about", "through", "along", "after", "into"));
    private static final Set<String> NOT_VERBS = new HashSet<>(Arrays.asList(
            "i", "you", "he", "she", "it", "we", "they", "a", "an", "the", "my", "your", "his", "her", "our", "their", "this", "that"));

    /** One word, or a phrasal verb such as "give up" / "look forward to". */
    static boolean looksLikeVerb(String s) {
        if (s.length() < 2 || !s.matches("[a-z]+(-[a-z]+)?( [a-z]+){0,2}")) return false;
        String[] w = s.split(" ");
        if (NOT_VERBS.contains(w[0])) return false;
        return w.length == 1 || PARTICLES.contains(w[1]);
    }

    /** "to go" → "go"; null when it does not look like an English verb. */
    static String clean(String input) {
        if (input == null) return null;
        String s = Text.clean(input).toLowerCase(Locale.ROOT);
        if (s.startsWith("to ")) s = s.substring(3).trim();
        return looksLikeVerb(s) ? s : null;
    }

    static Conjugation conjugate(String input) {
        String s = clean(input);
        if (s == null) return null;
        // Phrasal verbs ("give up"): the first word is conjugated, the rest follows.
        int sp = s.indexOf(' ');
        String b = sp < 0 ? s : s.substring(0, sp);
        String rest = sp < 0 ? "" : s.substring(sp);
        String third = third(b), pst = past(b), pp = participle(b), ing = ing(b);
        String[] pres = new String[6], cont = new String[6], pastF = new String[6], perf = new String[6], fut = new String[6];
        String[] be = {"am", "are", "is", "are", "are", "are"};
        for (int i = 0; i < 6; i++) {
            boolean he = i == 2;
            if (b.equals("be")) {
                pres[i] = be[i] + rest;
                pastF[i] = (i == 0 || i == 2 ? "was" : "were") + rest;
            } else {
                pres[i] = (he ? third : b) + rest;
                pastF[i] = pst + rest;
            }
            cont[i] = be[i] + " " + ing + rest;
            perf[i] = (he ? "has " : "have ") + pp + rest;
            fut[i] = "will " + b + rest;
        }
        List<Conjugation.Tense> tenses = new ArrayList<>();
        tenses.add(new Conjugation.Tense("Present", "Present simple", PERSONS, pres));
        tenses.add(new Conjugation.Tense("Present continuous", "Present progressive", PERSONS, cont));
        tenses.add(new Conjugation.Tense("Simple past", "Past simple", PERSONS, pastF));
        tenses.add(new Conjugation.Tense("Perfect", "Present perfect", PERSONS, perf));
        tenses.add(new Conjugation.Tense("Future", "will", PERSONS, fut));
        List<Conjugation.Info> info = new ArrayList<>();
        info.add(new Conjugation.Info("Type", isIrregular(b) ? "Irregular" : "Regular"));
        info.add(new Conjugation.Info("Past", pst + rest));
        info.add(new Conjugation.Info("Participle", pp + rest));
        info.add(new Conjugation.Info("-ing form", ing + rest));
        return new Conjugation(Lang.EN, s, info, tenses);
    }

    // ------------------------------------------------------------------ form → base

    private static Map<String, String> reverse;

    private static synchronized Map<String, String> reverse() {
        if (reverse != null) return reverse;
        Map<String, String> m = new HashMap<>();
        for (String[] e : TABLE.values()) {
            m.putIfAbsent(e[1], e[0]);
            m.putIfAbsent(e[2], e[0]);
            m.putIfAbsent(third(e[0]), e[0]);
            m.putIfAbsent(ing(e[0]), e[0]);
        }
        for (String f : new String[]{"am", "is", "are", "was", "were", "been", "being"}) m.put(f, "be");
        m.put("has", "have");
        m.put("did", "do");
        reverse = m;
        return m;
    }

    /** Base form of an English verb form: went → go, studies → study, stopped → stop. Best effort for regular verbs. */
    static String lemma(String word) {
        String w = clean(word);
        if (w == null || w.contains(" ")) return w;
        String known = reverse().get(w);
        if (known != null) return known;
        if (TABLE.containsKey(w)) return w;
        int n = w.length();
        if (w.endsWith("ies") && n > 4) return w.substring(0, n - 3) + "y";
        if (w.endsWith("ied") && n > 4) return w.substring(0, n - 3) + "y";
        if (w.endsWith("ing") && n > 5) return undo(w.substring(0, n - 3));
        if (w.endsWith("ed") && n > 4) return undo(w.substring(0, n - 2));
        if ((w.endsWith("ches") || w.endsWith("shes") || w.endsWith("sses") || w.endsWith("xes") || w.endsWith("zes")) && n > 4) return w.substring(0, n - 2);
        if (w.endsWith("s") && !w.endsWith("ss") && n > 3) return w.substring(0, n - 1);
        return w;
    }

    /** Stem left after removing -ed/-ing: stopp → stop, mak → make, visit → visit. */
    private static String undo(String r) {
        int n = r.length();
        if (n >= 3 && r.charAt(n - 1) == r.charAt(n - 2) && !vowel(r.charAt(n - 1)) && "lsfz".indexOf(r.charAt(n - 1)) < 0) return r.substring(0, n - 1);
        if (n <= 4 && vowelGroups(r) == 1 && n >= 2 && !vowel(r.charAt(n - 1)) && vowel(r.charAt(n - 2))
                && (n == 2 || !vowel(r.charAt(n - 3))) && "wxy".indexOf(r.charAt(n - 1)) < 0) return r + "e";
        return r;
    }
}
