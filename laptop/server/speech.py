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


def tts_available():
    if not config.PIPER_VOICE:
        return False
    try:
        import piper  # noqa: F401
        return True
    except ImportError:
        return False


def transcribe(audio: bytes) -> str:
    global _whisper
    from faster_whisper import WhisperModel

    if _whisper is None:
        _whisper = WhisperModel(config.WHISPER_MODEL, device="auto", compute_type="int8")
    samples = decode(audio)
    if samples.size < 1600:  # under 0.1 s: nothing to hear
        return ""
    segments, _ = _whisper.transcribe(samples, beam_size=1, vad_filter=True, language="en")
    return " ".join(s.text.strip() for s in segments).strip()


def synthesize(text: str) -> bytes:
    global _piper
    from piper import PiperVoice

    if _piper is None:
        _piper = PiperVoice.load(config.PIPER_VOICE)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        _piper.synthesize_wav(text, w)
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
