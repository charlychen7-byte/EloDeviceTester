package com.elotouch.devicetester.modules.bluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;

/**
 * Bluetooth module page (level 2). Shows the local adapter's MAC address and
 * offers a button that opens the system Bluetooth settings screen.
 *
 * <p>On Android 6+ the system reports a placeholder address to ordinary apps;
 * the real MAC is then only visible in system Settings, which the page says.
 */
public class BluetoothActivity extends BaseTestActivity {

    /** Address Android returns to apps that are not allowed to read the real MAC. */
    private static final String HIDDEN_MAC = "02:00:00:00:00:00";

    private TextView macInfo;

    @Override
    protected String title() {
        return "Bluetooth / 蓝牙";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("1. Bluetooth MAC address / 蓝牙 MAC 地址");
        macInfo = addInfo("Reading… 读取中");
        addButton("Open About phone 打开关于手机", () -> openSettings(
                Settings.ACTION_DEVICE_INFO_SETTINGS, "About phone not available. 无法打开关于手机。"));

        addSectionTitle("2. Settings / 设置");
        addButton("Open Bluetooth settings 打开蓝牙设置", this::openBluetoothSettings);

        // BLUETOOTH_CONNECT is a runtime permission on API 31+; on older
        // releases the array is empty and the callback runs immediately.
        requirePermissions(bluetoothPermissions(), this::readMac,
                () -> macInfo.setText("Permission restricted 权限受限"));
    }

    private String[] bluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[0];
    }

    @SuppressLint("MissingPermission")
    private void readMac() {
        runAsync(() -> {
            String text;
            try {
                BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
                if (adapter == null) {
                    text = "No Bluetooth adapter 无蓝牙适配器";
                } else {
                    String mac = adapter.getAddress();
                    if (mac == null || mac.isEmpty() || HIDDEN_MAC.equals(mac)) {
                        text = "Hidden by system; check Settings > About phone.\n"
                                + "系统隐藏了真实地址，请在 设置 > 关于手机 中查看。";
                    } else {
                        text = mac.toUpperCase(Locale.US);
                    }
                }
            } catch (SecurityException e) {
                text = "Permission restricted 权限受限";
            }
            String result = text;
            ui(() -> macInfo.setText(result));
        });
    }

    private void openSettings(String action, String unavailableMsg) {
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException e) {
            toast(unavailableMsg);
        }
    }

    /**
     * Tries the standard Bluetooth action first, then the AOSP Bluetooth page
     * by explicit component, since some builds ignore the action and land on
     * the Settings home screen.
     */
    private void openBluetoothSettings() {
        Intent[] candidates = {
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                new Intent().setClassName("com.android.settings",
                        "com.android.settings.bluetooth.BluetoothSettings"),
        };
        for (Intent intent : candidates) {
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // Try the next candidate.
            }
        }
        toast("Bluetooth settings not available. 无法打开蓝牙设置。");
    }
}
