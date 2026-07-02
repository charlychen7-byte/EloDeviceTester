package com.elotouch.devicetester.modules.display;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Screen-uniformity (Mura) test (PRD §3.6 addition): fullscreen low-gray
 * fields for spotting backlight bleed / blotching by eye at the corners and
 * edges.
 */
public class MuraActivity extends ImmersiveActivity {

    private static final int[] GRAYS = {30, 50, 70, 90, 128, 180};
    private int index = 0;
    private FrameLayout root;
    private TextView hint;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        hint = new TextView(this);
        hint.setTextColor(Color.RED);
        hint.setTextSize(14);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(hint, lp);
        root.setOnClickListener(v -> {
            index = (index + 1) % GRAYS.length;
            apply();
        });
        setContentView(root);
        apply();
    }

    private void apply() {
        int g = GRAYS[index];
        root.setBackgroundColor(Color.rgb(g, g, g));
        hint.setText("Gray level 灰阶：" + g + "/255\nCheck corners & edges for bleed/blotching\n"
                + "观察四角与边缘是否有漏光或色斑 · 点击切换 · 返回键退出");
    }
}
