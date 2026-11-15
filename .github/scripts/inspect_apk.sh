#!/usr/bin/env bash
# Read-only check: did Telegram keep the real names of the standard list library (androidx.recyclerview) and of its
# own RecyclerListView? If yes, an extension can watch the side menu through those names.
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

for f in dex/classes*.dex; do "$DEXDUMP" -f "$f" > "$f.f.txt" 2>/dev/null; done

section "androidx classes that exist (count by package, then the recyclerview ones)"
cat dex/*.f.txt | grep "Class descriptor" | grep "'Landroidx/" | sed -E "s/.*'L(androidx\/[a-z0-9]+\/[a-z0-9]*).*/\1/" | sort | uniq -c | sort -rn | head -n 25 >> "$OUT"
say "--- androidx/recyclerview classes:"
cat dex/*.f.txt | grep "Class descriptor" | grep "'Landroidx/recyclerview/" | sed -E "s/.*'(L[^']+;)'.*/\1/" | head -n 60 >> "$OUT"

# members of the interesting classes (names only)
show_members() {
  local desc="$1"
  section "MEMBERS OF $desc"
  local found=0
  for f in dex/classes*.dex.f.txt; do
    awk -v d="'$desc'" '/Class #/{p=0} index($0,"Class descriptor") && index($0,d){p=1} p{print}' "$f" \
      | grep -E "Class descriptor|Superclass|Interfaces|name +:|type +:" | paste -sd' ' | sed -E "s/ +name +: /\n  /g; s/ +type +: / :: /g; s/ +/ /g" | head -n 260 >> "$OUT"
    found=1
  done
  [ "$found" = 0 ] && say "(not found)"
}
show_members "Landroidx/recyclerview/widget/RecyclerView;"
show_members "Landroidx/recyclerview/widget/RecyclerView\$Adapter;"
show_members "Landroidx/recyclerview/widget/RecyclerView\$AdapterDataObserver;"
show_members "Lorg/telegram/ui/Components/RecyclerListView;"
show_members "Lorg/telegram/ui/Adapters/DrawerLayoutAdapter;"

section "classes whose names contain 'Drawer' or 'SideMenu'"
cat dex/*.f.txt | grep "Class descriptor" | grep -iE "Drawer|SideMenu" | sed -E "s/.*'(L[^']+;)'.*/\1/" | head -n 40 >> "$OUT"

section "END"
say "size: $(wc -c < "$OUT") bytes"
