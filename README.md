# Elo DeviceTester

Android-native (Java) hardware/function diagnostic tool — a **single-point tool
collection**: each test runs independently and shows its result on the spot
(no pass/fail judging, no status tracking, no aggregate report). Built per the
PRD in `../EloDeviceTester PRD.docx`.

## Build & run

Requires Android Studio (Giraffe+) or a JDK 17 + Android SDK (compileSdk 34).

```bash
# from this directory
./gradlew assembleDebug   # build debug APK -> app/build/outputs/apk/debug/
./gradlew installDebug    # build + install on a connected device/emulator
```

Or simply open this folder in Android Studio and press Run. The Gradle wrapper
(`gradlew`/`gradlew.bat`/`gradle/wrapper/gradle-wrapper.jar`) is committed, so
no local Gradle install or one-time `gradle wrapper` step is needed.

- **Language:** Java 17 · **minSdk:** 26 (Android 8.0) · **targetSdk/compileSdk:** 34
- **Package:** `com.elotouch.devicetester`

## Architecture

Two-level navigation (PRD §2):

1. **`MainActivity`** — persistent device-info banner + a grid of hardware
   modules (`RecyclerView` + `ModuleAdapter`). Module metadata lives in the
   `TestModule` enum.
2. **One Activity per module** under `modules/<name>/`, each extending
   `core.BaseTestActivity`.

### Key shared infrastructure (`core/`)

- **`BaseTestActivity`** — every level-2 page extends this. Provides: screen-on
  flag, programmatic UI helpers (`addSectionTitle/addInfo/addButton/...`), a
  single-thread background executor with a stop flag (`runAsync`/`isStopped`/
  `stopTests`), lazy per-module permission requests
  (`requirePermission`/`requirePermissions`), and `onStopTests()` (called from
  `onPause`/`onDestroy`) where modules release exclusive hardware.
- **`HardwareDetector`** — probes real hardware (`PackageManager.hasSystemFeature`,
  `SensorManager`) so the grid greys out unsupported modules **before** render
  (anti-crash core, PRD §5.3).
- **`TestModule`** — the single source of truth for the module grid (title,
  emoji icon, target Activity, hardware-feature requirement).
- **`ModuleAdapter`** — renders the grid; disables + labels unsupported modules.

### Conventions

- UI is built **programmatically** in `buildUi()`; there are intentionally few
  layout XML files. Module icons are emoji (no per-module drawables).
- Long/high-load tests (DDR, Storage, ping) run on the executor and offer a
  stop button. Tests that grab exclusive hardware release it in `onStopTests()`.
- Every Activity declares `android:screenOrientation="nosensor"`: the app is
  locked to the device's natural orientation and ignores sensor rotation, so
  display/touch tests always map to fixed screen edges. New Activities must
  carry the same attribute.
- Fullscreen tests (color, grayscale, multi-touch, touch-grid) extend
  `modules/display/ImmersiveActivity` and use the `Fullscreen` theme.

## Module map

`cpu` · `ddr` · `storage` · `display` (+ `ColorTestActivity`, `GrayscaleActivity`,
`ColorAccuracyActivity`, `MuraActivity`, `TouchAlignmentActivity`) ·
`touch` (+ `MultiTouchActivity`, `TouchGridActivity`, `TouchSamplingActivity`) ·
`battery` · `vibrator` · `camera` (CameraX) · `audio` · `gps` · `nfc` ·
`wifibt` · `sensors` · `barcode` · `msr` · `serial` · `displayext` ·
`cellular` · `ping` (+ `PingTestActivity`) · `stability` · `ethernet` (+ `IperfActivity`, also a
top-level grid module) · `usbotg` · `cashdrawer`

## Known device-dependent / best-effort items

These are inherently unreliable across ROMs and degrade gracefully rather than
erroring (see PRD "实现说明" notes):

- **System Stability CPU/GPU load** — a full CPU load makes the UI sluggish, and the
  GPU test draws a ~48k-triangle procedural teapot (`Teapot.java`, OpenGL ES 2.0)
  with a deliberately expensive per-pixel shader; both are expected. The DDR working set is
  capped at min(25% available RAM, 60% of remaining heap) and the EMMC file shrinks
  to fit free space (skipped below 16MB), so absolute figures are not comparable
  across devices — the test looks for verification errors, not benchmarks.
- **System Stability temperature** — battery temperature comes from the sticky
  `ACTION_BATTERY_CHANGED`; the CPU figure needs a readable
  `/sys/class/thermal/thermal_zone*`, which many ROMs deny → "不可获取".
- **Cash Drawer** — drives the Elo RJ12 controller board (USB VID `0x1A86` /
  PID `0xFE0C`) by claiming its CDC-data interface and writing 8-byte frames
  `38 BE EF <cmd> <p1> <p2> <p3> 0D` to the bulk OUT endpoint. No CDC
  line-coding is set first — that matches the reference Cash Drawer Demo, whose
  baud-rate setup is a separate unrelated control. The board is a pluggable
  peripheral, so presence is detected live on the page; the grid cell is only
  greyed out on devices without USB host support.
- **CPU info / frequency** — read from `/proc/cpuinfo` and
  `/sys/devices/system/cpu/*/cpufreq/*`; hardened ROMs deny either, so each value
  degrades to "不可获取 / Not available" independently.
- **Battery charge/discharge log** — recording runs in `BatteryLogService`, a
  `specialUse` foreground service holding a PARTIAL_WAKE_LOCK, so it survives screen-off
  and leaving the page. A 1-minute cadence rules out AlarmManager (exact-while-idle is
  throttled to ~1 per 9 min in Doze). The wake lock keeps the CPU awake, which slightly
  steepens a discharge curve. CSV goes to `getExternalFilesDir()/battery/` — no storage
  permission, pullable with adb. POST_NOTIFICATIONS is requested lazily; if denied the
  service still records, only the notification is hidden.
- **Battery live current** — many ROMs return 0/unreadable → shown as "不支持".
- **DDR bandwidth** — Java array copy is a *relative* figure, not physical bandwidth.
- **DDR memtester / QMESA** — bundled prebuilt arm64-v8a native binaries, packaged as
  `jniLibs/arm64-v8a/lib*.so` with `packaging.jniLibs.useLegacyPackaging = true` in
  `app/build.gradle` so they're extracted to `nativeLibraryDir` and remain executable
  under API 29+ W^X restrictions (raw `assets/` + runtime `chmod` does NOT work on
  this app's `targetSdk 34`).
- **Audio earpiece routing** & **secondary mic** — no standard API; approximate.
- **Wi-Fi scan** — throttled by the system and needs location permission + services on.
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
