import errno
import io
import os
import stat
import threading
import time

import pytest

import ferry
from ferry import Inbox


def up(ib, data, name="a.jpg", mtime=None):
    return ib.receive(io.BytesIO(data), len(data), name, mtime)


def files(d):
    return sorted(n for n in os.listdir(d))


def test_save_rename_skip(tmp_path):
    ib = Inbox(str(tmp_path), is_win=False)
    assert up(ib, b"one")["result"] == "saved"
    r = up(ib, b"two")
    assert r == {"result": "renamed", "saved_as": "a (1).jpg", "bytes": 3}
    assert up(ib, b"three")["saved_as"] == "a (2).jpg"
    assert up(ib, b"two") == {"result": "skipped", "saved_as": "a (1).jpg", "bytes": 3}
    assert files(tmp_path) == ["a (1).jpg", "a (2).jpg", "a.jpg"]
    assert (tmp_path / "a.jpg").read_bytes() == b"one"


def test_empty_file(tmp_path):
    ib = Inbox(str(tmp_path), is_win=False)
    assert up(ib, b"", "empty.txt")["result"] == "saved"
    assert up(ib, b"", "empty.txt")["result"] == "skipped"


def test_short_read_leaves_nothing(tmp_path):
    ib = Inbox(str(tmp_path), is_win=False)
    with pytest.raises(ferry.UploadAborted):
        ib.receive(io.BytesIO(b"abc"), 10, "x.bin", None)
    assert files(tmp_path) == []


class BadReader:
    def read(self, n):
        raise ConnectionResetError()


def test_read_error_is_abort(tmp_path):
    ib = Inbox(str(tmp_path), is_win=False)
    with pytest.raises(ferry.UploadAborted):
        ib.receive(BadReader(), 10, "x.bin", None)
    assert files(tmp_path) == []


def test_disk_full(tmp_path, monkeypatch):
    ib = Inbox(str(tmp_path), is_win=False)
    real_fdopen = os.fdopen

    class Full:
        def __init__(self, f):
            self.f = f

        def write(self, b):
            raise OSError(errno.ENOSPC, "No space left on device")

        def close(self):
            self.f.close()

    monkeypatch.setattr(ferry.os, "fdopen", lambda *a, **k: Full(real_fdopen(*a, **k)))
    data = b"x" * (3 * ferry.CHUNK)
    with pytest.raises(ferry.DiskFull) as ei:
        ib.receive(io.BytesIO(data), len(data), "big.bin", None)
    assert ei.value.remaining == 2 * ferry.CHUNK
    assert files(tmp_path) == []


def test_other_write_error(tmp_path, monkeypatch):
    ib = Inbox(str(tmp_path), is_win=False)
    real_fdopen = os.fdopen

    class Bad:
        def __init__(self, f):
            self.f = f

        def write(self, b):
            raise OSError(errno.EIO, "I/O error")

        def close(self):
            self.f.close()

    monkeypatch.setattr(ferry.os, "fdopen", lambda *a, **k: Bad(real_fdopen(*a, **k)))
    with pytest.raises(ferry.WriteError):
        ib.receive(io.BytesIO(b"abc"), 3, "x.bin", None)
    assert files(tmp_path) == []


def test_mtime_restored_and_implausible_ignored(tmp_path):
    ib = Inbox(str(tmp_path), is_win=False)
    t = 1700000000
    up(ib, b"1", "old.jpg", t * 1000)
    assert abs(os.stat(tmp_path / "old.jpg").st_mtime - t) < 1
    up(ib, b"2", "bad.jpg", 1000)  # 1970 -> ignored
    assert time.time() - os.stat(tmp_path / "bad.jpg").st_mtime < 60
    up(ib, b"3", "future.jpg", int((time.time() + 10 * 86400) * 1000))
    assert time.time() - os.stat(tmp_path / "future.jpg").st_mtime < 60


def test_permissions_follow_umask(tmp_path):
    ib = Inbox(str(tmp_path), is_win=False, umask=0o022)
    up(ib, b"x", "p.txt")
    assert stat.S_IMODE(os.stat(tmp_path / "p.txt").st_mode) == 0o644


def test_precheck_disk_full(tmp_path, monkeypatch):
    ib = Inbox(str(tmp_path), is_win=False)

    class U:
        free = 100 << 20
    monkeypatch.setattr(ferry.shutil, "disk_usage", lambda p: U)
    assert ib.precheck("a.jpg", 10, None)["ok"] is True
    r = ib.precheck("a.jpg", 50 << 20, None)
    assert r["ok"] is False and r["reason"] == "disk_full" and r["free_bytes"] == 100 << 20


def test_precheck_duplicate(tmp_path):
    ib = Inbox(str(tmp_path), is_win=False)
    t = 1700000000000
    up(ib, b"hello", "IMG.jpg", t)
    up(ib, b"other", "IMG.jpg", t)          # -> IMG (1).jpg
    assert ib.precheck("IMG.jpg", 5, t + 1500)["duplicate"] in ("IMG.jpg", "IMG (1).jpg")
    assert "duplicate" not in ib.precheck("IMG.jpg", 5, t + 5000)
    assert "duplicate" not in ib.precheck("IMG.jpg", 6, t)
    assert "duplicate" not in ib.precheck("IMG.jpg", 5, None)


def test_family_case_insensitive_on_windows(tmp_path):
    (tmp_path / "Photo.JPG").write_bytes(b"x")
    assert [n for n, _, _ in Inbox(str(tmp_path), is_win=True).family("photo.jpg")] == ["Photo.JPG"]
    assert Inbox(str(tmp_path), is_win=False).family("photo.jpg") == []


def test_temp_cleanup(tmp_path):
    (tmp_path / ".ferry-abc.part").write_bytes(b"x")
    (tmp_path / "keep.part").write_bytes(b"x")
    assert Inbox(str(tmp_path), is_win=False).cleanup_temps() == 1
    assert files(tmp_path) == ["keep.part"]


def run_threads(n, fn):
    ts = [threading.Thread(target=fn, args=(i,)) for i in range(n)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()


def test_concurrent_different_content(tmp_path):
    ib = Inbox(str(tmp_path), is_win=False)
    results = []
    run_threads(8, lambda i: results.append(up(ib, ("content-%d" % i).encode() * 1000)))
    assert len(files(tmp_path)) == 8
    contents = {(tmp_path / f).read_bytes() for f in files(tmp_path)}
    assert len(contents) == 8


def test_concurrent_identical_content(tmp_path):
    ib = Inbox(str(tmp_path), is_win=False)
    results = []
    run_threads(8, lambda i: results.append(up(ib, b"same" * 5000)))
    kinds = sorted(r["result"] for r in results)
    assert kinds.count("saved") == 1 and kinds.count("skipped") == 7
    assert files(tmp_path) == ["a.jpg"]


def test_hardlink_fallback(tmp_path, monkeypatch):
    ib = Inbox(str(tmp_path), is_win=False)

    def nolink(a, b):
        raise OSError(errno.EPERM, "Operation not permitted")
    monkeypatch.setattr(ferry.os, "link", nolink)
    up(ib, b"1")
    up(ib, b"2")
    assert files(tmp_path) == ["a (1).jpg", "a.jpg"]


def test_windows_place_branch(tmp_path, monkeypatch):
    """Simulate Windows rename semantics: fails if target exists; transient PermissionError."""
    ib = Inbox(str(tmp_path), is_win=True)
    real_rename = os.rename
    calls = {"perm": 0}

    def win_rename(src, dst):
        if os.path.exists(dst):
            raise FileExistsError(dst)
        if calls["perm"] < 2:
            calls["perm"] += 1
            raise PermissionError("locked by antivirus")
        real_rename(src, dst)
    monkeypatch.setattr(ferry.os, "rename", win_rename)
    monkeypatch.setattr(ferry.time, "sleep", lambda s: None)
    assert up(ib, b"one")["result"] == "saved"
    assert calls["perm"] == 2
    assert up(ib, b"two")["saved_as"] == "a (1).jpg"


def test_windows_place_race_target_appears(tmp_path, monkeypatch):
    ib = Inbox(str(tmp_path), is_win=True)
    real_rename = os.rename
    state = {"first": True}

    def win_rename(src, dst):
        if state["first"]:          # someone else creates the target just before us
            state["first"] = False
            open(dst, "wb").write(b"intruder")
        if os.path.exists(dst):
            raise FileExistsError(dst)
        real_rename(src, dst)
    monkeypatch.setattr(ferry.os, "rename", win_rename)
    r = up(ib, b"mine")
    assert r["saved_as"] == "a (1).jpg"
    assert (tmp_path / "a.jpg").read_bytes() == b"intruder"


def test_windows_permission_gives_up(tmp_path, monkeypatch):
    ib = Inbox(str(tmp_path), is_win=True)

    def always(src, dst):
        raise PermissionError("locked")
    monkeypatch.setattr(ferry.os, "rename", always)
    waits = []
    monkeypatch.setattr(ferry.time, "sleep", waits.append)
    with pytest.raises(ferry.WriteError) as ei:      # a clear message, not a bare PermissionError (audit O4)
        up(ib, b"x")
    assert ei.value.remaining == 0 and "antivirus" in str(ei.value)
    assert sum(waits) >= 5                           # keeps trying for several seconds before giving up
    assert files(tmp_path) == []


def test_fsync_before_the_file_gets_its_final_name(tmp_path, monkeypatch):
    """Audit O1: data must be flushed to disk before the file is placed and reported saved."""
    ib = Inbox(str(tmp_path), is_win=False)
    seen = []
    real = os.fsync

    def spy(fd):
        seen.append(files(tmp_path))                 # what the folder looked like when fsync ran
        real(fd)
    monkeypatch.setattr(ferry.os, "fsync", spy)
    assert up(ib, b"data")["result"] == "saved"
    assert len(seen) == 1 and "a.jpg" not in seen[0]


def test_fsync_unsupported_is_not_fatal_but_disk_full_is(tmp_path, monkeypatch):
    ib = Inbox(str(tmp_path), is_win=False)

    def unsupported(fd):
        raise OSError(errno.EINVAL, "Invalid argument")
    monkeypatch.setattr(ferry.os, "fsync", unsupported)
    assert up(ib, b"data")["result"] == "saved"

    def full(fd):
        raise OSError(errno.ENOSPC, "No space left on device")
    monkeypatch.setattr(ferry.os, "fsync", full)
    with pytest.raises(ferry.DiskFull):
        up(ib, b"other", name="b.jpg")
    assert files(tmp_path) == ["a.jpg"]


def test_inbox_folder_deleted_while_running_is_recreated(tmp_path):
    """Audit O2: tidying away the inbox folder must not break uploads until restart."""
    d = tmp_path / "Phone"
    d.mkdir()
    logs = []
    ib = Inbox(str(d), log=lambda *a: logs.append(a), is_win=False)
    d.rmdir()
    assert ib.precheck("a.jpg", 3, None)["ok"]
    assert up(ib, b"one")["result"] == "saved"
    assert files(d) == ["a.jpg"]
    assert any("recreated" in a[-1] for a in logs)


def test_inbox_folder_unusable_gives_write_error_with_unread_body(tmp_path):
    blocker = tmp_path / "Phone"
    blocker.write_bytes(b"a file where the folder should be")
    ib = Inbox(str(blocker), is_win=False)
    with pytest.raises(ferry.WriteError) as ei:
        up(ib, b"12345")
    assert ei.value.remaining == 5                   # nothing was read: the handler must drain it
