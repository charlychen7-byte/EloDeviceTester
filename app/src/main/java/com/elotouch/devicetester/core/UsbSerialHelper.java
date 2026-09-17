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

import androidx.core.content.ContextCompat;

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

    /**
     * Requests permission to access the given USB device, if not already
     * granted. Returns the {@link BroadcastReceiver} registered to await the
     * OS permission-dialog result, or {@code null} if permission was already
     * granted (in which case {@code callback} ran synchronously and no
     * receiver was registered). Callers should hold onto the returned
     * receiver and pass it to {@link #unregisterQuietly} (e.g. from
     * {@code onStopTests()}) in case the user navigates away before the
     * dialog is answered.
     */
    public static BroadcastReceiver requestPermission(Context context, UsbDevice device, PermissionCallback callback) {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager.hasPermission(device)) {
            callback.onResult(true);
            return null;
        }
        // FLAG_MUTABLE is required: UsbManager fills EXTRA_DEVICE and
        // EXTRA_PERMISSION_GRANTED into this intent before broadcasting it.
        // Targeting Android 14 (API 34) forbids a mutable PendingIntent built
        // from an *implicit* intent, so setPackage() makes it explicit — the
        // broadcast only ever comes back to us anyway.
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION)
                .setPackage(context.getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, permissionIntent, flags);

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
        // ACTION_USB_PERMISSION is an app-defined (non-protected) broadcast;
        // Android 14+ requires an explicit export flag for context-registered
        // receivers of non-protected broadcasts. This receiver is only ever
        // sent to from within this app, so RECEIVER_NOT_EXPORTED is correct.
        ContextCompat.registerReceiver(context, holder[0],
                new IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED);
        manager.requestPermission(device, pi);
        return holder[0];
    }

    /** Unregisters {@code receiver} if non-null, swallowing "not registered" errors. */
    public static void unregisterQuietly(Context context, BroadcastReceiver receiver) {
        if (receiver == null) return;
        try {
            context.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) { }
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
