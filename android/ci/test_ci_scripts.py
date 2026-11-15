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
