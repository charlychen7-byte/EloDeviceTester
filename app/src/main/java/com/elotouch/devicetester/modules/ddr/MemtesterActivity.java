package com.elotouch.devicetester.modules.ddr;

import android.app.ActivityManager;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;

/**
 * Runs the open-source memtester binary (PRD DDR §3.1 native-tool addendum)
 * against a user-selected memory size (1/4 of total RAM or 80% of available
 * RAM), for a user-selected duration in minutes (blank = run until stopped).
 */
public class MemtesterActivity extends NativeToolTestActivity {

    private RadioButton quarterTotalRadio;
    private RadioButton avail80Radio;
    private EditText durationMinutesInput;

    @Override
    protected String title() {
        return "Memtester";
    }

    @Override
    protected void buildExtraControls() {
        addSectionTitle("Test Size / 测试内存大小");
        quarterTotalRadio = new RadioButton(this);
        quarterTotalRadio.setId(View.generateViewId());
        quarterTotalRadio.setText("1/4 Total RAM / 1/4 总内存");
        avail80Radio = new RadioButton(this);
        avail80Radio.setId(View.generateViewId());
        avail80Radio.setText("80% Available RAM / 80% 可用内存");
        RadioGroup sizeGroup = new RadioGroup(this);
        sizeGroup.setOrientation(RadioGroup.VERTICAL);
        sizeGroup.addView(quarterTotalRadio);
        sizeGroup.addView(avail80Radio);
        sizeGroup.check(quarterTotalRadio.getId());
        addView(sizeGroup);

        addSectionTitle("Duration in minutes / 测试时长（分钟）");
        durationMinutesInput = new EditText(this);
        durationMinutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        durationMinutesInput.setHint("Blank = run until stopped / 留空表示无限运行");
        addView(durationMinutesInput);
    }

    @Override
    protected String soName() {
        return "libmemtester.so";
    }

    @Override
    protected String[] buildArgs() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long sizeBytes = avail80Radio.isChecked()
                ? (long) (mi.availMem * 0.8)
                : mi.totalMem / 4;
        long sizeMb = sizeBytes / (1024 * 1024);
        return new String[]{sizeMb + "M"};
    }

    @Override
    protected Long testDurationMs() {
        String text = durationMinutesInput.getText().toString().trim();
        if (text.isEmpty()) return null;
        try {
            long minutes = Long.parseLong(text);
            return minutes > 0 ? minutes * 60_000L : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    protected String failureKeyword() {
        return "FAILURE";
    }

    @Override
    protected String description() {
        return "Runs the open-source memtester binary against the memory size and duration "
                + "selected below (leave duration blank to run until stopped).\n"
                + "运行开源 memtester 工具，测试容量与时长见下方选择"
                + "（时长留空则无限运行直到点击停止）。";
    }
}
