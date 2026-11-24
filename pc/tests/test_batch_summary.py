"""2.1 (M10): one summary line per batch, by completions (not i == n), check-batch count, idle flush."""
import json
import threading
import time

import ferry
from helpers import upload

PHONE = {ferry.DEVICE_HEADER: "vivo%20V2413"}


def collect():
    got = []
    ev = threading.Event()

    def emit(text, ip, device):
        got.append((text, ip, device))
        ev.set()
    return got, ev, emit


def test_completions_not_index():
    got, _ev, emit = collect()
    b = ferry.BatchSummary(emit, idle_s=30)
    b.completed(("3f2a", 3, 3), "vivo", "1.2.3.4", "saved", 1000)      # the last index first: no summary yet
    assert got == []
    b.completed(("3f2a", 1, 3), "vivo", "1.2.3.4", "failed")
    b.completed(("3f2a", 1, 3), "vivo", "1.2.3.4", "saved", 2000)      # a retry replaces the failure
    assert got == []
    b.completed(("3f2a", 2, 3), "vivo", "1.2.3.4", "skipped")
    assert len(got) == 1
    text, ip, dev = got[0]
    assert text.startswith("batch 3f2a: 3 files · 2 saved · 1 already on PC · 3.0 KB in ")
    assert "failed" not in text and ip == "1.2.3.4" and dev == "vivo"
    b.close()


def test_check_batch_count_and_idle_flush():
    got, ev, emit = collect()
    b = ferry.BatchSummary(emit, idle_s=0.2)
    b.checked(("aa11", 0, 5), "tv", "1.2.3.5", 2)
    b.completed(("aa11", 1, 5), "tv", "1.2.3.5", "saved", 10)
    b.completed(("aa11", 2, 5), "tv", "1.2.3.5", "failed")
    assert ev.wait(3)
    text = got[0][0]
    assert "5 files · 1 saved · 2 already on PC · 1 failed · 1 not heard of" in text
    time.sleep(0.4)
    assert len(got) == 1
    b.close()


def test_all_already_on_pc_flushes_at_check():
    got, _ev, emit = collect()
    b = ferry.BatchSummary(emit, idle_s=30)
    b.checked(("bb22", 0, 2), "p", "ip", 2)
    assert len(got) == 1 and "2 files · 0 saved · 2 already on PC" in got[0][0]


def test_close_stops_timers():
    got, _ev, emit = collect()
    b = ferry.BatchSummary(emit, idle_s=0.1)
    b.completed(("cc33", 1, 9), "p", "ip", "saved", 1)
    b.close()
    time.sleep(0.3)
    assert got == []


def test_parse_batch():
    assert ferry.parse_batch("3f2a;1;105") == ("3f2a", 1, 105)
    for bad in (None, "", "3f2a;1", "3f2a;1;0", "x y;1;2", "3f2a;-1;2", "a;1;2;3"):
        assert ferry.parse_batch(bad) is None, bad


def test_through_http(srv):
    h = dict(PHONE, **{"X-Ferry-Batch": "9abc;0;3"})
    body = json.dumps({"files": [{"name": "x.jpg", "size": 1, "mtime": 1700000000000}, {"name": "y.jpg", "size": 2},
                                 {"name": "z.jpg", "size": 3}]}).encode()
    upload(srv, "x.jpg", b"1", mtime=1700000000000, headers=PHONE)                          # already there before the batch
    st, _ = srv.json("POST", "/api/v1/check-batch", body=body, headers=dict(h, **{"Content-Length": str(len(body))}))
    assert st == 200
    upload(srv, "y.jpg", b"22", headers=dict(PHONE, **{"X-Ferry-Batch": "9abc;2;3"}))
    upload(srv, "z.jpg", b"333", headers=dict(PHONE, **{"X-Ferry-Batch": "9abc;3;3"}))
    ev = [l.split("\t")[9] for l in srv.log_lines() if l.split("\t")[3] == "EVENT"]
    lines = [e for e in ev if e.startswith("batch 9abc:")]
    assert len(lines) == 1 and lines[0].startswith("batch 9abc: 3 files · 2 saved · 1 already on PC · 5 B in ")
