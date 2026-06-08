<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE

Struttura del capitolo:
6.1  Cosa sono le shape-fn
6.2  Trasformazioni geometriche: tapered, twisted, fluted, rugged
6.3  Displacement: noisy, displaced
6.4  morphed: interpolare tra due profili
6.5  profile: il profilo come silhouette
6.6  heightmap: displacement da mappa di altezze
6.7  mesh-to-heightmap e heightmap tileabili
6.8  Comporre shape-fn
6.9  Thickness-fn: controllare lo spessore delle pareti
6.10 Scrivere una shape-fn propria

Prerequisiti: cap. 3 (shape 2D), cap. 4 (extrude, loft, shell intro), cap. 5 (path).
Il cap. 4 ha già introdotto shell/woven-shell (4.6), capped (4.7), composizione
via threading. Qui si approfondisce il meccanismo e si coprono le shape-fn
che il cap. 4 non ha toccato.

Vincoli:
- shell deve essere l'ultima nella catena di composizione
- embroid non si compone in catena: stampa le proprie facce (cap. 4.6), eccezione più forte di shell
- loft-n controlla i passi longitudinali (resolution non influenza loft)
- shell/woven-shell richiedono risoluzione ~512 su entrambi gli assi
=========================================================================
-->

# From mathematical functions to shapes

> This chapter is among the most advanced in the manual. Shape-fns are a powerful tool, but not essential to start modeling: with the primitives, extrusions, and boolean operations of the previous chapters you can already build a lot. If you are reading the manual for the first time, you can skip to chapter 7 and come back here when you feel the need for surfaces that vary along the path, procedural textures, or decorated shells.

## What shape-fns are

In chapter 4 we saw that `extrude` drags an identical shape along a path. Every ring is an exact copy of the starting profile. When the profile has to change along the path (taper, twist, ripple), you need `loft`, and you need a way to tell `loft` *how* the profile changes.

That way is the shape-fns: functions that, given a value `t` between 0 and 1, return a shape. `t = 0` is the start of the path, `t = 1` is the end. The loft evaluates the shape-fn at each step and uses the resulting profile as the shape for the current step. At the end of the process the side faces are generated (joining the corresponding vertices of all the produced shapes with new segments) along with the start and end faces (the caps). All of this is possible only if every step produces shapes with the same number of vertices. It is an important constraint: shape-fns can move, scale, rotate the points of the profile, but they cannot add or remove any. If two shapes have a different number of points and have to coexist in a chain, you use `resample-shape` to bring them to the same count before passing them to the shape-fn (some, like `morphed`, do it automatically).

<!-- example-source: shapefn-intro-tapered -->
```clojure
(register cone
  (loft (tapered (circle 20) :to 0) (f 40)))
```

`tapered` takes a circle and returns a shape-fn. At `t = 0` the circle has radius 20; at `t = 1` the radius is 0 (`:to 0`). In between, the radius decreases linearly. The loft produces a cone.

The same logic holds for all the built-in shape-fns: `twisted`, `fluted`, `noisy`, `morphed`, `profile`, `heightmap`. Each describes a different kind of variation, but the mechanism is the same: a function from `t` to a shape, which `loft` (or `revolve`) evaluates step by step.

`loft` also accepts a simpler form in which the first parameter is a shape and the second is a transformation function `(fn [shape t] -> shape)`. Shape-fns make explicit what is implicit there: the variation logic lives *inside* the shape, not outside. This is what lets you compose several variations with `->` threading:

<!-- example-source: shapefn-intro-compose -->
```clojure
(register column
  (loft
    (-> (circle 15 128)
        (fluted :flutes 20 :depth 1.5)
        (tapered :to 0.25))
    (f 80)))
```

A fluted circle that tapers. Each shape-fn wraps the previous one: `fluted` receives the circle, `tapered` receives the result of `fluted`. The loft evaluates the chain at each step, and each step produces a fluted profile slightly narrower than the previous one.


## Geometric transformations

Four shape-fns modify the geometry of the profile in different ways along the path.

### tapered

Scales the profile uniformly. At `t = 0` the profile has scale 1 (or the value of `:from`); at `t = 1` it has the scale given by `:to`.

<!-- example-source: shapefn-tapered -->
```clojure
;; Cone: from radius 20 to radius 0
(register cone (loft (tapered (circle 20) :to 0) (f 40)))
(rt 50)

;; Truncated cone: from radius 20 to radius 10
(register frustum (loft (tapered (circle 20) :to 0.5) (f 40)))
(rt 50)

;; Expansion: from radius 10 to radius 20
(register horn (loft (tapered (circle 10) :from 0.5 :to 1) (f 40)))
```

`:to` and `:from` are ratios relative to the original size of the profile: `0.5` halves it, `2` doubles it. If you omit `:from`, the starting value is 1.

`tapered` works with any shape, not just circles. A tapered rectangle produces a truncated pyramid; a custom shape produces a section that narrows while keeping its proportions.

### twisted

Rotates the profile progressively along the path.

<!-- example-source: shapefn-twisted -->
```clojure
;; Rectangle that rotates by 90° along the path
(register ribbon
  (loft (twisted (rect 30 5) :angle 90) (f 60)))
(rt 60)

;; Full twist (360° default)
(register drill
  (loft (twisted (rect 20 10)) (f 80)))
```

`:angle` is in degrees. If you omit it, the default is 360 (a full turn). The rotation is linear with respect to `t`.

`twisted` is more visible on asymmetric profiles: a rotated circle is identical to itself, so the twist produces no visible effect (except at very low resolution, where the circle is actually a polygon). Rectangles, stars, L-profiles show the spiral well.

### fluted

Adds longitudinal flutes to the profile. The depth of the flutes is constant along the whole path (it does not vary with `t`).

<!-- example-source: shapefn-fluted -->
```clojure
;; Doric column: 20 flutes
(register pillar
  (loft (fluted (circle 15 128) :flutes 20 :depth 1.5) (f 80)))
```

`:flutes` is the number of flutes; `:depth` is the radial depth. The profile must have enough points for the flutes to be well defined: as a practical rule, at least 6 points per flute. A `(circle 15 128)` has 128 points, enough for 20 flutes.

### rugged

Adds a radial perturbation made of several superimposed sine waves (fBm style), which varies both around the profile and along the path. Unlike `fluted` (a single regular sine wave with crests parallel to the axis), `rugged` produces irregular asperities at several scales, useful for rocky surfaces, bark, cliffs.

<!-- example-source: shapefn-rugged -->
```clojure
(register rough-tube
  (loft (rugged (circle 15 256) :amplitude 2 :frequency 8 :octaves 2) (f 40)))
```

Parameters:
- `:amplitude` — maximum radial displacement.
- `:frequency` — base number of oscillations of the first octave around the profile.
- `:octaves` (default 3) — how many superimposed sine waves; each octave doubles the frequency. With `:octaves 1` it goes back to a single sine wave.
- `:gain` (default 0.5) — relative amplitude of each octave (0.5 = standard fBm, higher = rougher).
- `:seed` — phase shift to vary the pattern without changing its characteristics.

The profile must have enough points to capture the highest octave: with `:frequency F` and `:octaves N` the maximum frequency is `F · 2^(N-1)`. For `:frequency 8 :octaves 2` the final frequency is 16, so you need at least ~64 points per ring. The example above uses 256 points to have margin, and the default of 64 longitudinal steps is enough.


## Displacement

The displacement shape-fns move the vertices of the profile non-uniformly, producing organic or textured surfaces.

### noisy

Moves the vertices radially using a continuous noise function. Unlike `rugged` (superimposed sine waves, with an angular/crystalline look), `noisy` produces softer, more organic variations thanks to the interpolation of Perlin-like noise.

<!-- example-source: shapefn-noisy -->
```clojure
(register organic
  (loft (noisy (circle 15 64) :amplitude 2 :scale 3) (f 40)))
```

The options control the character of the noise:

- `:amplitude` (default 1.0) controls the magnitude of the displacement.
- `:scale` (default 3.0) controls the spatial scale: low values produce wide, soft variations, high values produce dense, nervous ones.
- `:scale-x` and `:scale-y` let you have different scales in the two directions (around the profile and along the path).
- `:octaves` (default 1) adds superimposed detail: more octaves produce a richer surface, like mountainous terrain.
- `:seed` (default 0) changes the pattern while keeping the same characteristics.

<!-- example-source: shapefn-noisy-detailed -->
```clojure
;; Detailed organic surface
(register bark
  (loft
    (noisy (circle 12 96) :amplitude 1.5 :scale 5 :octaves 3)
    (f 60)))
```

The default `loft` uses 64 longitudinal steps. For very dense patterns or with high `:octaves`, you may need to raise them with `loft-n 128` or more.

### displaced

The most flexible tool: it moves each vertex radially according to an arbitrary function.

<!-- example-source: shapefn-displaced -->
```clojure
(register ripple
  (loft
    (displaced (circle 15 64)
      (fn [p t] (* 2 (sin (* t PI 4)) (sin (* (angle p) 6)))))
    (f 40)))
```

The function receives two arguments: `p` (the 2D point `[x y]` on the profile, before displacement) and `t` (the position along the path, 0-1). It returns a number: the radial displacement in units. Positive values move outward, negative values inward.

`angle` is a utility function that returns the angle in radians of a 2D point relative to the origin. Useful for patterns that depend on the angular position on the profile.

`displaced` is the shape-fn to use when none of the built-ins does what you need. Any pattern expressible as `f(position_on_profile, position_on_path) -> radial_displacement` can be written as `displaced`.

Inside a `displaced` you can call any math function. For organic or rocky surfaces, `fbm` (fractal Brownian motion) is the most useful building block: it sums several octaves of noise at increasing frequencies and decreasing amplitudes, and produces detail at several scales.

<!-- example-source: shapefn-fbm-rocky -->
```clojure
(register rocky
  (loft (displaced (circle 15 96)
          (fn [p t] (* 2 (fbm (* (angle p) 4) (* t 6) 4))))
    (f 50)))
```

`(fbm x y)` samples the noise field at those coordinates; the third argument is the number of octaves, more octaves more detail. Here the point's angle and the position along the path become the two coordinates of the noise. It is the same mechanism that `noisy` uses internally with `:octaves > 1`: if the standard radial displacement is enough for you, `noisy` is more direct; `fbm` inside `displaced` is for when you want full control over the formula.


## morphed: interpolating between two profiles

`morphed` interpolates between two different shapes along the path. At `t = 0` the profile is the first shape; at `t = 1` it is the second. In between, the points are interpolated linearly.

<!-- example-source: shapefn-morphed -->
```clojure
(register transition
  (loft (morphed (rect 20 20) (circle 15 32)) (f 40)))
```

A square that becomes a circle. `morphed` automatically handles two things that would make the transition problematic:

1. **Point count**: if the two shapes have a different number of points, both are resampled to the maximum count.
2. **Angular alignment**: vertex `i` of `shape-a` is paired with the angularly nearest vertex of `shape-b`. Without this step, pairing for example the first vertex of a rectangle `(-10,-10)` with the first of a circle `(15,0)`, the interpolation would pass near the origin, producing a self-intersecting polygon (a bowtie). With the alignment, the intermediate point is a rounded square.

<!-- example-source: shapefn-morphed-star -->
```clojure
(register star-to-circle
  (loft (morphed (star 5 20 10) (circle 12 64)) (f 50)))
```

The result of `morphed` is a geometric transition, not a topological one: if the two shapes have very different concavity structures, even with the alignment the intermediate points can self-intersect. Profiles with the same concavity structure (both convex, or both 5-pointed like a star and a pentagon) produce cleaner transitions.

There is also the raw form of morphing: `loft` called with two shapes, also documented under the explicit name `loft-between`. It interpolates linearly between the two at each ring, but requires them to have the same number of points and does not do the angular alignment of `morphed`. For transitions between different shapes `morphed` remains the right choice; `loft-between` is handy when the two shapes are already compatible and you only want the direct interpolation.


## profile: the profile as a silhouette

`profile` scales the cross-section to follow a silhouette defined by a path. The path is read as a 2D profile where the X axis represents the radius and the Y axis represents the height.

<!-- example-source: shapefn-profile -->
```clojure
(def vase-silhouette
  (path (f 5) (th 80) (f 15) (arc-h 5 -160) (f 15)))

(register vase
  (loft (-> (circle 20 64) (profile vase-silhouette)) (f 40)))
```

At each step `t`, `profile` reads the X coordinate of the path at the corresponding position and scales the base profile accordingly. Where the path widens, the vase swells; where it narrows, the vase tightens.

The silhouette path works best when it is smooth. Abrupt angles produce steps in the surface. For complex silhouettes, a `bezier-as` on the path produces better results:

<!-- example-source: shapefn-profile-smooth -->
```clojure
(def raw-sil (path (f 3) (th 60) (f 10) (th -120) (f 10) (th 60) (f 3)))

(register smooth-vase
  (loft (-> (circle 15 64) (profile (path (bezier-as raw-sil)))) (f 50)))
```

`profile` is the right tool for asymmetric solids of revolution: vases, bottles, table legs, columns that swell slightly at mid-height. The base profile (circle, square, star) determines the cross-section; the silhouette determines how it varies in height.


## heightmap: displacement from a height map

`heightmap` uses a 2D grid of values as a displacement map. Each cell of the grid corresponds to a radial displacement. The map is "wrapped" around the profile (X axis of the map = angular position, Y axis = position along the path) and applied as displacement.

<!-- example-source: shapefn-heightmap -->
```clojure
(def weave (weave-heightmap :threads 6 :spacing 5 :radius 2 :resolution 128))

(register woven-cylinder
  (loft-n 128
    (heightmap (circle 20 128) weave :amplitude 3)
    (f 60)))
```

`:amplitude` controls the magnitude of the displacement. The heightmap repeats automatically (tiling) both horizontally and vertically, so a small map can cover large surfaces.

The tiling options control how many repetitions of the map are mapped onto the surface:

- `:tile-x` (default 1): repetitions in the angular direction.
- `:tile-y` (default 1): repetitions in the path direction.
- `:offset-x`, `:offset-y` (default 0): initial phase shift.
- `:center` (default false): if true, shifts the sampling range to [-0.5, 0.5].

A heightmap can come from three sources: `weave-heightmap` (an analytic weave pattern), `mesh-to-heightmap` (rasterization from 3D geometry), or built by hand as a grid of values.

Resolution matters on both axes. The heightmap itself must have enough pixels not to look blurry. The profile must have enough points for the displacement to be legible. And the loft must have enough longitudinal steps. For legible results, 128 is a good starting point for all three.


## mesh-to-heightmap and tileable heightmaps

### mesh-to-heightmap

Any mesh can become a heightmap. `mesh-to-heightmap` looks at the mesh from above (the Z axis), rasterizes the Z values into a 2D grid, and returns a heightmap ready to use with the `heightmap` shape-fn.

<!-- example-source: shapefn-mesh-to-heightmap -->
```clojure
;; A sphere becomes a dome-shaped height map
(def dome-hm (mesh-to-heightmap (sphere 10 32 16) :resolution 128))

(register embossed
  (loft-n 256
    (heightmap (circle 20 256) dome-hm :amplitude 2 :tile-x 4 :tile-y 4)
    (f 60)))
```

The options of `mesh-to-heightmap` control the sampling window:

- `:resolution` (default 128): size of the grid (pixels per side).
- `:bounds` `[x0 y0 x1 y1]`: XY area to rasterize. If omitted, it uses the mesh's bounding box.
- `:offset-x`, `:offset-y`, `:length-x`, `:length-y`: an alternative way to specify the window (starting point + size).

### weave-heightmap

Analytically generates a heightmap representing a weave pattern. Unlike `mesh-to-heightmap`, it does not start from a mesh but computes the pattern mathematically.

<!-- example-source: shapefn-weave-heightmap -->
```clojure
(def basket (weave-heightmap :threads 4 :spacing 5 :radius 2 :resolution 128))

(register basket-weave
  (loft
    (heightmap (circle 20 128) basket :amplitude 2 :tile-x 4 :tile-y 4)
    (f 60)))
```

The options:

- `:threads` (default 4): number of threads per direction.
- `:spacing` (default 5): distance between the centers of the threads.
- `:radius` (default 2): radius of the thread's section.
- `:lift` (default = radius): how much the thread rises above the crossing.
- `:resolution` (default 128): resolution of the grid.
- `:profile` (`:round` or `:flat`): section of the thread.
- `:thickness` (default radius * 0.5, for `:flat`): thickness of the flat thread.

### heightmap-to-mesh

Converts a heightmap into a flat mesh with Z taken from the map's values. It is a debug tool: the produced mesh is not manifold (it is an open surface, not a closed solid), so it cannot be used in boolean operations nor exported for printing. Its purpose is to let you visualize a heightmap as a 3D surface to check its content before using it with `heightmap`.

<!-- example-source: shapefn-heightmap-to-mesh -->
```clojure
(def dome-hm (mesh-to-heightmap (sphere 10 32 16) :resolution 128))
(def terrain (heightmap-to-mesh dome-hm :z-scale 0.5 :size 40))
(register ground terrain)
```

- `:z-scale` (default 1.0): amplifies the Z values.
- `:size`: scales the mesh so it fits in an N×N square centered on the origin.

### Tileable heightmaps

The heightmap produced by `weave-heightmap` is tileable by construction: the edges match. For heightmaps generated by `mesh-to-heightmap`, tiling works only if the starting mesh has compatible edges. In practice, this is achieved by choosing a sampling window that covers a complete period of the source geometry.

`sample-heightmap` samples a heightmap at arbitrary `u, v` coordinates with bilinear interpolation, and automatically applies tiling (coordinates that fall outside the [0,1] range are wrapped back inside). It is the function used internally by the `heightmap` shape-fn, but it is also exposed for direct uses such as computing offsets in custom functions.

```clojure
;; Sample the value at the center of the heightmap
(sample-heightmap weave 0.5 0.5)
```


## Composing shape-fns

Shape-fns compose with `->` threading. Each shape-fn in the chain wraps the previous one: when the loft evaluates the chain at a given `t`, execution proceeds from the inside outward.

<!-- example-source: shapefn-compose -->
```clojure
;; Fluted column that tapers and twists
(register column
  (loft
    (-> (circle 15 128)
        (fluted :flutes 20 :depth 1.5)
        (tapered :to 0.25)
        (twisted :angle 45))
    (f 80)))
```

The chain reads from top to bottom: circle, then flutes, then taper, then twist. The order matters. `(-> shape (twisted ...) (fluted ...))` produces a different result from `(-> shape (fluted ...) (twisted ...))`: in the first case the flutes are straight on an already rotated profile; in the second the flutes rotate together with the profile.

### The shell constraint

`shell` and `woven-shell` must be **last** in the composition chain. The reason is technical: `shell` annotates the shape with metadata (`:shell-mode`) that the loft reads to generate the double rings (outer + inner). The other shape-fns do not know about this metadata and would lose it.

```clojure
;; tapered is the exception: it can come after shell
(-> (circle 20 64)
    (fluted :flutes 12 :depth 1)
    (shell :thickness 2 :style :voronoi :cells 8 :rows 6)
    (tapered :to 0.6))
```

The exception is `tapered`: `tapered` *after* `shell` works because `tapered` preserves shell's metadata. In practice, the rule reduces to: put `shell` or `woven-shell` after the shape-fns that modify the profile's geometry (`fluted`, `twisted`, `noisy`, etc.), but `tapered` can come either before or after.

### The embroid case

`embroid` (chapter 4.6) is a special shape-fn: in the loft it does not transform the profile step by step like the others, but stamps its own faces directly, already built from the path. For this reason it does not compose with `->`. Any shape-fn placed after `embroid` in the chain is silently ignored, because there is no intermediate profile to act on. It is a stronger exception than shell's: shell only has to come last, embroid does not enter the chain at all. To reposition the result, use embroid's `:offset` option, or translate the mesh after the loft.

### Resolution

Composing shape-fns does not change how many steps the loft takes. If the result looks faceted in the path direction, raise the number of steps with `loft-n`:

<!-- example-source: shapefn-compose-resolution -->
```clojure
(register smooth-column
  (loft-n 128
    (-> (circle 15 128)
        (fluted :flutes 20 :depth 1.5)
        (tapered :to 0.85))
    (f 80)))
```

`resolution` (the global command) also affects the default number of `loft` steps. For explicit control, `loft-n` with the desired number always takes precedence. `resolution` also affects the number of points in the circle (if not specified explicitly) and the curves in the path.


## Thickness-fns: controlling wall thickness

In chapter 4 we used `shell` with the built-in styles (`:voronoi`, `:lattice`, `:weave`, `:checkerboard`). Each of those styles is a prepackaged thickness-fn: a function that tells the loft how thick the wall must be at each point.

The thickness-fn has the signature `(fn [angle t] -> 0..1)`. The two arguments are the coordinates of a point on the surface of the shell:

- `angle`: angular position on the profile, in radians (from 0 to 2π for a closed profile). It says *where* you are around the cross-section.
- `t`: position along the path (from 0 to 1). It says *where* you are along the extrusion.

The returned value is a thickness coefficient: 1 means full wall (thickness = `:thickness`), 0 means no wall (an opening). Intermediate values produce thinner walls. Values below the `:threshold` (default 0.05) are rounded to 0 to avoid degenerate triangles.

<!-- example-source: shapefn-thickness-fn-basic -->
```clojure
;; Horizontal stripe pattern
(register striped
  (loft
    (shell (circle 20 64) :thickness 2
      :fn (fn [a t] (if (> (mod (* t 12) 1) 0.5) 1 0)))
    (f 60)))
```

The function alternates between 1 (wall) and 0 (opening) based on `t`. The result is a hollow cylinder with alternating horizontal bands of wall and air.

### Designing a pattern

Reasoning in `(angle, t)` coordinates is reasoning over a rectangle that is then rolled around the profile. `angle` is the horizontal axis of the rectangle, `t` is the vertical axis. The pattern you draw in the rectangle is mapped onto the surface of the solid.

<!-- example-source: shapefn-thickness-fn-diagonal -->
```clojure
;; Diagonal pattern (45° stripes)
(register diagonal
  (loft
    (shell (circle 20 64) :thickness 2
      :fn (fn [a t]
        (if (> (mod (+ (* a 3) (* t 20)) 1) 0.4) 1 0)))
    (f 60)))
```

Summing `angle` and `t` with different weights gives diagonal stripes. The ratio between the weights controls the angle of the stripes; the threshold (0.4) controls the ratio between solid and empty.

<!-- example-source: shapefn-thickness-fn-sin -->
```clojure
;; Soft sinusoidal variation
(register wavy-shell
  (loft
    (shell (circle 20 64) :thickness 3
      :fn (fn [a t]
        (max 0.1 (sin (+ (* a 8) (* t PI 6))))))
    (f 60)))
```

Using `sin` instead of hard thresholds, the thickness varies continuously. With `max 0.1` as a floor the wall never disappears entirely: it alternates full zones and thinner zones without creating holes. Lowering the floor to 0 would produce real openings but would fragment the structure into many detached islands, one for each crest of the sine wave.

### Custom woven-shell

`woven-shell` has a similar but richer model. The custom function returns a map with two keys: `:thickness` (as for `shell`) and `:offset` (radial displacement of the wall's center). The radial displacement is what creates the real weaving effect, with threads passing in front of one another.

<!-- example-source: shapefn-woven-shell-custom -->
```clojure
(register custom-weave
  (loft
    (woven-shell (circle 20 128) :thickness 3
      :fn (fn [a t]
        {:thickness (if (> (mod (* a 4) 1) 0.3) 0.8 0)
         :offset (* 1.5 (sin (* a 8)) (cos (* t PI 4)))}))
    (f 60)))
```

`:thickness` controls where there is material; `:offset` controls where that material sits relative to the median surface. A positive offset moves the wall outward, a negative one inward.

### Combining patterns: a pen holder

Two shells with different patterns can coexist in the same object. By overlaying the previous weave on the horizontal bands of `striped`, and adding a base, you get a printable pen holder:

<!-- example-source: shapefn-uvase -->
```clojure
(def part-a
  (merge-vertices
    (loft
      (woven-shell (circle 20 128) :thickness 3
        :fn (fn [a t]
              {:thickness (if (> (mod (* a 4) 1) 0.3) 0.8 0)
               :offset (* 1.5 (sin (* a 8)) (cos (* t PI 4)))}))
      (f 60))))

(def part-b
  (merge-vertices
    (loft
      (shell (circle 20 64) :thickness 2
        :fn (fn [a t] (if (> (mod (* t 12) 1) 0.5) 1 0)))
      (f 60))))

(register uvase (mesh-union part-a part-b (cyl 20 3)))
```

`merge-vertices` is the technical detail that makes the union possible. Thickness-fns that generate openings (the `:fn`s above return 0 in some zones) produce duplicate vertices at the edges of the holes. `manifold` refuses boolean operations on meshes with duplicates, and `mesh-union` would fail silently. `merge-vertices` merges the spatially coincident duplicates and makes the mesh usable.

### Relationship with the built-in styles

The built-in styles of `shell` (`:voronoi`, `:lattice`, `:checkerboard`, `:weave`) are nothing more than prepackaged thickness-fns. When you write `(shell shape :thickness 2 :style :lattice :openings 8 :rows 12)`, internally `shell` builds a thickness-fn that produces a grid pattern with those dimensions.

Passing `:fn` overrides the style: the two options are mutually exclusive. If you specify both, `:fn` wins.

For patterns that are variants of a built-in style (a grid with wider openings in the center, a voronoi that fades toward the bottom), it is often simpler to start from the custom thickness-fn than to try to parametrize the built-in style.


## Writing your own shape-fn

The built-in shape-fns cover the most common cases. When you need variations that no built-in expresses, you can write a custom shape-fn with the `shape-fn` function.

<!-- example-source: shapefn-custom-basic -->
```clojure
;; Pulsation: the profile swells and shrinks
(register pulse
  (loft-n 32
    (shape-fn (circle 20)
      (fn [shape t]
        (scale-shape shape (+ 0.6 (* 0.4 (sin (* t PI)))))))
    (f 50)))
```

`shape-fn` takes two arguments: a base shape and a function `(fn [shape t] -> shape)`. The function receives the base shape (or, if the base is itself a shape-fn, the shape evaluated at that `t`) and the value `t`, and returns the transformed shape.

Since the base can be a shape-fn, custom shape-fns compose with the built-ins:

<!-- example-source: shapefn-custom-compose -->
```clojure
;; First taper, then pulsation
(register tapered-pulse
  (loft-n 32
    (shape-fn (tapered (circle 20) :to 0.5)
      (fn [shape t]
        (scale-shape shape (+ 0.8 (* 0.2 (sin (* t PI 4)))))))
    (f 50)))
```

Here the base is `(tapered (circle 20) :to 0.5)`, which is already a shape-fn. At each `t`, the loft first evaluates `tapered` (which returns a smaller circle), then passes the result to the custom function (which makes it pulse). The result is a cone that pulses.

Inside the transformation function you can use any operation on shapes: `scale-shape`, `rotate-shape`, `translate-shape`, `resample-shape`, and the utility functions like `displace-radial`. The function must return a shape with the same number of points as the input (the constraint described in 6.1: the loft connects corresponding vertices between successive shapes, and cannot do so if the count changes from one step to the next).

### The shape-fn? predicate

`shape-fn?` checks whether a value is a shape-fn. Useful in polymorphic code that has to handle both static shapes and shape-fns:

```clojure
(shape-fn? (circle 20))                    ;; false
(shape-fn? (tapered (circle 20) :to 0.5))  ;; true
```
