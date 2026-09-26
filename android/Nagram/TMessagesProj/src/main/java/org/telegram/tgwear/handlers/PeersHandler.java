/*
 * tgwear: peers.* RPC 处理器
 *
 * 手环端只需要展示用的极轻量资料字段，不传输头像、不传输完整 UserFull/ChatFull。
 */
package org.telegram.tgwear.handlers;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgwear.BridgeRouter;
import org.telegram.tgwear.TdJsonConverter;
import org.telegram.tgwear.WearConstants;

import java.util.HashMap;

public class PeersHandler {
    private static final String TAG = "TGWear/Peers";

    /** 读取单个会话/用户的轻量资料。 */
    public static class GetHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null || !params.has("peer")) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "peer required");
            }
            String peer = params.optString("peer", "");
            long dialogId = TdJsonConverter.peerToDialogId(peer);
            if (!peer.startsWith("user:") || !DialogObject.isUserDialog(dialogId)) {
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID, "private user peer required");
            }
            MessagesController messages = MessagesController.getInstance(UserConfig.selectedAccount);
            HashMap<String, Object> result = new HashMap<>();
            result.put("peer", params.optString("peer", ""));
            try {
                TLRPC.User user = messages.getUser(dialogId);
                if (user != null) result.put("user", TdJsonConverter.convertUser(user));
                TLRPC.Dialog dialog = messages.getDialog(dialogId);
                if (dialog != null) result.put("dialog", TdJsonConverter.convertDialog(dialog));
            } catch (Throwable t) {
                Log.w(TAG, "peer lookup failed", t);
            }
            return result;
        }
    }

    /**
     * 服务消息置顶控制。
     *
     * 说明：这里只做「本对话内置顶」的轻量操作 —— 通过 MessagesController.pinMessage
     * 需要 Chat/User 对象，因此仅在其可取到时执行；取不到时明确报错，
     * 不伪造成功。
     */
    public static class PinMessageHandler implements BridgeRouter.Handler {
        @NonNull @Override public Object handle(@Nullable JSONObject params) throws Exception {
            if (params == null) throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "params required");
            String peer = params.optString("peer", "");
            long dialogId = TdJsonConverter.peerToDialogId(peer);
            int messageId = params.optInt("message_id", 0);
            boolean unpin = params.optBoolean("unpin", false);
            if (!peer.startsWith("user:") || !DialogObject.isUserDialog(dialogId) || messageId == 0) {
                throw new BridgeRouter.RpcException(WearConstants.Code.INVALID_PARAMS, "peer and message_id required");
            }
            MessagesController messages = MessagesController.getInstance(UserConfig.selectedAccount);
            try {
                TLRPC.User user = messages.getUser(dialogId);
                if (user == null) throw new IllegalStateException("user not cached");
                messages.pinMessage(null, user, messageId, unpin, false, true);
            } catch (Throwable t) {
                Log.w(TAG, "pinMessage failed", t);
                throw new BridgeRouter.RpcException(WearConstants.Code.PEER_INVALID,
                        "cannot pin in this dialog: " + t.getMessage());
            }
            HashMap<String, Object> r = new HashMap<>();
            r.put("ok", true);
            r.put("unpinned", unpin);
            return r;
        }
    }
}
