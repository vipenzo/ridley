(ns ridley.editor.mesh-split-tree-test
  "Pure structural + emission tests for the tree session model. No WASM — the
   reports ({:finished? :count}) are supplied as literals exactly as the live
   WASM caller would, so the whole model is exercised in node."
  (:require [cljs.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ridley.editor.mesh-split-tree :as tree]))

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
      (is (str/includes? code "[piece-1 piece-2]")))))

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
    (is (str/includes? code "[piece-1 piece-2 piece-3]"))))

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

;; ── all-finished? drives the close condition ────────────────

(deftest all-finished-true-only-when-every-leaf-finished
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        r1 (tree/cut t0 0 (pose 10) fin conc)]
    (is (false? (tree/all-finished? (:tree r1))) "ahead still concave")
    (let [r2 (tree/cut (:tree r1) (:ahead r1) (pose 20) fin fin)]
      (is (true? (tree/all-finished? (:tree r2))) "every leaf now finished"))))

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

(deftest piece-name-matches-the-emitted-binding
  (let [t0 (tree/make-tree "block" (pose 0) conc)
        {:keys [tree behind ahead]} (tree/cut t0 0 (pose 10) fin conc)]
    (is (= "piece-1" (tree/piece-name tree behind)))
    (is (= "piece-2" (tree/piece-name tree ahead)))
    (is (= "block" (tree/piece-name tree 0)) "root shows its source-expr")))
