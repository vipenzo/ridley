(ns ridley.photogrammetry.edge-snap
  "Sub-pixel gradient edge-snap, ported from scripts/param-acq-tool.html's
   crossPeak/snapLine (accertamento 5, validated on real photos) — never
   compiled to CLJS before this. Pure: takes a `lum-at` function (image x,y
   -> luminance or nil off-image) instead of touching a canvas directly, so
   it's testable without one; ridley.editor.acquire-backdrop supplies the
   real one from the loaded photo's pixels.

   `edge-at-point` (2026-08-06) is the newer half: ONE click, and the edge finds
   its own direction and its own extent. See its docstring — the two live
   together because they share cross-peak, which is the only thing in here that
   touches pixels.")

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

(defn fit-line
  "PCA line through 2D points: centroid + unit direction of maximum variance,
   with the perpendicular scatter and how far the points reach along it.

   Returns {:mx :my :vx :vy :rms :n :t-min :t-max}, or nil for fewer than two
   points. `:rms` is the honest answer to 'are these points on a LINE at all' —
   on a curve it grows with the arc, which is what turns a straight-edge
   detector into an honest one instead of a confident one."
  [pts]
  (when (>= (count pts) 2)
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
          ts (mapv (fn [[px py]] (+ (* (- px mx) vx) (* (- py my) vy))) pts)
          rr (reduce (fn [acc [px py]]
                       (let [r (+ (* (- px mx) (- vy)) (* (- py my) vx))]
                         (+ acc (* r r))))
                     0.0 pts)]
      {:mx mx :my my :vx vx :vy vy :n n
       :rms (Math/sqrt (/ rr n))
       :t-min (reduce min ts) :t-max (reduce max ts)})))

(defn snap-segment
  "Sample N points along the projected edge p1->p2, snap each perpendicular
   via cross-peak against the real photo pixels, and refit a line through the
   surviving points (PCA on their covariance — direction of maximum
   variance). nil when too few points snap (< max(8, 0.3N)) or the segment is
   too short to sample (< 5px).

   The returned segment keeps the ORIGINAL length, re-centred on the fitted
   centroid: the caller said where the edge runs from and to, and the snap only
   corrects where the line sits and which way it points."
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
           (let [{:keys [mx my vx vy rms n]} (fit-line pts)
                 half (/ len 2.0)]
             {:p1 [(- mx (* vx half)) (- my (* vy half))]
              :p2 [(+ mx (* vx half)) (+ my (* vy half))]
              :n n
              :rms rms})))))))

;; ---------------------------------------------------------------------------
;; ONE CLICK (dev-docs/brief-observation-driven-acquire.md, gradino 3)
;;
;; A click is a point, and a point is not a line. The missing direction can only
;; come from one of two places, and only one of them works:
;;
;; - from the geometry already known: no. Once the edge is declared on the first
;;   photo, a single click on a second one constrains the 3D line by ONE of its
;;   four degrees of freedom, so a second click is needed there anyway. Nothing
;;   is saved.
;; - from the image: yes. Around the click the edge's direction is already
;;   written in the gradients, and the STRUCTURE TENSOR reads it — plus, for
;;   free, how much of an edge that neighbourhood is at all: one strong
;;   eigenvalue means a clean line, two mean a corner or texture, none mean flat.
;;
;; With the direction in hand the program then WALKS the edge, snapping
;; perpendicular at each station, until the contrast dies. So one click does not
;; merely replace two: it also finds the extent, and it walks further than anyone
;; would bother tracing by hand — which improves the conditioning rather than
;; trading it away for convenience.
;; ---------------------------------------------------------------------------

(def min-coherence
  "How line-like the neighbourhood of the click must be before a single click is
   trusted to have found a direction — the structure tensor's (λ1−λ2)/(λ1+λ2),
   which is 1 for a perfectly straight edge and 0 where the gradients point every
   way (a corner, a texture, noise on a flat area).

   0.55 is deliberately demanding, because the failure this guards against is
   silent: a corner has two directions and the tensor averages them into a third
   that belongs to neither, which would come back as a confident edge running
   diagonally into nothing. Below it the gesture asks for the two clicks instead
   of guessing — the honest fallback, not a refusal."
  0.55)

(def min-edge-strength
  "Smallest |gradient| the click's neighbourhood must show, in luminance units
   per pixel, before there is anything to call an edge. Matches cross-peak's own
   floor (6 units across its 2px difference)."
  3.0)

(def max-straight-rms-px
  "Perpendicular scatter, in pixels, above which points are not on a straight
   line. On a real edge this sits well under a pixel; on the rim of a disc the
   arc pushes past it within a few tens of pixels, which is precisely how a curve
   announces itself instead of being fitted as a chord."
  1.2)

(def min-straight-px
  "How long the straight stretch around the click must be to count as an edge in
   its own right, rather than as the chord of an arc.

   It exists because real edges END IN CURVES — a moulding's edge runs into a
   fillet, a corner is rounded — so a walk that follows the contrast as far as it
   goes will routinely leave the straight part and enter the bend. Measured on
   Vincenzo's own clip (2026-08-06): before the straight stretch was extracted,
   one blind click succeeded in 1442; the other candidates were being thrown away
   as :curved because of the bend at their far end.

   60 px, because that is where the direction is still worth having: with ~0.5 px
   of perpendicular snap noise over ~30 stations, 60 px of baseline pins the
   direction to about a third of a degree, and below it the hand does as well.
   The sagitta says the same thing from the other side — a circle only stays
   within max-straight-rms-px for sqrt(8·R·tol) px, i.e. 34 px on a 120 px radius,
   so an arc cannot reach this length and is still refused as an arc."
  60.0)

(def min-zoned-straight-px
  "The same floor, for an edge the user has BOUNDED with a painted zone.

   Lower because the declaration has changed hands. Unbounded, a short straight
   run means the walk could not find more, and 60 px is where a found direction
   starts being worth more than a drawn one. Inside a zone, a short run means the
   user painted a short band — a deliberate act, and usually the right one, since
   what they are doing is cutting the edge off before it turns into another. 25 px
   still carries a direction at a couple of tenths of a degree; below it the hand
   would do as well, zone or no zone."
  25.0)

(def ^:private min-straight-fraction
  "…and it must also be this much of what was walked. A straight stretch that is
   a small part of a long bending run IS an arc's chord, however long it is in
   pixels; this is the test the absolute length alone cannot make."
  0.35)

(defn- gradient-at
  "Central-difference gradient of luminance at (x,y), over the same 2px spacing
   cross-peak uses. nil when any sample falls off the image."
  [lum-at x y]
  (let [l (fn [dx dy] (lum-at (+ x dx) (+ y dy)))
        xm (l -1 0) xp (l 1 0) ym (l 0 -1) yp (l 0 1)]
    (when (and xm xp ym yp)
      [(* 0.5 (- xp xm)) (* 0.5 (- yp ym))])))

(defn structure-tensor
  "The local orientation of the image around (cx,cy), over a (2·half+1) square.

   Returns {:angle :coherence :strength}: `:angle` is the direction ALONG the
   edge (perpendicular to the mean gradient) in radians; `:coherence` is
   (λ1−λ2)/(λ1+λ2) over the gradient covariance, i.e. how much this
   neighbourhood is ONE direction rather than several; `:strength` is the mean
   gradient magnitude. nil when the window falls off the image entirely.

   The 2×2 eigen-decomposition is done in closed form (a half-angle atan2), so
   nothing here needs an eigen routine — the same house rule the rest of the
   channel follows."
  [lum-at cx cy half]
  (let [step (max 1 (quot half 8))
        acc (reduce (fn [[sxx syy sxy n mag] [dx dy]]
                      (if-let [[gx gy] (gradient-at lum-at (+ cx dx) (+ cy dy))]
                        [(+ sxx (* gx gx)) (+ syy (* gy gy)) (+ sxy (* gx gy))
                         (inc n) (+ mag (Math/hypot gx gy))]
                        [sxx syy sxy n mag]))
                    [0.0 0.0 0.0 0 0.0]
                    (for [dy (range (- half) (inc half) step)
                          dx (range (- half) (inc half) step)]
                      [dx dy]))
        [sxx syy sxy n mag] acc]
    (when (pos? n)
      (let [tr (+ sxx syy)
            disc (Math/sqrt (+ (* (- sxx syy) (- sxx syy)) (* 4.0 sxy sxy)))
            l1 (* 0.5 (+ tr disc))
            l2 (* 0.5 (- tr disc))
            ;; dominant GRADIENT direction; the edge runs across it
            grad-angle (* 0.5 (Math/atan2 (* 2.0 sxy) (- sxx syy)))]
        {:angle (+ grad-angle (/ Math/PI 2.0))
         :coherence (if (> (+ l1 l2) 1e-12) (/ (- l1 l2) (+ l1 l2)) 0.0)
         :strength (/ mag n)}))))

(defn- walk
  "Follow the edge from (x0,y0) along unit direction (ux,uy), snapping
   perpendicular at every `step` pixels, and stop when the contrast dies, the
   edge turns away, or the walk leaves the zone it was told to stay in. Returns
   the snapped points found (excluding the start).

   Stopping is where the honesty lives. `misses` consecutive stations without a
   peak ends the walk — that is the edge's END (at a corner the peak jumps off
   the search band and vanishes, which is exactly what should stop it), and so
   does a peak that lands further than `jump` from where the walk expected it,
   which is a different edge crossing rather than this one continuing.

   `in-zone?` is the one the USER controls, and it is the answer to the way this
   fails on real objects: contrast dying is not the same as the edge ending, so a
   walk that only watches the contrast follows the outline round a corner and
   into a stretch that lies on another plane entirely (Vincenzo, 2026-08-07: «la
   cattura della linea ha preso troppo: insegue tratti non complanari»). Which
   edge is meant is knowledge the program does not have and the user does — so
   the user paints a band and the walk stays inside it."
  [lum-at x0 y0 ux uy {:keys [step max-len H misses jump in-zone?]}]
  (let [nx (- uy) ny ux]
    (loop [d step, miss 0, off 0.0, acc []]
      (if (or (> d max-len) (>= miss misses))
        acc
        (let [cx (+ x0 (* ux d) (* nx off))
              cy (+ y0 (* uy d) (* ny off))]
          (if (and in-zone? (not (in-zone? cx cy)))
            acc
            (let [pk (cross-peak lum-at cx cy nx ny H)]
              (cond
                (nil? pk) (recur (+ d step) (inc miss) off acc)
                (> (Math/abs (:t pk)) jump) (recur (+ d step) (inc miss) off acc)
                :else
                (let [px (+ cx (* nx (:t pk))) py (+ cy (* ny (:t pk)))]
                  (if (and in-zone? (not (in-zone? px py)))
                    acc
                    (recur (+ d step) 0 (+ off (:t pk)) (conj acc [px py]))))))))))))

;; ---- the straight stretch around the click ----
;;
;; Running sums, so extending a window by one point costs O(1) instead of a
;; refit: the search below tries every extension and would otherwise be
;; quadratic in the length of the walk (which reaches ~900 px, i.e. hundreds of
;; points).

(defn- sums-add [{:keys [n sx sy sxx syy sxy]} [x y]]
  {:n (inc n) :sx (+ sx x) :sy (+ sy y)
   :sxx (+ sxx (* x x)) :syy (+ syy (* y y)) :sxy (+ sxy (* x y))})

(def ^:private sums-zero {:n 0 :sx 0.0 :sy 0.0 :sxx 0.0 :syy 0.0 :sxy 0.0})

(defn- sums-rms
  "Perpendicular rms of the points behind these sums — the smaller eigenvalue of
   their covariance, in closed form. 0 for fewer than three points, where a line
   passes through them exactly and the number would mean nothing."
  [{:keys [n sx sy sxx syy sxy]}]
  (if (< n 3)
    0.0
    (let [nn (double n)
          mx (/ sx nn) my (/ sy nn)
          cxx (- (/ sxx nn) (* mx mx))
          cyy (- (/ syy nn) (* my my))
          cxy (- (/ sxy nn) (* mx my))
          half (* 0.5 (+ cxx cyy))
          disc (Math/sqrt (max 0.0 (+ (* 0.25 (- cxx cyy) (- cxx cyy)) (* cxy cxy))))]
      (Math/sqrt (max 0.0 (- half disc))))))

(defn- straight-run-around
  "The longest run of CONSECUTIVE points containing index `i0` whose line fit
   stays within `tol` — i.e. the straight stretch of edge the user pointed at.

   Real edges end in curves: a moulding runs into a fillet, a corner is rounded.
   A walk that follows the contrast as far as it goes will leave the straight
   part, and fitting a line to everything it found would then reject the good
   part along with the bend. Growing outward from the CLICK instead keeps what
   the user meant and drops what came after it.

   Grows one point at a time, always taking whichever side keeps the fit
   straighter, so the run does not stop early on one side while the other could
   still have grown. Returns [i j] inclusive."
  [pts i0 tol]
  (let [n (count pts)
        add (fn [s i] (sums-add s (nth pts i)))]
    (loop [i i0, j i0, s (add sums-zero i0)]
      (let [li (dec i), rj (inc j)
            left (when (>= li 0) (add s li))
            right (when (< rj n) (add s rj))
            lr (when left (sums-rms left))
            rr (when right (sums-rms right))
            take-left? (and lr (<= lr tol) (or (nil? rr) (> rr tol) (<= lr rr)))
            take-right? (and rr (<= rr tol) (not take-left?))]
        (cond
          take-left? (recur li j left)
          take-right? (recur i rj right)
          :else [i j])))))

(defn edge-at-point
  "ONE click on an edge → the straight segment of image it belongs to.

   `lum-at` is the photo's luminance sampler; (cx,cy) the clicked pixel, which
   may be a few pixels off the edge — it is snapped on first.

   Returns {:ok? true :p1 :p2 :n :rms :coherence :length-px}, the segment being
   the full stretch the walk could follow, or {:ok? false :reason …} with the
   reason the caller should SAY rather than swallow:

     :flat       nothing here has enough contrast to be an edge;
     :ambiguous  the neighbourhood holds more than one direction — a corner, a
                 texture, a place where two edges cross. The tensor would average
                 them into a direction belonging to neither;
     :short      the edge was found but dies within a few pixels, so its
                 direction is worth less than the hand that clicked it;
     :curved     what was walked bends — the rim of a disc, a fillet seen
                 edge-on. Straight edges only, for now.

   Two passes: the tensor's direction is coarse (it is an average over a square
   window), so the first walk is used only to fit a better direction, and the
   second walk follows THAT. It converges immediately because the perpendicular
   snap is doing the real work — the direction only has to be good enough to
   keep the search band on the edge.

   Then the STRAIGHT STRETCH around the click is taken out of the walk
   (straight-run-around), because real edges end in curves and a walk that
   follows the contrast as far as it goes will leave the straight part. Without
   that step the bend at the far end condemns the good part with it: on
   Vincenzo's clip it was the difference between one blind click succeeding in
   1442 and one in 25. `:walked-px` reports how far the contrast went, next to
   `:length-px` for what was kept — a big gap between the two is the edge
   bending, said in numbers."
  ([lum-at cx cy] (edge-at-point lum-at cx cy {}))
  ([lum-at cx cy {:keys [window step max-len H misses jump in-zone? zoned?]
                  :or {window 12 step 2.0 max-len 900.0 H 10 misses 4 jump 6.0}}]
   (let [tensor (structure-tensor lum-at cx cy window)]
     (cond
       (nil? tensor) {:ok? false :reason :flat}
       (< (:strength tensor) min-edge-strength) {:ok? false :reason :flat}
       (< (:coherence tensor) min-coherence)
       {:ok? false :reason :ambiguous :coherence (:coherence tensor)}

       :else
       (let [a (:angle tensor)
             ux (Math/cos a) uy (Math/sin a)
             ;; put the start ON the edge: the click is only approximately there
             start (if-let [{:keys [t]} (cross-peak lum-at cx cy (- uy) ux H)]
                     [(+ cx (* (- uy) t)) (+ cy (* ux t))]
                     [cx cy])
             opts {:step step :max-len max-len :H H :misses misses :jump jump
                   :in-zone? in-zone?}
             pass (fn [[sx sy] [dx dy]]
                    (into [[sx sy]]
                          (concat (walk lum-at sx sy dx dy opts)
                                  (walk lum-at sx sy (- dx) (- dy) opts))))
             pts1 (pass start [ux uy])
             fit1 (fit-line pts1)
             [ux uy] (if fit1 [(:vx fit1) (:vy fit1)] [ux uy])
             walked (pass start [ux uy])
             rough (fit-line walked)]
         (if (nil? rough)
           {:ok? false :reason :flat}
           ;; order the walk ALONG the edge, so 'the run around the click' is a
           ;; contiguous window rather than a set — the walk itself comes back
           ;; start-first, then one way, then the other.
           (let [key-of (fn [[x y]] (+ (* (- x (:mx rough)) (:vx rough))
                                       (* (- y (:my rough)) (:vy rough))))
                 ordered (vec (sort-by key-of walked))
                 walked-px (- (:t-max rough) (:t-min rough))
                 i0 (apply min-key #(Math/abs (- (key-of (nth ordered %))
                                                 (key-of (first walked))))
                           (range (count ordered)))
                 [i j] (straight-run-around ordered i0 max-straight-rms-px)
                 {:keys [mx my vx vy rms n t-min t-max] :as fit}
                 (fit-line (subvec ordered i (inc j)))
                 kept (when fit (- t-max t-min))]
             (cond
               (nil? fit) {:ok? false :reason :flat}
               (< walked-px 20.0) {:ok? false :reason :short :length-px walked-px}
               ;; the straight part is a sliver of a long bending run, or too
               ;; short to be worth more than the hand: that is an ARC
               ;; With a ZONE the user has said where the edge ends, and their
               ;; declaration replaces the program's guess: the absolute floor
               ;; drops to what still carries a direction, because painting a
               ;; short band is a deliberate act and not a failure to find more.
               (or (< kept (if zoned? min-zoned-straight-px min-straight-px))
                   (< kept (* min-straight-fraction walked-px)))
               ;; the walked points travel WITH the refusal, ordered along the
               ;; curve: a bend is not a failure to find an edge, it is finding a
               ;; curved one, and the circle fit takes exactly this cloud. The
               ;; walk has already done the hard part.
               {:ok? false :reason :curved :rms (:rms rough)
                :length-px kept :walked-px walked-px :points ordered}
               :else
               {:ok? true
                :p1 [(+ mx (* vx t-min)) (+ my (* vy t-min))]
                :p2 [(+ mx (* vx t-max)) (+ my (* vy t-max))]
                :n n
                :rms rms
                :coherence (:coherence tensor)
                :length-px kept
                :walked-px walked-px
                :points (subvec ordered i (inc j))}))))))))
