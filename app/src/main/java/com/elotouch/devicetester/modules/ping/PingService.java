package com.elotouch.devicetester.modules.ping;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.elotouch.devicetester.R;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the continuous Custom Ping test (one ICMP echo per second).
 *
 * <p>Runs as a foreground service so the test keeps going after the operator
 * sends {@link PingTestActivity} to the background; the Activity only starts,
 * stops and polls it. Progress is published through the static fields below,
 * which the Activity reads once a second while it is visible.
 *
 * <p>Sends one {@link Ping#once} per second rather than a long-lived ping
 * process: that counts every lost packet exactly (a timed-out packet produces no
 * output line of its own) and makes Stop take effect within one interval.
 */
public class PingService extends Service {

    public static final String ACTION_START = "com.elotouch.devicetester.ping.START";
    public static final String ACTION_STOP = "com.elotouch.devicetester.ping.STOP";
    public static final String EXTRA_HOST = "host";
    /** Test length in ms; 0 = run until stopped. */
    public static final String EXTRA_DURATION_MS = "duration_ms";

    private static final int MAX_LOG_LINES = 200;
    private static final long PING_INTERVAL_MS = 1000L;
    /** Kept below the interval so the loop holds its 1 Hz cadence. */
    private static final int PING_TIMEOUT_SECONDS = 1;
    private static final int NOTIFICATION_ID = 4102;
    private static final String CHANNEL_ID = "ping_test";

    // ---- state the Activity polls (same process) ----
    public static volatile boolean running;
    /** True once Stop was pressed; the loop is draining its last packet. */
    public static volatile boolean userStopRequested;
    public static volatile boolean durationReached;
    public static volatile String fatalError;
    public static volatile String target;
    public static volatile long durationMs;
    public static volatile long startTimeMs;
    public static volatile long endTimeMs;
    public static volatile int sent;
    public static volatile int lost;
    public static volatile int latencySamples;
    public static volatile double minMs;
    public static volatile double maxMs;
    public static volatile double sumMs;
    /** Bumped on every log change so the Activity re-renders the log only when needed. */
    public static volatile int logVersion;

    private static final Deque<String> LOG_LINES = new ArrayDeque<>();

    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean sampling;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            if (running) {
                userStopRequested = true;
                sampling = false;
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (running || intent == null) return START_NOT_STICKY;

        String host = intent.getStringExtra(EXTRA_HOST);
        if (host == null || host.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        target = host;
        createChannel();
        // Must go foreground immediately or the system kills us for not doing so.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(0, 0),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);

        acquireWakeLock();

        durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L);
        running = true;
        sampling = true;
        userStopRequested = false;
        durationReached = false;
        fatalError = null;
        sent = 0;
        lost = 0;
        latencySamples = 0;
        minMs = Double.MAX_VALUE;
        maxMs = 0;
        sumMs = 0;
        endTimeMs = 0;
        startTimeMs = System.currentTimeMillis();
        clearLog();

        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> pingLoop(host));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sampling = false;
        running = false;
        if (endTimeMs == 0) endTimeMs = System.currentTimeMillis();
        releaseWakeLock();
        if (executor != null) executor.shutdownNow();
    }

    // ------------------------------------------------------------------ ping

    private void pingLoop(String host) {
        while (sampling) {
            if (durationMs > 0 && System.currentTimeMillis() - startTimeMs >= durationMs) {
                durationReached = true;
                break;
            }
            long loopStartNs = System.nanoTime();
            Ping.Result r = Ping.once(host, PING_TIMEOUT_SECONDS);
            sent++;
            int seq = sent;

            if (r.fatalMessage != null) {
                fatalError = r.fatalMessage;
                appendLog(seq + ": " + r.fatalMessage);
                break;
            }
            if (r.received) {
                if (!Double.isNaN(r.rttMs)) {
                    latencySamples++;
                    minMs = Math.min(minMs, r.rttMs);
                    maxMs = Math.max(maxMs, r.rttMs);
                    sumMs += r.rttMs;
                }
                appendLog(String.format(Locale.US, "%d: reply from %s  time=%s ms  ttl=%s",
                        seq, host,
                        Double.isNaN(r.rttMs) ? "?" : String.format(Locale.US, "%.1f", r.rttMs),
                        r.ttl != null ? r.ttl : "?"));
            } else {
                lost++;
                appendLog(seq + ": " + r.failureText());
            }
            updateNotification();

            long elapsedMs = (System.nanoTime() - loopStartNs) / 1_000_000;
            long sleepMs = PING_INTERVAL_MS - elapsedMs;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        finishSession();
    }

    private void finishSession() {
        sampling = false;
        running = false;
        endTimeMs = System.currentTimeMillis();
        releaseWakeLock();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ------------------------------------------------------------------- log

    private static synchronized void appendLog(String line) {
        if (LOG_LINES.size() >= MAX_LOG_LINES) LOG_LINES.removeFirst();
        LOG_LINES.addLast(line);
        logVersion++;
    }

    private static synchronized void clearLog() {
        LOG_LINES.clear();
        logVersion++;
    }

    /** The retained log lines, one per line of text. */
    static synchronized String logText() {
        StringBuilder sb = new StringBuilder();
        for (String l : LOG_LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    // -------------------------------------------------- wake lock & notification

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EloDeviceTester:ping");
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
                "Custom Ping 自定义 Ping", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(int total, int lostCount) {
        Intent open = new Intent(this, PingTestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Ping 测试中 / Ping test running")
                .setContentText(String.format(Locale.US,
                        "目标 %s  已发送 %d  丢失 %d", target, total, lostCount))
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(sent, lost));
    }
}
