/*
 * tgwear: TLRPC 对象 ↔ JSON 协议结构转换器
 *
 * 设计原则（性能优先）：
 *   1. 不直接序列化整个 TLRPC 对象，只挑 UI 真正需要的字段。
 *   2. 绝不传输二进制、文件路径、文档 ID、SVG 或缩略图字节。
 *      手环是资源受限设备，重处理放在手机端。
 *   3. 贴纸只保留 Telegram sticker attribute 的 alt（emoji）。
 *
 * 协议字段与手表端 src/utils/format.js、src/pages/chat/chat.ux 对齐。
 */
package org.telegram.tgwear;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class TdJsonConverter {

    private static final String TAG = "tgwear/Converter";

    /** 消息正文在手表端的最大字符数，避免超长消息撑爆 wearable 通道。 */
    private static final int MAX_TEXT_CHARS = 2000;

    private TdJsonConverter() {}

    /**
     * 把任意 Java 对象转成可放入 JSONObject 的值。
     */
    @Nullable
    public static Object toJSON(@Nullable Object o) {
        if (o == null) return null;
        if (o instanceof JSONObject || o instanceof JSONArray) return o;
        if (o instanceof String || o instanceof Boolean || o instanceof Number) return o;
        if (o instanceof Map) {
            JSONObject jo = new JSONObject();
            for (Object e : ((Map<?,?>) o).entrySet()) {
                Map.Entry<?,?> entry = (Map.Entry<?,?>) e;
                try {
                    jo.put(String.valueOf(entry.getKey()), toJSON(entry.getValue()));
                } catch (Throwable ignore) {}
            }
            return jo;
        }
        if (o instanceof Collection) {
            JSONArray arr = new JSONArray();
            for (Object item : (Collection<?>) o) {
                arr.put(toJSON(item));
            }
            return arr;
        }
        if (o.getClass().isArray()) {
            JSONArray arr = new JSONArray();
            int len = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < len; i++) {
                arr.put(toJSON(java.lang.reflect.Array.get(o, i)));
            }
            return arr;
        }
        if (o instanceof TLRPC.User)   return convertUser((TLRPC.User) o);
        if (o instanceof TLRPC.Chat)   return convertChat((TLRPC.Chat) o);
        if (o instanceof TLRPC.Dialog) return convertDialog((TLRPC.Dialog) o);
        if (o instanceof TLRPC.Message)return convertMessage((TLRPC.Message) o, 0);
        if (o instanceof MessageObject)return convertMessageObject((MessageObject) o);
        return o.toString();
    }

    /* === User === */
    @NonNull
    public static JSONObject convertUser(@NonNull TLRPC.User user) {
        JSONObject j = new JSONObject();
        try {
            j.put("id", user.id);
            j.put("first_name", nullToEmpty(user.first_name));
            j.put("last_name", nullToEmpty(user.last_name));
            j.put("username", nullToEmpty(user.username));
            j.put("phone", nullToEmpty(user.phone));
            j.put("self", user.self);
            j.put("bot", user.bot);
        } catch (Throwable t) {
            Log.w(TAG, "convertUser failed", t);
        }
        return j;
    }

    /* === Chat === */
    @NonNull
    public static JSONObject convertChat(@NonNull TLRPC.Chat chat) {
        JSONObject j = new JSONObject();
        try {
            j.put("id", chat.id);
            j.put("title", nullToEmpty(chat.title));
            j.put("username", nullToEmpty(chat.username));
            j.put("participants_count", chat.participants_count);
            j.put("is_channel", chat.megagroup || chat instanceof TLRPC.TL_channel);
            j.put("is_megagroup", chat.megagroup);
        } catch (Throwable t) {
            Log.w(TAG, "convertChat failed", t);
        }
        return j;
    }

    /* === Dialog === */
    @NonNull
    public static JSONObject convertDialog(@NonNull TLRPC.Dialog dialog) {
        return convertDialog(dialog, null);
    }

    @NonNull
    public static JSONObject convertDialog(@NonNull TLRPC.Dialog dialog, @Nullable TLRPC.Message lastMessage) {
        JSONObject j = new JSONObject();
        try {
            j.put("peer", peerToString(dialog.id, null));
            j.put("title", getDialogTitle(dialog));
            j.put("unread_count", dialog.unread_count);
            j.put("unread_mentions_count", dialog.unread_mentions_count);
            j.put("last_message_date", dialog.last_message_date);
            MessageObject last = findCachedMessage(dialog.id, dialog.top_message);
            j.put("last_message_id", dialog.top_message);
            JSONObject preview = lastMessage != null ? convertMessage(lastMessage, dialog.id)
                    : (last != null ? convertMessageObject(last) : new JSONObject());
            preview.put("text", truncate(preview.optString("text", ""), 160));
            j.put("last_message", preview);
            j.put("pinned", dialog.pinned);
            j.put("folder_id", dialog.folder_id);
            j.put("muted", isDialogMuted(dialog.id));
            j.put("is_channel", isChannelDialog(dialog.id));
        } catch (Throwable t) {
            Log.w(TAG, "convertDialog failed", t);
        }
        return j;
    }

    /* === Message === */
    @NonNull
    public static JSONObject convertMessage(@Nullable TLRPC.Message msg, long dialogId) {
        JSONObject j = new JSONObject();
        if (msg == null) return j;
        try {
            j.put("id", msg.id);
            j.put("date", msg.date);
            j.put("out", msg.out);
            j.put("from_id", msg.from_id != null ? msg.from_id.user_id : 0);
            long peerId = dialogId != 0 ? dialogId : msg.dialog_id;
            j.put("peer", peerToString(peerId, null));
            j.put("text", truncate(nullToEmpty(msg.message)));

            // 发送者显示名（群聊里手环需要区分是谁说的）
            String senderName = resolveSenderName(msg);
            if (!senderName.isEmpty()) j.put("sender", senderName);

            // 媒体类型：只输出「类型 + 极轻量元数据」，绝不输出二进制
            JSONObject media = describeMedia(msg);
            if (media != null) j.put("media", media);

            // 兼容旧字段：贴纸单独保留 emoji，便于旧手表端显示
            if (media != null && "sticker".equals(media.optString("type"))) {
                JSONObject sticker = new JSONObject();
                sticker.put("emoji", media.optString("emoji", ""));
                j.put("sticker", sticker);
            }

            if (msg.reply_to != null && msg.reply_to.reply_to_msg_id != 0) {
                JSONObject reply = new JSONObject();
                reply.put("id", msg.reply_to.reply_to_msg_id);
                MessageObject quoted = findCachedMessage(peerId, msg.reply_to.reply_to_msg_id);
                if (quoted != null) {
                    reply.put("text", truncate(nullToEmpty(quoted.messageOwner.message)));
                    if (quoted.isSticker()) reply.put("text", stickerEmoji(quoted.messageOwner));
                }
                j.put("reply_to", reply);
            }

            // 出站消息的发送状态：手表端可据此显示"发送中"
            // 语义（与 MessageObject 内部判断一致）：
            //   id < 0 且 send_state == SENDING     → 正在发送
            //   id < 0 且 send_state == SEND_ERROR  → 发送失败
            //   否则视为已发送
            final int sendState = msg.send_state;
            j.put("sending", msg.id < 0
                    && (sendState == MessageObject.MESSAGE_SEND_STATE_SENDING
                        || sendState == MessageObject.MESSAGE_SEND_STATE_SEND_ERROR));
            j.put("failed", msg.id < 0 && sendState == MessageObject.MESSAGE_SEND_STATE_SEND_ERROR);
        } catch (Throwable t) {
            Log.w(TAG, "convertMessage failed", t);
        }
        return j;
    }

    /* === MessageObject === */
    @NonNull
    public static JSONObject convertMessageObject(@NonNull MessageObject mo) {
        return convertMessage(mo.messageOwner, mo.getDialogId());
    }

    /**
     * 媒体描述。原则：只给类型标签与短字符串，体量恒定。
     */
    @Nullable
    public static JSONObject describeMedia(@NonNull TLRPC.Message msg) {
        try {
            TLRPC.MessageMedia m = msg.media;
            if (m == null) return null;
            JSONObject out = new JSONObject();
            if (m.document != null) {
                TLRPC.Document doc = m.document;
                String emoji = stickerEmoji(msg);
                boolean isSticker = isStickerDocument(doc);
                if (isSticker) {
                    out.put("type", "sticker");
                    out.put("emoji", emoji);
                    return out;
                }
                if (MessageObject.isVoiceDocument(doc)) {
                    out.put("type", "voice");
                    out.put("duration", documentDuration(doc));
                    return out;
                }
                if (MessageObject.isRoundVideoDocument(doc)) {
                    out.put("type", "video_note");
                    out.put("duration", documentDuration(doc));
                    return out;
                }
                if (MessageObject.isVideoDocument(doc)) {
                    out.put("type", "video");
                    out.put("duration", documentDuration(doc));
                    return out;
                }
                if (MessageObject.isGifDocument(doc)) {
                    out.put("type", "gif");
                    return out;
                }
                if (MessageObject.isMusicDocument(doc)) {
                    out.put("type", "audio");
                    out.put("duration", documentDuration(doc));
                    out.put("title", documentTitle(doc));
                    return out;
                }
                out.put("type", "document");
                out.put("name", documentTitle(doc));
                out.put("size", doc.size);
                return out;
            }
            if (m.photo != null) {
                out.put("type", "photo");
                return out;
            }
            if (m.geo != null) {
                out.put("type", "location");
                return out;
            }
            if (m.webpage != null) {
                out.put("type", "webpage");
                out.put("title", truncate(nullToEmpty(m.webpage.title)));
                out.put("site", truncate(nullToEmpty(m.webpage.site_name)));
                return out;
            }
            // 其他媒体类型统一给通用标签，不额外传字段
            out.put("type", "media");
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "describeMedia failed", t);
            return null;
        }
    }

    /* === Helpers === */

    /** peer 字符串格式（与手表端一致）：用户 "user:<id>"，群/频道 "chat:<id>"。 */
    @NonNull
    public static String peerToString(long dialogId, @Nullable TLRPC.Dialog dialog) {
        if (dialogId > 0) return "user:" + dialogId;
        return "chat:" + dialogId;
    }

    /** 反解 peer 字符串为 Telegram 内部 dialog_id */
    public static long peerToDialogId(@NonNull String peer) {
        int idx = peer.indexOf(':');
        if (idx < 0) {
            try { return Long.parseLong(peer); } catch (NumberFormatException e) { return 0; }
        }
        try {
            return Long.parseLong(peer.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 在本地对话缓存里按消息 id 找一条消息。 */
    @Nullable
    public static MessageObject findCachedMessage(long dialogId, int messageId) {
        try {
            java.util.ArrayList<MessageObject> cached =
                    MessagesController.getInstance(UserConfig.selectedAccount).dialogMessage.get(dialogId);
            if (cached == null) return null;
            for (MessageObject message : cached) {
                if (message.getId() == messageId) return message;
            }
        } catch (Throwable t) {
            Log.w(TAG, "findCachedMessage failed", t);
        }
        return null;
    }

    /** 取贴纸 emoji（Telegram sticker attribute 的 alt 字段）。 */
    @NonNull
    public static String stickerEmoji(@Nullable TLRPC.Message message) {
        if (message == null || message.media == null) return "";
        TLRPC.Document doc = message.media.document;
        if (doc == null || doc.attributes == null) return "";
        for (TLRPC.DocumentAttribute attr : doc.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeSticker) {
                return nullToEmpty(((TLRPC.TL_documentAttributeSticker) attr).alt);
            }
        }
        return "";
    }

    /** 消息附件是否含贴纸属性。 */
    public static boolean isStickerDocument(@Nullable TLRPC.Document doc) {
        if (doc == null || doc.attributes == null) return false;
        for (TLRPC.DocumentAttribute attr : doc.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeSticker) return true;
        }
        return false;
    }

    private static int documentDuration(@Nullable TLRPC.Document doc) {
        if (doc == null || doc.attributes == null) return 0;
        for (TLRPC.DocumentAttribute attr : doc.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeAudio) {
                // 注意：本 Nagram 构建里音频时长同样是 double，必须显式取整
                return (int) Math.round(((TLRPC.TL_documentAttributeAudio) attr).duration);
            }
            if (attr instanceof TLRPC.TL_documentAttributeVideo) {
                return (int) Math.round(((TLRPC.TL_documentAttributeVideo) attr).duration);
            }
        }
        return 0;
    }

    @NonNull
    private static String documentTitle(@Nullable TLRPC.Document doc) {
        if (doc == null || doc.attributes == null) return "";
        for (TLRPC.DocumentAttribute attr : doc.attributes) {
            if (attr instanceof TLRPC.TL_documentAttributeFilename) {
                return truncate(nullToEmpty(((TLRPC.TL_documentAttributeFilename) attr).file_name), 64);
            }
        }
        return "";
    }

    @NonNull
    private static String resolveSenderName(@NonNull TLRPC.Message msg) {
        try {
            if (msg.out) return "";
            int account = UserConfig.selectedAccount;
            MessagesController controller = MessagesController.getInstance(account);
            if (msg.from_id != null && msg.from_id.user_id != 0) {
                TLRPC.User u = controller.getUser(msg.from_id.user_id);
                if (u != null) {
                    String name = nullToEmpty(u.first_name);
                    if (u.last_name != null && !u.last_name.isEmpty()) name += " " + u.last_name;
                    return truncate(name, 32);
                }
            }
            if (msg.peer_id != null && msg.peer_id.channel_id != 0) {
                TLRPC.Chat c = controller.getChat(msg.peer_id.channel_id);
                if (c != null && c.title != null) return truncate(c.title, 32);
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveSenderName failed", t);
        }
        return "";
    }

    @NonNull
    private static String getDialogTitle(@NonNull TLRPC.Dialog dialog) {
        int currentAccount = UserConfig.selectedAccount;
        long id = dialog.id;
        try {
            if (id > 0) {
                TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(id);
                if (u != null) {
                    String name = nullToEmpty(u.first_name);
                    if (u.last_name != null && !u.last_name.isEmpty()) name += " " + u.last_name;
                    return name.isEmpty() ? (u.username != null ? "@" + u.username : "未知") : name;
                }
            } else {
                TLRPC.Chat c = MessagesController.getInstance(currentAccount).getChat(-id);
                if (c != null && c.title != null) return c.title;
            }
        } catch (Throwable t) {
            Log.w(TAG, "getDialogTitle failed", t);
        }
        return "未知";
    }

    private static boolean isDialogMuted(long dialogId) {
        try {
            return MessagesController.getInstance(UserConfig.selectedAccount)
                    .isDialogMuted(dialogId);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isChannelDialog(long dialogId) {
        try {
            if (dialogId >= 0) return false;
            TLRPC.Chat chat = MessagesController.getInstance(UserConfig.selectedAccount).getChat(-dialogId);
            return chat != null && (chat.megagroup || chat instanceof TLRPC.TL_channel);
        } catch (Throwable t) {
            return false;
        }
    }

    @NonNull
    public static String truncate(@Nullable String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    @NonNull
    private static String truncate(@Nullable String s) {
        return truncate(s, MAX_TEXT_CHARS);
    }

    @NonNull
    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }
}
