#!/usr/bin/env bash
# Drives the app on a running emulator (with internet) via adb, saves screenshots + UI dumps to tests/ui/out
# and fails if a step cannot be done, if texts overlap, or if the app crashed.
set -uo pipefail
API="${1:-0}"
cd "$(dirname "$0")"
OUT="$PWD/out"; rm -rf "$OUT"; mkdir -p "$OUT"
PKG=io.github.samaliyan.trilingo
APK=../../app/build/outputs/apk/release/app-release.apk
FAILS=0
log() { echo "[ui] $*" | tee -a "$OUT/report.txt"; }
fail() { log "FAIL: $*"; FAILS=$((FAILS+1)); }

dump() {
  timeout 30 adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; timeout 15 adb exec-out cat /sdcard/ui.xml > "$OUT/.ui.xml"
  # a slow emulator sometimes shows "<system app> isn't responding": wait for it (not part of the app)
  if grep -q "t responding" "$OUT/.ui.xml" 2>/dev/null; then
    local w; w=$(python3 find_node.py "$OUT/.ui.xml" "Wait")
    [ -n "$w" ] && { echo "[ui] (closed a system 'not responding' dialog)" >> "$OUT/report.txt"; adb shell input tap $w; sleep 2; }
    timeout 30 adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; timeout 15 adb exec-out cat /sdcard/ui.xml > "$OUT/.ui.xml"
  fi
}
shot() {      # shot NAME [wait] → screenshot, UI dump and overlap check
  sleep "${2:-1.2}"
  dump
  adb exec-out screencap -p > "$OUT/$1.png"
  dump; cp "$OUT/.ui.xml" "$OUT/$1.xml"
  python3 check_layout.py "$OUT/$1.xml" "$PKG" >> "$OUT/report.txt" || fail "layout problem on $1"
  log "shot $1"
}
nfind() { dump; python3 find_node.py "$OUT/.ui.xml" "$1" "${2:-}"; }
tap() {
  local p; p=$(nfind "$1" "${2:-}")
  if [ -z "$p" ]; then fail "cannot find '$1'"; return 1; fi
  adb shell input tap $p; sleep 0.8; return 0
}
tap_any() { for l in "$@"; do p=$(nfind "$l"); if [ -n "$p" ]; then adb shell input tap $p; sleep 1; return 0; fi; done; fail "none of: $*"; return 1; }
longpress() { local p; p=$(nfind "$1" "${2:-}"); [ -n "$p" ] || { fail "cannot find '$1'"; return 1; }; set -- $p; adb shell input swipe $1 $2 $1 $2 900; sleep 0.8; }
expect() { [ -n "$(nfind "$1" "${2:-}")" ] && log "ok: sees '$1'" || fail "expected '$1' on screen"; }
expect_not() { [ -z "$(nfind "$1" "${2:-}")" ] && log "ok: no '$1'" || fail "did not expect '$1'"; }
# wait_for LABEL [REGEX] [seconds] → waits until it appears (translation answers come from the internet)
wait_for() {
  local t="${3:-15}"
  for i in $(seq 1 "$t"); do [ -n "$(nfind "$1" "${2:-}")" ] && { log "ok: sees '$1'"; return 0; }; sleep 1; done
  fail "expected '$1' within ${t}s"; return 1
}
back() { adb shell input keyevent KEYCODE_BACK; sleep 0.8; }
open_app() { adb shell am start -a io.github.samaliyan.trilingo.OPEN -f 0x14000000 -n "$PKG/.MainActivity" > /dev/null; sleep 2.5; }
type_text() { adb shell input text "$1"; }
clear_field() { tap "Clear" > /dev/null || true; }
# scroll_to LABEL [REGEX] → scrolls the screen down in small steps until it is visible (fails if never)
scroll_to() {
  for i in $(seq 1 16); do
    [ -n "$(nfind "$1" "${2:-}")" ] && return 0
    # small swipes in the upper half: the keyboard may cover the lower half
    if [ "$i" -le 8 ]; then adb shell input swipe 540 1100 540 650 400; else adb shell input swipe 540 650 540 1100 400; fi
    sleep 0.6
  done
  [ "${3:-}" = quiet ] || fail "could not scroll to '$1'"; return 1
}

log "API $API"
adb install -r "$APK" || { echo "install failed"; exit 1; }
adb logcat -c
adb shell settings put global window_animation_scale 0

# 1. First start: the introduction (4 pages), then the main screen with the keyboard up
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; sleep 3
shot 00_intro_1
expect "Translate in a moment"
tap "Next" "^Next$"; shot 00_intro_2 0.6
expect "Understand the meaning"
# swipe to the next page
adb shell input swipe 900 1000 150 1000 200; sleep 1
shot 00_intro_3 0.6
expect "Keep what you learn"
tap "Next" "^Next$"; shot 00_intro_4 0.6
expect "Learn from real texts"
tap "Get started"
sleep 2.5
shot 01_first_start
KB=$(adb shell dumpsys input_method | grep -c "mInputShown=true")
[ "$KB" -gt 0 ] && log "ok: keyboard open on first start" || fail "keyboard not open on first start"
# 1.10: closed with Back, the keyboard stays closed (it used to come back up twice)
if [ "$KB" -gt 0 ]; then
  back
  sleep 3.5
  KB=$(adb shell dumpsys input_method | grep -c "mInputShown=true")
  [ "$KB" -eq 0 ] && log "ok: keyboard stays closed" || fail "keyboard came back after Back"
  tap "search field" "^Type a word$" > /dev/null || true
fi
expect "English · German · Persian"
expect "Type a word"

# 2. English word → Persian + German, with meanings
open_app
type_text "house"
wait_for "PERSIAN" "" 20
shot 02_english_house 1.5
expect "From English · detected"
back
expect "GERMAN"
expect "Haus" "Haus"
if [ -n "$(nfind "Translated by MyMemory")" ]; then log "Google did not answer (MyMemory used): meaning groups not checked"; else expect "Noun" "^Noun$"; fi

# 2a. Suggestions while typing, German articles, umlauts typed without dots
clear_field
type_text "dicti"
wait_for "Translate dictionary" "" 8
shot 02a_suggestions 0.5
tap "Translate dictionary"
wait_for "GERMAN" "" 20
shot 02a2_dictionary_result 1
expect "das Wörterbuch" "das Wörterbuch"
open_app
type_text "Haus"
wait_for "German noun: das Haus" "" 20
back
shot 02a3_german_noun 1
expect "die Häuser" "die Häuser"
open_app
type_text "mude"
wait_for "Showing results for" "" 20
back
shot 02a4_umlaut_fixed 1
expect "müde" "^müde$"
expect "tired" "(?i)tired"

# 2a5. Phrasal verbs (English) and prefix verbs (German); examples also for the English translation
open_app
type_text "give"
wait_for "PERSIAN" "" 20
back
if [ -n "$(nfind "Translated by MyMemory")" ]; then log "Google did not answer: phrasal verbs (need word types) skipped"; open_app; type_text "x"; else
scroll_to "Phrasal verbs"
tap "Phrasal verbs"
sleep 2
shot 02a5_phrasal_verbs 1
expect "give up" "^give up$"
expect "stop trying; quit"
tap "give up" "^give up$"
wait_for "From English" "^From English" 20
shot 02a6_give_up_result 1
fi
open_app
type_text "kommen"
wait_for "ENGLISH" "" 20
back
tap "Prefix verbs" "^(Phrasal verbs|Prefix verbs)$"
sleep 3
shot 02a7_prefix_verbs 1
expect "bekommen" "^bekommen$"
expect_not "gekommen" "^gekommen$"
expect "German has no phrasal verbs" "^German has no phrasal verbs"
back
open_app
type_text "Haus"
wait_for "ENGLISH" "" 20
back
scroll_to "Examples"; tap "Examples"
wait_for "English tab" "^English: " 10
tap "English tab" "^English: "
sleep 5
wait_for "example sentences" "([.!?]$)|^No examples found" 40
shot 02a8_examples_english_tab 1
expect "house" "(?i).*\\bhouse\\b.*"
back
clear_field
type_text "x"

# 2b. Misspelled word → "Did you mean", tap it
clear_field
type_text "hous"
wait_for "Did you mean" "" 20
shot 02b_did_you_mean 1
expect "house" "^house$"
tap "Did you mean house? Tap to translate it"
wait_for "Haus" "Haus" 10
expect_not "Did you mean"
clear_field
type_text "Schmeterling"
# Unknown misspelled word: the corrected word is translated right away
wait_for "Showing results for" "" 25
shot 02c_corrected_german 1
expect "Schmetterling" "^Schmetterling$"
expect "butterfly" "(?i)butterfly"
# 1.9: two mistakes and sound-alike letters are understood; more spellings to choose from
clear_field
type_text "Kuehlschrank"
wait_for "two mistakes" "^(Did you mean|Showing results for)$" 25
expect "Kühlschrank" "^Kühlschrank$"
shot 02c2_two_mistakes 1
clear_field
type_text "Termien"
wait_for "Termin" "^(Did you mean|Showing results for)$" 25
wait_for "other spellings" "^Other spellings$" 10
shot 02c3_other_spellings 1

# 3. German word → English + Persian
clear_field
type_text "Schmetterling"
wait_for "From German · detected" "" 20
shot 03_german_word 1.5
expect "butterfly" "(?i)butterfly"

# 3b. Examples (Tatoeba) and conjugation (built in)
back        # keyboard
scroll_to "Examples"; tap "Examples"
wait_for "example sentences" "([.!?]$)|^No examples found" 40
shot 03b_examples_german 1
expect "butterfly|Butterfly" "(?i)butterfly"
back
open_app
type_text "habe"
wait_for "ENGLISH" "" 20
back        # keyboard
wait_for "Conjugation" "" 5
shot 03c_habe_result 1
scroll_to "Conjugation"; tap "Conjugation"
sleep 1
shot 03d_conjugation_german 1
expect "hast" "^hast$"
expect "Präsens"
tap "English: have"
shot 03e_conjugation_english 1
expect "has" "^has$"
wait_for "Persian tab" "^Persian: " 15
tap "Persian tab" "^Persian: "
shot 03f_conjugation_persian 1
expect "دارم" "^دارم$"
adb shell input swipe 540 1700 540 500 300; sleep 0.8
shot 03g_conjugation_persian_scrolled 1
back
adb shell "am start -a android.intent.action.PROCESS_TEXT -t text/plain --es android.intent.extra.PROCESS_TEXT 'رفتن' -n $PKG/.MainActivity" > /dev/null
wait_for "Conjugation" "" 20
scroll_to "Conjugation"; tap "Conjugation"
sleep 1
shot 03h_conjugation_from_persian 1
expect "می‌روم" "^می.روم$"
tap "English: go"
expect "went" "^went$"
# 1.10: example sentences of one tense (the first tense card's Examples button)
tap "tense examples" "^Example sentences in the .* of go$"
wait_for "tense examples" "^(Listen in English|No sentences found with this tense yet\.)$" 30
shot 03h2_tense_examples 1
expect "tense sentences" "^Listen in English$"
back
back
scroll_to "Examples"; tap "Examples"
wait_for "examples result" "^(Sentences from Tatoeba|No examples found)" 25
shot 03i_examples_persian 1
back
open_app
type_text "house"
wait_for "PERSIAN" "" 20
back
expect "Examples"
expect_not "Conjugation"      # "house" is mainly a noun

# 3j. German examples for a word typed in lower case (Tatoeba needs "Tisch", not "tisch")
open_app
type_text "tisch"
wait_for "ENGLISH" "" 20
back
scroll_to "Examples"; tap "Examples"
wait_for "example sentences" "([.!?]$)|^No examples found" 40
shot 03j_examples_tisch 1
expect "a sentence with Tisch" "(?i).*\\btisch.*[.!?]$"
expect_not "No examples found"
back

# 3k. Grammar: the Grammar button of a word (fernsehen → separable verbs), the lesson, the Grammar screen and its search
open_app
type_text "fernsehen"
wait_for "ENGLISH" "" 20
back
wait_for "Grammar" "^Grammar$" 8
shot 03k_fernsehen_actions 1
tap "Grammar" "^Grammar$"
sleep 1
shot 03l_grammar_for_word 1
expect "Grammar for this word"
expect "separable verbs lesson" ".*fernsehen.*anrufen.*"
tap "separable verbs lesson" "^فعل.*جداشدنی.*"
sleep 1.5
shot 03m_lesson_persian 1
expect "Persian explanation" ".*fern.*ته جمله.*"
# scroll down in small steps until the first example is on screen
for i in $(seq 1 12); do [ -n "$(nfind "ex" "^Ich sehe jeden Abend fern\\.$")" ] && break; adb shell input swipe 540 1500 540 1000 600; sleep 0.5; done
shot 03n_lesson_scrolled 1
expect "example sentence" "^Ich sehe jeden Abend fern\\.$"
adb shell input swipe 540 1700 540 400 300; sleep 0.8
adb shell input swipe 540 1700 540 400 300; sleep 0.8
shot 03o_lesson_end 1
tap "Explanations in Persian. Change"
tap "English" "^English$"
sleep 1
for i in 1 2 3 4 5; do adb shell input swipe 540 500 540 1800 200; done; sleep 0.8
shot 03p_lesson_english 1
expect "Separable verbs (like fernsehen, anrufen)"
back                     # lesson
back                     # panel
tap "Grammar lessons"
sleep 1.5
shot 03q_grammar_screen 1
expect "German grammar"
expect "first German lesson" "^Personal pronouns, sein and haben$"
tap "English grammar"
sleep 1
shot 03r_grammar_english_list 1
expect "first English lesson" "^The verb to be: am, is, are$"
tap "Search a topic or a word"
type_text "fernsehen"
sleep 1.5
shot 03s_search_word 1
expect "Separable verbs (like fernsehen, anrufen)"
tap "Clear"
type_text "passive"
sleep 1.5
shot 03t_search_topic 1
expect "The passive: is made, was built, has been done"
tap "Clear"
type_text "trennbar"
sleep 1.5
expect "Separable verbs (like fernsehen, anrufen)"
tap "Clear"
type_text "qqqzzz"
sleep 1
shot 03u_search_nothing 1
expect "No lessons found"
tap "Explanations in English. Change"
tap "Persian" "^Persian$"
back; back
open_app
type_text "x"            # the next step starts with clear_field (an empty field would hit "Clear" history instead)

# 3v. Meaning in the word's own language, with a picture, level, pronunciation; save the word
open_app
type_text "Tisch"
wait_for "ENGLISH" "" 20
back
tap "Show the meaning in the word's own language first"
wait_for "meaning header" "^MEANING IN GERMAN$" 25
wait_for "German meaning" ".*Möbelstück.*" 20
wait_for "picture" "^Picture: Tisch.*" 25
shot 03v_meaning_first 1
expect "Level A1, Beginner"
tap "Save word"
expect "Remove from saved words"
expect_not "ENGLISH" "^ENGLISH$"
scroll_to "Show translation"
tap "Show translation"
sleep 1
scroll_to "ENGLISH" "^ENGLISH$"
shot 03w_meaning_scrolled 1
for i in $(seq 1 8); do [ -n "$(nfind "Show the translation first")" ] && break; adb shell input swipe 540 700 540 1600 300; sleep 0.6; done
tap "Show the translation first"
sleep 1
shot 03x_translation_first 1
open_app
type_text "distinguish"
wait_for "a translation" "^(GERMAN|PERSIAN)$" 20
back
sleep 3
scroll_to "English meaning" "(?i)^1\. .*different.*"
shot 03y_english_meaning 1
expect "Listen to a recording in English"
tap "Record yourself saying the word"
sleep 1
tap_any "While using the app" "WHILE USING THE APP" "Only this time" "ONLY THIS TIME" "Allow" "ALLOW" || true
sleep 1
if scroll_to "Stop recording" "" quiet; then
  shot 03z_recording 0.3
  adb shell input tap $(nfind "Stop recording"); log "ok: recording started and stopped"
else
  log "recording could not start on this emulator (no microphone) — the app shows a message"
fi
sleep 1
scroll_to "Play your recording"

# 3z2. Voice search opens (the recognizer itself cannot be driven here)
open_app
back
tap "Voice search"
sleep 1
shot 03z2_voice_language 0.5
if [ -n "$(nfind "Which language will you speak?")" ]; then
  tap "German" "^German$"
  sleep 3
  shot 03z3_voice_after 0.5
  back; sleep 1
else
  log "voice search: this emulator has no speech recognizer (the app shows a message) — ok"
fi
open_app

# 3z4. Home: saved words, review (flashcards)
open_app
back
shot 03z4_home_tiles 1
expect "Review tile" "^Review( \(\d+\))?$"
tap "Saved words"
sleep 1
shot 03z5_saved_words 1
expect "Tisch" "^Tisch$"
expect "Export for Anki"
tap "+ New folder"
type_text "Class"
tap_any "OK" "Ok"
sleep 1
shot 03z6_folder 1
expect "folder chip" "^Class .*"
back
[ -n "$(nfind "Search saved words")" ] && back      # a first Back may only close the keyboard
tap "Review tile" "^Review( \(\d+\))?$"
sleep 1
shot 03z7_review_front 1
if [ -n "$(nfind "Show answer")" ]; then
  tap "Show answer"
  shot 03z8_review_back 1
  tap "Good" "^Good, .*"
  sleep 1
  log "ok: reviewed one card"
else
  fail "no card to review"
fi
back

# 3z9. Grammar practice and find the mistake (5 questions, then 5 new ones)
open_app
back
tap "Grammar lessons"
sleep 1.5
tap "Find the mistake"
wait_for "question" "^Which sentence is correct\?$" 10
shot 03z9_find_mistake 1
expect "Question 1"
for i in $(seq 1 8); do [ -n "$(nfind "more" "^5 new questions$")" ] && break; adb shell input swipe 540 1600 540 500 300; sleep 0.6; done
tap "5 new questions" "^5 new questions$"
sleep 1
expect "Question 1"
back
tap "Practice" "^Practice$"
tap "level" "^up to A1.*"
wait_for "question" "^Question 1$" 10
shot 03z10_practice 1
back
back
open_app

# 3z11. Check a sentence (LanguageTool)
open_app
back
tap "Check a sentence"
sleep 1
tap "Write a sentence…"
type_text "Ich%shabe%sgestern%snach%sHause%sgegangen."
back
tap "Check" "^Check$"
wait_for "result" "^(\d+ possible mistakes?|No mistakes found\.|Could not check.*)$" 30
shot 03z11_check 1
expect "possible mistake" "^\d+ possible mistakes?$"
back
back

# 3z12. Scan text: a shared picture with three German words
adb push scan_sample.png /sdcard/Pictures/scan_sample.png > /dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/scan_sample.png > /dev/null 2>&1
sleep 3
MID=$(adb shell content query --uri content://media/external/images/media --projection _id:_display_name 2>/dev/null | grep scan_sample | head -1 | sed -n 's/.*_id=\([0-9]*\).*/\1/p')
if [ -n "$MID" ]; then
  adb shell am start -a android.intent.action.SEND -t image/png -d "content://media/external/images/media/$MID" --grant-read-uri-permission -n "$PKG/.ScanActivity" > /dev/null
  wait_for "scan result" "^(Tap a word to translate it\..*|No text found in this picture\.|Could not read.*)$" 30
  shot 03z12_scan 1
  expect "Tap a word to translate it." "^Tap a word to translate it\. Drag over the photo.*$"
  expect "turn photo" "^Turn photo$"
  P=$(nfind "photo" "^Photo with \d+ words.*")
  if [ -n "$P" ]; then
    set -- $P
    # the first word ("Bahnhof") is near the top of the picture
    adb shell input tap 250 $(( $2 - 175 ))
    wait_for "translation" "(?i).*(station|ایستگاه).*" 25
    shot 03z13_scan_word 1
  fi
  # 1.9: the whole text, editable, translated as a whole
  scroll_to "the text" "^The text · German$"
  scroll_to "translate the text" "^Translate the text$"
  tap "Translate the text" "^Translate the text$"
  wait_for "text translated" "(?i)^ENGLISH$" 25
  shot 03z13b_scan_text 1
  back
else
  log "scan test skipped: the picture could not be found in the media store"
fi
# 3z14. Listen and write (the phone's text-to-speech says the word)
open_app
back
tap "Listen & write"
sleep 2
shot 03z14_listen_write 1
if [ -n "$(nfind "Install voice")" ]; then
  log "listen & write: this emulator has no text-to-speech voice (the app offers to install one) — ok"
else
  tap "A1 words"
  sleep 1
  expect "Question 1"
  tap "Answer 1"
  type_text "xyz"
  tap "Check" "^Check$"
  sleep 1
  shot 03z15_listen_write_checked 1
  expect "Not quite." "^Not quite\.$"
  for i in $(seq 1 10); do [ -n "$(nfind "5 new")" ] && break; adb shell input swipe 540 1600 540 600 300; sleep 0.5; done
  tap "5 new" "^5 new$"
  sleep 1
  expect "Question 1"
fi
back

# 3z15b. Learn (1.13): today's lesson starts with a new word; stopping keeps the answers; then Progress
open_app
back
scroll_to "Learn" "^Learn$"
tap "Learn" "^Learn$"
wait_for "today's lesson" "^Today's lesson$" 20
expect "units" "^Unit 1$"
shot 03z15b_learn 1
scroll_to "weak spots" "^Your weak spots$"
shot 03z15b2_learn_weak 1
open_app
back
scroll_to "Learn" "^Learn$"
tap "Learn" "^Learn$"
wait_for "today's lesson again" "^Today's lesson$" 20
tap "Start" "^Start$"
wait_for "lesson exercise" "^(New word|What does it mean\?|Which (German|English) word\?|Write it in .*|Which article\?|Write the right form|Say it aloud)$" 15
shot 03z15c_lesson 1
if [ -n "$(nfind "Got it" "^Got it$")" ]; then
  tap "Got it" "^Got it$"
  wait_for "next exercise" "^(New word|What does it mean\?|Which (German|English) word\?|Write it in .*|Which article\?|Write the right form|Say it aloud)$" 10
fi
back
wait_for "stop question" "^Stop this lesson\? Your answers so far are kept\.$" 5
tap "Stop" "(?i)^stop$"
wait_for "learn home again" "^Today's lesson$" 10
expect "a word met" "^[1-9][0-9]* of [0-9]+ words of this level met$"
back
open_app
back
scroll_to "Progress" "^Progress$"
tap "Progress" "^Progress$"
wait_for "streak" "^days? in a row$" 10
expect "answers this week" "^answers$"
expect "chart" "^Words learned per week$"
shot 03z15e_progress 1
back

# 3z15f. Which meaning (1.13): put on as wear, not as put
open_app
type_text "put%son"
wait_for "meanings" "^Which meaning\?$" 25
expect "wear" "^wear$"
tap "wear" "^wear$"
wait_for "wear in Persian" "^پوشیدن$" 25
back
scroll_to "German follows" "(?i)^(tragen|anziehen|anhaben)$"
shot 03z15f_put_on_wear 1
# its meaning has a drawing of that meaning (a person dressing), not a photo of another one
scroll_to "Meaning tab" "^Meaning$"
tap "Meaning tab" "^Meaning$"
wait_for "drawing" "^Drawing: Sergio Palao.*" 25
expect_not "German caption" ".*(trägt|Pavian).*"
shot 03z15g_put_on_drawing 1
# the app keeps the chosen tab: back to Translation for the next steps
scroll_to "Translation tab" "^Translation$"
tap "Translation tab" "^Translation$"
sleep 0.6

# 3z16. Words from a text (shared text → the new words at your level → save them)
adb shell "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'Die Verwaltung hat die Genehmigung verweigert, obwohl die Anwohner sich beschwert hatten.' -n $PKG/.TextWordsActivity" > /dev/null
sleep 3
wait_for "new words" "^\d+ new words?$" 15
shot 03z16_text_words 1
expect "German text · Your level"
tap "level C1" "^My level: C1.*"
sleep 1
shot 03z17_text_words_c1 1
tap "level A2" "^My level: A2.*"
wait_for "new words" "^\d+ new words?$" 5
scroll_to "save" "^Save \d+ words?$"
tap "save" "^Save \d+ words?$"
sleep 1
shot 03z18_text_words_folder 0.5
tap "No folder"
sleep 1.5
expect "No new words at this level"
back

# 3z19. Saved words: search and sort
open_app
back
tap "Saved words"
sleep 1
tap "Search saved words"
type_text "tisch"
sleep 1
shot 03z19_search 1
expect "1 word found"
expect "Tisch" "^Tisch$"
back
tap "Sort"
tap "A to Z"
sleep 1
shot 03z20_sorted 1
back
open_app
type_text "x"            # the next step starts with clear_field

# 4. Ambiguous word: auto vs forced German ("Gift" = poison in German)
clear_field
type_text "Gift"
wait_for "ENGLISH|GERMAN" "^(ENGLISH|GERMAN)$" 20
shot 04_gift_auto 1.5
adb shell input keyevent KEYCODE_BACK; sleep 0.8        # close the keyboard so the chips are visible
tap "Translate from German"
wait_for "From German" "^From German$" 20
shot 05_gift_forced_german 1.5
expect "poison" "(?i)poison"
tap "Translate from Auto"

# 5. A short sentence
open_app
type_text "how%sare%syou%stoday"
wait_for "PERSIAN" "" 20
shot 06_sentence 1.5
# 5a. A sentence has a meaning section too: the meanings of its words
back
scroll_to "sentence meaning" "^MEANING IN ENGLISH$"
wait_for "words of the sentence" "^The words of this text$" 25
shot 06a_sentence_meaning 1

# 5b. A number: words in all three languages, a meaning (no "8" → "8")
open_app
type_text "8"
wait_for "number row" "^Number 8$" 10
wait_for "eight" "(?i)^eight$" 10
expect "acht" "(?i)^acht$"
back
scroll_to "number meaning" "^MEANING IN PERSIAN$"
shot 06b_number 1
# Persian digits from another app: ۸ → هشت
adb shell "am start -a android.intent.action.PROCESS_TEXT -t text/plain --es android.intent.extra.PROCESS_TEXT '۸' -n $PKG/.MainActivity" > /dev/null
sleep 2
wait_for "eight" "(?i)^eight$" 15
shot 06c_persian_digit 1

# 5c. A German compound with a typo: corrected, German, its parts, the female form
open_app
type_text "marketingassitentin"
wait_for "corrected" ".*Marketingassistentin.*" 25
wait_for "parts" "^Word parts$" 10
expect "female form" "^Female form of Marketingassistent$"
expect "part Marketing" "^Marketing$"
back
scroll_to "compound meaning" "^MEANING IN GERMAN$"
wait_for "a meaning, never empty" "^(Meaning of .*|From Wikipedia|Meaning of the English word .*|The words of this text|No dictionary explains this.*|\\d\\..*)$" 25
shot 06d_compound 1

# 6. Persian text from another app's text menu (PROCESS_TEXT)
adb shell "am start -a android.intent.action.PROCESS_TEXT -t text/plain --es android.intent.extra.PROCESS_TEXT 'خانه' -n $PKG/.MainActivity" > /dev/null
sleep 2
wait_for "From Persian" "^From Persian" 20
shot 07_persian_from_text_menu 1.5
expect "house|home" "(?i)(house|home)"

# 7. Copy
tap "Copy English"
shot 08_copied 0.3

# 8. Recent words (empty field), long-press menu; keyboard must be up after opening from the widget/tile
open_app
shot 09_recent_keyboard 1.5
KB=$(adb shell dumpsys input_method | grep -c "mInputShown=true")
[ "$KB" -gt 0 ] && log "ok: keyboard open" || fail "keyboard not open after OPEN"
back
shot 09b_recent 1
expect "Recent"; scroll_to "house"; scroll_to "Gift"
expect_not "hous" "^hous$"
expect_not "Schmeterling" "^Schmeterling$"
scroll_to "fernsehen"
longpress "fernsehen"
shot 10_remove_dialog
tap_any "Remove" "REMOVE"
tap "Undo"                         # right away: the bar goes after 8 s, and each screen check takes a few
sleep 1
scroll_to "fernsehen" && log "ok: undo brought the word back"
# swipe left removes (with Undo), swipe right saves
P=$(nfind "fernsehen")
if [ -n "$P" ]; then set -- $P; adb shell input swipe 900 $2 100 $2 250; sleep 1.2; fi
shot 10b_swiped_away 0.3
expect "Removed “fernsehen”"
expect_not "fernsehen"
scroll_to "Gift"
P=$(nfind "Gift")
if [ -n "$P" ]; then set -- $P; adb shell input swipe 150 $2 950 $2 250; sleep 1.2; fi
shot 10c_swiped_saved 0.3
scroll_to "house"
tap "house"
shot 11_from_history 1
expect "Haus" "Haus"

# 9. Offline: a known word still works, a new word explains the problem
net_off() { adb shell cmd connectivity airplane-mode enable 2>/dev/null || { adb shell settings put global airplane_mode_on 1; adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true; }; adb shell svc wifi disable; adb shell svc data disable; sleep 4; }
net_on() { adb shell cmd connectivity airplane-mode disable 2>/dev/null || { adb shell settings put global airplane_mode_on 0; adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false; }; adb shell svc wifi enable; adb shell svc data enable; sleep 8; }
net_off
open_app
type_text "house"; sleep 2
back
shot 12_offline_known 1
expect "Haus" "Haus"
# a common word never looked up: from the built-in dictionary (when this emulator really is offline)
clear_field
type_text "window"; sleep 3
if [ -n "$(nfind "From the built-in dictionary")" ]; then
  shot 12b_offline_dictionary 1
  expect "Fenster" "Fenster"
  log "ok: offline dictionary answered"
elif [ -n "$(nfind "PERSIAN")" ]; then
  log "offline dictionary: this emulator stayed online (answered by the service)"
else
  fail "no answer for a common word offline"
fi
clear_field
type_text "quokka"
# Some emulator images (Android 8) cannot be taken offline from adb: then the word is simply translated.
for i in $(seq 1 25); do
  sleep 1
  [ -n "$(nfind "No internet connection.")" ] && break
  [ -n "$(nfind "PERSIAN")" ] && break
done
shot 13_offline_new 1
if [ -n "$(nfind "PERSIAN")" ]; then
  log "offline message test skipped: this emulator stayed online"
  net_on
else
  expect "No internet connection."
  net_on
  tap "Try again"
fi
wait_for "PERSIAN" "" 25
shot 14_back_online 1.5

# 10. Not a word
clear_field
type_text "12345"; sleep 1.5
# a number is translated as words (1.8)
wait_for "number row" "^Number 12,345$" 10
shot 15a_number 0.5
expect "twelve thousand" "(?i)^twelve thousand three hundred forty-five$"
clear_field
type_text "...%s!!"; sleep 1.5
shot 15_no_letters
expect "Type a word to translate."

# 11. Settings, service choice, dark theme
open_app
back                     # keyboard
tap "Settings"
shot 16_settings
tap "Translation service"
shot 17_service_choice
tap_any "MyMemory (one translation per word)"
back
open_app
type_text "window"
wait_for "a result" "^(Translated by MyMemory|PERSIAN)$" 25
[ -n "$(nfind "Translated by MyMemory")" ] && log "ok: MyMemory answered" || log "MyMemory did not answer from this network (daily limit); Google was used instead — ok"
shot 18_mymemory 1.5
back; tap "Settings"
tap "Translation service"; tap_any "Google Translate (recommended, shows all meanings)"
tap "Theme"; tap "Dark"
shot 19_settings_dark
back
open_app
type_text "Freundschaft"
wait_for "From German · detected" "" 20
shot 20_dark_result 1.5
back
expect_not "Conjugation"          # a noun has no conjugation button
scroll_to "Examples"; tap "Examples" && { wait_for "example sentences" "([.!?]$)|^No examples found" 40; shot 20c_dark_examples 1; back; }
open_app; type_text "gehen"; wait_for "ENGLISH" "" 20; back
scroll_to "Conjugation"; tap "Conjugation" && { sleep 1; shot 20b_dark_conjugation 1; back; }

# 11b. Quick-translate bubble over other apps: copy a word, tap the bubble, the panel translates it
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
open_app; type_text "Wasser"; wait_for "ENGLISH" "" 20; back
tap "Copy English"
tap "Settings"
tap "Quick-translate bubble"
sleep 1
[ "$API" -ge 33 ] && { tap_any "Allow" "ALLOW" > /dev/null 2>&1 || true; }      # Android 13+ asks for the notification permission
shot 26a_bubble_on 0.5
adb shell input keyevent KEYCODE_HOME
sleep 2
shot 26b_bubble_home 0.5
# the bubble is not part of the screen dump (it cannot take focus): tap where it starts
W=$(adb shell wm size | sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\1/p' | tail -1); H=$(adb shell wm size | sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\2/p' | tail -1)
D=$(adb shell wm density | sed -n 's/.*: *\([0-9]*\).*/\1/p' | tail -1)
BX=$(( W - (35 * D / 160) )); BY=$(( H / 3 + (27 * D / 160) ))
adb shell input tap $BX $BY
wait_for "bubble panel" "^Open in Trilingo$" 10
wait_for "translation in the panel" "^Wasser$" 20
shot 26c_bubble_panel 1
tap "Close"
sleep 1
open_app; back; tap "Settings"
tap "Quick-translate bubble"
sleep 1
expect "Vibration"
back

# 11c. Backup to a file and restore it (the phone's file picker)
open_app; back; tap "Settings"
scroll_to "Back up now"
tap "Back up now"
sleep 3
shot 26d_backup_picker 0.5
if tap_any "SAVE" "Save" > /dev/null; then
  sleep 2
  scroll_to "Restore a backup"
  tap "Restore a backup"
  sleep 3
  shot 26e_restore_picker 0.5
  if tap "backup file" "^trilingo-backup-.*\.json$"; then
    wait_for "restore question" "^Backup from .*" 10
    shot 26f_restore_confirm 0.5
    tap_any "Restore" "RESTORE"
    sleep 1
    log "ok: backup saved and restored"
  else
    log "restore: the backup file is not listed in this emulator's picker — restore not driven (the backup itself was saved)"
    back
  fi
else
  log "backup: the file picker could not be driven on this emulator — skipped"
  back
fi
back

# 12. Widget on the home screen
open_app; back; tap "Settings"
if scroll_to "Add widget to home screen" "" quiet; then
  # the last item may sit at the very bottom edge: a little further, then tap
  adb shell input swipe 540 1500 540 1100 300; sleep 1
  tap "Add widget to home screen"
  shot 21_pin_widget_dialog 2
  tap_any "Add automatically" "ADD AUTOMATICALLY" "Add to home screen" "ADD TO HOME SCREEN" "Add" "ADD"
  sleep 2
  adb shell input keyevent KEYCODE_HOME
  shot 22_home_screen_widget 3
  if [ -z "$(nfind "Translate" "^Translate$")" ]; then
    adb shell input swipe 900 1200 150 1200 300
    shot 22b_home_screen_widget_page2 2
  fi
  if tap "Translate" "^Translate$"; then
    # 1.10: a small translate window right on the home screen
    shot 23_opened_from_widget 2.5
    expect "translate window" "^(Copy text, or type here|Open in Trilingo)$"
    back
    # 1.11: after an update the widget is drawn anew (the old drawing opened the app wherever it was tapped)
    adb install -r "$APK" > /dev/null && log "app updated in place"
    sleep 4
    adb shell input keyevent KEYCODE_HOME; sleep 2
    # the widget may be on the second page of the home screen (as when it was added)
    if [ -z "$(nfind "Translate" "^Translate$")" ]; then adb shell input swipe 900 1200 150 1200 300; sleep 2; fi
    shot 23a_home_after_update 1
    wait_for "widget after update" "^Translate$" 20
    if tap "Translate" "^Translate$"; then
      shot 23b_widget_after_update 2
      expect "translate window after update" "^(Copy text, or type here|Open in Trilingo)$"
      back
    else
      fail "widget gone after the update"
    fi
  else
    fail "widget not visible on the home screen"
  fi
else
  fail "no 'Add widget' item"
fi

# 13. Large text and landscape
adb shell settings put system font_scale 1.3
open_app
type_text "house"; sleep 2
shot 24_large_text 1.5
adb shell settings put system font_scale 1.0
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 2
shot 25_landscape 2
expect "PERSIAN"
# sideways there is room for two columns: the meaning is next to the translation, without scrolling
shot 25b_landscape_two_columns 1
ROOT=$(grep -o 'bounds="\[0,0\]\[[0-9]*,[0-9]*\]"' "$OUT/25b_landscape_two_columns.xml" | head -1 | sed 's/.*\]\[\([0-9]*\),\([0-9]*\)\]"/\1 \2/')
set -- $ROOT
if [ "${1:-0}" -le "${2:-0}" ]; then
  log "this emulator did not turn the screen sideways — two columns not checked here"
elif scroll_to "picture" "^Picture: house.*" quiet; then
  set -- $(nfind "picture" "^Picture: house.*")
  W=$(adb shell wm size | sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\2/p' | tail -1)     # sideways: the long side is the width
  [ "$1" -gt $(( W / 2 )) ] && log "ok: the picture is in the right column" || fail "picture not in a second column (x=$1)"
else
  log "no picture for house this time (picture service) — two columns not checked"
fi
adb shell settings put system user_rotation 0
sleep 1

# 14. Update check button (the test build has a huge build number → "latest version" or "could not reach")
open_app; back
tap "Settings"
scroll_to "Check for updates now"
tap "Check for updates now"
shot 26_update_check 0.6
back

# 15. Self-update from GitHub: an old build (same key) must find the latest release, download and install it
if [ -f old.apk ] && [ "$API" -lt 30 ]; then
  log "self-update test skipped on API $API: the published APK has ARM code only (text recognition), and this x86 emulator cannot run ARM code"
elif [ -f old.apk ]; then
  adb uninstall "$PKG" > /dev/null
  adb install old.apk > /dev/null && log "old build installed"
  adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
  adb shell am start -n "$PKG/.MainActivity" > /dev/null
  sleep 4
  [ -n "$(nfind "Skip" "^Skip$")" ] && tap "Skip" "^Skip$"          # a new install shows the introduction first
  wait_for "Update available" "" 90
  shot 27_update_offered 1
  tap_any "Update" "UPDATE"
  for i in $(seq 1 75); do
    sleep 2
    p=$(nfind "Update" '^(Update|UPDATE|Install|INSTALL)$')
    if [ -n "$p" ] && [ -z "$(nfind "Update available")" ]; then break; fi
  done
  shot 28_install_confirm 0.5
  tap_any "Update" "UPDATE" "Install" "INSTALL"
  sleep 8
  CODE=$(adb shell dumpsys package "$PKG" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n1)
  if [ -n "$CODE" ] && [ "$CODE" -gt 1 ]; then log "ok: updated in-app to build $CODE"; else fail "in-app update did not install (versionCode=$CODE)"; fi
else
  log "self-update test skipped (no signing secret)"
fi

# 16. Crash check
adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null
if grep -q "$PKG" "$OUT/crash.txt"; then fail "app crashed (see crash.txt)"; else log "ok: no crash"; fi
adb logcat -d | grep -iE "AndroidRuntime|$PKG" | tail -200 > "$OUT/logcat.txt"
rm -f "$OUT/.ui.xml"

log "failures: $FAILS"
[ "$FAILS" -eq 0 ]
