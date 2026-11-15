#!/usr/bin/env python3
"""Build dist/Ferry-<version>.zip (for the user) and dist/Ferry-<version>-src.zip (source + tests).

Run tools/build_assets.py first. Zips are deterministic (fixed timestamps, sorted entries).
"""
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXED = (2026, 11, 14, 12, 0, 0)
USER_FILES = ["Ferry.bat", "Ferry self-test.bat", "ferry.sh", "ferry.py", "qrcodegen.py", "ferry-icon.svg",
              "README.txt", "DEVICE-CHECKLIST.txt", "LICENSE.txt", "LICENSE-qrcodegen.txt"]
CRLF = {"README.txt", "DEVICE-CHECKLIST.txt", "LICENSE.txt", "LICENSE-qrcodegen.txt", "Ferry.bat", "Ferry self-test.bat"}
EXEC = {"ferry.sh", "ferry.py"}
SRC_DIRS = ["tools", "tests"]
SKIP = re.compile(r"(__pycache__|\.pytest_cache|node_modules|\.pyc$|ferry-state\.json|ferry-selftest\.txt|ferry-error\.log)")


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
    elif name.endswith((".py", ".sh", ".svg", ".html", ".mjs")):
        assert b"\r\n" not in data, name
    return data


def main():
    src = open(os.path.join(ROOT, "ferry.py"), encoding="utf-8").read()
    version = re.search(r'^VERSION = "([^"]+)"', src, re.M).group(1)
    assert "Page not built yet" not in src, "run tools/build_assets.py first"
    dist = os.path.join(ROOT, "dist")
    os.makedirs(dist, exist_ok=True)
    user_zip = os.path.join(dist, "Ferry-%s.zip" % version)
    with zipfile.ZipFile(user_zip, "w") as zf:
        for name in USER_FILES:
            add(zf, "Ferry/" + name, read(name), name in EXEC)
    src_zip = os.path.join(dist, "Ferry-%s-src.zip" % version)
    with zipfile.ZipFile(src_zip, "w") as zf:
        for name in USER_FILES:
            add(zf, "Ferry-src/" + name, read(name), name in EXEC)
        for d in SRC_DIRS:
            for base, dirs, files in os.walk(os.path.join(ROOT, d)):
                dirs[:] = sorted(x for x in dirs if not SKIP.search(x))
                for fn in sorted(files):
                    p = os.path.join(base, fn)
                    rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
                    if SKIP.search(rel) or os.path.islink(p):
                        continue
                    add(zf, "Ferry-src/" + rel, open(p, "rb").read())
    for z in (user_zip, src_zip):
        print("%s  %d bytes" % (os.path.relpath(z, ROOT), os.path.getsize(z)))


if __name__ == "__main__":
    sys.exit(main())
