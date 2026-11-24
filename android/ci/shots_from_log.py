#!/usr/bin/env python3
"""Turn screenshots printed into a GitHub Actions job log back into PNG files.

Artifact downloads are refused by this workspace's proxy (Ph0b), but job logs can be read:
    gh api repos/DrSaurabh/ferry/actions/jobs/<job-id>/logs > job.log
    python3 android/ci/shots_from_log.py job.log /tmp/shots
run_instrumented.sh prints each PNG as "FERRY_PNG_BEGIN <path>", one base64 line, "FERRY_PNG_END".

2.1 (CP134): the PCs' logs folders (ferry-log.txt and the shipped phone/TV app-log.txt) are printed the same way,
gzip + base64 between "FERRY_LOG_BEGIN <PC>/logs/<path>" and "FERRY_LOG_END":
    python3 android/ci/shots_from_log.py --logs job.log /tmp/logs
"""
import base64
import gzip
import os
import re
import sys

STAMP = re.compile(r"^\d{4}-\d\d-\d\dT[\d:.]+Z ")


def extract(lines, kind="PNG"):
    """Yields (relative path, bytes) for every marked screenshot (kind PNG) or gzipped log (kind LOG)."""
    begin, end = "FERRY_%s_BEGIN " % kind, "FERRY_%s_END" % kind
    name, data = None, []
    for raw in lines:
        # GitHub sometimes starts a log line with a byte-order mark (Ph8b: the END line of the last PNG).
        line = STAMP.sub("", raw.rstrip("\r\n").lstrip("\ufeff"))
        if line.startswith(begin):
            name, data = line[len(begin):].strip(), []
        elif line.startswith(end) and name is not None:
            blob = base64.b64decode("".join(data))
            yield name, (gzip.decompress(blob) if kind == "LOG" else blob)
            name = None
        elif name is not None:
            data.append(line.strip())


def main(argv):
    kind = "PNG"
    if len(argv) == 4 and argv[1] == "--logs":
        kind, argv = "LOG", [argv[0]] + argv[2:]
    if len(argv) != 3:
        sys.exit("usage: shots_from_log.py [--logs] JOB.log OUTDIR")
    with open(argv[1], encoding="utf-8", errors="replace") as f:
        shots = list(extract(f, kind))
    for name, png in shots:
        path = os.path.join(argv[2], *[p for p in name.split("/") if p not in ("", "..")])
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as out:
            out.write(png)
        print("%s (%d bytes)" % (path, len(png)))
    return 0 if shots else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
