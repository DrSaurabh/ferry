"""pytest for the host-side CI scripts in android/ci (plan §1.4, CP5). Runs locally and in app-build."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "pc"))

import check_apk  # noqa: E402

CERT_FILE = os.path.join(HERE, "..", "signing", "cert-sha256.txt")
FIX = os.path.join(HERE, "fixtures")

BADGING = ("package: name='app.ferry' versionCode='20057' versionName='2.0.57' platformBuildVersionName='15' "
           "platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'\n"
           "sdkVersion:'25'\ntargetSdkVersion:'35'\n")


def expected_cert():
    with open(CERT_FILE) as f:
        return f.read().strip()


def test_normalise_strips_colons_spaces_and_case():
    assert check_apk.normalise_fp("90:07:AB cd\n") == "9007abcd"


def test_cert_file_is_a_sha256():
    assert len(check_apk.normalise_fp(expected_cert())) == 64


def test_cert_matches_after_normalisation():
    out = "Signer #1 certificate SHA-256 digest: %s\n" % check_apk.normalise_fp(expected_cert())
    assert check_apk.check_cert(out, expected_cert()) == []
    # colon/upper-case form on the apksigner side too
    out2 = "Signer #1 certificate SHA-256 digest: %s\n" % expected_cert().upper()
    assert check_apk.check_cert(out2, expected_cert()) == []


def test_cert_per_sdk_range_signer_lines_accepted():
    fp = check_apk.normalise_fp(expected_cert())
    out = ("Signer (minSdkVersion=24, maxSdkVersion=2147483647) certificate SHA-256 digest: %s\n"
           "Signer (minSdkVersion=25, maxSdkVersion=27) certificate SHA-256 digest: %s\n" % (fp, fp))
    assert check_apk.check_cert(out, expected_cert()) == []


def test_cert_real_apksigner_37_output_accepted():
    # observed on ubuntu-latest, build-tools 37.0.0, minSdk 25 (v2 scheme only)
    out = ("Verifies\nV2 Signer: certificate DN: CN=Ferry, O=Saurabh, C=IN\n"
           "V2 Signer: certificate SHA-256 digest: %s\n"
           "V2 Signer: certificate SHA-1 digest: 0123456789abcdef0123456789abcdef01234567\n"
           % check_apk.normalise_fp(expected_cert()))
    assert check_apk.parse_apksigner(out) == [check_apk.normalise_fp(expected_cert())]
    assert check_apk.check_cert(out, expected_cert()) == []


def test_cert_mismatch_and_missing_are_reported():
    other = "Signer #1 certificate SHA-256 digest: %s\n" % ("ab" * 32)
    assert check_apk.check_cert(other, expected_cert())
    assert check_apk.check_cert("Verifies\n", expected_cert())
    two = other + "Signer #2 certificate SHA-256 digest: %s\n" % check_apk.normalise_fp(expected_cert())
    assert check_apk.check_cert(two, expected_cert())


def test_libs_expected_compose_picture_is_ok():
    names = ["classes.dex", "AndroidManifest.xml"] + [
        "lib/%s/libandroidx.graphics.path.so" % abi for abi in ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")]
    assert check_apk.check_libs(names) == []
    assert check_apk.check_libs(["classes.dex"]) == []


def test_libs_unknown_library_rejected():
    names = ["lib/%s/libfoo.so" % abi for abi in ("armeabi-v7a", "arm64-v8a")]
    assert any("allow-list" in p for p in check_apk.check_libs(names))


def test_libs_missing_32bit_arm_rejected():
    names = ["lib/arm64-v8a/libandroidx.graphics.path.so", "lib/x86_64/libandroidx.graphics.path.so"]
    problems = check_apk.check_libs(names)
    assert problems == ["libandroidx.graphics.path.so is missing for armeabi-v7a"]


def test_badging_parsed_and_compared():
    assert check_apk.parse_badging(BADGING) == ("20057", "2.0.57")
    assert check_apk.check_version(BADGING, "20057", "2.0.57") == []
    assert check_apk.check_version(BADGING, None, None) == []
    assert len(check_apk.check_version(BADGING, "1", "2.0.0-dev")) == 2
    assert check_apk.check_version("nothing", "1", None)


def test_latest_build_tools_sorts_versions(tmp_path):
    for v in ("9.0.0", "34.0.0", "35.0.0", "35.0.0-rc1", "35.0.1", "notadir.txt"):
        p = tmp_path / "build-tools" / v
        if v.endswith(".txt"):
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("x")
        else:
            p.mkdir(parents=True)
    assert os.path.basename(check_apk.latest_build_tools(str(tmp_path))) == "35.0.1"


def test_fixture_gps_jpeg_has_gps():
    import ferry
    with open(os.path.join(FIX, "gps.jpg"), "rb") as f:
        assert ferry.exif_gps_status(f.read(65536)) == "present"


def test_fixtures_present():
    for name in ["gps.jpg", "broken.jpg", "small.mp4"] + ["photo%d.jpg" % n for n in range(1, 6)]:
        assert os.path.getsize(os.path.join(FIX, name)) > 1000


def test_shots_from_log_round_trip(tmp_path):
    import base64
    import shots_from_log
    png = b"\x89PNG\r\n\x1a\n" + bytes(range(256)) * 3
    log = ["2026-11-15T08:00:00.1234567Z noise\n",
           "2026-11-15T08:00:01.0000000Z FERRY_PNG_BEGIN screenshots/tv/launch.png\n",
           "2026-11-15T08:00:01.0000001Z %s\n" % base64.b64encode(png).decode(),
           "2026-11-15T08:00:01.0000002Z FERRY_PNG_END\n"]
    assert list(shots_from_log.extract(log)) == [("screenshots/tv/launch.png", png)]
    f = tmp_path / "job.log"
    f.write_text("".join(log))
    assert shots_from_log.main(["x", str(f), str(tmp_path / "out")]) == 0
    assert (tmp_path / "out" / "screenshots" / "tv" / "launch.png").read_bytes() == png


def test_junit_summary(tmp_path):
    import junit_summary
    d = tmp_path / "test-results" / "test"
    d.mkdir(parents=True)
    (d / "TEST-app.ferry.core.it.ClientAgainstFerryPyTest.xml").write_text(
        '<testsuite name="x"><testcase name="a" classname="app.ferry.core.it.ClientAgainstFerryPyTest" time="1.5"/>'
        '<testcase name="b" classname="app.ferry.core.it.ClientAgainstFerryPyTest" time="0"><skipped/></testcase>'
        '<testcase name="c" classname="app.ferry.core.HostPortTest" time="0.1"><failure message="m">t</failure>'
        '</testcase><system-out><![CDATA[noise\nFERRY_NOTE diskFull branch=507\n]]></system-out></testsuite>')
    rows, total = junit_summary.summarise([str(tmp_path)])
    assert total == "junit: 1 passed, 1 skipped, 1 failed"
    assert rows[0].startswith("passed") and rows[0].endswith("ClientAgainstFerryPyTest.a")
    assert rows[2].startswith("FAILED") and rows[2].endswith("HostPortTest.c")
    assert rows[3] == "note    diskFull branch=507"
    assert len(rows) == 4


# ---------- check_manifest.py (Ph4) ----------
def _manifest(name):
    import check_manifest
    with open(os.path.join(FIX, name), encoding="utf-8") as f:
        return check_manifest.load(f.read())


def test_manifest_xmltree_fixture_passes():
    import check_manifest
    assert check_manifest.check(_manifest("manifest-xmltree.txt")) == []


def test_manifest_plain_xml_fixture_passes():
    import check_manifest
    assert check_manifest.check(_manifest("manifest-sample.xml")) == []


def test_manifest_source_matches_sample_entries():
    """The app's source manifest declares every permission the checker requires (and not the forbidden one)."""
    import check_manifest
    with open(os.path.join(HERE, "..", "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8") as f:
        src = check_manifest.load(f.read())
    names = {p.get("name") for p in src.kids("uses-permission")}
    assert set(check_manifest.REQUIRED_PERMISSIONS) <= names
    assert not names & set(check_manifest.FORBIDDEN_PERMISSIONS)


def _mutated(old, new, count=1):
    import check_manifest
    with open(os.path.join(FIX, "manifest-sample.xml"), encoding="utf-8") as f:
        text = f.read()
    assert old in text, old
    return check_manifest.check(check_manifest.load(text.replace(old, new, count)))


def test_manifest_each_rule_can_fail():
    cases = [
        ('android:usesCleartextTraffic="true"', 'android:usesCleartextTraffic="false"', "usesCleartextTraffic"),
        ('<uses-permission android:name="android.permission.INTERNET" />', "", "INTERNET"),
        ('<uses-permission android:name="android.permission.INTERNET" />',
         '<uses-permission android:name="android.permission.INTERNET" />'
         '<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />', "WRITE_EXTERNAL_STORAGE"),
        ('android:maxSdkVersion="32"', 'android:maxSdkVersion="28"', "maxSdkVersion 32"),
        ('android:minSdkVersion="25"', 'android:minSdkVersion="26"', "minSdkVersion"),
        ('<category android:name="android.intent.category.LEANBACK_LAUNCHER" />', "", "LEANBACK_LAUNCHER"),
        ('android:banner="@drawable/banner"', "", "banner"),
        ('android:launchMode="singleTask"', 'android:launchMode="singleTop"', "singleTask"),
        ('|uiMode"', '"', "configChanges"),
        ('android:foregroundServiceType="dataSync"', 'android:foregroundServiceType="mediaPlayback"', "TransferService"),
        ('android:authorities="app.ferry.files"', 'android:authorities="app.ferry.other"', "authority"),
        ('android:taskAffinity=""', 'android:taskAffinity="x"', "taskAffinity"),
        ('<action android:name="android.intent.action.SEND_MULTIPLE" />', "", "SEND_MULTIPLE"),
        ('android:name="android.software.leanback" android:required="false"',
         'android:name="android.software.leanback" android:required="true"', "leanback"),
        ("</application>", '<uses-library android:name="org.apache.http.legacy" /></application>', "uses-library"),
    ]
    for old, new, word in cases:
        problems = _mutated(old, new)
        assert problems, "no problem reported for: %s" % word
        assert any(word in p for p in problems), (word, problems)


def test_manifest_xmltree_values():
    import check_manifest
    assert check_manifest._xmltree_value('"a b" (Raw: "a b")') == "a b"
    assert check_manifest._xmltree_value("(type 0x12)0xffffffff") == "0xffffffff"
    assert check_manifest.as_bool("0xffffffff") is True and check_manifest.as_bool("false") is False
    assert check_manifest.as_int("0x7a0") == 0x7a0
    assert check_manifest.as_int("orientation|uiMode", check_manifest.CONFIG_BITS) == 0x280


def test_manifest_cli(tmp_path, capsys):
    import check_manifest
    assert check_manifest.main([os.path.join(FIX, "manifest-xmltree.txt")]) == 0
    bad = tmp_path / "m.txt"
    bad.write_text("nothing here\n")
    assert check_manifest.main([str(bad)]) == 1
    assert "could not read the manifest" in capsys.readouterr().out


# ---------- make_apk_json.py (Ph4) ----------
def test_make_apk_json_is_accepted_by_ferry_appbundle(tmp_path):
    import json
    import ferry
    import make_apk_json
    apk = tmp_path / "Ferry.apk"
    apk.write_bytes(b"PK\x03\x04" + os.urandom(5000))
    meta = make_apk_json.apk_json(str(apk), "20057", "2.0.57")
    assert meta["version_code"] == 20057 and meta["size"] == 5004 and meta["url"] == "/app/Ferry.apk"
    (tmp_path / "Ferry.apk.json").write_text(json.dumps(meta))
    state, got, _ = ferry.AppBundle(str(tmp_path)).status()
    assert state == "ok" and got["sha256"] == meta["sha256"] and got["version_name"] == "2.0.57"


def test_make_apk_json_rejects_bad_version(tmp_path):
    import pytest
    import make_apk_json
    apk = tmp_path / "a.apk"
    apk.write_bytes(b"x")
    with pytest.raises(ValueError):
        make_apk_json.apk_json(str(apk), "0", "2.0.0")


def test_manifest_real_xmltree_rules_can_fail():
    """The fixture is the real `aapt2 dump xmltree` of the Ph4 release APK (from the CI log)."""
    import check_manifest
    with open(os.path.join(FIX, "manifest-xmltree.txt"), encoding="utf-8") as f:
        text = f.read()
    cases = [
        ("usesCleartextTraffic(0x010104ec)=true", "usesCleartextTraffic(0x010104ec)=false", "usesCleartextTraffic"),
        ("foregroundServiceType(0x01010599)=0x00000001", "foregroundServiceType(0x01010599)=0x00000002", "TransferService"),
        ("configChanges(0x0101001f)=0x000007a0", "configChanges(0x0101001f)=0x00000080", "configChanges"),
        ("launchMode(0x0101001d)=2", "launchMode(0x0101001d)=1", "singleTask"),
        ('"android.permission.ACCESS_MEDIA_LOCATION"', '"android.permission.SOMETHING_ELSE"', "ACCESS_MEDIA_LOCATION"),
    ]
    for old, new, word in cases:
        assert old in text, old
        problems = check_manifest.check(check_manifest.load(text.replace(old, new, 1)))
        assert any(word in p for p in problems), (word, problems)


# ---------- verify_inbox.py (Ph5a) ----------
import verify_inbox  # noqa: E402


def _gps_fixture():
    return os.path.join(FIX, "gps.jpg")


def test_verify_identical_with_date(tmp_path):
    p = tmp_path / "IMG.jpg"
    p.write_bytes(open(_gps_fixture(), "rb").read())
    os.utime(str(p), (1735732800, 1735732800))
    ok, text = verify_inbox.check_identical(str(tmp_path), "IMG.jpg", _gps_fixture(), "1735732801")
    assert ok, text
    assert "identical, GPS present" in text
    ok, text = verify_inbox.check_identical(str(tmp_path), "IMG.jpg", _gps_fixture(), "1735732900")
    assert not ok and "WRONG" in text
    assert not verify_inbox.check_identical(str(tmp_path), "missing.jpg", _gps_fixture())[0]


def test_verify_identical_fails_on_other_bytes(tmp_path):
    (tmp_path / "IMG.jpg").write_bytes(open(os.path.join(FIX, "photo1.jpg"), "rb").read())
    assert not verify_inbox.check_identical(str(tmp_path), "IMG.jpg", _gps_fixture())[0]


def test_verify_redacted_is_the_negative_control(tmp_path):
    raw = open(_gps_fixture(), "rb").read()
    (tmp_path / "IMG.jpg").write_bytes(raw)
    ok, text = verify_inbox.check_redacted(str(tmp_path), "IMG.jpg", _gps_fixture())
    assert not ok and "inconclusive" in text
    # what Android serves without the permission: same size, GPS zeroed (simulated: different bytes)
    (tmp_path / "IMG.jpg").write_bytes(raw[:-1] + b"\x00")
    ok, text = verify_inbox.check_redacted(str(tmp_path), "IMG.jpg", _gps_fixture())
    assert ok, text


def test_verify_no_partials(tmp_path):
    (tmp_path / "a.jpg").write_bytes(b"x")
    assert verify_inbox.check_no_partials(str(tmp_path))[0]
    (tmp_path / ".ferry-abc.part").write_bytes(b"x")
    assert not verify_inbox.check_no_partials(str(tmp_path))[0]


def test_verify_uploads_reads_ferry_request_log(tmp_path):
    log = tmp_path / "ferry-log.txt"
    rows = [
        ["2026-11-15 10:00:00.000", "10.0.2.16", "vivo", "POST", "/api/v1/upload?name=IMG.jpg", "", "200", "10", "5", "saved IMG.jpg", ""],
        ["2026-11-15 10:00:01.000", "10.0.2.16", "vivo", "POST", "/api/v1/check-batch", "", "200", "10", "5", "checked 1 file", ""],
        ["2026-11-15 10:00:02.000", "10.0.2.16", "vivo", "POST", "/api/v1/upload?name=other.jpg", "", "200", "10", "5", "saved other.jpg", ""],
        ["2026-11-15 10:00:03.000", "10.0.2.16", "vivo", "POST", "/api/v1/upload?name=IMG.jpg", "", "200", "10", "5", "skipped IMG.jpg", ""],
    ]
    log.write_text("".join("\t".join(r) + "\n" for r in rows), encoding="utf-8")
    assert verify_inbox.upload_outcomes(str(log), "IMG.jpg") == ["saved", "skipped"]
    assert verify_inbox.check_uploads(str(log), "IMG.jpg", ["saved", "skipped"])[0]
    assert not verify_inbox.check_uploads(str(log), "IMG.jpg", ["saved"])[0]


def test_verify_uploads_matches_a_real_ferry_log_line(tmp_path):
    import ferry
    t = [1735732800.0]
    rl = ferry.RequestLog(str(tmp_path), lambda m: None, clock=lambda: t[0], rotate_delays=(0, 0, 0))
    rl.start()
    rl.request("10.0.2.16", "vivo", "POST", "/api/v1/upload?name=IMG.jpg&size=5&mtime=1", "", 200, 5, 3, "saved IMG.jpg", "")
    rl.stop()
    assert verify_inbox.upload_outcomes(str(tmp_path / ferry.LOG_NAME), "IMG.jpg") == ["saved"]


# ---------- verify_inbox.py share helper pattern (Ph5b) ----------
def test_helper_pattern_layout():
    data = verify_inbox.helper_pattern(2, 4096 + 10)
    assert len(data) == 4106
    assert data[:8] == bytes([2, 0, 0, 0, 0, 0, 0, 0])
    assert data[8] == (8 * 31 + 2 * 7) & 0xFF
    assert data[4096:4104] == bytes([2, 0, 0, 0, 0, 0, 0, 1])
    assert data[4104] == (8 * 31 + 2 * 7 + 1) & 0xFF
    assert data[4105] == (9 * 31 + 2 * 7 + 1) & 0xFF


def test_check_pattern_and_size(tmp_path):
    (tmp_path / "a.bin").write_bytes(verify_inbox.helper_pattern(3, 300000))
    assert verify_inbox.check_pattern(str(tmp_path), "a.bin", 3, 300000)[0]
    assert not verify_inbox.check_pattern(str(tmp_path), "a.bin", 1, 300000)[0]
    assert not verify_inbox.check_pattern(str(tmp_path), "a.bin", 3, 299999)[0]
    assert not verify_inbox.check_pattern(str(tmp_path), "missing.bin", 3, 1)[0]
    assert verify_inbox.check_size(str(tmp_path), "a.bin", 300000)[0]
    assert not verify_inbox.check_size(str(tmp_path), "a.bin", 300001)[0]


def test_helper_pattern_30mb_is_fast():
    import time
    t = time.time()
    assert len(verify_inbox.helper_pattern(1, 30 << 20)) == 30 << 20
    assert time.time() - t < 10
