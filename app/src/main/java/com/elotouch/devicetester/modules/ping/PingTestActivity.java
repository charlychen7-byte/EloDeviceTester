package com.elotouch.devicetester.modules.ping;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Continuous ping test against an operator-supplied address, for a duration in
 * minutes (blank = until stopped).
 *
 * <p>This issues one {@link Ping#once} per second on the background executor
 * rather than leaving a long-lived ping process running: that counts every lost
 * packet exactly (a timed-out packet produces no output line of its own) and
 * makes Stop take effect immediately.
 */
public class PingTestActivity extends BaseTestActivity {

    private static final int PASS_COLOR = Color.parseColor("#2E7D32");
    private static final int FAIL_COLOR = Color.parseColor("#C62828");
    private static final int MAX_LOG_LINES = 200;
    private static final long PING_INTERVAL_MS = 1000L;
    /** Kept below the interval so the loop holds its 1 Hz cadence. */
    private static final int PING_TIMEOUT_SECONDS = 1;

    private EditText hostInput;
    private EditText minutesInput;
    private TextView statusText;
    private TextView statsText;
    private Button startButton;
    private Button stopButton;
    private ScrollView logScroll;
    private TextView logText;

    private final Deque<String> logLines = new ArrayDeque<>();
    private int defaultStatusColor;

    private boolean running;
    private String target;
    private long startTimeMs;
    private Long durationMs;
    private boolean userStopRequested;
    private boolean durationReached;
    private volatile String fatalError;

    private volatile int sent;
    private volatile int lost;
    private volatile int latencySamples;
    private volatile double minMs;
    private volatile double maxMs;
    private volatile double sumMs;

    private final Runnable autoStopRunnable = () -> {
        durationReached = true;
        stopTests();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            statusText.setText(String.format(Locale.US,
                    "Running 运行中   目标 Target: %s   已测试时间 Elapsed: %s%s",
                    target, formatElapsed(elapsedSeconds()),
                    durationMs == null ? "" : " / " + formatElapsed(durationMs / 1000)));
            main.postDelayed(this, 1000);
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
        if (running) return;

        final String host = hostInput.getText().toString().trim();
        if (host.isEmpty()) {
            toast("Enter a server address. 请输入服务器地址。");
            return;
        }

        durationMs = parseDurationMs();
        target = host;
        running = true;
        userStopRequested = false;
        durationReached = false;
        fatalError = null;
        sent = 0;
        lost = 0;
        latencySamples = 0;
        minMs = Double.MAX_VALUE;
        maxMs = 0;
        sumMs = 0;
        startTimeMs = System.currentTimeMillis();

        logLines.clear();
        logText.setText("");
        statusText.setTextColor(defaultStatusColor);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        setInputsEnabled(false);
        updateStats();
        main.post(tick);

        main.removeCallbacks(autoStopRunnable);
        if (durationMs != null) {
            main.postDelayed(autoStopRunnable, durationMs);
        }

        runAsync(() -> pingLoop(host));
    }

    private void stopTest() {
        if (!running) return;
        userStopRequested = true;
        // The loop notices within one interval; disable the button now so the
        // operator does not press it twice while the last packet drains.
        stopButton.setEnabled(false);
        stopTests();
    }

    /** Minutes field to milliseconds; null when blank or not a positive number. */
    private Long parseDurationMs() {
        String text = minutesInput.getText().toString().trim();
        if (text.isEmpty()) return null;
        try {
            long minutes = Long.parseLong(text);
            return minutes > 0 ? minutes * 60_000L : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void setInputsEnabled(boolean enabled) {
        hostInput.setEnabled(enabled);
        minutesInput.setEnabled(enabled);
    }

    // -------------------------------------------------------------- ping loop

    private void pingLoop(String host) {
        while (!isStopped()) {
            long loopStartNs = System.nanoTime();
            Ping.Result r = Ping.once(host, PING_TIMEOUT_SECONDS);
            sent++;
            final int seq = sent;

            if (r.fatalMessage != null) {
                fatalError = r.fatalMessage;
                appendLog(seq + ": " + r.fatalMessage);
                break;
            }
            if (r.received) {
                if (!Double.isNaN(r.rttMs)) {
                    latencySamples++;
                    minMs = Math.min(minMs, r.rttMs);
                    maxMs = Math.max(maxMs, r.rttMs);
                    sumMs += r.rttMs;
                }
                appendLog(String.format(Locale.US, "%d: reply from %s  time=%s ms  ttl=%s",
                        seq, host,
                        Double.isNaN(r.rttMs) ? "?" : String.format(Locale.US, "%.1f", r.rttMs),
                        r.ttl != null ? r.ttl : "?"));
            } else {
                lost++;
                appendLog(seq + ": " + r.failureText());
            }
            ui(this::updateStats);

            long elapsedMs = (System.nanoTime() - loopStartNs) / 1_000_000;
            long sleepMs = PING_INTERVAL_MS - elapsedMs;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        ui(this::onTestFinished);
    }

    // ------------------------------------------------------------------- views

    private void appendLog(String line) {
        ui(() -> {
            if (logLines.size() >= MAX_LOG_LINES) logLines.removeFirst();
            logLines.addLast(line);
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) sb.append(l).append('\n');
            logText.setText(sb.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void updateStats() {
        int sentNow = sent;
        int lostNow = lost;
        double lossPct = sentNow == 0 ? 0.0 : (100.0 * lostNow / sentNow);
        String latency = latencySamples > 0
                ? String.format(Locale.US, "min=%.1f  avg=%.1f  max=%.1f ms",
                        minMs, sumMs / latencySamples, maxMs)
                : "N/A";
        statsText.setText(String.format(Locale.US,
                "已发送 Sent: %d   丢失 Lost: %d   丢包率 Loss: %.1f%%\n延迟 Latency: %s",
                sentNow, lostNow, lossPct, latency));
    }

    private long elapsedSeconds() {
        return (System.currentTimeMillis() - startTimeMs) / 1000;
    }

    private static String formatElapsed(long totalSeconds) {
        return String.format(Locale.US, "%02d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    private void onTestFinished() {
        if (!running) return;
        running = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(autoStopRunnable);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        setInputsEnabled(true);
        updateStats();

        String elapsed = formatElapsed(elapsedSeconds());
        String ending = durationReached
                ? "，已达设定时长 / duration reached"
                : (userStopRequested ? "，手动停止 / stopped by user" : "");

        if (fatalError != null) {
            statusText.setTextColor(FAIL_COLOR);
            statusText.setText(String.format(Locale.US,
                    "FAILED 测试失败：%s (%s)，已测试时间 Elapsed %s",
                    fatalError, target, elapsed));
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

    @Override
    protected void onStopTests() {
        // onPause already raised the stop flag; the loop exits on its next check.
        main.removeCallbacks(tick);
        main.removeCallbacks(autoStopRunnable);
    }
}
