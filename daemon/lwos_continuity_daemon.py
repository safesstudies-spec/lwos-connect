#!/usr/bin/env python3
"""
lwos_continuity_daemon.py

Minimal stub for the LWOS Desktop (CachyOS) side of "Continue On".

This is deliberately small: it speaks the exact same newline-delimited
JSON protocol as the Android app's ContinuityServer/ContinuityClient
(see app/src/main/java/com/lwos/connect/transport/ and model/
ContinuityPacket.kt), so the two sides can already talk to each other
today. It also now advertises itself over mDNS (via the `zeroconf`
package) on the same `_lwosconnect._tcp.` service type the Android
app's LanDiscoveryManager looks for, so the phone can find this
machine automatically instead of needing a typed-in IP.

What it still does NOT do, and what later passes should add:

  - Real trust/pairing persistence (currently an in-memory set, reset
    every time you restart this script).
  - Actually resuming an app's state from stateBlob — right now it just
    prints what it received. Once you decide which first app on LWOS
    Desktop should support Continue On (e.g. a browser, a notes app),
    add a handler keyed by appId.
  - Bluetooth discovery/pairing to match the Android side's fallback
    path (BlueZ via `bluezero` or D-Bus).

Run it with:
    pip3 install --user zeroconf   # one-time
    python3 lwos_continuity_daemon.py

Matches ContinuityServer.DEFAULT_PORT on the Android side (59213).
"""

import json
import socket
import socketserver
import uuid
from dataclasses import dataclass, field

from zeroconf import ServiceInfo, Zeroconf

DEFAULT_PORT = 59213
SERVICE_TYPE = "_lwosconnect._tcp.local."


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


def get_local_ip() -> str:
    """
    Finds this machine's LAN IP address (the one other devices on the
    same Wi-Fi/hotspot would use to reach it) without needing any
    actual internet connectivity — connecting a UDP socket doesn't
    send any packets, it just asks the OS to pick the right outbound
    interface, which we then read back.
    """
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def advertise(local_ip: str, device_id: str, device_name: str, port: int) -> tuple[Zeroconf, ServiceInfo]:
    """
    Broadcasts this machine over mDNS so the Android app's
    LanDiscoveryManager (NsdManager-based) can find it automatically,
    the same way it finds other LWOS Mobile devices.

    The service name format ("LWOS-<name>-<id>") and the "deviceId"/
    "deviceType" TXT attributes have to match what the Android side
    expects to parse in LanDiscoveryManager.kt's onServiceResolved.
    """
    service_name = f"LWOS-{device_name}-{device_id}.{SERVICE_TYPE}"
    info = ServiceInfo(
        type_=SERVICE_TYPE,
        name=service_name,
        addresses=[socket.inet_aton(local_ip)],
        port=port,
        properties={
            "deviceId": device_id,
            "deviceType": "PC",
        },
    )
    zc = Zeroconf()
    zc.register_service(info)
    print(f"Advertising as '{service_name}' at {local_ip}:{port}")
    return zc, info


def run(port: int = DEFAULT_PORT) -> None:
    local_ip = get_local_ip()
    device_id = f"lwos-desktop-{uuid.uuid5(uuid.NAMESPACE_DNS, socket.gethostname()).hex[:12]}"
    device_name = socket.gethostname()

    zc, info = advertise(local_ip, device_id, device_name, port)

    try:
        with ThreadingTCPServer(("0.0.0.0", port), ContinuityHandler) as server:
            print(f"LWOS continuity daemon listening on 0.0.0.0:{port}")
            print("Waiting for handoffs from paired LWOS Mobile devices...")
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                print("\nShutting down.")
    finally:
        zc.unregister_service(info)
        zc.close()


if __name__ == "__main__":
    run()
