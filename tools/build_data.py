#!/usr/bin/env python3
"""
Builds the word data the app ships in app/src/main/assets (run again only to refresh the data):

  words_en.txt, words_de.txt, words_fa.txt  one word per line, most frequent first
                                             (suggestions while typing, spelling help, German umlauts)
  de_nouns.txt                               German nouns: lemma <TAB> gender:plural[;gender:plural]

Sources (downloaded separately, read as plain data):
  FrequencyWords 2018 (MIT, word frequencies from OpenSubtitles)   github.com/hermitdave/FrequencyWords
  german-nouns (CC BY-SA 4.0, from German Wiktionary)              github.com/gambolputty/german-nouns
  wooorm/dictionaries (Hunspell word lists; only used to filter, not shipped)

Usage: build_data.py <FrequencyWords/content/2018> <german-nouns/german_nouns/nouns.csv> <dictionaries/dictionaries> <assets dir>
"""
import csv
import os
import re
import sys

FREQ, NOUNS, DICTS, OUT = sys.argv[1:5]
csv.field_size_limit(10 ** 8)


def freq(lang, limit):
    out = []
    with open(os.path.join(FREQ, lang, lang + "_50k.txt"), encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split(" ")
            if len(parts) == 2:
                out.append((parts[0], int(parts[1])))
            if len(out) >= limit:
                break
    return out


def stems(lang, exclude=frozenset()):
    """
    Every word form of a Hunspell dictionary: each stem with its suffix and prefix rules applied
    (one level, which covers ordinary inflection). Case is kept (German nouns are capitalised).
    """
    aff = open(os.path.join(DICTS, lang, "index.aff"), encoding="utf-8", errors="ignore").read().split("\n")
    long_flags = any(l.split()[:2] == ["FLAG", "long"] for l in aff)
    skip = set()          # ONLYINCOMPOUND / FORBIDDENWORD: the stem and its forms are not words on their own
    need = set()          # NEEDAFFIX: the stem alone is not a word, its forms are
    rules = {}            # flag -> (kind, cross, [(strip, add, regex)])
    i = 0
    while i < len(aff):
        p = aff[i].split()
        if p and p[0] in ("ONLYINCOMPOUND", "FORBIDDENWORD") and len(p) > 1:
            skip.add(p[1])
        if p and p[0] == "NEEDAFFIX" and len(p) > 1:
            need.add(p[1])
        if len(p) == 4 and p[0] in ("SFX", "PFX") and p[3].isdigit():
            kind, flag, cross, n = p[0], p[1], p[2] == "Y", int(p[3])
            lst = []
            for line in aff[i + 1:i + 1 + n]:
                q = line.split()
                if len(q) < 5 or q[0] != kind:
                    continue
                strip = "" if q[2] == "0" else q[2]
                add = q[3].split("/")[0]
                add = "" if add == "0" else add
                cond = q[4]
                try:
                    rx = re.compile(cond + "$") if kind == "SFX" else re.compile("^" + cond)
                except re.error:
                    continue
                lst.append((strip, add, rx))
            rules.setdefault(flag, (kind, cross, []))[2].extend(lst)
            i += 1 + n
            continue
        i += 1

    def split_flags(fl):
        if long_flags:
            return [fl[k:k + 2] for k in range(0, len(fl) - 1, 2)]
        return list(fl)

    out = set()
    with open(os.path.join(DICTS, lang, "index.dic"), encoding="utf-8", errors="ignore") as f:
        next(f, None)
        for line in f:
            if line.startswith("\t") or line.startswith("#"):
                continue
            part = line.strip().split("\t")[0].split(" ")[0]
            word, _, fl = part.partition("/")
            if not word:
                continue
            flags = split_flags(fl)
            if set(flags) & (skip | set(exclude)):
                continue
            if not (set(flags) & need):
                out.add(word)
            sfx, pfx = [], []
            for fg in flags:
                r = rules.get(fg)
                if not r:
                    continue
                kind, cross, lst = r
                for strip, add, rx in lst:
                    if kind == "SFX" and rx.search(word) and word.endswith(strip):
                        form = word[: len(word) - len(strip)] + add
                        out.add(form)
                        if cross:
                            sfx.append(form)
                    elif kind == "PFX" and rx.search(word) and word.startswith(strip):
                        form = add + word[len(strip):]
                        out.add(form)
                        if cross:
                            pfx.append((add, strip))
            for form in sfx:
                for add, strip in pfx:
                    if form.startswith(strip):
                        out.add(add + form[len(strip):])
    return out


def write(name, words):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        for w in words:
            f.write(w + "\n")
    print(name, len(words), os.path.getsize(os.path.join(OUT, name)), "bytes")


# ---------------------------------------------------------------- German nouns
nouns = {}          # lemma -> list of (gender, plural)
forms = set()       # every noun form (for capitalisation of frequency words)
with open(NOUNS, encoding="utf-8") as f:
    r = csv.reader(f)
    head = next(r)
    ix = {k: i for i, k in enumerate(head)}
    for row in r:
        lemma, pos = row[ix["lemma"]], row[ix["pos"]]
        if "Substantiv" not in pos:
            continue
        if any(x in pos for x in ("Affix", "Lexem", "Toponym", "Vorname", "Nachname", "Eigenname", "Abkürzung")):
            continue
        if not re.fullmatch(r"[A-ZÄÖÜ][a-zäöüß]+(-[A-ZÄÖÜa-zäöüß][a-zäöüß]+)?", lemma):
            continue
        for k, i in ix.items():
            if row[i] and ("singular" in k or "plural" in k) and re.fullmatch(r"[A-ZÄÖÜ][a-zäöüß-]+", row[i]):
                forms.add(row[i])
        genders = [row[ix[k]] for k in ("genus", "genus 1", "genus 2", "genus 3", "genus 4") if row[ix[k]] in ("m", "f", "n")]
        if not genders:
            continue
        plural = ""
        for k in ("nominativ plural", "nominativ plural 1", "nominativ plural*"):
            if row[ix[k]] and re.fullmatch(r"[A-ZÄÖÜ][a-zäöüß-]+", row[ix[k]]):
                plural = row[ix[k]]
                break
        lst = nouns.setdefault(lemma, [])
        for g in genders:
            if all(g != x[0] for x in lst):
                lst.append((g, plural))

# Keep nouns people meet: in the German frequency list (full), or short words, or ones another noun ends with.
full = set()
with open(os.path.join(FREQ, "de", "de_full.txt"), encoding="utf-8") as f:
    for line in f:
        p = line.split(" ")
        if len(p) == 2 and int(p[1]) >= 2:
            full.add(p[0])
kept = []
for lemma, lst in sorted(nouns.items()):
    plurals = {p for _, p in lst if p}
    if lemma.lower() in full or any(p.lower() in full for p in plurals) or len(lemma) <= 7:
        kept.append(lemma + "\t" + ";".join(g + ":" + p for g, p in lst))
write("de_nouns.txt", kept)

# ---------------------------------------------------------------- word lists
LETTERS = {"en": r"[a-z]+", "de": r"[a-zäöüß]+", "fa": r"[؀-ۿ‌]+"}


# English: only lower-case dictionary words (capitalised Hunspell entries are names) and their regular forms.
en_dic = {w for w in stems("en") if w.islower()}
print("English dictionary forms", len(en_dic))
en_words = []
for w, c in freq("en", 50000):
    if len(w) < 2 and w not in ("a", "i"):
        continue
    if not re.fullmatch(LETTERS["en"], w):
        continue
    if w in en_dic:
        en_words.append(w)
write("words_en.txt", en_words[:40000])

# German: capitalised when it is a noun form, else lower case if the dictionary knows it.
de_dic = stems("de")
print("German dictionary forms", len(de_dic))
# Lower-case words that stand on their own (not compound parts such as "mädchen-" or "augen-").
de_lower = {w for w in stems("de", exclude={"x", "y", "z", "o", "h", "c"}) if w.islower()}
# Function words the dictionary lists in an unusual way; always lower case.
de_lower |= {"wenn", "nichts", "etwas", "einer", "wegen", "danke", "bitte", "hast", "muss", "soll", "werde",
             "denke", "brauche", "würden", "gute", "guten", "ganze", "kleine", "neue", "neuen", "einen", "einem"}
de_words, seen = [], set()
for w, c in freq("de", 50000):
    if len(w) < 2 or not re.fullmatch(LETTERS["de"], w):
        continue
    cap = w[0].upper() + w[1:]
    noun = cap in forms or cap in nouns          # Wiktionary noun forms (names are not in it)
    if w in de_lower:
        word = w                                  # ich, sie, haben … (also listed as nouns: das Ich, das Haben)
    elif noun:
        word = cap
    elif w in de_lower:
        word = w
    else:
        continue
    if word not in seen:
        seen.add(word)
        de_words.append(word)
write("words_de.txt", de_words[:45000])

# Persian: Arabic ي/ك unified with Persian ی/ک; words the dictionary knows, or very frequent ones.
fa_dic = {w.replace("ي", "ی").replace("ك", "ک") for w in stems("fa")}
fa_words, seen = [], set()
for w, c in freq("fa", 50000):
    w = w.replace("ي", "ی").replace("ى", "ی").replace("ك", "ک")
    if len(w) < 2 or not re.fullmatch(LETTERS["fa"], w):
        continue
    if w in fa_dic and w not in seen:       # dictionary words only (subtitles are full of colloquial forms)
        seen.add(w)
        fa_words.append(w)
write("words_fa.txt", fa_words[:40000])
