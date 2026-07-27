package com.elotouch.devicetester.modules.display;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fullscreen viewer for the Elo color test patterns. Stretches the current
 * pattern to fill the whole screen (FIT_XY); tap advances to the next pattern
 * (wraps), Back returns to the grid. At most one full-res bitmap is held in
 * memory; the previous one is recycled on each load (PRD §5.5).
 */
public class EloColorPatternViewerActivity extends ImmersiveActivity {

    public static final String EXTRA_INDEX = "pattern_index";

    private String[] assets;
    private int index;

    private ImageView imageView;
    private TextView hint;
    private Bitmap current;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        assets = EloColorPatterns.list(this);
        if (assets.length == 0) { finish(); return; }

        index = getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (index < 0 || index >= assets.length) index = 0;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setTextColor(Color.RED);
        hint.setTextSize(14);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = 48;
        root.addView(hint, hlp);

        root.setOnClickListener(v -> {
            hint.setVisibility(View.GONE);
            index = (index + 1) % assets.length;
            load();
        });

        setContentView(root);
        load();
    }

    private void load() {
        hint.setText((index + 1) + " / " + assets.length);
        final String name = assets[index];
        io.execute(() -> {
            Bitmap bmp = null;
            try (InputStream in = getAssets().open(EloColorPatterns.ASSET_DIR + "/" + name)) {
                bmp = BitmapFactory.decodeStream(in);
            } catch (IOException ignored) { }
            final Bitmap loaded = bmp;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (loaded != null) loaded.recycle();
                    return;
                }
                imageView.setImageBitmap(loaded);
                if (current != null && current != loaded) current.recycle();
                current = loaded;
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (imageView != null) imageView.setImageDrawable(null);
        if (current != null) { current.recycle(); current = null; }
    }
}
