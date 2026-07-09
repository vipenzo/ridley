(ns ridley.editor.gizmo
  "Direct-manipulation mouse handles for edit-attach — the gizmo is the creation-pose's
   frame made visible and draggable: 3 translate arrows (heading/right/up), 3 rotation
   rings (around up/right/heading), 3 stretch handles (per axis), combined and always
   visible together (not modal). See dev-docs/brief-edit-attach-handles.md.

   Built for edit-attach specifically (no dependency on its session state — this module
   only knows about a pose, options, and gesture callbacks) but kept as a self-contained
   THREE-owning module like viewport/core.cljs itself, rather than the plain-data style
   edit-path/edit-image-board use — the gizmo is a combined multi-part 3D widget with
   hover state and mode-dependent styling, closer in spirit to viewport's own
   create-turtle-indicator than to a 2D node editor.

   Coordinate convention: everything (pose, deltas, hit-test results) is
   world-group-local, matching viewport/raycast-plane-point & friends. Local axis
   layout of the gizmo's own THREE.Group: local +X = heading, +Y = up, +Z = right
   (right = normalize(cross(heading, up)), per impl.cljs's shift/rotate-creation-pose —
   this is a right-handed basis since heading × up = right by definition), so a single
   group-level quaternion (built once per pose via Matrix4.makeBasis) reorients all
   children together; each child's own LOCAL orientation (arrow along local axis N,
   ring normal to local axis N) is fixed at construction time and never recomputed."
  (:require ["three" :as THREE]
            [ridley.viewport.core :as viewport]
            [ridley.math :as m]))

;; ============================================================
;; Pure math: drag → command-value quantization
;; ============================================================
;; (Ray-line / ray-plane-angle math lives in ridley.math — closest-point-on-line,
;; signed-angle-around-axis — shared with viewport/raycast-line-point. See
;; test/ridley/editor/gizmo_test.cljs, written before this file per the brief.)

(defn snap-round
  "Round `v` to the nearest multiple of `step`. step <= 0 is a no-op (identity) —
   used for translate/rotate drags with the session's step/angle-step grid."
  [v step]
  (if (or (nil? step) (<= step 0))
    v
    (* step (Math/round (/ v step)))))

(defn free-round
  "Round `v` to one decimal place — the rounding applied to a free (unsnapped, e.g.
   Shift-held) drag before it's ever written into the command list, so generated code
   never shows a value like (f 12.3847)."
  [v]
  (/ (Math/round (* v 10)) 10))

(defn snap-scale-factor
  "Snap a multiplicative stretch factor to the nearest integer power of `scale-step`
   (the same grid repeated keyboard stretch presses walk, e.g. scale-step 1.1 →
   ×1.1, ×1.21, ×0.909…). Degenerate inputs (factor <= 0, scale-step <= 1) pass
   through unchanged."
  [factor scale-step]
  (if (or (nil? factor) (<= factor 0) (nil? scale-step) (<= scale-step 1))
    factor
    (let [n (Math/round (/ (Math/log factor) (Math/log scale-step)))]
      (Math/pow scale-step n))))

(defn- round-to-step
  "Round `v` to a multiple of `step` without accumulating float noise in the
   result (unlike step*round(v/step)): scales by 1/step, rounds, scales back, so
   round-to-step 1.234 0.01 → 1.23 exactly (as a 2-decimal double), never
   1.2300000000000002 which would leak into generated code."
  [v step]
  (let [inv (/ 1.0 step)]
    (/ (Math/round (* v inv)) inv)))

(defn- dist2
  "Squared Euclidean distance between two [x y z] points (no sqrt — used only to
   pick the nearest of a face's three vertices to the impact point)."
  [[ax ay az] [bx by bz]]
  (let [dx (- ax bx) dy (- ay by) dz (- az bz)]
    (+ (* dx dx) (* dy dy) (* dz dz))))

(defn nearest-point
  "The point in `pts` closest (3D) to `target`."
  [target pts]
  (apply min-key #(dist2 target %) pts))

(defn closest-point-on-triangle
  "The point on triangle [a b c] closest to point `p`, clamped to the triangle
   (its interior, edges, and vertices) — the foot of the perpendicular from `p`
   when that lands inside the face, otherwise the nearest edge/vertex. Voronoi-
   region method (Ericson, Real-Time Collision Detection §5.1.5). Used by Alt+snap:
   'move the pose to the point of the clicked face nearest the current pose'."
  [p [a b c]]
  (let [ab (m/v- b a)
        ac (m/v- c a)
        ap (m/v- p a)
        d1 (m/dot ab ap)
        d2 (m/dot ac ap)]
    (if (and (<= d1 0) (<= d2 0))
      a                                                   ; vertex region A
      (let [bp (m/v- p b)
            d3 (m/dot ab bp)
            d4 (m/dot ac bp)]
        (if (and (>= d3 0) (<= d4 d3))
          b                                               ; vertex region B
          (let [vc (- (* d1 d4) (* d3 d2))]
            (if (and (<= vc 0) (>= d1 0) (<= d3 0))
              (m/v+ a (m/v* ab (/ d1 (- d1 d3))))         ; edge AB
              (let [cp (m/v- p c)
                    d5 (m/dot ab cp)
                    d6 (m/dot ac cp)]
                (if (and (>= d6 0) (<= d5 d6))
                  c                                       ; vertex region C
                  (let [vb (- (* d5 d2) (* d1 d6))]
                    (if (and (<= vb 0) (>= d2 0) (<= d6 0))
                      (m/v+ a (m/v* ac (/ d2 (- d2 d6)))) ; edge AC
                      (let [va (- (* d3 d6) (* d5 d4))]
                        (if (and (<= va 0) (>= (- d4 d3) 0) (>= (- d5 d6) 0))
                          (m/v+ b (m/v* (m/v- c b)        ; edge BC
                                        (/ (- d4 d3) (+ (- d4 d3) (- d5 d6)))))
                          (let [denom (/ 1.0 (+ va vb vc)) ; interior
                                v (* vb denom)
                                w (* vc denom)]
                            (m/v+ a (m/v+ (m/v* ab v) (m/v* ac w)))))))))))))))))

(defn snap-delta-commands
  "Pure core of origin-mode snap (brief-gizmo-polish.md §2): the cp-* commands that
   slide the attached geometry so the material point currently at world-local
   `target` lands on the creation-pose position `pivot`. The components of
   (target - pivot) resolved on the pose's own {heading, right, up} frame ARE the
   cp-f/cp-rt/cp-u values directly — the sign follows the pose, exactly like
   impl.cljs's shift-creation-pose (verified: 'il segno segue la pose'). Each value
   is rounded to `round-step` (0.01, finer than the drag grid — a click aims at an
   exact target). Only non-zero components are emitted, so a click dead-on an axis
   yields fewer than three commands (fixed rt, u, f order)."
  [pose pivot target round-step]
  (let [h (m/normalize (:heading pose))
        u (m/normalize (:up pose))
        r (m/normalize (m/cross h u))
        delta (m/v- target pivot)
        vr (round-to-step (m/dot delta r) round-step)
        vu (round-to-step (m/dot delta u) round-step)
        vf (round-to-step (m/dot delta h) round-step)]
    (cond-> []
      (not (zero? vr)) (conj [:cp-rt vr])
      (not (zero? vu)) (conj [:cp-u vu])
      (not (zero? vf)) (conj [:cp-f vf]))))

;; ============================================================
;; Handle vocabulary
;; ============================================================

(def ^:private axis-color
  "A3: same axis colors as viewport's turtle indicator (create-turtle-indicator)."
  {:h 0x00ffaa   ; heading — body/cone
   :r 0x00ddff   ; right — wings
   :u 0xffaa00}) ; up — tail fin

(def ^:private handle-info
  "handle-kw -> {:kind :translate|:rotate|:scale, :axis-key :h|:r|:u (which pose
   basis vector this handle acts along/around), :cmd (object-mode DSL command),
   :cp-cmd (origin-mode command, nil where none exists — stretch has no cp-*)}."
  {:arrow-f    {:kind :translate :axis-key :h :cmd :f  :cp-cmd :cp-f}
   :arrow-rt   {:kind :translate :axis-key :r :cmd :rt :cp-cmd :cp-rt}
   :arrow-u    {:kind :translate :axis-key :u :cmd :u  :cp-cmd :cp-u}
   :ring-th    {:kind :rotate    :axis-key :u :cmd :th :cp-cmd :cp-th}
   :ring-tv    {:kind :rotate    :axis-key :r :cmd :tv :cp-cmd :cp-tv}
   :ring-tr    {:kind :rotate    :axis-key :h :cmd :tr :cp-cmd :cp-tr}
   :stretch-f  {:kind :scale     :axis-key :h :cmd :stretch-f  :cp-cmd nil}
   :stretch-rt {:kind :scale     :axis-key :r :cmd :stretch-rt :cp-cmd nil}
   :stretch-u  {:kind :scale     :axis-key :u :cmd :stretch-u  :cp-cmd nil}})

(defn- pose-basis
  "{:h :r :u} orthonormal world-group-local basis for `pose`. right = heading × up,
   per impl.cljs's shift-creation-pose/rotate-creation-pose — must match exactly or
   drags feel mirrored relative to the keyboard layer."
  [pose]
  (let [h (m/normalize (:heading pose))
        u (m/normalize (:up pose))
        r (m/normalize (m/cross h u))]
    {:h h :r r :u u}))

(defn- material-basis
  "Like pose-basis, but reads :material-heading/:material-up (brief-stretch-
   material-frame.md — impl.cljs's accumulated cp-th/tv/tr offset resolved to world
   vectors) with a fallback to :heading/:up when absent, so this is a no-op for any
   pose that never went through that fix (REPL previews, panels, etc)."
  [pose]
  (let [h (m/normalize (or (:material-heading pose) (:heading pose)))
        u (m/normalize (or (:material-up pose) (:up pose)))
        r (m/normalize (m/cross h u))]
    {:h h :r r :u u}))

(defn- handle-axis
  "The world-group-local direction/rotation-axis vector for `handle-kw` at `pose`.
   Stretch handles act along the mesh's MATERIAL axes (brief-stretch-material-
   frame.md); arrows/rings stay on the pose axes."
  [pose handle-kw]
  (let [{:keys [axis-key kind]} (handle-info handle-kw)
        basis (if (= kind :scale) (material-basis pose) (pose-basis pose))]
    (case axis-key :h (:h basis) :r (:r basis) :u (:u basis))))

(defn- handle-color [handle-kw]
  (axis-color (:axis-key (handle-info handle-kw))))

;; ============================================================
;; THREE construction
;; ============================================================

(def ^:private viz
  "Single named tuning block for the gizmo's look (brief-gizmo-polish.md §1):
   opacities, thicknesses, radii, lengths — so a look tweak is a diff of a few
   lines in one place. Hit-zone sizes live here too but are NOT shrunk for looks:
   grab ergonomics are independent of visual heft (brief §1, rejected alternative).
   Final values are dialed in by hand in the browser with Vincenzo; these are only
   a lighter-than-before starting point."
  {;; Constant-apparent-size factor (A4) — same distance*base-scale technique as
   ;; viewport's update-turtle-indicator-scale. Shared by gizmo group + snap marker.
   :base-scale          0.045
   ;; Opacities — idle handles are see-through so the mesh stays readable through
   ;; the gizmo; only the hovered handle goes fully opaque (brief §1).
   :opacity-idle        0.35
   :opacity-hover       1.0
   :opacity-stretch-off 0.2      ; stretch handles in origin mode (no cp-stretch → inert)
   ;; Translate arrows — thin shafts, small heads
   :arrow-shaft-len     4.0
   :arrow-cone-len      1.0
   :arrow-shaft-r       0.05
   :arrow-cone-r        0.20
   :arrow-hit-r         0.45     ; hit-zone — kept fat for grab, but trimmed with the visuals
   ;; Rotation rings
   :ring-radius         3.4
   :ring-tube-r         0.04
   :ring-hit-tube-r     0.4      ; hit-zone
   ;; Stretch handles
   :stretch-dist        6.0
   :stretch-half        0.26
   :stretch-hit-half    0.65     ; hit-zone
   ;; Origin-mode markers
   :origin-marker-r     0.25
   :snap-marker-r       0.18
   ;; Origin-mode vertex/face-center snap behavior
   :click-slop-px       4        ; a pointer that moves more than this is a drag, not a click
   :snap-round          0.01})   ; cp value rounding — finer than the drag grid (exact target)

(def ^:private base-scale (:base-scale viz))

;; Local-axis-key -> {:dir [x y z] local direction, :arrow-quat, :ring-quat}.
;; Cylinder/Cone geometry default axis is local +Y; Torus geometry default normal is
;; local +Z — these constant quaternions realign a freshly built part (at the origin,
;; identity rotation) onto the gizmo group's local +X/+Y/+Z once, at construction.
(defn- local-axis-info []
  {:h {:dir [1 0 0]
       :arrow-quat (.setFromUnitVectors (THREE/Quaternion.) (THREE/Vector3. 0 1 0) (THREE/Vector3. 1 0 0))
       :ring-quat  (.setFromUnitVectors (THREE/Quaternion.) (THREE/Vector3. 0 0 1) (THREE/Vector3. 1 0 0))}
   :u {:dir [0 1 0]
       :arrow-quat (THREE/Quaternion.)
       :ring-quat  (.setFromUnitVectors (THREE/Quaternion.) (THREE/Vector3. 0 0 1) (THREE/Vector3. 0 1 0))}
   :r {:dir [0 0 1]
       :arrow-quat (.setFromUnitVectors (THREE/Quaternion.) (THREE/Vector3. 0 1 0) (THREE/Vector3. 0 0 1))
       :ring-quat  (THREE/Quaternion.)}})

(defn- vec3 [[x y z]] (THREE/Vector3. x y z))

(defn- world-dir->group-local
  "Convert a world-group-local direction vector into `group`'s own local frame
   (undo the group's current orientation). Used to position stretch handles on the
   material axes while the group itself stays oriented on the pose axes."
  [^js group world-dir]
  (let [v (vec3 world-dir)
        inv (.invert (.clone (.-quaternion group)))]
    (.applyQuaternion v inv)
    [(.-x v) (.-y v) (.-z v)]))

(defn- pose-quaternion
  "Group-level quaternion realizing local +X=heading, +Y=up, +Z=right for `pose`."
  [pose]
  (let [{:keys [h r u]} (pose-basis pose)
        mat (.makeBasis (THREE/Matrix4.) (vec3 h) (vec3 u) (vec3 r))]
    (.setFromRotationMatrix (THREE/Quaternion.) mat)))

(defn- make-visible-material [color]
  (THREE/MeshBasicMaterial. #js {:color color :transparent true
                                 :opacity (:opacity-idle viz) :depthTest false}))

(defn- make-hit-material []
  ;; visible:true (raycast skips invisible objects) but fully transparent — the
  ;; "fattened invisible hit-zone" the brief asks for.
  (THREE/MeshBasicMaterial. #js {:color 0xffffff :transparent true :opacity 0 :depthWrite false :depthTest false}))

(defn- build-arrow!
  "Add one translate-arrow (visible shaft+cone, invisible fattened hit-zone cylinder)
   to `group`, along local axis `axis-key`. Returns {:visible <Group> :hit <Mesh>}."
  [group handle-kw axis-key color]
  (let [{:keys [dir arrow-quat]} (axis-key (local-axis-info))
        {:keys [arrow-shaft-len arrow-cone-len arrow-shaft-r arrow-cone-r arrow-hit-r]} viz
        [dx dy dz] dir
        total-len (+ arrow-shaft-len arrow-cone-len)
        mid (/ total-len 2)
        shaft (THREE/Mesh. (THREE/CylinderGeometry. arrow-shaft-r arrow-shaft-r arrow-shaft-len 10)
                           (make-visible-material color))
        cone (THREE/Mesh. (THREE/ConeGeometry. arrow-cone-r arrow-cone-len 10)
                          (make-visible-material color))
        hit (THREE/Mesh. (THREE/CylinderGeometry. arrow-hit-r arrow-hit-r total-len 8)
                         (make-hit-material))
        visible-grp (THREE/Group.)]
    (.copy (.-quaternion shaft) arrow-quat)
    (.set (.-position shaft) (* dx (/ arrow-shaft-len 2)) (* dy (/ arrow-shaft-len 2)) (* dz (/ arrow-shaft-len 2)))
    (.copy (.-quaternion cone) arrow-quat)
    (.set (.-position cone)
          (* dx (+ arrow-shaft-len (/ arrow-cone-len 2)))
          (* dy (+ arrow-shaft-len (/ arrow-cone-len 2)))
          (* dz (+ arrow-shaft-len (/ arrow-cone-len 2))))
    (.copy (.-quaternion hit) arrow-quat)
    (.set (.-position hit) (* dx mid) (* dy mid) (* dz mid))
    (set! (.. hit -userData -gizmoHandle) (name handle-kw))
    (.add visible-grp shaft)
    (.add visible-grp cone)
    (.add group visible-grp)
    (.add group hit)
    {:visible visible-grp :hit hit}))

(defn- build-ring!
  "Add one rotation-ring (visible torus, invisible fattened hit-zone torus) to
   `group`, lying in the plane perpendicular to local axis `axis-key`. Returns
   {:visible <Mesh> :hit <Mesh>}."
  [group handle-kw axis-key color]
  (let [{:keys [ring-quat]} (axis-key (local-axis-info))
        {:keys [ring-radius ring-tube-r ring-hit-tube-r]} viz
        visible (THREE/Mesh. (THREE/TorusGeometry. ring-radius ring-tube-r 10 32)
                             (make-visible-material color))
        hit (THREE/Mesh. (THREE/TorusGeometry. ring-radius ring-hit-tube-r 8 32)
                         (make-hit-material))]
    (.copy (.-quaternion visible) ring-quat)
    (.copy (.-quaternion hit) ring-quat)
    (set! (.. hit -userData -gizmoHandle) (name handle-kw))
    (.add group visible)
    (.add group hit)
    {:visible visible :hit hit}))

(def ^:private stretch-dist (:stretch-dist viz))

(defn- build-stretch!
  "Add one stretch-handle (small visible cube, invisible fattened hit-zone cube)
   beyond the arrow tip on local axis `axis-key`. Returns {:visible :hit}."
  [group handle-kw axis-key color]
  (let [{:keys [dir]} (axis-key (local-axis-info))
        {:keys [stretch-half stretch-hit-half]} viz
        [dx dy dz] dir
        [px py pz] [(* dx stretch-dist) (* dy stretch-dist) (* dz stretch-dist)]
        visible (THREE/Mesh. (THREE/BoxGeometry. (* 2 stretch-half) (* 2 stretch-half) (* 2 stretch-half))
                             (make-visible-material color))
        hit (THREE/Mesh. (THREE/BoxGeometry. (* 2 stretch-hit-half) (* 2 stretch-hit-half) (* 2 stretch-hit-half))
                         (make-hit-material))]
    (.set (.-position visible) px py pz)
    (.set (.-position hit) px py pz)
    (set! (.. hit -userData -gizmoHandle) (name handle-kw))
    (.add group visible)
    (.add group hit)
    {:visible visible :hit hit}))

(defn- build-origin-marker!
  "A bright marker at the group's own origin (= pose position), shown only in origin
   mode so the mode change is visually unmistakable (brief §2/§5) — the rest of the
   gizmo looks identical in both modes otherwise."
  [group]
  (let [mesh (THREE/Mesh. (THREE/SphereGeometry. (:origin-marker-r viz) 16 16)
                          (THREE/MeshBasicMaterial. #js {:color 0xffffff :transparent true
                                                         :opacity 0.9 :depthTest false}))]
    (set! (.-visible mesh) false)
    (.add group mesh)
    mesh))

(defn- build-snap-marker!
  "A small bright sphere marking the origin-mode snap candidate (vertex or face
   point) under the pointer. Lives directly under the world-group (a sibling of the
   gizmo group, since it sits on an arbitrary mesh point, not on the pose), kept at
   constant apparent size by rescale-for-camera!. Hidden unless a candidate is live
   (brief §2 hover feedback). WRAPPED IN A GROUP on purpose: a re-eval runs
   clear-geometry, which strips every bare Mesh/LineSegments from the world-group —
   a Group is left alone (that's also why the gizmo group survives), so the marker
   must not be a bare Mesh or it vanishes after the first commit."
  []
  (let [mesh (THREE/Mesh. (THREE/SphereGeometry. 1 12 12)
                          (THREE/MeshBasicMaterial. #js {:color 0xffff66 :transparent true
                                                         :opacity 0.95 :depthTest false}))
        grp (THREE/Group.)]
    (set! (.-renderOrder mesh) 1000)
    (.add grp mesh)
    (set! (.-visible grp) false)
    grp))

(defn- build-gizmo-group!
  "Build the full THREE.Group for `pose`. Returns {:group :hitzones :visible-parts
   :origin-marker :snap-marker}."
  [pose]
  (let [group (THREE/Group.)]
    (let [[px py pz] (:position pose)]
      (.set (.-position group) px py pz))
    (.copy (.-quaternion group) (pose-quaternion pose))
    (let [f   (build-arrow! group :arrow-f  :h (axis-color :h))
          rt  (build-arrow! group :arrow-rt :r (axis-color :r))
          u   (build-arrow! group :arrow-u  :u (axis-color :u))
          th  (build-ring!  group :ring-th  :u (axis-color :u))
          tv  (build-ring!  group :ring-tv  :r (axis-color :r))
          tr  (build-ring!  group :ring-tr  :h (axis-color :h))
          sf  (build-stretch! group :stretch-f  :h (axis-color :h))
          srt (build-stretch! group :stretch-rt :r (axis-color :r))
          su  (build-stretch! group :stretch-u  :u (axis-color :u))
          origin-marker (build-origin-marker! group)
          parts {:arrow-f f :arrow-rt rt :arrow-u u
                 :ring-th th :ring-tv tv :ring-tr tr
                 :stretch-f sf :stretch-rt srt :stretch-u su}]
      {:group group
       :origin-marker origin-marker
       :snap-marker (build-snap-marker!)
       :hitzones (into {} (map (fn [[k v]] [k (:hit v)])) parts)
       :visible-parts (into {} (map (fn [[k v]] [k (:visible v)])) parts)})))

(defn- reposition-group!
  [group pose]
  (let [[px py pz] (:position pose)]
    (.set (.-position group) px py pz)
    (.copy (.-quaternion group) (pose-quaternion pose))))

;; ============================================================
;; Session state
;; ============================================================

(defonce ^:private gstate (atom nil))

(defn active? [] (some? @gstate))

(defn- rescale-for-camera!
  "Per-frame callback (A4): keep the gizmo's apparent screen size constant as the
   camera moves, same technique as viewport's turtle-indicator."
  [^js camera]
  (when-let [{:keys [group ^js snap-marker]} @gstate]
    (let [wp (THREE/Vector3.)]
      (.getWorldPosition group wp)
      (let [dist (.distanceTo wp (.-position camera))
            s (* dist base-scale)]
        (.set (.-scale group) s s s)))
    ;; The snap marker is a world-group sibling on an arbitrary mesh point, so it
    ;; needs its own distance-based rescale to stay a constant apparent size.
    (when (and snap-marker (.-visible snap-marker))
      (let [wp (THREE/Vector3.)]
        (.getWorldPosition snap-marker wp)
        (let [s (* (.distanceTo wp (.-position camera)) base-scale (:snap-marker-r viz))]
          (.set (.-scale snap-marker) s s s))))))

(defn- reposition-stretch-handles!
  "Move the 3 stretch handles to sit on the CURRENT material axes (brief-stretch-
   material-frame.md), converted into the group's own local frame since the group
   itself stays oriented on the pose axes (arrows/rings don't move). Called after
   every pose/material update — group.quaternion must already be current."
  []
  (when-let [{:keys [group pose visible-parts hitzones]} @gstate]
    (let [{:keys [h r u]} (material-basis pose)
          axis-dirs {:stretch-f  (world-dir->group-local group h)
                     :stretch-rt (world-dir->group-local group r)
                     :stretch-u  (world-dir->group-local group u)}]
      (doseq [[handle-kw [dx dy dz]] axis-dirs]
        (let [px (* dx stretch-dist) py (* dy stretch-dist) pz (* dz stretch-dist)]
          (when-let [^js v (visible-parts handle-kw)] (.set (.-position v) px py pz))
          (when-let [^js hz (hitzones handle-kw)] (.set (.-position hz) px py pz)))))))

(defn- set-mesh-color! [^js grp color]
  (when grp
    (.traverse grp (fn [^js c]
                     (when-let [mat (.-material c)]
                       (.set (.-color mat) color))))))

(defn- set-mesh-opacity! [^js grp opacity]
  (when grp
    (.traverse grp (fn [^js c]
                     (when-let [mat (.-material c)]
                       (set! (.-opacity mat) opacity))))))

(defn- handle-idle-opacity
  "Resting (non-hover) opacity for `handle-kw` in `mode`: stretch handles are dimmed
   in origin mode (no cp-stretch exists → inert), everything else is the see-through
   idle level (brief §1)."
  [handle-kw mode]
  (if (and (= mode :origin)
           (#{:stretch-f :stretch-rt :stretch-u} handle-kw))
    (:opacity-stretch-off viz)
    (:opacity-idle viz)))

(defn- apply-hover! [handle-kw]
  (let [{:keys [visible-parts hover mode]} @gstate]
    (when (and hover (not= hover handle-kw))
      (set-mesh-color! (visible-parts hover) (handle-color hover))
      (set-mesh-opacity! (visible-parts hover) (handle-idle-opacity hover mode)))
    (when (and handle-kw (not= hover handle-kw))
      (set-mesh-color! (visible-parts handle-kw) 0xffffff)
      (set-mesh-opacity! (visible-parts handle-kw) (:opacity-hover viz)))
    (swap! gstate assoc :hover handle-kw)))

(defn- apply-translate-preview!
  "Move the gizmo group by `value` along `axis` (world-group-local). In object mode
   the mesh preview moves rigidly with it (plain f/rt/u are turtle-carries-mesh
   translations — impl.cljs replay-path-commands); in origin mode only the pose
   marker (this group) moves for the drag's 'intent view' — the mesh preview is left
   untouched until the real re-eval snaps everything to the world-true state."
  [axis value]
  (let [{:keys [group pose mode]} @gstate
        [px py pz] (:position pose)
        [ax ay az] axis
        d [(* ax value) (* ay value) (* az value)]]
    (.set (.-position group) (+ px (nth d 0)) (+ py (nth d 1)) (+ pz (nth d 2)))
    (when (= mode :object)
      (viewport/nudge-preview! {:translate d}))))

(defn- apply-rotate-preview!
  "Rotate the gizmo group in place (about its own origin — the pivot for a plain
   th/tv/tr IS the pose position, which is where the group already sits) by
   `angle-deg` around `axis`, and in object mode rotate the mesh preview by the same
   amount around `pivot` (a rigid rotation, since plain th/tv/tr turn mesh+
   creation-pose together — impl.cljs replay-path-commands). Origin mode (cp-th et
   al) leaves the mesh preview untouched, same rationale as apply-translate-preview!."
  [pivot axis angle-deg]
  (let [{:keys [pose mode group]} @gstate
        delta-q (.setFromAxisAngle (THREE/Quaternion.) (vec3 (m/normalize axis))
                                   (* angle-deg (/ Math/PI 180)))
        base-q (pose-quaternion pose)]
    (.copy (.-quaternion group) (.multiply delta-q base-q))
    (when (= mode :object)
      (viewport/nudge-preview! {:rotate {:pivot pivot :axis axis :deg angle-deg}}))))

(defn- apply-scale-preview!
  "Scale the mesh preview by `factor` along `axis`, pivoted at `pivot` (the pose
   position — impl.cljs's stretch-* pivots at the current turtle position but never
   moves it, so the gizmo group itself never needs to move for a stretch drag)."
  [pivot axis factor]
  (viewport/nudge-preview! {:scale {:pivot pivot :axis axis :factor factor}}))

;; ============================================================
;; Origin-mode vertex / face-center snap (brief-gizmo-polish.md §2)
;; ============================================================

(defn- snap-candidate
  "The world-local point to snap to for a raycast-mesh-face result `face`: with
   `alt?`, the point of the hit face nearest the current pose `pivot` (foot of the
   perpendicular, clamped to the triangle); otherwise the vertex of the hit face
   nearest the impact point. nil when no face was hit (pointer off the mesh). No
   pixel threshold — the marker should always show where a click would land, so the
   aim is discoverable even mid-face (originally gated by a screen-px threshold,
   which hid the feedback until the pointer was already on a vertex — reverted per
   in-browser feedback)."
  [face alt? pivot]
  (when face
    (if alt?
      (closest-point-on-triangle pivot (:vertices face))
      (nearest-point (:point face) (:vertices face)))))

(defn- show-snap-marker! [[x y z]]
  (when-let [^js m (:snap-marker @gstate)]
    (.set (.-position m) x y z)
    (set! (.-visible m) true)))

(defn- hide-snap-marker! []
  (when-let [^js m (:snap-marker @gstate)]
    (when (.-visible m) (set! (.-visible m) false))))

(defn- update-snap-marker!
  "Throttled origin-mode hover feedback: raycast the mesh under the pointer, place
   the snap marker on the current candidate (nearest vertex, or with Alt the face
   point nearest the pose), or hide it when there's none. Same calc as the click so
   the marker shows exactly where a click would land."
  [^js e]
  (let [now (js/Date.now)]
    (when (>= (- now (or (:snap-marker-last @gstate) 0)) 33)
      (swap! gstate assoc :snap-marker-last now)
      (let [pivot (:position (:pose @gstate))]
        (if-let [target (snap-candidate (viewport/raycast-mesh-face e) (.-altKey e) pivot)]
          (show-snap-marker! target)
          (hide-snap-marker!))))))

(defn- perform-snap!
  "Execute an origin-mode snap click: land the creation-pose on the candidate under
   the pointer by emitting the corresponding cp-* commands (one gesture each, so up
   to three undo steps — accepted, brief §2). No-op if there's no candidate."
  [^js e alt?]
  (when-let [{:keys [pose on-commit]} @gstate]
    (when-let [target (snap-candidate (viewport/raycast-mesh-face e) alt? (:position pose))]
      (let [cmds (snap-delta-commands pose (:position pose) target (:snap-round viz))]
        (when on-commit
          (doseq [[cmd val] cmds] (on-commit cmd val)))))))

;; ============================================================
;; Live drag readout (brief follow-up 2026-07-09): a small tooltip that
;; follows the pointer during a handle drag, showing the value the next
;; command will carry — and whether the step-grid snap is being overridden.
;; ============================================================

(defn- fmt-num
  "Format a drag value for display: round to 2 decimals, drop a trailing .0."
  [v]
  (let [r (/ (Math/round (* v 100)) 100)]
    (if (== r (Math/round r)) (str (Math/round r)) (str r))))

(defn- drag-readout-text
  "The '(cmd value)' label for a live drag: the exact command on-pointer-up would
   emit (cp-* in origin mode; stretch-* / degrees / plain per kind), plus a 'libero'
   tag when the snap grid is being overridden (Shift)."
  [handle-kw kind mode value free?]
  (let [{:keys [cmd cp-cmd]} (handle-info handle-kw)
        c (if (and (= mode :origin) cp-cmd (not= kind :scale)) cp-cmd cmd)
        body (case kind
               :rotate (str (fmt-num value) "°")
               :scale  (str "×" (fmt-num value))
               (fmt-num value))]
    (str "(" (name c) " " body ")" (when free? "  libero"))))

(defn- build-readout-el! []
  (let [el (.createElement js/document "div")]
    (set! (.-className el) "gizmo-drag-readout")
    (let [s (.-style el)]
      (set! (.-position s) "fixed")
      (set! (.-pointerEvents s) "none")
      (set! (.-zIndex s) "10000")
      (set! (.-padding s) "2px 6px")
      (set! (.-borderRadius s) "4px")
      (set! (.-font s) "12px/1.4 monospace")
      (set! (.-background s) "rgba(20,20,24,0.88)")
      (set! (.-color s) "#ffef9f")
      (set! (.-border s) "1px solid rgba(255,255,255,0.18)")
      (set! (.-whiteSpace s) "nowrap")
      (set! (.-display s) "none"))
    (.appendChild (.-body js/document) el)
    el))

(defn- show-readout! [^js e text]
  (when-let [^js el (:readout-el @gstate)]
    (set! (.-textContent el) text)
    (let [s (.-style el)]
      (set! (.-left s) (str (+ (.-clientX e) 14) "px"))
      (set! (.-top s) (str (+ (.-clientY e) 14) "px"))
      (set! (.-display s) "block"))))

(defn- hide-readout! []
  (when-let [^js el (:readout-el @gstate)]
    (set! (.. el -style -display) "none")))

;; ============================================================
;; Pointer handling
;; ============================================================

(defn- hitzone-handle
  "hit is one of raycast-objects' {:object :point :distance} maps, not a raw THREE
   hit record — :object needs keyword access, only its value is JS interop."
  [hit]
  (keyword (.. ^js (:object hit) -userData -gizmoHandle)))

(defn- live-hitzones
  "The hit-zones eligible for hover/grab right now: in origin mode the (inert)
   stretch handles are excluded so a click near one still reaches vertex snap."
  [hitzones origin?]
  (if origin?
    (into {} (remove (fn [[k _]] (#{:stretch-f :stretch-rt :stretch-u} k))) hitzones)
    hitzones))

(defn- on-pointer-down [^js e]
  (when-let [{:keys [hitzones pose mode]} @gstate]
    (let [origin? (= mode :origin)
          hits (viewport/raycast-objects e (vals (live-hitzones hitzones origin?)))]
      (if (seq hits)
        (do
          (.preventDefault e)
          (let [handle-kw (hitzone-handle (first hits))
                {:keys [kind]} (handle-info handle-kw)
                axis (handle-axis pose handle-kw)
                pivot (:position pose)]
            (case kind
              :translate
              (when-let [grab-point (viewport/raycast-line-point e pivot axis)]
                (viewport/set-controls-enabled! false)
                (swap! gstate assoc :drag
                       {:handle handle-kw :kind kind :axis axis :pivot pivot
                        :grab-point grab-point :value 0}))
              :rotate
              (when-let [grab-point (viewport/raycast-plane-point e pivot axis)]
                (viewport/set-controls-enabled! false)
                (swap! gstate assoc :drag
                       {:handle handle-kw :kind kind :axis axis :pivot pivot
                        :grab-vec (m/v- grab-point pivot) :value 0}))
              :scale
              (when-not origin?
                (when-let [grab-point (viewport/raycast-line-point e pivot axis)]
                  (let [grab-dist (m/dot (m/v- grab-point pivot) axis)]
                    ;; grab-dist should be sizable — the handle sits well beyond the
                    ;; pivot — but guard the divide in on-pointer-move regardless.
                    (when (> (js/Math.abs grab-dist) 0.01)
                      (viewport/set-controls-enabled! false)
                      (swap! gstate assoc :drag
                             {:handle handle-kw :kind kind :axis axis :pivot pivot
                              :grab-dist grab-dist :value 1})))))
              nil)))
        ;; No gizmo handle under the pointer. In origin mode, arm a possible
        ;; click-snap: record the down position so on-pointer-up can tell a click
        ;; (snap) from a drag (orbit — controls stay enabled, we don't touch them).
        (when origin?
          (swap! gstate assoc :maybe-snap {:x (.-clientX e) :y (.-clientY e)}))))))

(defn- on-pointer-move [^js e]
  (when-let [{:keys [drag hitzones pose step angle-step scale-step]} @gstate]
    (if drag
      (let [{:keys [handle kind axis pivot grab-point grab-vec grab-dist]} drag]
        (case kind
          :translate
          (when-let [cur (viewport/raycast-line-point e pivot axis)]
            (let [raw (m/dot (m/v- cur grab-point) axis)
                  val (if (.-shiftKey e) (free-round raw) (snap-round raw step))]
              (apply-translate-preview! axis val)
              (swap! gstate assoc-in [:drag :value] val)))
          :rotate
          (when-let [cur (viewport/raycast-plane-point e pivot axis)]
            (let [cur-vec (m/v- cur pivot)
                  raw-deg (* (m/signed-angle-around-axis grab-vec cur-vec axis) (/ 180 Math/PI))
                  val (if (.-shiftKey e) (free-round raw-deg) (snap-round raw-deg angle-step))]
              (apply-rotate-preview! pivot axis val)
              (swap! gstate assoc-in [:drag :value] val)))
          :scale
          (when-let [cur (viewport/raycast-line-point e pivot axis)]
            (let [cur-dist (m/dot (m/v- cur pivot) axis)
                  raw-factor (max 0.02 (/ cur-dist grab-dist))
                  val (if (.-shiftKey e) (free-round raw-factor) (snap-scale-factor raw-factor scale-step))]
              (apply-scale-preview! pivot axis val)
              (swap! gstate assoc-in [:drag :value] val)))
          nil)
        ;; Live readout: the value the next command will carry, following the pointer.
        (show-readout! e (drag-readout-text handle kind (:mode @gstate)
                                            (get-in @gstate [:drag :value])
                                            (.-shiftKey e))))
      (let [origin? (= (:mode @gstate) :origin)
            hits (viewport/raycast-objects e (vals (live-hitzones hitzones origin?)))
            top (when (seq hits) (hitzone-handle (first hits)))]
        (when (not= top (:hover @gstate))
          (apply-hover! top))
        ;; Origin-mode hover feedback: a marker on the vertex/face-center under the
        ;; pointer — but suppressed while hovering a gizmo handle (the handle wins).
        (when origin?
          (if top (hide-snap-marker!) (update-snap-marker! e)))))))

(defn- on-pointer-up [^js e]
  (when-let [{:keys [drag mode on-commit maybe-snap]} @gstate]
    (cond
      ;; Completed handle drag → commit its gesture (same path as a keyboard press).
      drag
      (do
        (let [{:keys [handle kind value]} drag
              {:keys [cmd cp-cmd axis-key]} (handle-info handle)]
          (if (= kind :scale)
            ;; Emit the same [:scale [a b c]] vector form the keyboard SCALE mode
            ;; already uses (not a bare :stretch-f scalar) — edit-attach's
            ;; compact-commands merges :scale vectors component-wise (product),
            ;; which is correct for a multiplicative factor; a bare scalar command
            ;; would instead hit the generic numeric merge (sum), which is wrong here.
            (when (and on-commit (not (== value 1)))
              (on-commit :scale (case axis-key :h [value 1 1] :r [1 value 1] :u [1 1 value])))
            (let [cmd-type (if (= mode :origin) cp-cmd cmd)]
              (when (and cmd-type on-commit (not (zero? value)))
                (on-commit cmd-type value)))))
        (hide-readout!)
        (viewport/set-controls-enabled! true)
        (swap! gstate assoc :drag nil))

      ;; Armed origin-mode click on the mesh body: snap only if the pointer barely
      ;; moved (a real click, not an orbit drag on empty space).
      (and maybe-snap (= mode :origin))
      (let [dx (- (.-clientX e) (:x maybe-snap))
            dy (- (.-clientY e) (:y maybe-snap))]
        (swap! gstate assoc :maybe-snap nil)
        (when (<= (Math/sqrt (+ (* dx dx) (* dy dy))) (:click-slop-px viz))
          (perform-snap! e (.-altKey e))))

      :else nil)))

(defn- on-pointer-leave [^js _e]
  ;; Pointer left the canvas — drop any snap hover marker so it never lingers
  ;; (brief §5: no residue when the pointer leaves the mesh/viewport).
  (hide-snap-marker!)
  (swap! gstate assoc :maybe-snap nil))

(defn- apply-mode-style!
  "Visually mark the mode change (brief §2/§5: not a hidden modifier, the gizmo's
   own rendering must make the switch obvious): show the origin marker only in
   origin mode, and reset every handle to its resting opacity for `mode` — dimming
   the (inert) stretch handles in origin mode, restoring the hovered handle to full
   opacity so a mode toggle mid-hover doesn't flatten it."
  [mode]
  (when-let [{:keys [origin-marker visible-parts hover]} @gstate]
    (when origin-marker (set! (.-visible origin-marker) (= mode :origin)))
    (doseq [[k mesh] visible-parts]
      (set-mesh-opacity! mesh (if (= k hover)
                                (:opacity-hover viz)
                                (handle-idle-opacity k mode))))))

;; ============================================================
;; Public API
;; ============================================================

(defn enter!
  "Build and show the gizmo at `pose`, start listening for drags on the viewport
   canvas. opts: {:mode :object|:origin :step :angle-step :scale-step}.
   callbacks: {:on-commit (fn [cmd-type value])} — called once per completed gesture,
   exactly like a keyboard arrow press (edit-attach's add-command!)."
  [pose opts callbacks]
  (let [{:keys [group hitzones visible-parts origin-marker snap-marker]} (build-gizmo-group! pose)
        mode (or (:mode opts) :object)
        wg (viewport/get-world-group)]
    (.add wg group)
    (.add wg snap-marker)
    (reset! gstate {:pose pose
                    :mode mode
                    :step (:step opts)
                    :angle-step (:angle-step opts)
                    :scale-step (:scale-step opts)
                    :group group
                    :hitzones hitzones
                    :visible-parts visible-parts
                    :origin-marker origin-marker
                    :snap-marker snap-marker
                    :hover nil
                    :drag nil
                    :maybe-snap nil
                    :snap-marker-last 0
                    :readout-el (build-readout-el!)
                    :on-commit (:on-commit callbacks)})
    (apply-mode-style! mode)
    (reposition-stretch-handles!)
    (viewport/lock-interaction! true)
    (viewport/register-frame-callback! :gizmo rescale-for-camera!)
    (let [canvas (viewport/get-canvas)]
      (.addEventListener canvas "pointerdown" on-pointer-down)
      (.addEventListener canvas "pointermove" on-pointer-move)
      (.addEventListener canvas "pointerup" on-pointer-up)
      (.addEventListener canvas "pointerleave" on-pointer-leave)
      (swap! gstate assoc :canvas canvas))))

(defn close!
  "Tear down the gizmo: listeners, per-frame callback, overlay geometry. Safe to call
   even if enter! was never called or already closed."
  []
  (when-let [{:keys [group canvas ^js snap-marker ^js readout-el]} @gstate]
    (viewport/unregister-frame-callback! :gizmo)
    (viewport/lock-interaction! false)
    (viewport/set-controls-enabled! true)
    (when (and readout-el (.-parentNode readout-el))
      (.removeChild (.-parentNode readout-el) readout-el))
    (when canvas
      (.removeEventListener canvas "pointerdown" on-pointer-down)
      (.removeEventListener canvas "pointermove" on-pointer-move)
      (.removeEventListener canvas "pointerup" on-pointer-up)
      (.removeEventListener canvas "pointerleave" on-pointer-leave))
    (when-let [wg (viewport/get-world-group)]
      (.remove wg group)
      (when snap-marker (.remove wg snap-marker)))
    (when snap-marker
      (.traverse snap-marker (fn [^js c]
                               (when-let [g (.-geometry c)] (.dispose g))
                               (when-let [mt (.-material c)] (.dispose mt)))))
    (.traverse group (fn [^js c]
                       (when-let [g (.-geometry c)] (.dispose g))
                       (when-let [mt (.-material c)] (.dispose mt))))
    (reset! gstate nil)))

(defn update-pose!
  "Sync the gizmo to a freshly re-evaluated creation-pose — called by edit-attach
   alongside viewport/update-turtle-pose after every commit. In object mode this is
   where the gizmo lands where the drag was headed; in origin mode (cp-* commands
   never move creation-pose) this is what snaps the 'intent view' pose marker back to
   its true, unmoved world position."
  [pose]
  (when @gstate
    (swap! gstate assoc :pose pose)
    (reposition-group! (:group @gstate) pose)
    (reposition-stretch-handles!)
    ;; Geometry just moved under a snap/commit — the marker's old vertex position is
    ;; now stale; hide it (it re-appears on the next hover recompute).
    (hide-snap-marker!)))

(defn set-mode!
  [mode]
  (when @gstate
    (swap! gstate assoc :mode mode)
    (apply-mode-style! mode)
    ;; The snap marker only makes sense in origin mode — drop it on the way out
    ;; (brief §5: no marker in object mode).
    (when (not= mode :origin) (hide-snap-marker!))))

(defn set-snap!
  [{:keys [step angle-step scale-step]}]
  (when @gstate
    (swap! gstate merge (cond-> {}
                          (some? step) (assoc :step step)
                          (some? angle-step) (assoc :angle-step angle-step)
                          (some? scale-step) (assoc :scale-step scale-step)))))
