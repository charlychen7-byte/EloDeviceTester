package com.elotouch.devicetester.modules.serial;

import android.content.BroadcastReceiver;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.elotouch.devicetester.core.UsbSerialHelper;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serial port test (PRD §3.15): lists the USB-serial ports currently attached,
 * opens one at a chosen baud rate, sends text typed by the user and shows
 * whatever the peripheral sends back. Built on the standard Android UsbManager
 * plus the open-source usb-serial-for-android library (no vendor SDK). The
 * module entry stays clickable even with nothing attached; ports are detected
 * live on this page per the pluggable-peripheral model (PRD §5.3).
 */
public class SerialActivity extends BaseTestActivity {

    private static final int[] BAUD_RATES = {9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600};
    private static final int DEFAULT_BAUD_INDEX = 4; // 115200
    private static final int READ_TIMEOUT_MS = 200;
    private static final int WRITE_TIMEOUT_MS = 1000;
    private static final int MAX_LOG_CHARS = 8000;

    private final List<UsbSerialDriver> drivers = new ArrayList<>();
    private final StringBuilder log = new StringBuilder();

    private TextView devicesText;
    private ArrayAdapter<String> portAdapter;
    private Spinner portSpinner;
    private Spinner baudSpinner;
    private TextView statusText;
    private EditText sendInput;
    private CheckBox appendCrlf;
    private TextView receiveText;
    private ScrollView receiveScroll;

    private volatile UsbSerialPort openPort;
    private BroadcastReceiver pendingUsbReceiver;

    @Override
    protected String title() {
        return "Serial Port 串口测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Detected Ports / 检测到的串口");
        devicesText = addInfo("");
        portAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<String>());
        portAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        addInfo("Port 串口");
        portSpinner = addSpinner(portAdapter);
        addButton("Refresh / 刷新串口列表", this::refreshDevices);

        addSectionTitle("Connection / 连接设置");
        List<String> bauds = new ArrayList<>();
        for (int baud : BAUD_RATES) bauds.add(String.valueOf(baud));
        addInfo("Baud rate 波特率 (8N1)");
        baudSpinner = addSpinner(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, bauds));
        baudSpinner.setSelection(DEFAULT_BAUD_INDEX);
        statusText = addInfo("Disconnected 未连接");
        addButton("Connect / 连接", this::connectSelected);
        addButton("Apply Baud Rate / 应用波特率", this::applyBaud);
        addButton("Disconnect / 断开", this::disconnect);

        addSectionTitle("Send / 发送");
        sendInput = new EditText(this);
        sendInput.setHint("Text to send 输入要发送的内容");
        addView(sendInput);
        appendCrlf = new CheckBox(this);
        appendCrlf.setText("Append CRLF / 末尾追加回车换行");
        appendCrlf.setChecked(true);
        addView(appendCrlf);
        addButton("Send / 发送", this::sendText);

        addSectionTitle("Receive / 接收");
        receiveText = new TextView(this);
        receiveText.setTextSize(14);
        receiveText.setTypeface(Typeface.MONOSPACE);
        receiveScroll = new ScrollView(this);
        receiveScroll.addView(receiveText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        lp.topMargin = dp(6);
        content.addView(receiveScroll, lp);
        addButton("Clear / 清空接收区", this::clearLog);

        refreshDevices();
    }

    private Spinner addSpinner(ArrayAdapter<String> adapter) {
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(adapter);
        addView(spinner);
        return spinner;
    }

    private void refreshDevices() {
        drivers.clear();
        drivers.addAll(UsbSerialHelper.listDrivers(this));
        List<String> names = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        if (drivers.isEmpty()) {
            sb.append("No serial ports detected. 未检测到串口。");
        } else {
            sb.append(drivers.size()).append(" port(s) found 检测到串口：\n");
            for (UsbSerialDriver d : drivers) {
                String name = d.getDevice().getDeviceName()
                        + "  vid=" + d.getDevice().getVendorId()
                        + " pid=" + d.getDevice().getProductId()
                        + "  " + d.getClass().getSimpleName();
                names.add(name);
                sb.append("  · ").append(name).append('\n');
            }
        }
        devicesText.setText(sb.toString());
        portAdapter.clear();
        portAdapter.addAll(names);
    }

    private int selectedBaud() {
        return BAUD_RATES[baudSpinner.getSelectedItemPosition()];
    }

    private void connectSelected() {
        int index = portSpinner.getSelectedItemPosition();
        if (drivers.isEmpty() || index < 0) {
            statusText.setText("No serial ports detected. 未检测到串口，无法连接。");
            return;
        }
        UsbSerialDriver driver = drivers.get(index);
        int baud = selectedBaud();
        pendingUsbReceiver = UsbSerialHelper.requestPermission(this, driver.getDevice(), granted -> ui(() -> {
            if (!granted) {
                statusText.setText("Permission denied. 权限受限：用户拒绝了 USB 访问授权。");
                return;
            }
            runAsync(() -> {
                try {
                    closeQuietly();
                    UsbSerialPort port = UsbSerialHelper.open(this, driver, baud);
                    openPort = port;
                    ui(() -> statusText.setText("Connected 已连接：" + driver.getDevice().getDeviceName()
                            + " @ " + baud + " bps"));
                    startReader(port);
                } catch (Exception e) {
                    ui(() -> statusText.setText("Connect failed 连接失败：" + e.getMessage()));
                }
            });
        }));
    }

    private void applyBaud() {
        UsbSerialPort port = openPort;
        if (port == null) {
            statusText.setText("Connect a port first. 请先连接串口。");
            return;
        }
        int baud = selectedBaud();
        runAsync(() -> {
            try {
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                ui(() -> statusText.setText("Baud rate set 已设置波特率：" + baud + " bps"));
            } catch (IOException e) {
                ui(() -> statusText.setText("Set baud failed 设置失败：" + e.getMessage()));
            }
        });
    }

    private void sendText() {
        UsbSerialPort port = openPort;
        if (port == null) {
            statusText.setText("Connect a port first. 请先连接串口。");
            return;
        }
        String typed = sendInput.getText().toString();
        String line = typed + (appendCrlf.isChecked() ? "\r\n" : "");
        byte[] payload = line.getBytes(StandardCharsets.UTF_8);
        runAsync(() -> {
            try {
                port.write(payload, WRITE_TIMEOUT_MS);
                ui(() -> appendLog("\n[TX] " + typed + "\n"));
            } catch (IOException e) {
                ui(() -> statusText.setText("Send failed 发送失败：" + e.getMessage()));
            }
        });
    }

    /** Continuously reads from {@code port} on its own thread and shows the bytes received. */
    private void startReader(UsbSerialPort port) {
        new Thread(() -> {
            byte[] buffer = new byte[1024];
            try {
                while (port == openPort) {
                    int n = port.read(buffer, READ_TIMEOUT_MS);
                    if (n > 0) {
                        String text = new String(buffer, 0, n, StandardCharsets.UTF_8);
                        ui(() -> appendLog("[RX]: " + text));
                    }
                }
            } catch (IOException e) {
                ui(() -> {
                    if (port == openPort) {
                        closeQuietly();
                        statusText.setText("Link lost 连接中断：" + e.getMessage());
                    }
                });
            }
        }, "serial-reader").start();
    }

    private void appendLog(String text) {
        log.append(text);
        if (log.length() > MAX_LOG_CHARS) {
            log.delete(0, log.length() - MAX_LOG_CHARS);
        }
        receiveText.setText(log.toString());
        receiveScroll.post(() -> receiveScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void clearLog() {
        log.setLength(0);
        receiveText.setText("");
    }

    private void disconnect() {
        closeQuietly();
        statusText.setText("Disconnected 已断开");
    }

    private void closeQuietly() {
        UsbSerialPort port = openPort;
        openPort = null;
        if (port != null) {
            try { port.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    protected void onStopTests() {
        closeQuietly();
        UsbSerialHelper.unregisterQuietly(this, pendingUsbReceiver);
        pendingUsbReceiver = null;
    }
}
