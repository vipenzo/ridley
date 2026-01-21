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

### Corner Junction System (joint-mode)

When extruding along a path with rotations (th, tv, tr), the system must handle corners where segments meet. The `joint-mode` setting controls how these corners are constructed.

**The Problem:**
Two consecutive segments meeting at a corner can't simply butt up against each other - this would create self-intersecting geometry that breaks boolean operations.

**The Solution:**
All joint modes use the same segment shortening strategy. The difference is only in how the junction geometry fills the gap.

```
SEGMENT SHORTENING (same for all modes):
=========================================

Given a path: (f 10) (th 90) (f 10)
And a shape with radius R (e.g., circle radius 2)

Without shortening, segments would intersect at the corner:

    Segment A ──────────────┐
                            │ ← intersection!
                    ┌───────┴── Segment B
                    │

With shortening, segments are pulled back by R from the corner:

                   corner
    Segment A ─────┤     │
                   │ gap │ ← junction fills this
                   │     ├───── Segment B

    ◄──── R ────►◄─ R ──►

The gap is filled by the junction mesh, which varies by mode.
```

**Joint Modes:**

```
:flat (default)
===============
No intermediate rings. The mesh connects end-ring of segment A
directly to start-ring of segment B.

Ring sequence: [start-A] → [end-A] → [start-B] → [end-B]
                              ↑          ↑
                      (shortened)  (shortened)
                              └────┬─────┘
                           direct mesh connection
                           (creates triangular junction)

The key insight: end-A and start-B are at DIFFERENT positions
(both offset by R from corner, but in different directions).
The mesh between them forms the flat bevel.


:round
======
Arc of intermediate rings rotating from old heading to new heading.

Ring sequence: [start-A] → [end-A] → [arc-1] → [arc-2] → ... → [start-B] → [end-B]

The arc rings are generated by rotating end-ring around a pivot point
(the point where both cylinders would touch tangentially).

                    arc rings
                    ╭───╮
    Segment A ─────╯     ╰───── Segment B
                   (smooth curve)


:tapered
========
Single intermediate ring at corner, scaled along the angle bisector
to maintain cross-section continuity.

Ring sequence: [start-A] → [end-A] → [tapered-ring] → [start-B] → [end-B]

                 scaled ring
    Segment A ─────◇───── Segment B
                   (single intermediate)
```

**Implementation Details:**

```clojure
;; In extrude-from-path loop:

;; 1. Segments are ALWAYS shortened (for all joint modes)
shorten-start = (:shorten-start seg)  ; 0 for first segment
shorten-end = (:shorten-end seg)      ; 0 for last segment
effective-dist = dist - shorten-start - shorten-end

;; 2. Create rings at shortened positions
start-ring = (stamp-shape s1 shape)           ; at start-pos
end-ring = (stamp-shape s2 shape)             ; at end-pos (shortened)

;; 3. Calculate corner position
corner-pos = end-pos + heading * shorten-end  ; actual corner vertex

;; 4. Generate junction rings based on mode
corner-rings = (case joint-mode
                 :flat []  ; NO extra rings - direct connection
                 :round (generate-round-corner-rings ...)
                 :tapered (generate-tapered-corner-rings ...))

;; 5. Position next segment
next-start-pos = corner-pos + new-heading * next-shorten-start

;; 6. Collect rings
new-rings = [... start-ring end-ring ...corner-rings]
```

**Critical Points:**

1. **:flat has NO intermediate rings** - The mesh directly connects end-ring to the next start-ring. This works because both rings are at positions that "touch" the corner (each offset by R in their respective directions).

2. **All modes use identical shortening** - The segment lengths are the same regardless of joint mode. Only the junction geometry differs.

3. **No self-intersection** - Because segments are shortened and the junction fills the gap, there's no overlapping geometry. This is essential for `mesh-difference` and other boolean operations.

4. **Closed paths work the same way** - `extrude-closed-from-path` uses identical logic but wraps the last ring back to the first.

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

## Anchors and Navigation

The anchor system provides named reference points for navigation in complex models.

### Turtle State Stack

The turtle maintains a **stack of saved poses**. This enables:
- Temporary movements with automatic return
- Branching constructions (L-systems, trees)
- Attachment/detachment without losing original position

```clojure
;; Pose structure (what gets saved on the stack)
{:position [x y z]
 :heading [x y z]
 :up [x y z]
 :pen-mode :on}

;; Stack in turtle state
{:state-stack [pose1 pose2 ...]}  ; vector used as stack (conj/peek/pop)

;; Commands
(push-state)   ; Save current pose onto stack
(pop-state)    ; Restore most recent saved pose
```

**Important**: The stack saves only the pose, not meshes or geometry. Any meshes created between push and pop are kept.

### Anchor System

Anchors are named poses stored in the turtle state:

```clojure
;; Anchors in turtle state
{:anchors {:start {:position [0 0 0]
                   :heading [1 0 0]
                   :up [0 0 1]}
           :corner {:position [100 0 0]
                    :heading [0 1 0]
                    :up [0 0 1]}}}

;; Commands
(mark :name)      ; Save current position + heading + up with name
                  ; Overwrites if name already exists
```

### Navigation Commands

```clojure
(goto :name)      ; Move to anchor position AND adopt its heading/up
                  ; Draws a line if pen-mode is :on

(look-at :name)   ; Rotate heading to point toward anchor position
                  ; Adjusts up to maintain orthogonality
                  ; Does not move

(path-to :name)   ; Orient toward anchor (implicit look-at), then return
                  ; a path with single (f dist) to reach it
                  ; Useful for: (extrude (circle 5) (path-to :target))
```

### Design Decisions

1. **Stack saves pose only**: position, heading, up, pen-mode. Meshes created during push/pop persist.

2. **goto is oriented**: Adopts the saved heading/up. No separate `goto-oriented` needed.

3. **mark overwrites**: Calling `(mark :foo)` twice keeps only the latest value.

4. **goto draws**: Respects pen-mode like any movement command. If pen is on, draws a line.

5. **look-at adjusts up**: When rotating heading toward a point, up is recalculated to stay orthogonal while preserving the original up direction as much as possible.

6. **path-to orients and returns path**: Implicitly does a `look-at` to orient the turtle toward the anchor, then returns a path with `(f dist)`. This ensures extrusions go in the correct direction:
   ```clojure
   (mark :target)
   ;; ... do other things ...
   (extrude (circle 5) (path-to :target))  ; Orients toward :target, then extrudes
   ```

---

## Phase 3: Turtle Attachment System

Phase 3 introduces the ability to "attach" the turtle to existing geometry elements (meshes, faces, edges, vertices) and manipulate them using standard turtle commands.

### Core Concepts

#### Attachment System

When the turtle attaches to a geometry element, it:
1. **Pushes** current state onto the stack (automatic)
2. **Moves** to the element's position/orientation
3. **Enters attachment mode** where commands affect the attached element

```clojure
;; Attach commands (all push state automatically)
(attach mesh)              ; Attach to mesh at its creation pose
(attach-face mesh :top)    ; Attach to face center, heading = normal
(attach-edge mesh edge-id) ; Attach to edge midpoint
(attach-vertex mesh v-id)  ; Attach to vertex position

;; Detach (pops state, returns to previous position)
(detach)
```

#### Mesh Creation Pose

When a mesh is created, it remembers the turtle's pose at that moment:

```clojure
;; Mesh structure with creation pose
{:type :mesh
 :primitive :box
 :vertices [...]
 :faces [...]
 :creation-pose {:position [0 0 0]
                 :heading [1 0 0]
                 :up [0 0 1]}}
```

This allows `(attach mesh)` to position the turtle exactly where it was when the mesh was created, enabling intuitive modifications.

### Element-Specific Behavior

#### When Attached to a Mesh
- `f`, `th`, `tv`, `tr` → move/rotate the entire mesh
- `(scale factor)` → scale the mesh uniformly
- `(scale [sx sy sz])` → scale non-uniformly

#### When Attached to a Face
- Turtle position = face centroid
- Turtle heading = face normal (outward)
- Turtle up = first edge direction (for consistent orientation)
- `f` → extrude the face (positive = add material, negative = subtract)
- `(inset dist)` → create smaller face inside (or larger if negative)
- `(scale factor)` → scale face vertices from centroid

#### When Attached to an Edge
- Turtle position = edge midpoint
- Turtle heading = along the edge
- Turtle up = average of adjacent face normals
- `(bevel radius)` → bevel the edge
- `(chamfer dist)` → chamfer the edge

#### When Attached to a Vertex
- Turtle position = vertex position
- `(move [dx dy dz])` → move the vertex
- Adjacent faces update automatically

### Face Operations

#### Extrusion
```clojure
(attach-face mesh :top)
(f 20)        ; Extrude top face by 20 units (adds material)
(f -10)       ; Extrude inward (subtracts material / creates pocket)
(detach)
```

#### Inset
```clojure
(attach-face mesh :top)
(inset 5)     ; Create smaller face 5 units inward from edges
(f 10)        ; Extrude the inset face
(detach)
```

#### Face Cutting (Future)
```clojure
(attach-face mesh :top)
(circle 10)   ; Draw circle on face → creates new inner face
(f -50)       ; Extrude circle inward → creates hole
(detach)
```

### Inspection Commands

```clojure
;; List all faces of a mesh
(list-faces mesh)
; => [{:id :top :normal [0 0 1] :center [0 0 25] :vertices [0 1 2 3]}
;     {:id :bottom :normal [0 0 -1] :center [0 0 0] :vertices [4 5 6 7]}
;     ...]

;; Get detailed info about a specific face
(face-info mesh :top)
; => {:id :top
;     :normal [0 0 1]
;     :center [0 0 25]
;     :vertices [0 1 2 3]
;     :vertex-positions [[x y z] [x y z] ...]
;     :area 2500
;     :edges [[0 1] [1 2] [2 3] [3 0]]}

;; Highlight a face in the viewport (temporary flash)
(flash-face mesh :top)

;; List edges
(list-edges mesh)
; => [{:id 0 :vertices [0 1] :faces [:top :front]}
;     ...]
```

### Design Decisions

1. **Stack-based attachment**: Using push/pop instead of explicit save/restore simplifies the mental model and naturally handles nested operations.

2. **Automatic push on attach**: `(attach ...)` always pushes state, `(detach)` always pops. This ensures you can't "lose" your position.

3. **Mesh remembers creation pose**: Enables intuitive "go back to where I made this" workflow.

4. **No multi-selection**: Complex multi-element operations can be expressed with Clojure:
   ```clojure
   (doseq [id [:top :bottom :left :right]]
     (attach-face mesh id)
     (inset 2)
     (detach))
   ```

5. **No built-in undo**: The language itself is the undo mechanism — re-evaluate with changes.

6. **Face orientation**: When attached to a face, heading is always the outward normal. This makes `(f 10)` consistently mean "extrude outward" and `(f -10)` mean "extrude inward/cut".

## Desktop-Headset Sync (WebRTC)

Ridley supports real-time synchronization between a desktop browser (where you edit code) and a VR/AR headset (where you view the model). This enables a workflow where you write code on your computer while seeing the 3D result immersively on a Quest or other WebXR-capable device.

### Architecture

```
┌─────────────────┐                      ┌─────────────────┐
│  Desktop        │◄───── WebRTC ──────►│  VR/AR Headset  │
│  (host/editor)  │      DataChannel     │  (client/viewer)│
└─────────────────┘                      └─────────────────┘
         │                                        │
         └────────► PeerJS Cloud ◄────────────────┘
                   (signaling only)
```

- **PeerJS** abstracts WebRTC complexity (signaling, ICE negotiation)
- **Signaling** goes through PeerJS public server (only for initial handshake)
- **Data** flows directly peer-to-peer (low latency on LAN)
- **No server required** — works entirely in browsers

### Implementation

**Files:**
- `src/ridley/sync/peer.cljs` — PeerJS wrapper (host/join/send)
- `src/ridley/core.cljs` — UI integration (Share/Link buttons, status display)

**State (`peer-state` atom):**
```clojure
{:peer nil              ; PeerJS Peer instance
 :connections #{}       ; Set of DataConnection (host can have multiple)
 :connection nil        ; Single DataConnection (client has one)
 :role nil              ; :host or :client
 :status :disconnected  ; :disconnected, :waiting, :connecting, :connected, :error
 :peer-id nil           ; "ridley-XXXXXX" (host only)
 :on-script-received fn ; Callback when script arrives
 :on-repl-received fn   ; Callback when REPL command arrives
 :on-status-change fn   ; Callback for UI updates
 :on-clients-change fn  ; Callback when client count changes
 :on-client-connected fn} ; Callback for initial sync
```

### Protocol Messages

**Host → Client:**
```clojure
;; Script update (definitions panel content)
{:type "script-update"
 :definitions "..."
 :timestamp 1234567890}

;; REPL command (single expression)
{:type "repl-command"
 :command "(f 10)"
 :timestamp 1234567890}

;; Keepalive
{:type "ping"}
```

**Client → Host:**
```clojure
;; Acknowledgment
{:type "script-ack"
 :timestamp 1234567890}

;; Keepalive response
{:type "pong"}
```

### User Flow

**Host (Desktop):**
1. Click "Share" button
2. Modal shows 6-character code (e.g., `ABC123`)
3. Status: "Waiting · ABC123"
4. When client connects: "1 client · ABC123"
5. Every edit (debounced 500ms) sends `script-update`
6. Every REPL command sends `repl-command`

**Client (Headset):**
1. Click "Link" button, enter code (or use URL `?peer=ridley-ABC123`)
2. Status: "Connected to ABC123"
3. Receives initial script, evaluates, renders 3D
4. On each `script-update`: re-evaluate definitions, update viewport
5. On each `repl-command`: evaluate in REPL context, update viewport

### Multi-Client Support

The host maintains a **set** of connections, enabling multiple headsets to view simultaneously:

```clojure
;; Broadcasting to all clients
(defn send-script [definitions]
  (doseq [conn (:connections @peer-state)]
    (when (.-open conn)
      (.send conn (clj->js {:type "script-update" ...})))))
```

When a new client connects, the `on-client-connected` callback sends the current script immediately (so they don't have to wait for an edit).

### Peer ID Format

```
ridley-XXXXXX
```

- Prefix `ridley-` avoids collisions with other PeerJS apps
- 6 alphanumeric characters (no I, O, 0, 1 to avoid confusion)
- Case-insensitive (converted to uppercase internally)

### Browser Compatibility Notes

**Brave Browser:**
- Aggressive WebRTC privacy features can block connections
- May show `mDNS` addresses instead of real IPs
- Fix: Disable "Anonymize local IPs exposed by WebRTC" in `brave://flags`

**Safari:**
- Works out of the box
- Recommended for macOS development

**Quest Browser:**
- Works well on same network
- Must use HTTPS or localhost for WebXR

### Debouncing

Script updates are debounced to avoid flooding the connection:

```clojure
(def debounce-delay 500)  ; ms

(defn- send-script-debounced []
  (when (= :host @sync-mode)
    (when-let [timer @sync-debounce-timer]
      (js/clearTimeout timer))
    (reset! sync-debounce-timer
            (js/setTimeout
             (fn []
               (when-let [content (cm/get-value @editor-view)]
                 (sync/send-script content)))
             debounce-delay))))
```

### Limitations

1. **NAT traversal**: Works best on same network. Cross-network may fail without TURN server.
2. **Signaling dependency**: Relies on peerjs.com public server (free, no SLA).
3. **No persistence**: Session ends when host closes browser.
4. **One-way sync**: Host → clients only. Clients are view-only.

### Future Improvements

- QR code for easy headset connection
- Automatic reconnection on disconnect
- Custom PeerJS server for reliability
- Bidirectional editing (collaborative mode)

## Future Considerations

- **Undo/redo** — Since state is immutable, history is just a list of states
- **Collaborative editing** — CRDT on the script text
- **Plugin system** — User-defined SCI namespaces
- **AI assistant** — LLM generates DSL code from natural language