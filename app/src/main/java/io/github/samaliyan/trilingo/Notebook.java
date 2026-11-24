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

    int count(String folder) {
        return saved(folder).size();
    }

    // ------------------------------------------------------------------ review

    /**
     * Cards to review now, most overdue first: saved words (of a folder, or all), and — when
     * {@code recent} is given — recently looked-up words too (they get a card the first time).
     */
    List<Card> due(String folder, List<Lookup> recent, long now, int max) {
        if (recent != null) {
            for (Lookup l : recent) {
                if (l.source == Lang.FA || Text.clean(l.query).split(" ").length > 3) continue;
                String back = l.results.isEmpty() ? "" : summary(l);
                if (get(l.source, l.query) == null) {
                    Card c = cardFor(l.source, l.query, back, l.time);
                    c.saved = false;
                }
            }
        }
        List<Card> out = new ArrayList<>();
        for (Card c : cards.values()) {
            boolean inScope = recent != null ? true : c.saved && (folder == null || folder.equals(c.folder));
            if (inScope && c.due <= now && !c.back.isEmpty()) out.add(c);
        }
        out.sort((a, b) -> Long.compare(a.due, b.due));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
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
        if (!file.exists()) return;
        try {
            Map<String, Object> m = Json.obj(Json.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
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
                cards.put(c.key(), c);
            }
        } catch (IOException | RuntimeException e) {
            // a broken file starts empty
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    void save() {
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
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(b.toString());
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            tmp.renameTo(file);
        }
    }
}
