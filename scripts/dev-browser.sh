#!/usr/bin/env bash
#
# dev-browser.sh — dedicated development browser for Claude Code (fallback path).
#
# Launches a headless Chrome with its own profile, connected to the Ridley dev
# app on localhost:9000. This gives Code a browser it can start/stop/reload
# autonomously so that — while Code works at the shadow-cljs REPL — its tab is
# the ONE and ONLY client connected to the dev server.
#
# This is the plan-B / Playwright-independent runtime: the browser tab it opens
# provides the JS runtime shadow-cljs's REPL attaches to. Screenshots and console
# reading happen over the Chrome DevTools Protocol on the remote-debugging port,
# with no Playwright dependency (see `screenshot` / `eval` subcommands).
#
# Usage:
#   scripts/dev-browser.sh start        # launch (idempotent)
#   scripts/dev-browser.sh stop         # terminate, leave no orphans
#   scripts/dev-browser.sh restart      # stop + start
#   scripts/dev-browser.sh status       # is it up? CDP + page target
#   scripts/dev-browser.sh reload       # reload the app tab (CDP Page.reload)
#   scripts/dev-browser.sh screenshot [out.png]   # capture the app tab
#   scripts/dev-browser.sh eval '<js>'  # run JS in the tab, print JSON result
#
# Env overrides:
#   CHROME_BIN   path to Chrome/Chromium binary
#   DEV_URL      app URL to open           (default http://localhost:9000/index.html)
#   DEBUG_PORT   remote debugging port      (default 9222)
#   PROFILE_DIR  dedicated user-data-dir    (default /tmp/ridley-dev-browser)
#
set -euo pipefail

# ---- config ---------------------------------------------------------------
DEV_URL="${DEV_URL:-http://localhost:9000/index.html}"
DEBUG_PORT="${DEBUG_PORT:-9222}"
PROFILE_DIR="${PROFILE_DIR:-/tmp/ridley-dev-browser}"
PIDFILE="${PROFILE_DIR}.pid"
LOGFILE="${PROFILE_DIR}.log"
# `ridley-dev-browser` (the profile basename) is the pgrep signature used for
# orphan detection — keep it in the user-data-dir path.
SIGNATURE="ridley-dev-browser"

find_chrome() {
  if [[ -n "${CHROME_BIN:-}" && -x "${CHROME_BIN}" ]]; then
    echo "${CHROME_BIN}"; return 0
  fi
  local candidates=(
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
    "/Applications/Chromium.app/Contents/MacOS/Chromium"
    "$(command -v google-chrome 2>/dev/null || true)"
    "$(command -v google-chrome-stable 2>/dev/null || true)"
    "$(command -v chromium 2>/dev/null || true)"
    "$(command -v chromium-browser 2>/dev/null || true)"
  )
  local c
  for c in "${candidates[@]}"; do
    [[ -n "$c" && -x "$c" ]] && { echo "$c"; return 0; }
  done
  echo "ERROR: no Chrome/Chromium binary found; set CHROME_BIN" >&2
  return 1
}

# CDP HTTP endpoint helpers -------------------------------------------------
cdp_up() { curl -s --max-time 2 "http://localhost:${DEBUG_PORT}/json/version" >/dev/null 2>&1; }

# JSON value for the first page target matching the dev app; falls back to any page.
page_ws_url() {
  curl -s --max-time 2 "http://localhost:${DEBUG_PORT}/json" 2>/dev/null \
    | node -e '
        let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
          let a=[];try{a=JSON.parse(s)}catch(e){process.exit(3)}
          const pages=a.filter(t=>t.type==="page");
          const app=pages.find(t=>/localhost:9000/.test(t.url))||pages[0];
          if(!app||!app.webSocketDebuggerUrl){process.exit(4)}
          process.stdout.write(app.webSocketDebuggerUrl);
        })'
}

running_pids() { pgrep -f "$SIGNATURE" 2>/dev/null || true; }

# True once a page target has actually navigated to the dev app (not about:blank).
app_page_ready() {
  curl -s --max-time 2 "http://localhost:${DEBUG_PORT}/json" 2>/dev/null \
    | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{let a=[];try{a=JSON.parse(s)}catch(e){process.exit(1)};process.exit(a.some(t=>t.type==="page"&&/localhost:9000/.test(t.url))?0:1)})'
}

# Stamp the app tab so the REPL can confirm it is talking to Code's own tab:
#   (in the shadow CLJS REPL)  js/window.__ridley_dev_browser__  =>  true
# shadow binds the REPL to the OLDEST connected runtime, so if this returns nil
# another (newer or older) tab is selected — close the other tabs.
inject_marker() {
  local ws; ws="$(page_ws_url)" || return 0
  CDP_WS="$ws" node -e '
    const ws=new WebSocket(process.env.CDP_WS);
    ws.onopen=()=>ws.send(JSON.stringify({id:1,method:"Runtime.evaluate",params:{expression:"window.__ridley_dev_browser__=true",returnByValue:true}}));
    ws.onmessage=()=>{ws.close();process.exit(0)};
    ws.onerror=()=>process.exit(0);
    setTimeout(()=>process.exit(0),3000);
  ' 2>/dev/null || true
}

# ---- commands -------------------------------------------------------------
cmd_start() {
  if cdp_up; then
    echo "already running (CDP up on :${DEBUG_PORT})"; cmd_status; return 0
  fi
  # clear stale locks if no live process holds the profile
  if [[ -z "$(running_pids)" ]]; then
    rm -f "${PROFILE_DIR}/SingletonLock" "${PROFILE_DIR}/SingletonCookie" \
          "${PROFILE_DIR}/SingletonSocket" 2>/dev/null || true
  fi
  mkdir -p "$PROFILE_DIR"
  local chrome; chrome="$(find_chrome)"
  echo "launching: $chrome"
  "$chrome" \
    --headless=new \
    --remote-debugging-port="${DEBUG_PORT}" \
    --user-data-dir="${PROFILE_DIR}" \
    --use-gl=angle \
    --use-angle=swiftshader \
    --enable-unsafe-swiftshader \
    --ignore-gpu-blocklist \
    --no-first-run \
    --no-default-browser-check \
    --disable-background-timer-throttling \
    --disable-backgrounding-occluded-windows \
    --disable-renderer-backgrounding \
    --window-size=1600,1000 \
    "${DEV_URL}" \
    >"${LOGFILE}" 2>&1 &
  echo $! > "${PIDFILE}"
  # wait for CDP to come up
  local i
  for i in $(seq 1 50); do
    cdp_up && break
    sleep 0.2
  done
  if cdp_up; then
    # wait for the tab to leave about:blank and reach the app, else the marker
    # would be stamped on about:blank and lost on navigation
    for i in $(seq 1 50); do app_page_ready && break; sleep 0.2; done
    inject_marker
    echo "started (pid $(cat "${PIDFILE}")), CDP on :${DEBUG_PORT}, url ${DEV_URL}"
  else
    echo "ERROR: CDP did not come up on :${DEBUG_PORT}; see ${LOGFILE}" >&2
    return 1
  fi
}

cmd_stop() {
  # graceful: SIGTERM the launcher pid, let Chrome reap its children
  if [[ -f "${PIDFILE}" ]]; then
    local pid; pid="$(cat "${PIDFILE}" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      kill -TERM "$pid" 2>/dev/null || true
    fi
  fi
  # wait up to 5s for graceful exit
  local i
  for i in $(seq 1 25); do
    [[ -z "$(running_pids)" ]] && break
    sleep 0.2
  done
  # anything still matching the profile signature gets SIGTERM then SIGKILL
  if [[ -n "$(running_pids)" ]]; then
    pkill -TERM -f "$SIGNATURE" 2>/dev/null || true
    sleep 0.5
  fi
  if [[ -n "$(running_pids)" ]]; then
    pkill -KILL -f "$SIGNATURE" 2>/dev/null || true
    sleep 0.3
  fi
  rm -f "${PIDFILE}"
  if [[ -z "$(running_pids)" ]]; then
    echo "stopped (no processes match '${SIGNATURE}')"
  else
    echo "WARNING: orphan processes remain:" >&2
    running_pids >&2
    return 1
  fi
}

cmd_status() {
  local pids; pids="$(running_pids)"
  if [[ -n "$pids" ]]; then
    echo "process: running (pids: $(echo "$pids" | tr '\n' ' '))"
  else
    echo "process: not running"
  fi
  if cdp_up; then
    echo "cdp:     up on :${DEBUG_PORT}"
    curl -s --max-time 2 "http://localhost:${DEBUG_PORT}/json" 2>/dev/null \
      | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const a=JSON.parse(s);a.filter(t=>t.type==="page").forEach(t=>console.log("target:  "+t.url))}catch(e){}})' \
      || true
  else
    echo "cdp:     down"
  fi
}

cmd_reload() {
  local ws; ws="$(page_ws_url)" || { echo "no page target" >&2; return 1; }
  CDP_WS="$ws" node -e '
    const ws=new WebSocket(process.env.CDP_WS);
    ws.onopen=()=>ws.send(JSON.stringify({id:1,method:"Page.reload",params:{ignoreCache:true}}));
    ws.onmessage=(m)=>{const o=JSON.parse(m.data);if(o.id===1){console.log("reloaded");ws.close();process.exit(0)}};
    ws.onerror=(e)=>{console.error("ws error",e.message||e);process.exit(1)};
    setTimeout(()=>{console.error("timeout");process.exit(1)},5000);
  '
  # re-stamp: a full reload clears window vars
  sleep 0.5
  inject_marker
}

cmd_screenshot() {
  local out="${1:-${PROFILE_DIR}-shot.png}"
  local ws; ws="$(page_ws_url)" || { echo "no page target" >&2; return 1; }
  CDP_WS="$ws" OUT="$out" node -e '
    const fs=require("fs");
    const ws=new WebSocket(process.env.CDP_WS);
    let idc=0; const pending={};
    const send=(method,params={})=>new Promise((res)=>{const id=++idc;pending[id]=res;ws.send(JSON.stringify({id,method,params}))});
    ws.onopen=async()=>{
      await send("Page.enable");
      const {data}=await send("Page.captureScreenshot",{format:"png"});
      fs.writeFileSync(process.env.OUT,Buffer.from(data,"base64"));
      console.log("wrote "+process.env.OUT);
      ws.close();process.exit(0);
    };
    ws.onmessage=(m)=>{const o=JSON.parse(m.data);if(o.id&&pending[o.id]){pending[o.id](o.result||{});delete pending[o.id]}};
    ws.onerror=(e)=>{console.error("ws error",e.message||e);process.exit(1)};
    setTimeout(()=>{console.error("timeout");process.exit(1)},10000);
  '
}

cmd_eval() {
  local expr="${1:?usage: eval <js-expression>}"
  local ws; ws="$(page_ws_url)" || { echo "no page target" >&2; return 1; }
  CDP_WS="$ws" EXPR="$expr" node -e '
    const ws=new WebSocket(process.env.CDP_WS);
    let idc=0; const pending={};
    const send=(method,params={})=>new Promise((res)=>{const id=++idc;pending[id]=res;ws.send(JSON.stringify({id,method,params}))});
    ws.onopen=async()=>{
      const r=await send("Runtime.evaluate",{expression:process.env.EXPR,returnByValue:true,awaitPromise:true});
      console.log(JSON.stringify(r.result!==undefined?r.result:r,null,2));
      ws.close();process.exit(0);
    };
    ws.onmessage=(m)=>{const o=JSON.parse(m.data);if(o.id&&pending[o.id]){pending[o.id](o.result||{});delete pending[o.id]}};
    ws.onerror=(e)=>{console.error("ws error",e.message||e);process.exit(1)};
    setTimeout(()=>{console.error("timeout");process.exit(1)},10000);
  '
}

case "${1:-}" in
  start)      cmd_start ;;
  stop)       cmd_stop ;;
  restart)    cmd_stop || true; cmd_start ;;
  status)     cmd_status ;;
  reload)     cmd_reload ;;
  screenshot) shift; cmd_screenshot "${1:-}" ;;
  eval)       shift; cmd_eval "${1:-}" ;;
  *)
    echo "usage: $0 {start|stop|restart|status|reload|screenshot [out.png]|eval <js>}" >&2
    exit 2 ;;
esac
