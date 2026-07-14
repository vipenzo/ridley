(ns ridley.editor.attach-collections-test
  "brief-mesh-board.md Part 1 — attach on collections (map/vector) of meshes.
   The map branch fixes a silent no-op (mini-accertamento Q1): attach on a
   mesh-collection map used to fall through to mesh-attach-impl, find no
   :creation-pose at the map's top level, and return the map UNCHANGED with no
   error. Exercises impl/attach-impl directly, no-SCI pattern (see
   stretch_material_frame_test.cljs)."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.impl :as impl]
            [ridley.geometry.primitives :as primitives]
            [ridley.test-helpers :as th]))

(defn- cmd [c & args] {:cmd c :args (vec args)})
(defn- path [& cmds] {:type :path :commands (vec cmds)})

;; Two "leaves" sharing the same creation-pose but sitting at different
;; positions in space — the mesh-split-tree invariant (accertamento Q4): every
;; leaf of a decomposition carries the whole original object's creation-pose,
;; identical across leaves, via carry-meta.
(defn- mesh-at [dx]
  (update (primitives/box-mesh 2 2 2) :vertices
          (fn [vs] (mapv (fn [[x y z]] [(+ x dx) y z]) vs))))

(defn- centroid-dist [m1 m2]
  (let [[x1 y1 z1] (th/mesh-centroid m1)
        [x2 y2 z2] (th/mesh-centroid m2)]
    (Math/sqrt (+ (Math/pow (- x2 x1) 2) (Math/pow (- y2 y1) 2) (Math/pow (- z2 z1) 2)))))

;; ── map of meshes: the fixed no-op ──────────────────────────

(deftest map-attach-preserves-container-and-keys
  (let [tree {:piece-1 (mesh-at 0) :piece-2 (mesh-at 10)}
        out (impl/attach-impl tree (path (cmd :f 5)))]
    (is (map? out))
    (is (= #{:piece-1 :piece-2} (set (keys out))))))

(deftest map-attach-is-not-the-old-silent-noop
  (let [tree {:piece-1 (mesh-at 0) :piece-2 (mesh-at 10)}
        out (impl/attach-impl tree (path (cmd :f 5)))]
    (is (not= tree out) "the old bug returned the map identical, unmoved")))

(deftest map-attach-moves-rigidly-preserving-volume-and-relative-disposition
  (let [m1 (mesh-at 0) m2 (mesh-at 10)
        d0 (centroid-dist m1 m2)
        out (impl/attach-impl {:piece-1 m1 :piece-2 m2} (path (cmd :f 5)))
        o1 (:piece-1 out) o2 (:piece-2 out)]
    (is (th/approx= (th/signed-volume m1) (th/signed-volume o1) 1e-6) "piece-1 volume invariant")
    (is (th/approx= (th/signed-volume m2) (th/signed-volume o2) 1e-6) "piece-2 volume invariant")
    (is (th/approx= d0 (centroid-dist o1 o2) 1e-6) "centroid-to-centroid distance unchanged — rigid group")
    (is (= (:creation-pose o1) (:creation-pose o2)) "shared creation-pose stays shared")
    (is (th/vec-approx= [5 0 0] (:position (:creation-pose o1))) "shared pose advanced by (f 5)")))

(deftest map-attach-empty-map-is-empty-map-not-an-error
  (is (= {} (impl/attach-impl {} (path (cmd :f 5))))))

(deftest map-attach-single-entry-stays-wrapped
  (let [out (impl/attach-impl {:only-piece (mesh-at 0)} (path (cmd :f 5)))]
    (is (map? out))
    (is (= [:only-piece] (keys out)))))

;; ── vector of meshes: already worked, regression-guarded here too ──

(deftest vector-attach-still-works-and-preserves-container
  (let [out (impl/attach-impl [(mesh-at 0) (mesh-at 10)] (path (cmd :f 5)))]
    (is (vector? out))
    (is (= 2 (count out)))))

;; ── unsupported types: readable error, never a silent no-op ────

(deftest attach-on-non-mesh-map-is-a-readable-error
  (testing "a map without :vertices whose values aren't all meshes"
    (let [e (try (impl/attach-impl {:a 1 :b 2} (path (cmd :f 5)))
                 (catch :default e e))]
      (is (instance? js/Error e))
      (is (re-find #"attach" (.-message e)))
      (is (re-find #"map" (.-message e))))))

(deftest attach-on-foreign-type-is-a-readable-error
  (testing "a string is neither sequential (ISequential), a mesh, nor an sdf-node in this codebase"
    (let [e (try (impl/attach-impl "not-a-mesh" (path (cmd :f 5)))
                 (catch :default e e))]
      (is (instance? js/Error e))
      (is (re-find #"attach" (.-message e))))))
