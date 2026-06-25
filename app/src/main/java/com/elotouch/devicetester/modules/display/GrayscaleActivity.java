package com.elotouch.devicetester.modules.display;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Grayscale matrix for dark-detail / contrast inspection (PRD §3.6).
 * Tap toggles between a stepped grayscale bar and a smooth gradient.
 */
public class GrayscaleActivity extends ImmersiveActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GrayscaleView view = new GrayscaleView(this);
        view.setOnClickListener(v -> ((GrayscaleView) v).toggle());
        setContentView(view);
    }

    private static class GrayscaleView extends View {
        private final Paint paint = new Paint();
        private boolean smooth = false;

        GrayscaleView(Context c) {
            super(c);
        }

        void toggle() {
            smooth = !smooth;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (smooth) {
                for (int x = 0; x < w; x++) {
                    int g = (int) (255f * x / w);
                    paint.setColor(Color.rgb(g, g, g));
                    canvas.drawRect(x, 0, x + 1, h, paint);
                }
            } else {
                int steps = 16;
                float bw = (float) w / steps;
                for (int i = 0; i < steps; i++) {
                    int g = (int) (255f * i / (steps - 1));
                    paint.setColor(Color.rgb(g, g, g));
                    canvas.drawRect(i * bw, 0, (i + 1) * bw, h, paint);
                }
            }
        }
    }
}
