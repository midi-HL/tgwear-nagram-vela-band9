---
name: tgwear-v2-nagram-vela-handoff
description: Maintain and build the TG Wear Nagram Android companion and Xiaomi Band 9 Vela QuickApp using the project-specific TGW/2 chunked protocol. Use for Android APK build setup, Vela RPK builds, private-message synchronization, operation-id handling, phone/watch challenge debugging, or agent handoff.
---

# TG Wear v2 (Nagram ↔ Xiaomi Band 9) agent skill

Use for the companion sources in this project. Keep TGW/2 independent of SimpleFetch and `SF_*` frames. Never modify the user's `wear-browser-band9` or `SimpleFetch-Android-Bridge` repositories; treat them as read-only references.

## First read

1. Read repository `NEXT_AGENT_HANDOFF.md` for current state and unverified claims.
2. Read `docs/TGW2-PROTOCOL-AND-REFACTOR.md` before changing framing, ACK, limits, retries, session lifecycle, or reassembly.
3. Read `docs/ANDROID-APK-BUILD-OUTSIDE-SANDBOX.md` before an Android build; local signing/config and the proprietary Xiaomi AAR may need authorized provisioning.
4. Read `docs/SECURITY-AND-REPRODUCIBILITY.md` before committing/uploading.
5. Read `docs/DEVICE-CHALLENGE-TROUBLESHOOTING.md` for connection failures.

## Invariants

- Transport is Android Xiaomi Wearable SDK MessageApi ↔ Vela `@system.interconnect`.
- TGW/2 carries UTF-8 byte frames with session hello/helloAck, chunk ACK/NACK, CRC32, commitAck, one outstanding chunk, bounded retries and bounded reassembly.
- Data source remains Nagram/Telegram on the phone. Scope is private user dialogs/history unless explicitly expanded. The watch keeps only a bounded display window and no media binaries/full Telegram TL objects.
- Preserve request IDs and stable `operation_id` for side-effect RPC. Existing idempotency is process-local and bounded; never claim cross-process exactly-once.
- Android application package and Vela manifest package/signing identity must be compatible for interconnect. All keys/passwords stay outside Git.

## Workflow

1. **Recheck environment:** source revision, Gradle wrapper, JDK, Android SDK/build tools, NDK, CMake, Xiaomi AAR/JAR, Firebase config and signing materials. Do not assume a previous sandbox's installation persists.
2. **Trace both endpoints:** transport lifecycle → TGW/2 state → router/RPC/event → data normalization → page/store. Review operation-ID propagation through local temporary message IDs and Telegram confirmation.
3. **Keep edits bounded:** separate transport, protocol, business logic and UI. Preserve private-chat scope, memory limits, backpressure, validation, and explicit timeouts.
4. **Test in stages:** Vela tests/static acceptance → Android compile → RPK/APK package/signature checks → install → challenge handshake → private-chat sync → reconnect/performance. Report each stage independently.
5. **Debug from the earliest failed layer:** SDK node/auth/route, interconnect, TGW/2 handshake, frame ACK/CRC/commitAck, RPC/history, UI. Capture diagnostics and sanitized logcat; exclude message contents, tokens and account identifiers.
6. **Handoff:** record commit, commands, toolchain versions, files changed, tests, hashes, build outputs, missing inputs and hardware outcomes in `NEXT_AGENT_HANDOFF.md`.

## Build references

Read [`references/BUILD.md`](references/BUILD.md) and repository `docs/ANDROID-APK-BUILD-OUTSIDE-SANDBOX.md` for pinned tool versions, Xiaomi SDK provisioning, local signing, build commands, and failure triage. Do not put local keystores, PEMs, `local.properties`, Google service JSON, or tokens into Git.

## Validation commands

From the Vela app directory:

```bash
node --experimental-default-type=module --test test/protocol.test.js test/store.test.js
npm ci
npm run build
```

For Android, use the repository's `scripts/build-android-arm64.sh` after securing the documented local-only inputs. Locate and run the static verifier from the repository's `project-docs` or `docs` path; do not assume it is still at its historical path.

## Stop conditions

Stop and report if a proprietary dependency/license, signing identity, device access, or approval is missing. Do not invent credentials, substitute signer identity, bypass Nagram/Telegram signature checks, claim sandbox Java syntax parsing as an APK build, or claim real-device success without device evidence.
