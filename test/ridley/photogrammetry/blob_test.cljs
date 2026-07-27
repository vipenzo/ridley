(ns ridley.photogrammetry.blob-test
  "blob-snap: the centroid of a dark plate mark inside a local window must be
   recovered to sub-pixel from an OFF-centre prediction (mean-shift recentres),
   and a window with no crisp mark (flat plate, or the disc outside it) must be
   rejected rather than snapped to noise — the property fette A/C rest on."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.blob :as blob]
            [ridley.photogrammetry.synth :as synth]))

(defn- disc-lum
  "A synthetic scene sampler: a dark disc (luminance `dark`) of radius `dr`
   centred at (dcx,dcy) on a light background (`light`), over [0,W)×[0,H). nil
   off-image, like the real backdrop/luminance-at. Optional per-pixel gaussian
   noise (sigma, deterministic rng)."
  ([W H dcx dcy dr dark light] (disc-lum W H dcx dcy dr dark light 0.0 nil))
  ([W H dcx dcy dr dark light sigma rng]
   (fn [x y]
     (when (and (>= x 0) (>= y 0) (< x W) (< y H))
       (let [base (if (<= (+ (* (- x dcx) (- x dcx)) (* (- y dcy) (- y dcy))) (* dr dr))
                    dark light)]
         (if (pos? sigma) (+ base (* sigma (synth/gauss rng))) base))))))

(defn- err [[u v] cx cy]
  (Math/sqrt (+ (* (- u cx) (- u cx)) (* (- v cy) (- v cy)))))

(deftest recovers-disc-centre-from-offset-prediction
  (println "\n=== blob-snap: centroide del dischetto da una predizione decentrata ===")
  (doseq [[cx cy off] [[300.0 220.0 0.0] [300.0 220.0 15.0] [300.0 220.0 -22.0]]]
    (let [lum (disc-lum 800 600 cx cy 25.0 26.0 230.0)
          pred [(+ cx off) (- cy off)]
          res (blob/snap-to-blob lum pred 55)]
      (println (str "  vero=[" cx " " cy "] pred=" (mapv #(Math/round %) pred)
                    " → " (when res (mapv #(.toFixed % 2) (:center res)))
                    (when res (str " (err " (.toFixed (err (:center res) cx cy) 2) "px, "
                                   (:iters res) " iter)"))))
      (is (some? res) "a crisp disc in the window must snap")
      (is (< (err (:center res) cx cy) 1.0)
          (str "centroid within 1px of the true centre, got " (.toFixed (err (:center res) cx cy) 2))))))

(deftest survives-noise
  (testing "sub-pixel-ish recovery holds under per-pixel gaussian noise"
    (let [rng (synth/rng 9)
          cx 260.0 cy 300.0
          lum (disc-lum 700 600 cx cy 24.0 30.0 225.0 12.0 rng)
          res (blob/snap-to-blob lum [cx (+ cy 18.0)] 55)]
      (is (some? res))
      (is (< (err (:center res) cx cy) 2.0)
          (str "noisy centroid within 2px, got " (.toFixed (err (:center res) cx cy) 2))))))

(deftest rejects-flat-and-empty-windows
  (testing "a flat (uniform) window has no mark → nil"
    (let [flat (fn [x y] (when (and (>= x 0) (>= y 0) (< x 400) (< y 400)) 210.0))]
      (is (nil? (blob/snap-to-blob flat [200.0 200.0] 50)))))
  (testing "a disc entirely OUTSIDE the window → nil (too little dark, not snapped to noise)"
    (let [lum (disc-lum 800 600 120.0 120.0 20.0 25.0 230.0)]
      ;; predict far from the disc: the window sees only light plate
      (is (nil? (blob/snap-to-blob lum [500.0 400.0] 45)))))
  (testing "a mostly-dark window (a big shadow/occluding part, not a crisp mark) → nil"
    ;; high contrast (light corners) but the dark region fills >max-dark-frac of
    ;; the window: an oversized dark area, not a disc-sized mark → rejected.
    (let [big (disc-lum 600 600 250.0 250.0 70.0 25.0 230.0)]
      (is (nil? (blob/snap-to-blob big [250.0 250.0] 45))))))

(deftest light-polarity
  (testing ":polarity :light recovers a LIGHT disc on a dark ground (for generality)"
    (let [lum (disc-lum 600 600 280.0 240.0 24.0 235.0 28.0) ; light disc, dark bg
          res (blob/snap-to-blob lum [(+ 280.0 14.0) 240.0] 55 {:polarity :light})]
      (is (some? res))
      (is (< (err (:center res) 280.0 240.0) 1.0)))))
