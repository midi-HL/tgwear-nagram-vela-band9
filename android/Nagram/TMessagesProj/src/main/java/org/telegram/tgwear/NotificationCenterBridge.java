/*
 * tgwear: Telegram NotificationCenter → Vela 事件协议
 *
 * 负责把 Nagram 内部事件转成手环端可消费的轻量事件：
 *   update.newMessage     新消息 / 消息被编辑（Nagram 两者都走 didReceiveNewMessages，
 *                         手环端必须按 message.id 做 upsert，不能盲目 append）
 *   update.messageSent    出站消息拿到服务端 id（local_id -> server_id）
 *   update.messageUpdated 消息被删除，手环端应移除
 *   update.connectionState 通道就绪状态（由 Service 推送）
 *
 * 事件量控制：只推手环当前会展示的内容，不推交互细节（打字状态、在线状态等）。
 */
package org.telegram.tgwear;

import android.util.Log;

import androidx.annotation.NonNull;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgwear.handlers.MessagesHandler;

import java.util.HashMap;
import java.util.List;

/** Converts current Nagram NotificationCenter events into the Vela event protocol. */
public class NotificationCenterBridge implements NotificationCenter.NotificationCenterDelegate {
    private static final String TAG = "TGWear/Notify";
    private final BridgeRouter router;
    private final int currentAccount;
    private final NotificationCenter notificationCenter;

    public NotificationCenterBridge(@NonNull BridgeRouter router) {
        this.router = router;
        currentAccount = UserConfig.selectedAccount;
        notificationCenter = NotificationCenter.getInstance(currentAccount);
    }

    public void start() {
        notificationCenter.addObserver(this, NotificationCenter.didReceiveNewMessages);
        notificationCenter.addObserver(this, NotificationCenter.dialogsNeedReload);
        notificationCenter.addObserver(this, NotificationCenter.updateInterfaces);
        notificationCenter.addObserver(this, NotificationCenter.messageReceivedByServer);
        notificationCenter.addObserver(this, NotificationCenter.messagesDeleted);
    }

    public void stop() {
        try {
            notificationCenter.removeObserver(this, NotificationCenter.didReceiveNewMessages);
            notificationCenter.removeObserver(this, NotificationCenter.dialogsNeedReload);
            notificationCenter.removeObserver(this, NotificationCenter.updateInterfaces);
            notificationCenter.removeObserver(this, NotificationCenter.messageReceivedByServer);
            notificationCenter.removeObserver(this, NotificationCenter.messagesDeleted);
        } catch (Throwable error) {
            Log.w(TAG, "stop failed", error);
        }
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        try {
            if (id == NotificationCenter.didReceiveNewMessages) {
                handleNewMessages(args);
            } else if (id == NotificationCenter.messageReceivedByServer) {
                handleMessageSent(args);
            } else if (id == NotificationCenter.messagesDeleted) {
                handleMessagesDeleted(args);
            } else if (id == NotificationCenter.dialogsNeedReload) {
                pushDialogsRefresh();
            }
        } catch (Throwable error) {
            Log.w(TAG, "notification handling failed", error);
        }
    }

    /**
     * 新消息。
     * 当前 Nagram 签名：didReceiveNewMessages(dialogId, ArrayList<MessageObject>, scheduled, mode)
     */
    private void handleNewMessages(Object[] args) {
        if (args == null || args.length < 2 || !(args[0] instanceof Number) || !(args[1] instanceof List)) return;
        long dialogId = ((Number) args[0]).longValue();
        if (!DialogObject.isUserDialog(dialogId)) return;
        List<?> messages = (List<?>) args[1];
        for (Object item : messages) {
            if (!(item instanceof MessageObject)) continue;
            MessageObject messageObject = (MessageObject) item;
            HashMap<String, Object> data = new HashMap<>();
            data.put("peer", TdJsonConverter.peerToString(dialogId, null));
            data.put("message", TdJsonConverter.convertMessageObject(messageObject));
            try {
                TLRPC.Dialog dialog = MessagesController.getInstance(currentAccount).getDialog(dialogId);
                if (dialog != null) data.put("dialog", TdJsonConverter.convertDialog(dialog));
            } catch (Throwable ignored) { }
            router.pushEvent(WearConstants.Event.NEW_MESSAGE, data);
        }
    }

    /**
     * 出站消息确认。
     * 签名：messageReceivedByServer(oldId, newId, message, peer, grouped_id, existFlags, scheduled)
     * 手环端以此把乐观插入的负数 id 替换成真实服务端 id。
     */
    private void handleMessageSent(Object[] args) {
        if (args == null || args.length < 4) return;
        if (!(args[0] instanceof Number) || !(args[1] instanceof Number)) return;
        int oldId = ((Number) args[0]).intValue();
        int newId = ((Number) args[1]).intValue();
        long peer;
        if (args[3] instanceof Number) {
            peer = ((Number) args[3]).longValue();
        } else {
            return;
        }
        if (!DialogObject.isUserDialog(peer)) return;
        HashMap<String, Object> data = new HashMap<>();
        data.put("peer", TdJsonConverter.peerToString(peer, null));
        data.put("local_id", oldId);
        data.put("message_id", newId);
        String peerString = TdJsonConverter.peerToString(peer, null);
        String messageText = "";
        if (args[2] instanceof TLRPC.Message) messageText = ((TLRPC.Message) args[2]).message;
        else if (args[2] instanceof MessageObject) messageText = ((MessageObject) args[2]).messageOwner.message;
        String operationId = MessagesHandler.takeOperationIdForLocalMessage(oldId, peerString, messageText == null ? "" : messageText);
        if (operationId != null) data.put("operation_id", operationId);
        // 附带最新消息内容，手环端可直接替换气泡内容
        try {
            if (args[2] instanceof TLRPC.Message) {
                data.put("message", TdJsonConverter.convertMessage((TLRPC.Message) args[2], peer));
            } else if (args[2] instanceof MessageObject) {
                data.put("message", TdJsonConverter.convertMessageObject((MessageObject) args[2]));
            }
        } catch (Throwable ignored) { }
        router.pushEvent(WearConstants.Event.MESSAGE_SENT, data);
    }

    /** 删除消息。签名：messagesDeleted(ArrayList<Integer> messages, ArrayList<Long> channelIds, boolean manual) */
    private void handleMessagesDeleted(Object[] args) {
        if (args == null || args.length < 2 || !(args[0] instanceof List)) return;
        List<?> ids = (List<?>) args[0];
        if (ids.isEmpty()) return;
        HashMap<String, Object> data = new HashMap<>();
        java.util.ArrayList<Integer> pending = new java.util.ArrayList<>();
        for (Object o : ids) {
            if (o instanceof Number) pending.add(((Number) o).intValue());
        }
        if (pending.isEmpty()) return;
        data.put("message_ids", pending);
        // NotificationCenter only includes a channelId here, not a user dialog ID.
        // Ask the currently open private chat to reload instead of deleting ambiguous IDs globally.
        router.pushEvent(WearConstants.Event.MESSAGE_CACHE_INVALIDATED, data);
    }

    private long lastDialogsPush;
    private static final long DIALOGS_THROTTLE_MS = 1000;
    private void pushDialogsRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastDialogsPush < DIALOGS_THROTTLE_MS) return;
        lastDialogsPush = now;
        router.pushEvent(WearConstants.Event.DIALOGS_INVALIDATED, new HashMap<String, Object>());
    }
}
