# Project build references

The canonical outside-sandbox Android build prerequisites and commands are in the repository's `docs/ANDROID-APK-BUILD-OUTSIDE-SANDBOX.md`. Read that guide whenever preparing an APK. Do not duplicate its version pins here; verify versions from the current `android/Nagram` Gradle wrapper/build files before each build.

The current source snapshot expects Gradle 8.11.1, Android Gradle Plugin 8.10.1, compile SDK/build tools 36/36.0.0, NDK 27.2.12479018, and CMake 3.22.1. The Xiaomi Wearable SDK AAR/JAR, signing keystore, Google Services JSON, and local properties are intentionally not stored in Git.
