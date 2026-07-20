package com.elotouch.devicetester.modules.ethernet;

import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.elotouch.devicetester.modules.ddr.NativeToolTestActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the bundled iperf3 client against a user-specified server, measuring
 * download (-R) or upload throughput per the selected direction.
 */
public class IperfActivity extends NativeToolTestActivity {

    private EditText serverInput;
    private RadioButton downloadRadio;
    private RadioButton uploadRadio;
    private EditText intervalInput;
    private EditText windowInput;
    private EditText timeInput;

    @Override
    protected String title() {
        return "iperf3";
    }

    @Override
    protected void buildExtraControls() {
        addSectionTitle("Server / 服务器地址");
        serverInput = new EditText(this);
        serverInput.setInputType(InputType.TYPE_CLASS_TEXT);
        serverInput.setHint("iperf3 server IP/hostname / 服务器 IP 或主机名");
        addView(serverInput);

        addSectionTitle("Direction / 测试方向");
        downloadRadio = new RadioButton(this);
        downloadRadio.setId(View.generateViewId());
        downloadRadio.setText("Download / 下载");
        uploadRadio = new RadioButton(this);
        uploadRadio.setId(View.generateViewId());
        uploadRadio.setText("Upload / 上传");
        RadioGroup directionGroup = new RadioGroup(this);
        directionGroup.setOrientation(RadioGroup.VERTICAL);
        directionGroup.addView(downloadRadio);
        directionGroup.addView(uploadRadio);
        directionGroup.check(downloadRadio.getId());
        addView(directionGroup);

        addSectionTitle("Interval (s) / 报告间隔（秒）");
        intervalInput = new EditText(this);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalInput.setHint("Blank = 1 / 留空默认 1");
        addView(intervalInput);

        addSectionTitle("Window size (MB) / 窗口大小（MB）");
        windowInput = new EditText(this);
        windowInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        windowInput.setHint("Blank = 16 (download) / 8 (upload) / 留空默认下载16、上传8");
        addView(windowInput);

        addSectionTitle("Time (s) / 测试时长（秒）");
        timeInput = new EditText(this);
        timeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeInput.setHint("Blank = 100 / 留空默认 100");
        addView(timeInput);
    }

    @Override
    protected void setExtraControlsEnabled(boolean enabled) {
        serverInput.setEnabled(enabled);
        downloadRadio.setEnabled(enabled);
        uploadRadio.setEnabled(enabled);
        intervalInput.setEnabled(enabled);
        windowInput.setEnabled(enabled);
        timeInput.setEnabled(enabled);
    }

    @Override
    protected String soName() {
        return "libiperf3.so";
    }

    @Override
    protected String[] buildArgs() {
        boolean download = downloadRadio.isChecked();
        String server = serverInput.getText().toString().trim();
        long interval = parseOrDefault(intervalInput, 1);
        long window = parseOrDefault(windowInput, download ? 16 : 8);
        long time = parseOrDefault(timeInput, 100);

        List<String> args = new ArrayList<>();
        args.add("-c");
        args.add(server);
        args.add("-t");
        args.add(String.valueOf(time));
        args.add("-i");
        args.add(String.valueOf(interval));
        args.add("-w");
        args.add(window + "M");
        if (download) {
            args.add("-R");
        }
        return args.toArray(new String[0]);
    }

    private static long parseOrDefault(EditText input, long defaultValue) {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return defaultValue;
        try {
            long value = Long.parseLong(text);
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Override
    protected String failureKeyword() {
        return "iperf3: error";
    }

    @Override
    protected String description() {
        return "Runs the bundled iperf3 client against the server and settings below to "
                + "measure download or upload throughput.\n"
                + "运行内置 iperf3 客户端，根据下方服务器地址与参数测量下载或上传吞吐量。";
    }
}
