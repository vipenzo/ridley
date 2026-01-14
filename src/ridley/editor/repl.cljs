(ns ridley.editor.repl
  "SCI-based evaluation of user code.

   User code is wrapped in a threading macro that passes turtle state
   through each form. The DSL functions are available without require."
  (:require [sci.core :as sci]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.path :as path]))

(def ^:private turtle-bindings
  "Map of symbols to turtle functions for SCI context."
  {'f            turtle/f
   'b            turtle/b
   'u            turtle/u
   'd            turtle/d
   'th           turtle/th
   'tv           turtle/tv
   'tr           turtle/tr
   'pen-up       turtle/pen-up
   'pen-down     turtle/pen-down
   'turtle       turtle/make-turtle
   'path->data   path/path-from-state
   'shape->data  path/shape-from-state})

;; Macro definitions as strings for SCI
(def ^:private macro-defs
  "(defmacro path [& body]
     `(-> (turtle) ~@body path->data))

   (defmacro shape [& body]
     `(-> (turtle) ~@body shape->data))")

(def ^:private sci-ctx
  "SCI context with turtle functions and macros preloaded."
  (let [ctx (sci/init {:bindings turtle-bindings})]
    ;; Evaluate macro definitions in context
    (sci/eval-string macro-defs ctx)
    ctx))

(defn- wrap-code
  "Wrap user code in a threading form that threads turtle state."
  [code-str]
  (str "(-> (turtle)\n" code-str "\n)"))

(defn evaluate
  "Evaluate user code string, returning {:result turtle-state} or {:error msg}."
  [code-str]
  (try
    (let [wrapped (wrap-code code-str)
          result (sci/eval-string wrapped sci-ctx)]
      {:result result})
    (catch :default e
      {:error (.-message e)})))

(defn extract-geometry
  "Extract geometry from evaluation result.
   Handles turtle state, path, or shape results."
  [eval-result]
  (when-let [result (:result eval-result)]
    (cond
      ;; Path or shape result
      (:segments result) (:segments result)
      ;; Turtle state
      (:geometry result) (:geometry result)
      :else nil)))
