package com.elotouch.devicetester.modules.ethernet;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.RouteInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort extraction of human-readable Ethernet link details.
 * Link speed/duplex have no stable public Android API, so this reads the
 * kernel's sysfs network-interface files directly and fails gracefully
 * (returns null) when they're unreadable.
 */
final class EthernetLinkInfo {

    private EthernetLinkInfo() {
    }

    static final class IpEntry {
        final String address;
        final String subnetMask; // dotted-decimal, IPv4 only; null for IPv6
        final int prefixLength;

        IpEntry(String address, String subnetMask, int prefixLength) {
            this.address = address;
            this.subnetMask = subnetMask;
            this.prefixLength = prefixLength;
        }
    }

    static final class Snapshot {
        final List<IpEntry> ipAddresses;
        final String gateway;
        final List<String> dnsServers;
        final String interfaceName;

        Snapshot(List<IpEntry> ipAddresses, String gateway, List<String> dnsServers,
                 String interfaceName) {
            this.ipAddresses = ipAddresses;
            this.gateway = gateway;
            this.dnsServers = dnsServers;
            this.interfaceName = interfaceName;
        }
    }

    static Snapshot fromLinkProperties(LinkProperties lp) {
        List<IpEntry> ips = new ArrayList<>();
        for (LinkAddress addr : lp.getLinkAddresses()) {
            InetAddress a = addr.getAddress();
            int prefixLength = addr.getPrefixLength();
            String mask = (a instanceof Inet4Address) ? prefixToIpv4Mask(prefixLength) : null;
            ips.add(new IpEntry(a.getHostAddress(), mask, prefixLength));
        }
        String gateway = null;
        for (RouteInfo route : lp.getRoutes()) {
            if (route.isDefaultRoute() && route.hasGateway()) {
                gateway = route.getGateway().getHostAddress();
                break;
            }
        }
        List<String> dns = new ArrayList<>();
        for (InetAddress addr : lp.getDnsServers()) {
            dns.add(addr.getHostAddress());
        }
        return new Snapshot(ips, gateway, dns, lp.getInterfaceName());
    }

    private static String prefixToIpv4Mask(int prefixLength) {
        long mask = prefixLength == 0 ? 0 : (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
        return String.format(Locale.US, "%d.%d.%d.%d",
                (mask >> 24) & 0xFF, (mask >> 16) & 0xFF, (mask >> 8) & 0xFF, mask & 0xFF);
    }

    /** e.g. "1000 Mbps, Full Duplex". Null if sysfs doesn't expose this. */
    static String describeLinkSpeed(String interfaceName) {
        if (interfaceName == null || interfaceName.isEmpty()) return null;
        Integer speedMbps = readSysfsInt("/sys/class/net/" + interfaceName + "/speed");
        String duplex = readSysfsString("/sys/class/net/" + interfaceName + "/duplex");

        StringBuilder sb = new StringBuilder();
        if (speedMbps != null && speedMbps > 0) {
            sb.append(speedMbps).append(" Mbps");
        }
        if ("full".equalsIgnoreCase(duplex) || "half".equalsIgnoreCase(duplex)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("full".equalsIgnoreCase(duplex) ? "Full Duplex" : "Half Duplex");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static Integer readSysfsInt(String path) {
        String s = readSysfsString(path);
        if (s == null) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readSysfsString(String path) {
        File f = new File(path);
        if (!f.canRead()) return null;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line = r.readLine();
            return line != null ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
