package io.github.samaliyan.trilingo;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Pure-Java tests of the app logic (no Android, no network). Run by tests/run_core.sh. */
public final class CoreTest {
    private static int checks, failures;

    static void ok(boolean cond, String what) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static void eq(Object exp, Object got, String what) {
        ok(exp == null ? got == null : exp.equals(got), what + " — expected <" + exp + "> got <" + got + ">");
    }

    // Persian test words, written with escapes so the file stays readable in any editor.
    static final String KHANE = "\u062E\u0627\u0646\u0647";            // خانه
    static final String MANZEL = "\u0645\u0646\u0632\u0644";           // منزل
    static final String SALAM = "\u0633\u0644\u0627\u0645";            // سلام

    public static void main(String[] args) throws Exception {
        json();
        text();
        google();
        myMemory();
        lookupJson();
        engine();
        history();
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ Json

    static void json() {
        Object o = Json.parse("{\"a\":[1,2.5,-3e2,true,false,null,\"x\\\"y\\u0041\\n\"],\"b\":{}}");
        Map<String, Object> m = Json.obj(o);
        List<Object> a = Json.arr(m, "a");
        eq(7, a.size(), "array size");
        eq(1.0, a.get(0), "int");
        eq(2.5, a.get(1), "double");
        eq(-300.0, a.get(2), "exp");
        eq(Boolean.TRUE, a.get(3), "true");
        eq(null, a.get(5), "null");
        eq("x\"yA\n", a.get(6), "escapes");
        eq(0, Json.obj(m, "b").size(), "empty object");
        List<Object> holes = Json.arr(Json.parse("[1,,2,]"));
        eq(4, holes.size(), "array with empty slots");
        eq(null, holes.get(1), "empty slot is null");
        for (String bad : new String[]{"", "{", "[1 2]", "{\"a\" 1}", "\"abc", "tru", "{\"a\":1}x", "\"\\q\""}) {
            boolean threw = false;
            try {
                Json.parse(bad);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            ok(threw, "rejects bad json: " + bad);
        }
        StringBuilder deep = new StringBuilder();
        for (int k = 0; k < 200; k++) deep.append('[');
        boolean threw = false;
        try {
            Json.parse(deep.toString());
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        ok(threw, "rejects too deep nesting");
        String tricky = "a\"b\\c\nd\te\u0001" + KHANE;
        eq(tricky, Json.parse(Json.quote(tricky)), "quote round trip");
        eq(null, Json.str(Json.obj(Json.parse("[]")), "x"), "typed access never throws");
    }

    // ------------------------------------------------------------------ Text

    static void text() {
        eq("hello world", Text.clean("  hello \n\t world  "), "clean spaces");
        eq("", Text.clean(null), "clean null");
        eq(Text.MAX_LEN, Text.clean(repeat("a", 2000)).length(), "clean cuts long text");
        String zw = "\u0645\u06CC\u200C\u0631\u0648\u0645";             // می‌روم with half-space
        eq(zw, Text.clean(zw), "half-space kept");
        eq(Text.key("House"), Text.key(" house "), "key ignores case and spaces");
        eq(Text.key("\u0639\u0644\u064A"), Text.key("\u0639\u0644\u06CC"), "key unifies Arabic yeh");
        ok(Text.hasPersian(KHANE), "persian detected");
        ok(Text.hasPersian("hello " + SALAM), "mixed persian detected");
        ok(!Text.hasPersian("Mädchen"), "german is not persian");
        ok(Text.hasLetter("a1"), "has letter");
        ok(!Text.hasLetter("123 ?!"), "no letter");
        ok(Text.hasLetter(KHANE), "persian letters count");
        eq(Lang.DE, Text.guessLatin("Mädchen"), "umlaut → german");
        eq(Lang.DE, Text.guessLatin("Straße"), "ß → german");
        eq(Lang.DE, Text.guessLatin("ich habe keine Zeit"), "german sentence");
        eq(Lang.DE, Text.guessLatin("Entschuldigung"), "german -ung word");
        eq(Lang.EN, Text.guessLatin("the weather is nice"), "english sentence");
        eq(Lang.EN, Text.guessLatin("running"), "english -ing");
        eq(Lang.EN, Text.guessLatin("table"), "tie → english");
        eq("it's \"ok\" & <b> é", Text.unescapeHtml("it&#39;s &quot;ok&quot; &amp; &lt;b&gt; &#xE9;"), "unescape html");
        eq("a & b", Text.unescapeHtml("a & b"), "lone ampersand kept");
        eq("&bogus;", Text.unescapeHtml("&bogus;"), "unknown entity kept");
        eq("&#99999999;", Text.unescapeHtml("&#99999999;"), "invalid code point kept");
        eq(Lang.EN, Lang.of("EN"), "lang of upper");
        eq(Lang.DE, Lang.of("de-AT"), "lang of region");
        eq(null, Lang.of("nl"), "other language");
        eq(null, Lang.of(null), "lang of null");
        eq("FA,DE", Lang.EN.targets()[0] + "," + Lang.EN.targets()[1], "english targets");
        eq("EN,FA", Lang.DE.targets()[0] + "," + Lang.DE.targets()[1], "german targets");
        eq("EN,DE", Lang.FA.targets()[0] + "," + Lang.FA.targets()[1], "persian targets");
    }

    static String repeat(String s, int n) {
        return String.join("", Collections.nCopies(n, s));
    }

    // ------------------------------------------------------------------ recorded service answers

    /** Google answer in the dj=1 object form. */
    static String gObj(String trans, String src, String dict, String srclangs) {
        return "{\"sentences\":[{\"trans\":" + Json.quote(trans) + ",\"orig\":\"x\",\"backend\":10},{\"translit\":\"x\"}],"
                + (dict == null ? "" : "\"dict\":" + dict + ",")
                + "\"src\":\"" + src + "\",\"confidence\":0.9,\"spell\":{},"
                + "\"ld_result\":{\"srclangs\":[" + srclangs + "],\"srclangs_confidences\":[0.9],\"extended_srclangs\":[" + srclangs + "]}}";
    }

    static void google() throws IOException {
        String dict = "[{\"pos\":\"noun\",\"terms\":[\"" + KHANE + "\",\"" + MANZEL + "\",\"" + KHANE + "\"],"
                + "\"entry\":[{\"word\":\"" + KHANE + "\",\"reverse_translation\":[\"house\",\"home\"],\"score\":0.5}],\"base_form\":\"house\",\"pos_enum\":1},"
                + "{\"pos\":\"verb\",\"entry\":[{\"word\":\"x1\"},{\"word\":\"x2\"}]}]";
        Services.Answer a = Services.parseGoogle(gObj(KHANE, "en", dict, "\"en\""), Lang.FA);
        eq(KHANE, a.result.text, "google main text");
        eq(Lang.FA, a.result.lang, "google target");
        eq("en", a.detected, "google detected");
        eq(2, a.result.senses.size(), "google senses");
        eq("noun", a.result.senses.get(0).pos, "google pos");
        eq(2, a.result.senses.get(0).terms.size(), "google duplicate term removed");
        eq("x1,x2", String.join(",", a.result.senses.get(1).terms), "google terms from entry words");
        eq(KHANE + "\u060C " + MANZEL + "\u060C x1", a.result.summary(2), "summary uses Persian comma, no duplicate");

        // sentence split in several parts
        String multi = "{\"sentences\":[{\"trans\":\"Hello. \"},{\"trans\":\"How are you?\"}],\"src\":\"de\"}";
        eq("Hello. How are you?", Services.parseGoogle(multi, Lang.EN).result.text, "google joins sentences");

        // older array form
        String arr = "[[[\"Haus\",\"house\",null,null,10]],[[\"noun\",[\"Haus\",\"Heim\"],[[\"Haus\",[\"house\"]]],\"house\",1]],\"en\",null,null,null,0.9]";
        Services.Answer b = Services.parseGoogle(arr, Lang.DE);
        eq("Haus", b.result.text, "array form text");
        eq("Haus,Heim", String.join(",", b.result.senses.get(0).terms), "array form terms");
        eq("en", b.detected, "array form src");

        // many senses / terms are capped
        StringBuilder big = new StringBuilder("[");
        for (int k = 0; k < 9; k++) {
            if (k > 0) big.append(',');
            big.append("{\"pos\":\"p").append(k).append("\",\"terms\":[");
            for (int n = 0; n < 20; n++) big.append(n > 0 ? "," : "").append("\"t").append(n).append('"');
            big.append("]}");
        }
        big.append(']');
        Services.Answer c = Services.parseGoogle(gObj("x", "en", big.toString(), "\"en\""), Lang.DE);
        eq(Services.MAX_SENSES, c.result.senses.size(), "senses capped");
        eq(Services.MAX_TERMS, c.result.senses.get(0).terms.size(), "terms capped");

        for (String bad : new String[]{"<html>blocked</html>", "{}", "{\"sentences\":[]}", "\"x\"", ""}) {
            boolean threw = false;
            try {
                Services.parseGoogle(bad, Lang.EN);
            } catch (IOException e) {
                threw = true;
            }
            ok(threw, "google rejects: " + bad);
        }
        String url = Services.googleUrl("a b&c=" + KHANE, "auto", Lang.FA);
        ok(url.startsWith("https://translate.googleapis.com/"), "google https");
        ok(url.contains("&sl=auto&tl=fa&") && url.contains("dt=bd") && url.contains("dj=1"), "google url params");
        ok(url.endsWith("q=a+b%26c%3D%D8%AE%D8%A7%D9%86%D9%87"), "google url encodes query: " + url);
    }

    static String mm(String text, int status) {
        return "{\"responseData\":{\"translatedText\":" + Json.quote(text) + ",\"match\":0.9},\"quotaFinished\":false,"
                + "\"responseStatus\":" + status + ",\"matches\":[]}";
    }

    static void myMemory() throws IOException {
        Services.Answer a = Services.parseMyMemory(mm("it&#39;s a house", 200), Lang.EN);
        eq("it's a house", a.result.text, "mymemory text unescaped");
        eq(0, a.result.senses.size(), "mymemory has no senses");
        eq("house", Services.parseMyMemory(mm("house", 200).replace("200", "\"200\""), Lang.EN).result.text, "status as string");
        for (String bad : new String[]{mm("x", 403), mm("MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS", 200),
                mm("", 200), "not json", "[]"}) {
            boolean threw = false;
            try {
                Services.parseMyMemory(bad, Lang.EN);
            } catch (IOException e) {
                threw = true;
            }
            ok(threw, "mymemory rejects: " + bad);
        }
        eq("https://api.mymemory.translated.net/get?q=Haus&langpair=de%7Cen", Services.myMemoryUrl("Haus", Lang.DE, Lang.EN), "mymemory url");
    }

    // ------------------------------------------------------------------ Lookup JSON

    static Lookup sample(String q, Lang src, long t) {
        List<Lookup.Result> rs = new ArrayList<>();
        for (Lang l : src.targets()) {
            List<Lookup.Sense> ss = new ArrayList<>();
            List<String> terms = new ArrayList<>();
            terms.add(l.code + "-1");
            terms.add(l.code + "-2 \"q\"");
            ss.add(new Lookup.Sense("noun", terms));
            rs.add(new Lookup.Result(l, l.code + ":" + q, ss));
        }
        return new Lookup(q, src, rs, "Google Translate", t);
    }

    static void lookupJson() {
        Lookup l = sample("Haus \"x\"\n" + KHANE, Lang.DE, 1234567890123L);
        Lookup r = Lookup.fromJson(Json.parse(l.toJson()));
        ok(r != null, "lookup round trip");
        if (r == null) return;
        eq(l.query, r.query, "rt query");
        eq(Lang.DE, r.source, "rt source");
        eq(1234567890123L, r.time, "rt time");
        eq("Google Translate", r.service, "rt service");
        eq(Lang.EN, r.results.get(0).lang, "rt order");
        eq(l.results.get(1).text, r.results.get(1).text, "rt text");
        eq("fa-2 \"q\"", r.results.get(1).senses.get(0).terms.get(1), "rt terms");
        ok(r.result(Lang.FA) != null && r.result(Lang.DE) == null, "result by language");
        eq(null, Lookup.fromJson(Json.parse("{\"q\":\"x\",\"src\":\"nl\",\"r\":[]}")), "unknown source rejected");
        eq(null, Lookup.fromJson(Json.parse("{\"q\":\"x\",\"src\":\"en\",\"r\":[{\"l\":\"fa\",\"x\":\"y\"}]}")), "one result rejected");
        eq(null, Lookup.fromJson(Json.parse("{\"q\":\"x\",\"src\":\"en\",\"r\":[{\"l\":\"en\",\"x\":\"y\"},{\"l\":\"fa\",\"x\":\"y\"}]}")), "result in source language rejected");
        eq(null, Lookup.fromJson("text"), "non-object rejected");
    }

    // ------------------------------------------------------------------ Engine with a fake network

    static final class Fake implements Engine.Http {
        final Map<String, String> answers = new ConcurrentHashMap<>();
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        boolean googleDown, memoryDown, offline;

        @Override public String get(String url, int timeoutMs) throws IOException {
            calls.add(url);
            if (offline) throw new UnknownHostException("no network");
            if (googleDown && url.contains("googleapis")) throw new IOException("http 429");
            if (memoryDown && url.contains("mymemory")) throw new IOException("http 500");
            for (Map.Entry<String, String> e : answers.entrySet()) if (url.contains(e.getKey())) return e.getValue();
            throw new IOException("no fake answer for " + url);
        }
    }

    static void engine() throws IOException {
        // English word, automatic
        Fake f = new Fake();
        f.answers.put("sl=auto&tl=fa", gObj(KHANE, "en", "[{\"pos\":\"noun\",\"terms\":[\"" + KHANE + "\"]}]", "\"en\""));
        f.answers.put("sl=auto&tl=de", gObj("Haus", "en", null, "\"en\""));
        f.answers.put("sl=auto&tl=en", gObj("house", "en", null, "\"en\""));
        Engine e = new Engine(f);
        Lookup l = e.translate("house", null, Services.GOOGLE, 5);
        eq(Lang.EN, l.source, "auto english source");
        eq(Lang.FA, l.results.get(0).lang, "english → persian first");
        eq(KHANE, l.results.get(0).text, "english → persian text");
        eq("Haus", l.results.get(1).text, "english → german text");
        eq(1, l.results.get(0).senses.size(), "senses kept");
        eq(3, f.calls.size(), "auto latin = 3 parallel requests");
        eq("Google Translate", l.service, "service name");
        eq(5L, l.time, "time");

        // German word, automatic
        f = new Fake();
        f.answers.put("sl=auto&tl=fa", gObj(KHANE, "de", null, "\"de\""));
        f.answers.put("sl=auto&tl=de", gObj("Haus", "de", null, "\"de\""));
        f.answers.put("sl=auto&tl=en", gObj("house", "de", null, "\"de\""));
        e = new Engine(f);
        l = e.translate("Haus", null, Services.GOOGLE, 1);
        eq(Lang.DE, l.source, "auto german source");
        eq("house", l.results.get(0).text, "german → english first");
        eq(KHANE, l.results.get(1).text, "german → persian second");

        // Detected as Dutch: candidate list contains German → translate again from German
        f = new Fake();
        f.answers.put("sl=auto&tl=", gObj("?", "nl", null, "\"nl\",\"de\""));
        f.answers.put("sl=de&tl=en", gObj("thanks", "de", null, "\"de\""));
        f.answers.put("sl=de&tl=fa", gObj(KHANE, "de", null, "\"de\""));
        e = new Engine(f);
        l = e.translate("Dank", null, Services.GOOGLE, 1);
        eq(Lang.DE, l.source, "dutch detection → german candidate");
        eq("thanks", l.results.get(0).text, "re-translated from german");
        eq(5, f.calls.size(), "3 auto + 2 forced requests");

        // Detected as French, no candidates: guess (umlaut → German)
        f = new Fake();
        f.answers.put("sl=auto&tl=", gObj("?", "fr", null, ""));
        f.answers.put("sl=de&tl=en", gObj("girl", "de", null, ""));
        f.answers.put("sl=de&tl=fa", gObj("x", "de", null, ""));
        l = new Engine(f).translate("Mädchen", null, Services.GOOGLE, 1);
        eq(Lang.DE, l.source, "unknown detection → guess german");

        // Persian text: no detection needed, 2 requests
        f = new Fake();
        f.answers.put("sl=fa&tl=en", gObj("house", "fa", null, "\"fa\""));
        f.answers.put("sl=fa&tl=de", gObj("Haus", "fa", null, "\"fa\""));
        l = new Engine(f).translate(KHANE, null, Services.GOOGLE, 1);
        eq(Lang.FA, l.source, "persian source");
        eq("house", l.results.get(0).text, "persian → english first");
        eq("Haus", l.results.get(1).text, "persian → german second");
        eq(2, f.calls.size(), "persian = 2 requests");

        // Forced language (user tapped DE for an English-looking word)
        f = new Fake();
        f.answers.put("sl=de&tl=en", gObj("poison", "de", null, ""));
        f.answers.put("sl=de&tl=fa", gObj("x", "de", null, ""));
        l = new Engine(f).translate("Gift", Lang.DE, Services.GOOGLE, 1);
        eq(Lang.DE, l.source, "forced source");
        eq("poison", l.results.get(0).text, "forced german → english");
        ok(f.calls.stream().noneMatch(u -> u.contains("sl=auto")), "forced makes no auto request");

        // Google blocked → MyMemory backup
        f = new Fake();
        f.googleDown = true;
        f.answers.put("langpair=de%7Cen", mm("girl", 200));
        f.answers.put("langpair=de%7Cfa", mm(KHANE, 200));
        l = new Engine(f).translate("Mädchen", null, Services.GOOGLE, 1);
        eq("MyMemory", l.service, "fallback to mymemory");
        eq(Lang.DE, l.source, "mymemory guessed german");
        eq("girl", l.results.get(0).text, "mymemory text");

        // MyMemory chosen and working
        f = new Fake();
        f.answers.put("langpair=en%7Cfa", mm(KHANE, 200));
        f.answers.put("langpair=en%7Cde", mm("Haus", 200));
        l = new Engine(f).translate("house", null, Services.MYMEMORY, 1);
        eq("MyMemory", l.service, "mymemory preferred");
        ok(f.calls.stream().noneMatch(u -> u.contains("googleapis")), "google not asked when mymemory works");

        // MyMemory chosen but down → Google
        f = new Fake();
        f.memoryDown = true;
        f.answers.put("sl=en&tl=fa", gObj(KHANE, "en", null, ""));
        f.answers.put("sl=en&tl=de", gObj("Haus", "en", null, ""));
        l = new Engine(f).translate("house", Lang.EN, Services.MYMEMORY, 1);
        eq("Google Translate", l.service, "mymemory down → google");

        // Both fail: service failure vs offline
        f = new Fake();
        f.googleDown = true;
        f.memoryDown = true;
        try {
            new Engine(f).translate("house", null, Services.GOOGLE, 1);
            ok(false, "both down must fail");
        } catch (Engine.Failure x) {
            ok(!x.offline, "both down = service failure, not offline");
        }
        f = new Fake();
        f.offline = true;
        try {
            new Engine(f).translate("house", null, Services.GOOGLE, 1);
            ok(false, "offline must fail");
        } catch (Engine.Failure x) {
            ok(x.offline, "no network = offline");
        }
        // One of three parallel requests broken (bad answer) → falls back
        f = new Fake();
        f.answers.put("sl=auto&tl=fa", gObj(KHANE, "en", null, "\"en\""));
        f.answers.put("sl=auto&tl=de", "<html>error</html>");
        f.answers.put("sl=auto&tl=en", gObj("house", "en", null, "\"en\""));
        f.answers.put("langpair=en%7Cfa", mm(KHANE, 200));
        f.answers.put("langpair=en%7Cde", mm("Haus", 200));
        l = new Engine(f).translate("house", null, Services.GOOGLE, 1);
        eq("MyMemory", l.service, "broken google answer → backup");
        eq("Haus", l.results.get(1).text, "backup german");
    }

    // ------------------------------------------------------------------ History

    static void history() throws IOException {
        File dir = Files.createTempDirectory("trilingo").toFile();
        File file = new File(dir, "history.json");
        History h = new History(file);
        eq(0, h.items().size(), "empty history");
        h.add(sample("house", Lang.EN, 1_000_000));
        h.add(sample("Haus", Lang.DE, 2_000_000));
        eq("Haus", h.items().get(0).query, "newest first");
        h.add(sample("HOUSE ", Lang.EN, 3_000_000));
        eq(2, h.items().size(), "same word not repeated");
        eq("HOUSE ", h.items().get(0).query, "repeated word moves to top");
        ok(h.find("house", null) != null, "find ignores case");
        ok(h.find("house", Lang.DE) == null, "find with other forced language");
        ok(h.find("Haus", Lang.DE) != null, "find with forced language");
        ok(h.find("nothing", null) == null, "find missing");

        // typing "Wohn" then "Wohnung" quickly → one entry
        h.add(sample("Wohn", Lang.DE, 10_000_000));
        h.add(sample("Wohnung", Lang.DE, 10_003_000));
        eq("Wohnung", h.items().get(0).query, "longer word replaces unfinished one");
        eq(3, h.items().size(), "unfinished word removed");
        // the same later (not quick) → both kept
        h.add(sample("Kind", Lang.DE, 20_000_000));
        h.add(sample("Kinder", Lang.DE, 20_000_000 + History.MERGE_MS + 1));
        eq(5, h.items().size(), "slow lookups are separate entries");

        // persisted
        History h2 = new History(file);
        eq(5, h2.items().size(), "history reloaded");
        eq("Kinder", h2.items().get(0).query, "reloaded order");
        eq(h.items().get(2).results.get(1).text, h2.items().get(2).results.get(1).text, "reloaded content");
        h2.remove(h2.items().get(0));
        eq(4, new History(file).items().size(), "remove persisted");

        // max size
        for (int k = 0; k < History.MAX + 40; k++) h2.add(sample("w" + k, Lang.EN, 100_000_000L + k * 100_000L));
        eq(History.MAX, h2.items().size(), "history capped");
        eq("w" + (History.MAX + 39), h2.items().get(0).query, "cap keeps newest");

        h2.clear();
        eq(0, new History(file).items().size(), "clear persisted");

        // damaged file
        Files.write(file.toPath(), "[{\"q\":\"x\"".getBytes(StandardCharsets.UTF_8));
        eq(0, new History(file).items().size(), "damaged file → empty history, no crash");
        Files.write(file.toPath(), ("[" + sample("ok", Lang.EN, 1).toJson() + ",{\"bad\":1},42]").getBytes(StandardCharsets.UTF_8));
        eq(1, new History(file).items().size(), "bad entries skipped, good kept");
        ok(!new File(dir, "history.json.tmp").exists(), "no temp file left");
    }
}
