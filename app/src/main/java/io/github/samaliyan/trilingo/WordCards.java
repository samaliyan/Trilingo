package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.List;

/**
 * Which word the Word card widget shows: a saved word that is due for review first; when none is due, the
 * saved words one after another for practice; with no saved words, the recent lookups. Pure Java (tested).
 */
final class WordCards {
    static final int DUE = 0, PRACTICE = 1, RECENT = 2, EMPTY = 3;

    private WordCards() {
    }

    static final class Pick {
        final Notebook.Card card;
        final int mode, due;

        Pick(Notebook.Card card, int mode, int due) {
            this.card = card;
            this.mode = mode;
            this.due = due;
        }
    }

    /**
     * The card to show. {@code current} is the key of the card on the widget now: kept while it is still
     * the right one (so the widget does not change under the user's finger); {@code next} moves on from it.
     */
    static Pick pick(Notebook nb, List<Lookup> recent, long now, String current, boolean next) {
        List<Notebook.Card> due = nb.due(null, null, now, 500);
        if (!due.isEmpty()) {
            Notebook.Card c = due.get(0);
            if (!next && current != null) for (Notebook.Card d : due) if (d.key().equals(current)) c = d;
            return new Pick(c, DUE, due.size());
        }
        List<Notebook.Card> saved = new ArrayList<>();
        for (Notebook.Card c : nb.saved(null)) if (!c.back.isEmpty()) saved.add(c);
        if (!saved.isEmpty()) return new Pick(rotate(saved, current, next), PRACTICE, 0);
        List<Notebook.Card> rec = new ArrayList<>();
        if (recent != null) {
            for (Lookup l : recent) {
                String w = Text.clean(l.query);
                String back = Notebook.summary(l);
                if (w.isEmpty() || w.split(" ").length > 3 || back.isEmpty()) continue;
                Notebook.Card c = new Notebook.Card();
                c.lang = l.source;
                c.word = w;
                c.back = back;
                c.folder = "";
                rec.add(c);
                if (rec.size() >= 30) break;
            }
        }
        if (!rec.isEmpty()) return new Pick(rotate(rec, current, next), RECENT, 0);
        return new Pick(null, EMPTY, 0);
    }

    /** The current card again, or (next) the one after it; the first when the current one is gone. */
    private static Notebook.Card rotate(List<Notebook.Card> list, String current, boolean next) {
        int at = -1;
        for (int i = 0; i < list.size() && current != null; i++) if (list.get(i).key().equals(current)) at = i;
        if (at < 0) return list.get(0);
        return list.get(next ? (at + 1) % list.size() : at);
    }
}
