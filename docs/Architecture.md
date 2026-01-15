# Ridley — Architecture

## Overview

Ridley is a 3D modeling application based on turtle graphics with a face-based modeling paradigm. Users write scripts in Clojure to generate 3D geometry through an intuitive "draw on face, then extrude" workflow—similar to SketchUp's push/pull but fully programmatic. Models are rendered in real-time and can be exported for 3D printing or viewed in VR/AR.

## Core Concept: Face-Based Turtle Modeling

The central innovation is unifying all 3D operations into the turtle paradigm:

1. **Start with a primitive** (box, cylinder) or use generative ops (extrude, revolve)
2. **Select a face** with `(pen :face-id)`
3. **Draw a 2D profile** on that face using turtle commands
4. **Extrude** with `(f dist)` — positive adds material, negative subtracts (creates holes)

This eliminates the need for separate boolean operations in most cases, while keeping the mental model simple and sequential.

## Tech Stack

### Core
- **ClojureScript** — Main application language
- **SCI** (Small Clojure Interpreter) — Embedded interpreter for user scripts
- **Custom geometry engine** — Mesh generation, face operations, extrusion
- **Manifold** (WASM) — Boolean operations for complex CSG (future)

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
{:position [x y z]         ; Current position in 3D space
 :heading [x y z]          ; Forward direction (unit vector)
 :up [x y z]               ; Up direction (unit vector)
 :pen-mode nil             ; nil=off, :2d=draw lines, face-id=draw on face
 :current-face nil         ; Selected face for 2D drawing
 :pending-profile []       ; 2D points accumulated on current face
 :meshes [...]}            ; Accumulated 3D meshes with face metadata
```

### 2. Face-Based Modeling (Push/Pull Paradigm)

Instead of separate boolean operations, most 3D editing is done through face extrusion:

```clojure
;; Create a box with a hole
(box 100 100 50)           ; Start with a box
(pen :top)                 ; Select top face
(circle 20)                ; Draw a circle on that face
(f -50)                    ; Extrude down = subtract (hole)

;; Add a boss on top
(pen :top)
(circle 15)
(f 30)                     ; Extrude up = add material
```

The direction of extrusion determines the operation:
- **Positive distance**: Add material (union)
- **Negative distance**: Remove material (subtraction)

### 3. Visual Face Selection

For complex meshes where face IDs aren't obvious:

```clojure
(list-faces my-mesh)       ; Show all face IDs
(select my-mesh 42)        ; Highlight face 42 in viewport
(pen 42)                   ; Select face 42 for drawing
```

The viewport highlights selected faces with a distinct color.

### 4. Generative Operations

Standard generative ops create initial meshes with labeled faces:

- **extrude**: Creates `:top`, `:bottom`, `:side-N` faces
- **revolve**: Creates `:start-cap`, `:end-cap`, `:surface` faces
- **loft**: Creates `:start`, `:end`, `:surface` faces

### 5. Viewport as Selection

What's visible in the viewport is what gets exported. Simple mental model.

### 6. SCI for User Scripts

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