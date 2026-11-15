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
  "$DEXDUMP" -f "$f" > "$f.f.txt" 2>/dev/null
  "$DEXDUMP" -d "$f" > "$f.d.txt" 2>/dev/null
done

section "SIDE-MENU ADAPTER (found through the fields of LaunchActivity)"
python3 "$(dirname "$0")/inspect_adapter.py" dex "$PWD/dex/adapter.txt"
head -c 150000 dex/adapter.txt >> "$OUT"

section "END"
say "size: $(wc -c < "$OUT") bytes"
