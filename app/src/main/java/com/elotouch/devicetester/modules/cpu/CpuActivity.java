package com.elotouch.devicetester.modules.cpu;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.InputType;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;

/**
 * CPU module: a single page with static information (SoC, cores, ABI, cluster
 * layout, governor) and live per-core frequency.
 *
 * <p>All procfs/sysfs reads happen on the {@link BaseTestActivity} background
 * executor in a 1 Hz loop (PRD §5.5: never touch the UI thread with I/O), and
 * the loop is stoppable from the button at the bottom of the page. Values that
 * the kernel does not expose on this device render as "not available" instead
 * of failing the module.
 *
 * <p>The page also hosts the CPU load test: {@link CpuLoadTester} drives the
 * cores to a typed-in percentage on its own daemon threads (the base executor
 * is single-threaded and already busy with the refresh loop), and the load is
 * released by the Stop button, by {@code onPause}, and by leaving the page.
 */
public class CpuActivity extends BaseTestActivity {

    private static final long REFRESH_INTERVAL_MS = 1000L;
    private static final String STOP_LABEL = "Stop refresh / 停止刷新";
    private static final String START_LABEL = "Start refresh / 开始刷新";
    private static final String NOT_AVAILABLE = "不可获取 / Not available";
    private static final String LOAD_START_LABEL = "开始加载 / Start load";
    private static final String LOAD_STOP_LABEL = "停止并释放 / Stop & release";
    private static final int DEFAULT_TARGET_PERCENT = 50;

    private int coreCount;

    private TextView infoText;
    private TextView freqStatusText;
    private TextView freqText;
    private Button toggleButton;

    private final CpuUsageSampler usageSampler = new CpuUsageSampler();
    private final CpuLoadTester loadTester = new CpuLoadTester();
    private TextView loadUsageText;
    private TextView loadStatusText;
    private EditText targetInput;
    private Button loadButton;

    private volatile boolean refreshing;
    private volatile boolean infoDirty = true;
    /**
     * Bumped whenever the loop should end. A loop only keeps going while its own
     * generation is current, so a pause/resume cycle cannot leave the previous
     * loop alive (the executor is single-threaded — a second live loop would
     * block the queued one forever).
     */
    private volatile int loopGeneration;
    /** Set when the user stopped the loop, so onResume does not restart it. */
    private boolean userPaused;

    @Override
    protected String title() {
        return "CPU / Processor 处理器测试";
    }

    @Override
    protected void buildUi() {
        coreCount = CpuReader.coreCount();

        addSectionTitle("基本信息 / CPU Info");
        infoText = addInfo("Loading… 读取中…");
        addButton("Refresh Info / 刷新信息", this::requestInfoRefresh);

        addSectionTitle("实时频率 / Live Frequency");
        freqStatusText = addInfo("");
        freqText = addInfo("Loading… 读取中…");
        freqText.setTypeface(Typeface.MONOSPACE);

        toggleButton = addButton(STOP_LABEL, this::toggleRefresh);

        buildLoadUi();
    }

    private void buildLoadUi() {
        addSectionTitle("负载测试 / CPU Load Test");
        loadUsageText = addInfo(usageLine(CpuUsageSampler.UNKNOWN, true));
        loadUsageText.setTextSize(18);
        loadUsageText.setTypeface(Typeface.DEFAULT_BOLD);

        addInfo("目标负载率 1-100 % / Target load, one busy thread per core ("
                + loadTester.threadCount() + ")");
        targetInput = new EditText(this);
        targetInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        targetInput.setHint("1 - 100");
        targetInput.setText(String.valueOf(DEFAULT_TARGET_PERCENT));
        addView(targetInput);

        loadButton = addButton(LOAD_START_LABEL, this::toggleLoad);
        loadStatusText = addInfo("未运行 / Idle — CPU 未被本测试占用。");
    }

    // ------------------------------------------------------------- refreshing

    @Override
    protected void onResume() {
        super.onResume();
        if (!userPaused) startRefresh();
        // onPause released the load; put the controls back in their idle state.
        if (!loadTester.isRunning()) showLoadStopped();
    }

    private void toggleRefresh() {
        if (refreshing) {
            userPaused = true;
            refreshing = false;
            loopGeneration++;
            stopTests();
            toggleButton.setText(START_LABEL);
            freqStatusText.setText("Refresh stopped. 已停止刷新。");
        } else {
            userPaused = false;
            startRefresh();
        }
    }

    private void startRefresh() {
        if (refreshing) return;
        refreshing = true;
        infoDirty = true;
        toggleButton.setText(STOP_LABEL);
        freqStatusText.setText("Running 运行中 · 每秒刷新 / refreshing every 1s");
        final int generation = ++loopGeneration;
        runAsync(() -> refreshLoop(generation));
    }

    private void requestInfoRefresh() {
        infoDirty = true;
        // While the loop runs it picks the flag up on its next tick; the executor
        // is single-threaded, so a one-shot task would otherwise queue behind it.
        if (!refreshing) {
            runAsync(() -> {
                infoDirty = false;
                final String info = buildInfoText();
                ui(() -> infoText.setText(info));
            });
        }
    }

    /** Runs on the background executor until stopped or superseded. */
    private void refreshLoop(int generation) {
        while (generation == loopGeneration && refreshing && !isStopped()) {
            if (infoDirty) {
                infoDirty = false;
                final String info = buildInfoText();
                ui(() -> infoText.setText(info));
            }

            final String freq = buildFreqText();
            ui(() -> freqText.setText(freq));

            final double usage = usageSampler.sample();
            final boolean systemWide = usageSampler.isSystemWide();
            ui(() -> loadUsageText.setText(usageLine(usage, systemWide)));

            try {
                Thread.sleep(REFRESH_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ------------------------------------------------------------------- text

    /** Static-ish info block; runs on the background thread. */
    private String buildInfoText() {
        StringBuilder sb = new StringBuilder();

        String soc = CpuReader.socName();
        sb.append("硬件 Hardware/SoC：").append(soc != null ? soc : NOT_AVAILABLE).append('\n');

        String processor = CpuReader.processorName();
        if (processor != null) {
            sb.append("处理器 Processor：").append(processor).append('\n');
        }

        int online = Runtime.getRuntime().availableProcessors();
        sb.append("核心数 Cores：").append(coreCount)
                .append("（在线 online：").append(online).append("）\n");

        sb.append("CPU ABI 架构：").append(joinAbis(Build.SUPPORTED_ABIS)).append('\n');
        String abis64 = joinAbis(Build.SUPPORTED_64_BIT_ABIS);
        sb.append("位宽 Bitness：")
                .append(abis64.isEmpty() ? "32-bit only" : "64-bit (" + abis64 + ")")
                .append('\n');

        String cluster = CpuReader.clusterSummary(coreCount);
        sb.append("集群 Clusters：").append(cluster != null ? cluster : NOT_AVAILABLE).append('\n');

        String governor = CpuReader.governor(0);
        sb.append("调频策略 Governor (cpu0)：")
                .append(governor != null ? governor : NOT_AVAILABLE);
        return sb.toString();
    }

    private static String joinAbis(String[] abis) {
        if (abis == null || abis.length == 0) return "";
        return String.join(", ", abis);
    }

    /** Per-core frequency block; runs on the background thread. */
    private String buildFreqText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < coreCount; i++) {
            CpuReader.CoreFreq f = CpuReader.readFreq(i);
            sb.append(String.format(Locale.US, "%-7s", "cpu" + i));
            if (f.curKHz == CpuReader.UNKNOWN && f.maxKHz == CpuReader.UNKNOWN) {
                sb.append(NOT_AVAILABLE);
            } else {
                sb.append(f.curKHz == CpuReader.UNKNOWN
                        ? "offline 离线" : mhz(f.curKHz) + " MHz");
                if (f.minKHz != CpuReader.UNKNOWN && f.maxKHz != CpuReader.UNKNOWN) {
                    sb.append(String.format(Locale.US, "   (%s - %s MHz)",
                            mhz(f.minKHz), mhz(f.maxKHz)));
                }
            }
            if (i < coreCount - 1) sb.append('\n');
        }
        return sb.length() == 0 ? NOT_AVAILABLE : sb.toString();
    }

    private static String mhz(long kHz) {
        return String.format(Locale.US, "%d", Math.round(kHz / 1000.0));
    }

    // ------------------------------------------------------------- load test

    private void toggleLoad() {
        if (loadTester.isRunning()) {
            stopLoad();
        } else {
            startLoad();
        }
    }

    private void startLoad() {
        Integer target = parseTarget();
        if (target == null) {
            toast("请输入 1-100 的目标负载率 / Enter a target between 1 and 100");
            return;
        }
        hideKeyboard();
        targetInput.setEnabled(false);
        loadButton.setText(LOAD_STOP_LABEL);
        loadStatusText.setText(String.format(Locale.US,
                "运行中 Running · 目标 target %d%% · %d 线程 threads · 占空比 duty %d%%",
                target, loadTester.threadCount(), target));
        // Without the live figure the test is unverifiable, so make sure the
        // 1 Hz loop that feeds it is running even if the user had paused it.
        userPaused = false;
        startRefresh();
        loadTester.start(target, this::onLoadSample);
    }

    private void stopLoad() {
        loadTester.stop();
        showLoadStopped();
    }

    private void showLoadStopped() {
        loadButton.setText(LOAD_START_LABEL);
        targetInput.setEnabled(true);
        loadStatusText.setText("未运行 / Idle — CPU 未被本测试占用。");
    }

    /** Controller-thread callback: mirror the applied duty cycle into the status line. */
    private void onLoadSample(double measuredPercent, boolean systemWide, int dutyPercent) {
        final String status = String.format(Locale.US,
                "运行中 Running · 目标 target %d%% · %d 线程 threads · 占空比 duty %d%%",
                loadTester.targetPercent(), loadTester.threadCount(), dutyPercent);
        ui(() -> {
            if (loadTester.isRunning()) loadStatusText.setText(status);
        });
    }

    /** Typed target, or {@code null} if it is blank / not within 1-100. */
    private Integer parseTarget() {
        String text = targetInput.getText().toString().trim();
        if (text.isEmpty()) return null;
        try {
            int value = Integer.parseInt(text);
            return (value >= 1 && value <= 100) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String usageLine(double percent, boolean systemWide) {
        if (percent == CpuUsageSampler.UNKNOWN) {
            return "当前负载率 Current load：测量中… / measuring…";
        }
        return String.format(Locale.US, "当前负载率 Current load：%.1f%%", percent);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(targetInput.getWindowToken(), 0);
        targetInput.clearFocus();
    }

    @Override
    protected void onStopTests() {
        refreshing = false;
        loopGeneration++;
        // Leaving the page (or backgrounding it) must release the cores.
        loadTester.stop();
    }
}
