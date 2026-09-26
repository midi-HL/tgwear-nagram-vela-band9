/*
 * tgwear: Xiaomi Wearable SDK 1.4 transport implementation.
 * Transport diagnostics are recorded in WearDiagnosticStatus for the local status screen.
 */
package org.telegram.tgwear;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.AuthApi;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class XiaomiWearConnection implements WearConnection {
    private static final String TAG = "TGWear/Xiaomi";
    private static final String WEAR_APP_ROUTE = "/pages/splash";
    private static final long RETRY_INTERVAL_MS = 5000L;
    private static final String ALREADY_REGISTERED_HINT = "registered";

    private final NodeApi nodeApi;
    private final AuthApi authApi;
    private final MessageApi messageApi;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    @Nullable private Node currentNode;
    @Nullable private Listener listener;
    @Nullable private OnMessageReceivedListener sdkListener;
    @Nullable private String registeredNodeId;
    private volatile boolean ready;
    private final AtomicBoolean retryScheduled = new AtomicBoolean(false);
    private final AtomicBoolean discoveryInFlight = new AtomicBoolean(false);
    private volatile boolean destroyed;

    public XiaomiWearConnection(@NonNull Context context) {
        Context app = context.getApplicationContext();
        nodeApi = Wearable.getNodeApi(app);
        authApi = Wearable.getAuthApi(app);
        messageApi = Wearable.getMessageApi(app);
        WearDiagnosticStatus.sdkAvailable(true, null);
        Log.i(TAG, "Xiaomi Wearable SDK APIs initialized");
    }

    @Override
    public void init() {
        destroyed = false;
        reconnect();
    }

    @Override
    public void reconnect() {
        if (destroyed) destroyed = false;
        retryScheduled.set(false);
        mainHandler.removeCallbacks(retryRunnable);
        WearDiagnosticStatus.stage("手动重新发现已连接手环");
        Log.i(TAG, "manual reconnect requested");
        discoverNode();
    }

    private final Runnable retryRunnable = () -> {
        retryScheduled.set(false);
        if (!destroyed && !ready) discoverNode();
    };

    private void discoverNode() {
        if (destroyed || !discoveryInFlight.compareAndSet(false, true)) return;
        WearDiagnosticStatus.stage("正在查询已连接的 Xiaomi 手环");
        nodeApi.getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    discoveryInFlight.set(false);
                    if (destroyed) return;
                    if (nodes == null || nodes.isEmpty()) {
                        Log.w(TAG, "no connected Xiaomi wearable node; will retry");
                        synchronized (lock) {
                            currentNode = null;
                            nodeIdClearIfNeeded();
                        }
                        WearDiagnosticStatus.transport(false, "小米 SDK 未发现已连接手环");
                        setReady(false, "no-node");
                        scheduleRetry();
                        return;
                    }
                    Log.i(TAG, "connected Xiaomi wearable nodes=" + nodes.size());
                    Node chosen = pickNode(nodes);
                    synchronized (lock) { currentNode = chosen; }
                    String id = chosen.id == null ? "" : chosen.id;
                    Log.i(TAG, "selected node=" + id);
                    WearDiagnosticStatus.nodeFound(id);
                    checkWearApp(chosen);
                    checkPermission(chosen);
                })
                .addOnFailureListener(error -> {
                    discoveryInFlight.set(false);
                    if (destroyed) return;
                    Log.w(TAG, "getConnectedNodes failed", error);
                    WearDiagnosticStatus.error("查询已连接手环失败", error);
                    setReady(false, error);
                    scheduleRetry();
                });
    }

    private void nodeIdClearIfNeeded() {
        WearDiagnosticStatus.nodeFound("");
    }

    @NonNull
    private Node pickNode(@NonNull List<Node> nodes) {
        String registered;
        synchronized (lock) { registered = registeredNodeId; }
        if (registered != null) {
            for (Node node : nodes) {
                if (registered.equals(node.id)) return node;
            }
        }
        return nodes.get(0);
    }

    private void checkPermission(@NonNull Node node) {
        if (destroyed) return;
        WearDiagnosticStatus.permission(null);
        authApi.checkPermissions(node.id, new Permission[] { Permission.DEVICE_MANAGER })
                .addOnSuccessListener(granted -> {
                    if (destroyed) return;
                    boolean ok = granted != null && granted.length > 0 && granted[0];
                    WearDiagnosticStatus.permission(ok);
                    Log.i(TAG, "device-manager permission granted=" + ok);
                    if (ok) {
                        registerListener(node);
                    } else {
                        WearDiagnosticStatus.stage("正在请求 Xiaomi 设备管理授权");
                        Log.i(TAG, "requesting device-manager permission");
                        authApi.requestPermission(node.id, Permission.DEVICE_MANAGER)
                                .addOnSuccessListener(result -> {
                                    if (destroyed) return;
                                    boolean accepted = false;
                                    if (result != null) {
                                        for (Permission permission : result) {
                                            if (permission != null && "data_manager".equals(permission.getName())) {
                                                accepted = true;
                                                break;
                                            }
                                        }
                                    }
                                    WearDiagnosticStatus.permission(accepted);
                                    Log.i(TAG, "requestPermission completed; device-manager granted=" + accepted);
                                    if (accepted) {
                                        registerListener(node);
                                    } else {
                                        // Do not repeatedly raise an authorization UI after the user declines.
                                        WearDiagnosticStatus.stage("设备管理授权未批准；可在诊断页手动重试");
                                        setReady(false, "permission-not-granted");
                                    }
                                })
                                .addOnFailureListener(error -> {
                                    if (destroyed) return;
                                    Log.w(TAG, "requestPermission failed", error);
                                    WearDiagnosticStatus.permission(false);
                                    WearDiagnosticStatus.error("申请 Xiaomi 设备管理授权失败", error);
                                    setReady(false, error);
                                    scheduleRetry();
                                });
                    }
                })
                .addOnFailureListener(error -> {
                    if (destroyed) return;
                    Log.w(TAG, "checkPermissions failed", error);
                    WearDiagnosticStatus.error("查询 Xiaomi 设备管理授权失败", error);
                    setReady(false, error);
                    scheduleRetry();
                });
    }

    private void checkWearApp(@NonNull Node node) {
        if (destroyed) return;
        WearDiagnosticStatus.stage("正在检查手环端 TG Wear 是否安装");
        nodeApi.isWearAppInstalled(node.id)
                .addOnSuccessListener(installed -> {
                    if (destroyed) return;
                    boolean ok = Boolean.TRUE.equals(installed);
                    WearDiagnosticStatus.wearableApp(ok);
                    Log.i(TAG, "TG Wear quick app installed=" + ok + " on node=" + node.id);
                    if (!ok) {
                        setReady(false, "wear-app-not-installed");
                        scheduleRetry();
                        return;
                    }
                    WearDiagnosticStatus.stage("正在打开手环端 TG Wear");
                    nodeApi.launchWearApp(node.id, WEAR_APP_ROUTE)
                            .addOnSuccessListener(ignored -> Log.i(TAG, "launchWearApp ok route=" + WEAR_APP_ROUTE))
                            .addOnFailureListener(error -> {
                                Log.w(TAG, "launchWearApp failed", error);
                                WearDiagnosticStatus.error("打开手环端 TG Wear 失败", error);
                            });
                })
                .addOnFailureListener(error -> {
                    if (destroyed) return;
                    Log.w(TAG, "isWearAppInstalled failed", error);
                    WearDiagnosticStatus.error("检查手环端 TG Wear 安装状态失败", error);
                    setReady(false, error);
                    scheduleRetry();
                });
    }

    private void registerListener(@NonNull Node node) {
        boolean already;
        synchronized (lock) {
            already = node.id.equals(registeredNodeId);
            if (sdkListener == null) {
                sdkListener = (sourceNodeId, message) -> {
                    if (message == null) return;
                    WearDiagnosticStatus.watchMessageReceived();
                    Listener target = listener;
                    if (target != null) target.onMessage(new String(message, StandardCharsets.UTF_8));
                };
            }
        }
        if (already) {
            WearDiagnosticStatus.transport(true, null);
            setReady(true, node.id);
            return;
        }

        WearDiagnosticStatus.stage("正在注册手机端消息监听");
        messageApi.addListener(node.id, sdkListener)
                .addOnSuccessListener(ignored -> {
                    if (destroyed) return;
                    synchronized (lock) { registeredNodeId = node.id; }
                    Log.i(TAG, "message listener registered on node=" + node.id);
                    WearDiagnosticStatus.transport(true, null);
                    setReady(true, node.id);
                })
                .addOnFailureListener(error -> {
                    if (destroyed) return;
                    String msg = error == null ? "" : String.valueOf(error.getMessage());
                    if (msg.toLowerCase().contains(ALREADY_REGISTERED_HINT)) {
                        Log.w(TAG, "listener already registered, continue");
                        synchronized (lock) { registeredNodeId = node.id; }
                        WearDiagnosticStatus.transport(true, null);
                        setReady(true, node.id);
                        return;
                    }
                    Log.w(TAG, "addListener failed", error);
                    WearDiagnosticStatus.error("注册手机端消息监听失败", error);
                    setReady(false, error);
                    scheduleRetry();
                });
    }

    @Override
    public boolean send(@NonNull String data) {
        Node node;
        synchronized (lock) { node = currentNode; }
        if (node == null || !ready) {
            Log.w(TAG, "send rejected: transport not ready");
            return false;
        }
        byte[] payload = data.getBytes(StandardCharsets.UTF_8);
        messageApi.sendMessage(node.id, payload)
                .addOnSuccessListener(ignored -> Log.d(TAG, "sendMessage accepted bytes=" + payload.length))
                .addOnFailureListener(error -> {
                    if (destroyed) return;
                    Log.w(TAG, "sendMessage failed", error);
                    WearDiagnosticStatus.error("发送消息到手环失败", error);
                    setReady(false, error);
                    scheduleRetry();
                });
        return true;
    }

    @Override public void setMessageListener(@Nullable Listener listener) { this.listener = listener; }
    @Override public boolean isReady() { return ready; }

    private void scheduleRetry() {
        if (destroyed || !retryScheduled.compareAndSet(false, true)) return;
        WearDiagnosticStatus.stage("连接未就绪，5 秒后自动重试");
        mainHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS);
    }

    private void setReady(boolean value, @Nullable Object info) {
        boolean changed = ready != value;
        ready = value;
        Listener target = listener;
        if (changed && target != null) target.onReadyStateChanged(value, info);
    }

    @Override
    public void destroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        retryScheduled.set(false);
        discoveryInFlight.set(false);
        Node node;
        synchronized (lock) { node = currentNode; currentNode = null; }
        if (node != null && sdkListener != null) {
            messageApi.removeListener(node.id)
                    .addOnFailureListener(error -> Log.w(TAG, "removeListener failed", error));
        }
        setReady(false, null);
        sdkListener = null;
        registeredNodeId = null;
        listener = null;
    }
}
