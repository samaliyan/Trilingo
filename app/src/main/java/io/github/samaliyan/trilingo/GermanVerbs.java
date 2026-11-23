package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * German verb conjugation, offline. Irregular verbs come from a built-in list; regular (weak) verbs follow
 * the rules. Separable (ankommen) and inseparable (verstehen) prefixes and "sich …" verbs are handled.
 * Pure Java.
 */
final class GermanVerbs {
    private GermanVerbs() { }

    static final String[] PERSONS = {"ich", "du", "er/sie/es", "wir", "ihr", "sie/Sie"};
    static final String[] IMP_PERSONS = {"du", "ihr", "Sie"};
    private static final String[] REFL = {"mich", "dich", "sich", "uns", "euch", "sich"};

    /** infinitive | du (if irregular) | er (if irregular) | Präteritum | Partizip II | h = haben, s = sein */
    private static final String[] DATA = {
            "backen|||backte|gebacken|h", "befehlen|befiehlst|befiehlt|befahl|befohlen|h",
            "beginnen|||begann|begonnen|h", "beißen|||biss|gebissen|h", "bergen|birgst|birgt|barg|geborgen|h",
            "betrügen|||betrog|betrogen|h", "biegen|||bog|gebogen|h", "bieten|||bot|geboten|h",
            "binden|||band|gebunden|h", "bitten|||bat|gebeten|h", "blasen|bläst|bläst|blies|geblasen|h",
            "bleiben|||blieb|geblieben|s", "braten|brätst|brät|briet|gebraten|h",
            "brechen|brichst|bricht|brach|gebrochen|h", "brennen|||brannte|gebrannt|h",
            "bringen|||brachte|gebracht|h", "denken|||dachte|gedacht|h", "dringen|||drang|gedrungen|s",
            "dürfen|||durfte|gedurft|h", "empfehlen|empfiehlst|empfiehlt|empfahl|empfohlen|h",
            "essen|isst|isst|aß|gegessen|h", "fahren|fährst|fährt|fuhr|gefahren|s",
            "fallen|fällst|fällt|fiel|gefallen|s", "fangen|fängst|fängt|fing|gefangen|h",
            "finden|||fand|gefunden|h", "fliegen|||flog|geflogen|s", "fliehen|||floh|geflohen|s",
            "fließen|||floss|geflossen|s", "fressen|frisst|frisst|fraß|gefressen|h",
            "frieren|||fror|gefroren|h", "geben|gibst|gibt|gab|gegeben|h", "gehen|||ging|gegangen|s",
            "gelingen|||gelang|gelungen|s", "gelten|giltst|gilt|galt|gegolten|h",
            "genießen|||genoss|genossen|h", "geschehen|geschiehst|geschieht|geschah|geschehen|s",
            "gewinnen|||gewann|gewonnen|h", "gießen|||goss|gegossen|h", "gleichen|||glich|geglichen|h",
            "gleiten|||glitt|geglitten|s", "graben|gräbst|gräbt|grub|gegraben|h",
            "greifen|||griff|gegriffen|h", "haben|||hatte|gehabt|h", "halten|hältst|hält|hielt|gehalten|h",
            "hängen|||hing|gehangen|h", "heben|||hob|gehoben|h", "heißen|||hieß|geheißen|h",
            "helfen|hilfst|hilft|half|geholfen|h", "kennen|||kannte|gekannt|h", "klingen|||klang|geklungen|h",
            "kommen|||kam|gekommen|s", "können|||konnte|gekonnt|h", "kriechen|||kroch|gekrochen|s",
            "laden|lädst|lädt|lud|geladen|h", "lassen|lässt|lässt|ließ|gelassen|h",
            "laufen|läufst|läuft|lief|gelaufen|s", "leiden|||litt|gelitten|h", "leihen|||lieh|geliehen|h",
            "lesen|liest|liest|las|gelesen|h", "liegen|||lag|gelegen|h", "lügen|||log|gelogen|h",
            "meiden|||mied|gemieden|h", "messen|misst|misst|maß|gemessen|h", "mögen|||mochte|gemocht|h",
            "müssen|||musste|gemusst|h", "nehmen|nimmst|nimmt|nahm|genommen|h",
            "nennen|||nannte|genannt|h", "pfeifen|||pfiff|gepfiffen|h", "raten|rätst|rät|riet|geraten|h",
            "reiben|||rieb|gerieben|h", "reißen|||riss|gerissen|h", "reiten|||ritt|geritten|s",
            "rennen|||rannte|gerannt|s", "riechen|||roch|gerochen|h", "rufen|||rief|gerufen|h",
            "saufen|säufst|säuft|soff|gesoffen|h", "scheinen|||schien|geschienen|h",
            "schieben|||schob|geschoben|h", "schießen|||schoss|geschossen|h",
            "schlafen|schläfst|schläft|schlief|geschlafen|h", "schlagen|schlägst|schlägt|schlug|geschlagen|h",
            "schleichen|||schlich|geschlichen|s", "schließen|||schloss|geschlossen|h",
            "schmeißen|||schmiss|geschmissen|h", "schmelzen|schmilzt|schmilzt|schmolz|geschmolzen|s",
            "schneiden|||schnitt|geschnitten|h", "schreiben|||schrieb|geschrieben|h",
            "schreien|||schrie|geschrien|h", "schweigen|||schwieg|geschwiegen|h",
            "schwimmen|||schwamm|geschwommen|s", "schwören|||schwor|geschworen|h",
            "sehen|siehst|sieht|sah|gesehen|h", "sein|||war|gewesen|s", "singen|||sang|gesungen|h",
            "sinken|||sank|gesunken|s", "sitzen|||saß|gesessen|h", "sollen|||sollte|gesollt|h",
            "sprechen|sprichst|spricht|sprach|gesprochen|h", "springen|||sprang|gesprungen|s",
            "stechen|stichst|sticht|stach|gestochen|h", "stehen|||stand|gestanden|h",
            "stehlen|stiehlst|stiehlt|stahl|gestohlen|h", "steigen|||stieg|gestiegen|s",
            "sterben|stirbst|stirbt|starb|gestorben|s", "stinken|||stank|gestunken|h",
            "stoßen|stößt|stößt|stieß|gestoßen|h", "streichen|||strich|gestrichen|h",
            "streiten|||stritt|gestritten|h", "tragen|trägst|trägt|trug|getragen|h",
            "treffen|triffst|trifft|traf|getroffen|h", "treiben|||trieb|getrieben|h",
            "treten|trittst|tritt|trat|getreten|h", "trinken|||trank|getrunken|h", "tun|||tat|getan|h",
            "verderben|verdirbst|verdirbt|verdarb|verdorben|h", "vergessen|vergisst|vergisst|vergaß|vergessen|h",
            "verlieren|||verlor|verloren|h", "verschwinden|||verschwand|verschwunden|s",
            "verzeihen|||verzieh|verziehen|h", "wachsen|wächst|wächst|wuchs|gewachsen|s",
            "waschen|wäschst|wäscht|wusch|gewaschen|h", "weisen|||wies|gewiesen|h",
            "werben|wirbst|wirbt|warb|geworben|h", "werden|||wurde|geworden|s",
            "werfen|wirfst|wirft|warf|geworfen|h", "wiegen|||wog|gewogen|h", "wissen|||wusste|gewusst|h",
            "wollen|||wollte|gewollt|h", "ziehen|||zog|gezogen|h", "zwingen|||zwang|gezwungen|h",
    };

    /** Verbs whose present tense does not follow any rule. */
    private static final Map<String, String[]> PRESENT = new HashMap<>();
    private static final Map<String, String> IMPERATIVE_DU = new HashMap<>();
    private static final Set<String> MODALS = new HashSet<>(Arrays.asList("können", "dürfen", "müssen", "sollen", "wollen", "mögen"));
    private static final Map<String, String[]> TABLE = new HashMap<>();

    static {
        for (String row : DATA) {
            String[] f = row.split("\\|", -1);
            TABLE.put(f[0], f);
        }
        PRESENT.put("sein", new String[]{"bin", "bist", "ist", "sind", "seid", "sind"});
        PRESENT.put("haben", new String[]{"habe", "hast", "hat", "haben", "habt", "haben"});
        PRESENT.put("werden", new String[]{"werde", "wirst", "wird", "werden", "werdet", "werden"});
        PRESENT.put("wissen", new String[]{"weiß", "weißt", "weiß", "wissen", "wisst", "wissen"});
        PRESENT.put("tun", new String[]{"tue", "tust", "tut", "tun", "tut", "tun"});
        PRESENT.put("können", new String[]{"kann", "kannst", "kann", "können", "könnt", "können"});
        PRESENT.put("dürfen", new String[]{"darf", "darfst", "darf", "dürfen", "dürft", "dürfen"});
        PRESENT.put("müssen", new String[]{"muss", "musst", "muss", "müssen", "müsst", "müssen"});
        PRESENT.put("sollen", new String[]{"soll", "sollst", "soll", "sollen", "sollt", "sollen"});
        PRESENT.put("wollen", new String[]{"will", "willst", "will", "wollen", "wollt", "wollen"});
        PRESENT.put("mögen", new String[]{"mag", "magst", "mag", "mögen", "mögt", "mögen"});
        IMPERATIVE_DU.put("sein", "sei");
        IMPERATIVE_DU.put("haben", "hab");
        IMPERATIVE_DU.put("werden", "werde");
        IMPERATIVE_DU.put("wissen", "wisse");
        IMPERATIVE_DU.put("tun", "tu");
    }

    /** Longest first, so "zurück" is found before "zu". */
    private static final String[] SEPARABLE = {"zusammen", "vorbei", "zurück", "herein", "heraus", "hinein",
            "nieder", "weiter", "kennen", "statt", "fern", "fest", "fort", "teil", "auf", "aus", "bei", "ein", "her",
            "hin", "los", "mit", "nach", "vor", "weg", "ab", "an", "zu"};
    private static final String[] INSEPARABLE = {"miss", "zer", "emp", "ent", "ver", "be", "er", "ge"};

    /** Common regular verbs that are often used with a separable prefix (aufmachen, einkaufen …). */
    private static final Set<String> COMMON_WEAK = new HashSet<>(Arrays.asList(
            "machen", "hören", "kaufen", "holen", "stellen", "legen", "setzen", "räumen", "schauen", "passen",
            "packen", "füllen", "wachen", "zahlen", "suchen", "hängen", "probieren", "drücken",
            "laden", "wählen", "decken", "fassen", "führen", "kehren", "lernen", "kochen",
            "steuern", "teilen", "warten", "wenden", "wischen", "zeichnen", "drehen", "spielen", "melden", "bauen",
            "danken", "fragen", "reisen", "sagen", "schalten", "stimmen", "tauchen", "üben", "wecken", "brauchen",
            "nutzen", "atmen", "trocknen", "regen", "richten", "rechnen", "lösen", "zeigen", "bilden"));

    /** Verbs that form the perfect with "sein" although their base verb (or the rule) says "haben". */
    private static final Set<String> SEIN = new HashSet<>(Arrays.asList(
            "aufstehen", "einschlafen", "aufwachen", "erwachen", "umziehen", "einziehen", "ausziehen", "entkommen",
            "entstehen", "erscheinen", "ertrinken", "vergehen", "verfallen", "zerfallen", "entfliehen", "verunglücken",
            "begegnen", "reisen", "wandern", "folgen", "passieren", "landen", "klettern", "stolpern", "joggen",
            "rudern", "segeln", "starten", "stürzen", "abstürzen", "auswandern", "einwandern", "zurückkehren",
            "umkehren", "aufwachsen", "abreisen", "verreisen", "explodieren"));

    // ------------------------------------------------------------------ analysis

    /** The verb split into prefix, base and reflexive marker; null if it does not look like a German infinitive. */
    static final class Parts {
        String full, sep = "", insep = "", base;
        boolean reflexive;
    }

    static Parts analyze(String input) {
        if (input == null) return null;
        String s = Text.clean(input).toLowerCase(Locale.GERMAN);
        Parts p = new Parts();
        if (s.startsWith("sich ")) {
            p.reflexive = true;
            s = s.substring(5).trim();
        }
        if (!isInfinitive(s)) return null;
        p.full = s;
        p.base = s;
        if (TABLE.containsKey(s)) return p;
        for (String pre : SEPARABLE) {
            if (s.length() > pre.length() + 2 && s.startsWith(pre)) {
                String rest = s.substring(pre.length());
                if (TABLE.containsKey(rest) || COMMON_WEAK.contains(rest)) {
                    p.sep = pre;
                    p.base = rest;
                    return p;
                }
            }
        }
        for (String pre : INSEPARABLE) {
            if (s.startsWith(pre)) {
                String rest = s.substring(pre.length());
                if (TABLE.containsKey(rest) || (rest.length() >= 5 && isInfinitive(rest))) {
                    p.insep = pre;
                    p.base = rest;
                    return p;
                }
            }
        }
        return p;
    }

    static boolean isInfinitive(String s) {
        if (s.length() < 3 || !s.matches("[a-zäöüß]+")) return false;
        return s.endsWith("en") || s.endsWith("ern") || s.endsWith("eln") || s.equals("tun") || s.equals("sein");
    }

    static boolean isIrregular(String base) {
        return TABLE.containsKey(base);
    }

    // ------------------------------------------------------------------ rules

    private static String stem(String inf) {
        if (inf.equals("tun")) return "tu";
        if (inf.equals("sein")) return "sei";
        if (inf.endsWith("eln") || inf.endsWith("ern")) return inf.substring(0, inf.length() - 1);
        return inf.substring(0, inf.length() - 2);
    }

    /** Stem needs an extra "e" before -t/-st: arbeit-e-t, öffn-e-t, find-e-t. */
    static boolean needsE(String stem) {
        if (stem.isEmpty()) return false;
        char last = stem.charAt(stem.length() - 1);
        if (last == 't' || last == 'd') return true;
        if ((last == 'm' || last == 'n') && stem.length() >= 2) {
            char before = stem.charAt(stem.length() - 2);
            return "lrhmnaeiouäöüy".indexOf(before) < 0;
        }
        return false;
    }

    private static boolean sibilant(String s) {
        return s.endsWith("s") || s.endsWith("ß") || s.endsWith("z") || s.endsWith("x");
    }

    private static String[] present(String base) {
        String[] fixed = PRESENT.get(base);
        if (fixed != null) return fixed.clone();
        String[] e = TABLE.get(base);
        String st = stem(base);
        String ich = base.endsWith("eln") ? st.substring(0, st.length() - 2) + "le" : st + "e";
        String t = needsE(st) ? "et" : "t";
        String du = e != null && !e[1].isEmpty() ? e[1] : st + (needsE(st) ? "est" : sibilant(st) ? "t" : "st");
        String er = e != null && !e[2].isEmpty() ? e[2] : st + t;
        return new String[]{ich, du, er, base, st + t, base};
    }

    private static String[] past(String base) {
        if (base.equals("sein")) return new String[]{"war", "warst", "war", "waren", "wart", "waren"};
        String[] e = TABLE.get(base);
        String st = stem(base);
        String p = e != null ? e[3] : st + (needsE(st) ? "ete" : "te");
        if (p.endsWith("te")) return new String[]{p, p + "st", p, p + "n", p + "t", p + "n"};
        boolean dt = p.endsWith("t") || p.endsWith("d");
        String du = p + (dt || sibilant(p) || p.endsWith("sch") ? "est" : "st");
        String ihr = p + (dt ? "et" : "t");
        String wir = p + (p.endsWith("e") ? "n" : "en");
        return new String[]{p, du, p, wir, ihr, wir};
    }

    private static String participle(String base) {
        String[] e = TABLE.get(base);
        if (e != null) return e[4];
        String st = stem(base);
        String t = needsE(st) ? "et" : "t";
        if (base.endsWith("ieren")) return st + t;
        return "ge" + st + t;
    }

    /** du-imperative of the base verb; null for modal verbs (they have none). */
    private static String imperativeDu(String base) {
        if (MODALS.contains(base)) return null;
        String fixed = IMPERATIVE_DU.get(base);
        if (fixed != null) return fixed;
        String[] e = TABLE.get(base);
        String st = stem(base);
        if (e != null && !e[1].isEmpty()) {
            String du = e[1];
            boolean umlaut = du.contains("ä") || du.contains("ö") || du.contains("ü");
            if (!umlaut) {                              // e → i / ie verbs: gib, nimm, lies, iss
                if (du.endsWith("sst")) return du.substring(0, du.length() - 1);
                if (du.endsWith("st") && sibilant(st)) return du.substring(0, du.length() - 1);
                if (du.endsWith("st")) return du.substring(0, du.length() - 2);
                return du.substring(0, du.length() - 1);
            }
        }
        if (base.endsWith("eln")) return st.substring(0, st.length() - 2) + "le";
        if (needsE(st)) return st + "e";
        return st;
    }

    private static String aux(Parts p) {
        if (p.reflexive) return "haben";
        String name = p.sep + p.insep + p.base;
        if (SEIN.contains(name)) return "sein";
        if (!p.insep.isEmpty()) return "haben";
        String[] e = TABLE.get(p.base);
        if (e != null) return e[5].equals("s") ? "sein" : "haben";
        return SEIN.contains(p.base) ? "sein" : "haben";
    }

    // ------------------------------------------------------------------ table

    static Conjugation conjugate(String input) {
        Parts p = analyze(input);
        if (p == null) return null;
        String[] pres = present(p.base), pst = past(p.base);
        String pp = participle(p.base);
        String impDu = imperativeDu(p.base);
        String[] presIhr = pres;

        // prefixes and "sich"
        if (!p.insep.isEmpty()) {
            for (int i = 0; i < 6; i++) {
                pres[i] = p.insep + pres[i];
                pst[i] = p.insep + pst[i];
            }
            pp = p.insep + (pp.startsWith("ge") && !p.base.startsWith("ge") || pp.startsWith("gege") ? pp.substring(2) : pp);
            if (impDu != null) impDu = p.insep + impDu;
        } else if (!p.sep.isEmpty()) {
            pp = p.sep + pp;
        }
        String inf = p.full;
        String[] presF = new String[6], pstF = new String[6], perf = new String[6], fut = new String[6];
        String[] auxP = PRESENT.get(aux(p));
        String[] werden = PRESENT.get("werden");
        for (int i = 0; i < 6; i++) {
            String r = p.reflexive ? " " + REFL[i] : "";
            String s = p.sep.isEmpty() ? "" : " " + p.sep;
            presF[i] = pres[i] + r + s;
            pstF[i] = pst[i] + r + s;
            perf[i] = auxP[i] + r + " " + pp;
            fut[i] = werden[i] + r + " " + inf;
        }
        List<Conjugation.Tense> tenses = new ArrayList<>();
        tenses.add(new Conjugation.Tense("Present", "Präsens", PERSONS, presF));
        tenses.add(new Conjugation.Tense("Simple past", "Präteritum", PERSONS, pstF));
        tenses.add(new Conjugation.Tense("Perfect", "Perfekt", PERSONS, perf));
        tenses.add(new Conjugation.Tense("Future", "Futur I", PERSONS, fut));
        if (impDu != null) {
            String s = p.sep.isEmpty() ? "" : " " + p.sep;
            String ihrForm = p.base.equals("sein") ? "seid" : presIhr[4];
            String sieForm = p.base.equals("sein") ? "seien" : (p.insep + p.base);
            String[] imp = {
                    impDu + (p.reflexive ? " dich" : "") + s,
                    ihrForm + (p.reflexive ? " euch" : "") + s,
                    sieForm + " Sie" + (p.reflexive ? " sich" : "") + s};
            tenses.add(new Conjugation.Tense("Imperative", "Imperativ", IMP_PERSONS, imp));
        }
        List<Conjugation.Info> info = new ArrayList<>();
        info.add(new Conjugation.Info("Type", isIrregular(p.base) ? "Irregular" : "Regular"));
        info.add(new Conjugation.Info("Perfect with", aux(p)));
        info.add(new Conjugation.Info("Participle", pp));
        if (!p.sep.isEmpty()) info.add(new Conjugation.Info("Separable prefix", p.sep));
        return new Conjugation(Lang.DE, (p.reflexive ? "sich " : "") + inf, info, tenses);
    }

    // ------------------------------------------------------------------ form → infinitive

    private static Map<String, String> reverse;

    private static synchronized Map<String, String> reverse() {
        if (reverse != null) return reverse;
        Map<String, String> m = new HashMap<>();
        for (String inf : TABLE.keySet()) {
            Conjugation c = conjugate(inf);
            if (c == null) continue;
            for (Conjugation.Tense t : c.tenses) {
                if (!t.name.equals("Present") && !t.name.equals("Simple past") && !t.name.equals("Imperative")) continue;
                for (String f : t.forms) if (!f.contains(" ") && !m.containsKey(f)) m.put(f, inf);
            }
            m.putIfAbsent(participle(inf), inf);
        }
        reverse = m;
        return m;
    }

    /** True if the word is a form of a built-in irregular verb (habe, ging, gegessen …) or such an infinitive. */
    static boolean isKnownForm(String word) {
        String w = Text.clean(word).toLowerCase(Locale.GERMAN);
        return TABLE.containsKey(w) || reverse().containsKey(w);
    }

    /**
     * Infinitive for a verb form: "habe" → "haben", "ging" → "gehen", "machte" → "machen".
     * Regular forms are guessed by their endings, so only call this for words known to be verbs.
     */
    static String lemma(String word) {
        String w = Text.clean(word).toLowerCase(Locale.GERMAN);
        if (w.isEmpty() || !w.matches("[a-zäöüß ]+")) return null;
        if (w.startsWith("sich ") && isInfinitive(w.substring(5))) return w;
        if (w.contains(" ")) return null;
        String known = reverse().get(w);
        if (known != null) return known;
        if (isInfinitive(w)) return w;
        String rest = null;
        if (w.startsWith("ge") && w.endsWith("t") && w.length() > 5) rest = w.substring(2, w.length() - 1);
        else {
            for (String end : new String[]{"test", "teten", "tet", "ten", "te", "st", "t", "e"}) {
                if (w.endsWith(end) && w.length() - end.length() >= 3) {
                    rest = w.substring(0, w.length() - end.length());
                    break;
                }
            }
        }
        if (rest == null) return null;
        if (rest.endsWith("e") && needsE(rest.substring(0, rest.length() - 1))) rest = rest.substring(0, rest.length() - 1);
        return rest.endsWith("el") || rest.endsWith("er") ? rest + "n" : rest + "en";
    }
}
