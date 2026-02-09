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

## Phase 2.5: Anchors and Navigation ✓

**Goal**: Named reference points and navigation commands for complex models.

### 2.5.1 Turtle State Stack ✓
- [x] Add `:state-stack` to turtle state (vector of saved poses)
- [x] `(push-state)` — save position/heading/up/pen-mode onto stack
- [x] `(pop-state)` — restore most recent saved pose from stack
- [x] `(clear-stack)` — clear stack without restoring
- [x] Stack saves only pose, not meshes/geometry (those persist)

### 2.5.2 Anchor System ✓
- [x] Add `:anchors` map to turtle state `{:name {:position :heading :up}}`
- [x] `(mark :name)` — save current position + heading + up with name
- [x] Calling `(mark :name)` twice overwrites the previous value
- [x] Anchors persist within the turtle state (cleared on new turtle)

### 2.5.3 Navigation Commands ✓
- [x] `(goto :name)` — move to anchor position AND adopt its heading/up
- [x] `(goto :name)` draws a line if pen-mode is `:on`
- [x] `(look-at :name)` — rotate heading to point toward anchor position
- [x] `(look-at :name)` also adjusts up to maintain orthogonality
- [x] `(path-to :name)` — orient toward anchor (implicit look-at), return path with `(f dist)`

### 2.5.4 Design Decisions
1. **Stack saves pose only**: position, heading, up, pen-mode. Meshes created during push/pop are kept.
2. **goto is oriented**: adopts the saved heading/up (no separate goto-oriented needed)
3. **mark overwrites**: calling twice with same name keeps the latest
4. **goto draws**: respects pen-mode like any movement command
5. **path-to orients and returns path**: implicitly does `look-at`, then returns `(f dist)` — works with `extrude`

**Deliverable**: Can mark points, navigate between them, and extrude paths to anchors. ✓

---

## Phase 3: Turtle Attachment System ← CURRENT

**Goal**: Enable the turtle to attach to geometry elements (meshes, faces, edges, vertices) and manipulate them using standard turtle commands.

### 3.1 Mesh Creation Pose ✓
- [x] Store turtle pose in mesh when created: `:creation-pose {:position :heading :up}`
- [x] Update `box`, `sphere`, `cyl`, `cone` to include creation pose
- [x] Update extrusion/sweep/loft to include creation pose
- [x] `(attach mesh)` positions turtle at mesh's creation pose

### 3.2 Inspection Commands ✓
- [x] `(list-faces mesh)` — return list of faces with id, normal, center, vertices
- [x] `(face-info mesh face-id)` — detailed info: normal, center, vertices, area, edges
- [ ] `(list-edges mesh)` — return list of edges with vertices and adjacent faces
- [x] `(flash-face mesh face-id)` — temporarily highlight face in viewport
- [ ] `(flash-edge mesh edge-id)` — temporarily highlight edge
- [ ] `(flash-vertex mesh vertex-id)` — temporarily highlight vertex

### 3.3 Viewport Highlighting ✓
- [x] Add highlight layer to Three.js scene
- [x] Support temporary highlighting (flash) with configurable duration
- [x] Distinct colors for face/edge/vertex highlights
- [x] Highlight clears on next evaluation

### 3.4 Attachment Commands ✓
- [x] `(attach mesh)` — push state, move to mesh creation pose
- [x] `(attach-face mesh face-id)` — push state, move to face center, heading = normal
- [ ] `(attach-edge mesh edge-id)` — push state, move to edge midpoint
- [ ] `(attach-vertex mesh vertex-id)` — push state, move to vertex position
- [x] `(detach)` — pop state, return to previous position
- [x] Track attached element in turtle state: `:attached {:type :face :mesh m :id :top}`

### 3.5 Mesh Manipulation (when attached to mesh) ✓
- [x] `f` moves the entire mesh along turtle heading
- [x] `th`, `tv`, `tr` rotate the entire mesh around centroid
- [x] `(scale factor)` — scale mesh uniformly from centroid
- [ ] `(scale [sx sy sz])` — scale non-uniformly
- [x] Mesh vertices are transformed in-place

### 3.6 Face Operations (when attached to face) ✓
- [x] `(f dist)` — extrude face along normal (positive = outward, negative = inward)
- [x] `(inset dist)` — create smaller/larger face inside, returns new face
- [ ] `(scale factor)` — scale face vertices from centroid
- [x] Face extrusion creates new side faces and updates mesh topology

### 3.7 Edge Operations (when attached to edge) — FUTURE
- [ ] `(bevel radius)` — round the edge
- [ ] `(chamfer dist)` — cut the edge at 45°

### 3.8 Vertex Operations (when attached to vertex) — FUTURE
- [ ] `(move [dx dy dz])` — move vertex, adjacent faces update

**Deliverable**: Can do `(attach-face mesh :top) (f 20) (detach)` to extrude a face. ✓

### Implementation Order

**Phase 3a: Foundation** ✓
1. [x] Creation pose in meshes (primitives + extrusions)
2. [x] Inspection commands (`list-faces`, `face-info`, `get-face`, `face-ids`)
3. [x] `flash-face` with Three.js overlay and distinct highlight colors

**Phase 3b-c: Attachment** ✓
1. [x] `attach`, `attach-face`, `detach`, `attached?`
2. [x] Mesh move via `(f dist)` when attached to mesh
3. [x] Face extrusion via `(f dist)` when attached to face

**Phase 3d: Advanced Face Ops** (partial)
1. [x] `inset`
2. [ ] Face cutting (draw shape on face)

### Design Decisions

1. **Stack-based**: `attach` pushes, `detach` pops. Simple mental model, supports nesting.

2. **Automatic push**: Can't "lose" position — detach always returns you.

3. **Creation pose**: Meshes remember where they were created for intuitive re-attachment.

4. **No multi-selection**: Use Clojure loops for batch operations:
   ```clojure
   (doseq [id [:top :bottom]]
     (attach-face mesh id) (inset 2) (detach))
   ```

5. **No undo**: Re-evaluate code with changes. The script IS the model.

6. **Face heading = normal**: `(f 10)` always extrudes outward, `(f -10)` inward.

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
- [x] `bounds` query

**Deliverable**: Can create complex CSG operations. ✓

---

## Phase 5: Anchors and Navigation (Partial) ✓

**Goal**: Named reference points for complex models.

### 5.1 Anchor System ✓
- [x] `(mark :name)` — saves position + heading + up (always oriented)
- [x] Store anchors in turtle state

### 5.2 Navigation ✓
- [x] `(goto :name)` — move and adopt heading/up
- [x] `(look-at :name)` — orient toward anchor
- [x] `(path-to :name)` — create path from current position to anchor

### 5.3 Bezier Curves ✓
- [x] `(bezier-to [x y z])` — auto control points (starts tangent to heading)
- [x] `(bezier-to [x y z] [c1])` — quadratic bezier with 1 control point
- [x] `(bezier-to [x y z] [c1] [c2])` — cubic bezier with 2 control points
- [x] `(bezier-to-anchor :name)` — bezier to named anchor
- [x] Integration with path/extrusion system (decomposes to f + rotations)

### 5.4 Arcs ✓
- [x] `(arc-h radius angle)` — horizontal arc (turn while moving)
- [x] `(arc-v radius angle)` — vertical arc (pitch while moving)
- [x] Integration with extrusion system (decomposes to f + rotations)

### 5.5 Resolution Control ✓
- [x] `(resolution :n value)` — fixed segment count (like OpenSCAD $fn)
- [x] `(resolution :a value)` — max angle per segment (like OpenSCAD $fa)
- [x] `(resolution :s value)` — max segment length (like OpenSCAD $fs)
- [x] Affects: arc-h, arc-v, bezier-to, circle, sphere, cyl, cone, round joints

**Deliverable**: Can create smooth curved paths between named points. ✓

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
- [x] `(bounds shape)`, `(center shape)` — plus `height`, `width`, `depth`, `top`, `bottom` helpers

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

## Phase 10: Live Manual

**Goal**: Interactive, bilingual documentation integrated into the app.

### 10.1 Manual Infrastructure

#### Data Structure
- [ ] `structure.edn` — Single source of truth for hierarchy, page IDs, and code examples
- [ ] `i18n/en.edn` — English text content (titles, descriptions, captions)
- [ ] `i18n/it.edn` — Italian text content
- [ ] Build-time validation: all structure keys must exist in all i18n files
- [ ] Runtime fallback: missing translation falls back to English

#### File Layout
```
docs/manual/
├── structure.edn              # Page hierarchy + code examples
├── i18n/
│   ├── en.edn                 # English text
│   └── it.edn                 # Italian text
└── build.clj                  # Validation script (optional)

src/ridley/manual/
├── core.cljs                  # State, navigation, loading
├── content.cljs               # Merged structure + i18n at runtime
├── components.cljs            # UI components
└── keywords.cljs              # Keyword → page mapping for F1 help
```

### 10.2 UI Components

#### Manual Panel
- [ ] Toolbar button toggles manual open/closed
- [ ] Manual replaces editor area (left panel) when open
- [ ] Read-only display with syntax-highlighted code blocks
- [ ] Viewport (right panel) remains visible and interactive

#### Navigation
- [ ] Breadcrumb: `Home > Part > Page` (each element clickable)
- [ ] "Up" link returns to parent section
- [ ] Cross-reference links between pages (see-also)
- [ ] Table of contents (collapsible sidebar or top-level page)

#### Language Switcher
- [ ] Toggle EN/IT in manual header
- [ ] Preference saved to localStorage
- [ ] Auto-detect from browser locale on first visit

### 10.3 Example Blocks

#### Visual Design
- [ ] Code displayed in styled box with syntax highlighting
- [ ] Caption/title above code block
- [ ] Description text below code block
- [ ] **Active indicator**: border highlight (e.g., orange glow) when example is running

#### Interaction Buttons
- [ ] **Copy** button — copies code to editor, closes manual
- [ ] **Run** button — executes code in current context, keeps manual open
- [ ] Buttons appear on hover or always visible (TBD)

#### Auto-Execution
- [ ] When page loads or scrolls to reveal an example, auto-execute the **first visible example**
- [ ] Intersection Observer tracks which examples are in viewport
- [ ] Only one example runs at a time (new visible example replaces previous)
- [ ] Example block shows "active" state while its result is in viewport
- [ ] Explicit "Run" button needed only when multiple examples visible simultaneously

### 10.4 Editor Integration

#### F1 Context Help
- [ ] `keywords.cljs` maps DSL keywords to manual page IDs
- [ ] F1 with cursor on keyword opens corresponding manual page
- [ ] If no match, show "No documentation for X" toast
- [ ] Works for: commands (`f`, `th`, `extrude`), shapes (`circle`, `rect`), macros (`path`, `shape`)

#### Keyword Mapping Example
```clojure
{:f :the-turtle
 :th :the-turtle
 :tv :the-turtle
 :tr :the-turtle
 :extrude :extrude
 :extrude-closed :extrude-closed
 :circle :builtin-shapes
 :rect :builtin-shapes
 :path :paths
 :shape :drawing-shapes
 :register :register
 :mesh-union :csg
 :mesh-difference :csg
 ...}
```

### 10.5 Manual Content Structure

#### Table of Contents

**Part I: Getting Started**
1. Hello Ridley
2. The Turtle
3. Clojure Basics (sidebar)

**Part II: 2D Shapes**
4. Built-in Shapes
5. Drawing Shapes
6. Shape Transformations

**Part III: From 2D to 3D**
7. Extrude
8. Paths
9. Extrude-Closed
10. Loft
11. Revolve

**Part IV: 3D Primitives**
12. Box, Sphere, Cylinder, Cone
13. Register and Scene Management

**Part V: Curves and Navigation**
14. Arcs
15. Bezier Curves
16. Anchors
17. Resolution Control

**Part VI: Modifying Geometry**
18. Face Inspection
19. Attach and Detach
20. Face Operations
21. Mesh Manipulation

**Part VII: Boolean Operations**
22. CSG Operations
23. Manifold Validation
24. Convex Hull

**Part VIII: Text**
25. Text Shapes
26. Text on Path

**Part IX: Workflow**
27. State Stack
28. Joint Modes
29. STL Export
30. WebXR (when ready)

**Appendices**
- A. Command Reference
- B. Clojure Essentials
- C. Editor Keybindings

### 10.6 Implementation Order

**Phase 10a: Foundation**
1. [ ] Manual state atom (`open?`, `current-page`, `lang`, `history`)
2. [ ] Load `structure.edn` + i18n files at startup
3. [ ] Basic panel toggle (button + left panel replacement)
4. [ ] Simple page rendering (title + content + code blocks)

**Phase 10b: Navigation**
1. [ ] Breadcrumb component
2. [ ] Page links (internal navigation)
3. [ ] History (back button)
4. [ ] Language switcher

**Phase 10c: Example Interaction**
1. [ ] Run button executes code
2. [ ] Copy button copies to editor
3. [ ] Active state styling (border highlight)
4. [ ] Auto-execution on scroll (Intersection Observer)

**Phase 10d: Editor Integration**
1. [ ] Build keyword → page-id map
2. [ ] F1 keybinding in CodeMirror
3. [ ] Word-at-cursor detection
4. [ ] Open manual to correct page

**Phase 10e: Content**
1. [ ] Write `structure.edn` with all pages and examples
2. [ ] Write `en.edn` (English content)
3. [ ] Write `it.edn` (Italian content)
4. [ ] Validate completeness

### 10.7 Design Decisions

1. **Markdown vs EDN**: Pure EDN for content. Simpler parsing, no external dependencies, content is data.

2. **Code in structure, text in i18n**: Examples are language-agnostic, only descriptions need translation.

3. **Auto-run first example**: Provides immediate visual context when landing on a page.

4. **Single active example**: Avoids confusion about which code produced the current viewport state.

5. **F1 for help**: Familiar pattern (VS Code, most IDEs). Low friction to access docs.

6. **Manual replaces editor**: Clean separation — you're either coding or reading. No split attention.

7. **Fallback to English**: Incomplete translations don't break the app.

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

**→ Phase 3: Turtle Attachment System** (ACTIVE)

The core turtle system, generative operations, boolean operations, and anchor/navigation system are complete. Now implementing the attachment system for face-based modeling.

**Recent completions:**
- ✓ `extrude-closed` with path pre-processing for manifold torus-like meshes
- ✓ Manifold WASM integration (union, difference, intersection)
- ✓ Scene registry with named meshes and visibility control
- ✓ STL export
- ✓ WebXR basic support
- ✓ Joint modes for extrusion corners (flat, round, tapered)
- ✓ CodeMirror editor with syntax highlighting and paredit
- ✓ State stack (`push-state`, `pop-state`, `clear-stack`)
- ✓ Anchors and navigation (`mark`, `goto`, `look-at`, `path-to`)
- ✓ **Phase 3a: Foundation** — creation pose in meshes, inspection commands, face highlighting
- ✓ **Phase 3b-c: Attachment** — `attach`, `attach-face`, `detach`, mesh move, face extrusion
- ✓ **Phase 3d: Face inset** — `inset` operation
- ✓ **Phase 3.5: Mesh manipulation** — `th`, `tv`, `tr` rotate mesh, `scale` mesh
- ✓ **Convex Hull** — `mesh-hull` via Manifold
- ✓ **Text Shapes** — `text-shape`, `text-shapes` via opentype.js with bundled Roboto fonts

**Current sprint:**
1. [ ] Face cutting (draw shape on face)
2. [ ] Non-uniform scale `(scale [sx sy sz])`
3. [ ] STL import — load external STL as reusable mesh in Ridley
4. [x] 2D shape booleans — `(shape-union a b)`, `(shape-difference a b)`, `(shape-intersection a b)`, `(shape-xor a b)`, `(shape-offset shape delta)` via clipper2-js. Shapes with holes extrude correctly (holes-aware extrusion with caps, corners, and tunnel faces).

**Recently completed:**
- ✓ **Multi-shape XOR & Loft Improvements** — `shape-xor` returns vector of shapes for disconnected regions; `shape-offset`, `loft`, `bloft`, `revolve`, `stamp` accept shape vectors. Loft-from-path generates proper start/end cap meshes. Fixed clipper2-js offsetPolygon bug via monkey-patch. Editor selection fix (opacity-based).
- ✓ **2D Shape Booleans** — `shape-union`, `shape-difference`, `shape-intersection`, `shape-xor`, `shape-offset` via clipper2-js. Holes-aware extrusion pipeline (simple + complex paths with corners). Shape transforms propagate through holes.
- ✓ **Library/Namespace System** — User-defined reusable code libraries with localStorage persistence, SCI namespace injection (`shapes/hexagon`), topological sort for dependency ordering, cascade deactivation, drag & drop reordering, import/export as `.clj` files
- ✓ **Bounds functions** — `bounds`, `height`, `width`, `depth`, `top`, `bottom` for bounding box queries; `info` now includes bounds data
- ✓ **`attach!` macro** — in-place transform of registered meshes by keyword: `(attach! :name (f 20))`. Removed redundant `clone` macro (attach is already functional)
- ✓ **AI Tier System** — Auto-detection of model tier, tier-aware prompts (Tier 1: code-only, Tier 2+: JSON with clarification support), script context for Tier 2+, Settings UI dropdown
- ✓ **Arc Commands** — `arc-h` and `arc-v` for smooth curved paths
- ✓ **Bezier Commands** — `bezier-to` and `bezier-to-anchor` for bezier curves
- ✓ **Resolution Control** — `resolution` function inspired by OpenSCAD (`$fn`/`$fa`/`$fs`)
- ✓ **Text on Path** — `text-on-path` places extruded text along a curved path, with each letter oriented tangent to the curve
- ✓ **Path Utilities** — `follow-path` for visualizing paths, `path-total-length` for arc-length calculation, `sample-path-at-distance` for path sampling

The turtle attachment paradigm unifies all 3D operations under the familiar turtle metaphor: attach to an element, use turtle commands to manipulate it, detach when done.

---

## Enhancement Ideas

### Attach on mesh collections (structure-preserving)

Extend `attach` to work on nested vectors of meshes, transforming all meshes while preserving the nesting structure. This enables treating function-composed groups as rigid bodies:

```clojure
(defn branch [l] ...)              ;; returns a mesh
(defn ring [l n] ...)              ;; returns [mesh mesh ...]
(defn tree [l h rings branches]    ;; returns [[mesh ...] [mesh ...] ...]
  (for [i (range rings)]
    (do (f (/ h rings)) (ring l branches))))

(def t (tree 50 100 5 5))
(register T (attach t (f 20) (th 45)))
;; t2 has same nested structure, all vertices transformed
```

Key principles:
- **Preserve structure**: `attach` walks recursively, transforms meshes in-place, returns same nesting shape. No flattening.
- **Pivot = current turtle position**: the turtle's position at call time is the transformation pivot.
- **Composability**: since structure is preserved, the result can be passed to another `attach` or used in further function composition.
- **Revisit `register` flatten**: the current flatten in `register` is a workaround. Ideally the registry and viewport should walk nested structures for rendering without flattening, so that `(hide :T 2)` can address sub-groups by index path.