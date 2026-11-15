"""v1 browser leftovers are gone [B25] [H9]."""
import json
import os

import ferry
from helpers import ROOT


def test_removed_routes_404(srv):
    srv.add_out("a.txt", b"abc")                 # /1 would have redirected to it in v1
    for p in ("/diag", "/qr", "/new", "/1", "/1/", "/index.html", "/manifest.webmanifest", "/icon-192.png",
              "/icon-512.png", "/api/v1/diag/blob", "/api/v1/diag/test.zip", "/api/v1/diag/ferry-test.apk"):
        r, d = srv.req("GET", p)
        assert r.status == 404, p
        if p.startswith("/api/"):
            assert r.getheader("Content-Type").startswith("application/json"), p
            assert json.loads(d.decode("utf-8"))["result"] == "error"
        else:
            assert r.getheader("Content-Type").startswith("text/html"), p
    for p in ("/api/v1/diag/sink", "/api/v1/diag/inspect"):
        r, d = srv.req("POST", p, body=b"xyz", headers={"Content-Length": "3"})
        assert r.status == 405 and json.loads(d.decode("utf-8"))["result"] == "error", p


def test_v1_page_code_gone():
    for name in ("PAGE_HTML", "DIAG_HTML", "QR_PAGE", "ASSETS_B64", "build_assets", "render_page", "qr_svg",
                 "RX_SHORT"):
        assert not hasattr(ferry, name), name
    for name in ("page", "diag_page", "qr_page", "asset", "redirect_id", "redirect_new", "diag_blob", "diag_sink"):
        assert not hasattr(ferry.FerryHandler, name), name
    src = open(os.path.join(ROOT, "ferry.py"), encoding="utf-8").read()
    assert "BEGIN GENERATED" not in src and "FAVICON_SVG" in src
    for f in ("tools/page.html", "tools/diag.html", "tools/keepawake.mp4", "tools/icon-maskable.svg",
              "tools/build_assets.py", "tests/js", "tests/e2e"):
        assert not os.path.exists(os.path.join(ROOT, f)), f
