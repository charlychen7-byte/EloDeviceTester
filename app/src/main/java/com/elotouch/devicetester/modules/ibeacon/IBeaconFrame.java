package com.elotouch.devicetester.modules.ibeacon;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.UUID;

/**
 * iBeacon frame codec (Apple proximity beacon format carried in BLE manufacturer data).
 *
 * <p>Manufacturer-specific data, company ID 0x004C (Apple), 23 bytes after the company ID:
 * <pre>
 *   [0]      0x02        iBeacon type
 *   [1]      0x15        length of the rest (21)
 *   [2..17]  proximity UUID (16 bytes, big-endian)
 *   [18..19] major (big-endian)
 *   [20..21] minor (big-endian)
 *   [22]     measured TX power at 1 m (signed dBm)
 * </pre>
 * Pure logic, no Android dependencies, so the byte layout is easy to reason about.
 */
public final class IBeaconFrame {

    /** Apple's Bluetooth company identifier. */
    public static final int APPLE_COMPANY_ID = 0x004C;
    /** Length of the manufacturer data after the company ID. */
    public static final int PAYLOAD_LENGTH = 23;

    public final String uuid;   // canonical upper-case form, e.g. "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"
    public final int major;     // 0..65535
    public final int minor;     // 0..65535
    public final int txPower;   // -128..127 dBm

    private IBeaconFrame(String uuid, int major, int minor, int txPower) {
        this.uuid = uuid;
        this.major = major;
        this.minor = minor;
        this.txPower = txPower;
    }

    /**
     * Parses manufacturer data that has already had its company ID removed
     * (as {@code ScanRecord.getManufacturerSpecificData(0x004C)} returns it).
     *
     * @return the decoded frame, or {@code null} if the bytes are not an iBeacon frame
     */
    public static IBeaconFrame parse(byte[] data) {
        if (data == null || data.length < PAYLOAD_LENGTH) return null;
        if ((data[0] & 0xFF) != 0x02 || (data[1] & 0xFF) != 0x15) return null;

        ByteBuffer bb = ByteBuffer.wrap(data, 2, 16);
        UUID uuid = new UUID(bb.getLong(), bb.getLong());
        int major = ((data[18] & 0xFF) << 8) | (data[19] & 0xFF);
        int minor = ((data[20] & 0xFF) << 8) | (data[21] & 0xFF);
        int tx = data[22]; // signed
        return new IBeaconFrame(uuid.toString().toUpperCase(Locale.US), major, minor, tx);
    }

    /**
     * Builds the manufacturer data payload (without the company ID) for an iBeacon advertisement.
     *
     * @throws IllegalArgumentException if the UUID is malformed or a field is out of range
     */
    public static byte[] build(String uuidText, int major, int minor, int txPower) {
        UUID uuid = parseUuid(uuidText);
        if (major < 0 || major > 0xFFFF) throw new IllegalArgumentException("Major must be 0-65535");
        if (minor < 0 || minor > 0xFFFF) throw new IllegalArgumentException("Minor must be 0-65535");
        if (txPower < -128 || txPower > 127) throw new IllegalArgumentException("TX power must be -128 to 127");

        byte[] out = new byte[PAYLOAD_LENGTH];
        out[0] = 0x02;
        out[1] = 0x15;
        ByteBuffer.wrap(out, 2, 16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        out[18] = (byte) (major >> 8);
        out[19] = (byte) major;
        out[20] = (byte) (minor >> 8);
        out[21] = (byte) minor;
        out[22] = (byte) txPower;
        return out;
    }

    /** Accepts UUIDs with or without dashes, in either case. */
    public static UUID parseUuid(String text) {
        String s = text == null ? "" : text.trim().replace("-", "");
        if (s.length() != 32 || !s.matches("[0-9a-fA-F]{32}")) {
            throw new IllegalArgumentException("UUID must be 32 hex digits (dashes optional)");
        }
        String dashed = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                + "-" + s.substring(16, 20) + "-" + s.substring(20);
        return UUID.fromString(dashed);
    }

    @Override
    public String toString() {
        return uuid + " / " + major + " / " + minor + " / " + txPower + " dBm";
    }
}
