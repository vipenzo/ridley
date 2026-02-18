(ns ridley.editor.repl
  "SCI-based evaluation of user code.

   Two-phase evaluation:
   1. Explicit section: Full Clojure for definitions, functions, data
   2. Implicit section: Turtle commands that mutate a global atom

   Both phases share the same SCI context, so definitions from explicit
   are available in implicit.

   This namespace is the slim coordinator — atoms live in state.cljs,
   implicit functions in implicit.cljs, operations in operations.cljs,
   text ops in text_ops.cljs, bindings in bindings.cljs, and macros
   in macros.cljs."
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [ridley.editor.state :as state]
            [ridley.editor.bindings :refer [base-bindings]]
            [ridley.editor.macros :refer [macro-defs]]
            [ridley.library.core :as library]))

;; ============================================================
;; SCI Context Management
;; ============================================================

;; Persistent SCI context - created once, reused for REPL commands
(defonce ^:private sci-ctx (atom nil))

(defn- make-sci-ctx []
  (let [lib-ns (library/load-active-libraries base-bindings macro-defs)
        ctx (sci/init {:bindings base-bindings
                       :namespaces lib-ns})]
    (sci/eval-string macro-defs ctx)
    ;; Auto-require library namespaces so prefixed access (shapes/hexagon) works
    (doseq [ns-sym (keys lib-ns)]
      (sci/eval-string (str "(require '" ns-sym ")") ctx))
    ctx))

(defn- get-or-create-ctx []
  (if-let [ctx @sci-ctx]
    ctx
    (let [ctx (make-sci-ctx)]
      (reset! sci-ctx ctx)
      (reset! state/sci-ctx-ref ctx)
      ctx)))

(defn reset-ctx!
  "Reset the SCI context. Called when definitions are re-evaluated."
  []
  (let [ctx (make-sci-ctx)]
    (reset! sci-ctx ctx)
    (reset! state/sci-ctx-ref ctx)))

;; ============================================================
;; Evaluation
;; ============================================================

(defn evaluate-definitions
  "Evaluate definitions code only. Resets context and turtle state.
   Called when user runs definitions panel (Cmd+Enter or Run button).
   Returns {:result turtle-state :explicit-result any :print-output str} or {:error msg}."
  [explicit-code]
  (try
    ;; Reset context for fresh definitions evaluation
    (reset-ctx!)
    (let [ctx (get-or-create-ctx)]
      ;; Reset turtle and print buffer for fresh evaluation
      (state/reset-turtle!)
      (state/reset-print-buffer!)
      ;; Evaluate explicit code (definitions, functions, explicit geometry)
      (let [explicit-result (when (and explicit-code (seq (str/trim explicit-code)))
                              (sci/eval-string explicit-code ctx))
            print-output (state/get-print-output)]
        {:result @state/turtle-atom
         :explicit-result explicit-result
         :implicit-result nil
         :print-output print-output}))
    (catch :default e
      (let [data (ex-data e)
            line (:line data)
            col  (:column data)
            msg  (.-message e)
            loc  (when line (str " (line " line (when col (str ":" col)) ")"))]
        {:error (str msg loc)}))))

(defn evaluate-repl
  "Evaluate REPL input only, using existing context.
   Definitions must be evaluated first to populate the context.
   Turtle pose (position, heading, up) persists between REPL commands.
   Geometry is cleared each command (only shows current command's output).
   Returns {:result turtle-state :implicit-result any :print-output str} or {:error msg}."
  [repl-code]
  (try
    (let [ctx (get-or-create-ctx)]
      ;; Preserve turtle pose but clear geometry for fresh output
      (if (nil? @state/turtle-atom)
        (state/reset-turtle!)
        ;; Keep position/heading/up, clear geometry/meshes
        (swap! state/turtle-atom assoc :geometry [] :meshes []))
      ;; Reset print buffer for this command
      (state/reset-print-buffer!)
      ;; Evaluate REPL code using existing context with definitions
      (let [implicit-result (when (and repl-code (seq (str/trim repl-code)))
                              (sci/eval-string repl-code ctx))
            print-output (state/get-print-output)]
        {:result @state/turtle-atom
         :explicit-result nil
         :implicit-result implicit-result
         :print-output print-output}))
    (catch :default e
      (let [data (ex-data e)
            line (:line data)
            col  (:column data)
            msg  (.-message e)
            loc  (when line (str " (line " line (when col (str ":" col)) ")"))]
        {:error (str msg loc)}))))

(defn evaluate
  "Evaluate both explicit and implicit code sections (legacy API).
   Returns {:result turtle-state :explicit-result any :implicit-result any :print-output str} or {:error msg}."
  [explicit-code implicit-code]
  (try
    ;; Reset context for fresh evaluation
    (reset-ctx!)
    (let [ctx (get-or-create-ctx)]
      ;; Reset turtle and print buffer for fresh evaluation
      (state/reset-turtle!)
      (state/reset-print-buffer!)
      ;; Phase 1: Evaluate explicit code (definitions, functions, explicit geometry)
      (let [explicit-result (when (and explicit-code (seq (str/trim explicit-code)))
                              (sci/eval-string explicit-code ctx))
            ;; Phase 2: Evaluate implicit code (turtle commands)
            implicit-result (when (and implicit-code (seq (str/trim implicit-code)))
                              (sci/eval-string implicit-code ctx))
            print-output (state/get-print-output)]
        ;; Return combined result
        {:result @state/turtle-atom
         :explicit-result explicit-result
         :implicit-result implicit-result
         :print-output print-output}))
    (catch :default e
      (let [data (ex-data e)
            line (:line data)
            col  (:column data)
            msg  (.-message e)
            loc  (when line (str " (line " line (when col (str ":" col)) ")"))]
        {:error (str msg loc)}))))

(defn extract-render-data
  "Extract render data from evaluation result.
   Returns turtle geometry (lines from pen movements).
   Meshes must be explicitly registered with (register name mesh) to be visible.
   Does NOT include registry meshes - those are handled separately by refresh-viewport!."
  [eval-result]
  (let [turtle-state (:result eval-result)
        turtle-lines (or (:geometry turtle-state) [])
        turtle-stamps (or (:stamps turtle-state) [])]
    ;; Only return turtle lines (pen traces) and stamps (debug outlines)
    ;; Meshes from extrude/loft/etc are NOT auto-displayed - use register
    (when (or (seq turtle-lines) (seq turtle-stamps))
      {:lines (vec turtle-lines)
       :stamps (vec turtle-stamps)
       :meshes []})))
