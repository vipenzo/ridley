(ns ridley.editor.attach-simplify
  "Condense an edit-attach command list. Pure: items are the session's own —
   gestural [cmd val] pairs and verbatim source strings.

   The algebra it leans on: inside attach, the plain rigid commands
   (f rt u th tv tr) move the POSE and the geometry rides along, while the cp-*
   commands and stretches change only the geometry RELATIVE to the pose, in the
   pose's own frame. The two groups therefore commute, and each can be
   condensed on its own. A verbatim item is opaque and walls everything; inside
   the relative group a stretch walls the cp-* moves (it pivots on the pose,
   which a cp translation does not move).

   Two strengths:
   - `merge-exact` (always, on confirm): sums translation runs axis by axis,
     same-axis rotations, stretch runs; drops what cancels. Values stay the
     ones the user typed or stepped.
   - `simplify` (the panel's Simplify button, and the confirm of a session
     the SpaceMouse wrote in): rewrites each rigid run as its
     minimal form — at most (f rt u th tv tr) for the pose, (cp-f cp-rt cp-u
     cp-th cp-tv cp-tr) for the geometry — rounded to 0.01. A run keeps its
     exact merge when that is already as short."
  (:require [clojure.string :as str]))

;; ============================================================
;; Item classification
;; ============================================================

(def ^:private move-cmds #{:f :rt :u})
(def ^:private turn-cmds #{:th :tv :tr})
(def ^:private cp-move-cmds #{:cp-f :cp-rt :cp-u})
(def ^:private cp-turn-cmds #{:cp-th :cp-tv :cp-tr})

(defn- gestural? [item] (and (vector? item) (keyword? (first item))))

(defn- pose-item?
  "A plain rigid command: moves the pose, the geometry rides along."
  [item]
  (and (gestural? item) (number? (second item))
       (or (move-cmds (first item)) (turn-cmds (first item)))))

(defn- relative-item?
  "Changes the geometry relative to the pose: cp-* or a stretch."
  [item]
  (and (gestural? item)
       (let [[cmd v] item]
         (or (and (number? v) (or (cp-move-cmds cmd) (cp-turn-cmds cmd)))
             (and (= cmd :scale) (vector? v) (= 3 (count v)))))))

(defn- segments
  "Split into runs that can be condensed, separated by walls (verbatim items,
   anything unrecognized). Returns [[:run items] | [:wall item] …]."
  [commands]
  (reduce (fn [acc item]
            (if (or (pose-item? item) (relative-item? item))
              (if (= :run (first (peek acc)))
                (conj (pop acc) [:run (conj (second (peek acc)) item)])
                (conj acc [:run [item]]))
              (conj acc [:wall item])))
          [] commands))

;; ============================================================
;; A — exact merge
;; ============================================================

(def ^:private group-of
  "Commands that commute with their neighbours of the same group and are
   therefore summed (or, for :scale, multiplied) into one slot."
  {:f :move :rt :move :u :move
   :cp-f :cp-move :cp-rt :cp-move :cp-u :cp-move
   :scale :stretch})

(def ^:private group-order
  {:move [:f :rt :u] :cp-move [:cp-f :cp-rt :cp-u]})

(defn- zero-angle? [a]
  (let [m (mod a 360)] (or (< m 0.001) (> m (- 360 0.001)))))

(defn- empty-group? [[kind v]]
  (if (= kind :stretch)
    (every? #(< (Math/abs (- % 1.0)) 0.001) v)
    (every? #(< (Math/abs %) 0.001) (vals v))))

(defn- merge-stack
  "Reduce items onto a stack whose entries are either a group [kind v] or a
   rotation [cmd angle]. Cancelling entries are popped, so what they separated
   gets the chance to merge."
  [items]
  (reduce
   (fn [stack [cmd v]]
     (let [top (peek stack)]
       (if-let [kind (group-of cmd)]
         (let [grouped? (and top (= kind (first top)))
               base (if grouped? (second top) (if (= kind :stretch) [1 1 1] {}))
               merged (if (= kind :stretch)
                        [kind (mapv * base v)]
                        [kind (update base cmd (fnil + 0) v)])
               stack (if grouped? (pop stack) stack)]
           (if (empty-group? merged) stack (conj stack merged)))
         (if (and top (= cmd (first top)))
           (let [sum (+ (second top) v)]
             (if (zero-angle? sum) (pop stack) (conj (pop stack) [cmd sum])))
           (if (zero-angle? v) stack (conj stack [cmd v]))))))
   [] items))

(defn- tidy
  "Strip float noise from sums: 0.1 + 0.2 must still print as 0.3."
  [x]
  (/ (Math/round (* x 1e6)) 1e6))

(defn- unstack [stack]
  (mapcat (fn [[k v]]
            (case k
              :stretch [[:scale (mapv tidy v)]]
              (:move :cp-move) (for [c (group-order k)
                                     :let [x (get v c 0)]
                                     :when (>= (Math/abs x) 0.001)]
                                 [c (tidy x)])
              [[k (tidy v)]]))
          stack))

(defn- exact-run [items]
  (concat (unstack (merge-stack (filter pose-item? items)))
          (unstack (merge-stack (filter relative-item? items)))))

(defn merge-exact
  "Exact condensation: same pose, same values, fewer commands. Verbatim items
   are kept character for character and never merged across."
  [commands]
  (vec (mapcat (fn [[k x]] (if (= k :wall) [x] (exact-run x)))
               (segments commands))))

;; ============================================================
;; B — minimal form
;; ============================================================
;; Frame: the pose at the start of the run, h = +X, u = +Z, r = h × u = -Y
;; (the turtle's default). th/tv/tr turn by +angle about the CURRENT up /
;; right / heading (th+ left, tv+ nose up, tr+ right edge down).

(def ^:private h0 [1 0 0])
(def ^:private u0 [0 0 1])
(def ^:private r0 [0 -1 0])

(defn- dot [a b] (reduce + (map * a b)))
(defn- mat*v [m v] (mapv #(dot % v) m))
(defn- transpose [m] (apply mapv vector m))
(defn- mat* [a b] (let [bt (transpose b)] (mapv (fn [row] (mapv #(dot row %) bt)) a)))
(def ^:private identity3 [[1 0 0] [0 1 0] [0 0 1]])

(defn- rot
  "Rotation matrix about unit `axis` by `deg` (right-hand rule)."
  [[x y z] deg]
  (let [a (* deg (/ Math/PI 180)) c (Math/cos a) s (Math/sin a) t (- 1 c)]
    [[(+ c (* t x x)) (- (* t x y) (* s z)) (+ (* t x z) (* s y))]
     [(+ (* t x y) (* s z)) (+ c (* t y y)) (- (* t y z) (* s x))]
     [(- (* t x z) (* s y)) (+ (* t y z) (* s x)) (+ c (* t z z))]]))

(def ^:private local-axis
  {:f h0 :rt r0 :u u0 :th u0 :tv r0 :tr h0
   :cp-f h0 :cp-rt r0 :cp-u u0 :cp-th u0 :cp-tv r0 :cp-tr h0})

(defn- deg [rad] (* rad (/ 180 Math/PI)))

(defn- norm-angle [a]
  (let [m (mod (+ a 180) 360)] (- m 180)))

(defn- round2 [x]
  (let [r (/ (Math/round (* x 100)) 100)]
    (if (zero? r) 0 r)))

(defn- euler-solutions
  "The intrinsic th→tv→tr angles of rotation `R` (R = Rot(u,α)·Rot(r,β)·Rot(h,γ)):
   the principal solution and its twin (α+180, 180-β, γ+180). At β = ±90 only
   α + γ is defined and α is taken as 0."
  [R]
  (let [[hx hy hz] (mat*v R h0)
        hl (- (dot [hx hy hz] r0))          ; component toward left
        horiz (Math/hypot hx hl)
        gamma-for (fn [a b]
                    (let [A (mat* (rot u0 a) (rot r0 b))
                          M (mat* (transpose A) R)
                          [_ my mz] (mat*v M u0)]
                      (deg (Math/atan2 (- my) mz))))
        b (deg (Math/atan2 hz horiz))]
    (if (< horiz 1e-9)
      [[0 b (gamma-for 0 b)]]
      (let [a (deg (Math/atan2 hl hx))
            a2 (+ a 180) b2 (- 180 b)]
        [[a b (gamma-for a b)]
         [a2 b2 (gamma-for a2 b2)]]))))

(defn- best-angles
  "Fewest non-zero (rounded) angles, then the smallest total turn."
  [R]
  (->> (euler-solutions R)
       (map (fn [angles] (mapv (comp round2 norm-angle) angles)))
       (sort-by (fn [as] [(count (remove zero? as)) (reduce + (map Math/abs as))]))
       first))

(defn- nonzero-cmds [cmds vals]
  (vec (for [[c v] (map vector cmds vals) :when (not (zero? v))] [c v])))

(defn- minimal-pose
  "Pose run → (f rt u) in the start frame, then (th tv tr)."
  [items]
  (let [[R p] (reduce (fn [[R p] [cmd v]]
                        (if (move-cmds cmd)
                          [R (mapv + p (mapv #(* v %) (mat*v R (local-axis cmd))))]
                          [(mat* R (rot (local-axis cmd) v)) p]))
                      [identity3 [0 0 0]] items)]
    (into (nonzero-cmds [:f :rt :u] (mapv (comp round2 #(dot p %)) [h0 r0 u0]))
          (nonzero-cmds [:th :tv :tr] (best-angles R)))))

(defn- minimal-cp
  "cp run (geometry under a fixed pose: x ↦ Q·x + t, each op left-multiplied,
   cp-f d translating by -d·h, cp-th α turning by -α about up) → (cp-f cp-rt
   cp-u) then (cp-th cp-tv cp-tr). Translations first: G = Q·T(s), s = Qᵀ·t;
   the turns compose extrinsically, so Q⁻¹ = Qᵀ is the intrinsic th→tv→tr form."
  [items]
  (let [[Q t] (reduce (fn [[Q t] [cmd v]]
                        (if (cp-move-cmds cmd)
                          [Q (mapv - t (mapv #(* v %) (local-axis cmd)))]
                          (let [Rr (rot (local-axis cmd) (- v))]
                            [(mat* Rr Q) (mat*v Rr t)])))
                      [identity3 [0 0 0]] items)
        s (mat*v (transpose Q) t)]
    (into (nonzero-cmds [:cp-f :cp-rt :cp-u] (mapv (comp round2 - #(dot s %)) [h0 r0 u0]))
          (nonzero-cmds [:cp-th :cp-tv :cp-tr] (best-angles (transpose Q))))))

(defn- shortest [exact minimal]
  (if (< (count minimal) (count exact)) minimal exact))

(defn- relative-subruns
  "The relative group split at stretches: [[:cp items] | [:stretch items] …]."
  [items]
  (partition-by #(= :scale (first %)) items))

(defn- simplify-run [items]
  (let [pose (filter pose-item? items)
        rel (filter relative-item? items)
        pose-exact (unstack (merge-stack pose))]
    (concat
     (shortest pose-exact (minimal-pose pose))
     (mapcat (fn [sub]
               (let [exact (unstack (merge-stack sub))]
                 (if (= :scale (ffirst sub)) exact (shortest exact (minimal-cp sub)))))
             (relative-subruns rel)))))

;; Verbatim items that are a plain numeric command join the condensation —
;; Simplify is explicit, so a previous session's gestures may be folded too.

(def ^:private verbatim-re
  #"^\(\s*(f|rt|u|th|tv|tr|cp-f|cp-rt|cp-u|cp-th|cp-tv|cp-tr|stretch-f|stretch-rt|stretch-u)\s+(-?\d+(?:\.\d+)?(?:[eE]-?\d+)?)\s*\)$")

(defn- parse-verbatim [item]
  (if-let [[_ c n] (and (string? item) (re-matches verbatim-re (str/trim item)))]
    (let [v (js/parseFloat n)]
      (case c
        "stretch-f" [:scale [v 1 1]]
        "stretch-rt" [:scale [1 v 1]]
        "stretch-u" [:scale [1 1 v]]
        [(keyword c) v]))
    item))

(defn simplify
  "Minimal form of each condensable run: the same placement, rounded to 0.01,
   in as few commands as the algebra allows. Plain numeric verbatim items are
   read and folded in (unless `:fold-verbatim? false`); anything else verbatim
   stays a wall."
  ([commands] (simplify commands {}))
  ([commands {:keys [fold-verbatim?] :or {fold-verbatim? true}}]
   (vec (mapcat (fn [[k x]] (if (= k :wall) [x] (simplify-run x)))
                (segments (if fold-verbatim? (map parse-verbatim commands) commands))))))
