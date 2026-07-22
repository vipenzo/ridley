(ns ridley.photogrammetry.edge-snap
  "Sub-pixel gradient edge-snap, ported from scripts/param-acq-tool.html's
   crossPeak/snapLine (accertamento 5, validated on real photos) — never
   compiled to CLJS before this. Pure: takes a `lum-at` function (image x,y
   -> luminance or nil off-image) instead of touching a canvas directly, so
   it's testable without one; ridley.editor.acquire-backdrop supplies the
   real one from the loaded photo's pixels.")

(defn cross-peak
  "Perpendicular gradient search from (cx,cy) along unit normal (nx,ny), out
   to ±H pixels. Central-difference gradient magnitude at each integer
   offset, parabolic interpolation around its peak for the sub-pixel
   location. nil when the peak sits at either end of the scan or is too weak
   (< 6 luminance units) to trust."
  [lum-at cx cy nx ny H]
  (let [g (vec (for [t (range (- H) (inc H))]
                 (let [a (lum-at (+ cx (* nx (dec t))) (+ cy (* ny (dec t))))
                       b (lum-at (+ cx (* nx (inc t))) (+ cy (* ny (inc t))))]
                   (if (or (nil? a) (nil? b)) 0.0 (Math/abs (- b a))))))
        n (count g)
        k (reduce (fn [best i] (if (> (nth g i) (nth g best)) i best)) 0 (range 1 n))]
    (when (and (pos? k) (< k (dec n)) (>= (nth g k) 6.0))
      (let [g-prev (nth g (dec k)) g-k (nth g k) g-next (nth g (inc k))
            denom (let [d (* 2.0 (+ g-prev (* -2.0 g-k) g-next))]
                    (if (zero? d) 1e-9 d))
            d (/ (- g-prev g-next) denom)]
        {:t (+ (- k H) d) :strength g-k}))))

(defn snap-segment
  "Sample N points along the projected edge p1->p2, snap each perpendicular
   via cross-peak against the real photo pixels, and refit a line through the
   surviving points (PCA on their covariance — direction of maximum
   variance). nil when too few points snap (< max(8, 0.3N)) or the segment is
   too short to sample (< 5px)."
  ([lum-at p1 p2] (snap-segment lum-at p1 p2 14 40))
  ([lum-at [x1 y1] [x2 y2] H N]
   (let [dx (- x2 x1) dy (- y2 y1)
         len (Math/sqrt (+ (* dx dx) (* dy dy)))]
     (when (>= len 5)
       (let [ux (/ dx len) uy (/ dy len)
             nx (- uy) ny ux
             pts (vec (keep (fn [i]
                              (let [f (/ (double i) (dec N))
                                    cx (+ x1 (* dx f)) cy (+ y1 (* dy f))]
                                (when-let [{:keys [t]} (cross-peak lum-at cx cy nx ny H)]
                                  [(+ cx (* nx t)) (+ cy (* ny t))])))
                            (range N)))]
         (when (>= (count pts) (max 8 (* N 0.3)))
           (let [n (count pts)
                 mx (/ (reduce + (map first pts)) n)
                 my (/ (reduce + (map second pts)) n)
                 [sxx syy sxy]
                 (reduce (fn [[sxx syy sxy] [px py]]
                           (let [a (- px mx) b (- py my)]
                             [(+ sxx (* a a)) (+ syy (* b b)) (+ sxy (* a b))]))
                         [0.0 0.0 0.0] pts)
                 th (* 0.5 (Math/atan2 (* 2.0 sxy) (- sxx syy)))
                 vx (Math/cos th) vy (Math/sin th)
                 rr (reduce (fn [acc [px py]]
                              (let [r (+ (* (- px mx) (- vy)) (* (- py my) vx))]
                                (+ acc (* r r))))
                            0.0 pts)
                 half (/ len 2.0)]
             {:p1 [(- mx (* vx half)) (- my (* vy half))]
              :p2 [(+ mx (* vx half)) (+ my (* vy half))]
              :n n
              :rms (Math/sqrt (/ rr n))})))))))
