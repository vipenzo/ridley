(ns ridley.photogrammetry.plate-scene
  "Synthetic plate photo for the fetta-C tests (NOT a -test ns, so it is not run as
   a suite — a shared support helper): project a registration-plate's crown marks +
   zero-index from a chosen solver pose and stamp each as a filled dark disc into a
   luminance buffer, giving the SAME (lum-at, size) the real detector consumes. Lets
   blob-detect + fit-crown be exercised end-to-end (detect → identify → the pose
   comes back) without a real JPEG, deterministically."
  (:require [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.synth :as synth]))

(defn plate-targets
  "The crown marks [{:id :obj} …] (sorted), the zero-obj, marked-face normal and
   disc radius of `proxy`, all in the solver/object frame — exactly what fit-crown
   and assign-marks take (via bridge/pnp-target-points + bridge/plate-detect, the
   real code path). camera-pose is unused for a plate but the arity needs one."
  [proxy]
  (let [targets (bridge/pnp-target-points proxy {:position [0.0 0.0 300.0] :heading [0.0 0.0 -1.0] :up [0.0 1.0 0.0]})
        det (bridge/plate-detect proxy)]
    {:marks (mapv #(select-keys % [:id :obj]) targets)
     :zero-obj (:zero-obj det)
     :disc-r (:disc-r det)
     :face-normal (:face-normal det)}))

(defn- stamp-disc!
  "Paint a filled dark circle of radius `r` px at [u v] into the row-major Uint8
   buffer `buf` (w×h), value `dark`. Iterates only the disc's bounding box."
  [buf w h u v r dark]
  (let [ui (Math/round u) vi (Math/round v) ri (Math/ceil r) r2 (* r r)]
    (doseq [y (range (max 0 (- vi ri)) (min h (+ vi ri 1)))
            x (range (max 0 (- ui ri)) (min w (+ ui ri 1)))]
      (when (<= (+ (* (- x u) (- x u)) (* (- y v) (- y v))) r2)
        (aset buf (+ x (* y w)) dark)))))

(defn render
  "Render a synthetic plate photo of `proxy` from solver `pose` at `intrinsics` into
   a w×h luminance buffer. Each crown mark + the zero-index is a filled dark disc of
   its imaged radius (disc-r projected). Returns
   {:lum-at (fn [x y]) :size [w h] :truth {mark-idx [u v]} :zero-px [u v] :pose pose}.
   `:noise` (default 0) adds deterministic gaussian pixel noise."
  [proxy pose intrinsics w h & {:keys [dark light noise seed] :or {dark 30 light 230 noise 0.0 seed 7}}]
  (let [{:keys [marks zero-obj disc-r]} (plate-targets proxy)
        buf (js/Uint8Array. (* w h))
        _ (.fill buf light)
        r (synth/rng seed)
        img-radius (fn [obj]
                     ;; imaged disc radius: project centre and a disc-r offset in the
                     ;; marked plane (x/y are in-plane for the identity-pose plate)
                     (let [p0 (cam/project intrinsics pose obj)
                           pu (cam/project intrinsics pose (mapv + obj [disc-r 0.0 0.0]))
                           pv (cam/project intrinsics pose (mapv + obj [0.0 disc-r 0.0]))]
                       (when (and p0 pu pv)
                         [p0 (max (Math/hypot (- (pu 0) (p0 0)) (- (pu 1) (p0 1)))
                                  (Math/hypot (- (pv 0) (p0 0)) (- (pv 1) (p0 1))))])))
        truth (into {} (keep-indexed
                        (fn [i {:keys [obj]}]
                          (when-let [[[u v] rr] (img-radius obj)]
                            (stamp-disc! buf w h u v rr dark)
                            [i [u v]]))
                        marks))
        zero-px (when-let [[[u v] rr] (img-radius zero-obj)]
                  (stamp-disc! buf w h u v rr dark)
                  [u v])]
    (when (pos? noise)
      (dotimes [k (* w h)]
        (aset buf k (max 0 (min 255 (Math/round (+ (aget buf k) (* noise (synth/gauss r)))))))))
    {:lum-at (fn [x y]
               (let [xi (Math/round x) yi (Math/round y)]
                 (when (and (>= xi 0) (>= yi 0) (< xi w) (< yi h))
                   (aget buf (+ xi (* yi w))))))
     :size [w h] :truth truth :zero-px zero-px :pose pose}))
