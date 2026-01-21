(ns ridley.core
  "Main entry point for Ridley application."
  (:require [clojure.string :as str]
            [ridley.editor.repl :as repl]
            [ridley.editor.codemirror :as cm]
            [ridley.viewport.core :as viewport]
            [ridley.viewport.xr :as xr]
            [ridley.manifold.core :as manifold]
            [ridley.turtle.text :as text]
            [ridley.scene.registry :as registry]
            [ridley.export.stl :as stl]
            [ridley.sync.peer :as sync]))

(defonce ^:private editor-view (atom nil))
(defonce ^:private repl-input-el (atom nil))
(defonce ^:private repl-history-el (atom nil))
(defonce ^:private error-el (atom nil))

;; Command history
(defonce ^:private command-history (atom []))
(defonce ^:private history-index (atom -1))

;; View toggle state: true = show all, false = show only registered objects
(defonce ^:private show-all-view (atom true))

;; Sync state
(defonce ^:private sync-mode (atom nil))  ; nil, :host, or :client
(defonce ^:private sync-debounce-timer (atom nil))
(defonce ^:private share-modal-el (atom nil))  ; Reference to share modal for closing on connect
(defonce ^:private connected-host-id (atom nil))  ; Host peer-id when we're a client

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
  "Evaluate only the definitions panel (for Cmd+Enter).
   Optional reset-camera? parameter controls whether to reset camera view (default false)."
  ([] (evaluate-definitions false))
  ([reset-camera?]
   ;; Clear registry when re-running definitions (fresh start)
   (registry/clear-all!)
   (let [explicit-code (cm/get-value @editor-view)
         result (repl/evaluate-definitions explicit-code)]
     (if-let [error (:error result)]
       (show-error error)
       (do
         (hide-error)
         (when-let [render-data (repl/extract-render-data result)]
           ;; Store lines and definition meshes
           (registry/set-lines! (:lines render-data))
           (registry/set-definition-meshes! (:meshes render-data)))
         ;; Refresh viewport, optionally resetting camera
         (registry/refresh-viewport! reset-camera?))))))

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
        ;; Evaluate REPL input only (definitions already in context)
        (let [result (repl/evaluate-repl input)]
          (if-let [error (:error result)]
            (do
              (add-repl-entry input error true)
              (show-error error))
            (do
              (hide-error)
              ;; Show result in terminal history
              (add-repl-entry input (:implicit-result result) false)
              ;; Extract lines and meshes from REPL evaluation
              (when-let [render-data (repl/extract-render-data result)]
                (registry/add-lines! (:lines render-data))
                (registry/set-definition-meshes! (:meshes render-data)))
              ;; Update viewport (don't reset camera)
              (registry/refresh-viewport! false))))))))

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
  (when-let [content (cm/get-value @editor-view)]
    (.setItem js/localStorage storage-key content)))

(defn- load-from-storage
  "Load definitions from localStorage if available."
  []
  (.getItem js/localStorage storage-key))

(defn- save-definitions []
  (when-let [content (cm/get-value @editor-view)]
    (let [blob (js/Blob. #js [content] #js {:type "text/plain"})
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
        (cm/set-value @editor-view (.. e -target -result))
        ;; Auto-save to localStorage after loading
        (save-to-storage)))
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
               (cm/set-value @editor-view content)
               (save-to-storage)
               (evaluate-definitions)))
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
  ;; CodeMirror handles Cmd+Enter via keymap and on-change via update listener
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
  (let [meshes (viewport/get-current-meshes)]
    (if (seq meshes)
      (stl/download-stl meshes "ridley-model.stl")
      (js/alert "No meshes to export. Run some code first!"))))

(defn- toggle-view []
  "Toggle between showing all meshes and only registered objects."
  (let [btn (.getElementById js/document "btn-toggle-view")]
    (swap! show-all-view not)
    (if @show-all-view
      (do
        (registry/show-all!)
        (set! (.-textContent btn) "All"))
      (do
        (registry/show-only-registered!)
        (set! (.-textContent btn) "Obj")))
    (registry/refresh-viewport! false)))

(defn- setup-save-load []
  (let [run-btn (.getElementById js/document "btn-run")
        save-btn (.getElementById js/document "btn-save")
        load-btn (.getElementById js/document "btn-load")
        examples-btn (.getElementById js/document "btn-examples")
        export-stl-btn (.getElementById js/document "btn-export-stl")
        toggle-view-btn (.getElementById js/document "btn-toggle-view")
        toggle-grid-btn (.getElementById js/document "btn-toggle-grid")
        toggle-axes-btn (.getElementById js/document "btn-toggle-axes")
        reset-view-btn (.getElementById js/document "btn-reset-view")
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
    ;; Toggle view button
    (when toggle-view-btn
      (.addEventListener toggle-view-btn "click"
        (fn [_] (toggle-view))))
    ;; Toggle grid button
    (when toggle-grid-btn
      ;; Set initial active state (grid is visible by default)
      (.add (.-classList toggle-grid-btn) "active")
      (.addEventListener toggle-grid-btn "click"
        (fn [_]
          (let [visible (viewport/toggle-grid)]
            (if visible
              (.add (.-classList toggle-grid-btn) "active")
              (.remove (.-classList toggle-grid-btn) "active"))))))
    ;; Toggle axes button
    (when toggle-axes-btn
      ;; Set initial active state (axes visible by default)
      (.add (.-classList toggle-axes-btn) "active")
      (.addEventListener toggle-axes-btn "click"
        (fn [_]
          (let [visible (viewport/toggle-axes)]
            (if visible
              (.add (.-classList toggle-axes-btn) "active")
              (.remove (.-classList toggle-axes-btn) "active"))))))
    ;; Reset view button
    (when reset-view-btn
      (.addEventListener reset-view-btn "click"
        (fn [_] (viewport/reset-camera))))
    ;; File input change (for local file loading)
    (.addEventListener file-input "change"
      (fn [e]
        (when-let [file (aget (.-files (.-target e)) 0)]
          (load-definitions file))
        ;; Reset input so same file can be loaded again
        (set! (.-value file-input) "")))))

;; ============================================================
;; Sync (Desktop <-> Headset)
;; ============================================================

(declare join-session)

(defn- send-script-debounced
  "Send script to connected peer with debounce.
   Only sends if we're the host (client never sends, only receives)."
  []
  (when (and (sync/connected?)
             (= :host @sync-mode))
    (when-let [timer @sync-debounce-timer]
      (js/clearTimeout timer))
    (reset! sync-debounce-timer
            (js/setTimeout
             (fn []
               (when-let [content (cm/get-value @editor-view)]
                 (sync/send-script content)))
             500))))

(defn- on-script-received
  "Called when we receive a script from peer (client only receives, never sends back)."
  [definitions]
  (when @editor-view
    (cm/set-value @editor-view definitions)
    (save-to-storage)
    (evaluate-definitions)))

(defn- update-sync-status-text
  "Update the sync status text in toolbar."
  []
  (when-let [status-el (.getElementById js/document "sync-status")]
    (let [status (sync/get-status)]
      (set! (.-textContent status-el)
            (case status
              :connected (if (= :host @sync-mode)
                           (let [peer-id (sync/get-peer-id)
                                 short-code (sync/get-short-code peer-id)
                                 client-count (sync/get-client-count)]
                             (str client-count " client" (when (not= client-count 1) "s")
                                  " · " short-code))
                           ;; Client: show host's code
                           (let [short-code (sync/get-short-code @connected-host-id)]
                             (str "Connected to " short-code)))
              :waiting (let [peer-id (sync/get-peer-id)
                             short-code (sync/get-short-code peer-id)]
                         (str "Waiting · " short-code))
              "")))))

(defn- on-clients-change
  "Called when number of connected clients changes."
  [_count]
  (update-sync-status-text))

(defn- update-sync-status-ui
  "Update the sync buttons UI based on status."
  [status]
  (let [share-btn (.getElementById js/document "btn-share")
        link-btn (.getElementById js/document "btn-link")]
    (case status
      :disconnected (do
                      (when share-btn
                        (set! (.-textContent share-btn) "Share")
                        (.remove (.-classList share-btn) "active" "waiting"))
                      (when link-btn
                        (set! (.-textContent link-btn) "Link")
                        (.remove (.-classList link-btn) "active" "waiting"))
                      (update-sync-status-text))
      :waiting      (do
                      (when share-btn
                        (set! (.-textContent share-btn) "Waiting...")
                        (.add (.-classList share-btn) "waiting"))
                      (update-sync-status-text))
      :connecting   (when link-btn
                      (set! (.-textContent link-btn) "Connecting...")
                      (.add (.-classList link-btn) "waiting"))
      :connected    (do
                      ;; Close share modal if open
                      (when-let [modal @share-modal-el]
                        (reset! share-modal-el nil)
                        (.remove modal))
                      (when share-btn
                        (set! (.-textContent share-btn) "Synced")
                        (.add (.-classList share-btn) "active")
                        (.remove (.-classList share-btn) "waiting"))
                      (when link-btn
                        (set! (.-textContent link-btn) "Synced")
                        (.add (.-classList link-btn) "active")
                        (.remove (.-classList link-btn) "waiting"))
                      (update-sync-status-text))
      :error        (do
                      (when share-btn
                        (set! (.-textContent share-btn) "Error")
                        (.remove (.-classList share-btn) "active" "waiting"))
                      (when link-btn
                        (set! (.-textContent link-btn) "Error")
                        (.remove (.-classList link-btn) "active" "waiting")))
      nil)))

(defn- show-share-modal
  "Show modal with session code for sharing."
  [peer-id]
  (let [short-code (sync/get-short-code peer-id)
        modal (.createElement js/document "div")
        overlay (.createElement js/document "div")]
    ;; Store reference so we can close it on connect
    (reset! share-modal-el modal)
    ;; Setup overlay
    (set! (.-className overlay) "sync-modal-overlay")
    (.addEventListener overlay "click" (fn []
                                         (reset! share-modal-el nil)
                                         (.remove modal)))
    ;; Setup modal
    (set! (.-className modal) "sync-modal")
    (set! (.-innerHTML modal)
          (str "<div class='sync-modal-content'>"
               "<h3>Share Session</h3>"
               "<p>Enter this code on the other device:</p>"
               "<div class='sync-code'>" short-code "</div>"
               "<p class='sync-status'>Waiting for connection...</p>"
               "<button class='sync-close-btn'>Close</button>"
               "</div>"))
    (.appendChild modal overlay)
    (.appendChild js/document.body modal)
    ;; Close button
    (when-let [close-btn (.querySelector modal ".sync-close-btn")]
      (.addEventListener close-btn "click" (fn []
                                             (reset! share-modal-el nil)
                                             (.remove modal))))))

(defn- show-link-modal
  "Show modal with input field to enter session code."
  []
  (let [modal (.createElement js/document "div")
        overlay (.createElement js/document "div")]
    ;; Setup overlay
    (set! (.-className overlay) "sync-modal-overlay")
    (.addEventListener overlay "click" #(.remove modal))
    ;; Setup modal
    (set! (.-className modal) "sync-modal")
    (set! (.-innerHTML modal)
          (str "<div class='sync-modal-content'>"
               "<h3>Join Session</h3>"
               "<p>Enter the code shown on the other device:</p>"
               "<input type='text' class='sync-code-input' placeholder='ABC123' maxlength='6' autocapitalize='characters'>"
               "<p class='sync-error' style='display:none; color:#e74c3c;'></p>"
               "<div class='sync-buttons'>"
               "<button class='sync-connect-btn'>Connect</button>"
               "<button class='sync-close-btn'>Cancel</button>"
               "</div>"
               "</div>"))
    (.appendChild modal overlay)
    (.appendChild js/document.body modal)
    ;; Focus input
    (when-let [input (.querySelector modal ".sync-code-input")]
      (.focus input)
      ;; Connect on Enter
      (.addEventListener input "keydown"
                         (fn [e]
                           (when (= "Enter" (.-key e))
                             (.click (.querySelector modal ".sync-connect-btn"))))))
    ;; Connect button
    (when-let [connect-btn (.querySelector modal ".sync-connect-btn")]
      (.addEventListener connect-btn "click"
                         (fn []
                           (let [input (.querySelector modal ".sync-code-input")
                                 code (.-value input)]
                             (if (>= (count code) 4)
                               (let [peer-id (sync/peer-id-from-code code)]
                                 (.remove modal)
                                 (join-session peer-id))
                               (when-let [error (.querySelector modal ".sync-error")]
                                 (set! (.-textContent error) "Code must be at least 4 characters")
                                 (set! (.-style.display error) "block")))))))
    ;; Close button
    (when-let [close-btn (.querySelector modal ".sync-close-btn")]
      (.addEventListener close-btn "click" #(.remove modal)))))

(defn- on-client-connected
  "Called when a new client connects - send them the current script."
  [conn]
  (when-let [content (cm/get-value @editor-view)]
    (sync/send-script-to-connection conn content)))

(defn- start-hosting
  "Start hosting a sync session."
  []
  (let [peer-id (sync/host-session
                 :on-script-received on-script-received
                 :on-status-change update-sync-status-ui
                 :on-clients-change on-clients-change
                 :on-client-connected on-client-connected)]
    (reset! sync-mode :host)
    (show-share-modal peer-id)))

(defn- join-session
  "Join an existing sync session."
  [peer-id]
  (reset! sync-mode :client)
  (reset! connected-host-id peer-id)  ; Save host's peer-id for status display
  (sync/join-session peer-id
                     :on-script-received on-script-received
                     :on-status-change update-sync-status-ui))

(defn- setup-sync
  "Setup sync buttons and auto-join if URL has peer parameter."
  []
  ;; Share button - start hosting
  (when-let [share-btn (.getElementById js/document "btn-share")]
    (.addEventListener share-btn "click"
                       (fn [_]
                         (if (or (sync/hosting?) (sync/connected?))
                           (do
                             (sync/stop-hosting)
                             (reset! sync-mode nil)
                             (update-sync-status-ui :disconnected))
                           (start-hosting)))))
  ;; Link button - join session
  (when-let [link-btn (.getElementById js/document "btn-link")]
    (.addEventListener link-btn "click"
                       (fn [_]
                         (if (sync/connected?)
                           (do
                             (sync/leave-session)
                             (reset! sync-mode nil)
                             (update-sync-status-ui :disconnected))
                           (show-link-modal)))))
  ;; Auto-join if URL has peer parameter
  (when-let [peer-id (sync/get-peer-from-url)]
    (js/console.log "Auto-joining sync session:" peer-id)
    (sync/clear-peer-from-url)
    (join-session peer-id)))

;; ============================================================
;; Initialization
;; ============================================================

(def ^:private default-code "; Define reusable shapes here
(def sq (path (dotimes [_ 4] (f 20) (th 90))))
(extrude-closed (circle 5) sq)

; Run with Cmd+Enter, then use REPL below")

(defn init []
  (let [canvas (.getElementById js/document "viewport")
        editor-container (.getElementById js/document "editor-explicit")
        repl-input (.getElementById js/document "repl-input")
        repl-history (.getElementById js/document "repl-history")
        error-panel (.getElementById js/document "error-panel")
        initial-content (or (load-from-storage) default-code)]
    ;; Create CodeMirror editor
    (reset! editor-view
      (cm/create-editor
        {:parent editor-container
         :initial-value initial-content
         :on-change (fn []
                      (save-to-storage)
                      (send-script-debounced))
         :on-run evaluate-definitions}))
    (reset! repl-input-el repl-input)
    (reset! repl-history-el repl-history)
    (reset! error-el error-panel)
    (viewport/init canvas)
    ;; Register XR panel callbacks (avoid circular dependency)
    (xr/register-action-callback! :toggle-all-obj
      (fn [show-all?]
        (if show-all?
          (do (registry/show-all!)
              (registry/refresh-viewport! false))
          (do (registry/show-only-registered!)
              (registry/refresh-viewport! false)))))
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
    ;; Initialize default font for text shapes (async)
    (-> (text/init-default-font!)
        (.then #(js/console.log "Default font loaded"))
        (.catch #(js/console.warn "Default font failed to load:" %)))
    ;; Setup sync (desktop <-> headset)
    (setup-sync)
    ;; Focus REPL input
    (when repl-input
      (.focus repl-input))
    (js/console.log "Ridley initialized. Cmd+Enter for definitions, Enter in REPL.")))

(defn reload []
  ;; Hot reload callback - re-evaluate definitions
  (evaluate-definitions))
