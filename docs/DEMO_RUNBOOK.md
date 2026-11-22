# Demo runbook: the 4 things to show on real hardware

This is the step-by-step for running all four demos on your actual
phone and your actual Linux Mint laptop. It exists because of an
important limitation worth being upfront about: **the assistant
building this project runs in a cloud sandbox with no physical phone,
no USB port, no Bluetooth radio, and no Wi-Fi Direct hardware** — so
none of these four demos can be *performed* from there. What could be
done from the sandbox, and was:

- The PC-daemon side of pairing, clipboard sync, file transfer, and
  the Continue On handoff dispatch was exercised end-to-end over real
  sockets against the real, unmodified daemon code (a small script
  stood in for the phone, speaking the exact same wire protocol).
- The PC-side Second Screen pipeline (`ffmpeg -f x11grab` → H.264) was
  run against a real virtual display and verified to produce a valid
  Annex-B baseline H.264 stream — the exact bytes your phone's
  `MediaCodec` decoder expects.
- Every Android change (Bluetooth loop-discovery fix, Wi-Fi Direct,
  USB auto-reconnect, the manifest permissions) compiles clean in CI.

What couldn't be verified without your hardware: the Android app
actually running, actual Bluetooth/Wi-Fi Direct radios finding each
other, actual `adb`/USB behavior, and the phone's screen actually
decoding and showing the PC's video. That's what this runbook is for.

## Before you start

On the PC (Linux Mint):

```bash
cd lwos-connect/daemon
sudo apt install xclip ffmpeg android-tools-adb      # one-time
python3 lwos_continuity_daemon.py
```

Leave it running in a terminal — its printed log is how you'll see
pairing/clipboard/handoff/file events arrive in real time. Optional
but recommended for auto-discovery over Wi-Fi (see Demo 4):

```bash
pip install --break-system-packages zeroconf
```

(This one is flagged because it was written against the documented
`zeroconf` API but **could not be installed or run in the sandbox** —
the sandbox's network policy blocks reaching pypi.org. If anything
about LAN auto-discovery misbehaves, this is the first place to look.)

On the phone: build and install the app from Android Studio
(`./gradlew installDebug` with the phone plugged in and USB debugging
on, or `Run` from Android Studio). Grant the permissions it asks for —
Bluetooth, and on first launch over Wi-Fi Direct, either Nearby Wi-Fi
Devices (Android 13+) or Location (Android 12).

---

## Demo 1 — Mutual pairing + clipboard + file transfer

1. Make sure phone and laptop are on the same Wi-Fi (or connect the
   phone to the laptop's hotspot).
2. Open LWOS Connect on the phone. Your laptop should appear under
   **Nearby devices** within a few seconds (if it doesn't, use **Add
   device manually (by IP)** — find the laptop's IP with `ip addr` in
   its terminal).
3. Tap the laptop's entry. A dialog shows a pairing code (`482913`,
   fixed for this demo build — see README for why). Confirm the same
   code is in the daemon's terminal expectation and tap **Pair**.
4. Watch the daemon's terminal: you should see
   `[pairing] <your phone> (...) wants to pair with code 482913 — auto-accepting`
   immediately followed by it dialing back out and your phone's app
   showing the laptop as trusted too — that "dial back out" step is
   the actual bug fix this project made (pairing used to be one-way).
5. **Clipboard**: with LWOS Connect open and in the foreground on the
   phone, copy some text (anywhere — a browser address bar, a notes
   app, even inside LWOS Connect). Within a second or two it should
   land on the laptop's system clipboard — paste anywhere on the
   laptop to confirm. The daemon's terminal also logs
   `[clipboard] From <phone>: '...'`.
6. **File transfer**: tap **Send file to device**, pick any real file
   (a photo works well since you can eyeball that it's genuinely the
   same image afterward), choose the laptop. Confirm it lands in
   `~/LWOSReceived/` on the laptop, and that it opens correctly /
   matches the original.

## Demo 2 — Continue On handoff (`com.lwos.demo.notes`)

This one is almost a side effect of Demo 1: once the laptop is
trusted, **tap its entry in Nearby devices again** (same tap you used
to pair — now that it's trusted, it fires Continue On instead of
showing the pairing dialog). You should see:

- On the phone: a status line "Sent Continue On to \<laptop name\>".
- On the laptop's terminal:
  ```
  [handoff] LWOS Notes (demo) from <phone>
    -> Would open LWOS Notes (desktop) with: {'note': 'Picked up from <phone model>', 'cursor': 42}
  ```
  That dict is the real `stateBlob` JSON, decoded — proof the full
  phone → daemon → `APP_HANDLERS["com.lwos.demo.notes"]` dispatch path
  works, exactly like a real app's handoff would once one is wired up
  to actually resume from it (see ROADMAP.md).

## Demo 3 — Wired Second Screen (mirror mode)

1. One-time per phone: **Settings → About phone** → tap **Build
   number** 7 times → **Developer options** → enable **USB debugging**.
2. Plug the phone into the laptop over USB. Accept the **"Allow USB
   debugging?"** prompt on the phone (tick "always allow" to skip it
   next time).
3. Watch the daemon's terminal: within a few seconds (it polls every 3
   seconds) it should print
   `[adb] Wired transport ready for <serial> (ports 59213, 59214, 59215)`
   — that's `run_adb_reverse_loop` finding the phone via `adb devices`
   and running `adb reverse tcp:59215 tcp:59215` (and the other two
   ports) automatically, no manual `adb` command needed.
4. On the phone, tap **Second Screen (USB)**. It should show a live,
   low-latency mirror of whatever's on the laptop's screen. Move a
   window around or open something on the laptop to see it update in
   near-real-time on the phone.
5. If it just sits on "Connecting to PC…": check `adb devices` on the
   laptop shows the phone as `device` (not `unauthorized`), and that
   `ffmpeg` is actually installed (`ffmpeg -version`).

## Demo 4 — Remember devices + auto-connect in range

This is the new feature this last round of work added — four
independent paths, so it's worth testing each:

- **Wi-Fi (mDNS)**: once the laptop and phone have paired at least
  once, close and reopen LWOS Connect (or just walk out of Wi-Fi range
  and back in) — the laptop should reappear under Nearby devices
  automatically, with no "Add device manually" step, **provided**
  `pip install zeroconf` succeeded on the laptop (see "Before you
  start" above — this is the one piece that couldn't be runtime-tested
  in the sandbox at all).
- **USB**: unplug and replug the USB cable. Within a few seconds of
  replugging, the laptop should reappear in Nearby devices at
  `127.0.0.1` automatically (no re-running `adb reverse` by hand) —
  this rides on the exact same `adb reverse` tunnel as Demo 3, so if
  Demo 3 worked, this piece only adds "notice the cable event and
  re-surface the laptop without you tapping anything."
- **Bluetooth**: with Bluetooth on for both devices, and having paired
  at least once, move the phone out of Bluetooth range and back in (or
  toggle Bluetooth off/on on the phone) — it should start scanning
  again and reconnect, instead of the old behavior of giving up after
  ~12 seconds of scanning.
- **Wi-Fi Direct**: this is the one genuinely new wireless path (not
  just "reconnect automatically" — a whole new transport) and also the
  **least tested of everything in this project**, since it needs two
  real radios physically near each other and there was no way to
  exercise any of it without them. Turn off regular Wi-Fi on both
  devices (or take them out of range of any shared network) but leave
  Wi-Fi radios on, keep them physically close, and the phone should
  still discover and reconnect to an already-trusted laptop via Wi-Fi
  Direct. If it doesn't: `adb logcat | grep LwosWifiDirect` on the
  phone is the first place to look — that tag covers the whole
  feature, and Wi-Fi Direct's group-negotiation behavior is known to
  vary across Android OEM skins, so a real device is genuinely the
  only way this gets properly shaken out.

---

If something doesn't work, the daemon's terminal output and
`adb logcat` (filtered to `Lwos`) are the two best starting points —
every piece above logs clearly when it succeeds, fails, or is skipped
because a dependency isn't installed.
