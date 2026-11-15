#!/usr/bin/env python3
# Ferry - move files between your phone / Fire TV and this PC over Wi-Fi.
# Version: see VERSION below.
#
# MIT License
# Copyright (c) 2026 Saurabh
# Permission is hereby granted, free of charge, to any person obtaining a copy of this
# software and associated documentation files (the "Software"), to deal in the Software
# without restriction, including without limitation the rights to use, copy, modify, merge,
# publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons
# to whom the Software is furnished to do so, subject to the following conditions: the above
# copyright notice and this permission notice shall be included in all copies or substantial
# portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
#
# Requires Python 3.8+ and nothing else (standard library only). The QR code is drawn with
# the bundled qrcodegen.py (Project Nayuki, MIT). If that file is missing Ferry still works.
#
# Start it with Ferry.bat (Windows) or ferry.sh (Linux), or:  python ferry.py

from __future__ import annotations

import argparse
import errno
import hashlib
import io
import ipaddress
import json
import os
import queue
import re
import shutil
import socket
import socketserver
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import unicodedata
import zipfile
from dataclasses import dataclass
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, quote, urlsplit

try:  # bundled QR generator (optional)
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import qrcodegen  # type: ignore
except Exception:  # missing, or incompatible: run without a QR code
    qrcodegen = None

# =============================================================================================
# Constants
# =============================================================================================

VERSION = "1.0.2"
PROTOCOL = 1
DEFAULT_PORT = 8765
CHUNK = 1 << 20                  # 1 MiB
SOCKET_TIMEOUT = 60              # seconds of silence before a connection is dropped
DISK_MARGIN = 64 << 20           # keep this much free space on the PC
DRAIN_LIMIT = 64 << 20           # max bytes read-and-discarded before an error reply
MAX_NAME_BYTES = 200
WIN_MAX_PATH = 250
STABLE_SECONDS = 2.0             # outbox: file unchanged this long before it is offered
WATCH_INTERVAL = 1.0
WATCHDOG_SECONDS = 60
IS_WIN = os.name == "nt"
HERE = os.path.dirname(os.path.abspath(__file__))
STATE_FILE = os.path.join(HERE, "ferry-state.json")
ERROR_LOG = os.path.join(HERE, "ferry-error.log")
SELFTEST_FILE = os.path.join(HERE, "ferry-selftest.txt")

TEMP_PREFIX = ".ferry-"
TEMP_SUFFIX = ".part"
IGNORE_NAMES = {"desktop.ini", "thumbs.db", ".ds_store"}
IGNORE_SUFFIXES = (".part", ".crdownload", ".tmp", ".partial", ".!ut", ".download")
SHORTCUT_SUFFIXES = (".lnk", ".url")
FILE_ATTRIBUTE_HIDDEN = 0x2
FILE_ATTRIBUTE_SYSTEM = 0x4
FILE_ATTRIBUTE_OFFLINE = 0x1000
FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS = 0x400000

MIME_OVERRIDES = {
    ".apk": "application/vnd.android.package-archive",
    ".mp4": "video/mp4", ".m4v": "video/mp4", ".mkv": "video/x-matroska",
    ".mov": "video/quicktime", ".webm": "video/webm", ".avi": "video/x-msvideo",
    ".3gp": "video/3gpp", ".ts": "video/mp2t",
    ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".gif": "image/gif",
    ".webp": "image/webp", ".heic": "image/heic", ".heif": "image/heif", ".dng": "image/x-adobe-dng",
    ".pdf": "application/pdf", ".zip": "application/zip", ".txt": "text/plain; charset=utf-8",
    ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".srt": "application/x-subrip",
}
PLAYABLE_EXT = {".mp4", ".m4v", ".webm"}
VIDEO_EXT = PLAYABLE_EXT | {".mkv", ".mov", ".avi", ".3gp", ".ts", ".wmv", ".flv"}
IMAGE_EXT = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".dng", ".bmp"}

RESERVED_NAMES = {"CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"} | \
    {"COM" + c for c in "0123456789¹²³"} | {"LPT" + c for c in "0123456789¹²³"}
INVALID_CHARS = set('<>:"/\\|?*')


class FatalError(Exception):
    """A startup problem with a friendly message for the user."""


class UploadAborted(Exception):
    """The phone stopped sending (cancelled, screen off, Wi-Fi drop)."""


class DiskFull(Exception):
    def __init__(self, remaining: int):
        super().__init__("disk full")
        self.remaining = remaining


class WriteError(Exception):
    def __init__(self, message: str, remaining: int):
        super().__init__(message)
        self.remaining = remaining


class ZipAbort(Exception):
    """A file changed or vanished while being zipped."""


class ShortRead(Exception):
    """A file shrank while being sent."""


# =============================================================================================
# Small helpers
# =============================================================================================

def fmt_size(n: float) -> str:
    n = float(n)
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1000 or unit == "GB":
            if unit == "B":
                return "%d B" % n
            return "%.1f %s" % (n, unit)
        n /= 1000.0
    return "%.1f GB" % n


def fmt_speed(nbytes: int, seconds: float) -> str:
    if seconds <= 0.05 or nbytes < 256 * 1024:
        return ""
    return "%.1f MB/s" % (nbytes / seconds / 1e6)


def mid_trunc(s: str, width: int) -> str:
    if len(s) <= width:
        return s
    keep = width - 1
    head = (keep + 1) // 2
    return s[:head] + "…" + s[len(s) - (keep - head):]


def safe_ts(t: float) -> float:
    try:
        t = float(t)
    except (TypeError, ValueError):
        return 0.0
    return min(max(t, 0.0), 32503680000.0)


def parse_int(v: Optional[str]) -> Optional[int]:
    if v is None or v == "":
        return None
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def unlink_quiet(path: str) -> None:
    try:
        os.unlink(path)
    except OSError:
        pass


def utf16_len(s: str) -> int:
    return len(s.encode("utf-16-le")) // 2


def file_kind(name: str) -> str:
    ext = os.path.splitext(name)[1].lower()
    if ext in PLAYABLE_EXT:
        return "video_playable"
    if ext in VIDEO_EXT:
        return "video"
    if ext in IMAGE_EXT:
        return "image"
    if ext == ".apk":
        return "apk"
    return "other"


def guess_type(name: str) -> str:
    ext = os.path.splitext(name)[1].lower()
    if ext in MIME_OVERRIDES:
        return MIME_OVERRIDES[ext]
    import mimetypes
    t = mimetypes.guess_type(name)[0]
    return t or "application/octet-stream"


def content_disposition(kind: str, name: str) -> str:
    ascii_name = "".join(c if 32 <= ord(c) < 127 and c not in '"\\' else "_" for c in name)
    return "%s; filename=\"%s\"; filename*=UTF-8''%s" % (kind, ascii_name, quote(name, safe=""))


def device_type(ua: str) -> str:
    ua = ua or ""
    if "AFT" in ua or "Silk" in ua:
        return "Fire TV"
    if "Android" in ua or "iPhone" in ua or "iPad" in ua:
        return "phone"
    return "browser"


# =============================================================================================
# Console: the ONLY writer to stdout (a stuck console must never block a transfer)
# =============================================================================================

class Console:
    def __init__(self, ansi: bool = False, stream=None):
        self.ansi = ansi
        self.stream = stream if stream is not None else sys.stdout
        self.q: "queue.Queue[Optional[str]]" = queue.Queue()
        self._errors_seen: set = set()
        self.t = threading.Thread(target=self._run, name="ferry-console", daemon=True)
        self.t.start()

    def _run(self) -> None:
        while True:
            item = self.q.get()
            if item is None:
                break
            try:
                self.stream.write(item + "\n")
                self.stream.flush()
            except Exception:
                pass

    def print(self, text: str = "") -> None:
        for line in str(text).split("\n"):
            self.q.put(line)

    def log(self, area: str, verb: str, name: str = "", detail: str = "") -> None:
        ts = time.strftime("%H:%M:%S")
        line = "  [%s] %-14s%-10s%s" % (ts, area, verb, mid_trunc(name, 36))
        if detail:
            line += "  " + detail
        self.q.put(line.rstrip())

    def note(self, text: str) -> None:
        self.q.put("  [%s] %s" % (time.strftime("%H:%M:%S"), text))

    def error_once(self, text: str) -> None:
        if text not in self._errors_seen:
            self._errors_seen.add(text)
            self.note("error  " + text)

    def stop(self, timeout: float = 1.0) -> None:
        self.q.put(None)
        self.t.join(timeout)


# =============================================================================================
# Windows platform helpers (every function degrades silently; failure never stops Ferry)
# =============================================================================================

FOLDERID_DOWNLOADS = "{374DE290-123F-4565-9164-39C4925E467B}"
FOLDERID_DESKTOP = "{B4BFCC3A-DB2C-424C-B029-7FE99A87C641}"


def _get_windll(windll=None):
    if windll is not None:
        return windll
    import ctypes
    return ctypes.windll  # type: ignore[attr-defined]


def win_console_setup(windll=None) -> Dict[str, bool]:
    """Enable ANSI colours, turn OFF QuickEdit (a click would freeze output), set title."""
    result = {"ansi": False, "quickedit_off": False}
    try:
        import ctypes
        k = _get_windll(windll).kernel32
        try:
            k.GetStdHandle.restype = ctypes.c_void_p
        except Exception:
            pass
        mode = ctypes.c_uint32()
        hout = k.GetStdHandle(-11)
        if hout and k.GetConsoleMode(ctypes.c_void_p(hout), ctypes.byref(mode)):
            if k.SetConsoleMode(ctypes.c_void_p(hout), mode.value | 0x0004):
                result["ansi"] = True
        hin = k.GetStdHandle(-10)
        mode_in = ctypes.c_uint32()
        if hin and k.GetConsoleMode(ctypes.c_void_p(hin), ctypes.byref(mode_in)):
            new = (mode_in.value | 0x0080) & ~0x0040   # EXTENDED_FLAGS on, QUICK_EDIT off
            if k.SetConsoleMode(ctypes.c_void_p(hin), new):
                result["quickedit_off"] = True
        try:
            k.SetConsoleTitleW("Ferry")
        except Exception:
            pass
    except Exception:
        pass
    return result


def win_console_modes(windll=None) -> Optional[Tuple[int, int]]:
    try:
        import ctypes
        k = _get_windll(windll).kernel32
        k.GetStdHandle.restype = ctypes.c_void_p
        a, b = ctypes.c_uint32(), ctypes.c_uint32()
        hout, hin = k.GetStdHandle(-11), k.GetStdHandle(-10)
        if not (k.GetConsoleMode(ctypes.c_void_p(hout), ctypes.byref(a)) and
                k.GetConsoleMode(ctypes.c_void_p(hin), ctypes.byref(b))):
            return None
        return a.value, b.value
    except Exception:
        return None


def win_known_folder(guid: str, windll=None) -> Optional[str]:
    try:
        import ctypes
        import uuid

        class GUID(ctypes.Structure):
            _fields_ = [("data", ctypes.c_ubyte * 16)]

        g = GUID()
        ctypes.memmove(ctypes.byref(g), uuid.UUID(guid).bytes_le, 16)
        dll = _get_windll(windll)
        p = ctypes.c_wchar_p()
        hr = dll.shell32.SHGetKnownFolderPath(ctypes.byref(g), 0, None, ctypes.byref(p))
        if hr != 0:
            return None
        try:
            return p.value or None
        finally:
            dll.ole32.CoTaskMemFree(p)
    except Exception:
        return None


def win_keep_awake(on: bool, windll=None) -> bool:
    try:
        ES_CONTINUOUS, ES_SYSTEM_REQUIRED = 0x80000000, 0x00000001
        flags = ES_CONTINUOUS | ES_SYSTEM_REQUIRED if on else ES_CONTINUOUS
        return bool(_get_windll(windll).kernel32.SetThreadExecutionState(flags))
    except Exception:
        return False


def parse_network_category(out: str) -> Optional[str]:
    for line in (out or "").splitlines():
        s = line.strip()
        if s in ("Public", "Private", "DomainAuthenticated"):
            return s
    return None


def win_network_category(ip: str, run=subprocess.run) -> Optional[str]:
    try:
        ipaddress.ip_address(ip)  # validates; nothing else reaches the command line
        script = ("$i=(Get-NetIPAddress -IPAddress '%s' -ErrorAction Stop).InterfaceIndex; "
                  "(Get-NetConnectionProfile -InterfaceIndex $i -ErrorAction Stop).NetworkCategory" % ip)
        r = run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                capture_output=True, text=True, timeout=8,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        return parse_network_category(r.stdout)
    except Exception:
        return None


def open_folder(path: str) -> None:
    try:
        if IS_WIN:
            os.startfile(path)  # type: ignore[attr-defined]
        else:
            subprocess.Popen(["xdg-open", path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


def linux_user_dir(kind: str, home: Optional[str] = None, env=None) -> Optional[str]:
    """Read XDG_<KIND>_DIR from user-dirs.dirs (no subprocess)."""
    env = os.environ if env is None else env
    home = home or os.path.expanduser("~")
    cfg = env.get("XDG_CONFIG_HOME") or os.path.join(home, ".config")
    try:
        with open(os.path.join(cfg, "user-dirs.dirs"), encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                m = re.match(r'XDG_%s_DIR\s*=\s*"(.*)"\s*$' % kind, line)
                if m:
                    val = m.group(1).replace("$HOME", home)
                    if os.path.isabs(val) and os.path.normpath(val) != os.path.normpath(home):
                        return val
    except OSError:
        pass
    return None


def onedrive_roots(env=None) -> List[str]:
    env = os.environ if env is None else env
    roots = []
    for k in ("OneDrive", "OneDriveConsumer", "OneDriveCommercial"):
        v = env.get(k)
        if v:
            roots.append(os.path.normcase(os.path.normpath(v)))
    return roots


def is_under(path: str, root: str) -> bool:
    p = os.path.normcase(os.path.normpath(path))
    r = os.path.normcase(os.path.normpath(root))
    return p == r or p.startswith(r.rstrip("\\/") + os.sep)


# =============================================================================================
# Folders and persistent state
# =============================================================================================

@dataclass
class Config:
    port: int
    inbox: str
    outbox: str
    inbox_label: str
    outbox_label: str
    outbox_created: bool = False
    first_run: bool = False
    onedrive_outbox: bool = False
    no_open: bool = False


class State:
    def __init__(self, path: str = STATE_FILE):
        self.path = path
        self.data = {"last_ip": None, "first_run_done": False}

    @classmethod
    def load(cls, path: str = STATE_FILE) -> "State":
        s = cls(path)
        try:
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            if isinstance(d, dict):
                s.data.update({k: d[k] for k in ("last_ip", "first_run_done") if k in d})
        except Exception:
            pass
        return s

    def save(self) -> None:
        try:
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.data, f)
            os.replace(tmp, self.path)
        except Exception:
            pass


def _label(base: str, leaf: str) -> str:
    return os.path.join(os.path.basename(os.path.normpath(base)) or base, leaf)


def resolve_folders(args, state: State, is_win: bool = IS_WIN, env=None,
                    known=win_known_folder, home: Optional[str] = None) -> Config:
    env = os.environ if env is None else env
    home = home or os.path.expanduser("~")

    def base_for(win_guid: str, xdg: str, fallback: str) -> str:
        if is_win:
            return known(win_guid) or os.path.join(env.get("USERPROFILE") or home, fallback)
        return linux_user_dir(xdg, home, env) or os.path.join(home, fallback)

    if getattr(args, "inbox", None):
        inbox, inbox_label = os.path.abspath(args.inbox), os.path.abspath(args.inbox)
    else:
        b = base_for(FOLDERID_DOWNLOADS, "DOWNLOAD", "Downloads")
        inbox, inbox_label = os.path.join(b, "Phone"), _label(b, "Phone")
    if getattr(args, "outbox", None):
        outbox, outbox_label = os.path.abspath(args.outbox), os.path.abspath(args.outbox)
    else:
        b = base_for(FOLDERID_DESKTOP, "DESKTOP", "Desktop")
        outbox, outbox_label = os.path.join(b, "Ferry outbox"), _label(b, "Ferry outbox")

    if os.path.normcase(os.path.realpath(inbox)) == os.path.normcase(os.path.realpath(outbox)):
        raise FatalError("The inbox and outbox can't be the same folder (%s)." % inbox)

    outbox_existed = os.path.isdir(outbox)
    for p in (inbox, outbox):
        try:
            os.makedirs(p, exist_ok=True)
        except OSError as e:
            raise FatalError("Can't use %s: %s." % (p, e.strerror or e))
    probe = os.path.join(inbox, ".ferry-probe-%d" % os.getpid())
    try:
        with open(probe, "wb") as f:
            f.write(b"x")
        os.unlink(probe)
    except OSError as e:
        raise FatalError("Can't save files in %s: %s." % (inbox, e.strerror or e))

    onedrive = is_win and any(is_under(outbox, r) for r in onedrive_roots(env))
    cfg = Config(port=getattr(args, "port", DEFAULT_PORT) or DEFAULT_PORT,
                 inbox=inbox, outbox=outbox, inbox_label=inbox_label, outbox_label=outbox_label,
                 outbox_created=not outbox_existed,
                 first_run=(not state.data.get("first_run_done")) or not outbox_existed,
                 onedrive_outbox=onedrive, no_open=bool(getattr(args, "no_open", False)))
    return cfg


# =============================================================================================
# Network address detection
# =============================================================================================

def _is_lan(a: str) -> bool:
    try:
        ip = ipaddress.ip_address(a)
    except ValueError:
        return False
    return ip.version == 4 and ip.is_private and not ip.is_loopback and not ip.is_link_local


def rank_addresses(candidates, default_route: Optional[str]) -> List[str]:
    lan = sorted({a for a in candidates if _is_lan(a)})

    def key(a: str):
        if a.startswith("192.168."):
            cls = 0
        elif a.startswith("10."):
            cls = 1
        elif ipaddress.ip_address(a) in ipaddress.ip_network("172.16.0.0/12"):
            cls = 2
        else:
            cls = 3
        return (0 if a == default_route else 1, cls, a)

    return sorted(lan, key=key)


def default_route_ip() -> Optional[str]:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))   # UDP connect sends nothing; it just picks a route
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def detect_addresses() -> Tuple[Optional[str], List[str]]:
    cands = set()
    dr = default_route_ip()
    if dr:
        cands.add(dr)
    try:
        for ai in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            cands.add(ai[4][0])
    except OSError:
        pass
    ranked = rank_addresses(cands, dr)
    if not ranked:
        return None, []
    return ranked[0], ranked[1:]


def hostname_local() -> str:
    return socket.gethostname().split(".")[0].lower() + ".local"


# =============================================================================================
# QR code
# =============================================================================================

def qr_matrix(text: str) -> Optional[List[List[bool]]]:
    if qrcodegen is None:
        return None
    try:
        q = qrcodegen.QrCode.encode_text(text, qrcodegen.QrCode.Ecc.LOW)
        n = q.get_size()
        return [[q.get_module(x, y) for x in range(n)] for y in range(n)]
    except Exception:
        return None


def qr_console_lines(url: str, ansi: bool, indent: str = "      ") -> List[str]:
    m = qr_matrix(url)  # exact URL: phone cameras only treat lower-case "http://" as a link
    if m is None:
        return []
    border = 2
    size = len(m) + 2 * border

    def dark(x: int, y: int) -> bool:
        x -= border
        y -= border
        return 0 <= y < len(m) and 0 <= x < len(m) and m[y][x]

    glyph = {(True, True): "█", (True, False): "▀", (False, True): "▄", (False, False): " "}
    lines = []
    for y in range(0, size, 2):
        row = []
        for x in range(size):
            top, bot = dark(x, y), (dark(x, y + 1) if y + 1 < size else False)
            if not ansi:  # unknown colours: assume light text on dark, draw the light modules
                top, bot = not top, (not bot) if y + 1 < size else False
            row.append(glyph[(top, bot)])
        line = "".join(row)
        if ansi:
            line = "\x1b[30;107m" + line + "\x1b[0m"
        lines.append(indent + line)
    return lines


def qr_svg(url: str) -> Optional[str]:
    m = qr_matrix(url)
    if m is None:
        return None
    n, b = len(m), 4
    parts = ["M%d %dh1v1h-1z" % (x + b, y + b) for y in range(n) for x in range(n) if m[y][x]]
    s = n + 2 * b
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges">'
            '<rect width="%d" height="%d" fill="#fff"/><path d="%s" fill="#000"/></svg>'
            % (s, s, s, s, "".join(parts)))


# =============================================================================================
# File names (uploads)
# =============================================================================================

def split_ext(name: str) -> Tuple[str, str]:
    stem, dot, ext = name.rpartition(".")
    if not dot or not stem or len(ext) > 20 or not ext:
        return name, ""
    return stem, "." + ext


def _fit_bytes(stem: str, ext: str, limit: int) -> str:
    room = limit - len(ext.encode("utf-8"))
    b = stem.encode("utf-8")
    if len(b) > room:
        stem = b[:max(room, 1)].decode("utf-8", "ignore")
    return stem


def sanitize_name(raw: str, dest_dir: str = "", is_win: bool = IS_WIN) -> str:
    s = unicodedata.normalize("NFC", raw or "")
    s = re.split(r"[\\/]", s)[-1]
    s = "".join("_" if (unicodedata.category(ch) in ("Cc", "Cf") or ch in INVALID_CHARS) else ch
                for ch in s)
    s = s.strip(" .") or "file"
    stem, ext = split_ext(s)
    stem = _fit_bytes(stem, ext, MAX_NAME_BYTES).rstrip(" .") or "file"
    if is_win and dest_dir:
        over = utf16_len(dest_dir) + 1 + utf16_len(stem + ext) + 8 - WIN_MAX_PATH  # 8: " (NNNN)"
        while over > 0 and len(stem) > 1:
            over -= utf16_len(stem[-1])
            stem = stem[:-1]
        stem = stem.rstrip(" .") or "f"
    s = stem + ext
    if s.split(".")[0].rstrip(" ").upper() in RESERVED_NAMES:
        s = "_" + s
    return s


def numbered(name: str, n: int) -> str:
    if n == 0:
        return name
    stem, ext = split_ext(name)
    return "%s (%d)%s" % (stem, n, ext)


# =============================================================================================
# Inbox: receiving files from the phone
# =============================================================================================

def _read_umask() -> int:
    if IS_WIN:
        return 0
    old = os.umask(0)
    os.umask(old)
    return old


WIN_RENAME_DELAYS = (0.2, 0.3, 0.5, 1.0, 2.0, 4.0)   # about 8 s in total


class Inbox:
    def __init__(self, path: str, log: Optional[Callable] = None, is_win: bool = IS_WIN,
                 umask: Optional[int] = None):
        self.path = path
        self.log = log or (lambda *a, **k: None)
        self.is_win = is_win
        self.umask = _read_umask() if umask is None else umask
        self.lock = threading.Lock()
        self._hashes: Dict[Tuple[str, int, int], str] = {}
        self._hlock = threading.Lock()

    # -- housekeeping --------------------------------------------------------------------
    def cleanup_temps(self) -> int:
        n = 0
        try:
            with os.scandir(self.path) as it:
                for e in it:
                    if e.name.startswith(TEMP_PREFIX) and e.name.endswith(TEMP_SUFFIX):
                        try:
                            os.unlink(e.path)
                            n += 1
                        except OSError:
                            pass
        except OSError:
            pass
        return n

    # -- duplicate helpers -----------------------------------------------------------------
    def family(self, name: str) -> List[Tuple[str, str, os.stat_result]]:
        stem, ext = split_ext(name)
        flags = re.IGNORECASE if self.is_win else 0
        rx = re.compile(r"^%s(?: \((\d+)\))?%s$" % (re.escape(stem), re.escape(ext)), flags)
        out = []
        try:
            with os.scandir(self.path) as it:
                for e in it:
                    if rx.match(e.name):
                        try:
                            if e.is_file(follow_symlinks=False):
                                out.append((e.name, e.path, e.stat(follow_symlinks=False)))
                        except OSError:
                            pass
        except OSError:
            pass
        return out

    def file_hash(self, path: str) -> Optional[str]:
        try:
            st = os.stat(path)
        except OSError:
            return None
        key = (path, st.st_size, st.st_mtime_ns)
        with self._hlock:
            if key in self._hashes:
                return self._hashes[key]
        h = hashlib.sha256()
        try:
            with open(path, "rb") as f:
                while True:
                    b = f.read(CHUNK)
                    if not b:
                        break
                    h.update(b)
        except OSError:
            return None
        d = h.hexdigest()
        with self._hlock:
            self._hashes[key] = d
        return d

    def _seed_hash(self, path: str, digest: str) -> None:
        try:
            st = os.stat(path)
            with self._hlock:
                self._hashes[(path, st.st_size, st.st_mtime_ns)] = digest
        except OSError:
            pass

    # -- pre-flight --------------------------------------------------------------------------
    def precheck(self, raw_name: str, size: Optional[int], mtime_ms: Optional[int]) -> dict:
        size = max(size or 0, 0)
        try:
            free = shutil.disk_usage(self.path).free
        except OSError:
            free = None
        if free is not None and free < size + DISK_MARGIN:
            return {"ok": False, "reason": "disk_full", "free_bytes": free}
        name = sanitize_name(raw_name, self.path, self.is_win)
        if mtime_ms is not None:
            for fname, _p, st in self.family(name):
                if st.st_size == size and abs(st.st_mtime - mtime_ms / 1000.0) <= 2.0:
                    return {"ok": True, "duplicate": fname, "name": name}
        return {"ok": True, "name": name}

    # -- receive -----------------------------------------------------------------------------
    def receive(self, rfile, length: int, raw_name: str, mtime_ms: Optional[int]) -> dict:
        name = sanitize_name(raw_name, self.path, self.is_win)
        try:
            if not os.path.isdir(self.path):         # moved or deleted while Ferry was running
                os.makedirs(self.path, exist_ok=True)
                self.log("inbox", "", "", "Inbox folder was missing - recreated")
            fd, tmp = tempfile.mkstemp(prefix=TEMP_PREFIX, suffix=TEMP_SUFFIX, dir=self.path)
        except OSError as e:                         # nothing read yet: the caller drains the body
            raise WriteError(e.strerror or str(e), length) from e
        h = hashlib.sha256()
        f = os.fdopen(fd, "wb", buffering=0)
        try:
            remaining = length
            while remaining > 0:
                try:
                    buf = rfile.read(min(CHUNK, remaining))
                except (socket.timeout, OSError) as e:
                    raise UploadAborted("connection lost") from e
                if not buf:
                    raise UploadAborted("connection lost")
                try:
                    view = memoryview(buf)
                    while view:
                        n = f.write(view)
                        view = view[n:]
                except OSError as e:
                    left = remaining - len(buf)
                    if e.errno in (errno.ENOSPC, getattr(errno, "EDQUOT", -1)):
                        raise DiskFull(left) from e
                    raise WriteError(e.strerror or str(e), left) from e
                h.update(buf)
                remaining -= len(buf)
            try:
                os.fsync(f.fileno())                 # on disk before we report "saved"
            except OSError as e:
                if e.errno in (errno.ENOSPC, getattr(errno, "EDQUOT", -1)):
                    raise DiskFull(0) from e
                if e.errno == errno.EIO:
                    raise WriteError(e.strerror or str(e), 0) from e
                # other errors: this file system can't fsync - carry on as before
            f.close()
        except BaseException:
            try:
                f.close()
            except Exception:
                pass
            unlink_quiet(tmp)
            raise
        try:
            if not self.is_win:
                try:
                    os.chmod(tmp, 0o666 & ~self.umask)
                except OSError:
                    pass
            if mtime_ms is not None:
                t = mtime_ms / 1000.0
                if 631152000 <= t <= time.time() + 86400:   # 1990-01-01 .. tomorrow
                    try:
                        os.utime(tmp, (time.time(), t))
                    except OSError:
                        pass
            digest = h.hexdigest()
            for _n, p, st in self.family(name):      # hash candidates OUTSIDE the lock
                if st.st_size == length:
                    self.file_hash(p)
            with self.lock:
                for fname, p, st in self.family(name):
                    if st.st_size == length and self.file_hash(p) == digest:
                        unlink_quiet(tmp)
                        return {"result": "skipped", "saved_as": fname, "bytes": length}
                final = self._place(tmp, name)
                self._seed_hash(os.path.join(self.path, final), digest)
            return {"result": "saved" if final == name else "renamed", "saved_as": final, "bytes": length}
        except OSError as e:                         # body fully read: reply with a clear message
            unlink_quiet(tmp)
            raise WriteError(e.strerror or str(e), 0) from e
        except BaseException:
            unlink_quiet(tmp)
            raise

    def _place(self, tmp: str, name: str) -> str:
        """Move tmp to the first free name; never overwrite an existing file."""
        for n in range(0, 10000):
            cand = numbered(name, n)
            dst = os.path.join(self.path, cand)
            if os.path.lexists(dst):
                continue
            if self.is_win:
                placed = False
                for delay in WIN_RENAME_DELAYS + (None,):
                    try:
                        os.rename(tmp, dst)          # fails if dst exists (Windows)
                        placed = True
                        break
                    except FileExistsError:
                        break
                    except PermissionError as e:     # antivirus / indexer holding the file
                        if delay is None:
                            raise WriteError("another program (antivirus?) is still using it - "
                                             "send it again in a moment", 0) from e
                        time.sleep(delay)
                if placed:
                    return cand
                continue
            try:
                os.link(tmp, dst)                    # atomic, fails if dst exists
            except FileExistsError:
                continue
            except OSError as e:
                if e.errno in (errno.EPERM, errno.ENOTSUP, getattr(errno, "EOPNOTSUPP", -1),
                               errno.EXDEV, errno.EMLINK, getattr(errno, "ENOSYS", -1), errno.EACCES):
                    if os.path.lexists(dst):
                        continue
                    os.rename(tmp, dst)              # no hard links here (e.g. exFAT)
                    return cand
                raise
            unlink_quiet(tmp)
            return cand
        raise OSError("too many files named %s" % name)


# =============================================================================================
# Outbox: offering PC files to the phone / Fire TV
# =============================================================================================

@dataclass
class OutFile:
    id: int
    name: str
    path: str
    size: int
    mtime_ns: int
    kind: str

    def to_json(self) -> dict:
        return {"id": self.id, "name": self.name, "size": self.size,
                "mtime": self.mtime_ns // 1000000, "kind": self.kind,
                "url": "/api/v1/outbox/%d/%s" % (self.id, quote(self.name, safe=""))}


def _arrival_time(st: os.stat_result, is_win: bool) -> float:
    if is_win:
        return getattr(st, "st_birthtime", None) or st.st_ctime
    return st.st_mtime


class Outbox:
    def __init__(self, path: str, log: Optional[Callable] = None, is_win: bool = IS_WIN,
                 clock: Callable[[], float] = time.monotonic, url_base: Callable[[], str] = lambda: "",
                 readable: Optional[Callable[[str], bool]] = None):
        self.path = path
        self.log = log or (lambda *a, **k: None)
        self.is_win = is_win
        self.clock = clock
        self.url_base = url_base
        self._readable = readable or self._default_readable
        self.lock = threading.Lock()
        self.ready: Dict[str, OutFile] = {}
        self.by_id: Dict[int, OutFile] = {}
        self.pending: Dict[str, Tuple[tuple, float]] = {}
        self.next_id = 1
        self.folders: set = set()
        self._noted: set = set()
        self._last_error: Optional[str] = None
        self._stop = threading.Event()

    def _default_readable(self, path: str) -> bool:
        if not self.is_win:
            return True
        try:
            with open(path, "rb"):
                return True
        except OSError:
            return False

    def _classify(self, name: str, st: os.stat_result, is_dir: bool, is_link: bool) -> Optional[str]:
        low = name.lower()
        attrs = getattr(st, "st_file_attributes", 0)
        if is_link:
            return "link"
        if name.startswith(".") or attrs & (FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM):
            return "hidden"
        if is_dir:
            return "folder"
        if low in IGNORE_NAMES or low.startswith("~$") or low.endswith(IGNORE_SUFFIXES):
            return "temp"
        if low.endswith(SHORTCUT_SUFFIXES):
            return "shortcut"
        try:
            name.encode("utf-8")
        except UnicodeEncodeError:                   # undecodable name: can't be sent as JSON or in a URL
            return "badname"
        if attrs & (FILE_ATTRIBUTE_OFFLINE | FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS):
            return "placeholder"
        return None

    def _scan(self) -> Dict[str, Tuple[int, int, float]]:
        files: Dict[str, Tuple[int, int, float]] = {}
        folders = set()
        with os.scandir(self.path) as it:
            for e in it:
                try:
                    is_link = e.is_symlink()
                    st = e.stat(follow_symlinks=False)
                    is_dir = e.is_dir(follow_symlinks=False)
                except OSError:
                    continue
                why = self._classify(e.name, st, is_dir, is_link)
                if why is None:
                    files[e.name] = (st.st_size, st.st_mtime_ns, _arrival_time(st, self.is_win))
                elif why == "folder":
                    folders.add(e.name)
                    self._note_once(("folder", e.name), "outbox", "-", e.name,
                                    "is a folder - folders aren't sent; put files in directly or zip it")
                elif why == "shortcut":
                    self._note_once(("shortcut", e.name), "outbox", "-", e.name,
                                    "is a shortcut - copy the real file in instead")
                elif why == "badname":
                    self._note_once(("badname", e.name), "outbox", "-", e.name.encode("utf-8", "replace").decode("utf-8"),
                                    "has an unusual name - rename it to send it")
                elif why == "placeholder":
                    self._note_once(("ph", e.name), "outbox", "-", e.name,
                                    "is online-only (OneDrive) - right-click > Always keep on this device")
        self.folders = folders
        return files

    def _note_once(self, key, *args) -> None:
        if key not in self._noted:
            self._noted.add(key)
            self.log(*args)

    def _add(self, name: str, sig: Tuple[int, int, float], quiet: bool = False) -> OutFile:
        of = OutFile(self.next_id, name, os.path.join(self.path, name), sig[0], sig[1], file_kind(name))
        self.next_id += 1
        self.ready[name] = of
        self.by_id[of.id] = of
        if not quiet:
            self.log("outbox", "+ #%d" % of.id, name, "%s   short link: %s/%d" % (fmt_size(of.size), self.url_base(), of.id))
        return of

    def start_scan(self) -> None:
        """Initial scan: files already present are offered at once, numbered in arrival order."""
        try:
            files = self._scan()
        except OSError:
            files = {}
        with self.lock:
            for name in sorted(files, key=lambda n: (files[n][2], n)):
                self._add(name, files[name], quiet=True)

    def poll(self, now: Optional[float] = None) -> None:
        now = self.clock() if now is None else now
        try:
            files = self._scan()
        except FileNotFoundError:
            try:
                os.makedirs(self.path, exist_ok=True)
                self.log("outbox", "", "", "Outbox folder was missing - recreated")
            except OSError:
                pass
            files = {}
        with self.lock:
            for name in list(self.ready):
                of = self.ready[name]
                sig = files.get(name)
                if sig is None or (sig[0], sig[1]) != (of.size, of.mtime_ns):
                    del self.ready[name]
                    del self.by_id[of.id]
                    if sig is None:
                        self.log("outbox", "-", name, "removed")
            for name in sorted(files, key=lambda n: (files[n][2], n)):   # arrival order, then name
                sig = files[name]
                if name in self.ready:
                    continue
                p = self.pending.get(name)
                s2 = (sig[0], sig[1])
                if p is None or p[0] != s2:
                    self.pending[name] = (s2, now)
                    continue
                if now - p[1] >= STABLE_SECONDS and self._readable(os.path.join(self.path, name)):
                    del self.pending[name]
                    self._add(name, sig)
            for name in list(self.pending):
                if name not in files:
                    del self.pending[name]

    def run(self) -> None:
        while not self._stop.wait(WATCH_INTERVAL):
            self.poll_safe()

    def poll_safe(self) -> None:
        try:
            self.poll()
            self._last_error = None
        except Exception as e:  # never let the watcher die; say it once, not every second
            msg = "%s: %s" % (type(e).__name__, e)
            if msg != self._last_error:
                self._last_error = msg
                self.log("outbox", "error", "", msg)

    def start(self) -> None:
        self.start_scan()
        threading.Thread(target=self.run, name="ferry-outbox", daemon=True).start()

    def stop(self) -> None:
        self._stop.set()

    def snapshot(self) -> List[OutFile]:
        with self.lock:
            return sorted(self.by_id.values(), key=lambda o: -o.id)

    def get(self, id_: int) -> Optional[OutFile]:
        with self.lock:
            return self.by_id.get(id_)

    def newest(self) -> Optional[OutFile]:
        with self.lock:
            return self.by_id[max(self.by_id)] if self.by_id else None

    def contains(self, path: str) -> bool:
        root = os.path.normcase(os.path.realpath(self.path))
        p = os.path.normcase(os.path.realpath(path))
        return p.startswith(root.rstrip("\\/") + os.sep) and os.path.isfile(path) and not os.path.islink(path)


# =============================================================================================
# Zip (streamed, uncompressed, exact length known in advance)
# =============================================================================================

class GuardedWriter:
    """Write-only wrapper (no tell/seek keeps zipfile in streaming mode). After an abort it
    silently swallows writes, so ZipFile.close()/__del__ can never finish a broken archive."""

    def __init__(self, raw):
        self.raw, self.n, self.aborted = raw, 0, False

    def write(self, b) -> int:
        if self.aborted:
            return len(b)
        self.raw.write(b)
        self.n += len(b)
        return len(b)

    def flush(self) -> None:
        if not self.aborted:
            try:
                self.raw.flush()
            except Exception:
                pass


class CountingSink:
    def __init__(self):
        self.n, self.aborted = 0, False

    def write(self, b) -> int:
        self.n += len(b)
        return len(b)

    def flush(self) -> None:
        pass


class CollectSink(CountingSink):
    def __init__(self):
        super().__init__()
        self.parts: List[bytes] = []

    def write(self, b) -> int:
        self.parts.append(bytes(b))
        return super().write(b)

    def getvalue(self) -> bytes:
        return b"".join(self.parts)


def make_zinfo(of: OutFile) -> zipfile.ZipInfo:
    t = time.localtime(safe_ts(of.mtime_ns / 1e9))
    if t.tm_year < 1980:
        dt = (1980, 1, 1, 0, 0, 0)
    elif t.tm_year > 2107:
        dt = (2107, 12, 31, 23, 59, 58)
    else:
        dt = tuple(t[:6])
    zi = zipfile.ZipInfo(of.name, dt)
    zi.compress_type = zipfile.ZIP_STORED
    zi.file_size = of.size
    zi.external_attr = 0o644 << 16
    return zi


def zip_pairs(files: List[OutFile]):
    return [(of, make_zinfo(of)) for of in files]     # fresh ZipInfo objects every call


def file_source(of: OutFile):
    with open(of.path, "rb") as f:
        if os.fstat(f.fileno()).st_size != of.size:
            raise ZipAbort("%s changed" % of.name)
        left = of.size
        while left > 0:
            b = f.read(min(CHUNK, left))
            if not b:
                raise ZipAbort("%s shrank" % of.name)
            left -= len(b)
            yield b


_ZEROS = bytes(CHUNK)


def zero_source(of: OutFile):
    left = of.size
    while left > 0:
        k = min(CHUNK, left)
        yield _ZEROS if k == CHUNK else memoryview(_ZEROS)[:k]
        left -= k


def _can_fake(zf, w) -> bool:
    return hasattr(w, "_file_size") and hasattr(w, "_compress_size") and hasattr(zf.fp, "offset")


def zip_write(out, pairs, source, dry: bool = False) -> None:
    zf = zipfile.ZipFile(out, "w", zipfile.ZIP_STORED, strict_timestamps=False)
    try:
        for of, zi in pairs:
            w = zf.open(zi, "w")             # no `with`: on error w.close() must not run
            if dry and _can_fake(zf, w):
                w._file_size = w._compress_size = of.size   # skip the data bytes entirely
                zf.fp.offset += of.size
                out.n += of.size
            else:
                for chunk in (zero_source(of) if dry else source(of)):
                    w.write(chunk)
            w.close()
        zf.close()
    except BaseException:
        out.aborted = True
        raise


_zip_len_cache: Dict[tuple, int] = {}
_zip_len_lock = threading.Lock()


def zip_length(files: List[OutFile]) -> int:
    key = tuple((o.name, o.size, o.mtime_ns) for o in files)
    with _zip_len_lock:
        if key in _zip_len_cache:
            return _zip_len_cache[key]
    sink = CountingSink()
    zip_write(sink, zip_pairs(files), zero_source, dry=True)
    with _zip_len_lock:
        if len(_zip_len_cache) > 32:
            _zip_len_cache.clear()
        _zip_len_cache[key] = sink.n
    return sink.n


# =============================================================================================
# Transfers: keep-PC-awake, first-connection messages, "no phone yet" watchdog
# =============================================================================================

class TransferTracker:
    def __init__(self, log_note: Callable[[str], None], is_win: bool = IS_WIN):
        self.note = log_note
        self.is_win = is_win
        self.active = 0
        self.lock = threading.Lock()
        self.changed = threading.Event()
        self.clients: set = set()
        self.any_client = False
        self.played: set = set()
        if is_win:
            threading.Thread(target=self._awake_loop, name="ferry-awake", daemon=True).start()

    def begin(self) -> None:
        with self.lock:
            self.active += 1
        self.changed.set()

    def end(self) -> None:
        with self.lock:
            self.active = max(0, self.active - 1)
        self.changed.set()

    def _awake_loop(self) -> None:
        on = False
        while True:
            self.changed.wait()
            self.changed.clear()
            with self.lock:
                want = self.active > 0
            if want != on:
                win_keep_awake(want)
                on = want

    def seen_client(self, ip: str, ua: str) -> None:
        with self.lock:
            if ip in self.clients:
                return
            self.clients.add(ip)
            self.any_client = True
        dev = device_type(ua)
        hint = "Android" if "Android" in (ua or "") else ("Fire TV" if dev == "Fire TV" else "")
        self.note("%s connected (%s%s)" % (dev[0].upper() + dev[1:], (hint + ", ") if hint and hint != dev else "", ip))

    def first_play(self, key) -> bool:
        with self.lock:
            if key in self.played:
                return False
            self.played.add(key)
            return True

    def start_watchdog(self, seconds: float = WATCHDOG_SECONDS) -> None:
        def run():
            time.sleep(seconds)
            if not self.any_client:
                self.note("Phone can't connect? Check: (1) phone and PC on the same Wi-Fi, not a guest network")
                self.note("  (2) Windows Firewall allowed Python on Private networks  (3) README.txt > Troubleshooting")
        threading.Thread(target=run, name="ferry-watchdog", daemon=True).start()


# =============================================================================================
# EXIF GPS check (used only by the /diag page)
# =============================================================================================

def exif_gps_status(data: bytes) -> str:
    """'present', 'zeroed', 'none', or 'unknown' for the first bytes of a JPEG."""
    try:
        if data[:2] != b"\xff\xd8":
            return "unknown"
        i = 2
        while i + 4 <= len(data):
            if data[i] != 0xFF:
                return "none"
            marker = data[i + 1]
            if marker in (0xD9, 0xDA):
                return "none"
            seglen = int.from_bytes(data[i + 2:i + 4], "big")
            seg = data[i + 4:i + 2 + seglen]
            if marker == 0xE1 and seg[:6] == b"Exif\x00\x00":
                return _tiff_gps(seg[6:])
            i += 2 + seglen
        return "unknown"
    except Exception:
        return "unknown"


def _tiff_gps(t: bytes) -> str:
    bo = "little" if t[:2] == b"II" else "big"

    def u16(o): return int.from_bytes(t[o:o + 2], bo)

    def u32(o): return int.from_bytes(t[o:o + 4], bo)

    ifd = u32(4)
    for k in range(u16(ifd)):
        e = ifd + 2 + 12 * k
        if u16(e) == 0x8825:
            g = u32(e + 8)
            if g <= 0 or g + 2 > len(t):
                return "unknown"
            for j in range(u16(g)):
                ge = g + 2 + 12 * j
                if u16(ge) in (2, 4):  # latitude / longitude
                    off = u32(ge + 8)
                    nums = [u32(off + 8 * r) for r in range(3)]
                    return "present" if any(nums) else "zeroed"
            return "zeroed"
    return "none"


# =============================================================================================
# HTTP server
# =============================================================================================

class AppContext:
    def __init__(self, **kw):
        self.__dict__.update(kw)


class FerryServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 32

    def __init__(self, addr, handler, app: Optional[AppContext] = None, exclusive: bool = IS_WIN):
        self.app = app
        self._exclusive = exclusive
        self.allow_reuse_address = not exclusive
        super().__init__(addr, handler)

    def server_bind(self) -> None:
        if self._exclusive:
            opt = getattr(socket, "SO_EXCLUSIVEADDRUSE", -5 if IS_WIN else None)
            if opt is not None:
                try:
                    self.socket.setsockopt(socket.SOL_SOCKET, opt, 1)
                except OSError:
                    pass
        socketserver.TCPServer.server_bind(self)   # (skips HTTPServer's slow getfqdn)
        self.server_name = "ferry"
        self.server_port = self.server_address[1]

    def handle_error(self, request, client_address) -> None:
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionError, socket.timeout, TimeoutError)):
            return
        log = getattr(self.app, "console", None)
        if log:
            log.error_once("connection %s: %s: %s" % (client_address[0], type(exc).__name__, exc))


ROUTES_EXACT = {
    "/": "page", "/index.html": "page", "/api/v1/info": "api_info", "/api/v1/check": "api_check",
    "/api/v1/upload": "api_upload", "/api/v1/outbox": "api_outbox", "/api/v1/outbox.zip": "send_zip",
    "/new": "redirect_new", "/qr": "qr_page", "/diag": "diag_page",
    "/api/v1/diag/inspect": "diag_inspect", "/api/v1/diag/blob": "diag_blob",
    "/api/v1/diag/sink": "diag_sink", "/api/v1/diag/test.zip": "diag_zip",
    "/api/v1/diag/ferry-test.apk": "diag_apk",
}
POST_ROUTES = {"api_upload", "diag_inspect", "diag_sink"}
RX_OUTBOX = re.compile(r"^/api/v1/outbox/(\d{1,9})(?:/.*)?$")
RX_SHORT = re.compile(r"^/(\d{1,6})/?$")
ASSET_PATHS = ("/favicon.svg", "/icon-192.png", "/icon-512.png", "/icon-maskable-512.png",
               "/manifest.webmanifest")


class FerryHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"
    server_version = "Ferry/" + VERSION
    sys_version = ""
    timeout = SOCKET_TIMEOUT

    def log_message(self, *args) -> None:
        pass

    def log_error(self, *args) -> None:
        pass

    @property
    def app(self) -> AppContext:
        return self.server.app

    def do_GET(self) -> None:
        self._dispatch("GET")

    def do_HEAD(self) -> None:
        self._dispatch("HEAD")

    def do_POST(self) -> None:
        self._dispatch("POST")

    # -- plumbing ------------------------------------------------------------------------------
    def _dispatch(self, method: str) -> None:
        self._head = method == "HEAD"
        parts = urlsplit(self.path)
        path = parts.path
        self.q = {k: v[-1] for k, v in parse_qs(parts.query, keep_blank_values=True).items()}
        self.ua = self.headers.get("User-Agent", "")
        self.device = device_type(self.ua)
        if not path.startswith(("/favicon", "/icon-", "/manifest")):
            self.app.tracker.seen_client(self.client_address[0], self.ua)
        try:
            name = ROUTES_EXACT.get(path)
            if name:
                if (name in POST_ROUTES) != (method == "POST"):
                    return self.send_json(405, {"result": "error", "message": "method not allowed"})
                return getattr(self, name)()
            if method == "POST":
                return self.send_json(405, {"result": "error", "message": "method not allowed"})
            m = RX_OUTBOX.match(path)
            if m:
                return self.send_outbox_file(int(m.group(1)))
            m = RX_SHORT.match(path)
            if m:
                return self.redirect_id(int(m.group(1)))
            if path in ASSET_PATHS:
                return self.asset(path[1:])
            return self.not_found()
        except (ConnectionError, socket.timeout, TimeoutError):
            self.close_connection = True
        except Exception as e:
            self.app.console.note("error  %s: %s: %s" % (path, type(e).__name__, e))
            try:
                self.send_json(500, {"result": "error", "message": "unexpected error on the PC"})
            except Exception:
                pass

    def send_bytes(self, code: int, body: bytes, ctype: str, headers: Optional[dict] = None) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if not self._head:
            self.wfile.write(body)

    def send_json(self, code: int, obj) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_bytes(code, body, "application/json; charset=utf-8", {"Cache-Control": "no-store"})

    def send_html_page(self, code: int, title: str, message: str) -> None:
        body = (ERROR_PAGE.replace("{{TITLE}}", _h(title)).replace("{{MESSAGE}}", _h(message))).encode("utf-8")
        self.send_bytes(code, body, "text/html; charset=utf-8", {"Cache-Control": "no-store"})

    def not_found(self, message: str = "That page doesn't exist.") -> None:
        if self.path.startswith("/api/"):
            return self.send_json(404, {"result": "error", "message": message})
        return self.send_html_page(404, "Not found", message)

    def drain(self, n: int) -> None:
        left = min(max(n, 0), DRAIN_LIMIT)
        try:
            self.connection.settimeout(10)
            while left > 0:
                b = self.rfile.read(min(CHUNK, left))
                if not b:
                    break
                left -= len(b)
        except Exception:
            pass
        self.close_connection = True

    def abort_connection(self) -> None:
        self.close_connection = True
        try:
            self.connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def content_length(self) -> Optional[int]:
        try:
            n = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return None
        return n

    # -- pages & assets ---------------------------------------------------------------------
    def page(self) -> None:
        self.send_bytes(200, self.app.page_bytes, "text/html; charset=utf-8", {"Cache-Control": "no-store"})

    def diag_page(self) -> None:
        self.send_bytes(200, self.app.diag_bytes, "text/html; charset=utf-8", {"Cache-Control": "no-store"})

    def qr_page(self) -> None:
        svg = qr_svg(self.app.primary_url) if self.app.primary_url else None
        body = QR_PAGE.replace("{{URL}}", _h(self.app.primary_url or "(no network address)")) \
                      .replace("{{SVG}}", svg or "<p>QR code unavailable (qrcodegen.py missing).</p>")
        self.send_bytes(200, body.encode("utf-8"), "text/html; charset=utf-8", {"Cache-Control": "no-store"})

    def asset(self, name: str) -> None:
        a = self.app.assets.get(name)
        if a is None:
            return self.not_found()
        ctype, body = a
        self.send_bytes(200, body, ctype, {"Cache-Control": "max-age=86400"})

    # -- API -------------------------------------------------------------------------------------
    def api_info(self) -> None:
        app = self.app
        try:
            free = shutil.disk_usage(app.cfg.inbox).free
        except OSError:
            free = None
        self.send_json(200, {
            "app": "ferry", "protocol": PROTOCOL, "version": VERSION, "name": app.pc_name,
            "device_type": "pc", "os": "windows" if IS_WIN else "linux",
            "capabilities": ["receive", "outbox"],
            "inbox_label": app.cfg.inbox_label, "outbox_label": app.cfg.outbox_label,
            "free_bytes": free})

    def api_check(self) -> None:
        name = self.q.get("name", "")
        res = self.app.inbox.precheck(name, parse_int(self.q.get("size")), parse_int(self.q.get("mtime")))
        who = "from " + self.device
        if res.get("duplicate"):
            self.app.console.log(who, "skipped", res["duplicate"], "already on PC")
        elif not res.get("ok"):
            self.app.console.log(who, "FAILED", res.get("name", name) or name, "PC disk is full")
        self.send_json(200, res)

    def api_upload(self) -> None:
        app = self.app
        who = "from " + self.device
        length = self.content_length()
        if length is None:
            self.close_connection = True
            return self.send_json(411, {"result": "error", "message": "Content-Length required"})
        if length < 0:
            self.close_connection = True
            return self.send_json(400, {"result": "error", "message": "bad Content-Length"})
        name = self.q.get("name", "")
        size = parse_int(self.q.get("size"))
        mtime = parse_int(self.q.get("mtime"))
        if not name or (size is not None and size != length):
            self.drain(length)
            return self.send_json(400, {"result": "error", "message": "bad upload request"})
        app.tracker.begin()
        t0 = time.monotonic()
        try:
            res = app.inbox.receive(self.rfile, length, name, mtime)
        except UploadAborted:
            app.console.log(who, "stopped", sanitize_name(name), "cancelled or connection lost")
            self.close_connection = True
            return
        except DiskFull as e:
            app.console.log(who, "FAILED", sanitize_name(name), "PC disk is full")
            self.drain(e.remaining)
            return self.send_json(507, {"result": "error", "reason": "disk_full", "message": "PC disk is full"})
        except WriteError as e:
            app.console.log(who, "FAILED", sanitize_name(name), "can't save: %s" % e)
            self.drain(e.remaining)
            return self.send_json(500, {"result": "error", "message": "The PC couldn't save the file: %s" % e})
        finally:
            app.tracker.end()
        dt = time.monotonic() - t0
        r = res["result"]
        if r == "skipped":
            app.console.log(who, "skipped", res["saved_as"], "already on PC")
        elif r == "renamed":
            app.console.log(who, "renamed", "%s -> %s" % (sanitize_name(name), res["saved_as"]), fmt_size(length))
        else:
            app.console.log(who, "saved", res["saved_as"], ("%s  %s" % (fmt_size(length), fmt_speed(length, dt))).strip())
        self.send_json(200, res)

    def api_outbox(self) -> None:
        files = self.app.outbox.snapshot()
        self.send_json(200, {"files": [o.to_json() for o in files],
                             "total_size": sum(o.size for o in files),
                             "folders_ignored": len(self.app.outbox.folders)})

    def redirect_id(self, id_: int) -> None:
        of = self.app.outbox.get(id_)
        if of is None:
            return self.send_html_page(404, "Not in the outbox", "That file is no longer in the outbox.")
        self._redirect(of)

    def redirect_new(self) -> None:
        of = self.app.outbox.newest()
        if of is None:
            return self.send_html_page(404, "Outbox is empty", "There's nothing in the outbox yet.")
        self._redirect(of)

    def _redirect(self, of: OutFile) -> None:
        self.send_response(302)
        self.send_header("Location", "/api/v1/outbox/%d/%s" % (of.id, quote(of.name, safe="")))
        self.send_header("Content-Length", "0")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()

    def send_outbox_file(self, id_: int) -> None:
        app = self.app
        of = app.outbox.get(id_)
        gone = "That file is no longer in the outbox."
        if of is None or not app.outbox.contains(of.path):
            return self.not_found(gone)
        try:
            f = open(of.path, "rb")
        except OSError:
            return self.not_found(gone)
        with f:
            st = os.fstat(f.fileno())
            if st.st_size != of.size or st.st_mtime_ns != of.mtime_ns:
                return self.not_found(gone)
            size = st.st_size
            etag = '"%d-%d"' % (size, st.st_mtime_ns)
            last_mod = formatdate(safe_ts(st.st_mtime), usegmt=True)
            rng_header = self.headers.get("Range")
            if_range = self.headers.get("If-Range")
            rng = parse_range(rng_header, size) if (not if_range or if_range.strip() in (etag, last_mod)) else None
            if rng == "invalid":
                return self.send_bytes(416, b"", "text/plain", {"Content-Range": "bytes */%d" % size})
            inline = self.q.get("inline") == "1"
            if rng:
                start, end = rng
                code, length = 206, end - start + 1
            else:
                start, code, length = 0, 200, size
            self.send_response(code)
            self.send_header("Content-Type", guess_type(of.name))
            self.send_header("Content-Disposition", content_disposition("inline" if inline else "attachment", of.name))
            self.send_header("Content-Length", str(length))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("ETag", etag)
            self.send_header("Last-Modified", last_mod)
            if rng:
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, start + length - 1, size))
            self.end_headers()
            if self._head:
                return
            playing = bool(rng_header) or inline
            who = "to " + self.device
            app.tracker.begin()
            t0 = time.monotonic()
            try:
                f.seek(start)
                left = length
                while left > 0:
                    b = f.read(min(CHUNK, left))
                    if not b:
                        raise ShortRead()
                    self.wfile.write(b)
                    left -= len(b)
            except ShortRead:
                app.console.log(who, "FAILED", of.name, "file changed while sending")
                return self.abort_connection()
            except (ConnectionError, socket.timeout, TimeoutError, OSError):
                if not playing:
                    app.console.log(who, "cancelled", of.name)
                self.close_connection = True
                return
            finally:
                app.tracker.end()
            if playing:
                if app.tracker.first_play(of.id):
                    app.console.log(who, "playing", of.name, fmt_size(size))
            else:
                app.console.log(who, "sent", of.name, ("%s  %s" % (fmt_size(size), fmt_speed(size, time.monotonic() - t0))).strip())

    def send_zip(self) -> None:
        app = self.app
        ids = self.q.get("ids")
        if ids:
            files = [app.outbox.get(i) for i in (parse_int(x) for x in ids.split(",")) if i is not None]
            files = [o for o in files if o is not None]
        else:
            files = list(reversed(app.outbox.snapshot()))
        files = [o for o in files if app.outbox.contains(o.path)]
        if not files:
            return self.not_found("Nothing to zip - the outbox is empty.")
        length = zip_length(files)
        zname = "Ferry-%d-files.zip" % len(files)
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Disposition", content_disposition("attachment", zname))
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self._head:
            return
        who = "to " + self.device
        guard = GuardedWriter(self.wfile)
        app.tracker.begin()
        t0 = time.monotonic()
        try:
            zip_write(guard, zip_pairs(files), file_source)
        except (ZipAbort, OSError) as e:
            app.console.log(who, "cancelled" if not isinstance(e, ZipAbort) else "FAILED", zname,
                            "" if not isinstance(e, ZipAbort) else str(e))
            return self.abort_connection()
        finally:
            app.tracker.end()
        app.console.log(who, "sent", zname, ("%s  %s" % (fmt_size(length), fmt_speed(length, time.monotonic() - t0))).strip())

    # -- diagnostics (/diag) ----------------------------------------------------------------
    def diag_inspect(self) -> None:
        length = self.content_length()
        if length is None or length < 0:
            self.close_connection = True
            return self.send_json(411, {"result": "error", "message": "Content-Length required"})
        head = bytearray()
        left = length
        try:
            while left > 0:
                b = self.rfile.read(min(CHUNK, left))
                if not b:
                    raise UploadAborted()
                if len(head) < 262144:
                    head += b[:262144 - len(head)]
                left -= len(b)
        except (UploadAborted, OSError):
            self.close_connection = True
            return
        name = self.q.get("name", "")
        mt = parse_int(self.q.get("mtime"))
        now_ms = int(time.time() * 1000)
        ext = os.path.splitext(name)[1].lower()
        gps = exif_gps_status(bytes(head)) if ext in (".jpg", ".jpeg") or head[:2] == b"\xff\xd8" else (
            "unsupported" if ext in (".heic", ".heif") else "unknown")
        self.send_json(200, {"name": name, "size": length, "last_modified_ms": mt, "server_now_ms": now_ms,
                             "age_seconds": None if mt is None else round((now_ms - mt) / 1000.0),
                             "gps": gps})

    def diag_blob(self) -> None:
        mb = min(max(parse_int(self.q.get("mb")) or 100, 1), 1024)
        n = mb * 1000000
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(n))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self._head:
            return
        left = n
        try:
            while left > 0:
                k = min(CHUNK, left)
                self.wfile.write(_ZEROS if k == CHUNK else _ZEROS[:k])
                left -= k
        except OSError:
            self.close_connection = True

    def diag_sink(self) -> None:
        length = self.content_length()
        if length is None or length < 0:
            self.close_connection = True
            return self.send_json(411, {"result": "error"})
        t0 = time.monotonic()
        left = length
        try:
            while left > 0:
                b = self.rfile.read(min(CHUNK, left))
                if not b:
                    raise UploadAborted()
                left -= len(b)
        except (UploadAborted, OSError):
            self.close_connection = True
            return
        self.send_json(200, {"bytes": length, "seconds": round(time.monotonic() - t0, 3)})

    def diag_zip(self) -> None:
        sink = CollectSink()
        with tempfile.TemporaryDirectory() as d:
            files = []
            for i, (n, text) in enumerate([("ferry-test-1.txt", "Hello from Ferry!\n"),
                                           ("ferry-test-2.txt", "If you can read this, zip files work.\n")]):
                p = os.path.join(d, n)
                with open(p, "w", encoding="utf-8") as f:
                    f.write(text)
                st = os.stat(p)
                files.append(OutFile(i + 1, n, p, st.st_size, st.st_mtime_ns, "other"))
            zip_write(sink, zip_pairs(files), file_source)
        self.send_bytes(200, sink.getvalue(), "application/zip",
                        {"Content-Disposition": content_disposition("attachment", "ferry-test.zip")})

    def diag_apk(self) -> None:
        body = b"Ferry test file - not a real app. It is expected to fail to install.\n"
        self.send_bytes(200, body, MIME_OVERRIDES[".apk"],
                        {"Content-Disposition": content_disposition("attachment", "ferry-test.apk")})


def parse_range(header: Optional[str], size: int):
    """None = send whole file; (start, end) inclusive; 'invalid' = 416."""
    if not header:
        return None
    h = header.strip()
    if not h.lower().startswith("bytes="):
        return None
    spec = h[6:].strip()
    if "," in spec:
        return None
    a, sep, b = spec.partition("-")
    a, b = a.strip(), b.strip()
    if not sep:
        return None
    if a == "":
        if not b.isdigit():
            return None
        n = int(b)
        if n == 0 or size == 0:
            return "invalid"
        return (max(0, size - n), size - 1)
    if not a.isdigit() or (b and not b.isdigit()):
        return None
    start = int(a)
    end = int(b) if b else size - 1
    if b and end < start:
        return None
    if start >= size:
        return "invalid"
    return (start, min(end, size - 1))


def _h(s: str) -> str:
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
             .replace('"', "&quot;").replace("'", "&#39;"))


ERROR_PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Ferry - {{TITLE}}</title>
<link rel="icon" href="/favicon.svg"><style>body{font:18px system-ui,sans-serif;margin:0;padding:2rem;
background:#fff;color:#0f172a}@media(prefers-color-scheme:dark){body{background:#0f172a;color:#e2e8f0}}
a{color:#2563eb;font-weight:500}@media(prefers-color-scheme:dark){a{color:#60a5fa}}</style></head>
<body><h1>{{TITLE}}</h1><p>{{MESSAGE}}</p><p><a href="/">Back to Ferry</a></p></body></html>"""

QR_PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Ferry - scan me</title>
<link rel="icon" href="/favicon.svg"><style>body{font:20px system-ui,sans-serif;text-align:center;
margin:0;padding:2rem;background:#fff;color:#000}svg{width:min(70vmin,520px);height:auto}</style></head>
<body><h1>Scan with your phone camera</h1>{{SVG}}<p>{{URL}}</p></body></html>"""


# =============================================================================================
# Assets (icons, manifest)
# =============================================================================================

def build_assets() -> Dict[str, Tuple[str, bytes]]:
    import base64
    a: Dict[str, Tuple[str, bytes]] = {"favicon.svg": ("image/svg+xml", FAVICON_SVG.encode("utf-8"))}
    for name, b64 in ASSETS_B64.items():
        if name.endswith(".png"):
            a[name] = ("image/png", base64.b64decode(b64))
    manifest = {
        "name": "Ferry", "short_name": "Ferry", "start_url": "/", "display": "browser",
        "theme_color": "#1d4ed8", "background_color": "#1d4ed8",
        "icons": [{"src": "/icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
                  {"src": "/icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
                  {"src": "/icon-maskable-512.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"},
                  {"src": "/favicon.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "any"}]}
    a["manifest.webmanifest"] = ("application/manifest+json", json.dumps(manifest).encode("utf-8"))
    return a


def render_page(template: str, pc_name: str) -> bytes:
    keep = ASSETS_B64.get("keepawake.mp4", "")
    return (template.replace("{{PC_NAME}}", _h(pc_name))
                    .replace("{{VERSION}}", VERSION)
                    .replace("{{KEEPAWAKE_SRC}}", ("data:video/mp4;base64," + keep) if keep else "")
                    ).encode("utf-8")


# =============================================================================================
# Startup
# =============================================================================================

RULE = "  " + "─" * 62


def build_banner(cfg: Config, url: Optional[str], qr_lines: List[str], outbox_count: int) -> List[str]:
    lines = ["  FERRY  is running" + " " * 24 + "v" + VERSION]
    if url and qr_lines:
        lines.append("  Scan with your phone camera:")
        lines.extend(qr_lines)
    elif url:
        lines.append("  (QR code unavailable - qrcodegen.py is missing)")
    if url:
        lines.append("  Phone:  " + url)
    else:
        lines.append("  No network connection found - connect to Wi-Fi, then restart Ferry.")
    lines.append("  Inbox:  %s     Outbox: %s (%d file%s)" % (
        cfg.inbox_label, cfg.outbox_label, outbox_count, "" if outbox_count == 1 else "s"))
    lines.append("  Keep this window open. Ctrl+C to stop.   Help: README.txt")
    lines.append(RULE)
    return lines


def build_extras(cfg: Config, url: Optional[str], others: List[str], last_ip: Optional[str],
                 primary: Optional[str], port: int) -> List[str]:
    out = []
    if primary and last_ip and primary != last_ip:
        out.append("  ! Your PC's address changed (was %s). Rescan the QR code and update your" % last_ip)
        out.append("    phone shortcut and Fire TV bookmark. To stop this: README.txt > Keep the same address.")
    if others and primary and not primary.startswith("192.168."):
        out.append("  If the phone can't connect, try: " + "  ".join("http://%s:%d" % (o, port) for o in others[:3]))
    if cfg.onedrive_outbox:
        out.append("  ! Your Desktop syncs to OneDrive - big files in the outbox also upload to the cloud.")
        out.append("    (Advanced: start with --outbox to choose another folder.)")
    if cfg.first_run:
        out.append("  First run - welcome! Files from your phone go to:  " + cfg.inbox)
        out.append("  To send files to your phone or TV, put them in:      " + cfg.outbox)
        out.append("  Bookmark-friendly name (works on some devices):     http://%s:%d" % (hostname_local(), port))
    return out


def bind_server(port: int, app: Optional[AppContext], exclusive: bool = IS_WIN,
                probe=None, retry_seconds: float = 5.0) -> FerryServer:
    deadline = time.monotonic() + retry_seconds
    while True:
        try:
            return FerryServer(("0.0.0.0", port), FerryHandler, app=app, exclusive=exclusive)
        except OSError as e:
            winerr = getattr(e, "winerror", None)
            if winerr == 10013 or (winerr is None and e.errno == errno.EACCES):
                if IS_WIN or winerr == 10013:
                    raise FatalError("Windows has reserved port %d (often Hyper-V/WSL).\n"
                                     "  Start Ferry with:  Ferry.bat --port %d" % (port, port + 35))
                raise FatalError("Permission denied for port %d. Start Ferry with:  ./ferry.sh --port %d"
                                 % (port, port + 35))
            if winerr == 10048 or e.errno == errno.EADDRINUSE:
                if (probe or probe_ferry)(port):
                    raise FatalError("Ferry is already running in another window (port %d)." % port)
                if time.monotonic() < deadline:
                    time.sleep(1.0)
                    continue
                raise FatalError("Port %d is used by another program.\n"
                                 "  Start Ferry with:  %s --port %d"
                                 % (port, "Ferry.bat" if IS_WIN else "./ferry.sh", port + 35))
            raise FatalError("Can't start the server on port %d: %s" % (port, e))


def probe_ferry(port: int) -> bool:
    try:
        import http.client
        c = http.client.HTTPConnection("127.0.0.1", port, timeout=1.5)
        c.request("GET", "/api/v1/info")
        r = c.getresponse()
        ok = r.status == 200 and json.loads(r.read().decode("utf-8")).get("app") == "ferry"
        c.close()
        return ok
    except Exception:
        return False


def make_app(cfg: Config, console: Console, primary: Optional[str], is_win: bool = IS_WIN) -> AppContext:
    url = "http://%s:%d" % (primary, cfg.port) if primary else None
    pc_name = socket.gethostname().split(".")[0] or "PC"
    app = AppContext(cfg=cfg, console=console, primary=primary, primary_url=url, pc_name=pc_name)
    app.inbox = Inbox(cfg.inbox, console.log, is_win)
    app.outbox = Outbox(cfg.outbox, console.log, is_win,
                        url_base=lambda: app.primary_url or "http://<PC address>:%d" % cfg.port)
    app.tracker = TransferTracker(console.note, is_win)
    app.assets = build_assets()
    app.page_bytes = render_page(PAGE_HTML, pc_name)
    app.diag_bytes = render_page(DIAG_HTML, pc_name)
    return app


def install_hooks(console: Console) -> None:
    def thook(args):
        if issubclass(args.exc_type, (ConnectionError, socket.timeout, TimeoutError)):
            return
        console.error_once("%s in %s: %s" % (args.exc_type.__name__, getattr(args.thread, "name", "?"), args.exc_value))

    def uhook(u):
        console.error_once("ignored %s: %s" % (type(u.exc_value).__name__, u.exc_value))

    threading.excepthook = thook
    sys.unraisablehook = uhook


def parse_args(argv=None):
    p = argparse.ArgumentParser(
        prog="ferry", description="Ferry - move files between your phone / Fire TV and this PC over Wi-Fi.",
        epilog="Phone address: shown when Ferry starts (scan the QR code). A name-based address "
               "http://<pc-name>.local:PORT also works on some devices.")
    p.add_argument("--port", type=int, default=DEFAULT_PORT, help="port to use (default %d)" % DEFAULT_PORT)
    p.add_argument("--no-open", action="store_true", help="never open the outbox folder")
    p.add_argument("--inbox", help="advanced: folder for files from the phone (used exactly as given)")
    p.add_argument("--outbox", help="advanced: folder offered to the phone/TV (used exactly as given)")
    p.add_argument("--install-desktop", action="store_true", help="Linux: add Ferry to the app menu")
    p.add_argument("--selftest", action="store_true", help="check this PC and write ferry-selftest.txt")
    p.add_argument("--version", action="version", version="Ferry " + VERSION)
    a = p.parse_args(argv)
    if not (1 <= a.port <= 65535):
        p.error("port must be 1-65535")
    return a


def wait_enter_if_needed() -> None:
    if IS_WIN or os.environ.get("FERRY_LAUNCHER"):
        return  # the launcher pauses
    try:
        if sys.stdin and sys.stdin.isatty():
            input("Press Enter to close...")
    except Exception:
        pass


def setup_stdout() -> Dict[str, bool]:
    modes = {"ansi": False, "quickedit_off": False}
    if IS_WIN:
        modes = win_console_setup()
    else:
        modes["ansi"] = bool(getattr(sys.stdout, "isatty", lambda: False)())
    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except Exception:
            pass
    return modes


def main(argv=None, stop_event: Optional[threading.Event] = None) -> int:
    args = parse_args(argv)
    if args.install_desktop:
        return install_desktop()
    if args.selftest:
        return selftest(args)
    modes = setup_stdout()
    console = Console(ansi=modes["ansi"])
    install_hooks(console)
    state = State.load()
    try:
        cfg = resolve_folders(args, state)
        primary, others = detect_addresses()
        app = make_app(cfg, console, primary)
        server = bind_server(cfg.port, app)
    except FatalError as e:
        console.print("")
        console.print("  Ferry couldn't start:")
        console.print("  " + str(e))
        console.stop()
        wait_enter_if_needed()
        return 1
    app.inbox.cleanup_temps()                    # only after we own the port [T1]
    app.outbox.start_scan()
    qr = qr_console_lines(app.primary_url, console.ansi) if app.primary_url else []
    for line in build_banner(cfg, app.primary_url, qr, len(app.outbox.snapshot())):
        console.print(line)
    for line in build_extras(cfg, app.primary_url, others, state.data.get("last_ip"), primary, cfg.port):
        console.print(line)
    console.print("  Waiting for your phone...")
    threading.Thread(target=app.outbox.run, name="ferry-outbox", daemon=True).start()
    if IS_WIN and primary:
        def check_profile():
            if win_network_category(primary) == "Public":
                console.note("! Windows treats this network as Public, which blocks your phone.")
                console.note("  Fix: Settings > Network & Internet > Wi-Fi > (your network) > Private.")
        threading.Thread(target=check_profile, name="ferry-netcheck", daemon=True).start()
    if cfg.first_run and not cfg.no_open:       # first_run, not outbox_created: the self-test may have made the folder
        open_folder(cfg.outbox)
    state.data["last_ip"] = primary or state.data.get("last_ip")
    state.data["first_run_done"] = True
    state.save()
    app.tracker.start_watchdog()
    if stop_event is not None:
        threading.Thread(target=lambda: (stop_event.wait(), server.shutdown()), daemon=True).start()
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        app.outbox.stop()
        server.server_close()
        app.inbox.cleanup_temps()                # best effort; startup cleanup is the guarantee
        console.print("  Ferry stopped.")
        console.stop()
    return 0


# =============================================================================================
# --selftest and --install-desktop
# =============================================================================================

def selftest(args) -> int:
    setup_stdout()
    results: List[Tuple[str, bool, str]] = []

    def check(name: str, fn) -> None:
        try:
            ok, detail = fn()
        except Exception as e:
            ok, detail = False, "%s: %s" % (type(e).__name__, e)
        results.append((name, bool(ok), detail))

    import platform
    check("Python version", lambda: (sys.version_info >= (3, 8), "%s at %s" % (platform.python_version(), sys.executable)))
    check("Operating system", lambda: (True, platform.platform()))
    if IS_WIN:
        def console_modes():
            win_console_setup()
            m = win_console_modes()
            if m is None:
                return False, "not running in a console window"
            out_m, in_m = m
            return bool(out_m & 0x4) and not (in_m & 0x40), "colours %s, QuickEdit %s" % (
                "on" if out_m & 0x4 else "OFF", "off" if not in_m & 0x40 else "STILL ON")
        check("Console (colours on, QuickEdit off)", console_modes)
        check("Downloads folder", lambda: (bool(win_known_folder(FOLDERID_DOWNLOADS)), str(win_known_folder(FOLDERID_DOWNLOADS))))
        check("Desktop folder", lambda: (bool(win_known_folder(FOLDERID_DESKTOP)), str(win_known_folder(FOLDERID_DESKTOP))))
        check("Keep PC awake call", lambda: (win_keep_awake(True) and win_keep_awake(False), "SetThreadExecutionState"))

    state = State.load()

    def folders():
        cfg = resolve_folders(args, state)
        return True, "inbox %s | outbox %s" % (cfg.inbox, cfg.outbox)
    check("Inbox/outbox usable", folders)

    def exclusive_bind():
        s1 = FerryServer(("127.0.0.1", 0), FerryHandler, exclusive=IS_WIN)
        port = s1.server_address[1]
        try:
            try:
                s2 = FerryServer(("0.0.0.0", port), FerryHandler, exclusive=IS_WIN)
                s2.server_close()
                return False, "a second copy could use the same port"
            except OSError:
                return True, "second copy correctly refused (port %d)" % port
        finally:
            s1.server_close()
    check("Only one Ferry per port", exclusive_bind)

    def rename_rules():
        with tempfile.TemporaryDirectory() as d:
            ib = Inbox(d, is_win=IS_WIN)
            for content in (b"one", b"two"):
                r = ib.receive(io.BytesIO(content), len(content), "test.txt", int(time.time() * 1000))
            ok = r["result"] == "renamed" and sorted(os.listdir(d)) == ["test (1).txt", "test.txt"]
            with open(os.path.join(d, "test.txt"), "rb") as f:
                ok = ok and f.read() == b"one"
            return ok, "never overwrites; renames to 'test (1).txt'"
    check("Saving rules", rename_rules)

    def qr():
        lines = qr_console_lines("http://192.168.1.23:8765", False)
        return bool(lines), "%d rows" % len(lines) if lines else "qrcodegen.py missing or broken"
    check("QR code", qr)

    primary, others = detect_addresses()
    check("Network address", lambda: (primary is not None, "%s (others: %s)" % (primary, ", ".join(others) or "none")))
    if IS_WIN and primary:
        check("Network type", lambda: ((lambda c: (c in ("Private", "DomainAuthenticated"), str(c)))(win_network_category(primary))))

    lines = ["Ferry %s self-test - %s" % (VERSION, time.strftime("%Y-%m-%d %H:%M:%S")), ""]
    for name, ok, detail in results:
        lines.append("%-38s %s   %s" % (name, "PASS" if ok else "FAIL", detail))
    passed = all(ok for _, ok, _ in results)
    lines += ["", "ALL PASSED" if passed else "SOME CHECKS FAILED - please send ferry-selftest.txt"]
    text = "\n".join(lines)
    print(text)
    try:
        with open(SELFTEST_FILE, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        print("\nSaved to " + SELFTEST_FILE)
    except OSError:
        print("\nCouldn't save ferry-selftest.txt - please send a photo of this window instead.")
    if not os.environ.get("FERRY_NO_WAIT"):
        try:
            if sys.stdin and sys.stdin.isatty():
                input("\nPress Enter to close...")
                return 0          # results are in the file; avoid a second pause from the launcher
        except Exception:
            pass
    return 0 if passed else 1


def install_desktop() -> int:
    if IS_WIN:
        print("Not needed on Windows - just double-click Ferry.bat.")
        return 0
    d = os.path.join(os.path.expanduser("~"), ".local", "share", "applications")
    os.makedirs(d, exist_ok=True)
    path = os.path.join(d, "ferry.desktop")
    py = sys.executable or "python3"
    content = ("[Desktop Entry]\nType=Application\nName=Ferry\nComment=Move files between your phone and this PC\n"
               "Exec=\"%s\" \"%s\"\nPath=%s\nIcon=%s\nTerminal=true\nCategories=Network;FileTransfer;\n"
               % (py, os.path.join(HERE, "ferry.py"), HERE, os.path.join(HERE, "ferry-icon.svg")))
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    os.chmod(path, 0o755)
    print("Added Ferry to your app menu: " + path)
    return 0


def run() -> int:
    try:
        return main()
    except KeyboardInterrupt:
        return 0
    except Exception:
        tb = traceback.format_exc()
        try:
            import platform
            with open(ERROR_LOG, "w", encoding="utf-8") as f:
                f.write("Ferry %s / Python %s / %s\n\n%s" % (VERSION, platform.python_version(), platform.platform(), tb))
        except Exception:
            pass
        print("\nFerry hit an unexpected error. Details saved to ferry-error.log")
        return 2


# --- BEGIN GENERATED (tools/build_assets.py) - do not edit by hand ---
PAGE_HTML = r'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#1d4ed8">
<title>Ferry</title>
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/icon-192.png">
<link rel="manifest" href="/manifest.webmanifest">
<style>
:root{--bg:#f8fafc;--card:#fff;--text:#0f172a;--muted:#475569;--line:#e2e8f0;--accent:#1d4ed8;--on-accent:#fff;
--ok:#15803d;--warn:#b45309;--bad:#b91c1c;--bar:#e2e8f0;--focus:#f59e0b;font-size:17px}
@media (prefers-color-scheme:dark){:root{--bg:#0f172a;--card:#1e293b;--text:#e2e8f0;--muted:#94a3b8;--line:#334155;
--accent:#60a5fa;--on-accent:#0f172a;--ok:#4ade80;--warn:#fbbf24;--bad:#f87171;--bar:#334155;--focus:#fbbf24}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;line-height:1.45}
main{max-width:560px;margin:0 auto;padding:12px 16px 40px}
header{display:flex;align-items:center;gap:10px;padding:6px 0 12px}
header svg{width:36px;height:36px;flex:none;border-radius:8px}
header h1{font-size:1.15rem;margin:0;font-weight:600}
header .to{color:var(--muted);font-weight:500}
.tabs{display:flex;gap:6px;background:var(--line);padding:4px;border-radius:12px;margin-bottom:14px}
.tabs button{flex:1;min-height:44px;border:0;border-radius:9px;background:transparent;color:var(--text);font:inherit;font-weight:600;cursor:pointer}
.tabs button[aria-selected=true]{background:var(--card);box-shadow:0 1px 2px rgba(0,0,0,.15)}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px;margin-bottom:12px}
.btn{display:flex;align-items:center;justify-content:center;gap:8px;width:100%;min-height:52px;padding:10px 16px;border-radius:12px;
font:inherit;font-weight:600;font-size:1.05rem;cursor:pointer;text-decoration:none;border:2px solid var(--accent)}
.btn.primary{background:var(--accent);color:var(--on-accent)}
.btn.secondary{background:transparent;color:var(--accent)}
.btn+.btn{margin-top:10px}
.small{font-size:.9rem;color:var(--muted)}
.linkbtn{background:none;border:0;color:var(--accent);font:inherit;font-weight:600;padding:10px 0;min-height:44px;cursor:pointer;text-decoration:underline}
a{color:var(--accent)}
:focus-visible{outline:4px solid var(--focus);outline-offset:2px}
.banner{border-radius:12px;padding:12px 14px;margin-bottom:12px;font-weight:500}
.banner.err{background:color-mix(in srgb,var(--bad) 14%,var(--card));border:1px solid var(--bad)}
.banner.info{background:color-mix(in srgb,var(--accent) 12%,var(--card));border:1px solid var(--accent)}
.banner button{margin-top:8px}
.bar{height:12px;background:var(--bar);border-radius:6px;overflow:hidden}
.bar>i{display:block;height:100%;width:0;background:var(--accent);transition:width .3s}
@media (prefers-reduced-motion:reduce){.bar>i{transition:none}}
.big{font-size:1.25rem;font-weight:700;margin:0 0 8px}
.stats{margin:8px 0 0;color:var(--muted)}
.awake{display:flex;align-items:center;gap:10px;margin-top:12px;color:var(--warn);font-weight:600}
.awake video{width:24px;height:24px;border-radius:4px;background:#000;flex:none}
ul.items{list-style:none;margin:0;padding:0}
ul.items li{display:flex;align-items:center;gap:10px;padding:9px 0;border-top:1px solid var(--line);min-height:44px}
ul.items li:first-child{border-top:0}
#p-list{position:relative;max-height:45vh;overflow-y:auto}
ul.items li.prob{flex-wrap:wrap;row-gap:2px}
ul.items li.prob .fsize{flex:0 0 100%;overflow-wrap:anywhere;color:var(--bad)}
.st{flex:none;width:7.4em;white-space:nowrap;font-weight:600;font-size:.92rem}
.st.ok{color:var(--ok)}.st.warn{color:var(--warn)}.st.bad{color:var(--bad)}.st.muted{color:var(--muted)}
.fname{flex:1;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.fsize{flex:none;color:var(--muted);font-size:.9rem}
.mini{display:inline-block;width:56px;height:6px;background:var(--bar);border-radius:3px;vertical-align:middle;margin-right:6px;overflow:hidden}
.mini>i{display:block;height:100%;background:var(--accent)}
.done-head{text-align:center}
.done-head .tick{font-size:2.6rem;color:var(--ok);line-height:1}
.outrow{display:flex;align-items:center;gap:12px;padding:12px 0;border-top:1px solid var(--line);text-decoration:none;color:var(--text);min-height:56px}
.outrow:first-child{border-top:0}
.outrow .meta{flex:1;min-width:0}
.outrow .nm{display:block;font-weight:600;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.outrow .sub{display:block}
.outrow .dl{flex:none;font-weight:700;color:var(--accent)}
.empty{color:var(--muted);text-align:center;padding:18px 6px}
.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}
details summary{cursor:pointer;min-height:44px;display:flex;align-items:center;font-weight:600}
/* TV (10-foot) */
body.tv{font-size:24px}
body.tv main{max-width:900px;padding-top:28px}
body.tv .tvrow{display:flex;align-items:center;gap:20px;padding:18px 0;border-top:1px solid var(--line)}
body.tv .tvrow:first-child{border-top:0}
body.tv .tvrow .meta{flex:1;min-width:0}
body.tv .tvrow .btn{width:auto;min-width:7em;margin:0}
body.tv .tvrow .btns{display:flex;gap:14px}
body.tv :focus-visible,body.tv a:focus,body.tv button:focus{outline:6px solid var(--focus);outline-offset:3px}
body.tv .btn:focus{background:var(--focus);color:#0f172a;border-color:var(--focus)}
[hidden]{display:none!important}
</style>
</head>
<body>
<main>
<header>
<svg viewBox="0 0 100 100" aria-hidden="true"><rect width="100" height="100" rx="22" fill="#1d4ed8"/><rect x="60" y="12" width="8" height="11" rx="1.5" fill="#fbbf24"/><rect x="28" y="22" width="46" height="30" rx="3" fill="#fff"/><rect x="37" y="28" width="7" height="24" fill="#1d4ed8"/><rect x="37" y="28" width="29" height="5" fill="#1d4ed8"/><rect x="37" y="39" width="21" height="5" fill="#1d4ed8"/><path d="M18 52 L82 52 L72 68 L28 68 Z" fill="#fff"/><path d="M14 80 q9 -6 18 0 t18 0 t18 0 t18 0" fill="none" stroke="#93c5fd" stroke-width="5" stroke-linecap="round"/></svg>
<h1>Ferry <span class="to">&rarr; <span id="pcname">{{PC_NAME}}</span></span></h1>
</header>

<div id="banner" class="banner err" role="alert" hidden>
  <div id="banner-text">Can't reach the PC. Is Ferry still running, and is the phone on the same Wi-Fi?</div>
  <button id="banner-retry" class="linkbtn" type="button">Retry</button>
</div>
<div id="note" class="banner info" role="status" hidden></div>
<p class="sr-only" id="live" aria-live="polite"></p>

<div id="tabs" class="tabs" role="tablist" aria-label="Direction">
  <button id="tab-send" role="tab" aria-selected="true" aria-controls="pane-send" type="button">Send to PC</button>
  <button id="tab-from" role="tab" aria-selected="false" aria-controls="pane-from" type="button">From PC <span id="badge"></span></button>
</div>

<section id="pane-send" role="tabpanel" aria-labelledby="tab-send">
  <div id="send-ready" class="card">
    <button id="pick-media-btn" class="btn primary" type="button">Photos &amp; videos</button>
    <button id="pick-any-btn" class="btn secondary" type="button">Any file</button>
    <p class="small" style="margin:14px 0 0">Uploads start as soon as you pick. Saves to <b id="inbox-label">Downloads/Phone</b> on the PC.</p>
    <p id="gps-hint" class="small" hidden>Need location kept? Use <b>Any file</b> &rarr; DCIM/Camera.</p>
    <p class="small" style="border-top:1px solid var(--line);margin:14px 0 0;padding-top:12px">Tip: in Chrome tap &#8942; &rarr; <b>Add to Home screen</b> for one-tap access.</p>
  </div>

  <div id="send-progress" class="card" hidden>
    <p id="p-title" class="big">Sending</p>
    <div class="bar" id="p-bar" role="progressbar" aria-label="Upload progress" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0"><i id="p-fill"></i></div>
    <p id="p-stats" class="stats"></p>
    <p id="p-warn" class="small" hidden></p>
    <div class="awake" id="awake"><video id="keepawake" muted loop playsinline preload="auto" aria-hidden="true" src="{{KEEPAWAKE_SRC}}"></video><span>Keep this screen on until it finishes.</span></div>
    <ul class="items" id="p-list" style="margin-top:12px"></ul>
    <button id="cancel-btn" class="btn secondary" type="button" style="margin-top:12px">Cancel</button>
  </div>

  <div id="send-done" class="card" hidden>
    <div class="done-head"><div class="tick" id="d-icon" aria-hidden="true">&#10003;</div><p class="big" id="d-title">All done</p>
    <p id="d-line1" style="margin:0"></p><p id="d-line2" style="margin:0"></p><p id="d-line3" class="small" style="margin:4px 0 14px"></p>
    <p id="d-help" style="margin:0 0 14px" hidden></p></div>
    <button id="more-btn" class="btn primary" type="button">Send more</button>
    <button id="retry-btn" class="btn secondary" type="button" hidden>Retry failed</button>
    <details style="margin-top:10px"><summary>Show details</summary><ul class="items" id="d-list"></ul></details>
  </div>
  <input id="pick-media" type="file" multiple accept="image/*,video/*" hidden>
  <input id="pick-any" type="file" multiple hidden>
</section>

<section id="pane-from" role="tabpanel" aria-labelledby="tab-from" hidden>
  <div class="card">
    <p id="o-summary" class="big" style="font-size:1.05rem"></p>
    <button id="dl-all" class="btn primary" type="button" hidden>&#11015; Download all</button>
    <p id="dl-all-hint" class="small" hidden>If Chrome asks to allow multiple downloads, tap <b>Allow</b>.</p>
    <div id="o-list" style="margin-top:6px"></div>
    <p id="o-empty" class="empty" hidden>Nothing to send yet. On the PC, drop files into <b>Desktop &rarr; Ferry outbox</b> and they'll appear here.</p>
    <p id="o-folders" class="small" hidden>Folders aren't sent. On the PC, put files in directly or zip the folder.</p>
    <p id="o-zip" class="small" hidden><a id="o-zip-link" href="/api/v1/outbox.zip" download>Get all as one .zip</a></p>
    <p class="small" id="o-where">Files go to Downloads; photos and videos also show in Gallery.</p>
  </div>
</section>

<section id="pane-tv" hidden>
  <div class="card">
    <p id="tv-summary" class="big"></p>
    <div id="tv-list"></div>
    <p id="tv-empty" class="empty" hidden>Nothing to send yet. On the PC, put files in <b>Desktop &rarr; Ferry outbox</b>. This page updates by itself.</p>
  </div>
  <p class="small">Tip: bookmark this page so next time it's one click. &middot; <a href="/?mode=phone">Phone mode</a></p>
</section>
</main>

<script>
/*QUEUE-START*/
var FerryCore = (function () {
  'use strict';
  function fmtBytes(n) {
    n = Number(n) || 0;
    if (n < 1000) return n + ' B';
    var u = ['KB', 'MB', 'GB', 'TB'], i = -1;
    do { n /= 1000; i++; } while (n >= 1000 && i < u.length - 1);
    return (n >= 100 ? n.toFixed(0) : n.toFixed(1)) + ' ' + u[i];
  }
  function fmtSpeed(bps) { return bps > 0 ? (bps / 1e6).toFixed(1) + ' MB/s' : ''; }
  function fmtEta(sec) {
    if (!isFinite(sec) || sec < 0) return '';
    sec = Math.round(sec);
    if (sec < 5) return 'a few seconds left';
    if (sec < 60) return 'about ' + sec + ' s left';
    var m = Math.round(sec / 60);
    if (m < 60) return 'about ' + m + ' min left';
    return 'about ' + Math.floor(m / 60) + ' h ' + (m % 60) + ' min left';
  }
  function midTrunc(s, max) {
    s = String(s);
    if (s.length <= max) return s;
    var keep = max - 1, head = Math.ceil(keep / 2);
    return s.slice(0, head) + '…' + s.slice(s.length - (keep - head));
  }
  function relTime(ms, now) {
    var d = (now - ms) / 1000;
    if (d < 60) return 'just now';
    if (d < 3600) return Math.round(d / 60) + ' min ago';
    var a = new Date(ms), b = new Date(now);
    var hh = ('0' + a.getHours()).slice(-2) + ':' + ('0' + a.getMinutes()).slice(-2);
    if (a.toDateString() === b.toDateString()) return 'today ' + hh;
    return a.toLocaleDateString() + ' ' + hh;
  }

  var DONE = { saved: 1, renamed: 1, skipped: 1, failed: 1, notsent: 1 };

  function createQueue(opts) {
    var api = opts.api, now = opts.now, isHidden = opts.isHidden, sleep = opts.sleep;
    var onChange = opts.onChange || function () {};
    var items = [], running = false, cancelled = false, stopped = null, current = null;
    var sendStart = null, sendingMs = 0;

    function emit() { onChange(); }
    function add(files) {
      for (var i = 0; i < files.length; i++) {
        var f = files[i];
        items.push({ file: f, name: f.name, size: f.size, status: 'waiting', sent: 0, savedAs: null,
          reason: null, retried: false, hiddenSeen: false });
      }
      emit();
    }
    function nextWaiting() {
      for (var i = 0; i < items.length; i++) if (items[i].status === 'waiting') return items[i];
      return null;
    }
    function stopRest(why, reason) {
      stopped = why;
      items.forEach(function (x) {
        if (x.status === 'waiting' || x.status === 'paused') { x.status = 'notsent'; x.reason = reason; }
      });
    }
    function stopDiskFull(it) {
      if (it) { it.status = 'failed'; it.reason = 'PC disk is full'; }
      stopRest('disk_full', 'Not sent - PC disk full');
    }
    async function netFail(it) {
      var wasHidden = it.hiddenSeen;
      it.sent = 0; it.hiddenSeen = false;
      // Screen is off now: park it; resumeVisible() re-queues it when the screen comes back.
      if (isHidden()) { it.status = 'paused'; return; }
      // Screen went off during this attempt but is on again: nobody would resume a parked item, so try again now (free).
      if (wasHidden) { it.status = 'waiting'; emit(); await sleep(2000); return; }
      if (!it.retried) { it.retried = true; it.status = 'waiting'; emit(); await sleep(2000); return; }
      it.status = 'failed'; it.reason = 'Connection lost';
      // Is it this file, or is the PC gone? If the PC is gone, stop instead of failing every remaining file slowly.
      if (api.ping) {
        emit();
        var p = await api.ping();
        if (p.kind === 'network' && !cancelled && !isHidden()) stopRest('unreachable', 'Not sent - PC not reachable');
      }
    }
    async function process(it) {
      it.status = 'checking'; it.hiddenSeen = isHidden(); emit();
      var c = await api.check(it.file);
      if (cancelled) return;
      if (c.kind === 'network') return netFail(it);
      if (c.kind === 'http') { it.status = 'failed'; it.reason = 'PC error (' + c.status + ')'; return; }
      var j = c.json || {};
      if (j.ok === false && j.reason === 'disk_full') { stopDiskFull(it); return; }
      if (j.duplicate) { it.status = 'skipped'; it.savedAs = j.duplicate; it.sent = it.size; return; }
      it.status = 'sending'; it.sent = 0; emit();
      sendStart = now();
      var h = api.upload(it.file, function (sent) { it.sent = sent; emit(); });
      current = { item: it, abort: h.abort };
      var r = await h.promise;
      current = null;
      sendingMs += now() - sendStart; sendStart = null;
      if (cancelled) { it.status = 'notsent'; it.reason = 'Cancelled'; it.sent = 0; return; }
      if (r.kind === 'ok') {
        var res = (r.json && r.json.result) || 'saved';
        it.status = (res === 'renamed' || res === 'skipped') ? res : 'saved';
        it.savedAs = (r.json && r.json.saved_as) || it.name; it.sent = it.size; return;
      }
      if (r.kind === 'http') {
        if (r.status === 507 || (r.json && r.json.reason === 'disk_full')) { stopDiskFull(it); return; }
        it.status = 'failed'; it.sent = 0;
        it.reason = (r.json && r.json.message) || ('PC error (' + r.status + ')'); return;
      }
      return netFail(it);
    }
    async function run() {
      if (running) return;
      running = true; cancelled = false; stopped = null; emit();
      try {
        while (!cancelled && !stopped) {
          var it = nextWaiting();
          if (!it) break;
          await process(it);
          emit();
        }
      } finally { running = false; emit(); }
    }
    function cancel() {
      cancelled = true;
      if (current) { try { current.abort(); } catch (e) {} }
      items.forEach(function (x) {
        if (!DONE[x.status]) { x.status = 'notsent'; x.reason = 'Cancelled'; x.sent = 0; }
      });
      emit();
    }
    function noteHidden() { if (current) current.item.hiddenSeen = true; }
    function resumeVisible() {
      var n = 0;
      items.forEach(function (x) { if (x.status === 'paused') { x.status = 'waiting'; n++; } });
      if (n && !running) run();
      return n;
    }
    function retryFailed() {
      var n = 0;
      items.forEach(function (x) {
        if (x.status === 'failed' || x.status === 'notsent' || x.status === 'paused') {
          x.status = 'waiting'; x.retried = false; x.reason = null; x.sent = 0; n++;
        }
      });
      stopped = null;
      if (n) run();
      return n;
    }
    function reset() { if (!running) { items = []; stopped = null; cancelled = false; sendingMs = 0; emit(); } }
    function stats() {
      var s = { total: items.length, saved: 0, renamed: 0, skipped: 0, failed: 0, notsent: 0, paused: 0,
        waiting: 0, active: 0, bytesTotal: 0, bytesDone: 0, index: 0 };
      items.forEach(function (x, i) {
        s.bytesTotal += x.size;
        if (DONE[x.status]) { s[x.status]++; if (x.status !== 'failed' && x.status !== 'notsent') s.bytesDone += x.size; }
        else if (x.status === 'paused') s.paused++;
        else if (x.status === 'waiting') s.waiting++;
        else { s.active++; s.bytesDone += x.sent; s.index = i + 1; }
      });
      if (!s.index) s.index = Math.min(s.total, s.saved + s.renamed + s.skipped + s.failed + s.notsent + 1);
      s.finished = !running && s.waiting === 0 && s.paused === 0 && s.active === 0 && s.total > 0;
      s.stopped = stopped; s.cancelled = cancelled; s.running = running;
      return s;
    }
    function elapsedSending() { return sendingMs + (sendStart ? now() - sendStart : 0); }
    return { add: add, run: run, cancel: cancel, noteHidden: noteHidden, resumeVisible: resumeVisible,
      retryFailed: retryFailed, reset: reset, stats: stats, items: function () { return items; },
      isRunning: function () { return running; }, elapsedSending: elapsedSending };
  }

  function createSpeed(now) {
    var samples = [];
    return {
      add: function (bytes) { var t = now(); samples.push([t, bytes]); while (samples.length > 2 && t - samples[0][0] > 3000) samples.shift(); },
      bps: function () {
        if (samples.length < 2) return 0;
        var a = samples[0], b = samples[samples.length - 1], dt = (b[0] - a[0]) / 1000;
        return dt > 0.2 ? Math.max(0, (b[1] - a[1]) / dt) : 0;
      },
      reset: function () { samples = []; }
    };
  }
  return { fmtBytes: fmtBytes, fmtSpeed: fmtSpeed, fmtEta: fmtEta, midTrunc: midTrunc, relTime: relTime,
    createQueue: createQueue, createSpeed: createSpeed };
})();
if (typeof module !== 'undefined') module.exports = FerryCore;
/*QUEUE-END*/

(function () {
  'use strict';
  var C = FerryCore;
  var $ = function (id) { return document.getElementById(id); };
  var params = new URLSearchParams(location.search);
  var isTV = /AFT|Silk/.test(navigator.userAgent) && params.get('mode') !== 'phone';
  var SHOW_GPS_HINT = false;
  var info = null, lastOutbox = null, activeTab = 'send';

  // ---------------- API ----------------
  function fetchJSON(url, ms) {
    var ctl = new AbortController();
    var t = setTimeout(function () { ctl.abort(); }, ms || 15000);
    return fetch(url, { cache: 'no-store', signal: ctl.signal }).then(function (r) {
      clearTimeout(t);
      return r.json().then(function (j) { return r.ok ? { kind: 'ok', json: j } : { kind: 'http', status: r.status, json: j }; },
        function () { return { kind: 'http', status: r.status, json: null }; });
    }, function () { clearTimeout(t); return { kind: 'network' }; });
  }
  function qs(f) {
    return 'name=' + encodeURIComponent(f.name) + '&size=' + f.size + '&mtime=' + (f.lastModified || '');
  }
  var api = {
    info: function () { return fetchJSON('/api/v1/info', 8000); },
    outbox: function () { return fetchJSON('/api/v1/outbox', 10000); },
    ping: function () { return fetchJSON('/api/v1/info', 8000).then(reach); },
    check: function (f) { return fetchJSON('/api/v1/check?' + qs(f), 20000).then(reach); },
    upload: function (f, onProgress) {
      var xhr = new XMLHttpRequest(), done, stallT = null, waitT = null, settled = false;
      var promise = new Promise(function (res) { done = res; });
      function finish(v) { if (settled) return; settled = true; clearTimeout(stallT); clearTimeout(waitT); done(reach(v)); }
      function arm() { clearTimeout(stallT); stallT = setTimeout(function () { xhr.abort(); finish({ kind: 'network' }); }, 60000); }
      xhr.open('POST', '/api/v1/upload?' + qs(f));
      xhr.upload.onprogress = function (e) { arm(); onProgress(e.loaded); };
      xhr.upload.onload = function () {           // body fully sent: PC may now be checking duplicates
        clearTimeout(stallT);
        waitT = setTimeout(function () { xhr.abort(); finish({ kind: 'network' }); }, 600000);
      };
      xhr.onload = function () {
        var j = null; try { j = JSON.parse(xhr.responseText); } catch (e) {}
        finish(xhr.status === 200 ? { kind: 'ok', json: j } : { kind: 'http', status: xhr.status, json: j });
      };
      xhr.onerror = function () { finish({ kind: 'network' }); };
      xhr.onabort = function () { finish({ kind: 'network' }); };
      arm();
      xhr.send(f);
      return { promise: promise, abort: function () { xhr.abort(); } };
    }
  };
  function reach(r) {
    if (r.kind === 'network' && document.visibilityState === 'visible') showBanner(true);
    else if (r.kind !== 'network') showBanner(false);
    return r;
  }
  function showBanner(on) { $('banner').hidden = !on; }
  var noteTimer = null;
  function note(text, ms) {
    $('note').textContent = text; $('note').hidden = false;
    clearTimeout(noteTimer); noteTimer = setTimeout(function () { $('note').hidden = true; }, ms || 5000);
  }

  // ---------------- Send to PC ----------------
  var speed = C.createSpeed(function () { return Date.now(); });
  var queue = C.createQueue({
    api: api, now: function () { return Date.now(); },
    isHidden: function () { return document.visibilityState === 'hidden'; },
    sleep: function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); },
    onChange: schedule
  });
  var rows = new Map(), rafPending = false, lastLive = 0, lastCur = null;

  function schedule() { if (!rafPending) { rafPending = true; requestAnimationFrame(render); } }

  var LABEL = {
    waiting: ['Waiting', 'muted', '·'], checking: ['Checking', 'muted', '↻'], sending: ['Sending', 'muted', '↻'],
    saved: ['Saved', 'ok', '✓'], renamed: ['Saved as', 'ok', '✓'], skipped: ['Already on PC', 'warn', '='],
    failed: ['Failed', 'bad', '✗'], notsent: ['Not sent', 'bad', '✗'], paused: ['Paused', 'warn', '⏸']
  };
  function rowFor(it, list, chars) {
    var r = rows.get(it);
    if (!r) {
      var li = document.createElement('li');
      li.innerHTML = '<span class="st"></span><span class="fname"></span><span class="fsize"></span>';
      r = { li: li, st: li.children[0], nm: li.children[1], sz: li.children[2], key: '' };
      rows.set(it, r);
    }
    if (r.li.parentNode !== list) list.appendChild(r.li);
    var L = LABEL[it.status] || ['', 'muted', ''];
    var pct = it.size ? Math.floor(100 * it.sent / it.size) : 0;
    var key = it.status + pct + (it.savedAs || '') + (it.reason || '') + '|' + chars;
    if (key !== r.key) {
      r.key = key;
      r.st.className = 'st ' + L[1];
      if (it.status === 'sending') r.st.innerHTML = '<span class="mini"><i style="width:' + pct + '%"></i></span>' + pct + '%';
      else r.st.textContent = L[2] + ' ' + L[0];
      var nm = it.status === 'renamed' ? '"' + it.savedAs + '"' : it.name;
      r.nm.textContent = C.midTrunc(nm, chars);
      r.nm.title = nm;
      var prob = (it.status === 'failed' || it.status === 'notsent') && it.reason;
      r.li.className = prob ? 'prob' : '';             // the reason gets its own line under the name
      r.sz.textContent = prob ? it.reason : C.fmtBytes(it.size);
    }
    return r;
  }
  function nameChars(el) {
    var w = (el && el.clientWidth) || 320;
    return Math.max(12, Math.floor((w - 205) / 9.5));   // minus status + size columns
  }
  function render() {
    rafPending = false;
    var s = queue.stats(), items = queue.items();
    if (!s.total) return;
    speed.add(s.bytesDone);
    if (s.finished) return renderDone(s, items);
    $('send-ready').hidden = true; $('send-done').hidden = true; $('send-progress').hidden = false;
    var pct = s.bytesTotal ? Math.floor(100 * s.bytesDone / s.bytesTotal) : 100;
    $('p-fill').style.width = pct + '%';
    $('p-bar').setAttribute('aria-valuenow', String(pct));
    var title = s.stopped === 'disk_full' ? 'PC disk is full' :
      s.paused && !s.active && !s.waiting ? 'Paused — continues when the screen is on' :
      'Sending ' + Math.max(1, s.index) + ' of ' + s.total;
    $('p-title').textContent = title;
    var bps = speed.bps();
    var eta = bps > 0 ? C.fmtEta((s.bytesTotal - s.bytesDone) / bps) : '';
    $('p-stats').textContent = C.fmtBytes(s.bytesDone) + ' of ' + C.fmtBytes(s.bytesTotal) +
      (bps ? ' · ' + C.fmtSpeed(bps) : '') + (eta ? ' · ' + eta : '');
    var list = $('p-list');
    var chars = nameChars(list);
    items.forEach(function (it) { rowFor(it, list, chars); });
    var cur = null;
    for (var i = 0; i < items.length; i++) if (items[i].status === 'sending' || items[i].status === 'checking') { cur = rows.get(items[i]); break; }
    // Follow the current file inside the list box only, and only when it changes - never scroll the page itself.
    if (cur && cur !== lastCur) { lastCur = cur; list.scrollTop = Math.max(0, cur.li.offsetTop - list.clientHeight / 2); }
    var t = Date.now();
    if (t - lastLive > 5000) { lastLive = t; $('live').textContent = title + ', ' + pct + ' percent'; }
  }
  var doneShown = false;
  function renderDone(s, items) {
    if (doneShown) return;
    doneShown = true;
    stopAwake();
    $('send-progress').hidden = true; $('send-ready').hidden = true; $('send-done').hidden = false;
    var bad = s.failed + s.notsent;
    $('d-icon').textContent = bad ? '!' : '✓';
    $('d-icon').style.color = bad ? 'var(--bad)' : 'var(--ok)';
    $('d-title').textContent = s.cancelled ? 'Stopped' : s.stopped === 'disk_full' ? 'PC disk is full' :
      s.stopped === 'unreachable' ? "Can't reach the PC" : bad ? 'Finished with problems' : 'All done';
    $('d-line1').textContent = (s.saved + s.renamed) + ' saved · ' + s.skipped + ' already on PC';
    $('d-line2').textContent = s.renamed + ' renamed · ' + bad + ' not sent';
    var secs = queue.elapsedSending() / 1000, sent = 0;
    items.forEach(function (x) { if (x.status === 'saved' || x.status === 'renamed') sent += x.size; });
    $('d-line3').textContent = sent ? C.fmtBytes(sent) + ' in ' + Math.max(1, Math.round(secs)) + ' s' +
      (secs > 0.5 ? ' (' + C.fmtSpeed(sent / secs) + ')' : '') : '';
    $('retry-btn').hidden = !bad;
    $('retry-btn').textContent = 'Retry ' + bad + ' not sent';
    var list = $('d-list');
    var chars = nameChars($('send-done'));
    items.forEach(function (it) { rowFor(it, list, chars); });
    $('live').textContent = $('d-title').textContent + '. ' + $('d-line1').textContent;
    var help = s.cancelled ? '' : s.stopped === 'disk_full' ? 'Free up space on the PC, then tap "' + $('retry-btn').textContent + '".' :
      s.stopped === 'unreachable' ? 'Check that Ferry is still running on the PC and the phone is on the same Wi-Fi, then tap "' + $('retry-btn').textContent + '".' : '';
    $('d-help').textContent = help; $('d-help').hidden = !help;
    if (navigator.vibrate) { try { navigator.vibrate(200); } catch (e) {} }
  }
  function startAwake() {
    var v = $('keepawake');
    if (!v.getAttribute('src')) { v.hidden = true; return; }
    try { var p = v.play(); if (p && p.catch) p.catch(function () {}); } catch (e) {}
  }
  function stopAwake() { try { $('keepawake').pause(); } catch (e) {} }

  function picked(input) {
    var files = Array.prototype.slice.call(input.files || []);
    input.value = '';
    if (!files.length) return;
    startAwake();
    if (!queue.isRunning() && queue.stats().finished) { queue.reset(); rows.clear(); $('p-list').innerHTML = ''; $('d-list').innerHTML = ''; }
    speed.reset(); doneShown = false;
    queue.add(files);
    var total = files.reduce(function (a, f) { return a + f.size; }, 0);
    api.info().then(function (r) {
      if (r.kind === 'ok' && r.json.free_bytes != null && r.json.free_bytes < total) {
        $('p-warn').textContent = 'The PC may not have enough free space (' + C.fmtBytes(r.json.free_bytes) + ' free).';
        $('p-warn').hidden = false;
      } else { $('p-warn').hidden = true; }
    });
    queue.run();
  }
  $('pick-media-btn').onclick = function () { startAwake(); $('pick-media').click(); };
  $('pick-any-btn').onclick = function () { startAwake(); $('pick-any').click(); };
  // Picker closed without choosing anything: nothing will start, so let the screen sleep again.
  ['pick-media', 'pick-any'].forEach(function (id) {
    $(id).addEventListener('cancel', function () { if (!queue.isRunning()) stopAwake(); });
  });
  $('pick-media').onchange = function () { picked(this); };
  $('pick-any').onchange = function () { picked(this); };
  $('cancel-btn').onclick = function () {
    if (queue.elapsedSending() > 10000 && !confirm('Stop sending? Files already saved stay on the PC.')) return;
    queue.cancel();
  };
  $('more-btn').onclick = function () {
    queue.reset(); rows.clear(); $('p-list').innerHTML = ''; $('d-list').innerHTML = ''; doneShown = false;
    $('send-done').hidden = true; $('send-ready').hidden = false; showBanner(false);
  };
  $('retry-btn').onclick = function () { showBanner(false); startAwake(); speed.reset(); doneShown = false; queue.retryFailed(); };
  window.addEventListener('beforeunload', function (e) {
    if (queue.isRunning()) { e.preventDefault(); e.returnValue = 'Uploads in progress'; return 'Uploads in progress'; }
  });
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') { queue.noteHidden(); return; }
    if (queue.resumeVisible()) note('Paused while the screen was off — continuing');
    if (activeTab === 'from' || isTV) refreshOutbox();
  });

  // ---------------- From PC ----------------
  function setTab(t) {
    activeTab = t;
    $('tab-send').setAttribute('aria-selected', String(t === 'send'));
    $('tab-from').setAttribute('aria-selected', String(t === 'from'));
    $('pane-send').hidden = t !== 'send'; $('pane-from').hidden = t !== 'from';
    if (t === 'from') refreshOutbox();
  }
  $('tab-send').onclick = function () { setTab('send'); };
  $('tab-from').onclick = function () { setTab('from'); };
  $('tabs').addEventListener('keydown', function (e) {
    if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
      var t = activeTab === 'send' ? 'from' : 'send'; setTab(t); $(t === 'send' ? 'tab-send' : 'tab-from').focus(); e.preventDefault();
    }
  });

  function refreshOutbox() {
    return api.outbox().then(function (r) {
      if (r.kind !== 'ok') { if (r.kind === 'network' && document.visibilityState === 'visible') showBanner(true); return; }
      showBanner(false);
      lastOutbox = r.json;
      var n = r.json.files.length;
      $('badge').textContent = n ? '(' + n + ')' : '';
      if (isTV) renderTV(r.json); else renderOutbox(r.json);
    });
  }
  function dlLink(f) { return f.url; }
  function renderOutbox(o) {
    var files = o.files, list = $('o-list'), now = Date.now();
    $('o-summary').textContent = files.length ? files.length + ' file' + (files.length > 1 ? 's' : '') + ' · ' + C.fmtBytes(o.total_size) : 'From PC';
    $('o-empty').hidden = files.length > 0;
    $('dl-all').hidden = files.length < 2; $('dl-all-hint').hidden = files.length < 2; $('o-zip').hidden = files.length < 2;
    $('o-folders').hidden = !o.folders_ignored;
    var keep = {};
    files.forEach(function (f) { keep[f.id] = 1; });
    Array.prototype.slice.call(list.children).forEach(function (el) { if (!keep[el.dataset.id]) list.removeChild(el); });
    files.forEach(function (f, i) {
      var a = list.querySelector('[data-id="' + f.id + '"]');
      if (!a) {
        a = document.createElement('a'); a.className = 'outrow'; a.dataset.id = f.id; a.href = dlLink(f);
        a.setAttribute('download', f.name);
        a.innerHTML = '<span class="meta"><span class="nm"></span><span class="small sub"></span></span><span class="dl" aria-hidden="true">⬇</span>';
        a.querySelector('.nm').textContent = C.midTrunc(f.name, window.innerWidth < 480 ? 32 : 60);
        a.querySelector('.nm').title = f.name;
        a.addEventListener('click', function () {
          var d = a.querySelector('.dl'); d.textContent = 'Started ↓'; a.style.pointerEvents = 'none';
          setTimeout(function () { d.textContent = '⬇'; a.style.pointerEvents = ''; }, 3000);
        });
      }
      a.querySelector('.sub').textContent = C.fmtBytes(f.size) + ' · ' + C.relTime(f.mtime, now);
      a.setAttribute('aria-label', 'Download ' + f.name + ', ' + C.fmtBytes(f.size));
      if (list.children[i] !== a) list.insertBefore(a, list.children[i] || null);
    });
  }
  $('dl-all').onclick = function () {
    if (!lastOutbox) return;
    var files = lastOutbox.files.slice().reverse(), i = 0;
    $('dl-all').disabled = true;
    (function next() {
      if (i >= files.length) { $('dl-all').disabled = false; note('Started ' + files.length + ' downloads'); return; }
      var a = document.createElement('a'); a.href = dlLink(files[i]); a.setAttribute('download', files[i].name);
      document.body.appendChild(a); a.click(); document.body.removeChild(a); i++;
      setTimeout(next, 1000);
    })();
  };

  function renderTV(o) {
    var files = o.files, list = $('tv-list');
    $('tv-summary').textContent = files.length ? files.length + ' file' + (files.length > 1 ? 's' : '') + ' from ' + $('pcname').textContent : 'From ' + $('pcname').textContent;
    $('tv-empty').hidden = files.length > 0;
    var sig = files.map(function (f) { return f.id; }).join(',');
    if (list.dataset.sig === sig) return;
    var hadFocus = list.contains(document.activeElement);
    list.dataset.sig = sig; list.innerHTML = '';
    files.forEach(function (f) {
      var row = document.createElement('div'); row.className = 'tvrow';
      row.innerHTML = '<div class="meta"><div class="nm" style="font-weight:600"></div><div class="small"></div></div><div class="btns"></div>';
      row.querySelector('.nm').textContent = C.midTrunc(f.name, 40);
      row.querySelector('.small').textContent = C.fmtBytes(f.size);
      var btns = row.querySelector('.btns');
      if (f.kind === 'video_playable') {
        var p = document.createElement('a'); p.className = 'btn primary'; p.href = f.url + '?inline=1'; p.textContent = '▶ Play';
        btns.appendChild(p);
      }
      var s = document.createElement('a'); s.className = 'btn ' + (f.kind === 'video_playable' ? 'secondary' : 'primary');
      s.href = f.url; s.setAttribute('download', f.name); s.textContent = f.kind === 'apk' ? 'Install / Save' : 'Save';
      btns.appendChild(s);
      list.appendChild(row);
    });
    var first = list.querySelector('a');
    if (first && (hadFocus || !document.activeElement || document.activeElement === document.body)) first.focus();
  }

  // ---------------- Boot ----------------
  $('banner-retry').onclick = function () { boot(); };
  function boot() {
    return api.info().then(function (r) {
      if (r.kind !== 'ok') { showBanner(true); return; }
      showBanner(false);
      info = r.json;
      $('pcname').textContent = info.name;
      $('inbox-label').textContent = info.inbox_label;
      refreshOutbox();
    });
  }
  if (isTV) {
    document.body.classList.add('tv');
    $('tabs').hidden = true; $('pane-send').hidden = true; $('pane-tv').hidden = false;
  }
  $('gps-hint').hidden = !SHOW_GPS_HINT;
  boot();
  setInterval(function () {
    if (document.visibilityState !== 'visible') return;
    if (isTV || activeTab === 'from') refreshOutbox();
  }, 5000);
  setInterval(function () {
    if (document.visibilityState === 'visible' && !isTV && activeTab === 'send' && !queue.isRunning()) refreshOutbox();
  }, 15000);
})();
</script>
</body>
</html>
'''
DIAG_HTML = r'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ferry - device check</title>
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<style>
body{font:17px system-ui,sans-serif;margin:0;background:#f8fafc;color:#0f172a}
@media (prefers-color-scheme:dark){body{background:#0f172a;color:#e2e8f0}.card{background:#1e293b!important;border-color:#334155!important}a{color:#93c5fd}}
main{max-width:620px;margin:0 auto;padding:14px 16px 40px}
h1{font-size:1.3rem}h2{font-size:1.05rem;margin:0 0 8px}
.card{background:#fff;border:1px solid #e2e8f0;border-radius:14px;padding:14px;margin-bottom:12px}
button,a.b{display:inline-flex;align-items:center;min-height:46px;padding:8px 14px;margin:4px 6px 4px 0;border-radius:10px;border:2px solid #2563eb;
background:#1d4ed8;color:#fff;font:inherit;font-weight:600;text-decoration:none;cursor:pointer}
:focus-visible{outline:4px solid #f59e0b;outline-offset:2px}
pre{white-space:pre-wrap;word-break:break-word;background:rgba(127,127,127,.12);padding:10px;border-radius:8px;font-size:.9rem;min-height:1.4em}
video{width:24px;height:24px;background:#000;vertical-align:middle}
</style>
</head>
<body>
<main>
<h1>Ferry device check <small style="font-weight:400">v{{VERSION}}</small></h1>
<p>Run each test and note the result on DEVICE-CHECKLIST.txt. Nothing here is saved on the PC.</p>

<div class="card"><h2>1. This device</h2><pre id="dev"></pre></div>

<div class="card"><h2>2. Photo picker (GPS &amp; dates)</h2>
<p>Pick <b>one photo taken with location on</b>, first with each button.</p>
<button id="b-media" type="button">Photos &amp; videos picker</button>
<button id="b-any" type="button">Any file picker</button>
<input id="i-media" type="file" accept="image/*,video/*" hidden><input id="i-any" type="file" hidden>
<pre id="pick"></pre></div>

<div class="card"><h2>3. Keep screen awake</h2>
<p>Tap Start, then put the phone down and don't touch it for 2 minutes.</p>
<button id="b-awake" type="button">Start</button> <video id="v" muted loop playsinline src="{{KEEPAWAKE_SRC}}"></video>
<pre id="awake"></pre></div>

<div class="card"><h2>4. Speed</h2>
<button id="b-down" type="button">Test download (200 MB)</button>
<button id="b-up" type="button">Test upload (200 MB)</button>
<pre id="speed"></pre></div>

<div class="card"><h2>5. Downloads</h2>
<a class="b" href="/api/v1/diag/test.zip" download>Test .zip</a>
<a class="b" href="/api/v1/diag/ferry-test.apk" download>Test .apk</a>
<p>The zip should open in the Files app and contain two text files. The .apk is a placeholder: Chrome may warn, and it will fail to install &mdash; that's expected. Note what Chrome shows.</p></div>

<p><a href="/">&larr; Back to Ferry</a></p>
</main>
<script>
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  $('dev').textContent = 'User agent: ' + navigator.userAgent +
    '\nMode: ' + (/AFT|Silk/.test(navigator.userAgent) ? 'TV' : 'phone/desktop') +
    '\nScreen: ' + innerWidth + 'x' + innerHeight + ' @' + devicePixelRatio + 'x' +
    '\nTouch: ' + (matchMedia('(pointer: coarse)').matches ? 'yes' : 'no') +
    '\nWake Lock API: ' + ('wakeLock' in navigator ? 'present' : 'absent') +
    ' (secure context: ' + window.isSecureContext + ')';
  function inspect(input, label) {
    var f = input.files[0]; input.value = '';
    if (!f) return;
    $('pick').textContent += label + ': sending ' + f.name + ' ...\n';
    var xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/v1/diag/inspect?name=' + encodeURIComponent(f.name) + '&mtime=' + (f.lastModified || ''));
    xhr.onload = function () {
      try {
        var j = JSON.parse(xhr.responseText);
        var age = j.age_seconds == null ? '?' : j.age_seconds < 120 ? j.age_seconds + ' s (looks like "now" - date NOT kept)' : Math.round(j.age_seconds / 86400) + ' days (original date kept)';
        $('pick').textContent += label + ': ' + j.name + ' | ' + j.size + ' bytes | GPS: ' + j.gps.toUpperCase() + ' | file date age: ' + age + '\n';
      } catch (e) { $('pick').textContent += label + ': error ' + xhr.status + '\n'; }
    };
    xhr.onerror = function () { $('pick').textContent += label + ': network error\n'; };
    xhr.send(f);
  }
  $('b-media').onclick = function () { $('i-media').click(); };
  $('b-any').onclick = function () { $('i-any').click(); };
  $('i-media').onchange = function () { inspect(this, 'Photos & videos'); };
  $('i-any').onchange = function () { inspect(this, 'Any file'); };

  var t0 = 0, hidden = 0, timer = null;
  $('b-awake').onclick = function () {
    var v = $('v');
    if (v.getAttribute('src')) { var p = v.play(); if (p && p.catch) p.catch(function () {}); }
    t0 = Date.now(); hidden = 0; clearInterval(timer);
    timer = setInterval(function () {
      $('awake').textContent = 'Visible for ' + Math.round((Date.now() - t0) / 1000) + ' s' +
        (hidden ? ' | screen went off ' + hidden + ' time(s) - keep-awake did NOT work' : ' | screen still on') +
        (v.getAttribute('src') ? '' : ' | (no keep-awake video built in)');
    }, 1000);
  };
  document.addEventListener('visibilitychange', function () { if (document.visibilityState === 'hidden' && t0) hidden++; });

  $('b-down').onclick = function () {
    $('speed').textContent = 'Downloading...';
    var t = performance.now();
    fetch('/api/v1/diag/blob?mb=200', { cache: 'no-store' }).then(function (r) { return r.arrayBuffer(); }).then(function (b) {
      var s = (performance.now() - t) / 1000;
      $('speed').textContent = 'Download: ' + (b.byteLength / s / 1e6).toFixed(1) + ' MB/s';
    }, function () { $('speed').textContent = 'Download failed'; });
  };
  $('b-up').onclick = function () {
    $('speed').textContent = 'Uploading...';
    var blob = new Blob([new Uint8Array(200 * 1000 * 1000)]);
    var t = performance.now(), xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/v1/diag/sink');
    xhr.onload = function () {
      var s = (performance.now() - t) / 1000;
      $('speed').textContent = 'Upload: ' + (blob.size / s / 1e6).toFixed(1) + ' MB/s';
    };
    xhr.onerror = function () { $('speed').textContent = 'Upload failed'; };
    xhr.send(blob);
  };
})();
</script>
</body>
</html>
'''
FAVICON_SVG = r'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="512" height="512">
  <title>Ferry</title>
  <rect x="0" y="0" width="100" height="100" rx="22" fill="#1d4ed8"/>
  <rect x="60" y="12" width="8" height="11" rx="1.5" fill="#fbbf24"/>
  <rect x="28" y="22" width="46" height="30" rx="3" fill="#ffffff"/>
  <rect x="37" y="28" width="7" height="24" fill="#1d4ed8"/>
  <rect x="37" y="28" width="29" height="5" fill="#1d4ed8"/>
  <rect x="37" y="39" width="21" height="5" fill="#1d4ed8"/>
  <path d="M18 52 L82 52 L72 68 L28 68 Z" fill="#ffffff"/>
  <path d="M14 80 q9 -6 18 0 t18 0 t18 0 t18 0" fill="none" stroke="#93c5fd" stroke-width="5" stroke-linecap="round"/>
</svg>'''
ASSETS_B64: Dict[str, str] = {
    "icon-192.png": "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",
    "icon-512.png": "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",
    "icon-maskable-512.png": "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AgAAQhEAABCKAACAUAQAAIQiAAAgFAEAAKEIAAAIRQAAQCgCAABCEQAAEIoAAIBQBAAAhCIAACAUAQAAoQgAAAhFAABAKAIAAEIRAAAQigAAgFAEAACEIgAAIBQBAAChCAAACEUAAEAoAgAAQhEAABCKAACAUAQAAIQiAAAgFAEAAKEIAAAIRQAAQCgCAABCEQAAEIoAAIBQBAAAhCIAACAUAQAAoQgAAAhFAABAKAIAAEIRAAAQigAAgFAEAACEIgAAIBQBAAChCAAACEUAAEAoAgAAQhEAABCKAACAUAQAAIQiAAAgFAEAAKEIAAAIRQAAQCgCAABCEQAAEIoAAIBQBAAAhCIAACAUAQAAoQgAAAhFAABAKAIAAEIRAAAQigAAgFAEAACEIgAAIBQBAAChCAAACEUAAEAoAgAAQhEAABCKAACAUAQAAIQiAAAgFAEAAKEIAAAIRQAAQCgCAABCEQAAEIoAAIBQBAAAhCIAACAUAQAAoQgAAAhFAABAKAIAAEIRAAAQigAAgFAEAACEIgAAIBQBAAChCAAACEUAAEAoAgAAQhEAABCKAACAUAQAAIQiAAAgFAEAAKEIAAAIRQAAQCgCAABCEQAAEIoAAIBQ/xfFLHTuVm7jBwAAAABJRU5ErkJggg==",
    "keepawake.mp4": "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAMTbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAj10cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAABAAAAAQAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAPoAAAAAAABAAAAAAG1bWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAQABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABYG1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAASBzdGJsAAAAvHN0c2QAAAAAAAAAAQAAAKxhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAABAAEABIAAAASAAAAAAAAAABFUxhdmM2MC4zMS4xMDIgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAAMmF2Y0MBZAAK/+EAF2dkAAqs2V7ARAAAAwAEAAADAAg8SJZYAQAEaO+Py/34+AAAAAAQcGFzcAAAAAEAAAABAAAAFGJ0cnQAAAAAAAAWOAAAFjgAAAAYc3R0cwAAAAAAAAABAAAAAQAAQAAAAAAcc3RzYwAAAAAAAAABAAAAAQAAAAEAAAABAAAAFHN0c3oAAAAAAAACxwAAAAEAAAAUc3RjbwAAAAAAAAABAAADQwAAAGJ1ZHRhAAAAWm1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALWlsc3QAAAAlqXRvbwAAAB1kYXRhAAAAAQAAAABMYXZmNjAuMTYuMTAwAAAACGZyZWUAAALPbWRhdAAAAqwGBf//qNxF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjQgcjMxMDggMzFlMTlmOSAtIEguMjY0L01QRUctNCBBVkMgY29kZWMgLSBDb3B5bGVmdCAyMDAzLTIwMjMgLSBodHRwOi8vd3d3LnZpZGVvbGFuLm9yZy94MjY0Lmh0bWwgLSBvcHRpb25zOiBjYWJhYz0xIHJlZj0xIGRlYmxvY2s9MTotMzotMyBhbmFseXNlPTB4MzoweDMgbWU9aGV4IHN1Ym1lPTIgcHN5PTEgcHN5X3JkPTIuMDA6MC43MCBtaXhlZF9yZWY9MCBtZV9yYW5nZT0xNiBjaHJvbWFfbWU9MSB0cmVsbGlzPTAgOHg4ZGN0PTEgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9tYV9xcF9vZmZzZXQ9MCB0aHJlYWRzPTEgbG9va2FoZWFkX3RocmVhZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0wIGNvbnN0cmFpbmVkX2ludHJhPTAgYmZyYW1lcz0zIGJfcHlyYW1pZD0yIGJfYWRhcHQ9MSBiX2JpYXM9MCBkaXJlY3Q9MSB3ZWlnaHRiPTEgb3Blbl9nb3A9MCB3ZWlnaHRwPTEga2V5aW50PTI1MCBrZXlpbnRfbWluPTEgc2NlbmVjdXQ9NDAgaW50cmFfcmVmcmVzaD0wIHJjX2xvb2thaGVhZD0xMCByYz1jcmYgbWJ0cmVlPTEgY3JmPTIzLjAgcWNvbXA9MC42MCBxcG1pbj0wIHFwbWF4PTY5IHFwc3RlcD00IGlwX3JhdGlvPTEuNDAgYXE9MToxLjIwAIAAAAATZYiEABfOf+z/wlX/2PZEor//8Q==",
}
# --- END GENERATED ---

if __name__ == "__main__":
    sys.exit(run())
