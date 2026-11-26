package io.github.samaliyan.trilingo;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Coverage check against the real services (run on a CI machine, not on the phone): for each test query
 * the lookup, the meaning and the picture the app would show. Usage: Coverage queries.txt
 */
public final class Coverage {
    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "Trilingo/1.8 (https://github.com/samaliyan/Trilingo)");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        int code = c.getResponseCode();
        if (code == 404) return "";
        if (code >= 400) throw new IOException("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] a) throws Exception {
        String A = "app/src/main/assets/";
        Words en = Words.read(Lang.EN, new FileInputStream(A + "words_en.txt"));
        Words de = Words.read(Lang.DE, new FileInputStream(A + "words_de.txt"));
        Nouns nouns = Nouns.read(new FileInputStream(A + "de_nouns.txt"));
        Resolver res = new Resolver(en, de, nouns, Dict.read(Lang.EN, new FileInputStream(A + "dict_en.txt")),
                Dict.read(Lang.DE, new FileInputStream(A + "dict_de.txt")));
        Engine engine = new Engine(Engine.NET);
        List<String> qs = Files.readAllLines(Paths.get(a[0]), StandardCharsets.UTF_8);
        System.out.println("query\tsource\ttranslations\tmeanings\tpicture\tnote");
        for (String q : qs) {
            q = q.trim();
            if (q.isEmpty() || q.startsWith("#")) continue;
            Lookup l = null;
            String note = "";
            // as the main screen does: a spoken line in Persian when the query starts with "fa:"
            Lang spoken = null;
            if (q.startsWith("fa:")) {
                spoken = Lang.FA;
                q = q.substring(3).trim();
            }
            Resolver.Prepared p = res.prepare(q, null, spoken);
            if (p.corrected) note += "corrected:" + p.text + " ";
            if (p.number >= 0 || p.decimal != null) l = res.offline(p, false, System.currentTimeMillis());
            if (l == null) {
                try {
                    l = engine.translate(p.text, p.source != null ? p.source : res.guess(p.text), Services.GOOGLE,
                            System.currentTimeMillis());
                    if (l.hasSuggestion()) note += "did-you-mean:" + l.suggestion + " ";
                } catch (Exception e) {
                    note += "lookup failed: " + e.getMessage();
                    l = res.offline(p, true, System.currentTimeMillis());
                }
            }
            if (l == null) {
                System.out.println(q + "\t-\t-\t-\t-\t" + note);
                continue;
            }
            StringBuilder tr = new StringBuilder();
            for (Lookup.Result r : l.results) tr.append(r.lang.code).append('=').append(r.text).append(' ');
            String meanings = "-", pic = "-";
            {
                try {
                    Meaning m = MeaningSource.load(l, Coverage::get, res);
                    if (!m.note.isEmpty()) note += "meaning:" + m.note + (m.noteWord.isEmpty() ? "" : "(" + m.noteWord + ")") + " ";
                    meanings = String.valueOf(m.senses.size()) + (m.senses.isEmpty() ? "" : " (" + m.senses.get(0).text.replace('\t', ' ') + ")");
                    if (meanings.length() > 90) meanings = meanings.substring(0, 90);
                    pic = m.image.isEmpty() ? "-" : m.imageSource + ":" + m.image;
                    if (pic.length() > 70) pic = pic.substring(0, 70);
                } catch (Exception e) {
                    note += "meaning failed: " + e;
                }
            }
            System.out.println(q + "\t" + l.source.code + "\t" + tr.toString().trim() + "\t" + meanings + "\t" + pic + "\t" + note);
            Thread.sleep(1500);
        }
        engine.shutdown();
    }
}
