(ns ridley.photogrammetry.bridge
  "Bridge between the editor's turtle poses ({:position :heading :up},
   editor-world) and the solver's camera poses ({:rvec :t}, box-local world —
   box-fit.cljs assumes the box centred at the origin, axis-aligned: 'the
   object frame IS the world frame').

   The box mesh (geometry/primitives.cljs's apply-transform) uses
   right = up×heading for its own local-x axis — the OPPOSITE of the
   'canonical' heading×up convention used elsewhere in the editor (e.g.
   edit_acquire.cljs's pose-basis, for the gizmo). box-basis follows the
   box's own convention: getting this sign wrong silently mirrors every pose
   the solver computes.

   editor->solver-pose doesn't care which side (camera or proxy) is 'the
   free one' — it only computes the RELATIVE pose. The two inverse
   directions are, by construction, asymmetric: solver-pose->camera needs
   the proxy known and fixed (photos 1..N-1, proxy frozen); solver-pose->proxy
   needs the camera known and fixed (photo 0, where the gizmo moves the proxy
   with the camera still)."
  (:require [ridley.math :as m]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.linalg :as la]))

(defn box-basis
  "{:ex :ey :ez} — world-space orthonormal basis of the box's own local axes
   at `proxy-pose`, in the mesh's actual convention (up×heading, up, heading)
   — NOT the gizmo's 'canonical' convention (heading×up). heading is trusted
   as-is; up is Gram-Schmidt'd against it (m/orthogonalize-up) rather than
   merely normalized — a pose built by hand (edit_acquire.cljs's fixed
   photo-0 vantage was one) can have heading/up merely close to
   perpendicular, which would otherwise turn every rigid transform through
   this basis into a shear instead of a rotation."
  [{:keys [heading up]}]
  (let [h (m/normalize heading)
        u (m/orthogonalize-up h up)]
    {:ex (m/normalize (m/cross u h))
     :ey u
     :ez h}))

(defn- to-local-point [{:keys [ex ey ez]} origin p]
  (let [d (m/v- p origin)]
    [(m/dot d ex) (m/dot d ey) (m/dot d ez)]))

(defn- to-local-dir [{:keys [ex ey ez]} v]
  [(m/dot v ex) (m/dot v ey) (m/dot v ez)])

(defn- from-local-point [{:keys [ex ey ez]} origin [lx ly lz]]
  (m/v+ origin (m/v+ (m/v* ex lx) (m/v+ (m/v* ey ly) (m/v* ez lz)))))

(defn- from-local-dir [{:keys [ex ey ez]} [lx ly lz]]
  (m/v+ (m/v* ex lx) (m/v+ (m/v* ey ly) (m/v* ez lz))))

(defn editor->solver-pose
  "camera-pose (world) expressed in proxy-pose's box-local frame, as a solver
   pose {:rvec :t} — via cam/look-at-pose, reusing its convention exactly as
   it is (never reinvented here)."
  [camera-pose proxy-pose]
  (let [basis (box-basis proxy-pose)
        origin (:position proxy-pose)
        eye-l (to-local-point basis origin (:position camera-pose))
        fwd-l (to-local-dir basis (m/normalize (:heading camera-pose)))
        up-l (to-local-dir basis (m/normalize (:up camera-pose)))]
    (cam/look-at-pose eye-l (m/v+ eye-l fwd-l) up-l)))

(defn solver-pose->camera
  "New camera pose (world), given the proxy FIXED and known. Photos 1..N-1."
  [solver-pose proxy-pose]
  (let [basis (box-basis proxy-pose)
        origin (:position proxy-pose)
        cam-center-l (cam/camera-center solver-pose)
        [_row0 row1 row2] (cam/rodrigues (:rvec solver-pose))
        fwd-l row2
        up-l (m/v* row1 -1.0)]
    {:position (from-local-point basis origin cam-center-l)
     :heading (m/normalize (from-local-dir basis fwd-l))
     :up (m/normalize (from-local-dir basis up-l))}))

(defn- align-rotation
  "Rotation R (rows-of-rows, like cam/rodrigues) such that R·a1=b1, R·a2=b2 —
   aligns the orthonormal pair (a1,a2) onto the orthonormal pair (b1,b2);
   a3/b3 are their respective cross products."
  [a1 a2 b1 b2]
  (let [a3 (m/cross a1 a2)
        b3 (m/cross b1 b2)
        a-transpose [a1 a2 a3]           ; = A^T directly (rows)
        b-mat (la/transpose [b1 b2 b3])] ; columns b1 b2 b3
    (la/mat*mat b-mat a-transpose)))

(defn solver-pose->proxy
  "New proxy pose (world), given the camera FIXED and known. Photo 0 — the
   inverse of solver-pose->camera: here the box-local->world rotation is NOT
   known ahead of time (it's what we're solving for), so it's reconstructed
   by aligning the (forward,up) pair known in box-local coordinates from the
   solver pose onto the same pair known in world coordinates from the fixed
   camera pose — not simply the transpose of an already-known basis, unlike
   solver-pose->camera."
  [solver-pose camera-pose]
  (let [cam-center-l (cam/camera-center solver-pose)
        [_row0 row1 row2] (cam/rodrigues (:rvec solver-pose))
        fwd-l row2
        up-l (m/v* row1 -1.0)
        heading-w (m/normalize (:heading camera-pose))
        up-w (m/orthogonalize-up heading-w (:up camera-pose))
        rot (align-rotation fwd-l up-l heading-w up-w)
        [_ex ey ez] (la/transpose rot) ; columns of rot = box's local axes in world
        origin (m/v- (:position camera-pose) (la/mat*vec rot cam-center-l))]
    {:position origin :heading (m/normalize ez) :up (m/normalize ey)}))

(defn local->world
  "Object/solver-frame point [lx ly lz] (box-fit's frame, x=ex y=ey z=ez) lifted
   to world at `proxy-pose`. The public inverse used by the retrace: points are
   solved and stored in the object frame (stable as the proxy moves), then lifted
   here for rendering."
  [proxy-pose local-pt]
  (from-local-point (box-basis proxy-pose) (:position proxy-pose) local-pt))

(defn world->local
  "Inverse of local->world: a WORLD point expressed in `proxy-pose`'s object/
   solver frame (box-fit's frame, x=ex y=ey z=ez). Used to bring a proxy's named
   marks (which ride the geometry in world) into the SAME object frame the box
   corners live in, so both feed the PnP solver identically."
  [proxy-pose world-pt]
  (to-local-point (box-basis proxy-pose) (:position proxy-pose) world-pt))

(defn world->local-dir
  "A WORLD direction expressed in `proxy-pose`'s object/solver frame — directions
   only, no translation. The oriented companion of world->local (points), used to
   carry a mark's face normal into the object frame."
  [proxy-pose v]
  (to-local-dir (box-basis proxy-pose) v))

(defn dims-from-mesh
  "Box extents [w h d] from `mesh`'s ACTUAL vertices (not from construction
   arguments — robust to however the box was parameterized), projected into
   proxy-pose's box-local frame."
  [mesh proxy-pose]
  (let [basis (box-basis proxy-pose)
        origin (:position proxy-pose)
        locals (mapv #(to-local-point basis origin %) (:vertices mesh))
        axis-vals (fn [i] (map #(nth % i) locals))
        extent (fn [i] (let [xs (axis-vals i)] (- (apply max xs) (apply min xs))))]
    [(extent 0) (extent 1) (extent 2)]))

(defn index-anchor?
  "True for an anchor id that is a ZERO-INDEX rather than a crown mark: `:zero`
   on a plate, `:zero-zp` … on a cage's six marked faces.

   Index marks ride in :anchors so they transport rigidly with the geometry for
   free (see plate-detect), but they are not marks to PICK — offering them would
   invite a correspondence against a disc whose whole job is to be the tiebreak
   that identifies the others. The name is the carrier because :anchors is a flat
   map with no room for a second kind of key, and a prefix survives the rigid
   transports and the calibration rewrites that replace the values."
  [id]
  (= 0 (.indexOf (name id) "zero")))

(defn pnp-target-points
  "The indexed PnP correspondence targets for `proxy-mesh` seen from
   `camera-pose` — source-agnostic so the whole picking gesture (pick indices,
   cycle, correspondence build) is identical whatever the proxy is:

   - a proxy carrying named marks (:anchors — a registration PLATE or CAGE)
     yields one target per mark, its :obj the mark's position in the object/
     solver frame (world->local, the SAME frame box corners live in, so the
     solver is unchanged), :world the mark at the current pose, :id the mark
     keyword;
   - otherwise the 8 box corners (bf/corners), :id the 0-7 corner index.

   Each: {:obj [x y z] :world [x y z] :visible? bool :id kw-or-int}. Colour and
   label are the UI layer's concern (edit_acquire), deliberately not here. Pure."
  [proxy-mesh camera-pose]
  (let [proxy-pose (:creation-pose proxy-mesh)
        ;; Per-mark front-facing is right for a CAGE and wrong for a PLATE, so
        ;; the proxy declares which it wants rather than being sniffed for.
        ;;
        ;; A plate's marks are all coplanar on ONE face and the user always
        ;; photographs THAT face, so every mark is offerable — and culling them
        ;; would actively break it, because before PnP there is no pose to test
        ;; against except the default vantage, which frames the plate's blank
        ;; underside: it would hide every mark and leave nothing to click on a
        ;; fresh session.
        ;;
        ;; A cage has no such face. Whatever the vantage, about half its marks
        ;; are turned away — never all of them, so the plate's failure cannot
        ;; happen — and offering those invites a click on a disc that is not in
        ;; the picture. Occlusion of a mark BY THE PART stays the user's 'o' key.
        cull? (boolean (:anchor-culling? proxy-mesh))
        cam-pos (:position camera-pose)]
    (if-let [marks (seq (sort-by key (remove (comp index-anchor? key)
                                             (:anchors proxy-mesh))))]
      (mapv (fn [[id pose]]
              (let [world (:position pose)]
                {:id id
                 :obj (world->local proxy-pose world)
                 :world world
                 ;; the mark's own printed-face normal in the OBJECT frame, so a
                 ;; solved pose can be tested against the physical fact that the
                 ;; disc was photographed — see camera-sees-marks?
                 :normal (some->> (:heading pose)
                                  (world->local-dir proxy-pose)
                                  m/normalize)
                 :visible? (if (and cull? cam-pos (:heading pose))
                             (pos? (m/dot (m/normalize (:heading pose))
                                          (m/v- cam-pos world)))
                             true)}))
            marks)
      (let [dims (dims-from-mesh proxy-mesh proxy-pose)
            objs (bf/corners dims)
            visible (bf/visible-corners dims (editor->solver-pose camera-pose proxy-pose))]
        (mapv (fn [i obj]
                {:id i
                 :obj obj
                 :world (local->world proxy-pose obj)
                 :visible? (contains? visible i)})
              (range) objs)))))

(defn plate-detect
  "The extras a registration PLATE carries for identity-free registration (fetta
   B) beyond its 12 crown marks: the asymmetric ZERO-INDEX mark — a 13th disc
   sitting radially INSIDE one crown mark (`:zero` in :anchors), whose object-
   frame position (world->local, the SAME frame the crown :objs live in) is the
   tiebreak that fixes the crown's 12-fold rotational symmetry — and the physical
   disc radius in mm (`:mark-disc-r`), which sizes the disc-presence test. Returns
   {:zero-obj [x y z] :disc-r mm} or nil for a box / a plate without a zero-index."
  [proxy-mesh]
  (when-let [zero (get-in proxy-mesh [:anchors :zero])]
    (let [proxy-pose (:creation-pose proxy-mesh)]
      {:zero-obj (world->local proxy-pose (:position zero))
       :disc-r (:mark-disc-r proxy-mesh)
       ;; the marked face's outward normal in the object frame, oriented toward
       ;; the camera side — the non-mirror constraint match-plate needs to reject
       ;; the reflection twin (the crown+zero is mirror-symmetric about the zero
       ;; axis, so a reflected assignment reprojects onto discs and ties on score)
       :face-normal (m/normalize (world->local-dir proxy-pose (:heading zero)))})))

(defn camera-sees-marked-face?
  "True when `solver-pose` puts the camera on the side of the plate its marks
   are printed on. `detect` is plate-detect's map (:face-normal, :zero-obj).

   The constraint the reprojection error cannot express: the discs were
   photographed, so the camera WAS on their side. A plane admits two poses that
   explain the same image — the 2-fold ambiguity of a planar homography — and on
   a grazing shot with a mark or two missing the residual cannot tell them apart.
   This can: a pose that puts the camera behind the printed face is not unlikely,
   it is impossible."
  [{:keys [face-normal zero-obj]} solver-pose]
  (when (and face-normal zero-obj solver-pose)
    (pos? (m/dot face-normal (m/v- (cam/camera-center solver-pose) zero-obj)))))

(def behind-face-cos
  "How far behind a mark's printed face a solved camera may fall before the pose
   is called impossible — a cosine, ≈4.6°. Not zero: a mark seen almost exactly
   edge-on sits at cos ≈ 0, and a pose that is merely IMPRECISE would then flip
   the sign of a mark that was legitimately, if badly, clicked. Anything further
   behind than this is not imprecision — it is the wrong side."
  -0.08)

(defn camera-sees-marks?
  "True when `solver-pose` puts the camera on the printed side of EVERY mark in
   `cis` (indices into `targets`, i.e. the marks the user says they clicked).

   The generalisation of camera-sees-marked-face? from a plate — one face, one
   normal, one zero-index — to a proxy whose marks face different ways, which is
   what a CAGE is. The physical fact is unchanged and just as sharp: those discs
   were photographed, so the camera was in front of each of them. A pose that
   puts it behind one is not unlikely, it is impossible.

   Targets carrying no :normal (box corners) never veto. Pure."
  [targets cis solver-pose]
  (when solver-pose
    (let [c (cam/camera-center solver-pose)]
      (every? (fn [i]
                (let [{:keys [normal obj]} (nth targets i nil)]
                  (or (nil? normal)
                      (let [v (m/v- c obj)
                            len (Math/sqrt (m/dot v v))]
                        (or (zero? len)
                            (> (/ (m/dot normal v) len) behind-face-cos))))))
              cis))))

(defn mirror-crown-index
  "The index the SAME physical disc takes under the plate's own mirror symmetry:
   i → (n − i) mod n, the reflection about the axis through crown mark 0.

   A crown of n evenly-spaced marks is mirror-symmetric about that axis, and so
   is the zero-index (it sits radially INSIDE mark 0, on the axis). Reflecting
   every declared identity therefore yields a labelling that the SAME clicks fit
   exactly as well — measured on real picks (param-plate-paper photo 9, 11
   marks): rms 10.74 px both, to the last digit. The two are not a near-tie the
   residual could arbitrate; they are equal, and they put the camera on OPPOSITE
   sides of the printed face (z = −55.3 vs +58.3 mm). So the residual can never
   choose between them, and camera-sees-marked-face? always can.

   This is the same reflection twin match-plate rejects in the identity-FREE
   path; declared correspondences are just as exposed to it, because a proxy
   drawn from an already-flipped pose invites the user to click the mirror
   labelling and confirm it."
  [n i]
  (mod (- n i) n))

(defn klein-images
  "The four camera poses that reproject a box with three distinct sides to the
   IDENTICAL silhouette: `camera-pose` plus its 180°-rotations about each of the
   box's principal axes (ex/ey/ez of `proxy-pose`), orbited about the box centre.
   These are the Klein four-group ambiguity the edge solver can't resolve — the
   registration can legally land on any of them (see branch-by-marker)."
  [camera-pose proxy-pose]
  (let [pivot (:position proxy-pose)
        {:keys [ex ey ez]} (box-basis proxy-pose)]
    [camera-pose
     (m/pose-around-axis camera-pose pivot ex Math/PI)
     (m/pose-around-axis camera-pose pivot ey Math/PI)
     (m/pose-around-axis camera-pose pivot ez Math/PI)]))

(defn branch-by-marker
  "Resolve the Klein branch of a registered camera by an OBSERVED asymmetric mark.
   A box with three distinct sides has four camera poses that reproject to the
   identical silhouette (klein-images) — the edge solver can't choose between
   them, which is what lets a symmetric box register onto the 'wrong' twin (the
   split in dev-docs/HANDOVER-edit-acquire-registration-stability.md). But a mark
   on the object (a pen arrow on one corner) is NOT silhouette-symmetric: it
   projects to a DIFFERENT pixel under each of the four poses. Given the pixel the
   user clicked on that mark (`clicked-px [x y]`) and the mark's position in the
   box-local/object frame (`marker-obj`, the frame box-fit/corners live in), this
   returns whichever Klein image reprojects the mark nearest the click — the
   physically-correct branch, decided by the observation itself rather than a
   predicted seed (so it is independent of the turntable axis and robust even at
   θ≈180, where a seed-based guess is worst; measured separation between images
   ≥650px on a 4032px frame). Behind-camera projections cost +Inf so a valid
   image always wins when one exists; returns `camera-pose` unchanged if none
   projects (degenerate)."
  [camera-pose proxy-pose marker-obj clicked-px intrinsics]
  (let [[cx cy] clicked-px
        cost (fn [cam]
               (if-let [px (cam/project intrinsics
                                        (editor->solver-pose cam proxy-pose)
                                        marker-obj)]
                 (let [dx (- (nth px 0) cx) dy (- (nth px 1) cy)]
                   (+ (* dx dx) (* dy dy)))
                 js/Infinity))]
    (apply min-key cost (klein-images camera-pose proxy-pose))))
