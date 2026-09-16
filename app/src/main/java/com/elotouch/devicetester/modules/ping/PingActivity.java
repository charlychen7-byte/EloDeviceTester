package com.elotouch.devicetester.modules.ping;

import android.content.Intent;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.elotouch.devicetester.R;
import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;

/**
 * Ping module: a one-shot connectivity check against public DNS, run inline on
 * this page, plus an entry into {@link PingTestActivity} for a continuous test
 * against an operator-supplied address.
 */
public class PingActivity extends BaseTestActivity {

    private static final String PUBLIC_DNS = "8.8.8.8";
    /** Generous enough for a slow WAN link, since this is a single packet. */
    private static final int QUICK_TIMEOUT_SECONDS = 3;

    private TextView quickResultText;
    private Button quickButton;
    private int defaultResultColor;

    @Override
    protected String title() {
        return "Ping 连通性测试 / Connectivity";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Ping 8.8.8.8 / 快速连通性");
        addInfo("Sends one ICMP echo to Google public DNS (8.8.8.8) and reports whether "
                + "it replied, plus the round-trip time.\n"
                + "向 Google 公共 DNS（8.8.8.8）发送一个 ICMP 包，显示是否连通及响应时间。");
        quickResultText = addInfo("Tap to test. 点击开始测试。");
        defaultResultColor = quickResultText.getCurrentTextColor();
        quickButton = addButton("Start Test / 开始测试", this::runQuickTest);

        addSectionTitle("自定义 Ping / Custom Ping");
        addInfo("Continuously pings a server address you enter, for a duration in minutes "
                + "(blank = run until stopped), reporting latency and packet loss.\n"
                + "持续 ping 自行输入的服务器地址，测试时长按分钟计（留空 = 无限长，直到手动停止），"
                + "显示延迟与丢包率。");
        addButton("Enter Test / 进入测试",
                () -> startActivity(new Intent(this, PingTestActivity.class)));
    }

    private void runQuickTest() {
        quickButton.setEnabled(false);
        quickResultText.setTextColor(defaultResultColor);
        quickResultText.setText("Testing… 测试中…");
        runAsync(() -> {
            Ping.Result r = Ping.once(PUBLIC_DNS, QUICK_TIMEOUT_SECONDS);
            ui(() -> showQuickResult(r));
        });
    }

    private void showQuickResult(Ping.Result r) {
        quickButton.setEnabled(true);
        if (r.received) {
            quickResultText.setTextColor(ContextCompat.getColor(this, R.color.ok));
            quickResultText.setText(String.format(Locale.US,
                    "连通 Connected：%s\n响应时间 Time：%s%s",
                    PUBLIC_DNS,
                    Double.isNaN(r.rttMs)
                            ? "N/A" : String.format(Locale.US, "%.1f ms", r.rttMs),
                    r.ttl != null ? "   ttl=" + r.ttl : ""));
        } else {
            quickResultText.setTextColor(ContextCompat.getColor(this, R.color.error));
            quickResultText.setText(String.format(Locale.US,
                    "不通 Failed：%s\n%s", PUBLIC_DNS, r.failureText()));
        }
    }
}
