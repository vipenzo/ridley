(ns ridley.editor.macros
  "Macro definitions for the SCI context.")

(def macro-defs
  ";; Atom to hold recorder during path recording
   (def ^:private path-recorder (atom nil))

   ;; Atom for attach-face and attach macros (functional style)
   (def ^:private attach-state (atom nil))

   ;; Wrapper functions for attach macros that operate on attach-state
   (defn- att-f* [dist]
     (swap! attach-state (fn [s] (turtle-f s dist))))
   (defn- att-th* [angle]
     (swap! attach-state (fn [s] (turtle-th s angle))))
   (defn- att-tv* [angle]
     (swap! attach-state (fn [s] (turtle-tv s angle))))
   (defn- att-tr* [angle]
     (swap! attach-state (fn [s] (turtle-tr s angle))))
   (defn- att-inset* [amount]
     (swap! attach-state (fn [s] (turtle-inset s amount))))
   (defn- att-scale* [factor]
     (swap! attach-state (fn [s] (turtle-scale s factor))))
   (defn- att-u* [dist]
     (swap! attach-state (fn [s] (turtle-u s dist))))
   (defn- att-d* [dist]
     (swap! attach-state (fn [s] (turtle-d s dist))))
   (defn- att-rt* [dist]
     (swap! attach-state (fn [s] (turtle-rt s dist))))
   (defn- att-lt* [dist]
     (swap! attach-state (fn [s] (turtle-lt s dist))))

   ;; Resolve name-or-mesh to actual mesh (for use before bounds/mesh? are defined)
   (defn- att-resolve-mesh [name-or-mesh]
     (if (and (map? name-or-mesh) (:vertices name-or-mesh))
       name-or-mesh
       (get-mesh (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh)))))

   ;; Compute bounds inline (for use before bounds is defined)
   (defn- att-compute-bounds [name-or-mesh]
     (when-let [m (att-resolve-mesh name-or-mesh)]
       (when-let [vertices (seq (:vertices m))]
         (let [xs (map #(nth % 0) vertices)
               ys (map #(nth % 1) vertices)
               zs (map #(nth % 2) vertices)
               min-x (apply min xs) max-x (apply max xs)
               min-y (apply min ys) max-y (apply max ys)
               min-z (apply min zs) max-z (apply max zs)]
           {:min [min-x min-y min-z]
            :max [max-x max-y max-z]
            :center [(/ (+ min-x max-x) 2)
                     (/ (+ min-y max-y) 2)
                     (/ (+ min-z max-z) 2)]
            :size [(- max-x min-x)
                   (- max-y min-y)
                   (- max-z min-z)]}))))

   ;; Move to target object's pose or centroid (works inside attach/attach!)
   ;; Default: move to creation-pose position AND adopt heading/up
   ;; With :center flag: move to centroid only, keep current heading
   (defn- att-move-to-center* [target]
     (let [dest (:center (att-compute-bounds target))
           state @attach-state
           pos (:position state)
           heading (:heading state)
           up (:up state)
           right (vec3-cross heading up)
           delta (vec3- dest pos)
           d-fwd (vec3-dot delta heading)
           d-right (vec3-dot delta right)
           d-up (vec3-dot delta up)]
       ;; Move along right axis (th -90, f, th 90)
       (when-not (zero? d-right)
         (att-th* -90) (att-f* d-right) (att-th* 90))
       ;; Move along forward axis
       (when-not (zero? d-fwd)
         (att-f* d-fwd))
       ;; Move along up axis (tv 90, f, tv -90)
       (when-not (zero? d-up)
         (att-tv* 90) (att-f* d-up) (att-tv* -90))))

   (defn- att-move-to-pose* [target]
     (let [m (att-resolve-mesh target)
           pose (when m (:creation-pose m))]
       (if pose
         (let [dest (:position pose)]
           ;; First move to the pose position using centroid-style movement
           ;; (this properly translates the attached mesh via att-f*)
           (let [state @attach-state
                 pos (:position state)
                 heading (:heading state)
                 up (:up state)
                 right (vec3-cross heading up)
                 delta (vec3- dest pos)
                 d-fwd (vec3-dot delta heading)
                 d-right (vec3-dot delta right)
                 d-up (vec3-dot delta up)]
             (when-not (zero? d-right)
               (att-th* -90) (att-f* d-right) (att-th* 90))
             (when-not (zero? d-fwd)
               (att-f* d-fwd))
             (when-not (zero? d-up)
               (att-tv* 90) (att-f* d-up) (att-tv* -90)))
           ;; Then adopt the target's heading and up
           (swap! attach-state
                  (fn [s]
                    (assoc s
                           :heading (:heading pose)
                           :up (:up pose)))))
         ;; Fallback: no creation-pose, use centroid
         (att-move-to-center* target))))

   (defn- att-move-to*
     ([target] (att-move-to-pose* target))
     ([target mode]
      (case mode
        :center (att-move-to-center* target)
        (att-move-to-pose* target))))

   ;; Recording versions that work with the path-recorder atom
   (defn- rec-f* [dist]
     (swap! path-recorder rec-f dist))
   (defn- rec-th* [angle]
     (swap! path-recorder rec-th angle))
   (defn- rec-tv* [angle]
     (swap! path-recorder rec-tv angle))
   (defn- rec-tr* [angle]
     (swap! path-recorder rec-tr angle))
   (defn- rec-set-heading* [heading up]
     (swap! path-recorder rec-set-heading heading up))

   ;; Recording version of mark - records a named point in the path
   (defn- rec-mark* [name]
     (swap! path-recorder update :recording conj {:cmd :mark :args [name]}))

   ;; Recording version of follow - splices another path's commands into current recording
   (defn- rec-follow* [path]
     (when (and (map? path) (= :path (:type path)))
       (doseq [cmd (:commands path)]
         (swap! path-recorder update :recording conj cmd))))

   ;; Recording version of resolution - sets resolution in path-recorder
   (defn- rec-resolution* [mode value]
     (swap! path-recorder assoc :resolution {:mode mode :value value}))

   ;; Recording version of arc-h that decomposes into rec-f* and rec-th*
   (defn- rec-arc-h* [radius angle & {:keys [steps]}]
     (when-not (or (zero? radius) (zero? angle))
       (let [angle-rad (* (abs angle) (/ PI 180))
             arc-length (* radius angle-rad)
             ;; Use resolution from path-recorder state
             res-mode (get-in @path-recorder [:resolution :mode] :n)
             res-value (get-in @path-recorder [:resolution :value] 16)
             actual-steps (or steps
                              (case res-mode
                                :n res-value
                                :a (max 1 (int (ceil (/ (abs angle) res-value))))
                                :s (max 1 (int (ceil (/ arc-length res-value))))))
             step-angle-deg (/ angle actual-steps)
             step-angle-rad (/ angle-rad actual-steps)
             step-dist (* 2 radius (sin (/ step-angle-rad 2)))
             half-angle (/ step-angle-deg 2)]
         ;; First: rotate half and move
         (rec-th* half-angle)
         (rec-f* step-dist)
         ;; Middle steps
         (dotimes [_ (dec actual-steps)]
           (rec-th* step-angle-deg)
           (rec-f* step-dist))
         ;; Final half rotation
         (rec-th* half-angle))))

   ;; Recording version of arc-v that decomposes into rec-f* and rec-tv*
   (defn- rec-arc-v* [radius angle & {:keys [steps]}]
     (when-not (or (zero? radius) (zero? angle))
       (let [angle-rad (* (abs angle) (/ PI 180))
             arc-length (* radius angle-rad)
             res-mode (get-in @path-recorder [:resolution :mode] :n)
             res-value (get-in @path-recorder [:resolution :value] 16)
             actual-steps (or steps
                              (case res-mode
                                :n res-value
                                :a (max 1 (int (ceil (/ (abs angle) res-value))))
                                :s (max 1 (int (ceil (/ arc-length res-value))))))
             step-angle-deg (/ angle actual-steps)
             step-angle-rad (/ angle-rad actual-steps)
             step-dist (* 2 radius (sin (/ step-angle-rad 2)))
             half-angle (/ step-angle-deg 2)]
         ;; First: rotate half and move
         (rec-tv* half-angle)
         (rec-f* step-dist)
         ;; Middle steps
         (dotimes [_ (dec actual-steps)]
           (rec-tv* step-angle-deg)
           (rec-f* step-dist))
         ;; Final half rotation
         (rec-tv* half-angle))))

   ;; Helper: normalize a 3D vector
   (defn- rec-normalize [v]
     (let [len (sqrt (+ (* (nth v 0) (nth v 0))
                        (* (nth v 1) (nth v 1))
                        (* (nth v 2) (nth v 2))))]
       (if (> len 0.0001)
         [(/ (nth v 0) len) (/ (nth v 1) len) (/ (nth v 2) len)]
         [1 0 0])))

   ;; Helper: dot product
   (defn- rec-dot [a b]
     (+ (* (nth a 0) (nth b 0))
        (* (nth a 1) (nth b 1))
        (* (nth a 2) (nth b 2))))

   ;; Helper: cross product
   (defn- rec-cross [a b]
     [(- (* (nth a 1) (nth b 2)) (* (nth a 2) (nth b 1)))
      (- (* (nth a 2) (nth b 0)) (* (nth a 0) (nth b 2)))
      (- (* (nth a 0) (nth b 1)) (* (nth a 1) (nth b 0)))])

   ;; Helper: compute th and tv angles to rotate from one heading to another
   ;; Returns [th-angle tv-angle] in degrees
   ;; Rotation order: first apply tv (pitch around right), then th (yaw around up)
   (defn- rec-compute-rotation-angles [from-heading from-up to-direction]
     (let [;; Vertical angle (tv): pitch around right axis
           ;; First, find the vertical component
           up-comp (rec-dot to-direction from-up)
           ;; Project to horizontal plane to find horizontal direction
           horiz-dir [(- (nth to-direction 0) (* up-comp (nth from-up 0)))
                      (- (nth to-direction 1) (* up-comp (nth from-up 1)))
                      (- (nth to-direction 2) (* up-comp (nth from-up 2)))]
           horiz-len (sqrt (rec-dot horiz-dir horiz-dir))
           ;; Vertical angle: angle between horizontal and actual direction
           tv-rad (atan2 up-comp horiz-len)
           tv-deg (* tv-rad (/ 180 PI))
           ;; Horizontal angle (th): yaw around up axis
           ;; Only calculate if there's horizontal component
           [th-deg] (if (> horiz-len 0.001)
                      (let [horiz-norm (rec-normalize horiz-dir)
                            fwd-comp (rec-dot horiz-norm from-heading)
                            right (rec-cross from-heading from-up)
                            right-comp (rec-dot horiz-norm right)
                            th-rad (atan2 right-comp fwd-comp)]
                        [(* (- th-rad) (/ 180 PI))])
                      [0])]
       [th-deg tv-deg]))

   ;; Recording version of bezier-as
   ;; Uses relative rotations (th/tv) instead of absolute set-heading
   ;; to produce orientation-independent paths.
   (defn- rec-bezier-as* [p & {:keys [tension steps max-segment-length cubic]}]
     (let [path-segs (path-segments-impl p)
           path-segs (if max-segment-length
                       (vec (mapcat #(subdivide-segment-impl % max-segment-length) path-segs))
                       path-segs)]
       (when (seq path-segs)
         (swap! path-recorder assoc :bezier true)
         (let [init-state @path-recorder
               init-pose (select-keys init-state [:position :heading :up])
               ;; Calculate steps based on resolution settings
               res-mode (get-in init-state [:resolution :mode] :n)
               res-value (get-in init-state [:resolution :value] 16)
               scaled-res (max 3 (round (/ res-value 3)))
               calc-steps-fn (fn [seg-length]
                               (or steps
                                   (case res-mode
                                     :n scaled-res
                                     :a scaled-res
                                     :s (max 1 (int (ceil (/ seg-length res-value)))))))
               ;; Use pure function to compute walk data
               walk-data (compute-bezier-walk-impl
                           path-segs init-pose
                           {:tension (or tension 0.33)
                            :cubic cubic
                            :calc-steps-fn calc-steps-fn})]
           ;; Apply walk steps using relative rotations
           (doseq [segment-data walk-data]
             (when-not (:degenerate segment-data)
               (doseq [step (:walk-steps segment-data)]
                 (let [{:keys [dist chord-heading final-heading final-up]} step
                       ;; Convert absolute chord-heading to relative th/tv rotations
                       current-heading (:heading @path-recorder)
                       current-up (:up @path-recorder)
                       [th-angle tv-angle] (rec-compute-rotation-angles current-heading current-up chord-heading)]
                   ;; Apply rotations then move forward
                   (when (> (abs th-angle) 0.001) (rec-th* th-angle))
                   (when (> (abs tv-angle) 0.001) (rec-tv* tv-angle))
                   (rec-f* dist)
                   ;; Rotate to final-heading for tangent continuity
                   (let [current-heading2 (:heading @path-recorder)
                         current-up2 (:up @path-recorder)
                         [th2 tv2] (rec-compute-rotation-angles current-heading2 current-up2 final-heading)]
                     (when (> (abs th2) 0.001) (rec-th* th2))
                     (when (> (abs tv2) 0.001) (rec-tv* tv2)))))))))))




   ;; Recording version of bezier-to
   ;; Decomposes bezier into f movements with th/tv rotations
   (defn- rec-bezier-to* [target & args]
     (let [grouped (group-by vector? args)
           control-points (get grouped true)
           options (get grouped false)
           steps (get (apply hash-map (flatten options)) :steps)
           state @path-recorder
           p0 (:position state)
           start-heading (:heading state)
           p3 (vec target)
           dx0 (- (nth p3 0) (nth p0 0))
           dy0 (- (nth p3 1) (nth p0 1))
           dz0 (- (nth p3 2) (nth p0 2))
           approx-length (sqrt (+ (* dx0 dx0) (* dy0 dy0) (* dz0 dz0)))]
       (when (> approx-length 0.001)
         (swap! path-recorder assoc :bezier true)
         (let [res-mode (get-in state [:resolution :mode] :n)
               res-value (get-in state [:resolution :value] 16)
               actual-steps (or steps
                                (case res-mode
                                  :n res-value
                                  :a res-value
                                  :s (max 1 (int (ceil (/ approx-length res-value))))))
               n-controls (count control-points)
               ;; Compute control points
               ;; User-provided control points are ABSOLUTE coordinates (same as turtle/bezier-to)
               [c1 c2] (cond
                         (= n-controls 2) [(vec (first control-points))
                                           (vec (second control-points))]
                         (= n-controls 1) (let [cp (vec (first control-points))]
                                            [cp cp])
                         :else ;; Auto control points
                         (let [heading start-heading]
                           [(mapv + p0 (mapv #(* % (* approx-length 0.33)) heading))
                            (let [to-start (rec-normalize [(- (nth p0 0) (nth p3 0))
                                                           (- (nth p0 1) (nth p3 1))
                                                           (- (nth p0 2) (nth p3 2))])]
                              (mapv + p3 (mapv #(* % (* approx-length 0.33)) to-start)))]))
               ;; Bezier point function
               cubic-point (fn [t]
                             (let [t2 (- 1 t)
                                   a (* t2 t2 t2) b (* 3 t2 t2 t) c (* 3 t2 t t) d (* t t t)]
                               [(+ (* a (nth p0 0)) (* b (nth c1 0)) (* c (nth c2 0)) (* d (nth p3 0)))
                                (+ (* a (nth p0 1)) (* b (nth c1 1)) (* c (nth c2 1)) (* d (nth p3 1)))
                                (+ (* a (nth p0 2)) (* b (nth c1 2)) (* c (nth c2 2)) (* d (nth p3 2)))]))
               ;; Precompute all bezier points
               points (mapv #(cubic-point (/ % actual-steps)) (range (inc actual-steps)))
               ;; Precompute all segment directions and distances
               segments (vec (for [i (range actual-steps)]
                               (let [curr-pos (nth points i)
                                     next-pos (nth points (inc i))
                                     dx (- (nth next-pos 0) (nth curr-pos 0))
                                     dy (- (nth next-pos 1) (nth curr-pos 1))
                                     dz (- (nth next-pos 2) (nth curr-pos 2))
                                     dist (sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
                                 {:dir (if (> dist 0.001) (rec-normalize [dx dy dz]) nil)
                                  :dist dist})))]
           ;; Walk through segments using rotation-minimizing frame
           ;; This propagates the up vector smoothly to avoid twist/concave faces
           (loop [remaining-segments segments
                  current-up (:up state)]
             (when (seq remaining-segments)
               (let [{:keys [dir dist]} (first remaining-segments)]
                 (if (and dir (> dist 0.001))
                   (let [;; Rotation-minimizing frame: project current up onto plane perpendicular to new heading
                         ;; new_up = normalize(current_up - (current_up · dir) * dir)
                         dot-product (rec-dot current-up dir)
                         projected [(- (nth current-up 0) (* dot-product (nth dir 0)))
                                    (- (nth current-up 1) (* dot-product (nth dir 1)))
                                    (- (nth current-up 2) (* dot-product (nth dir 2)))]
                         proj-len (sqrt (rec-dot projected projected))
                         new-up (if (> proj-len 0.001)
                                  (rec-normalize projected)
                                  ;; Fallback: compute perpendicular using cross product
                                  (let [right (rec-cross dir current-up)
                                        right-len (sqrt (rec-dot right right))]
                                    (if (> right-len 0.001)
                                      (rec-normalize (rec-cross right dir))
                                      current-up)))]
                     ;; Rotate to segment direction using relative th/tv
                     (let [cur-heading (:heading @path-recorder)
                           cur-up (:up @path-recorder)
                           [th-angle tv-angle] (rec-compute-rotation-angles cur-heading cur-up dir)]
                       (when (> (abs th-angle) 0.001) (rec-th* th-angle))
                       (when (> (abs tv-angle) 0.001) (rec-tv* tv-angle)))
                     ;; Move forward
                     (rec-f* dist)
                     ;; Continue with next segment, propagating the up vector
                     (recur (rest remaining-segments) new-up))
                   ;; Skip zero-length segment, keep current up
                   (recur (rest remaining-segments) current-up)))))))))

   ;; Recording version of bezier-to-anchor
   ;; Like rec-bezier-to* but gets target from anchor and uses both headings
   ;; IMPORTANT: Anchor positions are in world coordinates, but the path-recorder
   ;; works in local coordinates starting at [0,0,0]. We must compute the
   ;; relative position from the turtle's current world position to the anchor.
   (defn- rec-bezier-to-anchor* [anchor-name & args]
     (when-let [anchor (get-anchor anchor-name)]
       (let [;; Get turtle's world position to compute relative target
             turtle-pos (turtle-position)
             anchor-pos (:position anchor)
             ;; Compute relative position from turtle to anchor
             relative-target [(- (nth anchor-pos 0) (nth turtle-pos 0))
                              (- (nth anchor-pos 1) (nth turtle-pos 1))
                              (- (nth anchor-pos 2) (nth turtle-pos 2))]
             grouped (group-by vector? args)
             control-points (get grouped true)
             options (get grouped false)
             opts-map (apply hash-map (flatten options))
             steps (get opts-map :steps)
             tension (get opts-map :tension 0.33)  ; default tension
             n-controls (count control-points)]
         (if (> n-controls 0)
           ;; Explicit control points provided - delegate to rec-bezier-to*
           ;; Use relative target (control points should also be relative)
           (apply rec-bezier-to* relative-target args)
           ;; Auto control points - use both headings for smooth connection
           (let [state @path-recorder
                 p0 (:position state)
                 ;; p3 is anchor position relative to turtle start (NOT relative to p0!)
                 ;; When extruded, path origin aligns with turtle, so anchor should be at anchor-turtle offset
                 p3 relative-target
                 ;; Use path-recorder's heading for start direction (tangent to path at current point)
                 start-heading (:heading state)
                 target-heading (:heading anchor)
                 dx0 (- (nth p3 0) (nth p0 0))
                 dy0 (- (nth p3 1) (nth p0 1))
                 dz0 (- (nth p3 2) (nth p0 2))
                 approx-length (sqrt (+ (* dx0 dx0) (* dy0 dy0) (* dz0 dz0)))]
             (when (> approx-length 0.001)
               (swap! path-recorder assoc :bezier true)
               (let [res-mode (get-in state [:resolution :mode] :n)
                     res-value (get-in state [:resolution :value] 16)
                     actual-steps (or steps
                                      (case res-mode
                                        :n res-value
                                        :a res-value
                                        :s (max 1 (int (ceil (/ approx-length res-value))))))
                     ;; Auto control points using BOTH headings, with tension
                     c1 (mapv + p0 (mapv #(* % (* approx-length tension)) start-heading))
                     ;; c2 extends from target in opposite direction of target heading
                     c2 (mapv + p3 (mapv #(* % (* approx-length (- tension))) target-heading))
                     ;; Bezier point function
                     cubic-point (fn [t]
                                   (let [t2 (- 1 t)
                                         a (* t2 t2 t2) b (* 3 t2 t2 t) c (* 3 t2 t t) d (* t t t)]
                                     [(+ (* a (nth p0 0)) (* b (nth c1 0)) (* c (nth c2 0)) (* d (nth p3 0)))
                                      (+ (* a (nth p0 1)) (* b (nth c1 1)) (* c (nth c2 1)) (* d (nth p3 1)))
                                      (+ (* a (nth p0 2)) (* b (nth c1 2)) (* c (nth c2 2)) (* d (nth p3 2)))]))
                     ;; Precompute all bezier points
                     points (mapv #(cubic-point (/ % actual-steps)) (range (inc actual-steps)))
                     ;; Precompute all segment directions and distances
                     segments (vec (for [i (range actual-steps)]
                                     (let [curr-pos (nth points i)
                                           next-pos (nth points (inc i))
                                           dx (- (nth next-pos 0) (nth curr-pos 0))
                                           dy (- (nth next-pos 1) (nth curr-pos 1))
                                           dz (- (nth next-pos 2) (nth curr-pos 2))
                                           dist (sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
                                       {:dir (if (> dist 0.001) (rec-normalize [dx dy dz]) nil)
                                        :dist dist})))]
                 ;; Walk through segments using rotation-minimizing frame
                 ;; Use path-recorder's up vector (maintains path's local frame)
                 ;; IMPORTANT: First segment uses start-heading for smooth connection
                 (loop [remaining-segments segments
                        current-up (:up state)
                        first-segment? true]
                   (when (seq remaining-segments)
                     (let [{:keys [dir dist]} (first remaining-segments)]
                       (if (and dir (> dist 0.001))
                         ;; Use start-heading for first segment to avoid discontinuity
                         (let [effective-dir (if first-segment? start-heading dir)
                               dot-product (rec-dot current-up effective-dir)
                               projected [(- (nth current-up 0) (* dot-product (nth effective-dir 0)))
                                          (- (nth current-up 1) (* dot-product (nth effective-dir 1)))
                                          (- (nth current-up 2) (* dot-product (nth effective-dir 2)))]
                               proj-len (sqrt (rec-dot projected projected))
                               new-up (if (> proj-len 0.001)
                                        (rec-normalize projected)
                                        (let [right (rec-cross effective-dir current-up)
                                              right-len (sqrt (rec-dot right right))]
                                          (if (> right-len 0.001)
                                            (rec-normalize (rec-cross right effective-dir))
                                            current-up)))]
                           ;; Rotate to segment direction using relative th/tv
                           (let [cur-heading (:heading @path-recorder)
                                 cur-up (:up @path-recorder)
                                 [th-angle tv-angle] (rec-compute-rotation-angles cur-heading cur-up effective-dir)]
                             (when (> (abs th-angle) 0.001) (rec-th* th-angle))
                             (when (> (abs tv-angle) 0.001) (rec-tv* tv-angle)))
                           (rec-f* dist)
                           (recur (rest remaining-segments) new-up false))
                         (recur (rest remaining-segments) current-up false))))))))))))

   ;; path: record turtle movements for later replay
   ;; (def p (path (f 20) (th 90) (f 20))) - record a path
   ;; (def p (path (dotimes [_ 4] (f 20) (th 90)))) - with arbitrary code
   ;; Returns a path object that can be used in extrude/loft
   (defmacro path [& body]
     `(let [saved# @path-recorder]
        (reset! path-recorder (make-recorder))
        ;; Copy resolution and joint-mode from global turtle
        (swap! path-recorder assoc
               :resolution (get-turtle-resolution)
               :joint-mode (get-turtle-joint-mode))
        (let [~'f rec-f*
              ~'th rec-th*
              ~'tv rec-tv*
              ~'tr rec-tr*
              ~'arc-h rec-arc-h*
              ~'arc-v rec-arc-v*
              ~'bezier-to rec-bezier-to*
              ~'bezier-to-anchor rec-bezier-to-anchor*
              ~'bezier-as rec-bezier-as*
              ~'resolution rec-resolution*
              ~'mark rec-mark*
              ~'follow rec-follow*]
          ~@body)
        (let [rec-state# @path-recorder
              result# (path-from-recorder rec-state#)
              result# (if (:bezier rec-state#) (assoc result# :bezier true) result#)]
          (reset! path-recorder saved#)
          result#)))

   ;; shape: create a 2D shape from turtle movements
   ;; (def tri (shape (f 4) (th 120) (f 4) (th 120) (f 4))) - triangle
   ;; (def tri (shape (f 4) (th 120) (f 4))) - same, auto-closes
   ;; To convert a path to shape, use: (path-to-shape my-path)
   ;; Uses a 2D turtle starting at origin, facing +X
   ;; Only f and th are allowed (2D plane)
   ;; Returns a shape that can be used in extrude/loft/revolve
   (defmacro shape [& body]
     `(let [state# (atom (recording-turtle))
            ~'f (fn [d#] (swap! state# shape-rec-f d#))
            ~'th (fn [a#] (swap! state# shape-rec-th a#))
            ~'tv (fn [& _#] (throw (js/Error. \"tv not allowed in shape - 2D only\")))
            ~'tr (fn [& _#] (throw (js/Error. \"tr not allowed in shape - 2D only\")))]
        ~@body
        (shape-from-recording @state#)))

   ;; pen is now only for mode changes: (pen :off), (pen :on)
   ;; No longer handles shapes - use extrude for that
   (defmacro pen [mode]
     `(pen-impl ~mode))

   ;; run-path: execute a path's movements on the implicit turtle
   ;; Used internally by extrude/loft when given a path
   (defn run-path [p]
     (doseq [{:keys [cmd args]} (:commands p)]
       (case cmd
         :f  (f (first args))
         :th (th (first args))
         :tv (tv (first args))
         :tr (tr (first args))
         nil)))

   ;; extrude: create mesh by extruding shape along a path
   ;; PURE: returns mesh without side effects (use register to make visible)
   ;; (extrude (circle 15) (f 30)) - extrude circle 30 units forward
   ;; (extrude (circle 15) my-path) - extrude along a recorded path
   ;; (extrude (circle 15) (path-to :target)) - extrude along path to anchor
   ;; (extrude (rect 20 10) (f 20) (th 45) (f 20)) - sweep with turns
   ;; Returns the created mesh (bind with def, show with register)
   (defmacro extrude [shape & movements]
     (if (= 1 (count movements))
       (let [arg (first movements)]
         (cond
           ;; Symbol - might be a pre-defined path, check at runtime
           (symbol? arg)
           `(let [arg# ~arg]
              (if (path? arg#)
                (pure-extrude-path ~shape arg#)
                ;; Not a path - wrap in path macro
                (pure-extrude-path ~shape (path ~arg))))

           ;; List starting with path or path-to - use directly
           (and (list? arg) (contains? #{'path 'path-to} (first arg)))
           `(pure-extrude-path ~shape ~arg)

           ;; List starting with turtle movement - wrap in path
           ;; This avoids evaluating (f 20) directly which would modify turtle-atom
           (and (list? arg) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to 'bezier-to-anchor 'bezier-as} (first arg)))
           `(pure-extrude-path ~shape (path ~arg))

           ;; Any other expression - check at runtime if it's already a path
           :else
           `(let [result# ~arg]
              (if (path? result#)
                (pure-extrude-path ~shape result#)
                (pure-extrude-path ~shape (path ~arg))))))
       ;; Multiple movements - wrap in path macro
       `(pure-extrude-path ~shape (path ~@movements))))

   ;; extrude-closed: like extrude but creates a closed torus-like mesh
   ;; (extrude-closed (circle 5) square-path) - closed torus along path
   ;; The path should return to the starting point for proper closure
   ;; Last ring connects to first ring, no end caps
   ;; Returns the created mesh (can be bound with def)
   ;; Uses pre-processed path approach for correct corner geometry
   (defmacro extrude-closed [shape & movements]
     (if (= 1 (count movements))
       (let [path-expr (first movements)]
         (cond
           ;; Symbol - use directly (should be a path)
           (symbol? path-expr)
           `(extrude-closed-path-impl ~shape ~path-expr)

           ;; List starting with path - use directly
           (and (list? path-expr) (= 'path (first path-expr)))
           `(extrude-closed-path-impl ~shape ~path-expr)

           ;; List starting with turtle movement - wrap in path
           ;; This avoids evaluating commands directly which would modify turtle-atom
           (and (list? path-expr) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to 'bezier-to-anchor 'bezier-as} (first path-expr)))
           `(extrude-closed-path-impl ~shape (path ~path-expr))

           ;; Other list - check at runtime if it's already a path
           :else
           `(let [result# ~path-expr]
              (if (path? result#)
                (extrude-closed-path-impl ~shape result#)
                (extrude-closed-path-impl ~shape (path ~path-expr))))))
       ;; Multiple movements - wrap in path macro
       `(extrude-closed-path-impl ~shape (path ~@movements))))

   ;; loft: like extrude but with shape transformation based on progress
   ;; PURE: returns mesh without side effects (use register to make visible)
   ;; (loft (circle 20) #(scale %1 (- 1 %2)) (f 30)) - cone (transform function)
   ;; (loft (circle 20) (circle 10) (f 40)) - taper (two shapes)
   ;; (loft (circle 20) #(scale %1 (- 1 %2)) my-path) - cone along path
   ;; (loft (rect 20 10) #(rotate-shape %1 (* %2 90)) (f 30)) - twist
   ;; Transform fn receives (shape t) where t goes from 0 to 1
   ;; Default: 16 steps
   ;; Returns the created mesh (can be bound with def)
   (defmacro loft [shape transform-fn-or-shape & movements]
     (if (= 1 (count movements))
       (let [arg (first movements)]
         (cond
           ;; Symbol - might be a pre-defined path, check at runtime
           (symbol? arg)
           `(let [arg# ~arg
                  tfn# ~transform-fn-or-shape]
              (if (shape? tfn#)
                ;; Two-shape loft
                (if (path? arg#)
                  (pure-loft-two-shapes ~shape tfn# arg#)
                  (pure-loft-two-shapes ~shape tfn# (path ~arg)))
                ;; Transform function loft
                (if (path? arg#)
                  (pure-loft-path ~shape tfn# arg#)
                  (pure-loft-path ~shape tfn# (path ~arg)))))

           ;; List starting with path - use directly
           (and (list? arg) (= 'path (first arg)))
           `(let [tfn# ~transform-fn-or-shape]
              (if (shape? tfn#)
                (pure-loft-two-shapes ~shape tfn# ~arg)
                (pure-loft-path ~shape tfn# ~arg)))

           ;; List starting with turtle movement - wrap in path
           (and (list? arg) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to 'bezier-to-anchor 'bezier-as} (first arg)))
           `(let [tfn# ~transform-fn-or-shape]
              (if (shape? tfn#)
                (pure-loft-two-shapes ~shape tfn# (path ~arg))
                (pure-loft-path ~shape tfn# (path ~arg))))

           ;; Any other expression - check at runtime if it's a path
           :else
           `(let [result# ~arg
                  tfn# ~transform-fn-or-shape]
              (if (shape? tfn#)
                (if (path? result#)
                  (pure-loft-two-shapes ~shape tfn# result#)
                  (pure-loft-two-shapes ~shape tfn# (path ~arg)))
                (if (path? result#)
                  (pure-loft-path ~shape tfn# result#)
                  (pure-loft-path ~shape tfn# (path ~arg)))))))
       ;; Multiple movements - wrap in path macro
       `(let [tfn# ~transform-fn-or-shape]
          (if (shape? tfn#)
            (pure-loft-two-shapes ~shape tfn# (path ~@movements))
            (pure-loft-path ~shape tfn# (path ~@movements))))))

   ;; loft-n: loft with custom step count
   ;; (loft-n 32 (circle 20) #(scale %1 (- 1 %2)) (f 30)) - smoother cone
   ;; Returns the created mesh (can be bound with def)
   (defmacro loft-n [steps shape transform-fn & movements]
     (if (= 1 (count movements))
       (let [arg (first movements)]
         (cond
           ;; Symbol - might be a pre-defined path, check at runtime
           (symbol? arg)
           `(let [arg# ~arg]
              (if (path? arg#)
                (pure-loft-path ~shape ~transform-fn arg# ~steps)
                (pure-loft-path ~shape ~transform-fn (path ~arg) ~steps)))

           ;; List starting with path - use directly
           (and (list? arg) (= 'path (first arg)))
           `(pure-loft-path ~shape ~transform-fn ~arg ~steps)

           ;; List starting with turtle movement - wrap in path
           (and (list? arg) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to 'bezier-as} (first arg)))
           `(pure-loft-path ~shape ~transform-fn (path ~arg) ~steps)

           ;; Any other expression - check at runtime if it's a path
           :else
           `(let [result# ~arg]
              (if (path? result#)
                (pure-loft-path ~shape ~transform-fn result# ~steps)
                (pure-loft-path ~shape ~transform-fn (path ~arg) ~steps)))))
       ;; Multiple movements - wrap in path macro
       `(pure-loft-path ~shape ~transform-fn (path ~@movements) ~steps)))

   ;; bloft: bezier-safe loft that handles self-intersecting paths
   ;; Uses convex hulls for intersecting sections, then unions all pieces.
   ;; Best for tight curves like (bezier-as (branch-path 30))
   ;; (bloft (circle 10) identity my-bezier-path)
   ;; (bloft (rect 3 3) #(scale %1 0.5) (bezier-as (branch-path 30)))
   ;; (bloft-n 64 (circle 10) identity my-bezier-path) - more steps
   (defmacro bloft
     ([shape transform-fn bezier-path]
      `(bloft ~shape ~transform-fn ~bezier-path nil 0.1))
     ([shape transform-fn bezier-path steps]
      `(bloft ~shape ~transform-fn ~bezier-path ~steps 0.1))
     ([shape transform-fn bezier-path steps threshold]
      (cond
        ;; Symbol - check at runtime if it's a path
        (symbol? bezier-path)
        `(let [p# ~bezier-path]
           (if (path? p#)
             (pure-bloft ~shape ~transform-fn p# ~steps ~threshold)
             (pure-bloft ~shape ~transform-fn (path ~bezier-path) ~steps ~threshold)))

        ;; List starting with path - use directly
        (and (list? bezier-path) (= 'path (first bezier-path)))
        `(pure-bloft ~shape ~transform-fn ~bezier-path ~steps ~threshold)

        ;; List starting with bezier-as or other movement - wrap in path
        (and (list? bezier-path) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to 'bezier-as} (first bezier-path)))
        `(pure-bloft ~shape ~transform-fn (path ~bezier-path) ~steps ~threshold)

        ;; Any other expression - check at runtime
        :else
        `(let [p# ~bezier-path]
           (if (path? p#)
             (pure-bloft ~shape ~transform-fn p# ~steps ~threshold)
             (pure-bloft ~shape ~transform-fn (path ~bezier-path) ~steps ~threshold))))))

   (defmacro bloft-n [steps shape transform-fn bezier-path]
     `(bloft ~shape ~transform-fn ~bezier-path ~steps 0.1))

   ;; revolve: create solid of revolution (lathe operation)
   ;; PURE: returns mesh without side effects (use register to make visible)
   ;; (revolve (shape (f 8) (th 90) (f 10) (th 90) (f 8)))  ; solid cylinder
   ;; (revolve (circle 5))         ; torus (circle revolved around axis)
   ;; (revolve profile 180)        ; half revolution
   ;; The profile is interpreted as:
   ;; - 2D X = radial distance from axis (perpendicular to heading)
   ;; - 2D Y = position along axis (in heading direction)
   ;; Returns the created mesh (can be bound with def)
   (defmacro revolve
     ([shape]
      `(pure-revolve ~shape 360))
     ([shape angle]
      `(pure-revolve ~shape ~angle)))

   ;; ============================================================
   ;; Functional attach macros
   ;; ============================================================

   ;; attach-face: move existing face vertices (no extrusion)
   ;; (attach-face mesh face-id & body) => modified mesh
   ;; Body operations (f, th, tv, tr, inset, scale) are rebound to operate
   ;; on a local attach-state atom, returning the modified mesh at the end.
   ;; f moves the face vertices directly without creating new geometry.
   (defmacro attach-face [mesh face-id & body]
     `(let [m# ~mesh
            _# (reset! attach-state
                       (-> (turtle)
                           (turtle-attach-face m# ~face-id)))]
        ;; Rebind operations to local versions and execute body
        (let [~'f att-f*
              ~'th att-th*
              ~'tv att-tv*
              ~'tr att-tr*
              ~'u att-u*
              ~'d att-d*
              ~'rt att-rt*
              ~'lt att-lt*
              ~'inset att-inset*
              ~'scale att-scale*
              ~'move-to att-move-to*]
          ~@body)
        ;; Return modified mesh
        (or (get-in @attach-state [:attached :mesh]) m#)))

   ;; clone-face: extrude face creating new vertices and side faces
   ;; (clone-face mesh face-id & body) => modified mesh with extrusion
   ;; f creates new vertices offset from original and side faces connecting them.
   (defmacro clone-face [mesh face-id & body]
     `(let [m# ~mesh
            _# (reset! attach-state
                       (-> (turtle)
                           (turtle-attach-face-extrude m# ~face-id)))]
        ;; Rebind operations to local versions and execute body
        (let [~'f att-f*
              ~'th att-th*
              ~'tv att-tv*
              ~'tr att-tr*
              ~'u att-u*
              ~'d att-d*
              ~'rt att-rt*
              ~'lt att-lt*
              ~'inset att-inset*
              ~'scale att-scale*
              ~'move-to att-move-to*]
          ~@body)
        ;; Return modified mesh
        (or (get-in @attach-state [:attached :mesh]) m#)))

   ;; attach: transform mesh/panel in place (modifies original)
   ;; (attach mesh & body) => transformed mesh
   ;; (attach panel & body) => panel repositioned to final turtle position
   ;; Attaches to mesh's creation pose and applies transformations.
   (defmacro attach [mesh & body]
     `(let [m# ~mesh]
        (if (panel? m#)
          ;; Panel handling: start from current turtle, run movements, reposition panel
          (do
            (reset! attach-state (turtle))
            (let [~'f att-f*
                  ~'th att-th*
                  ~'tv att-tv*
                  ~'tr att-tr*
                  ~'u att-u*
                  ~'d att-d*
                  ~'rt att-rt*
                  ~'lt att-lt*
                  ~'move-to att-move-to*]
              ~@body)
            ;; Return panel with updated position/heading/up from final attach-state
            (let [final-state @attach-state]
              (assoc m#
                :position (:position final-state)
                :heading (:heading final-state)
                :up (:up final-state))))
          ;; Mesh handling: original behavior
          (do
            (reset! attach-state
                    (-> (turtle)
                        (turtle-attach-move m#)))
            (let [~'f att-f*
                  ~'th att-th*
                  ~'tv att-tv*
                  ~'tr att-tr*
                  ~'u att-u*
                  ~'d att-d*
                  ~'rt att-rt*
                  ~'lt att-lt*
                  ~'move-to att-move-to*]
              ~@body)
            (or (get-in @attach-state [:attached :mesh]) m#)))))

   ;; attach!: transform a registered mesh in-place by keyword
   ;; (attach! :name (f 20) (th 45)) => re-registers the transformed mesh
   ;; Equivalent to (register name (attach name (f 20) (th 45)))
   (defmacro attach! [kw & body]
     `(let [mesh# (get-mesh ~kw)
            _# (when-not mesh#
                  (throw (js/Error. (str \"attach! - no registered mesh named \" ~kw))))
            _# (reset! attach-state
                       (-> (turtle)
                           (turtle-attach-move mesh#)))]
        (let [~'f att-f*
              ~'th att-th*
              ~'tv att-tv*
              ~'tr att-tr*
              ~'u att-u*
              ~'d att-d*
              ~'rt att-rt*
              ~'lt att-lt*
              ~'move-to att-move-to*]
          ~@body)
        (let [result# (or (get-in @attach-state [:attached :mesh]) mesh#)]
          (register-mesh! ~kw result#)
          (refresh-viewport! false)
          result#)))

   ;; play-path: replay a recorded path on the attach-state turtle.
   ;; Use inside attach/attach! body to follow a pre-built path:
   ;;   (attach mesh (play-path my-path) (f 10))
   ;;   (attach! :name (play-path my-path))
   (defn play-path [p]
     (when (path? p)
       (doseq [{:keys [cmd args]} (:commands p)]
         (case cmd
           :f  (att-f* (first args))
           :th (att-th* (first args))
           :tv (att-tv* (first args))
           :tr (att-tr* (first args))
           :set-heading (swap! attach-state
                          (fn [s] (assoc s
                                    :heading (vec3-normalize (first args))
                                    :up (vec3-normalize (second args)))))
           nil))))

   ;; Helper to check if something is a mesh (has :vertices and :faces)
   (defn mesh? [x]
     (and (map? x) (:vertices x) (:faces x)))

   ;; Helper to check if x is a vector/seq of meshes
   (defn mesh-vector? [x]
     (and (or (vector? x) (seq? x))
          (seq x)
          (mesh? (first x))))

   ;; Helper to check if x is a map of meshes (not a mesh itself)
   (defn mesh-map? [x]
     (and (map? x)
          (not (:vertices x))
          (seq x)
          (mesh? (first (vals x)))))

   (defn flatten-meshes
     \"Recursively flatten nested sequences/vectors into a flat vector of meshes.
      Non-mesh elements are silently skipped.\"
     [x]
     (cond
       (mesh? x) [x]
       (or (vector? x) (seq? x))
       (vec (mapcat flatten-meshes x))
       :else []))

   ;; register: define a symbol, add to registry, AND show it
   ;; Works with meshes, paths, shapes, and collections of meshes:
   ;; (register torus (extrude ...))  ; registers and shows a mesh
   ;; (register line (path ...))      ; registers a path (shown as polyline)
   ;; (register parts (for [...] ...)) ; registers vector of meshes
   ;; (register robot {:hand m1 :body m2}) ; registers map of meshes
   ;; (register torus (extrude ...) :hidden) ; registers but keeps hidden
   ;; On subsequent evals, updates the value but preserves visibility state
   (defmacro register [name expr & opts]
     `(let [raw-value# ~expr
            name-kw# ~(keyword name)
            hidden?# ~(contains? (set opts) :hidden)
            ;; Convert lazy seqs to vectors, flatten nested sequences of meshes
            value# (let [v# raw-value#]
                     (cond
                       ;; Already a mesh or map — leave as-is
                       (mesh? v#) v#
                       (mesh-map? v#) v#
                       ;; Sequence/vector — must contain only meshes
                       (and (or (seq? v#) (vector? v#)) (seq v#))
                       (let [items# (vec v#)]
                         (when-not (every? mesh? items#)
                           (throw (js/Error.
                                   (str \"register: expected a mesh or vector of meshes, but got a vector containing \"
                                        (type (first (remove mesh? items#)))))))
                         items#)
                       ;; Anything else — leave as-is
                       :else v#))]
        ;; For panels, add :name before def so out/append/clear work
        (if (and (map? value#) (= :panel (:type value#)))
          ;; Panel case - add name to value, def it, register it
          (let [panel-with-name# (assoc value# :name name-kw#)]
            (def ~name panel-with-name#)
            (register-panel! name-kw# panel-with-name#)
            (when hidden?# (hide-panel! name-kw#))
            (refresh-viewport! false)
            panel-with-name#)
          ;; Non-panel cases
          (do
            (def ~name value#)
            (cond
              ;; Single mesh (has :vertices)
              (and (map? value#) (:vertices value#))
              (let [already-registered# (contains? (set (registered-names)) name-kw#)]
                (register-mesh! name-kw# value#)
                (if hidden?#
                  (hide-mesh! name-kw#)
                  (when-not already-registered#
                    (show-mesh! name-kw#))))

              ;; Shape (has :type :shape)
              (and (map? value#) (= :shape (:type value#)))
              (register-shape! name-kw# value#)

              ;; Vector of meshes - add each anonymously
              (mesh-vector? value#)
              (doseq [mesh# value#]
                (add-mesh! mesh#))

              ;; Map of meshes - add each anonymously
              (mesh-map? value#)
              (doseq [[_# mesh#] value#]
                (add-mesh! mesh#))

              ;; Path (has :type :path) — abstract, no visibility
              (and (map? value#) (= :path (:type value#)))
              (register-path! name-kw# value#))
            ;; Refresh viewport and return value
            (refresh-viewport! false)
            value#))))

   ;; r: short alias for register
   (defmacro r [name expr & opts]
     `(register ~name ~expr ~@opts))

   ;; with-path: pin a path at current turtle pose, resolve marks as anchors
   ;; (with-path skeleton (goto :shoulder) (bezier-to-anchor :elbow))
   ;; Supports nesting — inner with-path shadows outer anchors, restores on exit
   (defmacro with-path [path-expr & body]
     `(let [saved-anchors# (save-anchors*)]
        (resolve-and-merge-marks* ~path-expr)
        (let [result# (do ~@body)]
          (restore-anchors* saved-anchors#)
          result#)))

   ;; qp: short alias for quick-path
   (def qp quick-path)

   ;; Convenience functions that work with names, mesh references, or collections
   ;; (show :torus)       - by registered name (keyword)
   ;; (show torus)        - by mesh reference
   ;; (show parts)        - show all meshes in vector/map
   ;; (show parts 2)      - show specific element by index
   ;; (show robot :hand)  - show specific element by key
   (defn show
     ([name-or-coll]
      (cond
        ;; Name (keyword/string/symbol) - try mesh and panel
        (or (keyword? name-or-coll) (string? name-or-coll) (symbol? name-or-coll))
        (let [kw (if (keyword? name-or-coll) name-or-coll (keyword name-or-coll))]
          (show-mesh! kw)
          (show-panel! kw))
        ;; Panel reference
        (panel? name-or-coll)
        (when-let [n (:name name-or-coll)]
          (show-panel! n))
        ;; Vector of meshes - show all
        (mesh-vector? name-or-coll)
        (doseq [m name-or-coll] (show-mesh-ref! m))
        ;; Map of meshes - show all
        (mesh-map? name-or-coll)
        (doseq [[_ m] name-or-coll] (show-mesh-ref! m))
        ;; Single mesh reference
        :else (show-mesh-ref! name-or-coll))
      (refresh-viewport! false))
     ([coll key]
      (when-let [m (get coll key)]
        (show-mesh-ref! m))
      (refresh-viewport! false)))

   (defn hide
     ([name-or-coll]
      (cond
        ;; Name (keyword/string/symbol) - try mesh and panel
        (or (keyword? name-or-coll) (string? name-or-coll) (symbol? name-or-coll))
        (let [kw (if (keyword? name-or-coll) name-or-coll (keyword name-or-coll))]
          (hide-mesh! kw)
          (hide-panel! kw))
        ;; Panel reference
        (panel? name-or-coll)
        (when-let [n (:name name-or-coll)]
          (hide-panel! n))
        ;; Vector of meshes - hide all
        (mesh-vector? name-or-coll)
        (doseq [m name-or-coll] (hide-mesh-ref! m))
        ;; Map of meshes - hide all
        (mesh-map? name-or-coll)
        (doseq [[_ m] name-or-coll] (hide-mesh-ref! m))
        ;; Single mesh reference
        :else (hide-mesh-ref! name-or-coll))
      (refresh-viewport! false)
      nil)
     ([coll key]
      (when-let [m (get coll key)]
        (hide-mesh-ref! m))
      (refresh-viewport! false)
      nil))

   (defn show-all []
     (show-all!)
     (refresh-viewport! false))  ; Don't reset camera

   (defn hide-all []
     (hide-all!)
     (refresh-viewport! false))  ; Don't reset camera

   ;; Show only registered objects (hide work-in-progress meshes)
   (defn show-only-objects []
     (show-only-registered!)
     (refresh-viewport! false))  ; Don't reset camera

   ;; List visible object names
   (defn objects []
     (visible-names))

   ;; List all registered object names (visible and hidden)
   (defn registered []
     (registered-names))

   ;; List all meshes in scene (registered + anonymous)
   (defn scene []
     (all-meshes-info))

   ;; Compute bounding box from mesh vertices
   ;; Returns {:min [x y z] :max [x y z] :center [x y z] :size [sx sy sz]}
   (defn- compute-bounds [vertices]
     (when (seq vertices)
       (let [xs (map #(nth % 0) vertices)
             ys (map #(nth % 1) vertices)
             zs (map #(nth % 2) vertices)
             min-x (apply min xs) max-x (apply max xs)
             min-y (apply min ys) max-y (apply max ys)
             min-z (apply min zs) max-z (apply max zs)]
         {:min [min-x min-y min-z]
          :max [max-x max-y max-z]
          :center [(/ (+ min-x max-x) 2)
                   (/ (+ min-y max-y) 2)
                   (/ (+ min-z max-z) 2)]
          :size [(- max-x min-x)
                 (- max-y min-y)
                 (- max-z min-z)]})))

   ;; Get info/details about a mesh or collection
   ;; (info :torus)       - by registered name (keyword)
   ;; (info torus)        - by mesh reference
   ;; (info parts)        - info for all meshes in vector/map
   ;; (info parts 2)      - info for specific element by index
   ;; (info robot :hand)  - info for specific element by key
   (defn info
     ([name-or-coll]
      (cond
        ;; Name (keyword/string/symbol) - try mesh first, then panel
        (or (keyword? name-or-coll) (string? name-or-coll) (symbol? name-or-coll))
        (let [kw (if (keyword? name-or-coll) name-or-coll (keyword name-or-coll))
              mesh (get-mesh kw)
              panel (get-panel kw)]
          (cond
            mesh
            (let [vis (contains? (set (visible-names)) kw)]
              {:name kw
               :type :mesh
               :visible vis
               :vertices (count (:vertices mesh))
               :faces (count (:faces mesh))
               :bounds (compute-bounds (:vertices mesh))})
            panel
            {:name kw
             :type :panel
             :width (:width panel)
             :height (:height panel)
             :content-length (count (:content panel \"\"))}
            :else nil))
        ;; Panel reference
        (panel? name-or-coll)
        {:name (:name name-or-coll)
         :type :panel
         :width (:width name-or-coll)
         :height (:height name-or-coll)
         :content-length (count (:content name-or-coll \"\"))}
        ;; Vector of meshes - return vector of info
        (mesh-vector? name-or-coll)
        (vec (map-indexed
              (fn [i m]
                {:index i
                 :vertices (count (:vertices m))
                 :faces (count (:faces m))})
              name-or-coll))
        ;; Map of meshes - return map of info
        (mesh-map? name-or-coll)
        (into {}
              (map (fn [[k m]]
                     [k {:vertices (count (:vertices m))
                         :faces (count (:faces m))}])
                   name-or-coll))
        ;; Single mesh reference
        (mesh? name-or-coll)
        {:name nil
         :type :mesh
         :vertices (count (:vertices name-or-coll))
         :faces (count (:faces name-or-coll))
         :bounds (compute-bounds (:vertices name-or-coll))}
        :else nil))
     ([coll key]
      (when-let [m (get coll key)]
        {:key key
         :vertices (count (:vertices m))
         :faces (count (:faces m))})))

   ;; Get the raw mesh data for an object
   ;; (mesh :torus) - by registered name
   ;; (mesh torus)  - returns mesh itself (identity)
   (defn mesh [name-or-mesh]
     (if (or (keyword? name-or-mesh) (string? name-or-mesh) (symbol? name-or-mesh))
       (get-mesh (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh)))
       name-or-mesh))

   ;; Helper to resolve name-or-mesh to actual mesh
   (defn- resolve-mesh [name-or-mesh]
     (if (mesh? name-or-mesh)
       name-or-mesh
       (get-mesh (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh)))))

   ;; Bounding box functions
   ;; (bounds :cube)   - by registered name
   ;; (bounds cube)    - by mesh reference
   (defn bounds [name-or-mesh]
     (when-let [m (resolve-mesh name-or-mesh)]
       (compute-bounds (:vertices m))))

   (defn height [name-or-mesh]
     (get-in (bounds name-or-mesh) [:size 2]))

   (defn width [name-or-mesh]
     (get-in (bounds name-or-mesh) [:size 0]))

   (defn depth [name-or-mesh]
     (get-in (bounds name-or-mesh) [:size 1]))

   (defn top [name-or-mesh]
     (get-in (bounds name-or-mesh) [:max 2]))

   (defn bottom [name-or-mesh]
     (get-in (bounds name-or-mesh) [:min 2]))

   (defn center-x [name-or-mesh]
     (get-in (bounds name-or-mesh) [:center 0]))

   (defn center-y [name-or-mesh]
     (get-in (bounds name-or-mesh) [:center 1]))

   (defn center-z [name-or-mesh]
     (get-in (bounds name-or-mesh) [:center 2]))

   ;; Export mesh(es) to STL file
   ;; (export :torus)       - by registered name
   ;; (export torus)        - by mesh reference
   ;; (export parts)        - export all meshes in vector/map
   ;; (export parts 2)      - export specific element by index
   ;; (export robot :hand)  - export specific element by key
   ;; (export :torus :cube) - multiple by name
   ;; (export torus cube)   - multiple by reference
   ;; (export (objects))    - export all visible objects
   (defn export
     ([name-or-coll]
      (cond
        ;; Single keyword
        (keyword? name-or-coll)
        (when-let [m (get-mesh name-or-coll)]
          (save-stl [m] (str (name name-or-coll) \".stl\")))
        ;; List of keywords
        (and (sequential? name-or-coll) (seq name-or-coll) (keyword? (first name-or-coll)))
        (let [meshes (keep get-mesh name-or-coll)]
          (when (seq meshes)
            (save-stl (vec meshes)
                      (str (clojure.string/join \"-\" (map name name-or-coll)) \".stl\"))))
        ;; Vector of meshes
        (mesh-vector? name-or-coll)
        (save-stl (vec name-or-coll) \"export.stl\")
        ;; Map of meshes
        (mesh-map? name-or-coll)
        (save-stl (vec (vals name-or-coll)) \"export.stl\")
        ;; Single mesh
        (mesh? name-or-coll)
        (save-stl [name-or-coll] \"export.stl\")))
     ([first-arg second-arg]
      ;; Check if it's collection + key access
      (cond
        ;; Vector + index
        (and (mesh-vector? first-arg) (number? second-arg))
        (when-let [m (get first-arg second-arg)]
          (save-stl [m] (str \"export-\" second-arg \".stl\")))
        ;; Map + key
        (and (mesh-map? first-arg) (keyword? second-arg))
        (when-let [m (get first-arg second-arg)]
          (save-stl [m] (str \"export-\" (name second-arg) \".stl\")))
        ;; Otherwise treat as multiple meshes/names
        :else
        (let [meshes (keep resolve-mesh [first-arg second-arg])]
          (when (seq meshes)
            (save-stl (vec meshes) \"export.stl\")))))
     ([first-arg second-arg & more-args]
      (let [all-args (cons first-arg (cons second-arg more-args))
            meshes (keep resolve-mesh all-args)]
        (when (seq meshes)
          (save-stl (vec meshes) \"export.stl\")))))

   ;; ============================================================
   ;; Animation macros: span, anim!
   ;; ============================================================

   ;; Parse a turtle command form into animation command data
   (defn- anim-parse-cmd [form]
     (if (and (seq? form) (symbol? (first form)))
       (let [s (first form)
             a (second form)]
         (cond
           (or (= s 'f) (= s 'u) (= s 'd) (= s 'rt) (= s 'lt))
           `(anim-make-cmd ~(keyword (name s)) :dist ~a)

           (or (= s 'th) (= s 'tv) (= s 'tr))
           `(anim-make-cmd ~(keyword (name s)) :angle ~a)

           (= s 'parallel)
           (let [parsed (mapv anim-parse-cmd (rest form))]
             `{:type :parallel :commands [~@parsed]})

           :else form))
       form))

   ;; span: define a timeline segment
   ;; (span weight easing & commands)
   ;; (span weight easing :ang-velocity N & commands)
   (defmacro span [weight easing & body]
     (let [[ang-vel cmds] (if (= :ang-velocity (first body))
                            [(second body) (drop 2 body)]
                            [1 body])
           parsed-cmds (map anim-parse-cmd cmds)]
       `(anim-make-span ~weight ~easing :ang-velocity ~ang-vel ~@parsed-cmds)))

   ;; anim!: define and register an animation
   ;; (anim! :name duration :target spans...)
   ;; (anim! :name duration :target :loop spans...)
   ;; (anim! :name duration :target :fps 30 spans...)
   ;; (anim! :name duration :target :loop :fps 30 spans...)
   (defmacro anim! [name duration target & body]
     (let [[opts spans] (loop [opts {} remaining (vec body)]
                          (cond
                            (= :loop (first remaining))
                            (recur (assoc opts :loop true) (vec (rest remaining)))
                            (= :fps (first remaining))
                            (recur (assoc opts :fps (second remaining)) (vec (drop 2 remaining)))
                            :else [opts remaining]))
           fps (get opts :fps 60)
           loop? (get opts :loop false)]
       `(let [initial-pose# (cond
                              (= ~target :camera)
                              (get-camera-pose)
                              :else
                              (or (when-let [m# (get-mesh ~target)]
                                    (:creation-pose m#))
                                  (let [t# (get-turtle)]
                                    {:position (:position t#) :heading (:heading t#) :up (:up t#)})))
              pivot# (when (= ~target :camera) (get-orbit-target))
              spans# (vector ~@spans)
              result# (anim-preprocess spans# ~duration ~fps initial-pose#
                                       {:camera-mode (when (= ~target :camera) :orbital)
                                        :pivot pivot#})]
          (anim-register! ~name
                          (merge result#
                                 {:target ~target
                                  :duration (double ~duration)
                                  :fps ~fps
                                  :loop ~loop?
                                  :initial-pose initial-pose#
                                  :spans spans#})))))")
