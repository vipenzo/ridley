; === Recursive Tree ===
;
; A 3D fractal tree built with nested turtle scopes.
;
; At each level of recursion, the trunk splits into several branches.
; Each branch is thinner and shorter than its parent, angled outward
; with a golden-angle twist for even distribution. Every segment is a
; cone frustum whose far radius is the next level's near radius, so the
; branches taper continuously across the forks.
;
; Demonstrates:
; - (turtle ...) scopes for branching: the child turtle starts from the
;   parent's pose, moves on its own copy, and the parent is untouched when
;   the scope returns — the pose comes back to the fork by itself
; - Recursive functions with (defn) in the DSL
; - Parametric design: depth, branching factor, taper ratio
; - A primitive (cone, axis along the heading) placed by turtle navigation
; - A function that RETURNS its meshes (a vector), registered once at the end
;
; Try changing:
; - max-depth for more or fewer levels (3-5 range is good)
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
    (let [r-child (* radius taper)
          ; this segment: a frustum along the heading, from radius to r-child
          segment (cone radius r-child length)]
      (f length)
      ; the children: each in its own scope, so every child forks from the
      ; SAME pose — the tip of this segment — whatever its siblings did
      (into [segment]
            (mapcat (fn [i]
                      (turtle
                        (tr (* i (/ 360 n-branches)))   ; distribute around the trunk
                        (tv spread-angle)               ; angle outward
                        (tr (* i golden-angle))         ; golden-angle twist for variety
                        (branch (dec depth) (* length taper) r-child)))
                    (range n-branches))))))

; Ground the tree: the trunk goes up (cones grow along the heading, and the
; turtle starts facing +X, so tilt it vertical first)
(register tree (concat-meshes (turtle :reset (tv 90) (branch max-depth 20 3))))
