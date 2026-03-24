# Fillet & Chamfer — 3D

3D fillet and chamfer are **post-processing operations** on meshes. They detect sharp edges by dihedral angle and modify them using CSG (Constructive Solid Geometry).

## `chamfer` — Flat Edge Cuts

Cut sharp edges with a flat bevel. Uses a continuous strip mesh subtracted in a single CSG operation.

```clojure
(-> mesh (chamfer :top 1.5))                    ; Edges of the "top" face (heading direction)
(-> mesh (chamfer :top 1.5 :min-radius ring-r)) ; Exclude edges near holes
(-> mesh (chamfer :all 2))                      ; All sharp edges
(-> mesh (chamfer :all 2 :angle 60))            ; Only edges with dihedral > 60°
(-> mesh (chamfer :all 2 :where #(> (first %) 0))) ; Custom vertex predicate
```

### Direction Selectors

Selectors are relative to the turtle pose at creation time:

| Selector | Meaning |
|----------|---------|
| `:top` | Edges adjacent to the face along heading (+X by default) |
| `:bottom` | Edges adjacent to the face opposite heading |
| `:up` | Edges adjacent to the face along up (+Z by default) |
| `:down` | Edges adjacent to the face opposite up |
| `:left` / `:right` | Edges adjacent to lateral faces |
| `:all` | All sharp edges above the angle threshold |

### Options

| Option | Default | Description |
|--------|---------|-------------|
| `:angle` | 80 | Minimum dihedral angle (degrees) for edge detection |
| `:min-radius` | nil | Exclude edges closer than this to the extrusion axis |
| `:where` | nil | Additional predicate `(fn [position] -> bool)` |

## `fillet` — Rounded Edges

Round sharp edges with a circular profile. Uses per-edge concave cutters, each subtracted individually via CSG.

```clojure
(-> mesh (fillet :top 3))                       ; Fillet top edges, radius 3
(-> mesh (fillet :top 3 :segments 16))          ; Smoother curve (default 8)
(-> mesh (fillet :all 2 :segments 8))           ; All sharp edges
(-> mesh (fillet :top 3 :blend-vertices true))  ; Spherical blend at corners
```

### Options

Same as `chamfer`, plus:

| Option | Default | Description |
|--------|---------|-------------|
| `:segments` | 8 | Arc resolution (number of facets on the fillet curve) |
| `:blend-vertices` | false | Spherical blend at corners where 3+ faces meet |

### Vertex Blending

When `:blend-vertices true`, spherical patches are added at mesh vertices where 3 or more filleted edges converge. This produces smooth corners on rounded profiles (e.g., extrusions of `fillet-shape` rects) but can create visible seams on sharp-angled geometry like boxes.

```clojure
;; Good: rounded profile + vertex blend
(-> (rect 40 20) (fillet-shape 5) (extrude (f 30))
    (fillet :top 2 :segments 8 :blend-vertices true))

;; Better without blend: sharp box corners
(-> (box 20 20 20) (fillet :top 3 :segments 8))
```

## How It Works

### Chamfer

1. **Edge detection**: `find-sharp-edges` scans all mesh edges for dihedral angles above the threshold
2. **Edge loops**: Connected edges are grouped into loops (closed or open)
3. **Strip mesh**: A continuous triangular-cross-section strip is built along each loop
4. **CSG subtraction**: Single `mesh-difference` removes the strip from the mesh

### Fillet

1. **Edge detection**: Same as chamfer
2. **Per-edge cutters**: Each edge gets a closed prism with a concave quarter-circle cross-section. The cross-section is `[corner, f1, A, arc₁...arc_{N-1}, B, f2]` where the arc is computed using slerp from the fillet center.
3. **Sequential CSG**: Each cutter is subtracted individually. This avoids geometric issues at edge-loop corners where different normal orientations create twisted cross-sections.
4. **Vertex blend** (optional): Box-minus-sphere cutters at vertices where 3+ faces meet.

### Performance

- **Chamfer**: Single CSG operation (fast)
- **Fillet**: N sequential CSG operations where N = number of selected edges (slower for many edges)

## Examples

```clojure
;; Basic box with chamfered top edges
(register c (-> (box 30 30 30) (chamfer :top 2)))

;; Cylinder with filleted base
(register cyl-f (-> (cyl 15 40) (fillet :down 2 :segments 8)))

;; Plate with rounded top edges (from supporto.clj example)
(def base (-> base-shape
              (extrude (f base-h))
              (fillet :top 1.5 :min-radius ring-r :segments 8)))

;; Rounded box: 2D corners + 3D edges + vertex blend
(register rounded
  (-> (rect 40 20)
      (fillet-shape 5)
      (extrude (f 30))
      (fillet :all 2 :segments 8 :blend-vertices true)))
```
