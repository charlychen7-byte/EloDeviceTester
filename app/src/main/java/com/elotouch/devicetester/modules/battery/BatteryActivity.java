package com.elotouch.devicetester.modules.battery;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;

/**
 * Battery & Power module (PRD §3.12): live status (level, voltage,
 * temperature, health, charge status and charge type) plus a charge/discharge
 * test that logs one CSV record per minute.
 *
 * <p>The recording itself lives in {@link BatteryLogService} so it keeps going
 * with the screen off and after this page is left; this Activity only starts
 * and stops it and polls its progress once a second while visible.
 */
public class BatteryActivity extends BaseTestActivity {

    private static final int PASS_COLOR = Color.parseColor("#2E7D32");
    private static final int FAIL_COLOR = Color.parseColor("#C62828");

    private TextView statusText;
    private TextView testStatusText;
    private TextView pathText;
    private Button startButton;
    private Button stopButton;
    private int defaultStatusColor;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateStatus(intent);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            renderTestStatus();
            // Keep polling only while there is something to watch.
            if (BatteryLogService.running) main.postDelayed(this, 1000);
        }
    };

    @Override
    protected String title() {
        return "Battery & Power 电池电源测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Battery Status / 电池状态");
        statusText = addInfo("Reading… 读取中…");

        addSectionTitle("充放电测试 / Charge-Discharge Test");
        addInfo("Appends one record per minute — time, level, voltage, temperature, "
                + "charge status and charge type — to a CSV file you can pull off the "
                + "device.\n"
                + "每分钟记录一条电池信息（时间、电量、电压、温度、充电状态、充电类型等）到 "
                + "CSV 文件，可直接拷出设备分析。");
        testStatusText = addInfo("Tap Start. 点击开始测试。");
        defaultStatusColor = testStatusText.getCurrentTextColor();
        startButton = addButton("Start Test / 开始测试", this::startRecording);
        stopButton = addButton("Stop / 停止", this::stopRecording);
        stopButton.setEnabled(false);

        pathText = addInfo("");
        pathText.setTypeface(Typeface.MONOSPACE);
        pathText.setTextIsSelectable(true);
        // The battery receiver is registered in onResume, which runs right after this.
    }

    // ------------------------------------------------------------ live status

    private void updateStatus(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = scale > 0 ? Math.round(level * 100f / scale) : -1;
        int tempTenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0);
        int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        statusText.setText(String.format(Locale.US,
                "Level 电量：%d%%\nTemperature 温度：%.1f ℃\nVoltage 电压：%.3f V\n"
                        + "Health 健康度：%s\nStatus 状态：%s\nCharge type 充电类型：%s",
                pct, tempTenths / 10f, voltageMv / 1000f,
                healthStr(health), statusStr(status), pluggedStr(plugged)));
    }

    // --------------------------------------------------------------- recording

    private void startRecording() {
        if (BatteryLogService.running) return;
        // Lazy, per-module permission (PRD §5.4): the ongoing notification needs
        // POST_NOTIFICATIONS on API 33+. Recording still works without it — only
        // the notification is hidden — so a refusal does not block the test.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            requirePermission(Manifest.permission.POST_NOTIFICATIONS,
                    this::launchService,
                    () -> {
                        toast("通知被限制，记录继续但看不到通知 / notification restricted");
                        launchService();
                    });
            return;
        }
        launchService();
    }

    private void launchService() {
        ContextCompat.startForegroundService(this,
                new Intent(this, BatteryLogService.class).setAction(BatteryLogService.ACTION_START));
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        testStatusText.setTextColor(defaultStatusColor);
        testStatusText.setText("Starting… 启动中…");
        pathText.setText("");
        main.removeCallbacks(tick);
        main.postDelayed(tick, 500);
    }

    private void stopRecording() {
        stopButton.setEnabled(false);
        startService(new Intent(this, BatteryLogService.class)
                .setAction(BatteryLogService.ACTION_STOP));
        // The service clears its running flag synchronously; give the last write a
        // moment to land before showing the final count.
        main.postDelayed(tick, 500);
    }

    /** Renders whatever the service is currently reporting. */
    private void renderTestStatus() {
        String path = BatteryLogService.logPath;
        pathText.setText(path == null ? "" : "Log 保存路径 / Saved to:\n" + path);

        boolean isRunning = BatteryLogService.running;
        startButton.setEnabled(!isRunning);
        stopButton.setEnabled(isRunning);

        String error = BatteryLogService.errorMessage;
        int records = BatteryLogService.recordCount;

        if (isRunning) {
            long sinceSample = (System.currentTimeMillis() - BatteryLogService.lastSampleMs) / 1000;
            long nextIn = Math.max(0, 60 - sinceSample);
            testStatusText.setTextColor(defaultStatusColor);
            testStatusText.setText(String.format(Locale.US,
                    "Recording 记录中   已记录 Records: %d   已测试时间 Elapsed: %s"
                            + "   下次记录 Next in: %ds",
                    records, formatElapsed(elapsedSeconds()), nextIn));
        } else if (error != null) {
            testStatusText.setTextColor(FAIL_COLOR);
            testStatusText.setText("FAILED 测试失败：" + error);
        } else if (records > 0) {
            testStatusText.setTextColor(PASS_COLOR);
            testStatusText.setText(String.format(Locale.US,
                    "Stopped 已停止：共记录 %d 条 / %d records，已测试时间 Elapsed %s",
                    records, records, formatElapsed(elapsedSeconds())));
        } else {
            testStatusText.setTextColor(defaultStatusColor);
            testStatusText.setText("Tap Start. 点击开始测试。");
        }
    }

    private static long elapsedSeconds() {
        long start = BatteryLogService.startTimeMs;
        if (start == 0) return 0;
        long end = BatteryLogService.running || BatteryLogService.endTimeMs == 0
                ? System.currentTimeMillis() : BatteryLogService.endTimeMs;
        return (end - start) / 1000;
    }

    private static String formatElapsed(long totalSeconds) {
        return String.format(Locale.US, "%02d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onStopTests() {
        // Deliberately does NOT stop the service: recording is meant to outlive
        // this page. Only the on-screen polling and the status receiver stop.
        main.removeCallbacks(tick);
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Intent sticky = registerReceiver(batteryReceiver,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (sticky != null) updateStatus(sticky);
        } catch (Exception ignored) {
            // Status stays at its last known values.
        }
        // Reflect a run that is still going (or has finished) from a previous visit.
        main.removeCallbacks(tick);
        main.post(tick);
    }

    // ---------------------------------------------------------------- labels

    private static String healthStr(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good 良好";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheat 过热";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead 损坏";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over-voltage 过压";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold 过冷";
            default: return "Unknown 未知";
        }
    }

    private static String statusStr(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging 充电中";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging 放电中";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full 已充满";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging 未充电";
            default: return "Unknown 未知";
        }
    }

    private static String pluggedStr(int p) {
        switch (p) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "AC 充电器";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "Wireless 无线充电";
            case BatteryManager.BATTERY_PLUGGED_DOCK: return "Dock 底座";
            case 0: return "Unplugged 未连接电源";
            default: return "Other 其他";
        }
    }
}
