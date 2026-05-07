(ns ridley.editor.transforms-equivalence-test
  "Equivalence tests for the polymorphic translate / scale / rotate API
   and for attach: verify that mesh and SDF land at consistent anchor
   positions and creation-poses for the same conceptual operation.

   Pivot conventions: mesh pivots on its centroid, SDF on its creation-pose.
   The base fixtures use centered-at-origin geometry so centroid = origin =
   creation-pose, and mesh/SDF results line up directly. The off-origin
   tests exercise the case where pivot-on-creation-pose matters."
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

;; ── Default creation-pose ──────────────────────────────────────

(deftest sdf-primitives-have-default-creation-pose
  (testing "every public SDF constructor stamps a default creation-pose at world origin"
    (doseq [s [(sdf/sdf-sphere 5)
               (sdf/sdf-box 4 4 4)
               (sdf/sdf-cyl 3 8)
               (sdf/sdf-rounded-box 4 4 4 0.5)
               (sdf/sdf-torus 10 1)
               (sdf/sdf-formula '(- (sqrt (+ (* x x) (* y y) (* z z))) 5))]]
      (is (= sdf/default-creation-pose (:creation-pose s))
          (str "missing default creation-pose on " (:op s))))))

;; ── Off-origin SDF rotate/scale (pivot on creation-pose) ───────

(deftest sdf-rotate-off-origin-pivots-on-creation-pose
  (testing "rotating an off-origin SDF pivots on its creation-pose, not world origin"
    ;; An anchor at (15,0,5) on an SDF whose creation-pose is at (10,0,0).
    ;; Anchor offset from pivot = (5,0,5). Rotating :z 90 should send (5,0,5)
    ;; → (0,5,5) relative to pivot → (10,5,5) in world coords.
    (let [s (-> (sdf/sdf-sphere 1)
                (assoc :creation-pose {:position [10 0 0] :heading [1 0 0] :up [0 0 1]}
                       :anchors {:foo {:position [15 0 5] :heading [1 0 0] :up [0 0 1]}}))
          r (t/rotate s :z 90)]
      (is (v-approx= [10 5 5] (anchor-pos r))
          (str "expected (10,5,5), got " (anchor-pos r)))
      ;; Creation-pose position stays put; its heading rotates.
      (is (v-approx= [10 0 0] (cp-pos r)))
      (is (v-approx= [0 1 0] (get-in r [:creation-pose :heading]))))))

(deftest sdf-scale-off-origin-pivots-on-creation-pose
  (testing "scaling an off-origin SDF expands about its creation-pose, not world origin"
    ;; Anchor at (15,4,0), creation-pose at (10,0,0), scale 2 →
    ;; offset (5,4,0) doubles to (10,8,0) → world (20,8,0).
    (let [s (-> (sdf/sdf-sphere 1)
                (assoc :creation-pose {:position [10 0 0] :heading [1 0 0] :up [0 0 1]}
                       :anchors {:foo {:position [15 4 0] :heading [1 0 0] :up [0 0 1]}}))
          r (t/scale s 2)]
      (is (v-approx= [20 8 0] (anchor-pos r))
          (str "expected (20,8,0), got " (anchor-pos r)))
      ;; Creation-pose position stays put under in-place scale.
      (is (v-approx= [10 0 0] (cp-pos r))))))

(deftest sdf-rotate-off-origin-equals-manual-sandwich
  (testing "the new (rotate sdf …) matches the historical translate-rotate-translate workaround"
    (let [base  (sdf/sdf-box 6 6 6)
          dx 7  dy -3  dz 4
          ;; SDF placed at (dx,dy,dz) by translate (which advances its pose).
          placed (t/translate base dx dy dz)
          new-way (t/rotate placed :y 30)
          old-way (-> placed
                      (t/translate (- dx) (- dy) (- dz))  ; back to origin
                      ((fn [s] (sdf/sdf-rotate s :y 30))) ; rotate around origin (raw primitive)
                      (t/translate dx dy dz))             ; translate back
          ;; Probe: pick an anchor on the placed sdf and compare positions
          ;; under both pipelines via anchor propagation.
          anchored (assoc placed :anchors {:probe {:position [dx (+ dy 4) dz]
                                                   :heading [1 0 0] :up [0 0 1]}})
          new-probe (anchor-pos (t/rotate anchored :y 30))
          old-probe (anchor-pos (-> anchored
                                    (t/translate (- dx) (- dy) (- dz))
                                    ((fn [s] (sdf/sdf-rotate s :y 30)))
                                    (t/translate dx dy dz)))]
      ;; Creation-poses should land in the same place too.
      (is (v-approx= (cp-pos new-way) (cp-pos old-way)))
      (is (v-approx= new-probe old-probe)
          (str "new=" new-probe " old=" old-probe)))))

(deftest sdf-and-mesh-rotate-around-same-point-when-collocated
  (testing "after the same translate, mesh and SDF rotate around the same point"
    ;; Both fresh primitives have origin-centroid / origin-creation-pose.
    ;; After (translate _ 8 0 0), both pivots move to (8,0,0).
    ;; Rotating (rotate _ :z 90) should send anchors initially at (8+a,b,c)
    ;; to the same place on both.
    (let [m (-> (prims/box-mesh 4)
                (assoc :anchors {:foo {:position [12 4 0] :heading [1 0 0] :up [0 0 1]}}))
          s (-> (sdf/sdf-box 4 4 4)
                (assoc :anchors {:foo {:position [12 4 0] :heading [1 0 0] :up [0 0 1]}}))
          ;; Translate both by (8,0,0) — but the anchor stays where it is in world space.
          ;; Translation moves geometry+pose+anchors together.
          mt (t/translate m 8 0 0)
          st (t/translate s 8 0 0)
          ;; After translate, anchors are at (20,4,0). Pivots at (8,0,0).
          ;; Anchor offset from pivot: (12,4,0). After :z 90 → (-4,12,0) +
          ;; pivot (8,0,0) = (4,12,0).
          mr (t/rotate mt :z 90)
          sr (t/rotate st :z 90)]
      (is (v-approx= (anchor-pos mr) (anchor-pos sr) 1e-5)
          (str "mesh=" (anchor-pos mr) " sdf=" (anchor-pos sr))))))
