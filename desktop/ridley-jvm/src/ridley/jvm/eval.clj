(ns ridley.jvm.eval
  "DSL eval engine for the JVM sidecar.
   Creates a namespace with all DSL bindings, evals user scripts in it."
  (:require [ridley.math :as math]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.extrusion :as extrusion]
            [ridley.turtle.loft :as loft]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.turtle.path :as path-ns]
            [ridley.turtle.transform :as xform]
            [ridley.turtle.attachment :as attachment]
            [ridley.turtle.text :as text]
            [ridley.geometry.primitives :as prims]
            [ridley.geometry.operations :as ops]
            [ridley.geometry.faces :as faces]
            [ridley.manifold.native :as manifold]
            [ridley.clipper.core :as clipper]
            [ridley.io.stl :as stl]
            [ridley.io.svg :as svg]
            [ridley.geometry.warp :as warp]
            [ridley.sdf.core :as sdf]))

;; ── Forward declarations ────────────────────────────────────────
(declare pure-loft-path pure-loft-two-shapes pure-loft-shape-fn
         make-initial-state creation-pose-from-current)

;; ── Turtle state (global, reset per eval) ───────────────────────
(def turtle-state (atom (turtle/make-turtle)))
(def registered-meshes (atom {}))
(def registered-values (atom {}))
(def registered-paths (atom {}))
(def registered-shapes-store (atom {}))
(def source-forms (atom {}))
(def stamp-accumulator (atom []))
(def mark-anchors (atom {}))

(defn reset-state! []
  (reset! turtle-state (turtle/make-turtle))
  (reset! registered-meshes {})
  (reset! registered-values {})
  (reset! registered-paths {})
  (reset! registered-shapes-store {})
  (reset! source-forms {})
  (reset! stamp-accumulator [])
  (reset! mark-anchors {}))

;; ── Implicit turtle commands (mutate global state) ──────────────

(defn implicit-f [dist] (swap! turtle-state turtle/f dist))
(defn implicit-th [angle] (swap! turtle-state turtle/th angle))
(defn implicit-tv [angle] (swap! turtle-state turtle/tv angle))
(defn implicit-tr [angle] (swap! turtle-state turtle/tr angle))
(defn implicit-u [dist] (swap! turtle-state turtle/move-up dist))
(defn implicit-d [dist] (swap! turtle-state turtle/move-down dist))
(defn implicit-rt [dist] (swap! turtle-state turtle/move-right dist))
(defn implicit-lt [dist] (swap! turtle-state turtle/move-left dist))
(defn implicit-arc-h [radius angle] (swap! turtle-state turtle/arc-h radius angle))
(defn implicit-arc-v [radius angle] (swap! turtle-state turtle/arc-v radius angle))
(defn implicit-pen [mode _shape] (swap! turtle-state turtle/pen mode))
(defn implicit-stamp
  "Stamp a shape at current turtle pose. Creates a flat mesh and adds to
   the global stamp accumulator (survives turtle scopes)."
  [shape]
  (let [state @turtle-state
        stamped (turtle/stamp-debug state shape)
        new-stamps (:stamps stamped)
        flat-meshes (keep (fn [s]
                            (when (and (:vertices s) (seq (:faces s)))
                              {:type :mesh
                               :vertices (vec (:vertices s))
                               :faces (vec (:faces s))
                               :material {:double-sided true}
                               :creation-pose {:position (:position state)
                                               :heading (:heading state)
                                               :up (:up state)}}))
                          new-stamps)]
    (swap! stamp-accumulator into flat-meshes)))
(defn implicit-finalize-sweep [] (swap! turtle-state turtle/finalize-sweep))
(defn implicit-finalize-sweep-closed [] (swap! turtle-state turtle/finalize-sweep-closed))
(defn implicit-resolution
  ([mode value] (swap! turtle-state assoc :resolution {:mode mode :value value}))
  ([value] (swap! turtle-state assoc :resolution {:mode :n :value value})))

(defn implicit-bezier-to [target & args]
  (swap! turtle-state #(apply turtle/bezier-to % target args)))
(defn implicit-bezier-as [p & args]
  (swap! turtle-state #(apply turtle/bezier-as % p args)))
(defn implicit-goto [anchor-name]
  ;; Check mark-anchors first, copy to turtle state if found
  (when-let [mark-pose (get @mark-anchors anchor-name)]
    (swap! turtle-state assoc-in [:anchors anchor-name]
           {:position (:pos mark-pose)
            :heading (:heading mark-pose)
            :up (:up mark-pose)}))
  (swap! turtle-state turtle/goto anchor-name))
(defn implicit-look-at [anchor-name]
  (when-let [mark-pose (get @mark-anchors anchor-name)]
    (swap! turtle-state assoc-in [:anchors anchor-name]
           {:position (:pos mark-pose)
            :heading (:heading mark-pose)
            :up (:up mark-pose)}))
  (swap! turtle-state turtle/look-at anchor-name))

;; ── Geometry helpers ────────────────────────────────────────────

(defn- with-creation-pose [mesh]
  (let [t @turtle-state]
    (assoc mesh :creation-pose
           {:position (:position t) :heading (:heading t) :up (:up t)})))

(defn box-impl [sx sy sz] (with-creation-pose (prims/box-mesh sx sy sz)))

(defn sphere-impl
  ([r] (sphere-impl r 16 12))
  ([r segs rings] (with-creation-pose (prims/sphere-mesh r segs rings))))

(defn cyl-impl
  ([r h] (cyl-impl r h 32))
  ([r h n] (with-creation-pose (prims/cyl-mesh r h n))))

(defn cone-impl
  ([r h] (cone-impl r h 32))
  ([r h n] (with-creation-pose (prims/cone-mesh r h n))))

(defn circle-impl
  ([r] (circle-impl r 32))
  ([r n] (shape/circle-shape r n)))

;; ── Attach-face / Clone-face impl ──────────────────────────────

(defn- replay-path-commands
  "Replay path commands on a turtle state."
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
              :inset (turtle/inset s (first args))
              :scale (turtle/scale s (first args))
              :mark s
              s))
          state
          (:commands path)))

;; ── Chamfer / Fillet impl ──────────────────────────────────────

(defn- direction-vec
  "Resolve a turtle-oriented direction keyword to a 3D unit vector."
  [mesh direction]
  (let [pose (or (:creation-pose mesh)
                 {:heading [1 0 0] :up [0 0 1]})
        h (:heading pose)
        u (:up pose)
        r (math/cross h u)]
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

(defn chamfer-edges-impl
  "Chamfer sharp edges by CSG subtraction of prisms.
   distance: chamfer size in mm
   Options: :angle (default 80), :where predicate, :debug (return first prism)"
  [mesh distance & {:keys [angle where debug] :or {angle 80}}]
  (when-let [prisms (faces/chamfer-prisms mesh distance :angle angle :where where)]
    (if debug
      (first prisms)
      (reduce (fn [current-mesh prism]
                (or (manifold/difference current-mesh prism)
                    current-mesh))
              mesh
              prisms))))

(defn chamfer-impl
  "Chamfer edges selected by turtle-oriented direction.
   direction: :top :bottom :up :down :left :right :all
   distance: chamfer size in mm
   Options: :angle (default 80), :min-radius, :where"
  [mesh direction distance & {:keys [angle min-radius where] :or {angle 80}}]
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
          (let [strip (faces/build-chamfer-strip dir-edges distance)]
            (if strip
              (or (manifold/difference mesh strip) mesh)
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

(defn fillet-impl
  "Fillet (round) edges selected by turtle-oriented direction.
   direction: :top :bottom :up :down :left :right :all
   radius: fillet radius in mm
   Options: :angle (default 80), :min-radius, :segments (default 8),
            :where, :blend-vertices (default false)"
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
          (let [cutters (faces/build-fillet-cutters dir-edges radius segments)
                edge-result (if (seq cutters)
                              (reduce (fn [m cutter]
                                        (or (manifold/difference m cutter) m))
                                      mesh cutters)
                              mesh)
                fillet-verts (when blend-vertices
                               (faces/find-fillet-vertices dir-edges))]
            (if (seq fillet-verts)
              (reduce
                (fn [m {:keys [position normals]}]
                  (let [center (faces/compute-fillet-vertex-center position normals radius)
                        sphere (-> (prims/sphere-mesh radius segments (max 6 (quot segments 2)))
                                   (update :vertices
                                           (fn [vs] (mapv (fn [[x y z]]
                                                            [(+ x (nth center 0))
                                                             (+ y (nth center 1))
                                                             (+ z (nth center 2))]) vs))))
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
                        vertex-cutter (manifold/difference corner-box sphere)]
                    (if vertex-cutter
                      (or (manifold/difference m vertex-cutter) m)
                      m)))
                edge-result fillet-verts)
              edge-result)))))))

;; ── Attach-face / Clone-face impl ──────────────────────────────

(defn attach-face-impl
  "Attach to a face and replay path. Returns modified mesh."
  [mesh face-id path]
  (let [mesh (faces/ensure-face-groups mesh)
        state (-> (turtle/make-turtle)
                  (turtle/attach-face mesh face-id))
        state (replay-path-commands state path)]
    (or (get-in state [:attached :mesh]) mesh)))

(defn clone-face-impl
  "Attach to a face with extrusion (clone), replay path. Returns modified mesh."
  [mesh face-id path]
  (let [mesh (faces/ensure-face-groups mesh)
        state (-> (turtle/make-turtle)
                  (turtle/attach-face-extrude mesh face-id))
        state (replay-path-commands state path)]
    (or (get-in state [:attached :mesh]) mesh)))

;; ── Init-turtle (for turtle scoping macro) ────────────────────

(defn init-turtle
  "Create a new turtle state for a turtle scope.
   Clones parent by default; :reset true starts fresh."
  [opts parent]
  (let [base (if (:reset opts)
               (turtle/make-turtle)
               (select-keys parent
                 [:position :heading :up :pen-mode :resolution
                  :joint-mode :material :anchors
                  :preserve-up :reference-up]))
        base (cond-> base
               (not (:reset opts))
               (merge {:geometry [] :meshes [] :stamps []
                       :stamped-shape nil :sweep-rings []
                       :pending-rotation nil :attached nil})
               (:pos opts)     (assoc :position (:pos opts))
               (:heading opts) (assoc :heading (:heading opts))
               (:up opts)      (assoc :up (:up opts))
               (:preserve-up opts)
               (-> (assoc :preserve-up true)
                   (#(assoc % :reference-up (or (:reference-up %) (:up %))))))]
    base))

;; ── Register & Registry ────────────────────────────────────────

(defn- flatten-meshes
  "Recursively flatten nested vectors/seqs of meshes into a single vector."
  [x]
  (cond
    (and (map? x) (:vertices x)) [x]
    (sequential? x) (vec (mapcat flatten-meshes x))
    :else []))

(defn- concat-mesh-vec
  "Concatenate multiple meshes into one by merging vertices and reindexing faces."
  [meshes]
  (when (seq meshes)
    (if (= 1 (count meshes))
      (first meshes)
      (loop [remaining (rest meshes)
             verts (vec (:vertices (first meshes)))
             faces (vec (:faces (first meshes)))]
        (if (empty? remaining)
          {:type :mesh :vertices verts :faces faces
           :creation-pose (:creation-pose (first meshes))}
          (let [m (first remaining)
                offset (count verts)]
            (recur (rest remaining)
                   (into verts (:vertices m))
                   (into faces (mapv (fn [f] (mapv #(+ % offset) f)) (:faces m))))))))))

(defn register-impl [name value]
  (let [res (get-in @turtle-state [:resolution :value] 15)
        mesh (binding [sdf/*sdf-resolution* res]
               (if (and (sequential? value) (not (map? value)))
                 ;; Vector of meshes — flatten and concatenate
                 (concat-mesh-vec (flatten-meshes value))
                 (sdf/ensure-mesh value)))]
    (swap! registered-meshes assoc name mesh)
    mesh))

(defn get-mesh
  "Look up a registered mesh by keyword or symbol."
  [name-kw]
  (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
    (get @registered-meshes sym)))

(defn get-value
  "Look up a registered value (mesh or non-mesh) by keyword or symbol.
   Checks meshes first, then values."
  [name-kw]
  (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
    (or (get @registered-meshes sym)
        (get @registered-values sym))))

(defn register-value!
  "Register a non-mesh value (path, shape, number, etc.)."
  [name-kw value]
  (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
    (swap! registered-values assoc sym value)
    value))

(defn registered-names
  "Return set of all registered mesh names."
  []
  (set (keys @registered-meshes)))

(defn show-mesh!
  "Mark a registered mesh as visible (metadata)."
  [name-kw]
  (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
    (when-let [m (get @registered-meshes sym)]
      (swap! registered-meshes assoc sym (assoc m :visible true)))))

(defn hide-mesh!
  "Mark a registered mesh as hidden (metadata)."
  [name-kw]
  (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
    (when-let [m (get @registered-meshes sym)]
      (swap! registered-meshes assoc sym (assoc m :visible false)))))

(defn show-all! []
  (swap! registered-meshes
         (fn [ms] (into {} (map (fn [[k v]] [k (assoc v :visible true)]) ms)))))

(defn hide-all! []
  (swap! registered-meshes
         (fn [ms] (into {} (map (fn [[k v]] [k (assoc v :visible false)]) ms)))))

(defn show-only-registered!
  "Mark all registered meshes visible; doesn't affect anonymous geometry."
  []
  (show-all!))

(defn visible-names
  "Return names of meshes that are not explicitly hidden."
  []
  (set (keep (fn [[k v]] (when (get v :visible true) k))
             @registered-meshes)))

(defn visible-meshes
  "Return vector of meshes that are not explicitly hidden."
  []
  (vec (keep (fn [[_ v]] (when (get v :visible true) v))
             @registered-meshes)))

;; ── Path/Shape registry ────────────────────────────────────────

(defn register-path! [name-kw path]
  (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
    (swap! registered-paths assoc sym path)
    path))

(defn get-path [name-kw]
  (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
    (get @registered-paths sym)))

(defn path-names [] (set (keys @registered-paths)))

(defn register-shape! [name-kw s]
  (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
    (swap! registered-shapes-store assoc sym s)
    s))

(defn get-shape [name-kw]
  (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
    (get @registered-shapes-store sym)))

(defn shape-names [] (set (keys @registered-shapes-store)))

(defn set-source-form! [name-kw form]
  (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
    (swap! source-forms assoc sym form)))

(defn get-source-form [name-kw]
  (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
    (get @source-forms sym)))

;; ── Measurement ───────────────────────────────────────────────

(defn distance-3d
  "Euclidean distance between two 3D points."
  [a b]
  (let [dx (- (nth b 0) (nth a 0))
        dy (- (nth b 1) (nth a 1))
        dz (- (nth b 2) (nth a 2))]
    (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))

(defn mesh-area
  "Total surface area of a mesh (sum of triangle areas)."
  [mesh]
  (let [verts (:vertices mesh)]
    (reduce + (map (fn [[i j k]]
                     (let [a (nth verts i) b (nth verts j) c (nth verts k)
                           ab (mapv - b a) ac (mapv - c a)
                           cross (math/cross ab ac)]
                       (* 0.5 (math/magnitude cross))))
                   (:faces mesh)))))

;; ── Lay-flat ────────────────────────────────────────────────────

(defn- lay-flat-with-normal
  "Rotate and translate mesh so that a face with given normal and center
   sits on the XY plane (z=0), centered.
   Optional up-ref: a 3D vector that should point toward +Y after layout."
  [mesh normal face-center-fn & [_up-ref]]
  (let [;; Step 1: rotate so face normal → [0, 0, -1]
        target [0.0 0.0 -1.0]
        dot-nt (math/dot normal target)
        vertices (:vertices mesh)
        ;; Build the rotation that maps normal → -Z
        rot-fn (if (> (Math/abs dot-nt) 0.9999)
                 (if (neg? dot-nt)
                   identity
                   (let [perp (if (> (Math/abs (nth normal 0)) 0.9) [0 1 0] [1 0 0])]
                     #(math/rotate-point-around-axis % perp Math/PI)))
                 (let [axis (math/normalize (math/cross normal target))
                       angle (Math/acos (max -1.0 (min 1.0 dot-nt)))]
                   #(math/rotate-point-around-axis % axis angle)))
        rotated-verts (mapv rot-fn vertices)
        ;; Step 2: rotate around Z to align with axes
        ;; Use the mesh's creation-pose heading as reference direction
        ;; After rotation, this heading should point along +X or +Y
        rotated-verts
        (let [;; Get creation-pose heading (or default [1,0,0])
              cp-heading (or (get-in mesh [:creation-pose :heading]) [1.0 0.0 0.0])
              ;; Rotate it the same way as the mesh
              ref-rotated (rot-fn cp-heading)
              ;; Project to XY plane
              rx (nth ref-rotated 0)
              ry (nth ref-rotated 1)
              ;; Angle to align this direction with +X
              z-angle (if (> (+ (* rx rx) (* ry ry)) 0.001)
                        (- (Math/atan2 ry rx))
                        0.0)]
          (if (< (Math/abs z-angle) 0.001)
            rotated-verts
            (mapv #(math/rotate-point-around-axis % [0 0 1] z-angle) rotated-verts)))
        ;; Step 3: translate so face center is at origin
        center (face-center-fn rotated-verts)
        offset [(- (center 0)) (- (center 1)) (- (center 2))]
        final-verts (mapv #(math/v+ % offset) rotated-verts)]
    (assoc mesh
           :vertices final-verts
           :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})))

(defn lay-flat-impl
  "Position a mesh so a specified face sits on the XY plane (z=0), centered.

   Accepts:
   - keyword anchor name → uses pose saved by :mark in extrude+/revolve+
   - :top/:bottom/:up/:down → finds largest face in that direction
   - face-id (number) → specific face group
   - nil → default :bottom

   The face normal will point down (-Z), the face center at the XY origin."
  ([mesh] (lay-flat-impl mesh nil))
  ([mesh target]
   (cond
     ;; Anchor keyword from :mark
     (and (keyword? target)
          (not (#{:top :bottom :up :down :left :right} target)))
     (let [pose (or (get @mark-anchors target)
                    (get-in @turtle-state [:anchors target]))]
       (if pose
         (lay-flat-with-normal mesh
           (:heading pose)  ;; heading = face normal
           (fn [rotated-verts]
             (let [normal (:heading pose)
                   tgt [0.0 0.0 -1.0]
                   dot-nt (math/dot normal tgt)
                   rot-fn (if (> (Math/abs dot-nt) 0.9999)
                            (if (neg? dot-nt)
                              identity
                              (let [perp (if (> (Math/abs (nth normal 0)) 0.9) [0 1 0] [1 0 0])]
                                #(math/rotate-point-around-axis % perp Math/PI)))
                            (let [axis (math/normalize (math/cross normal tgt))
                                  angle (Math/acos (max -1.0 (min 1.0 dot-nt)))]
                              #(math/rotate-point-around-axis % axis angle)))]
               (rot-fn (:pos pose))))
           (:up pose))  ;; pass up vector for Z-rotation alignment
         (throw (Exception. (str "lay-flat: no anchor named " target)))))

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

     ;; Numeric face-id
     (number? target)
     (let [mesh (faces/ensure-face-groups mesh)
           info (faces/compute-face-info (:vertices mesh)
                  (get (:face-groups mesh) target))
           face-indices (:vertices info)]
       (lay-flat-with-normal mesh (:normal info)
         (fn [rotated-verts]
           (let [fv (mapv #(nth rotated-verts %) face-indices)]
             (math/v* (reduce math/v+ fv) (/ 1.0 (count fv)))))))

     ;; Default: bottom
     :else
     (lay-flat-impl mesh :bottom))))

;; ── Bench ───────────────────────────────────────────────────────

(defn bench [label f]
  (let [t0 (System/nanoTime)
        result (if (fn? f) (f) f)
        t1 (System/nanoTime)]
    (println (str label ": " (format "%.1f" (/ (- t1 t0) 1e6)) "ms"))
    result))

;; ── Extrude/Loft impl functions (called from macros) ────────────

(defn extrude-closed-impl
  "extrude-closed: shape + path-data → closed mesh (no caps, torus-like)"
  [shape path-data]
  (let [current @turtle-state
        initial (-> (turtle/make-turtle)
                    (assoc :position (:position current))
                    (assoc :heading (:heading current))
                    (assoc :up (:up current))
                    (assoc :resolution (:resolution current)))
        state (turtle/extrude-closed-from-path initial shape path-data)
        mesh (last (:meshes state))]
    (when mesh
      (assoc mesh :creation-pose
             {:position (:position current) :heading (:heading current) :up (:up current)}))))

(defn- derive-end-up
  "Compute up vector for end-face pose, perpendicular to heading."
  [heading ref-up]
  (let [dot-hu (math/dot ref-up heading)
        up-raw (math/v- ref-up (math/v* heading dot-hu))
        m (math/magnitude up-raw)]
    (if (> m 0.001)
      (math/v* up-raw (/ 1.0 m))
      [0 0 1])))

(defn extrude-impl
  "extrude-impl: shape-or-shapes + path-data → mesh or vector of meshes."
  [shape-or-shapes path-data]
  (let [shapes (if (and (vector? shape-or-shapes)
                        (seq shape-or-shapes)
                        (map? (first shape-or-shapes)))
                 shape-or-shapes
                 [shape-or-shapes])
        initial (make-initial-state)
        pose (creation-pose-from-current)
        results (reduce
                  (fn [acc s]
                    (let [state (turtle/extrude-from-path initial s path-data)
                          mesh (last (:meshes state))]
                      (if mesh (conj acc (assoc mesh :creation-pose pose)) acc)))
                  []
                  shapes)]
    (if (= 1 (count results))
      (first results)
      results)))

(defn extrude+-impl
  "Like extrude-impl but returns {:mesh :end-face} for chaining.
   Optional :mark name cap — saves cap pose as anchor in global turtle state.
   cap is :start-cap or :end-cap."
  [shape-or-shapes path-data & {:keys [mark mark-cap]}]
  (let [shapes (if (and (vector? shape-or-shapes)
                        (seq shape-or-shapes)
                        (map? (first shape-or-shapes)))
                 shape-or-shapes
                 [shape-or-shapes])
        initial (make-initial-state)
        pose (creation-pose-from-current)
        start-up (derive-end-up (:heading pose) (or (:up pose) [0 0 1]))
        results (reduce
                  (fn [acc s]
                    (let [state (turtle/extrude-from-path initial s path-data)
                          mesh (last (:meshes state))]
                      (if mesh
                        (let [end-heading (:heading state)
                              end-pos (:position state)
                              end-up (derive-end-up end-heading
                                       (or (:up pose) [0 0 1]))]
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
    ;; Save mark as anchor if requested
    (when (and mark mark-cap result)
      (let [face-pose (case mark-cap
                        :start-cap (:pose (:start-face result))
                        :end-cap (:pose (:end-face result))
                        nil)]
        (when face-pose
          (swap! mark-anchors assoc mark face-pose))))
    result))

(defn loft-impl
  "loft-impl: dispatch based on args.
   2-arg: shape-fn + path
   3-arg: shape-fn + path (ignores 2nd), or shape + shape2 + path, or shape + transform-fn + path"
  ([first-arg path-data]
   (if (sfn/shape-fn? first-arg)
     (pure-loft-shape-fn first-arg path-data)
     (throw (Exception. "loft: 2-arg form requires a shape-fn as first argument"))))
  ([first-arg second-arg path-data]
   (cond
     (sfn/shape-fn? first-arg)
     (pure-loft-shape-fn first-arg path-data)

     (shape/shape? second-arg)
     (pure-loft-two-shapes first-arg second-arg path-data)

     :else
     (pure-loft-path first-arg second-arg path-data))))

;; ── Loft-n impl ───────────────────────────────────────────────

(defn loft-n-impl
  "loft-n-impl: loft with custom step count."
  ([steps first-arg path]
   (if (sfn/shape-fn? first-arg)
     (pure-loft-shape-fn first-arg path steps)
     (throw (Exception. "loft-n: 2-arg form requires a shape-fn as first argument"))))
  ([steps first-arg second-arg path]
   (cond
     (sfn/shape-fn? first-arg)
     (pure-loft-shape-fn first-arg path steps)

     (shape/shape? second-arg)
     (pure-loft-two-shapes first-arg second-arg path steps)

     :else
     (pure-loft-path first-arg second-arg path steps))))

;; ── Bloft impl ─────────────────────────────────────────────────

(defn bloft-impl
  "bloft-impl: bezier-safe loft — handles self-intersecting paths."
  ([first-arg path]
   (bloft-impl first-arg nil path nil 0.1))
  ([first-arg second-arg path]
   (bloft-impl first-arg second-arg path nil 0.1))
  ([first-arg second-arg path steps]
   (bloft-impl first-arg second-arg path steps 0.1))
  ([first-arg second-arg path steps threshold]
   (let [current @turtle-state
         initial (-> (turtle/make-turtle)
                     (assoc :position (:position current))
                     (assoc :heading (:heading current))
                     (assoc :up (:up current))
                     (assoc :resolution (:resolution current)))
         creation-pose {:position (:position current) :heading (:heading current) :up (:up current)}]
     (cond
       (sfn/shape-fn? first-arg)
       (let [path-length (reduce + 0 (keep (fn [cmd]
                                              (when (= :f (:cmd cmd))
                                                (first (:args cmd))))
                                            (:commands path)))]
         (binding [sfn/*path-length* path-length]
           (let [base-shape (first-arg 0)
                 transform-fn (fn [_shape t] (first-arg t))
                 state (loft/bloft initial base-shape transform-fn path steps threshold)
                 mesh (last (:meshes state))]
             (when mesh (assoc mesh :creation-pose creation-pose)))))

       (and second-arg (shape/shape? second-arg))
       (let [n1 (count (:points first-arg))
             n2 (count (:points second-arg))
             [rs1 rs2] (if (= n1 n2)
                          [first-arg second-arg]
                          (let [target-n (max n1 n2)]
                            [(xform/resample first-arg target-n)
                             (xform/resample second-arg target-n)]))
             s2-aligned (xform/align-to-shape rs1 rs2)
             transform-fn (shape/make-lerp-fn rs1 s2-aligned)
             state (loft/bloft initial rs1 transform-fn path steps threshold)
             mesh (last (:meshes state))]
         (when mesh (assoc mesh :creation-pose creation-pose)))

       :else
       (let [transform-fn (or second-arg (fn [s _] s))
             state (loft/bloft initial first-arg transform-fn path steps threshold)
             mesh (last (:meshes state))]
         (when mesh (assoc mesh :creation-pose creation-pose)))))))

;; ── Revolve impl ──────────────────────────────────────────────

(defn- compute-pivot-offset
  "Compute the 2D offset needed to place the specified edge on the revolution axis.
   Returns [dx dy] — the amount the shape was shifted."
  [s pivot]
  (let [pts (:points s)
        xs (map first pts)
        ys (map second pts)]
    (case pivot
      :right [(- (apply max xs)) 0]
      :left  [(- (apply min xs)) 0]
      :up    [0 (- (apply max ys))]
      :down  [0 (- (apply min ys))]
      [0 0])))

(defn- translate-mesh-3d
  "Translate all vertices of a mesh by a 3D offset."
  [mesh offset]
  (update mesh :vertices
          (fn [vs] (mapv (fn [v] (math/v+ v offset)) vs))))

(defn revolve-impl
  "revolve-impl: revolve shape or shape-fn around turtle's axis.
   Optional :pivot (:right/:left/:up/:down) shifts the shape so
   the specified edge sits on the revolution axis, then compensates
   the mesh position so it appears at the correct location."
  ([shape-or-fn]
   (revolve-impl shape-or-fn 360))
  ([shape-or-fn angle & {:keys [pivot]}]
   (let [;; Clip shape to x >= 0 for revolve (prevents crossing revolution axis)
         ;; Skip clip when pivot is used (pivot already ensures x >= 0)
         shape-or-fn (if (and (not pivot) (shape/shape? shape-or-fn))
                       (let [min-x (apply min (map first (:points shape-or-fn)))]
                         (if (neg? min-x)
                           (let [max-x (apply max (map first (:points shape-or-fn)))
                                 max-y (apply max (map #(Math/abs (double (second %))) (:points shape-or-fn)))
                                 half (+ (max max-x max-y) 100)
                                 clip-rect (shape/make-shape [[0 (- half)] [half (- half)] [half half] [0 half]]
                                             {:centered? true})]
                             (or (clipper/shape-intersection shape-or-fn clip-rect)
                                 shape-or-fn))
                           shape-or-fn))
                       shape-or-fn)
         current @turtle-state
         initial (-> (turtle/make-turtle)
                     (assoc :position (:position current))
                     (assoc :heading (:heading current))
                     (assoc :up (:up current))
                     (assoc :resolution (:resolution current)))
         creation-pose {:position (:position current) :heading (:heading current) :up (:up current)}
         ;; Compute pivot offset and shift shape
         [dx dy] (if (and pivot (shape/shape? shape-or-fn))
                   (compute-pivot-offset shape-or-fn pivot)
                   [0 0])
         shifted-shape (if (and pivot (shape/shape? shape-or-fn))
                         (shape/translate-shape shape-or-fn dx dy)
                         shape-or-fn)
         ;; Do the revolve
         mesh (if (sfn/shape-fn? shifted-shape)
                (let [base-shape (shifted-shape 0)
                      state (turtle/revolve-shape initial base-shape angle shifted-shape)]
                  (last (:meshes state)))
                (let [state (turtle/revolve-shape initial shifted-shape angle)]
                  (last (:meshes state))))]
     (when mesh
       (let [heading (:heading current)
             up (:up current)
             right (math/cross heading up)
             ;; 3D offset from pivot shift: shape X → right, shape Y → up
             pivot-offset (if (or (not= dx 0) (not= dy 0))
                            (math/v+ (math/v* right (- dx)) (math/v* up (- dy)))
                            [0 0 0])
             ;; Compensate pivot offset on mesh vertices
             mesh (if (or (not= dx 0) (not= dy 0))
                    (translate-mesh-3d mesh pivot-offset)
                    mesh)
]
         (assoc mesh :creation-pose creation-pose))))))

(defn revolve+-impl
  "Like revolve-impl but returns {:mesh :end-face} for chaining.
   Uses the original shape (not re-extracted) to avoid axis swap from 2D projection.
   Optional :mark name cap — saves cap pose as anchor."
  ([shape-or-fn]
   (revolve+-impl shape-or-fn 360))
  ([shape-or-fn angle & {:keys [pivot mark mark-cap]}]
   (let [current @turtle-state
         start-pose {:pos (:position current)
                     :heading (:heading current)
                     :up (:up current)}
         mesh (revolve-impl shape-or-fn angle :pivot pivot)]
     (when mesh
       (let [result
             (if (>= (Math/abs (double angle)) 360)
               {:mesh mesh
                :start-face {:shape shape-or-fn :pose start-pose}}
               (let [face-data (faces/face-shape mesh
                                 (:id (faces/largest-face mesh :top)))]
                 {:mesh mesh
                  :start-face {:shape shape-or-fn :pose start-pose}
                  :end-face {:shape shape-or-fn
                             :pose (:pose face-data)}}))]
         ;; Save mark as anchor if requested
         (when (and mark mark-cap)
           (let [face-pose (case mark-cap
                             :start-cap (:pose (:start-face result))
                             :end-cap (:pose (:end-face result))
                             nil)]
             (when face-pose
               (swap! mark-anchors assoc mark face-pose))))
         result)))))

;; ── transform-> : chainable pipeline ──────────────────────────

(defn- transform->step
  "Execute a single step in a transform-> pipeline."
  [prev-end step]
  (let [shape (:shape prev-end)
        pose (:pose prev-end)
        op (:op step)
        args (:args step)
        mark (:mark step)
        mark-cap (:mark-cap step)
        saved @turtle-state]
    (reset! turtle-state (init-turtle pose saved))
    (let [result (try
                   (case op
                     :extrude+ (extrude+-impl shape (first args))
                     :revolve+ (apply revolve+-impl shape args)
                     (throw (Exception. (str "transform->: unknown op " op))))
                   (finally
                     (reset! turtle-state saved)))]
      ;; Save mark to GLOBAL atom (survives turtle scopes)
      (when (and mark mark-cap result)
        (let [face-pose (case mark-cap
                          :start-cap (:pose (:start-face result))
                          :end-cap (:pose (:end-face result))
                          nil)]
          (when face-pose
            (swap! mark-anchors assoc mark face-pose))))
      result)))

(defn transform->impl
  "Execute a transform-> pipeline.
   shape-or-end-face: either a shape or an end-face map {:shape :pose}.
   steps is a vector of {:op ... :args ...}.
   Returns the mesh-union of all steps."
  [shape-or-end-face steps]
  (let [initial-end (if (and (map? shape-or-end-face) (:shape shape-or-end-face) (:pose shape-or-end-face))
                      ;; Already an end-face map
                      shape-or-end-face
                      ;; Plain shape — use current turtle pose
                      (let [current @turtle-state]
                        {:shape shape-or-end-face
                         :pose {:pos (:position current)
                                :heading (:heading current)
                                :up (:up current)}}))]
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

;; ── Pure helper functions (no side effects, read turtle state) ─

(defn- make-initial-state []
  (let [current @turtle-state]
    (-> (turtle/make-turtle)
        (assoc :position (:position current))
        (assoc :heading (:heading current))
        (assoc :up (:up current))
        (assoc :resolution (:resolution current)))))

(defn- creation-pose-from-current []
  (let [current @turtle-state]
    {:position (:position current) :heading (:heading current) :up (:up current)}))

(defn pure-extrude-path
  "Pure extrude: shape + path → mesh (no side effects)."
  [shape path]
  (let [initial (make-initial-state)
        pose (creation-pose-from-current)
        state (turtle/extrude-from-path initial shape path)
        mesh (last (:meshes state))]
    (when mesh (assoc mesh :creation-pose pose))))

(defn pure-loft-path
  "Pure loft: shape + transform-fn + path → mesh."
  ([shape transform-fn path] (pure-loft-path shape transform-fn path 16))
  ([shape transform-fn path steps]
   (let [initial (make-initial-state)
         pose (creation-pose-from-current)
         state (loft/loft-from-path initial shape transform-fn path steps)
         mesh (last (:meshes state))]
     (when mesh (assoc mesh :creation-pose pose)))))

(defn pure-loft-two-shapes
  "Pure loft between two shapes."
  ([shape1 shape2 path] (pure-loft-two-shapes shape1 shape2 path 16))
  ([shape1 shape2 path steps]
   (let [n1 (count (:points shape1))
         n2 (count (:points shape2))
         [rs1 rs2] (if (= n1 n2)
                      [shape1 shape2]
                      (let [target-n (max n1 n2)]
                        [(xform/resample shape1 target-n)
                         (xform/resample shape2 target-n)]))
         s2-aligned (xform/align-to-shape rs1 rs2)
         transform-fn (shape/make-lerp-fn rs1 s2-aligned)]
     (pure-loft-path rs1 transform-fn path steps))))

(defn pure-loft-shape-fn
  "Pure loft with shape-fn."
  ([shape-fn-val path] (pure-loft-shape-fn shape-fn-val path 16))
  ([shape-fn-val path steps]
   (let [path-length (reduce + 0 (keep (fn [cmd]
                                          (when (= :f (:cmd cmd))
                                            (first (:args cmd))))
                                        (:commands path)))]
     (binding [sfn/*path-length* path-length]
       (let [base-shape (shape-fn-val 0)
             transform-fn (fn [_shape t] (shape-fn-val t))]
         (pure-loft-path base-shape transform-fn path steps))))))

(defn pure-bloft
  "Pure bezier-safe loft."
  ([shape transform-fn path] (pure-bloft shape transform-fn path nil 0.1))
  ([shape transform-fn path steps] (pure-bloft shape transform-fn path steps 0.1))
  ([shape transform-fn path steps threshold]
   (let [initial (make-initial-state)
         pose (creation-pose-from-current)
         state (loft/bloft initial shape transform-fn path steps threshold)
         mesh (last (:meshes state))]
     (when mesh (assoc mesh :creation-pose pose)))))

(defn pure-bloft-two-shapes
  "Pure bezier-safe loft between two shapes."
  ([shape1 shape2 path] (pure-bloft-two-shapes shape1 shape2 path nil 0.1))
  ([shape1 shape2 path steps] (pure-bloft-two-shapes shape1 shape2 path steps 0.1))
  ([shape1 shape2 path steps threshold]
   (let [n1 (count (:points shape1))
         n2 (count (:points shape2))
         [rs1 rs2] (if (= n1 n2)
                      [shape1 shape2]
                      (let [target-n (max n1 n2)]
                        [(xform/resample shape1 target-n)
                         (xform/resample shape2 target-n)]))
         s2-aligned (xform/align-to-shape rs1 rs2)
         transform-fn (shape/make-lerp-fn rs1 s2-aligned)]
     (pure-bloft rs1 transform-fn path steps threshold))))

(defn pure-bloft-shape-fn
  "Pure bezier-safe loft with shape-fn."
  ([shape-fn-val path] (pure-bloft-shape-fn shape-fn-val path nil 0.1))
  ([shape-fn-val path steps] (pure-bloft-shape-fn shape-fn-val path steps 0.1))
  ([shape-fn-val path steps threshold]
   (let [path-length (reduce + 0 (keep (fn [cmd]
                                          (when (= :f (:cmd cmd))
                                            (first (:args cmd))))
                                        (:commands path)))]
     (binding [sfn/*path-length* path-length]
       (let [base-shape (shape-fn-val 0)
             transform-fn (fn [_shape t] (shape-fn-val t))]
         (pure-bloft base-shape transform-fn path steps threshold))))))

(defn pure-revolve
  "Pure revolve."
  ([shape] (pure-revolve shape 360))
  ([shape angle]
   (let [initial (make-initial-state)
         pose (creation-pose-from-current)
         state (turtle/revolve-shape initial shape angle)
         mesh (last (:meshes state))]
     (when mesh (assoc mesh :creation-pose pose)))))

(defn pure-revolve-shape-fn
  "Pure revolve with shape-fn."
  ([shape-fn-val] (pure-revolve-shape-fn shape-fn-val 360))
  ([shape-fn-val angle]
   (let [base-shape (shape-fn-val 0)
         initial (make-initial-state)
         pose (creation-pose-from-current)
         state (turtle/revolve-shape initial base-shape angle shape-fn-val)
         mesh (last (:meshes state))]
     (when mesh (assoc mesh :creation-pose pose)))))

;; ── DSL bindings (non-macro) ────────────────────────────────────

(def dsl-bindings
  {;; Turtle movement
   'f    implicit-f
   'th   implicit-th
   'tv   implicit-tv
   'tr   implicit-tr
   'u    implicit-u
   'd    implicit-d
   'rt   implicit-rt
   'lt   implicit-lt
   ;; Arc
   'arc-h  implicit-arc-h
   'arc-v  implicit-arc-v
   ;; Bezier
   'bezier-to  implicit-bezier-to
   'bezier-as  implicit-bezier-as
   ;; Navigation
   'goto       implicit-goto
   'look-at    implicit-look-at
   ;; Pen / sweep
   'pen          implicit-pen
   'pen-up       (fn [] (swap! turtle-state turtle/pen-up))
   'pen-down     (fn [] (swap! turtle-state turtle/pen-down))
   'stamp        implicit-stamp
   'finalize-sweep implicit-finalize-sweep
   'finalize-sweep-closed implicit-finalize-sweep-closed
   ;; Resolution
   'resolution   implicit-resolution
   ;; 3D primitives
   'box    box-impl
   'sphere sphere-impl
   'cyl    cyl-impl
   'cone   cone-impl
   ;; 2D shapes
   'circle circle-impl
   'rect   shape/rect-shape
   'poly   shape/poly-shape
   'polygon shape/ngon-shape
   'star   shape/star-shape
   ;; Shape transforms
   'scale         xform/scale
   'rotate-shape  xform/rotate
   'translate     xform/translate
   'translate-shape shape/translate-shape
   'scale-shape   shape/scale-shape
   'morph         xform/morph
   'resample      xform/resample
   'reverse-shape shape/reverse-shape
   'stroke-shape  shape/stroke-shape
   'path-to-shape shape/path-to-shape
   'fillet-shape  shape/fillet-shape
   'chamfer-shape shape/chamfer-shape
   'fit           shape/fit
   'poly-path     shape/poly-path
   'poly-path-closed shape/poly-path-closed
   'subpath-y     shape/subpath-y
   'offset-x      shape/offset-x
   'bounds-2d     shape/bounds-2d
   'mark-pos      shape/mark-pos
   'mark-x        shape/mark-x
   'mark-y        shape/mark-y
   ;; Shape-fn
   'shape-fn     sfn/shape-fn
   'shape-fn?    sfn/shape-fn?
   'tapered      sfn/tapered
   'twisted      sfn/twisted
   'rugged       sfn/rugged
   'fluted       sfn/fluted
   'displaced    sfn/displaced
   'morphed      sfn/morphed
   'angle        sfn/angle
   'displace-radial sfn/displace-radial
   'noise        sfn/noise
   'fbm          sfn/fbm
   'noisy        sfn/noisy
   'woven        sfn/woven
   'weave-heightmap sfn/weave-heightmap
   'mesh-bounds  sfn/mesh-bounds
   'mesh-to-heightmap sfn/mesh-to-heightmap
   'sample-heightmap  sfn/sample-heightmap
   'heightmap    sfn/heightmap
   'heightmap-to-mesh sfn/heightmap-to-mesh
   'profile      sfn/profile
   'capped       sfn/capped
   'shell        sfn/shell
   'woven-shell  sfn/woven-shell
   ;; transform
   'transform    turtle/transform-mesh
   ;; Boolean ops (via Rust HTTP server)
   'mesh-union       manifold/union
   'mesh-difference  manifold/difference
   'mesh-intersection manifold/intersection
   'mesh-hull        manifold/hull
   'native-union     manifold/union
   'native-difference manifold/difference
   'native-intersection manifold/intersection
   'native-hull      manifold/hull
   'concat-meshes    manifold/concat-meshes
   'solidify         manifold/solidify
   'manifold?        manifold/manifold?
   ;; Generative ops (legacy — prefer revolve macro)
   'ops-revolve  ops/revolve
   ;; Impl functions (used by macros)
   'extrude-impl        extrude-impl
   'extrude-closed-impl extrude-closed-impl
   'loft-impl           loft-impl
   'loft-n-impl         loft-n-impl
   'bloft-impl          bloft-impl
   'revolve-impl        revolve-impl
   'extrude+-impl       extrude+-impl
   'revolve+-impl       revolve+-impl
   'transform->impl     transform->impl
   ;; Pure functions (no side effects, for direct use)
   'pure-extrude-path       pure-extrude-path
   'pure-loft-path          pure-loft-path
   'pure-loft-two-shapes    pure-loft-two-shapes
   'pure-loft-shape-fn      pure-loft-shape-fn
   'pure-bloft              pure-bloft
   'pure-bloft-two-shapes   pure-bloft-two-shapes
   'pure-bloft-shape-fn     pure-bloft-shape-fn
   'pure-revolve            pure-revolve
   'pure-revolve-shape-fn   pure-revolve-shape-fn
   ;; Generative ops (legacy direct calls)
   'ops-extrude  ops/extrude
   'extrude-z    ops/extrude-z
   'extrude-y    ops/extrude-y
   'ops-loft     ops/loft
   ;; Turtle extras
   'joint-mode   (fn [mode] (swap! turtle-state assoc :joint-mode mode))
   'inset        (fn [dist] (swap! turtle-state attachment/inset dist))
   'get-anchor   (fn [name] (get-in @turtle-state [:anchors name]))
   'follow-path  (fn [p] (swap! turtle-state turtle/run-path p))
   'path?        turtle/path?
   'shape?       shape/shape?
   'quick-path   turtle/quick-path
   'set-creation-pose (fn [mesh] (turtle/set-creation-pose @turtle-state mesh))
   'last-mesh    (fn [] (last (:meshes @turtle-state)))
   'get-turtle-resolution (fn [] (get-in @turtle-state [:resolution :value] 15))
   'get-turtle-joint-mode (fn [] (:joint-mode @turtle-state :miter))
   ;; Path utilities
   'run-path-impl  turtle/run-path
   'path-segments-impl turtle/path-segments
   'subdivide-segment-impl turtle/subdivide-segment
   ;; Mesh validation
   'mesh-status    manifold/get-mesh-status
   ;; Attach-face / clone-face impl (used by macros)
   'attach-face-impl  attach-face-impl
   'clone-face-impl   clone-face-impl
   ;; Turtle scoping
   'init-turtle  init-turtle
   ;; Shape recording (used by shape macro)
   'recording-turtle       shape/recording-turtle
   'shape-rec-f            shape/rec-f
   'shape-rec-th           shape/rec-th
   'shape-from-recording   shape/shape-from-recording
   ;; Pure turtle functions (for attach macros / explicit use)
   'make-turtle            turtle/make-turtle
   'turtle-f               turtle/f
   'turtle-th              turtle/th
   'turtle-tv              turtle/tv
   'turtle-tr              turtle/tr
   'turtle-attach          turtle/attach
   'turtle-attach-face     turtle/attach-face
   'turtle-attach-face-extrude turtle/attach-face-extrude
   'turtle-attach-move     turtle/attach-move
   'turtle-inset           turtle/inset
   'turtle-scale           turtle/scale
   'turtle-group-transform attachment/group-transform
   ;; Warp — spatial mesh deformation
   'warp-impl        warp/warp
   'inflate          warp/inflate
   'dent             warp/dent
   'attract          warp/attract
   'twist            warp/twist
   'squash           warp/squash
   'roughen          warp/roughen
   'smooth-falloff   warp/smooth-falloff
   ;; 2D booleans
   'shape-union        clipper/shape-union
   'shape-difference   clipper/shape-difference
   'shape-intersection clipper/shape-intersection
   'shape-xor          clipper/shape-xor
   'shape-offset       clipper/shape-offset
   'shape-hull         clipper/shape-hull
   'shape-bridge       clipper/shape-bridge
   'pattern-tile       clipper/pattern-tile
   ;; Register is a macro (injected separately) — register-impl is the backing fn
   ;; Registry lookup
   'get-mesh          get-mesh
   '$                 get-value
   'register-value!   register-value!
   'registered-names  registered-names
   ;; Visibility (metadata-based)
   'show-mesh!        show-mesh!
   'hide-mesh!        hide-mesh!
   'show-all!         show-all!
   'hide-all!         hide-all!
   'show-only-registered! show-only-registered!
   'visible-names     visible-names
   'visible-meshes    visible-meshes
   'color     (fn [name-kw color-val]
                (let [sym (if (keyword? name-kw) (symbol (name name-kw)) name-kw)]
                  (when-let [m (get @registered-meshes sym)]
                    (swap! registered-meshes assoc sym (assoc m :color color-val)))))
   ;; File I/O (JVM native — direct filesystem access)
   'save-stl  (fn [value path] (stl/save-stl (sdf/ensure-mesh value) path))
   'load-stl  stl/load-stl
   'load-svg  svg/load-svg
   'svg-path  svg/svg-path
   ;; Text shapes (java.awt font rendering)
   'text-shape   text/text-shape
   'text-shapes  text/text-shapes
   'char-shape   text/char-shape
   'text-width   text/text-width
   'load-font!   text/load-font!
   'font-loaded? text/font-loaded?
   ;; Path registry
   'register-path!  register-path!
   'get-path        get-path
   'path-names      path-names
   ;; Shape registry
   'register-shape! register-shape!
   'get-shape       get-shape
   'shape-names     shape-names
   ;; Source form tracking (for tweak)
   'set-source-form! set-source-form!
   'get-source-form  get-source-form
   ;; Measurement
   'distance        distance-3d
   'area            mesh-area
   'lay-flat        lay-flat-impl
   ;; Material
   'material        (fn [opts] (swap! turtle-state assoc :material opts))
   'reset-material  (fn [] (swap! turtle-state dissoc :material))
   ;; Missing aliases
   'attached?       (fn [] (some? (:attached @turtle-state)))
   'make-shape      shape/make-shape
   'bezier-to-anchor (fn [anchor-name & args]
                       (swap! turtle-state
                              #(apply turtle/bezier-to-anchor % anchor-name args)))
   'path-to         (fn [anchor-name]
                      (swap! turtle-state turtle/look-at anchor-name)
                      (let [t2 @turtle-state
                            dist (math/magnitude (math/v- (:position (get-in t2 [:anchors anchor-name]))
                                                          (:position t2)))]
                        (turtle/quick-path [dist])))
   'stamp-impl      (fn [shape] (swap! turtle-state turtle/stamp shape))
   'stamp-closed-impl (fn [shape] (swap! turtle-state turtle/stamp-closed shape))
   'finalize-sweep-impl (fn [] (swap! turtle-state turtle/finalize-sweep))
   'finalize-sweep-closed-impl (fn [] (swap! turtle-state turtle/finalize-sweep-closed))
   'turtle-u        turtle/move-up
   'turtle-d        turtle/move-down
   'turtle-rt       turtle/move-right
   'turtle-lt       turtle/move-left
   ;; Voronoi (if available — stubbed in clipper)
   ;; 'voronoi-shell — requires d3-delaunay, not available in JVM
   ;; SDF operations (libfive via Rust backend)
   'sdf-sphere       sdf/sdf-sphere
   'sdf-box          sdf/sdf-box
   'sdf-cyl          sdf/sdf-cyl
   'sdf-union        sdf/sdf-union
   'sdf-difference   sdf/sdf-difference
   'sdf-intersection sdf/sdf-intersection
   'sdf-blend        sdf/sdf-blend
   'sdf-shell        sdf/sdf-shell
   'sdf-offset       sdf/sdf-offset
   'sdf-morph        sdf/sdf-morph
   'sdf-move         sdf/sdf-move
   'sdf->mesh        sdf/materialize     ;; explicit meshing (for resolution control)
   ;; Utility
   'bench     bench
   ;; Turtle state
   'get-turtle       (fn [] @turtle-state)
   'turtle-position  (fn [] (:position @turtle-state))
   'turtle-heading   (fn [] (:heading @turtle-state))
   'turtle-up        (fn [] (:up @turtle-state))
   ;; Math
   'PI       Math/PI
   'sin      #(Math/sin %)
   'cos      #(Math/cos %)
   'sqrt     #(Math/sqrt %)
   'abs      #(Math/abs (double %))
   'round    #(Math/round (double %))
   'ceil     #(Math/ceil %)
   'floor    #(Math/floor %)
   'pow      #(Math/pow %1 %2)
   'atan2    #(Math/atan2 %1 %2)
   'acos     #(Math/acos %)
   'asin     #(Math/asin %)
   'log      #(Math/log %)
   ;; Vector math
   'vec3+    math/v+
   'vec3-    math/v-
   'vec3*    math/v*
   'dot      math/dot
   'cross    math/cross
   'normalize math/normalize
   ;; Face ops
   'list-faces       faces/list-faces
   'get-face         faces/get-face
   'face-info        faces/face-info
   'face-ids         faces/face-ids
   ;; Face selection (query-based)
   'find-faces        faces/find-faces
   'face-at           faces/face-at
   'face-nearest      faces/face-nearest
   'largest-face      faces/largest-face
   'face-shape        faces/face-shape
   'auto-face-groups  faces/auto-face-groups
   'ensure-face-groups faces/ensure-face-groups
   ;; Edge analysis
   'find-sharp-edges  faces/find-sharp-edges
   'chamfer-prisms    faces/chamfer-prisms
   'chamfer-edges     chamfer-edges-impl
   'chamfer           chamfer-impl
   'fillet            fillet-impl
   'build-chamfer-strip   faces/build-chamfer-strip
   'build-fillet-cutters  faces/build-fillet-cutters
   'make-prism-along-edge faces/make-prism-along-edge
   ;; Measurement
   'bounds     (fn [obj]
                 (cond
                   ;; 2D shape or path
                   (and (map? obj) (#{:shape :path} (:type obj)))
                   (shape/bounds-2d obj)
                   ;; 3D mesh
                   (and (map? obj) (:vertices obj))
                   (let [vs (:vertices obj)
                         xs (map #(% 0) vs) ys (map #(% 1) vs) zs (map #(% 2) vs)
                         min-pt [(apply min xs) (apply min ys) (apply min zs)]
                         max-pt [(apply max xs) (apply max ys) (apply max zs)]]
                     {:min min-pt :max max-pt
                      :size (math/v- max-pt min-pt)
                      :center (math/v* (math/v+ min-pt max-pt) 0.5)})))})

;; ── Macro sources (injected into eval namespace) ────────────────
;; These macros rebind f/th/tv etc. to recorder versions inside their body.

(def ^:private path-macro-source
  "(defmacro path [& body]
     `(let [rec# (atom (ridley.turtle.core/make-recorder))
            ~'f  (fn [d#] (swap! rec# ridley.turtle.core/rec-f d#))
            ~'th (fn [a#] (swap! rec# ridley.turtle.core/rec-th a#))
            ~'tv (fn [a#] (swap! rec# ridley.turtle.core/rec-tv a#))
            ~'tr (fn [a#] (swap! rec# ridley.turtle.core/rec-tr a#))
            ~'u  (fn [d#] (swap! rec# ridley.turtle.core/rec-u d#))
            ~'rt (fn [d#] (swap! rec# ridley.turtle.core/rec-rt d#))
            ~'lt (fn [d#] (swap! rec# ridley.turtle.core/rec-lt d#))
            ~'arc-h (fn [r# a#]
                      ;; Decompose arc into th+f steps in recorder
                      (when-not (or (zero? r#) (zero? a#))
                        (let [angle-rad# (* (Math/abs (double a#)) (/ Math/PI 180))
                              step-count# (max 4 (int (* 16 (/ (Math/abs (double a#)) 360))))
                              step-angle# (/ (double a#) step-count#)
                              step-rad# (/ angle-rad# step-count#)
                              step-dist# (* 2 (double r#) (Math/sin (/ step-rad# 2)))
                              half# (/ step-angle# 2)]
                          (swap! rec# ridley.turtle.core/rec-th half#)
                          (swap! rec# ridley.turtle.core/rec-f step-dist#)
                          (dotimes [_# (dec step-count#)]
                            (swap! rec# ridley.turtle.core/rec-th step-angle#)
                            (swap! rec# ridley.turtle.core/rec-f step-dist#))
                          (swap! rec# ridley.turtle.core/rec-th half#))))
            ~'arc-v (fn [r# a#]
                      (when-not (or (zero? r#) (zero? a#))
                        (let [angle-rad# (* (Math/abs (double a#)) (/ Math/PI 180))
                              step-count# (max 4 (int (* 16 (/ (Math/abs (double a#)) 360))))
                              step-angle# (/ (double a#) step-count#)
                              step-rad# (/ angle-rad# step-count#)
                              step-dist# (* 2 (double r#) (Math/sin (/ step-rad# 2)))
                              half# (/ step-angle# 2)]
                          (swap! rec# ridley.turtle.core/rec-tv half#)
                          (swap! rec# ridley.turtle.core/rec-f step-dist#)
                          (dotimes [_# (dec step-count#)]
                            (swap! rec# ridley.turtle.core/rec-tv step-angle#)
                            (swap! rec# ridley.turtle.core/rec-f step-dist#))
                          (swap! rec# ridley.turtle.core/rec-tv half#))))
            ~'follow (fn [p#] (swap! rec# ridley.turtle.core/rec-play-path p#))
            ~'mark (fn [name#] (swap! rec# (fn [s#] (update s# :recording conj {:cmd :mark :args [name#]}))))
            ~'inset (fn [amount#] (swap! rec# ridley.turtle.core/rec-inset amount#))
            ~'scale (fn [factor#] (swap! rec# ridley.turtle.core/rec-scale factor#))]
        ~@body
        (let [result# @rec#
              body-result# ~(last body)]
          (if (and (map? body-result#) (= :path (:type body-result#)))
            body-result#
            (ridley.turtle.core/path-from-recorder result#)))))")

(def ^:private extrude-closed-macro-source
  "(defmacro extrude-closed [shape & movements]
     `(ridley.jvm.eval/extrude-closed-impl ~shape (path ~@movements)))")

(def ^:private extrude-macro-source
  "(defmacro extrude [shape & movements]
     ;; Always wrap movements in path — never evaluate them bare,
     ;; as (f x) would mutate the global turtle state.
     ;; If a pre-built path is passed, path macro returns it as-is.
     `(ridley.jvm.eval/extrude-impl ~shape (path ~@movements)))")

(def ^:private transform->macro-source
  "(defmacro transform-> [shape-or-end-face & steps]
     (let [step-forms
           (mapv (fn [form]
                   (if (and (list? form) (seq form))
                     (let [op (first form)
                           all-args (rest form)
                           ;; Split args: movement args before :mark, then :mark name cap
                           mark-idx (some (fn [i] (when (= :mark (nth (vec all-args) i nil)) i))
                                         (range (count all-args)))
                           [main-args mark-args] (if mark-idx
                                                   [(take mark-idx all-args) (drop mark-idx all-args)]
                                                   [all-args nil])
                           mark-name (when mark-args (second mark-args))
                           mark-cap (when mark-args (nth (vec mark-args) 2 nil))]
                       (case op
                         extrude+ (cond-> `{:op :extrude+ :args [(path ~@main-args)]}
                                    mark-name (assoc :mark mark-name)
                                    mark-cap (assoc :mark-cap mark-cap))
                         revolve+ (cond-> `{:op :revolve+ :args [~@main-args]}
                                    mark-name (assoc :mark mark-name)
                                    mark-cap (assoc :mark-cap mark-cap))
                         (throw (Exception. (str \"transform->: unknown op \" op)))))
                     (throw (Exception. (str \"transform->: expected (op args...), got \" form)))))
                 steps)]
       `(ridley.jvm.eval/transform->impl ~shape-or-end-face ~step-forms)))")

(def ^:private extrude+-macro-source
  "(defmacro extrude+ [shape & movements]
     `(ridley.jvm.eval/extrude+-impl ~shape (path ~@movements)))")

(def ^:private revolve+-macro-source
  "(defmacro revolve+
     ([shape]
      `(ridley.jvm.eval/revolve+-impl ~shape))
     ([shape angle & opts]
      `(ridley.jvm.eval/revolve+-impl ~shape ~angle ~@opts)))")

(def ^:private loft-macro-source
  "(defmacro loft [first-arg & rest-args]
     (let [mvmt? (fn [x#] (and (list? x#) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v} (first x#))))]
       (cond
         ;; Single rest arg: always shape-fn + path
         (= 1 (count rest-args))
         `(ridley.jvm.eval/loft-impl ~first-arg (path ~(first rest-args)))

         ;; First rest-arg is a movement: all are movements
         (mvmt? (first rest-args))
         `(ridley.jvm.eval/loft-impl ~first-arg (path ~@rest-args))

         ;; Otherwise: first rest-arg is dispatch (transform-fn or shape), rest are movements
         :else
         (let [[dispatch-arg# & movements#] rest-args]
           `(ridley.jvm.eval/loft-impl ~first-arg ~dispatch-arg# (path ~@movements#))))))")

(def ^:private attach-macro-source
  "(defmacro attach [mesh & body]
     `(let [saved# @ridley.jvm.eval/turtle-state
            obj# ~mesh]
        ;; SDF nodes: capture displacement as sdf-move
        (if (and (map? obj#) (:op obj#))
          (do
            (reset! ridley.jvm.eval/turtle-state (ridley.turtle.core/make-turtle))
            ~@body
            (let [t# @ridley.jvm.eval/turtle-state
                  p# (:position t#)]
              (reset! ridley.jvm.eval/turtle-state saved#)
              (ridley.sdf.core/sdf-move obj# (p# 0) (p# 1) (p# 2))))
          ;; Mesh: use group-transform
          (let [pose# (or (:creation-pose obj#)
                           {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
                p0# (:position pose#)
                h0# (ridley.math/normalize (:heading pose#))
                u0# (ridley.math/normalize (:up pose#))]
            (reset! ridley.jvm.eval/turtle-state
                    (assoc (ridley.turtle.core/make-turtle)
                           :position p0# :heading h0# :up u0#))
            ~@body
            (let [t# @ridley.jvm.eval/turtle-state
                  p1# (:position t#)
                  h1# (ridley.math/normalize (:heading t#))
                  u1# (ridley.math/normalize (:up t#))
                  result# (first (ridley.turtle.attachment/group-transform
                                   [obj#] p0# h0# u0# p1# h1# u1#))]
              (reset! ridley.jvm.eval/turtle-state saved#)
              result#)))))")

(def ^:private loft-n-macro-source
  "(defmacro loft-n [steps first-arg & rest-args]
     (let [mvmt? (fn [x#] (and (list? x#) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v} (first x#))))]
       (cond
         (= 1 (count rest-args))
         `(ridley.jvm.eval/loft-n-impl ~steps ~first-arg (path ~(first rest-args)))

         (mvmt? (first rest-args))
         `(ridley.jvm.eval/loft-n-impl ~steps ~first-arg (path ~@rest-args))

         :else
         (let [[dispatch-arg# & movements#] rest-args]
           `(ridley.jvm.eval/loft-n-impl ~steps ~first-arg ~dispatch-arg# (path ~@movements#))))))")

(def ^:private bloft-macro-source
  "(defmacro bloft [first-arg & rest-args]
     (let [mvmt? (fn [x#] (and (list? x#) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v} (first x#))))]
       (cond
         (= 1 (count rest-args))
         `(ridley.jvm.eval/bloft-impl ~first-arg (path ~(first rest-args)))

         (mvmt? (first rest-args))
         `(ridley.jvm.eval/bloft-impl ~first-arg (path ~@rest-args))

         :else
         (let [[dispatch-arg# & args#] rest-args]
           (cond
             (and (seq args#) (mvmt? (first args#)))
             `(ridley.jvm.eval/bloft-impl ~first-arg ~dispatch-arg# (path ~@args#))

             (= 1 (count args#))
             `(ridley.jvm.eval/bloft-impl ~first-arg ~dispatch-arg# (path ~(first args#)))

             :else
             `(ridley.jvm.eval/bloft-impl ~first-arg ~dispatch-arg# (path ~@args#)))))))")

(def ^:private bloft-n-macro-source
  "(defmacro bloft-n [steps first-arg & rest-args]
     (let [mvmt? (fn [x#] (and (list? x#) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v} (first x#))))]
       (cond
         (= 1 (count rest-args))
         `(ridley.jvm.eval/bloft-impl ~first-arg nil (path ~(first rest-args)) ~steps)

         (mvmt? (first rest-args))
         `(ridley.jvm.eval/bloft-impl ~first-arg nil (path ~@rest-args) ~steps)

         :else
         (let [[dispatch-arg# & movements#] rest-args]
           `(ridley.jvm.eval/bloft-impl ~first-arg ~dispatch-arg# (path ~@movements#) ~steps)))))")

(def ^:private revolve-macro-source
  "(defmacro revolve
     ([shape]
      `(ridley.jvm.eval/revolve-impl ~shape))
     ([shape angle & opts]
      `(ridley.jvm.eval/revolve-impl ~shape ~angle ~@opts)))")

(def ^:private shape-macro-source
  "(defmacro shape [& body]
     `(let [state# (atom (ridley.turtle.shape/recording-turtle))
            ~'f (fn [d#] (swap! state# ridley.turtle.shape/rec-f d#))
            ~'th (fn [a#] (swap! state# ridley.turtle.shape/rec-th a#))
            ~'tv (fn [& _#] (throw (Exception. \"tv not allowed in shape - 2D only\")))
            ~'tr (fn [& _#] (throw (Exception. \"tr not allowed in shape - 2D only\")))]
        ~@body
        (ridley.turtle.shape/shape-from-recording @state#)))")

(def ^:private pen-macro-source
  "(defmacro pen [mode]
     `(ridley.jvm.eval/implicit-pen ~mode nil))")

(def ^:private smooth-path-macro-source
  "(defmacro smooth-path [p & opts]
     `(~'path (~'bezier-as ~p ~@opts)))")

(def ^:private attach-face-macro-source
  "(defmacro attach-face [first-arg & rest]
     (let [[mesh# face-id# body#]
           (if (and (seq rest)
                    (let [f# (first rest)]
                      (or (keyword? f#) (number? f#) (vector? f#) (symbol? f#))))
             [first-arg (first rest) (next rest)]
             [first-arg nil rest])]
       `(ridley.jvm.eval/attach-face-impl ~mesh# ~face-id# (path ~@body#))))")

(def ^:private clone-face-macro-source
  "(defmacro clone-face [first-arg & rest]
     (let [[mesh# face-id# body#]
           (if (and (seq rest)
                    (let [f# (first rest)]
                      (or (keyword? f#) (number? f#) (vector? f#) (symbol? f#))))
             [first-arg (first rest) (next rest)]
             [first-arg nil rest])]
       `(ridley.jvm.eval/clone-face-impl ~mesh# ~face-id# (path ~@body#))))")

(def ^:private turtle-macro-source
  "(defmacro turtle [& args]
     ;; Parse compile-time keyword args: :reset, :preserve-up, [x y z] literal
     ;; For runtime pose maps: (turtle (:pose top) body...) or (turtle my-pose body...)
     (let [args-vec (vec args)
           parse (fn parse [opts remaining]
                   (if (empty? remaining)
                     {:opts opts :body []}
                     (let [x (first remaining)]
                       (cond
                         (= :reset x) (recur (assoc opts :reset true) (subvec remaining 1))
                         (= :preserve-up x) (recur (assoc opts :preserve-up true) (subvec remaining 1))
                         (vector? x) (recur (assoc opts :pos x) (subvec remaining 1))
                         (and (map? x) (some #{:pos :heading :up :reset :preserve-up} (keys x)))
                         (recur (merge opts x) (subvec remaining 1))
                         :else {:opts opts :body (vec remaining)}))))
           {:keys [opts body]} (parse {} args-vec)
           opts-form (if (empty? opts) {} opts)
           ;; If no compile-time opts and first body form looks like a pose reference
           ;; (symbol or keyword-access, not a function call like (f 30))
           first-form (when (seq body) (first body))
           runtime-pose? (and (empty? opts) (> (count body) 1)
                              (or (symbol? first-form)
                                  ;; (:keyword expr) accessor pattern
                                  (and (list? first-form) (keyword? (first first-form)))))]
       (if runtime-pose?
         (let [pose-expr (first body)
               rest-body (rest body)]
           `(let [saved# @ridley.jvm.eval/turtle-state
                  maybe-pose# ~pose-expr
                  opts# (if (and (map? maybe-pose#)
                                 (some #{:pos :heading :up} (keys maybe-pose#)))
                           maybe-pose#
                           {})]
              (reset! ridley.jvm.eval/turtle-state
                      (ridley.jvm.eval/init-turtle opts# saved#))
              (try
                (do ~@rest-body)
                (finally
                  (reset! ridley.jvm.eval/turtle-state saved#)))))
         `(let [saved# @ridley.jvm.eval/turtle-state]
            (reset! ridley.jvm.eval/turtle-state
                    (ridley.jvm.eval/init-turtle ~opts-form saved#))
            (try
              (do ~@body)
              (finally
                (reset! ridley.jvm.eval/turtle-state saved#)))))))")

(def ^:private warp-macro-source
  "(defmacro warp [mesh volume & args]
     `(warp-impl ~mesh ~volume ~@args))")

(def ^:private register-macro-source
  "(defmacro register [name expr & opts]
     `(let [v# ~expr]
        (ridley.jvm.eval/register-impl '~name v#)
        (def ~name v#)
        v#))")

(defn eval-script
  "Evaluate a DSL script string. Returns {:meshes map :print-output str}."
  [script-text]
  (reset-state!)
  (let [ns-sym (gensym "ridley-eval-")
        ns-obj (create-ns ns-sym)
        output (java.io.StringWriter.)]
    (try
      (binding [*ns* ns-obj]
        (refer 'clojure.core))
      (doseq [[sym val] dsl-bindings]
        (intern ns-obj sym val))
      ;; Inject macros
      (binding [*ns* ns-obj]
        (load-string path-macro-source)
        (load-string extrude-macro-source)
        (load-string extrude-closed-macro-source)
        (load-string loft-macro-source)
        (load-string loft-n-macro-source)
        (load-string bloft-macro-source)
        (load-string bloft-n-macro-source)
        (load-string revolve-macro-source)
        (load-string extrude+-macro-source)
        (load-string revolve+-macro-source)
        (load-string transform->macro-source)
        (load-string shape-macro-source)
        (load-string pen-macro-source)
        (load-string smooth-path-macro-source)
        (load-string warp-macro-source)
        (load-string attach-macro-source)
        (load-string attach-face-macro-source)
        (load-string clone-face-macro-source)
        (load-string turtle-macro-source)
        (load-string register-macro-source))
      ;; Eval script, capturing print output
      (binding [*ns* ns-obj
                *out* output]
        (load-string script-text))
      ;; Collect stamps from global accumulator (survives turtle scopes)
      (let [stamps @stamp-accumulator
            stamp-meshes (when (seq stamps)
                           (reduce (fn [m [i mesh]]
                                     (assoc m (symbol (str "__stamp_" i)) mesh))
                                   {} (map-indexed vector stamps)))]
        {:meshes (merge @registered-meshes stamp-meshes)
         :print-output (str output)})
      (finally
        (remove-ns ns-sym)))))
