(ns ridley.ai.capture-directives
  "Parse and execute user capture directives embedded in prompts.
   Directives let the user control which views/slices are sent to the AI.

   Syntax (case-insensitive, inside square brackets):
     [view: front]           — standard orthographic view
     [view: top]             — standard orthographic view
     [view: perspective]     — perspective view
     [view: 1,1,0.5]        — custom direction vector
     [slice: z=30]           — cross-section slice
     [slice: x=0]            — cross-section slice
     [no-default-views]              — suppress all default views

   Directives are stripped from the text before it's sent to the LLM."
  (:require [clojure.string :as str]
            [ridley.viewport.capture :as capture]
            [ridley.scene.registry :as registry]
            [ridley.measure.core :as measure]))

;; =============================================================================
;; Parsing
;; =============================================================================

(def ^:private directive-re
  "Regex matching a single directive. Case-insensitive.
   Group 1: full match (for stripping), Group 2: directive type, Group 3: value."
  #"(?i)\[\s*(view|slice|no-default-views)\s*(?::\s*([^\]]*?))?\s*\]")

(defn- parse-view-value
  "Parse a view directive value. Returns a capture spec keyword or vector.
   'front' → :front, '1,1,0.5' → [1 1 0.5]"
  [value]
  (let [v (str/trim (str/lower-case value))]
    (if (re-matches #"[\d\.\-,\s]+" v)
      ;; Looks like a numeric vector: parse as [x y z]
      (let [nums (mapv js/parseFloat (str/split v #"[,\s]+"))]
        (when (and (= 3 (count nums))
                   (every? js/isFinite nums))
          nums))
      ;; Named view
      (case v
        ("front" "back" "left" "right" "top" "bottom" "perspective")
        (keyword v)
        ;; Unknown name
        nil))))

(defn- parse-slice-value
  "Parse a slice directive value like 'z=30' or 'x=-5.5'.
   Returns {:axis :z :position 30.0} or nil."
  [value]
  (when-let [[_ axis pos] (re-find #"(?i)([xyz])\s*=\s*([\d\.\-]+)" value)]
    (let [position (js/parseFloat pos)]
      (when (js/isFinite position)
        {:axis (keyword (str/lower-case axis))
         :position position}))))

(defn parse
  "Parse all capture directives from text.
   Returns {:directives [...] :clean-text string :suppress-defaults? bool}.
   Each directive is {:type :view/:slice, ...params}."
  [text]
  (if (or (nil? text) (not (str/includes? text "[")))
    {:directives [] :clean-text text :suppress-defaults? false}
    (let [matches (re-seq directive-re text)
          directives (atom [])
          suppress? (atom false)]
      (doseq [[_ dtype value] matches]
        (let [dtype-lower (str/lower-case dtype)]
          (case dtype-lower
            "no-default-views"
            (reset! suppress? true)

            "view"
            (when value
              (when-let [spec (parse-view-value value)]
                (swap! directives conj
                       {:type :view :spec spec})))

            "slice"
            (when value
              (when-let [{:keys [axis position]} (parse-slice-value value)]
                (swap! directives conj
                       {:type :slice :axis axis :position position})))

            nil)))
      ;; Strip directives from text
      (let [clean (-> (str/replace text directive-re "")
                      (str/replace #"\n{3,}" "\n\n")
                      str/trim)]
        {:directives @directives
         :clean-text clean
         :suppress-defaults? @suppress?}))))

;; =============================================================================
;; Execution — turn parsed directives into [label data-url] pairs
;; =============================================================================

(defn- execute-view
  "Execute a single view directive. Returns [label data-url] or nil."
  [{:keys [spec]} {:keys [target width height]}]
  (try
    (let [label (if (keyword? spec)
                  spec
                  (keyword (str "custom-" (str/join "-" (map int spec)))))
          data-url (capture/render-view spec
                     :target target
                     :width (or width 512)
                     :height (or height 512))]
      [label data-url])
    (catch :default e
      (js/console.warn "Capture directive failed (view):" (.-message e))
      nil)))

(defn- resolve-target
  "Resolve the target for slicing — needs a single mesh name."
  [target]
  (or target
      (first (registry/visible-names))))

(defn- execute-slice
  "Execute a single slice directive. Returns [label data-url] or nil."
  [{:keys [axis position]} {:keys [target width height]}]
  (try
    (when-let [slice-target (resolve-target target)]
      (let [label (keyword (str "slice-" (name axis) "-" position))
            data-url (capture/render-slice slice-target axis position
                       :width (or width 512)
                       :height (or height 512))]
        [label data-url]))
    (catch :default e
      (js/console.warn "Capture directive failed (slice):" (.-message e))
      nil)))

(defn execute
  "Execute parsed directives, returning a seq of [label data-url] pairs.
   opts: {:target keyword-or-nil, :width int, :height int}"
  [directives opts]
  (keep (fn [d]
          (case (:type d)
            :view  (execute-view d opts)
            :slice (execute-slice d opts)
            nil))
        directives))

;; =============================================================================
;; Convenience — parse + execute in one step
;; =============================================================================

(defn process
  "Parse directives from text, execute captures, return results.
   Returns {:clean-text string
            :images seq-of-[label data-url]
            :suppress-defaults? bool
            :has-directives? bool}"
  ([text] (process text nil))
  ([text opts]
   (let [{:keys [directives clean-text suppress-defaults?]} (parse text)
         images (when (seq directives)
                  (execute directives opts))]
     {:clean-text clean-text
      :images (or images [])
      :suppress-defaults? suppress-defaults?
      :has-directives? (boolean (seq directives))})))
