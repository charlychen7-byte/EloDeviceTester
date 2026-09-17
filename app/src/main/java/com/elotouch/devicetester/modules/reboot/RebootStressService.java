package com.elotouch.devicetester.modules.reboot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.elotouch.devicetester.R;

import java.util.Locale;
import java.util.Map;

/**
 * Drives the reboot stress loop: record this boot, run the self-checks, wait
 * out the dwell, trigger the next reboot.
 *
 * <p>A foreground service rather than an Activity because the loop has to
 * continue with the screen off and with no operator present, and because
 * after a reboot there is no Activity to resume — Android 10+ blocks
 * background Activity starts, while a {@code BOOT_COMPLETED} receiver may
 * start a foreground service. The Activity is a viewer onto
 * {@link RebootStressState}; closing it does not stop the run.
 *
 * <p>The dwell is slept in one-second slices so a Stop lands within a second
 * instead of after a minute, and a partial wake lock is held so Doze cannot
 * stretch the dwell out.
 */
public class RebootStressService extends Service {

    private static final String TAG = "RebootStress";

    public static final String ACTION_START = "com.elotouch.devicetester.reboot.START";
    public static final String ACTION_BOOT = "com.elotouch.devicetester.reboot.BOOT";
    public static final String ACTION_STOP = "com.elotouch.devicetester.reboot.STOP";

    private static final int NOTIFICATION_ID = 4102;
    private static final int COMPLETION_NOTIFICATION_ID = 4103;
    private static final String CHANNEL_ID = "reboot_stress";

    /**
     * How long to keep re-polling the self-checks after a boot before calling
     * a peripheral absent.
     *
     * <p>{@code BOOT_COMPLETED} fires well before Wi-Fi has associated or a USB
     * device has enumerated, so checking once on arrival would fail almost
     * every cycle. Waiting turns the check into the question actually worth
     * asking — did this peripheral come back within a reasonable window of the
     * boot — and the cycle note records how long it took. Capped by the dwell,
     * since waiting longer than the gap between reboots makes no sense.
     */
    private static final long CHECK_SETTLE_MS = 30_000L;

    /**
     * How much longer than the boot itself the wall-clock gap may be before we
     * treat the cycle as having needed manual intervention. A clean
     * reboot spends only a few seconds powered down, so a couple of minutes of
     * slack still separates "normal" from "the operator had to hold the power
     * button".
     */
    private static final long HANG_MARGIN_MS = 120_000L;

    /** Retry delay when {@code reboot()} is refused (e.g. during a call). */
    private static final long REBOOT_RETRY_MS = 15_000L;

    /** Live countdown for the Activity to show. Same process, so a field does. */
    public static volatile int secondsToReboot = -1;

    private RebootStressState state;
    private PowerManager.WakeLock wakeLock;
    private Thread worker;
    private volatile boolean serviceStopping;

    // ------------------------------------------------------------------ starters

    public static void startForBoot(Context context) {
        start(context, ACTION_BOOT);
    }

    public static void startRun(Context context) {
        start(context, ACTION_START);
    }

    public static void stopRun(Context context) {
        start(context, ACTION_STOP);
    }

    private static void start(Context context, String action) {
        Intent intent = new Intent(context, RebootStressService.class).setAction(action);
        ContextCompat.startForegroundService(context.getApplicationContext(), intent);
    }

    // ------------------------------------------------------------------ lifecycle

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        state = new RebootStressState(this);

        createChannel();
        // Whatever the action, we were started as a foreground service and must
        // post the notification at once or the system kills us.
        ServiceCompat.startForeground(this, NOTIFICATION_ID,
                buildNotification("准备中 / preparing…"),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);

        if (ACTION_STOP.equals(action)) {
            state.stopRun();
            RebootStressLog.appendFooter(state.logPath(),
                    "stopped by operator after " + state.completed() + " cycle(s)");
            shutdown();
            return START_NOT_STICKY;
        }

        if (!state.isRunning()) {
            // Nothing armed (or a Stop landed while we were starting up).
            shutdown();
            return START_NOT_STICKY;
        }

        if (worker != null && worker.isAlive()) return START_STICKY;

        // elapsedRealtime() read here, at the earliest point in the boot we
        // control, is the closest we get to "how long did this boot take".
        final long bootElapsedMs = SystemClock.elapsedRealtime();
        final boolean recordBoot = ACTION_BOOT.equals(action);

        acquireWakeLock();
        if (recordBoot) bringUpActivity();
        worker = new Thread(() -> runLoop(recordBoot, bootElapsedMs), "reboot-stress");
        worker.start();
        // START_STICKY so a run that the system kills for memory pressure
        // mid-dwell is resumed rather than silently stalling until the next
        // boot. The restart delivers a null intent, which lands on the
        // recordBoot == false path and simply re-enters the dwell.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        serviceStopping = true;
        secondsToReboot = -1;
        releaseWakeLock();
        if (worker != null) worker.interrupt();
    }

    private void shutdown() {
        serviceStopping = true;
        secondsToReboot = -1;
        releaseWakeLock();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /**
     * Puts the module page back on screen after a boot, so the operator sees
     * the run's progress without having to reopen the app.
     *
     * <p>Android 10+ blocks background Activity starts, but a device owner's
     * UID is allowlisted — and this test cannot run without device-owner
     * status anyway, so the start is permitted for exactly the devices that
     * can get here. A ROM that refuses anyway costs nothing: the run carries
     * on in this service, and the ongoing notification still opens the page on
     * tap. Hence a logged warning rather than a failed cycle.
     */
    private void bringUpActivity() {
        Intent open = new Intent(this, RebootStressActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(open);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not bring up the activity after boot: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------- the loop

    private void runLoop(boolean recordBoot, long bootElapsedMs) {
        try {
            if (recordBoot) recordThisBoot(bootElapsedMs);

            if (state.completed() >= state.target()) {
                state.stopRun();
                RebootStressLog.appendFooter(state.logPath(),
                        "completed " + state.completed() + " cycle(s): " + state.summaryLine());
                // A separate dismissible notification is the completion record;
                // the service itself must go away rather than sit in the
                // foreground for the rest of the device's uptime.
                notifyCompletion("测试完成 / finished — " + state.summaryLine());
                shutdown();
                return;
            }

            int nextCycle = state.completed() + 1;
            if (!dwell(nextCycle)) {
                // Stop pressed during the countdown.
                RebootStressLog.appendFooter(state.logPath(),
                        "stopped by operator after " + state.completed() + " cycle(s)");
                shutdown();
                return;
            }
            triggerReboot(nextCycle);
        } catch (Throwable t) {
            Log.e(TAG, "run loop failed", t);
            state.setLastError("测试异常终止 / run aborted: " + t);
            state.stopRun();
            RebootStressLog.appendFooter(state.logPath(), "aborted: " + t);
            shutdown();
        }
    }

    /** Scores the boot we just completed and writes it to prefs and the log. */
    private void recordThisBoot(long bootElapsedMs) throws InterruptedException {
        int index = state.pendingCycle();
        if (index <= 0) index = state.completed() + 1;

        long nowMs = System.currentTimeMillis();
        long triggeredMs = state.rebootTriggeredMs();
        long gapMs = triggeredMs > 0 ? nowMs - triggeredMs : -1;

        StringBuilder note = new StringBuilder();
        Map<RebootChecks.Check, String> checks = runChecksWhenSettled(note);

        boolean pass = true;

        long bootLimitMs = state.bootLimitSec() * 1000L;
        if (bootElapsedMs > bootLimitMs) {
            pass = false;
            append(note, String.format(Locale.US, "boot %.1fs over limit %ds",
                    bootElapsedMs / 1000.0, state.bootLimitSec()));
        }
        for (Map.Entry<RebootChecks.Check, String> entry : checks.entrySet()) {
            if (RebootChecks.ABSENT.equals(entry.getValue())) {
                pass = false;
                append(note, entry.getKey().key + " absent");
            }
        }
        // A gap far larger than the boot itself means the device sat dead
        // between cycles and somebody power-cycled it by hand — the signature
        // of a hang that this test exists to catch.
        if (gapMs >= 0 && gapMs > bootElapsedMs + HANG_MARGIN_MS) {
            pass = false;
            append(note, String.format(Locale.US,
                    "gap %.0fs >> boot %.0fs, hang / manual power-on suspected",
                    gapMs / 1000.0, bootElapsedMs / 1000.0));
        }

        RebootCycle cycle = new RebootCycle(index, nowMs, bootElapsedMs, gapMs,
                pass, checks, note.toString());
        state.recordCycle(cycle);
        String error = RebootStressLog.append(state.logPath(), cycle);
        if (error != null) state.setLastError(error);
        Log.i(TAG, "cycle " + index + ": " + cycle.headline() + " " + note);
    }

    /**
     * Runs the enabled self-checks, re-polling anything still missing until it
     * appears or {@link #CHECK_SETTLE_MS} (capped by the dwell) runs out.
     * Appends the settle time to {@code note} when waiting was needed.
     */
    private Map<RebootChecks.Check, String> runChecksWhenSettled(StringBuilder note)
            throws InterruptedException {
        java.util.EnumSet<RebootChecks.Check> enabled = state.enabledChecks();
        Map<RebootChecks.Check, String> results = RebootChecks.run(this, enabled);
        if (enabled.isEmpty() || !hasAbsent(results)) return results;

        long budgetMs = Math.min(CHECK_SETTLE_MS, state.dwellSec() * 1000L);
        long startedAt = SystemClock.elapsedRealtime();
        notifyText("等待外设就绪 / waiting for peripherals…");
        while (SystemClock.elapsedRealtime() - startedAt < budgetMs) {
            if (serviceStopping || !state.isRunning()) break;
            Thread.sleep(1000);
            results = RebootChecks.run(this, enabled);
            if (!hasAbsent(results)) {
                append(note, String.format(Locale.US, "checks settled after %.0fs",
                        (SystemClock.elapsedRealtime() - startedAt) / 1000.0));
                break;
            }
        }
        return results;
    }

    private static boolean hasAbsent(Map<RebootChecks.Check, String> results) {
        for (String token : results.values()) {
            if (RebootChecks.ABSENT.equals(token)) return true;
        }
        return false;
    }

    /**
     * Counts down the dwell, refreshing the notification each second.
     *
     * @return true to proceed with the reboot, false if the run was stopped
     */
    private boolean dwell(int nextCycle) throws InterruptedException {
        for (int remaining = state.dwellSec(); remaining > 0; remaining--) {
            if (serviceStopping || !state.isRunning()) {
                secondsToReboot = -1;
                return false;
            }
            secondsToReboot = remaining;
            notifyText(String.format(Locale.US,
                    "第 %d/%d 轮 · 距下次重启 %ds",
                    nextCycle, state.target(), remaining));
            Thread.sleep(1000);
        }
        secondsToReboot = 0;
        return !serviceStopping && state.isRunning();
    }

    private void triggerReboot(int cycle) throws InterruptedException {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
            state.setLastError("已失去设备所有者权限，无法重启 / "
                    + "device owner status lost, cannot reboot");
            state.stopRun();
            RebootStressLog.appendFooter(state.logPath(),
                    "aborted: device owner status lost");
            shutdown();
            return;
        }

        notifyText("正在重启 / rebooting… (cycle " + cycle + ")");

        while (!serviceStopping && state.isRunning()) {
            try {
                // Stamped immediately before each attempt, not once before the
                // loop: the next boot measures its wall-clock gap against this,
                // and a retry-inflated stamp would look like a hang.
                // Written before the reboot so a device that never comes back
                // still leaves a record of which cycle it died on.
                state.markRebootTriggered(cycle);
                dpm.reboot(RebootAdminReceiver.componentName(this));
                return;
            } catch (IllegalStateException e) {
                // Documented behaviour: reboot() refuses while a call is
                // ongoing. Wait and try again rather than failing the run.
                Log.w(TAG, "reboot refused, retrying: " + e.getMessage());
                state.setLastError("重启被拒绝，重试中 / reboot refused, retrying: "
                        + e.getMessage());
                Thread.sleep(REBOOT_RETRY_MS);
            } catch (SecurityException e) {
                state.setLastError("重启被拒绝 / reboot denied: " + e.getMessage());
                state.stopRun();
                RebootStressLog.appendFooter(state.logPath(),
                        "aborted: reboot denied: " + e.getMessage());
                shutdown();
                return;
            }
        }
    }

    private static void append(StringBuilder notes, String text) {
        if (notes.length() > 0) notes.append("; ");
        notes.append(text);
    }

    // ------------------------------------------------------- wake lock & notice

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "EloDeviceTester:rebootStress");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Reboot stress 重启压力测试", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, RebootStressActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("重启压力测试 / Reboot stress")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void notifyText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    /** Dismissible end-of-run notice, under its own id so shutdown keeps it. */
    private void notifyCompletion(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(this, RebootStressActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(COMPLETION_NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("重启压力测试完成 / Reboot stress finished")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_launcher)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build());
    }
}
