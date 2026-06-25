package com.elotouch.devicetester.modules.display;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Fullscreen solid-color cycling for dead-pixel / backlight-bleed inspection
 * (PRD §3.6). Tap to advance to the next color; the hint hides after the
 * first tap.
 */
public class ColorTestActivity extends ImmersiveActivity {

    private static final int[] COLORS = {
            Color.RED, Color.GREEN, Color.BLUE,
            Color.YELLOW, Color.CYAN, Color.MAGENTA,
            Color.WHITE, Color.BLACK, Color.GRAY
    };

    private int index = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLORS[0]);

        TextView hint = new TextView(this);
        hint.setText("点击切换颜色 · 返回键退出");
        hint.setTextColor(Color.DKGRAY);
        hint.setTextSize(14);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(hint, lp);

        root.setOnClickListener(v -> {
            hint.setVisibility(View.GONE);
            index = (index + 1) % COLORS.length;
            root.setBackgroundColor(COLORS[index]);
        });

        setContentView(root);
    }
}
