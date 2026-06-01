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
;; Revolve
;; ============================================================

(defn- shape-fn-ring-points
  "Union of a shape-fn's 2D profile points over EXACTLY the ring parameters a
   revolve of `angle`° will build (t = i/steps). Measures the whole sweep —
   including non-monotonic extents, e.g. (twisted (rect 1 10) 180) is 1 wide at
   t=0 and t=1 but ~10 wide at t=0.5 — so axis tests cover every built ring."
  [sf angle turtle]
  (let [steps (turtle/calc-arc-steps turtle (* 2 Math/PI) (js/Math.abs angle))
        n-rings (if (>= (js/Math.abs angle) 360) steps (inc steps))]
    (mapcat #(:points (sf (/ (double %) steps))) (range n-rings))))

(defn- revolve-clear-axis
  "Plain shapes are clipped to x>=0 in pure-revolve so they don't cross the
   revolution axis; a shape-fn can't be clipped per-ring without changing the
   ring's point count, so when its profile STRADDLES the axis (points on both
   sides of x=0, over any built ring) we shift it instead — an implicit
   :pivot :left — so the whole sweep clears the axis and the revolve stays
   manifold. A shape-fn already on one side (e.g. user-translated, or an
   explicit pivot) is left untouched."
  [sf angle]
  (let [pts (shape-fn-ring-points sf angle @@state/turtle-state-var)
        min-x (apply min (map first pts))
        max-x (apply max (map first pts))]
    (if (and (neg? min-x) (pos? max-x))
      (sfn/shape-fn sf (fn [s _t] (shape/translate-shape s (- min-x) 0)))
      sf)))

(defn ^:export revolve-impl
  "Runtime dispatch for revolve."
  ([shape-or-fn] (revolve-impl shape-or-fn 360))
  ([shape-or-fn angle]
   (if (sfn/shape-fn? shape-or-fn)
     (ops/pure-revolve-shape-fn (revolve-clear-axis shape-or-fn angle) angle)
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
         ;; Compute pivot offset. Works for both plain shapes and shape-fns:
         ;; for a shape-fn we measure the UNION of its profile over EXACTLY the
         ;; ring parameters the revolve will build (t = i/steps), then compose a
         ;; translate onto every evaluated ring so the whole sweep clears the
         ;; axis. Sampling only the endpoints is not enough — the horizontal
         ;; extent can peak mid-sweep and be non-monotonic (e.g.
         ;; (twisted (rect 1 10) 180) is 1 wide at t=0 and t=1 but 10 wide at
         ;; t=0.5). Matching the ring t's guarantees every built ring is off
         ;; the axis, not just luckily-sampled ones.
         [dx dy] (cond
                   (and pivot (shape/shape? shape-or-fn))
                   (compute-pivot-offset shape-or-fn pivot)
                   (and pivot (sfn/shape-fn? shape-or-fn))
                   (compute-pivot-offset
                    {:points (shape-fn-ring-points shape-or-fn angle current-turtle)}
                    pivot)
                   :else [0 0])
         shifted-shape (cond
                         (and pivot (shape/shape? shape-or-fn))
                         (shape/translate-shape shape-or-fn dx dy)
                         (and pivot (sfn/shape-fn? shape-or-fn))
                         (sfn/shape-fn shape-or-fn
                                       (fn [s _t] (shape/translate-shape s dx dy)))
                         :else shape-or-fn)
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
             ;; Analytic end-pose computation. Face-detection via
             ;; `largest-face :top` biases toward +heading, so negative
             ;; revolution angles (which place the end-face on the
             ;; -heading side) silently picked the start-face. Compute
             ;; the end-pose directly from the rotation instead.
             angle-rad (* angle (/ Math/PI 180))
             cos-a (Math/cos angle-rad)
             sin-a (Math/sin angle-rad)
             centroid (if (sfn/shape-fn? shifted-shape)
                        (let [end-shape (shifted-shape 1)
                              pts (:points end-shape)
                              n (count pts)]
                          (if (zero? n)
                            [0 0]
                            [(/ (reduce + (map first pts)) n)
                             (/ (reduce + (map second pts)) n)]))
                        (let [pts (:points shifted-shape)
                              n (count pts)]
                          (if (zero? n)
                            [0 0]
                            [(/ (reduce + (map first pts)) n)
                             (/ (reduce + (map second pts)) n)])))
             [sx sy] centroid
             ;; r(θ) — direction of shape's x-axis after rotation by θ
             rx (+ (* cos-a (right 0)) (* sin-a (heading 0)))
             ry (+ (* cos-a (right 1)) (* sin-a (heading 1)))
             rz (+ (* cos-a (right 2)) (* sin-a (heading 2)))
             [tpx tpy tpz] (:position current-turtle)
             end-pos [(+ tpx (* sy (up 0)) (* sx rx) (pivot-offset 0))
                      (+ tpy (* sy (up 1)) (* sx ry) (pivot-offset 1))
                      (+ tpz (* sy (up 2)) (* sx rz) (pivot-offset 2))]
             ;; Rotate heading by angle around up (k · v = 0 since
             ;; heading ⊥ up, so Rodrigues collapses to:
             ;;   v' = cos·heading − sin·right)
             end-heading [(- (* cos-a (heading 0)) (* sin-a (right 0)))
                          (- (* cos-a (heading 1)) (* sin-a (right 1)))
                          (- (* cos-a (heading 2)) (* sin-a (right 2)))]
             end-pose {:pos end-pos :heading end-heading :up up}
             result (if (>= (js/Math.abs angle) 360)
                      {:mesh mesh :start-face {:shape shape-or-fn :pose start-pose}}
                      {:mesh mesh
                       :start-face {:shape shape-or-fn :pose start-pose}
                       :end-face {:shape shape-or-fn :pose end-pose}})]
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

(defn- anchor-pose-from-context
  "If target is a keyword matching a named anchor on the current turtle
   (set by `with-path` or `attach-path`) or in the top-level mark-anchors
   atom (set by `mark` outside `attach`), return its pose map. Else nil."
  [target]
  (when (keyword? target)
    (or (get @state/mark-anchors target)
        (get-in @@state/turtle-state-var [:anchors target]))))

(defn- resolve-mesh
  "Resolve a keyword/string/mesh/path/anchor-name to a target for `move-to`.
   Priority for keywords: (1) named anchor on the current turtle or in
   top-level mark-anchors → virtual target with that pose; (2) registered
   mesh in the registry. For path objects, returns a virtual mesh whose
   `:anchors` are the path's marks resolved at the world origin."
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
    (or (when-let [pose (anchor-pose-from-context target)]
          {:type :anchor-as-target
           :creation-pose {:position (or (:position pose) (:pos pose))
                           :heading (:heading pose)
                           :up (:up pose)}})
        (registry/get-mesh (if (keyword? target) target (keyword target))))))

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

(defn- move-to-pose
  "Move state to target's creation-pose position, then adopt its heading/up.
   With :align? true, also rotates the attached mesh so its frame matches
   the creation-pose's frame.
   Falls back to centroid if no creation-pose (align? ignored in that case)."
  [state target & {:keys [align?]}]
  (let [mesh (resolve-mesh target)
        pose (when mesh (:creation-pose mesh))]
    (if pose
      (let [tgt-pos (:position pose)
            tgt-h   (:heading pose)
            tgt-u   (:up pose)
            translated (move-state-to-position state tgt-pos)
            aligned (if (and align? (:attached translated))
                      (align-attached-mesh translated
                                           (:heading translated) (:up translated)
                                           tgt-h tgt-u tgt-pos)
                      translated)]
        (-> aligned
            (assoc :heading tgt-h)
            (assoc :up tgt-u)))
      (move-to-center state target))))

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

(defn- path-target?
  "True when target is an inline path value (as opposed to a mesh/SDF or a
   registered-mesh keyword). Paths have :anchors via their marks but no
   meaningful creation-pose or centroid."
  [target]
  (and (map? target) (= :path (:type target))))

(defn- move-to-dispatch
  "Handle :move-to command during replay.
   Supports:
     (move-to :name)                       — snap to creation-pose (translate only)
     (move-to :name :align)                — snap to creation-pose and rotate mesh to match
     (move-to :name :center)               — snap to centroid
     (move-to :name :at :anchor)           — snap to anchor (translate only)
     (move-to :name :at :anchor :align)    — snap to anchor and rotate mesh to match
   Path targets are only valid with :at :anchor (paths have no creation-pose
   or centroid of their own). Anchor targets (keywords that name an anchor
   on the current turtle or in mark-anchors) are only valid with the default
   form and :align (an anchor is a single pose: no centroid, no sub-anchors)."
  [state args]
  (let [target (first args)
        mode (second args)
        anchor-tgt? (some? (anchor-pose-from-context target))]
    (cond
      (and (path-target? target) (not= mode :at))
      (throw (js/Error. "move-to: path targets are only valid with :at :anchor — paths have no creation-pose or centroid. Use (move-to path :at :mark) or convert the path to a mesh first."))

      (and anchor-tgt? (#{:center :at} mode))
      (throw (js/Error. (str "move-to: anchor target " target " supports only (move-to " target ") and (move-to " target " :align). An anchor is a single pose — it has no centroid (:center) and no sub-anchors (:at).")))

      (and (= mode :center) (= (nth args 2 nil) :align))
      (throw (js/Error. "move-to: :align is not supported with :center (centroid has no frame). Use (move-to target :align) for creation-pose alignment."))

      (and (= mode :at) (= (nth args 3 nil) :align))
      (move-to-anchor state target (nth args 2) :align? true)

      (= mode :at)     (move-to-anchor state target (nth args 2))
      (= mode :center) (move-to-center state target)
      (= mode :align)  (move-to-pose state target :align? true)
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

(defn- rotate-creation-pose
  "Rotate the geometry of the attached mesh around the anchor (creation-pose
   position) while keeping the creation-pose orientation fixed in world.
   After `(cp-th α)`, the direction that was at +α around up from the original
   anchor-heading coincides with the (unchanged) anchor-heading. Vertices and
   named anchors rotate by `-α` around the corresponding pose axis;
   creation-pose is untouched.
   axis: :th (around up), :tv (around right = heading × up), :tr (around heading)."
  [state axis angle-deg]
  (if-let [attached (:attached state)]
    (let [mesh (:mesh attached)
          pose (or (:creation-pose mesh) {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
          h (math/normalize (:heading pose))
          u (math/normalize (:up pose))
          r (math/normalize (math/cross h u))
          rot-axis (case axis :th u :tv r :tr h)
          pivot (:position pose)
          rad (* (- angle-deg) (/ Math/PI 180))
          new-mesh (attachment/rotate-vertices-keeping-creation-pose
                    mesh rot-axis rad pivot)]
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
              :stretch-f  (let [factor (first args)
                                attached (:attached s)
                                mesh (:mesh attached)
                                new-mesh (attachment/stretch-mesh-along-axis
                                          mesh (:heading s) factor (:position s))]
                            (-> s
                                (turtle/replace-mesh-in-state mesh new-mesh)
                                (assoc-in [:attached :mesh] new-mesh)))
              :stretch-rt (let [factor (first args)
                                attached (:attached s)
                                mesh (:mesh attached)
                                right (math/cross (:heading s) (:up s))
                                new-mesh (attachment/stretch-mesh-along-axis
                                          mesh right factor (:position s))]
                            (-> s
                                (turtle/replace-mesh-in-state mesh new-mesh)
                                (assoc-in [:attached :mesh] new-mesh)))
              :stretch-u  (let [factor (first args)
                                attached (:attached s)
                                mesh (:mesh attached)
                                new-mesh (attachment/stretch-mesh-along-axis
                                          mesh (:up s) factor (:position s))]
                            (-> s
                                (turtle/replace-mesh-in-state mesh new-mesh)
                                (assoc-in [:attached :mesh] new-mesh)))
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
              ;; cp-* commands: relocate geometry while keeping creation-pose
              ;; fixed (anchor stays, geometry slides/rotates under it)
              :cp-f  (shift-creation-pose s :f (first args))
              :cp-rt (shift-creation-pose s :rt (first args))
              :cp-u  (shift-creation-pose s :u (first args))
              :cp-th (rotate-creation-pose s :th (first args))
              :cp-tv (rotate-creation-pose s :tv (first args))
              :cp-tr (rotate-creation-pose s :tr (first args))
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
  "Path commands not meaningful when attaching an SDF: only mesh-face ops."
  {:inset "inset is mesh-face-specific, not applicable to SDFs"})

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

(defn- sdf-stretch-along-axis
  "Scale an SDF by `factor` along world-space unit vector `axis`, pivoted at
   `pivot`. Sandwich: translate pivot to origin, rotate axis onto world X,
   scale (factor 1 1) along X, rotate back, translate back."
  [sdf-node axis factor pivot]
  (let [u (math/normalize axis)
        [ux uy uz] u
        [px py pz] pivot
        ;; Build rotation R that takes u to [1 0 0]: rot-axis = cross(u, [1 0 0]),
        ;; angle = acos(ux). The inverse rotation takes [1 0 0] back to u.
        dot-x ux
        translated (sdf/sdf-move sdf-node (- px) (- py) (- pz))
        align? (< dot-x 0.9999)
        [rot-axis angle-deg] (cond
                               (>= dot-x  0.9999) [nil 0]
                               (<= dot-x -0.9999) [[0 0 1] 180.0]
                               :else (let [;; cross(u, [1,0,0]) = [0, uz, -uy]
                                           a [0.0 uz (- uy)]
                                           ang (* (Math/acos dot-x) (/ 180 Math/PI))]
                                       [(math/normalize a) ang]))
        rotated (if align? (sdf/sdf-rotate translated rot-axis angle-deg) translated)
        scaled (sdf/sdf-scale rotated factor 1 1)
        unrotated (if align? (sdf/sdf-rotate scaled rot-axis (- angle-deg)) scaled)]
    (sdf/sdf-move unrotated px py pz)))

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
  "Resolve a target reference (keyword, mesh, SDF, or context anchor) to
   something that can carry :anchors / :creation-pose. Priority for
   keywords: (1) named anchor on the current turtle or in top-level
   mark-anchors → virtual target with that pose as creation-pose;
   (2) registered mesh in the registry."
  [target]
  (cond
    (keyword? target)
    (or (when-let [pose (anchor-pose-from-context target)]
          {:type :anchor-as-target
           :creation-pose {:position (or (:position pose) (:pos pose))
                           :heading (:heading pose)
                           :up (:up pose)}})
        (registry/get-mesh target))
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
        target-obj (resolve-anchor-source target)
        anchor-tgt? (some? (anchor-pose-from-context target))]
    (cond
      (and (path-target? target) (not= mode :at))
      (throw (js/Error. "move-to: path targets are only valid with :at :anchor — paths have no creation-pose or centroid. Use (move-to path :at :mark) or convert the path to a mesh first."))

      (and anchor-tgt? (#{:center :at} mode))
      (throw (js/Error. (str "move-to: anchor target " target " supports only (move-to " target ") and (move-to " target " :align). An anchor is a single pose — it has no centroid (:center) and no sub-anchors (:at).")))

      (and (= mode :center) (= (nth args 2 nil) :align))
      (throw (js/Error. "move-to: :align is not supported with :center (centroid has no frame). Use (move-to target :align) for creation-pose alignment."))

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
      (let [pose (when target-obj (:creation-pose target-obj))
            align? (= mode :align)]
        (if pose
          (let [tgt-pos (:position pose)
                tgt-h   (:heading pose)
                tgt-u   (:up pose)
                sdf-translated (translate-sdf-to-position sdf state tgt-pos)
                sdf' (if align?
                       (sdf-align sdf-translated (:heading state) (:up state) tgt-h tgt-u tgt-pos)
                       sdf-translated)]
            [(-> state
                 (assoc :position tgt-pos)
                 (assoc :heading tgt-h)
                 (assoc :up tgt-u))
             sdf'])
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
   - cp-th / cp-tv / cp-tr: rotate the SDF around the anchor by the
     NEGATIVE of the angle (creation-pose orientation stays put; geometry
     rotates under it).
   - mark: record the current turtle pose as a named anchor on the SDF.
   - move-to: snap the turtle to the target anchor / centroid / pose.
     With :align, also rotate the SDF so its frame matches the anchor.
   - set-heading: replace turtle heading/up directly (frame change only,
     SDF unchanged).
   - inset / scale (legacy turtle form): rejected with explanatory error."
  [sdf-node path]
  (validate-sdf-attach-path! path)
  (loop [state (turtle/make-turtle)
         sdf sdf-node
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

          :stretch-f (let [factor (first args)
                           sdf' (sdf-stretch-along-axis sdf (:heading state) factor (:position state))]
                       (recur state sdf' rest-cmds))

          :stretch-rt (let [factor (first args)
                            right (math/cross (:heading state) (:up state))
                            sdf' (sdf-stretch-along-axis sdf right factor (:position state))]
                        (recur state sdf' rest-cmds))

          :stretch-u (let [factor (first args)
                           sdf' (sdf-stretch-along-axis sdf (:up state) factor (:position state))]
                       (recur state sdf' rest-cmds))

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

          :cp-th (let [α (first args)
                       axis (math/normalize (:up state))
                       sdf' (sdf/sdf-rotate-keeping-creation-pose sdf axis (- α))]
                   (recur state sdf' rest-cmds))

          :cp-tv (let [α (first args)
                       [hx hy hz] (:heading state)
                       [ux uy uz] (:up state)
                       ;; right = heading × up
                       axis (math/normalize
                             [(- (* hy uz) (* hz uy))
                              (- (* hz ux) (* hx uz))
                              (- (* hx uy) (* hy ux))])
                       sdf' (sdf/sdf-rotate-keeping-creation-pose sdf axis (- α))]
                   (recur state sdf' rest-cmds))

          :cp-tr (let [α (first args)
                       axis (math/normalize (:heading state))
                       sdf' (sdf/sdf-rotate-keeping-creation-pose sdf axis (- α))]
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
   - :angle      minimum dihedral angle (inclusive, default 80)
   - :min-radius exclude edges closer than r to the extrusion axis
   - :where      additional predicate on vertex positions

   Concave edges and edges at vertices where more than 3 sharp edges meet are
   skipped automatically — those produce self-intersecting cutters.

   Limitation: on meshes built from CSG composition (e.g. mesh-union of
   primitives that intersect tightly), the chamfer cutters near intersection
   contours can clip features other than the intended edge, producing visible
   spurs. The algorithm is reliable on standalone primitives and on
   compositions where the chamfer distance is small relative to the local
   feature size; on dense junctions, prefer chamfering before composing, or
   reduce `distance`."
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
    ;; Find sharp edges, then filter by convexity and direction.
    ;; Concave edges (where two surfaces meet at an interior corner, e.g. the
    ;; intersection of two unioned boxes) must be excluded — chamfering them
    ;; would cut spurs of material along the interior crease.
    ;; Edges touching a vertex where >3 sharp edges meet are also excluded:
    ;; at a regular polyhedral corner exactly 3 edges meet (cube vertex), and
    ;; their chamfer wedges resolve to a clean corner triangle. At a CSG
    ;; intersection corner (e.g. where a contour ring meets a primitive's edge)
    ;; 4+ edges meet, the wedges overlap, and CSG produces self-intersecting
    ;; results.
    (when-let [edges (faces/find-sharp-edges mesh :angle angle :where combined-where)]
      (let [convex-edges (filterv :convex? edges)
            vertex-degree (reduce (fn [m {:keys [edge]}]
                                    (let [[v0 v1] edge]
                                      (-> m
                                          (update v0 (fnil inc 0))
                                          (update v1 (fnil inc 0)))))
                                  {} convex-edges)
            simple-edges (filterv (fn [{:keys [edge]}]
                                    (let [[v0 v1] edge]
                                      (and (<= (vertex-degree v0 0) 3)
                                           (<= (vertex-degree v1 0) 3))))
                                  convex-edges)
            ;; Filter edges by direction: one of the normals must align with dir-vec
            dir-edges (if dir-vec
                        (filterv (fn [{:keys [normals]}]
                                   (let [[n1 n2] normals]
                                     (or (> (dot3 n1 dir-vec) align-threshold)
                                         (> (dot3 n2 dir-vec) align-threshold))))
                                 simple-edges)
                        simple-edges)]
        (when (seq dir-edges)
          ;; Build a prism cutter per edge, then discard prisms whose interior
          ;; contains the midpoint of another sharp edge. This happens at
          ;; "difficult" edges in CSG-composed meshes — e.g. near where two
          ;; unioned solids meet — where a cutter would carve across the
          ;; intersection ring instead of just smoothing the intended edge.
          ;; Skipping is a conservative compromise: that edge stays unchamfered
          ;; rather than producing a self-intersecting spur.
          ;; Build cutter prisms that are just barely larger than the wedge to
          ;; remove. The legacy defaults (corner=1.5d, face-margin=0.5d) inflate
          ;; the prism well beyond the wedge — for d=4.5 the prism reaches ~7
          ;; units past the corner — which on tight CSG junctions causes the
          ;; cutter to clip adjacent features and produce spurs. We halve d
          ;; before passing it because the resulting cut depth with the small
          ;; face margin is ≈ d_passed (vs the legacy formula's ≈ d/2), and we
          ;; want to keep the user-visible cut size unchanged.
          (let [d-passed (* distance 0.5)
                prisms (keep (fn [{:keys [positions normals edge]}]
                               (let [[p0 p1] positions
                                     [n1 n2] normals
                                     prism (faces/make-prism-along-edge
                                            p0 p1 n1 n2 d-passed
                                            :edge-margin-factor 0
                                            :corner-factor 0.2
                                            :face-margin-factor 0.05)]
                                 (when-not (faces/prism-cuts-other-features? prism edge convex-edges)
                                   prism)))
                             dir-edges)]
            (reduce (fn [current-mesh prism]
                      (or (manifold/difference current-mesh prism)
                          current-mesh))
                    mesh
                    prisms)))))))

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

