package com.elotouch.devicetester.modules.reboot;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.elotouch.devicetester.modules.cashdrawer.CashDrawerController;

import java.io.File;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * The per-cycle self-checks the reboot stress test runs after every boot.
 *
 * <p>Every probe here is a passive enumeration — a transport lookup or a USB
 * device-list scan — so none of them needs a runtime permission and none of
 * them claims exclusive hardware. That matters because these run from a
 * boot-time service where no Activity exists to host a permission dialog.
 */
public final class RebootChecks {

    /** Result tokens, written verbatim into the CSV log. */
    public static final String CONNECTED = "connected";
    public static final String ABSENT = "absent";
    public static final String SKIPPED = "skipped";

    /** USB mass-storage interface class, i.e. "this is a thumb drive". */
    private static final int USB_CLASS_MASS_STORAGE = 8;

    public enum Check {
        ETHERNET("ethernet", "Ethernet 以太网已连接"),
        WIFI("wifi", "Wi-Fi 已连接"),
        CASH_DRAWER("cash_drawer", "Cash Drawer 钱箱已连接"),
        USB_DRIVE("usb_drive", "USB 存储设备已连接");

        /** Stable key used in prefs and as the CSV column name. */
        public final String key;
        public final String label;

        Check(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    private RebootChecks() {}

    /**
     * Runs the enabled checks; everything not enabled comes back
     * {@link #SKIPPED} so the log distinguishes "not connected" from
     * "never looked".
     */
    public static Map<Check, String> run(Context context, EnumSet<Check> enabled) {
        Map<Check, String> results = new EnumMap<>(Check.class);
        for (Check c : Check.values()) {
            if (!enabled.contains(c)) {
                results.put(c, SKIPPED);
                continue;
            }
            results.put(c, probe(context, c) ? CONNECTED : ABSENT);
        }
        return results;
    }

    private static boolean probe(Context context, Check check) {
        switch (check) {
            case ETHERNET:
                return hasTransport(context, NetworkCapabilities.TRANSPORT_ETHERNET);
            case WIFI:
                return hasTransport(context, NetworkCapabilities.TRANSPORT_WIFI);
            case CASH_DRAWER:
                return hasUsbDevice(context, CashDrawerController.VENDOR_ID,
                        CashDrawerController.PRODUCT_ID);
            case USB_DRIVE:
                return hasMassStorage(context);
            default:
                return false;
        }
    }

    /**
     * Whether this device could ever satisfy the check, so the Activity can
     * grey out the ones that are meaningless here (PRD §5.3).
     */
    public static boolean isSupported(Context context, Check check) {
        PackageManager pm = context.getPackageManager();
        switch (check) {
            case ETHERNET:
                // Plenty of ethernet-equipped devices never declare the system
                // feature, so fall back to asking the kernel whether an
                // ethernet interface exists at all.
                return pm.hasSystemFeature(PackageManager.FEATURE_ETHERNET)
                        || hasEthernetInterface();
            case WIFI:
                return pm.hasSystemFeature(PackageManager.FEATURE_WIFI);
            case CASH_DRAWER:
            case USB_DRIVE:
                return pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST);
            default:
                return false;
        }
    }

    private static boolean hasEthernetInterface() {
        File netDir = new File("/sys/class/net");
        File[] interfaces = netDir.listFiles();
        if (interfaces == null) return false;
        for (File iface : interfaces) {
            if (iface.getName().startsWith("eth")) return true;
        }
        return false;
    }

    private static boolean hasTransport(Context context, int transport) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        // Scan every network rather than trusting the active one: with both
        // ethernet and Wi-Fi up, only one of them is "active".
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(transport)) return true;
        }
        return false;
    }

    private static boolean hasUsbDevice(Context context, int vendorId, int productId) {
        UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (um == null) return false;
        for (UsbDevice device : um.getDeviceList().values()) {
            if (device.getVendorId() == vendorId && device.getProductId() == productId) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMassStorage(Context context) {
        UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (um == null) return false;
        for (UsbDevice device : um.getDeviceList().values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                if (iface.getInterfaceClass() == USB_CLASS_MASS_STORAGE) return true;
            }
        }
        return false;
    }
}
