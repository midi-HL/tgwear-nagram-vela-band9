# TG Wear × Nagram 双端同步器最终交接说明

**更新日期：2026-09-25**  
**项目：Vela app on Xiaomi Band 9**  
**工作区：**`/home/ubuntu/work/vela-tgwear/`

## 1. 当前状态

Android 手机端和 Xiaomi Vela 手环端源码已经完成同步器核心改造；最终 debug 构建已通过，诊断 UI 已进入 APK，手环端诊断页也已打包进 RPK。包名、签名和 JNI pin 已逐项核验。

**重要边界：本环境没有 OPPO F9 或小米手环真机。** 因此目前状态是“源码/构建/包内检查通过，真机启动与真实 Interconnect 通信待验证”，不能把静态验证描述成真机已连接。

## 2. 已交付构建产物

| 文件 | 用途 |
|---|---|
| `Nagram-v12.10.1(1248)-arm64-v8a-debug.apk` | Android ARM64 安装包，OPPO F9 使用此版 |
| `Nagram-v12.10.1(1248)-armeabi-v7a-debug.apk` | Android ARMv7 备用版 |
| `com.hrk.tgwear.debug.0.1.0.rpk` | Xiaomi Vela 手环端 debug 快应用 |
| `TGWear-双端源码交接包-20260925.zip` | 本轮修改相关的双端源码覆盖包与脱敏构建模板 |
| `SHA256SUMS.txt` | APK/RPK SHA-256 校验值 |

产物当前位于：

- Android：`Nagram-tgwear/TMessagesProj/build/outputs/apk/debug/`
- Vela：`tgwear-quickapp-main/dist/`

最终校验值：

```text
arm64 APK  a76a9e50f27b870935462df01e3b454e1a1cdbbf6aea7733900960a593c7fb90
armv7 APK  13df9c16e86779bc918852ab9e957fa00c62281ee1e4abe4b03eb2921952ea87
Vela RPK   b5db43c2e9cb84b12aeeefb8588056db6da5abb95e5e110a898099a51b33b69b
```

以上文件已通过 `sha256sum`；两 ABI 的 APK 均为 `com.hrk.tgwear`，APK 签名 SHA-1 为 `B0FFBED83AB99FB5555736C3AE185F4DA62A49B9`，与 Vela RPK 调试证书一致。两 APK 的 `libtmessages.49.so` 都包含同一 pin，dex 内含 `WearDiagnosticActivity`。

## 3. 功能范围

### Android / Nagram

- 小米 Wearable SDK 传输层支持节点发现、设备管理授权检查/申请、手环快应用检查/拉起、消息监听注册、错误状态记录与自动重试。
- JSON-RPC 覆盖登录状态、会话列表与置顶/静音/归档/删除、聊天历史、文本发送/回复/编辑/删除/转发/搜索、已读/typing，以及 peer 和消息置顶等同步器能力。
- 事件桥接包含新消息/编辑更新、出站消息确认、删除和连接状态；emoji-only 传输避免把大块媒体二进制塞进手环消息通道。
- Nagram 设置菜单新增 **TG Wear Connection Diagnostics**，显示桥接服务、SDK 初始化、设备授权、节点、手环 App、手机消息监听、最近手表消息/探测/确认和最近错误；可触发重连和手机→手环挑战。

### Vela 手环端

- 设置页新增 **手机连接诊断** 页面，可查询系统 `getReadyState()` / `diagnosis()`，读取 Android 桥接状态 RPC。
- Android 发出随机 nonce 挑战后，手环页提示用户明确点击 **确认手机连接**。手机校验 nonce 并回包成功后，才显示双向应用消息往返成功。
- 诊断页生命周期会清理 interval 和状态监听；新增页面已注册在 manifest，RPK 已包含页面代码及设置入口。

### 不等于“整个 Nagram 界面完整镜像”

当前实现覆盖手环适用的同步器核心消息和会话能力，并非把 Android Nagram 的全部页面、设置、网络调用、媒体上传/下载与所有 Telegram 功能移植到手环。媒体目前刻意采用 emoji/简短类型标签策略，不传媒体二进制。FCM 配置也不属于本轮手环互联链路。

## 4. 关键修改

- Android `applicationId` 与 Vela manifest 包名统一为 `com.hrk.tgwear`，debug/release 使用同源签名配置。
- `JNI_OnLoad` 中原有签名校验保留，只把硬编码 SHA-1 pin 更新为当前 Vela/Android 同源证书指纹；未绕过或删除签名校验。
- Xiaomi transport 与 RPC/event 路由完成跨端对齐；诊断 nonce RPC/事件贯穿 Android → Vela → Android。
- Vela manifest 注册 `pages/connection`；设置页接入入口；新增本地化默认资源并补齐 Android Activity 的 view listener。

本轮 Android 第一次编译发现 5 个源码错误（`View.OnClickListener` 参数形状和缺失 string 资源），均已修正；第二次最终 `:TMessagesProj:assembleDebug` 成功。

## 5. 构建 Android APK

### 环境

已验证工具链：JDK 21、Android SDK Platform 36 / Build Tools 36、NDK 27.2.12479018、CMake 3.22.1。Windows 本机请使用相匹配的 Android SDK/NDK；实际路径由 `local.properties` 指定。

### 从项目工作区构建

```bash
cd Nagram-tgwear
# 确保 local.properties 中已有本机 sdk.dir、TELEGRAM_APP_ID、TELEGRAM_APP_HASH
./gradlew :TMessagesProj:assembleDebug
```

Windows PowerShell：

```powershell
cd Nagram-tgwear
.\gradlew.bat :TMessagesProj:assembleDebug
```

输出目录：

```text
TMessagesProj/build/outputs/apk/debug/
```

在 Android Studio 中也可打开 `Nagram-tgwear/`，等待 Gradle Sync 后运行同一 Gradle task。打包覆盖自己手机已有旧版前，应先确认旧版是否不同包名；旧 Nagram 的旧 applicationId 版本需卸载后安装本版。若目标包名相同但签名不同，Android 也会拒绝覆盖。

### 签名/隐私注意

共享源码包**不包含** `tgwear.jks`、Vela `private.pem`、`local.properties`、Google Services 配置或任何本地凭据。debug 包名与 Interconnect 需要签名和手环证书匹配；要重新产出可在 Mi Fitness/目标手环互联的包，必须在受控本机补入原有同源签名材料及构建配置。不要把私钥或本机 API 凭据再次上传到共享资源或 Git。

## 6. 构建 Vela RPK

```bash
cd tgwear-quickapp-main
npm ci
npm run build
```

预期输出：`dist/com.hrk.tgwear.debug.0.1.0.rpk`。本轮 `npm run build` 成功；Toolkit 有既有弃用/样式路径/未使用变量警告，不阻止构建。工具会使用本地 `sign/debug` 证书，因此其他环境重签前也要提供与 Android 一致的签名证书/私钥。

## 7. 手机和手环诊断步骤

1. 在 OPPO F9 安装 ARM64 APK；在 Mi Fitness/AstroBox 的既有安装流程中安装对应 RPK，确认使用同一包名与签名。
2. 手机打开 Nagram → **设置 → TG Wear Connection Diagnostics**。
3. 点 **启动 / 恢复桥接并重新发现手环**，如系统提示则批准设备管理授权。
4. 按页面状态确认：SDK 初始化、已发现节点、手环端 App 安装状态、手机消息监听就绪。
5. 手环打开 TG Wear → **设置 → 手机连接诊断**，查看系统互联状态、诊断结果及手机服务/监听状态。
6. 手机点 **发送手机 → 手环连接挑战**；回手环诊断页点 **确认手机连接**。
7. 仅当手环显示 **手机↔手环双向通信成功**，才算完成了实际应用消息往返。系统 `diagnosis()` 单独成功只证明系统级通道诊断，不单独证明 TG Wear RPC 往返。

## 8. 下一位 Agent 的工作清单

1. 先让用户在 OPPO F9 Android 10 和目标 Xiaomi Band 9 上安装上表 APK/RPK，完成第 7 节手动双向挑战；不要继续改代码或重复要求用户核对包名/签名，除非设备新证据显示不匹配。
2. 若失败，收集：手机诊断页“阶段/最近错误”、手环诊断页四项状态/详情、`adb logcat` 中 `TGWear/Xiaomi`、`tgwear/Service`、`tgwear/Router` 及 Android 崩溃堆栈；再判断是授权、节点发现、手环 App 安装/拉起、消息监听，还是应用 RPC 层。
3. 修复完应重建 ARM64 APK 和 RPK，复跑 `python3 verify_tgwear_sync.py`，用 `apksigner` 核对 APK 证书，用 `sha256sum` 记录新版本，并重新做真机往返测试。
4. 若用户要求更完整的手环功能，先把需要移植的具体 Nagram 功能拆为 watch-friendly API/交互，不要承诺“所有 Nagram UI 功能已全量移植”。媒体继续遵循低带宽策略，若扩展附件再独立设计分片/ACK/大小限制。

已复跑：`python3 verify_tgwear_sync.py` → **44/44 checks passed**。RPK manifest/页面路由/挑战代码检查通过；APK 包名、双架构签名、Activity dex、native pin 检查通过。**尚未完成的唯一关键验收为实际设备验证。**

## 9. 项目资源与源码覆盖包说明

项目原有 `Nagram-main.zip` 是 Android 基线源码，`tgwear-quickapp-main.zip` 是手环基线，`tgwear-android-patch-main.zip` 是此前桥接补丁。新增 `TGWear-双端源码交接包-20260925.zip` 是基于当前实现的最终覆盖代码和构建模板，不包含上述大仓库构建缓存或私钥；需与项目已有基线/本交接说明配合，不是 8GB Android Gradle build cache 的整机复制件。

**未调用 Gemini**：本次代码打包、校验与文档整理无需生成式模型，不消耗 Gemini 免费额度。
