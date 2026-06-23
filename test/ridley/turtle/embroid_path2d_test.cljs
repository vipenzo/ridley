(ns ridley.turtle.embroid-path2d-test
  "embroid must honor the :2d path species like the other planar consumers.
   Bug: embroid's centerline used path-to-2d-waypoints (legacy XY tracer) while
   its base used stroke-shape (ensure-path-2d) — so a path-2d centerline was
   projected in the wrong plane. Fix: embroid uses ensure-path-2d for the
   centerline too. ensure-path-2d delegates to path-to-2d-waypoints for :3d paths
   (no regression) and projects :2d paths onto their frame plane (the fix)."
  (:require [cljs.test :refer [deftest is]]
            [ridley.editor.sci-harness :as h]
            [ridley.editor.operations :as ops]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [ridley.geometry.mesh-utils :as mu]))

(defn- rail [code] (:result (h/eval-dsl code)))
(defn- bbox [mesh]
  (mapv (fn [ax] (let [xs (map #(nth % ax) (:vertices mesh))]
                   [(Math/round (apply min xs)) (Math/round (apply max xs))]))
        [0 1 2]))
(defn- info [mesh]
  (let [d (mu/mesh-diagnose mesh)]
    {:f (count (:faces mesh)) :wt (:is-watertight? d) :bbox (bbox mesh)}))

;; DIAGNOSIS — relate the projections that embroid could use.
(deftest projections-diagnosis
  (println "\n==== embroid path-2d projections ====")
  (let [pa (rail "(path (f 20) (th 60) (f 30))")
        p2 (rail "(path-2d (move-to [0 0]) (f 20) (th 60) (f 30))")]
    (println ">>> path-to-2d-waypoints(plain path):" (mapv :pos (shape/path-to-2d-waypoints pa)))
    (println ">>> ensure-path-2d(plain path)      :" (mapv :pos (shape/ensure-path-2d pa)))
    (println ">>> ensure-path-2d(path-2d)         :" (mapv :pos (shape/ensure-path-2d p2)))
    ;; ensure-path-2d must be identical to path-to-2d-waypoints on a :3d path
    ;; (this is the non-regression guarantee for plain-path embroid).
    (is (= (mapv :pos (shape/path-to-2d-waypoints pa))
           (mapv :pos (shape/ensure-path-2d pa)))
        "ensure-path-2d == path-to-2d-waypoints on a :3d path (no regression)")))

;; The fix's effect on swept embroid geometry.
(deftest embroid-honors-path-2d
  (let [r   (rail "(path (f 30))")
        pa  (rail "(path (f 20) (th 60) (f 30))")
        p2  (rail "(path-2d (move-to [0 0]) (f 20) (th 60) (f 30))")
        ea  (info (ops/pure-loft-shape-fn (sfn/embroid pa 3 :cells 6) r 32))
        e2  (info (ops/pure-loft-shape-fn (sfn/embroid p2 3 :cells 6) r 32))]
    (println ">>> embroid(plain path) :" ea)
    (println ">>> embroid(path-2d)    :" e2)
    (is (:wt ea) "embroid(plain path) watertight")
    (is (:wt e2) "embroid(path-2d) watertight")
    ;; INVARIANT (the fix): ensure-path-2d(path-2d) == ensure-path-2d(plain path)
    ;; (measured identical, no flip), so a path-2d tracing the same 2D curve must
    ;; give a BYTE-IDENTICAL embroid wall — same face count AND same bbox.
    (is (= (:f ea) (:f e2)) "embroid(path-2d) has the same face count as embroid(plain path)")
    (is (= (:bbox ea) (:bbox e2)) "embroid(path-2d) is identical to embroid(plain path)")))
