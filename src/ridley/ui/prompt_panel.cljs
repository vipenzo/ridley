(ns ridley.ui.prompt-panel
  "Prompt Editor panel — view and edit system prompts used by AI features.
   Opens as an overlay dialog with prompt list, provider selector, and text editor."
  (:require [clojure.string :as str]
            [ridley.ai.prompt-store :as store]
            [ridley.ai.prompts :as prompts]
            [ridley.ai.describe :as describe]
            [ridley.settings :as settings]))

;; =============================================================================
;; State
;; =============================================================================

(defonce ^:private state
  (atom {:open? false
         :selected-id "codegen/tier2"
         :variant :default       ;; :default | [:provider "name"] | [:provider-model "name" "model"]
         :dirty? false
         :editor-text nil}))

(declare close!)

;; =============================================================================
;; DOM helpers
;; =============================================================================

(defn- el
  ([tag] (.createElement js/document tag))
  ([tag class-name] (let [e (.createElement js/document tag)]
                      (when class-name (set! (.-className e) class-name))
                      e))
  ([tag class-name text] (let [e (.createElement js/document tag)]
                           (when class-name (set! (.-className e) class-name))
                           (when text (set! (.-textContent e) text))
                           e)))

(defn- append! [parent & children]
  (doseq [c children]
    (when c (.appendChild parent c))))

;; =============================================================================
;; Prompt defaults — maps prompt ID to its hardcoded default
;; =============================================================================

(defn- get-default-for-id [id]
  (case id
    "codegen/tier1" (prompts/get-default-prompt :tier-1)
    "codegen/tier2" (prompts/get-default-prompt :tier-2)
    "codegen/tier3" (prompts/get-default-prompt :tier-3)
    "describe/system" describe/default-system-prompt
    "describe/user" describe/default-user-prompt
    nil))

;; =============================================================================
;; Current variant helpers
;; =============================================================================

(defn- variant-args
  "Return the arguments for store functions based on current variant."
  [id variant]
  (case (if (keyword? variant) variant (first variant))
    :default [id]
    :provider [id (second variant)]
    :provider-model [id (second variant) (nth variant 2)]))

(defn- load-current-text
  "Load the text for the currently selected prompt + variant."
  []
  (let [{:keys [selected-id variant]} @state
        args (variant-args selected-id variant)
        custom (apply store/get-custom-prompt args)
        default (get-default-for-id selected-id)]
    {:text (or custom default "")
     :modified? (some? custom)}))

;; =============================================================================
;; Render
;; =============================================================================

(declare render!)

(defn- render-prompt-list [container]
  (let [{:keys [selected-id]} @state]
    (doseq [{:keys [category prompts]} (store/categories)]
      (let [cat-header (el "div" "prompt-panel-category" category)]
        (append! container cat-header))
      (doseq [{:keys [id name]} prompts]
        (let [item (el "div" (str "prompt-panel-item"
                                  (when (= id selected-id) " selected")))
              label (el "span" "prompt-panel-item-label" name)
              modified? (store/is-modified? id)
              badge (when modified? (el "span" "prompt-panel-badge" "●"))]
          (.addEventListener item "click"
            (fn [_]
              (swap! state assoc :selected-id id :variant :default :dirty? false :editor-text nil)
              (render!)))
          (append! item label badge)
          (append! container item))))))

(defn- render-variant-selector [container]
  (let [{:keys [variant]} @state
        select (el "select" "prompt-panel-variant-select")
        provider (settings/get-ai-setting :provider)
        provider-name (when provider (if (keyword? provider) (name provider) (str provider)))
        model (settings/get-ai-model)]
    ;; Default option
    (let [opt (el "option" nil "default")]
      (set! (.-value opt) "default")
      (when (= variant :default) (set! (.-selected opt) true))
      (append! select opt))
    ;; Provider option
    (when provider-name
      (let [opt (el "option" nil provider-name)]
        (set! (.-value opt) (str "provider:" provider-name))
        (when (and (vector? variant) (= (first variant) :provider))
          (set! (.-selected opt) true))
        (append! select opt)))
    ;; Provider + model option
    (when (and provider-name model)
      (let [opt (el "option" nil (str provider-name " / " model))]
        (set! (.-value opt) (str "provider-model:" provider-name ":" model))
        (when (and (vector? variant) (= (first variant) :provider-model))
          (set! (.-selected opt) true))
        (append! select opt)))
    (.addEventListener select "change"
      (fn [_]
        (let [v (.-value select)
              new-variant (cond
                            (= v "default") :default
                            (str/starts-with? v "provider:")
                            [:provider (subs v 9)]
                            (str/starts-with? v "provider-model:")
                            (let [rest (subs v 15)
                                  idx (str/index-of rest ":")]
                              [:provider-model (subs rest 0 idx) (subs rest (inc idx))])
                            :else :default)]
          (swap! state assoc :variant new-variant :dirty? false :editor-text nil)
          (render!))))
    (append! container select)))

(defn- render-editor [container]
  (let [{:keys [selected-id dirty?]} @state
        {:keys [text modified?]} (load-current-text)
        ;; Use editor-text if user has typed, otherwise loaded text
        current-text (or (:editor-text @state) text)
        textarea (el "textarea" "prompt-panel-editor")
        info-row (el "div" "prompt-panel-info")
        macros-info (when-let [meta (store/prompt-ids-by-id selected-id)]
                      (str "Macros: " (str/join ", " (map #(str "{{" % "}}") (sort (:macros meta))))))
        macros-el (el "span" "prompt-panel-macros" macros-info)
        status-el (el "span" (str "prompt-panel-status"
                                  (when (or modified? dirty?) " modified"))
                       (cond dirty? "● unsaved changes"
                             modified? "● customized"
                             :else "default"))]
    (set! (.-value textarea) current-text)
    (set! (.-spellcheck textarea) false)
    (set! (.-rows textarea) 20)
    (.addEventListener textarea "input"
      (fn [_]
        (swap! state assoc
               :editor-text (.-value textarea)
               :dirty? true)
        ;; Update status without full re-render
        (when-let [s (.querySelector js/document ".prompt-panel-status")]
          (set! (.-textContent s) "● unsaved changes")
          (.add (.-classList s) "modified"))))
    (append! info-row macros-el status-el)
    (append! container info-row textarea)))

(defn- render-actions [container]
  (let [{:keys [selected-id variant dirty?]} @state
        save-btn (el "button" "prompt-panel-btn" "Save")
        reset-btn (el "button" "prompt-panel-btn prompt-panel-btn-danger" "Reset to Default")
        export-btn (el "button" "prompt-panel-btn" "Export All")
        import-btn (el "button" "prompt-panel-btn" "Import")]
    ;; Save
    (.addEventListener save-btn "click"
      (fn [_]
        (when-let [text (:editor-text @state)]
          (let [args (variant-args selected-id variant)]
            (apply store/save-prompt! (conj (vec args) text)))
          (swap! state assoc :dirty? false :editor-text nil)
          (render!))))
    ;; Reset
    (.addEventListener reset-btn "click"
      (fn [_]
        (when (js/confirm "Reset this prompt to the system default?")
          (let [args (variant-args selected-id variant)]
            (apply store/delete-prompt! args))
          (swap! state assoc :dirty? false :editor-text nil)
          (render!))))
    ;; Export
    (.addEventListener export-btn "click"
      (fn [_]
        (let [json (store/export-json)
              blob (js/Blob. #js [json] #js {:type "application/json"})
              url (.createObjectURL js/URL blob)
              a (el "a")]
          (set! (.-href a) url)
          (set! (.-download a) "ridley-prompts.json")
          (.click a)
          (.revokeObjectURL js/URL url))))
    ;; Import
    (.addEventListener import-btn "click"
      (fn [_]
        (let [input (el "input")]
          (set! (.-type input) "file")
          (set! (.-accept input) ".json")
          (.addEventListener input "change"
            (fn [_]
              (when-let [file (aget (.-files input) 0)]
                (-> (.text file)
                    (.then (fn [text]
                             (when (js/confirm "Import prompts? This will overwrite existing customizations.")
                               (let [n (store/import-json! text)]
                                 (js/alert (str "Imported " n " prompts."))
                                 (swap! state assoc :dirty? false :editor-text nil)
                                 (render!)))))))))
          (.click input))))
    (when dirty? (.add (.-classList save-btn) "primary"))
    (append! container save-btn reset-btn export-btn import-btn)))

(defn render!
  "Re-render the prompt editor panel."
  []
  (when-let [overlay (.getElementById js/document "prompt-panel")]
    (set! (.-innerHTML overlay) "")
    (when (:open? @state)
      ;; Click on overlay backdrop closes the panel
      (.addEventListener overlay "click"
        (fn [e] (when (= (.-target e) overlay) (close!))))
      ;; Escape key closes the panel
      (.addEventListener overlay "keydown"
        (fn [e] (when (= (.-key e) "Escape") (close!))))
      ;; Dialog container (the visible box inside the overlay)
      (let [dialog (el "div" "prompt-panel-dialog")]
        ;; Header
        (let [header (el "div" "prompt-panel-header")
              title (el "h2" "prompt-panel-title" "Prompt Editor")
              close-btn (el "button" "prompt-panel-close" "×")]
          (.addEventListener close-btn "click" (fn [_] (close!)))
          (append! header title close-btn)
          (append! dialog header))
        ;; Body
        (let [body (el "div" "prompt-panel-body")
              sidebar (el "div" "prompt-panel-sidebar")
              main (el "div" "prompt-panel-main")
              variant-row (el "div" "prompt-panel-variant-row")
              editor-area (el "div" "prompt-panel-editor-area")
              actions-row (el "div" "prompt-panel-actions")]
          (render-prompt-list sidebar)
          (render-variant-selector variant-row)
          (render-editor editor-area)
          (render-actions actions-row)
          (append! main variant-row editor-area actions-row)
          (append! body sidebar main)
          (append! dialog body))
        (append! overlay dialog)))))

;; =============================================================================
;; Public API
;; =============================================================================

(defn open!
  "Open the prompt editor panel."
  []
  (swap! state assoc :open? true :dirty? false :editor-text nil)
  (when-let [panel (.getElementById js/document "prompt-panel")]
    (set! (.-style.display panel) "flex"))
  (render!))

(defn close!
  "Close the prompt editor panel."
  []
  (when (or (not (:dirty? @state))
            (js/confirm "You have unsaved changes. Close anyway?"))
    (swap! state assoc :open? false :dirty? false :editor-text nil)
    (when-let [panel (.getElementById js/document "prompt-panel")]
      (set! (.-style.display panel) "none"))))

(defn toggle!
  "Toggle the prompt editor panel."
  []
  (if (:open? @state) (close!) (open!)))
