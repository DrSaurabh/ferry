"""Windows code paths exercised on Linux with fakes, plus folders, CLI and launchers."""
import argparse
import os
import re
import subprocess
import sys
import threading
import time
import types

import pytest

import ferry

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class FakeFn:
    def __init__(self, name, log, ret=1, raise_=None):
        self.name, self.log, self.ret, self.raise_ = name, log, ret, raise_

    def __call__(self, *a):
        self.log.append(self.name)
        if self.raise_:
            raise self.raise_
        return self.ret


class FakeDll:
    def __init__(self, log, ret=1, raise_=None):
        self.log, self.ret, self.raise_ = log, ret, raise_

    def __getattr__(self, name):
        return FakeFn(name, self.log, self.ret, self.raise_)


def fake_windll(ret=1, raise_=None):
    log = []
    return types.SimpleNamespace(kernel32=FakeDll(log, ret, raise_), shell32=FakeDll(log, ret, raise_),
                                 ole32=FakeDll(log, ret, raise_)), log


def test_console_setup_calls_and_degrades():
    w, log = fake_windll()
    r = ferry.win_console_setup(w)
    assert r == {"ansi": True, "quickedit_off": True}
    assert log.count("SetConsoleMode") == 2 and "SetConsoleTitleW" in log
    w, log = fake_windll(raise_=OSError("no console"))
    assert ferry.win_console_setup(w) == {"ansi": False, "quickedit_off": False}
    w, _ = fake_windll(ret=0)
    assert ferry.win_console_setup(w) == {"ansi": False, "quickedit_off": False}


def test_known_folder_degrades():
    w, log = fake_windll(ret=1)          # HRESULT != 0 -> None
    assert ferry.win_known_folder(ferry.FOLDERID_DOWNLOADS, w) is None
    w, _ = fake_windll(raise_=OSError("x"))
    assert ferry.win_known_folder(ferry.FOLDERID_DOWNLOADS, w) is None


def test_keep_awake():
    w, log = fake_windll(ret=5)
    assert ferry.win_keep_awake(True, w) is True and log == ["SetThreadExecutionState"]
    w, _ = fake_windll(raise_=OSError())
    assert ferry.win_keep_awake(True, w) is False


def test_network_category_parsing_and_command():
    assert ferry.parse_network_category("\r\nPublic\r\n") == "Public"
    assert ferry.parse_network_category("Private") == "Private"
    assert ferry.parse_network_category("") is None
    seen = {}

    def run(cmd, **kw):
        seen["cmd"] = cmd
        return types.SimpleNamespace(stdout="Public\n")
    assert ferry.win_network_category("192.168.1.5", run=run) == "Public"
    assert "192.168.1.5" in seen["cmd"][-1]
    assert ferry.win_network_category("1.2.3.4'; rm -rf", run=run) is None   # rejected before running

    def slow(cmd, **kw):
        raise subprocess.TimeoutExpired(cmd, 8)
    assert ferry.win_network_category("192.168.1.5", run=slow) is None


def args(**kw):
    a = argparse.Namespace(port=8765, inbox=None, outbox=None, no_open=False)
    a.__dict__.update(kw)
    return a


@pytest.mark.skipif(ferry.IS_WIN, reason="Linux bytes-path / XDG decoding")
def test_resolve_folders_linux(tmp_path):
    home = tmp_path / "home"
    (home / ".config").mkdir(parents=True)
    (home / ".config" / "user-dirs.dirs").write_text('XDG_DOWNLOAD_DIR="$HOME/Téléchargements"\nXDG_DESKTOP_DIR="$HOME"\n')
    st = ferry.State(str(tmp_path / "s.json"))
    c = ferry.resolve_folders(args(), st, is_win=False, env={}, home=str(home))
    assert c.inbox == str(home / "Téléchargements" / "Phone")
    assert c.outbox == str(home / "Desktop" / "Ferry outbox")      # XDG_DESKTOP_DIR=$HOME means disabled
    assert c.outbox_created and c.first_run and os.path.isdir(c.inbox)
    assert c.inbox_label == os.path.join("Téléchargements", "Phone")
    c2 = ferry.resolve_folders(args(), st, is_win=False, env={}, home=str(home))
    assert not c2.outbox_created


def test_resolve_folders_windows_fake(tmp_path):
    dl, desk = tmp_path / "D" / "Downloads", tmp_path / "OneDrive" / "Desktop"

    def known(guid):
        return str(dl) if guid == ferry.FOLDERID_DOWNLOADS else str(desk)
    st = ferry.State(str(tmp_path / "s.json"))
    c = ferry.resolve_folders(args(), st, is_win=True, env={"OneDrive": str(tmp_path / "OneDrive")}, known=known,
                              home=str(tmp_path))
    assert c.inbox == str(dl / "Phone") and c.outbox == str(desk / "Ferry outbox")
    assert c.onedrive_outbox is True
    c = ferry.resolve_folders(args(), st, is_win=True, env={"USERPROFILE": str(tmp_path / "U")},
                              known=lambda g: None, home=str(tmp_path))
    assert c.inbox == str(tmp_path / "U" / "Downloads" / "Phone") and not c.onedrive_outbox


def test_resolve_overrides_and_errors(tmp_path):
    st = ferry.State(str(tmp_path / "s.json"))
    c = ferry.resolve_folders(args(inbox=str(tmp_path / "in"), outbox=str(tmp_path / "out")), st, is_win=False, env={})
    assert c.inbox == str(tmp_path / "in") and c.inbox_label == str(tmp_path / "in")
    with pytest.raises(ferry.FatalError):
        ferry.resolve_folders(args(inbox=str(tmp_path / "x"), outbox=str(tmp_path / "x")), st, is_win=False, env={})
    (tmp_path / "file").write_text("x")
    with pytest.raises(ferry.FatalError) as ei:
        ferry.resolve_folders(args(inbox=str(tmp_path / "file" / "sub"), outbox=str(tmp_path / "o2")), st,
                              is_win=False, env={})
    assert "Can't use" in str(ei.value)


def test_cli():
    a = ferry.parse_args(["--port", "8800", "--no-open"])
    assert a.port == 8800 and a.no_open
    with pytest.raises(SystemExit):
        ferry.parse_args(["--port", "0"])
    r = subprocess.run([sys.executable, os.path.join(ROOT, "ferry.py"), "--help"], capture_output=True, text=True)
    assert r.returncode == 0 and ".local" in r.stdout and "--selftest" in r.stdout
    r = subprocess.run([sys.executable, os.path.join(ROOT, "ferry.py"), "--version"], capture_output=True, text=True)
    assert ferry.VERSION in r.stdout


def test_launchers_static():
    bat = open(os.path.join(ROOT, "Ferry.bat"), "rb").read()
    assert b"\r\n" in bat and b"\n" not in bat.replace(b"\r\n", b"")
    bat.decode("ascii")
    text = bat.decode("ascii")
    py_line = [l for l in text.splitlines() if "ferry.py %*" in l][0]
    assert "&&" not in py_line and "||" not in py_line
    assert "if errorlevel 1 pause" in text and "sys.version_info>=(3,8)" in text
    sh = open(os.path.join(ROOT, "ferry.sh"), "rb").read()
    assert b"\r" not in sh and sh.startswith(b"#!/usr/bin/env bash")
    if not ferry.IS_WIN:      # "bash" on a Windows runner is the WSL stub; the Linux legs check the syntax
        assert subprocess.run(["bash", "-n", os.path.join(ROOT, "ferry.sh")]).returncode == 0
    assert os.access(os.path.join(ROOT, "ferry.sh"), os.X_OK)
    # the line endings above only survive a Windows checkout because of these two rules
    rules = open(os.path.join(os.path.dirname(ROOT), ".gitattributes"), encoding="utf-8").read().split("\n")
    rules = [" ".join(l.split()) for l in rules]
    assert "*.sh text eol=lf" in rules and "*.bat text eol=crlf" in rules


def test_selftest_runs(tmp_path, monkeypatch):
    env = dict(os.environ, FERRY_NO_WAIT="1", HOME=str(tmp_path), XDG_CONFIG_HOME=str(tmp_path / "cfg"))
    r = subprocess.run([sys.executable, os.path.join(ROOT, "ferry.py"), "--selftest"], capture_output=True, text=True,
                       env=env, cwd=str(tmp_path), timeout=60)
    assert "Saving rules" in r.stdout and "Only one Ferry per port" in r.stdout
    assert "Saving rules                           PASS" in r.stdout
    assert "Only one Ferry per port                PASS" in r.stdout
    assert "QR code                                PASS" in r.stdout


def test_main_starts_and_stops_quickly(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "cfg"))
    monkeypatch.setattr(ferry, "STATE_FILE", str(tmp_path / "state.json"))
    monkeypatch.setattr(ferry.State, "load", classmethod(lambda cls, path=None: cls(str(tmp_path / "state.json"))))
    # Windows resolves the folders through SHGetKnownFolderPath, not HOME: point it at tmp_path too.
    # resolve_folders binds win_known_folder as a default argument, so the fake is passed in explicitly.
    def known(guid):
        return str(tmp_path / ("Downloads" if guid == ferry.FOLDERID_DOWNLOADS else "Desktop"))
    monkeypatch.setattr(ferry, "win_known_folder", known)
    real_resolve = ferry.resolve_folders
    monkeypatch.setattr(ferry, "resolve_folders", lambda a, st, **kw: real_resolve(a, st, **dict(kw, known=known)))
    import socket
    with socket.socket() as so:
        so.bind(("127.0.0.1", 0))
        port = so.getsockname()[1]
    stop = threading.Event()
    rc = {}
    t = threading.Thread(target=lambda: rc.setdefault("v", ferry.main(["--port", str(port), "--no-open"], stop_event=stop)))
    t.start()
    time.sleep(1.0)
    t0 = time.time()
    stop.set()
    t.join(5)
    assert not t.is_alive() and rc["v"] == 0
    assert time.time() - t0 < 3
    assert os.path.isdir(tmp_path / "Downloads" / "Phone") and os.path.isdir(tmp_path / "Desktop" / "Ferry outbox")


def test_message_catalogue_present():
    src = open(os.path.join(ROOT, "ferry.py"), encoding="utf-8").read()
    page = open(os.path.join(ROOT, "tools", "page.html"), encoding="utf-8").read()
    for msg in ("is already running in another window", "has reserved port", "is used by another program",
                "No network connection found", "Can't use", "PC disk is full", "cancelled or connection lost",
                "That file is no longer in the outbox.", "Windows treats this network as Public",
                "Phone can't connect? Check", "Your PC's address changed", "Ferry hit an unexpected error",
                "folders aren't sent", "syncs to OneDrive"):
        assert msg in src, msg
    for msg in ("Can't reach the PC. Is Ferry still running, and is the phone on the same Wi-Fi?",
                "PC disk is full", "Paused while the screen was off", "Stop sending? Files already saved stay on the PC.",
                "Nothing to send yet.", "Folders aren't sent.", "If Chrome asks to allow multiple downloads",
                "Keep this screen on until it finishes.", "Add to Home screen"):
        assert msg in page, msg
    assert re.search(r"Extract All", open(os.path.join(ROOT, "Ferry.bat")).read())
