package org.telegram.tgwear;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Process-local status shared by the bridge service and its diagnostic screen. */
public final class WearDiagnosticStatus {
    private static boolean serviceRunning;
    private static boolean sdkAvailable;
    private static boolean transportReady;
    private static boolean protocolReady;
    private static String protocolStage = "等待 TGW/2 握手";
    private static long rxFrames;
    private static long txFrames;
    private static long rxLogicalMessages;
    private static long lastProtocolFrameAt;
    private static int protocolChunkBytes;
    @Nullable private static Boolean permissionGranted;
    @Nullable private static Boolean wearableAppInstalled;
    private static String stage = "等待桥接服务";
    private static String nodeId = "";
    private static String lastError = "";
    private static String challengeNonce = "";
    private static long updatedAt;
    private static long lastWatchMessageAt;
    private static long lastPhoneProbeAt;
    private static long lastWatchAckAt;

    private WearDiagnosticStatus() {}

    public static synchronized void serviceStarted() {
        serviceRunning = true;
        transportReady = false;
        protocolReady = false;
        protocolStage = "等待 TGW/2 握手";
        rxFrames = 0;
        txFrames = 0;
        rxLogicalMessages = 0;
        lastProtocolFrameAt = 0;
        protocolChunkBytes = 0;
        stage = "桥接服务已启动";
        touch();
    }

    public static synchronized void serviceStopped() {
        serviceRunning = false;
        transportReady = false;
        protocolReady = false;
        protocolStage = "服务停止";
        stage = "桥接服务已停止";
        touch();
    }

    public static synchronized void sdkAvailable(boolean available, @Nullable Throwable error) {
        sdkAvailable = available;
        if (available) {
            lastError = "";
            stage = "小米穿戴 SDK 已初始化";
        } else {
            stage = "小米穿戴 SDK 初始化失败";
            lastError = describe(error);
        }
        touch();
    }

    public static synchronized void stage(String value) {
        stage = value == null ? "未知状态" : value;
        touch();
    }

    public static synchronized void nodeFound(String id) {
        nodeId = id == null ? "" : id;
        stage = "已发现手环";
        lastError = "";
        touch();
    }

    public static synchronized void permission(@Nullable Boolean granted) {
        permissionGranted = granted;
        stage = Boolean.TRUE.equals(granted) ? "设备管理授权已批准" :
                (Boolean.FALSE.equals(granted) ? "等待设备管理授权" : "正在检查设备管理授权");
        touch();
    }

    public static synchronized void wearableApp(@Nullable Boolean installed) {
        wearableAppInstalled = installed;
        if (Boolean.TRUE.equals(installed)) stage = "手环端 TG Wear 已安装";
        else if (Boolean.FALSE.equals(installed)) stage = "手环端 TG Wear 未安装";
        touch();
    }

    public static synchronized void transport(boolean ready, @Nullable String detail) {
        transportReady = ready;
        protocolReady = false;
        protocolStage = ready ? "等待 TGW/2 握手" : "Xiaomi transport down";
        stage = ready ? "手机端消息监听已就绪" : (detail == null ? "等待手环连接" : detail);
        if (ready) lastError = "";
        else if (detail != null) lastError = detail;
        touch();
    }

    public static synchronized void protocolState(boolean ready, @Nullable String detail) {
        protocolReady = ready;
        protocolStage = detail == null ? (ready ? "TGW/2 ready" : "TGW/2 offline") : detail;
        touch();
    }

    public static synchronized void protocolFrame(boolean incoming) {
        if (incoming) rxFrames++; else txFrames++;
        lastProtocolFrameAt = System.currentTimeMillis();
        touch();
    }

    public static synchronized void protocolPayloadReceived() {
        rxLogicalMessages++;
        touch();
    }

    public static synchronized void protocolChunkBytes(int value) {
        protocolChunkBytes = Math.max(0, Math.min(2048, value));
        touch();
    }

    public static synchronized void error(String where, @Nullable Throwable error) {
        stage = where == null ? "连接错误" : where;
        lastError = describe(error);
        transportReady = false;
        protocolReady = false;
        protocolStage = "连接错误";
        touch();
    }

    public static synchronized void watchMessageReceived() {
        lastWatchMessageAt = System.currentTimeMillis();
        touch();
    }

    public static synchronized void phoneProbeSent(String nonce) {
        challengeNonce = nonce == null ? "" : nonce;
        lastPhoneProbeAt = System.currentTimeMillis();
        stage = "已发送手机→手环测试，等待手环确认";
        lastError = "";
        touch();
    }

    public static synchronized boolean watchProbeAcknowledged(String nonce) {
        if (nonce == null || challengeNonce.isEmpty() || !challengeNonce.equals(nonce)) {
            lastError = "收到不匹配的手环测试确认";
            stage = "手环测试确认不匹配";
            touch();
            return false;
        }
        lastWatchAckAt = System.currentTimeMillis();
        stage = "手机↔手环双向握手成功";
        lastError = "";
        challengeNonce = "";
        touch();
        return true;
    }

    public static synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceRunning", serviceRunning);
        result.put("sdkAvailable", sdkAvailable);
        result.put("transportReady", transportReady);
        result.put("protocolReady", protocolReady);
        result.put("protocolStage", protocolStage);
        result.put("rxFrames", rxFrames);
        result.put("txFrames", txFrames);
        result.put("rxLogicalMessages", rxLogicalMessages);
        result.put("protocolChunkBytes", protocolChunkBytes);
        result.put("lastProtocolFrameAt", format(lastProtocolFrameAt));
        result.put("permissionGranted", permissionGranted);
        result.put("wearableAppInstalled", wearableAppInstalled);
        result.put("stage", stage);
        result.put("nodeId", nodeId);
        result.put("lastError", lastError);
        result.put("updatedAt", format(updatedAt));
        result.put("lastWatchMessageAt", format(lastWatchMessageAt));
        result.put("lastPhoneProbeAt", format(lastPhoneProbeAt));
        result.put("lastWatchAckAt", format(lastWatchAckAt));
        return result;
    }

    private static void touch() { updatedAt = System.currentTimeMillis(); }

    private static String format(long value) {
        if (value <= 0) return "—";
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(value));
    }

    private static String describe(@Nullable Throwable error) {
        if (error == null) return "未知错误";
        String message = error.getMessage();
        String result = error.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
        return result.length() > 200 ? result.substring(0, 200) : result;
    }
}
