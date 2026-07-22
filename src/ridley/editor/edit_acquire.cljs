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
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.edge-snap :as edge-snap]
            [ridley.photogrammetry.match :as match]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.bootstrap :as boot]
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
;;  :acquire-results {idx {:picks :matched :rms-px}} — `s`'s edge-snap outcome
;;                                     per photo, feeding both the filmstrip's
;;                                     badges and acquire-state.json
;;  :panel-el nil
;;  :key-handler nil
;;  :status-message nil :status-msg-timer nil}

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

(defn- corner-marker-pos
  "World position of ONE specific corner of the box — always the same corner
   in the box's OWN local frame (+ex +ey +ez, bridge/box-basis's convention),
   regardless of how the proxy has since been rotated/re-snapped. A plain
   box is symmetric under 4 rotations (the Klein-twin ambiguity noted since
   the gate — dev-docs/HANDOVER-edit-acquire-gate.md), so neither the solver
   nor the eye can tell which virtual corner is which physical one from the
   wireframe alone; marking one corner gives the user something to actually
   compare against a physical reference on the real piece (a pen mark, a
   piece of tape) instead of judging the box's bare silhouette."
  []
  (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
        {:keys [ex ey ez]} (bridge/box-basis proxy-pose)
        [w h d] (bridge/dims-from-mesh (:proxy-mesh @session) proxy-pose)]
    (m/v+ (:position proxy-pose)
          (m/v+ (m/v* ex (* 0.5 w)) (m/v+ (m/v* ey (* 0.5 h)) (m/v* ez (* 0.5 d)))))))

(defn- proxy-preview-items
  "The proxy mesh plus two always-on-top marker dots — the pivot (box
   center, reported 2026-07-21 as a missing reference point) and one
   distinctly-colored corner (reported 2026-07-22, for judging which
   symmetry branch the box is in — see corner-marker-pos)."
  []
  [{:type :mesh :data (:proxy-mesh @session)}
   {:type :dots :data [{:pos (pivot) :radius 2.0 :color 0xffffff}
                       {:pos (corner-marker-pos) :radius 3.0 :color 0xff3333}]}])

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
;; Transient panel messages (edit-mesh-split's own pattern: capture-println
;; only surfaces at the end of a full eval cycle, never during a live modal
;; session, so a result the user must see NOW needs its own panel line)
;; ============================================================

(declare update-panel!)

(defn- set-status-message! [msg]
  (when-let [t (:status-msg-timer @session)] (js/clearTimeout t))
  (swap! session assoc
         :status-message msg
         :status-msg-timer (js/setTimeout
                            (fn []
                              (when @session
                                (swap! session assoc :status-message nil :status-msg-timer nil)
                                (update-panel!)))
                            4000))
  (update-panel!))

;; ============================================================
;; Edge-snap ('s'): projects the box's own edges into the current photo at
;; the gizmo-set pose, snaps each against the real photo pixels
;; (edge-snap/snap-segment), and refines the pose against the matches with
;; the same solver the CLI/gate use (photogrammetry.match, certified per
;; dev-docs/acquisizione-parametrica-design.md). One flow for every photo —
;; see on-snap!'s docstring for why photo 0 (proxy free) and 1..N-1 (camera
;; free) don't need separate code paths here.
;; ============================================================

(def min-matched-edges
  "A pose has six degrees of freedom; fewer matched edges under-constrains it
   and the 'refined' pose would be closer to noise than to the photo — reject
   instead of silently accepting nonsense (mirrors match/solve-photo's own
   min-edges default)."
  5)

(defn- current-camera-pose []
  (get-in @session [:camera-poses (:current-idx @session)]))

(defn- snap-all-edges
  "Project every visible box edge at `seed-pose` into photo-pixel space and
   try to snap each against the real photo (edge-snap/snap-segment). Returns
   {group [{:p1 :p2} ...]} of whichever edges snapped, bucketed by their
   known group (boot/group-of) — no ambiguity to resolve here, unlike a
   manual click: the edge index is already known from the projection."
  [dims intrinsics seed-pose]
  (->> (bf/visible-edges dims seed-pose)
       (keep (fn [k]
               (let [{:keys [corners]} (bf/edge-geometry dims k)
                     a (pcamera/project intrinsics seed-pose (first corners))
                     b (pcamera/project intrinsics seed-pose (second corners))]
                 (when (and a b)
                   (when-let [{:keys [p1 p2]} (edge-snap/snap-segment
                                               backdrop/luminance-at a b)]
                     [(boot/group-of k) {:p1 p1 :p2 p2}])))))
       (reduce (fn [acc [g pick]] (update acc g (fnil conj []) pick)) {})))

(declare save-acquire-state!)

(defn- on-snap!
  "Photo 0: the gizmo just moved the PROXY, camera fixed — apply the refined
   pose to the proxy (bridge/solver-pose->proxy), a rigid transform via
   attachment/group-transform (same mechanism translate/rotate buttons use,
   generalized to an arbitrary target pose instead of a small delta).
   Photos 1..N-1: the proxy is frozen, the gizmo moved the CAMERA — apply the
   refined pose there instead (bridge/solver-pose->camera), exactly like
   on-inv-commit!'s manual drag. editor->solver-pose itself doesn't care
   which side is free, so both branches share every step up to the solve."
  []
  (when-let [[iw ih] (backdrop/image-size)]
    (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
          camera-pose (current-camera-pose)
          dims (bridge/dims-from-mesh (:proxy-mesh @session) proxy-pose)
          intrinsics (pcamera/intrinsics-from-fov
                      (pcamera/focal-mm->fov-deg (:focal-mm @session)) iw ih)
          seed-pose (bridge/editor->solver-pose camera-pose proxy-pose)
          picks (snap-all-edges dims intrinsics seed-pose)
          result (match/refine-from-pose dims intrinsics picks seed-pose {})]
      (if (or (nil? result) (< (:matched result) min-matched-edges))
        (set-status-message!
         (str "Snap insufficiente (" (if result (:matched result) 0)
              " spigoli agganciati, ne servono almeno " min-matched-edges ")"))
        (let [idx (:current-idx @session)]
          (if (zero? idx)
            (let [new-proxy-pose (bridge/solver-pose->proxy (:pose result) camera-pose)
                  [new-mesh] (attachment/group-transform
                              [(:proxy-mesh @session)]
                              (:position proxy-pose) (:heading proxy-pose) (:up proxy-pose)
                              (:position new-proxy-pose) (:heading new-proxy-pose) (:up new-proxy-pose))]
              (swap! session assoc :proxy-mesh new-mesh)
              (viewport/show-preview! (proxy-preview-items))
              (gizmo/update-pose! (:creation-pose new-mesh)))
            (let [new-camera-pose (bridge/solver-pose->camera (:pose result) proxy-pose)]
              (swap! session assoc-in [:camera-poses idx] new-camera-pose)
              (viewport/set-camera-pose! new-camera-pose)))
          (swap! session assoc-in [:acquire-results idx]
                 {:picks picks :matched (:matched result) :rms-px (:rms-px result)})
          (set-status-message!
           (str (:matched result) " spigoli agganciati, residuo "
                (.toFixed (:rms-px result) 2) " px"))
          (save-acquire-state!)))))
  (update-panel!))

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
     ;; [0 0 1] is only a HINT here, not already perpendicular to view-dir
     ;; (dot ≈ 0.577) — fine for THREE's own camera.lookAt (which corrects
     ;; internally) but not for bridge.cljs's box-basis, which trusts
     ;; heading⊥up exactly; orthogonalize-up is what keeps this pose usable
     ;; as a rigid-transform basis instead of silently becoming a shear
     ;; (found live 2026-07-22: photo-0 edge-snap corrupting the box into a
     ;; non-rectangular shape).
     :up (m/orthogonalize-up view-dir [0 0 1])}))

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
      (swap! session update :camera-poses select-keys [0])
      ;; :acquire-results must drop in lockstep — otherwise a photo's badge
      ;; keeps showing its old "agganciata, N px" after the pose it was
      ;; computed against has just been discarded above, which reads as the
      ;; photo being fine right up until the user actually looks at it and
      ;; finds the pre-refinement seed again (reported 2026-07-22: nudging
      ;; photo 1's own position "reverted" photo 2 to how it looked before
      ;; ever fixing it — the pose really was reset here, only the badge
      ;; hadn't caught up).
      (swap! session update :acquire-results select-keys [0])))
  (viewport/show-preview! (proxy-preview-items))
  (gizmo/update-pose! (get-in @session [:proxy-mesh :creation-pose])))

;; ============================================================
;; Photos 1..N-1 — turntable pre-seed + inverted gizmo (moves the camera)
;; ============================================================

(defn- seed-camera-pose
  "photo 0's confirmed camera pose, orbited around the frozen proxy's OWN up
   axis (not a hardcoded world vertical — see below) through its pivot by
   this photo's turntable angle (session.json theta is already relative to
   photo 0, so no subtraction needed) — NEGATED: physically the OBJECT turns
   by +theta on the turntable while the real camera stays put; here the
   object is what's frozen, so reproducing the same photo means orbiting the
   CAMERA by -theta instead (object rotating by +θ with a fixed camera is
   the same image as the camera orbiting by -θ around a fixed object — same
   derivation as apply-inverted's rotation cases). A sign flip here doesn't
   break each photo's own drag interaction (still self-consistent — reported
   2026-07-21 as movements 'staying put' once released) but makes the
   pre-seeded STARTING pose land wrong before any dragging, worse the larger
   theta is — reported same day as the initial position on each photo
   looking 'random'.

   The axis used to be a hardcoded world [0 0 1] — reasonable as a first
   guess, but the whole point of aligning the proxy on photo 0 (whether by
   hand or via edge-snap) is that its up vector IS the turntable's true
   vertical once that alignment is trustworthy; a not-yet-visited photo's
   seed should benefit from a better photo-0 alignment, not ignore it
   (reported 2026-07-22: refining photo 0 via `s` 'didn't propagate' to
   later photos). Already-cached seeds (a photo the user has already
   visited/refined) are NOT retroactively recomputed by this — camera-pose-
   for's memoization deliberately treats a cached entry as the user's own,
   same as it always has."
  [idx]
  (let [base (get-in @session [:camera-poses 0])
        theta (:theta (nth (:photos @session) idx))
        axis (m/normalize (get-in @session [:proxy-mesh :creation-pose :up]))]
    (m/pose-around-axis base (pivot) axis (- (deg->rad theta)))))

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
        message (.createElement js/document "div")
        {:keys [row slider]} (ui/create-slider-row {:label "Focale (mm)"
                                                    :value (:focal-mm @session)
                                                    :range-fn focal-range
                                                    :on-input on-focal-change!})
        close-btn (.createElement js/document "button")]
    (set! (.-className header) "pilot-header")
    (set! (.-textContent header) "edit-acquire — gate ingegneristico")
    (.appendChild panel header)
    (set! (.-textContent hint)
          "1) sulla foto 1, tara la Focale finché il box sembra della taglia giusta, SENZA spostarlo — 2) poi trascina per posizione/rotazione — 3) 's' per agganciare il box agli spigoli reali della foto.")
    (.appendChild panel hint)
    (.appendChild panel row)
    (.appendChild panel filmstrip)
    (set! (.-className message) "ems-message")
    (.appendChild panel message)
    (set! (.-type close-btn) "button")
    (set! (.-textContent close-btn) "Chiudi")
    (.addEventListener close-btn "click" close!)
    (.appendChild panel close-btn)
    (swap! session assoc :panel-el panel :filmstrip-el filmstrip :focal-slider-el slider
           :message-el message)
    (modal/mount-panel! panel)
    (update-panel!)))

(defn- update-panel! []
  ;; Focale is a phase-0-only control (see build-panel!'s hint): the lens
  ;; doesn't change between photos, so re-tuning it later would silently
  ;; rescale a photo the user thinks is already locked in. Disabled, not
  ;; hidden, so it's clear it isn't gone, just not this photo's job.
  (when-let [^js slider (:focal-slider-el @session)]
    (set! (.-disabled slider) (not (zero? (:current-idx @session)))))
  (when-let [^js message (:message-el @session)]
    (set! (.-textContent message) (or (:status-message @session) "")))
  (when-let [strip (:filmstrip-el @session)]
    (set! (.-innerHTML strip) "")
    (doseq [[i {:keys [file]}] (map-indexed vector (:photos @session))]
      (let [btn (.createElement js/document "button")
            snapped (get-in @session [:acquire-results i])]
        (set! (.-type btn) "button")
        (set! (.-textContent btn) (if snapped
                                    (str (inc i) " · " (.toFixed (:rms-px snapped) 1) "px")
                                    (str (inc i))))
        (set! (.-title btn) file)
        (.add (.-classList btn) (if snapped "eaq-badge-ok" "eaq-badge-none"))
        (when (= i (:current-idx @session))
          (.add (.-classList btn) "current"))
        (.addEventListener btn "click" (fn [_] (enter-photo! i)))
        (.appendChild strip btn)))))

;; ============================================================
;; Keyboard: [ / ] to move through the filmstrip, 's' to edge-snap,
;; Escape to close
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

        (= key "s")
        (do (.preventDefault e) (.stopPropagation e) (on-snap!))

        (= key "]")
        (do (.preventDefault e) (.stopPropagation e)
            (enter-photo! (mod (inc idx) n)))))))

;; ============================================================
;; Session persistence — acquire-state.json, next to session.json but a
;; SEPARATE file: session.json is written/read by the CLI (cli.cljs's
;; init-session/run-matched) with no merge mechanism of its own, so sharing
;; one file would risk the editor and the CLI clobbering each other.
;; ============================================================

(defn- acquire-state-path []
  (let [base (:base-dir @session)]
    (str base (if (str/ends-with? base "/") "" "/") "acquire-state.json")))

;; Chained rather than fire-and-forget: pressing 's' on several photos in
;; quick succession fires one desktop-write-file per snap, and those are
;; independent async requests with no ordering guarantee — the write started
;; EARLIER can complete LATER and overwrite a more-complete later snapshot
;; with a stale one (reported 2026-07-22: saved camera poses coming back
;; null/missing for several photos). Chaining onto the previous save's
;; promise forces every write to land in the order it was issued, so the
;; last one issued is also the last (and therefore winning) one on disk.
(defonce ^:private save-chain (atom (js/Promise.resolve)))

(defn- save-acquire-state! []
  (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
        photos (into {}
                     (map (fn [[idx {:keys [matched rms-px]}]]
                            [(str idx)
                             (cond-> {:matched matched :rms-px rms-px}
                               (pos? idx) (assoc :camera-pose (get-in @session [:camera-poses idx])))])
                          (:acquire-results @session)))
        body (js/JSON.stringify (clj->js {:proxy-pose proxy-pose :photos photos}))
        path (acquire-state-path)]
    (swap! save-chain
           (fn [prev]
             (-> prev
                 (.catch (fn [_] nil)) ;; a prior failed write must not wedge the chain
                 (.then (fn [_] (stl/desktop-write-file body path)))
                 (.catch (fn [err]
                           (js/console.warn "edit-acquire: couldn't save acquire-state.json" err))))))))

(defn- apply-loaded-state! [text]
  (try
    (let [{:keys [proxy-pose photos]} (js->clj (js/JSON.parse text) :keywordize-keys true)]
      (when proxy-pose
        (let [old-pose (get-in @session [:proxy-mesh :creation-pose])
              ;; Defensive, not redundant: a file saved before the
              ;; orthogonalize-up fix (or corrupted some other way) can hold
              ;; a heading/up pair that isn't perpendicular — group-transform
              ;; trusts its target basis exactly, so an uncorrected load
              ;; reproduces the shear immediately on session entry, before
              ;; any snap this session (reported 2026-07-22: "sghembo appena
              ;; partito" — traced to exactly this file, not a new bug).
              safe-heading (m/normalize (:heading proxy-pose))
              safe-up (m/orthogonalize-up safe-heading (:up proxy-pose))
              [new-mesh] (attachment/group-transform
                          [(:proxy-mesh @session)]
                          (:position old-pose) (:heading old-pose) (:up old-pose)
                          (:position proxy-pose) safe-heading safe-up)]
          (swap! session assoc :proxy-mesh new-mesh)))
      (doseq [[idx-kw {:keys [camera-pose matched rms-px]}] photos]
        (let [idx (js/parseInt (name idx-kw))]
          (cond
            (zero? idx)
            (swap! session assoc-in [:acquire-results idx] {:matched matched :rms-px rms-px})

            camera-pose
            (do (swap! session assoc-in [:camera-poses idx] camera-pose)
                (swap! session assoc-in [:acquire-results idx] {:matched matched :rms-px rms-px}))

            ;; idx > 0 with no camera-pose: this photo's entry is incomplete
            ;; (a save that raced with another and lost — see save-acquire-
            ;; state!'s chained-write fix). A badge with a residual number
            ;; but no pose behind it would be lying about being registered —
            ;; treat it as never snapped instead.
            :else nil))))
    (catch :default err
      (js/console.warn "edit-acquire: couldn't parse acquire-state.json" err))))

(defn- load-acquire-state! []
  (-> (stl/desktop-read-file (acquire-state-path))
      (.then apply-loaded-state!)
      (.catch (fn [_] nil)))) ;; no file yet (first snap of a fresh session) — fine

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
                                  :acquire-results {}
                                  :focal-mm default-focal-mm
                                  :panel-el nil
                                  :filmstrip-el nil
                                  :message-el nil
                                  :status-message nil
                                  :status-msg-timer nil
                                  :key-handler nil})
                 ;; Restores a previously-snapped proxy pose/camera poses/
                 ;; badges before anything renders, if acquire-state.json
                 ;; exists — a no-op (resolves anyway) on a fresh session.
                 (-> (load-acquire-state!)
                     (.then (fn [_]
                              (viewport/show-preview! (proxy-preview-items))
                              (backdrop/create! (viewport/get-camera))
                              (build-panel!)
                              (swap! session assoc :key-handler (modal/install-keydown! on-keydown))
                              (enter-photo! 0)))))))
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
