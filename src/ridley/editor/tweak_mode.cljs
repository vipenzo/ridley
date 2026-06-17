(ns ridley.editor.tweak-mode
  "Interactive tweak mode for REPL expressions.
   Evaluates an expression, shows the result in the viewport, and creates
   interactive sliders for numeric literals. Moving a slider re-evaluates
   the expression and updates the preview in real-time.

   Formerly ridley.editor.test-mode (renamed: the 'test' name was a
   historical artifact that collided with the test/ folder)."
  (:require [sci.core :as sci]
            [ridley.editor.state :as state]
            [ridley.editor.codemirror :as cm]
            [ridley.editor.modal-evaluator :as modal]
            [ridley.editor.ui :as ui]
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

;; The skip flag that stops a tweak re-eval from re-entering the (tweak ...) macro
;; now lives in modal-evaluator (shared across all modal evaluators).
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
   nil → all (no filter = tweak every parameter), int → #{n},
   neg-int → #{count+n}, vec → set, :all → all."
  [filt total-count]
  (let [resolve-idx (fn [n]
                      (let [resolved (if (neg? n) (+ total-count n) n)]
                        (when (and (>= resolved 0) (< resolved total-count))
                          resolved)))]
    (cond
      (nil? filt) (set (range total-count))
      (= :all filt) (set (range total-count))
      (integer? filt) (if-let [i (resolve-idx filt)] #{i} #{})
      (sequential? filt) (into #{} (keep resolve-idx) filt)
      :else (set (range total-count)))))

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
;; Script-mode helpers — find (tweak ...) in editor text
;; ============================================================

(defn- find-tweak-bounds
  "Find the character bounds [from, to) of the (tweak ...) form in editor text.
   Returns [from to] or nil if not found. The robust paren/string/comment-aware
   matcher lives in modal-evaluator (shared with edit-bezier)."
  [editor-text]
  (modal/find-form-bounds editor-text "(tweak "))

;; ============================================================
;; Evaluate and preview
;; ============================================================

(defn- mesh? [x]
  (and (map? x) (:vertices x) (:faces x)))

(defn- build-modified-script
  "Build the full editor script with (tweak ...) replaced by the current expression."
  [form-str]
  (let [{:keys [tweak-from tweak-to]} @test-state]
    (modal/splice-source (cm/get-value) tweak-from tweak-to form-str)))

(defn- evaluate-and-preview-script!
  "Script mode: re-evaluate the full editor script with (tweak ...) replaced.
   Returns :ok on success, nil on error. The shared re-eval boilerplate (clear
   scene, reset turtle, arm skip flag, eval, push scene, refresh) lives in
   modal-evaluator; here we add only the tweak-specific turtle-indicator update."
  []
  (when-let [{:keys [current-values]} @test-state]
    (let [form (:form @test-state)
          modified-form (substitute-values form current-values)
          form-str (pr-str modified-form)]
      ;; arm-skip? false: the modified script has (tweak …) replaced by the current
      ;; literals (no macro to re-enter), so arming would just leave the flag set and
      ;; make the NEXT tweak invocation pass through (the "every other time" bug).
      (when (modal/reeval-script! #(build-modified-script form-str)
                                  "tweak script eval error:" false)
        ;; Update turtle indicator to reflect new creation-pose
        (let [source (viewport/get-turtle-source)]
          (cond
            (and (map? source) (:mesh source))
            (when-let [mesh (registry/get-mesh (:mesh source))]
              (when-let [pose (:creation-pose mesh)]
                (viewport/update-turtle-pose pose)))

            (= source :global)
            (viewport/update-turtle-pose (state/get-turtle-pose))))
        :ok))))

(defn- evaluate-and-preview-repl!
  "REPL mode: evaluate the tweaked expression in isolation and show preview.
   Returns the evaluated result on success, nil on error."
  []
  (if-let [{:keys [current-values saved-turtle]} @test-state]
    (let [form (:form @test-state)
          modified-form (substitute-values form current-values)
          form-str (pr-str modified-form)]
      (reset! @state/turtle-state-var saved-turtle)
      (try
        (let [ctx @state/sci-ctx-ref
              _ (when-not ctx (throw (js/Error. "No SCI context — run definitions first")))
              result (sci/eval-string form-str ctx)
              items (cond
                      (mesh? result)
                      [{:type :mesh :data result}]

                      (shape/shape? result)
                      (let [stamped (turtle/stamp-debug saved-turtle result)
                            stamps (:stamps stamped)]
                        (mapv (fn [s] {:type :stamp :data s}) stamps))

                      (turtle/path? result)
                      (let [ran (turtle/run-path saved-turtle result)
                            lines (:geometry ran)]
                        (when (seq lines)
                          [{:type :lines :data lines}]))

                      (and (sequential? result) (seq result) (every? mesh? result))
                      (mapv (fn [m] {:type :mesh :data m}) result)

                      :else nil)]
          (if items
            (viewport/show-preview! items)
            (viewport/clear-preview!))
          (when-let [reg-name (:registry-name @test-state)]
            (when (mesh? result)
              (measure/set-ruler-overrides! {reg-name result})
              (measure/refresh-rulers!)))
          result)
        (catch :default e
          (let [msg (str "tweak eval error: " (.-message e))]
            (state/capture-println msg)
            (js/console.warn msg)
            (js/console.warn "tweak form-str:" form-str)
            nil))))
    nil))

(defn- evaluate-and-preview!
  "Dispatch to script or REPL mode evaluation.
   Returns the evaluated result on success, nil on error."
  []
  (if (and (:tweak-from @test-state)
           (not (:registry-name @test-state)))
    (evaluate-and-preview-script!)
    (evaluate-and-preview-repl!)))

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
  (modal/unmount-panel! (:panel-el @test-state))
  ;; Escape handler is bubble-phase (capture? false) — see create-slider-ui!.
  (modal/remove-keydown! (:esc-handler @test-state) false))

(defn cancel!
  "Cancel test mode: discard changes, clear preview, restore turtle.
   In registry mode, re-shows the original mesh.
   In script-only mode, re-evaluates the original script."
  []
  (when-let [{:keys [saved-turtle registry-name tweak-from tweak-to
                     transient-restore]} @test-state]
    (reset! @state/turtle-state-var saved-turtle)
    (viewport/clear-preview!)
    (measure/clear-ruler-overrides!)
    (measure/refresh-rulers!)
    (cond
      ;; Registry mode (also from script): the named mesh was hidden on entry
      ;; and never re-registered (slider preview is REPL-style). Just unhide it.
      registry-name
      (do (registry/show-mesh! registry-name)
          (registry/refresh-viewport! true))

      ;; Script-only mode: re-evaluate the original script to restore viewport.
      ;; If the session was auto-wrapped by the editor command, first remove the
      ;; (tweak …) wrapper, restoring the original text it stood in for.
      tweak-from
      (do (when transient-restore
            (modal/replace-source! tweak-from tweak-to transient-restore))
          ;; Only arm the skip when the (tweak …) form SURVIVES the re-run (a permanent
          ;; tweak in the user's source), so its re-eval passes through instead of
          ;; re-entering. For an editor-wrapped (transient) tweak the wrapper was just
          ;; removed, so arming would leak into the next tweak invocation.
          (when-not transient-restore (modal/arm-skip!))
          (modal/run-definitions!)))
    (cleanup-ui!)
    (modal/release!)
    (reset! test-state nil)))

(defn confirm!
  "Confirm test mode: print final expression, clear preview, restore turtle.
   - Registry mode (also from script): updates the named mesh in-memory and
     its stored source-form. The editor script is not edited; rerunning the
     script reverts B to its (register ...) line — edit that line manually
     to persist the change.
   - Script-only mode: replaces the (tweak ...) form in the editor and
     re-evaluates the whole script.
   - Plain REPL mode: prints the final expression."
  []
  (when-let [{:keys [form current-values saved-turtle registry-name
                     tweak-from tweak-to]} @test-state]
    (let [final-form (substitute-values form current-values)
          final-str (pr-str final-form)]
      (reset! @state/turtle-state-var saved-turtle)
      (viewport/clear-preview!)
      (cond
        ;; Registry mode (works in both script & REPL contexts):
        ;; update the named mesh in memory and its source-form.
        registry-name
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

        ;; Script-only mode: replace (tweak ...) in editor and re-evaluate
        (and tweak-from tweak-to)
        (do (modal/replace-source! tweak-from tweak-to final-str)
            (state/capture-println (str "tweak: " final-str))
            (modal/run-definitions!))

        ;; Plain REPL mode: just print
        :else
        (add-repl-output! final-str))
      (measure/clear-ruler-overrides!)
      (measure/refresh-rulers!)
      (cleanup-ui!)
      (modal/release!)
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
            timeout (atom nil)
            {:keys [row]} (ui/create-slider-row
                           {:label label
                            :value value
                            :on-input (fn [new-val]
                                        (when-let [t @timeout] (js/clearTimeout t))
                                        (reset! timeout
                                                (js/setTimeout
                                                 (fn []
                                                   (swap! test-state assoc-in [:current-values index] new-val)
                                                   (evaluate-and-preview!))
                                                 100)))})]
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
    (modal/mount-panel! panel)
    ;; Store panel reference
    (swap! test-state assoc :panel-el panel)
    ;; Escape key handler (bubble phase — tweak only listens for Escape and lets
    ;; the slider/value inputs handle their own keys, unlike pilot's capture handler)
    (let [esc-handler (fn [e]
                        (when (= (.-key e) "Escape")
                          (cancel!)))]
      (swap! test-state assoc :esc-handler esc-handler)
      (modal/install-keydown! esc-handler false))))

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
   filt: nil (all — same as :all), int, neg-int, vector, or :all
   registry-name: optional keyword — when set, hides the registered mesh on enter
                  and re-registers on confirm / re-shows on cancel
   locals: optional map of {symbol value} for let-bound vars captured by macro"
  ([quoted-form filt] (start! quoted-form filt nil nil))
  ([quoted-form filt registry-name] (start! quoted-form filt registry-name nil))
  ([quoted-form filt registry-name locals]
   (if (modal/consume-skip!)
     ;; Skip — cancel! in script mode armed this to avoid re-entry on re-eval
     nil
     ;; Normal entry
     (do
       ;; Claim interactive slot — throws if pilot or another tweak is active
       (modal/claim! :tweak)
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
           ;; No numeric literals or too many — no session opens, so release the
           ;; interactive slot we just claimed (otherwise the mutex leaks and the
           ;; next tweak throws "already in a tweak session").
           (do (when (zero? total)
                 (state/capture-println "tweak: no numeric literals found"))
               ;; Re-show if registry mode
               (when registry-name
                 (registry/show-mesh! registry-name)
                 (registry/refresh-viewport! true))
               (modal/release!)
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
                 saved-turtle @@state/turtle-state-var
                 ;; Detect script context: if eval-source is :definitions, find form in editor
                 from-script (= :definitions @state/eval-source-var)
                 [tweak-from tweak-to] (when from-script
                                         (find-tweak-bounds (cm/get-value)))]
             ;; Set up state
             (reset! test-state {:form resolved-form
                                 :literals labeled
                                 :selected selected
                                 :current-values initial-values
                                 :saved-turtle saved-turtle
                                 :registry-name registry-name
                                 :tweak-from tweak-from
                                 :tweak-to tweak-to
                                 ;; transient = auto-wrapped by the editor command;
                                 ;; carries the text to restore on cancel (unwrap).
                                 :transient-restore (modal/consume-tweak-transient!)})
             ;; Initial eval always uses REPL path (we're inside script eval,
             ;; can't re-eval the full script recursively)
             (let [initial-result (evaluate-and-preview-repl!)]
               (if initial-result
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
                   ;; In script mode, return the initial result so (register ...) gets
                   ;; the mesh and CCC appears in the dropdown / turtle menu.
                   ;; In REPL mode, return nil to avoid printing the mesh.
                   (when from-script initial-result))
                 ;; Evaluation failed — clean up, don't show sliders
                 (do
                   (modal/release!)
                   (when registry-name
                     (registry/show-mesh! registry-name)
                     (registry/refresh-viewport! true))
                   (reset! test-state nil)
                   nil))))))))))

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

(defn- numeric-literal?
  "True if s is (just) a single numeric literal, ignoring surrounding space."
  [s]
  (boolean (re-matches #"\s*-?\d+(\.\d+)?\s*" s)))

(defn ^:export tweak-selection!
  "Editor command: wrap the current selection in (tweak …) and run the script,
   turning the selected value(s) into sliders. A single numeric literal becomes
   (tweak n); a balanced, complete expression becomes (tweak :all …) (a slider per
   literal inside). The selection is validated first: an empty or malformed
   selection (e.g. one that accidentally grabbed a trailing paren, like \"2)\") is
   rejected without touching the buffer, since wrapping it would corrupt the
   script. The session is marked transient with the original text: on confirm
   tweak bakes the values over the wrapper, on cancel the wrapper is removed and
   the text restored, and if the wrapped script fails to evaluate the wrapper is
   rolled back (see modal/restore-failed-tweak-transient!)."
  []
  (let [{:keys [from to text]} (cm/get-selection)
        trimmed (some-> text str/trim)
        numeric? (and text (numeric-literal? text))]
    (cond
      ;; Reject paths run no eval, so capture-println (buffered, flushed during an
      ;; eval) would never surface — write straight to the REPL history instead.
      (or (nil? trimmed) (empty? trimmed))
      (add-repl-output! "tweak: select a value (or expression) first")

      (or numeric? (modal/balanced-source? trimmed))
      (let [wrapped (if numeric?
                      (str "(tweak " trimmed ")")
                      (str "(tweak :all " trimmed ")"))]
        ;; Carry the original text + wrapper position so a cancel (unwrap) or a
        ;; failed eval (rollback) can restore the selection exactly.
        (modal/arm-tweak-transient! text from wrapped)
        (cm/replace-range from to wrapped)
        (modal/run-definitions!))

      :else
      (add-repl-output!
       "tweak: select a single number or a complete expression — the selection isn't balanced (a stray bracket?)"))))

(defn- force-close!
  "Tear down the tweak UI and release the slot without restoring/re-evaluating.
   Used before a user-initiated definitions run (which re-evaluates anyway)."
  []
  (when @test-state
    (viewport/clear-preview!)
    (measure/clear-ruler-overrides!)
    (measure/refresh-rulers!)
    (cleanup-ui!)
    (modal/release!)
    (reset! test-state nil)))

;; ============================================================
;; Modal-evaluator registration
;; ============================================================

;; Tweak is the degenerate synchronous case of the two-phase pattern: it opens
;; inside start! (claim + deferred slider creation), so it never has a pending
;; request for the post-eval driver to fulfill — requested? is constantly false
;; and enter! is a no-op. It registers active?/cancel!/close! so the generic
;; driver in core.cljs can poll, cancel, and force-close it without naming this
;; module.
(modal/register-kind! :tweak
                      {:requested? (constantly false)
                       :enter!     (fn [])
                       :active?    active?
                       :cancel!    cancel!
                       :close!     force-close!})
