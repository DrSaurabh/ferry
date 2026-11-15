"""POST /api/v1/check-batch and Inbox.check_batch (design §2.3, plan §2.7)."""
import http.client
import json
import os
import random
import time

import pytest

import ferry

T = 1700000000000          # ms


def put(folder, name, data=b"12345", mtime_ms=T):
    p = os.path.join(str(folder), name)
    with open(p, "wb") as f:
        f.write(data)
    os.utime(p, (mtime_ms / 1000.0, mtime_ms / 1000.0))
    return p


def batch(srv, files, raw=None, headers=None):
    body = raw if raw is not None else json.dumps({"files": files}).encode("utf-8")
    h = {"Content-Type": "application/json"}
    if headers is None:
        h["Content-Length"] = str(len(body))
    else:
        h.update(headers)
    r, d = srv.req("POST", "/api/v1/check-batch", body=body, headers=h)
    return r.status, json.loads(d.decode("utf-8"))


def test_results_in_input_order(srv):
    put(srv.inbox, "a.jpg")
    put(srv.inbox, "c.jpg", b"123")
    files = [{"name": "x.jpg", "size": 5, "mtime": T}, {"name": "a.jpg", "size": 5, "mtime": T},
             {"name": "b.jpg", "size": 5, "mtime": T}, {"name": "c.jpg", "size": 3, "mtime": T},
             {"name": "a.jpg", "size": 6, "mtime": T}]
    st, j = batch(srv, files)
    assert st == 200 and j["ok"] is True
    assert [r["duplicate"] for r in j["results"]] == [None, "a.jpg", None, "c.jpg", None]


def test_duplicate_needs_size_and_mtime_within_2s(srv):
    put(srv.inbox, "a.jpg")
    files = [{"name": "a.jpg", "size": 5, "mtime": T + 2000}, {"name": "a.jpg", "size": 5, "mtime": T - 2000},
             {"name": "a.jpg", "size": 5, "mtime": T + 3000}, {"name": "a.jpg", "size": 4, "mtime": T},
             {"name": "a.jpg", "size": 5}]
    st, j = batch(srv, files)
    assert [r["duplicate"] for r in j["results"]] == ["a.jpg", "a.jpg", None, None, None]


def test_numbered_family_member_found(srv):
    put(srv.inbox, "a.jpg", b"other content")
    put(srv.inbox, "a (1).jpg")
    st, j = batch(srv, [{"name": "a.jpg", "size": 5, "mtime": T}])
    assert j["results"][0]["duplicate"] == "a (1).jpg"


def test_needed_bytes_excludes_duplicates(srv):
    put(srv.inbox, "a.jpg")
    st, j = batch(srv, [{"name": "a.jpg", "size": 5, "mtime": T}, {"name": "b.jpg", "size": 70, "mtime": T},
                        {"name": "c.jpg", "size": 30}])
    assert j["needed_bytes"] == 100
    st, j = batch(srv, [{"name": "a.jpg", "size": 5, "mtime": T}] * 3)
    assert j["needed_bytes"] == 0


def test_never_refuses_for_space(make_srv):
    s = make_srv(free_override=1)
    st, j = batch(s, [{"name": "big.mp4", "size": 10 << 30, "mtime": T}])
    assert st == 200 and j["ok"] is True and j["free_bytes"] == 0 and j["needed_bytes"] == 10 << 30
    s2_free = (1 << 30)
    s.app.inbox.free_override = s2_free
    st, j = batch(s, [])
    assert j["free_bytes"] == s2_free - ferry.DISK_MARGIN and j["results"] == []


TRAPS = ["İstanbul.jpg", "istanbul.jpg", "i̇stanbul.jpg", "ıstanbul.jpg", "Istanbul.jpg", "Σίσυφος.jpg",
         "ΣΊΣΥΦΟΣ.jpg", "σίσυφοσ.jpg", "ſun.jpg", "sun.jpg", "SUN.jpg", "Kite.jpg", "kite.jpg", "KITE.JPG",
         "a (٣).jpg", "a.jpg", "a (3).jpg", "A (3).JPG", "a (1) (2).jpg", "a (1).jpg", "name (3).jpg",
         "name.jpg", "NAME.JPG", "con.txt", "CON.txt", "_con.txt", "aux", "x.tar.gz", "x (2).tar.gz",
         "noext", "noext (4)", "dots.in.name.png", "dots.in.name (1).png", "naïve.jpg", "naïve.jpg",
         "ñ.jpg", "Ñ.jpg", "ß.jpg", "SS.jpg", "ss.jpg", "ﬁle.jpg", "file.jpg", "FILE.jpg", "x" * 30 + ".verylongextension_over_20"]


def gen_names(rng):
    names = list(TRAPS)
    stems = ["IMG_2024", "photo", "Ünïcødé", "file", "ka", "ski", "a b", "ı", "K"]
    exts = [".jpg", ".JPG", ".mp4", "", ".Png"]
    while len(names) < 200:
        s, e = rng.choice(stems), rng.choice(exts)
        if rng.random() < 0.3:
            s = s.upper() if rng.random() < 0.5 else s.lower()
        if rng.random() < 0.4:
            s += " (%d)" % rng.randint(1, 12)
        names.append(s + e)
    return names


@pytest.mark.parametrize("is_win", [True, False])
def test_same_answers_as_precheck(tmp_path, is_win):
    rng = random.Random(1234 + is_win)
    names = gen_names(rng)
    inbox = tmp_path / "in"
    inbox.mkdir()
    ib = ferry.Inbox(str(inbox), is_win=is_win, free_override=1 << 40)
    for i, n in enumerate(names):          # put about half of them in the inbox, with two sizes
        if rng.random() < 0.5:             # (case variants may coexist on Linux: more family members)
            try:
                put(inbox, ferry.sanitize_name(n, str(inbox), is_win), b"x" * (5 + (i % 2)),
                    T + rng.choice([0, 1000, 2500]))
            except OSError:
                pass
    items = [(n, s, m) for n in names for s in (5, 6) for m in (T, None)]
    got = ib.check_batch(items)["results"]
    for (n, s, m), r in zip(items, got):
        want = ib.precheck(n, s, m).get("duplicate")
        assert r["duplicate"] == want, (n, s, m, os.listdir(str(inbox)))
    assert any(r["duplicate"] for r in got)


def test_one_scan_one_disk_usage(tmp_path, monkeypatch):
    inbox = tmp_path / "in"
    inbox.mkdir()
    for i in range(50):
        put(inbox, "IMG_%04d.jpg" % i)
    ib = ferry.Inbox(str(inbox), is_win=False)
    calls = {"scandir": 0, "free": 0}
    real_scandir, real_free = os.scandir, ib.free_bytes

    def scandir(*a, **k):
        calls["scandir"] += 1
        return real_scandir(*a, **k)

    def free():
        calls["free"] += 1
        return real_free()
    monkeypatch.setattr(os, "scandir", scandir)
    monkeypatch.setattr(ib, "free_bytes", free)
    res = ib.check_batch([("IMG_%04d.jpg" % (i % 80), 5, T) for i in range(500)])
    assert calls == {"scandir": 1, "free": 1}
    assert sum(1 for r in res["results"] if r["duplicate"]) == sum(1 for i in range(500) if i % 80 < 50)


def test_10k_inbox_time_budget(tmp_path):
    inbox = tmp_path / "in"
    inbox.mkdir()
    for i in range(10000):
        with open(os.path.join(str(inbox), "IMG_%05d.jpg" % i), "wb"):
            pass
    ib = ferry.Inbox(str(inbox), is_win=True)
    items = [("img_%05d.JPG" % (i * 5), 0, T) for i in range(2000)]
    t0 = time.time()
    ib.check_batch(items)
    dt = time.time() - t0
    assert dt < (15.0 if os.environ.get("CI") else 3.0), dt


def test_limits_and_errors(srv):
    st, j = batch(srv, [{"name": "a", "size": 1}] * 2001)
    assert st == 400
    st, j = batch(srv, [], raw=b"{" + b" " * (ferry.CHECK_BATCH_MAX_BODY + 10) + b"}")
    assert st == 413
    for raw in (b"not json", b"[]", b'{"files": 3}', b'{"files": [{"name": "", "size": 1}]}',
                b'{"files": [{"name": "a", "size": -1}]}', b'{"files": [{"name": "a", "size": "5"}]}',
                b'{"files": [{"name": "a", "size": 5, "mtime": "x"}]}', b'{"files": [{"name": "a", "size": true}]}',
                b'{"files": ["a"]}', b"\xff\xfe"):
        st, j = batch(srv, [], raw=raw)
        assert st == 400, raw
    r, d = srv.req("GET", "/api/v1/check-batch")
    assert r.status == 405
    c = http.client.HTTPConnection("127.0.0.1", srv.port, timeout=5)
    c.putrequest("POST", "/api/v1/check-batch")
    c.endheaders()
    r = c.getresponse()
    assert r.status == 411
    c.close()


def test_console_one_line(srv):
    put(srv.inbox, "a.jpg")
    batch(srv, [{"name": "a.jpg", "size": 5, "mtime": T}] + [{"name": "n%d.jpg" % i, "size": 1} for i in range(19)])
    lines = [l for l in srv.log().splitlines() if "checked" in l]
    assert len(lines) == 1 and "20 files" in lines[0] and "1 already on PC" in lines[0]
    assert not any("skipped" in l for l in srv.out.getvalue().splitlines())
