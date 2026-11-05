package com.elotouch.devicetester.modules.ethernet;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ethernet module: live wired-network info (IP/gateway/DNS/link speed via
 * {@link EthernetLinkInfo}) driven by a {@link ConnectivityManager.NetworkCallback},
 * plus a ping-based connectivity stress test (added in a later step of this file).
 */
public class EthernetActivity extends BaseTestActivity {

    private static final String NOT_DETECTED_MESSAGE =
            "未检测到以太网设备，请插入USB网卡 / No Ethernet device detected, please plug in a USB adapter";

    private ConnectivityManager connectivityManager;
    private boolean callbackRegistered;

    private volatile String currentGateway;

    private TextView statusText;
    private TextView detailsText;

    private final ConnectivityManager.NetworkCallback ethernetCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            ui(() -> onEthernetAvailable(network));
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            ui(() -> onEthernetAvailable(network));
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
            ui(() -> onEthernetAvailable(network));
        }

        @Override
        public void onLost(Network network) {
            ui(EthernetActivity.this::onEthernetLost);
        }
    };

    @Override
    protected String title() {
        return "Ethernet 测试";
    }

    @Override
    protected void buildUi() {
        connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        addSectionTitle("网络信息 / Network Info");
        statusText = addInfo(NOT_DETECTED_MESSAGE);
        detailsText = addInfo("");
        addButton("Open Ethernet settings 打开以太网设置", this::openEthernetSettings);

        addSectionTitle("Speed Test");
        addInfo("Runs speed test by using iPerf3 to measure download/upload throughput.");
        addButton("Enter Test",
                () -> startActivity(new Intent(this, IperfActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!callbackRegistered && connectivityManager != null) {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .build();
            connectivityManager.registerNetworkCallback(request, ethernetCallback);
            callbackRegistered = true;
        }
    }

    @Override
    protected void onStopTests() {
        if (callbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(ethernetCallback);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
            callbackRegistered = false;
        }
    }

    private void onEthernetAvailable(Network network) {
        LinkProperties lp = connectivityManager.getLinkProperties(network);
        if (lp == null) return;
        EthernetLinkInfo.Snapshot snap = EthernetLinkInfo.fromLinkProperties(lp);
        currentGateway = snap.gateway;

        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
        boolean validated = caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        statusText.setText(validated
                ? "已连接 / Connected"
                : "已连接（无互联网）/ Connected (no internet)");

        StringBuilder sb = new StringBuilder();
        if (snap.ipAddresses.isEmpty()) {
            sb.append("IP: N/A\n");
        } else {
            for (EthernetLinkInfo.IpEntry ip : snap.ipAddresses) {
                sb.append("IP: ").append(ip.address);
                if (ip.subnetMask != null) {
                    sb.append("  子网掩码 Subnet Mask: ").append(ip.subnetMask);
                } else {
                    sb.append("  前缀 Prefix: /").append(ip.prefixLength);
                }
                sb.append('\n');
            }
        }
        sb.append("网关 Gateway: ").append(snap.gateway != null ? snap.gateway : "N/A").append('\n');
        sb.append("DNS: ")
                .append(snap.dnsServers.isEmpty() ? "N/A" : String.join(", ", snap.dnsServers))
                .append('\n');
        detailsText.setText(sb.toString());
    }

    private void onEthernetLost() {
        currentGateway = null;
        statusText.setText(NOT_DETECTED_MESSAGE);
        detailsText.setText("");
    }

    /**
     * Opens the Ethernet configuration page inside the system Settings app
     * (the "配置以太网" screen that shows the Eth Mac). This device's Settings
     * has no public action for it, so the fragment is opened directly through
     * SubSettings. Falls back to the network settings and then the Settings
     * home screen if that component is not present.
     */
    private void openEthernetSettings() {
        Intent ethernetPage = new Intent()
                .setClassName("com.android.settings", "com.android.settings.SubSettings")
                .putExtra(":settings:show_fragment",
                        "com.android.settings.ethernet.EthernetSettings");
        Intent[] candidates = {
                ethernetPage,
                new Intent(Settings.ACTION_WIRELESS_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent intent : candidates) {
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                // Try the next candidate.
            }
        }
        toast("Settings not available. 无法打开设置。");
    }
}
