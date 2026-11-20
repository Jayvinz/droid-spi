# The demo's release build does not enable minification (see app/build.gradle.kts).
# Droid-spi needs no R8 configuration: dynamic module implementations are referenced by `ldc`
# constants in the generated registry, so R8 sees the references and keeps the classes like
# ordinary code. No extra R8 keep configuration is required.
