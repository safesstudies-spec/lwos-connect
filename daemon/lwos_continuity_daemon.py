#!/usr/bin/env python3
"""
lwos_continuity_daemon.py

The LWOS Desktop (CachyOS) side of LWOS Connect. Speaks the exact same
newline-delimited JSON protocol as the Android app's ContinuityServer/
ContinuityClient (see app/src/main/java/com/lwos/connect/transport/ and
model/ContinuityPacket.kt), plus a second, separate raw-binary protocol
for file transfer.

What's implemented here:
  - Continue On handoffs (prints what it received; see APP_HANDLERS to
    wire up a real app)
  - Mutual pairing (see handle_pair_request / handle_pair_response — this
    mirrors a real bug fix made on the Android side: pairing only works
    both ways if BOTH sides proactively connect back out and send their
    own identity, since every message here is a short-lived
    connect/send/close, not a request-that-waits-for-a-reply)
  - Universal Clipboard: applies an incoming CLIPBOARD_SYNC to the Linux
    clipboard via xclip/xsel
  - Notification mirroring: prints incoming NOTIFICATION_MIRROR messages
  - Fast file transfer: a dedicated server on its own port that saves
    incoming files to ~/LWOSReceived/

What's still a stub / known simplification, worth knowing before
assuming something is broken instead of just not built yet:
  - No mDNS advertising on the LAN yet (phones currently need "Add
    device manually" with this machine's IP — see the Android app).
  - Trust is a plain in-memory set — resets every time this script
    restarts, and this machine's own "device id" is just its hostname,
    which is a fine simplification on a home LAN but isn't a real
    cryptographic identity.
  - Clipboard/notifications are one-directional (phone -> PC only for
    now); PC -> phone is a natural next step using the same send_message
    helper below.
  - Nothing here sends a file FROM the PC; FileTransferClient-equivalent
    code doesn't exist yet on this side, only the receiving server.

Run it with:
    python3 lwos_continuity_daemon.py
"""

import json
import os
import platform
import shutil
import socket
import socketserver
import subprocess
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Optional

CONTINUITY_PORT = 59213   # must match ContinuityServer.DEFAULT_PORT on the Android side
FILE_TRANSFER_PORT = 59214  # must match FileTransferServer.DEFAULT_PORT on the Android side
DOWNLOAD_DIR = Path.home() / "LWOSReceived"

# This machine's own identity on the continuity protocol. Using the
# hostname is a deliberate simplification — good enough to be stable
# across restarts on a personal LAN, not a real security identity.
LOCAL_DEVICE_ID = platform.node() or "lwos-desktop"
LOCAL_DEVICE_NAME = platform.node() or "LWOS Desktop"
LOCAL_DEVICE_TYPE = "PC"


# --- Trust store (in-memory stand-in for TrustStore.kt) ---------------------

@dataclass
class TrustStore:
    trusted_device_ids: set = field(default_factory=set)

    def is_trusted(self, device_id: str) -> bool:
        return device_id in self.trusted_device_ids

    def trust(self, device_id: str) -> None:
        self.trusted_device_ids.add(device_id)


trust_store = TrustStore()
known_device_names: Dict[str, str] = {}  # deviceId -> last-seen display name, for nicer logging


# --- Outbound messages ---------------------------------------------------

def send_message(host: str, port: int, message: dict, timeout: float = 4.0) -> bool:
    """
    Connects, writes one JSON line, closes — mirrors ContinuityClient.send
    on the Android side exactly, including the "one short-lived
    connection per message" design (see that file for why).
    """
    try:
        with socket.create_connection((host, port), timeout=timeout) as sock:
            sock.sendall((json.dumps(message) + "\n").encode("utf-8"))
        return True
    except OSError as e:
        print(f"[warn] Could not reach {host}:{port}: {e}")
        return False


# --- App handlers (Continue On) -------------------------------------------
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


# --- Pairing (mutual) ------------------------------------------------------

def handle_pair_request(message: dict, peer_host: str) -> None:
    device_id = message.get("deviceId", "")
    device_name = message.get("deviceName", device_id)
    code = message.get("pairingCode", "")

    # Auto-accept: the pairing *code* shown on both screens is the only
    # human verification step in this first pass — see README for the
    # "real confirmation dialog" item on the roadmap.
    print(f"[pairing] {device_name} ({device_id}) wants to pair with code {code} — auto-accepting")
    trust_store.trust(device_id)
    known_device_names[device_id] = device_name

    # Pairing only becomes MUTUAL if we proactively connect back out and
    # send our own identity — the requester already closed the connection
    # it used to reach us (every message here is connect/send/close, not
    # a request that waits for a reply on the same socket). Skipping this
    # step is exactly the bug that made pairing look broken on the
    # Android side: one device would trust the other, but not vice versa.
    response = {
        "type": "PAIR_RESPONSE",
        "deviceId": LOCAL_DEVICE_ID,
        "deviceName": LOCAL_DEVICE_NAME,
        "deviceType": LOCAL_DEVICE_TYPE,
        "accepted": True,
    }
    send_message(peer_host, CONTINUITY_PORT, response)


def handle_pair_response(message: dict) -> None:
    if not message.get("accepted"):
        return
    device_id = message.get("deviceId", "")
    device_name = message.get("deviceName", device_id)
    trust_store.trust(device_id)
    known_device_names[device_id] = device_name
    print(f"[pairing] {device_name} ({device_id}) accepted pairing — now mutually trusted")


# --- Universal Clipboard ---------------------------------------------------

def set_system_clipboard(text: str) -> None:
    """
    Writes `text` to the Linux clipboard via xclip (preferred) or xsel,
    whichever is installed. This needs a running X11 session (or XWayland)
    — it's a no-op with a warning on a headless box or pure-Wayland
    session without a clipboard bridge, which is worth knowing before
    assuming clipboard sync "isn't working".
    """
    if shutil.which("xclip"):
        cmd = ["xclip", "-selection", "clipboard"]
    elif shutil.which("xsel"):
        cmd = ["xsel", "--clipboard", "--input"]
    else:
        print("[clipboard] Neither xclip nor xsel is installed — run: sudo apt install xclip")
        return
    try:
        subprocess.run(cmd, input=text.encode("utf-8"), check=True, timeout=3)
    except Exception as e:
        print(f"[clipboard] Could not set system clipboard: {e}")


def handle_clipboard_sync(message: dict) -> None:
    device_id = message.get("deviceId", "")
    if not trust_store.is_trusted(device_id):
        print(f"[ignored] Clipboard sync from untrusted device {device_id}")
        return
    text = message.get("clipboardText", "")
    from_name = known_device_names.get(device_id, device_id)
    preview = text if len(text) <= 60 else text[:57] + "..."
    print(f"[clipboard] From {from_name}: {preview!r}")
    set_system_clipboard(text)


# --- Notification mirroring -------------------------------------------------

def handle_notification_mirror(message: dict) -> None:
    device_id = message.get("deviceId", "")
    if not trust_store.is_trusted(device_id):
        return
    from_name = known_device_names.get(device_id, device_id)
    app_label = message.get("notifAppLabel", "Unknown app")
    title = message.get("notifTitle") or ""
    text = message.get("notifText") or ""
    summary = " — ".join(part for part in (title, text) if part)
    print(f"[notification] ({from_name}) {app_label}: {summary}")


# --- Continuity control channel (JSON-over-TCP) -----------------------------

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
                handle_pair_request(message, peer)
            elif msg_type == "PAIR_RESPONSE":
                handle_pair_response(message)
            elif msg_type == "CLIPBOARD_SYNC":
                handle_clipboard_sync(message)
            elif msg_type == "NOTIFICATION_MIRROR":
                handle_notification_mirror(message)
            elif msg_type == "HEARTBEAT":
                pass  # nothing to do yet
            else:
                print(f"[info] Unhandled message type from {peer}: {msg_type}")


class ThreadingTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


# --- File transfer (dedicated raw-binary protocol, separate port) ----------
#
# Deliberately NOT part of the JSON control channel above: switching from
# a text header to a raw binary payload mid-connection is a real
# correctness trap if you read the header with a buffered line reader —
# it reads ahead looking for the newline and can silently consume bytes
# that belong to the file right after it. read_line_raw below reads the
# header one byte at a time specifically to avoid that. See
# FileTransferServer.kt on the Android side for the same protocol and the
# same reasoning.

def read_line_raw(conn: socket.socket) -> Optional[str]:
    chunks = []
    while True:
        b = conn.recv(1)
        if not b:
            return None if not chunks else b"".join(chunks).decode("utf-8")
        if b == b"\n":
            return b"".join(chunks).decode("utf-8")
        chunks.append(b)


def handle_file_transfer_connection(conn: socket.socket, addr) -> None:
    try:
        header_line = read_line_raw(conn)
        if not header_line:
            return
        header = json.loads(header_line)
        file_name = header["fileName"]
        file_size = int(header["fileSize"])
        sender_name = header.get("senderDeviceName", addr[0])

        DOWNLOAD_DIR.mkdir(parents=True, exist_ok=True)
        dest_path = DOWNLOAD_DIR / file_name
        # Don't clobber an existing file with the same name.
        counter = 1
        stem, suffix = os.path.splitext(file_name)
        while dest_path.exists():
            dest_path = DOWNLOAD_DIR / f"{stem} ({counter}){suffix}"
            counter += 1

        remaining = file_size
        with open(dest_path, "wb") as f:
            while remaining > 0:
                chunk = conn.recv(min(32768, remaining))
                if not chunk:
                    raise ConnectionError(f"connection closed early ({remaining} bytes missing)")
                f.write(chunk)
                remaining -= len(chunk)

        print(f"[file] Received {file_name} ({file_size} bytes) from {sender_name} -> {dest_path}")
    except Exception as e:
        print(f"[file] Transfer failed: {e}")
    finally:
        conn.close()


def run_file_transfer_server(port: int = FILE_TRANSFER_PORT) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", port))
        server.listen(5)
        print(f"LWOS file transfer server listening on 0.0.0.0:{port}")
        while True:
            conn, addr = server.accept()
            threading.Thread(target=handle_file_transfer_connection, args=(conn, addr), daemon=True).start()


# --- Entry point -------------------------------------------------------------

def run(port: int = CONTINUITY_PORT) -> None:
    threading.Thread(target=run_file_transfer_server, daemon=True).start()

    with ThreadingTCPServer(("0.0.0.0", port), ContinuityHandler) as server:
        print(f"LWOS continuity daemon ({LOCAL_DEVICE_NAME}) listening on 0.0.0.0:{port}")
        print("Waiting for handoffs, pairing, clipboard syncs, and notifications from paired LWOS Mobile devices...")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nShutting down.")


if __name__ == "__main__":
    run()
