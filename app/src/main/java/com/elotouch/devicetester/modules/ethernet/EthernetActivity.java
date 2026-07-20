package com.elotouch.devicetester.modules.ethernet;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.widget.Button;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

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
    private TextView resultText;
    private Button startButton;
    private Button stopButton;

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

        addSectionTitle("连接性压力测试 / Connectivity Stress Test");
        resultText = addInfo("等待检测到以太网网关… / Waiting for an Ethernet gateway…");
        startButton = addButton("Start / 开始", this::startStressTest);
        stopButton = addButton("Stop / 停止", this::stopStressTest);
        startButton.setEnabled(false);
        stopButton.setEnabled(false);
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
        String speed = EthernetLinkInfo.describeLinkSpeed(snap.interfaceName);
        sb.append("链路速率 Link Speed: ").append(speed != null ? speed : "不可获取 / Not available");
        detailsText.setText(sb.toString());

        updateStartButtonAvailability();
    }

    private void onEthernetLost() {
        currentGateway = null;
        statusText.setText(NOT_DETECTED_MESSAGE);
        detailsText.setText("");
        updateStartButtonAvailability();
    }

    private void updateStartButtonAvailability() {
        boolean canStart = currentGateway != null;
        startButton.setEnabled(canStart);
        if (!canStart) {
            resultText.setText("等待检测到以太网网关… / Waiting for an Ethernet gateway…");
        }
    }

    // Stress-test methods (startStressTest, stopStressTest) are added in
    // the next step of this plan — declared here as no-ops so buildUi()
    // compiles; the next task replaces this block with the real implementation.
    private void startStressTest() {
    }

    private void stopStressTest() {
    }
}
