package com.elotouch.devicetester.core;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base class for every level-2 test page.
 *
 * <p>Responsibilities common to all modules (PRD §4 / §5.2):
 * <ul>
 *   <li>Keeps the screen on for the whole test (FLAG_KEEP_SCREEN_ON).</li>
 *   <li>Builds the UI programmatically via helpers so modules stay consistent.</li>
 *   <li>Provides a single-thread background {@link ExecutorService} plus a stop
 *       flag, so long/high-load tests never block the UI thread and can be
 *       aborted (PRD: every long test needs a "stop" button).</li>
 *   <li>On {@code onPause} it stops running tests and calls {@link #onStopTests()}
 *       so subclasses release exclusive hardware (camera / nfc / sensors / ...).</li>
 *   <li>Provides lazy, per-module runtime permission requests (PRD §5.4).</li>
 * </ul>
 */
public abstract class BaseTestActivity extends AppCompatActivity {

    protected LinearLayout content;
    protected final ExecutorService executor = Executors.newSingleThreadExecutor();
    protected final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean stopped = false;

    // ---- lazy permission plumbing (registered before STARTED, as required) ----
    private Runnable onGranted;
    private Runnable onDenied;
    private final ActivityResultLauncher<String> singlePermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) { if (onGranted != null) onGranted.run(); }
                else { if (onDenied != null) onDenied.run(); }
            });
    private Runnable onMultiGranted;
    private Runnable onMultiDenied;
    private final ActivityResultLauncher<String[]> multiPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean all = !result.isEmpty();
                for (Boolean g : result.values()) all &= g;
                if (all) { if (onMultiGranted != null) onMultiGranted.run(); }
                else { if (onMultiDenied != null) onMultiDenied.run(); }
            });

    /** Title shown at the top of the page. */
    protected abstract String title();

    /** Build the page content using the add* helpers below. */
    protected abstract void buildUi();

    /** Release any exclusive hardware here. Called from onPause and onDestroy. */
    protected void onStopTests() { }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        TextView header = new TextView(this);
        header.setText(title());
        header.setTextSize(22);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, dp(12));
        content.addView(header);

        buildUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopped = true;
        onStopTests();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ----------------------------------------------------------------- UI helpers

    protected TextView addSectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(17);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(ContextCompat.getColor(this, com.elotouch.devicetester.R.color.primary));
        LinearLayout.LayoutParams lp = rowParams();
        lp.topMargin = dp(16);
        tv.setLayoutParams(lp);
        content.addView(tv);
        return tv;
    }

    /** A reusable text line (info / result). Returned so callers can update it. */
    protected TextView addInfo(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setLineSpacing(dp(2), 1f);
        tv.setLayoutParams(rowParams());
        content.addView(tv);
        return tv;
    }

    protected Button addButton(String text, Runnable onClick) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setLayoutParams(rowParams());
        b.setOnClickListener(v -> onClick.run());
        content.addView(b);
        return b;
    }

    protected ProgressBar addProgressBar() {
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setLayoutParams(rowParams());
        pb.setVisibility(View.GONE);
        content.addView(pb);
        return pb;
    }

    protected void addView(View v) {
        v.setLayoutParams(rowParams());
        content.addView(v);
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    // ----------------------------------------------------------------- threading

    /** Run work off the UI thread. Resets the stop flag before starting. */
    protected void runAsync(Runnable bg) {
        stopped = false;
        executor.execute(() -> {
            try {
                bg.run();
            } catch (Throwable t) {
                ui(() -> toast("测试出错：" + t.getMessage()));
            }
        });
    }

    protected boolean isStopped() { return stopped; }

    protected void stopTests() { stopped = true; }

    /** Post a runnable to the UI thread. */
    protected void ui(Runnable r) { main.post(r); }

    protected void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ----------------------------------------------------------------- permissions

    protected boolean hasPermission(String perm) {
        return ContextCompat.checkSelfPermission(this, perm)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Lazy single-permission request (PRD §5.4). */
    protected void requirePermission(String perm, Runnable granted, Runnable denied) {
        if (hasPermission(perm)) { granted.run(); return; }
        onGranted = granted;
        onDenied = denied;
        singlePermLauncher.launch(perm);
    }

    protected void requirePermissions(String[] perms, Runnable granted, Runnable denied) {
        boolean all = true;
        for (String p : perms) all &= hasPermission(p);
        if (all) { granted.run(); return; }
        onMultiGranted = granted;
        onMultiDenied = denied;
        multiPermLauncher.launch(perms);
    }

    // ----------------------------------------------------------------- misc

    protected int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
