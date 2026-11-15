"""GET / : the Get-the-app page (C4, plan §2.6)."""
import socket

from helpers import write_app

APK = b"PK\x03\x04" + b"x" * 24_100_000


def raw_get(port, request):
    s = socket.create_connection(("127.0.0.1", port), timeout=10)
    s.sendall(request)
    data = b""
    while True:
        b = s.recv(65536)
        if not b:
            break
        data += b
    s.close()
    return data


def test_page_content(srv):
    write_app(srv.app_dir, APK, code=57, name="2.0.57")
    r, d = srv.req("GET", "/", headers={"Host": "192.168.1.23:8765"})
    text = d.decode("utf-8")
    assert r.status == 200 and r.getheader("Content-Type") == "text/html; charset=utf-8"
    assert r.getheader("Cache-Control") == "no-store"
    assert "<title>Get the Ferry app</title>" in text
    assert 'href="/app"' in text and "Download Ferry for Android" in text
    assert "Ferry 2.0.57 &middot; 24.1 MB" in text
    assert "In the Downloader app, enter <code>192.168.1.23:8765/app</code>" in text
    assert "allow your browser to install apps" in text and "<script" not in text


def test_host_header_escaped(srv):
    r, d = srv.req("GET", "/", headers={"Host": "<b>x"})
    assert b"<b>x" not in d
    assert ("<code>127.0.0.1:%d/app</code>" % srv.port).encode() in d          # fallback: primary:port
    resp = raw_get(srv.port, b"GET / HTTP/1.0\r\n\r\n")                         # no Host header at all
    assert ("127.0.0.1:%d/app" % srv.port).encode() in resp


def test_head(srv):
    r, d = srv.req("HEAD", "/")
    assert r.status == 200 and d == b"" and int(r.getheader("Content-Length")) > 500
