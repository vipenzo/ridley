# Task: Implement `warp` — Spatial Deformation Function

## Summary

Add a `warp` function that deforms a mesh by modifying vertices that fall inside a volume. Any primitive can be used as volume — the shape of the primitive determines the deformation zone:

- **Sphere**: smooth, organic zone (spherical boundary)
- **Box**: sharp-edged rectangular zone
- **Cylinder**: axial zone (good for twist along an axis)
- **Cone**: tapered axial zone

The volume is positioned using `attach` (standard turtle commands). A deformation function defines what happens to vertices inside. Built-in preset functions cover common operations.

## API

### Core function

```clojure
(warp mesh volume deform-fn)
;; Returns a new mesh with modified vertices (pure, no side effects)
```

- `mesh` — any Ridley mesh (has `:vertices` and `:faces`)
- `volume` — a mesh used as bounding volume (its bounding box defines the deformation zone). Typically positioned with `attach`:
  ```clojure
  (warp m (attach (sphere 15) (f 10)) (inflate 3))
  ```
- `deform-fn` — a function `(fn [pos local-pos dist normal] -> new-pos)` where:
  - `pos` — world position `[x y z]` of the vertex
  - `local-pos` — position relative to volume center `[lx ly lz]` (normalized: -1 to 1 on each axis relative to volume half-extents)
  - `dist` — normalized distance from volume center (0 = center, 1 = boundary). For sphere volumes this is euclidean distance to center / radius. For box volumes this is max of abs(local-pos) on each axis.
  - `normal` — vertex normal `[nx ny nz]` (estimated from adjacent faces)
  - Returns: new world position `[x y z]`

### Multiple deform-fns (chained)

```clojure
(warp mesh volume (inflate 3) (twist 20 :z))
;; Apply inflate first, then twist
```

Implementation: `warp` accepts variadic deform-fns and applies them sequentially to each vertex.

### Options (future, not in v1)

```clojure
(warp mesh volume (inflate 3) :falloff :smooth)   ;; future
(warp mesh volume (inflate 3) :subdivide 2)        ;; future
```

## Preset Deformation Functions

Each preset returns a deform-fn `(fn [pos local-pos dist normal] -> new-pos)`.
All presets apply smooth falloff by default (hermite: `3t² - 2t³`, where `t = 1 - dist`).

### inflate

Push vertices outward along their normals.

```clojure
(inflate amount)
;; amount: displacement in world units at center (falls off toward boundary)
```

Implementation:
```clojure
(fn [pos local-pos dist normal]
  (let [falloff (smooth-falloff dist)]
    (v+ pos (v* normal (* amount falloff)))))
```

### dent

Push vertices inward (toward volume center). Opposite of inflate.

```clojure
(dent amount)
;; amount: displacement in world units
```

Implementation: like inflate but negative direction.

### flatten

Squash vertices toward a plane passing through the volume center.

```clojure
(flatten axis)           ;; :x, :y, or :z — axis to flatten along
(flatten axis amount)    ;; amount: 0 = fully flat, 1 = no effect (default 0)
```

Implementation:
```clojure
(fn [pos local-pos dist normal]
  (let [falloff (smooth-falloff dist)
        axis-idx (case axis :x 0 :y 1 :z 2)
        center-val (nth volume-center axis-idx)
        current-val (nth pos axis-idx)
        target-val (+ center-val (* amount (- current-val center-val)))
        new-val (+ current-val (* falloff (- target-val current-val)))]
    (assoc pos axis-idx new-val)))
```

### twist

Rotate vertices around an axis, with angle proportional to position along that axis. For cylinder/cone volumes, automatically uses the volume's axis. For sphere/box, requires explicit axis.

```clojure
(twist angle)            ;; auto axis from volume (cyl/cone only)
(twist angle :x)         ;; explicit axis (world X)
(twist angle :y)         ;; explicit axis (world Y)
(twist angle :z)         ;; explicit axis (world Z)
```

Implementation:
```clojure
(fn [pos local-pos dist normal vol-bounds]
  (let [f (smooth-falloff dist)
        ;; Determine twist axis
        axis (cond
               explicit-axis  (case explicit-axis :x [1 0 0] :y [0 1 0] :z [0 0 1])
               (#{:cyl :cone} (:primitive vol-bounds))  (:up vol-bounds)
               :else (throw "twist: specify axis for box/sphere volumes"))
        ;; Position along axis determines rotation amount (-1 to 1)
        d (m/v- pos (:center vol-bounds))
        along (m/dot d axis)
        half-h (/ (:height vol-bounds) 2)
        t (if (pos? half-h) (/ along half-h) 0)  ;; -1 to 1
        rotation (* angle f t (/ PI 180))
        ;; Rotate pos around axis through volume center
        ]))
```

This means:
- **Cylinder** + twist = natural torsion around cyl axis (no axis needed)
- **Box** + twist = specify which axis to twist around

### noise

Random displacement along normals. Deterministic (seeded from vertex position).

```clojure
(noise amplitude)
(noise amplitude :frequency freq)    ;; default freq = 1
```

Implementation uses a simple hash-based noise function on vertex position.

### attract

Pull vertices toward the volume center. For cylinder and cone volumes, pulls toward the **axis** (not a point), which creates a radial contraction effect.

```clojure
(attract strength)
;; strength: 0 = no effect, 1 = fully pulled to center/axis
```

Implementation:
```clojure
(fn [pos local-pos dist normal vol-bounds]
  (let [f (smooth-falloff dist)
        target (case (:primitive vol-bounds)
                 ;; Cylinder/cone: project pos onto axis (pull toward axis, not point)
                 (:cyl :cone)
                 (let [d (m/v- pos (:center vol-bounds))
                       along (m/dot d (:up vol-bounds))]
                   (m/v+ (:center vol-bounds) (m/v* (:up vol-bounds) along)))
                 ;; Sphere/box: pull toward center point
                 (:center vol-bounds))]
    (m/v+ pos (m/v* (m/v- target pos) (* strength f)))))
```

This means:
- **Sphere/box** + attract = pull toward a point (dimple)
- **Cylinder** + attract = pull toward axis (radial squeeze)
- **Cone** + attract = pull toward tapered axis

### smooth

Average vertex positions with neighbors (mesh relaxation).

```clojure
(smooth strength)
;; strength: 0-1, how much to blend toward neighbor average
```

Note: This one needs adjacency info. Can defer to v2 if too complex. In v1, could approximate by pulling toward local centroid.

## Implementation

### New file: `src/ridley/geometry/warp.cljs`

```clojure
(ns ridley.geometry.warp
  "Spatial deformation: modify mesh vertices within a bounding volume."
  (:require [ridley.math :as m]))

;; ============================================================
;; Volume intersection — primitive-aware
;; ============================================================

;; warp uses the volume mesh's :primitive field to determine
;; the actual shape boundary (not just bounding box).
;;
;; Supported primitives:
;;   :box     — axis-aligned box (bounding box = shape)
;;   :sphere  — spherical boundary (euclidean distance from center)
;;   :cyl     — cylindrical boundary (radial + height clamp)
;;   :cone    — conical boundary (radial varies with height)
;;
;; For unknown primitives, falls back to bounding box.
;;
;; The :primitive keyword is already present in all Ridley meshes
;; created by box, sphere, cyl, cone.

(defn- compute-volume-bounds
  "Extract center, half-extents, and primitive type from a volume mesh."
  [volume-mesh]
  (let [verts (:vertices volume-mesh)
        xs (map #(nth % 0) verts)
        ys (map #(nth % 1) verts)
        zs (map #(nth % 2) verts)
        min-v [(apply min xs) (apply min ys) (apply min zs)]
        max-v [(apply max xs) (apply max ys) (apply max zs)]
        center (m/v* (m/v+ min-v max-v) 0.5)
        half-ext (m/v* (m/v- max-v min-v) 0.5)
        ;; Extract orientation from creation-pose (for cyl/cone axis)
        pose (:creation-pose volume-mesh)
        up (or (:up pose) [0 0 1])         ;; cyl/cone axis
        heading (or (:heading pose) [1 0 0])]
    {:center center
     :half-ext half-ext
     :min min-v
     :max max-v
     :primitive (or (:primitive volume-mesh) :box)
     :up up           ;; axis for cyl/cone
     :heading heading
     ;; For sphere: radius = min half-extent (sphere is symmetric)
     :radius (apply min (map Math/abs [(nth half-ext 0) (nth half-ext 1) (nth half-ext 2)]))
     ;; For cyl/cone: radial extent and height
     :height (* 2 (Math/abs (m/dot half-ext up)))}))

(defn- point-in-volume?
  "Check if a point falls within the volume shape."
  [pos {:keys [primitive center half-ext min max up radius height] :as vol}]
  (case primitive
    :sphere
    (let [d (m/v- pos center)]
      (<= (m/dot d d) (* radius radius)))

    :cyl
    (let [d (m/v- pos center)
          along-axis (m/dot d up)
          half-h (/ height 2)
          radial (m/v- d (m/v* up along-axis))
          radial-dist (m/magnitude radial)]
      (and (<= (Math/abs along-axis) half-h)
           (<= radial-dist radius)))

    :cone
    (let [d (m/v- pos center)
          along-axis (m/dot d up)
          half-h (/ height 2)
          ;; radius varies linearly from bottom to top
          t (/ (+ along-axis half-h) height)  ;; 0 at bottom, 1 at top
          local-radius (* radius (- 1 (* t 0.5))) ;; approximate
          radial (m/v- d (m/v* up along-axis))
          radial-dist (m/magnitude radial)]
      (and (<= (Math/abs along-axis) half-h)
           (<= radial-dist local-radius)))

    ;; Default: bounding box
    (let [[px py pz] pos]
      (and (>= px (nth min 0)) (<= px (nth max 0))
           (>= py (nth min 1)) (<= py (nth max 1))
           (>= pz (nth min 2)) (<= pz (nth max 2))))))

(defn- compute-dist
  "Compute normalized distance from center (0=center, 1=boundary).
   Uses primitive-specific distance."
  [pos local-pos {:keys [primitive center up radius height] :as vol}]
  (case primitive
    :sphere
    (let [d (m/v- pos center)]
      (min 1.0 (/ (m/magnitude d) radius)))

    :cyl
    (let [d (m/v- pos center)
          along (m/dot d up)
          half-h (/ height 2)
          radial (m/v- d (m/v* up along))
          rdist (if (pos? radius) (/ (m/magnitude radial) radius) 0)
          hdist (if (pos? half-h) (/ (Math/abs along) half-h) 0)]
      (min 1.0 (max rdist hdist)))

    ;; Default (box): max-norm of local-pos
    (let [[lx ly lz] local-pos]
      (min 1.0 (max (Math/abs lx) (Math/abs ly) (Math/abs lz))))))

;; ============================================================
;; Vertex normals
;; ============================================================

(defn- estimate-vertex-normals
  "Estimate per-vertex normals by averaging adjacent face normals."
  [vertices faces]
  (let [n (count vertices)
        ;; Accumulate face normals per vertex
        normals (reduce
                  (fn [acc [i0 i1 i2]]
                    (let [v0 (nth vertices i0)
                          v1 (nth vertices i1)
                          v2 (nth vertices i2)
                          e1 (m/v- v1 v0)
                          e2 (m/v- v2 v0)
                          fn (m/cross e1 e2)]
                      (-> acc
                          (update i0 #(m/v+ % fn))
                          (update i1 #(m/v+ % fn))
                          (update i2 #(m/v+ % fn)))))
                  (vec (repeat n [0 0 0]))
                  faces)]
    (mapv m/normalize normals)))

;; ============================================================
;; Falloff
;; ============================================================

(defn smooth-falloff
  "Hermite smooth falloff: 1 at center (dist=0), 0 at boundary (dist=1)."
  [dist]
  (let [t (- 1.0 (max 0.0 (min 1.0 dist)))]
    (* t t (- 3 (* 2 t)))))

;; ============================================================
;; Core warp
;; ============================================================

(defn warp
  "Deform mesh vertices inside volume using deform-fn(s).
   Returns new mesh with modified vertices."
  [mesh volume & deform-fns]
  (let [vol-bounds (compute-volume-bounds volume)
        vertices (:vertices mesh)
        faces (:faces mesh)
        normals (estimate-vertex-normals vertices faces)
        new-vertices
        (vec (map-indexed
               (fn [i pos]
                 (if (point-in-volume? pos vol-bounds)
                   (let [local-pos (compute-local-pos pos vol-bounds)
                         dist (compute-dist local-pos)
                         normal (nth normals i)]
                     ;; Apply deform-fns sequentially
                     (reduce
                       (fn [p dfn] (dfn p local-pos dist normal))
                       pos
                       deform-fns))
                   pos))
               vertices))]
    (assoc mesh :vertices new-vertices)))

;; ============================================================
;; Preset deformation functions
;; ============================================================

(defn inflate
  "Push vertices outward along normals."
  [amount]
  (fn [pos local-pos dist normal]
    (let [f (smooth-falloff dist)]
      (m/v+ pos (m/v* normal (* amount f))))))

(defn dent
  "Push vertices inward (toward volume center)."
  [amount]
  (inflate (- amount)))

(defn flatten-deform
  "Squash vertices toward a plane through volume center.
   axis: :x :y :z. amount: 0=flat, 1=no effect."
  ([axis] (flatten-deform axis 0))
  ([axis amount]
   (fn [pos local-pos dist normal]
     (let [f (smooth-falloff dist)
           axis-idx (case axis :x 0 :y 1 :z 2)
           ;; Pull toward center on this axis by factor f*(1-amount)
           local-val (nth local-pos axis-idx)
           ;; Scale the local component toward 0
           scale (+ amount (* (- 1 amount) (- 1 f)))
           new-local-val (* local-val scale)
           ;; NOT NEEDED: we work in world coords with volume center
           ;; Simpler: blend pos toward center on given axis
           delta (* (- (nth pos axis-idx)
                       ;; would need center... pass via closure
                       )
                    f (- 1 amount))]
       ;; Hmm, we need volume-center here. Let's make presets receive vol-bounds.
       pos))))

;; NOTE: flatten and twist need volume center in the closure.
;; Solution: warp passes vol-bounds to each deform-fn as metadata,
;; OR presets that need it capture it. Simplest: warp wraps deform-fns.
```

### Problem: presets needing volume info

Some presets (`flatten`, `twist`, `attract`) need the volume center. Two options:

**Option A**: Pass volume-bounds as 5th arg to deform-fn:
```clojure
(fn [pos local-pos dist normal vol-bounds] -> new-pos)
```

**Option B**: `warp` "prepares" each deform-fn by passing vol-bounds before applying:
```clojure
;; Presets return (fn [vol-bounds] -> (fn [pos local-pos dist normal] -> new-pos))
;; warp detects and calls the outer fn first
```

**Recommendation**: Option A is simpler. All deform-fns take 5 args. Built-in presets use vol-bounds when needed, custom fns can ignore it.

## Bindings (SCI exposure)

Add to `src/ridley/editor/bindings.cljs`:

```clojure
'warp             warp/warp
'inflate          warp/inflate
'dent             warp/dent
'flatten-deform   warp/flatten-deform  ;; can't use 'flatten (clojure core)
'twist-deform     warp/twist-deform
'noise-deform     warp/noise-deform
'attract          warp/attract
```

Note: `flatten` and `twist` clash with Clojure core names. Use suffixed versions or short aliases that don't clash. Alternatively since these are in SCI context, we can shadow them — but safer not to.

Naming options:
- `flatten` → `squash` (no clash, descriptive)
- `twist` → `twist` (no clash in clojure.core actually — `twist` is fine)
- `noise` → `roughen` or keep `noise`

## V1 Scope (implement now)

1. **`warp`** core function with bounding box intersection
2. **`inflate`** preset
3. **`dent`** preset (negative inflate)  
4. **`attract`** preset
5. **`noise`** preset (simple hash-based)
6. **`twist`** preset
7. **`squash`** preset (flatten along axis)
8. **Smooth falloff** (hermite, default for all presets)
9. **SCI bindings** for all of the above
10. **Vertex normal estimation** for inflate/dent

## V2 (later)

- `smooth` preset (needs adjacency graph)
- `:falloff` option (`:linear`, `:sharp`, `:smooth`, custom fn)
- `:subdivide` option (increase tessellation before deforming)
- Spherical distance mode (instead of bounding box)
- Multiple warp chaining with `->` threading

## Testing

```clojure
;; Create a high-poly mesh to deform
(register s (sphere 30 32 16))

;; === Sphere volume: smooth organic deformation ===
(register s (warp s (attach (sphere 15) (f 20)) (inflate 5)))

;; === Box volume: sharp-edged deformation zone ===
(register b (box 50 50 10))
(register b (warp b (attach (box 20 20 30) (f 10)) (inflate 4)))

;; === Cylinder volume: axial deformation ===
(register s2 (sphere 30 32 16))
(register s2 (warp s2 (attach (cyl 10 40) (f 5)) (attract 0.3)))

;; Twist with cylinder (natural match: twist around cyl axis)
(register b2 (box 50 50 50))
(tv 90) ;; cyl axis vertical
(register b2 (warp b2 (attach (cyl 15 60)) (twist 45 :z)))

;; Squash with box (flat zone)
(register s3 (sphere 30 32 16))
(register s3 (warp s3 (attach (box 40 40 10) (tv 90) (f 20)) (squash :z)))

;; With tweak!
(register m (sphere 30 32 16))
(test :all (warp m (attach (sphere 15) (f 10)) (inflate 3)))
```

## File Structure

```
src/ridley/geometry/warp.cljs     # NEW: warp + presets
src/ridley/editor/bindings.cljs   # MODIFY: add warp bindings
```
