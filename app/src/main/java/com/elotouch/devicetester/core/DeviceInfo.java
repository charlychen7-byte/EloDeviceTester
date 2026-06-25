package com.elotouch.devicetester.core;

import android.os.Build;

/** Builds the always-on device info banner text (PRD §4). */
public final class DeviceInfo {

    private DeviceInfo() {}

    public static String banner() {
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "unknown";
        return "型号：" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "CPU 架构：" + abi;
    }
}
