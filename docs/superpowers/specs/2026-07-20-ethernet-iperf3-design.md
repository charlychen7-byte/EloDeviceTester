# Ethernet Module — iperf3 Throughput Test Design

Date: 2026-07-20

## Purpose

Add an iperf3-based download/upload throughput sub-test, reachable from the
existing Ethernet module page, alongside its network-info section and
ping-based connectivity stress test (see
`docs/superpowers/specs/2026-07-20-ethernet-module-design.md`).

## Background

iperf3 is not a stock Android binary — unlike the ping test (which shells
out to `/system/bin/ping`), this test needs a bundled prebuilt binary, the
same way the DDR module bundles `memtester`/`QMESA` as prebuilt `.so`
executables under `app/src/main/jniLibs/arm64-v8a/` and runs them via
`core/NativeProcessRunner`.

## Decisions (from brainstorming Q&A)

1. **Binary source**: the user has supplied a prebuilt iperf3 binary at
   `EloTestApp/iperf3` (one directory above the Android project root).
   Confirmed via `file`: `ELF 64-bit LSB pie executable, ARM aarch64, ...
   dynamically linked, interpreter /system/bin/linker64, for Android 28,
   built by NDK r28c` — directly compatible with the existing
   arm64-v8a-only jniLibs convention (same dynamic-linking-against-bionic
   setup as the existing `memtester` binary). It is copied as-is to
   `app/src/main/jniLibs/arm64-v8a/libiperf3.so` — no cross-compilation
   needed.
2. **Test layout — single page, one Start/Stop pair**: rather than two
   separate Download/Upload buttons (which would conflict with
   `NativeToolTestActivity`'s single-Start/Stop-pair contract, used
   as-is by Memtester/QMESA), direction is chosen via a `RadioGroup`
   (Download / Upload) that controls whether `-R` is passed to iperf3.
   This keeps `NativeToolTestActivity` completely unmodified — zero risk
   to the existing Memtester/QMESA sub-tests.
3. **UI style — reuse Memtester's pattern**: extend
   `modules/ddr/NativeToolTestActivity` directly. Scrolling raw-output
   log (last 10 lines) + color-coded PASSED/FAILED status line. No
   separate structured result box (unlike the Ethernet ping stress
   test) — actual throughput numbers are read directly from iperf3's own
   output in the log.
4. **Navigation**: `EthernetActivity` gains a new section — description +
   a single "Enter Test / 进入测试" button that
   `startActivity(new Intent(this, IperfActivity.class))` — matching
   exactly how `DdrActivity` launches `MemtesterActivity`/`QmesaActivity`.
   This is **not** a new top-level `TestModule` grid tile; it's a
   sub-test reachable from the existing Ethernet page.
5. **Configurable parameters** (all read fresh on each Start press):
   - Server address (`-c`): required text input, no default.
   - Direction: RadioGroup, default **Download** (`-R` flag), toggles
     `-R` presence.
   - Interval (`-i`): numeric input, blank → `1`.
   - Window size (`-w`): numeric input (megabytes, "M" suffix appended in
     code), blank → `16` when Download is selected, `8` when Upload is
     selected (per user-specified defaults).
   - Time (`-t`): numeric input, blank → `100`.
6. **No pre-flight validation on the server field**: if left blank,
   iperf3 itself fails fast with an `iperf3: error - ...` line, which the
   failure-keyword mechanism below already turns into a red "FAILED"
   status — no separate validation needed.
7. **Failure detection**: `failureKeyword()` → `"iperf3: error"`, matching
   iperf3's own error-line prefix (e.g. connection refused, unresolvable
   host, invalid args). A clean completed run shows green "PASSED"; any
   `iperf3: error` line seen shows red "FAILED".
8. **Duration handling**: `testDurationMs()` stays at the base class's
   default (`null` — no auto-stop timer). iperf3's own `-t` flag already
   bounds the run; the base class's Stop button remains the independent
   manual-abort path (satisfying the "every long test needs a Stop
   button" requirement on its own, regardless of `-t`).

## Architecture

- `app/src/main/jniLibs/arm64-v8a/libiperf3.so` — copied from the
  user-supplied `EloTestApp/iperf3` binary (git-tracked binary asset,
  same as the existing `libmemtester.so`/`libqmesa64.so`).
- New `modules/ethernet/IperfActivity.java extends NativeToolTestActivity`.
  Implements: `soName()` → `"libiperf3.so"`; `buildArgs()`; `failureKeyword()`
  → `"iperf3: error"`; `description()`; `buildExtraControls()` (server
  EditText, direction RadioGroup, interval/window/time EditTexts);
  `setExtraControlsEnabled(boolean)` (disables all five controls while
  running, matching Memtester's lock-while-running behavior). No override
  of `testDurationMs()` (keeps the `null` default).
- `modules/ethernet/EthernetActivity.java` — add one new
  `addSectionTitle`/`addInfo`/`addButton` block at the end of `buildUi()`,
  mirroring `DdrActivity`'s Memtester/QMESA launcher-button sections.
- `AndroidManifest.xml` — one new
  `<activity android:name=".modules.ethernet.IperfActivity" />` line.
- `core/TestModule.java` — **not** modified (no new grid tile).

## `IperfActivity` control-to-argument mapping

| Control | Type | Default when blank | Maps to |
|---|---|---|---|
| Server | `EditText` (text) | *(required, no default)* | `-c <value>` |
| Direction | `RadioGroup` (Download/Upload) | Download checked | `-R` present iff Download |
| Interval | `EditText` (number) | `1` | `-i <value>` |
| Window size | `EditText` (number, MB) | `16` (Download) / `8` (Upload) | `-w <value>M` |
| Time | `EditText` (number, seconds) | `100` | `-t <value>` |

`buildArgs()` reads all five fresh on each Start press and assembles:
`-c <server> -t <time> -i <interval> -w <window>M` plus `-R` when
Download is selected.

## Error handling summary

| Condition | Behavior |
|---|---|
| Server field blank/unreachable | iperf3 emits `iperf3: error - ...`; failure-keyword match shows red "FAILED" |
| Numeric field left blank | Falls back to its stated default (see table above) |
| Numeric field has invalid (non-numeric) text | Same `NumberFormatException`-guarded fallback-to-default pattern already used by Memtester's duration field |
| Device is not arm64-v8a | `NativeToolTestActivity`'s existing `NativeProcessRunner.isArm64Supported()` gate disables Start with an explanatory message (inherited for free, no new code) |
| User presses Stop mid-run | `NativeProcessRunner.stop()` kills the process (inherited for free) |

## Out of scope (explicitly excluded)

- A structured result box (Target/throughput/etc.) — log-based display only,
  per decision 3.
- Two independent Start/Stop button pairs — replaced by the single-page
  RadioGroup-direction design, per decision 2.
- `-p` (port) configurability — not requested; iperf3's default port
  (5201) is used implicitly.
- JSON (`-J`) output parsing — plain-text log display only.
- Any modification to `NativeToolTestActivity`, `core/TestModule.java`, or
  the Level-1 module grid.

## Testing

No automated test suite exists in this project (per `CLAUDE.md` and the
existing Ethernet module spec). Manual verification plan: build+install on
a device with a working Ethernet connection and a reachable iperf3 server,
enter the server address, run a Download test and an Upload test, confirm
the log shows per-interval throughput and a final PASSED summary; then
test an unreachable server address and confirm a red FAILED status
appears with the `iperf3: error` line visible in the log.
