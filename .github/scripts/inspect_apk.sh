#!/usr/bin/env bash
# Read-only look inside an APK's dex files: finds classes by name and prints their members and code.
# Usage: inspect_apk.sh APK OUT_FILE "Pattern1,Pattern2,..."
set -u
APK="${1:?apk}"; OUT="${2:?out}"; PATTERNS="${3:-DrawerLayoutAdapter}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)"
DEXDUMP="$BT/dexdump"
CAP_LINES=700

rm -rf dex && mkdir -p dex
unzip -q -o "$APK" 'classes*.dex' -d dex
: > "$OUT"
say() { printf '%s\n' "$*" >> "$OUT"; }
section() { printf '\n\n########## %s ##########\n' "$1" >> "$OUT"; }

section "APK"
"$BT/aapt" dump badging "$APK" 2>/dev/null | grep -E "^package:|^sdkVersion|^targetSdkVersion|^native-code" >> "$OUT"
ls -l dex >> "$OUT"

# class summary of every dex ("-f")
for f in dex/classes*.dex; do "$DEXDUMP" -f "$f" > "$f.txt" 2>/dev/null; done

section "NAMING (are class names readable?)"
TOTAL=$(cat dex/*.txt | grep -c "Class descriptor")
TG=$(cat dex/*.txt | grep "Class descriptor" | grep -c "'Lorg/telegram/")
say "classes total: $TOTAL, under org/telegram: $TG"
say "sample of org/telegram/ui/Adapters classes:"
cat dex/*.txt | grep "Class descriptor" | grep "org/telegram/ui/Adapters/" | head -n 25 >> "$OUT"

section "ANY METHOD NAMED resetItems"
grep -n "name  *: 'resetItems'" dex/*.txt | head -n 20 >> "$OUT"

IFS=',' read -ra PS <<< "$PATTERNS"
for P in "${PS[@]}"; do
  P="$(echo "$P" | tr -d '[:space:]')"
  [ -z "$P" ] && continue
  section "CLASSES MATCHING $P"
  cat dex/*.txt | grep "Class descriptor" | grep "$P" | head -n 20 >> "$OUT"
  DESC="$(cat dex/*.txt | grep "Class descriptor" | grep "/$P;" | head -n 1 | sed -E "s/.*'(L[^']+;)'.*/\1/")"
  if [ -n "$DESC" ]; then
    section "MEMBERS OF $DESC"
    for f in dex/classes*.dex.txt; do
      awk -v d="'$DESC'" '/Class #/{p=0} index($0,"Class descriptor") && index($0,d){p=1} p{print}' "$f" | head -n 220 >> "$OUT"
    done
    section "CODE OF $DESC (first methods)"
    for f in dex/classes*.dex; do
      "$DEXDUMP" -d "$f" 2>/dev/null | awk -v d="(in $DESC)" 'index($0,d){p=1;n=0} p{print; n++} n>70{p=0}' | head -n "$CAP_LINES" >> "$OUT"
    done
  else
    say "(no class named exactly $P)"
  fi
done
section "END"
say "size: $(wc -c < "$OUT") bytes"
