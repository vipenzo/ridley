(ns ridley.anim.playback
  "Animation playback: render loop integration, mesh/camera pose application.
   Called from viewport's render-frame on every frame."
  (:require [ridley.anim.core :as anim]
            [ridley.anim.easing :as easing]
            [ridley.editor.state :as state]
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
;; Procedural mesh application
;; ============================================================

(defn- apply-procedural-mesh!
  "Replace mesh data in registry and update Three.js geometry.
   The new-mesh comes from the user's gen-fn."
  [mesh-name new-mesh _anim-data]
  (when new-mesh
    (let [new-verts (:vertices new-mesh)
          new-faces (:faces new-mesh)]
      (when (and new-verts new-faces)
        ;; Update mesh in registry
        (when-let [get-fn @get-mesh-fn]
          (when-let [old-mesh (get-fn mesh-name)]
            (when-let [reg-fn @register-mesh-fn]
              (reg-fn mesh-name
                      (assoc old-mesh
                             :vertices new-verts
                             :faces new-faces)))))
        ;; Update Three.js geometry (handles face count changes)
        (when-let [f @update-geometry-fn]
          (f mesh-name new-verts new-faces))))))

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
;; Link support: parent-child position/rotation tracking
;; ============================================================

(defn- get-parent-position-delta
  "Compute the combined position delta of all active animations on a parent target.
   Sums deltas from multiple animations if present.
   Returns [dx dy dz] or nil if no active animations found."
  [parent-target]
  (let [deltas (keep (fn [[_ parent-anim]]
                       (when (and (= (:target parent-anim) parent-target)
                                  (not= :stopped (:state parent-anim))
                                  (= :preprocessed (:type parent-anim :preprocessed)))
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

(defn- current-frame-pose
  "Get the current frame pose for a target from its active animations."
  [parent-target]
  (some (fn [[_ anim-data]]
          (when (and (= (:target anim-data) parent-target)
                     (not= :stopped (:state anim-data))
                     (= :preprocessed (:type anim-data :preprocessed)))
            (let [frame-idx (anim/time->frame-idx (:current-time anim-data) anim-data)
                  frames (:frames anim-data)
                  total (count frames)]
              (when (pos? total)
                (nth frames (max 0 (min frame-idx (dec total))))))))
        @anim/anim-registry))

(defn- resolve-anchor-position
  "Compute current world position of a mesh anchor.
   Applies the same rotation+translation as the mesh vertices.
   Returns {:position :heading :up} or nil."
  [parent-target anchor-name]
  (when-let [get-fn @get-mesh-fn]
    (when-let [mesh (get-fn parent-target)]
      (when-let [anchor (get-in mesh [:anchors anchor-name])]
        (let [;; Find any active animation for this target to get base/frame pose
              anim-data (some (fn [[_ ad]]
                                (when (and (= (:target ad) parent-target)
                                           (not= :stopped (:state ad)))
                                  ad))
                              @anim/anim-registry)
              base-pose (or (:base-pose anim-data) (:creation-pose mesh))
              frame-pose (when anim-data (current-frame-pose parent-target))]
          (if (and base-pose frame-pose)
            ;; Transform anchor point same as vertices
            (let [rotate-fn (compute-rotation-matrix base-pose frame-pose)
                  translation (math/v- (:position frame-pose) (:position base-pose))
                  base-origin (:position base-pose)
                  rel (math/v- (:position anchor) base-origin)
                  rotated (rotate-fn rel)]
              {:position (math/v+ (math/v+ base-origin rotated) translation)
               :heading (rotate-fn (:heading anchor))
               :up (rotate-fn (:up anchor))})
            ;; No animation — return static anchor
            anchor))))))

(defn- resolve-link-delta
  "Resolve the position (and optionally rotation) delta for a linked child.
   Returns {:position-delta [dx dy dz] :rotation-fn fn-or-nil}."
  [target]
  (let [link-entry (get @anim/link-registry target)]
    (when link-entry
      (let [;; Support both old format (bare keyword) and new format (map)
            parent-target (if (keyword? link-entry) link-entry (:parent link-entry))
            parent-anchor (when (map? link-entry) (:parent-anchor link-entry))
            inherit-rot? (when (map? link-entry) (:inherit-rotation link-entry))]
        (if parent-anchor
          ;; Anchor-based link: track the anchor's world position
          (when-let [anchor-pose (resolve-anchor-position parent-target parent-anchor)]
            (let [;; Compute delta from the anchor's rest position to its current position
                  get-fn @get-mesh-fn
                  parent-mesh (when get-fn (get-fn parent-target))
                  rest-anchor (get-in parent-mesh [:anchors parent-anchor])
                  pos-delta (if rest-anchor
                              (math/v- (:position anchor-pose) (:position rest-anchor))
                              [0 0 0])]
              {:position-delta pos-delta
               :rotation-fn (when inherit-rot?
                              (when rest-anchor
                                (compute-rotation-matrix
                                  {:heading (:heading rest-anchor) :up (:up rest-anchor)
                                   :position (:position rest-anchor)}
                                  anchor-pose)))}))
          ;; Centroid-based link: track parent's position delta (existing behavior)
          (let [delta (get-parent-position-delta parent-target)]
            (when delta
              {:position-delta delta
               :rotation-fn (when inherit-rot?
                              (let [frame-pose (current-frame-pose parent-target)]
                                (when frame-pose
                                  (when-let [get-fn @get-mesh-fn]
                                    (when-let [parent-mesh (get-fn parent-target)]
                                      (when-let [base-pose (:creation-pose parent-mesh)]
                                        (compute-rotation-matrix base-pose frame-pose)))))))})))))))

;; ============================================================
;; Frame application dispatch
;; ============================================================

(defn- apply-frame!
  "Apply a precomputed frame pose to the animation target.
   If the target is linked to a parent, adds the parent's position/rotation delta."
  [anim-data frame-idx]
  (let [frames (:frames anim-data)
        total (count frames)]
    (when (pos? total)
      (let [clamped-idx (max 0 (min frame-idx (dec total)))
            pose (nth frames clamped-idx)
            target (:target anim-data)
            link-result (resolve-link-delta target)
            final-pose (if link-result
                         (let [{:keys [position-delta rotation-fn]} link-result
                               posed (update pose :position math/v+ position-delta)]
                           (if rotation-fn
                             (-> posed
                                 (update :heading #(math/normalize (rotation-fn %)))
                                 (update :up #(math/normalize (rotation-fn %))))
                             posed))
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
              link-result (resolve-link-delta target)
              final-pose (if link-result
                           (let [{:keys [position-delta rotation-fn]} link-result
                                 posed (update combined-pose :position math/v+ position-delta)]
                             (if rotation-fn
                               (-> posed
                                   (update :heading #(math/normalize (rotation-fn %)))
                                   (update :up #(math/normalize (rotation-fn %))))
                               posed))
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
    (if (= :procedural (:type anim-data))
      ;; Procedural: call gen-fn with eased t
      (let [t (max 0.0 (min 1.0 fraction))
            eased-t (easing/ease (:easing anim-data :linear) t)
            new-mesh ((:gen-fn anim-data) eased-t)]
        (when new-mesh
          (apply-procedural-mesh! (:target anim-data) new-mesh anim-data)))
      ;; Preprocessed: existing frame lookup
      (let [frame-idx (anim/time->frame-idx (:current-time anim-data) anim-data)]
        (apply-frame! anim-data frame-idx)))))

;; ============================================================
;; Tick — called from render loop
;; ============================================================

;; Callback to trigger viewport refresh after animation frame
(defonce ^:private refresh-fn (atom nil))
;; Callback to push async print output to the REPL UI
(defonce ^:private async-output-fn (atom nil))

(defn set-refresh-callback!
  "Set the callback for triggering viewport refresh after animation.
   Called by viewport/core.cljs during init."
  [f]
  (reset! refresh-fn f))

(defn set-async-output-callback!
  "Set the callback for displaying async output (from span callbacks) in the REPL.
   Called by core.cljs during init."
  [f]
  (reset! async-output-fn f))

(defn- compute-execution-order
  "Topological sort of targets based on link dependencies.
   Parents are processed before children. Handles arbitrary depth.
   Targets whose parents are not in the active set are treated as
   secondary roots so they still get processed."
  [link-registry targets]
  (let [;; Build children-of map: parent -> [children]
        children-of (reduce-kv (fn [m child entry]
                                 (let [parent (if (keyword? entry) entry (:parent entry))]
                                   (update m parent (fnil conj []) child)))
                               {} link-registry)
        target-set (set targets)
        ;; Roots = targets that have no parent in link-registry
        roots (remove #(contains? link-registry %) targets)
        ;; Secondary roots = targets whose parent is NOT in the active target set
        ;; (e.g. arm animated alone — parent torso has no animation this tick)
        secondary (filter (fn [t]
                            (and (contains? link-registry t)
                                 (let [entry (get link-registry t)
                                       parent (if (keyword? entry) entry (:parent entry))]
                                   (not (contains? target-set parent)))))
                          targets)]
    (loop [queue (vec (concat roots secondary))
           visited #{}
           order []]
      (if (empty? queue)
        order
        (let [t (first queue)]
          (if (visited t)
            (recur (subvec queue 1) visited order)
            (recur (into (subvec queue 1) (get children-of t []))
                   (conj visited t)
                   (conj order t))))))))

(defn- invoke-span-callback!
  "Invoke a span callback, flushing any println output to the REPL."
  [cb]
  (state/reset-print-buffer!)
  (try
    (cb)
    (catch :default e
      (js/console.warn "anim span callback error:" e)))
  (when-let [output (state/get-print-output)]
    (if-let [f @async-output-fn]
      (f output)
      (js/console.log output))))

(defn- fire-span-callbacks!
  "Detect span boundary crossings and fire :on-enter/:on-exit callbacks.
   Returns the new span-idx (or prev if unchanged)."
  [anim-name anim-data effective-time]
  (when-let [spans (:spans anim-data)]
    (let [{:keys [span-idx]} (anim/time->span-info effective-time anim-data)
          prev-span-idx (:current-span-idx anim-data)]
      (when (not= span-idx prev-span-idx)
        (when prev-span-idx
          (when-let [on-exit (:on-exit (nth spans prev-span-idx nil))]
            (invoke-span-callback! on-exit)))
        (when-let [on-enter (:on-enter (nth spans span-idx nil))]
          (invoke-span-callback! on-enter))
        (swap! anim/anim-registry assoc-in [anim-name :current-span-idx] span-idx))
      span-idx)))

(defn tick-animations!
  "Called from render-frame. Advances all playing animations by dt seconds.
   Groups animations by target for multi-animation composition (delta summing).
   Two-pass ordering: unlinked targets first (parents), then linked (children).
   Procedural animations call gen-fn each frame instead of looking up frames.
   Returns true if any animation was updated (viewport needs refresh)."
  [dt]
  (let [reg @anim/anim-registry
        links @anim/link-registry
        any-updated? (atom false)
        frame-data (atom {})   ; anim-name -> {:frame-idx :anim-data} (preprocessed)
        proc-data (atom {})    ; anim-name -> {:t :anim-data} (procedural)
        finished (atom [])]
    ;; Phase 1: Advance all playing animation times
    (doseq [[anim-name anim-data] reg]
      (when (= :playing (:state anim-data))
        (let [new-time (+ (:current-time anim-data) dt)
              duration (:duration anim-data)
              loop-mode (let [l (:loop anim-data)]
                          (cond (= true l) :forward  ;; backward compat
                                (keyword? l) l
                                :else nil))
              ;; Bounce has a full cycle of 2*duration (forward + backward)
              cycle-duration (if (= :bounce loop-mode)
                               (* 2.0 duration)
                               duration)
              procedural? (= :procedural (:type anim-data))]
          (reset! any-updated? true)
          (cond
            ;; Looping: wrap around using loop mode
            (and loop-mode (>= new-time cycle-duration))
            (let [wrapped-raw (mod new-time cycle-duration)
                  effective-time (case loop-mode
                                  :forward wrapped-raw
                                  :reverse (- duration wrapped-raw)
                                  :bounce  (if (< wrapped-raw duration)
                                             wrapped-raw
                                             (- (* 2.0 duration) wrapped-raw)))]
              (swap! anim/anim-registry assoc-in [anim-name :current-time] wrapped-raw)
              (if procedural?
                (swap! proc-data assoc anim-name
                       {:t (/ effective-time duration) :anim-data anim-data})
                (do (fire-span-callbacks! anim-name anim-data effective-time)
                    (let [frame-idx (anim/time->frame-idx effective-time anim-data)]
                      (swap! frame-data assoc anim-name
                             {:frame-idx frame-idx :anim-data anim-data})))))

            ;; Finished (non-looping only)
            (and (not loop-mode) (>= new-time duration))
            (do
              (if procedural?
                (swap! proc-data assoc anim-name {:t 1.0 :anim-data anim-data})
                (let [total-frames (:total-frames anim-data)
                      frame-idx (if (pos? total-frames) (dec total-frames) 0)]
                  ;; Fire span transition callbacks, then on-exit for the final span
                  (fire-span-callbacks! anim-name anim-data duration)
                  (let [final-idx (:current-span-idx (get @anim/anim-registry anim-name))]
                    (when final-idx
                      (when-let [on-exit (:on-exit (nth (:spans anim-data) final-idx nil))]
                        (invoke-span-callback! on-exit))))
                  (swap! frame-data assoc anim-name
                         {:frame-idx frame-idx :anim-data anim-data})))
              (swap! finished conj anim-name))

            ;; Normal advance (within first cycle)
            :else
            (let [effective-time (case loop-mode
                                  :reverse (- duration new-time)
                                  :bounce  (if (< new-time duration)
                                             new-time
                                             (- (* 2.0 duration) new-time))
                                  ;; :forward or nil
                                  new-time)]
              (swap! anim/anim-registry assoc-in [anim-name :current-time] new-time)
              (if procedural?
                (swap! proc-data assoc anim-name
                       {:t (/ effective-time duration) :anim-data anim-data})
                (do (fire-span-callbacks! anim-name anim-data effective-time)
                    (let [frame-idx (anim/time->frame-idx effective-time anim-data)]
                      (swap! frame-data assoc anim-name
                             {:frame-idx frame-idx :anim-data anim-data})))))))))
    ;; Phase 2a: Apply procedural animations (gen-fn per frame)
    (doseq [[_anim-name {:keys [t anim-data]}] @proc-data]
      (let [eased-t (easing/ease (:easing anim-data :linear) t)
            target (:target anim-data)
            new-mesh ((:gen-fn anim-data) eased-t)]
        (when new-mesh
          (apply-procedural-mesh! target new-mesh anim-data))))
    ;; Phase 2b: Group preprocessed by target, apply in topological order
    (when (seq @frame-data)
      (let [by-target (reduce-kv (fn [m _anim-name entry]
                                   (let [target (:target (:anim-data entry))]
                                     (update m target (fnil conj []) entry)))
                                 {}
                                 @frame-data)
            ordered-targets (compute-execution-order links (keys by-target))]
        (doseq [target ordered-targets]
          (when-let [anims (get by-target target)]
            (apply-target-frame! target anims)))))
    ;; Phase 3: Stop finished animations
    (let [had-camera-finish? (atom false)]
      (doseq [anim-name @finished]
        (let [anim-data (get reg anim-name)]
          (swap! anim/anim-registry update anim-name assoc
                 :state :stopped
                 :current-time 0.0
                 :current-span-idx nil)
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

