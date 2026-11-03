package com.elotouch.devicetester.modules.payment;

import com.elotouch.devicetester.core.BaseTestActivity;

/**
 * Payment module. Test items are not defined yet, so the page only shows its
 * title and a placeholder; add the test sections to {@link #buildUi()} when
 * they are specified.
 */
public class PaymentActivity extends BaseTestActivity {

    @Override
    protected String title() {
        return "Payment 支付测试";
    }

    @Override
    protected void buildUi() {
        addSectionTitle("MSR / 磁条读卡器");
        addInfo("Reads the magnetic stripe on swipe cards (credit/debit/membership).\n"
                + "读取刷卡银行卡、会员卡等磁条卡的磁条数据。");

        addSectionTitle("SCR / 智能卡读卡器");
        addInfo("Reads contact IC (chip) cards inserted into the slot, e.g. EMV cards.\n"
                + "读取插入卡槽的接触式 IC 芯片卡（如 EMV 卡）。");

        addSectionTitle("NFC / 近场通信");
        addInfo("Reads contactless cards and phones tapped on the reader (NFC, Apple/Google Pay).\n"
                + "读取靠近读卡区的非接触式卡片及手机（NFC、Apple/Google Pay）。");
    }
}
