package com.elotouch.devicetester.modules.reboot;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;

/**
 * The device-admin component that makes {@code DevicePolicyManager.reboot()}
 * available to the reboot stress test.
 *
 * <p>Rebooting is a privileged operation: {@code android.permission.REBOOT} is
 * signature-level, so an ordinary APK cannot hold it. Becoming the device
 * owner is the way to get it without root or a platform signature — the
 * operator provisions it once per device with
 *
 * <pre>adb shell dpm set-device-owner \
 *     com.elotouch.devicetester/.modules.reboot.RebootAdminReceiver</pre>
 *
 * <p>which only succeeds on a device with no configured accounts (i.e. fresh
 * from factory reset). This receiver intentionally enforces no policy of its
 * own; it exists solely as the admin component that call names.
 */
public class RebootAdminReceiver extends DeviceAdminReceiver {

    /** The component name {@code dpm set-device-owner} has to be given. */
    public static ComponentName componentName(Context context) {
        return new ComponentName(context.getApplicationContext(),
                RebootAdminReceiver.class);
    }
}
