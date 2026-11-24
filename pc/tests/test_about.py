"""2.1 (A2): the About line in the PC window header and the /app page footer."""
import ferry

TEXT = "Ferry 2.1.0 · © 2026 Saurabh Sawhney · MIT Licence · Built with Claude (Anthropic)"


def test_about_text():
    assert ferry.ABOUT_LINE == TEXT
    assert "Nayuki QR code generator (MIT)" in ferry.NOTICES_LINE and "Apache 2.0" in ferry.NOTICES_LINE


def test_window_header():
    cfg = ferry.Config(port=8765, inbox="i", outbox="o", inbox_label="a", outbox_label="b")
    lines = ferry.build_banner(cfg, "http://192.168.1.2:8765/", [], 0, "App: x")
    assert lines[1] == "  " + TEXT


def test_page_footer(srv):
    r, d = srv.req("GET", "/")
    text = d.decode("utf-8")
    assert r.status == 200
    assert "<footer><p>Ferry 2.1.0 · © 2026 Saurabh Sawhney · MIT Licence · Built with Claude (Anthropic)</p>" in text
    assert "Nayuki QR code generator (MIT)" in text
