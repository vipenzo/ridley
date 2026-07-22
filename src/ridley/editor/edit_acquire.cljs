(ns ridley.editor.edit-acquire
  "Gate ingegneristico prototype for `dev-docs/brief-param-acq-v1.md`: judges
   whether 'inverted manipulation' feels ergonomic before the rest of the
   acquisizione-parametrica feature is built. `(edit-acquire proxy-mesh
   session-dir)` opens a modal session (mutex/panel/keydown shared with the
   rest of the edit-* family via modal-evaluator) over a turntable photo
   sequence:

   - Photo 0: the gizmo moves `proxy-mesh` normally (:nudge-mesh? true) — the
     user aligns it to the photo, fixing the proxy's canonical world pose.
   - Photos 1..N-1: the proxy pose is FROZEN. The camera pose is pre-seeded by
     orbiting photo 0's camera pose around a vertical axis through the
     proxy's creation-pose position by the photo's turntable angle (from
     session.json). The gizmo still sits on the (frozen) proxy and still
     nudges its PREVIEW live during the drag (:nudge-mesh? true, same as photo
     0 — see on-inv-commit!'s docstring for why the camera itself is only
     touched once, on commit, not live) — narrated to the user as 'rotate the
     piece like it was in that photo'; once the drag resolves, the preview
     nudge is discarded and the equivalent INVERSE transform is applied to the
     camera pose instead, so what actually changed is the camera.

   Deliberately NOT a macro: no source-buffer marker, no commit-to-source —
   there's no canonical primitive to emit yet (later feature work). Closing
   the session (Escape / the panel's Chiudi button) discards everything.

   session-dir must be an ABSOLUTE path to a folder holding session.json (see
   test-assets/param-acq-box-tape/) — read via the Rust geo_server
   (ridley.export.stl), so that process (e.g. `cargo tauri dev` running in
   the background) must be up even when the app itself is open in Chrome for
   REPL/hot-reload."
  (:require [clojure.string :as str]
            [ridley.editor.modal-evaluator :as modal]
            [ridley.editor.gizmo :as gizmo]
            [ridley.editor.acquire-backdrop :as backdrop]
            [ridley.editor.state :as state]
            [ridley.editor.ui :as ui]
            [ridley.viewport.core :as viewport]
            [ridley.turtle.attachment :as attachment]
            [ridley.photogrammetry.camera :as pcamera]
            [ridley.math :as m]
            [ridley.export.stl :as stl]))

;; ============================================================
;; Session state
;; ============================================================

(defonce ^:private session (atom nil))
;; {:photos [{:file :theta} ...]     — from session.json, theta in DEGREES
;;  :base-dir "/abs/path"
;;  :current-idx 0
;;  :proxy-mesh mesh-data            — caller's mesh; :creation-pose is the
;;                                     single source of truth for its pose
;;  :camera-poses {idx pose}         — memoized per-photo; idx 0 = the fixed
;;                                     default vantage set at entry
;;  :focal-mm 48.0                   — user-entered, session-wide (one lens);
;;                                     converted to horizontal FOV via
;;                                     photogrammetry.camera/focal-mm->fov-deg
;;                                     wherever it feeds the backdrop/camera
;;  :panel-el nil
;;  :key-handler nil}

(def default-focal-mm
  "Starting focal-length guess, 35mm-equivalent — iPhone 15 Pro Max 2x crop,
   per test-assets/param-acq-box-tape/NOTE.md (48mm-eq, matching the nominal
   value already established for this camera/lens in dev-docs/HANDOVER-
   acquisizione-parametrica.md). User-editable in the panel."
  48.0)

(defn- deg->rad [d] (/ (* d Math/PI) 180))

;; ============================================================
;; Small local helpers (deliberately not reaching into gizmo's privates —
;; same choice edit-mesh-split makes elsewhere)
;; ============================================================

(defn- pose-basis
  "{:h :r :u} orthonormal world-space basis for a turtle pose — right = heading
   × up, matching gizmo.cljs's own convention exactly (must, or drags feel
   mirrored)."
  [pose]
  (let [h (m/normalize (:heading pose))
        u (m/normalize (:up pose))
        r (m/normalize (m/cross h u))]
    {:h h :r r :u u}))

(defn- pivot []
  (get-in @session [:proxy-mesh :creation-pose :position]))

(defn- proxy-preview-items
  "The proxy mesh plus a small always-on-top marker dot at its pivot — the
   gizmo's own arrows/rings converge there too, but they collapse to a point
   and get visually lost among the photo/box edges; a dedicated marker (drawn
   with depthTest false, so it reads even from inside/behind the box) answers
   'where is the box's center relative to the photo' at a glance (reported
   2026-07-21 as a missing reference point)."
  []
  [{:type :mesh :data (:proxy-mesh @session)}
   {:type :dots :data [{:pos (pivot) :radius 2.0 :color 0xffffff}]}])

(defn- photo-path [file]
  (let [base (:base-dir @session)]
    (str base (if (str/ends-with? base "/") "" "/") file)))

(defn- set-photo-for-current-focal! [file]
  (backdrop/set-photo! (photo-path file)
                       (pcamera/focal-mm->fov-deg (:focal-mm @session))
                       viewport/set-camera-fov!))

(defn- parse-session-json [text]
  (let [obj (js/JSON.parse text)]
    {:photos (mapv (fn [[file theta]] {:file file :theta theta})
                   (js->clj (.-photos obj)))}))

;; ============================================================
;; Photo 0 — normal gizmo (moves the proxy)
;; ============================================================

(defn- default-vantage-pose
  "A vantage at backdrop/default-depth from `piv`, looking along a FIXED
   world-space diagonal [1 -1 1] — NOT the proxy's own h/r/u basis, even
   though the two happen to coincide for a fresh (box ...)'s default
   orientation (h=[1 0 0] r=[0 -1 0] u=[0 0 1], so h+r+u = [1 -1 1]).
   Deriving the view direction from the proxy's basis seemed natural (it's
   what avoids a straight-down-one-axis view — edge-on to two of the gizmo's
   three rotation rings, collapsing them to line segments — reported
   2026-07-21) but made this pose change every time the box was ROTATED, not
   just moved: camera-poses[0] then depended on exactly when it was last
   recomputed relative to a moving target, so returning to photo 0 — or
   seeding a later photo — after further edits landed on a DIFFERENT framing
   than the one just aligned against (reported 2026-07-21: 'completamente
   diversa da come l'ho lasciata... così tutte le altre'). A fixed direction
   only reframes with the pivot's POSITION (predictable — the box translating
   should recenter the view) and never drifts from rotation."
  [piv]
  (let [view-dir (m/normalize [1 -1 1])]
    {:position (m/v- piv (m/v* view-dir backdrop/default-depth))
     :heading view-dir
     :up [0 0 1]}))

(defn- on-photo0-commit! [cmd-type value]
  (let [old-pivot (pivot)
        {:keys [h r u]} (pose-basis (get-in @session [:proxy-mesh :creation-pose]))]
    (swap! session update :proxy-mesh
           (fn [mesh]
             (case cmd-type
               :f (attachment/translate-mesh mesh (m/v* h value))
               :rt (attachment/translate-mesh mesh (m/v* r value))
               :u (attachment/translate-mesh mesh (m/v* u value))
               :th (attachment/rotate-mesh mesh u (deg->rad value))
               :tv (attachment/rotate-mesh mesh r (deg->rad value))
               :tr (attachment/rotate-mesh mesh h (deg->rad value)))))
    ;; camera-poses[0] itself is NEVER touched here (see enter-photo!'s
    ;; comment — it's set once, at the session's first entry into photo 0,
    ;; and frozen for the rest of the session). An earlier version
    ;; recomputed it on every commit to track the box's current pose, which
    ;; sounded right but made photo 0's own framing shift a little on every
    ;; edit and re-entry — reported 2026-07-21 twice, first as "completamente
    ;; diversa", then — after that recompute was made less eager — still "un
    ;; po' spostata": recomputing AT ALL, on any timing, is what's unstable;
    ;; not recomputing is what's stable.
    ;;
    ;; Only a TRANSLATE (:f/:rt/:u) moves the pivot — a pure rotation leaves
    ;; :creation-pose's position untouched (rotate-mesh pivots around it), so
    ;; every other photo's seed (seed-camera-pose orbits camera-poses[0]
    ;; around THIS pivot) is still exactly as valid as before. Previously
    ;; every commit — rotation included — dropped all of it, discarding
    ;; refinement work on photos the user hadn't touched again (reported
    ;; 2026-07-21: "tutte le altre si scombinano"). Only drop it when the
    ;; pivot actually moved.
    (when-not (= old-pivot (pivot))
      (swap! session update :camera-poses select-keys [0])))
  (viewport/show-preview! (proxy-preview-items))
  (gizmo/update-pose! (get-in @session [:proxy-mesh :creation-pose])))

;; ============================================================
;; Photos 1..N-1 — turntable pre-seed + inverted gizmo (moves the camera)
;; ============================================================

(defn- seed-camera-pose
  "photo 0's confirmed camera pose, orbited around the WORLD vertical axis
   through the frozen proxy's pivot by this photo's turntable angle (session.json
   theta is already relative to photo 0, so no subtraction needed) — NEGATED:
   physically the OBJECT turns by +theta on the turntable while the real
   camera stays put; here the object is what's frozen, so reproducing the
   same photo means orbiting the CAMERA by -theta instead (object rotating by
   +θ with a fixed camera is the same image as the camera orbiting by -θ
   around a fixed object — same derivation as apply-inverted's rotation
   cases). A sign flip here doesn't break each photo's own drag interaction
   (still self-consistent — reported 2026-07-21 as movements 'staying put'
   once released) but makes the pre-seeded STARTING pose land wrong before
   any dragging, worse the larger theta is — reported same day as the initial
   position on each photo looking 'random'."
  [idx]
  (let [base (get-in @session [:camera-poses 0])
        theta (:theta (nth (:photos @session) idx))]
    (m/pose-around-axis base (pivot) [0 0 1] (- (deg->rad theta)))))

(defn- camera-pose-for
  "Memoized: first visit caches the turntable pre-seed, later visits return
   whatever the user last refined it to (survives filmstrip navigation).
   Invalidated for every idx but 0 whenever the proxy's PIVOT moves
   (on-photo0-commit!, translate only — see there), since the pre-seed and
   any refinement are only valid relative to the pivot they were computed
   against."
  [idx]
  (or (get-in @session [:camera-poses idx])
      (let [p (seed-camera-pose idx)]
        (swap! session assoc-in [:camera-poses idx] p)
        p)))

(defn- apply-inverted
  "The camera pose that results from applying cmd-type/value — exactly what a
   NORMAL gizmo drag would do to the proxy — INVERTED onto the camera instead:
   negated translation, negated rotation about the same pivot/axis the proxy
   would have rotated about. `base` is this photo's last-committed camera pose
   (camera-pose-for) — nothing touches the camera mid-drag (see on-inv-commit!'s
   docstring for why), so it's always current when this runs."
  [base cmd-type value]
  (let [{:keys [h r u]} (pose-basis (get-in @session [:proxy-mesh :creation-pose]))
        piv (pivot)]
    (case cmd-type
      :f (update base :position #(m/v- % (m/v* h value)))
      :rt (update base :position #(m/v- % (m/v* r value)))
      :u (update base :position #(m/v- % (m/v* u value)))
      :th (m/pose-around-axis base piv u (- (deg->rad value)))
      :tv (m/pose-around-axis base piv r (- (deg->rad value)))
      :tr (m/pose-around-axis base piv h (- (deg->rad value))))))

(defn- on-inv-commit! [cmd-type value]
  ;; nudge-mesh? stays at its default true (see enter-photo!) so the LIVE drag
  ;; visually rotates/translates the box preview exactly like photo 0 — via
  ;; viewport/nudge-preview!, which never touches the camera. A first version
  ;; instead called viewport/set-camera-pose! from :on-drag on every pointer-
  ;; move, live: gizmo's own hit-testing re-projects the mouse through the
  ;; CURRENT camera every frame (viewport/raycast-line-point et al), so moving
  ;; the camera mid-drag fed back into the very ray the next frame's drag value
  ;; was computed from — a closed loop that made drags diverge unpredictably
  ;; (reported 2026-07-21: "lo snap muove l'oggetto in posizioni che sembrano
  ;; casuali"). Applying the inverse ONCE here, after the drag has fully
  ;; resolved against an unmoving camera, breaks the loop.
  (let [p (apply-inverted (camera-pose-for (:current-idx @session)) cmd-type value)]
    (swap! session assoc-in [:camera-poses (:current-idx @session)] p)
    ;; Discard the live nudge (the proxy mesh itself was never touched) before
    ;; applying the equivalent camera move, so the two never fight visually.
    (viewport/show-preview! (proxy-preview-items))
    (viewport/set-camera-pose! p)
    ;; The widget's own live rotation/translation (also just a preview effect)
    ;; needs the same reset, back onto the frozen proxy pose.
    (gizmo/update-pose! (get-in @session [:proxy-mesh :creation-pose]))))

;; ============================================================
;; Filmstrip navigation
;; ============================================================

(declare update-panel!)

(defn- enter-photo!
  "Close/reopen the gizmo for photo `idx` — simpler to reason about than
   special-casing the 0↔1+ boundary, since :nudge-mesh? can only be set at
   gizmo/enter! time, there's no mutator for it."
  [idx]
  (gizmo/close!)
  (swap! session assoc :current-idx idx)
  (let [{:keys [file]} (nth (:photos @session) idx)]
    (if (zero? idx)
      ;; Computed ONCE, on the very first entry, and frozen for the rest of
      ;; the session from then on (never recomputed on later re-entries or
      ;; commits — see on-photo0-commit!'s comment for why recomputing AT ALL
      ;; is what made this unstable).
      (let [start-pose (or (get-in @session [:camera-poses 0])
                           (default-vantage-pose (pivot)))]
        (swap! session assoc-in [:camera-poses 0] start-pose)
        (viewport/set-camera-pose! start-pose)
        (set-photo-for-current-focal! file)
        (gizmo/enter! (get-in @session [:proxy-mesh :creation-pose])
                      {:mode :object :handles #{:translate :rotate}}
                      {:on-commit on-photo0-commit!}))
      (let [pose (camera-pose-for idx)]
        (viewport/set-camera-pose! pose)
        (set-photo-for-current-focal! file)
        ;; :nudge-mesh? stays default-true (unlike a first version — see
        ;; on-inv-commit!'s docstring): the live drag nudges the PREVIEW only,
        ;; same mechanism photo 0 uses, so it never touches the camera/
        ;; raycasting mid-gesture.
        (gizmo/enter! (get-in @session [:proxy-mesh :creation-pose])
                      {:mode :object :handles #{:translate :rotate}}
                      {:on-commit on-inv-commit!}))))
  (update-panel!))

;; ============================================================
;; Panel (numbered filmstrip + focal-length field + Chiudi — no badges/
;; residuals, those need the solver integration, out of scope here)
;; ============================================================

(declare close!)

(defn- focal-range [_] [20 135 1])

(defn- on-focal-change!
  "Live: reapplies to whichever photo is already showing (backdrop/set-hfov!
   is a no-op before the first photo has loaded), so dragging the slider
   re-scales the proxy against the CURRENT photo with position/rotation
   untouched — the size-then-pose split from Vincenzo's 2026-07-21 feedback:
   get the apparent scale right first, on photo 0, before touching the gizmo."
  [focal-mm]
  (swap! session assoc :focal-mm focal-mm)
  (backdrop/set-hfov! (pcamera/focal-mm->fov-deg focal-mm) viewport/set-camera-fov!))

(defn- build-panel! []
  (let [panel (.createElement js/document "div")
        header (.createElement js/document "div")
        hint (.createElement js/document "div")
        filmstrip (.createElement js/document "div")
        {:keys [row slider]} (ui/create-slider-row {:label "Focale (mm)"
                                                    :value (:focal-mm @session)
                                                    :range-fn focal-range
                                                    :on-input on-focal-change!})
        close-btn (.createElement js/document "button")]
    (set! (.-className header) "pilot-header")
    (set! (.-textContent header) "edit-acquire — gate ingegneristico")
    (.appendChild panel header)
    (set! (.-textContent hint)
          "1) sulla foto 1, tara la Focale finché il box sembra della taglia giusta, SENZA spostarlo — 2) poi trascina per posizione/rotazione.")
    (.appendChild panel hint)
    (.appendChild panel row)
    (.appendChild panel filmstrip)
    (set! (.-type close-btn) "button")
    (set! (.-textContent close-btn) "Chiudi")
    (.addEventListener close-btn "click" close!)
    (.appendChild panel close-btn)
    (swap! session assoc :panel-el panel :filmstrip-el filmstrip :focal-slider-el slider)
    (modal/mount-panel! panel)
    (update-panel!)))

(defn- update-panel! []
  ;; Focale is a phase-0-only control (see build-panel!'s hint): the lens
  ;; doesn't change between photos, so re-tuning it later would silently
  ;; rescale a photo the user thinks is already locked in. Disabled, not
  ;; hidden, so it's clear it isn't gone, just not this photo's job.
  (when-let [^js slider (:focal-slider-el @session)]
    (set! (.-disabled slider) (not (zero? (:current-idx @session)))))
  (when-let [strip (:filmstrip-el @session)]
    (set! (.-innerHTML strip) "")
    (doseq [[i {:keys [file]}] (map-indexed vector (:photos @session))]
      (let [btn (.createElement js/document "button")]
        (set! (.-type btn) "button")
        (set! (.-textContent btn) (str (inc i)))
        (set! (.-title btn) file)
        (when (= i (:current-idx @session))
          (.add (.-classList btn) "current"))
        (.addEventListener btn "click" (fn [_] (enter-photo! i)))
        (.appendChild strip btn)))))

;; ============================================================
;; Keyboard: [ / ] to move through the filmstrip, Escape to close
;; ============================================================

(defn- on-keydown [^js e]
  (when @session
    (let [key (.-key e)
          n (count (:photos @session))
          idx (:current-idx @session)]
      (cond
        (= key "Escape")
        (do (.preventDefault e) (.stopPropagation e) (close!))

        (= key "[")
        (do (.preventDefault e) (.stopPropagation e)
            (enter-photo! (mod (dec idx) n)))

        (= key "]")
        (do (.preventDefault e) (.stopPropagation e)
            (enter-photo! (mod (inc idx) n)))))))

;; ============================================================
;; Entry / exit
;; ============================================================

(defn- close! []
  (when @session
    (viewport/unregister-frame-callback! :edit-acquire)
    (gizmo/close!)
    (backdrop/clear!)
    (viewport/clear-preview!)
    (viewport/show-user-geometry!)
    (viewport/enable-orbit-controls!)
    (modal/unmount-panel! (:panel-el @session))
    (modal/remove-keydown! (:key-handler @session))
    (reset! session nil)
    (modal/release!)))

(defn ^:export enter!
  "SCI symbol `edit-acquire`. See namespace docstring for the interaction
   design and session-dir's requirements."
  [proxy-mesh session-dir]
  (modal/claim! :edit-acquire)
  (-> (stl/desktop-read-file (str session-dir "/session.json"))
      (.then (fn [text]
               (let [{:keys [photos]} (parse-session-json text)]
                 (viewport/hide-user-geometry!)
                 ;; The camera must stay LOCKED for the whole session — but
                 ;; gizmo's own on-pointer-up unconditionally re-enables the
                 ;; viewport's TrackballControls at the end of every drag (the
                 ;; right call for normal editing, wrong here). Once re-
                 ;; enabled, the render loop's next frame calls the controls'
                 ;; own .update(), which repositions the camera from ITS
                 ;; internal tracked state — discarding whatever
                 ;; set-camera-pose! just did (reported 2026-07-21: translate
                 ;; drags "always reverting", rotate drags behaving
                 ;; unpredictably — .update() doesn't override every part of
                 ;; the transform the same way). Forcing controls disabled
                 ;; every frame, for as long as this session is open, keeps
                 ;; render-frame's `(when (.-enabled controls) (.update
                 ;; controls))` from ever firing (viewport/core.cljs ~1251),
                 ;; regardless of what gizmo does synchronously in between.
                 (viewport/register-frame-callback!
                  :edit-acquire (fn [_camera] (viewport/set-controls-enabled! false)))
                 (reset! session {:photos photos
                                  :base-dir session-dir
                                  :current-idx 0
                                  :proxy-mesh proxy-mesh
                                  :camera-poses {}
                                  :focal-mm default-focal-mm
                                  :panel-el nil
                                  :filmstrip-el nil
                                  :key-handler nil})
                 (viewport/show-preview! (proxy-preview-items))
                 (backdrop/create! (viewport/get-camera))
                 (build-panel!)
                 (swap! session assoc :key-handler (modal/install-keydown! on-keydown))
                 (enter-photo! 0))))
      (.catch (fn [err]
                (state/capture-println (str "edit-acquire: couldn't load session — " err))
                (modal/release!)))))

;; Synchronous modal (tweak_mode.cljs's own precedent): opens inside its own
;; enter!, so requested? is always false and the generic post-eval driver in
;; core.cljs never touches it — active?/cancel!/close! let it still be polled
;; and force-closed generically (e.g. before a Run/Cmd+Enter re-eval).
(modal/register-kind! :edit-acquire
                      {:requested? (constantly false)
                       :enter! (fn [])
                       :active? #(some? @session)
                       :cancel! close!
                       :close! close!})
