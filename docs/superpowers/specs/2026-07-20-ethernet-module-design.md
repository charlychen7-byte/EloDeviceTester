# Ethernet Module Design

Date: 2026-07-20

## Purpose

Add a new "Ethernet" hardware module to EloDeviceTester, following the
existing two-level navigation and module conventions documented in
`EloDeviceTester/README.md` and `CLAUDE.md`. The module shows basic wired
network information and provides a connectivity stress test, for devices
that have (or can have, via USB-C adapter) an Ethernet connection.

## Background / constraint specific to Ethernet

Unlike other hardware modules (camera, NFC, sensors), most Android devices
have no built-in Ethernet interface. Ethernet connectivity typically only
appears when a USB-C-to-Ethernet adapter is plugged in — i.e. the hardware
is hot-pluggable and its presence can change *while the app is running*,
not just at app-launch time like other modules' static hardware checks.

## Decisions (from brainstorming Q&A)

1. **Network info fields**: IP address / subnet mask / gateway / DNS,
   link speed & duplex mode, connection status (connected / no adapter /
   connected-no-internet). MAC address is explicitly **out of scope**.
2. **Stress test type**: continuous ping loss-rate/latency test only
   (no throughput/bandwidth test — avoids needing an external speed-test
   server and keeps the test self-contained).
3. **Ping target**: auto-detected gateway IP from the active Ethernet
   network's `LinkProperties` (not a fixed public DNS, not user-entered).
4. **Level-1 grid hardware gating**: the Ethernet module tile is **always
   enabled/clickable** in the main grid (not greyed out by a static
   `HardwareDetector` check), because the adapter can be plugged in after
   the grid renders. Real-time detection happens inside the module's own
   page instead.
5. **Link speed/duplex retrieval**: best-effort only. Try reflection on
   `android.net.EthernetManager` first, fall back to reading
   `/sys/class/net/<iface>/speed` and `/duplex`; if both fail, display
   "不可获取 / Not available" — never error or crash on this field.

## Architecture

- New package `modules/ethernet/` with `EthernetActivity.java`, extending
  `core.BaseTestActivity`, matching the bilingual EN/中文 label convention
  used by `modules/wifibt/WifiBtActivity.java` and the Start/Stop pattern
  used by `modules/ddr/NativeToolTestActivity.java`.
- `core/HardwareDetector.java` gains a new helper method,
  `hasEthernetTransport(Context)`, checking
  `ConnectivityManager.getAllNetworks()` / `getNetworkCapabilities()` for
  `NetworkCapabilities.TRANSPORT_ETHERNET`. This is used only *inside*
  `EthernetActivity` for its own real-time UI state — **not** added as a
  new `HardwareDetector.Feature` enum value, and **not** used to gate the
  Level-1 grid cell (per decision 4).
- `core/TestModule.java` gains one new enum entry:
  `ETHERNET("Ethernet / 有线网络", "🔌", EthernetActivity.class, ...)`.
  Since the tile is always enabled, the module's `Feature` argument is
  `HardwareDetector.Feature.ALWAYS` (matching how other always-available
  modules are declared).
- `AndroidManifest.xml` gets one new `<activity android:name=".modules.ethernet.EthernetActivity" />`
  entry. No new permissions — `ACCESS_NETWORK_STATE` and `INTERNET` are
  already declared.

## Level-2 page layout

Single page, two sections.

### Section 1 — "网络信息 / Network Info"

- A `ConnectivityManager.NetworkCallback` is registered against a
  `NetworkRequest.Builder().addTransportType(TRANSPORT_ETHERNET).build()`
  request when the page's UI is built, and unregistered in the
  `onStopTests()` override. Its `onAvailable` / `onLost` /
  `onCapabilitiesChanged` callbacks drive all UI updates in this section
  — no manual refresh button, no polling loop.
- Status line, one of: "已连接 / Connected", "已连接（无互联网）/
  Connected (no internet)" (via `NET_CAPABILITY_VALIDATED`), "未检测到以太网设备，请插入USB网卡
  / No Ethernet device detected, please plug in a USB adapter".
- Info block (`addInfo` TextView): IPv4/IPv6 address + prefix length,
  gateway, DNS servers — read from the active Ethernet `Network`'s
  `LinkProperties`.
- Link speed / duplex mode: reflection on `EthernetManager` first, then
  `/sys/class/net/<iface>/speed` + `/duplex` fallback (interface name from
  `LinkProperties.getInterfaceName()`). Displays "不可获取 / Not available"
  if both attempts fail.

### Section 2 — "连接性压力测试 / Connectivity Stress Test"

- Result `addInfo` TextView: target gateway IP, packets sent, packets
  lost, loss %, min/avg/max latency (ms), elapsed time.
- Start / Stop buttons (Stop disabled until Start is pressed), following
  the `NativeToolTestActivity` enable/disable pattern.
- Start is disabled (with an explanatory label) whenever there is no
  Ethernet gateway currently resolvable — i.e. no adapter, or no gateway
  in `LinkProperties`.
- Ping mechanism: **not** `InetAddress.isReachable()` — on Android that
  call falls back to a TCP connect on port 7 (echo) when ICMP raw sockets
  aren't permitted, which returns false "unreachable" on most real
  gateways even when the link is fine. Instead, shell out directly via
  `ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", gatewayIp)`
  (blocking call, run off the UI thread inside the stress-test loop —
  this is a plain `ProcessBuilder` invocation of the system `ping`
  binary, distinct from `core.NativeProcessRunner`, which is scoped to
  bundled `jniLibs` executables like memtester/QMESA and doesn't apply
  here). Parse stdout for `"1 packets transmitted, 1 received"` vs `"0
  received"` and the `time=` value (ms) when present.
- On Start: `runAsync` loop —
  `while (!isStopped() && ethernetStillPresent) { run one ping subprocess
  per decision above; update sent/lost/latency counters; ui(() -> update
  result TextView); sleep until ~1000ms since loop start (subtracting the
  ping's own round-trip time already spent); }`.
- If the `NetworkCallback` fires `onLost` while a stress test is running,
  a flag is set that the loop checks each iteration; the loop exits on
  its own, the result text shows "设备已断开 / Device disconnected", and
  Start is re-enabled once (if) an Ethernet network reappears.
- `onStopTests()` sets the stop flag (via the existing `BaseTestActivity`
  mechanism) so leaving the page cleanly ends any running loop — no
  lingering sockets.

## Error handling summary

| Condition | Behavior |
|---|---|
| No Ethernet adapter ever plugged in | Info section shows "no device detected" message; Start disabled |
| Adapter unplugged mid-test | Loop self-terminates via callback flag; "device disconnected" message; Start re-enabled when device returns |
| Link speed/duplex unreadable | Field shows "不可获取 / Not available", never throws |
| No gateway resolvable | Start stays disabled with explanatory label, no guaranteed-fail loop |

## Out of scope (explicitly excluded)

- MAC address / interface name display to the user.
- Bandwidth/throughput stress test (would require an external speed-test
  endpoint).
- User-configurable ping target.
- Any change to `HardwareDetector.Feature` enum or Level-1 grid gating
  logic for Ethernet (module tile is always clickable).

## Testing

No automated test suite exists in this project (per `CLAUDE.md`). Manual
verification plan: build+install on a device with a USB-C Ethernet
adapter, verify info section populates and updates on plug/unplug, run
the stress test for at least 30s and confirm counters update and Stop
works, then verify the page also opens cleanly on a device with no
adapter at all (no crash, correct "not detected" messaging).
