#!/usr/bin/env python3
"""Builds the offline dictionary: app/src/main/assets/dict_en.txt and dict_de.txt.

Source: Wiktionary translation tables (CC BY-SA), as extracted by wiktextract (https://kaikki.org):
  English words -> German and Persian: en.wiktionary, file kaikki.org-dictionary-English.jsonl(.gz)
  German words  -> English and Persian: de.wiktionary, file kaikki.org-dictionary-Deutsch.jsonl(.gz)

Which words: the CEFR word lists (levels_*.txt) and the most frequent words (words_*.txt), up to LIMIT
per language, most frequent first (the app ranks reverse Persian matches by this order).

Line format (one headword per line, UTF-8):
  word<TAB>pos<TAB>LANG=term,term;pos2=term<TAB>LANG2=...
  e.g. "house\tnoun\tde=noun:Haus,Gebäude;verb:unterbringen\tfa=noun:خانه,منزل"
Usage: python3 tools/build_dict.py <English.jsonl[.gz]> <Deutsch.jsonl[.gz]>
"""
import gzip, json, os, re, sys, time, urllib.error, urllib.parse, urllib.request
from collections import OrderedDict

ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
LIMIT = 12000
MAX_POS, MAX_TERMS = 3, 4
SKIP_TAGS = {"archaic", "obsolete", "dated", "rare", "humorous", "nonstandard", "dialectal", "proscribed",
             "vulgar", "derogatory", "offensive", "Switzerland", "Liechtenstein", "Austria", "historical"}
POS_EN = {"noun": "noun", "verb": "verb", "adj": "adjective", "adv": "adverb", "pron": "pronoun",
          "prep": "preposition", "conj": "conjunction", "intj": "interjection", "num": "number",
          "det": "determiner", "article": "article", "particle": "particle", "name": "proper noun",
          "phrase": "phrase", "prep_phrase": "phrase"}
HARAKAT = re.compile("[ً-ٰٟـ]")
LATIN = re.compile("[A-Za-z]")


def opened(path):
    return gzip.open(path, "rt", encoding="utf-8") if path.endswith(".gz") else open(path, encoding="utf-8")


def wanted(lang):
    """Headwords to keep (lower case) -> frequency rank."""
    rank = {}
    with open(os.path.join(ASSETS, f"words_{lang}.txt"), encoding="utf-8") as f:
        for i, line in enumerate(f):
            w = line.split("\t")[0].strip().lower()
            if w and w not in rank:
                rank[w] = i
    out = {}
    for w, r in rank.items():
        if r < LIMIT:
            out[w] = r
    with open(os.path.join(ASSETS, f"levels_{lang}.txt"), encoding="utf-8") as f:
        for line in f:
            w = line.split("\t")[0].strip().lower()
            if w and w not in out:
                out[w] = rank.get(w, 10 ** 6)
    return out


def persian(t):
    t = HARAKAT.sub("", t).replace("ي", "ی").replace("ك", "ک").strip()
    return "" if not t or LATIN.search(t) else t


def clean(term, lang):
    t = (term or "").strip()
    if not t or len(t) > 40 or t.startswith("-") or t.endswith("-") or any(c in t for c in ",;|\t=:()[]"):
        return ""
    if lang == "fa":
        return persian(t)
    if lang == "en" and t.lower().startswith("to ") and len(t) > 3:
        t = t[3:]
    return t


def add(entries, word, pos, lang, term):
    e = entries.setdefault(word, OrderedDict())
    p = e.setdefault(lang, OrderedDict())
    terms = p.setdefault(pos, [])
    if term and term not in terms and len(terms) < MAX_TERMS:
        terms.append(term)


RANK = {}


def rank(lang):
    if lang not in RANK:
        r = {}
        with open(os.path.join(ASSETS, f"words_{lang}.txt"), encoding="utf-8") as f:
            for i, line in enumerate(f):
                r.setdefault(line.split("\t")[0].strip().lower(), i)
        RANK[lang] = r
    return RANK[lang]


def add_senses(entries, word, pos, lang, senses):
    """senses: [[(term, tags)…] per meaning, in order]. The first meaning gives up to 3 terms (the most common
    words first), every further meaning its first term; a plural form only when its meaning has nothing else."""
    r = rank(lang)
    for i, items in enumerate(senses):
        singular = [t for t, tags in items if "plural" not in tags]
        terms = singular or [t for t, _ in items]
        if i == 0:
            terms = sorted(terms, key=lambda t: r.get(t.lower(), 10 ** 6))
        for t in terms[:3 if i == 0 else 1]:
            add(entries, word, pos, lang, t)


def grouped(translations, lang_ok, key):
    """Translations of the wanted language, grouped by meaning (in the page's order), cleaned."""
    groups = OrderedDict()
    for t in translations:
        if not lang_ok(t):
            continue
        tags = set(t.get("tags", []))
        if tags & SKIP_TAGS or any(x.endswith("-German") for x in tags):
            continue
        term = clean(t.get("word"), t.get("lang_code"))
        if term:
            groups.setdefault(key(t), []).append((term, tags))
    return list(groups.values())


def all_translations(o):
    """Translations attached to the meanings (in their order: the main meaning first), then the entry's own."""
    out = []
    for i, s in enumerate(o.get("senses", [])):
        for t in s.get("translations", []):
            t = dict(t)
            t.setdefault("sense", "#%d" % i)
            out.append(t)
    return out + list(o.get("translations", []))


def english(path, keep):
    entries, poses = {}, {}
    with opened(path) as f:
        for line in f:
            if '"translations"' not in line:
                continue
            o = json.loads(line)
            w = o.get("word", "")
            if w.lower() not in keep or o.get("lang_code") != "en":
                continue
            pos = POS_EN.get(o.get("pos", ""), "")
            if not pos:
                continue
            ts = all_translations(o)
            sense = lambda t: t.get("sense", "")
            add_senses(entries, w, pos, "de", grouped(ts, lambda t: t.get("lang_code") == "de" and t.get("lang") == "German", sense))
            fa = grouped(ts, lambda t: t.get("lang_code") == "fa" and t.get("lang") in ("Persian", "Iranian Persian"), sense)
            if not fa:              # Dari only when there is no Iranian Persian at all
                fa = grouped(ts, lambda t: t.get("lang_code") == "fa" and t.get("lang") == "Dari", sense)
            add_senses(entries, w, pos, "fa", fa)
            poses.setdefault(w, [])
            if pos not in poses[w]:
                poses[w].append(pos)
    return entries, poses


POS_DE = {"Substantiv": "noun", "Verb": "verb", "Adjektiv": "adjective", "Adverb": "adverb", "Pronomen": "pronoun",
          "Personalpronomen": "pronoun", "Präposition": "preposition", "Konjunktion": "conjunction",
          "Subjunktion": "conjunction", "Interjektion": "interjection", "Numerale": "number", "Kardinalzahl": "number",
          "Artikel": "article", "Partikel": "particle", "Grußformel": "phrase", "Redewendung": "phrase",
          "noun": "noun", "verb": "verb", "adj": "adjective", "adv": "adverb", "pron": "pronoun", "prep": "preposition",
          "conj": "conjunction", "intj": "interjection", "num": "number", "article": "article", "particle": "particle",
          "phrase": "phrase", "det": "determiner"}


def german(path, keep):
    entries, poses = {}, {}
    with opened(path) as f:
        for line in f:
            if '"translations"' not in line:
                continue
            o = json.loads(line)
            w = o.get("word", "")
            if w.lower() not in keep or o.get("lang_code") not in ("de", None):
                continue
            pos = POS_DE.get(o.get("pos", ""), "") or POS_DE.get(o.get("pos_title", ""), "")
            if not pos:
                continue
            ts = all_translations(o)

            def order(t):           # de.wiktionary numbers its meanings: sense 1 first
                m = re.match(r"\d+", str(t.get("sense_index") or t.get("sense_id") or "99"))
                return int(m.group()) if m else 99
            ts = sorted(ts, key=order)
            add_senses(entries, w, pos, "en", grouped(ts, lambda t: t.get("lang_code") == "en", order))
            add_senses(entries, w, pos, "fa", grouped(ts, lambda t: t.get("lang_code") == "fa", order))
            poses.setdefault(w, [])
            if pos not in poses[w]:
                poses[w].append(pos)
    return entries, poses


# ---------------------------------------------------------------- en.wiktionary translation subpages
# Common words keep their translation tables on a subpage ("go/translations"), which the extract does not
# include; they are read from the Wiktionary API (50 pages per request) and replace the extract's tables.

POS_HEAD = {"noun": "noun", "verb": "verb", "adjective": "adjective", "adverb": "adverb", "pronoun": "pronoun",
            "preposition": "preposition", "conjunction": "conjunction", "interjection": "interjection",
            "determiner": "determiner", "numeral": "number", "article": "article", "particle": "particle",
            "proper noun": "proper noun", "phrase": "phrase", "contraction": "phrase"}
TEMPLATE = re.compile(r"\{\{tt?\+?\|(de|fa|fa-ira)\|([^|}]+)([^}]*)\}\}")


def fetch_batch(batch):
    """The batch's subpages; a long answer comes in parts (rvcontinue), all are read."""
    titles = "|".join(w + "/translations" for w in batch)
    params = {"action": "query", "prop": "revisions", "rvprop": "content", "rvslots": "main", "maxlag": "5",
              "format": "json", "formatversion": "2", "titles": titles}
    out = {}
    for _ in range(60):
        data = None
        for attempt in range(6):
            try:
                req = urllib.request.Request("https://en.wiktionary.org/w/api.php?" + urllib.parse.urlencode(params),
                                             headers={"User-Agent": "TrilingoDictBuilder/1.0 (https://github.com/samaliyan/Trilingo; one-off build of an open dictionary)"})
                with urllib.request.urlopen(req, timeout=30) as r:
                    data = json.load(r)
                break
            except urllib.error.HTTPError as e:
                wait = int(e.headers.get("Retry-After") or 0) or 5 * (attempt + 1)
                print("wait", wait, "s:", e.code, flush=True)
                time.sleep(min(wait, 60))
            except Exception as e:
                print("retry", batch[0], e, flush=True)
                time.sleep(5)
        if data is None:
            print("gave up on", batch[0], flush=True)
            return out
        for page in data.get("query", {}).get("pages", []):
            if page.get("missing") or not page.get("revisions"):
                continue
            content = page["revisions"][0].get("slots", {}).get("main", {}).get("content")
            if content:
                out[page["title"][: -len("/translations")]] = content
        if "continue" not in data:
            return out
        params.update(data["continue"])
        time.sleep(0.4)
    return out


def fetch_subpages(words):
    """One request at a time (Wikimedia asks bots not to run requests in parallel)."""
    words = list(words)
    batches = [words[i:i + 50] for i in range(0, len(words), 50)]
    out, start = {}, time.time()
    for i, b in enumerate(batches):
        out.update(fetch_batch(b))
        time.sleep(0.4)
        if i % 40 == 0:
            print("subpages", i, "/", len(batches), len(out), "found", int(time.time() - start), "s", flush=True)
    return out


def parse_subpage(text):
    """{lang: {pos: [[(term, tags)…] per meaning]}} from a translation subpage."""
    res = {"de": OrderedDict(), "fa": OrderedDict()}
    pos, sense, top, sub = "", -1, "", ""
    for line in text.split("\n"):
        h = re.match(r"^=+\s*([^=]+?)\s*=+\s*$", line)
        if h:
            pos = POS_HEAD.get(h.group(1).strip().lower(), pos if h.group(1).lower().startswith("translations") else "")
            continue
        if line.startswith("{{trans-top") or line.startswith("{{checktrans-top"):
            sense += 1
            continue
        m = re.match(r"^\*\s*([^:{]+):", line)
        if m:
            top, sub = m.group(1).strip(), ""
        m2 = re.match(r"^\*:\s*([^:{]+):", line)
        if m2:
            sub = m2.group(1).strip()
        if not pos:
            continue
        for lang, word, rest in TEMPLATE.findall(line):
            lang = "fa" if lang == "fa-ira" else lang
            if lang == "de" and not (top == "German" and not sub):
                continue
            if lang == "fa" and not (top == "Persian" and sub in ("", "Iranian Persian")):
                continue
            term = clean(word, lang)
            if term:
                senses = res[lang].setdefault(pos, OrderedDict())
                senses.setdefault(sense, []).append((term, {"plural"} if "|p" in rest else set()))
    return {l: {p: list(s.values()) for p, s in by.items()} for l, by in res.items()}


def apply_subpages(entries, keep):
    pages = fetch_subpages(sorted(keep_words_cased(keep)))
    print("translation subpages", len(pages), flush=True)
    for w in ("go", "book", "house", "tired", "run"):
        if w in pages:
            print("subpage", w, json.dumps(parse_subpage(pages[w]), ensure_ascii=False, default=list)[:600], flush=True)
        else:
            print("no subpage for", w, flush=True)
    for w, text in pages.items():
        parsed = parse_subpage(text)
        word = w if w in entries else next((x for x in entries if x.lower() == w.lower()), w)
        for lang in ("de", "fa"):
            if not parsed[lang]:
                continue
            e = entries.setdefault(word, OrderedDict())
            e[lang] = OrderedDict()
            for pos, senses in parsed[lang].items():
                add_senses(entries, word, pos, lang, senses)


def keep_words_cased(keep):
    return [w for w in keep if " " not in w and "/" not in w]


def drop_junk(entries, lang):
    """Single letters, and German capitalised words that only repeat a common small word ("Ich", "Du", "Ja")."""
    for w in list(entries):
        if len(w) < 2:
            del entries[w]
    if lang != "de":
        return
    for w in list(entries):
        low = w.lower()
        if w == low or low not in entries:
            continue
        cap, small = entries[w], entries[low]
        cap_pos = {p for by in cap.values() for p in by}
        cap_en = {t.lower() for ts in cap.get("en", {}).values() for t in ts}
        small_en = {t.lower() for ts in small.get("en", {}).values() for t in ts}
        if cap_pos <= {"noun"} and cap_en & small_en:
            del entries[w]


def write(name, entries, keep, langs):
    rows = []
    for w, by in entries.items():
        parts = []
        # word types in the same order for both languages (the first target's order: go → verb before noun)
        order = [p for p, ts in by.get(langs[0], {}).items() if ts]
        for lang in langs:
            items = [(p, ts) for p, ts in by.get(lang, {}).items() if ts]
            items.sort(key=lambda x: order.index(x[0]) if x[0] in order else len(order))
            senses = items[:MAX_POS]
            if senses:
                parts.append(lang + "=" + ";".join(p + ":" + ",".join(ts) for p, ts in senses))
        if len(parts) == len(langs):          # both target languages, or the word is left to the online service
            rows.append((keep.get(w.lower(), 10 ** 6), w, parts))
    rows.sort(key=lambda r: (r[0], r[1]))
    with open(os.path.join(ASSETS, name), "w", encoding="utf-8") as f:
        for _, w, parts in rows:
            f.write(w + "\t" + "\t".join(parts) + "\n")
    print(name, len(rows), "words")
    return rows


def pivot(de, en):
    """German words without a Persian translation get the Persian of their English translation (same word type)."""
    lower = {}
    for w, by in en.items():
        lower.setdefault(w.lower(), by)
    n = 0
    for w, by in de.items():
        if by.get("fa") or not by.get("en"):
            continue
        for pos, terms in by["en"].items():
            for t in terms[:2]:
                src = lower.get(t.lower(), {}).get("fa", {})
                fa = src.get(pos) or (next(iter(src.values())) if src else [])
                for x in fa[:3]:
                    add(de, w, pos, "fa", x)
            if by.get("fa"):
                n += 1
                break
    print("pivoted", n)


# ---------------------------------------------------------------- from the other side: Persian and German
# entries of en.wiktionary, whose glosses are English ("خانه: house, home"; "Haus: house"). An English word
# without a Persian (or German) translation of a word type gets the words glossed with it, common ones first.

def gloss_index(path, lang):
    idx = {}
    r = rank(lang)
    with opened(path) as f:
        for line in f:
            o = json.loads(line)
            if o.get("lang_code") != lang:
                continue
            pos = POS_EN.get(o.get("pos", ""), "")
            w = clean(o.get("word", ""), lang)
            if not pos or not w or " " in w and lang == "de":
                continue
            for si, sense in enumerate(o.get("senses", [])[:2]):
                if any(t in SKIP_TAGS or t in ("form-of", "alt-of", "abbreviation", "colloquial", "informal", "Dari", "slang")
                       for t in sense.get("tags", [])):
                    continue
                for g in sense.get("glosses", [])[:1]:
                    g = re.sub(r"\([^)]*\)", "", g)
                    for part in re.split(r"[;,]", g):
                        part = part.strip().lower()
                        if part.startswith("to ") and pos == "verb":
                            part = part[3:]
                        if not part or len(part.split()) > 3:
                            continue
                        key = (part, pos)
                        score = r.get(w.lower(), 10 ** 6) + si * 50000
                        idx.setdefault(key, []).append((score, w))
    for k in idx:
        idx[k] = [w for _, w in sorted(set(idx[k]))]
    print("gloss index", lang, len(idx), flush=True)
    return idx


def fill_from_glosses(entries, idx, lang, via=None):
    """Adds `lang` translations per word type that has none. English entries look up their own word;
    German entries (via="en") look up their English translations."""
    n = 0
    for w, by in entries.items():
        have = by.get(lang, {})
        source = by.get(via, {}) if via else None
        poses = list((source or by.get("de" if lang == "fa" else "fa", {})).keys()) if via else \
            [p for l in by for p in by[l]]
        added = False
        for pos in dict.fromkeys(poses):
            if have.get(pos):
                continue
            keys = [w.lower()] if not via else [t.lower() for t in source.get(pos, [])[:2]]
            for k in keys:
                for x in idx.get((k, pos), [])[:3]:
                    add(entries, w, pos, lang, x)
                    added = True
        n += added
    print("filled from glosses", lang, n, flush=True)


def reorder(entries, idx, lang):
    """Within a word type, the translations that are glossed with exactly this word come first, most common
    first (house → خانه before مجلس, the "house of parliament" meaning)."""
    for w, by in entries.items():
        for pos, terms in by.get(lang, {}).items():
            pref = idx.get((w.lower(), pos), [])
            if not pref:
                continue
            was = list(terms)
            order = list(was)
            was.sort(key=lambda t: pref.index(t) if t in pref else len(pref) + order.index(t))
            terms[:] = was[:MAX_TERMS]


def fill_german(en, de):
    """English words without a German translation: the German words whose English translation they are."""
    rev = {}
    for w, by in de.items():
        for pos, terms in by.get("en", {}).items():
            for i, t in enumerate(terms[:2]):
                rev.setdefault((t.lower(), pos), []).append(w)
    n = 0
    for w, by in en.items():
        if by.get("de") or not by.get("fa"):
            continue
        for pos in by["fa"]:
            for x in rev.get((w.lower(), pos), [])[:3]:
                add(en, w, pos, "de", x)
        if by.get("de"):
            n += 1
    print("German filled in", n)


def main():
    """argv: English.jsonl[.gz] Deutsch.jsonl[.gz] Persian.jsonl[.gz] German.jsonl[.gz] (the last two: en.wiktionary)"""
    en_keep, de_keep = wanted("en"), wanted("de")
    print("wanted", len(en_keep), len(de_keep), flush=True)
    t = time.time()
    en, _ = english(sys.argv[1], en_keep)
    print("English extract read", int(time.time() - t), "s", flush=True)
    apply_subpages(en, en_keep)
    drop_junk(en, "en")
    fa_idx = gloss_index(sys.argv[3], "fa")
    de_idx = gloss_index(sys.argv[4], "de")
    fill_from_glosses(en, fa_idx, "fa")
    fill_from_glosses(en, de_idx, "de")
    reorder(en, fa_idx, "fa")
    de, _ = german(sys.argv[2], de_keep)
    fill_from_glosses(de, fa_idx, "fa", via="en")
    pivot(de, en)
    drop_junk(de, "de")
    fill_german(en, de)
    write("dict_en.txt", en, en_keep, ["de", "fa"])
    write("dict_de.txt", de, de_keep, ["en", "fa"])


if __name__ == "__main__":
    main()
