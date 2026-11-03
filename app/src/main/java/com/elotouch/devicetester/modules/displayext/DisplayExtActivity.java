package com.elotouch.devicetester.modules.displayext;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.elotouch.devicetester.core.BaseTestActivity;

/**
 * Secondary display module (PRD §3.16). The customer display is a pluggable
 * peripheral, so this page detects it live rather than the grid greying out (PRD §5.3).
 *
 * Two modes:
 *  - 同屏 (same screen): main and secondary screens show the same colour, and tapping either
 *    screen changes both.
 *  - 异屏 (different screen): each screen shows its own colour and is cycled independently.
 */
public class DisplayExtActivity extends BaseTestActivity {

    private static final int[] COLORS = {Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK};

    private enum Mode { NONE, SAME, DIFFERENT }

    private TextView displayText;
    private View mainPreview;
    private PatternPresentation presentation;
    private Mode mode = Mode.NONE;
    private int mainIndex = 0;
    private int subIndex = 0;

    @Override
    protected String title() {
        return "Display Ext 双屏测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("Secondary Display / 客显副屏");
        displayText = addInfo("");
        addButton("同屏显示 Same-screen / 主屏与副屏显示相同颜色", () -> startMode(Mode.SAME));
        addButton("异屏显示 Different-screen / 主屏与副屏独立显示", () -> startMode(Mode.DIFFERENT));
        addButton("Dismiss / 关闭副屏图案", this::dismissPattern);

        addSectionTitle("Main Screen / 主屏图案（点击切换颜色）");
        mainPreview = new View(this);
        mainPreview.setMinimumHeight(dp(160));
        mainPreview.setOnClickListener(v -> onMainTap());
        mainPreview.setVisibility(View.GONE);
        addView(mainPreview);

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        displayText.setText(findSecondary() != null
                ? "Secondary display detected 已检测到副屏"
                : "No secondary display detected. 未检测到副屏，请连接客显后重试。");
    }

    private Display findSecondary() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        }
        return null;
    }

    private void startMode(Mode next) {
        Display secondary = findSecondary();
        if (secondary == null) {
            toast("No secondary display. 未检测到副屏。");
            return;
        }
        dismissPattern();
        mode = next;
        subIndex = next == Mode.SAME ? mainIndex : (mainIndex + 1) % COLORS.length;
        String label = next == Mode.SAME ? "同屏显示" : "异屏显示";
        presentation = new PatternPresentation(this, secondary, label, this::onSubTap);
        presentation.show();
        mainPreview.setVisibility(View.VISIBLE);
        refreshColors();
    }

    private void dismissPattern() {
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        mode = Mode.NONE;
        if (mainPreview != null) mainPreview.setVisibility(View.GONE);
    }

    private void onMainTap() {
        mainIndex = (mainIndex + 1) % COLORS.length;
        if (mode == Mode.SAME) subIndex = mainIndex;
        refreshColors();
    }

    private void onSubTap() {
        subIndex = (subIndex + 1) % COLORS.length;
        if (mode == Mode.SAME) mainIndex = subIndex;
        refreshColors();
    }

    private void refreshColors() {
        mainPreview.setBackgroundColor(COLORS[mainIndex]);
        if (presentation != null) presentation.setColor(COLORS[subIndex]);
    }

    @Override
    protected void onStopTests() {
        dismissPattern();
    }

    private static class PatternPresentation extends Presentation {
        private final String label;
        private final Runnable onTap;
        private FrameLayout root;

        PatternPresentation(Context outerContext, Display display, String label, Runnable onTap) {
            super(outerContext, display);
            this.label = label;
            this.onTap = onTap;
        }

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            root = new FrameLayout(getContext());
            TextView hint = new TextView(getContext());
            hint.setText("Elo DeviceTester — Secondary Display Test\n" + label + " · 点击切换颜色");
            hint.setTextColor(Color.DKGRAY);
            hint.setTextSize(16);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER;
            root.addView(hint, lp);
            root.setOnClickListener(v -> onTap.run());
            setContentView(root);
        }

        void setColor(int color) {
            if (root != null) root.setBackgroundColor(color);
        }
    }
}
