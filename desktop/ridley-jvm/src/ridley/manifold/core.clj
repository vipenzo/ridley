(ns ridley.manifold.core
  "Manifold operations — delegates to native.clj (Rust HTTP server)."
  (:require [ridley.manifold.native :as native]))

(def union native/union)
(def difference native/difference)
(def intersection native/intersection)
(def hull native/hull)
(def manifold? native/manifold?)
(def get-mesh-status native/get-mesh-status)
(def solidify native/solidify)
(def concat-meshes native/concat-meshes)
(def smooth native/smooth)
(def refine native/refine)

(defn hull-from-points [points]
  ;; Wrap points as a single mesh and hull it
  (native/hull [{:type :mesh :vertices (vec points) :faces []
                 :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}}]))

;; Stub for slice-at-plane (not available in JVM spike)
(defn slice-at-plane [& _]
  (throw (Exception. "slice-at-plane not available in JVM spike")))
