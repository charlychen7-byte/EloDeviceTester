package com.elotouch.devicetester.modules.touch;

import android.content.Intent;

import com.elotouch.devicetester.core.BaseTestActivity;

/**
 * Touch module menu (PRD §3.7): multi-touch tracking and grid dead-zone test.
 */
public class TouchActivity extends BaseTestActivity {

    @Override
    protected String title() {
        return "Touch 触摸测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Elo Draw / 触控绘图");
        addInfo("Full EloDraw touch tool: multi-touch line/point drawing, down/up markers, "
                + "grid dead-zone counting, live touch-rate & coordinate readout, and screenshot. "
                + "Long-press hides the buttons; Back to exit.\n"
                + "EloDraw 触控绘图工具：多点画线/画点、按下与抬起标记、网格死角计数、"
                + "实时报点率与坐标显示、截图。长按隐藏按钮，返回键退出。");
        addButton("Start Elo Draw / 开始触控绘图", () ->
                startActivity(new Intent(this, EloDrawActivity.class)));

        addSectionTitle("Multi-touch / 多点触控");
        addInfo("Press and drag with multiple fingers; shows live trails and touch count. "
                + "Back to exit.\n多指同时按压并划线，实时显示触控轨迹与触点数。返回键退出。");
        addButton("Start Multi-touch Test / 开始多点触控测试", () ->
                startActivity(new Intent(this, MultiTouchActivity.class)));

        addSectionTitle("Accuracy / Dead-zone Grid / 触控精准度与死角");
        addInfo("Fullscreen grid; cells turn green where a finger passes, revealing edge dead "
                + "zones. Back to exit.\n全屏铺满网格，手指划过的方格变色，检测边缘与各区域触控死角。返回键退出。");
        addButton("Start Grid Test / 开始网格死角测试", () ->
                startActivity(new Intent(this, TouchGridActivity.class)));

        addSectionTitle("Sampling Rate & Pressure / 采样率与压感");
        addInfo("Live touch report rate (Hz) and pressure/contact-size readouts, if supported by "
                + "the digitizer. Back to exit.\n"
                + "实时显示触控报点率（Hz）与压力/接触面积（若硬件支持）。返回键退出。");
        addButton("Start Sampling Test / 开始采样率/压感测试", () ->
                startActivity(new Intent(this, TouchSamplingActivity.class)));
    }
}
