(ns ridley.viewport.core
  "Three.js viewport for rendering turtle geometry."
  (:require ["three" :as THREE]
            ["three/examples/jsm/controls/OrbitControls.js" :refer [OrbitControls]]
            [ridley.viewport.xr :as xr]
            [clojure.string :as str]))

(defonce ^:private state (atom nil))

;; Track current mesh data for export
(defonce ^:private current-meshes (atom []))

;; Visibility state for grid, axes, and lines
(defonce ^:private grid-visible (atom true))
(defonce ^:private axes-visible (atom true))
(defonce ^:private lines-visible (atom true))
(defonce ^:private lines-object (atom nil))

;; Turtle indicator state
(defonce ^:private turtle-visible (atom true))
(defonce ^:private turtle-indicator (atom nil))
(defonce ^:private turtle-pose (atom {:position [0 0 0]
                                       :heading [1 0 0]
                                       :up [0 0 1]}))

;; Face normals visualization (like Blender's normal display)
(defonce ^:private normals-visible (atom false))
(defonce ^:private normals-object (atom nil))

;; Stamp outlines visualization (debug 2D shape preview)
(defonce ^:private stamps-visible (atom true))
(defonce ^:private stamps-object (atom nil))
(defonce ^:private current-stamps (atom []))

;; Panel (3D text billboard) objects
;; Maps panel name -> {:mesh THREE.Mesh :canvas OffscreenCanvas :texture THREE.CanvasTexture}
(defonce ^:private panel-objects (atom {}))

;; Axis-constrained rotation state
;; :axis-key - currently pressed axis key (:x :y :z or nil)
;; :drag-start - mouse position at drag start
(defonce ^:private axis-rotation-state (atom {:axis-key nil :drag-start nil :dragging false}))

(defn- create-scene []
  (let [scene (THREE/Scene.)]
    (set! (.-background scene) (THREE/Color. 0x252526))
    scene))

(defn- create-camera [width height]
  (let [camera (THREE/PerspectiveCamera. 60 (/ width height) 0.1 10000)]
    (.set (.-up camera) 0 0 1)
    (.set (.-position camera) 100 100 100)
    (.lookAt camera 0 0 0)
    camera))

(defn- create-renderer [canvas]
  (let [renderer (THREE/WebGLRenderer. #js {:canvas canvas
                                             :antialias true
                                             :preserveDrawingBuffer true})]
    (.setPixelRatio renderer js/window.devicePixelRatio)
    renderer))

(defn- create-controls [^js camera ^js renderer]
  (let [controls (OrbitControls. camera (.-domElement renderer))]
    (set! (.-enableDamping controls) true)
    (set! (.-dampingFactor controls) 0.05)
    controls))

;; ============================================================
;; Axis-constrained rotation (X/Y/Z keys + drag)
;; ============================================================

(defn- rotate-vector-around-axis
  "Rotate a 3D vector [x y z] around an axis by angle."
  [[vx vy vz] axis angle]
  (let [cos-a (js/Math.cos angle)
        sin-a (js/Math.sin angle)]
    (case axis
      :x [vx
          (- (* vy cos-a) (* vz sin-a))
          (+ (* vy sin-a) (* vz cos-a))]
      :y [(+ (* vx cos-a) (* vz sin-a))
          vy
          (- (* vz cos-a) (* vx sin-a))]
      :z [(- (* vx cos-a) (* vy sin-a))
          (+ (* vx sin-a) (* vy cos-a))
          vz]
      [vx vy vz])))

(defn- rotate-camera-around-axis
  "Rotate camera around a world axis, keeping it looking at the target.
   Also rotates the camera's up vector to maintain consistent orientation."
  [^js camera ^js controls axis angle]
  (let [target (.-target controls)
        cam-pos (.-position camera)
        cam-up (.-up camera)
        ;; Get camera position relative to target
        rel-pos [(- (.-x cam-pos) (.-x target))
                 (- (.-y cam-pos) (.-y target))
                 (- (.-z cam-pos) (.-z target))]
        ;; Get current up vector
        up-vec [(.-x cam-up) (.-y cam-up) (.-z cam-up)]
        ;; Rotate both position and up vector
        [new-x new-y new-z] (rotate-vector-around-axis rel-pos axis angle)
        [new-ux new-uy new-uz] (rotate-vector-around-axis up-vec axis angle)]
    ;; Set new camera position
    (.set cam-pos
          (+ (.-x target) new-x)
          (+ (.-y target) new-y)
          (+ (.-z target) new-z))
    ;; Set new up vector
    (.set cam-up new-ux new-uy new-uz)
    ;; Look at target with new up vector
    (.lookAt camera target)
    (.update controls)))

(defn- setup-axis-rotation
  "Setup keyboard and mouse handlers for axis-constrained rotation."
  [^js canvas ^js camera ^js controls]
  (let [key->axis {"x" :x "X" :x
                   "y" :y "Y" :y
                   "z" :z "Z" :z}
        on-keydown (fn [e]
                     ;; Ignore key repeat events
                     (when-not (.-repeat e)
                       (when-let [axis (key->axis (.-key e))]
                         (swap! axis-rotation-state assoc :axis-key axis)
                         (set! (.-enabled controls) false))))
        on-keyup (fn [e]
                   (when (key->axis (.-key e))
                     (swap! axis-rotation-state assoc :axis-key nil :dragging false)
                     (set! (.-enabled controls) true)))
        on-mousedown (fn [e]
                       (when (:axis-key @axis-rotation-state)
                         (swap! axis-rotation-state assoc
                                :drag-start [(.-clientX e) (.-clientY e)]
                                :dragging true)))
        on-mousemove (fn [e]
                       (let [{:keys [axis-key drag-start dragging]} @axis-rotation-state]
                         (when (and axis-key dragging drag-start)
                           (let [[start-x _] drag-start
                                 dx (- (.-clientX e) start-x)
                                 ;; Convert pixel movement to rotation angle
                                 ;; ~200 pixels = 90 degrees
                                 angle (* dx (/ js/Math.PI 400))]
                             (rotate-camera-around-axis camera controls axis-key angle)
                             ;; Update drag start for continuous rotation
                             (swap! axis-rotation-state assoc
                                    :drag-start [(.-clientX e) (.-clientY e)])))))
        on-mouseup (fn [_]
                     (swap! axis-rotation-state assoc :dragging false :drag-start nil))
        ;; Handle focus loss
        on-blur (fn [_]
                  (swap! axis-rotation-state assoc :axis-key nil :dragging false :drag-start nil)
                  (set! (.-enabled controls) true))]
    ;; Add listeners to window for keys (so they work when canvas has focus)
    (.addEventListener js/window "keydown" on-keydown)
    (.addEventListener js/window "keyup" on-keyup)
    (.addEventListener js/window "blur" on-blur)
    ;; Add mouse listeners to canvas
    (.addEventListener canvas "mousedown" on-mousedown)
    (.addEventListener canvas "mousemove" on-mousemove)
    (.addEventListener canvas "mouseup" on-mouseup)
    (.addEventListener canvas "mouseleave" on-mouseup)))

(defn- add-grid [parent]
  (let [grid (THREE/GridHelper. 200 20 0x444444 0x333333)]
    (.rotateX grid (/ js/Math.PI 2)) ; XY plane instead of XZ
    (set! (.-name grid) "grid")
    (.add parent grid)
    grid))

(defn- create-text-sprite
  "Create a text sprite for axis labels."
  [text color]
  (let [canvas (js/document.createElement "canvas")
        ctx (.getContext canvas "2d")
        size 64]
    (set! (.-width canvas) size)
    (set! (.-height canvas) size)
    (set! (.-font ctx) "bold 48px Arial")
    (set! (.-fillStyle ctx) color)
    (set! (.-textAlign ctx) "center")
    (set! (.-textBaseline ctx) "middle")
    (.fillText ctx text (/ size 2) (/ size 2))
    (let [texture (THREE/CanvasTexture. canvas)
          material (THREE/SpriteMaterial. #js {:map texture})
          ^js sprite (THREE/Sprite. material)]
      (set! (.-x (.-scale sprite)) 8)
      (set! (.-y (.-scale sprite)) 8)
      sprite)))

(defn- add-axes [parent]
  (let [axes-group (THREE/Group.)
        axes (THREE/AxesHelper. 50)
        ;; Add axis labels
        ;; Three.js AxesHelper: Red = X, Green = Y, Blue = Z
        label-x (create-text-sprite "X" "#ff4444")
        label-y (create-text-sprite "Y" "#44ff44")
        label-z (create-text-sprite "Z" "#4444ff")]
    ;; Position labels at end of each axis
    (.set (.-position label-x) 58 0 0)
    (.set (.-position label-y) 0 58 0)
    (.set (.-position label-z) 0 0 58)
    (.add axes-group axes)
    (.add axes-group label-x)
    (.add axes-group label-y)
    (.add axes-group label-z)
    (set! (.-name axes-group) "axes")
    (.add parent axes-group)
    axes-group))

;; ============================================================
;; Turtle indicator (airplane-shaped orientation marker)
;; ============================================================

(defn- create-turtle-indicator
  "Create an airplane-shaped indicator showing turtle position and orientation.
   Returns a Three.js Group with the indicator mesh."
  []
  (let [group (THREE/Group.)
        ;; Body - cone pointing forward (along local +Z)
        body-geo (THREE/ConeGeometry. 0.3 1.2 8)
        body-mat (THREE/MeshBasicMaterial. #js {:color 0x00ffaa
                                                 :transparent true
                                                 :opacity 0.85
                                                 :depthTest false})
        body (THREE/Mesh. body-geo body-mat)
        ;; Wings - flat box extending sideways
        wing-geo (THREE/BoxGeometry. 1.0 0.05 0.3)
        wing-mat (THREE/MeshBasicMaterial. #js {:color 0x00ddff
                                                 :transparent true
                                                 :opacity 0.8
                                                 :depthTest false})
        wing (THREE/Mesh. wing-geo wing-mat)
        ;; Tail fin - vertical (shows up direction)
        tail-geo (THREE/BoxGeometry. 0.05 0.4 0.2)
        tail-mat (THREE/MeshBasicMaterial. #js {:color 0xffaa00
                                                 :transparent true
                                                 :opacity 0.8
                                                 :depthTest false})
        tail (THREE/Mesh. tail-geo tail-mat)]
    ;; Rotate body so cone points along +Z (local forward)
    (.rotateX body (/ js/Math.PI 2))
    ;; Position wings at center-back
    (.set (.-position wing) 0 0 -0.1)
    ;; Position tail at back, pointing up
    (.set (.-position tail) 0 0.2 -0.4)
    ;; High render order to draw on top
    (set! (.-renderOrder body) 1000)
    (set! (.-renderOrder wing) 1000)
    (set! (.-renderOrder tail) 1000)
    (.add group body)
    (.add group wing)
    (.add group tail)
    ;; Disable frustum culling
    (set! (.-frustumCulled group) false)
    (set! (.-name group) "turtle-indicator")
    group))

(defn- update-turtle-indicator-scale
  "Update indicator scale based on camera distance for screen-relative sizing."
  [^js indicator ^js camera]
  (when (and indicator (.-visible indicator))
    (let [world-pos (THREE/Vector3.)]
      (.getWorldPosition indicator world-pos)
      (let [cam-pos (.-position camera)
            distance (.distanceTo world-pos cam-pos)
            ;; Scale factor: larger = bigger on screen
            base-scale 0.04
            scale (* distance base-scale)]
        (.set (.-scale indicator) scale scale scale)))))

(defn- update-turtle-indicator-pose
  "Update turtle indicator position and orientation from pose data."
  [^js indicator {:keys [position heading up]}]
  (when indicator
    (let [[px py pz] position
          [hx hy hz] heading
          [ux uy uz] up]
      ;; Set position
      (.set (.-position indicator) px py pz)
      ;; Use lookAt with custom up vector
      ;; Three.js lookAt: object's +Z faces target, +Y is up
      ;; We want: heading = forward (+Z), up = up (+Y)
      (let [target (THREE/Vector3. (+ px hx) (+ py hy) (+ pz hz))]
        (.set (.-up indicator) ux uy uz)
        (.lookAt indicator target)))))

;; ============================================================
;; 3D Text Panels (billboard)
;; ============================================================

(def ^:private panel-px-per-unit
  "Pixels per world unit for panel texture resolution."
  10)

(defn- hex-to-rgba
  "Convert hex color (with optional alpha in high byte) to RGBA string."
  [hex]
  (let [has-alpha? (> hex 0xffffff)
        r (bit-and (bit-shift-right hex (if has-alpha? 24 16)) 0xff)
        g (bit-and (bit-shift-right hex (if has-alpha? 16 8)) 0xff)
        b (bit-and (bit-shift-right hex (if has-alpha? 8 0)) 0xff)
        a (if has-alpha?
            (/ (bit-and hex 0xff) 255.0)
            1.0)]
    (str "rgba(" r "," g "," b "," a ")")))

(defn- hex-to-rgb
  "Convert hex color to RGB string for canvas."
  [hex]
  (let [r (bit-and (bit-shift-right hex 16) 0xff)
        g (bit-and (bit-shift-right hex 8) 0xff)
        b (bit-and hex 0xff)]
    (str "rgb(" r "," g "," b ")")))

(defn- render-panel-canvas
  "Render panel content to a canvas. Returns the canvas."
  [canvas panel-data]
  (let [{:keys [width height content style]} panel-data
        {:keys [font-size bg fg padding line-height]
         :or {font-size 3 bg 0x333333cc fg 0xffffff padding 2 line-height 1.4}} style
        px-width (* width panel-px-per-unit)
        px-height (* height panel-px-per-unit)
        px-padding (* padding panel-px-per-unit)
        ;; font-size is in world units, convert to pixels
        px-font-size (* font-size panel-px-per-unit)
        ctx (.getContext canvas "2d")
        ;; Use provided content or show placeholder
        display-content (if (seq content) content "<empty>")]
    ;; Debug
    (js/console.log "Panel render:" (clj->js {:content content :width px-width :height px-height :font px-font-size}))
    ;; Clear and draw background
    (.clearRect ctx 0 0 px-width px-height)
    (set! (.-fillStyle ctx) "rgba(50,50,50,0.9)")
    (.fillRect ctx 0 0 px-width px-height)
    ;; Draw border
    (set! (.-strokeStyle ctx) "rgba(255,255,255,0.5)")
    (set! (.-lineWidth ctx) 3)
    (.strokeRect ctx 1 1 (- px-width 2) (- px-height 2))
    ;; Setup text rendering - use explicit white color
    (set! (.-fillStyle ctx) "#ffffff")
    (set! (.-font ctx) (str "bold " px-font-size "px Arial, sans-serif"))
    (set! (.-textBaseline ctx) "top")
    ;; Word wrap and render text
    (let [max-width (- px-width (* 2 px-padding))
          lines (str/split display-content #"\n")
          line-h (* px-font-size line-height)]
      (loop [y px-padding
             remaining-lines lines]
        (when (and (seq remaining-lines) (< y (- px-height px-padding)))
          (let [line (first remaining-lines)
                words (str/split line #" ")
                wrapped (reduce
                         (fn [{:keys [current-line lines]} word]
                           (let [test-line (if (empty? current-line)
                                             word
                                             (str current-line " " word))
                                 metrics (.measureText ctx test-line)]
                             (if (> (.-width metrics) max-width)
                               {:current-line word
                                :lines (conj lines current-line)}
                               {:current-line test-line
                                :lines lines})))
                         {:current-line "" :lines []}
                         words)
                final-lines (if (empty? (:current-line wrapped))
                              (:lines wrapped)
                              (conj (:lines wrapped) (:current-line wrapped)))]
            ;; Draw wrapped lines
            (doseq [[idx text] (map-indexed vector final-lines)]
              (let [line-y (+ y (* idx line-h))]
                (when (< line-y (- px-height px-padding))
                  (.fillText ctx text px-padding line-y))))
            (recur (+ y (* (count final-lines) line-h))
                   (rest remaining-lines))))))
    canvas))

(defn- create-panel-mesh
  "Create a Three.js mesh for a panel. Returns {:mesh :canvas :texture}."
  [panel-data]
  (let [{:keys [width height position heading up]} panel-data
        [px py pz] position
        [hx hy hz] heading
        [ux uy uz] up
        ;; Create canvas and texture (use regular canvas for Three.js compatibility)
        px-width (* width panel-px-per-unit)
        px-height (* height panel-px-per-unit)
        canvas (js/document.createElement "canvas")
        _ (set! (.-width canvas) px-width)
        _ (set! (.-height canvas) px-height)
        _ (render-panel-canvas canvas panel-data)
        texture (THREE/CanvasTexture. canvas)
        ;; Create geometry and material
        geometry (THREE/PlaneGeometry. width height)
        material (THREE/MeshBasicMaterial. #js {:map texture
                                                 :transparent true
                                                 :side THREE/DoubleSide
                                                 :depthWrite false})
        mesh (THREE/Mesh. geometry material)]
    ;; Position the mesh
    (.set (.-position mesh) px py pz)
    ;; Orient based on heading/up (panel faces opposite to heading)
    (let [target (THREE/Vector3. (- px hx) (- py hy) (- pz hz))]
      (.set (.-up mesh) ux uy uz)
      (.lookAt mesh target))
    ;; Mark as panel for identification
    (set! (.-userData mesh) #js {:isPanel true})
    (set! (.-renderOrder mesh) 100)  ; Render after other objects
    {:mesh mesh
     :canvas canvas
     :texture texture}))

(defn- update-panel-content
  "Update an existing panel's texture with new content."
  [panel-obj panel-data]
  (let [{:keys [canvas texture]} panel-obj]
    (render-panel-canvas canvas panel-data)
    (set! (.-needsUpdate ^js texture) true)))

(defn- update-panels-billboard
  "Update all panels to face the camera (billboard effect)."
  [^js camera]
  (doseq [[_name panel-obj] @panel-objects]
    (when-let [mesh (:mesh panel-obj)]
      ;; Copy camera quaternion for billboard effect
      (.copy (.-quaternion ^js mesh) (.-quaternion camera)))))

(defn- clear-panels
  "Remove all panel objects from the scene."
  [^js world-group]
  (doseq [[_name panel-obj] @panel-objects]
    (when-let [^js mesh (:mesh panel-obj)]
      (.remove world-group mesh)
      (when-let [geom (.-geometry mesh)]
        (.dispose geom))
      (when-let [mat (.-material mesh)]
        (.dispose mat))
      (when-let [^js tex (:texture panel-obj)]
        (.dispose tex))))
  (reset! panel-objects {}))

(defn- add-lights [scene]
  (let [;; Hemisphere light for even ambient from sky/ground
        hemi-light (THREE/HemisphereLight. 0xffffff 0x444444 0.8)
        ;; Main light from top-front-right
        main-light (THREE/DirectionalLight. 0xffffff 1.0)
        ;; Fill light from opposite side (softer)
        fill-light (THREE/DirectionalLight. 0x8888ff 0.5)
        ;; Top-down light for horizontal surfaces
        top-light (THREE/DirectionalLight. 0xffffff 0.6)
        ;; Bottom fill to illuminate undersides
        bottom-light (THREE/DirectionalLight. 0xffffff 0.4)
        ;; Front light
        front-light (THREE/DirectionalLight. 0xffffff 0.4)]
    (.set (.-position main-light) 100 150 100)
    (.set (.-position fill-light) -80 50 -50)
    (.set (.-position top-light) 0 200 0)
    (.set (.-position bottom-light) 0 -200 0)
    (.set (.-position front-light) 0 50 150)
    (.add scene hemi-light)
    (.add scene main-light)
    (.add scene fill-light)
    (.add scene top-light)
    (.add scene bottom-light)
    (.add scene front-light)))

(defn- create-mesh-material
  "Create mesh material from optional material map or use defaults."
  ([] (create-mesh-material nil))
  ([material]
   (let [{:keys [color metalness roughness opacity flat-shading]
          :or {color 0x00aaff metalness 0.3 roughness 0.7 opacity 1.0 flat-shading true}} material
         needs-transparency (< opacity 1.0)]
     (THREE/MeshStandardMaterial.
      #js {:color color
           :metalness metalness
           :roughness roughness
           :opacity opacity
           :transparent needs-transparency
           :side THREE/FrontSide
           :flatShading flat-shading}))))

(defn- create-highlight-material
  "Create material for highlighted faces."
  [color]
  (THREE/MeshStandardMaterial. #js {:color color
                                     :metalness 0.1
                                     :roughness 0.5
                                     :side THREE/DoubleSide
                                     :flatShading true
                                     :transparent true
                                     :opacity 0.85
                                     :emissive color
                                     :emissiveIntensity 0.3}))

;; Track highlight objects for cleanup
(defonce ^:private highlight-objects (atom []))

(def ^:private default-line-color 0x00ff88)

(defn- normalize-color
  "Convert color (keyword, string, or number) to [r g b] floats using Three.js."
  [c]
  (let [color-val (if (keyword? c) (name c) c)
        three-color (THREE/Color. color-val)]
    [(.-r three-color) (.-g three-color) (.-b three-color)]))

(defn- create-line-segments
  "Create Three.js line segments from turtle geometry.
   Supports per-segment colors via vertex colors."
  [geometry]
  (when (seq geometry)
    (let [points #js []
          colors #js []
          default-rgb (normalize-color default-line-color)]
      ;; Build points and colors
      (doseq [{:keys [from to color]} geometry]
        (let [[x1 y1 z1] from
              [x2 y2 z2] to
              [r g b] (if color (normalize-color color) default-rgb)]
          (.push points (THREE/Vector3. x1 y1 z1))
          (.push points (THREE/Vector3. x2 y2 z2))
          ;; Each vertex needs color
          (.push colors r g b r g b)))
      ;; Create geometry
      (let [buffer-geom (THREE/BufferGeometry.)]
        (.setFromPoints buffer-geom points)
        ;; Add color attribute
        (.setAttribute buffer-geom "color"
                       (THREE/Float32BufferAttribute. (clj->js colors) 3))
        (THREE/LineSegments. buffer-geom
                             (THREE/LineBasicMaterial. #js {:vertexColors true}))))))

(defn- create-three-mesh
  "Create Three.js mesh from vertices, faces, and optional material."
  [{:keys [vertices faces material]}]
  (let [geom (THREE/BufferGeometry.)
        n-verts (count vertices)
        ;; Flatten vertices for position attribute, with bounds checking
        face-verts (mapcat (fn [[i0 i1 i2]]
                             (when (and (< i0 n-verts) (< i1 n-verts) (< i2 n-verts))
                               [(nth vertices i0 [0 0 0])
                                (nth vertices i1 [0 0 0])
                                (nth vertices i2 [0 0 0])]))
                           faces)
        flat-coords (mapcat identity face-verts)
        positions (js/Float32Array. (clj->js flat-coords))
        three-material (create-mesh-material material)]
    (.setAttribute geom "position" (THREE/BufferAttribute. positions 3))
    ;; With flatShading: true, Three.js computes face normals automatically
    ;; We still call computeVertexNormals for compatibility, but flatShading overrides
    (.computeVertexNormals geom)
    (THREE/Mesh. geom three-material)))

(defn- clear-geometry
  "Remove all user geometry objects from world-group, keeping grid, axes, and highlight-group."
  [^js world-group ^js highlight-group]
  ;; Clear panels first (dispose textures properly)
  (clear-panels world-group)
  ;; Clear other geometry
  (let [to-remove (filterv #(and (or (= (.-type %) "LineSegments")
                                      (= (.-type %) "Mesh"))
                                  ;; Keep grid (GridHelper is also a LineSegments)
                                  (not (instance? THREE/GridHelper %))
                                  ;; Keep highlight group
                                  (not (identical? % highlight-group)))
                           (.-children world-group))]
    (doseq [^js obj to-remove]
      (.remove world-group obj)
      (when-let [geom (.-geometry obj)]
        (.dispose geom))
      (when-let [mat (.-material obj)]
        (.dispose mat)))))

(defn- fit-camera-to-geometry
  "Adjust camera to fit geometry in view."
  [camera controls geometry]
  (when (seq geometry)
    (let [points (mapcat (fn [{:keys [from to]}] [from to]) geometry)
          xs (map first points)
          ys (map second points)
          zs (map #(nth % 2) points)
          min-x (apply min xs) max-x (apply max xs)
          min-y (apply min ys) max-y (apply max ys)
          min-z (apply min zs) max-z (apply max zs)
          center-x (/ (+ min-x max-x) 2)
          center-y (/ (+ min-y max-y) 2)
          center-z (/ (+ min-z max-z) 2)
          size (max (- max-x min-x) (- max-y min-y) (- max-z min-z) 10)
          dist (* size 2)]
      (.set (.-target controls) center-x center-y center-z)
      (.set (.-position camera)
            (+ center-x dist)
            (+ center-y dist)
            (+ center-z dist))
      (.update controls))))

(defn- collect-all-points
  "Collect all points from lines and meshes for camera fitting."
  [lines meshes]
  (concat
   ;; Points from line segments
   (mapcat (fn [{:keys [from to]}] [from to]) lines)
   ;; Points from mesh vertices
   (mapcat :vertices meshes)))

;; ============================================================
;; Face normals visualization
;; ============================================================

(defn- compute-face-normal
  "Compute normal vector for a triangular face."
  [[v0 v1 v2]]
  (let [[x0 y0 z0] v0
        [x1 y1 z1] v1
        [x2 y2 z2] v2
        ;; Edge vectors
        e1x (- x1 x0) e1y (- y1 y0) e1z (- z1 z0)
        e2x (- x2 x0) e2y (- y2 y0) e2z (- z2 z0)
        ;; Cross product
        nx (- (* e1y e2z) (* e1z e2y))
        ny (- (* e1z e2x) (* e1x e2z))
        nz (- (* e1x e2y) (* e1y e2x))
        ;; Normalize
        len (Math/sqrt (+ (* nx nx) (* ny ny) (* nz nz)))]
    (if (pos? len)
      [(/ nx len) (/ ny len) (/ nz len)]
      [0 0 1])))

(defn- compute-face-centroid
  "Compute centroid of a triangular face."
  [[v0 v1 v2]]
  (let [[x0 y0 z0] v0
        [x1 y1 z1] v1
        [x2 y2 z2] v2]
    [(/ (+ x0 x1 x2) 3)
     (/ (+ y0 y1 y2) 3)
     (/ (+ z0 z1 z2) 3)]))

(defn- create-normals-lines
  "Create line segments showing face normals for all meshes.
   Each normal is a short line from face centroid in the normal direction."
  [meshes normal-length]
  (let [segments (atom [])]
    (doseq [mesh-data meshes]
      (let [vertices (:vertices mesh-data)
            faces (:faces mesh-data)]
        (doseq [[i0 i1 i2] faces]
          (when (and (< i0 (count vertices))
                     (< i1 (count vertices))
                     (< i2 (count vertices)))
            (let [v0 (nth vertices i0)
                  v1 (nth vertices i1)
                  v2 (nth vertices i2)
                  face-verts [v0 v1 v2]
                  centroid (compute-face-centroid face-verts)
                  normal (compute-face-normal face-verts)
                  [cx cy cz] centroid
                  [nx ny nz] normal
                  end-point [(+ cx (* nx normal-length))
                             (+ cy (* ny normal-length))
                             (+ cz (* nz normal-length))]]
              (swap! segments conj {:from centroid :to end-point}))))))
    @segments))

(defn- create-normals-object
  "Create a Three.js LineSegments object for normals visualization."
  [meshes]
  (let [segments (create-normals-lines meshes 2.0)]  ;; 2 units normal length
    (when (seq segments)
      (let [points (clj->js (mapcat (fn [{:keys [from to]}]
                                      [(THREE/Vector3. (first from) (second from) (nth from 2))
                                       (THREE/Vector3. (first to) (second to) (nth to 2))])
                                    segments))
            buffer-geom (THREE/BufferGeometry.)
            material (THREE/LineBasicMaterial. #js {:color 0xff00ff   ;; Magenta like Blender
                                                    :linewidth 1})]
        (.setFromPoints buffer-geom points)
        (THREE/LineSegments. buffer-geom material)))))

(defn- update-normals-display
  "Update or create the normals visualization object."
  [world-group meshes]
  ;; Remove old normals object
  (when-let [^js old-obj @normals-object]
    (.remove world-group old-obj)
    (when-let [geom (.-geometry old-obj)]
      (.dispose geom))
    (when-let [mat (.-material old-obj)]
      (.dispose mat)))
  ;; Create new normals object if visible and we have meshes
  (when (and @normals-visible (seq meshes))
    (when-let [new-obj (create-normals-object meshes)]
      (set! (.-name new-obj) "face-normals")
      (reset! normals-object new-obj)
      (.add world-group new-obj))))

;; ============================================================
;; Stamp surface visualization (debug 2D shape preview)
;; ============================================================

(defn- create-stamp-mesh
  "Create a semi-transparent Three.js mesh from pre-triangulated stamp data.
   Each stamp is {:vertices [[x y z]...] :faces [[i j k]...]}."
  [{:keys [vertices faces]}]
  (when (and (seq vertices) (seq faces))
    (let [geom (THREE/BufferGeometry.)
          n-verts (count vertices)
          ;; Expand indexed faces to flat vertex positions
          face-verts (mapcat (fn [[i0 i1 i2]]
                               (when (and (< i0 n-verts) (< i1 n-verts) (< i2 n-verts))
                                 [(nth vertices i0)
                                  (nth vertices i1)
                                  (nth vertices i2)]))
                             faces)
          flat-coords (mapcat identity face-verts)
          positions (js/Float32Array. (clj->js flat-coords))
          material (THREE/MeshBasicMaterial.
                    #js {:color 0xffaa00
                         :transparent true
                         :opacity 0.3
                         :side THREE/DoubleSide
                         :depthWrite false})]
      (.setAttribute geom "position" (THREE/BufferAttribute. positions 3))
      (.computeVertexNormals geom)
      (THREE/Mesh. geom material))))

(defn- update-stamps-display
  "Update or create the stamps visualization objects."
  [world-group stamps]
  ;; Remove old stamps group
  (when-let [^js old-obj @stamps-object]
    (.remove world-group old-obj)
    ;; Dispose children
    (.traverse old-obj (fn [^js child]
                         (when-let [geom (.-geometry child)]
                           (.dispose geom))
                         (when-let [mat (.-material child)]
                           (.dispose mat))))
    (reset! stamps-object nil))
  ;; Create new stamps group if visible and we have stamps
  (when (and @stamps-visible (seq stamps))
    (let [group (THREE/Group.)]
      (doseq [stamp-data stamps]
        (when-let [mesh (create-stamp-mesh stamp-data)]
          (.add group mesh)))
      (when (pos? (.-length (.-children group)))
        (set! (.-name group) "stamp-surfaces")
        (reset! stamps-object group)
        (.add world-group group)))))

(defn update-scene
  "Update viewport with lines, meshes, and panels.
   Options:
     :reset-camera? - if true (default), fit camera to geometry
     :panels - vector of panel data to render"
  [{:keys [lines meshes stamps panels reset-camera?] :or {reset-camera? true panels [] stamps []}}]
  ;; Store meshes and stamps for export/toggle
  (reset! current-meshes (vec meshes))
  (reset! current-stamps (vec stamps))
  (when-let [{:keys [world-group highlight-group camera controls]} @state]
    (clear-geometry world-group highlight-group)
    ;; Add line segments to world-group
    (reset! lines-object nil)
    (when (seq lines)
      (when-let [line-obj (create-line-segments lines)]
        (set! (.-name line-obj) "turtle-lines")
        (set! (.-visible line-obj) @lines-visible)
        (reset! lines-object line-obj)
        (.add world-group line-obj)))
    ;; Add meshes to world-group
    (doseq [mesh-data meshes]
      (let [mesh (create-three-mesh mesh-data)]
        (.add world-group mesh)))
    ;; Add panels to world-group
    (doseq [panel-data panels]
      (when (= :panel (:type panel-data))
        (let [panel-obj (create-panel-mesh panel-data)
              name (:name panel-data)]
          (.add world-group (:mesh panel-obj))
          (swap! panel-objects assoc name panel-obj))))
    ;; Update normals visualization
    (update-normals-display world-group meshes)
    ;; Update stamp outlines visualization
    (update-stamps-display world-group stamps)
    ;; Fit camera to all geometry (only if reset-camera? is true)
    (when reset-camera?
      (let [stamp-points (mapcat :vertices stamps)
            all-points (concat (collect-all-points lines meshes) stamp-points)]
        (when (seq all-points)
          (let [xs (map first all-points)
                ys (map second all-points)
                zs (map #(nth % 2) all-points)
                min-x (apply min xs) max-x (apply max xs)
                min-y (apply min ys) max-y (apply max ys)
                min-z (apply min zs) max-z (apply max zs)
                center-x (/ (+ min-x max-x) 2)
                center-y (/ (+ min-y max-y) 2)
                center-z (/ (+ min-z max-z) 2)
                size (max (- max-x min-x) (- max-y min-y) (- max-z min-z) 10)
                dist (* size 2)]
            (.set (.-target controls) center-x center-y center-z)
            (.set (.-position camera)
                  (+ center-x dist)
                  (+ center-y dist)
                  (+ center-z dist))
            (.update controls)))))))

(defn update-geometry
  "Update viewport with new turtle geometry (line segments only, legacy)."
  [geometry]
  (update-scene {:lines geometry :meshes []}))

(defn update-mesh
  "Update viewport with a mesh (legacy, single mesh)."
  [mesh-data]
  (when-let [{:keys [world-group highlight-group camera controls]} @state]
    (clear-geometry world-group highlight-group)
    (when mesh-data
      (let [mesh (create-three-mesh mesh-data)]
        (.add world-group mesh)))
    ;; Fit camera to mesh vertices
    (when-let [vertices (:vertices mesh-data)]
      (let [xs (map first vertices)
            ys (map second vertices)
            zs (map #(nth % 2) vertices)
            min-x (apply min xs) max-x (apply max xs)
            min-y (apply min ys) max-y (apply max ys)
            min-z (apply min zs) max-z (apply max zs)
            center-x (/ (+ min-x max-x) 2)
            center-y (/ (+ min-y max-y) 2)
            center-z (/ (+ min-z max-z) 2)
            size (max (- max-x min-x) (- max-y min-y) (- max-z min-z) 10)
            dist (* size 2)]
        (.set (.-target controls) center-x center-y center-z)
        (.set (.-position camera)
              (+ center-x dist)
              (+ center-y dist)
              (+ center-z dist))
        (.update controls)))))

(defn- render-frame
  "Single frame render function for setAnimationLoop.
   In WebXR mode, receives (time, xr-frame) parameters."
  [_time xr-frame]
  (when-let [{:keys [renderer scene camera controls]} @state]
    (let [^js renderer renderer
          ^js scene scene
          ^js camera camera
          ^js controls controls]
      ;; Update turtle indicator scale for screen-relative sizing
      (when-let [^js indicator @turtle-indicator]
        (update-turtle-indicator-scale indicator camera))
      ;; Update panels to face camera (billboard effect)
      (update-panels-billboard camera)
      (if (xr/xr-presenting? renderer)
        ;; VR mode: update controller input, pass XR frame for pose data
        (xr/update-controller xr-frame renderer)
        ;; Desktop mode: update OrbitControls
        (.update controls))
      (.render renderer scene camera))))

(defn- start-animation-loop
  "Start the render loop using setAnimationLoop for XR compatibility."
  []
  (when-let [{:keys [renderer]} @state]
    (.setAnimationLoop ^js renderer render-frame)))

(defn handle-resize
  "Handle viewport resize - call when panel dimensions change."
  []
  (when-let [{:keys [renderer camera canvas]} @state]
    (let [^js renderer renderer
          ^js camera camera
          ^js canvas canvas
          width (.-clientWidth canvas)
          height (.-clientHeight canvas)]
      (when (and (pos? width) (pos? height))
        (.setSize renderer width height false)  ; false = don't set CSS style
        (set! (.-aspect camera) (/ width height))
        (.updateProjectionMatrix camera)))))

(defn init
  "Initialize Three.js viewport on given canvas element."
  [canvas]
  (let [;; Get initial dimensions from canvas (CSS sets flex: 1)
        width (max 1 (.-clientWidth canvas))
        height (max 1 (.-clientHeight canvas))
        scene (create-scene)
        camera (create-camera width height)
        renderer (create-renderer canvas)
        controls (create-controls camera renderer)
        ;; Create camera rig for VR movement
        camera-rig (THREE/Group.)
        ;; Create world group for rotatable content (grid, axes, geometry)
        world-group (THREE/Group.)
        ;; Create separate group for highlights (not cleared with geometry)
        highlight-group (THREE/Group.)]
    ;; Add camera to rig (for VR positioning)
    (.add camera-rig camera)
    (.add scene camera-rig)
    ;; Add world group to scene
    (.add scene world-group)
    ;; Add highlight group to world-group (so it rotates with scene)
    (.add world-group highlight-group)
    (.setSize renderer width height)
    ;; Add grid, axes to world-group (so they rotate together)
    (let [grid (add-grid world-group)
          axes (add-axes world-group)
          ;; Create turtle indicator
          turtle-ind (create-turtle-indicator)]
      ;; Add turtle indicator to world-group
      (.add world-group turtle-ind)
      (reset! turtle-indicator turtle-ind)
      (set! (.-visible turtle-ind) @turtle-visible)
      ;; Initialize with default pose
      (update-turtle-indicator-pose turtle-ind @turtle-pose)
      ;; Lights stay in scene (not affected by world rotation)
      (add-lights scene)
      ;; Enable WebXR on renderer
      (xr/enable-xr renderer)
      ;; Setup VR controller (pass world-group for rotation)
      (xr/setup-controller renderer scene camera-rig camera world-group)
      ;; Setup axis-constrained rotation (X/Y/Z keys + drag)
      (setup-axis-rotation canvas camera controls)
      ;; Setup ResizeObserver on viewport-panel (parent) for responsive canvas sizing
      ;; Observing the parent catches resize from panel divider drag
      (let [viewport-panel (.-parentElement canvas)
            resize-observer (js/ResizeObserver.
                              (fn [_entries]
                                ;; Use requestAnimationFrame to ensure layout is updated
                                (js/requestAnimationFrame handle-resize)))]
        (.observe resize-observer viewport-panel)
        (reset! state {:scene scene
                       :camera camera
                       :camera-rig camera-rig
                       :world-group world-group
                       :highlight-group highlight-group
                       :renderer renderer
                       :controls controls
                       :grid grid
                       :axes axes
                       :canvas canvas
                       :resize-observer resize-observer})
        ;; Initial resize
        (handle-resize)
        (start-animation-loop)))))

(defn get-renderer
  "Return the current renderer (for XR integration)."
  []
  (:renderer @state))

(defn get-current-meshes
  "Return the current mesh data for export."
  []
  @current-meshes)

;; ============================================================
;; Face highlighting
;; ============================================================

(defn clear-highlights
  "Remove all highlight objects from the scene."
  []
  (when-let [{:keys [highlight-group]} @state]
    (doseq [^js obj @highlight-objects]
      (.remove ^js highlight-group obj)
      (when-let [geom (.-geometry obj)]
        (.dispose geom))
      (when-let [mat (.-material obj)]
        (.dispose mat)))
    (reset! highlight-objects [])))

(defn- compute-triangle-normal
  "Compute the normal of a triangle from three vertices."
  [[x0 y0 z0] [x1 y1 z1] [x2 y2 z2]]
  (let [;; Edge vectors
        e1x (- x1 x0) e1y (- y1 y0) e1z (- z1 z0)
        e2x (- x2 x0) e2y (- y2 y0) e2z (- z2 z0)
        ;; Cross product
        nx (- (* e1y e2z) (* e1z e2y))
        ny (- (* e1z e2x) (* e1x e2z))
        nz (- (* e1x e2y) (* e1y e2x))
        ;; Normalize
        len (js/Math.sqrt (+ (* nx nx) (* ny ny) (* nz nz)))]
    (if (> len 0.0001)
      [(/ nx len) (/ ny len) (/ nz len)]
      [0 1 0])))  ; fallback

(defn- offset-vertex-by-normal
  "Offset a vertex along a normal direction."
  [[x y z] [nx ny nz] offset]
  [(+ x (* nx offset))
   (+ y (* ny offset))
   (+ z (* nz offset))])

(defn- create-face-highlight-mesh
  "Create a Three.js mesh for highlighting specific triangles of a mesh."
  [mesh-data triangles color]
  (let [vertices (:vertices mesh-data)
        n-verts (count vertices)
        offset 0.5  ; Offset along normal to prevent z-fighting
        ;; Collect vertices for the triangles to highlight, offset along normal
        face-verts (mapcat (fn [[i0 i1 i2]]
                             (when (and (< i0 n-verts) (< i1 n-verts) (< i2 n-verts))
                               (let [v0 (nth vertices i0 [0 0 0])
                                     v1 (nth vertices i1 [0 0 0])
                                     v2 (nth vertices i2 [0 0 0])
                                     normal (compute-triangle-normal v0 v1 v2)]
                                 [(offset-vertex-by-normal v0 normal offset)
                                  (offset-vertex-by-normal v1 normal offset)
                                  (offset-vertex-by-normal v2 normal offset)])))
                           triangles)
        flat-coords (mapcat identity face-verts)
        geom (THREE/BufferGeometry.)
        positions (js/Float32Array. (clj->js flat-coords))
        material (create-highlight-material color)]
    (.setAttribute geom "position" (THREE/BufferAttribute. positions 3))
    (.computeVertexNormals geom)
    (THREE/Mesh. geom material)))

(defn highlight-face
  "Highlight a specific face of a mesh.
   mesh-data: the mesh data map with :vertices, :faces, :face-groups
   face-id: the face identifier (keyword like :top, :bottom, etc.)
   color: optional hex color (default 0xff6600 orange)
   Returns true if face was found and highlighted."
  ([mesh-data face-id] (highlight-face mesh-data face-id 0xff6600))
  ([mesh-data face-id color]
   (when-let [{:keys [highlight-group]} @state]
     (when-let [triangles (get-in mesh-data [:face-groups face-id])]
       (let [highlight-mesh (create-face-highlight-mesh mesh-data triangles color)]
         (.add highlight-group highlight-mesh)
         (swap! highlight-objects conj highlight-mesh)
         true)))))

(defn flash-face
  "Temporarily highlight a face, then remove after duration.
   mesh-data: the mesh data map
   face-id: the face identifier
   duration-ms: how long to show highlight (default 2000ms)
   color: optional hex color (default 0xff6600 orange)"
  ([mesh-data face-id] (flash-face mesh-data face-id 2000 0xff6600))
  ([mesh-data face-id duration-ms] (flash-face mesh-data face-id duration-ms 0xff6600))
  ([mesh-data face-id duration-ms color]
   (when (highlight-face mesh-data face-id color)
     ;; Schedule removal
     (js/setTimeout
      (fn []
        ;; Remove only the most recently added highlight
        (when-let [{:keys [highlight-group]} @state]
          (when-let [^js obj (last @highlight-objects)]
            (.remove highlight-group obj)
            (when-let [geom (.-geometry obj)]
              (.dispose geom))
            (when-let [mat (.-material obj)]
              (.dispose mat))
            (swap! highlight-objects pop))))
      duration-ms)
     true)))

(defn fit-camera
  "Fit camera to current visible geometry."
  []
  (when-let [{:keys [camera controls]} @state]
    (let [meshes @current-meshes
          all-points (mapcat :vertices meshes)]
      (when (seq all-points)
        (let [xs (map first all-points)
              ys (map second all-points)
              zs (map #(nth % 2) all-points)
              min-x (apply min xs) max-x (apply max xs)
              min-y (apply min ys) max-y (apply max ys)
              min-z (apply min zs) max-z (apply max zs)
              center-x (/ (+ min-x max-x) 2)
              center-y (/ (+ min-y max-y) 2)
              center-z (/ (+ min-z max-z) 2)
              size (max (- max-x min-x) (- max-y min-y) (- max-z min-z) 10)
              dist (* size 2)]
          (.set (.-target controls) center-x center-y center-z)
          (.set (.-position camera)
                (+ center-x dist)
                (+ center-y dist)
                (+ center-z dist))
          (.update controls)
          true)))))

(defn capture-screenshot-blob
  "Capture the current viewport as a PNG Blob. Forces a render before capture.
   Returns a Promise<Blob>."
  []
  (js/Promise.
    (fn [resolve reject]
      (if-let [{:keys [renderer scene camera]} @state]
        (do (.render ^js renderer ^js scene ^js camera)
            (.toBlob ^js (.-domElement ^js renderer)
                     (fn [blob] (resolve blob))
                     "image/png"))
        (reject (js/Error. "No renderer"))))))

;; ============================================================
;; Grid/Axes visibility toggles
;; ============================================================

(defn toggle-grid
  "Toggle grid visibility. Returns new visibility state."
  []
  (when-let [{:keys [grid]} @state]
    (let [new-visible (swap! grid-visible not)]
      (set! (.-visible grid) new-visible)
      new-visible)))

(defn toggle-axes
  "Toggle axes visibility. Returns new visibility state."
  []
  (when-let [{:keys [axes]} @state]
    (let [new-visible (swap! axes-visible not)]
      (set! (.-visible axes) new-visible)
      new-visible)))

(defn grid-visible?
  "Return current grid visibility state."
  []
  @grid-visible)

(defn axes-visible?
  "Return current axes visibility state."
  []
  @axes-visible)

(defn toggle-lines
  "Toggle construction lines visibility. Returns new visibility state."
  []
  (let [new-visible (swap! lines-visible not)]
    (when-let [line-obj @lines-object]
      (set! (.-visible line-obj) new-visible))
    new-visible))

(defn lines-visible?
  "Return current lines visibility state."
  []
  @lines-visible)

(defn set-lines-visible
  "Set construction lines visibility explicitly."
  [visible?]
  (reset! lines-visible visible?)
  (when-let [line-obj @lines-object]
    (set! (.-visible line-obj) visible?)))

(defn reset-camera
  "Reset camera to default position looking at origin."
  []
  (when-let [{:keys [camera controls]} @state]
    (let [^js camera camera
          ^js controls controls]
      (.set (.-up camera) 0 0 1)
      (.set (.-position camera) 100 100 100)
      (.set (.-target controls) 0 0 0)
      (.update controls))))

;; ============================================================
;; Turtle indicator visibility and updates
;; ============================================================

(defn toggle-turtle
  "Toggle turtle indicator visibility. Returns new visibility state."
  []
  (when-let [indicator @turtle-indicator]
    (let [new-visible (swap! turtle-visible not)]
      (set! (.-visible indicator) new-visible)
      new-visible)))

(defn turtle-visible?
  "Return current turtle indicator visibility state."
  []
  @turtle-visible)

(defn set-turtle-visible
  "Set turtle indicator visibility explicitly."
  [visible?]
  (reset! turtle-visible visible?)
  (when-let [indicator @turtle-indicator]
    (set! (.-visible indicator) visible?)))

;; ============================================================
;; Face normals visibility
;; ============================================================

(defn toggle-normals
  "Toggle face normals visualization. Returns new visibility state."
  []
  (let [new-visible (swap! normals-visible not)]
    (if new-visible
      ;; Turning on - recreate normals from current meshes
      (when-let [{:keys [world-group]} @state]
        (update-normals-display world-group @current-meshes))
      ;; Turning off - remove normals object
      (when-let [{:keys [world-group]} @state]
        (when-let [^js obj @normals-object]
          (.remove world-group obj)
          (when-let [geom (.-geometry obj)]
            (.dispose geom))
          (when-let [mat (.-material obj)]
            (.dispose mat))
          (reset! normals-object nil))))
    new-visible))

(defn normals-visible?
  "Return current normals visibility state."
  []
  @normals-visible)

(defn set-normals-visible
  "Set normals visibility explicitly."
  [visible?]
  (reset! normals-visible visible?)
  (when-let [{:keys [world-group]} @state]
    (if visible?
      (update-normals-display world-group @current-meshes)
      (when-let [obj @normals-object]
        (.remove world-group obj)
        (reset! normals-object nil)))))

;; ============================================================
;; Stamp surface visibility
;; ============================================================

(defn- dispose-stamps-object!
  "Remove and dispose stamps group from scene."
  [world-group]
  (when-let [^js obj @stamps-object]
    (.remove world-group obj)
    (.traverse obj (fn [^js child]
                     (when-let [geom (.-geometry child)]
                       (.dispose geom))
                     (when-let [mat (.-material child)]
                       (.dispose mat))))
    (reset! stamps-object nil)))

(defn toggle-stamps
  "Toggle stamp surfaces visibility. Returns new visibility state."
  []
  (let [new-visible (swap! stamps-visible not)]
    (if new-visible
      (when-let [{:keys [world-group]} @state]
        (update-stamps-display world-group @current-stamps))
      (when-let [{:keys [world-group]} @state]
        (dispose-stamps-object! world-group)))
    new-visible))

(defn stamps-visible?
  "Return current stamps visibility state."
  []
  @stamps-visible)

(defn set-stamps-visible
  "Set stamps visibility explicitly."
  [visible?]
  (reset! stamps-visible visible?)
  (when-let [{:keys [world-group]} @state]
    (if visible?
      (update-stamps-display world-group @current-stamps)
      (dispose-stamps-object! world-group))))

(defn show-loading!
  "Show loading overlay on viewport."
  []
  (when-let [el (js/document.getElementById "loading-overlay")]
    (.remove (.-classList el) "hidden")))

(defn hide-loading!
  "Hide loading overlay on viewport."
  []
  (when-let [el (js/document.getElementById "loading-overlay")]
    (.add (.-classList el) "hidden")))

(defn update-turtle-pose
  "Update turtle indicator with new pose from REPL evaluation.
   pose is {:position [x y z] :heading [x y z] :up [x y z]}"
  [pose]
  (when pose
    (reset! turtle-pose pose)
    (when-let [indicator @turtle-indicator]
      (update-turtle-indicator-pose indicator pose))))

;; ============================================================
;; Panel content updates
;; ============================================================

(defn update-panel-text
  "Update the text content of a panel by name.
   panel-data should include :name, :width, :height, :content, :style."
  [name panel-data]
  (when-let [panel-obj (get @panel-objects name)]
    (update-panel-content panel-obj panel-data)))

(defn dispose
  "Clean up Three.js resources."
  []
  (when-let [{:keys [renderer controls resize-observer]} @state]
    (clear-highlights)
    (when resize-observer
      (.disconnect resize-observer))
    (.dispose ^js controls)
    (.dispose ^js renderer)
    (reset! state nil)))
