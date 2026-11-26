#!/bin/sh
# Coverage of translation, meaning and picture for many kinds of input, against the real services.
# Needs internet (run on CI: the probe workflow). Usage: tools/coverage/run.sh [queries file]
set -e
cd "$(dirname "$0")/../.."
S=app/src/main/java/io/github/samaliyan/trilingo
OUT=build/coverage
rm -rf "$OUT" && mkdir -p "$OUT"
# the app's plain-Java classes (those that use nothing from Android)
javac -encoding UTF-8 -nowarn -d "$OUT" $(grep -L "^import android" $S/*.java) tools/coverage/Coverage.java
java -Dstdout.encoding=UTF-8 -cp "$OUT" io.github.samaliyan.trilingo.Coverage "${1:-tools/coverage/queries.txt}"
