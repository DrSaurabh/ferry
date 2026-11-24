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
from urllib.parse import parse_qs, quote, unquote, urlsplit

try:  # bundled QR generator (optional)
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import qrcodegen  # type: ignore
except Exception:  # missing, or incompatible: run without a QR code
    qrcodegen = None

# =============================================================================================
# Constants
# =============================================================================================

VERSION = "2.1.0"
PROTOCOL = 2
CAPABILITIES = ["receive", "outbox", "check_batch", "app_update", "discovery", "logs"]
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
DATA_DIR = HERE                  # ferry-state.json, ferry-error.log, ferry-selftest.txt, logs/ (2.1);
                                 # set once by main() from the hidden --data-dir flag
STATE_NAME = "ferry-state.json"
ERROR_LOG_NAME = "ferry-error.log"
SELFTEST_NAME = "ferry-selftest.txt"

DISCOVERY_PORT = 8765            # UDP; independent of --port (the app always asks on 8765)
DISCOVERY_MAX_DATAGRAM = 512
DISCOVERY_MAX_REPLIES_PER_S = 20
CHECK_BATCH_MAX_ITEMS = 2000
CHECK_BATCH_MAX_BODY = 1 << 20
LOG_NAME = "ferry-log.txt"
LOG_OLD_NAME = "ferry-log.1.txt"
LOG_MAX_BYTES = 5 << 20
LOG_RETRY_STEP = 1 << 20
LOG_HARD_CAP = 10 << 20
LOG_QUEUE_MAX = 10000
LOG_POLL_FLUSH_S = 600
LOG_DISCOVERY_EVERY_S = 60
LOGS_DIR_NAME = "logs"           # 2.1 (M12, CP110): <data-dir>/logs holds ferry-log*.txt and the device logs
DEVICE_LOG_NAME = "app-log.txt"
DEVICE_LOG_OLD_NAME = "app-log.1.txt"
DEVICE_LOG_SEQ_NAME = ".last-seq"
DEVICE_LOG_MAX = 20 << 20        # per device, x2 with the rotated file
LOGS_TOTAL_CAP = 200 << 20       # whole logs folder
LOG_UPLOAD_MAX = 512 << 10       # one POST /api/v1/logs body
DEVICE_ID_HEADER = "X-Ferry-Device-Id"
DEVICE_TYPE_HEADER = "X-Ferry-Device-Type"
LOG_FROM_HEADER = "X-Ferry-Log-From"
APP_DIR = os.path.join(HERE, "app")
APK_NAME = "Ferry.apk"
APK_JSON = "Ferry.apk.json"
APK_MIME = "application/vnd.android.package-archive"
APK_JSON_MAX = 64 << 10
DEVICE_HEADER = "X-Ferry-Device"
DEVICE_NAME_MAX = 60
POLL_PATHS = {"/api/v1/outbox", "/api/v1/info"}


def data_path(name: str) -> str:
    return os.path.join(DATA_DIR, name)


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
    ".apk": APK_MIME,
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


_CONTROL_CHARS = re.compile("[\x00-\x1f\x7f-\x9f]")


def device_label(headers, ua: str) -> str:
    """The app's device name (X-Ferry-Device, percent-encoded UTF-8) [B10], else the User-Agent class."""
    raw = headers.get(DEVICE_HEADER) if headers is not None else None
    if raw:
        s = _CONTROL_CHARS.sub(" ", unquote(raw, errors="replace"))
        s = " ".join(s.split())[:DEVICE_NAME_MAX].strip()
        if s:
            return s
    return device_type(ua)


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
    def __init__(self, path: Optional[str] = None):
        self.path = path or data_path(STATE_NAME)
        self.data = {"last_ip": None, "first_run_done": False}

    @classmethod
    def load(cls, path: Optional[str] = None) -> "State":
        s = cls(path)
        path = s.path
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
    # Windows drops trailing spaces and dots; a leading dot is part of the original name (".nomedia") and is
    # kept (audit S4). A name made only of dots and spaces has nothing left.
    s = s.strip(" ").rstrip(" .") or "file"
    if s.lower().startswith(TEMP_PREFIX):     # never a name that the start-up temp cleanup could match
        s = "_" + s
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


def family_rx(name: str, is_win: bool):
    """`name`, `name (1)`, `name (2)`… (same stem and extension). The ONE rule for "same family"."""
    stem, ext = split_ext(name)
    flags = re.IGNORECASE if is_win else 0
    return re.compile(r"^%s(?: \((\d+)\))?%s$" % (re.escape(stem), re.escape(ext)), flags)


RX_NUMBERED_STEM = re.compile(r"^(.*) \(\d+\)$", re.DOTALL)


def coarse_key(name: str, is_win: bool) -> str:
    """Index key that only NARROWS the candidates; family_rx decides [PB3]. Must be a superset of
    re.IGNORECASE: non-ASCII characters and the ASCII letters with non-ASCII case partners (i: İ ı,
    k: Kelvin sign, s: ſ) all become one placeholder."""
    if not is_win:
        return name
    return "".join("\0" if (ord(c) > 127 or c in "iksIKS") else c.lower() for c in name)


def family_keys(fname: str, is_win: bool) -> List[str]:
    """Keys under which an inbox file is indexed: its own name and, if numbered, its base name."""
    keys = [coarse_key(fname, is_win)]
    stem, ext = split_ext(fname)
    for s, e in ((stem, ext), (fname, "")):
        m = RX_NUMBERED_STEM.match(s)
        if m:
            k = coarse_key(m.group(1) + e, is_win)
            if k not in keys:
                keys.append(k)
    return keys


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
                 umask: Optional[int] = None, free_override: Optional[int] = None):
        self.path = path
        self.free_override = free_override          # hidden --test-free-bytes [B11]
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
    def free_bytes(self) -> Optional[int]:
        if self.free_override is not None:
            return self.free_override
        try:
            return shutil.disk_usage(self.path).free
        except OSError:
            return None

    def family(self, name: str) -> List[Tuple[str, str, os.stat_result]]:
        rx = family_rx(name, self.is_win)
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
        name = sanitize_name(raw_name, self.path, self.is_win)
        if mtime_ms is not None:      # a resend is "already on PC" even when the disk is full [H30] (audit S2)
            for fname, _p, st in self.family(name):
                if st.st_size == size and abs(st.st_mtime - mtime_ms / 1000.0) <= 2.0:
                    return {"ok": True, "duplicate": fname, "name": name}
        free = self.free_bytes()
        if free is not None and free < size + DISK_MARGIN:
            return {"ok": False, "reason": "disk_full", "free_bytes": free}
        return {"ok": True, "name": name}

    def check_batch(self, files: List[Tuple[str, int, Optional[int]]]) -> dict:
        """Duplicate check for many files: ONE folder scan and ONE free-space call [B4]. Same answers as
        precheck() per file; never refuses for space (the app decides) [B3]."""
        index: Dict[str, List[os.DirEntry]] = {}
        try:
            with os.scandir(self.path) as it:
                for e in it:
                    for k in family_keys(e.name, self.is_win):
                        index.setdefault(k, []).append(e)
        except OSError:
            pass
        free = self.free_bytes()
        results, needed = [], 0
        for raw, size, mtime_ms in files:
            dup = None
            if mtime_ms is not None:
                name = sanitize_name(raw, self.path, self.is_win)
                cands = index.get(coarse_key(name, self.is_win))
                if cands:
                    rx = family_rx(name, self.is_win)
                    for e in cands:
                        if not rx.match(e.name):
                            continue
                        try:
                            if not e.is_file(follow_symlinks=False):
                                continue
                            st = e.stat(follow_symlinks=False)
                        except OSError:
                            continue
                        if st.st_size == size and abs(st.st_mtime - mtime_ms / 1000.0) <= 2.0:
                            dup = e.name
                            break
            if dup is None:
                needed += size
            results.append({"duplicate": dup})
        return {"ok": True, "results": results, "needed_bytes": needed,
                "free_bytes": None if free is None else max(0, free - DISK_MARGIN)}

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
            self.log("outbox", "+ #%d" % of.id, name, fmt_size(of.size))
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

    def seen_client(self, ip: str, label: str) -> None:
        with self.lock:
            if ip in self.clients:
                return
            self.clients.add(ip)
            self.any_client = True
        label = label or "browser"
        self.note("%s connected (%s)" % (label[0].upper() + label[1:], ip))

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
# EXIF GPS check (test helper)
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
# Request log: ferry-log.txt (C5). A slow or locked disk never blocks a transfer.
# =============================================================================================

_LOG_STOP = object()
_LOG_CTRL = re.compile(r"[\t\r\n]")


def log_field(v) -> str:
    if v is None or v == "":
        return "-"
    return _LOG_CTRL.sub(" ", str(v))


def log_path(raw: str) -> str:
    """Request path for the log: query values are dropped, except the file name."""
    parts = urlsplit(raw or "")
    path = unquote(parts.path, errors="replace")
    if not parts.query:
        return path
    out = []
    for piece in parts.query.split("&"):
        k, eq, v = piece.partition("=")
        k = unquote(k, errors="replace")
        out.append("name=" + unquote(v, errors="replace") if (k == "name" and eq) else k)
    return path + "?" + "&".join(out)


class RequestLog:
    def __init__(self, folder: str, console_note: Callable[[str], None], max_bytes: int = LOG_MAX_BYTES,
                 retry_step: int = LOG_RETRY_STEP, hard_cap: int = LOG_HARD_CAP, queue_max: int = LOG_QUEUE_MAX,
                 replace=os.replace, opener=open, clock=time.time, poll_flush_s: float = LOG_POLL_FLUSH_S,
                 rotate_delays: Tuple[float, ...] = (0.1, 0.2, 0.4)):
        self.folder = folder
        self.path = os.path.join(folder, LOG_NAME)
        self.old_path = os.path.join(folder, LOG_OLD_NAME)
        self.note = console_note
        self.max_bytes, self.retry_step, self.hard_cap = max_bytes, retry_step, hard_cap
        self.replace, self.opener, self.clock = replace, opener, clock
        self.poll_flush_s = poll_flush_s
        self.rotate_delays = rotate_delays
        self.q: "queue.Queue" = queue.Queue(maxsize=queue_max)
        self.dropped = 0
        self._dlock = threading.Lock()
        self.polls: Dict[Tuple[str, str], int] = {}
        self._plock = threading.Lock()
        self._f = None
        self._size = 0
        self._next_try = 0
        self._noted: set = set()
        self._last_flush = clock()
        self.t: Optional[threading.Thread] = None

    # -- producers (any thread; never block) ----------------------------------------------------
    def _put(self, line: str) -> None:
        try:
            self.q.put_nowait(line)
        except queue.Full:
            with self._dlock:
                self.dropped += 1

    def _line(self, fields) -> str:
        t = self.clock()
        ts = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(t)) + ".%03d" % (int(t * 1000) % 1000)
        return "\t".join([ts] + [log_field(f) for f in fields])

    def request(self, ip, device, method, path, rng, status, nbytes, ms, outcome, error) -> None:
        self._put(self._line([ip, device, method, log_path(path), rng, status, nbytes, ms, outcome, error]))

    def event(self, text: str, ip=None, device=None) -> None:
        self._put(self._line([ip, device, "EVENT", None, None, None, None, None, text, None]))

    def poll(self, ip: str, device: str) -> None:
        with self._plock:
            self.polls[(ip, device)] = self.polls.get((ip, device), 0) + 1

    def flush_polls(self) -> None:
        with self._plock:
            polls, self.polls = self.polls, {}
        self._last_flush = self.clock()
        for (ip, device), n in sorted(polls.items()):
            self.event("%d list refresh%s" % (n, "" if n == 1 else "es"), ip, device)

    # -- writer thread ---------------------------------------------------------------------------
    def start(self) -> None:
        self.t = threading.Thread(target=self._run, name="ferry-log", daemon=True)
        self.t.start()

    def stop(self, timeout: float = 2.0) -> None:
        if self.t is None:
            return
        self.flush_polls()
        try:
            self.q.put(_LOG_STOP, timeout=timeout)
        except queue.Full:
            pass
        self.t.join(timeout)
        self.t = None

    def _note_once(self, text: str) -> None:
        if text not in self._noted:
            self._noted.add(text)
            try:
                self.note(text)
            except Exception:
                pass

    def _open(self, mode: str = "a") -> None:
        try:
            os.makedirs(self.folder, exist_ok=True)
            self._f = self.opener(self.path, mode, encoding="utf-8", newline="\n", buffering=1)
        except OSError as e:
            self._f = None
            self._note_once("Can't write ferry-log.txt: %s" % (e.strerror or e))
            return
        try:
            self._size = os.path.getsize(self.path)
        except OSError:
            self._size = 0

    def _close(self) -> None:
        if self._f is not None:
            try:
                self._f.close()
            except Exception:
                pass
            self._f = None

    def _maybe_rotate(self) -> None:
        if self._size <= self.max_bytes or self._size < self._next_try:
            return
        self._close()
        for delay in self.rotate_delays:
            try:
                self.replace(self.path, self.old_path)
                break
            except OSError:
                time.sleep(delay)
        else:
            self._note_once("Can't rotate ferry-log.txt (is it open in another program?)")
            self._open()
            if self._f is None or self._size < self.hard_cap:
                self._next_try = self._size + self.retry_step
                return
            self._close()
            self._open("w")
            self._next_try = 0
            self._write_raw(self._line([None, None, "EVENT", None, None, None, None, None,
                                        "log restarted (could not rotate)", None]))
            return
        self._next_try = 0
        self._open()

    def _write_raw(self, line: str) -> None:
        if self._f is None:
            self._open()
            if self._f is None:
                return
        try:
            self._f.write(line + "\n")
            self._f.flush()
            self._size += len(line.encode("utf-8")) + 1
        except Exception as e:
            self._note_once("Can't write ferry-log.txt: %s" % (getattr(e, "strerror", None) or e))
            self._close()

    def _write(self, line: str) -> None:
        self._maybe_rotate()
        with self._dlock:
            dropped, self.dropped = self.dropped, 0
        if dropped:
            self._write_raw(self._line([None, None, "EVENT", None, None, None, None, None,
                                        "(%d log lines dropped)" % dropped, None]))
        self._write_raw(line)

    def _run(self) -> None:
        self._open()
        self._maybe_rotate()
        while True:
            try:
                item = self.q.get(timeout=1.0)
            except queue.Empty:
                item = None
            if item is _LOG_STOP:
                while True:
                    try:
                        rest = self.q.get_nowait()
                    except queue.Empty:
                        break
                    if rest is not _LOG_STOP:
                        self._write(rest)
                break
            if item is not None:
                self._write(item)
            if self.clock() - self._last_flush >= self.poll_flush_s:
                self.flush_polls()
        self._close()


def migrate_old_logs(data_dir: str, note: Callable[[str], None], replace=os.replace) -> str:
    """2.1 (M12, CP110): moves 2.0's ferry-log.txt / ferry-log.1.txt from the data folder into <data>/logs.
    Returns the folder the request log should use: logs/, or the old place when a move failed (one note)."""
    logs = os.path.join(data_dir, LOGS_DIR_NAME)
    try:
        os.makedirs(logs, exist_ok=True)
    except OSError as e:
        note("Can't create the logs folder (%s); ferry-log.txt stays next to ferry.py." % (e.strerror or e))
        return data_dir
    for name in (LOG_NAME, LOG_OLD_NAME):
        src, dst = os.path.join(data_dir, name), os.path.join(logs, name)
        if not os.path.isfile(src) or os.path.exists(dst):
            continue
        try:
            replace(src, dst)
        except OSError as e:
            note("Can't move %s into the logs folder (%s); it stays next to ferry.py." % (name, e.strerror or e))
            return data_dir
    return logs


RX_EVENT_SEQ = re.compile(r"^\S+ \S+ #(\d{1,18}) ")
RX_DEVICE_ID = re.compile(r"^[0-9a-fA-F]{1,16}$")


class DeviceLogStore:
    """Phone/TV logs shipped with POST /api/v1/logs (design §7.2-7.3, M8, M12; plan §2.3):
    <logs>/<sanitize_name("<name> (<id>)")>/app-log.txt (+ app-log.1.txt), the last stored seq in .last-seq
    (repeats are dropped), and a cap on the whole logs folder (oldest rotated files go first; never a zip, never
    a file being written). Thread-safe; disk errors become OSError for the handler."""

    def __init__(self, logs_dir: str, is_win: bool = IS_WIN, max_bytes: int = DEVICE_LOG_MAX,
                 total_cap: int = LOGS_TOTAL_CAP, protected: Tuple[str, ...] = (LOG_NAME,)):
        self.dir = logs_dir
        self.is_win = is_win
        self.max_bytes, self.total_cap = max_bytes, total_cap
        self.protected = protected
        self._lock = threading.Lock()

    def folder_name(self, device_name: str, device_id: Optional[str]) -> str:
        if not device_id or not RX_DEVICE_ID.match(device_id):
            return "unknown"
        name = sanitize_name("%s (%s)" % (device_name or "device", device_id.lower()), self.dir, self.is_win)
        return name if name not in (".", "..") else "unknown"

    def _read_last(self, path: str) -> int:
        try:
            with open(path, encoding="ascii") as f:
                return int(f.read().strip() or "0")
        except (OSError, ValueError):
            return 0

    def _write_last(self, path: str, seq: int) -> None:
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="ascii") as f:
            f.write("%d\n" % seq)
        os.replace(tmp, path)

    def append(self, device_name: str, device_id: Optional[str], from_seq: Optional[int], body: str) -> Tuple[int, int]:
        """Stores the new lines of [body]; returns (stored, last_seq)."""
        folder = os.path.join(self.dir, self.folder_name(device_name, device_id))
        if os.path.dirname(os.path.abspath(folder)) != os.path.abspath(self.dir):   # never outside logs/
            folder = os.path.join(self.dir, "unknown")
        with self._lock:
            os.makedirs(folder, exist_ok=True)
            seq_path = os.path.join(folder, DEVICE_LOG_SEQ_NAME)
            last = self._read_last(seq_path)
            keep: List[str] = []
            top = last
            for line in body.split("\n"):
                if not line.strip():
                    continue
                m = RX_EVENT_SEQ.match(line)
                if m:
                    seq = int(m.group(1))
                    if seq <= top:
                        continue
                    top = seq
                elif from_seq is not None and from_seq <= last:
                    continue                      # a repeated body whose lines carry no seq
                keep.append(line.replace("\r", " "))
            path = os.path.join(folder, DEVICE_LOG_NAME)
            if keep:
                data = ("\n".join(keep) + "\n").encode("utf-8")
                try:
                    size = os.path.getsize(path)
                except OSError:
                    size = 0
                if size and size + len(data) > self.max_bytes:
                    os.replace(path, os.path.join(folder, DEVICE_LOG_OLD_NAME))
                with open(path, "ab") as f:
                    f.write(data)
            if top != last:
                self._write_last(seq_path, top)
            self._sweep(path)
            return len(keep), top

    def _sweep(self, open_path: str) -> None:
        """Total cap: delete the oldest rotated files first, then other devices' current files."""
        files = []
        for base, _dirs, names in os.walk(self.dir):
            for n in names:
                p = os.path.join(base, n)
                try:
                    st = os.stat(p)
                except OSError:
                    continue
                files.append((p, n, st.st_size, st.st_mtime))
        total = sum(f[2] for f in files)
        if total <= self.total_cap:
            return
        never = {os.path.abspath(open_path)}

        def removable(p: str, n: str) -> bool:
            return (not n.lower().endswith(".zip") and n not in self.protected and n != DEVICE_LOG_SEQ_NAME
                    and os.path.abspath(p) not in never)
        rotated = sorted((f for f in files if removable(f[0], f[1]) and ".1." in f[1]), key=lambda f: f[3])
        current = sorted((f for f in files if removable(f[0], f[1]) and ".1." not in f[1]), key=lambda f: f[3])
        for p, _n, size, _t in rotated + current:
            if total <= self.total_cap:
                break
            try:
                os.remove(p)
                total -= size
            except OSError:
                pass


# =============================================================================================
# Discovery responder (UDP 8765, C2): answers the app's broadcast "where is Ferry?"
# =============================================================================================

RX_NONCE = re.compile(r"^[0-9a-fA-F]{8,32}$")


class DiscoveryResponder:
    def __init__(self, port: int, reply_info: Callable[[], dict], console, reqlog, sock_factory=None,
                 clock=time.monotonic, address_hint: Callable[[], str] = lambda: "the PC's address"):
        self.port = port
        self.reply_info = reply_info
        self.console = console
        self.reqlog = reqlog
        self.sock_factory = sock_factory or (lambda: socket.socket(socket.AF_INET, socket.SOCK_DGRAM))
        self.clock = clock
        self.address_hint = address_hint
        self.sock = None
        self.bound_port = 0
        self._stop = threading.Event()
        self._replies: List[float] = []
        self._logged: Dict[str, float] = {}
        self.t: Optional[threading.Thread] = None

    def start(self) -> bool:
        s = None
        try:
            s = self.sock_factory()
            s.settimeout(0.5)
            ioctl, opt = getattr(s, "ioctl", None), getattr(socket, "SIO_UDP_CONNRESET", None)
            if ioctl is not None and opt is not None:
                try:
                    ioctl(opt, False)        # Windows: no 10054 storm after replying to a closed port
                except Exception:
                    pass
            s.bind(("0.0.0.0", self.port))
            try:
                self.bound_port = s.getsockname()[1]
            except Exception:
                self.bound_port = self.port
        except OSError:
            if s is not None:
                try:
                    s.close()
                except Exception:
                    pass
            self.console.note("Automatic discovery is off. On the phone, enter %s once" % self.address_hint())
            return False
        self.sock = s
        self.t = threading.Thread(target=self._loop, name="ferry-discovery", daemon=True)
        self.t.start()
        return True

    def stop(self) -> None:
        self._stop.set()
        if self.t is not None:
            self.t.join(1.0)
        if self.sock is not None:
            try:
                self.sock.close()
            except Exception:
                pass

    def handle(self, data: bytes, addr) -> Optional[bytes]:
        if not isinstance(data, (bytes, bytearray)) or len(data) > DISCOVERY_MAX_DATAGRAM:
            return None
        try:
            req = json.loads(bytes(data).decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None
        if not isinstance(req, dict) or req.get("app") != "ferry" or req.get("q") != "discover":
            return None
        nonce = req.get("nonce")
        if not isinstance(nonce, str) or not RX_NONCE.match(nonce):
            return None
        info = self.reply_info()
        return json.dumps({"app": "ferry", "protocol": PROTOCOL, "nonce": nonce, "name": info.get("name"),
                           "device_type": "pc", "port": info.get("port"), "version": VERSION}).encode("utf-8")

    def _allow(self) -> bool:
        now = self.clock()
        self._replies = [t for t in self._replies if now - t < 1.0]
        if len(self._replies) >= DISCOVERY_MAX_REPLIES_PER_S:
            return False
        self._replies.append(now)
        return True

    def _log(self, data: bytes, ip: str) -> None:
        now = self.clock()
        last = self._logged.get(ip)
        if last is not None and now - last < LOG_DISCOVERY_EVERY_S:
            return
        self._logged[ip] = now
        try:
            req = json.loads(bytes(data).decode("utf-8"))
            who = "%s, %s" % (str(req.get("name") or "?")[:DEVICE_NAME_MAX], str(req.get("device_type") or "?")[:20])
        except Exception:
            who = "?"
        if self.reqlog is not None:
            self.reqlog.event("discovery reply to %s (%s)" % (ip, who), ip)

    def _loop(self) -> None:
        s = self.sock
        while not self._stop.is_set():
            try:
                data, addr = s.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:              # e.g. Windows 10054 after a reply to a closed port [B2]
                if self._stop.is_set():
                    break
                time.sleep(0.01)
                continue
            try:
                reply = self.handle(data, addr)
                if reply is None or not self._allow():
                    continue
                s.sendto(reply, addr)
                self._log(data, addr[0])
            except OSError:
                continue
            except Exception as e:       # never let the loop die
                self.console.error_once("discovery: %s: %s" % (type(e).__name__, e))


# =============================================================================================
# App bundle: app/Ferry.apk + app/Ferry.apk.json (C3), checked on every request [B26] [PB2]
# =============================================================================================

RX_SHA256 = re.compile(r"^[0-9a-f]{64}$")


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def _is_int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


class AppBundle:
    def __init__(self, folder: str, hasher: Optional[Callable[[str], str]] = None):
        self.folder = folder
        self.hasher = hasher or sha256_file
        self.apk = os.path.join(folder, APK_NAME)
        self.json_path = os.path.join(folder, APK_JSON)
        self._lock = threading.Lock()
        self._key = None
        self._result: Tuple[str, Optional[dict], Optional[Tuple[int, int]]] = ("missing", None, None)

    def status(self) -> Tuple[str, Optional[dict], Optional[Tuple[int, int]]]:
        """("ok", meta, (apk_size, apk_mtime_ns)) | ("missing"|"invalid"|"copying", None, None)."""
        try:
            st = os.stat(self.apk)
            with open(self.json_path, "rb") as f:
                jb = f.read(APK_JSON_MAX + 1)
        except OSError:
            return ("missing", None, None)
        if not os.path.isfile(self.apk):
            return ("missing", None, None)
        key = (jb, st.st_size, st.st_mtime_ns)     # the json text differs between builds (it holds the sha)
        with self._lock:
            if key != self._key:
                self._result = self._evaluate(jb, st)
                self._key = key
            return self._result

    @staticmethod
    def _parse(jb: bytes) -> Optional[dict]:
        if len(jb) > APK_JSON_MAX:
            return None
        try:
            d = json.loads(jb.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None
        if not isinstance(d, dict):
            return None
        code, name, size, sha = d.get("version_code"), d.get("version_name"), d.get("size"), d.get("sha256")
        if not (_is_int(code) and code > 0 and isinstance(name, str) and name and _is_int(size) and size >= 0
                and isinstance(sha, str) and RX_SHA256.match(sha)):
            return None
        return {"version_code": code, "version_name": name, "size": size, "sha256": sha}

    def _evaluate(self, jb: bytes, st: os.stat_result):
        meta = self._parse(jb)
        if meta is None:
            return ("invalid", None, None)
        if st.st_size != meta["size"]:
            return ("copying", None, None)
        try:
            digest = self.hasher(self.apk)
        except OSError:
            return ("copying", None, None)
        if digest != meta["sha256"]:
            return ("copying", None, None)
        return ("ok", meta, (st.st_size, st.st_mtime_ns))

    def peek(self) -> Tuple[str, Optional[dict]]:
        """Cheap guess for the console banner (no hashing): ("ok", meta) when the json is valid and the
        APK size matches it."""
        try:
            st = os.stat(self.apk)
            with open(self.json_path, "rb") as f:
                jb = f.read(APK_JSON_MAX + 1)
        except OSError:
            return ("missing", None)
        meta = self._parse(jb)
        if meta is None:
            return ("invalid", None)
        return ("ok", meta) if st.st_size == meta["size"] else ("copying", None)


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
    "/": "get_app_page", "/favicon.svg": "favicon",
    "/app": "send_app", "/app/" + APK_NAME: "send_app", "/api/v1/app": "api_app",
    "/api/v1/info": "api_info", "/api/v1/check": "api_check", "/api/v1/check-batch": "api_check_batch",
    "/api/v1/upload": "api_upload", "/api/v1/outbox": "api_outbox", "/api/v1/outbox.zip": "send_zip",
    "/api/v1/logs": "api_logs",
}
POST_ROUTES = {"api_upload", "api_check_batch", "api_logs"}
RX_OUTBOX = re.compile(r"^/api/v1/outbox/(\d{1,9})(?:/(.*))?$", re.DOTALL)
RX_HOST = re.compile(r"^\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?$")
APP_COPYING = "The app is being copied, try again in a moment"


class _CountingReader:
    """Wraps rfile so the request log knows how much of a body was read."""

    def __init__(self, raw, handler):
        self.raw, self.h = raw, handler

    def read(self, n: int = -1) -> bytes:
        b = self.raw.read(n)
        self.h._nbytes += len(b)
        return b


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
    def send_response(self, code: int, message: Optional[str] = None) -> None:
        self._status = code
        super().send_response(code, message)

    def activity(self, who: str, verb: str, name: str = "", detail: str = "") -> None:
        """Console activity line; the same words become the request log's outcome."""
        self.app.console.log(who, verb, name, detail)
        self._outcome = ("%s %s" % (verb, name)).strip()
        if verb in ("FAILED", "stopped") and detail:
            self._error = detail

    def _dispatch(self, method: str) -> None:
        self._t0 = time.monotonic()
        self._status: Optional[int] = None
        self._nbytes = 0
        self._outcome = ""
        self._error = ""
        self._head = method == "HEAD"
        parts = urlsplit(self.path)
        path = parts.path
        self._range = self.headers.get("Range")
        self.q = {k: v[-1] for k, v in parse_qs(parts.query, keep_blank_values=True).items()}
        self.ua = self.headers.get("User-Agent", "")
        self.device = device_label(self.headers, self.ua)
        try:
            if not path.startswith("/favicon"):
                self.app.tracker.seen_client(self.client_address[0], self.device)
            name = ROUTES_EXACT.get(path)
            if name:
                if (name in POST_ROUTES) != (method == "POST"):
                    return self.send_json(405, {"result": "error", "message": "method not allowed"})
                return getattr(self, name)()
            if method == "POST":
                return self.send_json(405, {"result": "error", "message": "method not allowed"})
            m = RX_OUTBOX.match(path)
            if m:
                return self.send_outbox_file(int(m.group(1)), m.group(2))
            return self.not_found()
        except (ConnectionError, socket.timeout, TimeoutError) as e:
            self.close_connection = True
            self._error = self._error or ("connection lost: %s" % type(e).__name__)
        except Exception as e:
            self.app.console.note("error  %s: %s: %s" % (path, type(e).__name__, e))
            self._error = "%s: %s" % (type(e).__name__, e)
            try:
                self.send_json(500, {"result": "error", "message": "unexpected error on the PC"})
            except Exception:
                pass
        finally:
            self._log_request(method, path)

    def _log_request(self, method: str, path: str) -> None:
        reqlog = getattr(self.app, "reqlog", None)
        if reqlog is None:
            return
        ip = self.client_address[0]
        if method == "GET" and path in POLL_PATHS and self._status == 200:
            reqlog.poll(ip, self.device)
            return
        reqlog.request(ip, self.device, method, self.path, self._range, self._status, self._nbytes,
                       int((time.monotonic() - self._t0) * 1000), self._outcome, self._error)

    def send_bytes(self, code: int, body: bytes, ctype: str, headers: Optional[dict] = None) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if not self._head:
            self.wfile.write(body)
            if self.command != "POST":           # log "bytes": the body read for POST, the body sent for GET
                self._nbytes += len(body)

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
                self._nbytes += len(b)
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

    # -- pages -------------------------------------------------------------------------------
    def favicon(self) -> None:
        self.send_bytes(200, FAVICON_SVG.encode("utf-8"), "image/svg+xml", {"Cache-Control": "max-age=86400"})

    def page_host(self) -> str:
        host = (self.headers.get("Host") or "").strip()
        if RX_HOST.match(host):
            return host
        return "%s:%d" % (self.app.primary or "<PC address>", self.app.cfg.port)

    def get_app_page(self) -> None:
        state, meta, _ = self.app.bundle.status()
        host = self.page_host()
        if state == "ok":
            button = ('<a class="btn" href="/app">Download Ferry for Android</a>'
                      '<p class="small">Ferry %s &middot; %s</p>' % (_h(meta["version_name"]), _h(fmt_size(meta["size"]))))
        elif state == "copying":
            button = '<p class="warn">%s.</p>' % _h(APP_COPYING)
        else:
            button = '<p class="warn">This Ferry folder has no app. Get the latest Ferry zip.</p>'
        body = GET_APP_HTML.replace("{{BUTTON}}", button).replace("{{HOST}}", _h(host))
        self.send_bytes(200, body.encode("utf-8"), "text/html; charset=utf-8", {"Cache-Control": "no-store"})

    # -- app bundle ------------------------------------------------------------------------------
    def send_app(self) -> None:
        state, _meta, key = self.app.bundle.status()
        if state != "ok":
            if state == "copying":
                return self.send_html_page(404, "Try again in a moment", APP_COPYING)
            return self.send_html_page(404, "No app here", "No Ferry app in this Ferry folder.")
        self.send_file(self.app.bundle.apk, APK_NAME, APK_MIME, key[0], key[1], APK_NAME, APP_COPYING, ("app",))

    def api_app(self) -> None:
        state, meta, _ = self.app.bundle.status()
        if state == "ok":
            return self.send_json(200, dict(meta, url="/app/" + APK_NAME))
        if state == "copying":
            return self.send_json(404, {"result": "error", "message": APP_COPYING})
        return self.send_json(404, {"result": "error", "message": "no app bundled"})

    # -- API -------------------------------------------------------------------------------------
    def api_info(self) -> None:
        app = self.app
        state, meta, _ = app.bundle.status()
        self.send_json(200, {
            "app": "ferry", "protocol": PROTOCOL, "version": VERSION, "name": app.pc_name,
            "device_type": "pc", "os": "windows" if IS_WIN else "linux",
            "capabilities": CAPABILITIES,
            "inbox_label": app.cfg.inbox_label, "outbox_label": app.cfg.outbox_label,
            "free_bytes": app.inbox.free_bytes(),
            "app_version_code": meta["version_code"] if state == "ok" else None,
            "time_ms": int(time.time() * 1000)})        # clock offset for the log review (M18)

    def api_logs(self) -> None:
        """POST /api/v1/logs (plan §2.3): new phone/TV log lines, stored per device (M8, M12)."""
        length = self.content_length()
        if length is None:
            self.close_connection = True
            return self.send_json(411, {"result": "error", "message": "Content-Length required"})
        if length < 0:
            self.close_connection = True
            return self.send_json(400, {"result": "error", "message": "bad Content-Length"})
        if length > LOG_UPLOAD_MAX:
            self.drain(length)
            return self.send_json(413, {"result": "error", "message": "request too large"})
        body = b""
        try:
            while len(body) < length:
                b = self.rfile.read(length - len(body))
                if not b:
                    break
                body += b
        finally:
            self._nbytes += len(body)
        if len(body) < length:
            self.close_connection = True
            return
        try:
            text = body.decode("utf-8")
        except UnicodeDecodeError:
            return self.send_json(400, {"result": "error", "message": "log body is not UTF-8"})
        if not text.endswith("\n"):
            return self.send_json(400, {"result": "error", "message": "log body must be whole lines"})
        from_seq = parse_int(self.headers.get(LOG_FROM_HEADER))
        dev_id = (self.headers.get(DEVICE_ID_HEADER) or "").strip()
        try:
            stored, last = self.app.devlogs.append(self.device, dev_id, from_seq, text)
        except OSError as e:
            self._error = "can't store log: %s" % (e.strerror or e)
            return self.send_json(500, {"result": "error", "message": "The PC couldn't store the log"})
        self._outcome = "log %d line%s" % (stored, "" if stored == 1 else "s")
        self.send_json(200, {"ok": True, "stored": stored, "last_seq": last, "time_ms": int(time.time() * 1000)})

    def api_check(self) -> None:
        name = self.q.get("name", "")
        res = self.app.inbox.precheck(name, parse_int(self.q.get("size")), parse_int(self.q.get("mtime")))
        who = "from " + self.device
        if res.get("duplicate"):
            self.activity(who, "skipped", res["duplicate"], "already on PC")
        elif not res.get("ok"):
            self.activity(who, "FAILED", res.get("name", name) or name, "PC disk is full")
        self.send_json(200, res)

    def api_check_batch(self) -> None:
        length = self.content_length()
        if length is None:
            self.close_connection = True
            return self.send_json(411, {"result": "error", "message": "Content-Length required"})
        if length < 0:
            self.close_connection = True
            return self.send_json(400, {"result": "error", "message": "bad Content-Length"})
        if length > CHECK_BATCH_MAX_BODY:
            self.drain(length)
            return self.send_json(413, {"result": "error", "message": "request too large"})
        body = b""
        try:
            while len(body) < length:
                b = self.rfile.read(length - len(body))
                if not b:
                    break
                body += b
        finally:
            self._nbytes += len(body)
        if len(body) < length:
            self.close_connection = True
            return
        bad = {"result": "error", "message": "bad check-batch request"}
        try:
            req = json.loads(body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return self.send_json(400, bad)
        files = req.get("files") if isinstance(req, dict) else None
        if not isinstance(files, list) or len(files) > CHECK_BATCH_MAX_ITEMS:
            return self.send_json(400, bad)
        items = []
        for it in files:
            if not isinstance(it, dict):
                return self.send_json(400, bad)
            n, s, mt = it.get("name"), it.get("size"), it.get("mtime")
            if not (isinstance(n, str) and n and _is_int(s) and s >= 0 and (mt is None or _is_int(mt))):
                return self.send_json(400, bad)
            items.append((n, s, mt))
        res = self.app.inbox.check_batch(items)
        dups = sum(1 for r in res["results"] if r["duplicate"])
        self.activity("from " + self.device, "checked", "%d file%s" % (len(items), "" if len(items) == 1 else "s"),
                      "%d already on PC" % dups)
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
        free = app.inbox.free_bytes()
        if free is not None and free < length + DISK_MARGIN:      # refuse early [CP3]
            sname = sanitize_name(name, app.inbox.path, app.inbox.is_win)
            if not any(st.st_size == length for _n, _p, st in app.inbox.family(sname)):   # likely resend [PB4]
                self.activity(who, "FAILED", sname, "PC disk is full")
                self.drain(length)
                return self.send_json(507, {"result": "error", "reason": "disk_full", "message": "PC disk is full"})
        app.tracker.begin()
        t0 = time.monotonic()
        try:
            res = app.inbox.receive(_CountingReader(self.rfile, self), length, name, mtime)
        except UploadAborted:
            self.activity(who, "stopped", sanitize_name(name), "cancelled or connection lost")
            self.close_connection = True
            return
        except DiskFull as e:
            self.activity(who, "FAILED", sanitize_name(name), "PC disk is full")
            self.drain(e.remaining)
            return self.send_json(507, {"result": "error", "reason": "disk_full", "message": "PC disk is full"})
        except WriteError as e:
            self.activity(who, "FAILED", sanitize_name(name), "can't save: %s" % e)
            self.drain(e.remaining)
            return self.send_json(500, {"result": "error", "message": "The PC couldn't save the file: %s" % e})
        finally:
            app.tracker.end()
        dt = time.monotonic() - t0
        r = res["result"]
        if r == "skipped":
            self.activity(who, "skipped", res["saved_as"], "already on PC")
        elif r == "renamed":
            self.activity(who, "renamed", "%s -> %s" % (sanitize_name(name), res["saved_as"]), fmt_size(length))
        else:
            self.activity(who, "saved", res["saved_as"], ("%s  %s" % (fmt_size(length), fmt_speed(length, dt))).strip())
        self.send_json(200, res)

    def api_outbox(self) -> None:
        files = self.app.outbox.snapshot()
        self.send_json(200, {"files": [o.to_json() for o in files],
                             "total_size": sum(o.size for o in files),
                             "folders_ignored": len(self.app.outbox.folders)})

    def send_outbox_file(self, id_: int, name_part: Optional[str] = None) -> None:
        of = self.app.outbox.get(id_)
        gone = "That file is no longer in the outbox."
        if of is None or not self.app.outbox.contains(of.path):
            return self.not_found(gone)
        # Ids start again at 1 with every Ferry start: a list made before a restart must not fetch another
        # file under the old name, so a name in the URL has to be this file's name (audit S1).
        if name_part and unquote(name_part) != of.name:
            return self.not_found(gone)
        self.send_file(of.path, of.name, guess_type(of.name), of.size, of.mtime_ns, of.name, gone, ("outbox", of.id))

    def send_file(self, path: str, name: str, ctype: str, expect_size: int, expect_mtime_ns: int,
                  log_name: str, gone_message: str, play_key) -> None:
        """Shared sender for outbox files and the app (Range, If-Range, HEAD, inline)."""
        app = self.app
        try:
            f = open(path, "rb")
        except OSError:
            return self.not_found(gone_message)
        with f:
            st = os.fstat(f.fileno())
            if st.st_size != expect_size or st.st_mtime_ns != expect_mtime_ns:
                return self.not_found(gone_message)
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
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Disposition", content_disposition("inline" if inline else "attachment", name))
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
                    self._nbytes += len(b)
                    left -= len(b)
            except ShortRead:
                self.activity(who, "FAILED", log_name, "file changed while sending")
                return self.abort_connection()
            except (ConnectionError, socket.timeout, TimeoutError, OSError) as e:
                if not playing:
                    self.activity(who, "cancelled", log_name)
                else:
                    self._outcome = "playing %s (connection closed)" % log_name
                self._error = "connection lost: %s" % type(e).__name__
                self.close_connection = True
                return
            finally:
                app.tracker.end()
            if playing:
                if app.tracker.first_play(play_key):
                    self.activity(who, "playing", log_name, fmt_size(size))
                else:
                    self._outcome = "playing %s" % log_name
            else:
                self.activity(who, "sent", log_name,
                              ("%s  %s" % (fmt_size(size), fmt_speed(size, time.monotonic() - t0))).strip())

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
            self._nbytes += guard.n
            if isinstance(e, ZipAbort):
                self.activity(who, "FAILED", zname, str(e))
            else:
                self.activity(who, "cancelled", zname)
                self._error = "connection lost: %s" % type(e).__name__
            return self.abort_connection()
        finally:
            app.tracker.end()
        self._nbytes += guard.n
        self.activity(who, "sent", zname, ("%s  %s" % (fmt_size(length), fmt_speed(length, time.monotonic() - t0))).strip())


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

GET_APP_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Get the Ferry app</title>
<link rel="icon" href="/favicon.svg">
<style>
body{font:20px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;margin:0;background:#fff;color:#0f172a}
header{background:#1d4ed8;color:#fff;padding:1.2rem 1.5rem}
header h1{margin:0;font-size:1.6rem}
main{max-width:46rem;margin:0 auto;padding:1.2rem 1.5rem 2rem}
.btn{display:block;text-align:center;background:#1d4ed8;color:#fff;text-decoration:none;font-weight:600;
font-size:1.3rem;padding:1.1rem 1rem;border-radius:.8rem;margin:.6rem 0 .3rem}
.btn:focus,.btn:hover{outline:4px solid #fbbf24;outline-offset:3px}
.small{color:#475569;font-size:.95rem;text-align:center;margin:.2rem 0 1rem}
.warn{background:#fef3c7;color:#78350f;border-radius:.6rem;padding:.9rem 1rem;font-weight:500}
h2{font-size:1.2rem;margin:1.6rem 0 .4rem}
ol{padding-left:1.4rem;margin:.3rem 0}
code{background:#e2e8f0;border-radius:.3rem;padding:.05rem .35rem;font-size:1.05em}
@media(prefers-color-scheme:dark){body{background:#0f172a;color:#e2e8f0}.small{color:#94a3b8}
code{background:#1e293b}.warn{background:#451a03;color:#fde68a}}
</style>
</head>
<body>
<header><h1>Get the Ferry app</h1></header>
<main>
{{BUTTON}}
<h2>On your phone</h2>
<ol>
<li>Tap <b>Download Ferry for Android</b>.</li>
<li>Open the downloaded file. If Android asks, allow your browser to install apps.</li>
<li>Open Ferry.</li>
</ol>
<h2>On Fire TV</h2>
<p>In the Downloader app, enter <code>{{HOST}}/app</code></p>
</main>
</body>
</html>
"""


# =============================================================================================
# Startup
# =============================================================================================

RULE = "  " + "─" * 62


def build_banner(cfg: Config, url: Optional[str], qr_lines: List[str], outbox_count: int,
                 app_line: str) -> List[str]:
    """url = the Get-the-app page, http://<ip>:<port>/ (the QR code encodes exactly this)."""
    lines = ["  FERRY  is running" + " " * 24 + "v" + VERSION]
    if url and qr_lines:
        lines.append("  First time? Scan to install the Ferry app:")
        lines.extend(qr_lines)
    elif url:
        lines.append("  (QR code unavailable - qrcodegen.py is missing)")
    if url:
        hostport = url.split("//", 1)[-1].rstrip("/")
        lines.append("  App page:  http://%s/app" % hostport)
        lines.append("  Fire TV:   in Downloader enter %s/app" % hostport)
    else:
        lines.append("  No network connection found - connect to Wi-Fi, then restart Ferry.")
    lines.append("  Inbox:  %s     Outbox: %s (%d file%s)" % (
        cfg.inbox_label, cfg.outbox_label, outbox_count, "" if outbox_count == 1 else "s"))
    lines.append("  " + app_line)
    lines.append("  Keep this window open. Ctrl+C to stop.   Help: README.txt")
    lines.append(RULE)
    return lines


def app_banner_line(state: str, meta: Optional[dict]) -> str:
    if state == "ok":
        return "App: Ferry %s is ready for your phone and TV" % meta["version_name"]
    if state == "copying":
        return "! " + APP_COPYING
    return "! No app in this folder (%s) - get the full Ferry zip" % os.path.join("app", APK_NAME)


def build_extras(cfg: Config, url: Optional[str], others: List[str], last_ip: Optional[str],
                 primary: Optional[str], port: int) -> List[str]:
    out = []
    if primary and last_ip and primary != last_ip:
        out.append("  ! Your PC's address changed (was %s). The Ferry app finds it by itself;" % last_ip)
        out.append("    if not, enter %s in Ferry > Settings." % primary)
    if others and primary and not primary.startswith("192.168."):
        out.append("  If the phone can't connect, try: " + "  ".join("http://%s:%d" % (o, port) for o in others[:3]))
    if cfg.onedrive_outbox:
        out.append("  ! Your Desktop syncs to OneDrive - big files in the outbox also upload to the cloud.")
        out.append("    (Advanced: start with --outbox to choose another folder.)")
    if cfg.first_run:
        out.append("  First run - welcome! Files from your phone go to:  " + cfg.inbox)
        out.append("  To send files to your phone or TV, put them in:      " + cfg.outbox)
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


def page_url(primary: Optional[str], port: int) -> Optional[str]:
    return "http://%s:%d/" % (primary, port) if primary else None


def make_app(cfg: Config, console: Console, primary: Optional[str], is_win: bool = IS_WIN,
             app_dir: Optional[str] = None, free_override: Optional[int] = None,
             reqlog: Optional[RequestLog] = None, logs_dir: Optional[str] = None) -> AppContext:
    pc_name = socket.gethostname().split(".")[0] or "PC"
    app = AppContext(cfg=cfg, console=console, primary=primary, primary_url=page_url(primary, cfg.port),
                     pc_name=pc_name, reqlog=reqlog)

    def inbox_log(area, verb, name="", detail=""):
        console.log(area, verb, name, detail)
        if area == "inbox" and app.reqlog is not None:
            app.reqlog.event(detail or verb)
    app.inbox = Inbox(cfg.inbox, inbox_log, is_win, free_override=free_override)
    app.outbox = Outbox(cfg.outbox, console.log, is_win,
                        url_base=lambda: app.primary_url or "http://<PC address>:%d" % cfg.port)
    app.tracker = TransferTracker(console.note, is_win)
    app.bundle = AppBundle(app_dir or APP_DIR)
    app.devlogs = DeviceLogStore(logs_dir or os.path.join(DATA_DIR, LOGS_DIR_NAME), is_win)
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


def _discovery_port(v: str):
    if v == "off":
        return None
    n = int(v)
    if not 0 <= n <= 65535:
        raise ValueError(v)
    return n


def parse_args(argv=None):
    p = argparse.ArgumentParser(
        prog="ferry", description="Ferry - move files between your phone / Fire TV and this PC over Wi-Fi.",
        epilog="Get the Ferry app: open the address shown when Ferry starts (scan the QR code).")
    p.add_argument("--port", type=int, default=DEFAULT_PORT, help="port to use (default %d)" % DEFAULT_PORT)
    p.add_argument("--no-open", action="store_true", help="never open the outbox folder")
    p.add_argument("--inbox", help="advanced: folder for files from the phone (used exactly as given)")
    p.add_argument("--outbox", help="advanced: folder offered to the phone/TV (used exactly as given)")
    p.add_argument("--install-desktop", action="store_true", help="Linux: add Ferry to the app menu")
    p.add_argument("--selftest", action="store_true", help="check this PC and write ferry-selftest.txt")
    p.add_argument("--version", action="version", version="Ferry " + VERSION)
    # Hidden test hooks [B24] [CP4]: not in --help or the README.
    p.add_argument("--discovery-port", type=_discovery_port, default=DISCOVERY_PORT, help=argparse.SUPPRESS)
    p.add_argument("--data-dir", default=None, help=argparse.SUPPRESS)
    p.add_argument("--test-free-bytes", type=int, default=None, help=argparse.SUPPRESS)
    p.add_argument("--app-dir", default=None, help=argparse.SUPPRESS)
    a = p.parse_args(argv)
    if not (0 <= a.port <= 65535):
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
    global DATA_DIR
    args = parse_args(argv)
    if args.data_dir:
        DATA_DIR = os.path.abspath(args.data_dir)
        try:
            os.makedirs(DATA_DIR, exist_ok=True)
        except OSError:
            pass
    if args.install_desktop:
        return install_desktop()
    if args.selftest:
        return selftest(args)
    modes = setup_stdout()
    console = Console(ansi=modes["ansi"])
    install_hooks(console)
    state = State.load(data_path(STATE_NAME))
    try:
        cfg = resolve_folders(args, state)
        cfg.port = args.port
        primary, others = detect_addresses()
        logs_dir = os.path.join(DATA_DIR, LOGS_DIR_NAME)
        reqlog = RequestLog(migrate_old_logs(DATA_DIR, console.note), console.note)   # 2.1: into logs/ [CP110]
        app = make_app(cfg, console, primary, app_dir=args.app_dir, free_override=args.test_free_bytes,
                       reqlog=reqlog, logs_dir=logs_dir)
        server = bind_server(cfg.port, app)
    except FatalError as e:
        console.print("")
        console.print("  Ferry couldn't start:")
        console.print("  " + str(e))
        console.stop()
        wait_enter_if_needed()
        return 1
    cfg.port = server.server_port                # --port 0: the real port everywhere [PB15]
    app.primary_url = page_url(primary, cfg.port)
    threading.Thread(target=app.bundle.status, name="ferry-app-warmup", daemon=True).start()   # [PB24]
    reqlog.start()
    reqlog.event("Ferry %s started (port %d, Python %s)" % (VERSION, cfg.port, sys.version.split()[0]))
    app.inbox.cleanup_temps()                    # only after we own the port [T1]
    app.outbox.start_scan()
    qr = qr_console_lines(app.primary_url, console.ansi) if app.primary_url else []
    app_line = app_banner_line(*app.bundle.peek())   # no hashing here: the warm-up thread does it
    for line in build_banner(cfg, app.primary_url, qr, len(app.outbox.snapshot()), app_line):
        console.print(line)
    for line in build_extras(cfg, app.primary_url, others, state.data.get("last_ip"), primary, cfg.port):
        console.print(line)
    discovery = None
    if args.discovery_port is not None:
        discovery = DiscoveryResponder(args.discovery_port, lambda: {"name": app.pc_name, "port": cfg.port},
                                       console, reqlog,
                                       address_hint=lambda: "%s:%d" % (primary or "the PC's address", cfg.port))
        if not discovery.start():
            discovery = None
    if args.data_dir:                            # test harnesses always pass --data-dir [B24]
        console.print("FERRY_READY port=%d discovery=%s" % (
            cfg.port, discovery.bound_port if discovery else "off"))
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
        if discovery is not None:
            discovery.stop()
        app.outbox.stop()
        server.server_close()
        app.inbox.cleanup_temps()                # best effort; startup cleanup is the guarantee
        reqlog.event("Ferry stopped")
        reqlog.stop()
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
        with open(data_path(SELFTEST_NAME), "w", encoding="utf-8") as f:
            f.write(text + "\n")
        print("\nSaved to " + data_path(SELFTEST_NAME))
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
            with open(data_path(ERROR_LOG_NAME), "w", encoding="utf-8") as f:
                f.write("Ferry %s / Python %s / %s\n\n%s" % (VERSION, platform.python_version(), platform.platform(), tb))
        except Exception:
            pass
        print("\nFerry hit an unexpected error. Details saved to ferry-error.log")
        return 2


# =============================================================================================
# Favicon (the only static asset left)
# =============================================================================================

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

if __name__ == "__main__":
    sys.exit(run())
