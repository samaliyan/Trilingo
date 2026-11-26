package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a word means, explained in its own language (like a monolingual dictionary), plus a picture and its
 * pronunciation. Sources (free, no key): English — dictionaryapi.dev and English Wiktionary; German and
 * Persian — German / Persian Wiktionary; pictures — German Wiktionary (chosen to show the meaning) or Wikipedia.
 * This class builds the request URLs and reads the answers. Pure Java.
 */
final class Meaning {
    static final class Sense {
        final String pos, text, example;

        Sense(String pos, String text, String example) {
            this.pos = pos;
            this.text = text;
            this.example = example == null ? "" : example;
        }
    }

    final Lang lang;
    final String word;
    final List<Sense> senses = new ArrayList<>();
    final List<String> synonyms = new ArrayList<>(), antonyms = new ArrayList<>(), family = new ArrayList<>();
    /** Pronunciation: IPA text (may be empty) and sound file URLs (best first). */
    String ipa = "";
    final List<String> audio = new ArrayList<>();
    /** Picture: a Wikimedia Commons file name (or a full URL), its caption, and where it was found. */
    String image = "", imageCaption = "", imageSource = "";
    /**
     * When the meaning is not the word's own entry, what it is instead ("Meaning of Assistentin", "From
     * Wikipedia", "The words of this sentence"); "" for the word's own meaning. Kind: one of NOTE_*.
     */
    String note = "", noteWord = "";
    static final String NOTE_NUMBER = "number";
    static final String NOTE_BASE = "base", NOTE_WIKIPEDIA = "wikipedia", NOTE_ENGLISH = "english", NOTE_WORDS = "words",
            NOTE_TRANSLATION = "translation";

    Meaning(Lang lang, String word) {
        this.lang = lang;
        this.word = word;
    }

    boolean isEmpty() {
        return senses.isEmpty();
    }

    /** Senses grouped by word type, in their order. */
    Map<String, List<Sense>> byPos() {
        Map<String, List<Sense>> m = new LinkedHashMap<>();
        for (Sense s : senses) m.computeIfAbsent(s.pos, k -> new ArrayList<>()).add(s);
        return m;
    }

    // ------------------------------------------------------------------ URLs

    static final String UA = "Trilingo/1.6 (https://github.com/samaliyan/Trilingo)";

    static String dictionaryUrl(String word) {
        return "https://api.dictionaryapi.dev/api/v2/entries/en/" + enc(word.toLowerCase(Locale.ROOT));
    }

    /** Raw wikitext of a Wiktionary page ("de", "en" or "fa"). */
    static String wiktionaryUrl(String host, String page) {
        return "https://" + host + ".wiktionary.org/w/api.php?action=parse&prop=wikitext&format=json&formatversion=2&redirects=1&page=" + enc(page);
    }

    static String wikipediaUrl(String host, String title) {
        return "https://" + host + ".wikipedia.org/api/rest_v1/page/summary/" + enc(title.replace(' ', '_'));
    }

    /** A small version (about {@code width} pixels wide) of a Commons file; full URLs are returned as they are. */
    static String fileUrl(String file, int width) {
        if (file.startsWith("http")) return file;
        String u = "https://commons.wikimedia.org/wiki/Special:FilePath/" + enc(file.replace(' ', '_'));
        return width > 0 ? u + "?width=" + width : u;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A text field of a JSON object, "" when missing. */
    private static String s(Map<String, Object> m, String key) {
        String v = Json.str(m, key);
        return v == null ? "" : v;
    }

    // ------------------------------------------------------------------ English: dictionaryapi.dev

    @SuppressWarnings("unchecked")
    static Meaning parseDictionary(String body, String word) {
        Meaning m = new Meaning(Lang.EN, word);
        Object root;
        try {
            root = Json.parse(body);
        } catch (RuntimeException e) {
            return m;
        }
        if (!(root instanceof List)) return m;
        Set<String> syn = new LinkedHashSet<>(), ant = new LinkedHashSet<>();
        Map<String, Integer> perPos = new LinkedHashMap<>();
        for (Object eo : (List<Object>) root) {
            if (!(eo instanceof Map)) continue;
            Map<String, Object> e = (Map<String, Object>) eo;
            if (m.ipa.isEmpty()) m.ipa = s(e, "phonetic");
            Object ph = e.get("phonetics");
            if (ph instanceof List) {
                for (Object po : (List<Object>) ph) {
                    if (!(po instanceof Map)) continue;
                    String a = s((Map<String, Object>) po, "audio");
                    String t = s((Map<String, Object>) po, "text");
                    if (m.ipa.isEmpty() && !t.isEmpty()) m.ipa = t;
                    if (a.isEmpty() || m.audio.contains(a)) continue;
                    if (a.contains("-us.") || a.contains("-uk.")) m.audio.add(0, a);
                    else m.audio.add(a);
                }
            }
            Object ms = e.get("meanings");
            if (!(ms instanceof List)) continue;
            for (Object mo : (List<Object>) ms) {
                if (!(mo instanceof Map)) continue;
                Map<String, Object> mm = (Map<String, Object>) mo;
                String pos = capital(s(mm, "partOfSpeech"));
                addAll(syn, mm.get("synonyms"));
                addAll(ant, mm.get("antonyms"));
                Object ds = mm.get("definitions");
                if (!(ds instanceof List)) continue;
                for (Object d0 : (List<Object>) ds) {
                    if (!(d0 instanceof Map)) continue;
                    Map<String, Object> d = (Map<String, Object>) d0;
                    addAll(syn, d.get("synonyms"));
                    addAll(ant, d.get("antonyms"));
                    String text = s(d, "definition").trim();
                    if (text.isEmpty() || perPos.getOrDefault(pos, 0) >= 5) continue;
                    perPos.merge(pos, 1, Integer::sum);
                    m.senses.add(new Sense(pos, text, s(d, "example").trim()));
                }
            }
        }
        // uk/us recordings first
        m.synonyms.addAll(limit(syn, 8));
        m.antonyms.addAll(limit(ant, 6));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void addAll(Set<String> to, Object list) {
        if (!(list instanceof List)) return;
        for (Object o : (List<Object>) list) if (o instanceof String && !((String) o).trim().isEmpty()) to.add(((String) o).trim());
    }

    private static List<String> limit(Set<String> s, int n) {
        List<String> out = new ArrayList<>();
        for (String x : s) {
            if (out.size() >= n) break;
            out.add(x);
        }
        return out;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ Wiktionary (wikitext)

    /** The wikitext inside a Wiktionary "parse" answer, or "" (page missing). */
    @SuppressWarnings("unchecked")
    static String wikitext(String body) {
        try {
            Object o = Json.parse(body);
            if (!(o instanceof Map)) return "";
            Object p = ((Map<String, Object>) o).get("parse");
            return p instanceof Map ? s((Map<String, Object>) p, "wikitext") : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]|]*)(?:\\|([^\\]]*))?\\]\\]");
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{([^{}]*)\\}\\}");
    private static final Pattern REF = Pattern.compile("<ref[^>]*/>|<ref[^>]*>.*?</ref>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    /** Wikitext → plain text: links become their text, labels like {{K|trans.}} become "(trans.)", other templates go. */
    static String plain(String s) {
        String t = REF.matcher(s).replaceAll("");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&");
        // innermost templates first, until none are left
        for (int guard = 0; guard < 6 && t.contains("{{"); guard++) {
            Matcher m = TEMPLATE.matcher(t);
            StringBuffer b = new StringBuffer();
            while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(template(m.group(1))));
            m.appendTail(b);
            if (b.toString().equals(t)) break;
            t = b.toString();
        }
        Matcher l = LINK.matcher(t);
        StringBuffer b = new StringBuffer();
        while (l.find()) {
            String target = l.group(1), label = l.group(2);
            String low = target.toLowerCase(Locale.ROOT);
            String rep = low.startsWith("file:") || low.startsWith("datei:") || low.startsWith("bild:") || low.startsWith("image:")
                    || target.startsWith("تصویر:") || low.startsWith("category:") ? "" : (label != null ? label : target);
            l.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        l.appendTail(b);
        t = TAG.matcher(b.toString()).replaceAll("");
        t = t.replace("'''", "").replace("''", "");
        t = t.replaceAll("\\s+", " ").replaceAll(" ([,.;:!?])", "$1").replace("( ", "(").replace(" )", ")").replace("()", "");
        return t.trim();
    }

    private static String template(String inner) {
        String[] a = inner.split("\\|");
        String name = a[0].trim();
        if (name.equals("K")) {
            List<String> parts = new ArrayList<>();
            for (int i = 1; i < a.length; i++) if (!a[i].contains("=") && !a[i].trim().isEmpty()) parts.add(a[i].trim());
            return parts.isEmpty() ? "" : "(" + String.join(", ", parts) + ")";
        }
        if (name.equals("l") || name.equals("m") || name.equals("w") || name.equals("ت")) return a.length > 2 ? a[2] : a.length > 1 ? a[1] : "";
        // short labels such as {{ugs.}}, {{trans.}}, {{refl.|:}}
        if (name.endsWith(".") && name.length() <= 12 && !name.contains(" ")) return "(" + name + ")";
        if (name.equals("Pl.")) return "Plural:";
        return "";
    }

    /** Lines of one "{{Name}}" block (":[1] …"), up to the next block. */
    private static List<String> block(String section, String name) {
        List<String> out = new ArrayList<>();
        int i = section.indexOf("{{" + name + "}}");
        if (i < 0) return out;
        String[] lines = section.substring(i + name.length() + 4).split("\n");
        for (String line : lines) {
            if (line.startsWith("{{") && !line.startsWith("{{K") || line.startsWith("==")) break;
            if (line.trim().isEmpty()) {
                if (!out.isEmpty()) break;
                continue;
            }
            out.add(line);
        }
        return out;
    }

    private static final Pattern NUM = Pattern.compile("^:\\s*\\[([0-9]+)[a-z]?(?:,[^\\]]*)?\\]\\s*(.*)$");

    /** Lines about the first three meanings only (synonyms of rare meanings would mislead). */
    private static List<String> main(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            Matcher n = NUM.matcher(line);
            // labelled lines ("ugs.:", "salopp, abwertend:") hold slang, not plain synonyms
            if (line.contains("{{") || line.contains(":''") || line.contains("<ref")) continue;
            if (n.find() && Integer.parseInt(n.group(1)) <= 3) out.add(line);
        }
        return out;
    }

    /** "a|[[b|c]]|d" → a, [[b|c]], d (bars inside links and templates do not split). */
    private static List<String> topLevel(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '[' || c == '{') && i + 1 < s.length() && s.charAt(i + 1) == c) { depth++; i++; }
            else if ((c == ']' || c == '}') && i + 1 < s.length() && s.charAt(i + 1) == c) { depth--; i++; }
            else if (c == '|' && depth <= 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }

    /** Words linked in a block ("[[laufen]], [[rennen]]"), without duplicates. */
    private static List<String> linked(List<String> lines, int max) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : lines) {
            Matcher m = LINK.matcher(TEMPLATE.matcher(line).replaceAll(""));
            while (m.find() && out.size() < max) {
                String w = m.group(2) != null ? m.group(2) : m.group(1);
                if (!w.contains(":") && !w.trim().isEmpty()) out.add(w.trim());
            }
        }
        return new ArrayList<>(out);
    }

    private static final Pattern GERMAN_POS = Pattern.compile("=== *\\{\\{Wortart\\|([^|}]+)\\|Deutsch\\}\\}");

    /** German Wiktionary: meanings, one example each, synonyms, opposites, word family, picture and recording. */
    static Meaning parseGerman(String wikitext, String word) {
        Meaning m = new Meaning(Lang.DE, word);
        String t = wikitext;
        int de = t.indexOf("({{Sprache|Deutsch}})");
        if (de < 0) return m;
        int end = t.indexOf("\n== ", de);
        String german = end < 0 ? t.substring(de) : t.substring(de, end);
        Matcher pm = GERMAN_POS.matcher(german);
        List<int[]> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (pm.find()) {
            starts.add(new int[]{pm.start()});
            names.add(pm.group(1).trim());
        }
        Set<String> syn = new LinkedHashSet<>(), ant = new LinkedHashSet<>(), fam = new LinkedHashSet<>();
        int blocks = 0;
        for (int k = 0; k < starts.size() && blocks < 3; k++) {
            int s = starts.get(k)[0], e = k + 1 < starts.size() ? starts.get(k + 1)[0] : german.length();
            String sec = german.substring(s, e);
            String pos = germanPos(names.get(k));
            List<String> defs = block(sec, "Bedeutungen");
            if (defs.isEmpty()) continue;
            blocks++;
            Map<String, String> examples = new LinkedHashMap<>();
            for (String line : block(sec, "Beispiele")) {
                Matcher n = NUM.matcher(line);
                if (!n.find() || examples.containsKey(n.group(1))) continue;
                if (line.contains("<ref") || line.contains("„")) continue;      // quotations with sources are long
                String ex = plain(n.group(2));
                if (ex.length() > 4 && ex.length() < 140) examples.put(n.group(1), ex);
            }
            int count = 0;
            for (String line : defs) {
                Matcher n = NUM.matcher(line);
                if (!n.find()) continue;
                String text = plain(n.group(2));
                if (text.isEmpty() || count >= 5) continue;
                count++;
                m.senses.add(new Sense(pos, text, examples.get(n.group(1))));
            }
            syn.addAll(linked(main(block(sec, "Synonyme")), 8));
            syn.addAll(linked(main(block(sec, "Sinnverwandte Wörter")), 8));
            ant.addAll(linked(main(block(sec, "Gegenwörter")), 6));
            fam.addAll(linked(block(sec, "Wortbildungen"), 12));
            // a name's picture (Essen the city, a person) does not show the word's meaning
            boolean name = names.get(k).matches("(?i)Toponym|Eigenname|Vorname|Nachname|Familienname|Abkürzung");
            if (m.image.isEmpty() && !name) {
                Matcher b = Pattern.compile("\\|Bild(?: ?1)?=([^\\n]+)").matcher(sec);
                if (b.find()) {
                    List<String> parts = topLevel(b.group(1));
                    m.image = parts.get(0).trim();
                    m.imageCaption = parts.size() >= 3 ? plain(parts.get(parts.size() - 1)) : "";
                    m.imageSource = "Wiktionary";
                }
            }
            if (m.ipa.isEmpty()) {
                Matcher i = Pattern.compile("\\{\\{Lautschrift\\|([^}|]+)\\}\\}").matcher(sec);
                if (i.find()) m.ipa = "[" + i.group(1) + "]";
            }
            if (m.audio.isEmpty()) {
                Matcher a = Pattern.compile("\\{\\{Audio\\|([^}|]+)").matcher(sec);
                while (a.find()) {
                    String f = a.group(1).trim();
                    if (f.startsWith("De-") && !f.startsWith("De-at-") && !f.startsWith("De-ch-")) m.audio.add(fileUrl(f, 0));
                }
            }
        }
        syn.remove(word);
        m.synonyms.addAll(limit(syn, 8));
        m.antonyms.addAll(limit(ant, 6));
        fam.remove(word);
        m.family.addAll(limit(fam, 12));
        return m;
    }

    private static String germanPos(String w) {
        switch (w) {
            case "Substantiv": return "Noun";
            case "Verb": return "Verb";
            case "Adjektiv": return "Adjective";
            case "Adverb": return "Adverb";
            case "Präposition": return "Preposition";
            case "Konjunktion": case "Subjunktion": return "Conjunction";
            case "Pronomen": case "Personalpronomen": return "Pronoun";
            case "Interjektion": return "Interjection";
            default: return w;
        }
    }

    /** Persian Wiktionary: the numbered meanings ("# …") under each word-type heading. */
    static Meaning parsePersian(String wikitext, String word) {
        Meaning m = new Meaning(Lang.FA, word);
        int fa = wikitext.indexOf("==فارسی==");
        if (fa < 0) fa = 0;
        String[] lines = wikitext.substring(fa).split("\n");
        String pos = null;
        int count = 0;
        for (String line : lines) {
            String l = line.trim();
            if (l.startsWith("==") && !l.startsWith("===") && l.length() > 2 && !l.contains("فارسی")) break;
            if (l.startsWith("===")) {
                String h = plain(l.replaceAll("=", "")).trim();
                if (h.contains("برگردان") || h.contains("ترجمه")) break;
                pos = h.contains("مثال") || h.contains("ریشه") || h.contains("آوایش") || h.contains("تلفظ") ? null : persianPos(h);
                continue;
            }
            Matcher a = Pattern.compile("\\[\\[(?:File|file|پرونده):([^|\\]]+\\.(?:wav|ogg|mp3))").matcher(l);
            if (a.find() && m.audio.isEmpty()) m.audio.add(fileUrl(a.group(1).trim(), 0));
            if (pos == null || !l.startsWith("#") || l.startsWith("#:") || l.startsWith("#*")) continue;
            String text = plain(l.substring(1));
            if (text.length() < 2 || count >= 6) continue;
            count++;
            m.senses.add(new Sense(pos, text, null));
        }
        return m;
    }

    private static String persianPos(String h) {
        if (h.contains("اسم")) return "Noun";
        if (h.contains("صفت")) return "Adjective";
        if (h.contains("قید")) return "Adverb";
        if (h.contains("فعل") || h.contains("مصدر")) return "Verb";
        if (h.contains("حرف")) return "Preposition";
        return h.isEmpty() ? "Meaning" : h;
    }

    private static final Pattern EN_TERM = Pattern.compile("\\{\\{(?:l|der[2-5]?|rel[2-5]?|col[2-5]?(?:-u)?|der-top[^|]*)\\|en\\|([^}]*)\\}\\}");

    /** English Wiktionary: the "Derived terms" and "Related terms" of the English section (word family). */
    static List<String> englishFamily(String wikitext, String word, int max) {
        Set<String> out = new LinkedHashSet<>();
        int en = wikitext.indexOf("==English==");
        if (en < 0) return new ArrayList<>();
        int end = wikitext.indexOf("\n==", en + 11);
        while (end >= 0 && wikitext.startsWith("\n===", end)) end = wikitext.indexOf("\n==", end + 3);
        String sec = end < 0 ? wikitext.substring(en) : wikitext.substring(en, end);
        for (String head : new String[]{"Derived terms", "Related terms"}) {
            int i = sec.indexOf(head + "=");
            if (i < 0) continue;
            int j = sec.indexOf("\n=", i + head.length() + 2);
            String part = j < 0 ? sec.substring(i) : sec.substring(i, j);
            Matcher m = EN_TERM.matcher(part);
            while (m.find() && out.size() < max) {
                for (String x : m.group(1).split("[|,]")) {
                    String w = x.trim();
                    if (w.isEmpty() || w.contains("=") || w.contains("<")) continue;
                    if (w.split(" ").length > 3) continue;
                    if (!w.equalsIgnoreCase(word)) out.add(w);
                    if (out.size() >= max) break;
                }
            }
            Matcher l = LINK.matcher(part);
            while (l.find() && out.size() < max) {
                String w = l.group(1).trim();
                if (!w.contains(":") && !w.equalsIgnoreCase(word) && !w.isEmpty()) out.add(w);
            }
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ Wikipedia

    /** The first {@code sentences} sentences of a Wikipedia summary (not of a list of meanings); "" if none. */
    @SuppressWarnings("unchecked")
    static String wikipediaExtract(String body, int sentences) {
        try {
            Object o = Json.parse(body);
            if (!(o instanceof Map)) return "";
            Map<String, Object> m = (Map<String, Object>) o;
            if (!"standard".equals(s(m, "type"))) return "";
            String t = s(m, "extract").replace('\n', ' ').trim();
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (String part : t.split("(?<=[.!?؟])\\s+")) {
                if (n++ >= sentences) break;
                if (b.length() > 0) b.append(' ');
                b.append(part);
            }
            return b.length() > 400 ? b.substring(0, 400).trim() + "…" : b.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The thumbnail of a Wikipedia article, or "" (no article, a list of meanings, or no picture). */
    @SuppressWarnings("unchecked")
    static String wikipediaImage(String body) {
        try {
            Object o = Json.parse(body);
            if (!(o instanceof Map)) return "";
            Map<String, Object> m = (Map<String, Object>) o;
            if (!"standard".equals(s(m, "type"))) return "";
            Object th = m.get("thumbnail");
            return th instanceof Map ? s((Map<String, Object>) th, "source") : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
