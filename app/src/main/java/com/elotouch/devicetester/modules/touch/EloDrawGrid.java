package com.elotouch.devicetester.modules.touch;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.lang.reflect.Array;

/**
 * Port of EloDraw's GridManager: an mm-sized cell grid overlaid on the canvas with
 * per-cell touch-down / touch-up tap counters (used by the "Grid touch test" mode).
 */
class EloDrawGrid {

    static class Grid extends RectF {
        int col;
        int row;
        int downs;
        int ups;
    }

    private Canvas canvas;
    private Grid[][] grid;
    private final Paint paint = new Paint();
    private int cols;
    private int rows;
    private final int screenW;
    private final int screenH;

    EloDrawGrid(Canvas canvas, int cellPx, int screenW, int screenH) {
        this.canvas = canvas;
        this.screenW = screenW;
        this.screenH = screenH;
        resetGrids(cellPx);
    }

    void setCanvas(Canvas c) {
        this.canvas = c;
    }

    void resetGrids(int cell) {
        if (cell < 1) cell = 1;
        rows = Math.max(1, screenH / cell);
        cols = Math.max(1, screenW / cell);
        grid = (Grid[][]) Array.newInstance(Grid.class, rows, cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c] = new Grid();
            }
        }

        // Distribute the horizontal remainder across the first few columns (each +1px).
        int baseW = (screenW % cell) / cols + cell;
        int extraCols = screenW % baseW;
        int x = 0;
        for (int c = 0; c < cols; c++) {
            int w = c < extraCols ? baseW + 1 : baseW;
            for (int r = 0; r < rows; r++) {
                grid[r][c].row = r;
                grid[r][c].col = c;
                grid[r][c].downs = 0;
                grid[r][c].ups = 0;
                grid[r][c].left = x;
                grid[r][c].right = x + w;
            }
            x += w;
        }

        // Distribute the vertical remainder across the first few rows (each +1px).
        int baseH = cell + (screenH % cell) / rows;
        int extraRows = screenH % baseH;
        int y = 0;
        for (int r = 0; r < rows; r++) {
            int h = r < extraRows ? baseH + 1 : baseH;
            for (int c = 0; c < cols; c++) {
                grid[r][c].top = y;
                grid[r][c].bottom = y + h;
            }
            y += h;
        }
    }

    void drawGrids() {
        paint.setColor(0xFFCCCCCC);
        paint.setStrokeWidth(1f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setAlpha(150);
        for (int r = 0; r < rows; r++) {
            canvas.drawLine(0, grid[r][0].top, screenW, grid[r][0].top, paint);
        }
        for (int c = 0; c < cols; c++) {
            canvas.drawLine(grid[0][c].left, 0, grid[0][c].left, screenH, paint);
        }
    }

    void drawCounters() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Grid g = grid[r][c];
                float size = g.right - g.left;
                if (g.downs > 0 || g.ups > 0) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(Color.rgb(222, 184, 135));
                    canvas.drawRect(g.left + 1f, g.top + 1f, g.right - 1f, g.bottom - 1f, paint);
                    paint.setColor(Color.rgb(65, 105, 225));
                    paint.setTextSize(size / 4f);
                    paint.setTextAlign(Paint.Align.CENTER);
                    float cx = g.left + size / 2f;
                    canvas.drawText("dw " + g.downs, cx, g.top + (5f * size / 12f), paint);
                    canvas.drawText("up " + g.ups, cx, g.top + (size * 7f / 10f), paint);
                }
            }
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private Grid getGrid(float x, float y) {
        for (int r = 0; r < rows; r++) {
            if (y > grid[r][0].top && y < grid[r][0].bottom) {
                for (int c = 0; c < cols; c++) {
                    if (x > grid[r][c].left && x < grid[r][c].right) {
                        return grid[r][c];
                    }
                }
            }
        }
        return null;
    }

    void incrementDownCounter(float x, float y) {
        Grid g = getGrid(x, y);
        if (g != null) g.downs++;
    }

    void incrementUpCounter(float x, float y) {
        Grid g = getGrid(x, y);
        if (g != null) g.ups++;
    }
}
