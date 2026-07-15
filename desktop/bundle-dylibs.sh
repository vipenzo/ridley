#!/bin/bash
# Copy libfive's dylibs (and, recursively, any non-system dependency they
# pull in, e.g. Homebrew's libpng) into the .app bundle's Frameworks
# directory, rewrite install names to @rpath, strip build-machine rpaths
# from the main binary, and re-sign everything that was touched.
# Run after `cargo tauri build`, before DMG creation.

set -e

APP_BUNDLE="src-tauri/target/release/bundle/macos/Ridley.app"
FRAMEWORKS_DIR="$APP_BUNDLE/Contents/Frameworks"
MAIN_BINARY="$APP_BUNDLE/Contents/MacOS/ridley-desktop"
KEEP_RPATH="@executable_path/../Frameworks"

if [ ! -d "$APP_BUNDLE" ]; then
    echo "Error: $APP_BUNDLE not found. Run 'cargo tauri build' first."
    exit 1
fi

mkdir -p "$FRAMEWORKS_DIR"

# --- 1. Copy the libfive dylibs ---------------------------------------------

LIBFIVE_BUILD="src-tauri/target/release/build/ridley-desktop-*/out/libfive-build/libfive"
for lib in src/libfive.dylib stdlib/libfive-stdlib.dylib; do
    src=$(ls $LIBFIVE_BUILD/$lib 2>/dev/null | head -1)
    if [ -n "$src" ]; then
        cp "$src" "$FRAMEWORKS_DIR/"
        echo "Copied $(basename "$lib") to Frameworks/"
    else
        echo "Warning: $lib not found in libfive build output"
    fi
done

# --- 2. Recursively vendor non-system dependencies --------------------------
# Any dependency whose install name isn't under /usr/lib or /System, and
# isn't already a relocatable @rpath/@loader_path/@executable_path reference,
# is an external (typically Homebrew) dylib. It must be copied into
# Frameworks and every reference to its absolute path rewritten, or the
# bundle silently depends on the build machine's environment (this is how
# libpng went missing before). Fails loudly if the source file referenced by
# a load command doesn't exist on this machine, rather than producing a
# bundle that crashes on first launch elsewhere.

is_system_path() {
    case "$1" in
        /usr/lib/*|/System/*) return 0 ;;
        *) return 1 ;;
    esac
}

is_relocatable() {
    case "$1" in
        @rpath/*|@loader_path/*|@executable_path/*) return 0 ;;
        *) return 1 ;;
    esac
}

shopt -s nullglob
queue=("$FRAMEWORKS_DIR"/*.dylib)
shopt -u nullglob

while [ "${#queue[@]}" -gt 0 ]; do
    dylib="${queue[0]}"
    queue=("${queue[@]:1}")

    deps=$(otool -L "$dylib" | tail -n +2 | awk '{print $1}')
    while IFS= read -r dep; do
        [ -z "$dep" ] && continue
        is_system_path "$dep" && continue
        is_relocatable "$dep" && continue

        dep_name=$(basename "$dep")
        dest="$FRAMEWORKS_DIR/$dep_name"

        if [ ! -f "$dest" ]; then
            if [ ! -f "$dep" ]; then
                echo "Error: $(basename "$dylib") depends on $dep, which does not exist on this machine. Install it (e.g. via brew) or update this script." >&2
                exit 1
            fi
            cp "$dep" "$dest"
            install_name_tool -id "@rpath/$dep_name" "$dest"
            echo "Vendored $dep_name (dependency of $(basename "$dylib")) into Frameworks/"
            queue+=("$dest")
        fi

        install_name_tool -change "$dep" "@rpath/$dep_name" "$dylib"
        echo "Rewrote $dep_name reference in $(basename "$dylib") to @rpath/$dep_name"
    done <<< "$deps"
done

# --- 3. Strip build-machine rpaths from the main binary ---------------------
# `cargo tauri build` bakes in absolute rpaths pointing at the build tree's
# libfive-build output directory (on CI, the runner's workspace path). Only
# the bundle-relative Frameworks rpath should survive into the shipped app.

binary_changed=0
if [ -f "$MAIN_BINARY" ]; then
    all_rpaths=$(otool -l "$MAIN_BINARY" | awk '/LC_RPATH/{getline; getline; print $2}')
    stale_rpaths=$(printf '%s\n' "$all_rpaths" | grep -vFx "$KEEP_RPATH" || true)
    if [ -n "$stale_rpaths" ]; then
        while IFS= read -r rpath; do
            [ -z "$rpath" ] && continue
            if install_name_tool -delete_rpath "$rpath" "$MAIN_BINARY" 2>/dev/null; then
                echo "Removed stale rpath from $(basename "$MAIN_BINARY"): $rpath"
                binary_changed=1
            else
                echo "Note: rpath already absent, skipping: $rpath"
            fi
        done <<< "$stale_rpaths"
    fi
else
    echo "Warning: main binary not found at $MAIN_BINARY, skipping rpath cleanup"
fi

# --- 4. Re-sign everything install_name_tool touched -------------------------
# On arm64, install_name_tool invalidates the existing code signature; an
# unsigned (or stale-signed) binary/dylib gets SIGKILLed at launch.

for dylib in "$FRAMEWORKS_DIR"/*.dylib; do
    [ -f "$dylib" ] || continue
    codesign -f -s - "$dylib"
    echo "Re-signed $(basename "$dylib")"
done

if [ "$binary_changed" = "1" ]; then
    codesign -f -s - "$MAIN_BINARY"
    echo "Re-signed $(basename "$MAIN_BINARY")"
fi

# Re-seal the app bundle itself: adding files to Frameworks/ after Tauri
# signed the bundle leaves those files outside the top-level seal, which
# fails `codesign --verify --deep --strict`.
codesign -f -s - "$APP_BUNDLE"
echo "Re-signed $APP_BUNDLE"

echo "Done. Dylibs bundled and re-signed in $FRAMEWORKS_DIR"
