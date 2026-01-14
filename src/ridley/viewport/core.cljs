(ns ridley.viewport.core
  "Three.js viewport for rendering turtle geometry."
  (:require ["three" :as THREE]
            ["three/examples/jsm/controls/OrbitControls.js" :refer [OrbitControls]]))

(defonce ^:private state (atom nil))

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

(defn- add-grid [scene]
  (let [grid (THREE/GridHelper. 200 20 0x444444 0x333333)]
    (.rotateX grid (/ js/Math.PI 2)) ; XY plane instead of XZ
    (.add scene grid)))

(defn- add-axes [scene]
  (let [axes (THREE/AxesHelper. 50)]
    (.add scene axes)))

(defn- create-line-material []
  (THREE/LineBasicMaterial. #js {:color 0x00ff88 :linewidth 2}))

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

(defn- clear-geometry
  "Remove all geometry objects from scene, keeping grid and axes."
  [scene]
  (let [to-remove (filterv #(= (.-type %) "LineSegments")
                           (.-children scene))]
    (doseq [obj to-remove]
      (.remove scene obj)
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

(defn update-geometry
  "Update viewport with new turtle geometry."
  [geometry]
  (when-let [{:keys [scene camera controls]} @state]
    (clear-geometry scene)
    (when-let [lines (create-line-segments geometry)]
      (.add scene lines))
    (fit-camera-to-geometry camera controls geometry)))

(defn- animate []
  (when-let [{:keys [renderer scene camera controls]} @state]
    (.update controls)
    (.render renderer scene camera)
    (js/requestAnimationFrame animate)))

(defn- handle-resize []
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
        controls (create-controls camera renderer)]
    (.setSize renderer width height)
    (add-grid scene)
    (add-axes scene)
    (reset! state {:scene scene
                   :camera camera
                   :renderer renderer
                   :controls controls
                   :canvas canvas})
    (.addEventListener js/window "resize" handle-resize)
    (animate)))

(defn dispose
  "Clean up Three.js resources."
  []
  (when-let [{:keys [renderer controls]} @state]
    (.removeEventListener js/window "resize" handle-resize)
    (.dispose controls)
    (.dispose renderer)
    (reset! state nil)))
