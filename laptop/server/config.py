"""Nova settings. Override any value with an environment variable of the same name."""
import json
import os
import secrets
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
WEB = ROOT / "web"
DATA.mkdir(exist_ok=True)


def _env(name, default):
    return os.environ.get(name, default)


HOST = _env("NOVA_HOST", "0.0.0.0")
PORT = int(_env("NOVA_PORT", "8765"))

# Local LLM served by Ollama (https://ollama.com). Pick any model with tool support.
OLLAMA_URL = _env("NOVA_OLLAMA_URL", "http://127.0.0.1:11434")
MODEL = _env("NOVA_MODEL", "qwen3:8b")

# Local speech-to-text with faster-whisper. "base.en" is fast; "small.en" is more accurate.
WHISPER_MODEL = _env("NOVA_WHISPER_MODEL", "base.en")

# Optional Piper voice for local text-to-speech (path to a .onnx voice file).
PIPER_VOICE = _env("NOVA_PIPER_VOICE", "")

# Arbitrary shell commands are off unless you turn them on. Even then, each one asks first.
ALLOW_SHELL = _env("NOVA_ALLOW_SHELL", "0") == "1"


def _load_token():
    """Pairing token that other devices (your phone) need to connect. Localhost never needs it."""
    path = DATA / "pairing.json"
    if path.exists():
        return json.loads(path.read_text())["token"]
    token = secrets.token_urlsafe(16)
    path.write_text(json.dumps({"token": token}))
    return token


TOKEN = _env("NOVA_TOKEN", "") or _load_token()

# Global hotkey that summons Nova from anywhere on the laptop (needs `pip install pynput`).
HOTKEY = _env("NOVA_HOTKEY", "<ctrl>+<alt>+n")
