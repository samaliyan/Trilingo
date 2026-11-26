package io.github.samaliyan.trilingo;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The backup file: saved words with their review progress, recent words and settings, in one JSON file
 * ("trilingo-backup-2026-11-25.json"). Restoring adds to what is on the phone; nothing is deleted. Pure Java.
 */
final class Backup {
    static final String KIND = "trilingo-backup";
    static final int FORMAT = 1;

    private Backup() { }

    /** The file's text. {@code notebookJson} and {@code historyJson} are the apps' own JSON. */
    static String create(Map<String, String> settings, String notebookJson, String historyJson, long now, String version) {
        StringBuilder b = new StringBuilder();
        b.append("{\"kind\":").append(Json.quote(KIND))
                .append(",\"format\":").append(FORMAT)
                .append(",\"app\":").append(Json.quote(version == null ? "" : version))
                .append(",\"created\":").append(now)
                .append(",\"settings\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : settings.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(Json.quote(e.getKey())).append(':').append(Json.quote(e.getValue()));
        }
        b.append("},\n\"notebook\":").append(notebookJson == null ? "{}" : notebookJson)
                .append(",\n\"history\":").append(historyJson == null ? "[]" : historyJson)
                .append("}\n");
        return b.toString();
    }

    /** A read backup. */
    static final class Contents {
        final long created;
        final Map<String, String> settings = new TreeMap<>();
        final String notebookJson;
        final List<Object> history;
        final int savedWords;

        Contents(long created, String notebookJson, List<Object> history, int savedWords) {
            this.created = created;
            this.notebookJson = notebookJson;
            this.history = history;
            this.savedWords = savedWords;
        }
    }

    /** Reads a backup file; throws IllegalArgumentException when it is not one (or damaged). */
    static Contents read(String text) {
        Map<String, Object> m = Json.obj(Json.parse(text == null ? "" : text.trim()));
        if (m == null || !KIND.equals(Json.str(m, "kind"))) throw new IllegalArgumentException("not a Trilingo backup");
        if (Json.num(m, "format", 0) > FORMAT) throw new IllegalArgumentException("made by a newer version");
        Map<String, Object> nb = Json.obj(m.get("notebook"));
        int saved = 0;
        List<Object> cards = nb == null ? null : Json.arr(nb, "cards");
        if (cards != null) for (Object o : cards) if (Json.num(Json.obj(o), "s", 0) == 1) saved++;
        List<Object> history = Json.arr(m, "history");
        Contents c = new Contents((long) Json.num(m, "created", 0), nb == null ? "{}" : Json.write(nb), history, saved);
        Map<String, Object> s = Json.obj(m.get("settings"));
        if (s != null) for (Map.Entry<String, Object> e : s.entrySet()) if (e.getValue() instanceof String) c.settings.put(e.getKey(), (String) e.getValue());
        return c;
    }
}
