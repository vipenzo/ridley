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

;; ── Non-regression: valid SDF trees still build ─────────────────

(deftest valid-nodes-build-normally
  (testing "well-formed SDF operands are untouched by the check"
    (is (= "blend" (:op (sdf/sdf-blend node other-node 3))))
    (is (= "union" (:op (sdf/sdf-union node other-node))))
    (is (= "difference" (:op (sdf/sdf-difference node other-node))))
    (is (= "intersection" (:op (sdf/sdf-intersection node other-node))))
    (is (= "shell" (:op (sdf/sdf-shell node 2))))
    (is (= "offset" (:op (sdf/sdf-offset node 2))))
    (is (= "morph" (:op (sdf/sdf-morph node other-node 0.5))))))

(deftest variadic-forms-still-work
  (testing "vector form, n-ary form, and the 1-node and empty degenerate cases"
    (is (= "union" (:op (sdf/sdf-union [node other-node node]))))
    (is (= "union" (:op (sdf/sdf-union node other-node node))))
    (is (= node (sdf/sdf-union node)))
    (is (nil? (sdf/sdf-union [])))))
