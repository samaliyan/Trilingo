#!/usr/bin/env python3
"""Builds app/src/main/assets/levels_en.txt and levels_de.txt ("word<TAB>A1".."C2", lower case).

Sources (both MIT):
  English: https://github.com/Maximax67/Words-CEFR-Dataset (CEFR-J levels, extended with frequency data)
  German:  https://github.com/ph98/german-cefr-wordlist (levels anchored on FrequencyWords frequencies)
Usage: python3 tools/build_levels.py <Words-CEFR-Dataset dir> <german-cefr-wordlist dir>
"""
import csv, json, os, sys

LEVELS = ["A1", "A2", "B1", "B2", "C1", "C2"]
ASSETS = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")


def our_words(lang):
    with open(os.path.join(ASSETS, f"words_{lang}.txt"), encoding="utf-8") as f:
        return {line.split("\t")[0].strip().lower() for line in f if line.strip()}


def english(src):
    words = {r["word_id"]: r["word"].lower() for r in csv.DictReader(open(os.path.join(src, "csv", "words.csv"), encoding="utf-8"))}
    best = {}
    for r in csv.DictReader(open(os.path.join(src, "csv", "word_pos.csv"), encoding="utf-8")):
        w = words.get(r["word_id"])
        if not w or not r["level"]:
            continue
        lv = int(float(r["level"]))
        if lv >= 6:            # 6 is also used for "unknown": not shown
            continue
        best[w] = min(best.get(w, 9), lv)
    # the hand-made CEFR-J list wins where it has the word
    for r in csv.DictReader(open(os.path.join(src, "datasets", "word_list_cefr.csv"), encoding="utf-8"), delimiter=";"):
        w = r["headword"].lower()
        if r["CEFR"] in LEVELS:
            lv = LEVELS.index(r["CEFR"]) + 1
            best[w] = min(best.get(w, lv), lv) if w in best and best[w] < lv else lv
    return best


def german(src):
    best = {}
    for x in json.load(open(os.path.join(src, "data", "german_cefr_wordlist.json"), encoding="utf-8")):
        if x["level"] in LEVELS:
            w = x["word"].lower()
            best[w] = min(best.get(w, 9), LEVELS.index(x["level"]) + 1)
    return best


def write(lang, best):
    keep = our_words(lang)
    rows = sorted((w, l) for w, l in best.items() if w in keep and 1 <= l <= 6)
    with open(os.path.join(ASSETS, f"levels_{lang}.txt"), "w", encoding="utf-8") as f:
        for w, l in rows:
            f.write(f"{w}\t{LEVELS[l - 1]}\n")
    print(lang, len(rows))


if __name__ == "__main__":
    write("en", english(sys.argv[1]))
    write("de", german(sys.argv[2]))
