package com.elotouch.devicetester.modules.touch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.elotouch.devicetester.modules.display.ImmersiveActivity;

/**
 * Grid dead-zone test (PRD §3.7): the screen is tiled with cells; a cell turns
 * green once a finger passes over it. Edges are included so corner/edge dead
 * zones surface. Double-tap clears the grid.
 */
public class TouchGridActivity extends ImmersiveActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new GridView(this));
    }

    @SuppressLint("ViewConstructor")
    private static class GridView extends View {
        private static final int COLS = 12;
        private static final int ROWS = 24;

        private final boolean[][] hit = new boolean[COLS][ROWS];
        private final Paint fill = new Paint();
        private final Paint line = new Paint();
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long lastTapTime = 0;

        GridView(Context c) {
            super(c);
            setBackgroundColor(Color.WHITE);
            fill.setColor(Color.rgb(76, 175, 80));
            line.setColor(Color.LTGRAY);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2);
            text.setColor(Color.GRAY);
            text.setTextSize(36);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_UP) {
                long now = e.getEventTime();
                if (now - lastTapTime < 300) {
                    clear();
                    return true;
                }
                lastTapTime = now;
            }
            for (int i = 0; i < e.getPointerCount(); i++) {
                markCell(e.getX(i), e.getY(i));
            }
            invalidate();
            return true;
        }

        private void markCell(float x, float y) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            int col = (int) (x / w * COLS);
            int row = (int) (y / h * ROWS);
            if (col >= 0 && col < COLS && row >= 0 && row < ROWS) hit[col][row] = true;
        }

        private void clear() {
            for (boolean[] c : hit) java.util.Arrays.fill(c, false);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            float cw = (float) w / COLS, ch = (float) h / ROWS;
            int done = 0;
            for (int c = 0; c < COLS; c++) {
                for (int r = 0; r < ROWS; r++) {
                    float l = c * cw, t = r * ch;
                    if (hit[c][r]) {
                        canvas.drawRect(l, t, l + cw, t + ch, fill);
                        done++;
                    }
                    canvas.drawRect(l, t, l + cw, t + ch, line);
                }
            }
            canvas.drawText("Covered 已覆盖 " + done + " / " + (COLS * ROWS)
                            + " · Double-tap clear 双击清空 · Back exit 返回键退出",
                    20, h - 30, text);
        }
    }
}
