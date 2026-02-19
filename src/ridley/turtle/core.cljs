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
   - (extrude shape movements...) - stamp shape and extrude via movements"
  (:require [ridley.schema :as schema]
            [ridley.math :as math]
            [ridley.turtle.extrusion :as extrusion]
            [ridley.turtle.loft :as loft]
            [ridley.turtle.attachment :as attachment]
            [ridley.turtle.bezier :as bezier]))

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
   :meshes []
   :stamps []             ; accumulated stamp outlines for debug visualization
   :state-stack []          ; stack for push-state/pop-state
   :anchors {}              ; named poses for mark/goto
   :attached nil            ; attachment state for face/mesh operations
   :resolution {:mode :n :value 16}  ; curve resolution (like OpenSCAD $fn)
   :material {:color 0x00aaff        ; hex color
              :metalness 0.3         ; 0-1, PBR metalness
              :roughness 0.7         ; 0-1, PBR roughness
              :opacity 1.0           ; 0-1, transparency
              :flat-shading true}})  ; flat vs smooth shading


;; --- State stack ---

(defn push-state
  "Push current turtle pose (position, heading, up, pen-mode) onto the stack.
   Use pop-state to restore. Useful for branching or temporary movements.
   Meshes and geometry created between push and pop are kept."
  [state]
  (let [pose {:position (:position state)
              :heading (:heading state)
              :up (:up state)
              :pen-mode (:pen-mode state)}]
    (update state :state-stack conj pose)))

(defn pop-state
  "Pop and restore the most recently pushed turtle pose from the stack.
   Returns state unchanged if stack is empty."
  [state]
  (let [stack (:state-stack state)]
    (if (empty? stack)
      state
      (let [pose (peek stack)]
        (-> state
            (assoc :position (:position pose))
            (assoc :heading (:heading pose))
            (assoc :up (:up pose))
            (assoc :pen-mode (:pen-mode pose))
            (assoc :state-stack (pop stack)))))))

(defn clear-stack
  "Clear the state stack without restoring any pose.
   Useful to reset after complex branching operations."
  [state]
  (assoc state :state-stack []))

;; --- Vector math utilities (shared via ridley.math) ---

(def ^:export v+ math/v+)
(def ^:export v- math/v-)
(def ^:export v* math/v*)
(def ^:export dot math/dot)
(def ^:export cross math/cross)
(def ^:export magnitude math/magnitude)
(def ^:export normalize math/normalize)
(def ^:export rotate-point-around-axis math/rotate-point-around-axis)
(def ^:export rotate-around-axis math/rotate-around-axis)

;; --- Re-exports from extrusion module (facade pattern) ---

(def ^:export build-sweep-mesh extrusion/build-sweep-mesh)
(def ^:export build-segment-mesh extrusion/build-segment-mesh)
(def ^:export build-corner-mesh extrusion/build-corner-mesh)
(def ^:export shape-radius extrusion/shape-radius)
(def ^:export compute-stamp-transform extrusion/compute-stamp-transform)
(def ^:export transform-2d-to-3d extrusion/transform-2d-to-3d)
(def ^:export stamp-shape extrusion/stamp-shape)
(def ^:export stamp-shape-with-holes extrusion/stamp-shape-with-holes)
(def ^:export earcut-triangulate extrusion/earcut-triangulate)
(def ^:export project-to-2d extrusion/project-to-2d)
(def ^:export triangulate-cap extrusion/triangulate-cap)
(def ^:export triangulate-cap-with-holes extrusion/triangulate-cap-with-holes)
(def ^:export ring-centroid extrusion/ring-centroid)
(def ^:export rotate-ring-around-axis extrusion/rotate-ring-around-axis)
(def ^:export scale-ring-from-centroid extrusion/scale-ring-from-centroid)
(def ^:export scale-ring-along-direction extrusion/scale-ring-along-direction)
(def ^:export generate-round-corner-rings extrusion/generate-round-corner-rings)
(def ^:export generate-tapered-corner-rings extrusion/generate-tapered-corner-rings)
(def ^:export sweep-two-shapes-with-holes extrusion/sweep-two-shapes-with-holes)
(def ^:export sweep-two-shapes extrusion/sweep-two-shapes)
(def ^:export is-rotation? extrusion/is-rotation?)
(def ^:export is-corner-rotation? extrusion/is-corner-rotation?)
(def ^:export is-path? extrusion/is-path?)
(def ^:export calc-shorten-for-angle extrusion/calc-shorten-for-angle)
(def ^:export analyze-closed-path extrusion/analyze-closed-path)
(def ^:export analyze-open-path extrusion/analyze-open-path)
(def ^:export is-simple-forward-path? extrusion/is-simple-forward-path?)
(def ^:export extrude-from-path extrusion/extrude-from-path)
(def ^:export extrude-closed-from-path extrusion/extrude-closed-from-path)

;; Private helpers also re-exported for internal use
(def check-num extrusion/check-num)
(def shape? extrusion/shape?)
(def apply-rotation-to-state extrusion/apply-rotation-to-state)

;; --- Re-exports from loft module (facade pattern) ---

(def ^:export stamp-loft loft/stamp-loft)
(def ^:export finalize-loft loft/finalize-loft)
(def ^:export analyze-loft-path loft/analyze-loft-path)
(def ^:export loft-from-path loft/loft-from-path)
(def ^:export bloft loft/bloft)

;; --- Re-exports from attachment module (facade pattern) ---

(def ^:export mesh-centroid attachment/mesh-centroid)
(def ^:export translate-mesh attachment/translate-mesh)
(def ^:export rotate-mesh attachment/rotate-mesh)
(def ^:export scale-mesh attachment/scale-mesh)
(def replace-mesh-in-state attachment/replace-mesh-in-state)
(def rotate-attached-mesh attachment/rotate-attached-mesh)
(def scale-attached-mesh attachment/scale-attached-mesh)
(def move-attached-mesh attachment/move-attached-mesh)
(def ^:export extract-face-perimeter attachment/extract-face-perimeter)
(def ^:export build-face-extrusion attachment/build-face-extrusion)
(def extrude-attached-face attachment/extrude-attached-face)
(def move-attached-face attachment/move-attached-face)
(def rotate-attached-face attachment/rotate-attached-face)
(def ^:export build-face-inset attachment/build-face-inset)
(def move-face-vertices-toward-center attachment/move-face-vertices-toward-center)
(def inset-attached-face attachment/inset-attached-face)
(def ^:export inset attachment/inset)
(def ^:export build-face-scale attachment/build-face-scale)
(def scale-attached-face attachment/scale-attached-face)
(def ^:export scale attachment/scale)
(def ^:export compute-triangle-normal attachment/compute-triangle-normal)
(def ^:export compute-face-info-internal attachment/compute-face-info-internal)
(def clone-mesh attachment/clone-mesh)
(def ^:export attached? attachment/attached?)

;; --- Re-exports from bezier module (facade pattern) ---

(def ^:export cubic-bezier-point bezier/cubic-bezier-point)
(def ^:export quadratic-bezier-point bezier/quadratic-bezier-point)
(def ^:export cubic-bezier-tangent bezier/cubic-bezier-tangent)
(def ^:export quadratic-bezier-tangent bezier/quadratic-bezier-tangent)
(def ^:export auto-control-points bezier/auto-control-points)
(def ^:export auto-control-points-with-target-heading bezier/auto-control-points-with-target-heading)
(def ^:export compute-bezier-control-points bezier/compute-bezier-control-points)
(def ^:export sample-bezier-segment bezier/sample-bezier-segment)

;; --- Pose reset ---

(defn reset-pose
  "Reset turtle position, orientation, and pen mode to defaults.
   Keeps accumulated geometry and meshes.
   Options:
   - (reset-pose state) - reset to origin, facing +X, up +Z, pen :on
   - (reset-pose state [x y z]) - reset to position, default orientation
   - (reset-pose state [x y z] :heading [hx hy hz]) - position + heading
   - (reset-pose state [x y z] :heading [hx hy hz] :up [ux uy uz]) - full control"
  ([state]
   (reset-pose state [0 0 0]))
  ([state position]
   (assoc state
          :position (vec position)
          :heading [1 0 0]
          :up [0 0 1]
          :pen-mode :on
          :stamped-shape nil
          :sweep-rings []
          :pending-rotation nil))
  ([state position & {:keys [heading up]}]
   (let [h (if heading (normalize (vec heading)) [1 0 0])
         u (if up (normalize (vec up)) [0 0 1])]
     (assoc state
            :position (vec position)
            :heading h
            :up u
            :pen-mode :on
            :stamped-shape nil
            :sweep-rings []
            :pending-rotation nil))))

;; --- Rotation utilities (needed by fillet) ---

(defn- deg->rad [deg]
  (* deg (/ Math/PI 180)))

(defn- right-vector
  "Calculate the right vector (heading x up)."
  [state]
  (cross (:heading state) (:up state)))

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

;; --- Resolution settings (like OpenSCAD $fn/$fa/$fs) ---

(defn resolution
  "Set the default curve resolution for arcs, beziers, and primitives.

   Modes:
   - (resolution state :n 32)   ; fixed number of segments
   - (resolution state :a 5)    ; minimum angle per segment (degrees)
   - (resolution state :s 0.5)  ; minimum segment length (units)

   This affects: arc-h, arc-v, bezier-to, circle, sphere, cyl, cone, round joints."
  [state mode value]
  (assoc state :resolution {:mode mode :value value}))

(defn- get-resolution
  "Get current resolution settings, defaulting to {:mode :n :value 16}."
  [state]
  (or (:resolution state) {:mode :n :value 16}))

(defn calc-arc-steps
  "Calculate steps for an arc based on resolution settings.
   arc-length: approximate arc length in units
   angle-deg: total angle in degrees"
  [state arc-length angle-deg]
  (let [{:keys [mode value]} (get-resolution state)]
    (case mode
      :n value
      :a (max 1 (int (Math/ceil (/ (Math/abs angle-deg) value))))
      :s (max 1 (int (Math/ceil (/ arc-length value))))
      ;; default
      value)))

(defn calc-bezier-steps
  "Calculate steps for a bezier based on resolution settings.
   approx-length: approximate curve length in units"
  [state approx-length]
  (let [{:keys [mode value]} (get-resolution state)]
    (case mode
      :n value
      :a value  ; for bezier, :a mode uses value as steps (angle doesn't apply)
      :s (max 1 (int (Math/ceil (/ approx-length value))))
      ;; default
      value)))

(defn calc-circle-segments
  "Calculate segments for a full circle based on resolution.
   circumference: circle circumference in units"
  [state circumference]
  (let [{:keys [mode value]} (get-resolution state)]
    (case mode
      :n value
      :a (max 8 (int (Math/ceil (/ 360 value))))
      :s (max 8 (int (Math/ceil (/ circumference value))))
      ;; default
      value)))

(defn calc-round-steps
  "Calculate steps for a round joint based on resolution and angle.
   angle-deg: the bend angle in degrees"
  [state angle-deg]
  (let [{:keys [mode value]} (get-resolution state)]
    (case mode
      ;; :n mode: scale proportionally with angle (90° = 1/4 of full circle)
      :n (max 2 (int (* value (/ (Math/abs angle-deg) 360))))
      :a (max 2 (int (Math/ceil (/ (Math/abs angle-deg) value))))
      :s value  ; :s mode doesn't apply well to corners, use value as steps
      ;; default
      4)))

(defn- process-pending-rotation
  "Process a pending rotation when moving forward.
   Creates separate meshes for the pre-rotation segment and corner.

   Strategy:
   1. Finalize current segment (rings accumulated so far) - shortened by radius
   2. Create corner mesh connecting end of old segment to start of new segment
   3. Start fresh rings for next segment - starting at radius offset

   This ensures no internal faces at corners."
  [state _dist]
  (let [pending (:pending-rotation state)
        {:keys [heading up]} (apply-rotations state pending)
        base-shape (:sweep-base-shape state)
        radius (shape-radius base-shape)
        old-heading (:heading state)
        pos (:position state)
        rings (:sweep-rings state)

        ;; Corner position: pull back from current pos by radius along old heading
        corner-pos (v+ pos (v* old-heading (- radius)))

        ;; New turtle position: advance from corner by radius along NEW heading
        new-pos (v+ corner-pos (v* heading radius))

        ;; Create the end ring of the pre-rotation segment (at corner-pos, old orientation)
        end-ring (stamp-shape (assoc state :position corner-pos) base-shape)

        ;; Replace last ring with shortened version and build segment mesh
        segment-rings (if (seq rings)
                        (conj (pop rings) end-ring)
                        [end-ring])
        segment-mesh (build-segment-mesh segment-rings)

        ;; Create first ring of new segment (at new-pos, new orientation)
        first-new-ring (stamp-shape (-> state
                                        (assoc :position new-pos)
                                        (assoc :heading heading)
                                        (assoc :up up))
                                    base-shape)

        ;; Create corner mesh: from end-ring to first-new-ring
        ;; This connects the end of old segment to start of new segment
        corner-mesh (build-corner-mesh end-ring first-new-ring)]
    (-> state
        (assoc :heading heading)
        (assoc :up up)
        (assoc :position new-pos)
        ;; Add segment mesh and corner mesh to accumulated meshes
        (update :meshes (fn [meshes]
                          (cond-> meshes
                            segment-mesh (conj segment-mesh)
                            corner-mesh (conj corner-mesh))))
        ;; Start fresh rings for next segment
        (assoc :sweep-rings [first-new-ring])
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
                                  :to new-pos
                                  :color (get-in state [:material :color])}))

      :shape
      ;; Shape mode: check for pending rotation first
      (let [state' (if (:pending-rotation state)
                     (process-pending-rotation state dist)
                     state)
            base-shape (:sweep-base-shape state')
            rings (:sweep-rings state')
            closed? (:sweep-closed? state')
            radius (shape-radius base-shape)
            ;; For closed extrusion with empty rings: add offset first ring
            state'' (if (and closed? (empty? rings))
                      ;; First movement in closed extrusion: start at offset position
                      (let [offset-pos (v+ (:position state') (v* (:heading state') radius))
                            first-ring (stamp-shape (assoc state' :position offset-pos) base-shape)]
                        (-> state'
                            (assoc :position offset-pos)
                            (assoc :sweep-rings [first-ring])
                            ;; Save this offset first ring for closing
                            (assoc :sweep-closed-first-ring first-ring)))
                      state')
            ;; Move forward from current position
            new-pos' (v+ (:position state'') (v* (:heading state'') dist))
            new-state (assoc state'' :position new-pos')
            ;; Re-stamp the 2D shape at the new position/orientation
            new-shape (stamp-shape new-state base-shape)]
        (-> new-state
            (assoc :stamped-shape new-shape)
            ;; Add new ring to sweep-rings (mesh built at finalize)
            (update :sweep-rings conj new-shape)))

      :loft
      ;; Loft mode: track distance and orientations (rings built at finalize)
      ;; Check for pending rotation first - record corner info
      (let [pending (:pending-rotation state)
            ;; If there's a pending rotation, apply it and record corner
            state' (if pending
                     (let [{:keys [heading up]} (apply-rotations state pending)
                           old-heading (:heading state)
                           ;; Record corner info for processing at finalize
                           corner {:position pos
                                   :old-heading old-heading
                                   :new-heading heading
                                   :new-up up
                                   :dist-at-corner (:loft-total-dist state)}]
                       (-> state
                           (assoc :heading heading)
                           (assoc :up up)
                           (update :loft-corners (fnil conj []) corner)
                           (dissoc :pending-rotation)))
                     state)
            ;; Now do the forward movement with updated heading
            current-shape (:stamped-shape state')
            actual-heading (:heading state')
            offset (v* actual-heading dist)
            new-shape (mapv #(v+ % offset) current-shape)
            new-pos' (v+ (:position state') offset)
            new-total-dist (+ (:loft-total-dist state') (Math/abs dist))
            ;; Save turtle orientation for this waypoint
            orientation {:position new-pos'
                         :heading actual-heading
                         :up (:up state')
                         :dist new-total-dist}]
        (-> state'
            (assoc :position new-pos')
            (assoc :stamped-shape new-shape)
            (assoc :loft-total-dist new-total-dist)
            (update :loft-orientations conj orientation)))

      ;; Other modes - just move
      (assoc state :position new-pos))))

(defn f
  "Move forward by distance. Use negative values to move backward.
   When attached to a mesh, moves the entire mesh.
   When attached to a face, extrudes the face along its normal."
  [state dist]
  (check-num dist "f")
  (if-let [attachment (:attached state)]
    (case (:type attachment)
      :pose (move-attached-mesh state dist)
      :face (if (:extrude-mode attachment)
              (extrude-attached-face state dist)
              (move-attached-face state dist))
      (move state (:heading state) dist))
    (move state (:heading state) dist)))

;; --- Lateral movement commands ---

(defn move-lateral
  "Move turtle along a local axis without changing heading or up.
   Does NOT generate sweep rings or trigger corner detection.
   Draws a line if pen is :on. Throws if in :shape/:loft mode."
  [state axis dist]
  (check-num dist "lateral move")
  (when (#{:shape :loft} (:pen-mode state))
    (throw (js/Error. "Lateral movement (u/d/rt/lt) is not allowed inside extrude/loft")))
  (let [offset (v* axis dist)
        new-pos (v+ (:position state) offset)
        mode (:pen-mode state)]
    (case mode
      :on
      (-> state
          (assoc :position new-pos)
          (update :geometry conj {:type :line
                                  :from (:position state)
                                  :to new-pos
                                  :color (get-in state [:material :color])}))
      ;; :off or other — just move
      (assoc state :position new-pos))))

(defn- move-attached-mesh-lateral
  "Move attached mesh along a given axis (lateral translation)."
  [state axis dist]
  (let [attachment (:attached state)
        mesh (:mesh attachment)
        offset (v* axis dist)
        new-mesh (attachment/translate-mesh mesh offset)
        new-pos (v+ (:position state) offset)]
    (-> state
        (replace-mesh-in-state mesh new-mesh)
        (assoc :position new-pos)
        (assoc-in [:attached :mesh] new-mesh)
        (assoc-in [:attached :original-pose :position] new-pos))))

(defn move-up
  "Move along turtle's up axis without changing heading.
   Positive dist = up direction. When attached to a mesh, translates it."
  [state dist]
  (check-num dist "u")
  (if (= :pose (get-in state [:attached :type]))
    (move-attached-mesh-lateral state (:up state) dist)
    (move-lateral state (:up state) dist)))

(defn move-down
  "Move opposite to turtle's up axis. Equivalent to (move-up state (- dist))."
  [state dist]
  (move-up state (- dist)))

(defn move-right
  "Move along turtle's right axis (heading × up) without changing heading.
   Positive dist = right. When attached to a mesh, translates it."
  [state dist]
  (check-num dist "rt")
  (let [right (normalize (cross (:heading state) (:up state)))]
    (if (= :pose (get-in state [:attached :type]))
      (move-attached-mesh-lateral state right dist)
      (move-lateral state right dist))))

(defn move-left
  "Move opposite to turtle's right axis. Equivalent to (move-right state (- dist))."
  [state dist]
  (move-right state (- dist)))

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
   When attached to mesh, rotates the entire mesh around up axis.
   When attached to face, rotates the face around up axis (tilt sideways).
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (check-num angle "th")
  (cond
    ;; Attached to mesh pose: rotate the mesh
    (= :pose (get-in state [:attached :type]))
    (rotate-attached-mesh state (:up state) angle)

    ;; Attached to face: rotate the face around up axis (tilt sideways)
    (= :face (get-in state [:attached :type]))
    (rotate-attached-face state (:up state) angle)

    ;; Shape or loft mode: store pending rotation
    (#{:shape :loft} (:pen-mode state))
    (store-pending-rotation state :th angle)

    ;; Normal mode: apply rotation immediately
    :else
    (let [rad (deg->rad angle)
          new-heading (rotate-around-axis (:heading state) (:up state) rad)]
      (assoc state :heading new-heading))))

(defn tv
  "Turn vertical (pitch) - rotate heading and up around right axis.
   Positive angle pitches up.
   When attached to mesh, rotates the entire mesh around right axis.
   When attached to face, rotates the face around right axis (tilts the face).
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (check-num angle "tv")
  (cond
    ;; Attached to mesh pose: rotate the mesh
    (= :pose (get-in state [:attached :type]))
    (let [right (right-vector state)]
      (rotate-attached-mesh state right angle))

    ;; Attached to face: rotate the face around right axis
    (= :face (get-in state [:attached :type]))
    (let [right (right-vector state)]
      (rotate-attached-face state right angle))

    ;; Shape or loft mode: store pending rotation
    (#{:shape :loft} (:pen-mode state))
    (store-pending-rotation state :tv angle)

    ;; Normal mode: apply rotation immediately
    :else
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
   When attached to mesh, rotates the entire mesh around heading axis.
   When attached to face, rotates the face around its normal (spins in place).
   In shape mode, stores pending rotation for fillet creation on next (f)."
  [state angle]
  (check-num angle "tr")
  (cond
    ;; Attached to mesh pose: rotate the mesh
    (= :pose (get-in state [:attached :type]))
    (rotate-attached-mesh state (:heading state) angle)

    ;; Attached to face: rotate the face around its normal (heading = normal)
    (= :face (get-in state [:attached :type]))
    (rotate-attached-face state (:heading state) angle)

    ;; Shape or loft mode: store pending rotation
    (#{:shape :loft} (:pen-mode state))
    (store-pending-rotation state :tr angle)

    ;; Normal mode: apply rotation immediately
    :else
    (let [rad (deg->rad angle)
          new-up (rotate-around-axis (:up state) (:heading state) rad)]
      (assoc state :up new-up))))

;; --- Arc commands ---

(defn arc-h
  "Draw a horizontal arc (turning around up axis while moving).

   Parameters:
   - radius: arc radius in units
   - angle: total turn angle in degrees (positive = left, negative = right)
   - :steps n (optional): override resolution

   The turtle moves along an arc, ending at radius * angle_rad distance
   with heading rotated by angle degrees.

   Example: (arc-h 10 90) draws a quarter circle turning left"
  [state radius angle & {:keys [steps]}]
  (if (or (zero? radius) (zero? angle))
    state
    (let [angle-rad (* (Math/abs angle) (/ Math/PI 180))
          arc-length (* radius angle-rad)
          actual-steps (or steps (calc-arc-steps state arc-length (Math/abs angle)))
          step-angle-deg (/ angle actual-steps)
          step-angle-rad (/ angle-rad actual-steps)
          ;; Chord length for each step: 2 * r * sin(θ/2)
          step-dist (* 2 radius (Math/sin (/ step-angle-rad 2)))
          ;; First step: rotate half, then move
          ;; Middle steps: rotate full, then move
          ;; This ensures proper extrusion integration (pending rotation consumed by f)
          half-angle (/ step-angle-deg 2)]
      (-> (reduce (fn [s _]
                    (-> s
                        (th step-angle-deg)
                        (f step-dist)))
                  ;; First: rotate half and move
                  (-> state
                      (th half-angle)
                      (f step-dist))
                  ;; Remaining steps (n-1)
                  (range (dec actual-steps)))
          ;; Final half rotation to complete the arc
          (th half-angle)))))

(defn arc-v
  "Draw a vertical arc (pitching around right axis while moving).

   Parameters:
   - radius: arc radius in units
   - angle: total pitch angle in degrees (positive = up, negative = down)
   - :steps n (optional): override resolution

   The turtle moves along an arc in the vertical plane.

   Example: (arc-v 10 45) draws an arc pitching upward"
  [state radius angle & {:keys [steps]}]
  (if (or (zero? radius) (zero? angle))
    state
    (let [angle-rad (* (Math/abs angle) (/ Math/PI 180))
          arc-length (* radius angle-rad)
          actual-steps (or steps (calc-arc-steps state arc-length (Math/abs angle)))
          step-angle-deg (/ angle actual-steps)
          step-angle-rad (/ angle-rad actual-steps)
          ;; Chord length for each step: 2 * r * sin(θ/2)
          step-dist (* 2 radius (Math/sin (/ step-angle-rad 2)))
          ;; First step: rotate half, then move
          ;; Middle steps: rotate full, then move
          half-angle (/ step-angle-deg 2)]
      (-> (reduce (fn [s _]
                    (-> s
                        (tv step-angle-deg)
                        (f step-dist)))
                  ;; First: rotate half and move
                  (-> state
                      (tv half-angle)
                      (f step-dist))
                  ;; Remaining steps (n-1)
                  (range (dec actual-steps)))
          ;; Final half rotation to complete the arc
          (tv half-angle)))))

;; --- Bezier commands ---
;; Pure bezier math functions are in ridley.turtle.bezier module.
;; This file contains the higher-level commands that use turtle movement (f).

(defn- bezier-walk
  "Walk along a bezier curve, moving directly to each sample point.
   Uses chord directions for drawing (position accuracy), then sets
   the tangent heading for continuity. First step preserves the existing
   heading, last step uses exact end tangent.
   Uses parallel transport for smooth, twist-free frame evolution."
  [state steps point-fn tangent-fn]
  (let [last-i (dec steps)
        end-heading (tangent-fn 1)]
    (reduce
     (fn [s i]
       (let [t (/ (inc i) steps)
             new-pos (point-fn t)
             current-pos (:position s)
             move-dir (v- new-pos current-pos)
             dist (magnitude move-dir)]
         (if (> dist 0.001)
           (let [chord-heading (normalize move-dir)
                 prev-heading (:heading s)
                 ;; Final heading for continuity:
                 ;; First step: keep existing heading
                 ;; Last step: use exact end tangent
                 ;; Middle steps: use chord
                 final-heading (cond (zero? i) prev-heading
                                     (= i last-i) end-heading
                                     :else chord-heading)
                 ;; Parallel transport: rotate up with the heading change
                 new-up (bezier/parallel-transport-up (:up s) prev-heading final-heading)]
             ;; Move using chord direction (for accurate position),
             ;; then set final heading for tangent continuity
             (-> s
                 (assoc :heading chord-heading)  ; use chord to draw correctly
                 (f dist)                        ; draws line to new-pos
                 (assoc :heading final-heading)  ; restore tangent heading
                 (assoc :up new-up)))
           s)))
     state
     (range steps))))

(defn bezier-to
  "Draw a bezier curve to target position.

   Usage:
   (bezier-to state target)                    ; auto control points
   (bezier-to state target [cx cy cz])         ; quadratic with 1 control point
   (bezier-to state target [c1...] [c2...])    ; cubic with 2 control points
   (bezier-to state target :steps 24)          ; auto with more steps
   (bezier-to state target [c1] [c2] :steps 24); explicit with more steps

   With 0 control points: generates smooth curve starting along current heading
   With 1 control point: quadratic bezier
   With 2 control points: cubic bezier"
  [state target & args]
  (let [;; Separate vector args (control points) from keyword args
        {control-points true options false} (group-by vector? args)
        {:keys [steps]} (apply hash-map (flatten options))
        p0 (:position state)
        p3 (vec target)
        approx-length (magnitude (v- p3 p0))
        actual-steps (or steps (calc-bezier-steps state approx-length))
        n-controls (count control-points)]
    (if (< approx-length 0.001)
      state  ; target same as current position
      (cond
        ;; 2 control points: cubic bezier
        (= n-controls 2)
        (let [[c1 c2] control-points]
          (bezier-walk state actual-steps
                       #(cubic-bezier-point p0 c1 c2 p3 %)
                       #(cubic-bezier-tangent p0 c1 c2 p3 %)))

        ;; 1 control point: quadratic bezier
        (= n-controls 1)
        (let [c1 (first control-points)]
          (bezier-walk state actual-steps
                       #(quadratic-bezier-point p0 c1 p3 %)
                       #(quadratic-bezier-tangent p0 c1 p3 %)))

        ;; 0 control points: auto-generate cubic
        :else
        (let [[c1 c2] (auto-control-points p0 (:heading state) p3)]
          (bezier-walk state actual-steps
                       #(cubic-bezier-point p0 c1 c2 p3 %)
                       #(cubic-bezier-tangent p0 c1 c2 p3 %)))))))

(defn bezier-to-anchor
  "Draw a bezier curve to a named anchor position.
   When auto-generating control points (no explicit [c1] [c2] provided),
   the curve respects both the current heading AND the anchor's saved heading,
   creating a smooth connection that honors both directions.

   Usage:
   (bezier-to-anchor state :name)              ; auto control points (respects both headings)
   (bezier-to-anchor state :name [c1] [c2])    ; explicit control points
   (bezier-to-anchor state :name :steps 24)    ; auto with more steps
   (bezier-to-anchor state :name :tension 0.5) ; control curve width (0=tight, 1=wide)"
  [state anchor-name & args]
  (if-let [anchor (get-in state [:anchors anchor-name])]
    (let [{control-points true options false} (group-by vector? args)
          {:keys [steps tension]} (apply hash-map (flatten options))
          n-controls (count control-points)]
      (if (zero? n-controls)
        ;; Auto-generate control points using BOTH headings
        (let [p0 (:position state)
              p3 (:position anchor)
              approx-length (magnitude (v- p3 p0))
              actual-steps (or steps (calc-bezier-steps state approx-length))
              [c1 c2] (auto-control-points-with-target-heading
                        p0 (:heading state) p3 (:heading anchor) (or tension 0.33))]
          (if (< approx-length 0.001)
            state
            (bezier-walk state actual-steps
                         #(cubic-bezier-point p0 c1 c2 p3 %)
                         #(cubic-bezier-tangent p0 c1 c2 p3 %))))
        ;; Explicit control points - delegate to bezier-to
        (apply bezier-to state (:position anchor) args)))
    state))

;; --- Joint mode (for future corner styles) ---

(defn joint-mode
  "Set the joint mode for corners during extrusion.
   Currently supported: :flat (default)
   Future: :round, :tapered"
  [state mode]
  (assoc state :joint-mode mode))

;; --- Creation pose override ---

(defn ^:export set-creation-pose
  "Set the creation-pose of a mesh to the current turtle pose.
   This determines where (attach mesh) will place the turtle.
   Useful after boolean operations when the default inherited pose
   doesn't match the desired attachment point."
  [state mesh]
  (assoc mesh :creation-pose
    {:position (:position state)
     :heading (:heading state)
     :up (:up state)}))

;; --- Material settings ---

(defn set-color
  "Set the color for subsequent meshes.
   Accepts hex integer (0xff0000 for red) or RGB values (255 0 0)."
  ([state hex-color]
   (assoc-in state [:material :color] hex-color))
  ([state r g b]
   (let [hex (+ (bit-shift-left (int r) 16)
                (bit-shift-left (int g) 8)
                (int b))]
     (assoc-in state [:material :color] hex))))

(def ^:private default-material
  "Default material settings."
  {:color 0x00aaff
   :metalness 0.3
   :roughness 0.7
   :opacity 1.0
   :flat-shading true})

(defn set-material
  "Set material properties for subsequent meshes.
   Unspecified options reset to defaults.
   Options:
     :color - hex color (e.g., 0xff0000)
     :metalness - 0-1, how metallic the surface appears
     :roughness - 0-1, how rough/smooth the surface is
     :opacity - 0-1, transparency (1 = opaque)
     :flat-shading - true/false for flat vs smooth shading"
  [state & {:keys [color metalness roughness opacity flat-shading]}]
  (assoc state :material
         {:color (or color (:color default-material))
          :metalness (or metalness (:metalness default-material))
          :roughness (or roughness (:roughness default-material))
          :opacity (or opacity (:opacity default-material))
          :flat-shading (if (some? flat-shading) flat-shading (:flat-shading default-material))}))

(defn get-material
  "Get the current material settings."
  [state]
  (:material state))

(defn reset-material
  "Reset material to default values."
  [state]
  (assoc state :material default-material))

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
   Also stores the 2D base shape for re-stamping after rotations.
   For closed extrusions, also saves initial orientation to compute
   the correct closing connection."
  [state shape]
  (if (shape? shape)
    (let [stamped (stamp-shape state shape)]
      (-> state
          (assoc :pen-mode :shape)
          (assoc :stamped-shape stamped)
          (assoc :sweep-base-shape shape)  ; Store 2D shape for re-stamping
          (assoc :sweep-rings [stamped])   ; First ring is the stamped shape
          (assoc :sweep-first-ring stamped) ; Save for caps and closing
          (assoc :sweep-initial-pos (:position state))
          (assoc :sweep-initial-heading (:heading state))
          (assoc :sweep-initial-up (:up state))))
    state))

(defn stamp-closed
  "Internal: stamp a shape for closed extrusion. Used by extrude-closed macro.
   Unlike stamp, does NOT add the first ring to sweep-rings because for closed
   extrusions, all segments will be shortened on both ends.
   The first ring will be added during the first forward movement."
  [state shape]
  (if (shape? shape)
    (let [stamped (stamp-shape state shape)]
      (-> state
          (assoc :pen-mode :shape)
          (assoc :stamped-shape stamped)
          (assoc :sweep-base-shape shape)
          (assoc :sweep-rings [])          ; Empty! First ring added on first (f)
          (assoc :sweep-first-ring stamped) ; Save for reference
          (assoc :sweep-initial-pos (:position state))
          (assoc :sweep-initial-heading (:heading state))
          (assoc :sweep-initial-up (:up state))
          (assoc :sweep-closed? true)))    ; Mark as closed extrusion
    state))

(defn- stamp-single-shape
  "Stamp a single shape at current turtle pose. Returns stamp data map.
   Optional color is passed through for rendering."
  [state shape color]
  (let [stamp-3d (stamp-shape-with-holes state shape)
        outer-3d (:outer stamp-3d)
        holes-3d (or (:holes stamp-3d) [])
        all-verts (into (vec outer-3d) (apply concat holes-3d))
        outer-2d (:points shape)
        holes-2d (or (:holes shape) [])
        faces (earcut-triangulate outer-2d holes-2d)]
    (cond-> {:vertices all-verts :faces faces}
      color (assoc :color color))))

(defn stamp-debug
  "Visualize a 2D shape (or vector of shapes) at the current turtle position/orientation.
   Projects the shape into 3D and stores it as a semi-transparent surface.
   Pre-computes triangulated faces for rendering.
   Does not modify turtle position or heading.
   Optional color (hex int) overrides the default orange."
  [state shape-or-shapes & {:keys [color]}]
  (cond
    ;; Vector of shapes (e.g. from shape-xor)
    (and (vector? shape-or-shapes) (seq shape-or-shapes) (shape? (first shape-or-shapes)))
    (reduce (fn [s shape] (update s :stamps conj (stamp-single-shape s shape color)))
            state shape-or-shapes)
    ;; Single shape
    (shape? shape-or-shapes)
    (update state :stamps conj (stamp-single-shape state shape-or-shapes color))
    ;; Not a shape
    :else state))

(defn finalize-sweep
  "Internal: finalize sweep by building final segment mesh with caps.
   Called at end of extrude macro.

   The meshes list may already contain segment and corner meshes from
   rotations. This adds the final segment and puts caps on the whole thing."
  [state]
  (let [rings (:sweep-rings state)
        existing-meshes (:meshes state)
        first-ring (:sweep-first-ring state)]
    (if (>= (count rings) 2)
      ;; Build final segment from remaining rings
      ;; Detect if extrusion went backward by comparing first and last ring centroids
      ;; with the sweep-initial-heading
      (let [ring-centroid-fn (fn [ring]
                               (let [n (count ring)]
                                 (v* (reduce v+ ring) (/ 1.0 n))))
            first-centroid (ring-centroid-fn (first rings))
            last-centroid (ring-centroid-fn (last rings))
            extrusion-dir (v- last-centroid first-centroid)
            initial-heading (or (:sweep-initial-heading state) (:heading state))
            ;; If dot product is negative, extrusion went backward
            backward? (neg? (dot extrusion-dir initial-heading))
            ;; Flip winding for backward extrusion to correct normals
            flip-winding? backward?
            final-segment (build-segment-mesh rings flip-winding?)
            ;; Determine the actual first and last rings for caps
            actual-first-ring (or first-ring (first rings))
            actual-last-ring (last rings)
            ;; Add caps to final segment if it's the only mesh,
            ;; otherwise we need to add caps to the combined structure
            all-meshes (if final-segment
                         (conj existing-meshes final-segment)
                         existing-meshes)
            ;; Create separate cap meshes using ear clipping for concave shapes
            n-verts (count actual-first-ring)
            ;; Compute normals from extrusion direction (centroid-based)
            ;; More robust than cross-product of ring vertices (which fails with duplicate points)
            ring-centroid (fn [ring]
                            (let [n (count ring)]
                              (v* (reduce v+ ring) (/ 1.0 n))))
            ;; For bottom cap: direction is from actual-first-ring toward first ring in rings
            ;; (or second ring if actual-first-ring IS the first ring)
            bottom-reference-ring (if (= actual-first-ring (first rings))
                                    (second rings)
                                    (first rings))
            bottom-normal (when (and (>= n-verts 3) bottom-reference-ring)
                            (v* (normalize (v- (ring-centroid bottom-reference-ring)
                                               (ring-centroid actual-first-ring)))
                                -1))  ; points backward (away from interior)
            ;; For top cap: direction is from second-to-last ring toward last ring
            second-to-last-ring (nth rings (- (count rings) 2))
            top-normal (when (>= n-verts 3)
                         (normalize (v- (ring-centroid actual-last-ring)
                                        (ring-centroid second-to-last-ring))))
            ;; triangulate-cap handles winding via project-to-2d, no flip needed
            bottom-cap-flip false
            top-cap-flip false
            ;; Bottom cap mesh
            bottom-cap-mesh (when (>= n-verts 3)
                              {:type :mesh
                               :primitive :cap
                               :vertices (vec actual-first-ring)
                               :faces (triangulate-cap actual-first-ring 0 bottom-normal bottom-cap-flip)})
            ;; Top cap mesh
            top-cap-mesh (when (>= n-verts 3)
                           {:type :mesh
                            :primitive :cap
                            :vertices (vec actual-last-ring)
                            :faces (triangulate-cap actual-last-ring 0 top-normal top-cap-flip)})]
        (-> state
            (assoc :meshes (cond-> all-meshes
                             bottom-cap-mesh (conj bottom-cap-mesh)
                             top-cap-mesh (conj top-cap-mesh)))
            (assoc :sweep-rings [])
            (assoc :stamped-shape nil)
            (dissoc :sweep-base-shape :sweep-first-ring :sweep-closed?
                    :sweep-closed-first-ring
                    :sweep-initial-pos :sweep-initial-heading :sweep-initial-up)))
      ;; Not enough rings - just clear state
      (-> state
          (assoc :sweep-rings [])
          (assoc :stamped-shape nil)
          (dissoc :sweep-base-shape :sweep-first-ring :sweep-closed?
                  :sweep-closed-first-ring
                  :sweep-initial-pos :sweep-initial-heading :sweep-initial-up)))))

(defn finalize-sweep-closed
  "Internal: finalize sweep as a closed torus-like mesh.
   Last ring connects back to first ring, no end caps.
   Called at end of extrude-closed macro.

   Forces (f 0) first to process any pending rotation, ensuring
   the final corner fillet is created before closing the loop.

   With stamp-closed, the first ring is already offset by radius
   and saved in :sweep-closed-first-ring.
   The closing corner connects:
   - FROM: last ring (after final corner was processed)
   - TO: the saved first ring (offset, created during first movement)"
  [state]
  ;; First, process any pending rotation by doing (f 0)
  ;; This creates the fillet for the last corner
  (let [state' (if (:pending-rotation state)
                 (f state 0)
                 state)
        rings (:sweep-rings state')
        existing-meshes (:meshes state')
        ;; Use the saved first ring (from first movement with offset)
        closed-first-ring (:sweep-closed-first-ring state')]
    (if (and (>= (count rings) 1) closed-first-ring)
      ;; Build final segment (if any) and closing corner
      (let [;; The last ring is at post-corner position
            last-ring (last rings)

            ;; If there are 2+ rings, we have a final segment to build
            ;; Otherwise, just the closing corner
            final-segment (when (>= (count rings) 2)
                            (build-segment-mesh rings))

            ;; Create closing corner: from last ring to the saved first ring
            closing-corner (build-corner-mesh last-ring closed-first-ring)

            ;; Combine all meshes
            all-meshes (cond-> existing-meshes
                         final-segment (conj final-segment)
                         closing-corner (conj closing-corner))]
        (-> state'
            (assoc :meshes all-meshes)
            (assoc :sweep-rings [])
            (assoc :stamped-shape nil)
            (dissoc :sweep-base-shape :sweep-first-ring :sweep-closed?
                    :sweep-closed-first-ring
                    :sweep-initial-pos :sweep-initial-heading :sweep-initial-up)))
      ;; Not enough rings or missing initial data - just clear state
      (-> state'
          (assoc :sweep-rings [])
          (assoc :stamped-shape nil)
          (dissoc :sweep-base-shape :sweep-first-ring :sweep-closed?
                  :sweep-closed-first-ring
                  :sweep-initial-pos :sweep-initial-heading :sweep-initial-up)))))

;; ============================================================
;; Path - recorded turtle movements
;; ============================================================

(defn ^:export path?
  "Check if x is a path."
  [x]
  (and (map? x) (= :path (:type x))))

(defn make-path
  "Create a path from a vector of recorded commands.
   Each command is {:cmd :f/:b/:th/:tv/:tr :args [...]}"
  [commands]
  {:type :path
   :commands (vec commands)})

(defn quick-path
  "Create a path from compact numeric notation.
   Numbers alternate between forward distance and turn angle.
   Accepts: (quick-path 30 90 30), (quick-path [30 90 30]),
   or (def v [30 90 30]) (quick-path v)"
  ([x] (quick-path x nil))
  ([x y & more]
   (let [nums (if (and (nil? y) (sequential? x))
                x
                (cons x (cons y more)))]
     {:type :path
      :commands
      (->> nums
           (partition-all 2)
           (mapcat (fn [[a b]]
                     (if (some? b)
                       [{:cmd :f :args [a]}
                        {:cmd :th :args [b]}]
                       [{:cmd :f :args [a]}])))
           vec)})))

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

(defn rec-u [state dist]
  (record-cmd state :u [dist] #(move-up % dist)))

(defn rec-rt [state dist]
  (record-cmd state :rt [dist] #(move-right % dist)))

(defn rec-lt [state dist]
  (record-cmd state :lt [dist] #(move-left % dist)))

(defn rec-set-heading
  "Record a set-heading command that directly sets heading and up vectors."
  [state heading up]
  (record-cmd state :set-heading [heading up]
              #(-> %
                   (assoc :heading (normalize heading))
                   (assoc :up (normalize up)))))

;; Record-only commands: these don't affect heading/position during recording
;; because they are context-dependent (only meaningful in attach/face context).
(defn rec-inset
  "Record an inset command. Record-only — no state change during recording."
  [state amount]
  (update state :recording conj {:cmd :inset :args [amount]}))

(defn rec-scale
  "Record a scale command. Record-only — no state change during recording."
  [state factor]
  (update state :recording conj {:cmd :scale :args [factor]}))

(defn rec-move-to
  "Record a move-to command. Record-only — target resolution happens at replay."
  [state target & [mode]]
  (update state :recording conj {:cmd :move-to :args (if mode [target mode] [target])}))

(defn rec-play-path
  "Splice a sub-path's commands into the current recording.
   Movement commands (f, th, tv, tr, u, rt, lt, set-heading) are both recorded
   AND executed on the recorder state to keep heading in sync.
   Record-only commands (inset, scale, move-to, mark) are just appended."
  [state sub-path]
  (if (path? sub-path)
    (reduce (fn [s {:keys [cmd args] :as command}]
              (case cmd
                :f  (rec-f s (first args))
                :th (rec-th s (first args))
                :tv (rec-tv s (first args))
                :tr (rec-tr s (first args))
                :u  (rec-u s (first args))
                :rt (rec-rt s (first args))
                :lt (rec-lt s (first args))
                :set-heading (rec-set-heading s (first args) (second args))
                ;; record-only: just append
                (update s :recording conj command)))
            state
            (:commands sub-path))
    state))

(defn path-from-recorder
  "Extract a path from a recorder turtle."
  [recorder]
  (make-path (:recording recorder)))

(defn run-path
  "Execute a path's commands on a turtle state.
   Handles :mark commands by saving the pose as an anchor.
   Returns the updated state."
  [state path]
  (if (path? path)
    (reduce (fn [s {:keys [cmd args]}]
              (case cmd
                :f  (f s (first args))
                :th (th s (first args))
                :tv (tv s (first args))
                :tr (tr s (first args))
                :u  (move-up s (first args))
                :lt (move-left s (first args))
                :rt (move-right s (first args))
                :set-heading (-> s
                                 (assoc :heading (normalize (first args)))
                                 (assoc :up (normalize (second args))))
                :mark (assoc-in s [:anchors (first args)]
                                {:position (:position s)
                                 :heading (:heading s)
                                 :up (:up s)})
                s))
            state
            (:commands path))
    state))

(defn resolve-marks
  "Execute a path virtually from the given state (no geometry generated),
   collecting mark poses. Returns a map of {mark-name pose}."
  [state path]
  (if (path? path)
    (let [;; Run path on a bare turtle (no geometry) starting from given pose
          virtual-turtle (-> (make-turtle)
                             (assoc :position (:position state))
                             (assoc :heading (:heading state))
                             (assoc :up (:up state))
                             (assoc :pen-mode :off)
                             (assoc :anchors {}))
          result (run-path virtual-turtle path)]
      (:anchors result))
    {}))

(defn path-segments
  "Split a path's commands into segments, one per :f command.
   Each segment is a group of rotation commands followed by one :f.
   Returns a vector of {:rotations [...] :distance d}."
  [path]
  (loop [cmds (:commands path)
         current-rotations []
         segments []]
    (if (empty? cmds)
      segments
      (let [{:keys [cmd args] :as c} (first cmds)]
        (if (= :f cmd)
          (recur (rest cmds)
                 []
                 (conj segments {:rotations current-rotations
                                 :distance (first args)}))
          (recur (rest cmds)
                 (conj current-rotations c)
                 segments))))))

(defn subdivide-segment
  "Subdivide a segment into smaller sub-segments if it exceeds max-length.
   Returns a vector of segments. Only the first sub-segment keeps rotations."
  [segment max-length]
  (let [dist (:distance segment)]
    (if (or (<= dist max-length) (<= max-length 0))
      [segment]
      (let [n (int (Math/ceil (/ dist max-length)))
            sub-dist (/ dist n)]
        (into [{:rotations (:rotations segment) :distance sub-dist}]
              (repeat (dec n) {:rotations [] :distance sub-dist}))))))

(defn- segment->state
  "Run a segment's rotations and forward on a turtle state. Returns new state."
  [state segment]
  (let [rotated (reduce (fn [s {:keys [cmd args]}]
                          (case cmd
                            :th (th s (first args))
                            :tv (tv s (first args))
                            :tr (tr s (first args))
                            :set-heading (-> s
                                             (assoc :heading (normalize (first args)))
                                             (assoc :up (normalize (second args))))
                            s))
                        state
                        (:rotations segment))]
    (f rotated (:distance segment))))

(defn- catmull-rom-directions
  "Compute smoothing directions at each waypoint for cubic spline mode.
   At endpoints: turtle heading (unit vector).
   At interior points: normalized (P_{i+1} - P_{i-1}) (Catmull-Rom direction)."
  [waypoints]
  (let [n (count waypoints)]
    (mapv (fn [i]
            (if (or (zero? i) (= i (dec n)))
              ;; Endpoints: use turtle heading
              (:heading (nth waypoints i))
              ;; Interior: Catmull-Rom direction
              (let [prev (:position (nth waypoints (dec i)))
                    nxt  (:position (nth waypoints (inc i)))
                    diff (v- nxt prev)
                    len  (magnitude diff)]
                (if (> len 0.001)
                  (v* diff (/ 1.0 len))
                  (:heading (nth waypoints i))))))
          (range n))))

;; ============================================================
;; Pure bezier-as computation functions (for testing & reuse)
;; ============================================================

(defn compute-path-waypoints
  "Pure function: compute waypoints from path segments and initial pose.
   Returns vector of {:position :heading :up} maps.

   Arguments:
   - segments: vector of path segments (from path-segments)
   - init-pose: {:position :heading :up} initial turtle pose

   This is a pure function suitable for unit testing."
  [segments init-pose]
  (loop [s (merge {:position [0 0 0] :heading [1 0 0] :up [0 0 1]} init-pose)
         segs segments
         wps [(select-keys s [:position :heading :up])]]
    (if (empty? segs)
      wps
      (let [next-s (segment->state s (first segs))]
        (recur next-s
               (rest segs)
               (conj wps (select-keys next-s [:position :heading :up])))))))

;; compute-bezier-control-points and sample-bezier-segment are in bezier module

(defn compute-bezier-walk
  "Pure function: compute complete walk data for bezier-as.

   Arguments:
   - segments: path segments (from path-segments, optionally subdivided)
   - init-pose: {:position :heading :up} initial pose
   - opts: {:tension :cubic :steps :calc-steps-fn}
           calc-steps-fn: (fn [seg-length] -> num-steps) for dynamic resolution

   Returns vector of segment results, each containing:
   {:walk-steps [...] :target-pose {:position :heading :up}}

   This is the main pure function for bezier-as computation."
  [segments init-pose opts]
  (if (empty? segments)
    []
    (let [{:keys [tension cubic calc-steps-fn]
           :or {tension 0.33}} opts
          waypoints (compute-path-waypoints segments init-pose)
          directions (when cubic (catmull-rom-directions waypoints))]
      (loop [i 0
             current-pose init-pose
             results []]
        (if (>= i (count segments))
          results
          (let [wp1 (nth waypoints (inc i))
                p0 (:position current-pose)
                p3 (:position wp1)
                h0 (:heading current-pose)
                h1 (:heading wp1)
                seg-length (magnitude (v- p3 p0))]
            (if (< seg-length 0.001)
              ;; Degenerate segment - just record target pose
              (recur (inc i)
                     wp1
                     (conj results {:walk-steps []
                                    :target-pose wp1
                                    :degenerate true
                                    :segment-index i}))
              ;; Normal segment - compute bezier walk
              (let [steps (if calc-steps-fn
                            (calc-steps-fn seg-length)
                            16)
                    cubic-dirs (when cubic
                                 [(nth directions i) (nth directions (inc i))])
                    [c1 c2] (compute-bezier-control-points
                              p0 h0 p3 h1 tension cubic-dirs)
                    walk-steps (sample-bezier-segment
                                 p0 c1 c2 p3 steps h0 (:up current-pose))
                    ;; Final pose from last walk step, or target if no steps
                    final-pose (if (seq walk-steps)
                                 (let [last-step (peek walk-steps)]
                                   {:position (:to last-step)
                                    :heading (:final-heading last-step)
                                    :up (:final-up last-step)})
                                 wp1)]
                (recur (inc i)
                       final-pose
                       (conj results {:walk-steps walk-steps
                                      :target-pose final-pose
                                      :segment-index i}))))))))))

(defn- apply-walk-step
  "Apply a single walk step to turtle state, drawing a line."
  [state step]
  (let [{:keys [dist chord-heading final-heading final-up]} step]
    (-> state
        (assoc :heading chord-heading)  ; use chord to draw correctly
        (f dist)                        ; draws line from current pos to new pos
        (assoc :heading final-heading)  ; restore tangent heading
        (assoc :up final-up))))

(defn bezier-as
  "Draw a bezier curve that smoothly approximates a turtle path.
   Produces one cubic bezier per segment in the path, with C1 continuity
   at junction points.

   Usage:
   (bezier-as state my-path)                          ; one bezier per segment
   (bezier-as state my-path :tension 0.5)             ; wider curves
   (bezier-as state my-path :max-segment-length 20)   ; subdivide long segments
   (bezier-as state my-path :cubic true)              ; Catmull-Rom spline
   (bezier-as state my-path :steps 32)                ; resolution per bezier

   :tension            - control point distance factor (default 0.33)
   :max-segment-length - subdivide segments longer than this
   :cubic              - use Catmull-Rom tangents for smoother global curves
   :steps              - bezier resolution (default from resolution settings)"
  [state p & args]
  (let [{:keys [tension steps max-segment-length cubic]} (apply hash-map args)
        segments (path-segments p)
        ;; Optionally subdivide long segments
        segments (if max-segment-length
                   (vec (mapcat #(subdivide-segment % max-segment-length) segments))
                   segments)]
    (if (empty? segments)
      state
      ;; Use pure function to compute walk data
      (let [init-pose (select-keys state [:position :heading :up])
            calc-steps-fn (when-not steps
                            #(calc-bezier-steps state %))
            walk-data (compute-bezier-walk
                        segments init-pose
                        {:tension (or tension 0.33)
                         :cubic cubic
                         :calc-steps-fn (or calc-steps-fn (constantly (or steps 16)))})]
        ;; Apply walk steps to state
        (reduce
         (fn [current-state segment-data]
           (if (:degenerate segment-data)
             ;; Degenerate segment: apply rotations via segment->state
             (segment->state current-state (nth segments (:segment-index segment-data)))
             ;; Normal segment: apply walk steps
             (reduce apply-walk-step current-state (:walk-steps segment-data))))
         state
         walk-data)))))

;; ============================================================
;; Path sampling for text-on-path
;; ============================================================

(defn ^:export path-total-length
  "Calculate total arc length of a path by summing positive :f distances."
  [path]
  (if (path? path)
    (->> (:commands path)
         (filter #(= :f (:cmd %)))
         (map #(first (:args %)))
         (filter pos?)
         (reduce + 0))
    0))

(defn ^:export sample-path-at-distance
  "Sample a path at a specific arc-length distance.
   Returns {:position [x y z] :heading [x y z] :up [x y z]} or nil if past end.

   Options:
   - :wrap? - if true, wrap distance around for closed paths (default false)
   - :start-pos - starting position (default [0 0 0])
   - :start-heading - starting heading (default [1 0 0])
   - :start-up - starting up vector (default [0 0 1])"
  [path distance & {:keys [wrap? start-pos start-heading start-up]
                    :or {wrap? false
                         start-pos [0 0 0]
                         start-heading [1 0 0]
                         start-up [0 0 1]}}]
  (when (path? path)
    (let [total (path-total-length path)
          dist (if (and wrap? (> distance total) (pos? total))
                 (mod distance total)
                 distance)]
      (when (and (>= dist 0) (<= dist total))
        (loop [cmds (:commands path)
               pos start-pos
               heading start-heading
               up start-up
               cumulative 0.0]
          (if-let [cmd (first cmds)]
            (case (:cmd cmd)
              :f (let [d (first (:args cmd))]
                   (if (and (pos? d) (>= (+ cumulative d) dist))
                     ;; Target is in this segment - interpolate position
                     (let [remaining (- dist cumulative)
                           final-pos (v+ pos (v* heading remaining))]
                       {:position final-pos :heading heading :up up})
                     ;; Continue to next segment
                     (recur (rest cmds)
                            (if (pos? d)
                              (v+ pos (v* heading d))
                              pos)
                            heading
                            up
                            (if (pos? d) (+ cumulative d) cumulative))))
              :th (let [angle (first (:args cmd))
                        rad (deg->rad angle)
                        new-heading (rotate-around-axis heading up rad)]
                    (recur (rest cmds) pos new-heading up cumulative))
              :tv (let [angle (first (:args cmd))
                        rad (deg->rad angle)
                        right (cross heading up)
                        new-heading (rotate-around-axis heading right rad)
                        new-up (rotate-around-axis up right rad)]
                    (recur (rest cmds) pos new-heading new-up cumulative))
              :tr (let [angle (first (:args cmd))
                        rad (deg->rad angle)
                        new-up (rotate-around-axis up heading rad)]
                    (recur (rest cmds) pos heading new-up cumulative))
              ;; Unknown command - skip
              (recur (rest cmds) pos heading up cumulative))
            ;; End of commands - return final position if we reached the target
            (when (>= cumulative dist)
              {:position pos :heading heading :up up})))))))

;; ============================================================
;; Revolve (lathe operation)
;; ============================================================

(defn ^:export revolve-shape
  "Revolve a 2D profile shape around the turtle's heading axis.
   Creates a solid of revolution (like a lathe operation).

   The profile is interpreted as:
   - 2D X = radial distance from axis (swept around up axis)
   - 2D Y = position along axis (in up direction)

   At θ=0 the stamp matches extrude: shape-X → right, shape-Y → up.
   Revolution axis = turtle's up vector. Use (tv) to change the axis.

   The axis of revolution passes through the turtle's current position.

   Parameters:
   - state: turtle state
   - shape: a 2D shape (profile to revolve)
   - angle: rotation angle in degrees (default 360 for full revolution)

   Uses the global resolution setting for number of segments.

   Examples:
   (revolve (shape (f 8) (th 90) (f 10) (th 90) (f 8)) 360)  ; solid cylinder
   (revolve (circle 5) 360)  ; torus (circle revolved around axis)
   (revolve cup-profile 360) ; cup/vase shape"
  ([state shape]
   (revolve-shape state shape 360))
  ([state shape angle]
   (revolve-shape state shape angle nil))
  ([state shape angle eval-shape-at-t]
   (if-not (shape? shape)
     state
     (let [;; Save creation pose
           creation-pose {:position (:position state)
                          :heading (:heading state)
                          :up (:up state)}
           ;; Get profile points
           ;; When using shape-fn, clamp x >= 0 to prevent crossing revolution axis
           ;; (polygon clipping would change point count, breaking face generation)
           clamp-x (fn [pts] (mapv (fn [[x y]] [(max 0 x) y]) pts))
           profile-points (if eval-shape-at-t
                            (clamp-x (:points shape))
                            (:points shape))
           n-profile (count profile-points)
           ;; Calculate shape winding using signed area
           ;; Positive = CCW, Negative = CW
           shape-signed-area (let [pts profile-points
                                   n (count pts)]
                               (/ (reduce + (for [i (range n)]
                                              (let [[x1 y1] (nth pts i)
                                                    [x2 y2] (nth pts (mod (inc i) n))]
                                                (- (* x1 y2) (* x2 y1)))))
                                  2))
           ;; Determine if we need to flip face winding
           ;; Flip when: (CCW shape AND positive angle) OR (CW shape AND negative angle)
           shape-is-ccw? (pos? shape-signed-area)
           flip-winding? (if shape-is-ccw? (pos? angle) (neg? angle))
           ;; Calculate number of segments based on resolution
           ;; Use same logic as arc: resolution based on angle
           steps (calc-arc-steps state (* 2 Math/PI) (Math/abs angle))
           ;; For full 360, we don't need the last ring (it overlaps with first)
           is-closed (>= (Math/abs angle) 360)
           n-rings (if is-closed steps (inc steps))
           angle-rad (* angle (/ Math/PI 180))
           angle-step (/ angle-rad steps)
           ;; Get turtle orientation
           pos (:position state)
           heading (:heading state)
           up (:up state)
           ;; Right vector = heading × up (initial radial direction at θ=0)
           right (normalize (cross heading up))
           ;; Transform profile point [px py] at angle θ to 3D:
           ;; pos + py * up + px * (cos(θ) * right + sin(θ) * heading)
           ;; At θ=0 this matches extrude's stamp: px*right + py*up
           ;; Revolution axis = up; radial sweeps from right toward heading
           ;; shape-X = radial distance (swept around up axis)
           ;; shape-Y = axial position (along up / revolution axis)
           transform-point (fn [[px py] theta]
                             (let [cos-t (Math/cos theta)
                                   sin-t (Math/sin theta)
                                   ;; Radial direction at this angle (sweeps in right-heading plane)
                                   radial-x (+ (* cos-t (nth right 0)) (* sin-t (nth heading 0)))
                                   radial-y (+ (* cos-t (nth right 1)) (* sin-t (nth heading 1)))
                                   radial-z (+ (* cos-t (nth right 2)) (* sin-t (nth heading 2)))]
                               [(+ (nth pos 0) (* py (nth up 0)) (* px radial-x))
                                (+ (nth pos 1) (* py (nth up 1)) (* px radial-y))
                                (+ (nth pos 2) (* py (nth up 2)) (* px radial-z))]))
           ;; Hole support
           has-holes? (boolean (:holes shape))
           hole-profiles (when has-holes?
                           (if eval-shape-at-t
                             (mapv clamp-x (:holes shape))
                             (:holes shape)))
           hole-lengths (when has-holes? (mapv count hole-profiles))
           ;; Combined ring length: outer + all holes
           ring-len (if has-holes?
                      (+ n-profile (reduce + 0 hole-lengths))
                      n-profile)
           ;; Transform all points in a contour at a given angle
           transform-contour (fn [pts theta]
                               (vec (for [pt pts] (transform-point pt theta))))
           ;; Generate all rings
           ;; When eval-shape-at-t is provided, evaluate it at each step
           ;; to get varying profiles (for shape-fn support)
           ;; Each ring = outer-pts ++ hole0-pts ++ hole1-pts ...
           rings (vec (for [i (range n-rings)]
                        (let [theta (* i angle-step)
                              t (/ (double i) steps)
                              current-shape (when eval-shape-at-t (eval-shape-at-t t))
                              ring-outer (if current-shape
                                           (clamp-x (:points current-shape))
                                           profile-points)
                              ring-holes (when has-holes?
                                           (if current-shape
                                             (mapv clamp-x (or (:holes current-shape) []))
                                             hole-profiles))]
                          (vec (concat
                                (transform-contour ring-outer theta)
                                (when ring-holes
                                  (mapcat #(transform-contour % theta) ring-holes)))))))
           ;; Flatten vertices
           vertices (vec (apply concat rings))
           ;; Side faces connecting consecutive rings (outer contour)
           ;; For closed revolve, connect last ring to first
           outer-side-faces
           (vec
            (mapcat
             (fn [ring-idx]
               (let [next-ring-idx (if (and is-closed (= ring-idx (dec n-rings)))
                                     0
                                     (inc ring-idx))]
                 (when (< next-ring-idx n-rings)
                   (mapcat
                    (fn [vert-idx]
                      (let [next-vert (mod (inc vert-idx) n-profile)
                            base (* ring-idx ring-len)
                            next-base (* next-ring-idx ring-len)
                            b0 (+ base vert-idx)
                            b1 (+ base next-vert)
                            t0 (+ next-base vert-idx)
                            t1 (+ next-base next-vert)]
                        ;; CCW winding for outward-facing normals
                        ;; Flip based on shape winding and angle sign
                        (if flip-winding?
                          [[b0 t0 t1] [b0 t1 b1]]
                          [[b0 t1 t0] [b0 b1 t1]])))
                    (range n-profile)))))
             (range (if is-closed n-rings (dec n-rings)))))
           ;; Side faces for each hole — same flip as outer.
           ;; Hole contours are CW (opposite to outer CCW), so with the same
           ;; face-winding logic the normals naturally point inward (into the
           ;; tube cavity), matching how build-sweep-mesh-with-holes works.
           hole-side-faces
           (when has-holes?
             (vec (apply concat
                         (map-indexed
                          (fn [hole-idx hole-len]
                            (let [hole-offset (+ n-profile (reduce + 0 (take hole-idx hole-lengths)))]
                              (mapcat
                               (fn [ring-idx]
                                 (let [next-ring-idx (if (and is-closed (= ring-idx (dec n-rings)))
                                                       0
                                                       (inc ring-idx))]
                                   (when (< next-ring-idx n-rings)
                                     (mapcat
                                      (fn [vi]
                                        (let [next-vi (mod (inc vi) hole-len)
                                              base (+ (* ring-idx ring-len) hole-offset)
                                              next-base (+ (* next-ring-idx ring-len) hole-offset)
                                              b0 (+ base vi) b1 (+ base next-vi)
                                              t0 (+ next-base vi) t1 (+ next-base next-vi)]
                                          ;; Same flip as outer — CW hole winding
                                          ;; naturally produces inward normals
                                          (if flip-winding?
                                            [[b0 t0 t1] [b0 t1 b1]]
                                            [[b0 t1 t0] [b0 b1 t1]])))
                                      (range hole-len)))))
                               (range (if is-closed n-rings (dec n-rings))))))
                          hole-lengths))))
           side-faces (if hole-side-faces
                        (vec (concat outer-side-faces hole-side-faces))
                        outer-side-faces)
           ;; Caps for open revolve (angle < 360)
           cap-faces
           (when-not is-closed
             (let [first-combined-ring (first rings)
                   last-combined-ring (last rings)
                   last-ring-base (* (dec n-rings) ring-len)
                   ;; For triangulation, we need the normal to the ring PLANE.
                   ;; Ring at angle θ lies in plane spanned by {up, radial(θ)},
                   ;; where radial(θ) = cos(θ)*right + sin(θ)*heading.
                   ;; Plane normal = cross(up, radial(θ)) = cos(θ)*heading - sin(θ)*right
                   start-proj-normal heading  ;; θ=0: cos(0)*heading - sin(0)*right = heading
                   end-theta (* steps angle-step)
                   end-proj-normal (let [cos-t (Math/cos end-theta)
                                         sin-t (Math/sin end-theta)]
                                     (v- (v* heading cos-t) (v* right sin-t)))
                   ;; Cap flip: start cap faces backward (opposite revolution),
                   ;; end cap faces forward (along revolution direction)
                   start-cap-flip flip-winding?
                   end-cap-flip (not flip-winding?)]
               (if has-holes?
                 ;; Extract outer and hole sub-rings from combined ring
                 (let [split-ring (fn [combined-ring]
                                    (let [outer (subvec combined-ring 0 n-profile)
                                          holes (loop [idx 0 offset n-profile acc []]
                                                  (if (>= idx (count hole-lengths))
                                                    acc
                                                    (let [hl (nth hole-lengths idx)]
                                                      (recur (inc idx)
                                                             (+ offset hl)
                                                             (conj acc (subvec combined-ring offset (+ offset hl)))))))]
                                      [outer holes]))
                       [first-outer first-holes] (split-ring first-combined-ring)
                       [last-outer last-holes] (split-ring last-combined-ring)
                       start-cap (triangulate-cap-with-holes first-outer first-holes
                                                              0 start-proj-normal start-cap-flip)
                       end-cap (triangulate-cap-with-holes last-outer last-holes
                                                           last-ring-base end-proj-normal end-cap-flip)]
                   (vec (concat start-cap end-cap)))
                 ;; No holes — simple caps
                 (let [start-cap (triangulate-cap first-combined-ring 0 start-proj-normal start-cap-flip)
                       end-cap (triangulate-cap last-combined-ring last-ring-base end-proj-normal end-cap-flip)]
                   (vec (concat start-cap end-cap))))))
           all-faces (if cap-faces
                       (vec (concat side-faces cap-faces))
                       side-faces)
           mesh (schema/assert-mesh!
                    (cond-> {:type :mesh
                             :primitive :revolve
                             :vertices vertices
                             :faces all-faces
                             :creation-pose creation-pose}
                      (:material state) (assoc :material (:material state))))]
       (update state :meshes conj mesh)))))

;; ============================================================
;; Anchors and Navigation
;; ============================================================

;; mark removed — marks now exist only inside path recordings.
;; Use (mark :name) inside (path ...) to embed a mark,
;; then (with-path p ...) to resolve marks as anchors.

(defn goto
  "Move to a named anchor position and adopt its heading/up.
   Draws a line if pen-mode is :on.
   Returns state unchanged if anchor doesn't exist."
  [state name]
  (if-let [anchor (get-in state [:anchors name])]
    (let [from-pos (:position state)
          to-pos (:position anchor)
          mode (:pen-mode state)
          ;; Draw line if pen is on
          state' (if (= mode :on)
                   (update state :geometry conj {:type :line
                                                 :from from-pos
                                                 :to to-pos
                                                 :color (get-in state [:material :color])})
                   state)]
      (-> state'
          (assoc :position to-pos)
          (assoc :heading (:heading anchor))
          (assoc :up (:up anchor))))
    state))

(defn look-at
  "Rotate heading to point toward a named anchor.
   Does not move the turtle.
   Adjusts up to maintain orthogonality.
   Returns state unchanged if anchor doesn't exist or if at same position."
  [state name]
  (if-let [anchor (get-in state [:anchors name])]
    (let [from-pos (:position state)
          to-pos (:position anchor)
          dir (v- to-pos from-pos)
          dist (magnitude dir)]
      (if (< dist 0.0001)
        ;; Already at target position, can't determine direction
        state
        (let [new-heading (normalize dir)
              ;; Compute new up: try to keep it as close to original up as possible
              ;; but orthogonal to new heading
              old-up (:up state)
              ;; Project old-up onto plane perpendicular to new-heading
              ;; up' = up - (up · heading) * heading
              dot-prod (dot old-up new-heading)
              projected-up (v- old-up (v* new-heading dot-prod))
              proj-mag (magnitude projected-up)
              new-up (if (< proj-mag 0.0001)
                       ;; old-up is parallel to new-heading, pick arbitrary perpendicular
                       (let [arbitrary (if (< (Math/abs (first new-heading)) 0.9)
                                         [1 0 0]
                                         [0 1 0])]
                         (normalize (cross new-heading arbitrary)))
                       (normalize projected-up))]
          (-> state
              (assoc :heading new-heading)
              (assoc :up new-up)))))
    state))

(defn path-to
  "Create a path from current position to a named anchor.
   Returns a path with a single (f distance) command.

   Note: In implicit mode (REPL), this also orients the turtle toward
   the anchor first (via look-at), so extrusions go in the correct direction.

   Useful for: (extrude (circle 5) (path-to :target))
   Returns nil if anchor doesn't exist."
  [state name]
  (when-let [anchor (get-in state [:anchors name])]
    (let [from-pos (:position state)
          to-pos (:position anchor)
          dir (v- to-pos from-pos)
          dist (magnitude dir)]
      (make-path [{:cmd :f :args [dist]}]))))

;; ============================================================
;; Attachment system - attach to meshes and faces
;; ============================================================

(defn attach
  "Attach to a mesh's creation pose.
   With :clone true, creates a copy of the mesh first (original unchanged).
   Pushes current state, moves turtle to the mesh's creation position,
   adopts its heading and up vectors.
   Returns state unchanged if mesh has no creation-pose."
  [state mesh & {:keys [clone]}]
  (let [target-mesh (if clone (clone-mesh mesh) mesh)
        state (if clone
                (update state :meshes conj target-mesh)
                state)]
    (if-let [pose (:creation-pose target-mesh)]
      (-> state
          (push-state)
          (assoc :position (:position pose))
          (assoc :heading (:heading pose))
          (assoc :up (:up pose))
          (assoc :attached {:type :pose
                            :mesh target-mesh
                            :original-pose pose}))
      state)))

(defn- resolve-face-triangles
  "Resolve triangles for a face-id, which can be:
   - keyword or number: single face-group lookup
   - vector of numbers: collect triangles from multiple face-groups"
  [face-groups face-id]
  (cond
    (vector? face-id)
    (let [tris (mapcat #(get face-groups %) face-id)]
      (when (seq tris) (vec tris)))

    :else
    (get face-groups face-id)))

(defn attach-face
  "Attach to a specific face of a mesh.
   face-id can be a keyword (:top), a number (face index), or a vector of
   numbers (multiple face indices, e.g. from flood-fill picking).
   With :clone true, enables extrusion mode (f creates side faces).
   Without :clone, face movement mode (f moves vertices directly).
   Pushes current state, moves turtle to face center,
   sets heading to face normal (outward), up perpendicular.
   Returns state unchanged if face not found."
  [state mesh face-id & {:keys [clone]}]
  (if-let [face-groups (:face-groups mesh)]
    (if-let [triangles (resolve-face-triangles face-groups face-id)]
      (let [info (compute-face-info-internal (:vertices mesh) triangles)
            normal (:normal info)
            center (:center info)
            ;; Derive up vector perpendicular to normal
            face-heading (:heading info)
            ;; up = normal × face-heading (perpendicular to both)
            up (normalize (cross normal face-heading))]
        (-> state
            (push-state)
            (assoc :position center)
            (assoc :heading normal)
            (assoc :up up)
            (assoc :attached {:type :face
                              :mesh mesh
                              :face-id face-id
                              :face-info info
                              :extrude-mode clone})))  ; flag: if true, f() extrudes
      state)
    state))

(defn ^:export attach-face-extrude
  "Attach to a face with extrusion mode enabled.
   Immediately creates cloned vertices at distance 0 (coincident with original).
   This way scale/inset can operate on the new vertices, and f moves them.
   After cloning, extrude-mode is turned off so f just moves vertices.
   Sets clone-context so inset knows to create new vertices and re-enable extrude.
   SCI-compatible (no keyword args)."
  [state mesh face-id]
  (-> state
      (attach-face mesh face-id :clone true)
      ;; Immediately extrude 0 to create cloned vertices and side faces
      ;; The cloned vertices start coincident with the original face
      (extrude-attached-face 0)
      ;; Turn off extrude-mode: vertices are now cloned, future f just moves them
      (assoc-in [:attached :extrude-mode] false)
      ;; Mark clone context so inset knows to create new vertices
      (assoc-in [:attached :clone-context] true)))

(defn ^:export attach-move
  "Attach to a mesh's creation pose (modifies original mesh).
   SCI-compatible wrapper for (attach state mesh) without :clone."
  [state mesh]
  (attach state mesh))

(defn detach
  "Detach from current attachment and restore previous position.
   Equivalent to pop-state but also clears :attached.
   Returns state unchanged if not attached."
  [state]
  (if (:attached state)
    (-> state
        (pop-state)
        (assoc :attached nil))
    state))

;; ============================================================
;; Transform: functional mesh/group transformation via path
;; ============================================================

;; Re-export for SCI macro access
(def group-transform attachment/group-transform)

(defn ^:export transform-mesh
  "Apply a path's transformations to a mesh or vector of meshes.
   Single mesh: attaches and runs path commands (from creation-pose).
   Vector of meshes: group-style rigid body transformation.
   Returns transformed mesh or vector of meshes."
  [mesh-or-meshes path]
  (if (sequential? mesh-or-meshes)
    ;; Vector of meshes: group-style rigid body transform
    (let [first-mesh (first mesh-or-meshes)
          ref-pose (or (:creation-pose first-mesh)
                       {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
          p0 (:position ref-pose)
          h0 (normalize (:heading ref-pose))
          u0 (normalize (:up ref-pose))
          ;; Run path on virtual turtle at reference pose
          state (-> (make-turtle)
                    (assoc :position p0)
                    (assoc :heading h0)
                    (assoc :up u0))
          state (run-path state path)
          p1 (:position state)
          h1 (normalize (:heading state))
          u1 (normalize (:up state))]
      (attachment/group-transform mesh-or-meshes p0 h0 u0 p1 h1 u1))
    ;; Single mesh: attach and run path
    (let [state (-> (make-turtle) (attach mesh-or-meshes))
          state (run-path state path)]
      (or (get-in state [:attached :mesh]) mesh-or-meshes))))
