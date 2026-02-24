(ns ridley.editor.macros
  "Macro definitions for the SCI context.")

(def macro-defs
  ";; Atom to hold recorder during path recording
   (def ^:private path-recorder (atom nil))

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
   (defn- rec-u* [dist]
     (swap! path-recorder rec-u dist))
   (defn- rec-rt* [dist]
     (swap! path-recorder rec-rt dist))
   (defn- rec-lt* [dist]
     (swap! path-recorder rec-lt dist))

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

   ;; Record-only commands: inset, scale, move-to (meaningful only in attach context)
   (defn- rec-inset* [amount]
     (swap! path-recorder rec-inset amount))
   (defn- rec-scale* [factor]
     (swap! path-recorder rec-scale factor))
   (defn- rec-move-to* [target & [mode]]
     (swap! path-recorder rec-move-to target mode))
   (defn- rec-play-path* [sub-path]
     (swap! path-recorder rec-play-path sub-path))

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
              ~'u rec-u*
              ~'d (fn [dist#] (rec-u* (- dist#)))
              ~'rt rec-rt*
              ~'lt rec-lt*
              ~'arc-h rec-arc-h*
              ~'arc-v rec-arc-v*
              ~'bezier-to rec-bezier-to*
              ~'bezier-to-anchor rec-bezier-to-anchor*
              ~'bezier-as rec-bezier-as*
              ~'resolution rec-resolution*
              ~'mark rec-mark*
              ~'follow rec-follow*
              ~'inset rec-inset*
              ~'scale rec-scale*
              ~'move-to rec-move-to*
              ~'play-path rec-play-path*]
          (let [body-result# (do ~@body)]
            (let [rec-state# @path-recorder
                  recorded# (:recording rec-state#)]
              (reset! path-recorder saved#)
              ;; Pass-through: if body returned a path and recorder is empty, return it
              (if (and (path? body-result#) (empty? recorded#))
                body-result#
                (let [result# (path-from-recorder rec-state#)
                      result# (if (:bezier rec-state#) (assoc result# :bezier true) result#)]
                  result#)))))))

   ;; smooth-path: convert a path to its bezier-smoothed version
   ;; (def sp (smooth-path P))                   - smooth with defaults
   ;; (def sp (smooth-path P :tension 0.5))      - wider curves
   ;; (def sp (smooth-path P :cubic true))        - Catmull-Rom spline
   ;; Equivalent to (path (bezier-as P ...)) but more intuitive
   (defmacro smooth-path [p & opts]
     `(~'path (~'bezier-as ~p ~@opts)))

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
     (let [{:keys [line column]} (meta &form)]
       `(-> (extrude-impl ~shape (path ~@movements))
            (add-source {:op :extrude :line ~line :col ~column :source *eval-source*}))))

   ;; extrude-closed: like extrude but creates a closed torus-like mesh
   ;; (extrude-closed (circle 5) square-path) - closed torus along path
   ;; The path should return to the starting point for proper closure
   ;; Last ring connects to first ring, no end caps
   ;; Returns the created mesh (can be bound with def)
   (defmacro extrude-closed [shape & movements]
     (let [{:keys [line column]} (meta &form)]
       `(-> (extrude-closed-impl ~shape (path ~@movements))
            (add-source {:op :extrude-closed :line ~line :col ~column :source *eval-source*}))))

   ;; loft: like extrude but with shape transformation based on progress
   ;; PURE: returns mesh without side effects (use register to make visible)
   ;;
   ;; Shape-fn mode (shape varies along path):
   ;; (loft (tapered (circle 20) :to 0) (f 30))              - cone
   ;; (loft (twisted (rect 20 10) :angle 90) (f 40))         - twist
   ;; (loft (-> (circle 20) (fluted :flutes 12) (tapered :to 0)) (f 50))
   ;;
   ;; Legacy transform-fn mode:
   ;; (loft (circle 20) #(scale %1 (- 1 %2)) (f 30))        - cone
   ;; (loft (rect 20 10) #(rotate-shape %1 (* %2 90)) (f 30)) - twist
   ;;
   ;; Two-shape mode:
   ;; (loft (circle 20) (circle 10) (f 40))                  - taper between shapes
   ;;
   ;; Returns the created mesh (can be bound with def)
   (defmacro loft [first-arg & rest-args]
     (let [{:keys [line column]} (meta &form)
           mvmt? (fn [x] (and (list? x) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v
                                                       'bezier-to 'bezier-to-anchor
                                                       'bezier-as} (first x))))
           src `{:op :loft :line ~line :col ~column :source *eval-source*}]
       (cond
         ;; Single rest arg: always 2-arg impl (shape-fn mode)
         (= 1 (count rest-args))
         `(-> (loft-impl ~first-arg (path ~(first rest-args)))
              (add-source ~src))

         ;; First rest-arg is a movement: all rest-args are movements → 2-arg impl
         (mvmt? (first rest-args))
         `(-> (loft-impl ~first-arg (path ~@rest-args))
              (add-source ~src))

         ;; First rest-arg is NOT a movement: it's a dispatch arg (transform-fn or second shape)
         :else
         (let [[dispatch-arg & movements] rest-args]
           (if (seq movements)
             `(-> (loft-impl ~first-arg ~dispatch-arg (path ~@movements))
                  (add-source ~src))
             `(-> (loft-impl ~first-arg (path ~dispatch-arg))
                  (add-source ~src)))))))

   ;; loft-n: loft with custom step count
   ;; (loft-n 32 (tapered (circle 20) :to 0) (f 30))         - shape-fn
   ;; (loft-n 32 (circle 20) #(scale %1 (- 1 %2)) (f 30))   - legacy
   ;; Returns the created mesh (can be bound with def)
   (defmacro loft-n [steps first-arg & rest-args]
     (let [{:keys [line column]} (meta &form)
           mvmt? (fn [x] (and (list? x) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v
                                                       'bezier-to 'bezier-to-anchor
                                                       'bezier-as} (first x))))
           src `{:op :loft-n :line ~line :col ~column :source *eval-source*}]
       (cond
         (= 1 (count rest-args))
         `(-> (loft-n-impl ~steps ~first-arg (path ~(first rest-args)))
              (add-source ~src))

         (mvmt? (first rest-args))
         `(-> (loft-n-impl ~steps ~first-arg (path ~@rest-args))
              (add-source ~src))

         :else
         (let [[dispatch-arg & movements] rest-args]
           (if (seq movements)
             `(-> (loft-n-impl ~steps ~first-arg ~dispatch-arg (path ~@movements))
                  (add-source ~src))
             `(-> (loft-n-impl ~steps ~first-arg (path ~dispatch-arg))
                  (add-source ~src)))))))

   ;; bloft: bezier-safe loft that handles self-intersecting paths
   ;; Uses convex hulls for intersecting sections, then unions all pieces.
   ;; Best for tight curves like (bezier-as (branch-path 30))
   ;; (bloft (tapered (circle 10) :to 0.5) my-bezier-path)    - shape-fn
   ;; (bloft (circle 10) identity my-bezier-path)              - legacy
   ;; (bloft (rect 3 3) #(scale %1 0.5) (bezier-as (branch-path 30)))
   ;; (bloft-n 64 (circle 10) identity my-bezier-path) - more steps
   (defmacro bloft [first-arg & rest-args]
     (let [{:keys [line column]} (meta &form)
           mvmt? (fn [x] (and (list? x) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v
                                                       'bezier-to 'bezier-as} (first x))))
           src `{:op :bloft :line ~line :col ~column :source *eval-source*}
           wrap (fn [expr] `(-> ~expr (add-source ~src)))]
       (cond
         (= 1 (count rest-args))
         (wrap `(bloft-impl ~first-arg (path ~(first rest-args))))

         (mvmt? (first rest-args))
         (wrap `(bloft-impl ~first-arg (path ~@rest-args)))

         :else
         (let [[dispatch-arg & rest-movements] rest-args]
           (if (seq rest-movements)
             (let [args rest-movements]
               (cond
                 (and (seq args) (mvmt? (first args)))
                 (wrap `(bloft-impl ~first-arg ~dispatch-arg (path ~@args)))
                 (= 1 (count args))
                 (wrap `(bloft-impl ~first-arg ~dispatch-arg (path ~(first args))))
                 (= 2 (count args))
                 (wrap `(bloft-impl ~first-arg ~dispatch-arg (path ~(first args)) ~(second args)))
                 (= 3 (count args))
                 (wrap `(bloft-impl ~first-arg ~dispatch-arg (path ~(first args)) ~(second args) ~(nth args 2)))
                 :else
                 (wrap `(bloft-impl ~first-arg ~dispatch-arg (path ~@args)))))
             (wrap `(bloft-impl ~first-arg (path ~dispatch-arg))))))))

   (defmacro bloft-n [steps first-arg & rest-args]
     (let [{:keys [line column]} (meta &form)
           mvmt? (fn [x] (and (list? x) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v
                                                       'bezier-to 'bezier-as} (first x))))
           src `{:op :bloft-n :line ~line :col ~column :source *eval-source*}]
       (cond
         (= 1 (count rest-args))
         `(-> (bloft-n-impl ~steps ~first-arg (path ~(first rest-args)))
              (add-source ~src))

         (mvmt? (first rest-args))
         `(-> (bloft-n-impl ~steps ~first-arg (path ~@rest-args))
              (add-source ~src))

         :else
         (let [[dispatch-arg & movements] rest-args]
           (if (seq movements)
             `(-> (bloft-n-impl ~steps ~first-arg ~dispatch-arg (path ~@movements))
                  (add-source ~src))
             `(-> (bloft-n-impl ~steps ~first-arg (path ~dispatch-arg))
                  (add-source ~src)))))))

   ;; revolve: create solid of revolution (lathe operation)
   ;; PURE: returns mesh without side effects (use register to make visible)
   ;; (revolve (shape (f 8) (th 90) (f 10) (th 90) (f 8)))  ; solid cylinder
   ;; (revolve (circle 5))         ; torus (circle revolved around axis)
   ;; (revolve profile 180)        ; half revolution
   ;; (revolve (tapered (circle 20) :to 0.5))  ; shape-fn: profile varies during revolution
   ;; The profile is interpreted as:
   ;; - 2D X = radial distance from axis (perpendicular to heading)
   ;; - 2D Y = position along axis (in heading direction)
   ;; When a shape-fn is passed, t=0 at the first ring, t→1 at the last ring.
   ;; Returns the created mesh (can be bound with def)
   (defmacro revolve
     ([shape]
      (let [{:keys [line column]} (meta &form)]
        `(-> (revolve-impl ~shape)
             (add-source {:op :revolve :line ~line :col ~column :source *eval-source*}))))
     ([shape angle]
      (let [{:keys [line column]} (meta &form)]
        `(-> (revolve-impl ~shape ~angle)
             (add-source {:op :revolve :line ~line :col ~column :source *eval-source*})))))

   ;; ============================================================
   ;; Functional attach macros
   ;; ============================================================

   ;; attach-face: move existing face vertices (no extrusion)
   ;; (attach-face mesh face-id & body) => modified mesh
   ;; (attach-face selection & body) => selection map from (selected)
   (defmacro attach-face [first-arg & rest]
     (let [{:keys [line column]} (meta &form)
           ;; If second form is keyword/number/vector/symbol, it's face-id.
           ;; If it's a list (fn call) or absent, first-arg is a selection map.
           [mesh face-id body]
           (if (and (seq rest)
                    (let [f (first rest)]
                      (or (keyword? f) (number? f) (vector? f) (symbol? f))))
             [first-arg (first rest) (next rest)]
             [first-arg nil rest])]
       `(-> (attach-face-impl ~mesh ~face-id (path ~@body))
            (add-source {:op :attach-face :line ~line :col ~column :source *eval-source*}))))

   ;; clone-face: extrude face creating new vertices and side faces
   ;; (clone-face mesh face-id & body) => modified mesh with extrusion
   ;; (clone-face selection & body) => selection map from (selected)
   (defmacro clone-face [first-arg & rest]
     (let [{:keys [line column]} (meta &form)
           [mesh face-id body]
           (if (and (seq rest)
                    (let [f (first rest)]
                      (or (keyword? f) (number? f) (vector? f) (symbol? f))))
             [first-arg (first rest) (next rest)]
             [first-arg nil rest])]
       `(-> (clone-face-impl ~mesh ~face-id (path ~@body))
            (add-source {:op :clone-face :line ~line :col ~column :source *eval-source*}))))

   ;; attach: transform mesh/panel/vector in place
   ;; (attach mesh & body) => transformed mesh
   ;; (attach panel & body) => panel repositioned to final turtle position
   ;; (attach [m1 m2 ...] & body) => group-style rigid body transform
   (defmacro attach [mesh & body]
     (let [{:keys [line column]} (meta &form)]
       `(-> (attach-impl ~mesh (path ~@body))
            (add-source {:op :attach :line ~line :col ~column :source *eval-source*}))))

   ;; attach!: transform a registered mesh in-place by keyword
   ;; (attach! :name (f 20) (th 45)) => re-registers the transformed mesh
   (defmacro attach! [kw & body]
     (let [{:keys [line column]} (meta &form)]
       `(-> (attach!-impl ~kw (path ~@body))
            (add-source {:op :attach! :line ~line :col ~column :source *eval-source*}))))

   ;; ============================================================
   ;; Assembly context for hierarchical register
   ;; ============================================================

   ;; Stack of assembly frames for nested with-path + register map.
   ;; nil = no assembly active. Non-nil = vector of frames.
   ;; Each frame: {:prefix [:puppet :r-arm]      — name segments
   ;;              :parent nil                    — parent qualified keyword
   ;;              :goto nil}                     — last goto anchor name
   (def ^:private assembly-ctx (atom nil))

   ;; Current with-path skeleton path (for auto-attach in register map).
   ;; Set by with-path before evaluating body, restored on exit.
   (def ^:private current-skeleton (atom nil))

   (defn- assembly-active? []
     (some? @assembly-ctx))

   (defn- asm-push! [frame]
     (swap! assembly-ctx (fn [s] (conj (or s []) frame))))

   (defn- asm-pop! []
     (swap! assembly-ctx (fn [s]
                           (let [s2 (pop s)]
                             (when (seq s2) s2)))))

   (defn- asm-top []
     (when-let [s @assembly-ctx] (peek s)))

   (defn- asm-update-top! [f]
     (swap! assembly-ctx (fn [s] (conj (pop s) (f (peek s))))))

   ;; Wrap goto to also track in assembly context
   (def ^:private original-goto goto)
   (defn- assembly-goto [anchor-name]
     (original-goto anchor-name)
     (when (assembly-active?)
       (asm-update-top! #(assoc % :goto anchor-name))))

   (defn- qualified-name
     \"Build a qualified keyword from segments.
      E.g. [:puppet :r-arm :upper] => :puppet/r-arm/upper\"
     [segments]
     (keyword (clojure.string/join \"/\" (map name segments))))

   (defn- asm-register-mesh!
     \"Register a mesh in the assembly. Qualified name = prefix ++ [k].
      Creates links based on parent and goto state.
      First mesh in a frame gets the skeleton attached and becomes frame parent.\"
     [k mesh]
     (let [frame (asm-top)
           full-name (qualified-name (conj (:prefix frame) k))
           parent-key (:parent frame)
           goto-anchor (:goto frame)]
       ;; Register and show
       (register-mesh! full-name mesh)
       (show-mesh! full-name)
       ;; Create link
       (when parent-key
         (if goto-anchor
           (link! full-name parent-key :at goto-anchor :inherit-rotation true)
           (link! full-name parent-key :inherit-rotation true)))
       ;; First mesh in this frame becomes the parent for subsequent entries
       ;; and gets the skeleton attached for anchor resolution
       (when-not (:frame-parent-set? frame)
         (asm-update-top! #(assoc % :parent full-name :frame-parent-set? true))
         (when-let [skel (:skeleton frame)]
           (attach-path full-name skel)))
       ;; Clear goto after use
       (asm-update-top! #(assoc % :goto nil))
       full-name))

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
   ;; Inside with-path, a map literal triggers hierarchical assembly mode:
   ;;   qualified names, automatic links from goto calls.
   (defmacro register [name expr & opts]
     ;; Detect map literal for assembly mode
     (let [{reg-line :line reg-col :column} (meta &form)]
     (if (map? expr)
       ;; Map literal — use assembly system for sequential evaluation
       (let [name-kw (keyword name)
             entries (seq expr)]
         `(do
            ;; Initialize assembly context with root frame
            (let [was-active?# (assembly-active?)]
              (when-not was-active?#
                (reset! assembly-ctx []))
              (asm-push! {:prefix [~name-kw] :parent nil :goto nil
                          :frame-parent-set? false
                          :skeleton @current-skeleton})
              ;; Override goto to track anchors
              (let [~'goto assembly-goto]
                ~@(map (fn [[k v]]
                         `(do
                            ;; Extend prefix for this key's subtree
                            (let [bp# (:prefix (asm-top))]
                              (asm-update-top! #(assoc % :prefix (conj bp# ~k)))
                              ;; Evaluate value (may contain goto, with-path, etc.)
                              (let [v# ~v]
                                ;; Restore prefix
                                (asm-update-top! #(assoc % :prefix bp#))
                                (when (mesh? v#)
                                  (asm-register-mesh! ~k v#))))))
                       entries))
              (asm-pop!)
              (when-not was-active?#
                (reset! assembly-ctx nil)))
            (def ~name ~(keyword name))
            (refresh-viewport! false)
            ~(keyword name)))
       ;; Non-map: original behavior
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
          ;; Store raw value for $ lookup
          (register-value! name-kw# value#)
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
                ;; Single mesh (has :vertices) — add :register source entry
                (and (map? value#) (:vertices value#))
                (let [already-registered# (contains? (set (registered-names)) name-kw#)
                      value# (add-source value# {:op :register :as name-kw#
                                                  :line ~reg-line :col ~reg-col
                                                  :source *eval-source*})]
                  (register-mesh! name-kw# value#)
                  (set-source-form! name-kw# '~expr)
                  (if hidden?#
                    (hide-mesh! name-kw#)
                    (when-not already-registered#
                      (show-mesh! name-kw#))))

                ;; Shape (has :type :shape)
                (and (map? value#) (= :shape (:type value#)))
                (register-shape! name-kw# value#)

                ;; Vector of meshes — register each with sub-name for prefix matching
                (mesh-vector? value#)
                (doseq [[i# mesh#] (map-indexed vector value#)]
                  (claim-mesh! mesh#)
                  (register-mesh! (keyword (str (name name-kw#) \"/\" i#)) mesh#))

                ;; Map of meshes — register each with sub-name for prefix matching
                (mesh-map? value#)
                (doseq [[k# mesh#] value#]
                  (claim-mesh! mesh#)
                  (register-mesh! (keyword (str (name name-kw#) \"/\" (name k#))) mesh#))

                ;; Path (has :type :path) — abstract, no visibility
                (and (map? value#) (= :path (:type value#)))
                (register-path! name-kw# value#))
              ;; Refresh viewport and return value
              (refresh-viewport! false)
              value#))))))

   ;; r: short alias for register
   (defmacro r [name expr & opts]
     `(register ~name ~expr ~@opts))

   ;; with-path: pin a path at current turtle pose, resolve marks as anchors
   ;; (with-path skeleton (goto :shoulder) (bezier-to-anchor :elbow))
   ;; Supports nesting — inner with-path shadows outer anchors, restores on exit
   ;; When the body is a single map literal inside an assembly context,
   ;; evaluates entries sequentially for link inference.
   (defmacro with-path [path-expr & body]
     (let [;; Check if body is a single map literal (assembly sub-map case)
           single-map? (and (= 1 (count body)) (map? (first body)))]
       (if single-map?
         ;; Assembly sub-map: expand entries sequentially within with-path
         (let [entries (seq (first body))]
           `(let [saved-anchors# (save-anchors*)
                  saved-skeleton# @current-skeleton
                  path-val# ~path-expr]
              (resolve-and-merge-marks* path-val#)
              (reset! current-skeleton path-val#)
              (if (assembly-active?)
                ;; Inside assembly: push new frame inheriting parent info
                (do
                  (let [parent-frame# (asm-top)]
                    (asm-push! {:prefix (:prefix parent-frame#)
                                :parent (:parent parent-frame#)
                                :goto (:goto parent-frame#)
                                :frame-parent-set? false
                                :skeleton path-val#}))
                  (let [~'goto assembly-goto]
                    ~@(map (fn [[k v]]
                             `(do
                                ;; Extend prefix for this key's subtree
                                (let [bp# (:prefix (asm-top))]
                                  (asm-update-top! #(assoc % :prefix (conj bp# ~k)))
                                  (let [v# ~v]
                                    (asm-update-top! #(assoc % :prefix bp#))
                                    (when (mesh? v#)
                                      (asm-register-mesh! ~k v#))))))
                           entries))
                  (asm-pop!))
                ;; Not in assembly: evaluate normally
                (do ~@(map (fn [[k v]] v) entries)))
              (reset! current-skeleton saved-skeleton#)
              (restore-anchors* saved-anchors#)))
         ;; Normal with-path: no map literal
         ;; Store path as current-skeleton for assembly auto-attach
         `(let [saved-anchors# (save-anchors*)
                saved-skeleton# @current-skeleton
                path-val# ~path-expr]
            (resolve-and-merge-marks* path-val#)
            (reset! current-skeleton path-val#)
            (let [result# (do ~@body)]
              (reset! current-skeleton saved-skeleton#)
              (restore-anchors* saved-anchors#)
              result#)))))

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
        ;; Name (keyword/string/symbol) - try mesh, panel, then registered value
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
        ;; Name (keyword/string/symbol) - try mesh, panel, then registered value
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
   ;; (bounds :cube)       - by registered mesh name
   ;; (bounds cube)        - by mesh reference
   ;; (bounds my-path)     - by path (2D bounds)
   ;; (bounds my-shape)    - by shape (2D bounds)
   (defn bounds [obj]
     (if (and (map? obj) (#{:path :shape} (:type obj)))
       (bounds-2d obj)
       (when-let [m (resolve-mesh obj)]
         (compute-bounds (:vertices m)))))

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
   ;; Source-tracking macro wrappers for function-bound operations
   ;; ============================================================
   ;; These shadow the *-impl function bindings to capture &form metadata.

   ;; 3D primitives
   (defmacro box [& args]
     (let [{:keys [line column]} (meta &form)]
       `(-> (box-impl ~@args)
            (add-source {:op :box :line ~line :col ~column :source *eval-source*}))))
   (defmacro sphere [& args]
     (let [{:keys [line column]} (meta &form)]
       `(-> (sphere-impl ~@args)
            (add-source {:op :sphere :line ~line :col ~column :source *eval-source*}))))
   (defmacro cyl [& args]
     (let [{:keys [line column]} (meta &form)]
       `(-> (cyl-impl ~@args)
            (add-source {:op :cyl :line ~line :col ~column :source *eval-source*}))))
   (defmacro cone [& args]
     (let [{:keys [line column]} (meta &form)]
       `(-> (cone-impl ~@args)
            (add-source {:op :cone :line ~line :col ~column :source *eval-source*}))))

   ;; Boolean operations (capture operand refs)
   (defmacro mesh-union [base & more]
     (let [{:keys [line column]} (meta &form)]
       `(let [inputs# [~base ~@more]
              result# (apply mesh-union-impl inputs#)]
          (add-source result# {:op :mesh-union :line ~line :col ~column
                               :source *eval-source*
                               :operands (mapv source-ref inputs#)}))))
   (defmacro mesh-difference [base & tools]
     (let [{:keys [line column]} (meta &form)]
       `(let [inputs# [~base ~@tools]
              result# (apply mesh-difference-impl inputs#)]
          (add-source result# {:op :mesh-difference :line ~line :col ~column
                               :source *eval-source*
                               :operands (mapv source-ref inputs#)}))))
   (defmacro mesh-intersection [base & more]
     (let [{:keys [line column]} (meta &form)]
       `(let [inputs# [~base ~@more]
              result# (apply mesh-intersection-impl inputs#)]
          (add-source result# {:op :mesh-intersection :line ~line :col ~column
                               :source *eval-source*
                               :operands (mapv source-ref inputs#)}))))
   (defmacro mesh-hull [& args]
     (let [{:keys [line column]} (meta &form)]
       `(let [inputs# [~@args]
              result# (apply mesh-hull-impl inputs#)]
          (add-source result# {:op :mesh-hull :line ~line :col ~column
                               :source *eval-source*
                               :operands (mapv source-ref inputs#)}))))

   ;; Warp (single input ref)
   (defmacro warp [mesh volume & args]
     (let [{:keys [line column]} (meta &form)]
       `(let [m# ~mesh
              result# (warp-impl m# ~volume ~@args)]
          (add-source result# {:op :warp :line ~line :col ~column
                               :source *eval-source*
                               :input (source-ref m#)}))))

   ;; Solidify
   (defmacro solidify [mesh]
     (let [{:keys [line column]} (meta &form)]
       `(-> (solidify-impl ~mesh)
            (add-source {:op :solidify :line ~line :col ~column :source *eval-source*}))))

   ;; Text on path
   (defmacro text-on-path [& args]
     (let [{:keys [line column]} (meta &form)]
       `(-> (text-on-path-impl ~@args)
            (add-source {:op :text-on-path :line ~line :col ~column :source *eval-source*}))))

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
   ;; (span weight easing commands... :on-enter expr :on-exit expr)
   (defmacro span [weight easing & body]
     (let [;; Parse keyword options and commands from body
           ;; Options (:ang-velocity, :on-enter, :on-exit) can appear anywhere
           {:keys [ang-vel on-enter on-exit cmds]}
           (loop [opts {:ang-vel 1} cmds [] remaining (vec body)]
             (cond
               (empty? remaining)
               (assoc opts :cmds cmds)
               (= :ang-velocity (first remaining))
               (recur (assoc opts :ang-vel (second remaining))
                      cmds (vec (drop 2 remaining)))
               (= :on-enter (first remaining))
               (recur (assoc opts :on-enter (second remaining))
                      cmds (vec (drop 2 remaining)))
               (= :on-exit (first remaining))
               (recur (assoc opts :on-exit (second remaining))
                      cmds (vec (drop 2 remaining)))
               :else
               (recur opts (conj cmds (first remaining))
                      (vec (rest remaining)))))
           parsed-cmds (map anim-parse-cmd cmds)]
       `(anim-make-span ~weight ~easing
          :ang-velocity ~ang-vel
          ~@(when on-enter [:on-enter `(fn [] ~on-enter)])
          ~@(when on-exit  [:on-exit  `(fn [] ~on-exit)])
          ~@parsed-cmds)))

   ;; anim!: define and register an animation
   ;; (anim! :name duration :target spans...)
   ;; (anim! :name duration :target :loop spans...)
   ;; (anim! :name duration :target :loop-reverse spans...)
   ;; (anim! :name duration :target :loop-bounce spans...)
   ;; (anim! :name duration :target :fps 30 spans...)
   ;; (anim! :name duration :target :loop :fps 30 spans...)
   (defmacro anim! [name duration target & body]
     (let [[opts spans] (loop [opts {} remaining (vec body)]
                          (cond
                            (#{:loop :loop-reverse :loop-bounce} (first remaining))
                            (recur (assoc opts :loop (case (first remaining)
                                                       :loop :forward
                                                       :loop-reverse :reverse
                                                       :loop-bounce :bounce))
                                   (vec (rest remaining)))
                            (= :fps (first remaining))
                            (recur (assoc opts :fps (second remaining)) (vec (drop 2 remaining)))
                            :else [opts remaining]))
           fps (get opts :fps 60)
           loop-mode (get opts :loop false)]
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
                                  :loop ~loop-mode
                                  :initial-pose initial-pose#
                                  :spans spans#})))))

   ;; anim-proc!: define and register a procedural animation
   ;; A procedural animation calls gen-fn every frame with eased t (0→1)
   ;; and replaces the mesh with the returned value.
   ;; (anim-proc! :name duration :target easing gen-fn)
   ;; (anim-proc! :name duration :target easing :loop gen-fn)
   ;; (anim-proc! :name duration :target easing :loop-reverse gen-fn)
   ;; (anim-proc! :name duration :target easing :loop-bounce gen-fn)
   (defmacro anim-proc! [name duration target easing & body]
     (let [[loop-mode gen-fn-expr]
           (let [kw (first body)]
             (if (#{:loop :loop-reverse :loop-bounce} kw)
               [(case kw
                  :loop :forward
                  :loop-reverse :reverse
                  :loop-bounce :bounce)
                (second body)]
               [false (first body)]))]
       `(anim-proc-register! ~name
                             {:target ~target
                              :duration (double ~duration)
                              :easing ~easing
                              :loop ~loop-mode
                              :gen-fn ~gen-fn-expr})))

   ;; anim-fn: like anim! but returns a function instead of executing immediately.
   ;; The macro expands span/command parsing at compile time, but wraps the
   ;; preprocessing + registration in a fn. Each call auto-generates a unique name.
   ;; Returns a fn that, when called, registers and plays the animation,
   ;; returning the auto-generated animation name (for later stop!).
   ;; (anim-fn duration target [opts...] spans...) => (fn [] ...)
   (defmacro anim-fn [duration target & body]
     (let [[opts spans] (loop [opts {} remaining (vec body)]
                          (cond
                            (#{:loop :loop-reverse :loop-bounce} (first remaining))
                            (recur (assoc opts :loop (case (first remaining)
                                                       :loop :forward
                                                       :loop-reverse :reverse
                                                       :loop-bounce :bounce))
                                   (vec (rest remaining)))
                            (= :fps (first remaining))
                            (recur (assoc opts :fps (second remaining)) (vec (drop 2 remaining)))
                            :else [opts remaining]))
           fps (get opts :fps 60)
           loop-mode (get opts :loop false)]
       `(fn []
          (let [target-val# ~target
                auto-name# (keyword (str (clojure.string/replace (subs (str target-val#) 1) \"/\" \"-\")
                                         \"-\" (gensym \"fn\")))
                initial-pose# (cond
                                (= target-val# :camera)
                                (get-camera-pose)
                                :else
                                (or (when-let [m# (get-mesh target-val#)]
                                      (:creation-pose m#))
                                    (let [t# (get-turtle)]
                                      {:position (:position t#) :heading (:heading t#) :up (:up t#)})))
                pivot# (when (= target-val# :camera) (get-orbit-target))
                spans# (vector ~@spans)
                result# (anim-preprocess spans# ~duration ~fps initial-pose#
                                         {:camera-mode (when (= target-val# :camera) :orbital)
                                          :pivot pivot#})]
            (anim-register! auto-name#
                            (merge result#
                                   {:target target-val#
                                    :duration (double ~duration)
                                    :fps ~fps
                                    :loop ~loop-mode
                                    :initial-pose initial-pose#
                                    :spans spans#}))
            (play! auto-name#)
            auto-name#))))

   ;; ============================================================
   ;; turtle: scoped turtle state
   ;; ============================================================
   ;; (turtle body...)                    — clone parent pose, isolated geometry
   ;; (turtle :reset body...)             — fresh turtle at origin
   ;; (turtle :preserve-up body...)       — enable preserve-up mode
   ;; (turtle [x y z] body...)            — set position
   ;; (turtle {:pos p :heading h} body...)— full options map

   (defn- parse-turtle-opts
     \"Parse turtle macro arguments into {:opts map :body forms}.
      Consumes leading keywords (:reset, :preserve-up), vector (position),
      or map with known keys. Everything else is body.\"
     [args]
     (loop [opts {} remaining (vec args)]
       (if (empty? remaining)
         {:opts opts :body []}
         (let [x (first remaining)]
           (cond
             (= :reset x)
             (recur (assoc opts :reset true) (subvec remaining 1))
             (= :preserve-up x)
             (recur (assoc opts :preserve-up true) (subvec remaining 1))
             (vector? x)
             (recur (assoc opts :pos x) (subvec remaining 1))
             (and (map? x)
                  (some #{:pos :heading :up :reset :preserve-up} (keys x)))
             (recur (merge opts x) (subvec remaining 1))
             :else
             {:opts opts :body (vec remaining)})))))

   (defmacro turtle [& args]
     (let [{:keys [opts body]} (parse-turtle-opts args)
           opts-form (if (empty? opts) {} opts)]
       `(let [parent-state# @*turtle-state*]
          (binding [*turtle-state* (atom (init-turtle ~opts-form parent-state#))]
            ~@body))))

   ;; anim-proc-fn: like anim-proc! but returns a function.
   ;; Each call auto-generates a unique name.
   ;; Returns a fn that, when called, registers and plays the procedural animation,
   ;; returning the auto-generated animation name.
   ;; (anim-proc-fn duration target easing [opts...] gen-fn) => (fn [] ...)
   (defmacro anim-proc-fn [duration target easing & body]
     (let [[loop-mode gen-fn-expr]
           (let [kw (first body)]
             (if (#{:loop :loop-reverse :loop-bounce} kw)
               [(case kw
                  :loop :forward
                  :loop-reverse :reverse
                  :loop-bounce :bounce)
                (second body)]
               [false (first body)]))]
       `(fn []
          (let [target-val# ~target
                auto-name# (keyword (str (clojure.string/replace (subs (str target-val#) 1) \"/\" \"-\")
                                         \"-\" (gensym \"pfn\")))]
            (anim-proc-register! auto-name#
                                 {:target target-val#
                                  :duration (double ~duration)
                                  :easing ~easing
                                  :loop ~loop-mode
                                  :gen-fn ~gen-fn-expr})
            (play! auto-name#)
            auto-name#))))

   ;; ============================================================
   ;; tweak: interactive parameter tweaking with sliders
   ;; ============================================================
   ;; (tweak expr)              — slider for first numeric literal only
   ;; (tweak n expr)            — slider for literal at index n (negative = from end)
   ;; (tweak [n1 n2] expr)     — sliders for selected literals
   ;; (tweak :all expr)         — sliders for all literals
   ;; (tweak :A)                — tweak registered mesh using stored source form
   ;; (tweak :A expr)           — tweak registered mesh with explicit expression
   ;; (tweak :all :A)           — tweak registered mesh, all sliders
   ;; (tweak n :A)              — tweak registered mesh, slider n
   ;; (tweak :all :A expr)      — tweak registered mesh, explicit expression, all sliders

   ;; Collect non-fn-position symbols from a form (for tweak locals capture).
   ;; These symbols may be let-bound locals that need to be captured at runtime.
   (defn- collect-arg-symbols [form]
     (cond
       (symbol? form) (if (= '_ form) #{} #{form})
       (and (list? form) (seq form))
       (reduce into #{} (map collect-arg-symbols (rest form)))
       (vector? form)
       (reduce into #{} (map collect-arg-symbols form))
       (map? form)
       (reduce into #{} (map collect-arg-symbols (mapcat identity form)))
       (set? form)
       (reduce into #{} (map collect-arg-symbols form))
       :else #{}))

   (defn- tweak-locals-form
     \"Generate a (hash-map 'sym1 sym1 'sym2 sym2 ...) form for captured locals.\"
     [syms]
     (when (seq syms)
       `(hash-map ~@(mapcat (fn [s] [(list 'quote s) s]) syms))))

   (defmacro tweak
     ([expr]
      (if (keyword? expr)
        `(tweak-start-registered! ~expr nil nil)
        (let [locals (tweak-locals-form (collect-arg-symbols expr))]
          (if locals
            `(tweak-start! '~expr nil nil ~locals)
            `(tweak-start! '~expr nil)))))
     ([a b]
      (cond
        ;; (tweak :all :vase), (tweak -1 :vase), (tweak [1 2] :vase)
        ;; Second arg is keyword → filter + registry
        (keyword? b)
        `(tweak-start-registered! ~b nil ~a)
        ;; (tweak :vase (sphere 20)) — registry + expression
        (and (keyword? a) (not= :all a))
        (let [locals (tweak-locals-form (collect-arg-symbols b))]
          (if locals
            `(tweak-start-registered! ~a '~b nil ~locals)
            `(tweak-start-registered! ~a '~b nil)))
        ;; (tweak :all (sphere 20)), (tweak 2 (sphere 20)) — filter + expression
        :else
        (let [locals (tweak-locals-form (collect-arg-symbols b))]
          (if locals
            `(tweak-start! '~b ~a nil ~locals)
            `(tweak-start! '~b ~a)))))
     ([filt name expr]
      (let [locals (tweak-locals-form (collect-arg-symbols expr))]
        (if locals
          `(tweak-start-registered! ~name '~expr ~filt ~locals)
          `(tweak-start-registered! ~name '~expr ~filt)))))")
