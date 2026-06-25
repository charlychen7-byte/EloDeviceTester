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
        return "Display / 显示测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("坏点与纯色测试");
        addInfo("全屏循环切换纯色，肉眼检测暗点、亮点、漏光、屏幕发黄等。点击屏幕切换颜色，返回键退出。");
        addButton("开始纯色测试", () ->
                startActivity(new Intent(this, ColorTestActivity.class)));

        addSectionTitle("灰阶与对比度测试");
        addInfo("显示多级灰阶矩阵，检测暗部细节与色彩过渡。");
        addButton("开始灰阶测试", () ->
                startActivity(new Intent(this, GrayscaleActivity.class)));

        addSectionTitle("刷新率");
        Display d = getWindowManager().getDefaultDisplay();
        float hz = d.getRefreshRate();
        addInfo(String.format(Locale.US,
                "当前屏幕标称刷新率：%.1f Hz\n（读取系统标称值，系统可能动态降频，标称值不等于实测帧率）", hz));
    }
}
