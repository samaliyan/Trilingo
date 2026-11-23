package io.github.samaliyan.trilingo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Conjugation, verb detection and example sentences. Run by tests/run_core.sh after CoreTest. */
public final class VerbTest {
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

    static final String Z = PersianVerbs.Z;

    public static void main(String[] args) throws Exception {
        german();
        english();
        persian();
        verbs();
        examples();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static String forms(Conjugation c, String tense) {
        Conjugation.Tense t = c.tense(tense);
        return t == null ? null : String.join(", ", t.forms);
    }

    static String info(Conjugation c, String label) {
        for (Conjugation.Info i : c.info) if (i.label.equals(label)) return i.value;
        return null;
    }

    // ------------------------------------------------------------------ German

    static void german() {
        Conjugation c = Conjugation.of(Lang.DE, "haben");
        eq("habe, hast, hat, haben, habt, haben", forms(c, "Present"), "haben present");
        eq("hatte, hattest, hatte, hatten, hattet, hatten", forms(c, "Simple past"), "haben past");
        eq("habe gehabt, hast gehabt, hat gehabt, haben gehabt, habt gehabt, haben gehabt", forms(c, "Perfect"), "haben perfect");
        eq("werde haben, wirst haben, wird haben, werden haben, werdet haben, werden haben", forms(c, "Future"), "haben future");
        eq("hab, habt, haben Sie", forms(c, "Imperative"), "haben imperative");
        eq("Irregular", info(c, "Type"), "haben irregular");

        c = Conjugation.of(Lang.DE, "sein");
        eq("bin, bist, ist, sind, seid, sind", forms(c, "Present"), "sein present");
        eq("war, warst, war, waren, wart, waren", forms(c, "Simple past"), "sein past");
        eq("bin gewesen, bist gewesen, ist gewesen, sind gewesen, seid gewesen, sind gewesen", forms(c, "Perfect"), "sein perfect");
        eq("sei, seid, seien Sie", forms(c, "Imperative"), "sein imperative");

        c = Conjugation.of(Lang.DE, "machen");
        eq("mache, machst, macht, machen, macht, machen", forms(c, "Present"), "machen present");
        eq("machte, machtest, machte, machten, machtet, machten", forms(c, "Simple past"), "machen past");
        eq("habe gemacht", c.tense("Perfect").form(0), "machen perfect");
        eq("mach, macht, machen Sie", forms(c, "Imperative"), "machen imperative");
        eq("Regular", info(c, "Type"), "machen regular");

        c = Conjugation.of(Lang.DE, "arbeiten");
        eq("arbeite, arbeitest, arbeitet, arbeiten, arbeitet, arbeiten", forms(c, "Present"), "arbeiten e-insertion");
        eq("arbeitete", c.tense("Simple past").form(0), "arbeiten past");
        eq("habe gearbeitet", c.tense("Perfect").form(0), "arbeiten perfect");
        eq("arbeite", c.tense("Imperative").form(0), "arbeiten imperative");

        c = Conjugation.of(Lang.DE, "öffnen");
        eq("öffnet", c.tense("Present").form(2), "öffnen er");
        eq("lernt", Conjugation.of(Lang.DE, "lernen").tense("Present").form(2), "lernen er (no e)");
        eq("heißt", Conjugation.of(Lang.DE, "heißen").tense("Present").form(1), "heißen du");
        eq("tanzt", Conjugation.of(Lang.DE, "tanzen").tense("Present").form(1), "tanzen du");

        c = Conjugation.of(Lang.DE, "sammeln");
        eq("sammle, sammelst, sammelt, sammeln, sammelt, sammeln", forms(c, "Present"), "sammeln present");
        eq("sammle", c.tense("Imperative").form(0), "sammeln imperative");

        c = Conjugation.of(Lang.DE, "gehen");
        eq("gehe, gehst, geht, gehen, geht, gehen", forms(c, "Present"), "gehen present");
        eq("ging, gingst, ging, gingen, gingt, gingen", forms(c, "Simple past"), "gehen past");
        eq("bin gegangen, bist gegangen, ist gegangen, sind gegangen, seid gegangen, sind gegangen", forms(c, "Perfect"), "gehen perfect with sein");
        eq("sein", info(c, "Perfect with"), "gehen aux");

        c = Conjugation.of(Lang.DE, "geben");
        eq("gebe, gibst, gibt, geben, gebt, geben", forms(c, "Present"), "geben e→i");
        eq("gab, gabst, gab, gaben, gabt, gaben", forms(c, "Simple past"), "geben past");
        eq("gib, gebt, geben Sie", forms(c, "Imperative"), "geben imperative");
        eq("lies", Conjugation.of(Lang.DE, "lesen").tense("Imperative").form(0), "lesen imperative");
        eq("iss", Conjugation.of(Lang.DE, "essen").tense("Imperative").form(0), "essen imperative");
        eq("sieh", Conjugation.of(Lang.DE, "sehen").tense("Imperative").form(0), "sehen imperative");
        eq("fahr", Conjugation.of(Lang.DE, "fahren").tense("Imperative").form(0), "fahren imperative keeps a");
        eq("fährst", Conjugation.of(Lang.DE, "fahren").tense("Present").form(1), "fahren du");
        eq("fandest", Conjugation.of(Lang.DE, "finden").tense("Simple past").form(1), "finden past du");
        eq("fandet", Conjugation.of(Lang.DE, "finden").tense("Simple past").form(4), "finden past ihr");
        eq("lasest", Conjugation.of(Lang.DE, "lesen").tense("Simple past").form(1), "lesen past du");
        eq("schrien", Conjugation.of(Lang.DE, "schreien").tense("Simple past").form(3), "schreien past wir");
        eq("brachte", Conjugation.of(Lang.DE, "bringen").tense("Simple past").form(0), "bringen mixed");
        eq("brachtest", Conjugation.of(Lang.DE, "bringen").tense("Simple past").form(1), "bringen mixed du");

        c = Conjugation.of(Lang.DE, "können");
        eq("kann, kannst, kann, können, könnt, können", forms(c, "Present"), "können present");
        eq("konnte", c.tense("Simple past").form(0), "können past");
        eq(null, c.tense("Imperative"), "modal has no imperative");
        eq("weiß, weißt, weiß, wissen, wisst, wissen", forms(Conjugation.of(Lang.DE, "wissen"), "Present"), "wissen");
        eq("tue, tust, tut, tun, tut, tun", forms(Conjugation.of(Lang.DE, "tun"), "Present"), "tun");
        eq("werde, wirst, wird, werden, werdet, werden", forms(Conjugation.of(Lang.DE, "werden"), "Present"), "werden");

        // separable
        c = Conjugation.of(Lang.DE, "ankommen");
        eq("komme an, kommst an, kommt an, kommen an, kommt an, kommen an", forms(c, "Present"), "ankommen present");
        eq("kam an", c.tense("Simple past").form(0), "ankommen past");
        eq("bin angekommen", c.tense("Perfect").form(0), "ankommen perfect");
        eq("werde ankommen", c.tense("Future").form(0), "ankommen future");
        eq("komm an, kommt an, kommen Sie an", forms(c, "Imperative"), "ankommen imperative");
        eq("an", info(c, "Separable prefix"), "separable info");
        c = Conjugation.of(Lang.DE, "aufmachen");
        eq("mache auf", c.tense("Present").form(0), "aufmachen present");
        eq("habe aufgemacht", c.tense("Perfect").form(0), "aufmachen perfect");
        eq("bin aufgestanden", Conjugation.of(Lang.DE, "aufstehen").tense("Perfect").form(0), "aufstehen with sein");
        eq("habe angefangen", Conjugation.of(Lang.DE, "anfangen").tense("Perfect").form(0), "anfangen");
        eq("fängst an", Conjugation.of(Lang.DE, "anfangen").tense("Present").form(1), "anfangen du");
        eq("antworte", Conjugation.of(Lang.DE, "antworten").tense("Present").form(0), "antworten is not an+tworten");

        // inseparable
        c = Conjugation.of(Lang.DE, "verstehen");
        eq("verstehe, verstehst, versteht, verstehen, versteht, verstehen", forms(c, "Present"), "verstehen present");
        eq("verstand", c.tense("Simple past").form(0), "verstehen past");
        eq("habe verstanden", c.tense("Perfect").form(0), "verstehen perfect");
        eq("versteh, versteht, verstehen Sie", forms(c, "Imperative"), "verstehen imperative");
        eq("habe bekommen", Conjugation.of(Lang.DE, "bekommen").tense("Perfect").form(0), "bekommen with haben");
        eq("habe bezahlt", Conjugation.of(Lang.DE, "bezahlen").tense("Perfect").form(0), "bezahlen");
        eq("bezahlte", Conjugation.of(Lang.DE, "bezahlen").tense("Simple past").form(0), "bezahlen past");
        eq("habe vergessen", Conjugation.of(Lang.DE, "vergessen").tense("Perfect").form(0), "vergessen");
        eq("habe gehört", Conjugation.of(Lang.DE, "gehören").tense("Perfect").form(0), "gehören");
        eq("habe studiert", Conjugation.of(Lang.DE, "studieren").tense("Perfect").form(0), "-ieren no ge");
        eq("bin gereist", Conjugation.of(Lang.DE, "reisen").tense("Perfect").form(0), "reisen with sein");
        eq("habe begonnen", Conjugation.of(Lang.DE, "beginnen").tense("Perfect").form(0), "beginnen");

        // reflexive
        c = Conjugation.of(Lang.DE, "sich freuen");
        eq("freue mich, freust dich, freut sich, freuen uns, freut euch, freuen sich", forms(c, "Present"), "sich freuen present");
        eq("habe mich gefreut", c.tense("Perfect").form(0), "sich freuen perfect");
        eq("werde mich freuen", c.tense("Future").form(0), "sich freuen future");
        eq("freu dich, freut euch, freuen Sie sich", forms(c, "Imperative"), "sich freuen imperative");
        eq("ziehe mich an", Conjugation.of(Lang.DE, "sich anziehen").tense("Present").form(0), "sich anziehen");
        eq("habe mich angezogen", Conjugation.of(Lang.DE, "sich anziehen").tense("Perfect").form(0), "sich anziehen perfect");
        eq("sich freuen", c.verb, "reflexive verb name");

        // not verbs
        eq(null, Conjugation.of(Lang.DE, "Haus"), "noun is not conjugated");
        eq(null, Conjugation.of(Lang.DE, "ich habe"), "phrase is not an infinitive");
        eq(null, Conjugation.of(Lang.DE, ""), "empty");

        // forms → infinitive
        eq("haben", GermanVerbs.lemma("habe"), "lemma habe");
        eq("haben", GermanVerbs.lemma("hast"), "lemma hast");
        eq("haben", GermanVerbs.lemma("Hat"), "lemma Hat");
        eq("gehen", GermanVerbs.lemma("ging"), "lemma ging");
        eq("gehen", GermanVerbs.lemma("gegangen"), "lemma gegangen");
        eq("sein", GermanVerbs.lemma("bin"), "lemma bin");
        eq("sein", GermanVerbs.lemma("war"), "lemma war");
        eq("geben", GermanVerbs.lemma("gibt"), "lemma gibt");
        eq("nehmen", GermanVerbs.lemma("nimm"), "lemma nimm");
        eq("machen", GermanVerbs.lemma("machte"), "lemma machte");
        eq("machen", GermanVerbs.lemma("gemacht"), "lemma gemacht");
        eq("machen", GermanVerbs.lemma("macht"), "lemma macht");
        eq("arbeiten", GermanVerbs.lemma("arbeitete"), "lemma arbeitete");
        eq("lernen", GermanVerbs.lemma("lernen"), "lemma infinitive");
        ok(GermanVerbs.isKnownForm("hast"), "hast is a known form");
        ok(!GermanVerbs.isKnownForm("Haus"), "Haus is not a known form");
    }

    // ------------------------------------------------------------------ English

    static void english() {
        Conjugation c = Conjugation.of(Lang.EN, "go");
        eq("go, go, goes, go, go, go", forms(c, "Present"), "go present");
        eq("went", c.tense("Simple past").form(0), "go past");
        eq("have gone, have gone, has gone, have gone, have gone, have gone", forms(c, "Perfect"), "go perfect");
        eq("am going, are going, is going, are going, are going, are going", forms(c, "Present continuous"), "go continuous");
        eq("will go", c.tense("Future").form(0), "go future");
        eq("Irregular", info(c, "Type"), "go irregular");
        c = Conjugation.of(Lang.EN, "be");
        eq("am, are, is, are, are, are", forms(c, "Present"), "be present");
        eq("was, were, was, were, were, were", forms(c, "Simple past"), "be past");
        eq("has", Conjugation.of(Lang.EN, "have").tense("Present").form(2), "has");
        eq("to have", "to " + Conjugation.of(Lang.EN, "to have").verb, "to … removed");
        eq("studies", EnglishVerbs.third("study"), "studies");
        eq("plays", EnglishVerbs.third("play"), "plays");
        eq("watches", EnglishVerbs.third("watch"), "watches");
        eq("studied", EnglishVerbs.pastRegular("study"), "studied");
        eq("played", EnglishVerbs.pastRegular("play"), "played");
        eq("stopped", EnglishVerbs.pastRegular("stop"), "stopped");
        eq("liked", EnglishVerbs.pastRegular("like"), "liked");
        eq("visited", EnglishVerbs.pastRegular("visit"), "visited");
        eq("preferred", EnglishVerbs.pastRegular("prefer"), "preferred");
        eq("running", EnglishVerbs.ing("run"), "running");
        eq("making", EnglishVerbs.ing("make"), "making");
        eq("lying", EnglishVerbs.ing("lie"), "lying");
        eq("seeing", EnglishVerbs.ing("see"), "seeing");
        eq("fixing", EnglishVerbs.ing("fix"), "fixing (x not doubled)");
        eq("opening", EnglishVerbs.ing("open"), "opening");
        eq("beginning", EnglishVerbs.ing("begin"), "beginning");
        c = Conjugation.of(Lang.EN, "give up");
        eq("gives up", c.tense("Present").form(2), "phrasal present");
        eq("gave up", c.tense("Simple past").form(0), "phrasal past");
        eq("has given up", c.tense("Perfect").form(2), "phrasal perfect");
        eq(null, Conjugation.of(Lang.EN, "I have"), "pronoun phrase is not a verb");
        eq(null, Conjugation.of(Lang.EN, "Haus123"), "not a verb");
        eq("go", EnglishVerbs.lemma("went"), "lemma went");
        eq("go", EnglishVerbs.lemma("gone"), "lemma gone");
        eq("go", EnglishVerbs.lemma("goes"), "lemma goes");
        eq("be", EnglishVerbs.lemma("was"), "lemma was");
        eq("have", EnglishVerbs.lemma("has"), "lemma has");
        eq("study", EnglishVerbs.lemma("studies"), "lemma studies");
        eq("study", EnglishVerbs.lemma("studied"), "lemma studied");
        eq("stop", EnglishVerbs.lemma("stopped"), "lemma stopped");
        eq("like", EnglishVerbs.lemma("liked"), "lemma liked");
        eq("visit", EnglishVerbs.lemma("visited"), "lemma visited");
        eq("make", EnglishVerbs.lemma("making"), "lemma making");
        eq("run", EnglishVerbs.lemma("running"), "lemma running");
        eq("watch", EnglishVerbs.lemma("watches"), "lemma watches");
        eq("play", EnglishVerbs.lemma("plays"), "lemma plays");
    }

    // ------------------------------------------------------------------ Persian

    static void persian() {
        Conjugation c = Conjugation.of(Lang.FA, "رفتن");
        eq("می" + Z + "روم, می" + Z + "روی, می" + Z + "رود, می" + Z + "رویم, می" + Z + "روید, می" + Z + "روند", forms(c, "Present"), "رفتن present");
        eq("رفتم, رفتی, رفت, رفتیم, رفتید, رفتند", forms(c, "Simple past"), "رفتن past");
        eq("می" + Z + "رفتم", c.tense("Past continuous").form(0), "رفتن past continuous");
        eq("رفته" + Z + "ام", c.tense("Perfect").form(0), "رفتن perfect");
        eq("رفته است", c.tense("Perfect").form(2), "رفتن perfect 3sg");
        eq("بروم", c.tense("Subjunctive").form(0), "رفتن subjunctive");
        eq("خواهم رفت", c.tense("Future").form(0), "رفتن future");
        eq("برو, بروید", forms(c, "Imperative"), "رفتن imperative");
        eq("رو", info(c, "Present stem"), "present stem");
        eq("رفت", info(c, "Past stem"), "past stem");

        c = Conjugation.of(Lang.FA, "آمدن");
        eq("می" + Z + "آیم", c.tense("Present").form(0), "آمدن present");
        eq("بیایم", c.tense("Subjunctive").form(0), "آمدن subjunctive");
        eq("بیا, بیایید", forms(c, "Imperative"), "آمدن imperative");
        c = Conjugation.of(Lang.FA, "گفتن");
        eq("می" + Z + "گویم", c.tense("Present").form(0), "گفتن present");
        eq("بگو, بگویید", forms(c, "Imperative"), "گفتن imperative");
        eq("بیاورم", Conjugation.of(Lang.FA, "آوردن").tense("Subjunctive").form(0), "آوردن subjunctive");
        eq("بیندازم", Conjugation.of(Lang.FA, "انداختن").tense("Subjunctive").form(0), "انداختن subjunctive");
        eq("می" + Z + "نمایم", Conjugation.of(Lang.FA, "نمودن").tense("Present").form(0), "ا + ی");
        eq("ببین", Conjugation.of(Lang.FA, "دیدن").tense("Imperative").form(0), "دیدن imperative");
        eq("می" + Z + "شنوم", Conjugation.of(Lang.FA, "شنیدن").tense("Present").form(0), "شنیدن (not شن)");

        c = Conjugation.of(Lang.FA, "پرسیدن");
        eq("می" + Z + "پرسم", c.tense("Present").form(0), "-یدن rule");
        eq("بپرس", c.tense("Imperative").form(0), "-یدن imperative");

        c = Conjugation.of(Lang.FA, "داشتن");
        eq("دارم, داری, دارد, داریم, دارید, دارند", forms(c, "Present"), "داشتن present without می");
        eq("داشته باشم", c.tense("Subjunctive").form(0), "داشتن subjunctive");
        eq(null, c.tense("Past continuous"), "داشتن has no past continuous");
        c = Conjugation.of(Lang.FA, "بودن");
        eq("هستم, هستی, است, هستیم, هستید, هستند", forms(c, "Present"), "بودن present");
        eq("باشم", c.tense("Subjunctive").form(0), "بودن subjunctive");
        eq("باش, باشید", forms(c, "Imperative"), "بودن imperative");

        c = Conjugation.of(Lang.FA, "کار کردن");
        eq("کار می" + Z + "کنم", c.tense("Present").form(0), "compound present");
        eq("کار کنم", c.tense("Subjunctive").form(0), "compound subjunctive (no ب)");
        eq("کار کن, کار کنید", forms(c, "Imperative"), "compound imperative");
        eq("کار خواهم کرد", c.tense("Future").form(0), "compound future");
        eq("دوست دارم", Conjugation.of(Lang.FA, "دوست داشتن").tense("Present").form(0), "دوست داشتن");

        c = Conjugation.of(Lang.FA, "برگشتن");
        eq("برمی" + Z + "گردم", c.tense("Present").form(0), "preverb present");
        eq("برگردم", c.tense("Subjunctive").form(0), "preverb subjunctive");
        eq("برگرد, برگردید", forms(c, "Imperative"), "preverb imperative");
        eq("برخواهم گشت", c.tense("Future").form(0), "preverb future");
        eq("برمی" + Z + "دارم", Conjugation.of(Lang.FA, "برداشتن").tense("Present").form(0), "برداشتن has می");
        eq("می" + Z + "برم", Conjugation.of(Lang.FA, "بردن").tense("Present").form(0), "بردن is not بر + دن");

        c = Conjugation.of(Lang.FA, "نوشتاردن");      // unknown, not in the list: only the past-stem tenses
        ok(c != null && c.tense("Present") == null && c.tense("Simple past") != null, "unknown verb: past tenses only");
        eq(null, Conjugation.of(Lang.FA, "خانه"), "noun is not a verb");
        eq(null, Conjugation.of(Lang.FA, "house"), "latin is not persian");
        ok(Conjugation.of(Lang.FA, "رفتن") != null, "escaped input works");
    }

    // ------------------------------------------------------------------ which verbs

    static Lookup lookup(String q, Lang src, Lookup.Result a, Lookup.Result b, String base) {
        return new Lookup(q, src, Arrays.asList(a, b), "Google Translate", 1, "", base);
    }

    static Lookup.Result res(Lang l, String text, String pos, String... terms) {
        List<Lookup.Sense> s = new ArrayList<>();
        if (pos != null) s.add(new Lookup.Sense(pos, Arrays.asList(terms)));
        return new Lookup.Result(l, text, s);
    }

    static void verbs() {
        // German "habe": known form, translations with verb meanings
        Map<Lang, String> v = Verbs.of(lookup("habe", Lang.DE, res(Lang.EN, "have", "verb", "have", "own"),
                res(Lang.FA, "دارم", "verb", "داشتن", "دارا بودن"), ""));
        eq("[DE, EN, FA]", v.keySet().toString(), "habe: all three languages");
        eq("haben", v.get(Lang.DE), "habe → haben");
        eq("have", v.get(Lang.EN), "english verb");
        eq("داشتن", v.get(Lang.FA), "persian verb");

        // service base form wins
        v = Verbs.of(lookup("went", Lang.EN, res(Lang.FA, "رفت", "verb", "رفتن"), res(Lang.DE, "ging", "verb", "gehen"), "go"));
        eq("go", v.get(Lang.EN), "base form from the service");
        eq("gehen", v.get(Lang.DE), "german from verb meanings");

        // English verb without base form → own lemma
        v = Verbs.of(lookup("studied", Lang.EN, res(Lang.FA, "مطالعه کرد", "verb", "مطالعه کردن"), res(Lang.DE, "studierte", "verb", "studieren"), ""));
        eq("study", v.get(Lang.EN), "lemma studied");
        eq("مطالعه کردن", v.get(Lang.FA), "compound persian verb");

        // noun: nothing
        v = Verbs.of(lookup("house", Lang.EN, res(Lang.FA, "خانه", "noun", "خانه"), res(Lang.DE, "Haus", "noun", "Haus"), ""));
        ok(v.isEmpty(), "noun has no verbs");
        Lookup.Result nounFirst = new Lookup.Result(Lang.FA, "خانه", Arrays.asList(
                new Lookup.Sense("noun", Arrays.asList("خانه")), new Lookup.Sense("verb", Arrays.asList("منزل دادن"))));
        v = Verbs.of(lookup("house", Lang.EN, nounFirst, res(Lang.DE, "Haus", "noun", "Haus"), ""));
        ok(v.isEmpty(), "rare verb meaning after the noun: no conjugation");
        v = Verbs.of(lookup("Haus", Lang.DE, res(Lang.EN, "house", null), res(Lang.FA, "خانه", null), ""));
        ok(v.isEmpty(), "german noun without senses has no verbs");

        // Persian infinitive typed
        v = Verbs.of(lookup("رفتن", Lang.FA, res(Lang.EN, "to go", "verb", "go", "leave"), res(Lang.DE, "gehen", "verb", "gehen"), ""));
        eq("[FA, EN, DE]", v.keySet().toString(), "persian infinitive first");
        eq("go", v.get(Lang.EN), "english from verb terms");

        // no verb senses but known German form; translations used if they are infinitives
        v = Verbs.of(lookup("ging", Lang.DE, res(Lang.EN, "went", null), res(Lang.FA, "رفت", null), ""));
        eq("gehen", v.get(Lang.DE), "ging → gehen");
        eq("went", v.get(Lang.EN), "english main translation used when nothing better");
        ok(!v.containsKey(Lang.FA), "persian past form is not an infinitive");

        // verb terms that are not infinitives are skipped
        v = Verbs.of(lookup("go", Lang.EN, res(Lang.FA, "برو", "verb", "برو", "رفتن"), res(Lang.DE, "geh", "verb", "geh", "gehen"), ""));
        eq("رفتن", v.get(Lang.FA), "first infinitive term");
        eq("gehen", v.get(Lang.DE), "first german infinitive term");

        // long sentence: no conjugation of the typed text
        v = Verbs.of(lookup("I would like to go home", Lang.EN, res(Lang.FA, "x", null), res(Lang.DE, "y", null), ""));
        ok(v.isEmpty(), "sentence has no verbs");

        // base form saved in history
        Lookup l = lookup("went", Lang.EN, res(Lang.FA, "رفت", null), res(Lang.DE, "ging", null), "go");
        Lookup r = Lookup.fromJson(Json.parse(l.toJson()));
        eq("go", r == null ? null : r.baseForm, "base form saved");
    }

    // ------------------------------------------------------------------ examples

    static String sentence(String text, String... trans) {
        StringBuilder b = new StringBuilder("{\"id\":1,\"text\":" + Json.quote(text) + ",\"lang\":\"deu\",\"translations\":[");
        for (int i = 0; i < trans.length; i += 3) {
            if (i > 0) b.append(',');
            b.append("{\"id\":2,\"text\":").append(Json.quote(trans[i + 1])).append(",\"lang\":\"").append(trans[i])
                    .append("\",\"is_direct\":").append(trans[i + 2]).append('}');
        }
        return b.append("]}").toString();
    }

    static void examples() throws IOException {
        String body = "{\"data\":["
                + sentence("Haus!", "eng", "Home!", "false") + ","
                + sentence("Das Haus ist neu.", "eng", "The house is new.", "true", "pes", "خانه نو است.", "true") + ","
                + sentence("Ich sehe ein Haus.", "eng", "I see the house.", "false", "eng", "I see a house.", "true") + ","
                + sentence("Das Haus ist neu!", "eng", "The house is new!", "true") + ","
                + sentence("Wir haben ein sehr großes Haus am See gekauft.", "pes", "ما یک خانه خریدیم.", "false") + ","
                + sentence("Ihr Haus ist riesig!", "eng", "Your house is massive!", "true", "pes", "خانه شما بسیار بزرگ است.", "true")
                + "],\"paging\":{\"total\":5}}";
        List<Examples.Example> ex = Examples.parse(body, Lang.DE, 3);
        eq(3, ex.size(), "three examples");
        eq("Das Haus ist neu.", ex.get(0).text, "best: both direct translations, statement");
        eq("The house is new.", ex.get(0).translations.get(Lang.EN), "english translation");
        eq("خانه نو است.", ex.get(0).translations.get(Lang.FA), "persian translation");
        eq("Ihr Haus ist riesig!", ex.get(1).text, "second best");
        eq("I see a house.", ex.get(2).translations.get(Lang.EN), "direct translation preferred");
        for (Examples.Example e : ex) ok(!e.text.equals("Das Haus ist neu!"), "near-duplicate removed");
        eq(0, Examples.parse("{\"data\":[]}", Lang.DE, 3).size(), "no examples");
        boolean threw = false;
        try {
            Examples.parse("<html>", Lang.EN, 3);
        } catch (IOException e) {
            threw = true;
        }
        ok(threw, "bad answer rejected");

        String url = Examples.url("Haus", Lang.DE);
        ok(url.startsWith("https://api.tatoeba.org/unstable/sentences?lang=deu&q=%3DHaus&"), "url exact word: " + url);
        ok(url.contains("trans%3Alang=eng%2Cpes") && url.contains("sort=relevance") && url.contains("word_count=3-12"), "url params");
        ok(Examples.url("give up", Lang.EN).contains("q=%22give+up%22"), "phrase in quotes");
        ok(Examples.url("خانه", Lang.FA).contains("lang=pes"), "persian code");
        ok(Examples.suitable("house") && Examples.suitable("give up") && !Examples.suitable("I would like to go home"), "suitable");
        ok(!Examples.suitable("123"), "numbers not suitable");

        List<int[]> f = Examples.find("Das Haus und das Haustier im Haus.", "haus");
        eq(2, f.size(), "whole words only");
        eq(4, f.get(0)[0], "first position");
        f = Examples.find("خانه‌ها و خانه", "خانه");
        eq(2, f.size(), "persian substring matches");
    }
}
