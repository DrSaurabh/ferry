"""UDP discovery responder (C2, design §2.4, plan §2.5)."""
import json
import socket
import time

import pytest

import ferry


class Notes:
    def __init__(self):
        self.lines = []

    def note(self, text):
        self.lines.append(text)

    def error_once(self, text):
        self.lines.append(text)


class Events:
    def __init__(self):
        self.lines = []

    def event(self, text, ip=None, device=None):
        self.lines.append(text)


def responder(port=0, **kw):
    r = ferry.DiscoveryResponder(port, lambda: {"name": "Saurabhs-PC", "port": 8765}, kw.pop("console", Notes()),
                                 kw.pop("reqlog", Events()), **kw)
    return r


def req(nonce="0123abcd", **extra):
    d = {"app": "ferry", "q": "discover", "protocol": 2, "name": "vivo X200 Pro", "device_type": "phone",
         "nonce": nonce}
    d.update(extra)
    return json.dumps(d).encode("utf-8")


def client():
    c = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    c.settimeout(0.3)
    return c


def ask(c, port, data):
    c.sendto(data, ("127.0.0.1", port))
    try:
        return c.recvfrom(4096)[0]
    except socket.timeout:
        return None


@pytest.fixture
def running():
    r = responder()
    assert r.start()
    yield r
    r.stop()


def test_reply_on_loopback(running):
    c = client()
    c.settimeout(2)
    reply = json.loads(ask(c, running.bound_port, req("deadbeef")).decode("utf-8"))
    assert reply == {"app": "ferry", "protocol": 2, "nonce": "deadbeef", "name": "Saurabhs-PC",
                     "device_type": "pc", "port": 8765, "version": ferry.VERSION}
    c.close()


def test_garbage_ignored(running):
    c = client()
    bad = [b"not json", b"\xff\xfe", b"[1,2]", req(app="other"), req(q="hello"), req(nonce="xyz"),
           req(nonce="0123"), req(nonce="0" * 33), json.dumps({"app": "ferry", "q": "discover"}).encode(),
           req(name="x" * 600)]
    for b in bad:
        assert ask(c, running.bound_port, b) is None, b[:40]
    c.settimeout(2)
    assert ask(c, running.bound_port, req()) is not None
    c.close()


def test_rate_limit(running):
    c = client()
    for i in range(40):
        c.sendto(req("%08x" % i), ("127.0.0.1", running.bound_port))
    got = 0
    c.settimeout(0.5)
    try:
        while True:
            c.recvfrom(4096)
            got += 1
    except socket.timeout:
        pass
    assert 1 <= got <= ferry.DISCOVERY_MAX_REPLIES_PER_S
    c.close()


class FakeSock:
    def __init__(self, script):
        self.script = list(script)
        self.sent = []
        self.closed = False

    def settimeout(self, t):
        pass

    def bind(self, addr):
        pass

    def getsockname(self):
        return ("0.0.0.0", 9999)

    def recvfrom(self, n):
        if self.script:
            item = self.script.pop(0)
            if isinstance(item, BaseException):
                raise item
            return item
        time.sleep(0.05)
        raise socket.timeout()

    def sendto(self, data, addr):
        self.sent.append((data, addr))

    def close(self):
        self.closed = True


def test_loop_survives_connection_reset():
    fake = FakeSock([ConnectionResetError(10054, "reset"), OSError("boom"), (req("abcdef01"), ("10.0.0.7", 5000))])
    r = responder(sock_factory=lambda: fake)
    assert r.start()
    deadline = time.time() + 3
    while time.time() < deadline and not fake.sent:
        time.sleep(0.02)
    assert fake.sent and json.loads(fake.sent[0][0])["nonce"] == "abcdef01" and fake.sent[0][1] == ("10.0.0.7", 5000)
    assert r.t.is_alive()
    r.stop()
    assert fake.closed


def test_bind_failure_tolerated(srv):
    blocker = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    blocker.bind(("0.0.0.0", 0))                 # the same address the responder uses [PB19]
    port = blocker.getsockname()[1]
    try:
        r = ferry.DiscoveryResponder(port, lambda: {"name": "PC", "port": srv.port}, srv.console, srv.reqlog,
                                     address_hint=lambda: "192.168.1.5:8765")
        assert r.start() is False
        assert "Automatic discovery is off. On the phone, enter 192.168.1.5:8765 once" in srv.log()
        st, j = srv.json("GET", "/api/v1/info")
        assert st == 200
    finally:
        blocker.close()


def test_udp_port_independent_of_tcp_port():
    assert ferry.parse_args(["--port", "8800"]).discovery_port == 8765
    assert ferry.parse_args(["--discovery-port", "off"]).discovery_port is None
    assert ferry.parse_args(["--discovery-port", "0"]).discovery_port == 0
    r = ferry.DiscoveryResponder(0, lambda: {"name": "PC", "port": 8800}, Notes(), Events())
    assert json.loads(r.handle(req(), ("1.2.3.4", 5)))["port"] == 8800


def test_stop_within_a_second(running):
    t0 = time.time()
    running.stop()
    assert time.time() - t0 < 1.0 and not running.t.is_alive()


def test_reply_logged_once_per_minute():
    now = [1000.0]
    events = Events()
    script = [(req("%08x" % i), ("10.0.0.%d" % (1 + i % 2), 4000)) for i in range(6)]
    fake = FakeSock(script)
    r = responder(sock_factory=lambda: fake, clock=lambda: now[0], reqlog=events)
    r.start()
    deadline = time.time() + 3
    while time.time() < deadline and len(fake.sent) < 6:
        time.sleep(0.02)
    assert len(fake.sent) == 6
    assert events.lines == ["discovery reply to 10.0.0.1 (vivo X200 Pro, phone)",
                            "discovery reply to 10.0.0.2 (vivo X200 Pro, phone)"]
    now[0] += 61
    fake.script.append((req("ffffffff"), ("10.0.0.1", 4000)))
    deadline = time.time() + 3
    while time.time() < deadline and len(fake.sent) < 7:
        time.sleep(0.02)
    assert len(events.lines) == 3
    r.stop()
