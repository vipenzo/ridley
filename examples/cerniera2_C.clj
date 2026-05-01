;; cerniera2_C — Skeleton-driven hinge.
;;
;; A single `hinge-skel` path defines the assembly axis with named
;; marks: pin-axis (the pivot) plus one mark per bracket centre.
;; Each component is built INDEPENDENTLY in its own local frame:
;;
;;   • three cylinders — pin, dome (block solid with the pin), pin hole
;;   • two C shells — inner, outer (creation-pose at the pin axis)
;;   • one bracket template (with countersunk screw bevel)
;;
;; The final hinge is composed by registering a "skeleton anchor" mesh
;; carrying the marks, and snapping each component into place via
;; (move-to :Skel :at :some-mark). No magic offsets at compose time —
;; every position comes from the skeleton.

;; ============================================================
;; 1. PARAMETERS
;; ============================================================

;; --- Listello (the wooden post the hinge grips) ---
(def listello-w 40)
(def listello-d 60)

;; --- Wall thicknesses & tolerances ---
(def gap 0.3) ; clearance between mating C surfaces
(def inner-wall 3) ; inner C wall thickness
(def panel-mount-thickness 4) ; outer C wall thickness

;; --- Hinge geometry ---
(def H 80) ; inner C length along the pin axis
(def pin-r 15) ; pin radius
(def pin-clearance 2) ; pin overhang past the outer C, per side
(def outer-extra-h 10) ; how much the outer C rises above the pin axis

;; --- Entry bevels (screw holes into the inner C) ---
(def bevel-base-r 5)
(def bevel-tip-r 2)
(def bevel-overlap 1)
(def bevel-axial-pos 15)
(def bevel-h-extra 2)

;; --- Brackets (symmetric triangular wings, panel-mount feet) ---
;; Axial positions of each bracket PAIR (L+R) along the assembly,
;; expressed as a fraction of H from the pin axis. Add more entries for
;; more pairs. Using fractions keeps the brackets "inside" automatically
;; when H is changed.
(def bracket-axials [0.625 -0.33])
(def bracket-half 11) ; half base width (along the assembly axis)
(def bracket-w (/ H 2))
(def bracket-up (/ H 2)) ; vertical height of the triangular wing
(def bracket-out (/ H 4)) ; how far the wing extends outward laterally

;; --- Countersunk screw bevels (cut into each bracket) ---
(def screw-cs-r1 (/ H 2))
(def screw-cs-r2 2)
(def screw-cs-depth (/ H 2))
(def screw-pos-out (* bracket-out 1.3)) ; halfway along the outward direction
(def screw-pos-up (/ bracket-up 2)) ; halfway up

;; --- Outer C back-wall opening (lets the inner C pivot out) ---
(def back-opening-extra 2)

;; ============================================================
;; 2. DERIVED VALUES
;; ============================================================

(def inner-w (+ listello-w (* 2 inner-wall)))
(def inner-d (+ listello-d (* 2 inner-wall)))
(def outer-w (+ inner-w (* 2 gap) (* 2 panel-mount-thickness)))
(def outer-d (+ inner-d (* 2 gap) (* 2 panel-mount-thickness)))
(def pin-hole-r (+ pin-r (* 2 gap)))
(def pin-length (+ outer-w (* 2 pin-clearance)))
(def hplus (+ H (/ inner-d 2)))
(def outer-h (+ hplus outer-extra-h))
(def outer-stagger (/ inner-d 4))

(resolution :n 64)

;; ============================================================
;; 3. SKELETON — composed from reusable sub-paths
;; ============================================================
;; The skeleton is a single `path` that walks along the assembly
;; (the "spine") dropping marks. Each side-trip is delegated to a
;; small helper that returns its own sub-path; `follow` splices it
;; into the spine and brings the turtle back to where it was.
;;
;;   `(arm lateral down name)` — drop a mark `name` at offset
;;   (`lateral`, -`down`) from the current spine position. Positive
;;   `lateral` goes to the +Y side, negative to -Y. The mark's
;;   heading faces the side where the arm went, so `move-to :align`
;;   on a piece will rotate it to face outward.

(defn arm
  "Drop a mark `mname` at offset (`side`, -`depth`) from the spine.
   Positive `side` goes to the +Y side, negative to -Y. The mark's
   heading faces the side where the arm went, so `move-to … :align`
   on a piece will rotate it to face outward.

   Wrapped in `side-trip`: lateral and vertical moves are scoped, so
   the spine is unaffected by the arm — the turtle returns to where
   it was when the arm started.

   NOTE: parameter names avoid `down` and `name` because those are
   Ridley/Clojure bindings that would be shadowed inside the path body."
  [side depth mname]
  (path
   (side-trip
    ;; turn to face the lateral direction (sign of `side` picks left vs right)
    (th (if (pos? side) 90 -90))
    ;; walk forward by |side| to reach the lateral offset
    ;; (we always walk *forward* since we already turned to face the right way;
    ;;  `f` with a negative arg would walk backward)
    (f (abs side))
    ;; descend by `depth` (use tv pirouette since `u` is blocked in path)
    (u (- depth))
    ;; drop the mark — heading now faces the lateral direction
    (mark mname))))

;; sub-path that walks `k * H` forward from pin-axis and drops both L+R
;; brackets at that spine position. Wrapped in `side-trip` so each pair
;; starts back at the pin-axis — no need to compute deltas between marks.
(defn bracket-pair [i k]
  (path
   (side-trip
    (f (* k H))
    (follow (arm (/ outer-w 2) (/ outer-d 2)
                 (keyword (str "bracket-" (inc i) "-l"))))
    (follow (arm (- (/ outer-w 2)) (/ outer-d 2)
                 (keyword (str "bracket-" (inc i) "-r")))))))

(def hinge-skel
  (path
   (mark :pin-axis)
   (doseq [[i k] (map-indexed vector bracket-axials)]
     (follow (bracket-pair i k)))))

;; ============================================================
;; 4. SCAFFOLD (panel + listello — visual reference, not printed)
;; ============================================================

(color 0x888888)
(def pannello (extrude (rect 1000 600) (f -15)))
(def gamba (extrude (rect listello-w listello-d) (f 600)))
(register Gamba (attach gamba (u 190) (f 30.5) (u 20) (tv -50)))

;; ============================================================
;; 5. THE THREE CYLINDERS
;; ============================================================
;; All built at origin with axis along +Y (after `(th 90)`),
;; ready to be snapped onto the pin-axis mark.

(def pin (attach (cyl pin-r pin-length) (th 90)))
(def pin-dome (attach (cyl (/ inner-d 2) inner-w) (th 90)))
(def pin-hole (attach (cyl pin-hole-r pin-length) (th 90)))

;; ============================================================
;; 6. INNER C SHELL (no pin/dome — those are added at compose time)
;; ============================================================
;; Box scooped to fit the listello, with entry bevels on both sides.
;; Creation-pose ends up at the pin-axis mark via cp-f.

(def inner-shell
  (attach
   (mesh-difference
    (box inner-w inner-d H)
    (mesh-union
     ;; Listello slot: open at the top, with an inner-wall floor
     (attach (box (- inner-w (* 2 inner-wall)) inner-d H) (u inner-wall))
     ;; Entry bevel — left side
     (attach (cone bevel-base-r bevel-tip-r (+ inner-wall bevel-h-extra))
             (rt (- (- (/ inner-w 2) bevel-overlap)))
             (f bevel-axial-pos)
             (th 90))
     ;; Entry bevel — right side
     (attach (cone bevel-base-r bevel-tip-r (+ inner-wall bevel-h-extra))
             (rt (- (/ inner-w 2) bevel-overlap))
             (f bevel-axial-pos)
             (th -90))))
   ;; Move creation-pose from box centre to the pin axis
   (cp-f (- (/ H 2)))))

;; ============================================================
;; 7. OUTER C SHELL (no pin hole, no brackets — added at compose time)
;; ============================================================
;; Box scooped for back-wall opening + inner cavity (with clearance).
;; Creation-pose at the pin hole (= pin axis in the assembled frame).

(def outer-shell
  (attach
   (mesh-difference
    (box outer-w outer-d outer-h)
    (mesh-union
     ;; Back-wall opening
     (attach (box outer-w (+ (* 2 inner-wall) back-opening-extra) outer-h)
             (u (/ outer-d 2)))
     ;; Inner cavity (with clearance on both axes)
     (box (+ inner-w (* 2 gap)) (+ inner-d (* 2 gap)) outer-h)))
   ;; Shift box so its axis lines up with the pin axis
   (f (- outer-stagger))
   ;; Creation-pose lands on the pin hole
   (cp-f (- outer-stagger (/ H 2)))))

;; ============================================================
;; 8. SINGLE BRACKET (symmetric triangular wing + countersunk screw)
;; ============================================================
;; An isoceles triangle (base = 2·bracket-half along the assembly axis,
;; apex at height bracket-up) extruded laterally outward by bracket-out.
;; Symmetry around the axial axis means the SAME mesh is used for both
;; left and right brackets — `move-to … :align` rotates it to face out.
;; A countersunk hole is cut vertically through the wing's centre.

(def bracket
  (mesh-difference
   (turtle (th -90) (f (/ bracket-out -2))
           (extrude
            (poly (- bracket-w) 0 0 0 0 bracket-up)
            (f bracket-out)))

   ;; countersunk screw bevel: vertical hole through the wing's centre
   (attach (cone screw-cs-r1 screw-cs-r2 screw-cs-depth)
           (f screw-pos-out)
           (u screw-pos-up)
           (tv 90))))

;; ============================================================
;; 9. ASSEMBLY — compose via the skeleton + move-to
;; ============================================================
;; The skeleton path itself is the assembly's source of truth: every
;; component snaps to one of its marks via `(move-to hinge-skel :at …)`.
;; Marks are resolved at the world origin since `hinge-skel` was built
;; from a fresh path (no parent context).

;; --- Inner C: shell + pin + dome, all snapped to :pin-axis ---
(color 0x00ff00)
(def hinge-inner
  (mesh-union
   (attach inner-shell (move-to hinge-skel :at :pin-axis))
   (attach pin (move-to hinge-skel :at :pin-axis))
   (attach pin-dome (move-to hinge-skel :at :pin-axis))))

;; --- Outer C: shell + brackets - pin hole ---
;; Each bracket-mark already encodes both the bracket's final position
;; AND its orientation (heading flipped for right-side brackets in the
;; skeleton path). `move-to … :align` translates and rotates the mesh
;; to match the mark's full pose, so no extra rt/th/f is needed.
;; `(anchors hinge-skel)` returns the path's marks resolved at the world
;; origin — we just filter by name.
(def staffe
  (concat-meshes
   (for [m (filter #(re-find #"bracket" (name %)) (keys (anchors hinge-skel)))]
     (attach bracket (move-to hinge-skel :at m :align)))))

(def hinge-outer
  (mesh-difference
   (mesh-union
    (attach outer-shell (move-to hinge-skel :at :pin-axis))
    staffe)
   (attach pin-hole (move-to hinge-skel :at :pin-axis))))

;; --- Final hinge as a single print-in-place model ---
(register Hinge (mesh-union hinge-outer hinge-inner))

;; --- For separate STL exports, uncomment these ---
;(register A hinge-outer)
;(register B hinge-inner)
