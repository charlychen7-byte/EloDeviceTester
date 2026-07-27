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
        addSectionTitle("Elo Color Test Pattern");
        addInfo("Elo standard color / grayscale test patterns shown as a thumbnail "
                + "gallery; tap any pattern to view fullscreen.\n"
                + "Elo 标准色彩 / 灰阶测试图集，以缩略图展示，点击任意图案全屏显示。");
        addButton("Open Elo Color Test Pattern / 打开 Elo 色彩测试图", () ->
                startActivity(new Intent(this, EloColorPatternActivity.class)));

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

        addSectionTitle("Color Accuracy / 颜色准确度与色域");
        addInfo("Standard swatches with target RGB overlay, for external colorimeter or eye "
                + "comparison. Not an automatic pass/fail.\n"
                + "显示标准色块并叠加目标 RGB 数值，供外部仪器或人工比对，不做自动判定。");
        addButton("Start Color Accuracy Test / 开始色准测试", () ->
                startActivity(new Intent(this, ColorAccuracyActivity.class)));

        addSectionTitle("Uniformity / Mura / 屏幕均匀性");
        addInfo("Fullscreen low-gray fields for spotting backlight bleed and blotching by eye.\n"
                + "全屏低灰阶画面，肉眼观察漏光与色斑。");
        addButton("Start Mura Test / 开始均匀性测试", () ->
                startActivity(new Intent(this, MuraActivity.class)));

        addSectionTitle("Touch-Display Alignment / 触控显示对齐度");
        addInfo("Overlays a reference grid with the live touch point to reveal any offset.\n"
                + "叠加参考网格与实时触控点，检测是否存在偏移。");
        addButton("Start Alignment Test / 开始对齐度测试", () ->
                startActivity(new Intent(this, TouchAlignmentActivity.class)));
    }
}
