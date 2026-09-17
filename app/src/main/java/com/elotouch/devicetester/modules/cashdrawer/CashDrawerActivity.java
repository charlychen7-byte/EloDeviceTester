package com.elotouch.devicetester.modules.cashdrawer;

import android.content.BroadcastReceiver;
import android.hardware.usb.UsbDevice;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.elotouch.devicetester.R;
import com.elotouch.devicetester.core.BaseTestActivity;
import com.elotouch.devicetester.core.UsbSerialHelper;

import java.util.Locale;

/**
 * Cash Drawer module: drives the Elo RJ12 cash-drawer controller board over
 * USB (see {@link CashDrawerController} for the wire protocol).
 *
 * <p>Whether the board is attached is detected live on this page rather than
 * greyed out on the level-1 grid, since it is a pluggable peripheral — the same
 * model the other USB peripheral modules use (PRD §5.3). USB permission is
 * requested lazily, only once the operator taps "connect" (PRD §5.4).
 */
public class CashDrawerActivity extends BaseTestActivity {

    /** Enough for the board to answer a version query. */
    private static final int READ_TIMEOUT_MS = 300;

    private CashDrawerController controller;
    private BroadcastReceiver pendingUsbReceiver;
    /**
     * Set while the system USB permission dialog is up. That dialog pauses this
     * activity, so {@link #onStopTests()} must not tear the receiver down or the
     * grant would never reach us.
     */
    private boolean awaitingPermission;

    private TextView connectionText;
    private TextView powerText;
    private TextView drawerText;

    private int defaultTextColor;

    @Override
    protected String title() {
        return "Cash Drawer 钱箱测试";
    }

    @Override
    protected void buildUi() {
        controller = new CashDrawerController(this);

        addSectionTitle("Connection / 连接检测");
        addInfo("Check cash-drawer connection");
        connectionText = addInfo("Not connected. 尚未连接。");
        defaultTextColor = connectionText.getCurrentTextColor();
        addButton("Detect & Connect / 检测并连接", this::connect);
        addButton("Disconnect / 断开连接", this::disconnect);

        addSectionTitle("Power / 电源控制");
        addInfo("Selects the drawer supply voltage before kicking it open.\n"
                + "开钱箱前先选择钱箱的供电电压。");
        powerText = addInfo("—");
        addButton("12V Power ON / 12V 上电",
                () -> sendCommand(CashDrawerController.CMD_POWER_ON_12V,
                        "12V Power ON / 12V 上电", powerText));
        addButton("12V Power OFF / 12V 断电",
                () -> sendCommand(CashDrawerController.CMD_POWER_OFF_12V,
                        "12V Power OFF / 12V 断电", powerText));
        addButton("24V Power ON / 24V 上电",
                () -> sendCommand(CashDrawerController.CMD_POWER_ON_24V,
                        "24V Power ON / 24V 上电", powerText));
        addButton("24V Power OFF / 24V 断电",
                () -> sendCommand(CashDrawerController.CMD_POWER_OFF_24V,
                        "24V Power OFF / 24V 断电", powerText));

        addSectionTitle("Open Drawer / 开钱箱");
        addInfo("Sends the drawer kick pulse. Power the drawer on first.\n"
                + "发送开箱脉冲。请先给钱箱上电。");
        drawerText = addInfo("—");
        addButton("Open Cash Drawer / 打开钱箱", this::openDrawer);
    }

    // ------------------------------------------------------------- connection

    private void connect() {
        UsbDevice target = controller.findDevice();
        if (target == null) {
            showError(connectionText, String.format(Locale.US,
                    "Controller board not found (VID 0x%04X / PID 0x%04X).\n"
                            + "未找到钱箱控制板，请确认设备已连接。",
                    CashDrawerController.VENDOR_ID, CashDrawerController.PRODUCT_ID));
            return;
        }
        connectionText.setTextColor(defaultTextColor);
        connectionText.setText("Requesting USB permission… 正在申请 USB 权限…");
        awaitingPermission = true;
        pendingUsbReceiver = UsbSerialHelper.requestPermission(this, target, granted -> {
            awaitingPermission = false;
            pendingUsbReceiver = null;
            if (!granted) {
                showError(connectionText,
                        "USB permission denied. 权限受限：USB 权限被拒绝。");
                return;
            }
            openAndQuery(target);
        });
    }

    private void openAndQuery(UsbDevice target) {
        connectionText.setText("Connecting… 连接中…");
        runAsync(() -> {
            try {
                controller.open(target);
                String description = controller.describeConnection();
                controller.send(CashDrawerController.CMD_MCU_VERSION);
                ui(() -> {
                    connectionText.setTextColor(ContextCompat.getColor(this, R.color.ok));
                    connectionText.setText("Connected 已连接\n" + description);
                });
            } catch (Exception e) {
                ui(() -> showError(connectionText, "Connect failed 连接失败：" + e.getMessage()));
            }
        });
    }

    private void disconnect() {
        controller.close();
        connectionText.setTextColor(defaultTextColor);
        connectionText.setText("Disconnected. 已断开连接。");
    }

    // ---------------------------------------------------------------- commands

    private void openDrawer() {
        sendCommand(CashDrawerController.CMD_OPEN_DRAWER, "Open drawer / 开钱箱", drawerText);
    }

    /** Writes one frame off the UI thread and reports the result inline. */
    private void sendCommand(String hexCommand, String label, TextView target) {
        if (!controller.isOpen()) {
            showError(target, "Not connected — tap \"Detect & Connect\" first.\n"
                    + "尚未连接，请先点击「检测并连接」。");
            return;
        }
        target.setTextColor(defaultTextColor);
        target.setText(label + " …");
        runAsync(() -> {
            try {
                int sent = controller.send(hexCommand);
                String reply = controller.read(READ_TIMEOUT_MS);
                ui(() -> {
                    target.setTextColor(ContextCompat.getColor(this, R.color.ok));
                    StringBuilder sb = new StringBuilder();
                    sb.append(label).append("  ✓\n")
                            .append("TX: ").append(CashDrawerController.spacedHex(hexCommand))
                            .append(String.format(Locale.US, "  (%d bytes 字节)", sent));
                    if (!reply.isEmpty()) {
                        sb.append("\nRX: ").append(reply);
                    }
                    if (hexCommand.equals(CashDrawerController.CMD_OPEN_DRAWER)) {
                        sb.append("\nConfirm the drawer popped open. 请确认钱箱已弹出。");
                    }
                    target.setText(sb.toString());
                });
            } catch (Exception e) {
                ui(() -> showError(target, label + " failed 失败：" + e.getMessage()));
            }
        });
    }

    private void showError(TextView target, String message) {
        target.setTextColor(ContextCompat.getColor(this, R.color.error));
        target.setText(message);
    }

    // ----------------------------------------------------------------- cleanup

    @Override
    protected void onStopTests() {
        if (awaitingPermission) return;
        UsbSerialHelper.unregisterQuietly(this, pendingUsbReceiver);
        pendingUsbReceiver = null;
        if (controller != null) controller.close();
    }

    @Override
    protected void onDestroy() {
        // The permission guard above can leave the receiver registered if the
        // operator walks away with the dialog up; this is the backstop.
        awaitingPermission = false;
        UsbSerialHelper.unregisterQuietly(this, pendingUsbReceiver);
        pendingUsbReceiver = null;
        if (controller != null) controller.close();
        super.onDestroy();
    }
}
