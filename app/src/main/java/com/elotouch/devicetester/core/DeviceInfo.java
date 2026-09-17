package com.elotouch.devicetester.core;

import android.os.Build;

/** Builds the always-on device info banner text (PRD §4). */
public final class DeviceInfo {

    private DeviceInfo() {}

    public static String banner() {
        return "Model 型号：" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
    }
}
