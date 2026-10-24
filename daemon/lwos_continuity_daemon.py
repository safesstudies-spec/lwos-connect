#!/usr/bin/env python3
"""
lwos_continuity_daemon.py

Minimal stub for the LWOS Desktop (CachyOS) side of "Continue On".

This is deliberately small: it speaks the exact same newline-delimited
JSON protocol as the Android app's ContinuityServer/ContinuityClient
(see app/src/main/java/com/lwos/connect/transport/ and model/
ContinuityPacket.kt), so the two sides can already talk to each other
today. What it does NOT do yet, and what later passes should add:

  - mDNS advertising on the LAN (so phones discover the PC automatically
    instead of needing a typed-in IP). On Linux this is normally done via
    Avahi (`python-zeroconf` or shelling out to `avahi-publish-service`).
  - Real trust/pairing persistence (currently an in-memory set).
  - Actually resuming an app's state from stateBlob — right now it just
    prints what it received. Once you decide which first app on LWOS
    Desktop should support Continue On (e.g. a browser, a notes app),
    add a handler keyed by appId.
  - Bluetooth discovery/pairing to match the Android side's fallback
    path (BlueZ via `bluezero` or D-Bus).

Run it with:
    python3 lwos_continuity_daemon.py

Matches ContinuityServer.DEFAULT_PORT on the Android side (59213).
"""

import json
import socket
import socketserver
import threading
import time
from dataclasses import dataclass, field

DEFAULT_PORT = 59213


# --- Trust store (in-memory stand-in for TrustStore.kt) ---------------------

@dataclass
class TrustStore:
    trusted_device_ids: set = field(default_factory=set)

    def is_trusted(self, device_id: str) -> bool:
        return device_id in self.trusted_device_ids

    def trust(self, device_id: str) -> None:
        self.trusted_device_ids.add(device_id)


trust_store = TrustStore()


# --- App handlers -------------------------------------------------------
# Register a function per appId that knows how to resume from stateBlob.
# Anything with no handler just gets logged, which is enough to verify
# the phone <-> PC link end-to-end before wiring up a real app.

def handle_demo_notes(state_blob: str) -> None:
    try:
        data = json.loads(state_blob)
    except json.JSONDecodeError:
        data = {"raw": state_blob}
    print(f"  -> Would open LWOS Notes (desktop) with: {data}")


APP_HANDLERS = {
    "com.lwos.demo.notes": handle_demo_notes,
}


def handle_handoff(packet: dict) -> None:
    source_id = packet.get("sourceDeviceId", "unknown")
    if not trust_store.is_trusted(source_id):
        print(f"[ignored] Handoff from untrusted device {source_id}")
        return

    app_id = packet.get("appId", "")
    app_name = packet.get("appDisplayName", app_id)
    print(f"[handoff] {app_name} from {packet.get('sourceDeviceName', source_id)}")

    handler = APP_HANDLERS.get(app_id)
    if handler:
        handler(packet.get("stateBlob", ""))
    else:
        print(f"  -> No handler registered for appId={app_id}; stateBlob={packet.get('stateBlob')}")


def handle_pair_request(message: dict, send_response) -> None:
    device_id = message.get("deviceId", "")
    device_name = message.get("deviceName", device_id)
    code = message.get("pairingCode", "")

    # Matches the Android side's current "auto-accept, the code shown on
    # both screens IS the human verification step" approach. Swap this
    # for a real prompt (desktop notification + accept/deny) once this
    # is wired into an actual LWOS Desktop UI.
    print(f"[pairing] {device_name} ({device_id}) wants to pair with code {code} — auto-accepting")
    trust_store.trust(device_id)
    send_response({"type": "PAIR_RESPONSE", "deviceId": device_id, "accepted": True})


class ContinuityHandler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        peer = self.client_address[0]
        for raw_line in self.rfile:
            line = raw_line.decode("utf-8").strip()
            if not line:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                print(f"[warn] Dropped malformed message from {peer}: {line!r}")
                continue

            msg_type = message.get("type")
            if msg_type == "HANDOFF":
                packet = message.get("packet")
                if packet:
                    handle_handoff(packet)
            elif msg_type == "PAIR_REQUEST":
                def respond(payload: dict) -> None:
                    self.wfile.write((json.dumps(payload) + "\n").encode("utf-8"))

                handle_pair_request(message, respond)
            elif msg_type == "HEARTBEAT":
                pass  # nothing to do yet
            else:
                print(f"[info] Unhandled message type from {peer}: {msg_type}")


class ThreadingTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


def run(port: int = DEFAULT_PORT) -> None:
    with ThreadingTCPServer(("0.0.0.0", port), ContinuityHandler) as server:
        print(f"LWOS continuity daemon listening on 0.0.0.0:{port}")
        print("Waiting for handoffs from paired LWOS Mobile devices...")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down.")


if __name__ == "__main__":
    run()
