(ns ridley.viewport.inset
  "Picture-in-picture context inset — shared infra for the acquisition
   family's Vista contesto (dev-docs/brief-acquisition-views.md, Parte 1):
   'dove sono nell'oggetto' while working, without leaving focus. A second,
   small, non-interactive camera/renderer/scene mirrors the MAIN viewport
   camera's orientation every frame (quaternion copy — the same idiom
   viewport.core's billboard labels use for update-panels-billboard);
   content (a ghosted reference mesh + a highlighted current piece) is pushed
   by the owning session via set-content!, whenever it changes.

   Deliberately tool-agnostic (design doc: 'le viste sono del dato, non di
   uno strumento') — edit-mesh-split uses it today, edit-mesh-board will
   reuse it unchanged. Uses ONLY viewport.core's public API
   (register-frame-callback!/unregister-frame-callback!) — no new exports
   were needed there, and this ns never touches the main scene/renderer."
  (:require ["three" :as THREE]
            [ridley.viewport.core :as viewport]))

(defonce ^:private state (atom nil))

(def ^:private frame-callback-key ::inset)
(def ^:private canvas-w 220)
(def ^:private canvas-h 160)
(def ^:private fov-deg 50)
;; Same ghost convention edit-mesh-split validated (live feedback: a low-alpha
;; solid read blue-ish and blurred into other elements; a wireframe
;; unmistakably reads as reference, not live geometry) and the same "active"
;; accent used for the live cut-plane there.
(def ^:private ghost-color 0x999999)
(def ^:private highlight-color 0x66ccff)

;; ============================================================
;; Geometry — own tiny builder (no dependency on viewport.core's private
;; mesh-building fns): the same de-indexed vertices/faces -> BufferGeometry
;; "slow path" create-three-mesh uses.
;; ============================================================

(defn- mesh->geometry
  [{:keys [vertices faces]}]
  (let [n (count vertices)
        face-verts (mapcat (fn [[i0 i1 i2]]
                             (when (and (< i0 n) (< i1 n) (< i2 n))
                               [(nth vertices i0 [0 0 0])
                                (nth vertices i1 [0 0 0])
                                (nth vertices i2 [0 0 0])]))
                           faces)
        positions (js/Float32Array. (clj->js (mapcat identity face-verts)))
        geom (THREE/BufferGeometry.)]
    (.setAttribute geom "position" (THREE/BufferAttribute. positions 3))
    (.computeVertexNormals geom)
    geom))

(defn- ghost-object
  [mesh]
  (let [geom (mesh->geometry mesh)
        edges (THREE/EdgesGeometry. geom 30)
        mat (THREE/LineBasicMaterial. #js {:color ghost-color})
        obj (THREE/LineSegments. edges mat)]
    (.dispose geom)
    obj))

(defn- highlight-object
  [mesh]
  (THREE/Mesh. (mesh->geometry mesh)
               (THREE/MeshStandardMaterial. #js {:color highlight-color :metalness 0.2
                                                 :roughness 0.6 :flatShading true})))

(defn- framing-distance
  "Camera distance putting a sphere of `radius` fully in the vertical FOV,
   with a small margin so the ghost doesn't touch the edges."
  [radius]
  (let [half-fov (/ (* fov-deg (/ js/Math.PI 180.0)) 2)]
    (* 1.15 (/ radius (js/Math.sin half-fov)))))

(defn- bounds
  "[center radius] over mesh's vertices — the inset's one-time framing."
  [{:keys [vertices]}]
  (let [xs (map first vertices) ys (map second vertices) zs (map #(nth % 2) vertices)
        minx (reduce min xs) maxx (reduce max xs)
        miny (reduce min ys) maxy (reduce max ys)
        minz (reduce min zs) maxz (reduce max zs)]
    [[(/ (+ minx maxx) 2) (/ (+ miny maxy) 2) (/ (+ minz maxz) 2)]
     (* 0.5 (max (- maxx minx) (- maxy miny) (- maxz minz) 1e-6))]))

;; ============================================================
;; Lifecycle
;; ============================================================

(defn- clear-scene-objects!
  [^js scene]
  (doseq [^js obj [(:ghost-obj @state) (:highlight-obj @state)]
          :when obj]
    (.remove scene obj)
    (when-let [g (.-geometry obj)] (.dispose g))
    (when-let [mt (.-material obj)] (.dispose mt))))

(defn- sync-camera!
  "Registered once as a viewport frame-callback — runs every main-render
   frame, receives the MAIN THREE camera. Copies its orientation, places the
   inset camera on the framing sphere around `:center` at `:dist`, renders.
   No-op (cheap) when hidden or nothing to frame yet."
  [^js main-camera]
  (when-let [{:keys [^js renderer ^js scene ^js camera visible? center dist]} @state]
    (when (and visible? center dist)
      (.copy (.-quaternion camera) (.-quaternion main-camera))
      ;; the camera looks down its local -Z; "backward" (away from view dir)
      ;; is local +Z — rotate that into world space to place it.
      (let [back (doto (THREE/Vector3. 0 0 1) (.applyQuaternion (.-quaternion camera)))
            [cx cy cz] center]
        (.set (.-position camera)
              (+ cx (* (.-x back) dist)) (+ cy (* (.-y back) dist)) (+ cz (* (.-z back) dist))))
      (.render renderer scene camera))))

(defn mounted? [] (some? @state))

(defn mount!
  "Create the inset's own <canvas>/renderer/scene/camera as a small overlay
   inside #viewport-panel (already position:relative — the natural anchor)
   and register its camera-sync frame-callback. A session (edit-mesh-split
   today, edit-mesh-board later) calls this from its enter!, unmount! from
   its cleanup! — the same lifecycle as the modal panel itself. No-op if
   already mounted."
  []
  (when (and (not @state) (.getElementById js/document "viewport-panel"))
    (let [host (.getElementById js/document "viewport-panel")
          container (.createElement js/document "div")
          header (.createElement js/document "div")
          label (.createElement js/document "span")
          toggle (.createElement js/document "button")
          canvas (.createElement js/document "canvas")
          scene (THREE/Scene.)
          hemi (THREE/HemisphereLight. 0xffffff 0x444444 0.6)
          headlight (THREE/DirectionalLight. 0xffffff 0.8)
          camera (THREE/PerspectiveCamera. fov-deg (/ canvas-w canvas-h) 0.1 10000)
          renderer (THREE/WebGLRenderer. #js {:canvas canvas :antialias true})]
      ;; Start COLLAPSED (2026-07-15, Vincenzo): the inset sits bottom-right, where the
      ;; OS mic-dictation ("Structure") overlay also lands and covers it — so it opens
      ;; as just its header, out of the way, and the user expands it with the toggle
      ;; when wanted. A view-only default; nothing else about the session changes.
      (set! (.-className container) "viewport-inset collapsed")
      (set! (.-className header) "viewport-inset-header")
      (set! (.-textContent label) "contesto")
      (set! (.-className toggle) "viewport-inset-toggle")
      (set! (.-textContent toggle) "+")
      (set! (.-title toggle) "nascondi/mostra la vista contesto")
      (.addEventListener toggle "click"
                         (fn [_]
                           (let [now-visible? (not (:visible? @state))]
                             (swap! state assoc :visible? now-visible?)
                             (if now-visible?
                               (.remove (.-classList container) "collapsed")
                               (.add (.-classList container) "collapsed"))
                             (set! (.-textContent toggle) (if now-visible? "–" "+")))))
      (.appendChild header label)
      (.appendChild header toggle)
      (.appendChild container header)
      (.appendChild container canvas)
      (.appendChild host container)
      (.add scene hemi)
      (.set (.-position headlight) 0 0 0)
      (.add camera headlight)
      (.add scene camera) ; the headlight is camera's child — camera must be in the graph too
      (.setSize renderer canvas-w canvas-h)
      (.setPixelRatio renderer (min 2 js/window.devicePixelRatio))
      (reset! state {:container container :canvas canvas :renderer renderer :scene scene
                     :camera camera :ghost-obj nil :highlight-obj nil :center nil :dist nil
                     :visible? false})   ; collapsed by default (see header className above)
      (viewport/register-frame-callback! frame-callback-key sync-camera!))))

(defn unmount!
  "Symmetric teardown for the modal session's cleanup! — frees the WebGL
   context (a limited resource) and the DOM node. No-op if not mounted."
  []
  (when-let [{:keys [^js renderer ^js scene ^js container]} @state]
    (viewport/unregister-frame-callback! frame-callback-key)
    (clear-scene-objects! scene)
    (.dispose renderer)
    (when-let [^js parent (.-parentNode container)] (.removeChild parent container))
    (reset! state nil)))

(defn set-content!
  "Rebuild the inset's scene content: `ghost` (the whole reference mesh,
   wireframe) + `highlight` (the current piece, lit solid) — both already in
   the same world frame as each other (sub-meshes of the same decomposition),
   so no transform is applied. Recomputes framing from `ghost`'s bounds. A
   no-op if not mounted."
  [{:keys [ghost highlight]}]
  (when-let [{:keys [^js scene]} @state]
    (clear-scene-objects! scene)
    (let [have-ghost? (seq (:vertices ghost))
          [center radius] (if have-ghost? (bounds ghost) [nil nil])
          ghost-obj (when (and have-ghost? (seq (:faces ghost))) (ghost-object ghost))
          highlight-obj (when (and highlight (seq (:faces highlight))) (highlight-object highlight))]
      (when ghost-obj (.add scene ghost-obj))
      (when highlight-obj (.add scene highlight-obj))
      (swap! state assoc
             :ghost-obj ghost-obj :highlight-obj highlight-obj
             :center center :dist (when radius (framing-distance radius))))))
