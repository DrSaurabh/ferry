"""Print JUnit XML results into the job log (Ph2a, B-log): artifacts cannot be downloaded from the agent's
workspace (Ph0b, B27), so the per-test outcome (passed / skipped / FAILED, with time) must be in the log.

Usage: python junit_summary.py <dir with TEST-*.xml> [<dir> ...]
Exit code 0 always (Gradle decides pass/fail); prints a total line "junit: N passed, N skipped, N failed".
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def summarise(dirs):
    rows, counts = [], {"passed": 0, "skipped": 0, "FAILED": 0}
    for d in dirs:
        for path in sorted(glob.glob(os.path.join(d, "**", "TEST-*.xml"), recursive=True)):
            root = ET.parse(path).getroot()
            for case in root.iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    status = "FAILED"
                elif case.find("skipped") is not None:
                    status = "skipped"
                else:
                    status = "passed"
                counts[status] += 1
                cls = case.get("classname", "").rsplit(".", 1)[-1]
                rows.append("%-7s %6.2fs  %s.%s" % (status, float(case.get("time") or 0), cls, case.get("name")))
    total = "junit: %d passed, %d skipped, %d failed" % (counts["passed"], counts["skipped"], counts["FAILED"])
    return rows, total


def main(argv):
    rows, total = summarise(argv[1:])
    for r in rows:
        print(r)
    print(total)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
