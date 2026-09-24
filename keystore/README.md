# Keystores

These two key stores are **demo keys for this repository** and are committed on purpose so that a
fresh clone can produce signed artifacts with no setup:

| File | Alias | Password | Used by |
|------|-------|----------|---------|
| `morsecode-release.jks` | `morsecode` | `morsecode` | release APK, AAB |
| `debug.keystore` | `androiddebugkey` | `android` | debug APK |

**Do not ship to a store with these.** Generate your own key and point the builds at it:

```bash
keytool -genkeypair -v -keystore keystore/my-release.jks -alias mykey \
        -keyalg RSA -keysize 4096 -validity 10000
```

* Gradle: create `keystore/keystore.properties` (git-ignored) with `storeFile`, `storePassword`,
  `keyAlias`, `keyPassword`, or set the `MC_KEYSTORE_FILE` / `MC_KEYSTORE_PASSWORD` /
  `MC_KEY_ALIAS` / `MC_KEY_PASSWORD` environment variables (CI secrets).
* Offline script: `python3 tools/offline_build.py --keystore keystore/my-release.jks --ks-pass …`.

`tools/offline_build.py` recreates both demo keys automatically if they are missing, so neither
build path can ever produce an unsigned APK.
