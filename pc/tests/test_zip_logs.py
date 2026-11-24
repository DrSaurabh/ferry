"""2.1 (L7, M12): Z zips the logs folder; zips never include zips."""
import io
import time
import zipfile

import ferry


def test_zip_logs(tmp_path):
    logs = tmp_path / "logs"
    (logs / "vivo V2413 (a3f9)").mkdir(parents=True)
    (logs / "ferry-log.txt").write_bytes(b"pc line\n")
    (logs / "vivo V2413 (a3f9)" / "app-log.txt").write_bytes(b"phone line\n")
    (logs / "ferry-logs-20200101-000000.zip").write_bytes(b"old zip")
    now = time.mktime((2026, 11, 24, 9, 5, 7, 0, 0, -1))
    path = ferry.zip_logs(str(logs), now)
    assert path == str(logs / "ferry-logs-20261124-090507.zip")
    with zipfile.ZipFile(path) as z:
        assert sorted(z.namelist()) == ["ferry-log.txt", "vivo V2413 (a3f9)/app-log.txt"]
        assert z.read("vivo V2413 (a3f9)/app-log.txt") == b"phone line\n"
        assert all(i.compress_type == zipfile.ZIP_DEFLATED for i in z.infolist())
    assert not any(p.name.endswith(".tmp") for p in logs.iterdir())
    path2 = ferry.zip_logs(str(logs), now + 1)
    with zipfile.ZipFile(path2) as z:
        assert not any(n.endswith(".zip") for n in z.namelist())


def test_zip_to_console(tmp_path):
    out = io.StringIO()
    c = ferry.Console(stream=out)
    p = ferry.zip_logs_to_console(c, str(tmp_path / "logs"))
    c.stop()
    assert p and "Logs for Claude: " + p in out.getvalue()
