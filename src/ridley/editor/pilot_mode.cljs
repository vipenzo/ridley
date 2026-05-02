(ns ridley.editor.pilot-mode
  "Interactive pilot mode for mesh positioning.
   Evaluates (pilot :name) → enters interactive session where keyboard
   drives turtle commands. On confirm, replaces (pilot :name) with
   (attach! :name commands...) in the editor."
  (:require [sci.core :as sci]
            [ridley.editor.state :as state]
            [ridley.editor.codemirror :as cm]
            [ridley.scene.registry :as registry]
            [ridley.viewport.core :as viewport]
            [clojure.string :as str]))

;; Forward declarations for mutual references
(declare confirm! cancel! update-panel-display!)

;; ============================================================
;; State
;; ============================================================

(defonce pilot-state (atom nil))

;; When true, the next (pilot ...) macro invocation is silently ignored.
;; Used by cancel! to prevent re-entering pilot mode on re-eval.
(defonce ^:private skip-next-pilot (atom false))
;; When active:
;; {:source-expr   "cubo"          — source text of pilot argument
;;  :pilot-text    "(pilot :cubo)" — exact text in editor
;;  :commands      [[:f 10] ...]   — accumulated raw commands
;;  :step          5               — linear step size (mm)
;;  :angle-step    15              — angular step size (degrees)
;;  :scale-step    1.1             — scale multiplier per keypress
;;  :digit-buffer  ""              — accumulating digit input (vim-style count)
;;  :digit-target  :step           — :step, :angle, or :scale (cycled with Tab)
;;  :creation-pose {:position :heading :up}  — mesh's initial pose
;;  :after-text    "..."           — script text after pilot form
;;  :pilot-from    42              — char offset of pilot form start
;;  :pilot-to      56              — char offset of pilot form end
;;  :panel-el      <DOM>
;;  :key-handler   <fn>}

;; ============================================================
;; Helpers
;; ============================================================

(defn- cmd->code-str
  "Convert a single command to valid Clojure code.
   :cp-f → (cp-f ...), :scale [1.1 1 1] → (scale [1.1 1 1])"
  [[cmd val]]
  (cond
    (vector? val)
    (str "(" (name cmd) " [" (str/join " " val) "])")
    :else
    (str "(" (name cmd) " " val ")")))

(defn- commands->code-str
  "Convert command vector to display/code string."
  [commands]
  (str/join " " (map cmd->code-str commands)))

(defn- compact-commands
  "Collapse consecutive same-type commands and remove zero-sum pairs.
   (f 5) (f 5) → (f 10). (f 5) (f -5) → removed.
   Scale vectors are multiplied component-wise."
  [commands]
  (let [merged (reduce
                (fn [acc [cmd val :as c]]
                  (if (empty? acc)
                    [c]
                    (let [[prev-cmd prev-val] (peek acc)]
                      (if (= cmd prev-cmd)
                        (cond
                           ;; Both scalars — add
                          (and (number? val) (number? prev-val))
                          (let [sum (+ prev-val val)]
                            (if (< (Math/abs sum) 0.001)
                              (pop acc)
                              (conj (pop acc) [cmd sum])))
                           ;; Both scale vectors — multiply component-wise
                          (and (vector? val) (vector? prev-val))
                          (let [product (mapv * prev-val val)
                                trivial? (every? #(< (Math/abs (- % 1.0)) 0.001) product)]
                            (if trivial?
                              (pop acc)
                              (conj (pop acc) [cmd product])))
                          :else (conj acc c))
                        (conj acc c)))))
                []
                commands)]
    merged))

;; ============================================================
;; Partial re-evaluation
;; ============================================================

(defn- build-replacement-code
  "Build the code that replaces (pilot ...) during re-eval.
   Uses (attach expr (path cmds...)) to ensure commands like scale/cp-f
   are properly scoped inside a path context."
  [commands]
  (let [{:keys [source-expr]} @pilot-state
        cmds (commands->code-str commands)]
    (if (empty? commands)
      source-expr
      (str "(attach " source-expr " (path " cmds "))"))))

(defn- build-modified-script
  "Build the full editor script with (pilot ...) replaced by attach code."
  [commands]
  (let [{:keys [pilot-from pilot-to]} @pilot-state
        editor-text (cm/get-value)
        replacement (build-replacement-code commands)]
    (str (.substring editor-text 0 pilot-from)
         replacement
         (.substring editor-text pilot-to))))

(defn- eval-with-commands-repl!
  "REPL mode: evaluate replacement code and show preview without touching editor."
  [commands]
  (try
    (let [code (build-replacement-code commands)
          ctx @state/sci-ctx-ref
          result (sci/eval-string code ctx)]
      (when (and (map? result) (:creation-pose result))
        (viewport/update-turtle-pose (:creation-pose result))
        (viewport/show-wireframe-preview! result)))
    (catch :default e
      (js/console.warn "pilot eval error (repl):" (.-message e)))))

(defn- eval-with-commands-script!
  "Script mode: re-evaluate full editor script with (pilot ...) replaced."
  [commands]
  (try
    (let [modified-script (build-modified-script commands)]
      (registry/clear-all!)
      (state/reset-turtle!)
      (state/reset-scene-accumulator!)
      (state/reset-print-buffer!)
      (let [ctx @state/sci-ctx-ref]
        (reset! skip-next-pilot true)
        (sci/eval-string modified-script ctx))
      (let [{:keys [lines stamps]} @state/scene-accumulator]
        (registry/set-lines! (vec (or lines [])))
        (registry/set-stamps! (vec (or stamps []))))
      (registry/refresh-viewport! false)
      ;; Turtle + wireframe from SCI eval
      (let [replacement (build-replacement-code commands)
            mesh-result (try (sci/eval-string replacement @state/sci-ctx-ref)
                             (catch :default _ nil))]
        (when (and (map? mesh-result) (:creation-pose mesh-result))
          (viewport/update-turtle-pose (:creation-pose mesh-result))
          (viewport/show-wireframe-preview! mesh-result))))
    (catch :default e
      (js/console.warn "pilot eval error:" (.-message e)))))

(defn- eval-with-commands!
  "Re-evaluate with current pilot commands. Dispatches to REPL or script mode."
  []
  (when-let [{:keys [commands from-repl]} @pilot-state]
    (if from-repl
      (eval-with-commands-repl! commands)
      (eval-with-commands-script! commands))))

;; ============================================================
;; Command management
;; ============================================================

(defn- debounced-eval!
  "Debounced partial re-eval."
  []
  (when-let [t (:eval-timeout @pilot-state)]
    (js/clearTimeout t))
  (swap! pilot-state assoc :eval-timeout
         (js/setTimeout (fn []
                          (eval-with-commands!)
                          (update-panel-display!))
                        80)))

(defn- add-command!
  "Append a command and trigger re-eval."
  [cmd-type value]
  (swap! pilot-state update :commands conj [cmd-type value])
  (debounced-eval!))

(defn- undo-command!
  "Remove last command and trigger re-eval."
  []
  (when (seq (:commands @pilot-state))
    (swap! pilot-state update :commands pop)
    (debounced-eval!)))

;; ============================================================
;; Confirm / Cancel / Cleanup
;; ============================================================

(defn- cleanup!
  "Remove panel and key handler."
  []
  (when-let [panel (:panel-el @pilot-state)]
    (when-let [parent (.-parentNode panel)]
      (.removeChild parent panel)))
  (when-let [handler (:key-handler @pilot-state)]
    (.removeEventListener js/document "keydown" handler true))
  ;; Restore turtle source and clear preview
  (viewport/set-turtle-source! :global)
  (viewport/clear-preview!))

(defn confirm!
  "Confirm pilot mode: compute polished commands, replace in editor or print to REPL."
  []
  (when-let [{:keys [source-expr commands pilot-from pilot-to from-repl]} @pilot-state]
    (let [cmds (compact-commands commands)
          code (if (empty? cmds)
                 source-expr
                 (str "(attach " source-expr " (path " (commands->code-str cmds) "))"))]
      (if from-repl
        ;; REPL mode: just print the expression
        (state/capture-println code)
        ;; Script mode: replace in editor and re-evaluate
        (do (cm/replace-range pilot-from pilot-to code)
            (state/capture-println (str "pilot: " code))))
      (cleanup!)
      (state/release-interactive-mode!)
      (reset! pilot-state nil)
      (when-not from-repl
        (when-let [f @state/run-definitions-fn] (f))))))

(defn cancel!
  "Cancel pilot mode: restore original state."
  []
  (let [from-repl (:from-repl @pilot-state)]
    (cleanup!)
    (state/release-interactive-mode!)
    (reset! pilot-state nil)
    (when-not from-repl
      ;; Set skip flag so the (pilot ...) macro doesn't re-trigger
      (reset! skip-next-pilot true)
      ;; Re-evaluate original script to restore
      (when-let [f @state/run-definitions-fn] (f)))))

;; ============================================================
;; UI Panel
;; ============================================================

(defn update-panel-display!
  "Update the command display and step info in the panel."
  []
  (when-let [panel (:panel-el @pilot-state)]
    (let [buf (:digit-buffer @pilot-state)
          target (:digit-target @pilot-state)
          editing? (seq buf)
          ;; Helper: update value text and active indicator
          update-param! (fn [selector value-str param-key]
                          (when-let [el (.querySelector panel selector)]
                            (set! (.-textContent el)
                                  (if (and editing? (= target param-key))
                                    (str buf "_")
                                    value-str))
                            (let [span (.-parentNode el)]
                              (if (= target param-key)
                                (.add (.-classList span) "pilot-active-param")
                                (.remove (.-classList span) "pilot-active-param")))))]
      (update-param! ".pilot-step-value"
                     (str (:step @pilot-state) "mm") :step)
      (update-param! ".pilot-angle-value"
                     (str (:angle-step @pilot-state) "\u00B0") :angle)
      (update-param! ".pilot-scale-value"
                     (str "\u00D7" (:scale-step @pilot-state)) :scale))
    ;; Update command display
    (when-let [cmd-el (.querySelector panel ".pilot-commands")]
      (let [cmds (:commands @pilot-state)]
        (set! (.-textContent cmd-el)
              (if (empty? cmds)
                "(no commands yet)"
                (commands->code-str cmds)))))))

(defn- create-pilot-panel!
  "Create the pilot UI panel and insert into the REPL terminal."
  [mesh-name]
  (let [panel (.createElement js/document "div")]
    (set! (.-id panel) "pilot-panel")
    (set! (.-innerHTML panel)
          (str "<div class='pilot-header'>pilot " mesh-name "</div>"
               "<div class='pilot-controls'>"
               "<span>Step: <span class='pilot-step-value'>"
               (:step @pilot-state) "mm</span></span>"
               "<span>Angle: <span class='pilot-angle-value'>"
               (:angle-step @pilot-state) "\u00B0</span></span>"
               "<span>Scale: <span class='pilot-scale-value'>\u00D7"
               (:scale-step @pilot-state) "</span></span>"
               "</div>"
               "<div class='pilot-commands'>(no commands yet)</div>"
               "<div class='pilot-buttons'>"
               "<button class='pilot-btn pilot-btn-undo'>Undo</button>"
               "<button class='pilot-btn pilot-btn-ok'>OK</button>"
               "<button class='pilot-btn pilot-btn-cancel'>Cancel</button>"
               "</div>"))
    ;; Wire up buttons
    (.addEventListener (.querySelector panel ".pilot-btn-undo") "click"
                       (fn [_] (undo-command!)))
    (.addEventListener (.querySelector panel ".pilot-btn-ok") "click"
                       (fn [_] (confirm!)))
    (.addEventListener (.querySelector panel ".pilot-btn-cancel") "click"
                       (fn [_] (cancel!)))
    ;; Insert into repl-terminal before repl-input-line
    (when-let [terminal (.getElementById js/document "repl-terminal")]
      (when-let [input-line (.getElementById js/document "repl-input-line")]
        (.insertBefore terminal panel input-line)))
    panel))

;; ============================================================
;; Keyboard handler
;; ============================================================

(def ^:private code->digit
  {"Digit0" "0" "Digit1" "1" "Digit2" "2" "Digit3" "3" "Digit4" "4"
   "Digit5" "5" "Digit6" "6" "Digit7" "7" "Digit8" "8" "Digit9" "9"
   "Numpad0" "0" "Numpad1" "1" "Numpad2" "2" "Numpad3" "3" "Numpad4" "4"
   "Numpad5" "5" "Numpad6" "6" "Numpad7" "7" "Numpad8" "8" "Numpad9" "9"})

(defn- digit-code
  "If event code is a digit key, return the digit string. Else nil.
   Uses e.code (layout-independent) instead of e.key."
  [code]
  (code->digit code))

(defn- flush-digit-buffer!
  "Apply accumulated digit buffer as new step, angle, or scale value."
  []
  (let [buf (:digit-buffer @pilot-state)]
    (when (seq buf)
      (let [val (js/parseFloat buf)]
        (when (and (pos? val) (js/isFinite val))
          (case (:digit-target @pilot-state)
            :angle (swap! pilot-state assoc :angle-step val)
            :scale (swap! pilot-state assoc :scale-step val)
            (swap! pilot-state assoc :step val))))
      (swap! pilot-state assoc :digit-buffer "" :digit-target :step)
      (update-panel-display!))))

(defn- on-pilot-keydown [e]
  (when (:entered? @pilot-state)
    (let [key (.-key e)
          code (.-code e)
          shift? (.-shiftKey e)
          digit (digit-code code)]
      (cond
        ;; === Tab: cycle mode step → angle → scale ===
        ;; Mode controls both what digits edit AND what arrows do
        (= key "Tab")
        (do (.preventDefault e) (.stopPropagation e)
            (flush-digit-buffer!)
            (swap! pilot-state update :digit-target
                   {:step :angle, :angle :scale, :scale :step})
            (update-panel-display!))

        ;; === Digit input → edits the active parameter ===
        digit
        (do (.preventDefault e) (.stopPropagation e)
            (swap! pilot-state update :digit-buffer str digit)
            (update-panel-display!))

        ;; Decimal point in digit buffer
        (and (or (= key ".") (= key ">")) (seq (:digit-buffer @pilot-state)))
        (do (.preventDefault e) (.stopPropagation e)
            (when-not (str/includes? (:digit-buffer @pilot-state) ".")
              (swap! pilot-state update :digit-buffer str "."))
            (update-panel-display!))

        ;; === Arrow keys: behavior depends on active mode ===
        ;; STEP mode:  Up/Down=f, Left/Right=rt, Shift=u
        ;; ANGLE mode: Up/Down=tv, Left/Right=th, Shift=tr
        ;; SCALE mode: Up/Down=scale heading, Left/Right=scale right, Shift=scale up

        (and (= key "ArrowUp") (not shift?) (not (.-altKey e)))
        (let [m (:digit-target @pilot-state)
              s (:step @pilot-state)
              a (:angle-step @pilot-state)
              sc (:scale-step @pilot-state)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :f s)
            :angle (add-command! :tv a)
            :scale (add-command! :scale [sc 1 1])))

        (and (= key "ArrowDown") (not shift?) (not (.-altKey e)))
        (let [m (:digit-target @pilot-state)
              s (:step @pilot-state)
              a (:angle-step @pilot-state)
              sc (:scale-step @pilot-state)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :f (- s))
            :angle (add-command! :tv (- a))
            :scale (add-command! :scale [(/ 1.0 sc) 1 1])))

        (and (= key "ArrowLeft") (not shift?) (not (.-altKey e)))
        (let [m (:digit-target @pilot-state)
              s (:step @pilot-state)
              a (:angle-step @pilot-state)
              sc (:scale-step @pilot-state)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :rt (- s))
            :angle (add-command! :th a)
            :scale (add-command! :scale [1 (/ 1.0 sc) 1])))

        (and (= key "ArrowRight") (not shift?) (not (.-altKey e)))
        (let [m (:digit-target @pilot-state)
              s (:step @pilot-state)
              a (:angle-step @pilot-state)
              sc (:scale-step @pilot-state)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :rt s)
            :angle (add-command! :th (- a))
            :scale (add-command! :scale [1 sc 1])))

        ;; Shift+arrows: step=u, angle=tr, scale=scale up-axis
        (and (= key "ArrowUp") shift? (not (.-altKey e)))
        (let [m (:digit-target @pilot-state)
              s (:step @pilot-state)
              a (:angle-step @pilot-state)
              sc (:scale-step @pilot-state)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :u s)
            :angle (add-command! :tr a)
            :scale (add-command! :scale [1 1 sc])))

        (and (= key "ArrowDown") shift? (not (.-altKey e)))
        (let [m (:digit-target @pilot-state)
              s (:step @pilot-state)
              a (:angle-step @pilot-state)
              sc (:scale-step @pilot-state)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :u (- s))
            :angle (add-command! :tr (- a))
            :scale (add-command! :scale [1 1 (/ 1.0 sc)])))

        ;; === Alt+arrows: cp-* — slide geometry under a stationary creation-pose ===
        (and (= key "ArrowUp") (.-altKey e) (not shift?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-f (:step @pilot-state)))

        (and (= key "ArrowDown") (.-altKey e) (not shift?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-f (- (:step @pilot-state))))

        (and (= key "ArrowLeft") (.-altKey e) (not shift?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-rt (- (:step @pilot-state))))

        (and (= key "ArrowRight") (.-altKey e) (not shift?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-rt (:step @pilot-state)))

        (and (= key "ArrowUp") (.-altKey e) shift?)
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-u (:step @pilot-state)))

        (and (= key "ArrowDown") (.-altKey e) shift?)
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-u (- (:step @pilot-state))))

        ;; === Undo ===
        (= key "Backspace")
        (do (.preventDefault e) (.stopPropagation e)
            (if (seq (:digit-buffer @pilot-state))
              ;; Undo digit input first
              (do (swap! pilot-state update :digit-buffer
                         #(subs % 0 (max 0 (dec (count %)))))
                  (when (empty? (:digit-buffer @pilot-state))
                    (swap! pilot-state assoc :digit-target :step))
                  (update-panel-display!))
              ;; Undo last command
              (undo-command!)))

        ;; === Confirm ===
        (= key "Enter")
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (confirm!))

        ;; === Cancel ===
        (= key "Escape")
        (do (.preventDefault e) (.stopPropagation e) (cancel!))

        ;; Let everything else through
        :else nil))))

;; ============================================================
;; Entry points
;; ============================================================

(defn ^:export active?
  "Returns true if pilot mode is currently active."
  []
  (some? @pilot-state))

(defn ^:export request!
  "Called by the pilot macro during script evaluation.
   quoted-arg: the source form as written (unevaluated, e.g. 'cubo or '(get-mesh :cubo))
   value: the evaluated mesh value"
  [quoted-arg value]
  (if @skip-next-pilot
    ;; cancel!/re-eval set the skip flag — just pass through the mesh
    (do (reset! skip-next-pilot false) value)
    ;; Normal path
    (let [obj value
          sdf? (and (map? obj) (string? (:op obj)))
          mesh? (and (map? obj) (:vertices obj))]
      (when-not obj
        (throw (js/Error. (str "pilot: no object found for " quoted-arg))))
      (when-not (or mesh? sdf?)
        (throw (js/Error. (str "pilot: argument must be a mesh or SDF node"))))
      (state/claim-interactive-mode! :pilot)
      ;; Detect context from eval-source dynamic var
      (let [from-repl (not= :definitions @state/eval-source-var)
            ;; In script mode, find the (pilot ...) form in editor text
            pilot-pattern (str "(pilot " quoted-arg ")")
            [pilot-from pilot-to]
            (when-not from-repl
              (let [editor-text (cm/get-value)
                    idx (.indexOf editor-text pilot-pattern)]
                (when (>= idx 0)
                  [idx (+ idx (count pilot-pattern))])))]
        (when (and (not from-repl) (nil? pilot-from))
          (throw (js/Error. (str "pilot: cannot find '" pilot-pattern "' in editor"))))
        ;; Store request — will be picked up by core.cljs after eval
        (reset! pilot-state
                {:source-expr   (str quoted-arg) ;; source text of the argument
                 :pilot-text    pilot-pattern
                 :commands      []
                 :step          5
                 :angle-step    15
                 :scale-step    1.1
                 :digit-buffer  ""
                 :digit-target  :step
                 :creation-pose (or (:creation-pose obj)
                                    {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
                 :original-mesh obj
                 :from-repl     from-repl
                 :pilot-from    pilot-from
                 :pilot-to      pilot-to
                 :entered?      false})
        ;; Return object so (pilot x) acts as passthrough in first eval
        obj))))

(defn requested?
  "Check if pilot mode was requested during evaluation."
  []
  (and (some? @pilot-state)
       (not (:entered? @pilot-state))))

(defn enter!
  "Enter interactive pilot mode. Called by core.cljs after evaluation completes."
  []
  (when (requested?)
    (swap! pilot-state assoc :entered? true)
    ;; Install keyboard handler (capture phase to intercept before editor)
    (let [handler on-pilot-keydown]
      (swap! pilot-state assoc :key-handler handler)
      (.addEventListener js/document "keydown" handler true))
    ;; Show turtle indicator on the piloted mesh's creation-pose
    (viewport/set-turtle-source! {:custom (:creation-pose @pilot-state)})
    (viewport/update-turtle-pose (:creation-pose @pilot-state))
    (viewport/set-turtle-visible true)
    ;; Update dropdown to show "Pilot" instead of "Global"
    (when-let [sel (.getElementById js/document "turtle-source-select")]
      (let [opt (.createElement js/document "option")]
        (set! (.-value opt) "custom")
        (set! (.-textContent opt) "Turtle: Pilot")
        (.appendChild sel opt)
        (set! (.-value sel) "custom")))
    ;; Create UI panel
    (let [panel (create-pilot-panel! (:source-expr @pilot-state))]
      (swap! pilot-state assoc :panel-el panel))
    (state/capture-println
     (str "pilot: interactive mode for " (:source-expr @pilot-state)
          " — arrows to move, Shift+arrows to rotate, Enter to confirm, Esc to cancel"))))
