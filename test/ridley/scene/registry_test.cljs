(ns ridley.scene.registry-test
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.scene.registry :as reg]))

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
