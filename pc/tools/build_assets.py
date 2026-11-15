#!/usr/bin/env python3
"""Embed the web pages, icons and keep-awake video into ferry.py (between the GENERATED markers).

Usage:  python3 tools/build_assets.py
Needs rsvg-convert (librsvg) for the PNG icons. tools/keepawake.mp4 is committed (made once with
ffmpeg: a 1 s, 16x16 black H.264 clip); if it's missing and ffmpeg exists, it is regenerated.
"""
import base64
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.join(ROOT, "tools")
BEGIN = "# --- BEGIN GENERATED (tools/build_assets.py) - do not edit by hand ---"
END = "# --- END GENERATED ---"


def png(svg, size):
    return subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), svg],
                          check=True, capture_output=True).stdout


def keepawake():
    p = os.path.join(TOOLS, "keepawake.mp4")
    if not os.path.exists(p) and shutil.which("ffmpeg"):
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "color=black:s=16x16:d=1:r=1",
                        "-c:v", "libx264", "-tune", "stillimage", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                        "-an", p], check=True)
    with open(p, "rb") as f:
        return f.read()


def pystr(s):
    if "'''" in s or s.endswith("\\"):
        raise SystemExit("page contains ''' or ends with a backslash")
    return "r'''" + s + "'''"


def main():
    page = open(os.path.join(TOOLS, "page.html"), encoding="utf-8").read()
    diag = open(os.path.join(TOOLS, "diag.html"), encoding="utf-8").read()
    icon = open(os.path.join(ROOT, "ferry-icon.svg"), encoding="utf-8").read().strip()
    assets = {
        "icon-192.png": png(os.path.join(ROOT, "ferry-icon.svg"), 192),
        "icon-512.png": png(os.path.join(ROOT, "ferry-icon.svg"), 512),
        "icon-maskable-512.png": png(os.path.join(TOOLS, "icon-maskable.svg"), 512),
        "keepawake.mp4": keepawake(),
    }
    lines = [BEGIN,
             "PAGE_HTML = " + pystr(page),
             "DIAG_HTML = " + pystr(diag),
             "FAVICON_SVG = " + pystr(icon),
             "ASSETS_B64: Dict[str, str] = {"]
    for k, v in assets.items():
        lines.append('    "%s": "%s",' % (k, base64.b64encode(v).decode("ascii")))
    lines += ["}", END]
    src_path = os.path.join(ROOT, "ferry.py")
    src = open(src_path, encoding="utf-8").read()
    new, n = re.subn(re.escape(BEGIN) + r".*?" + re.escape(END), lambda m: "\n".join(lines), src, flags=re.S)
    if n != 1:
        raise SystemExit("GENERATED markers not found exactly once in ferry.py")
    with open(src_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(new)
    print("ferry.py: page %d B, diag %d B, assets %s" % (len(page), len(diag),
          ", ".join("%s %d B" % (k, len(v)) for k, v in assets.items())))


if __name__ == "__main__":
    sys.exit(main())
