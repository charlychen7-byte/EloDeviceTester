package com.elotouch.devicetester.modules.serial;

import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;
import com.elotouch.devicetester.core.UsbSerialHelper;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serial I/O peripherals module (PRD §3.15): USB-serial cash drawer / receipt
 * printer / generic serial connectivity, implemented on the standard Android
 * UsbManager + the open-source usb-serial-for-android library (no vendor
 * SDK). The module entry stays clickable even with nothing attached;
 * availability is detected live on this page per the pluggable-peripheral
 * model (PRD §5.3).
 */
public class SerialActivity extends BaseTestActivity {

    private static final byte[] CASH_DRAWER_KICK = {0x1B, 0x70, 0x00, 0x19, (byte) 0xFA};
    private static final byte[] PRINTER_INIT = {0x1B, 0x40};
    private static final byte[] PRINTER_CUT = {0x1D, 0x56, 0x42, 0x00};

    private TextView devicesText;
    private TextView ioText;
    private UsbSerialPort openPort;

    @Override
    protected String title() {
        return "Serial I/O 序口外设测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Detected Devices / 检测到的外设");
        addInfo("Uses UsbManager + usb-serial-for-android; not tied to any vendor SDK.\n"
                + "基于标准 UsbManager 与开源 usb-serial-for-android 库，不依赖厂商私有 SDK。");
        devicesText = addInfo("");
        addButton("Refresh / 刷新外设列表", this::refreshDevices);
        addButton("Connect First Device / 连接第一个检测到的外设", this::connectFirst);

        addSectionTitle("Serial Loopback / 串口收发测试");
        ioText = addInfo("Not connected. 尚未连接。");
        addButton("Send Test Bytes / 发送测试字节", this::sendTestBytes);

        addSectionTitle("Cash Drawer / 钱箱触发");
        addButton("Open Cash Drawer / 打开钱箱", this::kickDrawer);

        addSectionTitle("Receipt Printer / 小票打印机");
        addButton("Print Test Receipt / 打印测试小票", this::printTestReceipt);

        refreshDevices();
    }

    private void refreshDevices() {
        List<UsbSerialDriver> drivers = UsbSerialHelper.listDrivers(this);
        if (drivers.isEmpty()) {
            devicesText.setText("No serial peripherals detected. 未检测到外设。");
        } else {
            StringBuilder sb = new StringBuilder(drivers.size() + " device(s) found 检测到设备：\n");
            for (UsbSerialDriver d : drivers) {
                sb.append("  · ").append(d.getDevice().getDeviceName())
                        .append("  (vid=").append(d.getDevice().getVendorId())
                        .append(" pid=").append(d.getDevice().getProductId()).append(")\n");
            }
            devicesText.setText(sb.toString());
        }
    }

    private void connectFirst() {
        List<UsbSerialDriver> drivers = UsbSerialHelper.listDrivers(this);
        if (drivers.isEmpty()) {
            ioText.setText("No serial peripherals detected. 未检测到外设，无法连接。");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        UsbSerialHelper.requestPermission(this, driver.getDevice(), granted -> ui(() -> {
            if (!granted) {
                ioText.setText("Permission denied. 权限受限：用户拒绝了 USB 访问授权。");
                return;
            }
            runAsync(() -> {
                try {
                    closeQuietly();
                    openPort = UsbSerialHelper.open(this, driver, 9600);
                    ui(() -> ioText.setText("Connected 已连接：" + driver.getDevice().getDeviceName()));
                } catch (Exception e) {
                    ui(() -> ioText.setText("Connect failed 连接失败：" + e.getMessage()));
                }
            });
        }));
    }

    private void sendTestBytes() {
        if (openPort == null) {
            ioText.setText("Connect a device first. 请先连接外设。");
            return;
        }
        runAsync(() -> {
            try {
                byte[] payload = "ELO-TEST\n".getBytes(StandardCharsets.US_ASCII);
                openPort.write(payload, 1000);
                byte[] buffer = new byte[256];
                int read = openPort.read(buffer, 2000);
                String received = read > 0
                        ? new String(buffer, 0, read, StandardCharsets.US_ASCII)
                        : "(no reply within 2s 2 秒内无回应，若无自环/应答外设属正常现象)";
                ui(() -> ioText.setText("Sent 已发送 ELO-TEST\\n\nReceived 收到：" + received));
            } catch (Exception e) {
                ui(() -> ioText.setText("I/O error 通信出错：" + e.getMessage()));
            }
        });
    }

    private void kickDrawer() {
        if (openPort == null) {
            ioText.setText("Connect a device first. 请先连接外设。");
            return;
        }
        runAsync(() -> {
            try {
                openPort.write(CASH_DRAWER_KICK, 1000);
                ui(() -> ioText.setText("Kick command sent; confirm the drawer opened.\n"
                        + "已发送开钱箱指令，请确认钱箱是否弹开。"));
            } catch (Exception e) {
                ui(() -> ioText.setText("Send failed 发送失败：" + e.getMessage()));
            }
        });
    }

    private void printTestReceipt() {
        if (openPort == null) {
            ioText.setText("Connect a device first. 请先连接外设。");
            return;
        }
        runAsync(() -> {
            try {
                openPort.write(PRINTER_INIT, 1000);
                openPort.write("Elo DeviceTester\nTest Receipt 测试小票\n\n\n"
                        .getBytes(StandardCharsets.US_ASCII), 1000);
                try {
                    openPort.write(PRINTER_CUT, 1000);
                } catch (Exception ignored) {
                    // Cutter not supported by every printer; non-fatal.
                }
                ui(() -> ioText.setText("Print job sent. 打印任务已发送，请检查走纸与打印质量。"));
            } catch (Exception e) {
                ui(() -> ioText.setText("Print failed 打印失败：" + e.getMessage()));
            }
        });
    }

    private void closeQuietly() {
        if (openPort != null) {
            try { openPort.close(); } catch (Exception ignored) { }
            openPort = null;
        }
    }

    @Override
    protected void onStopTests() {
        closeQuietly();
    }
}
