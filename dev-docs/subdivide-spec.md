# Task: Add `:subdivide` option to `warp`

## Summary

Add a `:subdivide n` option to `warp` that increases tessellation inside the volume before applying deformation. Uses centroid subdivision (1→3) which avoids T-junction problems because no new vertices are added on shared edges.

## Syntax

```clojure
;; Without subdivide (current behavior)
(warp mesh volume (inflate 3))

;; With 1 pass of subdivision (each triangle inside volume → 3)
(warp mesh volume (inflate 3) :subdivide 1)

;; With 2 passes (×9 triangles inside volume)
(warp mesh volume (inflate 3) :subdivide 2)

;; With 3 passes (×27 — usually overkill)
(warp mesh volume (inflate 3) :subdivide 3)
```

## Algorithm: Centroid Subdivision

For each subdivision pass:

1. Compute volume bounds from volume mesh
2. For each triangle in the mesh, check if **any** of its 3 vertices falls inside the volume (use existing `point-in-volume-local?`)
3. Triangles with at least 1 vertex inside → subdivide: add centroid vertex, replace triangle with 3 new triangles
4. Triangles fully outside → keep unchanged

### Single triangle subdivision (1→3)

```
Original:              Subdivided:
    A                     A
   / \                   /|\
  /   \                 / | \
 /     \      →        / C  \      C = centroid = (A+B+D)/3
B-------D             B---+---D

Triangle ABD becomes:  ACB, ACD, BCD
                       (or equivalently: ABC, ACD, BDC — winding must match original)
```

The centroid C is at `[(A+B+D)/3]`. No existing edge is split, so neighboring triangles are unaffected.

### Winding order

The original triangle `[i0 i1 i2]` (CCW) becomes:
- `[i0 i1 ic]`
- `[i1 i2 ic]`  
- `[i2 i0 ic]`

where `ic` is the index of the new centroid vertex. This preserves CCW winding.

### Multiple passes

After pass 1, the new triangles (inside the volume) are candidates for pass 2. Check again which triangles are inside, subdivide those. The volume check uses the same volume bounds throughout (the volume doesn't change).

### Growth rate

- Pass 1: each affected triangle → 3 triangles
- Pass 2: each of those (still inside) → 3 again = ×9
- Pass 3: ×27

In practice 1-2 passes should be enough for most warp operations.

## Implementation

### Modify `src/ridley/geometry/warp.cljs`

Add a `subdivide-mesh` function and modify `warp` to accept `:subdivide` option.

```clojure
(defn- triangle-in-volume?
  "Check if any vertex of a triangle falls inside the volume."
  [vertices face center right up heading vol]
  (let [[i0 i1 i2] face]
    (or (point-in-volume-local? 
          (world-to-local (nth vertices i0) center right up heading) vol)
        (point-in-volume-local? 
          (world-to-local (nth vertices i1) center right up heading) vol)
        (point-in-volume-local? 
          (world-to-local (nth vertices i2) center right up heading) vol))))

(defn- subdivide-once
  "One pass of centroid subdivision on triangles inside the volume.
   Returns mesh with new vertices and faces."
  [mesh vol]
  (let [{:keys [center right up heading]} vol
        vertices (:vertices mesh)
        faces (:faces mesh)
        ;; Use transients for performance
        new-vertices (transient (vec vertices))
        new-faces (transient [])]
    (doseq [face faces]
      (if (triangle-in-volume? vertices face center right up heading vol)
        ;; Subdivide: add centroid, create 3 triangles
        (let [[i0 i1 i2] face
              v0 (nth vertices i0)
              v1 (nth vertices i1)
              v2 (nth vertices i2)
              centroid [(/ (+ (nth v0 0) (nth v1 0) (nth v2 0)) 3.0)
                        (/ (+ (nth v0 1) (nth v1 1) (nth v2 1)) 3.0)
                        (/ (+ (nth v0 2) (nth v1 2) (nth v2 2)) 3.0)]
              ic (count (persistent! new-vertices))]
          ;; PROBLEM: transient count — need different approach
          ;; See implementation note below
          )
        ;; Keep original triangle
        (conj! new-faces face)))
    ;; Return updated mesh
    (assoc mesh
      :vertices (persistent! new-vertices)
      :faces (persistent! new-faces))))
```

**Implementation note**: Using transients with `count` on partially built vectors is tricky. A simpler approach: build new-vertices as a regular vector (accumulate with atom or loop), track the next vertex index manually.

Simpler approach with loop:

```clojure
(defn- subdivide-once
  "One pass of centroid subdivision on triangles inside the volume."
  [mesh vol]
  (let [{:keys [center right up heading]} vol
        vertices (vec (:vertices mesh))
        faces (:faces mesh)]
    (loop [remaining-faces faces
           out-vertices vertices        ;; grows as we add centroids
           out-faces []]
      (if (empty? remaining-faces)
        (assoc mesh :vertices out-vertices :faces out-faces)
        (let [face (first remaining-faces)
              [i0 i1 i2] face]
          (if (triangle-in-volume? out-vertices face center right up heading vol)
            ;; Subdivide
            (let [v0 (nth out-vertices i0)
                  v1 (nth out-vertices i1)
                  v2 (nth out-vertices i2)
                  cx (/ (+ (nth v0 0) (nth v1 0) (nth v2 0)) 3.0)
                  cy (/ (+ (nth v0 1) (nth v1 1) (nth v2 1)) 3.0)
                  cz (/ (+ (nth v0 2) (nth v1 2) (nth v2 2)) 3.0)
                  ic (count out-vertices)]
              (recur (rest remaining-faces)
                     (conj out-vertices [cx cy cz])
                     (into out-faces [[i0 i1 ic] [i1 i2 ic] [i2 i0 ic]])))
            ;; Keep
            (recur (rest remaining-faces)
                   out-vertices
                   (conj out-faces face))))))))

(defn- subdivide-mesh
  "Apply n passes of centroid subdivision inside the volume."
  [mesh vol n]
  (loop [m mesh, i 0]
    (if (>= i n)
      m
      (recur (subdivide-once m vol) (inc i)))))
```

### Modify `warp` signature

The current `warp` signature is:
```clojure
(defn warp [mesh volume & deform-fns] ...)
```

With `:subdivide` as a keyword option mixed with deform-fns, we need to parse the args:

```clojure
(defn warp [mesh volume & args]
  (let [;; Separate keyword options from deform-fns
        {:keys [subdivide deform-fns]}
        (loop [remaining args, opts {}, fns []]
          (if (empty? remaining)
            (assoc opts :deform-fns fns)
            (if (= :subdivide (first remaining))
              (recur (drop 2 remaining)
                     (assoc opts :subdivide (second remaining))
                     fns)
              (recur (rest remaining)
                     opts
                     (conj fns (first remaining))))))
        vol (compute-volume-bounds volume)
        ;; Subdivide before deforming
        mesh (if subdivide
               (subdivide-mesh mesh vol subdivide)
               mesh)
        ;; ... rest of existing warp logic using deform-fns ...
        ]))
```

### Face groups

After subdivision, `:face-groups` may become invalid (face indices change). Options:
- **Simple**: drop `:face-groups` from subdivided mesh (lose face names like `:top`, `:bottom`)
- **Proper**: update face-groups to track which new triangles came from which original group

For v1, drop face-groups. Add a note that warp with subdivide loses face metadata.

## Testing

```clojure
;; Box with 12 triangles — too few for smooth inflate
(register b (box 40))
(register b (warp b (sphere 25) (inflate 5)))
;; Result: blocky, only 8 vertices to move

;; Same with subdivide — much smoother
(register b2 (box 40))
(register b2 (warp b2 (sphere 25) (inflate 5) :subdivide 3))
;; Result: smooth bump, ~12*27 = 324 triangles in affected zone

;; Sphere already has many vertices — subdivide not needed
(register s (sphere 30 32 16))
(register s (warp s (attach (sphere 15) (f 20)) (inflate 5)))
;; Already smooth

;; Subdivide + tweak
(register b (box 50))
(test :all (warp b (attach (sphere 20) (f 10)) (inflate 3) :subdivide 2))
```

## Scope

- Centroid subdivision (1→3) only inside volume
- Multiple passes via `:subdivide n`
- Drop face-groups after subdivision (v1 simplification)
- No retessellation/cleanup (future feature)
