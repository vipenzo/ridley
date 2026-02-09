(ns ridley.library.panel
  "Library management panel UI.

   Renders a collapsible section in the editor sidebar with:
   - List of libraries with active/inactive checkboxes
   - Dependency status indicators (✓/✗)
   - Context menu (edit, duplicate, export, delete)
   - Drag & drop reordering for active libraries
   - Edit mode: swaps CodeMirror content to library source

   All state lives in localStorage via storage.cljs.
   Panel re-renders from storage on every mutation."
  (:require [clojure.string :as str]
            [ridley.library.storage :as storage]))

;; ============================================================
;; Panel State
;; ============================================================

(defonce ^:private panel-state
  (atom {:collapsed false
         :editing nil        ;; library name being edited, or nil
         :saved-editor ""    ;; editor content saved before entering edit mode
         :context-menu nil})) ;; {:name "lib" :x px :y px} or nil

;; Callbacks provided by core.cljs at setup time
(defonce ^:private callbacks (atom nil))

(declare render!)

;; ============================================================
;; DOM Helpers
;; ============================================================

(defn- el [tag]
  (.createElement js/document tag))

(defn- el-with [tag class-name text]
  (let [e (el tag)]
    (when class-name (set! (.-className e) class-name))
    (when text (set! (.-textContent e) text))
    e))

(defn- append! [parent & children]
  (doseq [c children]
    (when c (.appendChild parent c))))

(defn- remove-children! [parent]
  (set! (.-innerHTML parent) ""))

;; ============================================================
;; Context Menu
;; ============================================================

(defn- close-context-menu! []
  (when-let [menu (.querySelector js/document ".library-context-menu")]
    (.remove menu))
  (swap! panel-state assoc :context-menu nil))

(defn- show-context-menu! [lib-name x y]
  (close-context-menu!)
  (let [menu (el-with "div" "library-context-menu" nil)
        make-item (fn [label class-name on-click]
                    (let [btn (el-with "button" (str "library-context-menu-item"
                                                     (when class-name (str " " class-name)))
                                       label)]
                      (.addEventListener btn "click"
                        (fn [_]
                          (close-context-menu!)
                          (on-click)))
                      btn))
        sep (fn [] (el-with "div" "library-context-menu-sep" nil))]
    (set! (.-style.left menu) (str x "px"))
    (set! (.-style.top menu) (str y "px"))
    (append! menu
      (make-item "Edit" nil
        (fn [] (when-let [cb (:on-edit @callbacks)] (cb lib-name))))
      (make-item "Duplicate" nil
        (fn []
          (when-let [lib (storage/get-library lib-name)]
            (let [new-name (str lib-name "-copy")]
              (storage/save-library! new-name (:source lib) (:requires lib))
              (render!)))))
      (sep)
      (make-item "Export .clj" nil
        (fn []
          (when-let [content (storage/export-library lib-name)]
            (let [blob (js/Blob. #js [content] #js {:type "text/plain"})
                  url (.createObjectURL js/URL blob)
                  a (el "a")]
              (set! (.-href a) url)
              (set! (.-download a) (str lib-name ".clj"))
              (.click a)
              (.revokeObjectURL js/URL url)))))
      (sep)
      (make-item "Delete" "danger"
        (fn []
          (when (js/confirm (str "Delete library '" lib-name "'?"))
            (storage/delete-library! lib-name)
            (when-let [cb (:on-change @callbacks)] (cb))
            (render!)))))
    (.appendChild js/document.body menu)
    (swap! panel-state assoc :context-menu {:name lib-name :x x :y y})))

;; Close context menu on outside click
(defonce ^:private _click-handler
  (.addEventListener js/document "click"
    (fn [e]
      (when (:context-menu @panel-state)
        (when-not (.closest (.-target e) ".library-context-menu")
          (close-context-menu!))))))

;; ============================================================
;; Drag & Drop
;; ============================================================

(defonce ^:private drag-state (atom nil))

(defn- setup-drag! [item-el lib-name]
  (set! (.-draggable item-el) true)
  (.addEventListener item-el "dragstart"
    (fn [e]
      (reset! drag-state lib-name)
      (.add (.-classList item-el) "dragging")
      (.setData (.-dataTransfer e) "text/plain" lib-name)))
  (.addEventListener item-el "dragend"
    (fn [_]
      (reset! drag-state nil)
      (.remove (.-classList item-el) "dragging")))
  (.addEventListener item-el "dragover"
    (fn [e]
      (.preventDefault e)
      (when @drag-state
        (.add (.-classList item-el) "drag-over"))))
  (.addEventListener item-el "dragleave"
    (fn [_]
      (.remove (.-classList item-el) "drag-over")))
  (.addEventListener item-el "drop"
    (fn [e]
      (.preventDefault e)
      (.remove (.-classList item-el) "drag-over")
      (when-let [dragged @drag-state]
        (when (not= dragged lib-name)
          (let [active (storage/get-active-libraries)
                without (vec (remove #{dragged} active))
                idx (.indexOf without lib-name)
                reordered (vec (concat (subvec without 0 (inc idx))
                                       [dragged]
                                       (subvec without (inc idx))))]
            (storage/reorder-active! reordered)
            (when-let [cb (:on-change @callbacks)] (cb))
            (render!)))))))

;; ============================================================
;; Edit Mode
;; ============================================================

(defn editing? []
  (some? (:editing @panel-state)))

(defn get-editing-name []
  (:editing @panel-state))

(defn enter-edit-mode!
  "Enter edit mode for a library. Saves current editor content,
   loads library source into editor."
  [lib-name]
  (when-let [lib (storage/get-library lib-name)]
    (let [current-content (when-let [cb (:get-editor-content @callbacks)] (cb))]
      (swap! panel-state assoc
             :editing lib-name
             :saved-editor (or current-content ""))
      (when-let [cb (:set-editor-content @callbacks)]
        (cb (:source lib)))
      (render!))))

(defn save-and-exit-edit!
  "Save the current editor content as library source, restore original editor."
  []
  (when-let [lib-name (:editing @panel-state)]
    (let [lib-source (when-let [cb (:get-editor-content @callbacks)] (cb))
          lib (storage/get-library lib-name)
          requires (or (:requires lib) [])]
      ;; Save library with current editor content
      (storage/save-library! lib-name (or lib-source "") requires)
      ;; Restore original editor content
      (when-let [cb (:set-editor-content @callbacks)]
        (cb (:saved-editor @panel-state)))
      ;; Clear edit state
      (swap! panel-state assoc :editing nil :saved-editor "")
      ;; Trigger re-evaluation with updated libraries
      (when-let [cb (:on-change @callbacks)] (cb))
      (render!))))

(defn discard-and-exit-edit!
  "Discard changes and restore original editor."
  []
  (when-let [cb (:set-editor-content @callbacks)]
    (cb (:saved-editor @panel-state)))
  (swap! panel-state assoc :editing nil :saved-editor "")
  (render!))

;; ============================================================
;; Requires Editor (for edit mode)
;; ============================================================

(defn- add-require! [lib-name req-name]
  (when-let [lib (storage/get-library lib-name)]
    (let [requires (or (:requires lib) [])
          source (or (:source lib) "")]
      (when-not (some #{req-name} requires)
        (storage/save-library! lib-name source (conj requires req-name))
        (render!)))))

(defn- remove-require! [lib-name req-name]
  (when-let [lib (storage/get-library lib-name)]
    (let [requires (vec (remove #{req-name} (:requires lib)))
          source (or (:source lib) "")]
      (storage/save-library! lib-name source requires)
      (render!))))

;; ============================================================
;; Render
;; ============================================================

(defn render!
  "Re-render the library panel from storage state."
  []
  (when-let [panel-el (.getElementById js/document "library-panel")]
    (remove-children! panel-el)
    ;; Apply collapsed class
    (if (:collapsed @panel-state)
      (.add (.-classList panel-el) "collapsed")
      (.remove (.-classList panel-el) "collapsed"))
    ;; Header
    (let [header (el-with "div" "section-header" nil)
          title-area (el-with "div" nil nil)
          collapse-icon (el-with "span" "library-collapse-icon" "\u25BC")
          title-text (el-with "span" "section-title" "Libraries")
          actions (el-with "div" "section-actions" nil)
          add-btn (el-with "button" "action-btn" "+")]
      (set! (.-title add-btn) "New library")
      (set! (.-style.display title-area) "flex")
      (set! (.-style.alignItems title-area) "center")
      (set! (.-style.gap title-area) "6px")
      (append! title-area collapse-icon title-text)
      ;; Header click toggles collapse
      (.addEventListener header "click"
        (fn [e]
          (when-not (.closest (.-target e) ".action-btn")
            (swap! panel-state update :collapsed not)
            (render!))))
      ;; Add button
      (.addEventListener add-btn "click"
        (fn [e]
          (.stopPropagation e)
          (when-let [name (js/prompt "Library name:")]
            (let [name (str/trim name)]
              (when (seq name)
                (storage/save-library! name "" [])
                (storage/activate-library! name)
                (render!)
                ;; Enter edit mode for new library
                (when-let [cb (:on-edit @callbacks)] (cb name)))))))
      (append! actions add-btn)
      ;; Import button
      (let [import-btn (el-with "button" "action-btn" "\u2191")]
        (set! (.-title import-btn) "Import .clj library")
        (.addEventListener import-btn "click"
          (fn [e]
            (.stopPropagation e)
            ;; Use a file input
            (let [input (or (.getElementById js/document "library-file-input")
                            (let [fi (el "input")]
                              (set! (.-type fi) "file")
                              (set! (.-id fi) "library-file-input")
                              (set! (.-accept fi) ".clj,.cljs")
                              (.appendChild js/document.body fi)
                              fi))]
              (set! (.-onchange input)
                (fn [_]
                  (when-let [file (aget (.-files input) 0)]
                    (let [reader (js/FileReader.)]
                      (set! (.-onload reader)
                        (fn [e]
                          (let [content (.. e -target -result)
                                lib-name (storage/import-library! content)]
                            (if lib-name
                              (do (storage/activate-library! lib-name)
                                  (render!)
                                  (when-let [cb (:on-change @callbacks)] (cb)))
                              (js/alert "Invalid library file. Expected ;; Ridley Library: NAME header.")))
                          ;; Reset file input
                          (set! (.-value input) "")))
                      (.readAsText reader file)))))
              (.click input))))
        (append! actions import-btn))
      (set! (.-style.display header) "flex")
      (set! (.-style.alignItems header) "center")
      (set! (.-style.justifyContent header) "space-between")
      (.appendChild header title-area)
      (.appendChild header actions)
      (.appendChild panel-el header))
    ;; Edit mode bar (if editing)
    (when-let [lib-name (:editing @panel-state)]
      (let [edit-bar (el-with "div" "library-edit-bar" nil)
            back-btn (el-with "button" "edit-back-btn" "\u25C0 Back")
            name-span (el-with "span" "edit-lib-name" (str "Editing: " lib-name))
            save-btn (el-with "button" "edit-save-btn" "Save")]
        (.addEventListener back-btn "click" (fn [_] (discard-and-exit-edit!)))
        (.addEventListener save-btn "click" (fn [_] (save-and-exit-edit!)))
        (append! edit-bar back-btn name-span save-btn)
        (.appendChild panel-el edit-bar))
      ;; Requires tags row
      (when-let [lib (storage/get-library lib-name)]
        (let [requires (:requires lib)
              req-row (el-with "div" "library-edit-requires" nil)]
          (doseq [req requires]
            (let [tag (el-with "span" "req-tag" req)
                  remove-x (el-with "span" nil " \u00D7")]
              (set! (.-style.cursor remove-x) "pointer")
              (.addEventListener remove-x "click"
                (fn [_] (remove-require! lib-name req)))
              (append! tag remove-x)
              (.appendChild req-row tag)))
          ;; Add require button
          (let [add-req (el-with "button" "req-add-btn" "+ require")]
            (.addEventListener add-req "click"
              (fn [e]
                (let [all-libs (storage/list-libraries)
                      available (vec (remove #{lib-name} (remove (set (or (:requires lib) [])) all-libs)))]
                  (cond
                    (empty? available) nil ;; no other libraries
                    (= 1 (count available))
                    (add-require! lib-name (first available))
                    :else
                    ;; Show picker menu
                    (let [rect (.getBoundingClientRect (.-target e))
                          menu (el-with "div" "library-context-menu" nil)]
                      (set! (.-style.left menu) (str (.-left rect) "px"))
                      (set! (.-style.top menu) (str (.-bottom rect) "px"))
                      (doseq [lib-opt available]
                        (let [btn (el-with "button" "library-context-menu-item" lib-opt)]
                          (.addEventListener btn "click"
                            (fn [_]
                              (.remove menu)
                              (add-require! lib-name lib-opt)))
                          (.appendChild menu btn)))
                      (.appendChild js/document.body menu)
                      ;; Close on outside click
                      (let [close-fn (atom nil)]
                        (reset! close-fn
                          (fn [evt]
                            (when-not (.contains menu (.-target evt))
                              (.remove menu)
                              (.removeEventListener js/document "click" @close-fn))))
                        (js/setTimeout
                          #(.addEventListener js/document "click" @close-fn) 0)))))))
            (.appendChild req-row add-req))
          (.appendChild panel-el req-row))))
    ;; Library list
    (let [list-el (el-with "div" "library-list" nil)
          all-libs (storage/list-libraries)
          active-set (set (storage/get-active-libraries))]
      (if (empty? all-libs)
        (let [empty-msg (el-with "div" "library-list-empty" "No libraries yet. Click + to create one.")]
          (.appendChild list-el empty-msg))
        (doseq [lib-name all-libs]
          (let [lib (storage/get-library lib-name)
                active? (contains? active-set lib-name)
                ;; A library is "effectively loaded" only if active AND all deps satisfied
                deps-satisfied? (every? active-set (or (:requires lib) []))
                loaded? (and active? deps-satisfied?)
                item (el-with "div" "library-item" nil)
                ;; Drag handle (only for loaded)
                handle (when loaded?
                         (el-with "span" "library-drag-handle" "\u2630"))
                ;; Checkbox
                checkbox (el "input")
                ;; Name
                name-el (el-with "span" "library-name" lib-name)
                ;; Dependency indicator
                deps-el (el-with "span" "library-deps" nil)
                ;; Menu button
                menu-btn (el-with "button" "library-menu-btn" "\u22EE")]
            ;; Checkbox shows "loaded" state, not just "active"
            (set! (.-type checkbox) "checkbox")
            (set! (.-checked checkbox) loaded?)
            (.addEventListener checkbox "change"
              (fn [_]
                (if (.-checked checkbox)
                  (storage/activate-library! lib-name)
                  (do
                    ;; Cascade deactivate: also deactivate libs that depend on this one
                    (storage/deactivate-library! lib-name)
                    (let [all-active (storage/get-active-libraries)]
                      (doseq [other all-active]
                        (when-let [other-lib (storage/get-library other)]
                          (when (some #{lib-name} (:requires other-lib))
                            (storage/deactivate-library! other)))))))
                (when-let [cb (:on-change @callbacks)] (cb))
                (render!)))
            ;; Dependency indicators
            (when (seq (:requires lib))
              (let [parts (for [req (:requires lib)]
                            (if (contains? active-set req)
                              (str req " \u2713")
                              (str req " \u2717")))]
                (set! (.-textContent deps-el) (str "req: " (str/join ", " parts)))
                (if deps-satisfied?
                  (.add (.-classList deps-el) "dep-ok")
                  (.add (.-classList deps-el) "dep-missing"))))
            ;; Dim the name if active but deps not satisfied
            (when (and active? (not deps-satisfied?))
              (set! (.-style.opacity name-el) "0.5"))
            ;; Menu button
            (.addEventListener menu-btn "click"
              (fn [e]
                (.stopPropagation e)
                (let [rect (.getBoundingClientRect menu-btn)]
                  (show-context-menu! lib-name (.-right rect) (.-bottom rect)))))
            ;; Double-click to edit
            (.addEventListener item "dblclick"
              (fn [_]
                (when-let [cb (:on-edit @callbacks)] (cb lib-name))))
            ;; Assemble item
            (when handle (append! item handle))
            (append! item checkbox name-el deps-el menu-btn)
            ;; Drag & drop for loaded items
            (when loaded? (setup-drag! item lib-name))
            (.appendChild list-el item))))
      (.appendChild panel-el list-el))))

;; ============================================================
;; Setup
;; ============================================================

(defn setup!
  "Initialize the library panel. Takes a callbacks map:
   :get-editor-content  fn [] -> string
   :set-editor-content  fn [string] -> nil
   :on-edit             fn [lib-name] -> nil (enter edit mode)
   :on-change           fn [] -> nil (library activated/deactivated/reordered)"
  [cb-map]
  (reset! callbacks cb-map)
  (render!))
