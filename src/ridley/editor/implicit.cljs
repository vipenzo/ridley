(ns ridley.editor.implicit
  "Implicit turtle functions that mutate the current turtle atom.
   These functions are bound in the SCI context via base-bindings.
   The turtle atom is resolved via state/turtle-state-var, supporting
   scoped turtle contexts."
  (:require [ridley.editor.state :as state]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.path :as path]
            [ridley.turtle.transform :as xform]
            [ridley.geometry.primitives :as prims]
            [ridley.manifold.core :as manifold]
            [ridley.scene.registry :as registry]
            [ridley.scene.panel :as panel]
            [ridley.viewport.core :as viewport]))

;; Local accessor — returns the current turtle atom.
;; Inside a (turtle ...) scope, returns the scoped atom.
(defn- turtle-ref [] @state/turtle-state-var)

;; --- Scene accumulator helpers ---

(defn- record-pen-lines!
  "Flush any pen traces from turtle state :geometry to the shared scene accumulator.
   Called after every implicit command that can produce lines."
  []
  (let [lines (:geometry @(turtle-ref))]
    (when (seq lines)
      (swap! state/scene-accumulator update :lines into lines)
      (swap! (turtle-ref) assoc :geometry []))))

(defn- record-stamps!
  "Flush any stamps from turtle state :stamps to the shared scene accumulator."
  []
  (let [stamps (:stamps @(turtle-ref))]
    (when (seq stamps)
      (swap! state/scene-accumulator update :stamps into stamps)
      (swap! (turtle-ref) assoc :stamps []))))

(defn ^:export implicit-f [dist]
  (let [old-attached (:attached @(turtle-ref))
        registry-idx (:registry-index old-attached)]
    (swap! (turtle-ref) turtle/f dist)
    (record-pen-lines!)
    ;; If attached with registry index, update the registry directly
    (when registry-idx
      (let [new-mesh (get-in @(turtle-ref) [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-th [angle]
  (let [old-attached (:attached @(turtle-ref))
        registry-idx (:registry-index old-attached)]
    (swap! (turtle-ref) turtle/th angle)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @(turtle-ref) [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-tv [angle]
  (let [old-attached (:attached @(turtle-ref))
        registry-idx (:registry-index old-attached)]
    (swap! (turtle-ref) turtle/tv angle)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @(turtle-ref) [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-tr [angle]
  (let [old-attached (:attached @(turtle-ref))
        registry-idx (:registry-index old-attached)]
    (swap! (turtle-ref) turtle/tr angle)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @(turtle-ref) [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

;; Lateral movement (pure translation, no heading change)
(defn ^:export implicit-u [dist]
  (let [old-attached (:attached @(turtle-ref))
        registry-idx (:registry-index old-attached)]
    (swap! (turtle-ref) turtle/move-up dist)
    (record-pen-lines!)
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @(turtle-ref) [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-d [dist]
  (let [old-attached (:attached @(turtle-ref))
        registry-idx (:registry-index old-attached)]
    (swap! (turtle-ref) turtle/move-down dist)
    (record-pen-lines!)
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @(turtle-ref) [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-rt [dist]
  (let [old-attached (:attached @(turtle-ref))
        registry-idx (:registry-index old-attached)]
    (swap! (turtle-ref) turtle/move-right dist)
    (record-pen-lines!)
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @(turtle-ref) [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-lt [dist]
  (let [old-attached (:attached @(turtle-ref))
        registry-idx (:registry-index old-attached)]
    (swap! (turtle-ref) turtle/move-left dist)
    (record-pen-lines!)
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @(turtle-ref) [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-pen-up []
  (swap! (turtle-ref) turtle/pen-up))

(defn ^:export implicit-pen-down []
  (swap! (turtle-ref) turtle/pen-down))

(defn ^:export implicit-pen [mode]
  (swap! (turtle-ref) turtle/pen mode))

(defn ^:export implicit-joint-mode [mode]
  (swap! (turtle-ref) turtle/joint-mode mode))

;; Resolution (like OpenSCAD $fn/$fa/$fs)
(defn ^:export implicit-resolution [mode value]
  (swap! (turtle-ref) turtle/resolution mode value))

;; Color and material
(defn- mesh-map? [x]
  (and (map? x) (:vertices x)))

(defn ^:export implicit-color
  "Set color globally, on a registered mesh, or on a mesh reference.
   (color 0xff0000)              — set global color (hex)
   (color 255 0 0)               — set global color (RGB)
   (color :my-mesh 0xff0000)     — set color on registered mesh
   (color :my-mesh 255 0 0)      — set color on registered mesh (RGB)
   (color my-mesh 0xff0000)      — return mesh with color (pure)
   (color my-mesh 255 0 0)       — return mesh with color (pure, RGB)"
  ([hex]
   (swap! (turtle-ref) turtle/set-color hex))
  ([name-or-mesh hex]
   (if (mesh-map? name-or-mesh)
     (update name-or-mesh :material merge {:color hex})
     (registry/update-mesh-material! (keyword name-or-mesh) {:color hex})))
  ([r g b]
   (swap! (turtle-ref) turtle/set-color r g b))
  ([name-or-mesh r g b]
   (let [hex (+ (bit-shift-left (int r) 16)
                (bit-shift-left (int g) 8)
                (int b))]
     (if (mesh-map? name-or-mesh)
       (update name-or-mesh :material merge {:color hex})
       (registry/update-mesh-material! (keyword name-or-mesh) {:color hex})))))

(def ^:private material-props
  #{:color :metalness :roughness :opacity :flat-shading})

(defn ^:export implicit-material
  "Set material properties globally, on a registered mesh, or on a mesh reference.
   (material :metalness 0.8 :roughness 0.2)              — global
   (material :my-mesh :metalness 0.8 :roughness 0.2)     — per-mesh
   (material my-mesh :opacity 0.3 :color 0xff0000)       — return mesh with material (pure)"
  [& args]
  (let [first-arg (first args)]
    (cond
      ;; Mesh reference → pure function, return mesh with material
      (mesh-map? first-arg)
      (let [opts (apply hash-map (rest args))]
        (update first-arg :material merge opts))
      ;; Material property keyword → global
      (material-props first-arg)
      (let [opts (apply hash-map args)]
        (swap! (turtle-ref) #(apply turtle/set-material % (mapcat identity opts))))
      ;; Otherwise → mesh name in registry
      :else
      (let [mesh-name first-arg
            opts (apply hash-map (rest args))]
        (registry/update-mesh-material! (keyword mesh-name) opts)))))

(defn ^:export implicit-reset-material
  "Reset material to default values."
  []
  (swap! (turtle-ref) turtle/reset-material))

;; Stamp debug visualization
(defn ^:export implicit-stamp-debug
  "Visualize a 2D shape at current turtle pose as a semi-transparent surface.
   Accepts a shape or a path (auto-converted via path-to-shape).
   Optional :color (hex int) overrides the default orange."
  [shape-or-path & {:keys [color]}]
  (let [s (if (turtle/path? shape-or-path)
            (shape/path-to-shape shape-or-path)
            shape-or-path)]
    (swap! (turtle-ref) turtle/stamp-debug s :color color)
    (record-stamps!)))

;; Arc commands
(defn ^:export implicit-arc-h [radius angle & {:keys [steps]}]
  (swap! (turtle-ref) #(turtle/arc-h % radius angle :steps steps))
  (record-pen-lines!))

(defn ^:export implicit-arc-v [radius angle & {:keys [steps]}]
  (swap! (turtle-ref) #(turtle/arc-v % radius angle :steps steps))
  (record-pen-lines!))

;; Bezier commands
(defn ^:export implicit-bezier-to [target & args]
  (swap! (turtle-ref) #(apply turtle/bezier-to % target args))
  (record-pen-lines!))

(defn ^:export implicit-bezier-to-anchor [anchor-name & args]
  (swap! (turtle-ref) #(apply turtle/bezier-to-anchor % anchor-name args))
  (record-pen-lines!))

(defn ^:export implicit-bezier-as [p & args]
  (swap! (turtle-ref) #(apply turtle/bezier-as % p args))
  (record-pen-lines!))

;; Anchors and navigation
;; mark removed — marks now exist only inside path recordings.

(defn ^:export implicit-save-anchors
  "Save current turtle anchors. Returns the saved anchors map."
  []
  (get @(turtle-ref) :anchors {}))

(defn ^:export implicit-restore-anchors
  "Restore turtle anchors to a previously saved state."
  [saved]
  (swap! (turtle-ref) assoc :anchors saved))

(defn ^:export implicit-resolve-and-merge-marks
  "Resolve marks from a path at current turtle pose and merge into anchors."
  [path]
  (let [marks (turtle/resolve-marks @(turtle-ref) path)]
    (swap! (turtle-ref) update :anchors merge marks)))

(defn ^:export implicit-goto [name]
  (swap! (turtle-ref) turtle/goto name)
  (record-pen-lines!))

(defn ^:export get-anchor
  "Get anchor data by name from turtle state. Returns {:position [x y z] :heading [x y z] :up [x y z]} or nil."
  [name]
  (get-in @(turtle-ref) [:anchors name]))

(defn ^:export implicit-attach-path
  "Associate a path's marks as anchors on a registered mesh.
   Resolves the path marks at the mesh's creation-pose."
  [mesh-name path-data]
  (when-let [mesh (registry/get-mesh mesh-name)]
    (let [pose (or (:creation-pose mesh)
                   {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
          anchors (turtle/resolve-marks pose path-data)]
      (when (seq anchors)
        (registry/register-mesh! mesh-name (assoc mesh :anchors anchors))))))

(defn ^:export implicit-look-at [name]
  (swap! (turtle-ref) turtle/look-at name))

(defn ^:export implicit-path-to [name]
  ;; First orient turtle toward anchor, then create path
  ;; This ensures extrusions go in the correct direction
  (swap! (turtle-ref) turtle/look-at name)
  (turtle/path-to @(turtle-ref) name))

(defn ^:export implicit-inset [dist]
  (let [old-attached (:attached @(turtle-ref))
        registry-idx (:registry-index old-attached)]
    (swap! (turtle-ref) turtle/inset dist)
    ;; If attached with registry index, update the registry directly
    (when registry-idx
      (let [new-mesh (get-in @(turtle-ref) [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-scale-mesh [factor]
  (let [old-attached (:attached @(turtle-ref))
        registry-idx (:registry-index old-attached)]
    (swap! (turtle-ref) turtle/scale factor)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @(turtle-ref) [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export unified-scale
  "Unified scale function:
   - If first arg is a shape, scales the shape (2D)
   - If no args and attached to mesh, scales the attached mesh"
  ([factor]
   ;; No shape provided - try to scale attached mesh
   (if (= :pose (get-in @(turtle-ref) [:attached :type]))
     (implicit-scale-mesh factor)
     (throw (js/Error. "scale requires a shape argument, or attach to a mesh first"))))
  ([shape factor]
   ;; Shape provided - scale the 2D shape
   (xform/scale shape factor))
  ([shape fx fy]
   ;; Non-uniform scale of 2D shape
   (xform/scale shape fx fy)))

;; Transform a mesh to turtle position/orientation
(defn- transform-mesh-to-turtle
  "Transform a mesh's vertices to current turtle position and orientation.
   Also stores creation-pose and material so mesh can be re-attached later."
  [mesh]
  (let [turtle @(turtle-ref)
        position (:position turtle)
        heading (:heading turtle)
        up (:up turtle)
        material (:material turtle)
        transformed-verts (prims/apply-transform
                           (:vertices mesh)
                           position
                           heading
                           up)]
    (cond-> mesh
      true (assoc :vertices (vec transformed-verts))
      true (assoc :creation-pose {:position position
                                  :heading heading
                                  :up up})
      material (assoc :material material))))

(defn- transform-mesh-to-turtle-upright
  "Like transform-mesh-to-turtle but rotates so the mesh extends along the
   turtle's UP axis instead of heading. Used for cyl/cone so height = UP."
  [mesh]
  (let [turtle @(turtle-ref)
        position (:position turtle)
        heading (:heading turtle)
        up (:up turtle)
        material (:material turtle)
        ;; Swap heading and up: mesh 'forward' becomes turtle's UP
        ;; heading becomes -up (pitch -90°), new up becomes heading
        rotated-heading up
        rotated-up (turtle/v* heading -1)
        transformed-verts (prims/apply-transform
                           (:vertices mesh)
                           position
                           rotated-heading
                           rotated-up)]
    (cond-> mesh
      true (assoc :vertices (vec transformed-verts))
      true (assoc :creation-pose {:position position
                                  :heading heading
                                  :up up})
      material (assoc :material material))))

;; Primitive constructors - create mesh at current turtle position
(defn ^:export pure-box
  ([size] (transform-mesh-to-turtle (prims/box-mesh size)))
  ([sx sy sz] (transform-mesh-to-turtle (prims/box-mesh sx sy sz))))

;; Resolution-aware shape and primitive constructors
;; These read resolution from turtle state when segments not explicitly provided

(defn ^:export circle-with-resolution
  "Create circular shape, using resolution from turtle state if segments not provided."
  ([radius]
   (let [circumference (* 2 Math/PI radius)
         segments (turtle/calc-circle-segments @(turtle-ref) circumference)]
     (shape/circle-shape radius segments)))
  ([radius segments]
   (shape/circle-shape radius segments)))

(defn ^:export sphere-with-resolution
  "Create sphere mesh at current turtle position, using resolution from turtle state if not provided."
  ([radius]
   (let [segments (turtle/calc-circle-segments @(turtle-ref) (* 2 Math/PI radius))
         rings (max 4 (int (/ segments 2)))]
     (transform-mesh-to-turtle (prims/sphere-mesh radius segments rings))))
  ([radius segments rings]
   (transform-mesh-to-turtle (prims/sphere-mesh radius segments rings))))

(defn ^:export cyl-with-resolution
  "Create cylinder mesh at current turtle position, height along turtle's UP axis."
  ([radius height]
   (let [segments (turtle/calc-circle-segments @(turtle-ref) (* 2 Math/PI radius))]
     (transform-mesh-to-turtle-upright (prims/cyl-mesh radius height segments))))
  ([radius height segments]
   (when-not (and (int? segments) (pos? segments))
     (throw (js/Error. (str "cyl expects (radius height) or (radius height segments). "
                            "Third arg must be an integer segment count. "
                            "For tapered cylinders, use cone or (loft (tapered (circle r) :to t) (f h))."))))
   (transform-mesh-to-turtle-upright (prims/cyl-mesh radius height segments))))

(defn ^:export cone-with-resolution
  "Create cone mesh at current turtle position, height along turtle's UP axis."
  ([r1 r2 height]
   (let [max-r (max r1 r2)
         segments (turtle/calc-circle-segments @(turtle-ref) (* 2 Math/PI max-r))]
     (transform-mesh-to-turtle-upright (prims/cone-mesh r1 r2 height segments))))
  ([r1 r2 height segments]
   (transform-mesh-to-turtle-upright (prims/cone-mesh r1 r2 height segments))))

(defn ^:export implicit-panel
  "Create a panel at the current turtle position and orientation.
   Options: :font-size :bg :fg :padding :line-height"
  [width height & {:keys [font-size bg fg padding line-height]
                   :or {font-size 3
                        bg 0x333333cc
                        fg 0xffffff
                        padding 2
                        line-height 1.4}}]
  (let [t @(turtle-ref)
        pos (:position t)
        heading (:heading t)
        up (:up t)]
    (panel/make-panel width height pos heading up
                      :font-size font-size
                      :bg bg
                      :fg fg
                      :padding padding
                      :line-height line-height)))

(defn ^:export implicit-out
  "Set the content of a registered panel. name can be a keyword or the panel itself."
  [name-or-panel text]
  (let [name (if (keyword? name-or-panel) name-or-panel (:name name-or-panel))]
    (when name
      (let [updated (registry/update-panel! name #(panel/set-content % text))]
        (when updated
          (viewport/update-panel-text name updated))))))

(defn ^:export implicit-append
  "Append text to a registered panel's content."
  [name-or-panel text]
  (let [name (if (keyword? name-or-panel) name-or-panel (:name name-or-panel))]
    (when name
      (let [updated (registry/update-panel! name #(panel/append-content % text))]
        (when updated
          (viewport/update-panel-text name updated))))))

(defn ^:export implicit-clear
  "Clear the content of a registered panel."
  [name-or-panel]
  (let [name (if (keyword? name-or-panel) name-or-panel (:name name-or-panel))]
    (when name
      (let [updated (registry/update-panel! name panel/clear-content)]
        (when updated
          (viewport/update-panel-text name updated))))))

;; Loft is now a macro - these are the impl functions
(defn ^:export implicit-stamp-loft
  ([shape transform-fn]
   (swap! (turtle-ref) turtle/stamp-loft shape transform-fn))
  ([shape transform-fn steps]
   (swap! (turtle-ref) turtle/stamp-loft shape transform-fn steps)))

(defn ^:export implicit-finalize-loft []
  (swap! (turtle-ref) turtle/finalize-loft))

(defn ^:export implicit-run-path
  "Execute a path's commands on the turtle.
   The turtle will move/turn as if the path commands were executed directly."
  [path]
  (swap! (turtle-ref) turtle/run-path path)
  (record-pen-lines!))

(defn ^:export implicit-add-mesh [mesh]
  (swap! (turtle-ref) update :meshes conj mesh)
  mesh)

(defn ^:export implicit-slice-mesh
  "Slice a mesh at the plane defined by the turtle's current position and heading.
   The heading vector is the plane normal. Returns a vector of shapes
   in the plane's local coordinates (X = turtle right, Y = turtle up).
   Shapes have :preserve-position? true for absolute coordinate rendering.
   Accepts a mesh map or a keyword (registered mesh name)."
  [mesh-or-name]
  (let [mesh (if (keyword? mesh-or-name)
               (registry/get-mesh mesh-or-name)
               mesh-or-name)
        state @(turtle-ref)
        pos (:position state)
        heading (:heading state)
        up (:up state)
        ;; Compute right as up × heading (not heading × up)
        ;; to ensure [right, up, heading] forms a right-handed basis (det=+1)
        ;; which preserves face winding when transforming for Manifold
        [hx hy hz] heading
        [ux uy uz] up
        rx (- (* uy hz) (* uz hy))
        ry (- (* uz hx) (* ux hz))
        rz (- (* ux hy) (* uy hx))
        rmag (Math/sqrt (+ (* rx rx) (* ry ry) (* rz rz)))
        right (if (pos? rmag)
                [(/ rx rmag) (/ ry rmag) (/ rz rmag)]
                [0 1 0])
        shapes (manifold/slice-at-plane mesh heading pos right up)
        ;; The slice basis uses right = up × heading (det=+1, needed for Manifold),
        ;; but stamp uses right = heading × up (opposite sign on X axis).
        ;; Mirror X and reverse point order to match the stamp coordinate frame.
        mirror (fn [pts] (mapv (fn [[x y]] [(- x) y]) (vec (rseq pts))))]
    (mapv (fn [shape]
            (cond-> (update shape :points mirror)
              (:holes shape) (update :holes (fn [hs] (mapv mirror hs)))))
          shapes)))
