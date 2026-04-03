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
(declare pure-loft-path pure-loft-two-shapes pure-loft-shape-fn)

;; ── Turtle state (global, reset per eval) ───────────────────────
(def turtle-state (atom (turtle/make-turtle)))
(def registered-meshes (atom {}))
(def registered-values (atom {}))

(defn reset-state! []
  (reset! turtle-state (turtle/make-turtle))
  (reset! registered-meshes {})
  (reset! registered-values {}))

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
(defn implicit-stamp [shape] (swap! turtle-state turtle/stamp shape))
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
  (swap! turtle-state turtle/goto anchor-name))
(defn implicit-look-at [anchor-name]
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

(defn attach-face-impl
  "Attach to a face and replay path. Returns modified mesh."
  [mesh face-id path]
  (let [state (-> (turtle/make-turtle)
                  (turtle/attach-face mesh face-id))
        state (replay-path-commands state path)]
    (or (get-in state [:attached :mesh]) mesh)))

(defn clone-face-impl
  "Attach to a face with extrusion (clone), replay path. Returns modified mesh."
  [mesh face-id path]
  (let [state (-> (turtle/make-turtle)
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

(defn register-impl [name value]
  (let [res (get-in @turtle-state [:resolution :value] 15)
        mesh (binding [sdf/*sdf-resolution* res]
               (sdf/ensure-mesh value))]
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

(defn extrude-impl
  "extrude-impl: shape + path-data → mesh"
  [shape path-data]
  (let [current @turtle-state
        initial (-> (turtle/make-turtle)
                    (assoc :position (:position current))
                    (assoc :heading (:heading current))
                    (assoc :up (:up current))
                    (assoc :resolution (:resolution current)))
        state (turtle/extrude-from-path initial shape path-data)
        mesh (last (:meshes state))]
    (when mesh
      (assoc mesh :creation-pose
             {:position (:position current) :heading (:heading current) :up (:up current)}))))

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

(defn revolve-impl
  "revolve-impl: revolve shape or shape-fn around turtle's axis."
  ([shape-or-fn]
   (revolve-impl shape-or-fn 360))
  ([shape-or-fn angle]
   (let [current @turtle-state
         initial (-> (turtle/make-turtle)
                     (assoc :position (:position current))
                     (assoc :heading (:heading current))
                     (assoc :up (:up current))
                     (assoc :resolution (:resolution current)))
         creation-pose {:position (:position current) :heading (:heading current) :up (:up current)}]
     (if (sfn/shape-fn? shape-or-fn)
       (let [base-shape (shape-or-fn 0)
             state (turtle/revolve-shape initial base-shape angle shape-or-fn)
             mesh (last (:meshes state))]
         (when mesh (assoc mesh :creation-pose creation-pose)))
       (let [state (turtle/revolve-shape initial shape-or-fn angle)
             mesh (last (:meshes state))]
         (when mesh (assoc mesh :creation-pose creation-pose)))))))

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
   'find-sharp-edges faces/find-sharp-edges
   'chamfer-prisms   faces/chamfer-prisms
   ;; Measurement
   'bounds     (fn [mesh] (let [vs (:vertices mesh)
                                 xs (map #(% 0) vs) ys (map #(% 1) vs) zs (map #(% 2) vs)]
                             {:min [(apply min xs) (apply min ys) (apply min zs)]
                              :max [(apply max xs) (apply max ys) (apply max zs)]}))})

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
            ~'mark (fn [name#] (swap! rec# (fn [s#] (update s# :recording conj {:cmd :mark :args [name#]}))))]
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
     ([shape angle]
      `(ridley.jvm.eval/revolve-impl ~shape ~angle)))")

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
     ;; Parse optional leading keyword args: :reset, :preserve-up, [x y z] position
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
           opts-form (if (empty? opts) {} opts)]
       `(let [saved# @ridley.jvm.eval/turtle-state]
          (reset! ridley.jvm.eval/turtle-state
                  (ridley.jvm.eval/init-turtle ~opts-form saved#))
          (try
            ~@body
            (finally
              (reset! ridley.jvm.eval/turtle-state saved#))))))")

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
      {:meshes @registered-meshes
       :print-output (str output)}
      (finally
        (remove-ns ns-sym)))))
