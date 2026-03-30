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
            [ridley.clipper.core :as clipper]))

;; ── Turtle state (global, reset per eval) ───────────────────────
(def turtle-state (atom (turtle/make-turtle)))
(def registered-meshes (atom {}))

(defn reset-state! []
  (reset! turtle-state (turtle/make-turtle))
  (reset! registered-meshes {}))

;; ── Implicit turtle commands (mutate global state) ──────────────

(defn implicit-f [dist] (swap! turtle-state turtle/f dist))
(defn implicit-th [angle] (swap! turtle-state turtle/th angle))
(defn implicit-tv [angle] (swap! turtle-state turtle/tv angle))
(defn implicit-tr [angle] (swap! turtle-state turtle/tr angle))
(defn implicit-u [dist] (swap! turtle-state turtle/move-up dist))
(defn implicit-d [dist] (swap! turtle-state turtle/move-down dist))
(defn implicit-rt [dist] (swap! turtle-state turtle/move-right dist))
(defn implicit-lt [dist] (swap! turtle-state turtle/move-left dist))

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

;; ── Register ────────────────────────────────────────────────────

(defn register-impl [name mesh]
  (swap! registered-meshes assoc name mesh)
  mesh)

;; ── Bench ───────────────────────────────────────────────────────

(defn bench [label f]
  (let [t0 (System/nanoTime)
        result (f)
        t1 (System/nanoTime)]
    (println (str label ": " (format "%.1f" (/ (- t1 t0) 1e6)) "ms"))
    result))

;; ── Extrude/Loft impl functions (called from macros) ────────────

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
  "loft-impl: dispatch based on args"
  ([first-arg path-data]
   (let [current @turtle-state
         initial (-> (turtle/make-turtle)
                     (assoc :position (:position current))
                     (assoc :heading (:heading current))
                     (assoc :up (:up current))
                     (assoc :resolution (:resolution current)))]
     (cond
       (sfn/shape-fn? first-arg)
       (let [state (loft/loft-from-path initial first-arg nil path-data)
             mesh (last (:meshes state))]
         (when mesh
           (assoc mesh :creation-pose
                  {:position (:position current) :heading (:heading current) :up (:up current)})))

       :else
       (throw (Exception. "loft: 2-arg form requires a shape-fn as first argument")))))
  ([first-arg second-arg path-data]
   (let [current @turtle-state
         initial (-> (turtle/make-turtle)
                     (assoc :position (:position current))
                     (assoc :heading (:heading current))
                     (assoc :up (:up current))
                     (assoc :resolution (:resolution current)))]
     (cond
       (sfn/shape-fn? first-arg)
       (loft-impl first-arg path-data)

       (shape/shape? second-arg)
       ;; Two-shape loft: use loft-from-path with a morphed shape-fn
       (let [morphed (sfn/morphed first-arg second-arg)
             state (loft/loft-from-path initial morphed nil path-data)
             mesh (last (:meshes state))]
         (when mesh
           (assoc mesh :creation-pose
                  {:position (:position current) :heading (:heading current) :up (:up current)})))

       :else  ;; legacy transform-fn mode
       (let [state (loft/loft-from-path initial first-arg second-arg path-data)
             mesh (last (:meshes state))]
         (when mesh
           (assoc mesh :creation-pose
                  {:position (:position current) :heading (:heading current) :up (:up current)})))))))

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
   ;; transform
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
            ~'lt (fn [d#] (swap! rec# ridley.turtle.core/rec-lt d#))]
        ~@body
        (ridley.turtle.core/path-from-recorder @rec#)))")

(def ^:private extrude-macro-source
  "(defmacro extrude [shape & movements]
     `(ridley.jvm.eval/extrude-impl ~shape (path ~@movements)))")

(def ^:private loft-macro-source
  "(defmacro loft [first-arg & rest-args]
     (let [mvmt? (fn [x#] (and (list? x#) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v} (first x#))))]
       (cond
         (= 1 (count rest-args))
         `(ridley.jvm.eval/loft-impl ~first-arg (path ~(first rest-args)))

         (mvmt? (first rest-args))
         `(ridley.jvm.eval/loft-impl ~first-arg (path ~@rest-args))

         :else
         (let [[dispatch-arg# & movements#] rest-args]
           (if (seq movements#)
             `(ridley.jvm.eval/loft-impl ~first-arg ~dispatch-arg# (path ~@movements#))
             `(ridley.jvm.eval/loft-impl ~first-arg (path ~dispatch-arg#)))))))")

(def ^:private attach-macro-source
  "(defmacro attach [mesh & body]
     `(let [saved# @ridley.jvm.eval/turtle-state
            pose# (or (:creation-pose ~mesh)
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
                               [~mesh] p0# h0# u0# p1# h1# u1#))]
          (reset! ridley.jvm.eval/turtle-state saved#)
          result#)))")

(defn eval-script
  "Evaluate a DSL script string. Returns map of registered meshes."
  [script-text]
  (reset-state!)
  (let [ns-sym (gensym "ridley-eval-")
        ns-obj (create-ns ns-sym)]
    (try
      (binding [*ns* ns-obj]
        (refer 'clojure.core))
      (doseq [[sym val] dsl-bindings]
        (intern ns-obj sym val))
      ;; Inject macros
      (binding [*ns* ns-obj]
        (load-string path-macro-source)
        (load-string extrude-macro-source)
        (load-string loft-macro-source)
        (load-string attach-macro-source))
      ;; Eval script
      (binding [*ns* ns-obj]
        (load-string script-text))
      @registered-meshes
      (finally
        (remove-ns ns-sym)))))
