#!/usr/bin/env python3
"""Make the committed emulator-test fixtures in android/ci/fixtures/ (plan §1.4).

Run locally once (needs Pillow and ffmpeg); the results are committed, CI never runs this.
    python3 android/ci/make_fixtures.py
Writes: gps.jpg (EXIF with GPS, checked with ferry.exif_gps_status == "present"),
photo1..5.jpg, broken.jpg, small.mp4, report.pdf (Ph7: the From PC download test), cast.mp4 (Ph9: 60 s,
for the cast loopback test).
"""
import os
import subprocess
import sys

from PIL import Image, ImageDraw, ExifTags

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "fixtures")
PC = os.path.join(HERE, "..", "..", "pc")

COLOURS = [(200, 60, 50), (40, 140, 70), (50, 90, 190), (220, 170, 30), (130, 60, 160)]


def picture(w, h, colour, label):
    img = Image.new("RGB", (w, h), colour)
    d = ImageDraw.Draw(img)
    for i in range(0, w, 80):
        d.line([(i, 0), (w - i, h)], fill=(255, 255, 255), width=3)
    d.rectangle([w // 4, h // 3, 3 * w // 4, 2 * h // 3], fill=(240, 240, 240))
    d.text((w // 4 + 20, h // 3 + 20), label, fill=(0, 0, 0), font_size=min(w, h) // 8)
    return img


def gps_jpeg(path):
    img = picture(1600, 1200, (70, 120, 160), "GPS")
    exif = Image.Exif()
    exif[ExifTags.Base.Make] = "Ferry"
    exif[ExifTags.Base.Model] = "Fixture"
    exif[ExifTags.Base.DateTime] = "2025:01:01 12:00:00"
    gps = exif.get_ifd(ExifTags.IFD.GPSInfo)
    gps[ExifTags.GPS.GPSVersionID] = b"\x02\x03\x00\x00"
    gps[ExifTags.GPS.GPSLatitudeRef] = "N"
    gps[ExifTags.GPS.GPSLatitude] = (28.0, 36.0, 50.0)
    gps[ExifTags.GPS.GPSLongitudeRef] = "E"
    gps[ExifTags.GPS.GPSLongitude] = (77.0, 12.0, 30.0)
    img.save(path, "JPEG", quality=85, exif=exif)


def report_pdf(path, pages=40):
    """A small valid PDF (plan §4.14 "report.pdf"), ~100 KB of text pages so a download shows progress."""
    objs = ["<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [%s] /Count %d >>" % (" ".join("%d 0 R" % (4 + 2 * i) for i in range(pages)), pages),
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"]
    for i in range(pages):
        lines = ["BT /F1 11 Tf 50 780 Td 14 TL"]
        lines += ["(Ferry test report, page %d, line %d: the quick brown fox jumps over the lazy dog.) '" % (i + 1, n)
                  for n in range(50)]
        lines.append("ET")
        stream = "\n".join(lines)
        objs.append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> "
                    "/Contents %d 0 R >>" % (5 + 2 * i))
        objs.append("<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream))
    out = b"%PDF-1.4\n"
    offsets = []
    for n, body in enumerate(objs, 1):
        offsets.append(len(out))
        out += ("%d 0 obj\n%s\nendobj\n" % (n, body)).encode("latin-1")
    xref = len(out)
    out += ("xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)).encode()
    out += "".join("%010d 00000 n \n" % o for o in offsets).encode()
    out += ("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)).encode()
    with open(path, "wb") as f:
        f.write(out)


def cast_mp4(path):
    """Ph9 cast loopback test: 60 s (small.mp4's 6 s end before pause and +10 s can be checked), small and light."""
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error",
                    "-f", "lavfi", "-i", "testsrc2=size=426x240:rate=15",
                    "-f", "lavfi", "-i", "sine=frequency=330",
                    "-t", "60", "-c:v", "libx264", "-preset", "veryfast", "-crf", "34", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "32k", "-movflags", "+faststart", path], check=True)


def main():
    os.makedirs(OUT, exist_ok=True)
    gps_jpeg(os.path.join(OUT, "gps.jpg"))
    for n in range(1, 6):
        picture(1920, 1080, COLOURS[n - 1], "Photo %d" % n).save(
            os.path.join(OUT, "photo%d.jpg" % n), "JPEG", quality=80)
    report_pdf(os.path.join(OUT, "report.pdf"))
    with open(os.path.join(OUT, "broken.jpg"), "wb") as f:  # JPEG signature, then garbage: undecodable
        f.write(b"\xff\xd8\xff\xe0" + b"\x00\x13\x37" * 500)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error",
                    "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25",
                    "-f", "lavfi", "-i", "sine=frequency=440",
                    "-t", "6", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart",
                    os.path.join(OUT, "small.mp4")], check=True)

    cast_mp4(os.path.join(OUT, "cast.mp4"))

    sys.path.insert(0, PC)
    import ferry  # noqa: E402
    with open(os.path.join(OUT, "gps.jpg"), "rb") as f:
        status = ferry.exif_gps_status(f.read(65536))
    print("gps.jpg exif_gps_status:", status)
    if status != "present":
        sys.exit("gps.jpg has no GPS")
    for name in sorted(os.listdir(OUT)):
        print("%-12s %8d" % (name, os.path.getsize(os.path.join(OUT, name))))


if __name__ == "__main__":
    main()
