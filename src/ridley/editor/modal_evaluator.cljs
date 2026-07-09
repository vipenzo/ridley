(ns ridley.editor.modal-evaluator
  "Shared skeleton for modal evaluators — interactive DSL sessions that open
   during/after an eval, preview live in the viewport, and rewrite the source on
   confirm. Concrete sessions (tweak, edit-attach, edit-bezier) provide only their
   specific logic; this layer owns the mechanics they all share:

   - the central mutex (one modal session at a time), re-exported from state
   - the skip flag that stops a session's own re-eval from re-entering its macro
   - mounting/unmounting the DOM panel under the REPL input line
   - installing/removing the global keydown handler (capture phase by default)
   - the script-mode re-eval boilerplate (clear scene, reset turtle, eval, refresh)
   - committing the rewritten source (cm/replace-range + run-definitions)
   - the two-phase entry driver (request! during eval, enter! after eval).

   Two-phase entry (Architecture §11.2.4, §15.2.1). In Ridley the eval owns the
   flow and the session waits for it. A deferred session (edit-attach, edit-bezier)
   returns a value to the in-flight eval from request! WITHOUT installing its
   handler, and the post-eval driver in core.cljs — (requested?) then (enter!) —
   installs it once the eval completes. Tweak is the degenerate synchronous case:
   it opens inside its own start! and registers requested? → false, so the generic
   driver skips it. Either way the driver here is the single owner of that loop;
   core.cljs no longer names any concrete module."
  (:require [sci.core :as sci]
            [ridley.editor.state :as state]
            [ridley.editor.codemirror :as cm]
            [ridley.scene.registry :as registry]))

;; ============================================================
;; Skip flag — shared across all modal evaluators
;; ============================================================

;; When armed, the next modal macro invocation (tweak-start!, edit-attach-request!, …)
;; passes through silently instead of opening a session. Armed by a session's own
;; re-eval / cancel so the macro it re-runs doesn't recursively re-enter. Only one
;; session is ever active (mutex), so a single shared flag is sufficient.
(defonce ^:private skip-next (atom false))

(defn arm-skip!
  "Arm the skip flag so the next modal macro invocation passes through."
  []
  (reset! skip-next true))

(defn consume-skip!
  "If the skip flag is armed, disarm it and return true; otherwise return false."
  []
  (if @skip-next
    (do (reset! skip-next false) true)
    false))

;; Transient-tweak flag: set by the editor's "Tweak this value" command, which
;; auto-wraps a selection in (tweak …). It carries the ORIGINAL selected text so
;; that cancelling the session can restore it (unwrap the auto-inserted wrapper),
;; plus the wrapper's position (`from`/`wrapped`) so that a FAILED wrap — one that
;; never opened a session because the modified script errored — can be rolled back
;; (see restore-failed-tweak-transient!). Confirm needs nothing extra — tweak
;; already bakes the value over the wrapper.
;; Value: {:restore text :from int :wrapped str} or nil.
(defonce ^:private tweak-transient (atom nil))

(defn arm-tweak-transient!
  "Mark the next tweak session as transient (auto-wrapped by the editor command).
   `restore-text` is the original selection to put back if the session is
   cancelled. The optional `from`/`wrapped` locate the inserted (tweak …) wrapper
   so a failed eval (the wrap never opened a session) can be rolled back."
  ([restore-text] (arm-tweak-transient! restore-text nil nil))
  ([restore-text from wrapped]
   (reset! tweak-transient {:restore restore-text :from from :wrapped wrapped})))

(defn consume-tweak-transient!
  "Return the pending transient restore-text (clearing the whole transient), or
   nil if none. Called by tweak start! once a session successfully opens, so the
   wrap is no longer pending and restore-failed-tweak-transient! becomes a no-op."
  []
  (let [v @tweak-transient]
    (reset! tweak-transient nil)
    (:restore v)))

(defn restore-failed-tweak-transient!
  "Safety net for the editor's auto-wrap. If a transient tweak is still pending
   AFTER a definitions run — i.e. start! never consumed it because the wrapped
   script failed to evaluate, so no session opened — roll the (tweak …) wrapper
   back to the original selection text in the editor. Returns true if it restored,
   false (no-op) otherwise. The caller re-runs definitions to refresh the viewport
   from the now-clean source."
  []
  (when-let [{:keys [restore from wrapped]} @tweak-transient]
    (reset! tweak-transient nil)
    (when (and from wrapped restore)
      (cm/replace-range from (+ from (count wrapped)) restore)
      true)))

;; ============================================================
;; Mutex — one modal session at a time
;; ============================================================

(defn claim!
  "Claim the single interactive-mode slot for `kind`. Throws (same exception as
   today) if another modal session is already active. On a successful claim the
   editor is made read-only, so user edits can't invalidate the source rewrite
   the session performs on confirm."
  [kind]
  (state/claim-interactive-mode! kind)   ;; throws if busy — read-only only on success
  (cm/set-read-only! true))

(defn release!
  "Release the interactive-mode slot and make the editor editable again. Every
   teardown path (confirm! / cancel! / force-close!) routes through here, so the
   editor is always restored."
  []
  (cm/set-read-only! false)
  (state/release-interactive-mode!))

;; ============================================================
;; DOM panel — mounted under the REPL input line
;; ============================================================

(defn mount-panel!
  "Insert a session panel into the REPL terminal, just above the input line.
   Tags it with the shared `modal-panel` class so every modal evaluator's panel
   picks up the same styling (see public/css/style.css). Returns the panel
   element."
  [panel-el]
  (.add (.-classList panel-el) "modal-panel")
  ;; Declutter: any element tagged `.modal-help` (the keyboard-shortcut cheatsheet) is
  ;; collapsed by default and revealed by a small "?" toggle dropped into the header.
  ;; Editors opt in just by adding the class — the toggle is wired here, once.
  (when-let [^js help (.querySelector panel-el ".modal-help")]
    (set! (.. help -style -display) "none")
    (when-let [^js header (.querySelector panel-el ".pilot-header")]
      (let [^js btn (.createElement js/document "button")]
        (set! (.-type btn) "button")
        (set! (.-className btn) "modal-help-toggle")
        (set! (.-textContent btn) "?")
        (set! (.-title btn) "Keyboard shortcuts")
        (.addEventListener btn "click"
                           (fn [^js e]
                             (.preventDefault e)
                             (let [hidden? (= "none" (.. help -style -display))]
                               (set! (.. help -style -display) (if hidden? "block" "none"))
                               (.toggle (.-classList btn) "active" hidden?))))
        (.appendChild header btn))))
  (when-let [terminal (.getElementById js/document "repl-terminal")]
    (when-let [input-line (.getElementById js/document "repl-input-line")]
      (.insertBefore terminal panel-el input-line)))
  panel-el)

(defn unmount-panel!
  "Remove a session panel from its parent, if mounted."
  [panel-el]
  (when (and panel-el (.-parentNode panel-el))
    (.removeChild (.-parentNode panel-el) panel-el)))

;; ============================================================
;; Global keydown handler
;; ============================================================

(defn install-keydown!
  "Install a global keydown handler. Capture phase by default (intercepts before
   the editor); pass capture? false for a plain bubble-phase listener. Returns the
   handler so the caller can store it for removal."
  ([handler] (install-keydown! handler true))
  ([handler capture?]
   (.addEventListener js/document "keydown" handler capture?)
   handler))

(defn remove-keydown!
  "Remove a previously installed keydown handler. The capture? flag must match
   the one used at install time."
  ([handler] (remove-keydown! handler true))
  ([handler capture?]
   (when handler
     (.removeEventListener js/document "keydown" handler capture?))))

;; ============================================================
;; Source commit
;; ============================================================

(defn splice-source
  "Return `text` with its [from to) character range replaced by `replacement`.
   The pure text operation shared by every modal evaluator's 'build the modified
   script' step (and by edit-bezier's marker → sibling-forms rewrite)."
  [text from to replacement]
  (str (.substring text 0 from) replacement (.substring text to)))

(defn- skip-string
  "Given text and the index of an opening quote, return the index after the
   closing quote, or -1 if unterminated."
  [text start]
  (let [len (count text)]
    (loop [j (inc start)]
      (cond
        (>= j len) -1
        (= (.charAt text j) "\\") (recur (+ j 2))
        (= (.charAt text j) "\"") (inc j)
        :else (recur (inc j))))))

(defn- find-matching-paren
  "Given text and the index of an opening paren, return the index one past the
   matching closing paren. Handles nested parens, strings, and line comments.
   Returns -1 if unbalanced."
  [text start]
  (let [len (count text)]
    (loop [i (inc start) depth 1]
      (cond
        (>= i len) -1
        (zero? depth) i
        :else
        (let [ch (.charAt text i)]
          (case ch
            "(" (recur (inc i) (inc depth))
            ")" (if (= depth 1) (inc i) (recur (inc i) (dec depth)))
            "\"" (let [after (skip-string text i)]
                   (if (neg? after) -1 (recur after depth)))
            ";" (let [nl (.indexOf text "\n" i)]
                  (if (neg? nl) -1 (recur (inc nl) depth)))
            (recur (inc i) depth)))))))

(defn strip-head
  "Replace the literal head token at the very start of text[from,to) — the
   exact string `old-head`, e.g. \"(edit-path\" — with `new-head`, e.g.
   \"(path\", leaving everything else in the range (the body) byte-identical.
   Shared by every editor whose cancel! follows the family's edit-X ↔ X
   head-rename grammar: since the session never writes to the buffer before
   confirm/cancel, the range still holds exactly the body the user typed."
  [text from to old-head new-head]
  (str new-head (subs text (+ from (count old-head)) to)))

(defn find-form-bounds
  "Find the [from to) character bounds of the first form in `text` whose opening
   matches `prefix` (e.g. \"(tweak \" or \"(edit-bezier\"). Returns [from to] or
   nil. Shared by every modal evaluator that locates its own marker in the source."
  [text prefix]
  (let [idx (.indexOf text prefix)]
    (when (>= idx 0)
      (let [end (find-matching-paren text idx)]
        (when (pos? end) [idx end])))))

(defn balanced-source?
  "True if `text` is one or more complete, well-formed s-expressions: brackets
   balanced and properly nested (a closer matches the most recent opener),
   respecting strings and line comments. A bare token (e.g. \"2\") counts as
   balanced. Empty/whitespace-only text is not. Used to reject malformed editor
   selections like \"2)\" before wrapping them in a modal (tweak …) form."
  [text]
  (let [len (count text)
        closer {"(" ")" "[" "]" "{" "}"}
        opener? #{"(" "[" "{"}
        closer? #{")" "]" "}"}]
    (and (pos? (count (.trim text)))
         (loop [i 0 stack ()]
           (if (>= i len)
             (empty? stack)
             (let [ch (.charAt text i)]
               (cond
                 (opener? ch) (recur (inc i) (conj stack (closer ch)))
                 (closer? ch) (and (seq stack) (= ch (first stack))
                                   (recur (inc i) (rest stack)))
                 (= ch "\"") (let [after (skip-string text i)]
                               (and (pos? after) (recur after stack)))
                 (= ch ";") (let [nl (.indexOf text "\n" i)]
                              (if (neg? nl) (empty? stack) (recur (inc nl) stack)))
                 :else (recur (inc i) stack))))))))

(defn replace-source!
  "Replace the [from to) character range of the editor with `code`."
  [from to code]
  (cm/replace-range from to code))

(defn run-definitions!
  "Re-run the whole definitions buffer (the commit step in script mode)."
  []
  (when-let [f @state/run-definitions-fn] (f)))

;; ============================================================
;; Shared script-mode re-eval boilerplate
;; ============================================================

(defn reeval-script!
  "Run the modified script (with the modal marker replaced) in script mode and
   push the resulting scene to the viewport. `build-script-fn` is a 0-arg fn that
   returns the full editor text with the marker substituted. Returns :ok on
   success, nil on error (logged with `err-prefix`). Callers do their own
   session-specific follow-up (turtle indicator, wireframe preview) on :ok.

   `arm-skip?` is REQUIRED (no default) — it decides whether the skip flag is
   armed before the eval, so that — if the marker survives in the modified script
   (e.g. stale offsets) — its own macro passes through instead of re-entering.
   In practice every live-preview caller replaces the marker with literals, so
   they all pass false; a stray true leaks the flag into the next modal macro,
   which then silently passes through instead of opening (the edit-attach reentry
   glitch — see code-issues.md). The arg is mandatory precisely so a caller can't
   omit it and inherit that footgun by accident; there is no safe default to pick
   for them. Legitimate arming of the flag is done explicitly via `arm-skip!` in
   the leave-the-marker cancel! paths (edit-bezier, permanent tweak), not here."
  [build-script-fn err-prefix arm-skip?]
  (try
    (let [script (build-script-fn)]
      (registry/clear-all!)
      (state/reset-turtle!)
      (state/reset-scene-accumulator!)
      (state/reset-print-buffer!)
      (let [ctx @state/sci-ctx-ref]
        (when arm-skip? (arm-skip!))
        (sci/eval-string script ctx))
      (let [{:keys [lines stamps]} @state/scene-accumulator]
        (registry/set-lines! (vec (or lines [])))
        (registry/set-stamps! (vec (or stamps []))))
      (registry/refresh-viewport! false)
      :ok)
    (catch :default e
      (js/console.warn err-prefix (.-message e))
      nil)))

;; ============================================================
;; Two-phase entry driver — generic dispatch over registered kinds
;; ============================================================

;; kind → {:requested? fn :enter! fn :active? fn :cancel! fn}. Concrete modules
;; push their spec at load time. This namespace never requires them, which keeps
;; the dependency arrow one-way (modules → modal-evaluator) and cycle-free.
(defonce ^:private kinds (atom {}))

(defn register-kind!
  "Register a concrete modal evaluator. `spec` is a map of:
     :requested? (fn [] bool) — a deferred request is pending, awaiting enter!
     :enter!     (fn [])      — install the interactive UI (deferred phase)
     :active?    (fn [] bool) — a session of this kind is live
     :cancel!    (fn [])      — cancel the live session
   A synchronous session (tweak) registers :requested? → false and :enter! as a
   no-op, since it opens inside its own start!."
  [kind spec]
  (swap! kinds assoc kind spec))

(defn- pending-kind
  "The kind whose deferred request is pending, or nil."
  []
  (some (fn [[k {:keys [requested?]}]]
          (when (and requested? (requested?)) k))
        @kinds))

(defn requested?
  "True if any registered modal session has a pending two-phase request."
  []
  (boolean (pending-kind)))

(defn enter!
  "Enter the pending modal session (deferred phase). No-op if none pending."
  []
  (when-let [k (pending-kind)]
    (when-let [f (:enter! (get @kinds k))] (f))))

(defn active?
  "True if any registered modal session is currently active."
  []
  (boolean (some (fn [[_ {:keys [active?]}]] (and active? (active?))) @kinds)))

(defn cancel-active!
  "Cancel whichever modal session is currently active. No-op if none."
  []
  (doseq [[_ {:keys [active? cancel!]}] @kinds]
    (when (and active? (active?) cancel!) (cancel!))))

(defn force-close-active!
  "Tear down whichever modal session is active and release the slot, WITHOUT the
   session's own restore re-eval — used before a user-initiated definitions run
   (Run button / Cmd+Enter), which re-evaluates the buffer anyway. Falls back to
   :cancel! for sessions that register no :close! hook. No-op if none active."
  []
  (doseq [[_ {:keys [active? close! cancel!]}] @kinds]
    (when (and active? (active?))
      (cond close! (close!) cancel! (cancel!)))))
