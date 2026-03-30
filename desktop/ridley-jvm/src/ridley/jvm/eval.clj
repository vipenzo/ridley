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
            [ridley.sdf.core :as sdf]))

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
(defn implicit-arc-h [radius angle] (swap! turtle-state turtle/arc-h radius angle))
(defn implicit-arc-v [radius angle] (swap! turtle-state turtle/arc-v radius angle))
(defn implicit-pen [mode shape] (swap! turtle-state turtle/pen mode shape))
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

;; ── Register ────────────────────────────────────────────────────

(defn register-impl [name value]
  (let [res (get-in @turtle-state [:resolution :value] 15)
        mesh (binding [sdf/*sdf-resolution* res]
               (sdf/ensure-mesh value))]
    (swap! registered-meshes assoc name mesh)
    mesh))

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
   ;; Generative ops
   'revolve      ops/revolve
   ;; Turtle extras
   'joint-mode   (fn [mode] (swap! turtle-state assoc :joint-mode mode))
   'inset        (fn [dist] (swap! turtle-state attachment/inset dist))
   'get-anchor   (fn [name] (get-in @turtle-state [:anchors name]))
   'follow-path  (fn [p] (swap! turtle-state turtle/run-path p))
   'path?        turtle/path?
   'shape?       shape/shape?
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
   'color     (fn [& _] nil)
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
     (if (= 1 (count movements))
       `(let [arg# ~(first movements)]
          (if (and (map? arg#) (= :path (:type arg#)))
            (ridley.jvm.eval/extrude-closed-impl ~shape arg#)
            (ridley.jvm.eval/extrude-closed-impl ~shape (path ~(first movements)))))
       `(ridley.jvm.eval/extrude-closed-impl ~shape (path ~@movements))))")

(def ^:private extrude-macro-source
  "(defmacro extrude [shape & movements]
     (if (= 1 (count movements))
       ;; Single arg: might be a path value or a single movement
       `(let [arg# ~(first movements)]
          (if (and (map? arg#) (= :path (:type arg#)))
            (ridley.jvm.eval/extrude-impl ~shape arg#)
            (ridley.jvm.eval/extrude-impl ~shape (path ~(first movements)))))
       ;; Multiple movements: always wrap in path
       `(ridley.jvm.eval/extrude-impl ~shape (path ~@movements))))")

(defn- ensure-path
  "If x is already a path, return it. Otherwise wrap in path recorder."
  [x]
  (if (and (map? x) (= :path (:type x)))
    x
    ;; Not a path — can't record here, caller should use (path ...) macro
    (throw (Exception. (str "Expected a path, got " (type x))))))

(def ^:private loft-macro-source
  "(defmacro loft [first-arg & rest-args]
     (let [mvmt? (fn [x#] (and (list? x#) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v} (first x#))))]
       (cond
         (= 1 (count rest-args))
         (let [arg (first rest-args)]
           `(let [a# ~arg]
              (if (and (map? a#) (= :path (:type a#)))
                (ridley.jvm.eval/loft-impl ~first-arg a#)
                (ridley.jvm.eval/loft-impl ~first-arg (path ~arg)))))

         (mvmt? (first rest-args))
         `(ridley.jvm.eval/loft-impl ~first-arg (path ~@rest-args))

         :else
         (let [[dispatch-arg# & movements#] rest-args]
           (if (= 1 (count movements#))
             `(let [a# ~(first movements#)]
                (if (and (map? a#) (= :path (:type a#)))
                  (ridley.jvm.eval/loft-impl ~first-arg ~dispatch-arg# a#)
                  (ridley.jvm.eval/loft-impl ~first-arg ~dispatch-arg# (path ~(first movements#)))))
             `(ridley.jvm.eval/loft-impl ~first-arg ~dispatch-arg# (path ~@movements#)))))))")

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
        (load-string attach-macro-source)
        (load-string register-macro-source))
      ;; Eval script, capturing print output
      (binding [*ns* ns-obj
                *out* output]
        (load-string script-text))
      {:meshes @registered-meshes
       :print-output (str output)}
      (finally
        (remove-ns ns-sym)))))
