package com.elotouch.devicetester.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;

/**
 * Core anti-crash mechanism (PRD 5.3): probe whether a module's hardware
 * actually exists BEFORE the grid is rendered. Missing-hardware modules are
 * greyed out and non-clickable so we never call a driver that isn't there.
 */
public final class HardwareDetector {

    public enum Feature {
        ALWAYS, TOUCH, VIBRATOR, CAMERA, GPS, NFC, WIFI, SENSORS, CELLULAR, USB_HOST
    }

    private HardwareDetector() {}

    public static boolean isAvailable(Context c, Feature f) {
        PackageManager pm = c.getPackageManager();
        switch (f) {
            case ALWAYS:
                return true;
            case TOUCH:
                return pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
            case VIBRATOR:
                return hasVibrator(c);
            case CAMERA:
                return pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
            case GPS:
                return pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
                        || pm.hasSystemFeature(PackageManager.FEATURE_LOCATION);
            case NFC:
                return pm.hasSystemFeature(PackageManager.FEATURE_NFC);
            case WIFI:
                return pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
                        || pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
            case SENSORS:
                return hasAnySensor(c);
            case CELLULAR:
                return pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
            case USB_HOST:
                return pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST);
            default:
                return false;
        }
    }

    private static boolean hasVibrator(Context c) {
        android.os.Vibrator v = (android.os.Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        return v != null && v.hasVibrator();
    }

    private static boolean hasAnySensor(Context c) {
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return false;
        return sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
                || sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
                || sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null
                || sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
                || sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null;
    }
}
