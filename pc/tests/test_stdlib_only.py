"""C7: ferry.py imports nothing outside the standard library (besides the bundled, optional qrcodegen)."""
import ast
import os
import sys

import pytest

from helpers import ROOT


@pytest.mark.skipif(sys.version_info < (3, 10), reason="sys.stdlib_module_names needs Python 3.10")
def test_stdlib_only():
    tree = ast.parse(open(os.path.join(ROOT, "ferry.py"), encoding="utf-8").read())
    mods = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            mods |= {a.name.split(".")[0] for a in node.names}
        elif isinstance(node, ast.ImportFrom) and node.level == 0:
            mods.add(node.module.split(".")[0])
    extra = mods - set(sys.stdlib_module_names) - {"__future__", "qrcodegen"}
    assert not extra, extra
    assert "json" in mods and "socket" in mods
