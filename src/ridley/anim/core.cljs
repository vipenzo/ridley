(ns ridley.anim.core
  "Animation registry and control: register, play, pause, stop, seek.

   Animations are preprocessed into per-frame pose arrays.
   The render loop picks up playing animations via tick-animations!."
  (:require [ridley.anim.preprocess :as preprocess]
            [ridley.anim.easing :as easing]))

;; ============================================================
;; Animation registry
;; ============================================================

(defonce anim-registry (atom {}))

;; Link registry: maps child-target → parent-target
;; Children inherit the parent's position delta at playback time.
(defonce link-registry (atom {}))

;; Callback for re-enabling OrbitControls when camera animation stops
;; Set by viewport/core.cljs during init (via playback)
(defonce ^:private on-camera-stop (atom nil))

;; Callbacks for mesh access (avoids circular dep with scene/registry)
;; Set by playback module which has access to registry
(defonce ^:private get-mesh-fn (atom nil))
(defonce ^:private register-mesh-fn (atom nil))

(defn set-on-camera-stop!
  "Set callback for when a camera animation stops (re-enable controls)."
  [f]
  (reset! on-camera-stop f))

(defn set-mesh-callbacks!
  "Set callbacks for mesh access. Called by playback during init."
  [get-fn register-fn]
  (reset! get-mesh-fn get-fn)
  (reset! register-mesh-fn register-fn))

(defn clear-all!
  "Clear all animations and links. Called on code re-evaluation."
  []
  (reset! anim-registry {})
  (reset! link-registry {}))

(defn- get-mesh [target]
  (when-let [f @get-mesh-fn] (f target)))

(defn register-animation!
  "Register a preprocessed animation.
   anim-data should contain:
     :target     - keyword (mesh name) or :camera
     :duration   - seconds
     :fps        - preprocessing framerate
     :loop       - boolean
     :spans      - original span data (for easing lookups)
     :frames     - precomputed pose vector
     :total-frames - count of frames
     :span-ranges - [{:start-frame :frame-count} ...]"
  [name anim-data]
  (let [target (:target anim-data)
        ;; For mesh targets, save base pose and vertices at registration time
        base-data (when (and (keyword? target) (not= target :camera))
                    (when-let [mesh (get-mesh target)]
                      {:base-vertices (:vertices mesh)
                       :base-faces (:faces mesh)
                       :base-pose (:creation-pose mesh)}))]
    (swap! anim-registry assoc name
           (merge anim-data
                  {:name name
                   :type :preprocessed
                   :state :stopped
                   :current-time 0.0
                   :current-span-idx nil}
                  base-data))))

(defn register-procedural-animation!
  "Register a procedural animation.
   A procedural animation evaluates gen-fn every frame with eased t (0→1),
   which returns a new mesh that replaces the current one.
   anim-data should contain:
     :target   - keyword (mesh name)
     :duration - seconds
     :easing   - easing keyword (:linear, :in, :out, :in-out, etc.)
     :loop     - boolean
     :gen-fn   - (fn [t] ...) returning a mesh"
  [name anim-data]
  (let [target (:target anim-data)
        base-data (when (and (keyword? target) (not= target :camera))
                    (when-let [mesh (get-mesh target)]
                      {:base-vertices (:vertices mesh)
                       :base-faces (:faces mesh)
                       :base-pose (:creation-pose mesh)}))]
    (swap! anim-registry assoc name
           (merge anim-data
                  {:name name
                   :type :procedural
                   :state :stopped
                   :current-time 0.0}
                  base-data))))

;; ============================================================
;; Playback control
;; ============================================================

(defn play!
  "Start playing an animation (or all if no name given)."
  ([]
   (doseq [[name _] @anim-registry]
     (play! name)))
  ([name]
   (when-let [anim (get @anim-registry name)]
     ;; If stopped, refresh base data from current mesh state
     ;; or from an already-active animation on the same target (shared base)
     (when (= :stopped (:state anim))
       (let [target (:target anim)]
         (when (and (keyword? target) (not= target :camera))
           (let [existing-base (some (fn [[other-name other-anim]]
                                       (when (and (not= other-name name)
                                                  (= target (:target other-anim))
                                                  (not= :stopped (:state other-anim))
                                                  (:base-vertices other-anim))
                                         (select-keys other-anim [:base-vertices :base-faces :base-pose])))
                                     @anim-registry)]
             (if existing-base
               ;; Share base state with the already-active animation
               (swap! anim-registry update name merge existing-base)
               ;; Fresh capture from current mesh state
               (when-let [mesh (get-mesh target)]
                 (swap! anim-registry update name merge
                        {:base-vertices (:vertices mesh)
                         :base-faces (:faces mesh)
                         :base-pose (:creation-pose mesh)})))))))
     (swap! anim-registry update name merge
            {:state :playing
             :current-span-idx nil}))))

(defn pause!
  "Pause an animation (or all if no name given)."
  ([]
   (doseq [[name anim] @anim-registry]
     (when (= :playing (:state anim))
       (pause! name))))
  ([name]
   (swap! anim-registry assoc-in [name :state] :paused)))

(defn stop!
  "Stop an animation and reset to frame 0 (or all if no name given)."
  ([]
   (doseq [[name _] @anim-registry]
     (stop! name)))
  ([name]
   (when-let [anim (get @anim-registry name)]
     (swap! anim-registry update name assoc
            :state :stopped
            :current-time 0.0
            :current-span-idx nil)
     ;; Restore mesh to base state or re-enable controls for camera
     ;; Only if no other animation is still active on this target
     (let [target (:target anim)
           others-active? (some (fn [[other-name other-anim]]
                                  (and (not= other-name name)
                                       (= target (:target other-anim))
                                       (= :playing (:state other-anim))))
                                @anim-registry)]
       (when-not others-active?
         (if (= target :camera)
           (when-let [f @on-camera-stop] (f))
           (when-let [base-verts (:base-vertices anim)]
             (when-let [reg-fn @register-mesh-fn]
               (when-let [mesh (get-mesh target)]
                 (reg-fn target (cond-> (assoc mesh
                                               :vertices base-verts
                                               :creation-pose (:base-pose anim))
                                  ;; Procedural animations may change faces too
                                  (:base-faces anim)
                                  (assoc :faces (:base-faces anim)))))))))))))

(defn stop-all!
  "Stop all animations."
  []
  (stop!))

(defn seek!
  "Jump to a fractional position (0-1) in an animation."
  [name fraction]
  (when (get @anim-registry name)
    (let [anim (get @anim-registry name)
          t (max 0.0 (min 1.0 fraction))
          new-time (* t (:duration anim))]
      (swap! anim-registry assoc-in [name :current-time] new-time))))

;; ============================================================
;; Animation info
;; ============================================================

(defn list-animations
  "List all registered animations with their status."
  []
  (mapv (fn [[name anim]]
          {:name name
           :type (:type anim :preprocessed)
           :target (:target anim)
           :duration (:duration anim)
           :state (:state anim)
           :loop (:loop anim)
           :current-time (:current-time anim)
           :total-frames (:total-frames anim)})
        @anim-registry))

(defn has-animations?
  "Return true if any animations are registered."
  []
  (pos? (count @anim-registry)))

(defn any-playing?
  "Return true if any animation is currently playing."
  []
  (some #(= :playing (:state (val %))) @anim-registry))

;; ============================================================
;; Time-to-frame mapping (with per-span easing)
;; ============================================================

(defn time->span-info
  "Convert a time value to span index and local-t within that span."
  [time anim]
  (let [duration (:duration anim)
        spans (:spans anim)
        ;; Compute cumulative time boundaries for spans
        weights (mapv #(:weight % 1.0) spans)
        total-weight (reduce + weights)
        span-durations (mapv #(* duration (/ % total-weight)) weights)]
    (loop [i 0
           elapsed 0.0]
      (if (>= i (count spans))
        ;; Past end — clamp to last span at t=1
        {:span-idx (dec (count spans)) :local-t 1.0}
        (let [span-dur (nth span-durations i)
              end-time (+ elapsed span-dur)]
          (if (< time end-time)
            {:span-idx i
             :local-t (if (pos? span-dur)
                        (/ (- time elapsed) span-dur)
                        1.0)}
            (recur (inc i) end-time)))))))

(defn time->frame-idx
  "Convert a time value to a frame index, applying per-span easing."
  [time anim]
  (let [{:keys [span-idx local-t]} (time->span-info time anim)
        span (nth (:spans anim) span-idx)
        eased-t (easing/ease (:easing span :linear) local-t)
        {:keys [start-frame frame-count]} (nth (:span-ranges anim) span-idx)]
    (if (zero? frame-count)
      start-frame
      (+ start-frame
         (min (dec frame-count)
              (int (Math/floor (* eased-t frame-count))))))))

;; ============================================================
;; DSL helpers for span construction
;; ============================================================

(defn make-span
  "Create a span data structure from DSL arguments.
   (make-span weight easing commands)
   (make-span weight easing :ang-velocity N commands)
   (make-span weight easing :on-enter fn :on-exit fn commands)"
  [weight easing-type & args]
  (let [;; Parse keyword options from args
        opts-and-cmds (loop [opts {} remaining args]
                        (cond
                          (empty? remaining)
                          [opts []]
                          (= :ang-velocity (first remaining))
                          (recur (assoc opts :ang-velocity (second remaining))
                                 (drop 2 remaining))
                          (= :on-enter (first remaining))
                          (recur (assoc opts :on-enter (second remaining))
                                 (drop 2 remaining))
                          (= :on-exit (first remaining))
                          (recur (assoc opts :on-exit (second remaining))
                                 (drop 2 remaining))
                          :else [opts (vec remaining)]))
        [opts commands] opts-and-cmds]
    (cond-> {:weight weight
             :easing easing-type
             :ang-velocity (get opts :ang-velocity 1)
             :commands commands}
      (:on-enter opts) (assoc :on-enter (:on-enter opts))
      (:on-exit opts)  (assoc :on-exit (:on-exit opts)))))

(defn make-anim-command
  "Create a command data structure for animation preprocessing."
  [type & {:keys [dist angle]}]
  (cond-> {:type type}
    dist  (assoc :dist dist)
    angle (assoc :angle angle)))

;; ============================================================
;; Target linking (parent-child position tracking)
;; ============================================================

(defn link!
  "Link child target to parent target.
   The child inherits the parent's position delta at playback time.
   Works for any combination: camera→mesh, mesh→mesh.
   Options:
     :at anchor-name       — track parent's anchor (not centroid)
     :from anchor-name     — child attachment point
     :inherit-rotation bool — inherit parent rotation (default false)"
  [child-target parent-target & {:keys [at from inherit-rotation]
                                  :or {inherit-rotation false}}]
  (swap! link-registry assoc child-target
         {:parent parent-target
          :parent-anchor at
          :child-anchor from
          :inherit-rotation inherit-rotation}))

(defn unlink!
  "Remove link from child target."
  [child-target]
  (swap! link-registry dissoc child-target))

(defn get-link-parent
  "Get the parent target for a linked child. Handles both old format
   (bare keyword) and new format ({:parent ...})."
  [child-target]
  (let [entry (get @link-registry child-target)]
    (cond
      (nil? entry) nil
      (keyword? entry) entry           ; old format compat
      (map? entry) (:parent entry)
      :else nil)))
