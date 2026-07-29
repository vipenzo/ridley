(ns ridley.photogrammetry.plate
  "Parametric registration PLATE proxy for edit-acquire — a cylinder carrying a
   crown of evenly-spaced marks plus an asymmetric zero-index under :anchors, and
   :mark-disc-r. This is the PROXY the acquire flow needs (mesh + marks); the
   printable two-colour discs and the mark-sheet SVG stay in
   examples/param-acq-plate.clj (fabrication, not registration).

   A NATIVE binding so no file import is needed: `(edit-acquire \"dir\" {:proxy
   (registration-plate)})` and the emitted `(acquire … {:proxy (registration-plate)})`
   are both self-contained (the marks ride the mesh; nothing to keep in scope).

   Frame: the cylinder is built axis-along-+Z with an IDENTITY creation-pose
   ({:heading [0 0 1] :up [0 1 0]} → box-basis = the identity), so a mark's object
   coordinate (what the solver sees, bridge/world->local) equals its :position:
   the crown is a circle of radius `crown-r` in the +Z (marked) face at z=+h/2,
   its normal +Z — exactly the geometry match-plate/pnp were validated against."
  (:require [ridley.geometry.primitives :as prims]))

(def ^:private default-diameter 130.0)
(def ^:private crown-margin
  "The crown sits this far inside the rim (mm) so the marks stay on the plate with
   room to spare and never get clipped by the edge."
  7.0)
(def ^:private mark-spacing
  "Target chord (mm) between adjacent marks for the DEFAULT count — so a bigger
   plate gets proportionally MORE marks (denser), which is what its extra
   circumference and the tighter PnP both want."
  30.0)
(def ^:private index-gap
  "The zero-index sits this far radially INSIDE its crown mark (mm) — close enough
   to read as 'the inner pallino next to m00', far enough not to blur into it."
  6.0)

(defn default-mark-count
  "The crown count for a plate of crown-radius `crown-r` when the caller doesn't
   pin one: enough marks to keep ~`mark-spacing` mm between them, clamped to a
   sane [8, 36]."
  [crown-r]
  (-> (Math/round (/ (* 2.0 Math/PI crown-r) mark-spacing))
      (max 8) (min 36)))

(defn- mark-id [i]
  (keyword (str "m" (when (< i 10) "0") i)))

(defn ^:export registration-plate
  "The registration-plate proxy. Keyword options:
     :d      diameter (mm), default 130
     :marks  crown mark count, default scales with the diameter (denser when big)
     :disc   disc diameter (mm), default 2.5
     :h      thickness (mm), default 3
   Returns a cylinder mesh with :anchors {mNN {:position :heading :up} … :zero {…}}
   and :mark-disc-r, in the solver's object frame."
  [& {:keys [d marks disc h]
      :or {d default-diameter disc 2.5 h 3.0}}]
  (let [radius (/ d 2.0)
        crown-r (- radius crown-margin)
        n (or marks (default-mark-count crown-r))
        half-h (/ h 2.0)
        two-pi (* 2.0 Math/PI)
        ;; prims builds the cylinder axis-along-+Y; rotate the vertices +90° about
        ;; X ([x y z] → [x -z y]) so the axis is +Z (the marked face normal),
        ;; matching the identity creation-pose below. Faces/face-groups index the
        ;; same vertices, and a proper rotation keeps the winding, so it renders
        ;; as a disc.
        cyl (prims/cyl-mesh radius h 48)
        verts (mapv (fn [[x y z]] [x (- z) y]) (:vertices cyl))
        crown (into {} (for [i (range n)]
                         (let [a (* i (/ two-pi n))]
                           [(mark-id i)
                            {:position [(* crown-r (Math/cos a)) (* crown-r (Math/sin a)) half-h]
                             :heading [0.0 0.0 1.0]
                             :up [(Math/cos a) (Math/sin a) 0.0]}])))
        zero {:position [(- crown-r index-gap) 0.0 half-h]
              :heading [0.0 0.0 1.0] :up [1.0 0.0 0.0]}]
    (-> cyl
        (assoc :vertices verts
               :creation-pose {:position [0.0 0.0 0.0] :heading [0.0 0.0 1.0] :up [0.0 1.0 0.0]}
               :anchors (assoc crown :zero zero)
               :mark-disc-r (/ disc 2.0)))))
