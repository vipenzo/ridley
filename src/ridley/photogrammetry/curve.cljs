(ns ridley.photogrammetry.curve
  "3D points from a curved edge declared on several photos — and the PLANE they
   lie in (dev-docs/brief-observation-driven-acquire.md, gradino 3).

   The plane is the point of this namespace, and it comes from Vincenzo, who had
   used the circle fit on his own parts and found the target wrong (2026-08-06):
   «la curva da identificare non è mai un cerchio, al massimo un segmento. Credo
   che potremmo usare le linee curve, non tanto per identificare cerchi, quanto
   per identificare piani. Quelle su cui sto cliccando sono tutte curve adagiate
   su un piano.»

   He is right, and the geometry agrees on every count:

   - a plane has 3 degrees of freedom where a circle has 6, so the same recovered
     points determine it far better;
   - a plane asks the curve to be nothing in particular. A circle fit only works
     on a curve that IS a circle, and then only if enough of the turn was seen;
     any planar curve at all — an arc, a moulding's outline, a rounded corner —
     gives a plane;
   - and the plane is what the rest of Ridley already knows how to use. It comes
     out as an ordinary plane mark, so `(turtle A :at :zona (edit-path-2d …))`
     and acquire-union's anchors work with no new DSL.

   What it replaces is the gesture that costs the most: the plane mark built from
   three points, each clicked on two photos, each requiring the user to FIND THE
   SAME PHYSICAL POINT from another angle. That is six fallible clicks and the
   place the fusion gate's mirrored marks came from. A curve is two clicks and
   asks for no correspondence at all — because the program pairs the rays itself.

   Pure: poses and pixels in, millimetres out."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.triangulate :as tri]))

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
   were real."
  0.5)

(def min-width-mm
  "How wide the recovered points must be ACROSS their own plane before that plane
   is worth having.

   This is the one way a curve can fail to give a plane, and it is not obvious
   from looking at the photo: points strung along a nearly straight line lie on
   infinitely many planes, all of them containing that line, and the fit picks
   one of them for reasons that have nothing to do with the object. A gentle arc
   is exactly that case — which is why the answer to 'this curve is too straight'
   is a SECOND curve on the same face, not a better fit.

   4 mm on a part measured from a quarter of a metre away: below it, one
   millimetre of error on a recovered point swings the normal by more than 14°
   (tri/fit-plane-mark reports that swing per millimetre, and it is the number to
   read rather than this threshold)."
  4.0)

(def min-elevation-deg
  "How high above the fitted plane the lowest contributing camera must sit.

   This is the guard against the one wrong answer that LOOKS right, and it was
   found on real photographs (2026-08-06): a plane came back neatly fitted,
   0.53 mm of flatness, 26 points, nothing dropped — and standing vertical where
   the true one is horizontal. What had happened is that the two photos had
   declared stretches of rim that did not overlap, so every ray meeting was a
   ghost; and ghosts are not scattered, they pile up on the plane that CONTAINS
   BOTH CAMERA CENTRES, which is where nearly-coplanar rays cross.

   That plane refutes itself, and the argument is what makes this a principle and
   not a patch: a plane containing the camera centres is seen EDGE-ON from them,
   so a curve lying in it would image as a straight line — and a straight line is
   not what was declared. Whatever else the fit is, it is not the plane of the
   curve the user pointed at.

   15° also catches the honest version of the same trouble: a real face measured
   from photographs that graze it is measured badly, for the same geometric
   reason the stage already warns about grazing shots."
  15.0)

(def min-agreement
  "What fraction of a photo's declared points must find an ORDER-RESPECTING
   partner in the other photo.

   This is what separates a real correspondence from a coincidence, and it took
   two wrong answers to arrive at. The walk returns its points ORDERED along the
   curve, and a true correspondence preserves that order: walk the curve one way
   in photo A and you walk it one way in photo B too. Ghost meetings do not —
   they pair A's third point with B's thirtieth and A's fourth with B's tenth,
   because they are crossings of rays that have nothing to do with each other.

   Two cheaper tests were tried first and BOTH passed the wrong answer, which is
   why the code carries this one instead:

   - the cameras' elevation above the fitted plane. Real on the plate (where the
     ghosts piled onto the plane containing both camera centres), useless in
     general — in the synthetic non-overlapping case the ghost plane sat 47°
     below the cameras and sailed through.
   - whether the two photos' own reconstructions of the curve agree ON that
     plane. Circular, and it took a measurement to see it: the ghosts ARE the
     ray crossings, so any plane through them makes both reconstructions pass
     through those very points. It reported 93% agreement for a plane 88° wrong.

   Order is not circular, because it is a property of the MATCHING and not of the
   plane fitted to it.

   Where the number comes from. A pairing that is real is monotone almost
   entirely — measured, 100% on clean synthetic curves — because the points that
   had no partner were already dropped by the distance test rather than paired
   badly. A pairing that is coincidence is a random permutation, and the longest
   monotone run of a random permutation of n is about 2√n, i.e. half of a set of
   twenty-six — measured, 51%. Three quarters sits in that gap with room on both
   sides, and it is still the weakest guard in the chain: the disc drawn in the
   world, checked across the photos, is the one that catches what arithmetic
   cannot."
  0.75)

(def ^:private plane-outlier-mm
  "A recovered point further than this from the plane most of them agree on did
   not come from the curve — it is a ray pairing that happened to pass close."
  1.5)

(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])

(defn- unit [v]
  (let [n (la/v-norm v)]
    (when (> n 1e-12) (la/v-scale v (/ 1.0 n)))))

(defn ray-closest
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

(defn subsample
  "At most `n` points, evenly spaced along the list. The walk lays a station every
   couple of pixels, so a curve arrives hundreds of points long and almost all of
   them say the same thing; thinning is what keeps the fit interactive."
  [pts n]
  (let [c (count pts)]
    (if (<= c n)
      (vec pts)
      (mapv #(nth pts (Math/round (* (/ (double %) (dec n)) (dec c)))) (range n)))))

(defn rays-of [k pose pts]
  (mapv #(cam/pixel-ray k pose %) pts))

(defn- longest-monotone
  "Indices of the longest subsequence of `xs` that runs one way — increasing or
   decreasing, whichever is longer, since walking the curve from either end is
   equally valid. O(n²), and n is forty.

   This is the whole ghost filter: a real correspondence between two orderings of
   the same curve is monotone, and a set of coincidental ray crossings is not."
  [xs]
  (let [n (count xs)
        run (fn [ok?]
              (let [len (long-array n 1)
                    prev (long-array n -1)]
                (dotimes [i n]
                  (dotimes [j i]
                    (when (and (ok? (nth xs j) (nth xs i))
                               (> (inc (aget len j)) (aget len i)))
                      (aset len i (inc (aget len j)))
                      (aset prev i j))))
                (let [best (reduce (fn [b i] (if (> (aget len i) (aget len b)) i b))
                                   0 (range n))]
                  (loop [i best acc ()]
                    (if (neg? i) (vec acc) (recur (aget prev i) (conj acc i)))))))]
    (if (zero? n)
      []
      (let [up (run <) down (run >)]
        (if (>= (count up) (count down)) up down)))))

(defn- meet-points
  "3D points where rays from view A meet rays from view B — brute force, closed
   form, a few thousand closest approaches, which is nothing.

   ONE meeting per ray of A, the closest, and only if it passes within
   min-match-mm. Each declared point of A is the image of exactly one 3D point,
   so one is the physically correct number — and the discipline matters: without
   it a single ray of poor parallax pairs acceptably with dozens of B's and
   floods the cloud with its own ghosts.

   Which B it paired with is kept, not just where they met, because that INDEX is
   what the order test reads: a real correspondence between two walks of the same
   curve is monotone, and ghosts are not."
  [rays-a rays-b]
  (into []
        (keep-indexed
         (fn [i ra]
           (let [best (reduce (fn [best [j rb]]
                                (if-let [[p d] (ray-closest ra rb)]
                                  (if (or (nil? best) (< d (second best))) [p d j] best)
                                  best))
                              nil
                              (map-indexed vector rays-b))]
             (when (and best (< (second best) min-match-mm))
               {:point (first best) :i i :j (nth best 2)}))))
        rays-a))

(defn- monotone-only
  "Keep the meetings whose pairing runs one way along both curves, and say what
   fraction survived. `matches` are meet-points' records, already in A's order.

   Returns [points fraction]."
  [matches]
  (if (empty? matches)
    [[] 0.0]
    (let [keep-idx (set (longest-monotone (mapv :j matches)))]
      [(mapv :point (keep-indexed (fn [i m] (when (keep-idx i) m)) matches))
       (/ (double (count keep-idx)) (count matches))])))

(defn curve-points
  "The 3D points of ONE declared curve, and how much of the matching RESPECTED
   THE ORDER of the two walks: {:points [[x y z] …] :monotone <fraction>}.

   `views` is [{:pose :intrinsics :points [[u v] …]} …] for that curve, at least
   two; every PAIR of views contributes its meetings, so a third photo adds
   evidence rather than merely confirming.

   Curves are kept apart from one another on purpose (see plane-from-curves):
   pairing a ray of one curve with a ray of another produces meetings that are
   geometrically real and physically meaningless.

   The order fraction is the honest measure of whether these points are a
   correspondence or a coincidence — see min-agreement, and the two cheaper tests
   it records as having failed."
  ([views] (curve-points views 40))
  ([views max-points]
   (let [views (mapv #(update % :points (fn [p] (subsample p max-points))) (vec views))]
     (when (and (>= (count views) 2) (every? #(>= (count (:points %)) 3) views))
       (let [rays (mapv #(rays-of (:intrinsics %) (:pose %) (:points %)) views)
             per-pair (for [i (range (count views))
                            j (range (inc i) (count views))]
                        (monotone-only (meet-points (nth rays i) (nth rays j))))
             kept (vec (mapcat first per-pair))
             fracs (keep (fn [[pts f]] (when (seq pts) f)) per-pair)]
         {:points kept
          :monotone (if (seq fracs) (/ (reduce + 0.0 fracs) (count fracs)) 0.0)})))))

(defn- prng
  "Deterministic LCG — a RANSAC that answers differently each run is not
   testable, and a measurement that moves when nothing changed is not
   trustworthy."
  [seed]
  (let [s (atom (bit-and (bit-or (int seed) 1) 0x7fffffff))]
    (fn [] (/ (unsigned-bit-shift-right
               (swap! s #(bit-and (+ (* % 1103515245) 12345) 0x7fffffff)) 0)
              2147483648.0))))

(defn- plane-through-3
  "Plane [point normal] through three points, or nil when they are collinear."
  [p q r]
  (when-let [n (unit (cross3 (la/v-sub q p) (la/v-sub r p)))]
    [p n]))

(defn- ransac-plane
  "The plane most of the recovered points agree on, by trying triples — the
   ghosts do not lie on it, because a ghost is the meeting of two rays that each
   touch the true plane at ONE point, and their crossing is somewhere off it.

   Ties on inlier count are broken by tightness. Returns the inlier points."
  [pts tol iters seed]
  (let [n (count pts)
        r (prng seed)
        pick (fn [] (min (dec n) (int (* (r) n))))]
    (if (< n 6)
      (vec pts)
      (loop [i 0, best [], best-score 1e9]
        (if (>= i iters)
          (if (>= (count best) 3) best (vec pts))
          (let [a (pick) b (pick) c (pick)]
            (if (or (= a b) (= b c) (= a c))
              (recur (inc i) best best-score)
              (if-let [[o nn] (plane-through-3 (nth pts a) (nth pts b) (nth pts c))]
                (let [d (fn [p] (Math/abs (la/v-dot (la/v-sub p o) nn)))
                      in (filterv #(< (d %) tol) pts)
                      s (/ (reduce + 0.0 (map d in)) (max 1 (count in)))]
                  (if (or (> (count in) (count best))
                          (and (= (count in) (count best)) (< s best-score)))
                    (recur (inc i) in s)
                    (recur (inc i) best best-score)))
                (recur (inc i) best best-score)))))))))

(defn plane-from-curves
  "The PLANE that the declared curves lie in, as an ordinary Ridley MARK.

   `curves` is a sequence of curve declarations, each itself
   [{:pose :intrinsics :points [[u v] …]} …] over ≥2 photos. More than one curve
   is allowed and is the answer to the commonest failure: a single gentle arc is
   nearly straight, and a nearly straight set of points lies in infinitely many
   planes. Two curves on the same face — or one curve and one straight edge's
   worth of points — pin it properly. The curves are pooled only AFTER each has
   been turned into 3D points, never before: pairing a ray of one curve with a
   ray of another gives meetings that are real geometry and meaningless
   measurement.

   `opts` takes :toward (a world point the normal must face, in practice the mean
   camera centre) and :up-hints, exactly as triangulate/fit-plane-mark, which is
   what actually builds the mark — so the result carries the same numbers the
   plane-mark gesture already reports, and means the same things:

     {:position :heading :up            — an ordinary mark
      :flatness-mm :per-point :exact?   — is the zone really flat
      :width-mm :tilt-per-mm-deg        — how well the normal is pinned
      :points                           — the recovered 3D points, the evidence
      :n :dropped                       — how many survived the robust fit
      :min-elevation-deg}               — how high the LOWEST contributing camera
                                          sits above the plane it found

   Returns nil when there is nothing to fit. The CALLER judges two numbers, and
   they refuse different things: :width-mm against min-width-mm (below it the
   answer is 'declare another curve', not 'trust this plane'), and
   :min-elevation-deg against min-elevation-deg (below THAT the plane is not the
   curve's at all — see that constant)."
  ([curves] (plane-from-curves curves {}))
  ([curves {:keys [toward up-hints max-points ransac-iters seed]
            :or {max-points 40 ransac-iters 200 seed 7}}]
   (let [clouds (keep #(curve-points % max-points) curves)
         pts (vec (mapcat :points clouds))
         mono (if (seq clouds)
                (reduce min (map :monotone clouds))
                0.0)]
     (when (>= (count pts) 3)
       (let [inliers (ransac-plane pts plane-outlier-mm ransac-iters seed)
             inliers (if (>= (count inliers) 3) inliers pts)]
         (when-let [mark (tri/fit-plane-mark inliers {:toward toward :up-hints up-hints})]
           ;; :width-mm and :tilt-per-mm-deg come from fit-plane-mark itself — it
           ;; already measures the narrowest in-plane slab, which is exactly the
           ;; conditioning number a curve-fitted plane lives or dies by.
           (let [centres (into [] (comp (mapcat identity) (map #(cam/camera-center (:pose %))))
                               curves)
                 elev (fn [ctr]
                        (let [d (la/v-sub ctr (:position mark))
                              len (la/v-norm d)]
                          (if (< len 1e-9)
                            0.0
                            (* (/ 180.0 Math/PI)
                               (Math/asin (min 1.0 (Math/abs
                                                    (/ (la/v-dot d (:heading mark)) len))))))))]
             (assoc mark
                    :points inliers
                    :n (count inliers)
                    :dropped (- (count pts) (count inliers))
                    :agreement mono
                    :min-elevation-deg (if (seq centres)
                                         (reduce min (map elev centres))
                                         0.0)))))))))
