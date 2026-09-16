package com.elotouch.devicetester.modules.cpu;

import android.graphics.Typeface;
import android.os.Build;
import android.widget.Button;
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
 */
public class CpuActivity extends BaseTestActivity {

    private static final long REFRESH_INTERVAL_MS = 1000L;
    private static final String STOP_LABEL = "Stop refresh / 停止刷新";
    private static final String START_LABEL = "Start refresh / 开始刷新";
    private static final String NOT_AVAILABLE = "不可获取 / Not available";

    private int coreCount;

    private TextView infoText;
    private TextView freqStatusText;
    private TextView freqText;
    private Button toggleButton;

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
    }

    // ------------------------------------------------------------- refreshing

    @Override
    protected void onResume() {
        super.onResume();
        if (!userPaused) startRefresh();
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

    @Override
    protected void onStopTests() {
        refreshing = false;
        loopGeneration++;
    }
}
