#!/bin/sh
for w in "put on" "wear" "dress" "switch on" "turn on" "take off" "give up" "go" "carry" "drive" "eat" "run" "beautiful" "table" "apple" "house" "remove" "gain weight" "get up" "stand up" "open" "read" "write"; do
  q=$(printf %s "$w" | sed 's/ /%20/g')
  echo "### $w"
  curl -s -m 20 "https://api.arasaac.org/api/pictograms/en/search/$q" | python3 -c '
import sys,json
try: d=json.load(sys.stdin)
except Exception as e: print("ERR",e); sys.exit()
if isinstance(d,dict): print(d); sys.exit()
for p in d[:6]:
  ks=[(k.get("keyword"),k.get("type")) for k in p.get("keywords",[])][:4]
  print(p["_id"], ks, p.get("categories",[])[:3], "sex" if p.get("sex") else "", "violence" if p.get("violence") else "")
'
done
echo "### de gehen"; curl -s -m 20 "https://api.arasaac.org/api/pictograms/de/search/gehen" | head -c 300; echo
echo "### fa"; curl -s -m 20 "https://api.arasaac.org/api/pictograms/fa/search/%D9%BE%D9%88%D8%B4%DB%8C%D8%AF%D9%86" | head -c 300; echo
curl -sI -m 20 "https://static.arasaac.org/pictograms/2781/2781_500.png" | head -3
