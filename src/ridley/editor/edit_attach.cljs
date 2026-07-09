(ns ridley.editor.edit-attach
  "Interactive editor for `attach` — the mesh/SDF transform. Evaluates
   `(edit-attach mesh & body)` → enters an interactive session where keyboard
   commands append to the (possibly already non-empty) body. On confirm, the
   editor is rewritten to the canonical flat `(attach mesh cmd1 cmd2 …)` form,
   completing the family's `edit-X ↔ X` round-trip (edit-path ↔ path,
   edit-path-2d ↔ path-2d, edit-image-board ↔ image-board).

   `pilot` is a legacy alias: `(pilot mesh)` delegates to edit-attach with an
   empty pre-existing body — same session, same output grammar."
  (:require [sci.core :as sci]
            [ridley.editor.state :as state]
            [ridley.editor.codemirror :as cm]
            [ridley.editor.modal-evaluator :as modal]
            [ridley.editor.gizmo :as gizmo]
            [ridley.viewport.core :as viewport]
            [clojure.string :as str]))

;; Forward declarations for mutual references
(declare confirm! cancel! update-panel-display!)

;; ============================================================
;; State
;; ============================================================

(defonce session (atom nil))

;; The skip flag that stops an edit-attach re-eval from re-entering the macro
;; lives in modal-evaluator (shared across all modal evaluators).
;; When active:
;; {:source-expr   "cubo"          — source text of the mesh argument
;;  :commands      [[:f 10] ...]   — live command list: verbatim items (opaque
;;                                   source-text strings, from a pre-existing
;;                                   body) interleaved with gestural [cmd val]
;;                                   pairs added during this session
;;  :orig-commands [...]           — the SAME list at session-open time, before
;;                                   any gesture — used to rebuild the exact
;;                                   pre-existing body on cancel
;;  :step          5               — linear step size (mm)
;;  :angle-step    15              — angular step size (degrees)
;;  :scale-step    1.1             — scale multiplier per keypress
;;  :digit-buffer  ""              — accumulating digit input (vim-style count)
;;  :digit-target  :step           — :step, :angle, or :scale (cycled with Tab)
;;  :creation-pose {:position :heading :up}  — mesh's current (attached) pose
;;  :original-mesh <value>         — the mesh/SDF BEFORE any attach was applied
;;  :edit-attach-from  42          — char offset of the marker form start
;;  :edit-attach-to    56          — char offset of the marker form end
;;  :panel-el      <DOM>
;;  :key-handler   <fn>}

;; ============================================================
;; Helpers
;; ============================================================

(defn- cmd->code-str
  "Convert a single command item to valid Clojure code. Verbatim items (source
   text preloaded from a pre-existing body) are opaque strings and pass
   through unchanged. Gestural items are [cmd val] pairs: :scale → per-axis
   stretch-f/stretch-rt/stretch-u (scale itself is not allowed inside attach),
   unit (≈1) axes dropped."
  [item]
  (if (string? item)
    item
    (let [[cmd val] item]
      (cond
        (= cmd :scale)
        (let [[a b c] val
              unit? (fn [x] (< (Math/abs (- x 1.0)) 0.001))]
          (->> [(when-not (unit? a) (str "(stretch-f " a ")"))
                (when-not (unit? b) (str "(stretch-rt " b ")"))
                (when-not (unit? c) (str "(stretch-u " c ")"))]
               (remove nil?)
               (str/join " ")))
        (vector? val)
        (str "(" (name cmd) " [" (str/join " " val) "])")
        :else
        (str "(" (name cmd) " " val ")")))))

(defn- commands->code-str
  "Convert the command vector to display/code string."
  [commands]
  (str/join " " (remove str/blank? (map cmd->code-str commands))))

(defn- compact-commands
  "Collapse consecutive same-type GESTURAL commands and remove zero-sum pairs.
   (f 5) (f 5) → (f 10). (f 5) (f -5) → removed. Scale vectors are multiplied
   component-wise. Verbatim items (pre-existing body forms, opaque strings)
   never merge and interrupt the merge chain on either side — a pre-existing
   command is preserved exactly and is never folded into a neighboring
   gesture."
  [commands]
  (reduce
   (fn [acc c]
     (cond
       (empty? acc) [c]
       (string? c) (conj acc c)
       (string? (peek acc)) (conj acc c)
       :else
       (let [[cmd val] c
             [prev-cmd prev-val] (peek acc)]
         (if (= cmd prev-cmd)
           (cond
             (and (number? val) (number? prev-val))
             (let [sum (+ prev-val val)]
               (if (< (Math/abs sum) 0.001)
                 (pop acc)
                 (conj (pop acc) [cmd sum])))
             (and (vector? val) (vector? prev-val))
             (let [product (mapv * prev-val val)
                   trivial? (every? #(< (Math/abs (- % 1.0)) 0.001) product)]
               (if trivial?
                 (pop acc)
                 (conj (pop acc) [cmd product])))
             :else (conj acc c))
           (conj acc c)))))
   []
   commands))

;; ============================================================
;; Marker location — paren-balanced, tolerant of multi-line bodies
;; ============================================================

(def ^:private marker-prefix "(edit-attach")
(def ^:private alias-prefix "(pilot")

(defn- find-marker
  "Locate the marker form in `text` for `marker-kind` (:edit-attach or
   :pilot). Returns [from to] or nil. Each macro searches only its OWN
   prefix — never both — so an edit-attach form elsewhere in the document
   can't be mistaken for a live pilot-alias invocation, or vice versa."
  [text marker-kind]
  (modal/find-form-bounds text (if (= marker-kind :pilot) alias-prefix marker-prefix)))

;; ============================================================
;; Code generation
;; ============================================================

(defn- build-code
  "Build the (attach mesh-expr cmds...) code for `commands`, or just
   mesh-expr when there are none. attach already wraps its body in (path …),
   so no extra wrapper is needed here (verified: a flat body and a
   path-wrapped body evaluate to the same mesh)."
  [source-expr commands]
  (let [cmds (commands->code-str commands)]
    (if (empty? commands)
      source-expr
      (str "(attach " source-expr " " cmds ")"))))

(defn- build-modified-script
  "Build the full editor script with the marker replaced by the attach code
   for the given commands."
  [commands]
  (let [{:keys [source-expr edit-attach-from edit-attach-to]} @session]
    (modal/splice-source (cm/get-value) edit-attach-from edit-attach-to
                         (build-code source-expr commands))))

(defn- eval-with-commands-repl!
  "REPL mode: evaluate replacement code and show preview without touching editor."
  [commands]
  (try
    (let [{:keys [source-expr]} @session
          code (build-code source-expr commands)
          ctx @state/sci-ctx-ref
          result (sci/eval-string code ctx)]
      (when (and (map? result) (:creation-pose result))
        (viewport/update-turtle-pose (:creation-pose result))
        (gizmo/update-pose! (:creation-pose result))
        (viewport/show-wireframe-preview! result)))
    (catch :default e
      (js/console.warn "edit-attach eval error (repl):" (.-message e)))))

(defn- eval-with-commands-script!
  "Script mode: re-evaluate full editor script with the marker replaced. The
   shared re-eval boilerplate (clear scene, reset turtle, arm skip flag, eval,
   push scene, refresh) lives in modal-evaluator; here we add only the
   edit-attach-specific turtle + wireframe preview from a second eval of the
   replacement."
  [commands]
  ;; arm-skip? = false: build-modified-script ALWAYS replaces the marker with
  ;; literal attach code, so this preview never re-enters request! and the skip
  ;; flag would be left armed — leaking across confirm!/cancel! into the next
  ;; edit-attach reinvocation, which would then silently pass through instead of
  ;; opening (the reentry glitch). Every sibling editor (edit-path,
  ;; edit-image-board, edit-bezier) passes false here for the same reason.
  (when (modal/reeval-script! #(build-modified-script commands) "edit-attach eval error:" false)
    (let [{:keys [source-expr]} @session
          replacement (build-code source-expr commands)
          mesh-result (try (sci/eval-string replacement @state/sci-ctx-ref)
                           (catch :default _ nil))]
      (when (and (map? mesh-result) (:creation-pose mesh-result))
        (viewport/update-turtle-pose (:creation-pose mesh-result))
        (gizmo/update-pose! (:creation-pose mesh-result))
        (viewport/show-wireframe-preview! mesh-result)))))

(defn- eval-with-commands!
  "Re-evaluate with current commands. Dispatches to REPL or script mode."
  []
  (when-let [{:keys [commands from-repl]} @session]
    (if from-repl
      (eval-with-commands-repl! commands)
      (eval-with-commands-script! commands))))

;; ============================================================
;; Command management
;; ============================================================

(defn- debounced-eval!
  "Debounced partial re-eval."
  []
  (when-let [t (:eval-timeout @session)]
    (js/clearTimeout t))
  (swap! session assoc :eval-timeout
         (js/setTimeout (fn []
                          (eval-with-commands!)
                          (update-panel-display!))
                        80)))

(defn- add-command!
  "Append a gestural command and trigger re-eval."
  [cmd-type value]
  (swap! session update :commands conj [cmd-type value])
  (debounced-eval!))

(defn- undo-command!
  "Remove the last item (verbatim or gestural) and trigger re-eval. Popping a
   pre-existing verbatim command is exactly as legitimate as popping a
   gesture just added — the panel always shows the full list, so the user
   can see what they're about to lose."
  []
  (when (seq (:commands @session))
    (swap! session update :commands pop)
    (debounced-eval!)))

;; ============================================================
;; Confirm / Cancel / Cleanup
;; ============================================================

(defn- cleanup!
  "Remove panel, key handler, and the drag gizmo."
  []
  (modal/unmount-panel! (:panel-el @session))
  (modal/remove-keydown! (:key-handler @session))
  (gizmo/close!)
  ;; Restore turtle source and clear preview
  (viewport/set-turtle-source! :global)
  (viewport/clear-preview!))

(defn confirm!
  "Confirm the session: compact gestures, keep verbatim items untouched,
   replace in editor or print to REPL."
  []
  (when-let [{:keys [source-expr commands edit-attach-from edit-attach-to from-repl]} @session]
    (let [cmds (compact-commands commands)
          code (build-code source-expr cmds)]
      (if from-repl
        ;; REPL mode: just print the expression
        (state/capture-println code)
        ;; Script mode: replace in editor and re-evaluate
        (do (modal/replace-source! edit-attach-from edit-attach-to code)
            (state/capture-println (str "edit-attach: " code))))
      (cleanup!)
      (modal/release!)
      (reset! session nil)
      (when-not from-repl
        (modal/run-definitions!)))))

(defn cancel!
  "Cancel the session: rewrite the marker down to its pre-existing form
   — (edit-attach mesh body...) → (attach mesh body...), or bare mesh-expr
   when body was empty (including the pilot alias, which is always empty at
   entry) — using the ORIGINAL commands, discarding any gesture added during
   the session. No skip flag is armed: since the rewritten source no longer
   contains an edit-* head, the next run has no session to reopen."
  []
  (let [{:keys [source-expr orig-commands edit-attach-from edit-attach-to from-repl]} @session]
    (when-not from-repl
      (modal/replace-source! edit-attach-from edit-attach-to
                             (build-code source-expr orig-commands)))
    (cleanup!)
    (modal/release!)
    (reset! session nil)
    (when-not from-repl
      (modal/run-definitions!))))

;; ============================================================
;; UI Panel
;; ============================================================

(defn update-panel-display!
  "Update the command display and step info in the panel."
  []
  (when-let [panel (:panel-el @session)]
    (let [buf (:digit-buffer @session)
          target (:digit-target @session)
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
                     (str (:step @session) "mm") :step)
      (update-param! ".pilot-angle-value"
                     (str (:angle-step @session) "°") :angle)
      (update-param! ".pilot-scale-value"
                     (str "×" (:scale-step @session)) :scale))
    ;; Update command display (the list div — NOT the .modal-help cheatsheet, which
    ;; also carries .pilot-commands for styling; query the list's own class)
    (when-let [cmd-el (.querySelector panel ".pilot-cmd-list")]
      (let [cmds (:commands @session)]
        (set! (.-textContent cmd-el)
              (if (empty? cmds)
                "(no commands yet)"
                (commands->code-str cmds)))))
    ;; Update object/origin mode toggle
    (when-let [mode-btn (.querySelector panel ".pilot-btn-mode")]
      (let [origin? (= (:gizmo-mode @session) :origin)]
        (set! (.-textContent mode-btn) (if origin? "Mode: Origin" "Mode: Object"))
        (if origin?
          (.add (.-classList mode-btn) "pilot-mode-origin")
          (.remove (.-classList mode-btn) "pilot-mode-origin"))))))

(defn- toggle-gizmo-mode!
  "Object/origin toggle for the drag gizmo (brief §5) — a visible, persistent panel
   button, not a hidden modifier key, since it changes what the same gestures mean."
  []
  (let [new-mode (if (= (:gizmo-mode @session) :origin) :object :origin)]
    (swap! session assoc :gizmo-mode new-mode)
    (gizmo/set-mode! new-mode)
    (update-panel-display!)))

(defn- create-edit-attach-panel!
  "Create the edit-attach UI panel and insert into the REPL terminal."
  [mesh-name]
  (let [panel (.createElement js/document "div")]
    (set! (.-id panel) "edit-attach-panel")
    (set! (.-innerHTML panel)
          (str "<div class='pilot-header'>edit-attach " mesh-name "</div>"
               "<div class='pilot-controls'>"
               "<span>Step: <span class='pilot-step-value'>"
               (:step @session) "mm</span></span>"
               "<span>Angle: <span class='pilot-angle-value'>"
               (:angle-step @session) "°</span></span>"
               "<span>Scale: <span class='pilot-scale-value'>×"
               (:scale-step @session) "</span></span>"
               "<button class='pilot-btn pilot-btn-mode'>Mode: Object</button>"
               "</div>"
               "<div class='pilot-commands pilot-cmd-list'>(no commands yet)</div>"
               ;; keyboard help (collapsed behind the header "?" toggle, wired
               ;; generically by modal/mount-panel!)
               "<div class='pilot-commands modal-help'>"
               "Tab: cycle step/angle/scale · digits: set active value · "
               "←→↑↓: move (step) / rotate (angle) / stretch (scale) · "
               "Shift+↑↓: u / tr / stretch-u · "
               "Alt+←→↑↓: cp slide geometry under the pose (Alt+Shift+↑↓: cp-u) · "
               "Backspace: undo · Enter: OK · Esc: cancel · Mode: Object↔Origin "
               "(who moves — mesh vs geometry-under-pose). "
               "Gizmo: drag arrows/rings/stretch cubes · Shift+drag: free (bypass snap) · "
               "readout shows the next command's value. "
               "Origin mode: click a mesh vertex to snap the pose onto it "
               "(Alt+click: nearest face point to the pose) — up to 3 cp = up to 3 undo."
               "</div>"
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
    (.addEventListener (.querySelector panel ".pilot-btn-mode") "click"
                       (fn [_] (toggle-gizmo-mode!)))
    ;; Insert into repl-terminal before repl-input-line
    (modal/mount-panel! panel)
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
  (let [buf (:digit-buffer @session)]
    (when (seq buf)
      (let [val (js/parseFloat buf)]
        (when (and (pos? val) (js/isFinite val))
          (case (:digit-target @session)
            :angle (swap! session assoc :angle-step val)
            :scale (swap! session assoc :scale-step val)
            (swap! session assoc :step val))
          (gizmo/set-snap! (select-keys @session [:step :angle-step :scale-step]))))
      (swap! session assoc :digit-buffer "" :digit-target :step)
      (update-panel-display!))))

(defn- on-edit-attach-keydown [e]
  (when (:entered? @session)
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
            (swap! session update :digit-target
                   {:step :angle, :angle :scale, :scale :step})
            (update-panel-display!))

        ;; === Digit input → edits the active parameter ===
        digit
        (do (.preventDefault e) (.stopPropagation e)
            (swap! session update :digit-buffer str digit)
            (update-panel-display!))

        ;; Decimal point in digit buffer
        (and (or (= key ".") (= key ">")) (seq (:digit-buffer @session)))
        (do (.preventDefault e) (.stopPropagation e)
            (when-not (str/includes? (:digit-buffer @session) ".")
              (swap! session update :digit-buffer str "."))
            (update-panel-display!))

        ;; === Arrow keys: behavior depends on active mode ===
        ;; STEP mode:  Up/Down=f, Left/Right=rt, Shift=u
        ;; ANGLE mode: Up/Down=tv, Left/Right=th, Shift=tr
        ;; SCALE mode: Up/Down=scale heading, Left/Right=scale right, Shift=scale up

        (and (= key "ArrowUp") (not shift?) (not (.-altKey e)))
        (let [m (:digit-target @session)
              s (:step @session)
              a (:angle-step @session)
              sc (:scale-step @session)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :f s)
            :angle (add-command! :tv a)
            :scale (add-command! :scale [sc 1 1])))

        (and (= key "ArrowDown") (not shift?) (not (.-altKey e)))
        (let [m (:digit-target @session)
              s (:step @session)
              a (:angle-step @session)
              sc (:scale-step @session)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :f (- s))
            :angle (add-command! :tv (- a))
            :scale (add-command! :scale [(/ 1.0 sc) 1 1])))

        (and (= key "ArrowLeft") (not shift?) (not (.-altKey e)))
        (let [m (:digit-target @session)
              s (:step @session)
              a (:angle-step @session)
              sc (:scale-step @session)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :rt (- s))
            :angle (add-command! :th a)
            :scale (add-command! :scale [1 (/ 1.0 sc) 1])))

        (and (= key "ArrowRight") (not shift?) (not (.-altKey e)))
        (let [m (:digit-target @session)
              s (:step @session)
              a (:angle-step @session)
              sc (:scale-step @session)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :rt s)
            :angle (add-command! :th (- a))
            :scale (add-command! :scale [1 sc 1])))

        ;; Shift+arrows: step=u, angle=tr, scale=scale up-axis
        (and (= key "ArrowUp") shift? (not (.-altKey e)))
        (let [m (:digit-target @session)
              s (:step @session)
              a (:angle-step @session)
              sc (:scale-step @session)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :u s)
            :angle (add-command! :tr a)
            :scale (add-command! :scale [1 1 sc])))

        (and (= key "ArrowDown") shift? (not (.-altKey e)))
        (let [m (:digit-target @session)
              s (:step @session)
              a (:angle-step @session)
              sc (:scale-step @session)]
          (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
          (case m
            :step  (add-command! :u (- s))
            :angle (add-command! :tr (- a))
            :scale (add-command! :scale [1 1 (/ 1.0 sc)])))

        ;; === Alt+arrows: cp-* — slide geometry under a stationary creation-pose ===
        (and (= key "ArrowUp") (.-altKey e) (not shift?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-f (:step @session)))

        (and (= key "ArrowDown") (.-altKey e) (not shift?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-f (- (:step @session))))

        (and (= key "ArrowLeft") (.-altKey e) (not shift?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-rt (- (:step @session))))

        (and (= key "ArrowRight") (.-altKey e) (not shift?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-rt (:step @session)))

        (and (= key "ArrowUp") (.-altKey e) shift?)
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-u (:step @session)))

        (and (= key "ArrowDown") (.-altKey e) shift?)
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (add-command! :cp-u (- (:step @session))))

        ;; === Undo ===
        (= key "Backspace")
        (do (.preventDefault e) (.stopPropagation e)
            (if (seq (:digit-buffer @session))
              ;; Undo digit input first
              (do (swap! session update :digit-buffer
                         #(subs % 0 (max 0 (dec (count %)))))
                  (when (empty? (:digit-buffer @session))
                    (swap! session assoc :digit-target :step))
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
  "Returns true if the edit-attach session is currently active."
  []
  (some? @session))

(defn- describe-arg
  "Render a short description of what the mesh argument actually is,
   so the user can tell apart 'a function' vs 'a number' vs 'nil'."
  [v]
  (cond
    (nil? v)     "nil"
    (fn? v)      "a function (did you forget to call it?)"
    (number? v)  "a number"
    (string? v)  "a string"
    (keyword? v) "a keyword"
    (vector? v)  "a vector"
    (map? v)     "a map (not a mesh or SDF node)"
    :else        (str (type v))))

(defn- clear-orphan-state!
  "Reset stuck session state from a previous failed eval.
   If interactive-mode is :edit-attach but no live UI exists, release everything."
  []
  (let [s @session
        live? (and s (:entered? s)
                   (some-> (:panel-el s) .-parentNode))]
    (when (and (= :edit-attach @state/interactive-mode) (not live?))
      (when s (cleanup!))
      (reset! session nil)
      (modal/release!))))

(defn ^:export request!
  "Called by the edit-attach macro (and its pilot alias) during script
   evaluation.
   quoted-mesh:    the mesh source form as written (unevaluated, e.g. 'cubo)
   mesh-value:     the evaluated mesh/SDF value
   quoted-body:    unevaluated seq of pre-existing body command forms (empty
                   for a bare mesh, or for the pilot alias)
   attached-value: mesh-value with quoted-body already applied via attach —
                   the value the CURRENT eval produces/previews, so the
                   session opens on the scene as it already stands
   marker-kind:    :edit-attach or :pilot — which literal head this
                   invocation was written with, so the marker search targets
                   only that head's own occurrences"
  [quoted-mesh mesh-value quoted-body attached-value marker-kind]
  (if (modal/consume-skip!)
    ;; The skip flag was armed by a modal re-eval and never consumed within its
    ;; own cycle. With the live preview now passing arm-skip? = false this should
    ;; not happen in normal flow, so leave a trace instead of silently swallowing
    ;; the reopen — this is the exact failure that made the reentry glitch
    ;; undiagnosable on the spot (see code-issues.md).
    (do (js/console.warn
         "edit-attach: skip flag was armed on request! — passing through without opening a session; a modal re-eval likely leaked the flag")
        attached-value)
    ;; Normal path
    (let [_ (clear-orphan-state!)
          sdf? (and (map? mesh-value) (string? (:op mesh-value)))
          mesh? (and (map? mesh-value) (:vertices mesh-value))]
      (when-not mesh-value
        (throw (js/Error. (str "edit-attach: no object found for " quoted-mesh))))
      (when-not (or mesh? sdf?)
        (throw (js/Error. (str "edit-attach: argument must be a mesh or SDF node — got "
                               (describe-arg mesh-value)))))
      (let [from-repl (not= :definitions @state/eval-source-var)
            [source-expr commands edit-attach-from edit-attach-to]
            (if from-repl
              [(str quoted-mesh) (mapv str quoted-body) nil nil]
              (let [text (cm/get-value)
                    [from to] (find-marker text marker-kind)]
                (when (nil? from)
                  (throw (js/Error. (str "edit-attach: cannot find '"
                                         (if (= marker-kind :pilot) alias-prefix marker-prefix)
                                         " …)' in editor"))))
                (let [form-text (subs text from to)
                      elements (cm/parse-form-elements form-text)
                      mesh-text (second elements)
                      cmd-texts (vec (drop 2 elements))]
                  [mesh-text cmd-texts from to])))]
        ;; All validation passed — claim slot and store request.
        ;; Claim is the LAST possible failure point so no error can leave it dangling.
        (modal/claim! :edit-attach)
        (reset! session
                {:source-expr      source-expr
                 :commands         commands
                 :orig-commands    commands
                 :step             5
                 :angle-step       15
                 :scale-step       1.1
                 :gizmo-mode       :object
                 :digit-buffer     ""
                 :digit-target     :step
                 :creation-pose    (or (:creation-pose attached-value)
                                       {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
                 :original-mesh    mesh-value
                 :from-repl        from-repl
                 :edit-attach-from edit-attach-from
                 :edit-attach-to   edit-attach-to
                 :entered?         false})
        ;; Return the attached value so (edit-attach mesh body...) previews
        ;; the current, already-transformed scene during the first eval.
        attached-value))))

(defn requested?
  "Check if an edit-attach session was requested during evaluation."
  []
  (and (some? @session)
       (not (:entered? @session))))

(defn enter!
  "Enter interactive edit-attach mode. Called by core.cljs after evaluation completes."
  []
  (when (requested?)
    (swap! session assoc :entered? true)
    ;; Install keyboard handler (capture phase to intercept before editor)
    (let [handler on-edit-attach-keydown]
      (swap! session assoc :key-handler handler)
      (modal/install-keydown! handler))
    ;; Show turtle indicator on the mesh's current (attached) creation-pose
    (viewport/set-turtle-source! {:custom (:creation-pose @session)})
    (viewport/update-turtle-pose (:creation-pose @session))
    (viewport/set-turtle-visible true)
    ;; Drag gizmo — object mode only for now; on-commit is the same add-command!
    ;; path a keyboard arrow press already uses.
    (gizmo/enter! (:creation-pose @session)
                  {:mode (:gizmo-mode @session)
                   :step (:step @session)
                   :angle-step (:angle-step @session)
                   :scale-step (:scale-step @session)}
                  {:on-commit add-command!})
    ;; Update dropdown to show "Edit-Attach" instead of "Global"
    (when-let [sel (.getElementById js/document "turtle-source-select")]
      (let [opt (.createElement js/document "option")]
        (set! (.-value opt) "custom")
        (set! (.-textContent opt) "Turtle: Edit-Attach")
        (.appendChild sel opt)
        (set! (.-value sel) "custom")))
    ;; Create UI panel and render the pre-existing (inherited) command list right
    ;; away — otherwise the panel shows the static "(no commands yet)" until the
    ;; first gesture triggers update-panel-display!.
    (let [panel (create-edit-attach-panel! (:source-expr @session))]
      (swap! session assoc :panel-el panel)
      (update-panel-display!))
    (state/capture-println
     (str "edit-attach: interactive mode for " (:source-expr @session)
          " — arrows to move, Shift+arrows to rotate, drag the gizmo handles in the "
          "viewport (arrows/rings/stretch — Shift+drag for free movement). In Origin "
          "mode, click a mesh vertex to snap the pose onto it (Alt+click = the face "
          "point nearest the pose); this emits up to 3 cp commands, so up to 3 "
          "Backspace to undo. Enter to confirm, Esc to cancel"))))

(defn- force-close!
  "Tear down the UI and release the slot without re-evaluating. Used before
   a user-initiated definitions run (which re-evaluates anyway)."
  []
  (when @session
    (cleanup!)
    (modal/release!)
    (reset! session nil)))

;; ============================================================
;; Modal-evaluator registration
;; ============================================================

;; edit-attach is a deferred two-phase session: request! runs during the eval
;; and returns the current attached value without installing the handler; the
;; post-eval driver in core.cljs calls enter! once the eval completes. It
;; registers all hooks so the generic driver can poll/enter/cancel/close it
;; without naming this module.
(modal/register-kind! :edit-attach
                      {:requested? requested?
                       :enter!     enter!
                       :active?    active?
                       :cancel!    cancel!
                       :close!     force-close!})
