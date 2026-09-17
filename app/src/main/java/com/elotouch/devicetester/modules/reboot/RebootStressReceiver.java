package com.elotouch.devicetester.modules.reboot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restarts the reboot stress run after each boot.
 *
 * <p>Only hands off to {@link RebootStressService} when a run is actually
 * armed, so a device that merely has the app installed never does anything
 * unusual at boot. The service — not this receiver — does the work, because a
 * broadcast receiver has about ten seconds to live and the cycle involves
 * file I/O plus a dwell of a minute or more.
 *
 * <p>Starting a foreground service from here is permitted under the Android
 * 12+ background-start restrictions on two independent grounds: {@code
 * ACTION_BOOT_COMPLETED} is an exempt broadcast, and a device owner is an
 * exempt app. This test requires device-owner status anyway.
 */
public class RebootStressReceiver extends BroadcastReceiver {

    private static final String TAG = "RebootStress";

    /** Some vendor ROMs send this instead of BOOT_COMPLETED after a fast boot. */
    private static final String ACTION_QUICKBOOT_POWERON =
            "android.intent.action.QUICKBOOT_POWERON";
    private static final String ACTION_HTC_QUICKBOOT_POWERON =
            "com.htc.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !ACTION_QUICKBOOT_POWERON.equals(action)
                && !ACTION_HTC_QUICKBOOT_POWERON.equals(action)) {
            return;
        }

        RebootStressState state = new RebootStressState(context);
        if (!state.isRunning()) return;

        Log.i(TAG, "boot detected, resuming run at cycle " + state.pendingCycle());
        RebootStressService.startForBoot(context);
    }
}
