# Ridley — Development Roadmap

## Phase 1: Proof of Concept

**Goal**: Minimal loop working — edit code, see 3D result.

### 1.1 Project Setup
- [ ] Initialize shadow-cljs project
- [ ] Configure deps.edn with SCI, thi.ng/geom
- [ ] Setup package.json with Three.js
- [ ] Basic HTML shell with editor (textarea) and canvas

### 1.2 Turtle Core
- [ ] Immutable turtle state: `{:position :heading :up :pen-down? :geometry}`
- [ ] Basic commands: `f`, `b`, `th`, `tv`, `tr`
- [ ] Pen control: `pen-up`, `pen-down`
- [ ] Geometry accumulation (line segments)

### 1.3 SCI Integration
- [ ] Setup SCI context with turtle functions exposed
- [ ] Evaluate user code string → geometry data
- [ ] Error handling and display

### 1.4 Three.js Viewport
- [ ] Basic scene setup (camera, lights, renderer)
- [ ] Orbit controls (rotate, zoom, pan)
- [ ] Convert turtle geometry → Three.js lines
- [ ] Auto-fit camera to geometry

### 1.5 Basic UI
- [ ] Split layout: editor left, viewport right
- [ ] Cmd+Enter to evaluate
- [ ] Display errors below editor

**Deliverable**: Can write `(f 50) (th 90) (f 50)` and see an L-shape in 3D.

---

## Phase 2: Shapes and Primitives

**Goal**: Generate solid geometry, not just lines.

### 2.1 Path and Shape
- [ ] `(path ...)` constructor
- [ ] `(shape ...)` constructor with auto-close
- [ ] Dense syntax parser for strings
- [ ] Fillet/chamfer in path context

### 2.2 3D Primitives
- [ ] `box`, `sphere`, `cyl`, `cone`
- [ ] Placement at turtle position/orientation
- [ ] thi.ng/geom mesh generation

### 2.3 Generative Operations
- [ ] `extrude` — linear extrusion
- [ ] `revolve` — revolution around axis
- [ ] `sweep` — extrude along path
- [ ] `loft` — transition between profiles

### 2.4 Viewport Improvements
- [ ] Render solid meshes (not just lines)
- [ ] Basic material/lighting
- [ ] Grid and axes display

**Deliverable**: Can create a vase with `(revolve profile axis)`.

---

## Phase 3: Boolean Operations

**Goal**: CSG operations that produce watertight meshes.

### 3.1 Manifold Integration
- [ ] Load Manifold WASM module
- [ ] Convert thi.ng mesh ↔ Manifold mesh
- [ ] `union`, `subtract`, `intersect`

### 3.2 Integrated Modifiers
- [ ] Boolean with fillet: `(subtract a b :fillet 2)`
- [ ] Boolean with chamfer: `(union a b :chamfer 1)`

### 3.3 Validation
- [ ] `watertight?` check
- [ ] `volume` calculation
- [ ] `bounds` query

**Deliverable**: Can create a box with holes, manifold-safe.

---

## Phase 4: Anchors and Navigation

**Goal**: Named reference points for complex models.

### 4.1 Anchor System
- [ ] `(mark :name)` — position only
- [ ] `(mark-oriented :name)` — position + heading
- [ ] Store anchors in scene context

### 4.2 Navigation
- [ ] `(goto :name)` — move, keep heading
- [ ] `(goto-oriented :name)` — move and adopt heading
- [ ] `(look-at :name)` — orient toward

### 4.3 Bezier Curves
- [ ] `(bezier-to [x y z])`
- [ ] `(bezier-to [x y z] :heading [...])`
- [ ] `(bezier-to-anchor :name)`
- [ ] `(bezier-to-oriented :name)`

### 4.4 Arcs
- [ ] `(arc-h radius angle)`
- [ ] `(arc-v radius angle)`

**Deliverable**: Can create smooth curved paths between named points.

---

## Phase 5: Viewport and Visibility

**Goal**: Control what's displayed, prepare for export.

### 5.1 Scene Management
- [ ] Track all defined shapes by name
- [ ] `(show shape)`, `(hide shape)`
- [ ] `(solo shape)`, `(show-all)`
- [ ] Wireframe mode

### 5.2 Visual Aids
- [ ] Toggle axes display
- [ ] Toggle grid
- [ ] Show anchors as small markers
- [ ] Show paths as lines

### 5.3 Inspection
- [ ] `(inspect shape)` — zoom to fit
- [ ] `(bounds shape)`, `(volume shape)`
- [ ] `(center shape)`

### 5.4 Fuzzy Search
- [ ] `(find "pattern")` — search defined names
- [ ] UI: search box that filters and highlights

**Deliverable**: Can selectively show parts of a complex model.

---

## Phase 6: Export

**Goal**: Generate files for 3D printing.

### 6.1 STL Export
- [ ] Binary STL export
- [ ] ASCII STL export
- [ ] Proper normals calculation

### 6.2 Other Formats
- [ ] OBJ export
- [ ] 3MF export (with metadata)

### 6.3 Export Options
- [ ] Scale on export
- [ ] Auto-orient for printing
- [ ] `(export-each "dir/")` — batch export

### 6.4 Pre-flight Check
- [ ] `(check-printable shape)` — manifold, overhangs, thin walls

**Deliverable**: Can export print-ready STL files.

---

## Phase 7: WebXR / VR

**Goal**: View models in VR on Quest 3.

### 7.1 WebXR Setup
- [ ] Enable WebXR on Three.js renderer
- [ ] VR button / enter VR mode
- [ ] Handle session start/end

### 7.2 HTTPS and QR
- [ ] Toggle to start HTTPS server
- [ ] Generate QR code with local URL
- [ ] Display in UI

### 7.3 VR Controls
- [ ] Controller: grab and rotate model
- [ ] Thumbstick: scale
- [ ] Trigger: select/highlight part

### 7.4 VR Settings
- [ ] `(set-vr :scale ...)` — magnification
- [ ] `(set-vr :table-height ...)` — where model floats
- [ ] Virtual grid/ruler

### 7.5 AR Passthrough (Quest 3)
- [ ] `immersive-ar` session type
- [ ] `(set-vr :mode :passthrough)`
- [ ] Anchor to real-world surface

**Deliverable**: Can view model at 1:1 scale in VR/AR before printing.

---

## Phase 8: Polish and Desktop

**Goal**: Packaged app, good UX.

### 8.1 Editor Improvements
- [ ] Syntax highlighting (CodeMirror or Monaco)
- [ ] Paredit-style editing
- [ ] Auto-complete for DSL functions
- [ ] Inline documentation

### 8.2 REPL Output
- [ ] Pretty-print results
- [ ] Show shape info on evaluation
- [ ] Error highlighting in editor

### 8.3 Tauri Packaging
- [ ] macOS .app bundle
- [ ] File open/save dialogs
- [ ] Recent files

### 8.4 Performance
- [ ] Web Worker for SCI evaluation
- [ ] Incremental geometry updates
- [ ] Level-of-detail for viewport

**Deliverable**: Polished desktop app ready for daily use.

---

## Future / Someday

- **Undo/redo** — state history
- **AI assistant** — natural language to DSL
- **Collaborative editing** — real-time sync
- **Plugin system** — user-defined namespaces
- **Animation** — parametric animation for visualization
- **Constraints** — parametric constraints solver
- **Standard parts library** — screws, nuts, bearings, gears
- **Slicer preview** — show layer-by-layer simulation

---

## Current Focus

**→ Phase 1: Proof of Concept**

Start with 1.1 (project setup) and proceed sequentially.

The goal is a working vertical slice as quickly as possible. Features can be basic — refinement comes later.