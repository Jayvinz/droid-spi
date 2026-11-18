# The demo's release build does not enable minification (see app/build.gradle.kts);
# this file is an illustrative sample consumers can reuse directly.
# Dynamic module implementations are referenced by `ldc` constants in the generated registry,
# so R8 sees the references and keeps the classes automatically.
# However, R8 in base reports these "not yet installed" module classes as missing; allow them as needed:
-dontwarn com.example.feature.camera.**
