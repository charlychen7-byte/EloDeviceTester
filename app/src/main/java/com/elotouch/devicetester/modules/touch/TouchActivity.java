package com.elotouch.devicetester.modules.touch;

import android.content.Intent;

import com.elotouch.devicetester.core.BaseTestActivity;

/**
 * Touch module menu (PRD §3.7): multi-touch tracking and grid dead-zone test.
 */
public class TouchActivity extends BaseTestActivity {

    @Override
    protected String title() {
        return "Touch / 触摸测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("多点触控测试");
        addInfo("多指同时按压并划线，实时显示触控轨迹与当前触控点数。返回键退出。");
        addButton("开始多点触控测试", () ->
                startActivity(new Intent(this, MultiTouchActivity.class)));

        addSectionTitle("触控精准度 / 死角测试");
        addInfo("全屏铺满网格，手指划过的方格会变色，用于检测边缘与各区域是否存在触控死角。返回键退出。");
        addButton("开始网格死角测试", () ->
                startActivity(new Intent(this, TouchGridActivity.class)));
    }
}
