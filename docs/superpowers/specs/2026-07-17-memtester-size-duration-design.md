# Memtester 测试项新增大小选择 / 时长输入 / 结果着色 / hh:mm:ss 计时 — 设计

日期：2026-07-17

## 背景

[[2026-07-07-ddr-memtester-qmesa-design]] 已经把 memtester / QMESA 拆成独立的
`MemtesterActivity` / `QmesaActivity`，共享 `NativeToolTestActivity` 基类
（Start/Stop、后台运行、日志滚动、PASSED/FAILED 判定）。

当前 Memtester 页面的两个问题需要改进：

1. 测试容量固定写死为 `min(总内存 1/4, 可用内存 80%)`，用户无法选择。
2. 测试时长没有上限选项，只能无限循环直到手动点 Stop；也没有失败/成功的视觉区分
   （纯文字 PASSED/FAILED），计时显示只有 `mm:ss`，长时间测试（memtester 无限循环
   可能跑数小时甚至更久）超过一小时后无法直观看出到底跑了多久。

## 需求（用户确认）

- Memtester 测试页新增一个单选控件，二选一：
  - **1/4 总内存**（默认选中）
  - **80% 可用内存**
  - 与原来的 `min(...)` 语义不同：现在用户选哪个就用哪个，不再取两者较小值。
- 新增一个时长输入框，单位为**分钟**：
  - 留空 = 无限运行，直到手动点 Stop（与现状一致）。
  - 填写正整数分钟数 → 到时自动停止，效果等同于用户手动点了 Stop（若过程中未
    检测到 FAILURE，结果仍显示 **PASSED**，不做区分提示）。
  - 非法输入（非数字、0、负数）→ 视为未填写，等同无限运行。
- 结果文字颜色区分：**PASSED 用绿色，FAILED 用红色**；其余状态（启动失败 /
  被系统信号杀死）保持默认文字颜色。
- 运行中的"已测试时间"与结束后的"最终已测试时长"都改为 **hh:mm:ss** 格式
  （原来是 `mm:ss`）。
- 颜色区分和 hh:mm:ss 计时格式改在共享基类 `NativeToolTestActivity` 里实现，
  QMESA 页面同步受益（无需改动 QMESA 自身代码）；大小选择和时长输入是
  Memtester 独有的，不影响 QMESA。

## 架构

### 1. `NativeToolTestActivity`（基类改动）

新增两个可覆写的扩展点，默认行为与现状一致（QMESA 不覆写即无变化）：

```java
/** 说明文字之后、Start/Stop 按钮之前插入的额外控件（大小选择、参数输入等）。默认不添加任何内容。 */
protected void buildExtraControls() { }

/** 本次测试的时长上限；null = 无限运行直到手动 Stop（默认）。每次点 Start 时重新读取。 */
protected Long testDurationMs() { return null; }
```

`buildUi()` 里在 `addInfo(description())` 之后、`statusText = addInfo(...)` 之前调用
`buildExtraControls()`。

新增 `formatElapsed(long seconds)` 私有 helper，返回 `hh:mm:ss`（`%02d:%02d:%02d`，
小时不封顶），替换 `tick()` 和 `onFinished()` 里原来的 `%02d:%02d`（mm:ss）拼接。

`start()`：
- 读取 `testDurationMs()`；非 null 时 `main.postDelayed(autoStopRunnable, durationMs)`
  安排到时自动停止；调用前先 `main.removeCallbacks(autoStopRunnable)` 清掉上一轮可能
  遗留的回调。
- 重置 `statusText` 的文字颜色为默认色（缓存在 `buildUi()` 里，通过
  `statusText.getCurrentTextColor()` 在设置任何颜色之前取一次）。

`onFinished()`：
- 先 `main.removeCallbacks(autoStopRunnable)`，防止测试提前手动结束/自然结束后
  该回调再次触发导致重复调用 `stop()`。
- FAILED 分支：`statusText.setTextColor(Color.RED)`。
- PASSED 分支（含到时自动停止且未见 FAILURE 的情况，因为自动停止直接复用
  `stop()`，`userStopRequested` 会被置位，走向与手动 Stop 完全一致的判定路径）：
  `statusText.setTextColor(Color.GREEN)`。
- 其余分支（启动失败 / 被系统信号杀死）：颜色重置为默认色，文案不变。
- 计时字符串统一改用 `formatElapsed(elapsed)`。

`autoStopRunnable`：`private final Runnable autoStopRunnable = this::stop;`
（直接复用现有 `stop()`，行为与用户手动点 Stop 完全一致，包括
`userStopRequested = true` 的置位，从而保证 §需求 中"等同手动 Stop"的约定）。

### 2. `MemtesterActivity`（子类改动）

新增字段：`RadioGroup sizeGroup`；`RadioButton quarterTotalRadio`, `avail80Radio`；
`EditText durationMinutesInput`。

```java
@Override
protected void buildExtraControls() {
    addSectionTitle("Test Size / 测试内存大小");
    quarterTotalRadio = new RadioButton(this);
    quarterTotalRadio.setId(View.generateViewId());
    quarterTotalRadio.setText("1/4 Total RAM / 1/4 总内存");
    avail80Radio = new RadioButton(this);
    avail80Radio.setId(View.generateViewId());
    avail80Radio.setText("80% Available RAM / 80% 可用内存");
    sizeGroup = new RadioGroup(this);
    sizeGroup.setOrientation(RadioGroup.VERTICAL);
    sizeGroup.addView(quarterTotalRadio);
    sizeGroup.addView(avail80Radio);
    sizeGroup.check(quarterTotalRadio.getId());
    addView(sizeGroup);

    addSectionTitle("Duration in minutes / 测试时长（分钟）");
    durationMinutesInput = new EditText(this);
    durationMinutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
    durationMinutesInput.setHint("Blank = run until stopped / 留空表示无限运行");
    addView(durationMinutesInput);
}

@Override
protected String[] buildArgs() {
    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
    am.getMemoryInfo(mi);
    long sizeBytes = avail80Radio.isChecked()
            ? (long) (mi.availMem * 0.8)
            : mi.totalMem / 4;
    long sizeMb = sizeBytes / (1024 * 1024);
    return new String[]{sizeMb + "M"};
}

@Override
protected Long testDurationMs() {
    String text = durationMinutesInput.getText().toString().trim();
    if (text.isEmpty()) return null;
    try {
        long minutes = Long.parseLong(text);
        return minutes > 0 ? minutes * 60_000L : null;
    } catch (NumberFormatException e) {
        return null;
    }
}
```

`description()` 文案更新为反映"容量和时长见下方选择"，去掉旧的"固定 1/4 总内存、
封顶 80% 可用、无限循环"措辞。

### 3. QMESA

不修改 `QmesaActivity`；仅通过基类改动自动获得颜色区分和 hh:mm:ss 计时。

## 数据流（新增/变化部分）

```
用户在 Memtester 页选择大小单选 + （可选）填写时长分钟数 → 点 Start
  → buildArgs() 按单选状态计算容量参数（不再取 min）
  → testDurationMs() 非 null 时安排 autoStopRunnable 延时执行
  → 运行中 tick() 每秒刷新，显示 hh:mm:ss

到达用户设定时长（若填写了）
  → autoStopRunnable 触发 → 等同调用 stop()（userStopRequested=true）
  → 与手动点 Stop 完全相同的收尾流程

进程结束（手动 Stop / 到时自动 Stop / 异常退出）
  → onFinished()：先清掉可能还挂着的 autoStopRunnable
  → 按现有判定逻辑（启动失败 / 被系统信号杀死 / FAILED / PASSED）设置文字与颜色
  → 计时统一用 hh:mm:ss
```

## 错误处理 / 边界情况

- 时长输入非法（非数字、0、负数、纯空白）→ 一律按"未填写"处理，等同无限运行，
  不弹错误提示（用户体验优先，字段本就是可选项）。
- 到时自动停止与手动停止复用同一个 `stop()` 方法，因此不需要新增状态区分逻辑，
  也自动继承现有的"被系统信号杀死 vs 用户主动停止"判定（§2026-07-07 设计文档
  中的修复 3）。
- `autoStopRunnable` 必须在 `start()`（新一轮开始前）和 `onFinished()`
  （测试结束时）都调用 `removeCallbacks`，避免：
  - 上一轮设置了 5 分钟自动停止，用户在 1 分钟时手动 Stop 又立刻重新 Start
    不填时长 → 如果不清理，4 分钟后遗留的回调会把新一轮意外掐断。
  - 测试提前失败/自然结束后，遗留的 autoStopRunnable 在测试已经不在运行时
    再次触发（调用 `stop()` 本身是幂等的，但仍属于不必要的悬挂回调，应清理）。

## 测试计划

沿用项目现状（无自动化测试基础设施），手动验证：

1. 打开 Memtester 页面，确认默认选中"1/4 总内存"，时长输入框为空、有提示文字。
2. 切换到"80% 可用内存"点 Start，确认日志/进程实际使用的容量与可用内存 80%
   接近（而不是原来的 `min` 逻辑）。
3. 时长输入框填 "1"，点 Start，确认约 1 分钟后测试自动停止，状态文字显示
   PASSED（绿色）+ hh:mm:ss 格式的已测试时长。
4. 时长输入框留空，点 Start 运行一段时间后手动点 Stop，确认行为与之前一致
   （PASSED/FAILED 判定不受影响），颜色和计时格式已更新。
5. 输入非法时长（如 "abc" 或 "0"），确认等同于无限运行，不报错。
6. 让 memtester 主动报出 FAILURE 关键字（如输入过大的容量触发失败，或人工在
   日志里确认判定逻辑），确认状态文字显示 FAILED（红色）。
7. 打开 QMESA 页面，确认没有新增大小/时长控件（其页面结构不变），但计时显示
   已经是 hh:mm:ss，且结束时 PASSED/FAILED 有颜色区分。
8. 填写时长后，在自动停止触发前按返回键退出页面，确认 `onStopTests()` 正常杀掉
   子进程，且没有因为遗留的 `autoStopRunnable` 回调在 Activity 已销毁后触发而
   导致崩溃或异常行为。
