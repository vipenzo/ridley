(ns ridley.jvm.eval
  "DSL eval engine for the JVM sidecar.
   Creates a namespace with all DSL bindings, evals user scripts in it."
  (:require [ridley.math :as math]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.extrusion :as extrusion]
            [ridley.turtle.loft :as loft]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.turtle.path :as path]
            [ridley.turtle.transform :as xform]
            [ridley.geometry.primitives :as prims]
            [ridley.geometry.operations :as ops]
            [ridley.geometry.faces :as faces]
            [ridley.manifold.native :as manifold]
            [ridley.clipper.core :as clipper]))

;; ── Turtle state (global, reset per eval) ───────────────────────
(def turtle-state (atom (turtle/make-turtle)))
(def registered-meshes (atom {}))

(defn reset-state! []
  (reset! turtle-state (turtle/make-turtle))
  (reset! registered-meshes {}))

;; ── Implicit turtle commands (mutate global state) ──────────────

(defn- implicit-f [dist]
  (swap! turtle-state turtle/f dist))

(defn- implicit-th [angle]
  (swap! turtle-state turtle/th angle))

(defn- implicit-tv [angle]
  (swap! turtle-state turtle/tv angle))

(defn- implicit-tr [angle]
  (swap! turtle-state turtle/tr angle))

(defn- implicit-u [dist]
  (swap! turtle-state turtle/move-up dist))

(defn- implicit-d [dist]
  (swap! turtle-state turtle/move-down dist))

(defn- implicit-rt [dist]
  (swap! turtle-state turtle/move-right dist))

(defn- implicit-lt [dist]
  (swap! turtle-state turtle/move-left dist))

(defn- implicit-pen-up []
  (swap! turtle-state turtle/pen-up))

(defn- implicit-pen-down []
  (swap! turtle-state turtle/pen-down))

;; ── Geometry helpers ────────────────────────────────────────────

(defn- with-creation-pose [mesh]
  (let [t @turtle-state]
    (assoc mesh :creation-pose
           {:position (:position t)
            :heading (:heading t)
            :up (:up t)})))

(defn- box-impl [sx sy sz]
  (with-creation-pose (prims/box-mesh sx sy sz)))

(defn- sphere-impl
  ([r] (sphere-impl r 16 12))
  ([r segments rings]
   (with-creation-pose (prims/sphere-mesh r segments rings))))

(defn- cyl-impl
  ([r h] (cyl-impl r h 32))
  ([r h n]
   (with-creation-pose (prims/cyl-mesh r h n))))

(defn- cone-impl
  ([r h] (cone-impl r h 32))
  ([r h n]
   (with-creation-pose (prims/cone-mesh r h n))))

(defn- circle-impl
  ([r] (circle-impl r 32))
  ([r n] (shape/circle-shape r n)))

;; ── Register ────────────────────────────────────────────────────

(defn- register-impl [name mesh]
  (swap! registered-meshes assoc name mesh)
  mesh)

;; ── Bench ───────────────────────────────────────────────────────

(defn bench [label f]
  (let [t0 (System/nanoTime)
        result (f)
        t1 (System/nanoTime)]
    (println (str label ": " (format "%.1f" (/ (- t1 t0) 1e6)) "ms"))
    result))

;; ── DSL Namespace Setup ─────────────────────────────────────────

(def dsl-bindings
  "Map of symbol → value for the DSL namespace."
  {;; Turtle movement (implicit, mutate state)
   'f    implicit-f
   'th   implicit-th
   'tv   implicit-tv
   'tr   implicit-tr
   'u    implicit-u
   'd    implicit-d
   'rt   implicit-rt
   'lt   implicit-lt
   ;; Pen
   'pen-up    implicit-pen-up
   'pen-down  implicit-pen-down
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
   'translate-shape shape/translate-shape
   'scale-shape   shape/scale-shape
   ;; Shape-fn
   'tapered  sfn/tapered
   'twisted  sfn/twisted
   'rugged   sfn/rugged
   'fluted   sfn/fluted
   'displaced sfn/displaced
   'morphed  sfn/morphed
   'noisy    sfn/noisy
   'woven    sfn/woven
   'profile  sfn/profile
   'capped   sfn/capped
   'shell    sfn/shell
   ;; transform-mesh
   'transform turtle/transform-mesh
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
   ;; 2D booleans (stubs)
   'shape-union        clipper/shape-union
   'shape-difference   clipper/shape-difference
   'shape-intersection clipper/shape-intersection
   'shape-offset       clipper/shape-offset
   ;; Register
   'register  register-impl
   'color     (fn [& _] nil)
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
   'list-faces faces/list-faces
   'get-face   faces/get-face
   'face-info  faces/face-info
   'face-ids   faces/face-ids
   ;; Measurement
   'bounds     (fn [mesh] (let [vs (:vertices mesh)
                                 xs (map #(% 0) vs) ys (map #(% 1) vs) zs (map #(% 2) vs)]
                             {:min [(apply min xs) (apply min ys) (apply min zs)]
                              :max [(apply max xs) (apply max ys) (apply max zs)]}))})

;; ── Attach macro source ─────────────────────────────────────────
;; attach must be a macro because (attach mesh (f 10) (th 90))
;; needs to execute f/th in the context of the attached turtle state.

(def ^:private attach-macro-source
  "(defmacro attach [mesh & body]
     `(let [saved# @ridley.jvm.eval/turtle-state
            ;; Get creation pose from mesh (or default)
            pose# (or (:creation-pose ~mesh)
                       {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
            p0# (:position pose#)
            h0# (ridley.math/normalize (:heading pose#))
            u0# (ridley.math/normalize (:up pose#))]
        ;; Set turtle to mesh's creation pose, execute body movements
        (reset! ridley.jvm.eval/turtle-state
                (assoc (ridley.turtle.core/make-turtle)
                       :position p0# :heading h0# :up u0#))
        ~@body
        ;; Turtle now at final pose — transform mesh vertices
        (let [t# @ridley.jvm.eval/turtle-state
              p1# (:position t#)
              h1# (ridley.math/normalize (:heading t#))
              u1# (ridley.math/normalize (:up t#))
              result# (ridley.turtle.attachment/group-transform
                        [~mesh] p0# h0# u0# p1# h1# u1#)]
          (reset! ridley.jvm.eval/turtle-state saved#)
          (first result#))))")

;; Extrude/loft macros — they need path (which captures turtle movements)
(def ^:private extrude-macro-source
  "(defmacro extrude [shape & movements]
     `(let [saved# @ridley.jvm.eval/turtle-state
            fresh# (ridley.turtle.core/make-turtle)]
        (reset! ridley.jvm.eval/turtle-state fresh#)
        ~@movements
        (let [path-data# (ridley.turtle.path/path-from-state @ridley.jvm.eval/turtle-state)]
          (reset! ridley.jvm.eval/turtle-state saved#)
          (ridley.turtle.extrusion/extrude-from-path ~shape path-data# {}))))")

(def ^:private loft-macro-source
  "(defmacro loft [shape transform-fn & movements]
     `(let [saved# @ridley.jvm.eval/turtle-state
            fresh# (ridley.turtle.core/make-turtle)]
        (reset! ridley.jvm.eval/turtle-state fresh#)
        ~@movements
        (let [path-data# (ridley.turtle.path/path-from-state @ridley.jvm.eval/turtle-state)]
          (reset! ridley.jvm.eval/turtle-state saved#)
          (ridley.turtle.loft/loft-from-path ~shape ~transform-fn path-data# {}))))")

(defn eval-script
  "Evaluate a DSL script string. Returns map of registered meshes."
  [script-text]
  (reset-state!)
  (let [ns-sym (gensym "ridley-eval-")
        ns-obj (create-ns ns-sym)]
    (try
      ;; Intern Clojure core
      (binding [*ns* ns-obj]
        (refer 'clojure.core))
      ;; Intern all DSL bindings
      (doseq [[sym val] dsl-bindings]
        (intern ns-obj sym val))
      ;; Inject macros (attach, extrude, loft)
      (binding [*ns* ns-obj]
        (load-string attach-macro-source)
        (load-string extrude-macro-source)
        (load-string loft-macro-source))
      ;; Eval the script
      (binding [*ns* ns-obj]
        (load-string script-text))
      @registered-meshes
      (finally
        (remove-ns ns-sym)))))
