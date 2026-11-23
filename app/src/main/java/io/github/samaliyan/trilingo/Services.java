package io.github.samaliyan.trilingo;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request addresses and answer parsing of the two free translation services. Pure Java.
 * Google Translate (the public endpoint used by browser extensions) gives the translation plus dictionary
 * meanings grouped by word type; MyMemory is the backup and gives one translation.
 */
final class Services {
    private Services() { }

    static final int GOOGLE = 0, MYMEMORY = 1;
    static final String[] NAMES = {"Google Translate", "MyMemory"};

    static final int MAX_SENSES = 5, MAX_TERMS = 8;

    /** One answer of a service: the translation and the language it detected (may be null). */
    static final class Answer {
        final Lookup.Result result;
        final String detected;
        final List<String> detectedCandidates;
        /** Google's spelling correction of the typed text ("Did you mean"), or "" when it looks right. */
        final String suggestion;

        Answer(Lookup.Result result, String detected, List<String> candidates, String suggestion) {
            this.result = result;
            this.detected = detected;
            this.detectedCandidates = candidates;
            this.suggestion = suggestion == null ? "" : suggestion;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ Google

    /** @param sl source code or "auto" */
    static String googleUrl(String q, String sl, Lang tl) {
        return "https://translate.googleapis.com/translate_a/single?client=gtx&dj=1&hl=en&ie=UTF-8&oe=UTF-8"
                + "&sl=" + sl + "&tl=" + tl.code + "&dt=t&dt=bd&dt=qc&q=" + enc(q);
    }

    /** Reads Google's answer; understands the object form (dj=1) and the older array form. */
    static Answer parseGoogle(String body, Lang target) throws IOException {
        Object root;
        try {
            root = Json.parse(body);
        } catch (IllegalArgumentException e) {
            throw new IOException("bad answer", e);
        }
        StringBuilder text = new StringBuilder();
        List<Lookup.Sense> senses = new ArrayList<>();
        String src = null, spell = null;
        List<String> cands = new ArrayList<>();

        Map<String, Object> m = Json.obj(root);
        if (m != null) {
            List<Object> sentences = Json.arr(m, "sentences");
            if (sentences != null) {
                for (Object so : sentences) {
                    String t = Json.str(Json.obj(so), "trans");
                    if (t != null) text.append(t);
                }
            }
            List<Object> dict = Json.arr(m, "dict");
            if (dict != null) {
                for (Object d : dict) {
                    Map<String, Object> dm = Json.obj(d);
                    List<String> terms = new ArrayList<>();
                    List<Object> ts = Json.arr(dm, "terms");
                    if (ts != null) for (Object t : ts) if (Json.str(t) != null) terms.add((String) t);
                    if (terms.isEmpty()) {
                        List<Object> es = Json.arr(dm, "entry");
                        if (es != null) for (Object e : es) {
                            String w = Json.str(Json.obj(e), "word");
                            if (w != null) terms.add(w);
                        }
                    }
                    addSense(senses, Json.str(dm, "pos"), terms);
                }
            }
            src = Json.str(m, "src");
            spell = Json.str(Json.obj(m, "spell"), "spell_res");
            List<Object> ls = Json.arr(Json.obj(m, "ld_result"), "srclangs");
            if (ls != null) for (Object l : ls) if (Json.str(l) != null) cands.add((String) l);
        } else {
            List<Object> a = Json.arr(root);
            if (a == null) throw new IOException("bad answer");
            List<Object> sentences = Json.arr(Json.at(a, 0));
            if (sentences != null) {
                for (Object so : sentences) {
                    String t = Json.str(Json.at(Json.arr(so), 0));
                    if (t != null) text.append(t);
                }
            }
            List<Object> dict = Json.arr(Json.at(a, 1));
            if (dict != null) {
                for (Object d : dict) {
                    List<Object> da = Json.arr(d);
                    List<String> terms = new ArrayList<>();
                    List<Object> ts = Json.arr(Json.at(da, 1));
                    if (ts != null) for (Object t : ts) if (Json.str(t) != null) terms.add((String) t);
                    addSense(senses, Json.str(Json.at(da, 0)), terms);
                }
            }
            src = Json.str(Json.at(a, 2));
            spell = Json.str(Json.at(Json.arr(Json.at(a, 7)), 1));
        }
        String t = text.toString().trim();
        if (t.isEmpty() && senses.isEmpty()) throw new IOException("empty answer");
        if (t.isEmpty()) t = senses.get(0).terms.get(0);
        return new Answer(new Lookup.Result(target, t, senses), src, cands, suggestion(spell));
    }

    /** Cleans Google's correction (it may carry <b>/<i> marks); "" when there is none. */
    static String suggestion(String spell) {
        if (spell == null) return "";
        String c = Text.clean(Text.unescapeHtml(spell.replaceAll("<[^>]*>", "")));
        return c.length() > Text.MAX_LEN ? "" : c;
    }

    private static void addSense(List<Lookup.Sense> senses, String pos, List<String> terms) {
        if (senses.size() >= MAX_SENSES) return;
        Set<String> seen = new LinkedHashSet<>();
        List<String> keep = new ArrayList<>();
        for (String t : terms) {
            String c = Text.clean(t);
            if (c.isEmpty() || !seen.add(c.toLowerCase(java.util.Locale.ROOT))) continue;
            keep.add(c);
            if (keep.size() >= MAX_TERMS) break;
        }
        if (!keep.isEmpty()) senses.add(new Lookup.Sense(pos == null ? "" : pos.trim(), keep));
    }

    // ------------------------------------------------------------------ MyMemory

    static String myMemoryUrl(String q, Lang sl, Lang tl) {
        return "https://api.mymemory.translated.net/get?q=" + enc(q) + "&langpair=" + sl.code + "%7C" + tl.code;
    }

    static Answer parseMyMemory(String body, Lang target) throws IOException {
        Map<String, Object> m;
        try {
            m = Json.obj(Json.parse(body));
        } catch (IllegalArgumentException e) {
            throw new IOException("bad answer", e);
        }
        if (m == null) throw new IOException("bad answer");
        Object st = m.get("responseStatus");
        String status = st instanceof Double ? String.valueOf(((Double) st).intValue()) : String.valueOf(st);
        String t = Text.unescapeHtml(Json.str(Json.obj(m, "responseData"), "translatedText"));
        if (!"200".equals(status) || t == null || t.trim().isEmpty()) throw new IOException("service: " + status);
        // When the daily free limit is used up, the "translation" is a warning text.
        if (t.startsWith("MYMEMORY WARNING") || t.startsWith("QUERY LENGTH LIMIT")) throw new IOException("limit");
        return new Answer(new Lookup.Result(target, t.trim(), new ArrayList<>()), null, new ArrayList<>(), "");
    }
}
