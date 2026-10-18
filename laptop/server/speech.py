"""Local speech. Both engines are optional; without them the app falls back to the
browser's built-in speech (see web/app.js)."""
import io
import os
import wave

os.environ.setdefault("HF_HUB_DISABLE_SYMLINKS_WARNING", "1")  # harmless Windows cache warning

from . import config

_whisper = None
_piper = None


def stt_available():
    try:
        import faster_whisper  # noqa: F401
        return True
    except ImportError:
        return False


VOICE_NAME = "en_GB-alan-medium"  # calm British male, runs offline
VOICE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_GB/alan/medium/"


def voice_path():
    return config.PIPER_VOICE or str(config.DATA / "voices" / f"{VOICE_NAME}.onnx")


def tts_available():
    try:
        import piper  # noqa: F401
    except ImportError:
        return False
    return os.path.exists(voice_path()) and os.path.exists(voice_path() + ".json")


def ensure_voice():
    """Download the British voice once (about 60 MB). Runs in the background at startup."""
    try:
        import piper  # noqa: F401
    except ImportError:
        return
    if config.PIPER_VOICE or tts_available():
        return
    import urllib.request
    folder = config.DATA / "voices"
    folder.mkdir(exist_ok=True)
    try:
        for ext in (".onnx.json", ".onnx"):
            dest = folder / f"{VOICE_NAME}{ext}"
            urllib.request.urlretrieve(VOICE_URL + VOICE_NAME + ext, str(dest) + ".part")
            os.replace(str(dest) + ".part", dest)
        print("  Voice ready: British (Alan)")
    except Exception as e:
        print("  Couldn't download the British voice:", e)


def transcribe(audio: bytes) -> str:
    global _whisper
    from faster_whisper import WhisperModel

    if _whisper is None:
        _whisper = WhisperModel(config.WHISPER_MODEL, device="auto", compute_type="int8")
    samples = decode(audio)
    if samples.size < 1600:  # under 0.1 s: nothing to hear
        return "", ""
    segments, _ = _whisper.transcribe(samples, beam_size=1, vad_filter=True, language="en")
    text = " ".join(s.text.strip() for s in segments).strip()
    return text, voice_cues(samples, text)


# ---------------------------------------------------------------- tone of voice

BASELINE = config.DATA / "voice_baseline.json"


def _pitch(x, rate=16000):
    """Median pitch (Hz) over voiced frames, by autocorrelation. Rough but good enough for 'higher than usual'."""
    import numpy as np

    frame, hop = 640, 320  # 40 ms frames
    lo, hi = rate // 400, rate // 70  # 70-400 Hz
    pitches = []
    for i in range(0, len(x) - frame, hop):
        f = x[i:i + frame] - x[i:i + frame].mean()
        if np.sqrt((f ** 2).mean()) < 0.02:
            continue
        ac = np.correlate(f, f, "full")[frame - 1:]
        lag = lo + int(np.argmax(ac[lo:hi]))
        if ac[lag] > 0.3 * ac[0]:
            pitches.append(rate / lag)
    return float(np.median(pitches)) if pitches else 0.0


def voice_cues(x, text):
    """Describe HOW something was said, relative to this user's usual voice."""
    import json

    import numpy as np

    secs = len(x) / 16000
    words = len(text.split())
    if secs < 0.6 or words == 0:
        return ""
    loud = float(20 * np.log10(np.sqrt((x ** 2).mean()) + 1e-9))
    speed = words / secs
    pitch = _pitch(x)
    try:
        b = json.loads(BASELINE.read_text())
    except Exception:
        b = {"loud": loud, "speed": speed, "pitch": pitch or 150.0, "n": 0}
    cues = []
    if b["n"] >= 5:  # only compare once we know the user's normal voice
        if loud > b["loud"] + 5: cues.append("louder than usual")
        elif loud < b["loud"] - 5: cues.append("quieter than usual")
        if speed > b["speed"] * 1.25: cues.append("faster than usual")
        elif speed < b["speed"] * 0.75: cues.append("slower than usual")
        if pitch and pitch > b["pitch"] * 1.15: cues.append("higher pitch than usual")
        elif pitch and pitch < b["pitch"] * 0.87: cues.append("lower, flatter pitch than usual")
    a = 0.15  # slowly learn the user's normal voice
    b = {"loud": b["loud"] * (1 - a) + loud * a, "speed": b["speed"] * (1 - a) + speed * a,
         "pitch": (b["pitch"] * (1 - a) + pitch * a) if pitch else b["pitch"], "n": b["n"] + 1}
    BASELINE.write_text(json.dumps(b))
    return ", ".join(cues) or "normal for them"


def synthesize(text: str) -> bytes:
    global _piper
    from piper import PiperVoice

    from piper import SynthesisConfig

    if _piper is None:
        _piper = PiperVoice.load(voice_path())
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        # a touch slower and steadier than default: calm, measured delivery
        _piper.synthesize_wav(text, w, syn_config=SynthesisConfig(length_scale=1.08, noise_scale=0.55))
    return buf.getvalue()


def decode(audio: bytes, rate=16000):
    """Browser recording (webm/opus or mp4) -> 16 kHz mono float32. Done here instead of in
    faster-whisper because its decoder breaks with newer PyAV releases."""
    import av
    import numpy as np

    out = []
    resampler = av.AudioResampler(format="s16", layout="mono", rate=rate)
    with av.open(io.BytesIO(audio), mode="r") as container:
        for frame in container.decode(audio=0):
            for f in resampler.resample(frame):
                out.append(f.to_ndarray().reshape(-1))
        for f in resampler.resample(None):  # flush
            out.append(f.to_ndarray().reshape(-1))
    if not out:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate(out).astype(np.float32) / 32768.0
