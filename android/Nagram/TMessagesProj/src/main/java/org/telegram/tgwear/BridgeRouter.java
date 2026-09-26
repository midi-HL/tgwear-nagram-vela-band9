/*
 * tgwear: JSON-RPC 路由器
 *
 * 职责：
 *   1. 解析手表端发来的 JSON 请求 {id, method, params}
 *   2. 按 method 路由到对应 Handler
 *   3. 把 Handler 返回的 result 包成 {id, result} 发回去
 *   4. Handler 抛异常时返回 {id, error: {code, msg}}
 *
 * 协议定义见 plan-quickapp-tg.md 第四节，与手表端 src/utils/api.js 严格对齐。
 */
package org.telegram.tgwear;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.tgwear.handlers.AuthHandler;
import org.telegram.tgwear.handlers.DialogsHandler;
import org.telegram.tgwear.handlers.MessagesHandler;
import org.telegram.tgwear.handlers.PeersHandler;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class BridgeRouter {

    private static final String TAG = "tgwear/Router";

    /** Handler 接口：每个 method 对应一个实现 */
    public interface Handler {
        /**
         * @param params 手表端传来的 params，可能为 null
         * @return result 对象，会被序列化成 JSON 发回
         * @throws Exception 任何异常都会被捕获并转成 error 响应
         */
        @NonNull
        Object handle(@Nullable JSONObject params) throws Exception;
    }

    @NonNull
    private final Map<String, Handler> handlers = new HashMap<>();

    @NonNull
    private final WearConnection connection;

    /** 处理线程独立，避免阻塞主线程 */
    @NonNull
    private final java.util.concurrent.ExecutorService executor = new ThreadPoolExecutor(1, 1, 0L,
        TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), r -> {
            Thread thread = new Thread(r, "TGWear-router");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final Object responseLock = new Object();
    private final Object eventLock = new Object();
    private final Queue<String> pendingEvents = new ArrayDeque<>();
    private static final int MAX_PENDING_EVENTS = 64;
    private final ScheduledExecutorService eventFlushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "TGWear-event-flush");
        thread.setDaemon(true);
        return thread;
    });
    private final LinkedHashMap<String, String> responseCache = new LinkedHashMap<String, String>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 128; }
    };
    private final LinkedHashMap<String, String> operationCache = new LinkedHashMap<String, String>(128, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 128; }
    };

    public BridgeRouter(@NonNull WearConnection connection) {
        this.connection = connection;
        eventFlushExecutor.scheduleAtFixedRate(this::flushPendingEvents, 500, 500, TimeUnit.MILLISECONDS);

        // 注册 handlers
        // 基础
        register(WearConstants.Method.AUTH_GET_STATE,        new AuthHandler());
        register(WearConstants.Method.AUTH_LOGOUT,           new AuthHandler.LogoutHandler());
        register(WearConstants.Method.BRIDGE_GET_STATUS, params -> WearDiagnosticStatus.snapshot());
        register(WearConstants.Method.BRIDGE_PROBE_ACK, params -> {
            String nonce = params == null ? "" : params.optString("nonce", "");
            boolean ok = WearDiagnosticStatus.watchProbeAcknowledged(nonce);
            Map<String, Object> result = new HashMap<>();
            result.put("ok", ok);
            result.put("serviceRunning", true);
            result.put("transportReady", connection.isReady());
            result.put("status", WearDiagnosticStatus.snapshot());
            return result;
        });
        // 会话
        register(WearConstants.Method.DIALOGS_GET,           new DialogsHandler.GetDialogsHandler(this));
        register(WearConstants.Method.DIALOGS_PIN,           new DialogsHandler.PinHandler());
        register(WearConstants.Method.DIALOGS_MUTE,          new DialogsHandler.MuteHandler());
        register(WearConstants.Method.DIALOGS_ARCHIVE,       new DialogsHandler.ArchiveHandler());
        register(WearConstants.Method.DIALOGS_DELETE,        new DialogsHandler.DeleteHandler());
        // 消息
        register(WearConstants.Method.MESSAGES_GET_HISTORY,  new MessagesHandler.GetHistoryHandler(this));
        register(WearConstants.Method.MESSAGES_SEND_TEXT,    new MessagesHandler.SendTextHandler());
        register(WearConstants.Method.MESSAGES_REPLY,        new MessagesHandler.ReplyHandler());
        register(WearConstants.Method.MESSAGES_EDIT,         new MessagesHandler.EditHandler());
        register(WearConstants.Method.MESSAGES_DELETE,       new MessagesHandler.DeleteHandler());
        register(WearConstants.Method.MESSAGES_FORWARD,      new MessagesHandler.ForwardHandler());
        register(WearConstants.Method.MESSAGES_MARK_READ,    new MessagesHandler.MarkReadHandler());
        register(WearConstants.Method.MESSAGES_SET_TYPING,   new MessagesHandler.SetTypingHandler());
        register(WearConstants.Method.MESSAGES_SEARCH,       new MessagesHandler.SearchHandler());
        // 资料
        register(WearConstants.Method.PEERS_GET,             new PeersHandler.GetHandler());
        register(WearConstants.Method.PEERS_PIN_MESSAGE,     new PeersHandler.PinMessageHandler());
    }

    private void register(@NonNull String method, @NonNull Handler h) {
        handlers.put(method, h);
    }

    /** 处理一帧来自手表的 JSON 字符串 */
    public void handle(@NonNull final String raw) {
        try { executor.execute(() -> {
            try {
                JSONObject req = new JSONObject(raw);
                if (!req.optBoolean(WearConstants.RPC_MARKER, false) || !req.has(WearConstants.FIELD_ID)) return;
                Object id = req.get(WearConstants.FIELD_ID);
                if (!(id instanceof String) && !(id instanceof Number)) return;
                String requestId = String.valueOf(id);
                if (requestId.length() > 96) return;
                String cached;
                synchronized (responseLock) { cached = responseCache.get(requestId); }
                if (cached != null) { connection.send(cached); return; }
                String method = req.optString(WearConstants.FIELD_METHOD, "");
                JSONObject params = req.optJSONObject(WearConstants.FIELD_PARAMS);
                String operationId = params == null ? "" : params.optString("operation_id", "");
                if (operationId.length() > 96) operationId = "";
                if (!operationId.isEmpty()) {
                    String prior;
                    synchronized (responseLock) { prior = operationCache.get(operationId); }
                    if (prior != null) {
                        JSONObject replay = new JSONObject(prior);
                        replay.put(WearConstants.FIELD_ID, id);
                        cacheAndSend(requestId, replay.toString());
                        return;
                    }
                }

                Handler h = handlers.get(method);
                if (h == null) {
                    sendError(requestId, id, WearConstants.Code.METHOD_NOT_FOUND, "method not found: " + method);
                    return;
                }

                Object result;
                try {
                    result = h.handle(params);
                } catch (RpcException e) {
                    sendError(requestId, id, e.code, e.getMessage());
                    return;
                } catch (Throwable t) {
                    Log.e(TAG, "handler failed method=" + method, t);
                    sendError(requestId, id, WearConstants.Code.INTERNAL_ERROR, "internal: " + t.getMessage());
                    return;
                }

                sendResult(requestId, id, result, operationId);
            } catch (JSONException e) {
                Log.w(TAG, "request parse failed bytes=" + raw.length(), e);
            }
        }); } catch (RuntimeException rejected) {
            Log.e(TAG, "router queue full; incoming request rejected", rejected);
        }
    }

    /** 发送 RPC 成功响应 */
    private void sendResult(String requestId, Object id, @Nullable Object result, String operationId) {
        try {
            JSONObject resp = new JSONObject();
            resp.put(WearConstants.FIELD_ID, id);
            // 用 toJSON 把对象转成 JSON（用 TdJsonConverter 统一处理）
            Object json = TdJsonConverter.toJSON(result);
            resp.put(WearConstants.FIELD_RESULT, json);
            if (!operationId.isEmpty()) synchronized (responseLock) { operationCache.put(operationId, resp.toString()); }
            cacheAndSend(requestId, resp.toString());
        } catch (Throwable t) {
            Log.e(TAG, "sendResult failed for id=" + id, t);
        }
    }

    /** 发送 RPC 错误响应 */
    private void sendError(String requestId, Object id, int code, @Nullable String msg) {
        try {
            JSONObject resp = new JSONObject();
            resp.put(WearConstants.FIELD_ID, id);
            JSONObject err = new JSONObject();
            err.put(WearConstants.FIELD_CODE, code);
            err.put(WearConstants.FIELD_MSG, msg == null ? "" : msg);
            resp.put(WearConstants.FIELD_ERROR, err);
            cacheAndSend(requestId, resp.toString());
        } catch (JSONException ignore) {
        }
    }

    private void cacheAndSend(String requestId, String response) {
        synchronized (responseLock) { responseCache.put(requestId, response); }
        if (!connection.send(response)) Log.w(TAG, "response queued while TGW session unavailable id=" + requestId);
    }

    /** 推送事件到手表端（单向） */
    public void pushEvent(@NonNull String event, @Nullable Object data) {
        try {
            JSONObject msg = new JSONObject();
            msg.put(WearConstants.EVENT_MARKER, true);
            msg.put(WearConstants.FIELD_EVENT, event);
            msg.put(WearConstants.FIELD_DATA, TdJsonConverter.toJSON(data));
            String encoded = msg.toString();
            synchronized (eventLock) {
                if (pendingEvents.isEmpty() && connection.send(encoded)) return;
                if (pendingEvents.size() >= MAX_PENDING_EVENTS) {
                    pendingEvents.poll();
                    Log.w(TAG, "pending wearable event queue full; oldest event dropped");
                }
                pendingEvents.offer(encoded);
            }
        } catch (Throwable t) {
            Log.e(TAG, "pushEvent " + event + " failed", t);
        }
    }

    public void flushPendingEvents() {
        synchronized (eventLock) {
            while (!pendingEvents.isEmpty()) {
                if (!connection.send(pendingEvents.peek())) return;
                pendingEvents.poll();
            }
        }
    }

    public void shutdown() {
        executor.shutdown();
        eventFlushExecutor.shutdownNow();
        synchronized (responseLock) { responseCache.clear(); operationCache.clear(); }
        synchronized (eventLock) { pendingEvents.clear(); }
    }

    /** RPC 异常 */
    public static class RpcException extends Exception {
        public final int code;
        public RpcException(int code, @NonNull String msg) {
            super(msg);
            this.code = code;
        }
    }

}
