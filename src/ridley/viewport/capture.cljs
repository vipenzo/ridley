(ns ridley.viewport.capture
  "Offscreen view capture for describe/AI and PNG export.
   Renders the scene from standard and custom camera angles using
   WebGLRenderTarget on the existing renderer."
  (:require ["three" :as THREE]
            ["jszip" :as JSZip]
            [ridley.viewport.core :as viewport]
            [ridley.scene.registry :as registry]
            [ridley.manifold.core :as manifold]))

;; ============================================================
;; Module state — reusable across captures
;; ============================================================

(defonce ^:private render-target (atom nil))
(defonce ^:private capture-canvas (atom nil))

;; ============================================================
;; Standard orthographic views (Z-up coordinate system)
;; ============================================================

(def ^:private ortho-views
  {:front  {:dir [0  1  0] :up [0 0 1]}   ; +Y looking -Y
   :back   {:dir [0 -1  0] :up [0 0 1]}   ; -Y looking +Y
   :left   {:dir [-1 0  0] :up [0 0 1]}   ; -X looking +X
   :right  {:dir [1  0  0] :up [0 0 1]}   ; +X looking -X
   :top    {:dir [0  0  1] :up [0 -1 0]}  ; +Z looking -Z
   :bottom {:dir [0  0 -1] :up [0  1 0]}  ; -Z looking +Z
   })

;; ============================================================
;; Bounding box computation
;; ============================================================

(defn- raw-arrays-bbox
  "Compute bounding box from a Float32Array of xyz triples.
   Returns [[min-x min-y min-z] [max-x max-y max-z]]."
  [^js vert-props]
  (let [n (.-length vert-props)]
    (when (pos? n)
      (loop [i 3
             min-x (aget vert-props 0) min-y (aget vert-props 1) min-z (aget vert-props 2)
             max-x min-x max-y min-y max-z min-z]
        (if (< i n)
          (let [x (aget vert-props i) y (aget vert-props (+ i 1)) z (aget vert-props (+ i 2))]
            (recur (+ i 3)
                   (min min-x x) (min min-y y) (min min-z z)
                   (max max-x x) (max max-y y) (max max-z z)))
          [[min-x min-y min-z] [max-x max-y max-z]])))))

(defn- mesh-bbox
  "Compute bounding box for a single Ridley mesh data map.
   Returns [[min-x min-y min-z] [max-x max-y max-z]] or nil."
  [mesh-data]
  (if-let [raw (::manifold/raw-arrays mesh-data)]
    (raw-arrays-bbox (:vert-props raw))
    (let [verts (:vertices mesh-data)]
      (when (seq verts)
        [[(apply min (map #(nth % 0) verts))
          (apply min (map #(nth % 1) verts))
          (apply min (map #(nth % 2) verts))]
         [(apply max (map #(nth % 0) verts))
          (apply max (map #(nth % 1) verts))
          (apply max (map #(nth % 2) verts))]]))))

(defn- combine-bboxes
  "Combine multiple [[min] [max]] bounding boxes into one."
  [bboxes]
  (when (seq bboxes)
    (let [mins (map first bboxes)
          maxs (map second bboxes)]
      [[(apply min (map #(nth % 0) mins))
        (apply min (map #(nth % 1) mins))
        (apply min (map #(nth % 2) mins))]
       [(apply max (map #(nth % 0) maxs))
        (apply max (map #(nth % 1) maxs))
        (apply max (map #(nth % 2) maxs))]])))

(defn- compute-meshes-bbox
  "Compute combined bounding box for a collection of Ridley mesh data.
   Returns {:min [x y z] :max [x y z] :center [x y z] :size [sx sy sz]} or nil."
  [meshes]
  (when-let [bbox (combine-bboxes (keep mesh-bbox meshes))]
    (let [[[min-x min-y min-z] [max-x max-y max-z]] bbox]
      {:min [min-x min-y min-z]
       :max [max-x max-y max-z]
       :center [(/ (+ min-x max-x) 2) (/ (+ min-y max-y) 2) (/ (+ min-z max-z) 2)]
       :size [(- max-x min-x) (- max-y min-y) (- max-z min-z)]})))

;; ============================================================
;; Offscreen scene construction
;; ============================================================

(defn- create-capture-mesh
  "Create a Three.js Mesh from Ridley mesh data with a given material.
   Mirrors the fast/slow path logic from viewport/core."
  [mesh-data ^js material]
  (let [raw (::manifold/raw-arrays mesh-data)]
    (if (and raw (= 3 (:num-prop raw)))
      ;; Fast path: indexed geometry from typed arrays
      (let [geom (THREE/BufferGeometry.)]
        (.setAttribute geom "position"
                       (THREE/BufferAttribute. (:vert-props raw) 3))
        (.setIndex geom (THREE/BufferAttribute. (:tri-verts raw) 1))
        (.computeVertexNormals geom)
        (THREE/Mesh. geom material))
      ;; Slow path: de-index from CLJS vectors
      (let [{:keys [vertices faces]} mesh-data
            n-verts (count vertices)
            face-verts (mapcat (fn [[i0 i1 i2]]
                                 (when (and (< i0 n-verts) (< i1 n-verts) (< i2 n-verts))
                                   [(nth vertices i0 [0 0 0])
                                    (nth vertices i1 [0 0 0])
                                    (nth vertices i2 [0 0 0])]))
                               faces)
            positions (js/Float32Array. (clj->js (mapcat identity face-verts)))
            geom (THREE/BufferGeometry.)]
        (.setAttribute geom "position" (THREE/BufferAttribute. positions 3))
        (.computeVertexNormals geom)
        (THREE/Mesh. geom material)))))

(def ^:private capture-palette
  "Bright, high-contrast colors for AI capture views.
   Chosen for maximum mutual distinguishability on white background."
  [0x2196F3   ; blue
   0xF44336   ; red
   0x4CAF50   ; green
   0xFF9800   ; orange
   0x9C27B0   ; purple
   0x00BCD4   ; cyan
   0xFFEB3B   ; yellow
   0xE91E63]) ; pink

(defn- mesh-material
  "Create a MeshStandardMaterial with a bright capture-palette color by index.
   Ignores user colors — capture views prioritize AI readability.
   Uses shading to convey 3D form (concavity, depth, curvature)."
  [_mesh-data idx]
  (THREE/MeshStandardMaterial.
    #js {:color (nth capture-palette (mod idx (count capture-palette)))
         :metalness 0.0
         :roughness 0.6
         :flatShading false
         :side THREE/DoubleSide}))

(defn- create-capture-scene
  "Build a minimal Scene for capture: white background, even lighting,
   per-object colors with edge wireframes. No grid/axes/turtle/panels."
  [meshes]
  (let [scene (THREE/Scene.)
        _ (set! (.-background scene) (THREE/Color. 0xffffff))
        ;; Strong ambient + directional from all sides for flat, readable renders
        ambient (THREE/AmbientLight. 0xffffff 0.6)
        main (THREE/DirectionalLight. 0xffffff 0.5)
        fill (THREE/DirectionalLight. 0xffffff 0.4)
        back (THREE/DirectionalLight. 0xffffff 0.4)
        top  (THREE/DirectionalLight. 0xffffff 0.3)
        bottom (THREE/DirectionalLight. 0xffffff 0.3)]
    (.set (.-position main) 100 150 100)
    (.set (.-position fill) -100 -80 80)
    (.set (.-position back) -50 50 -100)
    (.set (.-position top) 0 0 200)
    (.set (.-position bottom) 0 0 -200)
    (.add scene ambient)
    (.add scene main)
    (.add scene fill)
    (.add scene back)
    (.add scene top)
    (.add scene bottom)
    (doseq [[idx mesh-data] (map-indexed vector meshes)]
      (let [mat (mesh-material mesh-data idx)
            ^js three-mesh (create-capture-mesh mesh-data mat)]
        (.add scene three-mesh)))
    scene))

(defn- dispose-capture-scene!
  "Dispose all geometry and materials in a capture scene."
  [^js scene]
  (.traverse scene
    (fn [^js obj]
      (when-let [geom (.-geometry obj)]
        (.dispose geom))
      (when-let [mat (.-material obj)]
        (when-not (array? mat)
          (.dispose mat))))))

;; ============================================================
;; WebGLRenderTarget and pixel readback
;; ============================================================

(defn- ensure-render-target!
  "Ensure a WebGLRenderTarget of the given size exists. Returns it."
  [width height]
  (let [existing @render-target]
    (if (and existing
             (= width (.. existing -width))
             (= height (.. existing -height)))
      existing
      (do
        (when existing (.dispose existing))
        (let [rt (THREE/WebGLRenderTarget. width height
                   #js {:minFilter THREE/LinearFilter
                        :magFilter THREE/LinearFilter
                        :format THREE/RGBAFormat
                        :type THREE/UnsignedByteType})]
          (reset! render-target rt)
          rt)))))

(defn- ensure-capture-canvas!
  "Ensure a hidden canvas element for pixel readback. Returns it."
  [width height]
  (let [existing @capture-canvas]
    (if (and existing
             (= width (.-width existing))
             (= height (.-height existing)))
      existing
      (let [c (.createElement js/document "canvas")]
        (set! (.-width c) width)
        (set! (.-height c) height)
        (reset! capture-canvas c)
        c))))

(defn- render-target-to-data-url
  "Read pixels from a WebGLRenderTarget and return a base64 PNG data URL.
   Handles the Y-flip required by WebGL readback."
  [^js renderer ^js rt width height]
  (let [buffer (js/Uint8Array. (* width height 4))]
    (.readRenderTargetPixels renderer rt 0 0 width height buffer)
    (let [canvas (ensure-capture-canvas! width height)
          ctx (.getContext canvas "2d")
          img-data (.createImageData ctx width height)
          ^js pixels (.-data img-data)
          row-bytes (* width 4)]
      ;; Copy rows in reverse order (Y-flip: WebGL bottom-to-top → canvas top-to-bottom)
      (dotimes [y height]
        (let [src-offset (* (- height 1 y) row-bytes)
              dst-offset (* y row-bytes)]
          (.set (.subarray pixels dst-offset (+ dst-offset row-bytes))
                (.subarray buffer src-offset (+ src-offset row-bytes)))))
      (.putImageData ctx img-data 0 0)
      (.toDataURL canvas "image/png"))))

;; ============================================================
;; Camera setup
;; ============================================================

(defn- setup-ortho-camera
  "Create an OrthographicCamera for a standard view with tight frustum."
  [view-key bbox]
  (let [{:keys [dir up]} (get ortho-views view-key)
        [dx dy dz] dir
        [ux uy uz] up
        [cx cy cz] (:center bbox)
        [[min-x min-y min-z] [max-x max-y max-z]] [(:min bbox) (:max bbox)]
        ;; Camera right axis = cross(up, -dir)
        rx (- (* uy (- dz)) (* uz (- dy)))
        ry (- (* uz (- dx)) (* ux (- dz)))
        rz (- (* ux (- dy)) (* uy (- dx)))
        ;; Project 8 bbox corners onto camera local right/up axes
        corners (for [x [min-x max-x]
                      y [min-y max-y]
                      z [min-z max-z]]
                  [(- x cx) (- y cy) (- z cz)])
        projs (map (fn [[px py pz]]
                     {:u (+ (* px rx) (* py ry) (* pz rz))
                      :v (+ (* px ux) (* py uy) (* pz uz))})
                   corners)
        min-u (apply min (map :u projs))
        max-u (apply max (map :u projs))
        min-v (apply min (map :v projs))
        max-v (apply max (map :v projs))
        ;; 15% padding, then square up for 1:1 output
        pad-u (* (- max-u min-u) 0.15)
        pad-v (* (- max-v min-v) 0.15)
        range-u (+ (- max-u min-u) (* 2 pad-u))
        range-v (+ (- max-v min-v) (* 2 pad-v))
        half (/ (max range-u range-v 0.1) 2)
        camera (THREE/OrthographicCamera. (- half) half half (- half) 0.1 10000)]
    (.set (.-up camera) ux uy uz)
    (let [dist (* (max (- max-x min-x) (- max-y min-y) (- max-z min-z) 1) 2)]
      (.set (.-position camera)
            (+ cx (* dx dist))
            (+ cy (* dy dist))
            (+ cz (* dz dist))))
    (.lookAt camera cx cy cz)
    (.updateProjectionMatrix camera)
    camera))

(defn- setup-perspective-camera
  "Create a PerspectiveCamera for the 3/4 isometric-style view."
  [bbox width height]
  (let [[cx cy cz] (:center bbox)
        [sx sy sz] (:size bbox)
        ;; 45deg from front-right, slightly elevated
        dx 1 dy 1 dz 0.8
        len (js/Math.sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
        dx (/ dx len) dy (/ dy len) dz (/ dz len)
        size (max sx sy sz 10)
        dist (* size 2.5)
        camera (THREE/PerspectiveCamera. 45 (/ width height) 0.1 10000)]
    (.set (.-up camera) 0 0 1)
    (.set (.-position camera)
          (+ cx (* dx dist))
          (+ cy (* dy dist))
          (+ cz (* dz dist)))
    (.lookAt camera cx cy cz)
    (.updateProjectionMatrix camera)
    camera))

(defn- setup-direction-camera
  "Create a PerspectiveCamera from a direction vector [x y z],
   scaled to fit the geometry."
  [dir-vec bbox width height]
  (let [[dx dy dz] dir-vec
        len (js/Math.sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
    (if (< len 0.001)
      (setup-perspective-camera bbox width height)
      (let [dx (/ dx len) dy (/ dy len) dz (/ dz len)
            [cx cy cz] (:center bbox)
            [sx sy sz] (:size bbox)
            dist (* (max sx sy sz 10) 2.5)
            camera (THREE/PerspectiveCamera. 45 (/ width height) 0.1 10000)]
        (.set (.-up camera) 0 0 1)
        (.set (.-position camera)
              (+ cx (* dx dist))
              (+ cy (* dy dist))
              (+ cz (* dz dist)))
        (.lookAt camera cx cy cz)
        (.updateProjectionMatrix camera)
        camera))))

;; ============================================================
;; Mesh resolution
;; ============================================================

(defn- resolve-meshes
  "Resolve the target to a vector of Ridley mesh data maps."
  [target]
  (if target
    (if-let [m (registry/get-mesh target)]
      [m]
      (throw (js/Error. (str "Object " target " not found"))))
    (let [meshes (registry/visible-meshes)]
      (when (empty? meshes)
        (throw (js/Error. "No geometry to render. Create some shapes first.")))
      meshes)))

;; ============================================================
;; Public API
;; ============================================================

(defn ^:export render-view
  "Render a view of the scene to a data URL (base64 PNG).
   camera-spec is one of:
   - :front, :back, :left, :right, :top, :bottom (orthographic)
   - :perspective (default 3/4 view)
   - [x y z] (custom direction vector, looking at center)

   Options:
   - :width, :height — image dimensions (default 512x512)
   - :target — keyword name of specific object, or nil for all visible"
  [camera-spec & {:keys [width height target]
                  :or {width 512 height 512}}]
  (let [{:keys [renderer]} (viewport/get-capture-context)]
    (when-not renderer
      (throw (js/Error. "No renderer available")))
    (let [meshes (resolve-meshes target)
          bbox (compute-meshes-bbox meshes)
          _ (when-not bbox
              (throw (js/Error. "Could not compute geometry bounds")))
          capture-scene (create-capture-scene meshes)
          camera (cond
                   (keyword? camera-spec)
                   (if (= camera-spec :perspective)
                     (setup-perspective-camera bbox width height)
                     (if (get ortho-views camera-spec)
                       (setup-ortho-camera camera-spec bbox)
                       (throw (js/Error. (str "Unknown view: " (name camera-spec))))))

                   (vector? camera-spec)
                   (setup-direction-camera camera-spec bbox width height)

                   :else
                   (throw (js/Error. (str "Invalid camera-spec: " camera-spec))))
          rt (ensure-render-target! width height)]
      (.setRenderTarget ^js renderer rt)
      (.render ^js renderer capture-scene camera)
      (.setRenderTarget ^js renderer nil)
      (let [data-url (render-target-to-data-url renderer rt width height)]
        (dispose-capture-scene! capture-scene)
        data-url))))

(defn ^:export render-all-views
  "Render all 6 orthographic views + 1 perspective view.
   Returns a map {:front data-url :back data-url ... :perspective data-url}."
  [& {:keys [width height target] :or {width 512 height 512}}]
  (into {}
    (map (fn [k] [k (render-view k :width width :height height :target target)])
         [:front :back :left :right :top :bottom :perspective])))

;; ============================================================
;; save-views — ZIP download
;; ============================================================

(defn- data-url-to-uint8array
  "Convert a base64 data URL to a Uint8Array for JSZip."
  [data-url]
  (let [base64 (.split data-url ",")
        raw (js/atob (aget base64 1))
        n (.-length raw)
        arr (js/Uint8Array. n)]
    (dotimes [i n]
      (aset arr i (.charCodeAt raw i)))
    arr))

(defn ^:export save-views
  "Save all 6+1 views as a ZIP archive (browser download).
   Returns a Promise that resolves when the download is triggered."
  [& {:keys [width height target prefix]
      :or {width 512 height 512 prefix "views"}}]
  (let [views (render-all-views :width width :height height :target target)
        zip (JSZip.)]
    (doseq [[view-name data-url] views]
      (.file zip (str (name view-name) ".png") (data-url-to-uint8array data-url)))
    (-> (.generateAsync zip #js {:type "blob"})
        (.then (fn [blob]
                 (let [url (.createObjectURL js/URL blob)
                       link (.createElement js/document "a")]
                   (set! (.-href link) url)
                   (set! (.-download link) (str prefix ".zip"))
                   (.click link)
                   (.revokeObjectURL js/URL url)
                   (str "Saved " (count views) " views as " prefix ".zip")))))))

;; ============================================================
;; save-image — single PNG download
;; ============================================================

(defn ^:export save-image
  "Download a data URL as a PNG file.
   Works with render-view, render-slice, or any base64 data URL.
   Usage: (save-image (render-slice :cup :z -15) \"cup-z15.png\")"
  [data-url filename]
  (let [link (.createElement js/document "a")]
    (set! (.-href link) data-url)
    (set! (.-download link) (or filename "image.png"))
    (.click link)
    (str "Saved " filename)))

;; ============================================================
;; Slice rendering
;; ============================================================

(def ^:private axis-planes
  "Axis-aligned slice plane definitions.
   Each maps axis keyword to {normal, right, up} vectors."
  {:z {:normal [0 0 1] :right [1 0 0] :up [0 1 0]}
   :x {:normal [1 0 0] :right [0 1 0] :up [0 0 1]}
   :y {:normal [0 1 0] :right [0 0 1] :up [1 0 0]}})

(defn- contours-bbox-2d
  "Compute 2D bounding box from slice contours (shapes with :points).
   Returns {:min [x y] :max [x y]} or nil."
  [shapes]
  (let [all-pts (mapcat (fn [s]
                          (concat (:points s)
                                  (mapcat identity (:holes s))))
                        shapes)]
    (when (seq all-pts)
      (let [xs (map first all-pts)
            ys (map second all-pts)]
        {:min [(apply min xs) (apply min ys)]
         :max [(apply max xs) (apply max ys)]}))))

(defn- create-contour-line
  "Create a THREE.Line from a closed contour (vector of [x y] points).
   Renders in the XY plane at Z=0."
  [points ^js material]
  (let [geom (THREE/BufferGeometry.)
        ;; Close the contour by repeating first point
        closed (conj (vec points) (first points))
        coords (js/Float32Array.
                 (clj->js (mapcat (fn [[x y]] [x y 0]) closed)))]
    (.setAttribute geom "position" (THREE/BufferAttribute. coords 3))
    (THREE/Line. geom material)))

(defn- create-slice-scene
  "Build an offscreen scene with 2D contour lines on white background.
   groups is a seq of {:shapes [...] :color hex-or-nil :idx n}."
  [groups]
  (let [scene (THREE/Scene.)
        _ (set! (.-background scene) (THREE/Color. 0xffffff))]
    (doseq [{:keys [shapes color idx]} groups]
      (let [c (nth capture-palette (mod idx (count capture-palette)))
            outer-mat (THREE/LineBasicMaterial. #js {:color c :linewidth 2})
            hole-mat  (THREE/LineBasicMaterial.
                        #js {:color c :linewidth 1 :opacity 0.5 :transparent true})]
        (doseq [shape shapes]
          (when (seq (:points shape))
            (.add scene (create-contour-line (:points shape) outer-mat)))
          (doseq [hole (:holes shape)]
            (when (seq hole)
              (.add scene (create-contour-line hole hole-mat)))))))
    scene))

(defn- setup-slice-camera
  "Create an orthographic camera for a 2D slice view (looking down Z)."
  [bbox-2d]
  (let [[min-x min-y] (:min bbox-2d)
        [max-x max-y] (:max bbox-2d)
        cx (/ (+ min-x max-x) 2)
        cy (/ (+ min-y max-y) 2)
        range-x (- max-x min-x)
        range-y (- max-y min-y)
        pad-x (* range-x 0.15)
        pad-y (* range-y 0.15)
        range-x (+ range-x (* 2 pad-x))
        range-y (+ range-y (* 2 pad-y))
        half (/ (max range-x range-y 0.1) 2)
        camera (THREE/OrthographicCamera. (- half) half half (- half) 0.1 100)]
    (.set (.-up camera) 0 1 0)
    (.set (.-position camera) cx cy 10)
    (.lookAt camera cx cy 0)
    (.updateProjectionMatrix camera)
    camera))

(defn- resolve-single-mesh
  "Resolve target to a single Ridley mesh data map."
  [target]
  (if (keyword? target)
    (or (registry/get-mesh target)
        (throw (js/Error. (str "Object " target " not found"))))
    (if (and (map? target) (:vertices target))
      target
      (throw (js/Error. "render-slice requires a specific mesh target (keyword or mesh)")))))

(defn- resolve-slice-groups
  "Resolve target to a seq of {:mesh mesh-data :idx n}.
   When target is nil, includes all visible meshes."
  [target]
  (if target
    (let [m (resolve-single-mesh target)]
      [{:mesh m :idx 0}])
    (let [names (registry/visible-names)]
      (when (empty? names)
        (throw (js/Error. "No geometry to slice. Create some shapes first.")))
      (map-indexed (fn [i nm]
                     {:mesh (registry/get-mesh nm) :idx i})
                   names))))

(defn ^:export render-slice
  "Render a cross-section of one or all meshes at an axis-aligned plane.
   Returns a data URL (base64 PNG) of the 2D contour outlines.

   target   — keyword name, mesh reference, or nil for all visible
   axis     — :x, :y, or :z
   position — float, position along axis

   Options:
   - :width, :height — image dimensions (default 512x512)"
  [target axis position & {:keys [width height]
                           :or {width 512 height 512}}]
  (let [{:keys [renderer]} (viewport/get-capture-context)]
    (when-not renderer
      (throw (js/Error. "No renderer available")))
    (let [plane (or (get axis-planes axis)
                    (throw (js/Error. (str "Invalid axis: " axis ". Use :x, :y, or :z"))))
          {:keys [normal right up]} plane
          [nx ny nz] normal
          point [(* nx position) (* ny position) (* nz position)]
          groups (resolve-slice-groups target)
          slice-groups (keep (fn [{:keys [mesh color idx]}]
                              (let [shapes (manifold/slice-at-plane mesh normal point right up)]
                                (when (seq shapes)
                                  {:shapes shapes :color color :idx idx})))
                            groups)
          all-shapes (mapcat :shapes slice-groups)]
      (if (empty? all-shapes)
        (throw (js/Error. (str "No cross-section at " (name axis) "=" position
                               ". Slice plane may be outside the object(s).")))
        (let [bbox-2d (contours-bbox-2d all-shapes)
              _ (when-not bbox-2d
                  (throw (js/Error. "Could not compute contour bounds")))
              slice-scene (create-slice-scene slice-groups)
              camera (setup-slice-camera bbox-2d)
              rt (ensure-render-target! width height)]
          (.setRenderTarget ^js renderer rt)
          (.render ^js renderer slice-scene camera)
          (.setRenderTarget ^js renderer nil)
          (let [data-url (render-target-to-data-url renderer rt width height)]
            (dispose-capture-scene! slice-scene)
            data-url))))))
