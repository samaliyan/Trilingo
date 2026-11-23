#!/usr/bin/env bash
# Runs the pure-Java unit tests (no Android SDK, no network). Exit code != 0 on failure.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
S=app/src/main/java/io/github/samaliyan/trilingo
javac -encoding UTF-8 -Xlint:all -Werror -d "$OUT" "$S/Lang.java" "$S/Json.java" "$S/Text.java" "$S/Lookup.java" \
  "$S/Services.java" "$S/Engine.java" "$S/History.java" tests/java/io/github/samaliyan/trilingo/CoreTest.java
java -Dstdout.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.CoreTest
