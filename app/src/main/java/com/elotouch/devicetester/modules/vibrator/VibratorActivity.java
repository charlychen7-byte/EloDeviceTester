package com.elotouch.devicetester.modules.vibrator;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import com.elotouch.devicetester.core.BaseTestActivity;

/**
 * Vibrator module (PRD §3.5): continuous vibration and rhythmic waveform.
 */
public class VibratorActivity extends BaseTestActivity {

    private Vibrator vibrator;

    @Override
    protected String title() {
        return "Vibrator / 振动马达测试";
    }

    @Override
    protected void buildUi() {
        vibrator = getVibrator();

        addSectionTitle("持续振动测试");
        addInfo("马达连续振动约 2.5 秒，凭体感判断马达是否正常。");
        addButton("持续振动 2.5 秒", () -> {
            if (vibrator != null) {
                vibrator.vibrate(VibrationEffect.createOneShot(2500, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        });

        addSectionTitle("间歇 / 节奏振动测试");
        addInfo("按\"震-停-震-停\"波形短震，测试马达启停响应速度。");
        addButton("节奏振动", () -> {
            if (vibrator != null) {
                long[] timings = {0, 200, 150, 200, 150, 400};
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1));
            }
        });

        addButton("停止振动", this::cancel);
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
        cancel();
    }
}
