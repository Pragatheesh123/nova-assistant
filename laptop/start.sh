#!/usr/bin/env bash
# Start Nova on macOS / Linux
cd "$(dirname "$0")"
if [ ! -f .venv/installed.ok ]; then
  echo "First run: installing what Nova needs..."
  rm -rf .venv
  python3 -m venv .venv || { echo "Install Python 3.10+ first."; exit 1; }
  .venv/bin/pip install -r requirements.txt || { echo "Install failed, see above."; exit 1; }
  .venv/bin/pip install faster-whisper || echo "Speech recognition didn't install; using browser speech."
  touch .venv/installed.ok
fi
exec .venv/bin/python -m server.main "$@"
