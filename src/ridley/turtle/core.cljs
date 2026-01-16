(ns ridley.turtle.core
  "Immutable turtle state and movement commands.

   State structure:
   {:position [x y z]     - current position
    :heading [x y z]      - forward direction (unit vector)
    :up [x y z]           - up direction (unit vector)
    :pen-mode             - :off, :on, or :shape (internal)
    :stamped-shape        - for :shape mode: current 3D vertices of shape
    :sweep-rings          - for :shape mode: accumulated rings for unified mesh
    :pending-rotation     - for :shape mode: deferred rotation to create fillet
    :geometry []          - accumulated line segments
    :meshes []}           - accumulated 3D meshes

   API:
   - (pen :off) - stop drawing lines
   - (pen :on) - draw lines (default)
   - (extrude shape movements...) - stamp shape and extrude via movements")

(defn make-turtle
  "Create initial turtle state at origin, facing +X, up +Z.
   This makes 2D drawing happen in the XY plane (Z=0),
   ideal for extruding along Z."
  []
  {:position [0 0 0]
   :heading [1 0 0]
   :up [0 0 1]
   :pen-mode :on            ; :off, :on, or :shape (internal for extrude)
   :stamped-shape nil       ; for :shape mode: current 3D vertices of shape
   :sweep-rings []          ; for :shape mode: accumulated rings for unified mesh
   :geometry []
   :meshes []})

;; --- Vector math utilities ---

(defn v+ [[x1 y1 z1] [x2 y2 z2]]
  [(+ x1 x2) (+ y1 y2) (+ z1 z2)])

(defn v- [[x1 y1 z1] [x2 y2 z2]]
  [(- x1 x2) (- y1 y2) (- z1 z2)])

(defn v* [[x y z] s]
  [(* x s) (* y s) (* z s)])

(defn dot [[x1 y1 z1] [x2 y2 z2]]
  (+ (* x1 x2) (* y1 y2) (* z1 z2)))

(defn cross [[x1 y1 z1] [x2 y2 z2]]
  [(- (* y1 z2) (* z1 y2))
   (- (* z1 x2) (* x1 z2))
   (- (* x1 y2) (* y1 x2))])

(defn magnitude [[x y z]]
  (Math/sqrt (+ (* x x) (* y y) (* z z))))

(defn normalize [v]
  (let [m (magnitude v)]
    (if (zero? m)
      v
      (v* v (/ 1 m)))))

(defn rotate-around-axis
  "Rotate vector v around axis by angle (radians) using Rodrigues' formula."
  [v axis angle]
  (let [k (normalize axis)
        cos-a (Math/cos angle)
        sin-a (Math/sin angle)
        ; v' = v*cos(a) + (k x v)*sin(a) + k*(k·v)*(1-cos(a))
        term1 (v* v cos-a)
        term2 (v* (cross k v) sin-a)
        term3 (v* k (* (dot k v) (- 1 cos-a)))]
    (normalize (v+ (v+ term1 term2) term3))))

;; --- Sweep mesh building ---

(defn- build-sweep-mesh
  "Build a unified mesh from accumulated sweep rings.
   Each ring is a vector of 3D vertices.
   If closed? is true, connects last ring back to first (torus-like, no caps).
   Otherwise creates bottom cap, side faces, and top cap."
  ([rings] (build-sweep-mesh rings false))
  ([rings closed?]
   (let [n-rings (count rings)
         n-verts (count (first rings))]
     (when (and (>= n-rings 2) (>= n-verts 3))
       (if closed?
         ;; Closed loop: skip last ring (overlaps with first), connect last to first
         (let [effective-rings (butlast rings)
               effective-n-rings (count effective-rings)
               vertices (vec (apply concat effective-rings))
               side-faces (vec
                           (mapcat
                            (fn [ring-idx]
                              (let [next-ring-idx (mod (inc ring-idx) effective-n-rings)]
                                (mapcat
                                 (fn [vert-idx]
                                   (let [next-vert (mod (inc vert-idx) n-verts)
                                         base (* ring-idx n-verts)
                                         next-base (* next-ring-idx n-verts)
                                         b0 (+ base vert-idx)
                                         b1 (+ base next-vert)
                                         t0 (+ next-base vert-idx)
                                         t1 (+ next-base next-vert)]
                                     [[b0 t1 b1] [b0 t0 t1]]))
                                 (range n-verts))))
                            (range effective-n-rings)))]
           {:type :mesh
            :primitive :sweep-closed
            :vertices vertices
            :faces side-faces})
         ;; Open: caps at both ends
         (let [vertices (vec (apply concat rings))
               side-faces (vec
                           (mapcat
                            (fn [ring-idx]
                              (mapcat
                               (fn [vert-idx]
                                 (let [next-vert (mod (inc vert-idx) n-verts)
                                       base (* ring-idx n-verts)
                                       next-base (* (inc ring-idx) n-verts)
                                       b0 (+ base vert-idx)
                                       b1 (+ base next-vert)
                                       t0 (+ next-base vert-idx)
                                       t1 (+ next-base next-vert)]
                                   [[b0 t1 b1] [b0 t0 t1]]))
                               (range n-verts)))
                            (range (dec n-rings))))
               bottom-cap (vec (for [i (range 1 (dec n-verts))]
                                 [0 i (inc i)]))
               last-base (* (dec n-rings) n-verts)
               top-cap (vec (for [i (range 1 (dec n-verts))]
                              [last-base (+ last-base i 1) (+ last-base i)]))]
           {:type :mesh
            :primitive :sweep
            :vertices vertices
            :faces (vec (concat side-faces bottom-cap top-cap))}))))))

;; --- Shape stamping ---

(defn- shape?
  "Check if x is a shape (has :type :shape)."
  [x]
  (and (map? x) (= :shape (:type x))))

(defn- stamp-shape
  "Stamp a shape onto the plane perpendicular to turtle's heading.
   Returns 3D vertices of the stamped shape."
  [state shape]
  (let [points (:points shape)
        centered? (:centered? shape)
        pos (:position state)
        heading (:heading state)
        up (:up state)
        ;; The plane is perpendicular to heading
        ;; x-axis of plane = right vector (heading × up)
        ;; y-axis of plane = up vector
        [hx hy hz] heading
        [ux uy uz] up
        ;; Right vector = heading × up
        rx (- (* hy uz) (* hz uy))
        ry (- (* hz ux) (* hx uz))
        rz (- (* hx uy) (* hy ux))
        ;; Normalize right vector
        r-mag (Math/sqrt (+ (* rx rx) (* ry ry) (* rz rz)))
        [rx ry rz] (if (pos? r-mag)
                     [(/ rx r-mag) (/ ry r-mag) (/ rz r-mag)]
                     [1 0 0])
        ;; Plane axes: X = right, Y = up
        plane-x [rx ry rz]
        plane-y up
        ;; Calculate offset for non-centered shapes
        offset (if centered?
                 [0 0]
                 (let [[fx fy] (first points)]
                   [(- fx) (- fy)]))]
    ;; Transform each 2D point to 3D
    (mapv (fn [[px py]]
            (let [;; Apply offset for non-centered shapes
                  px' (+ px (first offset))
                  py' (+ py (second offset))
                  ;; Transform to 3D: origin + px*plane-x + py*plane-y
                  [ox oy oz] pos
                  [xx xy xz] plane-x
                  [yx yy yz] plane-y]
              [(+ ox (* px' xx) (* py' yx))
               (+ oy (* px' xy) (* py' yy))
               (+ oz (* px' xz) (* py' yz))]))
          points)))

;; --- Rotation utilities (needed by fillet) ---

(defn- deg->rad [deg]
  (* deg (/ Math/PI 180)))

(defn- right-vector
  "Calculate the right vector (heading x up)."
  [state]
  (cross (:heading state) (:up state)))

;; Default number of interpolation steps for fillet during extrude
(def ^:private default-fillet-steps 16)

(defn- apply-rotations
  "Apply a list of rotations to heading/up vectors.
   Returns {:heading new-heading :up new-up}"
  [state rotations]
  (reduce
   (fn [{:keys [heading up] :as acc} {:keys [type angle]}]
     (let [rad (deg->rad angle)]
       (case type
         :th (assoc acc :heading (rotate-around-axis heading up rad))
         :tv (let [right (normalize (cross heading up))
                   new-heading (rotate-around-axis heading right rad)
                   new-up (rotate-around-axis up right rad)]
               (assoc acc :heading new-heading :up new-up))
         :tr (assoc acc :up (rotate-around-axis up heading rad))
         acc)))
   {:heading (:heading state) :up (:up state)}
   rotations))

;; --- Fillet/Bend calculation ---

(defn- create-fillet-rings
  "Create fillet rings connecting pre-rotation and post-rotation orientations.
   The fillet rotates the shape in place at the current position, creating
   a smooth angular transition without moving the turtle along an arc.

   Parameters:
   - state: turtle state (at start of fillet, before rotation applied)
   - new-heading: heading after rotation
   - new-up: up vector after rotation
   - steps: number of intermediate rings

   Returns vector of rings (3D vertex arrays).

   The turtle stays at its current position; only the orientation changes.
   This keeps the extrusion centered on the path's centerline."
  [state new-heading new-up steps]
  (let [base-shape (:sweep-base-shape state)
        old-heading (:heading state)
        old-up (:up state)
        pos (:position state)]
    (when base-shape
      (vec
       (for [i (range 1 (inc steps))]
         (let [t (/ i steps)
               ;; Interpolate heading and up vectors
               interp-heading (normalize (v+ (v* old-heading (- 1 t))
                                             (v* new-heading t)))
               interp-up (normalize (v+ (v* old-up (- 1 t))
                                        (v* new-up t)))
               ;; Create temp state with interpolated orientation at same position
               temp-state (-> state
                              (assoc :position pos)
                              (assoc :heading interp-heading)
                              (assoc :up interp-up))]
           (stamp-shape temp-state base-shape)))))))

(defn- process-pending-rotation
  "Process a pending rotation when moving forward.
   Creates a fillet connecting the old and new orientations.
   The turtle rotates in place - no positional offset is introduced.
   Returns updated state with fillet rings added and pending rotation cleared."
  [state _dist]
  (let [pending (:pending-rotation state)
        ;; Calculate final heading/up after all pending rotations
        {:keys [heading up]} (apply-rotations state pending)
        ;; Steps for fillet smoothness
        steps (or (:fillet-steps state) default-fillet-steps)
        ;; Create fillet rings (turtle stays at same position)
        fillet-rings (create-fillet-rings state heading up steps)]
    (-> state
        (assoc :heading heading)
        (assoc :up up)
        ;; Position stays the same - turtle rotates in place
        (update :sweep-rings into fillet-rings)
        (dissoc :pending-rotation))))

;; --- Movement commands ---

(defn- move
  "Move turtle by distance along a direction vector.
   Behavior depends on pen-mode:
   - :off or nil - just move, no drawing
   - :on - add line segment to geometry
   - :shape - accumulate ring for sweep mesh"
  [state direction dist]
  (let [pos (:position state)
        new-pos (v+ pos (v* direction dist))
        mode (:pen-mode state)]
    (case mode
      (:off nil)
      (assoc state :position new-pos)

      :on
      (-> state
          (assoc :position new-pos)
          (update :geometry conj {:type :line
                                  :from pos
                                  :to new-pos}))

      :shape
      ;; Shape mode: check for pending rotation first
      (let [state' (if (:pending-rotation state)
                     (process-pending-rotation state dist)
                     state)
            ;; Now do normal forward movement from (possibly updated) position
            new-pos' (v+ (:position state') (v* (:heading state') dist))
            base-shape (:sweep-base-shape state')
            new-state (assoc state' :position new-pos')
            ;; Re-stamp the 2D shape at the new position/orientation
            new-shape (stamp-shape new-state base-shape)]
        (-> new-state
            (assoc :stamped-shape new-shape)
            ;; Add new ring to sweep-rings (mesh built at finalize)
            (update :sweep-rings conj new-shape)))

      :loft
      ;; Loft mode: track distance and orientations (rings built at finalize)
      (let [current-shape (:stamped-shape state)
            offset (v* direction dist)
            new-shape (mapv #(v+ % offset) current-shape)
            new-total-dist (+ (:loft-total-dist state) (Math/abs dist))
            ;; Save turtle orientation for this waypoint
            orientation {:position new-pos
                         :heading (:heading state)
                         :up (:up state)
                         :dist new-total-dist}]
        (-> state
            (assoc :position new-pos)
            (assoc :stamped-shape new-shape)
            (assoc :loft-total-dist new-total-dist)
            (update :loft-orientations conj orientation)))

      ;; Other modes - just move
      (assoc state :position new-pos))))

(defn f
  "Move forward by distance. Use negative values to move backward."
  [state dist]
  (move state (:heading state) dist))

;; --- Rotation commands ---

(defn- store-pending-rotation
  "Store a pending rotation to be applied on next forward movement.
   Multiple rotations accumulate (compose)."
  [state rotation-type angle]
  (let [current-pending (:pending-rotation state)
        new-rotation {:type rotation-type :angle angle}]
    (if current-pending
      ;; Accumulate rotations
      (update state :pending-rotation conj new-rotation)
      ;; Start new pending rotation list
      (assoc state :pending-rotation [new-rotation]))))

(defn th
  "Turn horizontal (yaw) - rotate heading around up axis.
   Positive angle turns left.
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (if (= :shape (:pen-mode state))
    ;; In shape mode, store pending rotation (fillet created on next f)
    (store-pending-rotation state :th angle)
    ;; Normal mode: apply rotation immediately
    (let [rad (deg->rad angle)
          new-heading (rotate-around-axis (:heading state) (:up state) rad)]
      (assoc state :heading new-heading))))

(defn tv
  "Turn vertical (pitch) - rotate heading and up around right axis.
   Positive angle pitches up.
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (if (= :shape (:pen-mode state))
    ;; In shape mode, store pending rotation (fillet created on next f)
    (store-pending-rotation state :tv angle)
    ;; Normal mode: apply rotation immediately
    (let [rad (deg->rad angle)
          right (right-vector state)
          new-heading (rotate-around-axis (:heading state) right rad)
          new-up (rotate-around-axis (:up state) right rad)]
      (-> state
          (assoc :heading new-heading)
          (assoc :up new-up)))))

(defn tr
  "Turn roll - rotate up around heading axis.
   Positive angle rolls clockwise (when viewed from behind).
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (if (= :shape (:pen-mode state))
    ;; In shape mode, store pending rotation (fillet created on next f)
    (store-pending-rotation state :tr angle)
    ;; Normal mode: apply rotation immediately
    (let [rad (deg->rad angle)
          new-up (rotate-around-axis (:up state) (:heading state) rad)]
      (assoc state :up new-up))))

;; --- Pen commands ---

(defn pen-up
  "Stop drawing when moving. (Legacy - use (pen :off) instead)"
  [state]
  (assoc state :pen-mode :off))

(defn pen-down
  "Start drawing when moving. (Legacy - use (pen :on) instead)"
  [state]
  (assoc state :pen-mode :on))

(defn pen
  "Set pen mode for drawing.

   Modes:
   - (pen :off) - stop drawing
   - (pen :on) - draw lines (default)

   For shape extrusion, use (extrude shape movements...) instead."
  [state mode]
  (cond
    (= mode :off)
    (-> state
        (assoc :pen-mode :off)
        (assoc :stamped-shape nil))

    (= mode :on)
    (-> state
        (assoc :pen-mode :on)
        (assoc :stamped-shape nil))

    ;; Unknown mode - no change
    :else
    state))

(defn stamp
  "Internal: stamp a shape for extrusion. Used by extrude macro.
   Initializes sweep-rings with the first ring (the stamped shape).
   Also stores the 2D base shape for re-stamping after rotations."
  [state shape]
  (if (shape? shape)
    (let [stamped (stamp-shape state shape)]
      (-> state
          (assoc :pen-mode :shape)
          (assoc :stamped-shape stamped)
          (assoc :sweep-base-shape shape)  ; Store 2D shape for re-stamping
          (assoc :sweep-rings [stamped])))  ; First ring is the stamped shape
    state))

(defn finalize-sweep
  "Internal: finalize sweep by building unified mesh from accumulated rings.
   Called at end of extrude macro."
  [state]
  (let [rings (:sweep-rings state)]
    (if (>= (count rings) 2)
      ;; Build unified mesh from all rings
      (if-let [mesh (build-sweep-mesh rings)]
        (-> state
            (update :meshes conj mesh)
            (assoc :sweep-rings [])
            (assoc :stamped-shape nil)
            (dissoc :sweep-base-shape))
        ;; Fallback: clear state without adding mesh
        (-> state
            (assoc :sweep-rings [])
            (assoc :stamped-shape nil)
            (dissoc :sweep-base-shape)))
      ;; Not enough rings - just clear state
      (-> state
          (assoc :sweep-rings [])
          (assoc :stamped-shape nil)
          (dissoc :sweep-base-shape)))))

(defn finalize-sweep-closed
  "Internal: finalize sweep as a closed torus-like mesh.
   Last ring connects back to first ring, no end caps.
   Called at end of extrude-closed macro.

   Forces (f 0) first to process any pending rotation, ensuring
   the final corner fillet is created before closing the loop."
  [state]
  ;; First, process any pending rotation by doing (f 0)
  ;; This creates the fillet for the last corner
  (let [state' (if (:pending-rotation state)
                 (f state 0)
                 state)
        rings (:sweep-rings state')]
    (if (>= (count rings) 2)
      ;; Build closed mesh from all rings
      (if-let [mesh (build-sweep-mesh rings true)]
        (-> state'
            (update :meshes conj mesh)
            (assoc :sweep-rings [])
            (assoc :stamped-shape nil)
            (dissoc :sweep-base-shape))
        ;; Fallback: clear state without adding mesh
        (-> state'
            (assoc :sweep-rings [])
            (assoc :stamped-shape nil)
            (dissoc :sweep-base-shape)))
      ;; Not enough rings - just clear state
      (-> state'
          (assoc :sweep-rings [])
          (assoc :stamped-shape nil)
          (dissoc :sweep-base-shape)))))

;; ============================================================
;; Loft - extrusion with shape transformation
;; ============================================================

(defn stamp-loft
  "Internal: stamp a shape for loft with transform function.
   Similar to stamp but also stores the base shape and transform function.
   steps: number of intermediate steps (default 16)"
  ([state shape transform-fn] (stamp-loft state shape transform-fn 16))
  ([state shape transform-fn steps]
   (if (shape? shape)
     (let [;; Apply transform at t=0 to get initial shape
           initial-shape (transform-fn shape 0)
           stamped (stamp-shape state initial-shape)
           ;; Save initial orientation
           initial-orientation {:position (:position state)
                                :heading (:heading state)
                                :up (:up state)}]
       (-> state
           (assoc :pen-mode :loft)
           (assoc :stamped-shape stamped)
           (assoc :sweep-rings [])           ; Will be built at finalize
           (assoc :loft-base-shape shape)
           (assoc :loft-transform-fn transform-fn)
           (assoc :loft-steps steps)
           (assoc :loft-total-dist 0)
           (assoc :loft-start-pos (:position state))
           (assoc :loft-orientations [initial-orientation])))
     state)))

(defn- interpolate-orientation
  "Interpolate between two orientations at parameter t (0-1)."
  [o1 o2 t]
  (let [;; Linear interpolation of position
        p1 (:position o1)
        p2 (:position o2)
        pos (v+ p1 (v* (v- p2 p1) t))
        ;; For heading/up, use linear interpolation then normalize
        ;; (proper slerp would be better but this works for small angles)
        h1 (:heading o1)
        h2 (:heading o2)
        heading (normalize (v+ h1 (v* (v- h2 h1) t)))
        u1 (:up o1)
        u2 (:up o2)
        up (normalize (v+ u1 (v* (v- u2 u1) t)))]
    {:position pos :heading heading :up up}))

(defn- find-orientation-at-dist
  "Find interpolated orientation at a given distance along the path."
  [orientations target-dist total-dist]
  (if (zero? total-dist)
    (first orientations)
    (let [;; Find the two waypoints that bracket this distance
          n (count orientations)]
      (loop [i 0]
        (if (>= i (dec n))
          ;; Past end - return last
          (last orientations)
          (let [o1 (nth orientations i)
                o2 (nth orientations (inc i))
                d1 (or (:dist o1) 0)
                d2 (:dist o2)]
            (if (and (<= d1 target-dist) (< target-dist d2))
              ;; Interpolate between o1 and o2
              (let [segment-len (- d2 d1)
                    local-t (if (pos? segment-len)
                              (/ (- target-dist d1) segment-len)
                              0)]
                (interpolate-orientation o1 o2 local-t))
              (recur (inc i)))))))))

(defn finalize-loft
  "Internal: finalize loft by generating rings at N steps with interpolated orientations.
   Called at end of loft macro."
  [state]
  (let [total-dist (:loft-total-dist state)
        base-shape (:loft-base-shape state)
        transform-fn (:loft-transform-fn state)
        orientations (:loft-orientations state)
        steps (:loft-steps state)]
    (if (and (pos? total-dist) base-shape transform-fn (>= (count orientations) 2))
      ;; Generate rings at N+1 evenly spaced points (0 to 1)
      (let [new-rings (vec
                       (for [i (range (inc steps))]
                         (let [t (/ i steps)
                               target-dist (* t total-dist)
                               ;; Get interpolated orientation at this distance
                               orientation (find-orientation-at-dist orientations target-dist total-dist)
                               ;; Get transformed 2D shape at this t
                               transformed-2d (transform-fn base-shape t)
                               ;; Create temp state with interpolated orientation
                               temp-state (-> state
                                              (assoc :position (:position orientation))
                                              (assoc :heading (:heading orientation))
                                              (assoc :up (:up orientation)))]
                           (stamp-shape temp-state transformed-2d))))
            mesh (build-sweep-mesh new-rings)]
        (if mesh
          (-> state
              (update :meshes conj mesh)
              (assoc :sweep-rings [])
              (assoc :stamped-shape nil)
              (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                      :loft-total-dist :loft-start-pos :loft-orientations))
          (-> state
              (assoc :sweep-rings [])
              (assoc :stamped-shape nil)
              (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                      :loft-total-dist :loft-start-pos :loft-orientations))))
      ;; Not enough data - just clear
      (-> state
          (assoc :sweep-rings [])
          (assoc :stamped-shape nil)
          (dissoc :loft-base-shape :loft-transform-fn :loft-steps
                  :loft-total-dist :loft-start-pos :loft-orientations)))))

;; ============================================================
;; Path - recorded turtle movements
;; ============================================================

(defn path?
  "Check if x is a path."
  [x]
  (and (map? x) (= :path (:type x))))

(defn make-path
  "Create a path from a vector of recorded commands.
   Each command is {:cmd :f/:b/:th/:tv/:tr :args [...]}"
  [commands]
  {:type :path
   :commands (vec commands)})

(defn make-recorder
  "Create a recorder turtle that captures commands.
   This is a regular turtle with an extra :recording vector."
  []
  (assoc (make-turtle) :recording []))

(defn- record-cmd
  "Record a command and execute it on the turtle."
  [state cmd args execute-fn]
  (-> state
      (update :recording conj {:cmd cmd :args (vec args)})
      (execute-fn)))

;; Recorder versions of movement commands
(defn rec-f [state dist]
  (record-cmd state :f [dist] #(f % dist)))

(defn rec-th [state angle]
  (record-cmd state :th [angle] #(th % angle)))

(defn rec-tv [state angle]
  (record-cmd state :tv [angle] #(tv % angle)))

(defn rec-tr [state angle]
  (record-cmd state :tr [angle] #(tr % angle)))

(defn path-from-recorder
  "Extract a path from a recorder turtle."
  [recorder]
  (make-path (:recording recorder)))

(defn run-path
  "Execute a path's commands on a turtle state.
   Returns the updated state."
  [state path]
  (if (path? path)
    (reduce (fn [s {:keys [cmd args]}]
              (case cmd
                :f  (f s (first args))
                :th (th s (first args))
                :tv (tv s (first args))
                :tr (tr s (first args))
                s))
            state
            (:commands path))
    state))

