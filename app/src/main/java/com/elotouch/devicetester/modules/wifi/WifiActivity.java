package com.elotouch.devicetester.modules.wifi;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.elotouch.devicetester.modules.ethernet.IperfActivity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Inet4Address;

/**
 * Wi-Fi module: shows the current connection (SSID, IP, BSSID,
 * RSSI, channel/band) and a shortcut to the system Wi-Fi settings.
 * Location permission is requested when the page opens, since SSID/BSSID
 * are hidden without it.
 */
public class WifiActivity extends BaseTestActivity {

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private TextView wifiText;

    private static final long REFRESH_INTERVAL_MS = 1000;

    private final Runnable periodicRefresh = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected String title() {
        return "Wi-Fi 测试";
    }

    @Override
    protected void buildUi() {
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        addSectionTitle("Wi-Fi");
        wifiText = addInfo("Loading… 加载中…");
        addButton("Wi-Fi Settings / 打开 Wi-Fi 设置", this::openWifiSettings);

        addInfo("Open Settings to check Wifi Mac");
        addInfo("Before checking, make sure Wifi on");
        addButton("Open About settings", this::openAboutSettings);

        addSectionTitle("Speed Test");
        addInfo("Runs speed test by using iPerf3 to measure wifi download/upload throughput.");
        addButton("Enter Test",
                () -> startActivity(new Intent(this, IperfActivity.class)));

        // SSID/BSSID need location permission on API 27+; the rest of the page works without it.
        requirePermission(Manifest.permission.ACCESS_FINE_LOCATION, this::refresh, this::refresh);
    }

    /** Opens the system Wi-Fi settings page (the device's current Wi-Fi screen). */
    private void openWifiSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast("No Wi-Fi settings page on this device. 本设备没有 Wi-Fi 设置页面。");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Poll once a second so RSSI, connection and IP changes show up live.
        if (wifiText == null) return;
        main.removeCallbacks(periodicRefresh);
        periodicRefresh.run();
    }

    @Override
    protected void onStopTests() {
        main.removeCallbacks(periodicRefresh);
    }

    private void openAboutSettings() {
        try {
            // "About phone" is where the device model is shown; the same page
            // the Bluetooth module opens.
            startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast("About settings not available. 无法打开设置。");
        }
    }

    @SuppressWarnings("deprecation") // getConnectionInfo() is deprecated on API 31+, but it is still the way to read RSSI/BSSID/frequency here
    private void refresh() {
        if (wifiManager == null) {
            wifiText.setText("No Wi-Fi on this device. 本设备不支持 Wi-Fi。");
            return;
        }
        boolean locationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        StringBuilder sb = new StringBuilder();
        sb.append("Wi-Fi 开关：").append(wifiManager.isWifiEnabled() ? "On 已开启" : "Off 已关闭").append('\n');

        Network network = connectivityManager.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : connectivityManager.getNetworkCapabilities(network);
        boolean connected = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        WifiInfo info = wifiManager.getConnectionInfo();

        sb.append(connected ? "连接状态 Status：Connected 已连接\n" : "连接状态 Status：Not connected 未连接\n");
        sb.append("Device Wifi MAC：").append(deviceMac());
        if (connected) {
            sb.append("\nSSID：").append(ssid(info, locationGranted)).append('\n');
            sb.append("IP Address：").append(ipAddress(network)).append('\n');
            sb.append("BSSID：").append(bssid(info, locationGranted)).append('\n');
            sb.append("RSSI：").append(info.getRssi()).append(" dBm\n");
            sb.append("Channel(Band)：").append(channelBand(info.getFrequency()));
        }
        if (!locationGranted) {
            sb.append("\n\n权限受限：SSID / BSSID 需要定位权限。");
        }
        wifiText.setText(sb.toString());
    }

    /**
     * Device WLAN MAC, read the same way the system Settings "About" page does:
     * WifiManager.getFactoryMacAddresses() (a @SystemApi, so it is called reflectively).
     * Ordinary apps may be denied this call; in that case we show "Unknown".
     */
    private String deviceMac() {
        try {
            Method m = WifiManager.class.getMethod("getFactoryMacAddresses");
            String[] macs = (String[]) m.invoke(wifiManager);
            if (macs != null && macs.length > 0 && macs[0] != null && !macs[0].isEmpty()) {
                return macs[0];
            }
        } catch (InvocationTargetException e) {
            // Usually SecurityException: this app lacks the privileged Wi-Fi permission.
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // API not present on this build.
        }
        return "Unknown 未知";
    }

    private static String ssid(WifiInfo info, boolean locationGranted) {
        if (!locationGranted) return "Permission restricted 权限受限";
        String raw = info.getSSID();
        if (raw == null || raw.equals(WifiManager.UNKNOWN_SSID)) return "Unknown 未知";
        // The framework wraps SSIDs in quotes; strip them for display.
        return raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")
                ? raw.substring(1, raw.length() - 1)
                : raw;
    }

    private static String bssid(WifiInfo info, boolean locationGranted) {
        if (!locationGranted) return "Permission restricted 权限受限";
        String bssid = info.getBSSID();
        return bssid == null ? "Unknown 未知" : bssid;
    }

    private String ipAddress(Network network) {
        LinkProperties lp = connectivityManager.getLinkProperties(network);
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) return la.getAddress().getHostAddress();
            }
        }
        return "Unknown 未知";
    }

    /** Maps a Wi-Fi centre frequency (MHz) to "channel (band)", e.g. "36 (5 GHz)". */
    private static String channelBand(int mhz) {
        if (mhz <= 0) return "Unknown 未知";
        if (mhz == 2484) return "14 (2.4 GHz)";
        if (mhz >= 2412 && mhz <= 2472) return (mhz - 2407) / 5 + " (2.4 GHz)";
        if (mhz >= 5160 && mhz <= 5885) return (mhz - 5000) / 5 + " (5 GHz)";
        if (mhz >= 5955 && mhz <= 7115) return (mhz - 5950) / 5 + " (6 GHz)";
        return mhz + " MHz";
    }
}
