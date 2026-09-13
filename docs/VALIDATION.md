# VitalBreathe validation

This page separates what has been observed on real hardware from what is covered only by automated tests. It is deliberately conservative: an unchecked item remains unchecked even when the implementation looks correct.

Last updated: 13 September 2026.

## Current public-beta position

VitalBreathe is ready for careful public beta use on the latest-generation Karoo, but it has not completed the repeated-ride and long-ride programme required for a stable claim.

The strongest physical evidence belongs to the previous signed Personal.5–Personal.6 test line. Personal.5 established the dashboard, graph, battery, and reconnect behaviour. Those runtime components were unchanged in Personal.6, which then established the corrected FIT metadata order. Beta.1 keeps the same package and signing line, but still needs a final candidate-specific hardware check after all public-release changes are frozen.

## Reference 1.0.0-personal.6 package

| Item | Value |
| --- | --- |
| Version | 1.0.0-personal.6, code 10006 |
| Privately archived source commit | `e0e9c7d63e06c74eec79404c2c6ae56218e617d4` |
| Hardware | Latest-generation Hammerhead Karoo, device family K24 |
| Sensor | Tymewear VitalPro; private sensor identity removed |
| APK SHA-256 | `87231672d1b6c93347468dde89d56cc30a13875d373f94bde8eed7280d849bd3` |
| Signer certificate SHA-256 | `707995e7c4077bc02abc934339d4b3d29700492f8998dc97b728fbbda23a7be4` |
| Evidence record | Sanitised results in this document; raw logs and FIT evidence retained privately |

The checked APK matched the installed copy byte for byte. Its release verification covered the package, version, signer, ZIP alignment and integrity, compiled build identity, clean Git revision, non-debuggable status, target SDK, and absence of Internet permission.

## Physical observations

| Check | Result | What the evidence proves |
| --- | --- | --- |
| Live VitalPro connection | Passed | The K24 received valid completed-breath data and strap battery data through the Karoo extension. |
| Ride dashboard | Passed | On Personal.5, full and compact layouts showed readable VE30, breathing rate, tidal volume, Karoo heart rate, and VitalPro battery during a real ride with zones disabled. The same dashboard implementation shipped in Personal.6. |
| Full-page VE graph | Passed | On Personal.5, the dedicated graph showed a rolling five-minute history, all three 15s/30s/60s modes, and a visible gap through automatic recovery. The same graph implementation shipped in Personal.6. Configured zone bands were not checked. |
| Controlled connection loss 1 | Passed | On Personal.5, data recovered in about 11 seconds without restarting or re-pairing the app. |
| Controlled connection loss 2 | Passed | On Personal.5, data recovered in about 13 seconds without restarting or re-pairing the app. |
| FIT metadata order | Passed | On Personal.6, a strict decoder found every VitalBreathe developer field described before any record used it. |
| FIT breathing records | Passed | On Personal.6, the checked activity contained 65 populated records with no zero, partial, or stale core breathing values. |

The FIT result followed the metadata-order correction in Personal.6. The ride used the deliberate zones-off state, so its success does not prove configured threshold colours, boundary transitions, or zone-session totals.

## Automated coverage

The local and GitHub Actions gates exercise both debug and release unit-test variants, Android lint, both APK build variants, locked dependencies, and strict dependency checksums.

Focused tests cover:

- VitalPro packet validation and breathing calculations;
- elapsed-time VE averaging and reconnect segments;
- Bluetooth admission, setup timeouts, retry phases, watchdogs, ownership hand-offs, and permission policy;
- stale-value clearing and FIT recording freshness;
- FIT field definitions, units, metadata settling, frozen settings, pause/resume time, and zone summaries;
- dashboard layouts at Karoo dimensions;
- graph axes, labels, first-point and empty states, elapsed-time movement, smoothing modes, zone bands, clipping, and outage gaps; and
- release build identity and privacy-safe logging.

These tests make regressions less likely. They do not simulate the Karoo Bluetooth stack, outdoor visibility, physical strap firmware, ride-service lifecycle, or the behaviour of an external analysis service.

## Still required

| Area | Status |
| --- | --- |
| Final Beta.1 APK on physical hardware | Not yet recorded |
| Cold installation and pairing of the final beta | Not yet recorded |
| All five configured zone boundaries and coloured bands | Not yet recorded |
| Experimental MI with valid personal inputs and heart rate | Not yet recorded |
| Karoo sleep and wake during a ride | Not yet recorded for the final beta |
| Official Tymewear phone-app contention and release | Not yet recorded for the final beta |
| Import of the original FIT into intended third-party services | Not yet recorded |
| Twenty consecutive rides without reboot, reinstall, or re-pair | Not run |
| One four-hour outdoor ride with navigation and a controlled dropout | Not run |
| Karoo 2 | Not validated |

No documentation or automated result should be used to turn those missing checks into a claim.

## How hardware evidence is collected

Start the repository's capture helper before pairing, reconnecting, or beginning a test ride:

```bash
./scripts/karoo-test-capture.sh \
  --apk /absolute/path/to/vitalbreathe-karoo-1.0.0-beta.1.apk
```

The helper requires exactly one authorised Karoo. It verifies the supplied and installed APK identities, records device and package state, collects bounded VitalBreathe logs and Android crash lines, and saves before/after memory and battery snapshots. It does not install unless `--install` is explicitly supplied, and it never uninstalls or clears settings.

Stop it with Ctrl-C and confirm `SESSION_INFO.txt` reports `capture_result=stopped_by_tester`. Complete the copied [hardware test record](HARDWARE_TEST_TEMPLATE.md) beside the evidence.

Download the original completed FIT from Hammerhead Dashboard and keep its checksum, decoder name and version, decoded output, and import result with the record. Do not infer FIT success from live numbers on the Karoo.

## Privacy of evidence

Raw evidence stays private. It may contain a Bluetooth address, four-character sensor ID, connection timing, health measurements, screenshots, a FIT activity, or route information. Debug builds may contain rejected physiological values used for protocol diagnosis.

Only a sanitised summary belongs in a pull request or public issue. Remove stable identifiers, route and location data, personal threshold values, and physiological samples. See [Support](../SUPPORT.md) and [Privacy](../PRIVACY.md).

## Stable-release bar

A future stable claim requires all checks in the [hardware test record](HARDWARE_TEST_TEMPLATE.md), including:

- every user-facing field and state on the physical Karoo;
- configured threshold agreement across number, colour, timer, and FIT output;
- controlled dropout, sleep/wake, and phone-contention recovery;
- original FIT decoding and intended-service import;
- 20 consecutive saved and decoded rides; and
- one four-hour outdoor ride with stable process memory and acceptable Karoo and strap battery use.

Until those pass on one frozen release candidate, VitalBreathe remains a beta.
