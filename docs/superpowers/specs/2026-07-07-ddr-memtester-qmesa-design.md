# DDR 模块新增 memtester / QMESA 原生工具测试 — 设计

日期：2026-07-07

## 背景

DDR 模块（`modules/ddr/DdrActivity.java`）目前只有纯 Java 实现的容量信息、带宽测试、压力填充测试。现在需要在同一个模块内追加两个第三方原生诊断工具的测试项：

- **memtester** — 标准开源内存测试工具（已提供 Android 编译好的 arm64-v8a 版本，位于仓库根目录 `memtester`，4.3.0，动态链接，用法 `memtester <mem>[B|K|M|G] [loops]`）。
- **QMESA** — 高通/芯片厂商的内存压力诊断工具（已提供 arm64-v8a 静态链接可执行文件 `QMESA_64`，命令行：
  `./QMESA_64 -startSize 8MB -endSize 8MB -totalSize 16MB -errorCheck T -secs 10000 -numThreads 4`，
  输出含 `PASSED`/`FAILED`/`Completed running %d stess tests.` 等可识别标记）。

两者都只需支持 arm64-v8a（已确认无需 armeabi-v7a）。

## 关键工程约束

App 的 `targetSdk 34`（`app/build.gradle`）会触发 Android 10+ 的 W^X 限制：无法执行从 `assets` 拷贝到内部存储（`filesDir`）后再 `chmod` 的文件（`noexec` 挂载会导致 `EACCES`）。因此必须使用 **`jniLibs` 打包手法**：把可执行文件以 `.so` 命名放进 `jniLibs/<abi>/`，让 Android 安装时把它当作原生库解压到 `nativeLibraryDir`（该目录允许执行）。

同时需要在 `build.gradle` 显式设置：

```gradle
android {
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
```

强制在安装时把库解压到磁盘（而不是新版默认的"从 APK 内直接 mmap 不落盘"模式），否则 `nativeLibraryDir` 下不会有真实文件可供 `ProcessBuilder` 执行。

## 架构

### 1. 二进制打包

- `memtester` → 重命名并放入 `app/src/main/jniLibs/arm64-v8a/libmemtester.so`
- `QMESA_64` → 重命名并放入 `app/src/main/jniLibs/arm64-v8a/libqmesa64.so`
- `QMESA_32` 保留在仓库根目录，不纳入 App（当前只支持 arm64-v8a）。

### 2. `core/NativeProcessRunner`（新增，通用组件）

放在 `core` 包，与 `BaseTestActivity` / `HardwareDetector` 同级，因为它不含任何 DDR 专属逻辑，是"启动一个随包内置的原生可执行文件"这一通用能力：

```java
public class NativeProcessRunner {
    public static boolean isArm64Supported();              // 检查 Build.SUPPORTED_ABIS
    public void run(Context ctx, String soName, String[] args,
                     LineListener onLine) throws IOException;  // 阻塞，按行回调，调用方需在后台线程调用
    public void stop();                                     // process.destroy()，用于真正打断阻塞中的 readLine()
    public interface LineListener { void onLine(String line); }
}
```

- `run()` 内部：`ProcessBuilder(nativeLibraryDir + "/" + soName, ...args)`，`redirectErrorStream(true)`，逐行 `BufferedReader.readLine()` 回调，`process.waitFor()` 收尾。
- `stop()` 从其他线程调用时 `destroy()` 当前 `Process`，让 `readLine()` 立即返回 null 从而使 `run()` 结束——仅靠 `BaseTestActivity` 现有的 `stopped` 布尔标志无法打断阻塞的 native 读取，必须真正杀掉子进程。

### 3. `DdrActivity` UI 新增两个区块

紧跟在现有 "Stress Fill" 区块之后，复用 `addSectionTitle/addInfo/addButton/runAsync/ui()`：

**Memtester (native)**
- 容量：`min(totalMem / 4, availMem * 0.8)`（1/4 总内存，但用可用内存的 80% 兜底，避免在内存紧张设备上被系统 OOM-kill 掉，思路与现有 Stress Fill 的安全阈值一致）。
- 不传 `loops` 参数 → memtester 无限循环，直到用户点 Stop。
- 已测试时间：Start 时记录起始时间戳，独立的每秒 Handler 计时器更新"已测试时间 mm:ss"文本，Stop 时停止计时器。
- 日志：固定容量 10 的滚动窗口（`ArrayDeque`，超过 10 行挤出最旧的），只显示最新 10 行。
- 状态摘要：独立于可见日志窗口，对**每一行**输出做关键字扫描（`FAILURE` → 标红 FAIL），未见失败且仍在运行显示"运行中"，从未失败运行满意也不会因为日志滚出可视区而漏判。

**QMESA (native)**
- 命令行参数完全按用户给定的原样：`-startSize 8MB -endSize 8MB -totalSize 16MB -errorCheck T -secs 10000 -numThreads 4`。
- 与 memtester 区块交互风格一致：计时器 + 最新10行滚动日志 + 全量关键字扫描（`FAILED` → 标红）。

**互斥**：两个区块共用 `BaseTestActivity` 的单线程 `executor`。当其中一个测试在运行时，另一个区块的 Start 按钮直接 `setEnabled(false)`；当前测试 Stop/结束后自动恢复可点击。

**ABI 兜底**：`buildUi()` 里调用 `NativeProcessRunner.isArm64Supported()`，若为 `false`，两个区块的 Start 按钮直接禁用，文案改为"当前设备架构不支持 arm64-v8a 原生工具 / Native tool requires arm64-v8a"（遵循 PRD 里"缺失能力必须置灰、绝不可点"的约束）。

### 4. 生命周期与错误处理

- `DdrActivity.onStopTests()` 追加对两个 `NativeProcessRunner` 实例的 `stop()` 调用，确保切后台/退出页面时不留子进程（与现有 `releaseBlocks()` 同一套 `onStopTests` 约定）。
- `ProcessBuilder.start()` 抛 `IOException` 时捕获并在摘要行显示"启动失败: <message>"，不让整个 Activity 崩溃。

## 数据流

```
用户点 Start
  → runAsync(() -> runner.run(ctx, soName, args, line -> ui(() -> {
        appendToRollingLog(line);      // 最新10行
        scanForFailureKeyword(line);   // 全量扫描，更新摘要状态
    })))
用户点 Stop / onStopTests
  → runner.stop() -> process.destroy() -> run() 中 readLine() 返回 null -> runAsync 结束
  → 计时器 Handler 停止
  → 恢复另一个区块的 Start 按钮可点击

进程自然结束（例如 QMESA 达到 -secs 时长后自行退出；memtester 因未传 loops 正常不会自然结束，仅在异常退出时走此路径）
  → readLine() 返回 null -> runAsync 正常结束，效果与用户点 Stop 相同：
  → 计时器停止、恢复另一区块 Start 按钮可点、摘要行定格显示最终 PASS/FAIL
```

## 测试计划

项目当前没有自动化测试基础设施（README: "No automated tests yet"），沿用现状，采用手动验证：

1. 真机（arm64-v8a）上分别点 Start memtester / QMESA，确认：日志开始滚动、计时器开始跳动、另一个区块的 Start 按钮被禁用。
2. 点 Stop，确认：进程被杀（可用 `adb shell ps` 确认无残留 `libmemtester.so`/`libqmesa64.so` 进程）、计时器停止、另一按钮恢复可点。
3. 切到后台再回来 / 退出该 Activity 再进入，确认没有残留进程、UI 状态复位。
4. 人为制造一次失败场景较难（两个工具本身就是硬件诊断工具），此项以代码走查关键字匹配逻辑为准，配合真实内存故障机（如有）复测。
5. 走查 `isArm64Supported()` 分支：在非 arm64 设备/模拟器（如 x86_64 模拟器）上确认两个区块的 Start 按钮为禁用状态、文案正确。
