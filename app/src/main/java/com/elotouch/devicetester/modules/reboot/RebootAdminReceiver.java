package com.elotouch.devicetester.modules.reboot;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import java.io.IOException;

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
 *
 * <p>It also receives the outcome of {@code DevicePolicyManager.requestBugreport()},
 * which {@link RebootStressService} fires on every failed cycle. Android
 * requires whoever is standing at the device to accept a system prompt before
 * the capture is handed to us — even a device owner cannot skip that consent
 * step — so these callbacks may land well after the cycle that triggered them,
 * hence reading which cycle from {@link RebootStressState#bugreportCycle()}
 * rather than trusting the current one.
 */
public class RebootAdminReceiver extends DeviceAdminReceiver {

    /** The component name {@code dpm set-device-owner} has to be given. */
    public static ComponentName componentName(Context context) {
        return new ComponentName(context.getApplicationContext(),
                RebootAdminReceiver.class);
    }

    @Override
    public void onBugreportShared(@NonNull Context context, @NonNull Intent intent,
                                  @NonNull String bugreportHash) {
        RebootStressState state = new RebootStressState(context);
        int cycle = state.bugreportCycle();
        try {
            String path = RebootStressLog.saveBugreport(context, intent.getData(), cycle);
            RebootStressLog.appendComment(state.logPath(), String.format(
                    "bugreport for cycle %d saved to %s (sha256=%s)", cycle, path, bugreportHash));
        } catch (IOException e) {
            RebootStressLog.appendComment(state.logPath(), String.format(
                    "bugreport for cycle %d could not be saved: %s", cycle, e.getMessage()));
        }
    }

    @Override
    public void onBugreportFailed(@NonNull Context context, @NonNull Intent intent,
                                  int failureCode) {
        RebootStressState state = new RebootStressState(context);
        RebootStressLog.appendComment(state.logPath(), String.format(
                "bugreport for cycle %d failed, code=%d", state.bugreportCycle(), failureCode));
    }

    @Override
    public void onBugreportSharingDeclined(@NonNull Context context, @NonNull Intent intent) {
        RebootStressState state = new RebootStressState(context);
        RebootStressLog.appendComment(state.logPath(), String.format(
                "bugreport for cycle %d declined by operator", state.bugreportCycle()));
    }
}
