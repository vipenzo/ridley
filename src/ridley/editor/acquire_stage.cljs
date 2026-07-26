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
            [ridley.editor.modal-evaluator :as modal]
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
   viewport/raycast-frustum-pick. Faces: 4 side triangles from the apex + 2 for the
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

(defn- frustum-preview-items
  "Ghost frustum items for the dedicated frustum layer, shown in FREE ORBIT (each: a
   ghost pyramid outline + an invisible pick-mesh). Now on their OWN layer, so they
   stay visible while an edit-path-2d ricalco is open (Vincenzo 2026-07-26: before,
   they vanished the moment a ricalco opened — disorienting when you want to choose
   the next angle). Not shown IN pose: there the camera is zoomed onto the object, so
   the other cameras (a ~250mm ring around a ~60mm object) fall outside the frame
   anyway; navigation in pose is via Prev/Next / [ / ] / the toggle."
  []
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

(defn- show-frustums! [] (viewport/show-frustum-layer! (frustum-preview-items)))

;; ------------------------------------------------------------
;; In-pose / free-orbit transitions. In pose the camera is locked (set-camera-pose!
;; disables the controls; there's no gizmo re-enabling them each frame, so it holds)
;; and the photo shows as backdrop with the user's own evaluated geometry projecting
;; over it. Leaving returns to free orbit WITHOUT a jump (free-camera-at-pivot!).
;; ------------------------------------------------------------

(declare reset-view! update-toolbar! nav-photo!)

(def ^:private flight-ms 200) ; camera flight duration for Prev/Next navigation

(defn- install-pose-lock!
  "Register the per-frame :acquire-stage callback that HARD-locks the camera in pose.
   set-camera-pose! disables the orbit controls ONCE, but a modal editor opened over
   the photo (an edit-path-2d ricalco) re-enables them on every node grab/release —
   after that, dragging a node ALSO orbits the camera and the photo↔proxy alignment
   drifts (Vincenzo 2026-07-25: 'grabbo un nodo e il wireframe si sposta'). Forcing
   controls off per-frame keeps the pose glued no matter who re-enables them. In the
   same callback, keep edit-path-2d's node handles a constant SCREEN size: the in-pose
   zoom is a camera view offset that magnifies the dots too, so counteract it by
   scaling them 1/zoom. Re-applied every frame so it survives edit-path re-rendering
   its dots and every photo change during navigation."
  []
  (viewport/register-frame-callback! :acquire-stage
                                     (fn [_camera]
                                       (viewport/set-controls-enabled! false)
                                       (viewport/scale-screen-dots!
                                        (/ 1.0 (get-in @stage [:view :zoom] 1.0))))))

(defn- load-photo-backdrop!
  "Ensure the backdrop plane exists, show photo `idx` full-screen (sets FOV), and
   reset any in-pose zoom/pan. The backdrop plane is a defonce that persists, so a
   stage activated by pre-fix code never got one and set-photo!/set-visible! would
   silently no-op — lazily create it here so the photo is robust to how we got here."
  [idx]
  (when-not (backdrop/ready?)
    (backdrop/create! (viewport/get-camera)))
  (when-let [file (photo-file idx)]
    (backdrop/set-photo! (str (:dir @stage) "/" file) (:focal-mm @stage)
                         viewport/set-camera-fov!)
    (backdrop/set-visible! true))
  ;; every photo starts un-zoomed (fresh view offset)
  (reset-view!))

(defn go-in-pose!
  "Move the camera into photo `idx`'s registered pose and show that photo full-screen
   as the backdrop; the user's geometry (already in the scene) projects over it.
   `animate?` uses a ~200ms flight (Prev/Next navigation, incl. while an edit-path-2d
   ricalco is open → live reprojection); a frustum click enters instantly.

   The frustums live on their own dedicated layer (viewport/show-frustum-layer!),
   separate from the preview layer, so managing them never touches an open edit-path-2d
   ricalco's overlay — the trace is left exactly where it is and reprojects for free
   from the new camera. show-frustums! clears the frustum layer in pose (frustums show
   in free orbit only)."
  ([idx] (go-in-pose! idx false))
  ([idx animate?]
   (when-let [pose (get-in @stage [:camera-poses idx])]
     (swap! stage assoc :current-idx idx :in-pose? true)
     (install-pose-lock!)
     (show-frustums!) ; empties the frustum layer in pose; leaves the ricalco overlay alone
     (if animate?
       (do ;; hide the OLD photo during the flight so the overlay sweeps over a
           ;; neutral background; the destination photo appears on arrival.
         (backdrop/set-visible! false)
         (viewport/fly-camera-to-pose!
          pose flight-ms
          (fn [] (load-photo-backdrop! idx) (update-toolbar!))))
       (do (viewport/set-camera-pose! pose) ; disables controls → locked
           (load-photo-backdrop! idx)))
     (update-toolbar!))))

(defn leave-pose!
  "Back to free orbit around the object; the camera stays exactly where the photo
   framed it (no jump) and the backdrop hides. Works WHILE a modal editor is open
   (Vincenzo 2026-07-25: the Photo toggle must return to global view even mid-ricalco,
   to orbit and inspect the trace from any angle). The per-frame pose-lock is needed
   only IN pose — there it stops a node-grab from orbiting away from the locked photo;
   in free orbit edit-path's own disable-controls-while-dragging is exactly right, so
   there's no drift. Frustums come back (now the full free-orbit set, clickable) on
   their own dedicated layer, so they no longer disturb an open ricalco's overlay."
  []
  (when (:in-pose? @stage)
    (swap! stage assoc :in-pose? false)
    (viewport/unregister-frame-callback! :camera-flight) ; kill any in-flight tween
    ;; release the per-frame camera lock FIRST, else free-camera-at-pivot! re-enables
    ;; orbit and the lock callback immediately re-disables it.
    (viewport/unregister-frame-callback! :acquire-stage)
    ;; the per-frame dot rescale is gone with the callback — restore handles to 1×
    (viewport/scale-screen-dots! 1.0)
    ;; clear any in-pose zoom/pan so free orbit uses the full frame
    (reset-view!)
    (backdrop/set-visible! false)
    (viewport/free-camera-at-pivot! (stage-pivot))
    (show-frustums!) ; free-orbit set (all, clickable) — dedicated layer, safe w/ modal
    (update-toolbar!)))

;; ------------------------------------------------------------
;; Photo navigation in θ (turntable-angle) order — NOT index order. Prev/Next and
;; [ / ] step through the REGISTERED photos (those with a camera pose) sorted by θ,
;; flying the camera between poses. This is the live-reprojection control: change
;; view while an edit-path-2d ricalco is open → the trace is seen from the new angle.
;; ------------------------------------------------------------

(defn- nav-order
  "Registered photo indices (those with a camera pose) sorted by turntable angle θ."
  []
  (let [{:keys [photos camera-poses]} @stage]
    (vec (sort-by (fn [idx] (or (:theta (nth photos idx nil)) 0))
                  (keys camera-poses)))))

(defn- nav-rank
  "1-based position of photo `idx` in θ order (0 if not registered)."
  [idx]
  (or (first (keep-indexed (fn [k i] (when (= i idx) (inc k))) (nav-order))) 0))

(defn nav-photo!
  "Step `dir` (+1 next / -1 prev) through the registered photos in θ order and fly
   the camera there (~200ms). Works whether free-orbit or already in pose, and while
   an edit-path-2d ricalco is open."
  [dir]
  (let [order (nav-order)
        n (count order)]
    (when (pos? n)
      (let [cur (:current-idx @stage)
            pos (or (first (keep-indexed (fn [k i] (when (= i cur) k)) order)) 0)]
        (go-in-pose! (nth order (mod (+ pos dir) n)) true)))))

;; ------------------------------------------------------------
;; Pointer + keyboard. A CLEAN click (little travel, so it never steals an orbit
;; drag) on a ghost frustum flies into pose. [ / ] navigate photos (θ order), Esc
;; leaves pose — but only when focus is outside any editable (the user must be able
;; to type brackets in their source), so keys act only when the viewport, not the
;; editor, has focus.
;; ------------------------------------------------------------

(def ^:private click-slop-px 6)

(defn- editable? [^js el]
  (boolean (and el (or (#{"INPUT" "TEXTAREA"} (.-tagName el))
                       (.-isContentEditable el)))))

(defn- on-pointerdown [^js e]
  (when (and (free-orbit?) (zero? (.-button e)))
    (swap! stage assoc :press {:x (.-clientX e) :y (.-clientY e)
                               :idx (viewport/raycast-frustum-pick e)})))

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
  ;; Guard on editable focus (the user must be able to type brackets in their
  ;; source) — but NOT on modal/active?: [ / ] are promoted ABOVE an open
  ;; edit-path-2d so you can change photo while tracing (edit-path binds neither
  ;; key, so there's no conflict; no stopPropagation, so it stays non-intrusive).
  ;; Esc, by contrast, belongs to the editor when a modal is open (it cancels the
  ;; current edit); the stage takes Esc only when nothing modal is up.
  (when (and @stage (loaded?)
             (not (editable? (.-activeElement js/document))))
    (let [k (.-key e)
          navigable? (or (:in-pose? @stage) (modal/active?))]
      (cond
        (and navigable? (= k "["))
        (do (.preventDefault e) (nav-photo! -1))
        (and navigable? (= k "]"))
        (do (.preventDefault e) (nav-photo! 1))
        (and (:in-pose? @stage) (not (modal/active?)) (= k "Escape"))
        (do (.preventDefault e) (leave-pose!))))))

;; ------------------------------------------------------------
;; In-pose ZOOM + PAN on the still photo (Vincenzo 2026-07-25: trace fine details
;; without changing the drawing plane). We zoom/pan the PROJECTION — camera view
;; offset, NOT the position/orientation — so the drawing plane is untouched, photo
;; and proxy scale/shift together (both projected by the same camera → stay
;; aligned), and edit-path-2d's click→plane raycast keeps landing right (it
;; unprojects through the camera, which now carries the offset). Zoom = wheel
;; around the cursor; pan = right-button drag. State in :view {:zoom :pan-x :pan-y},
;; where pan-x/y is the shown sub-rectangle's top-left in canvas-px of a virtual
;; full frame; reset on every go-in-pose!/leave-pose!.
;; ------------------------------------------------------------

(def ^:private zoom-min 1.0)
(def ^:private zoom-max 8.0)

(defn- clampv [v lo hi] (max lo (min hi v)))

(def ^:private default-view {:zoom 1.0 :pan-x 0.0 :pan-y 0.0})

(defn- apply-view!
  "Push :view onto the camera as a view offset (magnify + shift), or clear it at
   zoom 1 (the whole frame)."
  []
  (when-let [^js cam (viewport/get-camera)]
    (let [{:keys [zoom pan-x pan-y]} (:view @stage default-view)
          rect (.getBoundingClientRect (viewport/get-canvas))
          W (.-width rect) H (.-height rect)]
      (if (<= zoom 1.0001)
        (.clearViewOffset cam)
        (.setViewOffset cam W H pan-x pan-y (/ W zoom) (/ H zoom)))
      (.updateProjectionMatrix cam))))

(defn- reset-view! []
  (swap! stage assoc :view default-view)
  (when-let [^js cam (viewport/get-camera)]
    (.clearViewOffset cam)
    (.updateProjectionMatrix cam)))

(defn- on-wheel [^js e]
  (when (:in-pose? @stage)
    (.preventDefault e) (.stopPropagation e)
    (let [rect (.getBoundingClientRect (viewport/get-canvas))
          W (.-width rect) H (.-height rect)
          mx (- (.-clientX e) (.-left rect))
          my (- (.-clientY e) (.-top rect))
          {:keys [zoom pan-x pan-y]} (:view @stage default-view)
          w (/ W zoom) h (/ H zoom)
          ;; virtual-frame point currently under the cursor — keep it there
          fx (+ pan-x (* (/ mx W) w))
          fy (+ pan-y (* (/ my H) h))
          zoom' (clampv (* zoom (if (pos? (.-deltaY e)) (/ 1.0 1.1) 1.1)) zoom-min zoom-max)
          w' (/ W zoom') h' (/ H zoom')]
      (swap! stage assoc :view
             {:zoom zoom'
              :pan-x (clampv (- fx (* (/ mx W) w')) 0.0 (- W w'))
              :pan-y (clampv (- fy (* (/ my H) h')) 0.0 (- H h'))})
      (apply-view!))))

(defn- on-pan-down [^js e]
  (when (and (:in-pose? @stage) (= 2 (.-button e)))
    (.preventDefault e)
    (swap! stage assoc :pan-drag {:x (.-clientX e) :y (.-clientY e)
                                  :pan-x (get-in @stage [:view :pan-x] 0.0)
                                  :pan-y (get-in @stage [:view :pan-y] 0.0)})))

(defn- on-pan-move [^js e]
  (when-let [{:keys [x y pan-x pan-y]} (:pan-drag @stage)]
    (.preventDefault e)
    (let [rect (.getBoundingClientRect (viewport/get-canvas))
          W (.-width rect) H (.-height rect)
          zoom (:zoom (:view @stage default-view))
          w (/ W zoom) h (/ H zoom)
          ;; drag the content WITH the cursor → the sub-rectangle moves opposite
          px' (clampv (- pan-x (* (/ (- (.-clientX e) x) W) w)) 0.0 (- W w))
          py' (clampv (- pan-y (* (/ (- (.-clientY e) y) H) h)) 0.0 (- H h))]
      (swap! stage update :view merge {:pan-x px' :pan-y py'})
      (apply-view!))))

(defn- on-pan-up [^js e]
  (when (and (:pan-drag @stage) (= 2 (.-button e)))
    (swap! stage dissoc :pan-drag)))

(defn- on-contextmenu [^js e]
  ;; suppress the menu so right-drag can pan the posed photo
  (when (:in-pose? @stage) (.preventDefault e)))

(defn- install-listeners! []
  (when-not (:listeners? @stage)
    (let [^js canvas (viewport/get-canvas)]
      (.addEventListener canvas "pointerdown" on-pointerdown true)
      (.addEventListener canvas "pointerup" on-pointerup true)
      (.addEventListener js/document "keydown" on-keydown true)
      ;; in-pose zoom/pan (self-guarded on :in-pose?)
      (.addEventListener canvas "wheel" on-wheel #js {:capture true :passive false})
      (.addEventListener canvas "pointerdown" on-pan-down true)
      (.addEventListener canvas "pointermove" on-pan-move true)
      (.addEventListener canvas "pointerup" on-pan-up true)
      (.addEventListener canvas "contextmenu" on-contextmenu true))
    (swap! stage assoc :listeners? true)))

(defn- teardown-listeners! []
  (let [^js canvas (viewport/get-canvas)]
    (.removeEventListener canvas "pointerdown" on-pointerdown true)
    (.removeEventListener canvas "pointerup" on-pointerup true)
    (.removeEventListener js/document "keydown" on-keydown true)
    (.removeEventListener canvas "wheel" on-wheel true)
    (.removeEventListener canvas "pointerdown" on-pan-down true)
    (.removeEventListener canvas "pointermove" on-pan-move true)
    (.removeEventListener canvas "pointerup" on-pan-up true)
    (.removeEventListener canvas "contextmenu" on-contextmenu true)))

;; ------------------------------------------------------------
;; Viewport toolbar (shown only while the stage is active WITH registered cameras):
;; a Photo-lock toggle (in-pose ↔ free, label 'foto i/N · θ°') + Prev/Next photo
;; (θ order, ~200ms flight). Mounted in #viewport-toolbar; torn down on deactivate!.
;; ------------------------------------------------------------

(defn- theta-label
  "'foto i/N · θ°' for the current photo — i = its 1-based rank in θ order."
  []
  (let [cur (:current-idx @stage)
        n (count (nav-order))
        theta (:theta (nth (:photos @stage) cur nil))]
    (str "foto " (nav-rank cur) "/" n
         (when theta (str " · " (js/Math.round theta) "°")))))

(defn- update-toolbar!
  "Refresh the lock toggle's label + selected state. Called on every pose change so
   a frustum click (not just a toolbar click) reflects in the toggle."
  []
  (when-let [^js lock (.getElementById js/document "eaq-stage-lock")]
    (if (in-pose?)
      (do (.add (.-classList lock) "active")
          (set! (.-textContent lock) (theta-label)))
      (do (.remove (.-classList lock) "active")
          (set! (.-textContent lock) "Foto")))))

(defn- toggle-lock! []
  (cond
    ;; locked → free orbit. Works even with a ricalco open: you orbit to inspect the
    ;; trace from any angle (frustums stay hidden then — navigate back with Prev/Next
    ;; or by re-pressing the toggle; the overlay reprojects live as you orbit).
    (in-pose?) (leave-pose!)
    ;; free → lock onto the current photo, or the first in θ if none is current yet.
    :else (let [order (nav-order)]
            (when (seq order)
              (let [cur (:current-idx @stage)
                    idx (if (get-in @stage [:camera-poses cur]) cur (first order))]
                (go-in-pose! idx true))))))

(defn- make-tool-btn [id label title on-click]
  (let [^js b (.createElement js/document "button")]
    (set! (.-id b) id)
    (set! (.-className b) "action-btn view-btn")
    (set! (.-textContent b) label)
    (set! (.-title b) title)
    (.addEventListener b "click" (fn [^js e]
                                   (.preventDefault e) (.stopPropagation e)
                                   (on-click)))
    b))

(defn- setup-toolbar!
  "Mount the Prev / lock / Next buttons at the front of #viewport-toolbar (idempotent
   — a wrapper #eaq-stage-tools guards against double-mount)."
  []
  (when-let [^js tb (.getElementById js/document "viewport-toolbar")]
    (when-not (.getElementById js/document "eaq-stage-tools")
      (let [^js wrap (.createElement js/document "span")]
        (set! (.-id wrap) "eaq-stage-tools")
        (set! (.-className wrap) "eaq-stage-tools")
        (.appendChild wrap (make-tool-btn "eaq-stage-prev" "‹"
                                          "Foto precedente (ordine giradischi) — tasto ["
                                          #(nav-photo! -1)))
        (.appendChild wrap (make-tool-btn "eaq-stage-lock" "Foto"
                                          "Blocca/sblocca la vista sulla foto corrente"
                                          toggle-lock!))
        (.appendChild wrap (make-tool-btn "eaq-stage-next" "›"
                                          "Foto successiva (ordine giradischi) — tasto ]"
                                          #(nav-photo! 1)))
        (.insertBefore tb wrap (.-firstChild tb))))
    (update-toolbar!)))

(defn- teardown-toolbar! []
  (when-let [^js w (.getElementById js/document "eaq-stage-tools")]
    (.remove w)))

;; ------------------------------------------------------------
;; Lifecycle: activation from an evaluated (acquire …), refresh across Runs, and
;; deactivation when a Run no longer contains one.
;; ------------------------------------------------------------

(defn deactivate!
  "Tear the stage down entirely (no (acquire …) in the last eval)."
  []
  (when @stage
    (teardown-listeners!)
    (teardown-toolbar!)
    (viewport/unregister-frame-callback! :acquire-stage)
    (viewport/unregister-frame-callback! :camera-flight)
    (when (:in-pose? @stage) (backdrop/set-visible! false))
    (backdrop/clear!)
    (viewport/clear-frustum-layer!)
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
          ;; Build the photo backdrop plane (child of the camera) ONCE per activation,
          ;; hidden until go-in-pose! — without this set-photo!/set-visible! are silent
          ;; no-ops (backdrop bstate nil) and clicking a frustum flew the camera into
          ;; pose but showed NO photo, only the proxy (Vincenzo 2026-07-25).
          (backdrop/create! (viewport/get-camera))
          (backdrop/set-visible! false)
          (-> (load!) (.then (fn [_]
                               ;; toolbar earns its keep only with registered cameras
                               (when (seq (:camera-poses @stage)) (setup-toolbar!))
                               ;; frustums live on their OWN dedicated layer, so this
                               ;; is safe even when edit-path-2d opened in the same eval
                               ;; (its overlay owns the preview layer; the two coexist).
                               (show-frustums!)))))

      ;; same dir re-evaluated → keep camera/pose, just refresh geometry (dims/pose
      ;; may have changed) and re-show the layers the Run's clear-geometry wiped
      pending
      (do (swap! stage merge {:emit-pose (:emit-pose pending)
                              :dims (:dims pending)
                              :pending nil})
          (when (and (loaded?) (seq (:camera-poses @stage))) (setup-toolbar!))
          (when (:in-pose? @stage) (backdrop/set-visible! true))
          ;; re-show the frustums (in-pose or free-orbit set per state) on the
          ;; dedicated layer — safe with an open ricalco.
          (when (loaded?) (show-frustums!)))

      ;; no (acquire …) this eval → tear down
      @stage
      (deactivate!))))
