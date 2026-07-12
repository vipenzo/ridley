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
            [ridley.turtle.bezier :as bezier]
            [ridley.turtle.shape :as shape]))

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
   :anchors {}              ; named poses for mark/goto
   :attached nil            ; attachment state for face/mesh operations
   :resolution (extrusion/default-resolution)  ; curve resolution (like OpenSCAD $fn)
   :joint-mode :tapered            ; corner style for direction changes (:flat | :tapered | :round)
   :material {:color 0x00aaff        ; hex color
              :metalness 0.3         ; 0-1, PBR metalness
              :roughness 0.7         ; 0-1, PBR roughness
              :opacity 1.0           ; 0-1, transparency
              :flat-shading true}    ; flat vs smooth shading
   :preserve-up false                ; when true, th/tv keep up aligned with reference-up
   :reference-up nil})               ; captured up vector when :preserve-up scope is entered

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
(def ^:export canonical-bezier-frame bezier/canonical-bezier-frame)

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

(defn- orthogonalize
  "Project reference-up onto the plane perpendicular to heading, normalize.
   Returns a unit vector aligned as closely as possible with reference-up
   while being perpendicular to heading."
  [reference-up heading]
  (let [d (dot reference-up heading)
        proj (v* heading d)
        result (v- reference-up proj)
        mag (magnitude result)]
    (if (< mag 1e-10)
      ;; Degenerate: heading is parallel to reference-up, fall back
      reference-up
      (normalize result))))

(defn- right-vector
  "Calculate the right vector (heading x up)."
  [state]
  (cross (:heading state) (:up state)))

(defn- apply-rotations
  "Apply a list of rotations to heading/up vectors.
   Returns {:heading new-heading :up new-up}"
  [state rotations]
  (let [preserve? (:preserve-up state)
        ref-up (:reference-up state)]
    (reduce
     (fn [{:keys [heading up] :as acc} {:keys [type angle]}]
       (let [rad (deg->rad angle)]
         (case type
           :th (if preserve?
                 (let [new-heading (normalize (rotate-around-axis heading ref-up rad))
                       new-up (orthogonalize ref-up new-heading)]
                   (assoc acc :heading new-heading :up new-up))
                 (assoc acc :heading (rotate-around-axis heading up rad)))
           :tv (let [right (normalize (cross heading up))
                     new-heading (normalize (rotate-around-axis heading right rad))]
                 (if preserve?
                   (let [new-up (orthogonalize ref-up new-heading)]
                     (assoc acc :heading new-heading :up new-up))
                   (let [new-up (rotate-around-axis up right rad)]
                     (assoc acc :heading new-heading :up new-up))))
           :tr (assoc acc :up (rotate-around-axis up heading rad))
           acc)))
     {:heading (:heading state) :up (:up state)}
     rotations)))

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

(def ^:private get-resolution extrusion/get-resolution)

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
   - :on - add line segment to geometry (flushed to scene accumulator by implicit layer)
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
    (if (:preserve-up state)
      ;; Preserve-up: rotate heading around reference-up, re-orthogonalize up
      (let [rad (deg->rad angle)
            ref-up (:reference-up state)
            new-heading (normalize (rotate-around-axis (:heading state) ref-up rad))
            new-up (orthogonalize ref-up new-heading)]
        (assoc state :heading new-heading :up new-up))
      ;; Standard: rotate heading around local up
      (let [rad (deg->rad angle)
            new-heading (rotate-around-axis (:heading state) (:up state) rad)]
        (assoc state :heading new-heading)))))

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
          new-heading (normalize (rotate-around-axis (:heading state) right rad))]
      (if (:preserve-up state)
        ;; Preserve-up: re-orthogonalize up toward reference-up
        (let [new-up (orthogonalize (:reference-up state) new-heading)]
          (assoc state :heading new-heading :up new-up))
        ;; Standard: up rotates with heading
        (let [new-up (rotate-around-axis (:up state) right rad)]
          (assoc state :heading new-heading :up new-up))))))

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
  (when (neg? radius)
    (throw (js/Error. (str "arc-h: radius must be non-negative (got " radius
                           "). To reverse the turn direction use a negative angle, e.g. (arc-h " (- radius) " -" (Math/abs angle) ")."))))
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
  (when (neg? radius)
    (throw (js/Error. (str "arc-v: radius must be non-negative (got " radius
                           "). To reverse the pitch direction use a negative angle, e.g. (arc-v " (- radius) " -" (Math/abs angle) ")."))))
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
  "Walk along a cubic bezier curve p0→c1→c2→p3, moving directly to each sample
   point. Uses chord directions for drawing (position accuracy), then sets
   the tangent heading for continuity. First step preserves the existing
   heading, last step uses exact end tangent.
   Up is read off the canonical bezier frame (bezier/canonical-bezier-frame)
   — a property of the curve's control points and entry up alone, so it does
   not depend on `steps`."
  [state steps p0 c1 c2 p3]
  (let [last-i (dec steps)
        ts (mapv #(/ (inc %) steps) (range steps))
        frames (canonical-bezier-frame p0 c1 c2 p3 (:up state) ts)
        end-heading (:heading (peek frames))]
    (reduce
     (fn [s i]
       (let [t (nth ts i)
             new-pos (cubic-bezier-point p0 c1 c2 p3 t)
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
                 new-up (:up (nth frames i))]
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

(defn local->world
  "Convert a vector expressed in the turtle's local [right up heading] frame
   (origin at the turtle position) to world coordinates:
   world = p0 + a·right + b·up + c·heading. The basis is orthonormal, so this is
   exactly inverted by projecting onto the same axes — see edit-bezier."
  [state [a b c]]
  (let [p0 (:position state)
        right (normalize (cross (:heading state) (:up state)))]
    (v+ p0
        (v+ (v* right a)
            (v+ (v* (:up state) b)
                (v* (:heading state) c))))))

(defn bezier-to
  "Draw a bezier curve to target position.

   Usage:
   (bezier-to state target)                    ; auto control points
   (bezier-to state target [cx cy cz])         ; quadratic with 1 control point
   (bezier-to state target [c1...] [c2...])    ; cubic with 2 control points
   (bezier-to state target :steps 24)          ; auto with more steps
   (bezier-to state target [c1] [c2] :steps 24); explicit with more steps
   (bezier-to state target [c1] [c2] :local)   ; vectors in turtle-local frame
   (bezier-to state target :preserve-heading)  ; arrive tangent to current heading
   (bezier-to state target :preserve-heading :tension 0.5) ; wider belly

   With 0 control points: generates smooth curve starting along current heading
   With 1 control point: quadratic bezier
   With 2 control points: cubic bezier

   :preserve-heading (only with 0 control points): the curve arrives tangent to
   the current heading instead of along the start→end chord, so the turtle's
   heading is unchanged and a following (f …) welds without a cusp. :tension
   (default 0.33) controls how far the control points extend (curve width).

   Coordinate frame: by default target and control points are world coordinates.
   With the :local flag, they are read in the turtle's local [right up heading]
   frame (origin = current turtle position), making the call pose-independent.
   This is what edit-bezier emits."
  [state target & args]
  (let [;; Separate vector args (control points) from keyword args
        {control-points true options false} (group-by vector? args)
        ;; :local is a bare flag (no value) — pull it out before hash-map parsing,
        ;; which requires even-count key/value pairs.
        local? (boolean (some #{:local} options))
        ;; :preserve-heading is a bare flag too: make the curve arrive tangent to
        ;; the current heading (turtle heading unchanged), not along the chord.
        preserve-heading? (boolean (some #{:preserve-heading} options))
        options (remove #{:local :preserve-heading} options)
        {:keys [steps tension]} (apply hash-map (flatten options))
        ;; In :local mode, target and control points are given in the turtle's
        ;; local frame — map them to world before the world-space bezier math.
        target (if local? (local->world state target) target)
        control-points (if local? (mapv #(local->world state %) control-points) control-points)
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
          (bezier-walk state actual-steps p0 c1 c2 p3))

        ;; 1 control point: quadratic bezier — degree-elevate to cubic so the
        ;; canonical-frame walk (which only knows cubics) can be reused.
        (= n-controls 1)
        (let [qc (first control-points)
              c1 (v+ p0 (v* (v- qc p0) (/ 2.0 3.0)))
              c2 (v+ p3 (v* (v- qc p3) (/ 2.0 3.0)))]
          (bezier-walk state actual-steps p0 c1 c2 p3))

        ;; 0 control points: auto-generate cubic
        :else
        (let [[c1 c2] (if preserve-heading?
                        ;; Arrive tangent to the current heading: target heading
                        ;; = start heading, so the turtle heading is unchanged.
                        (auto-control-points-with-target-heading
                         p0 (:heading state) p3 (:heading state) (or tension 0.33))
                        (auto-control-points p0 (:heading state) p3))]
          (bezier-walk state actual-steps p0 c1 c2 p3))))))

;; Defined later in this namespace; forward-declared for the path-first
;; bezier-to-anchor form below.
(declare path? resolve-marks)

(defn bezier-to-anchor
  "Draw a bezier curve to a named anchor position.
   When auto-generating control points (no explicit [c1] [c2] provided),
   the curve respects both the current heading AND the anchor's saved heading,
   creating a smooth connection that honors both directions.

   Usage:
   (bezier-to-anchor state :name)              ; auto control points (respects both headings)
   (bezier-to-anchor state :name [c1] [c2])    ; explicit control points
   (bezier-to-anchor state :name :steps 24)    ; auto with more steps
   (bezier-to-anchor state :name :tension 0.5) ; control curve width (0=tight, 1=wide)
   (bezier-to-anchor state path :at :name)     ; resolve :name from a path inline"
  [state target & args]
  (let [;; Path-first form: (bezier-to-anchor path [:at] :name & opts) resolves
        ;; the mark from the path at the current pose, no with-path needed.
        path-first? (path? target)
        [anchor-name args] (if path-first?
                             (let [[a & more] args]
                               (if (= :at a) [(first more) (rest more)] [a more]))
                             [target args])
        anchor (if path-first?
                 (get (resolve-marks state target) anchor-name)
                 (get-in state [:anchors anchor-name]))]
    (if anchor
      (let [{control-points true options false} (group-by vector? args)
            {:keys [steps tension tension-end]} (apply hash-map (flatten options))
            n-controls (count control-points)]
        (if (zero? n-controls)
        ;; Auto-generate control points using BOTH headings
          (let [p0 (:position state)
                p3 (:position anchor)
                approx-length (magnitude (v- p3 p0))
                actual-steps (or steps (calc-bezier-steps state approx-length))
              ;; tension-end defaults to tension → symmetric handles; give it a
              ;; distinct value for asymmetric control-point distances.
                [c1 c2] (auto-control-points-with-target-heading
                         p0 (:heading state) p3 (:heading anchor)
                         (or tension 0.33) (or tension-end tension 0.33))]
            (if (< approx-length 0.001)
              state
              (bezier-walk state actual-steps p0 c1 c2 p3)))
        ;; Explicit control points - delegate to bezier-to
          (apply bezier-to state (:position anchor) args)))
      state)))

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
      color (assoc :color color)
      ;; Carry a reference image (set via set-image) into the stamp. Store the
      ;; per-vertex 2D coords (same order as :vertices) so the viewport can UV-map
      ;; the image onto the stamped polygon — clipping it to the actual outline.
      (:image shape) (assoc :image (assoc (:image shape)
                                          :verts-2d (into (vec outer-2d)
                                                          (apply concat holes-2d)))))))

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

(declare lower-commands)

(defn ^:export path?
  "Check if x is a path."
  [x]
  (and (map? x) (= :path (:type x))))

;; The pose every path's :commands are relative to — the recorder's own
;; local turtle always starts here, so lowering a curve command's local
;; c1/c2/end (or bezier-as's local chord/tangent directions) must decode
;; against the SAME identity frame, regardless of what pose a consumer
;; later replays the path from.
(def ^:private path-entry-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})

(defn with-micro-commands
  "Attach a memoized lowering of `commands` (a path map's :commands, or the
   vector before it's wrapped in one) to `path-map` under :micro-commands —
   the ONE place lower-commands actually runs for a given path. Every path
   constructor calls this; any site that rewrites a path's :commands after
   construction must call it again (the old delay would otherwise answer
   for stale commands) — see path-micro-commands below."
  [path-map]
  (assoc path-map :micro-commands
         (delay (lower-commands (:commands path-map) path-entry-pose))))

(defn ^:export path-micro-commands
  "The tessellated micro-command vector for `path` — the single accessor
   that replaces direct (:commands path) reads across every consumer.
   Lowering (curve commands → micro-commands, tags included) is computed
   once per path via the :micro-commands delay `with-micro-commands`
   stashes at construction; a path built without one (should not happen
   once every constructor uses with-micro-commands) falls back to lowering
   on demand, uncached."
  [path]
  (:commands
   (if-let [d (:micro-commands path)]
     @d
     (lower-commands (:commands path) path-entry-pose))))

(defn make-path
  "Create a path from a vector of recorded commands. Each command is
   {:cmd :f/:b/:th/:tv/:tr/:mark/… :args [...]}.

   Resolved anchors (mark name → pose) are baked into the returned map
   as top-level keys, so callers can write `(:mark-name path)` to get
   the pose directly. Structural keys (:type, :commands, :bezier) win
   in case of a name collision."
  [commands]
  (let [base (with-micro-commands {:type :path :commands (vec commands)})
        anchors (resolve-marks path-entry-pose base)]
    (merge anchors base)))

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
     (with-micro-commands
       {:type :path
        :commands
        (->> nums
             (partition-all 2)
             (mapcat (fn [[a b]]
                       (if (some? b)
                         [{:cmd :f :args [a]}
                          {:cmd :th :args [b]}]
                         [{:cmd :f :args [a]}])))
             vec)}))))

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

(defn apply-set-heading
  "Apply a set-heading command's args [heading up flag] to a turtle state. With a
   trailing :local flag the vectors are in the CURRENT [right up heading] frame (so
   the path composes with the consumption pose — rotates with it); otherwise they
   are absolute. right = heading × up."
  [s [heading up flag]]
  (if (= :local flag)
    (let [h (:heading s) u (:up s) r (normalize (cross h u))
          l->w (fn [[lx ly lz]] (v+ (v* r lx) (v+ (v* u ly) (v* h lz))))]
      (-> s (assoc :heading (normalize (l->w heading))) (assoc :up (normalize (l->w up)))))
    (-> s (assoc :heading (normalize heading)) (assoc :up (normalize up)))))

(defn rec-set-heading
  "Record a set-heading command. A trailing `:local` flag means heading/up are in the
   current frame (composes with the pose); otherwise absolute. See apply-set-heading."
  [state heading up & [flag]]
  (let [args (if flag [heading up flag] [heading up])]
    (record-cmd state :set-heading args #(apply-set-heading % args))))

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
  "Record a move-to command. Record-only — target resolution happens at replay.
   Extra args are kept verbatim so modes like :center or :at <anchor> reach the dispatcher."
  [state target & args]
  (update state :recording conj {:cmd :move-to :args (into [target] args)}))

;; cp-* commands: shift creation-pose without moving geometry
;; Record-only — applied during replay in attach!-impl
;; In user syntax: (@f 5) inside attach!, stored as :cp-f in path
(defn rec-cp-f
  "Record a creation-pose shift along heading. Record-only."
  [state dist]
  (update state :recording conj {:cmd :cp-f :args [dist]}))

(defn rec-cp-rt
  "Record a creation-pose shift along right. Record-only."
  [state dist]
  (update state :recording conj {:cmd :cp-rt :args [dist]}))

(defn rec-cp-u
  "Record a creation-pose shift along up. Record-only."
  [state dist]
  (update state :recording conj {:cmd :cp-u :args [dist]}))

(defn rec-cp-th
  "Record a creation-pose-preserving rotation around up. Record-only."
  [state angle-deg]
  (update state :recording conj {:cmd :cp-th :args [angle-deg]}))

(defn rec-cp-tv
  "Record a creation-pose-preserving rotation around right (= heading × up).
   Record-only."
  [state angle-deg]
  (update state :recording conj {:cmd :cp-tv :args [angle-deg]}))

(defn rec-cp-tr
  "Record a creation-pose-preserving rotation around heading (roll).
   Record-only."
  [state angle-deg]
  (update state :recording conj {:cmd :cp-tr :args [angle-deg]}))

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
            (path-micro-commands sub-path))
    state))

(defn path-from-recorder
  "Extract a path from a recorder turtle."
  [recorder]
  (make-path (:recording recorder)))

;; ============================================================
;; Frame-relative encode/decode — pose-free curve control points
;; ============================================================
;; A high-level curve command's control points/endpoint (and, for
;; bezier-as, its fitted chord/tangent directions) are stored in the LOCAL
;; [right up heading] frame of the segment's entry pose — like the
;; micro-commands they replace: relative, so replay composes correctly
;; from any consumption pose (dev-docs/brief-recording-highlevel-fase1.md,
;; residual point 2 — world coords, like the now-removed :pure rider used to
;; be, would only be valid replayed from the exact recording pose).
;;
;; POINTS (bezier-to/bezier-as's c1/c2/end) reuse the existing local->world
;; (773, above — already used by the runtime bezier-to's own :local flag);
;; world->local is its inverse (both include `state`'s position). DIRECTIONS
;; (bezier-as's chord-heading/final-heading/final-up, unit vectors with no
;; position component) use the *-dir variants instead — adding position to
;; a direction would be wrong.

(defn ^:export world->local
  "Inverse of local->world (773): world-frame ABSOLUTE point → local
   [right up heading] triple relative to `state`'s position/frame."
  [state w]
  (let [heading (:heading state) up (:up state)
        right (normalize (cross heading up))
        v (v- w (:position state))]
    [(dot v right) (dot v up) (dot v heading)]))

(defn- local-dir->world
  "Like local->world (773) but for a DIRECTION (no position offset)."
  [state [a b c]]
  (let [heading (:heading state) up (:up state)
        right (normalize (cross heading up))]
    (v+ (v* right a) (v+ (v* up b) (v* heading c)))))

(defn ^:export world-dir->local
  "Inverse of local-dir->world: world-frame direction → local
   [right up heading] triple, using `state`'s current frame."
  [state v]
  (let [heading (:heading state) up (:up state)
        right (normalize (cross heading up))]
    [(dot v right) (dot v up) (dot v heading)]))

;; ============================================================
;; lower-commands — pure lowering of high-level curve commands
;; ============================================================
;; dev-docs/brief-recording-highlevel-fase1.md (Fase 1): a path's recording
;; now carries curves at the level the user wrote them — one
;; {:cmd :bezier-to :c1 :c2 :end :steps}, {:cmd :arc-h/:arc-v :args [...]
;; :steps}, or {:cmd :bezier-as ...} per curve, with :steps already
;; resolved at record time (never recomputed here — that is what makes
;; replay byte-identical when resolution changes between def and
;; consumption) and any coordinates in the LOCAL frame of the entry pose
;; (local->world above). lower-commands expands these into the exact
;; tessellated micro-command vector (tags included: :smooth, :arc-cap,
;; :bez-cap — the lowering->extrusion protocol; :veer-deg and the :pure/:span
;; rider were reconstruction aids for readers that now interpret the
;; high-level commands directly and were dropped in Fase 3, dev-docs/
;; brief-recording-highlevel-fase3.md) the recorder used to emit directly —
;; ported verbatim from ridley.editor.macros'
;; rec-bezier-to*/rec-arc-h*/rec-arc-v*/rec-bezier-as*, not reinvented.
;; Atomic commands pass through unchanged, threading state the same way
;; record-cmd's execute-fn already does for each. Returns
;; {:commands [...] :state final-state}. Throws on an unrecognized :cmd —
;; this is the pure engine; an unknown command here is a real bug, never
;; something to skip silently.

(defn- lower-rot-if
  "Emit {:cmd cmd :args [angle]} (merged with tags) and apply rotate-fn to
   state — but only if |angle| exceeds the 0.001° noise floor the recorder
   already used to skip a rotation too small to matter."
  [acc cmd rotate-fn angle tags]
  (if (> (abs angle) 0.001)
    (-> acc
        (update :commands conj (merge {:cmd cmd :args [angle]} tags))
        (update :state rotate-fn angle))
    acc))

(defn- lower-arc*
  "Shared tessellation for :arc-h/:arc-v — ported verbatim from
   ridley.editor.macros rec-arc-h*/rec-arc-v*. rotate-cmd/rotate-fn is
   :th/th or :tv/tv."
  [acc rotate-cmd rotate-fn radius angle steps]
  (if (or (zero? radius) (zero? angle))
    acc
    (let [angle-rad (* (abs angle) (/ Math/PI 180))
          step-angle-deg (/ angle steps)
          step-angle-rad (/ angle-rad steps)
          step-dist (* 2 radius (Math/sin (/ step-angle-rad 2)))
          half-angle (/ step-angle-deg 2)
          emit-f (fn [acc d]
                   (-> acc (update :commands conj {:cmd :f :args [d]}) (update :state f d)))
          acc (-> acc
                  (update :commands conj {:cmd rotate-cmd :args [half-angle] :arc-cap :lead})
                  (update :state rotate-fn half-angle)
                  (emit-f step-dist))
          acc (reduce (fn [acc _]
                        (-> acc
                            (update :commands conj {:cmd rotate-cmd :args [step-angle-deg]})
                            (update :state rotate-fn step-angle-deg)
                            (emit-f step-dist)))
                      acc (range (dec steps)))]
      (-> acc
          (update :commands conj {:cmd rotate-cmd :args [half-angle] :arc-cap :trail})
          (update :state rotate-fn half-angle)))))

(defn- lower-bezier-to
  "Tessellate one high-level {:cmd :bezier-to :c1 :c2 :end :steps} command —
   ported verbatim from rec-bezier-to* (the post-control-point-resolution
   half: precompute points/segments/canonical-frame, the per-chord loop,
   the end-tangent exit correction).

   `anchor-auto?` (rec-bezier-to-anchor*'s auto branch, no explicit control
   points): the FIRST segment's rotation target — and residual-roll axis —
   is the entry heading itself rather than its own chord direction ('smooth
   connection' to the anchor)."
  [acc0 {:keys [c1 c2 end steps anchor-auto?]}]
  (let [state0 (:state acc0)
        p0 (:position state0)
        start-heading (:heading state0)
        start-up (:up state0)
        c1 (local->world state0 c1)
        c2 (local->world state0 c2)
        p3 (local->world state0 end)
        cubic-point (fn [t] (bezier/cubic-bezier-point p0 c1 c2 p3 t))
        points (mapv #(cubic-point (/ % steps)) (range (inc steps)))
        segments (vec (for [i (range steps)]
                        (let [curr (nth points i) nxt (nth points (inc i))
                              d (v- nxt curr) dist (magnitude d)]
                          {:dir (if (> dist 0.001) (normalize d) nil) :dist dist})))
        frame-ts (mapv #(/ % steps) (range 1 (inc steps)))
        frames (bezier/canonical-bezier-frame p0 c1 c2 p3 start-up frame-ts)
        acc1 (loop [remaining segments idx 0 acc acc0]
               (if (empty? remaining)
                 acc
                 (let [{:keys [dir dist]} (first remaining)
                       first? (zero? idx)
                       effective-dir (if (and first? anchor-auto?) start-heading dir)]
                   (if (and dir (> dist 0.001))
                     (let [cur-heading (:heading (:state acc))
                           cur-up (:up (:state acc))
                           [th-angle tv-angle] (bezier/compute-rotation-angles cur-heading cur-up effective-dir)
                           th-tv-tags (if first? {:smooth true :bez-cap :lead} {:smooth true})
                           tr-tags    (if first? {:smooth true :bez-cap :lead} {:smooth true})
                           acc (lower-rot-if acc :th th th-angle th-tv-tags)
                           acc (lower-rot-if acc :tv tv tv-angle th-tv-tags)
                           target-up (:up (nth frames idx))
                           byproduct-up (:up (:state acc))
                           roll-deg (bezier/residual-roll-deg byproduct-up target-up effective-dir)
                           acc (lower-rot-if acc :tr tr roll-deg tr-tags)
                           acc (-> acc (update :commands conj {:cmd :f :args [dist]}) (update :state f dist))]
                       (recur (rest remaining) (inc idx) acc))
                     (recur (rest remaining) (inc idx) acc)))))
        ;; End-tangent exit correction: face the analytic end tangent
        ;; (∝ p3 − c2), not the last chord — like the runtime bezier-walk.
        edge (v- p3 c2)
        elen (magnitude edge)
        acc2 (if (> elen 0.001)
               (let [end-dir (normalize edge)
                     [th-a tv-a] (bezier/compute-rotation-angles
                                  (:heading (:state acc1)) (:up (:state acc1)) end-dir)]
                 (-> acc1
                     (lower-rot-if :th th th-a {:smooth true})
                     (lower-rot-if :tv tv tv-a {:smooth true})))
               acc1)
        ;; Final residual roll onto the canonical exit up (frames' last
        ;; entry is already t=1).
        target-up (:up (peek frames))
        byproduct-up (:up (:state acc2))
        axis (:heading (:state acc2))
        roll-deg (bezier/residual-roll-deg byproduct-up target-up axis)
        acc3 (lower-rot-if acc2 :tr tr roll-deg {:smooth true})]
    acc3))

(defn- lower-bezier-as-step
  "One walk-step of a bezier-as segment (rec-bezier-as*'s apply-step,
   ported verbatim): rotate onto chord-heading (tagged :bez-cap :lead ONLY on
   the very first step of the whole bezier-as call, when c1 gives a genuine
   veer — otherwise plain, and NEVER :smooth — bezier-as's tessellation is
   intentionally untagged, see dev-docs/code-issues.md), move, rotate onto
   final-heading for tangent continuity (always plain), then a residual roll
   onto final-up (always plain)."
  [acc {:keys [dist chord-heading final-heading final-up]} c1 first?]
  (let [state (:state acc)
        cur-heading (:heading state) cur-up (:up state) cur-pos (:position state)
        chord-heading (local-dir->world state chord-heading)
        final-heading (local-dir->world state final-heading)
        final-up (when final-up (local-dir->world state final-up))
        [tha tva] (bezier/compute-rotation-angles cur-heading cur-up chord-heading)
        ;; `veer`'s value (the analytic angle) is no longer carried on the
        ;; command — only whether a GENUINE (non-degenerate) veer occurred
        ;; still gates whether this first step counts as a cap at all.
        veer (when (and first? c1)
               (let [c1-world (local->world state c1)
                     c1-p0 (v- c1-world cur-pos)
                     len (magnitude c1-p0)]
                 (when (>= len 1e-6)
                   (let [d (max -1.0 (min 1.0 (/ (dot c1-p0 cur-heading) len)))]
                     (* (Math/acos d) (/ 180 Math/PI))))))
        tags (if veer {:bez-cap :lead} {})
        acc (lower-rot-if acc :th th tha tags)
        acc (lower-rot-if acc :tv tv tva tags)
        acc (-> acc (update :commands conj {:cmd :f :args [dist]}) (update :state f dist))
        state2 (:state acc)
        [th2 tv2] (bezier/compute-rotation-angles (:heading state2) (:up state2) final-heading)
        acc (lower-rot-if acc :th th th2 {})
        acc (lower-rot-if acc :tv tv tv2 {})]
    (if final-up
      (let [byproduct-up (:up (:state acc))
            axis (:heading (:state acc))
            roll-deg (bezier/residual-roll-deg byproduct-up final-up axis)]
        (lower-rot-if acc :tr tr roll-deg {}))
      acc)))

(defn- lower-bezier-as
  "A whole bezier-as call — ported verbatim from rec-bezier-as*'s mode
   dispatch/mark emission (289-414), which is now identical between
   default/:cubic and :control since the recorder resolves both into the
   same {:segments :leading-marks :trailing-marks} shape (a segment's
   marks-by-step {0 [...]} covers default/:cubic's segment-boundary marks;
   {apex-idx [...]} covers :control's snap-to-apex marks; an empty
   walk-steps segment with marks-by-step {0 [...]} covers either mode's
   degenerate-segment-still-carries-a-mark edge case)."
  [acc {:keys [segments leading-marks trailing-marks]}]
  (let [emit-marks (fn [acc names] (reduce (fn [a nm] (update a :commands conj {:cmd :mark :args [nm]})) acc names))
        acc (emit-marks acc leading-marks)
        [acc _first?]
        (reduce
         (fn [[acc first?] {:keys [c1 walk-steps marks-by-step]}]
           (if (empty? walk-steps)
             [(emit-marks acc (get marks-by-step 0)) first?]
             (reduce
              (fn [[acc first?] [i step]]
                (let [acc (emit-marks acc (get marks-by-step i))
                      acc (lower-bezier-as-step acc step c1 first?)]
                  [acc false]))
              [acc first?]
              (map-indexed vector walk-steps))))
         [acc true]
         segments)]
    (emit-marks acc trailing-marks)))

(defn ^:export lower-commands
  [high-level-cmds state]
  (reduce
   (fn [acc {:keys [cmd args] :as c}]
     (case cmd
       :bezier-to (lower-bezier-to acc c)
       :bezier-as (lower-bezier-as acc c)
       :arc-h (lower-arc* acc :th th (first args) (second args) (:steps c))
       :arc-v (lower-arc* acc :tv tv (first args) (second args) (:steps c))
       :f  (-> acc (update :commands conj c) (update :state f (first args)))
       :th (-> acc (update :commands conj c) (update :state th (first args)))
       :tv (-> acc (update :commands conj c) (update :state tv (first args)))
       :tr (-> acc (update :commands conj c) (update :state tr (first args)))
       :u  (-> acc (update :commands conj c) (update :state move-up (first args)))
       :rt (-> acc (update :commands conj c) (update :state move-right (first args)))
       :lt (-> acc (update :commands conj c) (update :state move-left (first args)))
       :set-heading (-> acc (update :commands conj c) (update :state apply-set-heading args))
       (:mark :move-to :inset :scale :side-trip :cp-f :cp-rt :cp-u :cp-th :cp-tv :cp-tr
              :stretch-f :stretch-rt :stretch-u)
       (update acc :commands conj c)
       (throw (js/Error. (str "lower-commands: unknown command " cmd " — missing a case in lower-commands?")))))
   {:commands [] :state state}
   high-level-cmds))

(declare run-path)

(defn- run-side-trip
  "Replay a sub-path on `state`, then restore the original pose.
   Anchors added by the sub-path are kept. The spine is unaffected."
  [state sub-path]
  (let [saved-pos (:position state)
        saved-h   (:heading state)
        saved-u   (:up state)
        state'    (run-path state sub-path)]
    (-> state'
        (assoc :position saved-pos)
        (assoc :heading saved-h)
        (assoc :up saved-u))))

(defn run-path
  "Execute a path's commands on a turtle state.
   Handles :mark commands by saving the pose as an anchor.
   Handles :side-trip commands by running a sub-path and restoring the pose.
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
                :set-heading (apply-set-heading s args)
                :mark (assoc-in s [:anchors (first args)]
                                {:position (:position s)
                                 :heading (:heading s)
                                 :up (:up s)})
                :side-trip (run-side-trip s (first args))
                ;; A move-to with a coordinate vector sets the position directly —
                ;; so mark resolution honors a leading move-to the same way
                ;; path-to-shape / path-to-2d-waypoints do, keeping the anchors
                ;; aligned with the geometry. (move-to to a mark keyword stays a
                ;; no-op here; it is handled in attach context.)
                :move-to (let [t (first args)]
                           (if (and (sequential? t) (number? (first t)))
                             (assoc s :position
                                    [(first t) (second t)
                                     (if (> (count t) 2) (nth t 2) (nth (:position s) 2 0))])
                             s))
                s))
            state
            (path-micro-commands path))
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
                             (assoc :anchors {})
                             (assoc :preserve-up (:preserve-up state))
                             (assoc :reference-up (:reference-up state)))
          result (run-path virtual-turtle path)]
      (:anchors result))
    {}))

(defn synthesize-delta
  "Minimal canonical (th tv tr f rt u) delta that turns turtle pose `from`
   into pose `to`, exactly — not an approximation.

   Rotation and translation are independent degrees of freedom: `to`'s
   position is generally NOT straight ahead of `to`'s heading (it was
   reached by whatever sequence of moves the session actually made, not by
   one final straight hop) — so aiming th/tv at the position delta and
   hoping the resulting heading matches to.heading is wrong in general (it
   only works when to.position - from.position happens to be parallel to
   to.heading). The correct decomposition is two independent steps:

   1. Reach to's exact ORIENTATION first — th then tv rotate from-heading
      onto to.heading directly (this is always achievable exactly,
      regardless of position), then a residual tr fixes up. Same
      th-then-tv + residual-roll decomposition lower-commands already uses
      for bezier/arc lowering (bezier/compute-rotation-angles,
      bezier/residual-roll-deg).
   2. Reach to's exact POSITION from there — the position delta, projected
      onto the NOW-FINAL frame's heading/right/up axes, gives f/rt/u:
      three pure translations that don't touch heading/up, so they can
      follow the rotation in any order and still land exactly on
      to.position.

   A component is nil when it's below the noise floor (0.001° / 0.0001mm,
   same thresholds lower-commands' own lower-rot-if uses) — e.g. a pure
   forward move returns {:th nil :tv nil :tr nil :f d :rt nil :u nil}, and
   the common case the brief illustrates (position delta already parallel
   to the target heading) naturally collapses rt/u away, leaving the
   simple (th)(tv)(f) form.

   `from`/`to` are {:position :heading :up} maps (a full turtle state works
   too, extra keys are ignored)."
  [{fp :position fh :heading fu :up} {tp :position th-target :heading tu :up}]
  (let [[th-deg tv-deg] (bezier/compute-rotation-angles fh fu th-target)
        scratch (-> (make-turtle)
                    (assoc :position fp :heading fh :up fu)
                    (th th-deg)
                    (tv tv-deg))
        roll-deg (bezier/residual-roll-deg (:up scratch) tu (:heading scratch))
        ;; th-then-tv aims heading exactly at th-target by construction; tr
        ;; only rotates up around that fixed heading, so the final frame is
        ;; exactly (th-target, tu, right = th-target × tu) — no need to
        ;; replay tr on the scratch turtle to know this.
        final-right (cross th-target tu)
        pos-delta (v- tp fp)
        f-dist (dot pos-delta th-target)
        rt-dist (dot pos-delta final-right)
        u-dist (dot pos-delta tu)
        negligible-angle? #(< (Math/abs %) 0.001)
        negligible-dist? #(< (Math/abs %) 0.0001)]
    {:th (when-not (negligible-angle? th-deg) th-deg)
     :tv (when-not (negligible-angle? tv-deg) tv-deg)
     :tr (when-not (negligible-angle? roll-deg) roll-deg)
     :f  (when-not (negligible-dist? f-dist) f-dist)
     :rt (when-not (negligible-dist? rt-dist) rt-dist)
     :u  (when-not (negligible-dist? u-dist) u-dist)}))

;; Inject resolve-marks into the extrusion engine so extrude/loft can turn a
;; profile shape's carried marks into mesh anchors (extrusion can't require this
;; namespace — the dependency runs the other way).
(reset! extrusion/resolve-marks-ref resolve-marks)
(reset! extrusion/with-micro-commands-ref with-micro-commands)
(reset! extrusion/path-micro-commands-ref path-micro-commands)

;; Inject path-micro-commands into shape's tracers (path-to-3d-waypoints etc.)
;; so a high-level curve command gets lowered before any tracer sees it —
;; shape.cljs can't require this namespace (core → loft → shape cycle).
(reset! shape/path-micro-commands-ref path-micro-commands)

(defn path-segments
  "Split a path's commands into segments, one per :f command.
   Each segment is a group of rotation commands followed by one :f.
   Returns a vector of {:rotations [...] :distance d}."
  [path]
  (loop [cmds (path-micro-commands path)
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
                            :set-heading (apply-set-heading s args)
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
                                    :segment-index i
                                    :c1 nil}))
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
                                      :segment-index i
                                      :c1 c1}))))))))))

(defn- apply-walk-step
  "Apply a single walk step to turtle state, drawing a line."
  [state step]
  (let [{:keys [dist chord-heading final-heading final-up]} step]
    (-> state
        (assoc :heading chord-heading)  ; use chord to draw correctly
        (f dist)                        ; draws line from current pos to new pos
        (assoc :heading final-heading)  ; restore tangent heading
        (assoc :up final-up))))

(defn compute-midpoint-walk
  "Pure walk data for the control-polygon (midpoint) spline. The path's vertices
   are treated as OFF-curve control points; the curve passes through each segment
   midpoint (and the clamped endpoints), tangent to the polygon there, and is C1.
   One quadratic per interior vertex, degree-elevated to a cubic so the existing
   sampler is reused. With fewer than one interior vertex it falls back to the
   straight polyline. This is the dual of compute-bezier-walk (vertices on-curve)."
  [segments init-pose calc-steps-fn]
  (let [wps (compute-path-waypoints segments init-pose)
        V (mapv :position wps)
        n (count V)]
    (if (< n 3)
      (compute-bezier-walk segments init-pose {:tension 0.0 :calc-steps-fn calc-steps-fn})
      (let [mid (fn [a b] (v* (v+ a b) 0.5))
            MP (mapv #(mid (nth V %) (nth V (inc %))) (range (dec n)))
            last-k (- n 2)]
        (loop [k 1
               pose init-pose
               results []]
          (if (> k last-k)
            results
            (let [A (if (= k 1) (nth V 0) (nth MP (dec k)))
                  C (nth V k)
                  B (if (= k last-k) (nth V (dec n)) (nth MP k))
                  ;; degree-elevate the quadratic [A C B] to a cubic
                  c1 (v+ A (v* (v- C A) (/ 2.0 3.0)))
                  c2 (v+ B (v* (v- C B) (/ 2.0 3.0)))
                  seg-len (magnitude (v- B A))
                  steps (if calc-steps-fn (calc-steps-fn seg-len) 16)
                  walk-steps (sample-bezier-segment A c1 c2 B steps (:heading pose) (:up pose))
                  final-pose (if (seq walk-steps)
                               (let [ls (peek walk-steps)]
                                 {:position (:to ls) :heading (:final-heading ls) :up (:final-up ls)})
                               (assoc pose :position B))]
              (recur (inc k) final-pose
                     (conj results {:walk-steps walk-steps
                                    :target-pose final-pose
                                    :segment-index (dec k)
                                    :c1 c1})))))))))

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
  (let [{:keys [tension steps max-segment-length cubic control]} (apply hash-map args)
        segments (path-segments p)
        ;; Optionally subdivide long segments
        segments (if max-segment-length
                   (vec (mapcat #(subdivide-segment % max-segment-length) segments))
                   segments)
        ;; Marks in the source path sit at segment boundaries, where the bezier
        ;; curve coincides with the polyline (the bezier endpoints ARE the
        ;; waypoints). So resolve them at the entry pose — same poses the
        ;; un-bezier'd path would record — and carry them onto the result, since
        ;; path-segments/segment->state otherwise drop :mark commands silently.
        marks (resolve-marks state p)
        carry-marks (fn [st] (if (seq marks) (update st :anchors merge marks) st))]
    (if (empty? segments)
      (carry-marks state)
      ;; Use pure function to compute walk data
      (let [init-pose (select-keys state [:position :heading :up])
            calc-steps-fn (or (when-not steps #(calc-bezier-steps state %))
                              (constantly (or steps 16)))
            walk-data (if control
                        ;; control-polygon (midpoint) spline: vertices are controls
                        (compute-midpoint-walk segments init-pose calc-steps-fn)
                        (compute-bezier-walk
                         segments init-pose
                         {:tension (or tension 0.33)
                          :cubic cubic
                          :calc-steps-fn calc-steps-fn}))]
        ;; Apply walk steps to state
        (carry-marks
         (reduce
          (fn [current-state segment-data]
            (if (:degenerate segment-data)
              ;; Degenerate segment: apply rotations via segment->state
              (segment->state current-state (nth segments (:segment-index segment-data)))
              ;; Normal segment: apply walk steps
              (reduce apply-walk-step current-state (:walk-steps segment-data))))
          state
          walk-data))))))

;; ============================================================
;; Path sampling for text-on-path
;; ============================================================

(defn ^:export path-total-length
  "Calculate total arc length of a path by summing absolute distances of all movement commands."
  [path]
  (if (path? path)
    (->> (path-micro-commands path)
         (filter #(#{:f :u :rt :lt} (:cmd %)))
         (map #(Math/abs (first (:args %))))
         (reduce + 0))
    0))

(defn ^:export add-mark
  "Return a NEW path with a mark named `mark-name` inserted at `fraction`
   (0..1) of the path's total arc length — the existing path is not mutated.

   The path is walked along its spine (top-level movement commands :f/:u/:rt/:lt;
   side-trips and pure rotations have zero spine length). The movement command
   that straddles the target distance is split so the mark lands exactly there.

   Like any path mark, the inserted mark rides extrude/loft/revolve into the
   mesh as an :anchor — so a ruler to it tracks the realized geometry:
   (add-mark (path (bezier-to-anchor ps :at :end :tension 0.5)) :apex 0.5)
   then (ruler :wall :at :center :wall :at :apex)."
  [path mark-name fraction]
  (if-not (path? path)
    path
    (let [total (path-total-length path)
          target (* (max 0.0 (min 1.0 fraction)) total)
          move? (fn [c] (contains? #{:f :u :rt :lt} (:cmd c)))
          eps 1.0e-9
          mark-cmd {:cmd :mark :args [mark-name]}]
      (loop [cmds (path-micro-commands path)
             acc 0.0
             out []]
        (if (empty? cmds)
          ;; target at/after the end → append the mark last
          (make-path (conj out mark-cmd))
          (let [cmd (first cmds)]
            (if (move? cmd)
              (let [raw (first (:args cmd))
                    d (Math/abs raw)
                    sgn (if (neg? raw) -1.0 1.0)
                    next-acc (+ acc d)]
                (if (and (pos? d) (>= next-acc (- target eps)))
                  ;; target lands inside this move — split (move a)(mark)(move b)
                  (let [a (- target acc)
                        b (- d a)
                        k (:cmd cmd)
                        out (cond-> out
                              (> a eps) (conj {:cmd k :args [(* sgn a)]}))
                        out (conj out mark-cmd)
                        out (cond-> out
                              (> b eps) (conj {:cmd k :args [(* sgn b)]}))]
                    (make-path (into out (rest cmds))))
                  (recur (rest cmds) next-acc (conj out cmd))))
              (recur (rest cmds) acc (conj out cmd)))))))))

(defn ^:export sample-path-at-distance
  "Sample a path at a specific arc-length distance.
   Returns {:position [x y z] :heading [x y z] :up [x y z]} or nil if past end.

   Options:
   - :wrap? - if true, wrap distance around for closed paths (default false)
   - :start-pos - starting position (default [0 0 0])
   - :start-heading - starting heading (default [1 0 0])
   - :start-up - starting up vector (default [0 0 1])
   - :preserve-up - when true, keep up aligned with reference-up (default false)
   - :reference-up - the reference up vector for preserve-up mode"
  [path distance & {:keys [wrap? start-pos start-heading start-up preserve-up reference-up]
                    :or {wrap? false
                         start-pos [0 0 0]
                         start-heading [1 0 0]
                         start-up [0 0 1]
                         preserve-up false}}]
  (when (path? path)
    (let [total (path-total-length path)
          dist (if (and wrap? (> distance total) (pos? total))
                 (mod distance total)
                 distance)
          ref-up (or reference-up start-up)]
      (when (and (>= dist 0) (<= dist total))
        (loop [cmds (path-micro-commands path)
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
                        rad (deg->rad angle)]
                    (if preserve-up
                      (let [new-heading (normalize (rotate-around-axis heading ref-up rad))
                            new-up (orthogonalize ref-up new-heading)]
                        (recur (rest cmds) pos new-heading new-up cumulative))
                      (let [new-heading (rotate-around-axis heading up rad)]
                        (recur (rest cmds) pos new-heading up cumulative))))
              :tv (let [angle (first (:args cmd))
                        rad (deg->rad angle)
                        right (cross heading up)
                        new-heading (normalize (rotate-around-axis heading right rad))]
                    (if preserve-up
                      (let [new-up (orthogonalize ref-up new-heading)]
                        (recur (rest cmds) pos new-heading new-up cumulative))
                      (let [new-up (rotate-around-axis up right rad)]
                        (recur (rest cmds) pos new-heading new-up cumulative))))
              :tr (let [angle (first (:args cmd))
                        rad (deg->rad angle)
                        new-up (rotate-around-axis up heading rad)]
                    (recur (rest cmds) pos heading new-up cumulative))
              ;; Lateral movement commands — contribute to arc length like :f
              (:u :rt :lt)
              (let [d (first (:args cmd))
                    abs-d (Math/abs d)
                    dir (case (:cmd cmd)
                          :u  up
                          :rt (normalize (cross heading up))
                          :lt (v* (normalize (cross heading up)) -1))]
                (if (and (pos? abs-d) (>= (+ cumulative abs-d) dist))
                  ;; Target is in this segment - interpolate position
                  (let [remaining (- dist cumulative)
                        frac (/ remaining abs-d)
                        final-pos (v+ pos (v* dir (* d frac)))]
                    {:position final-pos :heading heading :up up})
                  ;; Continue to next segment
                  (recur (rest cmds)
                         (v+ pos (v* dir d))
                         heading up
                         (+ cumulative abs-d))))
              ;; Unknown command - skip
              (recur (rest cmds) pos heading up cumulative))
            ;; End of commands - return final position if we reached the target
            (when (>= cumulative dist)
              {:position pos :heading heading :up up})))))))

(defn ^:export rail-locator->pose
  "Resolve a rail locator on a mesh's stored :rail-path to a cross-section pose.
   A locator is either a keyword (a named rail mark) or a number t in [0,1]
   (fractional arc-length along the rail — t=0 is the base section, t=1 the end;
   no marks needed). Resolved from the mesh's :creation-pose, so it stays correct
   after the mesh moves. Returns {:position :heading :up} or nil."
  [mesh rail-loc]
  (let [rail-path (:rail-path mesh)
        cp (or (:creation-pose mesh) {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})]
    (when rail-path
      (if (number? rail-loc)
        (let [total (path-total-length rail-path)
              t (max 0.0 (min 1.0 rail-loc))]
          (sample-path-at-distance rail-path (* t total)
                                   :start-pos (:position cp)
                                   :start-heading (:heading cp)
                                   :start-up (:up cp)))
        (get (resolve-marks cp rail-path) rail-loc)))))

(defn ^:export rail-fraction
  "Normalised position t∈[0,1] of a rail locator along a mesh's :rail-path.
   A number is clamped; a keyword mark gives (arc length up to the mark)/total.
   Returns nil if the rail/mark is missing. Used to evaluate a loft's per-t
   cross-section (slice-mesh :on)."
  [mesh rail-loc]
  (let [rail-path (:rail-path mesh)]
    (when rail-path
      (if (number? rail-loc)
        (max 0.0 (min 1.0 rail-loc))
        (let [total (path-total-length rail-path)
              d (loop [cmds (path-micro-commands rail-path) acc 0]
                  (cond
                    (empty? cmds) nil
                    (and (= :mark (:cmd (first cmds)))
                         (= rail-loc (first (:args (first cmds))))) acc
                    (#{:f :u :rt :lt} (:cmd (first cmds)))
                    (recur (rest cmds) (+ acc (Math/abs (first (:args (first cmds))))))
                    :else (recur (rest cmds) acc)))]
          (when (and d (pos? total)) (/ d total)))))))

;; ============================================================
;; Revolve (lathe operation)
;; ============================================================

(defn ^:export revolve-shape
  "Revolve a 2D profile shape around the turtle's up axis.
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
     (let [;; Clamp to one full turn: beyond ±360 the sweep wraps over itself
           ;; (is-closed treats it as a full revolution but spreads the rings
           ;; over the larger angle), producing self-overlapping geometry.
           angle (-> angle (max -360.0) (min 360.0))
           ;; Save creation pose
           creation-pose {:position (:position state)
                          :heading (:heading state)
                          :up (:up state)}
           ;; Get profile points
           ;; When using shape-fn, clamp x away from 0 to prevent crossing
           ;; revolution axis (polygon clipping would change point count,
           ;; breaking face generation). Clamp magnitude to a tiny minimum
           ;; (not zero) to prevent pole vertex collisions in STL float32 —
           ;; a tiny circle is manifold, a point is not. The clamp preserves
           ;; sign so shapes entirely on the negative-x side (used by
           ;; `revolve+` with `:pivot :right`) keep their position.
           pole-eps 0.001
           clamp-x (fn [pts]
                     (mapv (fn [[x y]]
                             [(if (neg? x)
                                (min (- pole-eps) x)
                                (max pole-eps x))
                              y])
                           pts))
           profile-points (clamp-x (:points shape))
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
           base-flip? (if shape-is-ccw? (pos? angle) (neg? angle))
           ;; If the shape sits entirely on the non-positive-x side of the
           ;; axis (e.g. after `revolve+ :pivot :right`, where the right
           ;; edge sits exactly at x=0), the radial direction reverses, so
           ;; the winding→outward mapping inverts. Flip again to keep
           ;; normals outward. Detection uses the raw (unclamped) shape
           ;; because clamp-x pushes x=0 points to +pole-eps.
           shape-on-negative-x? (not (pos? (apply max (map first (:points shape)))))
           flip-winding? (if shape-on-negative-x? (not base-flip?) base-flip?)
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
           ;; Profile marks → mesh anchors, resolved on the θ=0 seam. revolve
           ;; maps raw 2D points (X=radius, Y=axis) with offset [0 0] regardless
           ;; of :centered?, so the section-anchor mapping must match.
           section-2d (extrusion/resolve-section-anchors shape)
           section-3d (extrusion/section-anchors->3d
                       section-2d
                       {:plane-x right :plane-y up :offset [0 0] :origin pos})
           ;; Destructure orientation vectors once (avoid repeated nth)
           [pos-x pos-y pos-z] pos
           [up-x up-y up-z] up
           [right-x right-y right-z] right
           [head-x head-y head-z] heading
           ;; Transform a contour's 2D points to 3D at a given angle.
           ;; cos-t/sin-t and radial vector are pre-computed per ring,
           ;; so each point only does 6 mul + 3 add (no trig).
           transform-contour (fn [pts cos-t sin-t]
                               (let [rx (+ (* cos-t right-x) (* sin-t head-x))
                                     ry (+ (* cos-t right-y) (* sin-t head-y))
                                     rz (+ (* cos-t right-z) (* sin-t head-z))]
                                 (mapv (fn [[px py]]
                                         [(+ pos-x (* py up-x) (* px rx))
                                          (+ pos-y (* py up-y) (* px ry))
                                          (+ pos-z (* py up-z) (* px rz))])
                                       pts)))
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
           ;; Generate all rings
           ;; When eval-shape-at-t is provided, evaluate it at each step
           ;; to get varying profiles (for shape-fn support)
           ;; Each ring = outer-pts ++ hole0-pts ++ hole1-pts ...
           rings (vec (for [i (range n-rings)]
                        (let [theta (* i angle-step)
                              cos-t (Math/cos theta)
                              sin-t (Math/sin theta)
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
                                (transform-contour ring-outer cos-t sin-t)
                                (when ring-holes
                                  (mapcat #(transform-contour % cos-t sin-t) ring-holes)))))))
           ;; Flatten vertices (transient for speed)
           vertices (persistent!
                     (reduce (fn [a ring] (reduce conj! a ring))
                             (transient []) rings))
           ;; Helper: generate side faces for a contour strip using a tight loop.
           ;; contour-len = number of vertices in the contour,
           ;; offset = starting index of that contour within each ring.
           gen-strip-faces
           (fn [contour-len offset]
             (let [n-face-rings (if is-closed n-rings (dec n-rings))]
               (persistent!
                (loop [ri 0, acc (transient [])]
                  (if (>= ri n-face-rings)
                    acc
                    (let [next-ri (if (and is-closed (= ri (dec n-rings))) 0 (inc ri))
                          base (+ (* ri ring-len) offset)
                          next-base (+ (* next-ri ring-len) offset)]
                      (recur (inc ri)
                             (loop [vi 0, acc acc]
                               (if (>= vi contour-len)
                                 acc
                                 (let [next-vi (let [v (inc vi)] (if (= v contour-len) 0 v))
                                       b0 (+ base vi) b1 (+ base next-vi)
                                       t0 (+ next-base vi) t1 (+ next-base next-vi)]
                                   (recur (inc vi)
                                          (if flip-winding?
                                            (-> acc (conj! [b0 t0 t1]) (conj! [b0 t1 b1]))
                                            (-> acc (conj! [b0 t1 t0]) (conj! [b0 b1 t1]))))))))))))))
           ;; Side faces for outer contour
           outer-side-faces (gen-strip-faces n-profile 0)
           ;; Side faces for each hole
           hole-side-faces
           (when has-holes?
             (loop [hi 0, offset n-profile, acc (transient [])]
               (if (>= hi (count hole-lengths))
                 (persistent! acc)
                 (let [hl (nth hole-lengths hi)
                       faces (gen-strip-faces hl offset)]
                   (recur (inc hi) (+ offset hl)
                          (reduce conj! acc faces))))))
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
                   (:material state) (assoc :material (:material state))
                   (seq section-3d) (assoc :anchors section-3d
                                           :section-anchors section-2d)))]
       (update state :meshes conj mesh)))))

;; ============================================================
;; Anchors and Navigation
;; ============================================================

;; mark removed — marks now exist only inside path recordings.
;; Use (mark :name) inside (path ...) to embed a mark,
;; then (with-path p ...) to resolve marks as anchors.

(defn goto
  "Move to a named anchor position and adopt its heading/up.
   Draws a line if pen-mode is :on (flushed to scene accumulator by implicit layer).
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
   Moves turtle to the mesh's creation position, adopts its heading and up vectors.
   Returns state unchanged if mesh has no creation-pose."
  [state mesh & {:keys [clone]}]
  (let [target-mesh (if clone (clone-mesh mesh) mesh)
        state (if clone
                (update state :meshes conj target-mesh)
                state)]
    (if-let [pose (:creation-pose target-mesh)]
      (-> state
          (assoc :position (:position pose))
          (assoc :heading (:heading pose))
          (assoc :up (:up pose))
          (assoc :attached {:type :pose
                            :mesh target-mesh
                            :original-pose pose}))
      state)))

(defn- resolve-face-triangles
  "Resolve triangles for a face-id, which can be:
   - keyword or number: single face-group lookup from face-groups
   - vector of numbers: indices into :faces (or face-groups if present)
   face-groups may be nil for meshes without semantic faces (e.g. boolean results)."
  [face-groups faces face-id]
  (cond
    (vector? face-id)
    (if face-groups
      ;; Has face-groups: look up each index as a face-group key
      (let [tris (mapcat #(get face-groups %) face-id)]
        (when (seq tris) (vec tris)))
      ;; No face-groups: indices refer to positions in :faces array
      (let [tris (keep #(nth faces % nil) face-id)]
        (when (seq tris) (vec (map vec tris)))))

    :else
    (when face-groups
      (get face-groups face-id))))

(defn attach-face
  "Attach to a specific face of a mesh.
   face-id can be a keyword (:top), a number (face index), or a vector of
   numbers (multiple face indices, e.g. from flood-fill picking).
   For vector face-ids, works even without :face-groups by indexing :faces directly.
   With :clone true, enables extrusion mode (f creates side faces).
   Without :clone, face movement mode (f moves vertices directly).
   Moves turtle to face center, sets heading to face normal (outward), up perpendicular.
   Returns state unchanged if face not found."
  [state mesh face-id & {:keys [clone]}]
  (if-let [triangles (resolve-face-triangles (:face-groups mesh) (:faces mesh) face-id)]
    (let [info (compute-face-info-internal (:vertices mesh) triangles)
          normal (:normal info)
          center (:center info)
          ;; Derive up vector perpendicular to normal
          face-heading (:heading info)
          ;; up = normal × face-heading (perpendicular to both)
          up (normalize (cross normal face-heading))]
      (-> state
          (assoc :position center)
          (assoc :heading normal)
          (assoc :up up)
          (assoc :attached {:type :face
                            :mesh mesh
                            :face-id face-id
                            :face-info info
                            :extrude-mode clone})))  ; flag: if true, f() extrudes
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
  "Detach from current attachment.
   Clears the :attached field. Returns state unchanged if not attached."
  [state]
  (if (:attached state)
    (assoc state :attached nil)
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
