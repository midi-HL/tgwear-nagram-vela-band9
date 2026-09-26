# TG Wear / TGW/2 下一个 AI 交接文档

**状态日期：2026-09-26。** 接手后先看本文件、根目录 `README.md` 和 `docs/ANDROID-APK-BUILD-OUTSIDE-SANDBOX.md`，不要把过去 sandbox 的环境当成当前可用环境。

## 项目目标

让 Nagram 手机端可靠地把 Telegram **一对一私聊**同步到 Xiaomi Band 9 Vela 快应用。手机仍是 Telegram 数据来源；链路为 Xiaomi Wearable SDK MessageApi ↔ Vela `system.interconnect`；应用层使用项目自定义 TGW/2。不要把 SimpleFetch 的 `SF_*` 混入 TGW/2。

用户明确要求：不得改动 `midi-HL/wear-browser-band9` 和 `midi-HL/SimpleFetch-Android-Bridge` 两个参考 GitHub 仓库；它们只可只读参考。

## 已完成

- 重构 Nagram Android TG Wear bridge/handlers 和 Band 9 Vela QuickApp 的 transport/state/history 逻辑。
- TGW/2 具备 UTF-8 字节分片（默认 2 KiB）、session hello/helloAck、单窗口 chunk ACK/NACK、CRC32、commitAck、有限重试、128 KiB 与 64 chunks 上限、有限重组/队列。
- Vela 的 app-owned 全局 facade 修复 page bundle 状态隔离；历史按页异步回填；通过 operation ID 和本地消息 ID 回传发信确认。
- 已构建 Vela debug RPK `0.1.4` / `versionCode 5`，位于 `artifacts/vela/`。
- 上轮验证记录：Node 测试 8/8、双端静态验收 44/44、TG Wear Java 语法解析 16/16、源码补丁 dry-run 成功。

## 不能夸大的验证边界

- 本仓库尚没有新 Android APK。本次工作环境缺 Android SDK/NDK、Xiaomi Wearable SDK AAR/JAR、本地签名与 Firebase 配置，未能执行 Gradle APK build。
- 没有完成 OPPO F9 + Band 9 安装、手机发挑战/手环确认、私聊同步或性能测试。
- 2 KiB 是起始帧尺寸，不是 Xiaomi 官方最大值或真机确认值。
- operation_id 是有限进程内去重，不能保证 app 重启后 Telegram 副作用 exactly-once。
- `artifacts/vela/archive/` 下老 RPK 仅作历史存档，不代表与 TGW/2 Android 对端兼容。

## 下一步顺序

1. 按 Android 构建说明准备 JDK、Gradle wrapper、AGP、SDK 36、Build Tools 36.0.0、NDK 27.2.12479018、CMake 3.22.1。
2. 从授权来源取得 Xiaomi Wearable SDK 1.4 AAR/JAR，放入本地 `android/Nagram/TMessagesProj/libs/`；不得未经许可从其他项目复制或入库。
3. 通过安全本地环境变量/忽略文件提供与 Vela RPK 对应的 Android keystore、证书、别名/密码、`local.properties`，以及所需的 `google-services.json`。APK 包名须与 Vela 一致，签名还必须匹配 Vela 证书及 Nagram JNI signer pin。任何密钥都不要提交。
4. 跑 `scripts/verify-repository.py`、Vela Node tests 和 `scripts/build-android-arm64.sh`。记录 commit、工具版本、首个 Gradle 错误、APK SHA-256；build success 仍不是设备验收。
5. 在 OPPO F9 与 Band 9 安装匹配的 APK + RPK，测试手机发起 challenge、手环确认；诊断应到 `transport-up → hello/helloAck → ready`。
6. 逐项测 1:1 私聊列表、分页历史、长中文/emoji、发信确认/重复 upsert、断线重连、ACK/commitAck/retry 和手环内存上限。
7. 失败时只抓最早失败层：节点/权限/route、Xiaomi SDK listener、interconnect、TGW/2 握手、frame ACK/CRC、RPC/Telegram history、Vela UI。保存 redacted `adb logcat` 和手环诊断，去除消息正文、手机号、账号标识和 token。
8. 修复后重跑测试和 build；仅当协议或 app 行为变化才升级版本，并更新报告与 SHA-256。

## 仓库结构

- `android/Nagram/`：Nagram 完整源码快照及 TG Wear Android 改动；无本地私钥、Google config、个人 SDK 路径。
- `vela/tgwear-quickapp/`：Vela QuickApp 源码/锁文件/Node 测试，无签名私钥。
- `artifacts/vela/`：0.1.4 RPK、构建日志和旧产物存档。
- `patches/`：源码补丁。
- `docs/`：TGW/2 设计、构建、设备诊断、测试结果与历史交接。
- `skills/`：项目 agent Skill 副本；完整可导入 skill 在本任务交付的独立文件中。

## 安全与许可证

仓库虽为 private 仍不得用作密钥保险箱。禁止提交 `.jks/.pem/.p12`、密码、`local.properties`、`google-services.json`、token、用户日志截图和 Telegram 内容。保留 Nagram 上游许可证/品牌要求。小米 Wearable SDK 由有权限的开发者本地获取，并遵守其再分发许可。
