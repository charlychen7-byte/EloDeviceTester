# EloDeviceTester PRD Expansion — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Android app in `EloDeviceTester/` up to date with the expanded PRD (`EloDeviceTester PRD.docx`, §3.13–§3.18 new modules + enhancements to §3.6/§3.7/§3.8/§3.9), adding 6 new hardware/system test modules and deeper test items in 4 existing modules, without breaking any existing module.

**Architecture:** Every module follows the existing two-layer pattern: a `TestModule` enum entry (title/icon/Activity/`HardwareDetector.Feature`) drives the level-1 grid, and each module's Activity extends `core.BaseTestActivity` (or `modules.display.ImmersiveActivity` for fullscreen tests). New pluggable-peripheral modules (Barcode, MSR, Serial I/O, Display Ext, USB/OTG) use `HardwareDetector.Feature.ALWAYS` and stay clickable on the grid; they detect their peripheral live inside the module page per PRD §5.3's "外设动态检测模型". Only Cellular gets a new real `HardwareDetector.Feature.CELLULAR` grid-level check, since telephony hardware presence is a fixed device-SKU property like camera/NFC/GPS.

**Tech Stack:** Java 17, Android minSdk 26 / target-compileSdk 34, CameraX 1.3.4 (already present), plus one new open-source dependency: `com.github.mik3y:usb-serial-for-android:3.7.0` (via JitPack) for the USB-serial peripheral modules (§3.15/§3.14), per the PRD §5.1 "第三方依赖" note.

## Global Constraints

- Language: Java only (no Kotlin), per CLAUDE.md / PRD §5.1.
- minSdk 26, targetSdk/compileSdk 34 — guard any API added later than 26 with `Build.VERSION.SDK_INT` checks, matching the existing pattern in `WifiBtActivity`/`NfcActivity`/`AudioActivity`.
- UI is built programmatically via `BaseTestActivity`'s `add*` helpers — no new layout XML files, module icons are emoji (PRD/README convention).
- No blocking work on the UI thread — long-running work goes through `runAsync`/`isStopped` (PRD §5.2).
- Exclusive hardware (USB serial ports, camera, secondary-display `Presentation`, telephony listeners, broadcast receivers) must be released in `onStopTests()` (PRD §4).
- Lazy, per-module permission requests via `requirePermission`/`requirePermissions` (PRD §5.4) — never request a permission until the user is on the page that needs it.
- **Two detection models (PRD §5.3):** built-in hardware (Cellular) is probed via `HardwareDetector` before the grid renders and greys out the whole module tile if absent. Pluggable peripherals (Barcode/MSR/Serial I/O/Display Ext/USB-OTG) always show as clickable tiles; the module page itself detects the peripheral on `onResume`/button-press and shows "未检测到外设" inline if nothing is attached.
- **No automated test suite exists in this project** (confirmed in `README.md`: "No automated tests yet"). Every task's verification step is therefore: (1) `./gradlew assembleDebug` compiles clean, and (2) a manual on-device/emulator check described in the task. This mirrors how every existing module in this codebase was verified — do not introduce a test framework as part of this plan (YAGNI; out of scope).
- Keep all existing PRD section numbers/behavior untouched — this plan only adds code, it does not refactor working modules.
- Commit after each task using the existing repo (`EloDeviceTester/.git`), from within the `EloDeviceTester/` directory.
- **PRD §2.1 "网格滚动" needs no code change.** `MainActivity`'s grid is already a `RecyclerView` with `GridLayoutManager` inside a weighted `LinearLayout` — it already scrolls vertically once the 18 modules overflow the screen. This plan verifies that behavior in Task 17 rather than adding a task for it.

---

## Task Overview

| # | Task | Files touched |
|---|------|----------------|
| 1 | Add `usb-serial-for-android` dependency | `settings.gradle`, `app/build.gradle` |
| 2 | `UsbSerialHelper` core utility + USB host feature | `core/UsbSerialHelper.java` (new), `AndroidManifest.xml` |
| 3 | `HardwareDetector` Cellular feature | `core/HardwareDetector.java` |
| 4 | New module: Barcode/QR (§3.13) | `modules/barcode/BarcodeActivity.java` (new), `core/TestModule.java`, `AndroidManifest.xml` |
| 5 | New module: MSR (§3.14) | `modules/msr/MsrActivity.java` (new), `core/TestModule.java`, `AndroidManifest.xml` |
| 6 | New module: Serial I/O (§3.15) | `modules/serial/SerialActivity.java` (new), `core/TestModule.java`, `AndroidManifest.xml` |
| 7 | New module: Display Ext (§3.16) | `modules/displayext/DisplayExtActivity.java` (new), `core/TestModule.java`, `AndroidManifest.xml` |
| 8 | New module: Cellular (§3.17) | `modules/cellular/CellularActivity.java` (new), `core/TestModule.java`, `AndroidManifest.xml` |
| 9 | New module: USB/OTG (§3.18) | `modules/usbotg/UsbOtgActivity.java` (new), `core/TestModule.java`, `AndroidManifest.xml` |
| 10 | Display enhancements (color accuracy / Mura / touch alignment) | 3 new Activities under `modules/display/`, `DisplayActivity.java`, `AndroidManifest.xml` |
| 11 | Touch enhancement (sampling rate + pressure) | `modules/touch/TouchSamplingActivity.java` (new), `TouchActivity.java`, `AndroidManifest.xml` |
| 12 | Audio enhancement — latency | `modules/audio/AudioActivity.java` |
| 13 | Audio enhancement — sample rate & channel verification | `modules/audio/AudioActivity.java` |
| 14 | Camera enhancement — resolution & focus speed | `modules/camera/CameraActivity.java` |
| 15 | Camera enhancement — multi-camera switching | `modules/camera/CameraActivity.java` |
| 16 | Update `README.md` | `README.md` |
| 17 | Full project build + manual smoke test pass | none (verification only) |

---

### Task 1: Add the `usb-serial-for-android` dependency

**Files:**
- Modify: `settings.gradle`
- Modify: `app/build.gradle`

**Interfaces:**
- Produces: the Gradle dependency `com.github.mik3y:usb-serial-for-android:3.7.0`, whose classes (`com.hoho.android.usbserial.driver.*`) Task 2's `UsbSerialHelper` consumes.

- [ ] **Step 1: Add the JitPack repository**

`settings.gradle` uses `dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) ... }`, so the repo must be declared here, not in `app/build.gradle`. Edit `settings.gradle`:

```gradle
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
rootProject.name = "EloDeviceTester"
include ':app'
```

- [ ] **Step 2: Add the dependency**

Edit `app/build.gradle`, in the `dependencies { ... }` block, after the CameraX lines:

```gradle
    // USB-serial support for peripheral modules (PRD §3.14/§3.15), no vendor SDK
    implementation 'com.github.mik3y:usb-serial-for-android:3.7.0'
```

- [ ] **Step 3: Verify it resolves**

Run (from `EloDeviceTester/`): `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL` (this only pulls the dependency; nothing references it yet).

- [ ] **Step 4: Commit**

```bash
git add settings.gradle app/build.gradle
git commit -m "build: add usb-serial-for-android dependency for peripheral modules"
```

---

### Task 2: `UsbSerialHelper` core utility

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/core/UsbSerialHelper.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `com.hoho.android.usbserial.driver.{UsbSerialDriver, UsbSerialPort, UsbSerialProber}` (Task 1's dependency).
- Produces (used by Tasks 5 and 6):
  - `static List<UsbSerialDriver> UsbSerialHelper.listDrivers(Context context)`
  - `static void UsbSerialHelper.requestPermission(Context context, UsbDevice device, UsbSerialHelper.PermissionCallback callback)` where `PermissionCallback` has `void onResult(boolean granted)`
  - `static UsbSerialPort UsbSerialHelper.open(Context context, UsbSerialDriver driver, int baudRate) throws IOException`

- [ ] **Step 1: Create the helper**

```java
package com.elotouch.devicetester.core;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;

/**
 * Shared helper for USB-serial peripherals (PRD §3.14/§3.15): enumerates
 * attached USB-serial adapters via the open-source usb-serial-for-android
 * library and handles the per-device runtime permission dance, so serial-
 * based modules don't duplicate this plumbing. Not tied to any vendor SDK.
 */
public final class UsbSerialHelper {

    private static final String ACTION_USB_PERMISSION =
            "com.elotouch.devicetester.USB_PERMISSION";

    private UsbSerialHelper() {}

    public interface PermissionCallback {
        void onResult(boolean granted);
    }

    public static List<UsbSerialDriver> listDrivers(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager);
    }

    public static void requestPermission(Context context, UsbDevice device, PermissionCallback callback) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager.hasPermission(device)) {
            callback.onResult(true);
            return;
        }
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_USB_PERMISSION), flags);

        BroadcastReceiver[] holder = new BroadcastReceiver[1];
        holder[0] = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                try {
                    context.unregisterReceiver(holder[0]);
                } catch (IllegalArgumentException ignored) { }
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                callback.onResult(granted);
            }
        };
        context.registerReceiver(holder[0], new IntentFilter(ACTION_USB_PERMISSION));
        manager.requestPermission(device, pi);
    }

    public static UsbSerialPort open(Context context, UsbSerialDriver driver, int baudRate) throws IOException {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            throw new IOException("Cannot open USB device connection 无法打开 USB 连接");
        }
        UsbSerialPort port = driver.getPorts().get(0);
        port.open(connection);
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        return port;
    }
}
```

- [ ] **Step 2: Declare optional USB host feature**

In `AndroidManifest.xml`, add next to the other `<uses-feature>` declarations:

```xml
    <uses-feature android:name="android.hardware.usb.host" android:required="false" />
```

- [ ] **Step 3: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/core/UsbSerialHelper.java app/src/main/AndroidManifest.xml
git commit -m "feat: add UsbSerialHelper for USB-serial peripheral modules"
```

---

### Task 3: `HardwareDetector` Cellular feature

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/core/HardwareDetector.java`

**Interfaces:**
- Produces (used by Task 8): `HardwareDetector.Feature.CELLULAR` and `HardwareDetector.isAvailable(context, Feature.CELLULAR)`.

- [ ] **Step 1: Add the enum value and its check**

In `HardwareDetector.java`, change:

```java
    public enum Feature {
        ALWAYS, TOUCH, VIBRATOR, CAMERA, GPS, NFC, WIFI, SENSORS
    }
```

to:

```java
    public enum Feature {
        ALWAYS, TOUCH, VIBRATOR, CAMERA, GPS, NFC, WIFI, SENSORS, CELLULAR
    }
```

and in the `switch (f)` inside `isAvailable`, add a case before `default`:

```java
            case CELLULAR:
                return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
```

- [ ] **Step 2: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/core/HardwareDetector.java
git commit -m "feat: add CELLULAR hardware feature check"
```

---

### Task 4: New module — Barcode/QR (§3.13)

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/barcode/BarcodeActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/core/TestModule.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `BaseTestActivity` helpers (`addSectionTitle`, `addInfo`, `addButton`, `addView`).
- Produces: `TestModule.BARCODE` enum entry consumed by `ModuleAdapter`/`MainActivity` (no new interface beyond that).

- [ ] **Step 1: Create the Activity**

```java
package com.elotouch.devicetester.modules.barcode;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;
import java.util.Map;

/**
 * Barcode/QR scanner module (PRD §3.13). Dedicated scan heads on Android
 * almost always act as a keyboard-wedge HID device (they "type" the decoded
 * string followed by Enter/Tab) rather than exposing a scan API, so capture
 * works the same way for a built-in head, an external USB scanner, or a
 * Bluetooth scanner in HID mode. A connected USB scanner is additionally
 * reported if present, but the capture box works regardless (pluggable-
 * peripheral detection model, PRD §5.3).
 */
public class BarcodeActivity extends BaseTestActivity {

    private TextView connectionText;
    private TextView resultText;
    private EditText scanInput;
    private long focusTime;
    private int successCount = 0;

    @Override
    protected String title() {
        return "Barcode/QR 条码扫描测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Scanner Connection / 扫描头连接检测");
        connectionText = addInfo("");
        addButton("Refresh / 刷新检测", this::refreshConnection);

        addSectionTitle("Scan Test / 扫码功能测试");
        addInfo("Tap the box below, then trigger the scanner (hardware key or trigger) at a "
                + "test barcode/QR code.\n点击下方输入框后，用扫描枪/扫描键对准测试条码或二维码触发扫描。");
        scanInput = new EditText(this);
        scanInput.setHint("Scan here 在此扫描…");
        scanInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) focusTime = System.nanoTime();
        });
        addView(scanInput);
        resultText = addInfo("Success count 成功次数：0");
        scanInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                String raw = s.toString();
                if (raw.endsWith("\n") || raw.endsWith("\t")) {
                    long ms = (System.nanoTime() - focusTime) / 1_000_000;
                    successCount++;
                    resultText.setText(String.format(Locale.US,
                            "Success count 成功次数：%d\nLast decoded 最近解码内容：%s\n"
                                    + "Elapsed since focus 耗时：%d ms\n"
                                    + "(Symbology 码制类型 unavailable — wedge scanners only transmit "
                                    + "decoded text. 码制类型不可读：Wedge 模式仅传输解码后的文本)",
                            successCount, raw.trim(), ms));
                    scanInput.setText("");
                    focusTime = System.nanoTime();
                }
            }
        });

        refreshConnection();
    }

    private void refreshConnection() {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        Map<String, UsbDevice> devices = um.getDeviceList();
        connectionText.setText(devices.isEmpty()
                ? "No external USB scanner detected; a built-in head or Bluetooth HID scanner will "
                        + "still work through the capture box below. 未检测到外接 USB 扫描设备；内置扫描头"
                        + "或蓝牙 HID 扫描枪仍可通过下方输入框正常使用。"
                : devices.size() + " USB device(s) present 检测到 USB 设备：" + devices.size());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConnection();
    }
}
```

- [ ] **Step 2: Register in `TestModule`**

In `TestModule.java`, add the import:

```java
import com.elotouch.devicetester.modules.barcode.BarcodeActivity;
```

and add the enum constant as the new last entry:

```java
    BARCODE("Barcode/QR / 条码扫描", "🔍", BarcodeActivity.class, HardwareDetector.Feature.ALWAYS);
```

Note: `TestModule`'s enum constant list currently ends with `SENSORS(...);` (trailing semicolon, since it's the last entry). Change that line's trailing `;` to `,` now that `BARCODE` follows it, and give `BARCODE` the trailing `;` instead, since it is the new last entry.

- [ ] **Step 3: Register in the manifest**

In `AndroidManifest.xml`, add inside `<application>`, after the `BatteryActivity` entry:

```xml
        <activity android:name=".modules.barcode.BarcodeActivity" />
```

- [ ] **Step 4: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Install (`./gradlew installDebug`) on a device/emulator. Open the app, confirm a new "🔍 Barcode/QR / 条码扫描" tile appears on the grid and is clickable. Enter the module, tap the scan box, type text ending with Enter (simulating a wedge scan) — confirm the result section updates with the decoded text and elapsed time.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/barcode/BarcodeActivity.java app/src/main/java/com/elotouch/devicetester/core/TestModule.java app/src/main/AndroidManifest.xml
git commit -m "feat: add Barcode/QR scanner module (PRD §3.13)"
```

---

### Task 5: New module — MSR (§3.14)

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/msr/MsrActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/core/TestModule.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `UsbSerialHelper.{listDrivers, requestPermission, open}` (Task 2).
- Produces: `TestModule.MSR` enum entry.

- [ ] **Step 1: Create the Activity**

```java
package com.elotouch.devicetester.modules.msr;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.elotouch.devicetester.core.UsbSerialHelper;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.util.List;
import java.util.Locale;

/**
 * MSR / IC-card reader module (PRD §3.14): distinct from the phone's own NFC
 * chip (§3.3) — this targets an external payment-terminal card reader.
 * Magstripe swipes are captured via keyboard-wedge input (the common HID mode
 * for cheap swipe readers); IC/contactless reads listen on a USB-serial port
 * when one is attached and show the reader's raw response bytes (not a parsed
 * ATR — the exact protocol is reader-specific and out of scope here).
 */
public class MsrActivity extends BaseTestActivity {

    private TextView connectionText;
    private TextView swipeText;
    private TextView icText;
    private EditText wedgeInput;

    @Override
    protected String title() {
        return "MSR 刷卡器测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Reader Connection / 刷卡器连接检测");
        connectionText = addInfo("");
        addButton("Refresh / 刷新检测", this::refreshConnection);

        addSectionTitle("Magstripe Swipe / 磁条卡刷卡测试");
        addInfo("Tap the box below, then swipe a test card on a keyboard-wedge reader.\n"
                + "点击下方输入框后，用磁条卡在键盘模拟（Wedge）读卡器上刷卡。");
        wedgeInput = new EditText(this);
        wedgeInput.setHint("Swipe here 在此刷卡…");
        addView(wedgeInput);
        swipeText = addInfo("Waiting for swipe. 等待刷卡…");
        wedgeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                String raw = s.toString();
                if (raw.endsWith("\n") || raw.endsWith("\r")) {
                    swipeText.setText("Track data (masked) 磁道数据（脱敏）：\n" + mask(raw.trim()));
                    wedgeInput.setText("");
                }
            }
        });

        addSectionTitle("IC Card / IC 卡插拔测试");
        icText = addInfo("Tap below, then insert the test IC card. 点击下方按钮后插入测试 IC 卡。");
        addButton("Start IC Detection / 开始 IC 检测", () -> listenSerial("IC"));

        addSectionTitle("Contactless / 非接触刷卡测试");
        addButton("Start Contactless Detection / 开始非接触检测", () -> listenSerial("Contactless"));

        refreshConnection();
    }

    private void refreshConnection() {
        List<UsbSerialDriver> drivers = UsbSerialHelper.listDrivers(this);
        connectionText.setText(drivers.isEmpty()
                ? "No external MSR/IC reader detected (swipe test still works via keyboard-wedge). "
                        + "未检测到外接读卡器（磁条刷卡测试仍可通过键盘模拟方式使用）。"
                : drivers.size() + " reader(s) detected 检测到读卡器：" + drivers.get(0).getDevice().getDeviceName());
    }

    private void listenSerial(String mode) {
        List<UsbSerialDriver> drivers = UsbSerialHelper.listDrivers(this);
        if (drivers.isEmpty()) {
            icText.setText("No external reader detected. 未检测到外接读卡器。");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        icText.setText("Waiting for card (" + mode + ")… 等待读卡（" + mode + "）…");
        UsbSerialHelper.requestPermission(this, driver.getDevice(), granted -> ui(() -> {
            if (!granted) {
                icText.setText("Permission denied. 权限受限。");
                return;
            }
            runAsync(() -> {
                UsbSerialPort port = null;
                try {
                    port = UsbSerialHelper.open(this, driver, 9600);
                    byte[] buffer = new byte[256];
                    int read = port.read(buffer, 5000);
                    String hex = toHex(buffer, read);
                    boolean got = read > 0;
                    ui(() -> icText.setText(got
                            ? "Reader response 读卡器响应字节：\n" + hex
                            : "No response within 5s. 5 秒内无响应。"));
                } catch (Exception e) {
                    ui(() -> icText.setText("Read failed 读取失败：" + e.getMessage()));
                } finally {
                    if (port != null) { try { port.close(); } catch (Exception ignored) { } }
                }
            });
        }));
    }

    private static String mask(String raw) {
        if (raw.length() <= 8) return raw;
        return raw.substring(0, 4) + "…(" + (raw.length() - 8) + " chars hidden)…" + raw.substring(raw.length() - 4);
    }

    private static String toHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(String.format(Locale.US, "%02X ", bytes[i]));
        return sb.toString().trim();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConnection();
    }
}
```

- [ ] **Step 2: Register in `TestModule`**

Add import `com.elotouch.devicetester.modules.msr.MsrActivity;`. Change the `BARCODE` line's trailing `;` to `,` (it's no longer the last entry), then add `MSR` as the new last entry:

```java
    BARCODE("Barcode/QR / 条码扫描", "🔍", BarcodeActivity.class, HardwareDetector.Feature.ALWAYS),
    MSR("MSR / 刷卡器", "💳", MsrActivity.class, HardwareDetector.Feature.ALWAYS);
```

- [ ] **Step 3: Register in the manifest**

```xml
        <activity android:name=".modules.msr.MsrActivity" />
```

- [ ] **Step 4: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Install and open the "💳 MSR / 刷卡器" tile. Confirm the connection status text renders. Focus the swipe box, type text ending with Enter — confirm masked track data appears. Without any USB reader attached, tapping "Start IC Detection" should show "未检测到外接读卡器" rather than crashing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/msr/MsrActivity.java app/src/main/java/com/elotouch/devicetester/core/TestModule.java app/src/main/AndroidManifest.xml
git commit -m "feat: add MSR card reader module (PRD §3.14)"
```

---

### Task 6: New module — Serial I/O (§3.15)

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/serial/SerialActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/core/TestModule.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `UsbSerialHelper.{listDrivers, requestPermission, open}` (Task 2).
- Produces: `TestModule.SERIAL_IO` enum entry.

- [ ] **Step 1: Create the Activity**

```java
package com.elotouch.devicetester.modules.serial;

import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.elotouch.devicetester.core.UsbSerialHelper;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serial I/O peripherals module (PRD §3.15): USB-serial cash drawer / receipt
 * printer / generic serial connectivity, implemented on the standard Android
 * UsbManager + the open-source usb-serial-for-android library (no vendor
 * SDK). The module entry stays clickable even with nothing attached;
 * availability is detected live on this page per the pluggable-peripheral
 * model (PRD §5.3).
 */
public class SerialActivity extends BaseTestActivity {

    private static final byte[] CASH_DRAWER_KICK = {0x1B, 0x70, 0x00, 0x19, (byte) 0xFA};
    private static final byte[] PRINTER_INIT = {0x1B, 0x40};
    private static final byte[] PRINTER_CUT = {0x1D, 0x56, 0x42, 0x00};

    private TextView devicesText;
    private TextView ioText;
    private UsbSerialPort openPort;

    @Override
    protected String title() {
        return "Serial I/O 序口外设测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Detected Devices / 检测到的外设");
        addInfo("Uses UsbManager + usb-serial-for-android; not tied to any vendor SDK.\n"
                + "基于标准 UsbManager 与开源 usb-serial-for-android 库，不依赖厂商私有 SDK。");
        devicesText = addInfo("");
        addButton("Refresh / 刷新外设列表", this::refreshDevices);
        addButton("Connect First Device / 连接第一个检测到的外设", this::connectFirst);

        addSectionTitle("Serial Loopback / 串口收发测试");
        ioText = addInfo("Not connected. 尚未连接。");
        addButton("Send Test Bytes / 发送测试字节", this::sendTestBytes);

        addSectionTitle("Cash Drawer / 钱箱触发");
        addButton("Open Cash Drawer / 打开钱箱", this::kickDrawer);

        addSectionTitle("Receipt Printer / 小票打印机");
        addButton("Print Test Receipt / 打印测试小票", this::printTestReceipt);

        refreshDevices();
    }

    private void refreshDevices() {
        List<UsbSerialDriver> drivers = UsbSerialHelper.listDrivers(this);
        if (drivers.isEmpty()) {
            devicesText.setText("No serial peripherals detected. 未检测到外设。");
        } else {
            StringBuilder sb = new StringBuilder(drivers.size() + " device(s) found 检测到设备：\n");
            for (UsbSerialDriver d : drivers) {
                sb.append("  · ").append(d.getDevice().getDeviceName())
                        .append("  (vid=").append(d.getDevice().getVendorId())
                        .append(" pid=").append(d.getDevice().getProductId()).append(")\n");
            }
            devicesText.setText(sb.toString());
        }
    }

    private void connectFirst() {
        List<UsbSerialDriver> drivers = UsbSerialHelper.listDrivers(this);
        if (drivers.isEmpty()) {
            ioText.setText("No serial peripherals detected. 未检测到外设，无法连接。");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        UsbSerialHelper.requestPermission(this, driver.getDevice(), granted -> ui(() -> {
            if (!granted) {
                ioText.setText("Permission denied. 权限受限：用户拒绝了 USB 访问授权。");
                return;
            }
            runAsync(() -> {
                try {
                    closeQuietly();
                    openPort = UsbSerialHelper.open(this, driver, 9600);
                    ui(() -> ioText.setText("Connected 已连接：" + driver.getDevice().getDeviceName()));
                } catch (Exception e) {
                    ui(() -> ioText.setText("Connect failed 连接失败：" + e.getMessage()));
                }
            });
        }));
    }

    private void sendTestBytes() {
        if (openPort == null) {
            ioText.setText("Connect a device first. 请先连接外设。");
            return;
        }
        runAsync(() -> {
            try {
                byte[] payload = "ELO-TEST\n".getBytes(StandardCharsets.US_ASCII);
                openPort.write(payload, 1000);
                byte[] buffer = new byte[256];
                int read = openPort.read(buffer, 2000);
                String received = read > 0
                        ? new String(buffer, 0, read, StandardCharsets.US_ASCII)
                        : "(no reply within 2s 2 秒内无回应，若无自环/应答外设属正常现象)";
                ui(() -> ioText.setText("Sent 已发送 ELO-TEST\\n\nReceived 收到：" + received));
            } catch (Exception e) {
                ui(() -> ioText.setText("I/O error 通信出错：" + e.getMessage()));
            }
        });
    }

    private void kickDrawer() {
        if (openPort == null) {
            ioText.setText("Connect a device first. 请先连接外设。");
            return;
        }
        runAsync(() -> {
            try {
                openPort.write(CASH_DRAWER_KICK, 1000);
                ui(() -> ioText.setText("Kick command sent; confirm the drawer opened.\n"
                        + "已发送开钱箱指令，请确认钱箱是否弹开。"));
            } catch (Exception e) {
                ui(() -> ioText.setText("Send failed 发送失败：" + e.getMessage()));
            }
        });
    }

    private void printTestReceipt() {
        if (openPort == null) {
            ioText.setText("Connect a device first. 请先连接外设。");
            return;
        }
        runAsync(() -> {
            try {
                openPort.write(PRINTER_INIT, 1000);
                openPort.write("Elo DeviceTester\nTest Receipt 测试小票\n\n\n"
                        .getBytes(StandardCharsets.US_ASCII), 1000);
                try {
                    openPort.write(PRINTER_CUT, 1000);
                } catch (Exception ignored) {
                    // Cutter not supported by every printer; non-fatal.
                }
                ui(() -> ioText.setText("Print job sent. 打印任务已发送，请检查走纸与打印质量。"));
            } catch (Exception e) {
                ui(() -> ioText.setText("Print failed 打印失败：" + e.getMessage()));
            }
        });
    }

    private void closeQuietly() {
        if (openPort != null) {
            try { openPort.close(); } catch (Exception ignored) { }
            openPort = null;
        }
    }

    @Override
    protected void onStopTests() {
        closeQuietly();
    }
}
```

- [ ] **Step 2: Register in `TestModule`**

Add import `com.elotouch.devicetester.modules.serial.SerialActivity;`. Change the `MSR` line's trailing `;` to `,`, then add `SERIAL_IO` as the new last entry:

```java
    MSR("MSR / 刷卡器", "💳", MsrActivity.class, HardwareDetector.Feature.ALWAYS),
    SERIAL_IO("Serial I/O / 序口外设", "🖨️", SerialActivity.class, HardwareDetector.Feature.ALWAYS);
```

- [ ] **Step 3: Register in the manifest**

```xml
        <activity android:name=".modules.serial.SerialActivity" />
```

- [ ] **Step 4: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Install and open "🖨️ Serial I/O / 序口外设". With no USB-serial device attached, confirm "未检测到外设" shows and connect/send/drawer/print buttons fail gracefully with a "请先连接外设" message rather than crashing. If a USB-serial adapter is available, connect it, confirm the permission dialog appears, and verify send/receive.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/serial/SerialActivity.java app/src/main/java/com/elotouch/devicetester/core/TestModule.java app/src/main/AndroidManifest.xml
git commit -m "feat: add Serial I/O peripherals module (PRD §3.15)"
```

---

### Task 7: New module — Display Ext (§3.16)

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/displayext/DisplayExtActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/core/TestModule.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `TestModule.DISPLAY_EXT` enum entry.

- [ ] **Step 1: Create the Activity**

```java
package com.elotouch.devicetester.modules.displayext;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.net.InetAddress;
import java.util.Locale;

/**
 * Secondary display & wired network module (PRD §3.16). Both sub-features are
 * pluggable peripherals — a docked customer display or an Ethernet dongle —
 * so this page detects them live rather than the grid greying out (PRD §5.3).
 */
public class DisplayExtActivity extends BaseTestActivity {

    private TextView displayText;
    private TextView networkText;
    private PatternPresentation presentation;

    @Override
    protected String title() {
        return "Display Ext 双屏/网络测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Secondary Display / 客显副屏输出");
        displayText = addInfo("");
        addButton("Show Test Pattern / 推送测试图案到副屏", this::showPattern);
        addButton("Dismiss / 关闭副屏图案", this::dismissPattern);

        addSectionTitle("Wired Network / 有线网络");
        networkText = addInfo("");
        addButton("Ping Test / Ping 连通性测试", this::pingTest);

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays();
        int extra = 0;
        for (Display d : displays) if (d.getDisplayId() != Display.DEFAULT_DISPLAY) extra++;
        displayText.setText(extra > 0
                ? extra + " secondary display(s) found 检测到副屏：" + extra
                : "No secondary display detected. 未检测到副屏，请连接客显后重试。");

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        boolean ethernetUp = false;
        StringBuilder ips = new StringBuilder();
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                ethernetUp = true;
                LinkProperties lp = cm.getLinkProperties(net);
                if (lp != null) {
                    for (android.net.LinkAddress a : lp.getLinkAddresses()) {
                        ips.append(a.getAddress().getHostAddress()).append(' ');
                    }
                }
            }
        }
        boolean finalEthernetUp = ethernetUp;
        networkText.setText(finalEthernetUp
                ? "Ethernet connected 已连接：" + ips
                : "No Ethernet link detected. 未检测到以太网连接。");
    }

    private void showPattern() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays();
        for (Display d : displays) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                dismissPattern();
                presentation = new PatternPresentation(this, d);
                presentation.show();
                return;
            }
        }
        toast("No secondary display. 未检测到副屏。");
    }

    private void dismissPattern() {
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
    }

    private void pingTest() {
        networkText.setText("Pinging… Ping 中…");
        runAsync(() -> {
            try {
                long t0 = System.nanoTime();
                InetAddress addr = InetAddress.getByName("8.8.8.8");
                boolean reachable = addr.isReachable(3000);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String msg = reachable
                        ? String.format(Locale.US, "Ping 8.8.8.8 OK 成功：%d ms", ms)
                        : "Ping timeout (3s) 超时。";
                ui(() -> networkText.setText(msg));
            } catch (Exception e) {
                ui(() -> networkText.setText("Ping failed Ping 失败：" + e.getMessage()));
            }
        });
    }

    @Override
    protected void onStopTests() {
        dismissPattern();
    }

    private static class PatternPresentation extends Presentation {
        private static final int[] COLORS = {Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK};
        private int index = 0;

        PatternPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(COLORS[0]);
            TextView hint = new TextView(getContext());
            hint.setText("Elo DeviceTester — Secondary Display Test\n客显测试图案 · 点击切换颜色");
            hint.setTextColor(Color.DKGRAY);
            hint.setTextSize(16);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            root.addView(hint, lp);
            root.setOnClickListener(v -> {
                index = (index + 1) % COLORS.length;
                root.setBackgroundColor(COLORS[index]);
            });
            setContentView(root);
        }
    }
}
```

- [ ] **Step 2: Register in `TestModule`**

Add import `com.elotouch.devicetester.modules.displayext.DisplayExtActivity;`. Change the `SERIAL_IO` line's trailing `;` to `,`, then add `DISPLAY_EXT` as the new last entry:

```java
    SERIAL_IO("Serial I/O / 序口外设", "🖨️", SerialActivity.class, HardwareDetector.Feature.ALWAYS),
    DISPLAY_EXT("Display Ext / 双屏网络", "📺", DisplayExtActivity.class, HardwareDetector.Feature.ALWAYS);
```

- [ ] **Step 3: Register in the manifest**

```xml
        <activity android:name=".modules.displayext.DisplayExtActivity" />
```

- [ ] **Step 4: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Install and open "📺 Display Ext / 双屏网络" with no secondary display/Ethernet attached — confirm both sections show "未检测到". If a secondary display (e.g. via a USB-C dock or Cast) is available, confirm "Show Test Pattern" renders a colored screen on it and "Dismiss" closes it without crashing the main Activity.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/displayext/DisplayExtActivity.java app/src/main/java/com/elotouch/devicetester/core/TestModule.java app/src/main/AndroidManifest.xml
git commit -m "feat: add secondary display & Ethernet module (PRD §3.16)"
```

---

### Task 8: New module — Cellular (§3.17)

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/cellular/CellularActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/core/TestModule.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `HardwareDetector.Feature.CELLULAR` (Task 3).
- Produces: `TestModule.CELLULAR` enum entry.

- [ ] **Step 1: Create the Activity**

```java
package com.elotouch.devicetester.modules.cellular;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.net.InetAddress;
import java.util.Locale;

/**
 * Cellular / network connectivity module (PRD §3.17). Only shown on devices
 * with telephony hardware (HardwareDetector.Feature.CELLULAR); Wi-Fi-only
 * devices never see this module on the grid (built-in-hardware detection
 * model, PRD §5.3).
 */
@SuppressWarnings("deprecation")
public class CellularActivity extends BaseTestActivity {

    private TelephonyManager tm;
    private TextView simText;
    private TextView signalText;
    private TextView dataText;
    private boolean listening = false;

    private final PhoneStateListener listener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            ui(() -> signalText.setText("Signal 信号强度：level " + signalStrength.getLevel() + " / 4"));
        }
    };

    @Override
    protected String title() {
        return "Cellular 蜂窝网络测试";
    }

    @Override
    protected void buildUi() {
        tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        addSectionTitle("SIM & Carrier / SIM 卡与运营商");
        simText = addInfo("Reading… 读取中…");

        addSectionTitle("Signal Strength / 信号强度");
        signalText = addInfo("Not started. 尚未开始。");

        addSectionTitle("Mobile Data / 移动数据连通性");
        dataText = addInfo("Not started. 尚未开始。");
        addButton("Ping Test / Ping 连通性测试", this::pingTest);

        requirePermission(Manifest.permission.READ_PHONE_STATE, this::start,
                () -> simText.setText("Permission denied. 权限受限：未授予电话状态权限。"));
    }

    @SuppressLint("MissingPermission")
    private void start() {
        if (tm == null) return;
        String carrier = tm.getNetworkOperatorName();
        int simState = tm.getSimState();
        simText.setText("SIM state SIM 状态：" + simStateStr(simState)
                + "\nCarrier 运营商：" + (carrier == null || carrier.isEmpty() ? "N/A" : carrier)
                + "\nNetwork type 网络类型：" + networkTypeStr(tm.getNetworkType()));
        if (!listening) {
            tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
            listening = true;
        }
    }

    private void pingTest() {
        dataText.setText("Pinging… Ping 中…");
        runAsync(() -> {
            try {
                long t0 = System.nanoTime();
                InetAddress addr = InetAddress.getByName("8.8.8.8");
                boolean reachable = addr.isReachable(3000);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String msg = reachable
                        ? String.format(Locale.US, "Ping 8.8.8.8 OK 成功：%d ms", ms)
                        : "Ping timeout (3s) 超时，请检查移动数据是否开启。";
                ui(() -> dataText.setText(msg));
            } catch (Exception e) {
                ui(() -> dataText.setText("Ping failed Ping 失败：" + e.getMessage()));
            }
        });
    }

    @Override
    protected void onStopTests() {
        if (listening && tm != null) {
            tm.listen(listener, PhoneStateListener.LISTEN_NONE);
            listening = false;
        }
    }

    private static String simStateStr(int s) {
        switch (s) {
            case TelephonyManager.SIM_STATE_READY: return "Ready 就绪";
            case TelephonyManager.SIM_STATE_ABSENT: return "Absent 未插卡";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED: return "PIN required 需要 PIN";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED: return "PUK required 需要 PUK";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED: return "Network locked 网络锁定";
            default: return "Unknown 未知";
        }
    }

    private static String networkTypeStr(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_LTE: return "4G LTE";
            case TelephonyManager.NETWORK_TYPE_NR: return "5G NR";
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_UMTS: return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GPRS: return "2G";
            case TelephonyManager.NETWORK_TYPE_UNKNOWN: return "Unknown 未知";
            default: return "Type " + type;
        }
    }
}
```

- [ ] **Step 2: Register in `TestModule`**

Add import `com.elotouch.devicetester.modules.cellular.CellularActivity;`. Change the `DISPLAY_EXT` line's trailing `;` to `,`, then add `CELLULAR` as the new last entry:

```java
    DISPLAY_EXT("Display Ext / 双屏网络", "📺", DisplayExtActivity.class, HardwareDetector.Feature.ALWAYS),
    CELLULAR("Cellular / 蜂窝网络", "📡", CellularActivity.class, HardwareDetector.Feature.CELLULAR);
```

- [ ] **Step 3: Register in the manifest**

Add the permission near the other `<uses-permission>` entries:

```xml
    <!-- Cellular / Telephony -->
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
```

and the feature near the other `<uses-feature>` entries:

```xml
    <uses-feature android:name="android.hardware.telephony" android:required="false" />
```

and the Activity inside `<application>`:

```xml
        <activity android:name=".modules.cellular.CellularActivity" />
```

- [ ] **Step 4: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

On a cellular-capable device: confirm the "📡 Cellular / 蜂窝网络" tile is clickable, grant the phone-state permission, and confirm SIM/carrier/signal/network-type populate. On a Wi-Fi-only tablet (or an emulator without telephony), confirm the tile is greyed out with "硬件不支持" and is not clickable.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/cellular/CellularActivity.java app/src/main/java/com/elotouch/devicetester/core/TestModule.java app/src/main/AndroidManifest.xml
git commit -m "feat: add Cellular connectivity module (PRD §3.17)"
```

---

### Task 9: New module — USB/OTG (§3.18)

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/usbotg/UsbOtgActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/core/TestModule.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `TestModule.USB_OTG` enum entry.

- [ ] **Step 1: Create the Activity**

```java
package com.elotouch.devicetester.modules.usbotg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * USB / OTG module (PRD §3.18). USB attach/detach and OTG storage are
 * inherently dynamic, so this page detects them live (pluggable-peripheral
 * model, PRD §5.3) rather than the grid greying the entry out.
 */
public class UsbOtgActivity extends BaseTestActivity {

    private TextView usbText;
    private TextView otgText;
    private TextView chargeText;
    private boolean receiverRegistered;

    private final ActivityResultLauncher<Intent> browseLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> { });

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refreshUsb();
        }
    };

    @Override
    protected String title() {
        return "USB/OTG 接口测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("USB Connection / USB 连接状态");
        usbText = addInfo("");

        addSectionTitle("OTG Storage / OTG 存储识别");
        otgText = addInfo("");
        addButton("Browse External Storage / 浏览外接存储", this::browseOtg);

        addSectionTitle("Charging Current / 充电电流");
        chargeText = addInfo("Reading… 读取中…");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerReceiver(usbReceiver, filter);
            receiverRegistered = true;
        }
        refreshUsb();
        refreshOtg();
        refreshCharge();
    }

    private void refreshUsb() {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        Map<String, UsbDevice> devices = um.getDeviceList();
        usbText.setText(devices.isEmpty()
                ? "No USB device attached. 当前无 USB 设备连接。"
                : devices.size() + " USB device(s) attached 已连接：" + devices.size());
    }

    private void refreshOtg() {
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> volumes = sm.getStorageVolumes();
        StringBuilder sb = new StringBuilder();
        int removable = 0;
        for (StorageVolume v : volumes) {
            if (v.isRemovable() && !v.isPrimary()) {
                removable++;
                String desc = v.getDescription(this);
                sb.append("  · ").append(v.getState())
                        .append(desc != null ? " (" + desc + ")" : "").append('\n');
            }
        }
        otgText.setText(removable > 0
                ? removable + " removable volume(s) 检测到可移动存储：\n" + sb
                : "No OTG storage detected. 未检测到 OTG 存储设备。");
    }

    private void refreshCharge() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int microAmps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        chargeText.setText(microAmps == Integer.MIN_VALUE || microAmps == 0
                ? "Not readable on this device. 该机型不支持读取。"
                : String.format(Locale.US, "%.0f mA", microAmps / 1000f));
    }

    private void browseOtg() {
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> volumes = sm.getStorageVolumes();
        for (StorageVolume v : volumes) {
            if (v.isRemovable() && !v.isPrimary()) {
                Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? v.createOpenDocumentTreeIntent()
                        : new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                browseLauncher.launch(intent);
                return;
            }
        }
        toast("No OTG storage to browse. 未检测到可浏览的 OTG 存储。");
    }

    @Override
    protected void onStopTests() {
        if (receiverRegistered) {
            try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
    }
}
```

- [ ] **Step 2: Register in `TestModule`**

Add import `com.elotouch.devicetester.modules.usbotg.UsbOtgActivity;`. Change the `CELLULAR` line's trailing `;` to `,`, then add `USB_OTG` as the new last entry (ends with `;`):

```java
    CELLULAR("Cellular / 蜂窝网络", "📡", CellularActivity.class, HardwareDetector.Feature.CELLULAR),
    USB_OTG("USB/OTG", "🔌", UsbOtgActivity.class, HardwareDetector.Feature.ALWAYS);
```

- [ ] **Step 3: Register in the manifest**

```xml
        <activity android:name=".modules.usbotg.UsbOtgActivity" />
```

- [ ] **Step 4: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Install and open "🔌 USB/OTG". Plug/unplug a USB cable or OTG flash drive and confirm the USB connection text updates live without needing to leave and re-enter the page. With an OTG drive attached, tap "Browse External Storage" and confirm the system document-tree picker opens.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/usbotg/UsbOtgActivity.java app/src/main/java/com/elotouch/devicetester/core/TestModule.java app/src/main/AndroidManifest.xml
git commit -m "feat: add USB/OTG module (PRD §3.18)"
```

---

### Task 10: Display enhancements — color accuracy / Mura / touch alignment

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/display/ColorAccuracyActivity.java`
- Create: `app/src/main/java/com/elotouch/devicetester/modules/display/MuraActivity.java`
- Create: `app/src/main/java/com/elotouch/devicetester/modules/display/TouchAlignmentActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/display/DisplayActivity.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `modules.display.ImmersiveActivity` (existing).

- [ ] **Step 1: Create `ColorAccuracyActivity`**

```java
package com.elotouch.devicetester.modules.display;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Color accuracy / gamut reference test (PRD §3.6 addition): shows standard
 * swatches with their target RGB values overlaid for comparison against an
 * external colorimeter or trained eye. The phone's own sensors can't verify
 * color accuracy in closed loop, so this intentionally does not auto-judge.
 */
public class ColorAccuracyActivity extends ImmersiveActivity {

    private static final int[][] SWATCHES = {
            {255, 0, 0}, {0, 255, 0}, {0, 0, 255},
            {255, 255, 255}, {128, 128, 128}, {255, 165, 0}
    };

    private int index = 0;
    private TextView label;
    private FrameLayout root;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        label = new TextView(this);
        label.setTextSize(18);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(label, lp);
        root.setOnClickListener(v -> {
            index = (index + 1) % SWATCHES.length;
            apply();
        });
        setContentView(root);
        apply();
    }

    private void apply() {
        int[] rgb = SWATCHES[index];
        int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
        root.setBackgroundColor(color);
        boolean lightBg = (rgb[0] + rgb[1] + rgb[2]) / 3 > 180;
        label.setTextColor(lightBg ? Color.BLACK : Color.WHITE);
        label.setText(String.format(Locale.US,
                "Target RGB(%d,%d,%d)  #%06X\nTap to switch · Back to exit\n"
                        + "目标 RGB 值，供外部仪器或肉眼比对 · 点击切换 · 返回键退出",
                rgb[0], rgb[1], rgb[2], color & 0xFFFFFF));
    }
}
```

- [ ] **Step 2: Create `MuraActivity`**

```java
package com.elotouch.devicetester.modules.display;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Screen-uniformity (Mura) test (PRD §3.6 addition): fullscreen low-gray
 * fields for spotting backlight bleed / blotching by eye at the corners and
 * edges.
 */
public class MuraActivity extends ImmersiveActivity {

    private static final int[] GRAYS = {30, 50, 70, 90, 128, 180};
    private int index = 0;
    private FrameLayout root;
    private TextView hint;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        hint = new TextView(this);
        hint.setTextColor(Color.RED);
        hint.setTextSize(14);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(hint, lp);
        root.setOnClickListener(v -> {
            index = (index + 1) % GRAYS.length;
            apply();
        });
        setContentView(root);
        apply();
    }

    private void apply() {
        int g = GRAYS[index];
        root.setBackgroundColor(Color.rgb(g, g, g));
        hint.setText("Gray level 灰阶：" + g + "/255\nCheck corners & edges for bleed/blotching\n"
                + "观察四角与边缘是否有漏光或色斑 · 点击切换 · 返回键退出");
    }
}
```

- [ ] **Step 3: Create `TouchAlignmentActivity`**

```java
package com.elotouch.devicetester.modules.display;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Touch-to-display alignment test (PRD §3.6 addition): overlays a reference
 * grid and the live touch point so any offset between what's drawn and where
 * the digitizer reports the finger is visible by eye.
 */
public class TouchAlignmentActivity extends ImmersiveActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new AlignmentView(this));
    }

    @SuppressLint("ViewConstructor")
    private static class AlignmentView extends View {
        private static final int GRID_STEP_DP = 40;
        private final Paint gridPaint = new Paint();
        private final Paint touchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float touchX = -1, touchY = -1;

        AlignmentView(Context c) {
            super(c);
            setBackgroundColor(Color.WHITE);
            gridPaint.setColor(Color.LTGRAY);
            gridPaint.setStrokeWidth(2);
            touchPaint.setColor(Color.RED);
            textPaint.setColor(Color.DKGRAY);
            textPaint.setTextSize(36);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            touchX = e.getX();
            touchY = e.getY();
            invalidate();
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float step = GRID_STEP_DP * density;
            for (float x = 0; x < getWidth(); x += step) canvas.drawLine(x, 0, x, getHeight(), gridPaint);
            for (float y = 0; y < getHeight(); y += step) canvas.drawLine(0, y, getWidth(), y, gridPaint);
            if (touchX >= 0) {
                canvas.drawLine(touchX, 0, touchX, getHeight(), touchPaint);
                canvas.drawLine(0, touchY, getWidth(), touchY, touchPaint);
                canvas.drawCircle(touchX, touchY, 12, touchPaint);
            }
            canvas.drawText("Compare the red touch marker to the grid lines under your finger\n"
                            + "比较红色触控标记与手指下方网格线的偏移 · 返回键退出",
                    20, getHeight() - 30, textPaint);
        }
    }
}
```

- [ ] **Step 4: Wire the 3 new sub-tests into `DisplayActivity`**

In `DisplayActivity.buildUi()`, after the existing "Refresh Rate / 刷新率" section's `addInfo(...)` call, append:

```java
        addSectionTitle("Color Accuracy / 颜色准确度与色域");
        addInfo("Standard swatches with target RGB overlay, for external colorimeter or eye "
                + "comparison. Not an automatic pass/fail.\n"
                + "显示标准色块并叠加目标 RGB 数值，供外部仪器或人工比对，不做自动判定。");
        addButton("Start Color Accuracy Test / 开始色准测试", () ->
                startActivity(new Intent(this, ColorAccuracyActivity.class)));

        addSectionTitle("Uniformity / Mura / 屏幕均匀性");
        addInfo("Fullscreen low-gray fields for spotting backlight bleed and blotching by eye.\n"
                + "全屏低灰阶画面，肉眼观察漏光与色斑。");
        addButton("Start Mura Test / 开始均匀性测试", () ->
                startActivity(new Intent(this, MuraActivity.class)));

        addSectionTitle("Touch-Display Alignment / 触控显示对齐度");
        addInfo("Overlays a reference grid with the live touch point to reveal any offset.\n"
                + "叠加参考网格与实时触控点，检测是否存在偏移。");
        addButton("Start Alignment Test / 开始对齐度测试", () ->
                startActivity(new Intent(this, TouchAlignmentActivity.class)));
```

(`Intent` is already imported in `DisplayActivity.java`.)

- [ ] **Step 5: Register the 3 Activities in the manifest**

Add inside `<application>`, after the existing `GrayscaleActivity` entry:

```xml
        <activity android:name=".modules.display.ColorAccuracyActivity"
            android:theme="@style/Theme.EloDeviceTester.Fullscreen" />
        <activity android:name=".modules.display.MuraActivity"
            android:theme="@style/Theme.EloDeviceTester.Fullscreen" />
        <activity android:name=".modules.display.TouchAlignmentActivity"
            android:theme="@style/Theme.EloDeviceTester.Fullscreen" />
```

- [ ] **Step 6: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Manual verification**

Open Display module, confirm 3 new sections/buttons appear below "Refresh Rate". Launch each: color-accuracy swatch should cycle on tap with an RGB label; Mura should cycle gray levels; alignment screen should show a grid with a red marker following your finger. Back button exits each to the Display menu.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/display/ColorAccuracyActivity.java app/src/main/java/com/elotouch/devicetester/modules/display/MuraActivity.java app/src/main/java/com/elotouch/devicetester/modules/display/TouchAlignmentActivity.java app/src/main/java/com/elotouch/devicetester/modules/display/DisplayActivity.java app/src/main/AndroidManifest.xml
git commit -m "feat: add color accuracy, Mura, and touch-alignment tests to Display module (PRD §3.6)"
```

---

### Task 11: Touch enhancement — sampling rate & pressure

**Files:**
- Create: `app/src/main/java/com/elotouch/devicetester/modules/touch/TouchSamplingActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/touch/TouchActivity.java`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Create `TouchSamplingActivity`**

```java
package com.elotouch.devicetester.modules.touch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.elotouch.devicetester.modules.display.ImmersiveActivity;

import java.util.Locale;

/**
 * Touch sampling-rate & pressure test (PRD §3.7 addition). Both figures come
 * straight off the MotionEvent stream, so they're shown together on one
 * screen rather than as two near-identical fullscreen views.
 */
public class TouchSamplingActivity extends ImmersiveActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new SamplingView(this));
    }

    @SuppressLint("ViewConstructor")
    private static class SamplingView extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long lastEventNanos = -1;
        private float avgHz = 0f;
        private float lastPressure = -1;
        private float lastSize = -1;
        private int sampleCount = 0;

        SamplingView(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            text.setColor(Color.WHITE);
            text.setTextSize(42);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                int historySize = e.getHistorySize();
                for (int i = 0; i < historySize; i++) {
                    recordSample(e.getHistoricalEventTime(i));
                }
                recordSample(e.getEventTime());
                lastPressure = e.getPressure();
                lastSize = e.getSize();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                lastEventNanos = -1;
            }
            invalidate();
            return true;
        }

        private void recordSample(long eventTimeMs) {
            long nanos = eventTimeMs * 1_000_000L;
            if (lastEventNanos > 0) {
                long deltaNanos = nanos - lastEventNanos;
                if (deltaNanos > 0) {
                    float hz = 1_000_000_000f / deltaNanos;
                    sampleCount++;
                    avgHz += (hz - avgHz) / Math.min(sampleCount, 30);
                }
            }
            lastEventNanos = nanos;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawText(String.format(Locale.US, "Sampling rate 采样率/报点率：%.0f Hz", avgHz), 40, 120, text);
            canvas.drawText(String.format(Locale.US, "Pressure 压力：%.3f", lastPressure), 40, 190, text);
            canvas.drawText(String.format(Locale.US, "Contact size 接触面积：%.3f", lastSize), 40, 260, text);
            canvas.drawText("Touch and move your finger · Back to exit\n"
                    + "触摸并移动手指 · 返回键退出", 40, getHeight() - 60, text);
        }
    }
}
```

- [ ] **Step 2: Wire into `TouchActivity`**

In `TouchActivity.buildUi()`, after the "Accuracy / Dead-zone Grid" section's button, append:

```java
        addSectionTitle("Sampling Rate & Pressure / 采样率与压感");
        addInfo("Live touch report rate (Hz) and pressure/contact-size readouts, if supported by "
                + "the digitizer. Back to exit.\n"
                + "实时显示触控报点率（Hz）与压力/接触面积（若硬件支持）。返回键退出。");
        addButton("Start Sampling Test / 开始采样率/压感测试", () ->
                startActivity(new Intent(this, TouchSamplingActivity.class)));
```

- [ ] **Step 3: Register in the manifest**

```xml
        <activity android:name=".modules.touch.TouchSamplingActivity"
            android:theme="@style/Theme.EloDeviceTester.Fullscreen" />
```

- [ ] **Step 4: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Open Touch module, tap "Start Sampling Test", move a finger around and confirm the Hz/pressure/contact-size numbers update live and look plausible (Hz roughly matching the panel's known touch report rate).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/touch/TouchSamplingActivity.java app/src/main/java/com/elotouch/devicetester/modules/touch/TouchActivity.java app/src/main/AndroidManifest.xml
git commit -m "feat: add touch sampling-rate/pressure test (PRD §3.7)"
```

---

### Task 12: Audio enhancement — latency test

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/audio/AudioActivity.java`

- [ ] **Step 1: Add imports and a field**

At the top of `AudioActivity.java`, add to the import list:

```java
import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
```

and add `import java.util.Locale;` (not yet present in this file).

Add a field alongside the existing ones:

```java
    private TextView latencyText;
```

- [ ] **Step 2: Add the UI section**

In `buildUi()`, after the "Microphone / 麦克风测试" section's button, append:

```java
        addSectionTitle("Audio Latency / 音频延迟测试");
        latencyText = addInfo("Tap to measure. 点击开始测量。");
        addButton("Measure Latency / 测量延迟", this::measureLatency);
```

- [ ] **Step 3: Add the measurement methods**

```java
    private void measureLatency() {
        requirePermission(Manifest.permission.RECORD_AUDIO, this::doMeasureLatency,
                () -> latencyText.setText("Permission denied. 权限受限：未授予麦克风权限。"));
    }

    @SuppressLint("MissingPermission")
    private void doMeasureLatency() {
        latencyText.setText("Measuring… 测量中…");
        runAsync(() -> {
            int sampleRate = 44100;
            int minBuf = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                ui(() -> latencyText.setText("Mic not available for raw capture. 无法获取麦克风原始采样。"));
                return;
            }
            AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
            ToneGenerator tone = null;
            try {
                record.startRecording();
                tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                short[] buffer = new short[minBuf];
                long totalSamples = 0;
                long playSampleMark = -1;
                long thresholdSample = -1;
                boolean tonePlayed = false;
                long endTimeMs = System.currentTimeMillis() + 2500;
                while (System.currentTimeMillis() < endTimeMs && !isStopped()) {
                    int read = record.read(buffer, 0, buffer.length);
                    if (read <= 0) continue;
                    if (!tonePlayed && totalSamples > sampleRate / 5) {
                        tone.startTone(ToneGenerator.TONE_DTMF_0, 300);
                        playSampleMark = totalSamples;
                        tonePlayed = true;
                    }
                    if (tonePlayed && thresholdSample < 0) {
                        for (int i = 0; i < read; i++) {
                            if (Math.abs(buffer[i]) > 8000) {
                                thresholdSample = totalSamples + i;
                                break;
                            }
                        }
                    }
                    totalSamples += read;
                    if (thresholdSample >= 0) break;
                }
                if (playSampleMark >= 0 && thresholdSample >= playSampleMark) {
                    long deltaSamples = thresholdSample - playSampleMark;
                    double ms = deltaSamples * 1000.0 / sampleRate;
                    ui(() -> latencyText.setText(String.format(Locale.US,
                            "Round-trip latency 往返延迟：约 %.0f ms (relative reference 相对参考值)", ms)));
                } else {
                    ui(() -> latencyText.setText("Could not detect the tone in the recording. "
                            + "未能在录音中检测到播放的提示音，请靠近扬声器重试。"));
                }
            } finally {
                if (tone != null) tone.release();
                record.stop();
                record.release();
            }
        });
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Open Audio module, tap "Measure Latency", grant mic permission if prompted, hold the device normally (mic near speaker) and confirm a plausible millisecond figure or the "could not detect" fallback message appears — it should never crash regardless of outcome.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/audio/AudioActivity.java
git commit -m "feat: add audio round-trip latency test (PRD §3.8)"
```

---

### Task 13: Audio enhancement — sample rate & channel verification

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/audio/AudioActivity.java`

- [ ] **Step 1: Add imports and a field**

Add to the import list:

```java
import android.media.AudioAttributes;
import android.media.AudioTrack;
```

Add a field:

```java
    private TextView channelText;
```

- [ ] **Step 2: Add the UI section**

In `buildUi()`, after the latency section added in Task 12, append:

```java
        addSectionTitle("Sample Rate & Channels / 采样率与声道验证");
        channelText = addInfo("Tap to verify. 点击开始验证。");
        addButton("Verify Left/Right Channel / 验证左右声道", this::verifyChannels);
```

- [ ] **Step 3: Add the verification methods**

```java
    private void verifyChannels() {
        String outRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
        channelText.setText("Output sample rate 输出采样率：" + outRate + " Hz\n"
                + "Playing left channel… 正在播放左声道…\n"
                + "(THD/distortion metrics need a dedicated audio analyzer and are out of scope; "
                + "phone mics aren't precise enough. THD/失真度需专用音频分析仪，手机麦克风精度不足，不纳入范围)");
        playChannelTone(true);
    }

    private void playChannelTone(boolean left) {
        int sampleRate = 44100;
        int numSamples = sampleRate; // 1 second
        short[] mono = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            mono[i] = (short) (Short.MAX_VALUE * 0.5 * Math.sin(2 * Math.PI * 440 * i / sampleRate));
        }
        short[] stereo = new short[numSamples * 2];
        for (int i = 0; i < numSamples; i++) {
            stereo[i * 2] = left ? mono[i] : 0;
            stereo[i * 2 + 1] = left ? 0 : mono[i];
        }
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(stereo.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        track.write(stereo, 0, stereo.length);
        track.play();
        String channel = left ? "left 左" : "right 右";
        channelText.setText("Playing " + channel + " channel only — confirm you hear it on that side.\n"
                + "仅播放" + (left ? "左" : "右") + "声道，请确认声音仅从对应一侧发出。");
        main.postDelayed(() -> {
            track.stop();
            track.release();
            if (left) {
                playChannelTone(false);
            } else {
                channelText.setText(channelText.getText() + "\nDone. 测试完成。");
            }
        }, 1200);
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Open Audio module, tap "Verify Left/Right Channel" with headphones on, confirm a 440 Hz tone plays in the left channel only for ~1.2s, then the right channel only for ~1.2s, ending with "测试完成".

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/audio/AudioActivity.java
git commit -m "feat: add audio sample-rate and channel verification (PRD §3.8)"
```

---

### Task 14: Camera enhancement — resolution & focus speed

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/camera/CameraActivity.java`

- [ ] **Step 1: Add imports and a field**

Add to the import list:

```java
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;

import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringResult;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;

import java.util.Locale;
```

Add a field:

```java
    private TextView cameraInfoText;
```

- [ ] **Step 2: Add the UI section**

In `buildUi()`, after the existing "Flashlight On/Off" button, append:

```java
        addSectionTitle("Resolution & Focus / 分辨率与对焦速度");
        cameraInfoText = addInfo("");
        addButton("Check Output Resolution / 核验输出分辨率", this::checkResolution);
        addButton("Measure Focus Speed / 测量对焦速度", this::measureFocusSpeed);
```

- [ ] **Step 3: Add the two methods**

```java
    private void checkResolution() {
        if (camera == null) {
            cameraInfoText.setText("Camera not ready. 相机未就绪。");
            return;
        }
        try {
            Camera2CameraInfo camera2Info = Camera2CameraInfo.from(camera.getCameraInfo());
            StreamConfigurationMap map = camera2Info.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            StringBuilder sb = new StringBuilder("Supported JPEG sizes 支持的拍照分辨率：\n");
            if (map != null) {
                Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                if (sizes != null) {
                    for (Size s : sizes) sb.append("  ").append(s.getWidth())
                            .append('x').append(s.getHeight()).append('\n');
                }
            }
            cameraInfoText.setText(sb.toString());
        } catch (Exception e) {
            cameraInfoText.setText("Could not read resolutions. 无法读取分辨率列表：" + e.getMessage());
        }
    }

    private void measureFocusSpeed() {
        if (camera == null || previewView == null) {
            cameraInfoText.setText("Camera not ready. 相机未就绪。");
            return;
        }
        MeteringPointFactory factory = previewView.getMeteringPointFactory();
        MeteringPoint point = factory.createPoint(previewView.getWidth() / 2f, previewView.getHeight() / 2f);
        FocusMeteringAction action = new FocusMeteringAction.Builder(point).build();
        long t0 = System.nanoTime();
        cameraInfoText.setText("Focusing… 对焦中…");
        ListenableFuture<FocusMeteringResult> future = camera.getCameraControl().startFocusAndMetering(action);
        future.addListener(() -> {
            try {
                FocusMeteringResult result = future.get();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                cameraInfoText.setText(String.format(Locale.US,
                        "Focus %s in %d ms\n对焦%s，耗时 %d ms",
                        result.isFocusSuccessful() ? "succeeded" : "did not converge", ms,
                        result.isFocusSuccessful() ? "成功" : "未收敛", ms));
            } catch (Exception e) {
                cameraInfoText.setText("Focus measurement failed 对焦测量失败：" + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Open Camera module, wait for preview, tap "Check Output Resolution" and confirm a list of `WxH` sizes appears; tap "Measure Focus Speed" and confirm a millisecond figure appears after the lens visibly refocuses.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/camera/CameraActivity.java
git commit -m "feat: add camera resolution check and focus-speed test (PRD §3.9)"
```

---

### Task 15: Camera enhancement — multi-camera switching

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/camera/CameraActivity.java`

- [ ] **Step 1: Add imports and fields**

Add to the import list:

```java
import android.hardware.camera2.CameraManager;

import androidx.camera.core.CameraInfo;

import java.util.ArrayList;
import java.util.List;
```

Add fields:

```java
    private TextView multiCameraText;
    private final List<String> cameraIds = new ArrayList<>();
    private int cameraIdIndex = -1;
```

- [ ] **Step 2: Add the UI section**

In `buildUi()`, after the resolution/focus section added in Task 14, append:

```java
        addSectionTitle("Multi-Camera / 多摄像头切换");
        multiCameraText = addInfo("");
        addButton("List Cameras / 列出所有摄像头", this::listCameras);
        addButton("Next Camera / 切换到下一个摄像头", this::nextCamera);
```

- [ ] **Step 3: Add the switching methods**

```java
    private void listCameras() {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            cameraIds.clear();
            StringBuilder sb = new StringBuilder("Cameras found 检测到摄像头：\n");
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                cameraIds.add(id);
                sb.append("  id=").append(id).append("  facing=").append(facingStr(facing)).append('\n');
            }
            multiCameraText.setText(sb.toString());
        } catch (Exception e) {
            multiCameraText.setText("Failed to list cameras 列出摄像头失败：" + e.getMessage());
        }
    }

    private void nextCamera() {
        if (cameraIds.isEmpty()) listCameras();
        if (cameraIds.isEmpty() || cameraProvider == null) return;
        cameraIdIndex = (cameraIdIndex + 1) % cameraIds.size();
        String targetId = cameraIds.get(cameraIdIndex);
        cameraProvider.unbindAll();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());
        CameraSelector selector = new CameraSelector.Builder()
                .addCameraFilter(infos -> {
                    List<CameraInfo> filtered = new ArrayList<>();
                    for (CameraInfo info : infos) {
                        if (targetId.equals(Camera2CameraInfo.from(info).getCameraId())) {
                            filtered.add(info);
                        }
                    }
                    return filtered;
                })
                .build();
        try {
            camera = cameraProvider.bindToLifecycle(this, selector, preview);
            multiCameraText.setText("Now showing camera id=" + targetId + " 当前显示摄像头 id=" + targetId);
        } catch (Exception e) {
            multiCameraText.setText("Bind failed 切换失败：" + e.getMessage());
        }
    }

    private static String facingStr(Integer facing) {
        if (facing == null) return "unknown 未知";
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT: return "front 前置";
            case CameraCharacteristics.LENS_FACING_BACK: return "back 后置";
            case CameraCharacteristics.LENS_FACING_EXTERNAL: return "external 外接";
            default: return "unknown 未知";
        }
    }
```

Note: on many OEM devices, extra back cameras (ultra-wide/tele) are hidden behind one logical camera ID rather than exposed as separate IDs — treat this listing as best-effort, matching this codebase's existing tone for hardware quirks (e.g. battery current, DDR bandwidth).

- [ ] **Step 4: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Manual verification**

Open Camera module, tap "List Cameras" and confirm every physical camera ID + facing is listed; tap "Next Camera" repeatedly and confirm the preview switches between them without crashing, cycling back to the first after the last.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/camera/CameraActivity.java
git commit -m "feat: add multi-camera switching test (PRD §3.9)"
```

---

### Task 16: Update `README.md`

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the module map**

Replace the "Module map" section's list with:

```markdown
## Module map

`ddr` · `storage` · `display` (+ `ColorTestActivity`, `GrayscaleActivity`,
`ColorAccuracyActivity`, `MuraActivity`, `TouchAlignmentActivity`) ·
`touch` (+ `MultiTouchActivity`, `TouchGridActivity`, `TouchSamplingActivity`) ·
`battery` · `vibrator` · `camera` (CameraX) · `audio` · `gps` · `nfc` ·
`wifibt` · `sensors` · `barcode` · `msr` · `serial` · `displayext` ·
`cellular` · `usbotg`
```

- [ ] **Step 2: Extend the "Known device-dependent / best-effort items" list**

Append these bullets to that section:

```markdown
- **Barcode symbology** — wedge-mode scanners only transmit the decoded text,
  not the barcode format (Code128/QR/etc.); the field is intentionally omitted.
- **MSR IC/contactless response** — shown as raw bytes from the attached
  serial reader, not a parsed ATR; the exact protocol is reader-specific.
- **Audio latency** — an approximate round-trip figure from mic self-capture,
  affected by acoustic crosstalk and scheduling jitter; not lab-grade.
- **Multi-camera enumeration** — some OEMs hide extra back cameras
  (ultra-wide/tele) behind one logical camera ID; listing is best-effort.
- **USB-serial peripherals** (§3.14/§3.15) need a device compatible with the
  open-source `usb-serial-for-android` library (common CP210x/FTDI/CH34x/PL2303
  chipsets); vendor-specific protocols are out of scope.
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: update README module map and known limitations for PRD expansion"
```

---

### Task 17: Full project build + manual smoke test pass

**Files:** none (verification only).

- [ ] **Step 1: Clean build**

Run: `./gradlew clean assembleDebug`
Expected: `BUILD SUCCESSFUL`, with no warnings escalated to errors.

- [ ] **Step 2: Install on a device/emulator**

Run: `./gradlew installDebug`

- [ ] **Step 3: Grid smoke test**

Launch the app. Confirm all 18 module tiles render (12 original + 6 new), the grid scrolls vertically if it overflows the screen, and every previously-working module (DDR, Storage, Display, Touch, Battery, Vibrator, Camera, Audio, GPS, NFC, Wi-Fi/BT, Sensors) still opens and behaves as before — this plan must not regress any existing module.

- [ ] **Step 4: New/enhanced module smoke test**

Walk through each of the 6 new modules and each of the 4 enhanced modules' new buttons at least once, confirming no crash occurs whether or not the underlying peripheral is physically attached (missing peripherals must show an inline "未检测到" message, never a stack trace / force-close).

- [ ] **Step 5: Final commit (if any fixes were needed)**

If Steps 3–4 surfaced any bugs, fix them, re-run Steps 1–4, then:

```bash
git add -A
git commit -m "fix: address issues found in full smoke test"
```

If no fixes were needed, no commit is required for this task.
