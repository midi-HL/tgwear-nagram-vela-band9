package org.telegram.tgwear;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Map;

/** On-device diagnostics and explicit reconnect/probe controls for TG Wear. */
public final class WearDiagnosticActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView statusView;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            renderStatus();
            if (!isFinishing()) handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.rgb(20, 36, 52));
            getWindow().setNavigationBarColor(Color.rgb(20, 36, 52));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(15, 23, 32));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("TG Wear 连接诊断");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title, matchWrap());

        TextView hint = new TextView(this);
        hint.setText("查看手机 SDK 到手环快应用的各阶段状态。往返测试成功表示手环请求已到手机，且手机响应返回手环。此页面不会读取 Telegram 私聊内容。");
        hint.setTextColor(Color.LTGRAY);
        hint.setTextSize(14);
        hint.setPadding(0, 0, 0, dp(14));
        root.addView(hint, matchWrap());

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(15);
        statusView.setPadding(dp(14), dp(14), dp(14), dp(14));
        statusView.setBackgroundColor(Color.rgb(30, 42, 54));
        root.addView(statusView, matchWrap());

        addButton(root, "启动 / 恢复桥接并重新发现手环", v -> sendServiceAction(WearBridgeService.ACTION_RECONNECT));
        addButton(root, "发送手机 → 手环连接挑战", v -> sendServiceAction(WearBridgeService.ACTION_PROBE));
        addButton(root, "刷新状态", v -> renderStatus());
        renderStatus();
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void sendServiceAction(String action) {
        try {
            Intent intent = new Intent(this, WearBridgeService.class).setAction(action);
            ContextCompat.startForegroundService(this, intent);
            Toast.makeText(this, action.equals(WearBridgeService.ACTION_PROBE)
                    ? "连接挑战已提交；等待手环确认" : "已请求服务恢复与重新连接", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            Toast.makeText(this, "无法启动桥接服务：" + error.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private void renderStatus() {
        if (statusView == null) return;
        Map<String, Object> s = WearDiagnosticStatus.snapshot();
        statusView.setText(
                "阶段：" + value(s, "stage") + "\n\n" +
                "桥接服务：" + yesNo(s.get("serviceRunning")) + "\n" +
                "小米 SDK：" + yesNo(s.get("sdkAvailable")) + "\n" +
                "设备管理授权：" + tri(s.get("permissionGranted")) + "\n" +
                "已发现节点：" + value(s, "nodeId") + "\n" +
                "手环端 TG Wear：" + tri(s.get("wearableAppInstalled")) + "\n" +
                "手机消息监听：" + yesNo(s.get("transportReady")) + "\n\n" +
                "最近收到手环消息：" + value(s, "lastWatchMessageAt") + "\n" +
                "最近发起手机→手环测试：" + value(s, "lastPhoneProbeAt") + "\n" +
                "最近收到手环确认：" + value(s, "lastWatchAckAt") + "\n" +
                "状态更新时间：" + value(s, "updatedAt") + "\n\n" +
                "最近错误：" + value(s, "lastError"));
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        parent.addView(button, params);
    }

    private String yesNo(Object value) { return Boolean.TRUE.equals(value) ? "是" : "否"; }
    private String tri(Object value) { return value == null ? "未知" : (Boolean.TRUE.equals(value) ? "是" : "否"); }
    private String value(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null || String.valueOf(value).isEmpty() ? "—" : String.valueOf(value);
    }
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
