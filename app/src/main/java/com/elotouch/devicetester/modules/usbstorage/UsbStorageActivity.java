package com.elotouch.devicetester.modules.usbstorage;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * USB Storage module. Lists the external storage volumes and USB mass-storage
 * devices (live on attach/detach/mount), lets the user browse each volume, and
 * runs a copy-speed test: U盘文件 → internal Downloads → U盘根目录.
 *
 * <p>The first time Step 1 runs it silently asks for one-time write access to the whole
 * USB volume (the system's tree picker), then opens the system file picker to choose the
 * source file — both triggered by the same tap, so no separate manual grant step is ever
 * shown. Step 2 reuses that same grant automatically. While a copy runs the page is locked
 * (buttons greyed, back blocked); leaving the page removes the test files.
 */
public class UsbStorageActivity extends BaseTestActivity {

    private static final String TAG = "UsbStorage";

    private TextView countText;
    private LinearLayout deviceBox;
    private boolean receiverRegistered;

    // ---- device list ----
    /** Per-volume "Browse" buttons, rebuilt on every refresh. */
    private final List<Button> deviceButtons = new ArrayList<>();

    // ---- copy-speed test ----
    private ProgressBar copyProgress;
    private TextView copyStatus;
    private TextView copyResult;
    private Button btnStep1;
    private Button btnStep2;
    private Button btnCleanup;
    /** Enabled only while a copy runs. */
    private Button btnStop;
    /** Buttons greyed out while a copy runs. */
    private final List<Button> actionButtons = new ArrayList<>();
    private OnBackPressedCallback backGuard;
    private boolean copying;

    /** USB volume root granted by the user; null until picked or after the volume is removed. */
    private Uri usbRoot;
    /** Whether the USB root grant was persisted, so cleanup knows to release it. */
    private boolean rootPersisted;
    /** Every copy run this page session, listed in order on the page. */
    private final List<Run> runs = new ArrayList<>();
    /** Step-1 result: the latest copy in internal Downloads that step 2 reads from. */
    private Uri downloadsCopy;
    private String downloadsName;
    private String downloadsMime;
    private long downloadsBytes;
    /** Every test copy created so far; removed on cleanup or page exit. */
    private final List<Uri> testDownloads = new ArrayList<>();
    private final List<Uri> testUsb = new ArrayList<>();

    /** One-time USB root grant, requested automatically the first time Step 1 runs. */
    private final ActivityResultLauncher<Intent> pickRoot =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                Intent data = r.getData();
                if (r.getResultCode() == RESULT_OK && data != null && data.getData() != null) {
                    onRootPicked(data.getData());
                }
            });

    /** Step 1 file picker: the chosen file is copied as soon as it is returned. */
    private final ActivityResultLauncher<Intent> pickFile =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
                Intent data = r.getData();
                if (r.getResultCode() == RESULT_OK && data != null && data.getData() != null) {
                    onUsbFilePicked(data.getData());
                }
            });

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            // The volume going away invalidates the USB root grant.
            if (Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                    || Intent.ACTION_MEDIA_REMOVED.equals(action)
                    || Intent.ACTION_MEDIA_EJECT.equals(action)
                    || Intent.ACTION_MEDIA_BAD_REMOVAL.equals(action)) {
                usbRoot = null;
            }
            refresh();
        }
    };

    @Override
    protected String title() {
        return "USB Storage / USB 存储";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("External Storage / 外接存储设备");
        countText = addInfo("");
        deviceBox = new LinearLayout(this);
        deviceBox.setOrientation(LinearLayout.VERTICAL);
        addView(deviceBox);

        addSectionTitle("Copy Speed / 拷贝速度测试");
        addInfo("1) Choose a file on the USB, copy it to Downloads / 从U盘选择文件拷贝到 Downloads"
                + "（首次点击会先请求U盘访问权限，无需额外手动授权）；\n"
                + "2) Copy that Downloads file back to the USB root / 拷回U盘根目录。\n"
                + "Speed = size ÷ time, including flush to storage. 速度 = 大小 ÷ 耗时（含落盘）。");
        btnStep1 = addButton("1. USB file → Downloads", this::startStep1);
        btnStep2 = addButton("2. Downloads → USB root", this::startStep2);
        copyProgress = addProgressBar();
        btnStop = addButton("Stop copy 停止拷贝", this::stopTests);
        btnStop.setEnabled(false);
        copyStatus = addInfo("");
        copyResult = addInfo("");
        btnCleanup = addButton("Clean up test files 清理测试文件", this::cleanupButton);
        actionButtons.add(btnStep1);
        actionButtons.add(btnStep2);
        actionButtons.add(btnCleanup);

        // Back is blocked while a copy runs; the callback is enabled only during copies.
        backGuard = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                toast("Copy in progress — stop it first 拷贝中，请先停止拷贝");
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backGuard);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            IntentFilter usb = new IntentFilter();
            usb.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            usb.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerReceiver(refreshReceiver, usb);

            // Media broadcasts carry a file:// data URI, so they need their own filter.
            IntentFilter media = new IntentFilter();
            media.addAction(Intent.ACTION_MEDIA_MOUNTED);
            media.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
            media.addAction(Intent.ACTION_MEDIA_REMOVED);
            media.addAction(Intent.ACTION_MEDIA_EJECT);
            media.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
            media.addDataScheme("file");
            registerReceiver(refreshReceiver, media);
            receiverRegistered = true;
        }
        refresh();
    }

    // ----------------------------------------------------------------- device list

    private void refresh() {
        List<StorageVolume> volumes = removableVolumes();
        List<UsbDevice> massStorage = massStorageDevices();

        deviceBox.removeAllViews();
        deviceButtons.clear();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);

        if (volumes.isEmpty() && massStorage.isEmpty()) {
            countText.setText("0 external storage device(s) detected 检测到外接存储设备：0\n"
                    + "No external storage attached. 未检测到外接存储设备。");
            return;
        }

        // Volumes do not expose their parent USB device, so the model is only
        // assigned when exactly one mass-storage device is attached.
        String model = massStorage.size() == 1 ? modelOf(massStorage.get(0)) : null;
        int count = volumes.isEmpty() ? massStorage.size() : volumes.size();
        countText.setText(count + " external storage device(s) detected 检测到外接存储设备："
                + count);

        if (volumes.isEmpty()) {
            // Attached but not mounted: show the hardware alone, nothing to browse.
            for (UsbDevice d : massStorage) {
                addDeviceBlock(lp, modelOf(d));
            }
            return;
        }
        for (int i = 0; i < volumes.size(); i++) {
            addVolumeBlock(lp, i + 1, volumes.get(i), model, massStorage.size());
        }
    }

    private void addDeviceBlock(LinearLayout.LayoutParams lp, String model) {
        TextView tv = new TextView(this);
        tv.setText("Model 型号: " + model);
        tv.setTextSize(15);
        tv.setLineSpacing(dp(2), 1f);
        deviceBox.addView(tv, lp);
    }

    private void addVolumeBlock(LinearLayout.LayoutParams lp, int index, StorageVolume v,
                                String model, int massCount) {
        File dir = volumeDir(v);

        String name = v.getDescription(this);
        String modelText = model != null ? model
                : massCount == 0 ? "-" : "Multiple devices, see device list 多设备，见设备列表";

        String info = "Device 设备 " + index + "\n"
                + "Name 名称: " + (name != null ? name : "-") + "\n"
                + "File format 文件格式: " + formatOf(dir) + "\n"
                + "Model 型号: " + modelText;

        TextView tv = new TextView(this);
        tv.setText(info);
        tv.setTextSize(15);
        tv.setLineSpacing(dp(2), 1f);
        deviceBox.addView(tv, lp);
    }

    /** Removable, non-primary volumes: the external USB/OTG storage. */
    private List<StorageVolume> removableVolumes() {
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        List<StorageVolume> out = new ArrayList<>();
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (v.isRemovable() && !v.isPrimary()) {
                out.add(v);
            }
        }
        return out;
    }

    private List<UsbDevice> massStorageDevices() {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        List<UsbDevice> out = new ArrayList<>();
        if (um == null) {
            return out;
        }
        for (UsbDevice d : um.getDeviceList().values()) {
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface intf = d.getInterface(i);
                if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) {
                    out.add(d);
                    break;
                }
            }
        }
        return out;
    }

    private static String modelOf(UsbDevice d) {
        String product = d.getProductName();
        String vendor = d.getManufacturerName();
        StringBuilder sb = new StringBuilder();
        if (vendor != null && !vendor.isEmpty()) {
            sb.append(vendor).append(' ');
        }
        sb.append(product != null && !product.isEmpty() ? product : "USB Storage");
        sb.append(String.format(Locale.US, " (%04X:%04X)", d.getVendorId(), d.getProductId()));
        return sb.toString();
    }

    /** Mount directory of a volume; API 30+ public, earlier releases via reflection. */
    private static File volumeDir(StorageVolume v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return v.getDirectory();
        }
        try {
            Method m = StorageVolume.class.getMethod("getPath");
            Object p = m.invoke(v);
            return p instanceof String ? new File((String) p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Filesystem type of the mount, read from /proc/mounts. Android 11+ exposes
     * removable volumes through FUSE, so the real on-disk format may be hidden.
     */
    private static String formatOf(File dir) {
        if (dir == null) {
            return "Unknown 未知";
        }
        String path = dir.getAbsolutePath();
        String fs = null;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/mounts"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(" ");
                if (f.length >= 3 && f[1].equals(path)) {
                    fs = f[2];  // last matching mount wins
                }
            }
        } catch (IOException ignored) {
            // fall through to "Unknown"
        }
        if (fs == null) {
            return "Unknown 未知";
        }
        if (fs.equals("fuse") || fs.equals("sdcardfs")) {
            return fs + " (sandboxed, on-disk format hidden 真实格式不可见)";
        }
        return fs;
    }

    // ----------------------------------------------------------------- USB root

    /** Volume root of the first removable volume, used as the picker's starting point. */
    private Uri initialRootUri() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        for (StorageVolume v : removableVolumes()) {
            Uri initial = v.createOpenDocumentTreeIntent()
                    .getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI);
            if (initial != null) {
                return initial;
            }
        }
        return null;
    }

    // ----------------------------------------------------------------- step 1: USB → Downloads

    /** Requests the USB root grant first (if needed), then opens the file picker. */
    private void startStep1() {
        if (copying) {
            return;
        }
        if (usbRoot == null) {
            requestUsbRoot();
            return;
        }
        launchFilePicker();
    }

    /**
     * Asks for one-time write access to the whole USB volume via the system's tree
     * picker. On success this flows straight into the file picker, so the grant and
     * the file choice together read as a single "Step 1" tap to the user.
     */
    private void requestUsbRoot() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        Uri initial = initialRootUri();
        if (initial != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        }
        try {
            pickRoot.launch(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No tree picker available", e);
            toast("No file browser available. 无可用的文件浏览器。");
        }
    }

    /** Validates and persists the USB root grant, then opens the file picker automatically. */
    private void onRootPicked(Uri tree) {
        if (!UsbCopyEngine.isTreeRoot(tree)) {
            toast("Please select the USB root folder 请选择 U 盘根目录");
            return;
        }
        usbRoot = tree;
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            rootPersisted = true;
        } catch (RuntimeException e) {
            rootPersisted = false;  // grant is still valid while this page is open
        }
        toast("USB root granted 已授权 U 盘根目录");
        launchFilePicker();
    }

    /** Opens the system file picker; choosing a file starts the copy straight away. */
    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        Uri initial = initialRootUri();
        if (initial != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        }
        try {
            pickFile.launch(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "No file picker available", e);
            toast("No file browser available. 无可用的文件浏览器。");
        }
    }

    /** Reads the picked file's name, size and type off the UI thread, then starts step 1. */
    private void onUsbFilePicked(Uri uri) {
        runAsync(() -> {
            String name = "file";
            long size = -1;
            try (Cursor c = getContentResolver().query(uri,
                    new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    if (!c.isNull(0)) {
                        name = c.getString(0);
                    }
                    if (!c.isNull(1)) {
                        size = c.getLong(1);
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot read picked file metadata", e);
            }
            String type = getContentResolver().getType(uri);
            final String mime = type != null ? type : "application/octet-stream";
            final String fileName = name;
            final long fileSize = size;
            ui(() -> startStep1For(uri, fileName, mime, fileSize));
        });
    }

    private void startStep1For(Uri src, String name, String mime, long size) {
        Runnable go = () -> doStep1(src, name, mime, size);
        // Pre-Q Downloads is a public folder, so it needs the write permission first.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            go.run();
        } else {
            requirePermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, go,
                    () -> toast("Storage permission denied 未授予存储权限"));
        }
    }

    private void doStep1(Uri src, String srcName, String mime, long size) {
        setCopying(true);
        setStatus("Copying to Downloads… 正在拷贝到 Downloads…");
        String name = UsbCopyEngine.PREFIX + srcName;
        // Free the space held by the previous Downloads copy before writing the new one.
        final List<Uri> stale = new ArrayList<>(testDownloads);
        testDownloads.clear();
        downloadsCopy = null;
        final Uri[] dst = new Uri[1];
        runAsync(() -> {
            for (Uri old : stale) {
                UsbCopyEngine.deleteDownloadsCopy(this, old);
            }
            try {
                dst[0] = UsbCopyEngine.createDownloadsTarget(this, name, mime);
                UsbCopyEngine.Result r = UsbCopyEngine.copy(getContentResolver(), src, dst[0],
                        size, this::isStopped, this::reportProgress);
                if (r == null) {
                    UsbCopyEngine.deleteDownloadsCopy(this, dst[0]);
                    ui(() -> addRun(Run.stopped(1, srcName)));
                    return;
                }
                UsbCopyEngine.finishDownloadsTarget(this, dst[0]);
                Uri done = dst[0];
                ui(() -> {
                    downloadsCopy = done;
                    downloadsName = name;
                    downloadsMime = mime;
                    downloadsBytes = r.bytes;
                    testDownloads.add(done);
                    addRun(Run.ok(1, srcName, r));
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Step 1 failed", e);
                if (dst[0] != null) {
                    UsbCopyEngine.deleteDownloadsCopy(this, dst[0]);
                }
                ui(() -> addRun(Run.failed(1, srcName, String.valueOf(e.getMessage()))));
            } finally {
                ui(this::copyDone);
            }
        });
    }

    // ----------------------------------------------------------------- step 2: Downloads → USB root

    private void startStep2() {
        if (copying) {
            return;
        }
        if (downloadsCopy == null) {
            toast("Run step 1 first 请先完成步骤 1");
            return;
        }
        doStep2(usbRoot);
    }

    private void doStep2(Uri tree) {
        setCopying(true);
        setStatus("Copying to USB root… 正在拷贝到 U 盘根目录…");
        final Uri src = downloadsCopy;
        final String name = downloadsName;
        final String mime = downloadsMime;
        final long total = downloadsBytes;
        // Free the space held by the previous USB copy before writing the new one.
        final List<Uri> stale = new ArrayList<>(testUsb);
        testUsb.clear();
        final Uri[] dst = new Uri[1];
        runAsync(() -> {
            for (Uri old : stale) {
                UsbCopyEngine.deleteDocument(this, old);
            }
            try {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                dst[0] = DocumentsContract.createDocument(getContentResolver(), parent, mime, name);
                if (dst[0] == null) {
                    throw new IOException("Cannot create file on USB 无法在 U 盘创建文件");
                }
                UsbCopyEngine.Result r = UsbCopyEngine.copy(getContentResolver(), src, dst[0],
                        total, this::isStopped, this::reportProgress);
                if (r == null) {
                    UsbCopyEngine.deleteDocument(this, dst[0]);
                    ui(() -> addRun(Run.stopped(2, name)));
                    return;
                }
                Uri done = dst[0];
                ui(() -> {
                    testUsb.add(done);
                    addRun(Run.ok(2, name, r));
                });
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Step 2 failed", e);
                if (dst[0] != null) {
                    UsbCopyEngine.deleteDocument(this, dst[0]);
                }
                ui(() -> addRun(Run.failed(2, name, String.valueOf(e.getMessage()))));
            } finally {
                ui(this::copyDone);
            }
        });
    }

    // ----------------------------------------------------------------- cleanup

    private void cleanupButton() {
        if (copying) {
            return;
        }
        if (testDownloads.isEmpty() && testUsb.isEmpty()) {
            toast("Nothing to clean 无可清理文件");
            return;
        }
        clearTestFiles(false, () -> {
            setStatus("Test files removed 测试文件已清理");
            renderResults();
        });
    }

    /**
     * Forget the test files and delete them on a dedicated thread (the worker
     * executor is shut down when the page is destroyed). The USB root grant is
     * released too, if it was persisted.
     */
    private void clearTestFiles(boolean exiting, Runnable afterUi) {
        final List<Uri> ds = new ArrayList<>(testDownloads);
        final List<Uri> us = new ArrayList<>(testUsb);
        testDownloads.clear();
        testUsb.clear();
        downloadsCopy = null;
        downloadsName = null;
        downloadsMime = null;
        downloadsBytes = 0;
        // The USB root grant is kept for manual cleanup and released only on exit.
        final Uri root = usbRoot;
        final boolean persisted = exiting && rootPersisted;
        if (exiting) {
            usbRoot = null;
            rootPersisted = false;
        }
        if (ds.isEmpty() && us.isEmpty() && !persisted) {
            return;
        }
        final Context app = getApplicationContext();
        new Thread(() -> {
            for (Uri d : ds) {
                UsbCopyEngine.deleteDownloadsCopy(app, d);
            }
            for (Uri u : us) {
                UsbCopyEngine.deleteDocument(app, u);
            }
            if (persisted && root != null) {
                try {
                    app.getContentResolver().releasePersistableUriPermission(root,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (RuntimeException ignored) {
                    // already released
                }
            }
            if (afterUi != null) {
                main.post(afterUi);
            }
        }, "usb-test-cleanup").start();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            clearTestFiles(true, null);
        }
        super.onDestroy();
    }

    // ----------------------------------------------------------------- copy state helpers

    /** Lock or unlock the page for a copy: grey the actions, block back, show progress. */
    private void setCopying(boolean on) {
        copying = on;
        for (Button b : actionButtons) {
            b.setEnabled(!on);
        }
        for (Button b : deviceButtons) {
            b.setEnabled(!on);
        }
        backGuard.setEnabled(on);
        btnStop.setEnabled(on);
        copyProgress.setProgress(0);
        copyProgress.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void copyDone() {
        setCopying(false);
        setStatus("");
        renderResults();
    }

    private void reportProgress(long done, long total) {
        int pct = total > 0 ? (int) (done * 100 / total) : 0;
        ui(() -> {
            copyProgress.setProgress(pct);
            setStatus("Copying 拷贝中… " + pct + "%");
        });
    }

    private void setStatus(String msg) {
        copyStatus.setText(msg);
    }

    private void addRun(Run r) {
        runs.add(r);
        renderResults();
    }

    /** One line per run, then the average speed per step over successful runs. */
    private void renderResults() {
        if (runs.isEmpty()) {
            copyResult.setText("");
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        double[] sum = new double[3];
        int[] count = new int[3];
        for (int i = 0; i < runs.size(); i++) {
            Run r = runs.get(i);
            appendColored(sb, r.line(i + 1), stepColor(r.step));
            if (r.error == null && !r.stopped) {
                sum[r.step] += r.mbps;
                count[r.step]++;
            }
        }
        for (int step = 1; step <= 2; step++) {
            if (count[step] > 0) {
                appendColored(sb, String.format(Locale.US, "Average 平均 Step %d: %.2f MB/s (n=%d)",
                        step, sum[step] / count[step], count[step]), stepColor(step));
            }
        }
        copyResult.setText(sb);
    }

    /** Step 1 records are orange, step 2 records green. */
    private static final int STEP1_COLOR = 0xFFF57C00;
    private static final int STEP2_COLOR = 0xFF2E7D32;

    private static int stepColor(int step) {
        return step == 1 ? STEP1_COLOR : STEP2_COLOR;
    }

    /** Appends a line (newline-separated from the previous one) in the given colour. */
    private static void appendColored(SpannableStringBuilder sb, String text, int color) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Outcome of one copy run, shown as one line in the results list. */
    private static final class Run {
        final int step;
        final String file;
        final long bytes;
        final double seconds;
        final double mbps;
        final boolean stopped;
        final String error;

        private Run(int step, String file, long bytes, double seconds, double mbps,
                    boolean stopped, String error) {
            this.step = step;
            this.file = file;
            this.bytes = bytes;
            this.seconds = seconds;
            this.mbps = mbps;
            this.stopped = stopped;
            this.error = error;
        }

        static Run ok(int step, String file, UsbCopyEngine.Result r) {
            return new Run(step, file, r.bytes, r.seconds, r.mbPerSecond(), false, null);
        }

        static Run stopped(int step, String file) {
            return new Run(step, file, 0, 0, 0, true, null);
        }

        static Run failed(int step, String file, String error) {
            return new Run(step, file, 0, 0, 0, false, error);
        }

        String line(int no) {
            String label = step == 1 ? "Step 1 U盘→Downloads" : "Step 2 Downloads→U盘根目录";
            if (stopped) {
                return String.format(Locale.US, "#%d %s  %s  stopped 已停止", no, label, file);
            }
            if (error != null) {
                return String.format(Locale.US, "#%d %s  %s  failed 失败: %s", no, label, file, error);
            }
            return String.format(Locale.US, "#%d %s  %s  %.2f MB  %.2f s  → %.2f MB/s",
                    no, label, file, bytes / (1024.0 * 1024.0), seconds, mbps);
        }
    }

    /**
     * Stop only ends a running copy. Leaving the page does not stop a copy: back is
     * blocked during one, and the copy keeps its own state until it finishes.
     */
    @Override
    protected void onStopTests() {
        if (!copying) {
            stopTests();
        }
        if (receiverRegistered) {
            try { unregisterReceiver(refreshReceiver); } catch (Exception ignored) { }
            receiverRegistered = false;
        }
    }
}
