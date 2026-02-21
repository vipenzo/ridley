(ns ridley.editor.implicit
  "Implicit turtle functions that mutate the shared turtle-atom.
   These functions are bound in the SCI context via base-bindings."
  (:require [ridley.editor.state :refer [turtle-atom]]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.path :as path]
            [ridley.turtle.transform :as xform]
            [ridley.geometry.primitives :as prims]
            [ridley.manifold.core :as manifold]
            [ridley.scene.registry :as registry]
            [ridley.scene.panel :as panel]
            [ridley.viewport.core :as viewport]))

(defn ^:export implicit-f [dist]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/f dist)
    ;; If attached with registry index, update the registry directly
    (when registry-idx
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-th [angle]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/th angle)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-tv [angle]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/tv angle)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-tr [angle]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/tr angle)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

;; Lateral movement (pure translation, no heading change)
(defn ^:export implicit-u [dist]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/move-up dist)
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-d [dist]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/move-down dist)
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-rt [dist]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/move-right dist)
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-lt [dist]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/move-left dist)
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-pen-up []
  (swap! turtle-atom turtle/pen-up))

(defn ^:export implicit-pen-down []
  (swap! turtle-atom turtle/pen-down))

(defn ^:export implicit-pen [mode]
  (swap! turtle-atom turtle/pen mode))

(defn ^:export implicit-reset
  ([] (swap! turtle-atom turtle/reset-pose))
  ([pos] (swap! turtle-atom turtle/reset-pose pos))
  ([pos & opts] (swap! turtle-atom #(apply turtle/reset-pose % pos opts))))

(defn ^:export implicit-joint-mode [mode]
  (swap! turtle-atom turtle/joint-mode mode))

;; Resolution (like OpenSCAD $fn/$fa/$fs)
(defn ^:export implicit-resolution [mode value]
  (swap! turtle-atom turtle/resolution mode value))

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
   (swap! turtle-atom turtle/set-color hex))
  ([name-or-mesh hex]
   (if (mesh-map? name-or-mesh)
     (update name-or-mesh :material merge {:color hex})
     (registry/update-mesh-material! (keyword name-or-mesh) {:color hex})))
  ([r g b]
   (swap! turtle-atom turtle/set-color r g b))
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
        (swap! turtle-atom #(apply turtle/set-material % (mapcat identity opts))))
      ;; Otherwise → mesh name in registry
      :else
      (let [mesh-name first-arg
            opts (apply hash-map (rest args))]
        (registry/update-mesh-material! (keyword mesh-name) opts)))))

(defn ^:export implicit-reset-material
  "Reset material to default values."
  []
  (swap! turtle-atom turtle/reset-material))

;; Stamp debug visualization
(defn ^:export implicit-stamp-debug
  "Visualize a 2D shape at current turtle pose as a semi-transparent surface.
   Accepts a shape or a path (auto-converted via path-to-shape).
   Optional :color (hex int) overrides the default orange."
  [shape-or-path & {:keys [color]}]
  (let [s (if (turtle/path? shape-or-path)
            (shape/path-to-shape shape-or-path)
            shape-or-path)]
    (swap! turtle-atom turtle/stamp-debug s :color color)))

;; Arc commands
(defn ^:export implicit-arc-h [radius angle & {:keys [steps]}]
  (swap! turtle-atom #(turtle/arc-h % radius angle :steps steps)))

(defn ^:export implicit-arc-v [radius angle & {:keys [steps]}]
  (swap! turtle-atom #(turtle/arc-v % radius angle :steps steps)))

;; Bezier commands
(defn ^:export implicit-bezier-to [target & args]
  (swap! turtle-atom #(apply turtle/bezier-to % target args)))

(defn ^:export implicit-bezier-to-anchor [anchor-name & args]
  (swap! turtle-atom #(apply turtle/bezier-to-anchor % anchor-name args)))

(defn ^:export implicit-bezier-as [p & args]
  (swap! turtle-atom #(apply turtle/bezier-as % p args)))

;; State stack
(defn ^:export implicit-push-state []
  (swap! turtle-atom turtle/push-state))

(defn ^:export implicit-pop-state []
  (swap! turtle-atom turtle/pop-state))

(defn ^:export implicit-clear-stack []
  (swap! turtle-atom turtle/clear-stack))

;; Anchors and navigation
;; mark removed — marks now exist only inside path recordings.

(defn ^:export implicit-save-anchors
  "Save current turtle anchors. Returns the saved anchors map."
  []
  (get @turtle-atom :anchors {}))

(defn ^:export implicit-restore-anchors
  "Restore turtle anchors to a previously saved state."
  [saved]
  (swap! turtle-atom assoc :anchors saved))

(defn ^:export implicit-resolve-and-merge-marks
  "Resolve marks from a path at current turtle pose and merge into anchors."
  [path]
  (let [marks (turtle/resolve-marks @turtle-atom path)]
    (swap! turtle-atom update :anchors merge marks)))

(defn ^:export implicit-goto [name]
  (swap! turtle-atom turtle/goto name))

(defn ^:export get-anchor
  "Get anchor data by name from turtle state. Returns {:position [x y z] :heading [x y z] :up [x y z]} or nil."
  [name]
  (get-in @turtle-atom [:anchors name]))

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
  (swap! turtle-atom turtle/look-at name))

(defn ^:export implicit-path-to [name]
  ;; First orient turtle toward anchor, then create path
  ;; This ensures extrusions go in the correct direction
  (swap! turtle-atom turtle/look-at name)
  (turtle/path-to @turtle-atom name))

;; Attachment commands
;; Store registry index in :attached so we can update the real mesh
(defn ^:export implicit-attach
  "Implicit attach - saves registry-index for replace-on-detach.
   With :clone flag, doesn't track registry (the clone is new)."
  ([mesh] (implicit-attach mesh nil))
  ([mesh clone-flag]
   (let [clone? (= clone-flag :clone)
         idx (when-not clone? (registry/get-mesh-index mesh))
         ;; Get the current mesh from registry (may have been modified since def)
         current-mesh (if idx (registry/get-mesh-at-index idx) mesh)]
     (swap! turtle-atom (fn [state]
                          (let [state' (turtle/attach state current-mesh :clone clone?)]
                            (if idx
                              (assoc-in state' [:attached :registry-index] idx)
                              state')))))))

(defn ^:export implicit-attach-face
  "Implicit attach-face - saves registry-index for replace-on-detach.
   With :clone flag, enables extrusion mode (f creates side faces)."
  ([mesh face-id] (implicit-attach-face mesh face-id nil))
  ([mesh face-id clone-flag]
   (let [clone? (= clone-flag :clone)
         idx (registry/get-mesh-index mesh)
         ;; Get the current mesh from registry (may have been modified since def)
         current-mesh (if idx (registry/get-mesh-at-index idx) mesh)]
     (swap! turtle-atom (fn [state]
                          (let [state' (turtle/attach-face state current-mesh face-id :clone clone?)]
                            (if idx
                              (assoc-in state' [:attached :registry-index] idx)
                              state')))))))

(defn ^:export implicit-detach []
  (swap! turtle-atom turtle/detach))

(defn ^:export implicit-inset [dist]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/inset dist)
    ;; If attached with registry index, update the registry directly
    (when registry-idx
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-scale-mesh [factor]
  (let [old-attached (:attached @turtle-atom)
        registry-idx (:registry-index old-attached)]
    (swap! turtle-atom turtle/scale factor)
    ;; If attached to mesh with registry index, update the registry
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @turtle-atom [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export unified-scale
  "Unified scale function:
   - If first arg is a shape, scales the shape (2D)
   - If no args and attached to mesh, scales the attached mesh"
  ([factor]
   ;; No shape provided - try to scale attached mesh
   (if (= :pose (get-in @turtle-atom [:attached :type]))
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
  (let [turtle @turtle-atom
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
  (let [turtle @turtle-atom
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
         segments (turtle/calc-circle-segments @turtle-atom circumference)]
     (shape/circle-shape radius segments)))
  ([radius segments]
   (shape/circle-shape radius segments)))

(defn ^:export sphere-with-resolution
  "Create sphere mesh at current turtle position, using resolution from turtle state if not provided."
  ([radius]
   (let [segments (turtle/calc-circle-segments @turtle-atom (* 2 Math/PI radius))
         rings (max 4 (int (/ segments 2)))]
     (transform-mesh-to-turtle (prims/sphere-mesh radius segments rings))))
  ([radius segments rings]
   (transform-mesh-to-turtle (prims/sphere-mesh radius segments rings))))

(defn ^:export cyl-with-resolution
  "Create cylinder mesh at current turtle position, height along turtle's UP axis."
  ([radius height]
   (let [segments (turtle/calc-circle-segments @turtle-atom (* 2 Math/PI radius))]
     (transform-mesh-to-turtle-upright (prims/cyl-mesh radius height segments))))
  ([radius height segments]
   (transform-mesh-to-turtle-upright (prims/cyl-mesh radius height segments))))

(defn ^:export cone-with-resolution
  "Create cone mesh at current turtle position, height along turtle's UP axis."
  ([r1 r2 height]
   (let [max-r (max r1 r2)
         segments (turtle/calc-circle-segments @turtle-atom (* 2 Math/PI max-r))]
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
  (let [t @turtle-atom
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
   (swap! turtle-atom turtle/stamp-loft shape transform-fn))
  ([shape transform-fn steps]
   (swap! turtle-atom turtle/stamp-loft shape transform-fn steps)))

(defn ^:export implicit-finalize-loft []
  (swap! turtle-atom turtle/finalize-loft))

(defn ^:export implicit-run-path
  "Execute a path's commands on the turtle.
   The turtle will move/turn as if the path commands were executed directly."
  [path]
  (swap! turtle-atom turtle/run-path path))

(defn ^:export implicit-add-mesh [mesh]
  (swap! turtle-atom update :meshes conj mesh)
  mesh)

(defn ^:export implicit-slice-mesh
  "Slice a mesh at the plane defined by the turtle's current position and heading.
   The heading vector is the plane normal. Returns a vector of shapes
   in the plane's local coordinates (X = turtle right, Y = turtle up).
   Shapes have :preserve-position? true for absolute coordinate rendering."
  [mesh]
  (let [state @turtle-atom
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
                [0 1 0])]
    (manifold/slice-at-plane mesh heading pos right up)))
