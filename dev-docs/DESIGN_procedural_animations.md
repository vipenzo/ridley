# Design: Procedural Animations, Mesh Anchors & Hierarchical Assemblies

## Context

The current animation system (`anim!`) preprocesses all frames at registration time: turtle commands are executed on a virtual turtle, poses are stored in a flat vector, and playback is O(1) frame lookup. This works perfectly for rigid body animations — move, rotate, scale a whole mesh.

But it can't:
- **Deform** a mesh (bend, twist, taper over time)
- **Build** a mesh progressively (grow, extend, construct step by step)
- **Articulate** linked parts with shared pivot points
- **Animate transforms** like scale, inset, or shape morphing

This document proposes three complementary extensions:
1. **Procedural animations** (`anim-proc!`) — mesh-regenerating functions alongside preprocessed
2. **Mesh anchors** (`attach-path`) — named reference points on meshes for joints
3. **Hierarchical assemblies** — `register` with maps + `with-path` nesting for implicit link hierarchies

---

## Part 1: Procedural Animations (`anim-proc!`)

### Concept

A procedural animation evaluates a function every frame. The function receives `t` (0→1, with easing already applied) and returns a **new mesh** that replaces the current one.

```clojure
;; A sphere that grows
(register blob (sphere 1))
(anim-proc! :grow 3.0 :blob :out
  (fn [t] (sphere (* 20 t))))

;; An arm that bends at the elbow
(register arm (extrude (circle 2) (f 15) (f 12)))
(anim-proc! :bend 2.0 :arm :in-out
  (fn [t] (extrude (circle 2) (f 15) (th (* t 90)) (f 12))))

;; A rectangle that twists progressively
(register bar (extrude (rect 10 5) (f 40)))
(anim-proc! :twist 4.0 :bar :linear
  (fn [t] (loft-n 32 (rect 10 5)
            #(rotate-shape %1 (* %2 t 180))
            (f 40))))
```

### Why a function, not preprocessed meshes?

Preprocessing vertex arrays for every frame would use enormous memory (a 1000-vertex mesh at 60fps for 5 seconds = 300,000 frames × 1000 vertices × 3 floats). A function that re-generates the mesh at each frame is typically cheaper in memory and often fast enough — simple extrusions take <1ms.

### Data Structure

In the animation registry, a procedural animation looks like:

```clojure
{:name :bend
 :type :procedural              ;; ← distinguishes from :preprocessed
 :target :arm
 :duration 2.0
 :easing :in-out
 :loop false
 :state :stopped
 :current-time 0.0
 :gen-fn (fn [t] ...)           ;; the user's mesh generation function
 :base-vertices [...]           ;; saved for stop! restoration
 :base-faces [...]
 :base-pose {...}}
```

The `:type` field is the key discriminator. Preprocessed animations have `:type :preprocessed` (the current default, set implicitly). The rest of the lifecycle (play/pause/stop/seek) is identical.

### Integration with `tick-animations!`

In `playback.cljs`, `tick-animations!` currently computes a frame index and looks up a pose from `:frames`. For procedural animations, it instead:

1. Computes `t` from current-time / duration
2. Applies easing
3. Calls `(:gen-fn anim) eased-t`
4. Gets back a mesh
5. Updates the registry and Three.js geometry in-place

```clojure
;; Inside tick-animations!, after advancing time:

(if (= :procedural (:type anim-data))
  ;; Procedural: call gen-fn, get new mesh
  (let [t (/ current-time duration)
        eased-t (easing/ease (:easing anim-data :linear) t)
        new-mesh ((:gen-fn anim-data) eased-t)]
    (when new-mesh
      (apply-procedural-mesh! target new-mesh anim-data)))
  ;; Preprocessed: existing frame lookup path
  (let [frame-idx (time->frame-idx current-time anim-data)]
    (apply-frame! anim-data frame-idx)))
```

### `apply-procedural-mesh!`

Replaces the registry mesh AND updates Three.js geometry:

```clojure
(defn- apply-procedural-mesh!
  "Replace mesh data in registry and update Three.js geometry.
   The new-mesh comes from the user's gen-fn."
  [mesh-name new-mesh anim-data]
  (when-let [reg-fn @register-mesh-fn]
    (reg-fn mesh-name new-mesh))
  (when-let [f @update-geometry-fn]
    (f mesh-name (:vertices new-mesh) (:faces new-mesh))))
```

### Geometry Resizing Problem

With preprocessed animations, vertex/face count never changes. With procedural animations, `gen-fn` could return a mesh with a **different** face count at different `t` values.

The current `update-mesh-geometry!` updates `Float32Array` in-place — it cannot change array length. If face count changes, we need to **rebuild** the `BufferGeometry`.

**Strategy** — compare face counts, two paths:

```clojure
(defn update-mesh-geometry!
  "Update a named mesh's Three.js geometry.
   Fast path if face count unchanged, rebuilds geometry otherwise."
  [mesh-name new-vertices new-faces]
  (when-let [target-mesh (find-three-mesh mesh-name)]
    (let [geom (.-geometry target-mesh)
          current-face-count (/ (.-count (.-position (.-attributes geom))) 3)
          new-face-count (count new-faces)]
      (if (= current-face-count new-face-count)
        ;; Fast path: update positions in-place
        (update-positions-in-place! geom new-vertices new-faces)
        ;; Slow path: rebuild geometry
        (let [new-geom (create-buffer-geometry new-vertices new-faces)]
          (.dispose geom)
          (set! (.-geometry target-mesh) new-geom))))))
```

**Performance guideline**: Keep face count constant in `gen-fn` when possible (same resolution, same segment count). This ensures the fast path.

### DSL

```clojure
;; Simple: one easing, one function
(anim-proc! :name duration :target easing gen-fn)

;; With loop
(anim-proc! :name duration :target easing :loop gen-fn)

;; Examples
(anim-proc! :grow 3.0 :blob :out
  (fn [t] (sphere (* 20 t))))

(anim-proc! :pulse 1.0 :heart :in-out :loop
  (fn [t]
    (let [s (+ 1.0 (* 0.3 (Math/sin (* t Math/PI 2))))]
      (box (* 10 s) (* 10 s) (* 10 s)))))
```

No spans — a single easing for the whole duration. Users who need piecewise behavior handle it in the function with `cond` on `t`. This is deliberate — keeping `anim-proc!` simple.

### Loop Modes

Both `anim!` and `anim-proc!` support three loop modes via keyword flags:

| Keyword | Registry value | Time mapping | Cycle period |
|---------|---------------|-------------|--------------|
| `:loop` | `:forward` | `mod(t, D)` — 0→1, 0→1, ... | D |
| `:loop-reverse` | `:reverse` | `D - mod(t, D)` — 1→0, 1→0, ... | D |
| `:loop-bounce` | `:bounce` | triangle wave — 0→1→0→1, ... | 2D |

```clojure
;; Forward loop (default)
(anim! :spin 2.0 :gear :loop (span 1.0 :linear (tr 360)))

;; Reverse: always plays backward
(anim! :unwind 2.0 :gear :loop-reverse (span 1.0 :linear (tr 360)))

;; Bounce (ping-pong): forward then backward, no discontinuity
(anim-proc! :breathe 2.0 :blob :in-out :loop-bounce
  (fn [t] (sphere (+ 5 (* 15 t)))))
```

The `:loop` field in the registry stores `:forward`, `:reverse`, or `:bounce` (or `false` for non-looping). `true` is accepted for backward compatibility and treated as `:forward`.

For bounce, `t` in the `gen-fn` goes 0→1→0 — the remapping happens at the playback level before easing is applied, so the user's function always receives a value in `[0, 1]`.

### Interaction Rules

| Existing animation | New registration | Result |
|---|---|---|
| preprocessed | preprocessed | Both run, deltas sum |
| preprocessed | procedural | Procedural wins, warning |
| procedural | preprocessed | Preprocessed wins, warning |
| procedural | procedural | Last wins, warning |

A target CAN have a procedural animation AND be linked to a parent via `link!`. The link adds a position offset after `gen-fn` produces the mesh.

### Scrubbing

Works naturally — `seek!` sets `current-time`, next tick calls `gen-fn` with corresponding `t`. Simpler than preprocessed.

### Performance Considerations

The `gen-fn` runs every frame (60fps). Typical costs:
- `(sphere (* 20 t))` — microseconds
- `(extrude (circle 5) (f 30) (th (* t 90)) (f 20))` — ~1ms
- `(loft-n 64 (circle 20) transform-fn (f 100))` — ~5ms

Avoid `mesh-union`/`mesh-difference` per frame. Use preprocessed for rigid motion.

### Stop and Restore

`stop!` restores original mesh from `base-vertices`/`base-faces`, same as preprocessed.

---

## Part 2: Mesh Anchors

### Problem

Articulated models need meshes attached at specific points — shoulders, elbows, sockets. The current `link!` tracks position delta from centroid/creation-pose. It doesn't support arbitrary attachment points.

### Solution: `attach-path`

Associate a path (with `mark` points) to a mesh. The path's marks become **anchors** stored on the mesh.

```clojure
;; Define a path with joint marks
(def arm-skeleton (path
  (mark :shoulder)
  (f 15)
  (mark :elbow)
  (f 12)
  (mark :wrist)))

;; Build and register the mesh
(register upper-arm (extrude (circle 1.5) (f 15)))

;; Attach — marks become mesh anchors
(attach-path :upper-arm arm-skeleton)
```

### How It Works

`attach-path`:
1. Takes the mesh's `creation-pose`
2. Runs the path on a virtual turtle starting at that pose
3. Collects `mark` commands as anchor poses
4. Stores them on the mesh under `:anchors`

```clojure
;; After attach-path, the mesh has:
{:vertices [...]
 :faces [...]
 :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}
 :anchors {:shoulder {:position [0 0 0]  :heading [1 0 0] :up [0 0 1]}
           :elbow    {:position [15 0 0] :heading [1 0 0] :up [0 0 1]}
           :wrist    {:position [27 0 0] :heading [1 0 0] :up [0 0 1]}}}
```

### On-Demand Anchor Resolution

Anchors are **not** transformed every frame for every mesh. That would be wasteful — most meshes have anchors that nobody reads on most frames.

Instead, when a `link!` needs the current world position of a parent's anchor, it computes it **on demand** by applying the same transform that was applied to the mesh's vertices:

```clojure
(defn- resolve-anchor-position
  "Compute current world position of a mesh anchor.
   Applies the same rotation+translation as the mesh vertices."
  [parent-target anchor-name]
  (when-let [mesh (get-mesh parent-target)]
    (when-let [anchor (get-in mesh [:anchors anchor-name])]
      (let [;; Get the animation's base-pose and current frame-pose
            anim-data (find-active-animation parent-target)
            base-pose (or (:base-pose anim-data) (:creation-pose mesh))
            frame-pose (current-frame-pose parent-target anim-data)]
        (if (and base-pose frame-pose)
          ;; Transform anchor point same as vertices
          (let [rotate-fn (compute-rotation-matrix base-pose frame-pose)
                translation (math/v- (:position frame-pose) (:position base-pose))
                base-origin (:position base-pose)
                rel (math/v- (:position anchor) base-origin)
                rotated (rotate-fn rel)]
            {:position (math/v+ (math/v+ base-origin rotated) translation)
             :heading (rotate-fn (:heading anchor))
             :up (rotate-fn (:up anchor))})
          ;; No animation — return static anchor
          anchor)))))
```

This is O(1) per anchor per frame — one rotation + one translation. Called only for anchors that are actually referenced by active links.

### Enhanced `link!`

```clojure
;; Current: child follows parent's centroid delta
(link! :upper-arm :torso)

;; Attach at specific parent anchor
(link! :upper-arm :torso :at :shoulder-r)

;; Specify child attachment point too
(link! :lower-arm :upper-arm :at :elbow :from :top)

;; With rotation inheritance (for articulated joints)
(link! :lower-arm :upper-arm :at :elbow :from :top :inherit-rotation true)
```

**`:at`** — anchor on parent. Child tracks this point's world position.

**`:from`** — anchor on child that sits at the parent's anchor. Defaults to creation-pose origin.

**`:inherit-rotation`** — child inherits parent's orientation changes. Default true in assemblies (see Part 3), false for explicit `link!` calls.

Link registry stores:

```clojure
{:upper-arm {:parent :torso
             :parent-anchor :shoulder-r
             :child-anchor nil
             :inherit-rotation false}}
```

At playback, `get-parent-position-delta` calls `resolve-anchor-position` when `:parent-anchor` is set — no pre-computed atom needed.

---

## Part 3: Hierarchical Assemblies

### Motivation

Building articulated models with explicit `attach-path` + `link!` calls works but is verbose. The skeleton definition, mesh construction, and link wiring are three separate steps that must stay in sync. For complex assemblies (puppets, robots, vehicles), this becomes tedious and error-prone.

### Key Insight: `register` Already Supports Vectors

Today, `register` accepts vectors:

```clojure
(register many [(box 20) (box 30)])
(hide many 1)  ;; hide second element
```

Extending to maps is natural — same logic, keyword keys instead of indices.

### Key Insight: `with-path` Is Already a Stack

`with-path` maintains a stack of contexts. Inside a `with-path`, `goto` moves the turtle to a mark. `mark` commands in the path define positions. Nesting `with-path` pushes a new context. This stack already captures the parent-child relationship.

### Assembly Syntax

```clojure
(def body-skeleton (path
  (mark :hip-l) (rt -6)
  (mark :hip-r) (rt 12)
  (mark :spine) (f 20)
  (mark :shoulder-l) (rt -7)
  (mark :shoulder-r) (rt 14)
  (mark :neck) (f 5)))

(def arm-skeleton (path
  (mark :top)
  (f 15)
  (mark :elbow)
  (f 12)
  (mark :wrist)))

(def hand-skeleton (path
  (mark :base)
  (f 4)
  (mark :thumb-base) (rt -3)
  (mark :index-base) (rt 2)
  (mark :middle-base) (rt 2)
  (mark :ring-base) (rt 2)))

(def finger-skeleton (path
  (mark :base)
  (f 3)
  (mark :mid-knuckle)
  (f 2.5)
  (mark :tip-knuckle)
  (f 2)))

(with-path body-skeleton
  (register puppet
    {:torso (box 12 6 20)
     :r-arm (do (goto :shoulder-r)
                (with-path arm-skeleton
                  {:upper (cyl 3 15)
                   :lower (do (goto :elbow) (cyl 2.5 12))
                   :hand  (do (goto :wrist)
                              (with-path hand-skeleton
                                {:palm (box 3 5 1.5)
                                 :index (do (goto :index-base)
                                            (with-path finger-skeleton
                                              {:prox (cyl 0.5 3)
                                               :mid  (do (goto :mid-knuckle) (cyl 0.4 2.5))
                                               :tip  (do (goto :tip-knuckle) (cyl 0.3 2))}))
                                 :thumb (do (goto :thumb-base) (cyl 0.6 3))}))}))}))
```

### What Happens Under the Hood

When `register` receives a map inside a `with-path` context:

1. **Each map value** is evaluated — turtle commands (`goto`) execute, mesh constructors return meshes
2. **Each mesh** is registered with a qualified name: `:puppet/torso`, `:puppet/r-arm/upper`, `:puppet/r-arm/hand/index/mid`
3. **Links are inferred** from the `with-path` stack. The system tracks which `goto` preceded each mesh creation:
   - `:puppet/r-arm/upper` was created after `(goto :shoulder-r)` in `body-skeleton` → linked to `:puppet/torso` at `:shoulder-r`
   - `:puppet/r-arm/lower` was created after `(goto :elbow)` in `arm-skeleton` → linked to `:puppet/r-arm/upper` at `:elbow`
   - `:puppet/r-arm/hand/index/mid` was created after `(goto :mid-knuckle)` in `finger-skeleton` → linked to `:puppet/r-arm/hand/index/prox` at `:mid-knuckle`
4. **`:inherit-rotation true`** is the default for assembly links (articulated joints need rotation inheritance)
5. **Anchors** from each `with-path`'s skeleton are attached to the corresponding mesh

### Link Inference Algorithm

The `with-path` stack maintains:

```clojure
;; Stack frame
{:path-data path          ;; the skeleton path
 :parent-mesh-key :puppet/r-arm/upper  ;; the "current" mesh in this with-path
 :last-goto-anchor :elbow              ;; last goto target (nil if none)
 :name-prefix [:puppet :r-arm]}        ;; for qualified name construction
```

When a mesh is created inside a map:
1. Look at current `with-path` frame's `:last-goto-anchor`
2. If non-nil → create link from this mesh to the frame's `:parent-mesh-key` at that anchor
3. If nil (first entry, or no goto before it) → link to parent with no anchor (centroid)

When a nested `with-path` starts:
1. Push new stack frame
2. The new frame's `:parent-mesh-key` is the mesh that was current in the outer frame

When a nested map entry starts:
1. The first mesh created becomes the frame's `:parent-mesh-key`
2. Subsequent meshes after `goto` link to this parent

### Qualified Names

Map nesting produces path-like names:

| Code position | Registered name |
|---|---|
| Top-level `:torso` | `:puppet/torso` |
| `:r-arm` → `:upper` | `:puppet/r-arm/upper` |
| `:r-arm` → `:hand` → `:palm` | `:puppet/r-arm/hand/palm` |
| `:r-arm` → `:hand` → `:index` → `:mid` | `:puppet/r-arm/hand/index/mid` |

These are regular keywords in the registry. Animations target them directly:

```clojure
(anim! :wave 1.0 :puppet/r-arm/upper :loop
  (span 0.5 :in-out :ang-velocity 10 (tv 30))
  (span 0.5 :in-out :ang-velocity 10 (tv -30)))

(anim-proc! :finger-curl 0.5 :puppet/r-arm/hand/index/mid :in-out
  (fn [t] (cyl 0.4 2.5)))
```

### Show/Hide

```clojure
;; Hide a single part
(hide :puppet/r-arm/hand/thumb)

;; Hide a whole sub-tree (all keys starting with prefix)
(hide :puppet/r-arm)

;; Show everything
(show :puppet)

;; Access via the register binding too
(hide puppet :r-arm :hand :thumb)  ;; equivalent to (hide :puppet/r-arm/hand/thumb)
```

The `hide`/`show` with a keyword prefix hides/shows all registered meshes whose name starts with that prefix. Simple `starts-with?` on the keyword string.

### Topology for Playback

The implicit links form a tree. The topological sort for `tick-animations!` walks this tree root-first:

```clojure
(defn- compute-execution-order
  "Topological sort of targets based on link dependencies.
   Handles arbitrary depth."
  [link-registry targets]
  (let [children-of (reduce-kv (fn [m child {:keys [parent]}]
                                 (update m parent (fnil conj []) child))
                               {} link-registry)
        roots (remove #(contains? link-registry %) targets)]
    (loop [queue (vec roots)
           visited #{}
           order []]
      (if (empty? queue)
        order
        (let [t (first queue)]
          (if (visited t)
            (recur (rest queue) visited order)
            (recur (into (vec (rest queue)) (get children-of t []))
                   (conj visited t)
                   (conj order t))))))))
```

This replaces the current two-pass (unlinked, then linked) approach.

### Full Assembly + Animation Example

```clojure
;; === Skeletons ===

(def body-sk (path
  (mark :shoulder-l) (rt -7)
  (mark :shoulder-r) (rt 14)
  (mark :hip-l) (rt -6)
  (mark :hip-r) (rt 12)))

(def arm-sk (path (mark :top) (f 15) (mark :elbow)))

;; === Assembly ===

(with-path body-sk
  (register puppet
    {:torso (box 12 6 20)
     :r-arm (do (goto :shoulder-r)
                (with-path arm-sk
                  {:upper (cyl 3 15)
                   :lower (do (goto :elbow) (cyl 2.5 12))}))
     :l-arm (do (goto :shoulder-l)
                (with-path arm-sk
                  {:upper (cyl 3 15)
                   :lower (do (goto :elbow) (cyl 2.5 12))}))}))

;; Implicit links created:
;;   :puppet/r-arm/upper → :puppet/torso at :shoulder-r (inherit-rotation true)
;;   :puppet/r-arm/lower → :puppet/r-arm/upper at :elbow (inherit-rotation true)
;;   :puppet/l-arm/upper → :puppet/torso at :shoulder-l (inherit-rotation true)
;;   :puppet/l-arm/lower → :puppet/l-arm/upper at :elbow (inherit-rotation true)

;; === Animate ===

;; Torso walks
(anim! :walk 2.0 :puppet/torso :loop
  (span 1.0 :linear (f 40)))

;; Right arm swings (preprocessed rigid motion)
(anim! :r-swing 1.0 :puppet/r-arm/upper :loop
  (span 0.5 :in-out :ang-velocity 10 (tv 30))
  (span 0.5 :in-out :ang-velocity 10 (tv -30)))

;; Left arm swings opposite
(anim! :l-swing 1.0 :puppet/l-arm/upper :loop
  (span 0.5 :in-out :ang-velocity 10 (tv -30))
  (span 0.5 :in-out :ang-velocity 10 (tv 30)))

;; Right elbow bends (procedural — shape changes)
(anim-proc! :r-elbow 1.0 :puppet/r-arm/lower :linear :loop
  (fn [t]
    (let [bend (* 30 (Math/sin (* t Math/PI 2)))]
      (extrude (circle 2.5) (th bend) (f 12)))))

(play!)
```

Execution order (topological):
1. `:puppet/torso` (root, no parent)
2. `:puppet/r-arm/upper`, `:puppet/l-arm/upper` (children of torso)
3. `:puppet/r-arm/lower`, `:puppet/l-arm/lower` (children of upper arms)

Each child reads its parent's anchor position on-demand via `resolve-anchor-position`.

---

## Part 4: Summary

### Animation Types

| Aspect | `anim!` (preprocessed) | `anim-proc!` (procedural) |
|--------|----------------------|--------------------------|
| Frame data | Pre-computed pose vector | Function called per frame |
| Memory | O(frames) poses | O(1) — just the function |
| CPU per frame | O(1) lookup + vertex transform | O(mesh-generation) |
| Scrubbing | Index lookup | Function call |
| Vertex count | Constant (rigid body) | Can vary |
| Deformation | No | Yes |
| Multi-animation | Delta summing | No (replaces mesh) |
| `link!` support | Yes | Yes (position offset) |
| Easing | Per-span | Single for duration |

### When to Use Which

- **Rigid motion** (move, rotate, orbit) → `anim!`
- **Camera movement** → `anim!` (orbital mode)
- **Shape change** (bend, twist, grow) → `anim-proc!`
- **Progressive construction** → `anim-proc!`
- **Pulsing/breathing** → `anim-proc!`

### Assembly Approaches (Simple → Complex)

| Approach | When to use |
|---|---|
| Single mesh + `anim!` | Simple objects, rigid motion |
| `attach-path` + explicit `link!` | 2-3 parts, fine control over link params |
| `with-path` + `register` map | Multi-part assemblies, automatic hierarchy |
| Nested `with-path` maps | Deep articulated models (puppets, robots, hands) |

---

## Implementation Plan

### Phase 1: `anim-proc!` Core

1. **`anim/core.cljs`** — Add `:type` field. `register-animation!` → `:type :preprocessed`. New `register-procedural-animation!` → `:type :procedural` with `:gen-fn`.
2. **`anim/playback.cljs`** — Branch on `:type` in `tick-animations!`.
3. **`viewport/core.cljs`** — Extend `update-mesh-geometry!` for face count changes (dispose + recreate).
4. **`editor/macros.cljs`** + **`editor/bindings.cljs`** — `anim-proc!` macro and binding.

### Phase 2: Mesh Anchors + On-Demand Resolution

5. **`editor/bindings.cljs`** — `attach-path` binding.
6. **`anim/playback.cljs`** — `resolve-anchor-position` (on-demand, uses same rotation matrix as vertices).
7. **`anim/core.cljs`** — Extend `link!` with `:at`, `:from`, `:inherit-rotation`.
8. **`anim/playback.cljs`** — Replace two-pass with topological sort.

### Phase 3: Hierarchical Assemblies

9. **`scene/registry.cljs`** — Extend `register` to accept maps. Qualified name generation (`:parent/child/grandchild`). Prefix-based `show`/`hide`.
10. **`editor/macros.cljs`** — Track `with-path` stack context. On map registration, infer links from `goto` history.
11. **`editor/bindings.cljs`** — Wire assembly registration with implicit link creation.

### Phase 4: Integration Testing

12. Build puppet with 4+ nesting levels. Verify topological sort, on-demand anchor resolution, mixed `anim!`/`anim-proc!`, show/hide by prefix.

---

## Open Questions

1. **`anim-proc!` and camera**: Support `:camera` as target? Gen-fn would return `{:position :heading :up}` instead of mesh. Useful but adds API surface — start with meshes only?

2. **Anchor visualization**: `(show-anchors :puppet/torso)` to display small markers at anchor positions. Helpful for debugging.

3. **Naming**: `attach-path` vs `attach-skeleton` vs `set-anchors`. "path" is already overloaded. "skeleton" is evocative but implies bones.

4. **Performance budget**: Warn in console if `gen-fn` takes >16ms? Slow gen-fn drops frames.

5. **Explicit override**: Can the user override an implicit assembly link? E.g., `(link! :puppet/r-arm/lower :puppet/torso :at :shoulder-r)` to bypass the elbow and attach directly to torso. Probably yes — explicit `link!` overwrites implicit.

6. **Map evaluation order**: Clojure maps don't guarantee insertion order. For the assembly to work, entries with `goto` must evaluate in the order written. Use `array-map` or a vector-of-pairs syntax? Or rely on small maps (<8 entries) which preserve insertion order in Clojure?
