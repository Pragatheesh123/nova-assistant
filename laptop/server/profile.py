"""What Nova knows about you (facts you've shared) and your routines (multi-step workflows).
Stored only on this laptop in data/profile.json."""
import json
import threading

from . import config

PATH = config.DATA / "profile.json"
_lock = threading.Lock()


def _load():
    try:
        return json.loads(PATH.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError):
        return {"facts": [], "routines": []}


def _save(p):
    PATH.write_text(json.dumps(p, indent=2, ensure_ascii=False), encoding="utf-8")


def facts():
    return _load()["facts"]


def routines():
    return _load()["routines"]


def remember(fact: str):
    fact = fact.strip()
    with _lock:
        p = _load()
        if fact and fact not in p["facts"]:
            p["facts"] = (p["facts"] + [fact])[-60:]
            _save(p)
    return "Remembered."


def forget(about: str):
    with _lock:
        p = _load()
        before = len(p["facts"])
        p["facts"] = [f for f in p["facts"] if about.lower() not in f.lower()]
        _save(p)
    return f"Forgot {before - len(p['facts'])} thing(s)."


def save_routine(name: str, trigger: str, steps: list[str]):
    name = name.strip()
    with _lock:
        p = _load()
        p["routines"] = [r for r in p["routines"] if r["name"].lower() != name.lower()]
        p["routines"].append({"name": name, "trigger": trigger.strip(), "steps": [s.strip() for s in steps if s.strip()]})
        _save(p)
    return f"Routine '{name}' saved with {len(steps)} steps. Say \"{trigger}\" to run it."


def delete_routine(name: str):
    with _lock:
        p = _load()
        n = len(p["routines"])
        p["routines"] = [r for r in p["routines"] if r["name"].lower() != name.lower()]
        _save(p)
    return "Deleted." if len(p["routines"]) < n else f"No routine called {name}."


def get_routine(name: str):
    return next((r for r in routines() if r["name"].lower() == name.lower()), None)


def describe():
    """Text block for the system prompt."""
    f = facts()
    r = routines()
    out = []
    if f:
        out.append("What you know about the user:\n" + "\n".join(f"- {x}" for x in f))
    if r:
        out.append("The user's routines (run with run_routine when they say the trigger or ask):\n" +
                   "\n".join(f"- {x['name']} (trigger: \"{x['trigger']}\"): " + " → ".join(x["steps"]) for x in r))
    return "\n\n".join(out)
