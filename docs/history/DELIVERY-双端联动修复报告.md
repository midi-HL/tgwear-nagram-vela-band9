# TG Wear 双端联动修复报告

> 本轮任务：修复 Android 端同步器，使其可用 Nagram 全部功能，并与手环端联动保证双端可正常使用。
>
> 工作目录：`/home/ubuntu/work/vela-tgwear/`

## 一、结论摘要

本轮定位并修复了**一个会导致同步器完全无法工作的阻塞缺陷**，并把 Android 端的能力从「只能收发文本」扩展到**覆盖 Nagram 主要功能**，同时把手环端改造成 **emoji-only** 渲染策略并补齐了对应的交互入口。

| 维度 | 修复前 | 修复后 |
|---|---|---|
| Android 端包名 | `xyz.nextalone.nagram` | `com.hrk.tgwear`（与手环端一致） |
| 两端签名 | Android 用 `release.keystore`，与手环端证书**不同源** | 统一使用 `signing/tgwear.jks`，指纹完全一致 |
| RPC 方法数 | 7 | 19 |
| 事件类型 | 2 | 4 |
| 手环端贴纸发送 | 有（`sendSticker`） | 已移除，改为 emoji-only |
| 消息去重/编辑处理 | 盲目 append（编辑会产生重复气泡） | 按 id upsert |
| 出站消息确认 | 无 | 有（`local_id → server_id` 替换） |
| 静态验收 | 14 项 | 42 项（全部通过） |
| 构建产物 | 无 | APK（2 ABI）+ RPK，均已产出 |

## 二、阻塞缺陷：interconnect 无法路由（已修复）

### 2.1 官方硬约束

小米官方文档 [快应用与三方应用互联互通](https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html) 明确要求：

> 开发注意事项：interconnect 通信前提要保证**快应用和三方应用安卓端两者的包名及签名保持一致**。
> - 快应用 `manifest.json` 里 `package` 字段必须与三方 App 安卓端包名一致。
> - 快应用签名需要使用三方应用安卓端签名（可从 .jks 提取证书与私钥）。

### 2.2 实际偏差

| 检查项 | 手环端 | Android 端（修复前） | 结果 |
|---|---|---|---|
| 包名 | `com.hrk.tgwear` | `xyz.nextalone.nagram` | **不一致 → 消息无法路由** |
| 签名证书 SHA-256 | `FA:63:81:C8:...:74:28` | `release.keystore`（`androidkey`，不同证书） | **不一致 → 握手被拒** |

两项同时不满足时，手环端 `interconnect.send()` 会因为找不到目标应用而失败，Android 端也收不到任何消息。这解释了「同步器不工作」的表象。

### 2.3 修复动作

1. `TMessagesProj/build.gradle`：`defaultConfig.applicationId` 改为 `com.hrk.tgwear`。
2. 把 `tgwear-android-patch-main/signing/tgwear.jks`（别名 `tgwear`；口令不写入共享文档）复制到 `Nagram-tgwear/signing/tgwear.jks`。
3. 新增 `signingConfigs.tgwear`，`debug` 与 `release` 两个 buildType 都改用它。
4. 写入 `local.properties`（`sdk.dir`、`TELEGRAM_APP_ID/HASH`）。

修复后  `verify_tgwear_sync.py` 会用 `keytool` 与 `openssl` 实测两端指纹并比对，防止未来再次退化。

### 2.4 连带修复：Firebase 构建校验

改包名后，Google Services Gradle 插件会因为 `google-services.json` 里的
`client_info.android_client_info.package_name` 与新 applicationId 不一致而直接报错：

```text
Execution failed for task ':TMessagesProj:processDebugGoogleServices'.
> No matching client found for package name 'com.hrk.tgwear'
```

已把 `TMessagesProj*/google-services.json`（共 4 个文件、10 处 client 条目）
的包名统一改为 `com.hrk.tgwear`，构建校验得以通过。

> **重要说明（不夸大）**：这**只解决了本地构建配置的校验**。Firebase 项目
> `nekogram-2753b` 侧并没有注册 `com.hrk.tgwear` 这个应用，因此 **FCM 推送实际不会生效**。
> 若你需要推送功能，请在 Firebase 控制台为 `com.hrk.tgwear` 新增一个 Android 应用，
> 下载新的 `google-services.json` 替换 `TMessagesProj/google-services.json`。
> 手环联动本身不依赖 FCM（走小米 interconnect 通道），所以不影响 TG Wear 的同步功能。

## 三、Android 端（Nagram 同步器）改造

### 3.1 传输层：`XiaomiWearConnection.java`（重写）

原实现只在 `init()` 时尝试一次 `getConnectedNodes()`，设备未连接或快应用未安装就直接放弃。新实现：

- **节点发现重试**：未拿到节点时按 5 秒间隔重试，直至 `destroy()`，覆盖「先开 App 后连手表」「先装 App 后装快应用」等真实时序。
- **重复注册容错**：SDK 在已注册时会返回包含 `registered` 的错误，按参考实现（BandTOTP / BandBBS 电子书）的做法视为成功继续。
- **权限复查**：`checkPermissions` 未通过则 `requestPermission` 后**重新检查**，不假设请求必然成功。
- **快应用探测与拉起**：`isWearAppInstalled` 未安装时标记未就绪并继续重试（快应用可能稍后安装）；已安装则 `launchWearApp("/pages/splash")`。
- **发送失败自愈**：`sendMessage` 失败时标记未就绪并调度重连，避免静默丢帧。

传输层使用的 SDK 签名全部经 `javap` 反查 AAR 逐一核对（见下表），且确认 `Task` 是本 SDK 自带的 `com.xiaomi.xms.wearable.tasks.Task`，不是 GMS Task。

| SDK API | 真实签名 |
|---|---|
| `Wearable.getNodeApi` | `(Context) → NodeApi` |
| `NodeApi.getConnectedNodes` | `() → Task<List<Node>>` |
| `NodeApi.isWearAppInstalled` | `(String) → Task<Boolean>` |
| `NodeApi.launchWearApp` | `(String, String) → Task<Void>` |
| `AuthApi.checkPermissions` | `(String, Permission[]) → Task<boolean[]>` |
| `AuthApi.requestPermission` | `(String, Permission...) → Task<Permission[]>` |
| `MessageApi.sendMessage` | `(String, byte[]) → Task<Void>` |
| `MessageApi.addListener` | `(String, OnMessageReceivedListener) → Task<Void>` |
| `OnMessageReceivedListener` | `void onMessageReceived(String, byte[])` |

### 3.2 协议层：19 个 RPC 方法

| 分类 | 方法 |
|---|---|
| 登录 | `auth.getState`、`auth.logout` |
| 会话 | `dialogs.get`、`dialogs.pin`、`dialogs.mute`、`dialogs.archive`、`dialogs.delete` |
| 消息 | `messages.getHistory`、`messages.sendText`、`messages.reply`、`messages.edit`、`messages.delete`、`messages.forward`、`messages.markRead`、`messages.setTyping`、`messages.search` |
| 资料 | `peers.get`、`peers.pinMessage` |

每个方法都对接 Nagram **当前真实存在的 API**（逐一 grep 源码核对），例如：

```text
editMessage   → SendMessagesHelper.editMessage(MessageObject, String, boolean, BaseFragment,
                                                ArrayList<TLRPC.MessageEntity>, int, int)
forward       → SendMessagesHelper.processForwardFromMyName(MessageObject, long, long, long,
                                                            MessageSuggestionParams)
delete        → MessagesController.deleteMessages(ArrayList<Integer>, ArrayList<Long>,
                                                  TLRPC.EncryptedChat, long, boolean, int, ...)
pin           → MessagesController.pinDialog(long, boolean, TLRPC.InputPeer, long)
mute          → NotificationsController.muteUntil(long did, long topicId, int seconds)
archive       → MessagesController.addDialogToFolder(long, int, int, long)
typing        → MessagesController.sendTyping(long, long, int, int)
read          → MessagesController.markDialogAsRead(long, int, int, int, boolean, long, int, boolean, int)
```

### 3.3 事件层：4 类事件

| 事件 | 触发来源 | 语义 |
|---|---|---|
| `update.newMessage` | `didReceiveNewMessages` | 新消息 **或消息被编辑** |
| `update.messageSent` | `messageReceivedByServer` | 出站消息拿到服务端 id |
| `update.messageUpdated` | `messagesDeleted` | 消息被删除 |
| `update.connectionState` | Service 回调 | 通道就绪状态 |

> **关键发现**：本 Nagram 构建中**不存在 `messageEdited` 事件**。消息被编辑时走
> `updateInterfaceWithMessages(...)` → `didReceiveNewMessages`。
> 因此手环端必须**按 message.id 做 upsert**，否则「编辑一次 = 多一个重复气泡」。
> 这一点已写入两端代码注释与验收脚本，防止回归。

出站消息的 id 生命周期同样已对齐：本地消息 id 为**负数**（`getNewMessageId()`），
服务端确认后通过 `messageReceivedByServer(oldId → newId)` 通知，手环端据此把乐观气泡
替换为真实 id。此前没有任何一端处理这个转换，会导致发送后气泡永远停留在「发送中」。

### 3.4 数据转换层：`TdJsonConverter.java`

在保留 emoji-only 约束的前提下扩展了表达能力：

- **媒体类型标签化**：`photo` / `voice` / `video` / `video_note` / `gif` / `audio` / `document` / `location` / `contact` / `poll` / `webpage`，只传类型与极短元数据（时长、标题、文件名），**绝不传二进制、文档 ID、SVG 或缩略图字节**。
- **贴纸**：仍只保留 `documentAttributeSticker.alt`（emoji）。
- **群聊发送者名**：`sender` 字段，便于手环区分发言人。
- **回复引用**：`reply_to.id` + 被引用文本（贴纸则回退为 emoji）。
- **发送状态**：`sending` / `failed`，判定口径与 `MessageObject` 内部一致（`id < 0 && send_state == SENDING/SEND_ERROR`）。
- **正文截断**：单条消息上限 2000 字符，防止超长消息撑爆 wearable 通道。

## 四、手环端（Vela 快应用）改造

### 4.1 emoji-only 策略

| 位置 | 改动 |
|---|---|
| `utils/api.js` | **删除 `sendSticker()`**，不再向手机端发送任何贴纸请求 |
| `utils/format.js` | 新增 `messageText()`：文本 → 贴纸 emoji → 媒体占位符 → `[消息]` 四级回退 |
| `pages/chat/chat.ux` | 消息渲染统一走 `messageText()`，不再拼接 `[贴纸]` 之类的占位 |

手环端不下载、不解码、不缓存任何贴纸或图片资源；所有重处理留在手机端完成。
`store.js` 增加 `MAX_MESSAGES = 200` 的单会话上限，超出丢弃最旧条目，避免长期运行内存膨胀。

### 4.2 事件语义对齐（防止重复/丢失）

`app.ux` 与 `chat.ux` 统一订阅 4 类事件，其中消息写入采用：

- `store.upsertMessage(peer, message)` — 按 id 命中则替换，未命中则追加
- `store.replaceMessageId(peer, localId, message)` — 出站确认
- `store.removeMessages(peer, ids)` — 删除

### 4.3 新增交互能力

聊天页新增长按菜单，把手环端能触达的功能对齐到 Android 端能力：

| 菜单项 | 调用 |
|---|---|
| 回复该消息 | `messages.reply` |
| 编辑 | `messages.edit` |
| 转发到最近会话 | `messages.forward` |
| 置顶此消息 | `peers.pinMessage` |
| 删除消息 | `messages.delete` |

此外：

- 发送中/失败状态直接显示在气泡下方（`stateText`）。
- 打字时按节流调用 `messages.setTyping`，让对端看到输入状态。
- 长按事件使用 Vela 通用事件 `longpress`（已对照官方文档确认支持）。
- 列表滚动改用官方 `list.scrollTo({index, behavior})`（而非不存在的 `bottom: true`）。

## 五、验证结果（全部为实测）

### 5.1 静态与产物级验收：42/42 通过

`verify_tgwear_sync.py` 已扩展为 42 项检查，覆盖五类断言：

1. **回归基线**（沿用上一轮 14 项）：包名、入口页、Nagram 事件名、`SendMessageParams.of`、`dialogMessage` 字段、SDK transport、emoji-only payload 等。
2. **包名一致性**：`Android applicationId == Vela manifest.package`。
3. **签名一致性（三层实测）**：
   - `keytool` 读取 `signing/tgwear.jks` 的 SHA-256
   - `openssl` 读取手环端 `sign/{debug,release}/certificate.pem` 的 SHA-256
   - `apksigner` 读取**已构建 APK** 内的证书 SHA-256

   三者完全一致，均为
   `FA:63:81:C8:28:9E:7A:DB:AE:E2:93:7F:58:32:A9:1B:40:86:CA:62:1E:A8:2C:FE:7B:30:AA:93:A4:1D:74:28`，
   subject 为 `C=CN, ST=Local, L=Local, O=hrk, OU=Open Source, CN=TG Wear`。
4. **RPC/事件双向一致**：手环端 `call('...')` 出现的每个方法都必须在 `WearConstants.Method` 中声明**且**在 `BridgeRouter` 中注册；手环端订阅的每个事件都必须在 `WearConstants.Event` 中声明。
5. **Nagram API 对齐**：断言使用当前 `editMessage` / `deleteMessages` / `processForwardFromMyName` / `sendTyping` / `markDialogAsRead` 等真实签名，且**不得**订阅本版本不存在的 `messageEdited`。

```text
PASS  Android package matches Vela package (interconnect requirement)
      [android=com.hrk.tgwear vela=com.hrk.tgwear]
PASS  Android keystore fingerprint matches Vela certificate (interconnect requirement)
      [jks=FA6381C8289E7ADB... pem=FA6381C8289E7ADB...]
PASS  built APK certificate matches Vela certificate (interconnect requirement)
      [apk=FA6381C8289E7ADB... pem=FA6381C8289E7ADB...]
42/42 checks passed
```

### 5.2 手环端构建：RPK 已产出

```text
tgwear-quickapp-main/dist/com.hrk.tgwear.debug.0.1.0.rpk   (344 KB)
```

已解包核验：`package = com.hrk.tgwear`、`router.entry = pages/splash`，
且 `app.js` 中确实包含新增的 `messages.forward`、`messages.search`、`dialogs.mute`
等 RPC 方法与 `update.messageSent`、`update.messageUpdated` 事件订阅。

### 5.3 Android 端构建：APK 已产出（BUILD SUCCESSFUL）

```text
Nagram-v12.10.1(1248)-arm64-v8a-debug.apk
Nagram-v12.10.1(1248)-armeabi-v7a-debug.apk
```

实测 `aapt2 dump badging` 结果：

```text
package: name='com.hrk.tgwear' versionCode='1248' versionName='12.10.1'
application-label:'Nagram'
```

> **真机日志根因已修复并完成双 ABI 重建（实机待验证）**：`JNI_OnLoad` 因签名 SHA-1 pin 仍为旧 Nagram 值而返回 `JNI_ERR`。源码 pin 已更新为 TG Wear 同源证书 SHA-1，并保留完整签名校验。随后 `:TMessagesProj:assembleDebug` 成功；ARM64 与 ARMv7 APK 均内含新 pin，且签名 SHA-1 与 Vela 证书一致。静态验收 44/44 通过。由于本环境无 OPPO 真机，仍需安装新 APK 实测启动和手环互联。

新产物 SHA-256：

```text
arm64-v8a   a9b930daa302eedaa779a64c3995ccc164428e2a30cf50015abadb51df08c8d5
armeabi-v7a 21b2c17eb6c0a8c77bae53c6a35c060fa0df6db8138205e1ade475e1b2e0d565
```

本轮为打通原生构建修复的三处环境问题（均属交接包遗留，非业务代码问题）：

| 问题 | 现象 | 修复 |
|---|---|---|
| `google-services.json` 包名不匹配 | `No matching client found for package name 'com.hrk.tgwear'` | 把 4 个文件的 10 处 `package_name` 统一为新包名 |
| ffmpeg 目录布局不符 | `fatal error: 'libavutil/timestamp.h' file not found` | 建立 `ffmpeg/build/include → ../include`，并把旧布局的 `.a` 补齐到 `build/<abi>/` |
| openh264 头文件缺失 | `fatal error: 'third_party/openh264/codec/api/wels/codec_app_def.h' file not found` | 补拉 `cisco/openh264` 源码（交接包中该子模块为空目录） |

同时修正了 javac 报出的 3 处真实签名错误（详见 `WORKING-NOTES.md`）：
`deleteMessages` 的参数顺序（`topicId` 在 `forAll` 之前）、`MessageMedia` 基类并无
`contact`/`poll` 字段、以及音频/视频时长均为 `double` 需显式取整。

工具链：JDK 21、Android SDK（platform 36 / build-tools 36.0.0 / NDK 27.2.12479018 /
cmake 3.22.1）、cmake、ninja。原生侧 BoringSSL 已按 `build_boringssl.sh` 编译出双 ABI 静态库。

## 六、仍需在真机验证的项（本环境无真机，不做断言）

以下项目**未**在本环境验证，需要你在真机上确认；本轮只做到「代码与静态契约正确 + 可编译 + RPK 可产出」：

1. 手环端首次连接时，Android 端的节点重试是否能在典型时序下收敛到就绪。
2. `Permission.DEVICE_MANAGER` 授权弹窗在真机上的实际表现。
3. 群聊/频道消息的 `sender` 与 `media` 字段在实际数据下的渲染效果。
4. 出站消息 `messageSent` 事件的到达时序（是否存在事件先于乐观插入的竞态）。
5. 转发到「最近会话」的交互是否符合你的使用习惯（当前为一步完成，转发到会话列表第一个非当前会话）。

## 七、安装与使用

### 7.1 手环端

```bash
cd tgwear-quickapp-main
npm ci
npm run build          # 生成 dist/com.hrk.tgwear.debug.0.1.0.rpk
```

用 AIoT-IDE 或 AstroBox 安装 `dist/com.hrk.tgwear.debug.0.1.0.rpk`。

### 7.2 Android 端

```bash
cd Nagram-tgwear
# local.properties 需包含 sdk.dir / TELEGRAM_APP_ID / TELEGRAM_APP_HASH
./gradlew :TMessagesProj:assembleDebug
```

产物：`TMessagesProj/build/outputs/apk/debug/`

> 安装前请先卸载手机上原有的 Nagram / Telegram（包名由 `xyz.nextalone.nagram` 改为
> `com.hrk.tgwear`，属于不同应用，无法覆盖安装）。

### 7.3 验收

```bash
cd /home/ubuntu/work/vela-tgwear
python3 verify_tgwear_sync.py
```

## 八、文件清单

### Android 端（`Nagram-tgwear/`）

| 文件 | 说明 |
|---|---|
| `TMessagesProj/build.gradle` | applicationId 改为 `com.hrk.tgwear`；新增 `signingConfigs.tgwear` |
| `signing/tgwear.jks` | 与手环端同源的签名材料（新增） |
| `local.properties` | SDK 路径与 Telegram API 凭据 |
| `.../tgwear/XiaomiWearConnection.java` | 传输层重写 |
| `.../tgwear/WearConstants.java` | 19 方法 + 4 事件 |
| `.../tgwear/TdJsonConverter.java` | 媒体/发送者/回复/状态扩展 |
| `.../tgwear/BridgeRouter.java` | 注册全部方法 |
| `.../tgwear/NotificationCenterBridge.java` | 新增出站确认与删除事件订阅 |
| `.../tgwear/handlers/MessagesHandler.java` | 新增 6 个方法 |
| `.../tgwear/handlers/DialogsHandler.java` | 新增 4 个方法 |
| `.../tgwear/handlers/PeersHandler.java` | 新增（2 个方法） |

### 手环端（`tgwear-quickapp-main/src/`）

| 文件 | 说明 |
|---|---|
| `utils/api.js` | 删除贴纸发送，新增 12 个方法 |
| `utils/format.js` | `messageText()` emoji-only 渲染 |
| `utils/store.js` | upsert / replaceId / remove 语义 |
| `app.ux` | 统一事件订阅 |
| `pages/chat/chat.ux` | 重写：upsert、发送状态、长按菜单 |
| `pages/chats/chats.ux` | 适配 pinned / muted |

### 工作区（`/home/ubuntu/work/vela-tgwear/`）

| 文件 | 说明 |
|---|---|
|  `verify_tgwear_sync.py` | 42 项静态验收脚本 |
| `WORKING-NOTES.md` | 关键事实与 API 台账 |
| `DELIVERY-双端联动修复报告.md` | 本文件 |
> **真机日志根因已修复并完成双 ABI 重建（实机待验证）**：`JNI_OnLoad` 因签名 SHA-1 pin 仍为旧 Nagram 值而返回 `JNI_ERR`。源码 pin 已更新为 TG Wear 同源证书 SHA-1，并保留完整签名校验。随后 `:TMessagesProj:assembleDebug` 成功；ARM64 与 ARMv7 APK 均内含新 pin，且签名 SHA-1 与 Vela 证书一致。静态验收 44/44 通过。由于本环境无 OPPO 真机，仍需安装新 APK 实测启动和手环互联。

新产物 SHA-256：

```text
arm64-v8a  a9b930daa302eedaa779a64c3995ccc164428e2a30cf50015abadb51df08c8d5
armeabi-v7a 21b2c17eb6c0a8c77bae53c6a35c060fa0df6db8138205e1ade475e1b2e0d565
```


## 九、最终构建补充（2026-09-25）

本节结果对应本次最终重建的文件；如与上文较早阶段记录的文件大小或 SHA-256 不同，以这里列出的当前产物为准。

### 9.1 最终产物与校验值

| 产物 | 文件 | SHA-256 |
|---|---|---|
| Android ARM64 APK | `Nagram-tgwear/TMessagesProj/build/outputs/apk/debug/Nagram-v12.10.1(1248)-arm64-v8a-debug.apk` | `a76a9e50f27b870935462df01e3b454e1a1cdbbf6aea7733900960a593c7fb90` |
| Android ARMv7 APK | `Nagram-tgwear/TMessagesProj/build/outputs/apk/debug/Nagram-v12.10.1(1248)-armeabi-v7a-debug.apk` | `13df9c16e86779bc918852ab9e957fa00c62281ee1e4abe4b03eb2921952ea87` |
| Vela RPK | `tgwear-quickapp-main/dist/com.hrk.tgwear.debug.0.1.0.rpk` | `b5db43c2e9cb84b12aeeefb8588056db6da5abb95e5e110a898099a51b33b69b` |

验证结论：

- `:TMessagesProj:assembleDebug` 最终 **BUILD SUCCESSFUL**；同时生成 ARM64 与 ARMv7 两个 APK。
- 两个 APK 均识别为 `com.hrk.tgwear`，签名 SHA-1 为 `B0FFBED83AB99FB5555736C3AE185F4DA62A49B9`。
- APK 签名证书 SHA-1 与 Vela RPK 调试证书相同，满足 Interconnect 的包名/签名匹配条件。
- 两个 APK 的 `libtmessages.49.so` 均包含对应的 JNI 签名 pin；dex 内包含 `WearDiagnosticActivity`。
- 新 RPK manifest 声明 `pages/connection`；包内含诊断页、挑战确认 RPC 和设置页入口。
- `python3 verify_tgwear_sync.py`：**44/44 checks passed**。
- 另有 RPK ZIP 结构/manifest/入口检查、APK 包名/签名/DEX/native pin 检查通过。

### 9.2 连接诊断使用步骤

1. 在手机安装 ARM64 APK（OPPO F9 为 ARM64 设备），首次安装若与旧包冲突，先卸载旧 TG Wear/Nagram 包后再装；包名已统一为 `com.hrk.tgwear`。
2. 在 Mi Fitness 中安装/授权同包名的 TG Wear 手环快应用 RPK，确保 Android APK 与 RPK 来自同一套签名。
3. 打开手机 Nagram → **设置 → TG Wear Connection Diagnostics**。
4. 点 **启动 / 恢复桥接并重新发现手环**；按提示完成小米设备管理授权。页面会区分 SDK、节点、手环快应用安装状态与手机消息监听状态。
5. 手环打开 TG Wear → **设置 → 手机连接诊断**。页面可查看系统 Interconnect diagnosis 和手机桥接状态。
6. 在手机诊断页点 **发送手机 → 手环连接挑战**，然后在手环诊断页点 **确认手机连接**。只有手环收到手机随机 nonce、手环用户确认、手机校验 nonce 并回包成功后，才会显示双向通信成功。

状态解释：Interconnect 的 `diagnosis()` 成功只证明系统通道诊断成功；“双向应用消息往返通过”才证明 TG Wear 消息监听/RPC 与响应返回链路实测成功。

### 9.3 仍需真机验证

本 sandbox 未连接 OPPO F9 或 Xiaomi Band 9，故没有声称实机启动/连接已验证。请在目标手机与手环上安装以上新 APK/RPK 后按 9.2 操作；若失败，先从手机诊断页记录“阶段/最近错误”，再从手环诊断页记录“系统互联/手机桥接服务/手机消息监听”状态与详情。
