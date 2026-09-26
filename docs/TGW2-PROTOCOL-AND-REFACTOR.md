# TG Wear 双端重构设计（v2）

## 1. 范围与边界

本轮只重构 Nagram 内的 TG Wear companion 子系统与 Xiaomi Band 9 Vela 快应用的同步层；不重写 Nagram/Telegram/MTProto，也不改用户指定的 GitHub 参考仓库。两个参考仓库均只读审查。

- Android 继续使用 Nagram 自身 `MessagesController` / `SendMessagesHelper` 读取与操作 Telegram 数据。
- 设备链路仍是 Xiaomi Wearable SDK MessageApi ↔ Vela `@system.interconnect`。
- 传输协议由 TG Wear 自己定义，不承载 HTTP 代理功能。
- `SimpleFetch-Android-Bridge` 的 `SF_*` wire protocol 与 TG Wear JSON-RPC 完全隔离；不发送、不解析、不兼容 `SF_HANDSHAKE`、`SF_REQUEST`、`SF_RESPONSE` 等消息。
- Android 参考源码树和本地 Vela 源码快照不包含完整、可复现的 Android 构建依赖；本轮不宣称 Android APK 已重新构建或真机已验证。

## 2. 证据驱动的关键问题

1. 旧 Android 把完整 JSON 直接传给 `MessageApi.sendMessage`，没有片、ACK、重试、接收重组或恢复。
2. `send()` 的 true 仅说明异步 SDK 调用已发起，不等于设备/应用层送达。
3. 事件与 RPC 响应可由不同线程同时发送，缺少发送队列和背压。
4. Android JSON converter 没有 `JSONObject` / `JSONArray` 透传分支，嵌套对象可能成为 JSON 字符串。
5. Vela 页面 bundles 含各自模块副本；根应用 facade 已用于 API/事件，但 store 仍是每 bundle 独立内存对象。
6. 历史获取只回本地缓存，并把 MTProto history response 忽略；离线事件也没有 cursor/backfill。
7. Android SDK 初始化失败时会使用 `ready=true` 的丢弃型 Stub，可能伪报成功。
8. SimpleFetch 示例虽有响应切片和上限，但没有 chunk ACK/重传/续传，且部分下载路径无界缓存；只借鉴其职责分层、跨 bundle global 单例和大小限额原则。

## 3. TGW/2 帧契约

所有帧是 JSON 对象，顶层带 `__tgw: 2`、`kind`。逻辑层原有 RPC/event JSON 作为 UTF-8 字节流承载在 `data` 中，保持业务字段独立。

### 会话握手

```json
{"__tgw":2,"kind":"hello","session":"<phone-session>","maxChunk":2048}
{"__tgw":2,"kind":"helloAck","session":"<same-session>","maxChunk":2048}
```

未完成握手不得传输业务帧。断连、节点改变或超时后旋转 session 并重握手。

### 数据片

```json
{
  "__tgw":2,"kind":"frame","session":"<session>",
  "transferId":"<unique-transfer>","seq":0,"total":3,
  "byteLength":512,"totalBytes":5000,"totalCrc32":"0123abcd",
  "chunkCrc32":"89abcdef","data":"<base64 bytes>"
}
```

- `seq` 从 0 开始；Base64 只编码本片字节；对原始 UTF-8 字节切片，不按 Unicode 字符切字符串。
- 会话协商 `maxChunk`，当前上限 2048 原始字节；`maxLogicalBytes=128KiB`、最多 64 片、window=1（上一片收到 ACK 后才发下一片）。当前两端相同，但仍不是 Xiaomi SDK 官方/真机实测最大值。
- 接收端校验 session、字段范围、总大小、chunk 长度与 CRC32；按 seq 去重，先缓存片再回 `ack`。
- 收齐后校验总长度和总 CRC32，成功才向 RPC/event dispatcher 提交完整 JSON，并回 `commitAck`；不完整/校验失败回 `nack`。
- ACK 是应用层 `RECEIVED_IN_MEMORY` 语义，不表示持久落盘，也不表示 Telegram 业务操作已完成。

### 应用层 ACK、RPC 与副作用

现有 RPC ID 应换为启动唯一字符串，服务端对最近请求缓存响应以避免同一进程内重复执行。发送消息、编辑、删除、转发等副作用必须带稳定 `operationId`；服务端操作记录在持久账本完成前，不得承诺跨进程 exactly-once。重试只是 at-least-once transport，不等于 Telegram exactly-once。

## 4. 初始重试与限制

- 每片 ACK timeout 3 秒；最多初发加 3 次重试，退避 1/2/4 秒。超过后报错，不无限盲发。
- 每端单一逻辑消息写队列；每个接收端同时最多 1 个内存组装、最近完成去重缓存最多 128 项（30 秒）；单帧 JSON 字符上限 8 KiB；逻辑消息最多 128 KiB。超限明确拒绝，不静默截断。
- TGW/2 分片不跨进程/断连持久续传。物理或协议会话断开会清理接收组装与 ACK waiter；UI 可重新发起读取，副作用仅用同一 `operation_id` 在手机进程内有限去重，不能承诺跨进程 exactly-once。
- 下一阶段若需设备断电/进程崩溃后的断点恢复，须加入 Android 文件 spool、Vela 有界 journal/原子存储、resume 协商与掉电测试。

## 5. 私聊和消息页面策略

目标默认按一对一 user dialog 过滤（群组、频道、secret chat 暂不推送，除非明确扩展）；手机端仍是唯一 Telegram 网络/数据来源。对话列表每页最多 30 个，但 last-message 预览只传 160 字符；私聊历史每页最多 6 条，网络结果以异步事件回填，避免一次把长正文推满手环链路。手环只保留最近有界窗口，不保存媒体二进制或完整 Telegram TL 对象。

## 6. 必须补齐的测试

- Java 与 Vela 共享 ASCII、中文、emoji、分片边界、空 payload 的 golden vectors（当前已具备 Vela Node round-trip 覆盖；尚未实际编译运行 Android Java transport 的 golden-vector 测试）。
- 双端逐片/累计长度/CRC 校验；重复、乱序、缺片、损坏帧、错 session、超限、超时重传、重握手重放。
- RPC JSON 嵌套对象仍为对象；未知帧版本可见地拒绝；不产生 `SF_*` wire token。
- 真机校准不同 chunk 参数，并观察 Android SDK send success、Vela receive、ACK、commitAck 的端到端时序与内存；协商值可在双端诊断状态查看。

## 7. 未知与停止条件

Xiaomi Wearable SDK 单帧最大有效字节、送达顺序、重复语义、回调线程与 send success 的精确定义仍未知；当前源码快照没有 AAR。Band 9 真机安装、断连与内存压力测试未完成前，不把 2 KiB 参数、恢复行为或稳定性标成真机通过。Android 当前源码快照也没有完整 Gradle wrapper、Xiaomi AAR、JNI/CMake 输入；交接包虽有 APK 基线，但不能因此推断此次新增代码已编译进 APK。


## 8. 外部参考来源（本轮已读）

- Xiaomi Vela interconnect API（本地 skill 文档引自官方页面）：https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html 。描述 `interconnect.instance()`、`getReadyState`（1 connected / 2 disconnected）、`diagnosis`（0/204/1001/1000）、对象型 `send({data,...})`、`onmessage` 回调以及手机 App 与 RPK 的包名/签名匹配要求。该文档未给出可用于此项目的 Android MessageApi 最大 payload 数值。
- SimpleFetch Vela 适配参考（本地 skill 文档）：`/home/ubuntu/skills/xiaomi-band-9-vela-simplefetch/references/simplefetch-integration.md`；协议速查：`/home/ubuntu/skills/xiaomi-band-9-vela-simplefetch/references/simplefetch-protocol.md`。说明 SF_* 结构、整体 Base64 后分片及现实现没有 ACK/续传；该协议仅为隔离参考。
- 本轮 web 搜索未找到可信、正式的 Xiaomi Wearable Android MessageApi 最大消息字节数。2 KiB 是本项目保守启动参数，必须真机校准；不可引用参考仓库经验值当官方上限。
