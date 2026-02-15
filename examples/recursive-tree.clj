; === Recursive Tree ===
;
; A 3D fractal tree built with push-state/pop-state branching.
;
; At each level of recursion, the trunk splits into several branches.
; Each branch is thinner and shorter than its parent, angled outward
; with a random-ish spread (using the golden angle for even distribution).
; Branches are extruded cylinders joined at the fork points.
;
; Demonstrates:
; - push-state / pop-state for branching (save and restore turtle pose)
; - Recursive functions with (defn) in the DSL
; - Parametric design: depth, branching factor, taper ratio
; - Mixing extrusion (cyl) with turtle navigation
;
; Try changing:
; - max-depth for more or fewer levels (3-6 range is good)
; - n-branches for bushier or sparser trees
; - spread-angle for wider or tighter branching
; - taper for how quickly branches thin out

(def max-depth 4)
(def n-branches 3)
(def spread-angle 35)
(def taper 0.65)
(def golden-angle 137.508)

(defn branch [depth length radius]
  (when (> depth 0)
    ; Draw this branch segment
    (cyl radius (- radius (* radius (- 1 taper))) length)
    (f length)
    ; Spawn child branches
    (dotimes [i n-branches]
      (push-state)
      (tr (* i (/ 360 n-branches)))  ; distribute around trunk
      (tv spread-angle)              ; angle outward
      (tr (* i golden-angle))        ; golden angle twist for variety
      (branch (dec depth)
              (* length taper)
              (* radius taper))
      (pop-state))))

; Ground the tree: start with trunk going up
(branch max-depth 20 3)
