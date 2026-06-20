(ns ridley.editor.on-anchors-test
  "Tests for the on-anchors macro: pattern dispatch over anchor markers
   on a path or on a mesh with anchors. Verifies pattern types, alignment,
   first-match-wins, unmatched-pattern warnings, and empty-target behavior."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]))

(defn- run [code]
  (h/reset-warnings!)
  (h/eval-dsl code))

(defn- approx=
  ([a b] (approx= a b 0.01))
  ([a b tol] (< (js/Math.abs (- a b)) tol)))

(defn- v-approx=
  [a b & [tol]]
  (let [t (or tol 0.01)]
    (and (= (count a) (count b))
         (every? true? (map #(approx= %1 %2 t) a b)))))

;; A "probe mesh" body that captures the turtle position+heading+up where it
;; was evaluated and looks like a mesh so flatten-meshes lets it through.
(def ^:private probe-body
  "(fn [tag]
     {:vertices [(turtle-position)]
      :faces []
      :tag tag
      :heading (turtle-heading)
      :up (turtle-up)})")

(defn- probes [result]
  ;; concat-meshes collapses bodies into one mesh; recover the per-body :tag
  ;; via :source-history is brittle, so we just verify positions/headings.
  (:vertices result))

;; ── String prefix pattern ────────────────────────────────────────

(deftest string-prefix-pattern
  (testing "string pattern matches as prefix on (name anchor-name)"
    (let [code (str "(let [probe " probe-body "]
                      (def skel (path (mark :end-post-1) (f 10)
                                      (mark :mid-post-1) (f 10)
                                      (mark :end-post-2)))
                      (on-anchors skel
                        \"end-post-\" (probe :end)
                        \"mid-post-\" (probe :mid)))")
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= 3 (count (:vertices result))))
      ;; positions: :end-post-1 at [0 0 0], :mid-post-1 at [10 0 0], :end-post-2 at [20 0 0]
      (let [positions (set (mapv #(mapv js/Math.round %) (:vertices result)))]
        (is (= #{[0 0 0] [10 0 0] [20 0 0]} positions))))))

;; ── Regex pattern ────────────────────────────────────────────────

(deftest regex-pattern
  (testing "regex pattern matches via re-find on (name anchor-name)"
    (let [code (str "(let [probe " probe-body "]
                      (def skel (path (mark :foo-1) (f 5)
                                      (mark :bar-1) (f 5)
                                      (mark :foo-2)))
                      (on-anchors skel
                        #\"^foo-\" (probe :foo)
                        #\"^bar-\" (probe :bar)))")
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= 3 (count (:vertices result))))
      (let [positions (set (mapv #(mapv js/Math.round %) (:vertices result)))]
        (is (= #{[0 0 0] [5 0 0] [10 0 0]} positions))))))

;; ── Keyword pattern (single anchor) ──────────────────────────────

(deftest keyword-pattern
  (testing "keyword pattern matches exactly one anchor by name"
    (let [code (str "(let [probe " probe-body "]
                      (def skel (path (mark :a) (f 7) (mark :b) (f 7) (mark :c)))
                      (on-anchors skel
                        :b (probe :only-b)))")
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= 1 (count (:vertices result))))
      (is (v-approx= [7 0 0] (first (:vertices result)))))))

;; ── Set pattern ──────────────────────────────────────────────────

(deftest set-pattern
  (testing "set pattern matches any anchor name in the set"
    (let [code (str "(let [probe " probe-body "]
                      (def skel (path (mark :foot-1) (f 10)
                                      (mark :foot-2) (f 10)
                                      (mark :foot-3) (f 10)
                                      (mark :foot-4)))
                      (on-anchors skel
                        #{:foot-1 :foot-3} (probe :odd)
                        #{:foot-2 :foot-4} (probe :even)))")
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= 4 (count (:vertices result)))))))

;; ── First-match-wins ─────────────────────────────────────────────

(deftest first-match-wins
  (testing "overlapping patterns: first match consumes the anchor"
    ;; ":foot-1" matches both the regex and the set, but the regex comes first.
    (let [code (str "(let [probe " probe-body "]
                      (def skel (path (mark :foot-1) (f 10)
                                      (mark :foot-2)))
                      (on-anchors skel
                        #\"^foot-\" (probe :regex)
                        #{:foot-1}  (probe :set)))")
          {:keys [result error]} (run code)
          warnings @h/on-anchors-warnings]
      (is (nil? error) (str "Should not error: " error))
      ;; Both anchors matched the regex clause; the set clause matched none.
      (is (= 2 (count (:vertices result))))
      (is (= 1 (count warnings)))
      (is (= #{:foot-1} (:pattern (first warnings)))))))

;; ── :align vs default no-align ───────────────────────────────────

(deftest no-align-default
  (testing "without :align, only position is set — heading/up inherited from parent"
    (let [code (str "(let [probe " probe-body "]
                      ;; Path turns 90deg so :b's marker has heading +y instead of +x.
                      (def skel (path (mark :a) (f 5) (th 90) (f 5) (mark :b)))
                      (on-anchors skel
                        :b (probe :no-align)))")
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= 1 (count (:vertices result))))
      ;; Parent heading was +x [1 0 0]; without :align the probe sees parent heading.
      (let [mesh-head (-> result :vertices first)]
        ;; Position is at the :b marker [5 5 0]
        (is (v-approx= [5 5 0] mesh-head)))
      ;; The probe inside captured (turtle-heading) — concat-meshes collapses
      ;; sub-meshes so we can't recover it from the final mesh. Verify behavior
      ;; via the turtle-state side effect: see align test below.
      )))

(deftest align-rotates-frame
  (testing ":align switches to full pose alignment"
    ;; Build a path with a 90deg turn between :a (heading +x) and :b (heading +y).
    ;; With :align the body's turtle scope sees heading +y at :b.
    ;; We probe by emitting (turtle-heading) into vertices.
    (let [code "(def skel (path (mark :a) (f 5) (th 90) (f 5) (mark :b)))
                (on-anchors skel
                  :a :align {:vertices [(turtle-heading)] :faces []}
                  :b :align {:vertices [(turtle-heading)] :faces []})"
          {:keys [result error]} (run code)
          headings (:vertices result)]
      (is (nil? error) (str "Should not error: " error))
      (is (= 2 (count headings)))
      (is (some #(v-approx= [1 0 0] %) headings) "anchor :a heading is +x")
      (is (some #(v-approx= [0 1 0] %) headings) "anchor :b heading is +y"))))

(deftest no-align-keeps-parent-heading
  (testing "without :align the body inherits parent turtle heading regardless of anchor frame"
    (let [code "(def skel (path (mark :a) (f 5) (th 90) (f 5) (mark :b)))
                (on-anchors skel
                  :a {:vertices [(turtle-heading)] :faces []}
                  :b {:vertices [(turtle-heading)] :faces []})"
          {:keys [result error]} (run code)
          headings (:vertices result)]
      (is (nil? error) (str "Should not error: " error))
      (is (= 2 (count headings)))
      ;; Both probes see the parent heading [1 0 0], not the anchor's local heading.
      (is (every? #(v-approx= [1 0 0] %) headings)))))

;; ── Body shapes ──────────────────────────────────────────────────

(deftest body-direct-mesh-literal
  (testing "body can be any expression returning a mesh"
    (let [code "(def skel (path (mark :a) (f 5) (mark :b)))
                (on-anchors skel
                  :a {:vertices [[1 0 0]] :faces []}
                  :b {:vertices [[2 0 0]] :faces []})"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= 2 (count (:vertices result)))))))

(deftest body-mesh-union
  (testing "body can be a composite expression"
    (let [code "(def skel (path (mark :a) (f 5) (mark :b)))
                (on-anchors skel
                  :a (mesh-union {:vertices [[1 0 0]] :faces []}
                                 {:vertices [[2 0 0]] :faces []})
                  :b {:vertices [[3 0 0]] :faces []})"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      ;; :a contributes 2 verts, :b contributes 1
      (is (= 3 (count (:vertices result)))))))

;; ── Target = mesh with :anchors ──────────────────────────────────

(deftest target-mesh-with-anchors
  (testing "target may be a mesh map with :anchors directly"
    (let [code "(def host {:vertices [[0 0 0]]
                           :faces []
                           :creation-pose {:position [0 0 0]
                                           :heading [1 0 0] :up [0 0 1]}
                           :anchors {:slot-a {:position [10 0 0]
                                              :heading [1 0 0] :up [0 0 1]}
                                     :slot-b {:position [0 20 0]
                                              :heading [0 1 0] :up [0 0 1]}}})
                (on-anchors host
                  \"slot-\" {:vertices [(turtle-position)] :faces []})"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= 2 (count (:vertices result))))
      (let [positions (set (mapv #(mapv js/Math.round %) (:vertices result)))]
        (is (= #{[10 0 0] [0 20 0]} positions))))))

;; ── Empty target / unmatched warnings ────────────────────────────

(deftest empty-target
  (testing "empty skeleton produces nil result and no anchor iteration"
    (let [code "(def skel (path (f 5)))   ;; no marks
                (on-anchors skel
                  \"foo-\" {:vertices [[0 0 0]] :faces []})"
          {:keys [result error]} (run code)
          warnings @h/on-anchors-warnings]
      (is (nil? error) (str "Should not error: " error))
      (is (nil? result) "concat-meshes of nothing returns nil")
      ;; No anchors → no per-pattern warning emitted (the seq check guards it).
      (is (empty? warnings)))))

(deftest unmatched-pattern-warning
  (testing "a pattern matching no anchor emits a warning"
    (let [code "(def skel (path (mark :a) (f 5) (mark :b)))
                (on-anchors skel
                  \"nonexistent-\" {:vertices [[0 0 0]] :faces []}
                  :a              {:vertices [[1 0 0]] :faces []})"
          {:keys [result error]} (run code)
          warnings @h/on-anchors-warnings]
      (is (nil? error) (str "Should not error: " error))
      (is (= 1 (count warnings)))
      (is (= "nonexistent-" (:pattern (first warnings))))
      (is (= [:a :b] (sort (keys (:anchor-map (first warnings)))))))))

(deftest matched-no-warning
  (testing "a pattern that matched at least one anchor does NOT warn"
    (let [code "(def skel (path (mark :a) (f 5) (mark :b)))
                (on-anchors skel
                  :a {:vertices [[1 0 0]] :faces []})"
          {:keys [result error]} (run code)
          warnings @h/on-anchors-warnings]
      (is (nil? error) (str "Should not error: " error))
      ;; :b is unmatched but that's fine (filtering is normal).
      ;; Only patterns with zero matches warn.
      (is (empty? warnings)))))

;; ── Expand-time clause parsing ───────────────────────────────────

(deftest pattern-without-body-fails
  (testing "pattern without a following body throws at expand time"
    (let [{:keys [error]} (run "(def skel (path (mark :a)))
                                (on-anchors skel
                                  \"foo-\" {:vertices [] :faces []}
                                  \"bar-\")")]
      (is (some? error))
      (is (re-find #"no body" error)))))

;; ── pin-path ─────────────────────────────────────────────────────

(deftest pin-path-at-origin
  (testing "pin-path at origin returns marks at their path-local positions"
    (let [code "(def skel (path (mark :a) (f 10) (mark :b)))
                (pin-path skel)"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (map? result))
      (is (= #{:a :b} (set (keys result))))
      (is (v-approx= [0 0 0] (:position (:a result))))
      (is (v-approx= [10 0 0] (:position (:b result)))))))

(deftest pin-path-at-shifted-turtle
  (testing "pin-path resolves marks relative to current turtle pose"
    (let [code "(def skel (path (mark :a) (f 10) (mark :b)))
                (turtle (f 100)
                  (pin-path skel))"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (v-approx= [100 0 0] (:position (:a result))))
      (is (v-approx= [110 0 0] (:position (:b result)))))))

(deftest pin-path-non-path-returns-nil
  (testing "pin-path on non-path values returns nil"
    (let [{:keys [result error]} (run "(pin-path {:vertices [] :faces []})")]
      (is (nil? error))
      (is (nil? result)))))

;; ── Composability: path marks resolved at current turtle pose ──

(deftest path-marks-resolve-at-current-turtle-pose
  (testing "on-anchors over a path resolves marks at CURRENT turtle pose, not world origin"
    (let [code "(def skel (path (mark :a) (f 10) (mark :b)))
                (turtle
                  (f 100)
                  (on-anchors skel
                    #\"\" {:vertices [(turtle-position)] :faces []}))"
          {:keys [result error]} (run code)
          positions (set (mapv #(mapv js/Math.round %) (:vertices result)))]
      (is (nil? error) (str "Should not error: " error))
      ;; Turtle moved to [100 0 0]. Marks of skel are at offsets [0 0 0] and [10 0 0]
      ;; relative to the turtle, so absolute world positions are [100 0 0] and [110 0 0].
      (is (= #{[100 0 0] [110 0 0]} positions)))))

(deftest path-marks-resolve-rotated-pose
  (testing "on-anchors carries heading rotation from parent turtle into path resolution"
    ;; After (th 90), heading is +y. The path's (f 10) between :a and :b moves
    ;; along the current heading, so :b lands at +y from :a.
    (let [code "(def skel (path (mark :a) (f 10) (mark :b)))
                (turtle
                  (th 90)
                  (on-anchors skel
                    #\"\" {:vertices [(turtle-position)] :faces []}))"
          {:keys [result error]} (run code)
          positions (set (mapv #(mapv js/Math.round %) (:vertices result)))]
      (is (nil? error) (str "Should not error: " error))
      ;; :a at origin (heading +y now), :b after (f 10) along +y → [0 10 0]
      (is (= #{[0 0 0] [0 10 0]} positions)))))

(deftest align-keyword-followed-by-body
  (testing ":align is correctly absorbed as a modifier, not a pattern"
    (let [code "(def skel (path (mark :a)))
                (on-anchors skel
                  :a :align {:vertices [(turtle-heading)] :faces []})"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= 1 (count (:vertices result)))))))

;; ── Combine modes ────────────────────────────────────────────────

(deftest combine-mode-vec
  (testing ":vec returns a vector of per-anchor meshes"
    (let [code "(def skel (path (mark :a) (f 5) (mark :b) (f 5) (mark :c)))
                (on-anchors skel :vec
                  #\".*\" {:vertices [(turtle-position)] :faces []})"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (vector? result))
      (is (= 3 (count result)))
      (is (every? :vertices result)))))

(deftest combine-mode-concat-explicit
  (testing ":concat is the explicit form of the default and behaves identically"
    (let [code "(def skel (path (mark :a) (f 5) (mark :b)))
                [(on-anchors skel
                   #\".*\" {:vertices [(turtle-position)] :faces []})
                 (on-anchors skel :concat
                   #\".*\" {:vertices [(turtle-position)] :faces []})]"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= (:vertices (first result)) (:vertices (second result)))))))

(deftest combine-mode-keyword-not-pattern
  (testing "a recognized combine keyword must not be parsed as a keyword pattern"
    ;; Bare :vec after target is the combine mode, not a pattern matching :vec.
    ;; The clause that follows must apply normally.
    (let [code "(def skel (path (mark :a) (f 5) (mark :b)))
                (on-anchors skel :vec
                  :a {:vertices [[1 0 0]] :faces []})"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (vector? result))
      (is (= 1 (count result))))))

(deftest combine-mode-union-smoke
  (testing ":union dispatches through mesh-union-impl (harness stubs to concat semantics)"
    ;; The test harness stubs mesh-union-impl to concat-meshes (no real boolean
    ;; available in node-test), so this only verifies the :union mode reaches
    ;; the runtime code path and produces a single mesh result.
    (let [code "(def skel (path (mark :a) (f 5) (mark :b)))
                (on-anchors skel :union
                  #\".*\" {:vertices [(turtle-position)] :faces []})"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (map? result))
      (is (= 2 (count (:vertices result)))))))

(deftest combine-mode-keyword-pattern-still-works
  (testing "keyword patterns not in the combine set still work as patterns"
    ;; :a is not in #{:concat :union :vec}, so it is parsed as a pattern,
    ;; preserving full backward compatibility with the existing API.
    (let [code "(def skel (path (mark :a) (f 5) (mark :b)))
                (on-anchors skel
                  :a {:vertices [[1 0 0]] :faces []})"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= 1 (count (:vertices result)))))))

;; ── Match bindings: anchor / $ / $1 ──────────────────────────────

(deftest capture-anchor-binding
  (testing "`anchor` is bound to the matched anchor keyword inside the body"
    (let [code "(def skel (path (mark :a) (f 5) (mark :b)))
                (on-anchors skel :vec
                  #\".*\" {:vertices [[0 0 0]] :faces [] :tag anchor})"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= #{:a :b} (set (map :tag result)))))))

(deftest capture-regex-groups
  (testing "`$` exposes the full match and `$1` the first capture group, so one
            regex clause can replace N hand-written clauses"
    (let [code "(def tags {\"0\" :zero \"1\" :one \"2\" :two})
                (def skel (path (mark :arm-0) (f 5) (mark :arm-1) (f 5) (mark :arm-2)))
                (on-anchors skel :vec
                  #\"arm-(\\d)\"
                  {:vertices [[0 0 0]] :faces []
                   :full $ :tag (tags $1)})"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= #{"arm-0" "arm-1" "arm-2"} (set (map :full result))))
      (is (= #{:zero :one :two} (set (map :tag result)))))))

(deftest capture-no-group-regex
  (testing "a regex with no capture group leaves $1 nil; $ is the whole match"
    (let [code "(def skel (path (mark :a) (f 5) (mark :b)))
                (on-anchors skel :vec
                  #\".*\" {:vertices [[0 0 0]] :faces [] :full $ :g1 $1})"
          {:keys [result error]} (run code)]
      (is (nil? error) (str "Should not error: " error))
      (is (= #{"a" "b"} (set (map :full result))))
      (is (every? nil? (map :g1 result))))))
