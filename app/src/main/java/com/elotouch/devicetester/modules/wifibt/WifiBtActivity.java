package com.elotouch.devicetester.modules.wifibt;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Wi-Fi & Bluetooth module (PRD §3.10): Wi-Fi state + AP scan (SSID/RSSI) +
 * a light ping latency test; Bluetooth state + device discovery (name/RSSI).
 * Permissions are requested lazily and per API level.
 */
public class WifiBtActivity extends BaseTestActivity {

    private WifiManager wifiManager;
    private BluetoothAdapter btAdapter;
    private TextView wifiText, btText;
    private boolean wifiReceiverRegistered, btReceiverRegistered;
    private final Set<String> btSeen = new LinkedHashSet<>();

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context c, Intent i) {
            try {
                StringBuilder sb = new StringBuilder("扫描到热点：\n");
                for (ScanResult r : wifiManager.getScanResults()) {
                    String ssid = (r.SSID == null || r.SSID.isEmpty()) ? "(隐藏)" : r.SSID;
                    sb.append(String.format(Locale.US, "  %s   RSSI=%d dBm\n", ssid, r.level));
                }
                wifiText.setText(sb.toString());
            } catch (SecurityException e) {
                wifiText.setText("权限受限：无法读取扫描结果。");
            }
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context c, Intent i) {
            if (!BluetoothDevice.ACTION_FOUND.equals(i.getAction())) return;
            BluetoothDevice device = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            short rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
            if (device == null) return;
            String name;
            try {
                name = device.getName();
            } catch (SecurityException e) {
                name = null;
            }
            if (name == null) name = "(未知)";
            btSeen.add(String.format(Locale.US, "  %s [%s]  RSSI=%d dBm",
                    name, device.getAddress(), rssi));
            StringBuilder sb = new StringBuilder("扫描到蓝牙设备：\n");
            for (String s : btSeen) sb.append(s).append('\n');
            btText.setText(sb.toString());
        }
    };

    @Override
    protected String title() {
        return "Wi-Fi & Bluetooth 测试";
    }

    @Override
    protected void buildUi() {
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = bm != null ? bm.getAdapter() : null;

        addSectionTitle("Wi-Fi");
        wifiText = addInfo(wifiStatus());
        addButton("扫描周边热点", this::scanWifi);
        addButton("Ping 延迟测试 (8.8.8.8)", this::pingTest);

        addSectionTitle("Bluetooth");
        btText = addInfo(btStatus());
        addButton("扫描蓝牙设备", this::scanBt);
    }

    private String wifiStatus() {
        if (wifiManager == null) return "本设备不支持 Wi-Fi。";
        return "Wi-Fi 开关：" + (wifiManager.isWifiEnabled() ? "已开启" : "已关闭");
    }

    private String btStatus() {
        if (btAdapter == null) return "本设备不支持蓝牙。";
        return "蓝牙开关：" + (btAdapter.isEnabled() ? "已开启" : "已关闭");
    }

    // ----------------------------------------------------------------- Wi-Fi

    private void scanWifi() {
        if (wifiManager == null) return;
        // Scan results require location permission + location services on API 26+.
        requirePermission(Manifest.permission.ACCESS_FINE_LOCATION,
                this::doWifiScan,
                () -> wifiText.setText("权限受限：扫描热点需要定位权限。"));
    }

    @SuppressLint("MissingPermission")
    private void doWifiScan() {
        if (!wifiReceiverRegistered) {
            registerReceiver(wifiReceiver,
                    new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            wifiReceiverRegistered = true;
        }
        wifiText.setText("扫描中…");
        boolean started = wifiManager.startScan(); // throttled on newer Android
        if (!started) wifiText.setText("扫描请求被系统限流，请稍后重试。");
    }

    private void pingTest() {
        wifiText.setText("Ping 中…");
        runAsync(() -> {
            try {
                long t0 = System.nanoTime();
                InetAddress addr = InetAddress.getByName("8.8.8.8");
                boolean reachable = addr.isReachable(3000);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String msg = reachable
                        ? String.format(Locale.US, "Ping 8.8.8.8 成功：%d ms", ms)
                        : "Ping 超时（3s 内不可达）。";
                ui(() -> wifiText.setText(msg));
            } catch (Exception e) {
                ui(() -> wifiText.setText("Ping 失败：" + e.getMessage()));
            }
        });
    }

    // ----------------------------------------------------------------- Bluetooth

    private void scanBt() {
        if (btAdapter == null) return;
        if (!btAdapter.isEnabled()) {
            btText.setText("蓝牙未开启，请先在系统中开启蓝牙。");
            return;
        }
        String[] perms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        requirePermissions(perms, this::doBtScan,
                () -> btText.setText("权限受限：扫描蓝牙需要相应权限。"));
    }

    @SuppressLint("MissingPermission")
    private void doBtScan() {
        if (!btReceiverRegistered) {
            registerReceiver(btReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            btReceiverRegistered = true;
        }
        btSeen.clear();
        btText.setText("蓝牙扫描中…");
        try {
            if (btAdapter.isDiscovering()) btAdapter.cancelDiscovery();
            btAdapter.startDiscovery();
        } catch (SecurityException e) {
            btText.setText("权限受限：" + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void onStopTests() {
        if (wifiReceiverRegistered) {
            try { unregisterReceiver(wifiReceiver); } catch (Exception ignored) { }
            wifiReceiverRegistered = false;
        }
        if (btReceiverRegistered) {
            try { unregisterReceiver(btReceiver); } catch (Exception ignored) { }
            btReceiverRegistered = false;
        }
        if (btAdapter != null) {
            try { btAdapter.cancelDiscovery(); } catch (SecurityException ignored) { }
        }
    }
}
