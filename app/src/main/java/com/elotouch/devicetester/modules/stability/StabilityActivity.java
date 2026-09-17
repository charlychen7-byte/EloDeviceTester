package com.elotouch.devicetester.modules.stability;

import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * System stability module: runs any combination of CPU full load, GPU
 * rendering, EMMC read/write and DDR read/write continuously, for a duration
 * in minutes (blank = until stopped), and reports each subsystem's live
 * counters plus a best-effort temperature readout.
 *
 * <p>Unlike the single-test modules this page needs real concurrency — four
 * tasks at once, the CPU one with a thread per core — so it runs its own
 * cached thread pool instead of {@link BaseTestActivity}'s single-thread
 * executor. Leaving the page stops everything, frees the DDR working set,
 * deletes the EMMC temp file and pauses the GL surface.
 */
public class StabilityActivity extends BaseTestActivity {

    private static final int PASS_COLOR = Color.parseColor("#2E7D32");
    private static final int FAIL_COLOR = Color.parseColor("#C62828");
    private static final int GL_VIEW_HEIGHT_DP = 200;
    private static final int THERMAL_ZONES = 20;

    private CheckBox cpuBox;
    private CheckBox gpuBox;
    private CheckBox emmcBox;
    private CheckBox ddrBox;
    private EditText minutesInput;
    private Button startButton;
    private Button stopButton;
    private TextView statusText;
    private TextView tempText;
    private LinearLayout statusContainer;
    private LinearLayout glContainer;
    private int defaultStatusColor;

    private final List<StressTask> tasks = new ArrayList<>();
    private final List<TextView> taskViews = new ArrayList<>();
    private ExecutorService pool;

    /** Thermal zone discovered on the first sample: path, or "" once known absent. */
    private volatile String thermalPath;

    private boolean running;
    private long startTimeMs;
    private Long durationMs;
    private boolean durationReached;
    private boolean userStopped;
    private volatile String tempLine = "";

    private final Runnable autoStopRunnable = () -> {
        durationReached = true;
        stopRun();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            renderRunningStatus();
            ExecutorService p = pool;
            if (p != null) p.execute(StabilityActivity.this::sampleTemperature);
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected String title() {
        return "System Stability 系统稳定性";
    }

    @Override
    protected void buildUi() {
        addInfo("Runs the selected subsystems under continuous load to look for "
                + "instability");

        addSectionTitle("测试项 / Tests");
        cpuBox = addCheckBox("CPU full loading / CPU 满载运算");
        gpuBox = addCheckBox("GPU running / GPU 持续渲染");
        emmcBox = addCheckBox("EMMC read & write / EMMC 读写校验");
        ddrBox = addCheckBox("DDR read & write / DDR 读写校验");

        addSectionTitle("测试时长（分钟）/ Duration (minutes)");
        minutesInput = new EditText(this);
        minutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutesInput.setSingleLine(true);
        minutesInput.setHint("Blank = unlimited / 留空 = 无限长");
        addView(minutesInput);

        statusText = addInfo("Tap Start. 点击开始测试。");
        defaultStatusColor = statusText.getCurrentTextColor();
        startButton = addButton("Start Test / 开始测试", this::start);
        stopButton = addButton("Stop / 停止", this::stopByUser);
        stopButton.setEnabled(false);

        addSectionTitle("实时状态 / Live Status");
        statusContainer = new LinearLayout(this);
        statusContainer.setOrientation(LinearLayout.VERTICAL);
        addView(statusContainer);
        tempText = addInfo("");

        glContainer = new LinearLayout(this);
        glContainer.setOrientation(LinearLayout.VERTICAL);
        addView(glContainer);
    }

    private CheckBox addCheckBox(String text) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setChecked(true);
        addView(box);
        return box;
    }

    // ----------------------------------------------------------------- control

    private void start() {
        if (running) return;

        tasks.clear();
        taskViews.clear();
        statusContainer.removeAllViews();
        glContainer.removeAllViews();

        if (cpuBox.isChecked()) tasks.add(new CpuLoadTask());
        if (gpuBox.isChecked()) {
            GpuLoadTask gpuTask = new GpuLoadTask(this);
            tasks.add(gpuTask);
            glContainer.addView(gpuTask.view(), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(GL_VIEW_HEIGHT_DP)));
        }
        if (emmcBox.isChecked()) tasks.add(new EmmcLoadTask(getCacheDir()));
        if (ddrBox.isChecked()) tasks.add(new DdrLoadTask(this));

        if (tasks.isEmpty()) {
            statusText.setTextColor(defaultStatusColor);
            statusText.setText("Select at least one test. 请至少勾选一项测试。");
            return;
        }

        for (StressTask task : tasks) {
            TextView tv = new TextView(this);
            tv.setTextSize(14);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(4);
            tv.setLayoutParams(lp);
            statusContainer.addView(tv);
            taskViews.add(tv);
        }

        durationMs = parseDurationMs();
        running = true;
        userStopped = false;
        durationReached = false;
        tempLine = "";
        startTimeMs = System.currentTimeMillis();
        statusText.setTextColor(defaultStatusColor);
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        setControlsEnabled(false);

        pool = Executors.newCachedThreadPool();
        for (StressTask task : tasks) task.start(pool);

        main.post(tick);
        main.removeCallbacks(autoStopRunnable);
        if (durationMs != null) main.postDelayed(autoStopRunnable, durationMs);
    }

    private void stopByUser() {
        if (!running) return;
        userStopped = true;
        stopRun();
    }

    /** Stops every task, releases what they hold and freezes the final numbers. */
    private void stopRun() {
        if (!running) return;
        running = false;
        main.removeCallbacks(tick);
        main.removeCallbacks(autoStopRunnable);

        for (StressTask task : tasks) task.stop();
        ExecutorService p = pool;
        pool = null;
        if (p != null) p.shutdownNow();

        glContainer.removeAllViews();

        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        setControlsEnabled(true);
        renderFinalStatus();
    }

    @Override
    protected void onStopTests() {
        stopRun();
    }

    private void setControlsEnabled(boolean enabled) {
        cpuBox.setEnabled(enabled);
        gpuBox.setEnabled(enabled);
        emmcBox.setEnabled(enabled);
        ddrBox.setEnabled(enabled);
        minutesInput.setEnabled(enabled);
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

    // ------------------------------------------------------------------ status

    private void renderRunningStatus() {
        statusText.setText(String.format(Locale.US,
                "Running 运行中   已测试时间 Elapsed: %s%s",
                formatElapsed(elapsedSeconds()),
                durationMs == null ? "" : " / " + formatElapsed(durationMs / 1000)));
        renderTaskLines();
        tempText.setText(tempLine);
    }

    private void renderTaskLines() {
        for (int i = 0; i < tasks.size(); i++) {
            StressTask task = tasks.get(i);
            TextView tv = taskViews.get(i);
            tv.setText(String.format(Locale.US, "%-5s %s", task.label(), task.status()));
            tv.setTextColor(task.errorCount() > 0 ? FAIL_COLOR : defaultStatusColor);
        }
    }

    private void renderFinalStatus() {
        renderTaskLines();
        tempText.setText(tempLine);

        int errors = 0;
        for (StressTask task : tasks) errors += task.errorCount();
        String elapsed = formatElapsed(elapsedSeconds());
        String ending = durationReached
                ? "，已达设定时长 / duration reached"
                : (userStopped ? "，手动停止 / stopped by user" : "，页面已离开 / page left");

        if (errors > 0) {
            statusText.setTextColor(FAIL_COLOR);
            statusText.setText(String.format(Locale.US,
                    "FAILED 测试失败：共 %d 处错误 / %d errors，已测试时间 Elapsed %s%s",
                    errors, errors, elapsed, ending));
        } else {
            statusText.setTextColor(PASS_COLOR);
            statusText.setText(String.format(Locale.US,
                    "PASSED 测试通过：未发现错误 / no errors，已测试时间 Elapsed %s%s",
                    elapsed, ending));
        }
    }

    private long elapsedSeconds() {
        return (System.currentTimeMillis() - startTimeMs) / 1000;
    }

    private static String formatElapsed(long totalSeconds) {
        return String.format(Locale.US, "%02d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    // ------------------------------------------------------------- temperature

    /**
     * Best-effort temperature sample. Runs on the pool, never the UI thread, so
     * the thermal-zone file reads cannot stall the tick; the tick just renders
     * whatever the last sample produced.
     */
    private void sampleTemperature() {
        StringBuilder sb = new StringBuilder();
        try {
            Intent battery = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                if (tenths > 0) {
                    sb.append(String.format(Locale.US, "电池 Battery %.1f°C", tenths / 10.0));
                }
            }
        } catch (Exception ignored) {
            // Some builds refuse the sticky battery broadcast; temperature is optional.
        }

        String cpu = readCpuThermal();
        if (cpu != null) {
            if (sb.length() > 0) sb.append("   ");
            sb.append("CPU ").append(cpu);
        }
        tempLine = sb.length() == 0
                ? "温度 Temp: 不可获取 / Not available"
                : "温度 Temp: " + sb;
    }

    /**
     * First CPU/SoC thermal zone that this build lets us read, or null. The zone
     * is located once and then remembered ("" when there is none), so the
     * per-second sample does not rescan up to 40 sysfs files every time.
     */
    private String readCpuThermal() {
        String known = thermalPath;
        if (known != null) {
            return known.isEmpty() ? null : readTemperature(known);
        }
        for (int zone = 0; zone < THERMAL_ZONES; zone++) {
            String base = "/sys/class/thermal/thermal_zone" + zone + "/";
            String type = readFirstLine(base + "type");
            if (type == null) continue;
            String lower = type.toLowerCase(Locale.US);
            if (!lower.contains("cpu") && !lower.contains("soc") && !lower.contains("tsens")) {
                continue;
            }
            String temperature = readTemperature(base + "temp");
            if (temperature != null) {
                thermalPath = base + "temp";
                return temperature;
            }
        }
        thermalPath = "";
        return null;
    }

    private static String readTemperature(String path) {
        String raw = readFirstLine(path);
        if (raw == null) return null;
        try {
            double value = Double.parseDouble(raw.trim());
            // Zones report either milli-degrees or whole degrees.
            if (Math.abs(value) > 1000) value /= 1000.0;
            return String.format(Locale.US, "%.1f°C", value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            if (line == null) return null;
            line = line.trim();
            return line.isEmpty() ? null : line;
        } catch (Exception e) {
            return null;
        }
    }
}
