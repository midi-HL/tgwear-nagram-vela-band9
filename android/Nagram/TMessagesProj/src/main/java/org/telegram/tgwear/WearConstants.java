/*
 * tgwear: 与手表端快应用通信的协议常量
 *
 * 与 tgwear-quickapp/src/utils/api.js 中的方法名一一对应。
 * 修改这里必须同步修改手表端（src/utils/api.js）。
 */
package org.telegram.tgwear;

public final class WearConstants {

    private WearConstants() {}

    /** RPC 请求中"是否为请求"的标记字段（手表端 api.js 在 send 时自动加上） */
    public static final String RPC_MARKER = "__rpc";

    /** 事件推送标记字段 */
    public static final String EVENT_MARKER = "__event";

    /** RPC 字段名 */
    public static final String FIELD_ID = "id";
    public static final String FIELD_METHOD = "method";
    public static final String FIELD_PARAMS = "params";
    public static final String FIELD_RESULT = "result";
    public static final String FIELD_ERROR = "error";
    public static final String FIELD_CODE = "code";
    public static final String FIELD_MSG = "msg";
    public static final String FIELD_EVENT = "event";
    public static final String FIELD_DATA = "data";

    /** RPC 方法清单 —— 与手表端 src/utils/api.js 对齐 */
    public static final class Method {
        private Method() {}
        // 基础：状态与登录
        public static final String AUTH_GET_STATE        = "auth.getState";
        public static final String AUTH_LOGOUT           = "auth.logout";
        public static final String BRIDGE_GET_STATUS     = "bridge.getStatus";
        public static final String BRIDGE_PROBE_ACK      = "bridge.probeAck";
        // 会话
        public static final String DIALOGS_GET           = "dialogs.get";
        public static final String DIALOGS_PIN           = "dialogs.pin";
        public static final String DIALOGS_MUTE          = "dialogs.mute";
        public static final String DIALOGS_ARCHIVE       = "dialogs.archive";
        public static final String DIALOGS_DELETE        = "dialogs.delete";
        // 消息读取
        public static final String MESSAGES_GET_HISTORY  = "messages.getHistory";
        public static final String MESSAGES_SEND_TEXT    = "messages.sendText";
        public static final String MESSAGES_REPLY         = "messages.reply";
        public static final String MESSAGES_EDIT          = "messages.edit";
        public static final String MESSAGES_DELETE        = "messages.delete";
        public static final String MESSAGES_FORWARD       = "messages.forward";
        public static final String MESSAGES_MARK_READ     = "messages.markRead";
        public static final String MESSAGES_SET_TYPING    = "messages.setTyping";
        public static final String MESSAGES_SEARCH        = "messages.search";
        // 联系人/资料
        public static final String PEERS_GET              = "peers.get";
        public static final String PEERS_PIN_MESSAGE      = "peers.pinMessage";
    }

    /** 事件名 */
    public static final class Event {
        private Event() {}
        public static final String NEW_MESSAGE            = "update.newMessage";
        public static final String CONNECTION_STATE       = "update.connectionState";
        /** Phone-originated challenge; watch acknowledges it through bridge.probeAck. */
        public static final String PHONE_PROBE            = "update.phoneProbe";
        /** 出站消息 ID 变更（local_id -> server_id），手环端据此修正本地记录 */
        public static final String MESSAGE_SENT           = "update.messageSent";
        /** 消息被编辑/删除，手环端据此刷新 */
        public static final String MESSAGE_UPDATED        = "update.messageUpdated";
        /** A page fetched from Telegram is returned asynchronously after the local cache response. */
        public static final String HISTORY_PAGE            = "update.historyPage";
        public static final String HISTORY_ERROR           = "update.historyError";
        public static final String DIALOGS_PAGE             = "update.dialogsPage";
        public static final String DIALOGS_ERROR            = "update.dialogsError";
        public static final String DIALOGS_INVALIDATED      = "update.dialogsInvalidated";
        public static final String MESSAGE_CACHE_INVALIDATED = "update.messageCacheInvalidated";
    }

    /** 错误码 */
    public static final class Code {
        private Code() {}
        public static final int OK               = 0;
        public static final int PARSE_ERROR      = -32700;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS   = -32602;
        public static final int INTERNAL_ERROR   = -32603;
        public static final int NOT_AUTHORIZED   = 401;
        public static final int PEER_INVALID     = 400;
    }
}
