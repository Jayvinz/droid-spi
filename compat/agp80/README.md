# AGP 8.0 compatibility fixture

The main build runs on AGP 9 and `compat/agp8` covers the modern 8.x line. This fixture targets
the **AGP 8.0–8.3** window specifically: those releases have no `SourceDirectories.Flat.static`,
so the plugin falls back to `all` when wiring scanned sources. It builds with **AGP 8.0.2,
Gradle 8.2 and Kotlin 2.0.21** and consumes the framework from `local-repo`.

Both a Java provider and a Kotlin provider are declared, and the check below confirms the
generated configuration class reaches the APK.

## Run

From the repository root, publish the framework first:

```bash
./gradlew :annotation:publish :runtime:publish :plugin:publish --configure-on-demand
```

Then build the fixture (it has its own Gradle 8.2 wrapper):

```bash
cd compat/agp80
./gradlew :app:assembleDebug
unzip -p app/build/outputs/apk/debug/app-debug.apk classes.dex \
  | grep -c 'top/jayvin/android/spi/generated/ServiceConfiguration'   # expected: 1
```

On Windows use `gradlew.bat`. The fixture reads `sdk.dir` from `local.properties` like any
Android project, or from `ANDROID_HOME`.
