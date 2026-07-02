package com.elotouch.devicetester.modules.battery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;

/**
 * Battery & Power module (PRD §3.12): static status (health, temp, level,
 * voltage) plus live charging current (mA) and charge type. Current reading
 * is best-effort — many ROMs return 0 or an unusable value, in which case
 * we show a note instead of erroring.
 */
public class BatteryActivity extends BaseTestActivity {

    private TextView statusText;
    private TextView liveText;
    private BatteryManager batteryManager;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateStatus(intent);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            updateLiveCurrent();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected String title() {
        return "Battery & Power 电池电源测试";
    }

    @Override
    protected void buildUi() {
        batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);

        addSectionTitle("Battery Status / 电池状态");
        statusText = addInfo("Reading… 读取中…");

        addSectionTitle("Live Charging / 实时充电检测");
        liveText = addInfo("Reading… 读取中…");

        // Sticky broadcast gives current state immediately.
        Intent sticky = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (sticky != null) updateStatus(sticky);
        main.post(poll);
    }

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
                        + "Health 健康度：%s\nStatus 状态：%s",
                pct, tempTenths / 10f, voltageMv / 1000f, healthStr(health), statusStr(status)));

        liveText.setText("Charge type 充电类型：" + pluggedStr(plugged) + "\nCurrent 实时电流：reading… 读取中…");
    }

    private void updateLiveCurrent() {
        if (batteryManager == null) return;
        // CURRENT_NOW is in microamps on most devices; best-effort.
        int microAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        String currentLine;
        if (microAmps == Integer.MIN_VALUE || microAmps == 0) {
            currentLine = "Current 实时电流：not readable on this device 该机型不支持读取";
        } else {
            currentLine = String.format(Locale.US, "Current 实时电流：%.0f mA (%s)",
                    microAmps / 1000f, microAmps > 0 ? "charging 充电" : "discharging 放电");
        }
        String existing = liveText.getText().toString();
        int idx = existing.indexOf("\nCurrent");
        String head = idx >= 0 ? existing.substring(0, idx) : existing;
        liveText.setText(head + "\n" + currentLine);
    }

    @Override
    protected void onStopTests() {
        main.removeCallbacks(poll);
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-arm after returning to the page.
        if (batteryManager != null) {
            try {
                Intent sticky = registerReceiver(batteryReceiver,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (sticky != null) updateStatus(sticky);
            } catch (Exception ignored) {
            }
            main.removeCallbacks(poll);
            main.post(poll);
        }
    }

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
            case 0: return "Unplugged 未连接电源";
            default: return "Other 其他";
        }
    }
}
