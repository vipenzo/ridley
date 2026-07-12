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

(declare cleanup! render! recompute! update-panel-display!)

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
;;  :labeled-tree   ; the :tree identity the scene labels were last built for
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
  "A non-current OPEN leaf: context, not focus (addendum Parte B). Neutral (no
   convexity tint — the semaphore belongs to the current piece alone) and faint
   (`alpha` ~0.12), so it's present for spatial orientation without competing with
   the current piece; the reveal toggle raises `alpha` to bring it fully back."
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

(def ^:private open-piece-alpha-focus 0.12)   ; faint context
(def ^:private open-piece-alpha-reveal 0.6)    ; brought fully back by the reveal toggle

(defn- other-piece-items
  "Every leaf EXCEPT the current one: finished leaves as ghosts (wireframe, always
   legible), still-open leaves as faint neutral bodies — full only while the reveal
   toggle is held (addendum Parte B). The current piece isn't drawn here; its live
   split halves stand in for it."
  [s]
  (let [tree (:tree s)
        cur (:current tree)
        alpha (if (:reveal-all? s) open-piece-alpha-reveal open-piece-alpha-focus)]
    (for [id (mtree/leaf-ids tree)
          :when (not= id cur)
          :let [mesh (piece-mesh id)]
          :when (and mesh (seq (:faces mesh)))]
      (if (:finished? (get-in tree [:pieces id]))
        (ghost-item mesh)
        (open-piece-item mesh alpha)))))

(defn- scene-labels
  "One billboard label per leaf at its centre, showing the SAME name the emission
   uses (addendum Parte C: one identity across scene, panel, code). The current
   piece's label is brightened."
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
      ;; Labels only change with the tree (pieces don't move on a plane nudge), so
      ;; rebuild them only when the tree identity changes — not every tick.
      (when-not (identical? (:tree s) (:labeled-tree s))
        (viewport/set-labels! (vec (scene-labels s)))
        (swap! session assoc :labeled-tree (:tree s)))
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

(defn- accept-cut!
  "plane-state = :active: cut the current piece into a behind + ahead node
   (mtree/cut), store their meshes, and let the tree advance current (stays on the
   ahead if it is still open — guillotine continuity — else the next open leaf).
   If current jumped to a DIFFERENT piece, the plane repositions onto it."
  []
  (let [{:keys [live-pose current-behind current-ahead
                behind-finished? behind-count ahead-finished? ahead-count] :as s} @session
        cur (current-id)
        pose (select-keys live-pose [:position :heading :up])
        {:keys [tree behind ahead]}
        (mtree/cut (:tree s) cur pose
                   {:finished? behind-finished? :count behind-count}
                   {:finished? ahead-finished? :count ahead-count})]
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
      (state/capture-println
       "edit-mesh-split: current piece is a single component — nothing to separate")
      (let [reports (mapv (fn [c] {:finished? (manifold/convex? c) :count 1}) comp-meshes)
            {:keys [tree components]} (mtree/separate (:tree s) cur reports)]
        (doseq [[cid cmesh] (map vector components comp-meshes)]
          (store-piece! cid cmesh))
        (swap! session assoc :tree tree)
        (reposition-plane-for! (:current tree))
        (state/capture-println (str "edit-mesh-split: separated into " (count comp-meshes) " pieces"))
        (recompute!)))))

(defn- cycle-current-piece!
  "Explicit, deterministic navigation over the OPEN pieces only (addendum Parte
   A): `dir` :next (n) or :prev (p), round-robin in DFS order, repositioning the
   plane onto the new current. The mouse never selects — it only moves the plane."
  [dir]
  (let [old (current-id)
        tree (mtree/cycle-current (:tree @session) dir)]
    (when (not= (:current tree) old)
      (swap! session assoc :tree tree)
      (reposition-plane-for! (:current tree))
      (recompute!))))

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
  (free-all-manifolds!)   ; the single no-leak point — every session exit runs cleanup!
  (modal/unmount-panel! (:panel-el @session))
  (modal/remove-keydown! (:key-handler @session))
  (gizmo/close!)
  (viewport/set-turtle-source! :global)
  (viewport/clear-labels!)
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
    (state/capture-println "edit-mesh-split: nothing to undo")))

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
                      (str " · this piece: " cur-count " components — press s to separate"))]
          (set! (.-textContent status-el) (str base badge))))
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
                   (- (count leaves) (count open)) " done"))))))

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
               ;; Position line + explicit next/prev navigation (addendum Parte A/C)
               "<div class='pilot-commands ems-nav'>"
               "<button class='pilot-btn ems-prev'>◀ prev</button>"
               "<span class='ems-position'>piece —</span>"
               "<button class='pilot-btn ems-next'>next ▶</button>"
               "</div>"
               "<div class='pilot-commands pilot-cmd-list'>(no cuts yet)</div>"
               "<div class='pilot-commands modal-help'>"
               "Tab: cycle step/angle · digits: set active value · "
               "←→↑↓: move (step, f/rt) / rotate (angle, th/tv) · "
               "Shift+↑↓: u / tr · Gizmo: drag arrows/rings — the mouse only ever "
               "moves the cut plane, never selects a piece. "
               "TREE: cut the current piece; both halves become pieces of the tree. "
               "s: separate the current piece into its connected components (no plane). "
               "n / p: next / previous open piece (buttons above) · "
               "r: reveal all pieces (re-orient), press again for focus · "
               "Enter: cut the current piece (or, when every piece is finished, commit) · "
               "Ctrl/Cmd+Enter: commit now (emit even with open pieces) · "
               "Backspace: undo the last cut/separation (chronological, any branch) · "
               "Esc: cancel, emit nothing. "
               "Plane color: grey = no-op, gold = plane past the piece, blue = active cut. "
               "Focus: the CURRENT piece is full (green/red halves + component badge); "
               "other open pieces are faint context (no convexity tint); finished pieces "
               "are grey wireframes. Cone points ahead (heading); Enter takes what's "
               "behind it, like extrude (material trails behind)."
               "</div>"
               "<div class='pilot-buttons'>"
               "<button class='pilot-btn pilot-btn-undo'>Undo</button>"
               "<button class='pilot-btn pilot-btn-ok'>Accept/Commit</button>"
               "<button class='pilot-btn pilot-btn-cancel'>Cancel</button>"
               "</div>"))
    (.addEventListener (.querySelector panel ".ems-prev") "click" (fn [_] (cycle-current-piece! :prev)))
    (.addEventListener (.querySelector panel ".ems-next") "click" (fn [_] (cycle-current-piece! :next)))
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
