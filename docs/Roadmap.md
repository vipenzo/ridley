# Ridley — Development Roadmap

## Phase 1: Proof of Concept ✓

**Goal**: Minimal loop working — edit code, see 3D result.

### 1.1 Project Setup ✓
- [x] Initialize shadow-cljs project
- [x] Configure deps.edn with SCI
- [x] Setup package.json with Three.js
- [x] Basic HTML shell with editor (textarea) and canvas

### 1.2 Turtle Core ✓
- [x] Immutable turtle state: `{:position :heading :up :pen-down? :geometry}`
- [x] Basic commands: `f`, `b`, `th`, `tv`, `tr`
- [x] Pen control: `pen-up`, `pen-down`
- [x] Geometry accumulation (line segments)

### 1.3 SCI Integration ✓
- [x] Setup SCI context with turtle functions exposed
- [x] Evaluate user code string → geometry data
- [x] Error handling and display

### 1.4 Three.js Viewport ✓
- [x] Basic scene setup (camera, lights, renderer)
- [x] Orbit controls (rotate, zoom, pan)
- [x] Convert turtle geometry → Three.js lines
- [x] Auto-fit camera to geometry

### 1.5 Basic UI ✓
- [x] Split layout: editor left, viewport right
- [x] Cmd+Enter to evaluate
- [x] Display errors below editor

**Deliverable**: Can write `(f 50) (th 90) (f 50)` and see an L-shape in 3D. ✓

---

## Phase 2: Shapes and Primitives ✓

**Goal**: Generate solid geometry, not just lines.

### 2.1 Path and Shape ✓
- [x] `(path ...)` constructor
- [x] `(shape ...)` constructor with auto-close
- [ ] Dense syntax parser for strings (deferred)
- [ ] Fillet/chamfer in path context (deferred)

### 2.2 3D Primitives ✓
- [x] `box`, `sphere`, `cyl`, `cone`
- [x] Placement at turtle position/orientation
- [x] Custom mesh generation

### 2.3 Generative Operations ✓
- [x] `extrude` — linear extrusion
- [x] `revolve` — revolution around axis
- [x] `sweep` — extrude along path
- [x] `loft` — transition between profiles

### 2.4 Viewport Improvements ✓
- [x] Render solid meshes (not just lines)
- [x] Basic material/lighting
- [x] Grid and axes display

**Deliverable**: Can create a vase with `(revolve profile axis)`. ✓

---

## Phase 3: Face-Based Modeling ← CURRENT

**Goal**: Implement the push/pull paradigm for intuitive 3D editing.

### 3.1 Enhanced Mesh Data Structure
- [ ] Add face metadata to meshes (id, normal, center, vertices)
- [ ] Semantic face naming for primitives (:top, :bottom, :side-N)
- [ ] Numeric face IDs for complex meshes
- [ ] Face lookup functions

### 3.2 Face Selection System
- [ ] `(list-faces mesh)` — enumerate all faces with info
- [ ] `(select mesh face-id)` — highlight face in viewport
- [ ] Face highlighting in Three.js (distinct color/material)
- [ ] Click-to-select in viewport (optional)

### 3.3 New Pen Modes
- [ ] Refactor pen state: `pen-mode` replaces `pen-down?`
- [ ] `(pen :off)` — no drawing
- [ ] `(pen :2d)` — draw lines (current behavior)
- [ ] `(pen face-id)` — draw on selected face

### 3.4 2D Drawing on Faces
- [ ] Transform turtle to face-local coordinates
- [ ] Profile accumulation in `pending-profile`
- [ ] 2D primitives on face: `circle`, `rect`, `polygon`

### 3.5 Face Extrusion
- [ ] `(f dist)` when pen is on face triggers extrusion
- [ ] Positive dist = add material (union with mesh)
- [ ] Negative dist = remove material (subtract from mesh)
- [ ] Update mesh topology after extrusion

**Deliverable**: Can create a box with holes using `(pen :top) (circle 10) (f -50)`.

---

## Phase 4: Boolean Operations (Future)

**Goal**: CSG operations for cases where face-based modeling isn't sufficient.

### 4.1 Manifold Integration
- [ ] Load Manifold WASM module
- [ ] Convert mesh ↔ Manifold mesh
- [ ] `union`, `subtract`, `intersect`

### 4.2 Integrated Modifiers
- [ ] Boolean with fillet: `(subtract a b :fillet 2)`
- [ ] Boolean with chamfer: `(union a b :chamfer 1)`

### 4.3 Validation
- [ ] `watertight?` check
- [ ] `volume` calculation
- [ ] `bounds` query

**Deliverable**: Can create complex CSG operations when face-based isn't enough.

---

## Phase 5: Anchors and Navigation

**Goal**: Named reference points for complex models.

### 5.1 Anchor System
- [ ] `(mark :name)` — position only
- [ ] `(mark-oriented :name)` — position + heading
- [ ] Store anchors in scene context

### 5.2 Navigation
- [ ] `(goto :name)` — move, keep heading
- [ ] `(goto-oriented :name)` — move and adopt heading
- [ ] `(look-at :name)` — orient toward

### 5.3 Bezier Curves
- [ ] `(bezier-to [x y z])`
- [ ] `(bezier-to [x y z] :heading [...])`
- [ ] `(bezier-to-anchor :name)`
- [ ] `(bezier-to-oriented :name)`

### 5.4 Arcs
- [ ] `(arc-h radius angle)`
- [ ] `(arc-v radius angle)`

**Deliverable**: Can create smooth curved paths between named points.

---

## Phase 6: Viewport and Visibility

**Goal**: Control what's displayed, prepare for export.

### 6.1 Scene Management
- [ ] Track all defined shapes by name
- [ ] `(show shape)`, `(hide shape)`
- [ ] `(solo shape)`, `(show-all)`
- [ ] Wireframe mode

### 6.2 Visual Aids
- [ ] Toggle axes display
- [ ] Toggle grid
- [ ] Show anchors as small markers
- [ ] Show paths as lines

### 6.3 Inspection
- [ ] `(inspect shape)` — zoom to fit
- [ ] `(bounds shape)`, `(volume shape)`
- [ ] `(center shape)`

### 6.4 Fuzzy Search
- [ ] `(find "pattern")` — search defined names
- [ ] UI: search box that filters and highlights

**Deliverable**: Can selectively show parts of a complex model.

---

## Phase 7: Export

**Goal**: Generate files for 3D printing.

### 7.1 STL Export
- [ ] Binary STL export
- [ ] ASCII STL export
- [ ] Proper normals calculation

### 7.2 Other Formats
- [ ] OBJ export
- [ ] 3MF export (with metadata)

### 7.3 Export Options
- [ ] Scale on export
- [ ] Auto-orient for printing
- [ ] `(export-each "dir/")` — batch export

### 7.4 Pre-flight Check
- [ ] `(check-printable shape)` — manifold, overhangs, thin walls

**Deliverable**: Can export print-ready STL files.

---

## Phase 8: WebXR / VR

**Goal**: View models in VR on Quest 3.

### 8.1 WebXR Setup
- [ ] Enable WebXR on Three.js renderer
- [ ] VR button / enter VR mode
- [ ] Handle session start/end

### 8.2 HTTPS and QR
- [ ] Toggle to start HTTPS server
- [ ] Generate QR code with local URL
- [ ] Display in UI

### 8.3 VR Controls
- [ ] Controller: grab and rotate model
- [ ] Thumbstick: scale
- [ ] Trigger: select/highlight part

### 8.4 VR Settings
- [ ] `(set-vr :scale ...)` — magnification
- [ ] `(set-vr :table-height ...)` — where model floats
- [ ] Virtual grid/ruler

### 8.5 AR Passthrough (Quest 3)
- [ ] `immersive-ar` session type
- [ ] `(set-vr :mode :passthrough)`
- [ ] Anchor to real-world surface

**Deliverable**: Can view model at 1:1 scale in VR/AR before printing.

---

## Phase 9: Polish and Desktop

**Goal**: Packaged app, good UX.

### 9.1 Editor Improvements
- [ ] Syntax highlighting (CodeMirror or Monaco)
- [ ] Paredit-style editing
- [ ] Auto-complete for DSL functions
- [ ] Inline documentation

### 9.2 REPL Output
- [ ] Pretty-print results
- [ ] Show shape info on evaluation
- [ ] Error highlighting in editor

### 9.3 Tauri Packaging
- [ ] macOS .app bundle
- [ ] File open/save dialogs
- [ ] Recent files

### 9.4 Performance
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

**→ Phase 3: Face-Based Modeling**

The core turtle system and generative operations are working. Now implementing the face-based push/pull paradigm:

1. **3.1 Mesh data structure** — Add face metadata to track selectable faces
2. **3.2 Face selection** — Visual highlighting and REPL commands
3. **3.3 Pen modes** — Refactor pen state for face drawing
4. **3.4 2D on faces** — Draw profiles on selected faces
5. **3.5 Face extrusion** — Push/pull to add or remove material

This is the key differentiating feature that makes Ridley intuitive for 3D modeling.