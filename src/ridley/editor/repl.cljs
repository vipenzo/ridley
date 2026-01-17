(ns ridley.editor.repl
  "SCI-based evaluation of user code.

   Two-phase evaluation:
   1. Explicit section: Full Clojure for definitions, functions, data
   2. Implicit section: Turtle commands that mutate a global atom

   Both phases share the same SCI context, so definitions from explicit
   are available in implicit."
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.path :as path]
            [ridley.turtle.transform :as xform]
            [ridley.geometry.primitives :as prims]
            [ridley.geometry.operations :as ops]
            [ridley.geometry.faces :as faces]
            [ridley.manifold.core :as manifold]
            [ridley.scene.registry :as registry]
            [ridley.export.stl :as stl]))

;; Global turtle state for implicit mode
(defonce ^:private turtle-atom (atom nil))

(defn- reset-turtle! []
  (reset! turtle-atom (turtle/make-turtle)))

;; ============================================================
;; Implicit turtle functions (mutate atom)
;; ============================================================

(defn- implicit-f [dist]
  (swap! turtle-atom turtle/f dist))

(defn- implicit-th [angle]
  (swap! turtle-atom turtle/th angle))

(defn- implicit-tv [angle]
  (swap! turtle-atom turtle/tv angle))

(defn- implicit-tr [angle]
  (swap! turtle-atom turtle/tr angle))

(defn- implicit-pen-up []
  (swap! turtle-atom turtle/pen-up))

(defn- implicit-pen-down []
  (swap! turtle-atom turtle/pen-down))

(defn- implicit-pen [mode]
  (swap! turtle-atom turtle/pen mode))

(defn- implicit-box
  ([size] (swap! turtle-atom prims/box size))
  ([sx sy sz] (swap! turtle-atom prims/box sx sy sz)))

(defn- implicit-sphere
  ([radius] (swap! turtle-atom prims/sphere radius))
  ([radius segments rings] (swap! turtle-atom prims/sphere radius segments rings)))

(defn- implicit-cyl
  ([radius height] (swap! turtle-atom prims/cyl radius height))
  ([radius height segments] (swap! turtle-atom prims/cyl radius height segments)))

(defn- implicit-cone
  ([r1 r2 height] (swap! turtle-atom prims/cone r1 r2 height))
  ([r1 r2 height segments] (swap! turtle-atom prims/cone r1 r2 height segments)))

(defn- get-turtle []
  @turtle-atom)

(defn- last-mesh []
  (last (:meshes @turtle-atom)))

;; Loft is now a macro - these are the impl functions
(defn- implicit-stamp-loft
  ([shape transform-fn]
   (swap! turtle-atom turtle/stamp-loft shape transform-fn))
  ([shape transform-fn steps]
   (swap! turtle-atom turtle/stamp-loft shape transform-fn steps)))

(defn- implicit-finalize-loft []
  (swap! turtle-atom turtle/finalize-loft))

;; ============================================================
;; Shared SCI context
;; ============================================================

(def ^:private base-bindings
  "Bindings available in both explicit and implicit sections."
  {;; Implicit turtle commands (mutate atom)
   'f            implicit-f
   'th           implicit-th
   'tv           implicit-tv
   'tr           implicit-tr
   'pen-impl     implicit-pen      ; Used by pen macro
   'stamp-impl   (fn [shape] (swap! turtle-atom turtle/stamp shape))
   'finalize-sweep-impl (fn [] (swap! turtle-atom turtle/finalize-sweep))
   'finalize-sweep-closed-impl (fn [] (swap! turtle-atom turtle/finalize-sweep-closed))
   'pen-up       implicit-pen-up
   'pen-down     implicit-pen-down
   ;; 3D primitives
   'box          implicit-box
   'sphere       implicit-sphere
   'cyl          implicit-cyl
   'cone         implicit-cone
   ;; Shape constructors (return shape data, use with pen)
   'circle       shape/circle-shape
   'rect         shape/rect-shape
   'polygon      shape/polygon-shape
   'star         shape/star-shape
   ;; Pure turtle functions (for explicit threading)
   'turtle       turtle/make-turtle
   'turtle-f     turtle/f
   'turtle-th    turtle/th
   'turtle-tv    turtle/tv
   'turtle-tr    turtle/tr
   'turtle-pen      turtle/pen
   'turtle-pen-up   turtle/pen-up
   'turtle-pen-down turtle/pen-down
   'turtle-box      prims/box
   'turtle-sphere   prims/sphere
   'turtle-cyl      prims/cyl
   'turtle-cone     prims/cone
   ;; Path/shape utilities
   'path->data   path/path-from-state
   'make-shape   shape/make-shape
   ;; Generative operations (legacy ops namespace)
   'ops-extrude  ops/extrude
   'extrude-z    ops/extrude-z
   'extrude-y    ops/extrude-y
   'revolve      ops/revolve
   'sweep        ops/sweep
   'ops-loft     ops/loft
   ;; Loft impl functions (used by loft macro)
   'stamp-loft-impl     implicit-stamp-loft
   'finalize-loft-impl  implicit-finalize-loft
   ;; Shape transformation functions
   'scale        xform/scale
   'rotate-shape xform/rotate
   'translate    xform/translate
   'morph        xform/morph
   'resample     xform/resample
   ;; Face operations
   'list-faces   faces/list-faces
   'get-face     faces/get-face
   'face-ids     faces/face-ids
   ;; Access current turtle state
   'get-turtle   get-turtle
   'last-mesh    last-mesh
   ;; Path recording functions
   'make-recorder       turtle/make-recorder
   'rec-f               turtle/rec-f
   'rec-th              turtle/rec-th
   'rec-tv              turtle/rec-tv
   'rec-tr              turtle/rec-tr
   'path-from-recorder  turtle/path-from-recorder
   'run-path-impl       turtle/run-path
   'path?               turtle/path?
   ;; Manifold operations
   'manifold?           manifold/manifold?
   'mesh-status         manifold/get-mesh-status
   'mesh-union          manifold/union
   'mesh-difference     manifold/difference
   'mesh-intersection   manifold/intersection
   ;; Scene registry
   'register-mesh!      registry/register-mesh!
   'remove-definition-mesh! registry/remove-definition-mesh!
   'show-mesh!          registry/show-mesh!
   'hide-mesh!          registry/hide-mesh!
   'show-all!           registry/show-all!
   'hide-all!           registry/hide-all!
   'visible-names       registry/visible-names
   'visible-meshes      registry/visible-meshes
   'registered-names    registry/registered-names
   'get-mesh            registry/get-mesh
   'refresh-viewport!   registry/refresh-viewport!
   ;; STL export
   'save-stl            stl/download-stl})

;; Macro definitions for SCI context
(def ^:private macro-defs
  ";; Atom to hold recorder during path recording
   (def ^:private path-recorder (atom nil))

   ;; Recording versions that work with the path-recorder atom
   (defn- rec-f* [dist]
     (swap! path-recorder rec-f dist))
   (defn- rec-th* [angle]
     (swap! path-recorder rec-th angle))
   (defn- rec-tv* [angle]
     (swap! path-recorder rec-tv angle))
   (defn- rec-tr* [angle]
     (swap! path-recorder rec-tr angle))

   ;; path: record turtle movements for later replay
   ;; (def p (path (f 20) (th 90) (f 20))) - record a path
   ;; (def p (path (dotimes [_ 4] (f 20) (th 90)))) - with arbitrary code
   ;; Returns a path object that can be used in extrude/loft
   (defmacro path [& body]
     `(do
        (reset! path-recorder (make-recorder))
        (let [~'f rec-f*
              ~'th rec-th*
              ~'tv rec-tv*
              ~'tr rec-tr*]
          ~@body)
        (path-from-recorder @path-recorder)))

   ;; pen is now only for mode changes: (pen :off), (pen :on)
   ;; No longer handles shapes - use extrude for that
   (defmacro pen [mode]
     `(pen-impl ~mode))

   ;; run-path: execute a path's movements on the implicit turtle
   ;; Used internally by extrude/loft when given a path
   (defn run-path [p]
     (doseq [{:keys [cmd args]} (:commands p)]
       (case cmd
         :f  (f (first args))
         :th (th (first args))
         :tv (tv (first args))
         :tr (tr (first args))
         nil)))

   ;; extrude: stamp a shape and extrude it via movements or path
   ;; (extrude (circle 15) (f 30)) - stamp circle, extrude 30 units forward
   ;; (extrude (circle 15) my-path) - extrude along a recorded path
   ;; (extrude (rect 20 10) (f 20) (th 45) (f 20)) - sweep with turns
   ;; Builds a unified mesh from all movements, restores previous pen mode
   ;; Returns the created mesh (can be bound with def)
   (defmacro extrude [shape & movements]
     (if (and (= 1 (count movements)) (symbol? (first movements)))
       ;; Single symbol - might be a path, check at runtime
       `(let [prev-mode# (:pen-mode (get-turtle))
              arg# ~(first movements)]
          (stamp-impl ~shape)
          (if (path? arg#)
            (run-path arg#)
            ~(first movements))
          (finalize-sweep-impl)
          (pen-impl prev-mode#)
          (last-mesh))
       ;; Multiple movements or literals - execute directly
       `(let [prev-mode# (:pen-mode (get-turtle))]
          (stamp-impl ~shape)
          ~@movements
          (finalize-sweep-impl)
          (pen-impl prev-mode#)
          (last-mesh))))

   ;; extrude-closed: like extrude but creates a closed torus-like mesh
   ;; (extrude-closed (circle 5) square-path) - closed torus along path
   ;; The path should return to the starting point for proper closure
   ;; Last ring connects to first ring, no end caps
   ;; Returns the created mesh (can be bound with def)
   (defmacro extrude-closed [shape & movements]
     (if (and (= 1 (count movements)) (symbol? (first movements)))
       ;; Single symbol - might be a path, check at runtime
       `(let [prev-mode# (:pen-mode (get-turtle))
              arg# ~(first movements)]
          (stamp-impl ~shape)
          (if (path? arg#)
            (run-path arg#)
            ~(first movements))
          (finalize-sweep-closed-impl)
          (pen-impl prev-mode#)
          (last-mesh))
       ;; Multiple movements or literals - execute directly
       `(let [prev-mode# (:pen-mode (get-turtle))]
          (stamp-impl ~shape)
          ~@movements
          (finalize-sweep-closed-impl)
          (pen-impl prev-mode#)
          (last-mesh))))

   ;; loft: like extrude but with shape transformation based on progress
   ;; (loft (circle 20) #(scale %1 (- 1 %2)) (f 30)) - cone
   ;; (loft (circle 20) #(scale %1 (- 1 %2)) my-path) - cone along path
   ;; (loft (rect 20 10) #(rotate-shape %1 (* %2 90)) (f 30)) - twist
   ;; Transform fn receives (shape t) where t goes from 0 to 1
   ;; Default: 16 steps
   ;; Returns the created mesh (can be bound with def)
   (defmacro loft [shape transform-fn & movements]
     (if (and (= 1 (count movements)) (symbol? (first movements)))
       ;; Single symbol - might be a path
       `(let [prev-mode# (:pen-mode (get-turtle))
              arg# ~(first movements)]
          (stamp-loft-impl ~shape ~transform-fn)
          (if (path? arg#)
            (run-path arg#)
            ~(first movements))
          (finalize-loft-impl)
          (pen-impl prev-mode#)
          (last-mesh))
       ;; Multiple movements or literals
       `(let [prev-mode# (:pen-mode (get-turtle))]
          (stamp-loft-impl ~shape ~transform-fn)
          ~@movements
          (finalize-loft-impl)
          (pen-impl prev-mode#)
          (last-mesh))))

   ;; loft-n: loft with custom step count
   ;; (loft-n 32 (circle 20) #(scale %1 (- 1 %2)) (f 30)) - smoother cone
   ;; Returns the created mesh (can be bound with def)
   (defmacro loft-n [steps shape transform-fn & movements]
     (if (and (= 1 (count movements)) (symbol? (first movements)))
       `(let [prev-mode# (:pen-mode (get-turtle))
              arg# ~(first movements)]
          (stamp-loft-impl ~shape ~transform-fn ~steps)
          (if (path? arg#)
            (run-path arg#)
            ~(first movements))
          (finalize-loft-impl)
          (pen-impl prev-mode#)
          (last-mesh))
       `(let [prev-mode# (:pen-mode (get-turtle))]
          (stamp-loft-impl ~shape ~transform-fn ~steps)
          ~@movements
          (finalize-loft-impl)
          (pen-impl prev-mode#)
          (last-mesh))))

   ;; register: define a symbol, add to registry, AND show it (only first time)
   ;; (register torus (extrude-closed (circle 5) square-path))
   ;; This creates a var 'torus', registers it, and makes it visible
   ;; On subsequent evals, updates the mesh but preserves visibility state
   (defmacro register [name expr]
     `(let [mesh# ~expr
            name-kw# ~(keyword name)
            already-registered# (contains? (set (registered-names)) name-kw#)]
        (def ~name mesh#)
        ;; Remove from definition-meshes so it's only managed by registry
        (remove-definition-mesh! mesh#)
        (register-mesh! name-kw# mesh#)
        ;; Only auto-show on first registration
        (when-not already-registered#
          (show-mesh! name-kw#))
        mesh#))

   ;; Convenience functions that work with names
   (defn show [name]
     (show-mesh! (if (keyword? name) name (keyword name)))
     (refresh-viewport!))

   (defn hide [name]
     (let [kw (if (keyword? name) name (keyword name))]
       (hide-mesh! kw)
       (refresh-viewport!)
       nil))

   (defn show-all []
     (show-all!)
     (refresh-viewport!))

   (defn hide-all []
     (hide-all!)
     (refresh-viewport!))

   ;; List visible object names
   (defn objects []
     (visible-names))

   ;; List all registered object names (visible and hidden)
   (defn registered []
     (registered-names))

   ;; Get info/details about a registered object
   ;; (info :torus) - show vertex/face count and visibility
   (defn info [name]
     (let [kw (if (keyword? name) name (keyword name))
           mesh (get-mesh kw)
           vis (contains? (set (visible-names)) kw)]
       (when mesh
         {:name kw
          :visible vis
          :vertices (count (:vertices mesh))
          :faces (count (:faces mesh))})))

   ;; Get the raw mesh data for an object
   ;; (deref :torus) or @:torus won't work, use (mesh :torus)
   (defn mesh [name]
     (get-mesh (if (keyword? name) name (keyword name))))

   ;; Export mesh(es) to STL file
   ;; (export :torus) - export as torus.stl
   ;; (export :torus :cube) - export as torus-cube.stl
   ;; (export (objects)) - export all visible objects
   (defn export
     ([name-or-names]
      (let [names (if (keyword? name-or-names)
                    [name-or-names]
                    (if (and (sequential? name-or-names) (keyword? (first name-or-names)))
                      name-or-names
                      nil))
            meshes (if names
                     (keep get-mesh names)
                     [name-or-names])
            filename (if names
                       (str (clojure.string/join \"-\" (map name names)) \".stl\")
                       \"export.stl\")]
        (when (seq meshes)
          (save-stl (vec meshes) filename))))
     ([name & more-names]
      (let [all-names (cons name more-names)
            meshes (keep get-mesh all-names)
            filename (str (clojure.string/join \"-\" (map name all-names)) \".stl\")]
        (when (seq meshes)
          (save-stl (vec meshes) filename)))))")

;; Persistent SCI context - created once, reused for REPL commands
(defonce ^:private sci-ctx (atom nil))

(defn- make-sci-ctx []
  (let [ctx (sci/init {:bindings base-bindings})]
    (sci/eval-string macro-defs ctx)
    ctx))

(defn- get-or-create-ctx []
  (if-let [ctx @sci-ctx]
    ctx
    (let [ctx (make-sci-ctx)]
      (reset! sci-ctx ctx)
      ctx)))

(defn reset-ctx!
  "Reset the SCI context. Called when definitions are re-evaluated."
  []
  (reset! sci-ctx (make-sci-ctx)))

;; ============================================================
;; Evaluation
;; ============================================================

(defn evaluate-definitions
  "Evaluate definitions code only. Resets context and turtle state.
   Called when user runs definitions panel (Cmd+Enter or Run button).
   Returns {:result turtle-state :explicit-result any} or {:error msg}."
  [explicit-code]
  (try
    ;; Reset context for fresh definitions evaluation
    (reset-ctx!)
    (let [ctx (get-or-create-ctx)]
      ;; Reset turtle for fresh evaluation
      (reset-turtle!)
      ;; Evaluate explicit code (definitions, functions, explicit geometry)
      (let [explicit-result (when (and explicit-code (seq (str/trim explicit-code)))
                              (sci/eval-string explicit-code ctx))]
        {:result @turtle-atom
         :explicit-result explicit-result
         :implicit-result nil}))
    (catch :default e
      {:error (.-message e)})))

(defn evaluate-repl
  "Evaluate REPL input only, using existing context.
   Definitions must be evaluated first to populate the context.
   Returns {:result turtle-state :implicit-result any} or {:error msg}."
  [repl-code]
  (try
    (let [ctx (get-or-create-ctx)]
      ;; Reset turtle for fresh geometry (but keep definitions in context)
      (reset-turtle!)
      ;; Evaluate REPL code using existing context with definitions
      (let [implicit-result (when (and repl-code (seq (str/trim repl-code)))
                              (sci/eval-string repl-code ctx))]
        {:result @turtle-atom
         :explicit-result nil
         :implicit-result implicit-result}))
    (catch :default e
      {:error (.-message e)})))

(defn evaluate
  "Evaluate both explicit and implicit code sections (legacy API).
   Returns {:result turtle-state :explicit-result any :implicit-result any} or {:error msg}."
  [explicit-code implicit-code]
  (try
    ;; Reset context for fresh evaluation
    (reset-ctx!)
    (let [ctx (get-or-create-ctx)]
      ;; Reset turtle for fresh evaluation (but NOT registry - that persists)
      (reset-turtle!)
      ;; Phase 1: Evaluate explicit code (definitions, functions, explicit geometry)
      (let [explicit-result (when (and explicit-code (seq (str/trim explicit-code)))
                              (sci/eval-string explicit-code ctx))
            ;; Phase 2: Evaluate implicit code (turtle commands)
            implicit-result (when (and implicit-code (seq (str/trim implicit-code)))
                              (sci/eval-string implicit-code ctx))]
        ;; Return combined result
        {:result @turtle-atom
         :explicit-result explicit-result
         :implicit-result implicit-result}))
    (catch :default e
      {:error (.-message e)})))

(defn extract-render-data
  "Extract render data from evaluation result.
   Combines geometry from turtle state and explicit results.
   Does NOT include registry meshes - those are handled separately by refresh-viewport!."
  [eval-result]
  (let [turtle-state (:result eval-result)
        explicit-result (:explicit-result eval-result)
        turtle-lines (or (:geometry turtle-state) [])
        turtle-meshes (or (:meshes turtle-state) [])
        ;; Check if explicit result has geometry
        explicit-data (cond
                        ;; Direct mesh result (from extrude, revolve, etc.)
                        (and (:vertices explicit-result) (:faces explicit-result))
                        {:lines [] :meshes [explicit-result]}
                        ;; Path or shape result
                        (:segments explicit-result)
                        {:lines (:segments explicit-result) :meshes []}
                        ;; Turtle state from explicit
                        (or (:geometry explicit-result) (:meshes explicit-result))
                        {:lines (or (:geometry explicit-result) [])
                         :meshes (or (:meshes explicit-result) [])}
                        :else nil)
        ;; Combine turtle + explicit geometry (NOT registry - that's handled by refresh-viewport!)
        all-lines (concat turtle-lines (or (:lines explicit-data) []))
        all-meshes (concat turtle-meshes
                           (or (:meshes explicit-data) []))]
    (when (or (seq all-lines) (seq all-meshes))
      {:lines (vec all-lines)
       :meshes (vec all-meshes)})))
