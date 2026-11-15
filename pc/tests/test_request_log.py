"""ferry-log.txt (C5, design §2.5, plan §2.4). Every assertion on the file goes through a sentinel [PB12]."""
import http.client
import os
import socket
import threading
import time


import ferry
from helpers import await_log, upload


def fields(line):
    f = line.split("\t")
    assert len(f) == 11, line
    return f


def lines_with(lines, text):
    return [l for l in lines if text in l]


class Notes:
    def __init__(self):
        self.lines = []

    def __call__(self, text):
        self.lines.append(text)


def wait_for(cond, timeout=5.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if cond():
            return True
        time.sleep(0.02)
    return cond()


def read_log(folder):
    out = []
    for n in (ferry.LOG_OLD_NAME, ferry.LOG_NAME):
        p = os.path.join(str(folder), n)
        if os.path.exists(p):
            with open(p, encoding="utf-8") as f:
                out += f.read().splitlines()
    return out


def test_line_format(tmp_path):
    log = ferry.RequestLog(str(tmp_path), Notes())
    log.start()
    log.request("10.0.0.2", "vivo\tX200", "POST", "/api/v1/upload?name=IMG%201.jpg&size=5&mtime=1700000000000",
                None, 200, 5, 12, "saved IMG 1.jpg", None)
    log.request("10.0.0.2", "tv", "GET", "/api/v1/outbox/3/a%20b.mp4?inline=1", "bytes=0-9", 206, 10, 3,
                "playing", "line\nbreak")
    log.stop()
    lines = read_log(tmp_path)
    f = fields(lines[0])
    assert f[1:] == ["10.0.0.2", "vivo X200", "POST", "/api/v1/upload?name=IMG 1.jpg&size&mtime", "-", "200", "5",
                     "12", "saved IMG 1.jpg", "-"]
    assert len(f[0]) == len("2026-11-15 14:03:22.123") and f[0][10] == " " and f[0][19] == "."
    f = fields(lines[1])
    assert f[4] == "/api/v1/outbox/3/a b.mp4?inline" and f[5] == "bytes=0-9" and f[10] == "line break"


def test_transfers_logged(srv):
    st, j = upload(srv, "IMG_1.jpg", b"x" * 3000, 1700000000000, headers={ferry.DEVICE_HEADER: "vivo%20X200%20Pro"})
    assert st == 200
    srv.add_out("movie.mp4", bytes(1000))
    srv.req("GET", "/api/v1/outbox/1/movie.mp4", headers={"Range": "bytes=0-9"})
    srv.req("GET", "/api/v1/outbox.zip")
    srv.add_out("big.bin", b"x" * (8 << 20))
    s = socket.create_connection(("127.0.0.1", srv.port))
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    s.sendall(b"GET /api/v1/outbox/2/big.bin HTTP/1.0\r\n\r\n")
    s.recv(1000)
    s.close()
    assert wait_for(lambda: "cancelled" in srv.out.getvalue())
    lines = await_log(srv)
    up = fields(lines_with(lines, "/api/v1/upload")[0])
    assert up[2] == "vivo X200 Pro" and up[3] == "POST" and up[4] == "/api/v1/upload?name=IMG_1.jpg&size&mtime"
    assert up[6] == "200" and up[7] == "3000" and int(up[8]) >= 0 and up[9] == "saved IMG_1.jpg"
    rng = fields(lines_with(lines, "/api/v1/outbox/1/movie.mp4")[0])
    assert rng[5] == "bytes=0-9" and rng[6] == "206" and rng[7] == "10" and rng[9].startswith("playing")
    z = fields(lines_with(lines, "/api/v1/outbox.zip")[0])
    assert z[6] == "200" and int(z[7]) > 1000 and z[9].startswith("sent Ferry-1-files.zip")
    c = fields(lines_with(lines, "/api/v1/outbox/2/big.bin")[0])
    assert c[9] == "cancelled big.bin" and c[10] != "-" and int(c[7]) < (8 << 20)


def test_errors_always_logged(srv, monkeypatch):
    srv.req("GET", "/nope")
    srv.req("GET", "/api/v1/upload")
    c = http.client.HTTPConnection("127.0.0.1", srv.port, timeout=5)
    c.putrequest("POST", "/api/v1/upload?name=x")
    c.endheaders()
    c.getresponse().read()
    c.close()

    def full(*a, **k):
        raise ferry.DiskFull(0)
    monkeypatch.setattr(srv.app.inbox, "receive", full)
    upload(srv, "x.bin", b"abc")
    monkeypatch.setattr(srv.app.outbox, "snapshot", lambda: 1 / 0)
    srv.req("GET", "/api/v1/outbox")
    lines = await_log(srv)
    statuses = [fields(l)[6] for l in lines if "\tEVENT\t" not in l]
    for code in ("404", "405", "411", "507", "500"):
        assert code in statuses, (code, statuses)
    e500 = fields(lines_with(lines, "\t500\t")[0])
    assert e500[4] == "/api/v1/outbox" and "ZeroDivisionError" in e500[10]
    e507 = fields(lines_with(lines, "\t507\t")[0])
    assert e507[9] == "FAILED x.bin" and e507[10] == "PC disk is full"


def test_polling_collapsed(srv):
    h = {ferry.DEVICE_HEADER: "Saurabh%E2%80%99s%20Fire%20TV"}
    for _ in range(50):
        srv.req("GET", "/api/v1/outbox", headers=h)
    for _ in range(19):
        srv.req("GET", "/api/v1/info", headers=h)
    srv.req("GET", "/api/v1/info?x=1", headers=h)
    srv.req("POST", "/api/v1/info", body=b"", headers=h)
    srv.req("GET", "/api/v1/infox", headers=h)
    lines = await_log(srv)
    assert not [l for l in lines if fields(l)[4] in ("/api/v1/outbox",) or fields(l)[4].startswith("/api/v1/info?")]
    assert fields(lines_with(lines, "\tPOST\t/api/v1/info\t")[0])[6] == "405"
    assert fields(lines_with(lines, "/api/v1/infox")[0])[6] == "404"
    srv.reqlog.flush_polls()
    lines = await_log(srv)
    ref = lines_with(lines, "list refreshes")
    assert len(ref) == 1
    f = fields(ref[0])
    assert f[1] == "127.0.0.1" and f[2] == "Saurabh’s Fire TV" and f[3] == "EVENT" and f[9] == "70 list refreshes"


def test_poll_flush_on_timer(tmp_path):
    now = [1000.0]
    log = ferry.RequestLog(str(tmp_path), Notes(), clock=lambda: now[0], poll_flush_s=600)
    log.start()
    for _ in range(5):
        log.poll("10.0.0.3", "phone")
    now[0] += 601
    assert wait_for(lambda: "5 list refreshes" in "\n".join(read_log(tmp_path)), 4)
    log.stop()


class BlockingFile:
    def __init__(self, gate):
        self.gate = gate

    def write(self, s):
        self.gate.wait(10)
        return len(s)

    def flush(self):
        pass

    def close(self):
        pass


def test_writer_never_blocks(tmp_path):
    gate = threading.Event()
    real_open = open
    state = {"n": 0}

    def opener(path, mode="r", **kw):
        state["n"] += 1
        if state["n"] == 1:
            return BlockingFile(gate)
        return real_open(path, mode, **kw)
    log = ferry.RequestLog(str(tmp_path), Notes(), opener=opener, queue_max=1000)
    log.start()
    log.event("first")                           # the writer stalls on this one
    time.sleep(0.1)
    t0 = time.time()
    for i in range(20000):
        log.request("1.2.3.4", "phone", "GET", "/x", None, 200, 1, 1, "ok", None)
    assert time.time() - t0 < 1.0
    assert log.dropped > 18000
    gate.set()
    log._f = None                                # next write reopens a real file
    time.sleep(0.2)
    log.event("after")
    log.stop()
    text = "\n".join(read_log(tmp_path))
    assert "log lines dropped)" in text and "after" in text


def fill(log, n_bytes, tag="x"):
    line = tag * 1000
    for _ in range(n_bytes // 1100 + 1):
        log.event(line)


def test_rotation(tmp_path):
    log = ferry.RequestLog(str(tmp_path), Notes(), max_bytes=50_000, rotate_delays=(0, 0, 0))
    log.start()
    fill(log, 60_000, "a")
    log.event("marker-after-rotation")
    log.stop()
    old, cur = tmp_path / ferry.LOG_OLD_NAME, tmp_path / ferry.LOG_NAME
    assert old.exists() and os.path.getsize(str(cur)) < 50_000
    assert "marker-after-rotation" in cur.read_text(encoding="utf-8")
    first_old = old.read_text(encoding="utf-8")
    log = ferry.RequestLog(str(tmp_path), Notes(), max_bytes=50_000, rotate_delays=(0, 0, 0))
    log.start()
    fill(log, 60_000, "b")
    log.stop()
    new_old = old.read_text(encoding="utf-8")         # .1 already present -> replaced
    assert new_old != first_old and "marker-after-rotation" in new_old and "bbbb" in new_old


def test_rotation_replace_fails_keeps_appending(tmp_path):
    # scaled down from the real limits (5 MB, retry every 1 MB, truncate at 10 MB)
    assert (ferry.LOG_MAX_BYTES, ferry.LOG_RETRY_STEP, ferry.LOG_HARD_CAP) == (5 << 20, 1 << 20, 10 << 20)
    calls = []

    def replace(a, b):
        calls.append(os.path.getsize(a))
        raise PermissionError(13, "in use")
    notes = Notes()
    log = ferry.RequestLog(str(tmp_path), notes, max_bytes=50_000, retry_step=10_000, hard_cap=100_000,
                           replace=replace, rotate_delays=(0, 0, 0))
    log.start()
    n = 0
    for i in range(85):                          # ~94 KB: grows past 50 KB, stays below the 100 KB cap
        log.event("%05d " % i + "y" * 1100)
        n += 1
    log.stop()
    lines = read_log(tmp_path)
    assert [l.split("\t")[9][:5] for l in lines] == ["%05d" % i for i in range(n)]      # nothing lost
    assert os.path.getsize(str(tmp_path / ferry.LOG_NAME)) > 50_000
    assert len(calls) % 3 == 0 and len(calls) >= 9                       # 3 tries per attempt
    sizes = []
    for c in calls:
        if not sizes or c != sizes[-1]:
            sizes.append(c)
    assert all(b - a >= 10_000 for a, b in zip(sizes, sizes[1:])), sizes      # retried after another step
    assert notes.lines == ["Can't rotate ferry-log.txt (is it open in another program?)"]
    log = ferry.RequestLog(str(tmp_path), notes, max_bytes=50_000, retry_step=10_000, hard_cap=100_000,
                           replace=replace, rotate_delays=(0, 0, 0))
    log.start()
    for i in range(20):
        log.event("z" * 1100)
    log.event("after-cap")
    log.stop()
    text = (tmp_path / ferry.LOG_NAME).read_text(encoding="utf-8")
    assert "log restarted (could not rotate)" in text and "after-cap" in text
    assert os.path.getsize(str(tmp_path / ferry.LOG_NAME)) < 50_000


def test_rotation_with_other_handle_open(tmp_path):
    log = ferry.RequestLog(str(tmp_path), Notes(), max_bytes=50_000, retry_step=10_000, rotate_delays=(0, 0, 0))
    log.start()
    log.event("start")
    assert wait_for(lambda: (tmp_path / ferry.LOG_NAME).exists())
    other = open(str(tmp_path / ferry.LOG_NAME), "rb")       # e.g. Notepad
    try:
        for i in range(70):
            log.event("%05d " % i + "q" * 1000)
        log.stop()
    finally:
        other.close()
    lines = read_log(tmp_path)
    got = [l.split("\t")[9][:5] for l in lines if "qqq" in l]
    assert got == ["%05d" % i for i in range(70)]
    if ferry.IS_WIN:
        assert not (tmp_path / ferry.LOG_OLD_NAME).exists()
    else:
        assert (tmp_path / ferry.LOG_OLD_NAME).exists()


def test_rotation_checked_at_start(tmp_path):
    (tmp_path / ferry.LOG_NAME).write_text("old line\n" * 10_000, encoding="utf-8")
    log = ferry.RequestLog(str(tmp_path), Notes(), max_bytes=50_000, rotate_delays=(0, 0, 0))
    log.start()
    log.event("fresh")
    log.stop()
    assert (tmp_path / ferry.LOG_OLD_NAME).exists()
    assert "old line" not in (tmp_path / ferry.LOG_NAME).read_text(encoding="utf-8")


def test_device_name_percent_decoded(srv):
    h = {ferry.DEVICE_HEADER: "Saurabh%E2%80%99s%20phone%20%F0%9F%93%B1"}
    upload(srv, "a.jpg", b"abc", headers=h)
    lines = await_log(srv)
    assert fields(lines_with(lines, "/api/v1/upload")[0])[2] == "Saurabh’s phone \U0001F4F1"
    out = srv.log()
    assert "from Saurabh’s phone \U0001F4F1" in out
    assert "Saurabh’s phone \U0001F4F1 connected (127.0.0.1)" in out


def test_device_header_sanitised():
    class H(dict):
        pass
    lab = ferry.device_label
    assert lab(H({ferry.DEVICE_HEADER: "a%09b%0Ac%00d%C2%85e"}), "") == "a b c d e"
    assert lab(H({ferry.DEVICE_HEADER: "x" * 200}), "") == "x" * 60
    assert lab(H({ferry.DEVICE_HEADER: "bad%FFescape"}), "") == "bad�escape"
    assert lab(H({ferry.DEVICE_HEADER: "%20%20"}), "Mozilla (Linux; Android 14)") == "phone"
    assert lab(H(), "Mozilla/5.0 (Linux; Android 9; AFTMM) Silk") == "Fire TV"
    assert lab(H(), "curl/8") == "browser"


def test_start_stop_events(tmp_path):
    from helpers import start_ferry_process
    p = start_ferry_process(tmp_path, "--port", "0", "--discovery-port", "off")
    try:
        lines = await_log(p.port, p.log_path)
        assert "Ferry 2.0.0 started (port %d" % p.port in lines[0]
    finally:
        p.close()


def test_stop_event_written_by_main(tmp_path, monkeypatch):
    stop = threading.Event()
    monkeypatch.setattr(ferry, "detect_addresses", lambda: ("127.0.0.1", []))
    args = ["--port", "0", "--no-open", "--discovery-port", "off", "--inbox", str(tmp_path / "i"),
            "--outbox", str(tmp_path / "o"), "--data-dir", str(tmp_path / "d")]
    monkeypatch.setattr(ferry, "DATA_DIR", ferry.DATA_DIR)       # main() sets it; restore afterwards
    t = threading.Thread(target=ferry.main, args=(args,), kwargs={"stop_event": stop})
    t.start()
    assert wait_for(lambda: (tmp_path / "d" / ferry.LOG_NAME).exists())
    stop.set()
    t.join(5)
    text = (tmp_path / "d" / ferry.LOG_NAME).read_text(encoding="utf-8")
    assert "Ferry 2.0.0 started" in text and "Ferry stopped" in text
