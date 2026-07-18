(ns ridley.photogrammetry.synth
  "Synthetic scene generation for the proxy-fit experiments.

   The point of a synthetic harness is that ground truth is known exactly, so
   the measured error is the SOLVER's error and nothing else. A single real
   photo set can only tell you the total error of solver + lens model + hand
   tracing + caliper together; it cannot tell you which of them dominates,
   nor how the error grows as conditions worsen. Both experiments are needed,
   and this one comes first because it can falsify the approach before any
   photograph is taken.

   The RNG is seeded and explicit: every number in the report is reproducible."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.box-fit :as bf]))

;; ---------------------------------------------------------------------------
;; Deterministic RNG (mulberry32) — no reliance on js/Math.random

(defn rng
  "Returns a stateful function of no arguments yielding uniform [0,1)."
  [seed]
  (let [s (atom (bit-or (int seed) 0))]
    (fn []
      (let [a (swap! s #(bit-or (+ % 0x6D2B79F5) 0))
            t (js/Math.imul (bit-xor a (unsigned-bit-shift-right a 15))
                            (bit-or 1 a))
            t (bit-xor t (+ t (js/Math.imul (bit-xor t (unsigned-bit-shift-right t 7))
                                            (bit-or 61 t))))]
        (/ (unsigned-bit-shift-right (bit-xor t (unsigned-bit-shift-right t 14)) 0)
           4294967296)))))

(defn gauss
  "Standard normal sample via Box-Muller from a uniform generator."
  [r]
  (let [u1 (max 1e-12 (r))
        u2 (r)]
    (* (Math/sqrt (* -2.0 (Math/log u1)))
       (Math/cos (* 2.0 Math/PI u2)))))

;; ---------------------------------------------------------------------------
;; Viewpoints

(defn viewpoint
  "A camera pose looking at the origin from spherical coordinates.
   azimuth/elevation in degrees, distance in mm."
  [azimuth-deg elevation-deg distance]
  (let [az (* (/ azimuth-deg 180.0) Math/PI)
        el (* (/ elevation-deg 180.0) Math/PI)
        eye [(* distance (Math/cos el) (Math/cos az))
             (* distance (Math/cos el) (Math/sin az))
             (* distance (Math/sin el))]]
    (cam/look-at-pose eye [0.0 0.0 0.0] [0.0 0.0 1.0])))

;; ---------------------------------------------------------------------------
;; Observation synthesis

(defn observations
  "Generate edge-line observations of a box from a set of poses.

   true-dims    : [w h d] ground truth, mm
   poses        : ground-truth camera poses
   true-intrin  : per-view intrinsics used to FORM the image (the real lens,
                  distortion included)
   sigma-px     : edge localisation noise, pixels
   r            : RNG

   Noise is applied PERPENDICULAR to each edge, which is the only direction
   in which an edge observation carries information."
  [true-dims poses true-intrin sigma-px r]
  (let [pts (bf/corners true-dims)]
    (vec (mapcat
          (fn [view]
            (let [pose (nth poses view)
                  k (nth true-intrin view)]
              (keep (fn [edge-idx]
                      (let [[ia ib] (nth bf/edges edge-idx)
                            pa (cam/project k pose (nth pts ia))
                            pb (cam/project k pose (nth pts ib))]
                        (when (and pa pb)
                          (let [base (bf/line-through pa pb)]
                            (when base
                              (let [[nx ny _] base
                                    da (* sigma-px (gauss r))
                                    db (* sigma-px (gauss r))
                                    pa' [(+ (first pa) (* nx da)) (+ (second pa) (* ny da))]
                                    pb' [(+ (first pb) (* nx db)) (+ (second pb) (* ny db))]]
                                (when-let [l (bf/line-through pa' pb')]
                                  {:view view :edge edge-idx :line l})))))))
                    (bf/visible-edges true-dims pose))))
          (range (count poses))))))

;; ---------------------------------------------------------------------------
;; Initialisation perturbation (the 'hand alignment' the user provides)

(defn perturb-pose
  "Perturb a pose as a hand alignment would: an angular error and a
   positional error, both isotropic."
  [pose angle-deg pos-mm r]
  (let [ang (* (/ angle-deg 180.0) Math/PI)
        drv [(* ang (gauss r)) (* ang (gauss r)) (* ang (gauss r))]
        dt [(* pos-mm (gauss r)) (* pos-mm (gauss r)) (* pos-mm (gauss r))]]
    {:rvec (mapv + (:rvec pose) drv)
     :t (mapv + (:t pose) dt)}))

(defn perturb-dims
  "Perturb dimensions by a relative fraction (the user's estimated 'n')."
  [dims frac r]
  (mapv (fn [d] (max 1e-3 (* d (+ 1.0 (* frac (gauss r)))))) dims))
