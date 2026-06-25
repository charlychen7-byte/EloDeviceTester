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
    private boolean listening = false;

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
                        status.usedInFix(i) ? "  (定位中)" : ""));
            }
            final int total = count, usedF = used;
            final String detail = sb.toString();
            ui(() -> satText.setText("可见卫星：" + total + "    参与定位：" + usedF + "\n" + detail));
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location loc) {
            ui(() -> fixText.setText(String.format(Locale.US,
                    "已获取定位 (Fix)：\n纬度：%.6f\n经度：%.6f\n海拔：%.1f m\n精度：%.1f m",
                    loc.getLatitude(), loc.getLongitude(), loc.getAltitude(), loc.getAccuracy())));
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) { }

        @Override
        public void onProviderDisabled(@NonNull String provider) { }
    };

    @Override
    protected String title() {
        return "GPS / 定位测试";
    }

    @Override
    protected void buildUi() {
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        addSectionTitle("卫星扫描");
        satText = addInfo("尚未开始。");

        addSectionTitle("经纬度定位");
        fixText = addInfo("尚未获取定位锁。");

        addButton("开始搜星 / 定位", this::start);
        addButton("停止", this::stop);
        addButton("打开定位系统设置", () ->
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
    }

    private void start() {
        if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            toast("系统定位未开启，请先在设置中开启");
        }
        requirePermission(Manifest.permission.ACCESS_FINE_LOCATION,
                this::beginUpdates,
                () -> {
                    satText.setText("权限受限：未授予定位权限。");
                    fixText.setText("权限受限。");
                });
    }

    @SuppressLint("MissingPermission")
    private void beginUpdates() {
        if (lm == null || listening) return;
        try {
            lm.registerGnssStatusCallback(gnssCallback, main);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener, main.getLooper());
            listening = true;
            satText.setText("搜星中…");
            fixText.setText("等待定位锁…");
        } catch (SecurityException e) {
            satText.setText("权限受限：" + e.getMessage());
        }
    }

    private void stop() {
        if (lm == null || !listening) return;
        lm.unregisterGnssStatusCallback(gnssCallback);
        lm.removeUpdates(locationListener);
        listening = false;
    }

    @Override
    protected void onStopTests() {
        stop();
    }

    private static String constellation(int type) {
        switch (type) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLONASS";
            case GnssStatus.CONSTELLATION_BEIDOU: return "北斗";
            case GnssStatus.CONSTELLATION_GALILEO: return "Galileo";
            case GnssStatus.CONSTELLATION_QZSS: return "QZSS";
            case GnssStatus.CONSTELLATION_SBAS: return "SBAS";
            default: return "其他";
        }
    }
}
