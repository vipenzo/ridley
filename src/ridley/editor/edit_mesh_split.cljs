(ns ridley.editor.edit-mesh-split
  "Interactive editor for mesh-split — the plane-cut decomposition tool
   (dev-docs/brief-edit-mesh-split.md, addendum-brief-edit-mesh-split.md).
   The turtle IS the cut-plane pose: no separate gizmo POSE, but — since
   the addendum — the shared editor.gizmo drag-handle layer (translate
   arrows + rotation rings only, no stretch) reuses that same pose,
   exactly like edit-attach's own gizmo. Keyboard and mouse both move/
   rotate the live pose; a semi-transparent quad + orientation cone
   render the plane at that pose, and the two live halves are colored by
   convex? (behind solid, ahead washed — see half-preview-item).

   Single-action confirm model (Vincenzo, 2026-07-11 — see the brief's
   Uscita section): Enter always means \"accept :behind as the definitive
   piece\"; whether that continues the session or closes it falls out of
   which half is empty at the current pose, not a separate key.

   Built on modal-evaluator, same two-phase request!/enter! shape as
   edit-attach/edit-bezier. Unlike edit-attach, there is no verbatim body
   to preserve — re-entry always re-derives session state from the
   evaluated composite (mesh-split's own return value), never from source
   text, and confirm always re-emits canonically."
  (:require [clojure.string :as str]
            [ridley.editor.state :as state]
            [ridley.editor.codemirror :as cm]
            [ridley.editor.modal-evaluator :as modal]
            [ridley.editor.implicit :as impl]
            [ridley.editor.gizmo :as gizmo]
            [ridley.manifold.core :as manifold]
            [ridley.turtle.core :as turtle]
            [ridley.sdf.core :as sdf]
            [ridley.scene.registry :as registry]
            [ridley.viewport.core :as viewport]))

(declare cleanup! render! recompute! update-panel-display!)

;; ============================================================
;; State
;; ============================================================

(defonce session (atom nil))

;; {:source-expr   "block"           ; source text of the mesh argument
;;  :initial-mesh  <mesh>             ; resolved, uncut — undo-to-start target
;;  :remaining     <mesh>             ; current live head
;;  :accepted      [{:pose {:position :heading :up} :behind <mesh>
;;                   :remaining <mesh> :name "cut-1"} ...]   ; undo stack
;;  :entry-pose    {:position :heading :up}  ; pose when the session opened —
;;                                             the "from" for the first delta
;;  :live-pose     <full turtle state>  ; the cut plane == the turtle;
;;                                        mutated via real th/tv/tr/f/move-*
;;  :step 5 :angle-step 15 :digit-buffer "" :digit-target :step  ; :step|:angle
;;  :current-behind <mesh or nil> :current-ahead <mesh or nil>   ; live split()
;;  :behind-convex? :ahead-convex?                                ; live convex?()
;;  :plane-state    :no-op | :terminal | :active
;;  :edit-mesh-split-from/-to  ; char offsets of the marker in the editor
;;  :panel-el :key-handler :entered? :from-repl}

(defn- resolve-to-mesh
  "Accept a mesh map, a keyword (registered mesh name), or an SDF node
   (auto-materialized) — same dispatch implicit.cljs's private version
   uses, duplicated here (3 lines) rather than exported, to keep that
   namespace's encapsulation."
  [x]
  (cond
    (keyword? x) (registry/get-mesh x)
    (sdf/sdf-node? x) (sdf/ensure-mesh x)
    :else x))

;; ============================================================
;; Colors
;; ============================================================

(def ^:private color-no-op 0x888888)
(def ^:private color-terminal 0xffcc00)
(def ^:private color-active 0x66ccff)
(def ^:private color-convex 0x33cc55)
(def ^:private color-concave 0xcc3333)
(def ^:private color-ghost 0x999999)

;; ============================================================
;; Geometry helpers — plane quad, orientation cone, terminal placement
;; ============================================================

(defn- bbox-corners
  [[[xmin xmax] [ymin ymax] [zmin zmax]]]
  (for [x [xmin xmax] y [ymin ymax] z [zmin zmax]] [x y z]))

(defn- terminal-position
  "A position along heading, from `position`, that clears ALL of mesh's
   bounding box — the plane there has the whole mesh :behind it, :ahead
   empty. Used both for the post-accept auto-placement and the Ctrl+Enter
   accelerator."
  [mesh position heading]
  (let [corners (bbox-corners (sdf/mesh-bounds mesh))
        max-proj (apply max (map #(turtle/dot (turtle/v- % position) heading) corners))]
    (turtle/v+ position (turtle/v* heading (+ max-proj 1.0)))))

(defn- quad-stamp
  "4-corner quad at `pose`, sized (with margin) to `mesh`'s bbox projected
   onto the pose's right/up axes — {:vertices :faces :color}, the shape
   viewport/show-preview!'s :type :stamp item expects."
  [{:keys [position heading up]} mesh color]
  (let [right (turtle/cross heading up)
        corners (bbox-corners (sdf/mesh-bounds mesh))
        margin 1.15
        proj-r (map #(turtle/dot (turtle/v- % position) right) corners)
        proj-u (map #(turtle/dot (turtle/v- % position) up) corners)
        r-half (* margin (max 1.0 (apply max (map #(Math/abs %) proj-r))))
        u-half (* margin (max 1.0 (apply max (map #(Math/abs %) proj-u))))
        corner (fn [sr su]
                 (turtle/v+ position
                            (turtle/v+ (turtle/v* right (* sr r-half))
                                       (turtle/v* up (* su u-half)))))]
    {:vertices [(corner -1 -1) (corner -1 1) (corner 1 -1) (corner 1 1)]
     :faces [[0 1 3] [0 3 2]]
     :color color}))

(defn- orientation-cone
  "A small cone from `position` pointing along +heading (into the :ahead
   half) — same direction as the turtle's own nose-cone (viewport's
   turtle indicator always points along +heading), so this cone and the
   turtle sitting on the same pose never point opposite ways. Enter
   accepts the OPPOSITE side, :behind — the plane-orientation requirement
   is a flat semi-transparent quad alone doesn't say which side is which;
   the mnemonic (declared in the panel cheat-sheet) is 'cone points ahead,
   Enter takes what's behind it', matching extrude's convention that
   material trails behind the direction of travel."
  [{:keys [position heading up]} color]
  (let [right (turtle/cross heading up)
        radius 3.0
        length 9.0
        apex (turtle/v+ position (turtle/v* heading length))
        segs 10
        ring (vec (for [i (range segs)]
                    (let [a (* 2 Math/PI (/ i segs))]
                      (turtle/v+ position
                                 (turtle/v+ (turtle/v* right (* radius (Math/cos a)))
                                            (turtle/v* up (* radius (Math/sin a))))))))
        verts (conj ring apex)
        apex-idx segs
        faces (vec (for [i (range segs)]
                     [apex-idx i (mod (inc i) segs)]))]
    ;; :material, not top-level :color/:opacity — create-three-mesh (viewport/core.cljs)
    ;; only reads (:material mesh-data); a top-level :color was silently ignored, so
    ;; the cone has always rendered as create-mesh-material's hardcoded default
    ;; (0x00aaff, opacity 1.0) regardless of plane-state — a pre-existing bug (predates
    ;; this addendum) caught live while verifying Part B's cone-tinting requirement.
    {:vertices verts :faces faces
     :material {:color color :opacity 0.9 :double-sided true}}))

;; ============================================================
;; Live recompute — split + convex? at the working pose
;; ============================================================

(defn- working-pose
  "The pose recompute!/render! actually act on: :preview-pose while a gizmo drag is
   live (an uncommitted trial pose — see on-gizmo-drag!), else the real :live-pose.
   Never both at once: on-drag-end clears :preview-pose before anything re-renders."
  [{:keys [preview-pose live-pose]}]
  (or preview-pose live-pose))

(defn- recompute!
  "Keyboard steps, gizmo-commit, and (since live feedback 2026-07-12) throttled
   gizmo-drag ticks all land here — the only place split/convex?/volume are
   (re)computed, against `working-pose`. Measures its own cost and stores it in
   session (:drag-recompute-cost-ms) so on-gizmo-drag!'s throttle can adapt: a
   simple mesh recomputes on nearly every tick, a complex one backs off instead of
   blocking the main thread back-to-back (measured live: ~12ms on a ~4600-tri box,
   ~50-200ms on a dense 8000-tri mesh depending on the cut — see dev-docs/addendum-
   brief-edit-mesh-split.md)."
  []
  (when-let [{:keys [remaining] :as s} @session]
    (let [t0 (js/performance.now)
          {:keys [position heading]} (working-pose s)
          offset (turtle/dot heading position)
          {:keys [ahead behind]} (manifold/split-by-plane remaining heading offset)
          behind-empty? (empty? (:faces behind))
          ahead-empty? (empty? (:faces ahead))
          plane-state (cond behind-empty? :no-op ahead-empty? :terminal :else :active)
          behind-vol (if behind-empty? 0 (:volume (manifold/get-mesh-status behind)))
          ahead-vol (if ahead-empty? 0 (:volume (manifold/get-mesh-status ahead)))
          total-vol (+ behind-vol ahead-vol)
          cost-ms (- (js/performance.now) t0)]
      (swap! session assoc
             :current-behind behind :current-ahead ahead
             :behind-convex? (when-not behind-empty? (manifold/convex? behind))
             :ahead-convex? (when-not ahead-empty? (manifold/convex? ahead))
             :plane-state plane-state
             :behind-pct (if (pos? total-vol) (* 100.0 (/ behind-vol total-vol)) 0.0)
             :ahead-pct (if (pos? total-vol) (* 100.0 (/ ahead-vol total-vol)) 0.0)
             :last-drag-recompute (js/Date.now)
             :drag-recompute-cost-ms cost-ms)))
  (render!)
  (update-panel-display!))

;; ============================================================
;; Rendering
;; ============================================================

(defn- half-preview-item
  "role = :behind (what Enter accepts — rendered solid/opaque, the 'figure') or :ahead
   (rendered washed/near-ghost, the 'ground') — solidity carries the behind/ahead
   distinction, hue stays reserved for convexity (one visual variable per semantic
   dimension, dev-docs/addendum-brief-edit-mesh-split.md)."
  [mesh convex? role]
  (when (and mesh (seq (:faces mesh)))
    {:type :mesh
     :data (assoc mesh :material {:color (if convex? color-convex color-concave)
                                  :opacity (if (= role :behind) 0.88 0.18)
                                  :double-sided true})}))

(defn- ghost-item
  "Consumed pieces carry no convexity tint at all — that hue is reserved for the two
   live halves of the current cut; a consumed piece is dead information, already
   accepted. Wireframe, not a low-alpha solid fill (live feedback 2026-07-12: a
   low-alpha grey solid read as blue-ish under the scene's lighting and blurred into
   both the plane quad and the current :behind piece — a wireframe is unmistakably
   NOT the solid current cut regardless of lighting/hue, the addendum's own
   documented fallback)."
  [mesh]
  {:type :wireframe
   :data (assoc mesh :material {:color color-ghost})})

(defn- plane-items
  "The quad + orientation cone at `pose` — always render!'s items 0 and 1, in this
   order, a contract on-gizmo-drag!'s cheap per-tick update relies on. Colored by
   the session's current :plane-state, whether that's fresh (just recomputed) or a
   few ms old (mid-drag, between throttled recomputes — see recompute!)."
  [pose]
  (let [{:keys [remaining plane-state]} @session
        plane-color (case plane-state
                      :no-op color-no-op
                      :terminal color-terminal
                      color-active)]
    [{:type :stamp :data (quad-stamp pose remaining plane-color)}
     {:type :mesh :data (orientation-cone pose plane-color)}]))

(defn- render!
  []
  (when-let [{:keys [accepted current-behind current-ahead
                     behind-convex? ahead-convex?] :as s} @session]
    (let [pose (working-pose s)
          items (concat
                 (plane-items pose)
                 (remove nil? [(half-preview-item current-behind behind-convex? :behind)
                               (half-preview-item current-ahead ahead-convex? :ahead)])
                 (map (comp ghost-item :behind) accepted))]
      (viewport/show-preview! (vec items))
      (viewport/set-turtle-source! {:custom pose})
      (viewport/update-turtle-pose pose)
      (gizmo/update-pose! pose))))

;; ============================================================
;; Live-pose gestures
;; ============================================================

(defn- apply-gesture!
  [f]
  (swap! session update :live-pose f)
  (recompute!))

(defn- gesture-fn
  "Map a gizmo on-commit (cmd-type value) pair to the same turtle op the keyboard
   handler already applies for that gesture, with no sign flip: gizmo's handle axes
   are calibrated (per its own docstring) to match impl.cljs's pose-mutation
   conventions, the same convention these raw turtle/* fns follow. VERIFY LIVE, one
   handle at a time against its matching arrow key — this mapping is a hypothesis, not
   a proven fact, since edit-mesh-split mutates the pose directly instead of going
   through the SCI-evaluated DSL edit-attach's gizmo was calibrated against."
  [cmd-type value]
  (case cmd-type
    :f  #(turtle/f % value)
    :rt #(turtle/move-right % value)
    :u  #(turtle/move-up % value)
    :th #(turtle/th % value)
    :tv #(turtle/tv % value)
    :tr #(turtle/tr % value)))

(defn- on-gizmo-commit!
  [cmd-type value]
  (apply-gesture! (gesture-fn cmd-type value)))

(defn- trial-pose
  "The uncommitted pose a gizmo drag is currently proposing — gesture-fn applied to
   the (unchanged during a drag) real :live-pose, never accumulated: gizmo's own
   `value` is already 'total since this drag started', matching gesture-fn's own
   one-shot semantics exactly."
  [cmd-type value]
  (when-let [{:keys [live-pose]} @session]
    (select-keys ((gesture-fn cmd-type value) live-pose) [:position :heading :up])))

(def ^:private drag-recompute-floor-ms
  "Minimum gap between drag-time recomputes even on a trivially cheap mesh — no
   point re-splitting more than ~20x/sec, the eye can't tell the difference."
  50)

(defn- drag-recompute-due?
  [{:keys [last-drag-recompute drag-recompute-cost-ms]}]
  (or (nil? last-drag-recompute)
      (>= (- (js/Date.now) last-drag-recompute)
          (if drag-recompute-cost-ms
            (max drag-recompute-floor-ms (* 2 drag-recompute-cost-ms))
            0))))

(defn- on-gizmo-drag!
  "Fired on every pointer-move during a translate/rotate drag (dev-docs/addendum-
   brief-edit-mesh-split.md, live feedback 2026-07-12: colors should update DURING
   the drag, not only on release). The plane (quad+cone, render!'s own items 0/1)
   tracks every tick — cheap, pure geometry, no split involved. The real recompute
   (split + convex? + volume — the expensive part, measured live from ~12ms on a
   ~4600-tri box to 50-200ms on a dense 8000-tri mesh) is throttled adaptively: due
   immediately on a drag's first tick, then gated by 2x the last-measured cost
   (floor 50ms) — a cheap mesh updates almost every frame, an expensive one backs
   off instead of stacking blocking recomputes back-to-back."
  [{:keys [cmd-type value]}]
  (when-let [pose (trial-pose cmd-type value)]
    (swap! session assoc :preview-pose pose)
    (if (drag-recompute-due? @session)
      (recompute!)
      (do (viewport/replace-preview-at! (zipmap [0 1] (plane-items pose)))
          (viewport/set-turtle-source! {:custom pose})
          (viewport/update-turtle-pose pose)
          (gizmo/update-pose! pose)))))

(defn- on-gizmo-drag-start!
  []
  (swap! session assoc :last-drag-recompute nil))

(defn- on-gizmo-drag-end!
  "Unconditional final recompute against the real :live-pose — on-drag-end fires
   before gizmo decides whether to call on-commit, so this must run first (see
   gizmo.cljs's on-pointer-up): if a real gesture follows, apply-gesture! recomputes
   again at the newly-committed pose (a harmless redundant pass); if the drag was a
   no-op (e.g. a click with no movement), this is the ONLY thing that restores the
   pre-drag truth."
  []
  (swap! session dissoc :preview-pose)
  (recompute!))

;; ============================================================
;; Accept / commit / undo / cancel
;; ============================================================

(defn- next-cut-name
  [used-names]
  (loop [n 1]
    (let [candidate (str "cut-" n)]
      (if (contains? used-names candidate)
        (recur (inc n))
        candidate))))

(defn- accept-cut!
  "plane-state = :active: push the current cut, continue the session."
  []
  (let [{:keys [live-pose current-behind current-ahead ahead-convex? accepted]} @session
        pose (select-keys live-pose [:position :heading :up])
        used-names (set (map :name accepted))
        name (next-cut-name used-names)]
    (swap! session
           (fn [s]
             (cond-> (-> s
                         (update :accepted conj {:pose pose :behind current-behind
                                                 :remaining current-ahead :name name})
                         (assoc :remaining current-ahead))
               ;; if the new remaining is already convex, propose the
               ;; terminal placement instead of staying put — "tutto verde"
               ;; becomes closable with one further Enter
               ahead-convex?
               (assoc-in [:live-pose :position]
                         (terminal-position current-ahead (:position pose) (:heading pose))))))
    (recompute!)))

(defn- fmt-num
  "Same rounding convention as edit-bezier's fmt-num: nearest integer
   within 1e-9, else 2 decimals with trailing zeros trimmed. 'Numeri
   liberi non arrotondati' is about never snapping the synthesized VALUE
   to a grid during editing — display precision is a separate concern."
  [v]
  (let [r (js/Math.round v)]
    (if (< (js/Math.abs (- v r)) 1e-9)
      (str r)
      (let [s (.toFixed v 2)]
        (cond
          (str/ends-with? s "00") (subs s 0 (- (count s) 3))
          (str/ends-with? s "0") (subs s 0 (dec (count s)))
          :else s)))))

(defn- delta->cmds-str
  [delta]
  (->> [:th :tv :tr :f :rt :u]
       (keep (fn [k] (when-let [v (k delta)] (str "(" (name k) " " (fmt-num v) ")"))))
       (str/join " ")))

(defn- build-nested-destructure
  [piece-names final-name]
  (if (empty? piece-names)
    final-name
    (str "{" (first piece-names) " :behind "
         (build-nested-destructure (rest piece-names) final-name) " :ahead}")))

(defn- build-emitted-code
  [{:keys [source-expr accepted entry-pose]}]
  (if (empty? accepted)
    source-expr
    (let [poses (into [entry-pose] (map :pose accepted))
          deltas (mapv (fn [[a b]] (turtle/synthesize-delta a b))
                       (partition 2 1 poses))
          names (mapv :name accepted)
          path-forms (str/join " "
                               (map (fn [delta nm] (str (delta->cmds-str delta) " (mark :" nm ")"))
                                    deltas names))
          marks-vec (str "[" (str/join " " (map #(str ":" %) names)) "]")
          piece-names (mapv #(str "piece-" (inc %)) (range (count accepted)))
          destructure-str (build-nested-destructure piece-names "remaining")
          body-str (str "[" (str/join " " (conj piece-names "remaining")) "]")]
      (str "(let [" destructure-str "\n      (mesh-split " source-expr "\n"
           "                  (path " path-forms ")\n"
           "                  " marks-vec ")]\n"
           "  " body-str ")"))))

(defn- commit-session!
  "plane-state = :terminal: no new mark — closing is stopping, not cutting.
   The current :remaining is already the final piece."
  []
  (let [{:keys [edit-mesh-split-from edit-mesh-split-to from-repl] :as s} @session
        code (build-emitted-code s)]
    (if from-repl
      (state/capture-println code)
      (do (modal/replace-source! edit-mesh-split-from edit-mesh-split-to code)
          (state/capture-println (str "edit-mesh-split: " code))))
    (cleanup!)
    (modal/release!)
    (reset! session nil)
    (when-not from-repl (modal/run-definitions!))))

(defn- accept-or-commit!
  []
  (case (:plane-state @session)
    :active (accept-cut!)
    :terminal (commit-session!)
    nil))

(defn- teleport-and-commit!
  "Ctrl/Cmd+Enter accelerator: teleport to terminal placement, then run
   the SAME accept-or-commit! — sugar on two already-defined primitives,
   no separate emission path."
  []
  (let [{:keys [remaining live-pose]} @session
        pose (select-keys live-pose [:position :heading :up])
        term-pos (terminal-position remaining (:position pose) (:heading pose))]
    (swap! session assoc-in [:live-pose :position] term-pos)
    (recompute!)
    (accept-or-commit!)))

(defn- undo!
  []
  (let [{:keys [accepted initial-mesh]} @session]
    (if (empty? accepted)
      (state/capture-println "edit-mesh-split: nothing to undo")
      (let [popped (peek accepted)
            rest-stack (pop accepted)
            new-remaining (if (seq rest-stack) (:remaining (peek rest-stack)) initial-mesh)]
        (swap! session
               (fn [s]
                 (-> s
                     (assoc :accepted rest-stack :remaining new-remaining)
                     (assoc-in [:live-pose :position] (:position (:pose popped)))
                     (assoc-in [:live-pose :heading] (:heading (:pose popped)))
                     (assoc-in [:live-pose :up] (:up (:pose popped))))))
        (recompute!)))))

(def ^:private marker-prefix "(edit-mesh-split")

(defn- find-marker
  [text]
  (modal/find-form-bounds text marker-prefix))

(defn- cleanup!
  []
  (modal/unmount-panel! (:panel-el @session))
  (modal/remove-keydown! (:key-handler @session))
  (gizmo/close!)
  (viewport/set-turtle-source! :global)
  (viewport/clear-preview!))

(defn cancel!
  "Esc: cancel everything unconditionally, emit nothing. No verbatim body
   to restore (unlike edit-attach) — the marker just rewrites down to the
   plain mesh-split form with the ORIGINAL (pre-session) arguments, same
   as if the tool had never been opened. strip-head can't be used as-is
   (mesh-split's arg list differs from edit-mesh-split's own), so this
   rebuilds the (mesh-split ...) call directly from what request! stored."
  []
  (let [{:keys [edit-mesh-split-from edit-mesh-split-to from-repl
                source-expr orig-path-text orig-marks-text]} @session]
    (when-not from-repl
      (modal/replace-source!
       edit-mesh-split-from edit-mesh-split-to
       (cond
         orig-marks-text (str "(mesh-split " source-expr " " orig-path-text " " orig-marks-text ")")
         orig-path-text (str "(mesh-split " source-expr " " orig-path-text ")")
         :else source-expr)))
    (cleanup!)
    (modal/release!)
    (reset! session nil)
    (when-not from-repl (modal/run-definitions!))))

;; ============================================================
;; Keyboard handler — reuses edit-attach's step/angle scheme (no :scale
;; target here) verbatim: Tab cycles, digits set the active value, arrows
;; gesture, Backspace undoes. Up/Down=f/tv, Left/Right=rt/th,
;; Shift+Up/Down=u/tr.
;; ============================================================

(def ^:private code->digit
  {"Digit0" "0" "Digit1" "1" "Digit2" "2" "Digit3" "3" "Digit4" "4"
   "Digit5" "5" "Digit6" "6" "Digit7" "7" "Digit8" "8" "Digit9" "9"
   "Numpad0" "0" "Numpad1" "1" "Numpad2" "2" "Numpad3" "3" "Numpad4" "4"
   "Numpad5" "5" "Numpad6" "6" "Numpad7" "7" "Numpad8" "8" "Numpad9" "9"})

(defn- flush-digit-buffer!
  []
  (let [buf (:digit-buffer @session)]
    (when (seq buf)
      (let [val (js/parseFloat buf)]
        (when (and (pos? val) (js/isFinite val))
          (case (:digit-target @session)
            :angle (swap! session assoc :angle-step val)
            (swap! session assoc :step val))
          (gizmo/set-snap! (select-keys @session [:step :angle-step]))))
      (swap! session assoc :digit-buffer "" :digit-target :step)
      (update-panel-display!))))

(defn- on-keydown
  [e]
  (when (:entered? @session)
    (let [key (.-key e)
          code (.-code e)
          shift? (.-shiftKey e)
          mod? (or (.-ctrlKey e) (.-metaKey e))
          digit (code->digit code)
          m (:digit-target @session)
          s (:step @session)
          a (:angle-step @session)]
      (cond
        (= key "Tab")
        (do (.preventDefault e) (.stopPropagation e)
            (flush-digit-buffer!)
            (swap! session update :digit-target {:step :angle :angle :step})
            (update-panel-display!))

        digit
        (do (.preventDefault e) (.stopPropagation e)
            (swap! session update :digit-buffer str digit)
            (update-panel-display!))

        (and (or (= key ".") (= key ",")) (seq (:digit-buffer @session)))
        (do (.preventDefault e) (.stopPropagation e)
            (when-not (str/includes? (:digit-buffer @session) ".")
              (swap! session update :digit-buffer str "."))
            (update-panel-display!))

        (and (= key "Enter") mod?)
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (teleport-and-commit!))

        (= key "Enter")
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (accept-or-commit!))

        (= key "Escape")
        (do (.preventDefault e) (.stopPropagation e) (cancel!))

        (and (= key "ArrowUp") (not shift?) (not (.-altKey e)))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (apply-gesture! (case m :angle #(turtle/tv % a) #(turtle/f % s))))

        (and (= key "ArrowDown") (not shift?) (not (.-altKey e)))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (apply-gesture! (case m :angle #(turtle/tv % (- a)) #(turtle/f % (- s)))))

        (and (= key "ArrowLeft") (not shift?) (not (.-altKey e)))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (apply-gesture! (case m :angle #(turtle/th % a) #(turtle/move-right % (- s)))))

        (and (= key "ArrowRight") (not shift?) (not (.-altKey e)))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (apply-gesture! (case m :angle #(turtle/th % (- a)) #(turtle/move-right % s))))

        (and (= key "ArrowUp") shift? (not (.-altKey e)))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (apply-gesture! (case m :angle #(turtle/tr % a) #(turtle/move-up % s))))

        (and (= key "ArrowDown") shift? (not (.-altKey e)))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!)
            (apply-gesture! (case m :angle #(turtle/tr % (- a)) #(turtle/move-up % (- s)))))

        (= key "Backspace")
        (do (.preventDefault e) (.stopPropagation e)
            (if (seq (:digit-buffer @session))
              (do (swap! session update :digit-buffer #(subs % 0 (max 0 (dec (count %)))))
                  (when (empty? (:digit-buffer @session))
                    (swap! session assoc :digit-target :step))
                  (update-panel-display!))
              (undo!)))

        :else nil))))

;; ============================================================
;; UI Panel
;; ============================================================

(defn- fmt-pct
  [v]
  (str (Math/round v) "%"))

(defn- volume-status-text
  "The Part B status line — sides named and quantified, e.g. 'behind 42% (convex) —
   ahead 58% (concave)'. Symmetric on purpose (both sides get a convexity word): the
   brief's own example only labels one side, read as shorthand rather than a spec,
   since either side can independently be convex or concave."
  [{:keys [plane-state behind-pct ahead-pct behind-convex? ahead-convex?]}]
  (str "behind " (fmt-pct behind-pct)
       (if (= plane-state :no-op) "" (str " (" (if behind-convex? "convex" "concave") ")"))
       " — ahead " (fmt-pct ahead-pct)
       (if (= plane-state :terminal) "" (str " (" (if ahead-convex? "convex" "concave") ")"))))

(defn update-panel-display!
  []
  (when-let [panel (:panel-el @session)]
    (let [{:keys [digit-buffer digit-target step angle-step plane-state accepted]
           :as s} @session
          editing? (seq digit-buffer)
          update-param! (fn [selector value-str param-key]
                          (when-let [el (.querySelector panel selector)]
                            (set! (.-textContent el)
                                  (if (and editing? (= digit-target param-key))
                                    (str digit-buffer "_")
                                    value-str))
                            (let [span (.-parentNode el)]
                              (if (= digit-target param-key)
                                (.add (.-classList span) "pilot-active-param")
                                (.remove (.-classList span) "pilot-active-param")))))]
      (update-param! ".ems-step-value" (str step "mm") :step)
      (update-param! ".ems-angle-value" (str angle-step "°") :angle)
      (when-let [status-el (.querySelector panel ".ems-status")]
        (set! (.-textContent status-el)
              (case plane-state
                :no-op "cut is a no-op (plane misses the piece) — Enter disabled"
                :terminal "Enter closes the session — remaining accepted as final piece"
                "Enter accepts this cut and continues")))
      (when-let [vol-el (.querySelector panel ".ems-volumes")]
        (set! (.-textContent vol-el) (volume-status-text s)))
      (when-let [cmd-el (.querySelector panel ".pilot-cmd-list")]
        (set! (.-textContent cmd-el)
              (if (empty? accepted)
                "(no cuts accepted yet)"
                (str/join ", " (map :name accepted))))))))

(defn- create-panel!
  [mesh-name]
  (let [panel (.createElement js/document "div")]
    (set! (.-id panel) "edit-mesh-split-panel")
    (set! (.-innerHTML panel)
          (str "<div class='pilot-header'>edit-mesh-split " mesh-name "</div>"
               "<div class='pilot-controls'>"
               "<span>Step: <span class='ems-step-value'>" (:step @session) "mm</span></span>"
               "<span>Angle: <span class='ems-angle-value'>" (:angle-step @session) "°</span></span>"
               "</div>"
               "<div class='pilot-commands ems-status'>Enter accepts this cut and continues</div>"
               "<div class='pilot-commands ems-volumes'>behind — · ahead —</div>"
               "<div class='pilot-commands pilot-cmd-list'>(no cuts accepted yet)</div>"
               "<div class='pilot-commands modal-help'>"
               "Tab: cycle step/angle · digits: set active value · "
               "←→↑↓: move (step, f/rt) / rotate (angle, th/tv) · "
               "Shift+↑↓: u / tr · "
               "Gizmo: drag arrows (translate) / rings (rotate) in the viewport — only "
               "the plane itself moves with the drag, the pieces don't · "
               "Shift+drag: free (bypass grid) · colors update live during the drag, "
               "throttled to what the mesh can afford. "
               "Enter: accept :behind (closes the session when the plane is in "
               "terminal placement) · Ctrl/Cmd+Enter: teleport to terminal + accept · "
               "Backspace: undo last accepted cut · Esc: cancel everything, emit nothing. "
               "Plane color: grey = no-op cut, gold = terminal (Enter closes), blue = active. "
               "Halves: green = convex, red = concave; behind is solid (what Enter "
               "takes), ahead is washed. Cone points ahead (heading), same direction "
               "as the turtle's own nose — Enter takes what's behind it, same "
               "convention as extrude (material trails behind). Accepted pieces are "
               "shown as a grey wireframe."
               "</div>"
               "<div class='pilot-buttons'>"
               "<button class='pilot-btn pilot-btn-undo'>Undo</button>"
               "<button class='pilot-btn pilot-btn-ok'>Accept/Commit</button>"
               "<button class='pilot-btn pilot-btn-cancel'>Cancel</button>"
               "</div>"))
    (.addEventListener (.querySelector panel ".pilot-btn-undo") "click" (fn [_] (undo!)))
    (.addEventListener (.querySelector panel ".pilot-btn-ok") "click" (fn [_] (accept-or-commit!)))
    (.addEventListener (.querySelector panel ".pilot-btn-cancel") "click" (fn [_] (cancel!)))
    (modal/mount-panel! panel)
    panel))

;; ============================================================
;; Re-entry — walk the ALREADY-COMPUTED composite (Part 1's own return
;; value) instead of replaying source text, since mesh-split's composite
;; already has exactly the shape a session needs.
;; ============================================================

(defn- composite->accepted
  [composite poses names]
  (if (or (empty? poses) (not (map? composite)) (= :mesh (:type composite)))
    []
    (let [{:keys [behind ahead]} composite]
      ;; names come from the caller's marks-vector, so they're keywords
      ;; (:cut-1) here — normalize to the bare string every OTHER :name
      ;; (next-cut-name's own output) already uses, so emission's "(mark :"
      ;; + name interpolation never double-colons a re-entered mark.
      (cons {:pose (first poses) :behind behind :remaining ahead :name (name (first names))}
            (composite->accepted ahead (rest poses) (rest names))))))

;; ============================================================
;; Entry points (two-phase, same shape as edit-attach/edit-bezier)
;; ============================================================

(defn ^:export active? [] (some? @session))

(defn- clear-orphan!
  []
  (let [s @session
        live? (and s (:entered? s) (some-> (:panel-el s) .-parentNode))]
    (when (and (= :edit-mesh-split @state/interactive-mode) (not live?))
      (when s (cleanup!))
      (reset! session nil)
      (modal/release!))))

(defn ^:export request!
  "Called by the edit-mesh-split macro during script evaluation.
   quoted-mesh:     the mesh source form as written (unevaluated)
   mesh-value:      the evaluated mesh/keyword/SDF value (RAW — mesh-split
                    itself resolves it; this fn resolves it independently
                    for :initial-mesh, undo-to-start)
   path-value:      the evaluated path, or nil for a fresh (1-arg) session
   marks-value:     the evaluated marks-vector, or nil (defaults to every
                    mark in path, in order — same as mesh-split's own 2-arg
                    default)
   composite-value: (mesh-split mesh-value path-value marks-value) already
                    evaluated by the macro — the value this eval previews"
  [quoted-mesh mesh-value path-value marks-value composite-value]
  (if (modal/consume-skip!)
    composite-value
    (let [_ (clear-orphan!)
          initial-mesh (resolve-to-mesh mesh-value)]
      (when-not (and (map? initial-mesh) (:vertices initial-mesh))
        (throw (js/Error. (str "edit-mesh-split: argument must be a mesh — got "
                               (pr-str mesh-value)))))
      (let [from-repl (not= :definitions @state/eval-source-var)
            [source-expr orig-path-text orig-marks-text edit-mesh-split-from edit-mesh-split-to]
            (if from-repl
              [(str quoted-mesh) nil nil nil nil]
              (let [text (cm/get-value)
                    [from to] (find-marker text)]
                (when (nil? from)
                  (throw (js/Error. (str "edit-mesh-split: cannot find '" marker-prefix " …)' in editor"))))
                (let [elements (cm/parse-form-elements (subs text from to))]
                  [(second elements) (get elements 2) (get elements 3) from to])))
            entry-pose (or (state/get-turtle-pose)
                           {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
            names (when path-value
                    (or marks-value (impl/path-mark-names-in-order path-value)))
            poses (when path-value
                    (let [anchors (turtle/resolve-marks entry-pose path-value)]
                      (mapv anchors names)))
            accepted (if (and composite-value (map? composite-value) (not= :mesh (:type composite-value)))
                       (vec (composite->accepted composite-value poses names))
                       [])
            remaining (if (seq accepted) (:remaining (peek accepted)) initial-mesh)
            live-pose-thin (if (seq accepted) (:pose (peek accepted)) entry-pose)]
        (modal/claim! :edit-mesh-split)
        (reset! session
                {:source-expr source-expr
                 :orig-path-text orig-path-text
                 :orig-marks-text orig-marks-text
                 :initial-mesh initial-mesh
                 :remaining remaining
                 :accepted accepted
                 :entry-pose entry-pose
                 :live-pose (-> (turtle/make-turtle)
                                (assoc :position (:position live-pose-thin)
                                       :heading (:heading live-pose-thin)
                                       :up (:up live-pose-thin)))
                 :step 5
                 :angle-step 15
                 :digit-buffer ""
                 :digit-target :step
                 :from-repl from-repl
                 :edit-mesh-split-from edit-mesh-split-from
                 :edit-mesh-split-to edit-mesh-split-to
                 :entered? false})
        composite-value))))

(defn requested?
  []
  (and (some? @session) (not (:entered? @session))))

(defn enter!
  []
  (when (requested?)
    (swap! session assoc :entered? true)
    (let [handler on-keydown]
      (swap! session assoc :key-handler handler)
      (modal/install-keydown! handler))
    (let [panel (create-panel! (:source-expr @session))]
      (swap! session assoc :panel-el panel))
    ;; Drag gizmo — translate+rotate only (no stretch, a cut plane has no scale
    ;; gesture); on-commit reuses the same apply-gesture! path a keyboard press
    ;; already uses. :nudge-mesh? false: the drag must NEVER visibly move the cut
    ;; pieces themselves (live feedback 2026-07-12 — only the PLANE is actually
    ;; changing, the pieces moving read as "the mesh is moving" and was confusing).
    ;; on-drag/-start/-end implement live-during-drag recompute (also live feedback
    ;; 2026-07-12, superseding this addendum's original release-only policy) — see
    ;; on-gizmo-drag!'s own docstring for the throttle.
    (gizmo/enter! (select-keys (:live-pose @session) [:position :heading :up])
                  {:handles #{:translate :rotate}
                   :step (:step @session)
                   :angle-step (:angle-step @session)
                   :nudge-mesh? false}
                  {:on-commit on-gizmo-commit!
                   :on-drag-start on-gizmo-drag-start!
                   :on-drag on-gizmo-drag!
                   :on-drag-end on-gizmo-drag-end!})
    (recompute!)
    (state/capture-println
     (str "edit-mesh-split: interactive mode for " (:source-expr @session)
          " — arrows to move/rotate the cut plane, or drag the gizmo arrows/rings in "
          "the viewport, Enter to accept :behind "
          "(closes the session in terminal placement), Ctrl/Cmd+Enter to "
          "teleport+accept, Backspace to undo the last accepted cut, Esc to cancel"))))

(defn- force-close!
  []
  (when @session
    (cleanup!)
    (modal/release!)
    (reset! session nil)))

(modal/register-kind! :edit-mesh-split
                      {:requested? requested?
                       :enter! enter!
                       :active? active?
                       :cancel! cancel!
                       :close! force-close!})
