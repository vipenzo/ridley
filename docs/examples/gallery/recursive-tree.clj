;; Recursive Tree
;; A 3D fractal tree built with turtle-scope branching. Each level splits
;; into several thinner, shorter branches, angled outward with the golden
;; angle for even spatial distribution.
;; Try changing: max-depth (3-6), n-branches, spread-angle, taper.

(def max-depth 6)
(def n-branches 3)
(def spread-angle 35)
(def taper 0.65)
(def golden-angle 137.508)

(defn branch [depth length radius]
  (when (> depth 0)
    (let [trunk (loft (tapered (circle radius) :to taper) (f length))]
      (f length)
      (cons trunk
        (mapcat (fn [i]
          (turtle
            (tr (* i (/ 360 n-branches)))
            (tv spread-angle)
            (tr (* i golden-angle))
            (branch (dec depth)
                    (* length taper)
                    (* radius taper))))
          (range n-branches))))))

(tv 90)
(register tree (branch max-depth 30 3))
