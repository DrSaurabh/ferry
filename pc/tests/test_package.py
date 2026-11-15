"""tools/package.py builds the user zip with the app pair [B25] [CP7] (plan §2.11)."""
import hashlib
import json
import os
import subprocess
import sys
import zipfile

from helpers import ROOT, write_app

APK = b"PK\x03\x04" + os.urandom(5000)


def build(tmp_path, app_dir, out="dist"):
    return subprocess.run([sys.executable, os.path.join(ROOT, "tools", "package.py"), "--app-dir", str(app_dir),
                           "--out", str(tmp_path / out)], capture_output=True, text=True, timeout=60)


def test_zip_contents(tmp_path):
    app = tmp_path / "app"
    write_app(app, APK, code=20099, name="2.0.99")
    r = build(tmp_path, app)
    assert r.returncode == 0, r.stderr
    z = tmp_path / "dist" / "Ferry-2.0.99.zip"
    with zipfile.ZipFile(str(z)) as zf:
        names = zf.namelist()
        assert names == sorted(names)
        assert zf.read("Ferry/app/Ferry.apk") == APK
        meta = json.loads(zf.read("Ferry/app/Ferry.apk.json"))
        assert meta["version_name"] == "2.0.99" and meta["sha256"] == hashlib.sha256(APK).hexdigest()
        for n in ("Ferry.bat", "Ferry self-test.bat", "ferry.sh", "ferry.py", "qrcodegen.py", "ferry-icon.svg",
                  "README.txt", "DEVICE-CHECKLIST.txt", "LICENSE.txt", "LICENSE-qrcodegen.txt"):
            assert "Ferry/" + n in names, n
        assert not any(n.endswith("-src.zip") or "/tools/" in n or "/tests/" in n for n in names)
        for n in ("Ferry/README.txt", "Ferry/Ferry.bat"):
            d = zf.read(n)
            assert b"\r\n" in d and b"\n" not in d.replace(b"\r\n", b""), n
        for n in ("Ferry/ferry.py", "Ferry/ferry.sh"):
            assert b"\r\n" not in zf.read(n), n
    r2 = build(tmp_path, app, out="dist2")
    assert r2.returncode == 0
    h = [hashlib.sha256((tmp_path / d / "Ferry-2.0.99.zip").read_bytes()).hexdigest() for d in ("dist", "dist2")]
    assert h[0] == h[1]                          # deterministic


def test_missing_apk_fails(tmp_path):
    app = tmp_path / "app"
    write_app(app, APK)
    os.unlink(str(app / "Ferry.apk"))
    r = build(tmp_path, app)
    assert r.returncode == 1 and "missing" in r.stderr
    assert not (tmp_path / "dist").exists() or not os.listdir(str(tmp_path / "dist"))


def test_sha_mismatch_fails(tmp_path):
    app = tmp_path / "app"
    write_app(app, APK, sha256="0" * 64)
    r = build(tmp_path, app)
    assert r.returncode == 1 and "does not match" in r.stderr
