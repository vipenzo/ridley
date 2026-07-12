(ns ridley.editor.mesh-split-tree
  "Pure structural model + emission for edit-mesh-split's TREE sessions
   (dev-docs/brief-mesh-components-tree.md, Parti 3–4). No WASM, no DOM: the
   actual meshes and their kept-alive Manifold objects live in the session's
   side-table keyed by piece id; THIS module owns the ids, the parentage
   (origins), the chronological structural-gesture log (for undo), current-piece
   selection, piece naming, and the let-chain emission. All pure — unit-testable
   in node, no Manifold.

   The tree is really a FOREST of linear mesh-split chains wired by let bindings
   (accertamento A2/A3): each emitted `mesh-split` call is a self-contained
   linear composite exactly like the guillotine's, so re-entry needs no new
   mechanism. The tree shape lives in the let topology; component separations
   appear as `mesh-components` destructures.

   Data model
   ----------
   tree =
     {:pieces      {id piece}       ; piece = {:id :origin :finished? :count}
      :log         [gesture]        ; chronological structural gestures
      :current     id               ; the current open (leaf) piece
      :next-id     n
      :source-expr \"block\"          ; input literal of the root
      :entry-pose  {:position :heading :up}}  ; every path resolves from here (A2)

   origin =
     {:kind :root}
     {:kind :cut :from pid :side :behind|:ahead}
     {:kind :separate :from pid :index k}

   gesture (structural, undoable — selection is NOT a gesture) =
     {:type :cut      :input pid :pose {...} :behind bid :ahead aid}
     {:type :separate :input pid :components [cid ...]}

   A piece is a LEAF iff it is neither a cut's input nor a separation's input;
   leaves are the final decomposition (the emitted body vector). `:current` is
   always a leaf. `:finished?`/`:count` are the per-component report (Parte 2),
   supplied by the WASM caller."
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
(defn- separations [tree] (filter #(= :separate (:type %)) (:log tree)))

(defn- input-ids
  "Piece ids that have been consumed by SOME gesture (cut or separate) — i.e.
   the non-leaf pieces."
  [tree]
  (into #{} (map :input) (:log tree)))

(defn leaf?
  "A piece is a leaf iff it was never cut and never separated."
  [tree pid]
  (and (contains? (:pieces tree) pid)
       (not (contains? (input-ids tree) pid))))

(defn leaf-ids
  "Leaf piece ids in DFS pre-order from the root (behind-before-ahead at each
   cut, component-order at each separation) — the order the emitted body vector
   and the scene labels use."
  [tree]
  (let [in? (input-ids tree)
        by-input (group-by :input (:log tree))
        children (fn [pid]
                   ;; a piece is consumed by exactly one gesture (cut xor separate)
                   (when-let [g (first (by-input pid))]
                     (case (:type g)
                       :cut [(:behind g) (:ahead g)]
                       :separate (:components g))))
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
  [tree pid pose behind-report ahead-report]
  (let [bid (:next-id tree)
        aid (inc bid)
        pieces (-> (:pieces tree)
                   (assoc bid {:id bid :origin {:kind :cut :from pid :side :behind}
                               :finished? (:finished? behind-report) :count (:count behind-report)})
                   (assoc aid {:id aid :origin {:kind :cut :from pid :side :ahead}
                               :finished? (:finished? ahead-report) :count (:count ahead-report)}))
        tree' (-> tree
                  (assoc :pieces pieces :next-id (+ aid 1))
                  (update :log conj {:type :cut :input pid :pose pose :behind bid :ahead aid}))
        ;; stay local to the cut: ahead if still open (guillotine continuity),
        ;; else the just-created behind if open, else the next open leaf.
        nfl (set (non-finished-leaves tree'))
        current (cond (contains? nfl aid) aid
                      (contains? nfl bid) bid
                      :else (pick-current tree' aid aid))]
    {:tree (assoc tree' :current current)
     :behind bid :ahead aid}))

(defn separate
  "Separate leaf `pid` into its connected components — one new piece per
   `component-reports` entry ({:finished? :count} in mesh-components' contract
   order). Appends a :separate gesture; advances current to the first
   non-finished component (else next non-finished leaf). Returns
   {:tree tree' :components [cid …]}."
  [tree pid component-reports]
  (let [start (:next-id tree)
        cids (vec (range start (+ start (count component-reports))))
        pieces (reduce (fn [acc [k cid rpt]]
                         (assoc acc cid {:id cid
                                         :origin {:kind :separate :from pid :index k}
                                         :finished? (:finished? rpt) :count (:count rpt)}))
                       (:pieces tree)
                       (map vector (range) cids component-reports))
        tree' (-> tree
                  (assoc :pieces pieces :next-id (+ start (count cids)))
                  (update :log conj {:type :separate :input pid :components cids}))
        preferred (first (filter #(not (:finished? (get-in tree' [:pieces %]))) cids))]
    {:tree (assoc tree' :current (pick-current tree' preferred (first cids)))
     :components cids}))

(defn undo
  "Pop the last structural gesture, whatever branch it touched (a single
   chronological semantics, like the linear model). Removes the pieces it
   produced, re-opens its input as the current leaf, and returns
   {:tree tree' :removed [pid …] :pose <cut pose or nil>} — `removed` so the
   caller can .delete those pieces' kept-alive Manifolds, `pose` so it can
   restore the plane to a popped cut. Returns nil if the log is empty."
  [tree]
  (when-let [g (peek (:log tree))]
    (let [removed (case (:type g) :cut [(:behind g) (:ahead g)] :separate (:components g))
          pieces (reduce dissoc (:pieces tree) removed)
          tree' (-> tree
                    (assoc :pieces pieces :current (:input g))
                    (update :log pop))]
      {:tree tree' :removed removed :pose (when (= :cut (:type g)) (:pose g))})))

(defn select
  "Set current to leaf `pid` (a selection, NOT a structural gesture — never
   logged, never undone). No-op if `pid` is not a leaf."
  [tree pid]
  (if (leaf? tree pid) (assoc tree :current pid) tree))

(defn cycle-current
  "Advance current to the next non-finished leaf, round-robin from the current
   one. No-op if there are no non-finished leaves."
  [tree]
  (let [nfl (non-finished-leaves tree)]
    (if (empty? nfl)
      tree
      (let [i (.indexOf nfl (:current tree))
            nxt (nth nfl (mod (inc (if (neg? i) -1 i)) (count nfl)))]
        (assoc tree :current nxt)))))

;; ============================================================
;; Emission — a let-chain of self-contained linear composites
;; ============================================================

(defn- fmt-num
  "edit-bezier/edit-mesh-split rounding: nearest int within 1e-9, else 2
   decimals trimmed."
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
  "Ahead pieces that are themselves cut — inlined inside a run's nested
   destructure, so they get no let-binding name."
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
                     (case (:type g)
                       :cut [(:behind g) (:ahead g)]
                       :separate (:components g))))
        pre-order (fn pre [pid]
                    (cons pid (when (contains? in? pid) (mapcat pre (children pid)))))
        named (->> (pre-order 0)
                   (remove #(= 0 %))
                   (remove inter))]
    (into {} (map-indexed (fn [i pid] [pid (str "piece-" (inc i))]) named))))

(defn- name-of [tree nm pid]
  (if (= 0 pid) (:source-expr tree) (nm pid)))

(defn- nested-destructure
  "{p1 :behind {p2 :behind … {pn :behind :ahead <rem>} :ahead} :ahead} for a
   run's behind names [p1…pn] and its final remaining name."
  [behind-names rem-name]
  (if (empty? behind-names)
    rem-name
    (str "{" (first behind-names) " :behind "
         (nested-destructure (rest behind-names) rem-name) " :ahead}")))

(defn- run-binding
  [tree nm run]
  (let [entry (:entry-pose tree)
        poses (into [entry] (map :pose run))
        deltas (mapv (fn [[a b]] (turtle/synthesize-delta a b)) (partition 2 1 poses))
        path-forms (str/join " "
                             (map-indexed
                              (fn [i delta] (str (delta->cmds-str delta) " (mark :cut-" (inc i) ")"))
                              deltas))
        marks-vec (str "[" (str/join " " (map #(str ":cut-" (inc %)) (range (count run)))) "]")
        behind-names (mapv #(name-of tree nm (:behind %)) run)
        rem-name (name-of tree nm (:ahead (peek run)))
        input-name (name-of tree nm (:input (first run)))
        destructure (nested-destructure behind-names rem-name)]
    (str destructure "\n      (mesh-split " input-name "\n"
         "                  (path " path-forms ")\n"
         "                  " marks-vec ")")))

(defn- separation-binding
  [tree nm sep]
  (let [comp-names (str/join " " (map #(name-of tree nm %) (:components sep)))
        input-name (name-of tree nm (:input sep))]
    (str "[" comp-names "] (mesh-components " input-name ")")))

(defn emit
  "The emitted source: a let-chain of self-contained linear composites plus the
   `mesh-components` destructures for separations, closing over a body vector of
   the leaf names in DFS order. With no structural gestures the whole thing is
   just the input literal (nothing was cut)."
  [tree]
  (if (empty? (:log tree))
    (:source-expr tree)
    (let [nm (name-map tree)
          op-idx (into {} (map-indexed (fn [i g] [g i]) (:log tree)))
          run-vec (runs tree)
          run-entries (map (fn [run] {:order (apply min (map op-idx run))
                                      :binding (run-binding tree nm run)}) run-vec)
          sep-entries (map (fn [sep] {:order (op-idx sep)
                                      :binding (separation-binding tree nm sep)})
                           (separations tree))
          bindings (->> (concat run-entries sep-entries)
                        (sort-by :order)
                        (map :binding))
          body (str "[" (str/join " " (map #(name-of tree nm %) (leaf-ids tree))) "]")]
      (str "(let [" (str/join "\n      " bindings) "]\n"
           "  " body ")"))))

(defn piece-name
  "The emission name of a piece (for scene labels) — the leaf shows the same
   binding name the emitted let uses; the root shows its source-expr."
  [tree pid]
  (name-of tree (name-map tree) pid))
