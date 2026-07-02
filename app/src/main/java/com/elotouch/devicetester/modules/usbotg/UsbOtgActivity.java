package com.elotouch.devicetester.modules.usbotg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * USB / OTG module (PRD §3.18). USB attach/detach and OTG storage are
 * inherently dynamic, so this page detects them live (pluggable-peripheral
 * model, PRD §5.3) rather than the grid greying the entry out.
 */
public class UsbOtgActivity extends BaseTestActivity {

    private TextView usbText;
    private TextView otgText;
    private TextView chargeText;
    private boolean receiverRegistered;

    private final ActivityResultLauncher<Intent> browseLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> { });

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refreshUsb();
        }
    };

    @Override
    protected String title() {
        return "USB/OTG 接口测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("USB Connection / USB 连接状态");
        usbText = addInfo("");

        addSectionTitle("OTG Storage / OTG 存储识别");
        otgText = addInfo("");
        addButton("Browse External Storage / 浏览外接存储", this::browseOtg);

        addSectionTitle("Charging Current / 充电电流");
        chargeText = addInfo("Reading… 读取中…");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerReceiver(usbReceiver, filter);
            receiverRegistered = true;
        }
        refreshUsb();
        refreshOtg();
        refreshCharge();
    }

    private void refreshUsb() {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        Map<String, UsbDevice> devices = um.getDeviceList();
        usbText.setText(devices.isEmpty()
                ? "No USB device attached. 当前无 USB 设备连接。"
                : devices.size() + " USB device(s) attached 已连接：" + devices.size());
    }

    private void refreshOtg() {
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> volumes = sm.getStorageVolumes();
        StringBuilder sb = new StringBuilder();
        int removable = 0;
        for (StorageVolume v : volumes) {
            if (v.isRemovable() && !v.isPrimary()) {
                removable++;
                String desc = v.getDescription(this);
                sb.append("  · ").append(v.getState())
                        .append(desc != null ? " (" + desc + ")" : "").append('\n');
            }
        }
        otgText.setText(removable > 0
                ? removable + " removable volume(s) 检测到可移动存储：\n" + sb
                : "No OTG storage detected. 未检测到 OTG 存储设备。");
    }

    private void refreshCharge() {
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        int microAmps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        chargeText.setText(microAmps == Integer.MIN_VALUE || microAmps == 0
                ? "Not readable on this device. 该机型不支持读取。"
                : String.format(Locale.US, "%.0f mA", microAmps / 1000f));
    }

    private void browseOtg() {
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> volumes = sm.getStorageVolumes();
        for (StorageVolume v : volumes) {
            if (v.isRemovable() && !v.isPrimary()) {
                Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? v.createOpenDocumentTreeIntent()
                        : new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                browseLauncher.launch(intent);
                return;
            }
        }
        toast("No OTG storage to browse. 未检测到可浏览的 OTG 存储。");
    }

    @Override
    protected void onStopTests() {
        if (receiverRegistered) {
            try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
    }
}
