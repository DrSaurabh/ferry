"""2.1 (L5, M12): the PC reads date taken + GPS of a JPEG itself, bounds-checked."""
import os

import ferry
from exif_maker import jpeg, tiff
from helpers import ROOT, jpeg_with_gps

S = ferry.exif_summary


def test_gps_and_date_original():
    j = jpeg(tiff(date_time="2020:01:01 00:00:00", date_original="2025:11:03 10:15:00", gps="yes"))
    assert S(j) == {"taken": "2025-11-03 10:15:00", "gps": "yes"}      # 0x8769 -> 0x9003 wins over IFD0 DateTime


def test_no_gps_ifd0_datetime_fallback():
    assert S(jpeg(tiff(date_time="2024:07:03 17:28:00"))) == {"taken": "2024-07-03 17:28:00", "gps": "no"}   # PS5


def test_zeroed():
    assert S(jpeg(tiff(gps="zeroed")))["gps"] == "zeroed"
    assert S(jpeg_with_gps(lat_nonzero=False))["gps"] == "zeroed"
    assert S(jpeg_with_gps())["gps"] == "yes"


def test_big_endian():
    j = jpeg(tiff(">", date_original="2023:02:01 08:09:10", gps="yes"))
    assert S(j) == {"taken": "2023-02-01 08:09:10", "gps": "yes"}


def test_truncated_and_hostile_never_raise():
    j = jpeg(tiff(date_original="2025:11:03 10:15:00", gps="yes"))
    for n in range(0, len(j), 3):
        r = S(j[:n])
        assert r["gps"] in ("yes", "no", "zeroed", "unknown") and r["taken"] in (None, "2025-11-03 10:15:00")
    bad = bytearray(j)
    bad[30:34] = b"\xff\xff\xff\x7f"               # absurd offsets inside the TIFF
    assert S(bytes(bad))["gps"] in ("yes", "no", "zeroed", "unknown")
    assert S(b"") == {"taken": None, "gps": "unknown"}
    assert S(b"\x89PNG\r\n") == {"taken": None, "gps": "unknown"}


def test_no_exif_at_all():
    assert S(jpeg()) == {"taken": None, "gps": "no"}


def test_bad_date_ignored():
    assert S(jpeg(tiff(date_time="0000:00:00 00:00:00")))["taken"] is None
    assert S(jpeg(tiff(date_time="yesterday")))["taken"] is None


def test_ci_fixture_and_probe_file(tmp_path):
    fx = os.path.join(os.path.dirname(ROOT), "android", "ci", "fixtures", "gps.jpg")
    if os.path.exists(fx):
        r = ferry.probe_file(fx)
        assert r["gps"] == "yes" and r["taken"]            # the phone suite's host check expects "GPS yes"
    p = tmp_path / "a.png"
    p.write_bytes(b"\x89PNG")
    assert ferry.probe_file(str(p)) is None
    assert ferry.probe_file(str(tmp_path / "missing.jpg")) is None
