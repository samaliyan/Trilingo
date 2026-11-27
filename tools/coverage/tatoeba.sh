#!/bin/sh
# Probe: do the tense searches find sentences on Tatoeba?
q() {
  enc=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$2")
  url="https://api.tatoeba.org/v1/sentences?lang=$1&q=$enc&trans%3Alang=$3&showtrans=matching&word_count=3-14&sort=relevance&limit=40&is_unapproved=no&is_orphan=no"
  body=$(curl -s -m 20 "$url")
  n=$(printf '%s' "$body" | python3 -c "import json,sys
try:
  d=json.load(sys.stdin); r=d.get('data',[]); print(len(r)); [print('   ',x['text']) for x in r[:3]]
except Exception as e: print('ERR',e)")
  echo "$1 | $2 => $n"
}
q deu "=ging | =gingst | =gingen | =gingt" eng%2Cpes
q deu "=gegangen (=bin | =bist | =ist | =sind | =seid)" eng%2Cpes
q deu "=gehen (=werde | =wirst | =wird | =werden | =werdet)" eng%2Cpes
q deu "=geh | =geht | (=gehen =Sie)" eng%2Cpes
q deu "(=ruf =an) | (=ruft =an) | (=rufen =Sie =an)" eng%2Cpes
q eng "=gone (=have | =has)" deu%2Cpes
q eng "=will =go" deu%2Cpes
q pes "=رفتم | =رفتی | =رفت | =رفتیم | =رفتید | =رفتند" eng%2Cdeu
q pes "=رفت (=خواهم | =خواهی | =خواهد | =خواهیم | =خواهید | =خواهند)" eng%2Cdeu
q pes "=می‌روم | =می‌روی | =می‌رود | =می‌رویم | =می‌روید | =می‌روند" eng%2Cdeu
