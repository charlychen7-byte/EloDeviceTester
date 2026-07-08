# DDR 模块新增 memtester / QMESA 原生工具测试 — 设计

日期：2026-07-07（2026-07-08 修订：真机验证发现问题后，架构由"DDR 页内嵌两个区块"改为"独立 Activity"，见文末修订记录）

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

### 3. UI 架构（2026-07-08 修订版，取代原"DdrActivity 内嵌两区块"方案）

**`modules/ddr/NativeToolTestActivity`（新增，抽象基类，extends `BaseTestActivity`）**

把原本写在 `DdrActivity` 内部类 `NativeTestSection` 里的全部行为搬到这里（包括真机验证中发现并修复的问题，见下方"真机验证发现的问题与修复"）：

```java
public abstract class NativeToolTestActivity extends BaseTestActivity {
    protected abstract String soName();          // 如 "libmemtester.so"
    protected abstract String[] buildArgs();      // 运行时计算（memtester 依赖当前内存信息）
    protected abstract String failureKeyword();   // 如 "FAILURE" / "FAILED"
    protected abstract String description();      // 区块说明文案（双语）
}
```

页面元素自上而下：说明文字 → 状态/计时文字 → **Start 按钮 → Stop 按钮**（原方案里在日志框下方，现移到日志框**上方**，位置固定，不会被日志内容撑高而被挤出屏幕）→ 终端风格日志框（黑底白字等宽字体）。

- 容量/参数计算、无限循环语义、ABI 兜底文案：与原方案一致，分别下放到 `MemtesterActivity.buildArgs()` / `QmesaActivity.buildArgs()`。
- **不再需要互斥逻辑**：memtester 和 QMESA 现在是两个独立 Activity，Android 同时只能有一个在前台，原方案里两个区块交叉禁用对方 Start 按钮的 `other` 字段引用整个去掉。
- 结束（Stop 或进程自然结束）时，状态文字里带上最终时长，例如 `PASSED 测试完成，已测试时间 02:15 / no errors detected, elapsed 02:15`。

**`MemtesterActivity extends NativeToolTestActivity`** / **`QmesaActivity extends NativeToolTestActivity`**：各自只提供 `soName()`/`buildArgs()`/`failureKeyword()`/`description()`，参数值与原方案完全一致（memtester 用 `min(totalMem/4, availMem*0.8)`、不传 loops；QMESA 用用户给定的原始命令行）。

**`DdrActivity`**：原来两个区块的全部逻辑删除，改为每个只保留标题 + 一句说明 + 一个"进入测试 Enter Test"按钮：

```java
addSectionTitle("Memtester (native tool 原生工具)");
addInfo("...说明文字...");
addButton("Enter Test / 进入测试", () -> startActivity(new Intent(this, MemtesterActivity.class)));
```

QMESA 区块同理。

**AndroidManifest.xml**：新增 `MemtesterActivity` / `QmesaActivity` 的 `<activity>` 声明（与 `ColorTestActivity` 等 Display 模块的子测试 Activity 同样的声明方式，不需要新增 `TestModule` 枚举项，因为它们不是一级模块）。

### 4. 生命周期与错误处理

- `NativeToolTestActivity.onStopTests()`（每个子类共享基类实现）调用 `NativeProcessRunner.stop()`，确保切后台/退出页面时不留子进程。
- `ProcessBuilder.start()` 抛 `IOException` 时捕获并在状态文字显示"启动失败: <message>"，不让整个 Activity 崩溃；**必须与用户主动点 Stop 区分开**（见下方修复记录 3），不能把 Stop 误报成启动失败。

## 数据流

```
用户点 Start
  → runAsync(() -> runner.run(ctx, soName, args, line -> {
        更新 failureSeen（全量扫描，不截断）;
        缓冲进 pendingLines，若无待处理的UI刷新则 post 一次合并刷新（防ANR）;
    }))
    → 合并刷新时：显示副本按行截断到~200字符，只保留最新10行

用户点 Stop / onStopTests
  → runner.stop() -> stopRequested=true -> process.destroy()
  → run() 内 readLine() 抛出的 IOException 因 stopRequested=true 被吞掉，run() 正常返回（不再误判为启动失败）
  → 计时器停止，状态文字显示 PASSED/FAILED + 最终已测试时长

进程自然结束（例如 QMESA 达到 -secs 时长后自行退出；memtester 因未传 loops 正常不会自然结束，仅在异常退出时走此路径）
  → 效果与用户点 Stop 相同
```

## 真机验证发现的问题与修复（2026-07-08，原"DdrActivity 内嵌区块"方案阶段发现，架构迁移后仍需保留这些修复）

1. **ANR（已修复）**：memtester 在 mlock 失败时高频刷日志（每秒数千行），原实现每行都 `post` 一次 UI 更新，导致主线程被淹没、输入事件 5 秒无响应，真实触发系统级 ANR。修复：后台线程把行堆进缓冲区，仅当没有已排队的 UI 刷新时才 `post` 一次，UI 线程一次性排干缓冲区并重建日志（详见 `core/NativeProcessRunner` 与日志区渲染代码里的合并刷新机制）。
2. **单行无界撑高（已修复）**：memtester 用退格符在同一行内写进度，导致单条"逻辑行"可能几万字符长，10 行的滚动窗口在屏幕上占据几十行视觉高度，把 Stop 按钮挤出屏幕。修复：显示副本按尾部截断到 ~200 字符（失败关键字扫描仍对完整原始行进行）；这次架构调整（Start/Stop 移到日志框上方）从布局上进一步兜底了这个问题。
3. **Stop 被误报为启动失败（已修复）**：`process.destroy()` 会导致阻塞中的 `readLine()` 抛 `IOException`，原实现把这个异常和"二进制没启动起来"混为一谈，导致正常点 Stop 却显示"启动失败"。修复：`NativeProcessRunner` 内加 `stopRequested` 标志，`stop()` 时置位，读循环里区分这两种情况。
4. 附带需求：日志框改为黑底白字终端风格（已实现）。

## 已知问题（待跟进，不阻塞本次架构调整）

- **QMESA 在 App 自身进程中执行无输出**：直接用 `adb shell run-as com.elotouch.devicetester <路径>/libqmesa64.so ...` 手动执行完全正常（完整跑出横幅和测试过程），但通过 App 的 `NativeProcessRunner.run()` 调用时，`readLine()` 立即返回 EOF、没有任何输出，且 `ps` 里也看不到对应子进程存活的痕迹。初步怀疑与 QMESA_64 是非 PIE 的普通 Linux 静态可执行文件（不是 memtester 那种 Android 编译的 PIE 格式）有关，`run-as` 拿到的 shell 域 SELinux 权限比 App 自身的 `untrusted_app` 域更宽松，两者对同一文件的执行结果不同。已确认文件本身完好（`libqmesa64.so` 与 `libmemtester.so` 在设备上的 SELinux label 均为 `u:object_r:apk_data_file:s0`，权限 `rwxr-xr-x`，大小正确）。此问题排查已推迟到本次 UI 架构调整完成之后。

## 测试计划

项目当前没有自动化测试基础设施（README: "No automated tests yet"），沿用现状，采用手动验证：

1. DDR 页面点"进入测试"，确认正确跳转到 `MemtesterActivity` / `QmesaActivity`，标题、说明文字正确。
2. 新页面里点 Start，确认：日志开始滚动（黑底白字终端样式）、状态文字开始跳动显示已测试时间、Start 按钮禁用、Stop 按钮启用。
3. 点 Stop，确认：进程被杀（`adb shell ps` 确认无残留 `libmemtester.so`/`libqmesa64.so` 进程）、状态文字显示 PASSED/FAILED 并带最终已测试时长（不是"启动失败"）、Start 按钮恢复可点。
4. 按返回键退出测试页面（不点 Stop），确认 `onStopTests()` 正确杀掉子进程，无残留。
5. 走查 `isArm64Supported()` 分支：在非 arm64 设备/模拟器上确认 Start 按钮为禁用状态、文案正确。
6. 长时间运行（如 memtester 跑过多个 subtest，产生大量超长单行日志），确认 Start/Stop 按钮位置固定、无需大量滚动即可点到。
