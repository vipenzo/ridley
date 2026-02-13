(ns ridley.editor.bindings
  "SCI context bindings map — wires SCI symbols to ClojureScript functions."
  (:require [ridley.editor.state :as state]
            [ridley.editor.implicit :as impl]
            [ridley.editor.operations :as gen-ops]
            [ridley.editor.text-ops :as text-ops]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.path :as path]
            [ridley.turtle.transform :as xform]
            [ridley.turtle.text :as text]
            [ridley.geometry.primitives :as prims]
            [ridley.geometry.operations :as ops]
            [ridley.geometry.faces :as faces]
            [ridley.manifold.core :as manifold]
            [ridley.clipper.core :as clipper]
            [ridley.scene.registry :as registry]
            [ridley.scene.panel :as panel]
            [ridley.viewport.core :as viewport]
            [ridley.export.stl :as stl]
            [ridley.anim.core :as anim]
            [ridley.anim.easing :as easing]
            [ridley.anim.preprocess :as anim-preprocess]
            [ridley.turtle.shape-fn :as sfn]))

(def base-bindings
  "Bindings available in both explicit and implicit sections."
  {;; Implicit turtle commands (mutate atom)
   'f            impl/implicit-f
   'th           impl/implicit-th
   'tv           impl/implicit-tv
   'tr           impl/implicit-tr
   'pen-impl     impl/implicit-pen      ; Used by pen macro
   'stamp-impl   (fn [shape] (swap! state/turtle-atom turtle/stamp shape))
   'stamp-closed-impl (fn [shape] (swap! state/turtle-atom turtle/stamp-closed shape))
   'finalize-sweep-impl (fn [] (swap! state/turtle-atom turtle/finalize-sweep))
   'finalize-sweep-closed-impl (fn [] (swap! state/turtle-atom turtle/finalize-sweep-closed))
   'pen-up       impl/implicit-pen-up
   'pen-down     impl/implicit-pen-down
   'reset        impl/implicit-reset
   'joint-mode   impl/implicit-joint-mode
   ;; Resolution (like OpenSCAD $fn/$fa/$fs)
   'resolution   impl/implicit-resolution
   ;; Color and material
   'color        impl/implicit-color
   'material     impl/implicit-material
   'reset-material impl/implicit-reset-material
   ;; Creation pose override
   'set-creation-pose (fn [mesh] (turtle/set-creation-pose @state/turtle-atom mesh))
   ;; Lateral movement (pure translation, no heading change)
   'u            impl/implicit-u
   'd            impl/implicit-d
   'rt           impl/implicit-rt
   'lt           impl/implicit-lt
   ;; Pure lateral functions (for explicit threading / attach macros)
   'turtle-u     turtle/move-up
   'turtle-d     turtle/move-down
   'turtle-rt    turtle/move-right
   'turtle-lt    turtle/move-left
   ;; Arc commands
   'arc-h        impl/implicit-arc-h
   'arc-v        impl/implicit-arc-v
   ;; Bezier commands
   'bezier-to         impl/implicit-bezier-to
   'bezier-to-anchor  impl/implicit-bezier-to-anchor
   'bezier-as         impl/implicit-bezier-as
   ;; State stack
   'push-state   impl/implicit-push-state
   'pop-state    impl/implicit-pop-state
   'clear-stack  impl/implicit-clear-stack
   ;; Anchors and navigation (mark removed — use inside path macro only)
   'goto         impl/implicit-goto
   'get-anchor   impl/get-anchor
   'look-at      impl/implicit-look-at
   'path-to      impl/implicit-path-to
   ;; with-path helpers (used by with-path macro)
   'save-anchors*           impl/implicit-save-anchors
   'restore-anchors*        impl/implicit-restore-anchors
   'resolve-and-merge-marks* impl/implicit-resolve-and-merge-marks
   ;; Attachment commands (functional versions defined in macro-defs)
   ;; NOTE: Legacy implicit-attach and implicit-attach-face removed
   ;; Use the functional macro: (attach-face mesh :top (f 20))
   'attached?    (fn [] (turtle/attached? @state/turtle-atom))
   ;; Turtle state inspection (for debugging)
   'turtle-position (fn [] (:position @state/turtle-atom))
   'turtle-heading  (fn [] (:heading @state/turtle-atom))
   'turtle-up       (fn [] (:up @state/turtle-atom))
   ;; NOTE: 'detach' removed - implicit at end of attach/attach-face macro
   'inset        impl/implicit-inset
   ;; 3D primitives - return mesh data at origin (resolution-aware)
   'box          impl/pure-box
   'sphere       impl/sphere-with-resolution
   'cyl          impl/cyl-with-resolution
   'cone         impl/cone-with-resolution
   ;; Debug shape visualization at current turtle pose
   'stamp        impl/implicit-stamp-debug
   ;; Shape constructors (return shape data, resolution-aware)
   'circle       impl/circle-with-resolution
   'rect         shape/rect-shape
   'polygon      shape/ngon-shape
   'poly         shape/poly-shape
   'star         shape/star-shape
   ;; Shape transformations
   'translate-shape shape/translate-shape
   'scale-shape     shape/scale-shape
   'reverse-shape   shape/reverse-shape
   'path-to-shape   shape/path-to-shape
   'stroke-shape    shape/stroke-shape
   ;; Shape boolean operations (2D, via Clipper2)
   'shape-union        clipper/shape-union
   'shape-difference   clipper/shape-difference
   'shape-intersection clipper/shape-intersection
   'shape-xor          clipper/shape-xor
   'shape-offset       clipper/shape-offset
   ;; Text shapes
   'text-shape   text/text-shape
   'text-shapes  text/text-shapes
   'char-shape   text/char-shape
   'load-font!   text/load-font!
   'font-loaded? text/font-loaded?
   'extrude-text text-ops/implicit-extrude-text
   'text-on-path text-ops/implicit-text-on-path
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
   ;; Pure attach functions for functional macros
   ;; (turtle-f, turtle-th, turtle-tv, turtle-tr already defined above)
   'turtle-attach       turtle/attach
   'turtle-attach-face  turtle/attach-face
   'turtle-attach-face-extrude turtle/attach-face-extrude
   'turtle-attach-move  turtle/attach-move
   'turtle-inset        turtle/inset
   'turtle-scale        turtle/scale
   ;; NOTE: attach-state and att-* functions are defined in macro-defs
   ;; Path/shape utilities
   'path->data   path/path-from-state
   'make-shape   shape/make-shape
   'shape?       shape/shape?
   ;; Generative operations (legacy ops namespace)
   'ops-extrude  ops/extrude
   'extrude-z    ops/extrude-z
   'extrude-y    ops/extrude-y
   'revolve      ops/revolve
   ;; ops-sweep removed (sweep is redundant with loft)
   'ops-loft     ops/loft
   ;; Loft impl functions (used by loft macro)
   'stamp-loft-impl     impl/implicit-stamp-loft
   'finalize-loft-impl  impl/implicit-finalize-loft
   ;; Shape transformation functions (scale also works on attached mesh)
   'scale        impl/unified-scale
   'rotate-shape xform/rotate
   'translate    xform/translate
   'morph        xform/morph
   'resample     xform/resample
   ;; Shape-fn system (shapes that vary along the extrusion path)
   'shape-fn         sfn/shape-fn
   'shape-fn?        sfn/shape-fn?
   'tapered          sfn/tapered
   'twisted          sfn/twisted
   'rugged           sfn/rugged
   'fluted           sfn/fluted
   'displaced        sfn/displaced
   'morphed          sfn/morphed
   'angle            sfn/angle
   'displace-radial  sfn/displace-radial
   ;; Procedural noise and displacement
   'noise            sfn/noise
   'fbm              sfn/fbm
   'noisy            sfn/noisy
   'woven            sfn/woven
   'weave-heightmap   sfn/weave-heightmap
   'mesh-bounds       sfn/mesh-bounds
   'mesh-to-heightmap sfn/mesh-to-heightmap
   'sample-heightmap  sfn/sample-heightmap
   'heightmap         sfn/heightmap
   'heightmap-to-mesh sfn/heightmap-to-mesh
   'pure-loft-shape-fn   gen-ops/pure-loft-shape-fn
   'pure-bloft-shape-fn  gen-ops/pure-bloft-shape-fn
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
   'get-turtle   state/get-turtle
   'get-turtle-resolution state/get-turtle-resolution
   'get-turtle-joint-mode state/get-turtle-joint-mode
   'last-mesh    state/last-mesh
   ;; Path recording functions
   'make-recorder       turtle/make-recorder
   'rec-f               turtle/rec-f
   'rec-th              turtle/rec-th
   'rec-tv              turtle/rec-tv
   'rec-tr              turtle/rec-tr
   'rec-set-heading     turtle/rec-set-heading
   'path-from-recorder  turtle/path-from-recorder
   ;; Shape recording functions (2D turtle)
   'shape-rec-f         shape/rec-f
   'shape-rec-th        shape/rec-th
   'shape-from-recording shape/shape-from-recording
   'recording-turtle    shape/recording-turtle
   'replay-path-to-recording shape/replay-path-to-recording
   'run-path-impl       turtle/run-path
   'follow-path         impl/implicit-run-path
   'path?               turtle/path?
   'quick-path          turtle/quick-path
   'path-segments-impl  turtle/path-segments
   'subdivide-segment-impl turtle/subdivide-segment
   'compute-bezier-walk-impl turtle/compute-bezier-walk
   'extrude-closed-path-impl gen-ops/implicit-extrude-closed-path
   'extrude-path-impl        gen-ops/implicit-extrude-path
   'pure-extrude-path        gen-ops/pure-extrude-path  ; Pure version (no side effects)
   'pure-loft-path           gen-ops/pure-loft-path     ; Pure loft version (no side effects)
   'pure-loft-two-shapes     gen-ops/pure-loft-two-shapes ; Loft between two shapes
   'pure-bloft               gen-ops/pure-bloft         ; Bezier-safe loft (handles self-intersection)
   'pure-revolve             gen-ops/pure-revolve       ; Pure revolve/lathe version
   'pure-revolve-shape-fn    gen-ops/pure-revolve-shape-fn ; Revolve with shape-fn
   'add-mesh-impl       impl/implicit-add-mesh
   ;; Manifold operations
   'manifold?           manifold/manifold?
   'mesh-status         manifold/get-mesh-status
   'mesh-union          manifold/union
   'mesh-difference     manifold/difference
   'mesh-intersection   manifold/intersection
   'mesh-hull           manifold/hull
   'concat-meshes       manifold/concat-meshes
   'solidify            manifold/solidify
   ;; Scene registry
   'add-mesh!           registry/add-mesh!
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
   '$                   registry/get-value
   'register-value!     registry/register-value!
   'refresh-viewport!   registry/refresh-viewport!
   'all-meshes-info     registry/all-meshes-info
   'anonymous-meshes    registry/anonymous-meshes
   'anonymous-count     registry/anonymous-count
   ;; Viewport visibility controls
   'show-lines          (fn [] (viewport/set-lines-visible true))
   'hide-lines          (fn [] (viewport/set-lines-visible false))
   'lines-visible?      viewport/lines-visible?
   'show-stamps         (fn [] (viewport/set-stamps-visible true))
   'hide-stamps         (fn [] (viewport/set-stamps-visible false))
   'stamps-visible?     viewport/stamps-visible?
   ;; Path registry (abstract, no visibility)
   'register-path!      registry/register-path!
   'get-path            registry/get-path
   'path-names          registry/path-names
   ;; Shape registry
   'register-shape!     registry/register-shape!
   'get-shape           registry/get-shape
   'shape-names         registry/shape-names
   ;; STL export
   'save-stl            stl/download-stl
   ;; Vector math (used by move-to and available for user code)
   'vec3+               turtle/v+
   'vec3-               turtle/v-
   'vec3*               turtle/v*
   'vec3-dot            turtle/dot
   'vec3-cross          turtle/cross
   'vec3-normalize      turtle/normalize
   ;; Math functions for SCI context (used by arc/bezier recording)
   'PI                  js/Math.PI
   'abs                 js/Math.abs
   'sin                 js/Math.sin
   'cos                 js/Math.cos
   'sqrt                js/Math.sqrt
   'ceil                js/Math.ceil
   'floor               js/Math.floor
   'round               js/Math.round
   'pow                 js/Math.pow
   'atan2               js/Math.atan2
   'acos                js/Math.acos
   ;; Debug logging (outputs to browser console)
   'log                 (fn [& args] (apply js/console.log (map clj->js args)))
   ;; Print functions (captured and shown in REPL)
   'print               state/capture-print
   'println             state/capture-println
   'pr                  (fn [& args] (state/capture-print (apply pr-str args)))
   'prn                 (fn [& args] (state/capture-println (apply pr-str args)))
   ;; Debug tap function - prints label and value, returns value
   'T                   (fn [label x] (state/capture-println (str label ": " x)) x)
   ;; Panel (3D text billboard) functions
   'panel               impl/implicit-panel
   'panel?              panel/panel?
   'out                 impl/implicit-out
   'append              impl/implicit-append
   'clear               impl/implicit-clear
   ;; Panel registry
   'register-panel!     registry/register-panel!
   'get-panel           registry/get-panel
   'show-panel!         registry/show-panel!
   'hide-panel!         registry/hide-panel!
   ;; Animation system
   'get-camera-pose     viewport/get-camera-pose
   'get-orbit-target    viewport/get-orbit-target
   'play!               anim/play!
   'pause!              anim/pause!
   'stop!               anim/stop!
   'stop-all!           anim/stop-all!
   'seek!               anim/seek!
   'anim-list           anim/list-animations
   'ease                easing/ease
   ;; Animation linking (parent-child position tracking)
   'link!               anim/link!
   'unlink!             anim/unlink!
   ;; Mesh anchors
   'attach-path         impl/implicit-attach-path
   ;; Animation internals (used by anim! / span / anim-proc! macros)
   'anim-register!      anim/register-animation!
   'anim-proc-register! anim/register-procedural-animation!
   'anim-preprocess     anim-preprocess/preprocess-animation
   'anim-make-span      anim/make-span
   'anim-make-cmd       anim/make-anim-command
   'anim-clear-all!     anim/clear-all!
   ;; Collision detection (centroid-distance based)
   'on-collide          anim/register-collision!
   'off-collide         anim/unregister-collision!
   'reset-collide       anim/reset-collision!
   'clear-collisions    anim/clear-collisions!
   'list-collisions     anim/list-collisions})
