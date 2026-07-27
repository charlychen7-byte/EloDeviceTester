package com.elotouch.devicetester.modules.touch;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * EloDraw settings (faithful port of the APK's SettingsActivity), built programmatically.
 * "Draw line" / "Draw touch points only" / "Grid touch test" are mutually exclusive; the
 * thin-line, mark-down/up and show-points checkboxes apply only to "Draw line". Choices are
 * returned live via setResult extras. Note: the APK left "Mark touch down/up" unwired; this
 * port wires it so the option actually works.
 */
public class EloDrawSettingsActivity extends AppCompatActivity {

    private final Intent result = new Intent();

    private boolean drawLine;
    private boolean thinLine;
    private boolean showMarkers;
    private boolean showPoints;
    private boolean pointsOnly;
    private boolean gridTest;
    private int gridSizeMM;

    private CheckBox cbThinLine;
    private CheckBox cbShowMarkers;
    private CheckBox cbShowPoints;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Elo Draw Settings / 设置");

        Intent in = getIntent();
        drawLine = in.getBooleanExtra("DrawLine", true);
        thinLine = in.getBooleanExtra("ThinLine", false);
        showMarkers = in.getBooleanExtra("ShowMarkers", true);
        showPoints = in.getBooleanExtra("ShowPoints", false);
        pointsOnly = in.getBooleanExtra("PointsOnly", false);
        gridTest = in.getBooleanExtra("GridTest", false);
        gridSizeMM = in.getIntExtra("GridSize", 10);
        writeResult();

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(header("Drawing options / 绘图选项"));

        final RadioButton rbDrawLine = new RadioButton(this);
        rbDrawLine.setText("Draw line / 画线");
        rbDrawLine.setChecked(drawLine);

        cbThinLine = new CheckBox(this);
        cbThinLine.setText("Draw thin line / 细线");
        cbThinLine.setChecked(thinLine);

        cbShowMarkers = new CheckBox(this);
        cbShowMarkers.setText("Mark touch down/up / 标记按下与抬起");
        cbShowMarkers.setChecked(showMarkers);

        cbShowPoints = new CheckBox(this);
        cbShowPoints.setText("Show touch points on line / 线上显示触点");
        cbShowPoints.setChecked(showPoints);

        final RadioButton rbPointsOnly = new RadioButton(this);
        rbPointsOnly.setText("Draw touch points only / 仅画触点");
        rbPointsOnly.setChecked(pointsOnly);

        final RadioButton rbGridTest = new RadioButton(this);
        rbGridTest.setText("Grid touch test / 网格触控测试");
        rbGridTest.setChecked(gridTest);

        RadioGroup modeGroup = new RadioGroup(this);
        modeGroup.addView(rbDrawLine);
        modeGroup.addView(indent(cbThinLine));
        modeGroup.addView(indent(cbShowMarkers));
        modeGroup.addView(indent(cbShowPoints));
        modeGroup.addView(rbPointsOnly);
        modeGroup.addView(rbGridTest);
        root.addView(modeGroup);

        updateEnabled();

        modeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            drawLine = checkedId == rbDrawLine.getId();
            pointsOnly = checkedId == rbPointsOnly.getId();
            gridTest = checkedId == rbGridTest.getId();
            updateEnabled();
            writeResult();
        });
        cbThinLine.setOnCheckedChangeListener((b, v) -> {
            thinLine = v;
            writeResult();
        });
        cbShowMarkers.setOnCheckedChangeListener((b, v) -> {
            showMarkers = v;
            writeResult();
        });
        cbShowPoints.setOnCheckedChangeListener((b, v) -> {
            showPoints = v;
            writeResult();
        });

        root.addView(header("Grid size / 网格尺寸"));
        RadioGroup gridGroup = new RadioGroup(this);
        final RadioButton rb5 = sizeButton("5 mm", 5);
        final RadioButton rb10 = sizeButton("10 mm", 10);
        final RadioButton rb15 = sizeButton("15 mm", 15);
        final RadioButton rb20 = sizeButton("20 mm", 20);
        gridGroup.addView(rb5);
        gridGroup.addView(rb10);
        gridGroup.addView(rb15);
        gridGroup.addView(rb20);
        gridGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == rb5.getId()) gridSizeMM = 5;
            else if (checkedId == rb10.getId()) gridSizeMM = 10;
            else if (checkedId == rb15.getId()) gridSizeMM = 15;
            else if (checkedId == rb20.getId()) gridSizeMM = 20;
            writeResult();
        });
        root.addView(gridGroup);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(16), 0, 0);
        Button about = new Button(this);
        about.setText("About");
        about.setAllCaps(false);
        about.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("About")
                .setMessage("EloDrawAndroid v1.0.0\nUtility for evaluating touch performance.\n\n"
                        + "Copyright© 2019 Elo Touch Solutions. All rights reserved.")
                .setPositiveButton("OK", null)
                .show());
        Button done = new Button(this);
        done.setText("Done");
        done.setAllCaps(false);
        done.setOnClickListener(v -> finish());
        buttons.addView(about);
        buttons.addView(done);
        root.addView(buttons);

        setContentView(root);
    }

    private void writeResult() {
        result.putExtra("DrawLine", drawLine);
        result.putExtra("ThinLine", thinLine);
        result.putExtra("ShowMarkers", showMarkers);
        result.putExtra("ShowPoints", showPoints);
        result.putExtra("PointsOnly", pointsOnly);
        result.putExtra("GridTest", gridTest);
        result.putExtra("GridSize", gridSizeMM);
        setResult(RESULT_OK, result);
    }

    private void updateEnabled() {
        cbThinLine.setEnabled(drawLine);
        cbShowMarkers.setEnabled(drawLine);
        cbShowPoints.setEnabled(drawLine);
    }

    private RadioButton sizeButton(String label, int mm) {
        RadioButton rb = new RadioButton(this);
        rb.setText(label);
        rb.setChecked(gridSizeMM == mm);
        return rb;
    }

    private TextView header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18f);
        tv.setPadding(0, dp(16), 0, dp(8));
        return tv;
    }

    private View indent(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(24);
        v.setLayoutParams(lp);
        return v;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
