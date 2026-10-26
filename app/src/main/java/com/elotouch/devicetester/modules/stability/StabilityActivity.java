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
import com.elotouch.devicetester.modules.cpu.CpuReader;
import com.elotouch.devicetester.modules.cpu.CpuUsageSampler;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * System stability module: runs any combination of CPU full load, GPU
 * rendering, EMMC read/write and DDR read/write continuously, for a duration
 * in minutes (blank = until stopped), and reports each subsystem's live
 * counters plus CPU load and a best-effort temperature readout.
 *
 * <p>Every run also writes a CSV log to the app's external files folder: one
 * record immediately after Start and one per minute after that, holding the
 * per-core CPU frequency, CPU load, CPU temperature and battery temperature.
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
    private static final long LOG_INTERVAL_MS = 60_000L;

    private CheckBox cpuBox;
    private CheckBox gpuBox;
    private CheckBox emmcBox;
    private CheckBox ddrBox;
    private EditText minutesInput;
    private Button startButton;
    private Button stopButton;
    private TextView statusText;
    private TextView cpuLoadText;
    private TextView tempText;
    private TextView logText;
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

    /** Recreated per run; only touched inside {@link #sampleSensors()}. */
    private CpuUsageSampler cpuSampler = new CpuUsageSampler();
    private volatile double cpuLoadPercent = CpuUsageSampler.UNKNOWN;
    private volatile Double cpuTempC;
    private volatile Double batteryTempC;

    /** Guards {@link #logWriter}; writes come from the pool, close from the UI thread. */
    private final Object logLock = new Object();
    private BufferedWriter logWriter;
    /** Elapsed time at which the next minute record is due; only used inside {@link #sampleSensors()}. */
    private long nextLogMs;

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
            if (p != null) p.execute(StabilityActivity.this::sampleSensors);
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
        cpuLoadText = addInfo("");
        tempText = addInfo("");
        statusContainer = new LinearLayout(this);
        statusContainer.setOrientation(LinearLayout.VERTICAL);
        addView(statusContainer);
        logText = addInfo("");

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
        cpuLoadPercent = CpuUsageSampler.UNKNOWN;
        cpuTempC = null;
        batteryTempC = null;
        cpuSampler = new CpuUsageSampler();
        nextLogMs = 0;
        startTimeMs = System.currentTimeMillis();
        openLog();
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
        closeLog(durationReached ? "duration reached"
                : (userStopped ? "stopped by user" : "page left"));

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
        cpuLoadText.setText("CPU Loading: " + loadText());
        tempText.setText(tempLine);
        renderTaskLines();
    }

    private String loadText() {
        double load = cpuLoadPercent;
        return load == CpuUsageSampler.UNKNOWN ? "--" : String.format(Locale.US, "%.1f%%", load);
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
        cpuLoadText.setText("CPU 负载 Load: " + loadText());
        tempText.setText(tempLine);
        renderTaskLines();

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
     * Once-a-second sensor sample: CPU load, battery and CPU temperature, plus
     * the minute log record when one is due. Runs on the pool, never the UI
     * thread, so the sysfs/proc reads cannot stall the tick; the tick just
     * renders whatever the last sample produced.
     */
    private synchronized void sampleSensors() {
        Double battery = null;
        try {
            Intent intent = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (intent != null) {
                int tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                if (tenths > 0) battery = tenths / 10.0;
            }
        } catch (Exception ignored) {
            // Some builds refuse the sticky battery broadcast; temperature is optional.
        }
        batteryTempC = battery;
        cpuTempC = readCpuThermal();
        cpuLoadPercent = cpuSampler.sample();

        StringBuilder sb = new StringBuilder();
        if (batteryTempC != null) {
            sb.append(String.format(Locale.US, "电池 Battery %.1f°C", batteryTempC));
        }
        if (cpuTempC != null) {
            if (sb.length() > 0) sb.append("   ");
            sb.append(String.format(Locale.US, "CPU %.1f°C", cpuTempC));
        }
        tempLine = sb.length() == 0
                ? "温度 Temp: 不可获取 / Not available"
                : "温度 Temp: " + sb;

        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        if (elapsedMs >= nextLogMs) {
            nextLogMs += LOG_INTERVAL_MS;
            writeLogRecord(elapsedMs);
        }
    }

    // --------------------------------------------------------------------- log

    /** Creates this run's CSV log in app-private external storage. UI thread. */
    private void openLog() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, "stability_" + stamp + ".log");
        List<String> names = new ArrayList<>();
        for (StressTask task : tasks) names.add(task.label());
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8));
            synchronized (logLock) {
                logWriter = writer;
            }
            writeLog("# Stability test started " + stamp + ", tests: " + String.join(", ", names));
            writeLog("time,elapsed,cpu_freq_khz_per_core,cpu_load_pct,cpu_temp_c,battery_temp_c");
            logText.setText("Log 日志文件: " + file.getAbsolutePath());
        } catch (IOException e) {
            logText.setText("Log 日志不可用 / unavailable: " + e.getMessage());
        }
    }

    /** Writes one CSV record; frequencies are ';'-separated per core, blank when unreadable. */
    private void writeLogRecord(long elapsedMs) {
        StringBuilder freqs = new StringBuilder();
        int cores = CpuReader.coreCount();
        for (int cpu = 0; cpu < cores; cpu++) {
            if (cpu > 0) freqs.append(';');
            long khz = CpuReader.readFreq(cpu).curKHz;
            if (khz != CpuReader.UNKNOWN) freqs.append(khz);
        }
        String load = cpuLoadPercent == CpuUsageSampler.UNKNOWN
                ? "" : String.format(Locale.US, "%.1f", cpuLoadPercent);
        String cpuTemp = cpuTempC == null ? "" : String.format(Locale.US, "%.1f", cpuTempC);
        String batteryTemp = batteryTempC == null
                ? "" : String.format(Locale.US, "%.1f", batteryTempC);
        writeLog(String.format(Locale.US, "%s,%s,%s,%s,%s,%s",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()),
                formatElapsed(elapsedMs / 1000), freqs, load, cpuTemp, batteryTemp));
    }

    /** Appends one line and flushes it, so a crash or power loss keeps every record so far. */
    private void writeLog(String line) {
        synchronized (logLock) {
            if (logWriter == null) return;
            try {
                logWriter.write(line);
                logWriter.newLine();
                logWriter.flush();
            } catch (IOException e) {
                logWriter = null;
                ui(() -> logText.setText("Log 日志写入失败 / write failed: " + e.getMessage()));
            }
        }
    }

    /** Writes the end marker and closes the file. Safe to call when no log is open. */
    private void closeLog(String reason) {
        synchronized (logLock) {
            if (logWriter == null) return;
            try {
                logWriter.write("# ended, elapsed " + formatElapsed(elapsedSeconds())
                        + ", " + reason);
                logWriter.newLine();
                logWriter.close();
            } catch (IOException ignored) {
                // Closing a log that already failed to write has nothing left to report.
            }
            logWriter = null;
        }
    }

    // ------------------------------------------------------------- sensor reads

    /**
     * First CPU/SoC thermal zone that this build lets us read, or null. The zone
     * is located once and then remembered ("" when there is none), so the
     * per-second sample does not rescan up to 40 sysfs files every time.
     */
    private Double readCpuThermal() {
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
            Double temperature = readTemperature(base + "temp");
            if (temperature != null) {
                thermalPath = base + "temp";
                return temperature;
            }
        }
        thermalPath = "";
        return null;
    }

    /** Degrees Celsius from a thermal file, or null when unreadable. */
    private static Double readTemperature(String path) {
        String raw = readFirstLine(path);
        if (raw == null) return null;
        try {
            double value = Double.parseDouble(raw.trim());
            // Zones report either milli-degrees or whole degrees.
            if (Math.abs(value) > 1000) value /= 1000.0;
            return value;
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
