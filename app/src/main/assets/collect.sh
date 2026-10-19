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
TICK=0
MINMAX_READY=0

echo $$ > "$PIDFILE"

# 读取配置。文件形如：
#   PKG=com.example.game
#   INTERVAL=0.5
#   FPS=1
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

# 解析目标进程。
#
# 先取 pidof 的主进程，再用它的 uid 把所有同 uid 进程一并纳入：
# 现代游戏常见「主进程 + 渲染/资源子进程」的结构，只看主进程会漏掉一半线程。
# 同 uid 进程集合变化很慢，每 4 个 tick（约 2 秒）刷新一次就够。
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

add_pid() {
  [ -n "$1" ] || return 0
  case " $PIDS " in
    *" $1 "*) return 0 ;;
  esac
  PIDS="$PIDS $1"
}

emit_cpu() {
  while read -r name u n s i w irq sirq st rest; do
    case "$name" in
      cpu*) printf 'C\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$name" "$u" "$n" "$s" "$i" "$w" "$irq" "$sirq" "$st" ;;
      *) break ;;
    esac
  done < /proc/stat
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

emit_battery() {
  for base in /sys/class/power_supply/battery /sys/class/power_supply/bms; do
    [ -d "$base" ] || continue
    cur=""
    vol=""
    lvl=""
    tmp=""
    [ -r "$base/current_now" ] && read -r cur < "$base/current_now"
    [ -r "$base/voltage_now" ] && read -r vol < "$base/voltage_now"
    [ -r "$base/capacity" ] && read -r lvl < "$base/capacity"
    [ -r "$base/temp" ] && read -r tmp < "$base/temp"
    printf 'BAT\t%s\t%s\t%s\t%s\n' "$cur" "$vol" "$lvl" "$tmp"
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
    printf 'P\t%s\t%s\t%s\t%s\t%s\n' "$p" "$utime" "$stime" "$rss" "$nrthr"
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

emit_fps() {
  [ "$FPS_ENABLED" = "1" ] || return 0
  [ -n "$PKG" ] || return 0
  if [ $((TICK % 8)) -eq 0 ]; then
    LAYER=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F "$PKG" | tail -n 1)
  fi
  if [ -n "$LAYER" ] && [ $((TICK % 4)) -eq 0 ]; then
    printf 'FPSB\n'
    dumpsys SurfaceFlinger --latency "$LAYER" 2>/dev/null | head -n 130
    printf 'FPSE\n'
  fi
}

load_cfg
resolve_pids

while :; do
  load_cfg

  if [ $((TICK % 4)) -eq 0 ] || [ -z "$PIDS" ]; then
    resolve_pids
  fi

  printf 'B\n'

  emit_cpu
  emit_freq
  if [ "$MINMAX_READY" = "0" ]; then
    emit_freq_limits
    MINMAX_READY=1
  fi
  emit_mem
  emit_gpu
  emit_battery
  emit_net
  emit_temp
  emit_proc
  emit_threads
  emit_fps
  printf 'E\n'

  TICK=$((TICK + 1))
  sleep "$INTERVAL"
done
