"""Device registry + "where should this happen?" logic.

Rules, in order:
  1. If the user named a device ("...on my phone"), use it.
  2. host tools always run on the laptop running this server.
  3. client tools that NEED a capability (calls need telephony) go to a device that has it,
     preferring the one you spoke from.
  4. Otherwise the device you spoke from does it. For "either" tools spoken from the laptop's
     own browser, the server opens it directly (no popup blockers).
"""
import itertools
from dataclasses import dataclass, field

from fastapi import WebSocket

HOST_DEVICE = "laptop"


@dataclass
class Device:
    id: str
    name: str
    kind: str  # "phone" | "desktop"
    ws: WebSocket
    local: bool  # the browser is running on the same machine as the server
    caps: set = field(default_factory=set)

    def info(self):
        return {"id": self.id, "name": self.name, "kind": self.kind, "local": self.local}


class Router:
    def __init__(self):
        self.devices: dict[str, Device] = {}
        self._ids = itertools.count(1)

    def add(self, ws, name, kind, caps, local):
        dev_id = f"d{next(self._ids)}"
        # friendly unique names: "phone", "phone 2", ...
        base = name or ("laptop" if local else kind)
        taken = {d.name for d in self.devices.values()}
        nm = base if base not in taken else next(f"{base} {i}" for i in itertools.count(2) if f"{base} {i}" not in taken)
        dev = Device(dev_id, nm, kind, ws, local, set(caps))
        self.devices[dev_id] = dev
        return dev

    def remove(self, dev_id):
        self.devices.pop(dev_id, None)

    def names(self):
        return sorted({HOST_DEVICE, *(d.name for d in self.devices.values())})

    def find(self, spoken):
        """Map a spoken device word to a device. Returns HOST_DEVICE, a Device, or None."""
        if not spoken or spoken == "auto":
            return None
        s = spoken.lower()
        for d in self.devices.values():
            if d.name.lower() == s:
                return d
        if s in ("laptop", "computer", "pc", "mac", "desktop", "here on laptop"):
            return HOST_DEVICE
        if s in ("phone", "mobile", "cell"):
            return next((d for d in self.devices.values() if d.kind == "phone"), "missing:phone")
        return None

    def route(self, tool, origin: Device, device_hint=None):
        """Returns ("host", None) | ("client", Device) | ("error", message)."""
        chosen = self.find(device_hint)
        if isinstance(chosen, str) and chosen.startswith("missing:"):
            return "error", "Your phone isn't connected to me right now. Open Nova on it first."

        if tool.where == "host":
            if isinstance(chosen, Device) and not chosen.local:
                return "error", f"I can only do that on the laptop, not the {chosen.name}."
            return "host", None

        if chosen == HOST_DEVICE:
            if tool.where == "either":
                return "host", None
            chosen = next((d for d in self.devices.values() if d.local), None)
            if chosen is None:
                return "error", "Open Nova in the laptop's browser so I can do that there."

        if isinstance(chosen, Device):
            if tool.needs and tool.needs not in chosen.caps:
                return "error", f"The {chosen.name} can't do that."
            return "client", chosen

        # auto
        if tool.needs:
            if tool.needs in origin.caps:
                return "client", origin
            capable = next((d for d in self.devices.values() if tool.needs in d.caps), None)
            if capable:
                return "client", capable
            return "error", "None of your connected devices can do that. Open Nova on your phone."

        if tool.where == "either" and origin.local:
            return "host", None
        return "client", origin
