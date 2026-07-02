package com.elotouch.devicetester.modules.sensors;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;

/**
 * Sensors module (PRD §3.11): live readouts for accelerometer, gyroscope,
 * light, proximity and magnetometer, plus a compass heading derived from the
 * accelerometer + magnetometer. Only sensors actually present are shown.
 */
public class SensorsActivity extends BaseTestActivity implements SensorEventListener {

    private SensorManager sm;
    private TextView accelText, gyroText, lightText, proxText, magText, headingText;

    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];
    private boolean haveGravity, haveMag;

    @Override
    protected String title() {
        return "Sensors 传感器测试";
    }

    @Override
    protected void buildUi() {
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        accelText = addSensorSection("Accelerometer 加速度计", Sensor.TYPE_ACCELEROMETER);
        gyroText = addSensorSection("Gyroscope 陀螺仪", Sensor.TYPE_GYROSCOPE);
        lightText = addSensorSection("Light 光线传感器", Sensor.TYPE_LIGHT);
        proxText = addSensorSection("Proximity 距离传感器", Sensor.TYPE_PROXIMITY);
        magText = addSensorSection("Magnetometer 磁力计", Sensor.TYPE_MAGNETIC_FIELD);

        if (has(Sensor.TYPE_ACCELEROMETER) && has(Sensor.TYPE_MAGNETIC_FIELD)) {
            addSectionTitle("Compass 电子罗盘");
            addInfo("Move away from magnets and do a figure-8 calibration for accuracy.\n"
                    + "远离磁性物体并做 8 字校准后读数更准。");
            headingText = addInfo("Heading 朝向：—");
        }
    }

    private TextView addSensorSection(String title, int type) {
        addSectionTitle(title);
        if (!has(type)) {
            addInfo("Not supported. 硬件不支持。");
            return null;
        }
        return addInfo("Waiting for data… 等待数据…");
    }

    private boolean has(int type) {
        return sm != null && sm.getDefaultSensor(type) != null;
    }

    private void register(int type) {
        Sensor s = sm.getDefaultSensor(type);
        if (s != null) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onResume() {
        super.onResume();
        register(Sensor.TYPE_ACCELEROMETER);
        register(Sensor.TYPE_GYROSCOPE);
        register(Sensor.TYPE_LIGHT);
        register(Sensor.TYPE_PROXIMITY);
        register(Sensor.TYPE_MAGNETIC_FIELD);
    }

    @Override
    protected void onStopTests() {
        if (sm != null) sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float[] v = e.values;
        switch (e.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                if (accelText != null) accelText.setText(xyz(v));
                System.arraycopy(v, 0, gravity, 0, 3);
                haveGravity = true;
                updateHeading();
                break;
            case Sensor.TYPE_GYROSCOPE:
                if (gyroText != null) gyroText.setText(xyz(v));
                break;
            case Sensor.TYPE_LIGHT:
                if (lightText != null)
                    lightText.setText(String.format(Locale.US, "Illuminance 照度：%.1f lux", v[0]));
                break;
            case Sensor.TYPE_PROXIMITY:
                if (proxText != null)
                    proxText.setText(String.format(Locale.US, "Distance 距离：%.1f cm", v[0]));
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                if (magText != null) magText.setText(xyz(v));
                System.arraycopy(v, 0, geomagnetic, 0, 3);
                haveMag = true;
                updateHeading();
                break;
        }
    }

    private void updateHeading() {
        if (headingText == null || !haveGravity || !haveMag) return;
        float[] r = new float[9];
        float[] orientation = new float[3];
        if (SensorManager.getRotationMatrix(r, null, gravity, geomagnetic)) {
            SensorManager.getOrientation(r, orientation);
            float deg = (float) Math.toDegrees(orientation[0]);
            if (deg < 0) deg += 360;
            headingText.setText(String.format(Locale.US, "Heading 朝向：%.0f° (%s)", deg, compassDir(deg)));
        }
    }

    private static String compassDir(float deg) {
        String[] dirs = {"N 北", "NE 东北", "E 东", "SE 东南", "S 南", "SW 西南", "W 西", "NW 西北"};
        return dirs[(int) ((deg + 22.5) / 45) % 8];
    }

    private static String xyz(float[] v) {
        return String.format(Locale.US, "X=%.2f  Y=%.2f  Z=%.2f", v[0], v[1], v[2]);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }
}
