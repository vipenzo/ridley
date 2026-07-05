;; Ridley Workspace — Libraries: puppet, mount
(sdf-resolution! 120)

(def golden-angle 137.50776405003785)

(defn fib-bumps [n r bump-r]
  (sdf-union
    (for [i (range n)]
      (let [z (- 1 (/ (+ (* 2 i) 1) n)) ; da ~+1 a ~-1, aree uguali
            el (to-degrees (asin z)) ; elevazione in gradi
            az (mod (* i golden-angle) 360)]
        (attach (sdf-sphere bump-r)
          (th az) (tv el) (f r))))))

(register Toy
  (sdf-shell
    (sdf-blend
      (sdf-sphere 45)
      (fib-bumps 30 45 8)
      2)
    2))