package com.elotouch.devicetester.modules.ddr;

/**
 * Runs the vendor QMESA memory stress tool (PRD DDR §3.1 native-tool addendum)
 * with the fixed 8-16MB / 4-thread / 10000s configuration.
 */
public class QmesaActivity extends NativeToolTestActivity {

    @Override
    protected String title() {
        return "QMESA";
    }

    @Override
    protected String soName() {
        return "libqmesa64.so";
    }

    @Override
    protected String[] buildArgs() {
        return new String[]{"-startSize", "8MB", "-endSize", "8MB", "-totalSize", "16MB",
                "-errorCheck", "T", "-secs", "10000", "-numThreads", "4"};
    }

    @Override
    protected String failureKeyword() {
        return "FAILED";
    }

    @Override
    protected String description() {
        return "Runs the vendor QMESA stress tool with an 8-16MB working set across "
                + "4 threads, for up to ~2.7 hours or until stopped.\n"
                + "运行厂商 QMESA 压力测试工具（8-16MB 工作集，4 线程），最长约 2.7 小时或手动停止。";
    }
}
