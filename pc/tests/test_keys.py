"""2.1 (L7, U6, M12): console keys A and Z through a fake msvcrt (the real kbhit is never called)."""
import threading

import ferry


class FakeMsvcrt:
    def __init__(self, keys):
        self.keys = list(keys)

    def kbhit(self):
        return bool(self.keys)

    def getwch(self):
        return self.keys.pop(0)


def test_a_and_z():
    calls = []
    z = threading.Event()
    kl = ferry.KeyListener(lambda: calls.append("a"), lambda: (calls.append("z"), z.set()),
                           msvcrt_mod=FakeMsvcrt(["A", "x", "\xe0", "H", "z"]))
    assert kl.poll_once() == "a" and calls == ["a"]
    assert kl.poll_once() is None                    # other keys do nothing
    assert kl.poll_once() is None and kl.m.keys == ["z"]   # arrow key: both codes eaten
    assert kl.poll_once() == "z"
    assert z.wait(2) and calls == ["a", "z"]         # Z runs on a worker thread
    assert kl.poll_once() is None


def test_thread_polls_and_stops():
    got = threading.Event()
    kl = ferry.KeyListener(got.set, lambda: None, msvcrt_mod=FakeMsvcrt(["a"]), interval=0.01)
    assert kl.start()
    assert got.wait(2)
    kl.stop()
    kl.t.join(2)
    assert not kl.t.is_alive()


def test_available_only_on_windows_console():
    class Tty:
        def __init__(self, v):
            self.v = v

        def isatty(self):
            return self.v
    assert ferry.KeyListener.available(True, Tty(True))
    assert not ferry.KeyListener.available(True, Tty(False))
    assert not ferry.KeyListener.available(False, Tty(True))


def test_key_a_shows_install_lines():
    import io
    out = io.StringIO()
    c = ferry.Console(stream=out)
    ferry.show_install(c, "http://192.168.1.2:8765/")
    c.stop()
    text = out.getvalue()
    assert "Scan to install" in text and "in Downloader enter http://192.168.1.2:8765/tv" in text
