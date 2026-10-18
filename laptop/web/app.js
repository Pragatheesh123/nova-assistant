/* Nova client — runs on the laptop browser and on the phone (installable PWA). */
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const store = {
    get(k, d) { try { const v = localStorage.getItem("nova." + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem("nova." + k, JSON.stringify(v)); } catch {} },
  };

  // ---------------------------------------------------------------- device identity
  const params = new URLSearchParams(location.search);
  if (params.get("token")) {
    store.set("token", params.get("token"));
    history.replaceState(null, "", location.pathname);
  }
  const isPhone = /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent) ||
    (matchMedia("(pointer: coarse)").matches && innerWidth < 900);
  const caps = [];
  if (isPhone) caps.push("telephony");
  if ("vibrate" in navigator) caps.push("vibrate");

  const settings = {
    wake: store.get("wake", false),
    light: store.get("light", false),
    speak: store.get("speak", true),
    voice: store.get("voice", ""),
    name: store.get("name", ""),
  };
  document.documentElement.dataset.theme = settings.light ? "light" : "dark";

  let server = { stt: false, tts: false, llm: false };
  let me = null;

  // ---------------------------------------------------------------- UI helpers
  const feed = $("feed");
  function addMsg(kind, text) {
    const el = document.createElement("div");
    el.className = "msg " + kind;
    el.textContent = text;
    feed.appendChild(el);
    while (feed.children.length > 40) feed.firstChild.remove();
    feed.scrollTo({ top: feed.scrollHeight, behavior: "smooth" });
  }
  let toastTimer;
  function toast(text) {
    const t = $("toast");
    t.textContent = text;
    t.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove("show"), 2600);
  }
  let state = "idle";
  const LABELS = { idle: "Tap to speak", wake: "Say “Nova…”", listening: "Listening", thinking: "Thinking", speaking: "Speaking", offline: "Offline" };
  function setState(s) {
    state = s;
    const label = s === "idle" && settings.wake ? LABELS.wake : LABELS[s];
    $("stateLabel").textContent = label;
    $("stateLabel").classList.toggle("live", s !== "idle" && s !== "offline");
    $("micBtn").classList.toggle("live", s === "listening");
    $("micBtn").classList.toggle("wake", settings.wake);
    $("stopBtn").hidden = s !== "thinking";
  }

  const sheets = ["confirmSheet", "actionSheet", "settingsSheet", "leakSheet", "privacySheet"];
  function openSheet(id) { sheets.forEach((s) => $(s).classList.toggle("show", s === id)); $("backdrop").classList.add("show"); }
  function closeSheets() { sheets.forEach((s) => $(s).classList.remove("show")); $("backdrop").classList.remove("show"); }
  $("backdrop").onclick = () => {
    if ($("leakSheet").classList.contains("show")) return answerLeak(false);
    if ($("confirmSheet").classList.contains("show")) answerConfirm(false);
    closeSheets();
  };

  const ICONS = {
    phone: '<svg viewBox="0 0 24 24"><rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18.5h2"/></svg>',
    desktop: '<svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="12" rx="2"/><path d="M2 20h20"/></svg>',
  };
  function renderDevices(list) {
    $("devices").innerHTML = "";
    for (const d of list) {
      const c = document.createElement("span");
      c.className = "chip" + (me && d.id === me.id ? " me" : "");
      c.innerHTML = ICONS[d.kind] || ICONS.desktop;
      const n = document.createElement("span");
      n.textContent = d.name;
      c.appendChild(n);
      c.title = d.name + (me && d.id === me.id ? " (this device)" : "");
      $("devices").appendChild(c);
    }
  }

  // ---------------------------------------------------------------- connection
  let ws, retry = 0;
  function connect() {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const token = store.get("token", "");
    ws = new WebSocket(`${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`);
    ws.onopen = () => {
      retry = 0;
      ws.send(JSON.stringify({ type: "hello", name: settings.name || undefined, kind: isPhone ? "phone" : "desktop", caps }));
    };
    ws.onmessage = (e) => onServer(JSON.parse(e.data));
    ws.onclose = (e) => {
      $("status").classList.remove("on");
      $("status").querySelector("b").textContent = e.code === 4401 ? "Not paired" : "Reconnecting";
      if (e.code === 4401) { addMsg("meta", "This device isn't paired. Scan the QR code shown on your laptop's terminal."); setState("offline"); return; }
      setState("offline");
      setTimeout(connect, Math.min(8000, 600 * 2 ** retry++));
    };
  }
  function send(obj) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); else toast("Not connected to Nova"); }

  function onServer(m) {
    switch (m.type) {
      case "welcome":
        me = m.device; server = m;
        $("status").classList.add("on");
        $("status").querySelector("b").textContent = m.cloud ? m.model : m.llm ? "Local · " + m.model : "Basic mode";
        $("claudeRow").hidden = !me.local;
        if (!settings.name) $("optName").placeholder = me.name;
        $("engineInfo").textContent =
          `Brain: ${m.cloud ? m.model : m.llm ? m.model + " via Ollama" : "offline rules (add a free Groq key)"}. ` +
          `Hearing: ${m.stt ? "Whisper on your laptop" : "browser speech recognition"}. ` +
          `Voice: ${m.tts ? "Piper on your laptop" : "this device's built-in voice"}.`;
        setState("idle");
        plog = m.log || [];
        if (settings.wake) startWake();
        break;
      case "devices": renderDevices(m.devices); break;
      case "state": setState(m.state); break;
      case "reply":
        addMsg("nova", m.text);
        speak(m.text);
        break;
      case "notice": toast(m.text); break;
      case "brain":
        $("status").querySelector("b").textContent = m.cloud ? m.model : "Basic mode";
        toast(m.cloud ? m.model + " connected" : "Key removed");
        break;
      case "summon": unlockAudio(); chime(); if (state !== "listening") talk(); break;
      case "action": runAction(m.action, m.args || {}); break;
      case "confirm": askConfirm(m); break;
      case "step": addMsg("step", m.text); break;
      case "leak": showLeak(m); break;
      case "leak_done": if (leak && leak.id === m.id) { clearInterval(leak.timer); leak = null; closeSheets(); } break;
      case "egress": plog.push(m.entry); if (plog.length > 200) plog.shift(); break;
    }
  }

  let lastCues = ""; // how the last thing was said (from the laptop's voice analysis)
  function ask(text) {
    text = text.trim();
    if (!text) return;
    addMsg("you", text);
    send({ type: "say", text, cues: lastCues });
    lastCues = "";
  }
  $("askForm").onsubmit = (e) => { e.preventDefault(); unlockAudio(); ask($("askInput").value); $("askInput").value = ""; };

  // ---------------------------------------------------------------- confirmations
  let confirmId = null;
  function askConfirm(m) { confirmId = m.id; $("confirmText").textContent = m.text; openSheet("confirmSheet"); speak("Should I go ahead?"); }
  function answerConfirm(ok) { if (confirmId) send({ type: "confirm", id: confirmId, ok }); confirmId = null; closeSheets(); }
  $("confirmYes").onclick = () => answerConfirm(true);
  $("confirmNo").onclick = () => answerConfirm(false);

  // ---------------------------------------------------------------- leak guard + stop
  let leak = null, plog = [];
  function showLeak(m) {
    if (leak) clearInterval(leak.timer);
    leak = { id: m.id, end: Date.now() + m.timeout * 1000 };
    $("leakTitle").textContent = `Nova wants to send this to ${m.to} (${m.what}):`;
    $("leakItems").innerHTML = "";
    for (const it of m.items) { const li = document.createElement("li"); li.textContent = it; $("leakItems").appendChild(li); }
    const tick = () => {
      const left = Math.max(0, leak.end - Date.now());
      $("leakBar").style.transform = `scaleX(${left / (m.timeout * 1000)})`;
      $("leakNote").textContent = `Aborting automatically in ${Math.ceil(left / 1000)}s unless you allow it.`;
    };
    tick();
    leak.timer = setInterval(() => leak && tick(), 200);
    openSheet("leakSheet");
    chime(2);
    navigator.vibrate?.([200, 100, 200]);
    speak("Hold on. Something sensitive is about to leave your laptop.");
  }
  function answerLeak(ok) {
    if (!leak) return closeSheets();
    send({ type: "confirm", id: leak.id, ok });
    clearInterval(leak.timer);
    leak = null;
    closeSheets();
    if (!ok) toast("Blocked. Nothing was sent.");
  }
  $("leakAbort").onclick = () => answerLeak(false);
  $("leakAllow").onclick = () => answerLeak(true);
  $("stopBtn").onclick = () => { send({ type: "abort" }); stopSpeaking(); toast("Stopping"); };

  function renderLog() {
    const ul = $("plog");
    ul.innerHTML = "";
    if (!plog.length) { ul.innerHTML = '<li class="empty">Nothing has been sent yet.</li>'; return; }
    for (const e of [...plog].reverse()) {
      const li = document.createElement("li");
      const t = document.createElement("span"); t.className = "t"; t.textContent = e.t;
      const w = document.createElement("span"); w.textContent = `${e.kind} → ${e.to}`;
      const d = document.createElement("span"); d.className = "d" + (e.status === "BLOCKED" ? " bad" : "");
      d.textContent = `${e.status}${e.detail ? " · " + e.detail : ""}`;
      li.append(t, w, d);
      ul.appendChild(li);
    }
  }
  $("privacyBtn").onclick = () => { renderLog(); openSheet("privacySheet"); };
  $("privacyDone").onclick = closeSheets;

  // ---------------------------------------------------------------- client actions
  function offerLink(kicker, title, href, label) {
    $("actionKicker").textContent = kicker;
    $("actionTitle").textContent = title;
    $("actionGo").href = href;
    $("actionGo").textContent = label;
    openSheet("actionSheet");
  }
  $("actionGo").onclick = () => setTimeout(closeSheets, 150);
  $("actionNo").onclick = closeSheets;

  function openLink(href, title) {
    // Browsers block pop-ups that aren't tied to a tap, so fall back to a one-tap card.
    const w = window.open(href, "_blank", "noopener");
    if (!w) offerLink("Ready", title, href, "Open");
  }
  const ACTIONS = {
    open_url({ url }) { if (!/^https?:/.test(url)) url = "https://" + url; openLink(url, url.replace(/^https?:\/\//, "")); },
    web_search({ query }) { openLink("https://www.google.com/search?q=" + encodeURIComponent(query), "Search: " + query); },
    navigate({ destination }) {
      openLink("https://www.google.com/maps/dir/?api=1&destination=" + encodeURIComponent(destination), "Directions to " + destination);
    },
    call({ number }) { offerLink("Call", number, "tel:" + number.replace(/[^\d+]/g, ""), "Call"); },
    send_sms({ number, body = "" }) {
      offerLink("Message", body ? `${number}\n“${body}”` : number,
        `sms:${number.replace(/[^\d+]/g, "")}?&body=${encodeURIComponent(body)}`, "Open Messages");
    },
    vibrate() {
      navigator.vibrate?.([500, 200, 500, 200, 900]);
      chime(4);
      speak("I'm right here.");
    },
    set_timer({ seconds, label }) { startTimer(Number(seconds) || 60, label || "Timer"); },
  };
  function runAction(name, args) {
    try { (ACTIONS[name] || (() => toast("Unknown action " + name)))(args); }
    catch (e) { console.error(e); toast("Couldn't do " + name); }
  }

  // timers
  const timers = new Map();
  function fmt(s) { const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60; return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(r).padStart(2, "0"); }
  function startTimer(seconds, label) {
    const id = Math.random().toString(36).slice(2);
    const end = Date.now() + seconds * 1000;
    const el = document.createElement("div");
    el.className = "timer";
    const t = document.createElement("span");
    const x = document.createElement("button");
    x.textContent = "×"; x.setAttribute("aria-label", "Cancel timer");
    el.append(t, x);
    $("timers").appendChild(el);
    if ("Notification" in window && Notification.permission === "default") Notification.requestPermission();
    const tick = () => {
      const left = Math.max(0, Math.round((end - Date.now()) / 1000));
      t.textContent = label + " · " + fmt(left);
      if (left > 0) return;
      clear();
      chime(3); navigator.vibrate?.([300, 150, 300]);
      speak(`Your ${label} is done.`);
      addMsg("meta", `${label} finished`);
      try { if (Notification.permission === "granted") new Notification("Nova", { body: `${label} is done`, icon: "icon-192.png" }); } catch {}
    };
    const iv = setInterval(tick, 500);
    const clear = () => { clearInterval(iv); el.remove(); timers.delete(id); };
    x.onclick = clear;
    timers.set(id, clear);
    tick();
  }

  // ---------------------------------------------------------------- audio
  let actx, analyser, micStream, micSource;
  const level = { v: 0 };
  function unlockAudio() {
    if (!actx) actx = new (window.AudioContext || window.webkitAudioContext)();
    if (actx.state === "suspended") actx.resume();
    if (!analyser) { analyser = actx.createAnalyser(); analyser.fftSize = 512; analyser.smoothingTimeConstant = 0.6; }
  }
  const buf = new Float32Array(512);
  function readLevel() {
    if (!analyser) return 0;
    analyser.getFloatTimeDomainData(buf);
    let sum = 0;
    for (let i = 0; i < buf.length; i++) sum += buf[i] * buf[i];
    return Math.sqrt(sum / buf.length);
  }
  function chime(times = 1) {
    unlockAudio();
    for (let i = 0; i < times; i++) {
      const t0 = actx.currentTime + i * 0.45;
      for (const [f, d] of [[880, 0], [1320, 0.08]]) {
        const o = actx.createOscillator(), g = actx.createGain();
        o.type = "sine"; o.frequency.value = f;
        g.gain.setValueAtTime(0.0001, t0 + d);
        g.gain.exponentialRampToValueAtTime(0.25, t0 + d + 0.02);
        g.gain.exponentialRampToValueAtTime(0.0001, t0 + d + 0.6);
        o.connect(g).connect(actx.destination);
        o.start(t0 + d); o.stop(t0 + d + 0.65);
      }
    }
  }

  async function getMic() {
    if (!navigator.mediaDevices?.getUserMedia) {
      throw new Error(location.protocol === "http:" && !["localhost", "127.0.0.1"].includes(location.hostname)
        ? "The microphone needs HTTPS. Start Nova without --http and open the https:// link."
        : "This browser has no microphone access.");
    }
    unlockAudio();
    if (!micStream || micStream.getTracks().every((t) => t.readyState === "ended")) {
      micStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
      micSource = actx.createMediaStreamSource(micStream);
      micSource.connect(analyser);
    }
    return micStream;
  }

  // ---------------------------------------------------------------- speaking
  let speaking = false, ttsAudio = null, ttsSource = null;
  function pickVoice() {
    const voices = speechSynthesis.getVoices().filter((v) => v.lang.startsWith("en"));
    return voices.find((v) => v.name === settings.voice) ||
      voices.find((v) => /Daniel|Google UK English Male|Arthur|Oliver|Guy|Ryan/i.test(v.name) && v.localService) ||
      voices.find((v) => v.localService) || voices[0];
  }
  async function speak(text) {
    if (!settings.speak || !text) { if (state === "thinking") setState("idle"); return; }
    stopSpeaking();
    speaking = true; setState("speaking");
    const done = () => { speaking = false; if (state === "speaking") setState("idle"); };
    if (server.tts) {
      try {
        const r = await fetch("/api/tts", { method: "POST", headers: { "content-type": "application/json", "x-nova-token": store.get("token", "") }, body: JSON.stringify({ text }) });
        if (!r.ok) throw new Error();
        const url = URL.createObjectURL(await r.blob());
        unlockAudio();
        ttsAudio = new Audio(url);
        ttsSource = actx.createMediaElementSource(ttsAudio);
        ttsSource.connect(analyser); ttsSource.connect(actx.destination);
        ttsAudio.onended = () => { URL.revokeObjectURL(url); done(); };
        await ttsAudio.play();
        return;
      } catch { /* fall through to built-in voice */ }
    }
    if (!("speechSynthesis" in window)) return done();
    const u = new SpeechSynthesisUtterance(text);
    const v = pickVoice(); if (v) u.voice = v;
    u.rate = 1.02; u.pitch = 0.95;
    u.onboundary = () => { level.pulse = 1; };
    u.onend = u.onerror = done;
    speechSynthesis.speak(u);
  }
  function stopSpeaking() {
    try { speechSynthesis.cancel(); } catch {}
    if (ttsAudio) { ttsAudio.pause(); ttsAudio = null; }
    speaking = false;
  }

  // ---------------------------------------------------------------- listening
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  let recording = null;

  async function transcribe(blob) {
    const r = await fetch("/api/stt", { method: "POST", body: blob, headers: { "x-nova-token": store.get("token", "") } });
    if (!r.ok) throw new Error("stt failed");
    const j = await r.json();
    lastCues = j.cues || "";
    return j.text || "";
  }

  // Record one utterance; stops on silence. Resolves to text.
  async function listenOnce({ maxMs = 12000, silenceMs = 1200 } = {}) {
    stopSpeaking();
    if (!server.stt) return browserListenOnce();
    const stream = await getMic();
    const type = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"].find((t) => MediaRecorder.isTypeSupported?.(t)) || "";
    const rec = new MediaRecorder(stream, type ? { mimeType: type } : {});
    const chunks = [];
    rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);
    return new Promise((resolve, reject) => {
      let heard = false, quietSince = performance.now();
      const started = performance.now();
      const watch = setInterval(() => {
        const l = readLevel(), now = performance.now();
        if (l > 0.025) { heard = true; quietSince = now; }
        if ((heard && now - quietSince > silenceMs) || now - started > maxMs || (!heard && now - started > 6000)) stop();
      }, 60);
      const stop = () => { clearInterval(watch); if (rec.state !== "inactive") rec.stop(); };
      recording = { stop };
      rec.onstop = async () => {
        recording = null;
        if (!heard) return resolve("");
        setState("thinking");
        try { resolve(await transcribe(new Blob(chunks, { type: rec.mimeType }))); } catch (e) { reject(e); }
      };
      rec.start(250);
      setState("listening");
    });
  }

  function browserListenOnce() {
    if (!SR) return Promise.reject(new Error("No speech recognition here. Type instead, or install faster-whisper on the laptop."));
    return new Promise((resolve, reject) => {
      const r = new SR();
      r.lang = "en-US"; r.interimResults = false; r.maxAlternatives = 1;
      let text = "";
      r.onresult = (e) => { text = e.results[0][0].transcript; };
      r.onerror = (e) => (e.error === "no-speech" ? resolve("") : reject(new Error(e.error)));
      r.onend = () => { recording = null; resolve(text); };
      recording = { stop: () => r.stop() };
      getMic().catch(() => {}); // drive the orb with mic level if allowed
      r.start();
      setState("listening");
    });
  }

  async function talk() {
    unlockAudio();
    if (recording) { recording.stop(); return; }
    if (speaking) { stopSpeaking(); setState("idle"); }
    const resume = wake.on;
    pauseWake();
    try {
      const text = await listenOnce();
      if (text) ask(text); else setState("idle");
    } catch (e) {
      setState("idle"); toast(e.message || "Couldn't hear that");
    }
    if (resume) startWake();
  }
  $("micBtn").onclick = talk;
  $("orbWrap").onclick = talk;
  addEventListener("keydown", (e) => {
    if (e.code === "Space" && document.activeElement === document.body && !e.repeat) { e.preventDefault(); talk(); }
    if (e.key === "Escape") {
      if (leak) return answerLeak(false);
      if (state === "thinking") send({ type: "abort" });
      closeSheets(); recording?.stop(); stopSpeaking();
    }
  });

  // ---------------------------------------------------------------- hands-free wake word
  // Listens continuously for speech; anything starting with "Nova" becomes a command.
  const wake = { on: false, rec: null };
  const WAKE_RE = /^\s*(hey |ok |okay |hi )?(nova|noah|nover|noba)\b[\s,.!?]*/i;
  function handleWakeText(text) {
    const m = text.match(WAKE_RE);
    if (!m) return false;
    const cmd = text.slice(m[0].length).trim();
    if (cmd) { ask(cmd); return true; }
    chime(); // just "Nova" — wait for the command
    pauseWake();
    listenOnce().then((t) => { if (t) ask(t); else setState("idle"); }).catch(() => setState("idle")).finally(startWake);
    return true;
  }
  async function startWake() {
    if (!settings.wake || wake.on) return;
    wake.on = true;
    setState(state === "offline" ? "offline" : "idle");
    if (server.stt) {
      try { await getMic(); } catch (e) { toast(e.message); wake.on = false; return; }
      (async function loop() {
        while (wake.on) {
          if (speaking || state === "thinking") { await new Promise((r) => setTimeout(r, 300)); continue; }
          const text = await segment().catch(() => "");
          if (wake.on && text) handleWakeText(text);
        }
      })();
    } else if (SR) {
      const r = new SR();
      r.continuous = true; r.interimResults = false; r.lang = "en-US";
      r.onresult = (e) => { if (!speaking) handleWakeText(e.results[e.results.length - 1][0].transcript); };
      r.onend = () => { if (wake.on) try { r.start(); } catch {} };
      wake.rec = r;
      try { r.start(); } catch {}
    } else {
      toast("Hands-free needs faster-whisper on the laptop or a browser with speech recognition.");
      wake.on = false;
    }
  }
  function pauseWake() { wake.on = false; try { wake.rec?.abort(); } catch {} wake.rec = null; wake.segStop?.(); }

  // Wait for a burst of speech, record it, transcribe it.
  function segment() {
    return new Promise((resolve) => {
      const type = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"].find((t) => MediaRecorder.isTypeSupported?.(t)) || "";
      let rec = null, chunks = [], quietSince = 0, started = 0;
      const iv = setInterval(() => {
        if (!wake.on) return finish(false);
        const l = readLevel(), now = performance.now();
        if (!rec && l > 0.03) {
          rec = new MediaRecorder(micStream, type ? { mimeType: type } : {});
          rec.ondataavailable = (e) => e.data.size && chunks.push(e.data);
          rec.onstop = async () => {
            if (!chunks.length || performance.now() - started < 400) return resolve("");
            try { resolve(await transcribe(new Blob(chunks, { type: rec.mimeType }))); } catch { resolve(""); }
          };
          rec.start(200); started = now; quietSince = now;
        }
        if (rec) {
          if (l > 0.02) quietSince = now;
          if (now - quietSince > 900 || now - started > 8000) finish(true);
        }
      }, 50);
      const finish = (keep) => {
        clearInterval(iv);
        if (rec && rec.state !== "inactive") { if (!keep) chunks = []; rec.stop(); } else resolve("");
      };
      wake.segStop = () => finish(false);
    });
  }

  // ---------------------------------------------------------------- HUD orb
  // Jarvis-style: rotating tick ring, sweeping arcs, a voice spectrum and a glowing core.
  const canvas = $("orb"), ctx = canvas.getContext("2d");
  let W = 0, DPR = 1;
  function resize() {
    DPR = Math.min(2, devicePixelRatio || 1);
    W = canvas.clientWidth;
    canvas.width = canvas.height = Math.round(W * DPR);
  }
  new ResizeObserver(resize).observe(canvas);
  let t = 0, lvl = 0, energy = 0, spin = 1, last = 0;
  function fgRGB() { return document.documentElement.dataset.theme === "light" ? "10,10,10" : "244,241,234"; }
  const TAU = Math.PI * 2;

  function frame(now) {
    requestAnimationFrame(frame);
    if (!W) return;
    const dt = last ? Math.min(0.05, (now - last) / 1000) : 0.016;
    last = now; t += dt;
    let target = 0;
    if (state === "listening") target = Math.min(1, readLevel() * 9);
    if (state === "speaking") target = ttsAudio ? Math.min(1, readLevel() * 9) : 0.45 + 0.35 * Math.abs(Math.sin(t * 7.3) * Math.sin(t * 2.1 + 1));
    if (state === "thinking") target = 0.2;
    lvl += (target - lvl) * 0.2;
    energy += ((state === "idle" || state === "offline" ? 0.15 : 1) - energy) * 0.05;
    spin += ((state === "thinking" ? 4 : 1) - spin) * 0.04;

    const c = fgRGB(), S = W * DPR, cx = S / 2, cy = S / 2, R = S / 2 * 0.96, px = S / 300;
    const col = (a) => `rgba(${c},${a})`;
    ctx.clearRect(0, 0, S, S);
    ctx.lineCap = "round";

    // ambient glow
    let g = ctx.createRadialGradient(cx, cy, 0, cx, cy, R);
    g.addColorStop(0, col(0.10 + 0.18 * energy * (0.6 + lvl))); g.addColorStop(0.6, col(0.02)); g.addColorStop(1, col(0));
    ctx.fillStyle = g; ctx.fillRect(0, 0, S, S);

    // outer hairline + tick ring
    ctx.strokeStyle = col(0.18 + 0.1 * energy); ctx.lineWidth = px;
    ctx.beginPath(); ctx.arc(cx, cy, R * 0.985, 0, TAU); ctx.stroke();
    const rot1 = t * 0.1 * spin;
    for (let i = 0; i < 90; i++) {
      const a = i / 90 * TAU + rot1, long = i % 6 === 0;
      const r1 = R * (long ? 0.86 : 0.9), r2 = R * 0.95;
      ctx.strokeStyle = col(long ? 0.55 : 0.22); ctx.lineWidth = (long ? 1.6 : 1) * px;
      ctx.beginPath(); ctx.moveTo(cx + Math.cos(a) * r1, cy + Math.sin(a) * r1); ctx.lineTo(cx + Math.cos(a) * r2, cy + Math.sin(a) * r2); ctx.stroke();
    }

    // three sweeping arc segments
    const rot2 = -t * 0.7 * spin;
    ctx.strokeStyle = col(0.35 + 0.5 * energy); ctx.lineWidth = 2.6 * px;
    for (let j = 0; j < 3; j++) {
      const a0 = rot2 + j * TAU / 3, sw = (38 + 14 * Math.sin(t * 1.3 + j)) * Math.PI / 180;
      ctx.beginPath(); ctx.arc(cx, cy, R * 0.79, a0, a0 + sw); ctx.stroke();
    }

    // voice spectrum
    const base = R * 0.6;
    ctx.strokeStyle = col(0.25 + 0.55 * energy); ctx.lineWidth = 1.8 * px;
    for (let i = 0; i < 72; i++) {
      const a = i / 72 * TAU + t * 0.2;
      const n = 0.5 + 0.5 * Math.sin(i * 0.9 + t * 9) * Math.sin(i * 0.37 - t * 5.1);
      const len = R * (0.015 + (0.05 * energy + 0.2 * lvl) * n);
      ctx.beginPath(); ctx.moveTo(cx + Math.cos(a) * base, cy + Math.sin(a) * base);
      ctx.lineTo(cx + Math.cos(a) * (base + len), cy + Math.sin(a) * (base + len)); ctx.stroke();
    }

    // inner solid ring + rotating dashed ring
    ctx.strokeStyle = col(0.55); ctx.lineWidth = 1.4 * px;
    ctx.beginPath(); ctx.arc(cx, cy, R * 0.56, 0, TAU); ctx.stroke();
    ctx.strokeStyle = col(0.45); ctx.lineWidth = 1.6 * px;
    const rot3 = t * 0.44 * spin;
    for (let i = 0; i < 24; i++) {
      const a0 = rot3 + i * TAU / 24;
      ctx.beginPath(); ctx.arc(cx, cy, R * 0.47, a0, a0 + TAU / 24 * 0.55); ctx.stroke();
    }

    // orbiting marker
    const oa = t * 1.4 * spin;
    ctx.fillStyle = col(0.9);
    ctx.beginPath(); ctx.arc(cx + Math.cos(oa) * R * 0.7, cy + Math.sin(oa) * R * 0.7, 2.6 * px, 0, TAU); ctx.fill();

    // core
    const coreR = R * (0.3 + 0.05 * Math.sin(t * 1.6) * (1 - energy) + 0.08 * lvl);
    g = ctx.createRadialGradient(cx, cy, 0, cx, cy, coreR * 1.25);
    g.addColorStop(0, col(0.95)); g.addColorStop(0.5, col(0.35 + 0.3 * energy)); g.addColorStop(1, col(0));
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, coreR * 1.25, 0, TAU); ctx.fill();
    ctx.fillStyle = document.documentElement.dataset.theme === "light" ? "rgba(245,242,235,0.55)" : "rgba(5,5,5,0.55)";
    ctx.beginPath(); ctx.arc(cx, cy, coreR * 0.42, 0, TAU); ctx.fill();
    ctx.strokeStyle = col(1); ctx.lineWidth = 1.2 * px;
    ctx.beginPath(); ctx.arc(cx, cy, coreR * 0.42, 0, TAU); ctx.stroke();

    // thinking: scanning sector
    if (state === "thinking") {
      const a0 = t * 4.5;
      const sg = ctx.createConicGradient ? ctx.createConicGradient(a0, cx, cy) : null;
      if (sg) {
        sg.addColorStop(0, col(0)); sg.addColorStop(0.19, col(0.35)); sg.addColorStop(0.2, col(0)); sg.addColorStop(1, col(0));
        ctx.fillStyle = sg; ctx.beginPath(); ctx.arc(cx, cy, R * 0.95, 0, TAU); ctx.fill();
      }
    }
  }
  requestAnimationFrame(frame);

  // ---------------------------------------------------------------- settings
  function fillVoices() {
    const sel = $("optVoice");
    const voices = ("speechSynthesis" in window ? speechSynthesis.getVoices() : []).filter((v) => v.lang.startsWith("en"));
    sel.innerHTML = "";
    const auto = new Option("Automatic", ""); sel.add(auto);
    for (const v of voices) sel.add(new Option(`${v.name}${v.localService ? "" : " (online)"}`, v.name));
    sel.value = settings.voice;
  }
  if ("speechSynthesis" in window) speechSynthesis.onvoiceschanged = fillVoices;
  fillVoices();

  $("optWake").checked = settings.wake;
  $("optLight").checked = settings.light;
  $("optSpeak").checked = settings.speak;
  $("optName").value = settings.name;
  $("settingsBtn").onclick = () => openSheet("settingsSheet");
  $("saveGroq").onclick = () => { send({ type: "set_key", provider: "groq", key: $("optGroq").value.trim() }); $("optGroq").value = ""; };
  $("saveClaude").onclick = () => { send({ type: "set_key", provider: "anthropic", key: $("optClaude").value.trim() }); $("optClaude").value = ""; };
  $("settingsDone").onclick = closeSheets;
  $("optLight").onchange = (e) => {
    settings.light = e.target.checked; store.set("light", settings.light);
    document.documentElement.dataset.theme = settings.light ? "light" : "dark";
    document.querySelector('meta[name="theme-color"]').content = settings.light ? "#f5f2eb" : "#050505";
  };
  $("optSpeak").onchange = (e) => { settings.speak = e.target.checked; store.set("speak", settings.speak); if (!settings.speak) stopSpeaking(); };
  $("optVoice").onchange = (e) => { settings.voice = e.target.value; store.set("voice", settings.voice); speak("This is how I sound."); };
  $("optWake").onchange = (e) => {
    settings.wake = e.target.checked; store.set("wake", settings.wake);
    unlockAudio();
    if (settings.wake) startWake(); else pauseWake();
    setState(state);
  };
  $("optName").onchange = (e) => {
    settings.name = e.target.value.trim().toLowerCase(); store.set("name", settings.name);
    toast("Saved — reconnecting"); ws?.close();
  };
  if (settings.light) document.querySelector('meta[name="theme-color"]').content = "#f5f2eb";

  // ---------------------------------------------------------------- go
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
  addMsg("meta", isPhone ? "Tap the orb and speak" : "Tap the orb, press Space, or Ctrl+Alt+N from anywhere");
  connect();
  if (new URLSearchParams(location.search).get("listen")) {
    history.replaceState(null, "", location.pathname);
    setTimeout(() => { unlockAudio(); talk(); }, 1200); // opened by the hotkey
  }
})();
