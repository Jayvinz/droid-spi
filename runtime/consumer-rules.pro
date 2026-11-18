# runtime directly new-s the generated ServiceConfiguration; allow the class to be missing
# when the app has not applied the plugin. R8 sees the new reference and keeps it when present,
# so no -keep rule is needed.
-dontwarn top.jayvin.android.spi.generated.ServiceConfiguration

# Implementation classes from dynamic modules are referenced by ldc constants in the registry,
# so R8 sees the references and no extra rule is needed. However, R8 in base reports these
# "not yet installed" dynamic module classes as missing; consumers should add -dontwarn per
# their own module names, for example:
#   -dontwarn com.example.feature.camera.**
