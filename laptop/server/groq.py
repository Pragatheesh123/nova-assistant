"""Nova's free cloud brain: open models on Groq (free tier, no card needed).

Uses Groq's OpenAI-compatible chat API with Nova's tools. Web questions go to a Groq model with
built-in browser search. When a model hits its free-tier limit, Nova moves to the next one."""
import datetime
import json

import httpx

from . import profile, web
from .claude import EXTRA_TOOLS, PERSONA, _question
from .tools import TOOLS

URL = __import__("os").environ.get("NOVA_GROQ_URL", "https://api.groq.com/openai/v1/chat/completions")
# Tried in order; each model has its own free-tier limits.
MODELS = ["openai/gpt-oss-120b", "llama-3.3-70b-versatile", "openai/gpt-oss-20b", "llama-3.1-8b-instant"]
SEARCH_MODELS = ["openai/gpt-oss-20b", "openai/gpt-oss-120b"]  # these have Groq's built-in browser search

SEARCH_TOOL = {"name": "web_search", "description": "Search the web for current or factual information and get a summary with sources.",
               "input_schema": {"type": "object", "properties": {"query": {"type": "string"}}, "required": ["query"]}}
SKIP = {"web_lookup"}


class LimitReached(Exception):
    pass


_resting: dict[str, float] = {}  # model -> time it can be tried again after hitting a free-tier limit


def _tools(devices):
    out = []
    for t in TOOLS.values():
        if t.name in SKIP:
            continue
        fn = t.schema(devices)["function"]
        out.append({"type": "function", "function": fn})
    for t in EXTRA_TOOLS + [SEARCH_TOOL]:
        out.append({"type": "function", "function": {"name": t["name"], "description": t["description"],
                                                     "parameters": t["input_schema"]}})
    return out


class GroqBrain:
    def __init__(self, key):
        self.key = key
        self.history: list[dict] = []

    async def _chat(self, c, model, messages, tools=None, effort="low"):
        body = {"model": model, "messages": messages, "temperature": 0.6, "max_completion_tokens": 1024}
        if tools:
            body["tools"] = tools
            body["tool_choice"] = "auto"
        if model.startswith("openai/gpt-oss"):
            body["reasoning_effort"] = effort
        r = await c.post(URL, json=body, headers={"Authorization": f"Bearer {self.key}"})
        if r.status_code == 429 or (r.status_code == 413):
            raise LimitReached(r.text[:200])
        if r.status_code == 401:
            raise PermissionError("bad key")
        r.raise_for_status()
        return r.json()["choices"][0]["message"]

    async def search(self, c, query):
        """Answer a web question with Groq's built-in browser search; fall back to our own lookup."""
        for model in SEARCH_MODELS:
            try:
                r = await c.post(URL, headers={"Authorization": f"Bearer {self.key}"}, json={
                    "model": model, "max_completion_tokens": 1024, "reasoning_effort": "low",
                    "tools": [{"type": "browser_search"}], "tool_choice": "auto",
                    "messages": [{"role": "user", "content":
                                  f"Search the web and give the key facts, with the source sites, for: {query}"}]})
                if r.status_code == 200:
                    return r.json()["choices"][0]["message"].get("content") or "No results."
            except httpx.HTTPError:
                pass
        try:
            digest, _ = await web.lookup(query)
            return digest
        except Exception:
            return "Web search isn't available right now. Answer from what you know and say so."

    async def think(self, text, origin, devices, execute, cues=""):
        now = datetime.datetime.now().strftime("%A %d %B %Y, %H:%M")
        line = f"[{now} · speaking from their {origin.name}" + (f" · voice: {cues}" if cues else "") + f"]\n{text}"
        system = PERSONA + (f"\n\n{profile.describe()}" if profile.describe() else "") + \
            f"\n\nConnected devices: {', '.join(devices)}."
        messages = [{"role": "system", "content": system}] + self.history + [{"role": "user", "content": line}]
        tools = _tools(devices)
        effort = "medium" if _question(text) else "low"
        import time
        models = [m for m in MODELS if _resting.get(m, 0) < time.time()] or MODELS
        model_i = 0
        reply = ""
        async with httpx.AsyncClient(timeout=60) as c:
            steps = 0
            while steps < 12:
                try:
                    msg = await self._chat(c, models[model_i], messages, tools, effort)
                except LimitReached:
                    _resting[models[model_i]] = time.time() + 60  # rest it for a minute
                    model_i += 1  # free-tier limit on this model: try the next one
                    if model_i >= len(models):
                        return None  # all used up: caller falls back to the local brain
                    continue
                except PermissionError:
                    return "My Groq API key isn't working. Check it in settings."
                except httpx.HTTPError as e:
                    print("Groq error:", e)
                    return None
                steps += 1
                calls = msg.get("tool_calls") or []
                messages.append({k: v for k, v in msg.items() if k in ("role", "content", "tool_calls")})
                if not calls:
                    reply = (msg.get("content") or "").strip()
                    break
                for call in calls:
                    fn = call["function"]
                    try:
                        args = json.loads(fn.get("arguments") or "{}")
                    except json.JSONDecodeError:
                        args = {}
                    out = await self._run(c, fn["name"], args, execute)
                    messages.append({"role": "tool", "tool_call_id": call["id"], "content": str(out)[:6000]})
            else:
                reply = "That took more steps than I allow myself, so I've paused there."
        reply = reply or "Done."
        self.history += [{"role": "user", "content": line}, {"role": "assistant", "content": reply}]
        self.history = self.history[-12:]
        return reply

    async def _run(self, c, name, args, execute):
        if name == "web_search":
            return await self.search(c, args.get("query", ""))
        if name == "remember":
            return profile.remember(args.get("fact", ""))
        if name == "forget":
            return profile.forget(args.get("about", ""))
        if name == "save_routine":
            return profile.save_routine(args.get("name", ""), args.get("trigger", ""), args.get("steps", []))
        if name == "delete_routine":
            return profile.delete_routine(args.get("name", ""))
        if name == "run_routine":
            r = profile.get_routine(args.get("name", ""))
            if not r:
                return f"No routine called {args.get('name')}."
            return "Carry out these steps now, in order, using your tools:\n" + \
                "\n".join(f"{i}. {s}" for i, s in enumerate(r["steps"], 1))
        return await execute(name, args)
