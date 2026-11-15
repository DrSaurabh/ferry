#!/usr/bin/env python3
"""Write Ferry.apk.json for an APK (plan §1.4, §5.3; read by ferry.py's AppBundle and package.py).

    make_apk_json.py APP.apk VERSION_CODE VERSION_NAME > Ferry.apk.json

Fields: version_code, version_name, size, sha256 (lower-case hex), url ("/app/Ferry.apk", where the PC
serves the APK).
"""
import hashlib
import json
import os
import sys

URL = "/app/Ferry.apk"


def apk_json(path, code, name):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    code = int(code)
    if code <= 0 or not name:
        raise ValueError("version code must be > 0 and version name non-empty")
    return {"version_code": code, "version_name": name, "size": os.path.getsize(path),
            "sha256": h.hexdigest(), "url": URL}


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    print(json.dumps(apk_json(*argv), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
