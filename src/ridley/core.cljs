(ns ridley.core
  "Main entry point for Ridley application."
  (:require [clojure.string :as str]
            [ridley.editor.repl :as repl]
            [ridley.viewport.core :as viewport]
            [ridley.viewport.xr :as xr]
            [ridley.manifold.core :as manifold]))

(defonce ^:private explicit-el (atom nil))
(defonce ^:private repl-input-el (atom nil))
(defonce ^:private repl-history-el (atom nil))
(defonce ^:private error-el (atom nil))

;; Command history
(defonce ^:private command-history (atom []))
(defonce ^:private history-index (atom -1))

(defn- show-error [msg]
  (when-let [el @error-el]
    (set! (.-textContent el) msg)
    (.add (.-classList el) "visible")))

(defn- hide-error []
  (when-let [el @error-el]
    (set! (.-textContent el) "")
    (.remove (.-classList el) "visible")))

(defn- format-value
  "Format a Clojure value for REPL display."
  [v]
  (cond
    (nil? v) "nil"
    (string? v) (pr-str v)
    (keyword? v) (str v)
    (map? v) (pr-str v)
    (vector? v) (pr-str v)
    (seq? v) (pr-str (vec v))
    (fn? v) "#<function>"
    :else (str v)))

(defn- escape-html [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- add-repl-entry
  "Add an entry to the REPL history display."
  [input result error?]
  (when-let [history-el @repl-history-el]
    (let [entry (.createElement js/document "div")
          formatted (if error?
                      (escape-html result)
                      (escape-html (format-value result)))
          result-class (cond
                         error? "repl-error"
                         (nil? result) "repl-nil"
                         :else "repl-result")]
      (.add (.-classList entry) "repl-entry")
      (set! (.-innerHTML entry)
            (str "<div class=\"repl-input-echo\">"
                 "<span class=\"repl-prompt\">&gt; </span>"
                 (escape-html input)
                 "</div>"
                 "<div class=\"" result-class "\">"
                 formatted
                 "</div>"))
      (.appendChild history-el entry)
      ;; Scroll to bottom
      (set! (.-scrollTop history-el) (.-scrollHeight history-el)))))

(defn- evaluate-definitions
  "Evaluate only the definitions panel (for Cmd+Enter)."
  []
  (let [explicit-code (when-let [el @explicit-el] (.-value el))
        result (repl/evaluate explicit-code nil)]
    (if-let [error (:error result)]
      (show-error error)
      (do
        (hide-error)
        (when-let [render-data (repl/extract-render-data result)]
          (viewport/update-scene render-data))))))

(defn- evaluate-repl-input
  "Evaluate the REPL input and show result in history."
  []
  (when-let [input-el @repl-input-el]
    (let [input (.-value input-el)]
      (when (seq (str/trim input))
        ;; Add to command history
        (swap! command-history conj input)
        (reset! history-index -1)
        ;; Clear input
        (set! (.-value input-el) "")
        ;; Evaluate with definitions context
        (let [explicit-code (when-let [el @explicit-el] (.-value el))
              result (repl/evaluate explicit-code input)]
          (if-let [error (:error result)]
            (do
              (add-repl-entry input error true)
              (show-error error))
            (do
              (hide-error)
              ;; Show result in terminal history
              (add-repl-entry input (:implicit-result result) false)
              ;; Update viewport
              (when-let [render-data (repl/extract-render-data result)]
                (viewport/update-scene render-data)))))))))

(defn- navigate-history
  "Navigate command history. direction: -1 for older, +1 for newer."
  [direction]
  (when-let [input-el @repl-input-el]
    (let [history @command-history
          current-idx @history-index
          history-len (count history)
          new-idx (cond
                    ;; Going back in history
                    (= direction -1)
                    (if (= current-idx -1)
                      (dec history-len)  ; Start from most recent
                      (max 0 (dec current-idx)))
                    ;; Going forward in history
                    (= direction 1)
                    (if (>= current-idx (dec history-len))
                      -1  ; Past end, clear
                      (inc current-idx))
                    :else current-idx)]
      (reset! history-index new-idx)
      (if (= new-idx -1)
        (set! (.-value input-el) "")
        (set! (.-value input-el) (nth history new-idx))))))

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
;; Server scripts (for VR access)
;; ============================================================

(defn- fetch-script-list
  "Fetch list of available scripts from server."
  []
  (-> (js/fetch "scripts/index.json")
      (.then #(.json %))
      (.catch (fn [_] #js []))))

(defn- fetch-script
  "Fetch a script by name and load it."
  [script-name]
  (-> (js/fetch (str "scripts/" script-name))
      (.then #(.text %))
      (.then (fn [content]
               (when-let [el @explicit-el]
                 (set! (.-value el) content)
                 (save-to-storage)
                 (evaluate-definitions))))
      (.catch #(js/console.error "Failed to load script:" %))))

(defn- show-script-picker
  "Show a dialog to pick a script from server."
  []
  (-> (fetch-script-list)
      (.then (fn [scripts]
               (let [scripts-arr (js->clj scripts)]
                 (if (empty? scripts-arr)
                   (js/alert "No scripts available")
                   (let [msg (str "Available scripts:\n"
                                  (str/join "\n" (map-indexed #(str (inc %1) ". " %2) scripts-arr))
                                  "\n\nEnter number:")
                         choice (js/prompt msg "1")]
                     (when choice
                       (let [idx (dec (js/parseInt choice 10))]
                         (when (and (>= idx 0) (< idx (count scripts-arr)))
                           (fetch-script (nth scripts-arr idx))))))))))))

;; ============================================================
;; Resizable panels
;; ============================================================

(defn- setup-vertical-resizer
  "Setup vertical resizer between definitions and REPL sections."
  []
  (let [divider (.querySelector js/document ".section-divider")
        explicit-section (.getElementById js/document "explicit-section")
        repl-section (.getElementById js/document "repl-section")
        editor-panel (.getElementById js/document "editor-panel")
        dragging (atom false)
        start-y (atom 0)
        start-explicit-height (atom 0)]
    (when (and divider explicit-section repl-section)
      (.addEventListener divider "mousedown"
        (fn [e]
          (.preventDefault e)
          (reset! dragging true)
          (reset! start-y (.-clientY e))
          (reset! start-explicit-height (.-offsetHeight explicit-section))
          (.add (.-classList js/document.body) "resizing-v")))
      (.addEventListener js/document "mousemove"
        (fn [e]
          (when @dragging
            (let [delta (- (.-clientY e) @start-y)
                  panel-height (.-offsetHeight editor-panel)
                  new-height (+ @start-explicit-height delta)
                  min-height 80
                  max-height (- panel-height 120)] ; Leave room for REPL
              (when (and (>= new-height min-height) (<= new-height max-height))
                (set! (.-flexGrow (.-style explicit-section)) "0")
                (set! (.-flexBasis (.-style explicit-section)) (str new-height "px"))
                (set! (.-flexGrow (.-style repl-section)) "1"))))))
      (.addEventListener js/document "mouseup"
        (fn [_]
          (when @dragging
            (reset! dragging false)
            (.remove (.-classList js/document.body) "resizing-v")))))))

(defn- setup-horizontal-resizer
  "Setup horizontal resizer between editor panel and viewport."
  []
  (let [editor-panel (.getElementById js/document "editor-panel")
        viewport-panel (.getElementById js/document "viewport-panel")
        app (.getElementById js/document "app")
        dragging (atom false)
        start-x (atom 0)
        start-width (atom 0)]
    (when (and editor-panel viewport-panel)
      ;; Create resizer element
      (let [resizer (.createElement js/document "div")]
        (set! (.-id resizer) "panel-resizer")
        (.insertBefore app resizer viewport-panel)
        (.addEventListener resizer "mousedown"
          (fn [e]
            (.preventDefault e)
            (reset! dragging true)
            (reset! start-x (.-clientX e))
            (reset! start-width (.-offsetWidth editor-panel))
            (.add (.-classList js/document.body) "resizing-h")))
        (.addEventListener js/document "mousemove"
          (fn [e]
            (when @dragging
              (let [delta (- (.-clientX e) @start-x)
                    app-width (.-offsetWidth app)
                    new-width (+ @start-width delta)
                    min-width 250
                    max-width (- app-width 300)] ; Leave room for viewport
                (when (and (>= new-width min-width) (<= new-width max-width))
                  (set! (.-width (.-style editor-panel)) (str new-width "px"))
                  (set! (.-minWidth (.-style editor-panel)) (str new-width "px"))
                  ;; Notify Three.js to resize
                  (viewport/handle-resize))))))
        (.addEventListener js/document "mouseup"
          (fn [_]
            (when @dragging
              (reset! dragging false)
              (.remove (.-classList js/document.body) "resizing-h"))))))))

;; ============================================================
;; Setup
;; ============================================================

(defn- setup-keybindings []
  ;; Definitions panel: Cmd+Enter to run definitions only
  (when-let [el @explicit-el]
    (.addEventListener el "keydown"
      (fn [e]
        (when (and (.-metaKey e) (= (.-key e) "Enter"))
          (.preventDefault e)
          (evaluate-definitions))))
    ;; Auto-save definitions on change
    (.addEventListener el "input" (fn [_] (save-to-storage))))
  ;; REPL input: Enter to run, arrows for history
  (when-let [el @repl-input-el]
    (.addEventListener el "keydown"
      (fn [e]
        (case (.-key e)
          "Enter"
          (do
            (.preventDefault e)
            (evaluate-repl-input))
          "ArrowUp"
          (do
            (.preventDefault e)
            (navigate-history -1))
          "ArrowDown"
          (do
            (.preventDefault e)
            (navigate-history 1))
          nil)))))

(defn- export-stl []
  ;; Placeholder - will implement actual STL export
  (js/alert "STL export coming soon!"))

(defn- setup-save-load []
  (let [run-btn (.getElementById js/document "btn-run")
        save-btn (.getElementById js/document "btn-save")
        load-btn (.getElementById js/document "btn-load")
        examples-btn (.getElementById js/document "btn-examples")
        export-stl-btn (.getElementById js/document "btn-export-stl")
        file-input (.getElementById js/document "file-input")]
    ;; Run button - evaluate definitions
    (.addEventListener run-btn "click"
      (fn [_] (evaluate-definitions)))
    ;; Save button
    (.addEventListener save-btn "click"
      (fn [_] (save-definitions)))
    ;; Load button - open file picker for local files
    (.addEventListener load-btn "click"
      (fn [_] (.click file-input)))
    ;; Examples button - show script picker from server
    (when examples-btn
      (.addEventListener examples-btn "click"
        (fn [_] (show-script-picker))))
    ;; Export STL button
    (when export-stl-btn
      (.addEventListener export-stl-btn "click"
        (fn [_] (export-stl))))
    ;; File input change (for local file loading)
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
        repl-input (.getElementById js/document "repl-input")
        repl-history (.getElementById js/document "repl-history")
        error-panel (.getElementById js/document "error-panel")]
    (reset! explicit-el explicit)
    (reset! repl-input-el repl-input)
    (reset! repl-history-el repl-history)
    (reset! error-el error-panel)
    (viewport/init canvas)
    (setup-keybindings)
    (setup-save-load)
    (setup-vertical-resizer)
    (setup-horizontal-resizer)
    ;; Setup VR and AR buttons in toolbar
    (let [toolbar (.getElementById js/document "viewport-toolbar")
          renderer (viewport/get-renderer)
          vr-btn (xr/create-vr-button renderer #(xr/toggle-vr renderer))
          ar-btn (xr/create-ar-button renderer #(xr/toggle-ar renderer))]
      (when toolbar
        (when ar-btn
          (.insertBefore toolbar ar-btn (.-firstChild toolbar)))
        (when vr-btn
          (.insertBefore toolbar vr-btn (.-firstChild toolbar)))))
    ;; Initialize Manifold WASM (async)
    (-> (manifold/init!)
        (.then #(js/console.log "Manifold WASM initialized"))
        (.catch #(js/console.warn "Manifold WASM failed to initialize:" %)))
    ;; Load from localStorage or set default
    (if-let [saved (load-from-storage)]
      (set! (.-value explicit) saved)
      (set! (.-value explicit) "; Define reusable shapes here
(def sq (path (dotimes [_ 4] (f 20) (th 90))))

; Run with Cmd+Enter, then use REPL below"))
    ;; Focus REPL input
    (when repl-input
      (.focus repl-input))
    (js/console.log "Ridley initialized. Cmd+Enter for definitions, Enter in REPL.")))

(defn reload []
  ;; Hot reload callback - re-evaluate definitions
  (evaluate-definitions))
