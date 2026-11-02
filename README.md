# Helix Remote

Android app for a Line 6 Helix Floor and a Behringer Wing, used on stage during church services.

- **Song list** for the week (title, key, BPM, delay start). Song 1 = Helix snapshot 1, and so on.
- **SET** sends the snapshot, the EXP 2 starting position and tap tempo to the Helix over USB MIDI, so the Helix never leaves stomp mode.
- **In-ear faders**: your channel's send to your IEM bus, a second channel's send, and the bus master, over Wing OSC. Changes made at the desk show up in the app.
- Keeps the screen on.

## Install
Download `helix-remote.apk` from the latest release (or the copy sent in chat) and open it on the phone. Each new build installs over the previous one and keeps your list and settings.

## How it works
- `app/src/main/assets/index.html` is the whole screen and the Wing logic. Opened in a desktop browser it runs a demo with a pretend Helix and Wing.
- `MainActivity.java` is a thin wrapper that does USB MIDI (Android's MIDI service) and UDP on the Wi-Fi, which a web page can't.
- Each push to `main` builds a signed APK with GitHub Actions and publishes it as a release.

Wing OSC details from Patrick-Gilles Maillot's *WING Remote Protocols*. Unofficial; not made or endorsed by Line 6 or Behringer.
