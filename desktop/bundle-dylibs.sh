#!/bin/bash
# Copy libfive dylibs into the .app bundle's Frameworks directory.
# Run after `cargo tauri build`.

set -e

APP_BUNDLE="src-tauri/target/release/bundle/macos/Ridley.app"
FRAMEWORKS_DIR="$APP_BUNDLE/Contents/Frameworks"

if [ ! -d "$APP_BUNDLE" ]; then
    echo "Error: $APP_BUNDLE not found. Run 'cargo tauri build' first."
    exit 1
fi

mkdir -p "$FRAMEWORKS_DIR"

# libfive dylibs
LIBFIVE_BUILD="src-tauri/target/release/build/ridley-desktop-*/out/libfive-build/libfive"
for lib in src/libfive.dylib stdlib/libfive-stdlib.dylib; do
    src=$(ls $LIBFIVE_BUILD/$lib 2>/dev/null | head -1)
    if [ -n "$src" ]; then
        cp "$src" "$FRAMEWORKS_DIR/"
        echo "Copied $(basename $lib) to Frameworks/"
    else
        echo "Warning: $lib not found in libfive build output"
    fi
done

echo "Done. Dylibs bundled in $FRAMEWORKS_DIR"
