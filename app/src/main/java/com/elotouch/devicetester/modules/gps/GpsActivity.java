package com.elotouch.devicetester.modules.gps;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;

/**
 * GPS module (PRD §3.4): satellite scan (visible count + per-satellite CN0
 * across GNSS constellations) and a position fix (lat/lon/alt/accuracy).
 */
public class GpsActivity extends BaseTestActivity {

    private LocationManager lm;
    private TextView satText;
    private TextView fixText;
    private Button startButton;
    private Button stopButton;
    private boolean listening = false;
    /** Last satellite report, kept so Stop can leave the readings on screen. */
    private String lastSatText;
    private boolean hasFix;

    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            int count = status.getSatelliteCount();
            int used = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                if (status.usedInFix(i)) used++;
                sb.append(String.format(Locale.US, "%s  CN0=%.0f dBHz%s\n",
                        constellation(status.getConstellationType(i)),
                        status.getCn0DbHz(i),
                        status.usedInFix(i) ? "  (in fix 定位中)" : ""));
            }
            final int total = count, usedF = used;
            final String detail = sb.toString();
            ui(() -> {
                lastSatText = "Visible 可见卫星：" + total
                        + "    In fix 参与定位：" + usedF + "\n" + detail;
                satText.setText(lastSatText);
            });
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location loc) {
            ui(() -> {
                hasFix = true;
                fixText.setText(String.format(Locale.US,
                        "Fix acquired 已获取定位：\nLatitude 纬度：%.6f\nLongitude 经度：%.6f\n"
                                + "Altitude 海拔：%.1f m\nAccuracy 精度：%.1f m",
                        loc.getLatitude(), loc.getLongitude(),
                        loc.getAltitude(), loc.getAccuracy()));
            });
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) { }

        @Override
        public void onProviderDisabled(@NonNull String provider) { }
    };

    @Override
    protected String title() {
        return "GPS 定位测试";
    }

    @Override
    protected void buildUi() {
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        addSectionTitle("Satellite Scan / 卫星扫描");
        satText = addInfo("Not started. 尚未开始。");

        addSectionTitle("Position Fix / 经纬度定位");
        fixText = addInfo("No fix yet. 尚未获取定位锁。");

        startButton = addButton("Start Scan / Locate / 开始搜星定位", this::start);
        stopButton = addButton("Stop / 停止", this::stop);
        addButton("Open Location Settings / 打开定位系统设置", () ->
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        updateButtons();
    }

    /** Single place the button states come from, so they can never disagree. */
    private void updateButtons() {
        startButton.setEnabled(!listening);
        stopButton.setEnabled(listening);
    }

    private void start() {
        if (listening) return;
        if (lm == null) {
            satText.setText("No location service 无定位服务。");
            return;
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            toast("Location is off; enable it in Settings. 系统定位未开启，请先在设置中开启");
        }
        requirePermission(Manifest.permission.ACCESS_FINE_LOCATION,
                this::beginUpdates,
                () -> {
                    satText.setText("Permission denied. 权限受限：未授予定位权限。");
                    fixText.setText("Permission denied. 权限受限。");
                    updateButtons();
                });
    }

    @SuppressLint("MissingPermission")
    private void beginUpdates() {
        if (lm == null || listening) return;

        boolean gnssRegistered = false;
        try {
            lm.registerGnssStatusCallback(gnssCallback, main);
            gnssRegistered = true;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0,
                    locationListener, main.getLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            // Roll the half-done registration back, or the callback stays live with
            // no way to stop it and doubles up on the next Start.
            if (gnssRegistered) {
                try {
                    lm.unregisterGnssStatusCallback(gnssCallback);
                } catch (Exception ignored) {
                    // Nothing more we can do; the module stays in the stopped state.
                }
            }
            satText.setText("Cannot start scan 无法启动搜星：" + e.getMessage());
            updateButtons();
            return;
        }

        listening = true;
        hasFix = false;
        lastSatText = null;
        satText.setText(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? "Searching… 搜星中…"
                : "Searching… 搜星中…（系统定位未开启，可能收不到卫星 / location is off）");
        fixText.setText("Waiting for fix… 等待定位锁…");
        updateButtons();
    }

    private void stop() {
        if (lm == null || !listening) return;
        listening = false;
        try {
            lm.unregisterGnssStatusCallback(gnssCallback);
            lm.removeUpdates(locationListener);
        } catch (Exception ignored) {
            // Already gone; the state below is what matters.
        }
        // Stop has to be visible: leave the readings on screen but say it ended,
        // otherwise the page still claims to be searching.
        satText.setText(lastSatText != null
                ? lastSatText + "(Stopped 已停止)"
                : "Stopped 已停止，未收到卫星信息。");
        if (!hasFix) fixText.setText("Stopped 已停止，未获取定位锁。");
        updateButtons();
    }

    @Override
    protected void onStopTests() {
        // Also reached from onPause, which is why stop() must leave the page in a
        // state that still reads correctly when the operator comes back.
        stop();
    }

    private static String constellation(int type) {
        switch (type) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLONASS";
            case GnssStatus.CONSTELLATION_BEIDOU: return "BeiDou 北斗";
            case GnssStatus.CONSTELLATION_GALILEO: return "Galileo";
            case GnssStatus.CONSTELLATION_QZSS: return "QZSS";
            case GnssStatus.CONSTELLATION_SBAS: return "SBAS";
            default: return "Other 其他";
        }
    }
}
