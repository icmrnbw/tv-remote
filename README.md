# Rosso Remote

An Android app that turns your phone into a remote for a Rosso smart TV over
Wi‑Fi. It works with two kinds of TV:

- **Vestel-made Smart TVs** (sold as Rosso, Toshiba, JVC, Hitachi, Telefunken,
  Finlux and others), which take "virtual remote" commands on port 56789. Turn
  on **Virtual Remote** in the TV's settings for these.
- **Android TV / Google TV**, using the same protocol as Google's TV remote.

Other Linux smart TVs that only offer YouTube/Netflix casting (DIAL) and a
media renderer (DLNA) get a limited remote: volume and mute, opening YouTube
and Netflix, and closing them with Home or Back.

**Download:** [rosso-remote.apk](https://github.com/icmrnbw/tv-remote/releases/latest/download/rosso-remote.apk)
(Android 8.0 or newer). Open the file on your phone and allow installing from
your browser or file manager when Android asks.

## Using it

1. Make sure the phone and the TV are on the same Wi‑Fi network.
2. Open Rosso Remote. It searches the Wi‑Fi for TVs (by name and by scanning
   the network) and says what kind of TV each one is; tap yours. If it isn't listed, tap
   **Enter IP address** and type the address shown on the TV under
   Settings → Network & Internet.
3. Android TV only: the TV shows a 6 character code. Type it into the app and
   tap **Pair**. You only do this once.

If a Vestel-made TV ignores the buttons, look in its settings (often
Settings → System → More, or Network) for **Virtual Remote** and turn it on.

The remote has power, input, mute, settings, the arrow pad with OK, back, home,
menu, volume and channel (hold to repeat), play/pause, rewind/fast forward, a
number pad, and shortcuts to YouTube, Netflix, Prime Video and Disney+.

## How it works

On Android TV the app speaks the same protocol as Google's own TV remote
("Android TV Remote Service", ports 6466 and 6467). Pairing exchanges
certificates; the code on the TV proves both sides are talking to each other.

On Vestel TVs each button is an HTTP POST of
`<remote><key code='1012'/></remote>` to `http://TV:56789/apps/SmartCenter`,
and YouTube / Netflix start through DIAL (`POST /apps/YouTube`).

For any other TV, the TV list's **Check this TV** report shows what the TV
runs, which is the starting point for adding support.

## Building

GitHub Actions builds the APK on every push to `main` and publishes it as a
release. To build locally with the Android SDK installed:

    ./gradlew assembleRelease

The APK is signed with `app/remote.keystore` so new versions install as
updates over old ones. `tools/` has a protocol test against a simulated TV.
