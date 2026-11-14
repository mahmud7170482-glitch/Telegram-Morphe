#!/usr/bin/env bash
# Read-only look inside an APK whose names are scrambled (Telegram 13.x).
# It finds the side-menu method by what the code DOES (clear a list, add Integer.valueOf, Collections.sort ...)
# and lists the string constants of the settings class found through the string "userconfing".
# Usage: inspect_apk.sh APK OUT_FILE
set -u
APK="${1:?apk}"; OUT="${2:?out}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)"
DEXDUMP="$BT/dexdump"

rm -rf dex && mkdir -p dex
unzip -q -o "$APK" 'classes*.dex' -d dex
: > "$OUT"
say() { printf '%s\n' "$*" >> "$OUT"; }
section() { printf '\n\n########## %s ##########\n' "$1" >> "$OUT"; }

section "APK"
"$BT/aapt" dump badging "$APK" 2>/dev/null | grep -E "^package:|^sdkVersion|^targetSdkVersion" | cut -c1-120 >> "$OUT"

for f in dex/classes*.dex; do
  "$DEXDUMP" -d "$f" > "$f.d.txt" 2>/dev/null
done

# awk helper: tracks class / method / proto, one record per method with flags for the calls it contains.
# Output: "<flags> <class>-><method><proto>"  (flags: S=Collections.sort C=ArrayList.clear V=Integer.valueOf A=ArrayList.add L=loop-ish compare)
FLAGS_AWK='
  /\(in L[^)]*;\)/ { flush(); match($0, /\(in L[^)]*;\)/); cls = substr($0, RSTART+4, RLENGTH-5); meth=""; proto=""; S=C=V=A=U=0 }
  /name +: / && meth=="" { split($0, a, "\x27"); meth = a[2] }
  /type +: \x27\(/ { split($0, a, "\x27"); proto = a[2] }
  /Ljava\/util\/Collections;\.sort:/ { S=1 }
  /Ljava\/util\/ArrayList;\.clear:/ { C=1 }
  /Ljava\/lang\/Integer;\.valueOf:\(I\)/ { V=1 }
  /Ljava\/util\/ArrayList;\.add:\(Ljava\/lang\/Object;\)Z/ { A=1 }
  /Lorg\/telegram\/[A-Za-z\/]*;\.[a-zA-Z0-9]+:\(I\)Lorg\/telegram\/[A-Za-z\/]*;/ { U=1 }
  function flush() { if (cls != "" && meth != "" && proto != "" && S && C && V && A) print (U ? "U" : "-") " " cls "->" meth proto }
  END { flush() }'

section "METHODS THAT CLEAR A LIST, ADD Integer.valueOf(..) TO IT AND SORT IT (U = also calls a static (I)->Telegram-class getter)"
for f in dex/classes*.d.txt; do awk "$FLAGS_AWK" "$f"; done | sort | uniq -c | sort -rn | head -n 40 >> "$OUT"

# code of the best candidates (U flag first, then the rest)
CAND="$(sed -n '/METHODS THAT CLEAR A LIST/,/^########## /p' "$OUT" | grep -E '^ *[0-9]+ [U-] L' | sed -E 's/^ *[0-9]+ [U-] //' | head -n 30)"
KEYS="$(sed -n '/METHODS THAT CLEAR A LIST/,/^########## /p' "$OUT" | grep -E '^ *[0-9]+ U L' | sed -E 's/^ *[0-9]+ U //' | head -n 6)"
[ -z "$KEYS" ] && KEYS="$(echo "$CAND" | head -n 6)"
printf '%s\n' "$KEYS" > dex/keys.txt

section "CODE OF THE BEST CANDIDATES"
for f in dex/classes*.d.txt; do
  awk '
    NR==FNR { want[$0]=1; next }
    /\(in L[^)]*;\)/ { flushp(); match($0, /\(in L[^)]*;\)/); cls = substr($0, RSTART+4, RLENGTH-5); meth=""; proto=""; buf=""; n=0 }
    /name +: / && meth=="" { split($0, a, "\x27"); meth = a[2] }
    /type +: \x27\(/ { split($0, a, "\x27"); proto = a[2] }
    { if (n < 220) { buf = buf $0 "\n"; n++ } }
    function flushp() { if (cls != "" && meth != "" && proto != "" && ((cls "->" meth proto) in want)) { print "=== " cls "->" meth proto; printf "%s", buf } }
    END { flushp() }' dex/keys.txt "$f"
done | head -n 900 >> "$OUT"

section "STRING CONSTANTS OF THE SETTINGS CLASS (the class that uses \"userconfing\")"
UC="$(for f in dex/classes*.d.txt; do awk '
  /\(in L[^)]*;\)/ { match($0, /\(in L[^)]*;\)/); cls = substr($0, RSTART+4, RLENGTH-5) }
  /"userconfing"/ { print cls }' "$f"; done | sort | uniq -c | sort -rn | head -n 1 | sed -E 's/^ *[0-9]+ //')"
say "settings class: ${UC:-not found}"
if [ -n "$UC" ]; then
  for f in dex/classes*.d.txt; do
    awk -v c="(in $UC)" '
      /\(in L[^)]*;\)/ { cur = index($0, c) > 0 }
      cur && /const-string/ { print }' "$f"
  done | sed -E 's/^ *[0-9a-f]+: *[0-9a-f ]*\| *//' | sort | uniq -c | sort -rn | head -n 80 >> "$OUT"
fi

section "END"
say "size: $(wc -c < "$OUT") bytes"
