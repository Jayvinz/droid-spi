#!/usr/bin/env bash
# Builds both AGP compatibility fixtures and asserts that the generated SPI configuration
# reaches each APK. Run from anywhere; the script resolves the repository root itself.
#
# Usage: bash scripts/verify-compat.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

echo "==> Publishing the framework to local-repo"
./gradlew :annotation:publish :runtime:publish :plugin:publish --configure-on-demand --console=plain

for fixture in compat/agp8 compat/agp80; do
  echo "==> $fixture"
  (
    cd "$fixture"
    ./gradlew :app:assembleDebug --console=plain
  )

  apk="$fixture/app/build/outputs/apk/debug/app-debug.apk"
  manifest="$fixture/app/build/generated/spi/debug/services.txt"
  cache="$fixture/app/build/generated/spi/debug/parsed.txt"

  [ -f "$apk" ] || { echo "FAIL: $apk was not produced"; exit 1; }
  [ -f "$manifest" ] || { echo "FAIL: $manifest was not produced"; exit 1; }
  [ -f "$cache" ] || { echo "FAIL: $cache was not produced"; exit 1; }

  count="$(unzip -p "$apk" classes.dex | grep -c 'top/jayvin/android/spi/generated/ServiceConfiguration' || true)"
  if [ "$count" -ne 1 ]; then
    echo "FAIL: $fixture: generated class count in APK = $count (expected 1)"
    exit 1
  fi

  grep -q 'demo\.JavaImpl' "$manifest" || { echo "FAIL: $fixture: Java provider missing"; exit 1; }
  grep -q 'demo\.KotlinImpl' "$manifest" || { echo "FAIL: $fixture: Kotlin provider missing"; exit 1; }

  echo "OK: $fixture"
done

echo "All compatibility fixtures passed."
