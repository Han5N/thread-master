# Thread Master

Root 权限下的 Android 性能监控与记录工具。它不只告诉你"整机 CPU 多少"，而是锁定一个应用，
把它所有进程、所有线程的占用摊开记录下来——用来回答"这个游戏到底是谁在吃 CPU"。

## 能做什么

- **整机面板**：CPU 总占用与每核实时频率、内存与 Swap、GPU 频率与占用、温度、帧率、电池电流、网络吞吐
- **应用锁定**：选定包名后自动解析它全部同 uid 进程，再枚举每个进程下的全部线程
- **线程级曲线**：线程的 CPU 占用和 runnable 等待时间，按 2 秒粒度落库，可单独回看任意一条线程
- **悬浮窗 HUD**：可拖动，常驻游戏上层，显示整机/应用占用、每核频率条和最近 45 秒曲线
- **历史记录**：会话列表 + 多指标曲线 + 线程排行，长按删除

## 为什么非要 root

`/proc/<pid>/task/<tid>/schedstat`、`/sys/class/kgsl/*`、`dumpsys SurfaceFlinger --latency`
这些节点在普通应用沙箱里读不到。没有 root 就只能拿到自己进程的数据，做出来的东西没有意义。

## 工作原理

### 一条常驻会话，不是每秒钟调一次 su

`su` 的单次调用开销在几十毫秒量级，还带授权 IPC。如果按采样频率去 fork 它，
监控器本身就会成为被观测对象的一部分，数据直接失真。

所以启动时只做三件事：

1. 用 base64 把 `assets/collect.sh` 写进 `/data/local/tmp/`
2. 写一份配置（包名、间隔、是否采 FPS）到 `/data/local/tmp/threadmaster.cfg`
3. `su -c "exec sh /data/local/tmp/threadmaster_collect.sh"` 拉起一条长期会话

之后所有采样都在这条会话里循环完成。改目标应用只需重写配置文件，脚本下一轮自己重读，
不必重新授权。停止时按 pid 文件精确 kill，不留残余 sh。

### 输出协议

脚本每个 tick 输出一个块，以 `B` 开始、`E` 结束，字段用 TAB 分隔：

| 前缀 | 内容 |
| --- | --- |
| `C` | `/proc/stat` 的 cpu 累计 jiffies |
| `F` / `L` | 每核当前频率 / 频率上下限 |
| `M` | MemTotal、MemAvailable、Swap |
| `G` | GPU 当前频率、gpubusy 的 busy/total、GPU 温度 |
| `BAT` | 电流、电压、电量、温度 |
| `N` | 网络累计收发字节 |
| `T` | 每个 thermal_zone 的 type 与温度 |
| `P` | 目标进程的 utime/stime/RSS/线程数（多进程时多行） |
| `THR` | 每个线程的 tid、comm、utime/stime、所在核、优先级、状态、schedstat 运行/等待纳秒 |
| `FPSB`…`FPSE` | `dumpsys SurfaceFlinger --latency` 的原始输出 |

App 侧是状态机解析，相邻两帧做差得到占用率。

### 口径说明

- **CPU 占用按单核计**：一条线程跑满一个核心就是 100%，多线程可以超过 100%，不除以核心数。
  这和 `top -H` 的语义一致，也便于判断"是不是有一个线程把大核占满了"。
- **线程占用优先用 `schedstat`**：直接拿纳秒级运行时间除以窗口，不依赖 `HZ`。
  内核没开 `CONFIG_SCHEDSTATS` 时自动回退到 `utime+stime` 的 jiffies 差分。
- **等待占比**：`schedstat` 第二字段是线程在 runqueue 上排队的时间。它比 CPU 占用更能解释卡顿——
  一个线程等待 30% 时间，说明它想跑但排不上队。
- **单位归一化**：不同平台把电流给成 µA 或 mA、温度给成毫度或 0.1℃，解析器按阈值统一换算成 mA 与 ℃。
- **缺失值**：拿不到的指标写 `-1` 入库，查询时用 `CASE WHEN` 排除，不会被算进均值。

## 构建

```bash
# 需要 JDK 17、Android SDK（platform 35 / build-tools 35.0.0）
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

## 使用

1. 安装后打开，先授予通知权限（否则前台服务通知不显示，但服务照跑）
2. 点「选择」，挑一个应用，会自动开始记录并弹出 root 授权
3. 在 Magisk / KernelSU 里把授权设为「记住」，以后不再打断
4. 想边玩边看，去设置里打开「显示 HUD」，首次会跳转「显示在其他应用上层」授权页
5. 停止后到「历史」里回看曲线和线程排行

## 已知限制

- **内核节点差异**：GPU 走的是高通 kgsl 路径，联发科平台大概率读不到，会显示 `--`。
  thermal zone 的命名各家不同，CPU 温度靠关键字匹配（cpu/soc/cluster/tsens），匹配不到也显示 `--`。
- **帧率**：依赖 `dumpsys SurfaceFlinger --latency` 和 layer 名匹配包名。部分游戏用独立 Surface，
  layer 名不含包名时会取不到。
- **多进程**：同 uid 进程在每 2 秒刷新一次集合，应用刚启动的瞬间可能少抓一两个子进程。
- **`ps` 兼容性**：同 uid 进程枚举依赖 toybox `ps -A -o PID,UID`，Android 8 以下没有验证过。
- **数据量**：线程明细每 2 秒一轮，50 线程的会话大约 9 万行/小时。长时间挂机建议调大采样间隔。

## 目录结构

```
app/src/main/
├── assets/collect.sh                   # 常驻采集脚本
└── kotlin/com/threadmaster/
    ├── collector/
    │   ├── RootShell.kt                # su 会话、脚本下发、配置写入
    │   ├── SampleParser.kt             # 协议解析 + 差分计算
    │   ├── MonitorEngine.kt            # 会话生命周期、数据分发
    │   └── Models.kt
    ├── data/                           # Room 实体、DAO、缓冲写入
    ├── hud/                            # 原生 Canvas 悬浮窗
    ├── service/MonitorService.kt       # 前台服务
    └── ui/                             # Compose 界面
```
