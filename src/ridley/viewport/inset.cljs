(ns ridley.viewport.inset
  "Picture-in-picture inset manager — shared infra for picture-in-picture
   views anywhere in the viewport. Originally the Vista contesto singleton
   (dev-docs/brief-acquisition-views.md, Parte 1): 'dove sono nell'oggetto'
   while working, without leaving focus. Generalized to N concurrent, keyed
   instances (dev-docs/brief-mesh-board-views.md, Parte 2) so mesh-board's
   simultaneous comparison views (:intersection/:missing/:excess, possibly
   several concurrent mesh-board calls) can share the same manager and the
   same per-frame camera sync as edit-mesh-split's single 'contesto' inset.

   Each instance is a small, non-interactive camera/renderer/scene that
   mirrors the MAIN viewport camera's orientation every frame (quaternion
   copy — the same idiom viewport.core's billboard labels use for
   update-panels-billboard); content is pushed by the owning caller via
   set-content!, whenever it changes. Instances stack in a column anchored
   at the viewport's bottom-right corner (column-reverse: the first-mounted
   instance sits nearest the corner, so a single mounted instance is
   pixel-identical to the old singleton's position).

   Deliberately tool-agnostic (design doc: 'le viste sono del dato, non di
   uno strumento') — edit-mesh-split uses it today (key :context),
   mesh-board reuses it unchanged for comparison views. Uses ONLY
   viewport.core's public API (register-frame-callback!/
   unregister-frame-callback!) — no new exports were needed there, and this
   ns never touches the main scene/renderer.

   First interactivity (brief-mesh-board-views.md Parte 4.3/4.4, feedback from
   real use): a header-drag repositions an instance (view state, not
   persisted — a reload restarts from the default column layout) and a wheel
   over the box zooms it (a per-instance distance multiplier on top of the
   auto-framing distance). Both stopPropagation/preventDefault so they never
   reach the main viewport's TrackballControls underneath — the inset is
   overlaid on top (z-index), not a descendant of its canvas, so this mostly
   falls out of normal DOM hit-testing, but the events are still consumed
   explicitly to be sure."
  (:require ["three" :as THREE]
            [ridley.viewport.core :as viewport]))

(defonce ^:private instances (atom {}))
(defonce ^:private column (atom nil))

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

(def ^:private min-zoom 0.2)
(def ^:private max-zoom 5.0)

(defn- clamp [v lo hi] (max lo (min hi v)))

(defn- bounds
  "[center radius] over mesh's vertices — an instance's one-time framing."
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
  [^js scene {:keys [ghost-obj highlight-obj]}]
  (doseq [^js obj [ghost-obj highlight-obj]
          :when obj]
    (.remove scene obj)
    (when-let [g (.-geometry obj)] (.dispose g))
    (when-let [mt (.-material obj)] (.dispose mt))))

(defn- render-instance!
  "Runs every main-render frame (via the single shared frame-callback),
   receives the MAIN THREE camera. Copies its orientation, places this
   instance's camera on the framing sphere around `:center` at `:dist` ×
   `:zoom` (the wheel-adjustable multiplier, 4.4), renders. No-op (cheap)
   when hidden or nothing to frame yet."
  [^js main-camera {:keys [^js renderer ^js scene ^js camera visible? center dist zoom]}]
  (when (and visible? center dist)
    (.copy (.-quaternion camera) (.-quaternion main-camera))
    ;; the camera looks down its local -Z; "backward" (away from view dir)
    ;; is local +Z — rotate that into world space to place it.
    (let [d (* dist (or zoom 1.0))
          back (doto (THREE/Vector3. 0 0 1) (.applyQuaternion (.-quaternion camera)))
          [cx cy cz] center]
      (.set (.-position camera)
            (+ cx (* (.-x back) d)) (+ cy (* (.-y back) d)) (+ cz (* (.-z back) d))))
    (.render renderer scene camera)))

(defn- sync-all!
  [^js main-camera]
  (doseq [inst (vals @instances)]
    (render-instance! main-camera inst)))

(defn mounted?
  ([] (some? @column))
  ([key] (contains? @instances key)))

(defn- ensure-column!
  [^js host]
  (or @column
      (let [col (.createElement js/document "div")]
        (set! (.-className col) "viewport-inset-column")
        (.appendChild host col)
        (reset! column col)
        col)))

(defn- attach-interactivity!
  "Header-drag (reposition, out of the column flow) + wheel-over-box (zoom,
   4.3/4.4) — the inset's first interactive gestures. Drag and wheel both
   stopPropagation/preventDefault so the main viewport's TrackballControls
   underneath never also reacts (Parte 4.4's explicit verification
   requirement). Returns the handler fns so unmount! can remove them —
   mousemove/mouseup are registered on `document` (a drag must keep tracking
   the pointer even once it leaves the small box), so leaving them attached
   past unmount would leak a listener referencing a detached DOM node."
  [key ^js container ^js header ^js toggle]
  (let [drag (atom nil)
        on-header-down (fn [^js e]
                         (when (not= (.-target e) toggle)
                           (.preventDefault e)
                           (let [rect (.getBoundingClientRect container)
                                 ^js style (.-style container)]
                             (set! (.-position style) "fixed")
                             (set! (.-left style) (str (.-left rect) "px"))
                             (set! (.-top style) (str (.-top rect) "px"))
                             (set! (.-right style) "auto")
                             (set! (.-bottom style) "auto")
                             (.add (.-classList header) "dragging")
                             (reset! drag {:dx (- (.-clientX e) (.-left rect))
                                           :dy (- (.-clientY e) (.-top rect))}))))
        on-doc-move (fn [^js e]
                      (when-let [{:keys [dx dy]} @drag]
                        (let [^js style (.-style container)]
                          (set! (.-left style) (str (- (.-clientX e) dx) "px"))
                          (set! (.-top style) (str (- (.-clientY e) dy) "px")))))
        on-doc-up (fn [_]
                    (when @drag
                      (reset! drag nil)
                      (.remove (.-classList header) "dragging")))
        on-wheel (fn [^js e]
                   (.preventDefault e)
                   (.stopPropagation e)
                   (let [factor (if (pos? (.-deltaY e)) 1.1 0.9)]
                     (swap! instances update-in [key :zoom]
                            #(clamp (* (or % 1.0) factor) min-zoom max-zoom))))]
    (.addEventListener header "mousedown" on-header-down)
    (.addEventListener js/document "mousemove" on-doc-move)
    (.addEventListener js/document "mouseup" on-doc-up)
    (.addEventListener container "wheel" on-wheel #js {:passive false})
    {:on-header-down on-header-down :on-doc-move on-doc-move
     :on-doc-up on-doc-up :on-wheel on-wheel}))

(defn mount!
  "Create `key`'s own <canvas>/renderer/scene/camera as a column entry inside
   #viewport-panel (already position:relative — the natural anchor) and
   register the shared camera-sync frame-callback (idempotent — registered
   once, on the first instance). A session (edit-mesh-split, mesh-board's
   compare!) calls this from its enter!/directive dispatch, unmount! from its
   cleanup! — the same lifecycle as a modal panel. No-op if `key` is already
   mounted, or if there's no #viewport-panel / no DOM at all (headless/node
   test environment — mesh-board's compare! runs there too, and must degrade
   gracefully exactly like WASM-dependent ops do)."
  ([key] (mount! key {}))
  ([key {:keys [label collapsed?] :or {collapsed? false}}]
   (when (and (not (contains? @instances key))
              (exists? js/document)
              (.getElementById js/document "viewport-panel"))
     (let [host (.getElementById js/document "viewport-panel")
           col (ensure-column! host)
           container (.createElement js/document "div")
           header (.createElement js/document "div")
           label-el (.createElement js/document "span")
           toggle (.createElement js/document "button")
           canvas (.createElement js/document "canvas")
           scene (THREE/Scene.)
           hemi (THREE/HemisphereLight. 0xffffff 0x444444 0.6)
           headlight (THREE/DirectionalLight. 0xffffff 0.8)
           camera (THREE/PerspectiveCamera. fov-deg (/ canvas-w canvas-h) 0.1 10000)
           renderer (THREE/WebGLRenderer. #js {:canvas canvas :antialias true})
           visible? (not collapsed?)]
       (set! (.-className container) (str "viewport-inset" (when collapsed? " collapsed")))
       (set! (.-className header) "viewport-inset-header")
       (set! (.-textContent label-el) (or label (name key)))
       (set! (.-className toggle) "viewport-inset-toggle")
       (set! (.-textContent toggle) (if visible? "–" "+"))
       (set! (.-title toggle) "nascondi/mostra questa vista")
       (.addEventListener toggle "click"
                          (fn [_]
                            (when (contains? @instances key)
                              (let [now-visible? (not (:visible? (get @instances key)))]
                                (swap! instances assoc-in [key :visible?] now-visible?)
                                (if now-visible?
                                  (.remove (.-classList container) "collapsed")
                                  (.add (.-classList container) "collapsed"))
                                (set! (.-textContent toggle) (if now-visible? "–" "+"))))))
       (.appendChild header label-el)
       (.appendChild header toggle)
       (.appendChild container header)
       (.appendChild container canvas)
       (.appendChild col container)
       (.add scene hemi)
       (.set (.-position headlight) 0 0 0)
       (.add camera headlight)
       (.add scene camera) ; the headlight is camera's child — camera must be in the graph too
       (.setSize renderer canvas-w canvas-h)
       (.setPixelRatio renderer (min 2 js/window.devicePixelRatio))
       (let [handlers (attach-interactivity! key container header toggle)]
         (swap! instances assoc key
                {:container container :canvas canvas :renderer renderer :scene scene
                 :camera camera :header header :label-el label-el :handlers handlers
                 :ghost-obj nil :highlight-obj nil :zoom 1.0
                 :center nil :dist nil :visible? visible?}))
       (when (= 1 (count @instances))
         (viewport/register-frame-callback! frame-callback-key sync-all!))))))

(defn unmount!
  "Symmetric teardown for `key` — frees its WebGL context (a limited
   resource), DOM node, and the document-level drag listeners (4.3).
   Unregisters the shared frame-callback and drops the column container once
   the last instance is gone. No-op if `key` isn't mounted."
  [key]
  (when-let [{:keys [^js renderer ^js scene ^js container ^js header handlers] :as inst}
             (get @instances key)]
    (clear-scene-objects! scene inst)
    (when-let [{:keys [on-header-down on-doc-move on-doc-up on-wheel]} handlers]
      (.removeEventListener header "mousedown" on-header-down)
      (.removeEventListener js/document "mousemove" on-doc-move)
      (.removeEventListener js/document "mouseup" on-doc-up)
      (.removeEventListener container "wheel" on-wheel))
    (.dispose renderer)
    (when-let [^js parent (.-parentNode container)] (.removeChild parent container))
    (swap! instances dissoc key)
    (when (empty? @instances)
      (viewport/unregister-frame-callback! frame-callback-key)
      (when-let [^js col @column]
        (when-let [^js parent (.-parentNode col)] (.removeChild parent col))
        (reset! column nil)))))

(defn set-content!
  "Rebuild `key`'s scene content: `ghost` (a whole reference mesh, wireframe)
   and/or `highlight` (a solid, lit mesh — the current piece for edit-mesh-
   split, or a comparison-view result for mesh-board), both already in the
   same world frame as each other, so no transform is applied. An optional
   `label` overwrites the header text (mesh-board updates it every eval with
   the view name + volume). Recomputes framing from whichever of
   ghost/highlight is present. A no-op if `key` isn't mounted."
  [key {:keys [ghost highlight label]}]
  (when-let [{:keys [^js scene] :as inst} (get @instances key)]
    (clear-scene-objects! scene inst)
    (let [have-ghost? (seq (:vertices ghost))
          have-highlight? (seq (:vertices highlight))
          [center radius] (cond have-ghost? (bounds ghost)
                                have-highlight? (bounds highlight)
                                :else [nil nil])
          ghost-obj (when (and have-ghost? (seq (:faces ghost))) (ghost-object ghost))
          highlight-obj (when (and have-highlight? (seq (:faces highlight))) (highlight-object highlight))]
      (when ghost-obj (.add scene ghost-obj))
      (when highlight-obj (.add scene highlight-obj))
      (when label
        (when-let [^js el (:label-el inst)] (set! (.-textContent el) label)))
      (swap! instances update key merge
             {:ghost-obj ghost-obj :highlight-obj highlight-obj
              :center center :dist (when radius (framing-distance radius))}))))
