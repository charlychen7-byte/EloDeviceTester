package com.elotouch.devicetester.modules.barcode;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;
import java.util.Map;

/**
 * Barcode/QR scanner module (PRD §3.13). Dedicated scan heads on Android
 * almost always act as a keyboard-wedge HID device (they "type" the decoded
 * string followed by Enter/Tab) rather than exposing a scan API, so capture
 * works the same way for a built-in head, an external USB scanner, or a
 * Bluetooth scanner in HID mode. A connected USB scanner is additionally
 * reported if present, but the capture box works regardless (pluggable-
 * peripheral detection model, PRD §5.3).
 */
public class BarcodeActivity extends BaseTestActivity {

    private TextView connectionText;
    private TextView resultText;
    private EditText scanInput;
    private long focusTime;
    private int successCount = 0;

    @Override
    protected String title() {
        return "Barcode/QR 条码扫描测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Scanner Connection / 扫描头连接检测");
        connectionText = addInfo("");
        addButton("Refresh / 刷新检测", this::refreshConnection);

        addSectionTitle("Scan Test / 扫码功能测试");
        addInfo("Tap the box below, then trigger the scanner (hardware key or trigger) at a "
                + "test barcode/QR code.\n点击下方输入框后，用扫描枪/扫描键对准测试条码或二维码触发扫描。");
        scanInput = new EditText(this);
        scanInput.setHint("Scan here 在此扫描…");
        scanInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) focusTime = System.nanoTime();
        });
        addView(scanInput);
        resultText = addInfo("Success count 成功次数：0");
        scanInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                String raw = s.toString();
                if (raw.endsWith("\n") || raw.endsWith("\t")) {
                    long ms = (System.nanoTime() - focusTime) / 1_000_000;
                    successCount++;
                    resultText.setText(String.format(Locale.US,
                            "Success count 成功次数：%d\nLast decoded 最近解码内容：%s\n"
                                    + "Elapsed since focus 耗时：%d ms\n"
                                    + "(Symbology 码制类型 unavailable — wedge scanners only transmit "
                                    + "decoded text. 码制类型不可读：Wedge 模式仅传输解码后的文本)",
                            successCount, raw.trim(), ms));
                    scanInput.setText("");
                    focusTime = System.nanoTime();
                }
            }
        });

        refreshConnection();
    }

    private void refreshConnection() {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        Map<String, UsbDevice> devices = um.getDeviceList();
        connectionText.setText(devices.isEmpty()
                ? "No external USB scanner detected; a built-in head or Bluetooth HID scanner will "
                        + "still work through the capture box below. 未检测到外接 USB 扫描设备；内置扫描头"
                        + "或蓝牙 HID 扫描枪仍可通过下方输入框正常使用。"
                : devices.size() + " USB device(s) present 检测到 USB 设备：" + devices.size());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConnection();
    }
}
