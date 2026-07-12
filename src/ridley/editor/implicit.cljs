(ns ridley.editor.implicit
  "Implicit turtle functions that mutate the current turtle atom.
   These functions are bound in the SCI context via base-bindings.
   The turtle atom is resolved via state/turtle-state-var, supporting
   scoped turtle contexts."
  (:require [clojure.string :as cstr]
            [ridley.editor.state :as state]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.path :as path]
            [ridley.geometry.primitives :as prims]
            [ridley.manifold.core :as manifold]
            [ridley.sdf.core :as sdf]
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

(defn ^:export implicit-reset-pose
  "Reset turtle pose to defaults (origin, +X heading, +Z up, pen :on).
   Keeps accumulated geometry and meshes.

   - (reset)                          — reset to origin
   - (reset [x y z])                  — reset to position
   - (reset [x y z] :heading h :up u) — reset to position with orientation
   - (reset pose-map)                 — reset from a pose map carrying
                                        :pos/:heading/:up keys, e.g.
                                        (reset (:pose (:end-face seg)))
   - (reset pose-map :heading h)      — pose-map with explicit overrides"
  [& args]
  (let [first-arg (first args)
        pose? (and first-arg
                   (map? first-arg)
                   (some #{:pos :position :heading :up} (keys first-arg)))
        norm-args
        (if pose?
          ;; Accept either :pos (extrude+/revolve+ end-face style) or
          ;; :position (anchors / turtle-state style).
          (let [pos (or (:pos first-arg) (:position first-arg))
                {:keys [heading up]} first-arg
                overrides (apply hash-map (rest args))
                final-heading (or (:heading overrides) heading)
                final-up (or (:up overrides) up)]
            (concat [(or pos [0 0 0])]
                    (when final-heading [:heading final-heading])
                    (when final-up [:up final-up])))
          args)]
    (apply swap! (turtle-ref) turtle/reset-pose norm-args)))

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
  ;; Built-in anchors
  (if (#{:origin :ground} name)
    (let [[h u] (case name
                  :origin [[1 0 0] [0 0 1]]
                  :ground [[0 0 -1] [0 1 0]])]
      (swap! (turtle-ref) #(-> % (assoc :position [0 0 0]) (assoc :heading h) (assoc :up u))))
    (do
      ;; Check mark-anchors, copy to turtle state if found
      (when-let [mark-pose (get @state/mark-anchors name)]
        (swap! (turtle-ref) assoc-in [:anchors name]
               {:position (or (:pos mark-pose) (:position mark-pose))
                :heading (:heading mark-pose)
                :up (:up mark-pose)}))
      (swap! (turtle-ref) turtle/goto name)))
  (record-pen-lines!))

(defn ^:export get-anchor
  "Get anchor data by name from turtle state. Returns {:position [x y z] :heading [x y z] :up [x y z]} or nil."
  [name]
  (get-in @(turtle-ref) [:anchors name]))

(defn ^:export implicit-attach-path
  "Associate a path's marks as anchors on a mesh, resolved at the mesh's
   creation-pose. Accepts either a registered mesh name (re-registers it in
   place) or a mesh value (returns a new mesh, so it threads through `->`).
   Returns the updated mesh."
  [mesh-or-name path-data]
  (let [by-name? (not (map? mesh-or-name))
        mesh (if by-name? (registry/get-mesh mesh-or-name) mesh-or-name)]
    (when mesh
      (let [pose (or (:creation-pose mesh)
                     {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
            anchors (turtle/resolve-marks pose path-data)
            updated (cond-> mesh (seq anchors) (assoc :anchors anchors))]
        (when (and by-name? (seq anchors))
          (registry/register-mesh! mesh-or-name updated))
        updated))))

(defn ^:export implicit-anchors
  "Return the named anchors of a registered mesh OR a path.

   - When `target` is a registered mesh name (or mesh value), returns its
     `:anchors` map (set by `attach-path`), or nil.
   - When `target` is a path, resolves the path's marks at the world origin
     and returns the resulting `name → pose` map. Useful when iterating a
     skeleton path's marks without going through a carrier mesh.

   Each entry is `name → {:position [x y z] :heading [x y z] :up [x y z]}`.

   Example:
     (def my-skel (path (mark :pin) (f 50) (mark :tip)))
     (anchors my-skel)              ; => {:pin {...} :tip {...}}
     (keys (anchors my-skel))       ; => (:pin :tip)

     ;; Or via a registered carrier:
     (register Sk (sphere 0.001) :hidden)
     (attach-path :Sk my-skel)
     (anchors :Sk)                  ; => same"
  [target]
  (cond
    (and (map? target) (= :path (:type target)))
    (turtle/resolve-marks
     {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}
     target)

    (and (map? target) (:vertices target))
    (:anchors target)

    :else
    (:anchors (registry/get-mesh target))))

(defn- on-anchors-format-pattern [pat]
  (cond
    (string? pat)             (pr-str pat)
    (keyword? pat)            (str pat)
    (set? pat)                (pr-str pat)
    (instance? js/RegExp pat) (str pat)
    :else                     (pr-str pat)))

(defn ^:export implicit-pin-path
  "Resolve a path's marks at the current turtle pose and return the resulting
   `{anchor-name → {:position [x y z] :heading [x y z] :up [x y z]}}` map.

   `pin-path` is the value-returning counterpart of `with-path` — it captures
   the same anchor resolution but as plain data, with no scope. Use it for
   introspection (\"where would these marks be if I pinned them here?\") or
   for manual per-anchor logic that does not fit the `on-anchors` dispatch
   shape:

       (doseq [[name pose] (pin-path skel)]
         (when (= \"foot-\" (subs (clojure.core/name name) 0 5))
           …))

   Returns nil if `path` is not a path map. Compare to `(anchors path)`,
   which always resolves marks at the world origin."
  [path]
  (when (and (map? path) (= :path (:type path)))
    (turtle/resolve-marks @(turtle-ref) path)))

(defn ^:export on-anchors-resolve-target
  "Resolve the anchor map an on-anchors call iterates over.

   - **Path target**: marks are resolved at the CURRENT turtle pose, so
     on-anchors composes naturally inside nested assembly contexts (the
     same convention as with-path, not as `(anchors path)` which uses
     the world origin).
   - **Mesh target** (value or registered name): the stored :anchors map
     is returned unchanged — mesh anchors live in world coordinates and
     are independent of the current turtle.

   Returns nil if the target carries no resolvable anchors."
  [target]
  (cond
    (and (map? target) (= :path (:type target)))
    (turtle/resolve-marks @(turtle-ref) target)
    (and (map? target) (:vertices target))
    (:anchors target)
    :else
    (:anchors (registry/get-mesh target))))

(defn ^:export on-anchors-match?
  "Test whether an anchor name matches a pattern.
   Pattern types:
   - string  → prefix match on (name anchor-name)
   - regex   → re-find on (name anchor-name)
   - keyword → equality with anchor-name
   - set     → contains? on anchor-name"
  [pat anchor-name]
  (cond
    (keyword? pat)            (= pat anchor-name)
    (set? pat)                (contains? pat anchor-name)
    (string? pat)             (cstr/starts-with? (name anchor-name) pat)
    (instance? js/RegExp pat) (some? (re-find pat (name anchor-name)))
    :else
    (throw (js/Error.
            (str "on-anchors: unsupported pattern type "
                 (pr-str (type pat)) ": " (pr-str pat)
                 ". Use string, regex, keyword, or set.")))))

(defn ^:export on-anchors-captures
  "Return the capture vector for an on-anchors match, as [full g1 g2 ...].
   For a regex pattern this normalizes the re-find result (a bare string when
   the regex has no groups becomes [string]); for keyword/set/string patterns
   it returns [(name anchor-name)]. The on-anchors macro binds element 0 to `$`
   and elements 1..9 to `$1`..`$9` inside each clause body."
  [pat anchor-name]
  (if (instance? js/RegExp pat)
    (let [m (re-find pat (name anchor-name))]
      (cond
        (nil? m)    [(name anchor-name)]
        (string? m) [m]
        :else       (vec m)))
    [(name anchor-name)]))

(defn ^:export on-anchors-warn-no-match!
  "Emit a console warning that an on-anchors pattern did not match any anchor
   in the given anchor map. Lists the available anchor names."
  [pattern anchor-map]
  (js/console.warn
   (str "on-anchors: pattern "
        (on-anchors-format-pattern pattern)
        " non ha matchato alcun anchor. Anchor disponibili: "
        (pr-str (sort (keys (or anchor-map {})))))))

(defn ^:export implicit-mark-pos
  "Return the 3D position [x y z] of a named mark within a path.
   Walks the path from the world origin (heading +X, up +Z) and returns
   the turtle's position at the moment the mark was recorded. Handles
   every path command — including :side-trip — via turtle/resolve-marks.
   Returns nil if the mark is not found."
  [path mark-name]
  (when (and (map? path) (= :path (:type path)))
    (-> (turtle/resolve-marks
         {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}
         path)
        (get mark-name)
        :position)))

(defn ^:export implicit-mark-x
  "X coordinate of a named mark within a path."
  [path mark-name]
  (first (implicit-mark-pos path mark-name)))

(defn ^:export implicit-mark-y
  "Y coordinate of a named mark within a path."
  [path mark-name]
  (second (implicit-mark-pos path mark-name)))

(defn ^:export implicit-mark-z
  "Z coordinate of a named mark within a path."
  [path mark-name]
  (nth (implicit-mark-pos path mark-name) 2 nil))

(defn ^:export implicit-path-length
  "Total length of an OPEN path: the sum of euclidean distances between
   consecutive 3D turtle waypoints, with NO closing edge back to the start.
   Walks the path from the world origin (heading +X, up +Z) via the real
   turtle replay, so the value is the true 3D length (for a planar path it
   equals the 2D length).

   The measurement is over the per-`f` waypoint polyline (the corners between
   forward moves): for paths built from curved/bezier segments this is the
   control polygon, which UNDERESTIMATES the smooth length. For a closed 2D
   profile use `shape-perimeter` instead. Returns nil for non-paths."
  [path]
  (when (and (map? path) (= :path (:type path)) (turtle/path-micro-commands path))
    (let [segments (turtle/path-segments path)
          wps (turtle/compute-path-waypoints
               segments {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})
          positions (mapv :position wps)]
      (loop [i 1 total 0]
        (if (>= i (count positions))
          total
          (let [[x1 y1 z1] (nth positions (dec i))
                [x2 y2 z2] (nth positions i)
                dx (- x2 x1) dy (- y2 y1) dz (- z2 z1)]
            (recur (inc i) (+ total (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))))))))))

(defn ^:export implicit-look-at [name]
  (when-let [mark-pose (get @state/mark-anchors name)]
    (swap! (turtle-ref) assoc-in [:anchors name]
           {:position (or (:pos mark-pose) (:position mark-pose))
            :heading (:heading mark-pose)
            :up (:up mark-pose)}))
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

(defn- do-scale-mesh [factor]
  (let [old-attached (:attached @(turtle-ref))
        registry-idx (:registry-index old-attached)]
    (swap! (turtle-ref) turtle/scale factor)
    (when (and registry-idx (= :pose (:type old-attached)))
      (let [new-mesh (get-in @(turtle-ref) [:attached :mesh])]
        (when new-mesh
          (registry/update-mesh-at-index! registry-idx new-mesh)
          (registry/refresh-viewport! false))))))

(defn ^:export implicit-scale-mesh
  "Scale the attached mesh in place.
   - (scale factor)            uniform scale
   - (scale [sx sy sz])        non-uniform scale (vector form)
   - (scale fx fy fz)          non-uniform scale (positional form)
   For 2D shape scaling use scale-shape."
  ([factor]
   (if (= :pose (get-in @(turtle-ref) [:attached :type]))
     (do-scale-mesh factor)
     (throw (js/Error. "scale: attach to a mesh first (for 2D shape scaling use scale-shape)"))))
  ([fx fy fz]
   (if (= :pose (get-in @(turtle-ref) [:attached :type]))
     (do-scale-mesh [fx fy fz])
     (throw (js/Error. "scale: attach to a mesh first (for 2D shape scaling use scale-shape)")))))

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
  "Transform mesh so height (local Y) extends along turtle's heading axis.
   The cylinder/cone local Y axis maps to heading, not up.
   Swaps heading↔up so apply-transform (which maps local Y→up param)
   ends up placing the cylinder axis along heading."
  [mesh]
  (let [turtle @(turtle-ref)
        position (:position turtle)
        heading (:heading turtle)
        up (:up turtle)
        material (:material turtle)
        ;; Swap: pass up as heading, -heading as up
        ;; apply-transform maps local Y → up param → now that's -heading
        ;; Net effect: cylinder local Y goes along heading direction
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
  "Create cylinder mesh at current turtle position, height along the turtle's heading."
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
  "Create cone mesh at current turtle position, axis along the turtle's heading.
   (cone r1 r2 height): r1 = radius at the near/start end, r2 = radius at the far
   end (along heading), matching loft reading order:
   (cone r1 r2 h) ~= (loft (circle r1) (circle r2) (f h))."
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

(defn- turtle-plane-basis
  "Returns [pos heading up right] for the turtle's current plane,
   with right = up × heading (det=+1, needed for Manifold)."
  []
  (let [state @(turtle-ref)
        pos (:position state)
        heading (:heading state)
        up (:up state)
        [hx hy hz] heading
        [ux uy uz] up
        rx (- (* uy hz) (* uz hy))
        ry (- (* uz hx) (* ux hz))
        rz (- (* ux hy) (* uy hx))
        rmag (Math/sqrt (+ (* rx rx) (* ry ry) (* rz rz)))
        right (if (pos? rmag)
                [(/ rx rmag) (/ ry rmag) (/ rz rmag)]
                [0 1 0])]
    [pos heading up right]))

(defn- mirror-shapes-x
  "Mirror X and reverse winding to match the stamp coordinate frame.
   Manifold uses right = up × heading; stamp uses right = heading × up."
  [shapes]
  (let [mirror (fn [pts] (mapv (fn [[x y]] [(- x) y]) (vec (rseq pts))))]
    (mapv (fn [shape]
            (cond-> (update shape :points mirror)
              (:holes shape) (update :holes (fn [hs] (mapv mirror hs)))))
          shapes)))

(defn- resolve-to-mesh
  "Accept a mesh map, a keyword (registered mesh name), or an SDF node
   (auto-materialized into a mesh)."
  [x]
  (cond
    (keyword? x)     (registry/get-mesh x)
    (sdf/sdf-node? x) (sdf/ensure-mesh x)
    :else x))

(defn ^:export implicit-slice-mesh
  "Slice a mesh at the plane defined by the turtle's current position and heading.
   The heading vector is the plane normal. Returns a vector of shapes
   in the plane's local coordinates (X = turtle right, Y = turtle up).
   Shapes have :preserve-position? true for absolute coordinate rendering.
   Accepts a mesh map, a keyword (registered mesh name), or an SDF node
   (auto-materialized)."
  [mesh-or-name-or-sdf & args]
  (let [on (second (drop-while #(not= :on %) args))
        mesh (resolve-to-mesh mesh-or-name-or-sdf)]
    (if (some? on)
      ;; `:on <mark|fraction>` → the generative profile that was swept, with its
      ;; marks, re-extrudable into the same marked mesh. For a morphing loft
      ;; (tapered/twisted) it returns the cross-section AT t — the morphed shape,
      ;; whose :mark-refs ride the scaled points; for extrude it is constant.
      (let [sfn (:profile-shape-fn mesh)
            profile (:profile-shape mesh)]
        (when-not (or sfn profile)
          (throw (js/Error. "slice-mesh :on: this mesh has no recorded generative profile (it was built without profile marks, or not by extrude/loft)")))
        (let [t (turtle/rail-fraction mesh on)]
          (when-not t
            (throw (js/Error. (str "slice-mesh :on: rail locator " (pr-str on)
                                   " not found — use a rail (mark …) name or a fraction 0..1"))))
          [(if sfn (sfn t) profile)]))
      (let [[pos heading up right] (turtle-plane-basis)]
        (mirror-shapes-x (manifold/slice-at-plane mesh heading pos right up))))))

(defn ^:export implicit-slice-at-plane
  "Slice a mesh at an arbitrary plane (point + normal), optionally with explicit
   right/up vectors for the plane's local basis. Accepts a mesh map, a keyword
   (registered mesh name), or an SDF node (auto-materialized). Returns shapes
   with :preserve-position? true, oriented to match the stamp coordinate frame
   (consistent with slice-mesh)."
  ([mesh-or-name-or-sdf normal point]
   (let [mesh (resolve-to-mesh mesh-or-name-or-sdf)]
     (mirror-shapes-x (manifold/slice-at-plane mesh normal point))))
  ([mesh-or-name-or-sdf normal point right up]
   (let [mesh (resolve-to-mesh mesh-or-name-or-sdf)]
     (mirror-shapes-x (manifold/slice-at-plane mesh normal point right up)))))

(defn ^:export implicit-project-mesh
  "Project a mesh onto the plane orthogonal to the turtle's heading,
   returning the silhouette outline as a vector of 2D shapes (X = turtle right,
   Y = turtle up). Holes are preserved. Shapes have :preserve-position? true.
   Accepts a mesh map, a keyword (registered mesh name), or an SDF node
   (auto-materialized)."
  [mesh-or-name-or-sdf]
  (let [mesh (resolve-to-mesh mesh-or-name-or-sdf)
        [pos heading up right] (turtle-plane-basis)]
    (mirror-shapes-x (manifold/project-at-plane mesh heading pos right up))))

(defn path-mark-names-in-order
  "Names of every (mark …) in `path`, in the order they appear. Public —
   edit-mesh-split reuses this to derive re-entry mark order without
   duplicating the walk."
  [path]
  (->> (turtle/path-micro-commands path)
       (filter #(= :mark (:cmd %)))
       (mapv (comp first :args))))

(defn- guillotine-split
  "Cut `mesh` at each pose in `mark-poses` (in order), right-nesting each
   result's :ahead into the next cut. Returns a bare mesh when mark-poses
   is empty — the identity that makes a single-mark composite call return
   literally the same shape as the primitive. An already-empty mesh (from
   a prior cut) short-circuits without touching Manifold: both halves stay
   the same empty mesh, preserving positional correspondence in the chain."
  [mesh mark-poses]
  (if (empty? mark-poses)
    mesh
    (let [{:keys [position heading]} (first mark-poses)
          [px py pz] position
          [hx hy hz] heading
          offset (+ (* hx px) (* hy py) (* hz pz))
          {:keys [ahead behind]}
          (if (empty? (:faces mesh))
            {:ahead mesh :behind mesh}
            (manifold/split-by-plane mesh heading offset))]
      {:behind behind :ahead (guillotine-split ahead (rest mark-poses))})))

(defn ^:export implicit-mesh-split
  "Split a mesh at the plane defined by the turtle's current position and
   heading — point = position, normal = heading, offset = heading·position.
   Works inside turtle/with-path scope like sdf-half-space/slice-mesh.

   (mesh-split m)                    single cut at the turtle's current pose
   (mesh-split m path)               a cut at EVERY mark in path, in order
   (mesh-split m path marks-vector)  cuts at only the listed marks, in that order

   Returns {:behind <mesh> :ahead <mesh>}. :behind is the half BEHIND the
   heading — the material side, same convention as sdf-half-space/extrude:
   after extrude the turtle sits on the far face with the material behind
   it. :ahead is the opposite half. This is the SAME convention as
   sdf-half-space, on purpose — one truth about which side is which across
   the whole system.

   With a path, each mark is one cut: :behind is the piece detached at that
   mark, :ahead is either the next cut's node ({:behind :ahead}) or, at the
   last mark, the final remaining mesh — so a single-mark call returns
   exactly the same shape as (mesh-split m). The path is resolved from the
   turtle's CURRENT pose (the same resolver every other path consumer uses),
   not from the path's own internal identity frame.

   Either half may be an empty mesh when the plane misses (or only grazes)
   the piece — a legitimate result, not an error.

   Accepts a mesh map, a keyword (registered mesh name), or an SDF node
   (auto-materialized)."
  ([mesh-or-name-or-sdf]
   (let [mesh (resolve-to-mesh mesh-or-name-or-sdf)
         state @(turtle-ref)
         [px py pz] (:position state)
         [hx hy hz :as heading] (:heading state)
         offset (+ (* hx px) (* hy py) (* hz pz))]
     (manifold/split-by-plane mesh heading offset)))
  ([mesh-or-name-or-sdf path]
   (let [names (path-mark-names-in-order path)]
     (when (empty? names)
       (throw (js/Error. "mesh-split: path has no marks — nothing to cut")))
     (implicit-mesh-split mesh-or-name-or-sdf path names)))
  ([mesh-or-name-or-sdf path marks-vector]
   (let [mesh (resolve-to-mesh mesh-or-name-or-sdf)
         anchors (turtle/resolve-marks @(turtle-ref) path)
         mark-poses (mapv (fn [mark-name]
                            (or (get anchors mark-name)
                                (throw (js/Error.
                                        (str "mesh-split: mark " mark-name
                                             " not found in path")))))
                          marks-vector)]
     (guillotine-split mesh mark-poses))))

(defn ^:export implicit-mesh-mirror
  "Reflect a mesh through the plane at the turtle's current pose (point = position,
   normal = heading) — same plane convention as mesh-split/sdf-half-space. Keeps
   the geometry a genuine reflection (Manifold's native .mirror, winding-correct).
   Use e.g. (mesh-union half (mesh-mirror half)) to rebuild a symmetric whole from
   one kept half. Accepts a mesh map, a keyword (registered name), or an SDF node."
  [mesh-or-name-or-sdf]
  (let [mesh (resolve-to-mesh mesh-or-name-or-sdf)
        state @(turtle-ref)]
    (manifold/mirror-by-plane mesh (:heading state) (:position state))))

(defn ^:export implicit-mirror?
  "Is a mesh mirror-symmetric about the plane at the turtle's current pose (point =
   position, normal = heading)? The B6 cascade (free volumetric gate + symmetric-
   difference confirmation). Optional epsilon on the ratio, like convex?. Empty
   mesh → true. On-demand (77–148 ms), never per-keystroke."
  ([mesh-or-name-or-sdf]
   (let [mesh (resolve-to-mesh mesh-or-name-or-sdf)
         state @(turtle-ref)]
     (manifold/symmetric-about-plane? mesh (:heading state) (:position state))))
  ([mesh-or-name-or-sdf epsilon]
   (let [mesh (resolve-to-mesh mesh-or-name-or-sdf)
         state @(turtle-ref)]
     (manifold/symmetric-about-plane? mesh (:heading state) (:position state) epsilon))))

(defn ^:export implicit-sdf-half-space
  "Returns an SDF representing a half-space defined by the turtle's current
   pose. The cut plane passes through the turtle's position with normal equal
   to the turtle's heading.

   By default, keeps the half-space BEHIND the heading (the side the turtle
   came from). With :cut-ahead, keeps the half-space AHEAD of the heading.

   The default matches the natural orientation after extrude: the turtle ends
   on the far face of the new solid, with the material behind it; calling
   (sdf-half-space) at that pose returns the half-space containing the material."
  ([] (implicit-sdf-half-space nil))
  ([opt]
   (let [state @(turtle-ref)
         [px py pz] (:position state)
         [hx hy hz] (:heading state)
         keep-ahead? (= opt :cut-ahead)
         ;; Signed distance from p=(x,y,z) to plane through (px,py,pz) with
         ;; unit normal (hx,hy,hz):  d(p) = sum hi*(xi - oi).
         ;; d > 0 ahead of plane, d < 0 behind. SDF convention: f<0 inside.
         ;; Default (keep behind): f = d (negative behind is already correct).
         ;; :cut-ahead (keep ahead): f = -d.
         dot-expr (list '+
                        (list '* hx (list '- 'x px))
                        (list '* hy (list '- 'y py))
                        (list '* hz (list '- 'z pz)))]
     (assoc (sdf/compile-expr (if keep-ahead? (list '- dot-expr) dot-expr))
            :creation-pose sdf/default-creation-pose))))

(defn ^:export implicit-sdf-clip
  "Clip an SDF shape against the turtle's plane, keeping the half behind
   the heading. Equivalent to (sdf-intersection shape (sdf-half-space)).

   For the rare case of keeping the front half, use the explicit form:
     (sdf-intersection shape (sdf-half-space :cut-ahead))."
  [shape]
  (sdf/sdf-intersection shape (implicit-sdf-half-space)))

;; ── Turtle-aware SDF primitive wrappers ─────────────────────────
;; SDF primitives spawn at the current turtle pose, mirroring the
;; behaviour of mesh primitives (box, sphere, cyl). Pure constructors
;; in sdf.core remain origin-anchored for use from internal Clojure code.

(defn- transform-sdf-to-turtle
  "Wrap an SDF node with rotate + move so it lives at the current turtle
   pose, and stamp creation-pose accordingly. Identity transform short-
   circuits to avoid noise in the tree."
  [node]
  (let [turtle @(turtle-ref)
        position (:position turtle)
        heading (:heading turtle)
        up (:up turtle)
        [px py pz] position
        [hx hy hz] heading
        [ux uy uz] up
        identity-rot? (and (== hx 1) (== hy 0) (== hz 0)
                           (== ux 0) (== uy 0) (== uz 1))
        rotated (if identity-rot?
                  node
                  ;; Decompose R = [h | h×u | u] into ZYX Tait-Bryan and
                  ;; apply via sdf-rotate. Mirrors sdf-rotate-axis logic.
                  ;; right = h × u; only its z component feeds the roll
                  ;; computation, so x/y components are not materialised.
                  (let [rzv (- (* hx uy) (* hy ux))
                        clamp1 (fn [v] (max -1.0 (min 1.0 v)))
                        pitch-rad (- (Math/asin (clamp1 hz)))
                        yaw-rad (Math/atan2 hy hx)
                        roll-rad (Math/atan2 rzv uz)
                        to-deg (fn [r] (* r (/ 180 Math/PI)))
                        nonzero? (fn [deg] (> (Math/abs deg) 1e-9))
                        maybe-rotate (fn [n axis deg]
                                       (if (nonzero? deg) (sdf/sdf-rotate n axis deg) n))]
                    (-> node
                        (maybe-rotate :x (to-deg roll-rad))
                        (maybe-rotate :y (to-deg pitch-rad))
                        (maybe-rotate :z (to-deg yaw-rad)))))
        moved (if (and (zero? px) (zero? py) (zero? pz))
                rotated
                (sdf/sdf-move rotated px py pz))]
    (assoc moved :creation-pose {:position position
                                 :heading heading
                                 :up up})))

(defn ^:export implicit-sdf-sphere [r]
  (transform-sdf-to-turtle (sdf/sdf-sphere r)))

(defn ^:export implicit-sdf-box
  ([size] (transform-sdf-to-turtle (sdf/sdf-box size)))
  ([a b c] (transform-sdf-to-turtle (sdf/sdf-box a b c))))

(defn- segments-for-radius
  "Voxels-around-curve target from the current turtle resolution, applied
   to a circle of given radius. Same call mesh primitives use, so
   `(resolution :n N)` (and :a, :s modes) drive mesh and SDF smoothness
   uniformly."
  [r]
  (turtle/calc-circle-segments @(turtle-ref) (* 2 Math/PI r)))

(defn ^:export implicit-sdf-cyl [r h]
  ;; libfive's cylinder is built along Z; pre-rotate so its axis swings
  ;; onto X (the turtle's heading in the default frame) before applying
  ;; the turtle transform — matching mesh `cyl`, whose height runs
  ;; along the turtle's heading.
  (-> (sdf/sdf-cyl r h)
      (assoc :feature-segments (segments-for-radius r))
      (sdf/sdf-rotate :y 90)
      transform-sdf-to-turtle))

(defn ^:export implicit-sdf-cone [r1 r2 h]
  ;; New convention (matches mesh `cone` and loft reading order): r1 = near/start
  ;; radius, r2 = far (+heading) radius. sdf-cone builds its first arg at z=-h/2,
  ;; which the -90° Y rotate sends to the +heading end — so pass r2 there and r1
  ;; at z=+h/2. (sdf-cyl uses +90° because it is symmetric and direction is
  ;; irrelevant.)
  (-> (sdf/sdf-cone r2 r1 h)
      (assoc :feature-segments (segments-for-radius (max r1 r2)))
      (sdf/sdf-rotate :y -90)
      transform-sdf-to-turtle))

(defn ^:export implicit-sdf-rounded-box [a b c r]
  (transform-sdf-to-turtle (sdf/sdf-rounded-box a b c r)))

(defn ^:export implicit-sdf-torus [R r]
  (-> (sdf/sdf-torus R r)
      (assoc :feature-segments (segments-for-radius r))
      transform-sdf-to-turtle))

(defn ^:export implicit-sdf-formula [form]
  (transform-sdf-to-turtle (sdf/sdf-formula form)))
