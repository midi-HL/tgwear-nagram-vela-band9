# Building the TG Wear Android APK outside the sandbox

This guide builds the phone-side APK from this repository's Nagram snapshot and TG Wear integration. It does not claim a build succeeded until Gradle, Android SDK/NDK, the Xiaomi Wearable SDK, signing material, and Firebase configuration have all been checked.

## 1. Build inputs

The current Nagram build files pin or request:

- Gradle Wrapper: **8.11.1** (`gradle/wrapper/gradle-wrapper.properties`)
- Android Gradle Plugin: **8.10.1** (`build.gradle`)
- Android `compileSdk`: **36**, Build Tools **36.0.0**
- NDK: **27.2.12479018**
- CMake: **3.22.1**
- Java source/target: **11**; use a supported JDK 17+ (JDK 21 is a suitable local choice)
- Application module/task path: `:TMessagesProj`; package ID in the TG Wear overlay: `com.hrk.tgwear`
- Wearable client library: Android Gradle currently expects local `TMessagesProj/libs/*.aar` or `*.jar`; the checked-in source snapshot does not include Xiaomi's proprietary Wearable SDK artifact.

Install Android Studio or the Android command-line tools. In SDK Manager install Android SDK Platform 36, Android SDK Build-Tools 36.0.0, NDK 27.2.12479018, and CMake 3.22.1. Set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and put `$ANDROID_HOME/platform-tools` on `PATH`. Let the Gradle wrapper download its pinned Gradle distribution. Do not upgrade Gradle/AGP/NDK as a first troubleshooting step.

> Java `sourceCompatibility 11` is not the Gradle runtime version. Run Gradle with a JDK supported by AGP 8.10.1; JDK 21 avoids relying on an old runtime.

## 2. Get the complete working tree

Clone this private repository and check out the desired commit. It contains a complete source snapshot under `android/Nagram/`; the TG Wear files and customized `TMessagesProj/build.gradle` are already overlaid. Do not substitute the older APK in `artifacts/` for a new build.

On Windows, use PowerShell with Android Studio's configured SDK and run `gradlew.bat`. On Linux/macOS, use the commands below from `android/Nagram/`.

## 3. Install the Xiaomi Wearable SDK dependency

Obtain the Xiaomi Wearable SDK 1.4 AAR/JAR from a source you are authorized to use (for example, the SDK package supplied with your official Xiaomi integration account). Put the required file(s) in `android/Nagram/TMessagesProj/libs/`. Check that the SDK version/API matches the imports used by `XiaomiWearConnection.java` and `ReliableWearConnection.java`.

The repository deliberately does **not** redistribute that proprietary AAR. Do not scrape another project or copy an artifact without checking its license and integrity. The Gradle declaration `implementation fileTree(dir: "libs", include: ["*.aar", "*.jar"])` / compile-only file tree must resolve to the Xiaomi SDK classes at compile time.

## 4. Supply local-only configuration and signing

Never commit credentials. Before building, make available:

1. A TG Wear Android signing keystore whose certificate matches the Vela RPK signer for `system.interconnect` **and** matches the SHA-1 signer pin compiled into `android/Nagram/TMessagesProj/jni/integrity/integrity.cpp`. Never replace the signing identity casually; changing it requires an explicitly coordinated Vela certificate and integrity-pin update, followed by verification.
2. The signing values as environment variables (or in an ignored local Gradle config):
   - `TGWEAR_KEYSTORE_PATH` — path to the keystore file
   - `TGWEAR_KEYSTORE_PASS`
   - `TGWEAR_KEY_ALIAS`
   - `TGWEAR_KEY_PASS`
3. A suitable local `TMessagesProj/google-services.json` if the Google Services Gradle task is required. Its registered Android app ID must match `com.hrk.tgwear`. Never commit this JSON. If the Firebase task is intentionally unnecessary for a debug build, try excluding only the generated `processDebugGoogleServices` task and confirm the app still compiles; do not silently change runtime notification behavior.
4. A local `local.properties` for SDK path and any project-required Telegram API/build settings. This is machine/user-specific and must stay untracked.

The repository build configuration reads signing fields from the environment or local properties and contains no embedded keystore password. If Gradle reports missing signing values, stop and obtain the matching local credentials; do not generate a different signer if the installed Vela package expects a matching certificate.

## 5. Build an ARM64 debug APK

```bash
cd android/Nagram
export ANDROID_HOME="$HOME/Android/Sdk"     # change to your installed SDK path
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="/path/to/jdk-21"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
export NATIVE_TARGET=arm64-v8a

./gradlew --version
./gradlew :TMessagesProj:tasks --all
./gradlew --no-daemon :TMessagesProj:assembleDebug
```

The Gradle configuration uses `NATIVE_TARGET` to restrict the ABI split. To build both configured ABIs, unset it and use `assembleDebug`. Do not set `NAGRAM_BUILD_ARGS=skip_buildCMakeDebug` for the first functional build; that option may omit native compilation.

Find and inspect the outputs:

```bash
find TMessagesProj/build/outputs/apk -type f -name '*.apk' -print
sha256sum TMessagesProj/build/outputs/apk/**/*.apk
```

The configured APK output naming can vary by Gradle/AGP and ABI. Confirm the package and signer rather than relying on the filename:

```bash
$ANDROID_HOME/build-tools/36.0.0/aapt dump badging <apk> | head
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs <apk>
```

Compare the signer certificate digest to the certificate used for the RPK. For debug/device testing, distribute the matching RPK and APK pair together. Keep release signing separate and protected.

## 6. Vela build and tests

```bash
cd vela/tgwear-quickapp
npm ci
node --experimental-default-type=module --test test/protocol.test.js test/store.test.js
npm run build
find dist -type f -name '*.rpk' -size +0c -print
sha256sum dist/*.rpk
```

Vela signing PEMs are excluded. Obtain the same authorized signing pair used by the phone app through a secure local channel or use the authorized AIoT signing setup. Do not commit PEM files.

## 7. Device validation order

1. Verify APK package ID, RPK package ID, and signer certificate compatibility.
2. Install/replace the phone APK and Vela RPK on the OPPO F9 + Xiaomi Band 9. If replacing a signer, uninstall the old app first; app data may be deleted.
3. Open the watch connection diagnostics; trigger the phone-side challenge and confirm it on the watch.
4. Confirm TGW/2 state `transport-up → hello/helloAck → ready` before trying RPC/history.
5. Test 1:1 private dialogs, small history pages, long Chinese/emoji text, send confirmation, reconnect, duplicate frame/event handling, and bounded memory.
6. Capture Android `adb logcat` and watch diagnostics if a test fails. Redact message content, account identifiers, tokens, and personal data before sharing logs.

## Failure triage

- `Could not find ...` for Xiaomi classes: the local SDK AAR/JAR is absent or incompatible.
- SDK location not found: fix `local.properties`/`ANDROID_HOME`; do not commit them.
- `processDebugGoogleServices` fails: provide a local configuration for the actual app ID, or explicitly skip that optional task for a debug-only build and verify runtime effects.
- Signing error / interconnect diagnosis fails: compare certificates on both artifacts; package equality alone is insufficient.
- `externalNativeBuild`/CMake error: verify NDK and CMake versions and presence of all source/prebuilt inputs; capture the first actual CMake diagnostic.
- Build success is not device acceptance. Report APK compile, install, handshake, private-chat sync, reconnection, and memory results as separate stages.
