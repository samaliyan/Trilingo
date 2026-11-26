# Trilingo

A small Android app for quick word translation between **English, German and Persian** — without opening a browser
and choosing "English to Persian" or "German to English" every time.

- **Type once, see both** — type a word in any of the three languages; its translation into the other two appears
  as you type. The language is detected automatically, or tap *English*, *German* or *Persian* to choose it.
- **German articles** — der / die / das in colour, with the plural, for every German noun.
- **Suggestions while typing** — dicti → dictionary; German words typed without ä/ö/ü/ß are understood (mude → müde).
- **All meanings** — not only one translation: the different meanings, grouped by word type (noun, verb…).
- **Examples** — simple real sentences with the word (from [Tatoeba](https://tatoeba.org)), translated into the other two languages.
- **Meaning first** — what a word means in its own language (like a monolingual dictionary), with a picture, examples, synonyms, opposites and word family; the translation can stay hidden until you want it.
- **Pronunciation practice** — a real recording of the word, record yourself and compare.
- **Word levels** — A1 … C2 next to English and German words.
- **Voice search** — say a word you heard but cannot spell.
- **Saved words and review** — star words into folders, review them as flashcards with spaced repetition, export to Anki.
- **Scan text** — photo or shared picture → tap any word to translate it (text recognition on the phone).
- **Check a sentence** and **grammar exercises** — practice questions, "find the mistake", five at a time with new sets on demand.
- **Grammar** — English and German grammar from A1 to C1 (109 lessons), explained in Persian, English or German, with tables and examples. Search by topic in any of the three languages or by a word (fernsehen → separable verbs); a looked-up word shows its grammar lessons too.
- **Conjugation** — for verbs, the conjugation in German, English and Persian, built in (works offline).
- **Widget** — a search bar on the home screen that opens the app with the keyboard up. Make it taller to see your recent words.
- **From any app** — select a word and choose *Trilingo* in the text menu, share text to it, or use the Quick Settings tile.
- **Works offline** — a built-in dictionary of about 14,000 common English and German words (and Persian through them), and every looked-up word is saved (can be turned off).
- **Quick-translate bubble** — an optional button over other apps: copy text, tap it, read the translation.
- **Learning** — saved words with folders, search and sort; review with spaced repetition; listen & write; words from a text at your level; grammar practice; backup and restore.
- **Listen and copy** — speaker button when the phone's text-to-speech has the language; one-tap copy.
- **Two services** — Google Translate (default, with dictionary meanings) and MyMemory as backup; if one does not answer, the other is used.
- **Updates itself** from this repository's Releases (asks first; Android shows its own install confirmation).

## Data

German nouns from [German Wiktionary](https://de.wiktionary.org) via [german-nouns](https://github.com/gambolputty/german-nouns)
(CC BY-SA 4.0); grammar lessons in `app/src/main/assets/grammar` (written for this app, format in `Grammar.java`); word frequencies from [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (MIT);
example sentences from [Tatoeba](https://tatoeba.org) (CC BY 2.0 FR); meanings from [Wiktionary](https://www.wiktionary.org) (CC BY-SA)
and the [Free Dictionary API](https://dictionaryapi.dev); pictures from Wikimedia Commons / Wikipedia; word levels from
[Words-CEFR-Dataset](https://github.com/Maximax67/Words-CEFR-Dataset) (MIT, based on CEFR-J) and
[german-cefr-wordlist](https://github.com/ph98/german-cefr-wordlist) (MIT) — `tools/build_levels.py`;
sentence checks by [LanguageTool](https://languagetool.org); text recognition by Google ML Kit; the offline dictionary from
Wiktionary translation tables (CC BY-SA) via [wiktextract / kaikki.org](https://kaikki.org) — `tools/build_dict.py`. `tools/build_data.py` rebuilds `app/src/main/assets`.

## Install

Download `Trilingo.apk` from the [latest release](https://github.com/samaliyan/Trilingo/releases/latest) and open it.
Android 8.0 or newer.

## Build

Requires JDK 17 and the Android SDK (API 34). No third-party libraries.

```
gradle assembleRelease
```

## Tests

| What | How |
| --- | --- |
| Core logic (JSON, both services' answers, language detection, history, fallback between services) | `bash tests/run_core.sh` (runs on every build) |
| Full app on Android 8.0 and 14 emulators with real internet: every screen, widget on the home screen, offline mode, in-app update; screenshots go to the `ui-results-26` / `ui-results-34` branches | *Actions → UI test (emulator) → Run workflow*, or push to the `ui-test` branch |

## Releases

Every push to `main` tests, lints and signs the APK, then publishes it as the GitHub release for the `versionName`
in `app/build.gradle`, with notes from `CHANGELOG.md`. The repository secret `SIGNING_PASSWORD` is required.
