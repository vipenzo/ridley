#!/bin/bash
# Launched by Tauri's beforeBuildCommand — builds production JS
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT" && exec npm run release
