package com.elotouch.devicetester.core;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.List;

/**
 * Shared helper for USB-serial peripherals (PRD §3.14/§3.15): enumerates
 * attached USB-serial adapters via the open-source usb-serial-for-android
 * library and handles the per-device runtime permission dance, so serial-
 * based modules don't duplicate this plumbing. Not tied to any vendor SDK.
 */
public final class UsbSerialHelper {

    private static final String ACTION_USB_PERMISSION =
            "com.elotouch.devicetester.USB_PERMISSION";

    private UsbSerialHelper() {}

    public interface PermissionCallback {
        void onResult(boolean granted);
    }

    public static List<UsbSerialDriver> listDrivers(Context context) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager);
    }

    public static void requestPermission(Context context, UsbDevice device, PermissionCallback callback) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager.hasPermission(device)) {
            callback.onResult(true);
            return;
        }
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_USB_PERMISSION), flags);

        BroadcastReceiver[] holder = new BroadcastReceiver[1];
        holder[0] = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                try {
                    context.unregisterReceiver(holder[0]);
                } catch (IllegalArgumentException ignored) { }
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                callback.onResult(granted);
            }
        };
        context.registerReceiver(holder[0], new IntentFilter(ACTION_USB_PERMISSION));
        manager.requestPermission(device, pi);
    }

    public static UsbSerialPort open(Context context, UsbSerialDriver driver, int baudRate) throws IOException {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
        if (connection == null) {
            throw new IOException("Cannot open USB device connection 无法打开 USB 连接");
        }
        UsbSerialPort port = driver.getPorts().get(0);
        port.open(connection);
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        return port;
    }
}
