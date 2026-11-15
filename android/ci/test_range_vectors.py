"""The Range vectors in android/core/src/test/resources/range-vectors.txt are ferry.parse_range's own answers
(plan §6 Ph3a acceptance: MiniHttpServer's RangeParser gives identical results; RangeParserTest reads the same file)."""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "pc"))

import ferry  # noqa: E402

VECTORS = os.path.join(HERE, "..", "core", "src", "test", "resources", "range-vectors.txt")


def load():
    out = []
    with open(VECTORS, encoding="utf-8") as f:
        for line in f.read().split("\n"):
            if not line or line.startswith("#"):
                continue
            header, size, expected = line.rsplit("|", 2)
            out.append((None if header == "<none>" else header, int(size), expected))
    return out


def test_vectors_match_ferry_parse_range():
    vectors = load()
    assert len(vectors) >= 30
    for header, size, expected in vectors:
        r = ferry.parse_range(header, size)
        got = "whole" if r is None else ("invalid" if r == "invalid" else "%d-%d" % r)
        assert got == expected, (header, size)
