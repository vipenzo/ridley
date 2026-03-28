#!/bin/bash
# Copy Manifold dylibs into the .app bundle's Frameworks directory.
# Run after `cargo tauri build`.

set -e

APP_BUNDLE="src-tauri/target/release/bundle/macos/Ridley.app"
FRAMEWORKS_DIR="$APP_BUNDLE/Contents/Frameworks"
DYLIB_DIR="src-tauri/target/release/build/manifold3d-sys-*/out/lib"

if [ ! -d "$APP_BUNDLE" ]; then
    echo "Error: $APP_BUNDLE not found. Run 'cargo tauri build' first."
    exit 1
fi

mkdir -p "$FRAMEWORKS_DIR"

# Copy the versioned dylibs
for lib in libmanifold.3.dylib libmanifoldc.3.dylib; do
    src=$(ls $DYLIB_DIR/$lib 2>/dev/null | head -1)
    if [ -n "$src" ]; then
        cp "$src" "$FRAMEWORKS_DIR/"
        echo "Copied $lib to Frameworks/"
    else
        echo "Warning: $lib not found in build output"
    fi
done

echo "Done. Dylibs bundled in $FRAMEWORKS_DIR"
