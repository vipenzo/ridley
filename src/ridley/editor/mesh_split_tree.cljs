(ns ridley.editor.mesh-split-tree
  "Pure structural model + emission for edit-mesh-split's TREE sessions
   (dev-docs/brief-mesh-components-tree.md, Parti 3–4). No WASM, no DOM: the
   actual meshes and their kept-alive Manifold objects live in the session's
   side-table keyed by piece id; THIS module owns the ids, the parentage
   (origins), the chronological structural-gesture log (for undo), current-piece
   selection, piece naming, and the nude `mesh-split` emission. All pure —
   unit-testable in node, no Manifold.

   The tree is really a FOREST of linear mesh-split chains: a cut's :behind
   either stays a leaf or starts its own chain, resolved from that cut's own
   pose (dev-docs/brief-split-tree.md Part 1/Q4) — so the whole forest emits
   as ONE nude `mesh-split` call, its branches expressed in the spec's tree
   shape (a map instead of a flat marks-vector), never a let. Separation
   (`mesh-components`) is not a session gesture: it is ordinary DSL the user
   reaches for by hand on the emitted call's result.

   Data model
   ----------
   tree =
     {:pieces      {id piece}       ; piece = {:id :origin :finished? :count :decided?}
      :log         [gesture]        ; chronological structural gestures
      :current     id               ; the current open (leaf) piece
      :next-id     n
      :source-expr \"block\"          ; input literal of the root
      :entry-pose  {:position :heading :up}}  ; every path resolves from here (A2)

   origin =
     {:kind :root}
     {:kind :cut :from pid :side :behind|:ahead}

   gesture (structural, undoable — selection is NOT a gesture) =
     {:type :cut    :input pid :pose {...} :behind bid :ahead aid}
     {:type :accept :pid pid}   ; 'accetta così com'è' (addendum 4 Parte A) —
                                ; MUTATES pid's own :finished?/:decided?, does
                                ; not consume/produce pieces (pid stays a leaf)

   A piece is a LEAF iff it is not a cut's input; leaves are the final
   decomposition. `:current` is always a leaf. `:finished?`/`:count` are the
   per-component report (Parte 2), supplied by the WASM caller (via
   `mesh-components`/`convex?`, computed OUTSIDE the tree — the tree never
   separates); `:decided?` (addendum 4) marks a `:finished?` that was DECIDED
   rather than measured convex — no consumer requires it, it exists only so a
   future one could distinguish the two without a tree walk."
  (:require [ridley.turtle.core :as turtle]
            [clojure.string :as str]))

;; ============================================================
;; Construction
;; ============================================================

(defn make-tree
  "A fresh tree over `source-expr` (the input literal) whose root piece (id 0)
   is the whole initial mesh; `root-report` = {:finished? :count}."
  [source-expr entry-pose root-report]
  {:pieces {0 {:id 0 :origin {:kind :root}
               :finished? (:finished? root-report)
               :count (:count root-report)}}
   :log []
   :current 0
   :next-id 1
   :source-expr source-expr
   :entry-pose entry-pose})

;; ============================================================
;; Structural queries (pure, derived from :log)
;; ============================================================

(defn- cut-gestures [tree] (filter #(= :cut (:type %)) (:log tree)))

(defn- input-ids
  "Piece ids that have been consumed by a cut — i.e. the non-leaf pieces.
   `keep`, not `map`: an :accept gesture (addendum 4) has no :input, and a
   bare `map` would pollute the set with a stray nil."
  [tree]
  (into #{} (keep :input) (:log tree)))

(defn leaf?
  "A piece is a leaf iff it was never cut."
  [tree pid]
  (and (contains? (:pieces tree) pid)
       (not (contains? (input-ids tree) pid))))

(defn leaf-ids
  "Leaf piece ids in DFS pre-order from the root (behind-before-ahead at each
   cut) — the order the scene labels use."
  [tree]
  (let [in? (input-ids tree)
        by-input (group-by :input (:log tree))
        children (fn [pid]
                   ;; a piece is consumed by at most one cut
                   (when-let [g (first (by-input pid))]
                     [(:behind g) (:ahead g)]))
        walk (fn walk [pid]
               (if (contains? in? pid)
                 (mapcat walk (children pid))
                 [pid]))]
    (vec (walk 0))))

(defn non-finished-leaves
  "Leaf ids whose piece is not finished (still needs work) — the pool the
   current-piece cycler walks, in DFS order."
  [tree]
  (filterv #(not (:finished? (get-in tree [:pieces %]))) (leaf-ids tree)))

(defn all-finished?
  "True iff every leaf is finished (every component convex) — the tree-wide
   'tutto verde' close condition."
  [tree]
  (empty? (non-finished-leaves tree)))

(defn- pick-current
  "Prefer `preferred` if it is a non-finished leaf; else the first non-finished
   leaf; else `fallback` (all finished → session done, current can rest anywhere)."
  [tree preferred fallback]
  (let [nfl (set (non-finished-leaves tree))]
    (cond
      (contains? nfl preferred) preferred
      (seq (non-finished-leaves tree)) (first (non-finished-leaves tree))
      :else fallback)))

;; ============================================================
;; Structural gestures
;; ============================================================

(defn cut
  "Cut leaf `pid` at `pose` into a behind and an ahead piece, each carrying its
   own {:finished? :count} report (WASM-computed by the caller). Appends a :cut
   gesture; advances current to the ahead if it is not finished, else the next
   non-finished leaf (continuità con la ghigliottina). Returns
   {:tree tree' :behind bid :ahead aid}."
  ([tree pid pose behind-report ahead-report]
   (cut tree pid pose behind-report ahead-report nil))
  ([tree pid pose behind-report ahead-report opts]
   ;; opts (optional): {:mirror? bool  ; a confirmed symmetry cut → emission comment
   ;;                    :group id}     ; a mirror-decompose replay → atomic undo
   (let [bid (:next-id tree)
         aid (inc bid)
         pieces (-> (:pieces tree)
                    (assoc bid {:id bid :origin {:kind :cut :from pid :side :behind}
                                :finished? (:finished? behind-report) :count (:count behind-report)})
                    (assoc aid {:id aid :origin {:kind :cut :from pid :side :ahead}
                                :finished? (:finished? ahead-report) :count (:count ahead-report)}))
         gesture (cond-> {:type :cut :input pid :pose pose :behind bid :ahead aid}
                   (:mirror? opts) (assoc :mirror? true)
                   (:group opts)   (assoc :group (:group opts)))
         tree' (-> tree
                   (assoc :pieces pieces :next-id (+ aid 1))
                   (update :log conj gesture))
         ;; stay local to the cut: ahead if still open (guillotine continuity),
         ;; else the just-created behind if open, else the next open leaf.
         nfl (set (non-finished-leaves tree'))
         current (cond (contains? nfl aid) aid
                       (contains? nfl bid) bid
                       :else (pick-current tree' aid aid))]
     {:tree (assoc tree' :current current)
      :behind bid :ahead aid})))

(defn- gesture-removed [g] [(:behind g) (:ahead g)])

(defn undo
  "Pop the last structural gesture, whatever branch it touched (a single
   chronological semantics, like the linear model). A mirror-decompose replay is
   ONE structural gesture: its cuts share a `:group`, so undo pops the whole
   trailing group atomically (brief Part 5). Removes the produced pieces,
   re-opens the (group's first) input as the current leaf, and returns
   {:tree tree' :removed [pid …] :pose <cut pose or nil>} — `removed` so the caller
   can .delete those pieces' Manifolds, `pose` to restore the plane to a lone
   popped cut. An :accept gesture (addendum 4 Parte A — 'accetta così com'è') is
   different in kind: it never consumes/produces pieces, only flips its piece's
   own :finished?/:decided?, so undoing it MUTATES that piece back to open
   rather than removing anything (`:removed []`, `:pose nil`); it never shares a
   :group. Returns nil if the log is empty."
  [tree]
  (when-let [g (peek (:log tree))]
    (if (= :accept (:type g))
      (let [pid (:pid g)]
        {:tree (-> tree
                   (update :log pop)
                   (assoc-in [:pieces pid :finished?] false)
                   (update-in [:pieces pid] dissoc :decided?)
                   (assoc :current pid))
         :removed []
         :pose nil})
      (let [log (:log tree)
            n (if (:group g)
                (count (take-while #(= (:group g) (:group %)) (rseq log)))
                1)
            popped (subvec log (- (count log) n))
            rest-log (subvec log 0 (- (count log) n))
            removed (vec (mapcat gesture-removed popped))
            pieces (reduce dissoc (:pieces tree) removed)]
        {:tree (assoc tree :pieces pieces :current (:input (first popped)) :log rest-log)
         :removed removed
         :pose (when (= 1 n) (:pose g))}))))

(defn select
  "Set current to leaf `pid` (a selection, NOT a structural gesture — never
   logged, never undone). No-op if `pid` is not a leaf."
  [tree pid]
  (if (leaf? tree pid) (assoc tree :current pid) tree))

(defn cycle-current
  "Advance current to the next (`dir` :next, the default) or previous (:prev)
   non-finished leaf, round-robin in DFS order — the deterministic n/p navigation
   (addendum Parte A). No-op if there are no non-finished leaves."
  ([tree] (cycle-current tree :next))
  ([tree dir]
   (let [nfl (non-finished-leaves tree)]
     (if (empty? nfl)
       tree
       (let [i (.indexOf nfl (:current tree))
             step (if (= dir :prev) -1 1)
             nxt (if (neg? i)
                   (if (= dir :prev) (peek nfl) (first nfl))
                   (nth nfl (mod (+ i step) (count nfl))))]
         (assoc tree :current nxt))))))

(defn accept-current
  "Declare the CURRENT leaf finished BY DECISION, whatever its color (addendum 4
   Parte A — 'finito è una decisione, non un fatto geometrico', not a gate on
   convexity). No-op if the piece is already finished: a green piece has
   nothing to decide, so the caller treats this the same as the natural close
   (no gesture is logged). Otherwise flips :finished? true, tags :decided? true
   (predisposed for a future consumer — no existing :finished?-based logic
   changes, same discipline as tree-view's :native?), appends an :accept
   gesture (undoable — mtree/undo reopens the piece), and advances current to
   the next non-finished leaf via cycle-current, which already handles current
   not being in the non-finished set (round-robins to the first one, or is a
   no-op if none remain)."
  [tree]
  (let [pid (:current tree)]
    (if (:finished? (get-in tree [:pieces pid]))
      tree
      (-> tree
          (assoc-in [:pieces pid :finished?] true)
          (assoc-in [:pieces pid :decided?] true)
          (update :log conj {:type :accept :pid pid})
          (cycle-current :next)))))

;; ============================================================
;; Mirror-decompose support (brief Part 5)
;; ============================================================

(defn descendant-pieces
  "The piece ids in the subtree rooted at `pid` (inclusive), pre-order — a piece's
   own decomposition."
  [tree pid]
  (let [in? (input-ids tree)
        by-input (group-by :input (:log tree))
        children (fn [p] (when-let [g (first (by-input p))]
                           [(:behind g) (:ahead g)]))
        walk (fn walk [p] (cons p (when (contains? in? p) (mapcat walk (children p)))))]
    (vec (walk pid))))

(defn subtree-gestures
  "The structural gestures operating on pieces within the subtree rooted at `pid`,
   in chronological (log) order — the sequence a mirror-decompose replays onto the
   twin. Excludes gestures on `pid` itself only if `pid` is a leaf (none)."
  [tree pid]
  (let [ids (set (descendant-pieces tree pid))]
    (filterv #(contains? ids (:input %)) (:log tree))))

(defn reflect-pose
  "Reflect a cut pose through the mirror plane (unit normal `n`, point `o`):
   position P → P − 2((P−o)·n)n, heading H → H − 2(H·n)n. ONLY position and
   heading — the reflected frame is left-handed and the turtle can't adopt it for
   rotations, so `up` is left free for the delta synthesis (brief Part 5). Returns
   {:position :heading}."
  [{:keys [position heading]} n o]
  (let [p (vec position) h (vec heading) n (vec n) o (vec o)
        dot3 (fn [a b] (+ (* (a 0) (b 0)) (* (a 1) (b 1)) (* (a 2) (b 2))))
        axpy (fn [v d] [(- (v 0) (* d (n 0))) (- (v 1) (* d (n 1))) (- (v 2) (* d (n 2)))])]
    {:position (axpy p (* 2 (dot3 [(- (p 0) (o 0)) (- (p 1) (o 1)) (- (p 2) (o 2))] n)))
     :heading  (axpy h (* 2 (dot3 h n)))}))

;; ============================================================
;; Emission — always one nude mesh-split call (dev-docs/brief-split-tree.md)
;; ============================================================

(def ^:private emit-tol
  "How far an emitted number may sit from the value it stands for — mm for
   f/rt/u, degrees for th/tv/tr.

   NOT a taste setting: it is bounded from above by the planarity noise of an
   IMPORTED face. A binary STL stores float32, so a nominally flat face's
   vertices scatter (~7nm on the mount at ~12mm coordinates). Land inside that
   band and the cut slices the face; land BELOW it and the plane cuts through
   solid material, handing the face's whole slab to the next piece. Measured
   2026-07-15: -1nm still cuts the intended solid, -10nm already transfers it.
   1e-6 keeps the worst case an order of magnitude inside the band."
  1e-6)

(defn- fmt-num
  "Shortest decimal string standing for `v` to within `emit-tol`.

   The emitted path IS the cut — re-evaluating the source has to land on the
   plane the user accepted, so this may SHORTEN a number but never MOVE it. A
   flat 2-decimal rounding moved it: on the mount STL a step face at
   -1.6250512734795155 emitted as (f -1.63), 4.9µm past the face, which hands a
   2.87mm³ sheet to the next piece (measured 2026-07-15 — the sheet renders as
   the wafer the bug report described). Precision therefore grows until the
   string is faithful instead of stopping at 2.

   The minimal p never leaves a trailing zero — p-1 would already have passed —
   so no trimming is needed."
  [v]
  (loop [p 0]
    (if (>= p 12)
      (str v)
      (let [s (.toFixed v p)]
        (if (< (js/Math.abs (- (js/parseFloat s) v)) emit-tol)
          (if (= s "-0") "0" s)
          (recur (inc p)))))))

(defn- delta->cmds-str
  [delta]
  (->> [:th :tv :tr :f :rt :u]
       (keep (fn [k] (when-let [v (k delta)] (str "(" (name k) " " (fmt-num v) ")"))))
       (str/join " ")))

(defn- runs
  "Group the cut gestures into maximal linear runs: a cut C continues run of a
   cut D iff C.input == D.ahead (each ahead is cut at most once, so the linkage
   is a 1:1 chain). Returns a vector of runs, each a vector of cut gestures in
   spine order; run order is by the earliest op-index of the run's cuts so a
   run's input binding is always emitted before the run consumes it."
  [tree]
  (let [cs (vec (cut-gestures tree))
        idx (into {} (map-indexed (fn [i c] [c i]) cs))
        ahead-ids (into #{} (map :ahead) cs)
        by-input (into {} (map (juxt :input identity) cs))
        starts (filterv #(not (contains? ahead-ids (:input %))) cs)
        follow (fn [c] (loop [run [c]]
                         (if-let [nxt (by-input (:ahead (peek run)))]
                           (recur (conj run nxt))
                           run)))]
    (->> starts
         (map follow)
         (sort-by (fn [run] (apply min (map idx run))))
         vec)))

(defn- intermediate-aheads
  "Ahead pieces that are themselves cut — inlined inside their run's own
   emitted path chain, so they get no scene-label name."
  [tree]
  (let [cs (cut-gestures tree)
        inputs (into #{} (map :input) cs)]
    (into #{} (comp (map :ahead) (filter inputs)) cs)))

(defn- name-map
  "Map every NAMED piece id → \"piece-N\", numbered in DFS pre-order. Named =
   every non-root piece except the intermediate aheads. The root is unnamed
   (it is referenced by its source-expr literal)."
  [tree]
  (let [inter (intermediate-aheads tree)
        in? (input-ids tree)
        by-input (group-by :input (:log tree))
        children (fn [pid]
                   (when-let [g (first (by-input pid))]
                     [(:behind g) (:ahead g)]))
        pre-order (fn pre [pid]
                    (cons pid (when (contains? in? pid) (mapcat pre (children pid)))))
        named (->> (pre-order 0)
                   (remove #(= 0 %))
                   (remove inter))]
    (into {} (map-indexed (fn [i pid] [pid (str "piece-" (inc i))]) named))))

(defn- name-of [tree nm pid]
  (if (= 0 pid) (:source-expr tree) (nm pid)))

(defn- run-comments
  "The `;; :cut-N: piano di simmetria` lines of a run's confirmed-symmetry cuts,
   or \"\" — knowledge that would otherwise die with the session, kept in the
   source where the user reads it. Comments never affect eval, so they cost the
   round-trip nothing.

   Re-entry does NOT restore the :mirror? flag (build-reentry-tree rebuilds the
   tree from the composite's geometry, which carries no such tag), so emit
   writes these once and never regenerates them. In the nude form that is why
   they sit OUTSIDE the call: a re-emission replaces the call only, so the lines
   survive as the user's own text instead of being silently dropped.

   The bargain (Vincenzo, 2026-07-15) is that they are the user's from then on,
   with what that implies: a re-opened cut that gets MOVED leaves its comment
   describing the old plane, and a re-opened cut that is re-made ON a symmetry
   plane emits a second copy beside the surviving first. Both are cosmetic —
   comments never reach eval — and the alternative costs more: inside the call
   they would reach cm/parse-form-elements as arguments, and having commit eat
   the lines after its own form would eat the user's own comments with them."
  [run]
  (->> run
       (map-indexed (fn [i c] (when (:mirror? c)
                                (str "  ;; :cut-" (inc i) ": piano di simmetria"))))
       (remove nil?)
       (str/join "\n")))

(defn- comment-suffix
  "Comment lines appended after a form. The LEADING newline gets them off the
   form's last line; the TRAILING one keeps whatever follows off the comment's
   line — emit's own closing \"]\", or an enclosing form's \")\" — which the `;;`
   would otherwise swallow into an unreadable form (caught live 2026-07-14:
   `Unmatched delimiter )`)."
  [comments]
  (when (seq comments) (str "\n" comments "\n")))

(defn- sub-entry-str
  "One `mark sub-form` line of a branching spec map — `sub` is nil (leaf, the
   mark just cuts) or a nested level-text result. A non-branching sub-level
   renders as a BARE `(path …)` (mesh-split's own default: cut every one of
   its marks — brief Part 1's sub-spec grammar has no other no-branching
   form); a branching one carries its own map too, `[(path …) {…}]`. The
   sub-level's OWN trailing comments (its run's `;; :cut-N: piano di
   simmetria` lines) are folded in right here, via comment-suffix's usual
   leading+trailing newline — so the NEXT map entry can never be swallowed."
  [mk sub]
  (str mk " "
       (if (nil? sub)
         "nil"
         (str (if (:branching? sub)
                (str "[" (:path-str sub) " " (:spec-str sub) "]")
                (:path-str sub))
              (comment-suffix (:trailing sub))))))

(defn- level-text
  "The `(path …)` + spec text for the run starting at `pid`, entering from
   `entry-pose` — the tree's own :entry-pose at the root, or (brief Part 1
   Q4) a branching cut's own :pose one level down: a sub-path resolves from
   ITS mark's cut-pose, never the live turtle. Returns {:path-str :spec-str
   :branching? :trailing}; :trailing is this run's OWN mirror-comment block
   (a descendant run's are already folded into :spec-str, see sub-entry-str)."
  [runs-by-start entry-pose pid]
  (let [run (get runs-by-start pid)
        poses (into [entry-pose] (map :pose run))
        deltas (mapv (fn [[a b]] (turtle/synthesize-delta a b)) (partition 2 1 poses))
        path-forms (str/join " "
                             (map-indexed
                              (fn [i delta] (str (delta->cmds-str delta) " (mark :cut-" (inc i) ")"))
                              deltas))
        path-str (str "(path " path-forms ")")
        mark-names (mapv #(str ":cut-" (inc %)) (range (count run)))
        subs (mapv (fn [c] (when (contains? runs-by-start (:behind c))
                             (level-text runs-by-start (:pose c) (:behind c))))
                   run)
        branching? (boolean (some some? subs))
        spec-str (if branching?
                   (str "{" (str/join "\n " (map sub-entry-str mark-names subs)) "}")
                   (str "[" (str/join " " mark-names) "]"))]
    {:path-str path-str :spec-str spec-str :branching? branching?
     :trailing (run-comments run)}))

(defn emit
  "The emitted source (dev-docs/brief-split-tree.md): no cut → the input
   literal, verbatim. An :accept-only log (addendum 4 Parte A, 'accetta così
   com'è' on the root) earns nothing more, since it produced no new binding.

   Otherwise ALWAYS one nude `mesh-split` call — no let, no destructuring,
   ever, however deep the tree:

     (mesh-split mount
       (path (tv 90) (f -1.62) (mark :cut-1))
       [:cut-1])

   A level with no branching cut emits the familiar `[:cut-1 :cut-2 …]`
   marks-vector (unchanged from before this brief). A level where some cut's
   `:behind` is itself cut further emits a MAP instead —

     (mesh-split mount
       (path … (mark :cut-1) … (mark :cut-2))
       {:cut-1 (path … (mark :cut-1-1))
        :cut-2 nil})

   — recursing into that piece's own run, resolved from THAT cut's own pose,
   never the live turtle (brief Part 1 Q4). Put `edit-` back in front of
   whatever this returns and it IS the macro's own re-entry arity — nothing
   to unpick, for any tree shape. The user who wants names calls `split-tree`
   on the result; `mesh-components` is plain DSL applied by hand afterward,
   not a session gesture (brief Part 3)."
  [tree]
  (let [run-vec (runs tree)]
    (if (empty? run-vec)
      (:source-expr tree)
      (let [runs-by-start (into {} (map (fn [r] [(:input (first r)) r])) run-vec)
            {:keys [path-str spec-str trailing]} (level-text runs-by-start (:entry-pose tree) 0)]
        (str "(mesh-split " (:source-expr tree) "\n"
             "  " path-str "\n"
             "  " spec-str ")"
             (comment-suffix trailing))))))

(defn piece-name
  "The emission name of a piece (for scene labels) — the leaf shows the same
   binding name the emitted let uses; the root shows its source-expr."
  [tree pid]
  (name-of tree (name-map tree) pid))

;; ============================================================
;; Panel views (brief acquisition-views.md, Parte 2 — Vista processo)
;; ============================================================

(defn- children-of
  "The ordered child piece ids of `pid` ([behind ahead]) — nil if pid is a
   leaf (never cut). Standalone (not shared with leaf-ids/descendant-pieces/
   name-map's own closures, which already amortize a single group-by across
   their own whole-tree walk) — fine at the tree sizes (~5-20 nodes)
   tree-view walks."
  [tree pid]
  (when-let [g (first (filter #(= pid (:input %)) (:log tree)))]
    [(:behind g) (:ahead g)]))

(defn tree-view
  "A nested tree for the panel's Vista processo: {:id :name :leaf? :current?
   :status :children}, rooted at the source expression (piece 0, named via
   source-expr like emission does). Children of a node are its nearest NAMED
   descendants — intermediate aheads (unnamed, inlined into the emitted
   path's own chain — see name-map) are walked through transparently, so
   every displayed node carries \"nome (quello dell'emissione)\" as the brief
   asks. status is :open | :finished | :native — :native reads a :native? key
   no writer sets yet (the enum predisposed for edit-mesh-board); a native
   piece is by construction also :finished?, so no existing :finished?-based
   logic (leaf?/non-finished-leaves/all-finished?/pick-current/cycle-current/
   position-info) changes."
  [tree]
  (let [nm (name-map tree)
        named? (fn [pid] (or (zero? pid) (contains? nm pid)))
        named-children (fn named-children [pid]
                         (mapcat (fn [cid] (if (named? cid) [cid] (named-children cid)))
                                 (children-of tree pid)))
        status (fn [pid]
                 (let [p (get-in tree [:pieces pid])]
                   (cond (:native? p) :native (:finished? p) :finished :else :open)))
        node (fn node [pid]
               (let [lf (leaf? tree pid)]
                 {:id pid :name (name-of tree nm pid) :leaf? lf
                  :current? (= pid (:current tree)) :status (status pid)
                  :children (when-not lf (mapv node (named-children pid)))}))]
    (node 0)))

(defn leaf-counts
  "{:open N :finished M} across all leaves — the panel's 'N aperte · M
   finite' headline (Vista processo)."
  [tree]
  (let [open (count (non-finished-leaves tree))]
    {:open open :finished (- (count (leaf-ids tree)) open)}))

(defn position-info
  "The panel's position anchor for the current piece (addendum Parte C):
   {:name :index :total :open? :count} — `index` is 1-based in the leaf DFS
   order, `name` the same name the emission uses."
  [tree]
  (let [leaves (leaf-ids tree)
        cur (:current tree)
        p (get-in tree [:pieces cur])]
    {:name (piece-name tree cur)
     :index (inc (.indexOf leaves cur))
     :total (count leaves)
     :open? (not (:finished? p))
     :count (:count p)}))
