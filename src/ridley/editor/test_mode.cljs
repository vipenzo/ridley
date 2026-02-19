(ns ridley.editor.test-mode
  "Interactive test/tweak mode for REPL expressions.
   Evaluates an expression, shows the result in the viewport, and creates
   interactive sliders for numeric literals. Moving a slider re-evaluates
   the expression and updates the preview in real-time."
  (:require [sci.core :as sci]
            [ridley.editor.state :as state]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.viewport.core :as viewport]
            [clojure.string :as str]))

;; ============================================================
;; State
;; ============================================================

(defonce ^:private test-state (atom nil))
;; When active:
;; {:form            <quoted s-expr>
;;  :literals        [{:index :value :label :parent-fn :parent-form :arg-idx} ...]
;;  :selected        #{0 2}          — indices with sliders
;;  :current-values  {0 15.0, 2 90}  — current slider values
;;  :saved-turtle    <snapshot of turtle-atom>
;;  :panel-el        <DOM element>
;;  :esc-handler     <fn>}

;; ============================================================
;; AST Walking — find numeric literals
;; ============================================================

(defn- find-numeric-literals
  "Walk a quoted form depth-first left-to-right, collecting numeric literals.
   Returns vector of {:index :value :parent-fn :parent-form :arg-idx}."
  [form]
  (let [results (volatile! [])
        idx (volatile! 0)]
    (letfn [(walk [form parent-fn parent-form arg-idx-in-parent]
              (cond
                (number? form)
                (do (vswap! results conj {:index @idx
                                          :value form
                                          :parent-fn parent-fn
                                          :parent-form parent-form
                                          :arg-idx arg-idx-in-parent})
                    (vswap! idx inc))

                (and (list? form) (seq form))
                (let [fn-sym (when (symbol? (first form)) (first form))
                      args (if fn-sym (rest form) form)]
                  (doall (map-indexed
                           (fn [i child]
                             (walk child (or fn-sym parent-fn) form i))
                           args)))

                (sequential? form)
                (doall (map-indexed
                         (fn [i child]
                           (walk child parent-fn parent-form i))
                         form))

                :else nil))]
      (walk form nil nil nil)
      @results)))

;; ============================================================
;; Label generation
;; ============================================================

(defn- generate-label
  "Generate a display label for a numeric literal based on its parent context.
   E.g. (circle 15) → 'circle: 15', (box 40 20) → 'box[0]: 40'."
  [lit all-lits]
  (let [{:keys [value parent-fn parent-form arg-idx]} lit]
    (if (nil? parent-fn)
      (str value)
      (let [;; Get parent args (skip fn symbol)
            args (when (and (list? parent-form) (seq parent-form)
                            (symbol? (first parent-form)))
                   (vec (rest parent-form)))
            ;; Check if preceded by a keyword arg (e.g. :amount 0.3)
            kw-name (when (and args arg-idx (pos? arg-idx))
                      (let [prev (nth args (dec arg-idx) nil)]
                        (when (keyword? prev) (name prev))))
            ;; Count numeric siblings in same parent form
            siblings (filterv #(identical? (:parent-form %) parent-form) all-lits)
            n-siblings (count siblings)]
        (cond
          kw-name
          (str (name parent-fn) " " kw-name ": " value)

          (> n-siblings 1)
          (let [pos (.indexOf (mapv :index siblings) (:index lit))]
            (str (name parent-fn) "[" pos "]: " value))

          :else
          (str (name parent-fn) ": " value))))))

;; ============================================================
;; Index filter resolution
;; ============================================================

(defn- resolve-filter
  "Resolve a filter spec into a set of valid indices.
   nil → #{0}, int → #{n}, neg-int → #{count+n}, vec → set, :all → all."
  [filt total-count]
  (let [resolve-idx (fn [n]
                      (let [resolved (if (neg? n) (+ total-count n) n)]
                        (when (and (>= resolved 0) (< resolved total-count))
                          resolved)))]
    (cond
      (nil? filt) #{0}
      (= :all filt) (set (range total-count))
      (integer? filt) (if-let [i (resolve-idx filt)] #{i} #{})
      (sequential? filt) (into #{} (keep resolve-idx) filt)
      :else #{0})))

;; ============================================================
;; Expression substitution
;; ============================================================

(defn- substitute-values
  "Walk form, replacing the nth numeric literal with the value from new-values map.
   Preserves the original structure (lists, vectors, etc.)."
  [form new-values]
  (let [idx (volatile! 0)]
    (letfn [(walk [form]
              (cond
                (number? form)
                (let [i @idx]
                  (vswap! idx inc)
                  (get new-values i form))

                (list? form)
                (apply list (map walk form))

                (vector? form)
                (mapv walk form)

                :else form))]
      (walk form))))

;; ============================================================
;; Slider range computation
;; ============================================================

(defn- slider-range
  "Compute [min max step] for a slider given an initial value."
  [value]
  (if (zero? value)
    [-50 50 1]
    (let [lo (* value 0.1)
          hi (* value 3)
          mn (min lo hi)
          mx (max lo hi)
          step (if (integer? value) 1 0.1)]
      [mn mx step])))

;; ============================================================
;; Index map formatting
;; ============================================================

(defn- format-index-map
  "Format the index map for REPL output."
  [literals]
  (let [lines (for [{:keys [index value parent-form]} literals]
                (let [ctx-str (if parent-form
                                (pr-str parent-form)
                                (str value))]
                  (str "  " index ": " ctx-str "  -> " value)))]
    (str "tweak: " (count literals) " numeric literal"
         (when (not= 1 (count literals)) "s") " found\n"
         (str/join "\n" lines))))

;; ============================================================
;; Evaluate and preview
;; ============================================================

(defn- mesh? [x]
  (and (map? x) (:vertices x) (:faces x)))

(defn- evaluate-and-preview!
  "Re-evaluate the current form with substituted values and update the preview.
   Returns true on success, false on error."
  []
  (if-let [{:keys [current-values saved-turtle]} @test-state]
    (let [form (:form @test-state)
          modified-form (substitute-values form current-values)
          form-str (pr-str modified-form)]
      ;; Restore turtle state before evaluation
      (reset! state/turtle-atom saved-turtle)
      (try
        (let [ctx @state/sci-ctx-ref
              _ (when-not ctx (throw (js/Error. "No SCI context — run definitions first")))
              result (sci/eval-string form-str ctx)
              items (cond
                      ;; Mesh
                      (mesh? result)
                      [{:type :mesh :data result}]

                      ;; Shape → stamp at saved turtle pose
                      (shape/shape? result)
                      (let [stamped (turtle/stamp-debug saved-turtle result)
                            stamps (:stamps stamped)]
                        (mapv (fn [s] {:type :stamp :data s}) stamps))

                      ;; Path → follow from saved turtle pose
                      (turtle/path? result)
                      (let [ran (turtle/run-path saved-turtle result)
                            lines (:geometry ran)]
                        (when (seq lines)
                          [{:type :lines :data lines}]))

                      ;; Vector of meshes
                      (and (sequential? result) (seq result) (every? mesh? result))
                      (mapv (fn [m] {:type :mesh :data m}) result)

                      :else nil)]
          (if items
            (viewport/show-preview! items)
            (viewport/clear-preview!))
          true)
        (catch :default e
          (let [msg (str "tweak eval error: " (.-message e))]
            (state/capture-println msg)
            (js/console.warn msg)
            false))))
    false))

;; ============================================================
;; DOM helpers
;; ============================================================

(defn- escape-html [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- add-repl-output!
  "Add a result line directly to the REPL history DOM."
  [text]
  (when-let [history (.getElementById js/document "repl-history")]
    (let [entry (.createElement js/document "div")]
      (.add (.-classList entry) "repl-entry")
      (set! (.-innerHTML entry)
            (str "<div class=\"repl-result\">" (escape-html text) "</div>"))
      (.appendChild history entry)
      (set! (.-scrollTop history) (.-scrollHeight history)))))

;; ============================================================
;; Confirm / Cancel
;; ============================================================

(defn- cleanup-ui!
  "Remove slider panel and escape handler."
  []
  (when-let [panel-el (:panel-el @test-state)]
    (when-let [parent (.-parentNode panel-el)]
      (.removeChild parent panel-el)))
  (when-let [esc-handler (:esc-handler @test-state)]
    (.removeEventListener js/document "keydown" esc-handler)))

(defn cancel!
  "Cancel test mode: discard changes, clear preview, restore turtle."
  []
  (when-let [{:keys [saved-turtle]} @test-state]
    (reset! state/turtle-atom saved-turtle)
    (viewport/clear-preview!)
    (cleanup-ui!)
    (reset! test-state nil)))

(defn confirm!
  "Confirm test mode: print final expression, clear preview, restore turtle."
  []
  (when-let [{:keys [form current-values saved-turtle]} @test-state]
    (let [final-form (substitute-values form current-values)
          final-str (pr-str final-form)]
      (add-repl-output! final-str)
      (reset! state/turtle-atom saved-turtle)
      (viewport/clear-preview!)
      (cleanup-ui!)
      (reset! test-state nil))))

;; ============================================================
;; Slider UI
;; ============================================================

(defn- create-slider-ui!
  "Create the slider panel DOM and insert into the REPL terminal."
  [literals selected-indices]
  (let [panel (.createElement js/document "div")]
    (set! (.-id panel) "test-slider-panel")
    ;; Create sliders for selected literals
    (doseq [lit (filter #(contains? selected-indices (:index %)) literals)]
      (let [{:keys [index value label]} lit
            [smin smax step] (slider-range value)
            row (.createElement js/document "div")
            label-el (.createElement js/document "span")
            slider (.createElement js/document "input")
            value-el (.createElement js/document "span")]
        (.add (.-classList row) "slider-row")
        (.add (.-classList label-el) "slider-label")
        (set! (.-textContent label-el) label)
        (.add (.-classList value-el) "slider-value")
        (set! (.-textContent value-el) (str value))
        (set! (.-type slider) "range")
        (set! (.-min slider) (str smin))
        (set! (.-max slider) (str smax))
        (set! (.-step slider) (str step))
        (set! (.-value slider) (str value))
        (.add (.-classList slider) "test-slider")
        ;; Debounced input handler
        (let [timeout (atom nil)]
          (.addEventListener slider "input"
            (fn [_e]
              (let [new-val (js/parseFloat (.-value slider))]
                (set! (.-textContent value-el) (str new-val))
                (when-let [t @timeout] (js/clearTimeout t))
                (reset! timeout
                  (js/setTimeout
                    (fn []
                      (swap! test-state assoc-in [:current-values index] new-val)
                      (evaluate-and-preview!))
                    100))))))
        ;; Zoom buttons: re-center range on current value
        (let [zoom-fn (fn [factor]
                        (let [cur (js/parseFloat (.-value slider))
                              old-min (js/parseFloat (.-min slider))
                              old-max (js/parseFloat (.-max slider))
                              half-span (/ (* (- old-max old-min) factor) 2)
                              old-step (js/parseFloat (.-step slider))
                              new-step (if (> factor 1)
                                         (* old-step 2)
                                         (max 0.01 (/ old-step 2)))]
                          (set! (.-min slider) (str (- cur half-span)))
                          (set! (.-max slider) (str (+ cur half-span)))
                          (set! (.-step slider) (str new-step))
                          (set! (.-value slider) (str cur))))
              zoom-out (.createElement js/document "button")
              zoom-in (.createElement js/document "button")]
          (.add (.-classList zoom-out) "test-zoom-btn")
          (.add (.-classList zoom-in) "test-zoom-btn")
          (set! (.-textContent zoom-out) "+")
          (set! (.-textContent zoom-in) "\u2212")  ;; minus sign
          (set! (.-title zoom-out) "Wider range")
          (set! (.-title zoom-in) "Narrower range (more precise)")
          (.addEventListener zoom-out "click" (fn [_] (zoom-fn 2)))
          (.addEventListener zoom-in "click" (fn [_] (zoom-fn 0.5)))
          (.appendChild row label-el)
          (.appendChild row zoom-in)
          (.appendChild row slider)
          (.appendChild row zoom-out)
          (.appendChild row value-el))
        (.appendChild panel row)))
    ;; OK / Cancel buttons
    (let [btn-row (.createElement js/document "div")
          ok-btn (.createElement js/document "button")
          cancel-btn (.createElement js/document "button")]
      (.add (.-classList btn-row) "test-buttons")
      (set! (.-textContent ok-btn) "OK")
      (set! (.-textContent cancel-btn) "Cancel")
      (.add (.-classList ok-btn) "test-btn" "test-btn-ok")
      (.add (.-classList cancel-btn) "test-btn" "test-btn-cancel")
      (.addEventListener ok-btn "click" (fn [_] (confirm!)))
      (.addEventListener cancel-btn "click" (fn [_] (cancel!)))
      (.appendChild btn-row ok-btn)
      (.appendChild btn-row cancel-btn)
      (.appendChild panel btn-row))
    ;; Insert into repl-terminal before repl-input-line
    (when-let [terminal (.getElementById js/document "repl-terminal")]
      (when-let [input-line (.getElementById js/document "repl-input-line")]
        (.insertBefore terminal panel input-line)))
    ;; Store panel reference
    (swap! test-state assoc :panel-el panel)
    ;; Escape key handler
    (let [esc-handler (fn [e]
                        (when (= (.-key e) "Escape")
                          (cancel!)))]
      (swap! test-state assoc :esc-handler esc-handler)
      (.addEventListener js/document "keydown" esc-handler))))

;; ============================================================
;; Entry point
;; ============================================================

(defn ^:export active?
  "Returns true if test mode is currently active."
  []
  (some? @test-state))

(defn ^:export start!
  "Enter test mode. Called by the test macro via SCI bindings.
   quoted-form: the expression as data (not evaluated)
   filt: nil (first only), int, neg-int, vector, or :all"
  [quoted-form filt]
  ;; Cancel any existing test session
  (when (active?) (cancel!))
  (let [;; Find all numeric literals
        literals (find-numeric-literals quoted-form)
        total (count literals)]
    (if (zero? total)
      ;; No numeric literals — just evaluate and show result
      (do (state/capture-println "tweak: no numeric literals found")
          nil)
      (let [;; Resolve filter to set of indices
            selected (resolve-filter filt total)
            ;; Generate labels
            labeled (mapv (fn [lit]
                            (assoc lit :label (generate-label lit literals)))
                          literals)
            ;; Build initial values map (all literals, not just selected)
            initial-values (into {} (map (fn [{:keys [index value]}]
                                           [index value]))
                                 literals)
            ;; Save turtle state
            saved-turtle @state/turtle-atom]
        ;; Set up state
        (reset! test-state {:form quoted-form
                            :literals labeled
                            :selected selected
                            :current-values initial-values
                            :saved-turtle saved-turtle})
        ;; Print index map
        (state/capture-println (format-index-map labeled))
        ;; Initial evaluation and preview — only show sliders if it succeeds
        (if (evaluate-and-preview!)
          (do
            ;; Create slider UI (deferred so print output appears first)
            (js/setTimeout #(create-slider-ui! labeled selected) 0)
            nil)
          ;; Evaluation failed — clean up, don't show sliders
          (do
            (reset! test-state nil)
            nil))))))
