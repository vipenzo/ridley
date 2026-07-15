(ns ridley.editor.mesh-split-tree-test
  "Pure structural + emission tests for the tree session model. No WASM — the
   reports ({:finished? :count}) are supplied as literals exactly as the live
   WASM caller would, so the whole model is exercised in node."
  (:require [cljs.test :refer [deftest testing is]]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [ridley.editor.mesh-split-tree :as tree]
            [ridley.test-helpers :as h]))

(def ^:private fin  {:finished? true  :count 1})
(def ^:private conc {:finished? false :count 1})
(def ^:private multi {:finished? true :count 2})

(defn- pose [x] {:position [x 0 0] :heading [1 0 0] :up [0 0 1]})

(defn- n-calls [code re] (count (re-seq re code)))

;; ── construction ────────────────────────────────────────────

(deftest fresh-tree-is-just-the-root
  (let [t (tree/make-tree "block" (pose 0) conc)]
    (is (= 0 (:current t)))
    (is (= [0] (tree/leaf-ids t)))
    (is (tree/leaf? t 0))
    (is (false? (tree/all-finished? t)))
    (is (= "block" (tree/emit t)) "nothing cut → the input literal, verbatim")))

(deftest fresh-finished-tree-is-all-finished
  (let [t (tree/make-tree "block" (pose 0) fin)]
    (is (true? (tree/all-finished? t)))
    (is (empty? (tree/non-finished-leaves t)))))

;; ── single cut ──────────────────────────────────────────────

(deftest single-cut-two-leaves-one-mesh-split
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        {:keys [tree behind ahead]} (tree/cut t0 0 (pose 10) fin conc)]
    (is (= [behind ahead] (tree/leaf-ids tree)) "DFS: behind before ahead")
    (is (= ahead (:current tree)) "ahead not finished → current advances to it")
    (is (= [ahead] (tree/non-finished-leaves tree)) "behind is finished, only ahead open")
    (let [code (tree/emit tree)]
      (is (= 1 (n-calls code #"mesh-split")))
      (is (str/includes? code "mesh-split block"))
      (is (str/includes? code "[:cut-1]"))
      (is (str/includes? code "{piece-1 :behind piece-2 :ahead}"))
      (is (str/includes? code "{:piece-1 piece-1 :piece-2 piece-2}")))))

(deftest cut-with-finished-ahead-moves-current-to-next-open
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        {:keys [tree behind ahead]} (tree/cut t0 0 (pose 10) conc fin)]
    ;; ahead finished, behind not → current should be the behind (next non-finished)
    (is (= behind (:current tree)))
    (is (= [behind] (tree/non-finished-leaves tree)))))

;; ── linear chain (cut the ahead repeatedly = one run) ───────

(deftest linear-chain-is-a-single-mesh-split-call
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/cut t0 0 (pose 10) fin conc)
        r2 (tree/cut (:tree r1) (:ahead r1) (pose 20) fin conc)
        tree (:tree r2)
        code (tree/emit tree)]
    (is (= 1 (n-calls code #"mesh-split")) "consecutive ahead-cuts collapse into ONE call")
    (is (str/includes? code "[:cut-1 :cut-2]"))
    (is (str/includes? code "{piece-1 :behind {piece-2 :behind piece-3 :ahead} :ahead}"))
    (is (= 3 (count (tree/leaf-ids tree))))
    (is (str/includes? code "{:piece-1 piece-1 :piece-2 piece-2 :piece-3 piece-3}"))))

;; ── branch (cut a behind → a second call in the let-chain) ──

(deftest branch-emits-a-second-mesh-split-fed-by-a-binding
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/cut t0 0 (pose 10) conc conc)      ; both halves open
        r2 (tree/cut (:tree r1) (:behind r1) (pose 5) fin fin)  ; cut the BEHIND
        tree (:tree r2)
        code (tree/emit tree)]
    (is (= 2 (n-calls code #"mesh-split")) "cutting a non-ahead piece starts a new call")
    (is (str/includes? code "mesh-split block") "root run consumes the source literal")
    (is (str/includes? code "mesh-split piece-1") "the branch run consumes the behind's binding")
    ;; b1 (piece-1) is cut → not a leaf; leaves are b2, a2, a1
    (is (= 3 (count (tree/leaf-ids tree))))
    (is (not (tree/leaf? tree (:behind r1))))))

;; ── separation (mesh-components destructure) ────────────────

(deftest separate-emits-a-mesh-components-destructure
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/cut t0 0 (pose 10) fin multi)       ; ahead is multi-component
        r2 (tree/separate (:tree r1) (:ahead r1) [fin fin])
        tree (:tree r2)
        code (tree/emit tree)]
    (is (= 2 (count (:components r2))))
    (is (str/includes? code "mesh-components piece-2") "separates the run's remaining binding")
    (is (str/includes? code "[piece-3 piece-4] (mesh-components"))
    ;; the ahead (piece-2) is separated → not a leaf; leaves are b1 + the 2 comps
    (is (= 3 (count (tree/leaf-ids tree))))
    (is (true? (tree/all-finished? tree)) "b1 finished + both components finished")))

;; ── undo (chronological, cross-branch) ──────────────────────

(deftest undo-cut-removes-both-halves-and-reopens-input
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        {:keys [tree behind ahead]} (tree/cut t0 0 (pose 10) fin conc)
        u (tree/undo tree)]
    (is (= #{behind ahead} (set (:removed u))) "both produced pieces are freed")
    (is (= 0 (:current (:tree u))) "the input is re-opened as current")
    (is (= (pose 10) (:pose u)) "the popped cut's pose, for plane restoration")
    (is (empty? (:log (:tree u))))
    (is (= "block" (tree/emit (:tree u))) "back to the untouched input")))

(deftest undo-separate-removes-components-no-pose
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/separate t0 0 [fin fin conc])
        u (tree/undo (:tree r1))]
    (is (= (set (:components r1)) (set (:removed u))))
    (is (= 0 (:current (:tree u))))
    (is (nil? (:pose u)) "a separation has no plane")
    (is (= "block" (tree/emit (:tree u))))))

(deftest undo-is-chronological-across-branches
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/cut t0 0 (pose 10) conc conc)
        r2 (tree/cut (:tree r1) (:behind r1) (pose 5) conc conc)  ; branch — LAST gesture
        u  (tree/undo (:tree r2))]
    ;; the last gesture (the branch cut) is popped, NOT the first
    (is (= #{(:behind r2) (:ahead r2)} (set (:removed u))))
    (is (= (:behind r1) (:current (:tree u))) "re-opens the branch's input")
    (is (= 1 (n-calls (tree/emit (:tree u)) #"mesh-split")) "one cut left → one call")))

(deftest undo-empty-log-is-nil
  (is (nil? (tree/undo (tree/make-tree "block" (pose 0) conc)))))

;; ── selection & cycling (not structural) ────────────────────

(deftest select-only-lands-on-leaves-and-does-not-log
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/cut t0 0 (pose 10) conc conc)
        tree (:tree r1)]
    (is (= (:behind r1) (:current (tree/select tree (:behind r1)))))
    (is (= (:current tree) (:current (tree/select tree 0)))
        "0 was cut → not a leaf → select is a no-op")
    (is (= (:log tree) (:log (tree/select tree (:behind r1)))) "selection never logs")))

(deftest cycle-current-round-robins-non-finished-leaves
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/cut t0 0 (pose 10) conc conc)       ; b1, a1 both open
        tree (:tree r1)
        nfl (tree/non-finished-leaves tree)
        c1 (tree/cycle-current tree)
        c2 (tree/cycle-current c1)]
    (is (= 2 (count nfl)))
    (is (not= (:current tree) (:current c1)) "advances")
    (is (= (:current tree) (:current c2)) "round-robin wraps back")))

(deftest cycle-with-no-open-leaves-is-noop
  (let [t (tree/make-tree "block" (pose 0) fin)]
    (is (= (:current t) (:current (tree/cycle-current t))))))

(deftest cycle-prev-goes-backward-round-robin
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/cut t0 0 (pose 10) conc conc)       ; b1, a1 both open (2 leaves)
        r2 (tree/cut (:tree r1) (:ahead r1) (pose 20) conc conc)  ; cut a1 → b2, a2 (3 open)
        tree (:tree r2)
        nfl (tree/non-finished-leaves tree)]
    (is (= 3 (count nfl)))
    ;; next then prev returns to the same current
    (let [nx (tree/cycle-current tree :next)]
      (is (= (:current tree) (:current (tree/cycle-current nx :prev)))))
    ;; prev from the first wraps to the last
    (let [at-first (assoc tree :current (first nfl))]
      (is (= (peek nfl) (:current (tree/cycle-current at-first :prev)))))))

(deftest position-info-reports-name-index-status-count
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        {:keys [tree ahead]} (tree/cut t0 0 (pose 10) fin conc)]
    ;; current is the ahead (open, concave); leaves DFS = [behind ahead]
    (is (= ahead (:current tree)))
    (let [{:keys [name index total open? count]} (tree/position-info tree)]
      (is (= "piece-2" name) "same name the emission uses")
      (is (= 2 index) "ahead is 2nd in the leaf DFS order")
      (is (= 2 total))
      (is (true? open?))
      (is (= 1 count)))))

;; ── panel views: tree-view / leaf-counts (acquisition-views Parte 2) ──

(deftest tree-view-fresh-tree-is-a-single-open-root-leaf
  (let [t (tree/make-tree "block" (pose 0) conc)]
    (is (= {:id 0 :name "block" :leaf? true :current? true
            :status :open :children nil}
           (tree/tree-view t)))
    (is (= {:open 1 :finished 0} (tree/leaf-counts t)))))

(deftest tree-view-single-cut-two-named-leaf-children
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        {:keys [tree behind ahead]} (tree/cut t0 0 (pose 10) fin conc)
        {:keys [children] :as root} (tree/tree-view tree)]
    (is (= "block" (:name root)))
    (is (false? (:leaf? root)))
    (is (= [{:id behind :name "piece-1" :leaf? true :current? false
             :status :finished :children nil}
            {:id ahead :name "piece-2" :leaf? true :current? true
             :status :open :children nil}]
           children))
    (is (= {:open 1 :finished 1} (tree/leaf-counts tree)))))

(deftest tree-view-skips-unnamed-intermediate-aheads
  (testing "a linear chain's intermediate ahead has no emission name — its
            named children are reparented as direct siblings under root, one
            flat run just like the single mesh-split call it emits"
    (let [t0 (tree/make-tree "block" (pose 0) conc)
          r1 (tree/cut t0 0 (pose 10) fin conc)
          r2 (tree/cut (:tree r1) (:ahead r1) (pose 20) fin conc)
          {:keys [children]} (tree/tree-view (:tree r2))]
      (is (= ["piece-1" "piece-2" "piece-3"] (map :name children))
          "all 3 leaves are siblings — the intermediate ahead is invisible")
      (is (every? :leaf? children))
      (is (every? #(nil? (:children %)) children)))))

(deftest tree-view-branch-nests-a-named-non-leaf
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/cut t0 0 (pose 10) conc conc)                   ; b1, a1 both open
        r2 (tree/cut (:tree r1) (:behind r1) (pose 5) fin fin)   ; cut the BEHIND
        {:keys [children]} (tree/tree-view (:tree r2))
        [branch leaf] children]
    (is (= "piece-1" (:name branch)) "the cut behind is a named, non-leaf node")
    (is (false? (:leaf? branch)))
    (is (= ["piece-2" "piece-3"] (map :name (:children branch))))
    (is (= "piece-4" (:name leaf)) "the never-cut ahead is a named leaf sibling")
    (is (true? (:leaf? leaf)))
    (is (= {:open 1 :finished 2} (tree/leaf-counts (:tree r2)))
        "piece-4 (a1) still concave — the only open leaf")))

(deftest tree-view-native-status-is-predisposed-and-additive
  (testing ":native? is read but never written yet — a native piece is by
            construction also :finished?, so leaf-counts (which only reads
            :finished?) is unaffected"
    (let [t0 (tree/make-tree "block" (pose 0) conc)
          {:keys [tree behind]} (tree/cut t0 0 (pose 10) fin conc)
          native-tree (assoc-in tree [:pieces behind :native?] true)]
      (is (= :native (:status (first (:children (tree/tree-view native-tree))))))
      (is (= (tree/leaf-counts tree) (tree/leaf-counts native-tree))))))

;; ── all-finished? drives the close condition ────────────────

(deftest all-finished-true-only-when-every-leaf-finished
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/cut t0 0 (pose 10) fin conc)]
    (is (false? (tree/all-finished? (:tree r1))) "ahead still concave")
    (let [r2 (tree/cut (:tree r1) (:ahead r1) (pose 20) fin fin)]
      (is (true? (tree/all-finished? (:tree r2))) "every leaf now finished"))))

;; ── accept-current (addendum 4 Parte A: 'accetta così com'è') ──

(deftest accept-current-flips-a-concave-piece-and-logs-a-gesture
  (let [t0 (tree/make-tree "block" (pose 0) conc)   ; single-leaf tree, root open+concave
        t1 (tree/accept-current t0)]
    (is (true? (get-in t1 [:pieces 0 :finished?])))
    (is (true? (get-in t1 [:pieces 0 :decided?])))
    (is (= [{:type :accept :pid 0}] (:log t1)))
    (is (true? (tree/all-finished? t1)))
    (is (= "block" (tree/emit t1)) "emission unaffected by an accepted-by-decision leaf")))

(deftest accept-current-on-a-finished-piece-is-a-noop
  (let [t0 (tree/make-tree "block" (pose 0) fin)]
    (is (= t0 (tree/accept-current t0)) "already finished — nothing to decide, no gesture logged")))

(deftest accept-current-advances-to-the-next-open-leaf
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        {:keys [tree behind ahead]} (tree/cut t0 0 (pose 10) conc conc)  ; both open
        t1 (tree/accept-current tree)]
    (is (= ahead (:current tree)) "cut leaves current on the ahead (both open — guillotine continuity)")
    (is (true? (get-in t1 [:pieces ahead :finished?])) "the accepted piece (ahead, current) is now finished")
    (is (= behind (:current t1)) "current advances to the only remaining open leaf")
    (is (= [behind] (tree/non-finished-leaves t1)) "the accepted piece leaves the open pool")
    (is (= {:open 1 :finished 1} (tree/leaf-counts t1)))))

(deftest accept-current-of-a-multi-component-piece-preserves-count
  (let [t0 (tree/make-tree "block" (pose 0) multi)   ; single leaf, 2 concave components
        t1 (assoc t0 :pieces (assoc (:pieces t0) 0 (assoc (get-in t0 [:pieces 0]) :finished? false)))
        t2 (tree/accept-current t1)]
    (is (= 2 (get-in t2 [:pieces 0 :count])) "count is preserved, only :finished? flips")
    (is (true? (get-in t2 [:pieces 0 :finished?])))))

(deftest undo-accept-reopens-the-piece-no-removed-no-pose
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        t1 (tree/accept-current t0)
        u (tree/undo t1)]
    (is (empty? (:removed u)) "accept never produced pieces")
    (is (nil? (:pose u)) "accept has no plane")
    (is (= 0 (:current (:tree u))))
    (is (false? (get-in (:tree u) [:pieces 0 :finished?])))
    (is (not (contains? (get-in (:tree u) [:pieces 0]) :decided?)))
    (is (empty? (:log (:tree u))))
    (is (= t0 (:tree u)) "undo of a lone accept exactly restores the pre-gesture tree")))

(deftest undo-accept-does-not-disturb-a-preceding-cut
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        {:keys [tree ahead]} (tree/cut t0 0 (pose 10) conc conc)  ; both open, current = ahead
        t1 (tree/accept-current tree)                            ; accept the current (ahead)
        u (tree/undo t1)]
    (is (= ahead (:current (:tree u))) "undo reopens the accepted piece, not the cut's input")
    (is (false? (get-in (:tree u) [:pieces ahead :finished?])))
    (is (= 1 (count (:log (:tree u)))) "only the accept gesture popped — the cut remains logged")
    (is (= tree (:tree u)) "back to exactly the post-cut, pre-accept tree")))

(deftest tree-view-status-of-an-accepted-piece-reads-finished
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        {:keys [tree ahead]} (tree/cut t0 0 (pose 10) conc conc)
        t1 (tree/accept-current tree)
        {:keys [children]} (tree/tree-view t1)
        ahead-node (first (filter #(= ahead (:id %)) children))]
    (is (= :finished (:status ahead-node)) "identical to a naturally-green leaf — no special visual state")))

;; ── piece names match emission (scene labels) ───────────────

(deftest emission-deltas-are-canonical-from-entry-pose
  (testing "each run's path deltas are synthesize-delta from the entry pose — a
            pure forward cut emits just (f …); marks restart per run"
    (let [t0 (tree/make-tree "block" (pose 0) conc)
          r1 (tree/cut t0 0 (pose -15) fin conc)
          r2 (tree/cut (:tree r1) (:ahead r1) (pose 15) fin conc)
          code (tree/emit (:tree r2))]
      (is (str/includes? code "(f -15) (mark :cut-1)") "entry(0)→(-15) = (f -15)")
      (is (str/includes? code "(f 30) (mark :cut-2)") "(-15)→(15) = (f 30)")
      (is (str/includes? code "[:cut-1 :cut-2]")))))

(deftest emission-branch-restarts-marks-and-poses-per-run
  (testing "a second run (cut a behind) restarts marks at :cut-1 and deltas from
            the SAME entry pose, not chained across runs (A2)"
    (let [t0 (tree/make-tree "block" (pose 0) conc)
          r1 (tree/cut t0 0 (pose 10) conc conc)
          r2 (tree/cut (:tree r1) (:behind r1) (pose -5) fin fin)
          code (tree/emit (:tree r2))]
      ;; run 1 input=block cut at (f 10); run 2 input=piece-1 cut at (f -5)
      (is (str/includes? code "mesh-split block"))
      (is (str/includes? code "(f 10) (mark :cut-1)"))
      (is (str/includes? code "mesh-split piece-1"))
      (is (str/includes? code "(f -5) (mark :cut-1)") "run 2 restarts :cut-1, delta from entry"))))

;; ── emitted numbers stand for the plane they came from ──────

(deftest emission-never-moves-the-plane
  (testing "the emitted path IS the cut, so a number may be shortened but not
            moved. The mount STL's step face sits at -1.6250512734795155; a flat
            2-decimal rounding emitted (f -1.63) — 4.9µm PAST the face, which
            cuts through solid material and hands a 2.87mm³ sheet to the next
            piece, rendering as the wafer the bug report described (measured
            live 2026-07-15)."
    (let [exact -1.6250512734795155
          t0 (tree/make-tree "block" (pose 0) conc)
          r (tree/cut t0 0 (pose exact) fin fin)
          code (tree/emit (:tree r))
          emitted (some-> (re-find #"\(f (-?[\d.]+)\)" code) second js/parseFloat)]
      (is (not (str/includes? code "(f -1.63)")) "the exact rounding that stole the sheet")
      (is (some? emitted) "an (f …) was emitted")
      (is (< (js/Math.abs (- emitted exact)) 1e-6)
          (str "emitted " emitted " must stand for " exact " to within 1e-6 mm — "
               "the bound is the ~7nm float32 planarity band of an imported face: "
               "-1nm still cuts the intended solid, -10nm transfers it")))))

(deftest emission-keeps-exact-numbers-short
  (testing "faithful precision must not make ordinary values ugly — a value that
            IS an integer emits as one, no 16-digit tail"
    (let [t0 (tree/make-tree "block" (pose 0) conc)
          r (tree/cut t0 0 (pose 10) fin fin)]
      (is (str/includes? (tree/emit (:tree r)) "(f 10) (mark :cut-1)")))))

;; ── mirror flag, group undo, reflection (symmetry brief Parts 4-5) ──

(deftest mirror-cut-emits-a-symmetry-comment
  (testing "a confirmed-mirror cut leaves a ;; comment on its mark's line"
    (let [t0 (tree/make-tree "block" (pose 0) conc)
          r1 (tree/cut t0 0 (pose 10) fin conc {:mirror? true})
          code (tree/emit (:tree r1))]
      (is (str/includes? code ";; :cut-1: piano di simmetria"))
      ;; a non-mirror cut has no such comment
      (let [r2 (tree/cut t0 0 (pose 10) fin conc)]
        (is (not (str/includes? (tree/emit (:tree r2)) "piano di simmetria")))))))

(deftest mirror-comment-on-the-last-binding-does-not-swallow-the-closing-bracket
  (testing "regression (found live 2026-07-14): when the mirror-commented cut is
            the LAST (here, only) run, emit's own closing ']' used to land on the
            comment's line and get swallowed by it — 'Unmatched delimiter )' on
            read. The comment now ends with its own trailing newline."
    (let [t0 (tree/make-tree "block" (pose 0) conc)
          r1 (tree/cut t0 0 (pose 10) conc conc {:mirror? true})
          code (tree/emit (:tree r1))]
      (is (some? (reader/read-string code)) "must parse — a comment must never eat a structural character"))))

(deftest group-undo-pops-the-whole-replay-atomically
  (testing "cuts sharing a :group are one structural gesture — one undo removes all"
    (let [t0 (tree/make-tree "block" (pose 0) conc)
          r1 (tree/cut t0 0 (pose 10) conc conc {:group :g1})
          r2 (tree/cut (:tree r1) (:behind r1) (pose 5) fin fin {:group :g1})
          u (tree/undo (:tree r2))]
      (is (= #{(:behind r1) (:ahead r1) (:behind r2) (:ahead r2)} (set (:removed u)))
          "all four pieces of the group removed at once")
      (is (= 0 (:current (:tree u))) "re-opens the group's first input")
      (is (empty? (:log (:tree u))))
      (is (= "block" (tree/emit (:tree u)))))))

(deftest lone-cut-still-pops-singly
  (testing "a cut without a group pops just itself (unchanged behavior)"
    (let [t0 (tree/make-tree "block" (pose 0) conc)
          r1 (tree/cut t0 0 (pose 10) conc conc {:group :g1})
          r2 (tree/cut (:tree r1) (:behind r1) (pose 5) fin fin)  ; NO group
          u (tree/undo (:tree r2))]
      (is (= #{(:behind r2) (:ahead r2)} (set (:removed u))) "only the lone cut popped"))))

(deftest reflect-pose-mirrors-position-and-heading-only
  (testing "position and heading reflect through the plane; up is not returned"
    (let [p {:position [3 2 1] :heading [1 0 0] :up [0 0 1]}
          r0 (tree/reflect-pose p [1 0 0] [0 0 0])
          r5 (tree/reflect-pose p [1 0 0] [5 0 0])]
      (is (h/vec-approx= [-3.0 2.0 1.0] (:position r0) 1e-9))
      (is (h/vec-approx= [-1.0 0.0 0.0] (:heading r0) 1e-9))
      (is (h/vec-approx= [7.0 2.0 1.0] (:position r5) 1e-9) "off-origin plane reflects position about the point")
      (is (h/vec-approx= [-1.0 0.0 0.0] (:heading r5) 1e-9) "heading reflects about the normal, point-independent")
      (is (nil? (:up r0)) "up is left free"))))

(deftest subtree-walk-collects-descendant-pieces-and-gestures
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/cut t0 0 (pose 10) conc conc)          ; b1, a1
        r2 (tree/cut (:tree r1) (:ahead r1) (pose 20) conc conc)  ; cut a1 → b2, a2
        tree (:tree r2)]
    (is (= #{(:ahead r1) (:behind r2) (:ahead r2)}
           (set (tree/descendant-pieces tree (:ahead r1)))) "a1 subtree = a1,b2,a2")
    (is (= 1 (count (tree/subtree-gestures tree (:ahead r1)))) "one gesture inside a1's subtree")
    (is (= [(:behind r1)] (tree/descendant-pieces tree (:behind r1))) "a leaf's subtree is itself")
    (is (= 0 (count (tree/subtree-gestures tree (:behind r1)))))))

(deftest piece-name-matches-the-emitted-binding
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        {:keys [tree behind ahead]} (tree/cut t0 0 (pose 10) fin conc)]
    (is (= "piece-1" (tree/piece-name tree behind)))
    (is (= "piece-2" (tree/piece-name tree ahead)))
    (is (= "block" (tree/piece-name tree 0)) "root shows its source-expr")))
