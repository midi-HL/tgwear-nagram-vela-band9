# Next-agent handoff — TG Wear / TGW/2

**Status snapshot:** 2026-09-26. Read this file before changing source or generating artifacts.

## Goal

Make private 1:1 Telegram messages from Nagram reliably available on a Xiaomi Band 9 Vela QuickApp using the companion app and **TGW/2**, not SimpleFetch. The user explicitly said not to modify `midi-HL/wear-browser-band9` or `midi-HL/SimpleFetch-Android-Bridge`; use them only as read-only references.

## Current work completed

- Refactored TG Wear Android bridge/handlers and Vela QuickApp transport/state/history layers.
- TGW/2 uses UTF-8 byte frames (default 2 KiB), one-frame ACK window, session handshake, per-chunk and whole-transfer CRC32, `commitAck`, bounded retries, 128 KiB/64-chunk logical ceiling, and bounded in-memory reassembly. It is independent of `SF_*`.
- Added Android `ReliableWearConnection.java`, Vela `src/utils/protocol.js`, and Vela Node tests.
- Fixed app-owned cross-bundle Vela state facade; added async dialog/history paging, event upsert and operation-ID plumbing.
- Vela debug RPK 0.1.4 / versionCode 5 built and included in `artifacts/vela/`.
- Last recorded validation: protocol/store Node tests 8/8; dual-end static checks 44/44; Android TG Wear Java syntax parsing 16/16; source patch dry-run passed on the matching baseline.

## Do not overstate

- The current repository source has **not** yet produced a new Android APK. The supplied workspace lacked Android SDK/NDK, a module-local Xiaomi Wearable SDK AAR/JAR, and the complete local build configuration.
- No OPPO F9 / Band 9 installation or TGW/2 real-device handshake was completed.
- 2 KiB is a conservative starting frame size, not a verified Xiaomi SDK maximum.
- `operation_id` response/event correlation and deduplication are bounded to current process state; it is not cross-process exactly-once Telegram delivery.
- Earlier APK/RPKs in artifact archives are historical baselines, not TGW/2-compatible builds.

## Next steps (in order)

1. Follow `docs/ANDROID-APK-BUILD-OUTSIDE-SANDBOX.md` on a machine with Gradle wrapper 8.11.1, AGP 8.10.1, compileSdk 36 / build tools 36.0.0, NDK 27.2.12479018, CMake 3.22.1, a supported JDK, and the properly licensed Xiaomi Wearable SDK 1.4 AAR/JAR.
2. Obtain local-only Android/Vela signing material and a matching Firebase configuration if required. Verify the APK and RPK signer digests match before installing. Never upload those files to GitHub or logs.
3. Build the debug ARM64 APK using `scripts/build-android-arm64.sh` after required inputs are provisioned. Record the exact commands, SDK/NDK versions, artifact SHA-256, and Gradle output. Fix actual compile errors before device tests.
4. Install matched APK + RPK on OPPO F9 and Xiaomi Band 9. Test phone-originated challenge with watch confirmation; capture transport and TGW/2 handshake states.
5. Test private-chat dialog listing, paged history, long Unicode messages, outgoing send confirmation, duplicate events, reconnect, bounded memory, and timeout/retry.
6. If failure: collect redacted Android `adb logcat`, watch-side diagnostics and protocol counters. Locate first failure layer (SDK/node/auth/route/interconnect → TGW/2 session → chunk ACK/commit → RPC/Telegram history → UI). Avoid dumping message bodies or credentials.
7. Fix and rerun tests/build, bump version only when user-facing behavior changes; update changelog and checksums.

## Repository map

- `android/Nagram/`: full Nagram source snapshot with the TG Wear overlay; secrets and local configuration removed.
- `vela/tgwear-quickapp/`: Vela app source, build lockfile, Node tests; signing keys excluded.
- `artifacts/vela/`: current RPK/build log and clearly labelled older artifact archive.
- `patches/`: unified patch and older baseline patch.
- `docs/`: implementation report, protocol design, build and device notes; `docs/history/` is historical.
- `skills/tgwear-v2-nagram-vela-handoff/`: portable AI-agent skill.

## Security/licensing

The repository is private, but still treat it as source, not a secret vault. Keep keystores, PEMs, passwords, `local.properties`, `google-services.json`, API keys, personal diagnostic logs/screenshots, account identifiers and message text outside Git. Preserve Nagram's upstream license/branding notices. Obtain Xiaomi SDK files through an authorized channel and check redistribution permissions.
