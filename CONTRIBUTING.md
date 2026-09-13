# Contributing to VitalBreathe

Thank you for helping improve VitalBreathe. This project handles live sensor data on a bike computer, so reliability and honest evidence matter more than adding features quickly.

## Before starting

Open a GitHub issue before a large change. Explain the rider problem, the Karoo model, and how the proposal fits the public beta. Never attach private ride data, Bluetooth addresses, sensor IDs, signing files, or credentials.

Contributions are accepted under the repository's MIT licence. By submitting a contribution, you confirm that you have the right to provide it under that licence.

## Development setup

You need:

- JDK 17;
- Android SDK Platform 34;
- Android SDK Build Tools 34.0.0; and
- Android SDK Command-line Tools, including `apkanalyzer`.

Clone the public repository and use `main`:

```bash
git clone https://github.com/misb74/vitalbreathe-karoo.git
cd vitalbreathe-karoo
git switch main
```

If `ANDROID_HOME` is not set, create a gitignored `local.properties` file:

```properties
sdk.dir=/absolute/path/to/your/Android/sdk
```

Select JDK 17 and run the complete local gate:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew --dependency-verification=strict --no-daemon \
  test lintDebug assembleDebug assembleRelease
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/vitalbreathe-karoo-1.0.0-beta.1.apk
```

It is for development only. An ordinary release build without the private release key is deliberately unsigned and cannot be installed.

## What a change must preserve

- VitalBreathe must keep working without Internet permission.
- One shared Bluetooth session must feed all fields. A new field must not open a competing sensor connection.
- A valid completed breath, not an arbitrary Bluetooth packet, is the source of user-visible freshness.
- FIT gaps must remain gaps. Do not write zero-filled, partial, or indefinitely stale breathing records.
- VE30 must stay the common signal for the dashboard colour, live zone, zone timer, and recorded zone.
- Personal thresholds have no universal defaults. All five must be valid and ordered, or zones remain off.
- Existing FIT developer-field names, numbers, types, units, and meaning are compatibility commitments.
- Release builds must not expose raw health data, sensor packets, or stable device identifiers in routine logs.

Add focused tests for every changed rule. A passing build proves software behaviour only; it does not prove Bluetooth timing, outdoor readability, Karoo lifecycle behaviour, or FIT compatibility on hardware.

## Hardware changes

Changes to Bluetooth, ride views, lifecycle handling, battery reporting, or FIT output need a physical Karoo and VitalPro check before they can be called validated.

Use the process in [Validation](docs/VALIDATION.md) and start from [the hardware test record](docs/HARDWARE_TEST_TEMPLATE.md). Keep raw captures private. A pull request should contain a sanitised result: exact build identity, hardware and firmware, scenario, measured timings, FIT decoder and result, and remaining uncertainty.

## Pull requests

Keep each pull request focused. In its description, include:

- what changes for the rider;
- why the change is needed;
- automated tests run and their result;
- physical tests run, or a clear statement that none were run;
- any compatibility or privacy impact; and
- screenshots for visible changes, with personal data removed.

Run `git diff --check` before pushing. Do not commit APKs, signing material, `local.properties`, diagnostic captures, or private FIT files.

## Dependencies

Dependencies are locked and their downloaded checksums are verified. After an intentional dependency change, run the full workload and regenerate both records:

```bash
./gradlew test lintDebug assembleDebug assembleRelease \
  --write-locks --write-verification-metadata sha256
```

Review every lock and checksum change before committing it. Do not weaken strict dependency verification to make a build pass.

## Maintainer release signing

Public releases use one long-lived private signing identity so Android can upgrade an existing installation without deleting settings. Never commit the key or its passwords.

For local signed builds, generate and securely back up a PKCS12 key before the first permanent install:

```bash
keytool -genkeypair \
  -keystore /secure/path/vitalbreathe-release.p12 \
  -storetype PKCS12 \
  -alias vitalbreathe \
  -keyalg RSA -keysize 4096 -validity 10000
```

Record its SHA-256 certificate fingerprint with `keytool -list -v`. Copy `keystore.properties.example` to the gitignored `keystore.properties`, then add the absolute key path, password, and alias. Restrict the file to your account with `chmod 600 keystore.properties`.

The established public signing-certificate fingerprint is pinned in `version.properties`, independently of the private key configuration. Never change it for an update to an existing VitalBreathe installation.

A separately distributed fork must also choose its own application ID, extension ID, update-manifest URL, and signer before its first install. Changing those identities breaks upgrade compatibility with VitalBreathe but avoids Android package conflicts and accidental competition for the same extension record.

Keep at least two tested offline backups of the key, password, and fingerprint. Losing them makes in-place updates impossible.

From a clean, committed source tree, the maintainer release gate is:

```bash
./scripts/signed-release.sh
```

This fail-closed release path runs both unit-test variants and lint, rebuilds from clean outputs, and verifies the signature, pinned certificate, ZIP alignment, package and SDK identity, compiled build identity, packaged licence notices, non-debuggable status, permission allowlist, disabled Android backup, public update metadata, and exact Git source revision. Only after every check passes does it write the APK, checksum, signer fingerprint, release information, update manifest, icon, third-party notices, and Apache licence to `dist`.

Never publish an APK from a build directory. Publish the verified files from `dist`, attach clear compatibility and known-limit notes, and keep the same signer for every public update.

`./scripts/publish-release.sh --check` performs a read-only publication preflight. The maintainer-only `--publish` route rebuilds the signed release from the clean pushed commit immediately before creating the GitHub release, so it never trusts an older `dist` directory as the final candidate.

## Vendored Karoo SDK

The supported Hammerhead karoo-ext 1.1.8 runtime source is vendored under `karoo-ext`. Preserve its Apache licence and update [`karoo-ext/UPSTREAM.md`](karoo-ext/UPSTREAM.md) whenever that source changes.
