#!/usr/bin/env python3
"""
Make a patched APK safe for Android to parse.

Android 11+ refuses ("There was a problem parsing the package") an app that targets API 30+ if its
resources.arsc is compressed. Some patch/merge tools leave it deflated. This copies the APK and
stores resources.arsc uncompressed. The old signature files are dropped because the APK is re-signed
afterwards (zipalign first, then apksigner).

Usage: fix_apk.py IN.apk OUT.apk
Prints a short report and exits 0.
"""
import sys
import zipfile

SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


def fix(src, dst):
    fixed = dropped = total = 0
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        for item in zin.infolist():
            name = item.filename
            if name.startswith("META-INF/") and name.upper().endswith(SIGNATURE_SUFFIXES):
                dropped += 1
                continue
            data = zin.read(name)
            new = zipfile.ZipInfo(name, date_time=item.date_time)
            new.external_attr = item.external_attr
            if name == "resources.arsc":
                new.compress_type = zipfile.ZIP_STORED
                if item.compress_type != zipfile.ZIP_STORED:
                    fixed += 1
            else:
                new.compress_type = item.compress_type
            zout.writestr(new, data)
            total += 1
    return total, fixed, dropped


def main():
    if len(sys.argv) != 3:
        print("usage: fix_apk.py IN.apk OUT.apk", file=sys.stderr)
        return 2
    total, fixed, dropped = fix(sys.argv[1], sys.argv[2])
    print(f"fix_apk: {total} entries copied, resources.arsc was compressed: {'YES (fixed)' if fixed else 'no'}, "
          f"{dropped} old signature file(s) dropped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
