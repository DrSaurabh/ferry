import ferry
from ferry import sanitize_name as S, split_ext, numbered


def test_path_components_stripped():
    assert S("../../etc/passwd", is_win=False) == "passwd"
    assert S("C:\\a\\b\\photo.jpg", is_win=False) == "photo.jpg"
    assert S("/sdcard/DCIM/IMG_1.jpg", is_win=False) == "IMG_1.jpg"


def test_invalid_chars_replaced():
    assert S('a<b>c:d"e|f?g*h.txt', is_win=False) == "a_b_c_d_e_f_g_h.txt"


def test_control_and_format_chars():
    assert S("bad\x00\x1fname.txt", is_win=False) == "bad__name.txt"
    assert S("invoice\u202egpj.exe", is_win=False) == "invoice_gpj.exe"


def test_nfc():
    assert S("cafe\u0301.jpg", is_win=False) == "caf\u00e9.jpg"


def test_empty_and_dots():
    assert S("", is_win=False) == "file"
    assert S("...", is_win=False) == "file"
    assert S("  name.txt. ", is_win=False) == "name.txt"


def test_reserved_names():
    for raw in ("con.txt", "CON", "Con .txt", "nul.tar.gz", "com1.log", "LPT9", "com\u00b9.log", "conin$", "CONOUT$.x"):
        assert S(raw, is_win=False).startswith("_"), raw
    assert S("console.txt", is_win=False) == "console.txt"
    assert S("com10.txt", is_win=False) == "com10.txt"


def test_unicode_kept():
    assert S("\u0928\u092e\u0938\u094d\u0924\u0947 \U0001F600.jpg", is_win=False) == "\u0928\u092e\u0938\u094d\u0924\u0947 \U0001F600.jpg"


def test_byte_limit_keeps_extension_and_codepoints():
    raw = "\u00e9" * 300 + ".jpeg"
    out = S(raw, is_win=False)
    assert out.endswith(".jpeg")
    assert len(out.encode("utf-8")) <= ferry.MAX_NAME_BYTES
    out.encode("utf-8").decode("utf-8")  # valid


def test_truncation_restrips():
    raw = "a" * 194 + "  ...." + "b" * 50  # truncation lands on spaces/dots
    out = S(raw, is_win=False)
    assert not out.endswith((" ", "."))


def test_long_extension_is_stem():
    assert split_ext("archive." + "x" * 30) == ("archive." + "x" * 30, "")
    assert split_ext("a.tar.gz") == ("a.tar", ".gz")
    assert split_ext("noext") == ("noext", "")


def test_numbered():
    assert numbered("a.jpg", 0) == "a.jpg"
    assert numbered("a.jpg", 2) == "a (2).jpg"
    assert numbered("README", 1) == "README (1)"


def test_windows_path_limit_utf16():
    deep = "C:\\Users\\Someone\\OneDrive - Company\\" + "Very Long Folder Name\\" * 8 + "Downloads\\Phone"
    raw = "\U0001F600" * 120 + ".jpg"   # emoji = 2 UTF-16 units each
    out = S(raw, deep, is_win=True)
    assert out.endswith(".jpg")
    assert ferry.utf16_len(deep) + 1 + ferry.utf16_len(out) + 8 <= ferry.WIN_MAX_PATH
    assert len(out) >= 5


def test_reserved_after_truncation():
    out = S("CON." + "x" * 10, is_win=False)
    assert out.startswith("_CON")


def test_leading_dot_kept_trailing_stripped():
    # Audit S4: the original name keeps its leading dot; only what Windows drops (trailing dots/spaces) goes.
    for win in (False, True):
        assert S(".nomedia", is_win=win) == ".nomedia"
        assert S(".trashed-170-IMG.jpg", is_win=win) == ".trashed-170-IMG.jpg"
        assert S("  .hidden.txt . ", is_win=win) == ".hidden.txt"
        assert S("photo.", is_win=win) == "photo"
        assert S("..", is_win=win) == "file"
        assert S(". .", is_win=win) == "file"
        assert S("../..", is_win=win) == "file"
    assert split_ext(".nomedia") == (".nomedia", "")


def test_leading_dot_never_looks_like_a_temp_file():
    # ".ferry-*.part" is what the start-up cleanup deletes: an upload can't be saved under such a name.
    for raw in (".ferry-abc.part", ".FERRY-x.part", " .ferry-probe-1"):
        out = S(raw, is_win=True)
        assert not out.lower().startswith(ferry.TEMP_PREFIX), out
        assert out.startswith("_.")
