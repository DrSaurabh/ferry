"""Third pytest root (plan CP124, Ph11a [PS12]). Placeholder until Ph19 adds review_logs/build_guide tests:
checks that the events catalogue (docs/v2.1-log-events.md) has its three machine-readable tables."""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CATALOGUE = os.path.join(ROOT, "docs", "v2.1-log-events.md")


def table(text, part):
    m = re.search(r"<!-- %s:start -->(.*?)<!-- %s:end -->" % (part, part), text, re.S)
    assert m, part
    rows = [r.strip() for r in m.group(1).strip().splitlines() if r.strip().startswith("|")]
    head = [c.strip() for c in rows[0].strip("|").split("|")]
    body = [re.split(r"(?<!\\)\|", r.strip()[1:-1]) for r in rows[2:]]
    return head, [[c.strip() for c in r] for r in body]


def test_catalogue_tables_parse():
    text = open(CATALOGUE, encoding="utf-8").read()
    head, events = table(text, "events")
    assert head == ["event", "layouts", "fields", "level"]
    names = [r[0] for r in events]
    assert len(names) == len(set(names)) and all(re.match(r"^[a-z][a-z0-9_.]*$", n) for n in names)
    assert all(r[1] in ("phone", "tv", "both") and r[3] in ("N", "D") and len(r) == 4 for r in events)
    head, outcomes = table(text, "outcomes")
    assert head == ["id", "intent", "outcome", "within_s", "key"]
    assert [r[0] for r in outcomes] == ["O%d" % i for i in range(1, len(outcomes) + 1)]
    assert all(len(r) == 5 for r in outcomes)
    head, signals = table(text, "signals")
    assert head == ["rule", "L6 text", "parameters"]
    assert [r[0] for r in signals] == ["G%d" % i for i in range(1, 12)]
