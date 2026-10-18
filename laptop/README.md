# NOVA — your private voice assistant

Nova runs **on your laptop**. Your phone (and the laptop's own browser) connect to it over your Wi-Fi.
The AI model, speech recognition and your notes all stay on your own machine. Nothing is sent to the cloud.

```
            ┌──────────── your laptop ────────────┐
 phone ───► │  Nova server  ──►  Ollama (AI brain) │
 (app)  ◄── │      │                               │
            │      ├─► Whisper (hears you)         │
 laptop ──► │      └─► actions: apps, volume,      │
 browser ◄─ │           media, lock, notes…        │
            └──────────────────────────────────────┘
```

## PC setup (Windows, one time, ~15 minutes)

1. **Python:** install from https://www.python.org/downloads/ and **tick "Add python.exe to PATH"**.
2. **Ollama (the AI brain):** install from https://ollama.com, then open a terminal and run:
   ```
   ollama pull qwen3:8b
   ```
   Weaker laptop (8 GB RAM or less)? Use `ollama pull qwen3:4b` and set `NOVA_MODEL=qwen3:4b`.
3. **Get Nova:** on GitHub open your `nova-assistant` repo → **Code → Download ZIP**, extract it, and open
   the `laptop` folder. (Updating? Copy the new files over the old ones, but keep your `.venv` folder.)
4. **Start it:** double-click **`start.bat`**. The first run installs everything (a few minutes).
   Nova then opens in **its own window**. Allow the microphone when asked.
5. **Chrome/Edge extension (tab control):** open `chrome://extensions` (or `edge://extensions`) → turn on
   **Developer mode** → **Load unpacked** → choose the `laptop\extension` folder. Click the Nova icon:
   it should say **Connected to Nova**.
6. **Optional:** double-click **`autostart.bat`** so Nova starts quietly whenever you sign in.

**Using it:** press **Ctrl + Alt + N** anywhere to call Nova, or click the orb / press Space in its window.
**Phone:** the Nova Android app works on its own. You don't need the laptop for it.

## Browser control (all your tabs)

1. Open Chrome (or Edge/Brave) → `chrome://extensions` → turn on **Developer mode** (top right).
2. Click **Load unpacked** and pick the `laptop/extension` folder.
3. Pin the Nova icon. Click it: it says **Connected to Nova** while Nova is running.

Now you can say things like:
- "Open YouTube and go to MrBeast's channel" · "play lofi beats on YouTube"
- "What tabs do I have open?" · "switch to my Gmail tab" · "close the Amazon tab"
- "Go to github.com, search for whisper, and open the first result"
- "Who won the match last night?" / "What's the price of an RTX 5070?" (Nova looks it up and answers)

The extension only talks to Nova on `127.0.0.1` (this laptop). Websites can't connect to it.
It never reads what's typed in password or card fields.

## Leak guard

Nova's AI runs on your laptop, so your conversations never leave it. The only things that go out are
web searches, pages Nova opens, and text Nova types into websites. **All of it passes through the leak guard:**

- It's checked for passwords, OTPs/PINs, card numbers, Aadhaar, PAN, emails, phone numbers, API keys,
  private keys, and any words you list in `data/protected.txt` (your address, full name, etc.).
- If something sensitive is found, a red **"Data about to leave your laptop"** pop-up shows what and where,
  with **Abort** and **Allow once**. No answer in 25 seconds = aborted.
- Typing into password or card fields always asks first.
- The **Stop** button (or Esc) cancels whatever Nova is doing.
- The shield icon shows the **privacy log**: everything Nova sent out, and what was blocked.
- Web page text is treated as untrusted, so a page can't instruct Nova to send your data somewhere.

Honest limit: the guard covers what *Nova* sends. It can't see what other apps on your laptop do.

## How Nova decides *where* to do things

Every action has a home:

| You say | Where it happens | Why |
|---|---|---|
| "Open Spotify", "volume 30", "pause", "lock my laptop" | Laptop | Only the laptop can control its apps |
| "Call 555 0100", "text mom…", "find my phone" | Phone | Only phones can make calls or vibrate. If you ask from the laptop, it goes to the phone. |
| "Set a timer", "directions to the airport" | The device you're talking to | It makes the most sense there |
| "Open YouTube **on my phone**" / "**on my laptop**" | The device you name | Naming a device always wins |

The devices that are connected right now are shown as chips at the top of the app.
To give a device its own name, open Settings → *This device's name* (for example "tablet").

## Safety

- Locking the laptop and running shell commands **always ask you first** with an Allow/Cancel card.
- Shell commands are **off** unless you start Nova with `NOVA_ALLOW_SHELL=1`.
- Only devices you pair with the QR code can connect. The pairing token is stored in `data/pairing.json`.
  Delete that file to unpair every device.

## Things you can say

"Nova, open VS Code" · "set volume to 40" · "next song" · "how's my battery" · "take a note that the dentist
is at 4" · "read my notes" · "set a timer for 10 minutes" · "search for pasta recipes" ·
"call 555 0100" · "find my phone" · "navigate to Central Park" · "lock my laptop" · plus normal
conversation once Ollama is running.

**Hands-free:** turn it on in Settings, then start a sentence with "Nova…". Leave the app open.

## Settings (environment variables)

| Variable | Default | |
|---|---|---|
| `NOVA_MODEL` | `qwen3:8b` | Any Ollama model that supports tools (llama3.1, qwen2.5, mistral-nemo…) |
| `NOVA_WHISPER_MODEL` | `base.en` | `small.en` is more accurate but slower |
| `NOVA_PIPER_VOICE` | *(none)* | Path to a Piper `.onnx` voice for a natural local voice (`pip install piper-tts`) |
| `NOVA_PORT` | `8765` | |
| `NOVA_ALLOW_SHELL` | `0` | `1` lets Nova run commands you ask for (it still asks you first) |

## Adding your own abilities

Open `server/tools.py`, write a Python function, and add it to `TOOLS` with `where="host"`.
The AI picks it up automatically. For something that should happen on the phone, use `where="client"`
and add a handler with the same name to `ACTIONS` in `web/app.js`.

## Honest limits

- A phone web app **can't control other apps** on your phone. It can open links, start calls and texts,
  give directions, run timers and vibrate. Deeper phone automation needs a native app or Tasker (Android).
- When you're away from home, your phone can't reach the laptop unless you use something like
  [Tailscale](https://tailscale.com) (free), which puts both devices on a private network.
- Hands-free mode only listens while the Nova app is open on the screen.
