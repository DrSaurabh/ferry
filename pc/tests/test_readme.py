"""README.txt (C6, plan §2.12 / Ph10): the sections the user needs, readable width, no hidden test flags."""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def readme() -> str:
    with open(os.path.join(ROOT, "README.txt"), encoding="utf-8") as f:
        return f.read()


SECTIONS = [
    "1. START FERRY ON THE PC",
    "2. INSTALL THE APP ON YOUR PHONE",
    "3. INSTALL THE APP ON THE FIRE TV",
    "4. VIVO BATTERY SETTINGS",
    "5. EVERYDAY USE",
    "6. UPDATING FERRY",
    "7. TROUBLESHOOTING",
    "8. KEEP THE SAME PC ADDRESS",
    "9. SECURITY NOTE",
    "10. ADVANCED OPTIONS",
    "11. CREDITS AND LICENCES",
]


def test_required_sections_in_order():
    text = readme()
    pos = [text.find(s) for s in SECTIONS]
    assert all(p >= 0 for p in pos), [s for s, p in zip(SECTIONS, pos) if p < 0]
    assert pos == sorted(pos)


def test_width_and_plain_text():
    for n, line in enumerate(readme().splitlines(), 1):
        assert len(line) <= 88, "line %d is %d columns" % (n, len(line))
        assert all(32 <= ord(c) < 127 for c in line), "line %d is not plain ASCII: %r" % (n, line)


def test_no_hidden_flags():
    text = readme()
    for flag in ("--discovery-port", "--data-dir", "--test-free-bytes", "--app-dir", "--install-desktop"):
        assert flag not in text, flag


def test_mentions_what_the_user_needs():
    text = readme()
    for must in ("ferry-log.txt", "8765/app", "Downloader", "adb install", "Downloads\\Phone",
                 "Ferry outbox", "Download/Ferry", "Background power consumption",
                 "Share diagnostics log", "UDP 8765"):
        assert must in text, must
    # Fire OS 8 restarts Ferry after "install unknown apps" is allowed (B72): say "press OK ... once more".
    assert re.search(r"Fire OS 8\).*restarts", text.replace("\n", " "))
    assert "once more" in text


def test_vivo_steps_match_the_app():
    """The README's vivo steps are the app's one string (A8), so a later correction changes both."""
    strings = os.path.join(ROOT, "..", "android", "app", "src", "main", "res", "values", "strings.xml")
    if not os.path.exists(strings):  # the user's zip has no android/ folder
        return
    with open(strings, encoding="utf-8") as f:
        m = re.search(r'<string name="vivo_steps">(.*?)</string>', f.read())
    app = m.group(1).replace("\u2192", ">").replace("\\n", " ")  # the app's string has a line break
    text = " ".join(readme().split())
    for step in re.split(r"\s*\d\.\s+", app):
        step = step.strip()
        if step:
            assert " ".join(step.split()) in text, step
