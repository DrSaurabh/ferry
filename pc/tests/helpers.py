"""Shared test helpers: an in-process Ferry server (Srv), uploads, GPS JPEGs, a subprocess Ferry, log waits."""
import http.client
import io
import json
import os
import queue
import struct
import subprocess
import sys
import threading
import time
import uuid
from urllib.parse import quote

import ferry

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class Srv:
    """ferry.FerryServer on 127.0.0.1 with tmp inbox/outbox, a captured console and a RequestLog."""

    def __init__(self, tmp_path, app_dir=None, free_override=None, reqlog=True, is_win=False):
        self.tmp = tmp_path
        self.inbox = tmp_path / "inbox"
        self.outbox = tmp_path / "outbox"
        self.data = tmp_path / "data"
        self.app_dir = tmp_path / "app" if app_dir is None else app_dir
        for d in (self.inbox, self.outbox, self.data):
            d.mkdir(exist_ok=True)
        self.out = io.StringIO()
        cfg = ferry.Config(port=0, inbox=str(self.inbox), outbox=str(self.outbox),
                           inbox_label="Downloads/Phone", outbox_label="Desktop/Ferry outbox")
        self.console = ferry.Console(stream=self.out)
        self.reqlog = ferry.RequestLog(str(self.data), self.console.note) if reqlog else None
        self.app = ferry.make_app(cfg, self.console, "127.0.0.1", is_win=is_win, app_dir=str(self.app_dir),
                                  free_override=free_override, reqlog=self.reqlog)
        self.app.outbox.start_scan()
        self.server = ferry.FerryServer(("127.0.0.1", 0), ferry.FerryHandler, app=self.app, exclusive=False)
        self.port = self.server.server_address[1]
        cfg.port = self.port
        self.app.primary_url = ferry.page_url("127.0.0.1", self.port)
        if self.reqlog:
            self.reqlog.start()
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.1}, daemon=True)
        self.thread.start()

    @property
    def log_path(self):
        return str(self.data / ferry.LOG_NAME)

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

    def log_lines(self):
        """ferry-log.txt lines written before a sentinel request (never races the writer) [PB12]."""
        return await_log(self)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        if self.reqlog:
            self.reqlog.stop()           # closes the handle, so Windows can delete tmp_path [PB19]
        self.console.stop()


def upload(srv, name, data, mtime=None, headers=None):
    q = "name=%s&size=%d" % (quote(name), len(data))
    if mtime is not None:
        q += "&mtime=%d" % mtime
    h = {"Content-Length": str(len(data))}
    h.update(headers or {})
    return srv.json("POST", "/api/v1/upload?" + q, body=data, headers=h)


def await_log(srv_or_port, path=None, timeout=5.0):
    """Send GET /api/v1/__sentinel_<uuid> (404, always logged), wait until it is in the log, return the
    lines before it. Takes a Srv, or (port, log path) for a subprocess Ferry."""
    if isinstance(srv_or_port, Srv):
        port, path = srv_or_port.port, srv_or_port.log_path
    else:
        port = srv_or_port
    marker = "/api/v1/__sentinel_" + uuid.uuid4().hex
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    c.request("GET", marker)
    c.getresponse().read()
    c.close()
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with open(path, encoding="utf-8") as f:
                lines = f.read().splitlines()
        except OSError:
            lines = []
        for i, line in enumerate(lines):
            if marker in line:
                return lines[:i]
        time.sleep(0.05)
    raise AssertionError("sentinel %s never reached %s" % (marker, path))


def jpeg_with_gps(lat_nonzero=True):
    # TIFF little endian: IFD0 with 1 entry (GPS pointer) -> GPS IFD with GPSLatitude (tag 2)
    tiff = bytearray(b"II*\x00" + struct.pack("<I", 8))
    tiff += struct.pack("<H", 1) + struct.pack("<HHII", 0x8825, 4, 1, 26) + struct.pack("<I", 0)
    # GPS IFD at 26: 1 entry, latitude RATIONAL x3 at offset 44
    tiff += struct.pack("<H", 1) + struct.pack("<HHII", 2, 5, 3, 44) + struct.pack("<I", 0)
    vals = (12, 1, 34, 1, 56, 1) if lat_nonzero else (0, 1, 0, 1, 0, 1)
    tiff += struct.pack("<6I", *vals)
    app1 = b"Exif\x00\x00" + bytes(tiff)
    return b"\xff\xd8" + b"\xff\xe1" + struct.pack(">H", len(app1) + 2) + app1 + b"\xff\xd9"


class FerryProcess:
    """ferry.py in a subprocess. stdout is read by a thread into a queue (select() doesn't work on pipes
    on Windows) [PB19]."""

    def __init__(self, tmp_path, *extra, ready_timeout=15.0, script=None):
        self.root = tmp_path
        self.inbox, self.outbox, self.data = tmp_path / "in", tmp_path / "out", tmp_path / "data"
        args = [sys.executable, "-u", script or os.path.join(ROOT, "ferry.py"), "--no-open", "--inbox", str(self.inbox),
                "--outbox", str(self.outbox), "--data-dir", str(self.data)] + list(extra)
        env = dict(os.environ, PYTHONIOENCODING="utf-8", HOME=str(tmp_path), XDG_CONFIG_HOME=str(tmp_path / "cfg"))
        self.p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                  env=env, cwd=str(tmp_path))
        self.q = queue.Queue()
        self.lines = []
        threading.Thread(target=self._read, daemon=True).start()
        self.port = None
        self.discovery = None
        deadline = time.time() + ready_timeout
        while time.time() < deadline:
            try:
                line = self.q.get(timeout=max(0.05, deadline - time.time()))
            except queue.Empty:
                break
            if line is None:
                break
            if line.startswith("FERRY_READY "):
                kv = dict(p.split("=", 1) for p in line.split()[1:])
                self.port = int(kv["port"])
                self.discovery = None if kv["discovery"] == "off" else int(kv["discovery"])
                self.ready_line = line
                return
        self.close()
        raise AssertionError("no FERRY_READY within %.0f s; output:\n%s" % (ready_timeout, "\n".join(self.lines)))

    def _read(self):
        for raw in self.p.stdout:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            self.lines.append(line)
            self.q.put(line)
        self.q.put(None)

    @property
    def log_path(self):
        return str(self.data / ferry.LOG_NAME)

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

    def close(self):
        if self.p.poll() is None:
            self.p.kill()
        try:
            self.p.wait(10)
        except Exception:
            pass
        try:
            self.p.stdout.close()
        except Exception:
            pass


def start_ferry_process(tmp_path, *extra, **kw):
    return FerryProcess(tmp_path, *extra, **kw)


def write_app(app_dir, data=b"PK\x03\x04 fake apk", code=7, name="2.0.7", **override):
    """Write app/Ferry.apk + a matching Ferry.apk.json; returns the meta dict."""
    import hashlib
    os.makedirs(str(app_dir), exist_ok=True)
    with open(os.path.join(str(app_dir), ferry.APK_NAME), "wb") as f:
        f.write(data)
    meta = {"version_code": code, "version_name": name, "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(), "url": "Ferry.apk"}
    meta.update(override)
    with open(os.path.join(str(app_dir), ferry.APK_JSON), "w", encoding="utf-8") as f:
        json.dump(meta, f)
    return meta
