#!/usr/bin/env bash
# Ferry launcher for Linux. Double-click (if your file manager allows) or run: ./ferry.sh
cd "$(dirname "$0")" || exit 1
export FERRY_LAUNCHER=sh
if ! command -v python3 >/dev/null 2>&1 || ! python3 -c 'import sys; sys.exit(0 if sys.version_info>=(3,8) else 3)'; then
  echo "Python 3.8 or newer was not found. Install it with your package manager (for example: sudo apt install python3)."
  [ -t 0 ] && read -r -p "Press Enter to close..." _
  exit 1
fi
python3 ferry.py "$@"
code=$?
if [ "$code" -ne 0 ] && [ -t 0 ]; then read -r -p "Press Enter to close..." _; fi
exit "$code"
