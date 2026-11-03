package com.elotouch.devicetester.modules.ping;

import android.Manifest;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;

/**
 * Continuous ping test against an operator-supplied address, for a duration in
 * minutes (blank = until stopped).
 *
 * <p>The loop itself lives in {@link PingService} so it keeps going after this
 * page is sent to the background with the "后台运行" button. This Activity only
 * starts and stops it and polls its progress once a second while visible.
 * Leaving the page any other way stops the test, as on every other module.
 */
public class PingTestActivity extends BaseTestActivity {

    private static final int PASS_COLOR = Color.parseColor("#2E7D32");
    private static final int FAIL_COLOR = Color.parseColor("#C62828");

    private EditText hostInput;
    private EditText minutesInput;
    private TextView statusText;
    private TextView statsText;
    private Button startButton;
    private Button stopButton;
    private Button backgroundButton;
    private ScrollView logScroll;
    private TextView logText;
    private int defaultStatusColor;
    private int renderedLogVersion = -1;

    /** Set by the background button so onStopTests leaves the service running. */
    private boolean sentToBackground;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            if (PingService.running) main.postDelayed(this, 1000);
        }
    };

    @Override
    protected String title() {
        return "Custom Ping / 自定义 Ping";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("目标 / Target");
        hostInput = new EditText(this);
        hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostInput.setSingleLine(true);
        hostInput.setHint("Server IP or hostname / 服务器 IP 或域名");
        addView(hostInput);

        addSectionTitle("测试时长（分钟）/ Duration (minutes)");
        minutesInput = new EditText(this);
        minutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutesInput.setSingleLine(true);
        minutesInput.setHint("Blank = unlimited / 留空 = 无限长");
        addView(minutesInput);

        statusText = addInfo("Tap Start. 点击开始测试。");
        defaultStatusColor = statusText.getCurrentTextColor();
        startButton = addButton("Start Test / 开始测试", this::startTest);
        stopButton = addButton("Stop / 停止", this::stopTest);
        stopButton.setEnabled(false);
        backgroundButton = addButton("Run in Background / 后台运行", this::runInBackground);
        backgroundButton.setEnabled(false);

        statsText = addInfo("");
        statsText.setTypeface(Typeface.MONOSPACE);
        buildLogBox();
    }

    private void buildLogBox() {
        logText = new TextView(this);
        logText.setTextSize(14);
        logText.setLineSpacing(dp(2), 1f);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setBackgroundColor(Color.BLACK);
        logText.setTextColor(Color.WHITE);

        logScroll = new ScrollView(this);
        logScroll.addView(logText, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240));
        lp.topMargin = dp(6);
        logScroll.setLayoutParams(lp);
        // The page is one big ScrollView (BaseTestActivity), so this same-axis
        // nested ScrollView has to claim the drag itself.
        logScroll.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        content.addView(logScroll);
    }

    // ----------------------------------------------------------------- control

    private void startTest() {
        if (PingService.running) return;

        String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            toast("Enter a server address. 请输入服务器地址。");
            return;
        }

        // Lazy, per-module permission (PRD §5.4): the ongoing notification needs
        // POST_NOTIFICATIONS on API 33+. The test still runs without it — only
        // the notification is hidden — so a refusal does not block the test.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            requirePermission(Manifest.permission.POST_NOTIFICATIONS,
                    () -> launchService(host),
                    () -> {
                        toast("通知被限制，测试继续但看不到通知 / notification restricted");
                        launchService(host);
                    });
            return;
        }
        launchService(host);
    }

    private void launchService(String host) {
        Long minutes = parseDurationMinutes();
        ContextCompat.startForegroundService(this, new Intent(this, PingService.class)
                .setAction(PingService.ACTION_START)
                .putExtra(PingService.EXTRA_HOST, host)
                .putExtra(PingService.EXTRA_DURATION_MS,
                        minutes == null ? 0L : minutes * 60_000L));
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        backgroundButton.setEnabled(true);
        setInputsEnabled(false);
        statusText.setTextColor(defaultStatusColor);
        statusText.setText("Starting… 启动中…");
        main.removeCallbacks(tick);
        main.postDelayed(tick, 300);
    }

    private void stopTest() {
        if (!PingService.running) return;
        // Disable both now so the operator does not press them while the last
        // packet drains; the loop notices within one interval.
        stopButton.setEnabled(false);
        backgroundButton.setEnabled(false);
        startService(new Intent(this, PingService.class).setAction(PingService.ACTION_STOP));
    }

    /** Sends the test to the background and leaves this page; it keeps running. */
    private void runInBackground() {
        if (!PingService.running || PingService.userStopRequested) return;
        sentToBackground = true;
        toast("测试已转入后台 / Test running in background");
        finish();
    }

    /** Minutes field; null when blank or not a positive number. */
    private Long parseDurationMinutes() {
        String text = minutesInput.getText().toString().trim();
        if (text.isEmpty()) return null;
        try {
            long minutes = Long.parseLong(text);
            return minutes > 0 ? minutes : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void setInputsEnabled(boolean enabled) {
        hostInput.setEnabled(enabled);
        minutesInput.setEnabled(enabled);
    }

    // ------------------------------------------------------------------- views

    /** Renders the service's current state; re-run each tick while it is running. */
    private void render() {
        boolean running = PingService.running;
        boolean stopping = PingService.userStopRequested;

        startButton.setEnabled(!running);
        stopButton.setEnabled(running && !stopping);
        backgroundButton.setEnabled(running && !stopping);
        setInputsEnabled(!running);
        if (running) fillInputsFromService();

        renderStatus(running, stopping);
        renderStats();
        renderLog();
    }

    /** Shows the running test's target and length when the page is reopened. */
    private void fillInputsFromService() {
        if (hostInput.getText().length() == 0 && PingService.target != null) {
            hostInput.setText(PingService.target);
        }
        long durationMs = PingService.durationMs;
        if (minutesInput.getText().length() == 0 && durationMs > 0) {
            minutesInput.setText(String.valueOf(durationMs / 60_000L));
        }
    }

    private void renderStatus(boolean running, boolean stopping) {
        String target = PingService.target;
        if (running) {
            statusText.setTextColor(defaultStatusColor);
            long durationMs = PingService.durationMs;
            statusText.setText(String.format(Locale.US,
                    "%s   目标 Target: %s   已测试时间 Elapsed: %s%s",
                    stopping ? "Stopping 停止中" : "Running 运行中",
                    target, formatElapsed(elapsedSeconds()),
                    durationMs > 0 ? " / " + formatElapsed(durationMs / 1000) : ""));
            return;
        }
        if (PingService.endTimeMs == 0) {
            statusText.setTextColor(defaultStatusColor);
            statusText.setText("Tap Start. 点击开始测试。");
            return;
        }

        String elapsed = formatElapsed(elapsedSeconds());
        String ending = PingService.durationReached
                ? "，已达设定时长 / duration reached"
                : (PingService.userStopRequested ? "，手动停止 / stopped by user" : "");
        int sent = PingService.sent;
        int lost = PingService.lost;
        String fatal = PingService.fatalError;

        if (fatal != null) {
            statusText.setTextColor(FAIL_COLOR);
            statusText.setText(String.format(Locale.US,
                    "FAILED 测试失败：%s (%s)，已测试时间 Elapsed %s",
                    fatal, target, elapsed));
        } else if (sent == 0) {
            statusText.setTextColor(defaultStatusColor);
            statusText.setText("No packets sent. 未发送任何数据包。");
        } else if (lost == 0) {
            statusText.setTextColor(PASS_COLOR);
            statusText.setText(String.format(Locale.US,
                    "PASSED 测试通过：0%% 丢包 / no packet loss，已测试时间 Elapsed %s%s",
                    elapsed, ending));
        } else {
            statusText.setTextColor(FAIL_COLOR);
            statusText.setText(String.format(Locale.US,
                    "FAILED 测试失败：丢包 Lost %d/%d (%.1f%%)，已测试时间 Elapsed %s%s",
                    lost, sent, 100.0 * lost / sent, elapsed, ending));
        }
    }

    private void renderStats() {
        int sentNow = PingService.sent;
        int lostNow = PingService.lost;
        int samples = PingService.latencySamples;
        double lossPct = sentNow == 0 ? 0.0 : (100.0 * lostNow / sentNow);
        String latency = samples > 0
                ? String.format(Locale.US, "min=%.1f  avg=%.1f  max=%.1f ms",
                        PingService.minMs, PingService.sumMs / samples, PingService.maxMs)
                : "N/A";
        statsText.setText(String.format(Locale.US,
                "已发送 Sent: %d   丢失 Lost: %d   丢包率 Loss: %.1f%%\n延迟 Latency: %s",
                sentNow, lostNow, lossPct, latency));
    }

    private void renderLog() {
        int version = PingService.logVersion;
        if (version == renderedLogVersion) return;
        renderedLogVersion = version;
        logText.setText(PingService.logText());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private long elapsedSeconds() {
        long end = PingService.running || PingService.endTimeMs == 0
                ? System.currentTimeMillis() : PingService.endTimeMs;
        return (end - PingService.startTimeMs) / 1000;
    }

    private static String formatElapsed(long totalSeconds) {
        return String.format(Locale.US, "%02d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        render();
        main.removeCallbacks(tick);
        if (PingService.running) main.postDelayed(tick, 1000);
    }

    @Override
    protected void onStopTests() {
        // onPause runs when this page is left, including via the background
        // button. Only a plain leave stops the test; a backgrounded one must not.
        main.removeCallbacks(tick);
        if (PingService.running && !sentToBackground) {
            startService(new Intent(this, PingService.class).setAction(PingService.ACTION_STOP));
        }
    }
}
