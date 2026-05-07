(ns ridley.editor.impl
  "Runtime *-impl functions for macro delegation.
   Extrude/loft/revolve macros become thin wrappers that call path + *-impl.
   Attach macros record movements into a path, then *-impl replays them."
  (:require [ridley.editor.operations :as ops]
            [ridley.editor.state :as state]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.attachment :as attachment]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.scene.registry :as registry]
            [ridley.scene.panel :as panel]
            [ridley.manifold.core :as manifold]
            [ridley.clipper.core :as clipper]
            [ridley.geometry.faces :as faces]
            [ridley.geometry.primitives :as primitives]
            [ridley.sdf.core :as sdf]
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
;; Extrude+ / Revolve+ (chainable variants)
;; ============================================================

(defn- derive-end-up [heading ref-up]
  (let [dot-hu (math/dot ref-up heading)
        up-raw (math/v- ref-up (math/v* heading dot-hu))
        m (math/magnitude up-raw)]
    (if (> m 0.001)
      (math/v* up-raw (/ 1.0 m))
      [0 0 1])))

(defn- current-pose []
  (let [t @@state/turtle-state-var]
    {:position (:position t) :heading (:heading t) :up (:up t)}))

(defn ^:export extrude+-impl
  "Like extrude-impl but returns {:mesh :end-face :start-face} for chaining."
  [shape-or-shapes path-data & {:keys [mark mark-cap]}]
  (validate-extrude-path! "extrude+" path-data)
  (let [shapes (if (and (vector? shape-or-shapes)
                        (seq shape-or-shapes)
                        (map? (first shape-or-shapes)))
                 shape-or-shapes
                 [shape-or-shapes])
        pose (current-pose)
        current-turtle @@state/turtle-state-var
        initial-state (if current-turtle
                        (-> (turtle/make-turtle)
                            (assoc :position (:position current-turtle))
                            (assoc :heading (:heading current-turtle))
                            (assoc :up (:up current-turtle))
                            (assoc :joint-mode (:joint-mode current-turtle))
                            (assoc :resolution (:resolution current-turtle))
                            (assoc :material (:material current-turtle)))
                        (turtle/make-turtle))
        start-up (derive-end-up (:heading pose) (or (:up pose) [0 0 1]))
        results (reduce
                 (fn [acc s]
                   (let [state (turtle/extrude-from-path initial-state s path-data)
                         mesh (last (:meshes state))]
                     (if mesh
                       (let [end-heading (:heading state)
                             end-pos (:position state)
                             end-up (derive-end-up end-heading (or (:up pose) [0 0 1]))]
                         (conj acc {:mesh (assoc mesh :creation-pose pose)
                                    :end-face {:shape s
                                               :pose {:pos end-pos
                                                      :heading end-heading
                                                      :up end-up}}
                                    :start-face {:shape s
                                                 :pose {:pos (:position pose)
                                                        :heading (:heading pose)
                                                        :up start-up}}}))
                       acc)))
                 []
                 shapes)
        result (if (= 1 (count results)) (first results) results)]
    (when (and mark mark-cap result)
      (let [face-pose (case mark-cap
                        :start-cap (:pose (:start-face result))
                        :end-cap (:pose (:end-face result))
                        nil)]
        (when face-pose
          (swap! state/mark-anchors assoc mark face-pose))))
    result))

(defn- compute-pivot-offset [s pivot]
  (let [pts (:points s)
        xs (map first pts)
        ys (map second pts)]
    (case pivot
      :right [(- (apply max xs)) 0]
      :left  [(- (apply min xs)) 0]
      :up    [0 (- (apply max ys))]
      :down  [0 (- (apply min ys))]
      [0 0])))

(defn- translate-mesh-3d [mesh offset]
  (update mesh :vertices
          (fn [vs] (mapv (fn [v] (math/v+ v offset)) vs))))

(defn ^:export revolve+-impl
  "Like revolve-impl but returns {:mesh :end-face} for chaining.
   Supports :pivot and :mark."
  ([shape-or-fn]
   (revolve+-impl shape-or-fn 360))
  ([shape-or-fn angle & {:keys [pivot mark mark-cap]}]
   (let [current-turtle @@state/turtle-state-var
         start-pose {:pos (:position current-turtle)
                     :heading (:heading current-turtle)
                     :up (:up current-turtle)}
         ;; Clip shape to x >= 0 if no pivot (prevents crossing revolution axis)
         shape-or-fn (if (and (not pivot) (shape/shape? shape-or-fn))
                       (let [min-x (apply min (map first (:points shape-or-fn)))]
                         (if (neg? min-x)
                           (let [max-x (apply max (map first (:points shape-or-fn)))
                                 max-y (apply max (map #(js/Math.abs (second %)) (:points shape-or-fn)))
                                 half (+ (max max-x max-y) 100)
                                 clip-rect (shape/make-shape [[0 (- half)] [half (- half)] [half half] [0 half]]
                                                             {:centered? true})]
                             (or (clipper/shape-intersection shape-or-fn clip-rect)
                                 shape-or-fn))
                           shape-or-fn))
                       shape-or-fn)
         ;; Compute pivot offset
         [dx dy] (if (and pivot (shape/shape? shape-or-fn))
                   (compute-pivot-offset shape-or-fn pivot)
                   [0 0])
         shifted-shape (if (and pivot (shape/shape? shape-or-fn))
                         (shape/translate-shape shape-or-fn dx dy)
                         shape-or-fn)
         ;; Do the revolve
         mesh (if (sfn/shape-fn? shifted-shape)
                (ops/pure-revolve-shape-fn shifted-shape angle)
                (ops/pure-revolve shifted-shape angle))]
     (when mesh
       (let [heading (:heading current-turtle)
             up (:up current-turtle)
             right (math/cross heading up)
             pivot-offset (if (or (not= dx 0) (not= dy 0))
                            (math/v+ (math/v* right (- dx)) (math/v* up (- dy)))
                            [0 0 0])
             mesh (if (or (not= dx 0) (not= dy 0))
                    (translate-mesh-3d mesh pivot-offset)
                    mesh)
             creation-pose {:position (:position current-turtle)
                            :heading heading :up up}
             mesh (assoc mesh :creation-pose creation-pose)
             result (if (>= (js/Math.abs angle) 360)
                      {:mesh mesh :start-face {:shape shape-or-fn :pose start-pose}}
                      (let [face-data (faces/face-shape mesh
                                                        (:id (faces/largest-face mesh :top)))]
                        {:mesh mesh
                         :start-face {:shape shape-or-fn :pose start-pose}
                         :end-face {:shape shape-or-fn
                                    :pose (:pose face-data)}}))]
         (when (and mark mark-cap)
           (let [face-pose (case mark-cap
                             :start-cap (:pose (:start-face result))
                             :end-cap (:pose (:end-face result))
                             nil)]
             (when face-pose
               (swap! state/mark-anchors assoc mark face-pose))))
         result)))))

;; ============================================================
;; transform-> : chainable pipeline
;; ============================================================

(defn- transform->step [prev-end step]
  (let [s (:shape prev-end)
        pose (:pose prev-end)
        op (:op step)
        args (:args step)
        mark (:mark step)
        mark-cap (:mark-cap step)
        saved @@state/turtle-state-var]
    (reset! @state/turtle-state-var
            (state/init-turtle pose saved))
    (let [result (try
                   (case op
                     :extrude+ (extrude+-impl s (first args))
                     :revolve+ (apply revolve+-impl s args)
                     (throw (js/Error. (str "transform->: unknown op " op))))
                   (finally
                     (reset! @state/turtle-state-var saved)))]
      (when (and mark mark-cap result)
        (let [face-pose (case mark-cap
                          :start-cap (:pose (:start-face result))
                          :end-cap (:pose (:end-face result))
                          nil)]
          (when face-pose
            (swap! state/mark-anchors assoc mark face-pose))))
      result)))

(defn ^:export transform->impl [shape-or-end-face steps]
  (let [initial-end (if (and (map? shape-or-end-face) (:shape shape-or-end-face) (:pose shape-or-end-face))
                      shape-or-end-face
                      (let [t @@state/turtle-state-var]
                        {:shape shape-or-end-face
                         :pose {:pos (:position t)
                                :heading (:heading t)
                                :up (:up t)}}))]
    (loop [remaining steps
           prev-end initial-end
           meshes []]
      (if (empty? remaining)
        (if (= 1 (count meshes))
          (first meshes)
          (apply manifold/union meshes))
        (let [result (transform->step prev-end (first remaining))
              mesh (:mesh result)
              end-face (:end-face result)]
          (recur (rest remaining)
                 (or end-face prev-end)
                 (conj meshes mesh)))))))

;; ============================================================
;; lay-flat
;; ============================================================

(defn- lay-flat-with-normal [mesh normal face-center-fn]
  (let [target [0.0 0.0 -1.0]
        dot-nt (math/dot normal target)
        vertices (:vertices mesh)
        rot-fn (if (> (js/Math.abs dot-nt) 0.9999)
                 (if (neg? dot-nt)
                   identity
                   (let [perp (if (> (js/Math.abs (nth normal 0)) 0.9) [0 1 0] [1 0 0])]
                     #(math/rotate-point-around-axis % perp js/Math.PI)))
                 (let [axis (math/normalize (math/cross normal target))
                       angle (js/Math.acos (max -1.0 (min 1.0 dot-nt)))]
                   #(math/rotate-point-around-axis % axis angle)))
        rotated-verts (mapv rot-fn vertices)
        ;; Align Z rotation using creation-pose heading
        cp-heading (or (get-in mesh [:creation-pose :heading]) [1.0 0.0 0.0])
        ref-rotated (rot-fn cp-heading)
        rx (nth ref-rotated 0)
        ry (nth ref-rotated 1)
        z-angle (if (> (+ (* rx rx) (* ry ry)) 0.001)
                  (- (js/Math.atan2 ry rx))
                  0.0)
        final-rotated (if (< (js/Math.abs z-angle) 0.001)
                        rotated-verts
                        (mapv #(math/rotate-point-around-axis % [0 0 1] z-angle) rotated-verts))
        center (face-center-fn final-rotated)
        offset [(- (center 0)) (- (center 1)) (- (center 2))]
        final-verts (mapv #(math/v+ % offset) final-rotated)]
    (-> mesh
        (assoc :vertices final-verts
               :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
        ;; Remove raw-arrays cache so viewport uses the transformed CLJS vertices
        (dissoc :ridley.manifold.core/raw-arrays :ridley.manifold.core/manifold-cache))))

(defn ^:export lay-flat-impl
  ([mesh] (lay-flat-impl mesh nil))
  ([mesh target]
   (cond
     ;; Anchor keyword from :mark
     (and (keyword? target)
          (not (#{:top :bottom :up :down :left :right} target)))
     (let [pose (or (get @state/mark-anchors target)
                    (get-in @@state/turtle-state-var [:anchors target]))]
       (if pose
         (lay-flat-with-normal mesh
                               (:heading pose)
                               (fn [rotated-verts]
                                 (let [normal (:heading pose)
                                       tgt [0.0 0.0 -1.0]
                                       dot-nt (math/dot normal tgt)
                                       rot-fn (if (> (js/Math.abs dot-nt) 0.9999)
                                                (if (neg? dot-nt)
                                                  identity
                                                  (let [perp (if (> (js/Math.abs (nth normal 0)) 0.9) [0 1 0] [1 0 0])]
                                                    #(math/rotate-point-around-axis % perp js/Math.PI)))
                                                (let [axis (math/normalize (math/cross normal tgt))
                                                      angle (js/Math.acos (max -1.0 (min 1.0 dot-nt)))]
                                                  #(math/rotate-point-around-axis % axis angle)))]
                                   (rot-fn (or (:pos pose) (:position pose))))))
         (throw (js/Error. (str "lay-flat: no anchor named " target)))))

     ;; Direction keyword
     (keyword? target)
     (let [mesh (faces/ensure-face-groups mesh)
           face (faces/largest-face mesh target)]
       (when face
         (let [info (faces/compute-face-info (:vertices mesh)
                                             (get (:face-groups mesh) (:id face)))
               face-indices (:vertices info)]
           (lay-flat-with-normal mesh (:normal info)
                                 (fn [rotated-verts]
                                   (let [fv (mapv #(nth rotated-verts %) face-indices)]
                                     (math/v* (reduce math/v+ fv) (/ 1.0 (count fv)))))))))

     ;; Default: bottom
     :else
     (lay-flat-impl mesh :bottom))))

;; ============================================================
;; Attach: helpers for move-to replay
;; ============================================================

(defn- resolve-mesh
  "Resolve a keyword/string/mesh/path to an actual mesh.
   For path objects, returns a virtual mesh whose `:anchors` are the path's
   marks resolved at the world origin — useful when a skeleton path is used
   directly as a target for `move-to`, without registering a carrier mesh."
  [target]
  (cond
    (and (map? target) (:vertices target))
    target

    (and (map? target) (= :path (:type target)))
    {:type :path-as-mesh
     :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}
     :anchors (turtle/resolve-marks
               {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}
               target)}

    :else
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

(defn- align-attached-mesh
  "Rotate the attached mesh in place so its current heading/up align with target heading/up.
   Pivots around `pivot` (typically the anchor position after translation).
   Uses a 2-step rotation: first align headings, then align ups around the new heading.
   Returns the updated state with mesh rotated and anchors transformed accordingly."
  [state cur-h cur-u tgt-h tgt-u pivot]
  (let [mesh (get-in state [:attached :mesh])
        ;; Step 1: align headings
        dot-h (math/dot cur-h tgt-h)
        cross-h (math/cross cur-h tgt-h)
        [s1-axis s1-angle] (cond
                             (>= dot-h 0.9999)  [nil 0]                                ; already aligned
                             (<= dot-h -0.9999) [(math/normalize cur-u) Math/PI]       ; antiparallel: 180° around current up
                             :else              [(math/normalize cross-h) (Math/acos dot-h)])
        mesh1 (if s1-axis (attachment/rotate-mesh-around-point mesh s1-axis s1-angle pivot) mesh)
        cur-u' (if s1-axis (math/rotate-around-axis cur-u s1-axis s1-angle) cur-u)
        ;; Step 2: align ups by rotating around the now-aligned heading (= tgt-h)
        dot-u (math/dot cur-u' tgt-u)
        cross-u (math/cross cur-u' tgt-u)
        sign (if (>= (math/dot cross-u tgt-h) 0) 1 -1)
        [s2-axis s2-angle] (cond
                             (>= dot-u 0.9999)  [nil 0]
                             (<= dot-u -0.9999) [tgt-h Math/PI]
                             :else              [tgt-h (* sign (Math/acos dot-u))])
        mesh2 (if s2-axis (attachment/rotate-mesh-around-point mesh1 s2-axis s2-angle pivot) mesh1)]
    (-> state
        (turtle/replace-mesh-in-state mesh mesh2)
        (assoc-in [:attached :mesh] mesh2))))

(defn- move-to-anchor
  "Move state to target mesh's named anchor, adopting its heading/up.
   Anchors come from attach-path. With :align? true, also rotates the
   attached mesh so its frame matches the anchor's frame."
  [state target anchor-name & {:keys [align?]}]
  (let [mesh (resolve-mesh target)
        anchor (when mesh (get-in mesh [:anchors anchor-name]))]
    (if anchor
      (let [tgt-pos (:position anchor)
            tgt-h   (:heading anchor)
            tgt-u   (:up anchor)
            translated (move-state-to-position state tgt-pos)
            aligned (if (and align? (:attached translated))
                      (align-attached-mesh translated
                                           (:heading translated) (:up translated)
                                           tgt-h tgt-u tgt-pos)
                      translated)]
        (-> aligned
            (assoc :heading tgt-h)
            (assoc :up tgt-u)))
      (throw (js/Error. (str "move-to: no anchor " anchor-name " on mesh " target))))))

(defn- move-to-dispatch
  "Handle :move-to command during replay.
   Supports:
     (move-to :name)                       — snap to creation-pose, adopt orientation
     (move-to :name :center)               — snap to centroid
     (move-to :name :at :anchor)           — snap to anchor (translate only)
     (move-to :name :at :anchor :align)    — snap to anchor and rotate mesh to match"
  [state args]
  (let [target (first args)
        mode (second args)]
    (cond
      (and (= mode :at) (= (nth args 3 nil) :align))
      (move-to-anchor state target (nth args 2) :align? true)

      (= mode :at)     (move-to-anchor state target (nth args 2))
      (= mode :center) (move-to-center state target)
      :else            (move-to-pose state target))))

;; ============================================================
;; Creation-pose shift (@ commands)
;; ============================================================

(defn- shift-creation-pose
  "Relocate the geometry of the attached mesh along an axis while keeping the
   creation-pose fixed in world. After `(cp-f n)`, the point that was at +n
   along heading from the original anchor coincides with the (unchanged) anchor.
   Vertices and named anchors translate by `-offset`; creation-pose is untouched.
   axis: :f (heading), :rt (right), :u (up). dist: distance to shift."
  [state axis dist]
  (if-let [attached (:attached state)]
    (let [mesh (:mesh attached)
          pose (or (:creation-pose mesh) {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
          h (math/normalize (:heading pose))
          u (math/normalize (:up pose))
          r (math/normalize (math/cross h u))
          dir (case axis :f h :rt r :u u h)
          offset (math/v* dir dist)
          new-mesh (attachment/translate-vertices-keeping-anchor mesh (math/v* offset -1))]
      (-> state
          (turtle/replace-mesh-in-state mesh new-mesh)
          (assoc-in [:attached :mesh] new-mesh)))
    state))

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
              :mark (let [nm (first args)
                          pose {:position (:position s)
                                :heading (:heading s)
                                :up (:up s)}]
                      (if (get-in s [:attached :mesh])
                        ;; Record anchor on the attached mesh (mirrors SDF attach
                        ;; behavior). When not attached to a mesh, mark is a no-op.
                        (assoc-in s [:attached :mesh :anchors nm] pose)
                        s))
              ;; cp-* commands: shift creation-pose without moving vertices
              :cp-f  (shift-creation-pose s :f (first args))
              :cp-rt (shift-creation-pose s :rt (first args))
              :cp-u  (shift-creation-pose s :u (first args))
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

;; ============================================================
;; SDF attach
;; ============================================================

(def ^:private sdf-attach-rejected
  "Path commands not meaningful when attaching an SDF: only mesh-face
   ops (inset) and the legacy turtle-mesh scale command."
  {:inset "inset is mesh-face-specific, not applicable to SDFs"
   :scale "(scale n) inside attach is mesh-only; for SDF use (scale sdf factor) outside attach"})

(defn- validate-sdf-attach-path! [path]
  (when-let [bad (first (filter #(contains? sdf-attach-rejected (:cmd %)) (:commands path)))]
    (throw (js/Error. (str "attach on SDF: '" (name (:cmd bad)) "' — "
                           (sdf-attach-rejected (:cmd bad)))))))

(defn- sdf-rotate-around-point
  "Rotate an SDF around an arbitrary axis through `pivot` by angle (radians)."
  [sdf-node axis angle-rad pivot]
  (let [[px py pz] pivot
        angle-deg (* angle-rad (/ 180 Math/PI))]
    (-> sdf-node
        (sdf/sdf-move (- px) (- py) (- pz))
        (sdf/sdf-rotate axis angle-deg)
        (sdf/sdf-move px py pz))))

(defn- sdf-align
  "Two-step rotation taking the (cur-h, cur-u) frame to (tgt-h, tgt-u),
   pivoted at `pivot`. Mirrors align-attached-mesh."
  [sdf-node cur-h cur-u tgt-h tgt-u pivot]
  (let [dot-h (math/dot cur-h tgt-h)
        cross-h (math/cross cur-h tgt-h)
        [s1-axis s1-angle] (cond
                             (>= dot-h 0.9999)  [nil 0]
                             (<= dot-h -0.9999) [(math/normalize cur-u) Math/PI]
                             :else              [(math/normalize cross-h) (Math/acos dot-h)])
        sdf1 (if s1-axis (sdf-rotate-around-point sdf-node s1-axis s1-angle pivot) sdf-node)
        cur-u' (if s1-axis (math/rotate-around-axis cur-u s1-axis s1-angle) cur-u)
        dot-u (math/dot cur-u' tgt-u)
        cross-u (math/cross cur-u' tgt-u)
        sign (if (>= (math/dot cross-u tgt-h) 0) 1 -1)
        [s2-axis s2-angle] (cond
                             (>= dot-u 0.9999)  [nil 0]
                             (<= dot-u -0.9999) [tgt-h Math/PI]
                             :else              [tgt-h (* sign (Math/acos dot-u))])]
    (if s2-axis (sdf-rotate-around-point sdf1 s2-axis s2-angle pivot) sdf1)))

(defn- resolve-anchor-source
  "Resolve a target reference (keyword, mesh, or SDF) to something that
   can carry :anchors / :creation-pose."
  [target]
  (cond
    (keyword? target) (registry/get-mesh target)
    (and (map? target) (or (:vertices target) (:anchors target) (sdf/sdf-node? target)))
    target
    :else nil))

(defn- translate-sdf-to-position
  "Translate the SDF by the world-space delta needed to take the turtle
   from its current position to dest. Mirrors what move-state-to-position
   achieves on meshes (which goes through turtle commands that drag the
   attached mesh along)."
  [sdf state dest]
  (let [[cx cy cz] (:position state)
        [dx dy dz] dest]
    (sdf/sdf-move sdf (- dx cx) (- dy cy) (- dz cz))))

(defn- sdf-move-to
  "Handle :move-to inside an SDF attach. Returns [new-state new-sdf]."
  [state sdf args]
  (let [target (first args)
        mode (second args)
        target-obj (resolve-anchor-source target)]
    (cond
      (= mode :at)
      (let [anchor-name (nth args 2)
            align? (= (nth args 3 nil) :align)
            anchor (when target-obj (get-in target-obj [:anchors anchor-name]))]
        (when-not anchor
          (throw (js/Error. (str "move-to: no anchor " anchor-name " on " target))))
        (let [tgt-pos (:position anchor)
              tgt-h   (:heading anchor)
              tgt-u   (:up anchor)
              sdf-translated (translate-sdf-to-position sdf state tgt-pos)
              sdf' (if align?
                     (sdf-align sdf-translated (:heading state) (:up state) tgt-h tgt-u tgt-pos)
                     sdf-translated)]
          [(-> state
               (assoc :position tgt-pos)
               (assoc :heading tgt-h)
               (assoc :up tgt-u))
           sdf']))

      (= mode :center)
      (let [centroid (when (and target-obj (:vertices target-obj))
                       (let [vs (:vertices target-obj)
                             xs (map first vs) ys (map second vs) zs (map #(nth % 2) vs)]
                         [(/ (+ (apply min xs) (apply max xs)) 2)
                          (/ (+ (apply min ys) (apply max ys)) 2)
                          (/ (+ (apply min zs) (apply max zs)) 2)]))]
        (if centroid
          [(assoc state :position centroid)
           (translate-sdf-to-position sdf state centroid)]
          [state sdf]))

      :else
      (let [pose (when target-obj (:creation-pose target-obj))]
        (if pose
          [(-> state
               (assoc :position (:position pose))
               (assoc :heading (:heading pose))
               (assoc :up (:up pose)))
           (translate-sdf-to-position sdf state (:position pose))]
          [state sdf])))))

(defn- sdf-attach-impl
  "Attach to an SDF: walk the path commands, transforming the SDF
   incrementally. Each command updates the turtle state and (where
   relevant) the SDF tree:

   - f / b / rt / u / lt / d: translate the SDF by the corresponding
     world-space delta and advance the turtle.
   - th / tv / tr: rotate the SDF around the current turtle position
     using the appropriate axis (up, right, heading), and rotate the
     turtle frame.
   - cp-f / cp-rt / cp-u: translate the SDF by the OPPOSITE of the
     direction (anchor stays put in world; geometry slides under).
   - mark: record the current turtle pose as a named anchor on the SDF.
   - move-to: snap the turtle to the target anchor / centroid / pose.
     With :align, also rotate the SDF so its frame matches the anchor.
   - set-heading: replace turtle heading/up directly (frame change only,
     SDF unchanged).
   - inset / scale (legacy turtle form): rejected with explanatory error."
  [sdf-node path]
  (validate-sdf-attach-path! path)
  (loop [state (turtle/make-turtle)
         ;; Give the SDF a default :creation-pose at the turtle origin if it
         ;; doesn't have one yet. cp-* leans on creation-pose to mean "anchor
         ;; that stays put while the geometry slides under it" — without an
         ;; initial pose, that semantic has nothing to anchor against.
         sdf (if (:creation-pose sdf-node)
               sdf-node
               (assoc sdf-node :creation-pose
                      {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}))
         remaining (:commands path)]
    (if (empty? remaining)
      sdf
      (let [{:keys [cmd args]} (first remaining)
            rest-cmds (rest remaining)
            apply-translation
            (fn [state' sdf']
              (let [delta (mapv - (:position state') (:position state))
                    [dx dy dz] delta]
                [state' (sdf/sdf-move sdf' dx dy dz)]))]
        (case cmd
          :f  (let [[s' sdf'] (apply-translation (turtle/f state (first args)) sdf)]
                (recur s' sdf' rest-cmds))
          :u  (let [[s' sdf'] (apply-translation (turtle/move-up state (first args)) sdf)]
                (recur s' sdf' rest-cmds))
          :rt (let [[s' sdf'] (apply-translation (turtle/move-right state (first args)) sdf)]
                (recur s' sdf' rest-cmds))
          :lt (let [[s' sdf'] (apply-translation (turtle/move-left state (first args)) sdf)]
                (recur s' sdf' rest-cmds))

          :th (let [α (first args)
                    pivot (:position state)
                    axis (:up state)
                    rad (* α (/ Math/PI 180))
                    sdf' (sdf-rotate-around-point sdf axis rad pivot)
                    state' (turtle/th state α)]
                (recur state' sdf' rest-cmds))

          :tv (let [α (first args)
                    pivot (:position state)
                    [hx hy hz] (:heading state)
                    [ux uy uz] (:up state)
                    ;; right = heading × up (turtle convention, see turtle/core.cljs).
                    axis [(- (* hy uz) (* hz uy))
                          (- (* hz ux) (* hx uz))
                          (- (* hx uy) (* hy ux))]
                    rad (* α (/ Math/PI 180))
                    sdf' (sdf-rotate-around-point sdf axis rad pivot)
                    state' (turtle/tv state α)]
                (recur state' sdf' rest-cmds))

          :tr (let [α (first args)
                    pivot (:position state)
                    axis (:heading state)
                    rad (* α (/ Math/PI 180))
                    sdf' (sdf-rotate-around-point sdf axis rad pivot)
                    state' (turtle/tr state α)]
                (recur state' sdf' rest-cmds))

          :set-heading (recur (-> state
                                  (assoc :heading (math/normalize (first args)))
                                  (assoc :up (math/normalize (second args))))
                              sdf rest-cmds)

          :cp-f (let [n (first args)
                      [hx hy hz] (math/normalize (:heading state))
                      sdf' (sdf/sdf-move-keeping-creation-pose
                            sdf (- (* n hx)) (- (* n hy)) (- (* n hz)))]
                  (recur state sdf' rest-cmds))

          :cp-rt (let [n (first args)
                       [hx hy hz] (:heading state)
                       [ux uy uz] (:up state)
                       ;; right = heading × up (turtle convention).
                       rx (- (* hy uz) (* hz uy))
                       ry (- (* hz ux) (* hx uz))
                       rz (- (* hx uy) (* hy ux))
                       sdf' (sdf/sdf-move-keeping-creation-pose
                             sdf (- (* n rx)) (- (* n ry)) (- (* n rz)))]
                   (recur state sdf' rest-cmds))

          :cp-u (let [n (first args)
                      [ux uy uz] (math/normalize (:up state))
                      sdf' (sdf/sdf-move-keeping-creation-pose
                            sdf (- (* n ux)) (- (* n uy)) (- (* n uz)))]
                  (recur state sdf' rest-cmds))

          :mark (let [nm (first args)
                      pose {:position (:position state)
                            :heading (:heading state)
                            :up (:up state)}
                      sdf' (assoc-in sdf [:anchors nm] pose)]
                  (recur state sdf' rest-cmds))

          :move-to (let [[s' sdf'] (sdf-move-to state sdf args)]
                     (recur s' sdf' rest-cmds))

          ;; Default: ignore unknown commands silently (matches the mesh path).
          (recur state sdf rest-cmds))))))

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
  "Dispatch attach by target type: sequential, panel, single mesh, SDF, or selection map."
  [target path]
  (let [target (if (and (map? target) (:name target) (not (:vertices target))
                        (not (sdf/sdf-node? target)))
                 ;; Selection map → resolve to mesh
                 (registry/get-mesh (:name target))
                 target)]
    (cond
      (sdf/sdf-node? target) (sdf-attach-impl target path)
      (sequential? target) (group-attach-impl target path)
      (panel/panel? target) (panel-attach-impl target path)
      :else (mesh-attach-impl target path))))

(defn ^:export attach-face-impl
  "Attach to a face (move-only mode) and replay path. Returns modified mesh.
   mesh can be a selection map from (selected) when face-id is nil."
  [mesh face-id path]
  (let [[mesh face-id] (resolve-selection mesh face-id)
        mesh (faces/ensure-face-groups mesh)
        state (-> (turtle/make-turtle)
                  (turtle/attach-face mesh face-id))
        state (replay-path-commands state path)]
    (or (get-in state [:attached :mesh]) mesh)))

(defn ^:export clone-face-impl
  "Attach to a face with extrusion (clone), replay path. Returns modified mesh.
   mesh can be a selection map from (selected) when face-id is nil."
  [mesh face-id path]
  (let [[mesh face-id] (resolve-selection mesh face-id)
        mesh (faces/ensure-face-groups mesh)
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

(defn ^:export set-creation-pose!-impl
  "Move the creation-pose of a registered mesh without moving its vertices.
   Replays path commands starting from the current creation-pose to get a new pose,
   then updates only the creation-pose (and heading/up) on the mesh."
  [kw path]
  (let [mesh (registry/get-mesh kw)]
    (when-not mesh
      (throw (js/Error. (str "set-creation-pose! - no registered mesh named " kw))))
    (let [pose (or (:creation-pose mesh)
                   {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
          ;; Create turtle at current creation-pose, replay commands
          state (-> (turtle/make-turtle)
                    (assoc :position (:position pose))
                    (assoc :heading (:heading pose))
                    (assoc :up (:up pose)))
          state (replay-path-commands state path)
          ;; Build new creation-pose from final turtle state
          new-pose {:position (:position state)
                    :heading  (:heading state)
                    :up       (:up state)}
          result (assoc mesh :creation-pose new-pose)]
      (registry/register-mesh! kw result)
      (registry/refresh-viewport! false)
      result)))

;; ============================================================
;; Chamfer/fillet on mesh edges (CSG approach)
;; ============================================================

(defn ^:export chamfer-edges-impl
  "Chamfer sharp edges of a mesh by CSG subtraction.
   Generates prisms along each sharp edge, unions them into one solid,
   then subtracts from the original mesh.

   distance: chamfer size in mm
   Options:
   - :angle  minimum dihedral angle in degrees (default 80)
   - :where  predicate fn on [x y z] vertex positions (both endpoints must match)"
  [mesh distance & {:keys [angle where debug] :or {angle 80}}]
  (when-let [prisms (faces/chamfer-prisms mesh distance :angle angle :where where)]
    (if debug
      ;; Debug: return first prism as-is for visual inspection
      (first prisms)
      ;; Apply each prism cut individually
      (reduce (fn [current-mesh prism]
                (or (manifold/difference current-mesh prism)
                    current-mesh))
              mesh
              prisms))))

(defn- direction-vec
  "Resolve a turtle-oriented direction keyword to a 3D unit vector
   using the mesh's creation-pose."
  [mesh direction]
  (let [pose (or (:creation-pose mesh)
                 {:heading [1 0 0] :up [0 0 1]})
        h (:heading pose)
        u (:up pose)
        ;; right = cross(heading, up)
        r [(- (* (nth h 1) (nth u 2)) (* (nth h 2) (nth u 1)))
           (- (* (nth h 2) (nth u 0)) (* (nth h 0) (nth u 2)))
           (- (* (nth h 0) (nth u 1)) (* (nth h 1) (nth u 0)))]]
    (case direction
      :top h
      :bottom (mapv - h)
      :up u
      :down (mapv - u)
      :right r
      :left (mapv - r)
      :all nil)))

(defn- dot3 [a b]
  (+ (* (nth a 0) (nth b 0))
     (* (nth a 1) (nth b 1))
     (* (nth a 2) (nth b 2))))

(defn ^:export chamfer-impl
  "Chamfer edges selected by turtle-oriented direction.

   direction: :top :bottom :up :down :left :right :all
   distance: chamfer size in mm
   Options:
   - :angle      minimum dihedral angle (default 80)
   - :min-radius exclude edges closer than r to the extrusion axis
   - :where      additional predicate on vertex positions"
  [mesh direction distance & {:keys [angle min-radius where] :or {angle 80}}]
  (let [dir-vec (direction-vec mesh direction)
        ;; Threshold for normal alignment (cos ~30° ≈ 0.85)
        align-threshold 0.85
        ;; Build the :where predicate combining direction + min-radius + custom where
        pose (or (:creation-pose mesh)
                 {:heading [1 0 0] :up [0 0 1] :position [0 0 0]})
        origin (:position pose)
        heading (:heading pose)
        ;; For min-radius: project vertex onto plane perpendicular to heading
        ;; Add 1% tolerance to min-radius to handle floating-point edge cases
        ;; (vertices exactly on the boundary are excluded)
        radius-check (when min-radius
                       (let [r2 (* min-radius 1.01 min-radius 1.01)]
                         (fn [p]
                           (let [;; Vector from origin to point
                                 v (mapv - p origin)
                                 ;; Project onto heading to get axial component
                                 axial (dot3 v heading)
                                 ;; Subtract axial component to get radial
                                 radial (mapv - v (mapv #(* axial %) heading))
                                 dist2 (dot3 radial radial)]
                             (> dist2 r2)))))
        combined-where (fn [p]
                         (and (or (nil? radius-check) (radius-check p))
                              (or (nil? where) (where p))))]
    ;; Find sharp edges, then filter by direction
    (when-let [edges (faces/find-sharp-edges mesh :angle angle :where combined-where)]
      (let [;; Filter edges by direction: one of the normals must align with dir-vec
            dir-edges (if dir-vec
                        (filterv (fn [{:keys [normals]}]
                                   (let [[n1 n2] normals]
                                     (or (> (dot3 n1 dir-vec) align-threshold)
                                         (> (dot3 n2 dir-vec) align-threshold))))
                                 edges)
                        edges)]
        (when (seq dir-edges)
          ;; Build strip mesh from pre-filtered edges, then subtract
          (let [strip (faces/build-chamfer-strip dir-edges distance)]
            (if strip
              (or (manifold/difference mesh strip) mesh)
              ;; Fallback to sequential prisms if strip fails
              (let [prisms (mapv (fn [{:keys [positions normals]}]
                                   (let [[p0 p1] positions
                                         [n1 n2] normals]
                                     (faces/make-prism-along-edge p0 p1 n1 n2 distance)))
                                 dir-edges)]
                (reduce (fn [current-mesh prism]
                          (or (manifold/difference current-mesh prism)
                              current-mesh))
                        mesh
                        prisms)))))))))

(defn ^:export fillet-impl
  "Fillet (round) edges selected by turtle-oriented direction.
   Same API as chamfer but produces rounded edges instead of flat cuts.

   direction: :top :bottom :up :down :left :right :all
   radius: fillet radius in mm
   Options:
   - :angle           minimum dihedral angle (default 80)
   - :min-radius      exclude edges closer than r to the extrusion axis
   - :segments        arc resolution (default 8)
   - :where           additional predicate on vertex positions
   - :blend-vertices  spherical blend at corners where 3+ faces meet (default false)"
  [mesh direction radius & {:keys [angle min-radius segments where blend-vertices]
                            :or {angle 80 segments 8 blend-vertices false}}]
  (let [dir-vec (direction-vec mesh direction)
        align-threshold 0.85
        pose (or (:creation-pose mesh)
                 {:heading [1 0 0] :up [0 0 1] :position [0 0 0]})
        origin (:position pose)
        heading (:heading pose)
        radius-check (when min-radius
                       (let [r2 (* min-radius 1.01 min-radius 1.01)]
                         (fn [p]
                           (let [v (mapv - p origin)
                                 axial (dot3 v heading)
                                 radial (mapv - v (mapv #(* axial %) heading))
                                 dist2 (dot3 radial radial)]
                             (> dist2 r2)))))
        combined-where (fn [p]
                         (and (or (nil? radius-check) (radius-check p))
                              (or (nil? where) (where p))))]
    (when-let [edges (faces/find-sharp-edges mesh :angle angle :where combined-where)]
      (let [dir-edges (if dir-vec
                        (filterv (fn [{:keys [normals]}]
                                   (let [[n1 n2] normals]
                                     (or (> (dot3 n1 dir-vec) align-threshold)
                                         (> (dot3 n2 dir-vec) align-threshold))))
                                 edges)
                        edges)]
        (when (seq dir-edges)
          ;; Fillet = per-edge concave cutters + vertex sphere cutters
          (let [cutters (faces/build-fillet-cutters dir-edges radius segments)
                ;; Apply edge cutters
                edge-result (if (seq cutters)
                              (reduce (fn [m cutter]
                                        (or (manifold/difference m cutter) m))
                                      mesh cutters)
                              mesh)
                ;; Apply vertex sphere cutters at corners where 3+ faces meet
                fillet-verts (when blend-vertices
                               (faces/find-fillet-vertices dir-edges))]
            (if (seq fillet-verts)
              (reduce
               (fn [m {:keys [position normals]}]
                 (let [center (faces/compute-fillet-vertex-center position normals radius)
                        ;; Sphere at fillet center
                       sphere (-> (primitives/sphere-mesh radius segments (max 6 (quot segments 2)))
                                  (update :vertices
                                          (fn [vs] (mapv (fn [[x y z]]
                                                           [(+ x (nth center 0))
                                                            (+ y (nth center 1))
                                                            (+ z (nth center 2))]) vs))))
                        ;; Box covering the corner: from center to vertex+margin
                       margin (* radius 0.5)
                       sum-n (reduce (fn [[ax ay az] [bx by bz]]
                                       [(+ ax bx) (+ ay by) (+ az bz)])
                                     normals)
                       extent [(+ (nth position 0) (* (nth sum-n 0) margin))
                               (+ (nth position 1) (* (nth sum-n 1) margin))
                               (+ (nth position 2) (* (nth sum-n 2) margin))]
                       [mnx mny mnz] [(min (nth center 0) (nth extent 0))
                                      (min (nth center 1) (nth extent 1))
                                      (min (nth center 2) (nth extent 2))]
                       [mxx mxy mxz] [(max (nth center 0) (nth extent 0))
                                      (max (nth center 1) (nth extent 1))
                                      (max (nth center 2) (nth extent 2))]
                       corner-box {:type :mesh
                                   :vertices [[mnx mny mnz] [mxx mny mnz] [mxx mxy mnz] [mnx mxy mnz]
                                              [mnx mny mxz] [mxx mny mxz] [mxx mxy mxz] [mnx mxy mxz]]
                                   :faces [[0 2 1] [0 3 2] [4 5 6] [4 6 7]
                                           [0 1 5] [0 5 4] [2 3 7] [2 7 6]
                                           [1 2 6] [1 6 5] [0 4 7] [0 7 3]]}
                        ;; Vertex cutter = box - sphere
                       vertex-cutter (manifold/difference corner-box sphere)]
                   (if vertex-cutter
                     (or (manifold/difference m vertex-cutter) m)
                     m)))
               edge-result fillet-verts)
              edge-result)))))))

