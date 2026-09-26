# TG Wear 连接挑战排障与 Band 9 复测说明

## 最新结论

手环显示 `conn not init`，这条消息不是手机网络超时，而是**诊断页 bundle 中的 Vela interconnect wrapper 尚未拿到连接对象**。继续追查后确认：AIoT Toolkit 将 `app.js` 与每个 `pages/*.js` 分别打包；模块级 `let conn`、`store`、API 的 `pending Map` 和事件总线都各自隔离。此前虽然 `app.ux` 执行了 `interconn.init()`，诊断页自己的模块副本仍看到 `conn === null`；同理，手机挑战 nonce 若只写入 app bundle 的 store，诊断页副本也读不到。

这解释了用户在手环诊断页看到的：

```text
系统互联：未连接
连接诊断：诊断失败
手机桥接服务：不可达
手机消息监听：不可达
conn not init
```

## 已修复内容

1. 在 `app.ux` 提供唯一的 app 级 interconnect / RPC / event facade；各页面在 `onInit` 中经 Vela 官方 `this.$app.$def` 挂接根实例。
2. 各页 RPC 请求、响应 pending 状态和服务端事件统一委托给 app 根 bundle；页面本地重复的 `onmessage` handler 会在挂接时移除，避免覆盖根监听器。
3. `diagnose()` 和 `queryReadyState()` 不再因本地 `conn` 为空直接返回 `conn not init`，而会确保初始化；发送前根据 Vela `getReadyState()` 的实际状态判断，不只依赖可能漏掉的本地 `onopen` 缓存。
4. 手机挑战 nonce 通过 `router.push` 参数传递，并在确认页的 `protected` 属性中接收；不依赖跨 bundle 的内存 store。
5. 保留前一轮两个修复：挑战监听先于异步设置读取注册；收到挑战后自动打开诊断确认页；首页调用正确的 `interconn.diagnose(8000)`。
6. 将包版本递增到 **`0.1.2 / versionCode 3`**，与已安装并显示 `conn not init` 的旧包明确区分。

## 构建与验证

- 按锁文件执行 `npm ci`，成功安装 820 个包；构建工具为 AIoT Toolkit `2.0.5`，Node `v22.13.0`、npm `10.9.2`。教程建议 Node 18/20 或项目已验证版本；当前环境 Node 22 的实际构建成功，未改 lockfile。
- `npm run build`：**build success**，Debug RPK 已生成。
- RPK 归档完整性、包名/版本、根 facade、挑战 nonce 参数和 5 个业务页面的 facade 接入均检查通过；编译后的诊断页 bundle 不再包含 `conn not init` 错误分支。
- 源码级跨 bundle 检查：**9/9 通过**；最终 RPK 包内检查通过；九文件补丁对原始源码 dry-run 全部无冲突。
- 双端静态验收脚本：**43/44**。唯一未通过的是 APK 产物级校验工具 `apksigner` 未安装，故脚本无法读取该证书项；脚本的其余签名配置、JNI pin、RPC/event 等检查通过。另用 `keytool -printcert -jarfile` 读取现有 ARM64 APK，SHA-256 与 Vela debug PEM 一致：`FA:63:81:C8:28:9E:7A:DB:AE:E2:93:7F:58:32:A9:1B:40:86:CA:62:1E:A8:2C:FE:7B:30:AA:93:A4:1D:74:28`。这没有重建或更改 Android APK。
- 构建仍报告原有输入法资源相对路径/未使用变量警告，以及 `enable-custom-component` 弃用提示；不影响构建成功。
- **未在此环境安装到 Band 9，未完成设备实测。**

## 交付文件

- `com.hrk.tgwear.debug.0.1.2.rpk`：最新修复版手环 Debug 包；请勿再装上一轮 `0.1.1`。
- `tgwear-vela-interconnect-fix.patch`：完整源码增量补丁，含 9 个文件；不包含私钥、证书或 keystore。
- `vela-build.log`：0.1.2 最终构建日志。
- `SHA256SUMS.txt`：上述交付文件的校验值。

## Band 9 下一步复测

1. 手机继续使用当前 ARM64 TG Wear APK；本轮没有更改 APK。
2. 用 **AIoT-IDE 官方设备调试/安装流程**将新 `.rpk` 安装到 Xiaomi Band 9。RPK 不是 Android APK，不要用 `adb install`。确保设备实际运行 **`0.1.2 / versionCode 3`**；如 IDE 仍启动旧代码，卸载旧快应用后重新安装该版本。
3. 手机打开连接诊断页并发起手机→手环连接挑战。
4. 手环应自动进入“手机连接诊断”页，不再显示 `conn not init`；连接正常时应显示系统互联已连接/诊断正常，手机桥接服务和监听状态可查询。
5. 点击手环 **“确认手机连接”**，成功标准是手环显示 **“手机↔手环双向通信成功”**，手机端也收到 ACK。
6. 若仍失败，请截图同时收集手机诊断页（service、transport、lastError、挑战时间）、手环诊断页（系统互联、连接诊断、双向确认状态）、从点击发送前开始到失败后约 30 秒的完整 `adb logcat`，并记录设备固件/Vela 版本及手机 APK、手环 RPK 版本。