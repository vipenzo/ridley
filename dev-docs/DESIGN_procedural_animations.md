# Design: Procedural Animations & Mesh Anchors

## Context

The current animation system (`anim!`) preprocesses all frames at registration time: turtle commands are executed on a virtual turtle, poses are stored in a flat vector, and playback is O(1) frame lookup. This works perfectly for rigid body animations — move, rotate, scale a whole mesh.

But it can't:
- **Deform** a mesh (bend, twist, taper over time)
- **Build** a mesh progressively (grow, extend, construct step by step)
- **Articulate** linked parts with shared pivot points
- **Animate transforms** like scale, inset, or shape morphing

This document proposes **procedural animations** as a complementary system that runs alongside preprocessed animations, plus **mesh anchors** that enable articulated assemblies.

---

## Part 1: Procedural Animations (`anim-proc!`)

### Concept

A procedural animation evaluates a function every frame. The function receives `t` (0→1, with easing already applied) and returns a **new mesh** that replaces the current one.

```clojure
;; A box that grows
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
2. Applies the span's easing (or the single easing if no spans)
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

This replaces the registry mesh AND updates Three.js geometry. It's similar to `apply-mesh-pose!` but replaces vertices entirely rather than transforming base vertices:

```clojure
(defn- apply-procedural-mesh!
  "Replace mesh data in registry and update Three.js geometry.
   The new-mesh comes from the user's gen-fn."
  [mesh-name new-mesh anim-data]
  (when-let [reg-fn @register-mesh-fn]
    (reg-fn mesh-name new-mesh))
  ;; Update Three.js geometry in-place
  (when-let [f @update-geometry-fn]
    ;; NOTE: vertex count may differ from base! See "Geometry Resizing" below.
    (f mesh-name (:vertices new-mesh) (:faces new-mesh))))
```

### Geometry Resizing Problem

With preprocessed animations, the vertex/face count never changes — we just move existing vertices. With procedural animations, `gen-fn` could return a mesh with a **different** number of faces at different `t` values (e.g., a loft with more steps, or a sphere with more segments).

The current `update-mesh-geometry!` in `viewport/core.cljs` updates `Float32Array` values in-place — it cannot change the array length. If the procedural mesh has a different face count, we need to **rebuild** the `BufferGeometry`.

**Strategy**: 

Compare the new mesh's face count with the current geometry's face count:
- **Same count**: fast path — update positions in-place (same as preprocessed)
- **Different count**: slow path — dispose old geometry, create new `BufferGeometry`

This means `update-mesh-geometry!` needs a small extension:

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

The slow path is still faster than rebuilding the entire scene — it only touches one mesh object.

**Performance guideline for users**: If possible, keep the face count constant in `gen-fn` (e.g., always use the same resolution for circles/spheres). This ensures the fast path. Document this.

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

No spans — a single easing for the whole duration. If users need piecewise behavior, they handle it in the function:

```clojure
(anim-proc! :complex 5.0 :arm :linear
  (fn [t]
    (cond
      (< t 0.3) (extrude (circle 2) (f 15) (th (* (/ t 0.3) 45)) (f 12))
      (< t 0.7) (extrude (circle 2) (f 15) (th 45) (f (* 12 (/ (- t 0.3) 0.4))) )
      :else     (extrude (circle 2) (f 15) (th 45) (f 12) (th (* (/ (- t 0.7) 0.3) -45)) (f 8)))))
```

This is deliberate — keeping `anim-proc!` simple. Users who need multi-span procedural control can use time math in the function.

### Interaction with Preprocessed Animations

Can a target have both a preprocessed AND a procedural animation? **No** — they conflict. A preprocessed animation applies a rigid transform to base vertices. A procedural animation replaces vertices entirely. The second registration overwrites the first (same as re-registering with `anim!`).

However, a target CAN have a procedural animation AND be linked to a parent via `link!`. The link adds a position offset to the mesh after `gen-fn` produces it. This works because `link!` operates on the final mesh position, not on individual vertices.

### Interaction with Multi-Animation Composition

Multiple preprocessed animations on the same target sum their deltas (current behavior). A procedural animation on the same target is incompatible — it replaces vertices rather than transforming them. If the user registers both, the procedural one takes priority and a console warning is emitted.

### Scrubbing

Scrubbing works naturally — `seek!` sets `current-time`, and on the next tick the `gen-fn` is called with the corresponding `t`. No frame array needed. This is actually simpler than preprocessed animations.

### Performance Considerations

The `gen-fn` runs every frame (60fps). For simple operations this is fine:
- `(sphere (* 20 t))` — microseconds
- `(extrude (circle 5) (f 30) (th (* t 90)) (f 20))` — ~1ms
- `(loft-n 64 (circle 20) transform-fn (f 100))` — ~5ms

For complex meshes with boolean operations, it could be too slow. Users should:
1. Keep `gen-fn` simple — avoid `mesh-union`/`mesh-difference` per frame
2. Use constant resolution (avoid face count changes for fast path)
3. Use preprocessed animations for anything that can be expressed as rigid motion

### Stop and Restore

`stop!` restores the original mesh from `base-vertices`/`base-faces`, exactly like preprocessed animations. The `gen-fn` is not called at t=0 — the saved base state is used directly.

---

## Part 2: Mesh Anchors (`attach-path`)

### Problem

To build articulated models (puppets, robots, mechanical assemblies), we need to attach meshes at specific points — a shoulder, a hinge, a socket. The current `link!` system links targets by position delta from centroid/creation-pose. It doesn't support attaching at arbitrary points on a mesh.

### Solution: `attach-path`

Associate a path (with its `mark` points) to a mesh. The path's marks become **anchors** stored on the mesh, positioned relative to the mesh's creation-pose.

```clojure
;; 1. Define a path with marks at joint points
(def torso-skeleton (path 
  (mark :hip-l) (rt 5)      ;; left hip offset
  (mark :hip-r) (rt -10)    ;; right hip offset  
  (mark :spine) (f 20)      ;; along the spine
  (mark :shoulder-l) (rt 5)
  (mark :shoulder-r) (rt -10)
  (mark :neck) (f 5)))

;; 2. Build the mesh (independently)
(register torso (box 10 5 20))

;; 3. Attach the skeleton — marks become mesh anchors
(attach-path :torso torso-skeleton)

;; Now :torso has anchors: :hip-l, :hip-r, :spine, :shoulder-l, :shoulder-r, :neck
;; Each anchor has position/heading/up relative to the mesh's creation-pose
```

### How It Works

`attach-path` does the following:

1. Takes the mesh's `creation-pose` (position, heading, up)
2. Resolves the path's marks relative to that pose (same logic as `with-path` / `resolve-and-merge-marks*`)
3. Stores the resolved anchors on the mesh data under `:anchors`

```clojure
;; After attach-path, the mesh in the registry has:
{:vertices [...]
 :faces [...]
 :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}
 :anchors {:hip-l    {:position [5 0 0]   :heading [1 0 0] :up [0 0 1]}
           :hip-r    {:position [-5 0 0]  :heading [1 0 0] :up [0 0 1]}
           :shoulder-l {:position [5 0 20] :heading [1 0 0] :up [0 0 1]}
           ...}}
```

Anchors are in **world coordinates** at registration time, but stored as **offsets from creation-pose** internally. When the mesh moves (via animation), the anchors move with it.

### Implementation

In `editor/bindings.cljs`, add:

```clojure
'attach-path  attach-path-impl
```

The implementation:

```clojure
(defn attach-path-impl
  "Attach a path's marks to a registered mesh as anchors.
   Path marks are resolved at the mesh's creation-pose."
  [mesh-name-or-kw path-data]
  (let [kw (if (keyword? mesh-name-or-kw) mesh-name-or-kw (keyword mesh-name-or-kw))
        mesh (get-mesh kw)]
    (when (and mesh path-data (:commands path-data))
      (let [creation-pose (or (:creation-pose mesh)
                              {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
            ;; Run path on a virtual turtle starting at creation-pose
            ;; Collect mark positions
            anchors (resolve-path-marks path-data creation-pose)]
        ;; Store anchors on the mesh
        (register-mesh! kw (assoc mesh :anchors anchors))))))
```

`resolve-path-marks` walks the path commands on a virtual turtle and collects `:mark` commands:

```clojure
(defn- resolve-path-marks
  "Execute path commands on a virtual turtle starting at initial-pose.
   Collect mark commands as anchor points."
  [path-data initial-pose]
  (loop [cmds (:commands path-data)
         turtle {:position (:position initial-pose)
                 :heading (:heading initial-pose)
                 :up (:up initial-pose)}
         anchors {}]
    (if (empty? cmds)
      anchors
      (let [{:keys [cmd args]} (first cmds)]
        (case cmd
          :f    (recur (rest cmds)
                       (update turtle :position #(math/v+ % (math/v* (:heading turtle) (first args))))
                       anchors)
          :th   (recur (rest cmds)
                       (update turtle :heading #(math/rotate-around-axis % (:up turtle) (* (first args) deg->rad)))
                       anchors)
          :tv   (let [right (math/normalize (math/cross (:heading turtle) (:up turtle)))
                      rad (* (first args) deg->rad)]
                  (recur (rest cmds)
                         (-> turtle
                             (update :heading #(math/rotate-around-axis % right rad))
                             (update :up #(math/rotate-around-axis % right rad)))
                         anchors))
          :tr   (recur (rest cmds)
                       (update turtle :up #(math/rotate-around-axis % (:heading turtle) (* (first args) deg->rad)))
                       anchors)
          :mark (recur (rest cmds)
                       turtle
                       (assoc anchors (first args)
                              {:position (:position turtle)
                               :heading (:heading turtle)
                               :up (:up turtle)}))
          ;; Skip unknown commands
          (recur (rest cmds) turtle anchors))))))
```

### Anchors and Animation

When a mesh is animated (preprocessed or procedural), its anchors must move with it. This is critical for `link!` to work with anchor-based attachments.

**For preprocessed animations**: The anchor positions are transformed using the same rotation matrix and translation as the vertices. In `apply-mesh-pose!`, after computing `new-verts`, also transform anchors:

```clojure
;; In apply-mesh-pose! (addition):
(when-let [anchors (:anchors mesh)]
  (let [transformed-anchors 
        (into {} (map (fn [[k anchor]]
                        [k {:position (transform-point (:position anchor) 
                                                       base-pose frame-pose)
                            :heading (rotate-fn (math/v- (:heading anchor) 
                                                         (:position base-pose)))
                            :up (rotate-fn (math/v- (:up anchor)
                                                    (:position base-pose)))}])
                      anchors))]
    ;; Store transformed anchors for link! to read
    (swap! current-anchor-positions assoc target transformed-anchors)))
```

**For procedural animations**: The `gen-fn` returns a new mesh. If the mesh has anchors, they should be part of the returned mesh. But this is impractical — users don't want to recalculate anchor positions in every `gen-fn` call.

Better approach: procedural animations on articulated parts use `link!` with the parent's anchors. The parent's anchor positions are computed from the parent's animation, and the child reads them via `link!`. The child doesn't need its own anchors — it just follows the parent's anchor point.

### Enhanced `link!`

Currently `link!` just tracks position delta from the parent's centroid. With mesh anchors, it needs to support attaching at a specific anchor point:

```clojure
;; Current: child follows parent's position delta from centroid
(link! :upper-arm :torso)

;; Enhanced: child is attached at parent's :shoulder-r anchor
(link! :upper-arm :torso :at :shoulder-r)

;; Full: specify both parent anchor and child anchor
(link! :lower-arm :upper-arm :at :elbow :from :top)

;; With rotation inheritance (for joints)
(link! :lower-arm :upper-arm :at :elbow :from :top :inherit-rotation true)
```

**`:at`** — the anchor point on the parent where the child is attached. The child's position tracks this anchor's world position (not the parent's centroid).

**`:from`** — the point on the child that sits at the parent's anchor. Defaults to the child's creation-pose position. Could be a face-id (`:top`, `:bottom`) whose center is used, or a child anchor name.

**`:inherit-rotation`** — if true, the child also inherits the parent's orientation changes. Essential for articulated joints. Default false (camera following a character should not rotate with it).

### Implementation of Enhanced `link!`

The link registry stores more data:

```clojure
;; link-registry: child-target → link-config
{:upper-arm {:parent :torso
             :parent-anchor :shoulder-r    ;; nil = centroid
             :child-anchor nil             ;; nil = creation-pose origin
             :inherit-rotation false}}
```

In `get-parent-position-delta` (playback.cljs), when a `:parent-anchor` is specified, read the anchor's current world position instead of the parent mesh's centroid:

```clojure
(defn- get-parent-anchor-position [parent-target anchor-name]
  ;; Read from current-anchor-positions (updated during animation tick)
  ;; Falls back to static anchor on the mesh if no animation is running
  (or (get-in @current-anchor-positions [parent-target anchor-name :position])
      (when-let [mesh (get-mesh parent-target)]
        (get-in mesh [:anchors anchor-name :position]))))
```

### Articulated Model Example

```clojure
;; === Build the parts ===

;; Torso with skeleton
(register torso (box 12 6 20))
(attach-path :torso (path
  (f 10)                    ;; spine base (bottom of torso is at z=0)
  (u 10)                    ;; move to top
  (mark :shoulder-l) (rt -7)
  (mark :shoulder-r) (rt 14)
  (mark :neck)))

;; Upper arm  
(register upper-arm-r (extrude (circle 1.5) (f 12)))
(attach-path :upper-arm-r (path (mark :top) (f 12) (mark :elbow)))

;; Lower arm
(register lower-arm-r (extrude (circle 1.2) (f 10)))
(attach-path :lower-arm-r (path (mark :top) (f 10) (mark :hand)))

;; === Link the hierarchy ===

(link! :upper-arm-r :torso :at :shoulder-r :from :top :inherit-rotation true)
(link! :lower-arm-r :upper-arm-r :at :elbow :from :top :inherit-rotation true)

;; === Animate ===

;; Torso walks forward
(anim! :walk 2.0 :torso :loop
  (span 1.0 :linear (f 20)))

;; Upper arm swings (relative to torso, which is moving)
(anim! :arm-swing 1.0 :upper-arm-r :loop
  (span 0.5 :in-out :ang-velocity 10 (tv 30))
  (span 0.5 :in-out :ang-velocity 10 (tv -30)))

;; Lower arm follows due to link — no separate animation needed,
;; or add a secondary swing:
(anim-proc! :elbow-bend 1.0 :lower-arm-r :linear :loop
  (fn [t] 
    (let [bend (* 45 (Math/sin (* t Math/PI 2)))]
      (extrude (circle 1.2) (th bend) (f 10)))))

(play!)  ;; all play
```

### Execution Order with Links

The current two-pass system (unlinked first, linked second) needs to extend to deeper hierarchies. With torso → upper-arm → lower-arm, we need three passes.

**Solution**: Topological sort on the link graph. Build a dependency graph, sort it, and process in that order:

```clojure
(defn- compute-execution-order
  "Topological sort of targets based on link dependencies."
  [link-registry targets]
  (let [;; Build adjacency: parent → [children]
        children-of (reduce-kv (fn [m child {:keys [parent]}]
                                 (update m parent (fnil conj []) child))
                               {} link-registry)
        ;; BFS from roots (targets with no parent)
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

This replaces the current two-pass approach and handles arbitrary depth.

---

## Part 3: Procedural + Preprocessed Coexistence

### Summary of Animation Types

| Aspect | `anim!` (preprocessed) | `anim-proc!` (procedural) |
|--------|----------------------|--------------------------|
| Frame data | Pre-computed pose vector | Function called per frame |
| Memory | O(frames) poses | O(1) — just the function |
| CPU per frame | O(1) lookup + vertex transform | O(mesh-generation) |
| Scrubbing | Index lookup | Function call |
| Vertex count | Constant (rigid body) | Can vary |
| Deformation | No | Yes |
| Boolean ops | No (too slow) | Possible but discouraged |
| Multi-animation | Delta summing | No (replaces mesh) |
| `link!` support | Yes | Yes (position offset) |
| Easing | Per-span | Single for duration |

### When to Use Which

- **Rigid motion** (move, rotate, orbit) → `anim!`
- **Camera movement** → `anim!` (orbital mode)
- **Shape change** (bend, twist, grow) → `anim-proc!`
- **Progressive construction** (build step by step) → `anim-proc!`
- **Pulsing/breathing** (periodic deformation) → `anim-proc!`

### Same Target Rules

| Existing animation | New registration | Result |
|---|---|---|
| preprocessed | preprocessed | Both run, deltas sum |
| preprocessed | procedural | Procedural wins, warning |
| procedural | preprocessed | Preprocessed wins, warning |
| procedural | procedural | Last wins, warning |

---

## Implementation Plan

### Phase 1: `anim-proc!` Core

Files to modify:

1. **`anim/core.cljs`** — Add `:type` field to registry entries. `register-animation!` sets `:type :preprocessed`. New `register-procedural-animation!` sets `:type :procedural` with `:gen-fn`.

2. **`anim/playback.cljs`** — In `tick-animations!`, branch on `:type`:
   - `:preprocessed` → existing path (frame lookup)
   - `:procedural` → compute t, apply easing, call gen-fn, update mesh

3. **`viewport/core.cljs`** — Extend `update-mesh-geometry!` to handle face count changes (dispose + recreate geometry when needed).

4. **`editor/macros.cljs`** — Add `anim-proc!` macro.

5. **`editor/bindings.cljs`** — Add `anim-proc!` binding.

### Phase 2: Mesh Anchors

6. **`editor/bindings.cljs`** — Add `attach-path` binding.

7. **`anim/playback.cljs`** or new `anim/anchors.cljs` — `resolve-path-marks` function. `current-anchor-positions` atom for runtime tracking.

8. **`anim/playback.cljs`** — Update `apply-mesh-pose!` to transform anchors alongside vertices.

9. **`scene/registry.cljs`** — Ensure `:anchors` field is preserved across mesh updates.

### Phase 3: Enhanced `link!`

10. **`anim/core.cljs`** — Extend `link!` to accept `:at`, `:from`, `:inherit-rotation` options. Update `link-registry` data structure.

11. **`anim/playback.cljs`** — Update `get-parent-position-delta` to read anchor positions. Add rotation inheritance. Replace two-pass with topological sort.

### Phase 4: Integration Testing

12. Build an articulated model (simple puppet: torso + 2 arms + 2 legs) with `attach-path`, `link!`, and mixed `anim!` / `anim-proc!` animations.

---

## Open Questions

1. **`anim-proc!` and camera**: Should `anim-proc!` support `:camera` as target? The gen-fn would return a pose `{:position :heading :up}` instead of a mesh. Could be useful for complex camera paths that can't be expressed as orbital commands. But it adds API surface — maybe just support it for meshes initially.

2. **Anchor visualization**: Should anchors be visible in the viewport? Small markers at anchor positions would help debugging. Could toggle with `(show-anchors :torso)`.

3. **`attach-path` vs `attach-skeleton`**: Name alternatives. "path" is already overloaded. "skeleton" is evocative but implies bones. Other options: `set-anchors`, `mark-joints`, `pin-path`.

4. **Performance budget**: Should we warn if `gen-fn` takes >16ms? A slow gen-fn drops frames. Could measure and log.

5. **`inherit-rotation` composition**: When a chain has multiple links with `inherit-rotation true`, rotations compose (child gets parent's rotation × grandparent's rotation). This is correct for articulated limbs but could surprise users. Document clearly.
