package com.elotouch.devicetester.modules.display;

import android.content.Intent;
import android.view.Display;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;

/**
 * Display module menu (PRD §3.6): launches the fullscreen dead-pixel/solid-color
 * test and grayscale test, and shows the screen's nominal refresh rate.
 */
public class DisplayActivity extends BaseTestActivity {

    @Override
    protected String title() {
        return "Display 显示测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Dead Pixel & Solid Color / 坏点与纯色");
        addInfo("Fullscreen solid colors to spot dark/bright pixels and backlight bleed. "
                + "Tap to switch color, Back to exit.\n"
                + "全屏循环纯色，肉眼检测暗点、亮点、漏光、发黄等。点击切换颜色，返回键退出。");
        addButton("Start Color Test / 开始纯色测试", () ->
                startActivity(new Intent(this, ColorTestActivity.class)));

        addSectionTitle("Grayscale & Contrast / 灰阶与对比度");
        addInfo("Multi-level grayscale matrix for dark detail & color transition.\n"
                + "显示多级灰阶矩阵，检测暗部细节与色彩过渡。");
        addButton("Start Grayscale Test / 开始灰阶测试", () ->
                startActivity(new Intent(this, GrayscaleActivity.class)));

        addSectionTitle("Refresh Rate / 刷新率");
        Display d = getWindowManager().getDefaultDisplay();
        float hz = d.getRefreshRate();
        addInfo(String.format(Locale.US,
                "Nominal refresh rate 标称刷新率：%.1f Hz\n"
                        + "(Reads the system nominal value; the system may downclock, so it is "
                        + "not the measured frame rate. 读取系统标称值，系统可能动态降频，非实测帧率)", hz));
    }
}
