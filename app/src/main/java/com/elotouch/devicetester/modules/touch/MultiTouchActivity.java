package com.elotouch.devicetester.modules.touch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.elotouch.devicetester.modules.display.ImmersiveActivity;

/**
 * Multi-touch test (PRD §3.7): follows each finger, draws its trail and a
 * circle, and reports the live / max simultaneous touch-point count.
 */
public class MultiTouchActivity extends ImmersiveActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new MultiTouchView(this));
    }

    @SuppressLint("ViewConstructor")
    private static class MultiTouchView extends View {
        private static final int[] PALETTE = {
                Color.RED, Color.GREEN, Color.BLUE, Color.MAGENTA,
                Color.CYAN, Color.rgb(255, 140, 0), Color.rgb(120, 0, 200),
                Color.rgb(0, 150, 136), Color.rgb(200, 0, 100), Color.rgb(100, 100, 0)
        };

        private final SparseArray<float[]> pointers = new SparseArray<>(); // id -> {x,y}
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int maxPoints = 0;

        MultiTouchView(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            cross.setColor(Color.WHITE);
            cross.setStrokeWidth(2);
            text.setColor(Color.WHITE);
            text.setTextSize(48);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < e.getPointerCount(); i++) {
                        int id = e.getPointerId(i);
                        pointers.put(id, new float[]{e.getX(i), e.getY(i)});
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP: {
                    int id = e.getPointerId(e.getActionIndex());
                    pointers.remove(id);
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pointers.clear();
                    break;
            }
            maxPoints = Math.max(maxPoints, pointers.size());
            invalidate();
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (int i = 0; i < pointers.size(); i++) {
                int id = pointers.keyAt(i);
                float[] p = pointers.valueAt(i);
                dot.setColor(PALETTE[id % PALETTE.length]);
                canvas.drawCircle(p[0], p[1], 70, dot);
                canvas.drawLine(p[0], 0, p[0], getHeight(), cross);
                canvas.drawLine(0, p[1], getWidth(), p[1], cross);
            }
            canvas.drawText("Touches 当前触点：" + pointers.size() + "    Max 最大：" + maxPoints,
                    40, 100, text);
        }
    }
}
