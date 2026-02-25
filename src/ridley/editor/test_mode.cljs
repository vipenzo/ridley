(ns ridley.editor.test-mode
  "Interactive test/tweak mode for REPL expressions.
   Evaluates an expression, shows the result in the viewport, and creates
   interactive sliders for numeric literals. Moving a slider re-evaluates
   the expression and updates the preview in real-time."
  (:require [sci.core :as sci]
            [ridley.editor.state :as state]
            [ridley.scene.registry :as registry]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.viewport.core :as viewport]
            [ridley.measure.core :as measure]
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
;;  :saved-turtle    <snapshot of turtle state>
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

                (map? form)
                (doseq [[k v] form]
                  (walk k parent-fn form nil)
                  (walk v (when (keyword? k) k) form nil))

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

                (map? form)
                (into (empty form) (map (fn [[k v]] [(walk k) (walk v)]) form))

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
      (reset! @state/turtle-state-var saved-turtle)
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
          ;; Live-refresh rulers when tweaking a registered mesh
          (when-let [reg-name (:registry-name @test-state)]
            (when (mesh? result)
              (measure/set-ruler-overrides! {reg-name result})
              (measure/refresh-rulers!)))
          true)
        (catch :default e
          (let [msg (str "tweak eval error: " (.-message e))]
            (state/capture-println msg)
            (js/console.warn msg)
            (js/console.warn "tweak form-str:" form-str)
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
  "Cancel test mode: discard changes, clear preview, restore turtle.
   In registry mode, re-shows the original mesh."
  []
  (when-let [{:keys [saved-turtle registry-name]} @test-state]
    (reset! @state/turtle-state-var saved-turtle)
    (viewport/clear-preview!)
    (measure/clear-ruler-overrides!)
    (measure/refresh-rulers!)
    (when registry-name
      (registry/show-mesh! registry-name)
      (registry/refresh-viewport! true))
    (cleanup-ui!)
    (reset! test-state nil)))

(defn confirm!
  "Confirm test mode: print final expression, clear preview, restore turtle.
   In registry mode, re-evaluates and registers the tweaked mesh."
  []
  (when-let [{:keys [form current-values saved-turtle registry-name]} @test-state]
    (let [final-form (substitute-values form current-values)
          final-str (pr-str final-form)]
      (reset! @state/turtle-state-var saved-turtle)
      (viewport/clear-preview!)
      (if registry-name
        ;; Registry mode: re-evaluate, register, show
        (try
          (let [ctx @state/sci-ctx-ref
                result (sci/eval-string final-str ctx)]
            (when (mesh? result)
              (registry/register-mesh! registry-name result)
              (registry/set-source-form! registry-name final-form)
              (registry/show-mesh! registry-name)
              (registry/refresh-viewport! true))
            (add-repl-output! final-str))
          (catch :default e
            (let [msg (str "tweak confirm error: " (.-message e))]
              (state/capture-println msg)
              (js/console.warn msg)
              ;; Re-show original on error
              (registry/show-mesh! registry-name)
              (registry/refresh-viewport! true))))
        ;; Normal mode: just print
        (add-repl-output! final-str))
      (measure/clear-ruler-overrides!)
      (measure/refresh-rulers!)
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
;; Symbol inlining — resolve def'd data before walking
;; ============================================================

(defn- data-value?
  "True if x is a plain data value suitable for inlining (not a fn, mesh, atom, etc.)."
  [x]
  (or (number? x) (string? x) (keyword? x) (boolean? x) (nil? x)
      (and (map? x) (not (:vertices x)) (not (:type x)))  ;; plain map, not mesh/shape/path
      (vector? x) (set? x)))

(defn- inline-data-symbols
  "Walk a quoted form, replacing symbols that resolve to data values.
   Checks locals map first (for let-bound vars), then falls back to SCI context.
   Symbols in function position (first element of a list) are NOT resolved.
   Returns the form with data symbols inlined."
  [form ctx locals]
  (letfn [(try-resolve [sym]
            (if (and locals (contains? locals sym))
              ;; Found in locals map (captured let-bound values)
              (let [v (get locals sym)]
                (when (data-value? v) v))
              ;; Fall back to SCI context (def'd vars)
              (try
                (let [v (sci/eval-string (str sym) ctx)]
                  (when (data-value? v) v))
                (catch :default _ nil))))
          (walk [form in-fn-pos?]
            (cond
              ;; Symbol not in function position → try to resolve
              (and (symbol? form) (not in-fn-pos?))
              (if-let [v (try-resolve form)]
                v
                form)

              ;; List (function call) — keep fn symbol, walk args
              (and (list? form) (seq form))
              (apply list
                     (walk (first form) true)  ;; fn position
                     (map #(walk % false) (rest form)))

              ;; Vector
              (vector? form)
              (mapv #(walk % false) form)

              ;; Map
              (map? form)
              (into (empty form)
                    (map (fn [[k v]] [(walk k false) (walk v false)]) form))

              ;; Set
              (set? form)
              (into #{} (map #(walk % false) form))

              :else form))]
    (walk form false)))

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
   filt: nil (first only), int, neg-int, vector, or :all
   registry-name: optional keyword — when set, hides the registered mesh on enter
                  and re-registers on confirm / re-shows on cancel
   locals: optional map of {symbol value} for let-bound vars captured by macro"
  ([quoted-form filt] (start! quoted-form filt nil nil))
  ([quoted-form filt registry-name] (start! quoted-form filt registry-name nil))
  ([quoted-form filt registry-name locals]
   ;; Cancel any existing test session
   (when (active?) (cancel!))
   ;; In registry mode, hide the original mesh first
   (when registry-name
     (registry/hide-mesh! registry-name)
     (registry/refresh-viewport! true))
   (let [;; Inline data symbols (resolve def'd maps/vectors/numbers)
         ctx @state/sci-ctx-ref
         resolved-form (if ctx
                         (inline-data-symbols quoted-form ctx locals)
                         quoted-form)
         ;; Find all numeric literals
         literals (find-numeric-literals resolved-form)
         total (count literals)]
     (when (> total 32)
       (state/capture-println
         (str "tweak: " total " numeric literals found, max 32 — narrow with (tweak [0 1 2] expr)")))
     (if (or (zero? total) (> total 32))
       ;; No numeric literals or too many — just evaluate and show result
       (do (when (zero? total)
             (state/capture-println "tweak: no numeric literals found"))
           ;; Re-show if registry mode
           (when registry-name
             (registry/show-mesh! registry-name)
             (registry/refresh-viewport! true))
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
             saved-turtle @@state/turtle-state-var]
         ;; Set up state
         (reset! test-state {:form resolved-form
                             :literals labeled
                             :selected selected
                             :current-values initial-values
                             :saved-turtle saved-turtle
                             :registry-name registry-name})
         ;; Verify expression evaluates successfully before committing
         (if (evaluate-and-preview!)
           (do
             ;; Success — print index map
             (state/capture-println (format-index-map labeled))
             ;; Defer preview re-render + sliders to next tick.
             ;; Reason: when tweak runs inside evaluate-definitions, the caller
             ;; calls refresh-viewport! after we return, which wipes the preview.
             ;; By deferring, our preview renders AFTER that refresh.
             (js/setTimeout
               (fn []
                 (evaluate-and-preview!)
                 (create-slider-ui! labeled selected))
               0)
             nil)
           ;; Evaluation failed — clean up, don't show sliders
           (do
             (when registry-name
               (registry/show-mesh! registry-name)
               (registry/refresh-viewport! true))
             (reset! test-state nil)
             nil)))))))

(defn ^:export start-registered!
  "Enter test mode for a registered mesh.
   name: keyword name in registry
   quoted-form: quoted expression, or nil to use stored source-form
   filt: optional filter (nil, int, vector, or :all)
   locals: optional map of {symbol value} for let-bound vars"
  ([name quoted-form filt] (start-registered! name quoted-form filt nil))
  ([name quoted-form filt locals]
   (let [form (or quoted-form (registry/get-source-form name))]
     (if (nil? form)
       (do (state/capture-println
             (str "tweak: no source form for " name " — use (tweak " name " expr)"))
           nil)
       (start! form filt name locals)))))
