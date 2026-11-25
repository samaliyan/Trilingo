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
import java.util.List;

/**
 * Looked-up words, newest first, kept in one JSON file. Also the offline cache: a word that is in the
 * history is shown from here without asking the network. Pure Java; not thread-safe (used on the UI thread).
 */
final class History {
    static final int MAX = 300;
    /** A longer word typed within this time after a shorter one replaces it ("hous" → "house"). */
    static final long MERGE_MS = 8_000;

    private final File file;
    private final List<Lookup> items = new ArrayList<>();

    History(File file) {
        this.file = file;
        load();
    }

    List<Lookup> items() {
        return Collections.unmodifiableList(items);
    }

    /** Saved lookup for this text; with {@code forced} only one from that language. */
    Lookup find(String q, Lang forced) {
        String k = Text.key(q);
        for (Lookup l : items) {
            if (Text.key(l.query).equals(k) && (forced == null || l.source == forced)) return l;
        }
        return null;
    }

    /** Adds (or moves to the top) a lookup. Returns true if the list changed. */
    boolean add(Lookup l) {
        String key = l.key();
        for (int k = 0; k < items.size(); k++) {
            if (items.get(k).key().equals(key)) {
                items.remove(k);
                break;
            }
        }
        // Typing "hous" then "house": the unfinished word is replaced, not kept as its own entry.
        if (!items.isEmpty()) {
            Lookup top = items.get(0);
            String a = Text.key(top.query), b = Text.key(l.query);
            if (l.time - top.time >= 0 && l.time - top.time < MERGE_MS && !a.equals(b)
                    && (b.startsWith(a) || a.startsWith(b))) items.remove(0);
        }
        items.add(0, l);
        while (items.size() > MAX) items.remove(items.size() - 1);
        save();
        return true;
    }

    /** Puts a removed lookup back where it was (undo). */
    void restore(Lookup l, int index) {
        for (Lookup x : items) if (x.key().equals(l.key())) return;
        items.add(Math.max(0, Math.min(index, items.size())), l);
        while (items.size() > MAX) items.remove(items.size() - 1);
        save();
    }

    void remove(Lookup l) {
        if (items.remove(l)) save();
    }

    void clear() {
        items.clear();
        save();
    }

    // ------------------------------------------------------------------ file

    private void load() {
        items.clear();
        if (file == null || !file.isFile()) return;
        try {
            String s = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            List<Object> a = Json.arr(Json.parse(s));
            if (a == null) return;
            for (Object o : a) {
                Lookup l = Lookup.fromJson(o);
                if (l != null && items.size() < MAX) items.add(l);
            }
        } catch (IOException | IllegalArgumentException e) {
            items.clear();          // a damaged file starts a fresh history instead of crashing
        }
    }

    /** All lookups as a JSON array (the file's format, also used in backups). */
    String toJson() {
        StringBuilder b = new StringBuilder("[");
        for (int k = 0; k < items.size(); k++) {
            if (k > 0) b.append(",\n");
            b.append(items.get(k).toJson());
        }
        return b.append("]").toString();
    }

    /** Adds a backup's lookups (a word both have keeps the newer one), newest first, at most {@link #MAX}. */
    int merge(List<Object> saved) {
        java.util.Map<String, Lookup> byKey = new java.util.LinkedHashMap<>();
        for (Lookup l : items) byKey.put(l.key(), l);
        int added = 0;
        if (saved != null) {
            for (Object o : saved) {
                Lookup l = Lookup.fromJson(o);
                if (l == null) continue;
                Lookup have = byKey.get(l.key());
                if (have == null) added++;
                if (have == null || l.time > have.time) byKey.put(l.key(), l);
            }
        }
        items.clear();
        items.addAll(byKey.values());
        items.sort((a, b) -> Long.compare(b.time, a.time));
        while (items.size() > MAX) items.remove(items.size() - 1);
        save();
        return added;
    }

    /** Written to a temporary file first and then renamed, so a crash never leaves half a file. */
    private void save() {
        if (file == null) return;
        String b = toJson();
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(b);
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        }
    }
}
