(ns ridley.geometry.outline
  "Does this outline cross itself, and where?

   A closed 2D outline that crosses itself is not a mistake the geometry catches
   politely. The cap triangulation simply drops the triangles it cannot make, and
   what comes out is a solid with a hole in each cap — reported three steps later
   as `open-edges 6`, on a mesh, with nothing pointing back at the curve that
   caused it. Vincenzo, 2026-08-17, after fixing one by feel: «ho messo a posto
   LowPath, ma senza capire dov'era il problema: ho semplicemente reso tutto un
   po' più smooth».

   The check belongs where the outline is DRAWN, not where it is extruded, and
   that is the whole point of putting it here: a crossing is trivial to see and
   trivial to fix while you are holding the node, and archaeology afterwards.

   Pure and O(n²) on the tessellated polyline — a few hundred points is tens of
   thousands of segment pairs, which is nothing next to the tessellation that
   produced them."
  (:require [ridley.photogrammetry.linalg :as la]))

(defn- cross2 [[ax ay] [bx by]] (- (* ax by) (* ay bx)))

(defn- seg-intersection
  "Where segments p→p2 and q→q2 properly cross, or nil.

   PROPER crossings only: shared endpoints do not count, and neither does a touch
   at an endpoint. Adjacent segments of a polyline share a node by construction,
   and collinear overlap — two segments lying along each other — is reported as a
   crossing at the overlap's start, because a doubled-back outline is exactly as
   fatal to the triangulator as a transversal one."
  [p p2 q q2]
  (let [r (la/v-sub p2 p)
        s (la/v-sub q2 q)
        denom (cross2 r s)
        qp (la/v-sub q p)]
    (if (< (Math/abs denom) 1e-12)
      ;; parallel; collinear overlap still counts
      (when (< (Math/abs (cross2 qp r)) 1e-9)
        (let [rr (la/v-dot r r)]
          (when (> rr 1e-18)
            (let [t0 (/ (la/v-dot qp r) rr)
                  t1 (+ t0 (/ (la/v-dot s r) rr))
                  [lo hi] (if (< t0 t1) [t0 t1] [t1 t0])
                  lo' (max lo 0.0) hi' (min hi 1.0)]
              ;; a bare touch at one point is not an overlap
              (when (> (- hi' lo') 1e-9)
                (la/v-add p (la/v-scale r lo')))))))
      (let [t (/ (cross2 qp s) denom)
            u (/ (cross2 qp r) denom)
            eps 1e-9]
        (when (and (> t eps) (< t (- 1.0 eps))
                   (> u eps) (< u (- 1.0 eps)))
          (la/v-add p (la/v-scale r t)))))))

(defn self-intersections
  "Every place `pts` crosses itself, as [[x y] …] — empty when it does not.

   `pts` is the outline's tessellated points in order, 2D. `closed?` adds the
   segment from the last point back to the first, which is where a closed
   outline most often crosses (the seam is the one join the eye does not check).

   Neighbouring segments are skipped: they share a node, and sharing a node is
   not crossing. On a closed outline the first and last segments are neighbours
   too, which is easy to forget and would report every closed outline as broken."
  ([pts] (self-intersections pts true))
  ([pts closed?]
   (let [pts (vec pts)
         n (count pts)]
     (if (< n (if closed? 4 4))
       []
       (let [segs (vec (concat (map (fn [i] [(nth pts i) (nth pts (inc i))])
                                    (range (dec n)))
                               (when closed? [[(nth pts (dec n)) (nth pts 0)]])))
             m (count segs)
             adjacent? (fn [i j]
                         (or (= 1 (- j i))
                             ;; on a closed ring the last and the first touch
                             (and closed? (zero? i) (= j (dec m)))))]
         (vec (distinct
               (for [i (range m) j (range (inc i) m)
                     :when (not (adjacent? i j))
                     :let [[a b] (nth segs i) [c d] (nth segs j)
                           x (seg-intersection a b c d)]
                     :when x]
                 (mapv #(/ (Math/round (* % 1e6)) 1e6) x)))))))))

(defn self-intersects?
  "Cheap yes/no — stops at the first crossing."
  ([pts] (self-intersects? pts true))
  ([pts closed?] (boolean (seq (self-intersections pts closed?)))))
