"""Builds small JPEGs with chosen EXIF parts (stdlib only) for test_exif_probe / test_readable_lines."""
import struct


def tiff(endian="<", date_time=None, date_original=None, gps=None):
    """gps: None (no GPS IFD), "yes" (non-zero latitude), "zeroed" (all-zero latitude/longitude)."""
    e = endian
    entries0 = []          # (tag, type, count, value_or_offset_bytes or ("ptr", key))
    blobs = {}
    if date_time:
        blobs["dt"] = date_time.encode("ascii") + b"\x00"
        entries0.append((0x0132, 2, len(blobs["dt"]), ("ptr", "dt")))
    if date_original:
        entries0.append((0x8769, 4, 1, ("ptr", "exif")))
    if gps:
        entries0.append((0x8825, 4, 1, ("ptr", "gps")))
    entries0.sort()

    def ifd_size(n):
        return 2 + 12 * n + 4

    layout = {}
    off = 8 + ifd_size(len(entries0))
    if "dt" in blobs:
        layout["dt"] = off
        off += len(blobs["dt"])
    if date_original:
        blobs["dto"] = date_original.encode("ascii") + b"\x00"
        layout["exif"] = off
        off += ifd_size(1)
        layout["dto"] = off
        off += len(blobs["dto"])
    if gps:
        layout["gps"] = off
        off += ifd_size(2)
        layout["lat"] = off
        off += 24
        layout["lon"] = off
        off += 24
    out = bytearray(b"II*\x00" if e == "<" else b"MM\x00*")
    out += struct.pack(e + "I", 8)

    def ifd(entries):
        b = struct.pack(e + "H", len(entries))
        for tag, typ, cnt, val in entries:
            v = struct.pack(e + "I", layout[val[1]]) if isinstance(val, tuple) else val
            b += struct.pack(e + "HHI", tag, typ, cnt) + v
        return b + struct.pack(e + "I", 0)

    out += ifd(entries0)
    if "dt" in blobs:
        out += blobs["dt"]
    if date_original:
        out += ifd([(0x9003, 2, len(blobs["dto"]), ("ptr", "dto"))])
        out += blobs["dto"]
    if gps:
        out += ifd([(2, 5, 3, ("ptr", "lat")), (4, 5, 3, ("ptr", "lon"))])
        vals = (12, 1, 34, 1, 56, 1) if gps == "yes" else (0, 1, 0, 1, 0, 1)
        out += struct.pack(e + "6I", *vals) + struct.pack(e + "6I", *vals)
    assert len(out) == off, (len(out), off)
    return bytes(out)


def jpeg(tiff_bytes=None, body=b"\x00" * 64):
    data = b"\xff\xd8"
    if tiff_bytes is not None:
        app1 = b"Exif\x00\x00" + tiff_bytes
        data += b"\xff\xe1" + struct.pack(">H", len(app1) + 2) + app1
    return data + b"\xff\xda\x00\x02" + body + b"\xff\xd9"
