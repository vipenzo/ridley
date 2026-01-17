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
- [x] `(path ...)` constructor with loops and conditionals
- [x] `(shape ...)` constructor with auto-close
- [x] Path pre-processing for closed extrusions (corner shortening)
- [ ] Dense syntax parser for strings (deferred)
- [ ] Fillet/chamfer in path context (partial - corners handled in extrude-closed)

### 2.2 3D Primitives ✓
- [x] `box`, `sphere`, `cyl`, `cone`
- [x] Placement at turtle position/orientation
- [x] Custom mesh generation

### 2.3 Generative Operations ✓
- [x] `extrude` — linear extrusion with turtle movements
- [x] `extrude-closed` — closed torus-like extrusion (manifold)
- [x] `revolve` — revolution around axis
- [x] `sweep` — extrude along path
- [x] `loft` — transition between profiles with transform function

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
- [ ] `(pen :3d :at [p] :normal [n])` — draw on arbitrary plane
- [ ] `(pen :3d @anchor)` — draw on plane defined by anchor
- [ ] `(pen face-id)` — draw on selected face
- [ ] `(pen face-id :at [u v])` — draw on face with offset

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

## Phase 4: Boolean Operations ✓

**Goal**: CSG operations for cases where face-based modeling isn't sufficient.

### 4.1 Manifold Integration ✓
- [x] Load Manifold WASM module (v3.0 via CDN)
- [x] Convert mesh ↔ Manifold mesh
- [x] `mesh-union`, `mesh-difference`, `mesh-intersection`

### 4.2 Validation ✓
- [x] `manifold?` check
- [x] `mesh-status` — detailed validation info
- [x] Volume calculation (via Manifold)

### 4.3 Integrated Modifiers (Future)
- [ ] Boolean with fillet: `(subtract a b :fillet 2)`
- [ ] Boolean with chamfer: `(union a b :chamfer 1)`
- [ ] `bounds` query

**Deliverable**: Can create complex CSG operations. ✓

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

## Phase 6: Viewport and Visibility (Partial) ✓

**Goal**: Control what's displayed, prepare for export.

### 6.1 Scene Management ✓
- [x] Scene registry for named meshes
- [x] `(register name mesh)` — create named object
- [x] `(show! name)`, `(hide! name)` — visibility control
- [x] `(show-all!)`, `(hide-all!)`, `(show-only-registered!)`
- [x] Toggle view button (All / Objects only)
- [ ] Wireframe mode

### 6.2 Visual Aids ✓
- [x] Grid display
- [x] Axes display
- [ ] Show anchors as small markers
- [x] Show turtle path as lines

### 6.3 Inspection (Partial)
- [ ] `(inspect shape)` — zoom to fit
- [x] Volume calculation (via Manifold)
- [ ] `(bounds shape)`, `(center shape)`

### 6.4 Fuzzy Search
- [ ] `(find "pattern")` — search defined names
- [ ] UI: search box that filters and highlights

**Deliverable**: Can selectively show parts of a complex model. ✓

---

## Phase 7: Export (Partial) ✓

**Goal**: Generate files for 3D printing.

### 7.1 STL Export ✓
- [x] Binary STL export
- [x] Export via `(save-stl mesh)` or `(save-stl (visible-meshes))`
- [x] Proper normals calculation

### 7.2 Other Formats
- [ ] OBJ export
- [ ] 3MF export (with metadata)

### 7.3 Export Options
- [ ] Scale on export
- [ ] Auto-orient for printing
- [ ] `(export-each "dir/")` — batch export

### 7.4 Pre-flight Check (Partial)
- [x] `(manifold? mesh)` — check if valid manifold
- [x] `(mesh-status mesh)` — detailed validation
- [ ] `(check-printable shape)` — overhangs, thin walls

**Deliverable**: Can export print-ready STL files. ✓

---

## Phase 8: WebXR / VR (Partial) ✓

**Goal**: View models in VR on Quest 3.

### 8.1 WebXR Setup ✓
- [x] Enable WebXR on Three.js renderer
- [x] VR button / enter VR mode
- [x] Handle session start/end
- [x] WebXR polyfill included

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

**→ Phase 3: Face-Based Modeling** (deferred for now)

The core turtle system, generative operations, and boolean operations are working well.

**Recent completions:**
- ✓ `extrude-closed` with path pre-processing for manifold torus-like meshes
- ✓ Manifold WASM integration (union, difference, intersection)
- ✓ Scene registry with named meshes and visibility control
- ✓ STL export
- ✓ WebXR basic support

**Next priorities:**
1. Complete remaining loft/extrude edge cases
2. Improve error messages and validation
3. Add more shape transformations
4. Face-based modeling (Phase 3) when ready

The face-based push/pull paradigm remains a key differentiating feature for future development.