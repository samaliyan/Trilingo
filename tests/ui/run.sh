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

dump() { timeout 30 adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; timeout 15 adb exec-out cat /sdcard/ui.xml > "$OUT/.ui.xml"; }
shot() {      # shot NAME [wait] → screenshot, UI dump and overlap check
  sleep "${2:-1.2}"
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

log "API $API"
adb install -r "$APK" || { echo "install failed"; exit 1; }
adb logcat -c
adb shell settings put global window_animation_scale 0

# 1. First start: introduction, keyboard up
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; sleep 3
shot 01_first_start
KB=$(adb shell dumpsys input_method | grep -c "mInputShown=true")
[ "$KB" -gt 0 ] && log "ok: keyboard open on first start" || fail "keyboard not open on first start"
expect "English · German · Persian"
expect "Type a word"

# 2. English word → Persian + German, with meanings
open_app
type_text "house"
wait_for "GERMAN" "" 20
shot 02_english_house 1.5
expect "PERSIAN"; expect "From English · detected"
back
expect "Haus" "Haus"
expect "Noun" "^Noun$"

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
wait_for "Phrasal verbs" "" 5
tap "Phrasal verbs"
sleep 2
shot 02a5_phrasal_verbs 1
expect "give up" "^give up$"
expect "stop trying; quit"
tap "give up" "^give up$"
wait_for "From English" "^From English" 20
shot 02a6_give_up_result 1
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
tap "Examples"
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

# 3. German word → English + Persian
clear_field
type_text "Schmetterling"
wait_for "From German · detected" "" 20
shot 03_german_word 1.5
expect "butterfly" "(?i)butterfly"

# 3b. Examples (Tatoeba) and conjugation (built in)
back        # keyboard
tap "Examples"
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
tap "Conjugation"
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
tap "Conjugation"
sleep 1
shot 03h_conjugation_from_persian 1
expect "می‌روم" "^می.روم$"
tap "English: go"
expect "went" "^went$"
back
tap "Examples"
wait_for "examples result" "^(Sentences from Tatoeba|No examples found)" 25
shot 03i_examples_persian 1
back
open_app
type_text "house"
wait_for "GERMAN" "" 20
back
expect "Examples"
expect_not "Conjugation"      # "house" is mainly a noun

# 3j. German examples for a word typed in lower case (Tatoeba needs "Tisch", not "tisch")
open_app
type_text "tisch"
wait_for "ENGLISH" "" 20
back
tap "Examples"
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
expect "Ich sehe jeden Abend fern."
expect "Persian explanation" ".*fern.*ته جمله.*"
adb shell input swipe 540 1700 540 400 300; sleep 0.8
adb shell input swipe 540 1700 540 400 300; sleep 0.8
shot 03n_lesson_scrolled 1
adb shell input swipe 540 1700 540 400 300; sleep 0.8
adb shell input swipe 540 1700 540 400 300; sleep 0.8
shot 03o_lesson_end 1
tap "Explanations in Persian. Change"
tap "English" "^English$"
sleep 1
shot 03p_lesson_english 1
expect "Separable verbs (like fernsehen, anrufen)"
back                     # lesson
back                     # panel
tap "Grammar lessons"
sleep 1.5
shot 03q_grammar_screen 1
expect "German" "^German$"
expect "first German lesson" "^Personal pronouns, sein and haben$"
tap "English" "^English$"
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
expect "Recent"; expect "house"; expect "Gift"
expect_not "hous" "^hous$"
expect_not "Schmeterling" "^Schmeterling$"
longpress "Schmetterling"
shot 10_remove_dialog
tap_any "Remove" "REMOVE"
expect_not "Schmetterling"
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
clear_field
type_text "zebra"
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
tap "Examples" && { wait_for "example sentences" "([.!?]$)|^No examples found" 40; shot 20c_dark_examples 1; back; }
open_app; type_text "gehen"; wait_for "ENGLISH" "" 20; back
tap "Conjugation" && { sleep 1; shot 20b_dark_conjugation 1; back; }

# 12. Widget on the home screen
open_app; back; tap "Settings"
if [ -n "$(nfind "Add widget to home screen")" ]; then
  tap "Add widget to home screen"
  shot 21_pin_widget_dialog 2
  tap_any "Add automatically" "ADD AUTOMATICALLY" "Add to home screen" "ADD TO HOME SCREEN" "Add" "ADD"
  sleep 2
  adb shell input keyevent KEYCODE_HOME
  shot 22_home_screen_widget 3
  if [ -z "$(nfind "Translate a word")" ]; then
    adb shell input swipe 900 1200 150 1200 300
    shot 22b_home_screen_widget_page2 2
  fi
  if tap "Translate a word"; then
    shot 23_opened_from_widget 2.5
    expect "Type a word"
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
adb shell settings put system user_rotation 0
sleep 1

# 14. Update check button (the test build has a huge build number → "latest version" or "could not reach")
open_app; back
tap "Settings"
tap "Check for updates now"
shot 26_update_check 0.6
back

# 15. Self-update from GitHub: an old build (same key) must find the latest release, download and install it
if [ -f old.apk ]; then
  adb uninstall "$PKG" > /dev/null
  adb install old.apk > /dev/null && log "old build installed"
  adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
  adb shell am start -n "$PKG/.MainActivity" > /dev/null
  wait_for "Update available" "" 90
  shot 27_update_offered 1
  tap_any "Update" "UPDATE"
  for i in $(seq 1 30); do
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
