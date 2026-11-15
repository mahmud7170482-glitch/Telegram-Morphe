#!/usr/bin/env python3
"""
Find Telegram's side-menu adapter (the thing that builds the list of accounts in the side menu) in an APK whose
class names are scrambled, through the one class whose name is NOT scrambled: LaunchActivity.

LaunchActivity keeps a field that holds the adapter. So:
  1. read the types of all LaunchActivity fields,
  2. keep the classes shaped like an adapter (has a Context, at least two ArrayLists, a boolean),
  3. print their methods and the code of the methods that clear a list.

Usage: inspect_adapter.py DEX_DIR OUT_FILE      (DEX_DIR holds classes*.dex.f.txt and classes*.dex.d.txt from dexdump)
"""
import glob
import re
import sys
from collections import Counter, defaultdict

DEX_DIR, OUT = sys.argv[1], sys.argv[2]
CLASS_RE = re.compile(r"Class descriptor\s*:\s*'(L[^']+;)'")
SUPER_RE = re.compile(r"Superclass\s*:\s*'(L[^']+;)'")
NAME_RE = re.compile(r"^\s*name\s*:\s*'([^']*)'")
TYPE_RE = re.compile(r"^\s*type\s*:\s*'([^']*)'")
IN_RE = re.compile(r"\(in (L[^)]*;)\)")

classes = {}  # descriptor -> {"super": str, "fields": [(name, type)], "methods": [(name, proto)]}

for path in sorted(glob.glob(f"{DEX_DIR}/classes*.dex.f.txt")):
    cur = None
    section = None
    pending_name = None
    with open(path, errors="replace") as fh:
        for line in fh:
            m = CLASS_RE.search(line)
            if m:
                cur = classes.setdefault(m.group(1), {"super": "", "fields": [], "methods": []})
                section = None
                continue
            if cur is None:
                continue
            m = SUPER_RE.search(line)
            if m and not cur["super"]:
                cur["super"] = m.group(1)
                continue
            s = line.strip()
            if s.startswith("Instance fields"):
                section = "ifields"; continue
            if s.startswith("Static fields"):
                section = "sfields"; continue
            if s.startswith("Direct methods") or s.startswith("Virtual methods"):
                section = "methods"; continue
            m = NAME_RE.match(line)
            if m:
                pending_name = m.group(1); continue
            m = TYPE_RE.match(line)
            if m and pending_name is not None:
                if section in ("ifields", "sfields"):
                    cur["fields"].append((pending_name, m.group(1), section == "sfields"))
                elif section == "methods":
                    cur["methods"].append((pending_name, m.group(1)))
                pending_name = None

out = open(OUT, "w")
def w(s=""):
    out.write(s + "\n")

LA = "Lorg/telegram/ui/LaunchActivity;"
if LA not in classes:
    w("LaunchActivity not found"); sys.exit(0)

la_types = Counter(t for (_, t, static) in classes[LA]["fields"] if not static and t.startswith("L"))
w(f"LaunchActivity has {len(la_types)} distinct object field types")

def shape(desc):
    c = classes.get(desc)
    if not c:
        return None
    types = [t for (_, t, st) in c["fields"] if not st]
    return {
        "ctx": types.count("Landroid/content/Context;"),
        "arrays": types.count("Ljava/util/ArrayList;"),
        "bools": types.count("Z"),
        "n": len(types),
        "types": types,
    }

w("\n==== field types of LaunchActivity that look like an adapter (Context + 2 or more ArrayList + boolean) ====")
adapters = []
for t in sorted(la_types):
    s = shape(t)
    if s and s["ctx"] >= 1 and s["arrays"] >= 2 and s["bools"] >= 1:
        adapters.append(t)
        w(f"{t}  super={classes[t]['super']}  fields={s['n']} ctx={s['ctx']} arrays={s['arrays']} bools={s['bools']}")
        w("    field types: " + " ".join(s["types"]))
if not adapters:
    w("(none; showing classes with a Context and an ArrayList instead)")
    for t in sorted(la_types):
        s = shape(t)
        if s and s["ctx"] >= 1 and s["arrays"] >= 1:
            adapters.append(t)
            w(f"{t}  super={classes[t]['super']}  fields={s['n']} ctx={s['ctx']} arrays={s['arrays']} bools={s['bools']}")

adapters = adapters[:5]
for t in adapters:
    w(f"\n==== methods of {t} (name proto) ====")
    for (n, p) in classes[t]["methods"][:150]:
        w(f"  {n} {p}")

# code of the methods of those classes that clear a list
targets = set(adapters)
w("\n==== code of methods of those classes that call ArrayList.clear ====")
shown = Counter()
for path in sorted(glob.glob(f"{DEX_DIR}/classes*.dex.d.txt")):
    buf, cls, name, proto = [], None, None, None
    def flush():
        global buf
        if cls in targets and name and proto and any("Ljava/util/ArrayList;.clear:" in l for l in buf) and shown[cls] < 4:
            shown[cls] += 1
            w(f"\n=== {cls}->{name}{proto}")
            for l in buf[:260]:
                w(l.rstrip("\n"))
        buf = []
    with open(path, errors="replace") as fh:
        for line in fh:
            m = IN_RE.search(line)
            if m:
                flush()
                cls, name, proto = m.group(1), None, None
                continue
            if cls in targets:
                m2 = NAME_RE.match(line)
                if m2 and name is None:
                    name = m2.group(1)
                m3 = TYPE_RE.match(line)
                if m3 and m3.group(1).startswith("(") and proto is None:
                    proto = m3.group(1)
                buf.append(line)
    flush()
out.close()
