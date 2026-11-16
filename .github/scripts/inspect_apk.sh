#!/usr/bin/env bash
# Read-only: finds where the built-in Telegram API credentials (a 32-character hex hash and a small API id)
# are set, through the hash string (strings are not scrambled even when names are).
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

for f in dex/classes*.dex; do "$DEXDUMP" -d "$f" > "$f.d.txt" 2>/dev/null; done

section "32-CHARACTER HEX STRINGS IN CODE (count, class->method : string)"
for f in dex/classes*.d.txt; do
  awk '
    /\(in L[^)]*;\)/ { match($0, /\(in L[^)]*;\)/); cls = substr($0, RSTART+4, RLENGTH-5); meth="" }
    /name +: / && meth=="" { split($0, a, "\x27"); meth = a[2] }
    match($0, /"[0-9a-f]{32}"/) { hit[cls "->" meth " : " substr($0, RSTART, RLENGTH)]++ }
    END { for (k in hit) print hit[k], k }' "$f"
done | sort -rn | head -n 40 >> "$OUT"

# code of the static initializers / methods that hold such a string, with the surrounding instructions
section "CODE AROUND THE HASH (first 4 hits)"
KEYS="$(sed -n '/32-CHARACTER HEX STRINGS/,/^########## /p' "$OUT" | grep -E '^[0-9]+ L' | sed -E 's/^[0-9]+ (L[^;]+;->[^ ]+) : .*/\1/' | sort -u | head -n 4)"
printf '%s\n' "$KEYS" > dex/keys.txt
for f in dex/classes*.d.txt; do
  awk '
    NR==FNR { want[$0]=1; next }
    /\(in L[^)]*;\)/ { flushp(); match($0, /\(in L[^)]*;\)/); cls = substr($0, RSTART+4, RLENGTH-5); meth=""; buf=""; n=0 }
    /name +: / && meth=="" { split($0, a, "\x27"); meth = a[2] }
    { if (n < 160) { buf = buf $0 "\n"; n++ } }
    function flushp() { if (cls != "" && meth != "" && ((cls "->" meth) in want)) { print "=== " cls "->" meth; printf "%s", buf } }
    END { flushp() }' dex/keys.txt "$f"
done | head -n 700 >> "$OUT"

section "END"
say "size: $(wc -c < "$OUT") bytes"
