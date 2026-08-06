(ns ridley.photogrammetry.edge
  "The 3D straight edge that best explains the same physical edge SEEN — not
   pointed at — on several photos (dev-docs/brief-observation-driven-acquire.md,
   gradino 3).

   Why this is not triangulation with extra steps. A plane mark needs the user to
   find THE SAME PHYSICAL POINT from another angle, and on a black glossy moulding
   there is often no such point to find: that gesture is where the mis-clicks of
   the fusion gate came from. An edge asks for nothing of the kind. What is
   declared per photo is a LINE IN THE IMAGE — two clicks roughly along the edge,
   snapped to the real gradient — and any two points along it will do, because a
   line has no landmarks to confuse. Identity of the FEATURE is still the user's
   declaration ('this is the same edge'); identity of the POINT is never asked
   for, and never needed.

   The geometry that makes that work: a line observed in one image, together with
   the camera centre, spans a PLANE in the world (the interpretation plane). The
   physical edge lies in it. Two photos give two planes, and two non-parallel
   planes meet in exactly one line — the edge, in closed form, no search. More
   photos over-determine it, and the extra planes are what turn a plausible line
   into a measured one.

   Degrees of freedom, so the guards are countable rather than felt: a 3D line
   has 4 (a point in the plane ⊥ to it, plus 2 of direction), and every photo
   contributes 2 (a 2D line has 2 DOF). Two photos are therefore EXACT — zero
   residual by construction, carrying no evidence of its own correctness, which
   is why :exact? travels with the answer. Three make the residual mean
   something. Four are the first count at which leave-one-out can name a culprit
   (drop one from three and what remains is exact again, so every removal would
   look like a fix).

   Pure and host-free — poses and pixels in, millimetres out — so it runs in the
   node suite. The gesture that collects the clicks lives in the editor
   (ridley.editor.acquire-stage); the conditioning it reports is computed here."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.lm :as lm]))

(def min-plane-angle-deg
  "Below this angle between the two most-separated interpretation planes, the
   edge is the intersection of two nearly-coincident planes: it is determined the
   way a pencil balanced on its tip is upright, and small click noise swings it
   far. This is the edge's parallax, and it plays exactly the role
   triangulate/min-parallax-deg plays for a point — deliberately permissive (it
   rejects only the truly degenerate), with the residual carrying the finer
   verdict.

   What the number MEANS, exactly (edge_test/the-angle-is-the-turn-around-the-edge
   asserts it): every interpretation plane's normal is perpendicular to the edge
   itself, so the angle between two of them is the angle the two cameras subtend
   AROUND THE EDGE AXIS — folded into [0°, 90°], because half a turn brings you
   back to the same plane from the other side.

   Two things follow, and neither is the obvious one. To pin an edge you must turn
   AROUND it; sliding along its direction buys nothing however far you go. And a
   quarter turn is the best there is — at HALF a turn the two cameras and the
   edge are coplanar again, the two planes coincide, and the edge is free to
   slide anywhere within them while still projecting exactly onto both drawn
   lines. Directly opposite is as blind as side by side."
  8.0)

(def min-segment-px
  "Shorter than this, in pixels, and the two clicks are one click: the image line
   through them is dominated by the few pixels of hand jitter, so its plane is
   noise and the edge it helps determine is noise. 12 px is under half a
   fingertip on a 4032-wide photo — permissive, and enough that the direction
   means something."
  12.0)

(defn- cross3 [[a b c] [d e f]]
  [(- (* b f) (* c e)) (- (* c d) (* a f)) (- (* a e) (* b d))])

(defn- unit [v]
  (let [n (la/v-norm v)]
    (when (> n 1e-12) (la/v-scale v (/ 1.0 n)))))

(defn- perp-basis
  "Two unit vectors spanning the plane ⊥ d. The seed direction's own frame: LM
   moves the line WITHIN it, so the 4 parameters are exactly the 4 degrees of
   freedom and there is no gauge direction for the damping to wander along."
  [d]
  (let [k (apply min-key #(Math/abs (nth d %)) [0 1 2])
        u (unit (cross3 d (assoc [0.0 0.0 0.0] k 1.0)))]
    [u (cross3 d u)]))

(defn segment-length-px [[[u1 v1] [u2 v2]]]
  (Math/hypot (- u2 u1) (- v2 v1)))

(defn- interpretation-plane
  "The world plane spanned by the camera centre and the declared image line:
   normal = the two endpoint rays crossed, since both lie IN it. nil when the
   two clicks are too close to define a direction."
  [k pose [q1 q2]]
  (let [{c :origin d1 :dir} (cam/pixel-ray k pose q1)
        {d2 :dir} (cam/pixel-ray k pose q2)]
    (when-let [n (unit (cross3 d1 d2))]
      {:normal n :center c})))

(defn- closest-params
  "Parameters [t s] of the closest approach between line (p,d) and ray (c,e),
   both directions UNIT. nil when they are parallel (the camera looking straight
   along the edge — the one direction from which an edge cannot be measured)."
  [p d c e]
  (let [w (la/v-sub p c)
        b (la/v-dot d e)
        dd (la/v-dot d w)
        ee (la/v-dot e w)
        den (- 1.0 (* b b))]
    (when (> (Math/abs den) 1e-9)
      [(/ (- (* b ee) dd) den)
       (/ (- ee (* b dd)) den)])))

(defn- line-pixel-distance
  "Perpendicular distance IN PIXELS from `q` to the image of the 3D line (p,d).

   Exact, and it never projects a point: the image of a line is the set of pixels
   whose rays lie in the plane through the camera centre containing it, so with
   that plane's normal m in camera coordinates the image line is
   mx·xn + my·yn + mz = 0 in normalised coordinates — one substitution away from
   pixels. No point of the line is singled out, which is the whole reason this
   works without point identity, and nothing has to be tested for being behind
   the camera: an edge behind the camera images on the far side of the line's
   vanishing point and reads as a large distance, not as a discontinuity.

   nil when the line images as a POINT (the camera looks along it): there the
   observation says nothing, and pretending it says zero would let the solver
   settle into exactly that blind configuration."
  [k pose p d q]
  (let [c (cam/camera-center pose)]
    (when-let [m (unit (cross3 (la/v-sub p c) d))]
      (let [[mx my mz] (la/mat*vec (cam/rodrigues (:rvec pose)) m)
            {:keys [fx fy cx cy]} k
            a (/ mx fx)
            b (/ my fy)
            c0 (- mz (* mx (/ cx fx)) (* my (/ cy fy)))
            n (Math/hypot a b)]
        (when (> n 1e-12)
          (/ (Math/abs (+ (* a (nth q 0)) (* b (nth q 1)) c0)) n))))))

(def ^:private blind-view-penalty
  "What an observation whose line images as a point costs. Constant-length
   residuals are required by lm/solve, so a vanished measurement is reported as
   a large number rather than dropped."
  1000.0)

(defn- max-pairwise-angle-deg
  "The LARGEST angle between any two interpretation-plane normals — the effective
   conditioning of the intersection. Max, not min, for the same reason parallax
   is: two views that nearly coincide still leave a well-determined edge if a
   third comes from elsewhere."
  [normals]
  (reduce max 0.0
          (for [i (range (count normals))
                j (range (inc i) (count normals))]
            (let [c (Math/abs (max -1.0 (min 1.0 (la/v-dot (nth normals i) (nth normals j)))))]
              (* (/ 180.0 Math/PI) (Math/acos c))))))

(defn- seed-line
  "Closed-form starting line from the best-conditioned PAIR of planes: direction
   = their normals crossed, position = the point of their intersection line
   closest to where the two mid-rays pass each other (i.e. near the object,
   rather than a kilometre along the same correct line — LM is scale-blind but
   the extents and the reported numbers are not)."
  [planes mids]
  (let [n (count planes)
        pairs (for [i (range n) j (range (inc i) n)] [i j])
        [i j] (apply min-key
                     (fn [[i j]] (Math/abs (la/v-dot (:normal (nth planes i))
                                                     (:normal (nth planes j)))))
                     pairs)
        ni (:normal (nth planes i))
        nj (:normal (nth planes j))]
    (when-let [d (unit (cross3 ni nj))]
      (let [ri (nth mids i)
            rj (nth mids j)
            near (if-let [[ti _] (closest-params (:origin ri) (:dir ri)
                                                 (:origin rj) (:dir rj))]
                   (la/v-add (:origin ri) (la/v-scale (:dir ri) ti))
                   (:center (nth planes i)))
            ;; the point of the intersection line nearest `near`: two Lagrange
            ;; multipliers against the 2x2 Gram matrix of the normals, which is
            ;; invertible exactly when the planes are not parallel — and they are
            ;; not, or `d` would have been nil.
            g (la/v-dot ni nj)
            det (- 1.0 (* g g))
            r1 (- (la/v-dot ni (:center (nth planes i))) (la/v-dot ni near))
            r2 (- (la/v-dot nj (:center (nth planes j))) (la/v-dot nj near))
            l1 (/ (- r1 (* g r2)) det)
            l2 (/ (- r2 (* g r1)) det)]
        {:point (la/v-add near (la/v-add (la/v-scale ni l1) (la/v-scale nj l2)))
         :dir d}))))

(defn- residual-fn [observations k-of sigma-px {p0 :point d0 :dir} [u v]]
  (fn [[a b alpha beta]]
    (let [p (la/v-add p0 (la/v-add (la/v-scale u a) (la/v-scale v b)))
          d (or (unit (la/v-add d0 (la/v-add (la/v-scale u alpha) (la/v-scale v beta))))
                d0)]
      (vec (mapcat (fn [o]
                     (map (fn [q]
                            (if-let [r (line-pixel-distance (k-of o) (:pose o) p d q)]
                              (/ r sigma-px)
                              blind-view-penalty))
                          (:seg o)))
                   observations)))))

(defn- line-of [{p0 :point d0 :dir} [u v] [a b alpha beta]]
  {:point (la/v-add p0 (la/v-add (la/v-scale u a) (la/v-scale v b)))
   :dir (or (unit (la/v-add d0 (la/v-add (la/v-scale u alpha) (la/v-scale v beta)))) d0)})

(defn- span-of
  "Where along the line this photo actually SAW the edge: each declared endpoint's
   ray is met with the line, giving the two parameters (millimetres from the
   line's origin) that bracket what was declared. Returned IN THE ORDER THE USER
   DREW THEM, which is what orients the finished edge; nil when a ray and the
   line are parallel.

   This is what makes the answer a SEGMENT rather than an infinite line, and it
   is also a measurement in its own right: two photos whose spans barely overlap
   are looking at different stretches of the same edge, which is fine for fitting
   and a warning when the ends are supposed to be the object's corners."
  [{:keys [point dir]} k pose seg]
  (let [ts (keep (fn [q]
                   (let [ray (cam/pixel-ray k pose q)]
                     (first (closest-params point dir (:origin ray) (:dir ray)))))
                 seg)]
    (when (= 2 (count ts)) (vec ts))))

(defn- metrics
  "Per-photo and overall reprojection, in pixels — the same currency PnP and
   triangulation report, so a bad edge reads at a glance."
  [line observations k-of]
  (let [per (mapv (fn [i o]
                    (let [ds (mapv #(line-pixel-distance (k-of o) (:pose o)
                                                         (:point line) (:dir line) %)
                                   (:seg o))
                          ok (keep identity ds)]
                      {:i i
                       :rms-px (when (seq ok)
                                 (Math/sqrt (/ (reduce + 0.0 (map #(* % %) ok)) (count ok))))
                       :max-px (when (seq ok) (reduce max ok))}))
                  (range) observations)
        all (mapcat (fn [o]
                      (keep #(line-pixel-distance (k-of o) (:pose o)
                                                  (:point line) (:dir line) %)
                            (:seg o)))
                    observations)]
    {:per-obs per
     :rms-px (if (seq all)
               (Math/sqrt (/ (reduce + 0.0 (map #(* % %) all)) (count all)))
               blind-view-penalty)
     :max-residual-px (if (seq all) (reduce max all) blind-view-penalty)}))

(defn- solve-line
  "Seed + LM, without the outlier search. Returns the refined line or nil."
  [observations k-of sigma-px max-iterations]
  (let [planes (mapv #(interpretation-plane (k-of %) (:pose %) (:seg %)) observations)
        mids (mapv (fn [o]
                     (let [[[u1 v1] [u2 v2]] (:seg o)]
                       (cam/pixel-ray (k-of o) (:pose o)
                                      [(* 0.5 (+ u1 u2)) (* 0.5 (+ v1 v2))])))
                   observations)]
    (when (every? some? planes)
      (when-let [seed (seed-line planes mids)]
        (let [basis (perp-basis (:dir seed))
              res (lm/solve (residual-fn observations k-of sigma-px seed basis)
                            [0.0 0.0 0.0 0.0]
                            {:max-iterations max-iterations})
              line (line-of seed basis (:params res))]
          (when (every? #(js/isFinite %) (concat (:point line) (:dir line)))
            (assoc line :planes (mapv :normal planes))))))))

(def ^:private outlier-improvement
  "A leave-one-out fit must bring the rms down to this fraction of the full fit's
   before that photo is named the culprit — otherwise a merely noisy set gets an
   innocent photo blamed every time. Same threshold, same reason, as
   triangulate/worst-observation."
  0.25)

(defn- worst-observation
  "WHICH declared line is wrong, when one of them is — by dropping each in turn
   and seeing which removal makes the rest agree. The per-photo residuals do NOT
   answer this: least squares tilts the line until the error is spread over
   everybody, so the photo that looks worst is routinely an innocent one.

   Needs FOUR observations, not three: a line has 4 degrees of freedom and each
   photo gives 2, so three minus one is exact — every removal would drive the rms
   to zero and the first one tried would take the blame."
  [observations k-of sigma-px full-rms]
  (when (and (>= (count observations) 4) (pos? full-rms))
    (let [trials (keep (fn [i]
                         (let [rest-obs (into (subvec observations 0 i)
                                              (subvec observations (inc i)))]
                           (when-let [l (solve-line rest-obs k-of sigma-px 60)]
                             [i (:rms-px (metrics l rest-obs k-of))])))
                       (range (count observations)))]
      (when (seq trials)
        (let [[i rms] (apply min-key second trials)]
          (when (< rms (* outlier-improvement full-rms)) i))))))

(defn triangulate-edge
  "The world line that best explains the same physical edge declared on several
   photos.

   `observations` is [{:pose {:rvec :t} :seg [[u1 v1] [u2 v2]]} …] (≥2), each
   :seg the edge's line AS DRAWN ON THAT PHOTO — two points anywhere along it,
   in any order, from either end. They are NOT required to be the same physical
   points across photos, and nothing in here ever pairs them up. `intrinsics` is
   the shared pinhole {:fx :fy :cx :cy}, which an observation may override with
   its own :intrinsics (a session is not obliged to have shot every photo at the
   same pixel size).

   Returns nil for <2 observations, a segment too short to define a direction, or
   a degenerate system. Otherwise:

     {:point, :dir    — a point on the line and its UNIT direction
      :a, :b          — the ends of the stretch that was actually declared, i.e.
                        the union of what the photos saw (see span-of)
      :length-mm      — |b − a|
      :per-obs        — [{:i :rms-px :max-px :span [t0 t1]} …]
      :rms-px, :max-residual-px  — over every declared endpoint, in pixels
      :angle-deg      — the largest angle two photos subtend AROUND the edge:
                        its parallax (compare min-plane-angle-deg)
      :exact?         — true with exactly two photos, where the fit has no
                        redundancy and the zero residual proves nothing
      :worst-obs      — index of the mis-drawn photo, or nil (needs ≥4)}"
  ([intrinsics observations] (triangulate-edge intrinsics observations {}))
  ([intrinsics observations {:keys [sigma-px max-iterations]
                             :or {sigma-px 1.0 max-iterations 120}}]
   (let [observations (vec observations)
         k-of (fn [o] (or (:intrinsics o) intrinsics))]
     (when (and (>= (count observations) 2)
                (every? #(and (= 2 (count (:seg %)))
                              (>= (segment-length-px (:seg %)) min-segment-px))
                        observations))
       (when-let [line (solve-line observations k-of sigma-px max-iterations)]
         (let [m (metrics line observations k-of)
               raw (mapv #(span-of line (k-of %) (:pose %) (:seg %)) observations)]
           (when-let [lead (first (keep identity raw))]
             ;; WHICH WAY the edge runs. The intersection of two planes has no
             ;; preferred direction — the cross product's sign is an accident of
             ;; which photo happened to be listed first — but the finished edge
             ;; does: it becomes a mark whose heading is what `(f …)` travels
             ;; along. Take the direction from the FIRST declaration, first click
             ;; to second: the edge then runs the way it was drawn, which is the
             ;; only convention the user can predict without being told.
             (let [flip? (> (first lead) (second lead))
                   sgn (if flip? -1.0 1.0)
                   dir (la/v-scale (:dir line) sgn)
                   spans (mapv (fn [s] (when s (let [[x y] (mapv #(* sgn %) s)]
                                                 [(min x y) (max x y)])))
                               raw)
                   ts (mapcat identity (keep identity spans))
                   t0 (reduce min ts)
                   t1 (reduce max ts)
                   a (la/v-add (:point line) (la/v-scale dir t0))
                   b (la/v-add (:point line) (la/v-scale dir t1))]
               {:point (:point line)
                :dir dir
                :a a
                :b b
                :length-mm (la/v-norm (la/v-sub b a))
                :per-obs (mapv #(assoc %1 :span %2) (:per-obs m) spans)
                :rms-px (:rms-px m)
                :max-residual-px (:max-residual-px m)
                :angle-deg (max-pairwise-angle-deg (:planes line))
                :exact? (= 2 (count observations))
                :worst-obs (worst-observation observations k-of sigma-px (:rms-px m))}))))))))

(defn edge-mark
  "The measured edge dressed as an ordinary Ridley pose, so it needs no new DSL
   downstream: origin at `a`, heading ALONG the edge toward `b`, `up` from the
   caller's hints.

   Heading along the edge — not a surface normal, as a plane mark's is — because
   what one wants to do at an edge is RUN it: `(turtle e (extrude (circle 2)
   (f (:length e))))` lays a fillet down its whole length, and `(f …)` travels
   along the heading by the house convention.

   `up-hints` are world directions tried in order and projected perpendicular to
   the edge; one within ~6° of the edge itself is skipped, its projection being
   noise. All exhausted → any perpendicular. Returns nil for a degenerate pair."
  [a b up-hints]
  (when-let [d (unit (la/v-sub b a))]
    (let [perp (fn [h] (let [p (la/v-sub h (la/v-scale d (la/v-dot h d)))]
                         (when (> (la/v-norm p) 0.1) (unit p))))
          up (or (some perp up-hints) (first (perp-basis d)))]
      (when up
        {:position (vec a) :heading d :up up}))))
