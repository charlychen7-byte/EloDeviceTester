package com.elotouch.devicetester.modules.reboot;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.elotouch.devicetester.modules.cashdrawer.CashDrawerController;

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
        ETHERNET("ethernet", "Ethernet"),
        WIFI("wifi", "Wi-Fi"),
        CASH_DRAWER("cash_drawer", "Cash Drawer"),
        USB_DRIVE("usb_drive", "USB Storage");

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
     *
     * @param expectedUsbStorage how many USB storage devices were connected
     *                           when the run started; the USB check passes only
     *                           while exactly that many are still present
     */
    public static Map<Check, String> run(Context context, EnumSet<Check> enabled,
                                         int expectedUsbStorage) {
        Map<Check, String> results = new EnumMap<>(Check.class);
        for (Check c : Check.values()) {
            if (!enabled.contains(c)) {
                results.put(c, SKIPPED);
                continue;
            }
            boolean pass = c == Check.USB_DRIVE
                    ? countUsbStorage(context) == expectedUsbStorage
                    : isConnected(context, c);
            results.put(c, pass ? CONNECTED : ABSENT);
        }
        return results;
    }

    /** Whether the peripheral for this check is connected right now. */
    public static boolean isConnected(Context context, Check check) {
        switch (check) {
            case ETHERNET:
                return hasTransport(context, NetworkCapabilities.TRANSPORT_ETHERNET);
            case WIFI:
                return hasTransport(context, NetworkCapabilities.TRANSPORT_WIFI);
            case CASH_DRAWER:
                return hasUsbDevice(context, CashDrawerController.VENDOR_ID,
                        CashDrawerController.PRODUCT_ID);
            case USB_DRIVE:
                return countUsbStorage(context) > 0;
            default:
                return false;
        }
    }

    /** How many connected USB devices expose a mass-storage interface. */
    public static int countUsbStorage(Context context) {
        UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (um == null) return 0;
        int count = 0;
        for (UsbDevice device : um.getDeviceList().values()) {
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface iface = device.getInterface(i);
                if (iface.getInterfaceClass() == USB_CLASS_MASS_STORAGE) {
                    count++;
                    break;
                }
            }
        }
        return count;
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
}
