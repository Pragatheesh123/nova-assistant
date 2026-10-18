"""Leak guard: everything Nova is about to send OFF your laptop passes through here.

The AI model itself runs locally (Ollama), so your conversation never leaves. What can leave:
web searches, pages Nova opens, and text Nova types into websites. Each of those is logged,
and if it contains something sensitive the user gets a pop-up with an Abort button
(abort is also what happens if nobody answers).
"""
import re
import time
from dataclasses import dataclass

from . import config

PROTECTED_FILE = config.DATA / "protected.txt"


@dataclass
class Finding:
    kind: str
    sample: str  # masked, safe to show


def _mask(s: str) -> str:
    s = s.strip()
    if len(s) <= 4:
        return "•" * len(s)
    keep = 2 if len(s) < 12 else 4
    return s[:keep] + "•" * min(8, len(s) - 2 * keep) + s[-keep:]


def _luhn(digits: str) -> bool:
    total, alt = 0, False
    for ch in reversed(digits):
        d = int(ch)
        if alt:
            d *= 2
            if d > 9:
                d -= 9
        total += d
        alt = not alt
    return total % 10 == 0


PATTERNS = [
    ("private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("API key", re.compile(r"\b(sk-[A-Za-z0-9_-]{20,}|sk-ant-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|"
                           r"xox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})\b")),
    ("password", re.compile(r"(?i)\b(?:password|passwd|pwd|passcode)\b\s*(?:is|=|:)?\s*(\S{4,})")),
    ("OTP / PIN", re.compile(r"(?i)\b(?:otp|pin|cvv|cvc)\b\s*(?:is|=|:)?\s*(\d{3,8})\b")),
    ("PAN card", re.compile(r"\b[A-Z]{5}\d{4}[A-Z]\b")),
    ("Aadhaar number", re.compile(r"\b[2-9]\d{3}[ -]?\d{4}[ -]?\d{4}\b")),
    ("US SSN", re.compile(r"\b\d{3}-\d{2}-\d{4}\b")),
    ("IBAN", re.compile(r"\b[A-Z]{2}\d{2}[A-Z0-9]{11,30}\b")),
    ("email address", re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]{2,}\b")),
]
CARD = re.compile(r"\b(?:\d[ -]?){13,19}\b")
PHONE = re.compile(r"(?<![\w])(\+?\d[\d ()-]{8,16}\d)(?![\w])")


def _protected_words():
    try:
        return [w.strip() for w in PROTECTED_FILE.read_text(encoding="utf-8").splitlines()
                if w.strip() and not w.startswith("#")]
    except FileNotFoundError:
        PROTECTED_FILE.write_text(
            "# Words Nova must never send off your laptop without asking (one per line).\n"
            "# e.g. your home address, full name, account numbers.\n", encoding="utf-8")
        return []


def scan(text: str) -> list[Finding]:
    """Return sensitive things found in text that is about to leave the laptop."""
    if not text:
        return []
    found: list[Finding] = []
    taken: list[tuple[int, int]] = []

    def add(kind, m, group=0):
        span = m.span(group)
        if any(a < span[1] and span[0] < b for a, b in taken):
            return
        taken.append(span)
        found.append(Finding(kind, _mask(m.group(group))))

    for m in CARD.finditer(text):  # before Aadhaar/phone so a card isn't mislabelled
        digits = re.sub(r"\D", "", m.group())
        if 13 <= len(digits) <= 19 and _luhn(digits):
            add("card number", m)
    for kind, rx in PATTERNS:
        for m in rx.finditer(text):
            add(kind, m, 1 if rx.groups else 0)
    for m in PHONE.finditer(text):
        if 10 <= len(re.sub(r"\D", "", m.group(1))) <= 13:
            add("phone number", m, 1)
    low = text.lower()
    for w in _protected_words():
        i = low.find(w.lower())
        if i >= 0:
            found.append(Finding("protected word", _mask(text[i:i + len(w)])))
    return found


# ---------------------------------------------------------------- outbound log

LOG: list[dict] = []


def log(kind: str, destination: str, detail: str, status: str):
    entry = {"t": time.strftime("%H:%M:%S"), "kind": kind, "to": destination,
             "detail": detail[:160], "status": status}
    LOG.append(entry)
    del LOG[:-200]
    return entry
