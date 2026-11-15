import os
import time

import pytest

import ferry
from ferry import Outbox


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def mk(tmp_path, **kw):
    logs = []
    c = Clock()
    ob = Outbox(str(tmp_path), log=lambda *a: logs.append(a), is_win=False, clock=c, **kw)
    return ob, c, logs


def write(p, data=b"x", mtime=None):
    p.write_bytes(data)
    if mtime:
        os.utime(p, (mtime, mtime))


def test_initial_scan_order_and_ids(tmp_path):
    write(tmp_path / "b.txt", mtime=2000)
    write(tmp_path / "a.txt", mtime=1000)
    write(tmp_path / "c.txt", mtime=3000)
    ob, c, _ = mk(tmp_path)
    ob.start_scan()
    assert [(o.id, o.name) for o in ob.snapshot()] == [(3, "c.txt"), (2, "b.txt"), (1, "a.txt")]
    assert ob.newest().name == "c.txt"


def test_new_file_needs_stability(tmp_path):
    ob, c, logs = mk(tmp_path)
    ob.start_scan()
    write(tmp_path / "new.mp4", b"1234")
    ob.poll()
    assert ob.snapshot() == []
    c.t += 1.0
    ob.poll()
    assert ob.snapshot() == []
    c.t += 1.5
    ob.poll()
    s = ob.snapshot()
    assert len(s) == 1 and s[0].id == 1 and s[0].kind == "video_playable"
    assert any(a[1] == "+ #1" for a in logs)


def test_growing_file_stays_pending(tmp_path):
    ob, c, _ = mk(tmp_path)
    ob.start_scan()
    p = tmp_path / "grow.bin"
    for i in range(5):
        write(p, b"x" * (i + 1))
        ob.poll()
        c.t += 1.0
    assert ob.snapshot() == []
    c.t += 3
    ob.poll()
    assert len(ob.snapshot()) == 1


def test_unreadable_waits(tmp_path):
    readable = {"ok": False}
    ob, c, _ = mk(tmp_path, readable=lambda p: readable["ok"])
    ob.start_scan()
    write(tmp_path / "locked.bin")
    ob.poll()
    c.t += 5
    ob.poll()
    assert ob.snapshot() == []
    readable["ok"] = True
    ob.poll()
    assert len(ob.snapshot()) == 1


def test_modified_file_gets_new_id_and_removed(tmp_path):
    write(tmp_path / "a.txt", b"1", mtime=1000)
    ob, c, logs = mk(tmp_path)
    ob.start_scan()
    assert ob.get(1).name == "a.txt"
    write(tmp_path / "a.txt", b"22", mtime=5000)
    ob.poll()
    assert ob.get(1) is None and ob.snapshot() == []
    c.t += 3
    ob.poll()
    assert ob.snapshot()[0].id == 2
    os.unlink(tmp_path / "a.txt")
    ob.poll()
    assert ob.snapshot() == [] and ob.get(2) is None
    assert any(a[1] == "-" and a[2] == "a.txt" for a in logs)


def test_ids_never_reused(tmp_path):
    ob, c, _ = mk(tmp_path)
    ob.start_scan()
    for i in range(3):
        write(tmp_path / ("f%d" % i))
        ob.poll()
        c.t += 3
        ob.poll()
        os.unlink(tmp_path / ("f%d" % i))
        ob.poll()
    write(tmp_path / "last")
    ob.poll()
    c.t += 3
    ob.poll()
    assert ob.snapshot()[0].id == 4


def test_ignored_entries(tmp_path):
    for n in (".hidden", "desktop.ini", "Thumbs.db", "~$doc.docx", "dl.crdownload", "x.part", "x.tmp", "link.lnk"):
        write(tmp_path / n)
    os.mkdir(tmp_path / "Holiday")
    os.symlink("/etc/passwd", tmp_path / "evil")
    write(tmp_path / "real.pdf")
    ob, c, logs = mk(tmp_path)
    ob.start_scan()
    assert [o.name for o in ob.snapshot()] == ["real.pdf"]
    assert ob.folders == {"Holiday"}
    texts = " ".join(str(a) for a in logs)
    assert "folders aren't sent" in texts and "shortcut" in texts


def test_missing_folder_recreated(tmp_path):
    d = tmp_path / "out"
    d.mkdir()
    ob = Outbox(str(d), log=lambda *a: None, is_win=False, clock=Clock())
    ob.start_scan()
    d.rmdir()
    ob.poll()
    assert d.is_dir()


def test_contains_rejects_escape(tmp_path):
    d = tmp_path / "out"
    d.mkdir()
    write(tmp_path / "secret.txt")
    os.symlink(str(tmp_path / "secret.txt"), d / "link.txt")
    write(d / "ok.txt")
    ob = Outbox(str(d), is_win=False)
    assert ob.contains(str(d / "ok.txt"))
    assert not ob.contains(str(d / "link.txt"))
    assert not ob.contains(str(tmp_path / "secret.txt"))


def test_kinds():
    assert ferry.file_kind("a.MP4") == "video_playable"
    assert ferry.file_kind("a.mkv") == "video"
    assert ferry.file_kind("a.apk") == "apk"
    assert ferry.file_kind("a.HEIC") == "image"
    assert ferry.file_kind("a.pdf") == "other"


def test_arrival_time_windows_uses_ctime():
    class St:
        st_ctime = 5.0
        st_mtime = 1.0
    assert ferry._arrival_time(St(), True) == 5.0
    assert ferry._arrival_time(St(), False) == 1.0
    _ = time


@pytest.mark.skipif(ferry.IS_WIN, reason="Windows file names are Unicode; undecodable names cannot exist")
def test_undecodable_name_is_not_offered(tmp_path):
    """Audit O3: one badly-encoded name must not break the whole outbox list."""
    import json
    try:
        with open(os.path.join(os.fsencode(str(tmp_path)), b"caf\xe9.txt"), "wb") as f:
            f.write(b"x")
    except OSError:
        return                                       # file system refuses such names: nothing to test
    write(tmp_path / "good.txt")
    ob, c, logs = mk(tmp_path)
    ob.start_scan()
    assert [o.name for o in ob.snapshot()] == ["good.txt"]
    json.dumps([o.to_json() for o in ob.snapshot()], ensure_ascii=False).encode("utf-8")
    ob.poll(now=0)
    ob.poll(now=1)
    notes = [a for a in logs if "unusual name" in a[-1]]
    assert len(notes) == 1
    "".join(notes[0]).encode("utf-8")                # the console line itself must be printable


def test_watcher_reports_a_repeating_error_once(tmp_path):
    """Audit O5: the same error every second would scroll the QR code away."""
    f = tmp_path / "not-a-folder"
    f.write_bytes(b"x")
    logs = []
    ob = Outbox(str(f), log=lambda *a: logs.append(a), is_win=False)
    for _ in range(5):
        ob.poll_safe()
    assert len(logs) == 1 and "NotADirectoryError" in logs[0][-1]
    f.unlink()
    f.mkdir()
    ob.poll_safe()                                   # healthy again ...
    f.rmdir()
    f.write_bytes(b"x")
    ob.poll_safe()                                   # ... so a new failure is reported again
    assert len(logs) == 2
