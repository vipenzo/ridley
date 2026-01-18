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
            [ridley.viewport.core :as viewport]
            [ridley.export.stl :as stl]))

;; Global turtle state for implicit mode
(defonce ^:private turtle-atom (atom nil))

(defn- reset-turtle! []
  (reset! turtle-atom (turtle/make-turtle)))

;; ============================================================
;; Implicit turtle functions (mutate atom)
;; ============================================================

(defn- implicit-f [dist]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/f dist)
    ;; If attached with registry index, update the registry directly
    (when registry-idx
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn- implicit-th [angle]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/th angle)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn- implicit-tv [angle]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/tv angle)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn- implicit-tr [angle]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/tr angle)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn- implicit-pen-up []
  (swap! turtle-atom turtle/pen-up))

(defn- implicit-pen-down []
  (swap! turtle-atom turtle/pen-down))

(defn- implicit-pen [mode]
  (swap! turtle-atom turtle/pen mode))

(defn- implicit-reset
  ([] (swap! turtle-atom turtle/reset-pose))
  ([pos] (swap! turtle-atom turtle/reset-pose pos))
  ([pos & opts] (swap! turtle-atom #(apply turtle/reset-pose % pos opts))))

(defn- implicit-joint-mode [mode]
  (swap! turtle-atom turtle/joint-mode mode))

;; State stack
(defn- implicit-push-state []
  (swap! turtle-atom turtle/push-state))

(defn- implicit-pop-state []
  (swap! turtle-atom turtle/pop-state))

(defn- implicit-clear-stack []
  (swap! turtle-atom turtle/clear-stack))

;; Anchors and navigation
(defn- implicit-mark [name]
  (swap! turtle-atom turtle/mark name))

(defn- implicit-goto [name]
  (swap! turtle-atom turtle/goto name))

(defn- implicit-look-at [name]
  (swap! turtle-atom turtle/look-at name))

(defn- implicit-path-to [name]
  ;; First orient turtle toward anchor, then create path
  ;; This ensures extrusions go in the correct direction
  (swap! turtle-atom turtle/look-at name)
  (turtle/path-to @turtle-atom name))

;; Attachment commands
;; Store registry index in :attached so we can update the real mesh
(defn- implicit-attach [mesh]
  (let [idx (registry/get-mesh-index mesh)
        ;; Get the current mesh from registry (may have been modified since def)
        current-mesh (if idx (registry/get-mesh-at-index idx) mesh)]
    (swap! turtle-atom (fn [state]
                         (let [state' (turtle/attach state current-mesh)]
                           (if idx
                             (assoc-in state' [:attached :registry-index] idx)
                             state'))))))

(defn- implicit-attach-face [mesh face-id]
  (let [idx (registry/get-mesh-index mesh)
        ;; Get the current mesh from registry (may have been modified since def)
        current-mesh (if idx (registry/get-mesh-at-index idx) mesh)]
    (swap! turtle-atom (fn [state]
                         (let [state' (turtle/attach-face state current-mesh face-id)]
                           (if idx
                             (assoc-in state' [:attached :registry-index] idx)
                             state'))))))

(defn- implicit-detach []
  (swap! turtle-atom turtle/detach))

(defn- implicit-inset [dist]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/inset dist)
    ;; If attached with registry index, update the registry directly
    (when registry-idx
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn- implicit-scale-mesh [factor]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/scale factor)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn- unified-scale
  "Unified scale function:
   - If first arg is a shape, scales the shape (2D)
   - If no args and attached to mesh, scales the attached mesh"
  ([factor]
   ;; No shape provided - try to scale attached mesh
   (if (= :pose (get-in @turtle-atom [:attached :type]))
     (implicit-scale-mesh factor)
     (throw (js/Error. "scale requires a shape argument, or attach to a mesh first"))))
  ([shape factor]
   ;; Shape provided - scale the 2D shape
   (xform/scale shape factor))
  ([shape fx fy]
   ;; Non-uniform scale of 2D shape
   (xform/scale shape fx fy)))

;; Pure primitive constructors - return mesh data at origin (no side effects)
(defn- pure-box
  ([size] (prims/box-mesh size))
  ([sx sy sz] (prims/box-mesh sx sy sz)))

(defn- pure-sphere
  ([radius] (prims/sphere-mesh radius))
  ([radius segments rings] (prims/sphere-mesh radius segments rings)))

(defn- pure-cyl
  ([radius height] (prims/cyl-mesh radius height))
  ([radius height segments] (prims/cyl-mesh radius height segments)))

(defn- pure-cone
  ([r1 r2 height] (prims/cone-mesh r1 r2 height))
  ([r1 r2 height segments] (prims/cone-mesh r1 r2 height segments)))

;; Transform a mesh to turtle position/orientation
(defn- transform-mesh-to-turtle
  "Transform a mesh's vertices to current turtle position and orientation.
   Also stores creation-pose so mesh can be re-attached later."
  [mesh]
  (let [turtle @turtle-atom
        position (:position turtle)
        heading (:heading turtle)
        up (:up turtle)
        transformed-verts (prims/apply-transform
                           (:vertices mesh)
                           position
                           heading
                           up)]
    (-> mesh
        (assoc :vertices (vec transformed-verts))
        (assoc :creation-pose {:position position
                               :heading heading
                               :up up}))))

;; stamp: materialize mesh at turtle position, add to scene as visible
;; Only works with primitives (box, sphere, cyl, cone) - not extrude results
(defn- implicit-stamp
  "Materialize a mesh at current turtle position and show it.
   Returns the transformed mesh with :registry-id.
   Only works with primitives - throws error for extrude results."
  [mesh]
  (if (:primitive mesh)
    (let [transformed (transform-mesh-to-turtle mesh)
          mesh-with-id (registry/add-mesh! transformed nil true)]
      (registry/refresh-viewport! false)  ; Don't reset camera
      mesh-with-id)
    (throw (js/Error. (str "stamp only works with primitives (box, sphere, cyl, cone). "
                           "For extrude results, use 'register' directly.")))))

;; make: materialize mesh at turtle position, return without showing
;; Only works with primitives (box, sphere, cyl, cone) - not extrude results
(defn- implicit-make
  "Materialize a mesh at current turtle position without showing.
   Returns the transformed mesh (for use in boolean operations).
   Only works with primitives - throws error for extrude results."
  [mesh]
  (if (:primitive mesh)
    (transform-mesh-to-turtle mesh)
    (throw (js/Error. (str "make only works with primitives (box, sphere, cyl, cone). "
                           "For extrude results, position the turtle first, then create the mesh.")))))

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

(defn- implicit-add-mesh [mesh]
  (swap! turtle-atom update :meshes conj mesh)
  mesh)

(defn- implicit-extrude-closed-path [shape path]
  (swap! turtle-atom turtle/extrude-closed-from-path shape path)
  (last (:meshes @turtle-atom)))

(defn- implicit-extrude-path [shape path]
  (swap! turtle-atom turtle/extrude-from-path shape path)
  (last (:meshes @turtle-atom)))

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
   'stamp-closed-impl (fn [shape] (swap! turtle-atom turtle/stamp-closed shape))
   'finalize-sweep-impl (fn [] (swap! turtle-atom turtle/finalize-sweep))
   'finalize-sweep-closed-impl (fn [] (swap! turtle-atom turtle/finalize-sweep-closed))
   'pen-up       implicit-pen-up
   'pen-down     implicit-pen-down
   'reset        implicit-reset
   'joint-mode   implicit-joint-mode
   ;; State stack
   'push-state   implicit-push-state
   'pop-state    implicit-pop-state
   'clear-stack  implicit-clear-stack
   ;; Anchors and navigation
   'mark         implicit-mark
   'goto         implicit-goto
   'look-at      implicit-look-at
   'path-to      implicit-path-to
   ;; Attachment commands
   'attach       implicit-attach
   'attach-face  implicit-attach-face
   'detach       implicit-detach
   'attached?    (fn [] (turtle/attached? @turtle-atom))
   'inset        implicit-inset
   ;; 3D primitives - return mesh data at origin (no side effects)
   'box          pure-box
   'sphere       pure-sphere
   'cyl          pure-cyl
   'cone         pure-cone
   ;; Materialize mesh at turtle position
   'stamp        implicit-stamp    ; show in viewport
   'make         implicit-make     ; hidden (for boolean ops)
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
   'ops-sweep    ops/sweep
   'ops-loft     ops/loft
   ;; Loft impl functions (used by loft macro)
   'stamp-loft-impl     implicit-stamp-loft
   'finalize-loft-impl  implicit-finalize-loft
   ;; Shape transformation functions (scale also works on attached mesh)
   'scale        unified-scale
   'rotate-shape xform/rotate
   'translate    xform/translate
   'morph        xform/morph
   'resample     xform/resample
   ;; Face operations
   'list-faces   faces/list-faces
   'get-face     faces/get-face
   'face-info    faces/face-info
   'face-ids     faces/face-ids
   ;; Face highlighting
   'flash-face      viewport/flash-face
   'highlight-face  viewport/highlight-face
   'clear-highlights viewport/clear-highlights
   'fit-camera      viewport/fit-camera
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
   'extrude-closed-path-impl implicit-extrude-closed-path
   'extrude-path-impl        implicit-extrude-path
   ;; Sweep between two shapes
   'stamp-shape-at      turtle/stamp-shape-at
   'sweep-two-shapes    turtle/sweep-two-shapes
   'add-mesh-impl       implicit-add-mesh
   ;; Manifold operations
   'manifold?           manifold/manifold?
   'mesh-status         manifold/get-mesh-status
   'mesh-union          manifold/union
   'mesh-difference     manifold/difference
   'mesh-intersection   manifold/intersection
   ;; Scene registry
   'register-mesh!      registry/register-mesh!
   'show-mesh!          registry/show-mesh!
   'hide-mesh!          registry/hide-mesh!
   'show-mesh-ref!      registry/show-mesh-ref!
   'hide-mesh-ref!      registry/hide-mesh-ref!
   'show-all!           registry/show-all!
   'hide-all!           registry/hide-all!
   'show-only-registered! registry/show-only-registered!
   'visible-names       registry/visible-names
   'visible-meshes      registry/visible-meshes
   'registered-names    registry/registered-names
   'get-mesh            registry/get-mesh
   'refresh-viewport!   registry/refresh-viewport!
   'all-meshes-info     registry/all-meshes-info
   'anonymous-meshes    registry/anonymous-meshes
   'anonymous-count     registry/anonymous-count
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
   ;; (extrude (circle 15) (path-to :target)) - extrude along path to anchor
   ;; (extrude (rect 20 10) (f 20) (th 45) (f 20)) - sweep with turns
   ;; Uses two-pass approach: first records movements into a path,
   ;; then processes with correct segment shortening at corners.
   ;; Returns the created mesh (can be bound with def)
   (defmacro extrude [shape & movements]
     (if (= 1 (count movements))
       ;; Single argument - check at runtime if it's a path
       ;; This handles: my-path, (path-to :x), (my-fn :x), etc.
       `(let [arg# ~(first movements)]
          (if (path? arg#)
            ;; It's a path - use directly
            (extrude-path-impl ~shape arg#)
            ;; Not a path - it was a movement that mutated turtle, record it fresh
            (extrude-path-impl ~shape (path ~(first movements)))))
       ;; Multiple movements - wrap in path macro
       `(extrude-path-impl ~shape (path ~@movements))))

   ;; extrude-closed: like extrude but creates a closed torus-like mesh
   ;; (extrude-closed (circle 5) square-path) - closed torus along path
   ;; The path should return to the starting point for proper closure
   ;; Last ring connects to first ring, no end caps
   ;; Returns the created mesh (can be bound with def)
   ;; Uses pre-processed path approach for correct corner geometry
   (defmacro extrude-closed [shape path-expr]
     `(extrude-closed-path-impl ~shape ~path-expr))

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

   ;; sweep: create mesh between two shapes
   ;; (sweep (circle 5) (do (f 10) (th 90) (circle 5)))
   ;; First shape is stamped at current turtle position
   ;; Body executes (can move turtle), last expression must return a shape
   ;; Second shape is stamped at final turtle position
   ;; Returns mesh connecting the two shapes
   (defmacro sweep [shape1 body]
     `(let [;; Stamp first shape at current position
            ring1# (stamp-shape-at (get-turtle) ~shape1)
            ;; Execute body (moves turtle, returns second shape)
            shape2# ~body
            ;; Stamp second shape at new position
            ring2# (stamp-shape-at (get-turtle) shape2#)
            ;; Create mesh between the two rings
            mesh# (sweep-two-shapes ring1# ring2#)]
        ;; Add mesh to turtle state and return it
        (add-mesh-impl mesh#)))

   ;; register: define a symbol, add to registry, AND show it (only first time)
   ;; (register torus (extrude-closed (circle 5) square-path))
   ;; This creates a var 'torus', registers it, and makes it visible
   ;; On subsequent evals, updates the mesh but preserves visibility state
   (defmacro register [name expr]
     `(let [mesh# ~expr
            name-kw# ~(keyword name)
            already-registered# (contains? (set (registered-names)) name-kw#)]
        (def ~name mesh#)
        (register-mesh! name-kw# mesh#)
        ;; Only auto-show on first registration
        (when-not already-registered#
          (show-mesh! name-kw#))
        mesh#))

   ;; Convenience functions that work with names OR mesh references
   ;; (hide :torus) - hide by registered name (keyword)
   ;; (hide torus)  - hide by mesh reference (def'd variable)
   (defn show [name-or-mesh]
     (if (or (keyword? name-or-mesh) (string? name-or-mesh) (symbol? name-or-mesh))
       ;; It's a name - convert to keyword and look up
       (show-mesh! (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh)))
       ;; It's a mesh reference - look up by identity
       (show-mesh-ref! name-or-mesh))
     (refresh-viewport! false))  ; Don't reset camera

   (defn hide [name-or-mesh]
     (if (or (keyword? name-or-mesh) (string? name-or-mesh) (symbol? name-or-mesh))
       ;; It's a name - convert to keyword and look up
       (hide-mesh! (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh)))
       ;; It's a mesh reference - look up by identity
       (hide-mesh-ref! name-or-mesh))
     (refresh-viewport! false)  ; Don't reset camera
     nil)

   (defn show-all []
     (show-all!)
     (refresh-viewport! false))  ; Don't reset camera

   (defn hide-all []
     (hide-all!)
     (refresh-viewport! false))  ; Don't reset camera

   ;; Show only registered objects (hide work-in-progress meshes)
   (defn show-only-objects []
     (show-only-registered!)
     (refresh-viewport! false))  ; Don't reset camera

   ;; List visible object names
   (defn objects []
     (visible-names))

   ;; List all registered object names (visible and hidden)
   (defn registered []
     (registered-names))

   ;; List all meshes in scene (registered + anonymous)
   (defn scene []
     (all-meshes-info))

   ;; Get info/details about a mesh
   ;; (info :torus) - by registered name (keyword)
   ;; (info torus)  - by mesh reference (def'd variable)
   (defn info [name-or-mesh]
     (if (or (keyword? name-or-mesh) (string? name-or-mesh) (symbol? name-or-mesh))
       ;; It's a name - look up by keyword
       (let [kw (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh))
             mesh (get-mesh kw)
             vis (contains? (set (visible-names)) kw)]
         (when mesh
           {:name kw
            :visible vis
            :vertices (count (:vertices mesh))
            :faces (count (:faces mesh))}))
       ;; It's a mesh reference - show info directly
       (when (and name-or-mesh (:vertices name-or-mesh))
         {:name nil
          :vertices (count (:vertices name-or-mesh))
          :faces (count (:faces name-or-mesh))})))

   ;; Get the raw mesh data for an object
   ;; (mesh :torus) - by registered name
   ;; (mesh torus)  - returns mesh itself (identity)
   (defn mesh [name-or-mesh]
     (if (or (keyword? name-or-mesh) (string? name-or-mesh) (symbol? name-or-mesh))
       (get-mesh (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh)))
       name-or-mesh))

   ;; Helper to check if something is a mesh (has :vertices and :faces)
   (defn- mesh? [x]
     (and (map? x) (:vertices x) (:faces x)))

   ;; Helper to resolve name-or-mesh to actual mesh
   (defn- resolve-mesh [name-or-mesh]
     (if (mesh? name-or-mesh)
       name-or-mesh
       (get-mesh (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh)))))

   ;; Export mesh(es) to STL file
   ;; (export :torus) - by registered name
   ;; (export torus)  - by mesh reference
   ;; (export :torus :cube) - multiple by name
   ;; (export torus cube)   - multiple by reference
   ;; (export (objects))    - export all visible objects
   (defn export
     ([name-or-mesh]
      (cond
        ;; Single keyword
        (keyword? name-or-mesh)
        (when-let [m (get-mesh name-or-mesh)]
          (save-stl [m] (str (name name-or-mesh) \".stl\")))
        ;; List of keywords
        (and (sequential? name-or-mesh) (keyword? (first name-or-mesh)))
        (let [meshes (keep get-mesh name-or-mesh)]
          (when (seq meshes)
            (save-stl (vec meshes)
                      (str (clojure.string/join \"-\" (map name name-or-mesh)) \".stl\"))))
        ;; Single mesh
        (mesh? name-or-mesh)
        (save-stl [name-or-mesh] \"export.stl\")
        ;; List of meshes
        (and (sequential? name-or-mesh) (mesh? (first name-or-mesh)))
        (save-stl (vec name-or-mesh) \"export.stl\")))
     ([first-arg & more-args]
      (let [all-args (cons first-arg more-args)
            meshes (keep resolve-mesh all-args)]
        (when (seq meshes)
          (save-stl (vec meshes) \"export.stl\")))))")

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
