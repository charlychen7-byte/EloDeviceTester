package com.elotouch.devicetester.modules.cellular;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.net.InetAddress;
import java.util.Locale;

/**
 * Cellular / network connectivity module (PRD §3.17). Only shown on devices
 * with telephony hardware (HardwareDetector.Feature.CELLULAR); Wi-Fi-only
 * devices never see this module on the grid (built-in-hardware detection
 * model, PRD §5.3).
 */
@SuppressWarnings("deprecation")
public class CellularActivity extends BaseTestActivity {

    private TelephonyManager tm;
    private TextView simText;
    private TextView signalText;
    private TextView dataText;
    private boolean listening = false;

    private final PhoneStateListener listener = new PhoneStateListener() {
        @Override
        public void onSignalStrengthsChanged(SignalStrength signalStrength) {
            ui(() -> signalText.setText("Signal 信号强度：level " + signalStrength.getLevel() + " / 4"));
        }
    };

    @Override
    protected String title() {
        return "Cellular 蜂窝网络测试";
    }

    @Override
    protected void buildUi() {
        tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        addSectionTitle("SIM & Carrier / SIM 卡与运营商");
        simText = addInfo("Reading… 读取中…");

        addSectionTitle("Signal Strength / 信号强度");
        signalText = addInfo("Not started. 尚未开始。");

        addSectionTitle("Mobile Data / 移动数据连通性");
        dataText = addInfo("Not started. 尚未开始。");
        addButton("Ping Test / Ping 连通性测试", this::pingTest);

        requirePermission(Manifest.permission.READ_PHONE_STATE, this::start,
                () -> simText.setText("Permission denied. 权限受限：未授予电话状态权限。"));
    }

    @SuppressLint("MissingPermission")
    private void start() {
        if (tm == null) return;
        String carrier = tm.getNetworkOperatorName();
        int simState = tm.getSimState();
        simText.setText("SIM state SIM 状态：" + simStateStr(simState)
                + "\nCarrier 运营商：" + (carrier == null || carrier.isEmpty() ? "N/A" : carrier)
                + "\nNetwork type 网络类型：" + networkTypeStr(tm.getNetworkType()));
        if (!listening) {
            tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
            listening = true;
        }
    }

    private void pingTest() {
        dataText.setText("Pinging… Ping 中…");
        runAsync(() -> {
            try {
                long t0 = System.nanoTime();
                InetAddress addr = InetAddress.getByName("8.8.8.8");
                boolean reachable = addr.isReachable(3000);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String msg = reachable
                        ? String.format(Locale.US, "Ping 8.8.8.8 OK 成功：%d ms", ms)
                        : "Ping timeout (3s) 超时，请检查移动数据是否开启。";
                ui(() -> dataText.setText(msg));
            } catch (Exception e) {
                ui(() -> dataText.setText("Ping failed Ping 失败：" + e.getMessage()));
            }
        });
    }

    @Override
    protected void onStopTests() {
        if (listening && tm != null) {
            tm.listen(listener, PhoneStateListener.LISTEN_NONE);
            listening = false;
        }
    }

    private static String simStateStr(int s) {
        switch (s) {
            case TelephonyManager.SIM_STATE_READY: return "Ready 就绪";
            case TelephonyManager.SIM_STATE_ABSENT: return "Absent 未插卡";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED: return "PIN required 需要 PIN";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED: return "PUK required 需要 PUK";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED: return "Network locked 网络锁定";
            default: return "Unknown 未知";
        }
    }

    private static String networkTypeStr(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_LTE: return "4G LTE";
            case TelephonyManager.NETWORK_TYPE_NR: return "5G NR";
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_UMTS: return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_GPRS: return "2G";
            case TelephonyManager.NETWORK_TYPE_UNKNOWN: return "Unknown 未知";
            default: return "Type " + type;
        }
    }
}
