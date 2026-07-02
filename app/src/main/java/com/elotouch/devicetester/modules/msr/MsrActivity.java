package com.elotouch.devicetester.modules.msr;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.elotouch.devicetester.core.UsbSerialHelper;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.util.List;
import java.util.Locale;

/**
 * MSR / IC-card reader module (PRD §3.14): distinct from the phone's own NFC
 * chip (§3.3) — this targets an external payment-terminal card reader.
 * Magstripe swipes are captured via keyboard-wedge input (the common HID mode
 * for cheap swipe readers); IC/contactless reads listen on a USB-serial port
 * when one is attached and show the reader's raw response bytes (not a parsed
 * ATR — the exact protocol is reader-specific and out of scope here).
 */
public class MsrActivity extends BaseTestActivity {

    private TextView connectionText;
    private TextView swipeText;
    private TextView icText;
    private EditText wedgeInput;

    @Override
    protected String title() {
        return "MSR 刷卡器测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Reader Connection / 刷卡器连接检测");
        connectionText = addInfo("");
        addButton("Refresh / 刷新检测", this::refreshConnection);

        addSectionTitle("Magstripe Swipe / 磁条卡刷卡测试");
        addInfo("Tap the box below, then swipe a test card on a keyboard-wedge reader.\n"
                + "点击下方输入框后，用磁条卡在键盘模拟（Wedge）读卡器上刷卡。");
        wedgeInput = new EditText(this);
        wedgeInput.setHint("Swipe here 在此刷卡…");
        addView(wedgeInput);
        swipeText = addInfo("Waiting for swipe. 等待刷卡…");
        wedgeInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                String raw = s.toString();
                if (raw.endsWith("\n") || raw.endsWith("\r")) {
                    swipeText.setText("Track data (masked) 磁道数据（脱敏）：\n" + mask(raw.trim()));
                    wedgeInput.setText("");
                }
            }
        });

        addSectionTitle("IC Card / IC 卡插拔测试");
        icText = addInfo("Tap below, then insert the test IC card. 点击下方按钮后插入测试 IC 卡。");
        addButton("Start IC Detection / 开始 IC 检测", () -> listenSerial("IC"));

        addSectionTitle("Contactless / 非接触刷卡测试");
        addButton("Start Contactless Detection / 开始非接触检测", () -> listenSerial("Contactless"));

        refreshConnection();
    }

    private void refreshConnection() {
        List<UsbSerialDriver> drivers = UsbSerialHelper.listDrivers(this);
        connectionText.setText(drivers.isEmpty()
                ? "No external MSR/IC reader detected (swipe test still works via keyboard-wedge). "
                        + "未检测到外接读卡器（磁条刷卡测试仍可通过键盘模拟方式使用）。"
                : drivers.size() + " reader(s) detected 检测到读卡器：" + drivers.get(0).getDevice().getDeviceName());
    }

    private void listenSerial(String mode) {
        List<UsbSerialDriver> drivers = UsbSerialHelper.listDrivers(this);
        if (drivers.isEmpty()) {
            icText.setText("No external reader detected. 未检测到外接读卡器。");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        icText.setText("Waiting for card (" + mode + ")… 等待读卡（" + mode + "）…");
        UsbSerialHelper.requestPermission(this, driver.getDevice(), granted -> ui(() -> {
            if (!granted) {
                icText.setText("Permission denied. 权限受限。");
                return;
            }
            runAsync(() -> {
                UsbSerialPort port = null;
                try {
                    port = UsbSerialHelper.open(this, driver, 9600);
                    byte[] buffer = new byte[256];
                    int read = port.read(buffer, 5000);
                    String hex = toHex(buffer, read);
                    boolean got = read > 0;
                    ui(() -> icText.setText(got
                            ? "Reader response 读卡器响应字节：\n" + hex
                            : "No response within 5s. 5 秒内无响应。"));
                } catch (Exception e) {
                    ui(() -> icText.setText("Read failed 读取失败：" + e.getMessage()));
                } finally {
                    if (port != null) { try { port.close(); } catch (Exception ignored) { } }
                }
            });
        }));
    }

    private static String mask(String raw) {
        if (raw.length() <= 8) return raw;
        return raw.substring(0, 4) + "…(" + (raw.length() - 8) + " chars hidden)…" + raw.substring(raw.length() - 4);
    }

    private static String toHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append(String.format(Locale.US, "%02X ", bytes[i]));
        return sb.toString().trim();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshConnection();
    }
}
