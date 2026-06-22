(ns ridley.editor.edit-bezier
  "Modal evaluator for authoring a cubic Bezier curve interactively, in 3D, from
   the keyboard. `(bezier-to (edit-bezier))` opens a session: the turtle pose at
   the call site is P0 (fixed, never written to source), and three movable points
   — the end point and the two control points — are nudged with the arrow keys.
   On confirm the (edit-bezier) marker is replaced by the three literal vectors
   plus the :local flag, so the call becomes
       (bezier-to [ex ey ez] [c1x c1y c1z] [c2x c2y c2z] :local)
   expressed in P0's local [right up heading] frame (a pose-independent, round-trip
   identity rewrite — see turtle/bezier-to's :local flag).

   Built on modal-evaluator (Architecture §11.2.4, §15.2.1): state, mutex, panel,
   keyhandler, two-phase entry and source commit all come from the shared layer.
   This module is the third modal evaluator and deliberately not a clone of pilot;
   its only specific work is the keymap and the ephemeral viewport geometry (the
   four points, the control polygon, and the live preview curve)."
  (:require [ridley.editor.state :as state]
            [ridley.editor.codemirror :as cm]
            [ridley.editor.modal-evaluator :as modal]
            [ridley.editor.ui :as ui]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.bezier :as bezier]
            [ridley.viewport.core :as viewport]
            [clojure.string :as str]))

(declare confirm! cancel! render! update-panel!)

;; ============================================================
;; State
;; ============================================================

(defonce ^:private session (atom nil))
;; When active:
;; {:p0           {:position :heading :up}  — captured pose (world), not editable
;;  :points       [end c1 c2]               — three movable points, each [a b c]
;;                                            in P0's local [right up heading] frame
;;  :selected     0                          — index 0..2 (Tab cycles)
;;  :step         5                          — nudge size (mm)
;;  :digit-buffer ""                         — accumulating step input (pilot-style)
;;  :shape-seed?  bool                       — :as-shape-seed mode (planar YZ editing,
;;                                             curve usable as a stroke-shape seed)
;;  :wireframe?   bool                       — :wireframe mode (nudges update only the
;;                                             ephemeral path; Insert forces a re-eval)
;;  :eval-timeout <id>                       — debounce handle for live re-eval
;;  :panel-el     <DOM>
;;  :key-handler  <fn>
;;  :entered?     bool}

;; Opening prefix used to locate the (edit-bezier …) marker in the source — works
;; for both the bare (edit-bezier) form and the (edit-bezier [..] [..] [..]) re-open.
(def ^:private marker-prefix "(edit-bezier")
(def ^:private default-length 40)
(def ^:private point-labels ["end" "ctrl1" "ctrl2"])

;; Anchor / tension mode (the (edit-bezier path :at :mark [:symmetric]) form):
;; the endpoints and tangent directions are fixed by the path's marks, so the
;; only editable degrees of freedom are the control-point distances (tensions).
(def ^:private default-tension 0.5)
(def ^:private tension-step 0.02)
(def ^:private tension-fine-step 0.005)

;; Colors for the ephemeral geometry
(def ^:private curve-color 0xff9933)  ; preview curve — orange
(def ^:private poly-color  0x6699ff)  ; control polygon — blue
(def ^:private p0-color    0x33ff66)  ; fixed start point — green
(def ^:private pt-color    0xffffff)  ; unselected movable point — white
(def ^:private sel-color   0xffff00)  ; selected movable point — yellow

;; ============================================================
;; Local frame ↔ world
;; ============================================================

(defn- pt->world
  "Map a stored point [a b c] (a=right, b=up, c=heading — the frame the emitted
   `:local` vectors use) to a world position for the overlay, given the mode.

   - Default (3D): faithful frame (a→right, b→up, c→heading), so the overlay is
     exactly the curve the emitted bezier-to draws.
   - :as-shape-seed: the emitted curve is a path that stroke-shape+extrude reorient
     into a YZ cross-section (length→world-Y, bow→world-Z). The overlay is drawn in
     that same orientation — length (c) along right, bow (a) along up — so the
     handles line up with the extruded wall instead of the un-reoriented path."
  [{:keys [position heading up]} shape-seed? [a b c]]
  (let [right (turtle/normalize (turtle/cross heading up))]
    (if shape-seed?
      (turtle/v+ position
                 (turtle/v+ (turtle/v* right c)
                            (turtle/v+ (turtle/v* up (- a))  ; bow → −up, matching the
                                       (turtle/v* heading b)))) ; extruded cross-section
      (turtle/v+ position
                 (turtle/v+ (turtle/v* right a)
                            (turtle/v+ (turtle/v* up b)
                                       (turtle/v* heading c)))))))

(defn- world->pt
  "Inverse of pt->world: project a WORLD position into the stored local frame
   [a=right, b=up, c=heading], so a world-coordinate bezier seed (e.g. a
   hand-written `(bezier-to … )` without `:local`) round-trips into the editor."
  [{:keys [position heading up]} shape-seed? w]
  (let [right (turtle/normalize (turtle/cross heading up))
        d  (turtle/v- w position)
        dr (turtle/dot d right)
        du (turtle/dot d up)
        dh (turtle/dot d heading)]
    (if shape-seed?
      [(- du) dh dr]     ; forward used c→right, (−a)→up, b→heading
      [dr du dh])))

(defn- default-points
  "A gentle starting curve, so there is something visible to move. Order matches
   bezier-to: [end c1 c2], where c1 is the start-tangent handle (off P0) and c2 is
   the end-tangent handle (into end). Each point is [a b c] = [right up heading].

   Both modes span the heading axis; they differ in the bow axis:
   - Default (3D) bows toward up → the heading/up (XZ) plane, for a free 3D curve.
   - :as-shape-seed bows toward right → the heading/right plane (the path's own
     2D trace plane that path-to-2d-waypoints reads, verified empirically). Fed to
     stroke-shape and extruded, that 2D curve becomes a cross-section in the YZ
     plane (path heading → world Y, path right → world Z)."
  [shape-seed?]
  (let [L default-length
        bow 10
        h1 15
        h2 25]
    (if shape-seed?
      [[0 0 L]            ; end — along heading (the length, drawn along right)
       [bow 0 h1]         ; c1 — bows toward right (drawn along up)
       [bow 0 h2]]
      [[0 0 L]            ; end — along heading
       [(- bow) 0 h1]     ; c1 — bows toward left (the editor's vertical)
       [(- bow) 0 h2]])))

;; ============================================================
;; Number / vector formatting for the source rewrite
;; ============================================================

(defn- fmt-num [v]
  (let [r (js/Math.round v)]
    (if (< (js/Math.abs (- v r)) 1e-9)
      (str r)
      (let [s (.toFixed v 2)]
        (cond
          (str/ends-with? s "00") (subs s 0 (- (count s) 3))
          (str/ends-with? s "0")  (subs s 0 (dec (count s)))
          :else s)))))

(defn- fmt-vec [v]
  (str "[" (str/join " " (map fmt-num v)) "]"))

;; ============================================================
;; Ephemeral geometry
;; ============================================================

(defn- cross-segments
  "Three axis-aligned segments forming a small 3D cross marker at center."
  [[x y z] size color]
  [{:from [(- x size) y z] :to [(+ x size) y z] :color color}
   {:from [x (- y size) z] :to [x (+ y size) z] :color color}
   {:from [x y (- z size)] :to [x y (+ z size)] :color color}])

(defn- curve-segments
  "Polyline approximating the cubic Bezier P0→end with controls c1,c2 (all world)."
  [p0w c1w c2w end-w]
  (let [n 32
        pts (mapv #(bezier/cubic-bezier-point p0w c1w c2w end-w (/ % n))
                  (range (inc n)))]
    (mapv (fn [a b] {:from a :to b :color curve-color}) pts (rest pts))))

(defn- render!
  "Redraw the ephemeral geometry from the current session state. Called on every
   nudge — the preview is session-driven, not a re-evaluation of the user code."
  []
  (when-let [{:keys [p0 points selected shape-seed? anchor-mode?]} @session]
    ;; Anchor / tension mode draws no ephemeral control polygon — the live
    ;; downstream geometry (the real bezier-to-anchor call) is the preview.
    (when-not anchor-mode?
      (let [[end c1 c2] points
            p0w  (:position p0)
            end-w (pt->world p0 shape-seed? end)
            c1w  (pt->world p0 shape-seed? c1)
            c2w  (pt->world p0 shape-seed? c2)
            movable [end-w c1w c2w]
            segs (concat
                  ;; preview curve
                  (curve-segments p0w c1w c2w end-w)
                  ;; control polygon: P0 → c1 → c2 → end
                  [{:from p0w :to c1w :color poly-color}
                   {:from c1w :to c2w :color poly-color}
                   {:from c2w :to end-w :color poly-color}]
                  ;; fixed start point
                  (cross-segments p0w 2 p0-color)
                  ;; three movable points (selected one larger + highlighted)
                  (mapcat (fn [[i pw]]
                            (cross-segments pw
                                            (if (= i selected) 4 2)
                                            (if (= i selected) sel-color pt-color)))
                          (map-indexed vector movable)))]
        (viewport/show-preview! [{:type :lines :data (vec segs)}])))))

;; ============================================================
;; Live re-eval (downstream geometry)
;; ============================================================

(defn- points->code
  "The replacement form: a complete (bezier-to …) call. (edit-bezier …) is a
   stand-in for this call, so confirming swaps the whole marker for it."
  [[end c1 c2]]
  (str "(bezier-to " (fmt-vec end) " " (fmt-vec c1) " " (fmt-vec c2) " :local)"))

(defn- anchor->code
  "The replacement form for the anchor / tension mode: a complete
   (bezier-to-anchor path :at :mark :tension …) call. Symmetric emits a single
   :tension; asymmetric adds :tension-end. The path is emitted as the original
   source expression so the call stays self-contained (round-trips into a new
   edit-bezier session)."
  [{:keys [path-src mark symmetric? tension tension-end]}]
  (str "(bezier-to-anchor " path-src " :at " mark
       " :tension " (fmt-num tension)
       (when-not symmetric? (str " :tension-end " (fmt-num tension-end)))
       ")"))

(defn- current-code
  "The replacement source for the active session, dispatching on mode."
  []
  (let [s @session]
    (if (:anchor-mode? s)
      (anchor->code s)
      (points->code (:points s)))))

(defn- find-marker
  "Locate the (edit-bezier …) marker in the current editor buffer. Re-found fresh
   each time (not cached at request! time) so it can't go stale."
  []
  (modal/find-form-bounds (cm/get-value) marker-prefix))

(defn- build-modified-script
  "The editor buffer with the (edit-bezier …) marker spliced out for the current
   (bezier-to …) call — evaluated as a throwaway copy for the live preview; the
   real editor source is only rewritten on confirm."
  []
  (let [[from to] (find-marker)]
    (modal/splice-source (cm/get-value) from to (current-code))))

(defn- live-reeval!
  "Re-evaluate a copy of the script with the marker replaced by the current
   literals, so downstream geometry (stroke-shape/extrude/register) updates live,
   then redraw the ephemeral overlay on top (the re-eval's refresh clears it).
   arm-skip? is false: the modified script has the marker replaced, so there is no
   (edit-bezier) to re-enter and no flag to leave armed."
  []
  ;; Clear stale ruler overlays first: the re-evaled buffer re-runs its own
  ;; (ruler …) forms, which append — without this they stack across nudges and
  ;; the reading never comes back down. The script recreates the live ones.
  (viewport/clear-rulers!)
  (modal/reeval-script! build-modified-script "edit-bezier eval error:" false)
  (render!))

(defn- debounced-reeval! []
  (when-let [t (:eval-timeout @session)] (js/clearTimeout t))
  (swap! session assoc :eval-timeout
         (js/setTimeout (fn [] (live-reeval!)) 80)))

(defn- refresh-preview!
  "After a nudge: redraw the overlay immediately for snappy feedback, and — unless
   in :wireframe mode — re-eval the downstream geometry (debounced). In :wireframe
   mode the downstream is refreshed only on demand (Insert)."
  []
  (render!)
  (when-not (:wireframe? @session)
    (debounced-reeval!)))

;; ============================================================
;; UI panel
;; ============================================================

(defn- anchor-point-label
  "Which handle the arrows currently drive, for the panel."
  [{:keys [symmetric? selected]}]
  (cond symmetric?         "both"
        (= :end selected)  "end"
        :else              "start"))

(defn update-panel!
  "Refresh the selected-point label and step display (mode-aware)."
  []
  (when-let [panel (:panel-el @session)]
    (let [s @session]
      (if (:anchor-mode? s)
        (do
          (when-let [el (.querySelector panel ".eb-point")]
            (set! (.-textContent el) (anchor-point-label s)))
          ;; Keyboard nudges push the current tension(s) back into the slider(s).
          (when-let [sl (get-in s [:sliders :tension])]
            ((:set-value! sl) (:tension s)))
          (when-let [sl (get-in s [:sliders :tension-end])]
            (when-not (:symmetric? s) ((:set-value! sl) (:tension-end s)))))
        (do
          (when-let [el (.querySelector panel ".eb-point")]
            (set! (.-textContent el) (nth point-labels (:selected s))))
          (when-let [el (.querySelector panel ".eb-step")]
            (let [buf (:digit-buffer s)]
              (set! (.-textContent el)
                    (if (seq buf) (str buf "_") (str (:step s) "mm"))))))))))

;; --- Tension sliders (anchor / tension mode) -----------------------------

(defn- tension-range
  "Slider [min max step] for a tension: 0 up to a generous max, fine step.
   Tensions are clamped >= 0 and usually live in ~0..1.5; zoom widens if needed."
  [v]
  [0 (max 1.5 (* 2 v)) 0.01])

(defn- on-tension-input
  "Slider handler for the tension named by `which` (:tension or :tension-end):
   store it (clamped >= 0) and refresh the live preview. Deliberately does NOT
   call update-panel! — the slider already shows the value, and re-pushing it
   mid-drag would fight the thumb."
  [which]
  (fn [v]
    (swap! session assoc which (max 0 v))
    (refresh-preview!)))

(defn- build-tension-sliders!
  "Build the tension slider row(s) into the panel's .eb-sliders container and
   stash their handles in session under :sliders, so keyboard nudges can sync."
  [panel s]
  (when-let [container (.querySelector panel ".eb-sliders")]
    (let [sym? (:symmetric? s)
          t-row (ui/create-slider-row {:label    (if sym? "tension" "tension start")
                                       :value    (:tension s)
                                       :range-fn tension-range
                                       :on-input (on-tension-input :tension)})]
      (.appendChild container (:row t-row))
      (swap! session assoc-in [:sliders :tension] t-row)
      (when-not sym?
        (let [e-row (ui/create-slider-row {:label    "tension end"
                                           :value    (:tension-end s)
                                           :range-fn tension-range
                                           :on-input (on-tension-input :tension-end)})]
          (.appendChild container (:row e-row))
          (swap! session assoc-in [:sliders :tension-end] e-row))))))

(defn- create-panel!
  "Create the edit-bezier UI panel (reusing pilot's CSS classes) and mount it.
   In anchor / tension mode it also mounts one (symmetric) or two (asymmetric)
   tension sliders alongside the keyboard controls."
  []
  (let [s @session
        anchor? (:anchor-mode? s)
        shape-seed? (:shape-seed? s)
        wf? (:wireframe? s)
        mode (if anchor?
               (if (:symmetric? s) "tension · symmetric" "tension")
               (str (if shape-seed? "shape-seed" "3D")
                    (when wf? " · wireframe")))
        hint (if anchor?
               (str "drag sliders · ↑↓: tension · Shift: fine"
                    (when-not (:symmetric? s) " · Tab: switch handle")
                    " · Enter: OK · Esc: cancel")
               (str "Tab: next point · ←→↑↓: move"
                    (when-not shape-seed? " · Shift+↑↓: depth")
                    " · digits: step · Ins: re-eval · Enter: OK · Esc: cancel"))
        point-label (if anchor? "Handle" "Point")
        panel (.createElement js/document "div")]
    (set! (.-id panel) "edit-bezier-panel")
    (set! (.-innerHTML panel)
          (str "<div class='pilot-header'>edit-bezier"
               "<span class='pilot-mode-badge'>" mode "</span></div>"
               "<div class='pilot-controls'>"
               "<span>" point-label ": <span class='eb-point'>end</span></span>"
               ;; Free mode shows the step readout as text; anchor mode shows
               ;; tension via the sliders below instead.
               (if anchor? "" "<span>Step: <span class='eb-step'>5mm</span></span>")
               "</div>"
               (when anchor? "<div class='eb-sliders'></div>")
               "<div class='pilot-commands modal-help'>" hint "</div>"
               "<div class='pilot-buttons'>"
               "<button class='pilot-btn pilot-btn-ok eb-ok'>OK</button>"
               "<button class='pilot-btn pilot-btn-cancel eb-cancel'>Cancel</button>"
               "</div>"))
    (when anchor? (build-tension-sliders! panel s))
    (.addEventListener (.querySelector panel ".eb-ok") "click" (fn [_] (confirm!)))
    (.addEventListener (.querySelector panel ".eb-cancel") "click" (fn [_] (cancel!)))
    (modal/mount-panel! panel)
    panel))

;; ============================================================
;; Keyboard handler
;; ============================================================

(defn- digit-key
  "If key is a single digit char, return it; else nil."
  [key]
  (when (and (= 1 (count key)) (re-matches #"[0-9]" key)) key))

(defn- flush-digit!
  "Apply the accumulated digit buffer as the new step, if any."
  []
  (let [buf (:digit-buffer @session)]
    (when (seq buf)
      (let [v (js/parseFloat buf)]
        (when (and (pos? v) (js/isFinite v))
          (swap! session assoc :step v)))
      (swap! session assoc :digit-buffer "")
      (update-panel!))))

(defn- nudge!
  "Move the selected point by sign·step along a local axis (0=right, 1=up, 2=heading)."
  [axis sign]
  (let [step (:step @session)]
    (swap! session update-in [:points (:selected @session) axis] + (* sign step))
    (refresh-preview!)
    (update-panel!)))

(defn- arrow->axis
  "Map an arrow key to [axis sign] (0=right, 1=up, 2=heading) for the current mode.
   Returns nil when the key/modifier combo is not bound (e.g. Shift+arrows in
   :as-shape-seed, which is planar and has no depth axis)."
  [key shift? shape-seed?]
  (if shape-seed?
    ;; Overlay shows the stroke-shape cross-section: ←→ = length (c, drawn along
    ;; right), ↑↓ = bow (a, drawn along up). Planar — Shift disabled.
    (when-not shift?
      (case key
        "ArrowLeft"  [2 -1]
        "ArrowRight" [2 1]
        "ArrowUp"    [0 -1]   ; bow draws along −up, so ↑ (visual +Z) decreases a
        "ArrowDown"  [0 1]
        nil))
    ;; Faithful path overlay: ←→ = heading (length), ↑↓ = left (the editor's
    ;; vertical, = −right), Shift+↑↓ = up (depth).
    (case key
      "ArrowLeft"  (when-not shift? [2 -1])
      "ArrowRight" (when-not shift? [2 1])
      "ArrowUp"    (if shift? [1 1] [0 -1])
      "ArrowDown"  (if shift? [1 -1] [0 1])
      nil)))

;; --- Anchor / tension mode keymap ----------------------------------------

(defn- tension-key
  "Which tension the arrows adjust: :tension-end only when asymmetric and the end
   handle is selected, else :tension."
  []
  (let [s @session]
    (if (and (not (:symmetric? s)) (= :end (:selected s))) :tension-end :tension)))

(defn- adjust-tension!
  "Nudge the selected tension by delta, clamped to >= 0, then refresh."
  [delta]
  (swap! session update (tension-key) #(max 0 (+ % delta)))
  (refresh-preview!)
  (update-panel!))

(defn- on-keydown-anchor [e key shift?]
  (let [step (if shift? tension-fine-step tension-step)]
    (cond
      ;; Tab: switch the adjusted handle (asymmetric only)
      (= key "Tab")
      (do (.preventDefault e) (.stopPropagation e)
          (when-not (:symmetric? @session)
            (swap! session update :selected #(if (= % :end) :start :end)))
          (update-panel!))

      ;; Arrows raise/lower the selected tension (Shift = fine step)
      (#{"ArrowUp" "ArrowRight"} key)
      (do (.preventDefault e) (.stopPropagation e) (adjust-tension! step))

      (#{"ArrowDown" "ArrowLeft"} key)
      (do (.preventDefault e) (.stopPropagation e) (adjust-tension! (- step)))

      (= key "Enter")
      (do (.preventDefault e) (.stopPropagation e) (confirm!))

      (= key "Escape")
      (do (.preventDefault e) (.stopPropagation e) (cancel!))

      :else nil)))

;; --- Free 3-point mode keymap --------------------------------------------

(defn- on-keydown-free [e key shift?]
  (let [digit (digit-key key)]
    (cond
        ;; Tab: cycle the three movable points
      (= key "Tab")
      (do (.preventDefault e) (.stopPropagation e)
          (flush-digit!)
          (swap! session update :selected #(mod (inc %) 3))
          (render!) (update-panel!))

        ;; Digit input → accumulate the step value (pilot-style)
      digit
      (do (.preventDefault e) (.stopPropagation e)
          (swap! session update :digit-buffer str digit)
          (update-panel!))

        ;; Decimal point in the step buffer
      (and (= key ".") (seq (:digit-buffer @session)))
      (do (.preventDefault e) (.stopPropagation e)
          (when-not (str/includes? (:digit-buffer @session) ".")
            (swap! session update :digit-buffer str "."))
          (update-panel!))

        ;; Arrows — axis mapping depends on the mode (see arrow->axis). Always
        ;; swallowed so the editor doesn't move the cursor / select text; a no-op
        ;; mapping (e.g. Shift+arrows in :as-shape-seed) just does nothing.
      (#{"ArrowUp" "ArrowDown" "ArrowLeft" "ArrowRight"} key)
      (do (.preventDefault e) (.stopPropagation e) (flush-digit!)
          (when-let [[axis sign] (arrow->axis key shift? (:shape-seed? @session))]
            (nudge! axis sign)))

        ;; Insert: force a downstream re-eval on demand (useful in :wireframe mode)
      (= key "Insert")
      (do (.preventDefault e) (.stopPropagation e) (flush-digit!) (live-reeval!))

        ;; Backspace: undo digit input
      (= key "Backspace")
      (do (.preventDefault e) (.stopPropagation e)
          (swap! session update :digit-buffer
                 #(subs % 0 (max 0 (dec (count %)))))
          (update-panel!))

        ;; Confirm / cancel
      (= key "Enter")
      (do (.preventDefault e) (.stopPropagation e) (flush-digit!) (confirm!))

      (= key "Escape")
      (do (.preventDefault e) (.stopPropagation e) (cancel!))

      :else nil)))

(defn- on-keydown
  "Global keydown dispatcher: route to the active mode's keymap."
  [e]
  (when (:entered? @session)
    (let [key (.-key e)
          shift? (.-shiftKey e)]
      (if (:anchor-mode? @session)
        (on-keydown-anchor e key shift?)
        (on-keydown-free e key shift?)))))

;; ============================================================
;; Confirm / Cancel / Cleanup
;; ============================================================

(defn- cleanup! []
  (when-let [t (:eval-timeout @session)] (js/clearTimeout t))
  (modal/unmount-panel! (:panel-el @session))
  (modal/remove-keydown! (:key-handler @session))
  (viewport/set-turtle-source! :global)
  (viewport/clear-preview!))

(defn confirm!
  "Confirm: replace the whole (edit-bezier …) marker with the complete call
   (bezier-to [end] [c1] [c2] :local), then re-run the definitions. Sessions only
   ever open in script mode (see request!)."
  []
  (when @session
    (let [[from to] (find-marker)
          code (current-code)]
      (when from
        (modal/replace-source! from to code)
        (state/capture-println (str "edit-bezier: " code)))
      (cleanup!)
      (modal/release!)
      (reset! session nil)
      (modal/run-definitions!))))

(defn cancel!
  "Cancel: leave the source unchanged (the (edit-bezier …) marker stays) and tear
   down the ephemeral UI, then re-run the definitions with the skip flag armed so
   the marker passes through (drawing its default curve) without re-opening a
   session — restoring the viewport to the unconfirmed state."
  []
  (cleanup!)
  (modal/release!)
  (reset! session nil)
  (modal/arm-skip!)
  (modal/run-definitions!))

;; ============================================================
;; Entry points (two-phase)
;; ============================================================

(defn ^:export active?
  "True if an edit-bezier session is currently open."
  []
  (some? @session))

(defn- clear-orphan!
  "Release a stuck session from a previous eval that claimed the mutex but never
   entered (e.g. an error later in the buffer aborted before the post-eval driver
   ran). Mirrors pilot's orphan recovery."
  []
  (let [s @session
        live? (and s (:entered? s) (some-> (:panel-el s) .-parentNode))]
    (when (and (= :edit-bezier @state/interactive-mode) (not live?))
      (cleanup!)
      (reset! session nil)
      (modal/release!))))

(defn ^:export request!
  "Called by the (edit-bezier …) macro expansion (before the bezier-to it expands
   to). Opens the session and ALWAYS returns the three initial points [end c1 c2]
   so the macro's `(apply bezier-to … :local)` draws a valid default curve during
   the eval. `shape-seed?`/`wireframe?` are booleans parsed by the macro from the
   flags; `provided` is the three initial vectors (re-open / round-trip) or nil.

   The interactive session only opens in script mode (definitions panel): the
   two-phase enter! driver fires only after a definitions eval, so opening from the
   REPL could never complete — there we just return the points and hint, without
   claiming the mutex (avoiding a stuck slot). On a skip-flagged re-eval (cancel /
   live preview) we likewise just return the points without re-opening."
  [shape-seed? wireframe? provided seed-local?]
  (let [p0 (state/get-turtle-pose)
        points (cond
                 ;; Seed already in the editor's local frame (round-tripping an
                 ;; (edit-bezier … :local) — i.e. our own emitted output).
                 (and provided (= 3 (count provided)) seed-local?)
                 (mapv vec provided)
                 ;; Seed is WORLD coordinates (a hand-written bezier-to without
                 ;; :local). Project into the local frame so the curve is
                 ;; preserved instead of being reinterpreted as local.
                 (and provided (= 3 (count provided)))
                 (mapv #(world->pt p0 shape-seed? %) provided)
                 :else
                 (default-points shape-seed?))]
    (cond
      ;; A re-eval armed the skip flag — pass through without opening a session.
      (modal/consume-skip!)
      points

      ;; REPL mode: can't host the interactive session — draw a default + hint.
      (not= :definitions @state/eval-source-var)
      (do (state/capture-println
           "edit-bezier: open it from the definitions panel (Cmd+Enter), not the REPL")
          points)

      :else
      (do
        (clear-orphan!)
        ;; Validate the marker exists before claiming, so a failure can't leave the
        ;; mutex dangling. The bounds themselves are re-found fresh at commit time.
        (when (nil? (find-marker))
          (throw (js/Error. (str "edit-bezier: cannot find '" marker-prefix " …)' in editor"))))
        (modal/claim! :edit-bezier)
        (reset! session {:p0           p0
                         :points       points
                         :selected     0
                         :step         5
                         :digit-buffer ""
                         :shape-seed?  shape-seed?
                         :wireframe?   wireframe?
                         :entered?     false})
        points))))

(defn ^:export edit-bezier-anchor-request!
  "Called by the (edit-bezier path :at :mark [:symmetric]) macro expansion. Opens
   a tension-editing session and ALWAYS returns the bezier-to-anchor option seq so
   the macro's (apply bezier-to-anchor path :at mark …) draws a default curve
   during the eval. `path-expr` is the path source form (for emission), `mark` the
   anchor keyword, `symmetric?` selects one shared tension vs two independent ones.
   Same script-mode / skip / REPL guards as request!."
  [path-expr mark symmetric?]
  (let [opts (if symmetric?
               (list :tension default-tension)
               (list :tension default-tension :tension-end default-tension))]
    (cond
      ;; A re-eval armed the skip flag — pass through without opening a session.
      (modal/consume-skip!)
      opts

      ;; REPL mode: can't host the interactive session — draw a default + hint.
      (not= :definitions @state/eval-source-var)
      (do (state/capture-println
           "edit-bezier: open it from the definitions panel (Cmd+Enter), not the REPL")
          opts)

      :else
      (do
        (clear-orphan!)
        (when (nil? (find-marker))
          (throw (js/Error. (str "edit-bezier: cannot find '" marker-prefix " …)' in editor"))))
        (modal/claim! :edit-bezier)
        (reset! session {:p0           (state/get-turtle-pose)
                         :anchor-mode? true
                         :path-src     (pr-str path-expr)
                         :mark         mark
                         :symmetric?   symmetric?
                         :tension      default-tension
                         :tension-end  default-tension
                         :selected     :start
                         :entered?     false})
        opts))))

(defn requested?
  "Check if an edit-bezier session was requested during evaluation."
  []
  (and (some? @session) (not (:entered? @session))))

(defn enter!
  "Enter the interactive session. Called by core.cljs after the eval completes."
  []
  (when (requested?)
    (swap! session assoc :entered? true)
    ;; Install keyboard handler (capture phase to intercept before the editor)
    (let [handler on-keydown]
      (swap! session assoc :key-handler handler)
      (modal/install-keydown! handler))
    ;; Turtle indicator at P0
    (viewport/set-turtle-source! {:custom (:p0 @session)})
    (viewport/update-turtle-pose (:p0 @session))
    (viewport/set-turtle-visible true)
    ;; UI panel + ephemeral geometry
    (let [panel (create-panel!)]
      (swap! session assoc :panel-el panel))
    (update-panel!)
    ;; Initial preview: in :wireframe mode just the overlay; otherwise re-eval the
    ;; downstream geometry now (the opening eval saw (edit-bezier) as a no-op).
    (if (:wireframe? @session)
      (render!)
      (live-reeval!))
    (state/capture-println
     (if (:anchor-mode? @session)
       (str "edit-bezier: ↑↓ adjust tension"
            (when-not (:symmetric? @session) ", Tab switches handle")
            ", Enter to confirm, Esc to cancel")
       (str "edit-bezier: Tab cycles points, arrows move, type digits to set step, "
            "Ins re-evaluates, Enter to confirm, Esc to cancel")))))

;; ============================================================
;; Modal-evaluator registration
;; ============================================================

(defn- force-close!
  "Tear down the session without re-evaluating. Used before a user-initiated
   definitions run (which re-evaluates anyway)."
  []
  (when @session
    (cleanup!)
    (modal/release!)
    (reset! session nil)))

;; edit-bezier is a deferred two-phase session like pilot: request! runs during
;; the eval and returns P0 (no-op) without installing the handler; the post-eval
;; driver in core.cljs calls enter! once the eval completes.
(modal/register-kind! :edit-bezier
                      {:requested? requested?
                       :enter!     enter!
                       :active?    active?
                       :cancel!    cancel!
                       :close!     force-close!})
