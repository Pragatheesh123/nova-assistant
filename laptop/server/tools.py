"""Everything Nova can do.

Each tool declares WHERE it runs:
  host   - on the laptop that runs the Nova server (open apps, volume, lock screen...)
  client - inside a connected Nova app (phone or laptop browser): calls, timers, links
  either - fine on any device (opening a website); the router picks the best one

and optionally a capability it NEEDS (e.g. "telephony" - only phones can place calls).
The router (router.py) uses these two facts to decide which device acts.
"""
import datetime
import platform
import shutil
import subprocess
import urllib.parse
import webbrowser
from dataclasses import dataclass, field
from typing import Callable, Optional

from . import browser, config, guard, web

OS = platform.system()  # "Windows" | "Darwin" | "Linux"


@dataclass
class Tool:
    name: str
    description: str
    params: dict = field(default_factory=dict)
    required: list = field(default_factory=list)
    where: str = "host"
    needs: Optional[str] = None
    risky: bool = False
    run: Optional[Callable] = None  # host implementation; client tools run in app.js
    needs_ctx: bool = False  # async tool that gets a context (step updates, leak guard)
    egress: Optional[str] = None  # argument that leaves the laptop -> checked by the leak guard
    egress_to: str = ""  # where it goes, if not a URL

    def schema(self, device_names):
        props = dict(self.params)
        props["device"] = {
            "type": "string",
            "enum": ["auto", *device_names],
            "description": "Which device should do this. Use 'auto' unless the user named a device.",
        }
        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": {"type": "object", "properties": props, "required": self.required},
            },
        }


def _sh(args, **kw):
    subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, **kw)


# ---------------------------------------------------------------- host actions

APP_ALIASES = {
    # spoken name: {os: command}
    "calculator": {"Windows": "calc", "Darwin": "Calculator", "Linux": "gnome-calculator"},
    "notepad": {"Windows": "notepad", "Darwin": "TextEdit", "Linux": "gedit"},
    "terminal": {"Windows": "wt", "Darwin": "Terminal", "Linux": "x-terminal-emulator"},
    "file explorer": {"Windows": "explorer", "Darwin": "Finder", "Linux": "nautilus"},
    "files": {"Windows": "explorer", "Darwin": "Finder", "Linux": "nautilus"},
    "chrome": {"Windows": "chrome", "Darwin": "Google Chrome", "Linux": "google-chrome"},
    "vs code": {"Windows": "code", "Darwin": "Visual Studio Code", "Linux": "code"},
    "vscode": {"Windows": "code", "Darwin": "Visual Studio Code", "Linux": "code"},
    "spotify": {"Windows": "spotify:", "Darwin": "Spotify", "Linux": "spotify"},
    "settings": {"Windows": "ms-settings:", "Darwin": "System Settings", "Linux": "gnome-control-center"},
}


def open_app(name: str):
    key = name.lower().strip()
    target = APP_ALIASES.get(key, {}).get(OS, name)
    try:
        if OS == "Windows":
            _sh(["cmd", "/c", "start", "", target])
        elif OS == "Darwin":
            subprocess.run(["open", "-a", target], check=True, capture_output=True)
        else:
            exe = shutil.which(target) or shutil.which(key.replace(" ", "-"))
            if exe:
                _sh([exe], start_new_session=True)
            elif shutil.which("gtk-launch"):
                subprocess.run(["gtk-launch", target], check=True, capture_output=True)
            else:
                return f"I couldn't find an app called {name}."
    except Exception:
        return f"I couldn't open {name}."
    return f"Opening {name}."


def open_url(url: str):
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    webbrowser.open(url)
    return "Done."


def web_search(query: str):
    webbrowser.open("https://www.google.com/search?q=" + urllib.parse.quote(query))
    return f"Searching for {query}."


def set_volume(level: int):
    level = max(0, min(100, int(level)))
    try:
        if OS == "Darwin":
            subprocess.run(["osascript", "-e", f"set volume output volume {level}"], check=True)
        elif OS == "Linux":
            if shutil.which("pactl"):
                subprocess.run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{level}%"], check=True)
            else:
                subprocess.run(["amixer", "-q", "sset", "Master", f"{level}%"], check=True)
        else:
            from ctypes import POINTER, cast  # needs: pip install pycaw
            from comtypes import CLSCTX_ALL
            from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume

            dev = AudioUtilities.GetSpeakers().Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
            cast(dev, POINTER(IAudioEndpointVolume)).SetMasterVolumeLevelScalar(level / 100, None)
    except Exception:
        return "I couldn't change the volume on this system."
    return f"Volume set to {level} percent."


_WIN_MEDIA_KEYS = {"play_pause": 179, "next": 176, "previous": 177, "mute": 173}


def media(action: str):
    action = action if action in _WIN_MEDIA_KEYS else "play_pause"
    try:
        if OS == "Windows":
            key = _WIN_MEDIA_KEYS[action]
            _sh(["powershell", "-NoProfile", "-Command",
                 f"(New-Object -ComObject WScript.Shell).SendKeys([char]{key})"])
        elif OS == "Darwin":
            verb = {"play_pause": "playpause", "next": "next track",
                    "previous": "previous track", "mute": "pause"}[action]
            app = "Spotify" if _mac_running("Spotify") else "Music"
            subprocess.run(["osascript", "-e", f'tell application "{app}" to {verb}'], check=True)
        else:
            verb = {"play_pause": "play-pause", "next": "next", "previous": "previous", "mute": "pause"}[action]
            subprocess.run(["playerctl", verb], check=True)
    except Exception:
        return "I couldn't reach a media player."
    return {"play_pause": "Done.", "next": "Skipping.", "previous": "Going back.", "mute": "Paused."}[action]


def _mac_running(app):
    r = subprocess.run(["pgrep", "-x", app], capture_output=True)
    return r.returncode == 0


def lock_screen():
    if OS == "Windows":
        _sh(["rundll32.exe", "user32.dll,LockWorkStation"])
    elif OS == "Darwin":
        _sh(["pmset", "displaysleepnow"])
    else:
        _sh(["loginctl", "lock-session"])
    return "Locking your laptop."


def system_status():
    try:
        import psutil
    except ImportError:
        return "Install psutil so I can read system stats."
    parts = [f"CPU at {psutil.cpu_percent(interval=0.3):.0f} percent",
             f"memory at {psutil.virtual_memory().percent:.0f} percent"]
    bat = psutil.sensors_battery() if hasattr(psutil, "sensors_battery") else None
    if bat:
        parts.append(f"battery at {bat.percent:.0f} percent{', charging' if bat.power_plugged else ''}")
    return "Your laptop is running with " + ", ".join(parts) + "."


NOTES = config.DATA / "notes.md"


def take_note(text: str):
    with NOTES.open("a", encoding="utf-8") as f:
        f.write(f"- {datetime.datetime.now():%Y-%m-%d %H:%M}  {text}\n")
    return "Noted."


def read_notes():
    if not NOTES.exists():
        return "You don't have any notes yet."
    lines = NOTES.read_text(encoding="utf-8").strip().splitlines()[-5:]
    return "Your latest notes: " + "; ".join(l.split("  ", 1)[-1] for l in lines)


def run_command(command: str):
    if not config.ALLOW_SHELL:
        return "Shell commands are turned off. Set NOVA_ALLOW_SHELL=1 to allow them."
    r = subprocess.run(command, shell=True, capture_output=True, text=True, timeout=60)
    out = (r.stdout or r.stderr).strip()
    return out[-600:] or f"Finished with exit code {r.returncode}."


async def web_lookup(ctx, query: str):
    if not await ctx.guard("web search", "duckduckgo.com", query):
        return "Aborted. Nothing was sent."
    await ctx.step(f"Looking up “{query}”")
    try:
        digest, hosts = await web.lookup(query)
    except Exception as e:
        return f"The web lookup failed ({e.__class__.__name__}). Answer from what you know and say so."
    for h in hosts[1:]:
        guard.log("read page", h, query, "sent")
    return digest


# ---------------------------------------------------------------- registry

TOOLS = {t.name: t for t in [
    Tool("open_app", "Open an application on the laptop, e.g. Spotify, VS Code, Calculator.",
         {"name": {"type": "string"}}, ["name"], where="host", run=open_app),
    Tool("open_url", "Open a website in a new tab (no need to read it afterwards). Use a full domain like github.com.",
         {"url": {"type": "string"}}, ["url"], where="either", run=open_url, egress="url"),
    Tool("web_search", "Show Google search results to the user in the browser.",
         {"query": {"type": "string"}}, ["query"], where="either", run=web_search,
         egress="query", egress_to="google.com"),
    Tool("web_lookup", "Look something up on the web and read the top results, so YOU can answer. "
         "Use for news, prices, weather, sports, people, anything recent or that you're unsure about.",
         {"query": {"type": "string"}}, ["query"], where="host", run=web_lookup, needs_ctx=True),
    Tool("youtube", "YouTube shortcuts: action 'search' shows results, 'channel' opens a creator's channel, "
         "'play' plays the top video for the query.",
         {"action": {"type": "string", "enum": ["search", "channel", "play"]}, "query": {"type": "string"}},
         ["action"], where="host", run=browser.youtube, needs_ctx=True),
    Tool("browser_tabs", "List all open browser tabs.", where="host", run=browser.tabs, needs_ctx=True),
    Tool("browser_open", "Open a page in a new browser tab and read it, so you can then click/type on it.",
         {"url": {"type": "string"}}, ["url"], where="host", run=browser.open_tab, needs_ctx=True),
    Tool("browser_switch", "Switch to an open tab by its number from browser_tabs, or a word from its title/site.",
         {"tab": {"type": "string"}}, ["tab"], where="host", run=browser.switch_tab, needs_ctx=True),
    Tool("browser_close", "Close a tab: 'current', a tab number, or a word from its title/site.",
         {"tab": {"type": "string"}}, ["tab"], where="host", run=browser.close_tab, needs_ctx=True),
    Tool("browser_read", "Read the current tab: its text and numbered clickable elements.",
         where="host", run=browser.read_page, needs_ctx=True),
    Tool("browser_click", "Click a numbered element from the last page reading.",
         {"element": {"type": "integer"}}, ["element"], where="host", run=browser.click, needs_ctx=True),
    Tool("browser_type", "Type into a numbered input from the last page reading; submit presses Enter.",
         {"element": {"type": "integer"}, "text": {"type": "string"}, "submit": {"type": "boolean"}},
         ["element", "text"], where="host", run=browser.type_text, needs_ctx=True),
    Tool("browser_scroll", "Scroll the current tab up or down.",
         {"direction": {"type": "string", "enum": ["up", "down"]}}, [], where="host", run=browser.scroll, needs_ctx=True),
    Tool("browser_back", "Go back in the current tab.", where="host", run=browser.back, needs_ctx=True),
    Tool("set_volume", "Set the laptop speaker volume from 0 to 100.",
         {"level": {"type": "integer"}}, ["level"], where="host", run=set_volume),
    Tool("media", "Control music/video playback on the laptop.",
         {"action": {"type": "string", "enum": ["play_pause", "next", "previous", "mute"]}},
         ["action"], where="host", run=media),
    Tool("lock_screen", "Lock the laptop.", where="host", risky=True, run=lock_screen),
    Tool("system_status", "Report laptop CPU, memory and battery.", where="host", run=system_status),
    Tool("take_note", "Save a note for the user.",
         {"text": {"type": "string"}}, ["text"], where="host", run=take_note),
    Tool("read_notes", "Read back the user's latest notes.", where="host", run=read_notes),
    Tool("run_command", "Run a shell command on the laptop. Only when the user explicitly asks.",
         {"command": {"type": "string"}}, ["command"], where="host", risky=True, run=run_command),
    # client-side: executed by the Nova app on the chosen device (see web/app.js ACTIONS)
    Tool("set_timer", "Start a countdown timer that alerts the user.",
         {"seconds": {"type": "integer"}, "label": {"type": "string"}}, ["seconds"], where="client"),
    Tool("call", "Phone call a number.",
         {"number": {"type": "string"}}, ["number"], where="client", needs="telephony"),
    Tool("send_sms", "Start a text message to a number.",
         {"number": {"type": "string"}, "body": {"type": "string"}}, ["number"],
         where="client", needs="telephony", egress="body", egress_to="SMS"),
    Tool("navigate", "Get directions to a place in Maps.",
         {"destination": {"type": "string"}}, ["destination"], where="client",
         egress="destination", egress_to="Google Maps"),
    Tool("vibrate", "Vibrate / buzz the phone, e.g. to find it.", where="client", needs="vibrate"),
]}
