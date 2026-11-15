import io
import os
import struct
import subprocess
import sys
import zipfile

import pytest

import ferry
from ferry import OutFile, zip_write, zip_pairs, zip_length, file_source, GuardedWriter, CountingSink

SLOW = os.environ.get("FERRY_SLOW") == "1"


def outfile(path, name=None, i=1):
    st = os.stat(path)
    return OutFile(i, name or os.path.basename(path), str(path), st.st_size, st.st_mtime_ns, "other")


class WriteOnly:
    """Non-seekable sink like a socket, keeping all bytes."""
    def __init__(self):
        self.buf = io.BytesIO()

    def write(self, b):
        self.buf.write(b)
        return len(b)

    def flush(self):
        pass


def real_bytes(files):
    raw = WriteOnly()
    g = GuardedWriter(raw)
    zip_write(g, zip_pairs(files), file_source)
    return raw.buf.getvalue()


def make(tmp_path, specs):
    out = []
    for i, (name, size, mtime) in enumerate(specs):
        p = tmp_path / name
        with open(p, "wb") as f:
            f.write(os.urandom(min(size, 4096)) + b"\0" * max(0, size - 4096))
        if mtime is not None:
            os.utime(p, (abs(mtime), mtime) if mtime >= 0 else (0, 0))
        out.append(outfile(p, name, i + 1))
    return out


def test_length_matches_small(tmp_path):
    files = make(tmp_path, [("empty.txt", 0, None), ("one.bin", 1, None), ("naïve résumé.pdf", 65536, None),
                            ("big.bin", 3 << 20, None), ("old.txt", 10, 100), ("future.txt", 10, 4102444800 * 2)])
    data = real_bytes(files)
    assert zip_length(files) == len(data)
    zf = zipfile.ZipFile(io.BytesIO(data))
    assert zf.testzip() is None
    assert all(i.flag_bits & 0x08 for i in zf.infolist())   # streaming mode (data descriptors)
    assert zf.read("naïve résumé.pdf")[:4096] == open(tmp_path / "naïve résumé.pdf", "rb").read()[:4096]
    assert zf.getinfo("old.txt").date_time[0] == 1980


def test_unzip_t(tmp_path):
    files = make(tmp_path, [("a.txt", 100, None), ("b न.jpg", 200000, None)])
    p = tmp_path / "out.zip"
    p.write_bytes(real_bytes(files))
    r = subprocess.run(["unzip", "-t", str(p)], capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


def test_dry_run_uses_fast_path(tmp_path):
    files = make(tmp_path, [("x.bin", 10, None)])
    sink = CountingSink()
    zf_called = {}
    orig = ferry._can_fake

    def spy(zf, w):
        zf_called["v"] = orig(zf, w)
        return zf_called["v"]
    ferry._can_fake = spy
    try:
        zip_write(sink, zip_pairs(files), ferry.zero_source, dry=True)
    finally:
        ferry._can_fake = orig
    assert zf_called["v"] is True


def test_dry_run_fallback_matches(tmp_path, monkeypatch):
    files = make(tmp_path, [("x.bin", 5000, None), ("y.bin", 7, None)])
    monkeypatch.setattr(ferry, "_can_fake", lambda zf, w: False)
    sink = CountingSink()
    zip_write(sink, zip_pairs(files), ferry.zero_source, dry=True)
    assert sink.n == len(real_bytes(files))


def test_abort_midway_no_eocd_no_traceback(tmp_path):
    code = r'''
import sys, os, io
sys.path.insert(0, %r)
import ferry
d = %r
ps = []
for i, n in enumerate(["a.bin", "b.bin", "c.bin"]):
    p = os.path.join(d, n)
    with open(p, "wb") as fh: fh.write(os.urandom(300000))
    ps.append(ferry.OutFile(i+1, n, p, 300000, os.stat(p).st_mtime_ns, "other"))
class Raw:
    def __init__(s): s.b = io.BytesIO()
    def write(s, x): s.b.write(x); return len(x)
    def flush(s): pass
def src(of):
    for k, chunk in enumerate(ferry.file_source(of)):
        yield chunk
    if of.name == "b.bin":
        raise ferry.ZipAbort("b changed")
raw = Raw(); g = ferry.GuardedWriter(raw)
try:
    ferry.zip_write(g, ferry.zip_pairs(ps), src)
except ferry.ZipAbort:
    pass
import gc; gc.collect()
data = raw.b.getvalue()
print(len(data), data.count(b"PK\x07\x08"), data.count(b"PK\x05\x06"), ferry.zip_length(ps))
''' % (os.path.dirname(os.path.dirname(os.path.abspath(__file__))), str(tmp_path))
    r = subprocess.run([sys.executable, "-X", "dev", "-c", code], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    assert r.stderr.strip() == "", r.stderr
    n, descs, eocd, full = map(int, r.stdout.split())
    assert descs == 1           # only a.bin completed
    assert eocd == 0
    assert n < full


class TailSink:
    def __init__(self, keep=1 << 16):
        self.n, self.keep, self.tail, self.aborted = 0, keep, b"", False

    def write(self, b):
        b = bytes(b)
        self.n += len(b)
        self.tail = (self.tail + b)[-self.keep:]
        return len(b)

    def flush(self):
        pass


def sparse(tmp_path, name, size, i):
    p = tmp_path / name
    with open(p, "wb") as f:
        f.truncate(size)
    return outfile(p, name, i)


@pytest.mark.skipif(not SLOW, reason="set FERRY_SLOW=1 for multi-GB zip tests")
@pytest.mark.parametrize("sizes", [[(2 << 30) - 1], [2 << 30], [(4 << 30) + 1], [3 << 30, 3 << 30]])
def test_length_matches_large(tmp_path, sizes):
    files = [sparse(tmp_path, "f%d.bin" % i, s, i + 1) for i, s in enumerate(sizes)]
    sink = TailSink()
    g = GuardedWriter(sink)
    zip_write(g, zip_pairs(files), file_source)
    assert zip_length(files) == sink.n
    tail = sink.tail
    if sink.n > 0xFFFFFFFF:
        assert b"PK\x06\x06" in tail and b"PK\x06\x07" in tail   # ZIP64 EOCD + locator
    eocd = tail.rfind(b"PK\x05\x06")
    assert eocd >= 0
    count = struct.unpack("<H", tail[eocd + 10:eocd + 12])[0]
    assert count == len(files)
