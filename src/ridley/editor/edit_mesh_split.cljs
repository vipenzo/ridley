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
            [ridley.editor.mesh-split-tree :as mtree]
            [ridley.manifold.core :as manifold]
            [ridley.turtle.core :as turtle]
            [ridley.sdf.core :as sdf]
            [ridley.scene.registry :as registry]
            [ridley.viewport.core :as viewport]))

(declare cleanup! render! recompute! update-panel-display! schedule-mirror-check!
         clear-mirror-badge! set-status-message! gesture-availability cut-frame-sig
         cut-frame-ready ensure-cut-frame!)

;; ============================================================
;; State
;; ============================================================

(defonce session (atom nil))

;; TREE session (dev-docs/brief-mesh-components-tree.md, Parti 3–4). The single
;; :remaining/:accepted-stack of the guillotine became a forest of pieces:
;; {:source-expr   "block"           ; source text of the mesh argument
;;  :initial-mesh  <mesh>             ; resolved, uncut — the root piece's mesh
;;  :tree          <mtree tree>       ; PURE structural tree (pieces/log/current);
;;                                      owns ids, origins, gesture-log, naming,
;;                                      emission — see ridley.editor.mesh-split-tree
;;  :piece-meshes  {id {:mesh <mesh> :manifold <js or nil>}}  ; WASM side-table,
;;                                      keyed by tree piece id; :manifold is the
;;                                      kept-alive object (created lazily when a
;;                                      piece becomes current, .delete'd on undo/
;;                                      cancel/commit — no per-tick re-conversion)
;;  :entry-pose    {:position :heading :up}  ; every emitted path resolves here (A2)
;;  :live-pose     <full turtle state>  ; the cut plane == the turtle
;;  :step 5 :angle-step 15 :digit-buffer "" :digit-target :step  ; :step|:angle
;;  :current-behind <mesh or nil> :current-ahead <mesh or nil>   ; live split of
;;                                      the CURRENT piece at :live-pose
;;  :behind-finished? :ahead-finished?   ; live per-component finiteness (Parte 2)
;;  :behind-count :ahead-count           ; live component counts (badge when >1)
;;  :plane-state    :no-op | :terminal | :active
;;  :reveal-all?    ; addendum: view-only focus/reveal toggle (r)
;;  :labels-shown?  ; whether world-anchored labels are currently up (reveal only)
;;  :labeled-tree   ; the :tree identity the scene labels were last built for
;;  :symmetry-cache {piece-id {:planes […] :index i}}  ; Part 4: cached symmetry-planes
;;  :symmetry-pending? :mirror-gate? :mirror-pending? :mirror-confirmed? :mirror-timer  ; Part 4 badges
;;  :reflex-cache {piece-id {:cands […] :index i}}     ; reflex brief: cached cut-candidates :reflex
;;  :reflex-pending?  ; reflex brief: the piece's reflex-edge scan is running (propose-and-cycle, like symmetry)
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
(def ^:private color-open-piece 0x4477aa)   ; a non-current open piece (clickable)

;; ============================================================
;; Pieces & Manifold keep-alive
;; ============================================================
;; The tree owns the STRUCTURE (ids/origins/log/current); this side-table owns
;; the meshes and their kept-alive Manifold objects. A piece's Manifold is
;; created once, the first time it is needed for a split, and reused every plane
;; tick (manifold/split-live) so a keystroke no longer pays the ~8ms mesh→
;; manifold conversion (accertamento B2). Every create is matched by a .delete on
;; undo-removal / cancel / commit — the leak-free discipline Parte 3 requires.

(defn- current-id [] (:current (:tree @session)))
(defn- piece-mesh [id] (get-in @session [:piece-meshes id :mesh]))
(defn- current-mesh [] (piece-mesh (current-id)))

(defn- store-piece!
  "Record a piece's mesh in the side-table (its Manifold is created lazily)."
  [id mesh]
  (swap! session assoc-in [:piece-meshes id] {:mesh mesh}))

(defn- ensure-manifold!
  "The kept-alive Manifold for piece `id`, created from its mesh on first use and
   cached in the side-table. Returns nil if the mesh can't be converted (caller
   falls back to the mesh-based split path)."
  [id]
  (or (get-in @session [:piece-meshes id :manifold])
      (when-let [mesh (piece-mesh id)]
        (when-let [mf (manifold/mesh->manifold mesh)]
          (swap! session assoc-in [:piece-meshes id :manifold] mf)
          mf))))

(defn- free-piece!
  "Delete piece `id`'s kept-alive Manifold (if any) and drop it from the
   side-table — called on undo-removal and, en masse, on session end."
  [id]
  (when-let [^js mf (get-in @session [:piece-meshes id :manifold])]
    (.delete mf))
  (swap! session update :piece-meshes dissoc id))

(defn- free-all-manifolds!
  "Delete every kept-alive Manifold — the no-leak guarantee on cancel/commit/
   force-close (the report must confirm nothing survives the cancel path)."
  []
  (doseq [[_ {:keys [^js manifold]}] (:piece-meshes @session)]
    (when manifold (.delete manifold))))

(defn- piece-report
  "The cached {:finished? :count} report of a tree piece (Parte 2)."
  [id]
  (select-keys (get-in @session [:tree :pieces id]) [:finished? :count]))

;; ============================================================
;; Geometry helpers — plane quad, orientation cone, terminal placement
;; ============================================================

(defn- bbox-corners
  [[[xmin xmax] [ymin ymax] [zmin zmax]]]
  (for [x [xmin xmax] y [ymin ymax] z [zmin zmax]] [x y z]))

(defn- bbox-center
  [mesh]
  (let [[[xmin xmax] [ymin ymax] [zmin zmax]] (sdf/mesh-bounds mesh)]
    [(* 0.5 (+ xmin xmax)) (* 0.5 (+ ymin ymax)) (* 0.5 (+ zmin zmax))]))

(defn- reposition-plane-for!
  "Move the live plane through the middle of piece `id` (its bbox centre), keeping
   the current heading/up — used whenever the current piece switches to a piece
   OTHER than the one the guillotine was cutting (an explicit select/cycle, or a
   cut/undo that lands on a different branch), so the plane always sits on the
   piece you are about to cut."
  [id]
  (when-let [mesh (piece-mesh id)]
    (swap! session assoc-in [:live-pose :position] (bbox-center mesh))))

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
  "Keyboard steps, gizmo-commit, and throttled gizmo-drag ticks all land here —
   the only place the CURRENT piece is (re)split against `working-pose`. Uses the
   current piece's kept-alive Manifold (manifold/split-live) so a plane nudge no
   longer pays the ~8ms mesh→manifold conversion (Parte 3 keep-alive / accertamento
   B2); the split also returns both halves' volumes, so no get-mesh-status re-
   conversion either. Measures its own cost into :drag-recompute-cost-ms so
   on-gizmo-drag!'s throttle adapts (a cheap mesh updates nearly every frame, a
   dense one backs off — dev-docs/addendum-brief-edit-mesh-split.md)."
  []
  (when-let [{:as s} @session]
    (let [t0 (js/performance.now)
          cur (current-id)
          cur-mesh (piece-mesh cur)
          mf (ensure-manifold! cur)
          {:keys [position heading]} (working-pose s)
          offset (turtle/dot heading position)
          {:keys [ahead behind ahead-volume behind-volume]}
          (if mf
            (manifold/split-live mf heading offset cur-mesh)
            (manifold/split-by-plane cur-mesh heading offset))
          behind-empty? (empty? (:faces behind))
          ahead-empty? (empty? (:faces ahead))
          plane-state (cond behind-empty? :no-op ahead-empty? :terminal :else :active)
          behind-vol (if behind-empty? 0 (or behind-volume 0))
          ahead-vol (if ahead-empty? 0 (or ahead-volume 0))
          total-vol (+ behind-vol ahead-vol)
          ;; Per-component finiteness (Parte 2): green = every connected component
          ;; convex. Only the two live halves are recomputed per tick — the current
          ;; piece's own report is cached on the tree (computed when it was created).
          behind-report (when-not behind-empty? (manifold/component-report behind))
          ahead-report  (when-not ahead-empty?  (manifold/component-report ahead))
          cost-ms (- (js/performance.now) t0)]
      (swap! session assoc
             :current-behind behind :current-ahead ahead
             :behind-finished? (:finished? behind-report)
             :behind-count     (:count behind-report)
             :ahead-finished?  (:finished? ahead-report)
             :ahead-count      (:count ahead-report)
             :plane-state plane-state
             :behind-pct (if (pos? total-vol) (* 100.0 (/ behind-vol total-vol)) 0.0)
             :ahead-pct (if (pos? total-vol) (* 100.0 (/ ahead-vol total-vol)) 0.0)
             ;; Any real action ends here → dismiss a transient no-op warning (Part A):
             ;; only FAILED gestures (which never recompute) leave one standing.
             :status-message nil
             :last-drag-recompute (js/Date.now)
             :drag-recompute-cost-ms cost-ms)
      ;; Part 4 mirror badge: free gate now, debounced B6 confirm in the background.
      (schedule-mirror-check! behind-vol ahead-vol)
      ;; Keep the cut-candidate strip current: recompute the frame only when its
      ;; DEFINING axis changed (piece / heading in step mode / position in angle mode),
      ;; a no-op while nudging the active DOF — so the common case pays nothing.
      (ensure-cut-frame!)))
  (render!)
  (update-panel-display!))

;; ============================================================
;; Rendering
;; ============================================================

(defn- half-preview-item
  "role = :behind (what Enter accepts — rendered solid/opaque, the 'figure') or :ahead
   (rendered washed/near-ghost, the 'ground') — solidity carries the behind/ahead
   distinction, hue stays reserved for the finiteness verdict (green = every
   connected component convex, red = a component is concave — Parte 2; one visual
   variable per semantic dimension, dev-docs/addendum-brief-edit-mesh-split.md)."
  [mesh finished? role]
  (when (and mesh (seq (:faces mesh)))
    {:type :mesh
     :data (assoc mesh :material {:color (if finished? color-convex color-concave)
                                  :opacity (if (= role :behind) 0.88 0.18)
                                  :double-sided true})}))

(defn- ghost-item
  "A FINISHED leaf (every component convex — done). Wireframe, not a low-alpha
   solid fill (live feedback 2026-07-12: a low-alpha grey solid read as blue-ish
   under the scene's lighting and blurred into the plane quad — a wireframe is
   unmistakably NOT a live solid)."
  [mesh]
  {:type :wireframe
   :data (assoc mesh :material {:color color-ghost})})

(defn- open-piece-item
  "A non-current OPEN leaf, shown ONLY in the reveal state (addendum 3 Part B).
   Neutral (no convexity tint — the semaphore belongs to the current piece alone)
   and near-solid, so reveal reads as 'here is the whole decomposition' before you
   drop back to focus on the current piece."
  [mesh alpha]
  {:type :mesh
   :data (assoc mesh :material {:color color-open-piece :opacity alpha :double-sided true})})

(defn- plane-items
  "The quad + orientation cone at `pose` — always render!'s items 0 and 1, in this
   order, a contract on-gizmo-drag!'s cheap per-tick update relies on. Colored by
   the session's current :plane-state."
  [pose]
  (let [plane-color (case (:plane-state @session)
                      :no-op color-no-op
                      :terminal color-terminal
                      color-active)]
    [{:type :stamp :data (quad-stamp pose (current-mesh) plane-color)}
     {:type :mesh :data (orientation-cone pose plane-color)}]))

(def ^:private open-piece-alpha-reveal 0.62)   ; near-solid: reveal shows the piece, not a hint

(defn- other-piece-items
  "Addendum 3 Part B — two scene states, no middle ground. In the WORK state
   (default) NOTHING but the current piece is drawn, so this returns nil: the plane
   and the current piece's live halves are the whole scene, and n/p navigation is
   felt as the current appearing while the previous vanishes. Only in the REVEAL
   state is every OTHER leaf drawn — finished leaves as ghost wireframes, still-open
   leaves as near-solid neutral bodies — to re-orient, then back to focus."
  [s]
  (when (:reveal-all? s)
    (let [tree (:tree s)
          cur (:current tree)]
      (for [id (mtree/leaf-ids tree)
            :when (not= id cur)
            :let [mesh (piece-mesh id)]
            :when (and mesh (seq (:faces mesh)))]
        (if (:finished? (get-in tree [:pieces id]))
          (ghost-item mesh)
          (open-piece-item mesh open-piece-alpha-reveal))))))

(defn- scene-labels
  "One BILLBOARD (camera-facing) label per leaf at its centre, showing the SAME name
   the emission uses (one identity across scene, panel, code). Shown ONLY in the
   reveal state (addendum 3 Part C): reveal is consultation, so legibility from any
   camera angle beats the fixed-in-the-world anchoring addendum 2 tried and that use
   falsified (occluded, illegible). The current piece's label is brightened."
  [s]
  (let [tree (:tree s)
        cur (:current tree)]
    (for [id (mtree/leaf-ids tree)
          :let [mesh (piece-mesh id)]
          :when (and mesh (seq (:faces mesh)))]
      {:text (mtree/piece-name tree id)
       :position (bbox-center mesh)
       :color (if (= id cur) 0xffffff 0x8899aa)})))

(defn- render!
  []
  (when-let [{:keys [current-behind current-ahead
                     behind-finished? ahead-finished?] :as s} @session]
    (let [pose (working-pose s)
          items (concat
                 (plane-items pose)
                 (remove nil? [(half-preview-item current-behind behind-finished? :behind)
                               (half-preview-item current-ahead ahead-finished? :ahead)])
                 (other-piece-items s))]
      (viewport/show-preview! (vec items))
      ;; Scene labels are on-demand: shown ONLY in the reveal state, as billboard
      ;; camera-facing labels (addendum 3 Part C — always legible; no labels at all
      ;; in the work state, where the current piece's identity is the panel row).
      ;; Rebuild when reveal flips on, or when the tree changes while revealed; clear
      ;; when it flips off.
      (let [want (boolean (:reveal-all? s))
            shown (boolean (:labels-shown? s))]
        (cond
          (and want (or (not shown) (not (identical? (:tree s) (:labeled-tree s)))))
          (do (viewport/set-labels! (vec (scene-labels s)))
              (swap! session assoc :labels-shown? true :labeled-tree (:tree s)))
          (and shown (not want))
          (do (viewport/clear-labels!)
              (swap! session assoc :labels-shown? false))))
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
;; Transient panel messages (addendum 3 Part A — no silent no-op)
;; ============================================================

(defn- set-status-message!
  "Write a transient reason into the panel's message line. A key press whose gesture
   can't act (a disabled button's keyboard twin) calls this so the reason is visible
   where the user is actually looking — the panel — instead of the REPL console
   (capture-println lands there and only after the eval finishes, i.e. never during a
   live modal session). Auto-clears after a few seconds; the next real action clears
   it early (recompute! drops :status-message)."
  [msg]
  (when-let [t (:status-msg-timer @session)] (js/clearTimeout t))
  (swap! session assoc
         :status-message msg
         :status-msg-timer (js/setTimeout
                            (fn []
                              (when @session
                                (swap! session assoc :status-message nil :status-msg-timer nil)
                                (update-panel-display!)))
                            4000))
  (update-panel-display!))

;; ============================================================
;; Accept / commit / undo / cancel
;; ============================================================

(defn- accept-cut!
  "plane-state = :active: cut the current piece into a behind + ahead node
   (mtree/cut), store their meshes, and let the tree advance current (stays on the
   ahead if it is still open — guillotine continuity — else the next open leaf).
   If current jumped to a DIFFERENT piece, the plane repositions onto it."
  []
  (let [{:keys [live-pose current-behind current-ahead mirror-confirmed?
                behind-finished? behind-count ahead-finished? ahead-count] :as s} @session
        cur (current-id)
        pose (select-keys live-pose [:position :heading :up])
        {:keys [tree behind ahead]}
        (mtree/cut (:tree s) cur pose
                   {:finished? behind-finished? :count behind-count}
                   {:finished? ahead-finished? :count ahead-count}
                   ;; a confirmed-mirror cut is tagged → emission comment + enables
                   ;; the mirror-decompose gesture on its halves (Parts 4/5)
                   (when mirror-confirmed? {:mirror? true}))]
    (store-piece! behind current-behind)
    (store-piece! ahead current-ahead)
    (swap! session assoc :tree tree)
    (when (not= (:current tree) ahead)
      (reposition-plane-for! (:current tree)))
    (recompute!)))

(defn- separate!
  "The 'separa componenti' gesture (Parte 3): decompose the current piece into its
   connected components (mtree/separate — Part 1's contract order), materializing
   each as a distinct tree piece. A structural gesture (logged, undoable). A no-op
   with a note when the piece is a single component (nothing to separate — it needs
   a plane, not a separation)."
  []
  (let [s @session
        cur (current-id)
        comp-meshes (manifold/mesh-components (piece-mesh cur))]
    (if (< (count comp-meshes) 2)
      (set-status-message! (:reason (:separate (gesture-availability @session))))
      (let [reports (mapv (fn [c] {:finished? (manifold/convex? c) :count 1}) comp-meshes)
            {:keys [tree components]} (mtree/separate (:tree s) cur reports)]
        (doseq [[cid cmesh] (map vector components comp-meshes)]
          (store-piece! cid cmesh))
        (swap! session assoc :tree tree)
        (reposition-plane-for! (:current tree))
        (state/capture-println (str "edit-mesh-split: separated into " (count comp-meshes) " pieces"))
        (recompute!)))))

;; ============================================================
;; Symmetry (dev-docs/brief-mesh-symmetry.md, Parts 4-5)
;; ============================================================

(def ^:private mirror-gate 0.02)          ; free volumetric gate (matches manifold's)
(def ^:private mirror-confirm-epsilon 0.02) ; symdiff confirm (matches manifold's default-mirror-epsilon)
(def ^:private mirror-debounce-ms 300)

(defn- report-of-mesh [m] (select-keys (manifold/component-report m) [:finished? :count]))

(defn- teleport-plane-to!
  "Move the live plane onto a pose {:position :heading :up} (a symmetry candidate);
   up only orients the quad."
  [{:keys [position heading up]}]
  (swap! session update :live-pose
         #(assoc % :position position :heading heading :up (or up (:up %)))))

(defn- symmetry-level-text
  "Human phrasing of a symmetry plane's quality from its symmetric-difference ratio
   (0 = exact mirror; the fraction of a half's volume that doesn't overlap its
   reflection). A small non-zero ratio means the halves match EXCEPT a small feature
   (a mounting hole, an internal thread…) — 'quasi simmetrico', which Vincenzo asked
   be shown so the user isn't misled into thinking a near-symmetric part is exact."
  [r]
  (if (or (nil? r) (< r 0.002))
    "simmetria esatta"
    (str "quasi simmetrico (scarto ~" (.toFixed (* 100.0 r) 1) "%)")))

(defn- announce-symmetry-plane!
  "Transient status line when the plane teleports onto symmetry candidate `idx`/`n`,
   naming the quality level so 'quasi' is visible. Called AFTER recompute! (which
   clears :status-message), so it survives."
  [pose idx n]
  (set-status-message!
   (str "piano di simmetria " (inc idx) "/" n " · " (symmetry-level-text (:symmetry-ratio pose)))))

(defn- propose-symmetry-plane!
  "Part 4: teleport the plane to the current piece's first verified symmetry plane;
   repeated presses cycle the candidates. symmetry-planes is cached per piece
   (immutable) and computed the first time behind a visible 'computing' state (the
   ~250-450 ms would otherwise freeze silently). Each teleport announces the plane's
   symmetry level (exact vs 'quasi'). No planes → a note, no move."
  []
  (let [cur (current-id)
        cached (get-in @session [:symmetry-cache cur])]
    (if cached
      (let [planes (:planes cached)]
        (if (empty? planes)
          (set-status-message! (:reason (:symmetry (gesture-availability @session))))
          (let [idx (mod (inc (:index cached)) (count planes))]
            (swap! session assoc-in [:symmetry-cache cur :index] idx)
            (teleport-plane-to! (nth planes idx))
            (recompute!)
            (announce-symmetry-plane! (nth planes idx) idx (count planes)))))
      (do
        (swap! session assoc :symmetry-pending? true)
        (update-panel-display!)
        (js/setTimeout
         (fn []
           (when (and @session (= cur (current-id)))
             (let [planes (manifold/symmetry-planes (piece-mesh cur))]
               (swap! session assoc-in [:symmetry-cache cur] {:planes planes :index 0})
               (swap! session assoc :symmetry-pending? false)
               (if (empty? planes)
                 (set-status-message! (:reason (:symmetry (gesture-availability @session))))
                 (do (teleport-plane-to! (first planes)) (recompute!)
                     (announce-symmetry-plane! (first planes) 0 (count planes)))))))
         0)))))

;; ── Reflex cut candidates (dev-docs/brief-cut-candidates-reflex.md) ──
;; A third candidate species beside symmetry (y) and profile events ([ ]): cuts where
;; the CONCAVITY lives. The candidates are complete poses sparse in space (orientation +
;; position together), with no DOF to order them — so the interaction is propose-and-cycle
;; by salience, IDENTICAL to y, not the next-event navigation of [ ].

(defn- announce-reflex-cut!
  "Transient status line when the plane teleports onto reflex candidate idx/n, naming
   its concavity-mass salience so the cycle order (descending salience) is legible."
  [cand idx n]
  (set-status-message!
   (str "taglio da concavità " (inc idx) "/" n " · salienza " (js/Math.round (:salience cand)))))

(defn- propose-reflex-cut!
  "Teleport the plane onto the current piece's most salient reflex-edge cut candidate;
   repeated presses cycle by descending salience (reflex brief Part 2). The candidates
   (manifold/cut-candidates :mode :reflex) are pose-free and pure, cached per piece
   (immutable mesh) and computed the first time behind a visible pending state (a big
   mesh's edge scan can stutter). A convex piece has no reflex edges → the disabled
   reason, no move. Model identical to propose-symmetry-plane! (y)."
  []
  (let [cur (current-id)
        cached (get-in @session [:reflex-cache cur])]
    (if cached
      (let [cands (:cands cached)]
        (if (empty? cands)
          (set-status-message! (:reason (:reflex (gesture-availability @session))))
          (let [idx (mod (inc (:index cached)) (count cands))]
            (swap! session assoc-in [:reflex-cache cur :index] idx)
            (teleport-plane-to! (:pose (nth cands idx)))
            (recompute!)
            (announce-reflex-cut! (nth cands idx) idx (count cands)))))
      (do
        (swap! session assoc :reflex-pending? true)
        (update-panel-display!)
        (js/setTimeout
         (fn []
           (when (and @session (= cur (current-id)))
             (let [cands (manifold/cut-candidates (piece-mesh cur) {:mode :reflex})]
               (swap! session assoc-in [:reflex-cache cur] {:cands cands :index 0})
               (swap! session assoc :reflex-pending? false)
               (if (empty? cands)
                 (set-status-message! (:reason (:reflex (gesture-availability @session))))
                 (do (teleport-plane-to! (:pose (first cands))) (recompute!)
                     (announce-reflex-cut! (first cands) 0 (count cands)))))))
         0)))))

(defn- clear-mirror-badge!
  []
  (when-let [t (:mirror-timer @session)] (js/clearTimeout t))
  (swap! session assoc :mirror-timer nil :mirror-confirmed? false :mirror-ratio nil :mirror-pending? false))

(defn- schedule-mirror-check!
  "Part 4 badge: the free volumetric gate runs per-tick; if it passes and the plane
   then sits still for the debounce, the B6 confirmation runs in the background and,
   on success, the panel shows behind/ahead are a mirror. Any plane move (a fresh
   recompute!) clears the badge and restarts the timer — the colors never lie."
  [behind-vol ahead-vol]
  (clear-mirror-badge!)
  (let [vtot (+ behind-vol ahead-vol)
        gate? (and (pos? vtot) (< (/ (js/Math.abs (- behind-vol ahead-vol)) vtot) mirror-gate))]
    (swap! session assoc :mirror-gate? gate?)
    (when gate?
      (let [cur (current-id)
            pose (select-keys (working-pose @session) [:position :heading])
            timer (js/setTimeout
                   (fn []
                     (swap! session assoc :mirror-pending? true :mirror-timer nil)
                     (update-panel-display!)
                     (js/setTimeout
                      (fn []
                        (when (and @session (= cur (current-id)))
                          ;; capture the RATIO (not just a boolean) so the badge can
                          ;; show HOW symmetric — exact vs 'quasi' (Vincenzo's ask).
                          (let [r (manifold/mirror-difference-ratio
                                   (piece-mesh cur) (:heading pose) (:position pose))
                                ok? (and (number? r) (<= r mirror-confirm-epsilon))]
                            (swap! session assoc :mirror-confirmed? ok?
                                   :mirror-ratio (when ok? r) :mirror-pending? false)
                            (update-panel-display!))))
                      0))
                   mirror-debounce-ms)]
        (swap! session assoc :mirror-timer timer)))))

(defn- mirror-plane-twin
  "If the current piece is a half of a confirmed-mirror cut whose OTHER half has
   been decomposed, returns {:twin id :normal [..] :point [..]} (the mirror plane),
   else nil."
  []
  (let [tree (:tree @session)
        cur (:current tree)
        g (some (fn [g] (when (and (= :cut (:type g)) (:mirror? g)
                                   (or (= cur (:behind g)) (= cur (:ahead g))))
                          g))
                (:log tree))]
    (when g
      (let [twin (if (= cur (:behind g)) (:ahead g) (:behind g))]
        (when (seq (mtree/subtree-gestures tree twin))
          {:twin twin :normal (:heading (:pose g)) :point (:position (:pose g))})))))

(defn- gesture-availability
  "Single source of truth (addendum 3 Part A) for whether each session gesture can
   act right now, and — when it can't — WHY. One map drives three surfaces at once:
   every panel button's enabled/disabled state + tooltip, and the transient status
   message a key press writes when its gesture can't act (so the keyboard twin of a
   disabled button is never a silent no-op). Keys → {:enabled? bool :reason str}:
     :separate :symmetry :reflex :mirror :undo :nav :reveal :cut-nav."
  [s]
  (let [tree (:tree s)
        cur (:current tree)
        cur-count (get-in tree [:pieces cur :count])
        open (mtree/non-finished-leaves tree)
        sym-cache (get-in s [:symmetry-cache cur])
        twin (mirror-plane-twin)]
    {:separate
     (if (and cur-count (> cur-count 1))
       {:enabled? true  :reason (str "separa questo pezzo in " cur-count " componenti")}
       {:enabled? false :reason "questo pezzo è un solo componente — niente da separare"})
     :symmetry
     (cond
       (:symmetry-pending? s)
       {:enabled? false :reason "calcolo dei piani di simmetria in corso…"}
       (and sym-cache (empty? (:planes sym-cache)))
       {:enabled? false :reason "nessun piano di simmetria verificato su questo pezzo"}
       sym-cache
       {:enabled? true  :reason (str (count (:planes sym-cache)) " piani di simmetria — premi per ciclarli")}
       :else
       {:enabled? true  :reason "proponi un piano di simmetria (lo calcola sul pezzo corrente)"})
     :reflex
     (let [rc (get-in s [:reflex-cache cur])]
       (cond
         (:reflex-pending? s)
         {:enabled? false :reason "calcolo dei candidati di concavità in corso…"}
         (and rc (empty? (:cands rc)))
         {:enabled? false :reason "nessuna concavità: il pezzo è convesso"}
         rc
         {:enabled? true  :reason (str (count (:cands rc)) " tagli dalla concavità — premi per ciclarli")}
         :else
         {:enabled? true  :reason "proponi un taglio dalla concavità (lo calcola sul pezzo corrente)"}))
     :mirror
     (if twin
       {:enabled? true  :reason "decomponi a specchio: replica la decomposizione del gemello, riflessa"}
       {:enabled? false :reason "il gemello a specchio non è ancora decomposto (taglia sul piano di simmetria e decomponi una metà)"})
     :undo
     (if (seq (:log tree))
       {:enabled? true  :reason "annulla l'ultimo gesto (taglio o separazione)"}
       {:enabled? false :reason "nessun gesto da annullare"})
     :nav
     (if (> (count open) 1)
       {:enabled? true  :reason "vai al prossimo / precedente pezzo aperto"}
       {:enabled? false :reason (if (zero? (count open))
                                  "tutti i pezzi sono finiti — niente da navigare"
                                  "nessun altro pezzo aperto")})
     :reveal
     {:enabled? true :reason (if (:reveal-all? s)
                               "torna al focus sul solo pezzo corrente"
                               "mostra tutti i pezzi + etichette")}
     :cut-nav
     (let [f (cut-frame-ready s)]
       (cond
         (:cut-pending? s) {:enabled? false :reason "calcolo dei candidati di rotazione…"}
         (and f (empty? (:cands f)))
         {:enabled? false :reason (str "nessun candidato di taglio in modo "
                                       (if (= :angle (:digit-target s)) "rotazione" "traslazione"))}
         :else
         {:enabled? true :reason (str "salta al prossimo/precedente evento del profilo di sezione ("
                                      (if (= :angle (:digit-target s)) "rotazione" "traslazione") ")")}))}))

(defn- free-up
  "A unit vector perpendicular to heading — the free up for a reflected cut pose
   (the reflected frame is left-handed; the turtle picks up freely, brief Part 5)."
  [h]
  (let [t (if (> (js/Math.abs (nth h 2)) 0.9) [1 0 0] [0 0 1])
        c (turtle/cross h t)
        m (js/Math.sqrt (turtle/dot c c))]
    (if (< m 1e-9) [0 0 1] (mapv #(/ % m) c))))

(defn- mirror-decompose!
  "Part 5: replay the twin's decomposition subtree onto the current piece with poses
   REFLECTED through the mirror-cut plane — the resulting pieces are REAL pieces of
   the original (no mirror copies). Reflection preserves dot products, so a twin
   piece's :behind/:ahead maps to the reflected cut's :behind/:ahead with no
   remapping. One structural gesture (a shared :group): a single undo removes the
   whole replay."
  []
  (if-let [{:keys [twin normal point]} (mirror-plane-twin)]
    (let [gestures (mtree/subtree-gestures (:tree @session) twin)
          group (keyword "mirror" (str (:next-id (:tree @session))))]
      (loop [gs gestures, pmap {twin (current-id)}]
        (if (empty? gs)
          (do (reposition-plane-for! (current-id))
              (state/capture-println (str "edit-mesh-split: mirror-decomposed (" (count gestures) " gestures)"))
              (recompute!))
          (let [g (first gs)
                target (pmap (:input g))]
            (case (:type g)
              :cut
              (let [{:keys [position heading]} (mtree/reflect-pose (:pose g) normal point)
                    offset (turtle/dot heading position)
                    {:keys [ahead behind]} (manifold/split-by-plane (piece-mesh target) heading offset)
                    {tree' :tree bid :behind aid :ahead}
                    (mtree/cut (:tree @session) target
                               {:position position :heading heading :up (free-up heading)}
                               (report-of-mesh behind) (report-of-mesh ahead)
                               {:group group})]
                (store-piece! bid behind)
                (store-piece! aid ahead)
                (swap! session assoc :tree tree')
                (recur (rest gs) (assoc pmap (:behind g) bid (:ahead g) aid)))
              :separate
              (let [comps (manifold/mesh-components (piece-mesh target))
                    {tree' :tree cids :components}
                    (mtree/separate (:tree @session) target
                                    (mapv #(hash-map :finished? (manifold/convex? %) :count 1) comps)
                                    {:group group})]
                (doseq [[cid cm] (map vector cids comps)] (store-piece! cid cm))
                (swap! session assoc :tree tree')
                (recur (rest gs) (merge pmap (zipmap (:components g) cids)))))))))
    (set-status-message! (:reason (:mirror (gesture-availability @session))))))

;; ============================================================
;; Cut candidates — "salta al prossimo evento" (dev-docs/brief-cut-candidates.md
;; Part 3). Mode-sensitive: step mode (Tab→:step) → translation along the heading;
;; angle mode → rotation about :up. Candidates + section-area profile are computed in
;; a FIXED frame per (piece, mode, heading|position) and cached (:cut-frame) so
;; navigation AND the panel strip (Part 4) share one computation; the live plane's DOF
;; value is the marker. Translation is cheap (sync); rotation ~15x costlier (async,
;; pending). :cut-frame holds only plain data (the WASM objects are freed inside the
;; generator calls), so cleanup needs nothing.
;; ============================================================

(def ^:private cut-samples 96)

(defn- cut-frame-sig
  "Invalidation key for the cached cut-frame: piece + mode + the pose part that
   DEFINES the frame (heading for translation, position for rotation — the OTHER part
   is the DOF being explored). Rounded so float drift doesn't thrash the cache."
  [s]
  (let [{:keys [live-pose tree digit-target]} s
        cur (:current tree)
        r (fn [v] (mapv #(/ (js/Math.round (* 1000 %)) 1000.0) v))]
    (if (= :angle digit-target)
      [cur :rotation (r (:position live-pose)) :up]
      [cur :translation (r (:heading live-pose))])))

(def ^:private cut-rotation-debounce-ms 220)

(defn- compute-cut-frame!
  "Compute cut-candidates + profile for the current piece/mode in a fixed frame and
   cache them (:cut-frame). Translation: through the bbox-centre along the live heading
   (sync, cheap). Rotation: about :up through the live position, θ=0 at the live
   heading — ~15x costlier, so DEBOUNCED behind :cut-pending? (the strip says
   'computing'), never per drag-tick. Callers render; only the async completion
   refreshes the panel itself."
  [sig]
  (let [{:keys [live-pose] :as s} @session
        mesh (piece-mesh (:current (:tree s)))]
    (if (= :rotation (second sig))
      (do
        (when-let [t (:cut-timer @session)] (js/clearTimeout t))
        (swap! session assoc :cut-pending? true :cut-frame {:sig sig :mode :rotation :computing? true}
               :cut-timer
               (js/setTimeout
                (fn []
                  (when (and @session (= sig (cut-frame-sig @session)))
                    (let [point (:position live-pose) ref-h (:heading live-pose) up (:up live-pose)]
                      (swap! session assoc :cut-pending? false :cut-timer nil
                             :cut-frame {:sig sig :mode :rotation :point point :ref-heading ref-h
                                         :up up :axis-vec up
                                         :cands (manifold/cut-candidates mesh {:mode :rotation :axis :up
                                                                               :heading ref-h :position point
                                                                               :up up :samples cut-samples})
                                         :profile (manifold/rotation-profile mesh ref-h point up :up cut-samples)})
                      (update-panel-display!))))
                cut-rotation-debounce-ms)))
      (let [point (bbox-center mesh) h (:heading live-pose) up (:up live-pose)]
        (swap! session assoc :cut-pending? false
               :cut-frame {:sig sig :mode :translation :point point :ref-heading h :up up :axis-vec h
                           :cands (manifold/cut-candidates mesh {:mode :translation :heading h
                                                                 :position point :up up :samples cut-samples})
                           :profile (manifold/translation-profile mesh h point cut-samples)})))))

(defn- ensure-cut-frame!
  "Recompute the cached cut-frame if it no longer matches the current piece/mode/frame."
  []
  (let [sig (cut-frame-sig @session)]
    (when (not= sig (:sig (:cut-frame @session)))
      (compute-cut-frame! sig))
    (:cut-frame @session)))

(defn- current-dof
  "Where the live plane sits on the active DOF, in `frame`'s coordinates — offset from
   the frame point along heading (translation) or signed angle from ref-heading about
   the axis (rotation). The strip marker."
  [frame]
  (let [{:keys [live-pose]} @session
        {:keys [mode point ref-heading axis-vec]} frame]
    (if (= mode :rotation)
      (js/Math.atan2 (turtle/dot (:heading live-pose) (turtle/cross axis-vec ref-heading))
                     (turtle/dot (:heading live-pose) ref-heading))
      (turtle/dot (turtle/v- (:position live-pose) point) ref-heading))))

(defn- cut-frame-ready
  "The cached cut-frame IF it matches the current sig and finished computing, else nil
   (button state without triggering a recompute)."
  [s]
  (let [f (:cut-frame s)]
    (when (and f (= (:sig f) (cut-frame-sig s)) (not (:computing? f))) f)))

(defn- jump-to-event!
  "Part 3: teleport the plane to the next (:next) / previous (:prev) cut candidate
   along the active DOF, ordered by POSITION (not salience — the ranking lives in the
   strip and the filter). No candidate that way → status message (no silent no-op)."
  [dir]
  (let [frame (ensure-cut-frame!)]
    (cond
      (:computing? frame) (set-status-message! "calcolo dei candidati di rotazione…")
      (empty? (:cands frame))
      (set-status-message! (str "nessun candidato di taglio ("
                                (if (= :rotation (:mode frame)) "rotazione" "traslazione") ")"))
      :else
      (let [cur (current-dof frame) eps 1e-4]
        (if-let [nxt (if (= dir :next)
                       (->> (:cands frame) (filter #(> (:at %) (+ cur eps))) (sort-by :at) first)
                       (->> (:cands frame) (filter #(< (:at %) (- cur eps))) (sort-by :at >) first))]
          (do (teleport-plane-to! (:pose nxt)) (recompute!)
              (set-status-message! (str "→ " (name (:kind nxt)) " · salienza "
                                        (js/Math.round (:salience nxt)))))
          (set-status-message! (str "nessun " (if (= dir :next) "prossimo" "precedente")
                                    " candidato in questa direzione")))))))

;; ── Part 4: profile strip in the panel ──
(def ^:private strip-w 280)
(def ^:private strip-h 48)
(def ^:private strip-step-color "#4fc3f7")   ; gradino
(def ^:private strip-neck-color "#ffb74d")   ; collo

(defn- strip-x-domain
  "[xmin xmax] of the profile's DOF axis (offset or angle)."
  [profile]
  (let [xs (map :offset profile)] [(reduce min xs) (reduce max xs)]))

(defn- render-cut-strip!
  "Part 4: draw A along the active DOF (t or θ) as an SVG polyline, with kind-coloured
   candidate ticks and a marker at the live plane's DOF value. Pending during a
   rotation recompute; empty until a frame exists. Rebuilt each panel update (cheap);
   the marker moves as the plane nudges."
  [panel]
  (when-let [svg (.querySelector panel ".ems-strip")]
    (let [s @session
          f (cut-frame-ready s)
          label (.querySelector panel ".ems-evt-label")]
      (cond
        (:cut-pending? s)
        (do (set! (.-innerHTML svg) "") (when label (set! (.-textContent label) "profilo rotazione: calcolo…")))
        (or (nil? f) (empty? (:profile f)))
        (do (set! (.-innerHTML svg) "") (when label (set! (.-textContent label) "profilo di sezione")))
        :else
        (let [profile (:profile f)
              [xmin xmax] (strip-x-domain profile)
              xspan (max 1e-9 (- xmax xmin))
              peak (max 1e-9 (reduce max (map :area profile)))
              px (fn [x] (* strip-w (/ (- x xmin) xspan)))
              py (fn [a] (+ 2 (* (- strip-h 4) (- 1 (/ a peak)))))
              poly (str/join " " (map (fn [{:keys [offset area]}]
                                        (str (.toFixed (px offset) 1) "," (.toFixed (py area) 1))) profile))
              ticks (str/join
                     (for [c (:cands f)]
                       (let [x (.toFixed (px (:at c)) 1)]
                         (str "<line x1='" x "' y1='0' x2='" x "' y2='" strip-h "' stroke='"
                              (if (= :step (:kind c)) strip-step-color strip-neck-color)
                              "' stroke-width='1.5' opacity='0.8'/>"))))
              mx (.toFixed (px (current-dof f)) 1)]
          (set! (.-innerHTML svg)
                (str "<polyline points='" poly "' fill='none' stroke='#9affc0' stroke-width='1'/>"
                     ticks
                     "<line x1='" mx "' y1='0' x2='" mx "' y2='" strip-h
                     "' stroke='#ffffff' stroke-width='1.5'/>"))
          (when label
            (set! (.-textContent label)
                  (str (if (= :rotation (:mode f)) "A(θ)" "A(t)") " · " (count (:cands f)) " eventi"
                       " (" (count (filter #(= :step (:kind %)) (:cands f))) " gradini)"))))))))

(defn- strip-click!
  "Click on the strip → teleport to the nearest candidate (brief Part 4: 'si vede, ci
   si salta sopra'). The panel is a legitimate click surface (only the viewport is
   mouse-monofunction)."
  [evt panel]
  (when-let [f (cut-frame-ready @session)]
    (when (seq (:cands f))
      (let [svg (.querySelector panel ".ems-strip")
            rect (.getBoundingClientRect svg)
            frac (/ (- (.-clientX evt) (.-left rect)) (max 1 (.-width rect)))
            [xmin xmax] (strip-x-domain (:profile f))
            dof (+ xmin (* frac (- xmax xmin)))
            nearest (apply min-key #(js/Math.abs (- (:at %) dof)) (:cands f))]
        (teleport-plane-to! (:pose nearest)) (recompute!)
        (set-status-message! (str "→ " (name (:kind nearest)) " · salienza "
                                  (js/Math.round (:salience nearest))))))))

(defn- cycle-current-piece!
  "Explicit, deterministic navigation over the OPEN pieces only (addendum Parte
   A): `dir` :next (n) or :prev (p), round-robin in DFS order, repositioning the
   plane onto the new current. The mouse never selects — it only moves the plane."
  [dir]
  (let [avail (:nav (gesture-availability @session))]
    (if-not (:enabled? avail)
      (set-status-message! (:reason avail))   ; only the current piece is open → no silent no-op
      (let [old (current-id)
            tree (mtree/cycle-current (:tree @session) dir)]
        (when (not= (:current tree) old)
          (swap! session assoc :tree tree)
          (reposition-plane-for! (:current tree))
          (recompute!))))))

(defn- toggle-reveal!
  "Reveal toggle (addendum Parte B): temporarily brings every piece back to full
   visibility to re-orient, then back to focus. A view-only flag — never touches
   session structure or the undo log."
  []
  (swap! session update :reveal-all? not)
  (render!)
  (update-panel-display!))

(def ^:private marker-prefix "(edit-mesh-split")

(defn- find-marker
  [text]
  (modal/find-form-bounds text marker-prefix))

(defn- cleanup!
  []
  (when-let [t (:mirror-timer @session)] (js/clearTimeout t))       ; Part 4 debounce timer
  (when-let [t (:status-msg-timer @session)] (js/clearTimeout t))   ; Part A message timer
  (when-let [t (:cut-timer @session)] (js/clearTimeout t))          ; cut-candidates rotation debounce
  (free-all-manifolds!)   ; the single no-leak point — every session exit runs cleanup!
  (modal/unmount-panel! (:panel-el @session))
  (modal/remove-keydown! (:key-handler @session))
  (gizmo/close!)
  (viewport/set-turtle-source! :global)
  (viewport/clear-labels!)        ; addendum 3: reveal labels are billboards now
  (viewport/clear-world-labels!)  ; also drop any legacy world-anchored labels
  (viewport/show-user-geometry!)  ; restore the base composite hidden on enter!
  (viewport/clear-preview!))

(defn- commit-session!
  "Close the session and emit the tree as a let-chain of self-contained linear
   composites (mtree/emit). Enter on a terminal/all-finished plane, or Ctrl+Enter
   (force). cleanup! frees every kept-alive Manifold."
  []
  (let [{:keys [edit-mesh-split-from edit-mesh-split-to from-repl] :as s} @session
        code (mtree/emit (:tree s))]
    (if from-repl
      (state/capture-println code)
      (do (modal/replace-source! edit-mesh-split-from edit-mesh-split-to code)
          (state/capture-println (str "edit-mesh-split: " code))))
    (cleanup!)
    (modal/release!)
    (reset! session nil)
    (when-not from-repl (modal/run-definitions!))))

(defn- accept-or-commit!
  "Enter: cut if the plane is actively bisecting the current piece; else commit if
   every tree piece is finished ('tutto verde'); else move on to the next open
   piece (the plane isn't cutting here and there is work left elsewhere)."
  []
  (cond
    (= :active (:plane-state @session)) (accept-cut!)
    (mtree/all-finished? (:tree @session)) (commit-session!)
    :else (cycle-current-piece! :next)))

(defn- force-commit!
  "Ctrl/Cmd+Enter: close now and emit whatever the tree currently is, even with
   concave leaves still open (the user's decomposition, their call)."
  []
  (commit-session!))

(defn- undo!
  "Pop the last structural gesture (chronological, cross-branch — mtree/undo),
   freeing the removed pieces' kept-alive Manifolds and restoring the plane to a
   popped cut's pose (or onto the re-opened piece for a popped separation)."
  []
  (if-let [{:keys [tree removed pose]} (mtree/undo (:tree @session))]
    (do
      (doseq [id removed] (free-piece! id))
      (swap! session assoc :tree tree)
      (if pose
        (swap! session update :live-pose merge pose)
        (reposition-plane-for! (:current tree)))
      (recompute!))
    (set-status-message! (:reason (:undo (gesture-availability @session))))))

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
            (ensure-cut-frame!)   ; mode changed → the cut-strip's DOF changed
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
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (force-commit!))

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

        ;; s: separate the current piece into its connected components (Parte 3)
        (and (= key "s") (not mod?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (separate!))

        ;; n / p: deterministic next / previous open piece (addendum Parte A)
        (and (= key "n") (not mod?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (cycle-current-piece! :next))

        (and (= key "p") (not mod?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (cycle-current-piece! :prev))

        ;; r: reveal toggle — all pieces to full visibility, then back (Parte B)
        (and (= key "r") (not mod?))
        (do (.preventDefault e) (.stopPropagation e) (toggle-reveal!))

        ;; y: propose / cycle verified symmetry planes of the current piece (Part 4)
        (and (= key "y") (not mod?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (propose-symmetry-plane!))

        ;; c: propose / cycle cut candidates from the piece's concavity — reflex edges
        ;; (dev-docs/brief-cut-candidates-reflex.md); propose-and-cycle by salience, like y
        (and (= key "c") (not mod?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (propose-reflex-cut!))

        ;; d: mirror-decompose — replay the twin's decomposition, reflected (Part 5)
        (and (= key "d") (not mod?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (mirror-decompose!))

        ;; ] / [ : jump to next / previous cut-candidate event, mode-sensitive
        ;; (step→translation, angle→rotation) — brief-cut-candidates Part 3
        (and (= key "]") (not mod?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (jump-to-event! :next))

        (and (= key "[") (not mod?))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit-buffer!) (jump-to-event! :prev))

        :else nil))))

;; ============================================================
;; UI Panel
;; ============================================================

(defn- fmt-pct
  [v]
  (str (Math/round v) "%"))

(defn- side-verdict-text
  "Per-side finiteness word for the status line (Parte 2). Green criterion =
   every connected component convex, so a half decomposing into several convex
   pieces reads as its component count (the badge), not 'concave':
     count 1, finished → 'convex'   · count 1, not finished → 'concave'
     count>1, finished → 'N pieces'  · count>1, not finished → 'concave, N pieces'"
  [finished? count]
  (cond
    (and finished? (> count 1)) (str count " pieces")
    finished?                   "convex"
    (> count 1)                 (str "concave, " count " pieces")
    :else                       "concave"))

(defn- volume-status-text
  "The Part B status line — sides named and quantified, e.g. 'behind 42% (convex) —
   ahead 58% (2 pieces)'. Symmetric on purpose (both sides get a verdict word):
   the brief's own example only labels one side, read as shorthand rather than a
   spec, since either side can independently be finished/concave/multi-component."
  [{:keys [plane-state behind-pct ahead-pct behind-finished? behind-count
           ahead-finished? ahead-count]}]
  (str "behind " (fmt-pct behind-pct)
       (if (= plane-state :no-op) "" (str " (" (side-verdict-text behind-finished? behind-count) ")"))
       " — ahead " (fmt-pct ahead-pct)
       (if (= plane-state :terminal) "" (str " (" (side-verdict-text ahead-finished? ahead-count) ")"))))

(defn update-panel-display!
  []
  (when-let [panel (:panel-el @session)]
    (let [{:keys [digit-buffer digit-target step angle-step plane-state tree]
           :as s} @session
          editing? (seq digit-buffer)
          cur (:current tree)
          cur-count (get-in tree [:pieces cur :count])
          leaves (mtree/leaf-ids tree)
          open (mtree/non-finished-leaves tree)
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
        (let [base (cond
                     (mtree/all-finished? tree) "all pieces finished — Enter commits"
                     (= plane-state :active)   "Enter cuts the current piece"
                     (= plane-state :no-op)    "cut is a no-op (plane misses the piece)"
                     :else                     "plane past the piece — Enter jumps to the next open piece")
              ;; Current-piece badge (Parte 2/3): flag a multi-component current
              ;; piece — press s to separate (no plane needed).
              badge (when (and cur-count (> cur-count 1))
                      (str " · this piece: " cur-count " components — press s to separate"))
              ;; Symmetry indicators (Part 4): pending computes and the mirror badge.
              sym (cond
                    (:symmetry-pending? s) " · computing symmetry planes…"
                    (:mirror-pending? s)   " · checking mirror…"
                    (:mirror-confirmed? s) (str " · ⟷ " (symmetry-level-text (:mirror-ratio s))
                                                " — behind = ahead riflesso")
                    :else "")]
          (set! (.-textContent status-el) (str base badge sym))))
      (when-let [vol-el (.querySelector panel ".ems-volumes")]
        (set! (.-textContent vol-el) (volume-status-text s)))
      ;; Position line (addendum Parte C): where am I, by the SAME name the
      ;; emission uses — one identity across scene, panel, code.
      (when-let [pos-el (.querySelector panel ".ems-position")]
        (let [{:keys [name index total open? count]} (mtree/position-info tree)]
          (set! (.-textContent pos-el)
                (str "piece " name " (" index "/" total ") · "
                     (if open? "open" "finished") " · "
                     count (if (= 1 count) " component" " components")
                     (when (:reveal-all? s) " · [reveal]")))))
      (when-let [cmd-el (.querySelector panel ".pilot-cmd-list")]
        (set! (.-textContent cmd-el)
              (str (count leaves) " pieces · " (count open) " open, "
                   (- (count leaves) (count open)) " done")))
      ;; Transient no-op message (Part A): the reason a just-pressed key couldn't act.
      (when-let [msg-el (.querySelector panel ".ems-message")]
        (set! (.-textContent msg-el) (or (:status-message s) "")))
      ;; Explicit per-gesture button state (Part A): enabled/disabled + a tooltip
      ;; reason, from the same gesture-availability the keyboard no-op path reads —
      ;; a disabled button announces its precondition before you even press.
      (let [avail (gesture-availability s)
            set-btn! (fn [selector {:keys [enabled? reason]}]
                       (when-let [btn (.querySelector panel selector)]
                         (set! (.-disabled btn) (not enabled?))
                         (set! (.-title btn) reason)))]
        (set-btn! ".ems-btn-sym"      (:symmetry avail))
        (set-btn! ".ems-btn-reflex"   (:reflex avail))
        (set-btn! ".ems-btn-mirror"   (:mirror avail))
        (set-btn! ".ems-btn-separate" (:separate avail))
        (set-btn! ".ems-prev"         (:nav avail))
        (set-btn! ".ems-next"         (:nav avail))
        (set-btn! ".ems-evt-prev"     (:cut-nav avail))
        (set-btn! ".ems-evt-next"     (:cut-nav avail))
        (set-btn! ".pilot-btn-undo"   (:undo avail))
        (when-let [rb (.querySelector panel ".ems-btn-reveal")]
          (set! (.-title rb) (:reason (:reveal avail)))
          (set! (.-textContent rb) (if (:reveal-all? s) "focus" "reveal"))))
      ;; Part 4: the section-area profile strip (A along the active DOF).
      (render-cut-strip! panel))))

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
               ;; Transient no-op reason line (addendum 3 Part A) — empty until a key
               ;; that can't act writes why; collapses again when cleared.
               "<div class='ems-message'></div>"
               ;; Position line + explicit next/prev navigation (addendum Parte A/C)
               "<div class='pilot-commands ems-nav'>"
               "<button class='pilot-btn ems-prev'>◀ prev</button>"
               "<span class='ems-position'>piece —</span>"
               "<button class='pilot-btn ems-next'>next ▶</button>"
               "</div>"
               ;; Session-gesture buttons (addendum 3 Part A): every gesture the
               ;; keyboard offers also has a discoverable button whose disabled state
               ;; + tooltip announces its precondition. Labels double as the keymap.
               "<div class='pilot-commands ems-gestures'>"
               "<button class='pilot-btn ems-btn-sym'>⟷ symmetry (y)</button>"
               "<button class='pilot-btn ems-btn-reflex'>⌐ concavity (c)</button>"
               "<button class='pilot-btn ems-btn-mirror'>mirror-halve (d)</button>"
               "<button class='pilot-btn ems-btn-separate'>separate (s)</button>"
               "<button class='pilot-btn ems-btn-reveal'>reveal (r)</button>"
               "</div>"
               ;; Cut-candidate events: profile strip + jump-to-event nav (brief-cut-
               ;; candidates Part 3/4). Mode-sensitive (step→translation, angle→rotation).
               "<div class='pilot-commands ems-events'>"
               "<button class='pilot-btn ems-evt-prev'>◀ [</button>"
               "<span class='ems-evt-label'>profilo di sezione</span>"
               "<button class='pilot-btn ems-evt-next'>] ▶</button>"
               "</div>"
               "<svg class='ems-strip' viewBox='0 0 280 48' preserveAspectRatio='none'></svg>"
               "<div class='pilot-commands pilot-cmd-list'>(no cuts yet)</div>"
               "<div class='pilot-commands modal-help'>"
               "Tab: cycle step/angle · digits: set active value · "
               "←→↑↓: move (step, f/rt) / rotate (angle, th/tv) · "
               "Shift+↑↓: u / tr · Gizmo: drag arrows/rings — the mouse only ever "
               "moves the cut plane, never selects a piece. "
               "TREE: cut the current piece; both halves become pieces of the tree. "
               "Every gesture below also has a button above (disabled = the reason "
               "it can't act right now). "
               "s: separate the current piece into its connected components (no plane). "
               "n / p: next / previous open piece · "
               "r: reveal — show every piece + billboard name labels, press again for focus · "
               "y: propose/cycle the current piece's symmetry planes · "
               "c: propose/cycle cut candidates from the piece's concavity (reflex "
               "edges — disabled on a convex piece), ranked by concavity mass · "
               "d: mirror-decompose (replay a decomposed mirror-twin, reflected) · "
               "[ / ]: jump to the previous/next cut-candidate event of the section "
               "profile (step mode = translation, angle mode = rotation) — the strip "
               "shows A along the active DOF with the plane marker and event ticks "
               "(blue = step / flush face, orange = neck / waist); click a tick to jump · "
               "Enter: cut the current piece (or, when every piece is finished, commit) · "
               "Ctrl/Cmd+Enter: commit now (emit even with open pieces) · "
               "Backspace: undo the last cut/separation (chronological, any branch) · "
               "Esc: cancel, emit nothing. "
               "Plane color: grey = no-op, gold = plane past the piece, blue = active cut. "
               "Work view: ONLY the current piece (green/red halves + component badge) "
               "and the cut plane are shown — n/p swap which piece is on screen. "
               "Press r (reveal) to see every piece at once: open ones near-solid, "
               "finished ones as grey wireframes, each with a camera-facing name label. "
               "Cone points ahead (heading); Enter takes what's behind it, like extrude "
               "(material trails behind)."
               "</div>"
               "<div class='pilot-buttons'>"
               "<button class='pilot-btn pilot-btn-undo'>Undo</button>"
               "<button class='pilot-btn pilot-btn-ok'>Accept/Commit</button>"
               "<button class='pilot-btn pilot-btn-cancel'>Cancel</button>"
               "</div>"))
    (.addEventListener (.querySelector panel ".ems-prev") "click" (fn [_] (cycle-current-piece! :prev)))
    (.addEventListener (.querySelector panel ".ems-next") "click" (fn [_] (cycle-current-piece! :next)))
    (.addEventListener (.querySelector panel ".ems-btn-sym") "click" (fn [_] (propose-symmetry-plane!)))
    (.addEventListener (.querySelector panel ".ems-btn-reflex") "click" (fn [_] (propose-reflex-cut!)))
    (.addEventListener (.querySelector panel ".ems-btn-mirror") "click" (fn [_] (mirror-decompose!)))
    (.addEventListener (.querySelector panel ".ems-btn-separate") "click" (fn [_] (separate!)))
    (.addEventListener (.querySelector panel ".ems-btn-reveal") "click" (fn [_] (toggle-reveal!)))
    (.addEventListener (.querySelector panel ".ems-evt-prev") "click" (fn [_] (jump-to-event! :prev)))
    (.addEventListener (.querySelector panel ".ems-evt-next") "click" (fn [_] (jump-to-event! :next)))
    (.addEventListener (.querySelector panel ".ems-strip") "click" (fn [e] (strip-click! e panel)))
    (.addEventListener (.querySelector panel ".pilot-btn-undo") "click" (fn [_] (undo!)))
    (.addEventListener (.querySelector panel ".pilot-btn-ok") "click" (fn [_] (accept-or-commit!)))
    (.addEventListener (.querySelector panel ".pilot-btn-cancel") "click" (fn [_] (cancel!)))
    (modal/mount-panel! panel)
    panel))

;; ============================================================
;; Re-entry — rebuild a session tree from the ALREADY-COMPUTED composite
;; (Part 1's own return value). A re-entered mesh-split call is a LINEAR
;; chain (one run), so re-entry needs no new mechanism (accertamento A3#6
;; dissolved): the root is the initial mesh, then one cut per chain level at
;; the resolved marks. Any tree structure the user builds AFTER re-opening is
;; a fresh forest that re-emits as its own let-chain in place of the call.
;; ============================================================

(defn- report-of
  "The {:finished? :count} report of a mesh (Parte 2), for seeding tree pieces."
  [mesh]
  (select-keys (manifold/component-report mesh) [:finished? :count]))

(defn- build-reentry-tree
  "Walk a re-entered composite (linear {:behind :ahead} chain) into a session
   tree + piece-meshes side-table: root = initial mesh, then mtree/cut per level
   at `poses`. Returns {:tree tree :piece-meshes {id {:mesh …}}}."
  [source-expr entry-pose initial-mesh composite poses]
  (loop [tree (mtree/make-tree source-expr entry-pose (report-of initial-mesh))
         pm {0 {:mesh initial-mesh}}
         node composite
         ps (seq poses)
         cur 0]
    (if (or (nil? ps) (not (map? node)) (= :mesh (:type node)))
      {:tree tree :piece-meshes pm}
      (let [{:keys [behind ahead]} node
            {tree' :tree bid :behind aid :ahead}
            (mtree/cut tree cur (first ps) (report-of behind) (report-of ahead))]
        (recur tree' (assoc pm bid {:mesh behind} aid {:mesh ahead})
               ahead (next ps) aid)))))

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
            {:keys [tree piece-meshes]}
            (if (and composite-value (map? composite-value) (not= :mesh (:type composite-value)))
              (build-reentry-tree source-expr entry-pose initial-mesh composite-value poses)
              {:tree (mtree/make-tree source-expr entry-pose (report-of initial-mesh))
               :piece-meshes {0 {:mesh initial-mesh}}})
            cur (:current tree)
            cur-mesh (:mesh (get piece-meshes cur))]
        (modal/claim! :edit-mesh-split)
        (reset! session
                {:source-expr source-expr
                 :orig-path-text orig-path-text
                 :orig-marks-text orig-marks-text
                 :initial-mesh initial-mesh
                 :tree tree
                 :piece-meshes piece-meshes
                 :entry-pose entry-pose
                 ;; the plane starts through the middle of the current piece
                 :live-pose (-> (turtle/make-turtle)
                                (assoc :position (bbox-center cur-mesh)
                                       :heading (:heading entry-pose)
                                       :up (:up entry-pose)))
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
    ;; Hide the evaluated base composite (the whole decomposition refresh-viewport!
    ;; rendered before we entered): the modal's own preview is now the SOLE truth of
    ;; the scene, so the work/reveal states control EXACTLY what is visible (addendum
    ;; 3 Part B — "tutto il resto nascosto del tutto"). Restored on cleanup!/commit
    ;; re-eval. Must run BEFORE gizmo/enter! and recompute!, both of which add their
    ;; own (gizmo handles / preview) objects to world-group that must stay visible.
    (viewport/hide-user-geometry!)
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
    ;; The mouse has ONE job in the viewport: manipulate the cut plane (the gizmo
    ;; above). Piece selection is keyboard/panel only (n/p) — no click-to-select,
    ;; so a viewport click never carries two meanings (addendum Parte A).
    (recompute!)
    (state/capture-println
     (str "edit-mesh-split: interactive tree mode for " (:source-expr @session)
          " — move/rotate the cut plane (arrows or gizmo, the mouse never selects), "
          "Enter cuts the current piece, s separates it, n/p navigate open pieces, "
          "r reveals all, Backspace undoes, Ctrl/Cmd+Enter commits, Esc cancels"))))

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
