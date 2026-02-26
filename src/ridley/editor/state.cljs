(ns ridley.editor.state
  "Shared atoms and state accessors for the editor subsystem.
   All modules that need turtle-state-var or print-buffer require this namespace."
  (:require [ridley.turtle.core :as turtle]
            [sci.core :as sci]))

;; SCI dynamic var holding the current turtle atom.
;; Root value is an atom; (turtle ...) macro rebinds to a new atom.
;; @turtle-state-var → the atom, @@turtle-state-var → turtle state map.
(def turtle-state-var
  (sci/new-dynamic-var '*turtle-state* (atom (turtle/make-turtle))))

;; Atom to capture print output during evaluation
(defonce print-buffer (atom []))

(defn reset-print-buffer! []
  (reset! print-buffer []))

(defn ^:export capture-print [& args]
  (swap! print-buffer conj (apply str args)))

(defn ^:export capture-println [& args]
  (swap! print-buffer conj (str (apply str args) "\n")))

(defn get-print-output []
  (let [output (apply str @print-buffer)]
    (when (seq output) output)))

(defn reset-turtle! []
  (reset! @turtle-state-var (turtle/make-turtle)))

(defn get-turtle []
  @@turtle-state-var)

(defn ^:export get-turtle-resolution
  "Get current turtle resolution settings for use in path macro."
  []
  (:resolution @@turtle-state-var))

(defn ^:export get-turtle-joint-mode
  "Get current turtle joint-mode for use in path macro."
  []
  (:joint-mode @@turtle-state-var))

(defn get-turtle-pose
  "Get current turtle pose for indicator display.
   Returns {:position [x y z] :heading [x y z] :up [x y z]} or nil."
  []
  (when-let [t @@turtle-state-var]
    {:position (:position t)
     :heading (:heading t)
     :up (:up t)}))

(defn last-mesh []
  (last (:meshes @@turtle-state-var)))

;; ============================================================
;; Turtle Scope Initialization
;; ============================================================

(defn ^:export init-turtle
  "Create a new turtle state for a turtle scope. Called by the `turtle` macro.
   Options (all optional):
   - :reset true       — start from fresh make-turtle defaults
   - :pos [x y z]      — set position
   - :heading [x y z]  — set heading
   - :up [x y z]       — set up vector
   - :preserve-up true — enable preserve-up mode (Phase 3)
   Parent is the current turtle state (cloned by default)."
  [opts parent]
  (let [base (if (:reset opts)
               (turtle/make-turtle)
               ;; Clone parent: position, heading, up, pen-mode, resolution,
               ;; joint-mode, material, anchors, preserve-up, reference-up
               (select-keys parent
                 [:position :heading :up :pen-mode :resolution
                  :joint-mode :material :anchors
                  :preserve-up :reference-up]))
        ;; Apply overrides
        base (cond-> base
               (not (:reset opts))
               (merge {:geometry [] :meshes [] :stamps []
                       :stamped-shape nil :sweep-rings []
                       :pending-rotation nil :attached nil})
               (:reset opts)
               identity
               (:pos opts) (assoc :position (:pos opts))
               (:heading opts) (assoc :heading (:heading opts))
               (:up opts) (assoc :up (:up opts))
               ;; Enable preserve-up: set flag and capture reference-up from current up
               (:preserve-up opts)
               (-> (assoc :preserve-up true)
                   (#(assoc % :reference-up (or (:reference-up %) (:up %))))))]
    base))

;; SCI context reference — set by repl.cljs, read by test_mode.cljs.
;; This avoids a circular dependency (repl → bindings → test-mode → repl).
(defonce sci-ctx-ref (atom nil))

;; Run-definitions callback — set by core.cljs, read by bindings.cljs.
;; Avoids circular dependency (core → bindings → core).
(defonce run-definitions-fn (atom nil))

;; ============================================================
;; Scene Accumulator
;; ============================================================

;; Visual output (pen traces, stamps) shared across all turtle scopes.
;; Cleared at the start of each evaluation cycle.
(defonce scene-accumulator (atom {:lines [] :stamps []}))

(defn reset-scene-accumulator! []
  (reset! scene-accumulator {:lines [] :stamps []}))

;; ============================================================
;; Source Tracking Dynamic Vars
;; ============================================================

;; SCI dynamic vars for tracking where code is evaluated from.
;; Live in state.cljs (not repl.cljs) to avoid circular deps:
;; repl → bindings → state is fine; repl → bindings → repl is not.
(def eval-source-var (sci/new-dynamic-var '*eval-source* :unknown))
(def eval-text-var   (sci/new-dynamic-var '*eval-text*   nil))
