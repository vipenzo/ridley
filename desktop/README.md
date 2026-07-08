# Ridley Desktop — Building from source

The desktop app wraps the web editor in a native shell and adds the
**desktop-only** features: SDF modeling (libfive), on-disk libraries, GIF
export, native file dialogs, and image boards.

## Why these dependencies

Two things need building, and they pull in different toolchains:

- **The frontend** is ClojureScript, compiled by **shadow-cljs**, which runs on
  the **JVM** — so a JDK is required *at build time*. The shipped app is
  JVM-free; the JDK never leaves your build machine.
- **The shell** is [Tauri](https://tauri.app/) (Rust). CSG (booleans) runs as
  **Manifold WASM inside the frontend**, so it needs no native build. The **only
  native dependency is [libfive](https://libfive.com/)** (SDF), compiled from
  vendored C++ via **CMake**, which in turn needs boost + eigen + libpng.

## Prerequisites (all platforms)

- **Rust** — install via [rustup](https://rustup.rs/).
- **Node + npm** — for the frontend build.
- **JDK 17+** — build-time only, for shadow-cljs.
- **CMake** + a C++ toolchain — for libfive.
- **Tauri CLI v2**: `cargo install tauri-cli --version "^2"`

## Platform system dependencies

### macOS

```bash
brew install cmake boost eigen libpng
```

### Linux (Debian / Ubuntu)

```bash
sudo apt update && sudo apt install -y \
  cmake build-essential \
  libboost-all-dev libeigen3-dev libpng-dev \
  libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev libssl-dev
```

The last four are Tauri's WebKitGTK webview stack. Plus a JDK for the frontend:
`sudo apt install -y default-jdk`.

### Windows

Not yet supported. The blocker is building libfive under MSVC (vcpkg-provisioned
boost/eigen/libpng, CMake output layout); everything else in the stack is already
cross-platform. Tracked as a future phase.

## Vendored libfive

`src-tauri/vendor/libfive` is **git-ignored** — it is not tracked and not a
submodule, so a fresh checkout will not have it. Clone it once after checkout:

```bash
git clone --depth 1 https://github.com/libfive/libfive.git \
  desktop/src-tauri/vendor/libfive
```

`build.rs` compiles libfive only if this directory exists.

## Build

From the repository root:

```bash
npm install                 # also runs postinstall (vendors gif.js)
npm run release             # compiles the ClojureScript frontend into public/
cd desktop/src-tauri
cargo tauri build           # builds libfive + the Rust shell, bundles the app
```

`npm run release` must run **before** the Tauri build — Tauri serves the static
frontend from `../../public` and does not build it for you. The bundle lands
under `src-tauri/target/release/bundle/` (`.dmg` on macOS, `.deb`/AppImage on
Linux).

For iterative development use `cargo tauri dev` (it starts `shadow-cljs watch`
and hot-reloads the frontend).

## Verifying the native build (no frontend needed)

libfive is the only part that can fail to port to a new OS. You can check it in
isolation — **without** Node, the JDK, or the frontend:

```bash
cd desktop/src-tauri
cargo build --release       # proves libfive compiles + links
```

To confirm it also *runs*, launch the built binary (it starts an in-process HTTP
server on `127.0.0.1:12321`) and ask it for a mesh:

```bash
./target/release/ridley-desktop &     # opens a window; needs a display
curl -s -X POST http://127.0.0.1:12321/sdf-mesh \
  -H 'Content-Type: application/json' \
  -d '{"tree":{"op":"sphere","r":10},
       "bounds":[[-15,15],[-15,15],[-15,15]],
       "resolution":20}' | head -c 200
```

A JSON payload with `vertices` lying on the sphere (√(x²+y²+z²) ≈ 10) means
libfive builds, links, and meshes correctly on your platform.

## Platform support status

| Platform | Status |
|---|---|
| macOS | Supported (CI-built DMG, Homebrew cask) |
| Linux | Builds and runs from source (SDF verified); packaging/CI pending |
| Windows | Not yet — libfive/MSVC de-risking pending |
