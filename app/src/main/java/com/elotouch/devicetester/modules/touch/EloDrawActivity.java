package com.elotouch.devicetester.modules.touch;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.elotouch.devicetester.modules.display.ImmersiveActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Elo Draw host (Touch module, first test item). A fullscreen canvas (EloDrawView)
 * with overlay readouts (touch rate, primary-touch coords) and four buttons:
 * Clean, Settings, Screenshot, Exit. Long-press the canvas to hide/show the overlay.
 * Ported from EloDraw_Android_v1.0.0.apk; Exit returns to the Touch menu and screenshots
 * are saved via MediaStore (API 29+) or legacy external storage with a lazy permission.
 */
public class EloDrawActivity extends ImmersiveActivity implements EloDrawView.Listener {

    private static final String RATE_LABEL = "Touch rate 报点率: ";
    private static final String POS_LABEL = "Primary touch 主触点: ";

    private EloDrawView drawView;
    private TextView tvTouchRate;
    private TextView tvTouchPos;
    private TextView tvHint;
    private LinearLayout buttonBar;
    private boolean controlsHidden = false;
    private ToneGenerator toneGen;

    // Settings state (mirrors EloDraw defaults).
    private boolean drawLine = true;
    private boolean thinLine = false;
    private boolean showMarkers = true;
    private boolean showPoints = false;
    private boolean pointsOnly = false;
    private boolean gridTest = false;
    private int gridSizeMM = 10;

    private ActivityResultLauncher<Intent> settingsLauncher;
    private ActivityResultLauncher<String> storagePermLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);

        FrameLayout root = new FrameLayout(this);

        drawView = new EloDrawView(this, dm.xdpi);
        drawView.setListener(this);
        root.addView(drawView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Readouts (top-left).
        LinearLayout readouts = new LinearLayout(this);
        readouts.setOrientation(LinearLayout.VERTICAL);
        tvTouchRate = readout(RATE_LABEL);
        tvTouchPos = readout(POS_LABEL);
        readouts.addView(tvTouchRate);
        readouts.addView(tvTouchPos);
        FrameLayout.LayoutParams roLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        roLp.gravity = Gravity.TOP | Gravity.START;
        roLp.leftMargin = 24;
        roLp.topMargin = 24;
        root.addView(readouts, roLp);

        // Buttons (top-right).
        buttonBar = new LinearLayout(this);
        buttonBar.setOrientation(LinearLayout.VERTICAL);
        buttonBar.addView(makeButton("Clean 清除", () -> {
            drawView.clean();
            tvTouchRate.setText(RATE_LABEL);
            tvTouchPos.setText(POS_LABEL);
        }));
        buttonBar.addView(makeButton("Settings 设置", this::openSettings));
        buttonBar.addView(makeButton("Screenshot 截图", this::takeScreenshot));
        buttonBar.addView(makeButton("Exit 退出", this::finish));
        FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        btnLp.gravity = Gravity.TOP | Gravity.END;
        btnLp.rightMargin = 24;
        btnLp.topMargin = 24;
        root.addView(buttonBar, btnLp);

        // Hint (bottom-center).
        tvHint = new TextView(this);
        tvHint.setText("Long-press to hide or show all buttons / 长按隐藏或显示按钮");
        tvHint.setTextColor(Color.BLUE);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hintLp.bottomMargin = 24;
        root.addView(tvHint, hintLp);

        setContentView(root);

        settingsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                        Intent d = r.getData();
                        drawLine = d.getBooleanExtra("DrawLine", true);
                        thinLine = d.getBooleanExtra("ThinLine", false);
                        showMarkers = d.getBooleanExtra("ShowMarkers", true);
                        showPoints = d.getBooleanExtra("ShowPoints", false);
                        pointsOnly = d.getBooleanExtra("PointsOnly", false);
                        gridTest = d.getBooleanExtra("GridTest", false);
                        gridSizeMM = d.getIntExtra("GridSize", 10);
                        drawView.applySettings(drawLine, thinLine, showMarkers,
                                showPoints, pointsOnly, gridTest, gridSizeMM);
                        tvTouchRate.setVisibility(gridTest ? View.INVISIBLE : View.VISIBLE);
                    }
                });

        storagePermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        saveBitmap(snapshot());
                    } else {
                        showMessage("Screenshot", "Storage permission restricted; cannot save. "
                                + "权限被拒绝，无法保存。");
                    }
                });
    }

    private void openSettings() {
        Intent i = new Intent(this, EloDrawSettingsActivity.class);
        i.putExtra("DrawLine", drawLine);
        i.putExtra("ThinLine", thinLine);
        i.putExtra("ShowMarkers", showMarkers);
        i.putExtra("ShowPoints", showPoints);
        i.putExtra("PointsOnly", pointsOnly);
        i.putExtra("GridTest", gridTest);
        i.putExtra("GridSize", gridSizeMM);
        settingsLauncher.launch(i);
    }

    private void takeScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            storagePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        saveBitmap(snapshot());
    }

    @Nullable
    private Bitmap snapshot() {
        Bitmap src = drawView.getBitmap();
        return src == null ? null : Bitmap.createBitmap(src);
    }

    private void saveBitmap(@Nullable final Bitmap bmp) {
        if (bmp == null) {
            showMessage("Screenshot", "Nothing to capture yet. 暂无可保存内容。");
            return;
        }
        final String name = "EloDraw_"
                + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".png";
        new Thread(() -> {
            String msg;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver cr = getContentResolver();
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/EloDeviceTester");
                    cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                    Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new IOException("MediaStore insert failed");
                    try (OutputStream os = cr.openOutputStream(uri)) {
                        if (os == null) throw new IOException("openOutputStream failed");
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    cv.clear();
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                    cr.update(uri, cv, null, null);
                    msg = "Saved to Pictures/EloDeviceTester/" + name;
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES), "EloDeviceTester");
                    if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
                    File out = new File(dir, name);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                    MediaScannerConnection.scanFile(this,
                            new String[]{out.getAbsolutePath()}, null, null);
                    msg = "Saved to " + out.getAbsolutePath();
                }
            } catch (Exception ex) {
                msg = "Screenshot failed: " + ex.getMessage();
            }
            final String finalMsg = msg;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) showMessage("Screenshot", finalMsg);
            });
        }).start();
    }

    private void showMessage(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void beep() {
        try {
            if (toneGen == null) {
                toneGen = new ToneGenerator(AudioManager.STREAM_SYSTEM, 80);
            }
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
        } catch (Exception ignored) {
        }
    }

    private TextView readout(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.DKGRAY);
        tv.setTextSize(16f);
        return tv;
    }

    private Button makeButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            beep();
            action.run();
        });
        return b;
    }

    // EloDrawView.Listener
    @Override
    public void onTouchRate(String text) {
        tvTouchRate.setText(RATE_LABEL + text);
    }

    @Override
    public void onPrimaryTouch(String text) {
        tvTouchPos.setText(POS_LABEL + text);
    }

    @Override
    public void onToggleControls() {
        controlsHidden = !controlsHidden;
        int vis = controlsHidden ? View.INVISIBLE : View.VISIBLE;
        buttonBar.setVisibility(vis);
        tvTouchPos.setVisibility(vis);
        tvHint.setVisibility(vis);
        tvTouchRate.setVisibility(
                controlsHidden ? View.INVISIBLE : (gridTest ? View.INVISIBLE : View.VISIBLE));
    }

    @Override
    protected void onDestroy() {
        if (toneGen != null) {
            toneGen.release();
            toneGen = null;
        }
        super.onDestroy();
    }
}
