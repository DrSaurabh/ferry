#!/usr/bin/env python3
"""Host-side checks of the PC inboxes after phone-suite classes (plan §1.4, §4.14) [B1].

  verify_inbox.py identical   --inbox DIR --name NAME --fixture FILE [--mtime-s N] [--label PC-A]
      the inbox copy is byte-identical to the fixture and has GPS (ferry.exif_gps_status == "present");
      with --mtime-s its modified time is within 2 s of the phone's DATE_MODIFIED.
  verify_inbox.py redacted    --inbox DIR --name NAME --fixture FILE [--label PC-B]
      the negative control: the copy must NOT be both identical and GPS-present, otherwise redaction was not
      observed and the GPS test is inconclusive (fails the build).
  verify_inbox.py no-partials --inbox DIR [--label PC-A]
      no ferry temp file (.ferry-*.part) is left in the inbox.
  verify_inbox.py uploads     --log FERRY_LOG --name NAME --expect saved,skipped [--label PC-A]
      the PC's request log has exactly these upload outcomes for NAME, in this order.
  verify_inbox.py pattern     --inbox DIR --name NAME --index K --size N [--label PC-A]
      (Ph5b) the inbox copy is byte-identical to the share helper's file K of N bytes (same rule as
      android/sharehelper ShareActivity.pattern).
  verify_inbox.py size        --inbox DIR --name NAME --size N [--label PC-A]
      (Ph5b) the inbox copy exists and has exactly N bytes.
  verify_inbox.py names       --inbox DIR --prefix P --expect A,B,C [--label PC-A]
      (Ph6) the inbox files whose names start with P are exactly A, B, C (as a set; picker test).

  verify_inbox.py device-log  --logs DIR --expect EV1,EV2 [--device SUBSTR] [--label PC-A]
      (2.1, Ph11a; used from Ph11b) a phone/TV shipped its log: some device folder under the PC's logs folder
      (`<data>/logs/<name> (<id>)/app-log.txt`, name containing SUBSTR when given) has lines with every event.
  (2.1, CP110: the PC request log for `uploads --log` is `<data>/logs/ferry-log.txt`.)

Prints one line per check; exit 1 on failure.
"""
import argparse
import hashlib
import os
import struct
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "pc"))
import ferry  # noqa: E402


def sha(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def gps_status(path):
    with open(path, "rb") as f:
        return ferry.exif_gps_status(f.read(65536))


def check_identical(inbox, name, fixture, mtime_s=None):
    p = os.path.join(inbox, name)
    if not os.path.isfile(p):
        return False, "%s: missing in the inbox (%s)" % (name, sorted(os.listdir(inbox)))
    same = sha(p) == sha(fixture)
    gps = gps_status(p)
    ok = same and gps == "present"
    text = "%s: %s, GPS %s" % (name, "identical" if same else "NOT identical", gps)
    if mtime_s is not None:
        delta = abs(os.stat(p).st_mtime - float(mtime_s))
        text += ", date %s (diff %.1f s)" % ("ok" if delta <= 2.0 else "WRONG", delta)
        ok = ok and delta <= 2.0
    return ok, text


def check_redacted(inbox, name, fixture):
    p = os.path.join(inbox, name)
    if not os.path.isfile(p):
        return False, "%s: missing in the inbox (%s)" % (name, sorted(os.listdir(inbox)))
    same = sha(p) == sha(fixture)
    gps = gps_status(p)
    if same and gps == "present":
        return False, "%s: identical and GPS present: redaction not observed, GPS test inconclusive" % name
    return True, "%s: redacted/not identical (identical=%s, GPS %s)" % (name, same, gps)


def check_no_partials(inbox):
    parts = [n for n in os.listdir(inbox) if n.startswith(ferry.TEMP_PREFIX) and n.endswith(ferry.TEMP_SUFFIX)]
    if parts:
        return False, "temp files left: %s" % parts
    return True, "no temp files (%d files in the inbox)" % len(os.listdir(inbox))


def upload_outcomes(log_path, name):
    out = []
    with open(log_path, encoding="utf-8", errors="replace") as f:
        for line in f:
            fields = line.rstrip("\n").split("\t")
            if len(fields) < 10 or fields[3] != "POST" or not fields[4].startswith("/api/v1/upload"):
                continue
            outcome = fields[9]
            if outcome.endswith(" " + name) or fields[4].endswith("name=" + name):
                out.append(outcome.split(" ", 1)[0] if outcome else "")
    return out


def check_uploads(log_path, name, expect):
    got = upload_outcomes(log_path, name)
    return got == expect, "%s uploads: %s (expected %s)" % (name, got, expect)


def helper_pattern(k, size):
    """The share helper's file k (android/sharehelper ShareActivity.pattern): 4096-byte blocks; block b starts
    with the 8-byte big-endian (k << 56 | b), then byte j = (j*31 + k*7 + b) mod 256 for j = 8..4095."""
    base = bytes((j * 31 + k * 7) & 0xFF for j in range(8, 4096))
    out = []
    for b in range((size + 4095) // 4096):
        table = bytes((i + b) & 0xFF for i in range(256))
        out.append(struct.pack(">Q", (k << 56) | b) + base.translate(table))
    return b"".join(out)[:size]


def check_pattern(inbox, name, k, size):
    p = os.path.join(inbox, name)
    if not os.path.isfile(p):
        return False, "%s: missing in the inbox (%s)" % (name, sorted(os.listdir(inbox)))
    with open(p, "rb") as f:
        got = f.read()
    want = helper_pattern(k, size)
    if got == want:
        return True, "%s: identical to the shared file (%d bytes)" % (name, size)
    return False, "%s: NOT identical (%d bytes, expected %d)" % (name, len(got), size)


def check_size(inbox, name, size):
    p = os.path.join(inbox, name)
    if not os.path.isfile(p):
        return False, "%s: missing in the inbox (%s)" % (name, sorted(os.listdir(inbox)))
    n = os.path.getsize(p)
    return n == size, "%s: %d bytes (expected %d)" % (name, n, size)


def check_names(inbox, prefix, expect):
    got = sorted(n for n in os.listdir(inbox) if n.startswith(prefix))
    want = sorted(expect)
    return got == want, "%s* files: %s (expected %s)" % (prefix, got, want)


def event_name(line):
    """Event of a 2.1 phone/TV log line: `<date> <time> #<seq> s=<sid> <event> k=v…` (plan §2.2)."""
    parts = line.split(" ", 5)
    if len(parts) >= 5 and parts[2].startswith("#") and parts[3].startswith("s="):
        return parts[4].rstrip("\n")
    return None


def check_device_log(logs_dir, expect, device=None):
    if not os.path.isdir(logs_dir):
        return False, "no logs folder %s" % logs_dir
    found = {}
    for d in sorted(os.listdir(logs_dir)):
        p = os.path.join(logs_dir, d, ferry.DEVICE_LOG_NAME)
        if not os.path.isfile(p) or (device and device not in d):
            continue
        with open(p, encoding="utf-8", errors="replace") as f:
            events = {event_name(line) for line in f}
        found[d] = sorted(e for e in expect if e in events)
        if len(found[d]) == len(expect):
            return True, "device log %r has %s" % (d, ", ".join(expect))
    return False, "no device log with all of %s (found: %s)" % (expect, found or "no device folders")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["identical", "redacted", "no-partials", "uploads", "pattern", "size", "names",
                                     "device-log"])
    ap.add_argument("--logs")
    ap.add_argument("--device")
    ap.add_argument("--prefix")
    ap.add_argument("--inbox")
    ap.add_argument("--name")
    ap.add_argument("--fixture")
    ap.add_argument("--mtime-s")
    ap.add_argument("--log")
    ap.add_argument("--expect", default="")
    ap.add_argument("--index", type=int)
    ap.add_argument("--size", type=int)
    ap.add_argument("--label", default="PC")
    a = ap.parse_args(argv)
    if a.mode == "identical":
        ok, text = check_identical(a.inbox, a.name, a.fixture, a.mtime_s or None)
    elif a.mode == "redacted":
        ok, text = check_redacted(a.inbox, a.name, a.fixture)
    elif a.mode == "no-partials":
        ok, text = check_no_partials(a.inbox)
    elif a.mode == "pattern":
        ok, text = check_pattern(a.inbox, a.name, a.index, a.size)
    elif a.mode == "names":
        ok, text = check_names(a.inbox, a.prefix, [e for e in a.expect.split(",") if e])
    elif a.mode == "device-log":
        ok, text = check_device_log(a.logs, [e for e in a.expect.split(",") if e], a.device)
    elif a.mode == "size":
        ok, text = check_size(a.inbox, a.name, a.size)
    else:
        ok, text = check_uploads(a.log, a.name, [e for e in a.expect.split(",") if e])
    print("%s %s%s" % (a.label, text, "" if ok else "  ** FAILED **"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
