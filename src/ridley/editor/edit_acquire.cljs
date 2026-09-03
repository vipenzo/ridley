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
            [ridley.editor.camera-capture :as camera]
            [ridley.editor.state :as state]
            [ridley.editor.ui :as ui]
            [ridley.geometry.primitives :as prims]
            [ridley.viewport.core :as viewport]
            [ridley.turtle.attachment :as attachment]
            [ridley.turtle.core :as turtle]
            [ridley.photogrammetry.camera :as pcamera]
            [ridley.photogrammetry.exif :as exif]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.cage :as cage]
            [ridley.photogrammetry.edge-snap :as edge-snap]
            [ridley.photogrammetry.pnp :as pnp]
            [ridley.photogrammetry.fuse :as fuse]
            [ridley.photogrammetry.bundle :as bundle]
            [ridley.photogrammetry.blob :as blob]
            [ridley.photogrammetry.blob-detect :as blob-detect]
            [ridley.photogrammetry.match-cage :as match-cage]
            [ridley.photogrammetry.match-plate :as match-plate]
            [ridley.photogrammetry.plate-focal :as plate-focal]
            [ridley.photogrammetry.plate-calib :as plate-calib]
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
;;  :focal-source :exif|:live|:refined|:manual|:remembered|:default — provenance of
;;                                     :focal-mm, for the panel's honest label AND for
;;                                     deciding whether a live grab may adopt its own
;;                                     single-frame measurement (see
;;                                     own-lens-sources). :live = measured off the
;;                                     plate by a grabbed frame; :refined = the joint
;;                                     fit over every view, which no single frame may
;;                                     overwrite; :remembered = this camera's lens
;;                                     from ~/.ridley/cameras.json, measured by a
;;                                     past session (camera-lens-key)
;;  :grab-camera "label @ w×h"         — which camera this session's grabs came
;;                                     from; persisted, so a later 'R' can file
;;                                     its measured lens under the right key
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

(declare trace-items mark-world-positions cage-proxy? current-camera-pose)

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
   read the photo is exactly when these are wanted.

   A CAGE is handled by cage-marks-item instead (front-face culling, the
   double pallini told apart, visibility tied to the proxy's own) — this
   fn's all-anchors dump would draw both faces of every ring through the
   plastic, which is how crowns get miscounted."
  []
  (when-let [as (seq (:anchors (:proxy-mesh @session)))]
    (when-not (cage-proxy?)
      {:type :dots
       :data (mapv (fn [[k a]]
                     (let [zero? (bridge/index-anchor? k)]
                       {:pos (:position a)
                        :radius (if zero? 1.3 1.0)
                        :color (if zero? zero-dot-color crown-dot-color)
                        :opacity 0.9}))
                   as)})))

(defn- cage-marks-item*
  "Every mark of the cage's FRONT faces, riding the virtual proxy in the
   alignment preview: the full crowns as azzurro dots, each face's DOUBLE
   PALLINO — mark 0 big, zero-index small — in orange. Takes the mesh as an
   argument so the Alt+drag peek can draw a ROTATED COPY; cage-marks-item is
   the session-reading wrapper.

   Two of Vincenzo's decisions, the second refining the first. The double
   pallini (2026-08-31: «vedere dove finiscono i doppi pallini») are the one
   printed figure that pins a crown's numbering AND its face, and the gauge of
   the passetto rule (big disc → small pallino: CCW in the image = face p).
   Then the whole crowns (2026-09-01): «dalla foto è difficile stabilire che
   numero è un certo pallino — se sono visibili su quello virtuale si possono
   contare guardando dietro eventuali ostacoli» — the virtual cage has no
   sticks, no part and no glare, so counting discs on IT works where counting
   on the photograph does not. This does not reopen the 2026-08-27 blanket
   problem: that was the PICKING mode, where predicted dots sat exactly on the
   discs being clicked; here they ride the proxy in the alignment view, and
   the picking mode keeps its own sparse dots.

   FRONT faces only (same per-mark test as bridge/pnp-target-points): a disc
   of a face looking away would show through the plastic, and counting through
   the plastic is exactly how crowns get misread. With no camera pose yet,
   nothing is culled. Shown and hidden WITH the proxy ('v'): the dots are part
   of the virtual cage, not an overlay on the photo — Vincenzo 2026-09-01,
   reversing the first cut, which kept the pallini always on."
  [proxy-mesh]
  (let [mesh proxy-mesh]
    (when (:cage-d mesh)
      (let [cam-pos (:position (current-camera-pose))
            front? (fn [{:keys [position heading]}]
                     (or (nil? cam-pos)
                         (pos? (m/dot (m/normalize heading) (m/v- cam-pos position)))))
            dots (for [[id a] (sort-by key (:anchors mesh))
                       :when (front? a)]
                   (cond
                     (bridge/index-anchor? id)
                     {:pos (:position a) :radius 1.1 :color zero-dot-color :opacity 0.95}

                     (= 0 (:index (cage/mark-parts id)))
                     {:pos (:position a) :radius 1.8 :color zero-dot-color :opacity 0.95}

                     :else
                     {:pos (:position a) :radius 1.0 :color crown-dot-color :opacity 0.9}))]
        (when (seq dots)
          {:type :dots :data (vec dots)})))))

(defn- cage-marks-item [] (cage-marks-item* (:proxy-mesh @session)))

(def ^:private cage-tab-color 0x7fd17f)     ; verde: alette d'incollaggio e fermi
(def ^:private cage-key-pin-color 0xff5533) ; rosso: la spina della chiave di montaggio
(def ^:private cage-slot-color 0x8899aa)    ; grigio-azzurro: i box porta-stick
(def ^:private cage-channel-color 0xe8f0ff) ; quasi bianco: la bocca del canale

(defn- box-corners
  "The 8 corners of a box: `center`, three orthonormal `axes`, and the
   half-extent along each. Corners are indexed by three bits, one per axis, so
   the faces and edges below are index arithmetic. Oriented rather than
   axis-aligned because the stick-slot bodies stand at their own azimuth on
   their ring, unlike the tabs."
  [center axes halves]
  (mapv (fn [i]
          (reduce (fn [p [b e h]] (m/v+ p (m/v* e (if (bit-test i b) h (- h)))))
                  center
                  (map vector [0 1 2] axes halves)))
        (range 8)))

(defn- aabb-corners
  "box-corners for a {:center :size} box in cage coordinates (the tabs)."
  [{:keys [center size]}]
  (box-corners center
               [[1.0 0.0 0.0] [0.0 1.0 0.0] [0.0 0.0 1.0]]
               (mapv #(/ % 2.0) size)))

(defn- box-faces
  "Triangles over box-corners' indexing, offset into a merged vertex vector.
   Winding is not curated: the material these feed is double-sided."
  [offset]
  (into [] (mapcat (fn [[a b c d]] [[(+ offset a) (+ offset b) (+ offset c)]
                                    [(+ offset a) (+ offset c) (+ offset d)]]))
        [[0 1 3 2] [4 6 7 5] [0 4 5 1] [2 3 7 6] [0 2 6 4] [1 5 7 3]]))

(defn- box-edges
  "The 12 edges of a corner vector as [from to] pairs — an edge wherever two
   corner indices differ in exactly one bit."
  [corners]
  (for [i (range 8)
        b (range 3)
        :let [j (bit-or i (bit-shift-left 1 b))]
        :when (> j i)]
    [(nth corners i) (nth corners j)]))

(defn- ellipse-loop
  "Closed polyline of an ellipse centred at `c`, semi-axis `r1` along `e1` and
   `r2` along `e2`, as [from to] pairs."
  [c e1 r1 e2 r2]
  (let [n 20
        pt (fn [i] (let [a (* 2.0 Math/PI (/ (double i) n))]
                     (m/v+ c (m/v+ (m/v* e1 (* r1 (Math/cos a)))
                                   (m/v* e2 (* r2 (Math/sin a)))))))
        pts (mapv pt (range n))]
    (map vector pts (conj (subvec pts 1) (first pts)))))

(defn- cage-feature-items*
  "The cage's PRINTED features drawn over the photo, riding the given mesh's
   pose (an argument, so the Alt+drag peek can draw a rotated COPY;
   cage-feature-items is the session-reading wrapper):
   the glue tabs with their stop lips (alette, green), the assembly key's pin
   (spina, red), and the stick-slot bodies with the elliptical mouth of their
   channel at each end (i box forati, grey-blue).

   The bare rings are not enough to align by eye, and Vincenzo said why
   (2026-08-31): on the print, these features are what tells him the position
   even when the zero-indices are hidden. And they carry more than recognition —
   the tabs rise off ONE face of each ring and the key pin is asymmetric on
   purpose, so they are exactly what distinguishes a pose from its mirror twin:
   a naked annulus matches its own reflection, an annulus with its tab does not.
   Without them the align-by-eye gesture would inherit the very twin ambiguity
   it exists to kill.

   Tabs and pin are SOLID translucent boxes, not just edges — the first cut was
   edge-only and Vincenzo couldn't use them («si vedono, ma in wireframe...
   dovrebbero essere un po' più evidenti», 2026-08-31). Translucent rather than
   opaque on purpose: the drawn aletta gets aligned TO the photographed one, so
   the photo must stay readable through it. The edge lines stay on top of the
   fill for a crisp outline.

   The slot bodies joined the tabs on 2026-09-01, at Vincenzo's request: «anche
   loro sono elementi chirali riconoscibili che facilitano il confronto tra
   l'immagine virtuale della gabbia e la foto» — and he is right twice over,
   since a slot body rises from ONE face of its ring, so like the tabs it is a
   feature the mirror twin cannot reproduce. The first cut drew them as flat
   lozenges, which read as decoration rather than as the blocks they are.

   The key NOTCH is still not drawn: it is a cut, and a box there would show
   material the print does not have. The STICKS are not drawn either — the
   model knows the slots (printed, fixed) but not which sticks were threaded
   through them this session.

   Geometry comes from the same :tabs/:stick-slots the print rides on
   (cage/joint-tabs, cage/stick-slots — cage coordinates), lifted to world at
   :creation-pose, so the drawn cage and the printed cage cannot drift apart.
   Returns a vector of preview items; nil on anything that is not a cage."
  [proxy-mesh]
  (let [mesh proxy-mesh]
    (when (and (:cage-d mesh) (seq (:tabs mesh)))
      (let [pose (:creation-pose mesh)
            w #(bridge/local->world pose %)
            ;; several boxes merged into ONE translucent mesh per colour: one
            ;; preview item instead of a dozen, and the vertex offsets are why
            ;; box-faces takes one
            solid (fn [corner-sets color opacity]
                    (when (seq corner-sets)
                      (let [{:keys [verts faces]}
                            (reduce (fn [{:keys [verts faces]} cs]
                                      {:verts (into verts cs)
                                       :faces (into faces (box-faces (count verts)))})
                                    {:verts [] :faces []}
                                    corner-sets)]
                        {:type :mesh
                         :data {:vertices (mapv w verts)
                                :faces faces
                                :material {:color color :opacity opacity
                                           :double-sided true
                                           :metalness 0.0 :roughness 0.9}}})))
            segs (fn [pairs color]
                   (map (fn [[a b]] {:from (w a) :to (w b) :color color}) pairs))
            by-kind (group-by :kind (:tabs mesh))
            corners-of (fn [ts] (mapv aabb-corners ts))
            tab-corners (corners-of (into (vec (:lap by-kind)) (:stop by-kind)))
            pin-corners (corners-of (:key-pin by-kind))
            ;; a slot body stands at its own azimuth on its ring, so it is an
            ;; ORIENTED box: across the channel, along the ring's axis (the
            ;; face it rises from — the chiral half of the cue), along the
            ;; channel itself
            slot-boxes (mapv (fn [{:keys [position heading up body-w body-h body-len body-lift]}]
                               (let [side (m/normalize (m/cross heading up))]
                                 (box-corners (m/v+ position (m/v* up body-lift))
                                              [side up heading]
                                              [(/ body-w 2.0) (/ body-h 2.0) (/ body-len 2.0)])))
                             (:stick-slots mesh))
            ;; the HOLE, drawn as its elliptical mouth at both ends of the body:
            ;; a slot the stick cannot be seen to pass through is just a block,
            ;; and the print is unmistakable about being pierced
            channel-segs (mapcat (fn [{:keys [position heading up channel-lift channel-r body-len]}]
                                   (let [side (m/normalize (m/cross heading up))
                                         [r-across r-up] channel-r
                                         c (m/v+ position (m/v* up channel-lift))
                                         half (/ body-len 2.0)]
                                     (mapcat (fn [s]
                                               (ellipse-loop (m/v+ c (m/v* heading (* s half)))
                                                             side r-across up r-up))
                                             [1.0 -1.0])))
                                 (:stick-slots mesh))]
        (into []
              (remove nil?)
              [(solid tab-corners cage-tab-color 0.55)
               (solid pin-corners cage-key-pin-color 0.85)
               (solid slot-boxes cage-slot-color 0.5)
               {:type :lines
                :data (vec (concat (segs (mapcat box-edges tab-corners) cage-tab-color)
                                   (segs (mapcat box-edges pin-corners) cage-key-pin-color)
                                   (segs (mapcat box-edges slot-boxes) cage-slot-color)
                                   (segs channel-segs cage-channel-color)))}])))))

(defn- cage-feature-items [] (cage-feature-items* (:proxy-mesh @session)))

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
        ;; the printed features hide together with the proxy ('v'): they are an
        ;; alignment aid, and 'v' means "let me read the photo naked"
        cage-features (when-not (:hide-proxy? @session) (cage-feature-items))
        ;; marks come and go WITH the proxy ('v'): they are part of the virtual
        ;; cage, not an overlay on the photo (Vincenzo 2026-09-01)
        cage-marks (when-not (:hide-proxy? @session) (cage-marks-item))
        base (cond-> (if (:hide-proxy? @session)
                       []
                       [{:type :mesh :data (:proxy-mesh @session)}])
               (seq cage-features) (into cage-features)
               cage-marks (conj cage-marks)
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
                   (js->clj (.-photos obj)))
     ;; the WHOLE document, so appending a grabbed frame rewrites the film without
     ;; dropping the keys this editor doesn't read (:bootstrap, :caliper — the
     ;; CLI's, and its own to keep)
     :doc (js->clj obj :keywordize-keys true)}))

(declare set-status-message! auto-log! corner-labels)

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
     (case focal-source
       :exif (str "Focale da EXIF: " mm "mm")
       ;; measured off the plate itself by a live grab — say WHERE it came from,
       ;; because "no EXIF" would now be a lie about a number that was measured
       :live (str "Focale misurata dal piatto: " mm "mm")
       :refined (str "Focale rifinita su tutte le viste: " mm "mm")
       :manual (str "Focale impostata a mano: " mm "mm")
       ;; from ~/.ridley/cameras.json — measured on this same camera by a past
       ;; session; honest about being memory, not a fresh measurement
       :remembered (str "Focale ricordata per questa camera: " mm "mm")
       (str "EXIF senza focale — uso " mm "mm (regola con lo slider)")))))

;; ============================================================
;; Transient panel messages (edit-mesh-split's own pattern: capture-println
;; only surfaces at the end of a full eval cycle, never during a live modal
;; session, so a result the user must see NOW needs its own panel line)
;; ============================================================

(declare update-panel!)

(defn- set-status-message!
  "Show `msg` on the panel's status line AND keep a copy in the REPL stream.

   The status line clears itself after four seconds, which is right for a line
   that must not go stale but wrong for anything worth reading twice — and the
   messages this channel produces are long, and arrive exactly when the user is
   looking at the photograph instead of at the panel (Vincenzo, 2026-08-19: 'non
   faccio in tempo a leggerli che spariscono'). The diagnoses are the whole point
   of them: which disc was renamed, which ring looks glued round, why a solve was
   refused. So the flash stays for immediacy and the REPL keeps the record.

   Consecutive duplicates are dropped, since a gesture repeated on the same
   obstacle (clicking off the declared plane, say) would otherwise fill the
   stream with one sentence."
  [msg]
  (when-let [t (:status-msg-timer @session)] (js/clearTimeout t))
  (when (and msg (not= msg (:last-logged-status @session)))
    (auto-log! msg)
    (swap! session assoc :last-logged-status msg))
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
(declare derive-faces-from-pose!)

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

(defn- cage-proxy?
  "True when the session's proxy is a registration CAGE. It is a plate-proxy?
   too — same per-photo PnP on named marks — but the automatic paths (Auto, the
   batch assignment) are built on the plate's ONE crown plus its zero-index,
   and a cage has six crowns and no `:zero`. Those paths already refuse it for
   want of a zero-index; this only lets them say WHY in terms of what the user
   is holding, instead of advising a plate they deliberately aren't using.
   The live Grab branches on it too — not to refuse, but to KEEP the frame
   unregistered (keep-live-frame-unregistered!): a cage frame can only be
   registered by hand, and only if it is in the film."
  []
  (boolean (:cage-d (:proxy-mesh @session))))

(defn- no-auto-on-cage-msg
  "The one sentence a PLATE-shaped automatic path owes a cage session — the batch
   assignment, built on the plate's single crown and its zero-index. (The live
   grab was the other caller until 2026-08-27: it now KEEPS a cage frame
   unregistered instead of bouncing it — keep-live-frame-unregistered! — so only
   register-live-frame's defensive branch still says this.)

   It used to say the cage had no automatic recognition at all. It has one since
   2026-08-24 ('a', see cage-read-and-place!), and it is seeded rather than
   zero-click for a reason worth passing on rather than hiding: the detector finds
   the discs, but a crown of twelve equal marks cannot say which of them is mark
   zero — no photograph can — so four clicks on one ring are what the automatic
   path is standing on. Sending the user to 'a' costs them four clicks; leaving
   this sentence stale would cost them the feature."
  []
  (str "Questa via automatica è fatta per la corona del piatto. Su una gabbia: "
       "clicca 4 dischetti su UN anello con 'p' (lo zero-indice, se lo vedi, vale "
       "doppio), poi premi 'a' — a dire come va letta la corona è il resto della "
       "gabbia, non l'anello."))

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
                remap-plane (fn [{:keys [axis sign offset base]}]
                              ;; a FREELY placed plane may carry no preset at all;
                              ;; remapping a face that isn't there is what broke the
                              ;; session open on 2026-08-21
                              (let [nrm (when (and (number? axis) (number? sign))
                                          (remap (assoc [0.0 0.0 0.0] axis (double sign))))
                                    a (when nrm (apply max-key #(Math/abs ^double (nth nrm %)) [0 1 2]))]
                                (cond-> {:offset offset}
                                  a (assoc :axis a :sign (if (>= (nth nrm a) 0) 1 -1))
                                  ;; a FREE plane is a pose, so it rides the same
                                  ;; remap the marks and normals do — position as a
                                  ;; point, heading/up as directions
                                  base (assoc :base
                                              {:position (m/v+ build-pos
                                                               (Mv (m/v- (:position base) center)))
                                               :heading (remap (:heading base))
                                               :up (remap (:up base))}))))]
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
  ;; The proxy just moved under photo 0's frozen camera: the relative pose
  ;; changed, so this photo's faces are re-read from it. The other photos'
  ;; cameras were transported RIGIDLY above, so their readings still hold.
  (derive-faces-from-pose! (:current-idx @session))
  ;; …and the aligned pose becomes this photo's EYE SEED: a commit is the
  ;; human act «prendo in mano la gabbia e la appaio», which is exactly what
  ;; 'a' may gate its hypotheses by. Only committed poses earn the stamp — a
  ;; bare turntable seed gating the automatic would kill true readings.
  (swap! session update :eye-posed (fnil conj #{}) (:current-idx @session))
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
    ;; This photo's camera just moved: re-read its faces from the new pose.
    (derive-faces-from-pose! idx)
    ;; a commit is the human act — the pose becomes this photo's eye seed
    ;; for 'a' (see on-photo0-commit's twin stamp)
    (swap! session update :eye-posed (fnil conj #{}) idx)
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

(declare install-retrace-gizmo! reset-view-zoom! refresh-retrace-gizmo! redraw-mark-names!)

(defn- enter-photo!
  "Close/reopen the gizmo for photo `idx` — simpler to reason about than
   special-casing the 0↔1+ boundary, since :nudge-mesh? can only be set at
   gizmo/enter! time, there's no mutator for it."
  [idx]
  (stop-pnp!) ; leaving a photo cancels any half-collected PnP session on it
  (stop-marker!) ; and any open marker-click mode (its listener is photo-specific)
  (gizmo/close!)
  (swap! session assoc :current-idx idx)
  (reset-view-zoom!)
  (let [{:keys [file]} (nth (:photos @session) idx)]
    (viewport/set-camera-pose! (ensure-photo-pose idx))
    (set-photo-for-current-focal! file)
    ;; In :retrace the filmstrip is the live-reprojection control: keep the mode,
    ;; just move the camera onto this photo and re-show the (unchanged) world-space
    ;; polyline from the new angle — never tear down the retrace to install a gizmo.
    (case (:mode @session)
      ;; the plane gizmo is built AT a pose and has no mutator, so navigating —
      ;; which closes every gizmo above — has to put it back, or it survives only
      ;; on the photo the retrace was started from (Vincenzo, 2026-08-21: 'sulla
      ;; prima foto il gizmo si vede, sulle altre no')
      :retrace (do (redraw-retrace!) (install-retrace-gizmo!))
      ;; :mark is a live-reprojection mode too — the named marks are world/object
      ;; space, so navigating just re-shows them (and their labels) from this
      ;; photo's camera; never tear it down to install a gizmo.
      :mark (redraw-marks!)
      (install-gizmo! idx))
    ;; the names follow the photo in every mode — checking a cage against a view
    ;; means stepping through the views with them on
    (redraw-mark-names!))
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

(def ^:private index-target-color
  "One colour for all six zero-indices, and deliberately not from the rotating
   palette: the index is not one target among many. It is the only disc on a
   cage that says WHICH mark is which — a crown of twelve equal marks fits
   equally well under all 48 readings of itself (measured on a real photograph:
   every one of them at 32.5px), and a single click on the index cuts that to
   two, the remaining pair differing only by the 3mm of plastic between the two
   faces, which the physical guard settles. Worth its own colour."
  0xffffff)

(defn- pnp-targets
  "The indexed PnP targets for the current photo: bridge/pnp-target-points (the
   8 box corners, OR a proxy plate's named marks when it carries :anchors)
   enriched with the UI's :color and :label. Pick keys are the vector INDEX
   0..N-1 whatever the source, so the whole picking gesture below is
   source-agnostic. Each: {:obj :world :visible? :id :color :label}."
  []
  (vec (map-indexed
        (fn [i t]
          (assoc t :color (if (:index? t) index-target-color (target-color i))
                 ;; the index reads as the thing it is on the print — the double
                 ;; dot of one face — not as an id with a prefix
                 :label (cond (:index? t) (str "⊙" (subs (name (:id t)) 5))
                              (keyword? (:id t)) (name (:id t))
                              :else (str (inc i)))))
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
  "Indices of the targets offered for picking: those facing the camera at the
   current pose, so the user is never asked to point at a box vertex hidden
   behind the part or a mark turned away (Vincenzo, 2026-07-23).

   UNLESS `:show-all-marks?` is set, and that escape exists because the default
   is CIRCULAR. Which face is 'visible' is decided by the proxy's pose — and the
   pose is exactly what the picking is trying to establish. Get it wrong and the
   editor offers `ym00…` while the user is plainly looking at the `yp` face, with
   no way to say so: the marks that would correct the pose are the ones the wrong
   pose has hidden (Vincenzo, 2026-08-23: 'non ho modo di mettere i punti yp0').

   So the cull is a default, not a prison. With it off, every mark is offered and
   the user's eyes arbitrate — which is the right authority, since a disc they can
   see is a fact and the pose is still a guess.

   `:cage-face-choice` {axis → 1|-1} is the SHARP form of that escape, proposed by
   Vincenzo (2026-08-30) after the blunt one had cost three evenings: with
   show-all-marks the panel offers BOTH faces of every ring and picking the wrong
   one is as easy as before — «avevo messo p perché mi presentava solo quelli».
   Declaring the face per RING is the decision he actually makes when he holds the
   cage up and matches it to the photo, and it is exactly the fact the pose lacks:
   on a chosen ring only that face's marks are offered, whatever the pose believes;
   rings left undeclared keep the pose's own culling."
  []
  (let [ts (pnp-targets)
        ;; per PHOTO: each view shows different faces, so a session-wide choice
        ;; would be wrong the moment you step to the next frame
        choice (get-in @session [:cage-face-choice (:current-idx @session)])
        chosen (fn [t] (when (seq choice)
                         (when-let [p (or (cage/mark-parts (:id t))
                                          (cage/index-parts (:id t)))]
                           (when-let [s (get choice (:axis p))]
                             (= s (:sign p))))))]
    (into #{} (keep-indexed
               (fn [i t]
                 (when (case (chosen t)
                         true true      ; declared face: always offered
                         false false    ; the other face of a declared ring: never
                         (or (:show-all-marks? @session) (:visible? t)))
                   i))
               ts))))

(defn- pnp-preview-items
  "The SOLID proxy plus a translucent coloured dot at each VISIBLE corner
   (occluded ones are never drawn — pointing at a hidden vertex is a blind
   guess): the armed one enlarged, placed ones in their colour, unplaced ones
   dimmed, and any corner the robust fit rejected drawn as a big opaque RED dot
   so the mislabel is obvious (shown even if the refined pose has since
   occluded it, so it can still be re-clicked).

   Solid since 2026-09-01, wireframe before that. The wireframe was there so the
   photo showed through and its features stayed clickable — a real constraint
   while it was the ONLY state. It stopped being one the same day, twice over:
   'v' now works in the picking (so the proxy comes off whenever it is in the
   way), and the loupe magnifies the PHOTOGRAPH's own pixels under the cursor,
   not the render, so a disc stays aimable even with the model drawn over it.
   What the wireframe cost, meanwhile, was the reading the whole cage exists
   for: Vincenzo saw solid tabs and slot bodies floating with no rings between
   them («i ring del proxy si vedono solo nell'alt-drag… devono essere pieni»),
   and a solid cage OCCLUDES, which is information — a mark behind a ring is
   behind it on the print too.

   The predicted-position dots FOLLOW the names toggle ('n'): where the model
   thinks the marks are is exactly where the real discs sit once the pose is
   close, so on a cage the dots blanket the photograph and cover the very
   discs being clicked (Vincenzo 2026-08-27). With names off only two kinds
   survive: the ARMED one (the tool in hand, one dot) and the red outliers
   (each an error demanding a re-click)."
  []
  (let [armed (:pnp-armed @session)
        placed (pnp-picks)
        outliers (pnp-outliers)
        occluded (pnp-occluded)
        visible (visible-corner-set)
        names? (:show-names? @session)
        ;; 'v' works here too since the tabs became solid (see toggle-proxy!):
        ;; cage, printed features and predicted dots go down together; the
        ;; PLACED clicks live in the DOM overlay and never hide — they are the
        ;; user's data, not the model's drawing
        hide? (:hide-proxy? @session)
        features (when-not hide? (cage-feature-items))
        ;; the cage's own marks — azzurro crowns and the ORANGE double pallini —
        ;; ride the proxy here exactly as in the alignment view. They were
        ;; missing from the picking, which is where they are needed most: the
        ;; picking is when you must know WHICH disc you are about to name, and
        ;; the pair is what says so (Vincenzo 2026-09-01). Distinct from the
        ;; pick-target dots below: those are the tool in hand (armed, placed,
        ;; rejected), these are the cage the model believes in.
        marks (when-not hide? (cage-marks-item))]
    (into
     (cond-> []
       (not hide?) (conj {:type :mesh :data (:proxy-mesh @session)})
       (seq features) (into features)
       marks (conj marks)
       ;; the predicted dots are the MODEL's drawing as much as the wireframe:
       ;; under 'v' they go too, or three crowns of dots keep painting the cage
       ;; over the naked photo (Vincenzo 2026-09-01: «nasconde solo le tacche
       ;; verdi, non tutta la gabbia»). The user's PLACED clicks live in the DOM
       ;; overlay (redraw-overlay-dots!) and stay — they are his data, not the
       ;; model's guess.
       (not hide?)
       (conj {:type :dots
              :data (vec (keep-indexed
                          (fn [i pos]
                            (cond
                              (contains? outliers i)
                              {:pos pos :radius 5.5 :color 0xff2020 :opacity 0.7}
                              (and (= i armed) (contains? visible i))
                              {:pos pos :radius 4.4 :opacity 0.3 :color (target-color i)}
                              (not names?) nil
                              ;; marked hidden-by-the-part: a faint grey dot, so it
                              ;; reads as "dismissed" and no longer solicits a click
                              (contains? occluded i)
                              {:pos pos :radius 1.6 :color 0x555555 :opacity 0.2}
                              (contains? visible i)
                              {:pos pos
                               :radius 2.4
                               :opacity 0.3
                               :color (if (contains? placed i)
                                        (target-color i) 0x808080)}))
                          (corner-world-positions)))}))
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

(defn- append-overlay-name!
  "The NAME of a mark, printed beside where the model says that mark shows in the
   photo."
  [ov rect cx cy colour text]
  (let [tag (.createElement js/document "div")
        st (.-style tag)]
    (set! (.-position st) "absolute")
    (set! (.-left st) (str (- cx (.-left rect) -10) "px"))
    (set! (.-top st) (str (- cy (.-top rect) 8) "px"))
    (set! (.-fontSize st) "11px")
    (set! (.-fontWeight st) "700")
    (set! (.-whiteSpace st) "nowrap")
    (set! (.-pointerEvents st) "none")
    (set! (.-color st) colour)
    (set! (.-textShadow st) "0 0 3px #000, 0 0 3px #000")
    (set! (.-textContent tag) text)
    (.appendChild ov tag)))

(defn- draw-mark-names!
  "Write every visible mark's NAME on the photograph, where the model currently
   says that mark is ('n' / the Nomi button).

   The editor draws a dot per mark but never says WHICH mark, and on a cage that
   is the whole difficulty: three rings' crowns cross in one frame, both faces of
   a ring carry identical discs, and the numbering reverses between them. So the
   user counts — and on 2026-08-20 counting put six clicks of one crown onto
   discs of three different rings, which no relabelling could undo and which cost
   two sessions to diagnose. The names were derivable the whole time; they were
   simply never shown.

   It reads exactly what the solver reads, so it is honest about being wrong: with
   the proxy out of pose the names land nowhere near the discs, and that is itself
   the reading — align first, then trust them. Once one crown is registered (four
   clicks are enough) they land on the right discs everywhere, including rings
   with no picks at all."
  [ov rect]
  (when (and (:show-names? @session)
             ;; in the picking, 'v' hides the model's whole drawing — and the
             ;; names are predictions exactly like the dots they label
             ;; (2026-09-01). Other modes keep their own rules.
             (not (and (= :pnp (:mode @session)) (:hide-proxy? @session))))
    (doseq [t (pnp-targets)
            :when (:visible? t)
            :let [pos (viewport/world->screen (:world t))]
            :when pos]
      (append-overlay-name! ov rect (nth pos 0) (nth pos 1)
                            (hex->css (:color t)) (:label t)))))

(defn- redraw-mark-names!
  "Rebuild the names overlay on its own, for the modes that have no picks to draw.

   'n' started as a PnP aid — name the disc you are about to click. It is just as
   useful BEFORE placing anything: stepping through the photos with the names on
   is how you check that the cage the model believes in is the cage in the
   photograph, which is the sanity test that catches a badly registered view
   (Vincenzo, 2026-08-21). So it works everywhere, not only where it was born."
  []
  (when (and @session (not= :pnp (:mode @session)))
    (let [ov (ensure-pnp-overlay!)]
      (set! (.-innerHTML ov) "")
      (draw-mark-names! ov (canvas-rect)))))

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
      (append-overlay-dot! ov rect cx cy "rgba(255,255,255,0.35)" (str (inc i))))
    (draw-mark-names! ov rect)))

(defn- toggle-all-marks! []
  (swap! session update :show-all-marks? not)
  (redraw-pnp-preview!)
  (redraw-overlay-dots!)
  (update-panel!)
  (set-status-message!
   (if (:show-all-marks? @session)
     (str "ogni mark è ora selezionabile, anche quelli che il modello crede girati "
          "dall'altra parte — clicca quelli che VEDI: è la posa a essere in dubbio, "
          "non i tuoi occhi")
     "di nuovo solo i mark rivolti verso di te")))

(defn- reface-picks-to-declaration!
  "Move photo `idx`'s picks on ring `axis` onto the DECLARED face, and say what
   moved. Returns [n-moved n-dropped].

   Declaring a face used to change only what the panel OFFERS, which quietly left
   the picks already made on the other face sitting in the set. That is not a
   cosmetic inconsistency: the two faces of a ring are 3mm apart through the
   plastic and point OPPOSITE WAYS, so a set holding `yp03` and `zero-ym`
   describes a cage seen from both sides at once. No pose can satisfy it — the
   camera is behind one of them whichever way it faces — and no ring-level
   rescue can cure it either, because flipping the ring carries the contradiction
   along with it. Every solve is refused, and the refusal blames a click.

   Measured on battiscopa3 grab-04 (2026-08-31): Vincenzo clicked ⊙ym, then
   declared Yp. Of the eight face combinations of his picks, ZERO were physically
   possible; with the stale ⊙ym renamed, one is. He had done nothing wrong — the
   declaration simply did not reach backwards.

   The pixel never moves: the same disc is on both faces, so only the NAME
   changes. A pick whose new name is already taken is dropped instead — two marks
   on one disc wreck the whole pose, not just that point."
  [idx axis sign]
  (let [targets (pnp-targets)
        ;; cage-crown-count is defined further down; same expression
        n (or (:cage-marks (:proxy-mesh @session)) 12)
        id->ci (into {} (map-indexed (fn [i t] [(:id t) i])) targets)
        face-of (fn [ci] (let [id (:id (nth targets ci))]
                           (:sign (or (cage/mark-parts id) (cage/index-parts id)))))
        ring-of (fn [ci] (cage/anchor-axis (:id (nth targets ci))))
        picks (get-in @session [:pnp-picks idx] {})
        wrong (filterv (fn [ci] (and (= axis (ring-of ci))
                                     (= (- sign) (face-of ci))))
                       (keys picks))
        flipped (into {} (keep (fn [ci]
                                 (when-let [j (id->ci (cage/relabel
                                                       (:id (nth targets ci))
                                                       {:flip-face? true :mirror? false :rot 0} n))]
                                   [ci j]))
                               wrong))
        taken (set (remove (set wrong) (keys picks)))
        moves (into {} (remove (fn [[_ j]] (contains? taken j)) flipped))
        drops (into #{} (remove (set (keys moves))) wrong)]
    (when (seq wrong)
      (swap! session update-in [:pnp-picks idx]
             (fn [m] (-> (apply dissoc m wrong)
                         (into (map (fn [[ci j]] [j (get m ci)])) moves))))
      ;; the stale flags describe names that no longer exist
      (swap! session update-in [:pnp-residuals idx] #(apply dissoc % wrong))
      (swap! session update-in [:pnp-outliers idx] #(when % (into #{} (remove (set wrong)) %)))
      (swap! session update-in [:pnp-occluded idx]
             #(when % (into #{} (keep (fn [ci] (if (contains? (set wrong) ci) (moves ci) ci))) %))))
    [(count moves) (count drops)]))

(defn- derive-faces-from-pose!
  "Read photo `idx`'s ring faces off the pose the user just made BY EYE with the
   gizmo, and make them this photo's face declaration — the decision of
   2026-08-31, after thirteen live rounds in which hand-declared faces were
   wrong on three photos in a row and every face error poisoned the solve and
   every diagnosis downstream. The physical gesture «prendo in mano la gabbia e
   la appaio alla foto» is now the virtual one: orient the cage until it
   matches, and the faces are READ from the pose, no longer declared.

   Runs on every gizmo commit (both handlers — photo 0 moves the proxy, later
   photos invert onto the camera; either way the RELATIVE pose just changed,
   and it is the relative pose the faces live on). The derived choice REPLACES
   whatever was in :cage-face-choice for this photo, manual overrides included:
   the last human act wins, and a commit IS a human act — the pose is his. The
   three buttons stay, as display of the derived value and as override for «mi
   fido dei tuoi occhi, non della posa»; a ring below the profile guard
   (bridge/cage-face-margin-deg) declares NOTHING — the pose's own per-mark
   culling stays in charge and the message says why, with the degrees, so the
   verdict is never stronger than its evidence.

   Each derived face is carried BACKWARDS over the picks already made
   (reface-picks-to-declaration!) — same medicine as the manual toggle, same
   grab-04 disease behind it."
  [idx]
  (when (cage-proxy?)
    (when-let [cam (get-in @session [:camera-poses idx])]
      (let [faces (bridge/cage-faces-from-pose (:proxy-mesh @session) cam)
            derived (into {} (keep (fn [[a {:keys [sign]}]] (when sign [a sign])) faces))
            profile (sort-by (comp str first)
                             (keep (fn [[a {:keys [sign geo-sign elev-deg]}]]
                                     (when-not sign [a elev-deg geo-sign]))
                                   faces))
            [moved dropped]
            (reduce (fn [[mv dv] [a s]]
                      (let [[m d] (reface-picks-to-declaration! idx a s)]
                        [(+ mv m) (+ dv d)]))
                    [0 0]
                    (sort-by (comp str key) derived))]
        (swap! session assoc-in [:cage-face-choice idx] derived)
        ;; an armed mark on a face just hidden would keep the old name in hand —
        ;; only meaningful while the picking UI is live
        (when (= :pnp (:mode @session))
          (when-let [a (:pnp-armed @session)]
            (when-not (contains? (visible-corner-set) a)
              (swap! session assoc :pnp-armed nil)))
          (redraw-pnp-preview!)
          (redraw-overlay-dots!))
        (update-panel!)
        (set-status-message!
         (str "facce lette dalla posa: "
              (if (seq derived)
                (str/join " " (for [[a s] (sort-by (comp str key) derived)]
                                (str (str/upper-case (name a)) (if (pos? s) "p" "m"))))
                "nessuna")
              (when (seq profile)
                (str " · " (str/join " · "
                                     (for [[a e s] profile
                                           :let [nm (str/upper-case (name a))]]
                                       (str nm " quasi di taglio (" (.toFixed e 0)
                                            "°): non la dichiaro io — direbbe "
                                            nm (if (pos? s) "p" "m")
                                            ", premilo tu se lo confermi")))))
              (when (pos? moved)
                (str " · " moved " click che avevi sull'altra faccia "
                     (if (> moved 1) "sono passati" "è passato")
                     " su questa: stesso dischetto attraverso la plastica, solo il nome cambia"))
              (when (pos? dropped)
                (str " · " dropped " " (if (> dropped 1) "click erano" "click era")
                     " sull'altra faccia e il nome nuovo era già occupato: "
                     (if (> dropped 1) "tolti" "tolto")))))))))

(defn- toggle-cage-face!
  "Declare (or un-declare) which FACE of ring `axis` this photo shows — the
   judgement Vincenzo makes by holding the cage up to the picture, which the
   pose cannot make for him (2026-08-30). Declared: only that face's marks are
   offered on that ring, whatever the pose believes. Pressed again: back to the
   pose's own culling."
  [axis sign]
  (let [idx (:current-idx @session)
        on? (not= sign (get-in @session [:cage-face-choice idx axis]))]
    (swap! session update-in [:cage-face-choice idx]
           (fn [c] (let [c (or c {})]
                     (if (= sign (get c axis)) (dissoc c axis) (assoc c axis sign)))))
    ;; The declaration reaches BACKWARDS over the picks already made, not only
    ;; forwards over what the panel offers. See reface-picks-to-declaration!.
    (let [[moved dropped] (if on? (reface-picks-to-declaration! idx axis sign) [0 0])]
      ;; an armed mark on the face just hidden would keep the old name in hand
      (when-let [a (:pnp-armed @session)]
        (when-not (contains? (visible-corner-set) a)
          (swap! session assoc :pnp-armed nil)))
      (redraw-pnp-preview!)
      (redraw-overlay-dots!)
      (update-panel!)
      (when (or (pos? moved) (pos? dropped)) (save-acquire-state!))
      (let [c (get-in @session [:cage-face-choice idx])]
        (set-status-message!
         (str
          (if (seq c)
            (str "facce dichiarate da te: "
                 (str/join " " (for [[a s] (sort-by (comp str key) c)]
                                 (str (str/upper-case (name a)) (if (pos? s) "p" "m"))))
                 " — su quegli anelli ti offro solo quella faccia, qualunque cosa creda la posa")
            "facce di nuovo decise dalla posa (nessun anello dichiarato)")
          (when (pos? moved)
            (str " · " moved " click che avevi già messo sull'altra faccia di "
                 (str/upper-case (name axis)) " " (if (> moved 1) "sono passati" "è passato")
                 " su questa: stesso dischetto attraverso la plastica, solo il nome cambia"))
          (when (pos? dropped)
            (str " · " dropped " " (if (> dropped 1) "click erano" "click era")
                 " sull'altra faccia e il nome nuovo era già occupato: "
                 (if (> dropped 1) "tolti" "tolto")
                 " (due mark sullo stesso dischetto mandano a gambe all'aria la posa)"))))))))

(defn- toggle-mark-names! []
  (swap! session update :show-names? not)
  ;; the predicted dots follow this toggle and live in the 3D preview, so the
  ;; preview of whichever mode draws them must rebuild along with the overlay:
  ;; :pnp (its own dots) and gizmo/marker (the crown item). :retrace/:mark/stage
  ;; previews carry no predicted dots — repainting them here would clobber the
  ;; plane quad / frustum scenes they own, so they keep the names-only redraw.
  (cond
    (= :pnp (:mode @session))
    (do (redraw-pnp-preview!) (redraw-overlay-dots!))

    (and (not (:stage? @session)) (contains? #{:gizmo :marker} (:mode @session)))
    (do (viewport/show-preview! (proxy-preview-items)) (redraw-mark-names!))

    :else (redraw-mark-names!))
  (update-panel!)
  (set-status-message!
   (if (:show-names? @session)
     (str "nomi dei mark SULLA FOTO. Se cadono lontano dai dischetti, è il proxy a "
          "essere fuori posa: registra prima una corona sola (bastano 4 click), poi "
          "torneranno al loro posto su tutti gli anelli")
     "nomi dei mark nascosti")))

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

(def ^:private duplicate-pick-px
  "Two picks closer than this are on the SAME disc, whatever their labels say.
   Clicks snap to a blob centroid, so two claims on one disc come back within a
   pixel or two of each other; distinct marks are hundreds of pixels apart even
   on a ring seen well off-square. Six leaves room for the small difference
   between a hand click's snap window and an auto-proposal's."
  6.0)

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
  "The pixel a click means: snapped to the disc under it, unless ALT is held —
   and on a cage, ALT only reaches here while the proxy is HIDDEN, since with
   it on screen ALT rolls the cage instead (pnp-on-pointerdown).

   The snap is right almost always and wrong in a way the user cannot argue
   with: when the mark touches something of a similar grey — the dark object
   sitting on the plate — the blob it finds spans both, and the centroid lands on
   a rounded tip of the object instead of the disc, however carefully you
   clicked (Vincenzo 2026-08-01, foto 10 / mark 11). No amount of aim fixes that,
   so there has to be a way to say 'take my click literally'.

   `label` names the mark being placed, and it is not decoration. The snap is
   reported at the moment of clicking, while the eye is on the photograph and
   several marks have just been placed in a row; without a name the report is
   unanswerable — 'lo snap ha spostato il click di 76px, di quale click parla?'
   (Vincenzo, 2026-08-20).

   A snap that travels FURTHER THAN THE WINDOW it started in is not refinement,
   it is a different feature: `blob/snap-to-blob` is a mean-shift, so it walks,
   and once it has walked past its own radius the disc under the cursor is no
   longer what it settled on. There the click is taken literally without being
   asked — a literal click is wrong by a few pixels, a snap onto the neighbouring
   disc is wrong by a whole mark, and one bad correspondence does not degrade a
   pose, it destroys it."
  [raw ^js e label]
  (let [named (if label (str " (" label ")") "")]
    (if (.-altKey e)
      (do (set-status-message! (str "click" named " preso alla lettera (Alt): nessuno snap"))
          raw)
      (let [px (snap-plate-click raw)
            d (Math/hypot (- (nth px 0) (nth raw 0)) (- (nth px 1) (nth raw 1)))]
        (cond
          (> d plate-click-snap-radius)
          (do (set-status-message!
               (str "il click" named " l'ho preso ALLA LETTERA: l'aggancio automatico se ne "
                    "andava di " (modal/fmt-number d) "px, cioè fuori dalla sua stessa "
                    "finestra — a quella distanza non stava più rifinendo il tuo dischetto "
                    "ma agganciandone un altro"))
              raw)

          (> d suspicious-snap-px)
          (do (set-status-message!
               (str "l'aggancio automatico ha spostato il click" named " di "
                    (modal/fmt-number d) "px — se ha preso la cosa sbagliata (un bordo scuro "
                    "lì vicino), riclicca tenendo ALT per prenderlo alla lettera"
                    ;; with the cage on screen ALT rolls it instead (see
                    ;; pnp-on-pointerdown), so the advice needs its first step
                    (when-not (:hide-proxy? @session)
                      ": prima premi 'v' per togliere la gabbia, sennò ALT la fa rotolare")))
              px)

          :else px)))))

(defn- screen-for [px client-fallback]
  (or (backdrop/screen-of-pixel (viewport/get-canvas) (viewport/get-camera) px) client-fallback))

(declare pnp-start-peek!)

(defn- pnp-on-pointerdown [^js e]
  (when (and @session (= :pnp (:mode @session)) (zero? (.-button e)))
    (if (and (.-altKey e) (not (:hide-proxy? @session)))
      ;; Alt+drag = sbirciatina, but ONLY while the cage is on screen. Alt was
      ;; taken: it has meant "this click, literally, no snap" since the plate
      ;; (click-pixel), and the peek stole it — reported the same evening it
      ;; shipped. Vincenzo's rule, adopted verbatim: cage visible → Alt rolls
      ;; the cage; cage hidden ('v') → Alt is the literal click again, and the
      ;; peek must NOT bring the cage back by itself. It divides cleanly
      ;; because each gesture is useless in the other's state — there is
      ;; nothing to roll when the cage is hidden, and a literal click is what
      ;; you want on the naked photo.
      (do (.preventDefault e)
          (.stopPropagation e)
          (pnp-start-peek! e))
      (when-let [raw (backdrop/pixel-under-pointer e (viewport/get-camera) (viewport/get-canvas))]
        (let [idx (:current-idx @session)]
          (cond
            ;; fetta B: identity-free batch — every click is just another disc
            ;; centroid appended to the batch (no armed target); 'r' assigns them.
            (batch-mode?)
            (do
              (.preventDefault e) (.stopPropagation e)
              (let [px (click-pixel raw e nil)]
                (swap! session update-in [:pnp-batch idx] (fnil conj [])
                       {:px px :screen (screen-for px [(.-clientX e) (.-clientY e)])})
                (redraw-overlay-dots!)
                (update-panel!)))

            ;; fetta A / box: place the armed target; ignore clicks when unarmed
            (:pnp-armed @session)
            (let [ci (:pnp-armed @session)
                  px (click-pixel raw e (:label (nth (pnp-targets) ci nil)))
                  ;; A disc belongs to ONE mark. If this click lands on a disc some
                  ;; other mark already holds, the two cannot both be right, and
                  ;; keeping both hands the solver a contradiction that wrecks the
                  ;; pose rather than showing up as one bad point (2026-08-19: three
                  ;; marks on one disc, rms 453px; later a fourth pair, 795px).
                  ;; The click just said what this disc IS, so the newer claim wins
                  ;; and the older one is released — never silently, since the
                  ;; released mark now needs placing again.
                  same-disc (vec (keep (fn [[other v]]
                                         (let [q (:px v)]
                                           (when (and (not= other ci)
                                                      (< (Math/hypot (- (nth px 0) (nth q 0))
                                                                     (- (nth px 1) (nth q 1)))
                                                         duplicate-pick-px))
                                             other)))
                                       (pnp-picks)))]
              (.preventDefault e) (.stopPropagation e)
              (doseq [other same-disc]
                (swap! session update-in [:pnp-picks idx] dissoc other))
              (when (seq same-disc)
                (set-status-message!
                 (str "quel dischetto era già assegnato a " (corner-labels same-disc)
                      ": ora è " (corner-labels [ci]) ", e "
                      (if (> (count same-disc) 1) "quelli restano" "quello resta")
                      " da ripiazzare — due mark sullo stesso dischetto mandano"
                      " a gambe all'aria tutta la posa, non solo quel punto")))
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
                (do (swap! session assoc :pnp-armed nil) (redraw-pnp-preview!) (update-panel!))))))))))

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
        ;; remembered for the keyboard half of the eraser: Backspace deletes the
        ;; pick nearest where the cursor last was (see erase-pick-at!)
        (swap! session assoc :pnp-cursor-px [ix iy])
        (backdrop/draw-loupe! cv ix iy (loupe-zoom))
        ;; up-right of the cursor by default, clamped into the window so it
        ;; never runs off-screen near an edge
        (let [left (min (- (.-innerWidth js/window) loupe-size 4) (+ (.-clientX e) 24))
              top (max 4 (- (.-clientY e) loupe-size 12))]
          (set! (.-left st) (str left "px"))
          (set! (.-top st) (str top "px"))
          (set! (.-display st) "block")))
      (set! (.-display st) "none"))))

;; --- Alt+trascina: sbircia la gabbia virtuale, poi torna da sola ------------
;;
;; In the picking the photo often hides a mark — a stick, the part, glare — and
;; counting discs on the photograph is where crowns are misread. The virtual
;; cage knows where every mark is, but it sits locked in the registered pose.
;; Vincenzo's proposal (2026-09-01), his own variant: no gizmo handles — hold
;; Alt and drag anywhere to roll the virtual cage like a ball in hand, look at
;; where the marks are, release, and after a moment it springs back to the
;; registered pose. He chose Alt+drag over a pnp gizmo deliberately («molto
;; più chiaro»): no handles sitting over the discs being clicked.
;;
;; NOTHING is ever committed: the rolled cage is a preview built from a
;; rotated COPY of the proxy — the session pose is untouched by construction,
;; so the snap-back is a delayed redraw, not a restore.

(def ^:private peek-deg-per-px
  "Trackball gain: degrees of cage roll per pixel of drag. 0.4 turns a
   250px swipe into a quarter turn."
  0.4)

(def ^:private peek-return-ms
  "How long the peeked cage lingers after release before springing back —
   Vincenzo's «dopo un secondo o due»."
  1200)

(defn- pnp-peek-active? [] (some? (:pnp-peek-drag @session)))

(defn- pnp-peek-items
  "Preview of the peeked cage: the SOLID cage + printed features + every
   front-face mark. The marks show here even though the normal picking preview
   leaves them to the overlay — seeing where they are is the whole point of the
   gesture — and their culling follows the rotated copy, so faces rolling
   toward the camera reveal their crowns like the print would in hand.

   Solid, not the picking mode's see-through wireframe (Vincenzo 2026-09-01:
   «il proxy è disegnato in wireframe, non pieno»): the wireframe is see-through
   so that discs can be clicked underneath it, and here nothing is being clicked
   — it is the object being looked at. Solid also OCCLUDES, which is the reading
   itself: a mark hidden behind a ring of the rolled cage is hidden on the print
   too, from that side."
  []
  (let [{:keys [ax ay]} (:pnp-peek @session)
        {:keys [r u]} (pose-basis (current-camera-pose))
        mesh (-> (:proxy-mesh @session)
                 (attachment/rotate-mesh u (deg->rad (or ax 0.0)))
                 (attachment/rotate-mesh r (deg->rad (or ay 0.0))))]
    (into [{:type :mesh :data mesh}]
          (concat (cage-feature-items* mesh)
                  (some-> (cage-marks-item* mesh) vector)))))

(defn- pnp-start-peek! [^js e]
  (when-let [t (:pnp-peek-timer @session)] (js/clearTimeout t))
  ;; capture the pointer so the release is heard even off-canvas — without it
  ;; a drag ending outside the photo would leave the cage rolled forever
  (try (.setPointerCapture (viewport/get-canvas) (.-pointerId e)) (catch :default _))
  (swap! session assoc
         :pnp-peek-drag {:x (.-clientX e) :y (.-clientY e)}
         :pnp-peek (or (:pnp-peek @session) {:ax 0.0 :ay 0.0})
         :pnp-peek-timer nil)
  (hide-pnp-loupe!)
  (viewport/show-preview! (pnp-peek-items)))

(defn- pnp-move-peek! [^js e]
  (let [{:keys [x y]} (:pnp-peek-drag @session)]
    (swap! session
           (fn [s]
             (-> s
                 (update-in [:pnp-peek :ax] (fnil + 0.0)
                            (* peek-deg-per-px (- (.-clientX e) x)))
                 (update-in [:pnp-peek :ay] (fnil + 0.0)
                            (* peek-deg-per-px (- (.-clientY e) y)))
                 (assoc :pnp-peek-drag {:x (.-clientX e) :y (.-clientY e)}))))
    (viewport/show-preview! (pnp-peek-items))))

(defn- pnp-end-peek! []
  (when (pnp-peek-active?)
    (swap! session dissoc :pnp-peek-drag)
    ;; a new Alt+drag inside the window clears this timer and rolls on from
    ;; where the cage is; only a quiet second sends it home
    (swap! session assoc :pnp-peek-timer
           (js/setTimeout
            (fn []
              (when (and @session (= :pnp (:mode @session)) (not (pnp-peek-active?)))
                (swap! session dissoc :pnp-peek :pnp-peek-timer)
                (redraw-pnp-preview!)))
            peek-return-ms))))

(defn- pnp-on-pointerup [^js e]
  (when (and @session (= :pnp (:mode @session)) (pnp-peek-active?))
    (.preventDefault e)
    (.stopPropagation e)
    (pnp-end-peek!)))

(defn- pnp-on-pointermove [^js e]
  (when (and @session (= :pnp (:mode @session)))
    (if (pnp-peek-active?)
      (do (.preventDefault e)
          (.stopPropagation e)
          (pnp-move-peek! e))
      (update-loupe! e))))

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

;; --- the eraser: a poisoned pick must be REMOVABLE, one at a time -----------
;;
;; Until 2026-08-28 there was no gesture to delete a single pick: a photo with a
;; doubled disc (see propose-clear-px) or a click on the wrong feature could
;; only be repaired by Azzera — throwing away every good click with the bad one.
;; Right-click on the dot (or Backspace with the cursor near it) removes just
;; that one, hand click or proposal alike.

(def ^:private eraser-radius-px
  "How far (image px) from the cursor the eraser reaches for a pick. Generous —
   aiming a right-click at a 2.5mm disc should not require the loupe — but well
   under the 200-500px between neighbouring marks, so it cannot grab the wrong
   dot: whatever is nearest within this ring is what the user is pointing at."
  40.0)

(defn- erase-pick-at!
  "Delete the ONE pick nearest `px` (within eraser-radius-px): a hand click, a
   proposal, or in batch mode an identity-free click. Clears that pick's stale
   fit flags, says what was removed and how to put it back, persists."
  [px]
  (let [idx (:current-idx @session)
        d (fn [q] (Math/hypot (- (nth px 0) (nth q 0)) (- (nth px 1) (nth q 1))))]
    (if (batch-mode?)
      (let [batch (vec (pnp-batch))
            i (when (seq batch)
                (apply min-key #(d (:px (nth batch %))) (range (count batch))))]
        (if (and i (<= (d (:px (nth batch i))) eraser-radius-px))
          (do (swap! session assoc-in [:pnp-batch idx]
                     (vec (concat (subvec batch 0 i) (subvec batch (inc i)))))
              (set-status-message! "gomma: tolto un click del batch")
              (redraw-overlay-dots!)
              (update-panel!))
          (set-status-message!
           "gomma: nessun click qui sotto — avvicina il cursore al pallino da togliere")))
      (let [picks (pnp-picks)
            best (when (seq picks)
                   (apply min-key (fn [[_ v]] (d (:px v))) (vec picks)))]
        (if (and best (<= (d (:px (val best))) eraser-radius-px))
          (let [[ci v] best
                lbl (:label (nth (pnp-targets) ci nil))]
            (swap! session update-in [:pnp-picks idx] dissoc ci)
            (swap! session update-in [:pnp-outliers idx] (fnil disj #{}) ci)
            (swap! session update-in [:pnp-residuals idx] dissoc ci)
            (set-status-message!
             (str "gomma: tolto " lbl
                  (if (:proposed? v) " (era una proposta automatica)" " (era un tuo click)")
                  " — 'r' per registrare sui restanti; per rimetterlo clicca il suo "
                  "bottone nel pannello, poi il punto nella foto"))
            (redraw-pnp-preview!)
            (redraw-overlay-dots!)
            (update-panel!)
            (save-acquire-state!))
          (set-status-message!
           "gomma: nessun pick qui sotto — avvicina il cursore al pallino da togliere"))))))

(defn- pnp-on-contextmenu
  "Right-click in PnP mode IS the eraser — the camera is locked on the photo, so
   the right button has no other job here, and an eraser wants to be aimed."
  [^js e]
  (when (and @session (= :pnp (:mode @session)))
    (.preventDefault e)
    (.stopPropagation e)
    (when-let [px (backdrop/pixel-under-pointer e (viewport/get-camera) (viewport/get-canvas))]
      (erase-pick-at! px))))

(defn- start-pnp! []
  (when (and @session (not= :pnp (:mode @session)))
    (gizmo/close!)
    (swap! session assoc :mode :pnp)
    (swap! session dissoc :pnp-batch-mode?)   ; always open in the armed flow
    ;; On a CAGE with no face declaration yet for this photo, read one off the
    ;; photo's current pose on the way in, so the panel opens with the toggles
    ;; already lit and offers the faces actually in the picture — Vincenzo asked
    ;; whether the six buttons set themselves (2026-09-01), and before this they
    ;; only did after a gizmo COMMIT on this same photo, which a photo you never
    ;; drag (the turntable seed is close) never gets. Entry-only, never
    ;; overwrites an existing choice, and never touches picks: refacing stays
    ;; tied to the gizmo commits, where the pose is explicitly the user's own
    ;; (derive-faces-from-pose!). BEFORE arm-corner!, so the first armed target
    ;; already respects the derived offer.
    (let [idx (:current-idx @session)]
      (when (and (cage-proxy?)
                 (nil? (get-in @session [:cage-face-choice idx])))
        (when-let [cam (get-in @session [:camera-poses idx])]
          (let [faces (bridge/cage-faces-from-pose (:proxy-mesh @session) cam)
                derived (into {} (keep (fn [[a {:keys [sign]}]] (when sign [a sign]))) faces)]
            (when (seq derived)
              (swap! session assoc-in [:cage-face-choice idx] derived)
              (set-status-message!
               (str "facce lette dalla posa: "
                    (str/join " " (for [[a s] (sort-by (comp str key) derived)]
                                    (str (str/upper-case (name a)) (if (pos? s) "p" "m"))))
                    " — se i tuoi occhi dicono altro, correggile coi bottoni")))))))
    (arm-corner! (next-unplaced-corner 0))
    (let [^js canvas (viewport/get-canvas)]
      (.addEventListener canvas "pointerdown" pnp-on-pointerdown true)
      (.addEventListener canvas "pointermove" pnp-on-pointermove true)
      ;; pointerup/cancel end the Alt+drag peek; with the pointer captured at
      ;; peek start they are heard even when the drag ends off-canvas
      (.addEventListener canvas "pointerup" pnp-on-pointerup true)
      (.addEventListener canvas "pointercancel" pnp-on-pointerup true)
      (.addEventListener canvas "pointerleave" hide-pnp-loupe! true)
      (.addEventListener canvas "contextmenu" pnp-on-contextmenu true)
      (.addEventListener canvas "wheel" pnp-on-wheel #js {:capture true :passive false}))
    (redraw-overlay-dots!)
    (redraw-pnp-preview!)
    (update-panel!)))

(defn- stop-pnp! []
  (when (and @session (= :pnp (:mode @session)))
    (let [^js canvas (viewport/get-canvas)]
      (.removeEventListener canvas "pointerdown" pnp-on-pointerdown true)
      (.removeEventListener canvas "pointermove" pnp-on-pointermove true)
      (.removeEventListener canvas "pointerup" pnp-on-pointerup true)
      (.removeEventListener canvas "pointercancel" pnp-on-pointerup true)
      (.removeEventListener canvas "pointerleave" hide-pnp-loupe! true)
      (.removeEventListener canvas "contextmenu" pnp-on-contextmenu true)
      (.removeEventListener canvas "wheel" pnp-on-wheel true))
    ;; a peek must not outlive the mode: kill the timer and drop the angles, or
    ;; the delayed snap-back would fire into whatever preview came next
    (when-let [t (:pnp-peek-timer @session)] (js/clearTimeout t))
    (swap! session dissoc :pnp-peek :pnp-peek-drag :pnp-peek-timer)
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
   distortion, not a single click); or a clean fit.

   The rejected points may be named as CULPRITS only when dropping them actually
   bought a clean fit. `accept-rms-px` says why in its own docstring: a mislabel
   spreads its damage over every residual, so the tell is that the rms COLLAPSES
   under the bar when the guilty point goes. If it does not collapse, the
   greedy cleaner simply removed the worst two of a uniformly bad fit — and they
   are arbitrary, which is exactly how it looks from the outside. Measured on
   battiscopa3 grab-01 (2026-08-30): 15.5 → 14.6 → 13.8px, nine tenths of a
   pixel per rejection, four solves in a row naming four DIFFERENT pairs, every
   one of them a point Vincenzo had clicked dead on a detected disc. He said the
   accused points looked right to him. They were.

   And a rejected point that the user never placed cannot be re-clicked: an
   automatic proposal is a guess of the previous pose, so the move there is to
   drop it, not to aim better. Naming it in the same breath as a hand click sent
   him hunting for a mark he had never touched."
  [sol]
  (let [rms (:rms-px sol)
        out (mapv :ci (:outliers sol))
        picks (pnp-picks)
        prop? (fn [ci] (boolean (:proposed? (get picks ci))))
        clicked (filterv (complement prop?) out)
        guessed (filterv prop? out)
        clean? (<= rms pnp/accept-rms-px)]
    (cond
      ;; the drops bought a clean fit: they WERE the culprits, say so
      (and (seq out) clean?)
      (str "registrata sui restanti (" (.toFixed rms 1) "px)"
           ;; a proposal excluded from the FIT is still on screen as a pick —
           ;; say "escluse dal calcolo", not "tolte", and say who put them there
           (when (seq guessed)
             (str ", escluse dal calcolo " (if (> (count guessed) 1) "le proposte automatiche "
                                               "la proposta automatica ")
                  (corner-labels guessed)
                  " (" (if (> (count guessed) 1) "le ha messe" "l'ha messa")
                  " il programma, non c'è niente da ricliccare)"))
           (when (seq clicked)
             (str ", scartat" (if (> (count clicked) 1) "i i punti " "o il punto ")
                  (corner-labels clicked)
                  ": riclicca" (if (> (count clicked) 1) "li" "lo")
                  " più preciso, o 'o' per scartarl" (if (> (count clicked) 1) "i" "o")
                  " (nascosto o non allineabile), poi 'r' — o vai avanti così")))
      ;; the rms did NOT collapse: nothing here is a single culprit
      (> rms pnp/accept-rms-px)
      (str "rms alto (" (.toFixed rms 1) "px)"
           (when (seq out)
             (str " anche togliendo " (corner-labels out)))
           ": NON è un punto solo — l'errore è sparso su tutti, e togliere i"
           " peggiori non lo fa crollare. Non ricliccare a caso: controlla la"
           " FACCIA dichiarata degli anelli e la focale; se restano giuste,"
           " segnalamelo")
      :else
      (str "fit pulito, rms " (.toFixed rms 2) "px"))))

(defn- min-pnp-picks [] (if (plate-proxy?) min-plate-picks pnp/min-correspondences))

(defn- cage-crown-count
  "Marks per crown on the session's cage proxy."
  []
  (or (:cage-marks (:proxy-mesh @session)) 12))

(defn- best-misreading
  "For ONE ring's picks, the entry of `cage/crown-misreadings` that puts them
   closest to where `pose` says their discs are — among the readings that are
   PHYSICALLY POSSIBLE from that pose. Returns {:t :err :pairs :possible?} with
   `pairs` as [correspondence new-ci], or nil when no candidate maps cleanly.

   Possible-first, and it is the whole point. Reprojection error cannot see a
   change of face: the two faces are the same discs 3mm apart through the
   plastic, so swapping them moves a point by 3mm — about 19px at arm's length —
   and ALWAYS uphill from a reading that already sits on the pixels. Ranking by
   error alone therefore hands back the reading whose discs face away from the
   camera, every time, and the guard downstream can then only refuse the whole
   solve. Which is what a user saw: a ring offered on the wrong face, no way to
   put the right names in, and a refusal at the end (Vincenzo, 2026-08-23).
   So the guard chooses the candidate SET and the error picks within it. If no
   reading is possible the ranking degrades to error alone, exactly as before."
  [group pose k targets id->ci n]
  (let [id-of (fn [ci] (:id (nth targets ci)))
        obj-of (fn [ci] (:obj (nth targets ci)))]
    (->> (cage/crown-misreadings n)
         (keep (fn [t]
                 (let [pairs (mapv (fn [c]
                                     (when-let [nci (id->ci (cage/relabel (id-of (:ci c)) t n))]
                                       [c nci]))
                                   group)]
                   (when (every? some? pairs)
                     {:t t
                      :pairs pairs
                      :possible? (boolean (bridge/camera-sees-marks?
                                           targets (mapv second pairs) pose))
                      :err (/ (reduce + (map (fn [[c nci]]
                                               (if-let [q (pcamera/project k pose (obj-of nci))]
                                                 (Math/hypot (- (nth q 0) (nth (:px c) 0))
                                                             (- (nth q 1) (nth (:px c) 1)))
                                                 1e9))
                                             pairs))
                              (count pairs))}))))
         (reduce (fn [a b]
                   (cond (nil? a) b
                         ;; possible beats impossible, whatever the pixels say
                         (not= (:possible? a) (:possible? b)) (if (:possible? b) b a)
                         (< (:err b) (:err a)) b
                         :else a))
                 nil))))

(def ^:private gross-pick-px
  "A rejected pick above this many image px is not click noise — it is a pick
   that means something else entirely: a wrong name, a disc of another ring, a
   click nowhere near a disc. Five times the acceptance bar, so it can never
   fire on ordinary hand scatter.

   It exists to bound what a RESCUE may claim. A relabelling search reaches its
   rms by dropping points; dropping one at 508px means the pose it kept was
   dragged by that point before it went, and such a pose cannot arbitrate which
   FACE of a ring the camera saw — a question decided by a sign. Measured on
   battiscopa3 grab-05 (2026-08-31): the bench told Vincenzo the Z ring read on
   the m face, with a comfortable 43° margin, from exactly such a set; he read
   the chirality off the printed part and said p. His witness is the better one,
   and the message had claimed 'the ONLY physically possible reading'."
  (* 5.0 pnp/accept-rms-px))

(defn- rescue-face-phrase
  "The relabel search's answer as a THING TO PRESS. Given `flip` (the search's
   ci→ci permutation) and the faces the user has already declared, return the
   DELTA — the face buttons that must change — not the full reading.

   Stating all three faces made the user diff them against his own declaration
   in his head, and he did not (Vincenzo, grab-05, 31/8: the message said 'legge
   X sulla faccia m, Z sulla faccia m', he had declared Xm Ym Zp, the answer was
   the single button Zm, and what he reported was «ho provato a rifarla ma non
   va»). A correct answer nobody can act on is not an answer.

   Returns nil when the reading agrees with what is already declared — there the
   faces are not the story and saying anything about them would mislead."
  [flip targets cis declared]
  (let [face (fn [ci] (let [id (:id (nth targets ci))]
                        (or (cage/mark-parts id) (cage/index-parts id))))
        by-axis (into (sorted-map)
                      (keep (fn [ci] (when-let [p (face (flip ci))]
                                       [(:axis p) (:sign p)])))
                      cis)
        btn (fn [a s] (str (str/upper-case (name a)) (if (pos? s) "p" "m")))
        changed (filterv (fn [[a s]] (not= s (get declared a))) by-axis)]
    (cond
      (empty? by-axis) nil
      ;; every ring already declared the way the search reads it
      (empty? changed) nil
      ;; only some rings differ, and the rest are declared and agree
      :else
      ;; Name the button to press AND what is set now. "cambia il bottone Zm"
      ;; reads as "change the Zm button" — and when the panel shows Zp there is
      ;; no Zm button to change, so the instruction is nonsense at the very
      ;; moment it matters (Vincenzo, 31/8: «mi dice di cambiare il bottone Zm
      ;; (ma è già Zp)»). From → to, explicitly.
      (str "premi " (if (> (count changed) 1) "i bottoni " "il bottone ")
           (str/join " e " (map (fn [[a s]]
                                  (str (btn a s)
                                       (when-let [had (get declared a)]
                                         (str " al posto di " (btn a had)))))
                                changed))
           (when-let [ok (seq (remove (fn [[a _]] (contains? (set (map key changed)) a))
                                      by-axis))]
             (str " (" (str/join " " (map (fn [[a s]] (btn a s)) ok)) " "
                  (if (> (count ok) 1) "restano" "resta") " come "
                  (if (> (count ok) 1) "sono" "è") ")"))))))

(defn- cage-relabel-rescue
  "Recover a solve whose picks are RIGHT and whose labels are misread, one ring
   at a time.

   The cage makes this failure ordinary rather than careless. Both faces of a
   ring carry the same discs at the same angles, so no photograph tells you which
   face you are looking at, and from the far side the crown numbers run backwards.
   Worse, the marks the editor OFFERS are chosen from where the proxy currently
   sits, not from the photograph — so a proxy that is merely out of pose hands the
   user labels from the wrong faces, and both faces look identical, so nothing on
   screen can give it away. Found on the first real session (2026-08-19): twelve
   clicks all dead centre on real discs, and an rms of 1007px.

   Fixing every label together is not enough, and that is the whole design here.
   In that same photograph the largest ring was numbered CORRECTLY while the
   middle one was mirrored, because the camera sat on opposite sides of the two —
   the normal condition for a cage. So: trust the ring with the most picks, solve
   from it alone, then let every ring choose its own misreading against that pose,
   and refit. Same clicks, better names (1007px → 18px on that photograph).

   Returns {:sol :flip :changed} — `flip` a ci→ci permutation for
   `relabel-picks!` — or nil when nothing better was found.

   WHAT ADOPTS IT: the physical guard, and only the guard. The rms is a
   non-regression check, not evidence. It used to be the evidence — the new
   names had to beat HALF the old rms — and that quietly excluded the most
   ordinary misreading of all. A ring assembled the other way round maps its
   crown ONTO ITSELF: the discs are in the same places, only the identities and
   the printed faces permute. So the fit was already perfect and could not
   improve on itself. Measured on that exact case: first fit 0.000px, guard
   failed, threshold demanded '< 0.000px', and the correct relabelling —
   which the search HAD found — was thrown away, leaving the user with a
   refusal and no way forward (Vincenzo, 2026-08-23). Asking a failure for
   evidence it cannot produce is not conservatism."
  [correspondences targets k baseline-rms extra-poses]
  (let [n (cage-crown-count)
        id->ci (into {} (map-indexed (fn [i t] [(:id t) i])) targets)
        obj-of (fn [ci] (:obj (nth targets ci)))
        axis-of (fn [ci] (cage/anchor-axis (:id (nth targets ci))))
        groups (vals (group-by #(axis-of (:ci %)) correspondences))
        anchor (reduce (fn [a b] (if (> (count b) (count a)) b a)) (first groups) groups)]
    (when (and (seq anchor) (>= (count anchor) 4) (> (count groups) 1))
      (let [;; poses to try the rings against: the anchor ring on its own (both
            ;; faces — flipping which face its discs are on is what decides which
            ;; side of them the camera must have been, at a cost of 3mm of
            ;; geometry), plus whatever the caller already has in hand
            anchor-poses
            (keep identity
                  (for [t [{:flip-face? false :mirror? false :rot 0}
                           {:flip-face? true :mirror? false :rot 0}]]
                    (let [corr (keep (fn [c]
                                       (when-let [nci (id->ci (cage/relabel
                                                               (:id (nth targets (:ci c))) t n))]
                                         {:ci nci :world (obj-of nci) :px (:px c)}))
                                     anchor)]
                      (when (= (count corr) (count anchor))
                        (:pose (pnp/solve-pnp (vec corr) k {}))))))
            candidates
            (keep (fn [pose]
                    (let [picked (map #(best-misreading % pose k targets id->ci n) groups)]
                      (when (every? some? picked)
                        (let [by-axis (into {} (map (fn [g m] [(axis-of (:ci (first g))) (:t m)])
                                                    groups picked))
                              corr (vec (for [[c nci] (mapcat :pairs picked)]
                                          {:ci nci :world (obj-of nci) :px (:px c)}))
                              sol (pnp/solve-pnp corr k {})]
                          ;; the physical guard has to be asked about the NEW
                          ;; names: a disc's printed face is what decides which
                          ;; side of it the camera can have been, so judging a
                          ;; relabelled solve by the old labels' normals answers
                          ;; a question nobody asked
                          (when (and sol (bridge/camera-sees-marks?
                                          targets (mapv :ci corr) (:pose sol)))
                            {:sol sol
                             :by-axis by-axis
                             :changed (count (remove (fn [[c nci]] (= nci (:ci c)))
                                                     (mapcat :pairs picked)))})))))
                  (concat anchor-poses extra-poses))
            best (reduce (fn [a b] (if (or (nil? a) (< (:rms-px (:sol b)) (:rms-px (:sol a)))) b a))
                         nil candidates)]
        (when (and best (pos? (:changed best)))
          ;; `:adoptable?` is the old gate, unchanged: no worse than what the
          ;; user had, or good in absolute terms — the guard above already
          ;; established that this is the physically possible reading and the
          ;; old one was not. What changed (2026-08-31) is that a candidate
          ;; failing it is RETURNED rather than swallowed. Both callers ask
          ;; `rename-worthy?` before adopting, so nothing is adopted that was
          ;; not adopted before — but the refusal can now say what the only
          ;; possible reading was and how far off it sat, instead of "non
          ;; basta". Measured on battiscopa3 grab-04: the sole physically
          ;; possible reading of Vincenzo's thirteen clicks was 14.0px against
          ;; a bar of 12, and the whole search result was thrown away for those
          ;; two pixels while he was told to hunt for a bad click.
          {:sol (:sol best)
           :changed (:changed best)
           :adoptable? (<= (:rms-px (:sol best)) (max baseline-rms pnp/accept-rms-px))
           :flip (fn [ci]
                   (or (when-let [t (get (:by-axis best) (axis-of ci))]
                         (id->ci (cage/relabel (:id (nth targets ci)) t n)))
                       ci))})))))

(defn- cage-zero-phase-rescue!
  "A hand-clicked zero-index the solve wants to discard as an outlier gets the
   k-step re-reading of its ring first (match-cage/rescue-hand-zeros) — as a
   DIAGNOSIS, never as a substitute for the declaration. Vincenzo's call
   (2026-08-28), overturning this function's first draft, which adopted the
   re-solve and registered the photo as if the phase were declared: «non mi
   sembra una cosa furba supplire alla mancanza di :phases — ci accolliamo
   lavoro e incertezza in più per niente: la gabbia deve essere giusta».
   A session that half-believes two geometries (the fit on the turned zero,
   the predictions on the model) is exactly that uncertainty.

   So the solve that discarded the zero STANDS — the zero stays an honest
   outlier of the model in use — and what the probe measured goes in `:note`
   and the log: which ring, how many steps, and the rms the declaration would
   buy (the trial re-solve runs as EVIDENCE, so the suggestion is never a
   guess), with the exact `:phases` line to declare. `:zero-phases` tags the
   diagnosis so the phase report skips the stale zero. Cage only; anything
   else passes through untouched, ::refused included."
  [sol correspondences targets k]
  (if-not (and (map? sol) (:pose sol) (cage-proxy?))
    sol
    (let [picks (pnp-picks)
          index-axis (fn [ci] (some-> (nth targets ci nil) :id cage/index-parts :axis))
          hand-zero (fn [ci] (when-not (:proposed? (get picks ci)) (index-axis ci)))
          r (match-cage/rescue-hand-zeros sol correspondences k (cage-crown-count)
                                          hand-zero index-axis)]
      (if-not r
        sol
        (let [phrase (str/join ", " (for [[axis {:keys [steps deg]}] (sort-by key (:phases r))]
                                      (str (str/upper-case (name axis)) " di " steps
                                           " passi (" (.toFixed deg 0) "°)")))
              ;; the declaration to suggest is the TOTAL mounting: what the proxy
              ;; already declares plus what this photo just measured on top of it
              declared (into {} (map (fn [[a v]] [a (double v)])
                                     (or (:cage-phases (:proxy-mesh @session)) {})))
              total (merge-with + declared
                                (into {} (map (fn [[a p]] [a (:deg p)]) (:phases r))))
              decl (str/join " " (for [[axis deg] (sort-by key total)]
                                   (str ":" (name axis) " " (.toFixed deg 0))))]
          (auto-log! (str "  anello montato girato, misurato dallo zero cliccato: " phrase
                          " · col :phases dichiarato il fit chiuderebbe a "
                          (.toFixed (:rms-px (:sol r)) 1) "px con lo zero dentro (ora "
                          (.toFixed (:rms-px sol) 1) "px scartandolo)"))
          (assoc sol
                 :zero-phases (:phases r)
                 :note (str (when-let [n (:note sol)] (str n " · "))
                            "lo zero che hai cliccato non è sbagliato: l'anello " phrase
                            " risulta MONTATO girato (i dischetti ricadono su altri "
                            "dischetti, solo lo zero lo può dire). Non lo compenso: "
                            "dichiara la fase e riapri la sessione con "
                            "(registration-cage :d "
                            (or (:cage-d (:proxy-mesh @session)) "…")
                            " :phases {" decl "}) — così il fit chiude a "
                            (.toFixed (:rms-px (:sol r)) 1)
                            "px con lo zero dentro (misurato, non indovinato). "
                            "Intanto qui lo zero resta fuori dal fit.")))))))

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
        camera-pose (current-camera-pose)
        k (session-intrinsics iw ih)]
    (let [sol (let [first-try (pnp/solve-pnp correspondences k {})
                    detect (bridge/plate-detect (:proxy-mesh @session))
                    sees? (fn [s] (and s (bridge/camera-sees-marked-face?
                                          detect (:pose s))))
                    ;; A proxy whose marks face DIFFERENT ways — a cage — has no
                    ;; single marked face, so plate-detect is nil and the plate's
                    ;; test above never fires. The physical constraint is the
                    ;; same one and still available per mark: each disc that was
                    ;; clicked was photographed, so the camera was in front of it.
                    ;; Without this a cage would be the one proxy with NO guard,
                    ;; and it needs one exactly when the picks all land on a
                    ;; single ring — coplanar again, planar solver again, mirror
                    ;; twin again, and no crown-wide reflection to rescue it with.
                    picked-cis (mapv :ci correspondences)
                    per-mark-faces? (and (nil? detect) (boolean (some :normal targets)))
                    sees-marks? (fn [s] (and s (bridge/camera-sees-marks?
                                                targets picked-cis (:pose s))))]
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
                (cond
                  (and detect first-try (not (sees? first-try)))
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

                  ;; Cage (or any proxy whose marks face different ways): the
                  ;; solution claims the camera was behind a disc it says was
                  ;; clicked. There is no crown-wide reflection to try — a cage
                  ;; has six crowns, not one — but there is the same rescue the
                  ;; plate falls back on, and here it is the FIRST resort rather
                  ;; than the last: re-solve seeded from the alignment on screen.
                  ;;
                  ;; The cause is worth naming because it is not a bug and it
                  ;; will recur: shot straight down one of the cage's three axes,
                  ;; the two rings containing that axis are edge-on and their
                  ;; marks are literally on the far side of a 3mm slab, so the
                  ;; picks CAN only come from one ring — coplanar again, planar
                  ;; solver again, mirror twin again. A few degrees off the axis
                  ;; brings the others back.
                  (and per-mark-faces? first-try (not (sees-marks? first-try)))
                  (let [;; FIRST the cheapest hypothesis, and the one that was
                        ;; missing: the picks are on the OTHER FACE of their rings.
                        ;; Both faces carry the same discs through the plastic, so
                        ;; the clicks stay valid and only the names change — and
                        ;; the residual cannot arbitrate (measured: 0.00px either
                        ;; way, the 3mm absorbed by moving the camera 3mm) while
                        ;; the physical guard can, which is what just fired.
                        ;; `cage-relabel-rescue` covers this too but needs picks on
                        ;; two rings to trust one of them; with a single crown —
                        ;; the normal way to start a photograph — it bails, and
                        ;; the session refused instead of trying the one thing
                        ;; that was wrong (Vincenzo, 2026-08-23).
                        ;; through cage/relabel, which knows that a ZERO-INDEX
                        ;; changes face like everything else on its ring but does
                        ;; not move round the crown — there is one per face
                        flip-face (fn [ci]
                                    (when-let [flipped (cage/relabel
                                                        (:id (nth targets ci))
                                                        {:rot 0 :mirror? false :flip-face? true}
                                                        (cage-crown-count))]
                                      (get (into {} (map-indexed (fn [i t] [(:id t) i]) targets))
                                           flipped)))
                        flipped (when (every? some? (map (comp flip-face :ci) correspondences))
                                  (mapv (fn [c] (let [j (flip-face (:ci c))]
                                                  (assoc c :ci j :world (:obj (nth targets j)))))
                                        correspondences))
                        flip-sol (when flipped (pnp/solve-pnp flipped k {}))
                        ;; A rescue may REWRITE the user's names only when the fit
                        ;; it buys is one the session would accept. Renaming on the
                        ;; strength of a 100px fit is guessing — and the guess gets
                        ;; PERSISTED: measured on Vincenzo's session (2026-08-26),
                        ;; the name rescue rewrote picks on fits of 103 and 118px
                        ;; while the focal was poisoned at 61mm, corrupting photo
                        ;; after photo until no single lens satisfied them all and
                        ;; the joint refinement could only refuse.
                        rename-worthy? (fn [sol] (and sol (<= (:rms-px sol) pnp/accept-rms-px)))
                        flip-ok? (and flip-sol
                                      (rename-worthy? flip-sol)
                                      (bridge/camera-sees-marks?
                                       targets (mapv :ci flipped) (:pose flip-sol)))
                        seed (bridge/editor->solver-pose camera-pose proxy-pose)
                        retry (when-not flip-ok?
                                (pnp/solve-pnp correspondences k
                                               {:method :seeded :seed seed}))
                        ;; Before blaming the geometry, suspect the NAMES. On a
                        ;; cage the offered labels come from where the proxy sits,
                        ;; not from the photograph, and the two faces of a ring
                        ;; are indistinguishable — so a proxy merely out of pose
                        ;; produces clicks that are all correct and labels that
                        ;; are not. Ring by ring, because they are not all wrong
                        ;; the same way.
                        rescue (when-not (or flip-ok? (sees-marks? retry))
                                 (cage-relabel-rescue correspondences targets k
                                                      (:rms-px first-try)
                                                      (keep :pose [first-try retry])))]
                    (if flip-ok?
                      (do (relabel-picks! idx #(or (flip-face %) %))
                          (assoc flip-sol :note
                                 (str "erano sull'ALTRA FACCIA dei loro anelli: stessi dischetti "
                                      "(le due facce sono gli stessi attraverso la plastica), "
                                      "nomi corretti. Il residuo non poteva accorgersene — è "
                                      "identico nei due casi — ma la camera finiva dietro i "
                                      "dischetti che avevi fotografato")))
                      (if (and rescue (:adoptable? rescue) (rename-worthy? (:sol rescue))
                               (not (sees-marks? retry)))
                        (do (relabel-picks! idx (:flip rescue))
                            (assoc (:sol rescue) :note
                                   (str "erano i NOMI, non i click: " (:changed rescue)
                                        " dischetti stavano sull'altra faccia del loro anello"
                                        " (o contati nel verso opposto, che è la stessa cosa"
                                        " vista dall'altro lato). Rinominati anello per anello"
                                        " — i tuoi click non li ho toccati")))
                        (if (sees-marks? retry)
                          (assoc retry :note
                                 (str "la prima soluzione metteva la camera dietro i dischetti "
                                      "cliccati (sono tutti su un anello solo, e un anello solo "
                                      "ha il suo gemello specchiato): ripresa dall'allineamento "
                                      "corrente"))
                      ;; A rescue that exists but could not buy an acceptable
                      ;; fit is reported, not applied — renaming on its strength
                      ;; would persist a guess (see rename-worthy? above).
                      ;; LAST RESORT before refusing: the whole chain again on
                      ;; the HAND PICKS ALONE. The proposals are the old pose's
                      ;; own guesses, and they can push the rescue's fit just
                      ;; over the acceptance bar — measured (grab-04, 30/8):
                      ;; hand clicks alone 10.9px, with three stale proposals
                      ;; 13.0 against a bar of 12, so a photograph whose only
                      ;; fault was a through-plastic face NAME on one ring
                      ;; refused camera-dietro three times running. Guesses do
                      ;; not get to outvote the cure.
                          (let [hand-corr (vec (for [[ci {:keys [px proposed?]}] (pnp-picks)
                                                     :when (not proposed?)]
                                                 {:ci ci :world (:obj (nth targets ci)) :px px}))
                                sees-hand? (fn [s]
                                             (and s (bridge/camera-sees-marks?
                                                     targets (mapv :ci hand-corr) (:pose s))))
                                retry-hand (when (and (< (count hand-corr) (count correspondences))
                                                      (>= (count hand-corr) (min-pnp-picks)))
                                             (pnp/solve-pnp hand-corr k {}))
                                hand-flipped (when (and retry-hand
                                                        (not (sees-hand? retry-hand))
                                                        (every? some? (map (comp flip-face :ci) hand-corr)))
                                               (mapv (fn [c] (let [j (flip-face (:ci c))]
                                                               (assoc c :ci j :world (:obj (nth targets j)))))
                                                     hand-corr))
                                hand-flip-sol (when hand-flipped (pnp/solve-pnp hand-flipped k {}))
                                hand-flip-ok? (and hand-flip-sol
                                                   (rename-worthy? hand-flip-sol)
                                                   (bridge/camera-sees-marks?
                                                    targets (mapv :ci hand-flipped) (:pose hand-flip-sol)))
                                hand-rescue (when (and retry-hand (not hand-flip-ok?)
                                                       (not (sees-hand? retry-hand)))
                                              (cage-relabel-rescue hand-corr targets k
                                                                   (:rms-px retry-hand)
                                                                   [(:pose retry-hand)]))
                                drop-proposals! (fn []
                                                  (swap! session update-in [:pnp-picks idx]
                                                         (fn [m] (into {} (remove (comp :proposed? val) m)))))
                                prop-note " Le proposte della vecchia posa remavano contro: tolte."]
                            (cond
                              (and retry-hand (sees-hand? retry-hand) (rename-worthy? retry-hand))
                              (do (drop-proposals!)
                                  ;; NOT "registrata sui tuoi soli click", which is
                                  ;; what this said until 2026-09-02: the note rides
                                  ;; the FIRST solve and is printed at the end of a
                                  ;; message describing the LAST one, after
                                  ;; propose-and-snap! has put fresh marks in. On
                                  ;; Vincenzo's grab-01 that produced one line saying
                                  ;; both "9 marker agganciati in automatico" and
                                  ;; "registrata sui tuoi soli click" — the second
                                  ;; false, and the reader left to guess which. It
                                  ;; may only report what it did: drop the stale
                                  ;; proposals. What the final fit stands on is the
                                  ;; :hand-only clause's business, and that one knows.
                                  (assoc retry-hand :note
                                         (str "le PROPOSTE della vecchia posa bloccavano il "
                                              "solve: tolte")))

                              hand-flip-ok?
                              (do (drop-proposals!)
                                  (relabel-picks! idx #(or (flip-face %) %))
                                  (assoc hand-flip-sol :note
                                         (str "erano sull'ALTRA FACCIA dei loro anelli: stessi "
                                              "dischetti attraverso la plastica, nomi corretti."
                                              prop-note)))

                              (and hand-rescue (:adoptable? hand-rescue)
                                   (rename-worthy? (:sol hand-rescue)))
                              (do (drop-proposals!)
                                  (relabel-picks! idx (:flip hand-rescue))
                                  (assoc (:sol hand-rescue) :note
                                         (str "erano i NOMI, non i click: " (:changed hand-rescue)
                                              " dischetti stavano sull'altra faccia del loro anello. "
                                              "Rinominati anello per anello — i tuoi click non li ho "
                                              "toccati." prop-note)))

                              :else
                              ;; Nothing left: not the pose on screen, and not a
                              ;; misreading of the names either — on the full set
                              ;; or on the hand picks alone. The refusal PERSISTS
                              ;; nothing, so the evidence goes in the log the user
                              ;; already pastes (2026-08-27).
                              (do
                                (auto-log!
                                 (str "  rifiuto camera-dietro · focale "
                                      (:focal-mm @session) "mm ("
                                      (name (or (:focal-source @session) :default))
                                      ") · fit di partenza "
                                      (when first-try (.toFixed (:rms-px first-try) 1))
                                      "px · pick: "
                                      (pr-str (mapv (fn [{:keys [ci px]}]
                                                      [(:id (nth targets ci))
                                                       (mapv #(js/Math.round %) px)])
                                                    correspondences))))
                                (set-status-message!
                                 (str "NON applicata: ogni soluzione mette la camera DIETRO almeno "
                                      "uno dei dischetti che hai cliccato, che è impossibile — quel "
                                      "dischetto l'hai fotografato. "
                                      ;; The search is not empty just because
                                      ;; nothing was adoptable. When it found a
                                      ;; physically possible reading and only the
                                      ;; bar stopped it, that reading is the most
                                      ;; useful sentence on the screen: it names
                                      ;; the faces to declare. Saying "non basta"
                                      ;; instead threw the answer away (grab-04).
                                      ;; …and only when it is WITHIN REACH of the
                                      ;; bar. A physically possible reading at
                                      ;; 30.8px is not an answer, it is the least
                                      ;; bad of a set of bad ones, and telling the
                                      ;; user to declare its faces on that
                                      ;; strength sends him to redo his face
                                      ;; buttons for nothing (grab-06, 31/8 — the
                                      ;; message I added the day before, misfiring
                                      ;; at 30.8px on a bar of 12). Same rule as
                                      ;; everywhere else here: above ~2x the bar a
                                      ;; number is not evidence.
                                      (if-let [near (->> [hand-rescue rescue]
                                                         (filter (comp :rms-px :sol))
                                                         (filter #(<= (:rms-px (:sol %))
                                                                      (* 2.0 pnp/accept-rms-px)))
                                                         ;; …and the reading must EXPLAIN the picks,
                                                         ;; not merely survive them. A candidate that
                                                         ;; reaches its rms only by discarding a point
                                                         ;; hundreds of px out is a pose dragged by
                                                         ;; whatever that point was, and it has no
                                                         ;; standing to tell the user which FACE he is
                                                         ;; looking at. Vincenzo read the chirality
                                                         ;; straight off the print — «nella foto 4 il
                                                         ;; ring Z è p, i mark girano antiorario» —
                                                         ;; against a verdict computed from a set
                                                         ;; holding a 508px pick (grab-05, 31/8). The
                                                         ;; print is the better witness; the message
                                                         ;; had claimed 'the ONLY possible reading'.
                                                         (remove #(some (fn [o] (> (:residual-px o)
                                                                                   gross-pick-px))
                                                                        (:outliers (:sol %))))
                                                         (sort-by (comp :rms-px :sol))
                                                         first)]
                                        (if-let [delta (rescue-face-phrase
                                                        (:flip near) targets
                                                        (mapv :ci correspondences)
                                                        (get-in @session [:cage-face-choice idx]))]
                                          ;; lead with the gesture, then the number
                                          (str "LA MOSSA (se sei d'accordo): " delta ", poi ripremi 'r'. "
                                               "È l'unica lettura fisicamente possibile dei tuoi "
                                               "click e chiude a "
                                               (.toFixed (:rms-px (:sol near)) 1) "px. Non la "
                                               "applico da sola perché rinominerebbe i tuoi click "
                                               "sulla forza di un fit sopra l'asticella; "
                                               "dichiarandola tu, la rinomina non serve. Se quella "
                                               "faccia NON è quella che vedi, allora l'errore è nei "
                                               "nomi dei singoli click. ")
                                          (str "L'unica lettura fisicamente possibile dei tuoi click "
                                               "sta sulle facce che hai già dichiarato e chiude a "
                                               (.toFixed (:rms-px (:sol near)) 1)
                                               "px, sopra l'asticella: le facce non sono il "
                                               "problema, i nomi dei singoli click sì. "))
                                        ;; "I tried and it wasn't enough" is a
                                        ;; LIE when the search never ran, and it
                                        ;; sends the user looking for a bad click
                                        ;; instead of clicking more. The rename
                                        ;; search needs one ring with at least
                                        ;; four picks to build a pose from; on
                                        ;; grab-06 the biggest ring had three and
                                        ;; the whole rescue returned nil unasked
                                        ;; (Vincenzo, 31/8).
                                        (if-let [gross (->> [hand-rescue rescue]
                                                            (keep :sol)
                                                            (mapcat :outliers)
                                                            (filter #(> (:residual-px %) gross-pick-px))
                                                            (sort-by :residual-px >)
                                                            first)]
                                          (str "Non ti dico quale faccia sia, perché non lo so: la "
                                               "lettura migliore ci arriva solo BUTTANDO il punto "
                                               (corner-labels [(:ci gross)]) ", che le cade a "
                                               (.toFixed (:residual-px gross) 0) "px — e una posa "
                                               "tenuta su da uno scarto così non ha titolo per "
                                               "giudicare le facce. Controlla PRIMA quel click "
                                               "(nome sbagliato? dischetto di un altro anello?), "
                                               "toglilo con la gomma o con 'o', e ripremi 'r'. ")
                                          (let [biggest (->> (mapv :ci correspondences)
                                                             (keep #(cage/anchor-axis
                                                                     (:id (nth targets %))))
                                                             frequencies vals (reduce max 0))]
                                            (if (< biggest 4)
                                              (str "E la rinomina per anello NON HO POTUTO provarla: "
                                                   "per ricostruire una posa le serve un anello con "
                                                   "almeno 4 click, e il tuo più fornito ne ha "
                                                   biggest ". Clicca altri mark sullo STESSO anello "
                                                   "(4 o più) e ripremi 'r'. ")
                                              (str "Ho provato a rinominarli anello per anello, anche "
                                                   "sui tuoi soli click, e non basta. Due cause "
                                                   "possibili: i punti stanno tutti su UN anello "
                                                   "(clicca qualche mark su un secondo anello), "
                                                   "oppure qualche click è finito su un dischetto di "
                                                   "un anello diverso da quello che dice "
                                                   "l'etichetta. ")))))
                                      "Intanto lascio la posa che hai adesso."))
                                (swap! session assoc :last-solve ::refused)
                                ::refused)))))))

                  :else first-try))
          ;; before the outliers become red dots: a hand-clicked zero the solve
          ;; wants to discard gets the k-step re-reading of its ring first — the
          ;; one witness of a ring mounted whole steps round (fetta 2026-08-28)
          sol (cage-zero-phase-rescue! sol correspondences targets k)]
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
            ;; Tee up the first rejected corner for an immediate re-click —
            ;; but ONLY when dropping it actually bought a clean fit. Above the
            ;; bar the cleaner has merely removed the worst two of a uniformly
            ;; bad fit (see pnp-diagnosis), and arming one of them hands the
            ;; user the very gesture that cannot help: re-click an innocent
            ;; point. Measured on battiscopa3 grab-01 (2026-08-30) — four solves,
            ;; four different pairs armed, none of them the disease.
            (when (and (seq outlier-cis) (<= (:rms-px sol) pnp/accept-rms-px))
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
          added (atom 0)
          ;; Every pixel already spoken for — the picks that are there, plus the
          ;; proposals made in this same pass.
          claimed (atom (mapv :px (vals (pnp-picks))))]
      (doseq [i (sort visible)
              :when (and (not (contains? placed i)) (not (contains? occ i))
                         (contains? predicted i))]
        (let [px (get predicted i)
              gap (gap-to-nearest i px)
              radius (max 15 (min 90 (* 0.12 gap)))]
          (when-let [snap (blob/snap-to-blob backdrop/luminance-at px radius)]
            (let [c (:center snap)
                  ;; TWO marks on ONE disc is not a near miss, it is impossible,
                  ;; and it is the single most destructive thing that can enter
                  ;; this map: the solver is handed a contradiction it can only
                  ;; answer by wrecking the pose. It happened twice on the first
                  ;; real session (2026-08-19) — once from repeated clicking, and
                  ;; once from HERE, when a proposal snapped onto a disc another
                  ;; mark already held and took an 18.7px fit to 795px.
                  ;;
                  ;; Half the distance to the nearest predicted neighbour is the
                  ;; right bar and needs no pixel constant: two DISTINCT marks
                  ;; are a whole gap apart, so anything closer than half a gap is
                  ;; the same disc seen twice.
                  too-close (* 0.5 gap)
                  clash? (some (fn [q]
                                 (< (Math/sqrt (+ (* (- (nth c 0) (nth q 0)) (- (nth c 0) (nth q 0)))
                                                  (* (- (nth c 1) (nth q 1)) (- (nth c 1) (nth q 1)))))
                                    too-close))
                               @claimed)]
              (when-not clash?
                (swap! claimed conj c)
                (swap! session assoc-in [:pnp-picks idx i]
                       {:px c :screen (backdrop/screen-of-pixel canvas (viewport/get-camera) c)
                        :proposed? true})
                (swap! added inc))))))
      @added)))

(defn- cage-phase-report!
  "After a solve on a CAGE, say whether a ring looks GLUED ROUND — the one error
   the cage's design leaves open, and the one a residual reports without naming.

   The largest ring has no tabs of its own: it is held by the other two pressing
   on its face and can turn while staying seated, so its rotation is set by hand
   at glue-up, where finding the point halfway between two marks is genuinely
   hard (Vincenzo, 2026-08-19) and one degree is 1.5mm at r=85. Measured on a
   synthetic cage: 4° of it costs 22px of rms and puts the camera 15mm out.
   Without this report that is just a bad number with no cause attached, and the
   natural suspect is the solver or the clicking — neither of which is at fault.

   Prints; changes nothing. The fix it points at is `:phases` on
   `registration-cage`, which is a declaration, so the user makes it."
  [sol picks-by-ci targets k]
  (when (and sol (:pose sol) (cage-proxy?))
    (let [;; a zero the k-step rescue just re-read is turned k steps from the
          ;; model's anchor: measured against the stale :obj it would read as a
          ;; huge angular estimate and drown this report's sub-step signal
          rescued (set (keys (:zero-phases sol)))
          picks (vec (keep (fn [[ci px]]
                             (when-let [t (nth targets ci nil)]
                               (let [ip (cage/index-parts (:id t))]
                                 (when-not (and ip (contains? rescued (:axis ip)))
                                   {:axis (cage/anchor-axis (:id t)) :obj (:obj t) :px px}))))
                           picks-by-ci))
          ;; the pose that MEASURES a ring is solved without that ring — see
          ;; cage/phase-from-residuals for why measuring against the full fit
          ;; reads a third of the truth and blames the innocent rings for the rest
          solve (fn [subset]
                  (when (>= (count subset) pnp/min-correspondences)
                    (when-let [s (pnp/solve-pnp
                                  (mapv (fn [p] {:world (:obj p) :px (:px p)}) subset)
                                  k {:max-outliers 0})]
                      (fn [obj] (pcamera/project k (:pose s) obj)))))
          report (cage/phase-from-residuals picks solve)
          ;; a ring is only ACCUSED when its marks agree with each other: a
          ;; genuine turn shows the same offset on every mark of that ring, while
          ;; sloppy clicking scatters. Without the agreement test this would blame
          ;; the geometry for a shaky hand.
          turned (filter (fn [[_ r]] (and (> (Math/abs (:deg r)) 0.6)
                                          (> (Math/abs (:deg r)) (* 1.5 (:spread-deg r)))))
                         report)]
      (when (seq report)
        (state/capture-println "  fase degli anelli (da questa foto):")
        (doseq [[axis r] (sort-by key report)]
          (state/capture-println
           (str "    anello " (name axis) " (" (:n r) " mark): "
                (.toFixed (:deg r) 2) "° ±" (.toFixed (:spread-deg r) 2)
                "  =  " (.toFixed (:mm r) 2) "mm sulla corona"
                (when-not (:held-out? r)
                  " (misura PRUDENTE: pochi mark sugli altri anelli, il vero scarto è maggiore)")))))
      (when (seq turned)
        (let [[axis r] (first (sort-by (fn [[_ x]] (- (Math/abs (:deg x)))) turned))]
          (state/capture-println
           (str "  → l'anello " (name axis) " sembra INCOLLATO GIRATO di "
                (.toFixed (:deg r) 1) "°, e i suoi mark lo dicono all'unisono"
                " (±" (.toFixed (:spread-deg r) 2) "°). Non è il solutore e non sono"
                " i click: riapri la sessione con"))
          (state/capture-println
           (str "     {:proxy (registration-cage :d "
                (or (:cage-d (:proxy-mesh @session)) "<diam>")
                " :phases {" axis " " (.toFixed (:deg r) 1) "})}"))
          (state/capture-println
           "     poi ri-registra questa foto: se il residuo crolla, era quello."))))))

(defn- retry-on-hand-picks!
  "When a settled fit sits ABOVE the acceptance bar and part of the picks are
   automatic proposals, solve again on the HAND CLICKS ALONE — and if THAT is
   clean, throw the proposals away and keep it.

   A proposal is the previous pose's own guess, blob-snapped to whatever disc
   was nearest; when the previous pose was wrong the guesses are wrong together,
   and being the majority they set the fit. Measured on battiscopa3 grab-01
   (2026-08-30): eleven hand clicks, every one of them within a pixel of a
   detected disc, fit at 9.2px — clean. The same clicks plus seventeen proposals
   fit at 13.8px, over the bar, and the cleaner then rejected two of HIS points
   (one of which was itself a proposal he had never placed). Four solves in a
   row, four different pairs accused, the photograph never registering.

   The camera-behind rescue already learned this (grab-04, 30/8) and does the
   same thing at the end of its chain. This is the plain over-the-bar case,
   which is far commoner and had no such fallback. Guesses do not get to
   outvote the clicks.

   Returns the new solve when it took over, nil otherwise. Nothing is dropped
   unless the hand-only fit is BOTH clean and physically possible — a smaller
   point set is easier to fit, so 'better rms' alone would be no evidence."
  [sol iw ih]
  (let [idx (:current-idx @session)
        picks (pnp-picks)
        hand (into {} (remove (comp :proposed? val) picks))]
    (when (and sol
               ;; two triggers, same medicine: the fit WITH the proposals is
               ;; over the bar, or the re-solve with them was REFUSED outright
               ;; (:reproposed-refused? — camera-dietro on the proposal set,
               ;; Vincenzo 2026-09-02): either way the guesses are the suspects
               ;; and the clicks alone get their chance
               (or (:reproposed-refused? sol)
                   (> (:rms-px sol) pnp/accept-rms-px))
               (< (count hand) (count picks))
               (>= (count hand) (min-pnp-picks)))
      (let [targets (pnp-targets)
            k (session-intrinsics iw ih)
            corr (vec (for [[ci {:keys [px]}] hand]
                        {:ci ci :world (:obj (nth targets ci)) :px px}))
            try-sol (pnp/solve-pnp corr k {})
            sees? (and try-sol
                       (or (nil? (some :normal targets))
                           (bridge/camera-sees-marks? targets (mapv :ci corr) (:pose try-sol))))]
        (when (and try-sol sees? (<= (:rms-px try-sol) pnp/accept-rms-px))
          ;; drop the guesses and re-solve through the normal path, so the pose
          ;; is applied and the residual/outlier overlays are rebuilt on the set
          ;; that actually produced it. If that path refuses after all, put the
          ;; picks back: a rescue that does not land must leave no trace.
          (swap! session update-in [:pnp-picks idx]
                 (fn [m] (into {} (remove (comp :proposed? val) m))))
          (let [applied (solve-and-apply! iw ih)]
            (if (and applied (not= applied ::refused))
              (assoc applied :hand-only (- (count picks) (count hand)))
              (do (swap! session assoc-in [:pnp-picks idx] picks)
                  nil))))))))

(defn- two-faced-rings
  "The rings whose picks name BOTH of their faces — {axis #{cis}} — or nil.

   This is impossible before any solving is attempted, and saying so costs one
   pass over the picks. The two faces of a ring are the same discs 3mm apart
   through the plastic and their printed normals point OPPOSITE WAYS, so a set
   holding `yp03` and `zero-ym` claims the camera was in front of both: it was
   in front of neither. Every solve is then refused camera-dietro, and the
   refusal blames a click on the wrong ring — a diagnosis that sends the user
   hunting through picks that are all fine (Vincenzo, battiscopa3 grab-04,
   2026-08-31: the culprit was a ⊙ym clicked before he declared Yp).

   Not a solver concern — a solver sees only world points — so it lives here,
   where the labels do."
  []
  (let [targets (pnp-targets)
        parts (fn [ci] (let [id (:id (nth targets ci))]
                         (or (cage/mark-parts id) (cage/index-parts id))))]
    (not-empty
     (into {} (keep (fn [[axis cis]]
                      (when (> (count (set (map (comp :sign parts) cis))) 1)
                        [axis (set cis)])))
           (group-by (comp :axis parts) (filter parts (keys (pnp-picks))))))))

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
    (let [n (count (pnp-picks))
          two-faced (when (cage-proxy?) (two-faced-rings))]
      (cond
        two-faced
        ;; refuse BEFORE solving, and name the contradiction: no pose exists, so
        ;; letting the solver discover that produces a camera-dietro refusal that
        ;; blames the wrong thing
        (set-status-message!
         (str "Non risolvo: su "
              (if (> (count two-faced) 1) "questi anelli hai click " "questo anello hai click ")
              "su TUTTE E DUE le facce — "
              (str/join "; "
                        (for [[axis cis] (sort-by (comp str key) two-faced)]
                          (str (str/upper-case (name axis)) ": " (corner-labels cis))))
              ". Le due facce guardano da parti opposte, quindi nessuna posa può"
              " averle fotografate entrambe. Dichiara la faccia di "
              (str/join "/" (map (comp str/upper-case name key) (sort-by (comp str key) two-faced)))
              " col suo bottone — i click passano da soli sulla faccia giusta"
              " — oppure togli con la gomma quelli di troppo."))

        (< n (min-pnp-picks))
        (set-status-message!
         (str "PnP: servono almeno " (min-pnp-picks) " " (pnp-noun) " piazzati (ne hai " n ")"))

        :else
        (if-let [sol (let [r (solve-and-apply! iw ih)]
                       (when-not (= r ::refused) r))]
          (let [added (propose-and-snap! (:pose sol) (session-intrinsics iw ih))
                ;; the re-solve WITH the proposals can itself be REFUSED
                ;; (camera-dietro) — and ::refused must not flow on as if it
                ;; were a solution: (:method ::refused) is nil, and (name nil)
                ;; is the «Doesn't support name» crash Vincenzo hit live
                ;; (2026-09-02, prima sessione col seme dell'occhio). The
                ;; FIRST accepted solve stays the fact on screen; the refusal
                ;; is recorded on the result so the hand-retry below — the
                ;; same medicine as over-the-bar guesses — gets its chance to
                ;; shed the proposals that caused it.
                settled (if (pos? added)
                          (let [r (solve-and-apply! iw ih)]
                            (if (or (nil? r) (= r ::refused))
                              (-> sol
                                  (assoc :reproposed-refused? true)
                                  (update :note
                                          #(str (when % (str % " — "))
                                                "le proposte agganciate mandavano il ri-solve "
                                                "in rifiuto: tenuta la posa dei tuoi click")))
                              r))
                          sol)
                ;; over the bar with guesses in the set? try the clicks alone
                rescued (retry-on-hand-picks! settled iw ih)
                final (or rescued settled)]
            (when-let [[iw2 ih2] (backdrop/image-size)]
              (cage-phase-report! final
                                  (mapv (fn [[ci {:keys [px]}]] [ci px]) (pnp-picks))
                                  (pnp-targets)
                                  (session-intrinsics iw2 ih2)))
            (set-status-message!
             (str "PnP " (name (:method final)) ": " (pnp-diagnosis final)
                  (when-let [dropped (:hand-only final)]
                    (str " · le " dropped " proposte automatiche remavano contro"
                         " (" (if (:reproposed-refused? settled)
                                "rifiuto secco"
                                (str (.toFixed (:rms-px settled) 1) "px"))
                         " con loro): tolte,"
                         " registrata sui tuoi soli click"))
                  (when (and (pos? added) (not (:hand-only final)))
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

(def ^:private propose-clear-px
  "No automatic proposal may land within this (image px) of an EXISTING pick,
   whatever name either carries. The double-booking it forbids is not a near
   miss but the cage's own anatomy: the two faces of a ring are the same discs
   through 3mm of plastic, ~2px apart in the image, so a reading that believes
   the other face happily proposes zm01 on the very pixel of the hand's zp01 —
   measured 2026-08-28 (foto 4): five doubled discs, fit 194.9px, unrecoverable.
   `duplicate-pick-px` (6) cannot cover this: an ALT click is taken literally
   and can sit ~25px off the detector centroid of its own disc (cf.
   suspicious-snap-px), while distinct marks stay 200-500px apart in working
   frames — so thirty is comfortably both above the one and below the other."
  30.0)

;; ── the session's cage mounting (zero-click lever 2) ─────────────────────────
;;
;; One session is one mounting: the cage opens only at part changes, and each
;; ring's zero-index sits at ONE pose-absolute (sense, slot) that every true
;; registration reads identically (match-cage/index-witness). Accumulating
;; those observations per photo and VOTING gives the machine the arbiter the
;; through-plastic/reflection twins cannot pass — measured on battiscopa
;; (2026-08-30): with it, foto 7 registers TRUE at 1.1mm where before it
;; shipped the twin at 764mm, and the vote itself exposed a twin among the
;; session's own HAND registrations. In-memory only, like :pnp?: a reloaded
;; session rebuilds it as photos are read.

(defn- cage-obs-focal-ok?
  "Index observations are slot GEOMETRY, and slot geometry under an unmeasured
   lens lies: at the default 48 on a 44mm camera the Z index read the MIRROR
   family in BOTH live sessions that started cold (28-29/8), raising false
   RIBALTATO/GEMELLO alarms that evaporated the moment the lens was measured.
   Third time was battiscopa3's opening night. No vote, no mounting diagnosis
   and no mounting arbitration until the lens has a real source — EXIF, live
   measure, refinement, or the user's own hand on the slider."
  []
  (not (contains? #{:default nil} (:focal-source @session))))

(defn- remember-cage-mounting!
  "Store one photo's index observations — every accepted cage reading measures
   the mounting, hand-seeded or machine. Refuses them at an unmeasured lens
   (`cage-obs-focal-ok?`)."
  [idx obs]
  (when (and (seq obs) (cage-obs-focal-ok?))
    (swap! session assoc-in [:cage-mounting-obs idx] obs)))

(defn- declared-cage-mounting
  "The mounting the proxy DECLARATION asserts — {axis entry} for cages with
   explicit `:phases` OR `:flips`, nil otherwise. The rings are GLUED
   (Vincenzo, 30/8: «incollati con l'attack, l'unico modo di cambiarli è
   ristampare»), so the mounting is a property of the CAGE, not of the
   session: a declaration asserts that every index sits on the MODEL's own
   slot, and that assertion arms the twin arbiter from the FIRST photo — no
   cold start on a declared cage.

   `:fwd k0` for every ring, flipped or not, and that is not an oversight:
   the observations' housings are computed from the TARGETS' own geometry
   (match-cage/index-witness reads the index offset off the anchors, never
   assuming ⅓ step), and the anchors carry the declared flip. A correctly
   declared cage therefore reads as its own nominal — the flip lives in the
   model, not in the expected reading. Until 2026-09-02 this armed only on
   `:phases` (flips didn't exist to declare when it was written), so
   battiscopa3's true form — `:flips #{:y :z}`, no phases — would have left
   the arbiter in cold start. Votes 2: enough to demand and veto, beatable by
   a session that reads otherwise three times over."
  []
  (let [pm (:proxy-mesh @session)]
    (when (or (seq (:cage-phases pm)) (seq (:cage-flips pm)))
      (into {} (for [a [:x :y :z]]
                 [a {:sense :fwd :k 0 :votes 2 :d 0.0 :declared? true}])))))

(defn- session-cage-mounting
  "The session's mounting: the declaration's assertion, refined by the vote of
   the photos (photo `idx` excluded — its own earlier reading must never
   arbitrate its re-read). A measured entry displaces the declared one only
   when it outvotes it 3-to-nothing uncontested."
  [idx]
  (let [measured (match-cage/vote-mounting
                  (vals (dissoc (or (:cage-mounting-obs @session) {}) idx)))]
    (merge-with (fn [d m]
                  (if (and (>= (:votes m 0) 3) (not (:contested? m))) m d))
                (or (declared-cage-mounting) {})
                measured)))

(defn- cage-flips-tag
  "A cage's declared flips as sorted NAMES, nil when there are none — the form
   that survives the round trip through acquire-state.json's JSON, where
   keywords come back as strings, and that lets a file written before :flips
   existed still match a cage without them."
  [proxy-mesh]
  (some->> (seq (:cage-flips proxy-mesh)) (map name) sort vec))

(defn- flips-suggestion
  "The `(registration-cage …)` form to reopen with, adding `axes` (names like
   \"Z\") to whatever the session's proxy already declares — diameter, phases
   and the flips already there.

   Written whole, not as a diff, for the rule this project learned the hard
   way: a message must name the GESTURE, never ask the user to reconstruct a
   form from memory. And additive, because a cage with one flipped ring can
   have two — dropping the flip already declared would trade one wrong
   declaration for another."
  [axes]
  (let [mesh (:proxy-mesh @session)
        add (set (map (comp keyword str/lower-case) axes))
        flips (sort (map name (into add (or (:cage-flips mesh) #{}))))
        phases (:cage-phases mesh)]
    (str "(registration-cage :d " (or (:cage-d mesh) "…")
         " :flips #{" (str/join " " (map #(str ":" %) flips)) "}"
         (when (seq phases)
           (str " :phases {"
                (str/join " " (for [[a d] (sort-by (comp str key) phases)]
                                (str ":" (name a) " " d)))
                "}"))
         ")")))

(defn- cage-mounting-suffix
  "The diagnosis lines the index observations earn — and only when they earn
   them (a caveat that always prints stops being read). A `:rev` ring is
   mounted FLIPPED: the reading stays good (crowns are flip-blind), but the
   assembly is not what the model says. Since 2026-09-01 that is a DECLARATION
   the user can make (`:flips`), so these lines name it instead of sending him
   back to the printer. An observation that reads a ring AGAINST the session's
   own vote means a twin sits among the session's registrations — this one or
   the others — or that the declaration itself is wrong, which the same day
   proved is not hypothetical."
  [mounting obs]
  (let [;; the EVIDENCE, not just the accusation: which housing the index was
        ;; actually seen in, against which expectation, at what pixel distance.
        ;; Added 2026-09-02, when Z kept contradicting on photos whose double
        ;; pallini looked right — a verdict without its measurement cannot be
        ;; argued with, in either direction (the project's own rule: una
        ;; diagnosi non può essere più forte delle prove che la reggono).
        ;; Since the same day each obs is the ring's ONE reading (the sharpest
        ;; hit — index-witness folds), so a contradiction here means the
        ;; ring's best evidence disagrees, not that a stray candidate grazed
        ;; some slot while the true index sat on its nominal (photo 2 of
        ;; battiscopa3: X read fwd k0 at 1.4px AND junk at rev k2 — the junk
        ;; fired this warning). And when the NOMINAL slot is empty (:zero-d
        ;; beyond the witness's own threshold), the accusation carries that
        ;; too: an index nobody detected cannot testify, so the stray that
        ;; did is the prime suspect, and the message should weigh it so.
        dis-ev (for [{:keys [axis sense k d zero-d]} obs
                     :let [m (get mounting axis)]
                     :when (and m (or (not= sense (:sense m))
                                      (not= k (:k m))))]
                 {:axis (str/upper-case (name axis))
                  :seen (str (name sense) " k" k
                             (when d (str " a " (.toFixed d 1) "px")))
                  :expected (str (name (:sense m)) " k" (:k m))
                  ;; the nominal slot's own state rides the evidence: an index
                  ;; nobody detected cannot testify, so the stray that did is
                  ;; the prime suspect — appended AFTER the expectation, or the
                  ;; sentence reads «pesa il terzo sospetto invece di fwd k0»
                  ;; (Vincenzo's live log, 2026-09-02)
                  :nominal (when (and (number? zero-d)
                                      (> zero-d match-cage/index-obs-px))
                             (str " (e al posto NOMINALE nessun dischetto — il più "
                                  "vicino a " (.toFixed zero-d 0) "px: l'indice vero "
                                  "è coperto o non rilevato, pesa il terzo sospetto)"))})
        dis (set (map :axis dis-ev))
        dis-detail (fn [axes]
                     (str/join "; "
                               (for [{:keys [axis seen expected nominal]} dis-ev
                                     :when (contains? (set axes) axis)]
                                 (str axis " visto " seen " invece di " expected
                                      nominal))))
        ;; a ring the session reads TURNED by whole steps, unanimously and
        ;; twice over, has earned the `:phases` suggestion — the diagnosis
        ;; Vincenzo asked for by direttiva (28/8: riconoscere e suggerire,
        ;; mai supplire). Measured before it was written: battiscopa2 reads
        ;; x=k6, y=k6 (180°) on every hand pose — the same mounting the stage
        ;; had measured on this physical cage. Self-limiting: once declared
        ;; in the proxy the observations read k0 and the line stops printing
        step-deg (/ 360.0 (max 1 (cage-crown-count)))
        turned (seq (sort (distinct
                           (for [{:keys [axis sense k]} obs
                                 :let [m (get mounting axis)]
                                 :when (and m (= :fwd sense) (pos? k)
                                            (= sense (:sense m)) (= k (:k m))
                                            (>= (:votes m 0) 2)
                                            (not (:contested? m)))]
                             [(name axis) (js/Math.round (* k step-deg))]))))
        ;; a CONTESTED ring gets only the contest warning: asserting 'mounted
        ;; flipped, remount it' about a reading the session disputes claims as
        ;; fact exactly what is in question — this photo may be the mirrored
        ;; one (both lines fired together on Vincenzo's first live log,
        ;; 2026-08-29, and read as an instruction to open the cage)
        rev-axes (remove dis (distinct (for [{:keys [axis sense]} obs
                                             :when (= :rev sense)]
                                         (str/upper-case (name axis)))))
        ;; asserted only when the SESSION agrees twice over — a lone
        ;; observation on a photo just registered through five renames is a
        ;; hint, not a verdict (battiscopa3 foto 2, 30/8: «X/Z RIBALTATO»
        ;; dichiarato da una foto sola a 11.9px)
        confirmed-rev? (fn [a] (let [m (get mounting (keyword (str/lower-case a)))]
                                 (and m (= :rev (:sense m)) (>= (:votes m 0) 2)
                                      (not (:contested? m)))))
        revs-sure (seq (sort (filter confirmed-rev? rev-axes)))
        revs-hint (seq (sort (remove confirmed-rev? rev-axes)))
        ;; the rings are GLUED (attack): a contradiction with the DECLARED
        ;; mounting can never mean 'remounted' — it accuses this reading
        declared? (fn [a] (:declared? (get mounting (keyword (str/lower-case a)))))
        dis-decl (seq (sort (filter declared? dis)))
        dis-meas (seq (sort (remove declared? dis)))]
    (str
     ;; Until 2026-09-01 these three lines ended in 'reprint it': a flip could
     ;; be SEEN and not said, so the only cure on offer was new plastic. It is
     ;; declarable now (registration-cage's :flips), so they name the
     ;; declaration instead — and the same day proved the sharper half of the
     ;; point, that a DECLARATION can be the wrong thing: Vincenzo's :phases
     ;; {:x 180} was, and nothing he could align by would ever have said so.
     (when revs-sure
       (str " · l'indice dell'anello " (str/join "/" revs-sure)
            " si vede SPECCHIATO su più foto concordi: quell'anello è INCOLLATO "
            "ribaltato. Dichiaralo — non si ristampa niente: riapri con "
            (flips-suggestion revs-sure)
            " e RIMISURA la fase di quell'anello (il flip cambia cosa misura)"))
     (when revs-hint
       (str " · in QUESTA foto l'indice dell'anello " (str/join "/" revs-hint)
            " si legge specchiato — da solo non fa verdetto: se lo confermano "
            "le prossime foto, l'anello fu incollato ribaltato e si dichiara con "
            (flips-suggestion revs-hint)))
     (when turned
       (str " · anelli montati GIRATI (confermato da più foto): "
            (str/join ", " (for [[a deg] turned]
                             (str (str/upper-case a) " di " deg "°")))
            " — per dichiararlo al modello riapri la sessione con "
            "(registration-cage :d " (or (:cage-d (:proxy-mesh @session)) "…")
            " :phases {" (str/join " " (for [[a deg] turned]
                                         (str ":" a " " deg)))
            "}) — i click fatti restano validi"))
     (when dis-decl
       (str " · ATTENZIONE: qui l'indice dell'anello " (str/join "/" dis-decl)
            " contraddice la DICHIARAZIONE della gabbia (" (dis-detail dis-decl)
            "). Due sospetti, in "
            "quest'ordine: (1) QUESTA registrazione è il gemello — rifalle i "
            "click, o verificala sulle altre foto; (2) è la DICHIARAZIONE a "
            "essere sbagliata — se l'anello è montato ribaltato si dichiara con "
            (flips-suggestion dis-decl)
            ", e se è girato di passi interi è la sua fase. Il montaggio è una "
            "costante fisica, ma quello che ne hai DETTO al modello no. "
            "Terzo sospetto, se il doppio pallino disegnato cade su quello vero: "
            "il testimone ha preso per indice un dischetto che non lo è "
            "(riflesso, stick) — guarda la distanza qui sopra"))
     (when dis-meas
       (str " · ATTENZIONE: qui l'indice dell'anello " (str/join "/" dis-meas)
            " si legge DIVERSAMENTE dalle altre foto della sessione ("
            (dis-detail dis-meas)
            ") — una delle "
            "due registrazioni è il GEMELLO. Un click sul doppio pallino di "
            "quell'anello in una TERZA foto fa da spareggio")))))

(defn- cage-read-and-place!
  "Cage 'a': the crown you clicked, read by the REST OF THE CAGE — then every
   other mark it accounts for placed for you, and the pose solved on all of them.

   The failure it exists for is SILENT, which is why it needs a key of its own and
   could not be left to the solve's guards. A crown of twelve equal marks reads
   the same rotated, so a user who starts counting three marks late produces picks
   that are all correct, labels that are all wrong, a residual of 5px, and a camera
   that IS in front of every disc they clicked — nothing fires. Measured on
   Vincenzo's photograph: that reading leaves the other two rings' marks 92-157px
   from the discs actually in the frame, and the session registers it as a success.

   `match-cage/read-crown` settles it by scoring each of the 48 readings on how
   much of the WHOLE cage its pose explains against the detector's candidates — the
   information a single crown does not contain and more clicking cannot supply.

   What lands in the session is ordinary: the picks are renamed (the pixels are
   never touched — they were right), the marks the reading accounts for are added
   as proposed picks exactly as fetta A does for a plate, and the normal solve runs
   over all of them. So the panel, the residuals, the outlier dots and the phase
   report all work with no new plumbing, and a proposal that grabbed the wrong disc
   shows up as a red outlier to re-click, like any other."
  []
  (let [idx (:current-idx @session)
        targets (pnp-targets)
        marks (cage-crown-count)
        placed (pnp-picks)
        ;; The seed is the USER's clicks — never the :proposed? picks. A proposal
        ;; is the old pose's own guess written back as data (propose-and-snap!),
        ;; and this reading exists precisely to overturn that pose: seeding the
        ;; arbitration with the defendant's testimony let a rotation-consistent
        ;; mislabel defend itself against fresh hand clicks (2026-08-27 evening:
        ;; two ALT clicks discarded as outliers by fourteen proposals).
        hand-picks (into {} (keep (fn [[ci {:keys [px proposed?]}]]
                                    (when-not proposed?
                                      (when-let [t (nth targets ci nil)] [(:id t) px])))
                                  placed))
        ;; read-crown's contract is the marks of ONE ring; hand clicks can span
        ;; rings (a second ring gets clicked when a refusal suggests it), so the
        ;; seed is the ring with the most clicks — the rest stay in the pool and
        ;; the reading renames them with everything else.
        ring-of-id (fn [id] (let [n (name id)]
                              (if (str/starts-with? n "zero-") (subs n 5 6) (subs n 0 1))))
        picks-by-id (if (seq hand-picks)
                      (into {} (val (apply max-key (comp count val)
                                           (group-by (comp ring-of-id key) hand-picks))))
                      {})
        ;; the zero-indices the hand clicked on rings OTHER than the seed: the
        ;; arbiter that outranks the vote (read-crown's :zero-picks). Measured
        ;; 2026-08-28: a FULL seed ring, zero included, still tied on its
        ;; through-plastic twin — only the other ring's clicked zero can refuse
        ;; the twin as a fact rather than outscore it.
        seed-axis (when (seq picks-by-id) (ring-of-id (key (first picks-by-id))))
        zero-picks (vec (for [[id px] hand-picks
                              :let [zp (cage/index-parts id)]
                              :when (and zp (not= (name (:axis zp)) seed-axis))]
                          {:axis (:axis zp) :px px}))
        id->ci (into {} (map-indexed (fn [i t] [(:id t) i]) targets))
        ;; `get` and not `nth`: with no session open :current-idx is nil, and nth
        ;; throws on a nil index even with a default. Found by calling this from
        ;; the browser with nothing loaded (2026-08-24) — the compiler cannot see
        ;; it, and neither can a test that always sets up a session first.
        file (:file (get (vec (:photos @session)) idx))
        ;; every message names its photo. Vincenzo's logs are this channel's
        ;; measuring instrument, and a log line that does not say WHICH photo
        ;; it is about cannot be cited back — «Non so quali sono le foto che
        ;; citi» (29/8) after two rounds of otherwise-perfect logs
        foto-tag (when file (str "foto " (inc idx) " — " file ": "))
        say! (fn [msg] (set-status-message! (str foto-tag msg)))]
    (cond
      (nil? file)
      (set-status-message! "Nessuna foto su cui leggere la gabbia.")

      ;; ZERO click: try to read the cage entirely on its own (Vincenzo,
      ;; 2026-08-27: «se non riusciamo ad avere la registrazione automatica
      ;; sarà tutto inutile»). The machine finds a ring among the detector's
      ;; candidates, pins its zero-index on the pixels, and solves on the whole
      ;; cage — measured on the battiscopa session: 2 of 8 frames register
      ;; alone, camera within 1-4mm of the hand result, zero false positives.
      ;; When it refuses, the seeded path (4 clicks + 'a') is the fallback, and
      ;; the message says so.
      (zero? (count picks-by-id))
      (do
        (say! "Leggo la gabbia DA SOLA (zero click)… può volerci fino a mezzo minuto")
        (js/setTimeout
         (fn []
           (-> (backdrop/load-luminance-sampler (photo-path file))
               (.then
                (fn [{:keys [lum-at size data]}]
                  (let [[iw ih] size
                        k (session-intrinsics iw ih)
                        cands (blob-detect/detect-blobs lum-at size
                                                        (assoc blob-detect/cage-opts :rgba data))
                        judge (fn [px r] (blob/disc-at? lum-at px r))
                        disc-r (or (:mark-disc-r (:proxy-mesh @session)) 1.25)
                        ;; no arbitration at an unmeasured lens: the stored
                        ;; vote is trusted, but the observations THIS read
                        ;; would judge by are being measured NOW, under the
                        ;; current focal
                        mounting (when (cage-obs-focal-ok?)
                                   (session-cage-mounting idx))
                        ;; the EYE SEED (lever 3): this photo's pose, but only
                        ;; when it is HUMAN — committed by gizmo (:eye-posed)
                        ;; or vouched by a registration. A bare turntable seed
                        ;; gating the hypotheses would kill true readings,
                        ;; which is worse than no gate at all.
                        eye-pose (when (or (contains? (or (:eye-posed @session) #{}) idx)
                                           (registered-result?
                                            (get-in @session [:acquire-results idx])))
                                   (when-let [cp (get-in @session [:camera-poses idx])]
                                     (bridge/editor->solver-pose
                                      cp (get-in @session [:proxy-mesh :creation-pose]))))
                        rr (match-cage/auto-read (mapv :center cands) targets k
                                                 judge marks
                                                 {:disc-r disc-r
                                                  :mounting mounting
                                                  :blobs cands
                                                  :eye-pose eye-pose
                                                  ;; the comb identity (lever 1)
                                                  ;; rides only where the
                                                  ;; mounting arbiter has
                                                  ;; jurisdiction: with no
                                                  ;; session context its extra
                                                  ;; reach registered the twins
                                                  ;; (misurato 30/8, 548/764mm)
                                                  ;; — or where the user's own
                                                  ;; eye does (the gizmo seed
                                                  ;; kills the same twins)
                                                  :teeth? (boolean (or (seq mounting)
                                                                       eye-pose))})]
                    (if (nil? rr)
                      (say!
                       (str "Da sola non ci riesco su questa foto (" (count cands)
                            " dischetti trovati, nessun anello identificato con certezza). "
                            "Clicca 4 dischetti su UN anello + il doppio pallino, poi ripremi 'a'."
                            (when eye-pose
                              (str " Ho provato anche dalla tua posa a occhio, ma il fit "
                                   "non reggeva le barre: se la gabbia disegnata ti sembra "
                                   "già appaiata bene, il problema sono i dischetti rilevati "
                                   "(pochi, o su un anello solo)."))))
                      (let [canvas (viewport/get-canvas)
                            ;; a clean slate: with zero hand clicks whatever picks
                            ;; exist are STALE proposals of an older pose — left
                            ;; alive under other names they double-book the discs
                            ;; this reading is about to propose (the same disease
                            ;; propose-clear-px guards in the seeded branch)
                            _ (swap! session update-in [:pnp-picks idx]
                                     (fn [m] (into {} (remove (comp :proposed? val) m))))
                            added (reduce (fn [n {:keys [ci px]}]
                                            (let [j (id->ci ci)]
                                              (if (nil? j)
                                                n
                                                (do (swap! session assoc-in [:pnp-picks idx j]
                                                           {:px px
                                                            :screen (backdrop/screen-of-pixel
                                                                     canvas (viewport/get-camera) px)
                                                            :proposed? true})
                                                    (inc n)))))
                                          0 (:corr rr))]
                        (remember-cage-mounting! idx (:index-obs rr))
                        (say!
                         (str (if (:eye-seed? rr)
                                ;; the eye-seed result has no seed RING to name
                                ;; (:seed nil) — and naming the mechanism tells
                                ;; the user his alignment is what did the work
                                (str "Gabbia letta dalla TUA posa a occhio: ")
                                (str "Gabbia letta DA SOLA: anello "
                                     (name (:axis (:seed rr)))
                                     " + zero-indice trovati nella foto, "))
                              added " dischetti piazzati (rms " (.toFixed (:rms-px rr) 1) "px)"
                              (when (:phase-suspect rr)
                                (str " · ATTENZIONE: l'anello "
                                     (name (:axis (:phase-suspect rr)))
                                     " sembra incollato girato di "
                                     (.toFixed (:deg (:phase-suspect rr)) 0) "°"))
                              (when mounting
                                (cage-mounting-suffix mounting (:index-obs rr)))))
                        (on-solve-pnp!))))))
               (.catch (fn [e]
                         (say! (str "Lettura automatica fallita: " (str e)))))))
         50))

      (< (count picks-by-id) 4)
      (say!
       (str "Per leggere la gabbia servono almeno 4 dischetti cliccati su UN anello "
            "(ne hai " (count picks-by-id) "): arma un mark con 'p' e clicca dov'è nella foto. "
            "Lo zero-indice, se lo vedi, vale doppio. Con ZERO click, 'a' prova da sola."))

      :else
      (do
        (say! "Leggo la gabbia… (rilevo i dischetti in tutta la foto)")
        (-> (backdrop/load-luminance-sampler (photo-path file))
            (.then
             (fn [{:keys [lum-at size data]}]
               (let [[iw ih] size
                     k (session-intrinsics iw ih)
                     cands (blob-detect/detect-blobs lum-at size
                                                     (assoc blob-detect/cage-opts :rgba data))
                     res (match-cage/read-crown picks-by-id targets (mapv :center cands) k marks
                                                {:zero-picks zero-picks
                                                 ;; the session's voted mounting
                                                 ;; arbitrates the hand seed too
                                                 ;; (foto 5, 2026-08-29: the
                                                 ;; flip-face twin renamed
                                                 ;; correct picks on a starved
                                                 ;; frame the session knew
                                                 ;; better about) — but never at
                                                 ;; an unmeasured lens
                                                 :mounting (when (cage-obs-focal-ok?)
                                                             (session-cage-mounting idx))
                                                 ;; the faces you declared with
                                                 ;; the ring toggles: facts, not
                                                 ;; hypotheses to re-read
                                                 :declared-faces
                                                 (get-in @session [:cage-face-choice idx])})]
                 (cond
                   (nil? res)
                   (say!
                    (str "Non riesco a leggere la gabbia da questa foto: dei "
                         (count cands) " dischetti trovati, nessuna delle 48 riletture "
                         "della corona ne spiega abbastanza. Di solito vuol dire che si vede "
                         "UN anello solo — bastano pochi gradi fuori dall'asse perché "
                         "ricompaiano gli altri."
                         ;; the evidence in the log, camera-dietro-style: foto 3
                         ;; (30/8) refused on a seed silently polluted by clicks
                         ;; from three nights before, and nothing printed WHICH
                         ;; picks the reading was fed
                         " · seme " (pr-str (vec (sort-by (comp str key) picks-by-id)))
                         (when-let [others (seq (remove (set (keys picks-by-id))
                                                        (keys hand-picks)))]
                           (str " · altri click a mano nel mucchio: "
                                (str/join " " (sort (map name others)))
                                " — se qualcuno è di una sessione di lavoro passata, "
                                "Azzera e riclicca pulito"))))

                   :else
                   (let [{:keys [reading corr]} res
                         nominal? (= reading {:rot 0 :mirror? false :flip-face? false})
                         flip (fn [ci] (or (some-> (nth targets ci nil) :id
                                                   (cage/relabel reading marks)
                                                   id->ci)
                                           ci))
                         canvas (viewport/get-canvas)]
                     (when-not nominal? (relabel-picks! idx flip))
                     ;; the OLD pose's proposals die here: they were its testimony,
                     ;; and the reading that just won may have overturned it. Left
                     ;; alive they outvote the fresh clicks in the very next solve
                     ;; (2026-08-27 evening: 'a' would have handed on-solve-pnp! the
                     ;; same fourteen stale proposals that had been discarding the
                     ;; user's ALT clicks as outliers). The reading's own corr
                     ;; re-proposes every mark it accounts for, so nothing earned
                     ;; is lost.
                     (swap! session update-in [:pnp-picks idx]
                            (fn [m] (into {} (remove (comp :proposed? val) m))))
                     ;; the marks the reading accounts for, minus the ones already
                     ;; clicked — the user's own pixels always win over a proposal,
                     ;; and win BY DISTANCE, not by name: the two faces of a ring
                     ;; project through the plastic onto the same disc, so corr can
                     ;; propose the whole other face on top of the hand's clicks
                     ;; under different names (measured 2026-08-28, foto 4: zp01 and
                     ;; zm01 on the SAME pixel — 2 doubles get dropped by the solve,
                     ;; 10 kill it camera-behind). No proposal lands within
                     ;; propose-clear-px of an existing pick, whatever it is called.
                     (let [kept (set (keys (get-in @session [:pnp-picks idx])))
                           kept-px (mapv :px (vals (get-in @session [:pnp-picks idx])))
                           on-your-disc? (fn [[u v]]
                                           (some (fn [[qu qv]]
                                                   (< (Math/hypot (- u qu) (- v qv))
                                                      propose-clear-px))
                                                 kept-px))
                           ;; which face of each ring the HAND clicked — a fact
                           ;; that outranks the pose's own guess. The Z-only seed
                           ;; is planar and its wrong homography branch believes
                           ;; the OTHER face of X visible, proposing xp over a
                           ;; clicked zero-xm: mixed faces, camera-dietro certo
                           ;; (battiscopa3 foto 2, tre sere di fila)
                           hand-face (into {} (keep (fn [[ci v]]
                                                      (when-not (:proposed? v)
                                                        (when-let [t (nth targets ci nil)]
                                                          (when-let [p (or (cage/mark-parts (:id t))
                                                                           (cage/index-parts (:id t)))]
                                                            [(:axis p) (:sign p)]))))
                                                    (get-in @session [:pnp-picks idx])))
                           against-hand? (fn [id]
                                           (when-let [p (or (cage/mark-parts id)
                                                            (cage/index-parts id))]
                                             (when-let [s (hand-face (:axis p))]
                                               (not= s (:sign p)))))
                           [added shadowed contrari]
                           (reduce (fn [[n s c] {:keys [ci px]}]
                                     (let [j (id->ci ci)]
                                       (cond
                                         (or (nil? j) (contains? kept j)) [n s c]
                                         (against-hand? ci) [n s (inc c)]
                                         (on-your-disc? px) [n (inc s) c]
                                         :else
                                         (do (swap! session assoc-in [:pnp-picks idx j]
                                                    {:px px
                                                     :screen (backdrop/screen-of-pixel
                                                              canvas (viewport/get-camera) px)
                                                     :proposed? true})
                                             [(inc n) s c]))))
                                   [0 0 0] corr)
                           zv (:zero-veto res)
                           ;; the hand reading measures the mounting too — and
                           ;; is measured BY it: an index read against the
                           ;; session's vote is the twin diagnosis, on either
                           ;; side (misurato 30/8: foto 1 della sessione-verità
                           ;; era registrata A MANO dal gemello, e a dirlo sono
                           ;; state le altre cinque)
                           wit-obs (when (cage-obs-focal-ok?)
                                     (when-let [fp (:pose (:full res))]
                                       (:obs (match-cage/index-witness targets cands k
                                                                       fp marks {}))))
                           _ (remember-cage-mounting! idx wit-obs)
                           ;; a clicked other-ring zero whose index the witness
                           ;; does NOT see is a tiebreak gesture that decided
                           ;; nothing at session level — SILENCE here is
                           ;; ambiguous ('agrees' vs 'never voted') and
                           ;; Vincenzo's 29/8 spareggio landed exactly in that
                           ;; ambiguity: say it
                           unvoted (when wit-obs
                                     (seq (sort (distinct
                                                 (for [{:keys [axis]} zero-picks
                                                       :when (not-any? #(= axis (:axis %))
                                                                       wit-obs)]
                                                   (str/upper-case (name axis)))))))
                           suffix (str
                                   (when wit-obs
                                     (cage-mounting-suffix (session-cage-mounting idx) wit-obs))
                                   (when unvoted
                                     (str " · nota: il doppio pallino di " (str/join "/" unvoted)
                                          " che hai cliccato non è fra i dischetti RILEVATI "
                                          "sotto questa posa — qui ha arbitrato le riletture, "
                                          "ma nel voto fra le foto della sessione non conta"))
                                   (when (and zv (pos? (:killed zv)))
                                     (str " · lo zero cliccato sull'altro anello ha fatto da "
                                          "arbitro: " (:killed zv) " riletture contraddette da "
                                          "quel doppio pallino"))
                                   (when-let [mv (:mounting-veto res)]
                                     (cond
                                       (:moot? mv)
                                       (str " · ATTENZIONE: TUTTE le riletture contraddicono il "
                                            "montaggio che la sessione ha già misurato su "
                                            "quest'anello — o qualche click è su un anello "
                                            "diverso, o una delle registrazioni passate è da "
                                            "riguardare")
                                       (pos? (:killed mv))
                                       (str " · il montaggio misurato dalla sessione ha fatto "
                                            "da arbitro: " (:killed mv) " riletture contraddette")))
                                   (when (seq (:moot zv))
                                     (let [axes (str/join "/" (map (comp str/upper-case name)
                                                                   (:moot zv)))]
                                       ;; on a GLUED cage with declared phases a
                                       ;; whole-step turn is impossible — the
                                       ;; suspect is the click (foto 3, 30/8:
                                       ;; this message blamed the mounting at
                                       ;; 52.8px)
                                       (if (declared-cage-mounting)
                                         (str " · ATTENZIONE: lo zero dell'anello " axes
                                              " non torna con NESSUNA rilettura — su una "
                                              "gabbia incollata e dichiarata vuol dire quasi "
                                              "sempre che quel click è su un dischetto "
                                              "sbagliato (o contato dall'altra faccia): "
                                              "controllalo o toglilo con il click destro")
                                         (str " · ATTENZIONE: lo zero dell'anello " axes
                                              " non torna con NESSUNA rilettura — quell'anello è "
                                              "probabilmente MONTATO girato di passi interi; il "
                                              "solve adesso lo misura proprio da quello zero"))))
                                   (when (pos? shadowed)
                                     (str " · " shadowed " proposte scartate: cadevano su "
                                          "dischetti già tuoi, sotto un altro nome"))
                                   (when (pos? contrari)
                                     (str " · " contrari " proposte scartate: stavano sulla "
                                          "FACCIA OPPOSTA a un anello che hai cliccato tu — "
                                          "mi fido dei tuoi occhi, non della posa")))]
                       (say!
                        (if-let [ps (:phase-suspect res)]
                          ;; The one assembly error nothing else can see, found by
                          ;; the disagreement of two witnesses: the candidates say
                          ;; the other rings sit k steps round, the clicked
                          ;; zero-index says the numbering is right. Both are: the
                          ;; ring was GLUED turned. Diagnosed live on Vincenzo's
                          ;; cage (2026-08-24, 90° on the big ring).
                          (str "I tuoi nomi sono GIUSTI (lo zero-indice li conferma), ma gli "
                               "altri anelli stanno " (:steps ps) " dischetti più in là: l'anello "
                               (str/upper-case (name (or (:axis ps) :x)))
                               " sembra INCOLLATO girato di " (.toFixed (:deg ps) 0) "°. "
                               "Riapri la sessione dichiarandolo nel proxy: (registration-cage :d "
                               (or (:cage-d (:proxy-mesh @session)) "…")
                               " :phases {:" (name (or (:axis ps) :x)) " " (.toFixed (:deg ps) 0)
                               "}) — se le facce di Y/Z escono invertite, usa −"
                               (.toFixed (:deg ps) 0) ". I click fatti restano validi." suffix)
                          (str "Gabbia letta: "
                               (if nominal?
                                 "i nomi che avevi dato erano giusti"
                                 (str "i tuoi click erano giusti, i NOMI no — la corona era "
                                      "sfasata di " (:rot reading) " mark"
                                      (when (:mirror? reading) ", letta al contrario")
                                      (when (:flip-face? reading) ", e dall'altra faccia")
                                      " (una corona di " marks " dischetti uguali si rilegge "
                                      "identica ruotata: a dirlo è il resto della gabbia, non l'anello)"))
                               " · " added " dischetti piazzati in automatico"
                               (when (seq (:ties res))
                                 (str " · ATTENZIONE: " (count (:ties res))
                                      " riletture spiegano la gabbia altrettanto bene — "
                                      "questa foto non basta a decidere"))
                               suffix)))
                       (on-solve-pnp!)))))))
            (.catch (fn [e]
                      (say! (str "Lettura della gabbia fallita: " (str e))))))))))

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

      (cage-proxy?)
      (set-status-message! (no-auto-on-cage-msg))

      (nil? (:zero-obj (bridge/plate-detect (:proxy-mesh @session))))
      ;; the proxy has no zero-index (an OLD plate def, before it was exposed on
      ;; :anchors) — the batch can't break the crown's rotational symmetry without
      ;; it. Say exactly that, not the misleading "clicca più sparsi".
      (set-status-message!
       (str "Questo piatto non espone lo zero-indice: rivaluta il file aggiornato "
            "il proxy (il piatto ora ha lo zero sotto :anchors) e "
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
        (set-status-message! "Questo piatto non espone lo zero-indice: usa (registration-plate :d <diametro>) come proxy e riapri.")
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

(defn- snap-radius-for
  "The blob-snap window (px) this frame's crown deserves — sized to the imaged disc
   rather than fixed at 40, so a 1920px live frame and a 4032px photo both land
   inside blob-snap's dark-fraction band. See match-plate/snap-window-radius for
   the arithmetic, and for why the phone-photo path is unchanged by this."
  [pixels marks disc-r]
  (match-plate/snap-window-radius pixels marks disc-r))

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

(declare register-live-frame live-focal remember-camera-focal! camera-lens-key)

(defn- register-one-auto-photo!
  "Register ONE unregistered photo `idx` with zero clicks, through EXACTLY the
   same pipeline a live grab goes through — detect, identify, MEASURE THE LENS
   off the plate, re-identify, solve, judge by residual.

   It used to have its own copy of that pipeline, identical but for one
   assumption: it took the focal from the session instead of measuring it. On a
   session of live-grabbed frames there is no EXIF to have set one, so it ran at
   the 48mm default while the lens was 28.6 — and the difference does not present
   itself as a wrong focal, it presents itself as residuals of 8-10px and photos
   that will not identify. Measured on the same eight frames: 0.3-2.5px through
   the grab path, 7.9-10.7px and two failures through this one (2026-08-13).
   Two copies of one pipeline is how one of them ends up with an assumption the
   other doesn't have.

   Resolves true on a registration, else false (a skip, never a throw)."
  [idx]
  (-> (backdrop/load-luminance-sampler (photo-path (:file (nth (:photos @session) idx))))
      (.then (fn [sampler]
               ;; proxy-pose BEFORE this photo — photo 0 moves the proxy
               (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
                     adopt? (nil? (live-focal))
                     out (register-live-frame sampler)]
                 (if-not (:ok? out)
                   (do (auto-log! (str "  foto " idx ": " (:message out))) false)
                   (let [{:keys [sol picks measured n]} out]
                     ;; the lens of the session, measured once off whichever photo
                     ;; identifies first; the rest reuse it
                     (when (and adopt? (:focal-mm measured))
                       (swap! session assoc :focal-mm (:focal-mm measured) :focal-source :live)
                       (auto-log! (str "  focale MISURATA dal piatto: "
                                       (.toFixed (:focal-mm measured) 1) "mm-equiv"))
                       ;; filed only when measured off a GRABBED frame: this
                       ;; batch also registers folder photos, and a still's
                       ;; lens filed under the grab camera's key would be the
                       ;; 4032↔1920 transplant the store exists to end
                       (when (re-find #"^grab-" (or (:file (nth (:photos @session) idx)) ""))
                         (remember-camera-focal! (:focal-mm measured) :live)))
                     (apply-auto-solve! idx sol picks proxy-pose)
                     (auto-log! (str "  foto " idx ": registrata ✓  rms "
                                     (.toFixed (:rms-px sol) 1) "px, " n " dischetti"))
                     true)))))
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

   Reuses each photo's own clicks; asks for nothing new.

   ROBUST since 2026-09-02 (direttiva di Vincenzo, dopo grab-04): a view the
   joint fit gets WORSE with loses its VOTE on the lens — leave-one-out on the
   worst per-view residual, retried down to a floor of four views — and keeps
   everything else: film, pose, clicks. A photo is worth what it SHOWS (an
   all-rings-oblique vantage can be exactly the one the tracing needs), so
   registration quality decides the vote, never membership. Every exclusion
   is announced, log and status — misurare e riferire, mai compensare in
   silenzio."
  []
  (if-let [[iw ih] (backdrop/image-size)]
    (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
          targets (pnp-targets)
          ;; A photo whose OWN registration is an OUTLIER among the session's must
          ;; not vote on the LENS. The joint fit is least-squares: one poisoned
          ;; view does not average out, it drags — measured on Vincenzo's session
          ;; (2026-08-25): foto 6, registered at 138.8px after a tangle of
          ;; duplicate clicks, pulled the shared focal from 48.6 to 60.7mm and
          ;; took every clean photo from 3-8px to 12-20px with it. The refiner
          ;; even NAMED it ("è questa che tira su la media") and then let it win.
          ;;
          ;; The bar is RELATIVE (3× the session's median rms, never below the
          ;; acceptance bar), and it must be: the first version used the absolute
          ;; 12px alone, and on the poisoned session it would have excluded
          ;; every photo — at the dragged focal the CLEAN photos all sat at
          ;; 12-20px, and only R itself can bring them back down. Against a
          ;; median of ~15 the bar is ~45: foto 6 (127) is out, the six clean
          ;; ones vote, the focal returns.
          all-rms (vec (sort (keep #(get-in @session [:acquire-results % :rms-px])
                                   (range (count (:photos @session))))))
          bar (if (seq all-rms)
                (max pnp/accept-rms-px
                     (* 3.0 (nth all-rms (quot (dec (count all-rms)) 2))))
                pnp/accept-rms-px)
          poisoned (vec (for [idx (range (count (:photos @session)))
                              :let [r (get-in @session [:acquire-results idx :rms-px])]
                              :when (and r (> r bar))]
                          idx))
          ;; …and a photo that was NEVER successfully registered does not vote at
          ;; all, whatever picks it carries: those picks were REFUSED by every
          ;; per-photo solve, which is the strongest possible statement about
          ;; them. Without this, Vincenzo's foto 8 — unregistrable, clicks
          ;; tangled at 931px — entered the joint fit through the side door (it
          ;; had picks and a camera pose) and dragged the lens 48→50.6mm, while
          ;; the poisoned-list above never saw it BECAUSE refusals leave no
          ;; registered rms to judge. The circle he named — R fails because 8 is
          ;; broken, 8 cannot be fixed because R is poisoned — was exactly this.
          ;; registered-result?, NOT the :pnp? flag: the flag lives only in
          ;; memory, while :matched/:rms-px round-trip through acquire-state —
          ;; tested on the first reload, where every photo of the session came
          ;; back "mai registrata" and R had nobody left to ask.
          unregistered (vec (for [idx (range (count (:photos @session)))
                                  :when (and (>= (count (get-in @session [:pnp-picks idx] {})) 4)
                                             (not (registered-result?
                                                   (get-in @session [:acquire-results idx]))))]
                              idx))
          views (vec (keep (fn [idx]
                             ;; A pick the per-photo solve already REJECTED must not
                             ;; vote here. solve-pnp reports its rms over the
                             ;; survivors, but the rejected picks stay in :pnp-picks
                             ;; (they are kept so the user can re-click them), and
                             ;; feeding them back made the joint fit both look worse
                             ;; and BE worse — it re-fitted every pose against points
                             ;; known to be wrong. Measured 2026-08-11: a photo the
                             ;; solve had cleaned to 1.7px came back as 8.04px here,
                             ;; and its pose was being dragged to earn that number.
                             (let [dropped (set (get-in @session [:pnp-outliers idx] #{}))
                                   picks (remove (fn [[ci _]] (dropped ci))
                                                 (get-in @session [:pnp-picks idx] {}))
                                   cam (get-in @session [:camera-poses idx])]
                               (when (and cam (>= (count picks) 4)
                                          (not (some #{idx} poisoned))
                                          (registered-result?
                                           (get-in @session [:acquire-results idx])))
                                 {:idx idx
                                  ;; one lens, one session: the pixel size is the
                                  ;; current photo's, which is every photo's
                                  :image-size [iw ih]
                                  :pose (bridge/editor->solver-pose cam proxy-pose)
                                  :picks (vec (for [[ci {:keys [px]}] picks]
                                                {:world (:obj (nth targets ci)) :px px}))})))
                           (range (count (:photos @session)))))
          ;; LEAVE-ONE-OUT quando peggiora — direttiva di Vincenzo (2/9 sera,
          ;; dopo grab-04): una foto vale per il PEZZO che mostra, non per come
          ;; registra — un'inquadratura che mette tutti gli anelli di taglio
          ;; può essere esattamente quella che serve al ricalco, e buttarla dal
          ;; film per far passare la R è la cura sbagliata. Quindi: se il fit
          ;; congiunto peggiora, la vista peggiore smette di votare sulla
          ;; LENTE e si riprova, finché migliora o restano quattro viste (il
          ;; minimo con cui una focale condivisa significa qualcosa). La foto
          ;; resta nel film, con posa e click suoi; l'esclusione si dice
          ;; sempre, a voce alta — misura e riferisce, mai compensare in
          ;; silenzio.
          focal-now (:focal-mm @session)
          refine-from
          (fn [f0]
            (loop [vs views held []]
              (let [o (bundle/refine-session vs f0)]
                (if (and (not (:error o))
                         (> (:rms-px o) (+ (:rms-px (:before o)) 1e-9))
                         (> (count vs) 4)
                         (seq (:per-view o)))
                  (let [idxs (:views o)
                        w (nth idxs (first (apply max-key second
                                                  (map-indexed vector (:per-view o)))))]
                    (recur (vec (remove #(= w (:idx %)) vs)) (conj held w)))
                  [o vs held]))))
          [out active-views held-out] (refine-from focal-now)
          ;; the session's ACTUAL state, for honest before→after messages even
          ;; when the winner restarted from the store (whose own :before is
          ;; the old poses evaluated under the other lens — a big number that
          ;; measures the restart, not the session)
          session-before (:rms-px (:before out))
          ;; MULTI-START: the refine is a local optimizer in a valley the
          ;; poses keep flattening (focal ↔ distance), and it can stall far
          ;; from home — measured 3/9 on battiscopa4: from the default 48 it
          ;; 'converged' at 40.67mm, not clamped, just stalled, while the
          ;; sweep of the SAME picks dips at 27.35 (mediana 2.36px contro
          ;; 9.60). The store's number for this camera is a measurement and
          ;; exactly the prior that breaks that valley: try it as a second
          ;; start and let the residuals judge — on the same view set, or
          ;; the comparison is apples to oranges.
          [out active-views held-out from-remembered?]
          (let [m (:remembered-focal-mm @session)]
            (if (and m (number? m)
                     (> (js/Math.abs (- m (:focal-mm @session))) (* 0.02 m)))
              (let [[o2 av2 ho2] (refine-from m)]
                (if (and (not (:error o2))
                         (= (set (map :idx av2)) (set (map :idx active-views)))
                         (< (:rms-px o2) (:rms-px out)))
                  [o2 av2 ho2 true]
                  [out active-views held-out false]))
              [out active-views held-out false]))]
      (doseq [idx unregistered]
        (auto-log! (str "  foto " (inc idx) " ha click ma NON è registrata: non vota "
                        "sulla lente. Registrala prima (Azzera, 4 click + doppio "
                        "pallino, 'a'), poi rifai R.")))
      (doseq [idx poisoned]
        (auto-log! (str "  foto " (inc idx) " ESCLUSA dalla rifinitura: la sua "
                        "registrazione è a "
                        (modal/fmt-number (get-in @session [:acquire-results idx :rms-px]))
                        "px, sopra la soglia di " (modal/fmt-number bar) " — sistemala (Azzera, poi "
                        "4 click + 'a') e rifai R")))
      (cond
        (:error out)
        (set-status-message! (str "Rifinitura: " (:error out)))

        ;; A refinement that made things WORSE is not a refinement, and adopting
        ;; it poisons everything downstream: the session focal feeds every later
        ;; solve on every photo. Measured (2026-08-25): 48.9 → 90.3px, ADOPTED,
        ;; focal clamped at the limit — and photo 8 became unsolvable at 60.7mm.
        ;; Keep what we had, say why, name the worst view.
        (> (:rms-px out) (+ (:rms-px (:before out)) 1e-9))
        ;; here even the leave-one-out above ran dry: the fit worsens ANCHE
        ;; sulle quattro viste migliori. That is no longer one bad photo — it
        ;; is the set (or the lens hypothesis) as a whole, and the message
        ;; says what was tried instead of pointing a finger the user cannot
        ;; act on (grab-04, 2/9: «guardala» on a photo whose vantage was the
        ;; problem left him stuck; deleting it was the wrong cure — his call).
        (do
          (auto-log! (str "=== rifinitura RIFIUTATA: peggiorava ("
                          (modal/fmt-number (:rms-px (:before out))) " → "
                          (modal/fmt-number (:rms-px out)) " px)"
                          (when (seq held-out)
                            (str " anche senza le foto "
                                 (str/join "/" (map inc (sort held-out)))))
                          " ==="))
          (set-status-message!
           (str "Rifinitura NON applicata: peggiorava ("
                (modal/fmt-number (:rms-px (:before out))) " → "
                (modal/fmt-number (:rms-px out)) " px)"
                (if (seq held-out)
                  (str ", e ho provato anche a togliere dal voto "
                       (if (> (count held-out) 1) "le foto " "la foto ")
                       (str/join "/" (map inc (sort held-out)))
                       " — non basta: non è una foto sola, è il set (o la "
                       "lente di partenza). Controlla i click segnati rossi "
                       "sulle foto peggiori, o registra una vista in più con "
                       "un anello ben di faccia.")
                  ". ")
                " Tengo focale e pose che avevi.")))

        :else
        (let [{:keys [focal-mm poses rms-px before]} out]
          ;; active-views, NOT views: a held-out photo's pose must not be
          ;; overwritten by a zip against the subset's poses — it kept its own
          (doseq [[view pose] (map vector (filterv #(and (:pose %) (>= (count (:picks %)) 4))
                                                   active-views)
                                   poses)]
            (swap! session assoc-in [:camera-poses (:idx view)]
                   (bridge/solver-pose->camera pose proxy-pose)))
          ;; the exclusions, said out loud — the photo stays in the film with
          ;; its pose and clicks; it only lost its vote on the LENS. A photo
          ;; is worth what it SHOWS (l'inquadratura può essere quella giusta
          ;; per il pezzo — Vincenzo, 2/9): registration quality decides the
          ;; vote, never membership.
          (doseq [idx (sort held-out)]
            (auto-log! (str "  foto " (inc idx) " NON ha votato sulla lente: il fit "
                            "congiunto peggiorava con lei. Resta nel film con la sua "
                            "posa e i suoi click — se la vuoi riallineata alla lente "
                            "nuova, premi 'r' su di lei.")))
          ;; :refined, not :manual. The joint fit is the BEST focal the session will
          ;; ever have — one lens against every view's picks — and calling it "manual"
          ;; made it indistinguishable from a slider nudge, so the next live grab
          ;; treated the session as having no lens of its own, measured its own from
          ;; ONE frame, and adopted it over the joint fit (found live 2026-08-11:
          ;; 28.41mm fitted on 5 views, replaced by 27.25mm from a single grab).
          (swap! session assoc :focal-mm focal-mm :focal-source :refined)
          ;; the joint fit is the best number this lens will ever get from one
          ;; session — file it under the camera the grabs came from, so the
          ;; NEXT session starts at the measured lens instead of the mute 48.
          ;; Only when the WHOLE film is grabs: the fit is one focal over every
          ;; view, and in a mixed film that number belongs to no single camera.
          ;; And NEVER when the refine was CLAMPED: R moves the lens at most
          ;; 15% per pass, so a clamped result is the trust region's edge, not
          ;; a measurement — filed once (3/9, C922 partita dal default 48):
          ;; 48×0.85 = 40.80mm went into the store as «refined» over the
          ;; camera's real 27.3, and every next session would inherit the lie
          (when (and (not (:clamped? out))
                     (seq (:photos @session))
                     (every? #(re-find #"^grab-" (or (:file %) "")) (:photos @session)))
            (remember-camera-focal! focal-mm :refined))
          (auto-log! (str "=== rifinitura congiunta: " (count poses) " foto ==="))
          (auto-log! (str "  focale "
                          (modal/fmt-number (if from-remembered? focal-now (:focal-mm before)))
                          " → " (modal/fmt-number focal-mm) " mm"
                          (when from-remembered?
                            (str "  (RIPARTITA dalla lente in memoria di questa camera, "
                                 (modal/fmt-number (:remembered-focal-mm @session))
                                 "mm: la focale della sessione era un minimo locale — "
                                 "i residui, sulle stesse viste, danno ragione alla memoria)"))
                          ;; a clamped move has two very different causes and
                          ;; the old text named only one: bad clicks dragging
                          ;; the lens (the 2026-08-25 disaster) — but a lens
                          ;; that STARTED far (the default 48 on a ~27 camera,
                          ;; 3/9) clamps too, and there the cure is simply to
                          ;; press R again: ±15% per pass, it walks home
                          (when (:clamped? out)
                            (str "  (fermata al limite di QUESTA passata: la R si "
                                 "muove al massimo del 15% alla volta — RIPREMI R; "
                                 "se sbatte sul limite anche dopo 2-3 passate, "
                                 "guarda i click)"))))
          (auto-log! (str "  riproiezione "
                          (modal/fmt-number (if from-remembered? session-before (:rms-px before)))
                          " → " (modal/fmt-number rms-px) " px"))
          ;; ONE number over N views cannot be judged — Vincenzo, 2026-08-11: "non so
          ;; giudicare, un po' migliora, ma poco". It was 9.3px because five views
          ;; registered before the outlier fix still carried their bad picks, and the
          ;; three good new ones (1.7-5.1px) were buried in the average. A per-view
          ;; line makes that visible instead of leaving it to be deduced, and the
          ;; badges are brought up to date so the filmstrip stops showing what each
          ;; photo scored BEFORE the joint fit.
          (let [idxs (:views out)
                per (:per-view out)
                per-before (:per-view before)
                worst (when (seq per) (reduce max per))]
            (doseq [[idx b a] (map vector idxs per-before per)]
              ;; on a restart from the store, the per-view 'before' measures
              ;; the RESTART (old poses under the other lens), not the
              ;; session — print only the landing
              (auto-log! (str "    foto " (inc idx) ": "
                              (if from-remembered?
                                (str (modal/fmt-number a) " px (con la lente nuova)")
                                (str (modal/fmt-number b) " → " (modal/fmt-number a) " px"))
                              (when (and worst (= a worst) (> a (* 2.0 rms-px)))
                                "   ← è questa che tira su la media"))))
            (doseq [[idx a] (map vector idxs per)]
              (when (get-in @session [:acquire-results idx])
                (swap! session assoc-in [:acquire-results idx :rms-px] a))))
          (save-acquire-state!)
          ;; the session's cage-mounting observations were measured under the
          ;; OLD intrinsics and every pose just moved: stale, and worse than
          ;; empty — obs taken at the default 48mm read the Z index in the
          ;; MIRROR family and raised a false GEMELLO alarm against the same
          ;; photos re-read at the measured 44 (log di Vincenzo, 29/8, primo
          ;; contro secondo giro). Re-pressing 'a' rebuilds the vote clean.
          (swap! session dissoc :cage-mounting-obs)
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
                (modal/fmt-number (if from-remembered? session-before (:rms-px before)))
                " → " (modal/fmt-number rms-px) " px"
                (when from-remembered?
                  (str " · RIPARTITA dalla lente in memoria ("
                       (modal/fmt-number (:remembered-focal-mm @session))
                       "mm): la focale che avevi era un minimo locale"))
                (when (:clamped? out)
                  " · la lente ha sbattuto sul limite della passata (±15%): RIPREMI R")
                (when (seq held-out)
                  (str " · " (if (> (count held-out) 1) "le foto " "la foto ")
                       (str/join "/" (map inc (sort held-out)))
                       " non " (if (> (count held-out) 1) "hanno" "ha")
                       " votato sulla lente (peggiorava con "
                       (if (> (count held-out) 1) "loro" "lei")
                       "): resta nel film coi suoi click — 'r' su di lei per "
                       "riallinearla")))))))
    (set-status-message! "Rifinitura: nessuna foto caricata.")))

;; ============================================================
;; Plate calibration — measuring the plate instead of trusting it
;; ============================================================
;;
;; Every measurement in this channel is referred to the registration plate, and
;; until now the plate was assumed perfect: the marks were wherever
;; `registration-plate` computed them. A printed one is not. Measured on the
;; ⌀300 plate on 2026-08-13, over twelve registered views: three marks stood
;; more than a millimetre out of the plane (worst 1.63mm), the rest within a
;; quarter. That is the residual nothing could explain — it changed with the
;; plate's rotation, because a mark that stands proud of the plane projects
;; differently depending which way the camera looks across it.
;;
;; Vincenzo's call, and the right one: 'stampare un piatto perfetto è
;; difficilissimo, per me e per chiunque provasse a utilizzare questa feature'.
;; So the plate stops being an assumption and becomes a measurement, like
;; everything else here.
;;
;; WHERE IT IS KEPT. Not in the session and not in the source: in
;; ~/.ridley/plates/, keyed by diameter and crown count. The calibration
;; describes a physical object that outlives any one session — you print a
;; plate, you calibrate it once, and every session that says
;; `(registration-plate :d 300)` from then on is measuring against the plate you
;; actually own. The session keeps its own copy in acquire-state.json so its
;; numbers stay reproducible even if the store is later overwritten by a
;; re-print.

(def ^:private small-deviation-mm
  "Below this, a calibration that fails to generalise means the plate is FINE.

   The held-out test refuses two very different things with one verdict, and they
   have opposite remedies. A large deviation that does not generalise is bad picks
   — go and re-click. A small one that does not generalise is a plate already flat
   to within what the session can see, and the only right response is to carry on.
   Telling the second case to go re-click sends someone hunting for an error that
   is not there.

   0.5mm, because a clean twelve-view session on a 1920px frame measures a mark to
   a couple of tenths: Vincenzo's ⌀300, once its blown picks were being dropped,
   came back at 0.24mm and refused — and a hand check agreed, one rise under a
   millimetre."
  0.5)

(defn- plate-store-path
  "Where this plate's calibration is filed: ~/.ridley/plates/plate-300mm-12.json.
   nil when the proxy is not a plate, or is one from before :plate-d existed."
  []
  (let [{:keys [plate-d plate-marks]} (:proxy-mesh @session)]
    (when (and plate-d plate-marks)
      (str (stl/expand-home "~/.ridley/plates/")
           "plate-" (js/Math.round plate-d) "mm-" plate-marks ".json"))))

(defn- crown-ids
  "The proxy's crown marks in PICK ORDER — the same order bridge/pnp-target-points
   uses, so index i here is pick index i everywhere else."
  []
  (vec (sort (keys (dissoc (:anchors (:proxy-mesh @session)) :zero)))))

(defn- apply-plate-calibration!
  "Move the proxy's crown anchors to `obj-positions` (object frame, pick order).

   This is the ONLY place the calibration needs to touch, and that is the point:
   every consumer — the PnP targets, the auto-detector's crown model, the drawn
   dots, the joint refine — reads the marks off the proxy, so correcting the
   proxy corrects all of them at once. The mesh's vertices are left alone; the
   disc is still a disc, it is where the marks sit on it that was wrong."
  [obj-positions]
  (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])]
    ;; Whatever is about to be overwritten is the MODEL's crown — remember it
    ;; once, the first time anything overwrites it. Every later run measures
    ;; from the model rather than from the previous measurement, so a second
    ;; calibration is a fresh reading of the plate and not a correction of a
    ;; correction, and its report says how far the PLATE is from the drawing
    ;; instead of how far this run drifted from the last one.
    (when-not (:plate-nominal @session)
      (swap! session assoc :plate-nominal
             (mapv (fn [id] (bridge/world->local
                             proxy-pose
                             (get-in @session [:proxy-mesh :anchors id :position])))
                   (crown-ids))))
    (swap! session update-in [:proxy-mesh :anchors]
           (fn [anchors]
             (reduce (fn [a [id obj]]
                       (assoc-in a [id :position] (bridge/local->world proxy-pose obj)))
                     anchors
                     (map vector (crown-ids) obj-positions))))))

(defn- calibration-views
  "The registered photos as plate-calib wants them, or nil if there aren't
   enough. Same construction as the joint refine, including the exclusion of
   picks the per-photo solve already rejected — a pick known to be wrong must not
   get a vote on where a mark IS, of all things."
  [iw ih]
  (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
        k (session-intrinsics iw ih)]
    (vec (keep (fn [idx]
                 (let [dropped (set (get-in @session [:pnp-outliers idx] #{}))
                       picks (remove (fn [[ci _]] (dropped ci))
                                     (get-in @session [:pnp-picks idx] {}))
                       cam (get-in @session [:camera-poses idx])]
                   (when (and cam (>= (count picks) 6))
                     {:idx idx
                      :intrinsics k
                      :pose (bridge/editor->solver-pose cam proxy-pose)
                      :picks (into {} (for [[ci {:keys [px]}] picks] [ci px]))})))
               (range (count (:photos @session)))))))

(defn- save-plate-calibration!
  "File the calibration under the plate it describes. Best-effort: the session's
   own copy (acquire-state.json) is what makes this run reproducible, so a store
   that cannot be written is worth a console line and nothing more."
  [calib]
  (if-let [path (plate-store-path)]
    (-> (stl/desktop-write-file (js/JSON.stringify (clj->js calib)) path)
        (.then (fn [_] (auto-log! (str "  archiviata in " path
                                       " — vale anche per le prossime sessioni con questo piatto"))))
        (.catch (fn [err] (js/console.warn "edit-acquire: couldn't save the plate calibration" err))))
    ;; A proxy that cannot say which plate it is (an older `acquire` form, or a
    ;; hand-built mesh with :anchors) still gets calibrated — the session keeps
    ;; its own copy — but there is nowhere to file it, and saying so is better
    ;; than letting the user believe the plate is now calibrated for good.
    (auto-log! (str "  NON archiviata: questo proxy non dice quale piatto è. "
                    "Vale per questa sessione; per renderla permanente riapri con "
                    "(registration-plate :d <diametro>)."))))

(defn- on-calibrate-plate!
  "'C': measure where this plate's marks really are, and use them from now on.

   Needs a session that is already registered and refined — the calibration reads
   the poses, so it inherits whatever is wrong with them. Run it after 'R'."
  []
  (cond
    (not (plate-proxy?))
    (set-status-message! "La calibrazione (C) è solo per il piatto di registrazione.")

    (nil? (backdrop/image-size))
    (set-status-message! "Calibrazione: nessuna foto caricata.")

    :else
    (let [[iw ih] (backdrop/image-size)
          views (calibration-views iw ih)
          targets (pnp-targets)
          ;; the marks as the MODEL has them, never as a previous run left them
          ;; (see apply-plate-calibration!): each calibration is a fresh reading
          ;; of the plate, measured from the drawing
          nominal (or (:plate-nominal @session) (mapv :obj targets))
          n-marks (count nominal)
          ;; a mark nobody clicked has no rays; calibrate refuses on it, but the
          ;; refusal is much more useful once it can name the mark
          seen (frequencies (mapcat (comp keys :picks) views))
          thin (filterv #(< (get seen % 0) 2) (range n-marks))]
      (cond
        (< (count views) 3)
        (set-status-message!
         (str "Calibrazione: servono almeno 3 foto registrate con ≥6 mark ciascuna "
              "(ne ho " (count views) "). Registra ('a' o 'p') e rifinisci ('R') prima."))

        (seq thin)
        (set-status-message!
         (str "Calibrazione: " (if (= 1 (count thin)) "il mark " "i mark ")
              (str/join ", " (map #(:label (nth targets %)) thin))
              (if (= 1 (count thin)) " è visto" " sono visti")
              " da meno di due foto — senza due raggi non ha una posizione. "
              "Gira il piatto e registra una foto in più."))

        :else
        (let [r (plate-calib/calibrate views nominal)
              ;; The verdict, before anything is adopted. A calibration's own
              ;; residual ALWAYS falls — it was chosen to make it fall — so it
              ;; cannot tell a warped plate from a fit that has swallowed the
              ;; noise of the very photographs it was given. Only a photograph
              ;; held out of the measurement can.
              ;;
              ;; This is not a hypothetical guard. On the first real session
              ;; (2026-08-14, twelve views on a ⌀300) the fitted residual fell
              ;; from 2.18 to 1.78px and reported a 1.97mm warp — and held out,
              ;; the same plate made EIGHT of twelve photographs worse. The whole
              ;; effect traced to one mark whose pick was wrong in four frames:
              ;; drop m00 and those four fall from 3.4/2.3/3.0/2.4px to
              ;; 1.5/0.4/0.3/0.3 on the UNTOUCHED model plate. The calibration
              ;; had been bending the plate around a bad click.
              cv (when-not (:error r) (plate-calib/cross-validate views nominal))]
          (cond
            (:error r)
            (do (auto-log! "=== calibrazione del piatto: RIFIUTATA ===")
                (auto-log! (str "  " (:error r)))
                (set-status-message! (str "Calibrazione rifiutata: " (:error r))))

            (and cv (not (:error cv)) (not= :confirmed (:verdict cv)))
            (do
              (auto-log! "=== calibrazione del piatto: NON CONFERMATA, non adottata ===")
              (auto-log! (str "  misurando, il residuo scenderebbe da "
                              (modal/fmt-number (:rms-before r)) " a "
                              (modal/fmt-number (:rms-after r)) " px — ma quel numero "
                              "non vale: è lo stesso che la misura è stata scelta per "
                              "abbassare."))
              (auto-log! (str "  provato su foto tenute FUORI dalla misura: modello "
                              (modal/fmt-number (:nominal-px cv)) " px → piatto misurato "
                              (modal/fmt-number (:measured-px cv)) " px · "
                              (:better cv) " migliorate, " (:worse cv) " peggiorate"))
              (doseq [{:keys [idx nominal-px measured-px]} (sort-by :idx (:per-view cv))]
                (auto-log! (str "    foto " (inc idx) ": " (modal/fmt-number nominal-px)
                                " → " (modal/fmt-number measured-px) " px"
                                (when (>= measured-px nominal-px) "   ← peggio"))))
              ;; Two different refusals wear the same verdict, and sending the
              ;; user to re-click on the wrong one wastes an afternoon. A big
              ;; deviation that fails to generalise IS bad picks. A SMALL one
              ;; that fails to generalise is a plate that is already flat to
              ;; within what the session can measure — nothing to fix, and the
              ;; right answer is to carry on (Vincenzo's ⌀300, once its six blown
              ;; picks were being dropped: 0.24mm, refused, and correctly so).
              (if (< (:worst-mm r) small-deviation-mm)
                (do (auto-log! (str "  Lo scostamento più grande sarebbe "
                                    (modal/fmt-number (:worst-mm r))
                                    " mm, sotto quello che questa sessione sa misurare: "
                                    "il piatto è piano quanto serve. Non c'è niente da "
                                    "correggere — vai avanti."))
                    (set-status-message!
                     (str "Piatto già piano entro " (modal/fmt-number (:worst-mm r))
                          " mm: niente da calibrare. Vai avanti.")))
                (do (auto-log! (str "  Quindi il piatto NON è storto: quello che si "
                                    "misurerebbe è l'errore di qualche click. Guarda i "
                                    "mark con il residuo più alto sulle foto peggiori, "
                                    "riclicca ('p'), e riprova."))
                    (set-status-message!
                     (str "Calibrazione NON adottata: sulle foto tenute fuori peggiora "
                          (:worse cv) " foto su " (+ (:better cv) (:worse cv))
                          ". Il piatto non è storto — sono i click. Dettagli nel pannello.")))))

            :else
            (let [{:keys [marks poses deviation-mm out-of-plane-mm radial-mm
                          tangential-mm worst-mm rms-before rms-after
                          per-view-before per-view view-idx]} r
                  proxy-pose (get-in @session [:proxy-mesh :creation-pose])]
              (apply-plate-calibration! marks)
              ;; adopt the poses the calibration re-solved against the measured
              ;; plate — leaving the old ones would show the corrected marks under
              ;; cameras that were fitted to the wrong ones
              (doseq [[view pose] (map vector views poses)]
                (when pose
                  (swap! session assoc-in [:camera-poses (:idx view)]
                         (bridge/solver-pose->camera pose proxy-pose))))
              (auto-log! (str "=== calibrazione del piatto: " (count views) " foto ==="))
              (auto-log! (str "  riproiezione " (modal/fmt-number rms-before)
                              " → " (modal/fmt-number rms-after) " px"))
              ;; the number that earns the adoption, said before the details
              (when (and cv (not (:error cv)))
                (auto-log! (str "  CONFERMATA su foto tenute fuori dalla misura: "
                                (modal/fmt-number (:nominal-px cv)) " → "
                                (modal/fmt-number (:measured-px cv)) " px, "
                                (:better cv) " migliorate su "
                                (+ (:better cv) (:worse cv)))))
              ;; Per photograph, like the joint refine: an aggregate that moves
              ;; from 2.13 to 1.79 says something happened and refuses to say to
              ;; whom — and on a plate the answer matters, because a warp presents
              ;; differently depending which way each camera looks across it.
              (doseq [[idx b a] (map vector view-idx per-view-before per-view)]
                (when (and b a)
                  (auto-log! (str "    foto " (inc idx) ": " (modal/fmt-number b)
                                  " → " (modal/fmt-number a) " px"))))
              ;; Three components, not two. They are the three ways a mark can be
              ;; in the wrong place and they mean different things — warped,
              ;; wrong radius, wrong angle around the crown — and printing only
              ;; two left the headline number unaccounted for (2026-08-13: m00
              ;; read -1.60 and -1.11 under a worst of 2.01, and the missing
              ;; 0.51mm had no name).
              (let [quiet (count (filter #(<= % 0.2) deviation-mm))]
                (doseq [[i d op rad tan] (map vector (range) deviation-mm out-of-plane-mm
                                              radial-mm tangential-mm)]
                  (when (> d 0.2) ; below this it is click noise, not a plate
                    (auto-log! (str "    " (:label (nth targets i)) ": "
                                    (modal/fmt-number d) " mm — "
                                    (modal/fmt-number op) " fuori piano, "
                                    (modal/fmt-number rad) " in raggio, "
                                    (modal/fmt-number tan) " di lato"))))
                (when (pos? quiet)
                  (auto-log! (str "    (" quiet " mark sotto 0.2 mm: a posto, non elencati)"))))
              (auto-log! (str "  il piatto è ora MISURATO: scostamento massimo "
                              (modal/fmt-number worst-mm) " mm"))
              (save-plate-calibration!
               {:d (:plate-d (:proxy-mesh @session))
                :marks n-marks
                :obj marks
                :worst-mm worst-mm
                :rms-before rms-before
                :rms-after rms-after
                :views (count views)
                :measured-on (.toISOString (js/Date.))})
              (swap! session assoc :plate-calib {:obj marks :worst-mm worst-mm
                                                 :views (count views)})
              (save-acquire-state!)
              (enter-photo! (:current-idx @session))
              (set-status-message!
               (str "Piatto calibrato su " (count views) " foto: scostamento massimo "
                    (modal/fmt-number worst-mm) " mm, riproiezione "
                    (modal/fmt-number rms-before) " → " (modal/fmt-number rms-after) " px")))))))))

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
        ;; A cage's automatic path is SEEDED, not zero-click, and deliberately so:
        ;; the detector finds the discs but a crown cannot say which of its marks
        ;; is mark zero — that is what the picks are for, and four on one ring is
        ;; the whole cost. It works on the photo in front of you rather than the
        ;; whole session, because the picks are per-photo and because applying a
        ;; pose to photo 0 moves the PROXY, which is not something to do to five
        ;; photos at once without looking.
        (cage-proxy?)
        (cage-read-and-place!)

        (nil? (:zero-obj det))
        (set-status-message! "Questo piatto non espone lo zero-indice: usa (registration-plate :d <diametro>) come proxy e riapri.")
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
;; Live capture ('g') — "scatta e registra": a camera in the room is a source of
;; VIEWS, and a view only counts once it is registered.
;;
;; The gesture is one key. What it saves is the round trip that used to sit
;; between wanting a view and having one: shoot, unlock the phone, find the file,
;; copy it into the session folder, rename it into the convention, re-open. With
;; that gone, a view costs about as much as looking, which changes what it is FOR
;; — not another lap of the turntable, but one more angle exactly where the edge
;; you are measuring is poorly seen.
;;
;; Two rules make it honest, and both are about refusing:
;;
;; 1. A frame that does not register never enters the session. It is measured
;;    BEFORE it is written, so a rejected frame leaves nothing behind — not a file
;;    to clean up, not a line in session.json to roll back. Re-shooting costs one
;;    key, so "take another one" is the right answer to a bad frame, and a frame
;;    kept in the hope of rescuing it by hand later is the old economy, from when
;;    photos were expensive.
;; 2. The lens is MEASURED, never assumed. A live frame carries no EXIF, and on
;;    the first frame of a session there is no second view to fit a focal against —
;;    so the plate is asked, in closed form (photogrammetry/plate-focal). Without
;;    that, `solve-pnp` still converges at whatever focal it is handed: it puts the
;;    camera at the wrong distance and reports a residual that looks fine. The
;;    first frame of a session therefore calibrates the camera; the rest reuse it,
;;    and 'R' refines it jointly once there are two.
;; ============================================================

(def ^:private seed-focal-ladder
  "Focals (35mm-equivalent) to TRY when the crown must be identified before the
   lens is known. Identity is nearly focal-blind — the crown and the zero-index are
   coplanar, so their reprojection goes through the homography, which the focal does
   not change (see plate-focal's docstring) — but not perfectly: `assign-marks` also
   LM-refines in pose space and rejects a back-facing twin, and far from the truth
   the refined pose drifts enough that the blob-snap grabs a neighbouring disc.
   Measured on a synthetic plate at a true 28mm: the rungs within roughly ±50%
   identify it correctly, the far ones mis-snap. Ordered by likelihood — a webcam
   or a phone in Continuity Camera sits near 28mm-equivalent — because the search
   stops at the first rung that works."
  [28.0 35.0 22.0 45.0 18.0 60.0])

(def ^:private own-lens-sources
  "Focal provenances that mean 'this belongs to the lens the session is shooting
   with'. Everything a live grab does downstream keys off this, so it is written
   once rather than as a scattered `(= :live …)`:

   - `:live`    measured off the plate by a grabbed frame;
   - `:refined` the joint fit over every registered view — strictly better than any
                single frame, and the reason this is a SET and not one keyword;
   - `:manual`  the user's own slider, which in a live session is their intent;
   - `:remembered` the store's number for THIS camera at THIS delivered size
                (~/.ridley/cameras.json) — measured on this very lens by a past
                session's joint fit, which is exactly what 'belongs to the lens'
                means. Letting a single grabbed frame overwrite it would repeat
                the 2026-08-11 mistake one session later; instead the grab only
                REPORTS its own number, and a real divergence (Center Stage
                moving the lens) shows up as that report disagreeing.

   Excluded: `:default` (a guess belonging to nothing) and `:exif` (a real lens, but
   the phone's STILLS camera, which is not the camera now pointed at the plate)."
  #{:live :refined :manual :remembered})

(defn- live-focal
  "The session's own lens, or nil when it has none yet. Provenance is what decides
   whether the next grab may skip the seed ladder AND — more importantly — whether
   it is allowed to adopt its own single-frame measurement over what the session
   already knows."
  []
  (when (own-lens-sources (:focal-source @session)) (:focal-mm @session)))

(defn- next-grab-name
  "grab-01.jpg, grab-02.jpg, … one past the highest already in the film. Numbered
   rather than timestamped so the filmstrip reads in the order the frames were
   taken, and so a name is predictable enough to talk about."
  []
  (let [n (reduce (fn [best {:keys [file]}]
                    (if-let [[_ d] (re-find #"^grab-(\d+)\." (or file ""))]
                      (max best (js/parseInt d 10))
                      best))
                  0 (:photos @session))]
    (str "grab-" (.padStart (str (inc n)) 2 "0") ".jpg")))

(defn- write-session-json!
  "Rewrite session.json with the current film, keeping every other key the document
   arrived with (:bootstrap, :caliper — the CLI's, and not ours to drop). Returns a
   Promise."
  []
  (let [doc (or (:session-doc @session) {})
        photos (mapv (fn [{:keys [file theta]}] [file theta]) (:photos @session))
        body (js/JSON.stringify (clj->js (assoc doc :photos photos)) nil 1)]
    (swap! session assoc-in [:session-doc :photos] photos)
    (stl/desktop-write-file body (str (:base-dir @session)
                                      (if (str/ends-with? (:base-dir @session) "/") "" "/")
                                      "session.json"))))

(defn- identify-failure-rank
  "How ACTIONABLE a failure is, for picking which one the user hears about when
   several seed focals all fail. Higher = the user can do more about it. nil (no
   failure seen yet) ranks below everything."
  [reason]
  ({:crown-not-found 0
    :too-few-blobs 1
    :not-identified 2
    :too-few-crown 3
    :too-few-snapped 4
    ;; the one with a two-second remedy: turn the plate
    :zero-not-visible 5}
   reason -1))

(defn- definitive-failure?
  "Failures no other seed focal can talk out of. The seed ladder exists to get the
   crown IDENTIFIED; once a rung has done that, whether the zero-index is covered —
   or whether the marks are sharp enough to snap — is a fact about the picture, not
   about the focal, and trying five more focals only makes a refusal slow."
  [reason]
  (contains? #{:zero-not-visible :too-few-snapped :no-zero-index :no-marks} reason))

(defn- identify-crown
  "Find the plate in an already-sampled frame at an assumed `focal-mm`: fit the
   crown, then snap each identified mark onto the real blob under it. Returns
   {:picks {mark-idx px} :corr [{:ci :world :px}] :crown-hits n} or nil.

   `cands` are the detector's blobs, passed in because detection is the expensive
   part and is focal-INDEPENDENT: the seed ladder re-identifies, it never re-detects."
  [{:keys [lum-at size]} focal-mm marks zero-obj det cands blobs rings]
  (let [[iw ih] size
        intrinsics (pcamera/intrinsics-from-fov
                    (pcamera/equiv-focal->hfov-deg focal-mm (/ iw ih)) iw ih)
        ;; The judge scores against EVERY detected blob (a mark just outside the
        ;; top-scored slice still counts as evidence), while the ring search runs on
        ;; the slice — `blobs` — that `rings`' indices refer to. It weighs SIZE as
        ;; well as position: a speck one tenth the area of a disc once stood in for a
        ;; covered zero-index and let a frame register at an unknown rotation.
        judge (match-plate/blob-judge cands)
        res (match-plate/fit-crown-explained blobs marks zero-obj
                                             intrinsics judge
                                             {:disc-r (:disc-r det) :face-normal (:face-normal det)
                                              ;; the rings were found once, from pixels
                                              ;; alone — they don't change with the focal
                                              :rings rings})]
    (if (:reason res)
      res ; carries :message, and a remedy the caller can pass on verbatim
      (let [snap-r (snap-radius-for (:pixels res) marks (:disc-r det))
            picks (into {} (keep (fn [[mi px]]
                                   (some->> (blob/snap-to-blob lum-at px snap-r)
                                            :center (vector mi)))
                                 (:pixels res)))]
        (if (< (count picks) min-plate-picks)
          {:reason :too-few-snapped
           :message (str "the crown is recognised, but only " (count picks) " marks could be "
                         "located precisely enough (at least " min-plate-picks " are needed) — "
                         "the frame is probably blurred or the plate is too small in it")}
          {:picks picks
           :crown-hits (:crown-hits res)
           :snap-radius snap-r
           :corr (vec (for [[ci px] picks]
                        {:ci ci :world (:obj (nth marks ci)) :px px}))})))))

(defn- register-live-frame
  "Everything a grabbed frame must survive to earn a place in the session, in the
   order that makes each step trustworthy:

     detect blobs  →  identify the crown (seed ladder, or the known lens)
                   →  MEASURE the focal off the plate
                   →  re-identify at the measured focal
                   →  solve the pose, and judge it by its residual

   The re-identification is not belt-and-braces: the ladder stops at the first rung
   that identifies the crown, and a rung that is merely close enough can have
   snapped a mark onto its neighbour. Once the true focal is known, the reprojections
   land where the discs actually are, and the picks that come out are the ones the
   pose is entitled to be judged on.

   Pure with respect to the session: reads the proxy, mutates nothing. Returns
   {:ok? true :sol :picks :focal-mm :measured :crown-hits} or
   {:ok? false :message …}."
  [sampler]
  (let [{:keys [lum-at size data]} sampler
        proxy-mesh (:proxy-mesh @session)
        det (bridge/plate-detect proxy-mesh)
        marks (mapv #(select-keys % [:id :obj]) (pnp-targets))
        zero-obj (:zero-obj det)]
    (if (nil? zero-obj)
      {:ok? false :message (if (cage-proxy?)
                             (no-auto-on-cage-msg)
                             (str "This proxy is not a registration plate (no zero-index): "
                                  "live capture registers against the plate's crown."))}
      (let [cands (blob-detect/detect-blobs lum-at size {:rgba data})
            ;; The candidate RINGS, once. Stage 1 reads pixels only, so it is the
            ;; same answer at every seed focal — recomputing it per rung made a
            ;; failed grab take as long as six good ones and left the status line
            ;; saying "registering…" with nothing happening (found live 2026-08-11).
            ;; ONE slice, shared: `rings` holds INDICES into it, so the ring search
            ;; and every identify pass must be looking at the same vector
            blobs (vec (take auto-fit-blobs (mapv :center cands)))
            rings (match-plate/crown-ring-hypotheses blobs {})
            known (live-focal)
            ladder (if known (cons known seed-focal-ladder) seed-focal-ladder)
            ;; Try the seed focals in turn, stopping at the first that identifies the
            ;; crown. When none does, report the most ACTIONABLE failure any of them
            ;; saw rather than the last one: a covered zero-index ("turn the plate")
            ;; is worth more than "no ring found", and a rung that got that far knew
            ;; something the others didn't.
            first-pass (loop [fs ladder best nil]
                         (if (empty? fs)
                           best
                           (let [r (identify-crown sampler (first fs) marks zero-obj det cands blobs rings)]
                             (cond
                               (not (:reason r)) (assoc r :seed (first fs))

                               ;; The ladder exists to fix IDENTIFICATION. Once a rung
                               ;; has identified the crown, another focal cannot change
                               ;; whether the zero-index is physically visible or the
                               ;; marks are sharp enough — so stop, instead of paying
                               ;; five more solves to be told the same thing.
                               (definitive-failure? (:reason r)) r

                               :else
                               (recur (rest fs)
                                      (if (> (identify-failure-rank (:reason r))
                                             (identify-failure-rank (:reason best)))
                                        r best))))))]
        (if (:reason first-pass)
          {:ok? false
           :message (str (:message first-pass) " (" (count cands) " dark blobs in the frame.)")}
          (let [measured (plate-focal/estimate-focal (:corr first-pass) size)
                ;; The SESSION's focal wins when it has one, even though this frame
                ;; measured its own: one lens, one number. Solving this pose at a
                ;; per-frame focal while the session keeps another would put the
                ;; camera at a depth the backdrop's field of view contradicts, and
                ;; the disagreement would show up as everything else being slightly
                ;; wrong. The frame's own measurement is still reported — a lens
                ;; that has genuinely changed (zoom, another camera) should be
                ;; visible, not silently averaged in.
                focal (or known (:focal-mm measured))]
            (if (nil? focal)
              ;; nothing to fall back on: this is the FIRST frame and it cannot see
              ;; the lens. Naming the geometry is the whole value of refusing here.
              {:ok? false
               :message (str "The lens cannot be measured from this frame — "
                             (:message measured)
                             " (the first frame of a session has no other view to fit against.)")}
              (let [[iw ih] size
                    intrinsics (pcamera/intrinsics-from-fov
                                (pcamera/equiv-focal->hfov-deg focal (/ iw ih)) iw ih)
                    ;; re-identify at the MEASURED focal; if that somehow fails, the
                    ;; seed's picks are still a valid (if slightly looser) answer
                    re (identify-crown sampler focal marks zero-obj det cands blobs rings)
                    final (if (:reason re) first-pass re)
                    sol (pnp/solve-pnp (:corr final) intrinsics {})]
                (cond
                  (nil? sol)
                  {:ok? false :message "The pose has no solution from these marks."}

                  (> (:rms-px sol) pnp/accept-rms-px)
                  {:ok? false
                   :message (str "Rejected: reprojection " (.toFixed (:rms-px sol) 1)
                                 "px, over the " pnp/accept-rms-px "px bar. "
                                 "Hold still, or move closer to the plate.")}

                  :else
                  {:ok? true :sol sol :picks (:picks final) :focal-mm focal
                   :measured measured :crown-hits (:crown-hits final)
                   :n (count (:corr final))})))))))))

(defn- accept-live-frame!
  "Write the frame, put it in the film, and apply its pose — in that order, because
   until the JPEG is on disk there is nothing for session.json to point at.
   Returns a Promise resolving when the session is consistent again."
  [^js canvas {:keys [sol picks focal-mm measured crown-hits n]}]
  (let [idx (count (:photos @session))
        file (next-grab-name)
        proxy-pose (get-in @session [:proxy-mesh :creation-pose])
        adopt? (and (:focal-mm measured) (nil? (live-focal)))]
    (-> (camera/canvas->jpeg canvas)
        (.then (fn [blob] (stl/desktop-write-file blob (photo-path file))))
        (.then (fn [_]
                 ;; the lens of the session, measured once off the first live frame;
                 ;; later frames only REPORT theirs, because adopting a new focal
                 ;; silently re-scales every pose already solved at the old one
                 (when adopt?
                   (swap! session assoc :focal-mm (:focal-mm measured) :focal-source :live)
                   (remember-camera-focal! (:focal-mm measured) :live))
                 ;; θ nil = "foto libera": a hand-held frame has no turntable angle,
                 ;; and the channel already knows what to do with one (free-photo?
                 ;; keeps it out of the ring model and its predictions).
                 (swap! session update :photos conj {:file file :theta nil})
                 (apply-auto-solve! idx sol picks proxy-pose)
                 ;; the first frame is the one that MOVES the proxy (it is the anchor):
                 ;; carry the whole system back to the construction turtle, so an
                 ;; object acquired from nothing appears where it is being built and
                 ;; not at the acquisition frame's arbitrary origin. A pure
                 ;; translation — registration is translation-invariant.
                 (when (zero? idx) (reanchor-to-build-pose!))
                 (save-acquire-state!)
                 (write-session-json!)))
        (.then (fn [_]
                 (enter-photo! idx)
                 (auto-log! (str "  " file " registrata ✓  rms "
                                 (.toFixed (:rms-px sol) 1) "px, " n " dischetti, corona "
                                 crown-hits "/12"
                                 (if adopt?
                                   (str " · focale MISURATA dal piatto: "
                                        (.toFixed (:focal-mm measured) 1) "mm-equiv"
                                        (when (:single-constraint? measured) " (un solo vincolo)"))
                                   (when-let [m (:focal-mm measured)]
                                     (str " · questa presa dice " (.toFixed m 1)
                                          "mm, la sessione usa " (.toFixed focal-mm 1) "mm")))))
                 (set-status-message! (str file ": registered, " (.toFixed (:rms-px sol) 1)
                                           "px over " n " marks")))))))

(defn- keep-live-frame-unregistered!
  "The cage's Grab: write the frame and put it in the film as an out-of-ring
   photo (θ nil — the same standing session-json-from-folder gives a cage
   folder's photos), leaving registration to the user's 'p'+'a' on it. The
   plate's grab keeps a frame only if it registers, and on a cage that made
   live capture a dead end: every frame bounced off the plate-shaped automatic
   path, and the advice it bounced with — click 4 discs with 'p', press 'a' —
   presupposes a photo that is IN the film (reported 2026-08-27, a whole
   session of grabs discarded). Returns a Promise resolving when the session
   is consistent again."
  [^js canvas]
  (let [idx (count (:photos @session))
        file (next-grab-name)]
    (-> (camera/canvas->jpeg canvas)
        (.then (fn [blob] (stl/desktop-write-file blob (photo-path file))))
        (.then (fn [_]
                 (swap! session update :photos conj {:file file :theta nil})
                 (write-session-json!)))
        (.then (fn [_]
                 (enter-photo! idx)
                 (auto-log! (str "  " file " tenuta, non registrata (gabbia): "
                                 "clicca 4 dischetti su UN anello con 'p' "
                                 "(lo zero-indice vale doppio), poi premi 'a'."))
                 (set-status-message!
                  (str file ": tenuta, da registrare — 4 dischetti con 'p', poi 'a'.")))))))

(defn- on-grab!
  "'g' / the Grab button. Plate: one frame, measured, and kept only if it
   registers. Cage: the frame is kept UNREGISTERED — there is no on-the-spot
   automatic for a cage yet (auto-read stands at 2/8 with tens of seconds per
   refusal, see dev-docs/HANDOVER-cage-zero-click.md), and a frame that is not
   in the film cannot be hand-registered at all."
  []
  (cond
    (not (camera/active?))
    (set-status-message! "No camera open — press Camera first.")

    (not (plate-proxy?))
    (set-status-message! "Live capture needs the registration plate as the proxy.")

    :else
    (if-let [{:keys [^js canvas size]} (camera/grab-frame)]
      (let [[w h] size]
        (auto-log! (str "=== presa dal vivo (" w "×" h ") ==="))
        ;; the lens these grabs come from, remembered by the session (and
        ;; persisted with it): the 'R' that finally measures the focal may run
        ;; long after the camera is closed, and a measurement that cannot say
        ;; which camera it belongs to cannot be filed
        (when-let [k (camera-lens-key (:camera-info @session))]
          (swap! session assoc :grab-camera k))
        (if (cage-proxy?)
          ;; a cage frame cannot register on the spot (the automatic path is
          ;; plate-shaped): keep it, and let 'p'+'a' register it in place
          (-> (keep-live-frame-unregistered! canvas)
              (.catch (fn [err]
                        (auto-log! (str "  errore: " err))
                        (set-status-message! (str "Grab failed: " err)))))
          (let [sampler (backdrop/sampler-of canvas w h)]
            (set-status-message! "Grabbed — registering… (detail in the REPL)")
            ;; hand the browser a frame first, so the status line paints before
            ;; the (seconds-long) detection blocks the thread
            (-> (yield-frame)
                (.then (fn [_] (register-live-frame sampler)))
                (.then (fn [outcome]
                         (if (:ok? outcome)
                           (accept-live-frame! canvas outcome)
                           (do (auto-log! (str "  scartata: " (:message outcome)))
                               (set-status-message! (str "Not kept — " (:message outcome)))
                               (update-panel!)))))
                (.catch (fn [err]
                          (auto-log! (str "  errore: " err))
                          (set-status-message! (str "Grab failed: " err))))))))
      (set-status-message! "The camera has not delivered a frame yet."))))

(def ^:private photo-indexed-keys
  "Every session map keyed by PHOTO INDEX. Removing a view has to renumber all of
   them together, so they are listed once here rather than remembered at each call
   site — a map left out would silently keep pointing at the wrong photo, which is
   the kind of mistake that shows up three steps later as geometry that makes no
   sense."
  [:camera-poses :acquire-results :pnp-picks :pnp-residuals :pnp-outliers
   :pnp-occluded :pnp-batch :marker-picks :cage-mounting-obs :cage-face-choice])

(defn- drop-index
  "Remove key `gone` from an index-keyed map and shift every higher key down one."
  [m gone]
  (into {} (keep (fn [[i v]]
                   (cond (= i gone) nil
                         (> i gone) [(dec i) v]
                         :else [i v])))
        m))

(defn- delete-view!
  "Remove view `idx` from the session: its JPEG from the folder, its entry from the
   film, and everything keyed to it — renumbering the views above it.

   Worth having because a live capture loop MAKES bad views. When a photo cost a
   trip to the phone and back you lived with the ones you had; when it costs one
   key you take another, and the folder fills with attempts. Without a way to drop
   one, every bad frame stays in the joint fit forever, which is the opposite of
   what cheap views are for.

   The file goes too — leaving the JPEG behind would put session.json and the
   folder out of step, and the next thing to read the folder would disagree with
   the session about what was shot."
  [idx]
  (let [{:keys [file]} (nth (:photos @session) idx nil)]
    (when file
      (-> (stl/desktop-delete-file (photo-path file))
          ;; a JPEG already gone is not a reason to keep the view
          (.catch (fn [_] nil))
          (.then
           (fn [_]
             (swap! session
                    (fn [s]
                      (let [s (update s :photos (fn [ps] (vec (concat (subvec ps 0 idx)
                                                                      (subvec ps (inc idx))))))
                            s (reduce (fn [acc k] (update acc k #(drop-index (or % {}) idx)))
                                      s photo-indexed-keys)
                            n (count (:photos s))]
                        (assoc s :current-idx (max 0 (min (dec n) (:current-idx s)))))))
             (save-acquire-state!)
             (write-session-json!)))
          (.then (fn [_]
                   (auto-log! (str "  " file " eliminata dalla sessione"))
                   (if (seq (:photos @session))
                     (enter-photo! (:current-idx @session))
                     (update-panel!))
                   (set-status-message! (str file " deleted — " (count (:photos @session))
                                             " views left"))))
          (.catch (fn [err]
                    (set-status-message! (str "Could not delete " file ": " err))))))))

;; ============================================================
;; Per-camera focal memory — ~/.ridley/cameras.json
;;
;; A grabbed frame has no EXIF, so a grab session starts at the 48mm default,
;; and a wrong focal does not present itself as a wrong focal: it registers
;; cleanly with the camera at the wrong distance, MUTE (the default-48 on the
;; 28mm Continuity lens ate four evenings across 28/8–2/9). The lens of a
;; camera the user owns is a constant worth keeping, like the plate's
;; calibration — measured once, proposed to every later session.
;;
;; Keyed by label AND delivered size, because a lens number is only worth the
;; pipeline it was measured on: the 44 "measured" for Continuity's 4032px
;; stills never held for its 1920×1440 grabs (different crop), and that
;; transplant is precisely how battiscopa3 opened wrong. Same phone, two keys,
;; two numbers — correct, not redundant. (Center Stage varies the crop live,
;; which no key can absorb: it stays OFF in grab sessions.)
;; ============================================================

(def ^:private camera-store-path
  "One JSON map for every camera the user has measured: key → {focal-mm,
   source, updated}. Keys are free-form strings (labels have spaces), so the
   file is read WITHOUT keywordizing."
  "~/.ridley/cameras.json")

(defn- camera-lens-key
  "The identity a measured focal belongs to: «label @ w×h». nil when the label
   is missing (a store entry under 'camera' would collide across devices)."
  [{:keys [label size]}]
  (when (and label (seq label) (not= label "camera") (= 2 (count size)))
    (str label " @ " (first size) "×" (second size))))

(defn- read-camera-store
  "Promise of the store's map, {} when absent/unreadable — a missing store is
   the normal first-run case, never an error."
  []
  (-> (stl/desktop-read-file (stl/expand-home camera-store-path))
      (.then (fn [text] (js->clj (js/JSON.parse text))))
      (.catch (fn [_] {}))))

(defn- remember-camera-focal!
  "File the session's measured lens under the camera it was grabbed with —
   :grab-camera, stamped at grab time and persisted with the session, so the
   'R' that finally measures the lens files it even if the camera has been
   closed (or the session reopened) in between. Best-effort, like the plate
   store: the session's own state is what makes this run reproducible."
  [mm source]
  (when-let [k (:grab-camera @session)]
    (swap! session assoc :remembered-focal-mm mm)
    (-> (read-camera-store)
        (.then (fn [store]
                 ;; an unchanged number is not news: a no-op R re-filed (and
                 ;; re-announced) the same lens three times in one sitting
                 (when-not (some-> (get-in store [k "focal-mm"])
                                   (- mm) js/Math.abs (< 0.05))
                   (-> (stl/desktop-write-file
                        (js/JSON.stringify
                         (clj->js (assoc store k {"focal-mm" mm
                                                  "source" (name source)
                                                  "updated" (.slice (.toISOString (js/Date.)) 0 10)}))
                         nil 2)
                        (stl/expand-home camera-store-path))
                       (.then (fn [_]
                                (auto-log! (str "  lente annotata per «" k "»: " (.toFixed mm 2)
                                                "mm (" (name source) ") — le prossime sessioni con "
                                                "questa camera partono da qui, non dal default"))))))))
        (.catch (fn [err]
                  (js/console.warn "edit-acquire: couldn't save the camera focal" err))))))

(defn- propose-remembered-focal!
  "On camera open: if the store knows this camera at this size, give the
   session that lens (source :remembered) — unless the session already owns
   one (:live/:refined/:manual/:remembered), in which case a real disagreement
   is REPORTED, never adopted: the number on file was measured, but so was the
   session's, and silently replacing the nearer one is how wrong focals stay
   mute. Async and best-effort."
  [info]
  (when-let [k (camera-lens-key info)]
    (-> (read-camera-store)
        (.then (fn [store]
                 (when-let [mm (get-in store [k "focal-mm"])]
                   ;; stash for R's multi-start regardless of adoption
                   (swap! session assoc :remembered-focal-mm mm)
                   (let [updated (get-in store [k "updated"])]
                     (if (live-focal)
                       (when (> (js/Math.abs (- mm (:focal-mm @session))) 0.5)
                         (auto-log! (str "  per «" k "» ho in memoria " (.toFixed mm 1)
                                         "mm (del " updated "), la sessione usa "
                                         (.toFixed (:focal-mm @session) 1)
                                         "mm — se i residui restano alti, 'R' fa da giudice")))
                       (do (swap! session assoc :focal-mm mm :focal-source :remembered)
                           (auto-log! (str "  focale ricordata per «" k "»: " (.toFixed mm 1)
                                           "mm (misurata il " updated ") — 'R' la rimisura"))
                           (report-focal!)
                           (update-panel!)))))))
        (.catch (fn [_] nil)))))

(defn- mount-camera-preview!
  "Put the live preview in the corner of the viewport — you frame by looking at the
   OBJECT, so the preview has to be the thing you glance at, not the thing you stare
   at. Idempotent."
  []
  (when-let [^js host (.getElementById js/document "viewport-panel")]
    (when-let [^js v (camera/video-el)]
      (when-not (.-parentNode v) (.appendChild host v)))))

(defn- refresh-cameras!
  "Re-read the list of connected cameras into the panel. Cheap, and worth doing
   on every change: a phone that offers itself over Continuity appears WHILE the
   session is open, and the user goes looking for it in the picker the moment
   they have woken it."
  []
  (-> (camera/list-cameras)
      (.then (fn [ds]
               (when @session
                 (swap! session assoc :camera-devices ds)
                 (update-panel!))))))

(defn- watch-cameras!
  "Keep the picker honest for as long as the session is open."
  []
  (camera/watch-devices! refresh-cameras!)
  (refresh-cameras!))

(defn- start-camera!
  "Open a camera (the given device, or the default) and show its preview. Also
   refreshes the device list — labels only become real once permission has been
   granted, so the picker is worth rebuilding after every successful open."
  [device-id]
  (-> (camera/start! device-id)
      (.then (fn [info]
               (mount-camera-preview!)
               (swap! session assoc :camera-info info)
               (set-status-message! (str "Camera: " (:label info) " · "
                                         (first (:size info)) "×" (second (:size info))))
               ;; a camera the store has already measured brings its lens with
               ;; it — the cure for the mute default-48 that ate four evenings
               (propose-remembered-focal! info)
               (refresh-cameras!)))
      (.catch (fn [err]
                (set-status-message! (str "Camera not opened: " (.-message err)))
                (update-panel!)))))

(defn- stop-camera! []
  (camera/stop!)
  (swap! session dissoc :camera-info)
  (set-status-message! "Camera closed."))

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
  {[0 1] "+X side" [0 -1] "−X side"
   [1 1] "Top"     [1 -1] "Bottom"
   [2 1] "Front"   [2 -1] "Back"})

(def ^:private retrace-face-colors
  "[axis sign] → colour, shared by the active-face highlight quad and its button
   so which plane is declared is unmistakable at a glance."
  {[0 1] 0x5fd35f [0 -1] 0xb06cf0
   [1 1] 0x38c3d6 [1 -1] 0x5b8def
   [2 1] 0xf4d03f [2 -1] 0xf06fb0})

(def ^:private retrace-face-order [[1 1] [1 -1] [2 1] [2 -1] [0 1] [0 -1]])

(defn- axis-unit [a] (assoc [0.0 0.0 0.0] a 1.0))

(declare obj-dir->world)

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

(defn- preset-plane-pose
  "The pose of the proxy bounding-box face `axis`/`sign` — the six presets. Kept
   because for a BOX proxy they are the part's own faces and remain the fastest
   way to say 'this one'."
  [axis sign]
  (let [half (* 0.5 (nth (retrace-dims) axis))
        a2 (last (remove #{axis} [0 1 2]))]
    {:position (assoc [0.0 0.0 0.0] axis (* sign half))
     :heading (m/v* (axis-unit axis) (double sign))
     :up (axis-unit a2)}))

(defn- cage-ring-presets
  "For a CAGE proxy: one preset per ring, each a pose at the cage's CENTRE with
   the ring's own axis as normal. Returns [{:label :pose} …], or nil for a proxy
   that is not a cage.

   Vincenzo's proposal (2026-08-21), and it is the right answer to a problem the
   occlusion cue failed to solve: placing a plane freely in space from a single
   photograph leaves you with no idea where it is. These give a KNOWN starting
   point — and a useful one, because the part being measured sits at the centre
   of the three rings by construction, so the centre is where its features are.

   Labelled by ring SIZE rather than by axis: :x/:y/:z are the model's names for
   the three, but what the eye can tell apart on the bench is big, medium and
   small."
  []
  (let [mesh (:proxy-mesh @session)]
    (when-let [rings (seq (:rings mesh))]
      (let [labels ["Big ring" "Medium ring" "Small ring"]]
        (vec (map-indexed
              (fn [i {:keys [axis]}]
                (let [n (axis-unit (case axis :x 0 :y 1 :z 2))
                      ;; any direction in the plane will do for :up; take the next
                      ;; axis round, so the three presets are mutually consistent
                      u (axis-unit (case axis :x 1 :y 2 :z 0))]
                  {:label (nth labels i (str "Ring " (name axis)))
                   :pose {:position [0.0 0.0 0.0] :heading n :up u}}))
              rings))))))

(defn- plane-pose
  "A tracing plane's full POSE in the object frame — position, heading (the
   outward normal) and up (the in-plane reference direction).

   A plane used to be `{:axis :sign :offset}`: one of the proxy bounding box's
   six faces, plus a shift along its normal. That was right while the proxy WAS
   the part, because then the six faces were the part's own faces. The
   registration CAGE ended that: the proxy is now the reference AROUND the part,
   its bounding box is a 176mm cube enclosing a 40mm object, and 'which face are
   you tracing on' has no answer — 'facce di cosa?' (Vincenzo, 2026-08-20). An
   arbitrary plane needs three degrees of orientation; a shift along a fixed
   normal offers none.

   So the plane is a pose, `:base`, freely placed with the gizmo. The six faces
   survive as presets that SET that pose, and `:offset` still slides it along its
   own normal — the one part of the old gesture that generalises unchanged.
   Legacy specs (and old session files) with only :axis/:sign are read through
   `preset-plane-pose`, so nothing already recorded is lost."
  [{:keys [base axis sign offset] :as spec}]
  (let [p (or base (preset-plane-pose (or axis 1) (or sign 1)))]
    (if (and offset (not (zero? offset)))
      (turtle/f p offset)
      p)))

(defn- plane-of
  "The plane in the OBJECT frame as {:point :normal} — what a ray is intersected
   against. Shared by the retrace and the named-mark gesture."
  [spec]
  (let [{:keys [position heading]} (plane-pose spec)]
    {:point position :normal heading}))

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
                     (when-let [m (re-matches #"(?:ricalco|ancora)-(\d+)" (or name ""))]
                       (js/parseInt (second m) 10)))
                   (ricalchi))]
    ;; "ancora", not "ricalco": what this gesture produces is an ANCHOR — a pose
    ;; you place — and the outline that used to justify the old name is now drawn
    ;; afterwards with edit-path-2d on that anchor's plane (Vincenzo, 2026-08-21).
    ;; The old names still parse, so a session recorded before this keeps
    ;; numbering from where it left off instead of colliding.
    (str "ancora-" (inc (reduce max 0 nums)))))

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

(defn- active-plane-pose
  "The plane as it should be DRAWN and clicked against right now: the trial pose
   while a gizmo drag is in flight, the committed one otherwise. The trial exists
   so the plane follows the handle continuously instead of jumping at release
   (Vincenzo, 2026-08-21) — you are aiming a plane at a surface in a photograph,
   and aiming without feedback is guessing."
  []
  (or (:trial-plane @session) (plane-pose (active-plane-spec))))

(defn- retrace-plane []
  (let [{:keys [position heading]} (active-plane-pose)]
    {:point position :normal heading}))

(defn- face-quad
  "A translucent coloured quad ON the face declared by `spec` — the plane
   indicator, so it's obvious in 3D which face you're tracing/marking (not just
   the panel text). Coloured by retrace-face-colors, matching the pressed face
   button."
  [{:keys [axis sign] :as spec} pose]
  (let [{:keys [position heading up]} pose
        ;; the quad is built in the PLANE's own frame now, not from two box axes:
        ;; an arbitrary plane has no box axes to borrow
        v up
        u (m/normalize (m/cross heading v))
        half (* 0.5 (apply max (retrace-dims)))
        mk (fn [s1 s2] (m/v+ position (m/v+ (m/v* u (* s1 half)) (m/v* v (* s2 half)))))
        proxy-pose (get-in @session [:proxy-mesh :creation-pose])
        [w0 w1 w2 w3] (mapv #(bridge/local->world proxy-pose %)
                            [(mk -1 -1) (mk 1 -1) (mk 1 1) (mk -1 1)])]
    {:type :mesh
     :data {:vertices [w0 w1 w2 w3]
            :faces [[0 1 2] [0 2 3]]
            ;; The sheet reads against two very different backgrounds, so it is
            ;; drawn differently for each. Over the PHOTO alone it must stay faint
            ;; and tinted, or it hides what is being measured. Against the solid
            ;; cage it is competing with an opaque dark surface, and at 0.3 the
            ;; occlusion it exists to show is "appena appena" visible (Vincenzo,
            ;; 2026-08-21) — so it goes lighter and much more opaque, and the edge
            ;; where a ring cuts across it becomes obvious.
            ;;
            ;; A freely-placed plane matches no face button either way, so it gets
            ;; a neutral colour rather than borrowing the last preset's and
            ;; implying it is still on that face.
            :material (if (:hide-proxy? @session)
                        {:color (or (retrace-face-colors [axis sign]) 0xbbbbbb)
                         :opacity 0.3 :double-sided true}
                        {:color (or (retrace-face-colors [axis sign]) 0xf2f2f2)
                         :opacity 0.75 :double-sided true})}}))

(defn- active-face-quad [] (face-quad (active-plane-spec) (active-plane-pose)))

(defn- retrace-solver-pose []
  (bridge/editor->solver-pose (current-camera-pose)
                              (get-in @session [:proxy-mesh :creation-pose])))

(def ^:private retrace-dot-radius
  "World-mm radius of a traced vertex marker — small (the connecting line carries
   the shape; the dot just pins each click), and translucent, so the dots read as
   precise marks over the photo rather than the solid balls of the first cut
   (Vincenzo 2026-07-23: 'i pallini gialli sono enormi')."
  0.9)

(defn- plane-origin-marker
  "The plane's ORIGIN, drawn as a small solid ball — the point that becomes the
   emitted mark's :position, and therefore the thing being aimed when the gizmo
   is dragged. Without it the quad shows an orientation and hides the one number
   the gesture exists to set."
  []
  (let [proxy-pose (get-in @session [:proxy-mesh :creation-pose])
        c (bridge/local->world proxy-pose (:position (active-plane-pose)))]
    ;; a :dots item, the same primitive the traced vertices use — bigger, white
    ;; and opaque, so it reads as "the origin" and not as one more clicked point
    {:type :dots
     :data [{:pos c :radius (* 2.6 retrace-dot-radius) :color 0xffffff :opacity 1.0}]
     :on-top true}))

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
        ;; A plane is FRONT-FACING when its outward normal points toward the
        ;; camera. Read that normal from the plane's POSE, not from :axis/:sign —
        ;; a freely placed plane has no face, and reaching for one crashed the
        ;; whole session open on reopen (2026-08-21: `Index argument to nth must
        ;; be a number`, raised asynchronously and therefore invisible).
        front? (fn [plane]
                 (let [n (obj-dir->world proxy-pose (:heading (plane-pose plane)))]
                   (neg? (m/dot n heading))))]
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
                    ;; The vertex dots belonged to the tracing gesture. This mode
                    ;; places an ANCHOR — a pose — and the outline is drawn later
                    ;; with edit-path-2d on that anchor's plane, so the dots are
                    ;; clutter over the one thing that matters, the white origin
                    ;; ball (Vincenzo, 2026-08-21). Points already recorded keep
                    ;; their line, so nothing traced is lost from view.
                    false
                    (conj {:type :dots :data (mapv (fn [w] {:pos w :radius retrace-dot-radius
                                                            :color trace-color :opacity 0.75}) wpts)}))))))
          (range) (ricalchi)))))

(defn- retrace-preview-items
  "In :retrace the box is never drawn — only the coloured active-face quad (the
   plane indicator, so which face you're tracing is obvious) plus the trace on
   top of it."
  []
  ;; The proxy is drawn SOLID here. Placing a plane in space from a single
  ;; photograph is guessing at depth, and the cheapest depth cue is occlusion: a
  ;; plane that disappears BEHIND a ring says where it is better than any number.
  ;;
  ;; It was a wireframe first, and that was a design mistake of mine: a wireframe
  ;; is thin lines, and lines cannot occlude a plane — you see it through the gaps,
  ;; which are nearly everything. Only a solid occluder occludes. It costs the
  ;; photo underneath, and that cost is accepted deliberately (Vincenzo,
  ;; 2026-08-21: «non importa se copre la foto, tanto la si può nascondere»),
  ;; because 'v' takes the cage away the moment you need to read the photo.
  ;;
  ;; The quad is deliberately NOT on-top: drawn over everything it would never be
  ;; occluded, and the cue this exists for would be gone.
  (into (cond-> [(active-face-quad) (plane-origin-marker)]
          (not (:hide-proxy? @session))
          (conj {:type :mesh :data (:proxy-mesh @session)}))
        (trace-items)))

(declare redraw-retrace!)

(defn- toggle-proxy!
  "Show/hide the SOLID proxy in the main (gizmo) view so the photo underneath is
   readable while registering. Available in :gizmo, in :retrace — where the
   proxy is the depth cue for placing a plane, and occasionally the thing
   standing in front of what you are trying to see — and, since the cage's glue
   tabs became SOLID boxes, in :pnp too: the see-through wireframe never needed
   hiding, but the tabs cover the very discs being clicked, and they sat there
   fixed with 'v' dead (Vincenzo 2026-09-01). The state persists across modes.

   Mode-aware on purpose: in :retrace the gizmo belongs to the PLANE, so hiding
   the proxy must not close it (you would lose the handles you are working with)
   and the preview to rebuild is the anchor's; in :pnp there is no gizmo at all
   — installing one here would drop handles over the picking — and the preview
   to rebuild is its own."
  []
  (swap! session update :hide-proxy? not)
  (case (:mode @session)
    :retrace (redraw-retrace!)
    ;; the DOM overlay carries the predicted NAMES too — rebuild it, or they
    ;; linger over the naked photo the toggle just produced
    :pnp (do (redraw-pnp-preview!) (redraw-overlay-dots!))
    (do
      ;; Hide the gizmo together with the solid proxy (install-gizmo! now no-ops
      ;; while hidden); re-install it when the proxy comes back.
      (if (:hide-proxy? @session)
        (gizmo/close!)
        (install-gizmo! (:current-idx @session)))
      (viewport/show-preview! (proxy-preview-items))))
  (update-panel!))

(defn- redraw-retrace! []
  (viewport/show-preview! (retrace-preview-items))
  (redraw-mark-names!))

;; loupe reuse (same magnifier as PnP — the camera is locked, so a crop under
;; the cursor stays on its photo feature); the '-pnp-' state keys are shared
(declare retrace-on-pan)

(defn- swallow-context-menu [^js e]
  (when (and @session (> (or (:view-zoom @session) 1.0) 1.0))
    (.preventDefault e)))

(defn- retrace-on-pointermove [^js e]
  ;; No magnifier at all here. It was a tracing aid — it enlarged the pixels you
  ;; were about to click — and this mode has stopped being about clicking pixels:
  ;; it places an ANCHOR (Vincenzo, 2026-08-21). Appearing over some parts of the
  ;; image and not others, it now reads as a glitch rather than a tool.
  (retrace-on-pan e)
  (when (and @session (= :retrace (:mode @session)))
    (hide-pnp-loupe!)))

(def ^:private view-zoom-max 8.0)

(defn- apply-view-zoom! []
  (let [{:keys [view-zoom view-cx view-cy]} @session]
    (viewport/set-view-window! (or view-zoom 1.0) (or view-cx 0.5) (or view-cy 0.5))))

(defn- reset-view-zoom!
  "Back to the whole frame. Called on every photo change: a window that made
   sense on one shot frames nothing on the next."
  []
  (swap! session assoc :view-zoom 1.0 :view-cx 0.5 :view-cy 0.5)
  (apply-view-zoom!))

(defn- zoom-view-at!
  "Wheel zoom about the POINTER, so the detail under the cursor stays under it —
   the behaviour every map and photo viewer has, and the reason the wheel was
   worth taking from the loupe (Vincenzo, 2026-08-21: 'usiamo pure la rotella')."
  [^js e dir]
  (let [^js canvas (viewport/get-canvas)
        rect (.getBoundingClientRect canvas)
        w (.-width rect) h (.-height rect)
        ;; pointer in 0..1 of the canvas
        px (/ (- (.-clientX e) (.-left rect)) (max 1.0 w))
        py (/ (- (.-clientY e) (.-top rect)) (max 1.0 h))
        {:keys [view-zoom view-cx view-cy]} @session
        z0 (or view-zoom 1.0)
        cx0 (or view-cx 0.5) cy0 (or view-cy 0.5)
        z1 (-> (* z0 (Math/pow 1.15 dir)) (max 1.0) (min view-zoom-max))
        ;; the frame point currently under the pointer, in 0..1 of the FULL frame
        fx (+ cx0 (/ (- px 0.5) z0))
        fy (+ cy0 (/ (- py 0.5) z0))
        ;; keep it there at the new zoom
        cx1 (- fx (/ (- px 0.5) z1))
        cy1 (- fy (/ (- py 0.5) z1))
        clamp (fn [c z] (let [half (/ 0.5 z)] (-> c (max half) (min (- 1.0 half)))))]
    (swap! session assoc :view-zoom z1
           :view-cx (clamp cx1 z1) :view-cy (clamp cy1 z1))
    (apply-view-zoom!)))

(defn- retrace-on-wheel
  "The wheel zooms the PHOTOGRAPH now, not the magnifier. Tracing an outline on a
   3024×4032 photo shown at canvas size means aiming at features a couple of
   pixels across; the loupe showed them but you still had to click in the
   original scale. With a real zoom the loupe matters much less, which is what
   made the trade worth it."
  [^js e]
  (when (and @session (#{:retrace :pnp} (:mode @session)))
    (.preventDefault e) (.stopPropagation e)
    (zoom-view-at! e (if (pos? (.-deltaY e)) -1.0 1.0))))

(defn- retrace-on-pan
  "Right-button drag pans the zoomed window. Only meaningful while zoomed in, and
   the right button is free — the left one is placing points."
  [^js e]
  (when (and @session (> (or (:view-zoom @session) 1.0) 1.0)
             (pos? (bit-and (.-buttons e) 2)))
    (.preventDefault e) (.stopPropagation e)
    (let [^js canvas (viewport/get-canvas)
          rect (.getBoundingClientRect canvas)
          z (or (:view-zoom @session) 1.0)
          dx (/ (.-movementX e) (max 1.0 (.-width rect)) z)
          dy (/ (.-movementY e) (max 1.0 (.-height rect)) z)
          clamp (fn [c] (let [half (/ 0.5 z)] (-> c (max half) (min (- 1.0 half)))))]
      (swap! session update :view-cx (fn [c] (clamp (- (or c 0.5) dx))))
      (swap! session update :view-cy (fn [c] (clamp (- (or c 0.5) dy))))
      (apply-view-zoom!))))

(defn- retrace-on-pointerdown [^js e]
  ;; The gizmo gets first refusal: this listener is on the CAPTURE phase and the
  ;; gizmo's is on the bubble phase, so without this test every press is consumed
  ;; here and the plane can never be dragged — the handles draw, hover, and do
  ;; nothing.
  (when (and @session (= :retrace (:mode @session)) (zero? (.-button e))
             (not (gizmo/over-handle? e)))
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
              (set-status-message! "That click lands too far from the plane — use a photo that shows it more face-on"))
            (set-status-message! "That click does not meet the declared plane")))))))

(defn- teardown-retrace-listeners! []
  (let [^js canvas (viewport/get-canvas)]
    (.removeEventListener canvas "pointerdown" retrace-on-pointerdown true)
    (.removeEventListener canvas "pointermove" retrace-on-pointermove true)
    (.removeEventListener canvas "pointerleave" hide-pnp-loupe! true)
    (.removeEventListener canvas "wheel" retrace-on-wheel true)
    (.removeEventListener canvas "contextmenu" swallow-context-menu true))
  (remove-pnp-loupe!))

(defn- obj-pose->world
  "A pose expressed in the proxy's object frame, in world coordinates."
  [obj-pose]
  (let [pp (get-in @session [:proxy-mesh :creation-pose])]
    {:position (bridge/local->world pp (:position obj-pose))
     :heading (obj-dir->world pp (:heading obj-pose))
     :up (obj-dir->world pp (:up obj-pose))}))

(defn- world-pose->obj
  "The inverse of `obj-pose->world`."
  [w-pose]
  (let [pp (get-in @session [:proxy-mesh :creation-pose])]
    {:position (bridge/world->local pp (:position w-pose))
     :heading (m/normalize (bridge/world->local-dir pp (:heading w-pose)))
     :up (m/normalize (bridge/world->local-dir pp (:up w-pose)))}))

(defn- on-retrace-gizmo-commit!
  "A gizmo gesture on the tracing PLANE. The gesture is applied in WORLD space —
   where the handles are — and the result converted back to the object frame,
   rather than trying to rotate the delta into object coordinates by hand.

   Committing FOLDS the offset slider into the base pose and zeroes it, so the
   plane never has two owners: after a drag the slider slides from wherever the
   drag left the plane. The points are NOT cleared — unlike changing face, moving
   the plane a little is usually a correction to a trace already begun, and
   throwing it away would punish the gesture that this whole change exists to
   make possible."
  [cmd-type value]
  (let [spec (active-plane-spec)
        ;; deliberately plane-pose, not active-plane-pose: the trial already IS
        ;; this gesture applied, so folding it in again would double every drag
        w (obj-pose->world (plane-pose spec))
        moved (case cmd-type
                :f (turtle/f w value)
                :rt (turtle/move-right w value)
                :u (turtle/move-up w value)
                :th (turtle/th w value)
                :tv (turtle/tv w value)
                :tr (turtle/tr w value))]
    ;; MERGE, never replace: :axis/:sign are the preset the panel highlights, and
    ;; dropping them leaves a plane that `canonicalize-orientation!` then tries to
    ;; remap through (assoc [0 0 0] nil …) — which kills the whole session open,
    ;; asynchronously and silently (2026-08-21).
    (swap! session update-in (active-r-path :plane)
           merge {:base (world-pose->obj moved) :offset 0.0})
    (redraw-retrace!)
    ;; and the gizmo FOLLOWS. It is built at a pose and has no mutator, so
    ;; leaving it where it was means the next gesture is computed against a stale
    ;; basis: grab a ring and the plane swings somewhere unrelated to the handle
    ;; (Vincenzo, 2026-08-21: "giri un anello e il piano va in direzioni che non
    ;; c'entrano"). The first gesture looks right, every one after it is wrong,
    ;; which is exactly what made it read as the mapping being scrambled.
    (refresh-retrace-gizmo!)
    (save-acquire-state!)
    (update-panel!)))

(defn- on-retrace-gizmo-drag!
  "Every pointer-move of a live drag. `value` is already the TOTAL since the drag
   began, so the trial is computed from the (untouched) committed pose each time
   and never accumulated — the same one-shot semantics on-commit has."
  [{:keys [cmd-type value]}]
  (let [base (plane-pose (active-plane-spec))
        w (obj-pose->world base)
        moved (case cmd-type
                :f (turtle/f w value)
                :rt (turtle/move-right w value)
                :u (turtle/move-up w value)
                :th (turtle/th w value)
                :tv (turtle/tv w value)
                :tr (turtle/tr w value)
                w)]
    (swap! session assoc :trial-plane (world-pose->obj moved))
    (redraw-retrace!)))

(defn- on-retrace-gizmo-drag-end! []
  (swap! session dissoc :trial-plane)
  (redraw-retrace!))

(defn- install-retrace-gizmo!
  "The tracing plane's own gizmo: translate + rotate, no scale (a plane has no
   size), and :nudge-mesh? false so dragging moves the PLANE and not the photo's
   proxy — the same choice edit-mesh-split makes for its cut plane, and for the
   same reason: seeing the object move when only the plane is changing reads as
   the object moving."
  []
  (gizmo/enter! (obj-pose->world (plane-pose (active-plane-spec)))
                {:handles #{:translate :rotate} :nudge-mesh? false}
                {:on-commit on-retrace-gizmo-commit!
                 :on-drag on-retrace-gizmo-drag!
                 :on-drag-end on-retrace-gizmo-drag-end!}))

(defn- start-retrace! []
  (when (and @session (not= :retrace (:mode @session)))
    (gizmo/close!)
    (swap! session assoc :mode :retrace)
    (ensure-active-ricalco!)
    (install-retrace-gizmo!)
    (let [^js canvas (viewport/get-canvas)]
      (.addEventListener canvas "pointerdown" retrace-on-pointerdown true)
      (.addEventListener canvas "pointermove" retrace-on-pointermove true)
      (.addEventListener canvas "pointerleave" hide-pnp-loupe! true)
      (.addEventListener canvas "wheel" retrace-on-wheel #js {:capture true :passive false})
      (.addEventListener canvas "contextmenu" swallow-context-menu true))
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
  (conj (into [(let [sp (:mark-plane @session)] (face-quad sp (plane-pose sp)))] (trace-items))
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
              (set-status-message! "That click lands too far from the plane — use a photo that shows it more face-on"))
            (set-status-message! "That click does not meet the declared plane")))))))

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

(declare install-retrace-gizmo!)

(defn- refresh-retrace-gizmo!
  "Put the gizmo back on the plane after something else moved it (a face preset,
   the offset slider) — it is built at a pose and has no mutator."
  []
  (when (= :retrace (:mode @session))
    (gizmo/close!)
    (install-retrace-gizmo!)))

(defn- set-ring-preset!
  "Put the active anchor's plane at the cage centre, oriented like ring `i`.
   Unlike changing FACE this keeps any points already traced: the plane is being
   aimed, not redeclared."
  [i]
  (when-let [presets (cage-ring-presets)]
    (when-let [{:keys [label pose]} (nth presets i nil)]
      (swap! session update-in (active-r-path :plane)
             merge {:base pose :offset 0.0})
      (redraw-retrace!)
      (refresh-retrace-gizmo!)
      (save-acquire-state!)
      (set-status-message!
       (str "anchor at the cage centre, in the " (str/lower-case label) "'s plane"
            " — move it from here"))
      (update-panel!))))

(defn- set-retrace-face!
  "Pick the ACTIVE ricalco's declared face. A ricalco belongs to ONE plane, so
   switching its face clears ITS points (they'd be meaningless there); the offset
   carries over. Other ricalchi are untouched."
  [axis sign]
  (let [had (seq (get-in @session (active-r-path :points)))]
    (swap! session update-in (active-r-path)
           (fn [rt] (assoc rt
                           :plane {:axis axis :sign sign :offset 0.0
                                   :base (preset-plane-pose axis sign)}
                           :points [])))
    (redraw-retrace!)
    (refresh-retrace-gizmo!)
    (save-acquire-state!)
    (when had (set-status-message! "Plane changed — anchor's points cleared"))
    (update-panel!)))

(defn- on-retrace-offset-change!
  "Live offset of the ACTIVE ricalco's plane along its normal (mm). Does NOT clear
   already-placed points (they keep their 3D positions); it retargets future
   clicks and moves the drawn face rectangle, so it's a set-first control."
  [offset]
  (swap! session assoc-in (active-r-path :plane :offset) offset)
  (refresh-retrace-gizmo!)
  (redraw-retrace!))

(defn- retrace-offset-range [_] [-15 15 0.5])

(declare fmt-vec)

(defn- obj-dir->world
  "Lift an OBJECT-frame direction to world through `pose`'s box-basis."
  [pose [x y z]]
  (let [{:keys [ex ey ez]} (bridge/box-basis pose)]
    (m/normalize (m/v+ (m/v* ex x) (m/v+ (m/v* ey y) (m/v* ez z))))))

(defn- anchor-mark
  "One anchor → \":id {:position :heading :up}\", its plane's pose lifted through
   `pose` (the emitted proxy's anchor pose).

   It used to emit \":id {:shape (poly …) :mark {…}}\" under :shapes, because the
   gesture used to be a TRACE and the polyline was its product. It isn't any
   more: what you place is an anchor, and the outline is drawn afterwards with
   `edit-path-2d` on the anchor's own plane. So it belongs in :marks, which is
   already the home of named poses — `(turtle (:ancora-1 (:marks A)) …)` — and
   :shapes stops being written at all.

   Points already traced are NOT emitted here. They were only ever a way to say
   where the plane was, and the plane now says that itself."
  [{:keys [name plane]} pose uniq]
  (let [{:keys [position heading up]} (plane-pose plane)]
    (str ":" (uniq (if (seq name) name "ancora"))
         " {:position " (fmt-vec (bridge/local->world pose position))
         " :heading " (fmt-vec (obj-dir->world pose heading))
         " :up " (fmt-vec (obj-dir->world pose up)) "}")))

(defn- anchor-entries
  "\":id {:position :heading :up}\" strings for every anchor, names keywordized
   and uniquified (a map can't hold duplicate keys)."
  [pose seen]
  (let [uniq (fn [nm] (loop [n (if (seq nm) nm "ancora")]
                        (if (contains? @seen n) (recur (str n "-2")) (do (swap! seen conj n) n))))]
    (vec (map #(anchor-mark % pose uniq) (ricalchi)))))

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
  ;; a lens change makes the stored mounting observations stale exactly as the
  ;; refinement does — slot geometry moves with the intrinsics. Lived before
  ;; it was written (battiscopa3, notte del 29/8): obs persisted at the
  ;; default 48 kept accusing the 44-manual readings of being the GEMELLO.
  (when (not= focal-mm (:focal-mm @session))
    (swap! session dissoc :cage-mounting-obs))
  (swap! session assoc :focal-mm focal-mm :focal-source :manual)
  ;; a manual lens is a human DECLARATION and must survive a reload like the
  ;; picks do — it didn't (found 3/9: Vincenzo's 28 lived only in memory, the
  ;; page reloaded at the default 48, and R clamped its way to 40.8 from
  ;; there). Debounced: the slider fires per-tick while dragging, and one
  ;; write after the hand settles is the honest amount of disk
  (when-let [t (:focal-save-timer @session)] (js/clearTimeout t))
  (swap! session assoc :focal-save-timer
         (js/setTimeout (fn []
                          (when @session
                            (swap! session dissoc :focal-save-timer)
                            (save-acquire-state!)))
                        400))
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
        live-box (.createElement js/document "div")
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
    (set! (.-className live-box) "eaq-live-box")
    (.appendChild panel live-box)
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
           :hint-el hint
           :message-el message :pnp-el pnp-box :retrace-el retrace-box :mark-el mark-box
           :live-el live-box
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
      (doseq [b [assign undo clr armedb exit]] (.appendChild actions b))
      (let [hint (.createElement js/document "span")]
        (set! (.-textContent hint) "gomma: clic destro su un click lo toglie")
        (set! (.-color (.-style hint)) "#999")
        (set! (.-fontSize (.-style hint)) "11px")
        (set! (.-marginLeft (.-style hint)) "6px")
        (.appendChild actions hint)))
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
          ;; detector finds the crown and fit-crown identifies it. A cage takes
          ;; the same button and the same key, seeded by four clicks on one ring —
          ;; the crown's own symmetry is what no photograph can resolve.
          (when (or (plate-proxy?) (cage-proxy?))
            (let [a (.createElement js/document "button")]
              (set! (.-type a) "button")
              (set! (.-textContent a)
                    (if (cage-proxy?)
                      "Auto — leggi la gabbia (a)"
                      "Auto — rileva e registra (a)"))
              (when (cage-proxy?)
                (set! (.-title a)
                      (str "Clicca 4 dischetti su UN anello, poi premi qui. Una corona di "
                           "dischetti uguali si rilegge identica ruotata, quindi i nomi che "
                           "hai dato possono essere sfasati senza che nulla se ne accorga: "
                           "a dirlo è il resto della gabbia, confrontato coi dischetti che "
                           "il rilevatore trova in tutta la foto.")))
              (.addEventListener a "click" (fn [_] (on-auto-register!)))
              (.appendChild box a))
            ;; offered only once there is something to refine: with one photo the
            ;; focal and the distance are the same unknown. Plate-only, as before —
            ;; widening the button above to a cage must not silently widen this one.
            (when (and (plate-proxy?)
                       (>= (count (filter #(>= (count (second %)) 4) (:pnp-picks @session))) 2))
              (let [r (.createElement js/document "button")]
                (set! (.-type r) "button")
                (set! (.-textContent r) "Rifinisci insieme (R)")
                (set! (.-title r)
                      (str "Una focale sola per la sessione e tutte le pose raffinate "
                           "insieme sui click che hai già fatto. Una foto da sola non "
                           "distingue una focale sbagliata da una distanza sbagliata; "
                           "tutte insieme sì."))
                (.addEventListener r "click" (fn [_] (on-refine-session!)))
                (.appendChild box r)))
            ;; Calibration reads the poses, so it is only worth offering once
            ;; there are enough of them to be worth reading — three is the
            ;; minimum plate-calib will accept, and three is already thin.
            (when (and (plate-proxy?)
                       (>= (count (filter #(>= (count (second %)) 6) (:pnp-picks @session))) 3))
              (let [c (.createElement js/document "button")]
                (set! (.-type c) "button")
                (set! (.-textContent c)
                      (if (:plate-calib @session)
                        "Ricalibra il piatto (C)"
                        "Calibra il piatto (C)"))
                (set! (.-title c)
                      (str "Misura dove stanno DAVVERO i dischetti di questo piatto, "
                           "invece di fidarsi del modello. Un piatto stampato si imbarca: "
                           "sul ⌀300 misurato tre mark stavano oltre un millimetro fuori "
                           "dal piano. Da fare dopo la rifinitura (R), perché legge le pose. "
                           "Il risultato resta legato al piatto, non alla sessione: vale "
                           "anche per le prossime."
                           (when-let [pc (:plate-calib @session)]
                             (str "\n\nGià calibrato: scostamento massimo "
                                  (modal/fmt-number (:worst-mm pc)) " mm su "
                                  (:views pc) " foto."))))
                (.addEventListener c "click" (fn [_] (on-calibrate-plate!)))
                (.appendChild box c))))))

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
                ;; ORDER MATTERS, and it was the wrong way round until 2026-08-30.
                ;; Above the bar there is no ✓ and there are no culprits: the
                ;; cleaner dropped the worst two of a fit that is bad all over,
                ;; and pointing at them in red sends the user to re-click points
                ;; that are fine (Vincenzo, battiscopa3 grab-01 — his clicks sat
                ;; within a pixel of a detected disc and got the blame anyway).
                (and rms (> rms pnp/accept-rms-px))
                (str "⚠ rms alto (" (.toFixed rms 1) "px)"
                     (when (seq outliers)
                       (str ", e togliere " (corner-labels outliers) " non lo fa scendere"))
                     " — non è un punto solo: controlla la FACCIA dichiarata degli"
                     " anelli e la focale, poi 'r'")
                (seq outliers)
                (str "✓ registrata" (when rms (str " (rms " (.toFixed rms 1) "px)")) " — "
                     (if (> (count outliers) 1) "i punti " "il punto ") (corner-labels outliers)
                     (if (> (count outliers) 1) " non si allineano" " non si allinea")
                     " (rosso): riclicca più preciso, toglil"
                     (if (> (count outliers) 1) "i" "o")
                     " con la gomma (clic destro), o 'o' per scartarl"
                     (if (> (count outliers) 1) "i" "o") " (nascosto o non allineabile), poi 'r'"
                     " — oppure vai avanti così.")
                solved?
                (str "✓ fit pulito, rms " (.toFixed rms 1) "px — 'p'/Esci, o ']' per un'altra foto")
                (nil? armed)
                (str "Tutti i " (pnp-noun) " visibili piazzati o nascosti — premi 'r'")
                :else
                (str (str/capitalize (pnp-noun)) " #" (lbl armed) " evidenziato — clicca dov'è, "
                     "o 'o' se è nascosto dal pezzo. Piazzati " n "/" target
                     (when (< n (min-pnp-picks)) (str " (ne servono ≥" (min-pnp-picks) ")")))))
        (.appendChild box info)
        ;; PER-RING FACE CHOICE (Vincenzo, 2026-08-30). On a cage the panel can
        ;; only guess which face of each ring you are looking at — it asks the
        ;; POSE, which is what the picking is trying to establish — and offering
        ;; the wrong one makes the user name discs after a face that is not in
        ;; the photograph: «avevo messo p perché mi presentava solo quelli»,
        ;; three evenings of camera-behind refusals downstream. He proposed the
        ;; cure and it is the right one: three toggles, one per ring, for the
        ;; judgement he actually makes by holding the cage up to the photo.
        ;;
        ;; Since 2026-08-31 the toggles are lit BY THE POSE: every gizmo commit
        ;; re-reads the faces from the aligned cage (derive-faces-from-pose!).
        ;; They stay pressable as the override — «mi fido dei tuoi occhi, non
        ;; della posa» — and a ring too edge-on to read gets a note here
        ;; instead of a lit button.
        (when (cage-proxy?)
          (let [row (.createElement js/document "div")
                choice (get-in @session [:cage-face-choice (:current-idx @session)])]
            (set! (.-className row) "eaq-pnp-actions")
            (let [lab (.createElement js/document "span")]
              (set! (.-textContent lab) "faccia che vedi:")
              (set! (.-color (.-style lab)) "#999")
              (set! (.-fontSize (.-style lab)) "11px")
              (.appendChild row lab))
            (doseq [axis [:x :y :z]]
              (doseq [[sign txt] [[1 "p"] [-1 "m"]]]
                (let [b (.createElement js/document "button")
                      on? (= sign (get choice axis))]
                  (set! (.-type b) "button")
                  (set! (.-textContent b) (str (str/upper-case (name axis)) txt))
                  (set! (.-color (.-style b)) (if on? "#111" "#ddd"))
                  (set! (.-background (.-style b)) (if on? "#7fd17f" "#333"))
                  (set! (.-border (.-style b)) (if on? "2px solid #fff" "1px solid #555"))
                  (set! (.-title b)
                        (str "Offri solo la faccia " txt " dell'anello "
                             (str/upper-case (name axis))
                             " — dal dischetto grande del doppio pallino verso il "
                             "pallino piccolo: antiorario = p, orario = m. "
                             "Ripremi per tornare alla scelta automatica."))
                  (.addEventListener b "click" (fn [_] (toggle-cage-face! axis sign)))
                  (.appendChild row b))))
            (.appendChild box row)
            ;; the profile guard, said out loud: a ring the pose sees nearly
            ;; edge-on declares nothing (bridge/cage-face-margin-deg), and the
            ;; panel owes the user the reason WITH the degrees — on grab-05 a
            ;; face decided by 13–17° of margin held half a day of wrong
            ;; diagnoses (2026-08-31)
            (let [faces (bridge/cage-faces-from-pose (:proxy-mesh @session)
                                                     (current-camera-pose))
                  edge-on (sort-by (comp str first)
                                   (keep (fn [[a {:keys [sign geo-sign elev-deg]}]]
                                           (when (and (nil? sign) (not (contains? choice a)))
                                             [a elev-deg geo-sign]))
                                         faces))]
              (when (seq edge-on)
                (let [note (.createElement js/document "div")]
                  ;; the guard withholds the DECLARATION, not the reading: say
                  ;; which button the pose would press and leave the pressing to
                  ;; him. Two dead buttons and no explanation is what he got
                  ;; first (2026-09-01, photo 4: «non viene aggiornato quello di
                  ;; Ym/Yp, restano deselezionati entrambi»).
                  (set! (.-textContent note)
                        (str/join " · "
                                  (for [[a e s] edge-on
                                        :let [nm (str/upper-case (name a))
                                              face (str nm (if (pos? s) "p" "m"))]]
                                    (str nm " è quasi di taglio (" (.toFixed e 0)
                                         "°): troppo poco per dichiararla io — la posa"
                                         " direbbe " face ", ma guarda la foto e premilo"
                                         " tu (o l'altro)"))))
                  (set! (.-color (.-style note)) "#c9a94a")
                  (set! (.-fontSize (.-style note)) "11px")
                  (.appendChild box note))))))
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
              names (.createElement js/document "button")
              faces (.createElement js/document "button")
              batchb (when (plate-proxy?) (.createElement js/document "button"))
              exit (.createElement js/document "button")]
          (set! (.-type solve) "button")
          (set! (.-textContent solve) "Risolvi PnP (r)")
          (set! (.-disabled solve) (< n (min-pnp-picks)))
          (.addEventListener solve "click" (fn [_] (on-solve-pnp!)))
          (set! (.-type clr) "button")
          (set! (.-textContent clr) "Azzera")
          (.addEventListener clr "click" (fn [_] (clear-pnp-picks!)))
          (set! (.-type names) "button")
          (set! (.-textContent names) (if (:show-names? @session) "Names: on (n)" "Names (n)"))
          (.addEventListener names "click" (fn [_] (toggle-mark-names!)))
          (set! (.-type faces) "button")
          (set! (.-textContent faces) (if (:show-all-marks? @session)
                                        "Both faces (F)" "Facing marks (F)"))
          (set! (.-title faces)
                "Offer every mark, including the ones the current pose believes are turned away")
          (.addEventListener faces "click" (fn [_] (toggle-all-marks!)))
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
          (.appendChild actions names)
          (.appendChild actions faces)
          (when batchb (.appendChild actions batchb))
          (.appendChild actions exit)
          ;; the eraser's one line of discoverability — a gesture with no button
          ;; is a gesture nobody finds (the missing eraser cost a whole poisoned
          ;; session, 2026-08-28)
          (let [hint (.createElement js/document "span")]
            (set! (.-textContent hint) "gomma: clic destro su un pallino lo toglie")
            (set! (.-color (.-style hint)) "#999")
            (set! (.-fontSize (.-style hint)) "11px")
            (set! (.-marginLeft (.-style hint)) "6px")
            (.appendChild actions hint)))
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
        (set! (.-textContent pv) (if (:hide-proxy? @session) "Show proxy (v)" "Hide proxy (v)"))
        (.addEventListener pv "click" (fn [_] (toggle-proxy!)))
        (set! (.-type b) "button")
        (set! (.-textContent b) "Add anchor (d)")
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
            {:keys [row]} (ui/create-slider-row {:label "Plane offset (mm)"
                                                 :value offset
                                                 :range-fn retrace-offset-range
                                                 :on-input on-retrace-offset-change!})
            actions (.createElement js/document "div")]
        (set! (.-className info) "eaq-pnp-info")
        (set! (.-textContent info)
              (str "Active anchor: " (or (:name (get rs active-idx)) "—") " — "
                   ;; naming a face is only honest where the six faces mean
                   ;; something: on a cage the plane is wherever the gizmo put it,
                   ;; and calling it "Sopra" would name the bounding cube's face
                   (if-let [lbl (and (not (plate-proxy?)) (retrace-face-labels [axis sign]))]
                     (str lbl " plane")
                     "free plane")
                   ". Place and orient it with the gizmo — the white ball is the"
                   " anchor's point. '[' / ']' to check it from the other views."
                   (when (pos? npts) (str " (" npts " traced points)"))))
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
        ;; The cage presets come FIRST: they are the ones that mean something on a
        ;; cage, and the only ones that give a known starting point.
        (when-let [presets (cage-ring-presets)]
          (let [row (.createElement js/document "div")]
            (set! (.-className row) "eaq-pnp-corners")
            (doseq [[i {:keys [label]}] (map-indexed vector presets)]
              (let [b (.createElement js/document "button")]
                (set! (.-type b) "button")
                (set! (.-textContent b) (str label " (" (inc i) ")"))
                (set! (.-title b) "Put the anchor at the cage centre, in this ring's plane")
                (.addEventListener b "click" (fn [_] (set-ring-preset! i)))
                (.appendChild row b)))
            (.appendChild box row)))
        (set! (.-className faces) "eaq-pnp-corners")
        ;; The six faces are the proxy BOUNDING BOX's faces. On a box proxy that
        ;; box is the part, so they are the part's own faces and the fastest way
        ;; to say "this one". On a cage the proxy is the reference AROUND the
        ;; part and the box is a cube enclosing it — 'sarebbero facce di cosa?'
        ;; (Vincenzo, 2026-08-20). Offering them there is offering six wrong
        ;; answers, so they are simply not built.
        (doseq [[a s] (if (plate-proxy?) [] retrace-face-order)]
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
              pxy (.createElement js/document "button")
              undo (.createElement js/document "button")
              clr (.createElement js/document "button")
              exit (.createElement js/document "button")]
          (set! (.-type nw) "button")
          (set! (.-type pxy) "button")
          (set! (.-textContent pxy) (if (:hide-proxy? @session) "Show cage (v)" "Hide cage (v)"))
          (.addEventListener pxy "click" (fn [_] (toggle-proxy!)))
          (set! (.-textContent nw) "New anchor (n)")
          (.addEventListener nw "click" (fn [_] (new-ricalco!)))
          (set! (.-type undo) "button")
          (set! (.-textContent undo) "Undo last (⌫)")
          (set! (.-disabled undo) (zero? npts))
          (.addEventListener undo "click" (fn [_] (undo-retrace-point!)))
          (set! (.-type clr) "button")
          (set! (.-textContent clr) "Clear")
          (set! (.-disabled clr) (zero? npts))
          (.addEventListener clr "click" (fn [_] (clear-retrace!)))
          (set! (.-type exit) "button")
          (set! (.-textContent exit) "Exit (d)")
          (.addEventListener exit "click" (fn [_] (stop-retrace!)))
          (.appendChild actions nw)
          (.appendChild actions pxy)
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
          (set! (.-textContent undo) "Undo last (⌫)")
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

(defn- render-live-panel!
  "The live-capture controls: a way in (Camera), a way to shoot (Grab), a device
   picker only once there is more than one device, and a way out. Shown only for a
   registration plate — a live frame registers against the crown, and offering the
   button on a box proxy would be offering a gesture that cannot work.

   The camera is deliberately NOT opened on entering the session: opening a camera
   turns on a light on the user's machine, and that should follow an intention."
  []
  (when-let [^js box (:live-el @session)]
    (set! (.-innerHTML box) "")
    (when (and (plate-proxy?) (not (:stage? @session)))
      (let [supported? (camera/supported?)
            on? (camera/active?)
            info (:camera-info @session)
            devices (:camera-devices @session)
            ^js info-el (.createElement js/document "div")
            ^js actions (.createElement js/document "div")]
        (set! (.-className info-el) "eaq-pnp-info")
        (set! (.-textContent info-el)
              (cond
                (not supported?)
                (str "No camera here: getUserMedia needs a secure context "
                     "(localhost or the desktop app, not an http:// LAN address).")
                on?
                (str "Live: " (:label info) " · " (first (:size info)) "×" (second (:size info))
                     " · Grab keeps the frame ONLY if it registers."
                     (if-let [f (live-focal)]
                       (str " Lens: " (.toFixed f 1) "mm-equiv ("
                            (name (or (:focal-source @session) :?)) ").")
                       " The first frame will measure the lens — give the plate some tilt."))
                :else
                "A camera in the room is a source of views: open it, aim, and grab."))
        (.appendChild box info-el)
        ;; The picker shows whether or not a camera is open: choosing the device
        ;; BEFORE opening is the natural order when the one you want is the phone
        ;; you just woke up, and a menu that only appears after you have opened
        ;; the wrong camera is a menu that arrives too late.
        (when (and supported? (> (count devices) 1))
          (let [^js sel (.createElement js/document "select")]
            (doseq [{:keys [id label]} devices]
              (let [^js o (.createElement js/document "option")]
                (set! (.-value o) id)
                (set! (.-textContent o) label)
                (when (= id (:device-id info)) (set! (.-selected o) true))
                (.appendChild sel o)))
            (.addEventListener sel "change" (fn [^js e] (start-camera! (.. e -target -value))))
            (.appendChild box sel)))
        (set! (.-className actions) "eaq-pnp-actions")
        (let [^js toggle (.createElement js/document "button")]
          (set! (.-type toggle) "button")
          (set! (.-textContent toggle) (if on? "Camera off" "Camera"))
          (set! (.-disabled toggle) (not supported?))
          (.addEventListener toggle "click"
                             (fn [_] (if (camera/active?)
                                       (do (stop-camera!) (update-panel!))
                                       (start-camera! (:device-id info)))))
          (.appendChild actions toggle))
        (let [^js grab (.createElement js/document "button")]
          (set! (.-type grab) "button")
          (set! (.-textContent grab) "Grab (g)")
          (set! (.-disabled grab) (not on?))
          (.addEventListener grab "click" (fn [_] (on-grab!)))
          (.appendChild actions grab))
        ;; Dropping a bad view: two clicks, no keyboard shortcut. A cheap view makes
        ;; bad views, so this has to exist — but it removes a file, so it must not be
        ;; something a stray keypress or a single mis-aimed click can do.
        (when (seq (:photos @session))
          (let [idx (:current-idx @session)
                armed? (= idx (:delete-armed @session))
                ^js del (.createElement js/document "button")]
            (set! (.-type del) "button")
            (set! (.-textContent del) (if armed?
                                        (str "Sure? delete view " (inc idx))
                                        (str "Delete view " (inc idx))))
            (when armed? (.add (.-classList del) "eaq-danger"))
            (.addEventListener del "click"
                               (fn [_]
                                 (if armed?
                                   (do (swap! session dissoc :delete-armed)
                                       (delete-view! idx))
                                   (do (swap! session assoc :delete-armed idx)
                                       (update-panel!)
                                       ;; disarm on its own: a button left saying
                                       ;; "Sure?" is a trap for the next click
                                       (js/setTimeout
                                        (fn [] (when (= idx (:delete-armed @session))
                                                 (swap! session dissoc :delete-armed)
                                                 (update-panel!)))
                                        4000)))))
            (.appendChild actions del)))
        (.appendChild box actions)))))

(defn- session-hint
  "The procedure this session actually has, in one line. Three, because they are
   three different jobs and one text that covers all of them covers none: a BOX is
   aligned by hand and snapped to its own edges; a PLATE of photos registers itself
   off the crown; an EMPTY folder has no photos at all and is filled by shooting."
  []
  (cond
    (empty? (:photos @session))
    (str "Sessione vuota: apri la Camera, inquadra il piatto con l'oggetto sopra e premi Grab "
         "(o 'g'). Il primo scatto MISURA l'obiettivo — dagli un po' di inclinazione, "
         "un piatto ripreso perfettamente in faccia non può dire la focale. "
         "Uno scatto che non si registra non entra: si riscatta.")

    (plate-proxy?)
    (str "Piatto di registrazione: 'a' registra da sola tutte le foto (rilevamento della corona), "
         "'p' per quelle che non ce la fanno, 'R' rifinisce focale e pose insieme, "
         "'C' misura il piatto stesso (una volta per piatto, vale anche per le prossime sessioni). "
         "Grab (o 'g') aggiunge una vista dal vivo, già registrata. "
         "'v' nasconde/mostra il proxy per leggere la foto sotto.")

    :else
    (str "1) la Focale è letta da EXIF — ritoccala con lo slider (foto 1) solo se il box è "
         "della taglia sbagliata, SENZA spostarlo — 2) poi trascina per posizione/rotazione — "
         "3) 's' per agganciare il box agli spigoli reali della foto — 4) con almeno 2 foto "
         "agganciate, 'f' per il fit congiunto sulle altre. "
         "'v' nasconde/mostra il proxy per leggere la foto sotto.")))

(defn- update-panel! []
  (let [stage? (:stage? @session)]
    (when-let [^js hint (:hint-el @session)]
      (set! (.-textContent hint) (session-hint)))
    ;; In the stage the Phase-1 sub-panels don't apply — blank their boxes so
    ;; no 'Registra…'/'Ricalca…' entry buttons linger over the free-orbit view.
    (if stage?
      (doseq [k [:pnp-el :retrace-el :mark-el :live-el]]
        (when-let [^js b (k @session)] (set! (.-innerHTML b) "")))
      (do (render-pnp-panel!)
          (render-retrace-panel!)
          (render-mark-panel!)
          (render-live-panel!)))
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

        ;; 'v' hides/shows the proxy so the photo is readable while registering.
        ;; :retrace since 2026-08-21 (the proxy is the depth cue for placing a
        ;; plane and sometimes the thing in the way); :pnp since 2026-09-01 —
        ;; its wireframe never needed hiding, but the cage's solid glue tabs
        ;; cover the very discs being clicked, and sat there with 'v' dead.
          (and (#{:gizmo :retrace :pnp} (:mode @session)) (= key "v"))
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

        ;; Backspace in the armed flow is the eraser's keyboard half: delete the
        ;; pick nearest where the cursor is (right-click does the same, aimed).
          (and pnp? (not (batch-mode?)) (= key "Backspace"))
          (do (.preventDefault e) (.stopPropagation e)
              (if-let [px (:pnp-cursor-px @session)]
                (erase-pick-at! px)
                (set-status-message!
                 "gomma: porta il cursore sul pallino da togliere, poi Backspace (o clic destro)")))

        ;; 'o' — the armed marker is occluded by the part in this view: skip it
        ;; (drop any pick, mark it hidden so nothing re-places it), then 'r'.
        ;; Armed flow only (batch has no armed target).
          (and pnp? (not (batch-mode?)) (= key "o"))
          (do (.preventDefault e) (.stopPropagation e) (skip-armed-corner!))

          (and pnp? (= key "F"))
          (do (.preventDefault e) (.stopPropagation e) (toggle-all-marks!))

          (and (#{:pnp :retrace :gizmo} (:mode @session)) (= key "n"))
          (do (.preventDefault e) (.stopPropagation e) (toggle-mark-names!))

          (and (= :retrace (:mode @session)) (re-matches #"[123]" key))
          (do (.preventDefault e) (.stopPropagation e)
              (set-ring-preset! (dec (js/parseInt key 10))))

          (and pnp? (not (batch-mode?)) (re-matches #"[1-8]" key))
          (do (.preventDefault e) (.stopPropagation e)
              (arm-corner! (dec (js/parseInt key 10))))

        ;; 'g' — grab a frame from the live camera and register it on the spot.
        ;; Gated to gizmo/pnp like the other registration keys: during a retrace or
        ;; a mark the proxy pose is frozen on purpose, and a new view moving it
        ;; underneath would be the one thing those modes must not suffer.
          (and (not retrace?) (not mark?) (= key "g"))
          (do (.preventDefault e) (.stopPropagation e) (on-grab!))

          (and (pos? n) (= key "["))
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

        ;; 'a' — Auto. On a PLATE: detect + register every photo with zero clicks
        ;; (fetta C). On a CAGE: read the crown you clicked against the whole cage
        ;; and place the rest (cage-read-and-place!) — seeded, because a crown
        ;; cannot say which of its equal marks is mark zero and no photograph can.
        ;; The box has no analogue; say so rather than silently no-op.
          (and (not retrace?) (not mark?) (= key "a"))
          (do (.preventDefault e) (.stopPropagation e)
              (if (or (plate-proxy?) (cage-proxy?))
                (on-auto-register!)
                (set-status-message!
                 "Auto (a) è per il piatto di registrazione e per la gabbia.")))

          ;; capital R: refining the whole session is not something to trip into
          ;; while reaching for 'r' (which re-solves THIS photo)
          (and (not retrace?) (not mark?) (= key "R"))
          (do (.preventDefault e) (.stopPropagation e)
              (on-refine-session!))

          ;; capital C for the same reason as R: calibrating the plate rewrites
          ;; the reference every measurement in the session is against, so it is
          ;; not something to trip into while reaching for a lowercase key
          (and (not retrace?) (not mark?) (= key "C"))
          (do (.preventDefault e) (.stopPropagation e)
              (on-calibrate-plate!))

          (and (pos? n) (= key "]"))
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
                                          ;; Leva 2 — the per-photo index
                                          ;; observations that feed the session's
                                          ;; mounting VOTE. Persisted because the
                                          ;; arbiter must survive reloads: kept
                                          ;; in-memory at first ('come :pnp?') and
                                          ;; paid for live (29/8 sera) — a reload
                                          ;; emptied the vote and the flip-face
                                          ;; twin walked right back in on the next
                                          ;; seeded read. The refinement still
                                          ;; clears them (stale intrinsics), and
                                          ;; the CAGE fingerprint travels with
                                          ;; them: slots are measured against the
                                          ;; MODEL's azimuths, so obs taken at
                                          ;; k6 become lies the moment the
                                          ;; session reopens with :phases
                                          ;; declared — they must die with the
                                          ;; proxy they were measured under.
                                          ;; the faces the USER declared per photo
                                          ;; — a judgement about the picture, not
                                          ;; a derived value: it must survive a
                                          ;; reload like the picks do
                                          :cage-face-choice
                                          (into {} (for [[i c] (:cage-face-choice @session)
                                                         :when (seq c)]
                                                     [(str i) c]))
                                          ;; the photos whose pose the user
                                          ;; aligned BY HAND (gizmo commit) —
                                          ;; the eye seed 'a' gates by. A
                                          ;; human act, so it survives reload
                                          ;; like the picks do
                                          :eye-posed (vec (sort (:eye-posed @session)))
                                          :cage-mounting-obs
                                          (let [pm (:proxy-mesh @session)]
                                            {:cage {:d (:cage-d pm)
                                                    :marks (:cage-marks pm)
                                                    :phases (:cage-phases pm)
                                                    ;; a flip is the sharpest
                                                    ;; possible change to what
                                                    ;; these observations MEAN —
                                                    ;; they are readings of the
                                                    ;; index's housing, and a
                                                    ;; flip mirrors it. Missing
                                                    ;; here for the first hours
                                                    ;; :flips existed (1/9).
                                                    ;; NAMES, and nil when
                                                    ;; empty: keywords come back
                                                    ;; from JSON as strings, and
                                                    ;; a file written before this
                                                    ;; key existed must still
                                                    ;; match a flip-less cage
                                                    :flips (cage-flips-tag pm)
                                                    :index-phase (:cage-index-phase pm)
                                                    ;; slot geometry moves with
                                                    ;; the lens: obs saved at one
                                                    ;; focal must not judge a
                                                    ;; session running another
                                                    ;; (battiscopa3: obs at 48
                                                    ;; accusavano le letture a 44
                                                    ;; per due sere)
                                                    :focal-mm (:focal-mm @session)}
                                             :by-photo
                                             (into {} (for [[idx obs] (:cage-mounting-obs @session)
                                                            :when (seq obs)]
                                                        [(str idx) obs]))})
                                          ;; P4a-2 — lens focal (35mm-equiv) +
                                          ;; provenance, so a manual tweak survives
                                          ;; re-entry instead of reverting to the
                                          ;; EXIF/default read (load-exif-focal!
                                          ;; runs before load-acquire-state!).
                                          :focal {:mm (:focal-mm @session)
                                                  :source (:focal-source @session)}
                                          ;; which camera this session's grabs
                                          ;; came from («label @ w×h») — it is
                                          ;; what lets a LATER 'R', camera long
                                          ;; closed, still file the measured
                                          ;; lens under ~/.ridley/cameras.json
                                          :grab-camera (:grab-camera @session)
                                          ;; P4a-3 — named marks (object-frame
                                          ;; position + normal + id) and the
                                          ;; current mark face, so they survive
                                          ;; exit/re-entry like the retrace does.
                                          :marks (:marks @session)
                                          :mark-plane (:mark-plane @session)
                                          ;; The measured plate ('C'). Also filed
                                          ;; under ~/.ridley/plates/, which is the
                                          ;; copy other sessions read — this one is
                                          ;; here so THIS session's numbers stay
                                          ;; reproducible even after the plate is
                                          ;; re-printed and the store overwritten.
                                          :plate-calib (:plate-calib @session)}))
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
    (let [{:keys [proxy-pose camera-pose-0 photos retrace ricalchi ricalco-idx marker-picks pnp focal grab-camera marks mark-plane plate-calib cage-mounting-obs cage-face-choice eye-posed]} (js->clj (js/JSON.parse text) :keywordize-keys true)
          ;; JSON keys are strings → keywordize-keys turns the integer photo/corner
          ;; keys into :0/:1/… ; parse a whole level back to int keys.
          int-keys (fn [m] (into {} (map (fn [[k v]] [(js/parseInt (name k) 10) v]) m)))
          ;; a plane saved by an older session has only :axis/:sign/:offset; one
          ;; saved since carries :base, the free pose. Keep whichever is there —
          ;; plane-pose reads both, so an old ricalco re-opens exactly where it was.
          norm-plane (fn [pl]
                       (cond-> {:axis (:axis pl) :sign (:sign pl)
                                :offset (or (:offset pl) 0.0)}
                         (:base pl) (assoc :base {:position (vec (:position (:base pl)))
                                                  :heading (vec (:heading (:base pl)))
                                                  :up (vec (:up (:base pl)))})))]
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
      (when (seq cage-face-choice)
        (swap! session assoc :cage-face-choice
               (into {} (map (fn [[k v]]
                               [(js/parseInt (name k) 10)
                                (into {} (map (fn [[a s]] [(keyword (name a)) s]) v))])
                             cage-face-choice))))
      ;; the hand-aligned photos come back with their stamp: the eye seed is a
      ;; human act, and 'a' after a reload deserves the same gate
      (when (seq eye-posed)
        (swap! session assoc :eye-posed (set eye-posed)))
      ;; Leva 2 — the mounting vote comes back with the session, but ONLY under
      ;; the cage it was measured against: slots are model-frame, so a session
      ;; reopened with :phases declared (or another cage entirely) makes the
      ;; old observations false witnesses — dropped, with a log line, and the
      ;; vote rebuilds at the next 'a'. :axis/:sense live as keywords, travel
      ;; as strings.
      (when-let [by-photo (:by-photo cage-mounting-obs)]
        (let [pm (:proxy-mesh @session)
              now {:d (:cage-d pm) :marks (:cage-marks pm)
                   :phases (:cage-phases pm)
                   :flips (cage-flips-tag pm)
                   :index-phase (:cage-index-phase pm)
                   ;; the focal this state file is about to restore — obs from
                   ;; a file saved at another lens (or before the lens was in
                   ;; the fingerprint at all) are dropped
                   :focal-mm (:mm focal)}
              then (:cage cage-mounting-obs)]
          (if (= now then)
            (swap! session assoc :cage-mounting-obs
                   (into {} (map (fn [[k v]]
                                   [(js/parseInt (name k) 10)
                                    (mapv #(-> %
                                               (update :axis keyword)
                                               (update :sense keyword))
                                          v)])
                                 by-photo)))
            (auto-log! (str "  voto del montaggio scartato: era misurato su un'altra "
                            "gabbia/fasatura (" (pr-str then) " → " (pr-str now)
                            ") — si ricostruisce ripremendo 'a'")))))
      ;; P4a-2 — lens focal. Restored AFTER load-exif-focal! (which ran first), so a
      ;; saved manual tweak wins; an unchanged EXIF/default value restores to itself.
      (when-let [mm (:mm focal)]
        (swap! session assoc :focal-mm mm :focal-source (keyword (:source focal))))
      ;; the camera this session's grabs came from — restored so a later 'R'
      ;; can still file its measured lens under ~/.ridley/cameras.json
      (when grab-camera
        (swap! session assoc :grab-camera grab-camera))
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
      ;; The measured plate ('C') is only STASHED here, not applied. Two sources
      ;; can supply one — this file and the plate store under ~/.ridley/plates/ —
      ;; and when both did, whichever ran first won silently: the session's copy
      ;; short-circuited the store's path, which was the only one that announced,
      ;; so a reopened session came up calibrated and said nothing (Vincenzo,
      ;; 2026-08-14: "non è comparso il messaggio"). Deciding in one place and
      ;; speaking in one place is load-plate-calibration!'s job now; here we only
      ;; carry the file's contents to it.
      (when-let [obj (seq (:obj plate-calib))]
        (swap! session assoc :plate-calib-from-session
               {:obj (mapv vec obj)
                :worst-mm (:worst-mm plate-calib)
                :views (:views plate-calib)}))
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
      (.catch (fn [_] nil)) ;; no file yet (first snap of a fresh session) — fine
      (.then (fn [_]
               ;; a reopened grab session knows its camera (:grab-camera just
               ;; restored) — stash the store's measured lens so R can use it
               ;; as a SECOND STARTING POINT (multi-start). Stashed, not
               ;; adopted: adoption stays the camera-open flow's business,
               ;; where the session provably has no lens of its own.
               (when-let [k (:grab-camera @session)]
                 (-> (read-camera-store)
                     (.then (fn [store]
                              (when-let [mm (get-in store [k "focal-mm"])]
                                (swap! session assoc :remembered-focal-mm mm))))
                     (.catch (fn [_] nil))))))))

(defn- announce-plate-calibration!
  "Apply `c` to the proxy and SAY SO. Every entry into a calibrated session goes
   through here, whichever file the calibration came from.

   The saying is not a courtesy. A calibration silently changes the reference
   every measurement in the session is taken against, and it can outlive the
   plate that justified it: the store is keyed by diameter and crown count, so a
   re-printed ⌀300 inherits the old one's warp under the new one's name. The line
   on entry is what lets that be noticed."
  [c source path]
  (let [obj (mapv vec (:obj c))]
    (when (= (count obj) (count (crown-ids)))
      (swap! session assoc :plate-calib
             {:obj obj :worst-mm (:worst-mm c) :views (:views c)})
      (apply-plate-calibration! obj)
      (auto-log! (str "piatto MISURATO"
                      (case source
                        :store " (calibrato in una sessione precedente)"
                        :session " in questa sessione"
                        "")
                      ": scostamento massimo "
                      (modal/fmt-number (:worst-mm c)) " mm su " (:views c) " foto"
                      (when-let [d (:measured-on c)] (str ", " (subs d 0 10)))
                      ". Le misure sono riferite a questo piatto."
                      (when (= source :store)
                        (str " Se lo hai ristampato, cancella " path))))
      true)))

(defn- load-plate-calibration!
  "Put the measured plate back, from whichever source has one, and announce it.

   This is what makes calibrating worth doing: the plate is a physical object you
   own, so measuring it once should improve every session you shoot on it, not
   just the one where you pressed 'C'.

   The session's own copy wins over the store — it is what makes THIS session's
   numbers reproducible after the plate has been re-measured — but both arrive
   here, because the previous arrangement applied the session's copy elsewhere
   and only the store's path spoke. A reopened session therefore came up
   calibrated in silence.

   Always resolves — a session with no calibration at all is the normal case."
  []
  (let [path (plate-store-path)
        mine (:plate-calib-from-session @session)]
    (cond
      (not (plate-proxy?)) (js/Promise.resolve nil)
      mine (do (announce-plate-calibration! mine :session path)
               (js/Promise.resolve nil))
      (nil? path) (js/Promise.resolve nil)
      :else
      (-> (stl/desktop-read-file path)
          (.then (fn [text]
                   ;; a store written for a different crown cannot be applied to
                   ;; this one mark-for-mark; ignore it rather than guess
                   (announce-plate-calibration!
                    (js->clj (js/JSON.parse text) :keywordize-keys true) :store path)))
          (.catch (fn [_] nil))))))

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
  (let [opts (first (filter map? args))
        rest' (remove map? args)]
    ;; SOLO nomi. Passare l'acquire — `(plane-from-edges A :uno :due)` — è
    ;; l'errore naturale, perché ogni altra cosa in questo canale comincia da A;
    ;; e il messaggio che ne usciva («non trovo #'user/A fra gli :edges») diceva
    ;; il sintomo e non la causa. Qui si separano i nomi dal resto, così il
    ;; rifiuto può nominare l'argomento di troppo per quello che è.
    {:plane-from (vec (filter keyword? rest'))
     :junk (vec (remove keyword? rest'))
     :opts opts}))

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
  [dir nm {:keys [plane-from junk opts]} edges pose plate? centre]
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
      (seq junk)
      ;; nomina il valore di troppo per com'è scritto: dire "un argomento così"
      ;; lascerebbe indovinare QUALE, e `#'user/A` da solo diceva il sintomo
      (do (say (str "vuole solo i NOMI dei bordi (:uno :due) · c'è anche "
                    (str/join ", " (map #(let [t (pr-str %)]
                                           (if (> (count t) 24) (str (subs t 0 24) "…") t))
                                        junk))
                    " · se è l'acquire, toglilo: bastano i nomi · ? plane-from-edges"))
          nil)

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
   hides it entirely, `:label false`/`\"testo\"` its name.

   NON si misura più (2026-08-10). Misurare una curva chiede di APPAIARE punti
   fra due foto, che è precisamente ciò che questo canale è nato per non fare, e
   il piano — l'unica cosa per cui serviva — lo danno meglio due spigoli dritti
   non paralleli, che si misurano senza appaiare niente. La forma resta perché i
   punti di una curva presa prima sono prove valide, e un sorgente che la
   contiene deve continuare a girare."
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
  "[[label acquire] …] → [[label marks edges] …], which is all
   fuse/declared-anchors needs to know about a session. Edges ride along because
   a declared correspondence may name one: `[[:A/spigolo :B/spigolo]]`."
  [sessions]
  (mapv (fn [[l a]] [l (:marks a) (:edges a)]) sessions))

(defn- report-union!
  "Print the fit: the numbers first, and a line of advice only where a number is
   actually bad.

   It used to print four paragraphs every time, prose and all, whether or not
   anything was wrong — and the reasoning that belongs in a docstring was being
   read out loud on every Run (Vincenzo 2026-08-14: «sarebbe meglio evitare
   romanzi nell'output di una funzione, torniamo dati e eventualmente suggerimenti
   su cosa fare se ci sono problemi»). He is right, and the failure mode is worse
   than verbosity: a warning that prints unconditionally stops being read, and
   the one time it matters it scrolls past with the rest.

   So: a table, a summary line, and nothing else unless a threshold is crossed.
   The explanations live in the manual card, which is where a long argument can
   be read once instead of every time."
  [dir fit]
  (let [{:keys [rms-mm max-normal-deg per-anchor planes points edges
                distances-testify? suspect]} fit
        n (fn [x] (modal/fmt-number x))]
    (state/capture-println
     (str ";; acquire-union · " dir " → frame della prima sessione\n"
          (str/join "\n"
                    ;; `str`, NOT clj->js: clj->js on a keyword keeps only its
                    ;; name, which would silently drop the :A/ label that says
                    ;; WHICH session's mark this line is about.
                    (map (fn [{:keys [name kind residual-mm normal-deg]}]
                           (str ";;   " (clj->js kind) " " name "  " (n residual-mm) " mm"
                                (when normal-deg (str " · " (n normal-deg) "°"))))
                         per-anchor))
          ;; The degrees are converted into millimetres per 10mm of distance,
          ;; because that is the only way they can be compared with the rms next
          ;; to them — and they usually WIN. A residual in mm is a rigid offset,
          ;; the same everywhere; an angle is a lever that grows with distance
          ;; from the anchor, which is exactly why geometry built far from a mark
          ;; stops covering the object while the numbers look small.
          "\n;;   rms " (n rms-mm) " mm · normali max " (n max-normal-deg) "° ("
          (n (* 10.0 (Math/tan (* max-normal-deg (/ Math/PI 180.0)))))
          " mm ogni 10 mm dal mark) · "
          planes " piani"
          (when (pos? (or edges 0)) (str " + " edges " spigoli"))
          (when (pos? points) (str " + " points " punti"))))
    ;; ---- and only now, what is WRONG ----
    (when (> max-normal-deg 3.0)
      (state/capture-println
       (str ";; ⚠ normali fuori di " (n max-normal-deg)
            "°: due zone dichiarate uguali non lo sono, o una è marcata male.")))
    (when-let [s suspect]
      (state/capture-println
       (str ";; ⚠ togli " (:name s) " e lo scarto va da " (n rms-mm) " a "
            (n (:rms-without s)) " mm: è quell'aggancio, non gli altri.")))
    (when-let [w (fuse/worst-anchor per-anchor)]
      (state/capture-println
       (str ";; ⚠ " (:name w) " si discosta dagli altri (" (n (:residual-mm w))
            " mm): rimisuralo, o non è la stessa zona nelle due sessioni.")))
    (when (> rms-mm 1.0)
      (state/capture-println
       (str ";; ⚠ " (n rms-mm) " mm di scarto: quello che disegni su una sessione "
            "cade storto sull'altra di altrettanto.")))
    (when-not distances-testify?
      (state/capture-println
       ";; nota: pochi agganci — i mm tornano a zero per costruzione, guarda le normali."))))

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
    ;; Printed only when the fit has nothing else warning about it. With two
    ;; sessions this is ALWAYS true, so unconditionally it was a line that
    ;; appeared on every single run and therefore stopped being read — which is
    ;; the opposite of what a caveat is for.
    (when (and (< (count others) 2)
               (every? #(and (nil? (:error (:fit %)))
                             (<= (:rms-mm (:fit %) 0.0) 1.0)
                             (<= (:max-normal-deg (:fit %) 0.0) 3.0))
                       fits))
      (state/capture-println
       ";; nota: due sessioni sole — nessun anello da chiudere, quindi niente che possa smentire il fit."))))

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
  (let [[x y] args
        ;; `(acquire-union :A A :B B)` — the labels and the sessions written as
        ;; keyword ARGUMENTS instead of as pairs. It is the natural mistake:
        ;; everything else that takes a name and a value in this language reads
        ;; that way, and the shape is one bracket away from correct. Worth
        ;; recognising by name, because the generic refusal below sends you to
        ;; read the signature when you already know it (Vincenzo 2026-08-14).
        kwargs? (and (>= (count args) 4)
                     (even? (count args))
                     (every? keyword? (take-nth 2 args))
                     (every? acq? (take-nth 2 (rest args))))]
    (cond
      kwargs?
      (do (state/capture-println
           (str ";; acquire-union: le sessioni vanno a COPPIE dentro un vettore, non "
                "come argomenti a chiave.\n"
                ";;   hai scritto:  (acquire-union "
                (str/join " " (map (fn [a] (if (keyword? a) (str a) "…")) args)) ")\n"
                ";;   va scritto:   (acquire-union [["
                (str/join "] [" (map (fn [[k _]] (str k " …"))
                                     (partition 2 args)))
                "]])\n"
                ";; Il vettore serve perché l'ORDINE conta: la prima sessione è quella "
                "di riferimento, e la fusione va nel suo frame."))
          nil)

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
      ;; Edges join marks as anchors here. They were measured all along and read
      ;; by nothing: an edge pins four degrees of freedom to a plane's three, is
      ;; measured along its whole length, and — the part that decides it — needs
      ;; no point paired with any other point. On an object whose only flat zones
      ;; are parallel (Vincenzo's grinder, 2026-08-14) they are the only anchors
      ;; there are.
      (let [marks-of (into {} (map (fn [[l a]] [l (:marks a)]) x))
            edges-of (into {} (map (fn [[l a]] [l (:edges a)]) x))]
        (fuse-sessions x
                       (fn [to-lbl _lbl b]
                         [(fuse/shared-name-anchors (get marks-of to-lbl) (:marks b)
                                                    (get edges-of to-lbl) (:edges b))
                          nil])
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
    (camera/unwatch-devices!)
    ;; Release the camera. A live stream left running keeps the recording light on
    ;; after the session that asked for it is gone — a device the user can see is
    ;; on, with nothing on screen explaining why.
    (camera/stop!)
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
  [pose seen]
  (let [{:keys [ex ey ez]} (bridge/box-basis pose)
        world-dir (fn [[nx ny nz]]
                    (m/normalize (m/v+ (m/v* ex nx) (m/v+ (m/v* ey ny) (m/v* ez nz)))))
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
         ;; :shapes is no longer WRITTEN — an anchor is a pose and lives in
         ;; :marks. It is still emitted when the marker already carried some, so
         ;; a form produced before this keeps its traced shapes instead of losing
         ;; them on the next confirm; a session that has none omits the key.
         (let [kept (preserved-entries ":shapes")]
           (if (seq kept)
             (str i3 ":shapes " (fmt-map-block ":shapes" kept i3) "\n")
             ""))
         i3 ":marks " (fmt-map-block ":marks"
                                     (let [seen (atom #{})]
                                       (merge-entries (preserved-entries ":marks")
                                                      (into (anchor-entries anchor-pose seen)
                                                            (marks-entries anchor-pose seen))))
                                     i3) "\n"
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

(defn- session-json-from-folder
  "session.json's TEXT for a folder of images with NO NOTE.md: every photo
   out-of-ring (θ nil), in filename order.

   This is not a lenient fallback, it is the registration CAGE's protocol. The
   reference travels WITH the object, so there is no turntable, no angle to
   record, and therefore nothing a NOTE could say that the folder doesn't:
   every shot is free and registers on its own marks via PnP ('p'). The photo
   table was only ever the turntable's log.

   Without this, `ensure-session-json!`'s last resort — an EMPTY session, right
   for an empty folder — would swallow a folder of real photos AND persist the
   emptiness, so the second open wouldn't even reach this code."
  [dir files]
  (state/capture-println
   (str "edit-acquire: nessun NOTE.md in " dir " — prendo le " (count files)
        " foto dalla cartella, tutte fuori-anello (θ libera). È il protocollo"
        " della gabbia: registrale una per una con 'p'."))
  (js/JSON.stringify
   (clj->js {:dir dir :photos (mapv (fn [f] [f nil]) files) :bootstrap []})
   nil 1))

(defn- build-session-json-from-note
  "Build session.json's TEXT for `dir` from its NOTE.md + image files — the
   same shape and rules cli.cljs's init-session writes, but IN-APP so a fresh
   session (photos + NOTE.md, no session.json yet) opens without the manual
   `node out/paq.js --init-session …` step. Parsing goes through the shared
   ridley.photogrammetry.note, so the tool and the CLI agree on angles and on
   which shots are out-of-ring (θ `libera`). With images but no NOTE, falls
   back to `session-json-from-folder`. Returns Promise<string>; rejects only
   when the folder has neither a NOTE with a photo table nor a single image."
  [dir]
  (-> (js/Promise.all
       #js [(-> (stl/desktop-read-file (str dir "/NOTE.md")) (.catch (fn [_] nil)))
            (-> (stl/desktop-list-dir dir)
                ;; NOT swallowed. A rejection here means the file service did not
                ;; answer — and an unanswered question is not the answer "the
                ;; folder is empty", which is what swallowing it said: the session
                ;; then opened EMPTY on a folder full of photos and invited the
                ;; user to grab a frame (2026-08-23, and it read as "the app lost
                ;; my photos" in both the desktop and the browser). An empty folder
                ;; resolves to [] and still lands in the live-grab branch below;
                ;; only silence gets reported.
                (.catch (fn [err]
                          (if (str/includes? (str (.-message err))
                                             stl/service-unreachable)
                            (throw err)
                            ;; the service answered, it just could not list this
                            ;; folder — same as an empty one, as before
                            #js []))))])
      (.then (fn [^js results]
               (let [note-txt (aget results 0)
                     files (->> (array-seq (aget results 1))
                                (map (fn [^js e] (.-name e)))
                                (filter #(re-find #"(?i)\.(jpe?g|png)$" %))
                                sort vec)]
                 (if-not note-txt
                   (if (seq files)
                     (session-json-from-folder dir files)
                     (throw (js/Error. (str "né NOTE.md né immagini in " dir))))
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
                      nil 1))))))))

(defn- empty-session-json
  "session.json for a folder with nothing in it yet — the LIVE case: the session is
   opened first and filled afterwards, one grabbed frame at a time. An empty film
   is a legitimate starting state, not a missing file: refusing to open here would
   mean there is no way to reach the Grab control that would create the photos the
   refusal is complaining about."
  [dir]
  (js/JSON.stringify (clj->js {:dir dir :photos [] :bootstrap []}) nil 1))

(defn- ensure-session-json!
  "Resolve to session.json's TEXT for `dir`: read it if present; otherwise BUILD it
   from NOTE.md (+ the folder's images) — so a session of real photos opens with no
   manual CLI step (Vincenzo 2026-07-26: 'non possiamo lanciare --init-session a
   mano per ogni sessione') — and failing that, start an EMPTY one. Persisting is
   best-effort: a document that can't be written still opens the session."
  [dir]
  (-> (stl/desktop-read-file (str dir "/session.json"))
      (.catch (fn [_]
                (state/capture-println
                 (str "edit-acquire: session.json assente in " dir
                      " — la costruisco dalla cartella"))
                (-> (build-session-json-from-note dir)
                    (.catch (fn [err]
                              ;; an unreachable file service is not an empty folder
                              (when (str/includes? (str (.-message err)) stl/service-unreachable)
                                (throw err))
                              (state/capture-println
                               (str "edit-acquire: nessun NOTE.md leggibile in " dir
                                    " (" err ") — apro una sessione VUOTA: riempila"
                                    " scattando dal vivo (Camera → Grab)."))
                              (empty-session-json dir)))
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
               (let [{:keys [photos doc]} (parse-session-json text)]
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
                                  ;; session.json as read, so appending a grabbed
                                  ;; frame rewrites the film and nothing else
                                  :session-doc doc
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
                     ;; then the plate itself: a calibration this session already
                     ;; carries wins, otherwise the one filed under the plate
                     (.then (fn [_] (load-plate-calibration!)))
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
                              ;; keep the camera picker up to date for as long as
                              ;; the session lives (a phone can arrive at any time)
                              (watch-cameras!)
                              ;; A session with no photos yet (the live case: it is
                              ;; filled by grabbing) has nothing to enter — put the
                              ;; camera at photo 0's vantage so the proxy is there to
                              ;; aim at, and let Grab create the first view.
                              (if (seq (:photos @session))
                                (do (enter-photo! 0) (report-focal!))
                                (do (viewport/set-camera-pose! (ensure-photo-pose 0))
                                    (set-status-message!
                                     (if (cage-proxy?)
                                       ;; the plate's promise ("measures the lens")
                                       ;; is not the cage's: its frames are kept
                                       ;; unregistered, for 'p'+'a' by hand
                                       (str "Empty session: open the camera and grab frames "
                                            "— they are kept unregistered; on each, click 4 "
                                            "discs on ONE ring with 'p', then press 'a'.")
                                       (str "Empty session: open the camera and grab a frame "
                                            "— the first one measures the lens.")))))))))))
      (.catch (fn [err]
                ;; ALSO to the browser console: this runs after the evaluation has
                ;; returned, so capture-println writes into a print buffer nobody
                ;; flushes any more — the session dies and the user is told
                ;; "Evaluation successful" and nothing else (2026-08-21, and it
                ;; cost a long hunt to see an error that had been raised all along)
                (js/console.error "edit-acquire: couldn't load session —" err)
                (state/capture-println (str "edit-acquire: couldn't load session — " err))
                ;; capture-println writes into a buffer nobody flushes any more at
                ;; this point (see the note above); the REPL panel is the one
                ;; surface that still reaches the user after evaluation returned
                (auto-log!
                 (if (str/includes? (str (.-message err)) stl/service-unreachable)
                   (str "edit-acquire: the file service is not answering, so the photos in "
                        session-dir " cannot be read. That service is Ridley Desktop's own "
                        "local server (127.0.0.1:12321), and it serves a browser tab too. "
                        "It does not start when another Ridley already holds the port: quit "
                        "every Ridley window and open ONE.")
                   (str "edit-acquire: couldn't load session — " err)))
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
