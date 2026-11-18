#!/usr/bin/env bash
# Takes Play Store screenshots on a running emulator: the TV list and the remote, in English,
# Russian and Uzbek. Run by .github/workflows/screenshots.yml.
set -u
APK="$1"
OUT="$2"
PKG=io.github.icmrnbw.tvremote.debug
mkdir -p "$OUT"
adb install -r -g "$APK"
# Hide the clock/battery clutter so the shots look clean.
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false

for locale in en-US ru-RU uz-UZ; do
  adb shell cmd locale set-app-locales "$PKG" --locales "$locale" || true
  adb shell am force-stop "$PKG"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 25   # the TV list searches the network for about 15 seconds
  adb exec-out screencap -p > "$OUT/$locale-1-tv-list.png"
  adb shell input keyevent KEYCODE_BACK
  sleep 2
  adb exec-out screencap -p > "$OUT/$locale-2-remote.png"
  # Open the number pad (bottom of the remote) for a third shot.
  adb shell input swipe 540 1800 540 600 300
  sleep 1
  adb exec-out screencap -p > "$OUT/$locale-3-remote-scrolled.png"
done
ls -l "$OUT"
