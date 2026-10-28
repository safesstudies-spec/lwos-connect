# LWOS Connect Roadmap

What's already real is covered in `README.md`: Continue On, mutual
pairing, Universal Clipboard, Fast File Transfer, and Notification
Mirroring (all phone/tablet → PC). This file is the honest technical
plan for everything else Ari asked for, grouped the way he grouped
them. Nothing here is built yet — this is architecture notes and
prior art to work from, not a progress log.

A general note on prior art: almost everything below has a
well-known open-source project that already solved some version of
it. Reading their source (or at least their design docs) before
building is worth it — not to copy, but to see which parts turned out
to be the hard parts in practice.

---

## 1. Seamless Interoperability & Continuity (remaining pieces)

### Notification Sync & Call/Messaging Integration — the other direction, plus calls/SMS

Notification *mirroring* (phone → PC, text only) is done. What's left:

- **PC → phone notification mirroring** — symmetrical to what exists,
  but Linux desktop notifications don't have one universal API the
  way Android's `NotificationListenerService` does. On GNOME/XFCE
  (what Ari's Linux Mint box runs today) this likely means hooking
  the D-Bus `org.freedesktop.Notifications` interface and watching
  `Notify` calls — `dbus-monitor` is the fastest way to see what that
  looks like before writing code against it.
- **Phone Call Movement** (answering/making calls from the PC, seeing
  caller ID pop up there) needs `READ_PHONE_STATE` /
  `READ_CALL_LOG` and, for actually routing audio, a
  `ConnectionService`/`InCallService` integration — this is a much
  bigger permission and API surface than anything built so far, and
  Google is strict about apps requesting call-related permissions
  (Play Store policy review), worth knowing before investing time if
  this app is ever meant to go on the Play Store rather than sideload
  only.
- **SMS/Messaging mirroring** is the same shape as notification
  mirroring technically (read `SmsManager`/content observer on
  `content://sms`), but Android has restricted default-SMS-app-only
  access to the SMS content provider for years — a non-default app
  can still listen via `BroadcastReceiver` for
  `SMS_RECEIVED_ACTION`, which is enough for *mirroring* (not
  sending) without becoming the default SMS app.
- **Prior art**: KDE Connect's `sms` and `telephony` plugins do
  exactly this on the Linux side and are open source — their Android
  manifest permissions and `TelephonyPlugin.kt` are the fastest way
  to see the real permission list needed.

### Hand-off & Task Continuation

This *is* Continue On, already built, just needing more apps to
register `appId` handlers and richer `stateBlob` payloads (a URL +
scroll position for a browser, a file path + cursor offset for an
editor, etc.) — not a new subsystem, just adoption.

### App & Storage Streaming

Two different problems wearing one name:

- **App streaming** (running a phone app's UI on the PC, or vice
  versa) means either (a) actual app virtualization — very hard,
  Samsung DeX and "Windows Subsystem for Android" are the closest
  references and both are OS-vendor-scale efforts — or (b) just
  screen-casting a single app's window, which is really a subset of
  Universal Cast (section 2) rather than its own feature.
- **Storage streaming** (browsing/mounting a phone's storage from the
  PC like a network drive) is the tractable part: SFTP via a small
  embedded server (`paramiko` on a future Android-side SSH daemon
  isn't realistic, but something like exposing storage over a simple
  custom protocol reusing the existing file-transfer port is) or
  MTP-over-network. **Prior art**: KDE Connect's `sftp` plugin mounts
  a paired Android device's storage directly via FUSE + SFTP — the
  cleanest existing reference for this specific piece.

### Multi-Control & Input Sharing

Using one keyboard/mouse across devices, cursor moving seamlessly
between screens. This is a full input-redirection system — genuinely
one of the harder items on this whole list.

- **Prior art**: Barrier (a maintained fork of Synergy) is the
  reference implementation — open source, same core idea (one
  machine is the "server" owning the real keyboard/mouse, others are
  "clients" that receive synthesized input events when the cursor
  crosses their screen edge). On Linux it hooks X11 (or the
  `uinput` kernel interface for Wayland, which is the harder,
  newer path); on Android there's no equivalent of "synthesize a
  system-wide input event from a regular app" without Accessibility
  Service abuse or root, which is the real blocker for a phone being
  a *target* of shared input (receiving keystrokes) rather than a
  controller.

### Audio Switch & Media Sharing

Switching which device's speakers/mic are active, or casting audio
from one device to another.

- The *control* half (telling a device "stop playing locally, the
  other device is now the output") is a straightforward addition to
  the existing control-channel protocol — a new `AUDIO_SWITCH`
  message type, same shape as `CLIPBOARD_SYNC`.
  - On Android, pausing local playback means using
    `MediaSessionManager`/`AudioFocus` the normal way other apps
    already cooperate with, not pausing the sender's phone.
  - On Linux, PipeWire (CachyOS defaults to it) or PulseAudio can
    retarget an audio sink via `pw-cli`/`pactl` — scriptable from
    the daemon with `subprocess`, the same pattern already used for
    `xclip`.
- The *streaming audio itself* across devices (actually piping audio
  bytes, not just switching a local source) is a separate, harder
  problem — see Ultra-Low Latency Streaming below, since the
  transport work is shared with gaming/screen streaming.

### Fast Peer-to-Peer File Transfer

Already real (see README). Possible next step: resumable transfers
for large files (currently a failed transfer has to restart from
byte 0) and a progress UI instead of a single "Sending…" status line.

---

## 2. Display & Workspace Expansion

### Flexible Second Screen (Wired & Wireless)

Using a phone/tablet as an extra monitor.

- **Wireless** is a specific case of screen *streaming*, covered by
  the "Universal Cast" item below — technically the same video
  pipeline, just framed as "extend" instead of "mirror."
- **Wired** (USB) is actually the easier path for low latency:
  Android has no built-in "be a display over USB" mode for arbitrary
  apps, but on the PC side, projects like **deskreen** and
  **spacedesk** show the two realistic approaches — spacedesk ships a
  real virtual-display driver on the PC and a receiver app on the
  phone, which is the more "correct" architecture than screen-casting
  a mirrored desktop, if LWOS Desktop ever wants a real virtual
  output.

### Universal Cast & Desktop Mode Mirroring

Mirroring one device's screen to another.

- **Prior art, directly applicable**: **scrcpy** is the reference
  implementation for Android screen mirroring to a PC — open source,
  extremely well optimized, uses Android's built-in
  `MediaProjection`/`VirtualDisplay` APIs to capture the screen and
  streams H.264/H.265 over a plain socket (adb forwarded, no cloud
  relay). Phone → PC mirroring for LWOS Connect could realistically
  start as "do what scrcpy does, over our own LAN socket instead of
  adb."
- The reverse direction (PC screen → phone, "Desktop Mode
  Mirroring") means capturing the Linux desktop (X11:
  `ffmpeg -f x11grab`; Wayland: portal-based screen capture via
  `xdg-desktop-portal`, notably harder and more fragmented across
  compositors) and decoding H.264 on Android with `MediaCodec`.
- This is the first item on this whole list that needs real video
  encode/decode, not just JSON/file bytes — meaningfully more
  complex than everything built so far.

### Remote Camera Node

Using a phone's camera as a webcam for the PC (or vice versa, less
common).

- **Prior art**: `droidcam` and `iriun` already do exactly this —
  Android `Camera2` API captures frames, encodes, streams over the
  LAN, and the PC side exposes it as a `v4l2loopback` virtual video
  device so any Linux app (a video call, OBS, etc.) just sees it as a
  normal webcam. `v4l2loopback` is the key piece of prior art to
  study on the Linux side — it's a kernel module, which is a
  different kind of dependency than anything built so far (needs to
  be installed/loaded on CachyOS, not just a userspace script).

---

## 3. XCore Gaming Architecture

This is the biggest, hardest pillar by far — worth saying plainly
rather than pretending it's a small addition. All three items below
share one underlying problem: real-time game video/audio/input
streaming with latency low enough to actually play, which is a
different engineering discipline (codecs, frame pacing, jitter
buffers, network QoS) from everything else in this app.

### Ultra-Low Latency Streaming

- **Prior art, directly applicable**: **Moonlight** (client) +
  **Sunshine** (open-source host, a modern replacement for
  NVIDIA GameStream) is the best existing reference — open source on
  both ends, actively maintained, and solves exactly this problem:
  hardware-encoded (NVENC/AMF/QuickSync) H.264/H.265/AV1 capture of a
  game's output, sent over UDP with its own low-latency protocol
  (not TCP — retransmission delay is the enemy here), decoded on the
  client with hardware decode. A realistic path for LWOS's version of
  this is literally "package Sunshine as the host and Moonlight-
  compatible streaming as the phone client" rather than building a
  new low-latency video protocol from scratch — that protocol design
  alone is a multi-month undertaking done well.
- Needs GPU hardware encode support on whatever PC is hosting, which
  is a hardware floor LWOS Desktop docs should probably call out
  explicitly (Ari's current laptop is an Intel Core i3-6006U with no
  discrete GPU — QuickSync exists on it but is a meaningfully weaker
  starting point than a dedicated GPU for this specific feature).

### Seamless Game Migration

Pausing a game on one device and resuming on another, mid-session —
this is strictly harder than Continue On's "resume a URL," because it
means either (a) the *entire game process/state* has to move (closer
to VM live-migration/checkpoint-restore — `CRIU` on Linux is the
relevant prior art for process checkpointing, though it's finicky
with GPU state) or (b) the game itself has cloud-save-style
checkpointing built in, which is on the game's side, not this app's.
Realistically a v2+ item even after basic streaming works.

### Long-Distance Remote Play

Streaming over the internet, not just LAN — same codec/transport work
as Ultra-Low Latency Streaming, plus NAT traversal (STUN/TURN,
similar to how WebRTC or Moonlight's internet-play mode handle it)
and adaptive bitrate for variable connections. Given the bandwidth
constraints already documented for this project (100–700kbps on
Ari's side today), this specific item is worth treating as aspirational
until that changes — Moonlight over the internet generally wants
several Mbps minimum to feel playable at all.

---

## Suggested build order for this pillar, if/when it's picked up

1. Get Sunshine running on the PC and a stock Moonlight client
   working phone → PC over LAN first, with zero custom code, just to
   have a real latency/quality baseline on Ari's actual hardware.
2. Only after that baseline exists, decide whether LWOS needs a
   custom client (for the LWOS-branded UI/UX and tighter integration
   with pairing/trust) or whether wrapping Moonlight is good enough
   for a long time.
3. Game Migration and Long-Distance Remote Play both build on top of
   whatever comes out of step 1–2 — neither is worth starting first.
