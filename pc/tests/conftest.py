import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

import pytest  # noqa: E402

from helpers import Srv  # noqa: E402


@pytest.fixture
def srv(tmp_path):
    s = Srv(tmp_path)
    yield s
    s.close()


@pytest.fixture
def make_srv(tmp_path):
    made = []

    def make(**kw):
        s = Srv(tmp_path, **kw)
        made.append(s)
        return s
    yield make
    for s in made:
        s.close()
