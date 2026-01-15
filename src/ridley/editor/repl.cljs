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
            [ridley.turtle.path :as path]
            [ridley.geometry.primitives :as prims]
            [ridley.geometry.operations :as ops]))

;; Global turtle state for implicit mode
(defonce ^:private turtle-atom (atom nil))

(defn- reset-turtle! []
  (reset! turtle-atom (turtle/make-turtle)))

;; ============================================================
;; Implicit turtle functions (mutate atom)
;; ============================================================

(defn- implicit-f [dist]
  (swap! turtle-atom turtle/f dist))

(defn- implicit-b [dist]
  (swap! turtle-atom turtle/b dist))

(defn- implicit-u [dist]
  (swap! turtle-atom turtle/u dist))

(defn- implicit-d [dist]
  (swap! turtle-atom turtle/d dist))

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

;; ============================================================
;; Shared SCI context
;; ============================================================

(def ^:private base-bindings
  "Bindings available in both explicit and implicit sections."
  {;; Implicit turtle commands (mutate atom)
   'f            implicit-f
   'b            implicit-b
   'u            implicit-u
   'd            implicit-d
   'th           implicit-th
   'tv           implicit-tv
   'tr           implicit-tr
   'pen-up       implicit-pen-up
   'pen-down     implicit-pen-down
   'box          implicit-box
   'sphere       implicit-sphere
   'cyl          implicit-cyl
   'cone         implicit-cone
   ;; Pure turtle functions (for explicit threading)
   'turtle       turtle/make-turtle
   'turtle-f     turtle/f
   'turtle-b     turtle/b
   'turtle-u     turtle/u
   'turtle-d     turtle/d
   'turtle-th    turtle/th
   'turtle-tv    turtle/tv
   'turtle-tr    turtle/tr
   'turtle-pen-up   turtle/pen-up
   'turtle-pen-down turtle/pen-down
   'turtle-box      prims/box
   'turtle-sphere   prims/sphere
   'turtle-cyl      prims/cyl
   'turtle-cone     prims/cone
   ;; Path/shape utilities
   'path->data   path/path-from-state
   'shape->data  path/shape-from-state
   ;; Generative operations
   'extrude      ops/extrude
   'extrude-z    ops/extrude-z
   'extrude-y    ops/extrude-y
   'revolve      ops/revolve
   'sweep        ops/sweep
   'loft         ops/loft
   ;; Access current turtle state
   'get-turtle   get-turtle})

;; Macro definitions for explicit mode
;; These macros rewrite turtle commands to use pure functions for threading.
(def ^:private macro-defs
  "(def ^:private pure-fn-map
     {'f 'turtle-f 'b 'turtle-b 'u 'turtle-u 'd 'turtle-d
      'th 'turtle-th 'tv 'turtle-tv 'tr 'turtle-tr
      'pen-up 'turtle-pen-up 'pen-down 'turtle-pen-down
      'box 'turtle-box 'sphere 'turtle-sphere
      'cyl 'turtle-cyl 'cone 'turtle-cone})

   (defn- rewrite-form [form]
     (if (seq? form)
       (let [[head & args] form
             new-head (get pure-fn-map head head)]
         (cons new-head (map rewrite-form args)))
       form))

   (defmacro path [& body]
     (let [rewritten (map rewrite-form body)]
       `(-> (turtle) ~@rewritten path->data)))

   (defmacro shape [& body]
     (let [rewritten (map rewrite-form body)]
       `(-> (turtle) ~@rewritten shape->data)))

   (defmacro with-turtle [& body]
     (let [rewritten (map rewrite-form body)]
       `(-> (turtle) ~@rewritten)))")

;; Create a fresh SCI context for each evaluation session
(defn- make-sci-ctx []
  (let [ctx (sci/init {:bindings base-bindings})]
    (sci/eval-string macro-defs ctx)
    ctx))

;; ============================================================
;; Evaluation
;; ============================================================

(defn evaluate
  "Evaluate both explicit and implicit code sections.
   Returns {:result turtle-state :explicit-result any} or {:error msg}."
  [explicit-code implicit-code]
  (try
    (let [ctx (make-sci-ctx)]
      ;; Reset turtle for implicit commands
      (reset-turtle!)
      ;; Phase 1: Evaluate explicit code (definitions, functions, explicit geometry)
      (let [explicit-result (when (and explicit-code (seq (str/trim explicit-code)))
                              (sci/eval-string explicit-code ctx))]
        ;; Phase 2: Evaluate implicit code (turtle commands)
        (when (and implicit-code (seq (str/trim implicit-code)))
          (sci/eval-string implicit-code ctx))
        ;; Return combined result
        {:result @turtle-atom
         :explicit-result explicit-result}))
    (catch :default e
      {:error (.-message e)})))

(defn extract-render-data
  "Extract render data from evaluation result.
   Combines geometry from turtle state and any explicit geometry results."
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
        ;; Combine all geometry
        all-lines (concat turtle-lines (or (:lines explicit-data) []))
        all-meshes (concat turtle-meshes (or (:meshes explicit-data) []))]
    (when (or (seq all-lines) (seq all-meshes))
      {:lines (vec all-lines)
       :meshes (vec all-meshes)})))
