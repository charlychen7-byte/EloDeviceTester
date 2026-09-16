package com.elotouch.devicetester.core;

import android.app.Activity;

import com.elotouch.devicetester.modules.audio.AudioActivity;
import com.elotouch.devicetester.modules.barcode.BarcodeActivity;
import com.elotouch.devicetester.modules.battery.BatteryActivity;
import com.elotouch.devicetester.modules.camera.CameraActivity;
import com.elotouch.devicetester.modules.cellular.CellularActivity;
import com.elotouch.devicetester.modules.cpu.CpuActivity;
import com.elotouch.devicetester.modules.ddr.DdrActivity;
import com.elotouch.devicetester.modules.display.DisplayActivity;
import com.elotouch.devicetester.modules.displayext.DisplayExtActivity;
import com.elotouch.devicetester.modules.ethernet.EthernetActivity;
import com.elotouch.devicetester.modules.ethernet.IperfActivity;
import com.elotouch.devicetester.modules.gps.GpsActivity;
import com.elotouch.devicetester.modules.msr.MsrActivity;
import com.elotouch.devicetester.modules.nfc.NfcActivity;
import com.elotouch.devicetester.modules.ping.PingActivity;
import com.elotouch.devicetester.modules.sensors.SensorsActivity;
import com.elotouch.devicetester.modules.serial.SerialActivity;
import com.elotouch.devicetester.modules.stability.StabilityActivity;
import com.elotouch.devicetester.modules.storage.StorageActivity;
import com.elotouch.devicetester.modules.touch.TouchActivity;
import com.elotouch.devicetester.modules.usbotg.UsbOtgActivity;
import com.elotouch.devicetester.modules.vibrator.VibratorActivity;
import com.elotouch.devicetester.modules.wifibt.WifiBtActivity;

/**
 * Registry of every hardware module shown on the level-1 grid.
 * Each entry knows its display name, an emoji icon, the Activity to launch,
 * and how to decide whether the underlying hardware exists on this device
 * (see {@link HardwareDetector}).
 */
public enum TestModule {
    CPU("CPU / 处理器", "⚙️", CpuActivity.class, HardwareDetector.Feature.ALWAYS),
    DDR("DDR / 内存", "🧠", DdrActivity.class, HardwareDetector.Feature.ALWAYS),
    STORAGE("Storage / 存储", "💾", StorageActivity.class, HardwareDetector.Feature.ALWAYS),
    DISPLAY("Display / 显示", "🖥️", DisplayActivity.class, HardwareDetector.Feature.ALWAYS),
    TOUCH("Touch / 触摸", "👆", TouchActivity.class, HardwareDetector.Feature.TOUCH),
    BATTERY("Battery / 电池", "🔋", BatteryActivity.class, HardwareDetector.Feature.ALWAYS),
    VIBRATOR("Vibrator / 振动", "📳", VibratorActivity.class, HardwareDetector.Feature.VIBRATOR),
    CAMERA("Camera / 相机", "📷", CameraActivity.class, HardwareDetector.Feature.CAMERA),
    AUDIO("Audio / 音频", "🔊", AudioActivity.class, HardwareDetector.Feature.ALWAYS),
    GPS("GPS / 定位", "🛰️", GpsActivity.class, HardwareDetector.Feature.GPS),
    NFC("NFC", "📇", NfcActivity.class, HardwareDetector.Feature.NFC),
    //WIFI_BT("Wi-Fi & BT", "📶", WifiBtActivity.class, HardwareDetector.Feature.WIFI),
    SENSORS("Sensors / 传感器", "🧭", SensorsActivity.class, HardwareDetector.Feature.SENSORS),
    //BARCODE("Barcode/QR / 条码扫描", "🔍", BarcodeActivity.class, HardwareDetector.Feature.ALWAYS),
    //MSR("MSR / 刷卡器", "💳", MsrActivity.class, HardwareDetector.Feature.ALWAYS),
    //SERIAL_IO("Serial I/O / 序口外设", "🖨️", SerialActivity.class, HardwareDetector.Feature.ALWAYS),
    //DISPLAY_EXT("Display Ext / 双屏网络", "📺", DisplayExtActivity.class, HardwareDetector.Feature.ALWAYS),
    //ETHERNET("Ethernet / 有线网络", "🔗", EthernetActivity.class, HardwareDetector.Feature.ALWAYS),
    CELLULAR("Cellular / 蜂窝网络", "📡", CellularActivity.class, HardwareDetector.Feature.CELLULAR),
    PING("Ping / 连通性", "🌐", PingActivity.class, HardwareDetector.Feature.ALWAYS),
    // Single-test module: goes straight to the iperf3 test page, which already
    // carries its own description, parameters and Start/Stop.
    IPERF("iperf3 / 网络吞吐", "📈", IperfActivity.class, HardwareDetector.Feature.ALWAYS),
    STABILITY("System Stability / 系统稳定性", "🔥", StabilityActivity.class,
            HardwareDetector.Feature.ALWAYS);
    //USB_OTG("USB/OTG", "🔌", UsbOtgActivity.class, HardwareDetector.Feature.ALWAYS)

    public final String title;
    public final String icon;
    public final Class<? extends Activity> activity;
    public final HardwareDetector.Feature feature;

    TestModule(String title, String icon, Class<? extends Activity> activity,
               HardwareDetector.Feature feature) {
        this.title = title;
        this.icon = icon;
        this.activity = activity;
        this.feature = feature;
    }
}
