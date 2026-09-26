/*
 * tgwear: messages.* RPC 处理器
 *
 * 设计要点：
 *   1. 所有对 Telegram 数据的写操作都通过 Nagram 当前真实的
 *      AccountInstance / SendMessagesHelper / MessagesController API 完成，
 *      不自行拼 MTProto 请求（除历史拉取刷新缓存这一读操作）。
 *   2. 返回给手环的数据一律走 TdJsonConverter，避免传大对象。
 *   3. 手环通道是"重试友好"的：读取类请求先回缓存，同时后台刷新。
 */
package org.telegram.tgwear.handlers;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgwear.BridgeRouter;
import org.telegram.tgwear.TdJsonConverter;
import org.telegram.tgwear.WearConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MessagesHandler {
    private static final String TAG = "TGWear/Messages";
    private static final ConcurrentHashMap<Integer, String> pendingOperationIds = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> pendingOperationsByText = new ConcurrentHashMap<>();

    /* ============================ 历史 ============================ */

    public static class GetHistoryHandler implements BridgeRouter.Handler {
        private final BridgeRouter router;
        public GetHistoryHandler(@NonNull BridgeRouter router) { this.router = router; }

        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null || !params.has("peer")) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "peer required");
            }
            String peer = params.getString("peer");
            int limit = clamp(params.optInt("limit", 6), 1, 6);
            long offsetId = params.optLong("offset_id", 0);
            if (!peer.startsWith("user:")) {
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "TG Wear currently syncs private user chats only");
            }
            long dialogId = requireDialogId(peer);
            if (dialogId <= 0) throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "private user peer required");

            int account = UserConfig.selectedAccount;
            MessagesController messages = MessagesController.getInstance(account);
            List<Object> result = collectFromCache(messages, dialogId, offsetId, limit);

            HashMap<String, Object> response = new HashMap<>();
            response.put("messages", result);
            response.put("has_more", result.size() >= limit);
            response.put("refreshing", true);

            // A real network response is asynchronously returned as an event; do not silently discard it.
            refreshHistoryFromServer(account, messages, dialogId, peer, limit, offsetId, router);
            return response;
        }
    }

    /* ============================ 发送 ============================ */

    public static class SendTextHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null) throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "params required");
            String peer = params.optString("peer", "");
            String text = params.optString("text", "");
            String operationId = params.optString("operation_id", "");
            long replyTo = params.optLong("reply_to", 0);
            if (peer.isEmpty() || text.isEmpty()) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "peer and text required");
            }
            if (text.length() > 2000) throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "text too long");
            long dialogId = requireDialogId(peer);
            AccountInstance accountInstance = AccountInstance.getInstance(UserConfig.selectedAccount);
            MessagesController messages = accountInstance.getMessagesController();
            if (messages.getInputPeer(dialogId) == null) {
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "inputPeer unavailable");
            }
            MessageObject reply = findReplyMessage(messages, dialogId, replyTo);
            if (!operationId.isEmpty()) enqueueOperation(peer, text, operationId);
            accountInstance.getSendMessagesHelper().sendMessage(
                    SendMessagesHelper.SendMessageParams.of(text, dialogId, reply, null, null,
                            true, null, null, null, true, 0, 0, null, false));

            int localId = findRecentLocalOutgoing(messages, dialogId, text);
            if (localId < 0 && !operationId.isEmpty()) {
                removeQueuedOperation(peer, text, operationId);
                pendingOperationIds.put(localId, operationId);
                if (pendingOperationIds.size() > 128) {
                    Integer oldest = pendingOperationIds.keySet().iterator().next();
                    pendingOperationIds.remove(oldest);
                }
            }

            HashMap<String, Object> response = new HashMap<>();
            response.put("message", optimisticMessage(peer, text, replyTo, localId, operationId));
            response.put("pending", true);
            return response;
        }
    }

    /** 与 messages.sendText 相同，但显式表达"回复"语义（reply_to 必填）。 */
    public static class ReplyHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null || params.optLong("reply_to", 0) == 0) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "reply_to required");
            }
            return new SendTextHandler().handle(params);
        }
    }

    /** 编辑自己发送的消息。 */
    public static class EditHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null) throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "params required");
            long dialogId = requireDialogId(params.optString("peer", ""));
            int messageId = params.optInt("message_id", 0);
            String text = params.optString("text", "");
            if (messageId == 0 || text.isEmpty()) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "message_id and text required");
            }
            MessageObject target = TdJsonConverter.findCachedMessage(dialogId, messageId);
            if (target == null) {
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "message not in cache");
            }
            if (!target.isOut()) {
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "can only edit own messages");
            }
            AccountInstance.getInstance(UserConfig.selectedAccount)
                    .getSendMessagesHelper()
                    .editMessage(target, text, false, null, null, 0, 0);

            HashMap<String, Object> response = new HashMap<>();
            response.put("ok", true);
            response.put("message_id", messageId);
            return response;
        }
    }

    /** 删除消息（单条或按 id 列表）。 */
    public static class DeleteHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null) throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "params required");
            long dialogId = requireDialogId(params.optString("peer", ""));
            ArrayList<Integer> ids = new ArrayList<>();
            if (params.has("message_id")) {
                ids.add(params.optInt("message_id", 0));
            }
            org.json.JSONArray arr = params.optJSONArray("message_ids");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) ids.add(arr.optInt(i, 0));
            }
            ids.removeIf(id -> id == 0);
            if (ids.isEmpty()) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "message_id required");
            }
            boolean revoke = params.optBoolean("revoke", true);
            // 真实签名：
            // deleteMessages(ArrayList<Integer> messages, ArrayList<Long> randoms,
            //                TLRPC.EncryptedChat encryptedChat, long dialogId,
            //                int topicId, boolean forAll, int mode)
            MessagesController.getInstance(UserConfig.selectedAccount)
                    .deleteMessages(ids, null, null, dialogId, 0, revoke, 0);

            HashMap<String, Object> response = new HashMap<>();
            response.put("ok", true);
            response.put("deleted", ids);
            return response;
        }
    }

    /**
     * 转发消息到另一个会话。
     *
     * 实现说明：用当前 Nagram 的 SendMessagesHelper.processForwardFromMyName，
     * 它会把原消息按"我自己发送"的方式投递到目标会话，不额外请求
     * TL_messages_forwardMessages 的多次往返，适合手环低功耗场景。
     */
    public static class ForwardHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null) throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "params required");
            long fromDialogId = requireDialogId(params.optString("peer", ""));
            int messageId = params.optInt("message_id", 0);
            long toDialogId = requireDialogId(params.optString("to_peer", ""));
            if (messageId == 0 || toDialogId == 0) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "message_id and to_peer required");
            }
            MessageObject target = TdJsonConverter.findCachedMessage(fromDialogId, messageId);
            if (target == null) {
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "message not in cache");
            }
            AccountInstance accountInstance = AccountInstance.getInstance(UserConfig.selectedAccount);
            if (accountInstance.getMessagesController().getInputPeer(toDialogId) == null) {
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "target peer unavailable");
            }
            accountInstance.getSendMessagesHelper()
                    .processForwardFromMyName(target, toDialogId, 0, 0, null);

            HashMap<String, Object> response = new HashMap<>();
            response.put("ok", true);
            response.put("to_peer", params.optString("to_peer", ""));
            return response;
        }
    }

    /**
     * 会话内搜索消息。
     *
     * 注意：这里只返回「本地缓存中」命中的消息，不发起服务器搜索，
     * 避免手环端因网络往返超时。若本地无结果，返回空列表并置
     * server_search=false，由上层决定是否提示用户。
     */
    public static class SearchHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null) throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "params required");
            String peer = params.optString("peer", "");
            String query = params.optString("query", "").toLowerCase();
            if (peer.isEmpty() || query.isEmpty()) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "peer and query required");
            }
            long dialogId = requireDialogId(peer);
            int limit = clamp(params.optInt("limit", 20), 1, 50);
            MessagesController messages = MessagesController.getInstance(UserConfig.selectedAccount);
            ArrayList<MessageObject> cached = messages.dialogMessage.get(dialogId);
            List<Object> hits = new ArrayList<>();
            if (cached != null) {
                ArrayList<MessageObject> ordered = new ArrayList<>(cached);
                ordered.sort((a, b) -> Integer.compare(b.getId(), a.getId()));
                for (MessageObject mo : ordered) {
                    if (hits.size() >= limit) break;
                    String text = mo.messageOwner.message == null ? "" : mo.messageOwner.message;
                    if (text.toLowerCase().contains(query)) {
                        hits.add(TdJsonConverter.convertMessageObject(mo));
                    }
                }
            }
            HashMap<String, Object> response = new HashMap<>();
            response.put("messages", hits);
            response.put("server_search", false);
            return response;
        }
    }

    public static class MarkReadHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null || !params.has("peer")) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "peer required");
            }
            long dialogId = requireDialogId(params.getString("peer"));
            int maxId = params.optInt("max_id", 0);
            MessagesController.getInstance(UserConfig.selectedAccount)
                    .markDialogAsRead(dialogId, maxId, maxId, 0, false, 0, 0, true, 0);
            HashMap<String, Object> response = new HashMap<>();
            response.put("ok", true);
            return response;
        }
    }

    /**
     * 发送"正在输入"状态。
     * action: 0=typing(默认) 1=cancel 2=record_voice 3=upload_photo …
     * 与 Telegram SendMessagesHelper.SendMessageParams / MessagesController.sendTyping 对齐。
     */
    public static class SetTypingHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null || !params.has("peer")) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "peer required");
            }
            long dialogId = requireDialogId(params.getString("peer"));
            int action = params.optInt("action", 0);
            MessagesController.getInstance(UserConfig.selectedAccount)
                    .sendTyping(dialogId, 0, action, 0);
            HashMap<String, Object> response = new HashMap<>();
            response.put("ok", true);
            return response;
        }
    }

    /* ============================ 内部工具 ============================ */

    /**
     * 把本地缓存按「倒序取最新 limit 条」返回。
     * 返回顺序：旧 → 新，方便手环端直接 append。
     */
    @NonNull
    private static List<Object> collectFromCache(@NonNull MessagesController messages,
                                                 long dialogId, long offsetId, int limit) {
        List<Object> result = new ArrayList<>();
        ArrayList<MessageObject> cached = messages.dialogMessage.get(dialogId);
        if (cached == null) return result;
        ArrayList<MessageObject> ordered = new ArrayList<>(cached);
        ordered.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        for (int i = ordered.size() - 1; i >= 0 && result.size() < limit; i--) {
            MessageObject mo = ordered.get(i);
            if (offsetId > 0 && mo.getId() >= offsetId) continue;
            result.add(0, TdJsonConverter.convertMessageObject(mo));
        }
        return result;
    }

    /** 异步拉取服务器历史以刷新本地缓存（读操作，失败不影响本次响应）。 */
    private static void refreshHistoryFromServer(int account, @NonNull MessagesController messages,
                                                 long dialogId, @NonNull String peer, int limit, long offsetId,
                                                 @NonNull BridgeRouter router) {
        try {
            TLRPC.InputPeer inputPeer = messages.getInputPeer(dialogId);
            if (inputPeer == null) return;
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = inputPeer;
            req.limit = limit;
            req.offset_id = (int) offsetId;
            req.offset_date = 0;
            req.add_offset = 0;
            req.max_id = 0;
            req.min_id = 0;
            org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (error != null) {
                    Log.w(TAG, "history request failed: " + error.text);
                    HashMap<String, Object> event = new HashMap<>();
                    event.put("peer", peer);
                    event.put("offset_id", offsetId);
                    event.put("message", error.text == null ? "history request failed" : error.text);
                    router.pushEvent(WearConstants.Event.HISTORY_ERROR, event);
                    return;
                }
                if (!(response instanceof TLRPC.messages_Messages)) {
                    HashMap<String, Object> event = new HashMap<>();
                    event.put("peer", peer);
                    event.put("offset_id", offsetId);
                    event.put("message", "unexpected Telegram history response");
                    router.pushEvent(WearConstants.Event.HISTORY_ERROR, event);
                    return;
                }
                TLRPC.messages_Messages page = (TLRPC.messages_Messages) response;
                messages.putUsers(page.users, false);
                messages.putChats(page.chats, false);
                ArrayList<TLRPC.Message> ordered = new ArrayList<>(page.messages);
                ordered.sort((a, b) -> Integer.compare(a.date, b.date));
                ArrayList<Object> converted = new ArrayList<>();
                for (TLRPC.Message message : ordered) converted.add(TdJsonConverter.convertMessage(message, dialogId));
                HashMap<String, Object> event = new HashMap<>();
                event.put("peer", peer);
                event.put("offset_id", offsetId);
                event.put("messages", converted);
                event.put("has_more", page.messages.size() >= limit);
                event.put("next_offset_id", page.messages.isEmpty() ? offsetId : page.messages.stream().mapToInt(m -> m.id).min().orElse(0));
                router.pushEvent(WearConstants.Event.HISTORY_PAGE, event);
            });
        } catch (Throwable error) {
            Log.w(TAG, "history refresh dispatch failed", error);
        }
    }

    @Nullable
    private static MessageObject findReplyMessage(@NonNull MessagesController messages,
                                                  long dialogId, long replyTo) {
        if (replyTo == 0) return null;
        return TdJsonConverter.findCachedMessage(dialogId, (int) replyTo);
    }

    /** 本地乐观回显：手环端先看到"发送中"，服务端回包后会推送 update。 */
    @NonNull
    private static HashMap<String, Object> optimisticMessage(@NonNull String peer,
                                                             @NonNull String text, long replyTo,
                                                             int localId, @NonNull String operationId) {
        HashMap<String, Object> message = new HashMap<>();
        long now = System.currentTimeMillis();
        message.put("id", localId < 0 ? localId : -now);
        message.put("out", true);
        message.put("text", text);
        message.put("date", now / 1000);
        message.put("peer", peer);
        message.put("sending", true);
        if (!operationId.isEmpty()) message.put("operation_id", operationId);
        if (replyTo != 0) {
            HashMap<String, Object> reply = new HashMap<>();
            reply.put("id", replyTo);
            message.put("reply_to", reply);
        }
        return message;
    }

    @Nullable public static String takeOperationIdForLocalMessage(int localId, @NonNull String peer, @NonNull String text) {
        String operationId = pendingOperationIds.remove(localId);
        if (operationId != null) return operationId;
        ConcurrentLinkedQueue<String> queue = pendingOperationsByText.get(peer + "\n" + text);
        if (queue == null) return null;
        operationId = queue.poll();
        if (queue.isEmpty()) pendingOperationsByText.remove(peer + "\n" + text, queue);
        return operationId;
    }

    private static void enqueueOperation(@NonNull String peer, @NonNull String text, @NonNull String operationId) {
        String key = peer + "\n" + text;
        ConcurrentLinkedQueue<String> queue = pendingOperationsByText.computeIfAbsent(key, unused -> new ConcurrentLinkedQueue<>());
        queue.offer(operationId);
        while (queue.size() > 8) queue.poll();
        while (pendingOperationsByText.size() > 128) {
            String first = pendingOperationsByText.keySet().iterator().next();
            pendingOperationsByText.remove(first);
        }
    }

    private static void removeQueuedOperation(String peer, String text, String operationId) {
        String key = peer + "\n" + text;
        ConcurrentLinkedQueue<String> queue = pendingOperationsByText.get(key);
        if (queue != null) {
            queue.remove(operationId);
            if (queue.isEmpty()) pendingOperationsByText.remove(key, queue);
        }
    }

    private static int findRecentLocalOutgoing(@NonNull MessagesController messages, long dialogId, @NonNull String text) {
        try {
            ArrayList<MessageObject> cached = messages.dialogMessage.get(dialogId);
            if (cached == null) return 0;
            for (int i = cached.size() - 1; i >= 0; i--) {
                MessageObject message = cached.get(i);
                if (message != null && message.getId() < 0 && message.isOut()
                        && text.equals(message.messageOwner.message)
                        && !pendingOperationIds.containsKey(message.getId())) return message.getId();
            }
        } catch (Throwable error) { Log.w(TAG, "unable to correlate local outgoing message"); }
        return 0;
    }

    /**
     * peer 字符串 → dialog_id，并对「频道/超级群」做基本合法性校验。
     * 不合法时抛 RpcException，避免把 0 传给 Telegram。
     */
    private static long requireDialogId(@NonNull String peer) throws BridgeRouter.RpcException {
        if (!peer.startsWith("user:")) {
            throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "TG Wear currently supports private user chats only");
        }
        long dialogId = TdJsonConverter.peerToDialogId(peer);
        if (dialogId <= 0) throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "bad private peer");
        return dialogId;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }
}
