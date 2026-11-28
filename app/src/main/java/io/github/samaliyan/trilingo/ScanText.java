package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.List;

/**
 * The words read from a photo, with their place: which ones lie in a chosen area, in reading order.
 * Pure Java, so it is tested without a phone.
 */
final class ScanText {
    private ScanText() {
    }

    static final class Item {
        final String text;
        final int left, top, right, bottom, line;

        Item(String text, int left, int top, int right, int bottom, int line) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.line = line;
        }

        float cx() {
            return (left + right) / 2f;
        }

        float cy() {
            return (top + bottom) / 2f;
        }
    }

    /** True when the middle of the word lies in the area (so a word half inside counts once, not never). */
    static boolean inside(Item w, float l, float t, float r, float b) {
        float x = w.cx(), y = w.cy();
        return x >= Math.min(l, r) && x <= Math.max(l, r) && y >= Math.min(t, b) && y <= Math.max(t, b);
    }

    /** The words in the area, line by line as they were read ("Ausgang\nExit"). */
    static String textIn(List<Item> words, float l, float t, float r, float b) {
        List<Item> in = new ArrayList<>();
        for (Item w : words) if (inside(w, l, t, r, b)) in.add(w);
        return join(in);
    }

    /** Words in reading order: by line, then left to right; a new line where the recognised line changes. */
    static String join(List<Item> in) {
        List<Item> s = new ArrayList<>(in);
        s.sort((a, b) -> a.line != b.line ? Integer.compare(a.line, b.line) : Integer.compare(a.left, b.left));
        StringBuilder out = new StringBuilder();
        int line = Integer.MIN_VALUE;
        for (Item w : s) {
            if (out.length() > 0) out.append(w.line == line ? ' ' : '\n');
            out.append(w.text);
            line = w.line;
        }
        return out.toString();
    }

    /** The word nearest to a tap (inside it, or within {@code reach}), or null. */
    static Item at(List<Item> words, float x, float y, float reach) {
        Item best = null;
        float bestD = Float.MAX_VALUE;
        for (Item w : words) {
            boolean in = x >= w.left && x <= w.right && y >= w.top && y <= w.bottom;
            float dx = w.cx() - x, dy = w.cy() - y;
            float d = in ? 0 : dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = w;
            }
        }
        return best != null && bestD <= reach * reach ? best : null;
    }

    /** Whether a word is above the learner's level ("B2" word for an "A2" learner); unknown words are not. */
    static boolean aboveLevel(String wordLevel, String myLevel) {
        return wordLevel != null && myLevel != null && wordLevel.compareTo(myLevel) > 0;
    }
}
