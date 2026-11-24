import io
import math
import time

import ferry
from ferry import Config, build_banner, build_extras, qr_console_lines

APP_LINE = ferry.app_banner_line("ok", {"version_name": "2.0.57"})


def cfg(**kw):
    base = dict(port=8765, inbox="/home/u/Downloads/Phone", outbox="/home/u/Desktop/Ferry outbox",
                inbox_label="Downloads/Phone", outbox_label="Desktop/Ferry outbox")
    base.update(kw)
    return Config(**base)


def matrix_from_lines(lines, ansi):
    rows = []
    for line in lines:
        s = line.strip("\n")[6:]
        if ansi:
            s = s.replace("\x1b[30;107m", "").replace("\x1b[0m", "")
        top, bot = [], []
        for ch in s:
            t, b = {"█": (1, 1), "▀": (1, 0), "▄": (0, 1), " ": (0, 0)}[ch]
            top.append(t)
            bot.append(b)
        rows += [top, bot]
    return rows


def test_qr_rows_and_matrix_roundtrip():
    url = "http://192.168.1.23:8765"
    m = ferry.qr_matrix(url)          # must be the exact (lower-case) URL - cameras ignore "HTTP://"
    n = len(m)
    for ansi in (True, False):
        lines = qr_console_lines(url, ansi)
        assert len(lines) == math.ceil((n + 4) / 2)
        widths = {len(l) for l in lines}
        assert len(widths) == 1
        got = matrix_from_lines(lines, ansi)
        for y in range(n):
            for x in range(n):
                v = got[y + 2][x + 2]
                if not ansi:
                    v = 1 - v
                assert bool(v) == m[y][x]
        # quiet zone is light
        q = got[0][0] if ansi else 1 - got[0][0]
        assert q == 0


def test_qr_size_for_long_ip():
    assert len(qr_console_lines("http://192.168.100.200:8765/", True)) == 15


def test_qr_missing_library(monkeypatch):
    monkeypatch.setattr(ferry, "qrcodegen", None)
    assert qr_console_lines("http://x", True) == []
    lines = build_banner(cfg(), "http://192.168.1.2:8765/", [], 0, APP_LINE)
    assert any("qrcodegen.py is missing" in l for l in lines)


def test_banner_budget():
    url = "http://192.168.100.200:8765/"
    lines = build_banner(cfg(), url, qr_console_lines(url, True), 3, APP_LINE)
    assert len(lines) <= 24      # v1: 22; v2 adds the Fire TV and app lines (B13); 2.1 adds the About line (A2)
    text = "\n".join(lines)
    assert "First time? Scan to install the Ferry app:" in text
    assert "  App page:  http://192.168.100.200:8765/app" in lines
    assert "  Fire TV:   in Downloader enter http://192.168.100.200:8765/tv" in lines     # 2.1: /tv (B4, M11)
    assert "  " + APP_LINE in lines and max(len(l) for l in lines if "█" not in l) <= 88
    # realistic worst case: first run (so no "address changed"), OneDrive desktop, non-192.168 primary
    extras = build_extras(cfg(first_run=True, onedrive_outbox=True), url, ["10.0.0.5"], None, "10.0.0.9", 8765)
    assert len(lines) + len(extras) + 1 <= 30   # +1 "Waiting for your phone..."
    later = build_extras(cfg(onedrive_outbox=True), url, ["10.0.0.5"], "192.168.1.1", "10.0.0.9", 8765)
    assert len(lines) + len(later) + 1 <= 30
    assert "Outbox: Desktop/Ferry outbox (3 files)" in "\n".join(lines)


def test_banner_glyphs_safe():
    allowed = set(" █▀▄─→↑↓=+-…·©") | set(chr(c) for c in range(32, 127))     # 2.1: · © of the About line (A2)
    url = "http://192.168.1.2:8765/"
    text = "\n".join(build_banner(cfg(), url, qr_console_lines(url, False), 1, APP_LINE) +
                     build_banner(cfg(), url, [], 1, ferry.app_banner_line("missing", None)) +
                     build_extras(cfg(first_run=True, onedrive_outbox=True), url, ["10.1.1.1"], "1.1.1.1", "10.0.0.2", 8765))
    assert set(text) - {"\n"} <= allowed, set(text) - allowed


def test_ip_changed_notice():
    ex = build_extras(cfg(), "u", [], "192.168.1.5", "192.168.1.9", 8765)
    assert any("address changed (was 192.168.1.5)" in l for l in ex)
    assert "The Ferry app finds it by itself" in "\n".join(ex) and "enter 192.168.1.9 in Ferry > Settings" in ex[1]
    assert not any("Bookmark" in l for l in build_extras(cfg(first_run=True), "u", [], None, "192.168.1.9", 8765))
    assert build_extras(cfg(), "u", [], "192.168.1.9", "192.168.1.9", 8765) == []


def test_console_queue_and_log_format():
    out = io.StringIO()
    c = ferry.Console(stream=out)
    c.log("from phone", "saved", "x" * 80, "4.2 MB")
    c.print("a\nb")
    c.stop()
    lines = out.getvalue().splitlines()
    assert lines[0].startswith("  [") and "from phone    saved" in lines[0] and "…" in lines[0]
    assert lines[1:] == ["a", "b"]


def test_console_never_blocks_writer():
    class Stuck:
        def write(self, s):
            time.sleep(5)

        def flush(self):
            pass
    c = ferry.Console(stream=Stuck())
    t = time.time()
    for _ in range(100):
        c.log("from phone", "saved", "f")
    assert time.time() - t < 0.5


def test_rank_addresses():
    r = ferry.rank_addresses({"10.8.0.2", "192.168.1.23", "172.20.0.1", "127.0.0.1", "169.254.1.1", "8.8.8.8"}, "10.8.0.2")
    assert r == ["10.8.0.2", "192.168.1.23", "172.20.0.1"]
    r = ferry.rank_addresses({"10.8.0.2", "192.168.1.23", "172.20.0.1"}, None)
    assert r[0] == "192.168.1.23"
    assert ferry.rank_addresses(set(), None) == []


def test_state_roundtrip(tmp_path):
    p = str(tmp_path / "s.json")
    s = ferry.State.load(p)
    assert s.data == {"last_ip": None, "first_run_done": False, "first_phone_seen": False}   # 2.1 adds M9
    s.data["last_ip"] = "1.2.3.4"
    s.save()
    assert ferry.State.load(p).data["last_ip"] == "1.2.3.4"
    (tmp_path / "s.json").write_text("{broken")
    assert ferry.State.load(p).data["last_ip"] is None


def test_qr_encodes_exact_lowercase_url():
    """Field bug (vivo camera): an upper-case 'HTTP://' QR was treated as text and sent to Google search."""
    url = ferry.page_url("192.168.1.5", 8765)
    assert url == "http://192.168.1.5:8765/"          # the QR opens the Get-the-app page
    lines = qr_console_lines(url, True)
    got = matrix_from_lines(lines, True)
    want = ferry.qr_matrix(url)
    assert want != ferry.qr_matrix(url.upper())
    n = len(want)
    assert all(bool(got[y + 2][x + 2]) == want[y][x] for y in range(n) for x in range(n))
    assert len(lines) <= 15


def test_app_banner_lines():
    assert APP_LINE == "App: Ferry 2.0.57 is ready for your phone and TV"
    assert ferry.app_banner_line("missing", None).startswith("! No app in this folder (app")
    assert ferry.app_banner_line("invalid", None).endswith("get the full Ferry zip")
    assert "being copied" in ferry.app_banner_line("copying", None)


def test_no_connect_tip_at_start_tip_after_aborted_upload(srv):
    """2.1 (U6, H75): no "Phone can't connect?" at start; the tip comes once after a request broke off."""
    import socket as _s
    import time as _t
    assert not hasattr(srv.app.tracker, "start_watchdog")
    srv.req("GET", "/api/v1/info")
    assert "Phone can't connect?" not in srv.log()
    for _ in range(2):
        c = _s.create_connection(("127.0.0.1", srv.port), timeout=5)
        c.sendall(b"POST /api/v1/upload?name=x.jpg&size=1000 HTTP/1.0\r\nContent-Length: 1000\r\n\r\n" + b"x" * 10)
        c.close()
    deadline = _t.time() + 5
    while "Phone can't connect?" not in srv.out.getvalue() and _t.time() < deadline:
        _t.sleep(0.05)
    text = srv.log()
    assert text.count("Phone can't connect? Check") == 1 and "README.txt > Troubleshooting" in text
