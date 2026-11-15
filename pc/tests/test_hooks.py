"""Hidden test flags with a real ferry.py subprocess [B24] [CP4] (plan §2.3)."""
import json
import os
import socket
import subprocess
import sys

import pytest

import ferry
from helpers import ROOT, start_ferry_process, write_app


@pytest.fixture
def proc(tmp_path):
    made = []

    def start(*extra):
        p = start_ferry_process(tmp_path / ("p%d" % len(made)), *extra)
        made.append(p)
        return p
    for d in range(3):
        (tmp_path / ("p%d" % d)).mkdir()
    yield start
    for p in made:
        p.close()


def test_port0_ready_line(proc):
    p = proc("--port", "0", "--discovery-port", "off")
    assert p.ready_line == "FERRY_READY port=%d discovery=off" % p.port and p.port > 0
    st, j = p.json("GET", "/api/v1/info")
    assert st == 200 and j["app"] == "ferry" and j["protocol"] == 2


def test_port0_page_shows_real_port(proc):
    p = proc("--port", "0", "--discovery-port", "off")
    s = socket.create_connection(("127.0.0.1", p.port), timeout=10)
    s.sendall(b"GET / HTTP/1.0\r\n\r\n")                 # no Host header
    data = b""
    while True:
        b = s.recv(65536)
        if not b:
            break
        data += b
    s.close()
    assert (":%d/app</code>" % p.port).encode() in data and b":0/app" not in data
    assert not any(":0/app" in l for l in p.lines)          # banner too


def test_discovery_port_zero_reported(proc):
    p = proc("--port", "0", "--discovery-port", "0")
    assert p.discovery and p.discovery > 0
    c = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    c.settimeout(3)
    c.sendto(json.dumps({"app": "ferry", "q": "discover", "nonce": "a1b2c3d4"}).encode(), ("127.0.0.1", p.discovery))
    reply = json.loads(c.recvfrom(4096)[0].decode("utf-8"))
    c.close()
    assert reply["nonce"] == "a1b2c3d4" and reply["port"] == p.port


def test_data_dir_holds_runtime_files(proc):
    before = set(os.listdir(ROOT))
    p = proc("--port", "0", "--discovery-port", "off")
    p.req("GET", "/nope")
    from helpers import await_log
    await_log(p.port, p.log_path)
    names = set(os.listdir(str(p.data)))
    assert ferry.STATE_NAME in names and ferry.LOG_NAME in names
    new = {n for n in set(os.listdir(ROOT)) - before if not n.startswith((".", "__"))}
    assert not new, new


def test_test_free_bytes(proc):
    n = 10 << 20
    p = proc("--port", "0", "--discovery-port", "off", "--test-free-bytes", str(n))
    assert p.json("GET", "/api/v1/info")[1]["free_bytes"] == n
    body = json.dumps({"files": [{"name": "a.jpg", "size": 1}]}).encode()
    st, j = p.json("POST", "/api/v1/check-batch", body=body, headers={"Content-Length": str(len(body))})
    assert st == 200 and j["free_bytes"] == max(0, n - ferry.DISK_MARGIN) == 0
    data = b"x" * (1 << 20)
    st, j = p.json("POST", "/api/v1/upload?name=a.bin&size=%d" % len(data), body=data,
                   headers={"Content-Length": str(len(data))})
    assert st == 507 and j["reason"] == "disk_full"
    assert os.listdir(str(p.inbox)) == []


def test_app_dir(proc, tmp_path):
    app_dir = tmp_path / "bundle"
    write_app(app_dir, b"PK" + b"z" * 1000, code=99, name="2.0.99")
    p = proc("--port", "0", "--discovery-port", "off", "--app-dir", str(app_dir))
    st, j = p.json("GET", "/api/v1/app")
    assert st == 200 and j["version_code"] == 99
    assert any("App: Ferry 2.0.99 is ready for your phone and TV" in l for l in p.lines)


def test_hidden_flags_not_in_help():
    r = subprocess.run([sys.executable, os.path.join(ROOT, "ferry.py"), "--help"], capture_output=True, text=True)
    assert r.returncode == 0
    for flag in ("--discovery-port", "--data-dir", "--test-free-bytes", "--app-dir"):
        assert flag not in r.stdout, flag
    readme = open(os.path.join(ROOT, "README.txt"), encoding="utf-8").read()
    for flag in ("--discovery-port", "--data-dir", "--test-free-bytes", "--app-dir"):
        assert flag not in readme, flag


def test_ready_line_only_with_data_dir(tmp_path, monkeypatch):
    """FERRY_READY is for test harnesses (they always pass --data-dir); the user's console stays clean (B15)."""
    import io
    import threading
    import time
    out = io.StringIO()
    real_console = ferry.Console
    monkeypatch.setattr(ferry, "Console", lambda ansi=False: real_console(ansi, stream=out))
    monkeypatch.setattr(ferry, "DATA_DIR", str(tmp_path))
    stop = threading.Event()
    t = threading.Thread(target=ferry.main, args=(["--port", "0", "--no-open", "--discovery-port", "off",
                                                   "--inbox", str(tmp_path / "i"), "--outbox", str(tmp_path / "o")],),
                         kwargs={"stop_event": stop})
    t.start()
    deadline = time.time() + 5
    while time.time() < deadline and "Waiting for your phone" not in out.getvalue():
        time.sleep(0.05)
    stop.set()
    t.join(5)
    assert "Waiting for your phone" in out.getvalue() and "FERRY_READY" not in out.getvalue()
