# Ridley — Procedural Displacement: noise, noisy, woven

## Overview

This document specifies procedural displacement functions for Ridley's DSL. These are built-in utilities that work with the `displaced` shape-fn to create organic and patterned surfaces without requiring the user to write displacement math from scratch.

## noise — Deterministic 2D Noise

### Why not rand?

`rand` is non-deterministic (different result each evaluation) and discontinuous (adjacent values are uncorrelated). This produces jagged, unstable geometry. We need a function that is:

- **Deterministic**: same inputs → same output, every time
- **Continuous**: nearby inputs → nearby outputs (smooth surface)
- **Pseudorandom-looking**: no visible repeating pattern

### Algorithm: Value Noise with Smoothstep Interpolation

The implementation uses a hash-based approach:

1. **Hash function**: maps integer grid coordinates to a pseudorandom value in [-1, 1] using a deterministic formula (large-number sine trick)
2. **Interpolation**: for fractional coordinates, bilinearly interpolate between the four surrounding grid points
3. **Smoothstep**: apply Hermite smoothstep to interpolation weights for C1 continuity (no visible grid artifacts)

```clojure
(defn- hash-noise
  "Deterministic pseudorandom value for integer grid coordinates.
   Returns a value in [-1, 1]."
  [x y]
  (let [n (* (sin (+ (* x 127.1) (* y 311.7))) 43758.5453)]
    (- (* 2 (- n (floor n))) 1)))

(defn noise
  "2D deterministic continuous noise. Returns value in approximately [-1, 1].
   Same inputs always produce the same output. Nearby inputs produce nearby outputs."
  [x y]
  (let [ix (floor x) iy (floor y)
        fx (- x ix)  fy (- y iy)
        ;; Hermite smoothstep for C1 continuity
        sx (* fx fx (- 3 (* 2 fx)))
        sy (* fy fy (- 3 (* 2 fy)))
        ;; Four corner values
        n00 (hash-noise ix iy)
        n10 (hash-noise (inc ix) iy)
        n01 (hash-noise ix (inc iy))
        n11 (hash-noise (inc ix) (inc iy))]
    (+ (* n00 (- 1 sx) (- 1 sy))
       (* n10 sx (- 1 sy))
       (* n01 (- 1 sx) sy)
       (* n11 sx sy))))
```

### Properties

- **Range**: approximately [-1, 1] (not guaranteed to hit extremes)
- **Period**: effectively non-repeating for practical inputs
- **Continuity**: C1 (smooth first derivative, no visible grid edges)
- **Performance**: fast — just 4 sin calls + interpolation per evaluation

### DSL Usage

`noise` is registered as a global function, available without import:

```clojure
(noise 1.5 2.3)           ;; => some value in [-1, 1]
(noise 1.5 2.3)           ;; => same value (deterministic)
(noise 1.51 2.3)          ;; => very close to the above (continuous)
```

### Octave Noise (fbm)

For richer, more natural-looking noise, layer multiple octaves at different scales. Each octave adds finer detail at reduced amplitude:

```clojure
(defn fbm
  "Fractal Brownian Motion — layered noise for natural-looking surfaces.
   octaves: number of layers (default 4, more = more detail)
   lacunarity: frequency multiplier per octave (default 2.0)
   gain: amplitude multiplier per octave (default 0.5)"
  ([x y] (fbm x y 4))
  ([x y octaves] (fbm x y octaves 2.0 0.5))
  ([x y octaves lacunarity gain]
   (loop [i 0 freq 1.0 amp 1.0 total 0.0 max-amp 0.0]
     (if (>= i octaves)
       (/ total max-amp)  ;; normalize to [-1, 1]
       (recur (inc i)
              (* freq lacunarity)
              (* amp gain)
              (+ total (* amp (noise (* x freq) (* y freq))))
              (+ max-amp amp))))))
```

Usage:

```clojure
;; Simple noise: smooth, blobby
(noise x y)

;; fbm: rough, detailed, natural
(fbm x y)           ;; 4 octaves (default)
(fbm x y 6)         ;; 6 octaves (more detail)
(fbm x y 3 2.0 0.5) ;; custom lacunarity and gain
```

---

## noisy — Built-in Shape-fn

A convenience shape-fn that applies noise-based displacement to any shape.

### API

```clojure
(noisy shape :amplitude a :scale s)
(noisy shape :amplitude a :scale-x sx :scale-y sy)
(noisy shape :amplitude a :scale s :octaves n)
(noisy shape :amplitude a :scale s :seed k)
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `:amplitude` | 1.0 | Maximum displacement distance |
| `:scale` | 3.0 | Noise frequency (higher = more features) |
| `:scale-x` | `:scale` | Angular frequency (around the profile) |
| `:scale-y` | `:scale` | Longitudinal frequency (along the path) |
| `:octaves` | 1 | Number of noise octaves (1 = smooth, 4+ = rough/detailed) |
| `:seed` | 0 | Offset added to noise coordinates (different seed = different pattern) |

### Implementation

```clojure
(defn noisy [shape-or-fn & {:keys [amplitude scale scale-x scale-y octaves seed]
                             :or {amplitude 1.0 scale 3.0 octaves 1 seed 0}}]
  (let [sx (or scale-x scale)
        sy (or scale-y scale)
        noise-fn (if (= octaves 1) noise fbm)]
    (displaced shape-or-fn
      (fn [p t]
        (let [a (angle p)
              nx (+ (* a sx) seed)
              ny (+ (* t sy) seed)]
          (* amplitude (if (= octaves 1)
                         (noise nx ny)
                         (fbm nx ny octaves))))))))
```

### Examples

```clojure
;; Basic rough cylinder
(register rough
  (loft-n 64
    (noisy (circle 15 64) :amplitude 1.5 :scale 3)
    (f 60)))

;; Rocky texture with fine detail (multiple octaves)
(register rocky
  (loft-n 64
    (noisy (circle 15 64) :amplitude 2 :scale 3 :octaves 4)
    (f 60)))

;; Subtle organic surface
(register organic
  (loft-n 48
    (noisy (circle 20 48) :amplitude 0.5 :scale 5)
    (f 40)))

;; Different patterns with seed
(register rock-a
  (loft-n 64
    (noisy (circle 15 64) :amplitude 2 :scale 3 :seed 0)
    (f 60)))

(reset [40 0 0])
(register rock-b
  (loft-n 64
    (noisy (circle 15 64) :amplitude 2 :scale 3 :seed 42)
    (f 60)))

;; Composed with other shape-fns
(register rough-column
  (loft-n 64
    (-> (circle 15 48)
        (noisy :amplitude 0.8 :scale 4)
        (tapered :to 0.85))
    (f 80)))

;; Different frequency along path vs around profile
(register bark
  (loft-n 64
    (noisy (circle 12 64) :amplitude 1.5 :scale-x 8 :scale-y 3)
    (f 50)))
```

---

## mesh-to-heightmap — Displacement from 3D Geometry

### Motivation

Some surface patterns are very hard to express as displacement math but easy to build
as actual 3D geometry. A woven fabric, for instance, is trivially modeled as interlocking
tubes, but the mathematical formula for the equivalent displacement is elusive.

`mesh-to-heightmap` bridges this gap: generate any 3D mesh, sample its height profile
onto a 2D grid, and use the resulting heightmap as a displacement source for shape-fns.

This is a general-purpose tool. Any mesh can become a displacement pattern:

- Interlocking tubes → woven fabric
- 3D text → embossed lettering
- Gear teeth → mechanical knurling
- A sculpted relief → bas-relief ornament
- Geometric patterns → tileable surface textures

### Concept

```
  3D Mesh (pattern)          Heightmap (2D grid)          Displaced surface
  ┌─────────────────┐        ┌─────────────────┐         ┌─────────────────┐
  │  ╲   ╱  ╲   ╱  │        │ ░░▓▓░░▓▓░░▓▓░░ │         │  ~~~∿~~~∿~~~∿  │
  │   ╲ ╱    ╲ ╱   │  ──►   │ ▓▓░░▓▓░░▓▓░░▓▓ │  ──►    │ ∿~~~∿~~~∿~~~∿  │
  │   ╱ ╲    ╱ ╲   │ sample │ ░░▓▓░░▓▓░░▓▓░░ │ displace │  ~~~∿~~~∿~~~∿  │
  │  ╱   ╲  ╱   ╲  │        │ ▓▓░░▓▓░░▓▓░░▓▓ │         │ ∿~~~∿~~~∿~~~∿  │
  └─────────────────┘        └─────────────────┘         └─────────────────┘
```

1. **Generate** a 3D mesh representing the pattern (any Ridley code)
2. **Sample** the mesh from above: for each (x, y) grid cell, find the maximum z
3. **Store** as a 2D array of height values (the heightmap)
4. **Use** inside a `displaced` shape-fn, mapping profile angle → x, path progress → y

### API

#### mesh-to-heightmap

Convert a mesh to a heightmap by sampling maximum z values on a grid:

```clojure
(mesh-to-heightmap mesh :resolution 128)
(mesh-to-heightmap mesh :resolution 128 :bounds [x-min y-min x-max y-max])
(mesh-to-heightmap mesh :resolution 128 :offset-x 0 :offset-y 0 :length-x 10 :length-y 10)
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `:resolution` | 128 | Grid size (NxN cells) |
| `:bounds` | auto (mesh AABB) | Sampling region `[x-min y-min x-max y-max]` |
| `:offset-x` | mesh x-min | X origin for cropping region |
| `:offset-y` | mesh y-min | Y origin for cropping region |
| `:length-x` | mesh x-span | Width of cropping region |
| `:length-y` | mesh y-span | Height of cropping region |

The `:offset-x`/`:offset-y`/`:length-x`/`:length-y` parameters provide an alternative to `:bounds` for specifying the sampling region. They define a rectangle starting at `(offset-x, offset-y)` with the given lengths. Any parameter not provided falls back to the mesh's actual extents. This is useful for cropping a heightmap to a specific area of a larger mesh.

Returns a heightmap object:

```clojure
{:type :heightmap
 :data Float32Array     ;; NxN grid of z values, normalized to [0, 1]
 :width N
 :height N
 :bounds [x-min y-min x-max y-max]
 :z-min original-min
 :z-max original-max}
```

#### sample-heightmap

Read a value from a heightmap with bilinear interpolation:

```clojure
(sample-heightmap hm u v)    ;; u, v in [0, 1] → height in [0, 1]
```

Bilinear interpolation between the four surrounding grid cells for smooth results.
Out-of-range u, v are wrapped (mod 1) for automatic tiling.

#### heightmap-to-mesh

Convert a heightmap back to a flat mesh for visualization or debugging. The mesh lies in the XY plane with Z values from the heightmap:

```clojure
(heightmap-to-mesh hm)                  ;; original scale and position
(heightmap-to-mesh hm :z-scale 5)       ;; amplify Z by 5x
(heightmap-to-mesh hm :size 20)         ;; fit into 20x20 square centered at origin
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `:z-scale` | 1.0 | Multiplier for Z values |
| `:size` | nil | Normalize XY to a square of this size centered at origin; Z is scaled proportionally |

When `:size` is provided, the mesh is repositioned so the XY extent fits within `[-size/2, size/2]` and Z is scaled proportionally. This is useful for previewing heightmaps at a consistent size regardless of the source mesh dimensions.

#### weave-heightmap

Generate a weave pattern heightmap analytically, without building an intermediate mesh. This is much faster than constructing tube geometry and rasterizing it with `mesh-to-heightmap`. Returns a heightmap struct usable with the `heightmap` shape-fn.

```clojure
(weave-heightmap)                                          ;; defaults
(weave-heightmap :threads 6 :spacing 4 :radius 1.5)       ;; custom weave
(weave-heightmap :threads 8 :profile :flat :thickness 1)   ;; flat ribbons
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `:threads` | 4 | Threads per direction in one tile (must be even for seamless tiling) |
| `:spacing` | 5 | Center-to-center thread distance |
| `:radius` | 2 | Thread radius |
| `:lift` | same as `:radius` | Over/under amplitude (how far threads rise above/below the midline) |
| `:resolution` | 128 | Heightmap grid size (NxN) |
| `:profile` | `:round` | Thread cross-section: `:round` (circular) or `:flat` (ribbon) |
| `:thickness` | `radius * 0.5` | Ribbon thickness (only used with `:profile :flat`) |

The function computes a single tile of size `threads * spacing` in each direction. Each thread follows a sinusoidal over/under path with the specified lift. At crossing points, the thread on top is determined by a checkerboard rule (alternating warp-on-top / weft-on-top). The result is normalized to [0, 1] and can be tiled seamlessly when `:threads` is even.

#### mesh-bounds

Return the 3D axis-aligned bounding box of a mesh. Useful for inspecting mesh extents when setting up heightmap cropping parameters.

```clojure
(mesh-bounds my-mesh)
;; => {:x [-10 10] :y [-5 5] :z [0 3]}
```

Returns a map with `:x`, `:y`, and `:z` keys, each containing a `[min max]` vector.

#### concat-meshes

Concatenate multiple meshes into one by combining their vertices and faces. Unlike `mesh-union`, this does NOT perform boolean operations -- it simply merges the geometry. The result is not manifold-valid but works for heightmap sampling, visualization, and similar use cases where geometric correctness is not required.

```clojure
(concat-meshes [mesh1 mesh2 mesh3])     ;; vector of meshes
(concat-meshes mesh1 mesh2 mesh3)       ;; variadic
```

This is useful when building complex heightmap source patterns from many pieces (e.g., an array of tubes for a weave) where a boolean union would be slow and unnecessary.

#### heightmap (shape-fn)

Create a shape-fn that displaces a shape using a heightmap:

```clojure
(heightmap shape hm :amplitude a)
(heightmap shape hm :amplitude a :tile-x nx :tile-y ny)
(heightmap shape hm :amplitude a :center true)
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `:amplitude` | 1.0 | Maximum displacement distance |
| `:tile-x` | 1 | Number of horizontal repeats (around profile) |
| `:tile-y` | 1 | Number of vertical repeats (along path) |
| `:offset-x` | 0 | Horizontal offset (0-1) for pattern alignment |
| `:offset-y` | 0 | Vertical offset (0-1) for pattern alignment |
| `:center` | false | Shift heightmap values from [0, 1] to [-0.5, +0.5] |

When `:center` is `true`, each sampled value is shifted by -0.5 before being multiplied by amplitude. This means the displacement ranges from `-amplitude/2` to `+amplitude/2` instead of `0` to `amplitude`. This is useful for patterns like weaves where the displacement should go both inward and outward from the base surface.

### Implementation

#### mesh-to-heightmap

The algorithm rasterizes each triangle of the mesh onto the grid, recording
the maximum z at each cell. This is equivalent to a z-buffer render from above.

```clojure
(defn mesh-to-heightmap [mesh & {:keys [resolution bounds]
                                  :or {resolution 128}}]
  (let [verts (:vertices mesh)
        faces (:faces mesh)
        ;; Auto-detect bounds from mesh AABB if not provided
        [x-min y-min x-max y-max] (or bounds (mesh-xy-bounds verts))
        w resolution
        h resolution
        data (js/Float32Array. (* w h))
        ;; Initialize to -Infinity
        _ (dotimes [i (* w h)] (aset data i js/Number.NEGATIVE_INFINITY))
        ;; Map world coordinates to grid coordinates
        scale-x (/ (- x-max x-min) w)
        scale-y (/ (- y-max y-min) h)]

    ;; For each triangle, rasterize onto the grid
    (doseq [face faces]
      (let [v0 (nth verts (nth face 0))
            v1 (nth verts (nth face 1))
            v2 (nth verts (nth face 2))]
        (rasterize-triangle! data w h
                             x-min y-min scale-x scale-y
                             v0 v1 v2)))

    ;; Normalize z values to [0, 1]
    (let [z-min (areduce data i m js/Number.POSITIVE_INFINITY
                         (min m (aget data i)))
          z-max (areduce data i m js/Number.NEGATIVE_INFINITY
                         (max m (aget data i)))
          z-range (- z-max z-min)]
      (when (> z-range 0)
        (dotimes [i (* w h)]
          (let [v (aget data i)]
            (aset data i (if (js/isFinite v)
                           (/ (- v z-min) z-range)
                           0)))))

      {:type :heightmap
       :data data
       :width w :height h
       :bounds [x-min y-min x-max y-max]
       :z-min z-min :z-max z-max})))
```

#### rasterize-triangle!

For each triangle, find its bounding box on the grid, then for each cell inside,
check if the cell center is inside the triangle (using barycentric coordinates)
and if so, interpolate the z value and keep the maximum:

```clojure
(defn- rasterize-triangle! [data w h x-min y-min sx sy v0 v1 v2]
  (let [;; Grid coordinates of triangle bounding box
        gx0 (max 0 (int (floor (/ (- (min (v0 0) (v1 0) (v2 0)) x-min) sx))))
        gy0 (max 0 (int (floor (/ (- (min (v0 1) (v1 1) (v2 1)) y-min) sy))))
        gx1 (min (dec w) (int (floor (/ (- (max (v0 0) (v1 0) (v2 0)) x-min) sx))))
        gy1 (min (dec h) (int (floor (/ (- (max (v0 1) (v1 1) (v2 1)) y-min) sy))))]
    (doseq [gy (range gy0 (inc gy1))
            gx (range gx0 (inc gx1))]
      (let [;; Cell center in world coordinates
            px (+ x-min (* (+ gx 0.5) sx))
            py (+ y-min (* (+ gy 0.5) sy))
            ;; Barycentric coordinates
            [u v w] (barycentric px py v0 v1 v2)]
        (when (and (>= u 0) (>= v 0) (>= w 0))
          (let [z (+ (* u (v0 2)) (* v (v1 2)) (* w (v2 2)))
                idx (+ gx (* gy w))]
            (when (> z (aget data idx))
              (aset data idx z))))))))
```

#### sample-heightmap

```clojure
(defn sample-heightmap [hm u v]
  (let [u (mod u 1)  ;; wrap for tiling
        v (mod v 1)
        x (* u (dec (:width hm)))
        y (* v (dec (:height hm)))
        ix (int (floor x))
        iy (int (floor y))
        fx (- x ix)
        fy (- y iy)
        w (:width hm)
        data (:data hm)
        ;; Four corners (with wrapping)
        i00 (+ (mod ix w) (* (mod iy w) w))
        i10 (+ (mod (inc ix) w) (* (mod iy w) w))
        i01 (+ (mod ix w) (* (mod (inc iy) w) w))
        i11 (+ (mod (inc ix) w) (* (mod (inc iy) w) w))]
    ;; Bilinear interpolation
    (+ (* (aget data i00) (- 1 fx) (- 1 fy))
       (* (aget data i10) fx (- 1 fy))
       (* (aget data i01) (- 1 fx) fy)
       (* (aget data i11) fx fy))))
```

#### heightmap shape-fn

```clojure
(defn heightmap [shape-or-fn hm & {:keys [amplitude tile tile-x tile-y
                                           offset-x offset-y]
                                    :or {amplitude 1.0 tile false
                                         tile-x 1 tile-y 1
                                         offset-x 0 offset-y 0}}]
  (let [tx (if tile tile-x tile-x)
        ty (if tile tile-y tile-y)]
    (displaced shape-or-fn
      (fn [p t]
        (let [;; Map angle to u (0-1 around the profile)
              u (+ offset-x (* tx (/ (+ (angle p) PI) (* 2 PI))))
              ;; Map t to v (0-1 along the path)
              v (+ offset-y (* ty t))]
          (* amplitude (sample-heightmap hm u v)))))))
```

### Usage Examples

#### Woven Fabric

The `woven` shape-fn produces an interlocking over/under thread pattern using
a mathematical raised-cosine profile. No intermediate mesh is needed — the
displacement is computed directly per vertex.

```clojure
;; Basic woven cylinder
(register woven-cup
  (loft-n 96
    (woven (circle 18 96) :warp 6 :weft 4 :amplitude 1.5)
    (f 50)))

;; Dense weave with thin threads
(register fine-weave
  (loft-n 128
    (woven (circle 15 128) :warp 12 :weft 10 :amplitude 0.8 :thread 0.38)
    (f 60)))

;; Composed with taper
(register woven-cone
  (loft-n 96
    (-> (circle 20 96) (woven :warp 8 :weft 5 :amplitude 1.2) (tapered :to 0.3))
    (f 60)))
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `:warp` | 6 | Number of warp threads (around the profile) |
| `:weft` | 4 | Number of weft threads (along the path) |
| `:amplitude` | 1.0 | Maximum radial displacement |
| `:thread` | 0.42 | Thread width as fraction of cell (0–0.5) |

#### Woven Fabric via Heightmap

An alternative approach uses `weave-heightmap` to generate an analytical weave pattern and applies it with the `heightmap` shape-fn. This is useful when you want to combine the weave with `:center` for symmetric displacement or with tiling control:

```clojure
;; Generate a weave heightmap tile
(def whm (weave-heightmap :threads 6 :spacing 4 :radius 1.5))

;; Apply centered (displacement goes both in and out)
(register woven-vase
  (loft-n 96
    (heightmap (circle 18 96) whm
      :amplitude 2 :tile-x 6 :tile-y 4 :center true)
    (f 50)))

;; Flat ribbon weave
(def ribbon-hm (weave-heightmap :threads 4 :profile :flat :thickness 1))
(register ribbon-basket
  (loft-n 96
    (heightmap (circle 20 96) ribbon-hm
      :amplitude 1.5 :tile-x 8 :tile-y 6 :center true)
    (f 60)))
```

#### Embossed Text

```clojure
;; 3D text as heightmap source
(def text-mesh (extrude (text-shape "RIDLEY" :size 20) (f 3)))
(def text-hm (mesh-to-heightmap text-mesh :resolution 256))

;; Apply as embossing on a cylinder
(register embossed
  (loft-n 64
    (heightmap (circle 20 128) text-hm :amplitude 1.5 :tile-x 2)
    (f 60)))
```

#### Tileable Pattern

```clojure
;; Generate a single tile of a pattern
(def tile-mesh
  (mesh-union
    (sphere 3)
    (do (reset [8 0 0]) (sphere 3))
    (do (reset [0 8 0]) (sphere 3))
    (do (reset [8 8 0]) (sphere 3))))

(def tile-hm (mesh-to-heightmap tile-mesh :resolution 64
               :bounds [0 0 8 8]))  ;; exact tile bounds for seamless repeat

;; Tile across a surface
(register dotted
  (loft-n 64
    (heightmap (circle 15 96) tile-hm
      :amplitude 1 :tile-x 8 :tile-y 6)
    (f 50)))
```

### Design Notes

#### Why Z-max sampling?

Taking the maximum z at each grid cell is equivalent to looking at the mesh from above.
This naturally handles overlapping geometry (e.g., crossing threads in a weave — the
thread on top determines the height). It also handles non-manifold input gracefully.

#### Performance

Rasterization is O(total_triangle_pixels), which for a 256×256 grid and a mesh with
a few thousand triangles takes milliseconds. The heightmap is computed once and reused
for all rings during loft, so the per-ring cost is just bilinear interpolation
(4 array lookups per vertex).

For very complex source meshes, reduce `:resolution` during design and increase for export.

#### Tiling

For seamless tiling, the source mesh should be designed with matching edges. Use
`:bounds` to specify exact tile boundaries. The `sample-heightmap` function wraps
u/v coordinates with `mod`, so tiling is automatic.

#### Limitations

- Sampling is from above only (Z-axis). Undercuts and overhangs are not captured.
- The heightmap is 2D — it cannot represent geometry where multiple surfaces overlap
  at different heights (it keeps only the topmost). For a weave this is correct
  (you see only the top thread), but for more complex geometry it's a simplification.
- Resolution limits detail. A 128×128 grid captures features down to ~1/128th of
  the mesh bounding box. Increase resolution for fine detail.

---

## Registration in SCI

All functions should be registered in the SCI context:

```clojure
;; Low-level
'noise              noise
'fbm                fbm
'mesh-to-heightmap  mesh-to-heightmap
'sample-heightmap   sample-heightmap
'heightmap-to-mesh  heightmap-to-mesh
'weave-heightmap    weave-heightmap
'mesh-bounds        mesh-bounds
'concat-meshes      concat-meshes

;; Shape-fns (add to existing shape-fn registrations)
'noisy              noisy
'woven              woven
'heightmap          heightmap
```

---

## Design Notes

### Why value noise instead of Perlin noise?

Value noise is simpler to implement (no gradient computation, no permutation table) and produces results that are good enough for geometric displacement. The visual difference between value noise and Perlin noise is subtle, especially after displacement where the geometry is viewed from the outside. If higher quality is needed later, the `noise` function can be swapped for a Perlin implementation without changing the API.

### Why fbm as a separate function?

Keeping `noise` as single-octave and `fbm` as multi-octave gives the user control over complexity and performance. Single-octave noise is faster and produces smooth, blobby surfaces. Multi-octave fbm is slower but produces richer detail. The `noisy` shape-fn exposes this via the `:octaves` parameter.

### Composability

`noisy` and `heightmap` follow the shape-fn protocol — they accept a shape or another shape-fn and return a shape-fn. This means they compose freely with `->`, `tapered`, `twisted`, and all other shape-fns:

```clojure
;; Noisy surface that tapers
(-> (circle 20 64) (noisy :amplitude 1 :scale 3) (tapered :to 0.5))

;; Heightmap texture on a twisted bar
(-> (rect 20 5) (heightmap weave-hm :amplitude 0.5) (twisted :angle 90))
```

### Performance Considerations

- `noise` and `fbm` are pure functions with no side effects — safe for parallel evaluation
- `noisy` with 1 octave: ~1 noise call per vertex per ring
- `noisy` with 4 octaves: ~4 noise calls per vertex per ring
- `heightmap`: ~4 array lookups per vertex per ring (bilinear interpolation)
- `mesh-to-heightmap`: one-time cost at definition time, milliseconds for typical meshes
- For interactive use, keep `loft-n` step count moderate (32–64) during design, increase for export
