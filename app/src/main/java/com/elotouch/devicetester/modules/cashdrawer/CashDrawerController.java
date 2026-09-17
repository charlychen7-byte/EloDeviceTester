package com.elotouch.devicetester.modules.cashdrawer;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Locale;

/**
 * Transport for the Elo RJ12 cash-drawer controller board.
 *
 * <p>The board enumerates as a USB CDC device (VID 0x1A86 / PID 0xFE0C). Its
 * command set is a fixed 8-byte frame written straight to the CDC-data bulk OUT
 * endpoint — no line-coding / baud-rate setup is required before writing:
 *
 * <pre>38 BE EF &lt;cmd&gt; &lt;p1&gt; &lt;p2&gt; &lt;p3&gt; 0D</pre>
 *
 * <p>All methods here block on USB I/O and must be called off the UI thread.
 * Permission for the device is obtained by the caller (see
 * {@link com.elotouch.devicetester.core.UsbSerialHelper}) before {@link #open}.
 */
public class CashDrawerController {

    public static final int VENDOR_ID = 0x1A86;
    public static final int PRODUCT_ID = 0xFE0C;

    // ---- command frames (hex), as used by the reference Cash Drawer Demo ----
    public static final String CMD_MCU_VERSION = "38BEEF678000000D";
    public static final String CMD_POWER_ON_12V = "38BEEF733100010D";
    public static final String CMD_POWER_OFF_12V = "38BEEF733100000D";
    public static final String CMD_POWER_ON_24V = "38BEEF732100010D";
    public static final String CMD_POWER_OFF_24V = "38BEEF732100000D";
    /** Drawer kick. The reference app sends this for both 12V and 24V drawers. */
    public static final String CMD_OPEN_DRAWER = "38BEEF735200000D";

    private static final int TRANSFER_TIMEOUT_MS = 100;
    private static final int READ_BUFFER_SIZE = 4096;

    private final Context context;

    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbInterface dataInterface;
    private UsbEndpoint bulkIn;
    private UsbEndpoint bulkOut;

    public CashDrawerController(Context context) {
        this.context = context.getApplicationContext();
    }

    /** The controller board if it is currently attached, else {@code null}. */
    public UsbDevice findDevice() {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) return null;
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID) {
                return d;
            }
        }
        return null;
    }

    public boolean isOpen() {
        return connection != null && bulkOut != null;
    }

    public UsbDevice device() {
        return device;
    }

    /**
     * Claims the CDC-data interface and resolves its bulk endpoints.
     * Caller must already hold USB permission for {@code target}.
     */
    public void open(UsbDevice target) throws IOException {
        close();
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            throw new IOException("USB service unavailable / 无法获取 USB 服务");
        }
        UsbDeviceConnection conn = manager.openDevice(target);
        if (conn == null) {
            throw new IOException("Cannot open USB device / 无法打开 USB 设备");
        }

        UsbInterface chosen = pickDataInterface(target);
        if (chosen == null) {
            conn.close();
            throw new IOException("No CDC data interface found / 未找到 CDC 数据接口");
        }
        if (!conn.claimInterface(chosen, true)) {
            conn.close();
            throw new IOException("claimInterface failed / 接口占用失败");
        }

        UsbEndpoint in = null;
        UsbEndpoint out = null;
        for (int i = 0; i < chosen.getEndpointCount(); i++) {
            UsbEndpoint ep = chosen.getEndpoint(i);
            if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
            else out = ep;
        }
        if (out == null) {
            conn.releaseInterface(chosen);
            conn.close();
            throw new IOException("No bulk OUT endpoint / 未找到批量输出端点");
        }

        this.device = target;
        this.connection = conn;
        this.dataInterface = chosen;
        this.bulkIn = in;
        this.bulkOut = out;
    }

    /**
     * Prefers the CDC-data interface (class 0x0A); falls back to any interface
     * that carries a bulk OUT endpoint, in case a firmware build reports a
     * vendor-specific class instead.
     */
    private UsbInterface pickDataInterface(UsbDevice target) {
        for (int i = 0; i < target.getInterfaceCount(); i++) {
            UsbInterface intf = target.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) return intf;
        }
        for (int i = 0; i < target.getInterfaceCount(); i++) {
            UsbInterface intf = target.getInterface(i);
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    return intf;
                }
            }
        }
        return null;
    }

    /** Writes one command frame. Returns the number of bytes accepted. */
    public int send(String hexCommand) throws IOException {
        if (!isOpen()) {
            throw new IOException("Not connected / 尚未连接");
        }
        byte[] payload = hexToBytes(hexCommand);
        int sent = connection.bulkTransfer(bulkOut, payload, payload.length, TRANSFER_TIMEOUT_MS);
        if (sent < 0) {
            throw new IOException("bulkTransfer failed / 数据发送失败");
        }
        return sent;
    }

    /**
     * Reads whatever the board replied with, as a hex string.
     * Returns an empty string when nothing arrived before {@code timeoutMs}.
     */
    public String read(int timeoutMs) {
        if (connection == null || bulkIn == null) return "";
        byte[] buffer = new byte[READ_BUFFER_SIZE];
        int len = connection.bulkTransfer(bulkIn, buffer, buffer.length, timeoutMs);
        if (len <= 0) return "";
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            sb.append(String.format(Locale.US, "%02X ", buffer[i]));
        }
        return sb.toString().trim();
    }

    public void close() {
        if (connection != null) {
            if (dataInterface != null) {
                connection.releaseInterface(dataInterface);
            }
            connection.close();
        }
        connection = null;
        dataInterface = null;
        bulkIn = null;
        bulkOut = null;
        device = null;
    }

    /** A one-line description of the open connection, for the status panel. */
    public String describeConnection() {
        if (!isOpen()) return "Not connected 未连接";
        return String.format(Locale.US,
                "%s\nVID: 0x%04X   PID: 0x%04X\nInterface 接口: #%d (class 0x%02X)\n"
                        + "Bulk OUT: 0x%02X   Bulk IN: %s",
                device.getDeviceName(),
                device.getVendorId(), device.getProductId(),
                dataInterface.getId(), dataInterface.getInterfaceClass(),
                bulkOut.getAddress(),
                bulkIn == null ? "N/A" : String.format(Locale.US, "0x%02X", bulkIn.getAddress()));
    }

    static byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] out = new byte[length / 2];
        for (int i = 0; i + 1 < length; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    /** "38BEEF735200000D" -> "38 BE EF 73 52 00 00 0D", for the TX log lines. */
    static String spacedHex(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(hex, i, i + 2);
        }
        return sb.toString();
    }
}
