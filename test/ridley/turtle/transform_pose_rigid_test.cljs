(ns ridley.turtle.transform-pose-rigid-test
  "attachment/transform-pose-rigid is what fix (2) of the edit-acquire
   registration-stability work leans on: when the proxy is re-aligned on photo
   0, every registered camera must follow it RIGIDLY instead of being wiped. The
   property that makes that correct is that the camera↔proxy relative geometry
   the registration solved is preserved: the camera expressed in the proxy's OWN
   local frame is identical before the move (camera C, proxy P_old) and after
   (transported camera C', proxy P_new). If this drifts, a photo-0 nudge would
   silently slide every other photo's camera off its solved pose — exactly the
   class of bug this whole task exists to kill."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.math :as m]
            [ridley.turtle.attachment :as att]))

(defn- pose-basis [pose]
  (let [h (m/normalize (:heading pose))
        u (m/normalize (:up pose))
        r (m/normalize (m/cross h u))]
    [h u r]))

(defn- pose->local
  "Camera pose expressed in `ref-pose`'s local frame: position as local coords,
   heading/up projected onto the local axes. This is the pose-independent
   description of the camera↔proxy relationship."
  [camera ref-pose]
  (let [[h u r] (pose-basis ref-pose)
        rel (m/v- (:position camera) (:position ref-pose))
        coords (fn [v] [(m/dot v h) (m/dot v u) (m/dot v r)])]
    {:position (coords rel)
     :heading (coords (m/normalize (:heading camera)))
     :up (coords (m/normalize (:up camera)))}))

(defn- close? [a b] (< (Math/abs (- a b)) 1e-9))
(defn- vclose? [va vb] (every? true? (map close? va vb)))
(defn- local-close? [la lb]
  (and (vclose? (:position la) (:position lb))
       (vclose? (:heading la) (:heading lb))
       (vclose? (:up la) (:up lb))))

(defn- ortho-pose
  "A proxy pose with heading⊥up EXACTLY (Gram-Schmidt the up hint) — the
   invariant every real proxy creation-pose holds and transform-pose-rigid
   (like group-transform) requires: a non-orthonormal basis silently turns the
   rigid transform into a shear (see math/orthogonalize-up's own docstring)."
  [position heading up-hint]
  (let [h (m/normalize heading)]
    {:position position :heading h :up (m/orthogonalize-up h up-hint)}))

;; The camera being transported: a generic pose. Its heading/up need NOT be
;; perpendicular — only the PROXY basis it's decomposed against must be
;; orthonormal — so we deliberately leave it un-orthogonalized to prove that.
(def camera {:position [220.0 -140.0 160.0]
             :heading (m/normalize [-0.7 0.45 -0.55])
             :up (m/normalize [0.1 0.2 0.97])})

(def proxy-old (ortho-pose [12.0 -3.0 8.0] [0.9 0.3 0.1] [-0.05 0.2 0.98]))

(deftest identity-transform-is-a-noop
  (testing "old-pose == new-pose leaves the camera exactly where it was"
    (let [{op :position oh :heading ou :up} proxy-old
          out (att/transform-pose-rigid camera op oh ou op oh ou)]
      (is (vclose? (:position out) (:position camera)))
      (is (vclose? (:heading out) (m/normalize (:heading camera))))
      (is (vclose? (:up out) (m/normalize (:up camera)))))))

(deftest pure-translation-shifts-position-only
  (testing "translating the proxy shifts the camera by the same delta, orientation intact"
    (let [{op :position oh :heading ou :up} proxy-old
          delta [30.0 -12.0 5.0]
          np (m/v+ op delta)
          out (att/transform-pose-rigid camera op oh ou np oh ou)]
      (is (vclose? (:position out) (m/v+ (:position camera) delta)))
      (is (vclose? (:heading out) (m/normalize (:heading camera))))
      (is (vclose? (:up out) (m/normalize (:up camera)))))))

(deftest rigid-transport-preserves-camera-in-proxy-frame
  (testing "a generic proxy re-pose (rotate + translate) preserves camera↔proxy geometry"
    (let [{op :position oh :heading ou :up} proxy-old
          ;; a genuinely different new proxy pose: rotated axes + moved origin
          proxy-new (ortho-pose [-40.0 25.0 -6.0] [0.2 -0.9 0.35] [0.6 0.1 0.79])
          {np :position nh :heading nu :up} proxy-new
          out (att/transform-pose-rigid camera op oh ou np nh nu)
          before (pose->local camera proxy-old)
          after (pose->local out proxy-new)]
      (is (local-close? before after)
          "camera expressed in the proxy's local frame must be identical before/after"))))

(deftest transport-is-composable-and-invertible
  (testing "transporting old->new then new->old returns the original camera"
    (let [{op :position oh :heading ou :up} proxy-old
          proxy-mid (ortho-pose [5.0 5.0 5.0] [0.0 1.0 0.0] [0.0 0.0 1.0])
          {mp :position mh :heading mu :up} proxy-mid
          fwd (att/transform-pose-rigid camera op oh ou mp mh mu)
          back (att/transform-pose-rigid fwd mp mh mu op oh ou)]
      (is (vclose? (:position back) (:position camera)))
      (is (vclose? (:heading back) (m/normalize (:heading camera))))
      (is (vclose? (:up back) (m/normalize (:up camera)))))))

;; A creation-pose carried through this transform (edit-attach placing its gizmo
;; where the REST of the script moved the object) also carries the material frame
;; stretch-* scales along. Readers fall back to :heading/:up when it's missing, so
;; dropping it wouldn't fail loudly — the stretch cubes would just quietly go back
;; on the pose axes.
(deftest transport-carries-the-material-frame
  (testing "material axes rotate with the pose, and are absent when the input had none"
    (let [{op :position oh :heading ou :up} proxy-old
          proxy-new (ortho-pose [-40.0 25.0 -6.0] [0.2 -0.9 0.35] [0.6 0.1 0.79])
          {np :position nh :heading nu :up} proxy-new
          ;; a creation-pose whose geometry was built in a frame of its own,
          ;; distinct from the pose frame (what stretch-f/rt/u actually use)
          material {:material-heading (m/normalize [0.0 1.0 0.0])
                    :material-up (m/normalize [0.0 0.0 1.0])}
          pose (merge camera material)
          out (att/transform-pose-rigid pose op oh ou np nh nu)
          ;; the material axes are directions: transporting them on their own as
          ;; a pose's heading/up must give the same vectors
          probe (att/transform-pose-rigid
                 {:position [0.0 0.0 0.0]
                  :heading (:material-heading material)
                  :up (:material-up material)}
                 op oh ou np nh nu)]
      (is (vclose? (:material-heading out) (:heading probe)))
      (is (vclose? (:material-up out) (:up probe)))
      (testing "material frame stays distinct from the pose frame after transport"
        (is (not (vclose? (:material-heading out) (:heading out)))))
      (testing "a pose without a material frame gains no keys (every camera)"
        (let [bare (att/transform-pose-rigid camera op oh ou np nh nu)]
          (is (= #{:position :heading :up} (set (keys bare)))))))))

;; group-transform must carry a mesh's :anchors rigidly, not leave them behind:
;; edit-acquire poses/canonicalizes a proxy through group-transform, and a proxy
;; PLATE's registration marks live on :anchors. If they don't ride the geometry,
;; every mark desyncs from the disc it names the moment the session re-poses the
;; plate — the point-source `p` would then click stale positions.
(deftest group-transform-carries-anchors-rigidly
  (testing "a mesh's :anchors move with its geometry under group-transform"
    (let [{op :position oh :heading ou :up} proxy-old
          proxy-new (ortho-pose [-40.0 25.0 -6.0] [0.2 -0.9 0.35] [0.6 0.1 0.79])
          {np :position nh :heading nu :up} proxy-new
          ;; a mark that COINCIDES with a mesh vertex, so "tracks the vertex" is
          ;; a direct equality after the transform
          mark {:position [58.0 0.0 1.5] :heading [0.0 0.0 1.0] :up [1.0 0.0 0.0]}
          mesh {:type :mesh
                :vertices [[58.0 0.0 1.5] [0.0 0.0 0.0]]
                :faces []
                :creation-pose {:position op :heading oh :up ou}
                :anchors {:m00 mark}}
          [out] (att/group-transform [mesh] op oh ou np nh nu)
          moved-anchor (get-in out [:anchors :m00])]
      (testing "anchor position tracks its coincident vertex"
        (is (vclose? (:position moved-anchor) (first (:vertices out)))))
      (testing "anchor pose is preserved in the mesh's own (proxy) local frame"
        (is (local-close? (pose->local mark proxy-old)
                          (pose->local moved-anchor proxy-new))))
      (testing "a mesh with no :anchors is unaffected (no key introduced)"
        (let [[bare] (att/group-transform [(dissoc mesh :anchors)] op oh ou np nh nu)]
          (is (not (contains? bare :anchors))))))))
