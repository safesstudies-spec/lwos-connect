# LWOS Connect

The cross-device companion app for LightningWeb OS: phone/tablet ↔ PC
continuity. Built on top of **Continue On** (discovery, mutual
pairing/trust, and handing off "what you're doing" from one device to
another), this app now also has: **Universal Clipboard**, **Fast File
Transfer**, **Notification Mirroring**, a proper **mutual pairing
handshake** (the bug where pairing "did something" but nothing
actually synced afterward — fixed, see below), and **Second Screen
(wired, mirror mode)** — turn the phone into a live USB-connected
mirror of the PC's screen.
See `ROADMAP.md` for the larger remaining features (Phone Call
Movement, App/Storage Streaming, Multi-Control, Audio Switch,
Universal Cast, Remote Camera, a true *extended* Second Screen, and
the XCore gaming pillar).

Per your answers: this ships as a **separate app** for now (a future
pass folds the always-on parts into the LWOS Mobile launcher/OS itself
— not today), written in **Kotlin**, talking over **both local Wi-Fi/LAN
and Bluetooth**.

## How it fits together

```
 LWOS Mobile (phone/tablet)              LWOS Desktop (CachyOS PC)
 ┌───────────────────────────┐           ┌──────────────────────────┐
 │  MainActivity (UI)         │           │  lwos_continuity_daemon   │
 │  ContinuityService          │◄─ LAN ──►│  (Python, stdlib only)    │
 │   ├─ LanDiscoveryManager    │  (mDNS)   │   - same JSON protocol    │
 │   ├─ BluetoothDiscovery     │◄Bluetooth│   - mutual pairing        │
 │   ├─ ContinuityServer       │          │   - clipboard (xclip/xsel)│
 │   ├─ ContinuityClient       │          │   - notification log      │
 │   ├─ FileTransferServer     │◄─ LAN ──►│   - file transfer server  │
 │   ├─ FileTransferClient     │ (binary,  │   - per-appId handlers    │
 │   ├─ NotificationMirror     │  :59214)  └──────────────────────────┘
 │   │  ListenerService        │
 │   └─ TrustStore              │
 └───────────────────────────┘
```

Both sides speak the exact same wire format for control messages: one
line of JSON per message, sent over a plain TCP socket (port `59213`
by default). File transfer deliberately uses a **separate** raw-binary
socket (port `59214`) instead of reusing that same connection — mixing
a line-based JSON reader with a raw binary payload on one socket is a
real correctness trap (the buffered line reader can read ahead and
silently eat bytes belonging to the file), so the two are kept apart
on both sides. That protocol separation is deliberate in general: it
means you can test the Android app against the Python daemon today,
and later swap either side's internals without touching the wire
format.

### Why LAN *and* Bluetooth

- **LAN (NSD/mDNS)** — like KDE Connect/AirPlay. Used for anything with
  real payload: the actual handoff packet, and later file transfer,
  app streaming, screen streaming. Devices advertise themselves as
  `_lwosconnect._tcp.` services.
- **Bluetooth** — used for *finding* a device that isn't on the same
  Wi-Fi yet (you just walked up with your phone) and for the initial
  pairing handshake. Once paired, the heavy lifting still happens over
  LAN.

### Pairing — now mutual

This is the bug you ran into ("pairing was a process, then nothing
happened"): the original version only trusted the device that
*received* the pairing request — the device that tapped "Pair" never
got trusted back, so nothing ever synced afterward. Fixed:

1. Discover a device (LAN/mDNS, Bluetooth, or **Add device manually**
   by IP, since mDNS is unreliable over a phone hotspot).
2. Tapping an unpaired device sends a `PAIR_REQUEST` with a short code
   shown on both screens (still a fixed demo code — `482913` — not yet
   a generated one with explicit confirmation; see roadmap).
3. Whoever *receives* that request trusts the sender **and** opens a
   brand-new outbound connection back to send a `PAIR_RESPONSE`
   carrying its own identity. Every message on this protocol is a
   short-lived connect → send → close, never a reply on the same
   socket, so that outbound call-back is the only way the original
   requester ever learns to trust the other side too.
4. If you'd already added the device manually (by typing its IP),
   that temporary entry gets merged onto its real device ID once
   pairing completes, so it correctly shows as trusted afterward
   instead of staying stuck as an untrusted manual entry.

Mirrored identically on the Python daemon (`handle_pair_request` /
`handle_pair_response`).

### The "Continue On" flow

Once trusted (`TrustStore`), tapping that device fires
`ContinuityService.sendContinueOn(...)`, which builds a
`ContinuityPacket` (which app, a free-form `stateBlob` the receiving
app understands) and sends it as a `HANDOFF` message. The receiving
side only accepts `HANDOFF`s from trusted devices, then dispatches by
`appId` to whichever app registered a handler for it. The demo button
sends a fake "LWOS Notes" handoff (`com.lwos.demo.notes`) so you can
see two real devices talk to each other before any other LWOS app
exists to actually resume from.

### Universal Clipboard

Copy something on one device, it shows up on the clipboard of every
trusted device. On Android, the read side only works while the app is
in the foreground (`MainActivity.onResume`/`onPause`) — Android 10+
only lets the focused app read the clipboard, a platform restriction,
not a bug — but a background sync can still *write* a received value
at any time. A one-shot "echo guard" (`consumeClipboardEchoGuard`)
stops a just-applied remote value from immediately bouncing back out
as if you'd copied it yourself. On the desktop side, applying a
received value needs `xclip` or `xsel` installed (`sudo apt install
xclip` on Mint) — without either, the daemon logs a clear warning and
no-ops instead of silently doing nothing.

### Fast File Transfer

Pick any file (`Send file` button → trusted device), and it streams
over the dedicated binary port (`59214`) straight into
`Downloads/LWOSConnect/` on Android (via `MediaStore`, so no storage
permission needed) or `~/LWOSReceived/` on the desktop daemon.
Verified byte-for-byte correct on a 200KB test payload.

### Notification Mirroring

Requires one manual step Android doesn't offer a normal permission
dialog for: tap **Enable notification mirroring**, which deep-links to
the system settings screen where you grant LWOS Connect notification
access. Once granted, titles/text of notifications from other apps get
forwarded (text only — no actions, no images yet) to trusted devices
and logged by the daemon.

### Second Screen (wired, mirror mode)

Tap **Second Screen (USB)** with the phone plugged into the PC, and it
shows a live duplicate of whatever's on the PC's screen. No pairing or
trust check needed here — a physical USB cable already is the trust
boundary, same reasoning as any other adb-based tool.

How the transport works: `adb reverse` is the whole trick. It makes
the *phone's own* `127.0.0.1:<port>` forward over the USB cable to a
real port on the PC — so the phone just connects to localhost and is
actually talking to the PC, with adb handling every bit of the USB
complexity. No custom USB protocol code anywhere in this app. The
daemon applies this automatically (`run_adb_reverse_loop`): every few
seconds it checks `adb devices` and runs `adb reverse` for all three
of this app's ports (control, file transfer, second screen) against
any connected, authorized device. This is exactly how
[scrcpy](https://github.com/Genymobile/scrcpy) does its wired mode.

How the video works: the daemon captures the PC's real X11 display
with `ffmpeg -f x11grab`, encodes it to H.264 (`libx264`, baseline
profile for the widest hardware-decoder compatibility, zero-latency
tuning, no B-frames) and streams the raw encoded bytes straight to the
phone — one small JSON line up front announcing the real resolution
(auto-detected via `xrandr`), then a continuous raw stream, deliberately
*not* wrapped in a container (no mp4/mkv muxing) since there's nothing
here that needs seeking, only the lowest latency we can get. The phone
splits that raw stream back into individual frames
(`AnnexBNalSplitter`) and feeds them straight into `MediaCodec`,
Android's hardware video decoder, rendering directly onto the screen.

This is **mirror**, not a true extended desktop you could drag windows
onto — it shows whatever's already on the PC's screen. See `ROADMAP.md`
for what a real second-monitor mode needs on top of this (a virtual
X11 display), and for wireless mode (same video pipeline, just reached
over Wi-Fi instead of `adb reverse`). There's also no touch/mouse input
back to the PC yet — display only.

## What's real vs. stubbed vs. roadmap

| Piece | Status |
|---|---|
| LAN discovery (NSD) | Real |
| Bluetooth discovery | Real (needs BT already on) |
| Manual IP pairing | Real — the reliable fallback when mDNS is flaky (e.g. phone hotspots) |
| Wire protocol + socket server/client | Real |
| Trust store on Android | Real (SharedPreferences-backed) |
| **Mutual** pairing handshake | Real (fixed this pass — see above) |
| Universal Clipboard (phone/tablet → PC) | Real |
| Fast File Transfer (phone/tablet → PC) | Real |
| Notification Mirroring (phone/tablet → PC) | Real (text only) |
| Second Screen — wired, mirror mode | Real |
| `adb reverse` wired transport (auto-applied to all 3 ports) | Real |
| Trust store on desktop daemon | **Stubbed** — in-memory, resets on restart |
| mDNS advertising on desktop | **Not done** — use "Add device manually" with the PC's IP |
| Pairing confirmation UX | **Stubbed** — fixed demo code (`482913`), auto-accepted, no generated code + explicit confirm dialog yet |
| Clipboard/notifications/files **PC → phone** direction | **Not done** — current daemon can receive but has no equivalent of `ContinuityClient`/`FileTransferClient` to initiate a send back |
| Second Screen — wireless mode | **Not done** — same video pipeline, needs a non-adb transport (LAN) wired in |
| Second Screen — true extended desktop | **Not done** — needs a virtual X11 output on the PC; mirror only for now |
| Second Screen — touch/mouse input back to PC | **Not done** — display only, no input yet |
| Actual Continue On "resume" behavior | **Stubbed** — logs what it received; no real app on either side knows how to resume from a `stateBlob` yet |
| Everything else (Phone Call Movement, App/Storage Streaming, Multi-Control, Audio Switch, Second Screen, Universal Cast, Remote Camera, XCore gaming) | **Roadmap** — see `ROADMAP.md` |

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

No dependencies beyond the Python 3 standard library — except for
clipboard support (`xclip`/`xsel`) and Second Screen (`ffmpeg`, `adb`):

```bash
# Linux Mint
sudo apt install xclip ffmpeg android-tools-adb
# CachyOS
sudo pacman -S xclip ffmpeg android-tools
```

It listens on `0.0.0.0:59213` (control channel), `0.0.0.0:59214` (file
transfer), and `0.0.0.0:59215` (Second Screen), prints every pairing/
handoff/clipboard/notification it receives, and saves incoming files
to `~/LWOSReceived/`.

Find your PC's LAN IP (`ip addr`) and use **Add device manually** on
the phone to type it in — mDNS advertising from the daemon isn't built
yet, so this is the reliable way to connect over Wi-Fi today, hotspot
or not. (Second Screen doesn't need this step — it's reached over the
USB cable instead, see below.)

### Second Screen setup (one-time)

1. On the phone: **Settings → About phone** → tap **Build number** 7
   times → **Developer options** → enable **USB debugging**.
2. Plug the phone into the PC with a USB cable.
3. The first time, the phone shows an **"Allow USB debugging?"**
   prompt — accept it (tick "always allow from this computer" to skip
   it next time).
4. Run the daemon as above. Within a few seconds it should log
   `[adb] Wired transport ready for <device serial> (ports 59213, 59214, 59215)`
   — that's `run_adb_reverse_loop` finding the phone and tunneling all
   three ports automatically.
5. On the phone, tap **Second Screen (USB)**.

If it just shows "Connecting to PC…" and never goes further, check
that `adb devices` on the PC lists the phone as `device` (not
`unauthorized` — re-check the prompt on the phone) and that `ffmpeg`
is actually installed.

## Suggested next steps, in order

See `ROADMAP.md` for the full breakdown of the larger remaining
features. Short list, roughly by value per effort:

1. **PC → phone direction** for clipboard/notifications/files — the
   daemon can receive all three but has no equivalent of
   `ContinuityClient`/`FileTransferClient` yet to send back out.
2. **mDNS advertising from the Python daemon** (via `python-zeroconf`
   or Avahi) so phones discover the PC the same way they discover each
   other — no typed-in IPs.
3. **Wire up real pairing UX** — generate a random code on the
   initiating device, show it on both screens, require explicit
   confirmation on the receiving device instead of auto-accept.
4. **Pick the first real app to support Continue On** (e.g. a browser
   — hand off the open URL + scroll position) so `stateBlob` has a real
   consumer on both ends.
5. Fold the always-on service into the LWOS Mobile launcher/OS itself,
   per your note about rebuilding the launcher later.
