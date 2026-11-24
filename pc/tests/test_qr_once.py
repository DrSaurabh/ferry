"""2.1 (U6, M9, M25-C6): the QR shows until the first Ferry phone app connected from the network."""
import ferry

PHONE_UA = "okhttp/4.12.0"


class H(dict):
    def get(self, k, d=None):
        return dict.get(self, k, d)


def app_with_state(tmp_path):
    state = ferry.State.load(str(tmp_path / "ferry-state.json"))
    return ferry.AppContext(state=state), state


def test_loopback_does_not_count(srv, tmp_path):
    srv.app.state = ferry.State.load(str(tmp_path / "s.json"))
    srv.req("GET", "/api/v1/info", headers={ferry.DEVICE_HEADER: "vivo", "User-Agent": PHONE_UA})
    assert srv.app.state.data["first_phone_seen"] is False


def test_phone_from_network_sets_and_persists(tmp_path):
    app, state = app_with_state(tmp_path)
    assert ferry.note_first_phone(app, "10.0.0.5", H({ferry.DEVICE_HEADER: "vivo"}), PHONE_UA)
    assert ferry.State.load(state.path).data["first_phone_seen"] is True
    assert not ferry.note_first_phone(app, "10.0.0.5", H({ferry.DEVICE_HEADER: "vivo"}), PHONE_UA)   # once


def test_tv_browser_and_loopback_do_not_count(tmp_path):
    app, state = app_with_state(tmp_path)
    cases = [("10.0.0.6", H({ferry.DEVICE_HEADER: "Fire TV", ferry.DEVICE_TYPE_HEADER: "tv"}), PHONE_UA),
             ("10.0.0.6", H({ferry.DEVICE_HEADER: "x"}), "Mozilla/5.0 (Linux; Android 9; AFTSSS) Silk"),
             ("10.0.0.7", H({}), PHONE_UA),                                   # a browser: no device header
             ("127.0.0.1", H({ferry.DEVICE_HEADER: "vivo"}), PHONE_UA),
             ("::1", H({ferry.DEVICE_HEADER: "vivo"}), PHONE_UA),
             ("::ffff:127.0.0.1", H({ferry.DEVICE_HEADER: "vivo"}), PHONE_UA),
             ("garbage", H({ferry.DEVICE_HEADER: "vivo"}), PHONE_UA)]
    for ip, h, ua in cases:
        assert not ferry.note_first_phone(app, ip, h, ua), (ip, h, ua)
    assert state.data["first_phone_seen"] is False


def test_banner_after_first_phone():
    cfg = ferry.Config(port=8765, inbox="i", outbox="o", inbox_label="Downloads/Phone", outbox_label="Desktop/Ferry outbox")
    url = "http://192.168.1.2:8765/"
    qr = ferry.qr_console_lines(url, False)
    first = ferry.build_banner(cfg, url, qr, 0, "App: x", show_qr=True, keys=True)
    later = ferry.build_banner(cfg, url, qr, 0, "App: x", show_qr=False, keys=True)
    assert any("Scan to install" in l for l in first) and not any("█" in l for l in later)
    assert "  To add a phone or TV press A" in later
    assert "  Fire TV:   in Downloader enter http://192.168.1.2:8765/tv" in later
    assert "  App page:  http://192.168.1.2:8765/app" in later
    no_keys = ferry.build_banner(cfg, url, qr, 0, "App: x", show_qr=False, keys=False)
    assert not any("press A" in l for l in no_keys) and not any("zip logs" in l for l in no_keys)
    assert any("Z = zip logs for Claude" in l for l in later)
