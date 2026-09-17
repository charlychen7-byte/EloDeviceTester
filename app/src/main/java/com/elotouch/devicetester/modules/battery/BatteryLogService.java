package com.elotouch.devicetester.modules.battery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import com.elotouch.devicetester.R;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Records one battery CSV row per minute for the charge/discharge test.
 *
 * <p>Runs as a foreground service holding a partial wake lock so recording
 * survives the screen going off and the operator leaving the page — a
 * charge or discharge run takes hours. A one-minute cadence rules out
 * {@code AlarmManager}, whose exact-while-idle alarms are throttled to roughly
 * one per nine minutes in Doze.
 *
 * <p>Progress is published through the static fields below: the Activity lives
 * in the same process and simply polls them once a second while it is visible.
 */
public class BatteryLogService extends Service {

    public static final String ACTION_START = "com.elotouch.devicetester.battery.START";
    public static final String ACTION_STOP = "com.elotouch.devicetester.battery.STOP";

    private static final long SAMPLE_INTERVAL_MS = 60_000L;
    private static final int NOTIFICATION_ID = 4101;
    private static final String CHANNEL_ID = "battery_log";
    private static final String CSV_HEADER =
            "time,level_pct,voltage_v,temperature_c,status,charge_type,health,current_ma";

    // ---- state the Activity polls (same process) ----
    public static volatile boolean running;
    public static volatile int recordCount;
    public static volatile long startTimeMs;
    public static volatile long endTimeMs;
    public static volatile long lastSampleMs;
    public static volatile String logPath;
    public static volatile String errorMessage;

    private final SimpleDateFormat recordStamp =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat fileStamp =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private ExecutorService executor;
    private PowerManager.WakeLock wakeLock;
    private BatteryManager batteryManager;
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
            stopRecording();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        createChannel();
        // Must go foreground immediately or the system kills us for not doing so.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(0),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);

        batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        acquireWakeLock();

        running = true;
        sampling = true;
        recordCount = 0;
        errorMessage = null;
        logPath = null;
        startTimeMs = System.currentTimeMillis();
        endTimeMs = 0;
        lastSampleMs = startTimeMs;

        executor = Executors.newSingleThreadExecutor();
        executor.execute(this::recordLoop);
        return START_NOT_STICKY;
    }

    private void stopRecording() {
        sampling = false;
        running = false;
        if (endTimeMs == 0) endTimeMs = System.currentTimeMillis();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
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

    // ------------------------------------------------------------------ record

    /** Writes one record a minute until stopped. Runs on the service's executor. */
    private void recordLoop() {
        File file;
        try {
            File dir = new File(externalOrInternalDir(), "battery");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir.getAbsolutePath());
            }
            file = new File(dir, "battery_log_" + fileStamp.format(new Date()) + ".csv");
        } catch (Exception e) {
            errorMessage = "无法创建日志文件 / cannot create log file: " + e.getMessage();
            stopRecording();
            return;
        }
        logPath = file.getAbsolutePath();

        while (sampling) {
            String error = append(file, buildRecord());
            lastSampleMs = System.currentTimeMillis();
            recordCount++;
            if (error != null) {
                errorMessage = error;
                stopRecording();
                return;
            }
            updateNotification(recordCount);
            // Sleep in slices so Stop takes effect promptly rather than after a
            // whole minute.
            for (long slept = 0; slept < SAMPLE_INTERVAL_MS && sampling; slept += 1000) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Where the CSV goes: app-private external files (no permission needed). */
    private File externalOrInternalDir() {
        File external = getExternalFilesDir(null);
        return external != null ? external : getFilesDir();
    }

    /** One CSV record from the current battery state. */
    private String buildRecord() {
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int pct = -1;
        double volts = 0;
        double celsius = 0;
        String status = "Unknown";
        String chargeType = "Unknown";
        String health = "Unknown";
        if (i != null) {
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            pct = scale > 0 ? Math.round(level * 100f / scale) : -1;
            volts = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0;
            celsius = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
            status = statusToken(i.getIntExtra(BatteryManager.EXTRA_STATUS, 0));
            chargeType = pluggedToken(i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
            health = healthToken(i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0));
        }

        String currentMa = "";
        if (batteryManager != null) {
            // CURRENT_NOW is microamps on most devices; unreadable on some ROMs.
            int microAmps = batteryManager.getIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (microAmps != Integer.MIN_VALUE && microAmps != 0) {
                currentMa = String.valueOf(Math.round(microAmps / 1000.0));
            }
        }

        return String.format(Locale.US, "%s,%d,%.3f,%.1f,%s,%s,%s,%s",
                recordStamp.format(new Date()), pct, volts, celsius,
                status, chargeType, health, currentMa);
    }

    /** Appends a record, writing the header first if the file is new. */
    private String append(File file, String record) {
        boolean needHeader = !file.exists() || file.length() == 0;
        try (FileWriter writer = new FileWriter(file, true)) {
            if (needHeader) writer.write(CSV_HEADER + "\n");
            writer.write(record + "\n");
            return null;
        } catch (IOException e) {
            return "写入失败 / write failed: " + e.getMessage();
        }
    }

    // ------------------------------------------------------- wake lock & notice

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "EloDeviceTester:batteryLog");
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
                "Battery log 充放电记录", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(int records) {
        Intent open = new Intent(this, BatteryActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("充放电记录中 / Battery logging")
                .setContentText(String.format(Locale.US,
                        "已记录 %d 条 / %d records，每分钟一条", records, records))
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(int records) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(records));
    }

    // CSV uses plain ASCII tokens so the file stays easy to parse and chart.

    private static String healthToken(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "OverVoltage";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            default: return "Unknown";
        }
    }

    private static String statusToken(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "NotCharging";
            default: return "Unknown";
        }
    }

    private static String pluggedToken(int p) {
        switch (p) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "AC";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "Wireless";
            case BatteryManager.BATTERY_PLUGGED_DOCK: return "Dock";
            case 0: return "Unplugged";
            default: return "Other";
        }
    }
}
