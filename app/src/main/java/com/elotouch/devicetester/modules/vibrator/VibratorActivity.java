package com.elotouch.devicetester.modules.vibrator;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

/**
 * Vibrator module (PRD §3.5): continuous vibration and rhythmic intermittent vibration.
 */
public class VibratorActivity extends BaseTestActivity {

    private Vibrator vibrator;

    private EditText vibrateSecEdit;
    private EditText intervalSecEdit;
    private EditText countEdit;
    private TextView countInfo;
    private Button rhythmButton;

    private final Runnable rhythmStep = this::rhythmTick;

    private boolean rhythmRunning;
    private long vibrateMs;
    private long intervalMs;
    private int totalCount;
    private int doneCount;

    @Override
    protected String title() {
        return "Vibrator 振动马达测试";
    }

    @Override
    protected void buildUi() {
        vibrator = getVibrator();

        addSectionTitle("Continuous Vibration / 持续振动");
        addInfo("Motor vibrates continuously for ~2.5s; judge by feel.\n"
                + "马达连续振动约 2.5 秒，凭体感判断马达是否正常。");
        addButton("Vibrate 2.5s / 持续振动 2.5 秒", () -> {
            if (vibrator != null) {
                vibrator.vibrate(VibrationEffect.createOneShot(2500, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        });

        addSectionTitle("Rhythmic Vibration / 间歇节奏振动");
        addInfo("Buzz for the set time, pause for the set interval, repeat the set number of times.\n"
                + "按设定的震动时间震动、间隔时间停止，重复设定的次数，测试马达启停响应速度。");

        addInfo("Vibration time (s) / 震动时间（秒）");
        vibrateSecEdit = addNumberInput("2");
        addInfo("Interval (s) / 震动间隔（秒）");
        intervalSecEdit = addNumberInput("2");
        addInfo("Count / 测试次数");
        countEdit = addNumberInput("10");

        countInfo = addInfo("");
        updateCountInfo();

        rhythmButton = addButton("Start Test / 开始测试", this::toggleRhythm);
    }

    private EditText addNumberInput(String defaultText) {
        EditText et = new EditText(this);
        et.setText(defaultText);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        addView(et);
        return et;
    }

    private void toggleRhythm() {
        if (rhythmRunning) {
            stopRhythm();
            return;
        }

        double vibrateSec = parseSeconds(vibrateSecEdit);
        double intervalSec = parseSeconds(intervalSecEdit);
        int count = parseCount(countEdit);
        if (Double.isNaN(vibrateSec) || vibrateSec <= 0
                || Double.isNaN(intervalSec) || intervalSec < 0
                || count < 1) {
            toast("请输入有效的时间和次数 / Please enter valid time and count");
            return;
        }

        vibrateMs = Math.round(vibrateSec * 1000);
        intervalMs = Math.round(intervalSec * 1000);
        totalCount = count;
        doneCount = 0;
        rhythmRunning = true;
        rhythmButton.setText("Stop Test / 停止测试");
        updateCountInfo();
        main.post(rhythmStep);
    }

    /** One step of the rhythm: vibrate, then schedule the next step (or the end). */
    private void rhythmTick() {
        if (doneCount >= totalCount) {
            stopRhythm();
            return;
        }
        doneCount++;
        vibrator.vibrate(VibrationEffect.createOneShot(vibrateMs, VibrationEffect.DEFAULT_AMPLITUDE));
        updateCountInfo();
        main.postDelayed(rhythmStep, doneCount < totalCount ? vibrateMs + intervalMs : vibrateMs);
    }

    private void stopRhythm() {
        main.removeCallbacks(rhythmStep);
        cancel();
        rhythmRunning = false;
        if (rhythmButton != null) rhythmButton.setText("Start Test / 开始测试");
    }

    private void updateCountInfo() {
        int total = rhythmRunning ? totalCount : parseCountOrZero(countEdit);
        countInfo.setText("当前测试次数 / Current count: " + doneCount + " / " + total);
    }

    private static double parseSeconds(EditText et) {
        try {
            return Double.parseDouble(et.getText().toString().trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static int parseCount(EditText et) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseCountOrZero(EditText et) {
        return Math.max(0, parseCount(et));
    }

    private Vibrator getVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm != null ? vm.getDefaultVibrator() : null;
        } else {
            return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
    }

    private void cancel() {
        if (vibrator != null) vibrator.cancel();
    }

    @Override
    protected void onStopTests() {
        stopRhythm();
    }
}
