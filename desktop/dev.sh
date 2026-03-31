#!/bin/bash
# Launched by Tauri's beforeDevCommand — finds project root and starts shadow-cljs
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT" && exec npm run dev
