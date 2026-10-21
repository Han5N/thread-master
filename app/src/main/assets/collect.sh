#!/system/bin/sh
# Thread Master 采集脚本
# 由 App 写入 /data/local/tmp/threadmaster_collect.sh 后以 root 常驻执行。
#
# 设计要点：
# 1. 整个采样周期只调用 shell 内建命令与少量外部命令，避免高频 fork 影响被观测对象；
# 2. 每轮以 B 开始、E 结束，字段用 TAB 分隔，App 端按行解析；
# 3. 目标包名与采样间隔写在 /data/local/tmp/threadmaster.cfg，App 修改配置无需重启会话。

CFG=/data/local/tmp/threadmaster.cfg
PIDFILE=/data/local/tmp/threadmaster.pid

PKG=""
INTERVAL=0.5
FPS_ENABLED=1
UID_STR=""
PIDS=""
PID=""
LAYER=""
FG_STATE=0
TICK=0
MINMAX_READY=0
GI_READY=0
HAS_TIMEOUT=0
command -v timeout >/dev/null 2>&1 && HAS_TIMEOUT=1
FPSFILE=/data/local/tmp/threadmaster_fps
FPS_PID=""


echo $$ > "$PIDFILE"

load_cfg() {
  [ -r "$CFG" ] || return 0
  while IFS='=' read -r k v; do
    case "$k" in
      PKG) PKG="$v" ;;
      INTERVAL) INTERVAL="$v" ;;
      FPS) FPS_ENABLED="$v" ;;
    esac
  done < "$CFG"
  [ -n "$INTERVAL" ] || INTERVAL=0.5
}

add_pid() {
  [ -n "$1" ] || return 0
  case " $PIDS " in
    *" $1 "*) return 0 ;;
  esac
  PIDS="$PIDS $1"
}

# 先取 pidof 的主进程，再用它的 uid 把所有同 uid 进程纳入。
# 现代游戏常见「主进程 + 渲染/资源子进程」，只看主进程会漏掉一半线程。
resolve_pids() {
  MAIN=""
  for p in $(pidof "$PKG" 2>/dev/null); do
    MAIN="$p"
    break
  done

  if [ -n "$MAIN" ] && [ -z "$UID_STR" ]; then
    while read -r k v rest; do
      case "$k" in
        Uid:) UID_STR="$v"; break ;;
      esac
    done < "/proc/$MAIN/status"
  fi

  PIDS=""
  [ -n "$MAIN" ] && add_pid "$MAIN"

  if [ -n "$UID_STR" ]; then
    # procps 只认小写字段名，toybox 两种都认，先小写再兜底
    ALL=$(ps -A -o pid,uid 2>/dev/null)
    if [ -z "$ALL" ]; then
      ALL=$(ps -A -o PID,UID 2>/dev/null)
    fi
    if [ -n "$ALL" ]; then
      for p in $(echo "$ALL" | while read -r a b; do
        [ "$b" = "$UID_STR" ] && echo "$a"
      done); do
        add_pid "$p"
      done
    fi
  fi

  set -- $PIDS
  PID=${1:-0}
}

emit_cpu() {
  while read -r name u n s i w irq sirq st rest; do
    case "$name" in
      cpu*) printf 'C\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$u" "$n" "$s" "$i" "$w" "$irq" "$sirq" "$st" ;;
      *) break ;;
    esac
  done < /proc/stat
}

# ctxt / intr / 运行队列都藏 /proc/stat 后半段，一轮读完不额外 fork
emit_kernel_counters() {
  while read -r key v rest; do
    case "$key" in
      ctxt) CTXT=$v ;;
      intr) INTR=$v ;;
      procs_running) RUNQ=$v ;;
      procs_blocked) BLKQ=$v ;;
      btime) break ;;
    esac
  done < /proc/stat
  printf 'CT\t%s\t%s\t%s\t%s\n' "${CTXT:-0}" "${INTR:-0}" "${RUNQ:-0}" "${BLKQ:-0}"
}

emit_load() {
  if read -r l1 l5 l15 rest < /proc/loadavg; then
    printf 'LD\t%s\t%s\t%s\n' "$l1" "$l5" "$l15"
  fi
}

emit_freq() {
  for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq; do
    [ -r "$f" ] || continue
    c=${f#/sys/devices/system/cpu/cpu}
    c=${c%%/*}
    if read -r v < "$f"; then
      printf 'F\t%s\t%s\n' "$c" "$v"
    fi
  done
}

emit_freq_limits() {
  for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq; do
    [ -r "$f" ] || continue
    c=${f#/sys/devices/system/cpu/cpu}
    c=${c%%/*}
    d=${f%scaling_max_freq}
    min=0
    max=0
    if read -r v < "$f"; then max="$v"; fi
    if read -r v < "${d}scaling_min_freq"; then min="$v"; fi
    printf 'L\t%s\t%s\t%s\n' "$c" "$min" "$max"
  done
}

emit_mem() {
  while read -r k v rest; do
    case "$k" in
      MemTotal:) printf 'M\tt\t%s\n' "$v" ;;
      MemAvailable:) printf 'M\ta\t%s\n' "$v" ;;
      SwapTotal:) printf 'M\tst\t%s\n' "$v" ;;
      SwapFree:) printf 'M\tsf\t%s\n' "$v" ;;
    esac
  done < /proc/meminfo
}

# 骁龙 GPU：不同平台节点位置不同，逐级尝试
emit_gpu() {
  gcur=0
  gbusy1=0
  gbusy2=0
  gtemp=""
  if [ -r /sys/class/kgsl/kgsl-3d0/gpuclk ]; then
    read -r gcur < /sys/class/kgsl/kgsl-3d0/gpuclk
  elif [ -r /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq ]; then
    read -r gcur < /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq
  fi
  if [ -r /sys/class/kgsl/kgsl-3d0/gpubusy ]; then
    read -r gbusy1 gbusy2 < /sys/class/kgsl/kgsl-3d0/gpubusy
  fi
  if [ -r /sys/class/kgsl/kgsl-3d0/temp ]; then
    read -r gtemp < /sys/class/kgsl/kgsl-3d0/temp
  fi
  printf 'G\t%s\t%s\t%s\t%s\n' "$gcur" "$gbusy1" "$gbusy2" "$gtemp"
}

# GPU 型号与频点不常变，每 20 轮读一次
emit_gpu_info() {
  [ "$GI_READY" = "1" ] && return 0
  model=""
  maxkhz=0
  freqs=""
  if read -r model < /sys/class/kgsl/kgsl-3d0/gpu_model 2>/dev/null; then :; fi
  if [ -z "$model" ]; then
    if read -r model < /sys/class/kgsl/kgsl-3d0/devfreq/device/of_node/compatible 2>/dev/null; then :; fi
  fi
  if read -r maxkhz < /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null; then :; fi
  if [ -z "$maxkhz" ] || [ "$maxkhz" = "0" ]; then
    if read -r maxkhz < /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null; then :; fi
  fi
  if read -r freqs < /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null; then :; fi
  printf 'GI\t%s\t%s\t%s\n' "$model" "${maxkhz:-0}" "$freqs"
  GI_READY=1
}

emit_battery() {
  for base in /sys/class/power_supply/battery /sys/class/power_supply/bms; do
    [ -d "$base" ] || continue
    cur=""
    vol=""
    lvl=""
    tmp=""
    pwr=""
    st=""
    [ -r "$base/current_now" ] && read -r cur < "$base/current_now"
    [ -r "$base/voltage_now" ] && read -r vol < "$base/voltage_now"
    [ -r "$base/capacity" ] && read -r lvl < "$base/capacity"
    [ -r "$base/temp" ] && read -r tmp < "$base/temp"
    [ -r "$base/power_now" ] && read -r pwr < "$base/power_now"
    [ -r "$base/status" ] && read -r st < "$base/status"
    printf 'BAT\t%s\t%s\t%s\t%s\n' "$cur" "$vol" "$lvl" "$tmp"
    printf 'BP\t%s\t%s\n' "$pwr" "$st"
    break
  done
}

emit_net() {
  rx=0
  tx=0
  while read -r line; do
    case "$line" in
      *:*) ;;
      *) continue ;;
    esac
    set -- $line
    case "$1" in
      lo:*) continue ;;
    esac
    rx=$((rx + ${2:-0}))
    tx=$((tx + ${10:-0}))
  done < /proc/net/dev
  printf 'N\t%s\t%s\n' "$rx" "$tx"
}

emit_temp() {
  for z in /sys/class/thermal/thermal_zone*; do
    [ -r "$z/temp" ] || continue
    t=""
    v=""
    read -r t < "$z/type" 2>/dev/null
    read -r v < "$z/temp" 2>/dev/null
    if [ -n "$t" ] && [ -n "$v" ]; then
      printf 'T\t%s\t%s\n' "$t" "$v"
    fi
  done
}

emit_zram() {
  used=0
  total=0
  if [ -r /sys/block/zram0/mm_stat ]; then
    read -r used rest < /sys/block/zram0/mm_stat 2>/dev/null
  fi
  if [ -r /sys/block/zram0/disksize ]; then
    read -r total < /sys/block/zram0/disksize 2>/dev/null
  fi
  printf 'ZR\t%s\t%s\n' "$((used / 1024))" "$((total / 1024))"
}

# 前台判断：只关心当前 resumed 的 Activity 是不是目标应用。
# grep -m1 会提前退出，dumpsys 收到 SIGPIPE 停止输出，开销比整份 dump 小很多。
emit_foreground() {
  [ -n "$PKG" ] || { printf 'FG\t0\n'; return 0; }
  if [ $((TICK % 6)) -eq 0 ] || [ "$TICK" -lt 6 ]; then
    if [ "$HAS_TIMEOUT" = "1" ]; then
      LINE=$(timeout 3 dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity")
    else
      LINE=$(dumpsys activity activities 2>/dev/null | grep -m1 -E "topResumedActivity|mResumedActivity")
    fi
    case "$LINE" in
      *"$PKG/"*) FG_STATE=1 ;;
      *) FG_STATE=0 ;;
    esac
  fi
  printf 'FG\t%s\n' "$FG_STATE"
}

emit_proc() {
  for p in $PIDS; do
    [ -r "/proc/$p/stat" ] || continue
    read -r pstat < "/proc/$p/stat" 2>/dev/null || continue
    rest=${pstat#*\(}
    tail=${rest##*\) }
    set -- $tail
    utime=${12:-0}
    stime=${13:-0}
    nrthr=${18:-0}
    rss=0
    while read -r k v u; do
      case "$k" in
        VmRSS:) rss="$v"; break ;;
      esac
    done < "/proc/$p/status"
    pior=0
    piow=0
    if [ -r "/proc/$p/io" ]; then
      while read -r k v; do
        case "$k" in
          read_bytes:) pior="$v" ;;
          write_bytes:) piow="$v" ;;
        esac
      done < "/proc/$p/io"
    fi
    printf 'P\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$p" "$utime" "$stime" "$rss" "$nrthr" "$pior" "$piow"
  done
}

emit_threads() {
  for p in $PIDS; do
    [ -d "/proc/$p/task" ] || continue
    for d in /proc/$p/task/*; do
      [ -r "$d/stat" ] || continue
      t=${d##*/}
      if IFS= read -r st < "$d/stat"; then
        rest=${st#*\(}
        comm=${rest%\) *}
        tail=${rest##*\) }
        set -- $tail
        rn=0
        wn=0
        if read -r rn wn tsl < "$d/schedstat" 2>/dev/null; then :; fi
        printf 'THR\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
          "$p" "$t" "$comm" "${12:-0}" "${13:-0}" "${37:-0}" "${16:-0}" "${1:-S}" "${rn:-0}" "${wn:-0}"
      fi
    done
  done
}

# 骁龙 DRM 驱动直接导出实测帧率，纯 sysfs 读取，比 dumpsys 低两个数量级
# measured_fps 的读取放在独立子进程里。
#
# 某些机型在显示管线繁忙时读这个节点会进入不可中断睡眠（D 状态），
# 连 SIGKILL 都不生效，timeout 也救不回来。主循环一旦卡在这里，
# 块结束标记 E 就永远发不出去，采集看起来就像"没有输出"。
# 所以主循环只读缓存文件，最坏情况也只是帧率不更新，不会拖垮整条链路。
fps_worker() {
  while :; do
    for f in \
      /sys/class/drm/sde-crtc-0/measured_fps \
      /sys/class/drm/card0/sde-crtc-0/measured_fps \
      /sys/class/drm/card0/sde_crtc_fps \
      /sys/class/graphics/fb0/measured_fps; do
      [ -r "$f" ] || continue
      if [ "$HAS_TIMEOUT" = "1" ]; then
        v=$(timeout 2 cat "$f" 2>/dev/null)
      else
        v=$(cat "$f" 2>/dev/null)
      fi
      [ -n "$v" ] || continue
      case "$v" in
        *"not available"*) continue ;;
      esac
      # 先写临时文件再 mv，避免主循环读到半行
      printf '%s\n' "$v" > "$FPSFILE.tmp" 2>/dev/null && mv "$FPSFILE.tmp" "$FPSFILE" 2>/dev/null
      break
    done
    sleep 2
  done
}

emit_drm_fps() {
  [ "$FPS_ENABLED" = "1" ] || return 0
  [ -r "$FPSFILE" ] || return 0
  v=$(cat "$FPSFILE" 2>/dev/null)
  [ -n "$v" ] || return 0
  printf 'FPSD\t%s\t%s\n' "$FPSFILE" "$v"
}

emit_fps() {
  [ "$FPS_ENABLED" = "1" ] || return 0
  [ -n "$PKG" ] || return 0
  if [ -z "$LAYER" ] || [ $((TICK % 20)) -eq 0 ]; then
    if [ "$HAS_TIMEOUT" = "1" ]; then
      LIST=$(timeout 3 dumpsys SurfaceFlinger --list 2>/dev/null | grep -F "$PKG")
    else
      LIST=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F "$PKG")
    fi
    LAYER=$(printf '%s\n' "$LIST" | grep -E "SurfaceView|BLAST" | tail -n 1)
    if [ -z "$LAYER" ]; then
      LAYER=$(printf '%s\n' "$LIST" | tail -n 1)
    fi
    printf 'FPSL\t%s\n' "$LAYER"
  fi
  if [ -n "$LAYER" ] && [ $((TICK % 4)) -eq 0 ]; then
    printf 'FPSB\n'
    if [ "$HAS_TIMEOUT" = "1" ]; then
      timeout 3 dumpsys SurfaceFlinger --latency "$LAYER" 2>/dev/null | head -n 130
    else
      dumpsys SurfaceFlinger --latency "$LAYER" 2>/dev/null | head -n 130
    fi
    printf 'FPSE\n'
  fi
}

cleanup() {
  [ -n "$FPS_PID" ] && kill "$FPS_PID" 2>/dev/null
  rm -f "$FPSFILE" "$FPSFILE.tmp"
}
trap cleanup TERM INT

load_cfg
resolve_pids

if [ "$FPS_ENABLED" = "1" ]; then
  fps_worker >/dev/null 2>&1 &
  FPS_PID=$!
fi

while :; do
  load_cfg

  if [ $((TICK % 4)) -eq 0 ] || [ -z "$PIDS" ]; then
    resolve_pids
  fi
  if [ $((TICK % 20)) -eq 0 ]; then
    GI_READY=0
  fi

  printf 'B\n'
  emit_cpu
  emit_kernel_counters
  emit_load
  emit_freq
  if [ "$MINMAX_READY" = "0" ]; then
    emit_freq_limits
    MINMAX_READY=1
  fi
  emit_mem
  emit_zram
  emit_gpu
  emit_gpu_info
  emit_battery
  emit_net
  emit_temp
  emit_foreground
  emit_proc
  emit_threads
  emit_drm_fps
  emit_fps
  printf 'E\n'

  TICK=$((TICK + 1))
  sleep "$INTERVAL"
done
