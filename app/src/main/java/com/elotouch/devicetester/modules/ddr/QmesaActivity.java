package com.elotouch.devicetester.modules.ddr;

import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the vendor QMESA memory stress tool (PRD DDR §3.1 native-tool addendum).
 *
 * <p>The page renders the command line itself: the executable name followed by
 * one row per argument, where each argument's value is an {@link EditText}
 * pre-filled with its default. {@link #buildArgs()} reads those fields back, so
 * whatever is on screen is what gets executed.
 *
 * <p>The bundled libqmesa64.so is a static glibc binary, not a bionic build, so
 * it is binary-patched to skip the {@code set_robust_list} syscall that Android's
 * app seccomp filter kills with SIGSYS. Any re-drop of the vendor binary must be
 * re-patched or this test dies instantly with "Killed by OS (signal 31)" — see
 * docs/vendor/qmesa-seccomp-patch.md.
 */
public class QmesaActivity extends NativeToolTestActivity {

    /** Shown above the argument rows so the page reads as one command line. */
    private static final String EXE_LABEL = "libqmesa64.so";

    private final List<String> argFlags = new ArrayList<>();
    private final List<EditText> argValueInputs = new ArrayList<>();
    private GridLayout argGrid;
    private int argRow;

    @Override
    protected String title() {
        return "QMESA";
    }

    @Override
    protected void buildExtraControls() {
        addSectionTitle("Command / 执行指令");
        TextView exe = addInfo("qmesa64");
        exe.setTypeface(Typeface.MONOSPACE);

        argGrid = new GridLayout(this);
        argGrid.setColumnCount(2);
        addArg("-startSize", "8MB", InputType.TYPE_CLASS_TEXT);
        addArg("-endSize", "8MB", InputType.TYPE_CLASS_TEXT);
        addArg("-totalSize", "16MB", InputType.TYPE_CLASS_TEXT);
        addArg("-errorCheck", "T", InputType.TYPE_CLASS_TEXT);
        addArg("-secs", "14400", InputType.TYPE_CLASS_NUMBER);
        addArg("-numThreads", "4", InputType.TYPE_CLASS_NUMBER);
        addView(argGrid);
    }

    /** Appends one "&lt;flag&gt; [value]" row to {@link #argGrid}. */
    private void addArg(String flag, String defaultValue, int inputType) {
        TextView label = new TextView(this);
        label.setText(flag);
        label.setTextSize(15);
        label.setTypeface(Typeface.MONOSPACE);
        GridLayout.LayoutParams labelParams = new GridLayout.LayoutParams(
                GridLayout.spec(argRow), GridLayout.spec(0));
        labelParams.setGravity(Gravity.CENTER_VERTICAL);
        labelParams.rightMargin = dp(12);
        argGrid.addView(label, labelParams);

        EditText input = new EditText(this);
        input.setText(defaultValue);
        input.setInputType(inputType);
        input.setTextSize(15);
        input.setTypeface(Typeface.MONOSPACE);
        input.setSingleLine(true);
        // Weight 1 on the value column so every input box lines up at the same
        // x and stretches to the page width, whatever the flag names measure.
        GridLayout.LayoutParams inputParams = new GridLayout.LayoutParams(
                GridLayout.spec(argRow), GridLayout.spec(1, 1f));
        inputParams.width = 0;
        inputParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        argGrid.addView(input, inputParams);

        argRow++;
        argFlags.add(flag);
        argValueInputs.add(input);
    }

    @Override
    protected void setExtraControlsEnabled(boolean enabled) {
        for (EditText input : argValueInputs) input.setEnabled(enabled);
    }

    @Override
    protected String soName() {
        return "libqmesa64.so";
    }

    @Override
    protected String[] buildArgs() {
        List<String> args = new ArrayList<>();
        for (int i = 0; i < argFlags.size(); i++) {
            String value = argValueInputs.get(i).getText().toString().trim();
            // A cleared field drops the argument entirely, letting QMESA fall
            // back to its own built-in default for it.
            if (value.isEmpty()) continue;
            args.add(argFlags.get(i));
            args.add(value);
        }
        return args.toArray(new String[0]);
    }

    @Override
    protected String failureKeyword() {
        return "FAILED";
    }

    @Override
    protected String description() {
        return "Runs the vendor QMESA stress tool with the command shown below. Edit any "
                + "argument before starting; clear a field to leave that argument off.\n"
                + "运行厂商 QMESA 压力测试工具，执行下方显示的指令。开始前可修改任意参数；"
                + "清空某个输入框则不传该参数。";
    }
}
