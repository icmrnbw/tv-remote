# Rosso Remote

An Android app that turns your phone into a remote for a Rosso smart TV (or any
Android TV / Google TV) over Wi‑Fi.

**Download:** [rosso-remote.apk](https://github.com/icmrnbw/tv-remote/releases/latest/download/rosso-remote.apk)
(Android 8.0 or newer). Open the file on your phone and allow installing from
your browser or file manager when Android asks.

## Using it

1. Make sure the phone and the TV are on the same Wi‑Fi network.
2. Open Rosso Remote. It searches the Wi‑Fi for TVs (by name and by scanning
   the network) and says what kind of TV each one is; tap yours. If it isn't listed, tap
   **Enter IP address** and type the address shown on the TV under
   Settings → Network & Internet.
3. The TV shows a 6 character code. Type it into the app and tap **Pair**.
   You only do this once.

The remote has power, input, mute, settings, the arrow pad with OK, back, home,
menu, volume and channel (hold to repeat), play/pause, rewind/fast forward, a
number pad, and shortcuts to YouTube, Netflix, Prime Video and Disney+.

## How it works

The app speaks the same protocol as Google's own TV remote ("Android TV Remote
Service", ports 6466 and 6467). Pairing exchanges certificates; the code on
the TV proves both sides are talking to each other. If your TV doesn't run
Android TV / Google TV, this app can't control it.

## Building

GitHub Actions builds the APK on every push to `main` and publishes it as a
release. To build locally with the Android SDK installed:

    ./gradlew assembleRelease

The APK is signed with `app/remote.keystore` so new versions install as
updates over old ones. `tools/` has a protocol test against a simulated TV.
