"""2.1 install routes (B4, M11) and /guide (A3)."""
from helpers import write_app

FIRE_UA = "Mozilla/5.0 (Linux; Android 9; AFTSSS Build/PS7633; wv) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"
PHONE_UA = "Mozilla/5.0 (Linux; Android 14; V2413) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"


def assert_redirect(r, d):
    assert r.status == 302 and d == b""
    assert r.getheader("Location") == "/app/Ferry.apk"            # relative
    assert r.getheader("Content-Length") == "0"


def test_tv_redirect_get_and_head(srv):
    for m in ("GET", "HEAD"):
        r, d = srv.req(m, "/tv")
        assert_redirect(r, d)
    r, _ = srv.req("POST", "/tv", body=b"", headers={"Content-Length": "0"})
    assert r.status == 405


def test_fire_tv_ua_on_root_redirects(srv):
    for m in ("GET", "HEAD"):
        r, d = srv.req(m, "/", headers={"User-Agent": FIRE_UA})
        assert_redirect(r, d)
    for ua in (DESKTOP_UA, PHONE_UA, "Mozilla/5.0 (Linux; Android 9; SAFTY)"):
        r, d = srv.req("GET", "/", headers={"User-Agent": ua})
        assert r.status == 200 and b"Get the Ferry app" in d, ua


def test_app_still_the_apk(srv):
    write_app(srv.app_dir, b"PK\x03\x04 apk bytes")
    for p in ("/app", "/app/Ferry.apk"):
        r, d = srv.req("GET", p, headers={"User-Agent": FIRE_UA})
        assert r.status == 200 and d == b"PK\x03\x04 apk bytes", p
    r, d = srv.req("GET", "/tv")
    assert r.status == 302


def test_guide(srv, tmp_path):
    srv.app.guide_path = str(tmp_path / "Ferry-Guide.html")
    r, d = srv.req("GET", "/guide")
    assert r.status == 404 and r.getheader("Content-Type").startswith("text/html") and b"Ferry-Guide.html" in d
    (tmp_path / "Ferry-Guide.html").write_text("<html>guide</html>", encoding="utf-8")
    r, d = srv.req("GET", "/guide")
    assert r.status == 200 and d == b"<html>guide</html>"
    assert r.getheader("Content-Type") == "text/html; charset=utf-8"
