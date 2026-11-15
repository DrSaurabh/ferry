#!/usr/bin/env bash
# Make the TV long-pause test video (plan §1.4 [PA17] [PR7]): 300 s, 720p, constant 6 Mbit/s,
# noise so it does not compress away. Usage: make_big_video.sh OUT.mp4
# Installs ffmpeg with apt when it is missing (CI). Asserts size >= 200 MB and duration >= 290 s.
set -euo pipefail
out=${1:?usage: make_big_video.sh OUT.mp4}
mkdir -p "$(dirname "$out")"
if ! command -v ffmpeg >/dev/null || ! command -v ffprobe >/dev/null; then
  sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg >/dev/null
fi
if [ ! -s "$out" ]; then
  ffmpeg -y -loglevel error \
    -f lavfi -i testsrc2=size=1280x720:rate=30 -f lavfi -i sine=frequency=440 -t 300 \
    -vf noise=alls=40:allf=t -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
    -b:v 6M -minrate 6M -maxrate 6M -bufsize 12M -x264-params nal-hrd=cbr \
    -c:a aac -b:a 128k -movflags +faststart "$out.tmp.mp4"
  mv "$out.tmp.mp4" "$out"
fi
size=$(stat -c %s "$out")
dur=$(ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$out")
echo "big video: $size bytes, $dur s"
[ "$size" -ge 200000000 ] || { echo "big video too small ($size bytes)"; exit 1; }
awk -v d="$dur" 'BEGIN { exit !(d >= 290) }' || { echo "big video too short ($dur s)"; exit 1; }
