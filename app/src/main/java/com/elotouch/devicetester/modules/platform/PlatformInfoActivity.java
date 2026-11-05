package com.elotouch.devicetester.modules.platform;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Platform Info page (level 2). Shows the screen size, Android version and
 * serial number, and offers a button that opens the system Model (About phone)
 * settings screen.
 *
 * <p>Serial number: {@code Build.getSerial()} needs READ_PHONE_STATE on
 * Android 8–9 and a privileged permission on Android 10+. When it is refused
 * the page falls back to the {@code ro.serialno} system property, which many
 * devices expose to ordinary apps.
 */
public class PlatformInfoActivity extends BaseTestActivity {

    private static final String UNKNOWN = "unknown";

    private TextView serialInfo;

    @Override
    protected String title() {
        return "Platform Info / 平台信息";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("1. Screen / 屏幕");
        addInfo(screenText());

        addSectionTitle("2. Android version / 系统版本");
        addInfo("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Build " + Build.DISPLAY);

        addSectionTitle("3. Serial number / 序列号");
        serialInfo = addInfo("Reading… 读取中");
        requirePermission(Manifest.permission.READ_PHONE_STATE,
                () -> readSerial(true), () -> readSerial(false));

        addSectionTitle("4. Settings / 设置");
        addButton("Open Model settings 打开型号设置", this::openModelSettings);
    }

    private String screenText() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);

        StringBuilder sb = new StringBuilder();
        sb.append("Resolution 分辨率：")
                .append(dm.widthPixels).append(" x ").append(dm.heightPixels).append(" px");
        sb.append("\nDensity 密度：").append(dm.densityDpi).append(" dpi");

        if (dm.xdpi > 0 && dm.ydpi > 0) {
            double w = dm.widthPixels / (double) dm.xdpi;
            double h = dm.heightPixels / (double) dm.ydpi;
            sb.append("\nSize 尺寸：")
                    .append(String.format(Locale.US, "%.1f", Math.sqrt(w * w + h * h)))
                    .append(" inch");
        } else {
            sb.append("\nSize 尺寸：unknown");
        }
        return sb.toString();
    }

    /** Reads the serial off the UI thread; {@code permitted} is false when READ_PHONE_STATE was denied. */
    @SuppressLint({"HardwareIds", "MissingPermission"})
    private void readSerial(boolean permitted) {
        runAsync(() -> {
            String sn = null;
            if (permitted) {
                try {
                    sn = Build.getSerial();
                } catch (SecurityException e) {
                    // Restricted to privileged apps on this Android version.
                }
            }
            if (!isKnown(sn)) sn = getProp("ro.serialno");
            if (!isKnown(sn)) sn = getProp("ro.boot.serialno");

            String text;
            if (isKnown(sn)) {
                text = sn;
            } else if (!permitted) {
                text = "Permission restricted 权限受限";
            } else {
                text = "Not available 无法读取（系统限制）";
            }
            ui(() -> serialInfo.setText(text));
        });
    }

    private static boolean isKnown(String value) {
        return value != null && !value.trim().isEmpty() && !UNKNOWN.equalsIgnoreCase(value.trim());
    }

    /** Returns the trimmed value of a system property via {@code getprop}, or null. */
    private static String getProp(String key) {
        try {
            Process p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                p.waitFor();
                return line == null ? null : line.trim();
            } finally {
                p.destroy();
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void openModelSettings() {
        try {
            // "About phone" is where the device model is shown; the same page
            // the Bluetooth module opens.
            startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast("Model settings not available. 无法打开型号设置。");
        }
    }
}
