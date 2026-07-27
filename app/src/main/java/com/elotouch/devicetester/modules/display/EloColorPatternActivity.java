package com.elotouch.devicetester.modules.display;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.widget.GridLayout;
import android.widget.ImageView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.io.InputStream;

/**
 * Level-2.5 gallery for the Elo color test patterns: a scrollable grid of
 * downsampled thumbnails. Tapping a thumbnail opens the fullscreen viewer at
 * that index. Thumbnails are decoded off the UI thread (PRD §5.2).
 */
public class EloColorPatternActivity extends BaseTestActivity {

    private static final int COLUMNS = 3;

    @Override
    protected String title() {
        return "Elo Color Test Pattern";
    }

    @Override
    protected void buildUi() {
        addInfo("Tap a pattern to view fullscreen. In the viewer, tap the image to "
                + "advance, Back to return.\n"
                + "点击图案全屏显示；全屏中点击图片切换下一张，返回键退出。");

        final String[] names = EloColorPatterns.list(this);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(COLUMNS);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int spacing = dp(6);
        int cell = (dm.widthPixels - dp(32) - spacing * (COLUMNS - 1)) / COLUMNS;
        int thumbH = cell * 9 / 16;

        for (int i = 0; i < names.length; i++) {
            final int index = i;
            final String name = names[i];

            final ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setBackgroundColor(0xFF202020);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = thumbH;
            lp.setMargins(0, 0,
                    (i % COLUMNS == COLUMNS - 1) ? 0 : spacing, spacing);
            iv.setLayoutParams(lp);

            iv.setOnClickListener(v -> {
                Intent it = new Intent(this, EloColorPatternViewerActivity.class);
                it.putExtra(EloColorPatternViewerActivity.EXTRA_INDEX, index);
                startActivity(it);
            });

            grid.addView(iv);

            runAsync(() -> {
                Bitmap thumb = decodeThumb(name, cell);
                ui(() -> { if (thumb != null) iv.setImageBitmap(thumb); });
            });
        }

        addView(grid);
    }

    /** Decode a downsampled thumbnail (~reqW px wide) from assets. */
    private Bitmap decodeThumb(String name, int reqW) {
        String path = EloColorPatterns.ASSET_DIR + "/" + name;
        try (InputStream in = getAssets().open(path)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);

            int sample = 1;
            while (reqW > 0 && bounds.outWidth / (sample * 2) >= reqW) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try (InputStream in2 = getAssets().open(path)) {
                return BitmapFactory.decodeStream(in2, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
