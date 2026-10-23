# RiverGuard V2 for Android

The independent Android application ID is `com.riverguard.v2`, so this preview
can remain installed beside the previous RiverGuard app. Its SQLite database is
`riverguard-v2.db`; no earlier app's files are read, changed or migrated.

## Build

Build the shared interface first from `../frontend` (`npm ci`, `npm test`,
`npm run build`). Its output is `v2/mobile/web`. Then run `npm ci` and
`npx cap sync android` here. Android builds and native tests require JDK 21 and
Android SDK 35. `npm test` runs the native debug unit tests. The CI workflow runs
release compilation, native tests, lint and signing verification. Capacitor
packages are pinned in the lock file. Release keys remain outside source control.

## Background monitoring

In Sensors, explicitly start monitoring while the app is visible. A native
`connectedDevice` foreground service owns the receiver on HTTP/TCP port 8000,
SQLite writes and alert evaluation. Neither reception nor alert checks depend on
React or a visible WebView. A persistent monitoring notification offers a Stop
action. Screen locking and switching apps do not deliberately stop this service.

Enable alerts through the separate contextual button. Android 13+ asks for
notification permission. If permission or either alert channel is blocked,
`getMonitoring().notificationsAllowed` reports false. The same button opens
Android's app notification settings when a prior denial or channel block needs
manual correction. Monitoring can continue with alerts denied, but notifications
are not promised. The high-water and review channels are checked separately when
dispatching so one blocked channel does not suppress an enabled channel.

Native checks use captured calibration snapshots, raw signals and each station's
saved `watch:<deviceId>` settings. Freshness requires measurement and receipt ages
between five seconds ahead and 30 seconds old. Faults, blind-zone distances and
uncalibrated signals stay unavailable. A known high-water warning retains priority
when another probe is missing. Rising-water checks require five same-revision
samples spanning eight seconds. `alerts:<deviceId>` returns the most recent 100
saved transitions; active conditions are queued durably for notification delivery, with recovery
only after three distinct usable samples clear the same condition. Missing values
cannot clear a warning. Changes to calibration or watch settings produce a
separate recheck transition rather than claiming environmental improvement. Old/future evidence does not generate
new current-condition warnings. No rule certifies water safety.

## Operating limits

- Phone and ESP32 must stay on the same trusted Wi-Fi. HTTP is unencrypted.
- This service is user-started and `START_NOT_STICKY`. Reboot, force-stop or an OS
  process stop requires reopening the app and starting monitoring again. There is
  no boot receiver and no hidden restart after a user Stop.
- CPU/Wi-Fi locks are released on service shutdown; monitoring consumes battery.
  Android Doze, OEM battery controls, Wi-Fi changes and phone restarts can still
  interrupt collection. Status must be checked on the actual phone.
- A second RiverGuard receiver on the same phone cannot also use port 8000. Stop
  the older receiver before starting V2. Keeping both applications installed is
  supported; simultaneous listeners on the same address/port are not.
- Phone and laptop histories, credentials, calibration and watch settings are
  independent. V2 firmware supports two independently authenticated destinations;
  this is not history synchronization.
- Exports use Android's document picker and include raw readings and calibration
  snapshots. App backups are excluded. Export before removing this application.

## Android platform qualification

Network interaction with an external ESP32 falls within Android's documented
`connectedDevice` service description. The manifest declares
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `connectedDevice`
service type and the listed `CHANGE_WIFI_STATE` prerequisite, plus networking and
wake-lock permissions. Starts occur only from an explicit visible user action.
This is not a generic `dataSync` service and does not claim that service type's
unlimited operation.

Primary references:

- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Notification channels](https://developer.android.com/develop/ui/compose/notifications/channels)
- [User stopping a foreground app](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)

## Acceptance remaining

Pure JVM tests exercise transport boundaries, validation, signal evaluation and
persistent ledger behavior. Compilation/lint do not establish physical operation.
Actual APK installation, native SQLite persistence, document exports, lock-screen
Wi-Fi reception, notification denial/channel blocks, Stop/force-stop/reboot,
battery impact and real calibrated hardware remain physical-device acceptance.

## Repair preview upgrade

The repair preview uses native database schema 2 and creates a private checkpointed
backup before migrating schema 1. Keep app data when updating. Watch settings now
have separate source/station revisions; geometry must support the chosen thresholds
before commissioning can finish. Existing events retain their original JSON and
are labelled when historical policy provenance is unavailable.

The application owns Demo generation, so its controls are consistent across views.
A new real station has a 60-second first-reading grace period while monitoring is
running. Notification permission can be granted later: still-active relevant
conditions remain pending until delivery is possible. A delivered notification is
not a claim that the user has read it.

See [native repair evidence](../NATIVE-REPAIR-EVIDENCE.md) for executed checks and
physical-device acceptance still required. Android instrumentation tests use isolated
private test databases and can be run with `:app:connectedDebugAndroidTest` on a
compatible emulator or test device.
