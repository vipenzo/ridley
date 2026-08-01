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
  (:require [clojure.string :as str]
            [ridley.math :as m]
            [ridley.viewport.core :as viewport]
            [ridley.editor.modal-evaluator :as modal]
            [ridley.editor.acquire-backdrop :as backdrop]
            [ridley.editor.codemirror :as cm]
            [ridley.editor.source-edit :as src]
            [ridley.editor.state :as state]
            [ridley.turtle.attachment :as attachment]
            [ridley.photogrammetry.camera :as pcamera]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.exif :as exif]
            [ridley.photogrammetry.triangulate :as tri]
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
  "acquire-state.json → {:proxy-pose <acq frame> :camera-poses {idx pose}
   :focal-mm :registration {idx {:rms-px :matched}}}.
   Camera poses are the acquisition-frame poses (idx 0 = the fixed vantage,
   camera-pose-0); idx>0 only when the photo carries a real camera-pose.

   :registration is the quality edit-acquire recorded when it solved each photo,
   and it was sitting unread in the file until Vincenzo hit exactly the problem
   it answers (2026-07-31: 'le ultime due foto probabilmente non erano
   allineate, vedevo i puntini e il dischetto in posti sbagliati'). In that very
   session photos 8 and 9 carry rms 9.9 / 11.3 px on 10 of 12 marks, against
   ~4.5 px on 12/12 for the rest — the file knew. A badly registered photo
   reprojects EVERYTHING wrong, so it must be labelled before it is clicked, not
   diagnosed after."
  [text]
  (let [{:keys [proxy-pose camera-pose-0 photos focal]}
        (js->clj (js/JSON.parse text) :keywordize-keys true)
        by-idx (fn [f] (into {} (keep (fn [[idx-kw v]]
                                        (when-let [x (f v)]
                                          [(js/parseInt (name idx-kw) 10) x]))
                                      photos)))
        cams (cond-> {}
               camera-pose-0 (assoc 0 camera-pose-0)
               :always (into (by-idx :camera-pose)))]
    {:proxy-pose proxy-pose
     :camera-poses cams
     :registration (by-idx #(when (:rms-px %) (select-keys % [:rms-px :matched])))
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

;; ------------------------------------------------------------
;; Marks drawn FROM THE SOURCE VALUE (brief-plane-marks.md, Seguito 3).
;;
;; The stage reads `:marks` off the evaluated (acquire …) and draws each one as
;; it is WRITTEN — its plane, its origin, and the points it was fitted through.
;; The point is diagnostic, and it is the twin of 'the source is the single
;; truth': the display reads from there too, so any divergence between what was
;; emitted and what is shown appears immediately, instead of surfacing three
;; steps later as geometry that looks displaced 'by some rule'.
;;
;; It also answers, without a measurement, the question that usually causes that
;; look: WHERE the mark's origin actually is — because everything the user
;; builds at the mark is centred on it, and a deliberately placed origin can sit
;; well away from the cluster of clicked points.
;; ------------------------------------------------------------

(def ^:private source-mark-color 0x66aaff)   ; azzurro: i mark come stanno nel sorgente

(def ^:private plane-disc-segments 40)

(defn- object-radius []
  (* 0.5 (m/magnitude (:dims @stage))))

(defn- source-mark-items
  "Disc + origin + fitted points for every mark of the evaluated acquire."
  []
  (when (:show-source-marks? @stage true)
    (into []
          (mapcat
           (fn [[_ mark]]
             (when (and (map? mark) (:position mark) (:heading mark) (:up mark))
               (let [pts (mapv vec (:from mark))
                     r (if (>= (count pts) 2)
                         (max 6.0 (* 1.15 (reduce max (map #(m/magnitude (m/v- % (:position mark)))
                                                           pts))))
                         (max 8.0 (* 0.3 (object-radius))))
                     {:keys [vertices faces]} (tri/disc-mesh mark r plane-disc-segments)]
                 (cond-> [{:type :mesh
                           :data {:vertices vertices :faces faces
                                  :material {:color source-mark-color :opacity 0.16
                                             :double-sided true}}}
                          {:type :dots
                           :data [{:pos (:position mark) :radius 1.2
                                   :color source-mark-color :opacity 0.95}]}]
                   (seq pts)
                   (conj {:type :dots
                          :data (mapv (fn [p] {:pos p :radius 0.8
                                               :color source-mark-color :opacity 0.6})
                                      pts)})))))
           (:source-marks @stage)))))

(declare plane-preview-items)

(defn- show-frustums!
  "Repaint the stage's OWN overlay layer: the ghost frustums (free orbit) plus
   whatever the plane-mark gesture is showing (in pose). One call, one layer —
   they share it because both are the stage's, and because the layer is
   deliberately NOT the preview layer an open edit-path-2d ricalco owns."
  []
  (viewport/show-frustum-layer! (-> (vec (frustum-preview-items))
                                    (into (source-mark-items))
                                    (into (plane-preview-items)))))

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

(declare plane-mode? plane-click! plane-key! toggle-plane-mode! toggle-source-marks!)

(defn- editable? [^js el]
  (boolean (and el (or (#{"INPUT" "TEXTAREA"} (.-tagName el))
                       (.-isContentEditable el)))))

(defn- on-pointerdown [^js e]
  (when (zero? (.-button e))
    (cond
      (free-orbit?)
      (swap! stage assoc :press {:x (.-clientX e) :y (.-clientY e)
                                 :idx (viewport/raycast-frustum-pick e)})
      ;; in pose, a clean click marks a plane point — but never while a modal
      ;; (an edit-path-2d ricalco) is up: there the click is the ricalco's.
      (and (plane-mode?) (:in-pose? @stage) (not (modal/active?)))
      (swap! stage assoc :press {:x (.-clientX e) :y (.-clientY e) :plane? true}))))

(defn- on-pointerup [^js e]
  (when (zero? (.-button e))
    (let [{:keys [x y idx plane?]} (:press @stage)]
      (swap! stage dissoc :press)
      (when (and (some? x)
                 (< (js/Math.hypot (- (.-clientX e) x) (- (.-clientY e) y)) click-slop-px))
        (if plane?
          (when (plane-mode?) (plane-click! e))
          (when (and (free-orbit?) (some? idx))
            ;; Defer the pose (which disables the orbit controls) to a macrotask so
            ;; TrackballControls processes THIS pointerup first — it early-returns while
            ;; disabled, leaving its ROTATE state + document listeners stranded, which
            ;; then re-activate on leave-pose! (Vincenzo 2026-07-25: "dopo Esc orbita
            ;; come se tenessi giù il tasto" — a lost mouse-up). Letting the controls
            ;; end the click cleanly first, then locking, avoids the stranded drag.
            ;; setTimeout (not requestAnimationFrame): rAF is throttled/paused in a
            ;; background tab, which would swallow the click; a 0-delay timer still runs
            ;; right after the pointerup dispatch, which is all the fix needs.
            (js/setTimeout (fn [] (go-in-pose! idx)) 0)))))))

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
        ;; plane mode owns n / Enter / Backspace / Esc while it is on — including
        ;; Esc, which it consumes BEFORE leave-pose!: pressing it once should
        ;; close the gesture, not throw you out of the photo you were measuring on.
        (and (plane-mode?) (not (modal/active?)) (plane-key! k))
        (.preventDefault e)
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
;; PLANE MARKS (dev-docs/brief-plane-marks.md, gradino 1).
;;
;; The hole this fills: with a registration PLATE the proxy sits UNDER the object,
;; so — unlike a box, whose faces double as tracing planes — it offers no working
;; plane ON the object. The user marks one by hand: click the same physical point
;; on ≥2 registered photos (it triangulates), do that for ≥3 points, and the plane
;; fitted through them is emitted as an ordinary named mark {:position :heading
;; :up} with heading = the surface normal. `(turtle (:zona (:marks A))
;; (edit-path-2d …))` then works with no new DSL — the mark IS the abstraction.
;;
;; It lives HERE, not in edit-acquire, on the v1 principle: edit-acquire does
;; REGISTRATION and closes; MEASUREMENT belongs to the stage. The cost that made
;; that debatable — the stage cannot write to the source — turns out to be small:
;; the emitted (acquire …) already carries a `:marks {…}` block, so appending an
;; entry to it is a bounded text edit (commit-plane-mark! below), and rename and
;; delete come free because the mark then lives in the user's own source.
;;
;; The verification is the gesture's own reprojection: the triangulated dots and
;; the fitted disc are drawn in the WORLD, so navigating photos with [ / ] shows
;; them from every registered angle. A disc that stays glued to the surface from
;; all of them is a correct plane; one that slides off is not. Same trick as the
;; ricalco, no new machinery.
;; ------------------------------------------------------------

(def ^:private plane-point-color 0x33ffcc)  ; verde-acqua: i punti triangolati
(def ^:private plane-ray-color 0x1f7f6b)    ; il raggio del click, più spento
(def ^:private plane-disc-color 0x33ffcc)
(def ^:private plane-pending-color 0xffcc33) ; giallo: un click che aspetta la seconda foto
(def ^:private plane-origin-color 0xff33cc)  ; magenta: l'origine del piano (come i mark)

(def ^:private world-frame
  "The identity proxy pose. bridge/box-basis of it is the identity matrix, so
   bridge/editor->solver-pose against it turns an editor camera pose into a
   solver pose in the WORLD frame — which is where plane marks belong: the
   stage's cameras are already reconciled to the emitted frame, and the emitted
   :marks are world poses. No object-frame round trip to get the sign of."
  {:position [0.0 0.0 0.0] :heading [0.0 0.0 1.0] :up [0.0 1.0 0.0]})

(defn- say!
  "Report to the output console. The stage has no status panel of its own (it is
   viewport state, not a modal session), and these messages carry NUMBERS — the
   residual, the parallax, the planarity — that the user should be able to read
   back after the fact rather than watch fade from a toast."
  [msg]
  (state/capture-println (str ";; piano: " msg)))

(defn- plane-mode? [] (some? (:plane @stage)))
(defn- plane-picks [] (get-in @stage [:plane :picks] []))

(defn- stage-intrinsics
  "Pinhole intrinsics for the CURRENT photo's pixel size — the 35mm-equivalent
   focal against the photo's own aspect (the diagonal convention). Stored with
   each click rather than assumed session-wide, so a session that mixes pixel
   sizes still triangulates correctly."
  []
  (when-let [[iw ih] (backdrop/image-size)]
    (pcamera/intrinsics-from-fov
     (pcamera/equiv-focal->hfov-deg (:focal-mm @stage) (/ iw ih)) iw ih)))

(defn- world-solver-pose [idx]
  (when-let [cam (get-in @stage [:camera-poses idx])]
    (bridge/editor->solver-pose cam world-frame)))

(defn- plausible-point?
  "A triangulated point must land near the acquired object. Two rays that nearly
   agree can meet a long way off (the same failure the retrace's plausible-hit?
   rejects); flinging a point out there and fitting a plane to it would produce a
   confident, meaningless mark."
  [p]
  (<= (m/magnitude (m/v- p (:position (:emit-pose @stage))))
      (+ (object-radius) 60.0)))

(defn- retriangulate
  "Re-solve one pick from its observations. Keyed by photo index so a second
   click on the same photo REPLACES that view's observation (the natural way to
   correct a slip) instead of piling up two contradictory rays."
  [pick]
  (let [entries (sort-by key (:obs pick))
        idxs (mapv key entries)
        obs (mapv val entries)
        fit (tri/triangulate (stage-intrinsics) obs)]
    (assoc pick :fit (when fit
                       (assoc fit :photos idxs
                              :plausible? (plausible-point? (:point fit)))))))

(defn- fitted-points []
  (into [] (keep #(when (get-in % [:fit :plausible?]) (get-in % [:fit :point]))
                 (plane-picks))))

;; ---- preview ----

(defn- disc-item [mark radius opacity]
  (let [{:keys [vertices faces]} (tri/disc-mesh mark radius plane-disc-segments)]
    {:type :mesh
     :data {:vertices vertices :faces faces
            :material {:color plane-disc-color :opacity opacity :double-sided true}}}))

(defn- disc-radius
  "Size the verification disc to the clicked zone — the spread of the points,
   with a margin so its rim reaches past them (that overhang is what makes a
   wrong plane visibly peel away from the surface). A single declared point has
   no spread, so it gets a fraction of the object instead."
  [pts]
  (if (< (count pts) 2)
    (max 8.0 (* 0.35 (object-radius)))
    (let [n (count pts)
          c (mapv #(/ % n) (reduce m/v+ [0.0 0.0 0.0] pts))]
      (max 6.0 (* 1.25 (reduce max (map #(m/magnitude (m/v- % c)) pts)))))))

(defn- ray-items
  "The current point's click rays, drawn as short world segments straddling the
   object. This is the gesture's navigation aid and it costs nothing: reprojected
   onto ANOTHER photo, a ray from the first click IS the epipolar line — the
   locus on which the same physical point must lie. Click photo A, press ], and
   the line tells you where to look."
  [pick]
  (let [c (:position (:emit-pose @stage))
        r (max 15.0 (* 1.1 (object-radius)))]
    (into []
          (keep (fn [[_ {:keys [px pose intrinsics]}]]
                  (when-let [{:keys [origin dir]} (pcamera/pixel-ray intrinsics pose px)]
                    (let [t (m/dot (m/v- c origin) dir)
                          a (m/v+ origin (m/v* dir (max 1.0 (- t r))))
                          b (m/v+ origin (m/v* dir (+ t r)))]
                      {:type :lines :data [{:from a :to b :color plane-ray-color}]}))))
          (:obs pick))))

(defn- pending-dot-items
  "A YELLOW dot for a click that is still waiting for its second photo — the
   'did that register?' feedback the gesture lacked (Vincenzo 2026-07-31: 'dopo
   il click del primo punto non succede niente e non si vede dove si è
   cliccato'; the ray alone is invisible on the photo it was cast from, since it
   points straight at you, and the green dot needs two views to exist).

   Placed on the ray at its closest approach to the object. That choice is not
   cosmetic: EVERY point of the ray reprojects onto the very pixel that was
   clicked, so on the photo you clicked the dot sits exactly under the cursor —
   while on any other photo it slides along the epipolar line, which is the
   honest picture of what is known so far (direction yes, depth not yet)."
  [pick]
  (when-not (:fit pick)
    (let [c (:position (:emit-pose @stage))
          dots (into [] (keep (fn [[_ {:keys [px pose intrinsics]}]]
                                (when-let [{:keys [origin dir]}
                                           (pcamera/pixel-ray intrinsics pose px)]
                                  {:pos (m/v+ origin (m/v* dir (m/dot (m/v- c origin) dir)))
                                   :radius 1.0
                                   :color plane-pending-color
                                   :opacity 0.9}))
                              (:obs pick)))]
      (when (seq dots) [{:type :dots :data dots}]))))

(defn- plane-preview-items
  "Everything the plane gesture draws: the current point's rays and pending
   click, every triangulated point as a dot, the candidate plane's disc, and the
   discs of marks already committed this session (kept on so the user can keep
   checking them across photos after the source has been written)."
  []
  (when (plane-mode?)
    (let [picks (plane-picks)
          pts (fitted-points)
          dots (into [] (keep (fn [p]
                                (when-let [f (:fit p)]
                                  {:pos (:point f)
                                   :radius 1.1
                                   :color plane-point-color
                                   :opacity (if (:plausible? f) 0.95 0.35)}))
                              picks))]
      (cond-> (ray-items (peek picks))
        :always (into (pending-dot-items (peek picks)))
        (seq dots) (conj {:type :dots :data dots})
        (:candidate (:plane @stage))
        (conj (disc-item (:candidate (:plane @stage))
                         (:candidate-radius (:plane @stage) (disc-radius pts)) 0.35)
              ;; the origin itself, drawn distinctly from the disc it centres:
              ;; it is the point every coordinate written against this mark will
              ;; be measured from, so it must be visible, not implied.
              {:type :dots
               :data [{:pos (:position (:candidate (:plane @stage)))
                       :radius 1.4 :color plane-origin-color :opacity 1.0}]})
        :always (into (map (fn [{:keys [mark radius]}] (disc-item mark radius 0.22))
                           (get-in @stage [:plane :committed] [])))))))

(defn- redraw-plane! []
  (show-frustums!)
  ;; update-toolbar! refreshes the HUD too — it is called on EVERY pose change
  ;; (frustum click, Prev/Next, photo lock), so hanging the HUD off it is what
  ;; keeps "foto N — reg. …" honest as you navigate, instead of freezing on the
  ;; photo you happened to be on when the gesture started.
  (update-toolbar!))

;; ---- registration quality of a photo (read from acquire-state.json) ----

(def ^:private poor-registration-px
  "Above this per-photo PnP rms, a photo's camera pose is not to be trusted for
   measurement: everything drawn in the world reprojects visibly off on it. The
   plate sessions register at ~4-5 px when they register well and at 10-11 px
   when they don't (a grazing shot losing 2 of 12 marks), so the gap is wide and
   8 px sits in it."
  8.0)

(defn- registration-of [idx] (get-in @stage [:registration idx]))

(defn- poorly-registered? [idx]
  (when-let [{:keys [rms-px]} (registration-of idx)]
    (> rms-px poor-registration-px)))

(defn- registration-label
  "'reg. 11.3px · 10/12' for the current photo, or nil when unknown."
  [idx]
  (when-let [{:keys [rms-px matched]} (registration-of idx)]
    (str "reg. " (src/fmt-number rms-px) "px"
         (when matched (str " · " matched " marker")))))

;; ---- the HUD ----
;; The gesture's state, drawn where the user is looking. The first live run
;; (Vincenzo 2026-07-31) worked but read as opaque: the state lived in a cramped
;; toolbar label and in console lines that scroll away, and every step had to be
;; advanced by a key you had to remember. Here the three steps are always all
;; visible with the current one highlighted, and every action is a BUTTON whose
;; enabled/disabled state IS the answer to "what can I do now" (keys still work).

(defn- enough-points?
  "Whether 'Crea il piano' can fire: three triangulated points, or the single
   declared one of the plate-parallel shortcut."
  []
  (let [n (count (fitted-points))]
    (or (>= n 3) (and (= n 1) (:plate? @stage)))))

(defn- hud-el [] (.getElementById js/document "eaq-plane-hud"))

(defn- el
  "Small DOM helper: tag + class + text, children appended."
  [tag cls & {:keys [text children html]}]
  (let [^js e (.createElement js/document tag)]
    (when cls (set! (.-className e) cls))
    (when text (set! (.-textContent e) text))
    (when html (set! (.-innerHTML e) html))
    (doseq [^js c children] (when c (.appendChild e c)))
    e))

(defn- hud-step
  "One line of the procedure. `state` is :done, :current or :todo."
  [state n label]
  (el "div" (str "eaq-hud-step " (name state))
      :children [(el "span" "eaq-hud-bullet"
                     :text (case state :done "✓" :current "▸" "·"))
                 (el "span" nil :text (str n ". " label))]))

(defn- hud-detail
  "The paragraph under the steps: what to do RIGHT NOW, with the numbers."
  []
  (let [picks (plane-picks)
        cur (peek picks)
        obs (:obs cur)
        fit (:fit cur)
        here (:current-idx @stage)
        cand (:candidate (:plane @stage))
        box (el "div" "eaq-hud-detail")
        add! (fn [cls txt] (.appendChild box (el "div" cls :text txt)))]
    (cond
      cand
      (do (if (:exact? cand)
            (add! "eaq-hud-warn"
                  (str "3 punti: piano esatto, planarità non verificata · "
                       (src/fmt-number (:tilt-per-mm-deg cand)) "°/mm"))
            (add! (if (> (:flatness-mm cand) 1.0) "eaq-hud-warn" "eaq-hud-good")
                  (str "Planarità " (src/fmt-number (:flatness-mm cand)) " mm")))
          (when (:exact? cand)
            (add! "eaq-hud-hint" "Un quarto punto lontano dagli altri rende la planarità un controllo."))
          (add! "eaq-hud-hint"
                "Cambia foto con [ e ] : il dischetto deve restare incollato alla superficie.")
          (when (> (:flatness-mm cand) 1.0)
            (add! "eaq-hud-warn" "La zona non è molto piana — guarda bene prima di accettare."))
          (let [off (get-in @stage [:plane :offset-mm] 0.0)]
            (when-not (zero? off)
              (add! "eaq-hud-warn"
                    (str "Spostato a mano di " (src/fmt-number off)
                         "mm lungo la normale (frecce su/giu)"))))
          (add! nil (str "Origine (pallino magenta): "
                         (if (:origin-override (:plane @stage))
                           "dove hai cliccato."
                           "al centro dei punti.")))
          (add! "eaq-hud-hint"
                "Un click sul piano la sposta lì — è il punto da cui si misura tutto quello che ci disegnerai."))

      (not (:in-pose? @stage))
      (add! "eaq-hud-hint"
            (str "Sei in vista libera: i punti già presi restano. Per cliccarne "
                 "altri torna dentro una foto — bottone Foto, o clicca una piramide."))

      :else
      (do
        (when-let [r (registration-label here)]
          (add! (if (poorly-registered? here) "eaq-hud-bad" nil)
                (str "Foto " (nav-rank here) " — " r
                     (when (poorly-registered? here) "  ⚠ mal registrata"))))
        (when (poorly-registered? here)
          (add! "eaq-hud-bad"
                "Su questa foto tutto si riproietta storto: usane un'altra."))
        (case (count obs)
          0 (add! "eaq-hud-hint" "Clicca un punto ben riconoscibile della zona piana.")
          1 (add! "eaq-hud-hint"
                  (str "Click preso — è il pallino GIALLO. Ora cambia foto con ] e "
                       "riclicca LO STESSO punto: lo troverai sulla linea verde, e il "
                       "pallino diventa verde quando il punto è fissato."))
          nil)
        (when fit
          (add! (cond (not (:plausible? fit)) "eaq-hud-bad"
                      (or (< (:parallax-deg fit) tri/min-parallax-deg)
                          (> (:max-residual-px fit) 25.0)) "eaq-hud-warn"
                      :else "eaq-hud-good")
                (str "scarto " (src/fmt-number (:max-residual-px fit)) " px · "
                     "parallasse " (src/fmt-number (:parallax-deg fit)) "°")))
        (when (seq obs)
          (let [shots (el "div" "eaq-hud-shots")]
            (doseq [[idx _] (sort-by key obs)]
              (.appendChild shots
                            (el "div" "eaq-hud-shot"
                                :children [(el "span" nil :text (str "foto " (nav-rank idx)))
                                           (el "span" (when (poorly-registered? idx) "eaq-hud-bad")
                                               :text (if (poorly-registered? idx) "⚠" "✓"))])))
            (.appendChild box shots)))))
    box))

(defn- hud-button [label title enabled? primary? on-click]
  (let [^js b (el "button" (str "action-btn view-btn" (when primary? " primary")))]
    (set! (.-textContent b) label)
    (set! (.-title b) title)
    (set! (.-disabled b) (not enabled?))
    (.addEventListener b "click" (fn [^js e]
                                   (.preventDefault e) (.stopPropagation e)
                                   (on-click)))
    b))

(declare set-candidate-origin! next-plane-point! add-another-point! fit-candidate! accept-candidate!
         discard-candidate! recentre-origin! nudge-plane! reset-offset!
         undo-plane-click! stop-plane!)

(defn- hud-actions []
  (let [picks (plane-picks)
        cur (peek picks)
        cand (:candidate (:plane @stage))
        enough? (enough-points?)
        row (el "div" "eaq-hud-actions")]
    (doseq [^js b (if cand
                    [(hud-button "Accetta" "Scrive il mark nel sorgente (Invio)"
                                 true true accept-candidate!)
                     (hud-button "Aggiungi punto"
                                 "Torna a cliccare punti, tenendo quelli che ci sono (n)"
                                 true false add-another-point!)
                     (hud-button "▲" "Alza il piano lungo la sua normale (freccia su)"
                                 true false #(nudge-plane! plane-nudge-step))
                     (hud-button "▼" "Abbassa il piano lungo la sua normale (freccia giu)"
                                 true false #(nudge-plane! (- plane-nudge-step)))
                     (hud-button "Origine al centro"
                                 "Rimette l'origine al centro dei punti cliccati"
                                 (some? (:origin-override (:plane @stage))) false
                                 recentre-origin!)
                     (hud-button "Rifai" "Scarta il piano proposto (Backspace)"
                                 true false discard-candidate!)]
                    [(hud-button "Punto successivo"
                                 "Chiude questo punto e ne comincia un altro (n)"
                                 (boolean (:plausible? (:fit cur))) false next-plane-point!)
                     (hud-button "Crea il piano"
                                 (if enough?
                                   "Calcola il piano e mostra il dischetto (Invio)"
                                   "Servono 3 punti triangolati")
                                 enough? enough? fit-candidate!)
                     (hud-button "Annulla click" "Toglie l'ultimo click (Backspace)"
                                 (boolean (seq (:obs cur))) false undo-plane-click!)])]
      (.appendChild row b))
    (.appendChild row (hud-button "Chiudi" "Esce dal modo piano (Esc)" true false stop-plane!))
    row))

(defn- hud-content []
  (let [ready (count (fitted-points))
        cand (:candidate (:plane @stage))
        ok? (enough-points?)
        frag (.createDocumentFragment js/document)]
    (.appendChild frag (el "div" "eaq-hud-title"
                           :text (if-let [nm (:name (:edit (:plane @stage)))]
                                   (str "PIANO · :" nm)
                                   "PIANO DI LAVORO")))
    ;; Always 'di 3', even where one point would legally do: the plate shortcut
    ;; is only valid when the user KNOWS the zone is parallel to the plate, and
    ;; advertising '1 di 1' would read as 'one point is normally enough'.
    (.appendChild frag (hud-step (if (or cand ok?) :done :current)
                                 1 (str "Punti: " ready " di 3")))
    (.appendChild frag (hud-step (cond cand :done ok? :current :else :todo)
                                 2 "Crea il piano"))
    (.appendChild frag (hud-step (if cand :current :todo) 3 "Controlla e accetta"))
    (.appendChild frag (hud-detail))
    (.appendChild frag (hud-actions))
    frag))

(defn- refresh-plane-hud!
  "Rebuild the HUD from the current state (or remove it when plane mode is off).
   Rebuilt wholesale rather than patched: it is a dozen nodes, and a panel that
   is a pure function of the state can never drift out of sync with it."
  []
  (if-not (plane-mode?)
    (when-let [^js p (hud-el)] (.remove p))
    (when-let [^js host (.getElementById js/document "viewport-panel")]
      (let [^js panel (or (hud-el)
                          (let [^js p (el "div" nil)]
                            (set! (.-id p) "eaq-plane-hud")
                            (.appendChild host p)
                            p))]
        (set! (.-innerHTML panel) "")
        (.appendChild panel (hud-content))))))

;; ---- the gesture ----

(defn- plane-status
  "One line of live state for the toolbar button: which point is being clicked,
   how many photos it has, and the fit quality once it has enough."
  []
  (let [picks (plane-picks)
        n (count picks)
        cur (peek picks)
        obs (count (:obs cur))
        fit (:fit cur)]
    (if (:candidate (:plane @stage))
      "Piano proposto · Invio accetta"
      (str "Piano · punto " n
           (cond
             (zero? obs) " (clicca)"
             (= 1 obs) " (1 foto — vai su un'altra)"
             fit (str " (" obs " foto, "
                      (src/fmt-number (:rms-px fit)) "px, "
                      (src/fmt-number (:parallax-deg fit)) "°)")
             :else (str " (" obs " foto)"))))))

(defn- start-plane! []
  (swap! stage assoc :plane {:picks [{:obs {}}] :committed []})
  (say! (str "modo piano attivo. Clicca lo STESSO punto su almeno 2 foto ("
             "usa [ e ] per cambiare), poi 'n' per il punto successivo. "
             "Servono 3 punti; Invio crea il piano, Esc esce."))
  (redraw-plane!))

(declare unwrap-edit-mark!)

(defn- stop-plane!
  "Leave plane mode. When an `(edit-plane-mark …)` opened it, the wrapper MUST
   come out of the source on the way out — otherwise the next eval re-opens the
   editor and there is no way to stop, since the intent to edit lives in the
   source rather than in a mode."
  []
  (let [edit (:edit (:plane @stage))]
    (swap! stage dissoc :plane)
    (redraw-plane!)                    ; :plane gone → the HUD removes itself
    (when edit (unwrap-edit-mark! edit))))

(defn- place-origin!
  "With a plane already proposed, a click MOVES ITS ORIGIN — the point everything
   drawn against the mark is measured from.

   This costs a single click, on one photo, and is exact: the plane is known, so
   the click's ray meets it in exactly one point (m/ray-plane-point, the same
   inverse the ricalco uses) — no triangulation, no second view. Which matters,
   because the three components of a mark do not come from the same place: the
   NORMAL is a measurement, averaged over the clicked points and better the more
   of them there are; `up` is inherited from the object's own up, so it is
   reproducible; but the ORIGIN was, until now, the centroid of wherever the user
   happened to click — an artefact of the gesture, not a property of the surface,
   which silently moved everything written against the mark whenever the mark was
   redone (Vincenzo 2026-07-31: 'la posizione in quel piano da cosa dipende?').
   Placing it deliberately makes it reproducible: click the same corner again and
   the origin comes back to the same corner."
  [px pose k]
  (let [{:keys [candidate]} (:plane @stage)
        hit (some-> (pcamera/pixel-ray k pose px)
                    (m/ray-plane-point (:position candidate) (:heading candidate)))]
    (cond
      (nil? hit)
      (say! (str "il click non incontra il piano — da questa foto lo vedi troppo "
                 "di taglio, provane un'altra"))
      (not (plausible-point? hit))
      (say! "il punto cade lontano dall'oggetto: da questa foto il piano è quasi di taglio")
      :else
      (do (set-candidate-origin! hit)
          ;; kept OUTSIDE the candidate on purpose: the candidate is thrown away
          ;; whenever the user goes back to add a point, and a deliberately
          ;; placed origin must survive that and be carried onto the refit.
          (swap! stage assoc-in [:plane :origin-override] hit)
          (say! "origine del piano spostata dove hai cliccato")
          (redraw-plane!)))))

(defn- add-observation!
  "Record this photo's view of the current point, re-triangulate, and say what is
   wrong with the result when something is."
  [idx px pose k]
  (swap! stage update-in [:plane :picks]
         (fn [picks]
           (let [i (dec (count picks))]
             (-> picks
                 (update i update :obs assoc idx {:px px :pose pose :intrinsics k})
                 (update i retriangulate)))))
  (when (poorly-registered? idx)
    (say! (str "attenzione: la foto " (nav-rank idx) " è registrata male ("
               (registration-label idx) "). Il click è preso lo stesso, ma "
               "su questa foto TUTTO si riproietta storto: se puoi, usane un'altra.")))
  (let [fit (:fit (peek (plane-picks)))]
    (cond
      (nil? fit) nil
      (not (:plausible? fit))
      (say! (str "il punto triangolato cade lontano dall'oggetto — i due click non "
                 "sono sullo stesso punto fisico, oppure le foto sono troppo vicine fra loro"))
      (< (:parallax-deg fit) tri/min-parallax-deg)
      (say! (str "parallasse " (src/fmt-number (:parallax-deg fit))
                 "° — troppo poca: aggiungi un click da una foto più lontana"))
      (> (:max-residual-px fit) 25.0)
      (say! (str "residuo " (src/fmt-number (:max-residual-px fit))
                 "px — uno dei click non è sullo stesso punto fisico. "
                 (if-let [w (:worst-obs fit)]
                   (str "Quello sbagliato è sulla foto "
                        (nav-rank (nth (:photos fit) w))
                        " (stessa numerazione della toolbar): vacci con [ o ] e riclicca.")
                   "Con due sole foto non si può dire quale: aggiungine una terza.")))
      :else nil))
  (redraw-plane!))

(defn- plane-click!
  "One click in plane mode. Its meaning depends on the stage of the gesture: with
   a plane already proposed it places that plane's ORIGIN; before that it records
   this photo's observation of the current point."
  [^js e]
  (let [idx (:current-idx @stage)]
    (if-let [pose (world-solver-pose idx)]
      (if-let [px (backdrop/pixel-under-pointer e (viewport/get-camera) (viewport/get-canvas))]
        (if-let [k (stage-intrinsics)]
          (if (:candidate (:plane @stage))
            (place-origin! px pose k)
            (add-observation! idx px pose k))
          (say! "nessuna foto caricata: non so a che risoluzione riferire il click"))
        (say! "il click è caduto fuori dalla foto"))
      (say! (str "la foto " (nav-rank idx) " non ha una posa registrata: non può contribuire — "
                 "cambiane una con [ o ]")))))

(defn- next-plane-point!
  "Close the current point and start another. Refuses to advance from a point
   that has not triangulated, so the user never discovers at fit time that a
   point they thought was placed contributed nothing."
  []
  (let [cur (peek (plane-picks))]
    (cond
      (nil? (:fit cur))
      (say! "questo punto non è ancora triangolato: serve un click su almeno 2 foto")
      (not (:plausible? (:fit cur)))
      (say! "questo punto non è affidabile: riclicca prima di passare al successivo")
      :else
      (do (swap! stage update-in [:plane :picks] conj {:obs {}})
          (redraw-plane!)))))

(defn- add-another-point!
  "'One more point'. With a plane on screen this means going BACK to picking
   while keeping the points already gathered: the proposed plane is dropped (a
   later Enter refits it, carrying a placed origin across) and an empty slot is
   opened for the next click. Without one it is the ordinary 'next point'."
  []
  (if (:candidate (:plane @stage))
    (do (swap! stage update :plane
               (fn [p] (-> p
                           (dissoc :candidate :candidate-radius)
                           (update :picks (fn [ps]
                                            (if (or (:fit (peek ps)) (seq (:obs (peek ps))))
                                              (conj ps {:obs {}})
                                              ps))))))
        (say! "aggiungi un punto: click su 2 foto, poi Invio per rifare il piano")
        (redraw-plane!))
    (next-plane-point!)))

(defn- undo-plane-click!
  "Backspace: drop the current point's observation on THE PHOTO YOU ARE LOOKING
   AT — the one you just mis-clicked — falling back to the highest-numbered photo
   if this one has none. With the point already empty, drop the point itself."
  []
  (let [here (:current-idx @stage)]
    (swap! stage update-in [:plane :picks]
           (fn [picks]
             (let [i (dec (count picks))
                   obs (:obs (nth picks i))]
               (cond
                 (seq obs)
                 (let [victim (if (contains? obs here) here (key (last (sort-by key obs))))]
                   (update picks i #(retriangulate (update % :obs dissoc victim))))
                 (> (count picks) 1) (vec (butlast picks))
                 :else picks)))))
  (redraw-plane!))

;; ---- source write-back ----

(defn- acquire-form-bounds
  "[from to) of THIS stage's `(acquire \"dir\" …)` in the buffer. Matched on the
   dir string so a source holding more than one acquire is never amended in the
   wrong one; falls back to the first `(acquire` only when the dir literal isn't
   found verbatim (a hand-edited path)."
  [text]
  (or (modal/find-form-bounds text (str "(acquire " (pr-str (:dir @stage))))
      (modal/find-form-bounds text "(acquire")))

(defn- next-plane-name
  "`piano-N`, N one past the highest already in the form — read from the SOURCE,
   not from stage state, because that is where the marks actually live (the user
   may have renamed or deleted some since)."
  [form-text]
  (let [nums (map #(js/parseInt (second %) 10)
                  (re-seq #":piano-(\d+)\b" form-text))]
    (str "piano-" (inc (reduce max 0 nums)))))

(defn- mark-literal
  "The `{…}` source of a plane mark, carrying the points it was fitted through
   under `:from`.

   `:from` is the mark's own EVIDENCE, and it is what makes the mark re-editable
   later ((edit-plane-mark …)): without it the source keeps the result and
   throws away what produced it, so 'add a better point to this plane' is
   impossible and the only move is to build a new mark from scratch — which
   moves the origin and takes whatever was written against it along. `turtle`
   and every other consumer ignore the extra key.

   It holds the TRIANGULATED points, not the per-photo clicks: a point can be
   added or dropped and the plane refitted, but a single 2D click cannot be
   revisited. That boundary is deliberate — storing observations would mean
   storing pixels against photo identities, which the source has no business
   knowing."
  [{:keys [position heading up]} points]
  (str "{:position " (src/fmt-vec3 position)
       " :heading " (src/fmt-vec3 heading)
       " :up " (src/fmt-vec3 up)
       (when (seq points)
         (str " :from [" (str/join " " (map src/fmt-vec3 points)) "]"))
       "}"))

(defn- mark-form
  "The RESTING form a plane mark is written as: `(plane-mark {…})`.

   Not decoration. It restores the family grammar for the pair
   `edit-plane-mark ⇄ plane-mark`: re-opening a mark becomes 'put edit- in front
   of the head and Run', the same gesture as every other editor, instead of
   wrapping a multi-line map by hand. And it makes the source say what the map
   IS rather than leaving an anonymous literal. A bare literal stays valid
   everywhere — old marks keep working — but everything emitted from here on is
   the one dialect, including a mark whose edit started from a bare literal."
  [mark points]
  (str "(plane-mark " (mark-literal mark points) ")"))

(defn- mark-entry-text [nm mark points]
  (str ":" nm " " (mark-form mark points)))

(defn- commit-plane-mark!
  "Append `mark` to the evaluated `(acquire …)`'s :marks map and re-run the
   definitions. A bounded text edit — only the :marks block's braces move — so
   everything else the user has written or hand-tuned in that form survives
   byte-identical. Returns the name written, or nil when the form can't be found."
  [mark points]
  (let [text (cm/get-value)]
    (if-let [[from to] (acquire-form-bounds text)]
      (if-let [[o e i] (src/map-value-bounds text from to ":marks")]
        (let [nm (next-plane-name (.substring text from to))
              updated (src/append-map-entry (.substring text o e)
                                            (mark-entry-text nm mark points)
                                            ":marks" (src/column-of text i))]
          (modal/replace-source! o e updated)
          (modal/run-definitions!)
          nm)
        (do (say! (str "la forma (acquire …) non ha uno slot :marks — aggiungi "
                       ":marks {} dentro la mappa e riprova"))
            nil))
      (do (say! "non trovo la forma (acquire …) nel sorgente")
          nil))))

;; ---- (edit-plane-mark …): rieditare un mark dal SORGENTE ----
;;
;; Design (Vincenzo 2026-07-31, brief-plane-marks §Seguito): no selection UI on
;; the stage. The mark to edit is marked IN THE SOURCE by wrapping it —
;;
;;   :marks {:piano-1 {…}
;;           :piano-2 (edit-plane-mark {…})}
;;
;; — eval opens the editor on it, confirm leaves the updated literal behind. The
;; edit-* family grammar applied to an INNER form: transient, round-tripping,
;; source as the single truth, and the stage stays pure by default because the
;; intent to edit lives in the source rather than in a mode.
;;
;; Two deliberate deviations from the family, both accepted explicitly:
;; - the name is `edit-plane-mark`, not `edit-mark`: every other member pairs
;;   with an existing form of the same name, and `(mark :A)` is the PATH anchor
;;   command — a different thing, which `edit-mark` would promise to edit;
;; - cancel does not rename a head (`edit-X` → `(X …)`) because there is no call
;;   to rename to: it UNWRAPS back to the literal. The scaffolding does not
;;   survive under another name, it falls away entirely — which is the same
;;   'inline deliberato del letterale' P4a already wanted.
;;
;; Called during the eval of the (acquire …) opts map, i.e. BEFORE acquire
;; itself, and it returns its argument untouched so the acquire value stays
;; valid and everything downstream keeps working while the edit is open.

(def ^:private edit-mark-head "(edit-plane-mark")

(defn ^:export request-mark-edit!
  "SCI entry point for `(edit-plane-mark <literal>)` / `(edit-plane-mark)`.
   Notes the request for after-eval! and returns the literal unchanged (nil for
   the empty creation form)."
  ([] (request-mark-edit! nil))
  ([mark]
   (swap! stage (fn [s] (update (or s {}) :pending-edits (fnil conj []) {:mark mark})))
   mark))

(defn- edit-mark-bounds
  "[from to) of the `(edit-plane-mark …)` form in the buffer, or nil."
  [text]
  (modal/find-form-bounds text edit-mark-head))

(defn- unwrap-edit-mark!
  "Give up on an edit. Now that a mark's resting form is `(plane-mark {…})` this
   is the family's ordinary cancel — rename the head back, body byte-identical
   (modal/strip-head) — and the deviation this editor used to carry is gone.

   The EMPTY creation spelling has no body to keep, and `(plane-mark)` would be
   an arity error, so its whole `:key (edit-plane-mark)` entry is removed
   instead of leaving a `nil` behind for the user to sweep up."
  [_edit]
  (let [text (cm/get-value)]
    (when-let [[from to] (modal/find-form-bounds text edit-mark-head)]
      (if (str/blank? (src/form-inner text from to edit-mark-head))
        (let [[k-from _] (src/entry-bounds-before text from)]
          (modal/replace-source! (or k-from from) to "")
          (modal/run-definitions!)
          (say! "creazione annullata — la voce è stata tolta dal sorgente"))
        (do (modal/replace-source! from to
                                   (modal/strip-head text from to edit-mark-head "(plane-mark"))
            (modal/run-definitions!)
            (say! "edit annullato — il mark è rimasto com'era"))))))

(defn- mark-name-before
  "The `:name` key this wrapped value belongs to, read backwards from the form's
   opening paren — used only to say WHICH mark is being edited; the write-back
   itself needs no name, it owns a source range."
  [text from]
  (when-let [m (re-find #":([A-Za-z0-9*+!_'?<>=/.-]+)\s*$" (.substring text 0 from))]
    (second m)))

(defn- fit-candidate!
  "First Enter: fit the plane through the triangulated points and show it as a
   translucent disc — but write NOTHING yet. The disc is the check the brief
   asks for: it lives in the world, so navigating the photos with [ / ] shows it
   from every registered angle, and it stays glued to the surface only if the
   plane is right. Accepting is a second, deliberate Enter."
  []
  (let [pts (fitted-points)
        picks (plane-picks)
        cams (into [] (keep #(get-in @stage [:camera-poses % :position])
                            (distinct (mapcat #(keys (:obs %)) picks))))
        toward (when (seq cams)
                 (mapv #(/ % (count cams)) (reduce m/v+ [0.0 0.0 0.0] cams)))
        emit (:emit-pose @stage)
        hints [(:up emit) (:heading emit)]
        mark (cond
               (>= (count pts) 3)
               (tri/fit-plane-mark pts {:toward toward :up-hints hints})

               ;; One point is enough when the zone is KNOWN parallel to the
               ;; plate — an object standing on the turntable, its upper face
               ;; level. The plate's own axis supplies the normal; the click only
               ;; fixes the height. Plate proxies only: a box's heading is its
               ;; front face, which says nothing about what the object rests on.
               (and (= 1 (count pts)) (:plate? @stage))
               (let [n (:heading emit)
                     n (if (and toward (neg? (m/dot n (m/v- toward (first pts)))))
                         (m/v* n -1.0) n)]
                 (tri/plane-through-point (first pts) n hints))

               :else nil)]
    (if (nil? mark)
      (say! (str "servono 3 punti triangolati (ne hai " (count pts) ")"
                 (when (:plate? @stage)
                   ", oppure 1 solo se la zona è parallela al piatto")
                 (when (>= (count pts) 3)
                   " — i punti sono quasi allineati, non definiscono un piano")))
      (let [fit-origin (:position mark)   ; the fit's own centroid, for 'Origine al centro'
            ;; A deliberately placed origin must SURVIVE a refit — dropped back
            ;; to the new centroid it would wander on its own, which is exactly
            ;; the defect place-origin! cures. Project the old origin onto the
            ;; new plane (its nearest point): the same physical spot, expressed
            ;; on the plane that now replaces the old one.
            placed (:origin-override (:plane @stage))
            mark (if placed
                   (let [n (:heading mark)
                         d (m/dot (m/v- placed fit-origin) n)]
                     (assoc mark :position (m/v- placed (m/v* n d))))
                   mark)]
        (swap! stage update :plane assoc
               :candidate mark
               :candidate-radius (disc-radius pts)
               :origin-centroid fit-origin
               ;; re-anchor the override to its projection, so repeated refits
               ;; do not keep re-projecting an ever-staler point
               :origin-override (when placed (:position mark))
               ;; a refit is a fresh measurement: it supersedes a hand offset
               :candidate-base-pos (:position mark)
               :offset-mm 0.0)
        (say! (str "piano proposto su " (count pts) " punti. "
                   (if (:exact? mark)
                     ;; Three points fit ANY plane exactly, so reporting a
                     ;; flatness of 0.00 here would be false comfort — it is
                     ;; arithmetic, not a check. Say what IS known instead.
                     (str "Con 3 punti il piano ci passa esatto: la planarità non è "
                          "una verifica, vale 0 comunque. Quello che si può dire è il "
                          "condizionamento: presa larga " (src/fmt-number (:width-mm mark))
                          "mm, quindi 1mm di errore su un click inclina la normale di "
                          (src/fmt-number (:tilt-per-mm-deg mark)) "°. "
                          "Un QUARTO punto, lontano dagli altri, è quello che rende "
                          "la planarità un controllo vero.")
                     (str "Planarità " (src/fmt-number (:flatness-mm mark)) "mm"
                          (when (> (:flatness-mm mark) 1.0)
                            (str " — ATTENZIONE, la zona non è così piana. Scarti per punto (mm): "
                                 (mapv #(src/fmt-number %) (:per-point mark))))))
                   " Naviga le foto con [ e ] e guarda se il dischetto resta incollato "
                   "alla superficie: se sì Invio per accettarlo, se no Backspace per rifarlo."))
        (redraw-plane!)))))

(defn- accept-candidate!
  "Second Enter: write the previewed plane into the source. Editing an existing
   mark REPLACES the `(edit-plane-mark …)` form with the updated literal — the
   scaffolding falls away and the source is left holding plain data; creating
   one appends a new named entry to the :marks block. Either way the disc stays
   on screen (dimmer) so it can still be checked afterwards."
  []
  (let [{:keys [candidate candidate-radius edit]} (:plane @stage)
        mark (select-keys candidate [:position :heading :up])
        pts (fitted-points)
        done! (fn [label]
                (swap! stage update :plane
                       (fn [p] (-> p
                                   (update :committed conj {:mark candidate
                                                            :radius candidate-radius})
                                   (assoc :picks [{:obs {}}])
                                   (dissoc :candidate :candidate-radius :edit
                                           :origin-centroid :origin-override))))
                (say! label)
                (redraw-plane!))]
    (if edit
      ;; re-locate the form: the buffer may have moved since it was opened
      (if-let [[from to] (edit-mark-bounds (cm/get-value))]
        (do (modal/replace-source! from to (mark-form mark pts))
            (modal/run-definitions!)
            (done! (str "aggiornato :" (or (:name edit) "il mark")
                        " — " (count pts) " punti, planarità "
                        (src/fmt-number (:flatness-mm candidate)) "mm")))
        (say! "non trovo più (edit-plane-mark …) nel sorgente: è stata modificata?"))
      (if-let [nm (commit-plane-mark! mark pts)]
        (done! (str "creato :" nm ". Usalo così:  (turtle (:" nm " (:marks A)) (edit-path-2d))"))
        (redraw-plane!)))))

(def ^:private plane-nudge-step 0.25)

(defn- set-candidate-origin!
  "Move the candidate's origin AND re-baseline the along-normal offset.

   THE INVARIANT: :candidate-base-pos is where the candidate sits with zero
   offset. Anything that moves the origin must go through here, or the arrows
   silently stop working — which is exactly what happened when re-opening a mark
   with (edit-plane-mark …) set :candidate without a base: the offset counter
   went up, the HUD said so, and the plane did not move (Vincenzo 2026-08-01)."
  [pos]
  (swap! stage update :plane #(-> %
                                  (assoc-in [:candidate :position] pos)
                                  (assoc :candidate-base-pos pos :offset-mm 0.0))))

(defn- apply-offset!
  "Re-derive the candidate position from its BASE plus the accumulated offset
   along the normal — recomputed from the base every time, so repeated nudges
   cannot drift."
  []
  (let [{:keys [candidate candidate-base-pos offset-mm]} (:plane @stage)]
    (when (and candidate candidate-base-pos)
      (swap! stage assoc-in [:plane :candidate :position]
             (m/v+ candidate-base-pos (m/v* (:heading candidate) (or offset-mm 0.0)))))))

(defn- nudge-plane!
  "Slide the proposed plane along its own NORMAL.

   The one constrained move worth having (Vincenzo 2026-08-01: 'su una certa
   foto mi posso fidare di quella dimensione e non di altre'). A photo does not
   observe every direction equally — depth along its own line of sight is the
   one it pins worst — so moving in ONE declared direction lets the correction
   be judged on the view that actually sees it, instead of dragging in three
   dimensions at once and trusting all of them equally. The normal is that
   direction for a plane: what an orientation-correct but mis-placed fit gets
   wrong, and what the points cannot pin better than their own triangulation."
  [delta]
  (when (:candidate (:plane @stage))
    (if-not (:candidate-base-pos (:plane @stage))
      ;; Without a baseline apply-offset! can do nothing, and the failure is
      ;; SILENT in the worst way: the counter goes up, the HUD reports a
      ;; displacement, and the plane does not move. Say it instead.
      (say! (str "non riesco a spostare il piano: manca il riferimento di partenza. "
                 "Rifai il fit (Invio) e riprova — e segnalalo, è un difetto."))
      (do (swap! stage update-in [:plane :offset-mm] (fnil + 0.0) delta)
          (apply-offset!)
          (say! (str "piano spostato lungo la normale: "
                     (src/fmt-number (get-in @stage [:plane :offset-mm])) "mm dal fit"))
          (redraw-plane!)))))

(defn- reset-offset! []
  (swap! stage assoc-in [:plane :offset-mm] 0.0)
  (apply-offset!)
  (say! "piano rimesso dove l'hanno messo i punti")
  (redraw-plane!))

(defn- recentre-origin!
  "Put the origin back where the fit itself put it — the centroid of the clicked
   points — without refitting anything. The plane is untouched: only the point
   coordinates are measured from moves."
  []
  (when-let [c (:origin-centroid (:plane @stage))]
    (set-candidate-origin! c)
    (swap! stage update :plane dissoc :origin-override)
    (say! "origine rimessa al centro dei punti")
    (redraw-plane!)))

(defn- discard-candidate! []
  (swap! stage update :plane dissoc
         :candidate :candidate-radius :origin-centroid :origin-override)
  (say! "piano proposto scartato — i punti restano, correggili e ripremi Invio")
  (redraw-plane!))

(defn- plane-key!
  "Plane-mode keys. Returns true when the key was consumed. Enter is two-stage —
   fit, then accept — so nothing reaches the source before the user has looked at
   the disc across the photos."
  [k]
  (let [candidate? (some? (:candidate (:plane @stage)))]
    (case k
      "n" (do (add-another-point!) true)
      "Enter" (do (if candidate? (accept-candidate!) (fit-candidate!)) true)
      "Backspace" (do (if candidate? (discard-candidate!) (undo-plane-click!)) true)
      "ArrowUp" (do (nudge-plane! plane-nudge-step) true)
      "ArrowDown" (do (nudge-plane! (- plane-nudge-step)) true)
      "Escape" (do (stop-plane!) (say! "modo piano chiuso") true)
      false)))

(defn- pick-of-point
  "A triangulated point loaded back from a mark's :from, dressed as a pick so it
   feeds fitted-points like a freshly clicked one. It carries no observations —
   :from keeps the POINTS, not the per-photo clicks — which is why a loaded
   point can be dropped or joined by new ones but not itself re-aimed."
  [p]
  {:obs {} :loaded? true :fit {:point (vec p) :plausible? true :photos []}})

(defn- open-mark-edit!
  "Open the plane editor on the `(edit-plane-mark …)` found in the source. With a
   literal it starts from that mark — its plane shown as the candidate disc, its
   :from points restored so they can be added to or thinned — so the very first
   click already re-places the origin. Empty, it is just the creation flow with a
   destination already chosen in the source."
  [{:keys [mark]}]
  (let [text (cm/get-value)
        from (first (edit-mark-bounds text))
        nm (when from (mark-name-before text from))
        pts (mapv vec (:from mark))
        edit {:name nm}]
    (if-not from
      (say! "(edit-plane-mark …) valutata ma non trovata nel sorgente")
      (do
        (swap! stage assoc :plane
               (merge {:picks (conj (mapv pick-of-point pts) {:obs {}})
                       :committed []
                       :edit edit}
                      (when (and mark (:position mark) (:heading mark))
                        {:candidate (select-keys mark [:position :heading :up])
                         :candidate-radius (disc-radius pts)
                         :origin-centroid (:position mark)
                         ;; the invariant set-candidate-origin! maintains — without
                         ;; it the arrows move nothing on a re-opened mark
                         :candidate-base-pos (:position mark)
                         :offset-mm 0.0
                         ;; an existing mark's origin is treated as PLACED: it is
                         ;; whatever the user settled on last time, and a refit
                         ;; must carry it over rather than recentre it.
                         :origin-override (:position mark)})))
        (when-not (in-pose?)
          (let [order (nav-order) cur (:current-idx @stage)]
            (when (seq order)
              (go-in-pose! (if (get-in @stage [:camera-poses cur]) cur (first order)) true))))
        (say! (str "edit di :" (or nm "?") " — "
                   (if (seq pts)
                     (str (count pts) " punti ripresi dal sorgente. Un click sposta l'origine; "
                          "'n' per aggiungere un punto al piano.")
                     "nessun punto memorizzato: clicca i punti del piano come per un mark nuovo.")
                   " Invio accetta, Esc annulla e lascia il mark com'era."))
        (redraw-plane!)))))

(defn- open-pending-edit!
  "Consume the `(edit-plane-mark …)` requests noted during the eval: the first
   opens the editor, the rest wait (they are still in the source, so confirming
   this one and re-running picks up the next)."
  []
  (when-let [reqs (seq (:pending-edits @stage))]
    (swap! stage dissoc :pending-edits :edit-after-load?)
    (when (> (count reqs) 1)
      (say! (str "ci sono " (count reqs) " forme (edit-plane-mark …): apro la prima, "
                 "le altre restano in attesa")))
    (if (seq (:camera-poses @stage))
      (open-mark-edit! (first reqs))
      (say! "nessuna camera registrata: non c'è niente con cui misurare un piano"))))

(defn- toggle-source-marks! []
  (swap! stage update :show-source-marks? #(not (if (nil? %) true %)))
  (redraw-plane!))

(defn- toggle-plane-mode! []
  (if (plane-mode?)
    (stop-plane!)
    (if (seq (:camera-poses @stage))
      (do (when-not (in-pose?)
            (let [order (nav-order)
                  cur (:current-idx @stage)]
              (when (seq order)
                (go-in-pose! (if (get-in @stage [:camera-poses cur]) cur (first order)) true))))
          (start-plane!))
      (say! "nessuna camera registrata: non c'è niente da triangolare"))))

;; ------------------------------------------------------------
;; Viewport toolbar (shown only while the stage is active WITH registered cameras):
;; a Photo-lock toggle (in-pose ↔ free, label 'foto i/N · θ°') + Prev/Next photo
;; (θ order, ~200ms flight) + the plane-mark toggle. Mounted in #viewport-toolbar;
;; torn down on deactivate!.
;; ------------------------------------------------------------

(defn- theta-label
  "'foto i/N · θ°' for the current photo — i = its 1-based rank in θ order, plus
   a ⚠ when this photo registered badly. The warning belongs HERE, not only in
   the plane gesture: a photo whose camera pose is off reprojects everything
   wrong — proxy, ricalchi, marks — so it is worth knowing the moment you land
   on it (Vincenzo 2026-07-31)."
  []
  (let [cur (:current-idx @stage)
        n (count (nav-order))
        theta (:theta (nth (:photos @stage) cur nil))]
    (str "foto " (nav-rank cur) "/" n
         (when theta (str " · " (js/Math.round theta) "°"))
         (when (poorly-registered? cur) " ⚠"))))

(defn- update-toolbar!
  "Refresh the lock toggle's label + selected state. Called on every pose change so
   a frustum click (not just a toolbar click) reflects in the toggle."
  []
  (when-let [^js lock (.getElementById js/document "eaq-stage-lock")]
    (if (in-pose?)
      (do (.add (.-classList lock) "active")
          (set! (.-textContent lock) (theta-label)))
      (do (.remove (.-classList lock) "active")
          (set! (.-textContent lock) "Foto"))))
  (when-let [^js mk (.getElementById js/document "eaq-stage-marks")]
    (if (:show-source-marks? @stage true)
      (.add (.-classList mk) "active")
      (.remove (.-classList mk) "active")))
  (when-let [^js pl (.getElementById js/document "eaq-stage-plane")]
    (if (plane-mode?)
      (do (.add (.-classList pl) "active")
          (set! (.-textContent pl) (plane-status)))
      (do (.remove (.-classList pl) "active")
          (set! (.-textContent pl) "Piano"))))
  (refresh-plane-hud!))

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
        (.appendChild wrap (make-tool-btn "eaq-stage-marks" "Mark"
                                          (str "Mostra i mark COME SONO SCRITTI nel sorgente: "
                                               "piano, origine e punti da cui è nato")
                                          toggle-source-marks!))
        (.appendChild wrap (make-tool-btn "eaq-stage-plane" "Piano"
                                          (str "Crea un piano di lavoro sull'oggetto: clicca lo stesso "
                                               "punto su 2+ foto, 'n' per il punto dopo, 3 punti, Invio")
                                          toggle-plane-mode!))
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
    (swap! stage dissoc :plane)
    (refresh-plane-hud!)
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
                   (swap! stage assoc :photos photos :camera-poses cams
                          :registration (:registration st))
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
  [{:keys [proxy pose dir marks]}]
  (swap! stage (fn [s]
                 (assoc (or s {})
                        :pending {:dir dir
                                  :emit-pose (or pose (:creation-pose proxy))
                                  :dims (bridge/dims-from-mesh proxy (:creation-pose proxy))
                                  :marks marks
                                  ;; a registration PLATE (it carries named marks)
                                  ;; — its axis is a usable 'the object rests on
                                  ;; this' normal, which unlocks the one-click
                                  ;; plane-mark case. A box's heading is not.
                                  :plate? (boolean (seq (:anchors proxy)))}))))

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
                              :plate? (:plate? pending)
                              :source-marks (:marks pending)
                              :camera-poses {}
                              :photos []
                              :focal-mm default-focal-mm
                              :current-idx 0
                              :in-pose? false
                              :loaded? false
                              ;; a different acquire = different object: its
                              ;; half-finished plane picks mean nothing here
                              :plane nil
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
                               (show-frustums!)
                               ;; an edit requested before the cameras existed
                               (when (:edit-after-load? @stage) (open-pending-edit!))))))

      ;; same dir re-evaluated → keep camera/pose, just refresh geometry (dims/pose
      ;; may have changed) and re-show the layers the Run's clear-geometry wiped
      pending
      (do (swap! stage merge {:emit-pose (:emit-pose pending)
                              :dims (:dims pending)
                              :plate? (:plate? pending)
                              :source-marks (:marks pending)
                              :pending nil})
          (when (and (loaded?) (seq (:camera-poses @stage))) (setup-toolbar!))
          (when (:in-pose? @stage) (backdrop/set-visible! true))
          ;; re-show the frustums (in-pose or free-orbit set per state) on the
          ;; dedicated layer — safe with an open ricalco.
          (when (loaded?) (show-frustums!)))

      ;; no (acquire …) this eval → tear down
      @stage
      (deactivate!))
    ;; An (edit-plane-mark …) evaluated inside the acquire's :marks opens the
    ;; plane editor on it — but only once the stage has its cameras, so on a
    ;; FRESH acquire (which loads them asynchronously) the request waits for the
    ;; load to resolve rather than being dropped.
    (when (seq (:pending-edits @stage))
      (if (loaded?)
        (open-pending-edit!)
        (swap! stage assoc :edit-after-load? true)))))
