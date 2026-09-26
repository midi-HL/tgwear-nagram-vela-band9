/*
 * tgwear: dialogs.* RPC 处理器
 *
 * 读取走本地缓存（MessagesController.dialogsByFolder / getAllDialogs），
 * 写操作走 Nagram 当前的 MessagesController API。
 */
package org.telegram.tgwear.handlers;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgwear.BridgeRouter;
import org.telegram.tgwear.TdJsonConverter;
import org.telegram.tgwear.WearConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class DialogsHandler {

    private static final String TAG = "tgwear/Dialogs";

    /** 默认拉取主文件夹（folderId = 0）的会话。 */
    public static class GetDialogsHandler implements BridgeRouter.Handler {
        private final BridgeRouter router;
        public GetDialogsHandler(@NonNull BridgeRouter router) { this.router = router; }

        @NonNull
        @Override
        public Object handle(@Nullable JSONObject params) throws Exception {
            int limit = clamp(params != null ? params.optInt("limit", 30) : 30, 1, 30);
            JSONObject cursor = params == null ? null : params.optJSONObject("cursor");
            int offset = cursor == null ? 0 : Math.max(0, cursor.optInt("cache_index", 0));
            int folderId = params != null ? params.optInt("folder_id", 0) : 0;

            int account = UserConfig.selectedAccount;
            MessagesController messages = MessagesController.getInstance(account);

            ArrayList<TLRPC.Dialog> source = messages.getDialogs(folderId);
            if (source == null || source.isEmpty()) {
                // 兼容：主文件夹为空时退回全量列表
                source = new ArrayList<>(messages.getAllDialogs());
            }

            List<TLRPC.Dialog> sorted = new ArrayList<>(source);
            sorted.removeIf(dialog -> dialog == null || !DialogObject.isUserDialog(dialog.id));
            java.util.Collections.sort(sorted, (a, b) -> {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                return Long.compare(b.last_message_date, a.last_message_date);
            });

            int start = Math.min(offset, sorted.size());
            int end = Math.min(start + limit, sorted.size());

            List<Object> dialogs = new ArrayList<>();
            for (int i = start; i < end; i++) {
                try {
                    dialogs.add(TdJsonConverter.convertDialog(sorted.get(i)));
                } catch (Throwable t) {
                    Log.w(TAG, "convert dialog failed", t);
                }
            }

            HashMap<String, Object> result = new HashMap<>();
            result.put("dialogs", dialogs);
            result.put("has_more", false); // Network cursor arrives only with update.dialogsPage.
            result.put("next_cursor", null);
            result.put("refreshing", true);
            refreshDialogsFromServer(account, messages, limit, folderId, cursor, router);
            return result;
        }
    }

    /** 置顶 / 取消置顶。 */
    public static class PinHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            long dialogId = requireDialogId(params);
            boolean pin = params.optBoolean("pin", true);
            TLRPC.InputPeer peer = MessagesController.getInstance(UserConfig.selectedAccount)
                    .getInputPeer(dialogId);
            MessagesController.getInstance(UserConfig.selectedAccount)
                    .pinDialog(dialogId, pin, peer, -1L);
            HashMap<String, Object> r = new HashMap<>();
            r.put("ok", true);
            r.put("pinned", pin);
            return r;
        }
    }

    /**
     * 静音 / 取消静音。
     *
     * seconds 语义与 Telegram 一致：0 = 取消静音；>0 = 静音到该秒数；
     * 传 2147483647 表示"永久静音"（与系统内置的 setDialogMuted 用法相同）。
     */
    public static class MuteHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            long dialogId = requireDialogId(params);
            int seconds = params.optInt("seconds", 0);
            org.telegram.messenger.NotificationsController.getInstance(UserConfig.selectedAccount)
                    .muteUntil(dialogId, 0, seconds);
            HashMap<String, Object> r = new HashMap<>();
            r.put("ok", true);
            r.put("muted", seconds > 0);
            return r;
        }
    }

    /**
     * 归档 / 取消归档。
     * folderId 1 即 Telegram 的 Archive 文件夹（Nagram 沿用该约定）。
     */
    public static class ArchiveHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            long dialogId = requireDialogId(params);
            boolean archive = params.optBoolean("archive", true);
            int folderId = archive ? 1 : 0;
            MessagesController.getInstance(UserConfig.selectedAccount)
                    .addDialogToFolder(dialogId, folderId, -1, -1L);
            HashMap<String, Object> r = new HashMap<>();
            r.put("ok", true);
            r.put("archived", archive);
            return r;
        }
    }

    /**
     * 删除会话。
     * onlyHistory=true 只清空聊天记录并保留会话；
     * false 表示连同会话一起删除。
     */
    public static class DeleteHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            long dialogId = requireDialogId(params);
            boolean onlyHistory = params != null && params.optBoolean("only_history", false);
            boolean revoke = params == null || params.optBoolean("revoke", true);
            MessagesController.getInstance(UserConfig.selectedAccount)
                    .deleteDialog(dialogId, onlyHistory ? 1 : 0, revoke);
            HashMap<String, Object> r = new HashMap<>();
            r.put("ok", true);
            return r;
        }
    }

    private static void refreshDialogsFromServer(int account, @NonNull MessagesController messages,
                                                 int limit, int folderId, @Nullable JSONObject cursor,
                                                 @NonNull BridgeRouter router) {
        try {
            TLRPC.TL_messages_getDialogs request = new TLRPC.TL_messages_getDialogs();
            request.limit = limit;
            request.exclude_pinned = true;
            if (folderId != 0) { request.flags |= 2; request.folder_id = folderId; }
            if (cursor == null || cursor.optString("peer", "").isEmpty()) {
                request.offset_peer = new TLRPC.TL_inputPeerEmpty();
            } else {
                long dialogId = TdJsonConverter.peerToDialogId(cursor.optString("peer", ""));
                TLRPC.InputPeer peer = messages.getInputPeer(dialogId);
                if (peer == null || dialogId == 0) {
                    pushDialogError(router, "invalid dialog pagination cursor");
                    return;
                }
                request.offset_peer = peer;
                request.offset_id = cursor.optInt("offset_id", 0);
                request.offset_date = cursor.optInt("offset_date", 0);
            }
            org.telegram.tgnet.ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
                if (error != null) {
                    Log.w(TAG, "dialogs request failed: " + error.text);
                    pushDialogError(router, error.text == null ? "dialogs request failed" : error.text);
                    return;
                }
                if (!(response instanceof TLRPC.messages_Dialogs)) {
                    pushDialogError(router, "unexpected Telegram dialogs response");
                    return;
                }
                TLRPC.messages_Dialogs page = (TLRPC.messages_Dialogs) response;
                messages.putUsers(page.users, false);
                messages.putChats(page.chats, false);
                HashMap<Long, TLRPC.Message> latest = new HashMap<>();
                for (TLRPC.Message message : page.messages) {
                    long dialogId = message.dialog_id != 0 ? message.dialog_id : MessageObject.getPeerId(message.peer_id);
                    TLRPC.Message previous = latest.get(dialogId);
                    if (previous == null || message.date > previous.date) latest.put(dialogId, message);
                }
                ArrayList<TLRPC.Dialog> eligible = new ArrayList<>();
                for (TLRPC.Dialog dialog : page.dialogs) {
                    if (dialog != null && DialogObject.isUserDialog(dialog.id)) eligible.add(dialog);
                }
                eligible.sort((a, b) -> Long.compare(b.last_message_date, a.last_message_date));
                ArrayList<Object> output = new ArrayList<>();
                for (TLRPC.Dialog dialog : eligible) output.add(TdJsonConverter.convertDialog(dialog, latest.get(dialog.id)));
                HashMap<String, Object> event = new HashMap<>();
                event.put("dialogs", output);
                event.put("has_more", page.dialogs.size() >= limit);
                if (!page.dialogs.isEmpty()) {
                    TLRPC.Dialog last = page.dialogs.get(page.dialogs.size() - 1);
                    HashMap<String, Object> next = new HashMap<>();
                    next.put("peer", TdJsonConverter.peerToString(last.id, last));
                    next.put("offset_id", last.top_message);
                    next.put("offset_date", last.last_message_date);
                    event.put("next_cursor", next);
                } else {
                    event.put("next_cursor", null);
                }
                router.pushEvent(WearConstants.Event.DIALOGS_PAGE, event);
            });
        } catch (Throwable error) {
            Log.w(TAG, "dialogs request dispatch failed", error);
            pushDialogError(router, error.getMessage() == null ? "dialogs request dispatch failed" : error.getMessage());
        }
    }

    private static void pushDialogError(@NonNull BridgeRouter router, @NonNull String message) {
        HashMap<String, Object> event = new HashMap<>();
        event.put("message", message.length() > 120 ? message.substring(0, 120) : message);
        router.pushEvent(WearConstants.Event.DIALOGS_ERROR, event);
    }

    private static long requireDialogId(@Nullable JSONObject params) throws BridgeRouter.RpcException {
        if (params == null || !params.has("peer")) {
            throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "peer required");
        }
        String peer = params.optString("peer", "");
        long dialogId = TdJsonConverter.peerToDialogId(peer);
        if (!peer.startsWith("user:") || dialogId <= 0) {
            throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "private user peer required");
        }
        return dialogId;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
