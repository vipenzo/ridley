(ns ridley.editor.sci-harness
  "Minimal SCI evaluation harness for testing DSL macro behavior.
   Uses production macro-defs and shared turtle-atom, with lightweight
   implicit commands (no browser/registry/viewport dependencies).

   Usage in tests:
     (let [{:keys [turtle result]} (eval-dsl \"(extrude (circle 5) (f 20))\")]
       (is (= 1 (count (:meshes turtle)))))"
  (:require [sci.core :as sci]
            [ridley.editor.state :as state]
            [ridley.editor.macros :as macros]
            [ridley.editor.operations :as gen-ops]
            [ridley.editor.impl :as macro-impl]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.transform :as xform]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.math :as math]))

;; ── Turtle lifecycle ──────────────────────────────────────

(defn- reset-turtle! []
  (reset! state/turtle-atom (turtle/make-turtle)))

(defn get-turtle []
  @state/turtle-atom)

;; ── Implicit commands (mutate shared turtle-atom) ─────────
;; Simplified versions without registry/viewport side effects.

(defn- implicit-f [dist]
  (swap! state/turtle-atom turtle/f dist))

(defn- implicit-th [angle]
  (swap! state/turtle-atom turtle/th angle))

(defn- implicit-tv [angle]
  (swap! state/turtle-atom turtle/tv angle))

(defn- implicit-tr [angle]
  (swap! state/turtle-atom turtle/tr angle))

(defn- implicit-pen [mode]
  (swap! state/turtle-atom turtle/pen mode))

(defn- implicit-reset
  ([] (reset-turtle!))
  ([pos] (reset! state/turtle-atom (turtle/reset-pose (turtle/make-turtle) pos))))

(defn- implicit-resolution [mode value]
  (swap! state/turtle-atom turtle/resolution mode value))

(defn- implicit-joint-mode [mode]
  (swap! state/turtle-atom turtle/joint-mode mode))

(defn- implicit-arc-h [radius angle & {:keys [steps]}]
  (swap! state/turtle-atom turtle/arc-h radius angle :steps steps))

(defn- implicit-arc-v [radius angle & {:keys [steps]}]
  (swap! state/turtle-atom turtle/arc-v radius angle :steps steps))

(defn- implicit-bezier-to [target & args]
  (swap! state/turtle-atom #(apply turtle/bezier-to % target args)))

(defn- implicit-bezier-as [p & args]
  (swap! state/turtle-atom #(apply turtle/bezier-as % p args)))

(defn- implicit-push-state []
  (swap! state/turtle-atom turtle/push-state))

(defn- implicit-pop-state []
  (swap! state/turtle-atom turtle/pop-state))

(defn- implicit-goto [name]
  (swap! state/turtle-atom turtle/goto name))

(defn- implicit-look-at [name]
  (swap! state/turtle-atom turtle/look-at name))

(defn- implicit-run-path [p]
  (swap! state/turtle-atom turtle/run-path p))

;; ── Resolution-aware circle ───────────────────────────────

(defn- circle-with-resolution [radius & [segments]]
  (let [res (or segments
                (let [{:keys [mode value]} (or (:resolution @state/turtle-atom) {:mode :n :value 16})]
                  (case mode :n value :a (max 8 (int (Math/ceil (/ 360 value)))) value)))]
    (shape/circle-shape radius res)))

;; ── Bindings map ──────────────────────────────────────────

(def ^:private base-bindings
  {;; Movement
   'f            implicit-f
   'th           implicit-th
   'tv           implicit-tv
   'tr           implicit-tr
   'pen-impl     implicit-pen
   'reset        implicit-reset
   'joint-mode   implicit-joint-mode
   'resolution   implicit-resolution
   ;; Arcs
   'arc-h        implicit-arc-h
   'arc-v        implicit-arc-v
   ;; Bezier
   'bezier-to    implicit-bezier-to
   'bezier-as    implicit-bezier-as
   ;; State stack
   'push-state   implicit-push-state
   'pop-state    implicit-pop-state
   ;; Navigation
   'goto         implicit-goto
   'look-at      implicit-look-at
   'follow-path  implicit-run-path
   ;; Turtle state access
   'turtle       turtle/make-turtle
   'get-turtle   get-turtle
   'get-turtle-resolution (fn [] (or (:resolution @state/turtle-atom) {:mode :n :value 16}))
   'get-turtle-joint-mode (fn [] (or (:joint-mode @state/turtle-atom) :flat))
   'turtle-position (fn [] (:position @state/turtle-atom))
   'turtle-heading  (fn [] (:heading @state/turtle-atom))
   'turtle-up       (fn [] (:up @state/turtle-atom))
   ;; Pure turtle functions (used by run-path in macro-defs)
   'turtle-f     turtle/f
   'turtle-th    turtle/th
   'turtle-tv    turtle/tv
   'turtle-tr    turtle/tr
   ;; Shapes (resolution-aware)
   'circle       circle-with-resolution
   'rect         shape/rect-shape
   'polygon      shape/ngon-shape
   'star         shape/star-shape
   'make-shape   shape/make-shape
   'shape?       shape/shape?
   ;; Shape transforms
   'scale-shape     shape/scale-shape
   'scale           shape/scale-shape
   'rotate-shape    xform/rotate
   'translate       xform/translate
   'morph           xform/morph
   'resample        xform/resample
   'path-to-shape   shape/path-to-shape
   ;; Path recording functions (used by macro-defs)
   'make-recorder       turtle/make-recorder
   'rec-f               turtle/rec-f
   'rec-th              turtle/rec-th
   'rec-tv              turtle/rec-tv
   'rec-tr              turtle/rec-tr
   'rec-u               turtle/rec-u
   'rec-rt              turtle/rec-rt
   'rec-lt              turtle/rec-lt
   'rec-set-heading     turtle/rec-set-heading
   'rec-inset           turtle/rec-inset
   'rec-scale           turtle/rec-scale
   'rec-move-to         turtle/rec-move-to
   'rec-play-path       turtle/rec-play-path
   'path-from-recorder  turtle/path-from-recorder
   ;; Shape recording functions (used by shape macro)
   'shape-rec-f         shape/rec-f
   'shape-rec-th        shape/rec-th
   'shape-from-recording shape/shape-from-recording
   'recording-turtle    shape/recording-turtle
   ;; Path utilities
   'run-path-impl       turtle/run-path
   'path?               turtle/path?
   'quick-path          turtle/quick-path
   'path-segments-impl  turtle/path-segments
   'subdivide-segment-impl turtle/subdivide-segment
   'compute-bezier-walk-impl turtle/compute-bezier-walk
   ;; Extrude/loft/revolve (production pure functions)
   'pure-extrude-path        gen-ops/pure-extrude-path
   'extrude-closed-path-impl gen-ops/implicit-extrude-closed-path
   'pure-loft-path           gen-ops/pure-loft-path
   'pure-loft-two-shapes     gen-ops/pure-loft-two-shapes
   'pure-loft-shape-fn       gen-ops/pure-loft-shape-fn
   'pure-bloft               gen-ops/pure-bloft
   'pure-bloft-shape-fn      gen-ops/pure-bloft-shape-fn
   'pure-bloft-two-shapes    gen-ops/pure-bloft-two-shapes
   'pure-revolve             gen-ops/pure-revolve
   'pure-revolve-shape-fn    gen-ops/pure-revolve-shape-fn
   ;; Shape-fn support
   'shape-fn?                sfn/shape-fn?
   ;; Impl functions (used by slimmed macros)
   'extrude-impl        macro-impl/extrude-impl
   'extrude-closed-impl macro-impl/extrude-closed-impl
   'loft-impl           macro-impl/loft-impl
   'loft-n-impl         macro-impl/loft-n-impl
   'bloft-impl          macro-impl/bloft-impl
   'bloft-n-impl        macro-impl/bloft-n-impl
   'revolve-impl        macro-impl/revolve-impl
   'attach-impl         macro-impl/attach-impl
   'attach-face-impl    macro-impl/attach-face-impl
   'clone-face-impl     macro-impl/clone-face-impl
   'attach!-impl        macro-impl/attach!-impl
   ;; Math (used by arc/bezier recording in macro-defs)
   'PI     js/Math.PI
   'abs    js/Math.abs
   'sin    js/Math.sin
   'cos    js/Math.cos
   'sqrt   js/Math.sqrt
   'ceil   js/Math.ceil
   'floor  js/Math.floor
   'round  js/Math.round
   'pow    js/Math.pow
   'atan2  js/Math.atan2
   'acos   js/Math.acos
   ;; Print (captured as no-ops in test)
   'println println
   'print   print
   ;; Anchor support (mark only meaningful inside path; no-op outside)
   'mark   (fn [_name] nil)
   ;; Needed for macro-defs follow-path reference
   'run-path (fn [p] (doseq [{:keys [cmd args]} (:commands p)]
                        (case cmd
                          :f  (implicit-f (first args))
                          :th (implicit-th (first args))
                          :tv (implicit-tv (first args))
                          :tr (implicit-tr (first args))
                          nil)))
   ;; Stubs for browser-only symbols referenced by macro-defs
   'register-mesh!      (fn [& _] nil)
   'show-mesh!          (fn [& _] nil)
   'hide-mesh!          (fn [& _] nil)
   'refresh-viewport!   (fn [& _] nil)
   'get-mesh            (fn [& _] nil)
   'get-anchor          (fn [& _] nil)
   'save-anchors*       (fn [] nil)
   'restore-anchors*    (fn [_] nil)
   'resolve-and-merge-marks* (fn [_] nil)
   ;; Registry / scene stubs
   'show-mesh-ref!      (fn [& _] nil)
   'hide-mesh-ref!      (fn [& _] nil)
   'show-all!           (fn [] nil)
   'hide-all!           (fn [] nil)
   'show-only-registered! (fn [] nil)
   'visible-names       (fn [] #{})
   'registered-names    (fn [] #{})
   'register-value!     (fn [& _] nil)
   'all-meshes-info     (fn [] [])
   'add-mesh!           (fn [& _] nil)
   'register-path!      (fn [& _] nil)
   'register-shape!     (fn [& _] nil)
   'attach-path         (fn [& _] nil)
   'bounds-2d           (fn [& _] {:min [0 0] :max [0 0]})
   ;; Panel stubs
   'panel?              (fn [_] false)
   'show-panel!         (fn [& _] nil)
   'hide-panel!         (fn [& _] nil)
   'register-panel!     (fn [& _] nil)
   'get-panel           (fn [& _] nil)
   ;; Animation stubs
   'get-camera-pose     (fn [] nil)
   'get-orbit-target    (fn [] [0 0 0])
   'play!               (fn [& _] nil)
   'anim-register!      (fn [& _] nil)
   'anim-proc-register! (fn [& _] nil)
   'anim-preprocess     (fn [& _] [])
   'anim-make-span      (fn [& _] nil)
   'anim-make-cmd       (fn [& _] nil)
   ;; Collision stubs
   'on-collide          (fn [& _] nil)
   'off-collide         (fn [& _] nil)
   ;; Assembly / linking stubs
   'link!               (fn [& _] nil)
   ;; Tweak stub
   'tweak-start!        (fn [& _] nil)
   ;; Export stub
   'save-stl            (fn [& _] nil)})

;; ── Public API ──────────────────────────────────────────────

(defn eval-dsl
  "Evaluate DSL code string through SCI. Returns:
   {:turtle <turtle-state-after>
    :result <SCI-eval-result>
    :error  <error-message-or-nil>}"
  [code]
  (reset-turtle!)
  (try
    (let [ctx (sci/init {:bindings base-bindings})]
      ;; Load production macros
      (sci/eval-string macros/macro-defs ctx)
      (let [result (sci/eval-string code ctx)]
        {:turtle @state/turtle-atom
         :result result
         :error nil}))
    (catch :default e
      {:turtle @state/turtle-atom
       :result nil
       :error (.-message e)})))

(defn eval-dsl-seq
  "Evaluate multiple DSL strings in sequence, preserving state.
   Returns final {:turtle :result :error}."
  [& codes]
  (reset-turtle!)
  (try
    (let [ctx (sci/init {:bindings base-bindings})]
      (sci/eval-string macros/macro-defs ctx)
      (let [result (last (mapv #(sci/eval-string % ctx) codes))]
        {:turtle @state/turtle-atom
         :result result
         :error nil}))
    (catch :default e
      {:turtle @state/turtle-atom
       :result nil
       :error (.-message e)})))
