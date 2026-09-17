# CTS Selection Share

Adds a **Share** button to Google Circle to Search through the standard Zygisk
API. It shares the selected crop with the Android system sharesheet.

## Compatibility

Android 11+, arm64-v8a, Google App 17.x, and a Zygisk API v4 implementation.
Limited fallback support is included for Google App 16.x.

Tested on OnePlus 15 with Android 16 and Pixel 10 Pro Fold with Android 17.

## Install

1. Install the release ZIP in your root/module manager.
2. Reboot.
3. Trigger Circle to Search, select an image, and tap **Share**.

Shared images use Google App's private cache and are not added to the gallery.
They are removed after ten minutes or when the Google App process next starts.

## Build

Run `build.ps1`. It requires Android SDK 36.1, Build Tools 37.0.0, JBR 21,
and Android NDK r29. The ZIP is written to `build/`.

GitHub Actions builds and checks the ZIP on pushes and pull requests. Its
artifact is a build check, not an on-device CTS test.

Diagnostics: `adb logcat -s CTSShareZygisk`

License: `GPL-3.0-only`.
