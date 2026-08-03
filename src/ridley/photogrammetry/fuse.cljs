(ns ridley.photogrammetry.fuse
  "Fusing two shooting sessions of the SAME object into one frame.

   The turntable gives a narrow BAND of angles: no view from above, none from
   below. The cure is to shoot the object again in a different resting pose and
   put the two sessions in one space (dev-docs/brief-session-fusion.md).

   The whole problem is ONE rigid motion per session, because the object is
   rigid and both sessions are in millimetres (the plate sets the scale). What
   ties them is the user's own declaration: marks with the SAME NAME in both
   sessions are the same physical point. No feature matching, no texture — the
   same philosophy that killed the Klein twin.

   Pure: no DOM, no stage, no source. The caller (editor.edit-acquire) supplies
   two mark maps and gets back the motion plus the numbers to judge it by."
  (:require [ridley.math :as m]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.linalg :as la]
            [ridley.photogrammetry.lm :as lm]))

;; ------------------------------------------------------------
;; Applying a rigid motion
;; ------------------------------------------------------------

(defn transform-point [{:keys [R t]} p]
  (la/v-add (la/mat*vec R (vec p)) t))

(defn transform-dir [{:keys [R]} d]
  (m/normalize (la/mat*vec R (vec d))))

(defn transform-pose
  "Carry a pose through the motion. Position moves, directions only rotate —
   which is why a mark transported into the other session's frame is still a
   usable mark and not just a point."
  [rt {:keys [position heading up] :as pose}]
  (cond-> pose
    position (assoc :position (transform-point rt position))
    heading  (assoc :heading (transform-dir rt heading))
    up       (assoc :up (transform-dir rt up))))

;; ------------------------------------------------------------
;; The seed: a closed-form frame-to-frame rotation
;; ------------------------------------------------------------
;;
;; The house pattern is closed-form seed + LM refine (PnP, the turntable fit,
;; the plate). Kabsch would want an SVD; here the rotation is built by composing
;; orthonormal triads out of the data, which needs nothing but cross products.

(defn- triad
  "Right-handed orthonormal triad (as a matrix whose COLUMNS are the axes) from
   a primary direction and a hint. nil when the two are parallel — there is no
   frame to build, and saying so is the point."
  [primary hint]
  (let [u1 (m/normalize primary)
        proj (m/v* u1 (m/dot hint u1))
        rest- (m/v- hint proj)]
    (when (> (la/v-norm rest-) 1e-6)
      (let [u2 (m/normalize rest-)
            u3 (m/cross u1 u2)]
        ;; columns u1 u2 u3
        [[(nth u1 0) (nth u2 0) (nth u3 0)]
         [(nth u1 1) (nth u2 1) (nth u3 1)]
         [(nth u1 2) (nth u2 2) (nth u3 2)]]))))

(defn- centroid [ps]
  (let [n (max 1 (count ps))]
    (m/v* (reduce m/v+ [0.0 0.0 0.0] ps) (/ 1.0 n))))

(defn- seed-rt
  "Closed-form (R, t) taking the `from` anchors onto the `to` anchors.

   With three or more anchors the origins alone give the frame. With exactly
   two, the baseline gives one direction and the first anchor's NORMAL gives the
   second — which is why two plane marks suffice where two bare points would
   not. Returns nil when the geometry does not determine a frame."
  [anchors]
  (let [pf (mapv :from-pos anchors)
        pt (mapv :to-pos anchors)
        base-f (m/v- (nth pf 1) (nth pf 0))
        base-t (m/v- (nth pt 1) (nth pt 0))
        [hint-f hint-t] (if (>= (count anchors) 3)
                          [(m/v- (nth pf 2) (nth pf 0)) (m/v- (nth pt 2) (nth pt 0))]
                          [(:from-dir (first anchors)) (:to-dir (first anchors))])
        ;; two anchors and no normals: nothing supplies the second direction,
        ;; and the rotation about the baseline stays free. Same answer as
        ;; "they are collinear" — no frame, say so.
        Ff (when hint-f (triad base-f hint-f))
        Ft (when hint-t (triad base-t hint-t))]
    (when (and Ff Ft)
      (let [R (la/mat*mat Ft (la/transpose Ff))
            t (la/v-sub (centroid pt) (la/mat*vec R (centroid pf)))]
        {:R R :t t}))))

;; ------------------------------------------------------------
;; The refinement
;; ------------------------------------------------------------

(def ^:private normal-arm-mm
  "Lever arm that turns a normal mismatch into millimetres, so directions and
   positions can be summed in one chi-square. 5 mm means 1° of normal error
   weighs about as much as 0.09 mm of position error: the normals nudge the fit,
   the origins decide it. `up` stays OUT of the cost entirely — it is projected
   from the object's own pose, which differs BY CONSTRUCTION between sessions."
  5.0)

(defn- rt-of-params [p]
  {:R (cam/rodrigues [(nth p 0) (nth p 1) (nth p 2)])
   :t [(nth p 3) (nth p 4) (nth p 5)]})

(defn- residual-fn [anchors sigma-mm]
  (fn [p]
    (let [rt (rt-of-params p)]
      (vec (mapcat (fn [{:keys [from-pos to-pos from-dir to-dir]}]
                     (let [dp (la/v-sub (transform-point rt from-pos) to-pos)
                           dn (if (and from-dir to-dir)
                                (la/v-sub (la/v-scale (transform-dir rt from-dir) normal-arm-mm)
                                          (la/v-scale (m/normalize to-dir) normal-arm-mm))
                                [0.0 0.0 0.0])]
                       (mapv #(/ % sigma-mm) (concat dp dn))))
                   anchors)))))

(defn- per-anchor
  "What each anchor costs after the fit: how far its transported origin lands
   from its twin (mm) and how far its normal is turned (degrees). This is the
   table that lets a wrong click be found rather than averaged into the answer."
  [rt anchors]
  (mapv (fn [{:keys [name from-pos to-pos from-dir to-dir]}]
          (let [d (la/v-norm (la/v-sub (transform-point rt from-pos) to-pos))
                ang (when (and from-dir to-dir)
                      (let [c (max -1.0 (min 1.0 (m/dot (transform-dir rt from-dir)
                                                        (m/normalize to-dir))))]
                        (* (/ 180.0 Math/PI) (Math/acos c))))]
            {:name name :residual-mm d :normal-deg ang}))
        anchors))

(def min-baseline-mm
  "Anchors closer together than this do not span the object: the rotation they
   determine is as noisy as the clicks, amplified by the ratio of the object's
   size to the baseline. Refusing is better than returning a pose that looks
   fitted."
  5.0)

(defn fit-rigid
  "The rigid motion carrying `anchors`' :from poses onto their :to poses.

   Each anchor is {:name :from-pos :to-pos :from-dir :to-dir} — the dirs
   optional, and used only as a weak pull (see normal-arm-mm).

   Returns {:R :t :rvec :rms-mm :max-mm :per-anchor :n} or
   {:error <human sentence>}: under-determined or degenerate input gets an
   honest nil, never a plausible-looking motion."
  ([anchors] (fit-rigid anchors {}))
  ([anchors {:keys [sigma-mm] :or {sigma-mm 0.2}}]
   (cond
     (< (count anchors) 2)
     {:error (str "servono almeno DUE mark con lo stesso nome nelle due sessioni "
                  "(ne ho trovato " (count anchors) "): uno solo fissa il punto ma non l'orientamento")}

     (< (la/v-norm (la/v-sub (:from-pos (second anchors)) (:from-pos (first anchors))))
        min-baseline-mm)
     {:error (str "i mark di aggancio sono troppo vicini fra loro (meno di "
                  min-baseline-mm " mm): la rotazione che determinano è rumore. "
                  "Prendine due lontani, agli estremi dell'oggetto")}

     :else
     (if-let [seed (seed-rt anchors)]
       (let [p0 (vec (concat (cam/rot-mat->rodrigues (:R seed)) (:t seed)))
             res (lm/solve (residual-fn anchors sigma-mm) p0 {:max-iterations 120})
             rt (rt-of-params (:params res))
             pa (per-anchor rt anchors)
             ds (mapv :residual-mm pa)]
         (assoc rt
                :rvec (vec (take 3 (:params res)))
                :n (count anchors)
                :per-anchor pa
                :rms-mm (Math/sqrt (/ (reduce + 0.0 (map #(* % %) ds)) (count ds)))
                :max-mm (reduce max 0.0 ds)))
       {:error (str "i mark di aggancio sono allineati (o le loro normali sono parallele "
                    "alla congiungente): non determinano la rotazione attorno a quella retta. "
                    "Serve un terzo mark fuori da quella linea")}))))

(defn mean-pose
  "The average of poses that are supposed to BE the same pose — the two views
   an anchor mark has of itself, one per session, once they are in one frame.

   Collapsing them is the honest representation: after the fit they ARE one
   point, and keeping two would invite code to pick the wrong one. `up` is
   re-orthogonalised against the averaged heading, so the result is a frame and
   not merely three averaged vectors."
  [poses]
  (let [ps (remove nil? poses)]
    (when (seq ps)
      (let [avg (fn [k] (m/v* (reduce m/v+ [0.0 0.0 0.0] (map #(vec (get % k)) ps))
                              (/ 1.0 (count ps))))
            h (m/normalize (avg :heading))
            u0 (avg :up)
            u (m/v- u0 (m/v* h (m/dot u0 h)))]
        (assoc (first ps)
               :position (avg :position)
               :heading h
               :up (if (> (la/v-norm u) 1e-9) (m/normalize u) (:up (first ps))))))))

(defn worst-anchor
  "The anchor whose residual stands out from the others — a candidate for a
   misnamed or mis-clicked twin — or nil when they all cost about the same.
   Leave-one-out is not needed to point the finger when one residual is several
   times the median; it would be, to prove it."
  [per-anchor-rows]
  (when (>= (count per-anchor-rows) 3)
    (let [ds (sort (mapv :residual-mm per-anchor-rows))
          med (nth ds (quot (count ds) 2))
          worst (apply max-key :residual-mm per-anchor-rows)]
      (when (> (:residual-mm worst) (max 0.5 (* 3.0 med)))
        worst))))
