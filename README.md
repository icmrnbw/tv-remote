# Rosso Remote

An Android app that turns your phone into a TV remote over Wi‑Fi or Bluetooth.
In English, Russian and Uzbek. Free, with ads (Google AdMob). It is an independent app,
not made by or affiliated with Rosso or any TV maker.

What it can control, honestly:

| TV | What works |
|---|---|
| **Android TV / Google TV** (incl. Rosso Android models, TV sticks) | Every button. Uses the same protocol as Google's own TV remote. |
| **Rosso Linux Smart TVs** and other TVs that only offer DIAL/DLNA | Volume, mute, opening YouTube and Netflix, closing them with Home or Back. |
| **Vestel-made Smart TVs** with "Virtual Remote" turned on | Most buttons (from the published Vestel protocol; not tested on a real TV). |
| **TVs with Bluetooth** that accept a keyboard/remote | Experimental Bluetooth remote mode (Android 9+); not yet tested on a real TV. |

**Download:** Google Play (coming soon), or the APK from
[releases](https://github.com/icmrnbw/tv-remote/releases/latest/download/rosso-remote.apk)
(Android 8.0 or newer). [Privacy policy](PRIVACY.md).

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

GitHub Actions builds a signed APK and App Bundle on every push to `main` and publishes them as a
release; signing keys and AdMob IDs come from repository secrets and variables (see
[RELEASE.md](RELEASE.md)). To build locally with the Android SDK installed:

    ./gradlew assembleDebug          # test ads, debug key
    ./gradlew bundleRelease          # needs RELEASE_KEYSTORE etc. in the environment or keystore.properties

`tools/` has a protocol test against a simulated TV, `tools/store/make_graphics.py` draws the
store graphics, and the **Store screenshots** workflow takes Play screenshots on an emulator.
