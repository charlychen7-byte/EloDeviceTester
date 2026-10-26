package com.elotouch.devicetester.modules.ddr;

import android.app.ActivityManager;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.elotouch.devicetester.modules.cpu.CpuLoadTester;

/**
 * Runs the open-source memtester binary (PRD DDR §3.1 native-tool addendum)
 * against a user-selected memory size (1/4 of total RAM or 80% of available
 * RAM), for a user-selected duration in minutes (blank = run until stopped).
 * An optional checkbox also drives every CPU core to full load for the run,
 * and the memtester output is saved line by line to a log file.
 */
public class MemtesterActivity extends NativeToolTestActivity {

    private RadioButton quarterTotalRadio;
    private RadioButton avail80Radio;
    private EditText durationMinutesInput;
    private CheckBox fullCpuLoadCheckBox;

    private final CpuLoadTester cpuLoad = new CpuLoadTester();

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

        addSectionTitle("CPU Load / CPU 负载");
        fullCpuLoadCheckBox = new CheckBox(this);
        fullCpuLoadCheckBox.setText("Full CPU load during test / 测试时 CPU 满载");
        addView(fullCpuLoadCheckBox);
    }

    @Override
    protected void setExtraControlsEnabled(boolean enabled) {
        quarterTotalRadio.setEnabled(enabled);
        avail80Radio.setEnabled(enabled);
        durationMinutesInput.setEnabled(enabled);
        fullCpuLoadCheckBox.setEnabled(enabled);
    }

    @Override
    protected void onRunStarted() {
        if (fullCpuLoadCheckBox.isChecked()) cpuLoad.start(100, null);
    }

    @Override
    protected void onRunFinished() {
        cpuLoad.stop();
    }

    @Override
    protected void onStopTests() {
        super.onStopTests();
        cpuLoad.stop();
    }

    @Override
    protected String logFilePrefix() {
        return "memtester";
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
                + "selected below (leave duration blank to run until stopped). Output is saved "
                + "to a log file in the app's external files folder.\n"
                + "运行开源 memtester 工具，测试容量与时长见下方选择"
                + "（时长留空则无限运行直到点击停止）。输出会实时保存到应用外部文件目录下的日志文件中。";
    }
}
