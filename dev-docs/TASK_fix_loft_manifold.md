# Task: Fix loft producing non-manifold meshes

## Problem

`loft` and `loft-n` produce meshes that fail Manifold validation, while `extrude` with the same shape and path produces valid manifold meshes.

```clojure
(mesh-status (extrude (circle 5) (f 20)))
;; => {:manifold? true, :status :ok, :volume 1530.7, :surface-area 777.4}

(mesh-status (loft (circle 5) #(identity %1) (f 20)))
;; => {:manifold? false, :status :failed-to-create, :volume 0, :surface-area 0}
```

This is critical because it prevents using `mesh-difference` and other boolean ops on lofted meshes, forcing users to use `bloft` which is much slower.

## Impact

- Procedural animations using loft are slow because they must use bloft + mesh-difference
- Any model combining loft with boolean operations is broken
- The fiore/cone example stutters due to bloft overhead

## Diagnosis Strategy

### Step 1: Dump and compare mesh data

Add temporary debug logging to compare what `extrude` vs `loft` produce for the identical case `(circle 5) (f 20)`:

```clojure
(let [e (extrude (circle 5) (f 20))
      l (loft (circle 5) #(identity %1) (f 20))]
  (println "=== EXTRUDE ===")
  (println "vertices:" (count (:vertices e)) "faces:" (count (:faces e)))
  (println "first 3 verts:" (take 3 (:vertices e)))
  (println "last 3 verts:" (take-last 3 (:vertices e)))
  (println "first 5 faces:" (take 5 (:faces e)))
  (println "last 5 faces:" (take-last 5 (:faces e)))
  (println "=== LOFT ===")
  (println "vertices:" (count (:vertices l)) "faces:" (count (:faces l)))
  (println "first 3 verts:" (take 3 (:vertices l)))
  (println "last 3 verts:" (take-last 3 (:vertices l)))
  (println "first 5 faces:" (take 5 (:faces l)))
  (println "last 5 faces:" (take-last 5 (:faces l))))
```

### Step 2: Check for common manifold failures

Run these checks on the loft output:

1. **Duplicate vertices**: Are any vertices identical or near-identical (within epsilon)?
2. **Degenerate faces**: Do any faces have area ≈ 0? (collinear vertices)
3. **Face index range**: Are all face indices within `[0, vertex-count)`?
4. **Consistent winding**: Pick a few adjacent face pairs and verify CCW from outside
5. **Edge sharing**: Every edge should be shared by exactly 2 faces (watertight). Find any edges with 1 or 3+ faces.
6. **Ring count**: How many rings does loft generate vs extrude? (loft interpolates extra rings which might be identical to start/end)

### Step 3: Root cause analysis

The loft code path for the simple case (no corners) in `finalize-loft`:

```
1. stamp-loft → saves base shape, transform fn, initial orientation
2. Each (f dist) → records orientation waypoint with accumulated distance
3. finalize-loft → generates rings by interpolating orientations at N steps
4. build-sweep-mesh → builds mesh from rings (same function extrude uses)
```

The extrude code path:
```
1. stamp → places first ring
2. Each (f dist) → places new ring at new position
3. finalize-sweep → calls build-sweep-mesh with collected rings
```

Both end up calling `build-sweep-mesh`. So the difference must be in the RINGS themselves, not in the mesh building. Specifically:

- **Ring count**: loft generates `steps + 1` rings (default 16+1 = 17). Extrude generates 2 rings for a simple `(f 20)`. More rings = more chance of degenerate/duplicate geometry?
- **Ring positions**: loft interpolates positions from orientations. For a simple forward movement, all intermediate positions should be on the same line — but are they exactly on the same line, or do floating point errors create micro-offsets?
- **First/last ring**: Does loft duplicate the first or last ring? If the first ring at t=0 and the second ring at t=1/steps are at the same position, that creates a degenerate face.

### Step 4: Likely fix

Based on the code analysis, the most likely issue is one of:

**A) Degenerate first/last rings**: If `stamp-loft` records an orientation at position P, and then `finalize-loft` also generates a ring at t=0 which is at position P, those rings overlap. Check if `loft-orientations` contains the initial position AND `finalize-loft` also generates a ring at t=0.

**B) Ring vertex count mismatch**: If `transform-fn` returns a shape with different point count than the base shape, consecutive rings have different vertex counts, which breaks `build-sweep-mesh`.

**C) Zero-distance rings**: With `steps=16` and `dist=20`, each ring is 1.25 units apart — fine. But if the first orientation's `dist` is 0 and the second is also 0 (initial stamp), two rings at the same position create degenerate side faces.

## Files to examine

- `src/ridley/turtle/loft.cljs` — `stamp-loft`, `finalize-loft`, `loft-from-path`
- `src/ridley/turtle/extrusion.cljs` — `build-sweep-mesh`, `stamp-shape`, `extrude-from-path`
- `src/ridley/editor/operations.cljs` — `implicit-extrude-path`, `pure-loft-path` (the wrappers)

## Fix requirements

- `(loft (circle 5) #(identity %1) (f 20))` must produce a manifold mesh
- `(loft-n 24 (circle 5) #(scale %1 0.5) (f 20))` must produce a manifold mesh
- `(loft (circle 5) #(scale %1 (- 1 %2)) (f 20))` — taper to zero — may legitimately not be manifold (open at tip), but should not crash Manifold with `:failed-to-create`
- Existing extrude behavior must not change
- Performance should remain similar (no manifold/union calls needed)

## Testing

After fix:
```clojure
;; All should return :manifold? true
(mesh-status (loft (circle 5) #(identity %1) (f 20)))
(mesh-status (loft-n 8 (circle 5) #(scale %1 0.5) (f 20)))
(mesh-status (loft (rect 10 5) #(rotate-shape %1 (* %2 90)) (f 30)))

;; Boolean ops should work
(mesh-difference
  (loft-n 24 (circle 5) #(scale %1 (+ 0.1 (* %2 2))) (f 20))
  (loft-n 24 (circle 4) #(scale %1 (+ 0.1 (* %2 2))) (f 21)))

;; The flower animation should be smooth
(register bud (loft (circle 5) #(scale %1 0.1) (f 20)))
(anim-proc! :bloom 3.0 :bud :out :loop-bounce
  (fn [t]
    (let [m (loft-n 24 (circle 5)
              #(scale %1 (+ 0.1 (* %2 t 2)))
              (f (* 20 (+ 0.5 (* 0.5 t)))))
          c (loft-n 24 (circle 4)
              #(scale %1 (+ 0.1 (* %2 t 2)))
              (f (* 21 (+ 0.5 (* 0.5 t)))))]
      (mesh-difference m c))))
(play!)
```
