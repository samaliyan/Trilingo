package io.github.samaliyan.trilingo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A verb's conjugation table in one language: tenses, each with one form per person. Pure Java. */
final class Conjugation {
    /** One tense. {@code forms[i]} belongs to {@code persons[i]}. */
    static final class Tense {
        final String name, nativeName;
        final String[] persons, forms;

        Tense(String name, String nativeName, String[] persons, String[] forms) {
            if (persons.length != forms.length) throw new IllegalArgumentException("persons/forms");
            this.name = name;
            this.nativeName = nativeName;
            this.persons = persons.clone();
            this.forms = forms.clone();
        }

        String form(int i) {
            return forms[i];
        }
    }

    /** A labelled fact shown above the tables, e.g. "Perfect with" → "sein". */
    static final class Info {
        final String label, value;

        Info(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    final Lang lang;
    final String verb;
    final List<Info> info;
    final List<Tense> tenses;

    Conjugation(Lang lang, String verb, List<Info> info, List<Tense> tenses) {
        this.lang = lang;
        this.verb = verb;
        this.info = Collections.unmodifiableList(new ArrayList<>(info));
        this.tenses = Collections.unmodifiableList(new ArrayList<>(tenses));
    }

    Tense tense(String name) {
        for (Tense t : tenses) if (t.name.equals(name)) return t;
        return null;
    }

    /** Conjugation of an infinitive in the given language, or null if it cannot be conjugated. */
    static Conjugation of(Lang lang, String infinitive) {
        if (infinitive == null || infinitive.trim().isEmpty()) return null;
        switch (lang) {
            case DE: return GermanVerbs.conjugate(infinitive);
            case EN: return EnglishVerbs.conjugate(infinitive);
            default: return PersianVerbs.conjugate(infinitive);
        }
    }
}
