"""2.1 (L5, plan §2.7): one readable line per received file in ferry-log.txt."""
import ferry
from exif_maker import jpeg, tiff
from helpers import upload

PHONE = {ferry.DEVICE_HEADER: "vivo%20V2413"}


def events(srv):
    return [l.split("\t")[9] for l in srv.log_lines() if l.split("\t")[3] == "EVENT"]


def test_saved_jpeg_with_phone_headers(srv):
    data = jpeg(tiff(date_original="2025:11:03 10:15:00", gps="yes"), body=b"\x01" * 300_000)
    h = dict(PHONE, **{"X-Ferry-Taken": "2025-11-03 10:15:00", "X-Ferry-Gps": "yes", "X-Ferry-Original": "1"})
    st, res = upload(srv, "IMG_1.jpg", data, headers=h)
    assert st == 200 and res["result"] == "saved"
    line = [e for e in events(srv) if e.startswith("saved IMG_1.jpg")][0]
    parts = line.split(" · ")
    assert parts[0] == "saved IMG_1.jpg" and parts[1].startswith("300.2 KB in ")
    assert parts[-4:] == ["taken 2025-11-03 10:15:00", "GPS yes (phone: yes)", "original", "from vivo V2413"]
    assert not any(e.startswith("WARNING") for e in events(srv))


def test_saved_without_phone_headers_2_0_phone(srv):
    upload(srv, "IMG_2.jpg", jpeg(tiff(date_time="2024:07:03 17:28:00")), headers=PHONE)
    upload(srv, "clip.mp4", b"\x00" * 1000, headers=PHONE)
    ev = events(srv)
    jl = [e for e in ev if e.startswith("saved IMG_2.jpg")][0]
    assert "taken 2024-07-03 17:28:00 · GPS no · from vivo V2413" in jl
    vl = [e for e in ev if e.startswith("saved clip.mp4")][0]
    assert vl.endswith("taken ? · GPS ? · from vivo V2413")
    assert "original" not in vl


def test_video_uses_phone_headers(srv):
    h = dict(PHONE, **{"X-Ferry-Taken": "2025-01-02 03:04:05", "X-Ferry-Gps": "removed", "X-Ferry-Original": "0"})
    upload(srv, "VID.mp4", b"\x00" * 10, headers=h)
    vl = [e for e in events(srv) if e.startswith("saved VID.mp4")][0]
    assert "taken 2025-01-02 03:04:05 · GPS removed · not original · from vivo V2413" in vl


def test_skipped_and_renamed(srv):
    upload(srv, "a.jpg", b"same", headers=PHONE)
    upload(srv, "a.jpg", b"same", headers=PHONE)
    upload(srv, "a.jpg", b"different", headers=PHONE)
    ev = events(srv)
    assert "already on PC a.jpg · from vivo V2413" in ev
    assert any(e.startswith("saved a (1).jpg · ") for e in ev)


def test_check_duplicate_is_logged(srv):
    upload(srv, "b.jpg", b"bytes", mtime=1700000000000, headers=PHONE)
    srv.json("GET", "/api/v1/check?name=b.jpg&size=5&mtime=1700000000000", headers=PHONE)
    assert "already on PC b.jpg · from vivo V2413" in events(srv)


def test_gps_mismatch_warning(srv):
    h = dict(PHONE, **{"X-Ferry-Gps": "yes"})
    upload(srv, "stripped.jpg", jpeg(tiff(date_time="2024:07:03 17:28:00")), headers=h)
    ev = events(srv)
    assert any(e.startswith("saved stripped.jpg") and "GPS no (phone: yes)" in e for e in ev)
    assert "WARNING stripped.jpg: the phone says GPS yes, the file has no GPS" in ev


def test_line_format_units():
    line, w = ferry.readable_saved("IMG.jpg", 4_200_000, 0.21, "vivo V2413",
                                   {"taken": None, "gps": "yes", "original": True},
                                   {"taken": "2025-11-03 10:15:00", "gps": "yes"})
    assert line == ("saved IMG.jpg · 4.2 MB in 0.21 s · 20.0 MB/s · taken 2025-11-03 10:15:00 · "
                    "GPS yes (phone: yes) · original · from vivo V2413") and w is None
    assert ferry.fmt_seconds(244) == "4 min 04 s" and ferry.fmt_seconds(12.4) == "12 s"
    m = ferry.phone_meta({"X-Ferry-Taken": "junk\x00", "X-Ferry-Gps": "maybe", "X-Ferry-Original": "2"})
    assert m == {"taken": None, "gps": None, "original": None}
