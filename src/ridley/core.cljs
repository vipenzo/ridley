(ns ridley.core
  "Main entry point for Ridley application."
  (:require [ridley.editor.repl :as repl]
            [ridley.viewport.core :as viewport]))

(defonce ^:private explicit-el (atom nil))
(defonce ^:private implicit-el (atom nil))
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
  (let [explicit-code (when-let [el @explicit-el] (.-value el))
        implicit-code (when-let [el @implicit-el] (.-value el))
        result (repl/evaluate explicit-code implicit-code)]
    (if-let [error (:error result)]
      (show-error error)
      (do
        (hide-error)
        (when-let [render-data (repl/extract-render-data result)]
          (viewport/update-scene render-data))))))

;; ============================================================
;; Save/Load functionality
;; ============================================================

(def ^:private storage-key "ridley-definitions")

(defn- save-to-storage
  "Auto-save definitions to localStorage."
  []
  (when-let [el @explicit-el]
    (.setItem js/localStorage storage-key (.-value el))))

(defn- load-from-storage
  "Load definitions from localStorage if available."
  []
  (.getItem js/localStorage storage-key))

(defn- save-definitions []
  (when-let [el @explicit-el]
    (let [content (.-value el)
          blob (js/Blob. #js [content] #js {:type "text/plain"})
          url (js/URL.createObjectURL blob)
          link (.createElement js/document "a")]
      (set! (.-href link) url)
      (set! (.-download link) "definitions.clj")
      (.click link)
      (js/URL.revokeObjectURL url)
      ;; Also save to localStorage
      (save-to-storage))))

(defn- load-definitions [file]
  (let [reader (js/FileReader.)]
    (set! (.-onload reader)
      (fn [e]
        (when-let [el @explicit-el]
          (set! (.-value el) (.. e -target -result))
          ;; Auto-save to localStorage after loading
          (save-to-storage))))
    (.readAsText reader file)))

;; ============================================================
;; Setup
;; ============================================================

(defn- setup-keybindings []
  ;; Add keybindings to both editors
  (doseq [el-atom [explicit-el implicit-el]]
    (when-let [el @el-atom]
      (.addEventListener el "keydown"
        (fn [e]
          (when (and (.-metaKey e) (= (.-key e) "Enter"))
            (.preventDefault e)
            (evaluate-and-render))))))
  ;; Auto-save definitions on change
  (when-let [el @explicit-el]
    (.addEventListener el "input" (fn [_] (save-to-storage)))))

(defn- setup-save-load []
  (let [save-btn (.getElementById js/document "btn-save")
        load-btn (.getElementById js/document "btn-load")
        file-input (.getElementById js/document "file-input")]
    ;; Save button
    (.addEventListener save-btn "click"
      (fn [_] (save-definitions)))
    ;; Load button - trigger file input
    (.addEventListener load-btn "click"
      (fn [_] (.click file-input)))
    ;; File input change
    (.addEventListener file-input "change"
      (fn [e]
        (when-let [file (aget (.-files (.-target e)) 0)]
          (load-definitions file))
        ;; Reset input so same file can be loaded again
        (set! (.-value file-input) "")))))

;; ============================================================
;; Initialization
;; ============================================================

(defn init []
  (let [canvas (.getElementById js/document "viewport")
        explicit (.getElementById js/document "editor-explicit")
        implicit (.getElementById js/document "editor-implicit")
        error-panel (.getElementById js/document "error-panel")]
    (reset! explicit-el explicit)
    (reset! implicit-el implicit)
    (reset! error-el error-panel)
    (viewport/init canvas)
    (setup-keybindings)
    (setup-save-load)
    ;; Load from localStorage or set default
    (if-let [saved (load-from-storage)]
      (set! (.-value explicit) saved)
      (set! (.-value explicit) "; Define reusable shapes here
(def sq (shape (f 20) (th 90) (f 20) (th 90) (f 20) (th 90) (f 20)))
(extrude-z sq 30)"))
    ;; Commands area starts empty
    (set! (.-value implicit) "")
    (js/console.log "Ridley initialized. Press Cmd+Enter to evaluate.")))

(defn reload []
  ;; Hot reload callback - re-evaluate current code
  (evaluate-and-render))
