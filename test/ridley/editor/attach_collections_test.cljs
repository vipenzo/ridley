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
            [ridley.manifold.core :as manifold]
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

;; ── raw mesh-split composite: named error, never obscure ────

(deftest attach-on-raw-composite-names-split-tree
  (testing "brief-split-tree.md Part 3 — the emitted call's value is a composite
            now, so attaching it directly is the likely mistake"
    (let [e (try (impl/attach-impl {:behind (mesh-at 0)
                                    :ahead {:behind (mesh-at 5) :ahead (mesh-at 10)}}
                                   (path (cmd :f 5)))
                 (catch :default e e))]
      (is (instance? js/Error e))
      (is (re-find #"attach" (.-message e)))
      (is (re-find #"split-tree" (.-message e)) "the error names the conversion"))))

(deftest attach-on-one-cut-composite-is-rejected-too
  (testing "at one cut both values ARE meshes, so meshes-collection? accepted it
            and group-transformed a 'tree' named :behind/:ahead — a success that
            teaches the wrong shape. One rule, whatever the cut count."
    (let [e (try (impl/attach-impl {:behind (mesh-at 0) :ahead (mesh-at 10)} (path (cmd :f 5)))
                 (catch :default e e))]
      (is (instance? js/Error e))
      (is (re-find #"split-tree" (.-message e))))))

(deftest attach-on-split-tree-of-a-composite-moves-the-group
  (testing "the cure the error names works: named pieces take the map branch"
    (let [composite {:behind (mesh-at 0) :ahead {:behind (mesh-at 5) :ahead (mesh-at 10)}}
          out (impl/attach-impl (manifold/split-tree composite) (path (cmd :f 5)))]
      (is (= #{:piece-1 :piece-2 :piece-3} (set (keys out))))
      (is (th/approx= (th/signed-volume (mesh-at 0)) (th/signed-volume (:piece-1 out)) 1e-6)
          "rigid — volume invariant"))))

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
