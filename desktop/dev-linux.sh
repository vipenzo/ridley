#!/usr/bin/env bash
# Launch the Ridley desktop app in dev mode on Linux.
#
# Handles the two gotchas that bite on this setup (see
# dev-docs/linux-dev-handoff.md, Blocco 1):
#   1. VS Code snap env pollution (GTK_PATH/SNAP_LIBRARY_PATH/... point into
#      /snap/code) drags in core20's old libpthread -> the webview dies with
#      `__libc_pthread_init`. We strip those vars.
#   2. A stale `shadow-cljs watch app` left running collides with the one the app
#      spawns (`ExceptionInfo: already started`) and leaves the app in a broken
#      state. We kill any stale watch first so the app owns the single watch.
#
# NB: launch with `cargo tauri dev`, NOT the bare binary — a debug build with no
# devUrl falls back to http://localhost:1420 (dead) => full-page
# "Could not connect to 127.0.0.1: Connection refused".
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT/desktop/src-tauri"

# 1. Kill any stale shadow watch / previous app instance (by PID, so we never
#    pkill our own shell). Ports 9000/9630/12321 must be free for a clean start.
for pat in "shadow-cljs watch app" "target/debug/ridley-desktop" "cargo tauri dev"; do
  pids="$(pgrep -f "$pat" || true)"
  [ -n "$pids" ] && { echo "killing stale [$pat]: $pids"; kill $pids 2>/dev/null || true; }
done
sleep 1

# 2. Strip the VS Code snap environment.
for v in $(env | grep -iE '^(SNAP|GTK_|GIO_|GDK_PIXBUF|LOCPATH|GSETTINGS|MXC_|VSCODE_)' | cut -d= -f1); do
  unset "$v"
done

# 3. The DMABUF workaround now lives in the app (main.rs, dmabuf_workaround:
#    on below WebKitGTK 2.52). Export WEBKIT_DISABLE_DMABUF_RENDERER yourself
#    to force it either way.
export RUST_BACKTRACE=1

echo "env clean: GTK_PATH='${GTK_PATH:-<unset>}' SNAP='${SNAP:-<unset>}' DMABUF_OFF='${WEBKIT_DISABLE_DMABUF_RENDERER:-<unset>}' DISPLAY=${DISPLAY:-<none>}"
exec cargo tauri dev "$@"
