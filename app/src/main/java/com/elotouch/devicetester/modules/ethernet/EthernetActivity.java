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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
    private TextView resultText;
    private Button startButton;
    private Button stopButton;

    private static final Pattern TIME_PATTERN = Pattern.compile("time[=<]\\s*([0-9.]+)");

    private volatile boolean ethernetLost;
    private boolean running;
    private int sent;
    private int lost;
    private int latencySamples;
    private double minMs;
    private double maxMs;
    private double sumMs;
    private long startTimeMs;
    private String pingTarget;

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
        ethernetLost = true;
        statusText.setText(NOT_DETECTED_MESSAGE);
        detailsText.setText("");
        updateStartButtonAvailability();
    }

    private void updateStartButtonAvailability() {
        boolean canStart = currentGateway != null && !running;
        startButton.setEnabled(canStart);
        if (currentGateway == null && !running) {
            resultText.setText("等待检测到以太网网关… / Waiting for an Ethernet gateway…");
        }
    }

    private void startStressTest() {
        String gateway = currentGateway;
        if (gateway == null) {
            resultText.setText("未检测到以太网网关 / No Ethernet gateway detected");
            return;
        }
        pingTarget = gateway;
        running = true;
        ethernetLost = false;
        sent = 0;
        lost = 0;
        latencySamples = 0;
        minMs = Double.MAX_VALUE;
        maxMs = 0;
        sumMs = 0;
        startTimeMs = System.currentTimeMillis();
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        runAsync(() -> pingLoop(gateway));
    }

    private void stopStressTest() {
        stopTests();
    }

    private void pingLoop(String host) {
        while (!isStopped() && !ethernetLost) {
            long loopStartNs = System.nanoTime();
            PingResult r = pingOnce(host);
            sent++;
            if (!r.received) {
                lost++;
            } else if (!Double.isNaN(r.rttMs)) {
                latencySamples++;
                minMs = Math.min(minMs, r.rttMs);
                maxMs = Math.max(maxMs, r.rttMs);
                sumMs += r.rttMs;
            }
            ui(this::updateResultText);
            long elapsedMs = (System.nanoTime() - loopStartNs) / 1_000_000;
            long sleepMs = 1000 - elapsedMs;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        ui(this::onStressTestFinished);
    }

    private PingResult pingOnce(String host) {
        try {
            Process process = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", host)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) return new PingResult(false, Double.NaN);
            Matcher m = TIME_PATTERN.matcher(output);
            double rtt = m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
            return new PingResult(true, rtt);
        } catch (IOException e) {
            return new PingResult(false, Double.NaN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PingResult(false, Double.NaN);
        }
    }

    private void updateResultText() {
        long elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000;
        double lossPct = sent == 0 ? 0.0 : (100.0 * lost / sent);
        String latency = latencySamples > 0
                ? String.format(Locale.US, "min=%.1f avg=%.1f max=%.1f ms",
                        minMs, sumMs / latencySamples, maxMs)
                : "N/A";
        resultText.setText(String.format(Locale.US,
                "目标 Target: %s\n"
                        + "已发送 Sent: %d   丢失 Lost: %d (%.1f%%)\n"
                        + "延迟 Latency: %s\n"
                        + "已运行 Elapsed: %s",
                pingTarget, sent, lost, lossPct, latency, formatElapsed(elapsedSec)));
    }

    private static String formatElapsed(long totalSeconds) {
        return String.format(Locale.US, "%02d:%02d:%02d",
                totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60);
    }

    private void onStressTestFinished() {
        running = false;
        stopButton.setEnabled(false);
        if (ethernetLost) {
            resultText.setText("设备已断开 / Device disconnected");
        }
        updateStartButtonAvailability();
    }

    private static final class PingResult {
        final boolean received;
        final double rttMs;

        PingResult(boolean received, double rttMs) {
            this.received = received;
            this.rttMs = rttMs;
        }
    }
}
