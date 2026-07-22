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
