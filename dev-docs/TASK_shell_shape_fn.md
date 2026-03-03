# Task: `shell` shape-fn — Thickness-mapped extrusion with openings

## Summary

Implement a new shape-fn wrapper called `shell` that turns a regular shape into a thickness-mapped extrusion. Instead of extruding a solid profile, the loft generates a **hollow wall** whose thickness varies per-point based on a user-supplied function. Where the thickness function returns 0, the wall disappears entirely — creating openings (holes) in the mesh.

This enables procedural lattice structures, woven patterns, perforated surfaces, and organic shell forms — all from a single shape + thickness function, without boolean operations.

## Concept

### Traditional extrude/loft

```
Shape → one ring per step → solid mesh
```

### Shell extrude/loft

```
Shape + thickness-fn → TWO rings per step (outer + inner) → hollow mesh with openings
```

The thickness function `(fn [angle t] → 0..1)` maps each point on the profile to a wall thickness:

- `1.0` = maximum wall thickness
- `0.5` = half thickness
- `0.0` = no wall → hole/opening
- Between 0 and a threshold (e.g., 0.05) → snapped to 0 to avoid degenerate triangles

The `angle` parameter is the angular position of the point on the profile (radians, 0 to 2π). The `t` parameter is the progress along the extrusion path (0 to 1).

## DSL API

### Basic usage

```clojure
;; Perforated tube: 6 openings that shift along the path
(register lattice
  (loft-n 64
    (shell (circle 20 64)
      :thickness 3              ;; max wall thickness (units)
      :fn (fn [angle t]
            ;; sin pattern creates openings
            (let [phase (* t 6)]
              (max 0 (sin (+ (* angle 6) (* phase PI)))))))
    (f 60)))
```

### Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:thickness` | number | 2 | Maximum wall thickness in model units |
| `:fn` | `(fn [angle t] → 0..1)` | **required** | Thickness function |
| `:threshold` | number | 0.05 | Values below this snap to 0 (prevents degenerate geometry) |
| `:smooth` | number | 0 | Transition smoothing width (radians) at 0↔nonzero boundaries |

### Composition with other shape-fns

`shell` should compose with existing shape-fns via `->` threading:

```clojure
;; Tapered perforated cone
(register tapered-lattice
  (loft-n 64
    (-> (circle 20 64)
        (shell :thickness 3
               :fn (fn [a t] (max 0 (sin (+ (* a 8) (* t PI 4))))))
        (tapered :to 0.5))
    (f 60)))

;; Twisted lattice
(register twisted-lattice
  (loft-n 64
    (-> (circle 20 64)
        (shell :thickness 2
               :fn (fn [a t] (max 0 (sin (+ (* a 6) (* t PI 6))))))
        (twisted :angle 90))
    (f 40)))
```

### Built-in pattern helpers

Convenience functions for common patterns:

```clojure
;; Lattice: regular grid of openings
(shell-lattice shape
  :thickness 2
  :openings 8          ;; openings per revolution
  :rows 12             ;; openings along the path
  :open-ratio 0.6      ;; fraction that is open (0-1)
  :shift 0.5)          ;; row-to-row phase shift (0-1), 0.5 = brick pattern

;; Weave: interlocking pattern (simulates warp/weft)
(shell-weave shape
  :thickness 2
  :strands 6           ;; number of strands
  :frequency 8         ;; crossings along the path
  :over-under 1)       ;; how many strands pass over before going under

;; Voronoi-like: organic irregular openings
(shell-voronoi shape
  :thickness 2
  :cells 20            ;; approximate number of cells
  :seed 42             ;; random seed for reproducibility
  :wall-width 0.3)     ;; relative wall width (0-1)
```

These are sugar — they internally create the appropriate `(fn [angle t] ...)`.

## Implementation

### Core mechanism

When the loft encounters a `shell` shape-fn, for each step at progress `t`:

1. **Evaluate the base shape** (applying any composed shape-fns like `tapered`, `twisted`)
2. **For each point** on the resulting shape:
   a. Calculate the point's angle from the shape centroid: `(atan2 (- y cy) (- x cx))`
   b. Evaluate `(thickness-fn angle t)` → value `v` (clamped 0..1)
   c. If `v < threshold`, set `v = 0`
   d. **Outer point** = the original shape point (unchanged)
   e. **Inner point** = shape point displaced toward centroid by `(* thickness (- 1 v))`
      - When `v = 1`: inner point is `thickness` units inward → full wall
      - When `v = 0`: inner point coincides with outer point → no wall (opening)
3. **Emit two rings**: outer ring + inner ring (reversed winding)

### Ring connectivity

The loft builds faces connecting consecutive ring-pairs:

```
Step i:          outer[i] ---- outer[i+1]
                   |               |
                   |  outer wall   |
                   |               |
                 inner[i] ---- inner[i+1]

Plus:
- Where v > 0 at current step AND v == 0 at adjacent point: 
  → closing faces between outer and inner at that boundary
- Cap faces at start and end (where wall exists)
```

#### Face generation (detailed)

For each step `i` to `i+1`, for each point `j` on the profile:

**Outer faces** (normal pointing outward):
```
outer[i][j] → outer[i][j+1] → outer[i+1][j+1] → outer[i+1][j]
```
These are generated for ALL points, even where thickness is 0 (the outer surface is continuous).

Wait — **correction**: where thickness is 0, outer and inner coincide. If we generate outer faces there, we get zero-area faces. Two options:

**Option A (simpler, recommended for v1):**
Generate outer and inner faces everywhere. Where thickness is 0, the faces are degenerate (zero area) but the mesh is still topologically valid. Three.js will render them as nothing. This avoids complex boundary detection.

**Option B (correct, for v2):**
Only generate outer/inner faces where thickness > 0. Generate "edge" faces at the boundaries between open and closed regions (connecting outer to inner where the wall starts/ends). This produces cleaner geometry but requires boundary detection logic.

**Recommendation:** Start with Option A. It's simpler and the degenerate faces have no visual impact. If manifold validation complains or STL export has issues, upgrade to Option B.

**Inner faces** (normal pointing inward — reversed winding):
```
inner[i][j] → inner[i+1][j] → inner[i+1][j+1] → inner[i][j+1]
```

**Start cap** (at `t = 0`, where thickness > 0):
Connect outer[0][j] to inner[0][j] for each point where `v > 0`.

**End cap** (at `t = 1`, where thickness > 0):
Connect outer[last][j] to inner[last][j] for each point where `v > 0`.

### Data structure

`shell` returns a shape-fn (same protocol as `tapered`, `twisted`, etc.):

```clojure
(defn shell
  "Wraps a shape into a shell shape-fn with variable wall thickness.
   thickness-fn: (fn [angle t] -> 0..1) where angle is radians, t is path progress."
  [shape & {:keys [thickness fn threshold smooth]
            :or {thickness 2 threshold 0.05 smooth 0}}]
  (let [base-shape shape
        thick-fn fn]
    (with-meta
      (clojure.core/fn [shape t]
        ;; Return the shape + metadata that the loft will use
        ;; to generate the double-ring
        (let [transformed-shape shape ;; already transformed by composed shape-fns
              centroid (shape-centroid transformed-shape)
              points (:points transformed-shape)
              angles (mapv (fn [[x y]]
                            (Math/atan2 (- y (second centroid))
                                        (- x (first centroid))))
                          points)
              thicknesses (mapv (fn [a] 
                                 (let [v (thick-fn a t)]
                                   (if (< v threshold) 0.0 v)))
                               angles)]
          (assoc transformed-shape
                 :shell-mode true
                 :shell-thickness thickness
                 :shell-values thicknesses
                 :shell-centroid centroid)))
      {:type :shape-fn})))
```

### Loft modifications

The loft's ring generation code needs to detect `:shell-mode` on the shape and generate double rings:

```clojure
;; In the loft ring-stamping loop:
(if (:shell-mode evaluated-shape)
  ;; Shell mode: generate outer + inner ring
  (let [outer-ring (stamp-ring turtle-state evaluated-shape)
        inner-ring (generate-inner-ring outer-ring evaluated-shape)]
    {:outer outer-ring :inner inner-ring :shell true})
  ;; Normal mode: single ring
  (stamp-ring turtle-state evaluated-shape))
```

The `generate-inner-ring` function displaces each point toward the centroid:

```clojure
(defn generate-inner-ring
  "Generate inner ring by displacing points toward centroid based on shell values."
  [outer-ring shape]
  (let [centroid-3d (:shell-centroid-3d shape)  ;; 3D centroid after stamping
        thickness (:shell-thickness shape)
        values (:shell-values shape)]
    (mapv (fn [point value]
            (if (zero? value)
              point  ;; coincides with outer (opening)
              (let [direction (math/normalize (math/v-sub centroid-3d point))
                    offset (* thickness (- 1.0 value))]
                (math/v-add point (math/v-scale direction offset)))))
          outer-ring
          values)))
```

### Face building for shell rings

The existing loft face builder connects ring[i] to ring[i+1] with quads. For shell mode, it needs to connect:

1. `outer[i]` to `outer[i+1]` — outer surface
2. `inner[i]` to `inner[i+1]` — inner surface (reversed winding)
3. `outer[i]` to `inner[i]` at start/end caps — closing the tube ends

```clojure
;; Pseudocode for shell face generation between step i and i+1:
(defn build-shell-faces [outer-i outer-i+1 inner-i inner-i+1 n-points]
  (let [outer-offset-i   (starting vertex index for outer ring i)
        outer-offset-i+1 (starting vertex index for outer ring i+1)
        inner-offset-i   (starting vertex index for inner ring i)
        inner-offset-i+1 (starting vertex index for inner ring i+1)]
    (concat
      ;; Outer wall faces (CCW from outside)
      (for [j (range n-points)]
        (let [j1 (mod (inc j) n-points)]
          [[(+ outer-offset-i j)
            (+ outer-offset-i j1)
            (+ outer-offset-i+1 j1)
            (+ outer-offset-i+1 j)]]))
      ;; Inner wall faces (CW from outside = CCW from inside)
      (for [j (range n-points)]
        (let [j1 (mod (inc j) n-points)]
          [[(+ inner-offset-i j)
            (+ inner-offset-i+1 j)
            (+ inner-offset-i+1 j1)
            (+ inner-offset-i j1)]]))
      ;; Note: edge faces at open/closed boundaries omitted for v1
      )))
```

## Interaction with existing shape with holes

The `shell` mechanism is **separate** from and **simpler than** shape-difference holes. Shape holes (from Clipper2) define the shape contour itself. Shell creates a thickness layer on any shape.

They can potentially combine: a shape-difference washer processed through `shell` would create a perforated hollow tube. But for v1, focus on shell working with simple shapes (circle, rect, polygon).

## Testing

### Basic tests

```clojure
;; 1. Uniform shell (constant thickness) — should produce a hollow tube
(register uniform-tube
  (loft-n 16
    (shell (circle 20 32)
      :thickness 3
      :fn (fn [a t] 1.0))  ;; constant = full thickness everywhere
    (f 40)))
;; Verify: looks like extrude of a washer

;; 2. Half-open shell — half the circumference open
(register half-open
  (loft-n 16
    (shell (circle 20 32)
      :thickness 3
      :fn (fn [a t] (if (pos? (sin a)) 1.0 0.0)))
    (f 40)))
;; Verify: tube with one side open

;; 3. Lattice pattern
(register lattice
  (loft-n 64
    (shell (circle 20 64)
      :thickness 2
      :fn (fn [a t]
            (max 0 (sin (+ (* a 8) (* t PI 6))))))
    (f 60)))
;; Verify: tube with diagonal lattice openings

;; 4. Brick pattern (shifted rows)
(register bricks
  (loft-n 64
    (shell (circle 20 64)
      :thickness 2
      :fn (fn [a t]
            (let [row (* t 20)
                  row-idx (int row)
                  shift (if (even? row-idx) 0 (/ PI 6))
                  circ (sin (+ (* a 6) shift))
                  long (sin (* row PI))]
              (max 0 (min circ long)))))
    (f 60)))
;; Verify: brick-like pattern with staggered openings

;; 5. Composition with tapered
(register tapered-lattice
  (loft-n 64
    (-> (circle 20 64)
        (shell :thickness 2
               :fn (fn [a t] (max 0 (sin (+ (* a 8) (* t PI 4))))))
        (tapered :to 0.5))
    (f 60)))
;; Verify: lattice that narrows toward the end
```

### Manifold validation

```clojure
;; Shell meshes should ideally be manifold
;; (may not be with Option A / degenerate faces — acceptable for v1)
(mesh-status lattice)
```

### Performance test

```clojure
;; Stress test: high resolution
(resolution :n 128)
(register hires-lattice
  (loft-n 128
    (shell (circle 20 128)
      :thickness 2
      :fn (fn [a t] (max 0 (sin (+ (* a 12) (* t PI 8))))))
    (f 80)))
;; Expected: ~128 × 128 × 2 = 32768 vertices. Should complete in < 5 seconds.
```

## Files to modify

| File | Change |
|------|--------|
| `src/ridley/turtle/shape_fn.cljs` | Add `shell` function, `shell-lattice`, `shell-weave`, `shell-voronoi` |
| `src/ridley/turtle/extrusion.cljs` | Detect `:shell-mode` in shape, generate double rings, build shell faces |
| `src/ridley/turtle/loft.cljs` | Same detection in loft ring-stamping loop |
| `src/ridley/editor/repl.cljs` | Expose `shell`, `shell-lattice`, `shell-weave`, `shell-voronoi` to SCI |
| `src/ridley/turtle/shape.cljs` | Add `shape-centroid` utility if not already present |

## Files that should NOT change

| File | Reason |
|------|--------|
| `clipper/core.cljs` | Shell is not a 2D boolean operation |
| `manifold/core.cljs` | No 3D boolean needed |
| `geometry/primitives.cljs` | Primitives are unaffected |
| `viewport/core.cljs` | Receives mesh data as usual |

## Implementation order

1. **`shell` function** in `shape_fn.cljs` — returns shape-fn that adds `:shell-mode` metadata
2. **`generate-inner-ring`** utility — displaces points toward centroid
3. **Double ring generation** in loft loop — detect shell-mode, produce outer+inner
4. **Shell face builder** — connect outer/inner rings with correct winding
5. **Start/end caps** — close the tube ends where wall exists
6. **Expose to SCI** — add bindings
7. **Test with basic examples**
8. **Built-in patterns** (`shell-lattice`, `shell-weave`) — convenience wrappers
9. **Composition test** — verify `->` threading with `tapered`, `twisted`

## Design decisions

1. **Option A for v1**: Generate faces everywhere including degenerate ones at openings. Simpler code, topologically consistent. Upgrade to Option B if manifold issues arise.

2. **Inner ring displaces toward centroid**: This works well for convex shapes (circle, polygon). For concave shapes, the direction might need per-point normals instead of centroid direction. For v1, centroid-based is fine.

3. **`shell` is a shape-fn**: This means it composes with `->` threading and other shape-fns naturally. The loft doesn't need a separate code path — it just checks for `:shell-mode` in the evaluated shape.

4. **Threshold snapping**: Values below threshold (default 0.05) are snapped to 0 to prevent walls thinner than a printer nozzle. This is both a geometric safety measure (prevents degenerate triangles) and a practical one (sub-nozzle walls can't be printed).

5. **Angle-based thickness function**: Using the angular position on the profile (rather than point index) makes the function resolution-independent. The same function works whether the circle has 32 or 128 points.

## Notes for Code

- The project is ClojureScript compiled with shadow-cljs
- Shape-fns are functions `(fn [shape t] → shape)` with metadata `{:type :shape-fn}`
- See existing shape-fns in `shape_fn.cljs` (`tapered`, `twisted`, `fluted`, `displaced`) for the pattern
- The loft ring-stamping is in `extrusion.cljs` — look for where `stamp-shape` or equivalent is called
- Inner ring winding must be opposite to outer for correct normals
- All math utilities (v-sub, v-add, v-scale, normalize, atan2) are in `ridley.math` or `ridley.turtle.core`
- Test with `npx shadow-cljs watch app` and verify in browser
