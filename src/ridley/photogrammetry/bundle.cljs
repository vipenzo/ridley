(ns ridley.photogrammetry.bundle
  "One focal for the whole session, and every camera pose refined together.

   Until now each photo was registered ALONE: its six degrees of freedom against
   its own dozen clicked marks, with the focal handed to it from the outside (the
   EXIF tag, or the slider). That is the best a single photo can do, and it is
   less than the session can do, for two reasons.

   The focal is ONE number for the whole session — the lens does not change
   between shots — but read from EXIF it is an external claim, quantised to a
   whole millimetre, and a 2% error in it does not announce itself: a single
   photo absorbs it almost entirely into its own distance from the object, so
   every per-photo residual still looks fine while every pose is wrong in depth.
   Fitted from the pictures instead, it has to satisfy all of them at once, and
   there it has nowhere to hide.

   And the noise averages. Five photos of the plate are 5 x 12 observations
   against 31 unknowns instead of 24 against 6, so what is random in the clicking
   cancels rather than accumulating into each pose separately. Depth is the
   direction a lone photo pins worst, and depth is what this recovers
   (dev-docs/brief-session-fusion.md: the 2-3 mm Vincenzo still saw after the
   fusion closed to 0.13 mm were per-photo pose error, not fusion error).

   Pure: correspondences in, poses and focal out. No session state, no files."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.lm :as lm]))

(def ^:private behind-camera-penalty
  "What a point that fails to image costs. Constant-length residuals are required
   by lm/solve, so a vanished projection is reported as a large number rather
   than dropped — dropping it would make the cost surface discontinuous and let
   the solver chase the artefact instead of the data."
  1000.0)

(defn- unpack
  "Parameters -> [focal-mm, poses]. The focal leads because it is shared; each
   photo follows with its own six."
  [p n]
  [(nth p 0)
   (mapv (fn [i]
           (let [o (+ 1 (* 6 i))]
             {:rvec [(nth p o) (nth p (+ o 1)) (nth p (+ o 2))]
              :t [(nth p (+ o 3)) (nth p (+ o 4)) (nth p (+ o 5))]}))
         (range n))])

(defn- pack [focal-mm poses]
  (vec (concat [focal-mm] (mapcat (fn [{:keys [rvec t]}] (concat rvec t)) poses))))

(defn- intrinsics-of [focal-mm [w h]]
  (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg focal-mm (/ w h)) w h))

(defn- residual-fn [views sigma-px]
  (fn [p]
    (let [[focal-mm poses] (unpack p (count views))]
      (vec (mapcat (fn [{:keys [image-size picks]} pose]
                     (let [k (intrinsics-of focal-mm image-size)]
                       (mapcat (fn [{:keys [world px]}]
                                 (if-let [[pu pv] (cam/project k pose world)]
                                   [(/ (- pu (first px)) sigma-px)
                                    (/ (- pv (second px)) sigma-px)]
                                   [behind-camera-penalty behind-camera-penalty]))
                               picks)))
                   views poses)))))

(defn- rms-of
  "Reprojection rms in pixels, per view and overall."
  [views focal-mm poses]
  (let [per (mapv (fn [{:keys [image-size picks]} pose]
                    (let [k (intrinsics-of focal-mm image-size)
                          sq (map (fn [{:keys [world px]}]
                                    (if-let [[pu pv] (cam/project k pose world)]
                                      (+ (* (- pu (first px)) (- pu (first px)))
                                         (* (- pv (second px)) (- pv (second px))))
                                      (* behind-camera-penalty behind-camera-penalty)))
                                  picks)]
                      (Math/sqrt (/ (reduce + 0.0 sq) (max 1 (count picks))))))
                  views poses)
        n (reduce + 0 (map #(count (:picks %)) views))
        tot (reduce + 0.0 (map (fn [r v] (* r r (count (:picks v)))) per views))]
    {:per-view per :rms-px (Math/sqrt (/ tot (max 1 n)))}))

(def focal-band
  "How far the fitted focal may stray from the one it started with, as a
   fraction. The EXIF value is a real measurement of a real lens, wrong by a
   percent or two, not by a third: a fit that wants to move it further is
   describing something other than the focal — a mislabelled mark, most likely —
   and clamping it there keeps that failure visible as residual instead of
   disguising it as a strange lens. When the clamp fires, the poses are
   RE-FITTED at the clamped focal (see refine-session): the runaway fit's poses
   belong to the runaway focal, and returning them under the clamped one is a
   state nobody fitted — battiscopa4 (3/9) measured it at 28-33px on views
   whose own solves sat at 2-11px, garbage that poisoned every consumer
   downstream (leave-one-out shed the GOOD views; the multi-start's restart
   could never win with its rms reported by a chimera)."
  0.15)

(defn refine-session
  "Refine ONE focal and every view's pose together.

   `views` is [{:image-size [w h] :pose {:rvec :t} :picks [{:world :px}]} …] —
   the poses being each photo's current PnP solution, which is where the joint
   fit starts from. `focal-mm` is the session's current 35mm-equivalent focal.

   Returns {:focal-mm :poses :rms-px :per-view :before {:focal-mm :rms-px
   :per-view} :clamped?}, or {:error …} when there is not enough to fit: the
   focal needs at least two views to be separable from the distances, and every
   view needs enough picks to hold its own six degrees of freedom."
  ([views focal-mm] (refine-session views focal-mm {}))
  ([views focal-mm {:keys [sigma-px max-iterations] :or {sigma-px 1.0 max-iterations 120}}]
   (let [usable (filterv #(and (:pose %) (>= (count (:picks %)) 4)) views)]
     (cond
       (< (count usable) 2)
       {:error (str "il raffinamento congiunto ha bisogno di almeno DUE foto "
                    "registrate con i loro click (ne ho trovate " (count usable)
                    "): con una sola, la focale e la distanza della camera sono "
                    "la stessa cosa e non si separano")}

       :else
       (let [poses0 (mapv :pose usable)
             before (rms-of usable focal-mm poses0)
             rfn (residual-fn usable sigma-px)
             res (lm/solve rfn (pack focal-mm poses0) {:max-iterations max-iterations})
             [f poses] (unpack (:params res) (count usable))
             lo (* focal-mm (- 1.0 focal-band))
             hi (* focal-mm (+ 1.0 focal-band))
             clamped? (or (< f lo) (> f hi))
             f (max lo (min hi f))
             ;; a clamped focal must not keep the runaway fit's poses: re-fit
             ;; them WITH the focal frozen at the band's edge, so what is
             ;; returned (and measured) is a state that was actually fitted
             poses (if clamped?
                     (let [p0 (vec (rest (pack f poses0)))
                           res2 (lm/solve #(rfn (into [f] %)) p0
                                          {:max-iterations max-iterations})]
                       (second (unpack (into [f] (:params res2)) (count usable))))
                     poses)
             after (rms-of usable f poses)]
         (merge after
                {:focal-mm f
                 :poses poses
                 :views (mapv :idx usable)
                 :clamped? clamped?
                 :before (assoc before :focal-mm focal-mm)}))))))
