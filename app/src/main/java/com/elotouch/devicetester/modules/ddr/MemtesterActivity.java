package com.elotouch.devicetester.modules.ddr;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Runs the open-source memtester binary (PRD DDR §3.1 native-tool addendum)
 * against ~1/4 of total RAM, capped at 80% of available RAM.
 */
public class MemtesterActivity extends NativeToolTestActivity {

    @Override
    protected String title() {
        return "Memtester";
    }

    @Override
    protected String soName() {
        return "libmemtester.so";
    }

    @Override
    protected String[] buildArgs() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long sizeBytes = Math.min(mi.totalMem / 4, (long) (mi.availMem * 0.8));
        long sizeMb = sizeBytes / (1024 * 1024);
        return new String[]{sizeMb + "M"};
    }

    @Override
    protected String failureKeyword() {
        return "FAILURE";
    }

    @Override
    protected String description() {
        return "Runs the open-source memtester binary against ~1/4 of total RAM "
                + "(capped at 80% of available RAM), looping forever until stopped.\n"
                + "运行开源 memtester 工具，测试容量约为总内存的 1/4（不超过可用内存的 80%），"
                + "无限循环直到点击 Stop。";
    }
}
