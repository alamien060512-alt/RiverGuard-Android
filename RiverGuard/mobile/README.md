# RiverGuard — Android app (thin client)

The APK is a native shell. RiverGuard still runs on your Windows laptop (**Start RiverGuard.cmd**); the phone connects over the same private Wi‑Fi using the normal pairing flow. No backend changes.

## Build
Install Node 20+, JDK 21 and Android Studio (SDK Platform 35 + Build‑Tools). Then double‑click/run `build-apk.bat` in this folder.
APK: `android\app\build\outputs\apk\debug\app-debug.apk`

## Use
1. Laptop: run Start RiverGuard; Station settings → Create pairing code.
2. Phone: open the app, enter the laptop address (e.g. `192.168.1.20:8501`; `ipconfig` shows it), Connect, enter the 8‑digit code.
3. The address is remembered. Android Back at the first page returns to the address screen.

If it cannot connect, allow port 8501 for private networks in Windows Firewall. HTTP is unencrypted; keep to trusted private Wi‑Fi.
Known limit: file downloads (CSV export) may not save from the in‑app WebView; export from a desktop browser.
