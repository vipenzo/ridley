(ns ridley.turtle.synthesize-delta-test
  "Round-trip property test for turtle/synthesize-delta — the pose-A -> pose-B
   canonical (th tv tr f) decomposition edit-mesh-split uses to emit
   human-readable cut deltas. The property: replaying the synthesized delta
   from pose-A reproduces pose-B within epsilon. This is the brief's own
   'decisive' test (dev-docs/brief-edit-mesh-split.md, Parte 3 Emissione)."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.turtle.core :as t]
            [ridley.test-helpers :as h]))

(def ^:private eps 1e-6)

(defn- delta->path
  "Build a (path ...) from synthesize-delta's output, rotations (th tv tr)
   before translations (f rt u), skipping nil (negligible) components."
  [{:keys [th tv tr f rt u]}]
  (t/make-path (cond-> []
                 th (conj {:cmd :th :args [th]})
                 tv (conj {:cmd :tv :args [tv]})
                 tr (conj {:cmd :tr :args [tr]})
                 f  (conj {:cmd :f :args [f]})
                 rt (conj {:cmd :rt :args [rt]})
                 u  (conj {:cmd :u :args [u]}))))

(defn- replay-from
  "Replay `path` from `from-pose` on a pen-off scratch turtle, same
   mechanism resolve-marks uses. Returns the resulting {:position :heading :up}."
  [from-pose path]
  (let [start (-> (t/make-turtle)
                  (assoc :position (:position from-pose)
                         :heading (:heading from-pose)
                         :up (:up from-pose))
                  (assoc :pen-mode :off))
        result (t/run-path start path)]
    (select-keys result [:position :heading :up])))

(defn- assert-round-trip [label from-pose to-pose]
  (testing label
    (let [delta (t/synthesize-delta from-pose to-pose)
          path (delta->path delta)
          replayed (replay-from from-pose path)]
      (is (h/vec-approx= (:position to-pose) (:position replayed) eps)
          (str label ": position, delta=" (pr-str delta)))
      (is (h/vec-approx= (:heading to-pose) (:heading replayed) eps)
          (str label ": heading, delta=" (pr-str delta)))
      (is (h/vec-approx= (:up to-pose) (:up replayed) eps)
          (str label ": up, delta=" (pr-str delta))))))

(deftest synthesize-delta-pure-forward
  (let [from (t/make-turtle)
        to (t/f from 25)]
    (assert-round-trip "pure forward move, no rotation" from to)))

(deftest synthesize-delta-pure-yaw
  (let [from (t/make-turtle)
        to (t/th from 40)]
    (assert-round-trip "pure yaw (th only), no position change" from to)))

(deftest synthesize-delta-pure-pitch
  (let [from (t/make-turtle)
        to (t/tv from -35)]
    (assert-round-trip "pure pitch (tv only), no position change" from to)))

(deftest synthesize-delta-yaw-pitch-forward
  (let [from (t/make-turtle)
        to (-> from (t/th 30) (t/tv 20) (t/f 15))]
    (assert-round-trip "th + tv + f, no roll needed" from to)))

(deftest synthesize-delta-requires-roll-residual
  ;; tr AFTER th/tv changes only `up`, not heading — so th-then-tv alone
  ;; can't reach this target frame; synthesize-delta's residual tr must
  ;; supply the missing roll.
  (let [from (t/make-turtle)
        to (-> from (t/th 25) (t/tv 15) (t/tr 47) (t/f 12))]
    (assert-round-trip "th + tv + tr (roll residual) + f" from to)))

(deftest synthesize-delta-general-arbitrary-poses
  (let [from (-> (t/make-turtle) (t/f 10) (t/th 15) (t/tv -20) (t/tr 8))
        to (-> from (t/f 8) (t/th 40) (t/tv -25) (t/tr 33) (t/f 5))]
    (assert-round-trip "from and to both off the default pose" from to)))

(deftest synthesize-delta-pure-rotation-same-position
  ;; from and to share a position — synthesize-delta must aim th/tv at
  ;; to's heading directly (no position delta to derive a direction from).
  (let [from (t/make-turtle)
        to (-> from (t/th 60) (t/tv -30) (t/tr 15))]
    (is (= (:position from) (:position to)) "sanity: same position")
    (assert-round-trip "pure rotation, from and to at the same position" from to)))

(deftest synthesize-delta-near-opposite-heading
  ;; th-then-tv's atan2-based decomposition has a degenerate case near
  ;; antiparallel headings — stress it explicitly.
  (let [from (t/make-turtle)
        to (-> from (t/th 179) (t/f 10))]
    (assert-round-trip "near-180 degree yaw" from to)))

(deftest synthesize-delta-negligible-components-are-nil
  (testing "identical poses synthesize an all-nil delta (nothing to emit)"
    (let [pose (t/make-turtle)
          delta (t/synthesize-delta pose pose)]
      (is (nil? (:th delta)))
      (is (nil? (:tv delta)))
      (is (nil? (:tr delta)))
      (is (nil? (:f delta))))))
