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

import java.util.Locale;

/**
 * Touch sampling-rate & pressure test (PRD §3.7 addition). Both figures come
 * straight off the MotionEvent stream, so they're shown together on one
 * screen rather than as two near-identical fullscreen views.
 */
public class TouchSamplingActivity extends ImmersiveActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new SamplingView(this));
    }

    @SuppressLint("ViewConstructor")
    private static class SamplingView extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long lastEventNanos = -1;
        private float avgHz = 0f;
        private float lastPressure = -1;
        private float lastSize = -1;
        private int sampleCount = 0;

        SamplingView(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            text.setColor(Color.WHITE);
            text.setTextSize(42);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                int historySize = e.getHistorySize();
                for (int i = 0; i < historySize; i++) {
                    recordSample(e.getHistoricalEventTime(i));
                }
                recordSample(e.getEventTime());
                lastPressure = e.getPressure();
                lastSize = e.getSize();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                lastEventNanos = -1;
            }
            invalidate();
            return true;
        }

        private void recordSample(long eventTimeMs) {
            long nanos = eventTimeMs * 1_000_000L;
            if (lastEventNanos > 0) {
                long deltaNanos = nanos - lastEventNanos;
                if (deltaNanos > 0) {
                    float hz = 1_000_000_000f / deltaNanos;
                    sampleCount++;
                    avgHz += (hz - avgHz) / Math.min(sampleCount, 30);
                }
            }
            lastEventNanos = nanos;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawText(String.format(Locale.US, "Sampling rate 采样率/报点率：%.0f Hz", avgHz), 40, 120, text);
            canvas.drawText(String.format(Locale.US, "Pressure 压力：%.3f", lastPressure), 40, 190, text);
            canvas.drawText(String.format(Locale.US, "Contact size 接触面积：%.3f", lastSize), 40, 260, text);
            canvas.drawText("Touch and move your finger · Back to exit\n"
                    + "触摸并移动手指 · 返回键退出", 40, getHeight() - 60, text);
        }
    }
}
