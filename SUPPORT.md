# VitalBreathe support

VitalBreathe is an unofficial community beta. Setup questions belong in [GitHub Discussions](https://github.com/misb74/vitalbreathe-karoo/discussions), while reproducible bugs belong in [GitHub Issues](https://github.com/misb74/vitalbreathe-karoo/issues). Help is provided on a best-effort basis. Tymewear and Hammerhead do not support this project.

For account access, sensor warranty, the official Tymewear app, threshold-test interpretation, Karoo hardware, or medical and training advice, contact the relevant company or a qualified professional.

## Start with these checks

Most connection problems are caused by the strap being asleep or another device already holding its Bluetooth connection.

1. Wear the VitalPro so it is awake.
2. Fully disconnect or close the official Tymewear phone app.
3. Disable or unpair K-Breathe and any other extension using the VitalPro.
4. Confirm the strap was paired under **Karoo Settings → Sensors → Add Sensor → Extensions**.
5. Open VitalBreathe and confirm sensor permission is allowed.
6. Leave the screen open long enough to receive several completed breaths.

## Installation problems

### Companion does not offer VitalBreathe

Companion APK sideloading is supported on the latest-generation Karoo. Confirm that the file ends in `.apk`, use the phone's share sheet to send the downloaded file to Hammerhead Companion, and follow Hammerhead's [current sideloading guide](https://support.hammerhead.io/hc/en-us/articles/31576497036827-Companion-App-Sideloading).

Karoo 2 needs USB debugging and ADB. It has not been validated for this beta.

### Android reports a conflicting package or signature

If you have the signed `1.0.0-personal.6` test build, the beta can upgrade it because it keeps the same package and signer. A locally built or older CI debug APK may have a different signing certificate and cannot be upgraded in place.

Do not uninstall immediately if you need the existing settings. First record the sensor ID and every personal threshold. Uninstalling VitalBreathe clears its settings and Karoo sensor pairing. After recording them, uninstall the old debug copy, install the signed beta, and pair again.

### The permission prompt does not appear

Open VitalBreathe and tap **Allow sensor access**. On Android 12 and later it needs Bluetooth scan and connect permission. Android 11 and earlier may also request location because Android ties Bluetooth discovery to that permission; VitalBreathe does not read GPS location.

On Android 12 or later, an experienced ADB user can grant the permissions directly:

```bash
./scripts/karoo-adb.sh shell pm grant com.moraybrown.vitalbreathe android.permission.BLUETOOTH_SCAN
./scripts/karoo-adb.sh shell pm grant com.moraybrown.vitalbreathe android.permission.BLUETOOTH_CONNECT
```

On Android 11 or earlier, grant location instead; the two Bluetooth runtime permissions above do not exist on those versions:

```bash
./scripts/karoo-adb.sh shell pm grant com.moraybrown.vitalbreathe android.permission.ACCESS_FINE_LOCATION
```

## Pairing and live-data problems

| What you see | What it means and what to do |
| --- | --- |
| The strap is not listed | Wear it, close the phone app, wait a few seconds, and scan again under **Sensors → Extensions**. |
| `TYME-XXXX` is not listed, but a VitalPro name is | Select the VitalPro name. The optional four-character Sensor ID can improve later discovery. |
| Karoo says paired, but values remain `—` | Check the six starting checks above. A Bluetooth connection is not considered ready until a valid completed breath arrives. |
| Values disappeared during a ride | VitalBreathe clears stale values deliberately. Leave the strap awake and nearby; automatic recovery can take several seconds. |
| The app shows repeated retries | Another app may still hold the strap, or it may be out of range. Release the competing connection and let VitalBreathe continue retrying. |
| Battery remains `—` | Live breathing can still work. The sensor did not provide a usable value from the standard Bluetooth battery service. |
| Data disappears when a ride starts | Confirm the current beta is installed, then report the build label and timing. Ride-start hand-off is specifically protected and should recover automatically. |

## Ride-page problems

### The graph never appears

The graph is a separate Karoo data field. Add **VE Graph** as the only field on its own full-height data page, then swipe to that page during the ride. The dashboard never changes into the graph automatically.

### The graph says `Waiting for breath`

The page is working but does not have a fresh completed breath. Check the live-data steps above. Earlier trace history may remain visible, separated by a gap, while the sensor reconnects.

### The graph says `ZONES OFF`

That is the safe default. The trace and automatic scale still work. Zone bands appear only after all five valid, ordered values from your own sport-specific Tymewear threshold test are saved.

### A threshold or MI change did not affect the current ride

Settings saved during a recording apply after the ride. This prevents one FIT activity from using two different zone definitions.

### MI says `MI off`, `no HR`, or `idle`

MI is experimental. It needs all four personal breathing-rate and heart-rate settings, plus a usable heart-rate source from Karoo. `idle` means heart-rate reserve is below the calculation's active range.

## FIT questions

### Hammerhead or Strava does not show breathing fields

VitalBreathe writes FIT developer fields. A service can preserve those fields without displaying them, and support varies. Download the original FIT file from Hammerhead Dashboard before another service transforms it. VitalBreathe does not upload directly to Tymewear.

### There is a gap in the breathing records

That is expected when no fresh completed breath was available. VitalBreathe omits stale intervals instead of writing zeroes or old values. Records should resume after the sensor recovers without backfilling the gap.

## Report a problem safely

Before opening an issue, collect:

- the complete build label shown in VitalBreathe;
- Karoo model and software version;
- VitalPro model and firmware, if known;
- whether the official phone app and K-Breathe were disconnected;
- exact steps and approximate connection or recovery timings;
- whether the problem occurs outside a ride, during a ride, or in the FIT file; and
- a cropped screenshot with personal information removed.

Do not post a sensor ID, Bluetooth address, route, raw FIT file, or unredacted capture publicly.

For a difficult problem, an authorised ADB computer can run:

```bash
./scripts/karoo-test-capture.sh \
  --apk /absolute/path/to/vitalbreathe-karoo-1.0.0-beta.1.apk
```

The command verifies the installed build and captures operational logs until Ctrl-C. It does not install unless `--install` is explicitly added, and it never uninstalls the app. Confirm that `SESSION_INFO.txt` ends with `capture_result=stopped_by_tester`.

Treat the complete output directory as private. It can contain Bluetooth identifiers, connection details, health information, screenshots, or routes. Review and redact it locally, then ask in the issue which minimum files or excerpts are actually needed. Release builds omit high-frequency breath values, heart-rate samples, and raw packets from routine logging.

Report a security vulnerability privately by following [Security](SECURITY.md), not through a public issue. More detail about stored and synced data is in [Privacy](PRIVACY.md).
