# Ethernet Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new "Ethernet" hardware module to EloDeviceTester that shows
live wired-network information and runs a ping-based connectivity stress
test, per `docs/superpowers/specs/2026-07-20-ethernet-module-design.md`.

**Architecture:** One new package `modules/ethernet/` with two classes — a
small pure-logic helper (`EthernetLinkInfo`) for extracting IP/gateway/DNS
and best-effort link speed/duplex, and an Activity (`EthernetActivity`,
extends `core.BaseTestActivity`) that owns the UI, a
`ConnectivityManager.NetworkCallback` for real-time Ethernet presence
detection, and the ping stress-test loop. The module is registered in
`core/TestModule.java` and `AndroidManifest.xml`.

**Tech Stack:** Java, Android SDK (`ConnectivityManager`, `LinkProperties`,
`NetworkCapabilities`), no new third-party dependencies.

## Global Constraints

- Java only, no Kotlin.
- Minimum SDK 26 (Android 8.0) — every API used below is available at API 26.
- No blocking work on the UI thread — the ping loop runs via
  `BaseTestActivity.runAsync`/`ui`, never directly on the main thread.
- Every long-running/looping test must expose a working Stop button — the
  stress test's Stop button calls `stopTests()`, which the loop polls via
  `isStopped()`.
- No automated test suite exists in this project (confirmed: no JUnit
  dependency in `app/build.gradle`, no test source sets). Verification in
  this plan is: (a) `./gradlew assembleDebug` must succeed after every
  task, and (b) manual on-device/emulator checks where behavior can only
  be observed at runtime. Do not introduce a test framework as part of
  this feature — that would be a separate, unrequested infra change.
- The Ethernet module tile in the main grid is **always enabled** — do
  **not** add a `HardwareDetector.Feature.ETHERNET` value or otherwise
  gate the grid cell (per spec decision 4: the adapter is hot-pluggable,
  so static gating would be wrong).

---

### Task 1: `EthernetLinkInfo` helper class

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/ethernet/EthernetLinkInfo.java`

**Interfaces:**
- Consumes: nothing project-specific — only `android.net.LinkProperties`,
  `android.net.LinkAddress`, `android.net.RouteInfo` (platform SDK types).
- Produces (used by Task 2 and Task 3):
  - `EthernetLinkInfo.IpEntry` — fields `String address`, `String subnetMask`
    (dotted-decimal, nullable — only computed for IPv4), `int prefixLength`.
  - `EthernetLinkInfo.Snapshot` — fields `List<IpEntry> ipAddresses`,
    `String gateway` (nullable), `List<String> dnsServers`,
    `String interfaceName` (nullable).
  - `static EthernetLinkInfo.Snapshot fromLinkProperties(LinkProperties lp)`
  - `static String describeLinkSpeed(String interfaceName)` — returns
    e.g. `"1000 Mbps, Full Duplex"`, or `null` if sysfs doesn't expose it.

- [ ] **Step 1: Create the file**

```java
package com.elotouch.devicetester.modules.ethernet;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort extraction of human-readable Ethernet link details.
 * Link speed/duplex have no stable public Android API, so this reads the
 * kernel's sysfs network-interface files directly and fails gracefully
 * (returns null) when they're unreadable.
 */
final class EthernetLinkInfo {

    private EthernetLinkInfo() {
    }

    static final class IpEntry {
        final String address;
        final String subnetMask; // dotted-decimal, IPv4 only; null for IPv6
        final int prefixLength;

        IpEntry(String address, String subnetMask, int prefixLength) {
            this.address = address;
            this.subnetMask = subnetMask;
            this.prefixLength = prefixLength;
        }
    }

    static final class Snapshot {
        final List<IpEntry> ipAddresses;
        final String gateway;
        final List<String> dnsServers;
        final String interfaceName;

        Snapshot(List<IpEntry> ipAddresses, String gateway, List<String> dnsServers,
                 String interfaceName) {
            this.ipAddresses = ipAddresses;
            this.gateway = gateway;
            this.dnsServers = dnsServers;
            this.interfaceName = interfaceName;
        }
    }

    static Snapshot fromLinkProperties(LinkProperties lp) {
        List<IpEntry> ips = new ArrayList<>();
        for (LinkAddress addr : lp.getLinkAddresses()) {
            InetAddress a = addr.getAddress();
            int prefixLength = addr.getPrefixLength();
            String mask = (a instanceof Inet4Address) ? prefixToIpv4Mask(prefixLength) : null;
            ips.add(new IpEntry(a.getHostAddress(), mask, prefixLength));
        }
        String gateway = null;
        for (RouteInfo route : lp.getRoutes()) {
            if (route.isDefaultRoute() && route.hasGateway()) {
                gateway = route.getGateway().getHostAddress();
                break;
            }
        }
        List<String> dns = new ArrayList<>();
        for (InetAddress addr : lp.getDnsServers()) {
            dns.add(addr.getHostAddress());
        }
        return new Snapshot(ips, gateway, dns, lp.getInterfaceName());
    }

    private static String prefixToIpv4Mask(int prefixLength) {
        long mask = prefixLength == 0 ? 0 : (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
        return String.format(Locale.US, "%d.%d.%d.%d",
                (mask >> 24) & 0xFF, (mask >> 16) & 0xFF, (mask >> 8) & 0xFF, mask & 0xFF);
    }

    /** e.g. "1000 Mbps, Full Duplex". Null if sysfs doesn't expose this. */
    static String describeLinkSpeed(String interfaceName) {
        if (interfaceName == null || interfaceName.isEmpty()) return null;
        Integer speedMbps = readSysfsInt("/sys/class/net/" + interfaceName + "/speed");
        String duplex = readSysfsString("/sys/class/net/" + interfaceName + "/duplex");

        StringBuilder sb = new StringBuilder();
        if (speedMbps != null && speedMbps > 0) {
            sb.append(speedMbps).append(" Mbps");
        }
        if (duplex != null && !duplex.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("full".equalsIgnoreCase(duplex) ? "Full Duplex" : "Half Duplex");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static Integer readSysfsInt(String path) {
        String s = readSysfsString(path);
        if (s == null) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readSysfsString(String path) {
        File f = new File(path);
        if (!f.canRead()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            return line != null ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
```

- [ ] **Step 2: Verify the project still compiles**

Run (from `EloDeviceTester/`): `./gradlew compileDebugJavaWithJavac`
Expected: `BUILD SUCCESSFUL`. This class isn't referenced by anything
yet, so this step only confirms it's syntactically and type-correct.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/ethernet/EthernetLinkInfo.java
git commit -m "feat: add EthernetLinkInfo helper for link info extraction"
```

---

### Task 2: `EthernetActivity` network-info section + module registration

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/ethernet/EthernetActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/core/TestModule.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `EthernetLinkInfo.Snapshot`, `EthernetLinkInfo.IpEntry`,
  `fromLinkProperties`, `describeLinkSpeed` (Task 1); `BaseTestActivity.addSectionTitle`,
  `addInfo`, `addButton`, `ui`, `runAsync`, `isStopped`, `stopTests`,
  `onStopTests()` (existing base class).
- Produces (used by Task 3): private fields `currentGateway` (`String`,
  nullable), `resultText`, `startButton`, `stopButton` (all `TextView`/
  `Button`), and method `updateStartButtonAvailability()` — Task 3 calls
  this after the stress test starts/stops so the Start button's enabled
  state stays in sync with live Ethernet presence.

- [ ] **Step 1: Create `EthernetActivity.java` with the network-info section**

```java
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
```

- [ ] **Step 2: Register the module in `TestModule.java`**

Modify `app/src/main/java/com/elotouch/devicetester/core/TestModule.java`:

Add the import (alphabetical, after the `displayext` import):

```java
import com.elotouch.devicetester.modules.displayext.DisplayExtActivity;
import com.elotouch.devicetester.modules.ethernet.EthernetActivity;
import com.elotouch.devicetester.modules.gps.GpsActivity;
```

Add the enum entry (after `DISPLAY_EXT`, before `CELLULAR` — matches file order otherwise; exact position doesn't affect behavior):

```java
    DISPLAY_EXT("Display Ext / 双屏网络", "📺", DisplayExtActivity.class, HardwareDetector.Feature.ALWAYS),
    ETHERNET("Ethernet / 有线网络", "🖧", EthernetActivity.class, HardwareDetector.Feature.ALWAYS),
    CELLULAR("Cellular / 蜂窝网络", "📡", CellularActivity.class, HardwareDetector.Feature.CELLULAR),
```

- [ ] **Step 3: Register the Activity and optional `uses-feature` in the manifest**

Modify `app/src/main/AndroidManifest.xml`. Add after the other
`android.hardware.*` optional feature declarations (after the
`android.hardware.telephony` line):

```xml
    <uses-feature android:name="android.hardware.ethernet" android:required="false" />
```

Add the activity entry after `.modules.displayext.DisplayExtActivity`:

```xml
        <activity android:name=".modules.displayext.DisplayExtActivity" />
        <activity android:name=".modules.ethernet.EthernetActivity" />
        <activity android:name=".modules.cellular.CellularActivity" />
```

- [ ] **Step 4: Build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Manual verification**

Install on a device or emulator: `./gradlew installDebug`. Open the app,
confirm an "Ethernet / 有线网络" tile (🖧) appears in the main grid and is
tappable. Tap it and confirm the page opens showing:
- "未检测到以太网设备，请插入USB网卡 / No Ethernet device detected..." if
  no adapter is present, with Start disabled.
- If a USB-C-to-Ethernet adapter is plugged in and connected: status
  flips to "已连接 / Connected" (or the no-internet variant), and the
  info block shows IP/gateway/DNS populated, with Start becoming enabled.
  Unplugging the adapter should flip the status back to "not detected"
  and disable Start again, without needing to leave/reopen the page.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/ethernet/EthernetActivity.java \
        app/src/main/java/com/elotouch/devicetester/core/TestModule.java \
        app/src/main/AndroidManifest.xml
git commit -m "feat: add Ethernet module with live network info section"
```

---

### Task 3: Connectivity stress test (ping loop)

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/ethernet/EthernetActivity.java`

**Interfaces:**
- Consumes: `currentGateway`, `resultText`, `startButton`, `stopButton`,
  `updateStartButtonAvailability()` (Task 2); `BaseTestActivity.runAsync`,
  `ui`, `isStopped`, `stopTests` (existing base class).
- Produces: nothing consumed by later tasks — this is the last task.

- [ ] **Step 1: Replace the no-op stress-test methods with the real implementation**

In `EthernetActivity.java`, first add these imports alongside the existing ones:

```java
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

Add these fields next to the existing `TextView`/`Button` fields:

```java
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
```

Replace the two no-op methods (`startStressTest`/`stopStressTest`) at the
bottom of the class with:

```java
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
```

Update `updateStartButtonAvailability()` (from Task 2) so it never
re-enables Start while a test is actively running, and update
`onEthernetLost()` so a running test is flagged to stop itself:

```java
    private void updateStartButtonAvailability() {
        boolean canStart = currentGateway != null && !running;
        startButton.setEnabled(canStart);
        if (currentGateway == null && !running) {
            resultText.setText("等待检测到以太网网关… / Waiting for an Ethernet gateway…");
        }
    }
```

```java
    private void onEthernetLost() {
        currentGateway = null;
        ethernetLost = true;
        statusText.setText(NOT_DETECTED_MESSAGE);
        detailsText.setText("");
        updateStartButtonAvailability();
    }
```

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual verification**

Install on a device with a connected USB-C-to-Ethernet adapter:
`./gradlew installDebug`. Open the Ethernet module page.
- Press Start: Start disables, Stop enables, result text begins updating
  roughly once per second with increasing "Sent" count, 0% loss (assuming
  a live gateway), and a latency range.
- Let it run 30+ seconds, confirm counters keep incrementing and the
  elapsed time (hh:mm:ss) advances.
- Press Stop: the loop stops within ~1 second, Stop disables, Start
  re-enables (since the gateway is still present), and the last-seen
  stats remain visible on screen.
- Unplug the adapter while the stress test is running: confirm it stops
  itself within ~1 second (no crash) and shows "设备已断开 / Device
  disconnected"; Start stays disabled until an adapter reappears.
- Leave the page (press back) and confirm no crash and no lingering
  ping subprocess (check `adb shell ps | grep ping` returns nothing a
  couple seconds after leaving).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/ethernet/EthernetActivity.java
git commit -m "feat: add ping-based connectivity stress test to Ethernet module"
```
