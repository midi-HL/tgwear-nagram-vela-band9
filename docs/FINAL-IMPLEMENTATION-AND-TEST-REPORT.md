# TG Wear v2 — implementation and validation report

**Snapshot:** 2026-09-26. **Vela:** version 0.1.4 (`versionCode: 5`). **Android:** Nagram source snapshot with TG Wear integration. The user's reference GitHub repositories were not modified.

## Current implementation

TGW/2 runs above Android Xiaomi Wearable SDK MessageApi ↔ Vela `system.interconnect`; it is independent of SimpleFetch and does not use `SF_*` frames. Frames contain UTF-8 bytes with a 2 KiB initial chunk target, session hello/helloAck, one-in-flight ACK/NACK, per-chunk and total CRC32, commitAck, bounded retries, bounded queues/reassembly, and 128 KiB / 64-chunk logical limits. App-level RPC/events use request IDs; send confirmation uses local message ID plus bounded operation-ID bookkeeping. Private-dialog listings and history are paged asynchronously; the watch stores a bounded display window and labels media rather than receiving raw media payloads.

## Validation recorded

| Level | Result |
|---|---|
| Vela Toolkit 2.0.5 build | Success; [debug RPK](../artifacts/vela/com.hrk.tgwear.debug.0.1.4.rpk), 398,462 bytes |
| Vela protocol/store Node tests | 8/8 passed; rerun with `node --experimental-default-type=module --test test/protocol.test.js test/store.test.js` from `vela/tgwear-quickapp/` |
| Dual-end static acceptance | 44/44 passed against the prior full source layout |
| Repository sanity checks | 10/10 passed; run `python3 scripts/verify-repository.py` |
| Android TG Wear Java syntax parse | 16/16 passed |
| Patch application dry run | Passed against the matching supplied baseline archive |
| New Android APK build | Not performed; the prior build environment lacked the Android SDK/NDK and Xiaomi Wearable SDK |
| OPPO F9 / Xiaomi Band 9 device test | Not performed |

The Vela build log is [`artifacts/vela/build-0.1.4.log`](../artifacts/vela/build-0.1.4.log). Repository sanity checks and independent protocol/store tests are not APK build or hardware validation.

## Outstanding blockers and next step

Follow [`ANDROID-APK-BUILD-OUTSIDE-SANDBOX.md`](ANDROID-APK-BUILD-OUTSIDE-SANDBOX.md) to provision the pinned Android toolchain, licensed Xiaomi Wearable SDK AAR/JAR, matching local signing material, and any required Firebase configuration. The Android package must match Vela; its signer must match the Vela certificate and the Nagram native integrity signer pin. Then build the new Android APK and test the pair on OPPO F9/Band 9: phone challenge/watch confirmation, private dialog/history paging, send confirmation, long Unicode messages, ACK/retry/reconnect, duplicates and bounded memory.

The 2 KiB frame size remains a starting value, not a verified Xiaomi SDK maximum. Operation-ID deduplication is bounded and process-local; it does not promise cross-process exactly-once behavior. See [`TGW2-PROTOCOL-AND-REFACTOR.md`](TGW2-PROTOCOL-AND-REFACTOR.md).
