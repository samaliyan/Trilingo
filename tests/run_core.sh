#!/usr/bin/env bash
# Runs the pure-Java unit tests (no Android SDK, no network). Exit code != 0 on failure.
set -euo pipefail
export LC_ALL=C.UTF-8
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
S=app/src/main/java/io/github/samaliyan/trilingo
javac -encoding UTF-8 -Xlint:all -Werror -d "$OUT" "$S/Lang.java" "$S/Json.java" "$S/Text.java" "$S/Lookup.java" \
  "$S/Services.java" "$S/Engine.java" "$S/History.java" \
  "$S/Conjugation.java" "$S/GermanVerbs.java" "$S/EnglishVerbs.java" "$S/PersianVerbs.java" "$S/Verbs.java" "$S/Examples.java" \
  "$S/Words.java" "$S/Umlauts.java" "$S/Nouns.java" "$S/Phrasal.java" "$S/Grammar.java" "$S/Meaning.java" "$S/Levels.java" "$S/Notebook.java" "$S/Exercises.java" "$S/MeaningSource.java" \
  tests/java/io/github/samaliyan/trilingo/CoreTest.java tests/java/io/github/samaliyan/trilingo/VerbTest.java tests/java/io/github/samaliyan/trilingo/WordsTest.java \
  tests/java/io/github/samaliyan/trilingo/GrammarTest.java tests/java/io/github/samaliyan/trilingo/LearnTest.java
java -Dstdout.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.CoreTest
java -Dstdout.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.VerbTest
java -Dstdout.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.WordsTest
java -Dstdout.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.GrammarTest
java -Dstdout.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.LearnTest
