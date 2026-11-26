package io.github.samaliyan.trilingo;

/** The three languages of the app. Pure Java (unit-tested without Android). */
enum Lang {
    EN("en", "English", false),
    DE("de", "German", false),
    FA("fa", "Persian", true);

    final String code, label;
    final boolean rtl;

    Lang(String code, String label, boolean rtl) {
        this.code = code;
        this.label = label;
        this.rtl = rtl;
    }

    /** Language for a code such as "en", "de-AT" or "fa"; null for any other language. */
    static Lang of(String code) {
        if (code == null) return null;
        String c = code.trim().toLowerCase(java.util.Locale.ROOT);
        int dash = c.indexOf('-');
        if (dash > 0) c = c.substring(0, dash);
        for (Lang l : values()) if (l.code.equals(c)) return l;
        return null;
    }

    /**
     * The two other languages, most useful first:
     * English word → Persian, German; German word → English, Persian; Persian word → English, German.
     */
    Lang[] targets() {
        switch (this) {
            case EN: return new Lang[]{FA, DE};
            case DE: return new Lang[]{EN, FA};
            default: return new Lang[]{EN, DE};
        }
    }
}
