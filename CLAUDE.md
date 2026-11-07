# Helix Remote: one-tap song changes for the Helix + in-ear faders on the Wing

## What this is
An Android app (sideloaded APK) on the guitarist's phone, mounted upright on his mic stand during Saturday services. It replaces three things he used to juggle between songs: Notion (song order, keys), Helix footswitching (leave stomp mode → pick snapshot → back to stomp mode → reset delay level), and WingQ (in-ear mix).

The owner is a live sound engineer, not a full-time developer. Keep the code simple and readable, avoid clever abstractions, and explain decisions briefly in plain language. **Reliability during a live service beats features.** He is often the only harmonic instrument, so any gap between songs is very noticeable.

## Decisions already made (don't revisit without asking)
- **Device:** Galaxy S22+ in **portrait**. (A Galaxy Tab A is a spare.) Screen stays on while the app is open.
- **Helix:** Helix Floor, firmware 3.82, **one preset for the whole set**. **Song 1 = snapshot 1, song 2 = snapshot 2 …** (max 8). The Helix must stay in stomp-mode view; he toggles stomps with his feet during songs. The app does nothing during a song.
- **SET button** on the right of each song row fires instantly. Tapping the title does nothing (accidental-brush protection). No long-press.
- **What SET sends**, in order: CC69 = snapshot (0–7) → ~60 ms → CC2 = EXP2 position (delay mix starting point; his EXP2 controls the main delay mix, and the physical pedal takes over when moved) → CC64 taps spaced at the song's BPM (default 4 taps). Empty BPM = no taps; empty delay = no CC2.
- **Tempo taps switch** (Settings → Send tempo taps): on in rehearsal, off for services once each snapshot has its exact tempo saved on the Helix (Tempo Select = Per Snapshot gives exact, instant tempo; taps land on e.g. 79.6). The BPM still shows on each song when off.
- **Tap timing:** each tap is handed to Android's MIDI service 4 ms early with its exact due time (`send(..., timestamp)`), on an URGENT_AUDIO-priority thread. If a device ignores timestamps, all taps are early by the same amount, so the gaps are unchanged. The first tap waits until ≥ 2.2 s after the previous song's last tap so the Helix can't pair them (that caused brief random tempos like 139).
- **Song list:** no library. He types title, key, BPM (and delay start %) each week and reorders. Saved on the device only.
- **Wing:** faders control **channel send levels to a target bus** plus that **bus master** (like WingQ). Never the channels' own PA faders, never anything else. Top fader = his channel, middle = a second channel (keys to start), bottom = target bus master. Two-way: the app polls so desk changes show up.
- **Changing what a fader controls:** a ⚙ button left of each fader's − button (no long-press: he must never open a picker by accident while adjusting). Top/middle ⚙ → 8×6 grid of ch 1–40 + aux 1–8 (Wing names and colours). Bottom ⚙ → 4×4 grid of buses 1–16; the top two faders then send to that bus. When the target isn't his own bus, an amber bar shows "Working on X, not your mix" with a **↩ My mix** button. The app always starts on his own bus.
- **Wing defaults:** his guitar is **ch 13** (stereo, inputs A13/14), IEM is **bus 13** (stereo), keys **ch 15**. All are settings.
- **Wing IP isn't fixed** → find it automatically ("WING?" broadcast), remember the last address, manual address as a fallback. The Wing's Wi-Fi has internet.
- **Build:** same pattern as Pedal Remote (reyffer/pedal-remote): one web page + thin Java wrapper, GitHub Actions builds a signed APK on every push to `main` and publishes it as a release. The APK is attached in chat too (GitHub downloads stall in Chrome on his phone).

## Helix settings it relies on (owner's current values)
Global Settings → MIDI/Tempo: MIDI Base Channel 1, MIDI Over USB On, Receive MIDI Clock Auto, Send MIDI Clock Off, **Tempo Select: Per Snapshot**, MIDI PC Receive MIDI+USB, Snapshot CC Send Off.
With Tempo Select = Per Snapshot, recalling a snapshot loads its stored tempo first; the taps then override it, which is why taps come after CC69.

## How it's built
- `app/src/main/assets/index.html` — the whole screen and logic: setlist, SET, Wing OSC (encode/decode, polling, discovery), faders, settings, log. Opens in a desktop browser as a demo (pretend Helix and Wing) for layout work.
- `app/src/main/java/com/reyffer/helixremote/MainActivity.java` — thin shell:
  - USB MIDI via `android.media.midi` (no USB permission prompt). Prefers a device named Helix/Line 6, else the first USB MIDI device. Reconnects on plug/unplug.
  - `setSong(channel, snapshot, expValue, bpm, taps)` runs the SET sequence on its own thread with ~0.1 ms tap timing. A newer SET cancels an unfinished one.
  - UDP socket tied to the Wi-Fi network (`Network.bindSocket`), recreated when Wi-Fi changes. Discovery sends "WING?" to 255.255.255.255:2222 and the subnet broadcast.
  - `FLAG_KEEP_SCREEN_ON`, portrait, Back key doesn't close the app.
- Page ↔ app: page calls `window.Native.*`; app calls `window.__native(type, text)` and `window.__wingPacket(ip, port, base64)`.

## Wing OSC notes (from Patrick-Gilles Maillot's "WING Remote Protocols", FW 3.0.5)
- OSC on UDP 2223; replies go back to the sending port. Discovery: send `WING?` to UDP 2222 → `WING,<ip>,<name>,<model>,<serial>,<fw>`.
- Read: send the address with no args. Float params reply `,sff` = (text, position 0..1, dB); int params `,sfi`.
- Write: a float sets dB directly (e.g. `/ch/13/send/13/lvl ,f -6.0`). -144 = -∞.
- Addresses used: `/ch|aux/N/send/B/lvl`, `/ch|aux/N/send/B/on` (display only), `/ch|aux/N/$name` (read-only effective name) and `/name`, `/ch|aux|bus/N/col` (1..12), `/bus/B/fdr`, `/bus/B/mute` (display only), `/bus/B/name`.
- Colours 1..12: gray blue, medium blue, dark blue, turquoise, green, olive green, yellow, orange, red, coral, pink, mauve. All names/colours are read on connect and when a picker opens (16 requests per 30 ms) and cached on the device.
- Only one OSC subscription is allowed at a time, so the app **polls** (every 400 ms) instead of subscribing, to avoid stealing it from WingQ/Mixing Station/Co-Pilot.
- Fader position ↔ dB uses the X32 law (0.75 = 0 dB, 1.0 = +10 dB). The upper half matches the Wing's own replies; the low end (< -30 dB) is unverified.
- Faders use relative drag (touching never jumps the level), ±1 dB buttons, max one send per 50 ms while dragging, and ignore polled values for 1.2 s after a local change.

## To verify on real gear (status)
- [x] Helix shows up over USB MIDI on the S22+ (connects straight away).
- [x] CC69 changes snapshot and the Helix stays in stomp view.
- [ ] CC2 moves the EXP2 delay mix — **didn't work** in the first home test (preset has 2–3 delay blocks). Owner is fine saving the delay mix per snapshot instead and leaving Delay start % empty. Cause not yet investigated.
- [ ] CC64 taps land on the right BPM (check the Helix tempo display) with Tempo Select = Per Snapshot.
- [x] Wing found automatically at church (10.6.6.20); faders working (v1.5, after fixing a Wi-Fi/reconnect loop that made them flicker).
- [ ] Names and colours come through (check `$name` vs `name`, and that colour replies parse); aux sends to buses work.
- [ ] Wing fader low-end law matches (drag to around -40 dB and compare with the desk).

## Known limits
- **No preset save from the app:** the Helix has no MIDI command to save. Only HX Edit's undocumented USB protocol can (TonePush reverse-engineered it, HX Stomp only, can lock the unit up). Not for a live rig.
- **On-screen keyboard:** the Helix's USB makes Android think a physical keyboard is attached. On the owner's S22+ the "Show on-screen keyboard" setting wasn't enough; Samsung's floating menu → "On Screen Keyboard" works.
- **Battery:** the phone powers the USB link and the screen is always on. Charging while connected needs a USB-C hub with PD passthrough (untested).

## Working agreements
- Ask before anything that changes live behaviour.
- Keep `NOTES.md` short: what's done, what's next.
- Commit after each working step with clear messages; each push to `main` builds an APK.
