(ns ridley.editor.impl
  "Runtime *-impl functions for macro delegation.
   Extrude/loft/revolve macros become thin wrappers that call path + *-impl.
   Attach macros record movements into a path, then *-impl replays them."
  (:require [ridley.editor.operations :as ops]
            [ridley.editor.state :as state]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.scene.registry :as registry]
            [ridley.scene.panel :as panel]
            [ridley.math :as math]))

;; ============================================================
;; Path validation
;; ============================================================

(def ^:private attach-only-cmds #{:inset :scale :move-to})

(defn- validate-extrude-path!
  "Throw if path contains commands that only make sense in attach context."
  [op-name path]
  (when-let [bad (first (filter #(attach-only-cmds (:cmd %)) (:commands path)))]
    (throw (js/Error. (str op-name ": '" (name (:cmd bad))
                          "' only works inside attach/attach-face, not " op-name)))))

;; ============================================================
;; Extrude family
;; ============================================================

(defn ^:export extrude-impl [shape path]
  (validate-extrude-path! "extrude" path)
  (ops/pure-extrude-path shape path))

(defn ^:export extrude-closed-impl [shape path]
  (validate-extrude-path! "extrude-closed" path)
  (ops/implicit-extrude-closed-path shape path))

;; ============================================================
;; Loft family
;; ============================================================

(defn ^:export loft-impl
  "Runtime dispatch for loft.
   2-arg: (loft-impl first-arg path) — shape-fn mode only.
   3-arg: (loft-impl first-arg second-arg path) — shape-fn, two-shape, or legacy."
  ([first-arg path]
   (validate-extrude-path! "loft" path)
   (if (sfn/shape-fn? first-arg)
     (ops/pure-loft-shape-fn first-arg path)
     (throw (js/Error. "loft: 2-arg form requires a shape-fn as first argument. For plain shapes use (loft shape transform-fn movements...)"))))
  ([first-arg second-arg path]
   (validate-extrude-path! "loft" path)
   (cond
     (sfn/shape-fn? first-arg)
     (ops/pure-loft-shape-fn first-arg path)

     (shape/shape? second-arg)
     (ops/pure-loft-two-shapes first-arg second-arg path)

     :else
     (ops/pure-loft-path first-arg second-arg path))))

(defn ^:export loft-n-impl
  "Runtime dispatch for loft-n (loft with custom step count)."
  ([steps first-arg path]
   (validate-extrude-path! "loft-n" path)
   (if (sfn/shape-fn? first-arg)
     (ops/pure-loft-shape-fn first-arg path steps)
     (throw (js/Error. "loft-n: 2-arg form requires a shape-fn as first argument"))))
  ([steps first-arg second-arg path]
   (validate-extrude-path! "loft-n" path)
   (cond
     (sfn/shape-fn? first-arg)
     (ops/pure-loft-shape-fn first-arg path steps)

     (shape/shape? second-arg)
     (ops/pure-loft-two-shapes first-arg second-arg path steps)

     :else
     (ops/pure-loft-path first-arg second-arg path steps))))

;; ============================================================
;; Bloft family
;; ============================================================

(defn ^:export bloft-impl
  "Runtime dispatch for bloft (bezier-safe loft)."
  ([first-arg path]
   (validate-extrude-path! "bloft" path)
   (if (sfn/shape-fn? first-arg)
     (ops/pure-bloft-shape-fn first-arg path)
     (throw (js/Error. "bloft: 2-arg form requires a shape-fn as first argument"))))
  ([first-arg second-arg path]
   (validate-extrude-path! "bloft" path)
   (cond
     (sfn/shape-fn? first-arg) (ops/pure-bloft-shape-fn first-arg path)
     (shape/shape? second-arg) (ops/pure-bloft-two-shapes first-arg second-arg path)
     :else (ops/pure-bloft first-arg second-arg path nil 0.1)))
  ([first-arg second-arg path steps]
   (validate-extrude-path! "bloft" path)
   (cond
     (sfn/shape-fn? first-arg) (ops/pure-bloft-shape-fn first-arg path steps)
     (shape/shape? second-arg) (ops/pure-bloft-two-shapes first-arg second-arg path steps)
     :else (ops/pure-bloft first-arg second-arg path steps 0.1)))
  ([first-arg second-arg path steps threshold]
   (validate-extrude-path! "bloft" path)
   (cond
     (sfn/shape-fn? first-arg) (ops/pure-bloft-shape-fn first-arg path steps threshold)
     (shape/shape? second-arg) (ops/pure-bloft-two-shapes first-arg second-arg path steps threshold)
     :else (ops/pure-bloft first-arg second-arg path steps threshold))))

(defn ^:export bloft-n-impl
  "Runtime dispatch for bloft-n (bloft with custom step count)."
  ([steps first-arg path]
   (validate-extrude-path! "bloft-n" path)
   (if (sfn/shape-fn? first-arg)
     (ops/pure-bloft-shape-fn first-arg path steps)
     (throw (js/Error. "bloft-n: 2-arg form requires a shape-fn as first argument"))))
  ([steps first-arg second-arg path]
   (validate-extrude-path! "bloft-n" path)
   (cond
     (sfn/shape-fn? first-arg) (ops/pure-bloft-shape-fn first-arg path steps)
     (shape/shape? second-arg) (ops/pure-bloft-two-shapes first-arg second-arg path steps)
     :else (ops/pure-bloft first-arg second-arg path steps 0.1))))

;; ============================================================
;; Revolve
;; ============================================================

(defn ^:export revolve-impl
  "Runtime dispatch for revolve."
  ([shape-or-fn]
   (if (sfn/shape-fn? shape-or-fn)
     (ops/pure-revolve-shape-fn shape-or-fn 360)
     (ops/pure-revolve shape-or-fn 360)))
  ([shape-or-fn angle]
   (if (sfn/shape-fn? shape-or-fn)
     (ops/pure-revolve-shape-fn shape-or-fn angle)
     (ops/pure-revolve shape-or-fn angle))))

;; ============================================================
;; Attach: helpers for move-to replay
;; ============================================================

(defn- resolve-mesh
  "Resolve a keyword/string/mesh to an actual mesh."
  [target]
  (if (and (map? target) (:vertices target))
    target
    (registry/get-mesh (if (keyword? target) target (keyword target)))))

(defn- compute-target-bounds
  "Compute bounding box for a target (mesh or name)."
  [target]
  (when-let [m (resolve-mesh target)]
    (when-let [vertices (seq (:vertices m))]
      (let [xs (map #(nth % 0) vertices)
            ys (map #(nth % 1) vertices)
            zs (map #(nth % 2) vertices)]
        {:center [(/ (+ (apply min xs) (apply max xs)) 2)
                  (/ (+ (apply min ys) (apply max ys)) 2)
                  (/ (+ (apply min zs) (apply max zs)) 2)]}))))

(defn- move-state-to-position
  "Move turtle state to a destination by decomposing into local-axis movements.
   Uses f/th/tv so that attached mesh vertices are transformed correctly."
  [state dest]
  (let [pos (:position state)
        heading (:heading state)
        up (:up state)
        right (math/cross heading up)
        delta (math/v- dest pos)
        d-fwd (math/dot delta heading)
        d-right (math/dot delta right)
        d-up (math/dot delta up)
        ;; Move along right axis (th -90, f, th 90)
        state (if-not (zero? d-right)
                (-> state (turtle/th -90) (turtle/f d-right) (turtle/th 90))
                state)
        ;; Move along forward axis
        state (if-not (zero? d-fwd)
                (turtle/f state d-fwd)
                state)
        ;; Move along up axis (tv 90, f, tv -90)
        state (if-not (zero? d-up)
                (-> state (turtle/tv 90) (turtle/f d-up) (turtle/tv -90))
                state)]
    state))

(defn- move-to-center
  "Move state to target's centroid, keeping current heading."
  [state target]
  (if-let [bounds (compute-target-bounds target)]
    (move-state-to-position state (:center bounds))
    state))

(defn- move-to-pose
  "Move state to target's creation-pose position, then adopt its heading/up.
   Falls back to centroid if no creation-pose."
  [state target]
  (let [mesh (resolve-mesh target)
        pose (when mesh (:creation-pose mesh))]
    (if pose
      (-> state
          (move-state-to-position (:position pose))
          (assoc :heading (:heading pose))
          (assoc :up (:up pose)))
      (move-to-center state target))))

(defn- move-to-dispatch
  "Handle :move-to command during replay."
  [state args]
  (let [target (first args)
        mode (second args)]
    (case mode
      :center (move-to-center state target)
      (move-to-pose state target))))

;; ============================================================
;; Attach: path replay
;; ============================================================

(defn- replay-path-commands
  "Replay path commands on a turtle state.
   Handles all command types including attach-specific ones (inset, scale, move-to)."
  [state path]
  (reduce (fn [s {:keys [cmd args]}]
            (case cmd
              :f  (turtle/f s (first args))
              :th (turtle/th s (first args))
              :tv (turtle/tv s (first args))
              :tr (turtle/tr s (first args))
              :u  (turtle/move-up s (first args))
              :rt (turtle/move-right s (first args))
              :lt (turtle/move-left s (first args))
              :set-heading (-> s
                               (assoc :heading (math/normalize (first args)))
                               (assoc :up (math/normalize (second args))))
              :inset (turtle/inset s (first args))
              :scale (turtle/scale s (first args))
              :move-to (move-to-dispatch s args)
              :mark s ;; no-op during replay
              s))
          state
          (:commands path)))

;; ============================================================
;; Attach impl functions
;; ============================================================

(defn- mesh-attach-impl
  "Attach to a single mesh and replay path. Returns transformed mesh."
  [mesh path]
  (let [state (-> (turtle/make-turtle)
                  (turtle/attach-move mesh))
        state (replay-path-commands state path)]
    (or (get-in state [:attached :mesh]) mesh)))

(defn- group-attach-impl
  "Attach to a vector of meshes (group transform). Returns transformed vector."
  [meshes path]
  (let [ref-pose (or (:creation-pose (first meshes))
                     {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
        p0 (:position ref-pose)
        h0 (:heading ref-pose)
        u0 (:up ref-pose)
        state (-> (turtle/make-turtle)
                  (assoc :position p0)
                  (assoc :heading h0)
                  (assoc :up u0))
        state (replay-path-commands state path)]
    (turtle/group-transform meshes p0 h0 u0
                            (:position state)
                            (:heading state)
                            (:up state))))

(defn- panel-attach-impl
  "Attach to a panel. Returns panel with updated position/heading/up."
  [p path]
  (let [state (turtle/make-turtle)
        state (replay-path-commands state path)]
    (assoc p
      :position (:position state)
      :heading (:heading state)
      :up (:up state))))

(defn- resolve-selection
  "If mesh-or-sel is a selection map (from (selected)), resolve mesh and face-id.
   Returns [mesh face-id]. If not a selection map, returns inputs unchanged."
  [mesh-or-sel face-id]
  (if (and (nil? face-id) (map? mesh-or-sel) (:name mesh-or-sel))
    [(registry/get-mesh (:name mesh-or-sel)) (:face-id mesh-or-sel)]
    [mesh-or-sel face-id]))

(defn ^:export attach-impl
  "Dispatch attach by target type: sequential, panel, single mesh, or selection map."
  [target path]
  (let [target (if (and (map? target) (:name target) (not (:vertices target)))
                 ;; Selection map → resolve to mesh
                 (registry/get-mesh (:name target))
                 target)]
    (cond
      (sequential? target) (group-attach-impl target path)
      (panel/panel? target) (panel-attach-impl target path)
      :else (mesh-attach-impl target path))))

(defn ^:export attach-face-impl
  "Attach to a face (move-only mode) and replay path. Returns modified mesh.
   mesh can be a selection map from (selected) when face-id is nil."
  [mesh face-id path]
  (let [[mesh face-id] (resolve-selection mesh face-id)
        state (-> (turtle/make-turtle)
                  (turtle/attach-face mesh face-id))
        state (replay-path-commands state path)]
    (or (get-in state [:attached :mesh]) mesh)))

(defn ^:export clone-face-impl
  "Attach to a face with extrusion (clone), replay path. Returns modified mesh.
   mesh can be a selection map from (selected) when face-id is nil."
  [mesh face-id path]
  (let [[mesh face-id] (resolve-selection mesh face-id)
        state (-> (turtle/make-turtle)
                  (turtle/attach-face-extrude mesh face-id))
        state (replay-path-commands state path)]
    (or (get-in state [:attached :mesh]) mesh)))

(defn ^:export attach!-impl
  "Attach to a registered mesh by keyword, replay path, re-register result."
  [kw path]
  (let [mesh (registry/get-mesh kw)]
    (when-not mesh
      (throw (js/Error. (str "attach! - no registered mesh named " kw))))
    (let [result (mesh-attach-impl mesh path)]
      (registry/register-mesh! kw result)
      (registry/refresh-viewport! false)
      result)))
