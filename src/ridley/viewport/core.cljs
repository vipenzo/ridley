(ns ridley.viewport.core
  "Three.js viewport for rendering turtle geometry."
  (:require ["three" :as THREE]
            ["three/examples/jsm/controls/OrbitControls.js" :refer [OrbitControls]]
            [ridley.viewport.xr :as xr]))

(defonce ^:private state (atom nil))

;; Track current mesh data for export
(defonce ^:private current-meshes (atom []))

(defn- create-scene []
  (let [scene (THREE/Scene.)]
    (set! (.-background scene) (THREE/Color. 0x252526))
    scene))

(defn- create-camera [width height]
  (let [camera (THREE/PerspectiveCamera. 60 (/ width height) 0.1 10000)]
    (.set (.-position camera) 100 100 100)
    (.lookAt camera 0 0 0)
    camera))

(defn- create-renderer [canvas]
  (let [renderer (THREE/WebGLRenderer. #js {:canvas canvas :antialias true})]
    (.setPixelRatio renderer js/window.devicePixelRatio)
    renderer))

(defn- create-controls [camera renderer]
  (let [controls (OrbitControls. camera (.-domElement renderer))]
    (set! (.-enableDamping controls) true)
    (set! (.-dampingFactor controls) 0.05)
    controls))

(defn- add-grid [parent]
  (let [grid (THREE/GridHelper. 200 20 0x444444 0x333333)]
    (.rotateX grid (/ js/Math.PI 2)) ; XY plane instead of XZ
    (.add parent grid)))

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
          sprite (THREE/Sprite. material)]
      (set! (.-x (.-scale sprite)) 8)
      (set! (.-y (.-scale sprite)) 8)
      sprite)))

(defn- add-axes [parent]
  (let [axes (THREE/AxesHelper. 50)
        ;; Add axis labels
        ;; Three.js AxesHelper: Red = X, Green = Y, Blue = Z
        label-x (create-text-sprite "X" "#ff4444")
        label-y (create-text-sprite "Y" "#44ff44")
        label-z (create-text-sprite "Z" "#4444ff")]
    ;; Position labels at end of each axis
    (.set (.-position label-x) 58 0 0)
    (.set (.-position label-y) 0 58 0)
    (.set (.-position label-z) 0 0 58)
    (.add parent axes)
    (.add parent label-x)
    (.add parent label-y)
    (.add parent label-z)))

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

(defn- create-line-material []
  (THREE/LineBasicMaterial. #js {:color 0x00ff88 :linewidth 2}))

(defn- create-mesh-material []
  (THREE/MeshStandardMaterial. #js {:color 0x00aaff
                                     :metalness 0.3
                                     :roughness 0.7
                                     :side THREE/FrontSide
                                     :flatShading true}))

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

(defn- geometry-to-points
  "Convert turtle geometry segments to Three.js points."
  [geometry]
  (let [points #js []]
    (doseq [{:keys [from to]} geometry]
      (let [[x1 y1 z1] from
            [x2 y2 z2] to]
        (.push points (THREE/Vector3. x1 y1 z1))
        (.push points (THREE/Vector3. x2 y2 z2))))
    points))

(defn- create-line-segments
  "Create Three.js line segments from turtle geometry."
  [geometry]
  (when (seq geometry)
    (let [points (geometry-to-points geometry)
          buffer-geom (THREE/BufferGeometry.)]
      (.setFromPoints buffer-geom points)
      (THREE/LineSegments. buffer-geom (create-line-material)))))

(defn- create-three-mesh
  "Create Three.js mesh from vertices and faces."
  [{:keys [vertices faces]}]
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
        material (create-mesh-material)]
    (.setAttribute geom "position" (THREE/BufferAttribute. positions 3))
    ;; With flatShading: true, Three.js computes face normals automatically
    ;; We still call computeVertexNormals for compatibility, but flatShading overrides
    (.computeVertexNormals geom)
    (THREE/Mesh. geom material)))

(defn- clear-geometry
  "Remove all user geometry objects from world-group, keeping grid, axes, and highlight-group."
  [world-group highlight-group]
  (let [to-remove (filterv #(and (or (= (.-type %) "LineSegments")
                                      (= (.-type %) "Mesh"))
                                  ;; Keep grid (GridHelper is also a LineSegments)
                                  (not (instance? THREE/GridHelper %))
                                  ;; Keep highlight group
                                  (not (identical? % highlight-group)))
                           (.-children world-group))]
    (doseq [obj to-remove]
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

(defn update-scene
  "Update viewport with lines and meshes.
   Options:
     :reset-camera? - if true (default), fit camera to geometry"
  [{:keys [lines meshes reset-camera?] :or {reset-camera? true}}]
  ;; Store meshes for export
  (reset! current-meshes (vec meshes))
  (when-let [{:keys [world-group highlight-group camera controls]} @state]
    (clear-geometry world-group highlight-group)
    ;; Add line segments to world-group
    (when (seq lines)
      (when-let [line-obj (create-line-segments lines)]
        (.add world-group line-obj)))
    ;; Add meshes to world-group
    (doseq [mesh-data meshes]
      (let [mesh (create-three-mesh mesh-data)]
        (.add world-group mesh)))
    ;; Fit camera to all geometry (only if reset-camera? is true)
    (when reset-camera?
      (let [all-points (collect-all-points lines meshes)]
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
    (if (xr/xr-presenting? renderer)
      ;; VR mode: update controller input, pass XR frame for pose data
      (xr/update-controller xr-frame renderer)
      ;; Desktop mode: update OrbitControls
      (.update controls))
    (.render renderer scene camera)))

(defn- start-animation-loop
  "Start the render loop using setAnimationLoop for XR compatibility."
  []
  (when-let [{:keys [renderer]} @state]
    (.setAnimationLoop renderer render-frame)))

(defn handle-resize
  "Handle viewport resize - call when panel dimensions change."
  []
  (when-let [{:keys [renderer camera canvas]} @state]
    (let [panel (.-parentElement canvas)
          width (.-clientWidth panel)
          height (.-clientHeight panel)]
      (.setSize renderer width height)
      (set! (.-aspect camera) (/ width height))
      (.updateProjectionMatrix camera))))

(defn init
  "Initialize Three.js viewport on given canvas element."
  [canvas]
  (let [panel (.-parentElement canvas)
        width (.-clientWidth panel)
        height (.-clientHeight panel)
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
    (add-grid world-group)
    (add-axes world-group)
    ;; Lights stay in scene (not affected by world rotation)
    (add-lights scene)
    ;; Enable WebXR on renderer
    (xr/enable-xr renderer)
    ;; Setup VR controller (pass world-group for rotation)
    (xr/setup-controller renderer scene camera-rig camera world-group)
    (reset! state {:scene scene
                   :camera camera
                   :camera-rig camera-rig
                   :world-group world-group
                   :highlight-group highlight-group
                   :renderer renderer
                   :controls controls
                   :canvas canvas})
    (.addEventListener js/window "resize" handle-resize)
    (start-animation-loop)))

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
    (doseq [obj @highlight-objects]
      (.remove highlight-group obj)
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
          (when-let [obj (last @highlight-objects)]
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

(defn dispose
  "Clean up Three.js resources."
  []
  (when-let [{:keys [renderer controls]} @state]
    (clear-highlights)
    (.removeEventListener js/window "resize" handle-resize)
    (.dispose controls)
    (.dispose renderer)
    (reset! state nil)))
