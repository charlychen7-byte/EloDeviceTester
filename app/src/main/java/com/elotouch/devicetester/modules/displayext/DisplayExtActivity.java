package com.elotouch.devicetester.modules.displayext;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.net.InetAddress;
import java.util.Locale;

/**
 * Secondary display & wired network module (PRD §3.16). Both sub-features are
 * pluggable peripherals — a docked customer display or an Ethernet dongle —
 * so this page detects them live rather than the grid greying out (PRD §5.3).
 */
public class DisplayExtActivity extends BaseTestActivity {

    private TextView displayText;
    private TextView networkText;
    private PatternPresentation presentation;

    @Override
    protected String title() {
        return "Display Ext 双屏/网络测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Secondary Display / 客显副屏输出");
        displayText = addInfo("");
        addButton("Show Test Pattern / 推送测试图案到副屏", this::showPattern);
        addButton("Dismiss / 关闭副屏图案", this::dismissPattern);

        addSectionTitle("Wired Network / 有线网络");
        networkText = addInfo("");
        addButton("Ping Test / Ping 连通性测试", this::pingTest);

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays();
        int extra = 0;
        for (Display d : displays) if (d.getDisplayId() != Display.DEFAULT_DISPLAY) extra++;
        displayText.setText(extra > 0
                ? extra + " secondary display(s) found 检测到副屏：" + extra
                : "No secondary display detected. 未检测到副屏，请连接客显后重试。");

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        boolean ethernetUp = false;
        StringBuilder ips = new StringBuilder();
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                ethernetUp = true;
                LinkProperties lp = cm.getLinkProperties(net);
                if (lp != null) {
                    for (android.net.LinkAddress a : lp.getLinkAddresses()) {
                        ips.append(a.getAddress().getHostAddress()).append(' ');
                    }
                }
            }
        }
        boolean finalEthernetUp = ethernetUp;
        networkText.setText(finalEthernetUp
                ? "Ethernet connected 已连接：" + ips
                : "No Ethernet link detected. 未检测到以太网连接。");
    }

    private void showPattern() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays();
        for (Display d : displays) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) {
                dismissPattern();
                presentation = new PatternPresentation(this, d);
                presentation.show();
                return;
            }
        }
        toast("No secondary display. 未检测到副屏。");
    }

    private void dismissPattern() {
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
    }

    private void pingTest() {
        networkText.setText("Pinging… Ping 中…");
        runAsync(() -> {
            try {
                long t0 = System.nanoTime();
                InetAddress addr = InetAddress.getByName("8.8.8.8");
                boolean reachable = addr.isReachable(3000);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String msg = reachable
                        ? String.format(Locale.US, "Ping 8.8.8.8 OK 成功：%d ms", ms)
                        : "Ping timeout (3s) 超时。";
                ui(() -> networkText.setText(msg));
            } catch (Exception e) {
                ui(() -> networkText.setText("Ping failed Ping 失败：" + e.getMessage()));
            }
        });
    }

    @Override
    protected void onStopTests() {
        dismissPattern();
    }

    private static class PatternPresentation extends Presentation {
        private static final int[] COLORS = {Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK};
        private int index = 0;

        PatternPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(COLORS[0]);
            TextView hint = new TextView(getContext());
            hint.setText("Elo DeviceTester — Secondary Display Test\n客显测试图案 · 点击切换颜色");
            hint.setTextColor(Color.DKGRAY);
            hint.setTextSize(16);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            root.addView(hint, lp);
            root.setOnClickListener(v -> {
                index = (index + 1) % COLORS.length;
                root.setBackgroundColor(COLORS[index]);
            });
            setContentView(root);
        }
    }
}
