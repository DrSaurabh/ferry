"""DeviceLogStore: rotation x2 and the logs-folder cap (2.1 plan §1.1, M12). Small caps via parameters."""
import os
import time

import ferry


def body(start, n, pad=80):
    return "".join("2026-11-24 02:01:03.456 #%d s=7f3a diag msg=%s\n" % (i, "x" * pad) for i in range(start, start + n))


def test_rotation_keeps_two_files(tmp_path):
    st = ferry.DeviceLogStore(str(tmp_path), is_win=False, max_bytes=5_000, total_cap=10 ** 9)
    seq = 1
    for _ in range(20):
        st.append("Phone", "a3f9", seq, body(seq, 10))
        seq += 10
    d = tmp_path / "Phone (a3f9)"
    names = sorted(os.listdir(str(d)))
    assert names == [ferry.DEVICE_LOG_SEQ_NAME, ferry.DEVICE_LOG_OLD_NAME, ferry.DEVICE_LOG_NAME]
    assert os.path.getsize(str(d / ferry.DEVICE_LOG_NAME)) <= 5_000
    assert os.path.getsize(str(d / ferry.DEVICE_LOG_OLD_NAME)) <= 5_000
    assert ("#%d " % (seq - 1)) in (d / ferry.DEVICE_LOG_NAME).read_text()


def test_cap_never_deletes_zip_or_open_file(tmp_path):
    (tmp_path / "ferry-logs-20261124-020000.zip").write_bytes(b"z" * 6_000)
    (tmp_path / ferry.LOG_NAME).write_bytes(b"p" * 3_000)           # the PC's own log (open)
    st = ferry.DeviceLogStore(str(tmp_path), is_win=False, max_bytes=4_000, total_cap=10 ** 9)
    seq = 1
    for _ in range(6):                                              # an older device: current + rotated
        st.append("Old TV", "0001", seq, body(seq, 10))
        seq += 10
    old_dir = tmp_path / "Old TV (0001)"
    os.utime(str(old_dir / ferry.DEVICE_LOG_OLD_NAME), (time.time() - 3600, time.time() - 3600))
    os.utime(str(old_dir / ferry.DEVICE_LOG_NAME), (time.time() - 1800, time.time() - 1800))
    st.total_cap = 15_000
    seq2 = 1
    for _ in range(6):
        st.append("Phone", "a3f9", seq2, body(seq2, 10))
        seq2 += 10
    assert (tmp_path / "ferry-logs-20261124-020000.zip").is_file()
    assert (tmp_path / ferry.LOG_NAME).is_file()
    assert (tmp_path / "Phone (a3f9)" / ferry.DEVICE_LOG_NAME).is_file()            # being written
    assert not (old_dir / ferry.DEVICE_LOG_OLD_NAME).exists()                         # oldest rotated went first
    total = sum(os.path.getsize(os.path.join(b, n)) for b, _d, ns in os.walk(str(tmp_path)) for n in ns)
    assert total <= 15_000


def test_cap_prefers_rotated_files(tmp_path):
    st = ferry.DeviceLogStore(str(tmp_path), is_win=False, max_bytes=3_000, total_cap=10 ** 9)
    seq = 1
    for _ in range(5):
        st.append("A", "000a", seq, body(seq, 10))
        st.append("B", "000b", seq, body(seq, 10))
        seq += 10
    st.total_cap = sum(os.path.getsize(os.path.join(b, n)) for b, _d, ns in os.walk(str(tmp_path)) for n in ns) - 100
    st.append("A", "000a", seq, body(seq, 1))
    left = {(os.path.basename(b), n) for b, _d, ns in os.walk(str(tmp_path)) for n in ns}
    assert ("A (000a)", ferry.DEVICE_LOG_NAME) in left and ("B (000b)", ferry.DEVICE_LOG_NAME) in left
    assert not {("A (000a)", ferry.DEVICE_LOG_OLD_NAME), ("B (000b)", ferry.DEVICE_LOG_OLD_NAME)} <= left


def test_lines_without_seq_kept_unless_repeat(tmp_path):
    st = ferry.DeviceLogStore(str(tmp_path), is_win=False)
    assert st.append("P", "00aa", 1, body(1, 2)) == (2, 2)
    assert st.append("P", "00aa", 3, "2026-11-24 02:01:04.000 gap from=3 to=9\n" + body(10, 1)) == (2, 10)
    assert st.append("P", "00aa", 3, "free text without seq\n") == (0, 10)   # from <= last: a repeat
