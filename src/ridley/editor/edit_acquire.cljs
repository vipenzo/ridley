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
            [cljs.reader :as reader]
            [ridley.editor.modal-evaluator :as modal]
            [ridley.editor.codemirror :as cm]
            [ridley.editor.source-edit :as src]
            [ridley.editor.gizmo :as gizmo]
            [ridley.editor.acquire-backdrop :as backdrop]
            [ridley.editor.acquire-stage :as stage]
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
            [ridley.photogrammetry.fuse :as fuse]
            [ridley.photogrammetry.bundle :as bundle]
            [ridley.photogrammetry.blob :as blob]
            [ridley.photogrammetry.blob-detect :as blob-detect]
            [ridley.photogrammetry.match-plate :as match-plate]
            [ridley.photogrammetry.match :as match]
            [ridley.photogrammetry.turntable-fit :as tt]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.bootstrap :as boot]
            [ridley.photogrammetry.note :as note]
            [ridley.photogrammetry.curve :as pcurve]
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

(def ^:private crown-dot-color 0x66ddff)   ; azzurro: i dischetti della corona
(def ^:private zero-dot-color 0xffaa33)    ; arancio: lo zero-indice

(defn- plate-crown-item
  "The plate's crown of discs as small dots, drawn whenever the proxy carries
   :anchors.

   A registration plate is a featureless disc: its wireframe gives the eye almost
   nothing to line up against the photo, while the printed discs are the one
   thing clearly visible in it. Without them there is no way to align the proxy
   BY HAND (Vincenzo 2026-08-01) — the crown only ever appeared in the 'p'
   picking mode, which is no help when what you are doing is dragging the gizmo.

   The zero-index gets its own colour, because it is what breaks the crown's
   12-fold symmetry: with it you can tell not just where the plate is but which
   way round. Drawn even when the solid proxy is hidden ('v'), since hiding it to
   read the photo is exactly when these are wanted."
  []
  (when-let [as (seq (:anchors (:proxy-mesh @session)))]
    {:type :dots
     :data (mapv (fn [[k a]]
                   {:pos (:position a)
                    :radius (if (= k :zero) 1.3 1.0)
                    :color (if (= k :zero) zero-dot-color crown-dot-color)
                    :opacity 0.9})
                 as)}))

(defn- proxy-preview-items
  "The proxy mesh, plus the traced bezels and placed named marks (appended so they
   stay visible after leaving :retrace/:mark and reproject as the camera moves).
   The SOLID proxy is under the 'v' / 'Nascondi proxy' toggle (Vincenzo 2026-07-23):
   while registering it covers the photo, so it can be dropped to read the photo
   underneath. Marker dots pared back (Vincenzo 2026-07-24: 'ce ne sono troppi'):
   the white pivot dot is gone (it never earned its keep), and the red Klein-branch
   corner dot shows ONLY during the 'm' marker gesture — the one time it's needed to
   compare the wireframe against the physical pen mark (start-marker! re-renders so
   it appears; stop-marker! clears it)."
  []
  (let [red-corner (when (= :marker (:mode @session))
                     {:type :dots :data [{:pos (corner-marker-pos) :radius 3.0 :color 0xff3333}]})
        crown (plate-crown-item)
        base (cond-> (if (:hide-proxy? @session)
                       []
                       [{:type :mesh :data (:proxy-mesh @session)}])
               crown (conj crown)
               red-corner (conj red-corner))]
    (conj (into base (trace-items))
          (mark-dots-item))))

;; ------------------------------------------------------------
;; P4b — frustum nel mondo (brief "Le foto, in tre stati", stato 1)
;; Le camere registrate come piramidi ghost alla loro posa vera, per leggere la
;; copertura del giro a colpo d'occhio. Riferimento puro (mai pick/export). Solo in
;; orbita libera, sotto un interruttore (:show-frustums?). ⚠ Ergonomia (ingombro)
;; DA COLLAUDARE — fallback: la sola pellicola.
;; ------------------------------------------------------------

;; Frustum geometry + colours now live in ridley.editor.acquire-stage (the shared,
;; session-free stage module the eval-driven palcoscenico will own); edit-acquire's
;; in-session stage still draws them here via frustum-items.
(def ^:private frustum-ghost-color stage/frustum-ghost-color)
(def ^:private frustum-current-color stage/frustum-current-color)

(defn- frustum-items
  "Every registered camera (:camera-poses) as a ghost pyramid at its world pose.
   Empty when :show-frustums? is off. Depth scales with the box (~1.2× its bounding
   radius) so the pyramids read as compact icons for any object size; FOV from the
   session focal + the loaded photo's aspect, so a pyramid opens like its lens. Each
   camera emits TWO items: the visible :lines pyramid, and an invisible solid twin
   (frustum-pick-mesh) tagged with its idx so a click flies into that pose."
  []
  (when (:show-frustums? @session)
    (let [dims (bridge/dims-from-mesh (:proxy-mesh @session)
                                      (get-in @session [:proxy-mesh :creation-pose]))
          depth (* 1.2 0.5 (m/magnitude dims))
          aspect (if-let [[w h] (backdrop/image-size)] (/ w h) (/ 4.0 3.0))
          hfov (pcamera/equiv-focal->hfov-deg (:focal-mm @session) aspect)
          half-w (* depth (Math/tan (* 0.5 hfov (/ Math/PI 180.0))))
          half-h (/ half-w aspect)
          cur (:current-idx @session)]
      (into []
            (mapcat (fn [[idx pose]]
                      [{:type :lines
                        :data (stage/frustum-edges pose depth half-w half-h
                                                   (if (= idx cur) frustum-current-color frustum-ghost-color))}
                       (stage/frustum-pick-mesh pose depth half-w half-h idx)]))
            (:camera-poses @session)))))

(defn- stage-free-preview-items
  "Free-orbit stage preview: the object (proxy + ricalchi + marks) plus the ghost
   camera frustums. Used ONLY in free orbit (enter-stage!/leave-pose!); the in-pose
   and Phase-1 previews stay proxy-preview-items (no frustums)."
  []
  (into (proxy-preview-items) (frustum-items)))

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

(defn- plate-proxy?
  "True when the session's proxy is a registration PLATE (carries named marks
   under :anchors) rather than a box. A plate registers PER-PHOTO via PnP ('p')
   on its marks, so the box-only gestures — edge-snap ('s') and the turntable
   joint fit ('f') — don't apply: 's' would snap the plate's meaningless
   bounding-box edges (and on photo 0 shove the proxy off its mark-derived
   pose), 'f' has no turntable angle to fit (plate photos are all out-of-ring)."
  []
  (boolean (seq (:anchors (:proxy-mesh @session)))))

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

(defn- free-photo?
  "True when photo `idx` is out-of-ring (θ `libera` in the NOTE): it has NO
   turntable angle, so it registers ONLY via PnP ('p') and must never enter the
   turntable model — not the joint fit ('f'), not its predictions, not the
   per-photo θ-orbit seed. Guarding here keeps the NOTE's 'MAI inglobarle nel
   modello giradischi' true even if such a photo were also edge-snapped."
  [idx]
  (nil? (:theta (nth (:photos @session) idx nil))))

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
            :when (and (not (free-photo? idx)) ; never predict an out-of-ring photo's pose
                       (not (registered-result? (get-in @session [:acquire-results idx]))))]
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
          ;; Only ring photos feed the turntable model: an out-of-ring photo
          ;; (θ nil) has no angle to fit against, and folding it in would move
          ;; the whole model (the NOTE's guard). when-let skips only nil — θ=0
          ;; is a valid ring angle and stays.
          photos-with-picks (vec (keep (fn [[idx result]]
                                         (when-let [picks (:picks result)]
                                           (when-let [theta (:theta (nth (:photos @session) idx))]
                                             {:picks picks :theta-deg theta})))
                                       (:acquire-results @session)))
          ;; snaps that exist but are on out-of-ring (θ nil) photos — excluded
          ;; above by design, but the plain count would then read '0 agganciate'
          ;; and look like 's' failed. Distinguish the two so a plate session
          ;; (every photo θ=libera) is told to use 'p', not that its snaps vanished.
          snapped-total (count (keep (fn [[_ r]] (:picks r)) (:acquire-results @session)))]
      (if (< (count photos-with-picks) min-photos-for-turntable-fit)
        (set-status-message!
         (if (and (pos? snapped-total) (zero? (count photos-with-picks)))
           (str "Fit congiunto non applicabile: le " snapped-total
                " foto agganciate sono tutte fuori-anello (θ=libera). "
                "Registra col PnP: premi 'p' e clicca i mark del piatto, non 's'/'f'.")
           (str "Fit congiunto: servono almeno " min-photos-for-turntable-fit
                " foto agganciate con 's' (ce ne sono " (count photos-with-picks) ")")))
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

(defn- reanchor-to-build-pose!
  "Rigidly translate the whole acquired system so the proxy CENTRE sits at the
   construction turtle's position (:build-pose) — where the emitted (acquire …)
   anchors the object (emit-acquire-code keeps the acquired orientation and only
   re-centres the box). Without this the object floats at the acquisition-frame
   gauge origin (~520 mm off in the taped-block test), which is invisible while
   the camera is locked to a photo but jarring once the P4b stage frees the camera
   (Vincenzo 2026-07-24: 'il proxy dovrebbe essere alla posizione della turtle,
   perché è lì che lo ritroveremo'). A PURE TRANSLATION, so every relative geometry
   is preserved: the photos still project (each camera carried by the same Δ), and
   the object-frame ricalchi / marks / planes follow the proxy for free (they're
   lifted through its creation-pose at draw time). Runs once at open, after
   acquire-state.json is restored; a no-op when already anchored (Δ≈0) or without a
   proxy. Registration stays translation-invariant, so Phase-1 is unaffected."
  []
  (when-let [pose (get-in @session [:proxy-mesh :creation-pose])]
    (let [target (get-in @session [:build-pose :position] [0 0 0])
          delta (m/v- target (:position pose))]
      (when (> (m/magnitude delta) 1e-6)
        (swap! session update :proxy-mesh attachment/translate-mesh delta)
        (swap! session update :camera-poses
               (fn [cps]
                 (into {} (map (fn [[idx p]] [idx (update p :position #(m/v+ % delta))]))
                       cps)))))))

(defn- variance [xs]
  (let [n (count xs) mean (/ (reduce + xs) n)]
    (/ (reduce + (map #(let [d (- % mean)] (* d d)) xs)) n)))

(defn- canonicalize-orientation!
  "Re-orient the whole acquired system to a STANDARD, intuitive pose: the turntable
   axis (physical vertical) → world +Z (up), the object axis-aligned, the camera ring
   horizontal, and the emitted box reads (box right up heading) with up = the VERTICAL
   dimension (Vincenzo 2026-07-24: '(box 20 40 60)', asse Z del giradischi = asse Z del
   modello). A rigid re-description (rotation M + axis relabel): relative geometry is
   preserved (photos still project, cameras carried by M), and the object-frame ricalchi
   / marks / planes are re-expressed so they stay on the same physical spot. Runs at open
   (replacing the translate-only reanchor) ONLY when a clean turntable ring is registered
   — the turntable axis is the box axis along which the cameras barely move (smallest
   spread), and it must clearly dominate (a real ring, not a scatter). Falls back to
   reanchor-to-build-pose! otherwise. Idempotent on an already-canonical session."
  []
  (let [pm (:proxy-mesh @session)
        pose (:creation-pose pm)
        center (:position pose)
        build-pos (get-in @session [:build-pose :position] [0 0 0])
        {:keys [ex ey ez]} (bridge/box-basis pose)
        axes [ex ey ez]
        dims (bridge/dims-from-mesh pm pose)
        cams (vals (:camera-poses @session))
        rels (mapv #(m/v- (:position %) center) cams)]
    (cond
      ;; A PLATE knows its own turntable axis: it IS the plate's axis, built
      ;; along the creation-pose heading (plate.cljs). Asking the camera ring
      ;; instead — which is what the box path must do — makes the guard refuse
      ;; exactly when the answer is certain: on param-plate-paper it IDENTIFIED
      ;; the plate axis correctly and then rejected it, because the shots were
      ;; taken from varying heights (σ≈78mm) and the ring was not planar enough.
      ;; So the object stayed at whatever orientation the solver happened to
      ;; produce (Vincenzo 2026-08-01: 'l'asse del cilindro dovrebbe coincidere
      ;; con l'asse Z').
      ;;
      ;; And a plate needs no axis RELABEL — the box path re-describes the box so
      ;; its dims read (right, up, heading), which for a disc means nothing. All
      ;; that is wanted is the pose: bring the plate to its own identity
      ;; convention (axis = heading = world +Z) at the build position. That is a
      ;; plain rigid move, so the object-frame ricalchi/marks/planes need no
      ;; re-expression at all — their frame's LABELS do not change — and the
      ;; cameras ride along on the transport that already exists.
      (plate-proxy?)
      (let [new-pose {:position build-pos :heading [0.0 0.0 1.0] :up [0.0 1.0 0.0]}]
        (when (> (+ (m/magnitude (m/v- (:position pose) build-pos))
                    (m/magnitude (m/v- (m/normalize (:heading pose)) [0.0 0.0 1.0])))
                 1e-9)
          (let [[moved] (attachment/group-transform
                         [pm]
                         (:position pose) (:heading pose) (:up pose)
                         (:position new-pose) (:heading new-pose) (:up new-pose))]
            (swap! session assoc :proxy-mesh moved)
            (transport-registered-cameras! pose (:creation-pose moved))
            (swap! session assoc-in [:camera-poses 0]
                   (when-let [c0 (get-in @session [:camera-poses 0])]
                     (attachment/transform-pose-rigid
                      c0 (:position pose) (:heading pose) (:up pose)
                      (:position new-pose) (:heading new-pose) (:up new-pose)))))))

      (< (count rels) 4)
      (reanchor-to-build-pose!)                          ; too few cameras for a ring

      :else
      (let [vars (mapv (fn [a] (variance (map #(m/dot % a) rels))) axes)
            vidx (first (apply min-key second (map-indexed vector vars)))
            [v-small v-mid _] (sort vars)]
        (if-not (< v-small (* 0.15 v-mid))               ; not a clean planar ring
          (reanchor-to-build-pose!)
          (let [up-vec (nth axes vidx)
                up-C (if (>= (m/dot up-vec [0 0 1]) 0) up-vec (m/v* up-vec -1.0))
                ;; the two horizontals: right = SMALLER dim, heading = LARGER dim
                [[r-vec] [h-vec]] (->> [[ex 0] [ey 1] [ez 2]]
                                       (remove (fn [[_ i]] (= i vidx)))
                                       (sort-by (fn [[_ i]] (nth dims i))))
                heading-C (if (>= (m/dot (m/cross up-C h-vec) r-vec) 0) h-vec (m/v* h-vec -1.0))
                right-C (m/cross up-C heading-C)          ; exact right-handed
                ;; M·v = [-(right·v), heading·v, up·v] → maps right→-X, heading→+Y, up→+Z.
                Mv (fn [v] [(- (m/dot right-C v)) (m/dot heading-C v) (m/dot up-C v)])
                new-pose {:position build-pos :heading [0.0 1.0 0.0] :up [0.0 0.0 1.0]}
                ;; object-frame (a,b,c) → new frame coords (project the old-frame direction
                ;; onto the new axes); works for points AND directions (normals).
                remap (fn [[a b c]]
                        (let [dir (m/v+ (m/v* ex a) (m/v+ (m/v* ey b) (m/v* ez c)))]
                          [(m/dot dir right-C) (m/dot dir up-C) (m/dot dir heading-C)]))
                ;; {:axis :sign :offset} plane → remap its normal to the new frame axis.
                remap-plane (fn [{:keys [axis sign offset]}]
                              (let [nrm (remap (assoc [0.0 0.0 0.0] axis (double sign)))
                                    a (apply max-key #(Math/abs ^double (nth nrm %)) [0 1 2])]
                                {:axis a :sign (if (>= (nth nrm a) 0) 1 -1) :offset offset}))]
            (swap! session update :proxy-mesh
                   (fn [m]
                     (let [xform-pt (fn [v] (m/v+ build-pos (Mv (m/v- v center))))]
                       (cond-> (-> m
                                   (dissoc :ridley.manifold.core/manifold-cache :ridley.manifold.core/raw-arrays)
                                   (update :vertices (fn [vs] (mapv xform-pt vs)))
                                   (assoc :creation-pose new-pose))
                         ;; a proxy PLATE's marks (:anchors) must be re-expressed by
                         ;; the SAME rigid re-description as the geometry — position
                         ;; like a point, heading/up like directions (Mv is the pure
                         ;; rotation). Canonicalize does its own inline remap here,
                         ;; not group-transform, so transform-mesh-rigid's anchor
                         ;; carry doesn't reach it.
                         (seq (:anchors m))
                         (update :anchors
                                 (fn [as]
                                   (into {} (map (fn [[k a]]
                                                   [k (cond-> a
                                                        (:position a) (assoc :position (xform-pt (:position a)))
                                                        (:heading a)  (assoc :heading (Mv (:heading a)))
                                                        (:up a)       (assoc :up (Mv (:up a))))]))
                                         as)))))))
            (swap! session update :camera-poses
                   (fn [cps]
                     (into {} (map (fn [[idx p]]
                                     [idx {:position (m/v+ build-pos (Mv (m/v- (:position p) center)))
                                           :heading (Mv (:heading p))
                                           :up (Mv (:up p))}]))
                           cps)))
            (swap! session update :ricalchi
                   (fn [rs] (mapv (fn [r] (-> r
                                              (update :points #(mapv remap %))
                                              (update :plane remap-plane))) rs)))
            (swap! session update :marks
                   (fn [ms] (mapv (fn [mk] (cond-> (update mk :position remap)
                                             (:normal mk) (update :normal remap))) ms)))
            (swap! session update :mark-plane remap-plane)))))))

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
    ;; Out-of-ring photo (θ libera): there is no turntable angle to orbit by
    ;; (deg->rad nil would seed a NaN pose the moment you navigate to it). Use
    ;; photo 0's vantage as a neutral placeholder — 'p' (PnP) then sets its real
    ;; pose, which overwrites this cache.
    (if (nil? theta)
      base
      (m/pose-around-axis base (pivot) axis (- (deg->rad theta))))))

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
(declare stop-mark!)
(declare teardown-retrace-listeners!)
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

(defn- ensure-photo-pose
  "Camera world-pose for photo `idx`. Photo 0's default vantage is computed ONCE,
   on the very first entry, and frozen for the rest of the session from then on
   (never recomputed on later re-entries or commits — see on-photo0-commit!'s
   comment for why recomputing AT ALL is what made this unstable). Shared by
   enter-photo! (Phase-1, locked) and go-in-pose! (P4b stage)."
  [idx]
  (if (zero? idx)
    (let [start-pose (or (get-in @session [:camera-poses 0])
                         (default-vantage-pose (pivot)))]
      (swap! session assoc-in [:camera-poses 0] start-pose)
      start-pose)
    (camera-pose-for idx)))

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
    (viewport/set-camera-pose! (ensure-photo-pose idx))
    (set-photo-for-current-focal! file)
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
;; P4b — Il palcoscenico (fase non-modale, fetta 1: camera libera + vai in posa)
;; ------------------------------------------------------------
;; La fase di lavoro (ricalco/mark) esce dallo sfondo-a-camera-bloccata. Le foto
;; registrate vivono in due stati collaudabili qui: (1) camera LIBERA che orbita
;; l'oggetto (l'oggetto non si muove mai — invariante di frame del brief), (2)
;; IN POSA: si clicca una foto e la camera vi vola dentro (sfondo full-res +
;; geometria proiettata sopra), Esc / bottone torna a orbitare. I frustum-nel-
;; mondo (dove sono le foto nello spazio) sono lo strato opzionale successivo,
;; con ergonomia dichiaratamente da collaudare (brief §Le foto, in tre stati).
;; ============================================================

(declare install-frustum-listeners! teardown-frustum-listeners!)

(defn- enter-stage!
  "Enter the stage: free the camera to orbit the acquired object. Tears down the
   Phase-1 registration tools (gizmo / PnP / marker / mark / retrace listeners),
   drops the per-frame camera lock (the `:edit-acquire` frame callback), hides the
   photo backdrop, shows the object (proxy + ricalchi + marks) as free-orbit
   reference geometry, and steps the camera BACK to frame the whole shoot — object
   plus the ring of camera frustums — so the coverage reads at a glance. Clicking a
   filmstrip photo (or, later, a frustum) then flies into pose (go-in-pose!)."
  []
  (stop-pnp!) (stop-marker!) (stop-mark!) (teardown-retrace-listeners!)
  (gizmo/close!)
  (swap! session assoc :stage? true :in-pose? false :mode :gizmo)
  ;; Release the per-frame lock so OrbitControls can drive the camera again;
  ;; there's no gizmo in the stage to re-enable controls behind our back, so
  ;; leaving the callback off is enough (see open-session!'s lock comment).
  (viewport/unregister-frame-callback! :edit-acquire)
  (backdrop/set-visible! false)
  (viewport/show-preview! (stage-free-preview-items)) ; object + ghost frustums
  ;; Step back (keeping the view direction) so object + every camera frustum fit —
  ;; the cameras sit ~250 mm out around a ~60 mm object, so the close photo framing
  ;; would leave the frustums off-screen. Radius = farthest camera from the object.
  (let [piv (pivot)
        obj-r (* 0.5 (m/magnitude (bridge/dims-from-mesh
                                   (:proxy-mesh @session)
                                   (get-in @session [:proxy-mesh :creation-pose]))))
        cam-r (reduce max 0.0 (map #(m/magnitude (m/v- (:position %) piv))
                                   (vals (:camera-poses @session))))]
    (viewport/frame-camera! piv (* 1.15 (max obj-r cam-r))))
  ;; arm click-a-frustum → go-in-pose (free-orbit only; the handler self-guards)
  (install-frustum-listeners!)
  (update-panel!))

(defn- leave-stage!
  "Leave the stage back to Phase-1 registration on the current photo: restore the
   per-frame camera lock and re-enter the photo (locked backdrop + gizmo)."
  []
  (teardown-frustum-listeners!)
  (swap! session assoc :stage? false :in-pose? false)
  (viewport/register-frame-callback!
   :edit-acquire (fn [_camera] (viewport/set-controls-enabled! false)))
  (backdrop/set-visible! true)
  (enter-photo! (:current-idx @session)))

(defn- go-in-pose!
  "Stage state 2 — 'vai in posa': fly the camera into photo `idx`'s registered
   pose, show that photo full-screen as the backdrop, geometry projected over it.
   The camera is LOCKED here (set-camera-pose! disables controls; the backdrop is
   glued to the frustum, so it can't be orbited away from) — Esc / the panel
   button returns to free orbit (leave-pose!)."
  [idx]
  (swap! session assoc :current-idx idx :in-pose? true)
  (let [{:keys [file]} (nth (:photos @session) idx)]
    (viewport/set-camera-pose! (ensure-photo-pose idx)) ; disables controls → locked
    (set-photo-for-current-focal! file)
    (backdrop/set-visible! true))
  ;; re-show so trace-items re-culls against THIS photo's heading (a shape on the
  ;; face now turned away from the camera is hidden instead of bleeding through).
  (viewport/show-preview! (proxy-preview-items))
  (update-panel!))

(defn- leave-pose!
  "Stage state 2 → 1: leave the posed photo and return to free orbit around the
   object. The camera stays EXACTLY where/how the photo framed it — the backdrop
   is hidden and orbit is re-enabled without reorienting, so the object doesn't
   jump (Vincenzo 2026-07-24: recentring on the proxy, which sits at the origin
   while the photo framed the object off-centre, made the world shift on Esc).
   The orbit pivot lands on the object's center along the current view axis."
  []
  (swap! session assoc :in-pose? false)
  (backdrop/set-visible! false)
  (viewport/free-camera-at-pivot! (pivot))
  ;; back in free orbit: re-show so every shape reappears (culling off) and the
  ;; ghost frustums come back.
  (viewport/show-preview! (stage-free-preview-items))
  (update-panel!))

(defn- toggle-stage! []
  (if (:stage? @session) (leave-stage!) (enter-stage!)))

(defn- toggle-frustums!
  "Show/hide the ghost camera frustums (P4b, free orbit only). Re-shows the stage
   preview when it takes effect. A stage control (the button is offered only there)."
  []
  (swap! session update :show-frustums? not)
  (when (and (:stage? @session) (not (:in-pose? @session)))
    (viewport/show-preview! (stage-free-preview-items)))
  (update-panel!))

;; ------------------------------------------------------------
;; P4b frustum FASE B — click a frustum → go in pose. In free orbit only, a CLEAN
;; click (little pointer travel, so it never steals an orbit drag) on a ghost
;; frustum flies the camera into that photo's pose. The invisible solid twin
;; (frustum-pick-mesh) resolves the click via viewport/raycast-preview-pick. We do
;; NOT preventDefault/stopPropagation: OrbitControls must keep seeing the events (a
;; clean click rotates nothing anyway), and go-in-pose! locks the camera itself.
;; ------------------------------------------------------------

(def ^:private frustum-click-slop-px 6) ; press→release travel under this = a click, not a drag

(defn- stage-free-orbit? []
  (and @session (:stage? @session) (not (:in-pose? @session))))

(defn- frustum-on-pointerdown [^js e]
  (when (and (stage-free-orbit?) (zero? (.-button e)))
    ;; record the press + whatever frustum sits under it; let OrbitControls drag.
    (swap! session assoc :frustum-press
           {:x (.-clientX e) :y (.-clientY e)
            :idx (viewport/raycast-preview-pick e)})))

(defn- frustum-on-pointerup [^js e]
  (when (and (stage-free-orbit?) (zero? (.-button e)))
    (let [{:keys [x y idx]} (:frustum-press @session)]
      (swap! session dissoc :frustum-press)
      (when (and (some? idx) (some? x)
                 (< (js/Math.hypot (- (.-clientX e) x) (- (.-clientY e) y))
                    frustum-click-slop-px))
        ;; Defer the pose (which disables the orbit controls) to a macrotask so
        ;; TrackballControls ends THIS click cleanly first — else its ROTATE state
        ;; is stranded (its pointerup early-returns while disabled) and re-activates
        ;; on leave-pose! as a phantom drag (Vincenzo 2026-07-25). setTimeout not
        ;; rAF: rAF is paused in a background tab and would swallow the click.
        (js/setTimeout (fn [] (go-in-pose! idx)) 0)))))

(defn- install-frustum-listeners! []
  (let [^js canvas (viewport/get-canvas)]
    (.addEventListener canvas "pointerdown" frustum-on-pointerdown true)
    (.addEventListener canvas "pointerup" frustum-on-pointerup true)))

(defn- teardown-frustum-listeners! []
  (let [^js canvas (viewport/get-canvas)]
    (.removeEventListener canvas "pointerdown" frustum-on-pointerdown true)
    (.removeEventListener canvas "pointerup" frustum-on-pointerup true))
  (when @session (swap! session dissoc :frustum-press)))

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

(def ^:private target-colors
  "Distinct hues so a placed photo marker, its panel button, and its dot on the
   proxy read as the same target at a glance. The first EIGHT are the original
   box-corner palette (box mode stays visually identical); the rest cover a
   registration PLATE, which carries more marks than a box has corners."
  [0xff5555 0xff9f43 0xf4d03f 0x5fd35f 0x38c3d6 0x5b8def 0xb06cf0 0xf06fb0
   0xff77aa 0xffd24a 0x9be15d 0x4ad6b0 0x6ab7ff 0x9d7bff 0xff8c42 0xbfc7d0])

(defn- target-color [i] (nth target-colors (mod i (count target-colors))))

(defn- pnp-targets
  "The indexed PnP targets for the current photo: bridge/pnp-target-points (the
   8 box corners, OR a proxy plate's named marks when it carries :anchors)
   enriched with the UI's :color and :label. Pick keys are the vector INDEX
   0..N-1 whatever the source, so the whole picking gesture below is
   source-agnostic. Each: {:obj :world :visible? :id :color :label}."
  []
  (vec (map-indexed
        (fn [i t]
          (assoc t :color (target-color i)
                 :label (if (keyword? (:id t)) (name (:id t)) (str (inc i)))))
        (bridge/pnp-target-points (:proxy-mesh @session) (current-camera-pose)))))

(defn- pnp-count [] (count (pnp-targets)))

(defn- pnp-noun
  "What a pickable point is called in the prompts: a box has 'spigoli', a plate
   'marker'."
  []
  (if (plate-proxy?) "marker" "spigolo"))

(defn- corner-world-positions
  "World position of each target at the current proxy pose, indexed the same as
   pnp-targets — for drawing the pickable dots."
  []
  (mapv :world (pnp-targets)))

(defn- pnp-picks [] (get-in @session [:pnp-picks (:current-idx @session)] {}))
(defn- pnp-residuals [] (get-in @session [:pnp-residuals (:current-idx @session)] {}))
(defn- pnp-outliers
  "Set of corner indices the robust solve REJECTED as mislabels on this photo."
  []
  (get-in @session [:pnp-outliers (:current-idx @session)] #{}))

(defn- pnp-occluded
  "Set of marker indices the user marked HIDDEN-by-the-object on this photo ('o'):
   front-facing (so visible-corner-set offers them) but covered by the part in
   THIS view, so they can't be clicked and blob-snap must not chase them. Held
   per-photo — the same object hides different marks from different angles."
  []
  (get-in @session [:pnp-occluded (:current-idx @session)] #{}))

(defn- relabel-picks!
  "Rewrite photo `idx`'s declared identities through `flip` (a permutation of the
   crown indices), leaving every clicked PIXEL exactly where it is. Used when the
   mirror twin is resolved: the clicks were right and the labels were reflected,
   so the picks, the occlusion flags and the armed mark must all travel with them
   — otherwise the panel, the residuals and the dots would go on describing a
   different photo than the pose does."
  [idx flip]
  (letfn [(remap-keys [m] (when m (into {} (map (fn [[ci v]] [(flip ci) v])) m)))
          (remap-set [s] (when s (into #{} (map flip) s)))]
    (swap! session (fn [st]
                     (-> st
                         (update-in [:pnp-picks idx] remap-keys)
                         (update-in [:pnp-occluded idx] remap-set)
                         (update :pnp-armed #(some-> % flip)))))))

(defn- batch-mode?
  "Fetta B toggle ('b'): while on, PnP clicks accumulate as IDENTITY-FREE batch
   picks (no armed target) that 'r' assigns in one shot (match-plate); while off,
   the armed fetta-A flow runs. Session-wide (persists across photos); the batch
   clicks themselves are per-photo. Plate-only — a box has no zero-index to break
   the crown symmetry with, so there is nothing to auto-assign."
  []
  (boolean (:pnp-batch-mode? @session)))

(defn- pnp-batch
  "The identity-free batch clicks collected on this photo (fetta B): a vector of
   {:px [u v] :screen [x y]} disc centroids the user clicked without saying which
   mark each is. 'r' turns them into identified picks via match-plate/assign-marks."
  []
  (get-in @session [:pnp-batch (:current-idx @session)] []))

(defn- visible-corner-set
  "Indices of the targets actually visible at the current pose (pnp-targets'
   :visible?) — the only ones offered for picking, so the user is never asked to
   point at a box vertex hidden behind the part, or a plate mark turned away from
   the camera (Vincenzo, 2026-07-23). Recomputed from the live camera↔proxy
   relation, so it tracks as the pose is refined."
  []
  (into #{} (keep-indexed (fn [i t] (when (:visible? t) i)) (pnp-targets))))

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
        occluded (pnp-occluded)
        visible (visible-corner-set)]
    (into
     [{:type :wireframe :data (:proxy-mesh @session)}
      {:type :dots
       :data (vec (keep-indexed
                   (fn [i pos]
                     (cond
                       (contains? outliers i)
                       {:pos pos :radius 5.5 :color 0xff2020 :opacity 0.7}
                       ;; marked hidden-by-the-part: a faint grey dot, so it reads
                       ;; as "dismissed" and no longer solicits a click
                       (contains? occluded i)
                       {:pos pos :radius 1.6 :color 0x555555 :opacity 0.2}
                       (contains? visible i)
                       {:pos pos
                        :radius (if (= i armed) 4.4 2.4)
                        :opacity 0.3
                        :color (if (or (= i armed) (contains? placed i))
                                 (target-color i) 0x808080)}))
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
        n (pnp-count)
        order (map #(mod (+ from %) n) (range n))]
    (or (first (filter #(and (contains? visible %) (not (contains? placed %))) order))
        (first (filter visible order))
        from)))

(defn- world-dist [a b] (let [d (m/v- a b)] (Math/sqrt (m/dot d d))))

(defn- next-seed-corner
  "The next marker to arm while collecting seed clicks: the visible, not-placed,
   not-occluded target FARTHEST (max-min world distance) from those already
   placed — so a handful of clicks spread around the ring (a well-conditioned
   planar seed) instead of clustering in one arc, which is what makes the 4-click
   fetta-A seed usable. With nothing placed yet it is just the first candidate.
   nil when none remain (all placed or occluded)."
  []
  (let [placed (pnp-picks)
        occ (pnp-occluded)
        targets (pnp-targets)
        cand (filterv (fn [i] (and (:visible? (nth targets i))
                                   (not (contains? placed i))
                                   (not (contains? occ i))))
                      (range (count targets)))
        placed-pos (mapv #(:world (nth targets %)) (keys placed))]
    (cond
      (empty? cand) nil
      (empty? placed-pos) (first cand)
      :else (apply max-key
                   (fn [i] (let [p (:world (nth targets i))]
                             (reduce min js/Infinity (map #(world-dist p %) placed-pos))))
                   cand))))

(defn- arm-corner!
  "Arm a corner for the next photo click — but only a corner the user can point
   at (visible, or a red outlier to be re-clicked); a click on a hidden vertex
   would be a guess, so those are inert. Explicitly arming a marker also clears
   any 'occluded' mark on it (the user is choosing to place it after all)."
  [i]
  (when (or (contains? (visible-corner-set) i) (contains? (pnp-outliers) i))
    (swap! session update-in [:pnp-occluded (:current-idx @session)] (fnil disj #{}) i)
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

(defn- append-overlay-dot!
  "One translucent marker dot at screen [cx cy] on overlay `ov` (offsets are from
   `rect`, the canvas' viewport rect). `bg` fills it; `label` (or nil) prints a
   small numeral centred in it."
  [ov rect cx cy bg label]
  (let [dot (.createElement js/document "div")
        st (.-style dot)]
    (set! (.-position st) "absolute")
    (set! (.-left st) (str (- cx (.-left rect) 7) "px"))
    (set! (.-top st) (str (- cy (.-top rect) 7) "px"))
    (set! (.-width st) "14px")
    (set! (.-height st) "14px")
    (set! (.-borderRadius st) "50%")
    (set! (.-boxSizing st) "border-box")
    (set! (.-border st) "2px solid rgba(255,255,255,0.85)")
    (set! (.-background st) bg)
    ;; translucent so photo detail under the marker stays readable while
    ;; placing (Vincenzo, 2026-07-23 / more so 2026-07-25)
    (set! (.-opacity st) "0.75")
    (when label
      (set! (.-fontSize st) "9px")
      (set! (.-lineHeight st) "10px")
      (set! (.-textAlign st) "center")
      (set! (.-color st) "#fff")
      (set! (.-textShadow st) "0 0 2px #000")
      (set! (.-textContent dot) label))
    (.appendChild ov dot)))

(defn- redraw-overlay-dots! []
  (let [ov (ensure-pnp-overlay!)
        rect (canvas-rect)
        cam (viewport/get-camera)
        cv (viewport/get-canvas)]
    (set! (.-innerHTML ov) "")
    (doseq [[ci {:keys [px screen]}] (pnp-picks)
            ;; position from the stable photo pixel through the LIVE camera, so
            ;; the dot tracks the render exactly (a stored :screen goes stale the
            ;; moment the pose is refined, and a blob-snapped proposal never had
            ;; a click screen to begin with); fall back to :screen for old data.
            :let [[cx cy] (or (and px (backdrop/screen-of-pixel cv cam px)) screen)]
            :when (and cx cy)]
      (append-overlay-dot! ov rect cx cy (hex->css (target-color ci)) nil))
    ;; fetta B: the identity-free batch clicks, neutral white and numbered in
    ;; click order (no colour — they carry no identity until 'r' assigns them)
    (doseq [[i {:keys [px screen]}] (map-indexed vector (pnp-batch))
            :let [[cx cy] (or (and px (backdrop/screen-of-pixel cv cam px)) screen)]
            :when (and cx cy)]
      (append-overlay-dot! ov rect cx cy "rgba(255,255,255,0.35)" (str (inc i))))))

(def min-plate-picks
  "A plate registers by the planar homography, which is exactly determined by 4
   coplanar marks — so 'p' can seed a pose from 4 clicks, the premise of fetta A
   (click 4, blob-snap proposes the rest) and the minimum for fetta B's batch
   assignment. A box still needs the DLT's pnp/min-correspondences (6, on
   non-coplanar corners)."
  4)

(def ^:private plate-click-snap-radius
  "Window half-size (px) for snapping a SEED click to its disc centroid. A plate
   mark images ~55px across at the session distance; this comfortably holds one
   disc plus margin (mean-shift recentres if the click was off) without reaching
   a neighbour."
  50)

(def ^:private suspicious-snap-px
  "A snap that moves the click further than this has probably latched onto
   something that is not the mark. A disc is ~2.5mm across — some 50px on these
   photos — so an honest snap from a rough click travels at most ~25px; beyond
   that it has walked to a NEIGHBOURING feature."
  25.0)

(defn- snap-plate-click
  "A plate mark IS a dark blob, so snap a raw click to its disc centroid — the
   clicks then match the auto-proposals' sub-pixel precision (what keeps the
   plate's rms ~4px) and the user only has to click ROUGHLY on the dot. Falls
   back to the raw click when no clean blob is under it (unclear disc, box corner)."
  [raw]
  (or (when (plate-proxy?)
        (some-> (blob/snap-to-blob backdrop/luminance-at raw plate-click-snap-radius) :center))
      raw))

(defn- click-pixel
  "The pixel a click means: snapped to the disc under it, unless ALT is held.

   The snap is right almost always and wrong in a way the user cannot argue
   with: when the mark touches something of a similar grey — the dark object
   sitting on the plate — the blob it finds spans both, and the centroid lands on
   a rounded tip of the object instead of the disc, however carefully you
   clicked (Vincenzo 2026-08-01, foto 10 / mark 11). No amount of aim fixes that,
   so there has to be a way to say 'take my click literally'.

   And because a user who does not know the override cannot ask for it, a snap
   that travelled suspiciously far ANNOUNCES itself and names the way out — the
   affordance is offered at the moment it is needed rather than hidden in a
   keymap."
  [raw ^js e]
  (if (.-altKey e)
    (do (set-status-message! "click preso alla lettera (Alt): nessuno snap")
        raw)
    (let [px (snap-plate-click raw)
          d (Math/hypot (- (nth px 0) (nth raw 0)) (- (nth px 1) (nth raw 1)))]
      (when (> d suspicious-snap-px)
        (set-status-message!
         (str "lo snap ha spostato il click di " (modal/fmt-number d) "px — se ha agganciato "
              "la cosa sbagliata (un bordo scuro lì vicino), riclicca tenendo ALT "
              "per prenderlo alla lettera")))
      px)))

(defn- screen-for [px client-fallback]
  (or (backdrop/screen-of-pixel (viewport/get-canvas) (viewport/get-camera) px) client-fallback))

(defn- pnp-on-pointerdown [^js e]
  (when (and @session (= :pnp (:mode @session)) (zero? (.-button e)))
    (when-let [raw (backdrop/pixel-under-pointer e (viewport/get-camera) (viewport/get-canvas))]
      (let [idx (:current-idx @session)]
        (cond
          ;; fetta B: identity-free batch — every click is just another disc
          ;; centroid appended to the batch (no armed target); 'r' assigns them.
          (batch-mode?)
          (do
            (.preventDefault e) (.stopPropagation e)
            (let [px (click-pixel raw e)]
              (swap! session update-in [:pnp-batch idx] (fnil conj [])
                     {:px px :screen (screen-for px [(.-clientX e) (.-clientY e)])})
              (redraw-overlay-dots!)
              (update-panel!)))

          ;; fetta A / box: place the armed target; ignore clicks when unarmed
          (:pnp-armed @session)
          (let [ci (:pnp-armed @session)
                px (click-pixel raw e)]
            (.preventDefault e) (.stopPropagation e)
            (swap! session assoc-in [:pnp-picks idx ci]
                   {:px px :screen (screen-for px [(.-clientX e) (.-clientY e)])})
            ;; a new click makes the last solve's residuals/outliers stale — drop
            ;; them so the red flags clear until the user re-solves
            (swap! session update :pnp-residuals dissoc idx)
            (swap! session update :pnp-outliers dissoc idx)
            (redraw-overlay-dots!)
            ;; arm the next SPREAD marker (farthest from those placed) so a few seed
            ;; clicks fan out around the ring instead of clustering; nil once every
            ;; non-occluded marker is placed (panel then says "premi 'r'")
            (if-let [nxt (next-seed-corner)]
              (arm-corner! nxt)
              (do (swap! session assoc :pnp-armed nil) (redraw-pnp-preview!) (update-panel!)))))))))

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
    (swap! session dissoc :pnp-batch-mode?)   ; always open in the armed flow
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
    ;; batch (fetta B) state is transient pre-assign scaffolding, not persisted —
    ;; drop it on exit so re-entering PnP opens clean in the armed flow
    (swap! session dissoc :pnp-batch :pnp-batch-mode?)
    (swap! session assoc :mode :gizmo)
    (viewport/show-preview! (proxy-preview-items))
    (install-gizmo! (:current-idx @session))
    (update-panel!)))

(defn- undo-batch-click!
  "Backspace in batch mode: drop the last identity-free click."
  []
  (let [idx (:current-idx @session)]
    (when (seq (pnp-batch))
      (swap! session update-in [:pnp-batch idx] pop)
      (redraw-overlay-dots!)
      (update-panel!))))

(defn- toggle-batch-mode!
  "'b' (plate only): flip between the identity-free batch flow (fetta B — click
   any discs, 'r' assigns) and the armed flow (fetta A — a highlighted marker at
   a time). Entering batch disarms; leaving it re-arms the next spread marker."
  []
  (let [on? (not (batch-mode?))]
    (swap! session assoc :pnp-batch-mode? on?)
    (if on?
      (do (swap! session assoc :pnp-armed nil)
          (set-status-message!
           (str "Batch (senza identità): clicca almeno " min-plate-picks
                " dischetti QUALSIASI, ben sparsi attorno al piatto, poi 'r'. "
                "'b' per tornare alla modalità armata."))
          (redraw-pnp-preview!))
      (do (arm-corner! (next-unplaced-corner 0))
          (set-status-message! "Modalità armata: evidenzia un marker, clicca dov'è, poi 'r'.")))
    (redraw-overlay-dots!)
    (update-panel!)))

(defn- clear-pnp-picks! []
  (let [idx (:current-idx @session)]
    (swap! session update :pnp-picks dissoc idx)
    (swap! session update :pnp-residuals dissoc idx)
    (swap! session update :pnp-outliers dissoc idx)
    (swap! session update :pnp-occluded dissoc idx)
    (swap! session update :pnp-batch dissoc idx))
  (redraw-overlay-dots!)
  (when-not (batch-mode?) (arm-corner! (next-unplaced-corner 0)))
  (update-panel!))

(defn- skip-armed-corner!
  "'o': DROP the armed marker from this photo — because it is hidden by the part,
   OR because it is a stubborn one that won't align (a flagged outlier whose
   residual won't drop no matter where it's re-clicked, e.g. a blurred disc on a
   steep view). Drop any pick for it, record it occluded so auto-advance and
   blob-snap both skip it, clear its stale fit flags, and arm the next spread
   marker. Then 'r' re-solves clean on the rest (a plate has marks to spare)."
  []
  (when-let [i (:pnp-armed @session)]
    (let [idx (:current-idx @session)
          lbl (:label (nth (pnp-targets) i))]
      (swap! session update-in [:pnp-occluded idx] (fnil conj #{}) i)
      (swap! session update-in [:pnp-picks idx] dissoc i)
      (swap! session update-in [:pnp-outliers idx] (fnil disj #{}) i)
      (swap! session update-in [:pnp-residuals idx] dissoc i)
      (swap! session assoc :pnp-armed (next-seed-corner)) ; nil once none remain
      (redraw-pnp-preview!)
      (redraw-overlay-dots!)
      (update-panel!)
      (save-acquire-state!)
      (set-status-message!
       (str (str/capitalize (pnp-noun)) " #" lbl " scartato — "
            "premi 'r' per registrare sui restanti")))))

(defn- corner-labels [cis]
  (let [targets (pnp-targets)]
    (str/join ", " (map #(str "#" (:label (nth targets %))) (sort cis)))))

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
      (str "registrata sui restanti (" (.toFixed rms 1) "px), "
           (if (> (count out) 1) "scartati i punti " "scartato il punto ")
           (corner-labels out) ": riclicca" (if (> (count out) 1) "li" "lo")
           " più preciso, o 'o' per scartarl" (if (> (count out) 1) "i" "o")
           " (nascosto o non allineabile), poi 'r' — o vai avanti così")
      (> rms pnp/accept-rms-px)
      (str "rms alto (" (.toFixed rms 1) "px) senza un singolo colpevole: clicca più "
           "preciso, o la faccia dichiarata è sbagliata; se resta, segnalamelo")
      :else
      (str "fit pulito, rms " (.toFixed rms 2) "px"))))

(defn- min-pnp-picks [] (if (plate-proxy?) min-plate-picks pnp/min-correspondences))

(defn- solve-and-apply!
  "Solve the current photo's placed correspondences and APPLY the pose (move the
   proxy on photo 0, the camera otherwise), updating results/residuals/outliers
   and redrawing. Returns the solve map (with :pose/:method/:rms-px) or nil. The
   shared core of on-solve-pnp!, called once for a plain solve and twice around
   propose-and-snap! for fetta A. Does NOT set the status line or save — the
   caller owns those, once, after the (possibly two-pass) solve settles."
  [iw ih]
  (swap! session dissoc :last-solve)
  (let [idx (:current-idx @session)
        proxy-pose (get-in @session [:proxy-mesh :creation-pose])
        targets (pnp-targets)
        correspondences (vec (for [[ci {:keys [px]}] (pnp-picks)]
                               {:ci ci :world (:obj (nth targets ci)) :px px}))
        camera-pose (current-camera-pose)]
    (let [sol (let [k (session-intrinsics iw ih)
                    first-try (pnp/solve-pnp correspondences k {})
                    detect (bridge/plate-detect (:proxy-mesh @session))
                    sees? (fn [s] (and s (bridge/camera-sees-marked-face?
                                          detect (:pose s))))]
                ;; A pose that puts the camera BEHIND the printed face is
                ;; impossible, not improbable — the discs were photographed. For
                ;; a plate it also has one exact cause: the crown is mirror-
                ;; symmetric about the axis through mark 0, so the reflected
                ;; LABELLING fits the very same clicks (measured: rms equal to
                ;; the last digit) and lands the camera on the opposite side.
                ;; The residual therefore cannot arbitrate and never will; the
                ;; physical test always can. So don't re-seed and hope — reflect
                ;; the identities and solve again. The user is not being
                ;; overruled arbitrarily: a proxy drawn from an already-flipped
                ;; pose shows mirrored labels, so clicking them confirms the
                ;; flip. See bridge/mirror-crown-index.
                (if (and detect first-try (not (sees? first-try)))
                  (let [flip #(bridge/mirror-crown-index (count targets) %)
                        mirrored (mapv (fn [c]
                                         (let [j (flip (:ci c))]
                                           (assoc c :ci j :world (:obj (nth targets j)))))
                                       correspondences)
                        m-sol (pnp/solve-pnp mirrored k {})]
                    (if (sees? m-sol)
                      (do (relabel-picks! idx flip)
                          (assoc m-sol :note
                                 (str "le etichette erano SPECCHIATE (la corona è simmetrica "
                                      "e il residuo non le distingue): riflesse attorno a m00, "
                                      "ora la camera è davanti al piatto")))
                      ;; The mirror didn't rescue it either: fall back on
                      ;; refining from the pose the user has on screen.
                      (let [seed (bridge/editor->solver-pose camera-pose proxy-pose)
                            retry (pnp/solve-pnp correspondences k
                                                 {:method :seeded :seed seed})]
                        (if (sees? retry)
                          (assoc retry :note
                                 (str "la prima soluzione metteva la camera dietro il piatto: "
                                      "ripresa dall'allineamento corrente"))
                          ;; Every candidate is impossible. APPLYING one would be
                          ;; worse than doing nothing: it silently overwrites
                          ;; whatever the user has aligned by hand — which on
                          ;; photos ≠ 0 is the camera, exactly what this would
                          ;; replace — and leaves them with an impossible pose
                          ;; that looks like a result. Refuse, keep what is on
                          ;; screen, say why.
                          (do (set-status-message!
                               (str "NON applicata: su questa foto ogni soluzione mette la camera "
                                    "dietro il piatto, anche riflettendo le etichette. Lascio la "
                                    "posa che hai adesso. Girala a mano e usala così, oppure "
                                    "scarta la foto."))
                              ;; NOT nil: nil means 'could not fit' to the caller,
                              ;; which would replace this explanation with the
                              ;; generic 'nessuna soluzione' and hide the real
                              ;; reason. A refusal is a decision, not a failure.
                              (swap! session assoc :last-solve ::refused)
                              ::refused)))))
                  first-try))]
      ;; A refusal must not reach the apply body: (:pose ::refused) is nil, and
      ;; bridge/solver-pose->camera of nil returns a PLAUSIBLE pose (measured:
      ;; {:position [0 0 0] :heading [0 0 1]}) rather than failing — so the
      ;; refusal would overwrite the very alignment it exists to protect, and
      ;; nothing downstream would notice.
      (if (= sol ::refused)
        ::refused
        (when sol
          (let [residuals (into {} (map (juxt :ci :residual-px) (:per-point sol)))
                outlier-cis (set (map :ci (:outliers sol)))]
            (if (zero? idx)
              (let [np (bridge/solver-pose->proxy (:pose sol) camera-pose)
                    [new-mesh] (attachment/group-transform
                                [(:proxy-mesh @session)]
                                (:position proxy-pose) (:heading proxy-pose) (:up proxy-pose)
                                (:position np) (:heading np) (:up np))]
                (swap! session assoc :proxy-mesh new-mesh)
                ;; Re-aligning the proxy on photo 0 via PnP is the same rigid move as
                ;; an on-photo0-commit! gizmo drag, so it earns the same treatment
                ;; (fix (2)): registered cameras follow the proxy rigidly, pure
                ;; seeds/predictions are dropped to re-derive.
                (transport-registered-cameras! proxy-pose (:creation-pose new-mesh)))
              (let [ncp (marker-lock-camera (bridge/solver-pose->camera (:pose sol) proxy-pose) idx)]
                (swap! session assoc-in [:camera-poses idx] ncp)
                ;; move the viewport camera to the solved pose NOW (as on-snap! does)
                ;; — the proxy is fixed on these photos, so without this the
                ;; wireframe/dots stay rendered from the old vantage and the disc
                ;; only snaps into place on the next enter-photo!.
                (viewport/set-camera-pose! ncp)))
            (swap! session assoc-in [:acquire-results idx]
                   {:pnp? true :matched (:n sol) :rms-px (:rms-px sol)
                    :outliers (count outlier-cis)})
            (swap! session assoc-in [:pnp-residuals idx] residuals)
            (swap! session assoc-in [:pnp-outliers idx] outlier-cis)
            ;; tee up the first rejected corner for an immediate re-click
            (when (seq outlier-cis)
              (swap! session assoc :pnp-armed (first (sort outlier-cis))))
            (redraw-pnp-preview!)
            (redraw-overlay-dots!)
            sol))))))

(defn- propose-and-snap!
  "Fetta A: with `pose` already solved from the placed picks, reproject every
   still-unplaced VISIBLE mark and blob-snap each predicted pixel to its dark
   disc centre in the photo (backdrop/luminance-at), placing the confident ones
   as picks so the next solve refines on all of them. Returns the count placed.
   Plate-only (a box corner is not a blob). The search window scales with the
   local mark spacing — a fraction of the nearest predicted-neighbour gap — so
   it comfortably holds one disc without reaching the plate rim or a neighbour."
  [pose intrinsics]
  (if-not (plate-proxy?)
    0
    (let [idx (:current-idx @session)
          targets (pnp-targets)
          placed (set (keys (pnp-picks)))
          occ (pnp-occluded)
          visible (visible-corner-set)
          canvas (viewport/get-canvas)
          predicted (into {} (keep (fn [i]
                                     (when-let [px (pcamera/project intrinsics pose (:obj (nth targets i)))]
                                       [i px]))
                                   (range (count targets))))
          gap-to-nearest (fn [i [ux uy]]
                           (reduce min js/Infinity
                                   (for [[j [qx qy]] predicted :when (not= j i)]
                                     (Math/sqrt (+ (* (- ux qx) (- ux qx)) (* (- uy qy) (- uy qy)))))))
          added (atom 0)]
      (doseq [i (sort visible)
              :when (and (not (contains? placed i)) (not (contains? occ i))
                         (contains? predicted i))]
        (let [px (get predicted i)
              radius (max 15 (min 90 (* 0.12 (gap-to-nearest i px))))]
          (when-let [snap (blob/snap-to-blob backdrop/luminance-at px radius)]
            (let [c (:center snap)]
              (swap! session assoc-in [:pnp-picks idx i]
                     {:px c :screen (backdrop/screen-of-pixel canvas (viewport/get-camera) c)
                      :proposed? true})
              (swap! added inc)))))
      @added)))

(defn- on-solve-pnp!
  "Solve the current photo's declared correspondences (robustly — a mislabeled
   corner is auto-rejected) and APPLY the pose, then STAY in PnP mode: rejected
   corners show as big red dots / red panel buttons and the first is re-armed,
   so 'riclicca e premi r' is one gesture. Exit is explicit ('p' / Esci).

   Fetta A (plate): a first solve from as few as 4 marks seeds a pose, then
   blob-snap auto-places the remaining visible marks and a second solve refines
   on all of them — so the user clicks 4, not 12. An auto-placed mark that
   snapped wrong simply shows up as a red outlier of the final fit, to re-click."
  []
  (when-let [[iw ih] (backdrop/image-size)]
    (let [n (count (pnp-picks))]
      (if (< n (min-pnp-picks))
        (set-status-message!
         (str "PnP: servono almeno " (min-pnp-picks) " " (pnp-noun) " piazzati (ne hai " n ")"))
        (if-let [sol (let [r (solve-and-apply! iw ih)]
                       (when-not (= r ::refused) r))]
          (let [added (propose-and-snap! (:pose sol) (session-intrinsics iw ih))
                final (if (pos? added) (or (solve-and-apply! iw ih) sol) sol)]
            (set-status-message!
             (str "PnP " (name (:method final)) ": " (pnp-diagnosis final)
                  (when (pos? added)
                    (str " · " added " " (pnp-noun) " agganciati in automatico"))
                  ;; A solve that had to reinterpret the picks says so HERE: the
                  ;; status line is written once per gesture, so a message set
                  ;; during the solve would be overwritten by this one and the
                  ;; user would never learn their labels had been reflected.
                  (when-let [note (or (:note final) (:note sol))]
                    (str " · " note))))
            (save-acquire-state!))
          ;; a REFUSED solve already said why, and its message must survive
          (when-not (= ::refused (:last-solve @session))
            (set-status-message!
             (str "PnP: nessuna soluzione — " (pnp-noun) " su più facce e almeno "
                  (min-pnp-picks) "?")))))))
  (update-panel!))

(def ^:private min-crown-assign
  "A batch (fetta B) assignment is accepted only if at least this many of the 12
   crown marks reproject onto real discs (on top of the zero-index, which fixes
   the rotation). Below it the clicks were too clustered or an obstruction hid too
   much — the armed flow ('b' to leave batch) is the fallback."
  8)

(defn- assign-batch!
  "Fetta B trigger ('r' while in batch mode): recover which mark each identity-
   free click is (match-plate/assign-marks — the photo is the judge, the zero-
   index breaks the crown's 12-fold symmetry), write the identified picks, leave
   batch mode, and hand off to on-solve-pnp! so the pose is refined and the rest
   of the crown blob-snapped exactly as the armed fetta A does. A low-confidence
   assignment is refused with a plain-language reason instead of a wrong pose."
  []
  (let [batch (pnp-batch)
        idx (:current-idx @session)]
    (cond
      (not (plate-proxy?))
      (set-status-message! "L'assegnazione automatica è solo per il piatto di registrazione.")

      (< (count batch) min-plate-picks)
      (set-status-message!
       (str "Batch: servono almeno " min-plate-picks " dischetti cliccati (ne hai " (count batch) ")"))

      (nil? (:zero-obj (bridge/plate-detect (:proxy-mesh @session))))
      ;; the proxy has no zero-index (an OLD plate def, before it was exposed on
      ;; :anchors) — the batch can't break the crown's rotational symmetry without
      ;; it. Say exactly that, not the misleading "clicca più sparsi".
      (set-status-message!
       (str "Questo piatto non espone lo zero-indice: rivaluta il file aggiornato "
            "examples/param-acq-plate.clj (il piatto ora ha lo zero sotto :anchors) e "
            "riapri la sessione — oppure premi 'b' per la modalità armata."))

      :else
      (if-let [[iw ih] (backdrop/image-size)]
        (let [targets (pnp-targets)
              det (bridge/plate-detect (:proxy-mesh @session))
              clicks (mapv :px batch)
              judge (fn [px r] (blob/disc-at? backdrop/luminance-at px r))
              res (match-plate/assign-marks clicks targets (:zero-obj det)
                                            (session-intrinsics iw ih) judge
                                            {:disc-r (:disc-r det)
                                             :face-normal (:face-normal det)})]
          (if (and res (:zero-hit? res) (>= (:crown-hits res) min-crown-assign))
            (do
              ;; identity recovered → write the 4 picks under their real mark
              ;; indices, drop the batch, and leave batch mode so the standard
              ;; solved view (residuals / arm-to-reclick) takes over
              (doseq [[click-idx mark-idx] (:assignment res)]
                (swap! session assoc-in [:pnp-picks idx mark-idx] (nth batch click-idx)))
              (swap! session update :pnp-batch dissoc idx)
              (swap! session assoc :pnp-batch-mode? false)
              (redraw-overlay-dots!)
              (on-solve-pnp!)) ; refines + auto-places the rest, sets its own status
            (set-status-message!
             (str "Non riesco ad assegnare le identità"
                  (when res (str " (dischetti riconosciuti " (:crown-hits res) "/12"
                                 (when-not (:zero-hit? res) ", zero-indice non trovato") ")"))
                  ": clicca dischetti più SPARSI attorno al piatto e assicurati che lo "
                  "zero-indice (il pallino interno accanto a un marker) sia visibile — "
                  "oppure premi 'b' per la modalità armata."))))
        (set-status-message! "Foto non ancora caricata."))))
  (update-panel!))

;; ============================================================
;; Turntable ring (plate 'f'): register the remaining photos from the ones the
;; user already registered with 'p'. The camera poses (object frame) trace a ring
;; about the plate's spin axis, so a registered photo, spun by the right plate
;; angle, predicts another ring photo's marks — a 1-DOF search (match-plate/
;; find-ring-pose) the zero-index disambiguates. Each pending photo is sampled
;; OFF-SCREEN (backdrop/load-luminance-sampler), predicted from its NEAREST
;; registered reference (best zero-hit score — a hand-held ring is not a perfect
;; circle, so a far reference drifts), blob-snapped, and PnP-solved. Only a fit
;; under the rms bar registers; the rest stay for manual 'p'. This is the plate's
;; analogue of the box's on-fit-turntable! — dispatched by 'f' on plate-proxy?.
;; ============================================================

(def ^:private ring-snap-radius
  "blob-snap window (px) for a ring-predicted mark. Predictions land within ~30px
   of the real disc (see the real-data test), comfortably inside this."
  60)

(def ^:private min-ring-crown
  "A ring candidate needs at least this many crown marks tightly on-disc (on top
   of the zero-index, the real discriminator) before its angle is trusted."
  4)

(defn- register-one-ring-photo!
  "Predict + register ONE unregistered ring photo `idx` from the registered
   reference solver poses. Samples the photo off-screen, finds the best zero-hit
   ring angle over all references, blob-snaps the predicted mark pixels, and
   PnP-solves. Resolves to true when a fit under the rms bar registers, else
   false (a load/blur/solve miss is just a skip, never a throw)."
  [idx ref-solvers marks zero-obj intrinsics disc-r proxy-pose]
  (-> (backdrop/load-luminance-sampler (photo-path (:file (nth (:photos @session) idx))))
      (.then (fn [sampler]
               (let [lum-at (:lum-at sampler)
                     judge (fn [px r] (blob/disc-at? lum-at px r))
                     best (->> ref-solvers
                               (keep #(match-plate/find-ring-pose % marks zero-obj intrinsics judge
                                                                  {:disc-r disc-r}))
                               (filter #(and (:zero-hit? %) (>= (:crown-hits %) min-ring-crown)))
                               (sort-by :score >)
                               first)]
                 (if-not best
                   false
                   (let [picks (into {} (keep (fn [[mi px]]
                                                (some->> (blob/snap-to-blob lum-at px ring-snap-radius)
                                                         :center (vector mi)))
                                              (:pixels best)))
                         corr (vec (for [[ci px] picks]
                                     {:ci ci :world (:obj (nth marks ci)) :px px}))]
                     (if (< (count corr) min-plate-picks)
                       false
                       (if-let [sol (pnp/solve-pnp corr intrinsics {})]
                         (if (<= (:rms-px sol) pnp/accept-rms-px)
                           (do
                             (swap! session assoc-in [:camera-poses idx]
                                    (bridge/solver-pose->camera (:pose sol) proxy-pose))
                             (swap! session assoc-in [:acquire-results idx]
                                    {:pnp? true :ring? true :matched (:n sol)
                                     :rms-px (:rms-px sol) :outliers (count (:outliers sol))})
                             (swap! session assoc-in [:pnp-picks idx]
                                    (into {} (map (fn [[ci px]] [ci {:px px :proposed? true}]) picks)))
                             true)
                           false)
                         false)))))))
      (.catch (fn [_] false))))

(defn- on-fit-ring!
  "Plate 'f': register every unregistered ring photo from the ones already done."
  []
  (if-let [[iw ih] (backdrop/image-size)]
    (let [proxy-mesh (:proxy-mesh @session)
          proxy-pose (:creation-pose proxy-mesh)
          det (bridge/plate-detect proxy-mesh)
          marks (mapv #(select-keys % [:id :obj]) (pnp-targets))
          zero-obj (:zero-obj det)
          intrinsics (session-intrinsics iw ih)
          n (count (:photos @session))
          registered? (fn [idx] (:pnp? (get-in @session [:acquire-results idx])))
          ;; ALL photos on a plate are θ=libera — the ring INFERS the angle, so
          ;; (unlike the box turntable) the free-photo? guard must NOT apply here.
          ;; Any registered photo (incl. 0, whose fixed-vantage camera is still a
          ;; valid view of the moved proxy) is a reference; every unregistered
          ;; photo ≥1 is a target (photo 0's own model moves the proxy, not the
          ;; camera, so it is never ring-predicted).
          refs (filterv (fn [idx] (and (registered? idx) (get-in @session [:camera-poses idx])))
                        (range n))
          ref-solvers (mapv #(bridge/editor->solver-pose (get-in @session [:camera-poses %]) proxy-pose) refs)
          pending (filterv (fn [idx] (and (pos? idx) (not (registered? idx)))) (range n))]
      (cond
        (nil? zero-obj)
        (set-status-message! "Questo piatto non espone lo zero-indice: rivaluta examples/param-acq-plate.clj e riapri.")
        (empty? refs)
        (set-status-message! "Anello: registra prima almeno una foto con 'p' (poi 'f' propone le altre).")
        (empty? pending)
        (set-status-message! "Anello: tutte le foto (nell'anello) sono già registrate.")
        :else
        (do
          (set-status-message!
           (str "Anello: registro " (count pending) " foto dai " (count refs) " riferimenti…"))
          (-> (js/Promise.all
               (clj->js (mapv #(register-one-ring-photo! % ref-solvers marks zero-obj
                                                         intrinsics (:disc-r det) proxy-pose)
                              pending)))
              (.then (fn [results]
                       (let [ok (count (filter identity (vec results)))]
                         (when-let [cp (get-in @session [:camera-poses (:current-idx @session)])]
                           (viewport/set-camera-pose! cp))
                         (when (= :pnp (:mode @session))
                           (redraw-pnp-preview!)
                           (redraw-overlay-dots!))
                         (save-acquire-state!)
                         (set-status-message!
                          (str "Anello: registrate " ok "/" (count pending) " foto"
                               (when (< ok (count pending))
                                 " — le altre: 'p' a mano, o registra un riferimento più vicino e ripremi 'f'")))
                         (update-panel!))))))))
    (set-status-message! "Foto non ancora caricata.")))

;; ============================================================
;; Auto (plate 'a'): fetta C — register with ZERO clicks. For each unregistered
;; photo the detector (blob-detect) finds the crown's dark discs across the WHOLE
;; frame; fit-crown SELECTS the crown from that superset and IDENTIFIES it (the
;; zero-index breaking the 12-fold symmetry); a full PnP on the blob-snapped marks
;; gives the pose — no armed picks, no 'p'. A photo whose detection is too poor (a
;; grazing shot — the discs image as thin ellipses the shape filter drops) simply
;; fails fit-crown and is LEFT for the ring ('f') or a manual 'p', never registered
;; wrong (the zero-index + crown threshold + rms bar are the guards). Photo 0 moves
;; the PROXY (as 'p' there does); photos ≥1 set their camera. Processed SEQUENTIALLY
;; (photo 0 first, so its proxy move precedes the camera solves) and OFF-SCREEN like
;; the ring. The plate's analogue of nothing on the box — dispatched by 'a'.
;; ============================================================

(def ^:private auto-snap-radius
  "blob-snap window (px) for an identified crown mark before the final PnP. The
   detector centroid + fit-crown's reprojection land within a disc-radius, well
   inside this."
  40)

(def ^:private auto-fit-blobs
  "How many of the detector's TOP-scored blobs fit-crown samples the crown from. The
   ~12 crown discs outrank the frame noise on area·fill, so this keeps the noise out
   of the RANSAC (each stray blob it must sift past costs ~a second) while leaving
   slack for a faint mark or two. The geometric judge still scores against ALL
   detected blobs (incl. the zero-index), so the crown + zero are found even when a
   mark falls just outside this top slice. The ellipse fit tolerates the extra noise
   these carry, and a wider slice recovers the crown on the busier (noisier) frames."
  24)

(defn- auto-log!
  "Append a line to the REPL history panel — a PERSISTENT progress stream for the
   Auto batch (the status line auto-clears after 4s, so a per-photo trace would be
   unreadable there). Vincenzo's ask: 'scrivi nella REPL così resta lo stream'."
  [text]
  (when-let [history (.getElementById js/document "repl-history")]
    (let [entry (.createElement js/document "div")
          res (.createElement js/document "div")]
      (set! (.-className entry) "repl-entry")
      (set! (.-className res) "repl-result")
      (set! (.-textContent res) text)
      (.appendChild entry res)
      (.appendChild history entry)
      (set! (.-scrollTop history) (.-scrollHeight history)))))

(defn- yield-frame
  "A Promise that resolves on the next macrotask, so a long sequential batch hands
   control back to the browser event loop between photos — otherwise the many
   seconds of solid compute freeze the page and the dev-server drops the socket."
  []
  (js/Promise. (fn [res] (js/setTimeout res 0))))

(defn- apply-auto-solve!
  "Apply an auto-registered `sol` for photo `idx` exactly as solve-and-apply! does
   for a manual solve — photo 0 moves the PROXY (and transports already-registered
   cameras rigidly), photos ≥1 set their camera — writing picks/results/residuals.
   Pure session mutation (no viewport), so it is safe inside the off-screen batch;
   the caller refreshes the view once at the end. `proxy-pose` is the proxy's
   creation-pose BEFORE this photo (recomputed per photo by the caller)."
  [idx sol picks proxy-pose]
  (let [residuals (into {} (map (juxt :ci :residual-px) (:per-point sol)))]
    (if (zero? idx)
      (when-let [camera-pose (get-in @session [:camera-poses 0])]
        (let [np (bridge/solver-pose->proxy (:pose sol) camera-pose)
              [new-mesh] (attachment/group-transform
                          [(:proxy-mesh @session)]
                          (:position proxy-pose) (:heading proxy-pose) (:up proxy-pose)
                          (:position np) (:heading np) (:up np))]
          (swap! session assoc :proxy-mesh new-mesh)
          (transport-registered-cameras! proxy-pose (:creation-pose new-mesh))))
      ;; camera branch, as register-one-ring-photo! does — NOT via marker-lock-camera
      ;; (that reads backdrop/image-size, the DISPLAYED photo, wrong for this
      ;; off-screen photo; and a plate needs no Klein branch lock — its zero-index
      ;; already fixed the orientation inside fit-crown).
      (swap! session assoc-in [:camera-poses idx]
             (bridge/solver-pose->camera (:pose sol) proxy-pose)))
    (swap! session assoc-in [:pnp-picks idx]
           (into {} (map (fn [[ci px]] [ci {:px px :proposed? true}]) picks)))
    (swap! session assoc-in [:acquire-results idx]
           {:pnp? true :auto? true :matched (:n sol) :rms-px (:rms-px sol)
            :outliers (count (:outliers sol))})
    (swap! session assoc-in [:pnp-residuals idx] residuals)
    (swap! session assoc-in [:pnp-outliers idx] (set (map :ci (:outliers sol))))))

(defn- register-one-auto-photo!
  "Detect + identify + register ONE unregistered photo `idx` with zero clicks.
   Samples off-screen, runs blob-detect → fit-crown → blob-snap → PnP, and applies
   the pose only if it clears the rms bar and the crown threshold. Resolves true on
   a registration, else false (poor detection / high rms is a skip, never a throw).
   Recomputes marks + proxy-pose from the CURRENT proxy-mesh so it stays correct
   after photo 0 has moved it."
  [idx]
  (-> (backdrop/load-luminance-sampler (photo-path (:file (nth (:photos @session) idx))))
      (.then (fn [sampler]
               (let [lum-at (:lum-at sampler)
                     [iw ih] (:size sampler)
                     proxy-mesh (:proxy-mesh @session)
                     proxy-pose (:creation-pose proxy-mesh)
                     det (bridge/plate-detect proxy-mesh)
                     marks (mapv #(select-keys % [:id :obj]) (pnp-targets))
                     zero-obj (:zero-obj det)
                     intrinsics (session-intrinsics iw ih)
                     ;; FAST path: hand the raw RGBA array so the detector downsamples
                     ;; in one tight loop, not ~12M lum-at closure calls (the freeze).
                     cands (blob-detect/detect-blobs lum-at [iw ih] {:rgba (:data sampler)})
                     centers (mapv :center cands)     ; best-first (detector score order)
                     ;; GEOMETRIC judge (no pixel reads): a reprojection "hits a disc"
                     ;; if a DETECTED blob sits within its radius. fit-crown's per-
                     ;; candidate scoring runs this thousands of times, so reading the
                     ;; photo there (blob/disc-at?) was the ~5s/photo churn; the blobs
                     ;; are already the pixel evidence. Final accuracy still comes from
                     ;; the real-pixel blob-snap + PnP + rms gate below.
                     judge (fn [[px py] r]
                             (let [r2 (* r r)]
                               (boolean (some (fn [[bx by]]
                                                (<= (+ (* (- bx px) (- bx px)) (* (- by py) (- by py))) r2))
                                              centers))))
                     ;; sample the crown from the TOP-scored blobs only (the discs
                     ;; outrank the noise), so a good quartet lands on the first sample
                     res (match-plate/fit-crown (vec (take auto-fit-blobs centers)) marks zero-obj
                                                intrinsics judge
                                                {:disc-r (:disc-r det) :face-normal (:face-normal det)})]
                 (if-not (and res (:zero-hit? res) (>= (:crown-hits res) min-crown-assign))
                   (do (auto-log! (str "  foto " idx ": corona non riconosciuta ("
                                       (count cands) " blob rilevati"
                                       (when res (str ", " (:crown-hits res) "/12 sui dischi"
                                                      (when-not (:zero-hit? res) ", zero-indice mancante"))) ")"
                                       " — la lascio all'anello / 'p'"))
                       false)
                   (let [picks (into {} (keep (fn [[mi px]]
                                                (some->> (blob/snap-to-blob lum-at px auto-snap-radius)
                                                         :center (vector mi)))
                                              (:pixels res)))
                         corr (vec (for [[ci px] picks]
                                     {:ci ci :world (:obj (nth marks ci)) :px px}))]
                     (if (< (count corr) min-plate-picks)
                       (do (auto-log! (str "  foto " idx ": pochi dischetti agganciati (" (count corr) ")")) false)
                       (if-let [sol (pnp/solve-pnp corr intrinsics {})]
                         (if (<= (:rms-px sol) pnp/accept-rms-px)
                           (do (apply-auto-solve! idx sol picks proxy-pose)
                               (auto-log! (str "  foto " idx ": registrata ✓  rms "
                                               (.toFixed (:rms-px sol) 1) "px, " (count corr) " dischetti"))
                               true)
                           (do (auto-log! (str "  foto " idx ": scartata, rms "
                                               (.toFixed (:rms-px sol) 1) "px > " pnp/accept-rms-px)) false))
                         (do (auto-log! (str "  foto " idx ": PnP senza soluzione")) false))))))))
      (.catch (fn [_] (auto-log! (str "  foto " idx ": errore di caricamento")) false))))

(defn- finish-auto!
  "Common tail of the Auto batch: refresh the view for the current photo, save, and
   report the final tally to BOTH the persistent REPL stream and the status line."
  [total detect-ok ring-ok]
  (when-let [cp (get-in @session [:camera-poses (:current-idx @session)])]
    (viewport/set-camera-pose! cp))
  (when (= :pnp (:mode @session))
    (redraw-pnp-preview!)
    (redraw-overlay-dots!))
  (save-acquire-state!)
  (let [ok (+ detect-ok ring-ok)
        msg (str "Auto: registrate " ok "/" total " foto ("
                 detect-ok " rilevate" (when (pos? ring-ok) (str " + " ring-ok " dall'anello")) ")"
                 (when (< ok total) " — le rimanenti: 'p' a mano"))]
    (auto-log! (str "=== " msg " ==="))
    ;; Nothing registered at all is almost always a WRONG FOCAL (the crown geometry
    ;; can't match at the wrong scale) — the loudest single cause. Point at it.
    (when (zero? ok)
      (auto-log! (str "  ⚠ 0 registrate: controlla la FOCALE (ora " (:focal-mm @session)
                      "mm, sorgente " (name (or (:focal-source @session) :?))
                      ") — dev'essere quella della foto (EXIF), es. 48mm; e che il proxy sia il piatto giusto")))
    (set-status-message! msg))
  (update-panel!))

(defn- ring-fill-then-finish!
  "Second pass of Auto: hand the photos the detector couldn't seed to the FAST ring
   (register-one-ring-photo! — 1-DOF search from an already-registered reference, no
   per-photo detection), then finish. This is why Auto stays cheap: only a few photos
   are detected outright; the rest ride the ring. Photo 0 (the proxy anchor) is never
   ring-filled, so if its detection failed it stays for a manual 'p'."
  [total detect-ok]
  (if-let [[iw ih] (backdrop/image-size)]
    (let [proxy-mesh (:proxy-mesh @session)
          proxy-pose (:creation-pose proxy-mesh)
          det (bridge/plate-detect proxy-mesh)
          marks (mapv #(select-keys % [:id :obj]) (pnp-targets))
          zero-obj (:zero-obj det)
          intrinsics (session-intrinsics iw ih)
          n (count (:photos @session))
          registered? (fn [idx] (:pnp? (get-in @session [:acquire-results idx])))
          refs (filterv (fn [idx] (and (registered? idx) (get-in @session [:camera-poses idx]))) (range n))
          ref-solvers (mapv #(bridge/editor->solver-pose (get-in @session [:camera-poses %]) proxy-pose) refs)
          remaining (filterv (fn [idx] (and (pos? idx) (not (registered? idx)))) (range n))]
      (if (or (empty? refs) (empty? remaining))
        (finish-auto! total detect-ok 0)
        (do
          (auto-log! (str "  anello: propago alle " (count remaining) " foto rimaste da "
                          (count refs) " riferimenti…"))
          (-> (js/Promise.all
               (clj->js (mapv #(register-one-ring-photo! % ref-solvers marks zero-obj
                                                         intrinsics (:disc-r det) proxy-pose)
                              remaining)))
              (.then (fn [ring-results]
                       (let [ring-ok (count (filter identity (vec ring-results)))]
                         (auto-log! (str "  anello: registrate " ring-ok "/" (count remaining) " foto rimaste"))
                         (finish-auto! total detect-ok ring-ok))))))))
    (finish-auto! total detect-ok 0)))

(defn- on-refine-session!
  "'R': one focal for the session and every registered pose refined TOGETHER.

   Each photo is registered alone, and alone it cannot tell a wrong focal from a
   wrong distance: it absorbs the first into the second and reports a clean
   residual either way. Together they can — the lens is one, the distances are
   many — and what is random in the clicking averages instead of settling into
   each pose separately. It is the residual Vincenzo was left with after the
   fusion itself closed to 0.13 mm (2026-08-06): 4-5 px of per-photo rms, worth
   a millimetre or two of depth.

   Reuses each photo's own clicks; asks for nothing new."
  []
  (if-let [[iw ih] (backdrop/image-size)]
    (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
          targets (pnp-targets)
          views (vec (keep (fn [idx]
                             (let [picks (get-in @session [:pnp-picks idx] {})
                                   cam (get-in @session [:camera-poses idx])]
                               (when (and cam (>= (count picks) 4))
                                 {:idx idx
                                  ;; one lens, one session: the pixel size is the
                                  ;; current photo's, which is every photo's
                                  :image-size [iw ih]
                                  :pose (bridge/editor->solver-pose cam proxy-pose)
                                  :picks (vec (for [[ci {:keys [px]}] picks]
                                                {:world (:obj (nth targets ci)) :px px}))})))
                           (range (count (:photos @session)))))
          out (bundle/refine-session views (:focal-mm @session))]
      (if (:error out)
        (set-status-message! (str "Rifinitura: " (:error out)))
        (let [{:keys [focal-mm poses rms-px before]} out]
          (doseq [[view pose] (map vector (filterv #(and (:pose %) (>= (count (:picks %)) 4)) views)
                                   poses)]
            (swap! session assoc-in [:camera-poses (:idx view)]
                   (bridge/solver-pose->camera pose proxy-pose)))
          (swap! session assoc :focal-mm focal-mm :focal-source :manual)
          (auto-log! (str "=== rifinitura congiunta: " (count poses) " foto ==="))
          (auto-log! (str "  focale " (modal/fmt-number (:focal-mm before))
                          " → " (modal/fmt-number focal-mm) " mm"
                          (when (:clamped? out) "  (fermata al limite: guarda i click, non la lente)")))
          (auto-log! (str "  riproiezione " (modal/fmt-number (:rms-px before))
                          " → " (modal/fmt-number rms-px) " px"))
          (save-acquire-state!)
          ;; Re-ENTER the photo, don't just redraw over it. The refinement moves
          ;; two things the viewport only picks up when a photo is loaded: the
          ;; camera pose (viewport/set-camera-pose!) and the FIELD OF VIEW, which
          ;; comes from the focal via set-photo-for-current-focal!. Redrawing the
          ;; overlays alone left the photo you were standing on with the OLD
          ;; framing under the NEW dots — misalignment that was pure display, and
          ;; that vanished as soon as you navigated away and back (Vincenzo
          ;; 2026-08-06: 'se faccio R su una foto sembra si disallineino le
          ;; altre'). One call, the same one every navigation makes.
          (enter-photo! (:current-idx @session))
          (set-status-message!
           (str "Rifinitura: focale " (modal/fmt-number focal-mm) " mm, riproiezione "
                (modal/fmt-number (:rms-px before)) " → " (modal/fmt-number rms-px) " px")))))
    (set-status-message! "Rifinitura: nessuna foto caricata.")))

(defn- on-auto-register!
  "Plate 'a': register every UNREGISTERED photo with zero clicks (fetta C), then fill
   any leftovers with the ring. First pass = detect+identify+PnP per photo,
   SEQUENTIALLY (photo 0 first, so its proxy move precedes the camera solves), yielding
   between photos so the batch never freezes the page; a per-photo trace goes to the
   REPL stream (persistent, unlike the 4s status line). Second pass = the fast ring
   over whatever the detector couldn't seed."
  []
  (if-let [[_iw _ih] (backdrop/image-size)]
    (let [proxy-mesh (:proxy-mesh @session)
          det (bridge/plate-detect proxy-mesh)
          n (count (:photos @session))
          registered? (fn [idx] (:pnp? (get-in @session [:acquire-results idx])))
          pending (filterv #(not (registered? %)) (range n))]
      (cond
        (nil? (:zero-obj det))
        (set-status-message! "Questo piatto non espone lo zero-indice: rivaluta examples/param-acq-plate.clj e riapri.")
        (empty? pending)
        (set-status-message! "Auto: tutte le foto sono già registrate.")
        :else
        (do
          (auto-log! (str "=== Auto (fetta C): rilevo e registro " (count pending) " foto ==="))
          (set-status-message! (str "Auto: rilevo " (count pending) " foto… (dettaglio nella REPL a destra)"))
          (-> (reduce (fn [p idx]
                        (-> p
                            (.then (fn [acc] (-> (register-one-auto-photo! idx)
                                                 (.then (fn [ok] (conj acc ok))))))
                            (.then (fn [acc] (-> (yield-frame) (.then (fn [_] acc)))))))
                      (js/Promise.resolve [])
                      pending)
              (.then (fn [results]
                       (let [detect-ok (count (filter identity (vec results)))]
                         (ring-fill-then-finish! (count pending) detect-ok))))))))
    (set-status-message! "Foto non ancora caricata."))
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

(defn- plausible-hit?
  "True when a ray↔plane hit (object frame) lands within a sane distance of the
   box. A ray nearly PARALLEL to the declared plane — a face seen edge-on, or the
   wrong face declared for the current photo — intersects it far away, producing a
   garbage vertex flung off the piece (Vincenzo 2026-07-24: a stray far point, a
   whole ricalco thrown off-screen). Rejecting it gives feedback instead of a
   silent outlier. Bound = the box's bounding-sphere radius + a generous in-plane/
   offset margin."
  [hit]
  (<= (m/magnitude hit) (+ (* 0.5 (m/magnitude (retrace-dims))) 45.0)))

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

(def ^:private trace-color
  "Single bright yellow for EVERY ricalco's outline. The old active/inactive dim
   split hid finished shapes over the busy photo (Vincenzo 2026-07-24: 'si vede solo
   la seconda'); now which one you're editing reads from its vertex dots, so the
   lines can all stay equally visible."
  0xffcc33)

(defn- trace-items
  "EVERY ricalco's CLOSED yellow outline (world) as show-preview! items (on-top, so
   it reads over the photo). The ACTIVE ricalco is full-bright, the others dimmer,
   so which one you're editing reads. The line is closed (last→first, ≥3 points) to
   match the emitted (poly …), a closed contour (Vincenzo 2026-07-24: 'la linea
   chiusa gialla'). Vertex DOTS are drawn only for the ACTIVE ricalco while EDITING
   it (:retrace mode) — a finished shape shows just its line, not the clutter of its
   handles (Vincenzo 2026-07-24: hide the yellow dots once editing is done). Shared
   by every mode's preview (proxy-preview-items / pnp-preview-items / retrace-
   preview-items) so the traced bezels stay visible after leaving :retrace and
   reproject as the camera moves between photos. Empty data is skipped."
  []
  (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
        active-idx (:ricalco-idx @session)
        editing? (= :retrace (:mode @session))
        ;; Back-face culling: a ricalco whose declared face points AWAY from the
        ;; current photo shouldn't bleed through it (Vincenzo 2026-07-24). The
        ;; current photo's heading is the view direction; a face is front-facing
        ;; when its outward normal points toward the camera (dot with heading < 0).
        ;; Skipped in free orbit (no photo backdrop to bleed through — show all).
        free-orbit? (and (:stage? @session) (not (:in-pose? @session)))
        heading (:heading (current-camera-pose))
        {:keys [ex ey ez]} (bridge/box-basis proxy-pose)
        axis-world [ex ey ez]
        front? (fn [{:keys [axis sign]}]
                 (or free-orbit? (nil? heading)
                     (neg? (m/dot (m/v* (nth axis-world axis) (double sign)) heading))))]
    (vec (mapcat
          (fn [i {:keys [points plane]}]
            (let [active? (= i active-idx)]
             ;; the ricalco you're editing is ALWAYS shown (never culled) — you
             ;; must see what you're tracing, even on a face turned partly away
             ;; (Vincenzo 2026-07-24: the active shape was invisible). Finished
             ;; shapes still cull against the current photo.
              (when (and (seq points) (or (and editing? active?) (front? plane)))
                (let [wpts (mapv #(bridge/local->world proxy-pose %) points)
                    ;; close the outline (last→first) so it reads as the closed poly
                    ;; it will emit; degenerate below 3 points, so left open there.
                      loop-pts (if (>= (count wpts) 3) (conj (vec wpts) (first wpts)) wpts)]
                  (cond-> [{:type :lines
                            :data (mapv (fn [a b] {:from a :to b :color trace-color}) loop-pts (rest loop-pts))
                            :on-top true}]
                    (and editing? active?)
                    (conj {:type :dots :data (mapv (fn [w] {:pos w :radius retrace-dot-radius
                                                            :color trace-color :opacity 0.75}) wpts)}))))))
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
            (if (plausible-hit? hit)
              (do (swap! session update-in (active-r-path :points) (fnil conj []) hit)
                  (redraw-retrace!)
                  (save-acquire-state!)
                  (update-panel!))
              (set-status-message! "Il click cade troppo lontano dalla faccia — usa una foto che la mostra più di fronte"))
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
            (if (plausible-hit? hit)
              (do (swap! session update :marks (fnil conj [])
                         {:name (next-mark-name) :position hit :normal normal})
                  (redraw-marks!)
                  (save-acquire-state!)
                  (update-panel!))
              (set-status-message! "Il click cade troppo lontano dalla faccia — usa una foto che la mostra più di fronte"))
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
          ;; re-render so the red Klein-branch corner dot appears (it's gated to
          ;; :marker mode now — proxy-preview-items only emits it here).
          (viewport/show-preview! (proxy-preview-items))
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

(declare fmt-vec)

(defn- obj-dir->world
  "Lift an OBJECT-frame direction to world through `pose`'s box-basis."
  [pose [x y z]]
  (let [{:keys [ex ey ez]} (bridge/box-basis pose)]
    (m/normalize (m/v+ (m/v* ex x) (m/v+ (m/v* ey y) (m/v* ez z))))))

(defn- ricalco-shape+mark
  "One ricalco → \":id {:shape (poly …) :mark {:position :heading :up}}\", or nil
   below 3 points. Per Vincenzo's design (2026-07-24): the ricalco is emitted with
   an implicit mark so it carries BOTH the 2D outline and its face frame, and
   `(let [q (:id (:shapes A))] (turtle (:mark q) (extrude (:shape q) (f d))))`
   extrudes it ON the face, PERPENDICULAR — instead of a bare 2D poly that follows
   the current turtle. The poly is re-expressed in an in-plane frame centred on the
   ricalco's centroid: v = an in-plane box axis, u = normal × v, so it matches
   Ridley's shape placement (shape-x → -right, shape-y → up, extrude → heading)
   with the mark's heading = OUTWARD face normal and up = v; the extrusion then
   lands un-mirrored and perpendicular. The mark is lifted through `pose` (the
   emitted proxy's anchor pose), so shape and proxy stay coincident."
  [{:keys [name plane points]} pose uniq]
  (when (>= (count points) 3)
    (let [{:keys [axis sign]} plane
          a2 (last (remove #{axis} [0 1 2]))
          normal-obj (m/v* (axis-unit axis) (double sign))
          v-obj (axis-unit a2)
          u-obj (m/cross normal-obj v-obj)
          n (count points)
          centroid-obj (mapv #(/ % n) (reduce m/v+ [0.0 0.0 0.0] points))
          f3 (fn [x] (.toFixed x 3))
          coords (mapcat (fn [p] (let [d (m/v- p centroid-obj)]
                                   [(f3 (m/dot d u-obj)) (f3 (m/dot d v-obj))]))
                         points)]
      (str ":" (uniq name)
           " {:shape (poly " (str/join " " coords) ")"
           " :mark {:position " (fmt-vec (bridge/local->world pose centroid-obj))
           " :heading " (fmt-vec (obj-dir->world pose normal-obj))
           " :up " (fmt-vec (obj-dir->world pose v-obj)) "}}"))))

(defn- shapes-entries
  "\":id {:shape (poly …) :mark {…}}\" strings for every ricalco with ≥3 points,
   names keywordized and uniquified (a map can't hold duplicate keys)."
  [pose]
  (let [seen (atom #{})
        uniq (fn [nm] (loop [n (if (seq nm) nm "ricalco")]
                        (if (contains? @seen n) (recur (str n "-2")) (do (swap! seen conj n) n))))]
    (vec (keep #(ricalco-shape+mark % pose uniq) (ricalchi)))))

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
        stage-btn (.createElement js/document "button")
        frustum-btn (.createElement js/document "button")
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
    ;; P4b stage toggle — always visible, so the free-camera mode is discoverable
    ;; from any Phase-1 state. Label + pressed look are refreshed by update-panel!.
    (set! (.-type stage-btn) "button")
    (.add (.-classList stage-btn) "pilot-btn")
    (.add (.-classList stage-btn) "eaq-stage-btn")
    (.addEventListener stage-btn "click" (fn [_] (toggle-stage!)))
    (.appendChild panel stage-btn)
    ;; P4b frustum toggle — shown only in the stage (update-panel! toggles display).
    (set! (.-type frustum-btn) "button")
    (.add (.-classList frustum-btn) "pilot-btn")
    (.add (.-classList frustum-btn) "eaq-stage-btn")
    (.addEventListener frustum-btn "click" (fn [_] (toggle-frustums!)))
    (.appendChild panel frustum-btn)
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
           :message-el message :pnp-el pnp-box :retrace-el retrace-box :mark-el mark-box
           :stage-btn-el stage-btn :frustum-btn-el frustum-btn)
    (modal/mount-panel! panel)
    (update-panel!)))

(defn- render-pnp-batch-panel!
  "Fetta B (identity-free) controls in the PnP box: a prompt, the click count,
   and Assegna/Annulla/Azzera/Modalità armata/Esci. The user clicks any discs
   (no per-marker buttons — the whole point is not to name them) and 'Assegna'
   recovers the identities in one shot."
  [box]
  (let [batch (pnp-batch)
        c (count batch)
        info (.createElement js/document "div")
        actions (.createElement js/document "div")]
    (set! (.-className info) "eaq-pnp-info")
    (set! (.-textContent info)
          (str "Batch (senza identità): clicca dischetti QUALSIASI ben sparsi "
               "attorno al piatto, poi 'Assegna'. Cliccati " c "/" min-plate-picks
               (when (< c min-plate-picks) (str " (ne servono ≥" min-plate-picks ")"))))
    (.appendChild box info)
    (set! (.-className actions) "eaq-pnp-actions")
    (let [assign (.createElement js/document "button")
          undo (.createElement js/document "button")
          clr (.createElement js/document "button")
          armedb (.createElement js/document "button")
          exit (.createElement js/document "button")]
      (set! (.-type assign) "button")
      (set! (.-textContent assign) "Assegna (r)")
      (set! (.-disabled assign) (< c min-plate-picks))
      (.addEventListener assign "click" (fn [_] (assign-batch!)))
      (set! (.-type undo) "button")
      (set! (.-textContent undo) "Annulla ultimo")
      (set! (.-disabled undo) (zero? c))
      (.addEventListener undo "click" (fn [_] (undo-batch-click!)))
      (set! (.-type clr) "button")
      (set! (.-textContent clr) "Azzera")
      (.addEventListener clr "click" (fn [_] (clear-pnp-picks!)))
      (set! (.-type armedb) "button")
      (set! (.-textContent armedb) "Modalità armata (b)")
      (.addEventListener armedb "click" (fn [_] (toggle-batch-mode!)))
      (set! (.-type exit) "button")
      (set! (.-textContent exit) "Esci (p)")
      (.addEventListener exit "click" (fn [_] (stop-pnp!)))
      (doseq [b [assign undo clr armedb exit]] (.appendChild actions b)))
    (.appendChild box actions)))

(defn- render-pnp-panel!
  "The PnP controls, rendered into :pnp-el and rebuilt each update: a single
   'Registra per punti' button in gizmo mode; in PnP mode the armed-corner
   prompt, corner/marker buttons (colour = corner, filled = placed, white ring =
   armed), and Risolvi/Azzera/Esci — or, on a plate in batch mode (fetta B), the
   identity-free panel (render-pnp-batch-panel!)."
  []
  (when-let [box (:pnp-el @session)]
    (set! (.-innerHTML box) "")
    (cond
      (not= :pnp (:mode @session))
      ;; entry button only from :gizmo — never on top of :retrace (empty box there)
      (when (= :gizmo (:mode @session))
        (let [b (.createElement js/document "button")]
          (set! (.-type b) "button")
          (set! (.-textContent b) "Registra per punti (p)")
          (.addEventListener b "click" (fn [_] (start-pnp!)))
          (.appendChild box b)
          ;; a registration plate can register with ZERO clicks (fetta C): the
          ;; detector finds the crown and fit-crown identifies it. Offer it here
          ;; next to the manual entry, plate-only.
          (when (plate-proxy?)
            (let [a (.createElement js/document "button")]
              (set! (.-type a) "button")
              (set! (.-textContent a) "Auto — rileva e registra (a)")
              (.addEventListener a "click" (fn [_] (on-auto-register!)))
              (.appendChild box a))
            ;; offered only once there is something to refine: with one photo the
            ;; focal and the distance are the same unknown
            (when (>= (count (filter #(>= (count (second %)) 4) (:pnp-picks @session))) 2)
              (let [r (.createElement js/document "button")]
                (set! (.-type r) "button")
                (set! (.-textContent r) "Rifinisci insieme (R)")
                (set! (.-title r)
                      (str "Una focale sola per la sessione e tutte le pose raffinate "
                           "insieme sui click che hai già fatto. Una foto da sola non "
                           "distingue una focale sbagliata da una distanza sbagliata; "
                           "tutte insieme sì."))
                (.addEventListener r "click" (fn [_] (on-refine-session!)))
                (.appendChild box r))))))

      (batch-mode?)
      (render-pnp-batch-panel! box)

      :else
      (let [placed (pnp-picks)
            resid (pnp-residuals)
            outliers (pnp-outliers)
            visible (visible-corner-set)
            targets (pnp-targets)
            lbl (fn [i] (:label (nth targets i)))
            ;; buttons for the points the user can act on: visible (offerable),
            ;; already-placed (status/re-do), or flagged outliers
            shown (sort (into (into (set (keys placed)) outliers) visible))
            armed (:pnp-armed @session)
            occluded (pnp-occluded)
            n (count placed)
            target (count (remove occluded visible))
            rms (get-in @session [:acquire-results (:current-idx @session) :rms-px])
            solved? (seq resid)
            info (.createElement js/document "div")
            corners (.createElement js/document "div")
            actions (.createElement js/document "div")]
        (set! (.-className info) "eaq-pnp-info")
        (set! (.-textContent info)
              (cond
                (seq outliers)
                (str "✓ registrata" (when rms (str " (rms " (.toFixed rms 1) "px)")) " — "
                     (if (> (count outliers) 1) "i punti " "il punto ") (corner-labels outliers)
                     (if (> (count outliers) 1) " non si allineano" " non si allinea")
                     " (rosso): riclicca più preciso, o 'o' per scartarl"
                     (if (> (count outliers) 1) "i" "o") " (nascosto o non allineabile), poi 'r'"
                     " — oppure vai avanti così.")
                (and rms (> rms pnp/accept-rms-px))
                (str "⚠ rms alto (" (.toFixed rms 0) "px) senza un colpevole singolo — "
                     "clicca più preciso o controlla la faccia dichiarata")
                solved?
                (str "✓ fit pulito, rms " (.toFixed rms 1) "px — 'p'/Esci, o ']' per un'altra foto")
                (nil? armed)
                (str "Tutti i " (pnp-noun) " visibili piazzati o nascosti — premi 'r'")
                :else
                (str (str/capitalize (pnp-noun)) " #" (lbl armed) " evidenziato — clicca dov'è, "
                     "o 'o' se è nascosto dal pezzo. Piazzati " n "/" target
                     (when (< n (min-pnp-picks)) (str " (ne servono ≥" (min-pnp-picks) ")")))))
        (.appendChild box info)
        (set! (.-className corners) "eaq-pnp-corners")
        (doseq [i shown]
          (let [b (.createElement js/document "button")
                st (.-style b)
                r (get resid i)
                bad? (contains? outliers i)]
            (set! (.-type b) "button")
            (set! (.-textContent b) (cond bad? (str (lbl i) " ✗")
                                          r (str (lbl i) "·" (.toFixed r 0))
                                          :else (lbl i)))
            (set! (.-minWidth st) "26px")
            (set! (.-color st) (cond bad? "#fff" (contains? placed i) "#111" :else "#ddd"))
            (set! (.-background st) (cond bad? "#ff2020"
                                          (contains? placed i) (hex->css (target-color i))
                                          :else "#333"))
            (set! (.-border st) (if (= i armed) "2px solid #fff" "1px solid #555"))
            (.addEventListener b "click" (fn [_] (arm-corner! i)))
            (.appendChild corners b)))
        (.appendChild box corners)
        (set! (.-className actions) "eaq-pnp-actions")
        (let [solve (.createElement js/document "button")
              clr (.createElement js/document "button")
              batchb (when (plate-proxy?) (.createElement js/document "button"))
              exit (.createElement js/document "button")]
          (set! (.-type solve) "button")
          (set! (.-textContent solve) "Risolvi PnP (r)")
          (set! (.-disabled solve) (< n (min-pnp-picks)))
          (.addEventListener solve "click" (fn [_] (on-solve-pnp!)))
          (set! (.-type clr) "button")
          (set! (.-textContent clr) "Azzera")
          (.addEventListener clr "click" (fn [_] (clear-pnp-picks!)))
          ;; a plate can register identity-free (fetta B) — offer the toggle
          (when batchb
            (set! (.-type batchb) "button")
            (set! (.-textContent batchb) "Senza identità (b)")
            (.addEventListener batchb "click" (fn [_] (toggle-batch-mode!))))
          (set! (.-type exit) "button")
          (set! (.-textContent exit) "Esci (p)")
          (.addEventListener exit "click" (fn [_] (stop-pnp!)))
          (.appendChild actions solve)
          (.appendChild actions clr)
          (when batchb (.appendChild actions batchb))
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
  (let [stage? (:stage? @session)]
    ;; In the stage the Phase-1 sub-panels don't apply — blank their boxes so
    ;; no 'Registra…'/'Ricalca…' entry buttons linger over the free-orbit view.
    (if stage?
      (doseq [k [:pnp-el :retrace-el :mark-el]]
        (when-let [^js b (k @session)] (set! (.-innerHTML b) "")))
      (do (render-pnp-panel!)
          (render-retrace-panel!)
          (render-mark-panel!)))
    ;; Focale is a phase-0-only control (see build-panel!'s hint): the lens
    ;; doesn't change between photos, so re-tuning it later would silently
    ;; rescale a photo the user thinks is already locked in. Disabled, not
    ;; hidden, so it's clear it isn't gone, just not this photo's job — and off
    ;; entirely in the stage.
    (when-let [^js slider (:focal-slider-el @session)]
      (set! (.-disabled slider) (or stage? (not (zero? (:current-idx @session))))))
    (when-let [^js btn (:stage-btn-el @session)]
      (set! (.-textContent btn) (if stage?
                                  "◀ Esci dal palcoscenico"
                                  "▶ Palcoscenico (camera libera)"))
      (if stage? (.add (.-classList btn) "active") (.remove (.-classList btn) "active")))
    ;; Frustum toggle: only meaningful in the stage (in-pose or free), so shown there.
    (when-let [^js fb (:frustum-btn-el @session)]
      (set! (.. fb -style -display) (if stage? "block" "none"))
      (set! (.-textContent fb) (if (:show-frustums? @session)
                                 "Frustum foto: mostrati (nascondi)"
                                 "Frustum foto: nascosti (mostra)"))
      (if (:show-frustums? @session) (.add (.-classList fb) "active") (.remove (.-classList fb) "active")))
    (when-let [^js message (:message-el @session)]
      (set! (.-textContent message)
            (cond
              (and stage? (:in-pose? @session))
              "In posa. Esc = torna a orbitare · clicca un'altra foto per cambiare vista."
              stage?
              (if (:show-frustums? @session)
                "Palcoscenico: orbita l'oggetto. Clicca una foto o il suo frustum per andare in posa. Esc = esci."
                "Palcoscenico: orbita l'oggetto. Clicca una foto per andare in posa. Esc = esci.")
              :else (or (:status-message @session) ""))))
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
          ;; In the stage the current photo is the one you're posed into (or last
          ;; were); highlight it the same way, and mark the strip so CSS can show
          ;; it's a 'go into pose' control now.
          (when (= i (:current-idx @session))
            (.add (.-classList btn) "current"))
          ;; Stage → 'vai in posa'; Phase-1 → the locked per-photo registration view.
          (.addEventListener btn "click"
                             (fn [_] (if (:stage? @session) (go-in-pose! i) (enter-photo! i))))
          (.appendChild strip btn))))))

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
      (if (:stage? @session)
        ;; --- P4b stage keys: Esc leaves the pose (or the stage), [ / ] fly
        ;; between photos; every Phase-1 hotkey is inert here (the registration
        ;; tools are torn down while orbiting).
        (cond
          (= key "Escape")
          (do (.preventDefault e) (.stopPropagation e)
              (if (:in-pose? @session) (leave-pose!) (leave-stage!)))

          (= key "[")
          (do (.preventDefault e) (.stopPropagation e) (go-in-pose! (mod (dec idx) n)))

          (= key "]")
          (do (.preventDefault e) (.stopPropagation e) (go-in-pose! (mod (inc idx) n))))

        (cond
        ;; Escape backs out of the active sub-mode ONLY (one step at a time). It
        ;; never tears the session down — an extra Esc at the top level is a no-op,
        ;; not an accidental exit (Vincenzo 2026-07-24: "è facile darne uno di più e
        ;; uscire"). Exit is the explicit "Chiudi" button.
          (= key "Escape")
          (do (.preventDefault e) (.stopPropagation e)
              (cond pnp? (stop-pnp!) retrace? (stop-retrace!) marker? (stop-marker!)
                    mark? (stop-mark!) :else nil))

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

        ;; 'b' (plate only) toggles the identity-free batch flow (fetta B) vs the
        ;; armed flow (fetta A) while in PnP.
          (and pnp? (plate-proxy?) (= key "b"))
          (do (.preventDefault e) (.stopPropagation e) (toggle-batch-mode!))

        ;; 'r' registers: in batch mode it assigns the identity-free clicks first
        ;; (match-plate) then solves; in the armed flow it solves directly.
          (and pnp? (= key "r"))
          (do (.preventDefault e) (.stopPropagation e)
              (if (batch-mode?) (assign-batch!) (on-solve-pnp!)))

        ;; Backspace in batch mode drops the last identity-free click.
          (and pnp? (batch-mode?) (= key "Backspace"))
          (do (.preventDefault e) (.stopPropagation e) (undo-batch-click!))

        ;; 'o' — the armed marker is occluded by the part in this view: skip it
        ;; (drop any pick, mark it hidden so nothing re-places it), then 'r'.
        ;; Armed flow only (batch has no armed target).
          (and pnp? (not (batch-mode?)) (= key "o"))
          (do (.preventDefault e) (.stopPropagation e) (skip-armed-corner!))

          (and pnp? (not (batch-mode?)) (re-matches #"[1-8]" key))
          (do (.preventDefault e) (.stopPropagation e)
              (arm-corner! (dec (js/parseInt key 10))))

          (= key "[")
          (do (.preventDefault e) (.stopPropagation e)
              (enter-photo! (mod (dec idx) n)))

        ;; 's'/'f' act on the gizmo/camera registration — meaningless (and
        ;; disruptive to the frozen pose) during a retrace/mark, so gate them
        ;; out; and meaningless on a registration plate (box-only gestures —
        ;; see plate-proxy?), where they'd only mislead, so redirect to 'p'.
          (and (not retrace?) (not mark?) (= key "s"))
          (do (.preventDefault e) (.stopPropagation e)
              (if (plate-proxy?)
                (set-status-message!
                 "Piatto di registrazione: usa 'p' (PnP sui mark del piatto), non 's'. Gli spigoli di un piatto non registrano.")
                (on-snap!)))

        ;; 'f' fits the shared multi-photo model: a box's turntable joint, or a
        ;; plate's ring (register a few with 'p', 'f' proposes the rest).
          (and (not retrace?) (not mark?) (= key "f"))
          (do (.preventDefault e) (.stopPropagation e)
              (if (plate-proxy?) (on-fit-ring!) (on-fit-turntable!)))

        ;; 'a' (plate only) — Auto: detect + register every photo with zero clicks
        ;; (fetta C). The box has no analogue; say so rather than silently no-op.
          (and (not retrace?) (not mark?) (= key "a"))
          (do (.preventDefault e) (.stopPropagation e)
              (if (plate-proxy?)
                (on-auto-register!)
                (set-status-message! "Auto (a) è solo per il piatto di registrazione.")))

          ;; capital R: refining the whole session is not something to trip into
          ;; while reaching for 'r' (which re-solves THIS photo)
          (and (not retrace?) (not mark?) (= key "R"))
          (do (.preventDefault e) (.stopPropagation e)
              (on-refine-session!))

          (= key "]")
          (do (.preventDefault e) (.stopPropagation e)
              (enter-photo! (mod (inc idx) n))))))))

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
                       (assoc :outliers (vec (get-in @session [:pnp-outliers idx])))
                       (seq (get-in @session [:pnp-occluded idx]))
                       (assoc :occluded (vec (get-in @session [:pnp-occluded idx]))))]))
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
        (doseq [[idx-kw {:keys [picks residuals outliers occluded]}] pnp]
          (let [idx (js/parseInt (name idx-kw) 10)]
            (when (seq picks)     (swap! session assoc-in [:pnp-picks idx] (int-keys picks)))
            (when (seq residuals) (swap! session assoc-in [:pnp-residuals idx] (int-keys residuals)))
            (when (seq outliers)  (swap! session assoc-in [:pnp-outliers idx] (set outliers)))
            (when (seq occluded)  (swap! session assoc-in [:pnp-occluded idx] (set occluded))))))
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

(declare normal-up-obj)

(def ^:private box-face-specs
  "Fallback face name → [axis sign] in the box's object frame (Ridley box convention:
   x=right, y=up, z=heading — so +y=top, +z=front), used only for a proxy WITHOUT
   face-groups. When the mesh HAS face-groups (a box does), face-poses keys off those
   instead, so a face name means the SAME face for `(:x (:faces A))` and
   `(flash-face (:proxy A) :x)`."
  {:right [0 1] :left [0 -1] :top [1 1] :bottom [1 -1] :front [2 1] :back [2 -1]})

(defn- box-axis-face-pose
  "Turtle pose {:position :heading :up} in world for the box face at object-frame
   [axis sign]: heading = OUTWARD normal, position = face centre, up = an in-plane
   box axis (normal-up-obj, deterministic). Same frame the emitted marks use."
  [proxy pose axis sign]
  (let [half (* 0.5 (nth (bridge/dims-from-mesh proxy pose) axis))
        center-obj (assoc [0.0 0.0 0.0] axis (* sign half))
        normal-obj (m/v* (axis-unit axis) (double sign))]
    {:position (bridge/local->world pose center-obj)
     :heading (obj-dir->world pose normal-obj)
     :up (obj-dir->world pose (normal-up-obj normal-obj))}))

(defn- facegroup-outward-normal
  "Average OUTWARD world normal of a mesh face-group's triangles, flipped to point
   away from the mesh centre so triangle winding can't invert the sign."
  [verts tris mesh-center]
  (let [idxs (distinct (mapcat identity tris))
        pts (mapv #(nth verts %) idxs)
        n (max 1 (count pts))
        centroid (mapv #(/ % n) (reduce m/v+ [0.0 0.0 0.0] pts))
        nsum (reduce m/v+ [0.0 0.0 0.0]
                     (map (fn [[a b c]]
                            (m/normalize (m/cross (m/v- (nth verts b) (nth verts a))
                                                  (m/v- (nth verts c) (nth verts a)))))
                          tris))
        nrm (m/normalize nsum)]
    (if (neg? (m/dot nrm (m/v- centroid mesh-center))) (m/v* nrm -1.0) nrm)))

(defn- world-normal->axis-sign
  "Match an outward WORLD normal to the posed box's ±axis directions → [axis sign] in
   the object frame, so a mesh face-group reuses the clean box-axis pose geometry."
  [world-normal pose]
  (apply max-key
         (fn [[axis sign]]
           (m/dot world-normal (obj-dir->world pose (m/v* (axis-unit axis) (double sign)))))
         (for [axis [0 1 2] sign [1 -1]] [axis sign])))

(defn- face-poses
  "The proxy's faces as turtle POSES {:position :heading :up} in world, keyed by the
   mesh's OWN face-group names — so `(turtle (:top (:faces A)) (edit-path-2d …))` and
   `(flash-face (:proxy A) :top)` name the SAME face (Vincenzo 2026-07-26: acquire
   follows the existing box/flash-face convention, it doesn't invent its own). Each
   face-group's outward world normal is matched to the posed box's ±axis so the clean
   box-axis pose geometry (box-axis-face-pose) is reused — only the KEY changes, not
   the pose's up convention. Falls back to box-face-specs for a proxy without
   face-groups. Same world frame the emitted marks use, so faces and marks pose the
   turtle identically."
  [proxy pose]
  (let [verts (:vertices proxy)
        groups (:face-groups proxy)]
    (if (seq groups)
      (let [nv (max 1 (count verts))
            mesh-center (mapv #(/ % nv) (reduce m/v+ [0.0 0.0 0.0] verts))]
        (into {}
              (map (fn [[nm tris]]
                     (let [[axis sign] (world-normal->axis-sign
                                        (facegroup-outward-normal verts tris mesh-center)
                                        pose)]
                       [nm (box-axis-face-pose proxy pose axis sign)]))
                   groups)))
      (into {}
            (map (fn [[nm [axis sign]]] [nm (box-axis-face-pose proxy pose axis sign)])
                 box-face-specs)))))

(def ^:private plane-mark-perp-tol
  "Allowed |heading·up| before a plane mark is called out as not perpendicular.
   0.01 is ~0.6°: far above the 1e-5 the fitter produces and far below anything
   a hand-edit would leave by accident."
  0.01)

(defn ^:export plane-mark
  "A PLANE MARK of an acquisition: `{:position … :heading(=surface normal) … :up …}`,
   optionally with `:from [[x y z] …]`, the triangulated points it was fitted
   through. Returns the map UNCHANGED — it is the resting form of the pair
   `edit-plane-mark ⇄ plane-mark`, so that re-opening one is the family's own
   gesture (put `edit-` in front of the head and Run) instead of wrapping a
   multi-line map by hand.

   Gentle, not silent: it checks the little there is to check — the expected
   keys, and heading ⊥ up — and REPORTS what looks wrong without touching the
   data or refusing it. A mark that has drifted out of square is still the
   user's mark; fixing it quietly would hide a real problem (a hand-edited
   heading, a mark copied between objects), and refusing it would break a source
   that otherwise renders.

   A bare `{…}` literal remains valid wherever a mark is accepted — marks
   emitted before this form exists keep working, they simply do not announce
   what they are.

   Display keys, honoured by the stage and carried through untouched (Vincenzo,
   2026-08-09: «troppi puntini e lineette»): `:show` — `false` hides it,
   `:prove` also draws the points it came from, absent means the plain sign —
   and `:label` — `false` for no name, a string for a different one."
  [m]
  (when (map? m)
    (let [missing (remove #(contains? m %) [:position :heading :up])
          d (when (and (:heading m) (:up m))
              (Math/abs (m/dot (m/normalize (:heading m)) (m/normalize (:up m)))))]
      (when (seq missing)
        (state/capture-println
         (str ";; plane-mark: mancano " (str/join ", " missing)
              " — un mark ha bisogno di posizione, normale e up")))
      (when (and d (> d plane-mark-perp-tol))
        ;; d = |cos θ| between heading and up, so the deviation from square is
        ;; 90° − θ; reported in degrees because that is what a person can judge.
        (let [dev (- 90.0 (* (/ 180.0 Math/PI) (Math/acos (min 1.0 d))))]
          (state/capture-println
           (str ";; plane-mark: heading e up fuori squadra di "
                (modal/fmt-number dev)
                "° — la turtle userà comunque questa coppia"))))))
  m)

;; ============================================================
;; plane-from-edges — il piano come FORMULA, non come copia
;; ============================================================
;;
;; «L'utente può creare un nuovo piano scrivendo (plane-from-edges :spigolo-1
;; :spigolo-2 :curva-1)» (Vincenzo, 2026-08-09). Il guadagno più profondo non è
;; togliere la UI di selezione — è che il piano smette di essere una COPIA dei
;; numeri calcolati una volta e diventa una formula: si rifà a ogni Run dalle
;; prove che nomina. Correggi uno spigolo e il piano lo segue; ne cancelli uno e
;; il piano cambia; e il caso «piano vecchio, prove nuove» smette di esistere.
;;
;; Scritta dentro la mappa dell'acquire, la chiamata viene valutata PRIMA che
;; l'acquire esista, quindi non può risolvere i nomi da sé: restituisce una
;; specifica DIFFERITA che `acquire` risolve dopo aver costruito i suoi :edges.
;; È lo stesso trucco a due tempi di edit-plane-mark.

(defn ^:export plane-from-edges
  "`(plane-from-edges :bordo-alto :bordo-basso)` — il PIANO che passa per i
   bordi nominati, scritto dove sta un mark:

       :marks {:coperchio (plane-from-edges :bordo-alto :bordo-basso)}

   I nomi sono quelli del blocco `:edges` dello STESSO acquire. Vale ogni tipo
   di bordo misurato: uno spigolo dritto (campionato lungo sé stesso), una curva
   (i suoi punti), un cerchio (il suo anello) — tutti diventano punti, e il
   piano è il fit robusto attraverso di essi.

   Due spigoli NON PARALLELI della stessa faccia la fissano esattamente, ed è la
   prova più solida che ci sia qui: uno spigolo dritto si misura senza appaiare
   nessun punto fra le foto, quindi non porta con sé i fantasmi che una curva
   può portare.

   Un ultimo argomento mappa passa dritto nel mark: `(plane-from-edges :a :b
   {:show false})` fa il piano e lo tiene fuori dal disegno.

   Restituisce una SPECIFICA, non ancora un mark: i nomi si possono risolvere
   solo dopo che l'acquire ha costruito i suoi :edges, ed è `acquire` a farlo."
  [& args]
  {:plane-from (vec (remove map? args))
   :opts (first (filter map? args))})

(def ^:private loo-swing-deg
  "Di quanto può ruotare il piano togliendo uno dei bordi che lo definiscono,
   prima che valga la pena dirlo. Due gradi: sotto, è il rumore di misure che
   descrivono la stessa faccia; sopra, i bordi stanno descrivendo superfici
   diverse e il piano è una media fra loro."
  2.0)

(defn- plane-spec? [v]
  (and (map? v) (contains? v :plane-from)))

(defn- resolve-plane-spec
  "Fit del piano di una specifica, o nil dopo aver detto AD ALTA VOCE perché no.
   Un rifiuto porta i suoi numeri, come ovunque in questo canale, e il mark non
   viene creato: meglio un nome che manca di un piano sbagliato che nessuno ha
   modo di sospettare.

   Il verso della normale NON viene dalle camere — qui non ce ne sono, e
   soprattutto una formula deve dare lo stesso risultato a ogni Run, mentre le
   camere si spostano. Punta VIA DAL CENTRO dell'oggetto, che è la regola fisica
   della normale uscente di una faccia."
  [dir nm {:keys [plane-from opts]} edges pose plate? centre]
  (let [;; prefisso corto: la CARTELLA della sessione, non tutto il percorso, e il
        ;; nome del mark. Basta a distinguere due acquire e sta su una riga.
        folder (last (remove empty? (str/split (str dir) #"/")))
        say (fn [msg] (state/capture-println
                       (str ";; plane-from-edges · " folder " · :" (name nm) " · " msg)))
        ;; due decimali: in una riga sintetica 26.5651° è rumore, 26.57° è la misura
        n2 (fn [x] (modal/fmt-number (/ (js/Math.round (* 100.0 x)) 100.0)))
        missing (remove #(contains? edges %) plane-from)
        pts (vec (mapcat #(pcurve/edge-points (get edges %)) plane-from))]
    (cond
      (empty? plane-from)
      (do (say "nessun bordo nominato · ? plane-from-edges") nil)

      (seq missing)
      (do (say (str "non trovo " (str/join ", " missing) " fra gli :edges")) nil)

      (< (count pts) 3)
      (do (say "punti insufficienti per un piano") nil)

      :else
      (if-let [pl (pcurve/plane-from-points
                   pts {:up-hints (if plate? [(:heading pose) (:up pose)]
                                      [(:up pose) (:heading pose)])})]
        (if (< (:width-mm pl) pcurve/min-width-mm)
          (do (say (str "NON creato · bordi in fila (larghi "
                        (n2 (:width-mm pl)) " mm, min "
                        (n2 pcurve/min-width-mm) ") · 1 mm d'errore = "
                        (n2 (:tilt-per-mm-deg pl)) "° · serve un bordo "
                        "trasversale · ? plane-from-edges"))
              nil)
          (let [outward (m/v- (:position pl) centre)
                flip? (neg? (m/dot (:heading pl) outward))
                mk (cond-> (select-keys pl [:position :heading :up])
                     flip? (update :heading #(m/v* % -1.0)))]
            ;; QUANTO DI OGNI BORDO È SERVITO. Il fit è robusto: un bordo che sta
            ;; oltre `plane-outlier-mm` dal piano su cui gli altri sono d'accordo
            ;; viene scartato — ed è giusto, perché un bordo di un'ALTRA faccia
            ;; non deve poter inclinare questo piano. Ma scartarlo in silenzio no:
            ;; si nomina un terzo bordo credendo di rinforzare il piano, e invece
            ;; non conta niente, senza che nulla lo dica (Vincenzo, 2026-08-10:
            ;; «del terzo prende solo il centro, è giusto?»).
            (doseq [en plane-from]
              (let [ps (pcurve/edge-points (get edges en))
                    d (fn [q] (Math/abs (m/dot (m/v- q (:position pl)) (:heading pl))))
                    ds (map d ps)
                    out (count (filter #(> % pcurve/plane-outlier-mm) ds))]
                (when (pos? out)
                  (say (str en (if (= out (count ps))
                                 " SCARTATO"
                                 (str " · usati " (- (count ps) out) "/" (count ps) " punti"))
                            " · fino a " (n2 (reduce max ds)) " mm fuori (max "
                            pcurve/plane-outlier-mm ") · ? plane-from-edges")))))
            ;; LEAVE-ONE-OUT: di quanto ruoterebbe il piano togliendo ciascun
            ;; bordo. È la domanda che la planarità in millimetri non risponde —
            ;; sui dati veri di Vincenzo 0.69 mm di scarto su una nuvola larga
            ;; 8 mm valgono NOVE GRADI, e una soglia assoluta in mm lasciava
            ;; passare tutto in silenzio. Con due bordi non ha senso (togliendone
            ;; uno resta una retta, che sta su infiniti piani); da tre in su è la
            ;; misura di quanto le prove sono d'accordo fra loro.
            (when (> (count plane-from) 2)
              (let [hints (if plate? [(:heading pose) (:up pose)] [(:up pose) (:heading pose)])
                    swing (fn [en]
                            (let [ps (vec (mapcat #(pcurve/edge-points (get edges %))
                                                  (remove #(= % en) plane-from)))]
                              (when-let [o (and (>= (count ps) 3)
                                                (pcurve/plane-from-points ps {:up-hints hints}))]
                                [en (* (/ 180.0 Math/PI)
                                       (Math/acos (min 1.0 (Math/abs (m/dot (:heading pl)
                                                                            (:heading o))))))])))
                    swings (sort-by (comp - second) (keep swing plane-from))]
                (when (> (or (second (first swings)) 0.0) loo-swing-deg)
                  (say (str "prove in disaccordo · "
                            (str/join " · " (map (fn [[en d]]
                                                   (str "senza " en " " (n2 d) "°"))
                                                 swings))
                            " · ? plane-from-edges")))))
            (when (> (:flatness-mm pl) 1.0)
              (say (str "planarità " (n2 (:flatness-mm pl)) " mm")))
            (merge mk {:from (mapv vec (pcurve/subsample (:points pl) 12))} opts)))
        (do (say "i bordi nominati non definiscono un piano · ? plane-from-edges") nil)))))

(defn- resolve-plane-specs
  "Sostituisce ogni `(plane-from-edges …)` di `:marks` col piano che nomina. Le
   specifiche che non si risolvono spariscono, dopo aver detto perché."
  [dir marks edges pose plate? centre]
  (reduce-kv (fn [acc nm v]
               (if-not (plane-spec? v)
                 (assoc acc nm v)
                 (if-let [mk (resolve-plane-spec dir nm v edges pose plate? centre)]
                   (assoc acc nm mk)
                   acc)))
             {} marks))

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
   (let [posed (resolve-proxy opts)
         pose (or (:pose opts) (:creation-pose posed))
         faces (face-poses posed pose)
         edges (or (:edges opts) {})
         ;; ogni `(plane-from-edges …)` fra i :marks diventa QUI il suo piano:
         ;; dopo che gli :edges esistono, prima che chiunque legga i marks
         marks (resolve-plane-specs dir (or (:marks opts) {}) edges pose
                                    (boolean (seq (:anchors posed)))
                                    (:position pose))]
     (record-scaffolds! [posed])
     ;; A mark named like one of the proxy's faces WINS over it in
     ;; `(turtle A :at …)` (turtle/named-poses merges faces under marks, on
     ;; purpose: a name you chose beats a generated one). That is the right
     ;; precedence and a silent surprise, so say it once — the plate's own faces
     ;; are called :top/:bottom/:side, which are tempting names for a zone.
     (when-let [clash (seq (filter (set (keys faces)) (keys marks)))]
       (state/capture-println
        (str ";; acquire · " dir ": " (str/join ", " (map str clash))
             (if (next clash) " sono nomi" " è un nome")
             " di faccia del proxy — il mark ha la precedenza, quindi "
             "(turtle A :at " (first clash) " …) userà il MARK. "
             "La faccia resta raggiungibile come (" (first clash) " (:faces A)).")))
     ;; The same silent-precedence trap one level up: a mark and an edge that
     ;; share a name are BOTH the user's, so neither is the obvious winner, and
     ;; `(turtle A :at …)` has to pick one (the mark). Worth saying, because an
     ;; edge's heading runs along it and a mark's points out of a surface — the
     ;; wrong one of the two aims an extrusion 90° away.
     (when-let [clash (seq (filter (set (keys marks)) (keys edges)))]
       (state/capture-println
        (str ";; acquire · " dir ": " (str/join ", " (map str clash))
             (if (next clash) " sono nomi" " è un nome")
             " sia di mark che di spigolo — (turtle A :at " (first clash)
             " …) userà il MARK. Lo spigolo resta raggiungibile come ("
             (first clash) " (:edges A)).")))
     ;; P4b: note the stage so the post-eval hook (core/after refresh-viewport!)
     ;; turns this evaluated directive into the interactive palcoscenico —
     ;; clickable frustums + click→pose, viewport state, camera left where it is.
     ;; :marks travel with the note so the stage can draw them FROM THE SOURCE
     ;; VALUE (dev-docs/brief-plane-marks.md, Seguito 3): any divergence between
     ;; what was emitted and what is displayed shows up at once, instead of
     ;; three steps later as displaced geometry.
     (stage/note-eval! {:proxy posed :pose pose :dir dir
                        :marks marks
                        :edges edges})
     {:proxy posed
      :pose pose
      :shapes (or (:shapes opts) {})
      :marks marks
      ;; Measured EDGES of the object (brief-observation-driven-acquire, gradino
      ;; 3): each one a pose that runs ALONG the edge, plus its two ends. Kept
      ;; apart from :marks on purpose — a mark's heading is a surface normal and
      ;; an edge's is a direction, and everything that reads marks as planes
      ;; (acquire-union's anchors, above all) would quietly misread an edge as a
      ;; plane whose normal points down its own length.
      :edges edges
      ;; P4b Pezzo (iii): the 6 box faces as turtle poses, so the user can drop the
      ;; turtle onto a face by name — `(turtle (:top (:faces A)) (edit-path-2d …))`
      ;; — and draw the ricalco there over the stage backdrop. Computed, not stored.
      :faces faces
      :dir dir})))

(def ^:private edge-length-tol-mm
  "Allowed disagreement between an edge's declared :length and the distance
   between its own ends. 0.01 mm is far above the emitter's rounding and far
   below anything a hand-edit would leave by accident."
  0.01)

(defn ^:export edge-mark
  "A MEASURED EDGE of an acquisition: a pose that runs ALONG the edge —
   `{:position <one end> :heading <direction> :up …}` — plus `:a`/`:b`, its two
   ends, and `:length`, their distance. Returns the map UNCHANGED; it is the
   resting form the stage's Spigolo gesture writes, in the same family as
   `plane-mark`.

   Heading along the edge, not across it, is what makes it useful without any new
   DSL: `(turtle (:spigolo-1 (:edges A)) (extrude (circle 2) (f 42.13)))` lays a
   fillet down the whole edge, because `(f …)` travels the heading.

   Gentle, not silent, exactly like plane-mark: it checks the little there is to
   check — the expected keys, and that :length still matches the ends — and
   REPORTS what looks wrong without touching the data. An edge whose length has
   been hand-edited is still the user's edge; correcting it quietly would hide
   the fact that the two no longer describe the same segment.

   Display keys, honoured by the stage and carried through untouched (Vincenzo,
   2026-08-09: «troppi puntini e lineette»): `:show` — `false` hides it,
   `:prove` also draws the points it came from, absent means the plain sign —
   and `:label` — `false` for no name, a string for a different one."
  [e]
  (when (map? e)
    (let [missing (remove #(contains? e %) [:position :heading :a :b])]
      (when (seq missing)
        (state/capture-println
         (str ";; edge-mark: mancano " (str/join ", " missing)
              " — uno spigolo ha bisogno dei suoi due capi e di una posa che li percorra")))
      (when (and (:a e) (:b e) (:length e))
        (let [d (m/magnitude (m/v- (:b e) (:a e)))]
          (when (> (Math/abs (- d (:length e))) edge-length-tol-mm)
            (state/capture-println
             (str ";; edge-mark: :length dice " (modal/fmt-number (:length e))
                  " mm ma fra :a e :b ce ne sono " (modal/fmt-number d)
                  " — uno dei due è stato modificato a mano")))))))
  e)

(defn ^:export circle-mark
  "A MEASURED CIRCLE of an acquisition: `{:position <centre> :heading <axis> :up …
   :radius r}` — the curved sibling of `edge-mark`, written by the stage's
   Spigolo gesture when the bordo it walked turns out to bend.

   A pose first, like every mark, and the pose is the useful part: the turtle
   stands at the CENTRE with its nose along the axis, so `(turtle (:cerchio-1
   (:edges A)) (extrude (circle 12.4) (f 20)))` raises or bores a cylinder
   exactly where the photographs found one. The radius travels with it because
   that is the number one came for.

   Gentle, not silent, like its siblings: it checks the keys and that the radius
   is a positive number, and reports without touching the data.

   Display keys, honoured by the stage and carried through untouched (Vincenzo,
   2026-08-09: «troppi puntini e lineette»): `:show` — `false` hides it,
   `:prove` also draws the points it came from, absent means the plain sign —
   and `:label` — `false` for no name, a string for a different one."
  [c]
  (when (map? c)
    (let [missing (remove #(contains? c %) [:position :heading :radius])]
      (when (seq missing)
        (state/capture-println
         (str ";; circle-mark: mancano " (str/join ", " missing)
              " — un cerchio ha bisogno di centro, asse e raggio")))
      (when (and (:radius c) (not (and (number? (:radius c)) (pos? (:radius c)))))
        (state/capture-println
         (str ";; circle-mark: :radius " (pr-str (:radius c))
              " non e' un raggio")))))
  c)

(defn ^:export curve-mark
  "A measured CURVED edge of an acquisition: `{:points [[x y z] …]}`, the 3D
   points recovered from the photographs.

   It carries no pose, because a curve has none — and that is the whole of what
   it is for. A curve's value is the PLANE it lies in, and a plane wants
   evidence: several curves, and straight edges too, pooled. So this is stored
   evidence and nothing more, written into the same `:edges` block as its
   straight siblings.

   Why it lives in the SOURCE rather than in the gesture's own memory (Vincenzo,
   2026-08-07): «non sarebbe meglio accumulare le cose (piani, segmenti) nel
   sorgente, così li posso cancellare come testo invece che nella UI?». Yes — and
   it is what this channel does everywhere else. A bench that lives in the source
   can be renamed, deleted, kept across sessions and diffed, with no editing UI
   at all; one that lives in a gesture's state needs a UI for each of those, and
   is lost the moment the gesture closes.

   Gentle, not silent, like the rest of the family.

   Its points are drawn only when it asks for them with `:show :prove` — forty
   dots per curve is exactly the clutter that key exists to stop. `:show false`
   hides it entirely, `:label false`/`\"testo\"` its name."
  [m]
  (when (map? m)
    (let [pts (:points m)]
      (when-not (and (sequential? pts) (>= (count pts) 3))
        (state/capture-println
         (str ";; curve-mark: servono almeno 3 punti in :points — questo bordo curvo "
              "non e' utilizzabile come evidenza per un piano")))))
  m)

;; ------------------------------------------------------------
;; acquire-union — fusing two shooting sessions (brief-session-fusion.md)
;; ------------------------------------------------------------

(defn- acq? [x] (and (map? x) (contains? x :marks) (contains? x :proxy)))

;; ---- the call shape ----
;;
;;   (acquire-union [[:A a] [:B b]])                       ; same name = same zone
;;   (acquire-union [[:A a] [:B b]] [[:A/p1 :B/p3] …])     ; when names cannot agree
;;
;; ONE session list, always labelled, because the labels are what let every mark
;; survive the fusion under its own address (`:A/testa`, `:B/testa`) — and it is
;; that addressability which makes the implicit rule safe again: a shared name
;; is now something the user WROTE, not something two counters happened to agree
;; on. The list is a VECTOR, not a map, because its order answers 'whose frame
;; is the fused frame': the first one's.
;;
;; The resolution itself is pure and lives in photogrammetry.fuse, with its
;; tests; here there is only the shape of the call.

(defn- labelled-sessions?
  [x]
  (and (vector? x) (seq x)
       (every? #(and (vector? %) (= 2 (count %)) (keyword? (first %)) (acq? (second %))) x)))

(defn- marks-by-label
  "[[label acquire] …] → [[label marks] …], which is all fuse/declared-anchors
   needs to know about a session."
  [sessions]
  (mapv (fn [[l a]] [l (:marks a)]) sessions))

(defn- report-union!
  "Print the fit the way mesh-board prints fidelity: the numbers that decide
   whether to trust it, per anchor, in millimetres. A fused frame that is
   quietly 2 mm out looks exactly like a good one until an extrusion misses the
   object — so the residual is not optional output."
  [dir fit]
  (state/capture-println
   (str ";; acquire-union · " dir " → frame della prima sessione\n"
        (str/join "\n"
                  ;; `str`, NOT clj->js: clj->js on a keyword keeps only its name,
                  ;; which would silently drop the :A/ label that says WHICH
                  ;; session's mark this line is about.
                  (map (fn [{:keys [name kind residual-mm normal-deg]}]
                         (str ";;   " name " (" (clj->js kind) ")  "
                              (modal/fmt-number residual-mm) " mm"
                              (when (= kind :piano) " dal piano")
                              (when normal-deg (str "  ·  normale " (modal/fmt-number normal-deg) "°"))))
                       (:per-anchor fit)))
        "\n;;   rms " (modal/fmt-number (:rms-mm fit)) " mm dai piani · normali "
        ;; The distance rms alone is not a verdict: with three planes it can
        ;; always be driven to zero (three constraints, six unknowns), so it once
        ;; printed 'rms 0 mm' under a fit whose normals were 152° out (Vincenzo
        ;; 2026-08-06). The angle travels next to it, always.
        "fuori di " (modal/fmt-number (:max-normal-deg fit)) "° al massimo · "
        (:planes fit) " piani"
        (when (pos? (:points fit)) (str " + " (:points fit) " punti"))))
  (when (> (:max-normal-deg fit) 3.0)
    (state/capture-println
     (str ";; acquire-union: normali fuori di " (modal/fmt-number (:max-normal-deg fit))
          "° — le zone combaciano come posizione ma non come ORIENTAMENTO. "
          "Sopra i pochi gradi non è imprecisione: è una zona marcata male, o due "
          "zone che non sono la stessa.")))
  (when-not (:distances-testify? fit)
    (state/capture-println
     (str ";; acquire-union: con tre soli piani gli scarti in mm tornano zero per "
          "costruzione (tre equazioni, tre incognite) — non sono una prova, e qui "
          "l'unica prova sono le NORMALI. Un quarto piano, o un mark su un punto "
          "vero (:point? true), mette alla prova anche i millimetri.")))
  (when-let [s (:suspect fit)]
    (state/capture-println
     (str ";; acquire-union: togliendo " (:name s) " lo scarto crolla da "
          (modal/fmt-number (:rms-mm fit)) " a " (modal/fmt-number (:rms-without s))
          " mm — è quell'aggancio a essere sbagliato, non gli altri. I minimi "
          "quadrati spalmano il danno su tutti, per questo nessuno sembrava "
          "colpevole. Rifallo, o togli quel mark dalla fusione.")))
  (when-let [w (fuse/worst-anchor (:per-anchor fit))]
    (state/capture-println
     (str ";; acquire-union: " (:name w) " si discosta dagli altri ("
          (modal/fmt-number (:residual-mm w)) " mm). "
          "O è misurato male in una delle due sessioni, o i due mark che hai "
          "dichiarato uguali non sono la stessa zona fisica.")))
  (when (> (:rms-mm fit) 1.0)
    (state/capture-println
     (str ";; acquire-union: " (modal/fmt-number (:rms-mm fit))
          " mm di scarto è molto per una fusione — quello che disegni su una "
          "sessione cadrà storto sull'altra di altrettanto."))))

(defn- loop-closure!
  "With THREE or more sessions, the only real proof available.

   Each pairwise fusion is exactly determined in translation when the anchors
   are three planes, so its own residuals are zero whatever it did — a fit can
   be confidently, silently wrong. But going round the loop cannot lie: carrying
   a point from C into A directly, and again via B, must land in the same place.
   On Vincenzo's three sessions that check read 33 mm while every pairwise report
   said 'perfect' (2026-08-06), and it is what caught the branch ambiguity that
   caused it.

   Prints the closure in millimetres — a number that IS the fusion's accuracy."
  [sessions anchors-for fits]
  (let [others (vec (rest sessions))
        by-lbl (into {} (map (fn [{:keys [lbl fit]}] [lbl fit]) fits))
        probe (fn [acq] (or (some-> (first (vals (:marks acq))) :position vec) [0.0 0.0 0.0]))]
    (doseq [[[lx _] [ly by]] (for [i (range (count others)) j (range (count others))
                                   :when (not= i j)]
                               [(nth others i) (nth others j)])
            :let [[anchors errs] (anchors-for lx ly by)
                  mid (when (empty? errs) (fuse/fit-rigid anchors))]
            :when (and mid (nil? (:error mid)) (by-lbl lx) (by-lbl ly))]
      (let [p (probe by)
            direct (fuse/transform-point (by-lbl ly) p)
            via (fuse/transform-point (by-lbl lx) (fuse/transform-point mid p))
            mm (Math/sqrt (reduce + 0.0 (map (fn [u v] (* (- u v) (- u v))) direct via)))]
        (state/capture-println
         (str ";; acquire-union: anello " (name (first (first sessions))) "→" (name lx)
              "→" (name ly) " chiude a " (modal/fmt-number mm) " mm"
              (cond
                (> mm 5.0) " — è tanto: due fusioni a due a due si contraddicono, quindi almeno una zona non è la stessa in tutte le sessioni"
                (> mm 1.5) " — accettabile ma non ottimo"
                :else " — le sessioni si accordano fra loro")))))
    (when (< (count others) 2)
      (state/capture-println
       (str ";; acquire-union: con due sole sessioni non c'è nessun anello da chiudere, "
            "quindi niente che possa smentire il fit. Una terza posa dello stesso "
            "oggetto lo metterebbe alla prova.")))))

(defn- transform-edge
  "Carry a measured edge through the fusion motion. Its pose moves like any mark
   (transform-pose keeps the keys it does not know about); its two ENDS are
   points and move as points. :length is invariant — the motion is rigid."
  [rt e]
  (cond-> (fuse/transform-pose rt e)
    ;; a curve's luggage is its cloud of points, each of which moves as a point
    (:points e) (assoc :points (mapv #(vec (fuse/transform-point rt %)) (:points e)))
    ;; a straight edge carries its two ENDS, which are points and move as
    ;; points; a circle carries only its radius, which a rigid motion does not
    ;; touch at all. Same transport, different luggage.
    (:a e) (assoc :a (vec (fuse/transform-point rt (:a e))))
    (:b e) (assoc :b (vec (fuse/transform-point rt (:b e))))))

(defn- fuse-sessions
  "`sessions` is [[label acq] …] with the REFERENCE first; `anchors-for` builds
   the correspondences between two labelled sessions."
  [sessions anchors-for declared?]
  (let [[[ref-lbl a] & others] sessions
        fits (mapv (fn [[lbl b]]
                     (let [[anchors errs] (anchors-for ref-lbl lbl b)]
                       {:lbl lbl :b b :anchors anchors :errs errs
                        :fit (if (seq errs)
                               {:error (str/join " · " errs)}
                               (fuse/fit-rigid anchors))}))
                   others)
        prefix (fn [lbl nm] (keyword (name lbl) (name nm)))]
    (if-let [bad (first (filter #(:error (:fit %)) fits))]
      (do (state/capture-println
           (str ";; acquire-union · " (:dir (:b bad)) ": " (:error (:fit bad))
                "\n;;   agganci trovati: "
                (if (seq (:anchors bad))
                  (str/join ", " (map #(str (:name %)) (:anchors bad)))
                  (if declared?
                    "nessuno — controlla i riferimenti nella lista delle corrispondenze"
                    (str "nessuno — senza la lista delle corrispondenze i mark che "
                         "valgono da aggancio sono quelli che portano lo STESSO NOME "
                         "nelle due sessioni")))))
          nil)
      (let [moved (mapv (fn [{:keys [lbl b fit]}]
                          (report-union! (:dir b) fit)
                          {:label lbl :dir (:dir b) :proxy (:proxy b) :pose (:pose b)
                           :transform (select-keys fit [:R :t :rvec])
                           :rms-mm (:rms-mm fit)
                           :marks (into {} (map (fn [[nm p]] [(prefix lbl nm) (fuse/transform-pose fit p)])
                                                (:marks b)))
                           :edges (into {} (map (fn [[nm e]] [(prefix lbl nm) (transform-edge fit e)])
                                                (:edges b)))})
                        fits)
            ;; Every mark of every session survives under its own address,
            ;; `:label/name` — nothing collides, nothing is dropped, and nothing
            ;; is averaged (with plane semantics two origins of the same zone are
            ;; different points ON that plane, so their mean is a third arbitrary
            ;; one). On top of that, each declared ZONE also answers to its bare
            ;; name, bound to the reference session's mark: `:testa` is the zone,
            ;; `:A/testa` and `:B/testa` are the two measurements of it.
            zones (into {} (for [{:keys [anchors]} fits
                                 {:keys [ref-name]} anchors
                                 :when ref-name
                                 :let [p (get (:marks a) ref-name)]
                                 :when p]
                             [ref-name p]))
            marks (reduce (fn [acc {:keys [marks]}] (merge marks acc))
                          (merge zones
                                 (into {} (map (fn [[nm p]] [(prefix ref-lbl nm) p]) (:marks a))))
                          moved)
            ;; Edges follow the same addressing — every one survives as
            ;; `:label/name` — with one difference: the REFERENCE session's edges
            ;; also keep their bare names. A mark's bare name is reserved (it
            ;; means the declared ZONE, as opposed to one session's measurement of
            ;; it), but an edge belongs to exactly one session and has no such
            ;; second meaning, so leaving A's names alone costs nothing and keeps
            ;; every `(:spigolo-1 (:edges A))` already written against A working
            ;; the day a second session is fused onto it.
            edges (reduce (fn [acc {:keys [edges]}] (merge edges acc))
                          (merge (:edges a)
                                 (into {} (map (fn [[nm e]] [(prefix ref-lbl nm) e]) (:edges a))))
                          moved)]
        (loop-closure! sessions anchors-for fits)
        ;; The stage works in the REFERENCE session's frame — the fused frame —
        ;; and gets EVERY session's photos: each one's cameras carried here by
        ;; the motion just fitted, so `[`/`]` walks the whole film and the object
        ;; can be traced from angles no single turntable pass could reach. Which
        ;; is the entire point of the fusion, and the check on it too: a mark
        ;; measured in B must land on the object in A's photos.
        (stage/note-eval! {:proxy (:proxy a) :pose (:pose a) :dir (:dir a)
                           :marks marks :edges edges
                           :sessions (into [{:dir (:dir a) :label ref-lbl
                                             :emit-pose (:pose a) :transform nil}]
                                           (map (fn [{:keys [dir label pose transform]}]
                                                  {:dir dir :label label
                                                   :emit-pose pose :transform transform})
                                                moved))})
        (assoc a
               :marks marks
               :edges edges
               :sessions (into [{:label ref-lbl :dir (:dir a) :proxy (:proxy a)
                                 :pose (:pose a) :transform nil :rms-mm 0.0}]
                               (mapv #(dissoc % :marks) moved)))))))

(defn ^:export acquire-union
  "Two (or more) sessions of the same object as ONE value, in the FIRST one's
   frame.

     (acquire-union [[:A a] [:B b]])                     ; same name = same zone
     (acquire-union [[:A a] [:B b]] [[:A/p1 :B/p3] …])   ; when names cannot agree

   The join is DECLARED, never detected: no feature matching, which is what
   makes this work on smooth textureless plastic. By default the declaration is
   the NAME — a mark called `:testa` in two sessions says those are the same
   zone. That rule was a hazard while the stage's counters (`:piano-1`,
   `:piano-2`, …) were the only names around, because two sessions then agreed
   by accident; it is safe now that every mark survives the fusion under its own
   address, so naming two marks alike is a deliberate act (Vincenzo 2026-08-05).
   Rename them in the source as you create them — that is normal text editing,
   and it is done while you still remember which zone was which.

   The second argument is the escape hatch for when the names cannot agree —
   when `:top` means one face in one session and another face in the other, or
   when you would rather not rename at all.

   Marks are believed as PLANES: only the plane matters, not where the origin
   sits on it (see the reference card). Three planes with independent normals
   determine the motion; two do not, and are refused rather than guessed.

   Pure and recomputed at every eval: the motion is never written into the
   source, so improving a mark and re-running improves the fusion. The price,
   declared: the anchor marks must STAY in the source — they are the join.

   Returns the fused value: the reference session's :proxy/:pose/:faces/:dir,
   `:sessions` (one entry each, with the :transform applied and its :rms-mm),
   and `:marks` holding EVERY mark of every session under `:label/name`, plus
   each declared zone under its bare name (the reference session's measurement
   of it). Or nil, with a printed reason, when the anchors do not determine a
   motion — never a plausible-looking one.

   Not fused in v1: `:shapes` stay the reference session's (a traced outline is
   geometry, not a pose; transporting it is a separate move)."
  [& args]
  (let [[x y] args]
    (cond
      (not (labelled-sessions? x))
      (do (state/capture-println
           (str ";; acquire-union: le sessioni vanno etichettate, così ogni mark resta "
                "indirizzabile dopo la fusione:\n"
                ";;   (acquire-union [[:A A] [:B B]])"))
          nil)

      (< (count x) 2)
      (do (state/capture-println
           ";; acquire-union: una sessione sola non è una fusione — passane due")
          (second (first x)))

      ;; with a correspondence list: read it as written. The builder takes BOTH
      ;; labels, so the loop-closure check can ask for a pair that does not
      ;; involve the reference at all.
      (and (vector? y) (seq y) (every? vector? y))
      (fuse-sessions x (fn [to-lbl lbl _b] (fuse/declared-anchors (marks-by-label x) y to-lbl lbl)) true)

      (some? y)
      (do (state/capture-println
           (str ";; acquire-union: il secondo argomento, se c'è, è la lista delle "
                "corrispondenze — es. [[:A/p1 :B/p3] [:A/p2 :B/p1]]"))
          nil)

      ;; without one: the shared names ARE the declaration
      :else
      (let [marks-of (into {} (map (fn [[l a]] [l (:marks a)]) x))]
        (fuse-sessions x
                       (fn [to-lbl _lbl b] [(fuse/shared-name-anchors (get marks-of to-lbl) (:marks b)) nil])
                       false)))))

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
    (teardown-frustum-listeners!) ; removes the stage click-a-frustum pointer handlers
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

;; Compact source-number rendering now lives in modal-evaluator, shared with the
;; acquire STAGE — which appends plane marks to this same emitted form later and
;; must format them identically (dev-docs/brief-plane-marks.md).
(def ^:private fmt-n modal/fmt-number)
(def ^:private fmt-vec modal/fmt-vec3)

(defn- find-marker []
  (modal/find-form-bounds (cm/get-value) marker-prefix))

(defn- normal-up-obj
  "An in-plane object-frame `up` for a face `normal` (an axis unit): the next box
   axis, so heading(=normal)⊥up. Gives the mark a full, deterministic frame."
  [normal]
  (let [a (or (some (fn [i] (when (> (js/Math.abs (nth normal i 0)) 0.5) i)) [0 1 2]) 0)]
    (assoc [0.0 0.0 0.0] (mod (inc a) 3) 1.0)))

(defn- marks-entries
  "\":id {:position [world] :heading [world] :up [world]}\" strings for the session's
   named marks, lifted through `pose` (the emitted proxy's anchor pose) — a POSE
   (not a bare point) so each plugs straight into `(turtle (:id (:marks A)) …)` and
   the anchor machinery. :heading = the face normal, :up = an in-plane box axis;
   object-frame position/normal lifted to world through the SAME pose the box is
   emitted at, so marks and proxy stay coincident. Names keywordized + uniquified."
  [pose]
  (let [{:keys [ex ey ez]} (bridge/box-basis pose)
        world-dir (fn [[nx ny nz]]
                    (m/normalize (m/v+ (m/v* ex nx) (m/v+ (m/v* ey ny) (m/v* ez nz)))))
        seen (atom #{})
        uniq (fn [nm] (loop [n (if (seq nm) nm "mark")]
                        (if (contains? @seen n) (recur (str n "-2")) (do (swap! seen conj n) n))))]
    (mapv (fn [{:keys [name position normal]}]
            (str ":" (uniq name)
                 " {:position " (fmt-vec (bridge/local->world pose position))
                 " :heading " (fmt-vec (world-dir normal))
                 " :up " (fmt-vec (world-dir (normal-up-obj normal))) "}"))
          (marks))))

(defn- fmt-map-block
  "Render a source map from its \"key value\" entry strings, one per line, with
   both braces alone on their own line (pretty-print, so the emitted form is
   readable instead of one long line — Vincenzo 2026-07-24). `owner` is the key
   this map is the value of (e.g. \":shapes\"), `key-indent` the indent string
   where that key sits, so alignment = key-indent + width of \"<owner> {\".
   Empty → \"{}\".

   Same layout as source-edit/append-map-entry, and for the same reason: every
   entry is a whole LINE, so undoing one is deleting that line (Vincenzo
   2026-08-07). Two writers of the same block must agree, or a re-confirm would
   undo the layout the stage's writes had."
  [owner entries key-indent]
  (if (empty? entries)
    "{}"
    (let [align (str key-indent (apply str (repeat (+ (count owner) 2) " ")))]
      (str "{\n" align (str/join (str "\n" align) entries) "\n" align "}"))))

(defn- preserved-entries
  "The entries of the marker's own `:kw {…}` block, as they are written NOW.

   On a re-open the marker still holds everything the previous `(acquire …)`
   carried — the user only renamed its head — so its text is where the entries
   this session knows nothing about live: a plane mark the STAGE wrote there, or
   anything hand-edited. Reading them back is what stops a re-confirm from
   deleting them (Vincenzo 2026-07-31: 'è abbastanza seccante che rifare la
   edit-acquire cancelli i marks'). nil on a first emission, when there is no
   block yet."
  [kw]
  (when-let [[from to] (find-marker)]
    (let [text (cm/get-value)]
      (when-let [[o e _] (src/map-value-bounds text from to kw)]
        (src/map-entries (.substring text o e))))))

(defn- merge-entries
  "The session's entries laid over whatever the source already had, matched by
   key: an entry this session owns is regenerated, every other one is kept with
   its own bytes, and the source's order is preserved so a confirm does not
   shuffle the map around.

   This is the demarcation made operational — registration owns :proxy and
   :pose, the stage owns what it wrote — without splitting the emitted form in
   two, which would have broken the v1 decision that it be self-contained."
  [existing session-entries]
  (let [key-of (fn [s] (second (re-find #"^(:[^\s]+)" s)))
        by-key (into {} (map (juxt key-of identity)) session-entries)
        kept (mapv (fn [{:keys [key text]}] (get by-key key text)) existing)
        seen (set (map :key existing))]
    (into kept (remove #(contains? seen (key-of %))) session-entries)))

(defn- marker-proxy-expr
  "The SOURCE text of the :proxy value in the (edit-acquire …) marker, read back
   from the buffer — e.g. \"piatto-carta\" or \"(box 20 40 60)\". Lets the emitted
   (acquire …) keep the SAME proxy the user opened with, which matters for a plate:
   a dims-based (box …) would throw away its cylinder shape AND its crown marks
   (Vincenzo 2026-07-29: the plate emitted+re-rendered as a box). nil when there's
   no marker, no :proxy, or the marker doesn't read as EDN (then the caller falls
   back to the (box …) reconstruction)."
  []
  (when-let [[from to] (find-marker)]
    (try
      (let [opts (nth (reader/read-string (subs (cm/get-value) from to)) 2 nil)]
        (when (and (map? opts) (contains? opts :proxy))
          (pr-str (:proxy opts))))
      (catch :default _ nil))))

(defn- emit-acquire-code
  "The (acquire \"dir\" {…}) source that replaces the marker on confirm, PRETTY-
   PRINTED (multi-line, indented to the marker's column `col`). The emitted pose
   keeps the ACQUIRED orientation but re-anchors the CENTRE to the construction
   turtle's position at open time (:build-pose) — object near the turtle/origin,
   re-entry still overlays the photos (session file restores the acquired
   position). The proxy: a plate keeps its ORIGINAL expression (marker-proxy-expr —
   the cylinder + crown marks a (box …) would lose); a box is reconstructed from
   its ACTUAL extents (bridge/dims-from-mesh). :shapes = the ricalchi as named
   (poly …), :marks = named points as poses; both destructurable by name, lifted
   through the SAME anchor pose (P4a-3)."
  [col]
  (let [proxy (:proxy-mesh @session)
        pose (:creation-pose proxy)                 ; acquired pose (orientation kept)
        anchor-pose {:position (get-in @session [:build-pose :position] [0 0 0])
                     :heading (:heading pose) :up (:up pose)}
        [w h d] (bridge/dims-from-mesh proxy pose)
        proxy-expr (or (when (plate-proxy?) (marker-proxy-expr))
                     (str "(box " (fmt-n w) " " (fmt-n h) " " (fmt-n d) ")"))
        ind (apply str (repeat col " "))
        i3 (str ind "   ")]                          ; column of the map's keys (:pose …)
    (str "(acquire " (pr-str (:base-dir @session)) "\n"
         ind "  {:proxy " proxy-expr "\n"
         i3 ":pose {:position " (fmt-vec (:position anchor-pose))
         " :heading " (fmt-vec (:heading anchor-pose))
         " :up " (fmt-vec (:up anchor-pose)) "}\n"
         ;; :shapes/:marks are MERGED with what the marker already holds rather
         ;; than regenerated wholesale: this session owns the ricalchi and the
         ;; 'k' marks it can see, and nothing else in those maps is its business.
         i3 ":shapes " (fmt-map-block ":shapes"
                                      (merge-entries (preserved-entries ":shapes")
                                                     (shapes-entries anchor-pose)) i3) "\n"
         i3 ":marks " (fmt-map-block ":marks"
                                     (merge-entries (preserved-entries ":marks")
                                                    (marks-entries anchor-pose)) i3) "\n"
         ;; :edges is emitted EMPTY (this session measures none — edges are the
         ;; stage's Spigolo gesture, which runs after registration is over) but it
         ;; is emitted, so the gesture finds its slot instead of having to insert
         ;; one. Whatever a re-opened marker already carried is preserved, like
         ;; the other two blocks.
         i3 ":edges " (fmt-map-block ":edges" (preserved-entries ":edges") i3) "})")))

(defn- confirm!
  "OK: write the aligned proxy+pose back to source as (acquire \"dir\" {…}),
   replacing the (edit-acquire …) marker, then tear down and re-run the
   definitions so the emitted form renders. With no marker in source (a legacy
   REPL open, from-marker? false), there's nothing to rewrite — print the form so
   the dev can copy it, then discard."
  []
  (when @session
    ;; Re-orient to the standard pose right before emitting, so the emitted
    ;; (acquire …) is ALWAYS upright/axis-aligned regardless of what happened
    ;; during the session (a snap/gizmo re-registration reverts the proxy to the
    ;; acquisition frame; a fresh acquisition only builds its ring mid-session).
    ;; Idempotent when open already canonicalized; a no-op without a clean ring.
    (canonicalize-orientation!)
    (save-acquire-state!) ; keep acquire-state.json's proxy-pose in sync with :pose
    (let [[from to] (find-marker)
          ;; column of the marker's opening paren, so the pretty-printed
          ;; continuation lines indent to align under it (0 when no marker).
          col (if from
                (let [buf (cm/get-value)
                      nl (.lastIndexOf (.substring buf 0 from) "\n")]
                  (- from (inc nl)))
                0)
          code (emit-acquire-code col)]
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

(defn- build-session-json-from-note
  "Build session.json's TEXT for `dir` from its NOTE.md + image files — the
   same shape and rules cli.cljs's init-session writes, but IN-APP so a fresh
   session (photos + NOTE.md, no session.json yet) opens without the manual
   `node out/paq.js --init-session …` step. Parsing goes through the shared
   ridley.photogrammetry.note, so the tool and the CLI agree on angles and on
   which shots are out-of-ring (θ `libera`). Returns Promise<string>; rejects
   when there is no NOTE.md with a photo table."
  [dir]
  (-> (js/Promise.all
       #js [(-> (stl/desktop-read-file (str dir "/NOTE.md")) (.catch (fn [_] nil)))
            (-> (stl/desktop-list-dir dir) (.catch (fn [_] #js [])))])
      (.then (fn [^js results]
               (let [note-txt (aget results 0)
                     files (->> (array-seq (aget results 1))
                                (map (fn [^js e] (.-name e)))
                                (filter #(re-find #"(?i)\.(jpe?g|png)$" %))
                                sort vec)]
                 (when-not note-txt
                   (throw (js/Error. (str "manca NOTE.md in " dir))))
                 (let [{:keys [photos caliper]} (note/parse-note-text note-txt)]
                   (when-not (seq photos)
                     (throw (js/Error. (str "il NOTE.md di " dir " non ha una tabella foto"))))
                   (let [missing (remove (set files) (map :image photos))]
                     (when (seq missing)
                       (state/capture-println
                        (str "edit-acquire: foto citate nel NOTE ma assenti nella cartella: "
                             (str/join ", " missing)))))
                   (js/JSON.stringify
                    (clj->js {:dir dir
                              :photos (mapv (fn [p] [(:image p) (:theta-deg p)]) photos)
                              :bootstrap (vec (keep #(when (:star? %) (:image %)) photos))
                              :caliper caliper})
                    nil 1)))))))

(defn- ensure-session-json!
  "Resolve to session.json's TEXT for `dir`: read it if present, otherwise BUILD
   it from NOTE.md (+ the folder's images) and write it back — so a fresh session
   opens with no manual CLI step (Vincenzo 2026-07-26: 'non possiamo lanciare
   --init-session a mano per ogni sessione'). Persisting is best-effort: a build
   that can't be written still opens the session."
  [dir]
  (-> (stl/desktop-read-file (str dir "/session.json"))
      (.catch (fn [_]
                (state/capture-println
                 (str "edit-acquire: session.json assente in " dir
                      " — la costruisco dal NOTE.md"))
                (-> (build-session-json-from-note dir)
                    (.then (fn [text]
                             (-> (stl/desktop-write-file text (str dir "/session.json"))
                                 (.then (fn [_] text))
                                 (.catch (fn [_] text))))))))))

(defn- open-session!
  "Shared async session mount: read session.json, install the frozen-camera frame
   callback, seed the session atom (carrying `from-marker?` for the confirm/cancel
   dispatch and `build-pose` — the construction-turtle pose at open time, so the
   emitted object anchors near the turtle instead of at the arbitrary acquisition-
   frame origin), restore acquire-state.json, build the panel, enter photo 0. The
   modal slot must already be claimed by the caller (enter!/request!)."
  [proxy-mesh session-dir from-marker? build-pose]
  (-> (ensure-session-json! session-dir)
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
                                  ;; P4b stage (fetta 1): :stage? = camera freed to
                                  ;; orbit the object (Phase-1 registration off);
                                  ;; :in-pose? = flown into a photo (locked backdrop).
                                  :stage? false
                                  :in-pose? false
                                  ;; P4b frustums (ghost camera pyramids) — shown in
                                  ;; free orbit under this toggle. Default on so the
                                  ;; coverage reads on entering the stage; the button
                                  ;; hides them if they clutter (ergonomics to test).
                                  :show-frustums? true
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
                              ;; Put the object in a standard, intuitive pose (upright,
                              ;; turntable axis → world +Z, at the build turtle) AFTER
                              ;; acquire-state.json set the acquisition-frame poses — a
                              ;; rigid re-description, Phase-1 unaffected. Falls back to
                              ;; a pure translate when there's no clean ring yet.
                              (canonicalize-orientation!)
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
      ;; gizmo/stage: in free orbit re-show the frustums too, else the plain object.
      (viewport/show-preview! (if (and (:stage? @session) (not (:in-pose? @session)))
                                (stage-free-preview-items)
                                (proxy-preview-items))))
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
