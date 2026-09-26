# TG Wear — Nagram ↔ Xiaomi Band 9 Vela

**Private project snapshot.** This repository groups the Nagram Android phone companion, Xiaomi Band 9 Vela QuickApp, the TGW/2 transport refactor, build artifacts, and agent handoff material. The GitHub reference repositories named by the user are not modified or mirrored here.

## Start here

1. Read [`NEXT_AGENT_HANDOFF.md`](NEXT_AGENT_HANDOFF.md) before editing or building.
2. For phone APK compilation outside the sandbox, follow [`docs/ANDROID-APK-BUILD-OUTSIDE-SANDBOX.md`](docs/ANDROID-APK-BUILD-OUTSIDE-SANDBOX.md).
3. For protocol details and acceptance boundaries, read [`docs/TGW2-PROTOCOL-AND-REFACTOR.md`](docs/TGW2-PROTOCOL-AND-REFACTOR.md) and [`docs/FINAL-IMPLEMENTATION-AND-TEST-REPORT.md`](docs/FINAL-IMPLEMENTATION-AND-TEST-REPORT.md).
4. Use the reusable agent workflow at [`skills/tgwear-v2-nagram-vela-handoff/SKILL.md`](skills/tgwear-v2-nagram-vela-handoff/SKILL.md).

## Repository map

| Path | Contents |
|---|---|
| `android/Nagram/` | Nagram source snapshot plus the TG Wear Android integration. Gradle wrapper and native inputs are included; personal configuration, signing keys, Google service configs, caches, and APKs are excluded. |
| `vela/tgwear-quickapp/` | Vela QuickApp source, build scripts, lockfile, and protocol/store tests. Signing PEMs and generated folders are excluded. |
| `artifacts/vela/` | Current debug RPK, its build log, and a clearly marked older RPK archive. |
| `patches/` | TG Wear source patch and legacy interconnect patch. |
| `docs/` | Protocol, build, device testing, security, implementation report, and historical handoff notes. |
| `skills/` | Portable skill package for future coding agents. |
| `scripts/` | Local build/validation helpers. |

## Current state

- Vela `0.1.4` (`versionCode: 5`) built successfully; protocol/store Node tests 8/8; dual-end static checks 44/44.
- Android TG Wear Java source passed syntax parsing, but this repository has **not yet produced a new Android APK**. See the build guide for the remaining Xiaomi Wearable SDK and local signing/configuration inputs.
- No OPPO F9 / Band 9 device test has been performed for TGW/2. Do not treat the previous Nagram APK baseline as containing this refactor.

## Privacy and source policy

This repo is private by explicit request. It intentionally does not contain device logs/screenshots, original input archives, `local.properties`, `google-services.json`, APK baselines, Android/Vela private signing keys, passwords, or build caches. Never commit those files. See [`docs/SECURITY-AND-REPRODUCIBILITY.md`](docs/SECURITY-AND-REPRODUCIBILITY.md).

## License and upstream attribution

The Android tree is a snapshot of [NextAlone/Nagram](https://github.com/NextAlone/Nagram), based on the source archive supplied for this task. Review its included license and branding requirements before redistribution. TG Wear-specific changes are the overlay in this repository; retain upstream license notices.
