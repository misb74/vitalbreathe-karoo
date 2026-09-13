# VitalBreathe hardware validation record

`scripts/karoo-test-capture.sh` copies this template into its timestamped
`captures` directory as `HARDWARE_TEST_RECORD.md`. Keep the original FIT files,
local decoder output, screenshots, and logs with it. Do not commit private ride
evidence.

## Result

- Overall result: NOT RUN / PASS / FAIL
- Tester:
- Test started (UTC):
- Test completed (UTC):
- Failure summary or remaining uncertainty:

Passing means every required field check passes, all 20 consecutive rides save
without a reboot, reinstall, or manual re-pair, and the four-hour ride passes.
A deliberate contention test may temporarily take the sensor away, but data
must recover automatically after the competing app releases it.

## Exact build and equipment

| Item | Recorded value |
| --- | --- |
| Git commit that produced the APK | |
| Installed APK build identity from `SESSION_INFO.txt` | |
| Embedded build identity says `sourceTree=clean` | YES / NO |
| APK filename | |
| APK SHA-256 | |
| APK signer SHA-256 | |
| App version | |
| Debug or signed release | |
| Karoo model | |
| KOS/build display | |
| Android version/API | |
| VitalPro model/size | |
| VitalPro firmware | |
| Private four-character sensor ID recorded separately | YES / NO |
| Threshold-test sport and date | |
| HR source | |
| Official Tymewear phone app disconnected | YES / NO |
| K-Breathe disabled or unpaired | YES / NO |
| Every capture ended with `capture_result=stopped_by_tester` | YES / NO |

Keep the sensor ID, Bluetooth address, route, and physiological measurements
private. The ID itself can be stored in a separate private note rather than here.

## Personal settings used

| Setting | Value |
| --- | ---: |
| Endurance VE | |
| VT1 VE | |
| VT2 VE | |
| Top Z4 VE | |
| VO2max VE | |
| Resting BR | |
| Maximum BR | |
| Resting HR | |
| Maximum HR | |

## Field and recovery check

| Check | PASS / FAIL | Evidence or observation |
| --- | --- | --- |
| Cold pairing through Sensors > Extensions | | |
| Vital Dashboard values and units | | |
| VE live, 5s, 15s, 30s, and 60s | | |
| Full-page VE Graph waiting/first-dot states, 15s/30s/60s modes, five-minute scale, and reconnect gap | | |
| BR and TV | | |
| VitalPro battery | | |
| VE Zones LIVE timer | | |
| VE Zones REC timer | | |
| MI and MI Reserve with a valid HR source | | |
| MI fields show `no HR` after HR removal | | |
| VE30 number, colour, timer, and FIT zone agree at each boundary | | |
| Values clear during a controlled sensor dropout | | |
| Sensor reconnects without restart or re-pair | | |
| Data returns after Karoo sleep/wake | | |
| Data returns after the competing phone app releases the sensor | | |

Record observed connection timings rather than guessing them:

| Event | Seconds | Log timestamp or note |
| --- | ---: | --- |
| Pair request to first valid breath | | |
| Ride start to first displayed value | | |
| Strap removed to values cleared | | |
| Strap returned to first valid breath | | |
| Contention released to first valid breath | | |

## Original FIT file check

- Hammerhead ride URL or private identifier:
- Original downloaded FIT filename:
- FIT SHA-256:
- Local decoder and version:
- Decoder integrity check: PASS / FAIL
- Intended analysis-service import: PASS / FAIL

| FIT requirement | PASS / FAIL | Evidence or observation |
| --- | --- | --- |
| Record timestamps are readable and ordered | | |
| `tyme_breath_rate` uses `brpm` | | |
| `tyme_tidal_volume` uses `vol/br` | | |
| `tyme_minute_volume` uses `vol/min` | | |
| `vitalbreathe_ve_30s` uses `vol/min` | | |
| `tyme_inhale_exhale_ratio` uses `sec/sec` | | |
| `tyme_ve_zone` matches the VE30 zone, or is zero when zones are disabled | | |
| All five frozen `vitalbreathe_ve_*` thresholds match the settings above, or are zero when zones are disabled | | |
| With configured zones, accumulated Z1–Z5 session fields are consistent; with zones disabled, all Z1–Z5 summary fields are absent | | |
| No zero-filled breathing values were added during the planned dropout | | |
| Samples resume after the planned dropout without backfilling it | | |
| Optional MI fields appear only when their inputs are valid | | |

## Twenty consecutive rides

Save and decode every ride. Do not reboot, reinstall, clear app data, or manually
re-pair between rides. Record `N/A` only when a scenario genuinely does not apply.

| Ride | Planned scenario | Duration | First data (s) | Unexpected dropout count | Recovery (s) | FIT decode | Service import | PASS / FAIL | Evidence/notes |
| ---: | --- | ---: | ---: | ---: | ---: | --- | --- | --- | --- |
| 1 | Cold start baseline | | | | | | | | |
| 2 | Immediate second ride | | | | | | | | |
| 3 | Repeated data-page changes | | | | | | | | |
| 4 | Pause and resume | | | | | | | | |
| 5 | Short controlled sensor dropout | | | | | | | | |
| 6 | Dropout longer than the data watchdog | | | | | | | | |
| 7 | Karoo sleep and wake between rides | | | | | | | | |
| 8 | Karoo sleep and wake during ride | | | | | | | | |
| 9 | VitalPro sleep and wake | | | | | | | | |
| 10 | Official phone-app contention, then release | | | | | | | | |
| 11 | Baseline after all recovery cases | | | | | | | | |
| 12 | Repeated page changes | | | | | | | | |
| 13 | Pause and resume | | | | | | | | |
| 14 | Short controlled dropout | | | | | | | | |
| 15 | Karoo sleep and wake | | | | | | | | |
| 16 | Baseline | | | | | | | | |
| 17 | Baseline | | | | | | | | |
| 18 | Baseline | | | | | | | | |
| 19 | Baseline | | | | | | | | |
| 20 | Final ride without reboot | | | | | | | | |

## Four-hour ride

| Check | Before | During/after | PASS / FAIL |
| --- | ---: | ---: | --- |
| Karoo battery | | | |
| VitalPro battery | | | |
| VitalBreathe total PSS from `dumpsys meminfo` | | | |
| Process/service remained alive | | | |
| Controlled dropout recovery time | | | |
| FIT decoded and imported | | | |

- Navigation and page-change sequence:
- Controlled dropout start/end timestamps:
- Any crash, frozen field, manual intervention, or unexplained FIT gap:
- Memory or battery concern:
- Evidence filenames and SHA-256 values:

## Final decision

- Safe for continued personal use: YES / NO
- Exact failures that need a software change:
- Checks that must be repeated after the change:
- Private evidence location and backup:
