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
            [ridley.sync.peer :as sync]
            [ridley.manual.core :as manual]
            [ridley.manual.components :as manual-ui]
            [ridley.voice.core :as voice]
            [ridley.voice.state :as voice-state]
            [ridley.voice.i18n :as voice-i18n]))

(defonce ^:private editor-view (atom nil))
(defonce ^:private repl-input-el (atom nil))
(defonce ^:private repl-history-el (atom nil))
(defonce ^:private error-el (atom nil))

;; Command history
(defonce ^:private command-history (atom []))
(defonce ^:private history-index (atom -1))


;; Sync state
(defonce ^:private sync-mode (atom nil))  ; nil, :host, or :client
(defonce ^:private sync-debounce-timer (atom nil))
(defonce ^:private share-modal-el (atom nil))  ; Reference to share modal for closing on connect
(defonce ^:private connected-host-id (atom nil))  ; Host peer-id when we're a client

;; Manual panel state
(defonce ^:private manual-panel (atom nil))

(declare sync-voice-state)

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
  "Add an entry to the REPL history display.
   Optional print-output shows stdout before the result."
  ([input result error?] (add-repl-entry input result error? nil))
  ([input result error? print-output]
   (when-let [history-el @repl-history-el]
     (let [entry (.createElement js/document "div")
           formatted (if error?
                       (escape-html result)
                       (escape-html (format-value result)))
           result-class (cond
                          error? "repl-error"
                          (nil? result) "repl-nil"
                          :else "repl-result")
           print-html (when print-output
                        (str "<div class=\"repl-print-output\">"
                             (escape-html print-output)
                             "</div>"))]
       (.add (.-classList entry) "repl-entry")
       (set! (.-innerHTML entry)
             (str "<div class=\"repl-input-echo\">"
                  "<span class=\"repl-prompt\">&gt; </span>"
                  (escape-html input)
                  "</div>"
                  (or print-html "")
                  "<div class=\"" result-class "\">"
                  formatted
                  "</div>"))
       (.appendChild history-el entry)
       ;; Scroll to bottom
       (set! (.-scrollTop history-el) (.-scrollHeight history-el))))))

(defn- add-script-output
  "Add script output (from definitions/manual) to the REPL history.
   Shows only print output without input/result."
  [print-output]
  (when-let [history-el @repl-history-el]
    (let [entry (.createElement js/document "div")]
      (.add (.-classList entry) "repl-entry")
      (set! (.-innerHTML entry)
            (str "<div class=\"repl-print-output\">"
                 (escape-html print-output)
                 "</div>"))
      (.appendChild history-el entry)
      ;; Scroll to bottom
      (set! (.-scrollTop history-el) (.-scrollHeight history-el)))))

(defn- update-turtle-indicator
  "Update the turtle indicator with current pose from REPL."
  []
  (viewport/update-turtle-pose (repl/get-turtle-pose)))

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
         ;; Show print output in REPL history if any
         (when-let [print-output (:print-output result)]
           (add-script-output print-output))
         (when-let [render-data (repl/extract-render-data result)]
           ;; Store lines and definition meshes
           (registry/set-lines! (:lines render-data))
           (registry/set-definition-meshes! (:meshes render-data)))
         ;; Refresh viewport, optionally resetting camera
         (registry/refresh-viewport! reset-camera?)
         ;; Update turtle indicator
         (update-turtle-indicator)
         ;; Sync AI state
         (sync-voice-state))))))

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
        ;; Send to connected clients if we're the host
        (when (= :host @sync-mode)
          (sync/send-repl-command input))
        ;; Evaluate REPL input only (definitions already in context)
        (let [result (repl/evaluate-repl input)]
          (if-let [error (:error result)]
            (do
              (add-repl-entry input error true)
              (show-error error))
            (do
              (hide-error)
              ;; Show result in terminal history (with any print output)
              (add-repl-entry input (:implicit-result result) false (:print-output result))
              ;; Extract lines and meshes from REPL evaluation
              (when-let [render-data (repl/extract-render-data result)]
                (registry/add-lines! (:lines render-data))
                (registry/set-definition-meshes! (:meshes render-data)))
              ;; Update viewport (don't reset camera)
              (registry/refresh-viewport! false)
              ;; Update turtle indicator
              (update-turtle-indicator)
              ;; Sync AI state
              (sync-voice-state))))))))

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
                  (set! (.-minWidth (.-style editor-panel)) (str new-width "px")))))))
                  ;; ResizeObserver handles viewport resize automatically
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

(defn- setup-save-load []
  (let [run-btn (.getElementById js/document "btn-run")
        save-btn (.getElementById js/document "btn-save")
        load-btn (.getElementById js/document "btn-load")
        export-stl-btn (.getElementById js/document "btn-export-stl")
        toggle-grid-btn (.getElementById js/document "btn-toggle-grid")
        toggle-axes-btn (.getElementById js/document "btn-toggle-axes")
        toggle-turtle-btn (.getElementById js/document "btn-toggle-turtle")
        toggle-lines-btn (.getElementById js/document "btn-toggle-lines")
        toggle-normals-btn (.getElementById js/document "btn-toggle-normals")
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
    ;; Export STL button
    (when export-stl-btn
      (.addEventListener export-stl-btn "click"
        (fn [_] (export-stl))))
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
    ;; Toggle turtle indicator button
    (when toggle-turtle-btn
      ;; Set initial active state (turtle visible by default)
      (.add (.-classList toggle-turtle-btn) "active")
      (.addEventListener toggle-turtle-btn "click"
        (fn [_]
          (let [visible (viewport/toggle-turtle)]
            (if visible
              (.add (.-classList toggle-turtle-btn) "active")
              (.remove (.-classList toggle-turtle-btn) "active"))))))
    ;; Toggle construction lines button
    (when toggle-lines-btn
      ;; Set initial active state (lines visible by default)
      (.add (.-classList toggle-lines-btn) "active")
      (.addEventListener toggle-lines-btn "click"
        (fn [_]
          (let [visible (viewport/toggle-lines)]
            (if visible
              (.add (.-classList toggle-lines-btn) "active")
              (.remove (.-classList toggle-lines-btn) "active"))))))
    ;; Toggle face normals button
    (when toggle-normals-btn
      ;; Normals off by default (no active class initially)
      (.addEventListener toggle-normals-btn "click"
        (fn [_]
          (let [visible (viewport/toggle-normals)]
            (if visible
              (.add (.-classList toggle-normals-btn) "active")
              (.remove (.-classList toggle-normals-btn) "active"))))))
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

(defn- on-repl-received
  "Called when we receive a REPL command from host. Execute it and show in history."
  [command]
  (when (seq (str/trim command))
    (let [result (repl/evaluate-repl command)]
      (if-let [error (:error result)]
        (do
          (add-repl-entry command error true)
          (show-error error))
        (do
          (hide-error)
          (add-repl-entry command (:implicit-result result) false (:print-output result))
          (when-let [render-data (repl/extract-render-data result)]
            (registry/add-lines! (:lines render-data))
            (registry/set-definition-meshes! (:meshes render-data)))
          (registry/refresh-viewport! false))))))

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
        overlay (.createElement js/document "div")
        close-modal (fn []
                      (reset! share-modal-el nil)
                      (.remove overlay)
                      (.remove modal))]
    ;; Store reference so we can close it on connect
    (reset! share-modal-el modal)
    ;; Setup overlay (sibling of modal, not child)
    (set! (.-className overlay) "sync-modal-overlay")
    (.addEventListener overlay "click" close-modal)
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
    (.appendChild js/document.body overlay)
    (.appendChild js/document.body modal)
    ;; Close button
    (when-let [close-btn (.querySelector modal ".sync-close-btn")]
      (.addEventListener close-btn "click" close-modal))))

(defn- show-link-modal
  "Show modal with input field to enter session code."
  []
  (let [modal (.createElement js/document "div")
        overlay (.createElement js/document "div")
        close-modal (fn [] (.remove overlay) (.remove modal))]
    ;; Setup overlay (sibling of modal, not child)
    (set! (.-className overlay) "sync-modal-overlay")
    (.addEventListener overlay "click" close-modal)
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
    (.appendChild js/document.body overlay)
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
                                 (close-modal)
                                 (join-session peer-id))
                               (when-let [error (.querySelector modal ".sync-error")]
                                 (set! (.-textContent error) "Code must be at least 4 characters")
                                 (set! (.-style.display error) "block")))))))
    ;; Close button
    (when-let [close-btn (.querySelector modal ".sync-close-btn")]
      (.addEventListener close-btn "click" close-modal))))

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
                     :on-repl-received on-repl-received
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
;; Manual
;; ============================================================

(defn- update-manual-visibility
  "Show or hide the manual panel based on manual state."
  []
  (let [editor-section (.getElementById js/document "explicit-section")
        repl-section (.getElementById js/document "repl-section")
        section-divider (.querySelector js/document ".section-divider")
        manual-container (.getElementById js/document "manual-container")]
    (if (manual/open?)
      ;; Show manual, hide editor
      (do
        (when editor-section (set! (.-style.display editor-section) "none"))
        (when repl-section (set! (.-style.display repl-section) "none"))
        (when section-divider (set! (.-style.display section-divider) "none"))
        (when manual-container
          (set! (.-style.display manual-container) "flex")
          ;; Render manual content
          (when-let [panel @manual-panel]
            ((:render panel)))))
      ;; Hide manual, show editor
      (do
        (when editor-section (set! (.-style.display editor-section) "flex"))
        (when repl-section (set! (.-style.display repl-section) "flex"))
        (when section-divider (set! (.-style.display section-divider) "block"))
        (when manual-container (set! (.-style.display manual-container) "none"))))))

(defn- run-manual-code
  "Execute code from the manual and show result in viewport."
  [code]
  ;; Clear previous geometry and evaluate fresh
  (registry/clear-all!)
  (let [result (repl/evaluate-definitions code)]
    (if-let [error (:error result)]
      (show-error error)
      (do
        (hide-error)
        ;; Show print output in REPL history if any
        (when-let [print-output (:print-output result)]
          (add-script-output print-output))
        (when-let [render-data (repl/extract-render-data result)]
          (registry/set-lines! (:lines render-data))
          (registry/set-definition-meshes! (:meshes render-data)))
        (registry/refresh-viewport! true)  ; Reset camera to show result
        (update-turtle-indicator)))))

(defn- copy-manual-code
  "Copy code from manual to editor and close manual."
  [code]
  (when @editor-view
    ;; Replace editor content with example code
    (cm/set-value @editor-view code)
    (save-to-storage)
    (manual/close-manual!)))

(defn- setup-manual
  "Setup the manual panel and button."
  []
  ;; Create manual container in editor panel
  (let [editor-panel (.getElementById js/document "editor-panel")
        container (.createElement js/document "div")]
    (set! (.-id container) "manual-container")
    (set! (.-className container) "manual-container")
    (set! (.-style.display container) "none")
    (.appendChild editor-panel container)
    ;; Create manual panel
    (let [panel (manual-ui/create-manual-panel)]
      (reset! manual-panel panel)
      (.appendChild container (:element panel))))
  ;; Set callbacks for Run/Copy buttons
  (manual-ui/set-callbacks!
   {:on-run run-manual-code
    :on-copy copy-manual-code})
  ;; Watch manual state for changes
  (manual/add-state-watcher! :ui-update
    (fn [_ _] (update-manual-visibility)))
  ;; Setup Manual button
  (when-let [manual-btn (.getElementById js/document "btn-manual")]
    (.addEventListener manual-btn "click"
      (fn [_] (manual/toggle-manual!)))))

;; ============================================================
;; Voice Input Integration
;; ============================================================

(defn- ai-insert-code
  "Insert code into editor or REPL based on target and position."
  [{:keys [target code position]}]
  (case target
    :script
    (when-let [view @editor-view]
      (let [insert-pos (case position
                         :cursor (.. view -state -selection -main -head)
                         :end (.. view -state -doc -length)
                         :after-current-form
                         (if-let [form (cm/get-form-at-cursor view)]
                           (:to form)
                           (.. view -state -selection -main -head))
                         :before-current-form
                         (if-let [form (cm/get-form-at-cursor view)]
                           (:from form)
                           (.. view -state -selection -main -head))
                         :append-child
                         (if-let [form (cm/get-form-at-cursor view)]
                           (dec (:to form))  ; before closing bracket
                           (.. view -state -selection -main -head))
                         ;; Default: at cursor
                         (.. view -state -selection -main -head))
            ;; Add whitespace for positional insertion
            actual-code (case position
                          :after-current-form (str "\n" code)
                          :before-current-form (str code "\n")
                          :append-child (str " " code)
                          code)
            code-length (count actual-code)
            start-pos (case position
                        :after-current-form (inc insert-pos) ; skip the leading newline
                        :append-child (inc insert-pos)       ; skip the leading space
                        insert-pos)
            end-pos (case position
                      :before-current-form (+ insert-pos (count code)) ; exclude trailing newline
                      (+ insert-pos code-length))]
        ;; Insert the code
        (case position
          :cursor (cm/insert-at-cursor view actual-code)
          :end (cm/insert-at-end view actual-code)
          (:after-current-form :before-current-form :append-child)
          (do
            (cm/set-cursor-position view {:pos insert-pos})
            (cm/insert-at-cursor view actual-code))
          ;; Default
          (cm/insert-at-cursor view actual-code))
        ;; Position cursor on inserted code
        (case position
          ;; For form-related positions, place cursor AT the new form
          (:after-current-form :before-current-form :append-child)
          (cm/set-cursor-position view {:pos start-pos})
          ;; For other positions, select the range
          (cm/select-range view start-pos end-pos))
        ;; Update AI focus to the inserted form
        (cm/update-ai-focus! view)
        ;; Keep focus on editor
        (cm/focus view))
      ;; Auto-save
      (save-to-storage)
      (send-script-debounced))

    :repl
    (when-let [input-el @repl-input-el]
      (case position
        :cursor (let [start (.-selectionStart input-el)
                      value (.-value input-el)]
                  (set! (.-value input-el)
                        (str (subs value 0 start) code (subs value start))))
        :end (set! (.-value input-el)
                   (str (.-value input-el) code))
        ;; Default: replace all
        (set! (.-value input-el) code)))

    (js/console.warn "AI insert: unknown target" target)))

(defn- ai-edit-code
  "Edit code based on operation and target."
  [{:keys [operation target value element transform-type]}]
  (when-let [view @editor-view]
    (let [;; Check if target references "it/lo/last" - meaning previous form
          ref (:ref target)
          use-previous? (and (= (:type target) "form")
                             (contains? #{"it" "lo" "last" "questo" "this"} ref))
          ;; Get the appropriate form based on target type
          the-form (if use-previous?
                     (cm/get-previous-form view)
                     (case (:type target)
                       "form" (cm/get-element-at-cursor view)
                       "word" (cm/get-word-at-cursor view)
                       "selection" (cm/get-selection view)
                       nil))]

      (when the-form
        (case operation
          :replace
          (cm/replace-range view (:from the-form) (:to the-form) value)

          :delete
          (let [;; Find parent form BEFORE deleting so we can position cursor there
                parent-pos (let [from (:from the-form)]
                             (when (pos? from)
                               ;; Temporarily move cursor before the form to find parent
                               (cm/set-cursor-position view {:pos (max 0 (dec from))})
                               (when-let [parent (cm/get-form-at-cursor view)]
                                 (inc (:from parent)))))]
            (cm/delete-range view (:from the-form) (:to the-form))
            ;; Position cursor in parent form
            (when parent-pos
              (cm/set-cursor-position view {:pos (min parent-pos
                                                      (.. view -state -doc -length))})))

          :wrap
          (when value
            (let [wrapped (str/replace value "$" (:text the-form))]
              (cm/replace-range view (:from the-form) (:to the-form) wrapped)))

          :unwrap
          (let [text (:text the-form)
                first-ch (when (seq text) (.charAt text 0))
                last-ch (when (seq text) (.charAt text (dec (count text))))]
            (when (and first-ch last-ch
                       (#{\( \[ \{} first-ch)
                       (#{\) \] \}} last-ch))
              (let [inner (subs text 1 (dec (count text)))
                    ;; For () forms, strip the head (fn name); for [] and {}, keep all content
                    content (if (= first-ch \()
                              (str/trim (str/replace-first inner #"^\S+\s*" ""))
                              (str/trim inner))]
                (cm/replace-range view (:from the-form) (:to the-form) content))))

          :replace-structured
          (when (and element value)
            (when-let [new-form (cm/replace-form-element (:text the-form) element value)]
              (cm/replace-range view (:from the-form) (:to the-form) new-form)))

          :insert-structured
          (when (and element value)
            (when-let [new-form (cm/insert-form-element (:text the-form) element value)]
              (cm/replace-range view (:from the-form) (:to the-form) new-form)))

          :delete-structured
          (when element
            (when-let [new-form (cm/delete-form-element (:text the-form) element)]
              (cm/replace-range view (:from the-form) (:to the-form) new-form)))

          :barf
          (cm/barf-form view)

          :slurp
          (cm/slurp-form view)

          :raise
          (let [form-text (:text the-form)
                form-from (:from the-form)]
            ;; Find the parent form
            (cm/set-cursor-position view {:pos (max 0 (dec form-from))})
            (when-let [parent (cm/get-form-at-cursor view)]
              (cm/replace-range view (:from parent) (:to parent) form-text)
              ;; Position cursor at the raised form
              (cm/set-cursor-position view {:pos (:from parent)})))

          :join
          ;; Merge current atom with next sibling (e.g. "do" + "times" → "dotimes")
          (when-let [next-form (cm/get-next-form view)]
            (let [joined (str (:text the-form) (:text next-form))]
              (cm/replace-range view (:from the-form) (:to next-form) joined)
              (cm/set-cursor-position view {:pos (:from the-form)})))

          :transform
          (let [text (:text the-form)
                lang (voice-state/get-language)
                new-text
                (case transform-type
                  :keyword   (str ":" text)
                  :symbol    (when (str/starts-with? text ":")
                               (subs text 1))
                  :hash      (str "#" text)
                  :deref     (str "@" text)
                  :capitalize (when (seq text)
                                (str (str/upper-case (subs text 0 1)) (subs text 1)))
                  :uppercase (str/upper-case text)
                  :number    nil  ; handled specially below
                  nil)]
            (if (= transform-type :number)
              ;; Number transform: word→digit, or "minus"+"N"→"-N"
              (let [neg-words (get voice-i18n/negative-words lang #{})
                    nums (get voice-i18n/numbers lang {})
                    lower (str/lower-case text)]
                (if (contains? neg-words lower)
                  ;; Current atom is "minus"/"meno" — negate next sibling
                  (when-let [next-form (cm/get-next-form view)]
                    (let [next-text (:text next-form)
                          n (or (get nums (str/lower-case next-text))
                                (when (re-matches #"\d+" next-text)
                                  (js/parseInt next-text 10)))]
                      (when n
                        (cm/replace-range view (:from the-form) (:to next-form) (str "-" n))
                        (cm/set-cursor-position view {:pos (:from the-form)}))))
                  ;; Try word→digit conversion
                  (when-let [n (get nums lower)]
                    (cm/replace-range view (:from the-form) (:to the-form) (str n))
                    (cm/set-cursor-position view {:pos (:from the-form)}))))
              ;; All other transforms: simple text replacement
              (when new-text
                (cm/replace-range view (:from the-form) (:to the-form) new-text)
                (cm/set-cursor-position view {:pos (:from the-form)}))))

          (js/console.warn "AI edit: unknown operation" operation)))

      (when-not the-form
        (js/console.warn "AI edit: no form found" (if use-previous? "(looking for previous)" ""))))

    ;; Update AI focus after edit
    (cm/update-ai-focus! view)

    ;; Auto-save after edit
    (save-to-storage)
    (send-script-debounced)))

(defn- ai-navigate
  "Navigate cursor based on direction and mode."
  [{:keys [direction mode count]}]
  (when-let [view @editor-view]
    (let [n (or count 1)]
      (dotimes [_ n]
        (case mode
          :structure
          (case direction
            :next (let [current (cm/get-element-at-cursor view)
                        next-form (cm/get-next-form view)]
                    (js/console.log "nav :next — current:" (pr-str (:text current))
                                    "next:" (pr-str (:text next-form)))
                    (when next-form
                      (cm/set-cursor-position view {:pos (:from next-form)})))
            :prev (when-let [prev-form (cm/get-previous-form view)]
                    (cm/set-cursor-position view {:pos (:from prev-form)}))
            :parent (when-let [{:keys [from]} (cm/get-form-at-cursor view)]
                      (let [current-pos (.. view -state -selection -main -head)]
                        (if (= current-pos from)
                          ;; Already at opening bracket — go to parent
                          (when (pos? from)
                            (cm/set-cursor-position view {:pos (dec from)})
                            (when-let [parent (cm/get-form-at-cursor view)]
                              (cm/set-cursor-position view {:pos (:from parent)})))
                          ;; Inside the form — go to its opening bracket
                          (cm/set-cursor-position view {:pos from}))))
            :child (when-let [child (cm/get-first-child-form view)]
                     (let [target-pos (:from child)
                           current-pos (.. view -state -selection -main -head)]
                       (if (= target-pos current-pos)
                         ;; Already at first child — jump to next sibling
                         (when-let [nxt (cm/get-next-form view)]
                           (cm/set-cursor-position view {:pos (:from nxt)}))
                         (cm/set-cursor-position view {:pos target-pos}))))
            :start (cm/move-cursor view :start)
            :end (cm/move-cursor view :end)
            nil)

          ;; Default: text mode
          (cm/move-cursor view direction))))
    ;; Update AI focus to reflect new position
    (cm/update-ai-focus! view)
    (sync-voice-state)))

(defn- sync-voice-state
  "Sync current editor state to voice state for context."
  []
  (when (voice-state/enabled?)
    (when-let [view @editor-view]
      ;; Update buffer
      (voice-state/update-buffer! :script (cm/get-value view))
      (when-let [repl-el @repl-input-el]
        (voice-state/update-buffer! :repl (.-value repl-el)))

      ;; Update cursor and form info
      (let [cursor (cm/get-cursor-position view)
            form (cm/get-element-at-cursor view)
            selection (cm/get-selection view)]
        (voice-state/update-cursor! {:line (:line cursor)
                                     :col (:col cursor)
                                     :current-form (:text form)
                                     :selection (when (not= (:from selection) (:to selection))
                                                  (:text selection))}))

      ;; Update scene info
      (voice-state/update-scene! {:meshes (vec (registry/registered-names))
                                  :visible (vec (registry/visible-names))
                                  :shapes (vec (registry/shape-names))
                                  :paths (vec (registry/path-names))}))))

(defn- setup-voice-ui
  "Setup voice button with push-to-talk behavior."
  []
  (when-let [toolbar (.getElementById js/document "viewport-toolbar")]
    (let [mic-btn (.createElement js/document "button")]
      (set! (.-id mic-btn) "btn-voice")
      (set! (.-className mic-btn) "toolbar-button")
      (set! (.-textContent mic-btn) "MIC")
      (set! (.-title mic-btn) "Push to talk — hold or click")

      ;; Track if we're doing push-to-talk (hold) or toggle (click)
      (let [press-start (atom nil)
            hold-threshold 200]

        (.addEventListener mic-btn "mousedown"
          (fn [e]
            (.preventDefault e)
            (.stopPropagation e)
            ;; Ignore left-click mousedown when in continuous mode
            ;; (continuous mode is toggled by right-click only)
            (when-not (voice/continuous-active?)
              (reset! press-start (js/Date.now))
              (voice/start-listening!)
              (.add (.-classList mic-btn) "active")
              (js/setTimeout #(when @editor-view (cm/focus @editor-view)) 50))))

        (.addEventListener mic-btn "mouseup"
          (fn [_]
            (when-let [start @press-start]
              (reset! press-start nil)
              (let [held (- (js/Date.now) start)]
                (when (> held hold-threshold)
                  (voice/stop-listening!)
                  (.remove (.-classList mic-btn) "active")))
              (when @editor-view (cm/focus @editor-view)))))

        (.addEventListener mic-btn "mouseleave"
          (fn [_]
            (when @press-start
              (reset! press-start nil)
              (when (voice/voice-active?)
                (voice/stop-listening!))
              (.remove (.-classList mic-btn) "active")))))

      ;; Right-click = toggle continuous listening mode
      (.addEventListener mic-btn "contextmenu"
        (fn [e]
          (.preventDefault e)
          (.stopPropagation e)
          (if (voice/continuous-active?)
            (voice/stop-continuous-listening!)
            (voice/start-continuous-listening!))
          (when @editor-view (cm/focus @editor-view))))

      ;; Watch state to update button appearance
      (add-watch voice-state/voice-state :voice-button-update
        (fn [_ _ old-state new-state]
          (let [was-listening (get-in old-state [:voice :listening?])
                is-listening (get-in new-state [:voice :listening?])
                is-continuous (get-in new-state [:voice :continuous?])
                pending (get-in new-state [:voice :pending-speech])
                mode (:mode new-state)]
            (when (not= was-listening is-listening)
              (if is-listening
                (.add (.-classList mic-btn) "active")
                (do
                  (.remove (.-classList mic-btn) "active")
                  (.remove (.-classList mic-btn) "continuous"))))
            (if is-continuous
              (.add (.-classList mic-btn) "continuous")
              (.remove (.-classList mic-btn) "continuous"))
            ;; Show mode + status as tooltip
            (when pending
              (set! (.-title mic-btn) pending))
            (when (and (not pending) (not is-listening))
              (set! (.-title mic-btn) (str "Mode: " (name mode) " — push to talk, right-click for continuous"))))))

      (.appendChild toolbar mic-btn)))

  ;; Create voice status panel
  (let [panel-el (.createElement js/document "div")]
    (set! (.-id panel-el) "voice-status-panel")
    (set! (.-className panel-el) "voice-panel")
    ;; Inject panel CSS
    (let [style-el (.createElement js/document "style")]
      (set! (.-textContent style-el) (voice/get-panel-css))
      (.appendChild js/document.head style-el))
    (.appendChild js/document.body panel-el)

    ;; Update panel content when state changes
    (add-watch voice-state/voice-state :voice-panel-update
      (fn [_ _ _ _new-state]
        (if (voice-state/enabled?)
          (do
            (set! (.-style.display panel-el) "block")
            (set! (.-innerHTML panel-el) (voice/render-panel-html)))
          (set! (.-style.display panel-el) "none"))))))

;; ============================================================
;; Initialization
;; ============================================================

(def ^:private default-code "; Run with Cmd+Enter, then use REPL below
(register smooth-spline
  (extrude (circle 5)
    (bezier-as
      (path (f 30) (th 90) (f 20) (tr -80) (th -45) (f 25))
      :cubic true)))")

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
                      (send-script-debounced)
                      (sync-voice-state))
         :on-run evaluate-definitions
         :on-selection-change (fn []
                                (cm/update-ai-focus!)
                                (sync-voice-state))}))
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
    ;; Setup manual panel
    (setup-manual)
    ;; Initialize voice input system
    (voice/init! {:insert ai-insert-code
                  :edit ai-edit-code
                  :navigate ai-navigate
                  :execute (fn [{:keys [target]}]
                             (case target
                               :script (evaluate-definitions)
                               :repl (evaluate-repl-input)
                               (js/console.warn "Voice execute: unknown target" target)))
                  :undo (fn [{:keys [operation count]}]
                          (when-let [view @editor-view]
                            (dotimes [_ (or count 1)]
                              (case operation
                                :undo (cm/editor-undo! view)
                                :redo (cm/editor-redo! view)
                                nil))
                            (cm/update-ai-focus! view)
                            (save-to-storage)
                            (send-script-debounced)))})
    (setup-voice-ui)
    ;; Focus REPL input
    (when repl-input
      (.focus repl-input))
    (js/console.log "Ridley initialized. Cmd+Enter for definitions, Enter in REPL.")))

(defn reload []
  ;; Hot reload callback - re-evaluate definitions
  (evaluate-definitions))
