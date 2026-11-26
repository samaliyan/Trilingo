package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persian verb conjugation, offline. The past stem comes from the infinitive (رفتن → رفت); the present stem
 * comes from a built-in list (رفتن → رو) or the -یدن rule (پرسیدن → پرس). Compound verbs (کار کردن) and
 * verbs with a preverb (برگشتن) are handled. Pure Java.
 */
final class PersianVerbs {
    private PersianVerbs() { }

    /** Zero-width non-joiner (half-space) between می and the verb, and before -ام/-ای … */
    static final String Z = String.valueOf((char) 0x200C);
    static final String[] PERSONS = {"من", "تو", "او", "ما", "شما", "آن" + Z + "ها"};
    static final String[] IMP_PERSONS = {"تو", "شما"};
    private static final String[] END = {"م", "ی", "د", "یم", "ید", "ند"};
    private static final String[] PAST_END = {"م", "ی", "", "یم", "ید", "ند"};
    private static final String[] PERFECT_END = {Z + "ام", Z + "ای", " است", Z + "ایم", Z + "اید", Z + "اند"};
    private static final String[] FUTURE = {"خواهم", "خواهی", "خواهد", "خواهیم", "خواهید", "خواهند"};

    /** infinitive | present stem | imperative stem (only where it differs) */
    private static final String[] DATA = {
            "آمدن|آی|آ", "آوردن|آور", "آموختن|آموز", "آراستن|آرا", "آزمودن|آزما", "آسودن|آسا", "آفریدن|آفرین",
            "افتادن|افت", "افروختن|افروز", "افزودن|افزا", "انداختن|انداز", "اندوختن|اندوز", "ایستادن|ایست",
            "بافتن|باف", "بردن|بر", "بستن|بند", "پختن|پز", "پذیرفتن|پذیر", "پرداختن|پرداز", "پنداشتن|پندار",
            "پیوستن|پیوند", "تاختن|تاز", "توانستن|توان", "جستن|جوی|جو", "چیدن|چین", "خاستن|خیز", "خواستن|خواه",
            "خواندن|خوان", "خوردن|خور", "خفتن|خواب", "دادن|ده", "داشتن|دار", "دانستن|دان", "دوختن|دوز",
            "دیدن|بین", "ربودن|ربا", "راندن|ران", "رستن|ره", "رفتن|رو", "ریختن|ریز", "زدن|زن", "زیستن|زی",
            "ساختن|ساز", "ستودن|ستا", "سپردن|سپار", "سوختن|سوز", "شدن|شو", "شستن|شوی", "شکستن|شکن",
            "شکفتن|شکف", "شمردن|شمار", "شناختن|شناس", "شنیدن|شنو", "فرستادن|فرست", "فرمودن|فرما",
            "فروختن|فروش", "فریفتن|فریب", "فشردن|فشار", "کاستن|کاه", "کردن|کن", "کشتن|کش", "کندن|کن",
            "گذاشتن|گذار", "گذشتن|گذر", "گرفتن|گیر", "گریختن|گریز", "گریستن|گری", "گزیدن|گزین", "گشتن|گرد",
            "گشودن|گشا", "گفتن|گوی|گو", "گماشتن|گمار", "ماندن|مان", "مردن|میر", "نشستن|نشین", "نگریستن|نگر",
            "نمودن|نما", "نوشتن|نویس", "نهادن|نه", "یافتن|یاب", "بودن|باش",
    };
    private static final Map<String, String[]> TABLE = new HashMap<>();

    static {
        for (String row : DATA) {
            String[] f = row.split("\\|");
            TABLE.put(f[0], f);
        }
    }

    private static final String[] PREVERBS = {"باز", "فرو", "فرا", "بر", "در", "ور"};

    /** Verb split into: words before it (کار in کار کردن), preverb (بر in برگشتن) and the simple verb. */
    static final class Parts {
        String before = "", preverb = "", verb, presentStem, imperativeStem;
    }

    static boolean isInfinitive(String w) {
        return w.length() >= 3 && (w.endsWith("تن") || w.endsWith("دن")) && Text.hasPersian(w);
    }

    static Parts analyze(String input) {
        if (input == null) return null;
        String s = Text.unifyPersian(Text.clean(input));
        if (s.isEmpty() || !Text.hasPersian(s)) return null;
        Parts p = new Parts();
        int sp = s.lastIndexOf(' ');
        if (sp > 0) {
            p.before = s.substring(0, sp + 1);
            s = s.substring(sp + 1);
        }
        if (!isInfinitive(s)) return null;
        String v = s;
        if (!TABLE.containsKey(v)) {
            for (String pre : PREVERBS) {
                String rest = s.substring(Math.min(pre.length(), s.length()));
                if (s.startsWith(pre) && rest.length() >= 3 && (TABLE.containsKey(rest) || (rest.endsWith("یدن") && rest.length() >= 5))) {
                    p.preverb = pre;
                    v = rest;
                    break;
                }
            }
        }
        p.verb = v;
        String[] e = TABLE.get(v);
        if (e != null) {
            p.presentStem = e[1];
            p.imperativeStem = e.length > 2 ? e[2] : e[1];
        } else if (v.endsWith("یدن") && v.length() >= 4) {
            p.presentStem = v.substring(0, v.length() - 3);
            p.imperativeStem = p.presentStem;
        }
        return p;
    }

    /** ب + stem with the spelling changes: آور → بیاور, انداز → بینداز, ایست → بایست. */
    private static String withBe(String stem) {
        if (stem.startsWith("آ")) return "بیا" + stem.substring(1);
        if (stem.startsWith("ای")) return "ب" + stem;
        if (stem.startsWith("ا")) return "بی" + stem.substring(1);
        return "ب" + stem;
    }

    /** Stems ending in ا take ی before the personal ending: نما → نمایم. */
    private static String joinStem(String stem) {
        return stem.endsWith("ا") ? stem + "ی" : stem;
    }

    static Conjugation conjugate(String input) {
        Parts p = analyze(input);
        if (p == null) return null;
        String full = p.before + p.preverb + p.verb;
        String pastStem = p.verb.substring(0, p.verb.length() - 1);
        String pre = p.before + p.preverb;
        boolean simple = p.before.isEmpty() && p.preverb.isEmpty();
        boolean budan = p.verb.equals("بودن") && simple;
        boolean dashtan = p.verb.equals("داشتن") && p.preverb.isEmpty();     // دارم (no می), also in دوست داشتن

        List<Conjugation.Tense> tenses = new ArrayList<>();
        String[] present = new String[6], past = new String[6], pastCont = new String[6], perfect = new String[6];
        String[] subj = new String[6], future = new String[6];
        for (int i = 0; i < 6; i++) {
            past[i] = pre + pastStem + PAST_END[i];
            pastCont[i] = pre + "می" + Z + pastStem + PAST_END[i];
            perfect[i] = pre + pastStem + "ه" + PERFECT_END[i];
            future[i] = pre + FUTURE[i] + " " + pastStem;
        }
        if (p.presentStem != null) {
            String st = joinStem(p.presentStem);
            for (int i = 0; i < 6; i++) {
                if (budan) {
                    present[i] = new String[]{"هستم", "هستی", "است", "هستیم", "هستید", "هستند"}[i];
                    subj[i] = "باش" + END[i];
                } else if (dashtan) {
                    present[i] = pre + st + END[i];
                    subj[i] = pre + pastStem + "ه " + "باش" + END[i];
                } else {
                    present[i] = pre + "می" + Z + st + END[i];
                    subj[i] = pre + (simple ? withBe(st) : st) + END[i];
                }
            }
            tenses.add(new Conjugation.Tense("Present", "مضارع اخباری", PERSONS, present));
        }
        tenses.add(new Conjugation.Tense("Simple past", "گذشته" + Z + "ی ساده", PERSONS, past));
        if (!budan && !dashtan) tenses.add(new Conjugation.Tense("Past continuous", "گذشته" + Z + "ی استمراری", PERSONS, pastCont));
        tenses.add(new Conjugation.Tense("Perfect", "ماضی نقلی", PERSONS, perfect));
        if (p.presentStem != null) tenses.add(new Conjugation.Tense("Subjunctive", "مضارع التزامی", PERSONS, subj));
        tenses.add(new Conjugation.Tense("Future", "آینده", PERSONS, future));
        if (p.presentStem != null && !p.verb.equals("توانستن") && !p.verb.equals("خواستن")) {
            String[] imp;
            if (budan) imp = new String[]{"باش", "باشید"};
            else if (dashtan) imp = new String[]{pre + pastStem + "ه باش", pre + pastStem + "ه باشید"};
            else {
                String one = simple ? withBe(p.imperativeStem) : p.imperativeStem;
                String two = simple ? withBe(joinStem(p.presentStem)) : joinStem(p.presentStem);
                imp = new String[]{pre + one, pre + two + "ید"};
            }
            tenses.add(new Conjugation.Tense("Imperative", "امر", IMP_PERSONS, imp));
        }

        List<Conjugation.Info> info = new ArrayList<>();
        info.add(new Conjugation.Info("Past stem", pastStem));
        if (p.presentStem != null) info.add(new Conjugation.Info("Present stem", p.presentStem));
        return new Conjugation(Lang.FA, full, info, tenses);
    }
}
