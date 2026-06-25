package com.elotouch.devicetester.modules.nfc;

import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.provider.Settings;
import android.widget.TextView;

import com.elotouch.devicetester.core.BaseTestActivity;

import java.util.Locale;

/**
 * NFC module (PRD §3.3): reports adapter status (offers a jump to settings if
 * disabled) and reads a card UID + tech list via foreground dispatch.
 */
public class NfcActivity extends BaseTestActivity {

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private TextView statusText;
    private TextView readText;

    @Override
    protected String title() {
        return "NFC 测试";
    }

    @Override
    protected void buildUi() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        addSectionTitle("NFC 状态");
        statusText = addInfo("");
        addButton("打开 NFC 系统设置", () ->
                startActivity(new Intent(Settings.ACTION_NFC_SETTINGS)));

        addSectionTitle("读卡检测");
        readText = addInfo("请将 IC 卡靠近感应区…");

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        Intent intent = new Intent(this, getClass())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);

        refreshStatus();
    }

    private void refreshStatus() {
        if (nfcAdapter == null) {
            statusText.setText("本设备不支持 NFC。");
        } else if (!nfcAdapter.isEnabled()) {
            statusText.setText("NFC 已关闭，请点击下方按钮开启。");
        } else {
            statusText.setText("NFC 已开启，可进行读卡检测。");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, null);
        }
    }

    @Override
    protected void onStopTests() {
        if (nfcAdapter != null) {
            try {
                nfcAdapter.disableForegroundDispatch(this);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTag(intent);
    }

    private void handleTag(Intent intent) {
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) return;
        StringBuilder sb = new StringBuilder("读卡成功！\n");
        sb.append("UID：").append(toHex(tag.getId())).append('\n');
        sb.append("支持技术：\n");
        for (String tech : tag.getTechList()) {
            sb.append("  · ").append(tech.substring(tech.lastIndexOf('.') + 1)).append('\n');
        }
        readText.setText(sb.toString());
        toast("读卡成功，天线与芯片正常");
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null) return "(空)";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02X ", b));
        return sb.toString().trim();
    }
}
