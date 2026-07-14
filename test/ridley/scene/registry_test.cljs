(ns ridley.scene.registry-test
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.scene.registry :as reg]
            [ridley.viewport.core :as viewport]))

(def mesh-a {:type :mesh
             :vertices [[0 0 0] [1 0 0] [0 1 0]]
             :faces [[0 1 2]]})

(def mesh-b {:type :mesh
             :vertices [[0 0 0] [0 1 0] [0 0 1]]
             :faces [[0 1 2]]})

(def sample-path {:type :path
                  :segments [{:type :line :from [0 0 0] :to [1 0 0]}
                             {:type :line :from [1 0 0] :to [1 1 0]}]})

(deftest mesh-registry-ids-are-unique
  (testing "add-mesh! assigns unique registry ids"
    (reg/clear-all!)
    (let [m1 (reg/add-mesh! mesh-a :a)
          m2 (reg/add-mesh! mesh-b :b)]
      (is (not= (:registry-id m1) (:registry-id m2)) "registry ids should differ")
      (is (= 1 (:registry-id m1)) "first mesh gets id 1")
      (is (= 2 (:registry-id m2)) "second mesh gets id 2"))))

(deftest register-path-validates-and-stores
  (testing "register-path! returns path and can be retrieved"
    (reg/clear-all!)
    (let [p (reg/register-path! :p sample-path)]
      (is p)
      (is (= sample-path (reg/get-path :p))))))

;; ── mesh-board scaffold citizenship (brief-mesh-board.md Part 4) ──
;; The export path (core.cljs's export-mesh → viewport/get-current-meshes) reads
;; ONLY current-meshes, populated from :meshes — never :scaffolds. This is the
;; structural guarantee, not a convention: a permanent regression test.

(deftest scaffolds-never-reach-current-meshes
  (testing "set-scaffolds!/add-scaffolds! never contaminate the exportable mesh set"
    (reg/clear-all!)
    (reg/add-mesh! mesh-a :a)
    (reg/set-scaffolds! [mesh-b])
    (reg/refresh-viewport!)
    (let [exportable (viewport/get-current-meshes)]
      (is (= 1 (count exportable)) "only the registered mesh reaches current-meshes")
      (is (= :a (:registry-name (first exportable))))
      (is (not-any? #(= (:vertices mesh-b) (:vertices %)) exportable)
          "the scaffold mesh's geometry never appears among exportable meshes"))))

(deftest scaffolds-survive-incremental-add-still-excluded
  (testing "add-scaffolds! (incremental REPL push) is excluded from export just like set-scaffolds!"
    (reg/clear-all!)
    (reg/add-mesh! mesh-a :a)
    (reg/add-scaffolds! [mesh-b])
    (reg/refresh-viewport!)
    (is (= 1 (count (viewport/get-current-meshes))))))
