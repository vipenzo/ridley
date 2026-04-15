(ns ridley.geometry.diagnose
  "Mesh topology diagnosis — answers \"how non-manifold is this mesh?\" for
   any Ridley mesh, without modifying it. Used to validate mesh-repair
   strategies before committing to a fix.

   Counts unique edges and their incidence (1 = boundary/open,
   2 = manifold, >2 = non-manifold), degenerate faces, boundary loops, and
   connected components. Read-only — never mutates input.")

(defn- edge-key
  "Canonical undirected edge key: smaller index first."
  [a b]
  (if (< a b) [a b] [b a]))

(defn- triangle-area
  "Magnitude of cross product / 2 — zero for degenerate (collinear) tris."
  [verts [a b c]]
  (let [[ax ay az] (nth verts a)
        [bx by bz] (nth verts b)
        [cx cy cz] (nth verts c)
        ux (- bx ax) uy (- by ay) uz (- bz az)
        vx (- cx ax) vy (- cy ay) vz (- cz az)
        nx (- (* uy vz) (* uz vy))
        ny (- (* uz vx) (* ux vz))
        nz (- (* ux vy) (* uy vx))]
    (* 0.5 (Math/sqrt (+ (* nx nx) (* ny ny) (* nz nz))))))

(defn- build-edge-incidence
  "Walk faces and count undirected edge incidence.
   Returns map of edge-key -> count."
  [faces]
  (persistent!
   (reduce
    (fn [m face]
      (let [a (nth face 0)
            b (nth face 1)
            c (nth face 2)
            e1 (edge-key a b)
            e2 (edge-key b c)
            e3 (edge-key c a)]
        (-> m
            (assoc! e1 (inc (get m e1 0)))
            (assoc! e2 (inc (get m e2 0)))
            (assoc! e3 (inc (get m e3 0))))))
    (transient {})
    faces)))

(defn diagnose
  "Compute topological invariants of a mesh. Returns a map; never mutates.
   O(n_faces) — only counts edges, no boundary walking or component analysis.

   Keys:
     :n-verts               vertex count
     :n-faces               triangle count
     :n-edges               unique undirected edge count
     :edge-incidence-distribution map of (incidence count) -> (number of edges)
     :open-edges            edges with exactly 1 incident face (boundary)
     :non-manifold-edges    edges with 3+ incident faces (T-junctions)
     :degenerate-faces      triangles with area < 1e-10
     :euler-characteristic  V - E + F (closed orientable manifold = 2)
     :is-watertight?        true iff open-edges == 0 AND non-manifold-edges == 0"
  [mesh]
  (let [verts (:vertices mesh)
        faces (:faces mesh)
        n-verts (count verts)
        n-faces (count faces)
        edge-counts (build-edge-incidence faces)
        n-edges (count edge-counts)
        edge-distribution (frequencies (vals edge-counts))
        open-edges (get edge-distribution 1 0)
        non-manifold-edges (->> edge-distribution
                                (filter (fn [[k _]] (> k 2)))
                                (map second)
                                (reduce + 0))
        degenerate (count (filter #(< (triangle-area verts %) 1e-10) faces))
        euler (+ n-verts (- n-edges) n-faces)]
    {:n-verts n-verts
     :n-faces n-faces
     :n-edges n-edges
     :edge-incidence-distribution edge-distribution
     :open-edges open-edges
     :non-manifold-edges non-manifold-edges
     :degenerate-faces degenerate
     :euler-characteristic euler
     :is-watertight? (and (zero? open-edges) (zero? non-manifold-edges))}))
