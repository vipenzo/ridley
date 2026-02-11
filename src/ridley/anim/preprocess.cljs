(ns ridley.anim.preprocess
  "Animation preprocessing: convert spans with turtle commands into
   a flat vector of frame poses for O(1) playback lookup.

   Frames are generated on a LINEAR timeline. Easing is applied at
   playback time by remapping the time-to-frame-index lookup."
  (:require [ridley.math :as math]))

(def ^:private deg->rad (/ Math/PI 180))

;; ============================================================
;; Frame distribution
;; ============================================================

(defn command-effective-distance
  "Calculate effective distance for a command given ang-velocity setting.
   Linear movements contribute their absolute distance.
   Rotations contribute based on ang-velocity (0 = instantaneous).
   Parallel groups use max of sub-commands."
  [cmd ang-velocity]
  (case (:type cmd)
    (:f :u :d :rt :lt) (Math/abs (:dist cmd))
    (:th :tv :tr) (if (zero? ang-velocity)
                    0
                    (* ang-velocity (/ (Math/abs (:angle cmd)) 360)))
    :parallel (let [ds (mapv #(command-effective-distance % ang-velocity) (:commands cmd))]
                (if (seq ds) (apply max ds) 0))
    0))

(defn- adjust-frame-counts
  "Round frame counts and adjust to sum exactly to total.
   Distributes rounding remainder to largest segments."
  [raw-counts total]
  (let [rounded (mapv #(int (Math/floor %)) raw-counts)
        current-sum (reduce + rounded)
        deficit (- total current-sum)]
    (if (zero? deficit)
      rounded
      ;; Distribute deficit to commands with largest fractional parts
      (let [fractionals (mapv #(- % (Math/floor %)) raw-counts)
            ;; Sort indices by fractional part descending
            sorted-indices (sort-by #(- (nth fractionals %)) (range (count raw-counts)))]
        (reduce (fn [counts i]
                  (update counts (nth sorted-indices i) inc))
                rounded
                (range deficit))))))

(defn distribute-frames
  "Distribute total-frames across commands proportionally to effective distance."
  [commands total-frames ang-velocity]
  (if (empty? commands)
    []
    (let [distances (mapv #(command-effective-distance % ang-velocity) commands)
          total-dist (reduce + distances)]
      (if (zero? total-dist)
        ;; All instantaneous — give 0 frames to each
        (vec (repeat (count commands) 0))
        ;; Proportional distribution
        (let [raw-frames (mapv #(* total-frames (/ % total-dist)) distances)]
          (adjust-frame-counts raw-frames total-frames))))))

(defn distribute-span-frames
  "Distribute total-frames across spans proportionally to their weights."
  [spans total-frames]
  (if (empty? spans)
    []
    (let [weights (mapv #(:weight % 1.0) spans)
          total-weight (reduce + weights)
          raw-frames (mapv #(* total-frames (/ % total-weight)) weights)]
      (adjust-frame-counts raw-frames total-frames))))

;; ============================================================
;; Virtual turtle for pose generation
;; ============================================================

(defn- make-virtual-turtle
  "Create a virtual turtle from an initial pose."
  [{:keys [position heading up]}]
  {:position (or position [0 0 0])
   :heading (or heading [1 0 0])
   :up (or up [0 0 1])})

(defn- turtle-pose
  "Extract pose map from virtual turtle."
  [t]
  {:position (:position t)
   :heading (:heading t)
   :up (:up t)})

(defn- virtual-f
  "Move virtual turtle forward by dist."
  [t dist]
  (update t :position #(math/v+ % (math/v* (:heading t) dist))))

(defn- virtual-lateral
  "Move virtual turtle along an axis by dist (no heading change)."
  [t axis dist]
  (update t :position #(math/v+ % (math/v* axis dist))))

(defn- virtual-th
  "Rotate virtual turtle heading around up axis."
  [t angle]
  (let [rad (* angle deg->rad)]
    (update t :heading #(math/rotate-around-axis % (:up t) rad))))

(defn- virtual-tv
  "Rotate virtual turtle heading and up around right axis."
  [t angle]
  (let [rad (* angle deg->rad)
        right (math/normalize (math/cross (:heading t) (:up t)))]
    (-> t
        (update :heading #(math/rotate-around-axis % right rad))
        (update :up #(math/rotate-around-axis % right rad)))))

(defn- virtual-tr
  "Rotate virtual turtle up around heading axis."
  [t angle]
  (let [rad (* angle deg->rad)]
    (update t :up #(math/rotate-around-axis % (:heading t) rad))))

;; ============================================================
;; Partial command application (for parallel groups)
;; ============================================================

(defn- apply-partial-command
  "Apply a command at fraction t (0-1) to turtle state.
   Used by parallel groups where all sub-commands share the same timeline."
  [turtle cmd t]
  (case (:type cmd)
    :f  (virtual-f turtle (* (:dist cmd) t))
    :u  (virtual-lateral turtle (:up turtle) (* (:dist cmd) t))
    :d  (virtual-lateral turtle (math/v* (:up turtle) -1) (* (:dist cmd) t))
    :rt (virtual-lateral turtle (math/normalize (math/cross (:heading turtle) (:up turtle))) (* (:dist cmd) t))
    :lt (virtual-lateral turtle (math/v* (math/normalize (math/cross (:heading turtle) (:up turtle))) -1) (* (:dist cmd) t))
    :th (virtual-th turtle (* (:angle cmd) t))
    :tv (virtual-tv turtle (* (:angle cmd) t))
    :tr (virtual-tr turtle (* (:angle cmd) t))
    turtle))

;; ============================================================
;; Frame generation per command
;; ============================================================

(defn- generate-linear-frames
  "Generate N frames for a linear movement command.
   Returns vector of poses, one per frame."
  [turtle cmd n-frames]
  (when (pos? n-frames)
    (let [{:keys [type dist angle]} cmd
          axis (case type
                 :f (:heading turtle)
                 :u (:up turtle)
                 :d (math/v* (:up turtle) -1)
                 :rt (math/normalize (math/cross (:heading turtle) (:up turtle)))
                 :lt (math/v* (math/normalize (math/cross (:heading turtle) (:up turtle))) -1)
                 nil)
          total-dist (case type
                       (:f :u :d :rt :lt) dist
                       nil)]
      (case type
        ;; Linear movements
        (:f :u :d :rt :lt)
        (mapv (fn [i]
                (let [t (/ (inc i) n-frames)
                      pos (math/v+ (:position turtle)
                                   (math/v* axis (* total-dist t)))]
                  {:position pos
                   :heading (:heading turtle)
                   :up (:up turtle)}))
              (range n-frames))

        ;; Rotations with allocated frames (ang-velocity > 0)
        :th
        (mapv (fn [i]
                (let [t (/ (inc i) n-frames)
                      rad (* angle t deg->rad)
                      new-heading (math/rotate-around-axis (:heading turtle) (:up turtle) rad)]
                  {:position (:position turtle)
                   :heading new-heading
                   :up (:up turtle)}))
              (range n-frames))

        :tv
        (mapv (fn [i]
                (let [t (/ (inc i) n-frames)
                      rad (* angle t deg->rad)
                      right (math/normalize (math/cross (:heading turtle) (:up turtle)))
                      new-heading (math/rotate-around-axis (:heading turtle) right rad)
                      new-up (math/rotate-around-axis (:up turtle) right rad)]
                  {:position (:position turtle)
                   :heading new-heading
                   :up new-up}))
              (range n-frames))

        :tr
        (mapv (fn [i]
                (let [t (/ (inc i) n-frames)
                      rad (* angle t deg->rad)
                      new-up (math/rotate-around-axis (:up turtle) (:heading turtle) rad)]
                  {:position (:position turtle)
                   :heading (:heading turtle)
                   :up new-up}))
              (range n-frames))

        ;; Parallel group: apply all sub-commands at same fraction t
        :parallel
        (let [sub-cmds (:commands cmd)]
          (mapv (fn [i]
                  (let [t (/ (inc i) n-frames)
                        final (reduce #(apply-partial-command %1 %2 t) turtle sub-cmds)]
                    (turtle-pose final)))
                (range n-frames)))

        ;; Unknown command type
        []))))

(defn- apply-command-to-turtle
  "Apply a full command to virtual turtle, returning updated turtle."
  [turtle cmd]
  (case (:type cmd)
    :f  (virtual-f turtle (:dist cmd))
    :u  (virtual-lateral turtle (:up turtle) (:dist cmd))
    :d  (virtual-lateral turtle (math/v* (:up turtle) -1) (:dist cmd))
    :rt (virtual-lateral turtle (math/normalize (math/cross (:heading turtle) (:up turtle))) (:dist cmd))
    :lt (virtual-lateral turtle (math/v* (math/normalize (math/cross (:heading turtle) (:up turtle))) -1) (:dist cmd))
    :th (virtual-th turtle (:angle cmd))
    :tv (virtual-tv turtle (:angle cmd))
    :tr (virtual-tr turtle (:angle cmd))
    :parallel (reduce apply-command-to-turtle turtle (:commands cmd))
    turtle))

;; ============================================================
;; Span-level frame generation
;; ============================================================

(defn- generate-span-frames
  "Generate all frames for a single span.
   Returns {:frames [...] :final-state turtle}."
  [turtle span cmd-frame-counts]
  (let [commands (:commands span)]
    (loop [i 0
           current-turtle turtle
           all-frames []]
      (if (>= i (count commands))
        {:frames all-frames
         :final-state current-turtle}
        (let [cmd (nth commands i)
              n-frames (nth cmd-frame-counts i 0)]
          (if (zero? n-frames)
            ;; Instantaneous command (e.g. rotation with ang-velocity 0)
            (recur (inc i)
                   (apply-command-to-turtle current-turtle cmd)
                   all-frames)
            ;; Generate frames for this command
            (let [frames (generate-linear-frames current-turtle cmd n-frames)
                  new-turtle (apply-command-to-turtle current-turtle cmd)]
              (recur (inc i)
                     new-turtle
                     (into all-frames frames)))))))))

;; ============================================================
;; Orbital camera mode (for :camera target animations)
;;
;; Commands reinterpreted in orbital mode:
;;   rt/lt dist  → orbit horizontally around pivot (dist = degrees)
;;   u/d   dist  → orbit vertically around pivot (dist = degrees)
;;   f     dist  → dolly toward/away from pivot (distance units)
;;   th    angle → pan (rotate look direction horizontally)
;;   tv    angle → tilt (rotate look direction vertically)
;;   tr    angle → roll (rotate up around heading)
;;
;; Output frames are still {:position :heading :up} — playback unchanged.
;; ============================================================

(declare apply-orbital-command)

(defn- orbital-effective-distance
  "Effective distance for orbital camera frame distribution.
   All angular commands weighted by degree magnitude.
   Dolly weighted by distance. ang-velocity=0 makes angular commands instantaneous."
  [cmd ang-velocity]
  (case (:type cmd)
    (:rt :lt :u :d) (let [deg (Math/abs (:dist cmd))]
                      (if (zero? ang-velocity) 0 deg))
    (:th :tv :tr)   (let [deg (Math/abs (:angle cmd))]
                      (if (zero? ang-velocity) 0 deg))
    :f (Math/abs (:dist cmd))
    :parallel (let [ds (mapv #(orbital-effective-distance % ang-velocity) (:commands cmd))]
                (if (seq ds) (apply max ds) 0))
    0))

(defn- distribute-orbital-frames
  "Distribute frames for orbital camera commands."
  [commands total-frames ang-velocity]
  (if (empty? commands)
    []
    (let [distances (mapv #(orbital-effective-distance % ang-velocity) commands)
          total-dist (reduce + distances)]
      (if (zero? total-dist)
        (vec (repeat (count commands) 0))
        (let [raw-frames (mapv #(* total-frames (/ % total-dist)) distances)]
          (adjust-frame-counts raw-frames total-frames))))))

(defn- generate-orbital-frames
  "Generate N interpolated frames for a single orbital camera command."
  [cam cmd n-frames pivot]
  (when (pos? n-frames)
    (let [{:keys [type dist angle]} cmd
          pos (:position cam)
          heading (:heading cam)
          up (:up cam)]
      (case type
        ;; Horizontal orbit right (clockwise from above = negative rotation around Z)
        ;; Rotate arm, heading, and up together — no pole singularity
        :rt
        (let [axis [0 0 1]
              arm (math/v- pos pivot)
              total-rad (* dist deg->rad)]
          (mapv (fn [i]
                  (let [t (/ (inc i) n-frames)
                        angle (* (- total-rad) t)
                        new-arm (math/rotate-point-around-axis arm axis angle)
                        new-pos (math/v+ pivot new-arm)]
                    {:position new-pos
                     :heading (math/rotate-around-axis heading axis angle)
                     :up (math/rotate-around-axis up axis angle)}))
                (range n-frames)))

        ;; Horizontal orbit left (counterclockwise from above)
        :lt
        (let [axis [0 0 1]
              arm (math/v- pos pivot)
              total-rad (* dist deg->rad)]
          (mapv (fn [i]
                  (let [t (/ (inc i) n-frames)
                        angle (* total-rad t)
                        new-arm (math/rotate-point-around-axis arm axis angle)
                        new-pos (math/v+ pivot new-arm)]
                    {:position new-pos
                     :heading (math/rotate-around-axis heading axis angle)
                     :up (math/rotate-around-axis up axis angle)}))
                (range n-frames)))

        ;; Vertical orbit up (elevate camera — rotate around right axis)
        :u
        (let [axis (math/normalize (math/cross heading up))
              arm (math/v- pos pivot)
              total-rad (* dist deg->rad)]
          (mapv (fn [i]
                  (let [t (/ (inc i) n-frames)
                        angle (* total-rad t)
                        new-arm (math/rotate-point-around-axis arm axis angle)
                        new-pos (math/v+ pivot new-arm)]
                    {:position new-pos
                     :heading (math/rotate-around-axis heading axis angle)
                     :up (math/rotate-around-axis up axis angle)}))
                (range n-frames)))

        ;; Vertical orbit down
        :d
        (let [axis (math/normalize (math/cross heading up))
              arm (math/v- pos pivot)
              total-rad (* dist deg->rad)]
          (mapv (fn [i]
                  (let [t (/ (inc i) n-frames)
                        angle (* (- total-rad) t)
                        new-arm (math/rotate-point-around-axis arm axis angle)
                        new-pos (math/v+ pivot new-arm)]
                    {:position new-pos
                     :heading (math/rotate-around-axis heading axis angle)
                     :up (math/rotate-around-axis up axis angle)}))
                (range n-frames)))

        ;; Dolly: move along heading (positive = toward pivot)
        :f
        (mapv (fn [i]
                (let [t (/ (inc i) n-frames)
                      new-pos (math/v+ pos (math/v* heading (* dist t)))]
                  {:position new-pos :heading heading :up up}))
              (range n-frames))

        ;; Pan: rotate heading around up axis (position fixed)
        :th
        (mapv (fn [i]
                (let [t (/ (inc i) n-frames)
                      rad (* angle t deg->rad)
                      new-heading (math/rotate-around-axis heading up rad)]
                  {:position pos :heading new-heading :up up}))
              (range n-frames))

        ;; Tilt: rotate heading and up around right axis (position fixed)
        :tv
        (let [right (math/normalize (math/cross heading up))]
          (mapv (fn [i]
                  (let [t (/ (inc i) n-frames)
                        rad (* angle t deg->rad)
                        new-heading (math/rotate-around-axis heading right rad)
                        new-up (math/rotate-around-axis up right rad)]
                    {:position pos :heading new-heading :up new-up}))
                (range n-frames)))

        ;; Roll: rotate up around heading
        :tr
        (mapv (fn [i]
                (let [t (/ (inc i) n-frames)
                      rad (* angle t deg->rad)
                      new-up (math/rotate-around-axis up heading rad)]
                  {:position pos :heading heading :up new-up}))
              (range n-frames))

        ;; Parallel group: apply all sub-commands at same fraction t
        :parallel
        (let [sub-cmds (:commands cmd)]
          (mapv (fn [i]
                  (let [t (/ (inc i) n-frames)
                        final-cam (reduce (fn [state sub-cmd]
                                            (let [scaled (case (:type sub-cmd)
                                                           (:f :u :d :rt :lt) (assoc sub-cmd :dist (* (:dist sub-cmd) t))
                                                           (:th :tv :tr) (assoc sub-cmd :angle (* (:angle sub-cmd) t))
                                                           sub-cmd)]
                                              (apply-orbital-command state scaled pivot)))
                                          cam sub-cmds)]
                    {:position (:position final-cam)
                     :heading (:heading final-cam)
                     :up (:up final-cam)}))
                (range n-frames)))

        ;; Unknown
        []))))

(defn- apply-orbital-command
  "Apply a full orbital camera command, returning updated camera state."
  [cam cmd pivot]
  (let [pos (:position cam)
        heading (:heading cam)
        up (:up cam)]
    (case (:type cmd)
      :rt (let [arm (math/v- pos pivot)
                rad (* (- (:dist cmd)) deg->rad)
                axis [0 0 1]
                new-arm (math/rotate-point-around-axis arm axis rad)
                new-pos (math/v+ pivot new-arm)]
            (assoc cam
                   :position new-pos
                   :heading (math/rotate-around-axis heading axis rad)
                   :up (math/rotate-around-axis up axis rad)))

      :lt (let [arm (math/v- pos pivot)
                rad (* (:dist cmd) deg->rad)
                axis [0 0 1]
                new-arm (math/rotate-point-around-axis arm axis rad)
                new-pos (math/v+ pivot new-arm)]
            (assoc cam
                   :position new-pos
                   :heading (math/rotate-around-axis heading axis rad)
                   :up (math/rotate-around-axis up axis rad)))

      :u (let [axis (math/normalize (math/cross heading up))
               arm (math/v- pos pivot)
               rad (* (:dist cmd) deg->rad)
               new-arm (math/rotate-point-around-axis arm axis rad)
               new-pos (math/v+ pivot new-arm)]
           (assoc cam
                  :position new-pos
                  :heading (math/rotate-around-axis heading axis rad)
                  :up (math/rotate-around-axis up axis rad)))

      :d (let [axis (math/normalize (math/cross heading up))
               arm (math/v- pos pivot)
               rad (* (- (:dist cmd)) deg->rad)
               new-arm (math/rotate-point-around-axis arm axis rad)
               new-pos (math/v+ pivot new-arm)]
           (assoc cam
                  :position new-pos
                  :heading (math/rotate-around-axis heading axis rad)
                  :up (math/rotate-around-axis up axis rad)))

      :f (assoc cam :position (math/v+ pos (math/v* heading (:dist cmd))))

      :th (let [rad (* (:angle cmd) deg->rad)]
            (assoc cam :heading (math/rotate-around-axis heading up rad)))

      :tv (let [right (math/normalize (math/cross heading up))
                rad (* (:angle cmd) deg->rad)]
            (assoc cam
                   :heading (math/rotate-around-axis heading right rad)
                   :up (math/rotate-around-axis up right rad)))

      :tr (let [rad (* (:angle cmd) deg->rad)]
            (assoc cam :up (math/rotate-around-axis up heading rad)))

      :parallel (reduce #(apply-orbital-command %1 %2 pivot) cam (:commands cmd))

      cam)))

(defn- generate-orbital-span-frames
  "Generate all frames for a single span in orbital camera mode."
  [cam span cmd-frame-counts pivot]
  (let [commands (:commands span)]
    (loop [i 0
           current-cam cam
           all-frames []]
      (if (>= i (count commands))
        {:frames all-frames
         :final-state current-cam}
        (let [cmd (nth commands i)
              n-frames (nth cmd-frame-counts i 0)]
          (if (zero? n-frames)
            (recur (inc i)
                   (apply-orbital-command current-cam cmd pivot)
                   all-frames)
            (let [frames (generate-orbital-frames current-cam cmd n-frames pivot)
                  new-cam (apply-orbital-command current-cam cmd pivot)]
              (recur (inc i)
                     new-cam
                     (into all-frames frames)))))))))

;; ============================================================
;; Main preprocessing entry point
;; ============================================================

(defn preprocess-animation
  "Convert spans + duration + fps into a vector of frame poses.
   Each frame is {:position [x y z] :heading [x y z] :up [x y z]}.
   Frames are on a LINEAR timeline; easing remaps at playback.

   opts (optional map):
     :camera-mode  - :orbital for camera animations (reinterprets commands)
     :pivot        - [x y z] orbit center (required for orbital mode)

   Returns {:frames [...] :total-frames N :span-ranges [...]}
   where span-ranges is [{:start-frame N :frame-count N} ...]
   for per-span easing lookups."
  [spans duration fps initial-pose & [opts]]
  (let [total-frames (max 1 (int (Math/ceil (* duration fps))))
        span-frame-counts (distribute-span-frames spans total-frames)
        orbital? (and (= :orbital (:camera-mode opts)) (:pivot opts))]
    (loop [span-idx 0
           cam-state (make-virtual-turtle initial-pose)
           all-frames []
           span-ranges []]
      (if (>= span-idx (count spans))
        {:frames (vec all-frames)
         :total-frames (count all-frames)
         :span-ranges span-ranges}
        (let [span (nth spans span-idx)
              span-frame-count (nth span-frame-counts span-idx)
              cmd-frame-counts (if orbital?
                                 (distribute-orbital-frames (:commands span)
                                                            span-frame-count
                                                            (:ang-velocity span 1))
                                 (distribute-frames (:commands span)
                                                    span-frame-count
                                                    (:ang-velocity span 1)))
              {:keys [frames final-state]}
              (if orbital?
                (generate-orbital-span-frames cam-state span cmd-frame-counts (:pivot opts))
                (generate-span-frames cam-state span cmd-frame-counts))
              span-range {:start-frame (count all-frames)
                          :frame-count (count frames)}]
          (recur (inc span-idx)
                 final-state
                 (into all-frames frames)
                 (conj span-ranges span-range)))))))
