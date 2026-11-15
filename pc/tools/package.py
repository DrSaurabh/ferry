#!/usr/bin/env python3
"""Build dist/Ferry-<version_name>.zip for the user (plan §2.11, [B25] [CP7]).

    python tools/package.py [--app-dir app] [--out dist]

The zip holds the PC program and the app pair app/Ferry.apk + app/Ferry.apk.json (written by CI). It refuses
to build without a consistent pair. Deterministic: fixed timestamps, sorted entries, CRLF for .txt/.bat.
"""
import argparse
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)
import ferry  # noqa: E402

FIXED = (2026, 11, 14, 12, 0, 0)
USER_FILES = ["Ferry.bat", "Ferry self-test.bat", "ferry.sh", "ferry.py", "qrcodegen.py", "ferry-icon.svg",
              "README.txt", "DEVICE-CHECKLIST.txt", "LICENSE.txt", "LICENSE-qrcodegen.txt"]
CRLF = {"README.txt", "DEVICE-CHECKLIST.txt", "LICENSE.txt", "LICENSE-qrcodegen.txt", "Ferry.bat", "Ferry self-test.bat"}
EXEC = {"ferry.sh", "ferry.py"}


def add(zf, arcname, data, executable=False):
    zi = zipfile.ZipInfo(arcname, FIXED)
    zi.create_system = 3
    zi.external_attr = ((0o100755 if executable else 0o100644) << 16)
    zi.compress_type = zipfile.ZIP_DEFLATED
    zf.writestr(zi, data)


def read(name):
    with open(os.path.join(ROOT, name), "rb") as f:
        data = f.read()
    if name in CRLF:
        data = data.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
    elif name.endswith((".py", ".sh", ".svg")):
        assert b"\r\n" not in data, name
    return data


def main(argv=None):
    p = argparse.ArgumentParser(description="Build the user zip Ferry-<version>.zip")
    p.add_argument("--app-dir", default=os.path.join(ROOT, "app"), help="folder with Ferry.apk + Ferry.apk.json")
    p.add_argument("--out", default=os.path.join(ROOT, "dist"), help="output folder")
    a = p.parse_args(argv)
    assert ferry.VERSION.startswith("2."), ferry.VERSION
    state, meta, _ = ferry.AppBundle(a.app_dir).status()
    if state != "ok":
        why = {"missing": "Ferry.apk or Ferry.apk.json is missing",
               "invalid": "Ferry.apk.json is not valid",
               "copying": "Ferry.apk does not match Ferry.apk.json (size or sha256)"}[state]
        print("package.py: can't build the zip: %s in %s" % (why, a.app_dir), file=sys.stderr)
        return 1
    os.makedirs(a.out, exist_ok=True)
    out = os.path.join(a.out, "Ferry-%s.zip" % meta["version_name"])
    entries = [("Ferry/" + n, read(n), n in EXEC) for n in USER_FILES]
    for n in (ferry.APK_NAME, ferry.APK_JSON):
        with open(os.path.join(a.app_dir, n), "rb") as f:
            entries.append(("Ferry/app/" + n, f.read(), False))      # binary: no line-ending changes
    with zipfile.ZipFile(out, "w") as zf:
        for arcname, data, ex in sorted(entries, key=lambda e: e[0]):
            add(zf, arcname, data, ex)
    print("%s  %d bytes" % (out, os.path.getsize(out)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
