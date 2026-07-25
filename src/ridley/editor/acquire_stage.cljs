(ns ridley.editor.acquire-stage
  "P4b — the palcoscenico as VIEWPORT STATE (not a modal session).

   Architecture (Vincenzo 2026-07-25): edit-acquire does REGISTRATION only and then
   closes. Its emitted `(acquire \"dir\" {…})`, evaluated in the user's normal source,
   turns into this stage: the registered cameras shown as clickable ghost frustums,
   a click flying the camera into that photo's pose (backdrop + geometry over),
   Esc / click-out back to free orbit. It never claims the modal slot (edit-acquire
   is closed by now) and never moves the camera on activation — 'disponibile ma non
   invadente'. The ricalco is then ordinary `(edit-path-2d …)` in the user's own
   source, drawn on a turtle plane posed from a mark/face of the acquire, with
   backdrop + reprojection supplied by this stage.

   Frame reconciliation: acquire-state.json stores camera poses in the ACQUISITION
   frame alongside that frame's proxy-pose; the evaluated `(acquire …)` gives the
   proxy in its EMITTED (canonical) pose. We rigidly transport each camera by the
   motion acq-proxy→emit-proxy (attachment/transform-pose-rigid) so cameras and
   proxy stay coincident — reusing the registration-stability transport, not
   re-running canonicalize."
  (:require [ridley.math :as m]
            [ridley.viewport.core :as viewport]
            [ridley.editor.acquire-backdrop :as backdrop]
            [ridley.turtle.attachment :as attachment]
            [ridley.photogrammetry.camera :as pcamera]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.exif :as exif]
            [ridley.export.stl :as stl]))

(def default-focal-mm 48.0)

;; ------------------------------------------------------------
;; Frustum geometry (pure). A registered camera drawn as a ghost pyramid at its
;; world pose: apex at the camera position, a rectangular base `depth` ahead along
;; heading, sized to the lens half-extents. `frustum-edges` is the visible outline
;; (:lines); `frustum-pick-mesh` is the invisible solid twin used for click-to-pose
;; (:lines aren't raycast-hittable — only a THREE.Mesh is).
;; ------------------------------------------------------------

(def frustum-ghost-color 0x8899aa)   ; grigio-azzurro, legge come riferimento
(def frustum-current-color 0x66ccff) ; la foto corrente, evidenziata

(defn frustum-corners
  "Apex + 4 base corners of a camera pyramid: apex at the camera `position`,
   rectangular base `depth` in front (along heading), sized to the half-extents.
   Shared by frustum-edges and frustum-pick-mesh."
  [{:keys [position heading up]} depth half-w half-h]
  (let [fwd (m/normalize heading)
        u (m/normalize up)
        r (m/normalize (m/cross fwd u))
        base (m/v+ position (m/v* fwd depth))
        c1 (m/v+ base (m/v+ (m/v* r half-w) (m/v* u half-h)))
        c2 (m/v+ base (m/v+ (m/v* r (- half-w)) (m/v* u half-h)))
        c3 (m/v+ base (m/v+ (m/v* r (- half-w)) (m/v* u (- half-h))))
        c4 (m/v+ base (m/v+ (m/v* r half-w) (m/v* u (- half-h))))]
    [position c1 c2 c3 c4]))

(defn frustum-edges
  "8 world-space edge segments of a camera pyramid (apex → 4 corners, + base loop)."
  [pose depth half-w half-h color]
  (let [[apex c1 c2 c3 c4] (frustum-corners pose depth half-w half-h)]
    (mapv (fn [[a b]] {:from a :to b :color color})
          [[apex c1] [apex c2] [apex c3] [apex c4]
           [c1 c2] [c2 c3] [c3 c4] [c4 c1]])))

(defn frustum-pick-mesh
  "Invisible solid twin of a frustum pyramid, tagged with the photo `idx` so a click
   in free orbit resolves to that camera. Never rendered (:pick-only) but picked by
   viewport/raycast-preview-pick. Faces: 4 side triangles from the apex + 2 for the
   base quad; double-sided so a ray hits regardless of which side it enters."
  [pose depth half-w half-h idx]
  (let [corners (frustum-corners pose depth half-w half-h)]
    {:type :mesh
     :pick-only true
     :pick-id idx
     :data {:vertices corners
            :faces [[0 1 2] [0 2 3] [0 3 4] [0 4 1]
                    [1 2 3] [1 3 4]]
            :material {:double-sided true}}}))

;; ------------------------------------------------------------
;; Stage state (viewport-level, NOT the modal session). nil = inactive.
;; When active:
;;   {:dir :dims :emit-pose :photos [{:file :theta}] :camera-poses {idx pose}
;;    :focal-mm :current-idx :in-pose? :loaded? :listeners? :press :pending}
;; :pending holds the acquire-value noted DURING an eval, consumed by after-eval!
;; (the post-refresh hook), so an eval that no longer contains (acquire …) can
;; deactivate the stage.
;; ------------------------------------------------------------

(defonce ^:private stage (atom nil))

(defn active? [] (some? @stage))
(defn in-pose? [] (boolean (:in-pose? @stage)))
(defn- loaded? [] (boolean (:loaded? @stage)))
(defn- free-orbit? [] (and @stage (loaded?) (not (:in-pose? @stage))))

(defn- photo-file [idx] (:file (nth (:photos @stage) idx nil)))
(defn- stage-pivot [] (:position (:emit-pose @stage)))

;; ------------------------------------------------------------
;; Parsing acquire-state.json / session.json (a lean subset of edit-acquire's
;; apply-loaded-state! — the stage only needs camera poses + the acq proxy-pose +
;; focal). Camera poses come back in the ACQUISITION frame; load! reconciles them.
;; ------------------------------------------------------------

(defn- parse-session-json [text]
  (let [obj (js/JSON.parse text)]
    (mapv (fn [[file theta]] {:file file :theta theta}) (js->clj (.-photos obj)))))

(defn- parse-acquire-state
  "acquire-state.json → {:proxy-pose <acq frame> :camera-poses {idx pose} :focal-mm}.
   Camera poses are the acquisition-frame poses (idx 0 = the fixed vantage,
   camera-pose-0); idx>0 only when the photo carries a real camera-pose."
  [text]
  (let [{:keys [proxy-pose camera-pose-0 photos focal]}
        (js->clj (js/JSON.parse text) :keywordize-keys true)
        cams (cond-> {}
               camera-pose-0 (assoc 0 camera-pose-0)
               :always (into (keep (fn [[idx-kw {:keys [camera-pose]}]]
                                     (when camera-pose
                                       [(js/parseInt (name idx-kw) 10) camera-pose]))
                                   photos)))]
    {:proxy-pose proxy-pose
     :camera-poses cams
     :focal-mm (:mm focal)}))

(defn- reconcile-cameras
  "Transport every acquisition-frame camera pose to the emitted proxy frame by the
   rigid motion acq-proxy-pose → emit-pose. Returns {idx pose} in the emit frame,
   or {} when there's no acq proxy-pose to reconcile against."
  [camera-poses acq-pose emit-pose]
  (if (and acq-pose emit-pose)
    (let [{op :position oh :heading ou :up} acq-pose
          {np :position nh :heading nu :up} emit-pose]
      (into {} (map (fn [[idx pose]]
                      [idx (attachment/transform-pose-rigid pose op oh ou np nh nu)])
                    camera-poses)))
    {}))

;; ------------------------------------------------------------
;; Frustum preview (free orbit only). Re-shown by after-eval! since a definitions
;; Run clears the preview layer.
;; ------------------------------------------------------------

(defn- frustum-preview-items []
  (when (free-orbit?)
    (let [{:keys [dims camera-poses focal-mm current-idx]} @stage
          depth (* 1.2 0.5 (m/magnitude dims))
          aspect (if-let [[w h] (backdrop/image-size)] (/ w h) (/ 4.0 3.0))
          hfov (pcamera/equiv-focal->hfov-deg focal-mm aspect)
          half-w (* depth (Math/tan (* 0.5 hfov (/ Math/PI 180.0))))
          half-h (/ half-w aspect)]
      (into []
            (mapcat (fn [[idx pose]]
                      [{:type :lines
                        :data (frustum-edges pose depth half-w half-h
                                             (if (= idx current-idx)
                                               frustum-current-color frustum-ghost-color))}
                       (frustum-pick-mesh pose depth half-w half-h idx)]))
            camera-poses))))

(defn- show-frustums! [] (viewport/show-preview! (frustum-preview-items)))

;; ------------------------------------------------------------
;; In-pose / free-orbit transitions. In pose the camera is locked (set-camera-pose!
;; disables the controls; there's no gizmo re-enabling them each frame, so it holds)
;; and the photo shows as backdrop with the user's own evaluated geometry projecting
;; over it. Leaving returns to free orbit WITHOUT a jump (free-camera-at-pivot!).
;; ------------------------------------------------------------

(defn go-in-pose!
  "Fly the camera into photo `idx`'s registered pose, show that photo full-screen
   as the backdrop; the user's geometry (already in the scene) projects over it."
  [idx]
  (when-let [pose (get-in @stage [:camera-poses idx])]
    (swap! stage assoc :current-idx idx :in-pose? true)
    (viewport/set-camera-pose! pose) ; disables controls → locked
    (when-let [file (photo-file idx)]
      (backdrop/set-photo! (str (:dir @stage) "/" file) (:focal-mm @stage)
                           viewport/set-camera-fov!)
      (backdrop/set-visible! true))
    ;; drop the frustums — we're in pose now
    (viewport/clear-preview!)))

(defn leave-pose!
  "Back to free orbit around the object; the camera stays exactly where the photo
   framed it (no jump), the backdrop hides and the frustums return."
  []
  (when (:in-pose? @stage)
    (swap! stage assoc :in-pose? false)
    (backdrop/set-visible! false)
    (viewport/free-camera-at-pivot! (stage-pivot))
    (show-frustums!)))

;; ------------------------------------------------------------
;; Pointer + keyboard. A CLEAN click (little travel, so it never steals an orbit
;; drag) on a ghost frustum flies into pose. [ / ] navigate, Esc leaves pose —
;; but only when focus is outside any editable (the user must be able to type
;; brackets in their source), so keys act only when the viewport, not the editor,
;; has focus.
;; ------------------------------------------------------------

(def ^:private click-slop-px 6)

(defn- editable? [^js el]
  (boolean (and el (or (#{"INPUT" "TEXTAREA"} (.-tagName el))
                       (.-isContentEditable el)))))

(defn- on-pointerdown [^js e]
  (when (and (free-orbit?) (zero? (.-button e)))
    (swap! stage assoc :press {:x (.-clientX e) :y (.-clientY e)
                               :idx (viewport/raycast-preview-pick e)})))

(defn- on-pointerup [^js e]
  (when (and (free-orbit?) (zero? (.-button e)))
    (let [{:keys [x y idx]} (:press @stage)]
      (swap! stage dissoc :press)
      (when (and (some? idx) (some? x)
                 (< (js/Math.hypot (- (.-clientX e) x) (- (.-clientY e) y)) click-slop-px))
        ;; Defer the pose (which disables the orbit controls) to a macrotask so
        ;; TrackballControls processes THIS pointerup first — it early-returns while
        ;; disabled, leaving its ROTATE state + document listeners stranded, which
        ;; then re-activate on leave-pose! (Vincenzo 2026-07-25: "dopo Esc orbita
        ;; come se tenessi giù il tasto" — a lost mouse-up). Letting the controls
        ;; end the click cleanly first, then locking, avoids the stranded drag.
        ;; setTimeout (not requestAnimationFrame): rAF is throttled/paused in a
        ;; background tab, which would swallow the click; a 0-delay timer still runs
        ;; right after the pointerup dispatch, which is all the fix needs.
        (js/setTimeout (fn [] (go-in-pose! idx)) 0)))))

(defn- on-keydown [^js e]
  (when (and @stage (loaded?) (not (editable? (.-activeElement js/document))))
    (let [k (.-key e)
          n (count (:photos @stage))
          idx (:current-idx @stage 0)]
      (cond
        (and (:in-pose? @stage) (= k "Escape"))
        (do (.preventDefault e) (leave-pose!))
        (and (:in-pose? @stage) (= k "["))
        (do (.preventDefault e) (go-in-pose! (mod (dec idx) n)))
        (and (:in-pose? @stage) (= k "]"))
        (do (.preventDefault e) (go-in-pose! (mod (inc idx) n)))))))

(defn- install-listeners! []
  (when-not (:listeners? @stage)
    (let [^js canvas (viewport/get-canvas)]
      (.addEventListener canvas "pointerdown" on-pointerdown true)
      (.addEventListener canvas "pointerup" on-pointerup true)
      (.addEventListener js/document "keydown" on-keydown true))
    (swap! stage assoc :listeners? true)))

(defn- teardown-listeners! []
  (let [^js canvas (viewport/get-canvas)]
    (.removeEventListener canvas "pointerdown" on-pointerdown true)
    (.removeEventListener canvas "pointerup" on-pointerup true)
    (.removeEventListener js/document "keydown" on-keydown true)))

;; ------------------------------------------------------------
;; Lifecycle: activation from an evaluated (acquire …), refresh across Runs, and
;; deactivation when a Run no longer contains one.
;; ------------------------------------------------------------

(defn deactivate!
  "Tear the stage down entirely (no (acquire …) in the last eval)."
  []
  (when @stage
    (teardown-listeners!)
    (when (:in-pose? @stage) (backdrop/set-visible! false))
    (backdrop/clear!)
    (viewport/clear-preview!)
    (reset! stage nil)))

(defn- resolve-focal!
  "Adopt the focal: acquire-state's saved value if present, else EXIF from photo 0,
   else the default. Returns a Promise resolving to the focal (mm)."
  [state-focal first-file]
  (if state-focal
    (js/Promise.resolve state-focal)
    (-> (stl/desktop-read-file-blob (str (:dir @stage) "/" (:file first-file)))
        (.then (fn [^js blob] (.arrayBuffer blob)))
        (.then (fn [ab] (or (exif/focal-35mm-from-arraybuffer ab) default-focal-mm)))
        (.catch (fn [_] default-focal-mm)))))

(defn- load!
  "Async: read session.json + acquire-state.json from the stage dir, reconcile
   camera poses into the emit frame, resolve focal, and mark the stage loaded.
   Returns a Promise. On resolve the caller shows the frustums (post-refresh)."
  []
  (let [dir (:dir @stage)]
    (-> (js/Promise.all
         #js [(stl/desktop-read-file (str dir "/session.json"))
              (-> (stl/desktop-read-file (str dir "/acquire-state.json"))
                  (.catch (fn [_] nil)))]) ; no state yet → no cameras (no frustums)
        (.then (fn [^js results]
                 (let [photos (parse-session-json (aget results 0))
                       st (some-> (aget results 1) parse-acquire-state)
                       cams (reconcile-cameras (:camera-poses st)
                                               (:proxy-pose st) (:emit-pose @stage))]
                   (swap! stage assoc :photos photos :camera-poses cams)
                   (-> (resolve-focal! (:focal-mm st) (first photos))
                       (.then (fn [focal]
                                (swap! stage assoc :focal-mm focal :loaded? true)))))))
        (.catch (fn [err]
                  (js/console.warn "acquire-stage: load failed" err)
                  nil)))))

(defn note-eval!
  "Called by the `acquire` runtime fn DURING evaluation: record the acquire value so
   after-eval! (post refresh-viewport!) can (re)establish the stage. `acquire-value`
   is {:proxy <posed mesh> :pose <emit pose> :dir …}."
  [{:keys [proxy pose dir]}]
  (swap! stage (fn [s]
                 (assoc (or s {})
                        :pending {:dir dir
                                  :emit-pose (or pose (:creation-pose proxy))
                                  :dims (bridge/dims-from-mesh proxy (:creation-pose proxy))}))))

(defn after-eval!
  "Post-eval hook (mirrors modal/requested?→enter!): run AFTER refresh-viewport!.
   If an (acquire …) was noted this eval, (re)establish the stage without moving the
   camera; a Run that noted none tears the stage down."
  []
  (let [pending (:pending @stage)]
    (cond
      ;; a fresh/changed acquire → (re)load, then show frustums (unless in pose)
      (and pending (not= (:dir pending) (:dir @stage)))
      (do (swap! stage merge {:dir (:dir pending)
                              :emit-pose (:emit-pose pending)
                              :dims (:dims pending)
                              :camera-poses {}
                              :photos []
                              :focal-mm default-focal-mm
                              :current-idx 0
                              :in-pose? false
                              :loaded? false
                              :pending nil})
          (install-listeners!)
          (-> (load!) (.then (fn [_] (when-not (:in-pose? @stage) (show-frustums!))))))

      ;; same dir re-evaluated → keep camera/pose, just refresh geometry (dims/pose
      ;; may have changed) and re-show the layer the Run wiped
      pending
      (do (swap! stage merge {:emit-pose (:emit-pose pending)
                              :dims (:dims pending)
                              :pending nil})
          (if (:in-pose? @stage)
            (backdrop/set-visible! true)
            (when (loaded?) (show-frustums!))))

      ;; no (acquire …) this eval → tear down
      @stage
      (deactivate!))))
