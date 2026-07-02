package com.elotouch.devicetester.modules.display;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Color accuracy / gamut reference test (PRD §3.6 addition): shows standard
 * swatches with their target RGB values overlaid for comparison against an
 * external colorimeter or trained eye. The phone's own sensors can't verify
 * color accuracy in closed loop, so this intentionally does not auto-judge.
 */
public class ColorAccuracyActivity extends ImmersiveActivity {

    private static final int[][] SWATCHES = {
            {255, 0, 0}, {0, 255, 0}, {0, 0, 255},
            {255, 255, 255}, {128, 128, 128}, {255, 165, 0}
    };

    private int index = 0;
    private TextView label;
    private FrameLayout root;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        label = new TextView(this);
        label.setTextSize(18);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(label, lp);
        root.setOnClickListener(v -> {
            index = (index + 1) % SWATCHES.length;
            apply();
        });
        setContentView(root);
        apply();
    }

    private void apply() {
        int[] rgb = SWATCHES[index];
        int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
        root.setBackgroundColor(color);
        boolean lightBg = (rgb[0] + rgb[1] + rgb[2]) / 3 > 180;
        label.setTextColor(lightBg ? Color.BLACK : Color.WHITE);
        label.setText(String.format(Locale.US,
                "Target RGB(%d,%d,%d)  #%06X\nTap to switch · Back to exit\n"
                        + "目标 RGB 值，供外部仪器或肉眼比对 · 点击切换 · 返回键退出",
                rgb[0], rgb[1], rgb[2], color & 0xFFFFFF));
    }
}
