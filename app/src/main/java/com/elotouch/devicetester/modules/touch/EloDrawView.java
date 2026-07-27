package com.elotouch.devicetester.modules.touch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * EloDraw drawing surface. Reproduces the APK's touch behavior: per-finger colored
 * line drawing (thin/thick), optional points-on-line, touch down/up markers,
 * points-only numbered circles, and grid down/up tap counting. Reports the live
 * touch rate and primary-touch coordinates to a Listener, and forwards long-press.
 */
@SuppressLint("ViewConstructor")
class EloDrawView extends View {

    interface Listener {
        void onTouchRate(String text);
        void onPrimaryTouch(String text);
        void onToggleControls();
    }

    // One color per finger id (id % 10), ported from EloDraw's palette.
    private static final int[] PALETTE = {
            Color.rgb(255, 128, 0), Color.rgb(0, 204, 204), Color.rgb(102, 0, 204),
            Color.RED, Color.rgb(0x88, 0x88, 0x88), Color.YELLOW,
            Color.BLUE, Color.GREEN, Color.MAGENTA, Color.BLACK
    };

    private final float xdpi;
    private final Paint paint = new Paint();
    private final SparseArray<Touch> fingers = new SparseArray<>();
    private final GestureDetector gestureDetector;
    private Listener listener;

    private Bitmap bitmap;
    private Canvas drawCanvas;
    private EloDrawGrid grid;

    // Draw-mode settings (EloDraw defaults).
    private boolean drawLine = true;
    private boolean thinLine = false;
    private boolean showMarkers = true;
    private boolean showPoints = false;
    private boolean pointsOnly = false;
    private boolean gridTest = false;
    private int gridSizeMM = 10;

    private static class Touch {
        float x;
        float y;
        float strokeDownX;
        float strokeDownY;
        float dx;
        float dy;
        long strokePointCount = 1;
        long strokeDownTime = System.currentTimeMillis();

        Touch(float x, float y) {
            this.x = x;
            this.y = y;
            this.strokeDownX = x;
            this.strokeDownY = y;
        }

        void update(float x, float y) {
            this.x = x;
            this.y = y;
            strokePointCount++;
        }
    }

    EloDrawView(Context c, float xdpi) {
        super(c);
        this.xdpi = xdpi;
        paint.setAntiAlias(true);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(6f);
        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (listener != null) listener.onToggleControls();
            }
        });
    }

    void setListener(Listener l) {
        this.listener = l;
    }

    private int cellPx() {
        return Math.max(1, Math.round(xdpi / 25.4f * gridSizeMM));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w == 0 || h == 0) return;
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        drawCanvas = new Canvas(bitmap);
        grid = new EloDrawGrid(drawCanvas, cellPx(), w, h);
        drawBackground();
        invalidate();
    }

    void applySettings(boolean drawLine, boolean thinLine, boolean showMarkers,
                       boolean showPoints, boolean pointsOnly, boolean gridTest, int gridSizeMM) {
        this.drawLine = drawLine;
        this.thinLine = thinLine;
        this.showMarkers = showMarkers;
        this.showPoints = showPoints;
        this.pointsOnly = pointsOnly;
        this.gridTest = gridTest;
        boolean gridChanged = this.gridSizeMM != gridSizeMM;
        this.gridSizeMM = gridSizeMM;
        paint.setStrokeWidth(thinLine ? 1f : 6f);
        if (grid != null && gridChanged) grid.resetGrids(cellPx());
        drawBackground();
        invalidate();
    }

    void clean() {
        drawBackground();
        invalidate();
    }

    Bitmap getBitmap() {
        return bitmap;
    }

    private void drawBackground() {
        if (drawCanvas == null) return;
        drawCanvas.drawColor(Color.WHITE);
        if (grid != null) grid.drawGrids();
    }

    private void drawPoint(int id, float x, float y) {
        paint.setColor(PALETTE[id % PALETTE.length]);
        paint.setStyle(Paint.Style.FILL);
        float r = 70f;
        drawCanvas.drawCircle(x, y, r, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(25f);
        drawCanvas.drawText(String.valueOf(id), x - 8f, y - r + 25f, paint);
    }

    private void reportPrimary(float x, float y) {
        if (listener != null) {
            listener.onPrimaryTouch(String.format(Locale.US, "%04.0f, %04.0f", x, y));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        gestureDetector.onTouchEvent(e);
        if (drawCanvas == null) return true;
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                float x = e.getX(idx);
                float y = e.getY(idx);
                if (id == 0) reportPrimary(x, y);
                paint.setColor(PALETTE[id % PALETTE.length]);
                paint.setStyle(Paint.Style.FILL);
                fingers.put(id, new Touch(x, y));
                if (drawLine) {
                    if (showMarkers) {
                        drawCanvas.drawLine(x - 30, y, x + 30, y, paint);
                        drawCanvas.drawLine(x, y - 30, x, y + 30, paint);
                    }
                } else if (gridTest && action == MotionEvent.ACTION_DOWN) {
                    grid.incrementDownCounter(x, y);
                    grid.drawCounters();
                } else if (pointsOnly) {
                    drawPoint(id, x, y);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (pointsOnly) drawBackground();
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    float x = e.getX(i);
                    float y = e.getY(i);
                    if (id == 0) reportPrimary(x, y);
                    Touch t = fingers.get(id);
                    if (t == null) continue;
                    float adx = Math.abs(x - t.strokeDownX);
                    if (adx > t.dx) t.dx = adx;
                    float ady = Math.abs(y - t.strokeDownY);
                    if (ady > t.dy) t.dy = ady;
                    paint.setColor(PALETTE[id % PALETTE.length]);
                    if (pointsOnly) {
                        drawPoint(id, x, y);
                    } else if (drawLine) {
                        if (showPoints) {
                            paint.setStyle(Paint.Style.FILL);
                            drawCanvas.drawCircle(x, y, thinLine ? 6f : 10f, paint);
                        }
                        drawCanvas.drawLine(t.x, t.y, x, y, paint);
                    }
                    t.update(x, y);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                float x = e.getX(idx);
                float y = e.getY(idx);
                if (id == 0 && listener != null) listener.onPrimaryTouch("");
                paint.setColor(PALETTE[id % PALETTE.length]);
                if (pointsOnly) {
                    drawBackground();
                } else if (drawLine) {
                    if (showMarkers) {
                        paint.setStyle(Paint.Style.STROKE);
                        drawCanvas.drawCircle(x, y, 30f, paint);
                    }
                } else if (gridTest && action == MotionEvent.ACTION_UP) {
                    grid.incrementUpCounter(x, y);
                    grid.drawCounters();
                }
                Touch t = fingers.get(id);
                if (t != null && listener != null) {
                    long elapsed = System.currentTimeMillis() - t.strokeDownTime;
                    long rate = elapsed > 0 ? (t.strokePointCount * 1000) / elapsed : 0;
                    listener.onTouchRate(rate + " points/sec");
                }
                fingers.remove(id);
                break;
            }
            default:
                return true;
        }
        invalidate();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) canvas.drawBitmap(bitmap, 0, 0, null);
    }
}
