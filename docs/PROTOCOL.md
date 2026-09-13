# VitalPro interoperability notes

These notes describe the minimum Bluetooth behaviour VitalBreathe uses to read
live breathing data from a Tymewear VitalPro. They are based on the original
[K-Breathe](https://github.com/gloscherrybomb/k-breathe) implementation and
observations made with owned hardware.

VitalBreathe is an unofficial community project. This is not a complete
VitalPro protocol specification, and it does not document firmware updates,
cloud services, commands, or private Tymewear application behaviour.

## Supported discovery

VitalBreathe accepts breathing straps advertised with these case-insensitive
name forms:

- a name containing `VitalPro`;
- a name containing `Tymewear`;
- `TYME-XXXX`, where `XXXX` is the four-character sensor ID.

The separate `TymeHR` heart-rate pod is explicitly excluded. Some VitalPro
firmware does not advertise the custom service UUID reliably, so discovery
cannot depend on a service filter alone.

## GATT services used

| Purpose | UUID | Access |
| --- | --- | --- |
| VitalPro custom service | `40B50000-30B5-11E5-A151-FEFF819CDC90` | service |
| Live packet stream | `40B50004-30B5-11E5-A151-FEFF819CDC90` | notify |
| Optional sensor identity | `40B50007-30B5-11E5-A151-FEFF819CDC90` | read |
| Standard Battery Service | `0000180F-0000-1000-8000-00805F9B34FB` | service |
| Standard Battery Level | `00002A19-0000-1000-8000-00805F9B34FB` | read |
| Client Characteristic Configuration | `00002902-0000-1000-8000-00805F9B34FB` | write |

The usable connection sequence is:

1. Find a supported advertisement.
2. Connect over BLE and discover services.
3. Enable notifications on `40B50004` by writing its CCCD.
4. Optionally read the standard battery level and sensor identity.
5. Wait for a valid breath-summary packet before reporting the sensor as
   connected.

Non-breath traffic must not keep a stale breathing connection alive. The app
tracks valid completed breaths separately and reconnects when they stop.

## Live packet types

Notifications on `40B50004` begin with a one-byte type:

| Type | Observed payload | VitalBreathe behaviour |
| --- | --- | --- |
| `0x01` | completed-breath summary | validate and publish |
| `0x02` | motion data | ignore |
| `0x06` | ADC peak data | ignore |

The completed-breath packet is 17 bytes. Multi-byte values are little-endian:

| Offset | Type | Meaning used by VitalBreathe |
| --- | --- | --- |
| `0` | `uint8` | packet type `0x01` |
| `1..4` | `uint32` | monotonic timestamp in 40 ms ticks |
| `5..6` | `uint16` | inhale duration |
| `7..8` | `uint16` | exhale duration |
| `9..10` | `uint16` | tidal-volume ADC delta |
| `11..12` | `uint16` | duplicate ADC delta integrity word |
| `13..14` | `uint16` | unknown value, not exposed |
| `15..16` | `uint16` | duplicate unknown-value integrity word |

VitalBreathe rejects a packet when either duplicated word disagrees. The first
valid packet establishes a timestamp baseline; the next one provides a full
inter-breath interval. Breathing rate is calculated from that interval rather
than inhale plus exhale time, which omits the pause between breaths.

The current tidal-volume conversion is:

```text
tidal volume (L) = raw ADC delta × 0.01
minute ventilation (L/min) = breathing rate × tidal volume
```

That factor has matched the available side-by-side FIT evidence, but Tymewear
does not publish a calibration contract for this unofficial integration. New
hardware or firmware should therefore be compared against the official
Tymewear output before its values are treated as equivalent.

## FIT data

VitalBreathe writes its own Hammerhead developer-field identity. It does not
claim Tymewear's private developer identity. Names beginning with `tyme_` are
retained for compatibility with existing FIT consumers; the `vitalbreathe_`
names are owned by this project. Every field currently uses the FIT `float32`
base type.

### Per-record fields

| Number | Name | Unit | When written |
| ---: | --- | --- | --- |
| 0 | `tyme_breath_rate` | `brpm` | Fresh breathing data |
| 1 | `tyme_tidal_volume` | `vol/br` | Fresh breathing data |
| 2 | `tyme_minute_volume` | `vol/min` | Fresh completed-breath VE |
| 3 | `tyme_inhale_exhale_ratio` | `sec/sec` | Fresh breathing data |
| 4 | `tyme_mobilization_index` | `%` | Only with valid personal MI inputs and heart rate |
| 5 | `tyme_percent_brr` | `%` | Only with valid personal MI inputs |
| 6 | `tyme_ve_zone` | none | Fresh breathing data; `0` means zones are off |
| 17 | `vitalbreathe_ve_30s` | `vol/min` | Fresh time-weighted VE30 |

### Session fields

| Number | Name | Unit | Meaning |
| ---: | --- | --- | --- |
| 7 | `tyme_ve_zone1_time` | `min` | Recorded time in Z1 |
| 8 | `tyme_ve_zone1_percentage` | `%` | Recorded share in Z1 |
| 9 | `tyme_ve_zone2_time` | `min` | Recorded time in Z2 |
| 10 | `tyme_ve_zone2_percentage` | `%` | Recorded share in Z2 |
| 11 | `tyme_ve_zone3_time` | `min` | Recorded time in Z3 |
| 12 | `tyme_ve_zone3_percentage` | `%` | Recorded share in Z3 |
| 13 | `tyme_ve_zone4_time` | `min` | Recorded time in Z4 |
| 14 | `tyme_ve_zone4_percentage` | `%` | Recorded share in Z4 |
| 15 | `tyme_ve_zone5_time` | `min` | Recorded time in Z5 |
| 16 | `tyme_ve_zone5_percentage` | `%` | Recorded share in Z5 |
| 18 | `vitalbreathe_ve_endurance` | `vol/min` | Z1-to-Z2 boundary used for the ride |
| 19 | `vitalbreathe_ve_vt1` | `vol/min` | Z2-to-Z3 boundary used for the ride |
| 20 | `vitalbreathe_ve_vt2` | `vol/min` | Z3-to-Z4 boundary used for the ride |
| 21 | `vitalbreathe_ve_top_z4` | `vol/min` | Z4-to-Z5 boundary used for the ride |
| 22 | `vitalbreathe_ve_vo2max` | `vol/min` | Z5 ceiling and graph reference used for the ride |

The five threshold fields are written once for each FIT session, including as
zero when zones are deliberately off. Zone-time and percentage summaries are
omitted when zones are off. The record-writing behaviour and hardware evidence
are explained in [the rider guide](../README.md#what-is-saved-in-the-fit-file)
and [validation record](VALIDATION.md).

## Contributing protocol evidence

Useful reports include the VitalBreathe build line, Karoo model and KOS version,
VitalPro model/firmware, and whether the official phone app was disconnected.
Do not post raw FIT files, Bluetooth addresses, sensor IDs, routes, or health
data in a public issue. Follow [SUPPORT.md](../SUPPORT.md) for a privacy-safe
report.
