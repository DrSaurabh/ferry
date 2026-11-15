"""app/Ferry.apk + Ferry.apk.json: /app, /app/Ferry.apk, /api/v1/app, info (C3, B26, PB2, PB24)."""
import hashlib
import json
import os
import time

import ferry
from helpers import write_app

APK = b"PK\x03\x04" + bytes(range(256)) * 50


def test_no_bundle(srv):
    st, j = srv.json("GET", "/api/v1/app")
    assert st == 404 and j == {"result": "error", "message": "no app bundled"}
    r, d = srv.req("GET", "/app")
    assert r.status == 404 and r.getheader("Content-Type").startswith("text/html") and b"No Ferry app" in d
    r, d = srv.req("GET", "/app/Ferry.apk")
    assert r.status == 404
    st, j = srv.json("GET", "/api/v1/info")
    assert j["app_version_code"] is None
    r, d = srv.req("GET", "/")
    assert b"This Ferry folder has no app" in d and b'href="/app"' not in d


def test_valid_bundle(srv):
    meta = write_app(srv.app_dir, APK, code=57, name="2.0.57")
    st, j = srv.json("GET", "/api/v1/app")
    assert st == 200 and j == {"version_code": 57, "version_name": "2.0.57", "size": len(APK),
                               "sha256": meta["sha256"], "url": "/app/Ferry.apk"}
    r1, d1 = srv.req("GET", "/app")
    r2, d2 = srv.req("GET", "/app/Ferry.apk")
    assert r1.status == r2.status == 200 and d1 == d2 == APK
    assert r1.getheader("Content-Type") == ferry.APK_MIME
    assert 'filename="Ferry.apk"' in r1.getheader("Content-Disposition")
    r, d = srv.req("GET", "/app", headers={"Range": "bytes=0-9"})
    assert r.status == 206 and d == APK[:10]
    r, d = srv.req("HEAD", "/app/Ferry.apk")
    assert r.status == 200 and d == b"" and int(r.getheader("Content-Length")) == len(APK)
    st, j = srv.json("GET", "/api/v1/info")
    assert j["app_version_code"] == 57


def test_invalid_json(srv):
    sha = hashlib.sha256(APK).hexdigest()
    cases = [b"garbage", b"[]", json.dumps({"version_name": "2.0.1", "size": len(APK), "sha256": sha}).encode(),
             json.dumps({"version_code": 0, "version_name": "x", "size": len(APK), "sha256": sha}).encode(),
             json.dumps({"version_code": True, "version_name": "x", "size": len(APK), "sha256": sha}).encode(),
             json.dumps({"version_code": 3, "version_name": "x", "size": len(APK), "sha256": "abc"}).encode(),
             json.dumps({"version_code": 3, "version_name": "x", "size": len(APK), "sha256": sha.upper()}).encode(),
             json.dumps({"version_code": 3, "version_name": "", "size": len(APK), "sha256": sha}).encode()]
    write_app(srv.app_dir, APK)
    for body in cases:
        (srv.app_dir / ferry.APK_JSON).write_bytes(body)
        st, j = srv.json("GET", "/api/v1/app")
        assert st == 404 and j["message"] == "no app bundled", body
        r, d = srv.req("GET", "/app")
        assert r.status == 404, body


def test_sha_or_size_mismatch_is_copying(srv):
    write_app(srv.app_dir, APK)
    (srv.app_dir / ferry.APK_NAME).write_bytes(APK[:-1] + b"X")         # same size, other bytes
    for path in ("/api/v1/app", "/app"):
        r, d = srv.req("GET", path)
        assert r.status == 404 and b"being copied" in d, path
    (srv.app_dir / ferry.APK_NAME).write_bytes(APK[:100])               # still copying: shorter
    st, j = srv.json("GET", "/api/v1/app")
    assert st == 404 and j["message"] == "The app is being copied, try again in a moment"
    r, d = srv.req("GET", "/app/Ferry.apk")
    assert r.status == 404 and APK[:10] not in d
    assert srv.json("GET", "/api/v1/info")[1]["app_version_code"] is None


def test_replaced_while_running(srv):
    write_app(srv.app_dir, APK, code=1, name="2.0.1")
    assert srv.json("GET", "/api/v1/app")[1]["version_code"] == 1
    new = APK + b"new build"
    (srv.app_dir / ferry.APK_NAME).write_bytes(new)
    assert srv.json("GET", "/api/v1/app")[0] == 404
    write_app(srv.app_dir, new, code=2, name="2.0.2")
    st, j = srv.json("GET", "/api/v1/app")
    assert st == 200 and j["version_code"] == 2
    assert srv.req("GET", "/app")[1] == new


def counting_bundle(folder):
    calls = []

    def hasher(path):
        calls.append(path)
        return ferry.sha256_file(path)
    return ferry.AppBundle(str(folder), hasher=hasher), calls


def test_hash_only_when_key_changes(tmp_path):
    write_app(tmp_path, APK)
    b, calls = counting_bundle(tmp_path)
    for _ in range(10):
        assert b.status()[0] == "ok"
    assert len(calls) == 1
    meta = json.loads((tmp_path / ferry.APK_JSON).read_text())
    meta["note"] = "different text"
    (tmp_path / ferry.APK_JSON).write_text(json.dumps(meta))
    assert b.status()[0] == "ok" and len(calls) == 2
    p = str(tmp_path / ferry.APK_JSON)
    t = time.time() + 50
    os.utime(p, (t, t))                       # touched, same bytes
    assert b.status()[0] == "ok" and len(calls) == 2


def test_same_size_same_mtime_replacement(srv):
    a = APK
    b = bytes(reversed(APK))                   # same size
    write_app(srv.app_dir, a, code=10, name="2.0.10")
    apk, js = str(srv.app_dir / ferry.APK_NAME), str(srv.app_dir / ferry.APK_JSON)
    st_apk, st_js = os.stat(apk), os.stat(js)
    assert srv.json("GET", "/api/v1/app")[1]["version_code"] == 10
    meta = write_app(srv.app_dir, b, code=11, name="2.0.11")
    assert os.path.getsize(js) == st_js.st_size and os.path.getsize(apk) == st_apk.st_size
    os.utime(apk, ns=(st_apk.st_atime_ns, st_apk.st_mtime_ns))
    os.utime(js, ns=(st_js.st_atime_ns, st_js.st_mtime_ns))
    st, j = srv.json("GET", "/api/v1/app")
    assert st == 200 and j["version_code"] == 11 and j["sha256"] == meta["sha256"]
    assert srv.req("GET", "/app")[1] == b


def test_warmup_thread(tmp_path):
    """main() hashes the APK in a background thread right after the bind [PB24]."""
    from helpers import start_ferry_process
    app_dir = tmp_path / "appdir"
    write_app(app_dir, APK, code=5, name="2.0.5")
    work = tmp_path / "w"
    work.mkdir()
    marker = tmp_path / "hashed.txt"
    # Observe the hash from outside: a tiny launcher wraps ferry.sha256_file, then runs Ferry.
    launcher = tmp_path / "launch.py"
    launcher.write_text(
        "import sys, runpy\n"
        "sys.path.insert(0, %r)\n"
        "import ferry\n"
        "real = ferry.sha256_file\n"
        "def counted(p):\n"
        "    with open(%r, 'a') as f: f.write('hash\\n')\n"
        "    return real(p)\n"
        "ferry.sha256_file = counted\n"
        "sys.argv = ['ferry.py'] + sys.argv[1:]\n"
        "sys.exit(ferry.run())\n" % (os.path.dirname(os.path.dirname(os.path.abspath(__file__))), str(marker)))
    p = start_ferry_process(work, "--port", "0", "--discovery-port", "off", "--app-dir", str(app_dir),
                            script=str(launcher))
    try:
        deadline = time.time() + 5
        while time.time() < deadline and not (marker.exists() and "hash" in marker.read_text()):
            time.sleep(0.05)
        assert marker.exists() and marker.read_text().count("hash") == 1    # before any request
        st, j = p.json("GET", "/api/v1/app")
        assert st == 200 and j["version_code"] == 5
        assert marker.read_text().count("hash") == 1
    finally:
        p.close()
