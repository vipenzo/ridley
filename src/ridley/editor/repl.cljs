(ns ridley.editor.repl
  "SCI-based evaluation of user code.

   Two-phase evaluation:
   1. Explicit section: Full Clojure for definitions, functions, data
   2. Implicit section: Turtle commands that mutate a global atom

   Both phases share the same SCI context, so definitions from explicit
   are available in implicit."
  (:require [sci.core :as sci]
            [clojure.string :as str]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.path :as path]
            [ridley.turtle.transform :as xform]
            [ridley.turtle.text :as text]
            [ridley.geometry.primitives :as prims]
            [ridley.geometry.operations :as ops]
            [ridley.geometry.faces :as faces]
            [ridley.manifold.core :as manifold]
            [ridley.scene.registry :as registry]
            [ridley.viewport.core :as viewport]
            [ridley.export.stl :as stl]))

;; Global turtle state for implicit mode
(defonce ^:private turtle-atom (atom nil))

(defn- reset-turtle! []
  (reset! turtle-atom (turtle/make-turtle)))

;; NOTE: attach-state and att-* functions moved to macro-defs for SCI context

;; ============================================================
;; Implicit turtle functions (mutate atom)
;; ============================================================

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

;; State stack
(defn ^:export implicit-push-state []
  (swap! turtle-atom turtle/push-state))

(defn ^:export implicit-pop-state []
  (swap! turtle-atom turtle/pop-state))

(defn ^:export implicit-clear-stack []
  (swap! turtle-atom turtle/clear-stack))

;; Anchors and navigation
(defn ^:export implicit-mark [name]
  (swap! turtle-atom turtle/mark name))

(defn ^:export implicit-goto [name]
  (swap! turtle-atom turtle/goto name))

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

(defn- unified-scale
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
   Also stores creation-pose so mesh can be re-attached later."
  [mesh]
  (let [turtle @turtle-atom
        position (:position turtle)
        heading (:heading turtle)
        up (:up turtle)
        transformed-verts (prims/apply-transform
                           (:vertices mesh)
                           position
                           heading
                           up)]
    (-> mesh
        (assoc :vertices (vec transformed-verts))
        (assoc :creation-pose {:position position
                               :heading heading
                               :up up}))))

;; Primitive constructors - create mesh at current turtle position
(defn- pure-box
  ([size] (transform-mesh-to-turtle (prims/box-mesh size)))
  ([sx sy sz] (transform-mesh-to-turtle (prims/box-mesh sx sy sz))))

;; Resolution-aware shape and primitive constructors
;; These read resolution from turtle state when segments not explicitly provided

(defn- circle-with-resolution
  "Create circular shape, using resolution from turtle state if segments not provided."
  ([radius]
   (let [circumference (* 2 Math/PI radius)
         segments (turtle/calc-circle-segments @turtle-atom circumference)]
     (shape/circle-shape radius segments)))
  ([radius segments]
   (shape/circle-shape radius segments)))

(defn- sphere-with-resolution
  "Create sphere mesh at current turtle position, using resolution from turtle state if not provided."
  ([radius]
   (let [segments (turtle/calc-circle-segments @turtle-atom (* 2 Math/PI radius))
         rings (max 4 (int (/ segments 2)))]
     (transform-mesh-to-turtle (prims/sphere-mesh radius segments rings))))
  ([radius segments rings]
   (transform-mesh-to-turtle (prims/sphere-mesh radius segments rings))))

(defn- cyl-with-resolution
  "Create cylinder mesh at current turtle position, using resolution from turtle state if not provided."
  ([radius height]
   (let [segments (turtle/calc-circle-segments @turtle-atom (* 2 Math/PI radius))]
     (transform-mesh-to-turtle (prims/cyl-mesh radius height segments))))
  ([radius height segments]
   (transform-mesh-to-turtle (prims/cyl-mesh radius height segments))))

(defn- cone-with-resolution
  "Create cone mesh at current turtle position, using resolution from turtle state if not provided."
  ([r1 r2 height]
   (let [max-r (max r1 r2)
         segments (turtle/calc-circle-segments @turtle-atom (* 2 Math/PI max-r))]
     (transform-mesh-to-turtle (prims/cone-mesh r1 r2 height segments))))
  ([r1 r2 height segments]
   (transform-mesh-to-turtle (prims/cone-mesh r1 r2 height segments))))

(defn- get-turtle []
  @turtle-atom)

(defn get-turtle-pose
  "Get current turtle pose for indicator display.
   Returns {:position [x y z] :heading [x y z] :up [x y z]} or nil."
  []
  (when-let [t @turtle-atom]
    {:position (:position t)
     :heading (:heading t)
     :up (:up t)}))

(defn- last-mesh []
  (last (:meshes @turtle-atom)))

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

(defn ^:export implicit-extrude-closed-path
  "Extrude-closed function - creates closed mesh without side effects.
   Starts from current turtle position/orientation."
  [shape-or-shapes path]
  ;; Handle both single shape and vector of shapes (from text-shape)
  (let [shapes (if (vector? shape-or-shapes) shape-or-shapes [shape-or-shapes])
        ;; Start from current turtle position/orientation
        ;; Also copy joint-mode and resolution settings
        current-turtle @turtle-atom
        initial-state (if current-turtle
                        (-> (turtle/make-turtle)
                            (assoc :position (:position current-turtle))
                            (assoc :heading (:heading current-turtle))
                            (assoc :up (:up current-turtle))
                            (assoc :joint-mode (:joint-mode current-turtle))
                            (assoc :resolution (:resolution current-turtle)))
                        (turtle/make-turtle))
        ;; Extrude each shape, collecting results
        results (reduce
                 (fn [acc shape]
                   (let [state (turtle/extrude-closed-from-path initial-state shape path)
                         mesh (last (:meshes state))]
                     (if mesh
                       (conj acc mesh)
                       acc)))
                 []
                 shapes)]
    ;; Return single mesh or vector of meshes
    (if (= 1 (count results))
      (first results)
      results)))

(defn ^:export pure-extrude-path
  "Pure extrude function - creates mesh without side effects.
   Starts from current turtle position/orientation."
  [shape-or-shapes path]
  ;; Handle both single shape and vector of shapes (from text-shape)
  (let [shapes (if (vector? shape-or-shapes) shape-or-shapes [shape-or-shapes])
        ;; Start from current turtle position/orientation (not origin)
        ;; Also copy joint-mode and resolution settings
        current-turtle @turtle-atom
        initial-state (if current-turtle
                        (-> (turtle/make-turtle)
                            (assoc :position (:position current-turtle))
                            (assoc :heading (:heading current-turtle))
                            (assoc :up (:up current-turtle))
                            (assoc :joint-mode (:joint-mode current-turtle))
                            (assoc :resolution (:resolution current-turtle)))
                        (turtle/make-turtle))
        ;; Extrude each shape, collecting results
        results (reduce
                 (fn [acc shape]
                   (let [state (turtle/extrude-from-path initial-state shape path)
                         mesh (last (:meshes state))]
                     (if mesh
                       (conj acc mesh)
                       acc)))
                 []
                 shapes)]
    ;; Return single mesh or vector of meshes
    (if (= 1 (count results))
      (first results)
      results)))

(defn ^:export pure-loft-path
  "Pure loft function - creates mesh without side effects.
   Starts from current turtle position/orientation.
   transform-fn: (fn [shape t]) where t goes from 0 to 1
   steps: number of intermediate steps (default 16)"
  ([shape transform-fn path] (pure-loft-path shape transform-fn path 16))
  ([shape transform-fn path steps]
   (let [;; Start from current turtle position/orientation
         ;; Also copy joint-mode and resolution settings
         current-turtle @turtle-atom
         initial-state (if current-turtle
                         (-> (turtle/make-turtle)
                             (assoc :position (:position current-turtle))
                             (assoc :heading (:heading current-turtle))
                             (assoc :up (:up current-turtle))
                             (assoc :joint-mode (:joint-mode current-turtle))
                             (assoc :resolution (:resolution current-turtle)))
                         (turtle/make-turtle))
         ;; Start loft mode on local state
         state-with-loft (turtle/stamp-loft initial-state shape transform-fn steps)
         ;; Run path commands on local state (this will track orientations)
         final-state (turtle/run-path state-with-loft path)
         ;; Finalize loft to build mesh
         result-state (turtle/finalize-loft final-state)
         ;; Get the mesh
         mesh (last (:meshes result-state))]
     mesh)))

;; Legacy version for backwards compatibility (modifies global state)
(defn ^:export implicit-extrude-path [shape-or-shapes path]
  ;; Handle both single shape and vector of shapes (from text-shape)
  (let [shapes (if (vector? shape-or-shapes) shape-or-shapes [shape-or-shapes])
        start-pos (:position @turtle-atom)]
    (doseq [shape shapes]
      ;; Reset to start position for each shape
      (swap! turtle-atom assoc :position start-pos)
      (swap! turtle-atom turtle/extrude-from-path shape path))
    ;; Return last mesh (or all meshes for multiple shapes)
    (if (= 1 (count shapes))
      (last (:meshes @turtle-atom))
      (vec (take-last (count shapes) (:meshes @turtle-atom))))))

;; ============================================================
;; Text extrusion
;; ============================================================

(defn ^:export transform-2d-point-to-3d
  "Transform a 2D point [x y] to 3D using turtle orientation.
   x -> along heading (text reading direction)
   y -> along right vector (perpendicular in text plane)
   The shape plane is perpendicular to 'up' (extrusion direction)."
  [[x y] position heading up]
  (let [right (turtle/cross heading up)]
    (turtle/v+ position
               (turtle/v+ (turtle/v* heading x)
                          (turtle/v* right y)))))

(defn ^:export contour-signed-area
  "Calculate signed area of a 2D contour using shoelace formula.
   Positive = counter-clockwise (outer), negative = clockwise (hole)."
  [contour]
  (let [n (count contour)]
    (when (> n 2)
      (/ (reduce + (for [i (range n)]
                     (let [[x1 y1] (nth contour i)
                           [x2 y2] (nth contour (mod (inc i) n))]
                       (- (* x1 y2) (* x2 y1)))))
         2.0))))

(defn ^:export build-extruded-contour-mesh
  "Build a mesh from extruding a single 2D contour along a direction.
   Returns {:vertices [...] :faces [...]}.

   reverse-winding? should be true for holes to ensure outward-facing normals
   point into the hole (which will be subtracted)."
  [contour-2d position heading up depth & {:keys [reverse-winding?] :or {reverse-winding? false}}]
  (let [;; If reverse-winding?, reverse the contour order
        contour (if reverse-winding? (vec (reverse contour-2d)) contour-2d)
        n (count contour)
        ;; Transform 2D contour to 3D at base position
        base-ring (mapv #(transform-2d-point-to-3d % position heading up) contour)
        ;; Create top ring by moving along 'up' direction
        top-ring (mapv #(turtle/v+ % (turtle/v* up depth)) base-ring)
        ;; Vertices: base ring then top ring
        vertices (vec (concat base-ring top-ring))
        ;; Side faces (quads as 2 triangles each)
        side-faces (vec (for [i (range n)]
                          (let [i0 i
                                i1 (mod (inc i) n)
                                i2 (+ n (mod (inc i) n))
                                i3 (+ n i)]
                            ;; Two triangles for each quad
                            [[i0 i1 i2] [i0 i2 i3]])))
        side-faces-flat (vec (mapcat identity side-faces))
        ;; Cap faces (simple fan triangulation)
        ;; Bottom cap (reversed winding for outward normal)
        bottom-faces (vec (for [i (range 1 (dec n))]
                            [0 (inc i) i]))
        ;; Top cap
        top-faces (vec (for [i (range 1 (dec n))]
                         [(+ n 0) (+ n i) (+ n (inc i))]))
        all-faces (vec (concat side-faces-flat bottom-faces top-faces))]
    {:type :mesh
     :vertices vertices
     :faces all-faces}))

(defn ^:export classify-glyph-contours
  "Classify contours into outer boundary and holes based on signed area.
   Returns {:outer contour :holes [contours]}
   The outer contour has the largest absolute area."
  [contours]
  (when (seq contours)
    (let [with-areas (map (fn [c] {:contour c :area (contour-signed-area c)}) contours)
          ;; Sort by absolute area descending - largest is outer
          sorted (sort-by #(- (Math/abs (or (:area %) 0))) with-areas)
          outer-entry (first sorted)
          rest-entries (rest sorted)]
      {:outer (:contour outer-entry)
       :holes (vec (map :contour rest-entries))})))

(defn ^:export transform-mesh-to-turtle-orientation
  "Transform a mesh from XY plane (Z up) to turtle orientation.
   Manifold's extrude creates mesh in XY plane extruding along +Z.
   We need to rotate it so the base is perpendicular to turtle's up,
   and the text flows along turtle's heading.

   Default orientation (heading=[1,0,0], up=[0,0,1]):
   - Text flows along +X (heading)
   - Letters' top points toward +Y
   - Extrusion goes toward +Z (up)"
  [mesh position heading up]
  (let [;; Manifold extrudes along +Z, we want along 'up'
        ;; Manifold's X axis should map to our 'heading' (text reading direction)
        ;; Manifold's Y axis should map to -right so letter tops point correctly
        ;; (With default turtle, -right = [0,-1,0] * -1 = toward +Y for letter tops)
        right (turtle/cross heading up)
        vertices (:vertices mesh)
        faces (:faces mesh)
        ;; Transform each vertex: [x, y, z] -> position + x*heading - y*right + z*up
        ;; Note the -y*right to flip the Y axis orientation
        transformed-verts
        (mapv (fn [[x y z]]
                (turtle/v+ position
                           (turtle/v+ (turtle/v* heading x)
                                      (turtle/v+ (turtle/v* right (- y))
                                                 (turtle/v* up z)))))
              vertices)]
    (-> mesh
        (assoc :vertices transformed-verts)
        (assoc :faces faces))))

(defn ^:export implicit-extrude-text
  "Extrude text along the turtle's heading direction.
   Text flows along heading, extrudes along up.
   Uses Manifold's CrossSection for proper handling of holes.

   Options:
   - :size - font size (default 10)
   - :depth - extrusion depth (default 5)
   - :font - font object (optional)

   Returns vector of meshes, one per character."
  [txt & {:keys [size depth font] :or {size 10 depth 5}}]
  (let [glyph-data (text/text-glyph-data txt :size size :font font)
        start-pos (:position @turtle-atom)
        heading (:heading @turtle-atom)
        up (:up @turtle-atom)
        meshes (atom [])]
    (doseq [{:keys [contours x-offset]} glyph-data]
      (when (seq contours)
        (let [{:keys [outer holes]} (classify-glyph-contours contours)]
          (when (and outer (> (count outer) 2))
            ;; Position for this glyph: start + offset along heading
            (let [glyph-pos (turtle/v+ start-pos (turtle/v* heading x-offset))
                  ;; Prepare contours for CrossSection:
                  ;; - Outer must be counter-clockwise (positive area)
                  ;; - Holes must be clockwise (negative area)
                  outer-area (contour-signed-area outer)
                  prepared-outer (if (neg? outer-area) (vec (reverse outer)) outer)
                  prepared-holes (mapv (fn [hole]
                                         (let [hole-area (contour-signed-area hole)]
                                           ;; Holes should be clockwise (negative area)
                                           (if (pos? hole-area)
                                             (vec (reverse hole))
                                             hole)))
                                       holes)
                  ;; Combine outer + holes into single contours vector
                  all-contours (into [prepared-outer] prepared-holes)
                  ;; Use Manifold's CrossSection for proper extrusion with holes
                  raw-mesh (manifold/extrude-cross-section all-contours depth)]
              (when raw-mesh
                (let [;; Transform mesh from XY/Z orientation to turtle orientation
                      transformed-mesh (transform-mesh-to-turtle-orientation raw-mesh glyph-pos heading up)
                      mesh-with-pose (assoc transformed-mesh :creation-pose
                                            {:position glyph-pos
                                             :heading heading
                                             :up up})]
                  (swap! meshes conj mesh-with-pose)
                  (swap! turtle-atom update :meshes conj mesh-with-pose))))))))
    @meshes))

(defn ^:export implicit-text-on-path
  "Place text along a path, extruding each glyph perpendicular to curve.
   Each letter is positioned at its x-offset distance along the path,
   oriented tangent to the curve direction.

   Options:
   - :size - font size (default 10)
   - :depth - extrusion depth (default 5)
   - :font - custom font (optional)
   - :overflow - :truncate (default), :wrap, or :scale
   - :align - :start (default), :center, or :end
   - :spacing - extra letter spacing (default 0)

   Returns vector of meshes, one per glyph."
  [txt path & {:keys [size depth font overflow align spacing]
               :or {size 10 depth 5 overflow :truncate align :start spacing 0}}]
  (let [glyph-data (text/text-glyph-data txt :size size :font font)
        path-len (turtle/path-total-length path)
        ;; Calculate total text width including spacing
        text-len (if (seq glyph-data)
                   (+ (reduce + (map :advance-width glyph-data))
                      (* spacing (max 0 (dec (count glyph-data)))))
                   0)
        ;; Calculate start offset based on alignment
        start-offset (case align
                       :center (/ (- path-len text-len) 2)
                       :end (- path-len text-len)
                       0)
        ;; Scale factor for :scale overflow mode
        scale-factor (if (and (= overflow :scale) (pos? text-len))
                       (/ path-len text-len)
                       1.0)
        ;; Get turtle's starting orientation for path sampling
        turtle-pos (:position @turtle-atom)
        turtle-heading (:heading @turtle-atom)
        turtle-up (:up @turtle-atom)
        meshes (atom [])]
    ;; x-offset in glyph-data is already cumulative, so we use it directly
    ;; We only need to add start-offset (for alignment) and apply scale-factor
    (doseq [[glyph-idx {:keys [contours x-offset advance-width]}] (map-indexed vector glyph-data)]
      (let [;; Distance along path for glyph CENTER (not start)
            ;; This gives better orientation on curves
            ;; x-offset is cumulative position of glyph start
            extra-spacing (* spacing glyph-idx)
            glyph-center-dist (+ start-offset
                                 (* (+ x-offset (/ advance-width 2)) scale-factor)
                                 extra-spacing)
            ;; Sample the path at the CENTER of the glyph for orientation
            sample (turtle/sample-path-at-distance path glyph-center-dist
                     :wrap? (= overflow :wrap)
                     :start-pos turtle-pos
                     :start-heading turtle-heading
                     :start-up turtle-up)]
        (when (and sample (seq contours))
          (let [{:keys [position heading up]} sample
                {:keys [outer holes]} (classify-glyph-contours contours)
                ;; Position is at center, but glyph origin is at x-offset=0
                ;; So we need to shift back by half the advance-width
                half-width (/ (* advance-width scale-factor) 2)
                glyph-position (turtle/v- position (turtle/v* heading half-width))]
            (when (and outer (> (count outer) 2))
              ;; Prepare contours for CrossSection (same as extrude-text)
              (let [outer-area (contour-signed-area outer)
                    prepared-outer (if (neg? outer-area) (vec (reverse outer)) outer)
                    prepared-holes (mapv (fn [hole]
                                           (let [a (contour-signed-area hole)]
                                             (if (pos? a) (vec (reverse hole)) hole)))
                                         holes)
                    all-contours (into [prepared-outer] prepared-holes)
                    raw-mesh (manifold/extrude-cross-section all-contours depth)]
                (when raw-mesh
                  (let [transformed (transform-mesh-to-turtle-orientation raw-mesh glyph-position heading up)
                        with-pose (assoc transformed :creation-pose
                                         {:position glyph-position :heading heading :up up})]
                    (swap! meshes conj with-pose)
                    (swap! turtle-atom update :meshes conj with-pose)))))))))
    @meshes))

;; ============================================================
;; Shared SCI context
;; ============================================================

(def ^:private base-bindings
  "Bindings available in both explicit and implicit sections."
  {;; Implicit turtle commands (mutate atom)
   'f            implicit-f
   'th           implicit-th
   'tv           implicit-tv
   'tr           implicit-tr
   'pen-impl     implicit-pen      ; Used by pen macro
   'stamp-impl   (fn [shape] (swap! turtle-atom turtle/stamp shape))
   'stamp-closed-impl (fn [shape] (swap! turtle-atom turtle/stamp-closed shape))
   'finalize-sweep-impl (fn [] (swap! turtle-atom turtle/finalize-sweep))
   'finalize-sweep-closed-impl (fn [] (swap! turtle-atom turtle/finalize-sweep-closed))
   'pen-up       implicit-pen-up
   'pen-down     implicit-pen-down
   'reset        implicit-reset
   'joint-mode   implicit-joint-mode
   ;; Resolution (like OpenSCAD $fn/$fa/$fs)
   'resolution   implicit-resolution
   ;; Arc commands
   'arc-h        implicit-arc-h
   'arc-v        implicit-arc-v
   ;; Bezier commands
   'bezier-to         implicit-bezier-to
   'bezier-to-anchor  implicit-bezier-to-anchor
   ;; State stack
   'push-state   implicit-push-state
   'pop-state    implicit-pop-state
   'clear-stack  implicit-clear-stack
   ;; Anchors and navigation
   'mark         implicit-mark
   'goto         implicit-goto
   'look-at      implicit-look-at
   'path-to      implicit-path-to
   ;; Attachment commands (functional versions defined in macro-defs)
   ;; NOTE: Legacy implicit-attach and implicit-attach-face removed
   ;; Use the functional macro: (attach-face mesh :top (f 20))
   'attached?    (fn [] (turtle/attached? @turtle-atom))
   ;; NOTE: 'detach' removed - implicit at end of attach/attach-face macro
   'inset        implicit-inset
   ;; 3D primitives - return mesh data at origin (resolution-aware)
   'box          pure-box
   'sphere       sphere-with-resolution
   'cyl          cyl-with-resolution
   'cone         cone-with-resolution
   ;; NOTE: 'stamp' and 'make' removed - primitives are now created at turtle position
   ;; Use (register :name mesh) to make mesh visible in scene
   ;; Shape constructors (return shape data, resolution-aware)
   'circle       circle-with-resolution
   'rect         shape/rect-shape
   'polygon      shape/polygon-shape
   'star         shape/star-shape
   ;; Text shapes
   'text-shape   text/text-shape
   'text-shapes  text/text-shapes
   'char-shape   text/char-shape
   'load-font!   text/load-font!
   'font-loaded? text/font-loaded?
   'extrude-text implicit-extrude-text
   'text-on-path implicit-text-on-path
   ;; Pure turtle functions (for explicit threading)
   'turtle       turtle/make-turtle
   'turtle-f     turtle/f
   'turtle-th    turtle/th
   'turtle-tv    turtle/tv
   'turtle-tr    turtle/tr
   'turtle-pen      turtle/pen
   'turtle-pen-up   turtle/pen-up
   'turtle-pen-down turtle/pen-down
   'turtle-box      prims/box
   'turtle-sphere   prims/sphere
   'turtle-cyl      prims/cyl
   'turtle-cone     prims/cone
   ;; Pure attach functions for functional macros
   ;; (turtle-f, turtle-th, turtle-tv, turtle-tr already defined above)
   'turtle-attach       turtle/attach
   'turtle-attach-face  turtle/attach-face
   'turtle-attach-face-extrude turtle/attach-face-extrude
   'turtle-attach-move  turtle/attach-move
   'turtle-attach-clone turtle/attach-clone
   'turtle-inset        turtle/inset
   'turtle-scale        turtle/scale
   ;; NOTE: attach-state and att-* functions are defined in macro-defs
   ;; Path/shape utilities
   'path->data   path/path-from-state
   'make-shape   shape/make-shape
   ;; Generative operations (legacy ops namespace)
   'ops-extrude  ops/extrude
   'extrude-z    ops/extrude-z
   'extrude-y    ops/extrude-y
   'revolve      ops/revolve
   'ops-sweep    ops/sweep
   'ops-loft     ops/loft
   ;; Loft impl functions (used by loft macro)
   'stamp-loft-impl     implicit-stamp-loft
   'finalize-loft-impl  implicit-finalize-loft
   ;; Shape transformation functions (scale also works on attached mesh)
   'scale        unified-scale
   'rotate-shape xform/rotate
   'translate    xform/translate
   'morph        xform/morph
   'resample     xform/resample
   ;; Face operations
   'list-faces   faces/list-faces
   'get-face     faces/get-face
   'face-info    faces/face-info
   'face-ids     faces/face-ids
   ;; Face highlighting
   'flash-face      viewport/flash-face
   'highlight-face  viewport/highlight-face
   'clear-highlights viewport/clear-highlights
   'fit-camera      viewport/fit-camera
   ;; Access current turtle state
   'get-turtle   get-turtle
   'last-mesh    last-mesh
   ;; Path recording functions
   'make-recorder       turtle/make-recorder
   'rec-f               turtle/rec-f
   'rec-th              turtle/rec-th
   'rec-tv              turtle/rec-tv
   'rec-tr              turtle/rec-tr
   'rec-set-heading     turtle/rec-set-heading
   'path-from-recorder  turtle/path-from-recorder
   ;; Shape recording functions (2D turtle)
   'shape-rec-f         shape/rec-f
   'shape-rec-th        shape/rec-th
   'shape-from-recording shape/shape-from-recording
   'recording-turtle    shape/recording-turtle
   'run-path-impl       turtle/run-path
   'follow-path         implicit-run-path
   'path?               turtle/path?
   'extrude-closed-path-impl implicit-extrude-closed-path
   'extrude-path-impl        implicit-extrude-path
   'pure-extrude-path        pure-extrude-path  ; Pure version (no side effects)
   'pure-loft-path           pure-loft-path     ; Pure loft version (no side effects)
   ;; Sweep between two shapes
   'stamp-shape-at      turtle/stamp-shape-at
   'sweep-two-shapes    turtle/sweep-two-shapes
   'add-mesh-impl       implicit-add-mesh
   ;; Manifold operations
   'manifold?           manifold/manifold?
   'mesh-status         manifold/get-mesh-status
   'mesh-union          manifold/union
   'mesh-difference     manifold/difference
   'mesh-intersection   manifold/intersection
   'mesh-hull           manifold/hull
   ;; Scene registry
   'register-mesh!      registry/register-mesh!
   'show-mesh!          registry/show-mesh!
   'hide-mesh!          registry/hide-mesh!
   'show-mesh-ref!      registry/show-mesh-ref!
   'hide-mesh-ref!      registry/hide-mesh-ref!
   'show-all!           registry/show-all!
   'hide-all!           registry/hide-all!
   'show-only-registered! registry/show-only-registered!
   'visible-names       registry/visible-names
   'visible-meshes      registry/visible-meshes
   'registered-names    registry/registered-names
   'get-mesh            registry/get-mesh
   'refresh-viewport!   registry/refresh-viewport!
   'all-meshes-info     registry/all-meshes-info
   'anonymous-meshes    registry/anonymous-meshes
   'anonymous-count     registry/anonymous-count
   ;; Viewport visibility controls
   'show-lines          (fn [] (viewport/set-lines-visible true))
   'hide-lines          (fn [] (viewport/set-lines-visible false))
   'lines-visible?      viewport/lines-visible?
   ;; Path registry
   'register-path!      registry/register-path!
   'show-path!          registry/show-path!
   'hide-path!          registry/hide-path!
   ;; STL export
   'save-stl            stl/download-stl
   ;; Math functions for SCI context (used by arc/bezier recording)
   'PI                  js/Math.PI
   'abs                 js/Math.abs
   'sin                 js/Math.sin
   'cos                 js/Math.cos
   'sqrt                js/Math.sqrt
   'ceil                js/Math.ceil
   'floor               js/Math.floor
   'round               js/Math.round
   'pow                 js/Math.pow
   'atan2               js/Math.atan2
   ;; Debug logging (outputs to browser console)
   'log                 (fn [& args] (apply js/console.log (map clj->js args)))})

;; Macro definitions for SCI context
(def ^:private macro-defs
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

   ;; Recording version of bezier-to
   ;; Decomposes bezier into f movements with th/tv rotations to follow the curve
   (defn- rec-bezier-to* [target & args]
     (let [grouped (group-by vector? args)
           control-points (get grouped true)
           options (get grouped false)
           steps (get (apply hash-map (flatten options)) :steps)
           state @path-recorder
           p0 (:position state)
           p3 (vec target)
           dx0 (- (nth p3 0) (nth p0 0))
           dy0 (- (nth p3 1) (nth p0 1))
           dz0 (- (nth p3 2) (nth p0 2))
           approx-length (sqrt (+ (* dx0 dx0) (* dy0 dy0) (* dz0 dz0)))]
       (when (> approx-length 0.001)
         (let [res-mode (get-in state [:resolution :mode] :n)
               res-value (get-in state [:resolution :value] 16)
               actual-steps (or steps
                                (case res-mode
                                  :n res-value
                                  :a res-value
                                  :s (max 1 (int (ceil (/ approx-length res-value))))))
               n-controls (count control-points)
               ;; Compute control points
               [c1 c2] (cond
                         (= n-controls 2) control-points
                         (= n-controls 1) [(first control-points) (first control-points)]
                         :else ;; Auto control points
                         (let [heading (:heading state)]
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
                     ;; Set heading directly to segment direction with propagated up
                     (rec-set-heading* dir new-up)
                     ;; Move forward
                     (rec-f* dist)
                     ;; Continue with next segment, propagating the up vector
                     (recur (rest remaining-segments) new-up))
                   ;; Skip zero-length segment, keep current up
                   (recur (rest remaining-segments) current-up)))))))))

   ;; path: record turtle movements for later replay
   ;; (def p (path (f 20) (th 90) (f 20))) - record a path
   ;; (def p (path (dotimes [_ 4] (f 20) (th 90)))) - with arbitrary code
   ;; Returns a path object that can be used in extrude/loft
   (defmacro path [& body]
     `(do
        (reset! path-recorder (make-recorder))
        (let [~'f rec-f*
              ~'th rec-th*
              ~'tv rec-tv*
              ~'tr rec-tr*
              ~'arc-h rec-arc-h*
              ~'arc-v rec-arc-v*
              ~'bezier-to rec-bezier-to*]
          ~@body)
        (path-from-recorder @path-recorder)))

   ;; shape: create a 2D shape from turtle movements
   ;; (def tri (shape (f 4) (th 120) (f 4) (th 120) (f 4))) - triangle
   ;; (def tri (shape (f 4) (th 120) (f 4))) - same, auto-closes
   ;; Uses a 2D turtle starting at origin, facing +X
   ;; Only f and th are allowed (2D plane)
   ;; Returns a shape that can be used in extrude/loft
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
           (and (list? arg) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to} (first arg)))
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
   (defmacro extrude-closed [shape path-expr]
     (cond
       ;; Symbol - use directly (should be a path)
       (symbol? path-expr)
       `(extrude-closed-path-impl ~shape ~path-expr)

       ;; List starting with path - use directly
       (and (list? path-expr) (= 'path (first path-expr)))
       `(extrude-closed-path-impl ~shape ~path-expr)

       ;; List starting with turtle movement - wrap in path
       ;; This avoids evaluating commands directly which would modify turtle-atom
       (and (list? path-expr) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to} (first path-expr)))
       `(extrude-closed-path-impl ~shape (path ~path-expr))

       ;; Other list - check at runtime if it's already a path
       :else
       `(let [result# ~path-expr]
          (if (path? result#)
            (extrude-closed-path-impl ~shape result#)
            (extrude-closed-path-impl ~shape (path ~path-expr))))))

   ;; loft: like extrude but with shape transformation based on progress
   ;; PURE: returns mesh without side effects (use register to make visible)
   ;; (loft (circle 20) #(scale %1 (- 1 %2)) (f 30)) - cone
   ;; (loft (circle 20) #(scale %1 (- 1 %2)) my-path) - cone along path
   ;; (loft (rect 20 10) #(rotate-shape %1 (* %2 90)) (f 30)) - twist
   ;; Transform fn receives (shape t) where t goes from 0 to 1
   ;; Default: 16 steps
   ;; Returns the created mesh (can be bound with def)
   (defmacro loft [shape transform-fn & movements]
     (if (= 1 (count movements))
       (let [arg (first movements)]
         (cond
           ;; Symbol - might be a pre-defined path, check at runtime
           (symbol? arg)
           `(let [arg# ~arg]
              (if (path? arg#)
                (pure-loft-path ~shape ~transform-fn arg#)
                (pure-loft-path ~shape ~transform-fn (path ~arg))))

           ;; List starting with path - use directly
           (and (list? arg) (= 'path (first arg)))
           `(pure-loft-path ~shape ~transform-fn ~arg)

           ;; List starting with turtle movement - wrap in path
           (and (list? arg) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to} (first arg)))
           `(pure-loft-path ~shape ~transform-fn (path ~arg))

           ;; Any other expression - check at runtime if it's a path
           :else
           `(let [result# ~arg]
              (if (path? result#)
                (pure-loft-path ~shape ~transform-fn result#)
                (pure-loft-path ~shape ~transform-fn (path ~arg))))))
       ;; Multiple movements - wrap in path macro
       `(pure-loft-path ~shape ~transform-fn (path ~@movements))))

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
           (and (list? arg) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to} (first arg)))
           `(pure-loft-path ~shape ~transform-fn (path ~arg) ~steps)

           ;; Any other expression - check at runtime if it's a path
           :else
           `(let [result# ~arg]
              (if (path? result#)
                (pure-loft-path ~shape ~transform-fn result# ~steps)
                (pure-loft-path ~shape ~transform-fn (path ~arg) ~steps)))))
       ;; Multiple movements - wrap in path macro
       `(pure-loft-path ~shape ~transform-fn (path ~@movements) ~steps)))

   ;; sweep: create mesh between two shapes
   ;; (sweep (circle 5) (do (f 10) (th 90) (circle 5)))
   ;; First shape is stamped at current turtle position
   ;; Body executes (can move turtle), last expression must return a shape
   ;; Second shape is stamped at final turtle position
   ;; Returns mesh connecting the two shapes
   (defmacro sweep [shape1 body]
     `(let [;; Stamp first shape at current position
            ring1# (stamp-shape-at (get-turtle) ~shape1)
            ;; Execute body (moves turtle, returns second shape)
            shape2# ~body
            ;; Stamp second shape at new position
            ring2# (stamp-shape-at (get-turtle) shape2#)
            ;; Create mesh between the two rings
            mesh# (sweep-two-shapes ring1# ring2#)]
        ;; Add mesh to turtle state and return it
        (add-mesh-impl mesh#)))

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
              ~'inset att-inset*
              ~'scale att-scale*]
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
              ~'inset att-inset*
              ~'scale att-scale*]
          ~@body)
        ;; Return modified mesh
        (or (get-in @attach-state [:attached :mesh]) m#)))

   ;; attach: transform mesh in place (modifies original)
   ;; (attach mesh & body) => transformed mesh
   ;; Attaches to mesh's creation pose and applies transformations.
   (defmacro attach [mesh & body]
     `(let [m# ~mesh]
        ;; Initialize local state with turtle attached to mesh pose (no clone)
        (reset! attach-state
                (-> (turtle)
                    (turtle-attach-move m#)))
        ;; Rebind operations to local versions
        (let [~'f att-f*
              ~'th att-th*
              ~'tv att-tv*
              ~'tr att-tr*]
          ~@body)
        ;; Return modified mesh
        (or (get-in @attach-state [:attached :mesh]) m#)))

   ;; clone: create transformed copy of mesh (original unchanged)
   ;; (clone mesh & body) => new transformed mesh
   ;; Creates a copy, attaches to its creation pose, applies transformations.
   (defmacro clone [mesh & body]
     `(do
        ;; Initialize local state with turtle attached to cloned mesh
        (reset! attach-state
                (-> (turtle)
                    (turtle-attach-clone ~mesh)))
        ;; Rebind operations to local versions
        (let [~'f att-f*
              ~'th att-th*
              ~'tv att-tv*
              ~'tr att-tr*]
          ~@body)
        ;; Return modified mesh
        (or (get-in @attach-state [:attached :mesh]) ~mesh)))

   ;; register: define a symbol, add to registry, AND show it
   ;; Works with both meshes and paths:
   ;; (register torus (extrude ...))  ; registers a mesh
   ;; (register line (path ...))      ; registers a path (shown as polyline)
   ;; On subsequent evals, updates the value but preserves visibility state
   (defmacro register [name expr]
     `(let [value# ~expr
            name-kw# ~(keyword name)]
        (def ~name value#)
        (cond
          ;; It's a mesh (has :vertices)
          (and (map? value#) (:vertices value#))
          (let [already-registered# (contains? (set (registered-names)) name-kw#)]
            (register-mesh! name-kw# value#)
            (when-not already-registered#
              (show-mesh! name-kw#)))

          ;; It's a path (has :type :path)
          (and (map? value#) (= :path (:type value#)))
          (do
            (register-path! name-kw# value#)
            (show-path! name-kw#)))
        ;; Refresh viewport and return value
        (refresh-viewport! false)
        value#))

   ;; Convenience functions that work with names OR mesh references
   ;; (hide :torus) - hide by registered name (keyword)
   ;; (hide torus)  - hide by mesh reference (def'd variable)
   (defn show [name-or-mesh]
     (if (or (keyword? name-or-mesh) (string? name-or-mesh) (symbol? name-or-mesh))
       ;; It's a name - convert to keyword and look up
       (show-mesh! (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh)))
       ;; It's a mesh reference - look up by identity
       (show-mesh-ref! name-or-mesh))
     (refresh-viewport! false))  ; Don't reset camera

   (defn hide [name-or-mesh]
     (if (or (keyword? name-or-mesh) (string? name-or-mesh) (symbol? name-or-mesh))
       ;; It's a name - convert to keyword and look up
       (hide-mesh! (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh)))
       ;; It's a mesh reference - look up by identity
       (hide-mesh-ref! name-or-mesh))
     (refresh-viewport! false)  ; Don't reset camera
     nil)

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

   ;; Get info/details about a mesh
   ;; (info :torus) - by registered name (keyword)
   ;; (info torus)  - by mesh reference (def'd variable)
   (defn info [name-or-mesh]
     (if (or (keyword? name-or-mesh) (string? name-or-mesh) (symbol? name-or-mesh))
       ;; It's a name - look up by keyword
       (let [kw (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh))
             mesh (get-mesh kw)
             vis (contains? (set (visible-names)) kw)]
         (when mesh
           {:name kw
            :visible vis
            :vertices (count (:vertices mesh))
            :faces (count (:faces mesh))}))
       ;; It's a mesh reference - show info directly
       (when (and name-or-mesh (:vertices name-or-mesh))
         {:name nil
          :vertices (count (:vertices name-or-mesh))
          :faces (count (:faces name-or-mesh))})))

   ;; Get the raw mesh data for an object
   ;; (mesh :torus) - by registered name
   ;; (mesh torus)  - returns mesh itself (identity)
   (defn mesh [name-or-mesh]
     (if (or (keyword? name-or-mesh) (string? name-or-mesh) (symbol? name-or-mesh))
       (get-mesh (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh)))
       name-or-mesh))

   ;; Helper to check if something is a mesh (has :vertices and :faces)
   (defn- mesh? [x]
     (and (map? x) (:vertices x) (:faces x)))

   ;; Helper to resolve name-or-mesh to actual mesh
   (defn- resolve-mesh [name-or-mesh]
     (if (mesh? name-or-mesh)
       name-or-mesh
       (get-mesh (if (keyword? name-or-mesh) name-or-mesh (keyword name-or-mesh)))))

   ;; Export mesh(es) to STL file
   ;; (export :torus) - by registered name
   ;; (export torus)  - by mesh reference
   ;; (export :torus :cube) - multiple by name
   ;; (export torus cube)   - multiple by reference
   ;; (export (objects))    - export all visible objects
   (defn export
     ([name-or-mesh]
      (cond
        ;; Single keyword
        (keyword? name-or-mesh)
        (when-let [m (get-mesh name-or-mesh)]
          (save-stl [m] (str (name name-or-mesh) \".stl\")))
        ;; List of keywords
        (and (sequential? name-or-mesh) (keyword? (first name-or-mesh)))
        (let [meshes (keep get-mesh name-or-mesh)]
          (when (seq meshes)
            (save-stl (vec meshes)
                      (str (clojure.string/join \"-\" (map name name-or-mesh)) \".stl\"))))
        ;; Single mesh
        (mesh? name-or-mesh)
        (save-stl [name-or-mesh] \"export.stl\")
        ;; List of meshes
        (and (sequential? name-or-mesh) (mesh? (first name-or-mesh)))
        (save-stl (vec name-or-mesh) \"export.stl\")))
     ([first-arg & more-args]
      (let [all-args (cons first-arg more-args)
            meshes (keep resolve-mesh all-args)]
        (when (seq meshes)
          (save-stl (vec meshes) \"export.stl\")))))")

;; Persistent SCI context - created once, reused for REPL commands
(defonce ^:private sci-ctx (atom nil))

(defn- make-sci-ctx []
  (let [ctx (sci/init {:bindings base-bindings})]
    (sci/eval-string macro-defs ctx)
    ctx))

(defn- get-or-create-ctx []
  (if-let [ctx @sci-ctx]
    ctx
    (let [ctx (make-sci-ctx)]
      (reset! sci-ctx ctx)
      ctx)))

(defn reset-ctx!
  "Reset the SCI context. Called when definitions are re-evaluated."
  []
  (reset! sci-ctx (make-sci-ctx)))

;; ============================================================
;; Evaluation
;; ============================================================

(defn evaluate-definitions
  "Evaluate definitions code only. Resets context and turtle state.
   Called when user runs definitions panel (Cmd+Enter or Run button).
   Returns {:result turtle-state :explicit-result any} or {:error msg}."
  [explicit-code]
  (try
    ;; Reset context for fresh definitions evaluation
    (reset-ctx!)
    (let [ctx (get-or-create-ctx)]
      ;; Reset turtle for fresh evaluation
      (reset-turtle!)
      ;; Evaluate explicit code (definitions, functions, explicit geometry)
      (let [explicit-result (when (and explicit-code (seq (str/trim explicit-code)))
                              (sci/eval-string explicit-code ctx))]
        {:result @turtle-atom
         :explicit-result explicit-result
         :implicit-result nil}))
    (catch :default e
      {:error (.-message e)})))

(defn evaluate-repl
  "Evaluate REPL input only, using existing context.
   Definitions must be evaluated first to populate the context.
   Turtle pose (position, heading, up) persists between REPL commands.
   Geometry is cleared each command (only shows current command's output).
   Returns {:result turtle-state :implicit-result any} or {:error msg}."
  [repl-code]
  (try
    (let [ctx (get-or-create-ctx)]
      ;; Preserve turtle pose but clear geometry for fresh output
      (if (nil? @turtle-atom)
        (reset-turtle!)
        ;; Keep position/heading/up, clear geometry/meshes
        (swap! turtle-atom assoc :geometry [] :meshes []))
      ;; Evaluate REPL code using existing context with definitions
      (let [implicit-result (when (and repl-code (seq (str/trim repl-code)))
                              (sci/eval-string repl-code ctx))]
        {:result @turtle-atom
         :explicit-result nil
         :implicit-result implicit-result}))
    (catch :default e
      {:error (.-message e)})))

(defn evaluate
  "Evaluate both explicit and implicit code sections (legacy API).
   Returns {:result turtle-state :explicit-result any :implicit-result any} or {:error msg}."
  [explicit-code implicit-code]
  (try
    ;; Reset context for fresh evaluation
    (reset-ctx!)
    (let [ctx (get-or-create-ctx)]
      ;; Reset turtle for fresh evaluation (but NOT registry - that persists)
      (reset-turtle!)
      ;; Phase 1: Evaluate explicit code (definitions, functions, explicit geometry)
      (let [explicit-result (when (and explicit-code (seq (str/trim explicit-code)))
                              (sci/eval-string explicit-code ctx))
            ;; Phase 2: Evaluate implicit code (turtle commands)
            implicit-result (when (and implicit-code (seq (str/trim implicit-code)))
                              (sci/eval-string implicit-code ctx))]
        ;; Return combined result
        {:result @turtle-atom
         :explicit-result explicit-result
         :implicit-result implicit-result}))
    (catch :default e
      {:error (.-message e)})))

(defn extract-render-data
  "Extract render data from evaluation result.
   Returns turtle geometry (lines from pen movements).
   Meshes must be explicitly registered with (register name mesh) to be visible.
   Does NOT include registry meshes - those are handled separately by refresh-viewport!."
  [eval-result]
  (let [turtle-state (:result eval-result)
        turtle-lines (or (:geometry turtle-state) [])]
    ;; Only return turtle lines (pen traces)
    ;; Meshes from extrude/loft/etc are NOT auto-displayed - use register
    (when (seq turtle-lines)
      {:lines (vec turtle-lines)
       :meshes []})))
