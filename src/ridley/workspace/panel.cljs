(ns ridley.workspace.panel
  "Workspaces panel UI — a section listing the open editor documents.

   Distinct from the library panel (ridley.library.panel): this lists the
   scripts you edit/run, not dependencies. Reuses the same CSS classes for a
   consistent look. Renders into the `#workspace-panel` container.

   Callbacks (set by core.cljs via `setup!`):
   - :switch-to  fn [id]  -> persist current doc, load `id` into the editor
   - :close      fn [id]  -> close workspace `id` (handles current-doc case)"
  (:require [clojure.string :as str]
            [ridley.workspace.store :as ws]))

;; ============================================================
;; State / callbacks
;; ============================================================

(defonce ^:private callbacks (atom nil))
(defonce ^:private panel-state (atom {:collapsed false}))

(declare render!)

;; ============================================================
;; DOM helpers (mirror library.panel for a consistent look)
;; ============================================================

(defn- el [tag] (.createElement js/document tag))

(defn- el-with [tag class-name text]
  (let [e (el tag)]
    (when class-name (set! (.-className e) class-name))
    (when text (set! (.-textContent e) text))
    e))

(defn- append! [parent & children]
  (doseq [c children] (when c (.appendChild parent c))))

(defn- remove-children! [parent]
  (set! (.-innerHTML parent) ""))

(defn- basename [path]
  (when path (last (str/split path #"[\\/]"))))

;; ============================================================
;; Modal dialogs (WKWebView blocks prompt/confirm) — same CSS as library panel
;; ============================================================

(defn- show-modal! [content-fn]
  (let [overlay (el-with "div" "lib-modal-overlay" nil)
        dialog (el-with "div" "lib-modal-dialog" nil)]
    (.appendChild overlay dialog)
    (content-fn dialog overlay)
    (.appendChild js/document.body overlay)
    (when-let [input (.querySelector dialog "input")] (.focus input))
    overlay))

(defn- modal-prompt! [message initial on-result]
  (let [overlay (atom nil)]
    (reset! overlay
            (show-modal!
             (fn [dialog _ov]
               (let [label (el-with "div" "lib-modal-label" message)
                     input (el "input")
                     btn-row (el-with "div" "lib-modal-buttons" nil)
                     ok-btn (el-with "button" "lib-modal-btn lib-modal-ok" "OK")
                     cancel-btn (el-with "button" "lib-modal-btn lib-modal-cancel" "Cancel")
                     submit! (fn []
                               (let [val (str/trim (.-value input))]
                                 (.remove @overlay)
                                 (on-result (when (seq val) val))))
                     cancel! (fn [] (.remove @overlay) (on-result nil))]
                 (set! (.-type input) "text")
                 (set! (.-className input) "lib-modal-input")
                 (set! (.-value input) (or initial ""))
                 (.addEventListener ok-btn "click" (fn [_] (submit!)))
                 (.addEventListener cancel-btn "click" (fn [_] (cancel!)))
                 (.addEventListener input "keydown"
                                    (fn [e] (case (.-key e)
                                              "Enter" (submit!)
                                              "Escape" (cancel!)
                                              nil)))
                 (append! btn-row ok-btn cancel-btn)
                 (append! dialog label input btn-row)
                 (js/setTimeout #(.select input) 0)))))))

(defn- modal-confirm! [message on-result]
  (show-modal!
   (fn [dialog overlay]
     (let [label (el-with "div" "lib-modal-label" message)
           btn-row (el-with "div" "lib-modal-buttons" nil)
           ok-btn (el-with "button" "lib-modal-btn lib-modal-ok" "OK")
           cancel-btn (el-with "button" "lib-modal-btn lib-modal-cancel" "Cancel")]
       (.addEventListener ok-btn "click" (fn [_] (.remove overlay) (on-result true)))
       (.addEventListener cancel-btn "click" (fn [_] (.remove overlay) (on-result false)))
       (append! btn-row ok-btn cancel-btn)
       (append! dialog label btn-row)))))

;; ============================================================
;; Actions
;; ============================================================

(defn- switch-to! [id]
  (when-let [cb (:switch-to @callbacks)] (cb id)))

(defn- new-workspace! []
  (let [id (ws/new-workspace! "")]
    (switch-to! id)))

(defn- duplicate! [id]
  (when-let [new-id (ws/duplicate! id)]
    (switch-to! new-id)))

(defn- rename! [id]
  (modal-prompt! "Workspace name:" (:name (ws/get-workspace id))
                 (fn [name] (when name (ws/rename! id name) (render!)))))

(defn- close! [id]
  (let [do-close (fn [] (when-let [cb (:close @callbacks)] (cb id)))]
    (if (ws/dirty? id)
      (modal-confirm! "This workspace has unsaved changes. Close anyway?"
                      (fn [yes?] (when yes? (do-close))))
      (do-close))))

;; ============================================================
;; Context menu
;; ============================================================

(defn- close-context-menu! []
  (when-let [m (.querySelector js/document ".library-context-menu")] (.remove m)))

(defn- show-context-menu! [id x y]
  (close-context-menu!)
  (let [menu (el-with "div" "library-context-menu" nil)
        item (fn [label class-name on-click]
               (let [btn (el-with "button" (str "library-context-menu-item"
                                                (when class-name (str " " class-name)))
                                  label)]
                 (.addEventListener btn "click"
                                    (fn [_] (close-context-menu!) (on-click)))
                 btn))]
    (set! (.-style.left menu) (str x "px"))
    (set! (.-style.top menu) (str y "px"))
    (append! menu
             (item "Rename" nil #(rename! id))
             (item "Duplicate" nil #(duplicate! id))
             (item "Close" "danger" #(close! id)))
    (.appendChild js/document.body menu)
    (let [close-fn (atom nil)]
      (reset! close-fn (fn [e]
                         (when-not (.contains menu (.-target e))
                           (.remove menu)
                           (.removeEventListener js/document "click" @close-fn))))
      (js/setTimeout #(.addEventListener js/document "click" @close-fn) 0))))

;; ============================================================
;; Render
;; ============================================================

(defn- render-row [list-el w current-id]
  (let [id (:id w)
        current? (= id current-id)
        file-path (:file-path w)
        item (el-with "div" (str "library-item workspace-item"
                                 (when current? " ws-current")) nil)
        name-el (el-with "span" "library-name" (:name w))
        file-el (when file-path (el-with "span" "ws-file-name" (basename file-path)))
        dot (el-with "span" "ws-dirty-dot" nil)
        menu-btn (el-with "button" "library-menu-btn" "⋮")]
    ;; Dirty dot (only meaningful for file-bound workspaces)
    (.setAttribute dot "data-ws-dot" id)
    (set! (.-title dot) "Unsaved changes")
    (set! (.-style.display dot) (if (ws/dirty? w) "inline-block" "none"))
    ;; Click row = switch (ignore clicks on the menu button)
    (.addEventListener item "click"
                       (fn [e]
                         (when-not (.closest (.-target e) ".library-menu-btn")
                           (switch-to! id))))
    ;; Double-click name = rename
    (.addEventListener name-el "dblclick"
                       (fn [e] (.stopPropagation e) (rename! id)))
    (.addEventListener menu-btn "click"
                       (fn [e]
                         (.stopPropagation e)
                         (let [rect (.getBoundingClientRect menu-btn)]
                           (show-context-menu! id (.-right rect) (.-bottom rect)))))
    (append! item name-el file-el dot menu-btn)
    (.appendChild list-el item)))

(defn render! []
  (when-let [panel-el (.getElementById js/document "workspace-panel")]
    (remove-children! panel-el)
    (if (:collapsed @panel-state)
      (.add (.-classList panel-el) "collapsed")
      (.remove (.-classList panel-el) "collapsed"))
    ;; Header
    (let [header (el-with "div" "section-header" nil)
          title-area (el-with "div" nil nil)
          collapse-icon (el-with "span" "library-collapse-icon" "▼")
          title-text (el-with "span" "section-title" "Workspaces")
          actions (el-with "div" "section-actions" nil)
          add-btn (el-with "button" "action-btn" "+")]
      (set! (.-title add-btn) "New workspace")
      (set! (.-style.display title-area) "flex")
      (set! (.-style.alignItems title-area) "center")
      (set! (.-style.gap title-area) "6px")
      (append! title-area collapse-icon title-text)
      (.addEventListener header "click"
                         (fn [e]
                           (when-not (.closest (.-target e) ".action-btn")
                             (swap! panel-state update :collapsed not)
                             (render!))))
      (.addEventListener add-btn "click"
                         (fn [e] (.stopPropagation e) (new-workspace!)))
      (append! actions add-btn)
      (set! (.-style.display header) "flex")
      (set! (.-style.alignItems header) "center")
      (set! (.-style.justifyContent header) "space-between")
      (append! header title-area actions)
      (.appendChild panel-el header))
    ;; List
    (let [list-el (el-with "div" "library-list" nil)
          all (ws/list-workspaces)
          current-id (ws/current-id)]
      (doseq [w all] (render-row list-el w current-id))
      (.appendChild panel-el list-el))))

(defn on-content-changed!
  "Cheap live update of the current workspace's dirty dot (called on every
   editor change, avoids a full re-render per keystroke)."
  []
  (when-let [panel-el (.getElementById js/document "workspace-panel")]
    (let [cur (ws/current-id)]
      (when-let [dot (.querySelector panel-el (str "[data-ws-dot='" cur "']"))]
        (set! (.-style.display dot)
              (if (ws/dirty? cur) "inline-block" "none"))))))

;; ============================================================
;; Setup
;; ============================================================

(defn setup! [cb-map]
  (reset! callbacks cb-map)
  (render!))
