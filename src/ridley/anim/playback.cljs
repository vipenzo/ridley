(ns ridley.anim.playback
  "Animation playback: render loop integration, mesh/camera pose application.
   Called from viewport's render-frame on every frame."
  (:require [ridley.anim.core :as anim]
            [ridley.math :as math]))

;; ============================================================
;; Mesh access callbacks (set by viewport to avoid circular dep)
;; ============================================================

(defonce ^:private get-mesh-fn (atom nil))
(defonce ^:private register-mesh-fn (atom nil))
;; Callback for in-place Three.js geometry update (mesh-name, new-verts, faces)
(defonce ^:private update-geometry-fn (atom nil))

(defn set-mesh-callbacks!
  "Set callbacks for mesh registry access. Called by viewport/core during init."
  [get-fn register-fn]
  (reset! get-mesh-fn get-fn)
  (reset! register-mesh-fn register-fn)
  ;; Also forward to anim/core for stop! mesh restoration
  (anim/set-mesh-callbacks! get-fn register-fn))

(defn set-update-geometry-callback!
  "Set callback for in-place Three.js geometry updates during animation.
   Called by core.cljs during init. Signature: (f mesh-name new-vertices faces)"
  [f]
  (reset! update-geometry-fn f))

;; ============================================================
;; Mesh pose application
;; ============================================================

(defn- compute-rotation-matrix
  "Compute the rotation that transforms base-pose orientation to frame-pose orientation.
   Returns a function that rotates a vector."
  [base-pose frame-pose]
  (let [;; Build orthonormal frames
        bh (:heading base-pose)
        bu (:up base-pose)
        br (math/normalize (math/cross bh bu))
        fh (:heading frame-pose)
        fu (:up frame-pose)
        fr (math/normalize (math/cross fh fu))]
    ;; The rotation maps: bh->fh, bu->fu, br->fr
    ;; For a vector v in base frame: v = (v·br)*br + (v·bh)*bh + (v·bu)*bu
    ;; Rotated: (v·br)*fr + (v·bh)*fh + (v·bu)*fu
    (fn [v]
      (let [comp-r (math/dot v br)
            comp-h (math/dot v bh)
            comp-u (math/dot v bu)]
        (math/v+ (math/v+ (math/v* fr comp-r)
                           (math/v* fh comp-h))
                 (math/v* fu comp-u))))))

(defn- apply-mesh-pose!
  "Transform mesh vertices from base-vertices using the delta between
   base-pose and frame-pose. Updates the mesh in the registry."
  [mesh-name frame-pose anim-data]
  (let [base-pose (:base-pose anim-data)
        base-verts (:base-vertices anim-data)
        base-faces (:base-faces anim-data)]
    (when (and base-pose base-verts (seq base-verts))
      (let [rotate-fn (compute-rotation-matrix base-pose frame-pose)
            translation (math/v- (:position frame-pose) (:position base-pose))
            base-origin (:position base-pose)
            new-verts (mapv (fn [v]
                              (let [rel (math/v- v base-origin)
                                    rotated (rotate-fn rel)]
                                (math/v+ (math/v+ base-origin rotated) translation)))
                            base-verts)]
        ;; Update mesh in registry (preserves all other mesh properties)
        (when-let [get-fn @get-mesh-fn]
          (when-let [mesh (get-fn mesh-name)]
            (when-let [reg-fn @register-mesh-fn]
              (reg-fn mesh-name
                      (assoc mesh
                             :vertices new-verts
                             :creation-pose frame-pose)))))
        ;; Update Three.js geometry in-place (visual update)
        (when-let [f @update-geometry-fn]
          (f mesh-name new-verts base-faces))))))

;; ============================================================
;; Camera pose application (deferred — set via callback)
;; ============================================================

;; Callbacks set by viewport/core.cljs to avoid circular dependency
(defonce ^:private camera-pose-fn (atom nil))
(defonce ^:private camera-stop-fn (atom nil))

(defn set-camera-pose-callback!
  "Set the callback function for applying camera poses.
   Called by viewport/core.cljs during init."
  [f]
  (reset! camera-pose-fn f))

(defn set-camera-stop-callback!
  "Set the callback for re-enabling OrbitControls when camera animation stops.
   Called by viewport/core.cljs during init. Also forwards to anim/core for manual stop!."
  [f]
  (reset! camera-stop-fn f)
  (anim/set-on-camera-stop! f))

(defn- apply-camera-pose!
  "Apply a pose to the camera via callback."
  [pose]
  (when-let [f @camera-pose-fn]
    (f pose)))

;; ============================================================
;; Link support: parent-child position tracking
;; ============================================================

(defn- get-parent-position-delta
  "Compute the combined position delta of all active animations on a parent target.
   Sums deltas from multiple animations if present.
   Returns [dx dy dz] or nil if no active animations found."
  [parent-target]
  (let [deltas (keep (fn [[_ parent-anim]]
                       (when (and (= (:target parent-anim) parent-target)
                                  (not= :stopped (:state parent-anim)))
                         (when-let [initial-pos (:position (:initial-pose parent-anim))]
                           (let [frame-idx (anim/time->frame-idx (:current-time parent-anim) parent-anim)
                                 frames (:frames parent-anim)
                                 total (count frames)]
                             (when (pos? total)
                               (let [current-pos (:position (nth frames (max 0 (min frame-idx (dec total)))))]
                                 (when current-pos
                                   (math/v- current-pos initial-pos))))))))
                     @anim/anim-registry)]
    (when (seq deltas)
      (reduce math/v+ [0 0 0] deltas))))

;; ============================================================
;; Frame application dispatch
;; ============================================================

(defn- apply-frame!
  "Apply a precomputed frame pose to the animation target.
   If the target is linked to a parent, adds the parent's position delta."
  [anim-data frame-idx]
  (let [frames (:frames anim-data)
        total (count frames)]
    (when (pos? total)
      (let [clamped-idx (max 0 (min frame-idx (dec total)))
            pose (nth frames clamped-idx)
            target (:target anim-data)
            ;; Apply link offset if this target has a parent
            parent-target (get @anim/link-registry target)
            delta (when parent-target (get-parent-position-delta parent-target))
            final-pose (if delta
                         (update pose :position math/v+ delta)
                         pose)]
        (cond
          (= target :camera) (apply-camera-pose! final-pose)
          :else (apply-mesh-pose! target final-pose anim-data))))))

;; ============================================================
;; Multi-animation composition on same target
;; ============================================================

(defn- apply-target-frame!
  "Apply frame(s) for a target. Handles both single and multi-animation targets.
   For single animation: delegates to apply-frame! (fast path).
   For multi-animation: sums position deltas and composes rotation changes
   from the shared base-pose, then applies the combined transform."
  [target anim-frames]
  (if (= 1 (count anim-frames))
    ;; Single animation — use existing fast path
    (let [{:keys [frame-idx anim-data]} (first anim-frames)]
      (apply-frame! anim-data frame-idx))
    ;; Multiple animations — combine deltas from shared base-pose
    (let [first-anim (:anim-data (first anim-frames))
          base-pose (:base-pose first-anim)]
      (when base-pose
        (let [base-pos (:position base-pose)
              base-heading (:heading base-pose)
              base-up (:up base-pose)
              ;; Collect frame poses from each animation
              frame-poses (mapv (fn [{:keys [frame-idx anim-data]}]
                                  (let [frames (:frames anim-data)
                                        total (count frames)
                                        idx (max 0 (min frame-idx (dec total)))]
                                    (nth frames idx)))
                                anim-frames)
              ;; Sum position deltas
              combined-pos (reduce (fn [pos fp]
                                     (math/v+ pos (math/v- (:position fp) base-pos)))
                                   base-pos
                                   frame-poses)
              ;; Sum heading/up deltas from base and re-orthogonalize
              combined-heading (reduce (fn [h fp]
                                         (math/v+ h (math/v- (:heading fp) base-heading)))
                                       base-heading
                                       frame-poses)
              combined-up (reduce (fn [u fp]
                                    (math/v+ u (math/v- (:up fp) base-up)))
                                  base-up
                                  frame-poses)
              ;; Normalize and ensure orthogonality (Gram-Schmidt)
              h-len (Math/sqrt (math/dot combined-heading combined-heading))
              norm-heading (if (> h-len 0.001)
                             (math/v* combined-heading (/ 1.0 h-len))
                             base-heading)
              proj (math/v* norm-heading (math/dot combined-up norm-heading))
              ortho-up (math/v- combined-up proj)
              u-len (Math/sqrt (math/dot ortho-up ortho-up))
              norm-up (if (> u-len 0.001)
                        (math/v* ortho-up (/ 1.0 u-len))
                        base-up)
              combined-pose {:position combined-pos
                             :heading norm-heading
                             :up norm-up}
              ;; Apply link offset if this target has a parent
              parent-target (get @anim/link-registry target)
              delta (when parent-target (get-parent-position-delta parent-target))
              final-pose (if delta
                           (update combined-pose :position math/v+ delta)
                           combined-pose)]
          (cond
            (= target :camera) (apply-camera-pose! final-pose)
            :else (apply-mesh-pose! target final-pose first-anim)))))))

;; ============================================================
;; Seek with visual application (for scrub/slider)
;; ============================================================

(defn seek-and-apply!
  "Seek to a fractional position and visually apply the frame.
   Unlike anim/seek! which only updates current-time, this also
   applies the pose to the mesh/camera."
  [anim-name fraction]
  (anim/seek! anim-name fraction)
  (when-let [anim-data (get @anim/anim-registry anim-name)]
    (let [frame-idx (anim/time->frame-idx (:current-time anim-data) anim-data)]
      (apply-frame! anim-data frame-idx))))

;; ============================================================
;; Tick — called from render loop
;; ============================================================

;; Callback to trigger viewport refresh after animation frame
(defonce ^:private refresh-fn (atom nil))

(defn set-refresh-callback!
  "Set the callback for triggering viewport refresh after animation.
   Called by viewport/core.cljs during init."
  [f]
  (reset! refresh-fn f))

(defn tick-animations!
  "Called from render-frame. Advances all playing animations by dt seconds.
   Groups animations by target for multi-animation composition (delta summing).
   Two-pass ordering: unlinked targets first (parents), then linked (children).
   Returns true if any animation was updated (viewport needs refresh)."
  [dt]
  (let [reg @anim/anim-registry
        links @anim/link-registry
        any-updated? (atom false)
        frame-data (atom {})   ; anim-name -> {:frame-idx :anim-data}
        finished (atom [])]
    ;; Phase 1: Advance all playing animation times, compute frame indices
    (doseq [[anim-name anim-data] reg]
      (when (= :playing (:state anim-data))
        (let [new-time (+ (:current-time anim-data) dt)
              duration (:duration anim-data)
              looping? (:loop anim-data false)]
          (reset! any-updated? true)
          (cond
            ;; Looping: wrap around
            (and looping? (>= new-time duration))
            (let [wrapped-time (mod new-time duration)
                  frame-idx (anim/time->frame-idx wrapped-time anim-data)]
              (swap! anim/anim-registry assoc-in [anim-name :current-time] wrapped-time)
              (swap! frame-data assoc anim-name {:frame-idx frame-idx :anim-data anim-data}))

            ;; Finished (non-looping)
            (>= new-time duration)
            (let [total-frames (:total-frames anim-data)
                  frame-idx (if (pos? total-frames) (dec total-frames) 0)]
              (swap! frame-data assoc anim-name {:frame-idx frame-idx :anim-data anim-data})
              (swap! finished conj anim-name))

            ;; Normal advance
            :else
            (let [frame-idx (anim/time->frame-idx new-time anim-data)]
              (swap! anim/anim-registry assoc-in [anim-name :current-time] new-time)
              (swap! frame-data assoc anim-name {:frame-idx frame-idx :anim-data anim-data}))))))
    ;; Phase 2: Group by target, apply combined poses
    (when @any-updated?
      (let [by-target (reduce-kv (fn [m _anim-name entry]
                                   (let [target (:target (:anim-data entry))]
                                     (update m target (fnil conj []) entry)))
                                 {}
                                 @frame-data)]
        ;; Pass 1: Unlinked targets (parents)
        (doseq [[target anims] by-target]
          (when-not (get links target)
            (apply-target-frame! target anims)))
        ;; Pass 2: Linked targets (children)
        (doseq [[target anims] by-target]
          (when (get links target)
            (apply-target-frame! target anims)))))
    ;; Phase 3: Stop finished animations
    (let [had-camera-finish? (atom false)]
      (doseq [anim-name @finished]
        (let [anim-data (get reg anim-name)]
          (swap! anim/anim-registry update anim-name assoc
                 :state :stopped
                 :current-time 0.0)
          (when (= :camera (:target anim-data))
            (reset! had-camera-finish? true))))
      ;; Re-enable OrbitControls if camera animation finished and none still playing
      (when @had-camera-finish?
        (let [any-cam-still? (some (fn [[_ d]]
                                     (and (= :camera (:target d))
                                          (= :playing (:state d))))
                                   @anim/anim-registry)]
          (when-not any-cam-still?
            (when-let [f @camera-stop-fn] (f))))))
    ;; Trigger viewport refresh if anything changed
    (when @any-updated?
      (when-let [f @refresh-fn]
        (f)))
    @any-updated?))

