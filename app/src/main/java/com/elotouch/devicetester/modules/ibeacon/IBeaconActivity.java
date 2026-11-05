package com.elotouch.devicetester.modules.ibeacon;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * iBeacon module (level 2). Three sections:
 * <ol>
 *   <li>Scan: finds nearby iBeacon advertisements and lists UUID / major / minor / TX power / RSSI.</li>
 *   <li>Connect: opens a GATT connection to the selected device (most iBeacons do not accept
 *       connections; the status line reports the failure code).</li>
 *   <li>Send: advertises a user-defined iBeacon frame from this device.</li>
 * </ol>
 *
 * <p>Permissions are requested once when the page opens: location plus the Bluetooth
 * scan/connect/advertise permissions on API 31+. Scanning on older releases needs location too.
 * Scanner, GATT and advertiser are released in {@link #onStopTests()}.
 */
public class IBeaconActivity extends BaseTestActivity {

    private static final long REFRESH_MS = 1000;
    /** Drop a beacon from the list if it has not been heard for this long. */
    private static final long STALE_MS = 10_000;
    private static final String DEFAULT_UUID = "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0";

    /** One iBeacon seen during the current scan. Main-thread only. */
    private static final class Found {
        final BluetoothDevice device;
        String name;
        int rssi;
        IBeaconFrame frame;
        long lastSeen;

        Found(BluetoothDevice device) { this.device = device; }
    }

    // ---- scan state (main thread only; callbacks hop here via ui())
    private final Map<String, Found> found = new LinkedHashMap<>();
    private final List<Found> listItems = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private boolean scanning;
    private TextView scanStatus;
    private Found selected;
    private TextView selectedInfo;

    // ---- connect state
    private BluetoothGatt gatt;
    private TextView connStatus;

    // ---- advertise state
    private BluetoothLeAdvertiser advertiser;
    /** Non-null while advertising or while a start is pending. */
    private AdvertiseCallback advertiseCallback;
    private EditText uuidInput;
    private EditText majorInput;
    private EditText minorInput;
    private EditText txInput;
    private TextView advStatus;

    // ---- tabs (search / broadcast)
    private Button tabScanButton;
    private Button tabAdvButton;

    // ---- action buttons (greyed out when the action does not apply)
    private Button startScanBtn;
    private Button stopScanBtn;
    private Button connectBtn;
    private Button disconnectBtn;
    private Button startAdvBtn;
    private Button stopAdvBtn;
    private LinearLayout scanPage;
    private LinearLayout advPage;

    private final Runnable periodicRender = new Runnable() {
        @Override
        public void run() {
            pruneAndRender();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected String title() {
        return "iBeacon / 信标";
    }

    @Override
    protected void buildUi() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabScanButton = tabButton("搜索 Scan", () -> showTab(true));
        tabAdvButton = tabButton("广播 Broadcast", () -> showTab(false));
        tabs.addView(tabScanButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabs.addView(tabAdvButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addView(tabs);

        scanPage = buildPage(this::buildScanSection);
        advPage = buildPage(this::buildAdvertiseSection);
        showTab(true);

        // Ask once, up front. A denial only affects the sub-sections that need the missing permission.
        requirePermissions(requiredPermissions(), () -> { }, () -> {
            scanStatus.setText("Permission restricted 权限受限");
            advStatus.setText("Permission restricted 权限受限");
        });
    }

    private Button tabButton(String text, Runnable onClick) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** Builds one tab's rows into their own container so the whole page can be shown or hidden. */
    private LinearLayout buildPage(Runnable section) {
        LinearLayout saved = content;
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        content = page;
        try {
            section.run();
        } finally {
            content = saved;
        }
        addView(page);
        return page;
    }

    private void showTab(boolean scan) {
        // Leaving a tab stops what it was doing: scanning and the GATT connection belong to the
        // search tab, advertising belongs to the broadcast tab.
        if (scan) {
            if (advertiseCallback != null) {
                stopAdvertisingQuietly();
                advStatus.setText("Stopped 已停止发送（切换页面）");
            }
        } else {
            if (scanning) {
                stopScanQuietly();
                pruneAndRender();
                scanStatus.setText("Scan stopped 已停止搜索（切换页面）· " + found.size() + " iBeacon(s) found");
            }
            if (gatt != null) {
                closeGatt();
                connStatus.setText("Disconnected 已断开（切换页面）");
            }
        }
        scanPage.setVisibility(scan ? android.view.View.VISIBLE : android.view.View.GONE);
        advPage.setVisibility(scan ? android.view.View.GONE : android.view.View.VISIBLE);
        styleTab(tabScanButton, scan);
        styleTab(tabAdvButton, !scan);
        refreshButtonStates();
    }

    /** Enables only the start/stop/connect/disconnect buttons that match the current state. */
    private void refreshButtonStates() {
        if (startScanBtn == null) return; // not built yet
        startScanBtn.setEnabled(!scanning);
        stopScanBtn.setEnabled(scanning);
        connectBtn.setEnabled(gatt == null);
        disconnectBtn.setEnabled(gatt != null);
        startAdvBtn.setEnabled(advertiseCallback == null);
        stopAdvBtn.setEnabled(advertiseCallback != null);
    }

    private void styleTab(Button b, boolean selected) {
        b.setBackgroundColor(selected ? getColor(com.elotouch.devicetester.R.color.primary) : Color.parseColor("#E0E0E0"));
        b.setTextColor(selected ? Color.WHITE : Color.BLACK);
    }

    private void buildScanSection() {
        addSectionTitle("1. Scan iBeacon 搜索 iBeacon 设备");
        scanStatus = addInfo("Idle 未开始");
        startScanBtn = addButton("Start scan 开始搜索", this::startScan);
        stopScanBtn = addButton("Stop scan 停止搜索", this::stopScan);

        ListView list = new ListView(this);
        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((AdapterView<?> parent, android.view.View v, int pos, long id) -> {
            if (pos >= 0 && pos < listItems.size()) selectDevice(listItems.get(pos));
        });
        addView(list);
        // ListView inside a ScrollView needs a fixed height to lay out.
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));

        addSectionTitle("2. Connect 连接设备");
        selectedInfo = addInfo("Tap a device in the list above to select it.\n请在上方列表中点击选择设备。");
        connectBtn = addButton("Connect 连接所选设备", this::connectSelected);
        disconnectBtn = addButton("Disconnect 断开连接", this::disconnect);
        connStatus = addInfo("Not connected 未连接");

    }

    private void buildAdvertiseSection() {
        addSectionTitle("1. Broadcast iBeacon 广播 iBeacon 数据");
        uuidInput = addField("Proximity UUID 区域 UUID", DEFAULT_UUID,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        majorInput = addField("Major 主值 (0-65535)", "1", InputType.TYPE_CLASS_NUMBER);
        minorInput = addField("Minor 次值 (0-65535)", "1", InputType.TYPE_CLASS_NUMBER);
        txInput = addField("TX power 发射功率 @1m, dBm (-128 to 127)", "-59",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        startAdvBtn = addButton("Start advertising 开始发送", this::startAdvertising);
        stopAdvBtn = addButton("Stop advertising 停止发送", this::stopAdvertising);
        advStatus = addInfo("Idle 未发送");
    }

    private EditText addField(String label, String initial, int inputType) {
        addInfo(label);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setText(initial);
        addView(field);
        return field;
    }

    // ----------------------------------------------------------------- permissions

    private String[] requiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        return perms.toArray(new String[0]);
    }

    private boolean allGranted(String... perms) {
        for (String p : perms) {
            if (!hasPermission(p)) return false;
        }
        return true;
    }

    private String[] scanPermissions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.BLUETOOTH_SCAN}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private String[] connectPermissions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{Manifest.permission.BLUETOOTH_CONNECT}
                : new String[0];
    }

    private String[] advertisePermissions() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{Manifest.permission.BLUETOOTH_ADVERTISE}
                : new String[0];
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    // ----------------------------------------------------------------- scan

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (scanning) return;
        if (!allGranted(scanPermissions())) {
            scanStatus.setText("Permission restricted 权限受限（需要定位与蓝牙扫描权限）");
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            scanStatus.setText("No Bluetooth adapter 无蓝牙适配器");
            return;
        }
        if (!adapter.isEnabled()) {
            scanStatus.setText("Bluetooth is off 蓝牙未开启");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            scanStatus.setText("BLE scanner unavailable 不支持 BLE 扫描");
            return;
        }
        if (scanCallback == null) {
            scanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    handleResult(result);
                }

                @Override
                public void onBatchScanResults(List<ScanResult> results) {
                    for (ScanResult r : results) handleResult(r);
                }

                @Override
                public void onScanFailed(int errorCode) {
                    ui(() -> {
                        scanning = false;
                        main.removeCallbacks(periodicRender);
                        scanStatus.setText("Scan failed 搜索失败, error code " + errorCode);
                        refreshButtonStates();
                    });
                }
            };
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, scanCallback);
        } catch (SecurityException | IllegalStateException e) {
            scanStatus.setText("Scan could not start 无法开始搜索: " + e.getMessage());
            return;
        }
        scanning = true;
        found.clear();
        pruneAndRender();
        scanStatus.setText("Scanning… 搜索中…");
        refreshButtonStates();
        main.removeCallbacks(periodicRender);
        main.postDelayed(periodicRender, REFRESH_MS);
    }

    /** Runs on the Bluetooth callback thread: decode here, then hop to the UI thread. */
    private void handleResult(ScanResult result) {
        if (result == null || result.getDevice() == null) return;
        ScanRecord record = result.getScanRecord();
        if (record == null) return;
        IBeaconFrame frame = IBeaconFrame.parse(
                record.getManufacturerSpecificData(IBeaconFrame.APPLE_COMPANY_ID));
        if (frame == null) return; // not an iBeacon
        BluetoothDevice device = result.getDevice();
        int rssi = result.getRssi();
        String name = record.getDeviceName();
        ui(() -> {
            if (!scanning) return;
            String address = device.getAddress();
            Found f = found.get(address);
            if (f == null) {
                f = new Found(device);
                found.put(address, f);
            }
            f.rssi = rssi;
            f.frame = frame;
            if (name != null) f.name = name;
            f.lastSeen = SystemClock.elapsedRealtime();
        });
    }

    private void stopScan() {
        if (!scanning) {
            scanStatus.setText("Not scanning 未在搜索");
            return;
        }
        stopScanQuietly();
        pruneAndRender();
        scanStatus.setText("Scan stopped 已停止搜索 · " + found.size() + " iBeacon(s) found");
    }

    @SuppressLint("MissingPermission")
    private void stopScanQuietly() {
        main.removeCallbacks(periodicRender);
        if (!scanning) return;
        scanning = false;
        refreshButtonStates();
        try {
            if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback);
        } catch (SecurityException | IllegalStateException ignored) {
            // Bluetooth turned off or permission revoked: nothing left to stop.
        }
    }

    /** Drops stale beacons and rebuilds the list. Main thread only. */
    private void pruneAndRender() {
        long now = SystemClock.elapsedRealtime();
        if (scanning) {
            Iterator<Found> it = found.values().iterator();
            while (it.hasNext()) {
                if (now - it.next().lastSeen > STALE_MS) it.remove();
            }
        }
        listItems.clear();
        listItems.addAll(found.values());

        List<String> labels = new ArrayList<>();
        for (Found f : listItems) {
            String label = f.frame.uuid
                    + "\nMajor " + f.frame.major + "  Minor " + f.frame.minor
                    + "  TX " + f.frame.txPower + " dBm  RSSI " + f.rssi + " dBm"
                    + "\n" + f.device.getAddress() + (f.name != null ? "  " + f.name : "");
            labels.add(label);
        }
        listAdapter.clear();
        listAdapter.addAll(labels);
        listAdapter.notifyDataSetChanged();

        if (scanning) {
            scanStatus.setText("Scanning… 搜索中… · " + listItems.size() + " iBeacon(s) found 已发现");
        }
    }

    private void selectDevice(Found f) {
        selected = f;
        selectedInfo.setText("Selected 已选择:\n" + f.device.getAddress()
                + "\nUUID " + f.frame.uuid
                + "\nMajor " + f.frame.major + "  Minor " + f.frame.minor);
    }

    // ----------------------------------------------------------------- connect

    private void connectSelected() {
        if (selected == null) {
            toast("Select a device first 请先选择设备");
            return;
        }
        if (!allGranted(connectPermissions())) {
            connStatus.setText("Permission restricted 权限受限（需要蓝牙连接权限）");
            return;
        }
        stopScanQuietly();
        pruneAndRender();
        closeGatt();
        try {
            gatt = selected.device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            connStatus.setText("Permission restricted 权限受限");
            return;
        }
        connStatus.setText("Connecting… 连接中…");
        refreshButtonStates();
    }

    private void disconnect() {
        if (gatt == null) {
            connStatus.setText("Not connected 未连接");
            return;
        }
        try {
            gatt.disconnect();
            connStatus.setText("Disconnecting… 断开中…");
        } catch (SecurityException e) {
            closeGatt();
            connStatus.setText("Disconnected 已断开");
        }
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (gatt == null) return;
        try {
            gatt.close();
        } catch (SecurityException | IllegalStateException ignored) {
            // Already torn down by the system.
        }
        gatt = null;
        refreshButtonStates();
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            ui(() -> {
                if (g != gatt) return; // stale callback from a closed connection
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connStatus.setText("Connected 已连接, discovering services… 正在发现服务…");
                    try {
                        g.discoverServices();
                    } catch (SecurityException e) {
                        connStatus.setText("Connected 已连接 (service discovery not permitted)");
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    String hint = status == 133
                            ? " — device refused or did not answer; iBeacons are usually not connectable"
                            : "";
                    connStatus.setText("Disconnected 已断开 (status " + status + ")" + hint);
                    closeGatt();
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            ui(() -> {
                if (g != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connStatus.setText("Connected 已连接\nServices 服务数: " + g.getServices().size());
                } else {
                    connStatus.setText("Service discovery failed 服务发现失败 (status " + status + ")");
                }
            });
        }
    };

    // ----------------------------------------------------------------- advertise

    @SuppressLint("MissingPermission")
    private void startAdvertising() {
        if (advertiseCallback != null) return;
        if (!allGranted(advertisePermissions())) {
            advStatus.setText("Permission restricted 权限受限（需要蓝牙广播权限）");
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            advStatus.setText("No Bluetooth adapter 无蓝牙适配器");
            return;
        }
        if (!adapter.isEnabled()) {
            advStatus.setText("Bluetooth is off 蓝牙未开启");
            return;
        }
        if (!adapter.isMultipleAdvertisementSupported()) {
            advStatus.setText("Not supported 不支持: this device cannot advertise over BLE 本设备不支持 BLE 广播");
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            advStatus.setText("Not supported 不支持: no BLE advertiser 无 BLE 广播器");
            return;
        }

        byte[] payload;
        try {
            payload = IBeaconFrame.build(
                    uuidInput.getText().toString(),
                    intOf(majorInput, "Major"),
                    intOf(minorInput, "Minor"),
                    intOf(txInput, "TX power"));
        } catch (IllegalArgumentException e) {
            advStatus.setText("Invalid input 输入无效: " + e.getMessage());
            return;
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(IBeaconFrame.APPLE_COMPANY_ID, payload)
                .build();

        AdvertiseCallback callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                ui(() -> {
                    if (advertiseCallback == this) {
                        advStatus.setText("Broadcasting 发送中\n" + describeSent());
                    }
                });
            }

            @Override
            public void onStartFailure(int errorCode) {
                ui(() -> {
                    if (advertiseCallback == this) advertiseCallback = null;
                    advStatus.setText("Start failed 启动失败, error code " + errorCode);
                    refreshButtonStates();
                });
            }
        };
        advertiseCallback = callback;
        try {
            advertiser.startAdvertising(settings, data, callback);
        } catch (SecurityException | IllegalStateException e) {
            advertiseCallback = null;
            advStatus.setText("Start failed 启动失败: " + e.getMessage());
            refreshButtonStates();
            return;
        }
        advStatus.setText("Starting… 启动中…");
        refreshButtonStates();
    }

    @SuppressLint("MissingPermission")
    private void stopAdvertising() {
        if (advertiseCallback == null) {
            advStatus.setText("Not broadcasting 未发送");
            return;
        }
        stopAdvertisingQuietly();
        advStatus.setText("Stopped 已停止发送");
    }

    @SuppressLint("MissingPermission")
    private void stopAdvertisingQuietly() {
        AdvertiseCallback callback = advertiseCallback;
        advertiseCallback = null;
        refreshButtonStates();
        if (callback == null || advertiser == null) return;
        try {
            advertiser.stopAdvertising(callback);
        } catch (SecurityException | IllegalStateException ignored) {
            // Adapter already off; the system dropped the advertisement.
        }
    }

    private String describeSent() {
        return "UUID " + uuidInput.getText().toString().trim().toUpperCase()
                + "\nMajor " + majorInput.getText() + "  Minor " + minorInput.getText()
                + "  TX " + txInput.getText() + " dBm";
    }

    private static int intOf(EditText field, String name) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " is not a number");
        }
    }

    // ----------------------------------------------------------------- lifecycle

    @Override
    protected void onStopTests() {
        stopScanQuietly();
        stopAdvertisingQuietly();
        closeGatt();
    }
}
