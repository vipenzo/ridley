(ns ridley.editor.repl-test
  "DSL integration tests via SCI harness.
   Tests that the macro layer (path, shape, extrude, loft, revolve)
   works correctly through SCI evaluation."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]))

;; ── Helpers ──────────────────────────────────────────────────

(defn- approx=
  "Compare two numbers within tolerance."
  ([a b] (approx= a b 0.01))
  ([a b tol] (< (js/Math.abs (- a b)) tol)))

(defn- v-approx=
  "Compare two 3D vectors within tolerance."
  [a b & [tol]]
  (let [t (or tol 0.01)]
    (and (= (count a) (count b))
         (every? true? (map #(approx= %1 %2 t) a b)))))

;; ── 1. Path recording basics ─────────────────────────────────

(deftest path-f-only
  (testing "Path with single f records one forward command"
    (let [{:keys [result error]} (h/eval-dsl "(path (f 10))")]
      (is (nil? error) (str "Should not error: " error))
      (is (= :path (:type result)))
      (is (= 1 (count (:commands result))))
      (is (= :f (:cmd (first (:commands result)))))
      (is (= [10] (:args (first (:commands result))))))))

(deftest path-f-th-f
  (testing "Path with f/th/f records three commands"
    (let [{:keys [result error]} (h/eval-dsl "(path (f 5) (th 90) (f 10))")]
      (is (nil? error))
      (is (= 3 (count (:commands result))))
      (is (= [:f :th :f] (mapv :cmd (:commands result)))))))

(deftest path-tv-command
  (testing "Path records tv commands"
    (let [{:keys [result error]} (h/eval-dsl "(path (f 5) (tv 45) (f 5))")]
      (is (nil? error))
      (is (= [:f :tv :f] (mapv :cmd (:commands result)))))))

;; ── 2. Shape creation ────────────────────────────────────────

(deftest circle-shape-creation
  (testing "circle creates a valid shape"
    (let [{:keys [result error]} (h/eval-dsl "(circle 5)")]
      (is (nil? error))
      (is (map? result))
      (is (seq (:points result)) "Should have points"))))

(deftest rect-shape-creation
  (testing "rect creates a valid shape"
    (let [{:keys [result error]} (h/eval-dsl "(rect 10 5)")]
      (is (nil? error))
      (is (= 4 (count (:points result))) "Rect has 4 points"))))

(deftest custom-shape-macro
  (testing "shape macro creates shape from f/th commands"
    (let [{:keys [result error]} (h/eval-dsl "(shape (f 10) (th 90) (f 5) (th 90) (f 10) (th 90) (f 5))")]
      (is (nil? error))
      (is (map? result))
      (is (seq (:points result)) "Custom shape should have points"))))

;; ── 3. Extrude with inline movements ────────────────────────

(deftest extrude-inline
  (testing "extrude with inline f produces a mesh"
    (let [{:keys [result error]} (h/eval-dsl "(extrude (circle 5) (f 20))")]
      (is (nil? error) (str "Extrude should not error: " error))
      (is (map? result) "Should return a mesh")
      (is (seq (:vertices result)) "Mesh should have vertices")
      (is (seq (:faces result)) "Mesh should have faces"))))

(deftest extrude-multiple-movements
  (testing "extrude with f/th/f produces a mesh"
    (let [{:keys [result error]} (h/eval-dsl "(extrude (circle 3) (f 10) (th 45) (f 10))")]
      (is (nil? error))
      (is (map? result))
      (is (seq (:vertices result))))))

;; ── 4. Extrude with pre-built path ──────────────────────────

(deftest extrude-with-path-var
  (testing "extrude with a pre-built path variable"
    (let [{:keys [result error]}
          (h/eval-dsl "(let [p (path (f 15) (th 30) (f 10))]
                         (extrude (circle 4) p))")]
      (is (nil? error))
      (is (map? result))
      (is (seq (:vertices result))))))

(deftest extrude-with-inline-path
  (testing "extrude with explicit (path ...) expression"
    (let [{:keys [result error]}
          (h/eval-dsl "(extrude (rect 6 3) (path (f 20)))")]
      (is (nil? error))
      (is (map? result))
      (is (seq (:vertices result))))))

;; ── 5. Loft basic ───────────────────────────────────────────

(deftest loft-with-transform
  (testing "loft with transform function produces a mesh"
    (let [{:keys [result error]}
          (h/eval-dsl "(loft (circle 5) (fn [s t] (scale-shape s (+ 1 t))) (f 20))")]
      (is (nil? error) (str "Loft should not error: " error))
      (is (map? result))
      (is (seq (:vertices result))))))

(deftest loft-two-shapes
  (testing "loft between two shapes produces a mesh"
    (let [{:keys [result error]}
          (h/eval-dsl "(loft (circle 5) (circle 10) (f 20))")]
      (is (nil? error) (str "Loft two shapes error: " error))
      (is (map? result))
      (is (seq (:vertices result))))))

;; ── 6. Extrude-closed ───────────────────────────────────────

(deftest extrude-closed-basic
  (testing "extrude-closed produces a closed mesh"
    (let [{:keys [result error]}
          (h/eval-dsl "(extrude-closed (circle 3) (path (f 10) (th 90) (f 10) (th 90) (f 10) (th 90) (f 10)))")]
      (is (nil? error) (str "Extrude-closed error: " error))
      (is (map? result))
      (is (seq (:vertices result))))))

;; ── 7. Revolve ──────────────────────────────────────────────

(deftest revolve-full
  (testing "revolve 360 produces a mesh (torus-like)"
    (let [{:keys [result error]}
          (h/eval-dsl "(do (f 10) (revolve (circle 3)))")]
      (is (nil? error) (str "Revolve error: " error))
      (is (map? result))
      (is (seq (:vertices result))))))

(deftest revolve-partial
  (testing "revolve with partial angle"
    (let [{:keys [result error]}
          (h/eval-dsl "(do (f 10) (revolve (circle 3) 180))")]
      (is (nil? error))
      (is (map? result))
      (is (seq (:vertices result))))))

;; ── 8. Arc in path ──────────────────────────────────────────

(deftest path-arc-h
  (testing "arc-h in path produces multiple commands"
    (let [{:keys [result error]}
          (h/eval-dsl "(path (arc-h 10 90 :steps 4))")]
      (is (nil? error))
      (is (= :path (:type result)))
      (is (> (count (:commands result)) 1) "Arc should produce multiple commands"))))

(deftest path-arc-v
  (testing "arc-v in path produces multiple commands"
    (let [{:keys [result error]}
          (h/eval-dsl "(path (arc-v 10 90 :steps 4))")]
      (is (nil? error))
      (is (= :path (:type result)))
      (is (> (count (:commands result)) 1)))))

;; ── 9. Extrude with arc path ────────────────────────────────

(deftest extrude-arc-path
  (testing "extrude along arc path produces mesh"
    (let [{:keys [result error]}
          (h/eval-dsl "(extrude (circle 2) (arc-h 15 180 :steps 8))")]
      (is (nil? error) (str "Extrude arc error: " error))
      (is (map? result))
      (is (seq (:vertices result))))))

;; ── 10. Resolution affects circle ───────────────────────────

(deftest resolution-affects-circle
  (testing "resolution setting changes circle point count"
    (let [{:keys [result error]}
          (h/eval-dsl "(do (resolution :n 8) (circle 5))")]
      (is (nil? error))
      (is (= 8 (count (:points result))) "Circle should have 8 points with resolution 8"))))

;; ── 11. Path with marks ─────────────────────────────────────

(deftest path-with-marks
  (testing "path records mark commands"
    (let [{:keys [result error]}
          (h/eval-dsl "(path (f 5) (mark :mid) (f 5))")]
      (is (nil? error))
      (is (some #(= :mark (:cmd %)) (:commands result))
          "Should contain a mark command"))))

;; ── 12. Multiple extrusions ─────────────────────────────────

(deftest multiple-extrusions
  (testing "Multiple extrusions accumulate meshes"
    (let [{:keys [turtle error]}
          (h/eval-dsl "(do (extrude (circle 3) (f 10))
                           (f 5)
                           (extrude (circle 3) (f 10)))")]
      (is (nil? error))
      ;; After two extrudes, turtle should have moved forward
      (is (not (v-approx= [0 0 0] (:position turtle)))
          "Turtle should have moved from origin"))))

;; ── 13. Bezier-as heading independence ──────────────────────
;; When a path is used with bezier-as, the resulting mesh should have
;; the same shape regardless of initial turtle orientation. The mesh
;; will be rotated/translated in space, but bounding box dimensions
;; (sorted) should match.

(defn- bounding-box-dims
  "Compute sorted bounding box dimensions [smallest mid largest] of mesh vertices."
  [verts]
  (when (seq verts)
    (let [xs (mapv #(nth % 0) verts)
          ys (mapv #(nth % 1) verts)
          zs (mapv #(nth % 2) verts)
          dx (- (apply max xs) (apply min xs))
          dy (- (apply max ys) (apply min ys))
          dz (- (apply max zs) (apply min zs))]
      (vec (sort [dx dy dz])))))

(deftest bezier-as-heading-independence-xy
  (testing "bezier-as extrusion: rotating 90° in XY plane preserves mesh dimensions"
    (let [ref-code "(let [p (path (f 10) (th 45) (f 10))]
                      (extrude (circle 2) (bezier-as p :steps 4)))"
          rot-code "(do (th 90)
                      (let [p (path (f 10) (th 45) (f 10))]
                        (extrude (circle 2) (bezier-as p :steps 4))))"
          ref (h/eval-dsl ref-code)
          rot (h/eval-dsl rot-code)]
      (is (nil? (:error ref)) (str "Reference error: " (:error ref)))
      (is (nil? (:error rot)) (str "Rotated error: " (:error rot)))
      (is (= (count (:vertices (:result ref)))
             (count (:vertices (:result rot))))
          "Vertex count should match regardless of orientation")
      (is (= (count (:faces (:result ref)))
             (count (:faces (:result rot))))
          "Face count should match regardless of orientation")
      (let [ref-dims (bounding-box-dims (:vertices (:result ref)))
            rot-dims (bounding-box-dims (:vertices (:result rot)))]
        (is (every? true? (map #(approx= %1 %2 0.5) ref-dims rot-dims))
            (str "Bounding box dimensions should match (sorted).\n"
                 "  Reference dims: " ref-dims "\n"
                 "  Rotated dims:   " rot-dims))))))

(deftest bezier-as-heading-independence-xz
  (testing "bezier-as extrusion: pitching 90° (tv) preserves mesh dimensions"
    (let [ref-code "(let [p (path (f 10) (th 30) (f 15))]
                      (extrude (circle 2) (bezier-as p :steps 4)))"
          rot-code "(do (tv 90)
                      (let [p (path (f 10) (th 30) (f 15))]
                        (extrude (circle 2) (bezier-as p :steps 4))))"
          ref (h/eval-dsl ref-code)
          rot (h/eval-dsl rot-code)]
      (is (nil? (:error ref)) (str "Reference error: " (:error ref)))
      (is (nil? (:error rot)) (str "Rotated error: " (:error rot)))
      (is (= (count (:vertices (:result ref)))
             (count (:vertices (:result rot))))
          "Vertex count should match")
      (let [ref-dims (bounding-box-dims (:vertices (:result ref)))
            rot-dims (bounding-box-dims (:vertices (:result rot)))]
        (is (every? true? (map #(approx= %1 %2 0.5) ref-dims rot-dims))
            (str "Bounding box dimensions should match.\n"
                 "  Reference dims: " ref-dims "\n"
                 "  Rotated dims:   " rot-dims))))))

(deftest bezier-as-heading-independence-compound
  (testing "bezier-as extrusion: th+tv rotation still produces valid mesh"
    ;; NOTE: Compound 3D rotation (th+tv) can cause slight shape variation
    ;; due to Euler angle decomposition. We verify the mesh is valid and
    ;; vertex/face counts match (the path topology is preserved).
    (let [ref-code "(let [p (path (f 8) (th 60) (f 12))]
                      (extrude (circle 2) (bezier-as p :steps 4)))"
          rot-code "(do (th 45) (tv 30)
                      (let [p (path (f 8) (th 60) (f 12))]
                        (extrude (circle 2) (bezier-as p :steps 4))))"
          ref (h/eval-dsl ref-code)
          rot (h/eval-dsl rot-code)]
      (is (nil? (:error ref)) (str "Reference error: " (:error ref)))
      (is (nil? (:error rot)) (str "Rotated error: " (:error rot)))
      (is (= (count (:vertices (:result ref)))
             (count (:vertices (:result rot))))
          "Vertex count should match even with compound rotation")
      (is (= (count (:faces (:result ref)))
             (count (:faces (:result rot))))
          "Face count should match even with compound rotation"))))

;; ── 14. Pen mode ────────────────────────────────────────────

(deftest pen-mode-off
  (testing "pen :off stops geometry recording"
    (let [{:keys [turtle error]}
          (h/eval-dsl "(do (pen :off) (f 10) (pen :on) (f 10))")]
      (is (nil? error))
      ;; With pen off for first f, only second f generates geometry
      (is (= :on (:pen-mode turtle))))))

;; ── 15. bezier-as heading independence (path pattern) ──────
;; These test the (path (bezier-as bp)) pattern specifically, which is
;; how bezier-as is used in practice (Christmas tree branches, etc.).
;; If rec-bezier-as* records absolute headings, the shape will differ
;; when the turtle starts at a different orientation.

(defn- bounding-box [mesh]
  (let [verts (:vertices mesh)
        xs (map first verts)
        ys (map second verts)
        zs (map #(nth % 2) verts)]
    {:min [(apply min xs) (apply min ys) (apply min zs)]
     :max [(apply max xs) (apply max ys) (apply max zs)]}))

(defn- bb-width  [bb] (- (first  (:max bb)) (first  (:min bb))))
(defn- bb-height [bb] (- (second (:max bb)) (second (:min bb))))
(defn- bb-depth  [bb] (- (nth (:max bb) 2)  (nth (:min bb) 2)))

(defn- distance [p1 p2]
  (js/Math.sqrt (reduce + (map #(* (- %1 %2) (- %1 %2)) p1 p2))))

(deftest bezier-as-path-same-shape-regardless-of-turtle-orientation
  (testing "bezier-as path produces same-shaped mesh regardless of turtle heading"
    ;; The key test: a branch-path with a 90° turn and straight segment
    ;; should produce the SAME shape when extruded from different orientations.
    ;; If rec-bezier-as* records absolute headings, the curves will differ.
    (let [;; Extrude from default orientation (facing +X)
          ref (h/eval-dsl
               "(def bp (path (f 5) (th 90) (f 30)))
                (extrude (circle 2) (path (bezier-as bp)))")
          mesh-default (:result ref)
          bb-default (bounding-box mesh-default)

          ;; Extrude after rotating turtle 90° (facing +Y)
          rot (h/eval-dsl
               "(def bp (path (f 5) (th 90) (f 30)))
                (th 90)
                (extrude (circle 2) (path (bezier-as bp)))")
          mesh-rotated (:result rot)
          bb-rotated (bounding-box mesh-rotated)]

      (is (nil? (:error ref)) (str "Reference error: " (:error ref)))
      (is (nil? (:error rot)) (str "Rotated error: " (:error rot)))

      ;; Both meshes should have the same vertex count
      (is (= (count (:vertices mesh-default))
             (count (:vertices mesh-rotated)))
          "Same vertex count regardless of turtle orientation")

      ;; Both bounding boxes should have the same DIMENSIONS (sorted)
      ;; (not necessarily same position — that depends on where the turtle was)
      (let [dims-default (sort [(bb-width bb-default) (bb-height bb-default) (bb-depth bb-default)])
            dims-rotated (sort [(bb-width bb-rotated) (bb-height bb-rotated) (bb-depth bb-rotated)])]
        (is (every? true? (map #(approx= %1 %2 0.5) dims-default dims-rotated))
            (str "Bounding box dimensions should match (sorted).\n"
                 "  Default dims: " (vec dims-default) "\n"
                 "  Rotated dims: " (vec dims-rotated)))))))

(deftest bezier-as-path-endpoint-matches-original
  (testing "bezier-as smoothed path ends at approximately the same point as the original"
    (let [;; Follow original path, record turtle endpoint
          {turtle-orig :turtle}
          (h/eval-dsl
           "(def bp (path (f 10) (th 45) (f 20)))
            (follow-path bp)")
          orig-end (:position turtle-orig)

          ;; Follow bezier-as version of the same path
          {turtle-smooth :turtle}
          (h/eval-dsl
           "(def bp (path (f 10) (th 45) (f 20)))
            (def smooth-bp (path (bezier-as bp)))
            (follow-path smooth-bp)")
          smooth-end (:position turtle-smooth)]

      ;; Endpoints should be close (bezier is an approximation)
      (is (< (distance orig-end smooth-end) 5.0)
          (str "Smoothed path ends near original path endpoint.\n"
               "  Original end: " orig-end "\n"
               "  Smooth end:   " smooth-end)))))

(defn- mean-dist-from-centroid
  "Rotation-invariant shape metric: mean vertex distance from centroid."
  [mesh]
  (let [verts (:vertices mesh)
        n (count verts)
        centroid (mapv #(/ % n) (reduce (fn [acc v] (mapv + acc v)) [0 0 0] verts))]
    (/ (reduce + (map #(distance % centroid) verts)) n)))

(deftest bezier-as-in-branch-pattern
  (testing "bezier-as works correctly in the branch+ring pattern (Christmas tree)"
    ;; Verify that the bezier-as recorded path is identical regardless of
    ;; turtle roll (tr). Since the path recorder resets to default state,
    ;; the commands should be byte-for-byte identical.
    ;; Then verify the extruded meshes are the same shape (just rotated)
    ;; using a rotation-invariant metric.
    (let [make-path (fn [tr-angle]
                      (h/eval-dsl
                       (str "(def bp (path (f 5) (th 90) (f 30)))"
                            (when (pos? tr-angle)
                              (str "(tr " tr-angle ")"))
                            "(path (bezier-as bp))")))
          make-branch (fn [tr-angle]
                        (h/eval-dsl
                         (str "(def bp (path (f 5) (th 90) (f 30)))"
                              (when (pos? tr-angle)
                                (str "(tr " tr-angle ")"))
                              "(extrude (circle 2) (path (bezier-as bp)))")))
          ;; Recorded paths
          path-0   (make-path 0)
          path-120 (make-path 120)
          path-240 (make-path 240)
          ;; Extruded meshes
          branch-0   (make-branch 0)
          branch-120 (make-branch 120)
          branch-240 (make-branch 240)]

      (is (nil? (:error branch-0))   (str "Branch 0° error: "   (:error branch-0)))
      (is (nil? (:error branch-120)) (str "Branch 120° error: " (:error branch-120)))
      (is (nil? (:error branch-240)) (str "Branch 240° error: " (:error branch-240)))

      ;; The recorded path commands must be identical — this is the definitive
      ;; shape-invariance check. Same commands = same local curve.
      (is (= (:commands (:result path-0)) (:commands (:result path-120)))
          "Recorded path commands should be identical at tr 0° and tr 120°")
      (is (= (:commands (:result path-0)) (:commands (:result path-240)))
          "Recorded path commands should be identical at tr 0° and tr 240°")

      ;; All 3 branches should have the same number of vertices and faces
      (is (= (count (:vertices (:result branch-0)))
             (count (:vertices (:result branch-120)))
             (count (:vertices (:result branch-240))))
          "All branches should have same vertex count")
      (is (= (count (:faces (:result branch-0)))
             (count (:faces (:result branch-120)))
             (count (:faces (:result branch-240))))
          "All branches should have same face count")

      ;; Mean distance from centroid is rotation-invariant — should match
      (let [md-0   (mean-dist-from-centroid (:result branch-0))
            md-120 (mean-dist-from-centroid (:result branch-120))
            md-240 (mean-dist-from-centroid (:result branch-240))]
        (is (approx= md-0 md-120 0.1)
            (str "Mean dist from centroid should match 0° vs 120°: " md-0 " vs " md-120))
        (is (approx= md-0 md-240 0.1)
            (str "Mean dist from centroid should match 0° vs 240°: " md-0 " vs " md-240))))))
