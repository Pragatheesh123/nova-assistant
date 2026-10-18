# Nova

Two parts, both private and on-device:
- **Phone:** the Android app below (download from **Releases**).
- **PC:** the [`laptop`](laptop/README.md) folder: setup steps are in its README.

## Nova for Android

A private, Jarvis-style voice assistant that lives on your phone. Hold the power button (or say “Hey Nova”), and a
small black panel pops up, listens, answers or does the task, then disappears. Everything, including the AI model,
runs on the phone. No laptop and no cloud.

## Install

1. On your Android phone open **Releases → latest** on this GitHub page and tap **Nova.apk**.
2. Android will ask to allow installing from your browser. Allow it, then install.
3. Open **Nova** and follow the five setup cards:
   1. **Allow access**: microphone, contacts, calls, texts, notifications.
   2. **Make Nova your assistant**: pick Nova under *Digital assistant app*.
      Samsung: also *Settings › Advanced features › Side button › Press and hold › Digital assistant*.
   3. **AI brain**: downloads a ~1.6 GB on-device model (Qwen2.5 1.5B). Use Wi-Fi.
   4. **“Hey Nova”** (optional): hands-free wake word, fully offline (Vosk). Uses a little battery.
   5. **Voice**: British or American.

## What it can do

Call · text (asks before sending) · WhatsApp · alarms · timers · reminders · open any app · torch · music
controls · volume · directions · web & YouTube search · camera · notes · time/date/battery · Wi-Fi/Bluetooth panels
· and open conversation through the on-device AI.

## How it works

- `AssistActivity`: the pop-up panel (listen → think → speak/act → close).
- `brain/Commands.kt`: instant offline understanding of everyday commands.
- `brain/Llm.kt` + `brain/Brain.kt`: on-device model (MediaPipe LLM Inference). It answers questions, or rewrites
  loosely worded requests into a command (`COMMAND: call mum`) that `Commands` runs.
- `actions/Actions.kt`: does the things.
- `assist/`: registers Nova as the system assistant (power-button long press).
- `wake/WakeService.kt`: offline “Hey Nova” wake word.

## Limits

- Android doesn't let any app switch Wi-Fi/Bluetooth on directly, so Nova opens the quick panel instead.
- “Hey Nova” stops if Android kills background apps. Allow Nova to run in the background (setup card 4).
- The AI model needs a phone with roughly 6–8 GB of RAM. Commands work on any phone.
