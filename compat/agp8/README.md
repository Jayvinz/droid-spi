# AGP 8 compatibility fixture

The main build runs on AGP 9. This fixture builds a minimal application with **AGP 8.7.3,
Gradle 8.9 and Kotlin 2.0.21**, consuming the framework from `local-repo`. It proves that one
plugin artifact still serves the 8.x line and that the published `runtime` AAR remains
consumable by projects that have not moved to Kotlin 2.4.

Both a Java provider and a Kotlin provider are declared, so the scan task exercises
`variant.sources.java`, `variant.sources.kotlin` and the generated configuration class, which
the check below looks for inside the APK.

## Run

From the repository root, publish the framework first:

```bash
./gradlew :annotation:publish :runtime:publish :plugin:publish --configure-on-demand
```

Then build the fixture (it has its own Gradle 8.9 wrapper):

```bash
cd compat/agp8
./gradlew :app:assembleDebug
unzip -p app/build/outputs/apk/debug/app-debug.apk classes.dex \
  | grep -c 'top/jayvin/android/spi/generated/ServiceConfiguration'   # expected: 1
```

On Windows use `gradlew.bat`. The fixture reads `sdk.dir` from `local.properties` like any
Android project, or from `ANDROID_HOME`.
