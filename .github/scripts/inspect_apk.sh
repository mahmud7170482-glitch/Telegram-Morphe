#!/usr/bin/env bash
# Read-only look inside an APK: names, the side-menu code (found through its resource strings), UserConfig (found
# through the string "userconfing") and LaunchActivity. Writes one text file.
# Usage: inspect_apk.sh APK OUT_FILE [RESOURCE_NAMES_COMMA_SEPARATED]
set -u
APK="${1:?apk}"; OUT="${2:?out}"
RES_NAMES="${3:-NewGroup,NewChannel,NewSecretChat,Contacts,Calls,SavedMessages,Settings,InviteFriends,TelegramFeatures}"
BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n 1)"
DEXDUMP="$BT/dexdump"

rm -rf dex && mkdir -p dex
unzip -q -o "$APK" 'classes*.dex' -d dex
: > "$OUT"
say() { printf '%s\n' "$*" >> "$OUT"; }
section() { printf '\n\n########## %s ##########\n' "$1" >> "$OUT"; }

section "APK"
"$BT/aapt" dump badging "$APK" 2>/dev/null | grep -E "^package:|^sdkVersion|^targetSdkVersion" >> "$OUT"

# --- dump every dex once (summary and code)
for f in dex/classes*.dex; do
  "$DEXDUMP" -f "$f" > "$f.f.txt" 2>/dev/null
  "$DEXDUMP" -d "$f" > "$f.d.txt" 2>/dev/null
done

section "NAMING"
TOTAL=$(cat dex/*.f.txt | grep -c "Class descriptor")
say "classes total: $TOTAL"
say "top packages under org/telegram (count, package):"
cat dex/*.f.txt | grep "Class descriptor" | grep "'Lorg/telegram/" | sed -E "s/.*'L(org\/telegram\/[^;\$]*)\/[^\/;\$]*[;\$].*/\1/" | sort | uniq -c | sort -rn | head -n 25 >> "$OUT"
say "sample class names in org/telegram/ui (first 40):"
cat dex/*.f.txt | grep "Class descriptor" | grep "'Lorg/telegram/ui/[A-Za-z]*;" | head -n 40 >> "$OUT"
say "sample class names in org/telegram/messenger (first 40):"
cat dex/*.f.txt | grep "Class descriptor" | grep "'Lorg/telegram/messenger/[A-Za-z]*;" | head -n 40 >> "$OUT"
say "readable names that matter (should exist if names are kept):"
cat dex/*.f.txt | grep "Class descriptor" | grep -E "/(LaunchActivity|ApplicationLoader|UserConfig|SharedConfig|DialogsActivity|PasscodeView);" >> "$OUT"

# --- resource ids of strings used by the side menu
section "RESOURCE IDS"
PATTERN=""
for N in $(echo "$RES_NAMES" | tr ',' ' '); do
  HEX="$("$BT/aapt" dump resources "$APK" 2>/dev/null | grep -E "resource 0x[0-9a-f]+ [^ ]*:string/$N:" | head -n 1 | sed -E 's/.*resource 0x([0-9a-f]+) .*/\1/')"
  if [ -n "$HEX" ]; then
    DEC=$((16#$HEX))
    say "string/$N = 0x$HEX ($DEC)"
    PATTERN="${PATTERN:+$PATTERN|}#int $DEC|// #$HEX|0x$HEX"
  else
    say "string/$N = (not found)"
  fi
done

section "METHODS THAT USE THOSE STRING IDS (count, class->method proto)"
if [ -n "$PATTERN" ]; then
  for f in dex/classes*.d.txt; do
    awk -v pat="$PATTERN" '
      /\(in L[^)]*;\)/ { match($0, /\(in L[^)]*;\)/); cls = substr($0, RSTART+4, RLENGTH-5); meth=""; proto="" }
      /name +: / && meth=="" { split($0, a, "\x27"); meth = a[2] }
      /type +: \x27\(/ { split($0, a, "\x27"); proto = a[2] }
      $0 ~ pat { hit[cls "->" meth proto]++ }
      END { for (k in hit) print hit[k], k }' "$f"
  done | sort -rn | head -n 15 >> "$OUT"
else
  say "(no resource ids found)"
fi

# code of the best candidate
BEST="$(sed -n '/METHODS THAT USE THOSE STRING IDS/,/^########## /p' "$OUT" | grep -E '^[0-9]+ L' | head -n 1)"
if [ -n "$BEST" ]; then
  BCLS="$(echo "$BEST" | sed -E 's/^[0-9]+ (L[^;]+;)->.*/\1/')"
  BMETH="$(echo "$BEST" | sed -E 's/^[0-9]+ L[^;]+;->([^(]*)\(.*/\1/')"
  section "CODE OF THE BEST CANDIDATE: $BCLS->$BMETH"
  for f in dex/classes*.d.txt; do
    awk -v c="(in $BCLS)" -v m="$BMETH" '
      index($0, c) { inm=1; seen=0; buf=""; }
      inm && /name +: / { seen=1; split($0, a, "\x27"); cur=a[2] }
      inm && seen && cur==m { print }
      /\(in L[^)]*;\)/ && !index($0, c) { inm=0 }' "$f" | head -n 500 >> "$OUT"
  done
  section "MEMBERS OF $BCLS"
  for f in dex/classes*.f.txt; do
    awk -v d="'$BCLS'" '/Class #/{p=0} index($0,"Class descriptor") && index($0,d){p=1} p{print}' "$f" | head -n 200 >> "$OUT"
  done
fi

# --- UserConfig via its famous misspelled preferences name
section "CLASSES THAT USE THE STRING \"userconfing\" (count, class->method)"
for f in dex/classes*.d.txt; do
  awk '
    /\(in L[^)]*;\)/ { match($0, /\(in L[^)]*;\)/); cls = substr($0, RSTART+4, RLENGTH-5); meth=""; proto="" }
    /name +: / && meth=="" { split($0, a, "\x27"); meth = a[2] }
    /userconfing/ { hit[cls "->" meth]++ }
    END { for (k in hit) print hit[k], k }' "$f"
done | sort -rn | head -n 12 >> "$OUT"

# --- LaunchActivity members (kept by the manifest)
LA="$(cat dex/*.f.txt | grep "Class descriptor" | grep "/LaunchActivity;" | head -n 1 | sed -E "s/.*'(L[^']+;)'.*/\1/")"
if [ -n "$LA" ]; then
  section "MEMBERS OF $LA (fields and method names)"
  for f in dex/classes*.f.txt; do
    awk -v d="'$LA'" '/Class #/{p=0} index($0,"Class descriptor") && index($0,d){p=1} p{print}' "$f" | grep -E "Class descriptor|Superclass|name +:|type +:" | head -n 400 >> "$OUT"
  done
else
  section "LaunchActivity"
  say "(no class named LaunchActivity)"
fi

section "END"
say "size: $(wc -c < "$OUT") bytes"
