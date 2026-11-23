# Trilingo

A small Android app for quick word translation between **English, German and Persian** — without opening a browser
and choosing "English to Persian" or "German to English" every time.

- **Type once, see both** — type a word in any of the three languages; its translation into the other two appears
  as you type. The language is detected automatically, or tap *English*, *German* or *Persian* to choose it.
- **All meanings** — not only one translation: the different meanings, grouped by word type (noun, verb…).
- **Examples** — simple real sentences with the word (from [Tatoeba](https://tatoeba.org)), translated into the other two languages.
- **Conjugation** — for verbs, the conjugation in German, English and Persian, built in (works offline).
- **Widget** — a search bar on the home screen that opens the app with the keyboard up. Make it taller to see your recent words.
- **From any app** — select a word and choose *Trilingo* in the text menu, share text to it, or use the Quick Settings tile.
- **Works offline for known words** — every looked-up word is saved (can be turned off).
- **Listen and copy** — speaker button when the phone's text-to-speech has the language; one-tap copy.
- **Two services** — Google Translate (default, with dictionary meanings) and MyMemory as backup; if one does not answer, the other is used.
- **Updates itself** from this repository's Releases (asks first; Android shows its own install confirmation).

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
