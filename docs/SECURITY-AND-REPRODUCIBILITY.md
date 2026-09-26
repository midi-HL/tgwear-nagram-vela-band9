# Security and reproducibility policy

## Never commit

- Android/Vela signing keys or certificates containing private keys (`*.jks`, `*.keystore`, `*.pem`, `*.p12`), passwords, tokens, `local.properties`, `.env` files.
- Firebase/Google service configuration (`google-services.json`) or external-service credentials.
- User/device logs, screenshots, Telegram message content, phone numbers, account identifiers, or diagnostic archives.
- `node_modules`, Android/Gradle build caches, Android APK/AAB build outputs, source input archives, and IDE-local state.

Private GitHub visibility is not a reason to commit secrets: collaborator permissions, accidental visibility changes, forks, and downloadable history create ongoing risk. If a secret is committed, rotate it and remove it from the full history; a later deletion commit is insufficient.

## Local-only inputs

Keep device-specific configuration and credentials outside the repository. The build guide documents environment variables and local-only config placement. Add only sanitized templates with fake values if a template is needed.

## Reproducibility

Record the Git commit, Gradle/AGP/JDK/SDK/NDK/CMake versions, Vela Toolkit/Node version, source of the proprietary Xiaomi AAR, exact commands, build result, output hash, device model/firmware, and test stage. Do not label a compile as a device pass.

## Source and licensing

The Nagram source snapshot retains upstream files and notices. Preserve all licenses and review upstream requirements before distributing derivative APKs. The Xiaomi Wearable SDK artifact is intentionally not mirrored here; acquire it from an authorized source and follow its redistribution terms.
