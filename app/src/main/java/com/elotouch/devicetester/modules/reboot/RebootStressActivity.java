package com.elotouch.devicetester.modules.reboot;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;

/**
 * Reboot stress module: reboots the device a configured number of times,
 * timing each boot and re-checking the selected peripherals after every one.
 *
 * <p>This page is only a viewer and a control panel —
 * {@link RebootStressService} owns the run, and {@link RebootStressState}
 * holds it across the reboots. Leaving the page (or the reboot itself) does
 * not interrupt the test; only Stop does. That inverts the usual
 * {@code BaseTestActivity} contract, so {@link #onStopTests()} deliberately
 * only cancels this page's refresh polling.
 *
 * <p>Per the design, the list on screen is just the previous and the current
 * cycle. Every cycle ever run is in the CSV log whose path is shown at the
 * bottom.
 */
public class RebootStressActivity extends BaseTestActivity {

    private static final int PASS_COLOR = Color.parseColor("#2E7D32");
    private static final int FAIL_COLOR = Color.parseColor("#C62828");
    private static final int MUTED_COLOR = Color.parseColor("#757575");

    private static final int MAX_TARGET = 10_000;
    private static final int MIN_DWELL_SEC = 10;
    private static final int MAX_DWELL_SEC = 3600;
    private static final int MIN_BOOT_LIMIT_SEC = 10;
    private static final int MAX_BOOT_LIMIT_SEC = 3600;

    private RebootStressState state;

    private TextView ownerText;
    private EditText targetInput;
    private EditText dwellInput;
    private EditText bootLimitInput;
    private final Map<RebootChecks.Check, CheckBox> checkBoxes =
            new EnumMap<>(RebootChecks.Check.class);
    private Button startButton;
    private Button stopButton;

    private TextView previousHeadline;
    private TextView previousChecks;
    private TextView currentHeadline;
    private TextView currentChecks;
    private TextView currentNote;
    private TextView countdownText;
    private TextView summaryText;
    private TextView logPathText;
    private TextView errorText;

    private int defaultTextColor;
    private boolean polling;

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            if (!polling) return;
            render();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected String title() {
        return "Reboot Stress 重启压力测试";
    }

    @Override
    protected void buildUi() {
        state = new RebootStressState(this);
        if (state.isRunning()) showOverLockScreen();

        addSectionTitle("Device Owner 设备所有者");
        ownerText = addInfo("");
        defaultTextColor = ownerText.getCurrentTextColor();

        addSectionTitle("Parameters 测试参数");
        targetInput = addNumberField("Reboot count 重启次数",
                state.target(), RebootStressState.DEFAULT_TARGET);
        dwellInput = addNumberField("Dwell after boot 开机后停留（秒）",
                state.dwellSec(), RebootStressState.DEFAULT_DWELL_SEC);
        bootLimitInput = addNumberField("Boot time limit 开机耗时上限（秒）",
                state.bootLimitSec(), RebootStressState.DEFAULT_BOOT_LIMIT_SEC);

        addSectionTitle("Per-boot checks 每轮自检项");
        EnumSet<RebootChecks.Check> restored = restoredChecks();
        for (RebootChecks.Check check : RebootChecks.Check.values()) {
            CheckBox box = new CheckBox(this);
            boolean supported = RebootChecks.isSupported(this, check);
            if (supported) {
                box.setText(check.label);
                box.setChecked(restored.contains(check));
            } else {
                // PRD 5.3: never offer a test the hardware cannot satisfy.
                box.setText(check.label + " — hardware not supported 硬件不支持");
                box.setEnabled(false);
                box.setChecked(false);
            }
            addView(box);
            checkBoxes.put(check, box);
        }

        startButton = addButton("Start 开始", this::onStartClicked);
        stopButton = addButton("Stop 停止", this::onStopClicked);

        addSectionTitle("Previous cycle 上一轮");
        previousHeadline = addInfo("—");
        previousChecks = addInfo("");

        addSectionTitle("Current cycle 本轮");
        currentHeadline = addInfo("—");
        currentChecks = addInfo("");
        currentNote = addInfo("");
        countdownText = addInfo("");

        addSectionTitle("Summary 汇总");
        summaryText = addInfo("");
        errorText = addInfo("");
        errorText.setTextColor(FAIL_COLOR);

        addSectionTitle("Log 日志文件");
        logPathText = addInfo("尚未开始 / not started");
        logPathText.setTextSize(13);
        logPathText.setTextColor(MUTED_COLOR);

        render();
    }

    /**
     * Wakes the screen and shows this page over the lock screen, so a run
     * resumed by {@link RebootStressService} after a boot is actually visible
     * rather than sitting behind a dark or locked display.
     *
     * <p>Applied only while a run is armed: opening the module by hand should
     * not behave like an alarm clock.
     */
    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            // API 26 has no setter pair; the window flags are the equivalent.
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        polling = true;
        main.post(refreshTick);
    }

    /**
     * Only stops this page refreshing. The run itself must survive the
     * operator leaving the page — and must survive the reboot it is about to
     * trigger — so it is never torn down here.
     */
    @Override
    protected void onStopTests() {
        polling = false;
        main.removeCallbacks(refreshTick);
    }

    // ------------------------------------------------------------------ controls

    private void onStartClicked() {
        if (state.isRunning()) {
            toast("测试已在运行 / already running");
            return;
        }
        if (!isDeviceOwner()) {
            toast("需要先设为设备所有者 / device owner required");
            return;
        }

        final Integer target = readField(targetInput, 1, MAX_TARGET, "重启次数 reboot count");
        if (target == null) return;
        final Integer dwellSec = readField(dwellInput, MIN_DWELL_SEC, MAX_DWELL_SEC,
                "停留秒数 dwell seconds");
        if (dwellSec == null) return;
        final Integer bootLimitSec = readField(bootLimitInput,
                MIN_BOOT_LIMIT_SEC, MAX_BOOT_LIMIT_SEC, "开机耗时上限 boot limit");
        if (bootLimitSec == null) return;

        final EnumSet<RebootChecks.Check> checks = selectedChecks();

        // The foreground notification carries the countdown and is the only
        // status the operator sees once the page is gone, so ask for
        // notification rights lazily here (PRD 5.4). The run proceeds either
        // way — a suppressed notification does not stop the service.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requirePermission(Manifest.permission.POST_NOTIFICATIONS,
                    () -> beginRun(target, dwellSec, bootLimitSec, checks),
                    () -> beginRun(target, dwellSec, bootLimitSec, checks));
        } else {
            beginRun(target, dwellSec, bootLimitSec, checks);
        }
    }

    private void beginRun(int target, int dwellSec, int bootLimitSec,
                          EnumSet<RebootChecks.Check> checks) {
        // File creation off the UI thread (PRD 5.5), then arm the run and hand
        // over to the service from the UI thread.
        runAsync(() -> {
            String logPath;
            try {
                logPath = RebootStressLog.create(this, target, dwellSec, bootLimitSec, checks);
            } catch (Exception e) {
                ui(() -> toast("无法创建日志文件 / cannot create log file: " + e.getMessage()));
                return;
            }
            state.startRun(target, dwellSec, bootLimitSec, checks, logPath);
            ui(() -> {
                RebootStressService.startRun(this);
                toast(String.format(Locale.US,
                        "%d 秒后开始第 1 次重启 / first reboot in %ds", dwellSec, dwellSec));
                render();
            });
        });
    }

    private void onStopClicked() {
        if (!state.isRunning()) {
            toast("测试未在运行 / not running");
            return;
        }
        RebootStressService.stopRun(this);
        toast("已停止，开机后不再自动重启 / stopped, no further reboots");
        render();
    }

    // ------------------------------------------------------------------ rendering

    private void render() {
        boolean running = state.isRunning();
        boolean owner = isDeviceOwner();

        if (owner) {
            ownerText.setText("已是设备所有者，可以触发重启 / device owner, reboot available");
            ownerText.setTextColor(PASS_COLOR);
        } else {
            ownerText.setText("Not device owner — run the following command："
                    + "\nadb shell dpm set-device-owner "
                    + "com.elotouch.devicetester/.modules.reboot.RebootAdminReceiver");
            ownerText.setTextColor(FAIL_COLOR);
        }

        startButton.setEnabled(owner && !running);
        stopButton.setEnabled(running);
        targetInput.setEnabled(!running);
        dwellInput.setEnabled(!running);
        bootLimitInput.setEnabled(!running);
        for (Map.Entry<RebootChecks.Check, CheckBox> entry : checkBoxes.entrySet()) {
            entry.getValue().setEnabled(
                    !running && RebootChecks.isSupported(this, entry.getKey()));
        }

        renderCycle(state.previousCycle(), previousHeadline, previousChecks, null);
        renderCycle(state.currentCycle(), currentHeadline, currentChecks, currentNote);

        int countdown = RebootStressService.secondsToReboot;
        if (running && countdown > 0) {
            countdownText.setText(String.format(Locale.US, "距下次重启 %ds / next reboot in %ds",
                    countdown, countdown));
        } else if (running) {
            int pending = state.pendingCycle();
            countdownText.setText(pending > state.completed()
                    ? "正在重启 / rebooting…" : "运行中 / running…");
        } else if (state.completed() > 0) {
            countdownText.setText(state.completed() >= state.target()
                    ? "测试完成 / finished" : "已停止 / stopped");
        } else {
            countdownText.setText("");
        }

        summaryText.setText(state.runStartedMs() == 0 ? "尚未开始 / not started"
                : state.summaryLine());

        String error = state.lastError();
        errorText.setText(error == null ? "" : error);

        String logPath = state.logPath();
        logPathText.setText(logPath == null ? "尚未开始 / not started" : logPath);
    }

    private void renderCycle(RebootCycle cycle, TextView headline, TextView checks,
                             TextView note) {
        if (cycle == null) {
            headline.setText("—");
            headline.setTextColor(defaultTextColor);
            checks.setText("");
            if (note != null) note.setText("");
            return;
        }
        headline.setText(cycle.headline());
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setTextColor(cycle.pass ? PASS_COLOR : FAIL_COLOR);
        checks.setText(cycle.checkLine());
        if (note != null) note.setText(cycle.note);
    }

    // --------------------------------------------------------------------- input

    private EditText addNumberField(String label, int current, int fallback) {
        addInfo(label);
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(String.valueOf(current > 0 ? current : fallback));
        addView(field);
        return field;
    }

    /** Parses and range-checks a field, toasting and returning null if invalid. */
    private Integer readField(EditText field, int min, int max, String name) {
        String raw = field.getText().toString().trim();
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            toast(name + " 请填写数字 / must be a number");
            return null;
        }
        if (value < min || value > max) {
            toast(String.format(Locale.US, "%s 需在 %d–%d 之间 / must be %d–%d",
                    name, min, max, min, max));
            return null;
        }
        return value;
    }

    private EnumSet<RebootChecks.Check> selectedChecks() {
        EnumSet<RebootChecks.Check> selected = EnumSet.noneOf(RebootChecks.Check.class);
        for (Map.Entry<RebootChecks.Check, CheckBox> entry : checkBoxes.entrySet()) {
            CheckBox box = entry.getValue();
            if (box.isChecked() && RebootChecks.isSupported(this, entry.getKey())) {
                selected.add(entry.getKey());
            }
        }
        return selected;
    }

    /**
     * The previous run's selection, so the operator does not re-tick boxes on
     * every run. First ever run defaults to the two always-present networks;
     * the peripheral checks stay opt-in so an unplugged cash drawer does not
     * fail a run that was not about the cash drawer.
     */
    private EnumSet<RebootChecks.Check> restoredChecks() {
        if (state.runStartedMs() != 0) return state.enabledChecks();
        EnumSet<RebootChecks.Check> defaults = EnumSet.noneOf(RebootChecks.Check.class);
        for (RebootChecks.Check c : new RebootChecks.Check[]{
                RebootChecks.Check.ETHERNET, RebootChecks.Check.WIFI}) {
            if (RebootChecks.isSupported(this, c)) defaults.add(c);
        }
        return defaults;
    }

    private boolean isDeviceOwner() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(getPackageName());
    }
}
