package com.elotouch.devicetester.modules.display;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Touch-to-display alignment test (PRD §3.6 addition): overlays a reference
 * grid and the live touch point so any offset between what's drawn and where
 * the digitizer reports the finger is visible by eye.
 */
public class TouchAlignmentActivity extends ImmersiveActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new AlignmentView(this));
    }

    @SuppressLint("ViewConstructor")
    private static class AlignmentView extends View {
        private static final int GRID_STEP_DP = 40;
        private final Paint gridPaint = new Paint();
        private final Paint touchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float touchX = -1, touchY = -1;

        AlignmentView(Context c) {
            super(c);
            setBackgroundColor(Color.WHITE);
            gridPaint.setColor(Color.LTGRAY);
            gridPaint.setStrokeWidth(2);
            touchPaint.setColor(Color.RED);
            textPaint.setColor(Color.DKGRAY);
            textPaint.setTextSize(36);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            touchX = e.getX();
            touchY = e.getY();
            invalidate();
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float step = GRID_STEP_DP * density;
            for (float x = 0; x < getWidth(); x += step) canvas.drawLine(x, 0, x, getHeight(), gridPaint);
            for (float y = 0; y < getHeight(); y += step) canvas.drawLine(0, y, getWidth(), y, gridPaint);
            if (touchX >= 0) {
                canvas.drawLine(touchX, 0, touchX, getHeight(), touchPaint);
                canvas.drawLine(0, touchY, getWidth(), touchY, touchPaint);
                canvas.drawCircle(touchX, touchY, 12, touchPaint);
            }
            canvas.drawText("Compare the red touch marker to the grid lines under your finger\n"
                            + "比较红色触控标记与手指下方网格线的偏移 · 返回键退出",
                    20, getHeight() - 30, textPaint);
        }
    }
}
