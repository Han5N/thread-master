# Thread Master

[![Build APK](https://github.com/Han5N/thread-master/actions/workflows/build.yml/badge.svg)](https://github.com/Han5N/thread-master/actions/workflows/build.yml)

Root 权限下的 Android 性能监控与记录工具。它不只告诉你"整机 CPU 多少"，而是锁定一个应用，
把它所有进程、所有线程的占用摊开记录下来——用来回答"这个游戏到底是谁在吃 CPU"。

**[下载最新 APK](https://github.com/Han5N/thread-master/releases/latest)** · 当前版本 0.2.0，约 1.4 MB

APK 由 GitHub Actions 构建并用仓库私钥签名，证书指纹（SHA-256）：
`8A:DF:0B:98:29:A9:45:6A:66:BF:DF:E8:22:74:6C:7C:58:85:E2:2C:00:8D:10:56:EB:FF:10:41:E6:93:BD:14`

## 能做什么

- **整机面板**：CPU 总占用、每核占用与实时频率、GPU 型号/频率/占用/温度、屏幕刷新率、内存与 ZRAM、
  电池功率与温度、网络吞吐、load average、运行队列、上下文切换与中断、目标进程磁盘 IO
- **应用锁定**：选定包名后自动解析它全部同 uid 进程，再枚举每个进程下的全部线程
- **线程级曲线**：线程的 CPU 占用和 runnable 等待时间，按 2 秒粒度落库，可单独回看任意一条线程
- **等应用进前台再记录**：点开始后先待机，目标应用到前台才写数据，切后台自动暂停
- **悬浮窗 HUD**：可拖动，单击展开看每核占用、GPU、内存、调度负载和线程 Top5，双击回主界面
- **历史记录**：会话列表 + 多指标曲线 + 线程排行，长按删除
- **黑白双主题**：Miuix 组件体系，浅色/深色/跟随系统三档

## 为什么非要 root

`/proc/<pid>/task/<tid>/schedstat`、`/sys/class/kgsl/*`、`dumpsys SurfaceFlinger --latency`、
`dumpsys activity activities` 这些在普通应用沙箱里读不到或受限。没有 root 就只能拿到自己进程的数据。

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
| `CT` | ctxt / intr / procs_running / procs_blocked |
| `LD` | `/proc/loadavg` |
| `F` / `L` | 每核当前频率 / 频率上下限 |
| `M` | MemTotal、MemAvailable、Swap |
| `ZR` | zram0 的已用与总大小 |
| `G` / `GI` | GPU 当前频率、gpubusy、温度 / 型号与频点 |
| `BAT` / `BP` | 电流、电压、电量、温度 / 功率与充电状态 |
| `N` | 网络累计收发字节 |
| `T` | 每个 thermal_zone 的 type 与温度 |
| `FG` | 目标应用是否在前台 |
| `P` | 目标进程的 utime/stime/RSS/线程数/磁盘 IO（多进程多行） |
| `THR` | 每个线程的 tid、comm、utime/stime、所在核、优先级、状态、schedstat 运行/等待纳秒 |
| `FPSL` | 匹配到的 SurfaceFlinger layer 名（用于诊断取不到帧率） |
| `FPSB`…`FPSE` | `dumpsys SurfaceFlinger --latency` 的原始输出 |

App 侧是状态机解析，相邻两帧做差得到占用率。latency 输出的首行是刷新周期，顺带换算成屏幕刷新率。

### 口径说明

- **CPU 占用按单核计**：一条线程跑满一个核心就是 100%，多线程可以超过 100%，不除以核心数。
  这和 `top -H` 的语义一致，也便于判断"是不是有一个线程把大核占满了"。
- **线程占用优先用 `schedstat`**：直接拿纳秒级运行时间除以窗口，不依赖 `HZ`。
  内核没开 `CONFIG_SCHEDSTATS` 时自动回退到 `utime+stime` 的 jiffies 差分。
- **等待占比**：`schedstat` 第二字段是线程在 runqueue 上排队的时间。它比 CPU 占用更能解释卡顿。
- **单位归一化**：不同平台把电流给成 µA 或 mA、温度给成毫度或 0.1℃，解析器按阈值统一换算。
- **缺失值**：拿不到的指标写 `-1` 入库，查询时用 `CASE WHEN` 排除，不会被算进均值。
- **前台判定**：`dumpsys activity activities` 里取 `topResumedActivity`，用 `包名/` 前缀匹配，
  避免 `com.foo` 误中 `com.foobar`。

## 构建

需要 JDK 17、Android SDK **platform 37**（`platforms;android-37.0`）与 `build-tools;37.0.0`。

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

正式包 `./gradlew :app:assembleRelease` 会开启 R8 与资源压缩（debug 21 MB → release 1.4 MB）。
签名密钥通过环境变量注入，仓库里不含任何密钥材料：

```bash
export TM_KEYSTORE=/path/to/threadmaster.jks
export TM_STORE_PASSWORD=...
export TM_KEY_ALIAS=threadmaster
export TM_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

没有提供 `TM_KEYSTORE` 时会自动退回 debug 签名，保证任何人 clone 下来都能构建。
CI 配置在 `.github/workflows/build.yml`，密钥存放在仓库 Secrets 中。

## 技术栈

- Kotlin 2.4.21 / AGP 9.4.1 / Gradle 9.8.2 / compileSdk 37 / minSdk 26
- Jetpack Compose（BOM 2026.09）+ [Miuix](https://github.com/compose-miuix-ui/miuix) 0.9.4
- Room 2.8.5（KSP）+ Kotlin Coroutines / StateFlow
- 悬浮窗用原生 Canvas 自绘，避免高频重绘时的重组开销
- 采集端是纯 POSIX shell，不引入 JNI

## 使用

1. 安装后打开，先授予通知权限
2. 点「选择」挑一个应用，之后会进入「等待应用进入前台」
3. 把目标应用切到前台，状态变成「记录中」后开始写数据
4. 在 Magisk / KernelSU 里把 root 授权设为「记住」
5. 想边玩边看，去设置里打开「显示 HUD」
6. 停止后到「历史」里回看曲线和线程排行

## 已知限制

- **内核节点差异**：GPU 走高通 kgsl 路径，联发科平台大概率读不到，会显示 `--`。
  thermal zone 命名各家不同，CPU 温度靠关键字匹配。
- **帧率**：依赖 `dumpsys SurfaceFlinger --latency` 和 layer 名匹配包名。部分游戏用独立 Surface，
  layer 名不含包名时会取不到；HUD 里会显示 `--`，界面上能看到匹配到的 layer 名以便排查。
- **多进程**：同 uid 进程每 2 秒刷新一次集合，应用刚启动的瞬间可能少抓一两个子进程。
- **前台判定**：每 3 秒轮询一次 `dumpsys activity activities`，进入/退出应用的判定有最多 3 秒延迟。
- **数据量**：线程明细每 2 秒一轮，50 线程的会话大约 9 万行/小时。长时间挂机建议调大采样间隔。

## 目录结构

```
app/src/main/
├── assets/collect.sh                   # 常驻采集脚本
└── kotlin/com/threadmaster/
    ├── collector/
    │   ├── RootShell.kt                # su 会话、脚本下发、配置写入
    │   ├── SampleParser.kt             # 协议解析 + 差分计算
    │   ├── MonitorEngine.kt            # 会话生命周期、前后台阶段、数据分发
    │   └── Models.kt
    ├── data/                           # Room 实体、DAO、迁移、缓冲写入
    ├── hud/                            # 原生 Canvas 悬浮窗（紧凑/展开两态）
    ├── service/MonitorService.kt       # 前台服务
    └── ui/                             # Miuix 界面
```
