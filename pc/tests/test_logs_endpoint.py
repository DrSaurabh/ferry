"""POST /api/v1/logs (2.1 plan §2.3, design §7.2-7.3, M8, M12)."""
import json
import os
import time

import ferry


def line(seq, text="tap id=send"):
    return "2026-11-24 02:01:03.456 #%d s=7f3a %s" % (seq, text)


def post(srv, body, name="vivo V2413", dev_id="a3f9", frm=None, extra=None):
    h = {"Content-Type": "text/plain; charset=utf-8"}
    if name is not None:
        h[ferry.DEVICE_HEADER] = name
    if dev_id is not None:
        h[ferry.DEVICE_ID_HEADER] = dev_id
    if frm is not None:
        h[ferry.LOG_FROM_HEADER] = str(frm)
    h.update(extra or {})
    data = body.encode("utf-8") if isinstance(body, str) else body
    h["Content-Length"] = str(len(data))
    r, d = srv.req("POST", "/api/v1/logs", body=data, headers=h)
    return r.status, json.loads(d.decode("utf-8"))


def device_log(srv, folder="vivo V2413 (a3f9)"):
    return (srv.logs / folder / ferry.DEVICE_LOG_NAME).read_text(encoding="utf-8")


def test_append_and_reply(srv):
    st, j = post(srv, line(1) + "\n" + line(2, 'err.shown text="PC not found"') + "\n", frm=1)
    assert st == 200 and j["ok"] is True and j["stored"] == 2 and j["last_seq"] == 2
    assert abs(j["time_ms"] - time.time() * 1000) < 60_000
    assert device_log(srv) == line(1) + "\n" + line(2, 'err.shown text="PC not found"') + "\n"
    st, j = post(srv, line(3) + "\n", frm=3)
    assert st == 200 and j["stored"] == 1 and j["last_seq"] == 3
    assert device_log(srv).count("\n") == 3


def test_repeated_seq_dropped(srv):
    post(srv, line(1) + "\n" + line(2) + "\n", frm=1)
    st, j = post(srv, line(1) + "\n" + line(2) + "\n" + line(3) + "\n", frm=1)   # a retry after a lost reply
    assert st == 200 and j["stored"] == 1 and j["last_seq"] == 3
    assert [l.split()[2] for l in device_log(srv).splitlines()] == ["#1", "#2", "#3"]
    st, j = post(srv, line(2) + "\n", frm=2)
    assert j["stored"] == 0 and j["last_seq"] == 3
    assert (srv.logs / "vivo V2413 (a3f9)" / ferry.DEVICE_LOG_SEQ_NAME).read_text().strip() == "3"


def test_too_big_is_413(srv):
    body = (line(1, "diag msg=" + "x" * 1000) + "\n") * ((ferry.LOG_UPLOAD_MAX // 1000) + 10)
    st, j = post(srv, body)
    assert st == 413
    assert not (srv.logs / "vivo V2413 (a3f9)").exists()


def test_not_utf8_or_no_newline_is_400(srv):
    st, _ = post(srv, b"2026-11-24 02:01:03.456 #1 s=7f3a tap id=\xff\xfe\n")
    assert st == 400
    st, _ = post(srv, line(1))
    assert st == 400
    st, _ = post(srv, "")
    assert st == 400


def test_get_is_405(srv):
    r, _ = srv.req("GET", "/api/v1/logs")
    assert r.status == 405


def test_no_device_id_goes_to_unknown(srv):
    st, j = post(srv, line(5) + "\n", dev_id=None)
    assert st == 200 and j["stored"] == 1
    assert device_log(srv, "unknown") == line(5) + "\n"
    st, j = post(srv, line(6) + "\n", dev_id="not hex!")
    assert st == 200 and device_log(srv, "unknown").endswith(line(6) + "\n")


def test_path_safety_of_names(srv):
    for name in ("..\\x", "../../evil", "CON", "a/b\\c:d*e", ".."):
        st, j = post(srv, line(1) + "\n", name=name, dev_id="b2c4")
        assert st == 200 and j["stored"] in (0, 1), name
    made = sorted(os.listdir(str(srv.logs)))
    for f in made:
        assert os.path.dirname(os.path.abspath(str(srv.logs / f))) == os.path.abspath(str(srv.logs))
    assert not (srv.tmp / "evil (b2c4)").exists() and not (srv.data / "x (b2c4)").exists()
    assert all(".." != f and "/" not in f and "\\" not in f for f in made), made
    assert "CON (b2c4)" in made or "_CON (b2c4)" in made


def test_percent_encoded_name(srv):
    st, _ = post(srv, line(1) + "\n", name="Saurabh%E2%80%99s%20phone", dev_id="0c1d")
    assert st == 200
    assert (srv.logs / "Saurabh’s phone (0c1d)" / ferry.DEVICE_LOG_NAME).is_file()


def test_logs_request_in_request_log(srv):
    post(srv, line(1) + "\n" + line(2) + "\n")
    lines = [l for l in srv.log_lines() if "/api/v1/logs" in l]
    assert lines and lines[-1].split("\t")[9] == "log 2 lines"


def test_info_advertises_logs(srv):
    st, j = srv.json("GET", "/api/v1/info")
    assert "logs" in j["capabilities"] and isinstance(j["time_ms"], int)
