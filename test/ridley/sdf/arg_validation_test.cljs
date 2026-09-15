(ns ridley.sdf.arg-validation-test
  "Pure-level tests for argument validation in SDF-space operations.
   The mirror image of ridley.manifold.sdf-coercion-test: that one covers an
   SDF operand reaching a mesh op (where it gets materialized), this one covers
   a mesh operand reaching an SDF op (where there is no conversion, so it must
   be rejected on the spot).

   The check runs before any server round-trip, so — unlike the coercion
   tests — these verify the real production behaviour in a Node run.

   Before the check, a mesh operand was embedded whole into the SDF request and
   only rejected by the Rust parser, as `missing field op` at a column buried
   megabytes deep in serialized vertex data."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.sdf.core :as sdf]))

(def ^:private node
  "A syntactically valid SDF node — no server needed to build a tree from it."
  {:op "sphere" :r 5.0})

(def ^:private other-node
  {:op "box" :sx 1 :sy 1 :sz 1})

(def ^:private a-mesh
  {:type :mesh
   :vertices [[0 0 0] [1 0 0] [0 1 0] [0 0 1]]
   :faces [[0 2 1] [0 1 3] [1 2 3] [0 3 2]]})

;; ── A mesh operand is rejected, not serialized ──────────────────

(deftest sdf-blend-rejects-mesh
  (testing "sdf-blend throws on a mesh operand instead of building a bad tree"
    (is (thrown? js/Error (sdf/sdf-blend a-mesh node 3)))
    (is (thrown? js/Error (sdf/sdf-blend node a-mesh 3)))))

(deftest sdf-blend-mesh-error-names-the-register-trap
  (testing "the message points at register, the usual way a name turns into a mesh"
    (is (thrown-with-msg?
         js/Error #"argument 1 is a mesh.*register"
         (sdf/sdf-blend a-mesh node 3)))))

(deftest sdf-blend-error-identifies-the-offending-position
  (testing "argument index is 1-based and points at the mesh, not the first arg"
    (is (thrown-with-msg?
         js/Error #"argument 2 is a mesh"
         (sdf/sdf-blend node a-mesh 3)))))

(deftest booleans-reject-mesh-and-suggest-mesh-space
  (testing "sdf-union/difference/intersection name their mesh-* equivalent"
    (is (thrown-with-msg? js/Error #"mesh-union"
                          (sdf/sdf-union node a-mesh)))
    (is (thrown-with-msg? js/Error #"mesh-difference"
                          (sdf/sdf-difference node a-mesh)))
    (is (thrown-with-msg? js/Error #"mesh-intersection"
                          (sdf/sdf-intersection node a-mesh)))))

(deftest sdf-only-ops-suggest-nothing
  (testing "blend has no mesh equivalent, so it offers no mesh-space alternative"
    (is (thrown-with-msg? js/Error #"^(?!.*To work in mesh space).*mesh, not an SDF node"
                          (sdf/sdf-blend a-mesh node 3)))))

(deftest vector-form-is-checked-too
  (testing "(sdf-union [a b]) validates the elements, not the vector itself"
    (is (thrown-with-msg? js/Error #"argument 2 is a mesh"
                          (sdf/sdf-union [node a-mesh])))))

(deftest unary-ops-reject-mesh
  (testing "shell/offset/displace check their node argument"
    (is (thrown? js/Error (sdf/sdf-shell a-mesh 2)))
    (is (thrown? js/Error (sdf/sdf-offset a-mesh 2)))
    (is (thrown? js/Error (sdf/sdf-displace a-mesh '(* 0.1 x))))))

(deftest morph-rejects-mesh
  (testing "sdf-morph checks both operands"
    (is (thrown? js/Error (sdf/sdf-morph a-mesh node 0.5)))
    (is (thrown? js/Error (sdf/sdf-morph node a-mesh 0.5)))))

;; ── Other non-node arguments ────────────────────────────────────

(deftest nil-operand-is-reported
  (testing "nil no longer slips through as a silent nil-valued branch"
    (is (thrown-with-msg? js/Error #"argument 2 is nil"
                          (sdf/sdf-blend node nil 3)))))

(deftest path-operand-is-reported-by-type
  (testing "a non-mesh typed map is described by its :type"
    (is (thrown-with-msg? js/Error #"argument 1 is a path"
                          (sdf/sdf-shell {:type :path :commands []} 2)))))

;; ── Numeric parameters are checked too ──────────────────────────

(deftest blend-k-must-be-a-number
  (testing "a nil or non-numeric k is rejected here, not by the Rust parser"
    (is (thrown-with-msg? js/Error #"sdf-blend: k must be a number, got nil"
                          (sdf/sdf-blend node other-node nil)))
    (is (thrown-with-msg? js/Error #"sdf-blend: k must be a number, got \"3\""
                          (sdf/sdf-blend node other-node "3")))
    (is (thrown-with-msg? js/Error #"sdf-blend-difference: k must be a number"
                          (sdf/sdf-blend-difference node other-node nil)))))

(deftest blend-k-must-be-finite
  (testing "NaN/Infinity would serialize as JSON null, so they are rejected too"
    (is (thrown-with-msg? js/Error #"sdf-blend: k must be a number"
                          (sdf/sdf-blend node other-node js/NaN)))
    (is (thrown-with-msg? js/Error #"sdf-blend-difference: k must be a number"
                          (sdf/sdf-blend-difference node other-node js/Infinity)))))

(deftest unary-op-numeric-params-are-checked
  (testing "shell/offset/morph reject a nil or non-numeric parameter"
    (is (thrown-with-msg? js/Error #"sdf-shell: thickness must be a number, got nil"
                          (sdf/sdf-shell node nil)))
    (is (thrown-with-msg? js/Error #"sdf-offset: amount must be a number, got \"2\""
                          (sdf/sdf-offset node "2")))
    (is (thrown-with-msg? js/Error #"sdf-morph: t must be a number"
                          (sdf/sdf-morph node other-node nil)))))

(deftest transform-numeric-params-are-checked
  (testing "move/rotate/scale name the offending component"
    (is (thrown-with-msg? js/Error #"sdf-move: dy must be a number, got nil"
                          (sdf/sdf-move node 1 nil 3)))
    (is (thrown-with-msg? js/Error #"sdf-rotate: angle must be a number"
                          (sdf/sdf-rotate node :z nil)))
    (is (thrown-with-msg? js/Error #"sdf-rotate: angle must be a number"
                          (sdf/sdf-rotate node [0 0 1] nil)))
    (is (thrown-with-msg? js/Error #"sdf-scale: sz must be a number"
                          (sdf/sdf-scale node 1 1 js/NaN)))
    (is (thrown-with-msg? js/Error #"sdf-scale: sx must be a number"
                          (sdf/sdf-scale node nil)))))

(deftest primitive-numeric-params-are-checked
  (testing "constructors reject nil/non-numeric dimensions on the spot"
    (is (thrown-with-msg? js/Error #"sdf-sphere: r must be a number, got nil"
                          (sdf/sdf-sphere nil)))
    (is (thrown-with-msg? js/Error #"sdf-box: b must be a number, got \"2\""
                          (sdf/sdf-box 1 "2" 3)))
    (is (thrown-with-msg? js/Error #"sdf-box: a must be a number"
                          (sdf/sdf-box nil)))
    (is (thrown-with-msg? js/Error #"sdf-cyl: h must be a number"
                          (sdf/sdf-cyl 5 nil)))
    (is (thrown-with-msg? js/Error #"sdf-cone: r2 must be a number"
                          (sdf/sdf-cone 10 nil 5)))
    (is (thrown-with-msg? js/Error #"sdf-rounded-box: r must be a number"
                          (sdf/sdf-rounded-box 1 2 3 nil)))
    (is (thrown-with-msg? js/Error #"sdf-torus: r must be a number"
                          (sdf/sdf-torus 10 js/NaN)))
    (is (thrown-with-msg? js/Error #"sdf-gyroid: period must be a number"
                          (sdf/sdf-gyroid nil 1)))
    (is (thrown-with-msg? js/Error #"sdf-schwarz-p: thickness must be a number"
                          (sdf/sdf-schwarz-p 10 nil)))
    (is (thrown-with-msg? js/Error #"sdf-diamond: period must be a number"
                          (sdf/sdf-diamond js/Infinity 1)))))

;; ── Non-regression: valid SDF trees still build ─────────────────

(deftest valid-nodes-build-normally
  (testing "well-formed SDF operands are untouched by the check"
    (is (= "blend" (:op (sdf/sdf-blend node other-node 3))))
    (is (= "blend-difference" (:op (sdf/sdf-blend-difference node other-node 3))))
    (is (= "union" (:op (sdf/sdf-union node other-node))))
    (is (= "difference" (:op (sdf/sdf-difference node other-node))))
    (is (= "intersection" (:op (sdf/sdf-intersection node other-node))))
    (is (= "shell" (:op (sdf/sdf-shell node 2))))
    (is (= "offset" (:op (sdf/sdf-offset node 2))))
    (is (= "morph" (:op (sdf/sdf-morph node other-node 0.5))))
    (is (= "sphere" (:op (sdf/sdf-sphere 5))))
    (is (= "box" (:op (sdf/sdf-box 1 2 3))))
    (is (= "cyl" (:op (sdf/sdf-cyl 5 10))))
    (is (= "move" (:op (sdf/sdf-move node 1 2 3))))
    (is (= "rotate" (:op (sdf/sdf-rotate node :z 45))))
    (is (= "scale" (:op (sdf/sdf-scale node 2))))))

(deftest variadic-forms-still-work
  (testing "vector form, n-ary form, and the 1-node and empty degenerate cases"
    (is (= "union" (:op (sdf/sdf-union [node other-node node]))))
    (is (= "union" (:op (sdf/sdf-union node other-node node))))
    (is (= node (sdf/sdf-union node)))
    (is (nil? (sdf/sdf-union [])))))
