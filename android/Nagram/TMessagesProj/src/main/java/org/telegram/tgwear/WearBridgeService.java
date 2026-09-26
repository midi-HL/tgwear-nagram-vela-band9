/*
 * tgwear: 与手表端通信的前台 Service
 *
 * 生命周期：
 *   onCreate  → 创建 WearConnection + BridgeRouter + 启动 Telegram 推送监听
 *   onStartCommand → 升级为前台 Service，保活
 *   onDestroy → 释放连接、注销监听
 *
 * 该 Service 不直接处理 RPC，所有请求交给 BridgeRouter 路由到具体 Handler。
 */
package org.telegram.tgwear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import org.json.JSONObject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgwear.NotificationCenterBridge;

/**
 * 桥接 Service。
 *
 * 注意：不要把这个类移到 org.telegram.messenger 包下，避免与现有 Telegram Service 命名冲突。
 */
public class WearBridgeService extends Service {

    public static final String ACTION_RECONNECT = "org.telegram.tgwear.action.RECONNECT";
    public static final String ACTION_PROBE = "org.telegram.tgwear.action.PROBE";

    private static final String TAG = "tgwear/Service";
    private static final String CHANNEL_ID = "tgwear_bridge";
    private static final int NOTIFICATION_ID = 0x7701;

    @Nullable
    private WearConnection connection;

    @Nullable
    private BridgeRouter router;

    @Nullable
    private NotificationCenterBridge notificationBridge;

    @Override
    public void onCreate() {
        super.onCreate();
        WearDiagnosticStatus.serviceStarted();
        try {
            // 1. Create the verified Xiaomi Wearable SDK transport.
            connection = new ReliableWearConnection(createConnection());

            // 2. 创建 Router
            router = new BridgeRouter(connection);

            // 3. 启动 Telegram 推送桥接
            notificationBridge = new NotificationCenterBridge(router);
            notificationBridge.start();

            // 4. 设置连接监听 → 收到请求就交给 Router
            connection.setMessageListener(new WearConnection.Listener() {
                @Override
                public void onReadyStateChanged(boolean ready, @Nullable Object info) {
                    Log.i(TAG, "connection ready=" + ready);
                    if (router != null) {
                        router.pushEvent(
                            WearConstants.Event.CONNECTION_STATE,
                            new java.util.HashMap<String, Object>() {{
                                put("ready", ready);
                                put("reconnected", info != null);
                            }}
                        );
                    }
                }

                @Override
                public void onMessage(@Nullable String message) {
                    if (message == null || message.isEmpty()) return;
                    if (router != null) router.handle(message);
                }
            });

            connection.init();
            Log.i(TAG, "WearBridgeService started");
        } catch (Throwable t) {
            WearDiagnosticStatus.error("桥接服务初始化失败", t);
            FileLog.e(TAG + " onCreate failed", t);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        ensureChannel();
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TG Wear Bridge")
            .setContentText("正在与手表端 Telegram 通信")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        if (intent != null && ACTION_RECONNECT.equals(intent.getAction())) {
            WearDiagnosticStatus.stage("收到用户重连请求");
            if (connection != null) connection.reconnect();
            else WearDiagnosticStatus.error("桥接连接对象尚未创建", null);
        } else if (intent != null && ACTION_PROBE.equals(intent.getAction())) {
            sendPhoneProbe();
        }
        return START_STICKY;
    }

    private void sendPhoneProbe() {
        if (connection == null || router == null || !connection.isReady()) {
            Log.w(TAG, "phone probe rejected: transport not ready");
            WearDiagnosticStatus.protocolState(false, "无法发起测试：Xiaomi transport/TGW/2 session 尚未就绪");
            WearDiagnosticStatus.stage("手机连接挑战暂不可用");
            return;
        }
        String nonce = java.util.UUID.randomUUID().toString();
        WearDiagnosticStatus.phoneProbeSent(nonce);
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("nonce", nonce);
        data.put("sentAt", System.currentTimeMillis());
        router.pushEvent(WearConstants.Event.PHONE_PROBE, data);
        Log.i(TAG, "phone-to-watch diagnostic challenge sent nonce=" + nonce);
    }

    @Override
    public void onDestroy() {
        try {
            if (notificationBridge != null) notificationBridge.stop();
            if (router != null) router.shutdown();
            if (connection != null) connection.destroy();
        } catch (Throwable t) {
            FileLog.e(TAG + " onDestroy failed", t);
        }
        WearDiagnosticStatus.serviceStopped();
        connection = null;
        router = null;
        notificationBridge = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Create the transport backed by xms-wearable-lib_1.4_release.aar. */
    @NonNull
    private WearConnection createConnection() {
        try {
            return new XiaomiWearConnection(this);
        } catch (Throwable e) {
            Log.e(TAG, "Xiaomi Wearable SDK unavailable; bridge remains offline", e);
            WearDiagnosticStatus.sdkAvailable(false, e);
            return new StubWearConnection();
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "TG Wear Bridge", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Bridge between phone Telegram and watch quick-app");
            nm.createNotificationChannel(ch);
        }
    }
}
