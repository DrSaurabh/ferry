"""Stage 7 code audit fixes on the PC (docs/v2-07-code-audit.md): S1, S2, S4."""
import os
from urllib.parse import quote

import ferry
from helpers import Srv


def put(folder, name, data):
    p = os.path.join(str(folder), name)
    with open(p, "wb") as f:
        f.write(data)
    return p


def test_outbox_url_name_must_match(make_srv):
    # S1: ids restart at 1 with every Ferry start, so the name in the URL must be the file's name.
    srv = make_srv()
    of = srv.add_out("a b+c é.jpg", b"AAAA")
    st, j = srv.json("GET", "/api/v1/outbox")
    f = j["files"][0]
    assert f["id"] == of.id
    assert f["url"] == "/api/v1/outbox/%d/%s" % (f["id"], quote("a b+c é.jpg", safe=""))
    r, d = srv.req("GET", f["url"])
    assert r.status == 200 and d == b"AAAA"
    r, d = srv.req("GET", f["url"] + "?inline=1")
    assert r.status == 200 and d == b"AAAA"
    r, d = srv.req("GET", "/api/v1/outbox/%d/other.jpg" % f["id"])
    assert r.status == 404
    r, d = srv.req("GET", "/api/v1/outbox/%d/../../../etc/passwd" % f["id"])
    assert r.status == 404
    r, d = srv.req("HEAD", "/api/v1/outbox/%d/other.jpg" % f["id"])
    assert r.status == 404
    # without a name part the id alone still works (nothing to compare)
    r, d = srv.req("GET", "/api/v1/outbox/%d" % f["id"])
    assert r.status == 200 and d == b"AAAA"


def test_stale_list_after_restart_gets_404_not_another_file(tmp_path):
    # S1 as the user would meet it: list, Ferry restarts with other files of the same size, download.
    s1 = Srv(tmp_path)
    s1.add_out("old.jpg", b"12345")
    st, j = s1.json("GET", "/api/v1/outbox")
    stale = j["files"][0]["url"]
    s1.close()
    os.unlink(os.path.join(str(s1.outbox), "old.jpg"))
    s2 = Srv(tmp_path)
    try:
        s2.add_out("new.jpg", b"abcde")
        st, j = s2.json("GET", "/api/v1/outbox")
        assert j["files"][0]["id"] == int(stale.split("/")[4])   # the same id now means another file
        r, d = s2.req("GET", stale)
        assert r.status == 404
        r, d = s2.req("GET", j["files"][0]["url"])
        assert r.status == 200 and d == b"abcde"
    finally:
        s2.close()


def test_check_duplicate_wins_over_disk_full(tmp_path, monkeypatch):
    # S2 / H30: a resend of something already on the PC is "already on PC", also when the disk is full.
    ib = ferry.Inbox(str(tmp_path))
    p = put(tmp_path, "a.jpg", b"x" * 10)
    t = int(os.stat(p).st_mtime * 1000)
    monkeypatch.setattr(ib, "free_bytes", lambda: 1000)
    assert ib.precheck("a.jpg", 10, t).get("duplicate") == "a.jpg"
    assert ib.precheck("b.jpg", 10, t) == {"ok": False, "reason": "disk_full", "free_bytes": 1000}
    assert ib.check_batch([("a.jpg", 10, t)])["results"][0].get("duplicate") == "a.jpg"


def test_dot_name_upload_and_resend(make_srv):
    # S4: ".nomedia" keeps its name; the resend is still recognised.
    srv = make_srv()
    body = b"hello"
    h = {"Content-Length": str(len(body))}
    st, j = srv.json("POST", "/api/v1/upload?name=.nomedia&size=5", body=body, headers=h)
    assert st == 200 and j["saved_as"] == ".nomedia"
    st, j = srv.json("POST", "/api/v1/upload?name=.nomedia&size=5", body=body, headers=h)
    assert st == 200 and j["result"] == "skipped"
    assert sorted(os.listdir(str(srv.inbox))) == [".nomedia"]
