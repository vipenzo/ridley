;; ============================================================================
;; PRINTING THE REGISTRATION CAGE
;; ============================================================================
;;
;; The cage is the plate's alternative: instead of resting the object ON the
;; reference, the reference travels AROUND the object. Three flat rings,
;; mutually orthogonal, the part anchored at the centre. Wherever you shoot
;; from, the frame holds at least one well-seen ring — so every photo
;; registers on its own, with no turntable, no angles, no NOTE.md, no session
;; fusion.
;;
;; This file IS the code that produces the cage — it used to be the builtin
;; `acquire-cage` library, and became an example by Vincenzo's decision (4/9):
;; it is used once in a while (it must not clutter the library list forever)
;; and it deserves to be READ and MODIFIED, not hidden behind a register.
;;
;; HOW TO USE IT: evaluate the file (the definitions produce nothing by
;; themselves), then uncomment the command you need at the bottom and
;; re-evaluate.
;;
;; WHERE THE GEOMETRY LIVES — and where divergence can hide. Nothing here
;; states a dimension twice: positions, tabs, seats, keys and channels are all
;; READ from the `registration-cage` proxy, the same data `edit-acquire` draws
;; over the photographs. This file and that drawing are two RENDERERS of one
;; model, so editing print cosmetics here cannot make them disagree. What CAN
;; diverge is the PLASTIC from the MODEL: replace a model read with a number
;; of your own, or move something by hand, and you will print a cage the
;; program neither draws nor searches for — the same disease as an undeclared
;; flip, discovered the same way (the drawn cage stops matching the photos).
;; Geometry changes belong in the model (ridley.photogrammetry.cage) or in
;; the declaration below; here you only decide how it prints. The few things
;; this file does restate, so you know where the boundary runs: `inlay` (the
;; disc pocket depth — print-only, it never moves the centres the solver
;; looks for), the stick's −0.4 rule (derived from the cage's own channel,
;; but the constant is repeated), and the `to-ring-frame` permutation
;; (verified against the matrices).
;;
;; NOTE (4/9): the rim segments (:rim-marks?) are not in the print geometry
;; yet — the model draws them over the photos, but the two-colour ribbons are
;; missing here. Ask for them when you decide to reprint.

;; --- Material ----------------------------------------------------------------

(def base-color
  "The rings' light colour. Pick a MATTE filament: a glossy or silk one makes
   specular highlights, and the detector looks for 'a dark round spot' — a
   highlight is exactly the opposite, and hands it dozens."
  0xEFE7D8)

(def mark-color
  "The discs' dark colour. The more contrast against the base, the tighter the
   threshold the detector can afford."
  0x1A1A1A)

(def inlay
  "How deep the mark pockets are, in mm. The discs sit INLAID FLUSH: a flat
   region of colour, not a groove and not a relief. The boundary between two
   colours is where it is, however you look at it; the edge of a groove moves
   with the light, and with it the centre the program measures."
  0.6)

;; --- A cylinder along an axis ------------------------------------------------
;; `cyl` is born with its axis along the turtle's forward (+X). Pockets and
;; discs are drilled along the normal of the face that carries them, which for
;; each ring is its own axis.

(defn ax-cyl [axis r h]
  (cond
    (= axis :x) (cyl r h)
    (= axis :y) (rotate (cyl r h) :z 90)
    :else       (rotate (cyl r h) :y 90)))

(defn axis-of
  "A mark's axis, deduced from its normal: the component that is not zero."
  [[nx ny nz]]
  (cond (not= 0.0 nx) :x
        (not= 0.0 ny) :y
        :else :z))

(defn v+ [a b] (mapv + a b))
(defn v* [v k] (mapv (fn [c] (* c k)) v))

;; --- One ring ----------------------------------------------------------------

(defn ring-letter
  "The letter of the ring a mark belongs to: `:zp07` → \"z\", and `:zero-zp` →
   \"z\" as well — which is why this function exists instead of just reading
   the first letter: 'zero' starts with z."
  [id]
  (let [s (name id)]
    (if (and (> (count s) 5) (= "zero-" (subs s 0 5)))
      (subs s 5 6)
      (subs s 0 1))))

(defn ring-anchors
  "The marks (crown + zero-index) sitting on ring `axis` of cage `c`, both
   faces."
  [c axis]
  (filter (fn [[id _]] (= (name axis) (ring-letter id))) (:anchors c)))

;; --- The part-holder: elliptical sticks and their slots ------------------------
;;
;; Designed, printed and TESTED by Vincenzo (2026-08-25): an elliptical stick
;; slides through an elliptical channel with clearance; a small twist and it
;; locks — the stick is its own cam, no levers, no extra parts. Four or five
;; sticks, entering from different rings, cage the part at the centre with no
;; clothes-pegs and no tape.
;;
;; The sections live in the PROXY (each slot's `:channel-r`) and since 4/9
;; they SCALE with the cage — (d/176)^0.75, floored at 1: longer sticks too
;; thin snap — with the 0.4mm clearances ABSOLUTE (printer tolerance, not
;; physics: scaling them would break the quarter-turn). At ⌀176 the numbers
;; are the tested ones: stick 4.0×3.6, channel 4.4×4.0.

(defn stick
  "A printable stick for cage `c`, `len` long (default 60; reaching the centre
   from a ⌀176's big ring takes ~80). The section is dictated by the cage's
   own CHANNEL — the quarter-turn's bite is guaranteed at every diameter.
   Print it LYING DOWN: the slight roundness the bridge side loses falls where
   the fit has clearance, not where it bites."
  ([c] (stick c 60))
  ([c len]
   (let [[across _up] (:channel-r (first (:stick-slots c)))
         maj across                ; major semi-axis = the channel's bite
         mn (- maj 0.2)]           ; minor semi-axis: −0.4 on the diameter
     (scale (cyl maj len) 1 1.0 (/ mn maj)))))

(defn punta-tricuspide
  "Vincenzo's three-pointed foot (2026-08-25), printed and proven: three
   squashed spheres blended into a three-contact grip — on a convex surface
   three points neither slip nor roll. It mounts on a stick's tip by the same
   principle as the slot: elliptical channel, insert, twist, locked. Optional:
   for delicate or slippery parts; elsewhere the stick's bare tip is enough.

   Built at the turtle's current pose, like every DSL part. Caveat: its inner
   channel is the ⌀176 one — for a scaled cage widen it by hand (this is a
   tested keepsake, not parametric)."
  []
  (mesh-difference
   (mesh-union
    (attach (cyl 3 3) (f 2))
    (sdf-blend
     (sdf-blend
      (attach (scale (sdf-sphere 2) 2 1 1) (cp-f -3) (tv 120))
      (attach (scale (sdf-sphere 2) 2 1 1) (cp-f -3) (tv -0) (th 60) (tv -75) (th 30))
      1.5)
     (attach (scale (sdf-sphere 2) 2 1 1) (cp-f -3) (tv -120) (th -15) (tv -15) (th -30))
     1.5))
   (attach (extrude (scale-shape (circle 2) 1.1 1) (f 60)) (f -20))))

;; --- From the flat frame to the cage's ---------------------------------------

(defn to-ring-frame
  "Takes a mesh built in the ring's FLAT frame (ring in XY, normal +Z — the
   print pose) into the frame that ring has in the cage. It is the same cyclic
   permutation as the model's `place`, written as two axis rotations —
   verified against the matrices: for :x, u→y v→z n→x; for :y, u→z v→x n→y."
  [m axis]
  (cond
    (= axis :z) m
    (= axis :x) (rotate (rotate m :x 90) :z 90)
    :else       (rotate (rotate m :z -90) :x -90)))

(defn slot-pieces
  "[bodies cuts] of one ring's slots — bodies to UNION onto the band, channels
   to SUBTRACT. Each slot arrives with its own pose data (from the printable's
   :slots or the cage's :stick-slots); geometry is built in the flat frame and
   posed with `to-ring-frame` — the numbers live in one place, the proxy."
  [slots h axis flat?]
  (let [;; ORIENT FIRST, TRANSLATE AFTER: rotate turns about the creation-pose,
        ;; which mesh-translate carries along — rotating after the translation
        ;; spins the block about itself instead of about the centre (found
        ;; live: every slot landed at azimuth zero).
        orient (fn [m azim] (let [r (rotate m :z azim)]
                              (if flat? r (to-ring-frame r axis))))
        one (fn [mk lift]
              (map (fn [sl]
                     (mesh-translate (orient (mk sl) (:azimuth-deg sl))
                                     (v+ (:position sl) (v* (:up sl) (lift sl)))))
                   slots))]
    [(one (fn [sl] (box (:body-w sl) (:body-h sl) (:body-len sl)))
          (fn [sl] (:body-lift sl)))
     (one (fn [sl] (let [[across up] (:channel-r sl)]
                     (scale (cyl across 80) 1 1.0 (/ up across))))
          (fn [sl] (+ (/ h 2.0) (:channel-lift sl))))]))

;; --- One ring, base + discs ---------------------------------------------------

(defn build-ring
  "Ring `axis` of `c` as [base discs]. With `flat?` true the ring comes out in
   ITS OWN frame — flat in XY, marks on the ±Z faces, tabs upward — the pose
   it prints in; with `flat?` false it comes out where it sits in the cage."
  [c axis flat?]
  (let [p (cage-printable-ring c axis)
        h (:h p)
        disc-r (:mark-disc-r c)
        r (first (filter (fn [x] (= axis (:axis x))) (:rings c)))
        ax (if flat? :z axis)
        marks (if flat?
                (:marks p)
                (map (fn [[_ a]] {:position (:position a) :heading (:heading a)})
                     (ring-anchors c axis)))
        tabs-boxes (if flat?
                     (:tabs p)
                     (filter (fn [t] (= axis (:owner t))) (:tabs c)))
        slots (if flat?
                (:slots p)
                (filter (fn [sl] (= axis (:axis sl))) (:stick-slots c)))
        [slot-bodies slot-cuts] (slot-pieces slots h axis flat?)
        ;; the cutter overshoots the face by 2mm: a clean cut, no coplanar
        ;; faces (the known CSG-artifact recipe)
        over 2.0
        pocket (fn [m]
                 (let [n (:heading m)]
                   (mesh-translate (ax-cyl (axis-of n) disc-r (+ inlay over))
                                   (v+ (:position m) (v* n (/ (- over inlay) 2.0))))))
        disc (fn [m]
               (let [n (:heading m)]
                 (mesh-translate (ax-cyl (axis-of n) disc-r inlay)
                                 (v+ (:position m) (v* n (- (/ inlay 2.0)))))))
        annulus (mesh-difference (ax-cyl ax (:outer r) h)
                                 (ax-cyl ax (:inner r) (+ h 2)))
        ;; `box` takes (right, up, forward) — the turtle's convention — and at
        ;; the starting pose those are (y, z, x). The tabs arrive as world
        ;; extents [dx dy dz], so they go back in that order.
        as-box (fn [t]
                 (let [sz (:size t)]
                   (mesh-translate (box (nth sz 1) (nth sz 2) (nth sz 0))
                                   (:center t))))
        ;; a box is either ring MATERIAL (tabs, stops, the keys' pins) or a
        ;; CUT in its owner: the notches that receive the pins, and the gen-2
        ;; seat pockets the partner's tabs sink into
        cut? (fn [t] (contains? #{:key-notch :seat} (:kind t)))
        cuts (concat (map as-box (filter cut? tabs-boxes))
                     slot-cuts)
        tabs (concat (map as-box (remove cut? tabs-boxes))
                     slot-bodies)
        solid (as-> annulus m
                (if (empty? tabs) m (mesh-union (cons m tabs)))
                (if (empty? cuts) m (mesh-difference (cons m cuts))))]
    ;; :export-group binds the two meshes into ONE object with two parts.
    ;; Without it the slicer treats them as independent bodies.
    (let [g (str "anello-" (name axis))]
      [(-> (mesh-difference (cons solid (map pocket marks)))
           (color base-color)
           (assoc :export-group g :export-name "anello"))
       (-> (mesh-union (map disc marks))
           (color mark-color)
           (assoc :export-group g :export-name "dischetti"))])))

(defn ring-part
  "Ring `axis` of cage `c` as [base discs], IN THE CAGE'S POSE: for looking at
   it assembled."
  [c axis]
  (build-ring c axis false))

;; --- The whole cage -----------------------------------------------------------

(def ring-keys
  "The three rings, largest to smallest. Named by SIZE and not by axis,
   because that is how you tell them apart with the parts in hand."
  [:big :medium :small])

(defn ring-axis-of
  "The axis of ring `which` (:big/:medium/:small) of cage `c`."
  [c which]
  (let [i (first (keep-indexed (fn [i kk] (when (= kk which) i)) ring-keys))]
    (when (nil? i)
      (throw (js/Error. (str "l'anello si chiede con :big, :medium o :small — non "
                             which "."))))
    (:axis (nth (:rings c) i))))

(defn files
  "The three files to print for cage `c`, as [name parts] pairs: ONE RING PER
   FILE, with its two crowns of discs — in a slicer a ring and its discs are
   two objects, and moving one without the other yields a part that prints
   beautifully and is useless."
  [c]
  (let [d (:cage-d c)]
    (mapv (fn [k r] [(str "gabbia-" (round d) "-" (name k) ".3mf")
                     (build-ring c (:axis r) true)])
          ring-keys (:rings c))))

(defn make-cage-ring
  "The meshes of one ring of cage `c`, for you to register — :big/:medium/
   :small or :all (the default). Returns a VECTOR of meshes (light ring +
   dark discs), which `register` accepts as is."
  ([c] (make-cage-ring c :all))
  ([c which]
   (if (= :all which)
     (vec (apply concat (map (fn [k] (ring-part c (ring-axis-of c k))) ring-keys)))
     (vec (ring-part c (ring-axis-of c which))))))

(defn make-print-ring
  "The meshes of ONE ring in PRINT pose — flat, tabs up, ready to export with
   nothing to rotate. The difference from `make-cage-ring` is orientation
   only: that one is for looking at the assembled cage, this one for
   printing."
  [c which]
  (vec (build-ring c (ring-axis-of c which) true)))

(defn save-3mf
  "Saves cage `c` as THREE two-colour 3MF files into folder `dir` — one ring
   per file, with its two crowns.

   HOW TO PRINT: one ring at a time, or all three on one plate (the colour
   changes then happen by HEIGHT: bottom discs, body, top discs). Always use
   a BRIM: a thin wide ring curls as it cools. MATTE filaments. If you move a
   ring in the slicer, move its discs with it.

   HOW TO ASSEMBLE (gen 2): each tab DROPS into its seat pocket — that is the
   azimuth, no eyeballing — and the two keys refuse wrong rotations and
   flips: if a ring will not sit, it is turned. Six joints, epoxy; the two
   big rings first, then the part at the centre, the small ring last."
  [c dir]
  (let [fs (files c)]
    (save-3mf-set-at fs dir)
    (println (str "Gabbia ⌀" (round (:cage-d c)) " (gen " (or (:cage-gen c) 1) "): tre anelli ⌀"
                  (apply str (interpose " ⌀" (map (fn [r] (round (* 2 (:outer r))))
                                                  (:rings c))))
                  ", " (:cage-marks c) " mark per faccia su sei facce."))
    (println "  Tre file, un anello per ciascuno — scegli la CARTELLA nel dialogo:")
    (doseq [f fs] (println (str "    " (first f))))
    (println (str "  Apertura libera ⌀" (round (:aperture c))
                  ": è il pezzo più grande che riesci a portare al centro."))
    (println (str "  Dischetti ⌀" (* 2 (:mark-disc-r c))
                  " incassati a filo, spessore anelli " (:cage-h c) " mm."))
    (when (:rim-marks? c)
      (println "  ATTENZIONE: i segmenti sul bordo NON sono ancora nella geometria di stampa."))
    (println "  Stampa col BRIM e filamenti OPACHI.")))

;; --- The cradle ---------------------------------------------------------------

(defn cradle
  "The nest to rest the ⌀`d` cage on while you shoot: a flared ring the cage
   settles into in ANY orientation without rolling. Not a base: a cage bolted
   to a support is back to photographing half a sphere, which is what the
   plate already did."
  [d]
  (let [r-out (* 0.42 d)
        r-wide (* 0.34 d)
        r-narrow (* 0.18 d)
        h (* 0.07 d)]
    (-> (mesh-difference (rotate (cyl r-out h) :y 90)
                         (rotate (cone r-narrow r-wide (+ h 2)) :y 90)
                         (rotate (cone r-wide r-narrow (+ h 2)) :y 90))
        (color base-color))))

(defn save-cradle
  "Saves the cradle for a ⌀`d` cage into folder `dir`. Print it hole-up, no
   supports: the flare holds itself."
  [d dir]
  (save-3mf-at (cradle d) (str dir "/culla-" (round d) ".3mf"))
  (println (str "Culla per la gabbia ⌀" (round d)
                ": scegli la cartella nel dialogo che si apre.")))

;; --- After printing: the caliper correction ------------------------------------

(defn measured
  "Cage `c`'s proxy with the marks corrected onto what ACTUALLY came out of
   the printer: `mis` is the diameter the caliper reads across the big ring,
   outer edge to outer edge. Use it as the session's :proxy — a printer errs
   in scale by a few tenths of a percent, and that error never presents
   itself: registration succeeds with excellent residuals and every
   measurement comes out scaled. One number, because three rings printed by
   the same machine the same way share a single factor (if it erred
   differently on the two axes, the rings would come out oval — and an oval
   ring shows)."
  [c mis]
  (let [s (/ mis (* 1.0 (:cage-d c)))]
    (assoc c :anchors
           (into {}
                 (map (fn [[id a]]
                        ;; POSITIONS scale; heading/up are directions
                        [id (assoc a :position (mapv (fn [x] (* x s)) (:position a)))])
                      (:anchors c))))))

;; ============================================================================
;; OPERATIONAL COMMANDS — uncomment what you need and re-evaluate the file
;; ============================================================================

(def diameter 176)

(def cage
  "THE declaration, in one place: the next print is gen 2 (seat pockets + the
   second key: no more eyeballed phases, no more possible flips) with the rim
   segments. The ALREADY-GLUED battiscopa cage stays
   (registration-cage :d 176 :flips #{:y :z}) — declare it as it is, not as
   you wish it were."
  (registration-cage :d diameter :gen 2 :rim-marks? true))

;; Look at it assembled (three rings in pose, two colours):
;; (register Gabbia (make-cage-ring cage))

;; One ring in print pose, then export:
;; (register Grande (make-print-ring cage :big))
;; (export :Grande :3mf)

;; All three 3MFs into one folder (a single dialog):
;; (save-3mf cage "~/Downloads")

;; The cradle to rest it on while shooting:
;; (save-cradle diameter "~/Downloads")

;; A stick (section read from the cage's own channel: the bite is guaranteed):
;; (register Stick (stick cage 80))
;; The three-pointed foot, for delicate parts:
;; (register Punta (punta-tricuspide))

;; After printing, with the caliper (example: 175.4 read across the big ring):
;; (measured cage 175.4)   ; pass as the session's :proxy
