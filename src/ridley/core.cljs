(ns ridley.core
  "Main entry point for Ridley application."
  (:require [ridley.editor.repl :as repl]
            [ridley.viewport.core :as viewport]))

(defonce ^:private editor-el (atom nil))
(defonce ^:private error-el (atom nil))

(defn- show-error [msg]
  (when-let [el @error-el]
    (set! (.-textContent el) msg)
    (.add (.-classList el) "visible")))

(defn- hide-error []
  (when-let [el @error-el]
    (set! (.-textContent el) "")
    (.remove (.-classList el) "visible")))

(defn- evaluate-and-render []
  (when-let [el @editor-el]
    (let [code (.-value el)
          result (repl/evaluate code)]
      (if-let [error (:error result)]
        (show-error error)
        (do
          (hide-error)
          (when-let [geometry (repl/extract-geometry result)]
            (viewport/update-geometry geometry)))))))

(defn- setup-keybindings []
  (when-let [el @editor-el]
    (.addEventListener el "keydown"
      (fn [e]
        (when (and (.-metaKey e) (= (.-key e) "Enter"))
          (.preventDefault e)
          (evaluate-and-render))))))

(defn init []
  (let [canvas (.getElementById js/document "viewport")
        editor (.getElementById js/document "editor")
        error-panel (.getElementById js/document "error-panel")]
    (reset! editor-el editor)
    (reset! error-el error-panel)
    (viewport/init canvas)
    (setup-keybindings)
    ;; Set initial example code
    (set! (.-value editor) "(f 50)\n(th 90)\n(f 50)")
    (js/console.log "Ridley initialized. Press Cmd+Enter to evaluate.")))

(defn reload []
  ;; Hot reload callback - re-evaluate current code
  (evaluate-and-render))
