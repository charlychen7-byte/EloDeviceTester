# Ethernet Icon Swap + Native-Tool Log Box Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Swap the Ethernet module's tile emoji (fixes a tofu-box render
confirmed on real hardware), and give the native-tool log box (shared by
Memtester, QMESA, and iperf3) a fixed height, independent scrolling, and a
200-line history cap, per
`docs/superpowers/specs/2026-07-20-ethernet-icon-log-box-design.md`.

**Architecture:** One-line emoji change in `core/TestModule.java`. The log
box fix lives entirely in `modules/ddr/NativeToolTestActivity.java` — the
single shared base class behind all three native-tool test pages — so
fixing it there applies uniformly without touching
`MemtesterActivity`/`QmesaActivity`/`IperfActivity` themselves.

**Tech Stack:** Java, Android SDK (`ScrollView`, `LinearLayout.LayoutParams`),
no new dependencies.

## Global Constraints

- Java only, no Kotlin. Minimum SDK 26.
- No automated test suite exists in this project. Verification is
  `./gradlew assembleDebug` succeeding, plus manual on-device checks.
- Do not modify `MemtesterActivity.java`, `QmesaActivity.java`, or
  `IperfActivity.java` — the log box fix is additive/internal to
  `NativeToolTestActivity` only.
- The existing per-line character truncation
  (`MAX_DISPLAY_LINE_LENGTH = 200`, chars-per-line) is a different,
  unrelated limit from the new 200-line history cap — do not conflate or
  change it.

---

### Task 1: Swap Ethernet tile emoji

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/core/TestModule.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Change the emoji**

In `TestModule.java`, change:

```java
    ETHERNET("Ethernet / 有线网络", "🖧", EthernetActivity.class, HardwareDetector.Feature.ALWAYS),
```

to:

```java
    ETHERNET("Ethernet / 有线网络", "🔗", EthernetActivity.class, HardwareDetector.Feature.ALWAYS),
```

- [ ] **Step 2: Build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Manual verification**

Install on a device (`./gradlew installDebug`), open the app, scroll to
the Ethernet tile, confirm it now shows 🔗 (a link/chain glyph) instead
of a tofu box or the old 🖧.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/core/TestModule.java
git commit -m "fix: swap Ethernet tile emoji to fix tofu-box rendering on real devices"
```

---

### Task 2: Fixed-height, scrollable, 200-line native-tool log box

**Files:**
- Modify: `app/src/main/java/com/elotouch/devicetester/modules/ddr/NativeToolTestActivity.java`

**Interfaces:**
- Consumes: `BaseTestActivity.content` (protected `LinearLayout` field,
  inherited), `BaseTestActivity.dp(int)` (inherited).
- Produces: nothing consumed by later tasks — this is the last task in
  this plan.

- [ ] **Step 1: Add imports**

Add these imports to `NativeToolTestActivity.java`, alongside the
existing `android.graphics.*`/`android.widget.*` imports:

```java
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
```

- [ ] **Step 2: Add a `logScroll` field next to the existing `logText` field**

Change:

```java
    private TextView statusText;
    private TextView logText;
    private Button startButton;
    private Button stopButton;
```

to:

```java
    private TextView statusText;
    private ScrollView logScroll;
    private TextView logText;
    private Button startButton;
    private Button stopButton;
```

- [ ] **Step 3: Replace the log-creation block in `buildUi()`**

Change this block:

```java
        logText = addInfo("");
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setBackgroundColor(Color.BLACK);
        logText.setTextColor(Color.WHITE);
```

to:

```java
        logText = new TextView(this);
        logText.setTextSize(15);
        logText.setLineSpacing(dp(2), 1f);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setBackgroundColor(Color.BLACK);
        logText.setTextColor(Color.WHITE);

        logScroll = new ScrollView(this);
        logScroll.addView(logText, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams logScrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(240));
        logScrollParams.topMargin = dp(6);
        logScroll.setLayoutParams(logScrollParams);
        content.addView(logScroll);
```

(The rest of `buildUi()` — the arm64-support check that follows — is
unchanged; it only touches `startButton`/`statusText`, not the log.)

- [ ] **Step 4: Raise the line cap to 200 and auto-scroll on new lines**

In `flushPendingLines()`, change:

```java
        for (String l : toAppend) {
            if (lastLines.size() >= 10) lastLines.removeFirst();
            lastLines.addLast(truncateForDisplay(l));
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lastLines) sb.append(l).append('\n');
        logText.setText(sb.toString());
```

to:

```java
        for (String l : toAppend) {
            if (lastLines.size() >= 200) lastLines.removeFirst();
            lastLines.addLast(truncateForDisplay(l));
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lastLines) sb.append(l).append('\n');
        logText.setText(sb.toString());
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
```

- [ ] **Step 5: Build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Manual verification**

Install on a device (`./gradlew installDebug`). Open the Memtester page
(DDR module → Memtester "Enter Test") or the iperf3 page (Ethernet
module → iperf3 "Enter Test") and press Start.

- Confirm the log area now has a fixed height (~240dp, roughly 12 lines
  tall) with a visible scrollbar, rather than growing with the page.
- Let it run long enough to produce more than ~12 lines of output;
  confirm newest lines stay visible automatically (auto-scroll) without
  the user needing to manually scroll down.
- Manually scroll up within the log box; confirm earlier lines (up to
  200 total retained) are still there and the rest of the page (status
  line, Start/Stop buttons) does not move — only the log box itself
  scrolls.
- Stop the test; confirm the log box still displays correctly and
  remains independently scrollable with the final output.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/elotouch/devicetester/modules/ddr/NativeToolTestActivity.java
git commit -m "fix: make native-tool log box fixed-height, scrollable, 200-line cap"
```
