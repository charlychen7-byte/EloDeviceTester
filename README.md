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
- Fullscreen tests (color, grayscale, multi-touch, touch-grid) extend
  `modules/display/ImmersiveActivity` and use the `Fullscreen` theme.

## Module map

`ddr` · `storage` · `display` (+ `ColorTestActivity`, `GrayscaleActivity`,
`ColorAccuracyActivity`, `MuraActivity`, `TouchAlignmentActivity`) ·
`touch` (+ `MultiTouchActivity`, `TouchGridActivity`, `TouchSamplingActivity`) ·
`battery` · `vibrator` · `camera` (CameraX) · `audio` · `gps` · `nfc` ·
`wifibt` · `sensors` · `barcode` · `msr` · `serial` · `displayext` ·
`cellular` · `usbotg`

## Known device-dependent / best-effort items

These are inherently unreliable across ROMs and degrade gracefully rather than
erroring (see PRD "实现说明" notes):

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
