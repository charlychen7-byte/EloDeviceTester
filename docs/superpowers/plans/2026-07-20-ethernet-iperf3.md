# Ethernet iperf3 Throughput Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an iperf3-based download/upload throughput sub-test, reachable
from the existing Ethernet module page, per
`docs/superpowers/specs/2026-07-20-ethernet-iperf3-design.md`.

**Architecture:** Bundle the user-supplied iperf3 binary the same way
`memtester`/`QMESA` are bundled (prebuilt `.so` under
`jniLibs/arm64-v8a/`). Add one new Activity, `IperfActivity`, extending
`modules/ddr/NativeToolTestActivity` unchanged — reusing its Start/Stop,
elapsed-time ticker, rolling log, and PASSED/FAILED status entirely as-is.
Direction (download/upload) is a `RadioGroup` toggling the `-R` flag,
avoiding any need for two separate Start/Stop pairs or base-class changes.
`EthernetActivity` gains a launcher section mirroring how `DdrActivity`
launches `MemtesterActivity`/`QmesaActivity`.

**Tech Stack:** Java, Android SDK, `core.NativeProcessRunner` (existing,
unchanged), no new third-party dependencies.

## Global Constraints

- Java only, no Kotlin. Minimum SDK 26.
- No automated test suite exists in this project. Verification is
  `./gradlew assembleDebug` succeeding.
- Do not modify `modules/ddr/NativeToolTestActivity.java`,
  `core/NativeProcessRunner.java`, `core/TestModule.java`, or any existing
  Memtester/QMESA behavior — this feature must be additive only.
- This is **not** a new top-level module grid tile — it's a sub-test
  reachable from the existing Ethernet page, exactly like Memtester/QMESA
  are reachable from the DDR page.
- The iperf3 binary is a prebuilt ARM64 executable already supplied by the
  user at `EloTestApp/iperf3` (one directory above the Android project
  root, i.e. sibling to `EloDeviceTester/`) — confirmed via `file` as
  `ELF 64-bit LSB pie executable, ARM aarch64, ... dynamically linked,
  interpreter /system/bin/linker64, for Android 28, built by NDK r28c`.
  Do not attempt to build or download a different iperf3 binary.

---

### Task 1: Bundle iperf3 binary, add `IperfActivity`, wire into Ethernet page

**Files:**
- Create (binary copy, not source): `app/src/main/jniLibs/arm64-v8a/libiperf3.so`
- Create: `app/src/main/java/com/elotouch/devicetester/modules/ethernet/IperfActivity.java`
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/ethernet/EthernetActivity.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `modules/ddr/NativeToolTestActivity` (existing, unchanged) —
  its abstract hooks `soName()`, `buildArgs()`, `failureKeyword()`,
  `description()`, `buildExtraControls()`, `setExtraControlsEnabled(boolean)`,
  and inherited `addSectionTitle`/`addView` helpers (from `BaseTestActivity`
  via `NativeToolTestActivity`).
- Produces: nothing consumed by later tasks — this is the only task in
  this plan.

- [ ] **Step 1: Copy the iperf3 binary into jniLibs**

The binary already exists one directory above the Android project root
(confirmed present and ELF arm64-v8a — see Global Constraints). Run from
the `EloDeviceTester/` project root:

```bash
mkdir -p app/src/main/jniLibs/arm64-v8a
cp ../iperf3 app/src/main/jniLibs/arm64-v8a/libiperf3.so
```

Verify it copied correctly:

```bash
file app/src/main/jniLibs/arm64-v8a/libiperf3.so
```

Expected output contains: `ELF 64-bit LSB pie executable, ARM aarch64`
(same as the source file). This is a binary asset, not a text file — do
not attempt to view or edit its contents.

- [ ] **Step 2: Create `IperfActivity.java`**

```java
package com.elotouch.devicetester.modules.ethernet;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.elotouch.devicetester.modules.ddr.NativeToolTestActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the bundled iperf3 client against a user-specified server, measuring
 * download (-R) or upload throughput per the selected direction.
 */
public class IperfActivity extends NativeToolTestActivity {

    private EditText serverInput;
    private RadioButton downloadRadio;
    private RadioButton uploadRadio;
    private EditText intervalInput;
    private EditText windowInput;
    private EditText timeInput;

    @Override
    protected String title() {
        return "iperf3";
    }

    @Override
    protected void buildExtraControls() {
        addSectionTitle("Server / 服务器地址");
        serverInput = new EditText(this);
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT);
        serverInput.setHint("iperf3 server IP/hostname / 服务器 IP 或主机名");
        addView(serverInput);

        addSectionTitle("Direction / 测试方向");
        downloadRadio = new RadioButton(this);
        downloadRadio.setId(View.generateViewId());
        downloadRadio.setText("Download / 下载");
        uploadRadio = new RadioButton(this);
        uploadRadio.setId(View.generateViewId());
        uploadRadio.setText("Upload / 上传");
        RadioGroup directionGroup = new RadioGroup(this);
        directionGroup.setOrientation(RadioGroup.VERTICAL);
        directionGroup.addView(downloadRadio);
        directionGroup.addView(uploadRadio);
        directionGroup.check(downloadRadio.getId());
        addView(directionGroup);

        addSectionTitle("Interval (s) / 报告间隔（秒）");
        intervalInput = new EditText(this);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalInput.setHint("Blank = 1 / 留空默认 1");
        addView(intervalInput);

        addSectionTitle("Window size (MB) / 窗口大小（MB）");
        windowInput = new EditText(this);
        windowInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        windowInput.setHint("Blank = 16 (download) / 8 (upload) / 留空默认下载16、上传8");
        addView(windowInput);

        addSectionTitle("Time (s) / 测试时长（秒）");
        timeInput = new EditText(this);
        timeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeInput.setHint("Blank = 100 / 留空默认 100");
        addView(timeInput);
    }

    @Override
    protected void setExtraControlsEnabled(boolean enabled) {
        serverInput.setEnabled(enabled);
        downloadRadio.setEnabled(enabled);
        uploadRadio.setEnabled(enabled);
        intervalInput.setEnabled(enabled);
        windowInput.setEnabled(enabled);
        timeInput.setEnabled(enabled);
    }

    @Override
    protected String soName() {
        return "libiperf3.so";
    }

    @Override
    protected String[] buildArgs() {
        boolean download = downloadRadio.isChecked();
        String server = serverInput.getText().toString().trim();
        long interval = parseOrDefault(intervalInput, 1);
        long window = parseOrDefault(windowInput, download ? 16 : 8);
        long time = parseOrDefault(timeInput, 100);

        List<String> args = new ArrayList<>();
        args.add("-c");
        args.add(server);
        args.add("-t");
        args.add(String.valueOf(time));
        args.add("-i");
        args.add(String.valueOf(interval));
        args.add("-w");
        args.add(window + "M");
        if (download) {
            args.add("-R");
        }
        return args.toArray(new String[0]);
    }

    private static long parseOrDefault(EditText input, long defaultValue) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return defaultValue;
        try {
            long value = Long.parseLong(text);
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    protected String failureKeyword() {
        return "iperf3: error";
    }

    @Override
    protected String description() {
        return "Runs the bundled iperf3 client against the server and settings below to "
                + "measure download or upload throughput.\n"
                + "运行内置 iperf3 客户端，根据下方服务器地址与参数测量下载或上传吞吐量。";
    }
}
```

- [ ] **Step 3: Add the launcher section to `EthernetActivity.java`**

Add the import in the existing `android.*` block, immediately after
`import android.content.Context;` (alphabetical: `Intent` follows
`Context` within `android.content`):

```java
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
```

Add this block at the very end of `buildUi()` (after the existing
`stopButton.setEnabled(false);` line):

```java
        addSectionTitle("iperf3 吞吐测试 / iperf3 Throughput Test");
        addInfo("Runs the bundled iperf3 client against a server you specify, measuring "
                + "download/upload throughput.\n"
                + "运行内置 iperf3 客户端，针对指定服务器测量下载/上传吞吐量。");
        addButton("Enter Test / 进入测试",
                () -> startActivity(new Intent(this, IperfActivity.class)));
```

- [ ] **Step 4: Register `IperfActivity` in the manifest**

Modify `app/src/main/AndroidManifest.xml`. Add immediately after the
`.modules.ethernet.EthernetActivity` line:

```xml
        <activity android:name=".modules.ethernet.EthernetActivity" />
        <activity android:name=".modules.ethernet.IperfActivity" />
        <activity android:name=".modules.cellular.CellularActivity" />
```

- [ ] **Step 5: Build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. This also confirms the binary was picked up
correctly by the jniLibs packaging (no missing-file errors).

- [ ] **Step 6: Manual verification**

Install on a device with a working Ethernet connection and access to a
running iperf3 server (`iperf3 -s` on a machine on the same network):
`./gradlew installDebug`.

- Open the app → Ethernet module → confirm a new "iperf3 吞吐测试 /
  iperf3 Throughput Test" section appears below the connectivity stress
  test, with an "Enter Test / 进入测试" button.
- Tap it → confirm the iperf3 page opens showing: description, Server
  field, Direction radio group (Download checked by default), Interval/
  Window size/Time fields (all with hint text), then Start/Stop buttons,
  status line, and log area (all inherited from `NativeToolTestActivity`
  — same layout as the Memtester page).
- Enter the iperf3 server's IP address, leave Direction on Download,
  leave Interval/Window/Time blank, press Start: confirm all input
  fields and the radio group become disabled, the log begins scrolling
  with per-interval throughput lines (`Mbits/sec`), and after ~100
  seconds (default `-t`) the test finishes on its own showing green
  "PASSED" with elapsed time, and the last visible log lines show the
  final sender/receiver summary bitrate.
- Switch Direction to Upload, press Start again: confirm the fields
  re-enable then disable again as expected, and the log shows upload
  throughput this time (no `-R` — client sends).
- Press Stop mid-run at least once: confirm the test stops within a
  couple seconds and shows a non-crashing status (not necessarily
  PASSED/FAILED — whatever `NativeToolTestActivity`'s existing stop
  handling produces, matching Memtester's existing Stop behavior).
- Enter an unreachable/invalid server address (e.g. `0.0.0.1`), press
  Start: confirm the log shows an `iperf3: error - ...` line and the
  final status shows red "FAILED".

- [ ] **Step 7: Commit**

```bash
git add app/src/main/jniLibs/arm64-v8a/libiperf3.so \
        app/src/main/java/com/elotouch/devicetester/modules/ethernet/IperfActivity.java \
        app/src/main/java/com/elotouch/devicetester/modules/ethernet/EthernetActivity.java \
        app/src/main/AndroidManifest.xml
git commit -m "feat: add iperf3 download/upload throughput test to Ethernet module"
```
