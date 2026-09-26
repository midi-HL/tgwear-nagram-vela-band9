# TG Wear 双端联动 — 工作笔记（当前 Agent）

> 本文件记录本轮修复的关键事实、外部证据与待办，供上下文压缩后继续使用。

## 工作目录

```text
/home/ubuntu/work/vela-tgwear/
├── Nagram-tgwear/                 # Nagram（Android 端）集成树
├── tgwear-quickapp-main/          # 手环端 Vela 快应用
├── tgwear-android-patch-main/     # 原始同步器参考补丁（含 signing/、patches/）
├── references/                    # 参考工程（BandTOTP-Android、com.bandbbs.ebook-android）
├── verify_tgwear_sync.py          # 静态验收脚本（原 14 项）
├── WORKING-NOTES.md               # 本文件
```

原始交接包已解压到 `/home/ubuntu/work/vela-tgwear/`（tar 内无顶层目录）。

## 一、官方硬约束（已从官方文档确认）

来源：https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html

> 开发注意事项：interconnect 通信前提要保证**快应用和三方应用安卓端两者的包名及签名保持一致**。
> - 快应用 `manifest.json` 里 `package` 字段必须与三方 App 安卓端包名一致。
> - 快应用签名需要使用三方应用安卓端签名（可从 .jks 提取证书与私钥）。

官方 API 事实（同页）：

- `interconnect.instance()` 单例；连接由系统自动建立。
- `connect.getReadyState({success,fail})` → `status`: 1=连接成功, 2=连接断开；fail code 1006=连接断开。
- `connect.diagnosis({timeout,success,fail})` → `status`: 0=OK, 204=CONNECT_TIMEOUT, 1001=APP_UNINSTALLED, 1000=OTHERS。
- `connect.send({data,success,fail})`：`data` 是**对象**；fail code 204=链接超时, 1006=连接断开。
- `connect.onmessage = (data) => {}`：`data.data` 为接收到的内容。
- `connect.onopen`（参数 `isReconnected`）、`connect.onclose`（`code`,`data`）、`connect.onerror`（code 1000/1001/1006）。

## 二、Xiaomi Wearable SDK 1.4 真实 API（javap 实查 AAR）

AAR：`Nagram-tgwear/TMessagesProj/libs/xms-wearable-lib_1.4_release.aar`

```text
com.xiaomi.xms.wearable.Wearable
  static getNodeApi/getAuthApi/getMessageApi(Context) → NodeApi/AuthApi/MessageApi
com.xiaomi.xms.wearable.node.NodeApi
  Task<List<Node>> getConnectedNodes()
  Task<Boolean>     isWearAppInstalled(String nodeId)
  Task<Void>        launchWearApp(String nodeId, String route)
com.xiaomi.xms.wearable.node.Node
  public String id; public String name;
com.xiaomi.xms.wearable.auth.AuthApi
  Task<Boolean>   checkPermission(String, Permission)
  Task<boolean[]> checkPermissions(String, Permission[])
  Task<Permission[]> requestPermission(String, Permission...)
com.xiaomi.xms.wearable.auth.Permission
  static DEVICE_MANAGER, NOTIFY
com.xiaomi.xms.wearable.message.MessageApi
  Task<Void> sendMessage(String nodeId, byte[])
  Task<Void> addListener(String nodeId, OnMessageReceivedListener)
  Task<Void> removeListener(String nodeId)
com.xiaomi.xms.wearable.message.OnMessageReceivedListener
  void onMessageReceived(String sourceNodeId, byte[] message)
```

关键：`Task` 是 **SDK 自带** 的 `com.xiaomi.xms.wearable.tasks.Task`，**不是** GMS Task。
`tasks.OnSuccessListener<T>{onSuccess(T)}`、`tasks.OnFailureListener{onFailure(Exception)}`。

## 三、签名与包名对齐状态（已验证）

- 手环端 `src/manifest.json` → `"package": "com.hrk.tgwear"`，`router.entry = "pages/splash"`。
- 手环端签名证书 SHA-256 指纹：
  `FA:63:81:C8:28:9E:7A:DB:AE:E2:93:7F:58:32:A9:1B:40:86:CA:62:1E:A8:2C:FE:7B:30:AA:93:A4:1D:74:28`
  subject: `C=CN, ST=Local, L=Local, O=hrk, OU=Open Source, CN=TG Wear`
- `tgwear-android-patch-main/signing/tgwear.jks`（签名凭据已从 Git 版历史笔记中移除）指纹**完全一致** → 两端同源 ✔
- 已把 jks 复制到 `Nagram-tgwear/signing/tgwear.jks`，在 `TMessagesProj/build.gradle` 新增 `signingConfigs.tgwear`，debug/release 均改用它。
- **已修复**：`defaultConfig.applicationId` 由 `xyz.nextalone.nagram` 改为 `com.hrk.tgwear`（原为阻塞项）。

## 四、Nagram 侧真实 API（grep 实查源码）

```text
SendMessagesHelper.SendMessageParams.of(...)   // 长参数列表，末尾 (..., int ttl, Object parentObject,
                                               //   MessageObject.SendAnimationData, boolean updateStickersOrder)
sendMessage(SendMessageParams)
editMessage(MessageObject, String message, boolean searchLinks, BaseFragment fragment,
            ArrayList<TLRPC.MessageEntity> entities, int scheduleDate, int scheduleRepeatPeriod) → int
processForwardFromMyName(MessageObject, long did, long payStars, long monoForumPeerId, MessageSuggestionParams)
MessagesController.markDialogAsRead(long dialogId, int maxPositiveId, int maxNegativeId, int maxDate,
            boolean popup, long threadId, int countDiff, boolean readNow, int scheduledCount)
MessagesController.deleteMessages(ArrayList<Integer>, ArrayList<Long>, TLRPC.EncryptedChat, long dialogId,
            boolean forAll, int mode)   // 另有 (..., int topicId) 等重载
MessagesController.deleteDialog(long did, int onlyHistory, boolean revoke)
MessagesController.pinDialog(long dialogId, boolean pin, TLRPC.InputPeer peer, long taskId) → boolean
MessagesController.pinMessage(TLRPC.Chat chat, TLRPC.User user, int id, boolean unpin, boolean oneSide, boolean notify)
MessagesController.addDialogToFolder(long dialogId, int folderId, int pinnedNum, long taskId) → int
MessagesController.getDialogs(int folderId) / getAllDialogs() / getDialog(long) / getUser(long) / getChat(long)
MessagesController.sendTyping(long dialogId, long threadMsgId, int action, int classGuid) → boolean
MessagesController.isDialogMuted(long dialogId) → boolean
NotificationsController.muteUntil(long did, long topicId, int selectedTimeInSeconds)
MessagesController.dialogMessage : LongSparseArray<ArrayList<MessageObject>>   // 对话消息缓存
```

NotificationCenter 事件（本 Nagram 构建实有）：

```text
didReceiveNewMessages(dialogId, ArrayList<MessageObject>, scheduled, mode)
messageReceivedByServer(oldId, newId, TLRPC.Message, peer, grouped_id, existFlags, scheduled)
messageReceivedByServer2(...)  // 同上
messagesDeleted(ArrayList<Integer> ids, ArrayList<Long> channelId, ...)   // 参数个数有多种
dialogsNeedReload / updateInterfaces / updateMessageMedia
```

**注意：`messageEdited` 事件在此版本不存在。** 消息被编辑时 Nagram 走
`updateInterfaceWithMessages(...)` → `didReceiveNewMessages`。
因此手环端对 `update.newMessage` 必须**按 id upsert**，不能盲目 append。

发送流程：本地消息 `id = getUserConfig().getNewMessageId()`（**负数**），
服务端确认后发 `messageReceivedByServer(oldId→newId)`，手环端据此替换。

## 五、构建环境（本 sandbox 已安装）

| 组件 | 位置/版本 |
|---|---|
| JDK | openjdk-21（`apt install openjdk-21-jdk-headless`，含 javac/javap/keytool） |
| Android SDK | `/home/ubuntu/android-sdk`（platform-tools、platforms;android-36、build-tools;36.0.0、ndk;27.2.12479018、cmake;3.22.1） |
| cmake/ninja | `apt install cmake ninja-build`（系统 3.28.3 / 1.11.1） |
| BoringSSL | 已克隆+打补丁+编译：`jni/boringssl/build/{arm64-v8a,armeabi-v7a}/{libcrypto.a,libssl.a}` ✔ |
| ffmpeg 预编译库 | 已复制到 `jni/ffmpeg/build/{arm64-v8a,armeabi-v7a}/` ✔ |
| 其它子模块 | `jni/tlottie`、`third_party/xiph/{ogg,opus,opusfile}` 已克隆 ✔ |
| 预编译静态库 | `jni/prebuild/*/libtlottie.a`、`libopenh264.a` 已随包提供 ✔ |
| 手环端依赖 | `tgwear-quickapp-main/node_modules` 已 `npm ci` 安装（820 包）✔ |

BoringSSL 构建时 `crypto_test`/`ssl_test` 等测试目标会失败（NDK 头文件差异），
但 `libcrypto.a`/`libssl.a` 已生成，**不影响 APK 链接**。批处理仅打印 RC=1。

## 六、本轮已修改文件

### Android 端（`Nagram-tgwear/TMessagesProj/src/main/java/org/telegram/tgwear/`）

- `XiaomiWearConnection.java` — 重写：节点重试、权限复查、已注册容错、发送失败重连调度。
- `WearConstants.java` — 扩展 19 个 RPC 方法 + 4 个事件。
- `TdJsonConverter.java` — 扩展：媒体类型描述、发送者名、回复引用、发送状态；保留 emoji-only 贴纸。
- `BridgeRouter.java` — 注册全部 19 个方法。
- `NotificationCenterBridge.java` — 新增 messageReceivedByServer / messagesDeleted 订阅。
- `handlers/MessagesHandler.java` — 新增 reply/edit/delete/forward/search/setTyping。
- `handlers/DialogsHandler.java` — 新增 pin/mute/archive/delete + folder 分页。
- `handlers/PeersHandler.java` — 新增（peers.get / peers.pinMessage）。
- `TMessagesProj/build.gradle` — applicationId、tgwear 签名。
- `Nagram-tgwear/signing/tgwear.jks` + `local.properties`。

### 手环端（`tgwear-quickapp-main/src/`）

- `utils/api.js` — 删除 `sendSticker()`；新增 reply/edit/delete/forward/search/setTyping/pin/mute/archive/deleteDialog/getPeer/pinMessage。
- `utils/format.js` — `messageText()` emoji-only 渲染、`messagePreview()`。
- `utils/store.js` — `upsertMessage` / `replaceMessageId` / `removeMessages` / MAX_MESSAGES 裁剪。
- `app.ux` — 订阅 update.messageSent / update.messageUpdated，统一 upsert。
- `pages/chat/chat.ux` — 重写：upsert 语义、发送中/失败状态、长按菜单（回复/编辑/转发/置顶/删除）。
- `pages/chats/chats.ux` — 适配 pinned/muted 字段。

## 七、待办

1. 在 OPPO CPH1823 上安装最新 ARM64 APK，检查 `JNI_OnLoad` 不再报 `JNI_ERR`，应用可进入主界面。
2. 在手机与 Xiaomi Band 9 上验证 interconnect 握手、消息收发及双端操作。
3. 当前环境未接入真机，故以上设备侧验证仍待完成。

## 八、必须保持的口径（不得夸大）

- 编译成功 ≠ 实机同步已验证。
- 构建任务启动 ≠ 产物已生成。
- Android 端 emoji-only payload ≠ 手环端 UI 已改造完成（需双侧都验证）。
- 本环境**无真机**，不得声称真机验证通过。

### 编译期实测纠正（重要）

以下两处最初按「直觉签名」写，被 javac 打回，已按真实签名修正：

1. `MessagesController.deleteMessages` 的**参数顺序**是
   `(ArrayList<Integer>, ArrayList<Long>, EncryptedChat, long dialogId, int topicId, boolean forAll, int mode)`，
   即 **topicId 在 forAll 之前**。曾误写为 `(..., dialogId, revoke, 0)`。
2. `TLRPC.MessageMedia` **没有** `contact` / `poll` 字段（它们只存在于
   `TL_messageMediaContact` / `TL_messageMediaPoll` 子类上），基类只有
   `photo / document / geo / webpage / game / invoice / ...`。
   转换器里已删除这两个分支，改用 `webpage` 作为通用链接类型。
3. `TL_documentAttributeVideo.duration` 是 **double**，
   `TL_documentAttributeAudio.duration` 是 **int**，混用会触发 lossy conversion 编译错误。
   已改为 `(int) Math.round(...)`。


## 九、真机闪退根因与签名 pin 修复（2026-09-24；双 ABI 构建通过，实机待验证）

用户在 OPPO CPH1823 / Android 10 上复现启动闪退。完整 UTF-16 logcat 先以 UTF-16 解码后确认：

```text
integrity: 应用读取的签名为：B0FFBED83AB99FB5555736C3AE185F4DA62A49B9
integrity: 应用预置的签名为：3A0F57FE06485D0B90D0ACD990E3A30328E3988D
integrity: 签名校验失败
UnsatisfiedLinkError: JNI_ERR returned from JNI_OnLoad ... libtmessages.49.so
No implementation found for ... ConnectionsManager.native_setJava(boolean)
```

根因：`TMessagesProj/jni/jni.c` 的 `JNI_OnLoad()` 先执行 `verifySign(env)`，失败即返回 `JNI_ERR`，导致 Nagram native 方法未注册；`ApplicationLoader` 随后调用 `native_setJava(false)` 并崩溃。`jni/integrity/integrity.cpp` 原硬编码的 SHA-1 (`3A0F...`) 与 APK 实测证书/手环 PEM 不同。不是设备 ABI 不支持：APK 内 ARM64 `.so` 存在，设备也成功映射并执行了该库的 `JNI_OnLoad()`。

本轮源码修改：

- 将 `integrity.cpp` 允许的证书 SHA-1 改为 `B0FFBED83AB99FB5555736C3AE185F4DA62A49B9`（与 `sign/debug/certificate.pem` 及已签名 APK 一致）。
- 保留 `verifySign()` 完整校验逻辑，不绕过签名校验。
- `verify_tgwear_sync.py` 新增两项回归：pin 必须等于 Vela 证书的 openssl SHA-1；JNI_OnLoad 仍调用签名校验。

静态验收：`44/44 checks passed`。随后执行 `:TMessagesProj:assembleDebug`，Gradle `BUILD SUCCESSFUL`；CMake 已分别重建 `arm64-v8a` 与 `armeabi-v7a` 的 `tmessages.49`。从新 APK 提取的 `.so` 均包含新 SHA-1 pin；两个 APK 的包名均为 `com.hrk.tgwear`，签名 SHA-1 与 Vela PEM 一致（`b0ffbed83ab99fb5555736c3ae185f4da62a49b9`）。仍需在 OPPO 真机安装、启动并验证联动，不宣称真机问题已最终关闭。

新 APK SHA-256：

```text
arm64-v8a   a9b930daa302eedaa779a64c3995ccc164428e2a30cf50015abadb51df08c8d5
armeabi-v7a 21b2c17eb6c0a8c77bae53c6a35c060fa0df6db8138205e1ade475e1b2e0d565
```

> 维护注意：只要 Android 与 Vela 同源签名证书发生变化，必须同步更新 native `SIGN` SHA-1 pin；不允许为绕过失败而移除 `verifySign()`。


## 十、设备实装 APK 哈希对比（2026-09-25）

用户从 OPPO F9 拉取当前安装的 `com.hrk.tgwear` `base.apk`，报告 SHA-256：

```text
A572D255E697988D717BB4B29FF978CAC0E83E9440B7345625772FEBBC1B125B
```

该值与 2026-09-24 旧 ARM64 APK（签名 pin 仍为 `3A0F...`、会在 `JNI_OnLoad` 失败）完全相同；而修复版 ARM64 APK SHA-256 为 `A9B930DAA302EEDAA779A64C3995CCC164428E2A30CF50015ABADB51DF08C8D5`，并确认 `.so` 仅含新 pin `B0FF...`。因此这次设备日志证明运行的是旧产物，但不足以断定旧 APK 是如何进入设备的，不能再把原因归为用户操作错误。

为避免同名文件混淆，提供了带 `SIGNFIX-20260924` 的修复版副本；该副本逐字节与修复版 ARM64 APK 相同（SHA-256 相同），没有重新编译或重新签名。
