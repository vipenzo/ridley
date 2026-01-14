(ns ridley.editor.repl
  "SCI-based evaluation of user code.

   User code is wrapped in a threading macro that passes turtle state
   through each form. The DSL functions are available without require."
  (:require [sci.core :as sci]
            [ridley.turtle.core :as turtle]))

(def ^:private turtle-bindings
  "Map of symbols to turtle functions for SCI context."
  {'f         turtle/f
   'b         turtle/b
   'u         turtle/u
   'd         turtle/d
   'th        turtle/th
   'tv        turtle/tv
   'tr        turtle/tr
   'pen-up    turtle/pen-up
   'pen-down  turtle/pen-down
   'turtle    turtle/make-turtle})

(def ^:private sci-ctx
  "SCI context with turtle functions preloaded."
  (sci/init {:bindings turtle-bindings}))

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
  "Extract geometry from evaluation result."
  [eval-result]
  (when-let [state (:result eval-result)]
    (:geometry state)))
