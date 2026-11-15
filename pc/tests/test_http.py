import http.client
import io
import json
import os
import socket
import threading
import time
import zipfile
from urllib.parse import quote

import pytest

import ferry


class Srv:
    def __init__(self, tmp_path):
        self.inbox = tmp_path / "inbox"
        self.outbox = tmp_path / "outbox"
        self.inbox.mkdir()
        self.outbox.mkdir()
        self.out = io.StringIO()
        cfg = ferry.Config(port=0, inbox=str(self.inbox), outbox=str(self.outbox),
                           inbox_label="Downloads/Phone", outbox_label="Desktop/Ferry outbox")
        self.console = ferry.Console(stream=self.out)
        self.app = ferry.make_app(cfg, self.console, "127.0.0.1", is_win=False)
        self.app.outbox.start_scan()
        self.server = ferry.FerryServer(("127.0.0.1", 0), ferry.FerryHandler, app=self.app, exclusive=False)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.1}, daemon=True).start()

    def req(self, method, path, body=None, headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request(method, path, body=body, headers=headers or {})
        r = c.getresponse()
        data = r.read()
        c.close()
        return r, data

    def json(self, method, path, body=None, headers=None):
        r, d = self.req(method, path, body, headers)
        return r.status, json.loads(d.decode("utf-8"))

    def add_out(self, name, data):
        p = self.outbox / name
        p.write_bytes(data)
        self.app.outbox.poll(now=0)
        self.app.outbox.poll(now=100)
        return self.app.outbox.newest()

    def log(self):
        time.sleep(0.15)
        return self.out.getvalue()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.console.stop()


@pytest.fixture
def srv(tmp_path):
    s = Srv(tmp_path)
    yield s
    s.close()


def upload(srv, name, data, mtime=None):
    q = "name=%s&size=%d" % (quote(name), len(data))
    if mtime is not None:
        q += "&mtime=%d" % mtime
    return srv.json("POST", "/api/v1/upload?" + q, body=data, headers={"Content-Length": str(len(data))})


def test_page_and_info(srv):
    r, d = srv.req("GET", "/")
    assert r.status == 200 and b"<title>Ferry</title>" in d and r.getheader("Cache-Control") == "no-store"
    assert b"{{" not in d
    st, j = srv.json("GET", "/api/v1/info")
    assert st == 200 and j["app"] == "ferry" and j["protocol"] == 1
    assert j["inbox_label"] == "Downloads/Phone"
    assert str(srv.inbox) not in json.dumps(j)     # no full paths [U18]
    assert isinstance(j["free_bytes"], int)


def test_head_everywhere(srv):
    for p in ("/", "/api/v1/info", "/favicon.svg", "/diag"):
        r, d = srv.req("HEAD", p)
        assert r.status == 200 and d == b"" and int(r.getheader("Content-Length")) > 0, p


def test_upload_roundtrip_and_logs(srv):
    st, j = upload(srv, "IMG न.jpg", b"hello")
    assert st == 200 and j == {"result": "saved", "saved_as": "IMG न.jpg", "bytes": 5}
    st, j = upload(srv, "IMG न.jpg", b"hello")
    assert j["result"] == "skipped"
    st, j = upload(srv, "IMG न.jpg", b"world")
    assert j["result"] == "renamed" and j["saved_as"] == "IMG न (1).jpg"
    log = srv.log()
    assert "from browser  saved" in log and "already on PC" in log and "renamed" in log


def test_check_endpoint(srv):
    t = 1700000000000
    upload(srv, "a.jpg", b"12345", t)
    st, j = srv.json("GET", "/api/v1/check?name=a.jpg&size=5&mtime=%d" % (t + 1000))
    assert j["ok"] and j["duplicate"] == "a.jpg"
    st, j = srv.json("GET", "/api/v1/check?name=b.jpg&size=5&mtime=%d" % t)
    assert j == {"ok": True, "name": "b.jpg"}


def test_upload_errors(srv):
    c = http.client.HTTPConnection("127.0.0.1", srv.port, timeout=5)
    c.putrequest("POST", "/api/v1/upload?name=x")
    c.endheaders()
    r = c.getresponse()
    assert r.status == 411
    c.close()
    st, j = srv.json("POST", "/api/v1/upload?name=x&size=99", body=b"abc", headers={"Content-Length": "3"})
    assert st == 400
    st, j = srv.json("POST", "/api/v1/upload?name=x", body=b"", headers={"Content-Length": "-1"})
    assert st == 400
    st, j = srv.json("GET", "/api/v1/upload")
    assert st == 405
    assert os.listdir(srv.inbox) == []


def test_disconnect_mid_upload_leaves_no_temp(srv):
    s = socket.create_connection(("127.0.0.1", srv.port))
    s.sendall(b"POST /api/v1/upload?name=big.bin HTTP/1.0\r\nContent-Length: 1000000\r\n\r\n" + b"x" * 1000)
    time.sleep(0.2)
    s.close()
    deadline = time.time() + 5
    while time.time() < deadline and os.listdir(srv.inbox):
        time.sleep(0.1)
    assert os.listdir(srv.inbox) == []
    assert "stopped" in srv.log()


def test_upload_after_inbox_folder_deleted(srv):
    """Audit O2: the folder comes back and the upload is saved."""
    os.rmdir(srv.inbox)
    st, j = upload(srv, "a.jpg", b"x" * 5000)
    assert st == 200 and j["result"] == "saved"
    assert os.listdir(srv.inbox) == ["a.jpg"]
    assert "Inbox folder was missing - recreated" in srv.log()


def test_upload_when_inbox_unusable_gives_clear_500(srv):
    os.rmdir(srv.inbox)
    srv.inbox.write_bytes(b"a file where the folder should be")
    st, j = upload(srv, "a.jpg", b"x" * 5000)       # the phone must get a reply, not a dropped connection
    assert st == 500 and "couldn't save" in j["message"]


def test_disk_full_reply(srv, monkeypatch):
    def full(*a, **k):
        raise ferry.DiskFull(0)
    monkeypatch.setattr(srv.app.inbox, "receive", full)
    st, j = upload(srv, "x.bin", b"abc")
    assert st == 507 and j["reason"] == "disk_full"


def test_outbox_list_download_range(srv):
    data = bytes(range(256)) * 40
    of = srv.add_out("naïve résumé.pdf", data)
    st, j = srv.json("GET", "/api/v1/outbox")
    assert j["files"][0]["name"] == "naïve résumé.pdf" and j["files"][0]["mtime"] > 1e12
    url = j["files"][0]["url"]
    r, d = srv.req("GET", url)
    assert r.status == 200 and d == data
    cd = r.getheader("Content-Disposition")
    assert cd.startswith("attachment; filename=\"na_ve r_sum_.pdf\"; filename*=UTF-8''na%C3%AFve%20r%C3%A9sum%C3%A9.pdf")
    etag = r.getheader("ETag")
    r, d = srv.req("GET", url, headers={"Range": "bytes=0-99"})
    assert r.status == 206 and d == data[:100] and r.getheader("Content-Range") == "bytes 0-99/%d" % len(data)
    r, d = srv.req("GET", url, headers={"Range": "bytes=100-"})
    assert r.status == 206 and d == data[100:]
    r, d = srv.req("GET", url, headers={"Range": "bytes=-50"})
    assert d == data[-50:]
    r, d = srv.req("GET", url, headers={"Range": "bytes=0-1,5-6"})
    assert r.status == 200 and d == data
    r, d = srv.req("GET", url, headers={"Range": "bytes=999999-"})
    assert r.status == 416 and r.getheader("Content-Range") == "bytes */%d" % len(data)
    r, d = srv.req("GET", url, headers={"Range": "bytes=0-9", "If-Range": etag})
    assert r.status == 206
    r, d = srv.req("GET", url, headers={"Range": "bytes=0-9", "If-Range": '"other"'})
    assert r.status == 200 and d == data
    r, d = srv.req("GET", url + "?inline=1")
    assert r.getheader("Content-Disposition").startswith("inline;")
    r, d = srv.req("HEAD", url)
    assert r.status == 200 and d == b"" and r.getheader("Content-Length") == str(len(data))
    assert of.id == 1


def test_apk_mime_and_short_links(srv):
    srv.add_out("Kodi.apk", b"PK-apk")
    srv.add_out("movie.mp4", b"mp4data")
    r, _ = srv.req("GET", "/1")
    assert r.status == 302 and r.getheader("Location") == "/api/v1/outbox/1/Kodi.apk"
    r, _ = srv.req("HEAD", "/1")
    assert r.status == 302
    r, _ = srv.req("GET", "/new")
    assert r.status == 302 and r.getheader("Location") == "/api/v1/outbox/2/movie.mp4"
    r, d = srv.req("GET", "/api/v1/outbox/1/whatever")
    assert r.getheader("Content-Type") == "application/vnd.android.package-archive" and d == b"PK-apk"
    r, d = srv.req("GET", "/77")
    assert r.status == 404 and b"no longer in the outbox" in d


def test_removed_and_changed_file_404(srv):
    srv.add_out("a.txt", b"abc")
    os.unlink(srv.outbox / "a.txt")
    r, d = srv.req("GET", "/api/v1/outbox/1/a.txt")
    assert r.status == 404 and b"no longer in the outbox" in d
    srv.add_out("b.txt", b"abc")
    (srv.outbox / "b.txt").write_bytes(b"abcdef")
    r, d = srv.req("GET", "/api/v1/outbox/2/b.txt")
    assert r.status == 404


def test_symlink_not_served(srv, tmp_path):
    (tmp_path / "secret.txt").write_bytes(b"secret")
    os.symlink(str(tmp_path / "secret.txt"), srv.outbox / "s.txt")
    srv.app.outbox.poll(now=0)
    srv.app.outbox.poll(now=100)
    assert srv.app.outbox.snapshot() == []
    r, d = srv.req("GET", "/api/v1/outbox/1/s.txt")
    assert r.status == 404


def test_zip_download(srv):
    srv.add_out("a.txt", b"A" * 1000)
    srv.add_out("b न.bin", os.urandom(5000))
    r, d = srv.req("GET", "/api/v1/outbox.zip")
    assert r.status == 200 and int(r.getheader("Content-Length")) == len(d)
    zf = zipfile.ZipFile(io.BytesIO(d))
    assert sorted(zf.namelist()) == sorted(["a.txt", "b न.bin"]) and zf.testzip() is None
    r, d = srv.req("GET", "/api/v1/outbox.zip?ids=2")
    assert zipfile.ZipFile(io.BytesIO(d)).namelist() == ["b न.bin"]
    r, d = srv.req("HEAD", "/api/v1/outbox.zip")
    assert d == b"" and int(r.getheader("Content-Length")) > 0
    assert r.getheader("Accept-Ranges") is None


def test_zip_empty_404(srv):
    r, d = srv.req("GET", "/api/v1/outbox.zip")
    assert r.status == 404


def test_zip_file_changes_midway_aborts(srv):
    srv.add_out("a.bin", os.urandom(3 << 20))
    srv.add_out("b.bin", os.urandom(1000))
    (srv.outbox / "b.bin").write_bytes(b"shorter")  # size mismatch -> ZipAbort when reached
    c = http.client.HTTPConnection("127.0.0.1", srv.port, timeout=10)
    c.request("GET", "/api/v1/outbox.zip")
    r = c.getresponse()
    expected = int(r.getheader("Content-Length"))
    with pytest.raises(http.client.IncompleteRead):
        r.read()
    c.close()
    assert expected > 0
    assert "FAILED" in srv.log()


def test_download_cancel_logged(srv):
    srv.add_out("big.bin", b"x" * (8 << 20))
    s = socket.create_connection(("127.0.0.1", srv.port))
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    s.sendall(b"GET /api/v1/outbox/1/big.bin HTTP/1.0\r\n\r\n")
    s.recv(1000)
    s.close()
    deadline = time.time() + 5
    while time.time() < deadline and "cancelled" not in srv.out.getvalue():
        time.sleep(0.1)
    assert "cancelled" in srv.out.getvalue()


def test_play_logged_once(srv):
    srv.add_out("m.mp4", b"x" * 1000)
    for _ in range(3):
        srv.req("GET", "/api/v1/outbox/1/m.mp4", headers={"Range": "bytes=0-"})
    assert srv.log().count("playing") == 1


def test_assets_and_manifest(srv):
    for p, t in (("/icon-192.png", "image/png"), ("/icon-512.png", "image/png"), ("/icon-maskable-512.png", "image/png"),
                 ("/favicon.svg", "image/svg+xml")):
        r, d = srv.req("GET", p)
        assert r.status == 200 and r.getheader("Content-Type") == t and len(d) > 100, p
    r, d = srv.req("GET", "/icon-192.png")
    assert d[:8] == b"\x89PNG\r\n\x1a\n"
    st, m = srv.json("GET", "/manifest.webmanifest")
    assert m["name"] == "Ferry" and any(i["purpose"] == "maskable" for i in m["icons"])


def test_not_found(srv):
    r, d = srv.req("GET", "/nope")
    assert r.status == 404 and b"Back to Ferry" in d
    st, j = srv.json("GET", "/api/v1/nope")
    assert st == 404


def test_qr_page(srv):
    r, d = srv.req("GET", "/qr")
    assert r.status == 200 and b"<svg" in d


def test_diag_endpoints(srv):
    r, d = srv.req("GET", "/api/v1/diag/blob?mb=2")
    assert len(d) == 2000000
    st, j = srv.json("POST", "/api/v1/diag/sink", body=b"x" * 10000, headers={"Content-Length": "10000"})
    assert j["bytes"] == 10000
    r, d = srv.req("GET", "/api/v1/diag/test.zip")
    assert zipfile.ZipFile(io.BytesIO(d)).testzip() is None
    r, d = srv.req("GET", "/api/v1/diag/ferry-test.apk")
    assert r.getheader("Content-Type") == "application/vnd.android.package-archive"
    st, j = srv.json("POST", "/api/v1/diag/inspect?name=x.jpg&mtime=1700000000000", body=b"\xff\xd8\xff\xd9",
                     headers={"Content-Length": "4"})
    assert j["gps"] in ("none", "unknown") and j["size"] == 4
    assert os.listdir(srv.inbox) == []


def test_exif_gps_detection():
    def jpeg_with_gps(lat_nonzero=True):
        import struct
        # TIFF little endian: IFD0 with 1 entry (GPS pointer) -> GPS IFD with GPSLatitude (tag 2)
        tiff = bytearray(b"II*\x00" + struct.pack("<I", 8))
        tiff += struct.pack("<H", 1) + struct.pack("<HHII", 0x8825, 4, 1, 26) + struct.pack("<I", 0)
        # GPS IFD at 26: 1 entry, latitude RATIONAL x3 at offset 44
        tiff += struct.pack("<H", 1) + struct.pack("<HHII", 2, 5, 3, 44) + struct.pack("<I", 0)
        vals = (12, 1, 34, 1, 56, 1) if lat_nonzero else (0, 1, 0, 1, 0, 1)
        tiff += struct.pack("<6I", *vals)
        app1 = b"Exif\x00\x00" + bytes(tiff)
        return b"\xff\xd8" + b"\xff\xe1" + struct.pack(">H", len(app1) + 2) + app1 + b"\xff\xd9"
    assert ferry.exif_gps_status(jpeg_with_gps(True)) == "present"
    assert ferry.exif_gps_status(jpeg_with_gps(False)) == "zeroed"
    assert ferry.exif_gps_status(b"not a jpeg") == "unknown"


def test_second_bind_refused_and_probe(srv):
    with pytest.raises(ferry.FatalError) as ei:
        ferry.bind_server(srv.port, None, exclusive=ferry.IS_WIN, retry_seconds=0)
    # Linux: SO_REUSEADDR still can't bind a LISTENING port. Windows: production uses SO_EXCLUSIVEADDRUSE
    # (a plain SO_REUSEADDR socket would bind there). Either way the probe then finds Ferry.
    assert "already running" in str(ei.value)


def test_bind_error_mapping(monkeypatch):
    def boom(winerror=None, eno=None):
        def f(*a, **k):
            e = OSError(eno or 0, "x")
            if winerror:
                e.winerror = winerror
            raise e
        return f
    monkeypatch.setattr(ferry, "FerryServer", boom(winerror=10013))
    with pytest.raises(ferry.FatalError) as ei:
        ferry.bind_server(8765, None, retry_seconds=0)
    assert "reserved port 8765" in str(ei.value) and "--port 8800" in str(ei.value)
    monkeypatch.setattr(ferry, "FerryServer", boom(winerror=10048))
    with pytest.raises(ferry.FatalError) as ei:
        ferry.bind_server(8765, None, probe=lambda p: False, retry_seconds=0)
    assert "used by another program" in str(ei.value)
    with pytest.raises(ferry.FatalError) as ei:
        ferry.bind_server(8765, None, probe=lambda p: True, retry_seconds=0)
    assert "already running" in str(ei.value)


def test_noise_free_on_reset(srv, capfd):
    s = socket.create_connection(("127.0.0.1", srv.port))
    s.sendall(b"GET / HT")
    s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, b"\x01\x00\x00\x00\x00\x00\x00\x00")
    s.close()
    time.sleep(0.3)
    err = capfd.readouterr().err
    assert "Traceback" not in err


def test_parse_range_unit():
    P = ferry.parse_range
    assert P(None, 10) is None and P("items=0-1", 10) is None and P("bytes=5-2", 10) is None
    assert P("bytes=0-0", 10) == (0, 0) and P("bytes=8-100", 10) == (8, 9)
    assert P("bytes=-0", 10) == "invalid" and P("bytes=10-", 10) == "invalid"
    assert P("bytes=-20", 10) == (0, 9) and P("bytes=abc", 10) is None
