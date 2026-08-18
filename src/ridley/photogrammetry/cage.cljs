(ns ridley.photogrammetry.cage
  "Parametric registration CAGE proxy for edit-acquire — three concentric,
   mutually orthogonal flat rings, each carrying a crown of marks on BOTH faces
   plus its own zero-index, under :anchors. The sibling of `plate`: same frame,
   same anchor shape, same solver, different geometry. The printable two-colour
   rings and the cradle live in the `acquire-cage` builtin library (fabrication,
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

(defn joint-tabs
  "The boxes that hold a cage of diameter `d` and thickness `h` together:
   {:owner :partner :along :sign :kind :center [x y z] :size [dx dy dz]}.

   `:owner` is the ring the box is printed as part of — four joints on the
   smallest ring, two on the middle one, none on the largest. `:kind` is

     :lap   the glued tab itself, standing proud of its own ring so the contact
            with the partner is a face and not an edge;
     :stop  a lip just BEYOND the partner's rim, so the partner drops between the
            pair of them and lands where it belongs instead of where the eye put
            it. Only on the smallest ring's joints, and that restriction is the
            whole design of it: a stop must reach across the partner's plane to
            block anything, and the partner is fitted by sliding along its own
            normal — so any lip lying in that path stops the ASSEMBLY, not the
            ring. The four small-ring lips sit outside the partner's outer radius
            and are never swept through; the middle ring's would sit exactly where
            the largest ring's rim passes on its way in, so they are not made."
  [d h]
  (let [band (* d band-frac)
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
              lap (fn [axis]
                    (condp = axis
                      shared [(* sign (/ (+ (- q-outer root) tip) 2.0)) len]
                      p-axis [(+ (/ h 2.0) tab-clearance (/ tab-width 2.0)) tab-width]
                      q-axis rise))
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
            stopped? (conj (box :stop stop)))))
      (for [pr pairs sign [1 -1]] [pr sign])))))

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

(defn- face-anchors
  "The crown + zero-index anchors of ONE marked face: `n` marks on a circle of
   radius `crown-r` starting at `crown-phase`, the index radially inside mark 0,
   all at n = s·h/2."
  [axis s crown-r index-r n h]
  (let [off (* s (/ h 2.0))
        heading (axis-unit axis s)
        step (/ (* 2.0 Math/PI) n)
        phase (crown-phase n)
        at (fn [r a] (place axis [(* r (Math/cos a)) (* r (Math/sin a)) off]))
        radial (fn [a] (place axis [(Math/cos a) (Math/sin a) 0.0]))]
    (into {(index-id axis s) {:position (at index-r phase)
                              :heading heading
                              :up (radial phase)}}
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

   Returns a three-ring mesh with, under :anchors, six crowns of `marks` plus six
   zero-indices — `:zp00`…, `:zm00`…, `:yp00`…, `:ym00`…, `:xp00`…, `:xm00`…,
   `:zero-zp` … — and `:mark-disc-r`, `:cage-d`, `:cage-marks`, `:cage-h` and
   `:rings` (each ring's axis and radii, which the `acquire-cage` library prints
   from). All in the solver's object frame.

     (edit-acquire \"/Users/me/scans/testina\" {:proxy (registration-cage :d 176)})

   The anchors are non-coplanar, so `pnp/solve-pnp` routes them to the general
   DLT rather than the planar homography — pick marks on TWO rings and the pose
   is conditioned on all six degrees of freedom with no mirror twin to reject."
  [& {:keys [d marks disc h seg]
      :or {h default-h seg 64}}]
  (when-not (and (number? d) (pos? d))
    (throw (js/Error.
            (str "registration-cage: dimmi il diametro della gabbia — quello "
                 "dell'anello PIÙ GRANDE — per esempio (registration-cage :d 176).\n"
                 "Non c'è un default apposta: è l'unico numero che lega il modello "
                 "alla gabbia che hai in mano, e se è sbagliato NON si presenta come "
                 "un errore — la registrazione riesce lo stesso, con residui ottimi, "
                 "e tutte le misure escono scalate in silenzio."))))
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
        anchors (reduce (fn [acc {:keys [axis crown index]}]
                          (into acc (concat (face-anchors axis 1 crown index n h)
                                            (face-anchors axis -1 crown index n h))))
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
     :rings rings
     ;; Fabrication rides on the proxy for the same reason the marks do: the
     ;; `acquire-cage` library must not restate any of this, or the printed cage
     ;; and the model of it drift apart without either one looking wrong.
     :tabs (joint-tabs d h)
     :aperture (aperture d)}))

(defn printable-ring
  "Ring `which` (:x/:y/:z, or an index) of an already-built `cage`, described in
   ITS OWN frame: flat in XY, marks on the ±Z faces, tabs rising in +Z.

   {:axis :inner :outer :crown :index :h :marks [{:id :position :heading}] :tabs [{:center :size}]}

   Same numbers as the cage — they are the cage's own anchors and tabs put
   through `unplace`, not a second computation — only turned the way a printer
   wants them.

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
                        {:center (unplace axis (:center t))
                         :size (unplace axis (:size t))})))))
