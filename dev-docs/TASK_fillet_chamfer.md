# Fillet & Chamfer — Task Tracker

## Status Summary

| Feature | Status | Notes |
|---------|--------|-------|
| `fillet-shape` / `chamfer-shape` (2D) | ✅ Done | Corner ops on shapes before extrusion |
| `capped` shape-fn | ✅ Done | Fillet/chamfer on loft cap transitions |
| `find-sharp-edges` | ✅ Done | Edge detection by dihedral angle + filters |
| `chamfer` (3D post-processing) | ✅ Done | Turtle selectors, strip mesh, CSG subtraction |
| `fillet` (3D post-processing) | ⚠️ WIP | Per-edge cutters work, vertex blending missing |

---

## What Works

### 2D: `fillet-shape` / `chamfer-shape`
```clojure
(fillet-shape (rect 40 20) 3)              ; round all corners
(chamfer-shape (rect 40 20) 3 :indices [0 2]) ; chamfer specific corners
```

### Loft caps: `capped`
```clojure
(loft (capped ring-section -7 :end false :fraction 0.4) (f 25))
```
Negative radius expands the profile (reinforcement fillet).
`:preserve-holes true` keeps inner boundaries unchanged.

### 3D edges: `chamfer`
```clojure
(-> mesh (chamfer :top 1.5))                       ; top edges
(-> mesh (chamfer :top 1.5 :min-radius ring-r))   ; exclude holes
(-> mesh (chamfer :all 2))                         ; all sharp edges
```
Turtle-oriented selectors: `:top` `:bottom` `:up` `:down` `:left` `:right` `:all`.
Strip mesh for continuous cutting solid. Single `mesh-difference`.

### 3D edges: `fillet`
```clojure
(-> mesh (fillet :top 3 :segments 8))              ; top edges with radius 3
```
Per-edge concave cutters. Each cutter has a cross-section with a quarter-circle
arc from the fillet center. Sequential `mesh-difference` per edge.

---

## Fillet 3D: Implementation History

### Approach 1: Arc in the cutting solid (FAILED)
Modified the chamfer strip's flat f1-f2 face into an arc.
Failed due to scale mismatch: cutter extends 1.5d outside mesh, so arc changes
in cutter space produce negligible effects on the mesh surface.

### Approach 2: Cylinder along edge + chamfer strip (FAILED)
Generate quarter-cylinder tubes along edges, use `(mesh - strip) ∪ (mesh ∩ tube)`.
Failed because strip (outside mesh) and tube (inside mesh) don't overlap enough —
the strip's margin pushes f1/f2 beyond the tube's tangent points.

### Approach 3: Continuous strip with concave arc (PARTIALLY WORKED)
Integrate the arc directly into the strip cross-section: `[corner, f1, A, arc..., B, f2]`.
Works for single edges but at loop corners (where two edges meet), the cross-section
transitions between different normal orientations, creating twisted/distorted geometry
that only cuts properly at one vertex of each edge.

### Approach 4: Per-edge concave cutters (CURRENT — WORKS)
Generate individual closed prisms per edge, each with the concave arc cross-section.
Sequential `mesh-difference` per cutter. Avoids corner transition issues entirely.
Edge margins (0.3d) ensure overlap between adjacent cutters.

**Key functions:**
- `compute-strip-offsets` — cross-section with arc for segments > 1
- `make-fillet-cutter` — closed prism per edge with end caps
- `build-fillet-cutters` — generates vector of per-edge cutters
- `fillet-impl` — reduces `mesh-difference` over all cutters

---

## Known Limitations

### Vertex blending (not implemented)
At vertices where 3+ faces meet (e.g., box corners), each edge's fillet ends
abruptly. A proper fillet should have a spherical patch (⅛ sphere for 90° corners)
blending the adjacent edge fillets. This is "vertex blending" in CAD terminology.

### Performance
Sequential `mesh-difference` per edge is slower than a single strip operation.
For meshes with many edges, this could be slow. A possible optimization:
union all cutters first, then single `mesh-difference`.

---

## Dev Infrastructure

- Fixed nREPL port: 7888 (in `shadow-cljs.edn`)
- `src/dev/repl.clj`: controlled build cycles, no autobuild
- `touch` source files to trigger watch recompile
- Browser refresh needed after `defonce` SCI context changes
