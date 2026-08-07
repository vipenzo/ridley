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
            [ridley.photogrammetry.edge :as pedge]
            [ridley.photogrammetry.circle :as pcircle]
            [ridley.photogrammetry.curve :as pcurve]
            [ridley.photogrammetry.edge-snap :as edge-snap]
            [ridley.photogrammetry.fuse :as fuse]
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

(defn- photo-dir
  "Which session's folder photo `idx` lives in. The film is a FLAT list across
   every fused session, so the folder travels with the photo rather than being
   the stage's one directory (dev-docs/brief-session-fusion.md, fetta 2)."
  [idx]
  (or (:dir (nth (:photos @stage) idx nil)) (:dir @stage)))

(defn- photo-session
  "The label of the session photo `idx` came from, or nil for a lone acquire."
  [idx]
  (:session (nth (:photos @stage) idx nil)))
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

(def ^:private source-edge-color 0xff8811)   ; arancio scuro: gli spigoli nel sorgente

(defn- ring-segments
  "A closed 3D polyline as :lines data — how a measured circle gets drawn with
   the same primitive a measured edge uses."
  [pts color]
  (mapv (fn [i] {:from (nth pts i)
                 :to (nth pts (mod (inc i) (count pts)))
                 :color color})
        (range (count pts))))

(defn- source-edge-items
  "Every measured edge of the evaluated acquire, drawn AS IT IS WRITTEN — from
   its own :a to its own :b, not from the gesture's state. Same principle as
   source-mark-items: what you see is what the source says, so a divergence
   between what was written and what is displayed shows up at once instead of
   three steps later as displaced geometry. It is also the after-the-fact check —
   navigate the photos and the segment must stay on the object's edge."
  []
  (when (:show-source-marks? @stage true)
    (into []
          (keep (fn [[_ e]]
                  (cond
                    (and (map? e) (:a e) (:b e))
                    {:type :lines
                     :data [{:from (vec (:a e)) :to (vec (:b e))
                             :color source-edge-color}]}
                    ;; a measured CIRCLE draws as its own ring, rebuilt from the
                    ;; three numbers the source holds — centre, axis, radius
                    (and (map? e) (:radius e) (:position e) (:heading e))
                    {:type :lines
                     :data (ring-segments (pcircle/ring-mesh {:center (vec (:position e))
                                                              :normal (vec (:heading e))
                                                              :radius (:radius e)})
                                          source-edge-color)}
                    :else nil)))
          (:source-edges @stage))))

(declare plane-preview-items edge-preview-items)

(declare edge-labels edge-mode?)

(defn- show-frustums!
  "Repaint the stage's OWN overlay layer: the ghost frustums (free orbit) plus
   whatever the plane-mark and edge gestures are showing (in pose). One call, one
   layer — they share it because all of them are the stage's, and because the
   layer is deliberately NOT the preview layer an open edit-path-2d ricalco owns.

   The billboard LABELS go up in the same breath. They are what ties the bench's
   list to the photograph — «i segmenti listati lì come faccio a vedere dove sono
   nelle foto?» (Vincenzo, 2026-08-07) — and a list of numbered things whose
   numbers appear nowhere on the thing is not a list, it is a riddle."
  []
  (viewport/show-frustum-layer! (-> (vec (frustum-preview-items))
                                    (into (source-mark-items))
                                    (into (source-edge-items))
                                    (into (plane-preview-items))
                                    (into (edge-preview-items))))
  ;; ONLY while the gesture is on. set-labels! is global and replaces whatever is
  ;; there, so calling it on every repaint would wipe the labels an open
  ;; edit-path-2d ricalco has put up — and the stage repaints on every photo
  ;; change, which is exactly when a ricalco is being navigated.
  (when (edge-mode?) (viewport/set-labels! (vec (edge-labels)))))

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
    (backdrop/set-photo! (str (photo-dir idx) "/" file) (:focal-mm @stage)
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
  "Registered photo indices (those with a camera pose), sorted by SESSION first
   and turntable angle θ within it. With sessions fused the film is one strip
   that walks a pose at a time, which is what makes `[`/`]` read as 'go round
   this pose, then round the next'."
  []
  (let [{:keys [photos camera-poses]} @stage]
    (vec (sort-by (fn [idx] [(or (:session-idx (nth photos idx nil)) 0)
                             (or (:theta (nth photos idx nil)) 0)])
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

(declare plane-mode? plane-click! plane-key! toggle-plane-mode! toggle-source-marks!
         edge-click! edge-key! toggle-edge-mode! edge-hud-content edge-status
         paint-declare! world-solver-pose stage-intrinsics say!)

(defn- editable? [^js el]
  (boolean (and el (or (#{"INPUT" "TEXTAREA"} (.-tagName el))
                       (.-isContentEditable el)))))

(defn- on-pointerdown [^js e]
  (when (zero? (.-button e))
    (cond
      (free-orbit?)
      (swap! stage assoc :press {:x (.-clientX e) :y (.-clientY e)
                                 :idx (viewport/raycast-frustum-pick e)})
      ;; in pose, a clean click marks a plane point or draws an edge stroke — but
      ;; never while a modal (an edit-path-2d ricalco) is up: there the click is
      ;; the ricalco's. Only one gesture is ever on (each toggle stops the other).
      (and (or (plane-mode?) (edge-mode?)) (:in-pose? @stage) (not (modal/active?)))
      (do (swap! stage assoc :press {:x (.-clientX e) :y (.-clientY e) :gesture? true})
          ;; …and in edge mode a DRAG paints the zone to look in, so start
          ;; collecting the trail now: whether it was a click or a stroke is only
          ;; known when the pointer comes up.
          (when (edge-mode?)
            (swap! stage (fn [st] (-> st
                                      (assoc :paint {:idx (:current-idx @stage) :trail []})
                                      (update :edge dissoc :outcome)))))))))

(defn- on-paint-move
  "While the left button is down in edge mode, every few pixels of travel become
   a station of the painted band. Sampled by DISTANCE rather than by event, so a
   slow hand and a fast one paint the same stroke."
  [^js e]
  (when-let [{:keys [trail]} (:paint @stage)]
    (when-let [px (backdrop/pixel-under-pointer e (viewport/get-camera) (viewport/get-canvas))]
      (let [last-px (:px (peek trail))]
        (when (or (nil? last-px)
                  (> (js/Math.hypot (- (first px) (first last-px))
                                    (- (second px) (second last-px)))
                     4.0))
          (swap! stage update-in [:paint :trail] conj
                 {:px px :screen [(.-clientX e) (.-clientY e)]}))))))

(defn- on-pointerup [^js e]
  (when (zero? (.-button e))
    (let [{:keys [x y idx gesture?]} (:press @stage)
          paint (:paint @stage)
          dragged? (and (some? x)
                        (>= (js/Math.hypot (- (.-clientX e) x) (- (.-clientY e) y))
                            click-slop-px))]
      (swap! stage dissoc :press :paint)
      ;; a DRAG in edge mode is the marker, not a miss-click
      (when (and dragged? gesture? (edge-mode?) (>= (count (:trail paint)) 2))
        (let [i (:idx paint)]
          (if-let [pose (world-solver-pose i)]
            (if-let [k (stage-intrinsics)]
              (paint-declare! i (:trail paint) pose k)
              (say! "nessuna foto caricata: non so a che risoluzione riferire la pennellata"))
            (say! (str "la foto " (nav-rank i) " non ha una posa registrata")))))
      (when (and (some? x)
                 (< (js/Math.hypot (- (.-clientX e) x) (- (.-clientY e) y)) click-slop-px))
        (if gesture?
          (cond (plane-mode?) (plane-click! e)
                (edge-mode?) (edge-click! e))
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
        (and (plane-mode?) (not (modal/active?)) (plane-key! k (.-shiftKey e)))
        (.preventDefault e)
        ;; the edge gesture owns the same keys while IT is on, for the same reason
        (and (edge-mode?) (not (modal/active?)) (edge-key! k))
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
      (.addEventListener canvas "pointermove" on-paint-move true)
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
    (.removeEventListener canvas "pointermove" on-paint-move true)
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

(defn- redraw-overlay!
  "Repaint everything the stage draws and refresh its toolbar/HUD. Shared by the
   plane gesture and the edge gesture — one call, because they share one layer."
  []
  (show-frustums!)
  ;; update-toolbar! refreshes the HUD too — it is called on EVERY pose change
  ;; (frustum click, Prev/Next, photo lock), so hanging the HUD off it is what
  ;; keeps "foto N — reg. …" honest as you navigate, instead of freezing on the
  ;; photo you happened to be on when the gesture started.
  (update-toolbar!))

;; ---- registration quality of a photo (read from acquire-state.json) ----

(def ^:private poor-registration-px
  "Above this per-photo PnP rms, what is drawn in the world reprojects visibly
   off on this photo, so points clicked on it are worth less. Measured on
   param-plate-paper: ~4-5 px on the turntable ring, 1.4 px looking straight
   down, 9-11 px on the two grazing shots. The gap is wide and 8 px sits in it."
  8.0)

(def ^:private grazing-deg
  "Below this elevation above the plate's marked face a photo counts as GRAZING.
   Measured across a whole session, the reprojection rms tracks elevation and
   nothing else: 84° → 1.4 px, ~39° (the ring) → 4.2-5.2 px, 19° → 9.4 px,
   15° → 10.7 px. That is the signature of a systematic camera-model error (an
   imperfect focal, unmodelled radial distortion), which a fronto-parallel plane
   absorbs into its distance and an oblique one cannot — not of sloppy clicking.
   Worth separating, because the advice is the opposite: on a grazing photo
   re-clicking does NOT help."
  25.0)

(defn- camera-elevation-deg
  "Degrees photo `idx`'s camera sits ABOVE the plane of the marked face: 90° is
   straight down on the plate, 0° is in its plane, negative is behind it.
   Plate-only — a box has no single marked plane. nil when unknown."
  [idx]
  (when (:plate? @stage)
    (let [{:keys [position heading]} (:emit-pose @stage)
          cam (get-in @stage [:camera-poses idx :position])]
      (when (and cam heading position)
        (let [d (m/v- cam position)
              len (m/magnitude d)]
          (when (pos? len)
            (-> (/ (m/dot (m/normalize heading) d) len)
                (max -1.0) (min 1.0) Math/asin
                (* (/ 180.0 Math/PI)) Math/round)))))))

(defn- registration-of [idx] (get-in @stage [:registration idx]))

(defn- behind-plate?
  "True when this photo's camera sits BEHIND the plate's marked face.

   Physically impossible: the discs were photographed, so the camera was on
   their side. A pose that says otherwise is the mirror twin of the planar PnP —
   the 2-fold ambiguity a plane-based homography always has, normally arbitrated
   by the reprojection error and lost when the shot is grazing and a few marks
   are missing. On such a photo the proxy renders SEEN FROM BEHIND: its crown
   dots appear on the far face while the photo shows them face-on, which reads
   as 'the proxy has been turned 180°' (Vincenzo 2026-08-01).

   Measured on param-plate-paper: photo 9 (rms 12.26, 10 marks of 12) has its
   camera at cos −0.26 from the marked normal, while every other photo sits
   between +0.33 and +0.99."
  [idx]
  (when (:plate? @stage)
    (let [{:keys [position heading]} (:emit-pose @stage)
          cam (get-in @stage [:camera-poses idx :position])]
      (when (and cam heading position)
        (neg? (m/dot (m/normalize heading) (m/v- cam position)))))))

(defn- registration-trouble
  "What is wrong with photo `idx`'s registration, or nil when nothing is:

     :flipped  the camera is behind the marked face — impossible, re-register;
     :grazing  correct but looser, because the shot is nearly in the plate's
               plane (see grazing-deg);
     :loose    a high rms with no such excuse, so the clicks are the suspect.

   Kept apart because the advice is opposite: :loose says re-click, :grazing
   says re-clicking will not help and the photo is fine to work from, just not
   to measure from."
  [idx]
  (if (behind-plate? idx)
    ;; not gated on a registration record: an impossible pose is impossible
    ;; whether or not this photo ever recorded an rms.
    :flipped
    (when-let [{:keys [rms-px]} (registration-of idx)]
      (cond
        (<= rms-px poor-registration-px) nil
        ;; an unknown elevation earns no excuse — fall through to :loose
        (when-let [e (camera-elevation-deg idx)] (< e grazing-deg)) :grazing
        :else :loose))))

(defn- poorly-registered? [idx]
  (some? (registration-trouble idx)))

(defn- registration-label
  "'reg. 11.3px · 10/12' for the current photo, or nil when unknown."
  [idx]
  (let [{:keys [rms-px matched]} (registration-of idx)]
    (cond
      (behind-plate? idx)
      (str "REGISTRAZIONE RIBALTATA — la camera è dietro il piatto"
           (when rms-px (str " (reg. " (src/fmt-number rms-px) "px)")))
      rms-px
      (str "reg. " (src/fmt-number rms-px) "px"
           (when matched (str " · " matched " marker")))
      :else nil)))

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
          ;; The hand offset is NOT reported here any more: it is drawn in the
          ;; nudge pad above, always, at a fixed width. A line that appears the
          ;; moment you press an arrow is a line that moves the arrow you were
          ;; about to press again.
          (add! "eaq-hud-hint"
                "Frecce = sposta l'origine NEL piano · Shift+↑↓ = sposta il piano in profondità")
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
        (let [trouble (registration-trouble here)]
          (when-let [r (registration-label here)]
            (add! (case trouble (:flipped :loose) "eaq-hud-bad" :grazing "eaq-hud-warn" nil)
                  (str "Foto " (nav-rank here) " — " r
                       (case trouble
                         :flipped "  ⚠ mal registrata"
                         :loose "  ⚠ mal registrata"
                         :grazing "  ⚠ meno precisa"
                         nil))))
          (case trouble
            :flipped
            (add! "eaq-hud-bad"
                  (str "Il piatto è registrato al contrario su questa foto: la vedi "
                       "da dietro. Non usarla — va ri-registrata."))
            ;; A grazing photo is registered CORRECTLY; it is just looser, and
            ;; the looseness comes from the viewing angle, not from the clicks.
            ;; Saying 'usane un'altra' here sent Vincenzo hunting for a mistake
            ;; that isn't there (2026-08-02) — so name the cause and say plainly
            ;; that re-clicking won't move it.
            :grazing
            (add! "eaq-hud-warn"
                  (str "Foto radente"
                       (when-let [e (camera-elevation-deg here)]
                         (str " (" e "° sul piano del piatto)"))
                       ": i mark si riproiettano più larghi che sulle foto alte. "
                       "È l'angolo, non i tuoi click — ricliccare non la migliora. "
                       "La posa è coerente: usala pure per orientarti, ma per "
                       "prendere punti da misurare preferisci una foto più alta."))
            :loose
            (add! "eaq-hud-bad"
                  (str "Su questa foto i punti si riproiettano storti, e non è "
                       "l'angolo: controlla i mark cliccati, o usane un'altra."))
            nil))
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
                                           ;; orange ⚠ for a grazing shot, red for one
                                           ;; whose pose is actually suspect — the same
                                           ;; distinction the paragraph above draws
                                           (el "span" (case (registration-trouble idx)
                                                        (:flipped :loose) "eaq-hud-bad"
                                                        :grazing "eaq-hud-warn"
                                                        nil)
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

(declare set-candidate-origin! schedule-live-reeval! next-plane-point! add-another-point! fit-candidate! accept-candidate!
         discard-candidate! recentre-origin! nudge-plane! reset-offset!
         undo-plane-click! stop-plane!)

(def ^:private plane-nudge-steps
  "The ladder the 'passo' button cycles through, in mm. 0.25 was the fixed step
   before it was adjustable, and stays the default: the gesture opens behaving
   exactly as it did. The fine end matters for the same reason the whole origin
   gesture exists — an origin placed on a real feature is reproducible, and
   'reproducible' at photo scale can mean well under a tenth of a millimetre
   (Vincenzo 2026-08-03: 'in alcuni casi mi servirebbe più fine')."
  [1.0 0.5 0.25 0.1 0.05])

(def ^:private default-nudge-step 0.25)

(defn- nudge-step [] (get-in @stage [:plane :nudge-step] default-nudge-step))

(defn- cycle-nudge-step!
  "Next step on the ladder, wrapping. A cycling BUTTON rather than a number
   field on purpose: the HUD is rebuilt wholesale on every state change, and a
   focused input would lose its caret (or force the rebuild to tiptoe around
   it) — while a button carries its current value as its own label, which is
   also the only place the step is documented."
  []
  (let [cur (nudge-step)
        i (or (first (keep-indexed (fn [i v] (when (= v cur) i)) plane-nudge-steps)) 2)]
    (swap! stage assoc-in [:plane :nudge-step]
           (nth plane-nudge-steps (mod (inc i) (count plane-nudge-steps))))
    (redraw-overlay!)))

(defn- hud-nudge-pad
  "The origin controls, in their OWN block above the detail paragraph.

   Position stability is the whole point (Vincenzo 2026-08-03: 'dopo il primo
   click l'icona è da un'altra parte'). The arrows are the one control here that
   gets clicked repeatedly, and they used to sit in the wrapping actions row
   BELOW a paragraph that changes height at every press — the hand-offset line
   appears, a hint swaps — so the second click of a pair landed where the first
   button no longer was. Here everything above this block (title + three steps)
   is constant-height, and the offset readout is always drawn, at a fixed width,
   even when it is zero: nothing in this block can move it."
  []
  (let [{:keys [dr du dn] :or {dr 0.0 du 0.0 dn 0.0}} (:offset (:plane @stage))
        step (nudge-step)
        moved? (not (= 0.0 dr du dn))
        pad (el "div" "eaq-hud-pad")
        row (el "div" "eaq-hud-pad-row")
        ;; toFixed, not fmt-number: a readout that must not change WIDTH cannot
        ;; use the formatter that trims trailing zeros.
        num (fn [glyph v]
              (el "span" "eaq-hud-num" :text (str glyph " " (.toFixed v 2))))]
    (doseq [[label title axis delta]
            [["◀" "Sposta l'origine a sinistra nel piano (freccia sinistra)" :dr (- step)]
             ["▶" "Sposta l'origine a destra nel piano (freccia destra)" :dr step]
             ["▲" "Sposta l'origine in su nel piano (freccia su)" :du step]
             ["▼" "Sposta l'origine in giù nel piano (freccia giù)" :du (- step)]]]
      (.appendChild row (hud-button label title true false #(nudge-plane! axis delta))))
    (let [^js b (hud-button (str "passo " (src/fmt-number step) " mm")
                            (str "Di quanto si sposta a ogni freccia — click per il passo "
                                 "successivo (" (str/join " · " (map src/fmt-number plane-nudge-steps))
                                 " mm). Vale anche per le frecce della tastiera.")
                            true false cycle-nudge-step!)]
      (set! (.-className b) (str (.-className b) " eaq-hud-step-btn"))
      (.appendChild row b))
    (.appendChild pad row)
    (.appendChild pad (el "div" (str "eaq-hud-offset" (when moved? " moved"))
                          :children [(num "→" dr) (num "↑" du) (num "⊥" dn)
                                     (el "span" nil :text "mm")]))
    pad))

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
                     ;; the arrows are NOT here: they live in hud-nudge-pad, above
                     ;; the detail paragraph, where nothing can slide them around
                     ;; between two clicks.
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
    ;; ORDER MATTERS: the nudge pad goes ABOVE the detail paragraph. Everything
    ;; before it (title + the three steps) has constant height, so the arrows sit
    ;; at the same pixel for the whole life of the candidate — which is what lets
    ;; you click one of them twice without looking.
    (when cand (.appendChild frag (hud-nudge-pad)))
    (.appendChild frag (hud-detail))
    (.appendChild frag (hud-actions))
    frag))

(defn- refresh-hud!
  "Rebuild the HUD of whichever gesture is on (or remove it when none is).
   Rebuilt wholesale rather than patched: it is a dozen nodes, and a panel that
   is a pure function of the state can never drift out of sync with it. One panel
   serves both gestures because only one of them is ever on."
  []
  (if-let [content (cond (plane-mode?) (hud-content)
                         (edge-mode?) (edge-hud-content)
                         :else nil)]
    (when-let [^js host (.getElementById js/document "viewport-panel")]
      (let [^js panel (or (hud-el)
                          (let [^js p (el "div" nil)]
                            (set! (.-id p) "eaq-plane-hud")
                            (.appendChild host p)
                            p))]
        (set! (.-innerHTML panel) "")
        (.appendChild panel content)))
    (when-let [^js p (hud-el)] (.remove p))))

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
  (redraw-overlay!))

(declare unwrap-edit-mark!)

(defn- stop-plane!
  "Leave plane mode. When an `(edit-plane-mark …)` opened it, the wrapper MUST
   come out of the source on the way out — otherwise the next eval re-opens the
   editor and there is no way to stop, since the intent to edit lives in the
   source rather than in a mode."
  []
  (let [edit (:edit (:plane @stage))]
    (swap! stage dissoc :plane)
    (redraw-overlay!)                    ; :plane gone → the HUD removes itself
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
          (redraw-overlay!)
          (schedule-live-reeval!)))))

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
  (case (registration-trouble idx)
    :grazing
    (say! (str "nota: la foto " (nav-rank idx) " è radente ("
               (registration-label idx) "), quindi meno precisa delle altre. "
               "Il click è preso: se puoi, dai a questo punto anche una foto più alta."))
    (:flipped :loose)
    (say! (str "attenzione: la foto " (nav-rank idx) " è registrata male ("
               (registration-label idx) "). Il click è preso lo stesso, ma "
               "su questa foto TUTTO si riproietta storto: se puoi, usane un'altra."))
    nil)
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
  (redraw-overlay!))

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
          (redraw-overlay!)))))

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
        (redraw-overlay!))
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
  (redraw-overlay!))

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
        ;; Which direction of the OBJECT should read as 'up' on the mark. For a
        ;; box it is its :up. For a PLATE it is its :heading — the plate's
        ;; heading IS the turntable axis (plate.cljs builds the cylinder along
        ;; +Z with an identity creation-pose), while its :up is just some
        ;; direction across the disc. Trying :up first on a plate gave every
        ;; vertical face a mark turned 90° from the axis (Vincenzo 2026-08-01:
        ;; 'l'up deve essere l'asse del turntable'), because on such a face the
        ;; across-the-disc hint is perfectly usable and won.
        hints (if (:plate? @stage)
                [(:heading emit) (:up emit)]
                [(:up emit) (:heading emit)])
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
               :offset nil)
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
        (redraw-overlay!)))))

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
                (redraw-overlay!))]
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
        (redraw-overlay!)))))

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
                                  (assoc :candidate-base-pos pos :offset nil))))

(defn- mark-axes
  "The mark's own three directions: [right up normal]. `right` is up×heading,
   the same convention geometry created at the mark is placed with
   (primitives/apply-transform), so a nudge 'to the right' moves the mark the
   way the thing built on it will move."
  [{:keys [heading up]}]
  (let [h (m/normalize heading)
        u (m/normalize up)]
    [(m/normalize (m/cross u h)) u h]))

(defn- apply-offset!
  "Re-derive the candidate position from its BASE plus the accumulated offset,
   component by component in the mark's OWN frame. Recomputed from the base
   every time, so repeated nudges cannot drift."
  []
  (let [{:keys [candidate candidate-base-pos offset]} (:plane @stage)]
    (when (and candidate candidate-base-pos)
      (let [[r u n] (mark-axes candidate)
            {:keys [dr du dn] :or {dr 0.0 du 0.0 dn 0.0}} offset
            p (-> candidate-base-pos
                  (m/v+ (m/v* r dr))
                  (m/v+ (m/v* u du))
                  (m/v+ (m/v* n dn)))]
        (swap! stage update :plane
               (fn [pl] (cond-> (assoc-in pl [:candidate :position] p)
                          ;; a hand-positioned origin is the user's answer to
                          ;; 'where is this measured from', so a later refit must
                          ;; carry it rather than fall back to the centroid
                          (or (:origin-override pl) (not= p candidate-base-pos))
                          (assoc :origin-override p))))))))

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
  [axis delta]
  (when (:candidate (:plane @stage))
    (if-not (:candidate-base-pos (:plane @stage))
      ;; Without a baseline apply-offset! can do nothing, and the failure is
      ;; SILENT in the worst way: the counter goes up, the HUD reports a
      ;; displacement, and the plane does not move. Say it instead.
      (say! (str "non riesco a spostare il piano: manca il riferimento di partenza. "
                 "Rifai il fit (Invio) e riprova — e segnalalo, è un difetto."))
      (do (swap! stage update-in [:plane :offset axis] (fnil + 0.0) delta)
          (apply-offset!)
          (let [{:keys [dr du dn] :or {dr 0.0 du 0.0 dn 0.0}} (:offset (:plane @stage))]
            (say! (str (if (= axis :dn) "piano spostato in profondità" "origine spostata nel piano")
                       ": destra " (src/fmt-number dr)
                       ", su " (src/fmt-number du)
                       ", normale " (src/fmt-number dn) " mm dal fit")))
          (redraw-overlay!)
          (schedule-live-reeval!)))))

(def ^:private live-reeval-delay-ms
  "Trailing debounce on the live re-run. Key-repeat on the arrows would otherwise
   re-evaluate the whole script per step; this collapses a burst into one run at
   the end of it, which is what the eye needs anyway."
  90)

(defn- reeval-with-candidate!
  "Re-run the WHOLE script with the mark under edit replaced by its current
   value, so everything built on that mark moves as it is nudged.

   Requested by Vincenzo (2026-08-01: 'se ho usato quel mark per posizionare un
   oggetto sarebbe bello vederlo muoversi quando ne sposto la posizione — aiuta a
   trovare quella giusta'), and it is the right instrument for the job: what you
   are really aiming at is not the disc, it is the thing you built on it, and
   only that thing can tell you when you have got there.

   Reuses the modal family's live-preview machinery on the source with the
   (edit-plane-mark …) form swapped for the literal — no marker survives the
   substitution, hence arm-skip? false (see modal/reeval-script!)."
  []
  (let [{:keys [candidate edit]} (:plane @stage)]
    (when (and candidate edit)
      (when-let [[from to] (edit-mark-bounds (cm/get-value))]
        (modal/reeval-script!
         (fn [] (modal/splice-source (cm/get-value) from to
                                     (mark-form (select-keys candidate [:position :heading :up])
                                                (fitted-points))))
         "plane-mark live:" false)
        ;; the run rebuilt the scene, so the stage's own overlay must come back
        (show-frustums!)))))

(defn- schedule-live-reeval! []
  (when-let [t (:live-timer @stage)] (js/clearTimeout t))
  (swap! stage assoc :live-timer
         (js/setTimeout (fn []
                          (swap! stage dissoc :live-timer)
                          (reeval-with-candidate!))
                        live-reeval-delay-ms)))

(defn- reset-offset! []
  (swap! stage assoc-in [:plane :offset] nil)
  (apply-offset!)
  (say! "piano rimesso dove l'hanno messo i punti")
  (redraw-overlay!)
  (schedule-live-reeval!))

(defn- recentre-origin!
  "Put the origin back where the fit itself put it — the centroid of the clicked
   points — without refitting anything. The plane is untouched: only the point
   coordinates are measured from moves."
  []
  (when-let [c (:origin-centroid (:plane @stage))]
    (set-candidate-origin! c)
    (swap! stage update :plane dissoc :origin-override)
    (say! "origine rimessa al centro dei punti")
    (schedule-live-reeval!)
    (redraw-overlay!)))

(defn- discard-candidate! []
  (swap! stage update :plane dissoc
         :candidate :candidate-radius :origin-centroid :origin-override)
  (say! "piano proposto scartato — i punti restano, correggili e ripremi Invio")
  (redraw-overlay!))

(defn- plane-key!
  "Plane-mode keys. Returns true when the key was consumed. Enter is two-stage —
   fit, then accept — so nothing reaches the source before the user has looked at
   the disc across the photos."
  [k shift?]
  (let [candidate? (some? (:candidate (:plane @stage)))]
    (case k
      "n" (do (add-another-point!) true)
      "Enter" (do (if candidate? (accept-candidate!) (fit-candidate!)) true)
      "Backspace" (do (if candidate? (discard-candidate!) (undo-plane-click!)) true)
      ;; the arrows move IN the plane — up/down/left/right on the surface, which
      ;; is what a displaced mark actually needs (Vincenzo 2026-08-01). Depth,
      ;; the direction that moves the PLANE itself, goes on the same arrows with
      ;; Shift: separate gesture for a separate kind of change.
      ;; Same step as the buttons — one setting, wherever the nudge comes from.
      "ArrowUp" (do (nudge-plane! (if shift? :dn :du) (nudge-step)) true)
      "ArrowDown" (do (nudge-plane! (if shift? :dn :du) (- (nudge-step))) true)
      "ArrowRight" (do (nudge-plane! :dr (nudge-step)) true)
      "ArrowLeft" (do (nudge-plane! :dr (- (nudge-step))) true)
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
                         :offset nil
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
        (redraw-overlay!)))))

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
  (redraw-overlay!))

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
;; DECLARED EDGES (dev-docs/brief-observation-driven-acquire.md, gradino 3)
;;
;; The plane gesture asks for the hardest thing there is: find THE SAME PHYSICAL
;; POINT from another angle. On a black glossy moulding there is often no such
;; point to find, and that gesture is where the mis-clicks of the fusion gate came
;; from. This one asks for nothing of the kind. On each photo you draw the edge —
;; two clicks anywhere along it, in any order, snapped to the real gradient — and
;; the program intersects the planes those lines span with their camera centres.
;; Any two points along a line are as good as any other two, so there is no
;; correspondence to get wrong.
;;
;; And the click becomes PRODUCT: what comes out is a measured 3D segment of the
;; object, written into the source as a mark that runs along the edge. Registration
;; and modelling stop being separate phases.
;;
;; The check is the same one the plane disc uses and costs nothing: the measured
;; edge is drawn IN THE WORLD, so navigating with [ / ] reprojects it onto every
;; photo. It lies on the object's edge in all of them, or it is wrong.
;; ------------------------------------------------------------

(def ^:private edge-measured-color 0xff9933)  ; arancio: lo spigolo misurato in 3D
(def ^:private edge-drawn-color 0xffe08a)     ; il tratto dichiarato su questa foto
(def ^:private edge-pending-color 0xffcc33)   ; il primo click, in attesa del secondo
(def ^:private edge-committed-color 0xb36b1f) ; gli spigoli già scritti nel sorgente
(def ^:private bench-color 0x66ddaa)          ; i bordi tenuti sul banco
(def ^:private bench-selected-color 0x33ffcc) ; …e quelli scelti per il piano
(def ^:private bench-used-color 0x557766)     ; …e quelli già spesi in un piano
(def ^:private brush-color 0x8899ff)          ; la zona dipinta col pennarello
(def ^:private brush-ok-color 0x44dd66)       ; …quando la pennellata ha trovato
(def ^:private brush-fail-color 0xff4444)     ; …quando non ha trovato niente
(def ^:private fresh-plane-color 0x33ccff)    ; il piano appena scritto nel sorgente

(defn- edge-mode? [] (some? (:edge @stage)))
(defn- edge-obs [] (get-in @stage [:edge :obs] {}))
(defn- edge-fit [] (get-in @stage [:edge :fit]))

;; ---- the bench ----
;;
;; Every bordo that gets measured STAYS: drawn in the world, numbered, and there
;; to be picked. The plane is then built from the ones the user says lie on the
;; same face — which is the shape Vincenzo asked for after the first version
;; asked too much of a single gesture (2026-08-06: «se accumulassimo semplicemente
;; segmenti che restano visualizzati e l'utente può selezionare per dire questi
;; stanno sullo stesso piano?»).
;;
;; It is better than what it replaces for a reason that took a while to see: a
;; STRAIGHT edge is recovered by intersecting the planes its image lines span,
;; and that needs no pairing of points at all — so it carries none of the ghost
;; trouble a curve does. Two non-parallel edges of a face pin its plane exactly,
;; and straight edges are precisely the ones that get found reliably. The curve
;; is no longer the only road to a plane; it is one of the things one can put on
;; the bench.

(defn- edge-kind
  "What is being declared RIGHT NOW — decided by the image at the first click, and
   held until this bordo is kept or dropped. Not a session-wide mode any more:
   each bordo on the bench is its own thing."
  []
  (get-in @stage [:edge :kind] :retta))

(defn- curve-mode? [] (= :curva (edge-kind)))

(defn- bench
  "The bench IS the `:edges` block of the evaluated acquire — read from the
   SOURCE, not from a store of its own.

   Vincenzo asked for this in as many words (2026-08-07): «non sarebbe meglio
   accumulare le cose (piani, segmenti) nel sorgente, così li posso cancellare
   come testo invece che nella UI?». It is also what the rest of the channel
   already does, and it pays three ways at once: a bordo can be deleted, renamed
   or hand-edited with no UI for any of it; it survives closing the gesture; and
   the answer to «non c'è modo di annullare un piano e rifarlo» stops being a
   feature to build and becomes a line to delete.

   Each entry carries its own source NAME, which is what the label shows."
  []
  (into []
        (map-indexed (fn [i [nm e]]
                       (assoc e :id nm :label (str (inc i))
                              :kind (if (:points e) :curva :retta)
                              :name (name nm))))
        (sort-by key (:source-edges @stage))))
(defn- selected-ids [] (get-in @stage [:edge :selected] #{}))
(defn- selected-features [] (filterv #(contains? (selected-ids) (:id %)) (bench)))

(def ^:private line-sample-points
  "How many points to spread along a measured straight edge when it is used as
   evidence for a plane. A line is a line however finely it is sampled; twenty is
   enough for the robust fit to have something to chew on without letting one
   long edge outvote everything else on the bench."
  20)

(defn- feature-points
  "The 3D evidence a bench feature contributes to a plane: a curve's own recovered
   points, or points spread along a straight edge."
  [{:keys [kind points a b]}]
  (if (= :curva kind)
    (vec points)
    (mapv (fn [i]
            (let [t (/ (double i) (dec line-sample-points))]
              (m/v+ a (m/v* (m/v- b a) t))))
          (range line-sample-points))))

(def ^:private max-write-rms-px
  "Reprojection above which a measured feature must NOT be written, whatever else
   about it looks fine.

   It is the guard that was missing, and its absence was not theoretical: a
   circle fitted across three photos that had each followed a DIFFERENT curve
   came back at ⌀390 for a rim of 130 — with its centre near the object and its
   arc well covered, so every other test passed it, while its residual stood at
   157 px (2026-08-06). A residual that size does not mean 'imprecise', it means
   the figure does not explain the photographs at all.

   8 px is the same line the stage already draws for a badly registered photo
   (poor-registration-px): above it, what is drawn in the world visibly misses
   what is in the picture."
  8.0)

(defn- edge-usable?
  "Whether the bordo in hand is measured well enough to be KEPT on the bench.

   The two kinds fail differently and neither failure is visible in the picture.
   A straight edge is undetermined when the photos did not turn enough AROUND it;
   a curve, when the ray pairings between the two photos do not respect the ORDER
   the walks travelled, which means the photos followed stretches that do not
   overlap. Each is reported by its own number."
  []
  (let [f (edge-fit)]
    (boolean (and f (:plausible? f)
                  (if (= :curva (:kind f))
                    (and (>= (:agreement f) pcurve/min-agreement)
                         (>= (count (:points f)) pcurve/min-shared-points))
                    (and (<= (:rms-px f) max-write-rms-px)
                         (>= (:angle-deg f) pedge/min-plane-angle-deg)))))))

(defn- up-hints
  "Which direction of the OBJECT should read as `up` on a written mark. Same
   choice fit-candidate! makes for a plane mark, and for the same reason: a
   plate's HEADING is the turntable axis, while its up is just some direction
   across the disc."
  []
  (let [emit (:emit-pose @stage)]
    (if (:plate? @stage)
      [(:heading emit) (:up emit)]
      [(:up emit) (:heading emit)])))

(defn- toward-cameras
  "Mean position of the cameras that declared something — which side a measured
   surface faces, so a fitted normal is not signed at random."
  [idxs]
  (let [cams (into [] (keep #(get-in @stage [:camera-poses % :position]) idxs))]
    (when (seq cams)
      (mapv #(/ % (count cams)) (reduce m/v+ [0.0 0.0 0.0] cams)))))

(defn- solve-edge!
  "Measure the bordo in hand. Keyed by photo index, so declaring again on the same
   photo REPLACES that view (the natural way to correct a slip) instead of piling
   up two contradictory ones.

   Two kinds, one shape of answer — {:kind :points …} — because what happens next
   is the same for both: it goes on the bench, and the bench is what planes are
   made of.

   A STRAIGHT edge is the intersection of the planes its image lines span. Closed
   form, and — the part that matters — no point is ever paired with another, so
   there are no ghost correspondences to survive. That is why an edge is the
   sturdier evidence, and why two of them on a face beat one careful curve.

   A CURVE gives the 3D points where the photos' rays meet, which does need
   pairing, and carries the order test that says whether the pairing was real."
  []
  (let [entries (sort-by key (edge-obs))
        idxs (mapv key entries)
        curve? (curve-mode?)
        toward (toward-cameras idxs)
        views (mapv (fn [[_ o]] (select-keys o [:pose :points :intrinsics])) entries)
        segs (mapv (fn [[_ o]] (select-keys o [:pose :seg :intrinsics])) entries)
        fit (cond
              (< (count entries) 2) nil

              curve?
              (let [{:keys [points monotone]} (pcurve/curve-points views)]
                (when (>= (count points) 3)
                  (let [circ (pcircle/fit-circle views)]
                    {:kind :curva
                     :points points
                     :agreement monotone
                     :circle (when (and circ (>= (:span-deg circ) pcircle/min-arc-deg)
                                        (<= (:rms-px circ) max-write-rms-px)
                                        (plausible-point? (:center circ)))
                               circ)
                     :plausible? (every? plausible-point? points)})))

              :else
              (when-let [e (pedge/triangulate-edge (stage-intrinsics) segs)]
                (assoc e
                       :kind :retta
                       :plausible? (and (plausible-point? (:a e))
                                        (plausible-point? (:b e))))))]
    (swap! stage assoc-in [:edge :fit]
           (when fit (assoc fit :photos idxs :toward toward)))))

(defn- ray-point-near-object
  "Where to DRAW a pixel that has no depth yet: on its ray, at its closest
   approach to the object. On the photo it was clicked from, that point
   reprojects exactly under the cursor (every point of the ray does); on any
   other photo it slides along the epipolar line — which is the honest picture of
   what is known so far."
  [k pose px]
  (let [c (:position (:emit-pose @stage))]
    (when-let [{:keys [origin dir]} (pcamera/pixel-ray k pose px)]
      (m/v+ origin (m/v* dir (m/dot (m/v- c origin) dir))))))

(defn- edge-preview-items
  "What the edge gesture draws: the pending click, the line declared on the photo
   you are looking at, the measured 3D segment, and the edges already written to
   the source this session."
  []
  (when (edge-mode?)
    (let [here (:current-idx @stage)
          k (stage-intrinsics)
          pose (world-solver-pose here)
          fit (edge-fit)
          items (atom [])
          add! (fn [x] (swap! items conj x))]
      ;; the pending click is drawn from ITS OWN photo's pose, not the current
      ;; one: it is a point on that photo's ray, so it lands under the cursor
      ;; there and on the epipolar line everywhere else — which is the honest
      ;; picture, and what makes a stroke left behind on another photo visible.
      (when-let [{ppx :px pidx :idx} (get-in @stage [:edge :pending])]
        (when-let [ppose (world-solver-pose pidx)]
          (when-let [pk (get-in @stage [:edge :pending :intrinsics] k)]
            (when-let [p (ray-point-near-object pk ppose ppx)]
              (add! {:type :dots :data [{:pos p :radius 1.1
                                         :color edge-pending-color :opacity 0.95}]})))))
      ;; the stroke declared on THIS photo, drawn at object depth so it lies over
      ;; the pixels it was drawn on
      ;; What was declared ON THIS PHOTO, drawn AT ONCE — before any second photo
      ;; has made a measurement of it possible. Its absence was the first thing
      ;; Vincenzo hit (2026-08-06: «al primo click dice che la curva c'è, ma non
      ;; si vede nessuna linea»): the gesture reported success and showed
      ;; nothing, so there was no way to tell a good click from a bad one until
      ;; two photos later. Placed at the object's depth on its own ray, so on the
      ;; photo it was declared from it lies exactly over the pixels it came from.
      ;; the painted band, kept on screen after the stroke: it is the user's own
      ;; declaration of WHERE the edge is, and when the detector finds nothing it
      ;; is the only thing that says the gesture was heard at all.
      (let [br (get-in @stage [:edge :brush])
            here-br? (= (:idx br) here)
            zone (or (when here-br? (:stroke br))
                     (:zone (get (edge-obs) here)))
            r-px (when here-br? (:r br))]
        (when (and zone k pose)
          (let [pts (into [] (keep #(ray-point-near-object k pose %))
                          (pcurve/subsample zone 30))
                ;; the band is drawn at the width it ACTUALLY has. Millimetres per
                ;; photo pixel at the object's distance is depth/fx, so the dots
                ;; are the zone rather than a decoration of it — which matters,
                ;; because a marker whose drawn width does not match its effect is
                ;; worse than no marker at all.
                mm-per-px (when (and r-px (seq pts))
                            (/ (m/magnitude (m/v- (first pts)
                                                  (pcamera/camera-center pose)))
                               (:fx k)))]
            (when (seq pts)
              ;; the band carries the ANSWER in its colour: green when the stroke
              ;; found an edge, red when it did not. Said where the eye already
              ;; is — the console line was three panels away from the hand, which
              ;; is why 'non è chiaro se trova qualcosa o no' came back three
              ;; times (Vincenzo, 2026-08-07).
              (let [oc (get-in @stage [:edge :outcome])
                    col (cond (nil? oc) brush-color
                              (:ok? oc) brush-ok-color
                              :else brush-fail-color)]
                (add! {:type :dots
                       :data (mapv (fn [p] {:pos p
                                            :radius (if mm-per-px
                                                      (max 0.4 (* r-px mm-per-px))
                                                      1.6)
                                            :color col
                                            :opacity (if oc 0.35 0.22)})
                                   pts)}))))))
      (when-let [o (get (edge-obs) here)]
        (when (and k pose)
          (if (:points o)
            (let [pts (into [] (keep #(ray-point-near-object k pose %))
                            (pcurve/subsample (:points o) 60))]
              (when (seq pts)
                (add! {:type :dots
                       :data (mapv (fn [p] {:pos p :radius 0.7
                                            :color edge-drawn-color :opacity 0.9})
                                   pts)})))
            (let [[a b] (keep #(ray-point-near-object k pose %) (:seg o))]
              (when (and a b)
                (add! {:type :lines :data [{:from a :to b :color edge-drawn-color}]}))))))
      (doseq [{:keys [a b ring]} (get-in @stage [:edge :committed] [])]
        (if ring
          (add! {:type :lines :data (ring-segments ring edge-committed-color)})
          (add! {:type :lines :data [{:from a :to b :color edge-committed-color}]})))
      ;; the BENCH: every bordo already measured, still there, still numbered.
      ;; Picked ones brighter, and ones already spent on a plane dimmer — which
      ;; ones lie on the same face is the user's declaration and has to be
      ;; visible while making it.
      (doseq [feat (bench)]
        (let [picked? (contains? (selected-ids) (:id feat))
              col (if picked? bench-selected-color bench-color)]
          (if (= :curva (:kind feat))
            (add! {:type :dots
                   :data (mapv (fn [p] {:pos p :radius (if picked? 0.9 0.6)
                                        :color col
                                        :opacity (if picked? 0.95 0.6)})
                               (:points feat))})
            (add! {:type :lines :data [{:from (:a feat) :to (:b feat) :color col}]}))))
      ;; the planes are NOT redrawn here: they live in the source's :marks, and
      ;; source-mark-items already draws them from there. Keeping a second copy
      ;; in the gesture would be a copy that a deleted line could not reach.
      ;; and the bordo in hand, once it is measured
      (when (and fit (:plausible? fit))
        (if (= :curva (:kind fit))
          (add! {:type :dots
                 :data (mapv (fn [p] {:pos p :radius 0.8
                                      :color edge-measured-color :opacity 0.95})
                             (:points fit))})
          (do (add! {:type :lines :data [{:from (:a fit) :to (:b fit)
                                          :color edge-measured-color}]})
              (add! {:type :dots :data [{:pos (:a fit) :radius 0.9
                                         :color edge-measured-color :opacity 0.95}
                                        {:pos (:b fit) :radius 0.9
                                         :color edge-measured-color :opacity 0.95}]}))))
      @items)))

(defn- feature-anchor
  "Where to hang a bench feature's number: the middle of a segment, the middle
   point of a curve."
  [{:keys [kind a b points]}]
  (if (= :curva kind)
    (when (seq points) (nth points (quot (count points) 2)))
    (when (and a b) (m/v* (m/v+ a b) 0.5))))

(defn- labels-on? [] (get-in @stage [:edge :labels?] true))

(defn- toggle-labels!
  "'l' — put the numbers away, or bring them back.

   They answer a question one asks BETWEEN strokes («quale di questi è il numero
   2?») and get in the way DURING one, because they are drawn on top of
   everything and sit over the very photo one is trying to paint on (Vincenzo,
   2026-08-07: «con le labels così in evidenza non si riesce a selezionare
   ulteriori tratti»). Two modes of use, one switch."
  []
  (swap! stage update-in [:edge :labels?] #(not (if (nil? %) true %)))
  (say! (if (labels-on?)
          "etichette accese: ogni bordo del banco porta il suo numero nella foto"
          "etichette spente — 'l' le riaccende quando devi scegliere"))
  (redraw-overlay!))

(defn- edge-labels
  "The bench's numbers and the planes' names, written IN THE WORLD next to what
   they name. Empty when the gesture is off or the numbers are put away, so
   nothing of this lingers over an ordinary stage."
  []
  (when (and (edge-mode?) (labels-on?))
    (-> []
        (into (keep (fn [feat]
                      (when-let [p (feature-anchor feat)]
                        {:text (str (:label feat)
                                    "")
                         :position p
                         :color (if (contains? (selected-ids) (:id feat))
                                  bench-selected-color
                                  bench-color)}))
                    (bench)))
        (into (keep (fn [[nm mark]]
                      (when (and (map? mark) (:position mark))
                        {:text (name nm) :position (vec (:position mark))
                         :color fresh-plane-color}))
                    (:source-marks @stage))))))

;; ---- the gesture ----

(defn- report-edge!
  "Say what the bordo in hand is worth, in the currency the rest of the channel
   uses: pixels of reprojection and degrees of parallax for a straight edge, the
   order of the pairings for a curve — and, when one photo is the culprit, WHICH."
  []
  (let [f (edge-fit)]
    (cond
      (nil? f) nil

      (= :curva (:kind f))
      (cond
        (not (:plausible? f))
        (say! (str "i punti recuperati cadono lontano dall'oggetto: le due foto "
                   "non stanno guardando lo stesso bordo curvo"))

        (< (count (:points f)) pcurve/min-shared-points)
        (say! (str "le due foto condividono solo " (count (:points f))
                   " punti di questa curva (ne servono almeno "
                   pcurve/min-shared-points "): hanno seguito tratti quasi "
                   "disgiunti. Col pennarello dipingi sulla seconda foto LO STESSO "
                   "pezzo di bordo che hai dipinto sulla prima — non serve tutto, "
                   "serve in comune."))

        (< (:agreement f) pcurve/min-agreement)
        (say! (str "solo il " (src/fmt-number (* 100 (:agreement f)))
                   "% degli accoppiamenti fra le due foto rispetta l'ORDINE in cui "
                   "i due bordi sono stati percorsi (ne serve il "
                   (src/fmt-number (* 100 pcurve/min-agreement)) "%). Percorrere la "
                   "stessa curva da due parti da' accoppiamenti ordinati; incroci "
                   "casuali no. Quasi sempre vuol dire che le due foto hanno seguito "
                   "TRATTI DIVERSI dello stesso bordo: rifai quella che non si "
                   "sovrappone, partendo da dove passa l'altra."))

        :else
        (do (say! (str "curva misurata: le due foto condividono " (count (:points f))
                       " punti (ordine rispettato al "
                       (src/fmt-number (* 100 (:agreement f))) "%). "
                       (if (< (count (:points f)) 8)
                         (str "Sono POCHI, ma bastano come pezzo: tienila con 'n' e "
                              "aggiungi un altro bordo sulla stessa faccia — il piano "
                              "si fa con tutto insieme.")
                         "Tienila con 'n', poi prendi un altro bordo sulla stessa faccia e premi 'p'.")))
            (when-let [ci (:circle f)]
              (say! (str "per inciso, questa curva E' un cerchio di ⌀"
                         (src/fmt-number (* 2 (:radius ci))) " mm: 'c' scrive quello.")))))

      (not (:plausible? f))
      (say! (str "lo spigolo cade lontano dall'oggetto: i tratti disegnati non sono "
                 "lo stesso spigolo fisico, oppure le due foto sono troppo simili"))

      (< (:angle-deg f) pedge/min-plane-angle-deg)
      (say! (str "le foto girano solo " (src/fmt-number (:angle-deg f))
                 "° INTORNO allo spigolo: troppo poco perche' la sua posizione sia "
                 "determinata. Serve una foto che lo guardi da un altro lato — "
                 "spostarsi lungo lo spigolo non serve a niente, e nemmeno mettersi "
                 "esattamente di fronte alla foto di prima (mezzo giro riporta allo "
                 "stesso piano)."))

      (> (:rms-px f) max-write-rms-px)
      (say! (str "riproiezione " (src/fmt-number (:rms-px f))
                 " px: questo spigolo NON spiega le foto da cui e' nato — su "
                 "qualcuna e' stato seguito un bordo diverso."))

      :else
      (do (say! (str "spigolo su " (count (:photos f)) " foto · lunghezza "
                     (src/fmt-number (:length-mm f)) " mm · riproiezione "
                     (src/fmt-number (:rms-px f)) " px · giro "
                     (src/fmt-number (:angle-deg f)) "°"
                     (when (:exact? f)
                       (str " — con DUE foto la retta ci passa esatta: quello 0 px "
                            "non e' una verifica, aggiungi una terza foto perche' lo diventi"))))
          (when-let [w (:worst-obs f)]
            (say! (str "il tratto sbagliato e' quello della foto "
                       (nav-rank (nth (:photos f) w))
                       ": vacci con [ o ] e ridisegnalo.")))))))

(defn- finish-edge-stroke!
  "Second click on a photo: the stroke is closed, snapped to the real gradient,
   and becomes this photo's declaration.

   The snap is what makes two rough clicks worth a measurement — cross-peak finds
   the luminance edge perpendicular to the stroke at ~40 stations and refits the
   line through them (edge-snap/snap-segment, the same routine the box gesture
   uses). It is also the one step that can fail honestly: on a soft or occluded
   edge too few stations peak, and then the raw clicks stand, SAID OUT LOUD —
   a silent fallback would quietly turn a measurement into a guess."
  [idx p1 p2 pose k]
  (if (< (pedge/segment-length-px [p1 p2]) pedge/min-segment-px)
    (do (swap! stage update :edge dissoc :pending)
        (say! (str "i due click sono quasi sovrapposti (meno di "
                   pedge/min-segment-px " px): un tratto così corto non dice in che "
                   "direzione va lo spigolo. Ridisegnalo più lungo."))
        (redraw-overlay!))
    (let [snap (edge-snap/snap-segment backdrop/luminance-at p1 p2)
          seg (if snap [(:p1 snap) (:p2 snap)] [p1 p2])]
      (swap! stage update :edge
             #(-> %
                  (assoc-in [:obs idx] {:seg seg :raw [p1 p2] :pose pose
                                        :intrinsics k :snap snap})
                  (dissoc :pending)))
      (solve-edge!)
      (if snap
        (say! (str "tratto preso sulla foto " (nav-rank idx) " · agganciato al "
                   "contrasto su " (:n snap) " punti (scarto "
                   (src/fmt-number (:rms snap)) " px)"))
        (say! (str "tratto preso sulla foto " (nav-rank idx)
                   " · NON agganciato al contrasto (l'edge è sfumato o coperto qui): "
                   "valgono i tuoi due click, quindi conta la precisione della mano")))
      (case (registration-trouble idx)
        :grazing (say! (str "nota: la foto " (nav-rank idx) " è radente, quindi meno "
                            "precisa — il tratto è preso lo stesso"))
        (:flipped :loose) (say! (str "attenzione: la foto " (nav-rank idx)
                                     " è registrata male, su di lei tutto si "
                                     "riproietta storto"))
        nil)
      (report-edge!)
      (redraw-overlay!))))

(defn- edge-refusal-message
  "Why one click was not enough, said so the user knows what to do next rather
   than that something failed."
  [{:keys [reason coherence length-px]}]
  (case reason
    :flat (str "qui non c'è contrasto: non vedo nessun bordo. Se lo spigolo c'è "
               "ma è debole, dammelo con DUE click (uno adesso, uno più avanti "
               "lungo lo spigolo).")
    :ambiguous (str "qui i bordi sono più di uno (coerenza "
                    (src/fmt-number coherence) ", ne serve "
                    (src/fmt-number edge-snap/min-coherence)
                    "): sei su un angolo, su un incrocio o su una texture. "
                    "Clicca più lontano dall'angolo, oppure dammi DUE click.")
    :short (str "il bordo qui dura solo " (src/fmt-number length-px)
                " pixel: troppo poco perché la sua direzione valga più della tua "
                "mano. Cliccane uno più lungo, o usa DUE click.")
    :curved (str "questo bordo è curvo, ma le altre foto lo dichiarano dritto: "
                 "o non è lo stesso bordo, o questa foto lo guarda di taglio. "
                 "(Uno spigolo dritto e un cerchio sono misure diverse: per "
                 "misurare il cerchio, chiudi e ricomincia cliccando prima qui.)")
    "non riesco a leggere il bordo da qui: dammi DUE click."))

(def ^:private brush-sizes
  "The ladder the marker's half-width climbs, in SCREEN pixels.

   A ladder and not a fixed value because the first version had none, and the
   consequence was the one thing a marker must never do (Vincenzo, 2026-08-07:
   «la pennellata cambia size con lo zoom, per cui non si riesce ad
   assottigliarla»). Screen pixels rather than photo pixels so the nib feels the
   same under the hand at any zoom; what zooming changes is how much of the OBJECT
   it covers, which is the useful half of the bargain and the one worth keeping."
  [4.0 8.0 14.0 22.0 34.0])

(defn- brush-px [] (get-in @stage [:edge :brush-px] 14.0))

(defn- cycle-brush!
  "Next nib on the ladder, wrapping — a cycling control that carries its current
   value as its own label, like the plane gesture's nudge step."
  [dir]
  (let [cur (brush-px)
        i (or (first (keep-indexed #(when (= %2 cur) %1) brush-sizes)) 2)
        nxt (nth brush-sizes (mod (+ i dir) (count brush-sizes)))]
    (swap! stage assoc-in [:edge :brush-px] nxt)
    (say! (str "pennarello: punta " (src/fmt-number nxt) " px di schermo"
               " (le altre: " (str/join " · " (map src/fmt-number brush-sizes)) ")"))
    (redraw-overlay!)))

(def ^:private zone-end-margin-px
  "How far past the painted stroke's ends the walk may still go, in photo pixels.
   A little, because the hand stops where it means to stop but not to the pixel."
  12.0)

(defn- zone-pred
  "'Look for the line in HERE'. Two conditions, and separating them is the point:

   - LATERALLY, within `r` of the painted polyline. This is tolerance for the
     hand, and it wants to be generous;
   - ALONG the stroke, between its two ends. This is the instruction — «fin
     qui» — and it wants to be exact.

   The first version used only the lateral distance, and so one number had to do
   both jobs at once. They pull opposite ways, and the measurement showed it on
   Vincenzo's own edge (2026-08-07): with a band of 10-30 px the walk was held
   and the edge came back straight at 1.19 px; at 60 px it escaped again and the
   answer went back to :curved. A wide band does not merely fail to stop the walk
   at the stroke's end — near a corner it lets the walk CUT ACROSS onto the other
   branch, which is the very thing being guarded against. Meanwhile the default
   nib, at ordinary zoom, was already producing about 56 px.

   Projecting on the stroke's own principal direction settles it: lateral
   tolerance can be as wide as a hand needs, while the extent stays exactly what
   was painted."
  [pts r]
  (let [n (count pts)
        r2 (* r r)
        cx (/ (reduce + (map first pts)) n)
        cy (/ (reduce + (map second pts)) n)
        [sxx syy sxy] (reduce (fn [[axx ayy axy] [px py]]
                                (let [a (- px cx) b (- py cy)]
                                  [(+ axx (* a a)) (+ ayy (* b b)) (+ axy (* a b))]))
                              [0.0 0.0 0.0] pts)
        th (* 0.5 (Math/atan2 (* 2.0 sxy) (- sxx syy)))
        vx (Math/cos th) vy (Math/sin th)
        ts (mapv (fn [[px py]] (+ (* (- px cx) vx) (* (- py cy) vy))) pts)
        t-lo (- (reduce min ts) zone-end-margin-px)
        t-hi (+ (reduce max ts) zone-end-margin-px)]
    (fn [x y]
      (let [t (+ (* (- x cx) vx) (* (- y cy) vy))]
        (and (<= t-lo t t-hi)
             (loop [i 0]
               (if (>= i (dec n))
                 false
                 (let [[ax ay] (nth pts i)
                       [bx by] (nth pts (inc i))
                       dx (- bx ax) dy (- by ay)
                       len2 (+ (* dx dx) (* dy dy))
                       u (if (< len2 1e-9)
                           0.0
                           (max 0.0 (min 1.0 (/ (+ (* (- x ax) dx) (* (- y ay) dy)) len2))))
                       px (+ ax (* u dx)) py (+ ay (* u dy))
                       d2 (+ (* (- x px) (- x px)) (* (- y py) (- y py)))]
                   (if (<= d2 r2) true (recur (inc i)))))))))))

(defn- try-one-click!
  "The normal case: ONE click, and the edge finds its own direction and its own
   extent (edge-snap/edge-at-point). Returns true when it did.

   A click is a point and a point is not a line, so the direction has to come
   from somewhere: it comes from the gradients around the click, which is the
   only place it CAN come from — the geometry already known constrains the 3D
   line by one degree of freedom out of four, so it would need a second click on
   every photo anyway.

   What is gained is not only the second click. The walk follows the edge as far
   as the contrast goes, which is usually much further than anyone would trace by
   hand, and a longer baseline is a better-determined edge — the convenience and
   the accuracy pull the same way for once."
  ([idx px pose k] (try-one-click! idx px pose k nil))
  ([idx px pose k zone]
   (let [r (if zone
            ;; a painted zone: try seeds ALONG it until one takes. Which edge is
            ;; meant is the user's declaration; WHERE exactly on it to start is
            ;; the program's business, and a thick stroke is allowed to be sloppy.
             (or (first (keep (fn [q]
                                (let [res (edge-snap/edge-at-point
                                           backdrop/luminance-at (first q) (second q)
                                           {:in-zone? (:pred zone) :zoned? true})]
                                  (when (or (:ok? res) (= :curved (:reason res))) res)))
                              (:seeds zone)))
                 (edge-snap/edge-at-point backdrop/luminance-at (first px) (second px)
                                          {:in-zone? (:pred zone) :zoned? true}))
             (edge-snap/edge-at-point backdrop/luminance-at (first px) (second px)))
         curved? (= :curved (:reason r))
         kind (if curved? :curva :retta)
         cur-kind (get-in @stage [:edge :kind])
         others (dissoc (edge-obs) idx)
         ;; A declaration of the OTHER kind is not a mistake to refuse — it is
         ;; almost always a new bordo. Refusing it was the bug that made the
         ;; gesture die after its first success (Vincenzo, 2026-08-07: «non sono
         ;; riuscito a trovare nessun bordo, tranne la prima volta»): the kind
         ;; stayed latched from the first stroke, so every stroke of the other
         ;; kind came back with a message about 'the other photos' that meant
         ;; nothing here.
         ;;
         ;; The kind belongs to a FEATURE, and a feature is what the bench holds.
         ;; So: a clash only exists when this feature already has views on OTHER
         ;; photos — that is the case where the user is genuinely adding a second
         ;; view of one thing. Otherwise the declaration simply starts a fresh
         ;; one, and says so.
         clash? (and cur-kind (not= cur-kind kind) (seq others))
         outcome! (fn [ok? txt]
                    (swap! stage assoc-in [:edge :outcome] {:ok? ok? :text txt})
                    ok?)
         take! (fn [entry msg]
                 (swap! stage update :edge
                        (fn [e] (-> (if clash? (assoc e :obs {}) e)
                                    (assoc :kind kind)
                                    (assoc-in [:obs idx] entry)
                                    (dissoc :pending))))
                 (solve-edge!)
                 (say! (if clash?
                         (str msg " — le foto di prima lo vedevano "
                              (if (= :curva cur-kind) "CURVO" "DRITTO")
                              ", quindi questo comincia un bordo NUOVO. Se volevi "
                              "aggiungere una vista a quello di prima, Backspace "
                              "torna indietro.")
                         msg))
                 (outcome! true msg)
                 (report-edge!)
                 (redraw-overlay!)
                 true)]
     (cond
       curved?
       (take! {:points (:points r) :raw [px px] :pose pose :intrinsics k
               :zone (:stroke zone)
               :snap {:n (count (:points r))} :auto? true :curved? true}
              (str "bordo CURVO sulla foto " (nav-rank idx) ": "
                   (count (:points r)) " punti su " (Math/round (:walked-px r)) " px"))

       (:ok? r)
       (take! {:seg [(:p1 r) (:p2 r)] :raw [px px] :pose pose :intrinsics k
               :zone (:stroke zone)
               :snap {:n (:n r) :rms (:rms r)} :auto? true}
              (str "bordo DRITTO sulla foto " (nav-rank idx) ": "
                   (Math/round (:length-px r)) " px su " (:n r)
                   " punti, scarto " (src/fmt-number (:rms r)) " px"))

       :else (let [m (edge-refusal-message r)]
               (say! m)
               (outcome! false m)
               (redraw-overlay!)
               false)))))

(def ^:private walk-tube-px
  "How far, in PHOTO pixels, the walk may stray from the edge the pennellata
   actually landed on. Absolute and small on purpose.

   The lateral limit used to be the brush's own width, measured from the PAINTED
   stroke, and that made it do a job it could not do: it had to absorb the hand's
   error AND keep the walk on one edge. So a wide band let the walk hop to a
   neighbouring parallel edge (Vincenzo, 2026-08-07: «senza zoom viene verde, ma
   credo prenda altri bordi che a quel punto vengono inclusi») and a narrow one —
   which is what zooming in produces, the width being in screen pixels — let the
   walk out after forty pixels of a five-hundred-pixel stroke, reported as «non
   c'è contrasto».

   Once the painted points are snapped sideways onto the contrast, the tube can
   be measured from the EDGE instead, and then it needs to be neither wide nor
   zoom-dependent: 14 px is more than any real edge wanders between stations and
   far less than the gap to the next edge."
  14.0)

(defn- paint-declare!
  "A DRAG in edge mode: the user has painted a band and said 'the line is in
   here'. Three quantities come out of that one gesture, and keeping them apart
   is what finally made it work:

   - the WIDTH (the nib, in screen pixels, converted by the stroke's own
     measured scale): how far off the edge the hand is allowed to be. Used to
     SNAP the painted points sideways onto the contrast, and for nothing else;
   - the LENGTH: where the edge is meant to stop. Enforced along the stroke's
     principal direction;
   - the TUBE (walk-tube-px, absolute): how far the walk may stray from the edge
     it landed on. Measured from the SNAPPED points, not the painted ones, so it
     no longer has to absorb the hand's error and can be as tight as keeping to
     one edge requires.

   The scale is read off the TOTAL PATH LENGTH rather than the distance between
   the first and last points: a stroke that curves back on itself has almost no
   end-to-end span, and reading the scale from that gave a nib of random width."
  [idx trail pose k]
  (let [pts (mapv :px trail)
        screen (mapv :screen trail)
        path (fn [ps] (reduce + 0.0 (map (fn [a b] (Math/hypot (- (first b) (first a))
                                                               (- (second b) (second a))))
                                         ps (rest ps))))
        scale (let [sc (path screen)]
                (if (> sc 1.0) (/ (path pts) sc) 1.0))
        r (max 3.0 (* (brush-px) scale))
        n (count pts)
        dir-at (fn [i]
                 (let [j (min (dec n) (max 1 i))
                       [ax ay] (nth pts (dec j))
                       [bx by] (nth pts j)
                       d (Math/hypot (- bx ax) (- by ay))]
                   (if (< d 1e-6) [1.0 0.0] [(/ (- bx ax) d) (/ (- by ay) d)])))
        ;; snap the WHOLE stroke sideways onto the contrast: what comes back is
        ;; where the edge actually runs, which is what the walk should be held to
        snapped (into [] (keep-indexed
                          (fn [i [px py]]
                            (let [[ux uy] (dir-at i)
                                  nx (- uy) ny ux]
                              (when-let [pk (edge-snap/cross-peak backdrop/luminance-at
                                                                  px py nx ny (Math/round r))]
                                [(+ px (* nx (:t pk))) (+ py (* ny (:t pk)))])))
                          pts))
        on-edge? (>= (count snapped) (max 3 (quot n 3)))
        spine (if on-edge? snapped pts)
        tube (if on-edge? walk-tube-px (max r walk-tube-px))
        m (count spine)
        order (sort-by #(Math/abs (- % (quot m 2))) (range m))
        zone {:pred (zone-pred spine tube) :stroke pts
              :seeds (mapv #(nth spine %) (take 12 order))}]
    (if (< (count pts) 2)
      (do (swap! stage assoc-in [:edge :outcome]
                 {:ok? false :text "pennellata troppo corta"})
          (say! "pennellata troppo corta")
          (redraw-overlay!))
      (do (swap! stage assoc-in [:edge :brush] {:idx idx :stroke pts :r r})
          (when-not (try-one-click! idx (nth spine (quot m 2)) pose k zone)
            (redraw-overlay!))))))

(defn- edge-click!
  "One click in edge mode. Normally that is the whole gesture (try-one-click!);
   when the image cannot answer — a corner, a curve, no contrast — the program
   says so and falls back to asking for the second click, which is also what a
   second click on an already-declared photo means: 'let me draw this one'."
  [^js e]
  (let [idx (:current-idx @stage)]
    (if-let [pose (world-solver-pose idx)]
      (if-let [px (backdrop/pixel-under-pointer e (viewport/get-camera) (viewport/get-canvas))]
        (if-let [k (stage-intrinsics)]
          (let [{ppx :px pidx :idx} (get-in @stage [:edge :pending])
                open! (fn [msg]
                        (swap! stage assoc-in [:edge :pending]
                               {:px px :idx idx :intrinsics k})
                        (say! msg)
                        (redraw-overlay!))]
            (cond
              ;; A stroke belongs to ONE photo. Half of it left behind on another
              ;; one is not half a declaration — the two pixels would be read as
              ;; a line on THIS photo, which is a line through nothing. Start
              ;; over here, and say why, rather than silently building it.
              (and ppx (not= pidx idx))
              (open! (str "avevi un capo aperto sulla foto " (nav-rank pidx)
                          ": un tratto sta tutto su UNA foto, quindi quello è "
                          "stato lasciato e questo click apre il tratto di qui"))

              ppx (finish-edge-stroke! idx ppx px pose k)

              ;; Clicking again on a photo that already has its stroke means 'I
              ;; want to draw this one myself' — which is how the user takes back
              ;; an extent the walk chose, or declares a short straight stretch of
              ;; something the detector rightly called curved.
              (contains? (edge-obs) idx)
              (open! (str "questa foto ha già il suo tratto: questo click apre un "
                          "tratto A MANO che lo sostituirà — clicca l'altro capo"))

              ;; the normal case: one click, and the image answers
              (try-one-click! idx px pose k) nil

              :else
              (open! (str "…quindi facciamolo a mano: primo capo preso (pallino "
                          "giallo), clicca il secondo più avanti lungo lo spigolo"))))
          (say! "nessuna foto caricata: non so a che risoluzione riferire il click"))
        (say! "il click è caduto fuori dalla foto"))
      (say! (str "la foto " (nav-rank idx) " non ha una posa registrata: non può "
                 "contribuire — cambiane una con [ o ]")))))

(defn- undo-edge-click!
  "Backspace: drop the pending click, else the line declared on the photo you are
   looking at — the one you just drew wrong — falling back to the last one
   declared anywhere."
  []
  (let [here (:current-idx @stage)
        obs (edge-obs)]
    (cond
      (get-in @stage [:edge :pending])
      (do (swap! stage update :edge dissoc :pending)
          (say! "primo capo annullato"))

      (seq obs)
      (let [victim (if (contains? obs here) here (key (last (sort-by key obs))))]
        (swap! stage update :edge
               (fn [e] (-> e
                           (update :obs dissoc victim)
                           ;; the painted band goes with the declaration it made:
                           ;; leaving it behind would mean the screen still showed
                           ;; a zone that no longer bounds anything
                           (cond-> (= (:idx (:brush e)) victim) (dissoc :brush))
                           (cond-> (empty? (dissoc (:obs e) victim)) (dissoc :kind)))))
        (solve-edge!)
        (say! (str "tratto della foto " (nav-rank victim) " tolto")))

      ;; nothing in hand: what is on the bench lives in the SOURCE now, and the
      ;; way to take it back is the way one takes back any text
      (seq (bench))
      (say! (str "non c'e' niente in mano. I bordi tenuti stanno nel sorgente, nel "
                 "blocco :edges — per toglierne uno cancella la sua riga e rilancia."))

      :else (say! "non c'è niente da togliere"))
    (redraw-overlay!)))

(defn- reset-current!
  "'r' — throw away the bordo in hand, pennellata and all, and start over.

   Backspace unwinds one step at a time, which is right when one step is what
   went wrong; this is for when the whole attempt is a mess and picking it apart
   costs more than redoing it (Vincenzo, 2026-08-07: «forse serve un modo per
   resettare la pennellata e ricominciare?»). The BENCH is untouched — what has
   been measured and kept is not part of the mess."
  []
  (swap! stage update :edge #(-> % (assoc :obs {}) (dissoc :fit :pending :kind :brush :outcome)))
  (say! "ricominciato: pennellata e bordo in mano azzerati (il banco resta)")
  (redraw-overlay!))

;; ---- source write-back ----

(defn- next-edge-name
  "`spigolo-N` / `cerchio-N`, N one past the highest already in the form — read
   from the SOURCE, where the features actually live (the user may have renamed
   or deleted some). The two families count separately, so a source holding both
   reads as what it is instead of interleaving them."
  [form-text stem]
  (let [nums (map #(js/parseInt (second %) 10)
                  (re-seq (re-pattern (str ":" stem "-(\\d+)\\b")) form-text))]
    (str stem "-" (inc (reduce max 0 nums)))))

(defn- edge-literal
  "The `(edge-mark {…})` source of a measured edge.

   It is a POSE first — :position :heading :up, like every other mark — so it
   needs no new DSL to be useful: `(turtle (:spigolo-1 (:edges A)) (extrude
   (circle 2) (f (:length …))))` runs a fillet down it. :a and :b are its ends
   and :length their distance, which is the number the `(f …)` wants.

   Unlike a plane mark it carries no :from evidence: an edge's evidence is a
   PIXEL LINE per photo, and the source has no business knowing about photo
   identities (the same boundary mark-literal draws). Re-declaring an edge means
   drawing it again — until the observation store of the next slice makes it
   re-openable."
  [{:keys [position heading up]} a b]
  (str "{:position " (src/fmt-vec3 position)
       " :heading " (src/fmt-vec3 heading)
       " :up " (src/fmt-vec3 up)
       " :a " (src/fmt-vec3 a)
       " :b " (src/fmt-vec3 b)
       " :length " (src/fmt-number (m/magnitude (m/v- b a)))
       "}"))

(defn- circle-literal
  "The `(circle-mark {…})` source of a measured circle. A POSE first, like every
   other mark — origin at the centre, heading along the axis — with the radius
   attached, which is the number the user came for."
  [{:keys [position heading up radius]}]
  (str "{:position " (src/fmt-vec3 position)
       " :heading " (src/fmt-vec3 heading)
       " :up " (src/fmt-vec3 up)
       " :radius " (src/fmt-number radius)
       "}"))

(defn- ensure-edges-slot!
  "Give the acquire form an `:edges {}` block when it has none, by inserting one
   right after :marks at the same indentation. Forms emitted before edges existed
   have no such slot, and refusing to write into them would mean asking the user
   to hand-edit a map before the gesture would work. Returns the buffer text
   after the insertion, or nil when there is no :marks to hang it off."
  [text from to]
  (when-let [[_ e i] (src/map-value-bounds text from to ":marks")]
    (let [col (src/column-of text i)
          ins (str "\n" (apply str (repeat col " ")) ":edges {}")]
      (modal/replace-source! e e ins)
      (cm/get-value))))

(defn- commit-edge!
  "Write the measured edge into the evaluated `(acquire …)`'s :edges block and
   re-run the definitions. A bounded text edit — only that block's braces move —
   so everything else in the form survives byte-identical. Returns the name
   written, or nil."
  [stem entry-fn]
  (let [text (cm/get-value)]
    (if-let [[from to] (acquire-form-bounds text)]
      (let [text (if (src/map-value-bounds text from to ":edges")
                   text
                   (ensure-edges-slot! text from to))
            [from to] (when text (acquire-form-bounds text))]
        (if-let [[o e i] (and text (src/map-value-bounds text from to ":edges"))]
          (let [nm (next-edge-name (.substring text from to) stem)
                updated (src/append-map-entry
                         (.substring text o e)
                         (entry-fn nm)
                         ":edges" (src/column-of text i))]
            (modal/replace-source! o e updated)
            (modal/run-definitions!)
            nm)
          (do (say! (str "la forma (acquire …) non ha né uno slot :edges né uno "
                         ":marks a cui affiancarlo — aggiungi :edges {} dentro la "
                         "mappa e riprova"))
              nil)))
      (do (say! "non trovo la forma (acquire …) nel sorgente")
          nil))))

(def ^:private evidence-points
  "How many of the recovered 3D points to write into the mark's `:from`. They are
   the mark's evidence — what makes it re-editable — but a walked curve arrives
   forty-odd points long and a literal that size buries the form it lives in.
   A dozen, evenly spaced, still describes the zone."
  12)

(defn- curve-literal
  "The `(curve-mark {…})` source of a measured curved edge: its 3D points, thinned
   to what still describes the curve. Evidence, in the source, where it can be
   deleted as text."
  [pts]
  (str "{:points [" (str/join " " (map src/fmt-vec3 (pcurve/subsample pts evidence-points)))
       "]}"))

(defn- keep-feature!
  "'n' — keep the bordo in hand by WRITING IT INTO THE SOURCE, in the acquire's
   `:edges` block, next to the ones kept before.

   It used to go onto a bench that lived in the gesture's own state, and that was
   the wrong home: it needed a UI to delete from, another to rename in, and it
   vanished when the gesture closed. In the source it needs none of those — the
   text editor already does all three — and it survives."
  []
  (let [f (edge-fit)]
    (cond
      (nil? f)
      (say! "questo bordo non e' ancora misurato: serve una dichiarazione su DUE foto")

      (not (edge-usable?))
      (report-edge!)

      (= :curva (:kind f))
      (if-let [nm (commit-edge! "curva"
                                (fn [nm] (str ":" nm " (curve-mark "
                                              (curve-literal (:points f)) ")")))]
        (do (swap! stage update :edge
                   #(-> % (assoc :obs {}) (dissoc :fit :pending :kind :brush :outcome)))
            (say! (str "curva scritta nel sorgente come :" nm " ("
                       (count (:points f)) " punti). Prendine un altro bordo sulla "
                       "stessa faccia e poi premi 'p'. Per toglierla, cancella la "
                       "sua riga nel sorgente."))
            (redraw-overlay!))
        (redraw-overlay!))

      :else
      (if-let [mark (pedge/edge-mark (:a f) (:b f) (up-hints))]
        (if-let [nm (commit-edge! "spigolo"
                                  (fn [nm] (str ":" nm " (edge-mark "
                                                (edge-literal mark (:a f) (:b f)) ")")))]
          (do (swap! stage update :edge
                     #(-> % (assoc :obs {}) (dissoc :fit :pending :kind :brush :outcome)))
              (say! (str "spigolo scritto nel sorgente come :" nm " · lunghezza "
                         (src/fmt-number (:length-mm f)) " mm. Prendine un altro sulla "
                         "stessa faccia e poi premi 'p'. Per toglierlo, cancella la "
                         "sua riga nel sorgente."))
              (redraw-overlay!))
          (redraw-overlay!))
        (say! "i due capi coincidono: non c'e' una direzione da scrivere")))))

(defn- toggle-selected!
  "A digit key picks or unpicks a bordo of the bench. Which ones lie on the same
   face is the user's declaration — the program has no way to know it, and
   guessing would be exactly the kind of silent decision this channel refuses."
  [n]
  (let [ids (mapv :id (bench))]
    (when-let [id (nth ids (dec n) nil)]
      (swap! stage update-in [:edge :selected]
             (fn [sel] (let [sel (or sel #{})]
                         (if (sel id) (disj sel id) (conj sel id)))))
      (say! (str "bordo :" (name id)
                 (if (contains? (selected-ids) id) " selezionato" " deselezionato")
                 " · selezionati: "
                 (if (seq (selected-ids))
                   (str/join ", " (sort (map name (selected-ids)))) "nessuno")))
      (redraw-overlay!))))

(defn- plane-from-bench!
  "'p' — the plane through the bordi you have picked: 'questi stanno sullo stesso
   piano'. Written as an ordinary `(plane-mark {…})` in `:marks`, through the very
   same write-back the three-point gesture uses, so everything downstream — turtle
   :at, edit-path-2d on the zone, acquire-union's anchors — works untouched.

   Two straight edges are the sturdiest evidence there is here, because a
   straight edge is measured WITHOUT pairing any points: no ghosts to survive, no
   order to respect. A single gentle curve is the weakest, and the width says so."
  []
  (let [feats (selected-features)
        pts (vec (mapcat feature-points feats))
        idxs (distinct (mapcat :photos feats))]
    (cond
      (empty? feats)
      (say! (str "nessun bordo selezionato: tienine almeno uno con 'n', poi premi "
                 "il suo numero per selezionarlo"))

      (< (count pts) 3)
      (say! "i bordi selezionati non hanno abbastanza punti per un piano")

      :else
      (if-let [pl (pcurve/plane-from-points pts {:toward (toward-cameras idxs)
                                                 :up-hints (up-hints)})]
        (cond
          (< (:width-mm pl) pcurve/min-width-mm)
          (say! (str "i bordi selezionati stanno quasi in FILA (larghi "
                     (src/fmt-number (:width-mm pl)) " mm), e una fila sta su "
                     "INFINITI piani: 1 mm d'errore inclinerebbe la normale di "
                     (src/fmt-number (:tilt-per-mm-deg pl)) "°. Aggiungine uno "
                     "trasversale, non parallelo a questi."))

          :else
          (let [mark (select-keys pl [:position :heading :up])
                ev (pcurve/subsample (:points pl) evidence-points)]
            (if-let [nm (commit-plane-mark! mark ev)]
              (do (swap! stage update :edge
                         (fn [e] (-> e
                                     (assoc :selected #{})
                                     (assoc :outcome {:ok? true
                                                      :text (str "piano :" nm
                                                                 " scritto — è il dischetto AZZURRO sull'oggetto")}))))
                  ;; a mark has just been made: showing marks is the obvious
                  ;; follow-through, and leaving the toggle off would mean writing
                  ;; something the user then cannot find (which is exactly what
                  ;; happened: «ho generato un piano, ma non capisco dove lo vedo»)
                  (swap! stage assoc :show-source-marks? true)
                  (say! (str "scritto :" nm " nel sorgente come mark-piano, da "
                             (count feats) " bordi · larghezza "
                             (src/fmt-number (:width-mm pl)) " mm, planarita' "
                             (src/fmt-number (:flatness-mm pl)) " mm"
                             (when (> (:flatness-mm pl) 1.0)
                               " — ATTENZIONE, i bordi non sono cosi' complanari")
                             ". Per DISFARLO, cancella la riga :" nm " nel sorgente e "
                             "rilancia — non c'e' altro da annullare, perche' il "
                             "sorgente e' l'unico posto dove vive. "
                             "LO VEDI come dischetto AZZURRO sull'oggetto: cambia "
                             "foto con [ e ] e deve restare incollato alla superficie. "
                             "Il bottone Mark lo accende e spegne. Usalo come "
                             "qualunque altro piano: (turtle A :at :" nm " …)."))
                  (redraw-overlay!))
              (redraw-overlay!))))
        (say! "i bordi selezionati non definiscono un piano")))))

(defn- accept-circle!
  "'c' — write the bordo in hand as a CIRCLE, when the curve really is one."
  []
  (let [f (edge-fit)
        ci (:circle f)]
    (if-not ci
      (say! (str "questa curva non e' un cerchio abbastanza definito (o se ne vede "
                 "troppo poco): tienila con 'n' e usala per un piano."))
      (if-let [mark (pcircle/circle-mark ci {:toward (:toward f) :up-hints (up-hints)})]
        (if-let [nm (commit-edge! "cerchio"
                                  (fn [nm] (str ":" nm " (circle-mark "
                                                (circle-literal mark) ")")))]
          (do (swap! stage update :edge
                     #(-> % (update :committed conj {:ring (pcircle/ring-mesh ci)})
                          (assoc :obs {}) (dissoc :fit :pending :kind)))
              (say! (str "scritto :" nm " nel sorgente · diametro "
                         (src/fmt-number (* 2 (:radius ci))) " mm. "
                         "La turtle ci sta al CENTRO col naso lungo l'asse: "
                         "(turtle A :at :" nm " (extrude (circle "
                         (src/fmt-number (:radius ci)) ") (f …)))."))
              (redraw-overlay!))
          (redraw-overlay!))
        (say! "il cerchio misurato non ha una normale utilizzabile")))))

(defn- accept-edge!
  "Enter — write the bordo in hand as a measured EDGE. For a curve there is
   nothing to write on its own: a curve's value is the plane, so it says to keep
   it and press 'p'."
  []
  (let [f (edge-fit)]
    (cond
      (nil? f)
      (say! "serve una dichiarazione su almeno DUE foto perche' ci sia qualcosa da scrivere")

      (not (edge-usable?))
      (report-edge!)

      (= :curva (:kind f))
      (say! (str "una curva non si scrive da sola: quello che vale e' il PIANO su "
                 "cui e' adagiata. Tienila con 'n', prendi un altro bordo sulla "
                 "stessa faccia, e premi 'p'. (Se e' un cerchio vero, 'c' scrive "
                 "quello.)"))

      :else
      (if-let [mark (pedge/edge-mark (:a f) (:b f) (up-hints))]
        (if-let [nm (commit-edge! "spigolo"
                                  (fn [nm] (str ":" nm " (edge-mark "
                                                (edge-literal mark (:a f) (:b f)) ")")))]
          (do (swap! stage update :edge
                     #(-> % (update :committed conj {:a (:a f) :b (:b f)})
                          (assoc :obs {}) (dissoc :fit :pending :kind)))
              (say! (str "scritto :" nm " nel sorgente · lunghezza "
                         (src/fmt-number (:length-mm f)) " mm. "
                         "Usalo con (turtle (:" nm " (:edges A)) …): la turtle parte "
                         "da un capo e (f " (src/fmt-number (:length-mm f))
                         ") arriva all'altro."))
              (redraw-overlay!))
          (redraw-overlay!))
        (say! "i due capi coincidono: non c'e' una direzione da scrivere")))))

(defn- edge-status
  "One line of live state for the toolbar button."
  []
  (let [n (count (edge-obs))
        f (edge-fit)
        b (count (bench))
        sel (count (selected-ids))]
    (cond
      (get-in @stage [:edge :pending]) "Bordi · clicca il secondo capo"
      f (str "Bordi · " (if (= :curva (:kind f)) "curva" "retta") " misurata"
             (when (pos? b) (str " · banco " b)))
      (= 1 n) "Bordi · 1 foto (vai su un'altra)"
      (pos? b) (str "Bordi · banco " b ", selezionati " sel)
      :else "Bordi · clicca un bordo")))

(defn- edge-hud-content []
  (let [n (count (edge-obs))
        f (edge-fit)
        here (:current-idx @stage)
        b (bench)
        sel (selected-ids)
        frag (.createDocumentFragment js/document)
        box (el "div" "eaq-hud-detail")
        add! (fn [cls txt] (.appendChild box (el "div" cls :text txt)))]
    (.appendChild frag (el "div" "eaq-hud-title" :text "BORDI MISURATI"))
    ;; The ANSWER to the last stroke, first and big. Everything else in this
    ;; panel is state; this is the one line that says whether the thing you just
    ;; did worked, and it goes where the hand already is instead of three panels
    ;; away in the console (Vincenzo asked for it three times before it landed).
    (when-let [oc (get-in @stage [:edge :outcome])]
      (.appendChild frag (el "div" (if (:ok? oc) "eaq-hud-good" "eaq-hud-bad")
                             :text (str (if (:ok? oc) "✓ TROVATO — " "✗ NIENTE QUI — ")
                                        (:text oc)))))
    (.appendChild frag (hud-step (if f :done :current) 1 "Misura un bordo (2 foto)"))
    (.appendChild frag (hud-step (cond (seq b) :done f :current :else :todo)
                                 2 (str "Tienilo sul banco — 'n'  (" (count b) ")")))
    (.appendChild frag (hud-step (if (seq sel) :current :todo)
                                 3 (str "Piano dai selezionati — 'p'  (" (count sel) ")")))
    (if-not (:in-pose? @stage)
      (add! "eaq-hud-hint"
            (str "Sei in vista libera: il banco resta. Per misurare altri bordi "
                 "torna dentro una foto — bottone Foto, o clicca una piramide."))
      (do
        (when-let [r (registration-label here)]
          (add! (case (registration-trouble here)
                  (:flipped :loose) "eaq-hud-bad" :grazing "eaq-hud-warn" nil)
                (str "Foto " (nav-rank here) " — " r)))
        (cond
          (get-in @stage [:edge :pending])
          (add! "eaq-hud-hint"
                "Primo capo preso (pallino giallo). Clicca il secondo più avanti lungo il bordo.")
          (contains? (edge-obs) here)
          (add! "eaq-hud-hint"
                (str "Su questa foto un bordo è già preso (chiaro)"
                     (when-let [oc (get-in @stage [:edge :outcome])]
                       (when-not (:ok? oc) " — l'ULTIMA pennellata però non ha preso niente"))
                     ". Cambia foto con ] e prendi LO STESSO tratto, oppure ridipingi qui."))
          :else
          (add! "eaq-hud-hint"
                (str "UN click su un bordo — oppure TRASCINA per dire 'cercalo qui "
                     "dentro', se il bordo prosegue in un altro e va tagliato. "
                     "Serve su 2 foto, guardato da lati diversi.")))))
    (when f
      (if (= :curva (:kind f))
        (add! (cond (not (:plausible? f)) "eaq-hud-bad"
                    (< (count (:points f)) pcurve/min-shared-points) "eaq-hud-bad"
                    (< (:agreement f) pcurve/min-agreement) "eaq-hud-bad"
                    :else "eaq-hud-good")
              (str "curva · " (count (:points f)) " punti IN COMUNE fra le due foto · ordine "
                   (src/fmt-number (* 100 (:agreement f))) "%"))
        (add! (cond (not (:plausible? f)) "eaq-hud-bad"
                    (< (:angle-deg f) pedge/min-plane-angle-deg) "eaq-hud-warn"
                    (> (:rms-px f) max-write-rms-px) "eaq-hud-warn"
                    :else "eaq-hud-good")
              (str "retta · " (src/fmt-number (:length-mm f)) " mm · scarto "
                   (src/fmt-number (:rms-px f)) " px · giro "
                   (src/fmt-number (:angle-deg f)) "°")))
      (when (and (= :curva (:kind f)) (< (:agreement f) pcurve/min-agreement))
        (add! "eaq-hud-bad"
              (str "Le due foto non stanno percorrendo lo stesso tratto: solo il "
                   (src/fmt-number (* 100 (:agreement f))) "% degli accoppiamenti "
                   "rispetta l'ordine del percorso.")))
      (when-let [ci (:circle f)]
        (add! "eaq-hud-hint"
              (str "È anche un cerchio di ⌀" (src/fmt-number (* 2 (:radius ci)))
                   " mm: 'c' scrive quello.")))
      (add! "eaq-hud-hint"
            "Quello che è disegnato sta nel MONDO: cambia foto con [ e ] — deve restare sul bordo."))
    ;; the bench itself, which is the thing being built
    (when (seq b)
      (add! nil "Banco (premi il numero per scegliere):")
      (let [shots (el "div" "eaq-hud-shots")]
        (doseq [feat b]
          (.appendChild shots
                        (el "div" "eaq-hud-shot"
                            :children [(el "span" nil
                                           :text (str (:label feat) ". "
                                                      (if (= :curva (:kind feat)) "curva" "retta")
                                                      (when (:length-mm feat)
                                                        (str " " (src/fmt-number (:length-mm feat)) "mm"))))
                                       (el "span" (when (sel (:id feat)) "eaq-hud-good")
                                           :text (if (sel (:id feat)) "scelto"
                                                     (str ":" (:name feat))))])))
        (.appendChild box shots)
        (add! "eaq-hud-hint"
              "Il numero di ogni bordo è scritto anche NELLA FOTO, accanto al bordo stesso."))
      (when (= 1 (count sel))
        (add! "eaq-hud-hint"
              (str "Con UN bordo solo il piano è debole: scegline un secondo, non "
                   "parallelo, sulla stessa faccia."))))
    ;; the planes already made, with the bordi each was made of — the record of
    ;; what has been decided, which the bench alone could not show
    (when-let [ps (seq (:source-marks @stage))]
      (add! nil (str "Piani nel sorgente (" (count ps) "):"))
      (let [rows (el "div" "eaq-hud-shots")]
        (doseq [[nm _] (sort-by key ps)]
          (.appendChild rows
                        (el "div" "eaq-hud-shot"
                            :children [(el "span" nil :text (str ":" (name nm)))
                                       (el "span" "eaq-hud-hint" :text "cancellabile nel testo")])))
        (.appendChild box rows)))
    (.appendChild frag box)
    (let [row (el "div" "eaq-hud-actions")]
      (.appendChild row (hud-button "Tieni" "Mette il bordo misurato sul banco (n)"
                                    (edge-usable?) (edge-usable?) keep-feature!))
      (.appendChild row (hud-button "Piano dai selezionati"
                                    "Scrive il mark-piano dai bordi scelti (p)"
                                    (boolean (seq sel)) (boolean (seq sel)) plane-from-bench!))
      (.appendChild row (hud-button "Scrivi spigolo"
                                    "Scrive il bordo dritto come spigolo misurato (Invio)"
                                    (boolean (and f (= :retta (:kind f)) (edge-usable?)))
                                    false accept-edge!))
      (.appendChild row (hud-button (if (labels-on?) "Numeri: sì" "Numeri: no")
                                    (str "Mostra o nasconde i numeri dei bordi NELLA "
                                         "FOTO (tasto l). Servono per scegliere; "
                                         "mentre dipingi stanno in mezzo.")
                                    true false toggle-labels!))
      (.appendChild row (hud-button (str "punta " (src/fmt-number (brush-px)) "px")
                                    (str "Spessore del pennarello — click per il "
                                         "prossimo (tasti + e -). E' in pixel dello "
                                         "SCHERMO: zoomando copre meno oggetto.")
                                    true false #(cycle-brush! 1)))
      (.appendChild row (hud-button "Ricomincia"
                                    "Butta via pennellata e bordo in mano, il banco resta (r)"
                                    (boolean (or (seq (edge-obs))
                                                 (get-in @stage [:edge :brush])))
                                    false reset-current!))
      (.appendChild row (hud-button "Annulla tratto" "Toglie il tratto di questa foto (Backspace)"
                                    (boolean (or (seq (edge-obs))
                                                 (get-in @stage [:edge :pending])))
                                    false undo-edge-click!))
      (.appendChild row (hud-button "Chiudi" "Esce dal modo bordi (Esc)" true false
                                    #(do (swap! stage dissoc :edge)
                                         (viewport/clear-labels!)
                                         (redraw-overlay!))))
      (.appendChild frag row))
    frag))

(defn- start-edge! []
  (swap! stage assoc :edge {:obs {} :selected #{} :committed []})
  (say! (str "modo bordi attivo. UN click su un bordo (dritto o curvo, lo capisce "
             "l'immagine), su DUE foto che lo guardino da lati diversi ([ e ] per "
             "cambiare): il bordo viene misurato e disegnato. 'n' lo TIENE sul "
             "SORGENTE, nel blocco :edges, dove resta visibile e numerato — e da "
             "dove si toglie cancellandone la riga, senza bisogno di un comando "
             "apposta. Quando ne hai due sulla stessa faccia, premi 'p': quello è "
             "il mark-piano, e due bordi non paralleli lo fissano esattamente. "
             "Invio scrive un bordo dritto come spigolo, 'c' un cerchio, Esc esce."))
  (redraw-overlay!))

(defn- stop-edge! []
  (swap! stage dissoc :edge)
  (viewport/clear-labels!)
  (say! "modo spigolo chiuso")
  (redraw-overlay!))

(defn- edge-key! [k]
  (cond
    (= k "Enter") (do (accept-edge!) true)
    (= k "n") (do (keep-feature!) true)
    (= k "p") (do (plane-from-bench!) true)
    (= k "c") (do (accept-circle!) true)
    (= k "r") (do (reset-current!) true)
    (= k "l") (do (toggle-labels!) true)
    (or (= k "+") (= k "=")) (do (cycle-brush! 1) true)
    (or (= k "-") (= k "_")) (do (cycle-brush! -1) true)
    (= k "Backspace") (do (undo-edge-click!) true)
    (= k "Escape") (do (stop-edge!) true)
    (re-matches #"[1-9]" k) (do (toggle-selected! (js/parseInt k 10)) true)
    :else false))

(defn- toggle-edge-mode! []
  (if (edge-mode?)
    (stop-edge!)
    (if (seq (:camera-poses @stage))
      (do (when (plane-mode?) (stop-plane!))   ; one gesture owns the clicks at a time
          (when-not (in-pose?)
            (let [order (nav-order)
                  cur (:current-idx @stage)]
              (when (seq order)
                (go-in-pose! (if (get-in @stage [:camera-poses cur]) cur (first order)) true))))
          (start-edge!))
      (say! "nessuna camera registrata: non c'è niente con cui misurare uno spigolo"))))

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
  (when-let [^js sp (.getElementById js/document "eaq-stage-edge")]
    (if (edge-mode?)
      (do (.add (.-classList sp) "active")
          (set! (.-textContent sp) (edge-status)))
      (do (.remove (.-classList sp) "active")
          (set! (.-textContent sp) "Spigolo"))))
  (refresh-hud!))

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
        (.appendChild wrap (make-tool-btn "eaq-stage-edge" "Spigolo"
                                          (str "Misura uno spigolo dell'oggetto: UN click su 2+ foto — "
                                               "NON serve ritrovare lo stesso punto, e direzione e "
                                               "lunghezza le legge dall'immagine — e ne esce un segmento "
                                               "3D scritto nel sorgente. Invio scrive.")
                                          toggle-edge-mode!))
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
    (swap! stage dissoc :plane :edge)
    (viewport/clear-labels!)
    (refresh-hud!)
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
  [state-focal dir first-file]
  (if state-focal
    (js/Promise.resolve state-focal)
    (-> (stl/desktop-read-file-blob (str dir "/" (:file first-file)))
        (.then (fn [^js blob] (.arrayBuffer blob)))
        (.then (fn [ab] (or (exif/focal-35mm-from-arraybuffer ab) default-focal-mm)))
        (.catch (fn [_] default-focal-mm)))))

(defn- load-one-session!
  "Read one session's files and return a Promise of
   {:photos [{:file :theta :dir :session :session-idx}] :camera-poses {i pose}
    :registration {i …} :focal-mm}, with the camera poses ALREADY in the fused
   frame: first reconciled from the acquisition frame to that session's emitted
   frame, then carried through the fusion's own rigid motion (nil for the
   reference session, which is already there)."
  [{:keys [dir label emit-pose transform]} session-idx]
  (-> (js/Promise.all
       #js [(stl/desktop-read-file (str dir "/session.json"))
            (-> (stl/desktop-read-file (str dir "/acquire-state.json"))
                (.catch (fn [_] nil)))]) ; no state yet → no cameras (no frustums)
      (.then (fn [^js results]
               (let [photos (mapv #(assoc % :dir dir :session label :session-idx session-idx)
                                  (parse-session-json (aget results 0)))
                     st (some-> (aget results 1) parse-acquire-state)
                     cams (reconcile-cameras (:camera-poses st) (:proxy-pose st) emit-pose)
                     cams (if transform
                            (into {} (map (fn [[i p]] [i (fuse/transform-pose transform p)]) cams))
                            cams)]
                 {:photos photos :camera-poses cams
                  :registration (:registration st) :focal-mm (:focal-mm st)})))
      (.catch (fn [err]
                (js/console.warn "acquire-stage: load failed" dir err)
                {:photos [] :camera-poses {} :registration {}}))))

(defn- load!
  "Async: read every fused session, transport its cameras into the fused frame,
   and lay the photos out as ONE film with global indices. Returns a Promise; on
   resolve the caller shows the frustums (post-refresh).

   Global indices are what let everything downstream stay as it was: frustums,
   `[`/`]`, the plane gesture's observations and the registration badges are all
   keyed by photo index, and they neither know nor care that the index now spans
   three folders."
  []
  (let [sessions (or (seq (:sessions @stage))
                     [{:dir (:dir @stage) :emit-pose (:emit-pose @stage)}])]
    (-> (js/Promise.all (into-array (map-indexed #(load-one-session! %2 %1) sessions)))
        (.then (fn [^js parts]
                 (let [parts (vec parts)
                       ;; offset each session's own 0-based photo indices into the
                       ;; global film
                       offsets (reductions + 0 (map #(count (:photos %)) parts))
                       shift (fn [m off] (into {} (map (fn [[i v]] [(+ i off) v]) m)))]
                   (swap! stage assoc
                          :photos (vec (mapcat :photos parts))
                          :camera-poses (apply merge (map #(shift (:camera-poses %1) %2) parts offsets))
                          :registration (apply merge (map #(shift (:registration %1) %2) parts offsets)))
                   (let [p0 (first (:photos @stage))]
                     (-> (resolve-focal! (:focal-mm (first parts)) (:dir p0) p0)
                         (.then (fn [focal]
                                  (swap! stage assoc :focal-mm focal :loaded? true))))))))
        (.catch (fn [err]
                  (js/console.warn "acquire-stage: load failed" err)
                  nil)))))

(defn note-eval!
  "Called by the `acquire` runtime fn DURING evaluation: record the acquire value so
   after-eval! (post refresh-viewport!) can (re)establish the stage. `acquire-value`
   is {:proxy <posed mesh> :pose <emit pose> :dir …}."
  [{:keys [proxy pose dir marks edges sessions]}]
  (let [emit (or pose (:creation-pose proxy))]
    (swap! stage
           (fn [s]
             (assoc (or s {})
                    :pending {:dir dir
                              :emit-pose emit
                              :dims (bridge/dims-from-mesh proxy (:creation-pose proxy))
                              :marks marks
                              :edges edges
                              ;; every session whose photos belong on the film. One
                              ;; entry for a lone (acquire …); one per fused session
                              ;; for an (acquire-union …), each with the rigid motion
                              ;; that carries its cameras into this frame.
                              :sessions (or sessions [{:dir dir :emit-pose emit}])
                              ;; a registration PLATE (it carries named marks)
                              ;; — its axis is a usable 'the object rests on
                              ;; this' normal, which unlocks the one-click
                              ;; plane-mark case. A box's heading is not.
                              :plate? (boolean (seq (:anchors proxy)))})))))

(defn after-eval!
  "Post-eval hook (mirrors modal/requested?→enter!): run AFTER refresh-viewport!.
   If an (acquire …) was noted this eval, (re)establish the stage without moving the
   camera; a Run that noted none tears the stage down."
  []
  (let [pending (:pending @stage)
        ;; what identifies 'the same stage' is now the whole SET of fused
        ;; sessions, not one folder: adding a session to an acquire-union has to
        ;; count as a change, or its photos never arrive.
        sig (fn [s] (mapv :dir (:sessions s)))]
    (cond
      ;; a fresh/changed acquire → (re)load, then show frustums (unless in pose)
      (and pending (not= (sig pending) (sig @stage)))
      (do (swap! stage merge {:dir (:dir pending)
                              :sessions (:sessions pending)
                              :emit-pose (:emit-pose pending)
                              :dims (:dims pending)
                              :plate? (:plate? pending)
                              :source-marks (:marks pending)
                              :source-edges (:edges pending)
                              :camera-poses {}
                              :photos []
                              :focal-mm default-focal-mm
                              :current-idx 0
                              :in-pose? false
                              :loaded? false
                              ;; a different acquire = different object: its
                              ;; half-finished plane picks and edge strokes mean
                              ;; nothing here
                              :plane nil
                              :edge nil
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
                              :source-edges (:edges pending)
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
