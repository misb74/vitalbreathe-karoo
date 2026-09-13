# Hammerhead karoo-ext 1.1.8

This directory vendors the runtime source of Hammerhead's `karoo-ext` library so
VitalBreathe can be built without credentials for GitHub Packages.

- Upstream: <https://github.com/hammerheadnav/karoo-ext>
- Release tag: `1.1.8`
- Commit: `10afb4728d31303f0b970cc7bf58f0cf7bcb8896`
- Licence: Apache License 2.0; see `LICENSE` in this directory.

The files under `src/main` reproduce upstream `lib/src/main` at that commit;
four text files only gain a final newline. `KarooSystemService` uses ordinary
collection loops instead of `ConcurrentHashMap.forEach`, preserving the same
behaviour on the library's Android 23 minimum after newer Android lint began
enforcing that Java method's Android 24 API level.

The local `build.gradle.kts` removes upstream's documentation, test,
publishing, and GitHub Packages configuration, but keeps the runtime namespace,
minimum SDK, AIDL generation, serialization plugin, dependencies, and library
version.

To audit the vendored source against an upstream clone:

```bash
git -C /path/to/karoo-ext checkout 1.1.8
diff -ru --exclude=build.gradle.kts \
  /path/to/karoo-ext/lib/src/main \
  ./karoo-ext/src/main
```

The expected source diff is the compatibility loop above plus the four final
newlines.
