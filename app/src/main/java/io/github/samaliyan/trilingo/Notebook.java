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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Saved words (with folders) and the spaced-repetition state of every word that was reviewed.
 * One JSON file. Pure Java; used on the UI thread.
 *
 * Review: "Again" shows the word again in a few minutes, "Good" doubles (×2.5) the interval,
 * "Easy" stretches it more. A new word starts with 1 day after "Good" and 4 days after "Easy".
 */
final class Notebook {
    static final long MINUTE = 60_000L, DAY = 24 * 60 * MINUTE;
    static final int AGAIN = 0, GOOD = 1, EASY = 2;
    static final int SORT_NEWEST = 0, SORT_OLDEST = 1, SORT_AZ = 2, SORT_LEVEL = 3, SORT_HARDEST = 4, SORT_DUE = 5;

    static final class Card {
        String word, back, folder;
        Lang lang;
        boolean saved;
        long added, due;
        double interval;          // days
        int reps, lapses;

        String key() {
            return key(lang, word);
        }

        static String key(Lang l, String w) {
            return l.code + "|" + Text.key(w);
        }

        boolean isNew() {
            return reps == 0;
        }
    }

    private final File file;
    private final List<String> folders = new ArrayList<>();
    private final Map<String, Card> cards = new LinkedHashMap<>();

    Notebook(File file) {
        this.file = file;
        load();
    }

    // ------------------------------------------------------------------ folders and saved words

    List<String> folders() {
        return Collections.unmodifiableList(folders);
    }

    boolean addFolder(String name) {
        String n = Text.clean(name);
        if (n.isEmpty()) return false;
        for (String f : folders) if (f.equalsIgnoreCase(n)) return false;
        folders.add(n);
        save();
        return true;
    }

    void renameFolder(String from, String to) {
        String n = Text.clean(to);
        int i = folders.indexOf(from);
        if (i < 0 || n.isEmpty()) return;
        folders.set(i, n);
        for (Card c : cards.values()) if (from.equals(c.folder)) c.folder = n;
        save();
    }

    /** Removes the folder; its words stay saved, without a folder. */
    void deleteFolder(String name) {
        folders.remove(name);
        for (Card c : cards.values()) if (name.equals(c.folder)) c.folder = "";
        save();
    }

    Card get(Lang l, String word) {
        return cards.get(Card.key(l, word));
    }

    boolean isSaved(Lang l, String word) {
        Card c = get(l, word);
        return c != null && c.saved;
    }

    /** Saves (or updates) a word with its translation summary in a folder ("" = no folder). */
    Card save(Lang l, String word, String back, String folder, long now) {
        Card c = cardFor(l, word, back, now);
        c.word = Text.clean(word);                     // the spelling chosen now ("Tisch", not an earlier "tisch")
        c.saved = true;
        c.folder = folder == null ? "" : folder;
        if (!c.folder.isEmpty() && !folders.contains(c.folder)) folders.add(c.folder);
        save();
        return c;
    }

    void unsave(Lang l, String word) {
        Card c = get(l, word);
        if (c == null) return;
        c.saved = false;
        if (c.reps == 0) cards.remove(c.key());
        save();
    }

    private Card cardFor(Lang l, String word, String back, long now) {
        Card c = get(l, word);
        if (c == null) {
            c = new Card();
            c.lang = l;
            c.word = Text.clean(word);
            c.folder = "";
            c.added = now;
            c.due = now;
            cards.put(c.key(), c);
        }
        if (back != null && !back.isEmpty()) c.back = back;
        if (c.back == null) c.back = "";
        return c;
    }

    /** Saved words, newest first; {@code folder} null = all, "" = without a folder. */
    List<Card> saved(String folder) {
        List<Card> out = new ArrayList<>();
        for (Card c : cards.values()) if (c.saved && (folder == null || folder.equals(c.folder))) out.add(c);
        out.sort((a, b) -> Long.compare(b.added, a.added));
        return out;
    }

    /** Words whose spelling or translation contains the search (any case; "mude" finds "müde"). */
    static List<Card> filter(List<Card> in, String query) {
        String q = fold(Text.clean(query));
        if (q.isEmpty()) return new ArrayList<>(in);
        List<Card> out = new ArrayList<>();
        for (Card c : in) if (fold(c.word).contains(q) || fold(c.back).contains(q)) out.add(c);
        return out;
    }

    private static String fold(String s) {
        String x = Text.unifyPersian(s == null ? "" : s).toLowerCase(Locale.ROOT).replace("\u200C", "");
        return x.replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss");
    }

    /** Sorts saved words; {@code level} gives a word's level ("B1") or null, for {@link #SORT_LEVEL}. */
    static void sort(List<Card> list, int mode, java.util.function.Function<Card, String> level) {
        java.util.Comparator<Card> newest = (a, b) -> Long.compare(b.added, a.added);
        java.util.Comparator<Card> az = (a, b) -> {
            int c = fold(a.word).compareTo(fold(b.word));
            return c != 0 ? c : a.word.compareTo(b.word);
        };
        switch (mode) {
            case SORT_OLDEST: list.sort((a, b) -> Long.compare(a.added, b.added)); break;
            case SORT_AZ: list.sort(az); break;
            case SORT_LEVEL:
                list.sort((a, b) -> {
                    String la = level.apply(a), lb = level.apply(b);
                    int c = (la == null ? "Z" : la).compareTo(lb == null ? "Z" : lb);   // words without a level last
                    return c != 0 ? c : az.compare(a, b);
                });
                break;
            case SORT_HARDEST:
                list.sort((a, b) -> {
                    int c = Integer.compare(b.lapses, a.lapses);
                    return c != 0 ? c : newest.compare(a, b);
                });
                break;
            case SORT_DUE: list.sort((a, b) -> Long.compare(a.due, b.due)); break;
            default: list.sort(newest);
        }
    }

    /** Saves several words at once (one file write); words already saved keep their folder. Returns how many are new. */
    int saveAll(Lang l, List<String> words, java.util.function.Function<String, String> back, String folder, long now) {
        int added = 0;
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (isSaved(l, w)) continue;
            Card c = cardFor(l, w, back.apply(w), now - i);       // keeps the text's order among "newest first"
            c.word = Text.clean(w);
            c.saved = true;
            c.folder = folder == null ? "" : folder;
            added++;
        }
        if (folder != null && !folder.isEmpty() && !folders.contains(folder)) folders.add(folder);
        save();
        return added;
    }

    /** Sets the translation of a saved word (filled in later, when it was saved without one). */
    void setBack(Lang l, String word, String back) {
        Card c = get(l, word);
        if (c == null || back == null || back.isEmpty()) return;
        c.back = back;
        save();
    }

    /** Saved words that still have no translation. */
    List<Card> withoutBack() {
        List<Card> out = new ArrayList<>();
        for (Card c : cards.values()) if (c.saved && c.back.isEmpty()) out.add(c);
        return out;
    }

    /** Number of saved words and of words with review progress. */
    int size() {
        return cards.size();
    }

    /**
     * Adds a backup's words: missing words are added, a word both have keeps the one with more review
     * progress, and is saved when either is. Returns the number of words added.
     */
    int merge(String json) {
        Notebook other = new Notebook(null);
        other.read(json);
        int added = 0;
        for (String f : other.folders) if (!folders.contains(f)) folders.add(f);
        for (Card o : other.cards.values()) {
            Card c = cards.get(o.key());
            if (c == null) {
                cards.put(o.key(), o);
                added++;
                continue;
            }
            if (o.reps > c.reps) {
                c.due = o.due;
                c.interval = o.interval;
                c.reps = o.reps;
                c.lapses = o.lapses;
            }
            if (o.saved && !c.saved) {
                c.saved = true;
                c.folder = o.folder;
            }
            if (c.back.isEmpty()) c.back = o.back;
            if (c.folder.isEmpty() && c.saved) c.folder = o.folder;
            if (o.added > 0 && (c.added == 0 || o.added < c.added)) c.added = o.added;
        }
        save();
        return added;
    }

    int count(String folder) {
        return saved(folder).size();
    }

    // ------------------------------------------------------------------ review

    /**
     * Cards to review now, most overdue first: saved words (of a folder, or all), and — when
     * {@code recent} is given — recently looked-up words too (they get a card the first time).
     */
    List<Card> due(String folder, List<Lookup> recent, long now, int max) {
        List<Card> out = new ArrayList<>();
        java.util.Set<String> recentKeys = new java.util.HashSet<>();
        if (recent != null) {
            for (Lookup l : recent) {
                String k = Card.key(l.source, l.query);
                if (!recentKeys.add(k) || cards.containsKey(k)) continue;
                // a recent word gets a card only when it is answered (see answer): nothing is stored before
                Card c = new Card();
                c.lang = l.source;
                c.word = Text.clean(l.query);
                c.back = summary(l);
                c.folder = "";
                c.added = l.time;
                c.due = l.time;
                if (c.due <= now && !c.back.isEmpty()) out.add(c);
            }
        }
        for (Card c : cards.values()) {
            // saved words (of the folder); with recent words also those already reviewed and still in Recent
            boolean inScope = c.saved ? (recent != null || folder == null || folder.equals(c.folder))
                    : recent != null && recentKeys.contains(c.key());
            if (inScope && c.due <= now && !c.back.isEmpty()) out.add(c);
        }
        out.sort((a, b) -> Long.compare(a.due, b.due));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /**
     * Recent lookups worth reviewing: real words of English or German (in the word lists), not misspellings,
     * not long sentences, and with a real translation. Typos and half-typed words never become cards.
     */
    static List<Lookup> reviewable(List<Lookup> recent, Words en, Words de) {
        List<Lookup> out = new ArrayList<>();
        if (recent == null || en == null || de == null) return out;
        for (Lookup l : recent) {
            if (l.source == Lang.FA || l.hasSuggestion() || l.results.isEmpty()) continue;
            String q = Text.clean(l.query);
            String[] parts = q.split(" ");
            if (q.length() < 2 || parts.length > 3) continue;
            Words w = l.source == Lang.EN ? en : de;
            boolean known = true;
            for (String p : parts) known &= w.known(p.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", ""));
            if (!known) continue;
            boolean echo = true;
            for (Lookup.Result r : l.results) echo &= Text.key(r.text).equals(Text.key(q));
            if (!echo) out.add(l);
        }
        return out;
    }

    /** A word removed from Recent is no longer reviewed (unless it is saved). */
    void forgetRecent(Lang l, String word) {
        Card c = get(l, word);
        if (c != null && !c.saved) {
            cards.remove(c.key());
            save();
        }
    }

    /** Recent was cleared: the review progress of words that were not saved goes too. */
    void forgetAllRecent() {
        cards.values().removeIf(c -> !c.saved);
        save();
    }

    /** When the card would come back for each answer ("10 min", "1 day", "4 days"), for the buttons. */
    static String[] preview(Card c) {
        String[] out = new String[3];
        for (int g = 0; g < 3; g++) {
            Card x = copy(c);
            grade(x, g, 0);
            out[g] = when(x.due);
        }
        return out;
    }

    private static Card copy(Card c) {
        Card x = new Card();
        x.interval = c.interval;
        x.reps = c.reps;
        x.lapses = c.lapses;
        return x;
    }

    private static String when(long ms) {
        if (ms < DAY) return Math.max(1, ms / MINUTE) + " min";
        long d = Math.round((double) ms / DAY);
        if (d < 31) return d + (d == 1 ? " day" : " days");
        long m = Math.round(d / 30.0);
        return m + (m == 1 ? " month" : " months");
    }

    /** Applies an answer: sets the next due time. */
    static void grade(Card c, int answer, long now) {
        if (answer == AGAIN) {
            c.lapses++;
            c.interval = 0;
            c.due = now + 10 * MINUTE;
        } else {
            double next;
            if (c.interval < 1) next = answer == EASY ? 4 : 1;
            else next = c.interval * (answer == EASY ? 3.5 : 2.5);
            c.interval = Math.min(365, next);
            c.due = now + Math.round(c.interval * DAY);
        }
        c.reps++;
    }

    void answer(Card c, int answer, long now) {
        grade(c, answer, now);
        if (!cards.containsKey(c.key())) cards.put(c.key(), c);       // a recent word's first review
        save();
    }

    /** Number of reviewed words that are not due for at least three weeks ("learned"). */
    int learned() {
        int n = 0;
        for (Card c : cards.values()) if (c.interval >= 21) n++;
        return n;
    }

    /** "table · میز" — the translations of a lookup in one line, for the back of a card. */
    static String summary(Lookup l) {
        StringBuilder b = new StringBuilder();
        for (Lookup.Result r : l.results) {
            if (r.text.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(r.summary(2));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ export

    /** Tab-separated file Anki can import (front, back, tags), for saved words of a folder (null = all). */
    String ankiExport(String folder) {
        StringBuilder b = new StringBuilder("#separator:tab\n#html:false\n#columns:Front\tBack\tTags\n");
        for (Card c : saved(folder)) {
            b.append(clean(c.word)).append('\t').append(clean(c.back.replace('\n', ';'))).append('\t')
                    .append("trilingo ").append(c.lang.code)
                    .append(c.folder.isEmpty() ? "" : " " + c.folder.replace(' ', '_')).append('\n');
        }
        return b.toString();
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace("\r", "");
    }

    // ------------------------------------------------------------------ file

    private void load() {
        if (file == null || !file.exists()) return;
        try {
            read(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (IOException e) {
            // a broken file starts empty
        }
    }

    /** Reads words and folders from JSON (the file's format); damaged parts are skipped. */
    private void read(String json) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(json));
            if (m == null) return;
            List<Object> fs = Json.arr(m, "folders");
            if (fs != null) for (Object o : fs) if (o instanceof String) folders.add((String) o);
            List<Object> cs = Json.arr(m, "cards");
            if (cs == null) return;
            for (Object o : cs) {
                Map<String, Object> x = Json.obj(o);
                Lang l = x == null ? null : Lang.of(Json.str(x, "l"));
                String w = x == null ? null : Json.str(x, "w");
                if (l == null || w == null) continue;
                Card c = new Card();
                c.lang = l;
                c.word = w;
                c.back = orEmpty(Json.str(x, "b"));
                c.folder = orEmpty(Json.str(x, "f"));
                c.saved = Json.num(x, "s", 0) == 1;
                c.added = (long) Json.num(x, "a", 0);
                c.due = (long) Json.num(x, "d", 0);
                c.interval = Json.num(x, "i", 0);
                c.reps = (int) Json.num(x, "r", 0);
                c.lapses = (int) Json.num(x, "x", 0);
                if (!c.saved && c.reps == 0) continue;           // a recent word never reviewed (older versions kept these)
                cards.put(c.key(), c);
            }
        } catch (RuntimeException e) {
            // a broken file starts empty
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    void save() {
        if (file == null) return;
        String json = toJson();
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(json);
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            tmp.renameTo(file);
        }
    }

    /** Everything as JSON (the file's format, also used in backups). */
    String toJson() {
        StringBuilder b = new StringBuilder("{\"folders\":[");
        for (int i = 0; i < folders.size(); i++) b.append(i > 0 ? "," : "").append(Json.quote(folders.get(i)));
        b.append("],\"cards\":[");
        boolean first = true;
        for (Card c : cards.values()) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"l\":").append(Json.quote(c.lang.code)).append(",\"w\":").append(Json.quote(c.word))
                    .append(",\"b\":").append(Json.quote(c.back)).append(",\"f\":").append(Json.quote(c.folder))
                    .append(",\"s\":").append(c.saved ? 1 : 0).append(",\"a\":").append(c.added).append(",\"d\":").append(c.due)
                    .append(",\"i\":").append(String.format(Locale.ROOT, "%.3f", c.interval)).append(",\"r\":").append(c.reps)
                    .append(",\"x\":").append(c.lapses).append('}');
        }
        b.append("]}");
        return b.toString();
    }
}
