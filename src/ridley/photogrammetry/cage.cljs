(ns ridley.photogrammetry.cage
  "Parametric registration CAGE proxy for edit-acquire — three concentric,
   mutually orthogonal flat rings, each carrying a crown of marks on BOTH faces
   plus its own zero-index, under :anchors. The sibling of `plate`: same frame,
   same anchor shape, same solver, different geometry. The printable two-colour
   rings and the cradle live in the print file (examples/print-cage.clj) (fabrication,
   not registration), and they read THIS function's :anchors, so what is printed
   and what the solver looks for cannot drift apart.

   WHY A CAGE. The registration PLATE is a plane, and a plane has two costs the
   cage does not. (1) Seen from its edge it degenerates: the photograph from
   behind, or from level with the table, cannot be registered at all, so the
   object has to be re-mounted and the sessions fused. (2) The marks sit at the
   TABLE, while the detail being traced sits centimetres above it, so the pose
   error is levered up through that gap. Worse, both push the photographer the
   wrong way: to keep enough plate in frame the shot is taken wide and far, which
   is exactly the opposite of the close, aimed shot the tracing wants. On
   2026-08-17 that cost a real job — a broken appliance part was abandoned for
   `edit-image-board`, with the correct diagnosis that the photographs were
   obeying the mechanism rather than the drawing.

   The cage moves the reference ONTO the object. Rings anchored rigidly around
   the part, part in the middle: every photograph carries its own reference at
   the object's own depth, and the turntable, the θ angles, the NOTE.md and the
   multi-session fusion all cease to exist — not reduced, removed, because they
   existed to enforce a rigidity that the mounting now supplies physically.

   WHY THREE RINGS. One ring is a plate with a hole: still degenerate edge-on.
   With three mutually orthogonal ring planes, for ANY viewing direction v the
   best axis satisfies max|v·axis_i| ≥ 1/√3, so at least one ring is seen at
   ≥35° from its plane — an obliquity the ellipse fit already digests. And from
   most directions TWO rings are visible at once, which makes the visible marks
   NON-COPLANAR: the general (DLT) pose estimator comes back into service, all
   six degrees of freedom are conditioned by the data, and the planar
   homography's 2-fold mirror ambiguity — the one `bridge/camera-sees-marked-
   face?` exists to break — simply does not arise.

   WHY THE THREE RINGS ARE DIFFERENT SIZES. Two circles in orthogonal planes can
   only meet on the line their planes share, and there they sit at their own
   radii — so circles of DIFFERENT radius never intersect. That is true of
   circles; rings have width, so the rule that actually binds is that their
   radial BANDS must not overlap. Honouring it buys a cage that needs no
   interlocking slots, no notches and no springing: three whole rings, each
   printed flat with its marks against the build plate (the sharpest surface an
   FDM makes), joined by six glued tabs. It also hands the detector a free
   identity signal — the RATIO of two ellipses imaged together is invariant to
   distance, so the rings tell themselves apart by size, with no second colour
   and no second mark shape.

   Frame: identity creation-pose ({:heading [0 0 1] :up [0 1 0]}), as the plate,
   so a mark's object coordinate (bridge/world->local) equals its :position. The
   ring planes are XY, XZ and YZ; a ring is NAMED by its normal axis, and a mark
   id says which face of which ring it is on: :zp07 is mark 7 on the +Z face of
   the ring whose normal is Z, :xm00 is mark 0 on the −X face of the X ring.

   MARKS ON BOTH FACES, and it is not a nicety. A flat ring marked on one side
   is only visible from that side's half-space. With three rings marked on their
   +X/+Y/+Z faces a camera in the (−,−,−) octant sees NOT ONE MARK: the blind
   region the cage exists to abolish comes straight back. Both faces cost two
   colour changes and nothing else — the two faces' marks share their (u,v), so
   they are the same disc through the material — and which face is being looked
   at is never ambiguous, because the camera must be on the side of the face it
   can see."
  (:require [ridley.photogrammetry.plate :as plate]))

;; As with the plate there is NO default diameter, and for the same reason,
;; which cost this channel a session on 2026-08-13: a uniform scale error on the
;; target is absorbed exactly by the camera distance, so a wrong size does not
;; fail — it registers cleanly and silently scales every measurement that comes
;; out. `:d` is the number that ties this model to the object in the room, and
;; it is the one a caliper across the outermost ring can check.

;; The reference cage — ⌀176 over the largest ring — is what the pixel-facing
;; thresholds of this channel are tuned for, so the geometry below is expressed
;; as FRACTIONS of that diameter, exactly as the plate is. A cage of any size
;; framed to fill the shot then puts the same imaged disc and the same crown
;; proportions in front of the detector. (⌀176 is not arbitrary either: its
;; innermost ring clears ⌀88, which swallows a part of 60mm and its mounting,
;; while the outermost stays small enough that the part still fills a third of
;; the frame — against a fifth on the ⌀300 plate it replaces.)

(def reference-d
  "The cage every fraction below is expressed against (mm)."
  176.0)

(def ^:private band-frac
  "Radial WIDTH of one ring's annulus, as a fraction of :d (12mm on the reference
   176). It is what the crown needs and no more: the crown sits 2.5mm inside the
   outer edge, the zero-index 6mm inside the crown, and 3.5mm of material carries
   them — because every millimetre of band is a millimetre the NEXT ring has to
   stand further out, and the outermost ring's diameter is what decides how small
   the part looks in the photograph."
  (/ 12.0 176.0))

(def ^:private gap-frac
  "Clear radial GAP between one ring's band and the next (4mm on the reference
   176). Rings whose bands overlap collide — this is the whole reason the three
   diameters differ — and the gap is also where the joining tabs live."
  (/ 4.0 176.0))

(def ^:private crown-inset-frac
  "How far inside a ring's outer edge its crown sits (2.5mm on the reference
   176): one disc radius plus a margin, so a mark is never clipped by the rim."
  (/ 2.5 176.0))

(def ^:private index-gap-frac
  "How far radially INSIDE its crown mark the zero-index sits (6mm on the
   reference 176) — the same proportion the plate uses, so it reads as 'the inner
   pallino next to mark 0' at the same imaged size."
  (/ 6.0 176.0))

(def default-index-phase
  "How far round from mark 0 the zero-index sits, in MARK STEPS. A third of a
   step — 10° at twelve marks — and the whole point is that it is not zero.

   With the index exactly on mark 0's axis the crown is MIRROR-SYMMETRIC about
   that axis, and since both faces of a ring carry the same discs through the
   plastic, the two faces present an identical figure. Nothing in the photograph
   can then say which face you are looking at, and the numbering runs the opposite
   way on each — so a mark gets named as its mirror twin, the solve compensates
   with a rotated pose, and the residual stays low while the cage sits wrong.
   That is not a hypothesis: on 2026-08-22 Vincenzo read it straight off a photo —
   the disc labelled ym11 had the index pair under it, so it was ym00, and the
   face was the other one.

   Off the axis, the figure is CHIRAL: its mirror image cannot be produced by any
   rotation, so which face you are looking at is legible, and with it the
   direction the numbers run. A third of a step keeps the index unmistakably
   nearer mark 0 (10°) than mark 1 (20°), so 'whose index is this' stays obvious.
   Half a step would sit exactly between the two and be symmetric again.

   A cage PRINTED BEFORE 2026-08-22 has its index on the axis: model it with
   `(registration-cage :d … :index-phase 0)`, or the marks will be looked for
   where they are not."
  (/ 1.0 3.0))

(def ^:private disc-frac
  "Disc diameter as a fraction of :d (2.5mm on the reference 176)."
  (/ 2.5 176.0))

(def ring-count 3)

(def default-marks
  "Marks per crown, and the SAME number on every ring, which is a decision.

   The plate scales its count with its circumference; here it must not, because
   the identity of a ring is already carried by its radius (see the namespace
   docstring) and a uniform count is one less thing to get wrong on the print and
   at the bench. Twelve is also the ceiling `plate/max-default-marks` argues for
   at length: the identity solve enumerates subsets over the marks a frame
   actually shows, and past twelve that search stops being affordable the moment
   three marks are covered."
  plate/max-default-marks)

(def default-h
  "Ring thickness (mm). The ONE measurement that does not scale with :d, twice
   over: it is about stiffness, and it is the width of the band a ring seen
   edge-on paints across the part — 3mm against a 60mm part is 5% of it, and
   that is the whole occlusion cost of the cage."
  3.0)

;; --- the three rings' radii -------------------------------------------------

(defn ring-radii
  "The radii (mm) of ring `k` of a cage of diameter `d`, k=0 the LARGEST:
   {:outer :inner :crown :index}. Ring k sits one band-plus-gap inside ring k−1."
  [d k]
  (let [step (* d (+ band-frac gap-frac))
        outer (- (/ d 2.0) (* k step))]
    {:outer outer
     :inner (- outer (* d band-frac))
     :crown (- outer (* d crown-inset-frac))
     :index (- outer (* d crown-inset-frac) (* d index-gap-frac))}))

(defn aperture
  "The clear opening (mm) through the cage of diameter `d` — the innermost ring's
   hole, and therefore the largest thing that can be got to the middle."
  [d]
  (* 2.0 (:inner (ring-radii d (dec ring-count)))))

;; Ring k's normal axis. The order is deliberate: the SMALLEST ring is the Z one,
;; so the two long joining tabs (smallest↔largest, which span the whole width of
;; the middle ring's band and gap) are rooted in the ring that has the most
;; material to spare inboard of its crown.
(def ^:private ring-axes [:x :y :z])

(defn ring-axis [k] (nth ring-axes k))

;; Each ring is built in a local (u,v,n) frame — the annulus in the u-v plane,
;; n along its normal — and placed by an EVEN permutation of the coordinates, so
;; the face winding is preserved and the mesh renders solid from outside.
(defn place
  "Local ring coordinates (u,v,n) as world [x y z], for the ring whose normal is
   `axis`. Linear, so it carries directions as well as points."
  [axis [u v n]]
  (case axis
    :z [u v n]
    :y [v n u]
    :x [n u v]))

(defn unplace
  "World [x y z] back into the ring's own (u,v,n) frame — the exact inverse of
   `place`, and the whole of what turning a ring from CAGE orientation into
   PRINT orientation requires. Sizes of axis-aligned boxes permute with it too."
  [axis [x y z]]
  (case axis
    :z [x y z]
    :y [z x y]
    :x [y z x]))

(defn- axis-unit [axis s]
  (place axis [0.0 0.0 (double s)]))

;; --- the six joining tabs ---------------------------------------------------
;;
;; Two rings in orthogonal planes share exactly one axis, and that is where they
;; are joined. The tab belongs to the SMALLER ring of the pair and reaches
;; outward along the shared axis until it lies flat against the larger ring's
;; face, where it is glued: a lap joint, not an interlock — nothing is notched,
;; nothing is sprung, and no crown is interrupted.
;;
;; It survives two collision tests by construction, both worth stating because
;; getting either wrong shows up only after four hours of printing. (1) The tab
;; sits in its OWN ring's plane, which is spanned by the shared axis and the
;; partner's normal — so stepping sideways along the partner's normal, to clear
;; the partner's 3mm of material, keeps it flat and printable without support.
;; (2) It never meets the third ring, whose material hugs the plane through the
;; origin that the tab's own offset has already stepped off.
;;
;; The far end stops FLUSH with the partner's outer edge, which is the whole
;; assembly jig: slide until the tab ends level with the rim, then glue. No
;; shoulder to model, no tolerance to guess, and the check is visual.

(def ^:private tab-width
  "How far (mm) the tab stands out from the face it is glued to — its depth away
   from the partner, not its contact. Absolute: sized by stiffness, not by
   anything the camera sees."
  6.0)

(def ^:private tab-height
  "How TALL the tab is across its own ring's thickness (mm), and this is the
   dimension the joint actually lives on.

   Left equal to the ring's 3mm the tab meets the partner along a 3×12mm stripe —
   36mm² of glue, applied edge-on to a narrow face, and a joint that resists
   twisting about as well as a strip of card. Vincenzo saw it before printing and
   asked the right question. At 8mm the contact becomes 8×12 = 96mm², and the
   resistance to racking goes as the square of the width, so it is not 2.7× stiffer
   but about seven times. It costs nothing to print: the ring lies flat on the bed
   and the tab simply grows upward from it, no overhang, no support."
  8.0)

(def ^:private stop-gap
  "Slack (mm) between a pair of stop lips and the ring that drops between them.
   Two lips exactly one diameter apart do not receive a ring, they refuse it."
  0.3)

(def ^:private stop-thickness
  "How thick (mm) a stop lip is along the direction it blocks."
  3.0)

(def ^:private tab-clearance
  "Slip fit (mm) between the tab and the face it lies against — a glue line, and
   the slack that lets three rings printed to ±0.2 go together at all."
  0.25)

(def ^:private tab-root-frac
  "How far INTO its own ring's band the tab is rooted, as a fraction of the band.
   Seven tenths: deep enough that the joint fails by tearing the ring rather than
   by peeling the tab, shallow enough to stay clear of the crown."
  0.7)

(def seat-depth
  "How deep (mm) the seat pocket is cut into the partner's face where each lap
   tab lands — Vincenzo's «tacche/inviti» (3/9 notte). Today the azimuth of a
   glue-up is found by eye while the epoxy sets («il risultato è sempre un
   pressapoco» — the measured phases of the reference cage run 1-2°, and at
   R85 a degree is 1.5mm): the pocket receives the tab during the last
   fraction of the partner's own seating slide — the direction assembly
   already moves in, so nothing is swept laterally — and its walls locate the
   azimuth positively (±tab-clearance ≈ ±0.16° at this radius). The tab sinks
   seat-depth minus the glue line; 0.8 leaves 0.55mm of wall engagement and
   2.2mm of face under the pocket. A partner offered FLIPPED shows the face
   without pockets and sits proud by the same 0.55 — a visible witness,
   though the hard refusal of flips is the keys' job."
  0.8)

(def ^:private key-pin-azim
  "The assembly key's azimuthal width (mm) — how wide the pin is along the rim it
   blocks."
  2.0)

(def ^:private key-pin-radial
  "How far (mm) the pin reaches radially INTO the largest ring's band. Enough
   that a mis-rotated ring stands on it visibly; little enough that the notch
   removing it leaves the rim's strength alone."
  3.0)

(def ^:private key-pin-block
  "How far (mm) the pin reaches across the partner's plane — the depth of the
   slide it blocks. A mis-rotated ring seats this far proud of its tabs, which no
   one glues past by accident."
  2.5)

(defn joint-tabs
  "The boxes that hold a cage of diameter `d` and thickness `h` together:
   {:owner :partner :along :sign :kind :center [x y z] :size [dx dy dz]}.

   `:owner` is the ring the box is printed as part of — four joints on the
   smallest ring, two on the middle one, none on the largest. `:kind` is

     :lap   the glued tab itself, standing proud of its own ring so the contact
            with the partner is a face and not an edge. Since 3/9 it sinks
            `seat-depth` into the partner's seat pocket (below), so its
            azimuth is located by plastic instead of by eye;
     :seat  the pocket cut in the partner's face where the lap lands —
            Vincenzo's «tacche/inviti»: entered axially during the partner's
            own seating slide, walls at tab-clearance, so the pair's azimuth
            clicks to nominal instead of settling wherever the epoxy caught
            (the measured 1-2° phases of the reference cage). A CUT in its
            owner, like the key notch;
     :stop  a lip just BEYOND the partner's rim, so the partner drops between the
            pair of them and lands where it belongs instead of where the eye put
            it. Only on the smallest ring's joints, and that restriction is the
            whole design of it: a stop must reach across the partner's plane to
            block anything, and the partner is fitted by sliding along its own
            normal — so any lip lying in that path stops the ASSEMBLY, not the
            ring. The four small-ring lips sit outside the partner's outer radius
            and are never swept through; the middle ring's would sit exactly where
            the largest ring's rim passes on its way in, so they are not made;
     :key-pin, :key-notch
            the assembly keys — a spina on one tab, the tacca it needs cut in
            the partner's rim. TWO since 3/9 notte (Vincenzo: «una tacca a 90
            gradi oltre a quella già messa»): the middle ring's key refuses
            every rotation and flip of the largest ring but the nominal one —
            the rotation no other joint imposes, found glued 90° round on the
            reference cage (2026-08-24) — and the smallest ring's key, 90°
            round the same rim, refuses the SMALL ring flipped: its four tabs
            glue equally well either way up (which is how the battiscopa cage
            got its :flips), and with Z pinned to X and Y pinned to X the
            last mounting freedom any pair had is closed, transitively."
  [d h & [gen]]
  (let [gen2? (>= (or gen 1) 2)
        band (* d band-frac)
        root (* band tab-root-frac)
        rings (into {} (for [k (range ring-count)]
                         [(ring-axis k) (ring-radii d k)]))
        smallest (ring-axis (dec ring-count))
        pairs (for [k (range ring-count) j (range (inc k) ring-count)]
                [(ring-axis k) (ring-axis j)])] ; k is always the LARGER ring
    (vec
     (mapcat
      (fn [[[p-axis q-axis] sign]]
        (let [shared (first (remove #{p-axis q-axis} [:x :y :z]))
              p-outer (:outer (get rings p-axis))
              q-outer (:outer (get rings q-axis))
              stopped? (= q-axis smallest)
              ;; Where the tab ENDS. Flush with the partner's rim when there is
              ;; no lip; under the whole lip when there is one — because a lip
              ;; that merely sits NEXT to the tab is not part of the tab. The
              ;; first version put the lip 0.3mm beyond a tab that stopped at
              ;; the rim, and 0.3mm of air is a separate object: all four
              ;; printed loose and fell off the plate when the ring was lifted
              ;; (Vincenzo, 2026-08-18). The clearance the ring needs is between
              ;; the ring and the LIP, and it does not have to be air between
              ;; the lip and what carries it.
              tip (if stopped? (+ p-outer stop-gap stop-thickness) p-outer)
              len (+ (- tip q-outer) root)
              ;; the tab grows off ONE face of its own ring, so the part still
              ;; prints flat: the ring lies on the bed and this rises from it
              rise [(/ (- tab-height h) 2.0) tab-height]
              ;; gen 2: the tab's glue face sits at the seat pocket's floor
              ;; plus the glue line — sunk seat-depth below the partner's
              ;; face, so the pocket walls, not the gluer's eye, hold the
              ;; azimuth. gen 1 (the cages already printed): on the face.
              glue-face (+ (if gen2? (- (/ h 2.0) seat-depth) (/ h 2.0))
                           tab-clearance)
              lap (fn [axis]
                    (condp = axis
                      shared [(* sign (/ (+ (- q-outer root) tip) 2.0)) len]
                      p-axis [(+ glue-face (/ tab-width 2.0)) tab-width]
                      q-axis rise))
              seat (fn [axis]
                     (condp = axis
                       shared [(* sign (/ (+ (- q-outer root) tip) 2.0))
                               (+ len (* 2.0 tab-clearance))]
                       ;; from the pocket floor to 1mm past the face: a cut
                       ;; that stops flush would leave coincident faces, the
                       ;; known CSG-artifact recipe
                       p-axis (let [lo (- (/ h 2.0) seat-depth)
                                    hi (+ (/ h 2.0) 1.0)]
                                [(/ (+ lo hi) 2.0) (- hi lo)])
                       q-axis (let [[c s] rise] [c (+ s (* 2.0 tab-clearance))])))
              ;; the lip reaches from beyond the tab back ACROSS the partner's
              ;; plane, which is what makes it a stop rather than decoration
              far (+ p-outer stop-gap)
              inner-reach (- (+ (/ h 2.0) 1.0))
              outer-reach (+ (/ h 2.0) tab-clearance tab-width)
              stop (fn [axis]
                     (condp = axis
                       shared [(* sign (+ far (/ stop-thickness 2.0))) stop-thickness]
                       p-axis [(/ (+ inner-reach outer-reach) 2.0)
                               (- outer-reach inner-reach)]
                       q-axis rise))
              box (fn [kind f]
                    (let [parts (mapv f [:x :y :z])]
                      {:owner q-axis :partner p-axis :along shared :sign sign
                       :kind kind
                       :center (mapv first parts)
                       :size (mapv second parts)}))]
          (cond-> [(box :lap lap)]
            ;; the seat is cut in the PARTNER, the ring whose face receives
            ;; the tab — owner says whose material a cut removes
            gen2? (conj (assoc (box :seat seat) :owner p-axis :partner q-axis))
            stopped? (conj (box :stop stop))
            ;; ── the assembly keys ───────────────────────────────────────────
            ;; A pin on a tab, and the notch it needs cut in the partner's rim.
            ;; The first (middle ring → largest, 'una tacca e una spina',
            ;; Vincenzo 2026-08-24) exists because the largest ring is the one
            ;; part whose rotation no joint imposes — held by the others
            ;; pressing on its face, it can turn while staying seated, and at
            ;; a whole number of steps the glue-up LOOKS nominal (found at 90°
            ;; on the reference cage, by the photographs disagreeing with the
            ;; zero-index).
            ;;
            ;; The pin protrudes from the tab across the partner's plane,
            ;; inside its rim: sliding in, the rim meets the pin and the ring
            ;; cannot seat — at ANY wrong rotation, not just wrong steps —
            ;; unless the notch admits it; asymmetric (bed end of the tab), so
            ;; the flipped engagement is refused too. It costs nothing to
            ;; print: the pin is first-layer footprint, the notch a cut in a
            ;; flat part.
            ;;
            ;; The SECOND key (smallest ring → largest, 90° round the same
            ;; rim — Vincenzo, 3/9 notte: «una tacca a 90 gradi oltre a
            ;; quella già messa») closes the freedom the first left open: the
            ;; small ring's rotation is imposed by its own four tabs, but its
            ;; FLIP is not — flipped, its tabs land on the partners' other
            ;; faces and glue just as well, which is exactly how the
            ;; battiscopa cage got its :flips. With Y pinned to X and Z
            ;; pinned to X, every pair's mounting is fixed, transitively.
            (and (or (= [:y :x] [q-axis p-axis])
                     (and gen2? (= [:z :x] [q-axis p-axis])))
                 (pos? sign))
            (into (let [;; pin, radially: from half a mm inside the rim, reaching
                        ;; key-pin-radial into the band
                        pin-r-hi (- p-outer 0.5)
                        pin-r-lo (- pin-r-hi key-pin-radial)
                        ;; notch, radially: the pin with clearance, open past the rim
                        cut-r-lo (- pin-r-lo tab-clearance)
                        cut-r-hi (+ p-outer 1.0)
                        ;; pin, across the partner's plane: from 1mm INSIDE the tab
                        ;; (an exact touch would be a pair of coincident faces, the
                        ;; known CSG-artifact recipe) inward by key-pin-block past
                        ;; the glue face (which the seat pocket has sunk)
                        pin-p-hi (+ glue-face 1.0)
                        mid (fn [lo hi] [(/ (+ lo hi) 2.0) (- hi lo)])
                        pin (fn [axis]
                              (condp = axis
                                shared (let [[c sz] (mid pin-r-lo pin-r-hi)] [(* sign c) sz])
                                p-axis (mid (- glue-face key-pin-block)
                                            pin-p-hi)
                                ;; at the tab's BED end, off-centre — asymmetric on
                                ;; purpose, so the flipped ring is refused too
                                q-axis (mid (/ h -2.0) (+ (/ h -2.0) key-pin-azim))))
                        notch (fn [axis]
                                (condp = axis
                                  shared (let [[c sz] (mid cut-r-lo cut-r-hi)] [(* sign c) sz])
                                  ;; a through cut — the ring slides past the pin
                                  p-axis [0.0 (+ h 2.0)]
                                  q-axis (mid (- (/ h -2.0) tab-clearance)
                                              (+ (/ h -2.0) key-pin-azim tab-clearance))))]
                    [(box :key-pin pin)
                     (assoc (box :key-notch notch) :owner p-axis :partner q-axis)])))))
      (for [pr pairs sign [1 -1]] [pr sign])))))

(def ^:private slot-azimuths
  "Where the two stick-slots sit on each ring, in RING-LOCAL degrees. The
   occupants to stay clear of: marks at the odd multiples of 15° (crown-phase),
   tabs at 0/90/180/270 — and the ZERO-INDEX at 25° (crown phase plus a third
   of a step), which the first choice of 30° forgot: the test measured the slot
   body 7.3mm from the most precious disc on the ring. At 60° and 240° a slot is
   15° from the nearest crown mark, 30° from the nearest joint, 35° from the
   index — and the two being opposite, one ring's sticks brace the part from
   both sides."
  [60.0 240.0])

;; --- the stick-slot body, in ONE place --------------------------------------
;;
;; These were the print code's own constants until 2026-09-01 (then the `acquire-cage` library, now examples/print-cage.clj), the
;; last piece of cage fabrication still described outside the proxy. They moved
;; here for the reason every other one did: the library must not restate
;; geometry, or the printed cage and the model of it drift apart without either
;; looking wrong. What made it urgent is that the slots are now DRAWN over the
;; photograph as well as printed (Vincenzo: «anche loro sono elementi chirali
;; riconoscibili»), so the same numbers must reach a third consumer.

(def ^:private slot-body-w
  "Width (mm) of the slot body at d=176, across the channel — azimuthal on its
   ring. Scales with `stick-scale`."
  8.0)

(def ^:private slot-body-len
  "Length (mm) of the slot body at d=176, along the channel — radial on its
   ring. Scales with `stick-scale`: a longer stick wants a longer guide."
  14.0)

(def ^:private slot-rise
  "How far (mm) the body stands proud of the ring's face at d=176. The body
   spans the band and stands the rest on the print bed, so it prints with no
   overhang — the tabs' recipe. Scales with `stick-scale` (the channel grows,
   the wall above it must keep up)."
  6.0)

(def ^:private slot-channel-lift
  "Height (mm) of the channel's axis above the ring's face at d=176. Scales
   with `stick-scale`, or a grown channel would dip below the face."
  2.5)

(def ^:private stick-major
  "The stick family's ONE driving size (mm): the stick's MAJOR axis at d=176 —
   the tested 4.0. Everything else follows with ABSOLUTE clearances, because
   0.4mm is the printer's tolerance, not physics, and scaling it would break
   the cam: stick minor = major − 0.4 (insertion), channel minor = major
   EXACTLY (the quarter-turn bite), channel major = major + 0.4 (the bridge
   sag falls on the insertion clearance, never on the bite — the tested
   4.0×3.6 stick in the 4.4×4.0 channel)."
  4.0)

(defn stick-scale
  "How much the stick/slot CROSS-SECTIONS grow with the cage: (d/176)^0.75,
   floored at 1. A cantilever's stiffness goes as d⁴/L³ and the stick's free
   length grows with the cage's aperture, so equal tip stiffness wants the
   section ∝ length^0.75 — Vincenzo's question (4/9): «se fossero troppo fini
   rischiano di spezzarsi», and the answer is yes, from about ⌀220 up. Never
   below 1: the 176 sizes are the TESTED ones, and a smaller cage keeps them
   (its sticks are shorter, stiffer, and the slots stay printable)."
  [d]
  (max 1.0 (Math/pow (/ (double d) 176.0) 0.75)))

(defn stick-section-r
  "Semi-axes [across up] (mm) of the STICK for a cage of diameter `d` — what
   the print file builds sticks from, derived from the same driving size the
   channels use so the cam relation survives every scale."
  [d]
  (let [maj (* stick-major (stick-scale d))]
    [(/ maj 2.0) (/ (- maj 0.4) 2.0)]))

(defn stick-slots
  "The poses of the STICK-SLOTS on a cage of diameter `d` — the part-holder
   Vincenzo designed, printed and tested (2026-08-25): an elliptical stick slides
   through an elliptical channel and a small twist locks it, the stick being its
   own cam. Four or five sticks, entering from different rings, cage the part at
   the centre with no clothes-pegs and no tape — which matters to REGISTRATION,
   not just to convenience: the pegs and stems of the first real session are
   exactly what covered marks and fed the detector its false candidates.

   Two per ring, at `slot-azimuths`. Each: {:axis :azimuth-deg :position
   :heading :up} in cage coordinates — :position on the ring's mid-plane at
   radius outer−7 (the slot body spans the band and stands the rest on the print
   bed), :heading RADIALLY INWARD (the direction the stick travels), :up along
   the ring's own axis, the face the body rises from — the same face the tabs
   rise from, so the ring still prints flat with everything growing upward.

   Plus the BODY and CHANNEL each slot is made of, `h` being the ring
   thickness: :body-w across the channel, :body-len along it, :body-h its full
   height (band plus rise) and :body-lift how far its centre sits above
   :position — the four numbers a box needs — and :channel-lift / :channel-r
   for the elliptical hole through it. They live here rather than in the
   print file (where they were until 2026-09-01) because they now
   have three consumers — printing, the flat printable ring, and the cage drawn
   over the photograph — and three copies of a number is three chances to drift.

   Fabrication data, not registration data: slots carry no marks and take no
   part in the solve, so `:phases` does not move them."
  [d h]
  (let [s (stick-scale d)
        maj (* stick-major s)
        body-h (+ h (* slot-rise s))]
    (vec (for [k (range ring-count)
               alpha slot-azimuths]
           (let [axis (ring-axis k)
                 a (* alpha (/ Math/PI 180.0))
                 r (- (:outer (ring-radii d k)) 7.0)]
             {:axis axis
              :azimuth-deg alpha
              :position (place axis [(* r (Math/cos a)) (* r (Math/sin a)) 0.0])
              :heading (place axis [(- (Math/cos a)) (- (Math/sin a)) 0.0])
              :up (place axis [0.0 0.0 1.0])
              :body-w (* slot-body-w s)
              :body-len (* slot-body-len s)
              :body-h body-h
              :body-lift (- (/ body-h 2.0) (/ h 2.0))
              :channel-lift (* slot-channel-lift s)
              ;; [across up]: minor = the stick's major EXACTLY (the bite),
              ;; major = +0.4 absolute (the insertion, where the bridge sags)
              :channel-r [(/ maj 2.0) (/ (+ maj 0.4) 2.0)]})))))

;; --- anchors ----------------------------------------------------------------

(defn- face-tag [s] (if (pos? s) "p" "m"))

(defn mark-id
  "The anchor id of crown mark `i` on face `s` (+1/−1) of the ring whose normal
   is `axis` — :zp07 is mark 7 on the +Z face of the Z ring."
  [axis s i]
  (keyword (str (name axis) (face-tag s) (when (< i 10) "0") i)))

(defn index-id
  "The anchor id of the zero-index on face `s` of the ring whose normal is
   `axis`. Six per cage, one per marked face."
  [axis s]
  (keyword (str "zero-" (name axis) (face-tag s))))

(defn index-parts
  "A zero-index id like :zero-ym → {:axis :y :sign -1}; nil for anything else,
   crown marks included. The mirror of `index-id`."
  [id]
  (let [s (name id)]
    (when (and (= 7 (count s)) (= "zero-" (subs s 0 5)))
      {:axis (keyword (subs s 5 6))
       :sign (if (= "p" (subs s 6 7)) 1 -1)})))

(defn mark-parts
  "A crown id like :yp07 → {:axis :y :sign 1 :index 7}; nil for a zero-index or
   anything that is not a crown mark."
  [id]
  (when-let [m (re-matches #"([xyz])([pm])(\d+)" (name id))]
    {:axis (keyword (nth m 1))
     :sign (if (= "p" (nth m 2)) 1 -1)
     :index (js/parseInt (nth m 3) 10)}))

(defn crown-misreadings
  "Every way ONE ring's crown can be misread while the clicks themselves are
   right — `n` marks per crown, so 4n of them.

   The cage earns this list the hard way. Both faces of a ring carry the same
   discs at the same angles, so a photograph cannot tell you which face you are
   looking at; and from the far side the numbering runs the other way. Read a
   ring from the wrong side and you get the other face AND the reversed sense,
   plus whatever offset picking the wrong mark as number zero introduces.

   Crucially this is PER RING and not global. Measured on a real session
   (2026-08-19): in one photograph the largest ring was numbered correctly while
   the middle one came out mirrored, because the camera was on opposite sides of
   the two — which is the normal state of affairs for a cage, not bad luck. A
   rescue that flips every label together cannot fix that photograph."
  [n]
  (for [flip-face? [false true] mirror? [false true] rot (range n)]
    {:flip-face? flip-face? :mirror? mirror? :rot rot}))

(defn relabel
  "`id` re-read under one of `crown-misreadings`' entries; nil if `id` is neither
   a crown mark nor a zero-index. `n` is marks per crown.

   A ZERO-INDEX travels differently from a crown mark, and correctly so: there
   is exactly one per face, so a rotation or a mirror leaves it where it is and
   only a change of face moves it — to the index of the other face. Without this
   an index pick made every candidate in the per-ring search collapse (the
   search drops a reading it cannot map for EVERY pick), so clicking the one
   disc that identifies a crown would have disabled the machinery that uses it."
  [id {:keys [rot mirror? flip-face?]} n]
  (if-let [{:keys [axis sign index]} (mark-parts id)]
    (mark-id axis
             (if flip-face? (- sign) sign)
             (mod (+ rot (if mirror? (- index) index)) n))
    (when-let [{:keys [axis sign]} (index-parts id)]
      (index-id axis (if flip-face? (- sign) sign)))))

(defn crown-phase
  "The angle (rad) every crown is turned by, and it is not decoration.

   In a ring's own frame the two axes it shares with the other rings lie at 0°,
   90°, 180° and 270° — which is exactly where the six joining tabs run, because
   a ring's plane cuts another ring at precisely those two points and nowhere
   else. Leave the crown starting at 0° and mark 0 of every ring sits under a
   tab: measured on the reference cage before this existed, seven marks were
   covered, one of them a zero-index — and it would have been discovered by
   looking at the printed part, not at the code.

   Half a mark-step puts the four axes squarely BETWEEN marks (15° of clearance
   at twelve marks, against a tab 1.5mm wide). The one count that defeats a half
   step is n ≡ 2 (mod 4), where 90° lands back on a mark; a quarter step clears
   it, and cannot itself collide, since that would need n ≡ 1 (mod 4)."
  [n]
  (let [step (/ (* 2.0 Math/PI) n)]
    (if (= 2 (mod n 4)) (/ step 4.0) (/ step 2.0))))

(defn- deg->rad [x] (* (double x) (/ Math/PI 180.0)))

(defn turn-about-axis
  "Object-frame point `obj` turned by `deg` about the ring `axis`'s own axis —
   the exact motion a ring makes when it is glued round from nominal, since it
   stays seated and concentric while it turns."
  [axis obj deg]
  (let [[u v n] (unplace axis obj)
        a (deg->rad deg)
        c (Math/cos a) sn (Math/sin a)]
    (place axis [(- (* u c) (* v sn)) (+ (* u sn) (* v c)) n])))

(defn flip-in-ring
  "A point (or direction — the map is linear) of ring `axis` taken through the
   ring being TURNED OVER: a 180° rotation about its own local-u diameter,
   (u,v,n) → (u,−v,−n) in ring coordinates.

   A PROPER rotation, deliberately: a physical ring cannot be mirrored, only
   turned over, so no reflection belongs in the model. Which diameter it turns
   about does not matter — any other choice differs from this one by an
   in-plane rotation, and that residue is exactly what the ring's `:phases`
   number declares. So (flip, phase) spans every mounting the joints leave
   open, with no redundancy."
  [axis p]
  (let [[u v n] (unplace axis p)]
    (place axis [u (- v) (- n)])))

(defn- mount-anchor
  "One anchor of a FLIPPED ring carried from print frame to as-built frame:
   the flip first, then the measured phase turn (`po-deg`, degrees) — the
   physical order of assembly: the ring was turned over, then it seated at
   whatever rotation the epoxy caught. Both maps are linear, so :heading and
   :up ride the same transform as :position."
  [axis po-deg a]
  (let [t #(turn-about-axis axis (flip-in-ring axis %) po-deg)]
    (cond-> a
      (:position a) (update :position t)
      (:heading a) (update :heading t)
      (:up a) (update :up t))))

;; --- rim segments (Vincenzo's design, 2026-09-03 notte) ---------------------
;;
;; The rim of a ring is maximally visible exactly when its face vanishes: a
;; signal that turns ON in the hardest case (rings di taglio — grab-09, where
;; Y at 9-17° and Z at 6-20° left zero-click nothing to assemble). Twelve
;; segments at the crown's own azimuths, each occupying the HALF of the rim
;; thickness toward the print face p — the first physically face-asymmetric
;; feature of the cage: every face disc is a through-hole, identical from
;; both sides by construction, while a half-thickness segment SHOWS which
;; face it hugs. The zero segment carries a break at 2/3 of its length: it
;; breaks the crown's 12-fold symmetry on the rim (the rim's own zero-index)
;; and reads at 1/3 on a ring glued flipped — a chirality witness.

(def rim-seg-deg
  "Azimuthal extent (deg) of one rim segment. The binding constraint is the
   glue tabs, which own the rim at 0/90/180/270 in every ring's local frame:
   by Vincenzo's eye a segment must stay under 1/25 of the turn (14.4°),
   «forse meno». 12° is 1/30 — centred between tabs like the marks are
   (crown-phase), it leaves ≥9° of rim to the nearest tab centre."
  12.0)

(def rim-zero-break
  "The zero segment's interruption: `:at` as a fraction of the segment's
   length ALONG THE NUMBERING DIRECTION (increasing azimuth), `:width` the
   fraction removed. At 2/3 by design: seen from the other side — or on a
   ring glued flipped — the same break reads at 1/3, so its position alone
   distinguishes the two mountings the crown's through-holes cannot."
  {:at (/ 2.0 3.0) :width (/ 1.0 6.0)})

(defn rim-spans
  "One crown's rim segments as azimuth spans in the ring's OWN frame: `n`
   entries {:k :zero? :spans [[a0 a1] …]} (rad), each segment `rim-seg-deg`
   wide and centred on its mark's azimuth (crown-phase — the same clearing
   of the tabs the marks get), the zero segment split in two by
   `rim-zero-break`. The single source both renderers read: `rim-segments`
   mounts these onto the cage for the drawing over the photographs, and
   `printable-ring` hands them to the print file for the plastic — so the
   drawn ribbons and the printed ones cannot disagree on where they run."
  [n]
  (let [step (/ (* 2.0 Math/PI) n)
        phase (crown-phase n)
        w (deg->rad rim-seg-deg)
        {:keys [at width]} rim-zero-break]
    (vec (for [i (range n)
               :let [a0 (- (+ phase (* i step)) (/ w 2.0))
                     ts (if (zero? i)
                          [[0.0 (- at (/ width 2.0))]
                           [(+ at (/ width 2.0)) 1.0]]
                          [[0.0 1.0]])]]
           {:k i :zero? (zero? i)
            :spans (mapv (fn [[t0 t1]]
                           [(+ a0 (* w t0)) (+ a0 (* w t1))])
                         ts)}))))

(defn rim-segments
  "The rim marks of a cage that DECLARES them (`:rim-marks?` on
   registration-cage), as-built: `rim-spans`' azimuths, phases and flips
   applied exactly as the anchors take them (flip first, then the measured
   turn), the band spanning the half thickness toward the PRINT face p — on
   a flipped ring the drawn segments land on the cage's other side, like the
   glued plastic does. Returns [{:axis :k :zero? :pieces [{:lo pts :hi pts}]} …]
   in cage coordinates — :lo the arc at the ring's mid-plane, :hi at the p
   edge, `samples` points each, radius = ring outer + `r-off`. nil when the
   mesh does not declare rim marks: cages printed before 4/9 do not have
   them, and a drawn feature the plastic does not have is worse than none."
  [mesh & {:keys [r-off samples] :or {r-off 0.0 samples 7}}]
  (when (:rim-marks? mesh)
    (let [h (:cage-h mesh)
          phases (:cage-phases mesh)
          flips (or (:cage-flips mesh) #{})
          segs (rim-spans (:cage-marks mesh))]
      (vec
       (for [{:keys [axis outer]} (:rings mesh)
             {:keys [k zero? spans]} segs
             :let [po (or (get phases axis) 0.0)
                   flip? (contains? flips axis)
                   mount (fn [p]
                           (turn-about-axis axis
                                            (if flip? (flip-in-ring axis p) p)
                                            po))
                   r (+ outer r-off)
                   arc (fn [[s0 s1] z]
                         (vec (for [s (range samples)
                                    :let [a (+ s0 (* (- s1 s0)
                                                     (/ s (dec samples))))]]
                                (mount (place axis [(* r (Math/cos a))
                                                    (* r (Math/sin a))
                                                    z])))))]]
         {:axis axis :k k :zero? zero?
          :pieces (mapv (fn [sp]
                          {:lo (arc sp 0.0)
                           :hi (arc sp (/ h 2.0))})
                        spans)})))))

(defn- ring-radius [{:keys [axis obj]}]
  (let [[u v _] (unplace axis obj)] (Math/sqrt (+ (* u u) (* v v)))))

(defn- phase-of-ring
  "One ring's apparent turn (deg) and the scatter of the marks that said so,
   measured against `project`. To first order: turn a mark by a probe angle, see
   which way and how far its image moves, and read the click's residual along
   that direction."
  [axis ps project]
  (let [probe 1.0
        ests (vec (keep (fn [{:keys [obj px]}]
                          (let [p0 (project obj)
                                p1 (project (turn-about-axis axis obj probe))]
                            ;; `project` gives nil behind the camera, and a mark
                            ;; whose image barely moves when the ring turns (this
                            ;; ring seen edge-on) measures nothing — dividing by
                            ;; that is how a diagnosis becomes noise
                            (when (and p0 p1)
                              (let [t [(- (nth p1 0) (nth p0 0)) (- (nth p1 1) (nth p0 1))]
                                    e [(- (nth px 0) (nth p0 0)) (- (nth px 1) (nth p0 1))]
                                    tt (+ (* (nth t 0) (nth t 0)) (* (nth t 1) (nth t 1)))]
                                (when (> tt 1e-6)
                                  (* probe (/ (+ (* (nth e 0) (nth t 0))
                                                 (* (nth e 1) (nth t 1)))
                                              tt)))))))
                        ps))]
    (when (>= (count ests) 2)
      (let [mean (/ (reduce + ests) (count ests))
            var (/ (reduce + (map #(let [d (- % mean)] (* d d)) ests)) (count ests))]
        {:deg mean
         :mm (* (apply max (map ring-radius ps)) (deg->rad mean))
         :spread-deg (Math/sqrt var)
         :n (count ests)}))))

(defn- estimate-all
  "One pass: every ring with at least two picks, each measured against a pose
   solved WITHOUT it (leave-one-ring-out). Falls back to the full fit when the
   hold-out leaves too little to fit — flagged `:held-out? false`, because that
   estimate under-reads and the caller must not present it as a measurement."
  [picks solve]
  (let [by-ring (group-by :axis picks)]
    (into {}
          (keep identity)
          (for [[axis ps] by-ring
                :when (>= (count ps) 2)]
            (let [others (vec (mapcat val (dissoc by-ring axis)))
                  held (when (seq others) (solve others))
                  project (or held (solve picks))]
              (when project
                (when-let [r (phase-of-ring axis ps project)]
                  [axis (assoc r :held-out? (some? held))])))))))

(defn- confident?
  "A ring is ACCUSED only when its own marks agree with each other. That
   agreement is the entire evidence: a ring clicked sloppily scatters as widely
   as its own mean, a ring GLUED round gives the same offset from every mark."
  [{:keys [deg spread-deg]}]
  (and (> (Math/abs deg) 0.3) (> (Math/abs deg) (* 1.5 spread-deg))))

(def ^:private phase-passes
  "How many times the estimate is refined. One turned ring contaminates the
   hold-out poses of the other two — measured: with one ring 3.0° round, the two
   innocent rings read −1.2° each (at ten times the scatter, which is what keeps
   them from being accused). Correcting the confident ring and re-measuring
   clears that, and a second refinement has nothing left to find."
  2)

(defn phase-from-residuals
  "How far each ring looks TURNED, in degrees, read off one solved photograph.

   `picks` are the correspondences the photograph was solved from, each
   {:axis :obj [x y z] :px [u v]}: the mark's object position per the MODEL and
   the pixel the user actually clicked. `solve` takes a subset of picks and
   returns a function from object point to pixel (the pose that subset implies),
   or nil when it cannot fit one.

   Two things make the number trustworthy rather than merely suggestive.

   LEAVE-ONE-RING-OUT: each ring is measured against a pose solved without it.
   Measured against the fit that used it, a ring 3.0° round reads 1.0° — the fit
   rotates the whole cage to split the difference and hides two thirds of the
   error.

   REFINEMENT: a genuinely turned ring drags the hold-out poses of the other
   two, which then read about −1.2° apiece. So a ring the evidence is confident
   about is corrected and everything re-measured, which returns the innocent
   rings to zero.

   Returns {axis {:deg :mm :spread-deg :n :held-out?}} per ring with at least two
   picks. `:spread-deg` is reported next to `:deg` and never summarised away — see
   `confident?`. `:held-out? false` marks an estimate that could not be held out
   and therefore under-reads: a direction and a starting point, not an answer."
  [picks solve]
  (loop [ps picks
         applied {}
         pass 0]
    (let [est (estimate-all ps solve)
          sure (into {} (filter (fn [[_ r]] (and (confident? r) (:held-out? r))) est))]
      (if (or (>= pass phase-passes) (empty? sure))
        ;; report the TOTAL: what earlier passes already corrected, plus what is
        ;; still left over in this one
        (into {} (for [[axis r] est]
                   [axis (let [total (+ (get applied axis 0.0) (:deg r))]
                           (assoc r
                                  :deg total
                                  :mm (* (apply max (map ring-radius
                                                         (filter #(= axis (:axis %)) ps)))
                                         (deg->rad total))))]))
        (recur (mapv (fn [{:keys [axis obj] :as p}]
                       (if-let [r (get sure axis)]
                         (assoc p :obj (turn-about-axis axis obj (:deg r)))
                         p))
                     ps)
               (merge-with + applied (into {} (map (fn [[a r]] [a (:deg r)]) sure)))
               (inc pass))))))

(defn- face-anchors
  "The crown + zero-index anchors of ONE marked face: `n` marks on a circle of
   radius `crown-r` starting at `crown-phase` PLUS `phase-off` (rad, see
   `registration-cage`'s :phases), the index radially inside mark 0, all at
   n = s·h/2.

   Both faces of a ring take the SAME `phase-off`, and must: the two crowns are
   the same physical discs seen through 3mm of plastic, so a ring that was glued
   turned is turned on both its sides at once."
  [axis s crown-r index-r n h phase-off index-phase]
  (let [off (* s (/ h 2.0))
        heading (axis-unit axis s)
        step (/ (* 2.0 Math/PI) n)
        phase (+ (crown-phase n) phase-off)
        ;; the index is turned off mark 0's axis — see `default-index-phase`
        ipos (+ phase (* index-phase step))
        at (fn [r a] (place axis [(* r (Math/cos a)) (* r (Math/sin a)) off]))
        radial (fn [a] (place axis [(Math/cos a) (Math/sin a) 0.0]))]
    (into {(index-id axis s) {:position (at index-r ipos)
                              :heading heading
                              :up (radial ipos)}}
          (for [i (range n)]
            (let [a (+ phase (* i step))]
              [(mark-id axis s i) {:position (at crown-r a)
                                   :heading heading
                                   :up (radial a)}])))))

;; --- mesh -------------------------------------------------------------------

(defn- annulus-mesh
  "Vertices and faces of one flat annulus: radii [r-in r-out], thickness `h`,
   `seg` segments, in the ring whose normal is `axis`, with vertex indices offset
   by `base`. Faces wind CCW seen from outside."
  [axis r-in r-out h seg base]
  (let [half (/ h 2.0)
        step (/ (* 2.0 Math/PI) seg)
        verts (vec (mapcat (fn [i]
                             (let [a (* i step)
                                   c (Math/cos a) s (Math/sin a)]
                               ;; per segment: inner/outer × +n/−n
                               [(place axis [(* r-in c) (* r-in s) half])
                                (place axis [(* r-out c) (* r-out s) half])
                                (place axis [(* r-in c) (* r-in s) (- half)])
                                (place axis [(* r-out c) (* r-out s) (- half)])]))
                           (range seg)))
        faces (vec (mapcat (fn [i]
                             (let [j (mod (inc i) seg)
                                   i0 (+ base (* 4 i)) i1 (inc i0) i2 (+ i0 2) i3 (+ i0 3)
                                   j0 (+ base (* 4 j)) j1 (inc j0) j2 (+ j0 2) j3 (+ j0 3)]
                               [;; +n face
                                [i0 i1 j1] [i0 j1 j0]
                                ;; −n face
                                [j3 i3 i2] [j2 j3 i2]
                                ;; outer wall
                                [i1 i3 j3] [i1 j3 j1]
                                ;; inner wall
                                [j2 i2 i0] [j0 j2 i0]]))
                           (range seg)))]
    {:vertices verts :faces faces}))

(defn anchor-axis
  "Which ring an anchor id belongs to: `:zp07` → :z, and `:zero-zp` → :z too —
   which is why this exists instead of reading the first letter, since 'zero'
   itself begins with a z."
  [id]
  (let [s (name id)]
    (keyword (if (and (> (count s) 5) (= "zero-" (subs s 0 5)))
               (subs s 5 6)
               (subs s 0 1)))))

(defn ^:export registration-cage
  "The registration-cage proxy. Keyword options:
     :d      diameter (mm) OVER THE LARGEST RING — REQUIRED, and deliberately
             without a default: it is the one number tying this model to the
             object on the bench, a wrong one registers cleanly and scales every
             measurement in silence, and it is what a caliper across the cage
             measures.
     :marks  marks per crown, default 12 on every ring (see `default-marks`)
     :disc   disc diameter (mm), default scales with :d (2.5 on the reference 176)
     :h      ring thickness (mm), default 3 — does NOT scale (see `default-h`)
     :seg    mesh segments per ring, default 64 (appearance only)
     :phases how much each ring is turned about its OWN axis on the cage you
             actually glued, in DEGREES, as {:x d :y d :z d} — :x the largest
             ring, :z the smallest — positive by the right-hand rule on that
             axis. Default 0, meaning nominal.

             This exists because one of the three rotations is not constrained
             by the joints. Concentricity and orthogonality are imposed by the
             tabs and their stops; the small and medium rings' own tabs only
             reach their partners when those rings are turned right; but the
             LARGEST ring has no tabs of its own — it is held by the other two
             pressing against its face, and it can turn while staying seated.
             Nominally every joint lands halfway between two marks (15° of
             clearance at twelve), and that is the visual check; but finding
             that midpoint by eye while the epoxy sets is genuinely hard, and
             at r=85mm one degree is 1.5mm. So the phase is a number to
             MEASURE on the built cage rather than a tolerance to hit — the
             same move `plate-calib` makes, difficulty shifted off the
             fabrication and onto an instrument. One scalar per ring.

             A WHOLE-STEP error (90° is three steps at twelve marks) IS one of
             these, and it is the sneaky one: the tabs land between marks again,
             so the glued cage looks nominal, and every crown fits every
             rotation of its own labels — the misfit shows up only as the OTHER
             rings sitting k steps round from where the model puts them. The
             zero-index is the sole witness that separates 'labels out by k'
             from 'ring glued k steps round': it travels with its ring, so it
             confirms the labels while the rings disagree — exactly the
             signature match-cage/read-crown reports as :phase-suspect, telling
             you to declare the phase here. Lived before it was written:
             Vincenzo's reference cage has its big ring at 90°
             (:phases {:x 90}), found live on 2026-08-24 after a day of the
             candidates saying 'rot 3' and the clicked zero saying 'rot 0' —
             both were right.
     :flips  which rings were glued TURNED OVER, as a set of axes (#{:y}) or a
             map {:y true}. The other mounting freedom, and like :phases it is
             a constant of the built cage to DECLARE, never a defect to fix by
             reprinting: the reference cage's Y ring is mounted flipped
             (physical test 2026-08-28 — a flip the joints do not forbid), it
             was discovered from photographs (the ring's zero-index detected
             cleanly but sitting in the MIRRORED housing, leva 2), and the
             cage is glued with epoxy — the choice is model it or bin it.

             A flip is modelled as the physical motion it is: a 180° proper
             rotation of the printed ring about one of its own diameters
             (flip-in-ring), then the ring's :phases turn. No mirror — a real
             ring cannot be mirrored — and no new parameter beyond the boolean:
             whichever diameter it was actually turned over, the difference is
             an in-plane rotation that the ring's phase absorbs. Which means:
             DECLARING A FLIP CHANGES WHAT THAT RING'S PHASE MEASURES — a
             phase fitted under the unflipped assumption is void for that ring
             and must be re-measured on the as-built model.

             Anchor ids keep the PRINT's labels: after a flip, :yp… names the
             discs of the printed p face, which now faces −y. That is the
             reading gesture's own convention — the passetto rule (big disc →
             small pallino, CCW in the image = p) reads the printed figure's
             chirality, which mounting cannot change — so what the eye reads
             off a photograph and what the id says stay the same fact. The
             face-from-pose derivation accounts for the flip instead
             (bridge/cage-faces-from-pose).

             Fabrication features (tabs, slots) flip with their ring, so the
             drawn cage matches the glued one; they still do not take the
             :phases turn (an axis-aligned box cannot turn 30° and stay a box)
             — exact for phases that are multiples of 180°, which his
             measured cages so far all are; crowns and indices, the things
             the solver and the double pallini live on, are exact always.

   Returns a three-ring mesh with, under :anchors, six crowns of `marks` plus six
   zero-indices — `:zp00`…, `:zm00`…, `:yp00`…, `:ym00`…, `:xp00`…, `:xm00`…,
   `:zero-zp` … — and `:mark-disc-r`, `:cage-d`, `:cage-marks`, `:cage-h` and
   `:rings` (each ring's axis and radii, which the print file (examples/print-cage.clj) prints
   from). All in the solver's object frame.

     (edit-acquire \"/Users/me/scans/testina\" {:proxy (registration-cage :d 176)})

   The anchors are non-coplanar, so `pnp/solve-pnp` routes them to the general
   DLT rather than the planar homography — pick marks on TWO rings and the pose
   is conditioned on all six degrees of freedom with no mirror twin to reject."
  [& {:keys [d marks disc h seg phases flips index-phase rim-marks? gen]
      :or {h default-h seg 64 index-phase default-index-phase gen 1}}]
  (when-not (and (number? d) (pos? d))
    (throw (js/Error.
            (str "registration-cage: give me the cage's diameter — the one "
                 "of the LARGEST ring — for example (registration-cage :d 176).\n"
                 "There is deliberately no default: it is the only number tying the model "
                 "to the cage in your hands, and if it is wrong it does NOT show up as "
                 "an error — registration succeeds anyway, with excellent residuals, "
                 "and every measurement comes out scaled, silently."))))
  (let [n (or marks default-marks)
        disc (or disc (* d disc-frac))
        rings (mapv (fn [k] (assoc (ring-radii d k) :axis (ring-axis k) :marks n))
                    (range ring-count))
        parts (reduce (fn [{:keys [verts faces groups]} {:keys [axis inner outer]}]
                        (let [m (annulus-mesh axis inner outer h seg (count verts))]
                          {:verts (into verts (:vertices m))
                           :faces (into faces (:faces m))
                           :groups (assoc groups
                                          (keyword (str "ring-" (name axis)))
                                          (:faces m))}))
                      {:verts [] :faces [] :groups {}}
                      rings)
        phase-off (fn [axis] (deg->rad (or (get phases axis) 0.0)))
        flip? (if (map? flips)
                (into #{} (keep (fn [[k v]] (when v k))) flips)
                (set flips))
        anchors (reduce (fn [acc {:keys [axis crown index]}]
                          (if (flip? axis)
                            ;; as-built ring: generate the PRINT (phase 0), then
                            ;; flip it over and turn it by the measured phase —
                            ;; the assembly's own order (see :flips above)
                            (let [po-deg (or (get phases axis) 0.0)]
                              (into acc
                                    (map (fn [[id a]] [id (mount-anchor axis po-deg a)]))
                                    (concat (face-anchors axis 1 crown index n h 0.0 index-phase)
                                            (face-anchors axis -1 crown index n h 0.0 index-phase))))
                            (let [po (phase-off axis)]
                              (into acc (concat (face-anchors axis 1 crown index n h po index-phase)
                                                (face-anchors axis -1 crown index n h po index-phase))))))
                        {}
                        rings)]
    {:type :mesh
     :primitive :cage
     :vertices (:verts parts)
     :faces (:faces parts)
     :face-groups (:groups parts)
     :creation-pose {:position [0.0 0.0 0.0] :heading [0.0 0.0 1.0] :up [0.0 1.0 0.0]}
     :anchors anchors
     :mark-disc-r (/ disc 2.0)
     ;; Per-anchor front-facing culling is CORRECT here and wrong on a plate: a
     ;; plate's marks are all on one face that the user is always looking at, so
     ;; culling them would leave nothing to click on a fresh session, while a
     ;; cage always turns about half its marks away from any vantage and offering
     ;; those invites a click on a disc that is not there. See
     ;; bridge/pnp-target-points.
     :anchor-culling? true
     ;; The cage's identity, carried on the mesh — what names the object on the
     ;; bench, and therefore what a future calibration of the real one is filed
     ;; against (the anchors themselves get overwritten by it, so they cannot say
     ;; which cage they came from).
     :cage-d d
     :cage-marks n
     :cage-h h
     :cage-phases phases
     :cage-flips flip?
     :cage-index-phase index-phase
     ;; Vincenzo's rim segments (see rim-segments): DECLARED, never assumed —
     ;; the drawn cage must match the printed one, and today's cages do not
     ;; have them. `:rim-marks? true` is for the design preview and, when a
     ;; cage is printed with them, for the real thing.
     :rim-marks? (boolean rim-marks?)
     ;; The FABRICATION GENERATION of the physical cage, declared like
     ;; everything else about it: 1 = the prints of today (one assembly key,
     ;; tabs on the partner's face); 2 = seat pockets with sunk tabs and the
     ;; second key (designed 3/9 notte, not yet printed). The drawn cage must
     ;; be the glued one — a second red pin, or alette sunk 0.8mm, on the
     ;; model of a cage that does not have them would have the eye aligning
     ;; to plastic that is not there.
     :cage-gen gen
     :rings rings
     ;; Fabrication rides on the proxy for the same reason the marks do: the
     ;; print file (examples/print-cage.clj) must not restate any of this, or the printed cage
     ;; and the model of it drift apart without either one looking wrong.
     ;; A flipped ring's features flip with it (as-built, not as-designed) —
     ;; the drawn tabs must sit where the glued ones are, or the eye alignment
     ;; they exist for would be aligning to a cage that was never built.
     :tabs (mapv (fn [t] (cond-> t
                           (flip? (:owner t))
                           (update :center (partial flip-in-ring (:owner t)))))
                 (joint-tabs d h gen))
     ;; A slot is part of its ring, so it takes the ring's mounting whole: the
     ;; flip first, then the PHASE — unlike the tabs, which are not turned
     ;; because a turned tab would not reach its partner (that is precisely why
     ;; only the largest ring's rotation is free; see :phases). Slots did not
     ;; take the phase while they were fabrication-only data. They must now:
     ;; they are DRAWN over the photograph as an alignment reference, and a
     ;; reference drawn where the plastic is not is worse than none — on a cage
     ;; with :phases {:x 90} the eye would be lining the model up with a lie.
     ;; Invisible at 180° (the two slots sit 60°/240°, so a half turn maps the
     ;; pair onto itself) — which is exactly why nothing Vincenzo aligned by
     ;; could contradict the X ring's declared 180° on 2026-09-01.
     :stick-slots (mapv (fn [{:keys [axis] :as sl}]
                          (let [po-deg (or (get phases axis) 0.0)
                                mount (fn [v]
                                        (cond->> v
                                          (flip? axis) (flip-in-ring axis)
                                          true (#(turn-about-axis axis % po-deg))))]
                            (-> sl
                                (update :position mount)
                                (update :heading mount)
                                (update :up mount))))
                        (stick-slots d h))
     :aperture (aperture d)}))

(defn printable-ring
  "Ring `which` (:x/:y/:z, or an index) of an already-built `cage`, described in
   ITS OWN frame: flat in XY, marks on the ±Z faces, tabs rising in +Z.

   {:axis :inner :outer :crown :index :h :marks [{:id :position :heading}]
    :tabs [{:kind :center :size}] :slots […] :rim-segments […]}

   Same numbers as the cage — they are the cage's own anchors and tabs put
   through `unplace`, not a second computation — only turned the way a printer
   wants them. `:rim-segments` is `rim-spans` (azimuths are already per-ring
   local, so there is nothing to unplace), present only when the cage
   declares `:rim-marks?` — a print must not carry ribbons the model does
   not draw, nor the other way round.

   Print from a cage declared WITHOUT :flips: a flip describes how an existing
   assembly was GLUED, and a printable-ring taken from a flipped model would
   bake that mounting into the plastic of a new part.

   It exists because two rings out of three stand on EDGE in cage coordinates,
   and a 3MF written that way asks the user to rotate them in the slicer. A ring
   and its discs are two objects there, so rotating one and not the other leaves
   the discs behind — which is not hypothetical: it printed a ring with no marks
   on it (Vincenzo, 2026-08-18). A part should arrive in the orientation it is
   printed in; then there is nothing to rotate and nothing to forget."
  [cage which]
  (let [axis (if (keyword? which) which (ring-axis which))
        r (first (filter #(= axis (:axis %)) (:rings cage)))
        h (:cage-h cage)]
    (assoc (select-keys r [:inner :outer :crown :index])
           :axis axis
           :h h
           :marks (vec (for [[id a] (sort-by key (:anchors cage))
                             :when (= axis (anchor-axis id))]
                         {:id id
                          :position (unplace axis (:position a))
                          :heading (unplace axis (:heading a))}))
           :tabs (vec (for [t (:tabs cage) :when (= axis (:owner t))]
                        {:kind (:kind t)
                         :center (unplace axis (:center t))
                         :size (unplace axis (:size t))}))
           :slots (vec (for [sl (:stick-slots cage) :when (= axis (:axis sl))]
                         (-> sl
                             (update :position (partial unplace axis))
                             (update :heading (partial unplace axis))
                             (update :up (partial unplace axis)))))
           :rim-segments (when (:rim-marks? cage)
                           (rim-spans (:cage-marks cage))))))
