(ns ridley.photogrammetry.ellipse
  "Robust ELLIPSE (conic) fit over a 2-D point set by RANSAC — fetta-C's tool for
   SELECTING the plate's crown discs out of the detector's superset. The 12 crown
   marks lie on a circle, so in the image they lie on an ELLIPSE; the zero-index
   (radially inside) and the frame noise do NOT, so the best-fitting ellipse's
   INLIERS are exactly the crown. This turns fit-crown from a blind quartet search
   (thousands of homographies per photo) into: fit one ellipse → hand the ~12 clean
   crown points to a single identity solve.

   A general conic is a·x²+b·xy+c·y²+d·x+e·y+f = 0. We fit it from 5 points with the
   constant pinned (f=1) and la/solve — the same scale-pin trick estimate-homography
   uses for h33, valid because a centred ellipse's constant term is non-zero (and we
   Hartley-normalise first, so the crown ellipse IS centred near the origin). Pixel
   coordinates make x²~10⁷: without normalisation the 5×5 is hopelessly ill-
   conditioned, so normalisation is not optional.

   Deterministic (a seeded LCG, never Math/random), so it is node-testable and gives
   the same answer every run — no flaky registration."
  (:require [ridley.photogrammetry.linalg :as la]))

(defn- prng
  "A tiny deterministic LCG yielding [0,1) — reproducible RANSAC sampling."
  [seed]
  (let [s (atom (bit-and (bit-or (int seed) 1) 0x7fffffff))]
    (fn []
      (/ (unsigned-bit-shift-right
          (swap! s #(bit-and (+ (* % 1103515245) 12345) 0x7fffffff)) 0)
         2147483648.0))))

(defn- normalize
  "Hartley normalisation: translate `pts` to their centroid and scale so the mean
   distance to the origin is √2. Returns the normalised points (raw coords are only
   needed to fit — the caller wants inlier INDICES, which normalisation preserves)."
  [pts]
  (let [n (count pts)
        cx (/ (reduce + 0.0 (map first pts)) n)
        cy (/ (reduce + 0.0 (map second pts)) n)
        md (/ (reduce + 0.0 (map (fn [[x y]] (Math/hypot (- x cx) (- y cy))) pts)) n)
        s (/ (Math/sqrt 2.0) (max 1e-9 md))]
    (mapv (fn [[x y]] [(* s (- x cx)) (* s (- y cy))]) pts)))

(defn- fit-conic-5
  "Conic [a b c d e f] through the 5 points, f pinned to 1. nil if singular."
  [pts5]
  (let [A (mapv (fn [[x y]] [(* x x) (* x y) (* y y) x y]) pts5)
        b (vec (repeat 5 -1.0))]
    (when-let [sol (la/solve A b)]
      (conj (vec sol) 1.0))))

(defn- ellipse?
  "Is the conic an ellipse (not a parabola/hyperbola)? b² − 4ac < 0."
  [[a b c _ _ _]]
  (neg? (- (* b b) (* 4.0 a c))))

(defn- sampson-sq
  "Squared Sampson distance of `[x y]` to the conic — |Q|²/|∇Q|², the first-order
   geometric residual (≈ squared distance to the curve). ~0 on the conic."
  [[a b c d e f] [x y]]
  (let [q (+ (* a x x) (* b x y) (* c y y) (* d x) (* e y) f)
        gx (+ (* 2.0 a x) (* b y) d)
        gy (+ (* b x) (* 2.0 c y) e)]
    (/ (* q q) (max 1e-12 (+ (* gx gx) (* gy gy))))))

(defn- pick5
  "5 distinct indices in [0,n) from the generator `r`."
  [r n]
  (loop [acc #{}]
    (if (>= (count acc) 5)
      (vec acc)
      (recur (conj acc (min (dec n) (int (* (r) n))))))))

(defn fit-inliers
  "RANSAC an ellipse through `pts` ([[x y] …]) and return the inlier INDICES sorted
   by fit (closest to the ellipse first). opts:
     :iters       RANSAC samples (default 150)
     :thr         inlier band — Sampson distance in NORMALISED units (default 0.03)
     :min-inliers give up below this many (default 6)
     :seed        LCG seed (default 1) — determinism
   Returns [] when fewer than 5 points or no ellipse gathers :min-inliers."
  [pts {:keys [iters thr min-inliers seed] :or {iters 150 thr 0.03 min-inliers 6 seed 1}}]
  (let [n (count pts)]
    (if (< n 5)
      []
      (let [np (normalize pts)
            r (prng seed)
            thr2 (* thr thr)
            [in conic]
            (loop [i 0 best-in [] best-conic nil]
              (if (>= i iters)
                [best-in best-conic]
                (let [conic (fit-conic-5 (mapv #(nth np %) (pick5 r n)))]
                  (if (and conic (ellipse? conic))
                    (let [inl (filterv #(< (sampson-sq conic (nth np %)) thr2) (range n))]
                      (if (> (count inl) (count best-in))
                        (recur (inc i) inl conic)
                        (recur (inc i) best-in best-conic)))
                    (recur (inc i) best-in best-conic)))))]
        (if (or (nil? conic) (< (count in) min-inliers))
          []
          (vec (sort-by #(sampson-sq conic (nth np %)) in)))))))
