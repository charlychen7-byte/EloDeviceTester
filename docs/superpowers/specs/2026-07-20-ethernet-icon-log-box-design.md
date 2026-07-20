# Ethernet Icon Swap + Native-Tool Log Box Fix Design

Date: 2026-07-20

## Purpose

Two small, unrelated UI fixes requested after real-device verification of the
Ethernet module:

1. The Ethernet module tile's emoji (🖧) renders as a tofu box on real Elo
   hardware (Android 14, BP6490_3COM) — confirmed during verification.
   Replace it with 🔗.
2. The rolling output log used by native-tool test pages (Memtester, QMESA,
   and the new iperf3 test) currently caps at 10 lines and grows/shrinks
   the whole page's scroll region with it. Make it a fixed-height,
   independently-scrollable box holding up to 200 lines.

## Decisions

1. **Icon**: `core/TestModule.java`'s `ETHERNET` entry emoji changes from
   `"🖧"` to `"🔗"`. One-line change, no other module affected.
2. **Scope of the log fix**: `modules/ddr/NativeToolTestActivity.java` is
   the single shared base class behind all three native-tool test pages
   (`MemtesterActivity`, `QmesaActivity`, `IperfActivity`) — fixing it
   there applies uniformly to all three, per the request.
3. **Fixed height**: 240dp (~12 visible lines at the log's existing
   monospace text size), chosen to give a reasonable live-output view
   without crowding out the rest of the page's controls.
4. **Scrolling**: the log `TextView` is wrapped in its own `ScrollView`
   with that fixed height, added directly via the page's `content`
   `LinearLayout` (bypassing `BaseTestActivity.addView()`, which forces
   `WRAP_CONTENT` height) — so the log scrolls independently of the
   surrounding page.
5. **Line cap**: raised from the current hardcoded 10 to 200
   (`lastLines.size() >= 200`). The existing per-line character
   truncation (`MAX_DISPLAY_LINE_LENGTH = 200`, a different, unrelated
   limit on how many characters of one long line are shown) is
   unchanged.
6. **Auto-scroll**: the log's `ScrollView` auto-scrolls to the bottom
   whenever a new line is appended, so live output stays visible by
   default while the user can still scroll up to review history.

## Out of scope

- No change to `MemtesterActivity`, `QmesaActivity`, or `IperfActivity`
  themselves — the fix lives entirely in the shared base class.
- No change to the per-line character truncation limit.
- No change to any other module's icon or log-like UI.

## Testing

No automated test suite exists in this project. Manual verification:
build + install, confirm the Ethernet tile shows 🔗 (not a tofu box) on a
real device, and confirm on the Memtester (or iperf3) page that the log
box has a fixed height with its own scrollbar, holds more than 10 lines
of history when scrolled up, and auto-scrolls to the newest line during
a live run.
