# Ridley — Shape Functions (shape-fn)

## Overview

Shape functions extend Ridley's shape system by allowing shapes to vary along the extrusion path. Instead of extruding a static 2D profile, a shape-fn produces a different shape at each point along the path based on a progress parameter `t` (0 → 1).

This unifies `loft`'s transform function and displacement into a single concept: **a shape that knows how to transform itself**.

## Motivation

Currently `loft` accepts a shape and a separate transform function:

```clojure
(loft (circle 20) #(scale %1 (- 1 %2)) (f 30))
```

This works, but the transform function is always external to the shape. A shape-fn moves the transformation logic into the shape itself, enabling composition, reusable presets, and a cleaner API.

`extrude` remains unchanged — it stamps the same shape at every ring, with no `t` parameter, and is faster. When you need a static cross-section, use `extrude`. When the cross-section varies, use `loft` with a shape-fn.

## Core Concept

A shape-fn is a function that takes `t` (progress along path, 0 to 1) and returns a shape. It carries metadata about its base shape and point count.

```clojure
;; Static shape — use extrude (fast, no per-ring evaluation)
(extrude (circle 20) (f 30))

;; Shape-fn — use loft (evaluates shape at each step)
(loft (tapered (circle 20) :to 0) (f 30))
```

### How loft handles shape-fns

Today `loft` takes three arguments: shape, transform function, and movements. With shape-fns, `loft` detects whether it received a plain shape or a shape-fn:

- **Plain shape + transform function** — current behavior, unchanged
- **Shape-fn (no transform function)** — calls `(shape-fn t)` at each step

```clojure
;; Current API (still works):
(loft (circle 20) #(scale %1 (- 1 %2)) (f 30))

;; New API with shape-fn:
(loft (tapered (circle 20) :to 0) (f 30))
```

Both produce the same result. The shape-fn form is more composable and readable.

## API

### Creating shape-fns

```clojure
(shape-fn base-shape transform-fn)
```

- `base-shape` — a shape or another shape-fn (enables composition)
- `transform-fn` — `(fn [shape t] ...)` returning a shape with the same point count

```clojure
;; Custom shape-fn
(def my-profile
  (shape-fn (circle 20)
    (fn [shape t]
      (scale shape (- 1 (* 0.5 t))))))

(loft my-profile (f 30))
```

### Built-in shape-fns

#### `tapered` — scale along path

```clojure
(tapered shape :to ratio)
(tapered shape :from ratio-start :to ratio-end)
```

Scale the shape from 1 (or `:from`) to `:to` as t goes from 0 to 1.

```clojure
;; Cone
(loft (tapered (circle 20) :to 0) (f 30))

;; Taper to half size
(loft (tapered (circle 20) :to 0.5) (f 30))

;; Expand from half to full
(loft (tapered (circle 20) :from 0.5 :to 1) (f 30))
```

#### `twisted` — rotate along path

```clojure
(twisted shape :angle degrees)
```

Rotate the shape progressively. At t=0 rotation is 0, at t=1 rotation is `:angle`.

```clojure
;; 90° twist
(loft (twisted (rect 20 10) :angle 90) (f 40))

;; Full twist
(loft (twisted (star 5 20 8) :angle 360) (f 60))
```

#### `rugged` — radial displacement pattern

```clojure
(rugged shape :amplitude a :frequency f)
```

Displaces each vertex radially by `a * sin(angle * f)`. The pattern is constant along `t` — the shape has the same bumps at every ring.

```clojure
;; Bumpy tube
(loft (rugged (circle 15) :amplitude 2 :frequency 8) (f 30))
```

#### `fluted` — longitudinal grooves

```clojure
(fluted shape :flutes n :depth d)
```

Like `rugged` but uses `cos` for symmetrical flutes (grooves align with shape axes).

```clojure
;; Fluted column
(loft (fluted (circle 20) :flutes 12 :depth 2) (f 80))
```

#### `displaced` — custom per-vertex displacement

```clojure
(displaced shape displacement-fn)
```

The displacement function receives a point `[x y]` and `t`, returns a radial offset.

```clojure
;; Spiral grooves
(loft
  (displaced (resample (circle 15) 64)
    (fn [p t]
      (* 2 (sin (+ (* (angle p) 6) (* t 20))))))
  (f 50))

;; Displacement only on top half
(loft
  (displaced (resample (rect 40 20) 80)
    (fn [p t]
      (if (> (second p) 0)
        (* 1.5 (sin (* (first p) 8)))
        0)))
  (f 30))
```

#### `morphed` — interpolate between two shapes

```clojure
(morphed shape-a shape-b)
```

At t=0 the shape is `shape-a`, at t=1 it is `shape-b`. Both must have the same number of points (use `resample` if needed).

```clojure
;; Star morphing into circle
(loft
  (morphed (resample (star 5 20 8) 32)
           (circle 15 32))
  (f 30))
```

### Composition

Shape-fns compose with `->`. Each transformation wraps the previous one:

```clojure
;; Fluted column that tapers to a point
(loft
  (-> (circle 20)
      (fluted :flutes 12 :depth 2)
      (tapered :to 0))
  (f 50))

;; Twisted rectangle that shrinks
(loft
  (-> (rect 30 10)
      (twisted :angle 180)
      (tapered :to 0.3))
  (f 40))

;; Bumpy surface with spiral grooves that tapers
(loft
  (-> (resample (circle 20) 64)
      (displaced (fn [p t] (* 2 (sin (+ (* (angle p) 8) (* t 12))))))
      (tapered :to 0.5))
  (f 60))
```

Evaluation order: at each `t`, the innermost shape-fn is evaluated first, and each outer layer transforms the result. So `(-> (circle 20) (fluted ...) (tapered ...))` first applies fluting, then scales the fluted shape.

## Implementation

### shape-fn constructor

```clojure
(defn shape-fn [base transform]
  (let [evaluate (if (fn? base)
                   (fn [t] (transform (base t) t))  ; chain: evaluate base first
                   (fn [t] (transform base t)))]     ; leaf: transform static shape
    (with-meta evaluate
      {:type :shape-fn
       :base base
       :point-count (if (fn? base)
                      (:point-count (meta base))
                      (count (:points base)))})))
```

Key properties:
- A shape-fn is a regular Clojure function (`IFn`)
- Metadata carries `:type`, `:base`, and `:point-count` for introspection
- When `base` is itself a shape-fn, evaluation chains automatically

### Built-in implementations

```clojure
(defn tapered [shape-or-fn & {:keys [to from] :or {to 0 from 1}}]
  (shape-fn shape-or-fn
    (fn [s t]
      (scale s (+ from (* t (- to from)))))))

(defn twisted [shape-or-fn & {:keys [angle] :or {angle 360}}]
  (shape-fn shape-or-fn
    (fn [s t]
      (rotate-shape s (* t angle)))))

(defn rugged [shape-or-fn & {:keys [amplitude frequency]
                              :or {amplitude 1 frequency 6}}]
  (shape-fn shape-or-fn
    (fn [s _t]
      (displace-radial s (fn [p]
        (* amplitude (sin (* (angle p) frequency))))))))

(defn fluted [shape-or-fn & {:keys [flutes depth]
                              :or {flutes 6 depth 1}}]
  (shape-fn shape-or-fn
    (fn [s _t]
      (displace-radial s (fn [p]
        (* depth (cos (* (angle p) flutes))))))))

(defn displaced [shape-or-fn displace-fn]
  (shape-fn shape-or-fn
    (fn [s t]
      (displace-radial s (fn [p] (displace-fn p t))))))

(defn morphed [shape-a shape-b]
  (shape-fn shape-a
    (fn [s t]
      (morph s shape-b t))))
```

### Helper: displace-radial

Displaces each point of a shape along the radial direction from the shape centroid:

```clojure
(defn displace-radial [shape offset-fn]
  (let [center (shape-centroid shape)]
    (update shape :points
      (fn [pts]
        (mapv (fn [p]
                (let [dir (vec2-normalize (vec2-sub p center))
                      offset (offset-fn p)]
                  (vec2-add p (vec2-scale dir offset))))
              pts)))))
```

### Helper: angle

Returns the angle (in radians) of a point relative to the shape centroid. Useful in displacement functions for angular patterns:

```clojure
(defn angle [p]
  (Math/atan2 (second p) (first p)))
```

### Changes to loft

The only modification needed is in `loft`'s argument handling:

```clojure
;; Current signature:
(loft shape transform-fn & movements)

;; Extended: detect shape-fn and skip transform-fn
(loft shape-or-shape-fn & args)
```

When the first argument is a shape-fn (detected via metadata), `loft` uses it directly as the shape source. When it's a plain shape, the second argument must be a transform function (current behavior).

```clojure
(defn loft [shape-or-fn & args]
  (if (shape-fn? shape-or-fn)
    ;; shape-fn mode: (loft shape-fn & movements)
    (let [movements args
          transform (fn [_shape t] (shape-or-fn t))]
      (loft-impl (shape-or-fn 0) transform movements))
    ;; legacy mode: (loft shape transform-fn & movements)
    (let [[transform-fn & movements] args]
      (loft-impl shape-or-fn transform-fn movements))))

(defn shape-fn? [x]
  (and (fn? x) (= :shape-fn (:type (meta x)))))
```

The internal `loft-impl` remains unchanged — it receives a base shape, a transform function, and movements, exactly as today.

### Resolution considerations

Displacement and rugged patterns require sufficient points in the base shape:

- **Circumferential detail** — use `(resample shape n)` or `(circle r n)` to increase point count
- **Longitudinal detail** — use `loft-n` step count or `(resolution :n steps)` to increase ring count

Rule of thumb: points per ring ≥ 2 × displacement frequency (Nyquist).

```clojure
;; Low frequency: default resolution is fine
(loft (rugged (circle 10) :frequency 4 :amplitude 2) (f 20))

;; High frequency: need more points
(loft (rugged (circle 10 64) :frequency 16 :amplitude 1) (f 20))
```

## extrude vs loft: when to use which

| | `extrude` | `loft` |
|---|---|---|
| Cross-section | Static | Varies with `t` |
| Performance | Faster (no per-ring eval) | Slower (evaluates shape-fn per step) |
| Use case | Tubes, beams, constant profiles | Cones, twists, displacement, ornaments |
| Shape-fn support | No | Yes |
| Step count | Determined by path | Configurable (`loft-n`) |

**Rule of thumb**: if the shape doesn't change along the path, use `extrude`. Otherwise, use `loft`.

## Examples

### Fluted Greek Column

```clojure
(loft
  (-> (circle 15 48)
      (fluted :flutes 20 :depth 1.5)
      (tapered :from 1 :to 0.85))
  (f 80))
```

### Twisted Candy

```clojure
(loft
  (-> (star 5 12 6)
      (twisted :angle 720))
  (f 40))
```

### Seashell

```clojure
(loft-n 128
  (-> (resample (circle 20) 64)
      (displaced (fn [p t]
        (* (- 1 t) 3 (sin (* (angle p) 5)))))
      (tapered :to 0.1))
  (arc-h 40 (* 3 360)))
```

### Ornamental Band (for cups/vases)

```clojure
;; Path: circle for the cup perimeter
(def perimeter (path (dotimes [_ 72] (f 3) (th 5))))

;; Band with wave pattern on one side
(register band
  (loft
    (displaced (resample (rect 10 3) 60)
      (fn [p t]
        (if (> (second p) 0)
          (* 1.5 (sin (* t 40)))
          0)))
    perimeter))
```

### Spiral Grooves

```clojure
(loft-n 64
  (displaced (circle 15 64)
    (fn [p t]
      (* 2 (sin (+ (* (angle p) 6) (* t 30))))))
  (f 60))
```

### Vase with Fluted Surface

```clojure
;; Vase profile as shape-fn with fluting
(loft-n 48
  (-> (circle 20 48)
      (fluted :flutes 16 :depth 1)
      (shape-fn (fn [s t]
        (scale s (+ 0.6 (* 0.4 (sin (* t Math/PI))))))))
  (f 60))
```

## Future Extensions

**Noise-based displacement**: integrate a Perlin/simplex noise function for organic surfaces.

```clojure
;; Hypothetical
(loft (noisy (circle 20) :scale 0.1 :amplitude 3) (f 40))
```

**Heightmap displacement**: load a grayscale image and use it as displacement source, mapped via angle and t.

```clojure
;; Hypothetical
(loft (heightmap (circle 20 128) "pattern.png" :amplitude 2) (f 30))
```

**Parametric shape-fns**: shape-fns that accept additional parameters beyond t, for interactive/animated use.

```clojure
;; Hypothetical
(def adjustable (tapered (circle 20) :to (param :taper 0 1 0.5)))
```
