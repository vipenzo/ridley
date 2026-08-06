(ns ridley.photogrammetry.circle
  "The 3D CIRCLE (or arc) that best explains the same curved edge declared on
   several photos — the other half of the edge gesture
   (dev-docs/brief-observation-driven-acquire.md, gradino 3).

   Why it belongs next to edge.cljs rather than inside it. A straight edge is
   pinned by a wonderfully cheap fact: a line seen in an image, together with the
   camera centre, spans a PLANE, and two planes meet in exactly one line. A curve
   spans a CONE instead, and two cones meet in a quartic — so the closed form
   that makes edges easy simply is not there. What replaces it is not harder to
   understand, only different: get 3D points on the curve first, then fit.

   And 3D points on the curve cost nothing here, because the poses are already
   known. A ray through a pixel of photo A and a ray through a pixel of photo B
   meet in space only if both pixels are images of the SAME 3D point; so of all
   the rays from B, the one that comes closest to a given ray from A identifies
   that point, and their meeting place IS it. No correspondence is asked of the
   user and none is guessed at: the geometry does the matching, and the closest-
   approach distance is its own confidence measure — false pairings do not come
   close, and the ones that do are dropped by a robust plane fit afterwards.

   From there it is ordinary: fit a plane to the recovered points, fit a circle
   inside that plane (Kasa, one linear solve), and refine all six degrees of
   freedom — plane normal 2, centre 3, radius 1 — against the reprojection in
   PIXELS, the same currency as everything else in the channel.

   Pure: poses and pixels in, millimetres out."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.lm :as lm]
            [ridley.photogrammetry.pnp :as pnp]))

(def min-match-mm
  "How near two rays must pass to be believed images of the same point.

   Measured, not guessed (Vincenzo's plate, two ring photos of its rim,
   2026-08-06): where the pairing is RIGHT the rays pass at 0.15 mm; where it is
   wrong, at 3.3 mm. There is a factor of twenty between the two populations and
   0.5 sits in the gap.

   The first version used 1.5 mm and it was a mistake worth recording, because it
   did not fail loudly: a handful of nearly-coplanar ray pairs — the same poor
   parallax that makes a half-turn blind for a straight edge — each contributed
   dozens of spurious meetings, and the cloud came out 329 points of which 28
   were real. RANSAC then found a circle of radius 1838 mm through the debris
   with more support than the true one had."
  0.5)

(def min-arc-deg
  "How much of the circle must have been seen before the fit is worth anything.
   Below a third of a turn the radius and the centre trade against each other
   almost freely — a short arc is fitted equally well by a small circle nearby
   and a huge one far away, which is the same ill-conditioning that makes a
   conic fit to a stub meaningless. Reported, not silently tolerated."
  120.0)

(def ^:private plane-outlier-mm
  "A recovered point further than this from the fitted plane did not come from
   the curve — it is a ray pairing that happened to pass close. Dropped, and the
   plane refitted without it."
  2.0)

(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])

(defn- unit [v]
  (let [n (la/v-norm v)]
    (when (> n 1e-12) (la/v-scale v (/ 1.0 n)))))

(defn- ray-closest
  "Closest approach of two rays, both directions UNIT: [midpoint distance], or
   nil when they are parallel."
  [{o1 :origin d1 :dir} {o2 :origin d2 :dir}]
  (let [w (la/v-sub o1 o2)
        b (la/v-dot d1 d2)
        d (la/v-dot d1 w)
        e (la/v-dot d2 w)
        den (- 1.0 (* b b))]
    (when (> (Math/abs den) 1e-9)
      (let [s (/ (- (* b e) d) den)
            t (/ (- e (* b d)) den)
            p1 (la/v-add o1 (la/v-scale d1 s))
            p2 (la/v-add o2 (la/v-scale d2 t))]
        [(la/v-scale (la/v-add p1 p2) 0.5) (la/v-norm (la/v-sub p1 p2))]))))

(defn- subsample
  "At most `n` points, evenly spaced along the list. The walk lays a station every
   couple of pixels, so a curve arrives hundreds of points long and almost all of
   them say the same thing; thinning is what keeps the fit interactive."
  [pts n]
  (let [c (count pts)]
    (if (<= c n)
      (vec pts)
      (mapv #(nth pts (Math/round (* (/ (double %) (dec n)) (dec c)))) (range n)))))

(defn- rays-of [k pose pts]
  (mapv #(cam/pixel-ray k pose %) pts))

(defn- meet-points
  "3D points where rays from view A meet rays from view B — brute force, closed
   form, a few thousand closest approaches, which is nothing.

   ONE meeting per ray of A, the closest, and only if it passes within
   min-match-mm. Each declared point of A is the image of exactly one 3D point,
   so one is the physically correct number — and the discipline matters: without
   it a single ray of poor parallax pairs acceptably with dozens of B's and
   floods the cloud with its own ghosts, which is how the first version came to
   fit a circle of radius 1838 mm to a plate of 130.

   Ghosts still get through — a ray of A does pierce B's cone twice — and that is
   what ransac-circle is for. This only keeps their number down to something a
   robust fit can carry."
  [rays-a rays-b]
  (into []
        (keep (fn [ra]
                (let [best (reduce (fn [best rb]
                                     (if-let [[p d] (ray-closest ra rb)]
                                       (if (or (nil? best) (< d (second best))) [p d] best)
                                       best))
                                   nil rays-b)]
                  (when (and best (< (second best) min-match-mm)) (first best)))))
        rays-a))

(defn- prng
  "Deterministic LCG — the same one ellipse.cljs uses, for the same reason: a
   RANSAC that answers differently each run is not testable, and a registration
   that moves when nothing changed is not trustworthy."
  [seed]
  (let [s (atom (bit-and (bit-or (int seed) 1) 0x7fffffff))]
    (fn [] (/ (unsigned-bit-shift-right
               (swap! s #(bit-and (+ (* % 1103515245) 12345) 0x7fffffff)) 0)
              2147483648.0))))

(defn- circle-through-3
  "The circle through three 3D points: their plane, then the circumcentre inside
   it. nil when they are collinear."
  [p q r]
  (let [a (la/v-sub q p) b (la/v-sub r p)
        n (unit (cross3 a b))]
    (when n
      (let [aa (la/v-dot a a) bb (la/v-dot b b) ab (la/v-dot a b)
            den (- (* aa bb) (* ab ab))]
        (when (> (Math/abs den) 1e-12)
          (let [s (/ (- (* aa bb) (* bb ab)) (* 2.0 den))
                t (/ (- (* aa bb) (* aa ab)) (* 2.0 den))
                c (la/v-add p (la/v-add (la/v-scale a s) (la/v-scale b t)))]
            {:center c :normal n :radius (la/v-norm (la/v-sub c p))}))))))

(defn- distance-to-circle
  "How far a 3D point is from the circle itself — out-of-plane and in-plane
   error combined, which is the only test that tells a point of the rim from a
   point that merely lies in its plane."
  [{:keys [center normal radius]} p]
  (let [d (la/v-sub p center)
        out (la/v-dot d normal)
        inp (Math/sqrt (max 0.0 (- (la/v-dot d d) (* out out))))]
    (Math/hypot out (- inp radius))))

(defn- cloud-extent
  "Largest spread of the cloud along a coordinate axis — a scale for the object,
   read off the data instead of asked of the caller."
  [pts]
  (reduce max 0.0
          (for [k (range 3)]
            (let [xs (map #(nth % k) pts)]
              (- (reduce max xs) (reduce min xs))))))

(def ^:private max-radius-factor
  "A candidate circle may not be larger than this many times the cloud's own
   extent. Without the bound RANSAC has a runaway favourite: a huge circle is
   locally almost a straight line, so it can be threaded through scattered
   debris and collect more inliers than the true small one. On the plate it chose
   radius 1838 mm for a rim of 65 — not a close call, a different kind of object.
   Three is loose enough that a circle seen edge-on, whose cloud is a sliver of
   its own diameter, still passes."
  3.0)

(defn- ransac-circle
  "The circle that most of the recovered points agree on, found by trying
   triples. Three points determine a circle in 3D exactly, which is what makes
   the sample so small and the search so cheap.

   Ties on inlier count are broken by TIGHTNESS, and both guards earn their
   keep: without the size bound the winner is a giant arc through the debris,
   and without the tie-break two candidates with equal support are settled by
   whichever came first.

   Returns [circle inliers] or nil."
  [pts tol iters seed]
  (let [n (count pts)
        r (prng seed)
        pick (fn [] (min (dec n) (int (* (r) n))))
        max-r (* max-radius-factor (max 1e-6 (cloud-extent pts)))
        score (fn [circ in]
                (/ (reduce + 0.0 (map #(distance-to-circle circ %) in))
                   (max 1 (count in))))]
    (when (>= n 3)
      (loop [i 0, best nil, best-in [], best-score 1e9]
        (if (>= i iters)
          (when (>= (count best-in) 5) [best best-in])
          (let [a (pick) b (pick) c (pick)]
            (if (or (= a b) (= b c) (= a c))
              (recur (inc i) best best-in best-score)
              (let [circ (circle-through-3 (nth pts a) (nth pts b) (nth pts c))]
                (if (or (nil? circ) (> (:radius circ) max-r))
                  (recur (inc i) best best-in best-score)
                  (let [in (filterv #(< (distance-to-circle circ %) tol) pts)
                        s (score circ in)]
                    (if (or (> (count in) (count best-in))
                            (and (= (count in) (count best-in)) (< s best-score)))
                      (recur (inc i) circ in s)
                      (recur (inc i) best best-in best-score))))))))))))

(defn- fit-circle-in-plane
  "Kasa circle fit on 2D points: the algebraic system that falls out of writing
   the circle as x²+y² + Dx + Ey + F = 0, which is LINEAR in (D,E,F). One 3x3
   solve, no iteration. Returns [cx cy r] or nil."
  [pts]
  (let [n (count pts)]
    (when (>= n 3)
      (let [rows (mapv (fn [[x y]] [x y 1.0]) pts)
            rhs (mapv (fn [[x y]] (- (+ (* x x) (* y y)))) pts)
            ;; normal equations, so the fit uses every point instead of three
            at (la/transpose rows)
            ata (la/mat*mat at rows)
            atb (la/mat*vec at rhs)]
        (when-let [[d e f] (la/solve ata atb)]
          (let [cx (* -0.5 d)
                cy (* -0.5 e)
                r2 (- (+ (* cx cx) (* cy cy)) f)]
            (when (> r2 1e-9)
              [cx cy (Math/sqrt r2)])))))))

(defn- plane-of
  "Robust plane through the recovered points: fit, drop what lies further than
   plane-outlier-mm from it, fit again. Returns pnp/plane-frame's {:o :u :n} or
   nil."
  [pts]
  (when-let [{:keys [o n] :as fr} (pnp/plane-frame pts)]
    (let [keep-pts (filterv #(< (Math/abs (la/v-dot (la/v-sub % o) n)) plane-outlier-mm) pts)]
      (if (and (>= (count keep-pts) 5) (< (count keep-pts) (count pts)))
        (or (pnp/plane-frame keep-pts) fr)
        fr))))

(defn- circle-points
  "The 3D circle sampled `n` times around — its own polyline, which is what the
   pixel residual measures against and what a caller draws."
  [{:keys [center normal radius]} n]
  (let [nn (unit normal)
        u (or (unit (la/v-sub [1.0 0.0 0.0] (la/v-scale nn (la/v-dot [1.0 0.0 0.0] nn))))
              (unit (cross3 nn [0.0 1.0 0.0]))
              [1.0 0.0 0.0])
        v (cross3 nn u)]
    (mapv (fn [i]
            (let [a (* 2.0 Math/PI (/ (double i) n))]
              (la/v-add center
                        (la/v-add (la/v-scale u (* radius (Math/cos a)))
                                  (la/v-scale v (* radius (Math/sin a)))))))
          (range n))))

(def ^:private ring-samples 72)

(def ^:private off-image-penalty 500.0)

(defn- point-to-polyline-px
  "Distance in pixels from `q` to the projected ring — the residual that needs no
   correspondence at all: it asks only 'how far is this observed pixel from the
   curve', which is the entire content of the observation."
  [ring q]
  (let [[qx qy] q]
    (reduce (fn [best i]
              (let [a (nth ring i)
                    b (nth ring (mod (inc i) (count ring)))]
                (if (or (nil? a) (nil? b))
                  best
                  (let [[ax ay] a [bx by] b
                        dx (- bx ax) dy (- by ay)
                        len2 (+ (* dx dx) (* dy dy))
                        t (if (< len2 1e-12)
                            0.0
                            (max 0.0 (min 1.0 (/ (+ (* (- qx ax) dx) (* (- qy ay) dy)) len2))))
                        px (+ ax (* t dx)) py (+ ay (* t dy))]
                    (min best (Math/hypot (- qx px) (- qy py)))))))
            off-image-penalty
            (range (count ring)))))

(defn- projected-ring [k pose circ]
  (mapv #(cam/project k pose %) (circle-points circ ring-samples)))

(defn- residuals
  "Pixel distance of every declared point from the projected circle, weighted."
  [views circ sigma-px]
  (vec (mapcat (fn [{:keys [pose intrinsics points]}]
                 (let [ring (projected-ring intrinsics pose circ)]
                   (map #(/ (point-to-polyline-px ring %) sigma-px) points)))
               views)))

(defn- unpack [[cx cy cz a b r] {n0 :normal} [u v]]
  {:center [cx cy cz]
   :normal (or (unit (la/v-add n0 (la/v-add (la/v-scale u a) (la/v-scale v b)))) n0)
   :radius (Math/abs r)})

(defn- arc-span-deg
  "How much of the circle the declared points actually covered, in degrees, and
   the angular interval they span. A full circle comes back 360; a fillet, the
   little it is — which is the number that says whether the radius is a
   measurement or an extrapolation."
  [{:keys [center normal radius]} views]
  (let [nn (unit normal)
        u (or (unit (la/v-sub [1.0 0.0 0.0] (la/v-scale nn (la/v-dot [1.0 0.0 0.0] nn))))
              (unit (cross3 nn [0.0 1.0 0.0])))
        v (cross3 nn u)
        angs (vec (for [{:keys [pose intrinsics points]} views
                        q points
                        :let [{:keys [origin dir]} (cam/pixel-ray intrinsics pose q)
                              ;; where this ray meets the circle's plane, expressed
                              ;; in the plane's own frame
                              den (la/v-dot dir nn)]
                        :when (> (Math/abs den) 1e-9)
                        :let [t (/ (la/v-dot (la/v-sub center origin) nn) den)
                              p (la/v-sub (la/v-add origin (la/v-scale dir t)) center)]]
                    (Math/atan2 (la/v-dot p v) (la/v-dot p u))))]
    (when (seq angs)
      ;; the largest GAP between consecutive angles is what is missing; the span
      ;; is the rest. Stated this way it is right for an arc that straddles the
      ;; +-pi wrap, where min/max would read a full turn.
      (let [sorted (vec (sort angs))
            gaps (conj (mapv - (rest sorted) (butlast sorted))
                       (+ (- (first sorted) (peek sorted)) (* 2.0 Math/PI)))
            biggest (reduce max gaps)]
        {:span-deg (* (/ 180.0 Math/PI) (- (* 2.0 Math/PI) biggest))
         :radius-mm radius}))))

(defn fit-circle
  "The world circle that best explains the same curved edge declared on several
   photos.

   `views` is [{:pose {:rvec :t} :intrinsics {…} :points [[u v] …]} …] — at least
   two, each holding the snapped points the walk collected along the curve
   (edge-snap/edge-at-point returns them with its :curved refusal, which is the
   intended way in). No correspondence between the photos is required or used.

   Returns nil when it cannot fit. Otherwise:

     {:center :normal :radius        — the circle, in world millimetres
      :span-deg                      — how much of it was actually seen
      :rms-px, :max-residual-px      — reprojection of every declared point
      :per-view                      — [{:i :rms-px :n} …]
      :points-3d                     — the ray meetings that survived, kept
                                       because they are the evidence
      :seed-n / :cloud-n             — how many survived, out of how many were
                                       recovered (about half are ghosts, by
                                       construction — see meet-points)}

   The caller judges: `:span-deg` against min-arc-deg (a short arc fits many
   circles almost equally well), `:rms-px` against what the session's other
   residuals look like."
  ([views] (fit-circle views {}))
  ([views {:keys [sigma-px max-iterations max-points tol-mm ransac-iters seed]
           :or {sigma-px 1.0 max-iterations 60 max-points 40
                tol-mm 1.5 ransac-iters 200 seed 7}}]
   (let [views (mapv #(update % :points (fn [p] (subsample p max-points))) (vec views))]
     (when (and (>= (count views) 2) (every? #(>= (count (:points %)) 5) views))
       (let [rays (mapv #(rays-of (:intrinsics %) (:pose %) (:points %)) views)
             ;; every pair of views contributes its meetings, so a third photo
             ;; adds evidence instead of merely confirming
             cloud (vec (mapcat (fn [[i j]] (meet-points (nth rays i) (nth rays j)))
                                (for [i (range (count views))
                                      j (range (inc i) (count views))]
                                  [i j])))
             ;; The ghosts are removed HERE, by the circle itself. Fitting a
             ;; plane to the raw cloud would fail: half of it is not on the
             ;; object at all, and least squares has no way to know.
             [_ inliers] (ransac-circle cloud tol-mm ransac-iters seed)
             frame (when (seq inliers) (plane-of inliers))
             in-plane (when frame
                        (let [{:keys [o u v]} frame]
                          (mapv (fn [p] (let [d (la/v-sub p o)]
                                          [(la/v-dot d u) (la/v-dot d v)]))
                                inliers)))
             kasa (when in-plane (fit-circle-in-plane in-plane))]
         (when kasa
           (let [{:keys [o u v n]} frame
                 [cx cy r] kasa
                 seed-circ {:center (la/v-add o (la/v-add (la/v-scale u cx) (la/v-scale v cy)))
                            :normal n
                            :radius r}
                 basis [u v]
                 rfn (fn [p] (residuals views (unpack p seed-circ basis) sigma-px))
                 p0 (into (vec (:center seed-circ)) [0.0 0.0 (:radius seed-circ)])
                 res (lm/solve rfn p0 {:max-iterations max-iterations})
                 circ (unpack (:params res) seed-circ basis)
                 dists (fn [{:keys [pose intrinsics points]}]
                         (let [ring (projected-ring intrinsics pose circ)]
                           (mapv #(point-to-polyline-px ring %) points)))
                 per (mapv (fn [i view]
                             (let [ds (dists view)]
                               {:i i :n (count ds)
                                :rms-px (Math/sqrt (/ (reduce + 0.0 (map #(* % %) ds))
                                                      (max 1 (count ds))))}))
                           (range) views)
                 all (vec (mapcat dists views))]
             (when (and (every? #(js/isFinite %) (:center circ))
                        (js/isFinite (:radius circ))
                        (pos? (:radius circ)))
               (merge circ
                      (arc-span-deg circ views)
                      {:rms-px (Math/sqrt (/ (reduce + 0.0 (map #(* % %) all))
                                             (max 1 (count all))))
                       :max-residual-px (reduce max 0.0 all)
                       :per-view per
                       :points-3d inliers
                       :seed-n (count inliers)
                       :cloud-n (count cloud)})))))))))

(defn circle-mark
  "The measured circle dressed as an ordinary Ridley pose, the way edge/edge-mark
   dresses a straight edge: origin at the CENTRE, heading along the plane's
   normal, `up` from the caller's hints.

   Heading along the normal — not around the rim — because what one does at a
   measured circle is build ON it: `(turtle A :at :cerchio-1 (extrude (circle r)
   (f 20)))` bores or raises a cylinder through it, and `(f …)` travels the
   heading. It is the same convention a plane mark uses, which is right: a circle
   IS a plane, with a radius attached.

   `toward` is a world point the normal should face (in practice the mean camera
   centre), since a fitted normal's sign is otherwise arbitrary."
  [{:keys [center normal radius]} {:keys [toward up-hints]}]
  (when-let [n0 (unit normal)]
    (let [n (if (and toward (neg? (la/v-dot n0 (la/v-sub toward center))))
              (la/v-scale n0 -1.0)
              n0)
          perp (fn [h] (let [p (la/v-sub h (la/v-scale n (la/v-dot h n)))]
                         (when (> (la/v-norm p) 0.1) (unit p))))
          up (or (some perp up-hints)
                 (let [k (apply min-key #(Math/abs (nth n %)) [0 1 2])]
                   (unit (cross3 n (assoc [0.0 0.0 0.0] k 1.0)))))]
      (when up
        {:position (vec center) :heading n :up up :radius radius}))))

(defn ring-mesh
  "The circle as a world polyline, for drawing it over the photos — the check
   that costs nothing, exactly as the plane mark's disc and the edge's segment:
   navigate the film and it stays on the object's rim, or it is wrong."
  ([circ] (ring-mesh circ ring-samples))
  ([circ n] (circle-points circ n)))
