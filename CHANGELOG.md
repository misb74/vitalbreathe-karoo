# Changelog

This file records rider-visible changes to VitalBreathe. The project follows semantic versioning while it is in public beta.

## [1.0.0-beta.1] - 2026-09-13

First public beta of VitalBreathe.

### Added

- Direct VitalPro breathing data on Hammerhead Karoo under a VitalBreathe-owned app identity.
- A combined VE30, zone, breathing-rate, tidal-volume, heart-rate, and strap-battery dashboard with full and compact layouts.
- A five-minute VE graph with readable time and L/min axes, 15s/30s/60s tap smoothing, optional zone bands, and visible gaps across sensor outages.
- Separate VE, BR, TV, battery, zone-distribution, experimental MI, and MI Reserve fields.
- Time-weighted ventilation averaging and a five-value personal zone model with a safe zones-off state.
- FIT developer fields for fresh breathing data, explicit VE30, the frozen threshold definition used by the ride, and configured zone-session totals.
- Bounded Bluetooth setup, valid-breath watchdog recovery, reconnect backoff, shared connection ownership, and rider-visible diagnostics.
- A fail-closed signed-release process with package, signer, checksum, source, permission, and runtime-identity verification.
- Public installation, support, privacy, security, contribution, and validation documentation.
- A signed GitHub release update path for Karoo, including a versioned manifest and icon.
- Licence and third-party notices inside the APK and beside each published release.

### Changed

- Public naming now uses an honest beta version while the endurance test programme remains incomplete.
- The signed beta keeps the package and signing identity used by the previous signed test build, allowing in-place upgrades.
- Release diagnostics keep useful timing and recovery information while redacting stable strap identifiers.

### Known limits

- Configured zones and experimental MI have not completed physical validation.
- Karoo 2, third-party FIT imports, 20 consecutive rides, and a four-hour ride remain unproven.
- VitalBreathe does not sign in to Tymewear, import thresholds, run threshold tests, or upload to the Tymewear cloud.

## 1.0.0-personal.6 - 2026-09-12

### Fixed

- Prevented live breathing records from reaching a FIT activity before their developer-field definitions at ride start.

### Verified

- The release-signed APK was checked against its expected package, certificate, checksum, clean source revision, runtime identity, and no-Internet-permission policy.
- Strict decoding of a physical Karoo FIT file found every custom field defined before use.
- The checked activity contained 65 populated breathing records with no zero, partial, or stale core values.

Personal.1 through Personal.5 were private development builds. Their dashboard layout, Bluetooth recovery, graph, release identity, and FIT-safety changes are included in the first public beta. The original development history is retained in a private archive; this public repository begins with the reviewed Beta.1 source tree.

[1.0.0-beta.1]: https://github.com/misb74/vitalbreathe-karoo/releases/tag/v1.0.0-beta.1
