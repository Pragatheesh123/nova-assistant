"""Nova server. Run on your laptop:  python -m server.main   (from the nova folder)"""
import argparse
import asyncio
import ipaddress
import os
import platform
import socket
import uuid

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import Response
from fastapi.staticfiles import StaticFiles

from . import config, guard, speech
from .brain import Brain
from .browser import ext_socket
from .router import Router
from .tools import TOOLS

app = FastAPI(title="Nova")
app.add_api_websocket_route("/ext", ext_socket)  # browser extension (laptop only, checked inside)
router = Router()
brain = Brain()
brain_lock = asyncio.Lock()
pending: dict[str, asyncio.Future] = {}
tasks: dict[str, asyncio.Task] = {}  # running request per device, so Stop can cancel it
LEAK_TIMEOUT = 25  # seconds; no answer = abort

# What Nova says after handing an action to a device.
CLIENT_DONE = {
    "set_timer": "Timer started.", "call": "Ready to call.", "send_sms": "Your message is ready.",
    "navigate": "Here are your directions.", "open_url": "Opening it.", "web_search": "Here's your search.",
    "vibrate": "Buzzing it now.",
}


def is_local(host):
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return host in ("localhost", "testclient")


def authorized(host, token):
    return is_local(host) or token == config.TOKEN


async def broadcast_devices():
    devices = [d.info() for d in router.devices.values()]
    for d in list(router.devices.values()):
        await safe_send(d, {"type": "devices", "devices": devices})


async def safe_send(dev, msg):
    try:
        await dev.ws.send_json(msg)
    except Exception:
        pass


def describe(name, args):
    a = {k: v for k, v in args.items() if k != "device"}
    return {
        "lock_screen": "Lock the laptop?",
        "run_command": f"Run this command on the laptop?\n{a.get('command', '')}",
    }.get(name, f"Allow {name}?")


class Ctx:
    """Given to tools that talk to the outside world: progress updates + the leak guard."""

    def __init__(self, origin):
        self.origin = origin

    async def step(self, text):
        await safe_send(self.origin, {"type": "step", "text": text})

    async def guard(self, kind, destination, payload, force=False, reason=""):
        """True if it's OK to send `payload` to `destination`. Asks the user if it looks sensitive."""
        findings = guard.scan(str(payload))
        if not findings and not force:
            entry = guard.log(kind, destination, str(payload), "sent")
            await broadcast({"type": "egress", "entry": entry})
            return True
        items = [f"{f.kind}: {f.sample}" for f in findings] or [reason or "sensitive field"]
        cid = uuid.uuid4().hex[:8]
        fut = asyncio.get_running_loop().create_future()
        pending[cid] = fut
        await broadcast({"type": "leak", "id": cid, "to": destination, "what": kind,
                         "items": items, "timeout": LEAK_TIMEOUT})
        print(f"  ! leak guard: {kind} -> {destination}: {items}")
        try:
            ok = await asyncio.wait_for(fut, LEAK_TIMEOUT)
        except asyncio.TimeoutError:
            ok = False
        finally:
            pending.pop(cid, None)
            await broadcast({"type": "leak_done", "id": cid})
        entry = guard.log(kind, destination, "; ".join(items), "allowed by you" if ok else "BLOCKED")
        await broadcast({"type": "egress", "entry": entry})
        return ok


async def broadcast(msg):
    for d in list(router.devices.values()):
        await safe_send(d, msg)


def _egress_target(tool, value):
    import urllib.parse
    if tool.egress_to:
        return tool.egress_to
    host = urllib.parse.urlparse(value if "://" in value else "https://" + value).netloc
    return host or "the internet"


async def handle(origin, text):
    ctx = Ctx(origin)

    async def execute(name, args):
        tool = TOOLS.get(name)
        if not tool:
            return f"Unknown tool {name}."
        args = dict(args)
        hint = args.pop("device", "auto")
        where, target = router.route(tool, origin, hint)
        print(f"  -> {name}{args} via {where}{'' if target is None or isinstance(target, str) else ':' + target.name}")
        if where == "error":
            return target
        if tool.egress and args.get(tool.egress):
            value = str(args[tool.egress])
            if not await ctx.guard(name.replace("_", " "), _egress_target(tool, value), value):
                return "Aborted by the user. Nothing was sent."
        if tool.risky:
            cid = uuid.uuid4().hex[:8]
            fut = asyncio.get_running_loop().create_future()
            pending[cid] = fut
            await safe_send(origin, {"type": "confirm", "id": cid, "text": describe(name, args)})
            try:
                ok = await asyncio.wait_for(fut, 30)
            except asyncio.TimeoutError:
                ok = False
            finally:
                pending.pop(cid, None)
            if not ok:
                return "Okay, I've left it alone."
        if where == "host":
            try:
                if tool.needs_ctx:
                    return await tool.run(ctx, **args)
                return await asyncio.to_thread(tool.run, **args)
            except Exception as e:
                return f"That failed: {e}"
        await safe_send(target, {"type": "action", "action": name, "args": args})
        if target is not origin:
            await safe_send(origin, {"type": "notice", "text": f"Sent to {target.name}"})
            return f"Sent to your {target.name}."
        return CLIENT_DONE.get(name, "Done.")

    await safe_send(origin, {"type": "state", "state": "thinking"})
    try:
        async with brain_lock:
            reply = await brain.think(text, origin, router.names(), execute)
    except asyncio.CancelledError:
        await safe_send(origin, {"type": "reply", "text": "Stopped."})
        raise
    await safe_send(origin, {"type": "reply", "text": reply})


def same_origin(ws: WebSocket) -> bool:
    """Block web pages on other sites from talking to Nova through your browser."""
    import urllib.parse
    origin = ws.headers.get("origin")
    if not origin:
        return True  # not a browser (scripts, tests)
    return urllib.parse.urlparse(origin).netloc == ws.headers.get("host", "")


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    host = ws.client.host if ws.client else ""
    await ws.accept()
    if not same_origin(ws):
        await ws.close(code=4403)
        return
    if not authorized(host, ws.query_params.get("token")):
        await ws.close(code=4401)  # the app shows "Not paired"
        return
    hello = await ws.receive_json()
    dev = router.add(ws, hello.get("name"), hello.get("kind", "desktop"), hello.get("caps", []), is_local(host))
    print(f"+ {dev.name} connected ({dev.kind}, {host})")
    await ws.send_json({
        "type": "welcome", "device": dev.info(), "llm": await brain.check_llm(), "model": config.MODEL,
        "stt": speech.stt_available(), "tts": speech.tts_available(),
        "log": guard.LOG[-50:],
    })
    await broadcast_devices()
    try:
        while True:
            msg = await ws.receive_json()
            if msg.get("type") == "say" and msg.get("text", "").strip():
                print(f"[{dev.name}] {msg['text']}")
                if dev.id in tasks and not tasks[dev.id].done():
                    tasks[dev.id].cancel()  # a new request replaces the old one
                tasks[dev.id] = asyncio.create_task(handle(dev, msg["text"].strip()))
            elif msg.get("type") == "abort":
                for f in list(pending.values()):
                    if not f.done():
                        f.set_result(False)
                if dev.id in tasks and not tasks[dev.id].done():
                    tasks[dev.id].cancel()
            elif msg.get("type") == "confirm" and msg.get("id") in pending:
                if not pending[msg["id"]].done():
                    pending[msg["id"]].set_result(bool(msg.get("ok")))
    except WebSocketDisconnect:
        pass
    finally:
        router.remove(dev.id)
        print(f"- {dev.name} disconnected")
        await broadcast_devices()


def check_http(request: Request):
    host = request.client.host if request.client else ""
    if not authorized(host, request.headers.get("x-nova-token")):
        raise HTTPException(401)


@app.post("/api/stt")
async def stt(request: Request):
    check_http(request)
    if not speech.stt_available():
        raise HTTPException(501, "faster-whisper not installed")
    audio = await request.body()
    try:
        return {"text": await asyncio.to_thread(speech.transcribe, audio)}
    except Exception as e:
        print(f"! speech recognition failed: {type(e).__name__}: {e}")
        raise HTTPException(500, "speech recognition failed")


@app.post("/api/tts")
async def tts(request: Request):
    check_http(request)
    if not speech.tts_available():
        raise HTTPException(501, "piper not configured")
    text = (await request.json()).get("text", "")
    return Response(await asyncio.to_thread(speech.synthesize, text[:800]), media_type="audio/wav")


app.mount("/", StaticFiles(directory=config.WEB, html=True), name="web")


# ---------------------------------------------------------------- startup helpers

# ---------------------------------------------------------------- global hotkey

def _raise_window():
    """Bring the Nova app window to the front (Windows)."""
    if platform.system() != "Windows":
        return
    import ctypes
    user32 = ctypes.windll.user32
    hwnd = user32.FindWindowW(None, "Nova")
    if hwnd:
        user32.ShowWindow(hwnd, 9)  # SW_RESTORE
        user32.SetForegroundWindow(hwnd)


def open_window(listen=False):
    """Open Nova in its own app window (Edge or Chrome), with its own profile."""
    url = f"http://localhost:{config.PORT + 1}/" + ("?listen=1" if listen else "")
    profile = str(config.DATA / "window-profile")
    flags = [f"--app={url}", f"--user-data-dir={profile}", "--autoplay-policy=no-user-gesture-required",
             "--window-size=460,820", "--no-first-run", "--no-default-browser-check"]
    import shutil
    import subprocess
    candidates = [
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"),
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        shutil.which("google-chrome") or "", shutil.which("chromium") or "", shutil.which("microsoft-edge") or "",
    ]
    for exe in candidates:
        if exe and os.path.exists(exe):
            subprocess.Popen([exe, *flags])
            return
    import webbrowser
    webbrowser.open(url)


async def summon():
    local = [d for d in router.devices.values() if d.local]
    if local:
        await asyncio.to_thread(_raise_window)
        for d in local:
            await safe_send(d, {"type": "summon"})
    else:
        open_window(listen=True)


def start_hotkey(loop):
    try:
        from pynput import keyboard
    except Exception:
        print("  (Install pynput for the Ctrl+Alt+N hotkey: pip install pynput)")
        return
    try:
        hk = keyboard.GlobalHotKeys({config.HOTKEY: lambda: asyncio.run_coroutine_threadsafe(summon(), loop)})
        hk.daemon = True
        hk.start()
        print(f"  Hotkey: {config.HOTKEY.replace('<', '').replace('>', '').title()} summons Nova from anywhere")
    except Exception as e:
        print("  Hotkey unavailable:", e)


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def make_cert(ip):
    """Self-signed certificate so phone browsers allow the microphone over Wi-Fi."""
    cert, key = config.DATA / "cert.pem", config.DATA / "key.pem"
    if cert.exists() and key.exists() and (config.DATA / "cert.ip").read_text() == ip:
        return cert, key
    import datetime as dt
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    k = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Nova")])
    now = dt.datetime.now(dt.timezone.utc)
    c = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(k.public_key())
         .serial_number(x509.random_serial_number()).not_valid_before(now)
         .not_valid_after(now + dt.timedelta(days=825))
         .add_extension(x509.SubjectAlternativeName([
             x509.DNSName("localhost"), x509.IPAddress(ipaddress.ip_address(ip)),
             x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
         .sign(k, hashes.SHA256()))
    key.write_bytes(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                    serialization.NoEncryption()))
    cert.write_bytes(c.public_bytes(serialization.Encoding.PEM))
    (config.DATA / "cert.ip").write_text(ip)
    return cert, key


def main():
    import sys

    import uvicorn

    if sys.stdout is None:  # started silently (autostart): keep a log instead
        sys.stdout = sys.stderr = open(config.DATA / "nova.log", "a", buffering=1, encoding="utf-8")

    p = argparse.ArgumentParser(description="Nova voice assistant server")
    p.add_argument("--http", action="store_true", help="plain HTTP (phone mic won't work over Wi-Fi)")
    p.add_argument("--window", action="store_true", help="open Nova in its own app window once started")
    args = p.parse_args()

    ip = lan_ip()
    scheme = "http" if args.http else "https"
    phone_url = f"{scheme}://{ip}:{config.PORT}/?token={config.TOKEN}"
    print("\n  N O V A   is online\n")
    print(f"  Laptop : {scheme}://localhost:{config.PORT}")
    print(f"  Phone  : {phone_url}   (same Wi-Fi; scan the QR below)\n")
    try:
        import qrcode
        q = qrcode.QRCode(border=1)
        q.add_data(phone_url)
        q.print_ascii(invert=True)
    except Exception:
        pass  # QR is a convenience; some consoles can't draw it

    ssl = {}
    if not args.http:
        cert, key = make_cert(ip)
        ssl = {"ssl_certfile": str(cert), "ssl_keyfile": str(key)}
    print(f"  Laptop window & browser extension: http://localhost:{config.PORT + 1} (this laptop only)")

    async def serve():
        start_hotkey(asyncio.get_running_loop())
        if args.window:
            asyncio.get_running_loop().call_later(2.5, open_window)
        # Wi-Fi port (HTTPS, for the phone) + laptop-only port (no certificate warning, extension)
        main_srv = uvicorn.Server(uvicorn.Config(app, host=config.HOST, port=config.PORT, log_level="warning", **ssl))
        local_srv = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=config.PORT + 1, log_level="warning"))
        await asyncio.gather(main_srv.serve(), local_srv.serve())

    asyncio.run(serve())


if __name__ == "__main__":
    main()
