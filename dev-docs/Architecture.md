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
│   └── ridley/
│       ├── core.cljs              # App entry point, UI event handling
│       ├── editor/
│       │   └── repl.cljs          # SCI-based code evaluation, macros
│       ├── turtle/
│       │   ├── core.cljs          # Turtle state, movement, extrusion
│       │   ├── path.cljs          # Path recording and playback
│       │   ├── shape.cljs         # 2D shape definitions (circle, rect, star)
│       │   └── transform.cljs     # Shape transformations (scale, rotate, morph)
│       ├── geometry/
│       │   ├── primitives.cljs    # Box, sphere, cylinder, cone
│       │   ├── operations.cljs    # Legacy extrude, revolve, sweep, loft
│       │   ├── faces.cljs         # Face group identification
│       │   └── stl.cljs           # STL export
│       ├── scene/
│       │   └── registry.cljs      # Mesh registry (named meshes, visibility)
│       ├── viewport/
│       │   └── core.cljs          # Three.js rendering, OrbitControls
│       └── manifold/
│           └── core.cljs          # Manifold WASM integration (CSG)
├── public/
│   ├── index.html
│   ├── css/style.css
│   └── scripts/                   # WebXR polyfill
├── docs/                          # GitHub Pages deployment
├── dev-docs/                      # Development documentation
├── shadow-cljs.edn                # Build config
└── package.json                   # JS dependencies
```

## Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│                         USER                                │
│                          │                                  │
│         Definitions panel / REPL input                      │
│                          ▼                                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    SCI Interpreter                   │   │
│  │   - Evaluates user code (repl.cljs)                 │   │
│  │   - DSL macros: extrude, loft, path, register       │   │
│  │   - Returns turtle state with meshes/lines          │   │
│  └──────────────────────┬──────────────────────────────┘   │
│                         │                                   │
│         Turtle state: {:meshes [...] :lines [...]}         │
│                         ▼                                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │               Scene Registry                         │   │
│  │   - Named meshes (persistent)                       │   │
│  │   - Anonymous meshes (cleared on re-eval)           │   │
│  │   - Visibility flags per mesh                       │   │
│  └──────────────────────┬──────────────────────────────┘   │
│                         │                                   │
│         Visible meshes: [{:vertices :faces} ...]           │
│                         ▼                                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Three.js Viewport                       │   │
│  │   - BufferGeometry from mesh vertices/faces         │   │
│  │   - MeshStandardMaterial with flat shading          │   │
│  │   - OrbitControls for camera                        │   │
│  └──────────────────────┬──────────────────────────────┘   │
│                         │                                   │
│                         ▼                                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    Export                            │   │
│  │   - STL (visible meshes)                            │   │
│  │   - Manifold boolean ops for CSG                    │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Key Design Decisions

### 1. Immutable Turtle State

The turtle is a pure data structure. Commands are functions that take state and return new state. No mutation.

```clojure
{:position [x y z]              ; Current position in 3D space
 :heading [x y z]               ; Forward direction (unit vector)
 :up [x y z]                    ; Up direction (unit vector)
 :pen-mode nil                  ; nil/:off, :line, :shape, :loft
 :lines [...]                   ; Accumulated line segments
 :meshes [...]                  ; Accumulated 3D meshes

 ;; Extrusion state (when pen-mode is :shape)
 :sweep-base-shape shape        ; 2D shape being extruded
 :sweep-rings [...]             ; Accumulated 3D rings for mesh construction
 :sweep-first-ring ring         ; First ring (for caps)
 :pending-rotation {:th :tv :tr} ; Pending rotation (processed on next f)

 ;; Closed extrusion state
 :sweep-closed? true            ; Marks closed extrusion (torus-like)
 :sweep-closed-first-ring ring  ; Offset first ring for closing

 ;; Loft state (when pen-mode is :loft)
 :loft-transform-fn fn          ; Shape transform function (shape, t) -> shape
 :loft-steps n                  ; Number of interpolation steps
 :loft-progress 0.0}            ; Current progress (0 to 1)
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

### ClojureScript (shadow-cljs.edn)
```clojure
:dependencies [[borkdude/sci "0.8.43"]]
```

### JavaScript (package.json)
```json
{
  "dependencies": {
    "three": "^0.176.0"
  }
}
```

### CDN
- **Manifold 3D** (v3.0.0) - Loaded as ES module from jsDelivr CDN
  - Boolean operations (union, difference, intersection)
  - Mesh validation (manifold status check)

## VR/AR Architecture

WebXR runs in the same Three.js context. The VR toggle:
1. Starts HTTPS server (required for WebXR)
2. Generates QR code with local IP
3. Quest browser connects, same scene renders in stereo

Passthrough AR uses Quest 3's passthrough API via WebXR's `immersive-ar` session type.

## Extrusion Architecture

### Open Extrusion (`extrude`)

Standard extrusion creates a mesh with caps at both ends:

1. **Stamp**: Place first ring at turtle position
2. **Move**: Each `(f dist)` adds a new ring
3. **Rotate**: Rotations are "pending" until next forward movement
4. **Finalize**: Build mesh from rings, add caps

When a rotation is followed by forward movement, the system:
- Shortens the previous segment by shape radius
- Creates a corner mesh connecting old and new orientations
- Starts new segment at offset position

### Closed Extrusion (`extrude-closed`)

Creates torus-like meshes where the last ring connects to the first (no caps):

**Two implementations:**

1. **Macro-based** (`extrude-closed shape & movements`):
   - Uses `stamp-closed` to mark closed mode
   - First ring offset by radius
   - `finalize-sweep-closed` creates closing corner

2. **Path-based** (`extrude-closed shape path`):
   - Pre-processes path to calculate segment shortening
   - Creates a SINGLE manifold mesh (not multiple segments)
   - All rings collected in one pass, faces connect consecutively
   - Last ring wraps to first ring for closed topology

**Path pre-processing algorithm:**

```
For each forward segment:
  - shorten-start = radius if preceded by rotation, else 0
  - shorten-end = radius if followed by rotation, else 0
  - effective-dist = dist - shorten-start - shorten-end
```

This produces correct manifold meshes suitable for boolean operations.

## Mesh Registry

The scene registry (`scene/registry.cljs`) manages all meshes in the scene:

```clojure
;; Internal structure
[{:mesh mesh-data :name :keyword-or-nil :visible true/false} ...]
```

**Features:**
- **Named meshes**: `(register name mesh)` - persisted across evaluations
- **Anonymous meshes**: From turtle movements, cleared on re-eval
- **Visibility control**: `(show! :name)`, `(hide! :name)`, `(show-all!)`, `(hide-all!)`
- **Toggle view**: "All" shows everything, "Objects" shows only named meshes

The viewport only renders meshes marked as `:visible true`.

## Manifold Integration

Boolean operations use Manifold WASM library:

```clojure
(mesh-union a b)        ; A + B
(mesh-difference a b)   ; A - B
(mesh-intersection a b) ; A ∩ B
(mesh-status mesh)      ; Check if mesh is valid manifold
```

**Manifold requirements:**
- Meshes must be watertight (no holes)
- All faces must have consistent CCW winding (from outside)
- No self-intersecting geometry
- No duplicate/degenerate faces

The `extrude-closed-from-path` function produces single manifold meshes by:
- Collecting all rings in a single vector
- Building faces that connect consecutive rings
- Using `(mod (inc ring-idx) n-rings)` for closed topology

## Future Considerations

- **Undo/redo** — Since state is immutable, history is just a list of states
- **Collaborative editing** — CRDT on the script text
- **Plugin system** — User-defined SCI namespaces
- **AI assistant** — LLM generates DSL code from natural language