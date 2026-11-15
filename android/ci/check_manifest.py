#!/usr/bin/env python3
"""Check the manifest that ships in the release APK (plan §1.4, §4.11, design §3.6) [A1] [A4] [CP5] [PR14].

    check_manifest.py MANIFEST

MANIFEST is the text of `aapt2 dump xmltree --file AndroidManifest.xml app-release.apk` (what ships), or,
for local use, a plain (merged) AndroidManifest.xml. Every entry of plan §4.11 is asserted; libraries may
add more (e.g. androidx.core's DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION), so permissions are checked as
"required present / forbidden absent", not as an exact list.
Exit code 0 = all good; otherwise the problems are printed and the exit code is 1.
"""
import re
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"
P = "android.permission."
REQUIRED_PERMISSIONS = [P + n for n in (
    "INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_WIFI_MULTICAST_STATE", "WAKE_LOCK",
    "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_DATA_SYNC", "FOREGROUND_SERVICE_MEDIA_PLAYBACK",
    "POST_NOTIFICATIONS", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED",
    "READ_EXTERNAL_STORAGE", "ACCESS_MEDIA_LOCATION", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "REQUEST_INSTALL_PACKAGES")]
# No download path below API 29 [CP17]: WRITE_EXTERNAL_STORAGE must not be declared.
FORBIDDEN_PERMISSIONS = [P + "WRITE_EXTERNAL_STORAGE"]
# android:configChanges flag bits (android.content.pm.ActivityInfo / attrs.xml).
CONFIG_BITS = {"keyboardHidden": 0x20, "orientation": 0x80, "screenLayout": 0x100, "uiMode": 0x200,
               "screenSize": 0x400}
LAUNCH_MODES = {"standard": 0, "singleTop": 1, "singleTask": 2, "singleInstance": 3}
FGS_TYPES = {"dataSync": 0x1, "mediaPlayback": 0x2}


class Node:
    def __init__(self, tag, attrs=None):
        self.tag = tag
        self.attrs = attrs or {}     # local attribute name → raw value (str)
        self.children = []

    def all(self, tag):
        """Every descendant (depth-first) with this tag."""
        out = []
        for c in self.children:
            if c.tag == tag:
                out.append(c)
            out += c.all(tag)
        return out

    def kids(self, tag):
        return [c for c in self.children if c.tag == tag]

    def get(self, name):
        return self.attrs.get(name)


# ---------- input formats ----------
_E = re.compile(r"^(\s*)E: (\S+)")
_A = re.compile(r"^(\s*)A: (?:(\S+):)?([A-Za-z_][\w.]*)(?:\(0x[0-9a-fA-F]+\))?=(.*)$")


def _xmltree_value(v):
    v = v.strip()
    m = re.match(r'^"(.*)" \(Raw: ".*"\)$', v)
    if m:
        return m.group(1)
    m = re.match(r'^"(.*)"$', v)
    if m:
        return m.group(1)
    v = re.sub(r"^\(type 0x[0-9a-fA-F]+\)", "", v)
    return v


def parse_xmltree(text):
    """`aapt2 dump xmltree` text → Node tree (root = the <manifest> node)."""
    root = Node("#document")
    stack = [(-1, root)]          # (indent, node)
    for line in text.splitlines():
        m = _E.match(line)
        if m:
            indent = len(m.group(1))
            while stack[-1][0] >= indent:
                stack.pop()
            node = Node(m.group(2))
            stack[-1][1].children.append(node)
            stack.append((indent, node))
            continue
        m = _A.match(line)
        if m and len(stack) > 1:
            name, value = m.group(3), m.group(4)
            stack[-1][1].attrs[name] = _xmltree_value(value)
    ms = root.kids("manifest")
    if not ms:
        raise ValueError("no manifest element found in the xmltree dump")
    return ms[0]


def parse_xml(text):
    """Plain AndroidManifest.xml → Node tree."""
    def conv(el):
        tag = el.tag.split("}")[-1]
        n = Node(tag, {k.split("}")[-1]: v for k, v in el.attrib.items()})
        n.children = [conv(c) for c in el]
        return n
    return conv(ET.fromstring(text))


def load(text):
    return parse_xml(text) if text.lstrip().startswith("<") else parse_xmltree(text)


# ---------- value helpers (both formats) ----------
def as_bool(v):
    if v is None:
        return None
    s = v.strip().lower()
    if s in ("true", "0xffffffff", "-1"):
        return True
    if s in ("false", "0x0", "0x00000000", "0"):
        return False
    return None


def as_int(v, names=None):
    """Decimal, 0x hex or (for XML) a symbolic name / `a|b` flag list resolved with `names`."""
    if v is None:
        return None
    s = v.strip()
    try:
        return int(s, 16) if s.lower().startswith("0x") else int(s)
    except ValueError:
        pass
    if names:
        total = 0
        for part in s.split("|"):
            if part not in names:
                return None
            total |= names[part]
        return total
    return None


def named(nodes, suffix):
    """Nodes whose android:name is `suffix` or ends with `.suffix`."""
    return [n for n in nodes if (n.get("name") or "") == suffix or (n.get("name") or "").endswith("." + suffix)]


def filter_has(node, actions=(), categories=(), mime=None):
    for f in node.kids("intent-filter"):
        a = {x.get("name") for x in f.kids("action")}
        c = {x.get("name") for x in f.kids("category")}
        m = {x.get("mimeType") for x in f.kids("data")}
        if set(actions) <= a and set(categories) <= c and (mime is None or mime in m):
            return True
    return False


# ---------- the checks (plan §4.11) ----------
def check(manifest):
    problems = []

    def need(cond, msg):
        if not cond:
            problems.append(msg)

    sdk = manifest.kids("uses-sdk")
    need(sdk and as_int(sdk[0].get("minSdkVersion")) == 25, "uses-sdk minSdkVersion must be 25")
    need(sdk and as_int(sdk[0].get("targetSdkVersion")) == 35, "uses-sdk targetSdkVersion must be 35")

    perms = {p.get("name"): p for p in manifest.kids("uses-permission")}
    for p in REQUIRED_PERMISSIONS:
        need(p in perms, "missing <uses-permission> %s" % p)
    for p in FORBIDDEN_PERMISSIONS:
        need(p not in perms, "%s must not be declared (no download path below API 29, CP17)" % p)
    if P + "READ_EXTERNAL_STORAGE" in perms:
        need(as_int(perms[P + "READ_EXTERNAL_STORAGE"].get("maxSdkVersion")) == 32,
             "READ_EXTERNAL_STORAGE must have maxSdkVersion 32")

    feats = {f.get("name"): f for f in manifest.kids("uses-feature")}
    for f in ("android.hardware.touchscreen", "android.software.leanback"):
        need(f in feats and as_bool(feats[f].get("required")) is False,
             "<uses-feature %s required=false> missing" % f)

    apps = manifest.kids("application")
    need(len(apps) == 1, "exactly one <application> expected")
    if not apps:
        return problems
    app = apps[0]
    need((app.get("name") or "").endswith("FerryApp"), "application android:name must be .FerryApp")
    need(app.get("label") is not None, "application android:label missing")
    for a in ("icon", "roundIcon", "banner", "theme"):
        need(app.get(a) is not None, "application android:%s missing" % a)
    need(as_bool(app.get("usesCleartextTraffic")) is True,
         "application android:usesCleartextTraffic must be true [A1]")
    need(not manifest.all("uses-library"), "no <uses-library> allowed")

    acts = app.kids("activity")
    main = named(acts, "MainActivity")
    need(len(main) == 1, "MainActivity missing")
    if main:
        m = main[0]
        need(as_bool(m.get("exported")) is True, "MainActivity must be exported")
        need(as_int(m.get("launchMode"), LAUNCH_MODES) == LAUNCH_MODES["singleTask"],
             "MainActivity launchMode must be singleTask")
        want = sum(CONFIG_BITS.values())
        got = as_int(m.get("configChanges"), CONFIG_BITS)
        need(got is not None and got & want == want,
             "MainActivity configChanges must include orientation|screenSize|screenLayout|keyboardHidden|uiMode")
        need(filter_has(m, ["android.intent.action.MAIN"], ["android.intent.category.LAUNCHER"]),
             "MainActivity needs MAIN + LAUNCHER")
        need(filter_has(m, ["android.intent.action.MAIN"], ["android.intent.category.LEANBACK_LAUNCHER"]),
             "MainActivity needs MAIN + LEANBACK_LAUNCHER")

    share = named(acts, "ShareReceiverActivity")
    need(len(share) == 1, "ShareReceiverActivity missing")
    if share:
        s = share[0]
        need(as_bool(s.get("exported")) is True, "ShareReceiverActivity must be exported")
        need(s.get("theme") is not None, "ShareReceiverActivity needs the translucent theme")
        need(as_bool(s.get("excludeFromRecents")) is True, "ShareReceiverActivity excludeFromRecents must be true")
        need(as_bool(s.get("noHistory")) is True, "ShareReceiverActivity noHistory must be true")
        need(s.get("taskAffinity") == "", 'ShareReceiverActivity taskAffinity must be ""')
        for action in ("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"):
            need(filter_has(s, [action], mime="*/*"), "ShareReceiverActivity needs %s with */* [A5]" % action)

    svcs = app.kids("service")
    for name, fgs in (("TransferService", "dataSync"), ("CastService", "mediaPlayback")):
        found = named(svcs, name)
        need(len(found) == 1, "%s missing" % name)
        if found:
            need(as_int(found[0].get("foregroundServiceType"), FGS_TYPES) == FGS_TYPES[fgs],
                 "%s foregroundServiceType must be %s" % (name, fgs))
            need(as_bool(found[0].get("exported")) is False, "%s must not be exported" % name)

    provs = [p for p in app.kids("provider") if p.get("name") == "androidx.core.content.FileProvider"]
    need(len(provs) == 1, "androidx.core.content.FileProvider missing")
    if provs:
        p = provs[0]
        need(p.get("authorities") == "app.ferry.files", "FileProvider authority must be app.ferry.files")
        need(as_bool(p.get("exported")) is False, "FileProvider must not be exported")
        need(as_bool(p.get("grantUriPermissions")) is True, "FileProvider grantUriPermissions must be true")
        need(any(md.get("name") == "android.support.FILE_PROVIDER_PATHS" for md in p.kids("meta-data")),
             "FileProvider needs the FILE_PROVIDER_PATHS meta-data")
    return problems


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 1:
        print(__doc__)
        return 2
    with open(argv[0], encoding="utf-8") as f:
        text = f.read()
    try:
        problems = check(load(text))
    except (ValueError, ET.ParseError) as e:
        problems = ["could not read the manifest: %s" % e]
    for p in problems:
        print("PROBLEM:", p)
    print("check_manifest: %s" % ("FAILED" if problems else "OK"))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
