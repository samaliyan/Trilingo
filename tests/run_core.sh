#!/usr/bin/env bash
# Runs the pure-Java unit tests (no Android SDK, no network). Exit code != 0 on failure.
set -euo pipefail
export LC_ALL=C.UTF-8
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
S=app/src/main/java/io/github/samaliyan/trilingo
# all plain-Java classes of the app (those that use nothing from Android)
javac -encoding UTF-8 -Xlint:all -Werror -d "$OUT" $(grep -L "^import android" "$S"/*.java) \
  tests/java/io/github/samaliyan/trilingo/CoreTest.java tests/java/io/github/samaliyan/trilingo/VerbTest.java tests/java/io/github/samaliyan/trilingo/WordsTest.java \
  tests/java/io/github/samaliyan/trilingo/GrammarTest.java tests/java/io/github/samaliyan/trilingo/LearnTest.java \
  tests/java/io/github/samaliyan/trilingo/StudyTest.java tests/java/io/github/samaliyan/trilingo/ResolveTest.java
java -Dstdout.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.CoreTest
java -Dstdout.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.VerbTest
java -Dstdout.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.WordsTest
java -Dstdout.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.GrammarTest
java -Dstdout.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.LearnTest
java -Dstdout.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.StudyTest
java -Dstdout.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.ResolveTest
