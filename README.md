# TV Trackpad

Turns an Android phone into a Wi-Fi remote for Google TV / Android TV. It talks to the TV's
built-in debugging service (ADB over the network, port 5555), so it can press any button
and type text.

**Install:** open
https://github.com/theboyjones-oss/tv-trackpad/releases/latest/download/TV-Trackpad.apk
on your phone, open the downloaded file, and allow "install unknown apps" if asked.

Every push to `main` builds a new APK automatically (Actions tab → Build APK),
and it installs over the previous version.

## One-time TV setup
1. TV: Settings › System › About → select **Android TV OS build** 7 times.
2. TV: Settings › System › Developer options → turn on **USB debugging**
   (and **Network debugging / ADB over network** if listed).
3. Phone and TV on the same Wi-Fi. In the app: **Connect › Find my TV on Wi-Fi**.
4. On the TV, tick **Always allow from this computer** and choose **Allow**.

## Using it
- **Swipe remote:** swipe = arrow keys, tap = OK, long-press = hold OK, two-finger tap = Back.
- Buttons: Back, Home, Play/Pause, volume, mute, keyboard, Power, Settings, Search, Apps, Menu.

## Code
- UI: `app/src/main/assets/index.html`
- Connection and commands: `MainActivity.java`
- ADB protocol client: `AdbConnection.java`, key handling: `AdbKeys.java`

## On-screen cursor
The top box is a trackpad. The first tap installs (once) and starts a small helper on the TV
(`tvhelper/`, an accessibility service), then shows a mouse cursor. Slide to move, tap to click,
hold to long-press, two fingers to scroll, two-finger tap = Back. Pressing any remote button hides
the cursor. The helper is built by CI and bundled into the phone app as `assets/tvhelper.apk`.
