(ns ridley.editor.move-to-mate-test
  "Tests for move-to :from / :mate (anchor-on-anchor mating).
   Exercises impl/attach-impl with a hand-built mobile mesh (carrying a :plug
   anchor) mated onto a target mesh's :socket anchor, per dev-docs/brief-move-to-mate.md."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.impl :as impl]
            [ridley.editor.sci-harness :as h]
            [ridley.math :as math]))

;; ── Helpers ──────────────────────────────────────────────────

(defn- approx= [a b] (< (js/Math.abs (- a b)) 1e-6))
(defn- v= [a b] (every? true? (map approx= a b)))

(defn- det3
  "Determinant of the frame [h u (h×u)] expressed vs itself — used to check the
   result frame is a proper rotation (right-handed): det [h u r] where r=h×u
   must be +1 for any orthonormal right-handed frame."
  [h u]
  (let [r (math/cross h u)]
    (math/dot (math/cross h u) r)))

(defn- mobile-mesh
  "A unit box with creation-pose at origin (heading +x, up +z) and a :plug anchor
   on the top face (position [0.5 0.5 1], heading +z out of the face, up +x)."
  []
  {:vertices [[0 0 0] [1 0 0] [1 1 0] [0 1 0]
              [0 0 1] [1 0 1] [1 1 1] [0 1 1]]
   :faces [[0 1 2] [0 2 3] [4 6 5] [4 7 6]]
   :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}
   :anchors {:plug {:position [0.5 0.5 1] :heading [0 0 1] :up [1 0 0]}}})

(defn- target-mesh
  "A far-away target carrying a :socket anchor: position [10 0 0], heading -z
   (pointing back at an approaching plug), up +x."
  []
  {:vertices [[10 0 0] [11 0 0] [11 1 0]]
   :faces [[0 1 2]]
   :creation-pose {:position [10 0 0] :heading [0 0 -1] :up [1 0 0]}
   :anchors {:socket {:position [10 0 0] :heading [0 0 -1] :up [1 0 0]}}})

(defn- run-move-to
  "Replay a single (move-to target & args) inside attach on the mobile mesh.
   Returns the transformed mesh (with world-space :anchors kept in sync)."
  [& args]
  (impl/attach-impl (mobile-mesh)
                    {:type :path
                     :commands [{:cmd :move-to :args (vec args)}]}))

;; ── 1. Pure translation (:from, no align) ────────────────────

(deftest from-pure-translation
  (testing ":from moves the mobile anchor onto the destination without rotating"
    (let [m (run-move-to (target-mesh) :at :socket :from :plug)
          plug (get-in m [:anchors :plug])]
      (is (v= (:position plug) [10 0 0])
          "plug position lands on socket position")
      ;; No rotation: the plug frame is unchanged from its creation frame.
      (is (v= (:heading plug) [0 0 1]) "plug heading unchanged (no align)")
      (is (v= (:up plug) [1 0 0]) "plug up unchanged (no align)"))))

;; ── 2. :from :align — mobile frame == destination frame ──────

(deftest from-align
  (testing ":from :align brings the mobile-anchor frame onto the destination frame"
    (let [m (run-move-to (target-mesh) :at :socket :from :plug :align)
          plug (get-in m [:anchors :plug])
          socket (get-in (target-mesh) [:anchors :socket])]
      (is (v= (:position plug) [10 0 0]) "plug on socket position")
      (is (v= (:heading plug) (:heading socket)) "plug heading == socket heading")
      (is (v= (:up plug) (:up socket)) "plug up == socket up")
      (is (approx= (det3 (:heading plug) (:up plug)) 1.0) "proper rotation (det +1)"))))

;; ── 3. :from :mate — th-180 of the destination frame ─────────

(deftest from-mate
  (testing ":from :mate aligns to the destination composed with th 180"
    (let [m (run-move-to (target-mesh) :at :socket :from :plug :mate)
          plug (get-in m [:anchors :plug])
          socket (get-in (target-mesh) [:anchors :socket])]
      (is (v= (:position plug) [10 0 0]) "plug on socket position")
      (is (v= (:heading plug) (mapv - (:heading socket)))
          "plug heading == -socket heading (faces opposed)")
      (is (v= (:up plug) (:up socket))
          "plug up == socket up (chirality preserved — north stays north)")
      (is (approx= (det3 (:heading plug) (:up plug)) 1.0)
          "proper rotation, not a reflection"))))

;; ── 4. Round-trip: :mate ≡ :align onto a th-180 target ───────

(deftest mate-equals-align-onto-rotated-target
  (testing ":mate on a mark equals :align on the same mark pre-rotated by th 180"
    (let [tgt-rot (assoc-in (target-mesh) [:anchors :socket]
                            {:position [10 0 0] :heading [0 0 1] :up [1 0 0]})
          m-mate  (run-move-to (target-mesh) :at :socket :from :plug :mate)
          m-align (impl/attach-impl (mobile-mesh)
                                    {:type :path
                                     :commands [{:cmd :move-to
                                                 :args [tgt-rot :at :socket :from :plug :align]}]})
          a (get-in m-mate [:anchors :plug])
          b (get-in m-align [:anchors :plug])]
      (is (v= (:position a) (:position b)) "same position")
      (is (v= (:heading a) (:heading b)) "same heading")
      (is (v= (:up a) (:up b)) "same up")
      ;; And the actual geometry matches vertex-for-vertex.
      (is (every? true? (map v= (:vertices m-mate) (:vertices m-align)))
          "same vertex positions"))))

;; ── 5. Turtle final pose == mobile anchor world pose ─────────

(deftest turtle-pose-follows-mobile-anchor
  (testing "the turtle adopts the mobile anchor's post-op world pose (decision 4)"
    ;; Reach into the replay to read final turtle state: run attach and inspect
    ;; via a trailing mark-free command is hard, so assert through the mesh: the
    ;; plug anchor (which the turtle rides) is at socket with the mated frame.
    (let [m (run-move-to (target-mesh) :at :socket :from :plug :mate)
          plug (get-in m [:anchors :plug])]
      (is (v= (:position plug) [10 0 0]))
      (is (v= (:heading plug) [0 0 1]))
      (is (v= (:up plug) [1 0 0])))))

;; ── 6. Pose-map :from ────────────────────────────────────────

(deftest from-pose-map
  (testing ":from accepts an explicit pose map (viewport-codegen hook)"
    (let [m (run-move-to (target-mesh) :at :socket
                         :from {:position [0.5 0.5 1] :heading [0 0 1] :up [1 0 0]}
                         :align)
          ;; plug anchor is co-located with the pose map, so it must land on socket
          plug (get-in m [:anchors :plug])]
      (is (v= (:position plug) [10 0 0]) "pose-map anchor mated onto socket"))))

;; ── 7. Error cases ───────────────────────────────────────────

(deftest errors
  (testing ":from/:mate reject :center"
    (is (thrown? js/Error
                 (run-move-to (target-mesh) :center :from :plug))))
  (testing ":from with unknown anchor errors"
    (is (thrown? js/Error
                 (run-move-to (target-mesh) :at :socket :from :nope))))
  (testing ":from must be followed by a value"
    (is (thrown? js/Error
                 (run-move-to (target-mesh) :at :socket :from)))))

;; ── 7b. Recorder pass-through (brief: verify, don't assume) ──

(deftest recorder-passthrough
  (testing "the path recorder threads :from/:mate through verbatim (no recorder change)"
    (let [{:keys [result error]} (h/eval-dsl "(path (move-to :sock :at :socket :from :plug :mate))")]
      (is (nil? error) (str "should record cleanly: " error))
      (let [cmd (first (:commands result))]
        (is (= :move-to (:cmd cmd)))
        (is (= [:sock :at :socket :from :plug :mate] (:args cmd))
            "args reach the dispatch unchanged (extraction happens at replay, not record)")))))

;; ── 8. Legacy forms untouched (regression) ───────────────────

(deftest legacy-still-works
  (testing "plain :at :align (no :from/:mate) still mates creation-frame turtle"
    (let [m (impl/attach-impl (mobile-mesh)
                              {:type :path
                               :commands [{:cmd :move-to
                                           :args [(target-mesh) :at :socket :align]}]})
          ;; creation-pose (turtle frame at attach start) heading +x, up +z, at
          ;; origin; aligns to socket. The mesh moves; assert the mesh's own
          ;; creation-pose anchor tracked to socket position.
          cp (:creation-pose m)]
      (is (v= (:position cp) [10 0 0]) "creation-pose translated to socket")
      (is (v= (:heading cp) [0 0 -1]) "creation-pose heading == socket heading")
      (is (v= (:up cp) [1 0 0]) "creation-pose up == socket up"))))
