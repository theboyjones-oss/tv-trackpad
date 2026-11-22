# TV Trackpad

Turns an Android phone into a Bluetooth trackpad + keyboard for Google TV / Android TV.
The TV pairs with it like any Bluetooth accessory, so nothing needs installing on the TV.

**Install:** open
https://github.com/theboyjones-oss/tv-trackpad/releases/latest/download/TV-Trackpad.apk
on your phone, open the downloaded file, and allow "install unknown apps" if asked.

Every push to `main` builds a new APK automatically (Actions tab → Build APK),
and it installs over the previous version.

- UI: `app/src/main/assets/index.html`
- Bluetooth keyboard/mouse: `app/src/main/java/com/example/tvtrackpad/MainActivity.java`
- Keeps the connection alive with the screen off: `KeepAliveService.java`

## First connection
Open the app and tap **Connect**. Then either choose **Search for my TV** while the TV is on
*Settings › Remotes & Accessories › Pair accessory*, or choose **Make phone visible** and
pick the phone from that same TV screen.

## Honor / MagicOS
Go to Settings › Battery › App launch › TV Trackpad, switch to manual, and turn on all three options.
That stops MagicOS cutting the connection when the screen is off.
