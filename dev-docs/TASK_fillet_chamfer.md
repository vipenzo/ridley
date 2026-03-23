# Fillet & Chamfer

## Goal

Add fillet/chamfer capabilities to Ridley, incrementally:

1. **Phase 1** — 2D shape fillet/chamfer (corners of profiles before extrusion) ✅
2. **Phase 2** — Cap fillet via loft transition (round edges where profile meets top/bottom cap)
3. **Phase 3** — 3D edge fillet/chamfer (arbitrary mesh edges, via picking or proxy objects)

---

## Phase 1: 2D Shape Fillet/Chamfer ✅

### Functions

```clojure
(chamfer-shape shape distance)                     ; Cut all corners flat
(chamfer-shape shape distance :indices [0 1])      ; Only specific vertices

(fillet-shape shape radius)                        ; Round all corners with circular arcs
(fillet-shape shape radius :segments 16)           ; Smoother arcs (default 8)
(fillet-shape shape radius :indices [0 2])         ; Only specific vertices
```

### Algorithm

**Chamfer:** For each vertex, replace it with two points at distance `d` along each adjacent edge. A rect corner becomes a flat cut (4 vertices → 8 vertices).

**Fillet:** For each vertex:
1. Compute two tangent points at distance `d` along adjacent edges (same as chamfer)
2. Find the inscribed circle center along the angle bisector at distance `r / sin(half_angle)` from the corner, where `r = d / tan(half_angle)`
3. Sweep a true circular arc from tangent point A to tangent point B using `n` segments

### Edge cases
- If `d` exceeds half the length of an adjacent edge, that corner is left unchanged (prevents overlapping cuts)
- Nearly straight angles (< 1e-6 radians from π) are left unchanged
- Holes are processed with the same algorithm

### Implementation

- `chamfer-shape` / `fillet-shape` in `src/ridley/turtle/shape.cljs`
- SCI bindings in `src/ridley/editor/bindings.cljs`
- Manual sections (EN + IT) in `src/ridley/manual/content.cljs`
- RAG chunk in `src/ridley/ai/rag_chunks.cljs`

### Tested

- `chamfer-shape` on rect: 4 → 8 points, correct coordinates ✅
- `fillet-shape` on rect: 4 → 36 points (8 segments default), true circular arc ✅
- Selective indices: `:indices [0 1]` processes only specified corners ✅
- Custom segments: `:segments 16` → 68 points ✅
- Hexagon and other polygons ✅
- SCI context: both functions accessible from user code ✅

---

## Phase 2: Cap Fillet via Loft Transition ✅

### Concept

When extruding a shape, the edges where the profile meets the top/bottom cap are sharp 90° angles. A "cap fillet" adds a smooth curved transition at the start and/or end of the extrusion.

```
          ┌─────────────┐  ← full profile (main body)
         ╱               ╲
        ╱                 ╲  ← arc transition (fillet easing)
       │                   │
       └───────────────────┘  ← cap (smaller profile, offset by -r)
```

### Implemented as: `capped` shape-fn modifier

Rather than modifying the extrusion pipeline, `capped` is implemented as a **shape-fn modifier** in `shape_fn.cljs`. This approach:
- Works with the existing loft infrastructure (no changes to `extrusion.cljs`)
- Composes with all other shape-fns via `->` threading
- Uses `shape-offset` (Clipper2) for geometrically correct inward offset

### API

```clojure
(loft (capped (rect 40 20) 3) (f 50))                  ; fillet both caps
(loft (capped (rect 40 20) 3 :mode :chamfer) (f 50))   ; chamfer both caps
(loft (capped (rect 40 20) 3 :end false) (f 50))       ; fillet start only
(loft (capped (rect 40 20) 3 :start false) (f 50))     ; fillet end only
(loft (capped (rect 40 20) 3 :fraction 0.15) (f 50))   ; wider transition zone
```

Composes with other shape-fns:
```clojure
(-> (circle 20) (tapered :to 0.3) (capped 2))          ; tapered + rounded caps
(-> (rect 40 20) (fillet-shape 5) (capped 3))           ; 2D rounded corners + 3D rounded caps
(-> (circle 20) (fluted :flutes 8 :depth 2) (capped 3)) ; fluted + rounded caps
```

### Algorithm

At parameter `t` in [0, 1] along the loft path:
- **Start transition** (t ∈ [0, fraction]): `u = ease(t / fraction)`
- **End transition** (t ∈ [1-fraction, 1]): `u = ease((1-t) / fraction)`
- **Main body** (t ∈ [fraction, 1-fraction]): `u = 1.0` (full shape)

The easing function:
- `:fillet` mode: `sin(u × π/2)` — quarter-circle arc (smooth tangent at both ends)
- `:chamfer` mode: `u` — linear (creates a flat bevel)

The inset amount at each step: `shape-offset(shape, -radius × (1 - u))`

### Options

| Option | Default | Description |
|--------|---------|-------------|
| `:mode` | `:fillet` | `:fillet` (quarter-circle) or `:chamfer` (linear) |
| `:start` | `true` | Apply transition at t=0 |
| `:end` | `true` | Apply transition at t=1 |
| `:fraction` | `0.08` | Fraction of path used for each transition zone |

### Implementation

- `capped` function in `src/ridley/turtle/shape_fn.cljs`
- SCI binding in `src/ridley/editor/bindings.cljs`

### Tested

- Basic rect: cap width 34 (40 - 2×3), mid width 40 ✅
- Fillet easing: t=0.04 gives width 38.24 (smooth sin curve) ✅
- Chamfer easing: t=0.04 gives width 37 (linear) ✅
- Start-only / end-only ✅
- Composition with `tapered` ✅
- Composition with `fillet-shape` ✅
- SCI context: `(loft (capped (rect 40 20) 3) (f 50))` → 68 vertices, 132 faces ✅
- Full composition: `(loft (capped (fillet-shape (rect 40 20) 5) 3) (f 50))` → 612 vertices ✅

### Limitations

- Works with `loft` and `bloft`, not with raw `extrude` (which doesn't evaluate shape-fns). Use `loft` with identity shape-fn for the same effect.
- The `fraction` parameter is relative to path length — for very short or very long paths, you may need to adjust it.
- `shape-offset` is called per-ring in the transition zone, which could be slow for complex shapes with many holes. For typical shapes this is negligible.

---

## Blocking Issues: Loft + shape-fn producing varying shapes

`capped` works correctly as a shape-fn (verified via REPL), but the loft pipeline has issues when the shape-fn produces shapes of different sizes:

### Issue 1: Segmented mesh output
The loft creates 2-3 separate disconnected segments instead of one continuous sweep mesh. Visible as gaps/seams in the rendered output. Happens with both `loft` and `loft-n`.

### Issue 2: Non-manifold cap triangulation with holes
When lofting a shape with holes (e.g., annulus from `shape-difference`), the cap triangulation produces non-manifold geometry. Manifold WASM rejects the mesh, preventing `mesh-union`/`mesh-difference`.

### Issue 3: shape-offset changes point topology
`shape-offset` (Clipper2) can change the number of points and their distribution. Even with `resample` to restore count, the point alignment between rings may shift, causing twisted faces.

### Workaround (current)
For the pipe-clamp example, we use `tapered` (which only scales, preserving topology) + separate mesh parts + `mesh-union`. This works but is more verbose than the intended `capped` one-liner.

### Proposed fix direction
The root cause is likely in `loft.cljs` — the path analysis splits a simple `(f 25)` path into segments when it detects shape size changes between rings. The fix should ensure that a single straight segment stays as one continuous sweep regardless of shape-fn output variation.

---

## Phase 3: 3D Edge Chamfer via CSG ✅

### Implemented: `chamfer-edges` (low-level) + `chamfer` (high-level, in progress)

### Low-level: `chamfer-edges`

Per-edge prism CSG approach:
1. `find-sharp-edges` detects edges by dihedral angle threshold + `:where` predicate
2. `chamfer-prisms` generates triangular prisms along each selected edge
3. Sequential `mesh-difference` subtracts each prism from the mesh

```clojure
(chamfer-edges mesh 1.5 :angle 80 :where #(> (first %) 4))
```

**Prism geometry**: all 3 cross-section vertices are OUTSIDE the mesh. Their triangle's
intersection with the mesh volume IS the corner wedge to remove:
- corner: along bisector of the two face normals (outside the mesh corner)
- face1-pt: d along face-1 surface + margin past it (along n1)
- face2-pt: d along face-2 surface + margin past it (along n2)

**Winding**: signed volume check ensures correct face orientation for all edge directions.

### Known limitations
- Curved edges (circles, fillets) show slight faceting — each prism is flat
- Edge margin overlap helps but doesn't fully eliminate seams on tight curves
- Increasing shape resolution (more segments) improves curve quality

### High-level: `chamfer` with turtle-oriented selectors (in progress)

Turtle-oriented face selectors derived from `:creation-pose`:

```clojure
(chamfer :top 1.5)        ; edges at end of extrusion (heading direction)
(chamfer :bottom 1.5)     ; edges at start of extrusion (-heading)
(chamfer :up 1.5)         ; edges on up-facing side
(chamfer :down 1.5)       ; edges on down-facing side
(chamfer :left 1.5)       ; edges on left side
(chamfer :right 1.5)      ; edges on right side
(chamfer :all 1.5)        ; all sharp edges

;; Additional filters:
(chamfer :top 1.5 :min-radius 15)   ; exclude holes
(chamfer :top 1.5 :where pred)      ; custom filter (fallback)
```

Direction mapping from `:creation-pose`:
- `:top` / `:bottom` → ±heading
- `:up` / `:down` → ±up
- `:left` / `:right` → ±cross(heading, up)

An edge matches a direction selector if one of its adjacent face normals
aligns with that direction (dot product > threshold).

### Future improvements
- Strip mesh approach for smoother curved chamfers (single continuous cutting solid per edge loop)
- Fillet variant (cylindrical cut instead of flat chamfer)
- Viewport edge picking for interactive selection
