# Task: Animation System for Ridley

## Overview

Add a timeline-based animation system to Ridley that reuses the existing path recording mechanism for preprocessing. Animations are defined in user scripts, preprocessed into per-frame pose arrays, and played back by the render loop with O(1) frame lookup.

The system supports animating registered meshes, the camera, and (phase 2) lights using turtle commands, with easing functions, sequential spans (timeline segments), and a transport UI (play/pause/stop + scrub slider).

## Prerequisite: Lateral Movement Commands (u/d/l/r)

Before implementing the animation system, add lateral movement commands to the core turtle. These are pure translations along the turtle's local axes — no heading/up change, no ring generation.

### Commands

| Function | Description | Equivalent to |
|----------|-------------|---------------|
| `(u dist)` | Move along turtle's up axis | `(tv 90) (f dist) (tv -90)` but without heading change |
| `(d dist)` | Move opposite to up axis | `(u (- dist))` |
| `(r dist)` | Move along right axis (heading × up) | `(th -90) (f dist) (th 90)` but without heading change |
| `(l dist)` | Move opposite to right axis | `(r (- dist))` |

### Implementation

In `turtle/core.cljs`, add a pure translation function:

```clojure
(defn move-lateral
  "Move turtle along an axis without changing heading or up.
   axis is one of the turtle's local vectors (up, right, etc.)
   Does NOT generate sweep rings or trigger corner detection."
  [state axis dist]
  (let [offset (v* axis dist)
        new-pos (v+ (:position state) offset)]
    (-> state
        (assoc :position new-pos)
        ;; Draw line if pen is on
        (cond-> (= :on (:pen-mode state))
                (update :geometry conj {:type :line
                                        :from (:position state)
                                        :to new-pos})))))

(defn move-up [state dist]
  (move-lateral state (:up state) dist))

(defn move-right [state dist]
  (let [right (normalize (cross (:heading state) (:up state)))]
    (move-lateral state right dist)))
```

### Restrictions

- **Blocked inside `path`, `extrude`, `loft`**: These commands are NOT available inside path recording or extrusion contexts. They would produce degenerate ring sequences (lateral displacement without heading change creates overlapping/folded rings).
- **Allowed in**: Top-level turtle movement, `attach`/`attach!` bodies, animation spans.
- **Implementation**: In `macros.cljs`, the `path` macro rebinds `f`/`th`/`tv`/`tr` to recording versions. Simply do NOT rebind `u`/`d`/`l`/`r` inside `path` — they'll remain bound to the implicit (turtle-atom) versions, which will throw a clear error if called during path recording.

### Bindings (`editor/bindings.cljs`)

```clojure
'u  impl/implicit-u    ; (u dist) - move up
'd  impl/implicit-d    ; (d dist) - move down
'r  impl/implicit-r    ; (r dist) - move right (not to be confused with 'r register alias)
'l  impl/implicit-l    ; (l dist) - move left
```

**NOTE**: `r` conflicts with the existing `r` alias for `register`. Options:
1. Remove `r` alias for register (use `register` explicitly)
2. Use `rt` for move-right, `lt` for move-left
3. Use `mr`, `ml`, `mu`, `md` (move-right, move-left, etc.)

Recommended: **Option 2** — `rt`/`lt`/`up`/`dn`. Avoids conflict, still short, reads well:
```clojure
(up 10) (dn 5) (rt 8) (lt 3)
```

Or keep `u`/`d` and use `rt`/`lt` only for the conflicting pair.

### Usage Examples

```clojure
;; Position camera for a shot
(reset)
(f 50) (u 30) (th 180)    ; 50 forward, 30 up, face back

;; Move object sideways
(attach! :gear (r 10))     ; slide gear right by 10

;; Camera dolly + pedestal in animation
(anim! :cam-move 3.0 :camera
  (span 1.0 :in-out (f 20) (u 10)))
```

---

## Design Philosophy

**Reuse path recording**: Each animation span preprocesses its turtle commands into a vector of poses (position/heading/up), exactly like the `path` macro records movements. At playback time, the render loop just looks up the current frame index and applies the pose — no re-evaluation, no drift.

**Immutable base pose**: When an animation starts, the target mesh's current vertex positions are saved as `base-vertices`. Every frame recomputes from base, never accumulates.

**Camera as target**: The camera is treated as a special animation target with the same pose interface.

**Lights as targets (phase 2)**: Lights follow the same pattern — position/heading mapped to Three.js light properties.

## Architecture

### New Files

```
src/ridley/
├── anim/
│   ├── core.cljs          # Animation state, registration, timeline engine
│   ├── easing.cljs         # Easing functions (linear, ease-in, ease-out, etc.)
│   ├── preprocess.cljs     # Span preprocessing: commands → pose vector
│   └── playback.cljs       # Render loop integration, mesh/camera/light pose application
```

### Data Structures

#### Animation Registry (atom in `anim/core.cljs`)

```clojure
(defonce anim-registry (atom {}))

;; Structure:
;; {:gear-anim
;;   {:name :gear-anim
;;    :target :gear              ; keyword (registered mesh name), :camera, or :light-name
;;    :duration 8.0              ; seconds
;;    :fps 60                    ; preprocessing framerate
;;    :loop false                ; if true, wraps with mod instead of stopping
;;    :state :stopped            ; :playing, :paused, :stopped
;;    :current-time 0.0          ; seconds (for playback)
;;    :base-vertices [...]       ; saved mesh vertices at animation start
;;    :base-pose {:position :heading :up}  ; saved mesh creation-pose
;;    :frames [{:position :heading :up} ...]  ; precomputed, one per frame
;;    :total-frames 480          ; (duration * fps)
;;    }}
```

#### Span (input from user DSL)

A span is a segment of the timeline with a fractional weight, easing, optional angular velocity, and turtle commands:

```clojure
{:weight 0.10              ; fraction of total duration (0.0 - 1.0)
 :easing :out              ; easing function name
 :ang-velocity 0           ; angular velocity: 0 = instantaneous rotation
                           ; >0 = 360° takes same time as (f ang-velocity)
 :commands [...]           ; parsed turtle commands
 }
```

### Angular Velocity: Time Distribution for Rotations

#### The Problem

A span contains turtle commands like `(f 10) (th 45) (f 20)`. The preprocessor must distribute frames across these commands. The question: how much time does a rotation take?

#### Solution: `:ang-velocity` parameter

The `:ang-velocity` parameter on a span controls how rotations consume time relative to linear movement:

- **`:ang-velocity 0`** (default): Rotations are instantaneous. They take 0 frames. All time is distributed among forward/lateral movements proportionally to distance.
- **`:ang-velocity N`** (N > 0): A full 360° rotation takes the same number of frames as `(f N)`. Partial rotations scale linearly.

#### Frame Distribution Algorithm

```
Given: commands = [(f 10) (th 45) (f 20)], total span frames = 120, ang-velocity = 15

Step 1: Compute "effective distance" for each command
  (f 10)  → distance = 10
  (th 45) → distance = 15 * (45/360) = 1.875
  (f 20)  → distance = 20
  Total effective distance = 31.875

Step 2: Distribute frames proportionally
  (f 10)  → 120 * (10 / 31.875) ≈ 38 frames
  (th 45) → 120 * (1.875 / 31.875) ≈ 7 frames
  (f 20)  → 120 * (20 / 31.875) ≈ 75 frames

With ang-velocity = 0:
  (f 10)  → distance = 10
  (th 45) → distance = 0 (instantaneous)
  (f 20)  → distance = 20
  Total = 30

  (f 10)  → 120 * (10/30) = 40 frames
  (th 45) → 0 frames (applied between frame 40 and 41)
  (f 20)  → 120 * (20/30) = 80 frames
```

#### Implementation in Preprocessor

```clojure
(defn command-effective-distance
  "Calculate effective distance for a command given ang-velocity setting."
  [cmd ang-velocity]
  (case (:type cmd)
    :f (Math/abs (:dist cmd))
    :u (Math/abs (:dist cmd))
    :d (Math/abs (:dist cmd))
    :r (Math/abs (:dist cmd))
    :l (Math/abs (:dist cmd))
    :th (if (zero? ang-velocity) 0
          (* ang-velocity (/ (Math/abs (:angle cmd)) 360)))
    :tv (if (zero? ang-velocity) 0
          (* ang-velocity (/ (Math/abs (:angle cmd)) 360)))
    :tr (if (zero? ang-velocity) 0
          (* ang-velocity (/ (Math/abs (:angle cmd)) 360)))
    0))

(defn distribute-frames
  "Distribute total-frames across commands proportionally to effective distance."
  [commands total-frames ang-velocity]
  (let [distances (mapv #(command-effective-distance % ang-velocity) commands)
        total-dist (reduce + distances)]
    (if (zero? total-dist)
      ;; All instantaneous — distribute evenly
      (mapv (fn [_] (/ total-frames (count commands))) commands)
      ;; Proportional distribution
      (let [raw-frames (mapv #(* total-frames (/ % total-dist)) distances)]
        ;; Round and adjust to sum exactly to total-frames
        (adjust-frame-counts raw-frames total-frames)))))
```

#### Producing Frames for a Command

For each command with N allocated frames:

**Linear movement** `(f dist)` with N frames:
```
For frame i (0 to N-1):
  local-t = i / N  (before easing — easing is applied at span level)
  turtle position = start + heading * dist * local-t
  Record pose
```

**Rotation** `(th angle)` with N frames (ang-velocity > 0):
```
For frame i (0 to N-1):
  local-t = i / N
  turtle heading = rotate(start-heading, up, angle * local-t)
  Record pose (position unchanged)
```

**Rotation** `(th angle)` with 0 frames (ang-velocity = 0):
```
Apply full rotation to turtle state immediately.
No frames recorded — heading changes between last frame of previous command
and first frame of next command.
```

#### DSL Usage

```clojure
;; Default: rotations are instantaneous
(span 0.80 :linear (f 10) (th 45) (f 20))

;; Visible rotation: 360° takes as long as (f 20)
(span 0.80 :linear :ang-velocity 20
  (f 10) (th 45) (f 20))

;; Very slow rotation
(span 0.80 :linear :ang-velocity 60
  (f 10) (th 45) (f 20))
```

If different rotations need different speeds, use separate spans:

```clojure
(span 0.20 :linear :ang-velocity 10 (f 10) (th 45))   ; slow turn
(span 0.60 :linear (f 20))                              ; fast straight
(span 0.20 :linear :ang-velocity 40 (th 90))            ; very slow turn
```

### Preprocessing Pipeline (anim/preprocess.cljs)

This is the core insight: reuse the path recorder's approach.

```
User DSL → spans with commands → for each span:
  1. Parse commands and calculate effective distances
  2. Distribute the span's frames across commands (using ang-velocity)
  3. For each frame within a command:
     a. Calculate local-t for this frame within this command
     b. Apply span-level easing to local-t
     c. Reset virtual turtle to command's start state
     d. Execute command with eased local-t as parameter
     e. Save {:position :heading :up}
```

**Sequential composition**: Spans are processed in order. The virtual turtle's final state from span N becomes the initial state for span N+1. Within a span, each command's final state becomes the next command's initial state.

**Implementation approach**:

```clojure
(defn preprocess-animation
  "Convert spans + duration + fps into a vector of frame poses."
  [spans duration fps initial-pose]
  (let [total-frames (int (Math/ceil (* duration fps)))
        ;; Convert span weights to frame counts
        span-frame-counts (distribute-span-frames spans total-frames)]
    (loop [span-idx 0
           turtle-state (make-virtual-turtle initial-pose)
           all-frames []]
      (if (>= span-idx (count spans))
        all-frames
        (let [span (nth spans span-idx)
              span-frames (nth span-frame-counts span-idx)
              ;; Distribute this span's frames across its commands
              cmd-frame-counts (distribute-frames (:commands span)
                                                  span-frames
                                                  (:ang-velocity span 0))
              ;; Generate frames for this span
              {:keys [frames final-state]}
              (generate-span-frames turtle-state span cmd-frame-counts)]
          (recur (inc span-idx)
                 final-state
                 (into all-frames frames)))))))
```

**CRITICAL DETAIL about how easing interacts with frame generation**:

Easing is applied at the SPAN level, not per-command. The span's `t` (0→1) is eased, and this eased-t determines the overall progress through the span's commands. This means:

- With `:ease-out`, the span starts fast and slows down — ALL commands in the span follow this timing
- The frame distribution (which command gets how many frames) is computed on the LINEAR timeline
- Easing then remaps which frame corresponds to which position along the commands

To achieve this practically: we generate frames on a linear timeline (proportional to distance), then remap frame indices through the easing function when looking up poses during playback. This is simpler than trying to bake easing into the preprocessing.

**Alternative (simpler) approach**: Generate frames linearly, store them, and during playback use `eased-t` to index into the frame array:

```clojure
;; At playback time:
(let [linear-t (/ current-span-time span-duration)
      eased-t (ease (:easing span) linear-t)
      frame-idx (Math/floor (* eased-t span-frame-count))]
  (nth span-frames frame-idx))
```

This means the frame array is always linearly distributed, and easing just changes which frame we pick at any given clock time. Scrubbing still works perfectly.

### Easing Functions (anim/easing.cljs)

Pure math functions, t ∈ [0,1] → [0,1]:

```clojure
(defn ease [type t]
  (case type
    :linear t
    :in (* t t)                              ; quadratic ease-in
    :out (- 1 (* (- 1 t) (- 1 t)))          ; quadratic ease-out
    :in-out (if (< t 0.5)                    ; quadratic ease-in-out
              (* 2 t t)
              (- 1 (* 2 (- 1 t) (- 1 t))))
    :in-cubic (* t t t)
    :out-cubic (- 1 (Math/pow (- 1 t) 3))
    :in-out-cubic (if (< t 0.5)
                    (* 4 t t t)
                    (- 1 (/ (Math/pow (- (* -2 t) 2) 3) 2)))
    :spring (let [c4 (/ (* 2 Math/PI) 3)]   ; spring overshoot
              (if (or (= t 0) (= t 1)) t
                (- (* (Math/pow 2 (* -10 t))
                      (Math/sin (* (- (* t 10) 0.75) c4)))
                   1)))
    :bounce (let [n1 7.5625 d1 2.75]        ; bounce
              (cond
                (< t (/ 1 d1)) (* n1 t t)
                (< t (/ 2 d1)) (let [t (- t (/ 1.5 d1))] (+ (* n1 t t) 0.75))
                (< t (/ 2.5 d1)) (let [t (- t (/ 2.25 d1))] (+ (* n1 t t) 0.9375))
                :else (let [t (- t (/ 2.625 d1))] (+ (* n1 t t) 0.984375))))
    ;; Default: linear
    t))
```

### Playback (anim/playback.cljs)

Integration with the existing render loop in `viewport/core.cljs`.

**Approach**: Add a `tick-animations!` call inside `render-frame`. On each frame:

```clojure
(defn tick-animations!
  "Called from render-frame. Advances all playing animations by dt."
  [dt]
  (doseq [[anim-name anim] @anim-registry]
    (when (= :playing (:state anim))
      (let [new-time (+ (:current-time anim) dt)
            duration (:duration anim)
            looping? (:loop anim false)]
        (cond
          ;; Looping: wrap around
          (and looping? (>= new-time duration))
          (let [wrapped-time (mod new-time duration)
                frame-idx (time->frame-idx wrapped-time anim)]
            (apply-frame! anim frame-idx)
            (swap! anim-registry assoc-in [anim-name :current-time] wrapped-time))

          ;; Not looping, finished
          (>= new-time duration)
          (do
            (apply-frame! anim (dec (:total-frames anim)))
            (swap! anim-registry assoc-in [anim-name :state] :stopped)
            (swap! anim-registry assoc-in [anim-name :current-time] 0.0))

          ;; Normal advance
          :else
          (let [frame-idx (time->frame-idx new-time anim)]
            (apply-frame! anim frame-idx)
            (swap! anim-registry assoc-in [anim-name :current-time] new-time)))))))

(defn- time->frame-idx
  "Convert a time value to a frame index, applying per-span easing."
  [time anim]
  ;; Find which span this time falls in
  ;; Apply that span's easing to the local-t within the span
  ;; Map to global frame index
  (let [{:keys [span-idx local-t]} (time->span-info time anim)
        span (nth (:spans anim) span-idx)
        eased-t (ease (:easing span :linear) local-t)
        span-start-frame (:start-frame (nth (:span-ranges anim) span-idx))
        span-frame-count (:frame-count (nth (:span-ranges anim) span-idx))]
    (+ span-start-frame
       (min (dec span-frame-count)
            (int (Math/floor (* eased-t span-frame-count)))))))
```

**Applying a frame to a mesh**:

```clojure
(defn apply-frame!
  "Apply a precomputed frame pose to the animation target."
  [anim frame-idx]
  (let [pose (nth (:frames anim) (min frame-idx (dec (:total-frames anim))))
        target (:target anim)]
    (cond
      (= target :camera) (apply-camera-pose! pose)
      (light-target? target) (apply-light-pose! target pose)  ; phase 2
      :else (apply-mesh-pose! target pose anim))))
```

For meshes, `apply-mesh-pose!` computes the delta transform between `base-pose` and the frame's pose, then applies it to `base-vertices`:

```clojure
(defn apply-mesh-pose!
  "Transform mesh vertices from base-vertices using the delta between
   base-pose and frame-pose."
  [mesh-name frame-pose anim]
  (let [base-pose (:base-pose anim)
        base-verts (:base-vertices anim)
        ;; Compute rotation from base heading/up to frame heading/up
        rotation-matrix (compute-rotation-matrix base-pose frame-pose)
        ;; Compute translation
        translation (v- (:position frame-pose) (:position base-pose))
        ;; Transform all vertices
        new-verts (mapv (fn [v]
                          (let [rel (v- v (:position base-pose))
                                rotated (apply-rotation rotation-matrix rel)]
                            (v+ (v+ (:position base-pose) rotated) translation)))
                        base-verts)]
    ;; Update the mesh in the registry
    (registry/update-mesh-vertices! mesh-name new-verts)
    ;; Trigger viewport refresh (lightweight — just update BufferGeometry)
    (viewport/update-mesh-geometry! mesh-name new-verts)))
```

For camera:

```clojure
(defn apply-camera-pose!
  "Apply a pose to the Three.js camera."
  [pose]
  (let [{:keys [position heading up]} pose]
    (viewport/set-camera-pose! position heading up)))
```

### Changes to Existing Files

#### `turtle/core.cljs`

Add lateral movement functions:

```clojure
(defn move-up [state dist] ...)
(defn move-down [state dist] ...)
(defn move-right [state dist] ...)
(defn move-left [state dist] ...)
```

These are pure translations along local axes. No heading/up change. Draw lines if pen is on. Do NOT interact with sweep/extrusion state.

#### `editor/implicit.cljs`

Add implicit versions:

```clojure
(defn implicit-u [dist] (swap! state/turtle-atom turtle/move-up dist))
(defn implicit-d [dist] (swap! state/turtle-atom turtle/move-down dist))
(defn implicit-rt [dist] (swap! state/turtle-atom turtle/move-right dist))
(defn implicit-lt [dist] (swap! state/turtle-atom turtle/move-left dist))
```

#### `viewport/core.cljs`

Add to `render-frame`:

```clojure
(defn- render-frame [_time xr-frame]
  (when-let [{:keys [renderer scene camera controls]} @state]
    ;; ... existing code ...

    ;; NEW: Tick animations
    (anim-playback/tick-animations! (/ 1.0 60))

    (.render renderer scene camera)))
```

Add new functions:

```clojure
(defn update-mesh-geometry!
  "Update only the vertex positions of a named mesh's Three.js object.
   Much faster than full scene rebuild."
  [mesh-name new-vertices faces]
  (when-let [{:keys [world-group]} @state]
    ;; Find Three.js mesh by userData.registryName
    (let [target-mesh (some #(when (= mesh-name (.. % -userData -registryName)) %)
                            (.-children world-group))]
      (when target-mesh
        (let [geom (.-geometry target-mesh)
              positions (.-array (.-position (.-attributes geom)))
              n-verts (count new-vertices)]
          ;; Rebuild unindexed position buffer (same as create-three-mesh)
          (let [idx (atom 0)]
            (doseq [[i0 i1 i2] faces]
              (when (and (< i0 n-verts) (< i1 n-verts) (< i2 n-verts))
                (let [[x0 y0 z0] (nth new-vertices i0)
                      [x1 y1 z1] (nth new-vertices i1)
                      [x2 y2 z2] (nth new-vertices i2)
                      i @idx]
                  (aset positions i x0) (aset positions (+ i 1) y0) (aset positions (+ i 2) z0)
                  (aset positions (+ i 3) x1) (aset positions (+ i 4) y1) (aset positions (+ i 5) z1)
                  (aset positions (+ i 6) x2) (aset positions (+ i 7) y2) (aset positions (+ i 8) z2)
                  (swap! idx + 9)))))
          (set! (.-needsUpdate (.-position (.-attributes geom))) true)
          (.computeVertexNormals geom))))))

(defn set-camera-pose!
  "Set camera position and orientation from a turtle pose."
  [position heading up]
  (when-let [{:keys [camera controls]} @state]
    (let [[px py pz] position
          look-target (mapv + position (mapv #(* % 100) heading))]
      (.set (.-position camera) px py pz)
      (.set (.-up camera) (nth up 0) (nth up 1) (nth up 2))
      (.lookAt camera (nth look-target 0) (nth look-target 1) (nth look-target 2))
      (.update controls))))
```

**Mesh tagging**: Modify `update-scene` to tag Three.js meshes with registry names:

```clojure
;; In update-scene, where meshes are added:
(doseq [{:keys [mesh-data name]} named-meshes]
  (let [mesh (create-three-mesh mesh-data)]
    (set! (.. mesh -userData -registryName) name)
    (.add world-group mesh)))
```

**CRITICAL NOTE on unindexed geometry**: The current `create-three-mesh` uses "unindexed" geometry — face vertices are duplicated for flat shading. The Float32Array has `n_faces * 3 * 3` floats, NOT `n_vertices * 3`. The `update-mesh-geometry!` function must match this layout. It needs both the new vertices AND the faces array to rebuild the position buffer in the same unindexed format.

#### `scene/registry.cljs`

Add:

```clojure
(defn update-mesh-vertices!
  "Update the vertices of a registered mesh in-place (for animation).
   Does not trigger viewport rebuild."
  [name new-vertices]
  (swap! scene-meshes
         (fn [meshes]
           (mapv (fn [entry]
                   (if (= (:name entry) name)
                     (assoc-in entry [:mesh :vertices] new-vertices)
                     entry))
                 meshes))))

(defn get-mesh-data
  "Get the full mesh data map for a registered name."
  [name]
  (some (fn [entry] (when (= (:name entry) name) (:mesh entry)))
        @scene-meshes))
```

#### `editor/bindings.cljs`

Add lateral movement and animation DSL bindings:

```clojure
;; Lateral movement (pure translation, no heading change)
'u   impl/implicit-u
'd   impl/implicit-d
'rt  impl/implicit-rt
'lt  impl/implicit-lt

;; Animation commands
'anim!          anim-core/register-animation!
'play!          anim-core/play!
'pause!         anim-core/pause!
'stop!          anim-core/stop!
'stop-all!      anim-core/stop-all!
'seek!          anim-core/seek!
'anim-list      anim-core/list-animations
'ease           easing/ease
```

#### `editor/macros.cljs`

Add the `anim!` and `span` macros:

```clojure
;; span: define a timeline segment
;; (span weight easing & commands)
;; (span weight easing :ang-velocity N & commands)
;; Returns a span data structure
(defmacro span [weight easing & body]
  ;; Parse optional :ang-velocity from body
  ;; Remaining body items are turtle commands
  ;; Commands are captured as data (not executed)
  `(make-span ~weight ~easing ...))

;; anim!: define and preprocess an animation
;; (anim! :name duration :target spans...)
;; (anim! :name duration :target :loop spans...)
(defmacro anim! [name duration target & body]
  ;; Parse optional :loop flag from body
  ;; Remaining items are span expressions
  ;; Preprocess all spans into frame vector
  ;; Register animation
  `(let [spans# [~@span-exprs]
         frames# (preprocess-animation spans# ~duration 60 initial-pose#)]
     (register-animation! ~name
       {:target ~target
        :duration ~duration
        :loop ~loop?
        :spans spans#
        :frames frames#
        ...})))
```

### DSL User API (Complete)

```clojure
;; === Lateral movement (general purpose, not animation-specific) ===
(u 10)                  ; move up 10
(d 5)                   ; move down 5
(rt 8)                  ; move right 8
(lt 3)                  ; move left 3

;; === Define animations ===

;; Simple rotation (instantaneous rotation changes)
(anim! :spin 3.0 :gear
  (span 1.0 :linear (tr 360)))

;; Multi-span with easing
(anim! :gear-entrance 8.0 :gear
  (span 0.10 :out (f 6))
  (span 0.80 :linear (tr 720))
  (span 0.10 :in (f -6)))

;; Visible rotation (gear turns visibly before moving)
(anim! :gear-turn 4.0 :gear
  (span 0.30 :out :ang-velocity 20 (f 10) (th 45))
  (span 0.70 :linear (f 20)))

;; Camera orbit
(anim! :cam-orbit 5.0 :camera
  (span 1.0 :in-out (th 360)))

;; Camera with lateral movement
(anim! :cam-dolly 3.0 :camera
  (span 0.5 :out (f 20))
  (span 0.3 :linear (rt 10))
  (span 0.2 :in (u 5)))

;; Looping animation
(anim! :spin-forever 2.0 :gear :loop
  (span 1.0 :linear (tr 360)))

;; === Playback control ===
(play! :spin)           ; start playing
(play!)                 ; play all registered animations
(pause! :spin)          ; pause
(stop! :spin)           ; stop and reset to frame 0
(stop-all!)             ; stop all animations
(seek! :spin 0.5)       ; jump to 50% of duration

;; === Info ===
(anim-list)             ; list registered animations with status
```

### UI Changes

#### Transport Controls

Add a transport bar below the viewport toolbar:

```html
<!-- Inside #viewport-panel, after #viewport-toolbar -->
<div id="anim-transport" class="hidden">
  <button id="btn-anim-play" class="transport-btn" title="Play">▶</button>
  <button id="btn-anim-pause" class="transport-btn" title="Pause">⏸</button>
  <button id="btn-anim-stop" class="transport-btn" title="Stop">⏹</button>
  <input type="range" id="anim-slider" min="0" max="1000" value="0"
         class="anim-slider" title="Scrub timeline">
  <span id="anim-time">0:00 / 0:00</span>
  <select id="anim-select" title="Active animation">
    <option value="">No animations</option>
  </select>
</div>
```

**CSS**: Dark theme matching existing toolbar. Slider styled to match the app.

**Visibility**: The transport bar appears automatically when at least one animation is registered. Hidden otherwise.

**Slider behavior**:
- During playback: slider follows current time
- When dragged: pauses playback, seeks to position (scrubbing)
- On release: stays paused at new position

### Implementation Phases

#### Phase 0: Lateral Movement Commands (prerequisite)

Files to modify:
1. `src/ridley/turtle/core.cljs` — add `move-up`, `move-down`, `move-right`, `move-left`
2. `src/ridley/editor/implicit.cljs` — add implicit versions
3. `src/ridley/editor/bindings.cljs` — add `u`, `d`, `rt`, `lt` bindings
4. `src/ridley/editor/macros.cljs` — ensure these are NOT rebound inside `path` macro

Testing: `(u 10)` moves turtle up, pen draws line, heading unchanged. Calling inside `(path ...)` throws error.

#### Phase 1: Core Animation Engine

Files to create:
5. `src/ridley/anim/easing.cljs` — easing functions
6. `src/ridley/anim/preprocess.cljs` — span preprocessing with ang-velocity frame distribution
7. `src/ridley/anim/core.cljs` — animation registry, register/play/pause/stop/seek
8. `src/ridley/anim/playback.cljs` — render loop tick, mesh pose application

Files to modify:
9. `src/ridley/viewport/core.cljs` — add `tick-animations!` to render-frame, add `update-mesh-geometry!`, tag meshes with registry names
10. `src/ridley/scene/registry.cljs` — add `update-mesh-vertices!`, `get-mesh-data`
11. `src/ridley/editor/bindings.cljs` — add animation DSL bindings
12. `src/ridley/editor/macros.cljs` — add `anim!`, `span` macros

Testing: Register a box, `(anim! :spin 3.0 :box (span 1.0 :linear (tr 360)))`, `(play! :spin)` → box rotates smoothly.

#### Phase 2: UI Transport + Camera + Lights

Files to modify:
13. `public/index.html` — add transport bar HTML
14. `public/css/style.css` — transport bar styling
15. `src/ridley/core.cljs` — wire transport buttons, slider, animation select dropdown

Camera:
16. `src/ridley/viewport/core.cljs` — add `set-camera-pose!`, disable OrbitControls during camera animation

Lights (new):
17. `src/ridley/viewport/core.cljs` — light creation, registration, pose application
18. `src/ridley/scene/registry.cljs` — light registration (similar to meshes)
19. `src/ridley/editor/bindings.cljs` — light DSL: `spot-light`, `point-light`, `dir-light`

Light DSL:
```clojure
;; Create and register a light
(register spot (spot-light :color 0xffffff :intensity 1.0 :angle 30))

;; Position with attach (same as meshes)
(register spot (attach (spot-light ...) (f 50) (tv -45)))

;; Animate
(anim! :light-sweep 4.0 :spot
  (span 1.0 :in-out (th 90)))
```

Lights as animation targets: position = Three.js light position, heading = light direction (for spot/directional). `apply-light-pose!` maps turtle pose to light properties.

#### Phase 3: Export

Frame-by-frame PNG capture:
- Render mode: advance animation frame-by-frame at fixed fps, capture each frame via `renderer.domElement.toDataURL()`
- Browser: download as ZIP of PNGs
- Tauri: optionally call ffmpeg for direct MP4 export

### Key Technical Details

#### Efficient Mesh Updates

For 60fps animation, cannot rebuild entire scene each frame. Solution:

1. Tag Three.js mesh objects: `mesh.userData.registryName = name`
2. `update-mesh-geometry!` finds object by `userData.registryName`
3. Updates `geometry.attributes.position.array` in-place
4. Sets `geometry.attributes.position.needsUpdate = true`
5. Calls `geometry.computeVertexNormals()`

Must match unindexed geometry layout (faces × 3 vertices × 3 coords).

#### Easing Applied at Playback, Not Preprocessing

Frames are stored on a LINEAR timeline. Easing is applied at playback time by remapping the time-to-frame-index lookup through the easing function. This means:
- The frame array is always uniformly distributed
- Scrubbing uses the same easing math
- Changing easing function doesn't require re-preprocessing

#### Multiple Animations

Run simultaneously on different targets. Same target = last write wins (console warning).

#### Camera Special Handling

- Disable OrbitControls during camera animation (set `controls.enabled = false`)
- Re-enable on stop
- `look-at` inside camera spans computes heading toward target anchor

#### Code Re-evaluation

On re-eval: all animations stop, registry clears, `anim!` in script re-registers (fresh preprocess).

### Open Questions / Decisions for Implementation

1. **`rt`/`lt` naming**: Confirm these names work or choose alternatives (`mr`/`ml`?)
2. **Default FPS**: 60 fixed. Add `:fps` option to `anim!` if needed later.
3. **`play!` without args**: Play ALL registered animations (most useful for presentations).
4. **Looping syntax**: `:loop` flag after target keyword: `(anim! :name dur :target :loop ...)`.
5. **Light types**: Phase 2 — start with `spot-light` and `point-light` only.
