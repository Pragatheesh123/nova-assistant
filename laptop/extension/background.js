// Nova browser bridge. Connects ONLY to the Nova app on this laptop and runs its tab commands.
const NOVA = "ws://127.0.0.1:8766/ext";
let ws = null;
let pinger = null;

function setStatus(on) {
  chrome.action.setBadgeText({ text: on ? "" : "off" });
  chrome.action.setBadgeBackgroundColor({ color: "#444" });
  chrome.storage.session.set({ connected: on });
}

function connect() {
  if (ws && ws.readyState <= 1) return;
  try { ws = new WebSocket(NOVA); } catch { return; }
  ws.onopen = () => {
    setStatus(true);
    clearInterval(pinger);
    // keeps the service worker alive while connected
    pinger = setInterval(() => ws && ws.readyState === 1 && ws.send(JSON.stringify({ type: "ping" })), 20000);
  };
  ws.onmessage = async (e) => {
    const m = JSON.parse(e.data);
    try {
      const fn = COMMANDS[m.cmd];
      if (!fn) throw new Error("unknown command " + m.cmd);
      const result = await fn(m.args || {});
      ws.send(JSON.stringify({ id: m.id, ok: true, result }));
    } catch (err) {
      ws.send(JSON.stringify({ id: m.id, ok: false, error: String((err && err.message) || err) }));
    }
  };
  ws.onclose = () => { setStatus(false); clearInterval(pinger); ws = null; setTimeout(connect, 3000); };
  ws.onerror = () => {};
}

chrome.alarms.create("nova-keepalive", { periodInMinutes: 0.5 });
chrome.alarms.onAlarm.addListener(connect);
chrome.runtime.onStartup.addListener(connect);
chrome.runtime.onInstalled.addListener(connect);
connect();

// ------------------------------------------------------------------ helpers

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function activeTab() {
  const [t] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  if (!t) throw new Error("no active tab");
  return t;
}

async function waitLoad(tabId, timeout = 15000) {
  const end = Date.now() + timeout;
  await sleep(300);
  while (Date.now() < end) {
    const t = await chrome.tabs.get(tabId);
    if (t.status === "complete") break;
    await sleep(250);
  }
  await sleep(900); // let modern sites render
}

async function run(tabId, func, args = []) {
  try {
    const [res] = await chrome.scripting.executeScript({ target: { tabId }, func, args });
    return res ? res.result : undefined;
  } catch (e) {
    throw new Error("Can't access this page (browser pages and the Web Store are off-limits).");
  }
}

async function snapshot(tabId) {
  return run(tabId, novaSnapshot);
}

async function findTab(query) {
  const tabs = await chrome.tabs.query({});
  if (!query || query === "current") return activeTab();
  const n = parseInt(query, 10);
  if (!isNaN(n) && String(n) === String(query).trim()) {
    if (tabs[n - 1]) return tabs[n - 1];
    throw new Error("no tab number " + n);
  }
  const q = query.toLowerCase();
  const t = tabs.find((t) => (t.title || "").toLowerCase().includes(q)) ||
            tabs.find((t) => (t.url || "").toLowerCase().includes(q));
  if (!t) throw new Error("no tab matching " + query);
  return t;
}

// ------------------------------------------------------------------ commands

const COMMANDS = {
  async tabs() {
    const [cur] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    const tabs = await chrome.tabs.query({});
    return tabs.map((t) => ({ id: t.id, title: t.title || "", url: t.url || "", active: !!cur && t.id === cur.id }));
  },
  async open({ url }) {
    const t = await chrome.tabs.create({ url, active: true });
    await chrome.windows.update(t.windowId, { focused: true });
    await waitLoad(t.id);
    return snapshot(t.id);
  },
  async navigate({ url }) {
    const t = await activeTab();
    await chrome.tabs.update(t.id, { url });
    await waitLoad(t.id);
    return snapshot(t.id);
  },
  async switch({ query }) {
    const t = await findTab(query);
    await chrome.tabs.update(t.id, { active: true });
    await chrome.windows.update(t.windowId, { focused: true });
    return { title: t.title };
  },
  async close({ query }) {
    const t = await findTab(query);
    await chrome.tabs.remove(t.id);
    return { title: t.title };
  },
  async read() {
    const t = await activeTab();
    return snapshot(t.id);
  },
  async click({ element }) {
    const t = await activeTab();
    const ok = await run(t.id, novaClick, [element]);
    if (!ok) throw new Error("that element is gone; read the page again");
    await sleep(600);
    await waitLoad(t.id, 8000);
    return snapshot(t.id);
  },
  async type({ element, text, submit }) {
    const t = await activeTab();
    const ok = await run(t.id, novaType, [element, text, !!submit]);
    if (!ok) throw new Error("that input is gone; read the page again");
    await sleep(submit ? 800 : 200);
    if (submit) await waitLoad(t.id, 8000);
    return snapshot(t.id);
  },
  async scroll({ direction }) {
    const t = await activeTab();
    await run(t.id, (d) => window.scrollBy({ top: (d === "up" ? -1 : 1) * innerHeight * 0.8, behavior: "smooth" }), [direction]);
    await sleep(600);
    return snapshot(t.id);
  },
  async back() {
    const t = await activeTab();
    await chrome.tabs.goBack(t.id);
    await waitLoad(t.id, 8000);
    return snapshot(t.id);
  },
  async find_link({ pattern }) {
    const t = await activeTab();
    const end = Date.now() + 7000;
    while (Date.now() < end) {
      const href = await run(t.id, (p) => {
        const rx = new RegExp(p);
        for (const a of document.querySelectorAll("a[href]")) {
          const r = a.getBoundingClientRect();
          if (r.width > 0 && r.height > 0 && rx.test(a.href)) return a.href;
        }
        return null;
      }, [pattern]);
      if (href) return href;
      await sleep(500);
    }
    return null;
  },
};

// ------------------------------------------------------------------ in-page functions (must be self-contained)

function novaSnapshot() {
  document.querySelectorAll("[data-nova-id]").forEach((e) => e.removeAttribute("data-nova-id"));
  const sel = 'a[href], button, input:not([type=hidden]), textarea, select, [role=button], [role=link], [role=tab], ' +
              '[role=menuitem], [role=searchbox], [role=textbox], [contenteditable=true]';
  const out = [];
  let i = 0;
  for (const el of document.querySelectorAll(sel)) {
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2 || r.bottom < -300 || r.top > innerHeight * 2.5) continue;
    const st = getComputedStyle(el);
    if (st.visibility === "hidden" || st.display === "none") continue;
    const tag = el.tagName.toLowerCase();
    const type = (el.getAttribute("type") || "").toLowerCase();
    const ac = (el.getAttribute("autocomplete") || "").toLowerCase();
    const sensitive = type === "password" ? "password"
      : (/cc-/.test(ac) || /card.?num|cvv|cvc/i.test((el.name || "") + (el.id || ""))) ? "payment card" : "";
    const kind = tag === "a" ? "link" : tag === "input" ? (type || "text") + " input" : tag === "textarea" ? "text box"
      : tag === "select" ? "dropdown" : (el.getAttribute("role") || tag);
    const isField = tag === "input" || tag === "textarea";
    // Never read what's typed in fields; describe them by their label/placeholder only.
    let label = (el.getAttribute("aria-label") || (isField ? "" : el.innerText) || el.getAttribute("placeholder") ||
                 el.getAttribute("title") || el.getAttribute("name") || "").trim().replace(/\s+/g, " ").slice(0, 100);
    if (!label && !isField) continue;
    i++;
    el.setAttribute("data-nova-id", String(i));
    out.push({ i, kind, label, href: tag === "a" ? el.href : "", sensitive });
    if (i >= 120) break;
  }
  const main = document.querySelector("main, article, [role=main]") || document.body;
  const text = ((main && main.innerText) || "").replace(/\n{2,}/g, "\n").slice(0, 4000);
  return { title: document.title, url: location.href, text, elements: out };
}

function novaClick(n) {
  const el = document.querySelector(`[data-nova-id="${n}"]`);
  if (!el) return false;
  el.scrollIntoView({ block: "center" });
  el.click();
  return true;
}

function novaType(n, text, submit) {
  const el = document.querySelector(`[data-nova-id="${n}"]`);
  if (!el) return false;
  el.scrollIntoView({ block: "center" });
  el.focus();
  if (el.isContentEditable) {
    el.textContent = text;
    el.dispatchEvent(new InputEvent("input", { bubbles: true }));
  } else {
    const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, "value").set.call(el, text);
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }
  if (submit) {
    if (el.form) {
      el.form.requestSubmit ? el.form.requestSubmit() : el.form.submit();
    } else {
      const o = { key: "Enter", code: "Enter", keyCode: 13, which: 13, bubbles: true };
      el.dispatchEvent(new KeyboardEvent("keydown", o));
      el.dispatchEvent(new KeyboardEvent("keypress", o));
      el.dispatchEvent(new KeyboardEvent("keyup", o));
    }
  }
  return true;
}
