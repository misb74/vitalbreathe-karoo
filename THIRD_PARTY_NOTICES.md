# Third-party notices

VitalBreathe builds on open-source work from several projects. This notice is a
guide to attribution; the referenced licence texts are authoritative.

## K-Breathe

VitalBreathe is derived from
[K-Breathe](https://github.com/gloscherrybomb/k-breathe), originally copyright
2025 gloscherrybomb and licensed under the MIT License. VitalBreathe's 2026
modifications are copyright Moray Brown and are distributed under the same
licence. The complete MIT text is in [LICENSE](LICENSE).

## Hammerhead karoo-ext

The `karoo-ext` directory vendors the runtime source from Hammerhead karoo-ext
1.1.8 at commit `10afb4728d31303f0b970cc7bf58f0cf7bcb8896`.

- Project: <https://github.com/hammerheadnav/karoo-ext>
- Copyright: 2024-2026 SRAM LLC, as stated in the vendored source files
- Licence: Apache License 2.0
- Provenance and local-build differences: [karoo-ext/UPSTREAM.md](karoo-ext/UPSTREAM.md)
- Complete licence text: [karoo-ext/LICENSE](karoo-ext/LICENSE)

The checked upstream revision contains a `LICENSE` file and no `NOTICE` file.

## Runtime libraries

The release APK also includes code from these directly selected library
families and their transitive dependencies:

| Project | Selected version | Licence |
| --- | --- | --- |
| AndroidX Core | 1.13.1 | Apache License 2.0 |
| AndroidX AppCompat | 1.7.0 | Apache License 2.0 |
| AndroidX Activity Compose | 1.9.3 | Apache License 2.0 |
| Jetpack Compose UI | 1.7.4 | Apache License 2.0 |
| Jetpack Compose Material 3 | 1.3.0 | Apache License 2.0 |
| Kotlin standard library | 2.4.20 | Apache License 2.0 |
| kotlinx.coroutines | 1.8.1 | Apache License 2.0 |
| kotlinx.serialization | 1.6.3 | Apache License 2.0 |
| Timber | 5.0.1 | Apache License 2.0 |

AndroidX and Jetpack sources are available from the
[Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/).
Kotlin is available from [JetBrains](https://github.com/JetBrains/kotlin), while
kotlinx.coroutines and kotlinx.serialization have their own
[coroutines](https://github.com/Kotlin/kotlinx.coroutines) and
[serialization](https://github.com/Kotlin/kotlinx.serialization) repositories.
Timber is available from
[Jake Wharton's repository](https://github.com/JakeWharton/timber).

The Apache License 2.0 text supplied in
[karoo-ext/LICENSE](karoo-ext/LICENSE) also provides the complete terms for the
Apache-licensed libraries listed above.

## Development and test tools

The source build uses Gradle 8.7, Android Gradle Plugin 8.5.2, and the standalone
Android Lint 9.3.1 analyser, all under the Apache License 2.0. Tests use JUnit
4.13.2 under the Eclipse Public License 1.0 and Robolectric 4.17 under the MIT
License. Build and test dependencies are not packaged into the release APK.

Exact resolved components and versions are recorded in the committed Gradle
lock files. Their downloaded-file checksums are recorded in
`gradle/verification-metadata.xml`. Update this notice whenever a release
changes a directly selected dependency or vendored component.

## Trademarks

Tymewear and VitalPro are names used by Tyme Wear, Inc. Hammerhead and Karoo are
names used by SRAM LLC. They are used here only to describe compatibility.
VitalBreathe is not produced, supported, certified, or endorsed by either
company.
