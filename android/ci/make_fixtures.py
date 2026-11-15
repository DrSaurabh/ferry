#!/usr/bin/env python3
"""Make the committed emulator-test fixtures in android/ci/fixtures/ (plan §1.4).

Run locally once (needs Pillow and ffmpeg); the results are committed, CI never runs this.
    python3 android/ci/make_fixtures.py
Writes: gps.jpg (EXIF with GPS, checked with ferry.exif_gps_status == "present"),
photo1..5.jpg, broken.jpg, small.mp4.
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


def main():
    os.makedirs(OUT, exist_ok=True)
    gps_jpeg(os.path.join(OUT, "gps.jpg"))
    for n in range(1, 6):
        picture(1920, 1080, COLOURS[n - 1], "Photo %d" % n).save(
            os.path.join(OUT, "photo%d.jpg" % n), "JPEG", quality=80)
    with open(os.path.join(OUT, "broken.jpg"), "wb") as f:  # JPEG signature, then garbage: undecodable
        f.write(b"\xff\xd8\xff\xe0" + b"\x00\x13\x37" * 500)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error",
                    "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25",
                    "-f", "lavfi", "-i", "sine=frequency=440",
                    "-t", "6", "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-b:a", "64k", "-movflags", "+faststart",
                    os.path.join(OUT, "small.mp4")], check=True)

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
