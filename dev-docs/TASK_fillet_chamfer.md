# Fillet & Chamfer — Task Tracker

## Status Summary

| Feature | Status | Notes |
|---------|--------|-------|
| `fillet-shape` / `chamfer-shape` (2D) | ✅ Done | Corner ops on shapes before extrusion |
| `capped` shape-fn | ✅ Done | Fillet/chamfer on loft cap transitions |
| `find-sharp-edges` | ✅ Done | Edge detection by dihedral angle + filters |
| `chamfer` (3D post-processing) | ✅ Done | Turtle selectors, strip mesh, CSG subtraction |
| `fillet` (3D post-processing) | ❌ WIP | Arc geometry needs rethinking |

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

---

## Fillet 3D: What Went Wrong

### Approach tried: arc in the cutting solid
Modified the chamfer strip's flat f1-f2 face into an arc.
The arc should curve toward the corner, making the cutting solid smaller
at the midpoint, leaving a convex fillet after subtraction.

### Problems encountered:
1. **Scale mismatch**: The cutter extends well beyond the mesh (corner at 1.5d outside).
   A small arc change in cutter space produces a tiny effect on the mesh surface.
2. **Too much bulge → non-manifold**: Full geometric sagitta makes the strip degenerate
   (faces near corner collapse to zero area). Manifold rejects it.
3. **Too little bulge → invisible**: Reduced bulge produces a barely visible curve,
   indistinguishable from chamfer.
4. **Scale correction attempt**: Scaling by dist_corner/dist_edge ratio overcorrects
   (bulge 3.08mm on a 3.71mm triangle → nearly collapsed → Manifold rejects).

### Root cause insight (from user)
The curvature must be calculated on the **intersection** of the cutter with the mesh,
not on the cutter itself. Since the cutter is much larger than the mesh, the mapping
is non-linear and the simple linear scaling doesn't work.

### Proposed alternative approaches:
1. **Cylinder along edge**: Generate an actual cylinder (tube with quarter-circle profile)
   positioned at the fillet center (r from both faces, inside the mesh).
   Subtract: `mesh - (chamfer_cutter - cylinder)` or `mesh ∩ (mesh + cylinder)`.
2. **Profile-based for extrusions**: Since we know the 2D profile, generate the fillet
   directly from `shape-offset` transitions (like `capped` but as post-processing).
3. **Manifold smooth()**: Use Manifold's built-in subdivision surface with per-edge
   sharpness control. Requires mapping selected edges to Manifold halfedge indices.

---

## Dev Infrastructure

- Fixed nREPL port: 7888 (in `shadow-cljs.edn`)
- `src/dev/repl.clj`: controlled build cycles, no autobuild
- `touch` source files to trigger watch recompile
- Browser refresh needed after `defonce` SCI context changes
