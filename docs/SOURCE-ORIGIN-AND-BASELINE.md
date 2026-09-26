# Source origin and repository boundaries

- `android/Nagram/` is based on the Nagram source snapshot provided in project resource `Nagram-main.zip`, with the TG Wear Android overlay from `TGWear-双端源码与构建配置-20260925.zip` applied. The snapshot omits machine credentials and generated app packages.
- Two upstream WebRTC unit-test-only files embedding synthetic PEM private-key fixtures were omitted from the snapshot; neither is used to build or run the Android app. Runtime and app production sources were retained.
- `vela/tgwear-quickapp/` is the TG Wear Vela app source with the current TGW/2 implementation and tests. Build and signer private keys are not included.
- User-uploaded diagnostics (`mi.zip`, logcat dumps, screenshots, phone/watch logs) are deliberately not copied to this repository because they can contain private message/account/device data.
- Older APK/RPK inputs and complete input archives are deliberately not mirrored. The current RPK is retained in `artifacts/vela/`; no Android APK for this TGW/2 revision has been built.
- `midi-HL/wear-browser-band9` and `midi-HL/SimpleFetch-Android-Bridge` are read-only references only; neither is edited or duplicated here.
- Preserve Nagram's included GPL license and upstream branding requirements. Xiaomi Wearable SDK binaries must be obtained from an authorized source and are excluded unless redistribution rights are established.
