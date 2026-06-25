package com.elotouch.devicetester;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.elotouch.devicetester.core.DeviceInfo;
import com.elotouch.devicetester.core.ModuleAdapter;

/**
 * Level-1 entry screen: a persistent device-info banner plus a grid of
 * hardware modules. Tapping a (supported) module opens its level-2 page.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // --- persistent device info banner ---
        TextView title = new TextView(this);
        title.setText("Elo DeviceTester");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);

        TextView info = new TextView(this);
        info.setText(DeviceInfo.banner());
        info.setTextSize(13);
        info.setTextColor(Color.parseColor("#E3F2FD"));
        info.setPadding(0, dp(4), 0, 0);

        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setBackgroundColor(Color.parseColor("#1565C0"));
        int p = dp(16);
        banner.setPadding(p, p, p, p);
        banner.addView(title);
        banner.addView(info);
        root.addView(banner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- module grid ---
        RecyclerView grid = new RecyclerView(this);
        int span = getResources().getConfiguration().smallestScreenWidthDp >= 600 ? 4 : 3;
        grid.setLayoutManager(new GridLayoutManager(this, span));
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        grid.setClipToPadding(false);
        grid.setAdapter(new ModuleAdapter(this, module -> {
            startActivity(new Intent(this, module.activity));
        }));
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
