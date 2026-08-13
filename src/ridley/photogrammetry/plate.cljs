(ns ridley.photogrammetry.plate
  "Parametric registration PLATE proxy for edit-acquire — a cylinder carrying a
   crown of evenly-spaced marks plus an asymmetric zero-index under :anchors, and
   :mark-disc-r. This is the PROXY the acquire flow needs (mesh + marks); the
   printable two-colour discs and the mark-sheet SVG stay in
   the `acquire-plate` builtin library (fabrication, not registration).

   A NATIVE binding so no file import is needed: `(edit-acquire \"dir\" {:proxy
   (registration-plate :d 300)})` and the emitted `(acquire … {:proxy (registration-plate :d 300)})`
   are both self-contained (the marks ride the mesh; nothing to keep in scope).

   Frame: the cylinder is built axis-along-+Z with an IDENTITY creation-pose
   ({:heading [0 0 1] :up [0 1 0]} → box-basis = the identity), so a mark's object
   coordinate (what the solver sees, bridge/world->local) equals its :position:
   the crown is a circle of radius `crown-r` in the +Z (marked) face at z=+h/2,
   its normal +Z — exactly the geometry match-plate/pnp were validated against."
  (:require [ridley.geometry.primitives :as prims]))

;; There is NO default diameter, and that is a decision, not an omission.
;;
;; ⌀130 used to be one, for no better reason than being the first plate we built
;; — and on 2026-08-13 it cost exactly what an unearned default costs. A session
;; was registered against `(registration-plate)` while a ⌀300 plate sat on the
;; turntable, and NOTHING could tell: a uniform scale error on the target is
;; absorbed exactly by the camera distance, so every residual came back clean
;; (1.7px over eight views) while the whole scene was 2.3× too small. It took
;; triangulating the marks to see it.
;;
;; The diameter is the one number that ties the model to the object in the room.
;; A default lets it be left unsaid, and a number left unsaid is a number nobody
;; checks — so it is asked for.

;; The reference plate — ⌀130 — is the one every pixel-facing threshold in this
;; channel was tuned on, and it is why the geometry below is expressed as
;; FRACTIONS of the diameter rather than in millimetres. A bigger plate is a
;; scaled copy of it, so that a plate of any size, framed to fill the shot, puts
;; the same imaged disc size and the same crown proportions in front of the
;; detector. Fixed millimetres would make a 350mm plate's marks image at a third
;; of what the blob detector and the snap window expect — the exact class of bug
;; this channel spent 2026-08-11 finding elsewhere.

(def ^:private crown-margin-frac
  "How far inside the rim the crown sits, as a fraction of the DIAMETER (7mm on
   the reference 130): the marks stay on the plate with room to spare and never
   get clipped by the edge."
  (/ 7.0 130.0))

(def ^:private index-gap-frac
  "How far radially INSIDE its crown mark the zero-index sits, as a fraction of
   the diameter (6mm on the reference 130) — close enough to read as 'the inner
   pallino next to m00', far enough not to blur into it."
  (/ 6.0 130.0))

(def ^:private disc-frac
  "Disc diameter as a fraction of the plate's (2.5mm on the reference 130)."
  (/ 2.5 130.0))

(def ^:private mark-spacing
  "Target chord (mm) between adjacent marks on the REFERENCE plate."
  30.0)

(def max-default-marks
  "The crown count the default never exceeds, and the reason is the identity
   solve's combinatorics, not the circumference.

   `match-plate/assign-marks` enumerates C(m,k) subsets × k rotations × 2
   handednesses, where k is how many marks the frame actually shows. An object
   sitting on the plate hides some, so k < m is the NORMAL case, and the cost of
   j hidden marks is C(m,j)·(m−j)·2 candidate homographies — measured at roughly
   a millisecond each:

       m=12, 2 hidden →   1 500 candidates  ≈ 1.5 s
       m=12, 3 hidden →   4 000             ≈ 4 s
       m=16, 3 hidden →  15 000             ≈ 14 s
       m=24, 3 hidden →  85 000             ≈ 80 s
       m=36, 3 hidden → 471 000             ≈ 8 min

   So a denser crown does not buy accuracy, it buys a registration that stops
   working the moment the object covers three marks. And it buys little anyway:
   twelve marks spread around a 350mm plate condition the pose BETTER than
   twelve around a 130mm one, because what helps is their spread in the image,
   not their number.

   Raising this is safe only once `assign-marks` stops enumerating subsets — the
   crown is equally spaced, so the assignment is in principle m rotations × 2,
   independent of how many are hidden. That is the fix; this is the guard until
   it exists."
  12)

(defn default-mark-count
  "The crown count for a plate of crown-radius `crown-r` when the caller doesn't
   pin one: enough marks to keep ~`mark-spacing` mm between them on a SMALL plate,
   clamped to [8, `max-default-marks`]. Bigger plates get the same twelve, spread
   further apart — see max-default-marks for why more would be worse."
  [crown-r]
  (-> (Math/round (/ (* 2.0 Math/PI crown-r) mark-spacing))
      (max 8) (min max-default-marks)))

(defn crown-radius
  "Radius (mm) of the circle the crown marks sit on, for a plate of diameter `d`."
  [d]
  (- (/ d 2.0) (* crown-margin-frac d)))

(defn index-radius
  "Radius (mm) of the zero-index — radially inside crown mark m00."
  [d]
  (- (crown-radius d) (* index-gap-frac d)))

(defn disc-diameter
  "Diameter (mm) of one printed mark, for a plate of diameter `d`."
  [d]
  (* disc-frac d))

(defn- mark-id [i]
  (keyword (str "m" (when (< i 10) "0") i)))

(defn ^:export registration-plate
  "The registration-plate proxy. Keyword options:
     :d      diameter (mm) — REQUIRED, see the note above the namespace's
             constants for why there is no default
     :marks  crown mark count, default `default-mark-count` (never more than
             `max-default-marks` — read its docstring before pinning a big one)
     :disc   disc diameter (mm), default SCALES with :d (2.5 on the reference 130)
     :h      thickness (mm), default 3

   A plate of another size is a SCALED COPY of the reference 130: crown margin,
   disc and index gap are fractions of the diameter, so a 300mm plate framed to
   fill the shot puts the same imaged geometry in front of the detector as a
   130mm one does. Only `:h` stays absolute — thickness is about stiffness, not
   about what the camera sees.

   Returns a cylinder mesh with :anchors {mNN {:position :heading :up} … :zero {…}}
   and :mark-disc-r, in the solver's object frame."
  [& {:keys [d marks disc h]
      :or {h 3.0}}]
  (when-not (and (number? d) (pos? d))
    (throw (js/Error.
            (str "registration-plate: dimmi il diametro del piatto, per esempio "
                 "(registration-plate :d 300).\n"
                 "Non c'è un default apposta: il diametro è l'unico numero che lega "
                 "il modello all'oggetto che hai sul tavolo, e se è sbagliato NON si "
                 "presenta come un errore — la registrazione riesce lo stesso, con "
                 "residui ottimi, e tutte le misure escono scalate in silenzio."))))
  (let [radius (/ d 2.0)
        crown-r (crown-radius d)
        disc (or disc (disc-diameter d))
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
        zero {:position [(index-radius d) 0.0 half-h]
              :heading [0.0 0.0 1.0] :up [1.0 0.0 0.0]}]
    (-> cyl
        (assoc :vertices verts
               :creation-pose {:position [0.0 0.0 0.0] :heading [0.0 0.0 1.0] :up [0.0 1.0 0.0]}
               :anchors (assoc crown :zero zero)
               :mark-disc-r (/ disc 2.0)
               ;; The plate's identity, carried on the mesh. A CALIBRATION — the
               ;; measured positions of this physical plate's marks — is filed
               ;; against the diameter and crown count, because that is what
               ;; names the object on the table; the anchors themselves get
               ;; overwritten by it, so they cannot say what plate they came from.
               :plate-d d
               :plate-marks n))))
