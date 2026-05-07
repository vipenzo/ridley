(ns ridley.editor.transforms-equivalence-test
  "Equivalence tests for the polymorphic translate / scale / rotate API
   and for attach: verify that mesh and SDF land at consistent anchor
   positions and creation-poses for the same conceptual operation.

   Pivot conventions differ by type (mesh = centroid, SDF = world origin),
   so these tests deliberately use centered-at-origin geometry where
   centroid = origin and the comparisons line up."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.sdf.core :as sdf]
            [ridley.editor.transforms :as t]
            [ridley.editor.impl :as impl]
            [ridley.turtle.core :as turtle]
            [ridley.geometry.primitives :as prims]))

(defn- approx=
  ([a b] (approx= a b 1e-6))
  ([a b tol] (< (Math/abs (- a b)) tol)))

(defn- v-approx=
  ([a b] (v-approx= a b 1e-6))
  ([a b tol]
   (and (= (count a) (count b))
        (every? true? (map #(approx= %1 %2 tol) a b)))))

;; ── Test fixtures ──────────────────────────────────────────────

(def ^:private sample-anchor
  {:position [3 4 5] :heading [1 0 0] :up [0 0 1]})

(def ^:private identity-pose
  {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})

(defn- mesh-with-anchor
  "10×10×10 box centered at origin (centroid = origin) with one named anchor."
  []
  (-> (prims/box-mesh 10)
      (assoc :creation-pose identity-pose)
      (assoc :anchors {:foo sample-anchor})))

(defn- sdf-with-anchor
  []
  (-> (sdf/sdf-box 10 10 10)
      (assoc :creation-pose identity-pose)
      (assoc :anchors {:foo sample-anchor})))

(defn- anchor-pos [thing] (get-in thing [:anchors :foo :position]))
(defn- anchor-heading [thing] (get-in thing [:anchors :foo :heading]))
(defn- anchor-up [thing] (get-in thing [:anchors :foo :up]))
(defn- cp-pos [thing] (get-in thing [:creation-pose :position]))

;; ── translate ──────────────────────────────────────────────────

(deftest translate-anchor-position
  (testing "translate by (5, 6, 7) shifts anchor positions identically on mesh and SDF"
    (let [m  (t/translate (mesh-with-anchor) 5 6 7)
          s  (t/translate (sdf-with-anchor) 5 6 7)
          expected [8 10 12]]
      (is (v-approx= expected (anchor-pos m)))
      (is (v-approx= expected (anchor-pos s))))))

(deftest translate-creation-pose
  (testing "translate shifts creation-pose by the same delta on mesh and SDF"
    (let [m  (t/translate (mesh-with-anchor) 5 6 7)
          s  (t/translate (sdf-with-anchor) 5 6 7)]
      (is (v-approx= [5 6 7] (cp-pos m)))
      (is (v-approx= [5 6 7] (cp-pos s))))))

(deftest translate-leaves-orientation
  (testing "translate doesn't change anchor heading/up"
    (let [m  (t/translate (mesh-with-anchor) 5 6 7)
          s  (t/translate (sdf-with-anchor) 5 6 7)]
      (is (v-approx= [1 0 0] (anchor-heading m)))
      (is (v-approx= [1 0 0] (anchor-heading s)))
      (is (v-approx= [0 0 1] (anchor-up m)))
      (is (v-approx= [0 0 1] (anchor-up s))))))

;; ── rotate ─────────────────────────────────────────────────────

(deftest rotate-z-anchor-position
  (testing "rotate :z 90: anchor at (3,4,5) → (-4,3,5) on both mesh and SDF (centered geometry)"
    (let [m (t/rotate (mesh-with-anchor) :z 90)
          s (t/rotate (sdf-with-anchor) :z 90)
          expected [-4 3 5]]
      (is (v-approx= expected (anchor-pos m)))
      (is (v-approx= expected (anchor-pos s))))))

(deftest rotate-y-anchor-position
  (testing "rotate :y 90: anchor at (3,4,5) → (5,4,-3) on both, accounting for libfive's left-hand y convention"
    ;; libfive's rotate_y(+90) takes +X to +Z (left-hand around +Y, equivalently
    ;; right-hand around -Y). Our polymorphic rotate exposes the same convention
    ;; for both mesh and SDF.
    (let [m (t/rotate (mesh-with-anchor) :y 90)
          s (t/rotate (sdf-with-anchor) :y 90)
          expected [5 4 -3]]
      (is (v-approx= expected (anchor-pos m)) (str "mesh got " (anchor-pos m)))
      (is (v-approx= expected (anchor-pos s)) (str "sdf got " (anchor-pos s))))))

(deftest rotate-x-anchor-heading
  (testing "rotate :x 90: anchor heading (1,0,0) unchanged (perpendicular to rotation), up (0,0,1) → (0,-1,0)"
    (let [m (t/rotate (mesh-with-anchor) :x 90)
          s (t/rotate (sdf-with-anchor) :x 90)]
      (is (v-approx= [1 0 0] (anchor-heading m)))
      (is (v-approx= [1 0 0] (anchor-heading s)))
      (is (v-approx= [0 -1 0] (anchor-up m)))
      (is (v-approx= [0 -1 0] (anchor-up s))))))

(deftest rotate-arbitrary-axis-cycles
  (testing "rotate around (1,1,1) by 120° permutes axes cyclically"
    ;; Rodrigues rotation around (1,1,1)/√3 by 120° cycles (x,y,z) → (y,z,x).
    ;; So an anchor at +X goes to +Y, +Y to +Z, +Z to +X.
    (let [setup (fn [pos] {:position pos :heading [1 0 0] :up [0 0 1]})
          m (-> (prims/box-mesh 10)
                (assoc :anchors {:foo (setup [1 0 0])}))
          s (-> (sdf/sdf-box 10 10 10)
                (assoc :anchors {:foo (setup [1 0 0])}))
          m' (t/rotate m [1 1 1] 120)
          s' (t/rotate s [1 1 1] 120)
          expected [0 1 0]]
      (is (v-approx= expected (anchor-pos m') 1e-5)
          (str "mesh: expected (0,1,0), got " (anchor-pos m')))
      (is (v-approx= expected (anchor-pos s') 1e-5)
          (str "sdf: expected (0,1,0), got " (anchor-pos s'))))))

;; ── scale ──────────────────────────────────────────────────────

(deftest scale-uniform-anchor-position
  (testing "scale 2 doubles anchor distance from centroid on mesh, from origin on SDF (= same when centered)"
    (let [m (t/scale (mesh-with-anchor) 2)
          s (t/scale (sdf-with-anchor) 2)
          expected [6 8 10]]
      (is (v-approx= expected (anchor-pos m)))
      (is (v-approx= expected (anchor-pos s))))))

(deftest scale-non-uniform-anchor-position
  (testing "scale 2 1 0.5: anchor scaled per-axis (mesh on local axes, SDF on world axes — coincident at identity creation-pose)"
    (let [m (t/scale (mesh-with-anchor) 2 1 0.5)
          s (t/scale (sdf-with-anchor) 2 1 0.5)
          expected [6 4 2.5]]
      (is (v-approx= expected (anchor-pos m)))
      (is (v-approx= expected (anchor-pos s))))))

(deftest scale-leaves-anchor-orientation
  (testing "scaling doesn't rotate anchor heading/up"
    (let [m (t/scale (mesh-with-anchor) 2)
          s (t/scale (sdf-with-anchor) 2)]
      (is (v-approx= [1 0 0] (anchor-heading m)))
      (is (v-approx= [1 0 0] (anchor-heading s)))
      (is (v-approx= [0 0 1] (anchor-up m)))
      (is (v-approx= [0 0 1] (anchor-up s))))))

;; ── attach ─────────────────────────────────────────────────────

(defn- p [& cmd-pairs]
  (turtle/make-path (mapv (fn [[c & args]] {:cmd c :args (vec args)}) cmd-pairs)))

(deftest attach-translate-via-f
  (testing "(attach _ (f 10)) translates anchors by 10 along +X on both mesh and SDF"
    (let [pth (p [:f 10])
          m (impl/attach-impl (mesh-with-anchor) pth)
          s (impl/attach-impl (sdf-with-anchor) pth)
          expected [13 4 5]]
      (is (v-approx= expected (anchor-pos m)))
      (is (v-approx= expected (anchor-pos s))))))

(deftest attach-rotation-then-translation
  (testing "(attach _ (tv 90) (f 10)): heading goes +X→+Z, then forward by 10 lands at +Z*10"
    (let [pth (p [:tv 90] [:f 10])
          ;; Anchor at origin keeps things simple — only creation-pose matters.
          mk-mesh #(-> (prims/box-mesh 4) (assoc :creation-pose identity-pose))
          mk-sdf  #(-> (sdf/sdf-box 4 4 4) (assoc :creation-pose identity-pose))
          m (impl/attach-impl (mk-mesh) pth)
          s (impl/attach-impl (mk-sdf) pth)]
      (is (v-approx= [0 0 10] (cp-pos m)))
      (is (v-approx= [0 0 10] (cp-pos s)))
      ;; After tv 90, heading should be +Z.
      (is (v-approx= [0 0 1] (get-in m [:creation-pose :heading])))
      (is (v-approx= [0 0 1] (get-in s [:creation-pose :heading]))))))

(deftest attach-cp-f-leaves-creation-pose-fixed
  (testing "(attach _ (cp-f 5)): vertices/anchors slide by -5*heading, creation-pose stays at origin"
    (let [pth (p [:cp-f 5])
          m (impl/attach-impl (mesh-with-anchor) pth)
          s (impl/attach-impl (sdf-with-anchor) pth)]
      ;; anchor at (3,4,5) − (5,0,0) = (-2,4,5)
      (is (v-approx= [-2 4 5] (anchor-pos m)))
      (is (v-approx= [-2 4 5] (anchor-pos s)))
      ;; creation-pose stays at origin
      (is (v-approx= [0 0 0] (cp-pos m)))
      (is (v-approx= [0 0 0] (cp-pos s))))))

(deftest attach-mark-records-anchor
  (testing "(attach _ (f 10) (mark :tip)): :tip anchor recorded at (10,0,0) on both"
    (let [pth (p [:f 10] [:mark :tip])
          mk-mesh #(-> (prims/box-mesh 4) (assoc :creation-pose identity-pose))
          mk-sdf  #(-> (sdf/sdf-box 4 4 4) (assoc :creation-pose identity-pose))
          m (impl/attach-impl (mk-mesh) pth)
          s (impl/attach-impl (mk-sdf) pth)]
      (is (v-approx= [10 0 0] (get-in m [:anchors :tip :position])))
      (is (v-approx= [10 0 0] (get-in s [:anchors :tip :position]))))))

(deftest attach-move-to-align-on-external-target
  (testing "(attach _ (move-to other :at :tip :align)) lands at the target's anchor pose for both mesh and SDF"
    (let [;; External target with :tip anchor at (5, 5, 0) facing +Y, up +Z.
          target {:type :mesh
                  :vertices [] :faces []
                  :anchors {:tip {:position [5 5 0] :heading [0 1 0] :up [0 0 1]}}}
          pth (p [:move-to target :at :tip :align])
          mk-mesh #(-> (prims/box-mesh 4) (assoc :creation-pose identity-pose))
          mk-sdf  #(-> (sdf/sdf-box 4 4 4) (assoc :creation-pose identity-pose))
          m (impl/attach-impl (mk-mesh) pth)
          s (impl/attach-impl (mk-sdf) pth)]
      ;; Both: creation-pose now at (5,5,0) with heading +Y, up +Z.
      (is (v-approx= [5 5 0] (cp-pos m)))
      (is (v-approx= [5 5 0] (cp-pos s)))
      (is (v-approx= [0 1 0] (get-in m [:creation-pose :heading])))
      (is (v-approx= [0 1 0] (get-in s [:creation-pose :heading]))))))

;; ── SDF-only invariants ────────────────────────────────────────

(deftest sdf-union-merges-anchors-first-wins
  (testing "(sdf-union a b) merges anchors with first-arg winning on collision"
    (let [a (-> (sdf/sdf-sphere 5)
                (assoc :anchors {:shared {:position [1 0 0] :heading [1 0 0] :up [0 0 1]}
                                 :only-a {:position [2 0 0] :heading [1 0 0] :up [0 0 1]}}))
          b (-> (sdf/sdf-sphere 3)
                (assoc :anchors {:shared {:position [99 99 99] :heading [1 0 0] :up [0 0 1]}
                                 :only-b {:position [3 0 0] :heading [1 0 0] :up [0 0 1]}}))
          u (sdf/sdf-union a b)]
      (is (v-approx= [1 0 0] (get-in u [:anchors :shared :position])) "first arg wins on :shared")
      (is (v-approx= [2 0 0] (get-in u [:anchors :only-a :position])))
      (is (v-approx= [3 0 0] (get-in u [:anchors :only-b :position]))))))

(deftest sdf-difference-keeps-minuend-anchors
  (testing "(sdf-difference a b) keeps only the minuend's anchors"
    (let [a (-> (sdf/sdf-box 10 10 10)
                (assoc :anchors {:keep {:position [1 1 1] :heading [1 0 0] :up [0 0 1]}}))
          b (-> (sdf/sdf-sphere 3)
                (assoc :anchors {:drop {:position [9 9 9] :heading [1 0 0] :up [0 0 1]}}))
          d (sdf/sdf-difference a b)]
      (is (some? (get-in d [:anchors :keep])))
      (is (nil? (get-in d [:anchors :drop]))))))
