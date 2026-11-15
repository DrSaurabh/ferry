#!/usr/bin/env python3
"""Turn screenshots printed into a GitHub Actions job log back into PNG files.

Artifact downloads are refused by this workspace's proxy (Ph0b), but job logs can be read:
    gh api repos/DrSaurabh/ferry/actions/jobs/<job-id>/logs > job.log
    python3 android/ci/shots_from_log.py job.log /tmp/shots
run_instrumented.sh prints each PNG as "FERRY_PNG_BEGIN <path>", one base64 line, "FERRY_PNG_END".
"""
import base64
import os
import re
import sys

STAMP = re.compile(r"^\d{4}-\d\d-\d\dT[\d:.]+Z ")


def extract(lines):
    """Yields (relative path, png bytes) for every marked screenshot in the log lines."""
    name, data = None, []
    for raw in lines:
        line = STAMP.sub("", raw.rstrip("\r\n"))
        if line.startswith("FERRY_PNG_BEGIN "):
            name, data = line[len("FERRY_PNG_BEGIN "):].strip(), []
        elif line.startswith("FERRY_PNG_END") and name is not None:
            yield name, base64.b64decode("".join(data))
            name = None
        elif name is not None:
            data.append(line.strip())


def main(argv):
    if len(argv) != 3:
        sys.exit("usage: shots_from_log.py JOB.log OUTDIR")
    with open(argv[1], encoding="utf-8", errors="replace") as f:
        shots = list(extract(f))
    for name, png in shots:
        path = os.path.join(argv[2], *[p for p in name.split("/") if p not in ("", "..")])
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as out:
            out.write(png)
        print("%s (%d bytes)" % (path, len(png)))
    return 0 if shots else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
