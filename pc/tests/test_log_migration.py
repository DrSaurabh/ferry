"""2.0's ferry-log.txt moves into <data>/logs at start (2.1 M12, CP110)."""
import os

import ferry


def test_move(tmp_path):
    (tmp_path / ferry.LOG_NAME).write_text("old 1\n", encoding="utf-8")
    (tmp_path / ferry.LOG_OLD_NAME).write_text("old 0\n", encoding="utf-8")
    notes = []
    folder = ferry.migrate_old_logs(str(tmp_path), notes.append)
    assert folder == str(tmp_path / ferry.LOGS_DIR_NAME) and notes == []
    assert (tmp_path / "logs" / ferry.LOG_NAME).read_text(encoding="utf-8") == "old 1\n"
    assert (tmp_path / "logs" / ferry.LOG_OLD_NAME).read_text(encoding="utf-8") == "old 0\n"
    assert not (tmp_path / ferry.LOG_NAME).exists() and not (tmp_path / ferry.LOG_OLD_NAME).exists()


def test_nothing_to_move(tmp_path):
    notes = []
    assert ferry.migrate_old_logs(str(tmp_path), notes.append) == str(tmp_path / "logs")
    assert os.path.isdir(str(tmp_path / "logs")) and notes == []


def test_failure_keeps_old_place(tmp_path):
    (tmp_path / ferry.LOG_NAME).write_text("old\n", encoding="utf-8")
    notes = []

    def refuse(src, dst):
        raise PermissionError(13, "Access is denied")
    folder = ferry.migrate_old_logs(str(tmp_path), notes.append, replace=refuse)
    assert folder == str(tmp_path)
    assert (tmp_path / ferry.LOG_NAME).read_text(encoding="utf-8") == "old\n"
    assert len(notes) == 1 and "stays next to ferry.py" in notes[0]


def test_main_moves_the_old_log(tmp_path):
    from helpers import FerryProcess, await_log
    data = tmp_path / "data"
    data.mkdir()
    (data / ferry.LOG_NAME).write_text("2025-01-01 10:00:00.000\t-\t-\tEVENT\t-\t-\t-\t-\t-\tFerry 2.0.0 started\t-\n",
                                       encoding="utf-8")
    p = FerryProcess(tmp_path, "--port", "0", "--discovery-port", "off")
    try:
        lines = await_log(p.port, p.log_path)
        assert "Ferry 2.0.0 started" in lines[0] and any("Ferry 2.1.0 started" in l for l in lines)
        assert not (data / ferry.LOG_NAME).exists()
    finally:
        p.close()
