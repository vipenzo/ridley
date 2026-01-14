# Ridley — Architecture

## Overview

Ridley is a 3D modeling application based on turtle graphics. Users write scripts in Clojure to generate 3D geometry, which is rendered in real-time and can be exported for 3D printing or viewed in VR/AR.

## Tech Stack

### Core
- **ClojureScript** — Main application language
- **SCI** (Small Clojure Interpreter) — Embedded interpreter for user scripts
- **thi.ng/geom** — Computational geometry library (transforms, meshes, primitives)
- **Manifold** (WASM) — Boolean operations (CSG), guarantees watertight meshes

### Rendering
- **Three.js** — 3D rendering engine
- **WebXR** — VR/AR support (Quest 3, other headsets)

### Application Shell
- **Tauri** — Desktop app packaging (macOS, lighter than Electron)
- **Web** — Browser-based version (same codebase)

### Development
- **shadow-cljs** — ClojureScript build tool
- **npm** — JavaScript dependencies

## Project Structure

```
ridley/
├── src/
│   ├── ridley/
│   │   ├── core.cljs          # App entry point
│   │   ├── editor/
│   │   │   ├── core.cljs      # Editor component
│   │   │   └── repl.cljs      # REPL evaluation with SCI
│   │   ├── turtle/
│   │   │   ├── core.cljs      # Turtle state and commands
│   │   │   ├── path.cljs      # Path construction (2D/3D)
│   │   │   ├── shape.cljs     # Shape definitions
│   │   │   └── parse.cljs     # Dense syntax parser
│   │   ├── geometry/
│   │   │   ├── primitives.cljs # Box, cylinder, sphere, cone
│   │   │   ├── generative.cljs # Extrude, revolve, sweep, loft
│   │   │   ├── boolean.cljs    # Union, subtract, intersect (Manifold)
│   │   │   └── export.cljs     # STL, OBJ, 3MF export
│   │   ├── viewport/
│   │   │   ├── core.cljs      # Three.js setup
│   │   │   ├── scene.cljs     # Scene management (show/hide/solo)
│   │   │   ├── controls.cljs  # Orbit controls, zoom, pan
│   │   │   └── xr.cljs        # WebXR integration
│   │   └── ui/
│   │       ├── layout.cljs    # Main layout
│   │       ├── toolbar.cljs   # Toolbar, VR toggle
│   │       └── search.cljs    # Fuzzy search for shapes
│   └── sci_ctx.cljs           # SCI context with preloaded symbols
├── public/
│   ├── index.html
│   └── css/
│       └── style.css
├── src-tauri/                  # Tauri config (for desktop)
├── deps.edn                    # Clojure dependencies
├── shadow-cljs.edn             # Build config
├── package.json                # JS dependencies
└── docs/
    ├── ARCHITECTURE.md         # This file
    ├── SPEC.md                 # DSL specification
    └── ROADMAP.md              # Development phases
```

## Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│                         USER                                │
│                          │                                  │
│                    Clojure script                           │
│                          ▼                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    SCI Interpreter                   │   │
│  │   - Evaluates user code                             │   │
│  │   - Preloaded namespace with DSL functions          │   │
│  │   - Returns geometry data structures                │   │
│  └──────────────────────┬──────────────────────────────┘   │
│                         │                                   │
│            Immutable geometry data (thi.ng)                │
│                         ▼                                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                 Geometry Engine                      │   │
│  │   - thi.ng/geom for transforms, tessellation        │   │
│  │   - Manifold WASM for boolean ops                   │   │
│  │   - Produces renderable meshes                      │   │
│  └──────────────────────┬──────────────────────────────┘   │
│                         │                                   │
│                    Mesh data                                │
│                         ▼                                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    Viewport                          │   │
│  │   - Three.js scene                                  │   │
│  │   - WebXR for VR/AR                                 │   │
│  │   - Show/hide/solo commands                         │   │
│  └──────────────────────┬──────────────────────────────┘   │
│                         │                                   │
│                         ▼                                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    Export                            │   │
│  │   - STL (binary/ascii)                              │   │
│  │   - OBJ                                             │   │
│  │   - 3MF (with colors)                               │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Key Design Decisions

### 1. Immutable Turtle State

The turtle is a pure data structure. Commands are functions that take state and return new state. No mutation.

```clojure
{:position [0 0 0]
 :heading [0 0 1]
 :up [0 1 0]
 :pen-down? true
 :geometry []}
```

### 2. Generative Modifiers

Fillet and chamfer are applied generatively, not as post-processing on meshes:
- On 2D shapes: affects longitudinal edges
- On paths: affects edges along extrusion direction
- On caps: `:cap-fillet` parameter on generative operations
- On booleans: `(subtract a b :fillet 2)`

### 3. Typed Anchors

Two types of anchors to avoid ambiguity:
- `@` — position only
- `@>` — position + orientation

Navigation commands:
- `GA` — goto anchor, keep current heading (works with both)
- `GD` — goto and adopt direction (requires `@>`)

### 4. Viewport as Selection

What's visible in the viewport is what gets exported. Simple mental model.

### 5. SCI for User Scripts

User code runs in SCI, not raw ClojureScript. Benefits:
- Sandboxed execution
- Controlled namespace exposure
- No compilation step (instant feedback)
- Easy to add/restrict functions

## External Dependencies

### ClojureScript (deps.edn)
```clojure
{:deps
 {org.clojure/clojurescript {:mvn/version "1.11.132"}
  thheller/shadow-cljs {:mvn/version "2.27.1"}
  borkdude/sci {:mvn/version "0.8.41"}
  thi.ng/geom {:mvn/version "1.0.1"}}}
```

### JavaScript (package.json)
```json
{
  "dependencies": {
    "three": "^0.160.0",
    "manifold-3d": "^2.5.0"
  }
}
```

## VR/AR Architecture

WebXR runs in the same Three.js context. The VR toggle:
1. Starts HTTPS server (required for WebXR)
2. Generates QR code with local IP
3. Quest browser connects, same scene renders in stereo

Passthrough AR uses Quest 3's passthrough API via WebXR's `immersive-ar` session type.

## Future Considerations

- **Undo/redo** — Since state is immutable, history is just a list of states
- **Collaborative editing** — CRDT on the script text
- **Plugin system** — User-defined SCI namespaces
- **AI assistant** — LLM generates DSL code from natural language