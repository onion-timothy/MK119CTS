#!/bin/zsh
#
# MK119 앱 기동·정지 헬퍼.
#
#   tools/app.sh start | stop | restart | status | log
#
# VS Code 의 디버그 설정 "Spring Boot (MK119)" 는 VS Code 가 JVM 을 띄우는 방식이라
# 재시작을 VS Code 밖에서 할 수 없다. 이 스크립트는 JDWP 를 '서버 모드' 로 열어
# 앱을 셸에서 띄운다. 디버거는 "Attach (MK119, port 5005)" 로 붙였다 뗐다 하면 되고,
# 앱 수명과 분리되므로 재시작해도 VS Code 쪽에서 할 일이 없다.
#
# 콘솔 출력은 logs/console.log 에 쌓인다(logs/ 는 gitignore). 터미널에서 보려면
# `tools/app.sh log` 또는 VS Code 의 app-console 태스크.
#
set -u
HERE=${0:A:h}
MOD=$HERE/../MK119CommunicationTestSuite
LOG=$MOD/logs/console.log
PORT=8082
DBG=5005
# restart 가 도는 동안만 존재하는 표식. 아래 stop-on-debug-exit 설명 참고.
# 내용은 "<restart 프로세스 PID> <시작 epoch>".
FLAG=$MOD/logs/.restarting
# `log` 로 띄운 tail 들의 PID. Stop Debugging 때 같이 내리려고 기록해 둔다
TAILS=$MOD/logs/.tail-pids
# 앱 JVM 을 알아보는 표식. build.gradle 이 bootRun 에 붙이는 jdwp 인자다
JDWP="suspend=n,address=$DBG"
# gradle 쪽(gradlew 래퍼 + GradleWrapperMain). 앱이 아직 포트를 못 잡았을 때 이것만 남는다.
# gradle '데몬' 은 이 문자열이 없으므로 걸리지 않는다 — 데몬은 건드리면 안 된다
GRADLE_SIG="bootRun -PdebugPort=$DBG"

# 콘솔 로그에 한 줄 남긴다. 무슨 일이 있었는지 나중에 추적할 수 있게 —
# VS Code 태스크는 reveal:silent + close:true 라 화면에 아무 것도 남지 않는다
note() {
  mkdir -p "$(dirname "$LOG")" 2>/dev/null
  printf '========== %s · %s ==========\n' "$(date '+%F %T')" "$1" >> "$LOG" 2>/dev/null
  return 0
}

# 포트를 '듣고 있는' 프로세스만 고른다. -sTCP:LISTEN 을 빼면 그 포트에 접속한
# 클라이언트(브라우저 탭 등)까지 잡혀서 엉뚱한 프로그램을 죽이게 된다.
listeners() { lsof -ti tcp:$PORT -sTCP:LISTEN 2>/dev/null; }

# pgrep -f 는 '명령줄에 그 문자열이 있는' 아무 프로세스나 잡는다 — 그 문자열을 인자로
# 적은 셸이나 grep 까지 걸린다(실제로 테스트 중에 엉뚱한 셸을 죽였다). 그래서 실행 파일
# 이름까지 확인한다. $1 = pgrep 패턴, $2 = 실행 파일 이름에 요구되는 정규식
pgrep_prog() {
  local pat=$1 prog=$2 p
  local -a out
  for p in ${=$(pgrep -f "$pat" 2>/dev/null)}; do
    [ "$p" = "$$" ] && continue
    [ "$p" = "$PPID" ] && continue
    ps -o comm= -p "$p" 2>/dev/null | grep -qE "$prog" && out+=$p
  done
  print -l -- "${out[@]}"
}

# 앱 JVM. 포트 바인딩 전(기동 중)이나 기동 실패 상태에서도 잡히도록
# 8082 리스너·5005(JDWP) 리스너·jdwp 인자를 가진 java 프로세스를 모두 모은다
app_pids() {
  {
    lsof -ti tcp:$PORT -sTCP:LISTEN 2>/dev/null
    lsof -ti tcp:$DBG  -sTCP:LISTEN 2>/dev/null
    pgrep_prog "$JDWP" "/java$"
  } | sort -un
}

gradle_pids() { pgrep_prog "$GRADLE_SIG" "/java$" | sort -un; }

# 주어진 PID 중 아직 살아 있는 것만
alive() {
  local p; local -a out
  for p in "$@"; do kill -0 "$p" 2>/dev/null && out+=$p; done
  print -r -- "${out[@]}"
}

stop() {
  local -a pids gpids
  # zsh 는 따옴표 없는 변수도 단어 분할을 하지 않는다. ${=...} 로 명시적으로 쪼갠다
  pids=(${=$(app_pids)})
  gpids=(${=$(gradle_pids)})

  if (( ${#pids} == 0 && ${#gpids} == 0 )); then
    echo "앱이 떠 있지 않다 (포트 $PORT)"
    note "stop · 내려갈 것이 없었다"
    return 0
  fi

  if (( ${#pids} > 0 )); then
    echo "종료: ${pids[*]}"
    note "stop · 앱 종료 요청 (PID ${pids[*]})"
    kill "${pids[@]}" 2>/dev/null
    # SIGTERM 유예 10초. 더 기다려 봐야 사용자가 먼저 손으로 kill 하게 된다
    local i
    for i in {1..10}; do
      pids=(${=$(alive "${pids[@]}")})
      (( ${#pids} == 0 )) && break
      sleep 1
    done
    if (( ${#pids} > 0 )); then
      echo "강제 종료: ${pids[*]}"
      note "stop · 10초 안에 안 죽어 강제 종료(SIGKILL) (PID ${pids[*]})"
      kill -9 "${pids[@]}" 2>/dev/null
      for i in {1..5}; do
        pids=(${=$(alive "${pids[@]}")})
        (( ${#pids} == 0 )) && break
        sleep 1
      done
    fi
  fi

  # 앱 JVM 이 죽으면 gradle 래퍼도 뒤따라 끝나지만, 빌드 단계에서 멈춰 있거나
  # 기동에 실패한 경우엔 그대로 남는다. 남아 있으면 같이 내린다
  gpids=(${=$(alive ${=$(gradle_pids)})})
  if (( ${#gpids} > 0 )); then
    kill "${gpids[@]}" 2>/dev/null
    local i
    for i in {1..5}; do
      gpids=(${=$(alive "${gpids[@]}")})
      (( ${#gpids} == 0 )) && break
      sleep 1
    done
    (( ${#gpids} > 0 )) && kill -9 "${gpids[@]}" 2>/dev/null
  fi

  # gradle 이 종료 메시지를 흘릴 틈을 준다. 그래야 다음 기동 구분선 위에 남는다
  sleep 1
  local -a left; left=(${=$(alive ${=$(app_pids)})})
  if (( ${#left} > 0 )); then
    echo "경고: 아직 살아 있는 프로세스 ${left[*]}"
    note "stop · 실패 — 아직 살아 있다 (PID ${left[*]})"
  else
    printf '========== stopped %s · 위의 "BUILD FAILED ... 143" 은 정상 종료(SIGTERM) 신호다 ==========\n' \
           "$(date '+%F %T')" >> "$LOG" 2>/dev/null
  fi
  return 0   # 마지막 판정의 종료코드가 새어 나가 restart 의 && 를 끊는다
}

# 기동 완료(포트 응답)를 기다린다. $1 = 같이 지켜볼 PID(없으면 0)
wait_up() {
  local watch=${1:-0} from=$2 i
  for i in {1..180}; do
    if curl -s -o /dev/null --max-time 2 "http://localhost:$PORT/"; then
      echo "기동 완료 (${i}s) · 앱 http://localhost:$PORT · 디버그 $DBG 대기"
      return 0
    fi
    if (( watch > 0 )) && ! kill -0 $watch 2>/dev/null; then
      echo "기동 실패 — gradle 이 끝나 버렸다:"; tail -n "+$from" "$LOG" | tail -25; return 1
    fi
    sleep 1
  done
  echo "180s 안에 안 뜸:"; tail -n "+$from" "$LOG" | tail -25; return 1
}

start() {
  if [ -n "$(listeners)" ]; then
    echo "이미 떠 있다 (PID $(listeners)). 다시 띄우려면 restart."
    arm_watchdog
    return 0
  fi
  mkdir -p "$(dirname "$LOG")"

  # 포트는 아직인데 gradle 이 이미 돌고 있으면(기동 중) 두 번 띄우지 않고 기다린다
  local -a g; g=(${=$(gradle_pids)})
  if (( ${#g} > 0 )); then
    echo "이미 기동 중이다 (gradle PID ${g[*]}). 기다린다..."
    wait_up ${g[1]} "$(( $(wc -l < "$LOG") + 1 ))"
    local rc=$?
    (( rc == 0 )) && arm_watchdog
    return $rc
  fi

  # 비우지 않고 이어쓴다 — truncate 하면 붙여 둔 tail -F 가 끊긴다
  printf '\n========== start %s ==========\n' "$(date '+%F %T')" >> "$LOG"
  local from=$(( $(wc -l < "$LOG") + 1 ))

  # 실패 판정은 로그 글자가 아니라 '띄운 프로세스가 살아 있는가' 로 한다.
  # 정상 종료(SIGTERM)도 gradle 은 "BUILD FAILED ... 143" 으로 적기 때문에,
  # 직전 종료의 그 메시지가 늦게 닿으면 새 기동을 실패로 오판한다.
  local back=$PWD
  cd "$MOD" || return 1
  nohup ./gradlew bootRun -PdebugPort=$DBG --console=plain >> "$LOG" 2>&1 &
  local gpid=$!
  cd "$back"

  echo "기동 중... (로그: $LOG)"
  wait_up $gpid "$from"
  local rc=$?
  (( rc == 0 )) && arm_watchdog
  return $rc
}

status() {
  local -a p; p=(${=$(listeners)})
  if (( ${#p} > 0 )); then
    echo "앱: 실행 중 (PID ${p[*]})"
    if [ -n "$(lsof -ti tcp:$DBG -sTCP:LISTEN 2>/dev/null)" ]; then
      if [ -n "$(lsof -nP -a -p ${p[1]} -iTCP:$DBG -sTCP:ESTABLISHED 2>/dev/null)" ]; then
        echo "디버그 포트 $DBG: 열림 · 디버거 붙어 있음"
      else
        echo "디버그 포트 $DBG: 열림 · 디버거 안 붙음 — VS Code 에서 Attach (MK119, port 5005)"
      fi
    else
      echo "디버그 포트 $DBG: 닫힘"
    fi
    local wd; wd=$(pgrep_prog "app\.sh watch" "zsh$" | head -1)
    if [ -n "$wd" ]; then
      echo "watchdog: 감시 중 (PID $wd) — 디버거가 떨어지면 앱을 내린다"
    else
      echo "watchdog: 없음 — Stop Debugging 은 postDebugTask 에만 기댄다"
    fi
  else
    local -a g; g=(${=$(gradle_pids)})
    if (( ${#g} > 0 )); then
      echo "앱: 기동 중 또는 기동 실패 (gradle PID ${g[*]}) — 포트 $PORT 아직 안 열림"
    else
      echo "앱: 내려감"
    fi
  fi
  if [ -e "$FLAG" ]; then
    # flag_active 는 낡은 표식을 그 자리에서 치우므로 존재 확인을 먼저 한다
    if flag_active; then
      echo "재시작 표식: 유효 ($(cat "$FLAG" 2>/dev/null)) — 지금 Stop Debugging 하면 앱은 안 내려간다"
    else
      echo "재시작 표식: 낡아서 치웠다 — Stop Debugging 은 정상 동작한다"
    fi
  fi
}

# 콘솔 tail 을 띄운다. exec 이라 이 셸이 그대로 tail 이 되므로 기록한 $$ 가 tail 의 PID 다.
log() {
  mkdir -p "$(dirname "$LOG")"
  touch "$LOG"
  # 죽은 PID 는 걸러내고 다시 쓴다 — append 만 하면 낡은 PID 가 끝없이 쌓인다
  local -a keep
  if [ -e "$TAILS" ]; then
    local p
    for p in ${(f)"$(<$TAILS)"}; do
      kill -0 "$p" 2>/dev/null && keep+=$p
    done
  fi
  keep+=$$
  print -l -- "${keep[@]}" > "$TAILS"
  exec tail -n 200 -F "$LOG"
}

kill_tails() {
  local -a pids
  [ -e "$TAILS" ] && pids=(${(f)"$(<$TAILS)"})
  # 기록이 비거나 어긋났을 때를 대비한 보강 — 실제로 이 로그를 보고 있는 tail 을 찾는다
  pids+=(${=$(pgrep_prog "tail -n 200 -F" "^tail$")})
  rm -f "$TAILS"
  local p
  for p in ${(u)pids}; do
    [ -n "$p" ] || continue
    # PID 재사용 때문에 정말 우리 tail 인지 확인하고 죽인다
    if ps -o command= -p "$p" 2>/dev/null | grep -q -- "-F .*console\.log"; then
      kill "$p" 2>/dev/null && echo "콘솔 tail 종료: $p"
    fi
  done
  return 0
}

# 재시작 표식이 '지금 유효한가'.
#
# 표식을 지우는 일을 분리 서브셸(sleep 10)에 맡기면, restart 를 돌린 터미널이나
# 태스크가 그 전에 닫히는 순간 표식이 영원히 남는다. 그러면 그 뒤로 Stop Debugging 이
# 매번 조용히 건너뛰어 앱이 안 죽는다 — 실제로 그렇게 고장났다.
# 그래서 '파일이 있느냐' 가 아니라 '아직 살아 있는 restart 가 있느냐 / 방금 끝났느냐' 로
# 판단하고, 낡은 표식은 여기서 치운다. 어떤 경우에도 스스로 복구된다.
flag_active() {
  [ -e "$FLAG" ] || return 1
  local fpid age mtime
  fpid=$(awk '{print $1}' "$FLAG" 2>/dev/null)
  if [ -n "$fpid" ] && kill -0 "$fpid" 2>/dev/null; then
    return 0   # restart 가 아직 돌고 있다
  fi
  mtime=$(stat -f %m "$FLAG" 2>/dev/null || echo 0)
  age=$(( $(date +%s) - mtime ))
  (( age <= 15 )) && return 0   # 막 끝났다 — VS Code 가 늦게 처리하는 몫
  rm -f "$FLAG"
  return 1
}

# ───────────────────────────────────────────────────────────────────────────
# 디버거 연결 감시(watchdog).
#
# VS Code 의 postDebugTask 는 믿을 수 없다. 세션이 제대로 시작되지 않았거나
# 비정상으로 끝나면 VS Code 가 아예 주지 않고, 그러면 앱이 그대로 남는다.
# 그래서 앱 쪽에서도 본다 — JDWP(5005)에 디버거가 '붙은 적이 있는데' 그 연결이
# 사라지면 Stop Debugging 으로 보고 앱과 콘솔을 내린다.
#
# 붙은 적이 없으면 아무 것도 하지 않는다. 즉 디버깅 없이 셸에서 띄워 쓰는 쓰임새는
# 그대로다. restart 로 끊긴 것이면 건너뛴다(flag_active).
# 끄고 싶으면: MK119_NO_WATCHDOG=1 tools/app.sh start
watch_debugger() {
  local apppid=${1:-0}
  (( apppid > 0 )) || return 1
  local armed=0 gone=0
  while kill -0 $apppid 2>/dev/null; do
    if [ -n "$(lsof -nP -a -p $apppid -iTCP:$DBG -sTCP:ESTABLISHED 2>/dev/null)" ]; then
      (( armed == 0 )) && note "watchdog · 디버거가 붙었다 (PID $apppid)"
      armed=1; gone=0
    elif (( armed )); then
      # 한 번 놓친 것으로 죽이지 않는다 — 2초 연속으로 없을 때만
      gone=$(( gone + 1 ))
      if (( gone >= 2 )); then
        if flag_active; then
          armed=0; gone=0   # 재시작 때문에 끊긴 것이다
        else
          note "watchdog · 디버거 연결이 끊겼다 — 앱과 콘솔을 내린다"
          stop
          kill_tails
          return 0
        fi
      fi
    fi
    sleep 1
  done
  return 0
}

# 떠 있는 앱에 watchdog 을 붙인다. 이미 붙어 있으면 아무 것도 하지 않는다.
# nohup 이라 태스크 터미널이 닫혀도(SIGHUP) 살아남는다.
arm_watchdog() {
  [ -z "${MK119_NO_WATCHDOG:-}" ] || return 0
  [ -n "$(pgrep_prog "app\.sh watch" "zsh$")" ] && return 0
  local pid
  pid=$(lsof -ti tcp:$PORT -sTCP:LISTEN 2>/dev/null | head -1)
  [ -n "$pid" ] || return 0
  nohup "$HERE/app.sh" watch "$pid" >/dev/null 2>&1 &
  disown 2>/dev/null
  return 0
}

restart() {
  mkdir -p "$(dirname "$FLAG")"
  print -r -- "$$ $(date +%s)" > "$FLAG"   # 재시작 중임을 알린다 (stop-on-debug-exit 가 본다)
  stop
  start; local rc=$?
  # 끝난 시각으로 mtime 을 갱신한다. 이 프로세스가 죽은 뒤에도 15초는 유효하게 남아
  # VS Code 가 '연결 끊김 → postDebugTask' 를 늦게 처리해도 새 앱이 죽지 않는다
  touch "$FLAG" 2>/dev/null
  ( sleep 10; rm -f "$FLAG" ) >/dev/null 2>&1 &
  return $rc
}

# VS Code 의 postDebugTask 전용.
#
# Run → Stop Debugging 으로 세션을 끝내면 앱도 함께 내려야 한다. 그런데 postDebugTask 는
# '사용자가 멈췄을 때' 와 '대상 JVM 이 사라져 연결이 끊겼을 때' 를 구분하지 않는다.
# 그래서 밖에서 app.sh restart 를 하면 (끊김 → postDebugTask) 가 방금 새로 띄운 앱을
# 잡아 죽인다. restart 가 세워 둔 표식이 '유효할 때만' 건너뛴다 (flag_active 설명 참고).
# 앱과 함께 콘솔 tail 도 내린다. 단 restart 때는 둘 다 건너뛴다 —
# tail -F 는 재시작을 견디도록 붙여 둔 것이므로 끊으면 안 된다.
stop_on_debug_exit() {
  if flag_active; then
    echo "재시작 중이라 건너뛴다 (디버거 연결 끊김은 재시작 때문이다)"
    note "stop-on-debug-exit · 재시작 중이라 건너뜀"
    return 0
  fi
  note "stop-on-debug-exit · Stop Debugging — 앱과 콘솔을 내린다"
  stop
  kill_tails
}

case "${1:-status}" in
  start)   start ;;
  stop)    stop ;;
  restart) restart ;;
  status)  status ;;
  log)     log ;;
  stop-on-debug-exit) stop_on_debug_exit ;;
  watch)   watch_debugger "${2:-}" ;;        # 내부용 — arm_watchdog 이 띄운다
  pids)    echo "앱: $(app_pids | tr '\n' ' ')"; echo "gradle: $(gradle_pids | tr '\n' ' ')" ;;  # 무엇을 죽일지 미리 본다
  arm)     arm_watchdog; status ;;           # 이미 떠 있는 앱에 수동으로 붙일 때
  *) echo "usage: app.sh {start|stop|restart|status|log|arm|stop-on-debug-exit}"; exit 1 ;;
esac
