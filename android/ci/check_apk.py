#!/usr/bin/env python3
"""Check a built Ferry APK (plan §1.4): signing certificate, native libraries, version.

    check_apk.py APP.apk --build-tools DIR [--expect-code N] [--expect-name NAME] [--cert FILE]

- `apksigner verify --print-certs` must succeed and its SHA-256 must equal android/signing/cert-sha256.txt
  (both sides normalised: colons/spaces removed, lower-case) [B23] [PA19].
- `lib/`: only libandroidx.graphics.path.so is allowed, and every library under lib/ must exist for
  armeabi-v7a and arm64-v8a (the Fire TV Stick is 32-bit ARM) [PA1] [PR1].
- `aapt2 dump badging` versionCode / versionName must match when expected values are given.
Exit code 0 = all good; otherwise the problems are printed and the exit code is 1.
"""
import argparse
import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CERT = os.path.join(HERE, "..", "signing", "cert-sha256.txt")
ALLOWED_LIBS = {"libandroidx.graphics.path.so"}
REQUIRED_ABIS = ("armeabi-v7a", "arm64-v8a")


def normalise_fp(text):
    """'90:07:63 …' or '900763…' → '900763…' (lower-case hex only)."""
    return re.sub(r"[\s:]", "", text).lower()


def parse_apksigner(text):
    """SHA-256 digests of the signer certificates in `apksigner verify --print-certs` output."""
    return [normalise_fp(m) for m in
            re.findall(r"^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)\s*$", text, re.M)]


def check_cert(apksigner_output, expected):
    digests = parse_apksigner(apksigner_output)
    want = normalise_fp(expected)
    if not digests:
        return ["apksigner printed no SHA-256 certificate digest"]
    if digests != [want]:
        return ["signing certificate %s does not match cert-sha256.txt %s" % (", ".join(digests), want)]
    return []


def check_libs(names):
    """Problems with the native libraries among the APK entry `names`."""
    problems = []
    by_abi = {}
    for n in names:
        if not n.startswith("lib/") or n.endswith("/"):
            continue
        parts = n.split("/")
        if len(parts) != 3:
            problems.append("unexpected entry under lib/: %s" % n)
            continue
        abi, lib = parts[1], parts[2]
        if lib not in ALLOWED_LIBS:
            problems.append("native library not on the allow-list: %s" % n)
        by_abi.setdefault(abi, set()).add(lib)
    libs = set().union(*by_abi.values()) if by_abi else set()
    for lib in sorted(libs):
        for abi in REQUIRED_ABIS:
            if lib not in by_abi.get(abi, set()):
                problems.append("%s is missing for %s" % (lib, abi))
    return problems


def parse_badging(text):
    m = re.search(r"^package: .*?versionCode='([^']*)'.*?versionName='([^']*)'", text, re.M)
    if not m:
        return None, None
    return m.group(1), m.group(2)


def check_version(badging, code, name):
    got_code, got_name = parse_badging(badging)
    problems = []
    if got_code is None:
        return ["aapt2 badging has no package line"]
    if code is not None and got_code != str(code):
        problems.append("versionCode %s, expected %s" % (got_code, code))
    if name is not None and got_name != name:
        problems.append("versionName %s, expected %s" % (got_name, name))
    return problems


def _version_key(name):
    return [int(p) if p.isdigit() else p for p in re.split(r"[.\-]", name)]


def latest_build_tools(android_home):
    """Highest build-tools version folder under $ANDROID_HOME (never a hard-coded version) [PA7]."""
    root = os.path.join(android_home, "build-tools")
    dirs = [d for d in os.listdir(root) if os.path.isdir(os.path.join(root, d))]
    if not dirs:
        raise SystemExit("no build-tools under %s" % root)
    return os.path.join(root, max(dirs, key=_version_key))


def _run(args):
    r = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
    return r.returncode, r.stdout


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--build-tools", help="build-tools folder (default: highest under $ANDROID_HOME)")
    ap.add_argument("--expect-code")
    ap.add_argument("--expect-name")
    ap.add_argument("--cert", default=DEFAULT_CERT)
    a = ap.parse_args(argv)
    bt = a.build_tools or latest_build_tools(os.environ["ANDROID_HOME"])
    with open(a.cert) as f:
        expected = f.read().strip()

    problems = []
    rc, out = _run([os.path.join(bt, "apksigner"), "verify", "--print-certs", a.apk])
    if rc != 0:
        problems.append("apksigner verify failed:\n" + out)
    else:
        problems += check_cert(out, expected)
    with zipfile.ZipFile(a.apk) as z:
        names = z.namelist()
    libs = sorted(n for n in names if n.startswith("lib/"))
    print("lib/ entries:", *(libs or ["(none)"]), sep="\n  ")
    problems += check_libs(names)
    rc, out = _run([os.path.join(bt, "aapt2"), "dump", "badging", a.apk])
    if rc != 0:
        problems.append("aapt2 dump badging failed:\n" + out)
    else:
        print("badging:", out.splitlines()[0] if out else "")
        problems += check_version(out, a.expect_code, a.expect_name)

    for p in problems:
        print("PROBLEM:", p)
    print("check_apk: %s" % ("FAILED" if problems else "OK"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
