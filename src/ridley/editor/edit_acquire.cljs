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

   P4a-1 round-trip: `edit-acquire` is now also a buffer MARKER in the edit-*
   family. `(edit-acquire \"dir\")` opened from the definitions panel (request!)
   runs the same session; Conferma rewrites the marker to the self-contained
   `(acquire \"dir\" {:proxy (box …) :pose {…} :shapes {} :marks {}})` directive
   (confirm! → emit-acquire-code) and Chiudi/Esc strip-heads it to `(acquire …)`
   (cancel!). Re-opening reads proxy+pose back from that form; the fotografia
   (camera poses, observations, planes) stays in acquire-state.json. The legacy
   REPL entry `(edit-acquire proxy-mesh \"dir\")` (enter!) is kept in parallel
   through the transition — the macro dispatches on whether the first arg is the
   dir string (marker) or a proxy form (open). :shapes/:marks are empty in
   P4a-1 (retrace/marks land in P4a-3).

   session-dir must be an ABSOLUTE path to a folder holding session.json (see
   test-assets/param-acq-box-tape/) — read via the Rust geo_server
   (ridley.export.stl), so that process (e.g. `cargo tauri dev` running in
   the background) must be up even when the app itself is open in Chrome for
   REPL/hot-reload."
  (:require [clojure.string :as str]
            [ridley.editor.modal-evaluator :as modal]
            [ridley.editor.codemirror :as cm]
            [ridley.editor.gizmo :as gizmo]
            [ridley.editor.acquire-backdrop :as backdrop]
            [ridley.editor.state :as state]
            [ridley.editor.ui :as ui]
            [ridley.geometry.primitives :as prims]
            [ridley.viewport.core :as viewport]
            [ridley.turtle.attachment :as attachment]
            [ridley.photogrammetry.camera :as pcamera]
            [ridley.photogrammetry.exif :as exif]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.edge-snap :as edge-snap]
            [ridley.photogrammetry.pnp :as pnp]
            [ridley.photogrammetry.match :as match]
            [ridley.photogrammetry.turntable-fit :as tt]
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
;;  :focal-mm 48.0                   — 35mm-equiv focal, session-wide (one lens);
;;                                     read from photo 0's EXIF at entry, else
;;                                     the default; converted to horizontal FOV
;;                                     via photogrammetry.camera/equiv-focal->
;;                                     hfov-deg (diagonal convention + the
;;                                     photo's aspect) wherever it feeds the
;;                                     backdrop/camera/intrinsics
;;  :focal-source :exif|:manual|:default — provenance of :focal-mm, for the
;;                                     panel's honest label
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

(def ^:private marker-prefix
  "Buffer head of the edit-acquire marker form — located via
   modal/find-form-bounds for the confirm!/cancel! source rewrite (P4a-1)."
  "(edit-acquire")

(def ^:private default-proxy-dims
  "Starting box [w h d] for a bare (acquire \"dir\") / (edit-acquire \"dir\") with
   no explicit :proxy — distinct sides so its orientation reads while aligning."
  [40 30 20])

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

(declare trace-items mark-world-positions)

(def ^:private mark-color 0xff33cc)  ; magenta — placed marks, distinct from the retrace yellow

(defn- mark-dots-item
  "Placed named marks as always-on-top magenta dots — shared by every mode's
   preview so a mark stays visible after leaving :mark (gizmo/navigation view),
   the way the retrace trace does. Empty when no marks are placed."
  []
  {:type :dots :data (mapv (fn [w] {:pos w :radius 1.3 :color mark-color :opacity 0.95})
                           (mark-world-positions))})

(defn- proxy-preview-items
  "The proxy mesh plus two always-on-top marker dots — the pivot (box
   center, reported 2026-07-21 as a missing reference point) and one
   distinctly-colored corner (reported 2026-07-22, for judging which
   symmetry branch the box is in — see corner-marker-pos). The SOLID proxy is
   under the 'v' / 'Nascondi proxy' toggle (Vincenzo 2026-07-23): while
   registering it covers the photo, so it can be dropped to read the photo
   underneath. The markers stay — they locate the (hidden) box and are tiny.
   Any retrace trace + placed marks are appended, so the bezel and the named
   points stay visible after leaving :retrace/:mark (Vincenzo 2026-07-23/-24)."
  []
  (let [markers {:type :dots :data [{:pos (pivot) :radius 2.0 :color 0xffffff}
                                    {:pos (corner-marker-pos) :radius 3.0 :color 0xff3333}]}]
    (conj (into (if (:hide-proxy? @session)
                  [markers]
                  [{:type :mesh :data (:proxy-mesh @session)} markers])
                (trace-items))
          (mark-dots-item))))

(defn- photo-path [file]
  (let [base (:base-dir @session)]
    (str base (if (str/ends-with? base "/") "" "/") file)))

(defn- set-photo-for-current-focal! [file]
  (backdrop/set-photo! (photo-path file)
                       (:focal-mm @session)
                       viewport/set-camera-fov!))

(defn- session-intrinsics
  "Pinhole intrinsics for the session's lens at the current photo's pixel size.
   Converts the 35mm-equivalent focal to a HORIZONTAL FOV against the photo's
   own aspect ratio (pcamera/equiv-focal->hfov-deg — the diagonal convention,
   not the 36mm-width one), so the projected box lands at the photo's true
   scale. iw/ih come from the loaded photo (backdrop/image-size)."
  [iw ih]
  (pcamera/intrinsics-from-fov
   (pcamera/equiv-focal->hfov-deg (:focal-mm @session) (/ iw ih)) iw ih))

(defn- parse-session-json [text]
  (let [obj (js/JSON.parse text)]
    {:photos (mapv (fn [[file theta]] {:file file :theta theta})
                   (js->clj (.-photos obj)))}))

(declare set-status-message!)

(defn- load-exif-focal!
  "Read the 35mm-equivalent focal length from photo 0's EXIF and adopt it as
   the session focal (source :exif). The lens is the same for every photo in a
   turntable session, so one read is enough. Silently keeps the default +
   manual slider (source stays :default) when the tag is absent or unreadable
   — a missing/odd EXIF must never block the session. Returns a Promise so the
   caller can order the first photo load AFTER the focal is known."
  [first-file]
  (-> (stl/desktop-read-file-blob (photo-path (:file first-file)))
      (.then (fn [^js blob] (.arrayBuffer blob)))
      (.then (fn [ab]
               (when-let [focal (exif/focal-35mm-from-arraybuffer ab)]
                 (swap! session assoc :focal-mm focal :focal-source :exif))))
      (.catch (fn [_] nil)))) ; unreadable file/blob — fall back to the default

(defn- report-focal! []
  (let [{:keys [focal-mm focal-source]} @session
        mm (js/Math.round focal-mm)]
    (set-status-message!
     (if (= focal-source :exif)
       (str "Focale da EXIF: " mm "mm")
       (str "EXIF senza focale — uso " mm "mm (regola con lo slider)")))))

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

(defn- registered-result?
  "True for an :acquire-results entry that came from ACTUAL registration work on
   this photo — edge-snap ('s'), PnP ('p') or a manual inverted drag — all of
   which put the camera at a pose the user vouched for. False for a bare
   turntable seed or an 'f'-fit :predicted? guess. Only registered cameras earn
   rigid transport when the proxy is re-aligned on photo 0 (on-photo0-commit!);
   pure seeds/predictions are cheaper — and more correct — to just re-derive
   from the corrected proxy."
  [result]
  (boolean (and result (or (:rms-px result) (:manual? result)))))

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

;; ============================================================
;; Blindato branch lock ('m'): the Klein-twin fix (fix (1) of
;; dev-docs/HANDOVER-edit-acquire-registration-stability.md). A box with three
;; distinct sides has FOUR camera poses that reproject to the identical
;; silhouette, so the edge solver can't tell which physical corner is which and
;; a photo can register onto the mirror branch — the split that made a turntable
;; session run in two opposite senses. The user marks ONE corner physically (a
;; pen arrow) and, once, clicks it in each photo; bridge/branch-by-marker then
;; pins that photo to the Klein image whose marked corner reprojects nearest the
;; click. Decided by the observation, not a predicted seed — so it is robust
;; even at θ≈180 (where a seed guess is worst; measured image separation ≥650px
;; on a 4032px frame). marker-lock-camera is the single funnel snap/PnP/fit pass
;; a fresh camera pose through, so the lock, set once, survives every later
;; registration on that photo.
;; ============================================================

(defn- marked-corner-obj
  "The marked corner in the object/solver frame — the +++ corner, matching
   corner-marker-pos's +ex+ey+ez (the red dot). Once photo 0's proxy is aligned
   so the red dot lands on the physical pen mark, THIS is the point the mark sits
   on, and branch-by-marker reprojects it through each Klein image."
  []
  (let [[w h d] (bridge/dims-from-mesh (:proxy-mesh @session)
                                       (get-in @session [:proxy-mesh :creation-pose]))]
    [(* 0.5 w) (* 0.5 h) (* 0.5 d)]))

(defn- marker-lock-camera
  "If photo `idx` has a marker pick (the user clicked the physical mark), return
   `camera-pose` pinned to the branch whose marked corner reprojects nearest that
   click (bridge/branch-by-marker); otherwise return it unchanged. Every camera
   registration (snap/PnP/fit) funnels its result through here, so a mark clicked
   once locks the branch for good on that photo — no dependence on the turntable
   seed/axis that a symmetric box defeats."
  [camera-pose idx]
  (if-let [click (get-in @session [:marker-picks idx])]
    (if-let [[iw ih] (backdrop/image-size)]
      (bridge/branch-by-marker camera-pose
                               (get-in @session [:proxy-mesh :creation-pose])
                               (marked-corner-obj) click (session-intrinsics iw ih))
      camera-pose)
    camera-pose))

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
          intrinsics (session-intrinsics iw ih)
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
            (let [new-camera-pose (marker-lock-camera
                                   (bridge/solver-pose->camera (:pose result) proxy-pose) idx)]
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
;; Joint turntable fit ('f'): once at least 2 photos have their own `s`
;; picks, recovers ONE shared camera pose + rotation axis that explains all
;; of them together (photogrammetry.match/fit-turntable-seeded — the same
;; model the CLI's own fit-turntable uses, treating each photo's turntable
;; angle as a soft PRIOR rather than rigid truth per the 2026-07-18 design
;; decision, but without the 5×5 axis-offset grid the CLI's blind scan
;; pays for — see fit-turntable-seeded's docstring for why a NARROWED yaw
;; range turned out unsafe here too, and why a full 360° sweep is the
;; default instead) and applies the result to EVERY photo, snapped or not
;; — replacing the from-scratch reseed that made an unrelated photo's pose
;; jump by an amount with no relation to how small the triggering edit was
;; (reported 2026-07-22). Deliberately NOT run automatically after every
;; `s`: still a batch solve (dozens of LM refinements), so it's an
;; explicit, occasional action, not a per-keystroke one.
;; ============================================================

(def min-photos-for-turntable-fit
  "match/fit-turntable-seeded itself never attempts a candidate below 2
   matched photos — mirrored here so the rejection message doesn't wait
   for the (several-second) call to say so."
  2)

(defn- turntable-pose-for
  "The solver pose ({:rvec :t}) for photo `idx` from a fit-turntable result
   — same composition as match/reproject-turntable, only returning the pose
   itself rather than projected edges (reproject-turntable's own concern)."
  [tt-result idx]
  (let [{:keys [base fit hyp]} tt-result
        {:keys [phi psi a b]} (:axis-params fit)
        axis (tt/axis-from-params phi psi a b)
        theta (deg->rad (:theta (nth (:photos @session) idx)))]
    (tt/pose-at-angle base axis (+ (:yaw hyp) (* (:sense hyp) theta)))))

(defn- apply-turntable-fit! [tt-result proxy-pose]
  ;; PREDICT-ONLY: never overwrite a photo the user already registered (snapped
  ;; with 's' or marked with 'm' — registered-result?). The turntable fit is a
  ;; single-DOF global model (one base pose + axis + sense, axis forced through
  ;; the box centre); on a real, slightly-imperfect turntable it is COARSER than
  ;; an individual snap, so applying it to an already-good photo degrades it —
  ;; and the marker lock can only fix the branch, not the geometry (reported
  ;; 2026-07-23: 'f' scrambled every photo and threw the red dots around). Since
  ;; the blindato ('m') already makes each photo individually branch-correct,
  ;; 'f' no longer has a consistency job to do — it just fills photos that have
  ;; no registration at all, branch-locked to their mark when they have one.
  (let [predicted (atom 0)]
    (doseq [idx (range 1 (count (:photos @session)))
            :when (not (registered-result? (get-in @session [:acquire-results idx])))]
      (let [new-camera-pose (marker-lock-camera
                             (bridge/solver-pose->camera (turntable-pose-for tt-result idx) proxy-pose) idx)]
        (swap! session assoc-in [:camera-poses idx] new-camera-pose)
        (swap! session assoc-in [:acquire-results idx] {:predicted? true})
        (swap! predicted inc)))
    (when (pos? (:current-idx @session))
      (viewport/set-camera-pose! (get-in @session [:camera-poses (:current-idx @session)])))
    (set-status-message!
     (if (zero? @predicted)
       "Fit congiunto: tutte le foto sono già registrate (niente da predire)"
       (str "Fit congiunto: predette " @predicted " foto non ancora registrate")))
    (save-acquire-state!)))

(defn- on-fit-turntable! []
  (when-let [[iw ih] (backdrop/image-size)]
    (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
          dims (bridge/dims-from-mesh (:proxy-mesh @session) proxy-pose)
          intrinsics (session-intrinsics iw ih)
          photos-with-picks (vec (keep (fn [[idx result]]
                                         (when-let [picks (:picks result)]
                                           {:picks picks
                                            :theta-deg (:theta (nth (:photos @session) idx))}))
                                       (:acquire-results @session)))]
      (if (< (count photos-with-picks) min-photos-for-turntable-fit)
        (set-status-message!
         (str "Fit congiunto: servono almeno " min-photos-for-turntable-fit
              " foto agganciate con 's' (ce ne sono " (count photos-with-picks) ")"))
        (if-let [tt-result (match/fit-turntable-seeded dims intrinsics photos-with-picks {:sigma-px 1.0})]
          (apply-turntable-fit! tt-result proxy-pose)
          (set-status-message! "Fit congiunto: nessuna soluzione trovata")))))
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

(defn- transport-registered-cameras!
  "Rigidly carry every REGISTERED camera (idx>0 with an :acquire-results the
   user vouched for — registered-result?) through the rigid transform that took
   the proxy from `old-pose` to `new-pose`, keeping each one's :acquire-results
   intact. Pure turntable seeds and 'f'-fit predictions (idx>0, not registered)
   are DROPPED — camera-pose and :acquire-results both — so they re-derive from
   the corrected proxy on next visit. camera-poses[0] and its result are left
   untouched by construction (this only walks idx>0). This is what fix (2) of
   HANDOVER-edit-acquire-registration-stability.md replaces the old
   select-keys [0] wipe with: a re-alignment of the proxy on photo 0 no longer
   throws away the registration work on every other photo — the object didn't
   move relative to those cameras, only our world-space estimate of it did, so
   the camera↔object geometry each registration solved is preserved by moving
   the cameras with the proxy."
  [old-pose new-pose]
  (let [{op :position oh :heading ou :up} old-pose
        {np :position nh :heading nu :up} new-pose]
    (doseq [[idx pose] (:camera-poses @session)
            :when (pos? idx)]
      (if (registered-result? (get-in @session [:acquire-results idx]))
        (swap! session assoc-in [:camera-poses idx]
               (attachment/transform-pose-rigid pose op oh ou np nh nu))
        (do (swap! session update :camera-poses dissoc idx)
            (swap! session update :acquire-results dissoc idx))))))

(defn- on-photo0-commit! [cmd-type value]
  (let [old-pose (get-in @session [:proxy-mesh :creation-pose])
        {:keys [h r u]} (pose-basis old-pose)]
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
    ;; Registered cameras (1..N) follow the proxy RIGIDLY on every commit —
    ;; translate AND rotate. The old code wiped them (select-keys [0]) on any
    ;; pivot move and left them stale on a pure rotation; neither is right.
    ;; A proxy re-alignment on photo 0 doesn't mean those photos' cameras moved
    ;; relative to the object — only our world estimate of the object did — so
    ;; the camera↔object geometry each 's'/'p'/manual registration solved must
    ;; be preserved by transporting the camera with the proxy (fix (2), see
    ;; transport-registered-cameras!). Pure seeds/predictions are dropped there
    ;; to re-derive from the corrected proxy. Rotating the proxy also changes
    ;; its up axis (the turntable vertical seed-camera-pose orbits about), so a
    ;; stale cached seed would seed later photos wrong — dropping it is what
    ;; keeps a photo-0 rotation propagating to the seeds (reported 2026-07-22
    ;; the other way: refining photo 0 'didn't propagate').
    (transport-registered-cameras! old-pose (get-in @session [:proxy-mesh :creation-pose])))
  (viewport/show-preview! (proxy-preview-items))
  (gizmo/update-pose! (get-in @session [:proxy-mesh :creation-pose]))
  ;; Persist the hand-aligned proxy pose. Only the solver paths ('s'/'r'/'f')
  ;; used to save, so a manual gizmo alignment was lost on re-entry — the root of
  ;; "realign the proxy every test" (Vincenzo 2026-07-25). The proxy pose is the
  ;; one thing acquire-state.json already round-trips, so this is the immediate,
  ;; low-risk half of the fix; the source write-back (P4) is the durable half.
  (save-acquire-state!))

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
  (let [idx (:current-idx @session)
        p (apply-inverted (camera-pose-for idx) cmd-type value)]
    (swap! session assoc-in [:camera-poses idx] p)
    ;; Mark the photo touched so save-acquire-state! (which only writes camera
    ;; poses for photos with an acquire-result, to avoid persisting raw seeds)
    ;; keeps this hand-set pose across re-entry — the photos-1..N half of the
    ;; "realign every test" fix (Vincenzo 2026-07-25). :manual? shows no residual
    ;; badge, unlike a snap/pnp result, so it doesn't claim a fit it didn't do.
    (swap! session update-in [:acquire-results idx] merge {:manual? true})
    ;; Discard the live nudge (the proxy mesh itself was never touched) before
    ;; applying the equivalent camera move, so the two never fight visually.
    (viewport/show-preview! (proxy-preview-items))
    (viewport/set-camera-pose! p)
    ;; The widget's own live rotation/translation (also just a preview effect)
    ;; needs the same reset, back onto the frozen proxy pose.
    (gizmo/update-pose! (get-in @session [:proxy-mesh :creation-pose]))
    (save-acquire-state!)))

;; ============================================================
;; Filmstrip navigation
;; ============================================================

(declare stop-pnp!)
(declare stop-marker!)
(declare redraw-retrace!)
(declare redraw-marks!)

(defn- install-gizmo!
  "Open the gizmo for photo `idx`. Photo 0's gizmo commits move the PROXY
   (on-photo0-commit!); photos 1..N-1's commits invert onto the CAMERA
   (on-inv-commit!). Extracted so PnP mode can tear the gizmo down and put it
   back without re-loading the photo. Skipped while the proxy is HIDDEN ('v') —
   the rings would otherwise float over the bare photo with nothing to grab
   (Vincenzo 2026-07-24); the single guard here covers every install site
   (enter-photo!, stop-pnp!/retrace!/mark!), so navigation keeps it hidden too."
  [idx]
  (when-not (:hide-proxy? @session)
    (gizmo/enter! (get-in @session [:proxy-mesh :creation-pose])
                  {:mode :object :handles #{:translate :rotate}}
                  {:on-commit (if (zero? idx) on-photo0-commit! on-inv-commit!)})))

(defn- enter-photo!
  "Close/reopen the gizmo for photo `idx` — simpler to reason about than
   special-casing the 0↔1+ boundary, since :nudge-mesh? can only be set at
   gizmo/enter! time, there's no mutator for it."
  [idx]
  (stop-pnp!) ; leaving a photo cancels any half-collected PnP session on it
  (stop-marker!) ; and any open marker-click mode (its listener is photo-specific)
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
        (set-photo-for-current-focal! file))
      (let [pose (camera-pose-for idx)]
        (viewport/set-camera-pose! pose)
        (set-photo-for-current-focal! file)))
    ;; In :retrace the filmstrip is the live-reprojection control: keep the mode,
    ;; just move the camera onto this photo and re-show the (unchanged) world-space
    ;; polyline from the new angle — never tear down the retrace to install a gizmo.
    (case (:mode @session)
      :retrace (redraw-retrace!)
      ;; :mark is a live-reprojection mode too — the named marks are world/object
      ;; space, so navigating just re-shows them (and their labels) from this
      ;; photo's camera; never tear it down to install a gizmo.
      :mark (redraw-marks!)
      (install-gizmo! idx)))
  (update-panel!))

;; ============================================================
;; PnP registration ('p'): the primary 'registrazione per corrispondenze'
;; gesture (brief P2). The user arms a box corner — DECLARING its identity —
;; and clicks where it sits in the photo; ≥6 declared correspondences recover
;; the camera pose in closed form (photogrammetry.pnp), with NO pose search
;; and NO Klein-symmetry ambiguity — the two things that made drag+snap
;; fragile (see the four bugs in project_edit_acquire_p2_slice2). Snap stays a
;; downstream sub-pixel refiner and the gizmo coarse placement / a fallback for
;; featureless shapes; both are torn down while collecting so they can't grab
;; the picking clicks, and rebuilt on exit.
;; ============================================================

(def ^:private corner-colors
  "Eight distinct hues so a placed photo marker, its panel button, and its dot
   on the proxy read as the same corner at a glance."
  [0xff5555 0xff9f43 0xf4d03f 0x5fd35f 0x38c3d6 0x5b8def 0xb06cf0 0xf06fb0])

(defn- box-object-corners
  "The 8 box corners in the SOLVER/object frame (box-fit/corners), indexed 0-7
   — the :world side of each PnP correspondence."
  []
  (bf/corners (bridge/dims-from-mesh (:proxy-mesh @session)
                                     (get-in @session [:proxy-mesh :creation-pose]))))

(defn- corner-world-positions
  "World position of each of the 8 corners at the current proxy pose, indexed
   the same as box-object-corners — only for drawing the pickable dots."
  []
  (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
        {:keys [ex ey ez]} (bridge/box-basis proxy-pose)
        origin (:position proxy-pose)]
    (mapv (fn [[lx ly lz]]
            (m/v+ origin (m/v+ (m/v* ex lx) (m/v+ (m/v* ey ly) (m/v* ez lz)))))
          (box-object-corners))))

(defn- pnp-picks [] (get-in @session [:pnp-picks (:current-idx @session)] {}))
(defn- pnp-residuals [] (get-in @session [:pnp-residuals (:current-idx @session)] {}))
(defn- pnp-outliers
  "Set of corner indices the robust solve REJECTED as mislabels on this photo."
  []
  (get-in @session [:pnp-outliers (:current-idx @session)] #{}))

(defn- visible-corner-set
  "Corner indices actually visible on the part at the current pose
   (bf/visible-corners) — the only ones offered for picking, so the user is
   never asked to point at a vertex hidden behind the box (Vincenzo,
   2026-07-23). Recomputed from the live camera↔proxy relation, so it tracks
   the part as the pose is refined."
  []
  (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])]
    (bf/visible-corners (bridge/dims-from-mesh (:proxy-mesh @session) proxy-pose)
                        (bridge/editor->solver-pose (current-camera-pose) proxy-pose))))

(defn- pnp-preview-items
  "Proxy as a WIREFRAME (not a solid — the real part must show through so the
   user can click its actual corners in the photo) plus a translucent coloured
   dot at each VISIBLE corner (occluded ones are never drawn — pointing at a
   hidden vertex is a blind guess): the armed one enlarged, placed ones in
   their colour, unplaced ones dimmed, and any corner the robust fit rejected
   drawn as a big opaque RED dot so the mislabel is obvious (shown even if the
   refined pose has since occluded it, so it can still be re-clicked)."
  []
  (let [armed (:pnp-armed @session)
        placed (pnp-picks)
        outliers (pnp-outliers)
        visible (visible-corner-set)]
    (into
     [{:type :wireframe :data (:proxy-mesh @session)}
      {:type :dots
       :data (vec (keep-indexed
                   (fn [i pos]
                     (cond
                       (contains? outliers i)
                       {:pos pos :radius 5.5 :color 0xff2020 :opacity 0.7}
                       (contains? visible i)
                       {:pos pos
                        :radius (if (= i armed) 4.4 2.4)
                        :opacity 0.3
                        :color (if (or (= i armed) (contains? placed i))
                                 (nth corner-colors i) 0x808080)}))
                   (corner-world-positions)))}]
     (trace-items))))

(defn- redraw-pnp-preview! [] (viewport/show-preview! (pnp-preview-items)))

(defn- next-unplaced-corner
  "First VISIBLE, not-yet-placed corner at or after `from` (wrapping) — the
   corners the user can actually point at. Falls back to the first visible
   corner when they're all placed, or `from` if none are visible."
  [from]
  (let [placed (pnp-picks)
        visible (visible-corner-set)
        order (map #(mod (+ from %) 8) (range 8))]
    (or (first (filter #(and (contains? visible %) (not (contains? placed %))) order))
        (first (filter visible order))
        from)))

(defn- arm-corner!
  "Arm a corner for the next photo click — but only a corner the user can point
   at (visible, or a red outlier to be re-clicked); a click on a hidden vertex
   would be a guess, so those are inert."
  [i]
  (when (or (contains? (visible-corner-set) i) (contains? (pnp-outliers) i))
    (swap! session assoc :pnp-armed i)
    (redraw-pnp-preview!)
    (update-panel!)))

;; --- placed-marker overlay (an HTML layer over the canvas; the camera is
;; locked while collecting, so a marker drawn at the click's screen position
;; stays on its photo feature — see pnp-on-pointerdown) ---

(defn- canvas-rect [] (.getBoundingClientRect (viewport/get-canvas)))
(defn- hex->css [c] (str "#" (.padStart (.toString c 16) 6 "0")))

(defn- ensure-pnp-overlay! []
  (or (:pnp-overlay-el @session)
      (let [rect (canvas-rect)
            ov (.createElement js/document "div")
            st (.-style ov)]
        (set! (.-className ov) "eaq-pnp-overlay")
        (set! (.-position st) "fixed")
        (set! (.-left st) (str (.-left rect) "px"))
        (set! (.-top st) (str (.-top rect) "px"))
        (set! (.-width st) (str (.-width rect) "px"))
        (set! (.-height st) (str (.-height rect) "px"))
        (set! (.-pointerEvents st) "none")
        (set! (.-zIndex st) "40")
        (.appendChild (.-body js/document) ov)
        (swap! session assoc :pnp-overlay-el ov)
        ov)))

(defn- remove-pnp-overlay! []
  (when-let [ov (:pnp-overlay-el @session)]
    (.remove ov)
    (swap! session dissoc :pnp-overlay-el)))

(defn- redraw-overlay-dots! []
  (let [ov (ensure-pnp-overlay!)
        rect (canvas-rect)]
    (set! (.-innerHTML ov) "")
    (doseq [[ci {:keys [screen]}] (pnp-picks)]
      (let [[cx cy] screen
            dot (.createElement js/document "div")
            st (.-style dot)]
        (set! (.-position st) "absolute")
        (set! (.-left st) (str (- cx (.-left rect) 7) "px"))
        (set! (.-top st) (str (- cy (.-top rect) 7) "px"))
        (set! (.-width st) "14px")
        (set! (.-height st) "14px")
        (set! (.-borderRadius st) "50%")
        (set! (.-boxSizing st) "border-box")
        (set! (.-border st) "2px solid rgba(255,255,255,0.85)")
        (set! (.-background st) (hex->css (nth corner-colors ci)))
        ;; translucent so photo detail under the marker stays readable while
        ;; placing (Vincenzo, 2026-07-23 / more so 2026-07-25)
        (set! (.-opacity st) "0.4")
        (.appendChild ov dot)))))

(defn- pnp-on-pointerdown [^js e]
  (when (and @session (= :pnp (:mode @session)) (zero? (.-button e)))
    (when-let [px (backdrop/pixel-under-pointer e (viewport/get-camera) (viewport/get-canvas))]
      (.preventDefault e)
      (.stopPropagation e)
      (let [idx (:current-idx @session)
            ci (:pnp-armed @session)]
        (swap! session assoc-in [:pnp-picks idx ci]
               {:px px :screen [(.-clientX e) (.-clientY e)]})
        ;; a new click makes the last solve's residuals/outliers stale — drop
        ;; them so the red flags clear until the user re-solves
        (swap! session update :pnp-residuals dissoc idx)
        (swap! session update :pnp-outliers dissoc idx)
        (redraw-overlay-dots!)
        (arm-corner! (next-unplaced-corner (mod (inc ci) 8)))))))

;; --- loupe: a magnifier that expands the pixels under the cursor so a corner
;; can be placed on the exact edge despite the translucent proxy over it
;; (Vincenzo, 2026-07-25) — reproduces scripts/param-acq-tool.html's lens ---

(def ^:private loupe-size 160)
(def ^:private loupe-zoom-default 8.0)
(def ^:private loupe-zoom-min 2.0)
(def ^:private loupe-zoom-max 16.0)

(defn- loupe-zoom [] (or (:pnp-loupe-zoom @session) loupe-zoom-default))

(defn- ensure-pnp-loupe! []
  (or (:pnp-loupe-el @session)
      (let [cv (.createElement js/document "canvas")
            st (.-style cv)]
        (set! (.-width cv) loupe-size)
        (set! (.-height cv) loupe-size)
        (set! (.-className cv) "eaq-pnp-loupe")
        (set! (.-position st) "fixed")
        (set! (.-pointerEvents st) "none")
        (set! (.-borderRadius st) "50%")
        (set! (.-border st) "1px solid #55565e")
        (set! (.-boxShadow st) "0 4px 18px #000a")
        (set! (.-zIndex st) "60")
        (set! (.-display st) "none")
        (.appendChild (.-body js/document) cv)
        (swap! session assoc :pnp-loupe-el cv)
        cv)))

(defn- remove-pnp-loupe! []
  (when-let [cv (:pnp-loupe-el @session)]
    (.remove cv)
    (swap! session dissoc :pnp-loupe-el)))

(defn- hide-pnp-loupe! []
  (when-let [cv (:pnp-loupe-el @session)]
    (set! (.-display (.-style cv)) "none")))

(defn- update-loupe!
  "Draw + position the loupe for the cursor at pointer event `e` (shared by
   pointermove and wheel-zoom). Hides it when the cursor isn't over the photo."
  [^js e]
  (let [cv (ensure-pnp-loupe!)
        st (.-style cv)]
    (if-let [[ix iy] (backdrop/pixel-under-pointer e (viewport/get-camera) (viewport/get-canvas))]
      (do
        (backdrop/draw-loupe! cv ix iy (loupe-zoom))
        ;; up-right of the cursor by default, clamped into the window so it
        ;; never runs off-screen near an edge
        (let [left (min (- (.-innerWidth js/window) loupe-size 4) (+ (.-clientX e) 24))
              top (max 4 (- (.-clientY e) loupe-size 12))]
          (set! (.-left st) (str left "px"))
          (set! (.-top st) (str top "px"))
          (set! (.-display st) "block")))
      (set! (.-display st) "none"))))

(defn- pnp-on-pointermove [^js e]
  (when (and @session (= :pnp (:mode @session)))
    (update-loupe! e)))

(defn- pnp-on-wheel
  "Wheel over the photo tunes the LOUPE zoom (the camera is locked, so the wheel
   has no other job here) — wheel up magnifies, down widens, so the loupe can be
   dropped to a level where the shape in the photo reads, not just single pixels
   (Vincenzo, 2026-07-25). Consumes the event so it never reaches the viewport's
   own dolly."
  [^js e]
  (when (and @session (= :pnp (:mode @session)))
    (.preventDefault e)
    (.stopPropagation e)
    (let [dir (if (pos? (.-deltaY e)) -1.0 1.0)
          z' (-> (* (loupe-zoom) (Math/pow 1.2 dir))
                 (max loupe-zoom-min) (min loupe-zoom-max))]
      (swap! session assoc :pnp-loupe-zoom z')
      (update-loupe! e))))

(defn- start-pnp! []
  (when (and @session (not= :pnp (:mode @session)))
    (gizmo/close!)
    (swap! session assoc :mode :pnp)
    (arm-corner! (next-unplaced-corner 0))
    (let [^js canvas (viewport/get-canvas)]
      (.addEventListener canvas "pointerdown" pnp-on-pointerdown true)
      (.addEventListener canvas "pointermove" pnp-on-pointermove true)
      (.addEventListener canvas "pointerleave" hide-pnp-loupe! true)
      (.addEventListener canvas "wheel" pnp-on-wheel #js {:capture true :passive false}))
    (redraw-overlay-dots!)
    (redraw-pnp-preview!)
    (update-panel!)))

(defn- stop-pnp! []
  (when (and @session (= :pnp (:mode @session)))
    (let [^js canvas (viewport/get-canvas)]
      (.removeEventListener canvas "pointerdown" pnp-on-pointerdown true)
      (.removeEventListener canvas "pointermove" pnp-on-pointermove true)
      (.removeEventListener canvas "pointerleave" hide-pnp-loupe! true)
      (.removeEventListener canvas "wheel" pnp-on-wheel true))
    (remove-pnp-overlay!)
    (remove-pnp-loupe!)
    (swap! session assoc :mode :gizmo)
    (viewport/show-preview! (proxy-preview-items))
    (install-gizmo! (:current-idx @session))
    (update-panel!)))

(defn- clear-pnp-picks! []
  (swap! session update :pnp-picks dissoc (:current-idx @session))
  (swap! session update :pnp-residuals dissoc (:current-idx @session))
  (swap! session update :pnp-outliers dissoc (:current-idx @session))
  (redraw-overlay-dots!)
  (arm-corner! (next-unplaced-corner 0)))

(defn- corner-labels [cis] (str/join ", " (map #(str "#" (inc %)) (sort cis))))

(defn- pnp-diagnosis
  "Plain-language verdict from a robust PnP solve — the answer to 'why won't the
   rms drop': corner(s) rejected as mislabels (re-click them); a fit still dirty
   with nothing left to drop (systematic — a wrong declared face or lens
   distortion, not a single click); or a clean fit."
  [sol]
  (let [rms (:rms-px sol)
        out (mapv :ci (:outliers sol))]
    (cond
      (seq out)
      (str "scartat" (if (> (count out) 1) "i gli spigoli " "o lo spigolo ")
           (corner-labels out) " (identità sbagliata) — riclicca"
           (if (> (count out) 1) "li" "lo") " sul pezzo vero, poi 'r'. Fit sui restanti "
           (.toFixed rms 1) "px")
      (> rms pnp/accept-rms-px)
      (str "rms alto (" (.toFixed rms 1) "px) senza un singolo colpevole: clicca più "
           "preciso, o la faccia dichiarata è sbagliata; se resta, segnalamelo")
      :else
      (str "fit pulito, rms " (.toFixed rms 2) "px"))))

(defn- on-solve-pnp!
  "Solve the current photo's declared correspondences (robustly — a mislabeled
   corner is auto-rejected) and APPLY the pose, then STAY in PnP mode: rejected
   corners show as big red dots / red panel buttons and the first is re-armed,
   so 'riclicca e premi r' is one gesture. Exit is explicit ('p' / Esci)."
  []
  (when-let [[iw ih] (backdrop/image-size)]
    (let [idx (:current-idx @session)
          proxy-pose (get-in @session [:proxy-mesh :creation-pose])
          object-corners (box-object-corners)
          correspondences (vec (for [[ci {:keys [px]}] (pnp-picks)]
                                 {:ci ci :world (nth object-corners ci) :px px}))
          camera-pose (current-camera-pose)]
      (if (< (count correspondences) pnp/min-correspondences)
        (set-status-message!
         (str "PnP: servono almeno " pnp/min-correspondences " spigoli piazzati (ne hai "
              (count correspondences) ")"))
        (if-let [sol (pnp/solve-pnp correspondences (session-intrinsics iw ih) {})]
          (let [residuals (into {} (map (juxt :ci :residual-px) (:per-point sol)))
                outlier-cis (set (map :ci (:outliers sol)))]
            (if (zero? idx)
              (let [np (bridge/solver-pose->proxy (:pose sol) camera-pose)
                    [new-mesh] (attachment/group-transform
                                [(:proxy-mesh @session)]
                                (:position proxy-pose) (:heading proxy-pose) (:up proxy-pose)
                                (:position np) (:heading np) (:up np))]
                (swap! session assoc :proxy-mesh new-mesh)
                ;; Re-aligning the proxy on photo 0 via PnP is the same rigid
                ;; move as an on-photo0-commit! gizmo drag, so it earns the same
                ;; treatment (fix (2)): registered cameras follow the proxy
                ;; rigidly, pure seeds/predictions are dropped to re-derive.
                ;; (:pnp-residuals/:pnp-outliers only ever exist for registered
                ;; photos, which transport keeps, so they need no separate wipe.)
                (transport-registered-cameras! proxy-pose (:creation-pose new-mesh)))
              (let [ncp (marker-lock-camera (bridge/solver-pose->camera (:pose sol) proxy-pose) idx)]
                (swap! session assoc-in [:camera-poses idx] ncp)))
            (swap! session assoc-in [:acquire-results idx]
                   {:pnp? true :matched (:n sol) :rms-px (:rms-px sol)
                    :outliers (count outlier-cis)})
            (swap! session assoc-in [:pnp-residuals idx] residuals)
            (swap! session assoc-in [:pnp-outliers idx] outlier-cis)
            ;; tee up the first rejected corner for an immediate re-click
            (when (seq outlier-cis)
              (swap! session assoc :pnp-armed (first (sort outlier-cis))))
            (set-status-message! (str "PnP " (name (:method sol)) ": " (pnp-diagnosis sol)))
            (redraw-pnp-preview!)
            (redraw-overlay-dots!)
            (save-acquire-state!))
          (set-status-message! "PnP: nessuna soluzione — spigoli su più facce e almeno 6?")))))
  (update-panel!))

;; ============================================================
;; Retrace ('d'): P3 thin slice — trace a planar feature ON a declared face of
;; the proxy, over the photo in pose. A click is backprojected (camera/pixel-ray)
;; from the registered camera and intersected (math/ray-plane-point) with the
;; declared face, giving a 3D point in the box's OBJECT frame (stable as the
;; proxy moves). The polyline is world geometry, so navigating the filmstrip
;; ([ / ]) re-shows it from each photo's registered camera over that photo's
;; backdrop — the "riproiezione live nelle altre viste" of the brief, for free.
;; Points persist in acquire-state.json; closing emits a minimal (poly …) so the
;; retrace isn't lost (the P4-anticipated emission). Bezier/arc richness (full
;; edit-path-2d) waits for P4, when fase-2 becomes non-modal.
;; ============================================================

(def ^:private retrace-face-labels
  "[axis sign] → human name. A name alone ('Fronte') doesn't say WHICH face, so
   the same colour (retrace-face-colors) tints the active face in 3D and its
   button (Vincenzo 2026-07-25: colour the current one)."
  {[0 1] "Lato +X" [0 -1] "Lato −X"
   [1 1] "Sopra"   [1 -1] "Sotto"
   [2 1] "Fronte"  [2 -1] "Retro"})

(def ^:private retrace-face-colors
  "[axis sign] → colour, shared by the active-face highlight quad and its button
   so which plane is declared is unmistakable at a glance."
  {[0 1] 0x5fd35f [0 -1] 0xb06cf0
   [1 1] 0x38c3d6 [1 -1] 0x5b8def
   [2 1] 0xf4d03f [2 -1] 0xf06fb0})

(def ^:private retrace-face-order [[1 1] [1 -1] [2 1] [2 -1] [0 1] [0 -1]])

(defn- axis-unit [a] (assoc [0.0 0.0 0.0] a 1.0))

(defn- retrace-dims []
  (bridge/dims-from-mesh (:proxy-mesh @session)
                         (get-in @session [:proxy-mesh :creation-pose])))

(defn- plane-of
  "The plane in the OBJECT frame for a {:axis :sign :offset} spec + the box dims:
   {:point :normal}. `offset` moves the plane OUTWARD along the face normal (mm),
   so a positive value floats it above the box surface (a feature sitting proud of
   the face). Shared by the retrace and the named-mark gesture."
  [{:keys [axis sign offset]}]
  (let [half (* 0.5 (nth (retrace-dims) axis))
        coord (* sign (+ half (or offset 0.0)))]
    {:point (assoc [0.0 0.0 0.0] axis coord)
     :normal (axis-unit axis)}))

;; ---- multiple named ricalchi (P4a-3 follow-up, Vincenzo 2026-07-24: "più di
;; uno, ognuno con un id") ----
;; :ricalchi = [{:name :plane :points} …]; :ricalco-idx = the ACTIVE one clicks
;; add to. Each ricalco is one polyline on one declared face; the retrace gesture
;; edits the active one, and "Nuovo ricalco" starts another. Emitted as
;; :shapes {:id-1 (poly …) :id-2 (poly …) …}.
(def ^:private default-plane-spec {:axis 1 :sign 1 :offset 0.0})

(defn- ricalchi [] (get @session :ricalchi []))

(defn- active-r-path
  "assoc-in/get-in path into the ACTIVE ricalco (…:plane / …:points)."
  [& ks]
  (into [:ricalchi (:ricalco-idx @session)] ks))

(defn- active-plane-spec []
  (or (get-in @session (active-r-path :plane)) default-plane-spec))

(defn- next-ricalco-name []
  (let [nums (keep (fn [{:keys [name]}]
                     (when-let [m (re-matches #"ricalco-(\d+)" (or name ""))]
                       (js/parseInt (second m) 10)))
                   (ricalchi))]
    (str "ricalco-" (inc (reduce max 0 nums)))))

(defn- ensure-active-ricalco!
  "Guarantee an active ricalco to draw into (on entering retrace): create the
   first one if the list is empty, else point idx at a valid entry (the last)."
  []
  (let [rs (ricalchi)]
    (cond
      (empty? rs)
      (swap! session assoc
             :ricalchi [{:name (next-ricalco-name) :plane default-plane-spec :points []}]
             :ricalco-idx 0)
      (not (get-in @session [:ricalchi (:ricalco-idx @session)]))
      (swap! session assoc :ricalco-idx (dec (count rs))))))

(defn- retrace-plane [] (plane-of (active-plane-spec)))

(defn- face-quad
  "A translucent coloured quad ON the face declared by `spec` — the plane
   indicator, so it's obvious in 3D which face you're tracing/marking (not just
   the panel text). Coloured by retrace-face-colors, matching the pressed face
   button."
  [{:keys [axis sign] :as spec}]
  (let [coord (nth (:point (plane-of spec)) axis)
        [a1 a2] (vec (remove #{axis} [0 1 2]))
        dims (retrace-dims)
        h1 (* 0.5 (nth dims a1))
        h2 (* 0.5 (nth dims a2))
        mk (fn [s1 s2] (-> [0.0 0.0 0.0] (assoc axis coord) (assoc a1 (* s1 h1)) (assoc a2 (* s2 h2))))
        proxy-pose (get-in @session [:proxy-mesh :creation-pose])
        [w0 w1 w2 w3] (mapv #(bridge/local->world proxy-pose %)
                            [(mk -1 -1) (mk 1 -1) (mk 1 1) (mk -1 1)])]
    {:type :mesh
     :data {:vertices [w0 w1 w2 w3]
            :faces [[0 1 2] [0 2 3]]
            :material {:color (retrace-face-colors [axis sign]) :opacity 0.3 :double-sided true}}}))

(defn- active-face-quad [] (face-quad (active-plane-spec)))

(defn- retrace-solver-pose []
  (bridge/editor->solver-pose (current-camera-pose)
                              (get-in @session [:proxy-mesh :creation-pose])))

(def ^:private retrace-dot-radius
  "World-mm radius of a traced vertex marker — small (the connecting line carries
   the shape; the dot just pins each click), and translucent, so the dots read as
   precise marks over the photo rather than the solid balls of the first cut
   (Vincenzo 2026-07-23: 'i pallini gialli sono enormi')."
  0.9)

(defn- trace-items
  "EVERY ricalco's polyline (world) as show-preview! items — yellow lines + vertex
   dots (on-top, so they read over the photo). The ACTIVE ricalco is full-bright,
   the others dimmer, so which one you're editing reads. Shared by every mode's
   preview (proxy-preview-items / pnp-preview-items / retrace-preview-items) so the
   traced bezels stay visible after leaving :retrace and reproject as the camera
   moves between photos. Empty data is skipped, so an untraced session adds nothing."
  []
  (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
        active-idx (:ricalco-idx @session)]
    (vec (mapcat
          (fn [i {:keys [points]}]
            (let [wpts (mapv #(bridge/local->world proxy-pose %) points)
                  color (if (= i active-idx) 0xffcc33 0xbb8f22)]
              [{:type :lines :data (mapv (fn [a b] {:from a :to b :color color}) wpts (rest wpts))
                :on-top true}
               {:type :dots :data (mapv (fn [w] {:pos w :radius retrace-dot-radius
                                                 :color color :opacity 0.75}) wpts)}]))
          (range) (ricalchi)))))

(defn- retrace-preview-items
  "In :retrace the box is never drawn — only the coloured active-face quad (the
   plane indicator, so which face you're tracing is obvious) plus the trace on
   top of it."
  []
  (into [(active-face-quad)] (trace-items)))

(defn- toggle-proxy!
  "Show/hide the SOLID proxy in the main (gizmo) view so the photo underneath is
   readable while registering. A gizmo-mode control ('v' / panel button); :retrace
   never draws the proxy anyway, so it isn't offered there. The state persists, so
   leaving :retrace returns to whatever was chosen here."
  []
  (swap! session update :hide-proxy? not)
  ;; Hide the gizmo together with the solid proxy (install-gizmo! now no-ops while
  ;; hidden); re-install it when the proxy comes back.
  (if (:hide-proxy? @session)
    (gizmo/close!)
    (install-gizmo! (:current-idx @session)))
  (viewport/show-preview! (proxy-preview-items))
  (update-panel!))

(defn- redraw-retrace! [] (viewport/show-preview! (retrace-preview-items)))

;; loupe reuse (same magnifier as PnP — the camera is locked, so a crop under
;; the cursor stays on its photo feature); the '-pnp-' state keys are shared
(defn- retrace-on-pointermove [^js e]
  (when (and @session (= :retrace (:mode @session)))
    (update-loupe! e)))

(defn- retrace-on-wheel [^js e]
  (when (and @session (= :retrace (:mode @session)))
    (.preventDefault e) (.stopPropagation e)
    (let [dir (if (pos? (.-deltaY e)) -1.0 1.0)
          z' (-> (* (loupe-zoom) (Math/pow 1.2 dir)) (max loupe-zoom-min) (min loupe-zoom-max))]
      (swap! session assoc :pnp-loupe-zoom z')
      (update-loupe! e))))

(defn- retrace-on-pointerdown [^js e]
  (when (and @session (= :retrace (:mode @session)) (zero? (.-button e)))
    (when-let [[iw ih] (backdrop/image-size)]
      (when-let [px (backdrop/pixel-under-pointer e (viewport/get-camera) (viewport/get-canvas))]
        (.preventDefault e) (.stopPropagation e)
        (let [ray (pcamera/pixel-ray (session-intrinsics iw ih) (retrace-solver-pose) px)
              {:keys [point normal]} (retrace-plane)]
          (if-let [hit (m/ray-plane-point ray point normal)]
            (do (swap! session update-in (active-r-path :points) (fnil conj []) hit)
                (redraw-retrace!)
                (save-acquire-state!)
                (update-panel!))
            (set-status-message! "Il click non incontra il piano dichiarato")))))))

(defn- teardown-retrace-listeners! []
  (let [^js canvas (viewport/get-canvas)]
    (.removeEventListener canvas "pointerdown" retrace-on-pointerdown true)
    (.removeEventListener canvas "pointermove" retrace-on-pointermove true)
    (.removeEventListener canvas "pointerleave" hide-pnp-loupe! true)
    (.removeEventListener canvas "wheel" retrace-on-wheel true))
  (remove-pnp-loupe!))

(defn- start-retrace! []
  (when (and @session (not= :retrace (:mode @session)))
    (gizmo/close!)
    (swap! session assoc :mode :retrace)
    (ensure-active-ricalco!)
    (let [^js canvas (viewport/get-canvas)]
      (.addEventListener canvas "pointerdown" retrace-on-pointerdown true)
      (.addEventListener canvas "pointermove" retrace-on-pointermove true)
      (.addEventListener canvas "pointerleave" hide-pnp-loupe! true)
      (.addEventListener canvas "wheel" retrace-on-wheel #js {:capture true :passive false}))
    (redraw-retrace!)
    (update-panel!)))

(defn- stop-retrace! []
  (when (and @session (= :retrace (:mode @session)))
    (teardown-retrace-listeners!)
    (swap! session assoc :mode :gizmo)
    (viewport/show-preview! (proxy-preview-items))
    (install-gizmo! (:current-idx @session))
    (update-panel!)))

(defn- undo-retrace-point! []
  (when (seq (get-in @session (active-r-path :points)))
    (swap! session update-in (active-r-path :points) pop)
    (redraw-retrace!)
    (save-acquire-state!)
    (update-panel!)))

(defn- clear-retrace! []
  (swap! session assoc-in (active-r-path :points) [])
  (redraw-retrace!)
  (save-acquire-state!)
  (update-panel!))

(defn- new-ricalco!
  "Start a fresh ricalco (keeping the current face), make it active. The gesture
   then draws into the new one; the old ones stay put and keep rendering."
  []
  (let [plane (active-plane-spec)]
    (swap! session update :ricalchi (fnil conj [])
           {:name (next-ricalco-name) :plane plane :points []})
    (swap! session assoc :ricalco-idx (dec (count (ricalchi))))
    (redraw-retrace!)
    (save-acquire-state!)
    (update-panel!)))

(defn- select-ricalco! [i]
  (swap! session assoc :ricalco-idx i)
  (redraw-retrace!)
  (update-panel!))

(defn- delete-ricalco! [i]
  (swap! session update :ricalchi
         (fn [rs] (vec (concat (subvec rs 0 i) (subvec rs (inc i))))))
  ;; keep :ricalco-idx valid (clamp; the deleted one shifts the rest down)
  (swap! session update :ricalco-idx
         (fn [idx] (let [n (count (ricalchi))]
                     (cond (zero? n) nil
                           (>= idx n) (dec n)
                           (> i idx) idx
                           :else (max 0 (dec idx))))))
  (redraw-retrace!)
  (save-acquire-state!)
  (update-panel!))

(defn- rename-ricalco! [i new-name]
  (swap! session assoc-in [:ricalchi i :name] new-name)
  (save-acquire-state!))

;; ============================================================
;; Named marks ('k'): the acquisizione-parametrica MARK primitive (P4a-3) — a
;; NAMED point of the observed object (position + direction + id) that lands in
;; the emitted (acquire …) form's :marks and is recalled by name. NOT the blindato
;; 'm' marker (that's :marker / marker-lock-camera, the Klein branch-lock). This
;; first cut is plane-declared (Vincenzo's choice): a click is backprojected onto
;; a declared box face — the exact same click→plane→object-point machinery as the
;; retrace, so the position is exact with one click; the direction is the face
;; normal. Each mark stores its own object-frame position + normal, so it survives
;; a later face change (it doesn't reference the shared plane after placement).
;; Its own :mark-plane (separate from :retrace :plane) so switching the mark face
;; never clears the ricalco polyline.
;; ============================================================

;; mark-color + mark-dots-item are defined up by proxy-preview-items (shared so a
;; mark stays visible in every mode, not just :mark).

(defn- marks [] (get @session :marks []))

(defn- next-mark-name
  "Auto id `mark-N`, N one past the highest existing mark-N (so deleting then
   re-adding never collides). User-renamable in the panel."
  []
  (let [nums (keep (fn [{:keys [name]}]
                     (when-let [m (re-matches #"mark-(\d+)" (or name ""))]
                       (js/parseInt (second m) 10)))
                   (marks))]
    (str "mark-" (inc (reduce max 0 nums)))))

(defn- mark-world-positions
  "World position of every mark at the current proxy pose — marks store OBJECT-
   frame positions (stable as the proxy moves), lifted here for drawing/emit."
  []
  (let [pose (get-in @session [:proxy-mesh :creation-pose])]
    (mapv (fn [{:keys [position]}] (bridge/local->world pose position)) (marks))))

(defn- mark-preview-items
  "Mark-mode preview: the :mark-plane face quad, the ricalco trace (context) and a
   magenta dot per mark. The solid proxy is hidden (like :retrace) so the photo
   under it stays readable."
  []
  (conj (into [(face-quad (:mark-plane @session))] (trace-items))
        (mark-dots-item)))

(defn- redraw-marks! []
  (viewport/show-preview! (mark-preview-items))
  ;; billboard the id at each mark (viewport/set-labels!, as image-board's ruler
  ;; label) so which dot is which name reads in 3D, not just the panel list
  (viewport/set-labels!
   (mapv (fn [{:keys [name]} w] {:text name :position w :color mark-color})
         (marks) (mark-world-positions))))

(defn- mark-on-pointermove [^js e]
  (when (and @session (= :mark (:mode @session)))
    (update-loupe! e)))

(defn- mark-on-wheel [^js e]
  (when (and @session (= :mark (:mode @session)))
    (.preventDefault e) (.stopPropagation e)
    (let [dir (if (pos? (.-deltaY e)) -1.0 1.0)
          z' (-> (* (loupe-zoom) (Math/pow 1.2 dir)) (max loupe-zoom-min) (min loupe-zoom-max))]
      (swap! session assoc :pnp-loupe-zoom z')
      (update-loupe! e))))

(defn- mark-on-pointerdown [^js e]
  (when (and @session (= :mark (:mode @session)) (zero? (.-button e)))
    (when-let [[iw ih] (backdrop/image-size)]
      (when-let [px (backdrop/pixel-under-pointer e (viewport/get-camera) (viewport/get-canvas))]
        (.preventDefault e) (.stopPropagation e)
        (let [ray (pcamera/pixel-ray (session-intrinsics iw ih) (retrace-solver-pose) px)
              {:keys [point normal]} (plane-of (:mark-plane @session))]
          (if-let [hit (m/ray-plane-point ray point normal)]
            (do (swap! session update :marks (fnil conj [])
                       {:name (next-mark-name) :position hit :normal normal})
                (redraw-marks!)
                (save-acquire-state!)
                (update-panel!))
            (set-status-message! "Il click non incontra il piano dichiarato")))))))

(defn- teardown-mark-listeners! []
  (let [^js canvas (viewport/get-canvas)]
    (.removeEventListener canvas "pointerdown" mark-on-pointerdown true)
    (.removeEventListener canvas "pointermove" mark-on-pointermove true)
    (.removeEventListener canvas "pointerleave" hide-pnp-loupe! true)
    (.removeEventListener canvas "wheel" mark-on-wheel true))
  (remove-pnp-loupe!))

(defn- start-mark! []
  (when (and @session (not= :mark (:mode @session)))
    (gizmo/close!)
    (swap! session assoc :mode :mark)
    (let [^js canvas (viewport/get-canvas)]
      (.addEventListener canvas "pointerdown" mark-on-pointerdown true)
      (.addEventListener canvas "pointermove" mark-on-pointermove true)
      (.addEventListener canvas "pointerleave" hide-pnp-loupe! true)
      (.addEventListener canvas "wheel" mark-on-wheel #js {:capture true :passive false}))
    (redraw-marks!)
    (update-panel!)))

(defn- stop-mark! []
  (when (and @session (= :mark (:mode @session)))
    (teardown-mark-listeners!)
    (viewport/clear-labels!)
    (swap! session assoc :mode :gizmo)
    (viewport/show-preview! (proxy-preview-items))
    (install-gizmo! (:current-idx @session))
    (update-panel!)))

(defn- set-mark-face!
  "Pick the face for the NEXT mark. Unlike set-retrace-face! this clears NOTHING —
   each mark already carries its own object-frame position + normal, so existing
   marks (possibly on other faces) are untouched."
  [axis sign]
  (swap! session update :mark-plane assoc :axis axis :sign sign)
  (redraw-marks!)
  (update-panel!))

(defn- undo-mark! []
  (when (seq (marks))
    (swap! session update :marks pop)
    (redraw-marks!)
    (save-acquire-state!)
    (update-panel!)))

(defn- delete-mark! [idx]
  (swap! session update :marks
         (fn [ms] (vec (concat (subvec ms 0 idx) (subvec ms (inc idx))))))
  (redraw-marks!)
  (save-acquire-state!)
  (update-panel!))

(defn- clear-marks! []
  (swap! session assoc :marks [])
  (redraw-marks!)
  (save-acquire-state!)
  (update-panel!))

(defn- rename-mark!
  "Rename mark `idx`. No redraw-of-preview needed (dots unchanged) but the label
   text changes, so refresh labels; skip a full save on every keystroke — the
   caller (change listener) fires on blur/commit."
  [idx new-name]
  (swap! session assoc-in [:marks idx :name] new-name)
  (redraw-marks!)
  (save-acquire-state!))

;; ============================================================
;; Blindato marker mode ('m'): click the physical mark to pin the branch. A tiny
;; mode — one canvas click, no plane/loupe (the four Klein reprojections sit
;; ≥650px apart on a 4032px frame, so a rough click disambiguates). See
;; marker-lock-camera above for the lock itself.
;; ============================================================

(defn- on-marker-click!
  "The user clicked the physical mark on the current photo. Store the pixel and
   pin this photo's camera to the marked branch (marker-lock-camera). Camera
   photos only — photo 0's branch is fixed by the proxy alignment, not a camera
   lock. Flags :manual? so the marker-locked pose is persisted + rigidly
   transported like any hand-set camera (transport-registered-cameras!)."
  [px]
  (let [idx (:current-idx @session)]
    (when (pos? idx)
      (swap! session assoc-in [:marker-picks idx] px)
      (let [locked (marker-lock-camera (camera-pose-for idx) idx)]
        (swap! session assoc-in [:camera-poses idx] locked)
        (swap! session update-in [:acquire-results idx] merge {:manual? true})
        (viewport/set-camera-pose! locked)
        (save-acquire-state!)
        (set-status-message! "Segno marcato — ramo bloccato su questa foto")
        ;; one-shot: return to the gizmo (which re-shows the preview and re-arms
        ;; the normal handles) so no marker pointer handler lingers to conflict
        ;; with s/f/p, and a rough click is enough (≥650px margin).
        (stop-marker!)))))

(defn- marker-on-pointerdown [^js e]
  (when (and @session (= :marker (:mode @session)) (zero? (.-button e)))
    (when-let [px (backdrop/pixel-under-pointer e (viewport/get-camera) (viewport/get-canvas))]
      (.preventDefault e) (.stopPropagation e)
      (on-marker-click! px))))

(defn- start-marker! []
  (when (and @session (not= :marker (:mode @session)))
    (if (zero? (:current-idx @session))
      (set-status-message! "Il segno si marca su una foto diversa dalla prima (quella fissa il proxy)")
      (do (gizmo/close!)
          (swap! session assoc :mode :marker)
          (.addEventListener (viewport/get-canvas) "pointerdown" marker-on-pointerdown true)
          (set-status-message! "Clicca il segno/freccia sul pezzo in questa foto")
          (update-panel!)))))

(defn- stop-marker! []
  (when (and @session (= :marker (:mode @session)))
    (.removeEventListener (viewport/get-canvas) "pointerdown" marker-on-pointerdown true)
    (swap! session assoc :mode :gizmo)
    (viewport/show-preview! (proxy-preview-items))
    (install-gizmo! (:current-idx @session))
    (update-panel!)))

(defn- set-retrace-face!
  "Pick the ACTIVE ricalco's declared face. A ricalco belongs to ONE plane, so
   switching its face clears ITS points (they'd be meaningless there); the offset
   carries over. Other ricalchi are untouched."
  [axis sign]
  (let [had (seq (get-in @session (active-r-path :points)))]
    (swap! session update-in (active-r-path)
           (fn [rt] (assoc rt :plane (assoc (:plane rt) :axis axis :sign sign) :points [])))
    (redraw-retrace!)
    (save-acquire-state!)
    (when had (set-status-message! "Piano cambiato — ricalco azzerato"))
    (update-panel!)))

(defn- on-retrace-offset-change!
  "Live offset of the ACTIVE ricalco's plane along its normal (mm). Does NOT clear
   already-placed points (they keep their 3D positions); it retargets future
   clicks and moves the drawn face rectangle, so it's a set-first control."
  [offset]
  (swap! session assoc-in (active-r-path :plane :offset) offset)
  (redraw-retrace!))

(defn- retrace-offset-range [_] [-15 15 0.5])

(defn- ricalco-poly-string
  "One ricalco's polyline as a (poly …) of its in-plane 2D coords, or nil below 3
   points (poly needs ≥3). The in-plane axes are the two box axes other than the
   declared plane's normal axis."
  [{:keys [plane points]}]
  (when (>= (count points) 3)
    (let [[a1 a2] (vec (remove #{(:axis plane)} [0 1 2]))
          f3 (fn [x] (.toFixed x 3))]
      (str "(poly "
           (str/join " " (mapcat (fn [p] [(f3 (nth p a1)) (f3 (nth p a2))]) points))
           ")"))))

(defn- shapes-emit-string
  "All ricalchi with ≥3 points as a source map {:id (poly …) …}, names keywordized
   and uniquified (a map can't hold duplicate keys). '{}' when none is drawable."
  []
  (let [seen (atom #{})
        uniq (fn [nm] (loop [n (if (seq nm) nm "ricalco")]
                        (if (contains? @seen n) (recur (str n "-2")) (do (swap! seen conj n) n))))
        entries (keep (fn [{:keys [name] :as r}]
                        (when-let [poly (ricalco-poly-string r)]
                          (str ":" (uniq name) " " poly)))
                      (ricalchi))]
    (if (seq entries) (str "{" (str/join " " entries) "}") "{}")))

;; ============================================================
;; Panel (numbered filmstrip + focal-length field + Chiudi — no badges/
;; residuals, those need the solver integration, out of scope here)
;; ============================================================

(declare close! confirm! discard!)

(defn- focal-range [_] [20 135 1])

(defn- on-focal-change!
  "Live: reapplies to whichever photo is already showing (backdrop/set-focal!
   is a no-op before the first photo has loaded), so dragging the slider
   re-scales the proxy against the CURRENT photo with position/rotation
   untouched — the size-then-pose split from Vincenzo's 2026-07-21 feedback:
   get the apparent scale right first, on photo 0, before touching the gizmo."
  [focal-mm]
  (swap! session assoc :focal-mm focal-mm :focal-source :manual)
  (backdrop/set-focal! focal-mm viewport/set-camera-fov!))

(defn- build-panel! []
  (let [panel (.createElement js/document "div")
        header (.createElement js/document "div")
        hint (.createElement js/document "div")
        filmstrip (.createElement js/document "div")
        pnp-box (.createElement js/document "div")
        retrace-box (.createElement js/document "div")
        mark-box (.createElement js/document "div")
        message (.createElement js/document "div")
        {:keys [row slider]} (ui/create-slider-row {:label "Focale (mm)"
                                                    :value (:focal-mm @session)
                                                    :range-fn focal-range
                                                    :on-input on-focal-change!})
        buttons (.createElement js/document "div")
        ok-btn (.createElement js/document "button")
        close-btn (.createElement js/document "button")]
    (set! (.-className header) "pilot-header")
    (set! (.-textContent header) "edit-acquire — gate ingegneristico")
    (.appendChild panel header)
    (set! (.-textContent hint)
          "1) la Focale è letta da EXIF — ritoccala con lo slider (foto 1) solo se il box è della taglia sbagliata, SENZA spostarlo — 2) poi trascina per posizione/rotazione — 3) 's' per agganciare il box agli spigoli reali della foto — 4) con almeno 2 foto agganciate, 'f' per il fit congiunto sulle altre. 'v' nasconde/mostra il proxy per leggere la foto sotto.")
    (.appendChild panel hint)
    (.appendChild panel row)
    (.appendChild panel filmstrip)
    (set! (.-className pnp-box) "eaq-pnp-box")
    (.appendChild panel pnp-box)
    (set! (.-className retrace-box) "eaq-retrace-box")
    (.appendChild panel retrace-box)
    (set! (.-className mark-box) "eaq-mark-box")
    (.appendChild panel mark-box)
    (set! (.-className message) "ems-message")
    (.appendChild panel message)
    ;; Conferma emits (acquire "dir" {…}) over the marker; Chiudi discards
    ;; (strip-head → (acquire …) on the marker path, plain teardown otherwise).
    (set! (.-className buttons) "pilot-buttons")
    (set! (.-type ok-btn) "button")
    (set! (.-textContent ok-btn) "Conferma (OK)")
    (.add (.-classList ok-btn) "pilot-btn")
    (.add (.-classList ok-btn) "pilot-btn-ok")
    (.addEventListener ok-btn "click" (fn [_] (confirm!)))
    (set! (.-type close-btn) "button")
    (set! (.-textContent close-btn) "Chiudi")
    (.add (.-classList close-btn) "pilot-btn")
    (.add (.-classList close-btn) "pilot-btn-cancel")
    (.addEventListener close-btn "click" (fn [_] (discard!)))
    (.appendChild buttons ok-btn)
    (.appendChild buttons close-btn)
    (.appendChild panel buttons)
    (swap! session assoc :panel-el panel :filmstrip-el filmstrip :focal-slider-el slider
           :message-el message :pnp-el pnp-box :retrace-el retrace-box :mark-el mark-box)
    (modal/mount-panel! panel)
    (update-panel!)))

(defn- render-pnp-panel!
  "The PnP controls, rendered into :pnp-el and rebuilt each update: a single
   'Registra per punti' button in gizmo mode; in PnP mode the armed-corner
   prompt, eight corner buttons (colour = corner, filled = placed, white ring =
   armed), and Risolvi/Azzera/Esci."
  []
  (when-let [box (:pnp-el @session)]
    (set! (.-innerHTML box) "")
    (if (not= :pnp (:mode @session))
      ;; entry button only from :gizmo — never on top of :retrace (empty box there)
      (when (= :gizmo (:mode @session))
        (let [b (.createElement js/document "button")]
          (set! (.-type b) "button")
          (set! (.-textContent b) "Registra per punti (p)")
          (.addEventListener b "click" (fn [_] (start-pnp!)))
          (.appendChild box b)))
      (let [placed (pnp-picks)
            resid (pnp-residuals)
            outliers (pnp-outliers)
            visible (visible-corner-set)
            ;; buttons for corners the user can act on: visible (offerable),
            ;; already-placed (status/re-do), or flagged outliers
            shown (sort (into (into (set (keys placed)) outliers) visible))
            armed (:pnp-armed @session)
            n (count placed)
            target (count visible)
            rms (get-in @session [:acquire-results (:current-idx @session) :rms-px])
            solved? (seq resid)
            info (.createElement js/document "div")
            corners (.createElement js/document "div")
            actions (.createElement js/document "div")]
        (set! (.-className info) "eaq-pnp-info")
        (set! (.-textContent info)
              (cond
                (seq outliers)
                (str "⚠ " (if (> (count outliers) 1) "spigoli " "spigolo ")
                     (corner-labels outliers) " in rosso — riclicca dov'"
                     (if (> (count outliers) 1) "sono" "è") " sul pezzo vero, poi 'r'")
                (and rms (> rms pnp/accept-rms-px))
                (str "⚠ rms alto (" (.toFixed rms 0) "px) senza un colpevole singolo — "
                     "clicca più preciso o controlla la faccia dichiarata")
                solved?
                (str "✓ fit pulito, rms " (.toFixed rms 1) "px — 'p'/Esci, o ']' per un'altra foto")
                :else
                (str "Spigolo #" (inc armed) " evidenziato — clicca nella foto dov'è. "
                     "Piazzati " n "/" target " visibili"
                     (when (< n pnp/min-correspondences)
                       (str " (ne servono ≥" pnp/min-correspondences ")")))))
        (.appendChild box info)
        (set! (.-className corners) "eaq-pnp-corners")
        (doseq [i shown]
          (let [b (.createElement js/document "button")
                st (.-style b)
                r (get resid i)
                bad? (contains? outliers i)]
            (set! (.-type b) "button")
            (set! (.-textContent b) (cond bad? (str (inc i) " ✗")
                                          r (str (inc i) "·" (.toFixed r 0))
                                          :else (str (inc i))))
            (set! (.-minWidth st) "26px")
            (set! (.-color st) (cond bad? "#fff" (contains? placed i) "#111" :else "#ddd"))
            (set! (.-background st) (cond bad? "#ff2020"
                                          (contains? placed i) (hex->css (nth corner-colors i))
                                          :else "#333"))
            (set! (.-border st) (if (= i armed) "2px solid #fff" "1px solid #555"))
            (.addEventListener b "click" (fn [_] (arm-corner! i)))
            (.appendChild corners b)))
        (.appendChild box corners)
        (set! (.-className actions) "eaq-pnp-actions")
        (let [solve (.createElement js/document "button")
              clr (.createElement js/document "button")
              exit (.createElement js/document "button")]
          (set! (.-type solve) "button")
          (set! (.-textContent solve) "Risolvi PnP (r)")
          (set! (.-disabled solve) (< n pnp/min-correspondences))
          (.addEventListener solve "click" (fn [_] (on-solve-pnp!)))
          (set! (.-type clr) "button")
          (set! (.-textContent clr) "Azzera")
          (.addEventListener clr "click" (fn [_] (clear-pnp-picks!)))
          (set! (.-type exit) "button")
          (set! (.-textContent exit) "Esci (p)")
          (.addEventListener exit "click" (fn [_] (stop-pnp!)))
          (.appendChild actions solve)
          (.appendChild actions clr)
          (.appendChild actions exit))
        (.appendChild box actions)))))

(defn- render-retrace-panel!
  "Retrace controls in :retrace-el, rebuilt each update. Entry button only in
   :gizmo mode (so a mode is never started on top of another — pnp/retrace are
   both reached from the gizmo); in :retrace mode the six face buttons (current
   highlighted), an offset slider, a point count + hint, and Annulla/Azzera/Esci."
  []
  (when-let [box (:retrace-el @session)]
    (set! (.-innerHTML box) "")
    (cond
      (= :gizmo (:mode @session))
      (let [actions (.createElement js/document "div")
            pv (.createElement js/document "button")
            b (.createElement js/document "button")]
        (set! (.-className actions) "eaq-pnp-actions")
        (set! (.-type pv) "button")
        (set! (.-textContent pv) (if (:hide-proxy? @session) "Mostra proxy (v)" "Nascondi proxy (v)"))
        (.addEventListener pv "click" (fn [_] (toggle-proxy!)))
        (set! (.-type b) "button")
        (set! (.-textContent b) "Ricalca su un piano (d)")
        (.addEventListener b "click" (fn [_] (start-retrace!)))
        (.appendChild actions pv)
        (.appendChild actions b)
        ;; P4a-3: place named points on a declared face (distinct from the
        ;; blindato 'm' below — that pins the branch, this names object points).
        (let [mkb (.createElement js/document "button")]
          (set! (.-type mkb) "button")
          (set! (.-textContent mkb) "Segna punti (k)")
          (.addEventListener mkb "click" (fn [_] (start-mark!)))
          (.appendChild actions mkb))
        ;; Blindato: mark the physical sign to pin this photo's branch. Camera
        ;; photos only (photo 0's branch is set by the proxy alignment). A green
        ;; tick reminds the user this photo already has its mark.
        (when (pos? (:current-idx @session))
          (let [mk (.createElement js/document "button")
                marked? (get-in @session [:marker-picks (:current-idx @session)])]
            (set! (.-type mk) "button")
            (set! (.-textContent mk) (if marked? "Segno marcato ✓ — rimarca (m)" "Marca il segno (m)"))
            (.addEventListener mk "click" (fn [_] (start-marker!)))
            (.appendChild actions mk)))
        (.appendChild box actions))

      (= :marker (:mode @session))
      (let [info (.createElement js/document "div")
            actions (.createElement js/document "div")
            exit (.createElement js/document "button")]
        (set! (.-className info) "eaq-pnp-info")
        (set! (.-textContent info)
              "Clicca sul segno/freccia disegnato sul pezzo, in questa foto. Serve a dire da che lato sta il pezzo (blocca il ramo).")
        (.appendChild box info)
        (set! (.-className actions) "eaq-pnp-actions")
        (set! (.-type exit) "button")
        (set! (.-textContent exit) "Esci (Esc)")
        (.addEventListener exit "click" (fn [_] (stop-marker!)))
        (.appendChild actions exit)
        (.appendChild box actions))

      (= :retrace (:mode @session))
      (let [{:keys [axis sign offset]} (active-plane-spec)
            rs (ricalchi)
            active-idx (:ricalco-idx @session)
            npts (count (get-in @session (active-r-path :points)))
            info (.createElement js/document "div")
            list-el (.createElement js/document "div")
            faces (.createElement js/document "div")
            {:keys [row]} (ui/create-slider-row {:label "Offset piano (mm)"
                                                 :value offset
                                                 :range-fn retrace-offset-range
                                                 :on-input on-retrace-offset-change!})
            actions (.createElement js/document "div")]
        (set! (.-className info) "eaq-pnp-info")
        (set! (.-textContent info)
              (str "Ricalco attivo: " (or (:name (get rs active-idx)) "—") " — piano "
                   (retrace-face-labels [axis sign]) ", clicca il contorno sulla foto ("
                   npts " punti). '[' / ']' per rivederli dalle altre viste."))
        (.appendChild box info)
        ;; one row per ricalco: ● active / ○ pick-active, editable id, ✕ delete
        (set! (.-className list-el) "eaq-mark-list")
        (doseq [[i {:keys [name]}] (map-indexed vector rs)]
          (let [rrow (.createElement js/document "div")
                sel (.createElement js/document "button")
                inp (.createElement js/document "input")
                del (.createElement js/document "button")]
            (set! (.-className rrow) "eaq-mark-row")
            (set! (.-type sel) "button")
            (set! (.-textContent sel) (if (= i active-idx) "●" "○"))
            (set! (.-title sel) "Rendi attivo")
            (.addEventListener sel "click" (fn [_] (select-ricalco! i)))
            (set! (.-type inp) "text")
            (set! (.-value inp) name)
            (set! (.. inp -style -width) "110px")
            (.addEventListener inp "change" (fn [^js e] (rename-ricalco! i (.. e -target -value))))
            (set! (.-type del) "button")
            (set! (.-textContent del) "✕")
            (.addEventListener del "click" (fn [_] (delete-ricalco! i)))
            (.appendChild rrow sel)
            (.appendChild rrow inp)
            (.appendChild rrow del)
            (.appendChild list-el rrow)))
        (.appendChild box list-el)
        (set! (.-className faces) "eaq-pnp-corners")
        (doseq [[a s] retrace-face-order]
          (let [b (.createElement js/document "button")
                st (.-style b)
                cur? (and (= a axis) (= s sign))]
            (set! (.-type b) "button")
            (set! (.-textContent b) (retrace-face-labels [a s]))
            ;; each button carries its face colour; the active one is full-bright
            ;; with a white ring, the others dimmed — so the panel matches the
            ;; coloured face in 3D (Vincenzo 2026-07-25)
            (set! (.-color st) "#111")
            (set! (.-background st) (hex->css (retrace-face-colors [a s])))
            (set! (.-opacity st) (if cur? "1" "0.5"))
            (set! (.-border st) (if cur? "2px solid #fff" "1px solid #555"))
            (.addEventListener b "click" (fn [_] (set-retrace-face! a s)))
            (.appendChild faces b)))
        (.appendChild box faces)
        (.appendChild box row)
        (set! (.-className actions) "eaq-pnp-actions")
        (let [nw (.createElement js/document "button")
              undo (.createElement js/document "button")
              clr (.createElement js/document "button")
              exit (.createElement js/document "button")]
          (set! (.-type nw) "button")
          (set! (.-textContent nw) "Nuovo ricalco (n)")
          (.addEventListener nw "click" (fn [_] (new-ricalco!)))
          (set! (.-type undo) "button")
          (set! (.-textContent undo) "Annulla ultimo (⌫)")
          (set! (.-disabled undo) (zero? npts))
          (.addEventListener undo "click" (fn [_] (undo-retrace-point!)))
          (set! (.-type clr) "button")
          (set! (.-textContent clr) "Azzera")
          (set! (.-disabled clr) (zero? npts))
          (.addEventListener clr "click" (fn [_] (clear-retrace!)))
          (set! (.-type exit) "button")
          (set! (.-textContent exit) "Esci (d)")
          (.addEventListener exit "click" (fn [_] (stop-retrace!)))
          (.appendChild actions nw)
          (.appendChild actions undo)
          (.appendChild actions clr)
          (.appendChild actions exit))
        (.appendChild box actions)))))

(defn- render-mark-panel!
  "Named-mark controls in :mark-el, rebuilt each update. Empty unless in :mark
   mode (entry is the 'Segna punti (k)' button in the gizmo panel); there: the six
   face buttons (the plane for the NEXT mark — changing it clears nothing), a row
   per placed mark with an editable id + delete, and Annulla/Azzera/Esci."
  []
  (when-let [box (:mark-el @session)]
    (set! (.-innerHTML box) "")
    (when (= :mark (:mode @session))
      (let [{:keys [axis sign]} (:mark-plane @session)
            ms (marks)
            info (.createElement js/document "div")
            faces (.createElement js/document "div")
            list-el (.createElement js/document "div")
            actions (.createElement js/document "div")]
        (set! (.-className info) "eaq-pnp-info")
        (set! (.-textContent info)
              (str "Faccia: " (retrace-face-labels [axis sign])
                   " — clicca sulla foto per segnare un punto (" (count ms) " segnati). "
                   "'[' / ']' per rivederli dalle altre viste."))
        (.appendChild box info)
        (set! (.-className faces) "eaq-pnp-corners")
        (doseq [[a s] retrace-face-order]
          (let [b (.createElement js/document "button")
                st (.-style b)
                cur? (and (= a axis) (= s sign))]
            (set! (.-type b) "button")
            (set! (.-textContent b) (retrace-face-labels [a s]))
            (set! (.-color st) "#111")
            (set! (.-background st) (hex->css (retrace-face-colors [a s])))
            (set! (.-opacity st) (if cur? "1" "0.5"))
            (set! (.-border st) (if cur? "2px solid #fff" "1px solid #555"))
            (.addEventListener b "click" (fn [_] (set-mark-face! a s)))
            (.appendChild faces b)))
        (.appendChild box faces)
        ;; one row per mark: editable id (commit on blur/Enter → rename) + delete
        (set! (.-className list-el) "eaq-mark-list")
        (doseq [[i {:keys [name]}] (map-indexed vector ms)]
          (let [row (.createElement js/document "div")
                sw (.createElement js/document "span")
                inp (.createElement js/document "input")
                del (.createElement js/document "button")]
            (set! (.-className row) "eaq-mark-row")
            (set! (.-textContent sw) "•")
            (set! (.. sw -style -color) (hex->css mark-color))
            (set! (.-type inp) "text")
            (set! (.-value inp) name)
            (set! (.. inp -style -width) "110px")
            (.addEventListener inp "change" (fn [^js e] (rename-mark! i (.. e -target -value))))
            (set! (.-type del) "button")
            (set! (.-textContent del) "✕")
            (.addEventListener del "click" (fn [_] (delete-mark! i)))
            (.appendChild row sw)
            (.appendChild row inp)
            (.appendChild row del)
            (.appendChild list-el row)))
        (.appendChild box list-el)
        (set! (.-className actions) "eaq-pnp-actions")
        (let [undo (.createElement js/document "button")
              clr (.createElement js/document "button")
              exit (.createElement js/document "button")]
          (set! (.-type undo) "button")
          (set! (.-textContent undo) "Annulla ultimo (⌫)")
          (set! (.-disabled undo) (zero? (count ms)))
          (.addEventListener undo "click" (fn [_] (undo-mark!)))
          (set! (.-type clr) "button")
          (set! (.-textContent clr) "Azzera")
          (set! (.-disabled clr) (zero? (count ms)))
          (.addEventListener clr "click" (fn [_] (clear-marks!)))
          (set! (.-type exit) "button")
          (set! (.-textContent exit) "Esci (k)")
          (.addEventListener exit "click" (fn [_] (stop-mark!)))
          (.appendChild actions undo)
          (.appendChild actions clr)
          (.appendChild actions exit))
        (.appendChild box actions)))))

(defn- update-panel! []
  ;; Focale is a phase-0-only control (see build-panel!'s hint): the lens
  ;; doesn't change between photos, so re-tuning it later would silently
  ;; rescale a photo the user thinks is already locked in. Disabled, not
  ;; hidden, so it's clear it isn't gone, just not this photo's job.
  (render-pnp-panel!)
  (render-retrace-panel!)
  (render-mark-panel!)
  (when-let [^js slider (:focal-slider-el @session)]
    (set! (.-disabled slider) (not (zero? (:current-idx @session)))))
  (when-let [^js message (:message-el @session)]
    (set! (.-textContent message) (or (:status-message @session) "")))
  (when-let [strip (:filmstrip-el @session)]
    (set! (.-innerHTML strip) "")
    (doseq [[i {:keys [file]}] (map-indexed vector (:photos @session))]
      (let [btn (.createElement js/document "button")
            result (get-in @session [:acquire-results i])
            confirmed? (:rms-px result)
            predicted? (and result (:predicted? result))]
        (set! (.-type btn) "button")
        (set! (.-textContent btn) (if confirmed?
                                    (str (inc i) " · " (.toFixed (:rms-px result) 1) "px")
                                    (str (inc i))))
        (set! (.-title btn) file)
        (.add (.-classList btn) (cond confirmed? "eaq-badge-ok"
                                      predicted? "eaq-badge-predicted"
                                      :else "eaq-badge-none"))
        (when (= i (:current-idx @session))
          (.add (.-classList btn) "current"))
        (.addEventListener btn "click" (fn [_] (enter-photo! i)))
        (.appendChild strip btn)))))

;; ============================================================
;; Keyboard: [ / ] to move through the filmstrip (works in every mode — in
;; :retrace it IS the live-reprojection control), 's' edge-snap, 'f' joint
;; turntable fit, 'p' PnP, 'd' plane-retrace, Backspace undo a retrace point,
;; Escape backs out of the current mode (then closes).
;; ============================================================

(defn- input-el? [^js el]
  (and el (boolean (#{"INPUT" "TEXTAREA"} (.-tagName el)))))

(defn- on-keydown [^js e]
  ;; Bail while a text field is focused (the mark-id inputs) so typing a name —
  ;; letters, digits, Backspace — edits the field instead of firing a hotkey.
  (when (and @session (not (input-el? (.-activeElement js/document))))
    (let [key (.-key e)
          n (count (:photos @session))
          idx (:current-idx @session)
          pnp? (= :pnp (:mode @session))
          retrace? (= :retrace (:mode @session))
          marker? (= :marker (:mode @session))
          mark? (= :mark (:mode @session))]
      (cond
        ;; Escape backs out of the active sub-mode first (one step at a time),
        ;; rather than tearing the whole session down mid-registration/retrace.
        (= key "Escape")
        (do (.preventDefault e) (.stopPropagation e)
            (cond pnp? (stop-pnp!) retrace? (stop-retrace!) marker? (stop-marker!)
                  mark? (stop-mark!) :else (discard!)))

        ;; 'p' toggles PnP from gizmo/pnp; inert during retrace/mark (exit first)
        (and (not retrace?) (not mark?) (= key "p"))
        (do (.preventDefault e) (.stopPropagation e)
            (if pnp? (stop-pnp!) (start-pnp!)))

        ;; 'd' toggles the plane-retrace from gizmo/retrace; inert during pnp/mark
        (and (not pnp?) (not mark?) (= key "d"))
        (do (.preventDefault e) (.stopPropagation e)
            (if retrace? (stop-retrace!) (start-retrace!)))

        ;; 'k' toggles the named-mark mode from gizmo/mark; inert during pnp/retrace
        (and (not pnp?) (not retrace?) (not marker?) (= key "k"))
        (do (.preventDefault e) (.stopPropagation e)
            (if mark? (stop-mark!) (start-mark!)))

        (and retrace? (= key "Backspace"))
        (do (.preventDefault e) (.stopPropagation e) (undo-retrace-point!))

        ;; 'n' starts a new ricalco (retrace mode only) — keeps the current face
        (and retrace? (= key "n"))
        (do (.preventDefault e) (.stopPropagation e) (new-ricalco!))

        (and mark? (= key "Backspace"))
        (do (.preventDefault e) (.stopPropagation e) (undo-mark!))

        ;; 'v' hides/shows the solid proxy in the main (gizmo) view so the photo
        ;; is readable while registering — meaningless in :retrace (no proxy) and
        ;; :pnp (already a see-through wireframe), so gizmo-only.
        (and (= :gizmo (:mode @session)) (= key "v"))
        (do (.preventDefault e) (.stopPropagation e) (toggle-proxy!))

        ;; 'm' arms the blindato marker-click (pin the Klein branch by the
        ;; physical mark). Toggles from gizmo/marker; inert during pnp/retrace/mark.
        (and (not pnp?) (not retrace?) (not mark?) (= key "m"))
        (do (.preventDefault e) (.stopPropagation e)
            (if marker? (stop-marker!) (start-marker!)))

        (and pnp? (= key "r"))
        (do (.preventDefault e) (.stopPropagation e) (on-solve-pnp!))

        (and pnp? (re-matches #"[1-8]" key))
        (do (.preventDefault e) (.stopPropagation e)
            (arm-corner! (dec (js/parseInt key 10))))

        (= key "[")
        (do (.preventDefault e) (.stopPropagation e)
            (enter-photo! (mod (dec idx) n)))

        ;; 's'/'f' act on the gizmo/camera registration — meaningless (and
        ;; disruptive to the frozen pose) during a retrace/mark, so gate them out.
        (and (not retrace?) (not mark?) (= key "s"))
        (do (.preventDefault e) (.stopPropagation e) (on-snap!))

        (and (not retrace?) (not mark?) (= key "f"))
        (do (.preventDefault e) (.stopPropagation e) (on-fit-turntable!))

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
                     (map (fn [[idx {:keys [matched rms-px manual?]}]]
                            [(str idx)
                             (cond-> {:matched matched :rms-px rms-px}
                               ;; Persist :manual? so a hand-placed camera comes
                               ;; back as REGISTERED (registered-result?) after a
                               ;; reload — otherwise a later proxy nudge on photo
                               ;; 0 would drop it as if it were a bare seed
                               ;; (transport-registered-cameras!).
                               manual? (assoc :manual? true)
                               (pos? idx) (assoc :camera-pose (get-in @session [:camera-poses idx])))])
                          (:acquire-results @session)))
        ;; P4a-2 — the PnP CORRESPONDENCES (the per-photo corners the user clicked)
        ;; plus the solve's residuals/outliers, keyed by photo idx. Unlike 's'
        ;; edge-snap (which re-derives its edges from the camera pose each time), a
        ;; PnP registration IS its clicks: the camera pose already round-trips, but
        ;; without the picks a reopened photo can't show or refine the
        ;; correspondences — you'd re-click from scratch. These are the design's
        ;; "osservazioni-di-mark" (the per-photo rays). clj->js stringifies the
        ;; integer photo AND corner keys; apply-loaded-state! parses both back.
        pnp (into {}
                  (for [[idx picks] (:pnp-picks @session) :when (seq picks)]
                    [(str idx)
                     (cond-> {:picks picks}
                       (seq (get-in @session [:pnp-residuals idx]))
                       (assoc :residuals (get-in @session [:pnp-residuals idx]))
                       (seq (get-in @session [:pnp-outliers idx]))
                       (assoc :outliers (vec (get-in @session [:pnp-outliers idx]))))]))
        body (js/JSON.stringify (clj->js {:proxy-pose proxy-pose :photos photos
                                          ;; Photo 0's camera pose, saved explicitly:
                                          ;; the per-photo `photos` map only carries
                                          ;; camera poses for idx>0, and photo 0's is
                                          ;; otherwise RECOMPUTED at re-entry from the
                                          ;; (now-aligned) pivot via default-vantage-
                                          ;; pose — a different vantage than the frozen
                                          ;; one aligned against, so the proxy came back
                                          ;; translated by however far the pivot moved
                                          ;; (Vincenzo 2026-07-25: "la uno traslata di
                                          ;; diversi mm"). Persisting it keeps the frozen
                                          ;; vantage across sessions.
                                          :camera-pose-0 (get-in @session [:camera-poses 0])
                                          ;; Ricalchi (P4a-3): every named polyline
                                          ;; (plane spec + object-frame points) +
                                          ;; the active index, so they survive exit/
                                          ;; re-entry (object frame = stable under
                                          ;; later proxy moves)
                                          :ricalchi (:ricalchi @session)
                                          :ricalco-idx (:ricalco-idx @session)
                                          ;; Blindato marker picks (pixel per photo)
                                          ;; — the durable branch decision; re-applied
                                          ;; to every future registration via
                                          ;; marker-lock-camera.
                                          :marker-picks (:marker-picks @session)
                                          ;; P4a-2 — PnP correspondences per photo
                                          ;; (see the `pnp` binding above).
                                          :pnp pnp
                                          ;; P4a-2 — lens focal (35mm-equiv) +
                                          ;; provenance, so a manual tweak survives
                                          ;; re-entry instead of reverting to the
                                          ;; EXIF/default read (load-exif-focal!
                                          ;; runs before load-acquire-state!).
                                          :focal {:mm (:focal-mm @session)
                                                  :source (:focal-source @session)}
                                          ;; P4a-3 — named marks (object-frame
                                          ;; position + normal + id) and the
                                          ;; current mark face, so they survive
                                          ;; exit/re-entry like the retrace does.
                                          :marks (:marks @session)
                                          :mark-plane (:mark-plane @session)}))
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
    (let [{:keys [proxy-pose camera-pose-0 photos retrace ricalchi ricalco-idx marker-picks pnp focal marks mark-plane]} (js->clj (js/JSON.parse text) :keywordize-keys true)
          ;; JSON keys are strings → keywordize-keys turns the integer photo/corner
          ;; keys into :0/:1/… ; parse a whole level back to int keys.
          int-keys (fn [m] (into {} (map (fn [[k v]] [(js/parseInt (name k) 10) v]) m)))
          norm-plane (fn [pl] {:axis (:axis pl) :sign (:sign pl) :offset (or (:offset pl) 0.0)})]
      ;; Ricalchi (P4a-3). Back-compat: a file saved with the old single :retrace
      ;; is migrated to a one-element list named ricalco-1.
      (cond
        (seq ricalchi)
        (swap! session assoc
               :ricalchi (mapv (fn [r] {:name (:name r)
                                        :plane (norm-plane (:plane r))
                                        :points (mapv vec (or (:points r) []))})
                               ricalchi)
               :ricalco-idx (or ricalco-idx (dec (count ricalchi))))

        (and retrace (:plane retrace) (:axis (:plane retrace)))
        (swap! session assoc
               :ricalchi [{:name "ricalco-1"
                           :plane (norm-plane (:plane retrace))
                           :points (mapv vec (or (:points retrace) []))}]
               :ricalco-idx 0))
      ;; Blindato marker picks — JSON stringifies the integer photo keys, so
      ;; keywordize-keys turns them into :1/:2/… ; back to ints for :current-idx
      ;; lookups (marker-lock-camera / on-marker-click!).
      (when marker-picks
        (swap! session assoc :marker-picks
               (into {} (map (fn [[k v]] [(js/parseInt (name k) 10) (vec v)]) marker-picks))))
      ;; P4a-2 — PnP correspondences per photo (picks/residuals/outliers). Both the
      ;; photo idx and, inside :picks/:residuals, the corner idx were integer keys;
      ;; parse both levels. Outliers persisted as a vector → back to a set.
      (when pnp
        (doseq [[idx-kw {:keys [picks residuals outliers]}] pnp]
          (let [idx (js/parseInt (name idx-kw) 10)]
            (when (seq picks)     (swap! session assoc-in [:pnp-picks idx] (int-keys picks)))
            (when (seq residuals) (swap! session assoc-in [:pnp-residuals idx] (int-keys residuals)))
            (when (seq outliers)  (swap! session assoc-in [:pnp-outliers idx] (set outliers))))))
      ;; P4a-2 — lens focal. Restored AFTER load-exif-focal! (which ran first), so a
      ;; saved manual tweak wins; an unchanged EXIF/default value restores to itself.
      (when-let [mm (:mm focal)]
        (swap! session assoc :focal-mm mm :focal-source (keyword (:source focal))))
      ;; P4a-3 — named marks + the current mark face. Marks are object-frame
      ;; (position/normal vectors) + a string id; coerce the vectors back.
      (when (seq marks)
        (swap! session assoc :marks
               (mapv (fn [m] {:name (:name m)
                              :position (vec (:position m))
                              :normal (vec (:normal m))})
                     marks)))
      (when mark-plane
        (swap! session assoc :mark-plane
               {:axis (:axis mark-plane) :sign (:sign mark-plane)
                :offset (or (:offset mark-plane) 0.0)}))
      ;; Restore photo 0's frozen vantage so enter-photo! 0 uses it instead of
      ;; recomputing from the aligned pivot (which shifted the proxy on re-entry).
      (when camera-pose-0
        (swap! session assoc-in [:camera-poses 0] camera-pose-0))
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
      (doseq [[idx-kw {:keys [camera-pose matched rms-px manual?]}] photos]
        (let [idx (js/parseInt (name idx-kw))]
          (cond
            (zero? idx)
            (swap! session assoc-in [:acquire-results idx] {:matched matched :rms-px rms-px})

            camera-pose
            (do (swap! session assoc-in [:camera-poses idx] camera-pose)
                (swap! session assoc-in [:acquire-results idx]
                       (cond-> {:matched matched :rms-px rms-px}
                         manual? (assoc :manual? true))))

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
;; `acquire` — the emitted directive (P4a-1). Reference-citizen shape of the
;; image-board/mesh-board family: mounts the posed proxy as scaffold (shown,
;; never CSG/export) and RETURNS the acquired structure, destructurable by name
;; like split-tree's output. The round-trip's downstream half: edit-acquire's
;; confirm! rewrites its marker to one of these.
;; ============================================================

(defn- record-scaffolds!
  "Push reference-citizen meshes into the per-eval scaffold accumulator (mirrors
   mesh-board/record-scaffolds! — scaffolds are visualized, never exported or
   fed to CSG through the language itself)."
  [meshes]
  (let [meshes (filterv identity meshes)]
    (when (seq meshes)
      (swap! state/scene-accumulator update :scaffolds into meshes))))

(defn- apply-pose
  "Rigidly move `proxy` from its creation-pose to `pose` (position+heading+up),
   returning the mesh whose creation-pose IS `pose`. up is orthogonalized against
   heading first, so a hand-written/edited pose that isn't exactly perpendicular
   can't turn the rigid transform into a shear (same guard box-basis and
   apply-loaded-state! already apply)."
  [proxy pose]
  (let [cp (:creation-pose proxy)
        h (m/normalize (:heading pose))
        u (m/orthogonalize-up h (:up pose))
        [moved] (attachment/group-transform
                 [proxy]
                 (:position cp) (:heading cp) (:up cp)
                 (:position pose) h u)]
    moved))

(defn- default-proxy
  "A default box built the SAME way the emitted (box w h d) is — pure-box applies
   the turtle transform (local x→right, y→up, z→heading) at the default pose, and
   box-basis/dims-from-mesh read that convention. Building it from prims/box-mesh
   alone (raw local-frame vertices) would permute the axes, so dims-from-mesh
   would emit a permuted (box …). Applying the identity/default transform here
   matches transform-mesh-to-turtle at the default pose, so a bare-open box
   round-trips to its own dims."
  []
  (let [[w h d] default-proxy-dims
        m (prims/box-mesh w h d)]
    (assoc m :vertices (prims/apply-transform (:vertices m) [0 0 0] [1 0 0] [0 0 1]))))

(defn- resolve-proxy
  "The proxy mesh for an acquire/edit-acquire opts map: the given :proxy moved to
   :pose, or a default box (kept at origin for the user to align) when none is
   supplied."
  [{:keys [proxy pose]}]
  (let [m (or proxy (default-proxy))]
    (if pose (apply-pose m pose) m)))

(defn ^:export acquire
  "(acquire \"dir\") / (acquire \"dir\" {:proxy (box …) :pose {…} :shapes {} :marks {}})
   — the self-contained acquisizione-parametrica form (P4a). Mounts the posed
   proxy as reference scaffold and returns {:proxy :pose :shapes :marks :dir},
   accessed by name (`(:proxy A)`, `(:bezel (:shapes A))`) exactly like
   split-tree's output — no new downstream API. In P4a-1 :shapes/:marks are the
   caller's (usually empty) maps; only proxy+pose round-trip. dir is retained for
   P4b (the in-pose film)."
  ([dir] (acquire dir nil))
  ([dir opts]
   (let [posed (resolve-proxy opts)]
     (record-scaffolds! [posed])
     {:proxy posed
      :pose (or (:pose opts) (:creation-pose posed))
      :shapes (or (:shapes opts) {})
      :marks (or (:marks opts) {})
      :dir dir})))

;; ============================================================
;; Entry / exit
;; ============================================================

(defn- close!
  "Pure teardown: tears the session down and releases the modal slot WITHOUT
   writing the source buffer. This is the lifecycle discard path — bound to
   :cancel! (a fresh REPL eval) and :close! (before a Run) in register-kind!, and
   used by the source-writing confirm!/cancel! once they've done their rewrite."
  []
  (when @session
    (stop-pnp!) ; removes the canvas pointer handler + placed-marker overlay
    (stop-marker!) ; removes the marker-click canvas pointer handler
    (stop-mark!) ; removes the named-mark pointer/wheel handlers + labels
    (teardown-retrace-listeners!) ; removes retrace pointer/wheel handlers + loupe
    ;; The ricalco is no longer printed loose here (P4a-3): confirm! folds it into
    ;; the acquire form's :shapes via emit-acquire-code; discard/cancel emit nothing.
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

;; ------------------------------------------------------------
;; Source write-back (P4a-1): the edit-acquire ↔ acquire round-trip
;; ------------------------------------------------------------

(defn- fmt-n
  "Round to 4 decimals, drop trailing zeros, integers as ints — compact source."
  [x]
  (let [r (/ (js/Math.round (* (double x) 10000)) 10000)]
    (if (= r (js/Math.floor r)) (str (long r)) (str r))))

(defn- fmt-vec [[a b c]] (str "[" (fmt-n a) " " (fmt-n b) " " (fmt-n c) "]"))

(defn- find-marker []
  (modal/find-form-bounds (cm/get-value) marker-prefix))

(defn- normal-up-obj
  "An in-plane object-frame `up` for a face `normal` (an axis unit): the next box
   axis, so heading(=normal)⊥up. Gives the mark a full, deterministic frame."
  [normal]
  (let [a (or (some (fn [i] (when (> (js/Math.abs (nth normal i 0)) 0.5) i)) [0 1 2]) 0)]
    (assoc [0.0 0.0 0.0] (mod (inc a) 3) 1.0)))

(defn- marks-emit-string
  "The session's named marks as a source map {:id {:position [world] :heading
   [world] :up [world]} …} lifted through `pose` (the emitted proxy's anchor pose)
   — a POSE (not a bare point) so it plugs straight into `(turtle (:id (:marks A))
   …)` and the anchor machinery (position + orientation). :heading = the face
   normal (out of the surface), :up = an in-plane box axis. Object-frame
   position/normal lifted to world through the SAME pose the box is emitted at, so
   marks and proxy stay coincident wherever the object is anchored. Names are
   keywordized and uniquified (a map can't hold duplicate keys, and a user may
   rename two marks the same)."
  [pose]
  (if (empty? (marks))
    "{}"
    (let [{:keys [ex ey ez]} (bridge/box-basis pose)
          world-dir (fn [[nx ny nz]]
                      (m/normalize (m/v+ (m/v* ex nx) (m/v+ (m/v* ey ny) (m/v* ez nz)))))
          seen (atom #{})
          uniq (fn [nm] (loop [n (if (seq nm) nm "mark")]
                          (if (contains? @seen n)
                            (recur (str n "-2"))
                            (do (swap! seen conj n) n))))]
      (str "{"
           (str/join " "
                     (map (fn [{:keys [name position normal]}]
                            (str ":" (uniq name)
                                 " {:position " (fmt-vec (bridge/local->world pose position))
                                 " :heading " (fmt-vec (world-dir normal))
                                 " :up " (fmt-vec (world-dir (normal-up-obj normal))) "}"))
                          (marks)))
           "}"))))

(defn- emit-acquire-code
  "The (acquire \"dir\" {…}) source that replaces the marker on confirm. Proxy dims
   come from the mesh's ACTUAL extents (bridge/dims-from-mesh, robust to how it was
   parameterized). The emitted pose keeps the ACQUIRED orientation but re-anchors
   the box CENTRE to the construction turtle's position at open time (:build-pose)
   — so the object lands near the turtle/origin in the build world instead of the
   arbitrary acquisition-frame offset (Vincenzo 2026-07-24), while re-entry still
   overlays the photos (edit-acquire restores the acquired position from the
   session file). :shapes carries the ricalco as a named (poly …), :marks the named
   points as poses; both destructurable by name, both lifted through the SAME
   anchor pose so they stay coincident with the proxy (P4a-3)."
  []
  (let [proxy (:proxy-mesh @session)
        pose (:creation-pose proxy)                 ; acquired pose (orientation kept)
        anchor-pose {:position (get-in @session [:build-pose :position] [0 0 0])
                     :heading (:heading pose) :up (:up pose)}
        [w h d] (bridge/dims-from-mesh proxy pose)
        shapes-str (shapes-emit-string)]
    (str "(acquire " (pr-str (:base-dir @session))
         " {:proxy (box " (fmt-n w) " " (fmt-n h) " " (fmt-n d) ")"
         " :pose {:position " (fmt-vec (:position anchor-pose))
         " :heading " (fmt-vec (:heading anchor-pose))
         " :up " (fmt-vec (:up anchor-pose)) "}"
         " :shapes " shapes-str " :marks " (marks-emit-string anchor-pose) "})")))

(defn- confirm!
  "OK: write the aligned proxy+pose back to source as (acquire \"dir\" {…}),
   replacing the (edit-acquire …) marker, then tear down and re-run the
   definitions so the emitted form renders. With no marker in source (a legacy
   REPL open, from-marker? false), there's nothing to rewrite — print the form so
   the dev can copy it, then discard."
  []
  (when @session
    (save-acquire-state!) ; keep acquire-state.json's proxy-pose in sync with :pose
    (let [[from to] (find-marker)
          code (emit-acquire-code)]
      (if from
        (do (modal/replace-source! from to code)
            (close!)
            (modal/run-definitions!))
        (do (state/capture-println (str ";; acquire — nessun marcatore in sorgente, forma da copiare:\n" code))
            (close!))))))

(defn- cancel!
  "Annulla (marker path): rewrite only the marker's head — (edit-acquire …) →
   (acquire …) — leaving the body as typed (family head-rename grammar), then
   re-run. On a bare first open the body is just the dir string, so it strips to
   a valid (acquire \"dir\") that mounts a default box."
  []
  (when @session
    (let [[from to] (find-marker)]
      (if from
        (do (modal/replace-source! from to
                                   (modal/strip-head (cm/get-value) from to marker-prefix "(acquire"))
            (close!)
            (modal/run-definitions!))
        (close!)))))

(defn- discard!
  "The panel's Chiudi button / Escape-in-gizmo dispatcher: strip-head the marker
   (marker path) or plain teardown (legacy REPL open)."
  []
  (if (:from-marker? @session) (cancel!) (close!)))

;; ------------------------------------------------------------
;; Session mount + entry points
;; ------------------------------------------------------------

(defn- open-session!
  "Shared async session mount: read session.json, install the frozen-camera frame
   callback, seed the session atom (carrying `from-marker?` for the confirm/cancel
   dispatch and `build-pose` — the construction-turtle pose at open time, so the
   emitted object anchors near the turtle instead of at the arbitrary acquisition-
   frame origin), restore acquire-state.json, build the panel, enter photo 0. The
   modal slot must already be claimed by the caller (enter!/request!)."
  [proxy-mesh session-dir from-marker? build-pose]
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
                                  ;; build-pose = the construction turtle's pose
                                  ;; when edit-acquire ran; emit-acquire-code
                                  ;; anchors the emitted proxy's center here (its
                                  ;; :position) instead of the arbitrary
                                  ;; acquisition-frame origin (Vincenzo 2026-07-24:
                                  ;; "il centro del proxy = posizione della turtle
                                  ;; al momento di acquire").
                                  :build-pose (or build-pose {:position [0 0 0]})
                                  ;; from-marker? routes the panel's Chiudi/Esc
                                  ;; to cancel! (strip-head the (edit-acquire …)
                                  ;; marker) vs close! (plain discard for a
                                  ;; legacy REPL open with no marker in source).
                                  :from-marker? from-marker?
                                  :current-idx 0
                                  :proxy-mesh proxy-mesh
                                  :camera-poses {}
                                  :acquire-results {}
                                  :mode :gizmo
                                  :pnp-picks {}
                                  :pnp-residuals {}
                                  :pnp-outliers {}
                                  :pnp-armed 0
                                  :pnp-loupe-zoom loupe-zoom-default
                                  ;; Ricalchi (P4a-3): a named polyline per traced
                                  ;; feature; the first is created on entering the
                                  ;; retrace ('d') mode (ensure-active-ricalco!).
                                  ;; Overwritten by acquire-state.json on re-entry.
                                  :ricalchi []
                                  :ricalco-idx nil
                                  ;; P4a-3 named marks: the placed points (object
                                  ;; frame + normal + id) and the face for the NEXT
                                  ;; mark (its own plane, so switching it never
                                  ;; clears the ricalco). Overwritten on re-entry
                                  ;; by acquire-state.json (load-acquire-state!).
                                  :marks []
                                  :mark-plane {:axis 1 :sign 1 :offset 0.0}
                                  :hide-proxy? false ; 'v' toggle in :retrace
                                  :focal-mm default-focal-mm
                                  :focal-source :default
                                  :panel-el nil
                                  :filmstrip-el nil
                                  :message-el nil
                                  :status-message nil
                                  :status-msg-timer nil
                                  :key-handler nil})
                 ;; Read the lens focal from photo 0's EXIF BEFORE the first
                 ;; photo loads, so the backdrop and every projection start at
                 ;; the photo's true scale (not the 48mm default). Then restore
                 ;; a previously-snapped proxy pose/camera poses/badges, if
                 ;; acquire-state.json exists — a no-op (resolves anyway) on a
                 ;; fresh session.
                 (-> (load-exif-focal! (first photos))
                     (.then (fn [_] (load-acquire-state!)))
                     (.then (fn [_]
                              (viewport/show-preview! (proxy-preview-items))
                              (backdrop/create! (viewport/get-camera))
                              (build-panel!)
                              (swap! session assoc :key-handler (modal/install-keydown! on-keydown))
                              (enter-photo! 0)
                              (report-focal!)))))))
      (.catch (fn [err]
                (state/capture-println (str "edit-acquire: couldn't load session — " err))
                (modal/release!)))))

(defn ^:export enter!
  "Legacy REPL/programmatic entry: (edit-acquire proxy-mesh session-dir). Opens
   the session directly with an explicit proxy mesh and NO source marker. Kept in
   parallel with the marker path (request!) through the P4a transition — the
   `edit-acquire` macro dispatches here when its first arg is a proxy form rather
   than the dir string. See the namespace docstring for the interaction design."
  [proxy-mesh session-dir]
  (modal/claim! :edit-acquire)
  (open-session! proxy-mesh session-dir false (state/get-turtle-pose)))

(defn ^:export request!
  "Marker entry for (edit-acquire \"dir\" [opts]). Opens the acquire session from
   the definitions panel (Cmd+Enter) — like the rest of the edit-* family — and
   returns the acquire structure for the eval's value. `opts` (nil on a bare
   first open) is the {:proxy :pose :shapes :marks} map: the proxy is moved to
   :pose (or a default box when absent). Refuses to open outside a definitions
   run (the marker has no meaning in the REPL), and requires the marker to be
   locatable so confirm!/cancel! can rewrite it."
  [dir & more]
  (let [opts (first more)]
    (cond
      (modal/consume-skip!)
      (acquire dir opts)

      (not= :definitions @state/eval-source-var)
      (do (state/capture-println
           "edit-acquire: aprilo dal pannello definizioni (Cmd+Enter), non dal REPL")
          (acquire dir opts))

      :else
      (let [posed (resolve-proxy opts)
            ;; the construction turtle's pose right now — captured before the
            ;; session hides geometry, so the emitted object anchors here.
            build-pose (state/get-turtle-pose)]
        (when (nil? (find-marker))
          (throw (js/Error. (str "edit-acquire: non trovo '" marker-prefix " …)' nell'editor"))))
        (modal/claim! :edit-acquire)
        (open-session! posed dir true build-pose)
        {:proxy posed
         :pose (:creation-pose posed)
         :shapes (or (:shapes opts) {})
         :marks (or (:marks opts) {})
         :dir dir}))))

(defn ^:dev/after-load reinstall-after-hot-reload!
  "Dev-only. A shadow-cljs hot code-swap re-runs redraws (so a preview tweak shows
   at once) but NOT enter!, so a session opened before the swap keeps the OLD
   keydown listener and the OLD panel DOM — a newly added key or panel button
   never appears without a full page reload + re-run of `edit-acquire`. That gap
   is what made a live `v`/'Nascondi proxy' change look broken during tuning
   (2026-07-23) when only a hot-reload had happened. So: when a session is open,
   re-arm the keydown with the current `on-keydown` and re-render the panel with
   the current renderers. No-op with no session open (the usual case); stripped
   entirely from release builds (^:dev/after-load)."
  []
  (when @session
    (when-let [h (:key-handler @session)] (modal/remove-keydown! h))
    (swap! session assoc :key-handler (modal/install-keydown! on-keydown))
    ;; also re-render the current mode's preview, so a hot-swapped *-preview-items
    ;; (e.g. a dot size or a hide-proxy rule) shows without needing a manual redraw
    (case (:mode @session)
      :retrace (redraw-retrace!)
      :mark (redraw-marks!)
      :pnp (do (redraw-pnp-preview!) (redraw-overlay-dots!))
      (viewport/show-preview! (proxy-preview-items)))
    (update-panel!)))

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
