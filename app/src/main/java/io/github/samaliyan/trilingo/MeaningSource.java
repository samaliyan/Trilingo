package io.github.samaliyan.trilingo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads the meaning of a looked-up word in its own language, and a picture for it. Decides which sources to
 * ask and in which order; the network is passed in, so this is pure Java and tested with saved answers.
 */
final class MeaningSource {
    interface Fetch {
        /** The body of a GET answer; "" when the page does not exist (404). Throws when the network fails. */
        String get(String url) throws IOException;
    }

    private MeaningSource() { }

    /** The word whose meaning is shown: the typed word, if it is one word or a short phrase. */
    static String word(Lookup l) {
        String q = Text.clean(l.query);
        if (q.isEmpty() || q.split(" ").length > 3 || q.length() > 40) return null;
        return q;
    }

    /** The German word of a lookup (typed, or the main German translation), without an article. */
    static String germanWord(Lookup l) {
        String w = null;
        if (l.source == Lang.DE) w = Text.clean(l.query);
        else for (Lookup.Result r : l.results) if (r.lang == Lang.DE) { w = Text.clean(r.text); break; }
        if (w == null || w.isEmpty()) return null;
        String low = w.toLowerCase(Locale.ROOT);
        for (String a : new String[]{"der ", "die ", "das ", "ein ", "eine "}) if (low.startsWith(a)) w = w.substring(a.length());
        if (w.split(" ").length > 2) return null;
        return w;
    }

    static String englishWord(Lookup l) {
        if (l.source == Lang.EN) return Text.clean(l.query);
        for (Lookup.Result r : l.results) if (r.lang == Lang.EN) return Text.clean(r.text);
        return null;
    }

    /**
     * Meaning of the typed word (in its language) with a picture. {@code german} gives the right case of a German
     * word ("tisch" → "Tisch"), {@code enWords} keeps only real, common words in the English word family.
     */
    static Meaning load(Lookup l, Fetch net, java.util.function.UnaryOperator<String> german, Words enWords) throws IOException {
        String w = word(l);
        Meaning m = new Meaning(l.source, w == null ? Text.clean(l.query) : w);
        if (w == null) return m;
        Meaning own;
        switch (l.source) {
            case EN: {
                own = Meaning.parseDictionary(net.get(Meaning.dictionaryUrl(w)), w);
                try {
                    List<String> fam = Meaning.englishFamily(Meaning.wikitext(net.get(Meaning.wiktionaryUrl("en", w.replace(' ', '_')))), w, 600);
                    own.family.addAll(common(fam, enWords, 10));
                } catch (IOException e) {
                    // the family is extra; the meanings are enough
                }
                break;
            }
            case DE: {
                String page = german == null ? w : german.apply(w);
                own = Meaning.parseGerman(Meaning.wikitext(net.get(Meaning.wiktionaryUrl("de", page))), page);
                break;
            }
            default:
                own = Meaning.parsePersian(Meaning.wikitext(net.get(Meaning.wiktionaryUrl("fa", w))), w);
        }
        m = own;
        // picture: German Wiktionary has pictures chosen to show the meaning; Wikipedia's for things (nouns)
        if (m.image.isEmpty()) {
            String de = germanWord(l);
            if (de != null && l.source != Lang.DE) {
                try {
                    String page = german == null ? de : german.apply(de);
                    Meaning d = Meaning.parseGerman(Meaning.wikitext(net.get(Meaning.wiktionaryUrl("de", page))), page);
                    m.image = d.image;
                    m.imageCaption = d.imageCaption;
                    m.imageSource = d.imageSource;
                } catch (IOException e) {
                    // no picture
                }
            }
        }
        if (m.image.isEmpty() && (isNoun(m) || m.senses.isEmpty())) {
            String en = englishWord(l);
            if (en != null && !en.isEmpty() && en.split(" ").length <= 2) {
                try {
                    String title = Character.toUpperCase(en.charAt(0)) + en.substring(1).toLowerCase(Locale.ROOT);
                    String img = Meaning.wikipediaImage(net.get(Meaning.wikipediaUrl("en", title)));
                    if (!img.isEmpty()) {
                        m.image = img;
                        m.imageCaption = "";
                        m.imageSource = "Wikipedia";
                    }
                } catch (IOException e) {
                    // no picture
                }
            }
        }
        return m;
    }

    /** True when the main meaning is a noun. */
    static boolean isNoun(Meaning m) {
        return !m.senses.isEmpty() && "Noun".equals(m.senses.get(0).pos);
    }

    /** Family words that are in the word list, most frequent first. */
    static List<String> common(List<String> words, Words list, int max) {
        List<String> out = new ArrayList<>();
        if (list == null) {
            for (String w : words) if (out.size() < max) out.add(w);
            return out;
        }
        List<String> known = new ArrayList<>();
        for (String w : words) if (!w.contains(" ") && list.known(w)) known.add(w);
        known.sort((a, b) -> Integer.compare(list.rank(a), list.rank(b)));
        for (String w : known) if (out.size() < max) out.add(w);
        return out;
    }
}
