"""Upload changes in 2.0: protocol 2 and the early free-space refusal [CP3] [B11] [PB4] [PR18]."""
import os
import socket
import time

import ferry
from helpers import await_log, upload

MB = 1 << 20


def test_info_protocol_2(srv):
    st, j = srv.json("GET", "/api/v1/info")
    assert j["protocol"] == ferry.PROTOCOL == 2 and "check_batch" in j["capabilities"]


def test_upload_precheck_507_small_body(make_srv):
    s = make_srv(free_override=10 * MB)
    st, j = upload(s, "a.bin", b"x" * MB)
    assert st == 507 and j == {"result": "error", "reason": "disk_full", "message": "PC disk is full"}
    assert os.listdir(str(s.inbox)) == []
    lines = await_log(s)
    f = [l.split("\t") for l in lines if "/api/v1/upload" in l][0]
    assert f[6] == "507" and f[7] == str(MB)              # the small body was read completely
    assert "FAILED" in s.log() and "PC disk is full" in s.log()


def test_upload_precheck_large_body_closes(make_srv):
    s = make_srv(free_override=10 * MB)
    declared = 100 * MB
    sock = socket.create_connection(("127.0.0.1", s.port), timeout=20)
    sock.sendall(b"POST /api/v1/upload?name=big.bin&size=%d HTTP/1.0\r\nContent-Length: %d\r\n\r\n" % (declared, declared))
    chunk = b"x" * (256 * 1024)
    sent, reply = 0, b""
    try:
        while sent < declared:
            sock.sendall(chunk)
            sent += len(chunk)
    except OSError:                       # the PC stopped reading and closed: reset / broken pipe
        pass
    try:
        sock.settimeout(5)
        while True:
            b = sock.recv(65536)
            if not b:
                break
            reply += b
    except OSError:
        pass
    sock.close()
    assert reply == b"" or b" 507 " in reply.split(b"\r\n", 1)[0]
    lines = await_log(s, timeout=15)
    f = [l.split("\t") for l in lines if "/api/v1/upload" in l][0]
    assert f[6] == "507" and int(f[7]) <= 65 * MB, f     # the server did not read the whole body
    deadline = time.time() + 5
    while time.time() < deadline and os.listdir(str(s.inbox)):
        time.sleep(0.1)
    assert os.listdir(str(s.inbox)) == []


def test_precheck_skipped_for_same_size_family(make_srv):
    s = make_srv(free_override=1 * MB)
    data = os.urandom(2 * MB)
    (s.inbox / "IMG_1.jpg").write_bytes(data)
    st, j = upload(s, "IMG_1.jpg", data)
    assert st == 200 and j["result"] == "skipped" and j["saved_as"] == "IMG_1.jpg"
    st, j = upload(s, "img_1.jpg" if ferry.IS_WIN else "IMG_1.jpg", data[:-1])
    assert st == 507
    assert sorted(os.listdir(str(s.inbox))) == ["IMG_1.jpg"]
