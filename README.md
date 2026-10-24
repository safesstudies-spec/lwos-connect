# LWOS Connect

The cross-device companion app for LightningWeb OS: phone/tablet ↔ PC
continuity. This first pass implements **Continue On** end-to-end —
discovery, pairing/trust, and handing off "what you're doing" from one
device to another — as the foundation the other features (Phone Call
Movement, Stream Apps, Messaging, Second Screen, Fast File Sharing,
File Management, Notification Sharing, DeX Monitor, and eventually
Local AI Streaming) will build on top of.

Per your answers: this ships as a **separate app** for now (a future
pass folds the always-on parts into the LWOS Mobile launcher/OS itself
— not today), written in **Kotlin**, talking over **both local Wi-Fi/LAN
and Bluetooth**.

## How it fits together

```
 LWOS Mobile (phone/tablet)              LWOS Desktop (CachyOS PC)
 ┌─────────────────────────┐             ┌─────────────────────────┐
 │  MainActivity (UI)       │             │  lwos_continuity_daemon  │
 │  ContinuityService        │◄── LAN ───►│  (Python, stub for now)  │
 │   ├─ LanDiscoveryManager  │   (mDNS)    │   - same JSON protocol   │
 │   ├─ BluetoothDiscovery   │◄─ Bluetooth│   - in-memory trust store │
 │   ├─ ContinuityServer     │            │   - per-appId handlers    │
 │   ├─ ContinuityClient     │            └─────────────────────────┘
 │   └─ TrustStore           │
 └─────────────────────────┘
```

Both sides speak the exact same wire format: one line of JSON per
message, sent over a plain TCP socket (port `59213` by default).
That's deliberate — it means you can test the Android app against the
Python stub today, before LWOS Desktop has its own real continuity
daemon, and later swap either side's internals without touching the
protocol.

### Why LAN *and* Bluetooth

- **LAN (NSD/mDNS)** — like KDE Connect/AirPlay. Used for anything with
  real payload: the actual handoff packet, and later file transfer,
  app streaming, screen streaming. Devices advertise themselves as
  `_lwosconnect._tcp.` services.
- **Bluetooth** — used for *finding* a device that isn't on the same
  Wi-Fi yet (you just walked up with your phone) and for the initial
  pairing handshake. Once paired, the heavy lifting still happens over
  LAN.

### The "Continue On" flow

1. Both devices run `ContinuityService`, which advertises itself and
   listens on `ContinuityServer.DEFAULT_PORT`.
2. Each discovers the other via `LanDiscoveryManager` (same Wi-Fi) or
   `BluetoothDiscoveryManager` (nearby, different network).
3. Tapping an unpaired device sends a `PAIR_REQUEST` with a short code
   shown on both screens (today: a fixed demo code — see "What's
   stubbed" below).
4. Once trusted (`TrustStore`), tapping that device fires
   `ContinuityService.sendContinueOn(...)`, which builds a
   `ContinuityPacket` (which app, a free-form `stateBlob` the receiving
   app understands) and sends it as a `HANDOFF` message.
5. The receiving side only accepts `HANDOFF`s from devices in its
   `TrustStore`, then dispatches by `appId` to whichever app registered
   a handler for it.

The demo button in the app sends a fake "LWOS Notes" handoff
(`com.lwos.demo.notes`) so you can see two real devices talk to each
other before any other LWOS app exists to actually resume from.

## What's real vs. stubbed in this pass

| Piece | Status |
|---|---|
| LAN discovery (NSD) | Real |
| Bluetooth discovery | Real (needs BT already on) |
| Wire protocol + socket server/client | Real |
| Trust store on Android | Real (SharedPreferences-backed) |
| Pairing UX | **Stubbed** — auto-accepts with a fixed code (`482913`) instead of generating one and prompting the other side for confirmation |
| Trust store on desktop daemon | **Stubbed** — in-memory, resets on restart |
| mDNS advertising on desktop | **Not done** — the Python daemon only listens; you'll need to type the PC's IP into the phone (or we add this next) |
| Actual "resume" behavior | **Stubbed** — logs what it received; no real app on either side knows how to resume from a `stateBlob` yet |

## Building the Android app

**Important for AIDE users:** your existing LWOS launcher project is
Java, built in AIDE. AIDE's Kotlin support is very limited/largely
unsupported, so **this project is set up as a standard Gradle project
for Android Studio**, not AIDE. You have three realistic options:

1. Build this in Android Studio as its own app (recommended — fastest
   path to testing Continue On on real devices today).
2. Port it to Java if you want to keep everything in AIDE. I can do
   this if you'd rather not introduce a second IDE/toolchain.
3. Treat this as the design/reference implementation and port the
   pieces into the launcher's Java codebase when you get to that
   "fold it into the OS" step you mentioned.

To build in Android Studio:

```bash
# from the lwos-connect/ directory
./gradlew assembleDebug
# or just open the folder in Android Studio and hit Run
```

Minimum supported OS is Android 12 (`minSdk = 31`), matching LWOS
Mobile's floor.

## Running the desktop daemon (CachyOS side)

```bash
cd daemon
python3 lwos_continuity_daemon.py
```

No dependencies beyond the Python 3 standard library. It listens on
`0.0.0.0:59213` and prints every pairing/handoff it receives.

Find your PC's LAN IP (`ip addr` on CachyOS) and, for now, that's what
you'd point a future "pair manually" field at until mDNS advertising
is added to the daemon.

## Suggested next steps, in order

1. **Wire up real pairing UX** — generate a random code on the
   initiating device, show it on both screens, require explicit
   confirmation on the receiving device instead of auto-accept.
2. **mDNS advertising from the Python daemon** (via `python-zeroconf`
   or Avahi) so phones discover the PC the same way they discover each
   other — no typed-in IPs.
3. **Pick the first real app to support Continue On** (e.g. a browser
   — hand off the open URL + scroll position) so `stateBlob` has a real
   consumer on both ends.
4. **Fast File Sharing** next, since it reuses this exact transport
   layer (LAN discovery + trust) and is the next feature by value per
   effort.
5. Fold the always-on service into the LWOS Mobile launcher/OS itself,
   per your note about rebuilding the launcher later.
